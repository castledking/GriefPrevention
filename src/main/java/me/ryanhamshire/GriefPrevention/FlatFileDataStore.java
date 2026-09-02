/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention;

import com.google.common.io.Files;
import com.griefprevention.claims.ClaimSnapshot;
import com.griefprevention.claims.ClaimTrustSnapshot;
import com.griefprevention.geometry.OrthogonalPoint2i;
import com.griefprevention.persistence.ClaimDocument;
import com.griefprevention.persistence.ClaimDocumentCodec;
import com.griefprevention.persistence.ClaimDocumentFormatException;
import com.griefprevention.persistence.PlayerDataDocument;
import com.griefprevention.persistence.PlayerDataDocumentCodec;
import com.griefprevention.persistence.PlayerDataFormatException;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.Collections;

//manages data stored in the file system
public class FlatFileDataStore extends DataStore
{
    private static final ClaimDocumentCodec CLAIM_DOCUMENT_CODEC = new ClaimDocumentCodec();
    private static final PlayerDataDocumentCodec PLAYER_DATA_DOCUMENT_CODEC = new PlayerDataDocumentCodec();

    private final Set<Long> claimsNeedingRewrite = new HashSet<>();

    // Documents decoded from disk retain fields which are not part of Bukkit's mutable Claim API.
    // They are merged back whenever Paper writes the claim, preserving addon-owned data.
    private final Map<Long, ClaimDocument> loadedClaimDocuments = new ConcurrentHashMap<>();

    //claim data waiting to be serialized and written to disk, keyed by claim file name.
    //only the newest data per claim is kept - older, unwritten data for the same claim is simply replaced.
    private final Map<String, String> pendingClaimWrites = new ConcurrentHashMap<>();

    //serializing and writing claim files happens here so that saving a claim never blocks a tick
    //(on Folia, a region) thread.  a single thread keeps writes and deletes for a claim in order.
    private final ExecutorService claimWriteExecutor = Executors.newSingleThreadExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "GriefPrevention Claim Writer");
        thread.setDaemon(true);
        return thread;
    });

    private final static String claimDataFolderPath = dataLayerFolderPath + File.separator + "ClaimData";
    private final static String nextClaimIdFilePath = claimDataFolderPath + File.separator + "_nextClaimID";
    private final static String schemaVersionFilePath = dataLayerFolderPath + File.separator + "_schemaVersion";

    static boolean hasData()
    {
        File claimsDataFolder = new File(claimDataFolderPath);

        return claimsDataFolder.exists();
    }

    //initialization!
    FlatFileDataStore() throws Exception
    {
        this(true);
    }

    // Test seam for exercising the real claim parser/serializer without touching the process-wide
    // plugins/GriefPreventionData directory.
    FlatFileDataStore(boolean initializeStorage) throws Exception
    {
        if (initializeStorage)
        {
            this.initialize();
        }
    }

    @Override
    void initialize() throws Exception
    {
        //ensure data folders exist
        boolean newDataStore = false;
        File playerDataFolder = new File(playerDataFolderPath);
        File claimDataFolder = new File(claimDataFolderPath);
        if (!playerDataFolder.exists() || !claimDataFolder.exists())
        {
            newDataStore = true;
            playerDataFolder.mkdirs();
            claimDataFolder.mkdirs();
        }

        //if there's no data yet, then anything written will use the schema implemented by this code
        if (newDataStore)
        {
            this.setSchemaVersion(DataStore.latestSchemaVersion);
        }

        //check for legacy subdivision format migration
        if (GriefPrevention.instance.config_claims_legacySubdivisionFormat)
        {
            this.migrateToLegacySubdivisionFormat();
        }

        //load group data into memory
        File[] files = playerDataFolder.listFiles();
        for (File file : files)
        {
            if (!file.isFile()) continue;  //avoids folders

            //all group data files start with a dollar sign.  ignoring the rest, which are player data files.
            if (!file.getName().startsWith("$")) continue;

            String groupName = file.getName().substring(1);
            if (groupName == null || groupName.isEmpty()) continue;  //defensive coding, avoid unlikely cases

            BufferedReader inStream = null;
            try
            {
                inStream = new BufferedReader(new FileReader(file.getAbsolutePath()));
                String line = inStream.readLine();

                int groupBonusBlocks = Integer.parseInt(line);

                this.permissionToBonusBlocksMap.put(groupName, groupBonusBlocks);
            }
            catch (Exception e)
            {
                StringWriter errors = new StringWriter();
                e.printStackTrace(new PrintWriter(errors));
                GriefPrevention.AddLogEntry(errors.toString(), CustomLogEntryTypes.Exception);
            }

            try
            {
                if (inStream != null) inStream.close();
            }
            catch (IOException exception) {}
        }

        //load next claim number from file
        File nextClaimIdFile = new File(nextClaimIdFilePath);
        if (nextClaimIdFile.exists())
        {
            BufferedReader inStream = null;
            try
            {
                inStream = new BufferedReader(new FileReader(nextClaimIdFile.getAbsolutePath()));

                //read the id
                String line = inStream.readLine();

                //try to parse into a long value
                this.nextClaimID = Long.parseLong(line);
            }
            catch (Exception e) { }

            try
            {
                if (inStream != null) inStream.close();
            }
            catch (IOException exception) {}
        }

        //if converting up from schema version 0, rename player data files using UUIDs instead of player names
        //get a list of all the files in the claims data folder
        if (this.getSchemaVersion() == 0)
        {
            files = playerDataFolder.listFiles();
            ArrayList<String> namesToConvert = new ArrayList<>();
            for (File playerFile : files)
            {
                namesToConvert.add(playerFile.getName());
            }

            //resolve and cache as many as possible through various means
            try
            {
                UUIDFetcher fetcher = new UUIDFetcher(namesToConvert);
                fetcher.call();
            }
            catch (Exception e)
            {
                GriefPrevention.AddLogEntry("Failed to resolve a batch of names to UUIDs.  Details:" + e.getMessage());
                e.printStackTrace();
            }

            //rename files
            for (File playerFile : files)
            {
                String currentFilename = playerFile.getName();

                //if corrected casing and a record already exists using the correct casing, skip this one
                String correctedCasing = UUIDFetcher.correctedNames.get(currentFilename);
                if (correctedCasing != null && !currentFilename.equals(correctedCasing))
                {
                    File correctedCasingFile = new File(playerDataFolder.getPath() + File.separator + correctedCasing);
                    if (correctedCasingFile.exists())
                    {
                        continue;
                    }
                }

                //try to convert player name to UUID
                UUID playerID = null;
                try
                {
                    playerID = UUIDFetcher.getUUIDOf(currentFilename);

                    //if successful, rename the file using the UUID
                    if (playerID != null)
                    {
                        playerFile.renameTo(new File(playerDataFolder, playerID.toString()));
                    }
                }
                catch (Exception ex) { }
            }
        }

        //load claims data into memory
        //get a list of all the files in the claims data folder
        files = claimDataFolder.listFiles();

        if (this.getSchemaVersion() <= 1)
        {
            this.loadClaimData_Legacy(files);
        }
        else
        {
            this.loadClaimData(files);
        }

        super.initialize();
    }

    void loadClaimData_Legacy(File[] files) throws Exception
    {
        List<World> validWorlds = Bukkit.getServer().getWorlds();

        for (int i = 0; i < files.length; i++)
        {
            if (files[i].isFile())  //avoids folders
            {
                //skip any file starting with an underscore, to avoid special files not representing land claims
                if (files[i].getName().startsWith("_")) continue;

                //the filename is the claim ID.  try to parse it
                long claimID;

                try
                {
                    claimID = Long.parseLong(files[i].getName());
                }

                //because some older versions used a different file name pattern before claim IDs were introduced,
                //those files need to be "converted" by renaming them to a unique ID
                catch (Exception e)
                {
                    claimID = this.nextClaimID;
                    this.incrementNextClaimID();
                    File newFile = new File(claimDataFolderPath + File.separator + this.nextClaimID);
                    files[i].renameTo(newFile);
                    files[i] = newFile;
                }

                BufferedReader inStream = null;
                String lesserCornerString = "";
                try
                {
                    Claim topLevelClaim = null;

                    inStream = new BufferedReader(new FileReader(files[i].getAbsolutePath()));
                    String line = inStream.readLine();

                    while (line != null)
                    {
                        //skip any SUB:### lines from previous versions
                        if (line.toLowerCase().startsWith("sub:"))
                        {
                            line = inStream.readLine();
                        }

                        //skip any UUID lines from previous versions
                        Matcher match = uuidpattern.matcher(line.trim());
                        if (match.find())
                        {
                            line = inStream.readLine();
                        }

                        //first line is lesser boundary corner location
                        lesserCornerString = line;
                        Location lesserBoundaryCorner = this.locationFromString(lesserCornerString, validWorlds);

                        //second line is greater boundary corner location
                        line = inStream.readLine();
                        Location greaterBoundaryCorner = this.locationFromString(line, validWorlds);

                        //third line is owner name
                        line = inStream.readLine();
                        String ownerName = line;
                        UUID ownerID = null;
                        if (ownerName.isEmpty() || ownerName.startsWith("--"))
                        {
                            ownerID = null;  //administrative land claim or subdivision
                        }
                        else if (this.getSchemaVersion() == 0)
                        {
                            try
                            {
                                ownerID = UUIDFetcher.getUUIDOf(ownerName);
                            }
                            catch (Exception ex)
                            {
                                GriefPrevention.AddLogEntry("Couldn't resolve this name to a UUID: " + ownerName + ".");
                                GriefPrevention.AddLogEntry("  Converted land claim to administrative @ " + lesserBoundaryCorner.toString());
                            }
                        }
                        else
                        {
                            try
                            {
                                ownerID = UUID.fromString(ownerName);
                            }
                            catch (Exception ex)
                            {
                                GriefPrevention.AddLogEntry("Error - this is not a valid UUID: " + ownerName + ".");
                                GriefPrevention.AddLogEntry("  Converted land claim to administrative @ " + lesserBoundaryCorner.toString());
                            }
                        }

                        //fourth line is list of builders
                        line = inStream.readLine();
                        List<String> builderNames = Arrays.asList(line.split(";"));
                        builderNames = this.convertNameListToUUIDList(builderNames);

                        //fifth line is list of players who can access containers
                        line = inStream.readLine();
                        List<String> containerNames = Arrays.asList(line.split(";"));
                        containerNames = this.convertNameListToUUIDList(containerNames);

                        //sixth line is list of players who can use buttons and switches
                        line = inStream.readLine();
                        List<String> accessorNames = Arrays.asList(line.split(";"));
                        accessorNames = this.convertNameListToUUIDList(accessorNames);

                        //seventh line is list of players who can grant permissions
                        line = inStream.readLine();
                        if (line == null) line = "";
                        List<String> managerNames = Arrays.asList(line.split(";"));
                        managerNames = this.convertNameListToUUIDList(managerNames);

                        //skip any remaining extra lines, until the "===" string, indicating the end of this claim or subdivision
                        line = inStream.readLine();
                        while (line != null && !line.contains("==="))
                            line = inStream.readLine();

                        //build a claim instance from those data
                        //if this is the first claim loaded from this file, it's the top level claim
                        if (topLevelClaim == null)
                        {
                            //instantiate
                            topLevelClaim = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, ownerID, builderNames, containerNames, accessorNames, managerNames, claimID);

                            topLevelClaim.modifiedDate = new Date(files[i].lastModified());
                            this.addClaim(topLevelClaim, false);
                        }

                        //otherwise there's already a top level claim, so this must be a subdivision of that top level claim
                        else
                        {
                            Claim subdivision = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, null, builderNames, containerNames, accessorNames, managerNames, null);

                            subdivision.modifiedDate = new Date(files[i].lastModified());
                            subdivision.parent = topLevelClaim;
                            topLevelClaim.children.add(subdivision);
                            subdivision.inDataStore = true;
                        }

                        //move up to the first line in the next subdivision
                        line = inStream.readLine();
                    }

                    inStream.close();
                }

                //if there's any problem with the file's content, log an error message and skip it
                catch (Exception e)
                {
                    if (e.getMessage() != null && e.getMessage().contains("World not found"))
                    {
                        GriefPrevention.AddLogEntry("Failed to load a claim " + files[i].getName() + " because its world isn't loaded (yet?).  Please delete the claim file or contact the GriefPrevention developer with information about which plugin(s) you're using to load or create worlds.  " + lesserCornerString);
                        if (inStream != null) inStream.close();

                    }
                    else
                    {
                        StringWriter errors = new StringWriter();
                        e.printStackTrace(new PrintWriter(errors));
                        GriefPrevention.AddLogEntry("Failed to load claim " + files[i].getName() + ". This usually occurs when your server runs out of storage space, causing any file saves to corrupt. Fix or delete the file found in GriefPreventionData/ClaimData/" + files[i].getName(), CustomLogEntryTypes.Debug, false);
                        GriefPrevention.AddLogEntry(files[i].getName() + " " + errors, CustomLogEntryTypes.Exception);
                    }
                }

                try
                {
                    if (inStream != null) inStream.close();
                }
                catch (IOException exception) {}
            }
        }
    }

    void loadClaimData(File[] files) throws Exception
    {
        ConcurrentHashMap<Claim, Long> orphans = new ConcurrentHashMap<>();
        for (int i = 0; i < files.length; i++)
        {
            if (files[i].isFile())  //avoids folders
            {
                //skip any file starting with an underscore, to avoid special files not representing land claims
                if (files[i].getName().startsWith("_")) continue;

                //delete any which don't end in .yml
                if (!files[i].getName().endsWith(".yml"))
                {
                    files[i].delete();
                    continue;
                }

                //the filename is the claim ID.  try to parse it
                long claimID;

                try
                {
                    claimID = Long.parseLong(files[i].getName().split("\\.")[0]);
                }

                //because some older versions used a different file name pattern before claim IDs were introduced,
                //those files need to be "converted" by renaming them to a unique ID
                catch (Exception e)
                {
                    claimID = this.nextClaimID;
                    this.incrementNextClaimID();
                    File newFile = new File(claimDataFolderPath + File.separator + this.nextClaimID + ".yml");
                    files[i].renameTo(newFile);
                    files[i] = newFile;
                }

                try
                {
                    ArrayList<Long> out_parentID = new ArrayList<>();  //hacky output parameter
                    Claim claim = this.loadClaim(files[i], out_parentID, claimID);
                    if (out_parentID.isEmpty() || out_parentID.get(0) == -1)
                    {
                        this.addClaim(claim, false);
                    }
                    else
                    {
                        orphans.put(claim, out_parentID.get(0));
                    }
                }

                //if there's any problem with the file's content, log an error message and skip it
                catch (Exception e)
                {
                    if (e.getMessage() != null && e.getMessage().contains("World not found"))
                    {
                        GriefPrevention.AddLogEntry("Failed to load a claim (ID:" + claimID + ") because its world isn't loaded (yet?).  If this is not expected, delete this claim.");
                    }
                    else
                    {
                        StringWriter errors = new StringWriter();
                        e.printStackTrace(new PrintWriter(errors));
                        GriefPrevention.AddLogEntry(files[i].getName() + " " + errors, CustomLogEntryTypes.Exception);
                    }
                }
            }
        }

        //link children to parents and clean up legacy orphan subdivision files
        for (Claim child : orphans.keySet())
        {
            Claim parent = this.getClaim(orphans.get(child));
            if (parent != null)
            {
                child.parent = parent;
                this.addClaim(child, false);
                
                // Delete the orphan subdivision file - subdivisions should only be stored
                // inside their parent's YAML file, not as separate files
                if (child.id != null)
                {
                    File orphanFile = new File(claimDataFolderPath + File.separator + child.id + ".yml");
                    if (orphanFile.exists())
                    {
                        // Save the parent claim BEFORE deleting the orphan file to ensure subdivision data is preserved
                        this.writeClaimToStorage(parent);
                        orphanFile.delete();
                        GriefPrevention.AddLogEntry("Cleaned up legacy subdivision file: " + orphanFile.getName() + " (now stored in parent claim " + parent.id + ")");
                    }
                }
            }
        }
    }

    Claim loadClaim(@NotNull File file, ArrayList<Long> out_parentID, long claimID) throws IOException, InvalidConfigurationException, Exception
    {
        @SuppressWarnings("null")
        List<String> lines = Files.readLines(file, StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder();
        for (String line : lines)
        {
            builder.append(line).append('\n');
        }

        return this.loadClaim(builder.toString(), out_parentID, file.lastModified(), claimID, Bukkit.getServer().getWorlds());
    }

    // Carried through the shared claim codec as an unknown field so subdivision admin status
    // survives round trips without changing the cross-platform document schema.
    private static final String ADMIN_SUBDIVISION_FIELD = "Admin Subdivision";
    // Marks a claim whose pvpEnabled was explicitly toggled rather than left at its default.
    // Parked in the cross-platform document's extras so it survives claim moves.
    private static final String PVP_TOGGLE_SET_FIELD = "PvP Toggle Set";

    private List<String> serializeShapeCorners(@NotNull Claim claim)
    {
        List<OrthogonalPoint2i> corners = claim.getShapedCorners();
        if (corners == null || corners.isEmpty())
        {
            return Collections.emptyList();
        }

        List<String> serialized = new ArrayList<>(corners.size());
        for (OrthogonalPoint2i corner : corners)
        {
            serialized.add(corner.x() + "," + corner.z());
        }
        return serialized;
    }

    private List<OrthogonalPoint2i> parseShapeCorners(List<String> serializedCorners)
    {
        if (serializedCorners == null || serializedCorners.isEmpty())
        {
            return Collections.emptyList();
        }

        List<OrthogonalPoint2i> corners = new ArrayList<>(serializedCorners.size());
        for (String entry : serializedCorners)
        {
            String[] parts = entry.split(",");
            if (parts.length != 2)
            {
                continue;
            }

            corners.add(new OrthogonalPoint2i(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())));
        }

        return corners;
    }

    Claim loadClaim(String input, ArrayList<Long> out_parentID, long lastModifiedDate, long claimID, List<World> validWorlds) throws InvalidConfigurationException, Exception
    {
        Claim claim = null;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(input);

        //boundaries
        Location lesserBoundaryCorner = this.locationFromString(yaml.getString("Lesser Boundary Corner"), validWorlds);
        Location greaterBoundaryCorner = this.locationFromString(yaml.getString("Greater Boundary Corner"), validWorlds);

        //owner
        String ownerIdentifier = yaml.getString("Owner");
        UUID ownerID = null;
        if (!ownerIdentifier.isEmpty())
        {
            try
            {
                ownerID = UUID.fromString(ownerIdentifier);
            }
            catch (Exception ex)
            {
                GriefPrevention.AddLogEntry("Error - this is not a valid UUID: " + ownerIdentifier + ".");
                GriefPrevention.AddLogEntry("  Converted land claim to administrative @ " + lesserBoundaryCorner.toString());
            }
        }

        List<String> builders = yaml.getStringList("Builders");

        List<String> containers = yaml.getStringList("Containers");

        List<String> accessors = yaml.getStringList("Accessors");

        List<String> managers = yaml.getStringList("Managers");

        boolean inheritNothing = yaml.getBoolean("inheritNothing");

        // Load inheritNothingForNewSubdivisions setting (default false = inherit)
        boolean inheritNothingForNewSubdivisions = yaml.getBoolean("inheritNothingForNewSubdivisions", false);

        out_parentID.add(yaml.getLong("Parent Claim ID", -1L));

        // Add is3D flag
        boolean is3D = yaml.getBoolean("Is3D", false);

        // Load explosives allowed setting (default false = protected)
        boolean explosivesAllowed = yaml.getBoolean("Explosives Allowed", false);
        boolean witherExplosionsAllowed = yaml.getBoolean("Wither Explosions Allowed", false);
        boolean pvpEnabled = yaml.getBoolean("PvP Enabled", true);

        //instantiate
        claim = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, ownerID, builders, containers, accessors, managers, inheritNothing, claimID, is3D);
        claim.modifiedDate = new Date(yaml.getLong("Modified Date", lastModifiedDate));
        claim.id = claimID;
        claim.areExplosivesAllowed = explosivesAllowed;
        claim.areWitherExplosionsAllowed = witherExplosionsAllowed;
        claim.pvpEnabled = pvpEnabled;
        claim.pvpToggleSet = yaml.getBoolean(PVP_TOGGLE_SET_FIELD, false);
        claim.alertsEnabled = yaml.getBoolean("Alerts Enabled", true);
        claim.setInheritNothingForNewSubdivisions(inheritNothingForNewSubdivisions);
        claim.setShapedCorners(parseShapeCorners(yaml.getStringList("Shape Corners")));
        claim.restoreDeniedPermissions(yaml.getStringList("Denied"));
        for (String neighbor : yaml.getStringList("Neighbors"))
        {
            claim.addNeighbor(neighbor);
        }
        for (String identifier : yaml.getStringList("PvP Trusted"))
        {
            claim.addPvpTrust(identifier);
        }
        for (String identifier : yaml.getStringList("PvE Trusted"))
        {
            claim.addPveTrust(identifier);
        }
        claim.allowAllNeighbors = yaml.getBoolean("allowAllNeighbors", false);

        ConfigurationSection childrenSection = yaml.getConfigurationSection("Children");
        if (childrenSection != null)
        {
            for (String childKey : childrenSection.getKeys(false))
            {
                ConfigurationSection childYaml = childrenSection.getConfigurationSection(childKey);
                if (childYaml == null) continue;

                Claim child = deserializeChild(childYaml, claim, validWorlds);
                if (child != null)
                {
                    claim.children.add(child);
                }
            }
        }

        this.captureClaimDocuments(input, claimID, lastModifiedDate);

        if (claim.parent == null && claim.id != null && this.claimsNeedingRewrite.remove(claim.id))
        {
            this.writeClaimToStorage(claim);
        }

        return claim;
    }

    private Claim deserializeChild(ConfigurationSection section, Claim parent, List<World> validWorlds) throws Exception
    {
        String lesserString = section.getString("Lesser Boundary Corner");
        String greaterString = section.getString("Greater Boundary Corner");
        if (lesserString == null || greaterString == null)
        {
            return null;
        }

        Location lesserBoundaryCorner = this.locationFromString(lesserString, validWorlds);
        Location greaterBoundaryCorner = this.locationFromString(greaterString, validWorlds);

        String ownerIdentifier = section.getString("Owner", "");
        UUID ownerID = null;
        if (!ownerIdentifier.isEmpty())
        {
            try
            {
                ownerID = UUID.fromString(ownerIdentifier);
            }
            catch (IllegalArgumentException ignored) {}
        }

        List<String> builders = section.getStringList("Builders");
        List<String> containers = section.getStringList("Containers");
        List<String> accessors = section.getStringList("Accessors");
        List<String> managers = section.getStringList("Managers");

        boolean inheritNothing = section.getBoolean("inheritNothing");
        boolean is3D = section.getBoolean("Is3D", false);
        boolean explosivesAllowed = section.getBoolean("Explosives Allowed", false);
        boolean witherExplosionsAllowed = section.getBoolean("Wither Explosions Allowed", false);
        boolean pvpEnabled = section.getBoolean("PvP Enabled", true);

        Long childID = null;
        if (section.contains("Claim ID"))
        {
            String idString = section.getString("Claim ID");
            if (idString != null && !idString.isEmpty())
            {
                try
                {
                    childID = Long.parseLong(idString);
                }
                catch (NumberFormatException ignored) {}
            }
        }

        Claim child = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, ownerID, builders, containers, accessors, managers, inheritNothing, childID, is3D);
        child.parent = parent;
        child.inDataStore = true;
        child.areExplosivesAllowed = explosivesAllowed;
        child.areWitherExplosionsAllowed = witherExplosionsAllowed;
        child.pvpEnabled = pvpEnabled;
        child.pvpToggleSet = section.getBoolean(PVP_TOGGLE_SET_FIELD, false);
        child.alertsEnabled = section.getBoolean("Alerts Enabled", true);
        child.setInheritNothingForNewSubdivisions(
                section.getBoolean("inheritNothingForNewSubdivisions", false)
        );
        child.setShapedCorners(parseShapeCorners(section.getStringList("Shape Corners")));
        child.setAdminSubdivision(section.getBoolean(ADMIN_SUBDIVISION_FIELD, false));
        child.restoreDeniedPermissions(section.getStringList("Denied"));
        for (String neighbor : section.getStringList("Neighbors"))
        {
            child.addNeighbor(neighbor);
        }
        for (String identifier : section.getStringList("PvP Trusted"))
        {
            child.addPvpTrust(identifier);
        }
        for (String identifier : section.getStringList("PvE Trusted"))
        {
            child.addPveTrust(identifier);
        }
        child.allowAllNeighbors = section.getBoolean("allowAllNeighbors", false);

        if (!child.getSubclaimRestrictions())
        {
            Claim root = parent;
            while (root != null && root.parent != null)
            {
                root = root.parent;
            }
            if (root != null && root.id != null)
            {
                this.claimsNeedingRewrite.add(root.id);
            }
        }

        long modifiedTime = section.getLong("Modified Date", parent.modifiedDate != null ? parent.modifiedDate.getTime() : System.currentTimeMillis());
        child.modifiedDate = new Date(modifiedTime);

        ConfigurationSection grandchildrenSection = section.getConfigurationSection("Children");
        if (grandchildrenSection != null)
        {
            for (String grandChildKey : grandchildrenSection.getKeys(false))
            {
                ConfigurationSection grandChildYaml = grandchildrenSection.getConfigurationSection(grandChildKey);
                if (grandChildYaml == null) continue;

                Claim grandChild = deserializeChild(grandChildYaml, child, validWorlds);
                if (grandChild != null)
                {
                    child.children.add(grandChild);
                }
            }
        }

        return child;
    }

    String getYamlForClaim(Claim claim)
    {
        if (claim.parent == null)
        {
            List<ClaimDocument> documents = new ArrayList<>();
            this.collectClaimDocuments(claim, documents);
            try
            {
                String encoded = CLAIM_DOCUMENT_CODEC.encodeTree(documents.get(0), documents);
                for (ClaimDocument document : documents)
                {
                    Long id = document.snapshot().id();
                    if (id != null)
                    {
                        this.loadedClaimDocuments.put(id, document);
                    }
                }
                return encoded;
            }
            catch (ClaimDocumentFormatException exception)
            {
                throw new IllegalStateException(
                        "Refusing to serialize invalid claim graph rooted at " + claim.id + ".",
                        exception
                );
            }
        }

        // The opt-in legacy subdivision migration intentionally writes a child as a standalone
        // orphan file. The shared codec only emits complete root trees, so retain the upstream
        // serializer for that compatibility-only path.
        YamlConfiguration yaml = new YamlConfiguration();
        populateYamlForClaim(claim, yaml);
        return yaml.saveToString();
    }

    private void captureClaimDocuments(String input, long fallbackClaimId, long fallbackModifiedDate)
    {
        try
        {
            for (ClaimDocument document : CLAIM_DOCUMENT_CODEC.decodeTree(
                    input,
                    fallbackClaimId,
                    fallbackModifiedDate
            ))
            {
                Long id = document.snapshot().id();
                if (id != null)
                {
                    this.loadedClaimDocuments.put(id, document);
                }
            }
        }
        catch (ClaimDocumentFormatException ignored)
        {
            // Preserve upstream's parser behavior for files outside the shared codec's strict
            // contract. Supported cross-platform files always take the lossless path above.
        }
    }

    private void collectClaimDocuments(Claim claim, List<ClaimDocument> output)
    {
        ClaimDocument previous = claim.id == null ? null : this.loadedClaimDocuments.get(claim.id);
        ClaimTrustSnapshot rawTrust = claim.getTrustSnapshot();
        // Manage trust is its own track and never appears in the interaction map, so both are
        // written as-is. Dropping managers from the map here would erase the build/container/access
        // trust of anyone who is also a manager.
        ClaimTrustSnapshot trust = new ClaimTrustSnapshot(
                claim.ownerID,
                rawTrust.permissionsByIdentifier(),
                rawTrust.managerIdentifiers(),
                rawTrust.neighborIdentifiers(),
                rawTrust.deniedIdentifiers(),
                claim.getPvpTrustedIdentifiers(),
                claim.getPveTrustedIdentifiers()
        );

        ClaimSnapshot runtimeSnapshot = claim.getSnapshot();
        ClaimSnapshot persistedSnapshot = new ClaimSnapshot(
                runtimeSnapshot.id(),
                runtimeSnapshot.worldKey(),
                claim.ownerID,
                runtimeSnapshot.parentId(),
                runtimeSnapshot.bounds(),
                runtimeSnapshot.threeDimensional(),
                runtimeSnapshot.subdivision()
        );

        List<OrthogonalPoint2i> shapeCorners = claim.getShapedCorners();
        if (shapeCorners == null)
        {
            shapeCorners = Collections.emptyList();
        }
        long modifiedDate = claim.modifiedDate == null
                ? System.currentTimeMillis()
                : claim.modifiedDate.getTime();
        Map<String, Object> extraFields = new LinkedHashMap<>(
                previous == null ? Collections.<String, Object>emptyMap() : previous.extraFields()
        );
        if (claim.isAdminSubdivision())
        {
            extraFields.put(ADMIN_SUBDIVISION_FIELD, Boolean.TRUE);
        }
        else
        {
            extraFields.remove(ADMIN_SUBDIVISION_FIELD);
        }
        if (claim.pvpToggleSet)
        {
            extraFields.put(PVP_TOGGLE_SET_FIELD, Boolean.TRUE);
        }
        else
        {
            extraFields.remove(PVP_TOGGLE_SET_FIELD);
        }
        ClaimDocument document = new ClaimDocument(
                persistedSnapshot,
                trust,
                shapeCorners,
                claim.getSubclaimRestrictions(),
                claim.getInheritNothingForNewSubdivisions(),
                claim.areExplosivesAllowed,
                claim.areWitherExplosionsAllowed,
                claim.allowAllNeighbors,
                claim.pvpEnabled,
                claim.alertsEnabled,
                modifiedDate,
                previous == null ? (claim.id == null ? null : String.valueOf(claim.id)) : previous.storageKey(),
                extraFields
        );
        output.add(document);

        for (Claim child : claim.children)
        {
            if (child != null && child.inDataStore)
            {
                this.collectClaimDocuments(child, output);
            }
        }
    }

    private void forgetClaimDocuments(Claim claim)
    {
        if (claim.id != null)
        {
            this.loadedClaimDocuments.remove(claim.id);
        }
        for (Claim child : claim.children)
        {
            if (child != null)
            {
                this.forgetClaimDocuments(child);
            }
        }
    }

    private void populateYamlForClaim(Claim claim, ConfigurationSection section)
    {
        section.set("Claim ID", claim.id == null ? null : String.valueOf(claim.id));
        section.set("Lesser Boundary Corner", this.locationToString(claim.lesserBoundaryCorner));
        section.set("Greater Boundary Corner", this.locationToString(claim.greaterBoundaryCorner));

        String ownerID = claim.ownerID == null ? "" : claim.ownerID.toString();
        section.set("Owner", ownerID);

        ArrayList<String> builders = new ArrayList<>();
        ArrayList<String> containers = new ArrayList<>();
        ArrayList<String> accessors = new ArrayList<>();
        ArrayList<String> managers = new ArrayList<>();
        claim.getPermissions(builders, containers, accessors, managers);

        section.set("Builders", builders);
        section.set("Containers", containers);
        section.set("Accessors", accessors);
        section.set("Managers", managers);
        section.set("Neighbors", claim.getManualNeighbors());
        section.set("PvP Trusted", new ArrayList<>(claim.getPvpTrustedIdentifiers()));
        section.set("PvE Trusted", new ArrayList<>(claim.getPveTrustedIdentifiers()));

        // A revoked inherited grant is stored as a deny entry, not as missing trust. Omitting it
        // here would let the next load hand that trust back.
        Set<String> denied = claim.getTrustSnapshot().deniedIdentifiers();
        if (!denied.isEmpty())
        {
            section.set("Denied", new ArrayList<>(denied));
        }

        section.set("Parent Claim ID", claim.parent == null ? -1L : claim.parent.id);
        section.set("inheritNothing", claim.getSubclaimRestrictions());
        section.set("inheritNothingForNewSubdivisions", claim.getInheritNothingForNewSubdivisions());
        section.set("allowAllNeighbors", claim.allowAllNeighbors);
        section.set("Is3D", claim.is3D());
        if (claim.isShaped())
        {
            section.set("Shape Corners", serializeShapeCorners(claim));
        }
        if (claim.isAdminSubdivision())
        {
            section.set(ADMIN_SUBDIVISION_FIELD, true);
        }
        section.set("Explosives Allowed", claim.areExplosivesAllowed);
        section.set("Wither Explosions Allowed", claim.areWitherExplosionsAllowed);
        section.set("PvP Enabled", claim.pvpEnabled);
        if (claim.pvpToggleSet)
        {
            section.set(PVP_TOGGLE_SET_FIELD, true);
        }
        section.set("Alerts Enabled", claim.alertsEnabled);
        section.set("Modified Date", claim.modifiedDate != null ? claim.modifiedDate.getTime() : System.currentTimeMillis());

        ArrayList<Claim> persistedChildren = new ArrayList<>();
        for (Claim child : claim.children)
        {
            if (child != null && child.inDataStore)
            {
                persistedChildren.add(child);
            }
        }

        if (!persistedChildren.isEmpty())
        {
            ConfigurationSection childrenSection = section.createSection("Children");
            int index = 0;
            for (Claim child : persistedChildren)
            {
                String key = child.id != null ? String.valueOf(child.id) : String.valueOf(index++);
                ConfigurationSection childSection = childrenSection.createSection(key);
                populateYamlForClaim(child, childSection);
            }
        }
    }

    @Override
    @SuppressWarnings("null")
    synchronized void writeClaimToStorage(Claim claim)
    {
        // Subdivisions are stored inside their root parent's YAML file.
        // If this is a subdivision, save the root parent instead, which includes
        // this subdivision in its Children section.
        Claim root = claim;
        while (root.parent != null)
        {
            root = root.parent;
        }

        String claimID = String.valueOf(root.id);

        //read the claim data on the calling thread, where it can't change underneath us, but leave
        //the expensive YAML serialization and the disk write to the writer thread
        this.queueClaimWrite(claimID, this.getYamlForClaim(root));
    }

    //subdivisions live in their root claim's file, so a group of claims only needs one write per root
    //involved - writing once per claim would re-serialize the same tree over and over.
    @Override
    synchronized public void saveClaims(Collection<Claim> claims)
    {
        Set<Claim> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Claim claim : claims)
        {
            if (claim == null) continue;

            //subdivisions still need an ID of their own, since it's stored with their data
            this.assignClaimID(claim);

            Claim root = claim;
            while (root.parent != null)
            {
                root = root.parent;
            }
            roots.add(root);
        }

        for (Claim root : roots)
        {
            this.saveClaim(root);
        }
    }

    //hands a claim's data to the writer thread, superseding any data for the same claim that hasn't been written yet
    private void queueClaimWrite(String claimID, String yaml)
    {
        this.pendingClaimWrites.put(claimID, yaml);

        try
        {
            this.claimWriteExecutor.execute(() -> this.flushClaimWrite(claimID));
        }
        catch (RejectedExecutionException e)
        {
            //writer is shut down (server stopping) - write on this thread instead so nothing is lost
            this.flushClaimWrite(claimID);
        }
    }

    private void flushClaimWrite(String claimID)
    {
        String yaml = this.pendingClaimWrites.remove(claimID);

        //an earlier queued write already wrote this claim's newest data, or it was deleted since
        if (yaml == null) return;

        try
        {
            //open the claim's file
            File claimFile = new File(claimDataFolderPath + File.separator + claimID + ".yml");
            claimFile.createNewFile();
            byte[] yamlBytes = yaml.getBytes(StandardCharsets.UTF_8);
            Files.write(yamlBytes, claimFile);
        }

        //if any problem, log it
        catch (Exception e)
        {
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            GriefPrevention.AddLogEntry(claimID + " " + errors, CustomLogEntryTypes.Exception);
        }
    }

    //deletes a claim from the file system
    @Override
    synchronized void deleteClaimFromSecondaryStorage(Claim claim)
    {
        this.forgetClaimDocuments(claim);
        boolean debugEnabled = GriefPrevention.instance.config_logs_debugEnabled;
        
        // For subclaims, rewrite the parent file
        if (claim.parent != null)
        {
            Claim root = claim.parent;
            while (root.parent != null)
            {
                root = root.parent;
            }
            
            // Remove the claim from its parent's children list
            claim.parent.children.remove(claim);
            
            if (debugEnabled) {
                String rootFile = claimDataFolderPath + File.separator + root.id + ".yml";
                GriefPrevention.AddLogEntry("[DEBUG] Storage: Subdivision " + claim.id 
                    + " removed from parent " + claim.parent.id 
                    + ", updating root file: " + rootFile, CustomLogEntryTypes.Debug, true);
            }
            
            // Save the parent to update the YAML
            this.writeClaimToStorage(root);
        }

        // Always try to delete the claim file if it exists
        // (in case it's a top-level claim or the file wasn't properly cleaned up)
        String claimID = String.valueOf(claim.id);
        boolean isTopLevel = claim.parent == null;

        //drop any data still waiting to be written for this claim so it can't recreate the file
        this.pendingClaimWrites.remove(claimID);

        Runnable delete = () -> this.deleteClaimFile(claimID, debugEnabled, isTopLevel);
        try
        {
            //queued behind any pending writes so ordering is preserved
            this.claimWriteExecutor.execute(delete);
        }
        catch (RejectedExecutionException e)
        {
            //writer is shut down (server stopping) - delete on this thread instead
            delete.run();
        }
    }

    private void deleteClaimFile(String claimID, boolean debugEnabled, boolean isTopLevel)
    {
        File claimFile = new File(claimDataFolderPath + File.separator + claimID + ".yml");
        if (claimFile.exists())
        {
            if (claimFile.delete())
            {
                if (debugEnabled) {
                    GriefPrevention.AddLogEntry("[DEBUG] Storage: Deleted claim file: " + claimFile.getAbsolutePath(),
                        CustomLogEntryTypes.Debug, true);
                }
            }
            else
            {
                GriefPrevention.AddLogEntry("Error: Unable to delete claim file \"" + claimFile.getAbsolutePath() + "\".");
            }
        }
        else if (debugEnabled && isTopLevel)
        {
            GriefPrevention.AddLogEntry("[DEBUG] Storage: No file to delete for claim " + claimID
                + " (file did not exist: " + claimFile.getAbsolutePath() + ")", CustomLogEntryTypes.Debug, true);
        }
    }

    @Override
    synchronized PlayerData getPlayerDataFromStorage(UUID playerID)
    {
        File playerFile = new File(playerDataFolderPath + File.separator + playerID.toString());

        PlayerData playerData = new PlayerData();
        playerData.playerID = playerID;

        //if it exists as a file, read the file
        if (playerFile.exists())
        {
            boolean needRetry = false;
            int retriesRemaining = 5;
            Exception latestException = null;
            do
            {
                try
                {
                    needRetry = false;

                    // Decode with the same platform-neutral codec used by Fabric. The first and
                    // fourth upstream lines remain intentionally ignored.
                    String input = new String(Files.toByteArray(playerFile), StandardCharsets.UTF_8);
                    PlayerData decoded = decodePlayerData(playerID, input);
                    playerData.setAccruedClaimBlocks(decoded.getAccruedClaimBlocks());
                    playerData.setBonusClaimBlocks(decoded.getBonusClaimBlocks());
                }

                //if there's any problem with the file's content, retry up to 5 times with 5 milliseconds between
                catch (Exception e)
                {
                    latestException = e;
                    needRetry = true;
                    retriesRemaining--;
                }

                try
                {
                    if (needRetry) Thread.sleep(5);
                }
                catch (InterruptedException exception) {}

            } while (needRetry && retriesRemaining >= 0);

            //if last attempt failed, log information about the problem
            if (needRetry)
            {
                // Flag this PlayerData as having failed to load so the in-memory
                // copy is not later written back over the (still-intact, on-disk)
                // record. See upstream issues #2589 / #666.
                playerData.loadFailedFromStorage = true;
                StringWriter errors = new StringWriter();
                if (latestException != null) {
                    latestException.printStackTrace(new PrintWriter(errors));
                }
                GriefPrevention.AddLogEntry("Failed to load PlayerData for " + playerID + ". This usually occurs when your server runs out of storage space, causing any file saves to corrupt. Fix or delete the file in GriefPrevetionData/PlayerData/" + playerID, CustomLogEntryTypes.Debug, false);
                GriefPrevention.AddLogEntry(playerID + " " + errors, CustomLogEntryTypes.Exception);
                GriefPrevention.AddLogEntry(
                        "Saves for " + playerID + " will be skipped this session to protect the on-disk record.",
                        CustomLogEntryTypes.Exception, false);
            }
        }

        return playerData;
    }

    //saves changes to player data.  MUST be called after you're done making changes, otherwise a reload will lose them
    @Override
    @SuppressWarnings("null")
    public void overrideSavePlayerData(UUID playerID, PlayerData playerData)
    {
        //never save data for the "administrative" account.  null for claim owner ID indicates administrative account
        if (playerID == null) return;

        // Refuse to save a PlayerData that failed to load. Writing now would
        // overwrite the player's existing on-disk file with default zeros.
        if (playerData != null && playerData.loadFailedFromStorage)
        {
            GriefPrevention.AddLogEntry(
                    "Refusing to save PlayerData for " + playerID + " because the most recent storage read failed. The on-disk record is being preserved.",
                    CustomLogEntryTypes.Exception, false);
            return;
        }

        try
        {
            //write data to file
            File playerDataFile = new File(playerDataFolderPath + File.separator + playerID);
            Files.write(encodePlayerData(playerData).getBytes(StandardCharsets.UTF_8), playerDataFile);
        }

        //if any problem, log it
        catch (Exception e)
        {
            GriefPrevention.AddLogEntry("GriefPrevention: Unexpected exception saving data for player \"" + playerID + "\": " + e.getMessage());
            e.printStackTrace();
        }
    }

    static PlayerData decodePlayerData(UUID playerID, String input) throws PlayerDataFormatException
    {
        PlayerDataDocument document = PLAYER_DATA_DOCUMENT_CODEC.decode(input);
        PlayerData playerData = new PlayerData();
        playerData.playerID = playerID;
        playerData.setAccruedClaimBlocks(document.accruedClaimBlocks());
        playerData.setBonusClaimBlocks(document.bonusClaimBlocks());
        return playerData;
    }

    static String encodePlayerData(PlayerData playerData)
    {
        return PLAYER_DATA_DOCUMENT_CODEC.encode(new PlayerDataDocument(
                playerData.getAccruedClaimBlocks(),
                playerData.getBonusClaimBlocks()
        ));
    }

    @Override
    synchronized void incrementNextClaimID()
    {
        //increment in memory
        this.nextClaimID++;

        BufferedWriter outStream = null;

        try
        {
            //open the file and write the new value
            File nextClaimIdFile = new File(nextClaimIdFilePath);
            nextClaimIdFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(nextClaimIdFile));

            outStream.write(String.valueOf(this.nextClaimID));
        }

        //if any problem, log it
        catch (Exception e)
        {
            GriefPrevention.AddLogEntry("Unexpected exception saving next claim ID: " + e.getMessage());
            e.printStackTrace();
        }

        //close the file
        try
        {
            if (outStream != null) outStream.close();
        }
        catch (IOException exception) {}
    }

    //grants a group (players with a specific permission) bonus claim blocks as long as they're still members of the group
    @Override
    synchronized void saveGroupBonusBlocks(String groupName, int currentValue)
    {
        //write changes to file to ensure they don't get lost
        BufferedWriter outStream = null;
        try
        {
            //open the group's file
            File groupDataFile = new File(playerDataFolderPath + File.separator + "$" + groupName);
            groupDataFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(groupDataFile));

            //first line is number of bonus blocks
            outStream.write(String.valueOf(currentValue));
            outStream.newLine();
        }

        //if any problem, log it
        catch (Exception e)
        {
            GriefPrevention.AddLogEntry("Unexpected exception saving data for group \"" + groupName + "\": " + e.getMessage());
        }

        try
        {
            //close the file
            if (outStream != null)
            {
                outStream.close();
            }
        }
        catch (IOException exception) {}
    }

    synchronized void migrateData(DatabaseDataStore databaseStore)
    {
        //the claim data folder is renamed at the end of this, so don't leave writes queued against it
        this.flushPendingClaimWrites();

        //migrate claims
        for (Claim claim : this.claims)
        {
            databaseStore.addClaim(claim, true);
            for (Claim child : claim.children)
            {
                databaseStore.addClaim(child, true);
            }
        }

        //migrate groups
        for (Map.Entry<String, Integer> groupEntry : this.permissionToBonusBlocksMap.entrySet())
        {
            databaseStore.saveGroupBonusBlocks(groupEntry.getKey(), groupEntry.getValue());
        }

        //migrate players
        File playerDataFolder = new File(playerDataFolderPath);
        File[] files = playerDataFolder.listFiles();
        for (File file : files)
        {
            if (!file.isFile()) continue;  //avoids folders
            if (file.isHidden()) continue; //avoid hidden files, which are likely not created by GriefPrevention

            //all group data files start with a dollar sign.  ignoring those, already handled above
            if (file.getName().startsWith("$")) continue;

            //ignore special files
            if (file.getName().startsWith("_")) continue;
            if (file.getName().endsWith(".ignore")) continue;

            UUID playerID = UUID.fromString(file.getName());
            databaseStore.savePlayerData(playerID, this.getPlayerData(playerID));
            this.clearCachedPlayerData(playerID);
        }

        //migrate next claim ID
        if (this.nextClaimID > databaseStore.nextClaimID)
        {
            databaseStore.setNextClaimID(this.nextClaimID);
        }

        //rename player and claim data folders so the migration won't run again
        int i = 0;
        File claimsBackupFolder;
        File playersBackupFolder;
        do
        {
            String claimsFolderBackupPath = claimDataFolderPath;
            if (i > 0) claimsFolderBackupPath += String.valueOf(i);
            claimsBackupFolder = new File(claimsFolderBackupPath);

            String playersFolderBackupPath = playerDataFolderPath;
            if (i > 0) playersFolderBackupPath += String.valueOf(i);
            playersBackupFolder = new File(playersFolderBackupPath);
            i++;
        } while (claimsBackupFolder.exists() || playersBackupFolder.exists());

        File claimsFolder = new File(claimDataFolderPath);
        File playersFolder = new File(playerDataFolderPath);

        claimsFolder.renameTo(claimsBackupFolder);
        playersFolder.renameTo(playersBackupFolder);

        GriefPrevention.AddLogEntry("Backed your file system data up to " + claimsBackupFolder.getName() + " and " + playersBackupFolder.getName() + ".");
        GriefPrevention.AddLogEntry("If your migration encountered any problems, you can restore those data with a quick copy/paste.");
        GriefPrevention.AddLogEntry("When you're satisfied that all your data have been safely migrated, consider deleting those folders.");
    }

    @Override
    synchronized void close()
    {
        //let the writer thread finish what's already queued, then make sure nothing is left unwritten
        this.claimWriteExecutor.shutdown();
        try
        {
            if (!this.claimWriteExecutor.awaitTermination(30, TimeUnit.SECONDS))
            {
                this.claimWriteExecutor.shutdownNow();
            }
        }
        catch (InterruptedException e)
        {
            this.claimWriteExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        this.flushPendingClaimWrites();
    }

    //writes any claim data still waiting on the writer thread using the calling thread
    private void flushPendingClaimWrites()
    {
        for (String claimID : new ArrayList<>(this.pendingClaimWrites.keySet()))
        {
            this.flushClaimWrite(claimID);
        }
    }

    @Override
    int getSchemaVersionFromStorage()
    {
        File schemaVersionFile = new File(schemaVersionFilePath);
        if (schemaVersionFile.exists())
        {
            BufferedReader inStream = null;
            int schemaVersion = 0;
            try
            {
                inStream = new BufferedReader(new FileReader(schemaVersionFile.getAbsolutePath()));

                //read the version number
                String line = inStream.readLine();

                //try to parse into an int value
                schemaVersion = Integer.parseInt(line);
            }
            catch (Exception e) { }

            try
            {
                if (inStream != null) inStream.close();
            }
            catch (IOException exception) {}

            return schemaVersion;
        }
        else
        {
            this.updateSchemaVersionInStorage(0);
            return 0;
        }
    }

    @Override
    void updateSchemaVersionInStorage(int versionToSet)
    {
        BufferedWriter outStream = null;

        try
        {
            //open the file and write the new value
            File schemaVersionFile = new File(schemaVersionFilePath);
            schemaVersionFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(schemaVersionFile));

            outStream.write(String.valueOf(versionToSet));
        }

        //if any problem, log it
        catch (Exception e)
        {
            GriefPrevention.AddLogEntry("Unexpected exception saving schema version: " + e.getMessage());
        }

        //close the file
        try
        {
            if (outStream != null) outStream.close();
        }
        catch (IOException exception) {}

    }

    /**
     * Migrates subdivisions from nested Children: format to original GP format (separate files)
     * Only processes 2D subdivisions, ignores 3D subdivisions
     */
    @SuppressWarnings("null")
    private void migrateToLegacySubdivisionFormat()
    {
        GriefPrevention.AddLogEntry("Starting migration to legacy subdivision format...");
        
        File claimDataFolder = new File(claimDataFolderPath);
        File[] claimFiles = claimDataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        
        if (claimFiles == null) return;
        
        int migratedCount = 0;
        int skippedCount = 0;
        
        for (File claimFile : claimFiles)
        {
            try
            {
                // Read the claim file
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(claimFile);
                
                // Check if this claim has Children
                ConfigurationSection childrenSection = yaml.getConfigurationSection("Children");
                if (childrenSection == null) continue;
                
                // Load the parent claim
                List<World> validWorlds = Bukkit.getServer().getWorlds();
                Claim parentClaim = this.loadClaim(claimFile, new ArrayList<>(), Long.parseLong(claimFile.getName().replace(".yml", "")));
                
                if (parentClaim == null) continue;
                
                // Process each child
                for (String childKey : childrenSection.getKeys(false))
                {
                    ConfigurationSection childYaml = childrenSection.getConfigurationSection(childKey);
                    if (childYaml == null) continue;
                    
                    Claim child = deserializeChild(childYaml, parentClaim, validWorlds);
                    if (child == null) continue;
                    
                    // Skip 3D subdivisions - only migrate 2D
                    if (child.is3D())
                    {
                        skippedCount++;
                        continue;
                    }
                    
                    // Create separate file for the subdivision
                    long childId = this.nextClaimID;
                    this.incrementNextClaimID();
                    
                    child.id = childId;
                    child.parent = parentClaim;
                    child.inDataStore = true;
                    
                    // Write child to separate file
                    String childYamlContent = this.getYamlForClaim(child);
                    File childFile = new File(claimDataFolderPath + File.separator + childId + ".yml");
                    Files.write(childYamlContent.getBytes(StandardCharsets.UTF_8), childFile);
                    
                    migratedCount++;
                }
                
                // Remove Children section from parent file
                yaml.set("Children", null);
                String parentYaml = yaml.saveToString();
                Files.write(parentYaml.getBytes(StandardCharsets.UTF_8), claimFile);
                
            }
            catch (Exception e)
            {
                GriefPrevention.AddLogEntry("Error migrating claim file " + claimFile.getName() + ": " + e.getMessage(), CustomLogEntryTypes.Exception);
            }
        }
        
        GriefPrevention.AddLogEntry("Migration complete. Migrated " + migratedCount + " subdivisions, skipped " + skippedCount + " (3D subdivisions).");
        GriefPrevention.AddLogEntry("Please restart the server to complete the migration.");
    }
}
