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

import com.griefprevention.persistence.ClaimDataSchema;

import com.google.common.io.Files;
import com.griefprevention.claims.ClaimSnapshot;
import com.griefprevention.claims.ClaimSnapshotIndex;
import com.griefprevention.geometry.MergeCorridor;
import com.griefprevention.geometry.OrthogonalEdge2i;
import com.griefprevention.geometry.OrthogonalPoint2i;
import com.griefprevention.geometry.OrthogonalPolygon;
import com.griefprevention.visualization.BoundaryVisualization;
import com.griefprevention.visualization.VisualizationType;
import me.ryanhamshire.GriefPrevention.util.SchedulerUtil;
import me.ryanhamshire.GriefPrevention.util.TaskHandle;
import me.ryanhamshire.GriefPrevention.events.ClaimCreatedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimDeletedEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimExtendEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimResizeEvent;
import me.ryanhamshire.GriefPrevention.events.PreDeleteClaimEvent;
import me.ryanhamshire.GriefPrevention.events.ClaimTransferEvent;
import me.ryanhamshire.GriefPrevention.util.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

//singleton class which manages all GriefPrevention data (except for config options)
public abstract class DataStore {

    // Optional WorldGuard bridge. Null means WorldGuard is absent or incompatible.
    private WorldGuardWrapper worldGuard;

    // in-memory cache for player data
    protected ConcurrentHashMap<UUID, PlayerData> playerNameToPlayerDataMap = new ConcurrentHashMap<>();

    // in-memory cache for group (permission-based) data
    protected ConcurrentHashMap<String, Integer> permissionToBonusBlocksMap = new ConcurrentHashMap<>();

    // in-memory cache for claim data
    ArrayList<Claim> claims = new ArrayList<>();
    // claim id to claim cache
    public final Map<Long, Claim> claimIDMap = new ConcurrentHashMap<>();
    ConcurrentHashMap<Long, ArrayList<Claim>> chunksToClaimsMap = new ConcurrentHashMap<>();
    private final ClaimSnapshotIndex claimSnapshotIndex = new ClaimSnapshotIndex();

    // In-memory siege cooldowns; active siege state intentionally does not survive restart.
    private final HashMap<String, Long> siegeCooldownRemaining = new HashMap<>();

    // in-memory cache for messages, keyed by locale code
    private final Map<String, String[]> messagesByLocale = new HashMap<>();

    // pattern for unique user identifiers (UUIDs)
    protected final static Pattern uuidpattern = Pattern
            .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    // next claim ID
    Long nextClaimID = (long) 0;

    // path information, for where stuff stored on disk is well... stored
    protected final static String dataLayerFolderPath = "plugins" + File.separator + "GriefPreventionData";
    final static String playerDataFolderPath = dataLayerFolderPath + File.separator + "PlayerData";
    final static String configFilePath = dataLayerFolderPath + File.separator + "config.yml";
    final static String messagesFilePath = dataLayerFolderPath + File.separator + "messages.yml";
    // locale files live alongside config.yml in the data folder
    public static final String languageFolderPath = dataLayerFolderPath;

    final static String softMuteFilePath = dataLayerFolderPath + File.separator + "softMute.txt";
    final static String bannedWordsFilePath = dataLayerFolderPath + File.separator + "bannedWords.txt";

    // the latest version of the data schema implemented here
    protected static final int latestSchemaVersion = ClaimDataSchema.CURRENT_VERSION;

    // reading and writing the schema version to the data store
    abstract int getSchemaVersionFromStorage();

    abstract void updateSchemaVersionInStorage(int versionToSet);

    // current version of the schema of data in secondary storage
    private int currentSchemaVersion = -1; // -1 means not determined yet

    // video links
    public static final String SURVIVAL_VIDEO_URL = "" + ChatColor.DARK_AQUA + ChatColor.UNDERLINE + "bit.ly/mcgpuser"
            + ChatColor.RESET;
    public static final String CREATIVE_VIDEO_URL = "" + ChatColor.DARK_AQUA + ChatColor.UNDERLINE + "bit.ly/mcgpcrea"
            + ChatColor.RESET;
    public static final String SUBDIVISION_VIDEO_URL = "" + ChatColor.DARK_AQUA + ChatColor.UNDERLINE + "bit.ly/mcgpsub"
            + ChatColor.RESET;

    // list of UUIDs which are soft-muted
    ConcurrentHashMap<UUID, Boolean> softMuteMap = new ConcurrentHashMap<>();

    protected int getSchemaVersion() {
        if (this.currentSchemaVersion >= 0) {
            return this.currentSchemaVersion;
        } else {
            this.currentSchemaVersion = this.getSchemaVersionFromStorage();
            return this.currentSchemaVersion;
        }
    }

    protected void setSchemaVersion(int versionToSet) {
        this.currentSchemaVersion = versionToSet;
        this.updateSchemaVersionInStorage(versionToSet);
    }

    // initialization!
    void initialize() throws Exception {

        // RoboMWM: ensure the nextClaimID is greater than any other claim ID. If not,
        // data corruption occurred (out of storage space, usually).
        for (Claim claim : this.claims) {
            if (claim.id >= nextClaimID) {
                Bukkit.getLogger().severe("nextClaimID was lesser or equal to an already-existing claim ID!\n" +
                        "This usually happens if you ran out of storage space.");
                GriefPrevention.AddLogEntry("Changing nextClaimID from " + nextClaimID + " to " + claim.id,
                        CustomLogEntryTypes.Debug, false);
                nextClaimID = claim.id + 1;
            }
        }

        // ensure data folders exist
        File playerDataFolder = new File(playerDataFolderPath);
        if (!playerDataFolder.exists()) {
            playerDataFolder.mkdirs();
        }

        // load up all the messages from messages.yml
        this.loadMessages();

        // if converting up from an earlier schema version, write all claims back to
        // storage using the latest format
        if (this.getSchemaVersion() < latestSchemaVersion) {
            GriefPrevention.AddLogEntry("Please wait.  Updating data format.");

            for (Claim claim : this.claims) {
                this.saveClaim(claim);

                for (Claim subClaim : claim.children) {
                    this.saveClaim(subClaim);
                }
            }

            // clean up any UUID conversion work
            if (UUIDFetcher.lookupCache != null) {
                UUIDFetcher.lookupCache.clear();
                UUIDFetcher.correctedNames.clear();
            }

            GriefPrevention.AddLogEntry("Update finished.");
        }

        // load list of soft mutes
        this.loadSoftMutes();

        // make a note of the data store schema version
        this.setSchemaVersion(latestSchemaVersion);
        this.rebuildClaimSnapshotIndex();

        try {
            this.worldGuard = new WorldGuardWrapper();
            GriefPrevention.AddLogEntry("Successfully hooked into WorldGuard.");
        } catch (IllegalStateException | LinkageError ignored) {
            this.worldGuard = null;
        }

    }

    // Extracts bundled locale YAML files from JAR to GriefPreventionData/Lang/ as reference copies
    private void loadSoftMutes() {
        File softMuteFile = new File(softMuteFilePath);
        if (softMuteFile.exists()) {
            BufferedReader inStream = null;
            try {
                // open the file
                inStream = new BufferedReader(new FileReader(softMuteFile.getAbsolutePath()));

                // while there are lines left
                String nextID = inStream.readLine();
                while (nextID != null) {
                    // parse line into a UUID
                    UUID playerID;
                    try {
                        playerID = UUID.fromString(nextID);
                    } catch (Exception e) {
                        playerID = null;
                        Bukkit.getLogger().info("Failed to parse soft mute entry as a UUID: " + nextID);
                    }

                    // push it into the map
                    if (playerID != null) {
                        this.softMuteMap.put(playerID, true);
                    }

                    // move to the next
                    nextID = inStream.readLine();
                }
            } catch (Exception e) {
                GriefPrevention.AddLogEntry("Failed to read from the soft mute data file: " + e);
                e.printStackTrace();
            }

            try {
                if (inStream != null)
                    inStream.close();
            } catch (IOException exception) {
            }
        }
    }

    public List<String> loadBannedWords() {
        try {
            File bannedWordsFile = new File(bannedWordsFilePath);
            if (!bannedWordsFile.exists()) {
                Files.touch(bannedWordsFile);
                String defaultWords = "nigger\nniggers\nniger\nnigga\nnigers\nniggas\n" +
                        "fag\nfags\nfaggot\nfaggots\nfeggit\nfeggits\nfaggit\nfaggits\n" +
                        "cunt\ncunts\nwhore\nwhores\nslut\nsluts\n";
                java.nio.file.Files.write(bannedWordsFile.toPath(), defaultWords.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }

            @SuppressWarnings("null")
            List<String> bannedWords = Files.readLines(bannedWordsFile, StandardCharsets.UTF_8);
            return bannedWords;
        } catch (Exception e) {
            GriefPrevention.AddLogEntry("Failed to read from the banned words data file: " + e);
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    // updates soft mute map and data file
    boolean toggleSoftMute(UUID playerID) {
        boolean newValue = !this.isSoftMuted(playerID);

        this.softMuteMap.put(playerID, newValue);
        this.saveSoftMutes();

        return newValue;
    }

    public boolean isSoftMuted(UUID playerID) {
        Boolean mapEntry = this.softMuteMap.get(playerID);
        if (mapEntry == null || mapEntry == Boolean.FALSE) {
            return false;
        }

        return true;
    }

    private void saveSoftMutes() {
        BufferedWriter outStream = null;

        try {
            // open the file and write the new value
            File softMuteFile = new File(softMuteFilePath);
            softMuteFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(softMuteFile));

            for (Map.Entry<UUID, Boolean> entry : softMuteMap.entrySet()) {
                if (entry.getValue() == Boolean.TRUE) {
                    outStream.write(entry.getKey().toString());
                    outStream.newLine();
                }
            }

        }

        // if any problem, log it
        catch (Exception e) {
            Bukkit.getLogger().info("Unexpected exception saving soft mute data: " + e.getMessage());
            e.printStackTrace();
        }

        // close the file
        try {
            if (outStream != null)
                outStream.close();
        } catch (IOException exception) {
        }
    }

    // removes cached player data from memory
    synchronized void clearCachedPlayerData(UUID playerID) {
        this.playerNameToPlayerDataMap.remove(playerID);
    }

    // gets the number of bonus blocks a player has from his permissions
    // Bukkit doesn't allow for checking permissions of an offline player.
    // this will return 0 when he's offline, and the correct number when online.
    synchronized public int getGroupBonusBlocks(UUID playerID) {
        Player player = GriefPrevention.instance.getServer().getPlayer(playerID);

        if (player == null)
            return 0;

        int bonusBlocks = 0;

        for (Map.Entry<String, Integer> groupEntry : this.permissionToBonusBlocksMap.entrySet()) {
            if (player.hasPermission(groupEntry.getKey())) {
                bonusBlocks += groupEntry.getValue();
            }
        }

        return bonusBlocks;
    }

    // grants a group (players with a specific permission) bonus claim blocks as
    // long as they're still members of the group
    synchronized public int adjustGroupBonusBlocks(String groupName, int amount) {
        Integer currentValue = this.permissionToBonusBlocksMap.get(groupName);
        if (currentValue == null)
            currentValue = 0;

        currentValue += amount;
        this.permissionToBonusBlocksMap.put(groupName, currentValue);

        // write changes to storage to ensure they don't get lost
        this.saveGroupBonusBlocks(groupName, currentValue);

        return currentValue;
    }

    abstract void saveGroupBonusBlocks(String groupName, int amount);

    public class NoTransferException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NoTransferException(String message) {
            super(message);
        }
    }

    synchronized public void changeClaimOwner(Claim claim, UUID newOwnerID) {
        // if it's a subdivision, throw an exception
        if (claim.parent != null) {
            throw new NoTransferException(
                    "Subdivisions can't be transferred.  Only top-level claims may change owners.");
        }

        // otherwise update information

        // determine current claim owner
        PlayerData ownerData = null;
        if (!claim.isAdminClaim()) {
            ownerData = this.getPlayerData(claim.ownerID);
        }

        // call event
        ClaimTransferEvent event = new ClaimTransferEvent(claim, newOwnerID);
        Bukkit.getPluginManager().callEvent(event);

        // return if event is cancelled
        if (event.isCancelled())
            return;

        // determine new owner
        PlayerData newOwnerData = null;

        if (event.getNewOwner() != null) {
            newOwnerData = this.getPlayerData(event.getNewOwner());
        }

        // transfer
        claim.ownerID = event.getNewOwner();
        this.saveClaim(claim);

        // adjust blocks and other records
        if (ownerData != null) {
            ownerData.getClaims().remove(claim);
        }

        if (newOwnerData != null) {
            Vector<Claim> newOwnerClaims = newOwnerData.getClaims();
            if (!newOwnerClaims.contains(claim)) {
                newOwnerClaims.add(claim);
            }
        }
    }

    // adds a claim to the datastore, making it an effective claim
    synchronized void addClaim(Claim newClaim, boolean writeToStorage) {
        // subdivisions are added under their parent, not directly to the hash map for
        // direct search
        if (newClaim.parent != null) {
            // Check by ID to prevent duplicates (object reference comparison is insufficient
            // since the same subdivision may be loaded from multiple sources)
            boolean alreadyExists = false;
            if (newClaim.id != null) {
                for (Claim child : newClaim.parent.children) {
                    if (newClaim.id.equals(child.id)) {
                        alreadyExists = true;
                        break;
                    }
                }
            }
            // Additional check: prevent duplicates by comparing boundaries during resize operations
            // This catches cases where subdivisions might be added with the same boundaries but different timing
            if (!alreadyExists) {
                for (Claim child : newClaim.parent.children) {
                    if (boundariesEqual(newClaim, child)) {
                        alreadyExists = true;
                        break;
                    }
                }
            }
            if (!alreadyExists) {
                newClaim.parent.children.add(newClaim);
            }

            // 3D subdivisions need to be added to chunk claims map so getClaimAt can find
            // them
            if (newClaim.is3D()) {
                addToChunkClaimMap(newClaim);
            }

            newClaim.inDataStore = true;
            this.indexClaimSnapshot(newClaim);
            if (writeToStorage) {
                this.saveClaim(newClaim);
            }
            return;
        }

        // add it and mark it as added
        this.claims.add(newClaim);
        this.claimIDMap.put(newClaim.id, newClaim);
        for (Claim child : newClaim.children) {
            this.claimIDMap.put(child.id, child);
            child.inDataStore = true;
            // 3D subdivisions need to be in chunk claim map so getClaimAt can find them
            if (child.is3D()) {
                addToChunkClaimMap(child);
            }
        }
        addToChunkClaimMap(newClaim);

        newClaim.inDataStore = true;
        this.indexClaimTree(newClaim);

        // except for administrative claims (which have no owner), update the owner's
        // playerData with the new claim
        if (!newClaim.isAdminClaim() && writeToStorage) {
            PlayerData ownerData = this.getPlayerData(newClaim.ownerID);
            Vector<Claim> ownerClaims = ownerData.getClaims();
            if (!ownerClaims.contains(newClaim)) {
                ownerClaims.add(newClaim);
            }
        }

        // make sure the claim is saved to disk
        if (writeToStorage) {
            this.saveClaim(newClaim);
        }
    }

    private void addToChunkClaimMap(Claim claim) {
        // Regular subclaims should not be added to chunk claim map, but 3D subdivisions
        // should be
        // because they need to be independently discoverable by getClaimAt for trust
        // commands
        if (claim.parent != null && !claim.is3D())
            return;

        ArrayList<Long> chunkHashes = claim.getChunkHashes();
        for (Long chunkHash : chunkHashes) {
            ArrayList<Claim> claimsInChunk = this.chunksToClaimsMap.get(chunkHash);
            if (claimsInChunk == null) {
                this.chunksToClaimsMap.put(chunkHash, claimsInChunk = new ArrayList<>());
            }

            claimsInChunk.add(claim);
        }
    }

    private void removeFromChunkClaimMap(Claim claim) {
        // Only remove claims that were added to the chunk map (regular claims and 3D
        // subdivisions)
        if (claim.parent != null && !claim.is3D())
            return;

        ArrayList<Long> chunkHashes = claim.getChunkHashes();
        for (Long chunkHash : chunkHashes) {
            ArrayList<Claim> claimsInChunk = this.chunksToClaimsMap.get(chunkHash);
            if (claimsInChunk != null) {
                for (Iterator<Claim> it = claimsInChunk.iterator(); it.hasNext();) {
                    Claim c = it.next();
                    if (c.id.equals(claim.id)) {
                        it.remove();
                        break;
                    }
                }
                if (claimsInChunk.isEmpty()) { // if nothing's left, remove this chunk's cache
                    this.chunksToClaimsMap.remove(chunkHash);
                }
            }
        }
    }

    private void rebuildClaimSnapshotIndex() {
        this.claimSnapshotIndex.clear();
        for (Claim claim : this.claims) {
            this.indexClaimTree(claim);
        }
    }

    private void indexClaimTree(Claim claim) {
        this.indexClaimSnapshot(claim);
        for (Claim child : claim.children) {
            this.indexClaimTree(child);
        }
    }

    private void indexClaimSnapshot(Claim claim) {
        if (claim.id == null) {
            return;
        }

        if (claim.inDataStore) {
            this.claimSnapshotIndex.put(claim.getSnapshot());
        } else {
            this.claimSnapshotIndex.remove(claim.id);
        }
    }

    private void removeClaimSnapshot(Claim claim) {
        if (claim.id != null) {
            this.claimSnapshotIndex.remove(claim.id);
        }
    }

    // Helper method to compare claim boundaries for duplicate detection
    private boolean boundariesEqual(Claim claim1, Claim claim2) {
        if (claim1 == null || claim2 == null) return false;
        if (claim1.parent != claim2.parent) return false;
        
        Location c1Lesser = claim1.getLesserBoundaryCorner();
        Location c1Greater = claim1.getGreaterBoundaryCorner();
        Location c2Lesser = claim2.getLesserBoundaryCorner();
        Location c2Greater = claim2.getGreaterBoundaryCorner();
        
        return c1Lesser.getBlockX() == c2Lesser.getBlockX() &&
               c1Lesser.getBlockY() == c2Lesser.getBlockY() &&
               c1Lesser.getBlockZ() == c2Lesser.getBlockZ() &&
               c1Greater.getBlockX() == c2Greater.getBlockX() &&
               c1Greater.getBlockY() == c2Greater.getBlockY() &&
               c1Greater.getBlockZ() == c2Greater.getBlockZ() &&
               claim1.is3D() == claim2.is3D();
    }

    private int polygonCellArea(@NotNull World world, @NotNull OrthogonalPolygon polygon) {
        // Compute lattice-cell count directly from polygon corners (O(edges)) instead of
        // iterating every (x, z) cell and invoking a point-in-polygon test per cell.
        // For orthogonal polygons with integer corners, cell count equals the number of
        // lattice points inside or on the boundary, i.e. Pick's theorem: A + B/2 + 1.
        return polygon.cellCount();
    }

    private static final class MinimumProfile
    {
        private final int minWidth;
        private final int minArea;

        private MinimumProfile(int minWidth, int minArea)
        {
            this.minWidth = minWidth;
            this.minArea = minArea;
        }

        private int minWidth()
        {
            return minWidth;
        }

        private int minArea()
        {
            return minArea;
        }
    }

    private MinimumProfile resolveMinimumProfileForPolygon(@NotNull OrthogonalPolygon polygon)
    {
        // A normalized orthogonal polygon with 4 corners is a simple rectangle.
        // Rectangles must respect standard claim minimums even when shaped editing exists.
        if (polygon.corners().size() == 4)
        {
            return new MinimumProfile(
                    Math.max(1, GriefPrevention.instance.config_claims_minWidth),
                    Math.max(1, GriefPrevention.instance.config_claims_minArea));
        }

        return new MinimumProfile(
                Math.max(1, GriefPrevention.instance.config_claims_shapedMinWidth),
                Math.max(1, GriefPrevention.instance.config_claims_shapedMinArea));
    }

    private @Nullable Supplier<String> validateShapedCreationMinimums(
            @NotNull World world,
            @NotNull OrthogonalPolygon polygon)
    {
        MinimumProfile minimumProfile = resolveMinimumProfileForPolygon(polygon);
        int minWidth = minimumProfile.minWidth();
        int minArea = minimumProfile.minArea();

        int width;
        int height;
        try
        {
            width = Math.abs(Math.subtractExact(polygon.minX(), polygon.maxX())) + 1;
            height = Math.abs(Math.subtractExact(polygon.minZ(), polygon.maxZ())) + 1;
        }
        catch (ArithmeticException e)
        {
            return () -> this.getMessage(Messages.CreateClaimInsufficientBlocks, String.valueOf(Integer.MAX_VALUE));
        }

        if (width < minWidth
                || height < minWidth
                || !shapedPolygonMeetsMinimumWidth(polygon, minWidth))
        {
            return () -> this.getMessage(Messages.NewClaimTooNarrow, String.valueOf(minWidth));
        }

        int area = polygonCellArea(world, polygon);
        if (area < minArea)
        {
            return () -> this.getMessage(Messages.ResizeClaimInsufficientArea, String.valueOf(minArea));
        }

        return null;
    }

    private @Nullable Supplier<String> validateShapedResizeMinimums(
            @NotNull Player player,
            @NotNull Claim claim,
            @NotNull World world,
            @NotNull OrthogonalPolygon polygon)
    {
        if (player.hasPermission("griefprevention.adminclaims") || claim.isAdminClaim())
        {
            return null;
        }

        MinimumProfile minimumProfile = resolveMinimumProfileForPolygon(polygon);
        int minWidth = minimumProfile.minWidth();
        int minArea = minimumProfile.minArea();

        int width;
        int height;
        try
        {
            width = Math.abs(Math.subtractExact(polygon.minX(), polygon.maxX())) + 1;
            height = Math.abs(Math.subtractExact(polygon.minZ(), polygon.maxZ())) + 1;
        }
        catch (ArithmeticException e)
        {
            return () -> this.getMessage(Messages.ResizeNeedMoreBlocks, String.valueOf(Integer.MAX_VALUE));
        }

        if (width < minWidth
                || height < minWidth
                || !shapedPolygonMeetsMinimumWidth(polygon, minWidth))
        {
            return () -> this.getMessage(Messages.ResizeClaimTooNarrow, String.valueOf(minWidth));
        }

        int newArea = polygonCellArea(world, polygon);
        if (newArea < minArea && newArea < claim.getArea())
        {
            return () -> this.getMessage(Messages.ResizeClaimInsufficientArea, String.valueOf(minArea));
        }

        return null;
    }

    private boolean shapedPolygonMeetsMinimumWidth(@NotNull OrthogonalPolygon polygon, int minimumWidth)
    {
        int minimumEdgeLength = Math.max(0, minimumWidth - 1);
        if (minimumEdgeLength <= 0)
        {
            return true;
        }

        for (OrthogonalEdge2i edge : polygon.edges())
        {
            if (edge.length() < minimumEdgeLength)
            {
                return false;
            }
        }

        return true;
    }

    private boolean containsChild(@NotNull Claim parentCandidate, @NotNull Claim child) {
        Location lesser = child.getLesserBoundaryCorner();
        Location greater = child.getGreaterBoundaryCorner();
        World world = Objects.requireNonNull(lesser.getWorld());
        int minX = Math.min(lesser.getBlockX(), greater.getBlockX());
        int maxX = Math.max(lesser.getBlockX(), greater.getBlockX());
        int minZ = Math.min(lesser.getBlockZ(), greater.getBlockZ());
        int maxZ = Math.max(lesser.getBlockZ(), greater.getBlockZ());
        int childMinY = Math.min(lesser.getBlockY(), greater.getBlockY());
        int childMaxY = Math.max(lesser.getBlockY(), greater.getBlockY());

        // Shaped parents need exact X/Z containment checks for each occupied child column.
        // A simple corner check can miss concave boundary escapes.
        if (parentCandidate.isShaped()) {
            int sampleY = childMinY;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location probe = new Location(world, x, sampleY, z);
                    if (!child.contains(probe, true, false)) {
                        continue;
                    }

                    if (!parentCandidate.contains(probe, true, false)) {
                        return false;
                    }
                }
            }

            return true;
        }

        boolean cornersInside = parentCandidate.contains(new Location(world, minX, childMinY, minZ), true, false)
                && parentCandidate.contains(new Location(world, minX, childMinY, maxZ), true, false)
                && parentCandidate.contains(new Location(world, maxX, childMinY, minZ), true, false)
                && parentCandidate.contains(new Location(world, maxX, childMinY, maxZ), true, false);
        if (!cornersInside) {
            return false;
        }

        if (parentCandidate.is3D()) {
            Location parentLesser = parentCandidate.getLesserBoundaryCorner();
            Location parentGreater = parentCandidate.getGreaterBoundaryCorner();
            int parentMinY = Math.min(parentLesser.getBlockY(), parentGreater.getBlockY());
            int parentMaxY = Math.max(parentLesser.getBlockY(), parentGreater.getBlockY());
            return childMinY >= parentMinY && childMaxY <= parentMaxY;
        }

        return true;
    }

    // turns a location into a string, useful in data storage
    private final String locationStringDelimiter = ";";

    String locationToString(Location location) {
        StringBuilder stringBuilder = new StringBuilder(location.getWorld().getName());
        stringBuilder.append(locationStringDelimiter);
        stringBuilder.append(location.getBlockX());
        stringBuilder.append(locationStringDelimiter);
        stringBuilder.append(location.getBlockY());
        stringBuilder.append(locationStringDelimiter);
        stringBuilder.append(location.getBlockZ());

        return stringBuilder.toString();
    }

    // turns a location string back into a location
    Location locationFromString(String string, List<World> validWorlds) throws Exception {
        // split the input string on the space
        String[] elements = string.split(locationStringDelimiter);

        // expect four elements - world name, X, Y, and Z, respectively
        if (elements.length < 4) {
            throw new Exception("Expected four distinct parts to the location string: \"" + string + "\"");
        }

        String worldName = elements[0];
        String xString = elements[1];
        String yString = elements[2];
        String zString = elements[3];

        // identify world the claim is in
        World world = null;
        for (World w : validWorlds) {
            if (w.getName().equalsIgnoreCase(worldName)) {
                world = w;
                break;
            }
        }

        if (world == null) {
            throw new Exception("World not found: \"" + worldName + "\"");
        }

        // convert those numerical strings to integer values
        int x = Integer.parseInt(xString);
        int y = Integer.parseInt(yString);
        int z = Integer.parseInt(zString);

        return new Location(world, x, y, z);
    }

    // saves any changes to a claim to secondary storage
    synchronized public void saveClaim(Claim claim) {
        assignClaimID(claim);

        this.writeClaimToStorage(claim);
    }

    // saves any changes to a group of claims to secondary storage.
    // storage implementations that keep several claims in one record can override this to
    // write each record only once instead of once per claim.
    synchronized public void saveClaims(Collection<Claim> claims) {
        for (Claim claim : claims) {
            if (claim == null) continue;
            this.saveClaim(claim);
        }
    }

    void assignClaimID(Claim claim) {
        // ensure a unique identifier for the claim which will be used to name the file
        // on disk
        if (claim.id == null || claim.id == -1) {
            claim.id = this.nextClaimID;
            this.incrementNextClaimID();
        }
    }

    abstract void writeClaimToStorage(Claim claim);

    // increments the claim ID and updates secondary storage to be sure it's saved
    abstract void incrementNextClaimID();

    // retrieves player data from memory or secondary storage, as necessary
    // if the player has never been on the server before, this will return a fresh
    // player data with default values
    synchronized public PlayerData getPlayerData(UUID playerID) {
        // first, look in memory
        PlayerData playerData = this.playerNameToPlayerDataMap.get(playerID);

        // if not there, build a fresh instance with some blanks for what may be in
        // secondary storage
        if (playerData == null) {
            playerData = new PlayerData();
            playerData.playerID = playerID;

            // shove that new player data into the hash map cache
            this.playerNameToPlayerDataMap.put(playerID, playerData);
        }

        return playerData;
    }

    abstract PlayerData getPlayerDataFromStorage(UUID playerID);

    // deletes a claim or subdivision
    synchronized public void deleteClaim(Claim claim) {
        this.deleteClaim(claim, true, false);
    }

    synchronized public void deleteClaim(Claim claim, boolean releasePets) {
        this.deleteClaim(claim, true, releasePets);
    }

    synchronized void deleteClaim(Claim claim, boolean fireEvent, boolean releasePets) {
        this.deleteClaimWithResult(claim, fireEvent, releasePets);
    }

    synchronized boolean deleteClaimWithResult(Claim claim, boolean fireEvent, boolean releasePets) {
        if (fireEvent && preDeleteCancelled(claim)) {
            return false;
        }

        this.deleteClaimUnchecked(claim, fireEvent, releasePets);
        return true;
    }

    private boolean preDeleteCancelled(Claim claim) {
        PreDeleteClaimEvent event = new PreDeleteClaimEvent(claim);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return true;
        }

        if (!claim.children.isEmpty()) {
            java.util.List<Claim> childrenSnapshot = new java.util.ArrayList<>(claim.children);
            for (Claim child : childrenSnapshot) {
                if (preDeleteCancelled(child)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void deleteClaimUnchecked(Claim claim, boolean fireEvent, boolean releasePets) {
        // Debug logging for claim deletion
        if (GriefPrevention.instance.config_logs_debugEnabled) {
            String claimType = claim.parent != null ? "Subdivision" : (claim.isAdminClaim() ? "Admin Claim" : "Top-level Claim");
            String ownerInfo = claim.ownerID != null ? claim.ownerID.toString() : "admin";
            String locationInfo = claim.getLesserBoundaryCorner() != null 
                ? GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()) 
                : "unknown";
            GriefPrevention.AddLogEntry("[DEBUG] Deleting " + claimType + " - ID: " + claim.id 
                + ", Owner: " + ownerInfo 
                + ", Location: " + locationInfo
                + ", Children: " + claim.children.size(), CustomLogEntryTypes.Debug, true);
        }

        // delete any children (iterate over a snapshot to avoid skipping due to parent
        // list mutation)
        if (!claim.children.isEmpty()) {
            java.util.List<Claim> childrenSnapshot = new java.util.ArrayList<>(claim.children);
            for (Claim child : childrenSnapshot) {
                this.deleteClaimUnchecked(child, fireEvent, releasePets);
            }
        }

        // subdivisions must also be removed from the parent claim child list
        if (claim.parent != null) {
            Claim parentClaim = claim.parent;
            parentClaim.children.remove(claim);
        }

        // mark as deleted so any references elsewhere can be ignored

        // Clean up auto-neighbor trust from nearby claims
        this.cleanupAutoNeighborsOnDelete(claim);
        claim.inDataStore = false;

        // remove from memory
        for (int i = 0; i < this.claims.size(); i++) {
            if (claims.get(i).id.equals(claim.id)) {
                this.claims.remove(i);
                break;
            }
        }

        claimIDMap.remove(claim.id);
        for (Claim child : claim.children) {
            claimIDMap.remove(child.id);
        }

        removeFromChunkClaimMap(claim);
        this.removeClaimSnapshot(claim);

        // remove from secondary storage
        this.deleteClaimFromSecondaryStorage(claim);

        // update player data
        if (claim.ownerID != null) {
            PlayerData ownerData = this.getPlayerData(claim.ownerID);
            for (int i = 0; i < ownerData.getClaims().size(); i++) {
                if (ownerData.getClaims().get(i).id.equals(claim.id)) {
                    ownerData.getClaims().remove(i);
                    break;
                }
            }
            this.savePlayerData(claim.ownerID, ownerData);
        }

        // Proactively clear any active visualizations referencing this claim for all
        // online players
        // to prevent lingering ghost boundaries after deletion.
        try {
            org.bukkit.Server server = org.bukkit.Bukkit.getServer();
            for (org.bukkit.entity.Player online : server.getOnlinePlayers()) {
                if (online != null) {
                    PlayerData data = GriefPrevention.instance.dataStore.getPlayerData(online.getUniqueId());
                    com.griefprevention.visualization.BoundaryVisualization bv = data.getVisibleBoundaries();
                    if (bv != null) {
                        // If the player has any active visualization, conservatively clear it.
                        // This guarantees no stale visualization for deleted claims and their children.
                        data.setVisibleBoundaries(null);
                    }
                }
            }
        } catch (Exception ignoredEx) {
            // Visualization cleanup is best-effort; ignore any exceptions to avoid
            // interfering with deletion.
        }

        if (fireEvent) {
            ClaimDeletedEvent ev = new ClaimDeletedEvent(claim);
            Bukkit.getPluginManager().callEvent(ev);
        }

        if (releasePets && claim.ownerID != null && claim.parent == null) {
            releasePetsInClaim(claim);
        }
    }

    private void releasePetsInClaim(final Claim claim) {
        final World world = claim.getLesserBoundaryCorner().getWorld();
        if (world == null) return;
        final UUID ownerID = claim.ownerID;
        int minChunkX = claim.getLesserBoundaryCorner().getBlockX() >> 4;
        int maxChunkX = claim.getGreaterBoundaryCorner().getBlockX() >> 4;
        int minChunkZ = claim.getLesserBoundaryCorner().getBlockZ() >> 4;
        int maxChunkZ = claim.getGreaterBoundaryCorner().getBlockZ() >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final int scheduledChunkX = chunkX;
                final int scheduledChunkZ = chunkZ;
                Location location = new Location(world, (chunkX << 4) + 8,
                        claim.getLesserBoundaryCorner().getY(), (chunkZ << 4) + 8);
                SchedulerUtil.runAtLocation(GriefPrevention.instance, location, new Runnable() {
                    @Override
                    public void run() {
                        Chunk chunk = world.getChunkAt(scheduledChunkX, scheduledChunkZ);
                        for (Entity entity : chunk.getEntities()) {
                            if (!(entity instanceof Tameable) || !claim.contains(entity.getLocation(), true, false)) continue;
                            Tameable pet = (Tameable) entity;
                            AnimalTamer owner = pet.getOwner();
                            if (!pet.isTamed() || owner == null || !ownerID.equals(owner.getUniqueId())) continue;
                            pet.setTamed(false);
                            pet.setOwner(null);
                            if (pet instanceof InventoryHolder) {
                                ((InventoryHolder) pet).getInventory().clear();
                            }
                            try {
                                pet.getClass().getMethod("setSitting", boolean.class).invoke(pet, false);
                            } catch (ReflectiveOperationException ignored) {
                                // Not all tameables are sittable, and older APIs have no Sittable interface.
                            }
                        }
                    }
                });
            }
        }
    }

    abstract void deleteClaimFromSecondaryStorage(Claim claim);

    // gets the claim at a specific location
    // ignoreHeight = TRUE means that a location UNDER an existing claim will return
    // the claim
    // cachedClaim can be NULL, but will help performance if you have a reasonable
    // guess about which claim the location is in
    synchronized public Claim getClaimAt(Location location, boolean ignoreHeight, Claim cachedClaim) {
        return getClaimAt(location, ignoreHeight, false, cachedClaim);
    }

    /**
     * Get the claim at a specific location.
     *
     * <p>
     * The cached claim may be null, but will increase performance if you have a
     * reasonable idea
     * of which claim is correct.
     *
     * @param location        the location
     * @param ignoreHeight    whether or not to check containment vertically
     * @param ignoreSubclaims whether or not subclaims should be returned over
     *                        claims
     * @param cachedClaim     the cached claim, if any
     * @return the claim containing the location or null if no claim exists there
     */
    synchronized public Claim getClaimAt(Location location, boolean ignoreHeight, boolean ignoreSubclaims,
            Claim cachedClaim) {
        // Check cached claim first, but don't prematurely return a non-3D claim if a
        // more specific 3D subclaim exists.
        if (cachedClaim != null && cachedClaim.inDataStore
                && cachedClaim.contains(location, ignoreHeight, ignoreSubclaims)) {
            final boolean cachedAcceptsY = !cachedClaim.is3D() || cachedClaim.containsY(location.getBlockY());
            if (cachedAcceptsY) {
                if (!ignoreSubclaims) {
                    // Look for a more specific 3D claim within the same chunks that contains this
                    // location including Y.
                    Set<Claim> claimsInChunks = this.getChunkClaims(location.getWorld(),
                            new BoundingBox(location.getBlock()));
                    Claim better3D = null;
                    for (Claim claim : claimsInChunks) {
                        if (!claim.contains(location, false /* respect height */, false))
                            continue;
                        if (!claim.is3D())
                            continue;
                        // Prefer smallest Y-range, then area
                        if (better3D == null) {
                            better3D = claim;
                        } else {
                            int currentYRange = claim.getGreaterBoundaryCorner().getBlockY()
                                    - claim.getLesserBoundaryCorner().getBlockY();
                            int bestYRange = better3D.getGreaterBoundaryCorner().getBlockY()
                                    - better3D.getLesserBoundaryCorner().getBlockY();

                            // Prefer the claim with smaller Y-range (more specific), or smaller area if
                            // Y-ranges are equal
                            if (currentYRange < bestYRange ||
                                    (currentYRange == bestYRange && claim.getArea() < better3D.getArea())) {
                                better3D = claim;
                            }
                        }
                    }
                    if (better3D != null) {
                        return better3D;
                    }

                    // Prefer a matching child (handles nested subdivisions)
                    if (!cachedClaim.children.isEmpty()) {
                        Claim bestChild = null;
                        for (Claim child : cachedClaim.children) {
                            if (!child.inDataStore)
                                continue;
                            // For child.contains: for 2D children, height is effectively ignored;
                            // for 3D children, Y is enforced because ignoreHeight=false
                            if (!child.contains(location, false /* respect height where applicable */, false))
                                continue;

                            if (bestChild == null) {
                                bestChild = child;
                            } else {
                                boolean bestIs3D = bestChild.is3D();
                                boolean currIs3D = child.is3D();

                                if (bestIs3D && currIs3D) {
                                    int bestYRange = bestChild.getGreaterBoundaryCorner().getBlockY()
                                            - bestChild.getLesserBoundaryCorner().getBlockY();
                                    int currYRange = child.getGreaterBoundaryCorner().getBlockY()
                                            - child.getLesserBoundaryCorner().getBlockY();
                                    if (currYRange < bestYRange
                                            || (currYRange == bestYRange && child.getArea() < bestChild.getArea())) {
                                        bestChild = child;
                                    }
                                } else if (!bestIs3D && currIs3D) {
                                    bestChild = child; // prefer 3D over 2D if both match
                                } else if (!bestIs3D && !currIs3D) {
                                    if (child.getArea() < bestChild.getArea()) {
                                        bestChild = child;
                                    }
                                }
                            }
                        }
                        if (bestChild != null) {
                            return bestChild;
                        }
                    }
                }
                // No better claim found; return cached or matching child from above.
                return cachedClaim;
            }
            // If cached is 3D but doesn't accept Y, continue to full search below.
        }
        // Check all claims in the same chunks as the location
        Set<Claim> claimsInChunks = this.getChunkClaims(location.getWorld(), new BoundingBox(location.getBlock()));
        if (claimsInChunks.isEmpty())
            return null;

        List<Claim> containingClaims = new ArrayList<>();
        for (Claim claim : claimsInChunks) {
            collectClaimsContaining(claim, location, ignoreHeight, ignoreSubclaims, containingClaims);
        }

        if (containingClaims.isEmpty()) {
            return null;
        }

        // Find the most specific claim at the location, prioritizing 3D subclaims that
        // contain the Y coordinate
        // This method handles the complex logic of claim prioritization:
        // 1. Collect all claims that contain the location (respecting height boundaries
        // where applicable)
        // 2. Among 3D claims that contain the Y coordinate, prefer the one with the
        // smallest Y-range (most specific)
        // 3. Among 3D claims that don't contain the Y coordinate, treat them as regular
        // 2D claim fallbacks
        // 4. Among non-3D claims, prefer the one with the smallest area (most specific)
        // 5. If we have both 3D and 2D claims, ensure we select the most appropriate 3D
        // claim that contains the Y coordinate
        // 6. Finally, if the selected claim is a parent claim, check if any of its
        // children are more specific
        // This ensures that stacked 3D subdivisions are handled correctly, with trust
        // applied to the correct subclaim

        Claim smallestClaim = null;
        Claim smallest3DClaim = null;

        for (Claim claim : containingClaims) {
            // For 3D claims, verify Y coordinate
            if (claim.is3D()) {
                if (claim.containsY(location.getBlockY())) {
                    // If we don't have a 3D claim yet, or this one is more specific (smaller
                    // Y-range), take it
                    if (smallest3DClaim == null) {
                        smallest3DClaim = claim;
                    } else {
                        // Calculate Y-range for both claims
                        int currentYRange = claim.getGreaterBoundaryCorner().getBlockY()
                                - claim.getLesserBoundaryCorner().getBlockY();
                        int smallestYRange = smallest3DClaim.getGreaterBoundaryCorner().getBlockY()
                                - smallest3DClaim.getLesserBoundaryCorner().getBlockY();

                        // Prefer the claim with smaller Y-range (more specific), or smaller area if
                        // Y-ranges are equal
                        if (currentYRange < smallestYRange ||
                                (currentYRange == smallestYRange && claim.getArea() < smallest3DClaim.getArea())) {
                            smallest3DClaim = claim;
                        }
                    }
                } else {
                    // 3D claim doesn't contain Y coordinate, treat it as a regular claim fallback
                    if (smallestClaim == null || claim.getArea() < smallestClaim.getArea()) {
                        smallestClaim = claim;
                    }
                }
            } else {
                // For non-3D claims, find the smallest one
                if (smallestClaim == null || claim.getArea() < smallestClaim.getArea()) {
                    smallestClaim = claim;
                }
            }
        }

        // Return 3D claim if found, otherwise return smallest non-3D claim
        Claim result = smallest3DClaim != null ? smallest3DClaim : smallestClaim;

        // If we have both a 3D and 2D claim, but the selected 3D claim doesn't contain
        // the Y coordinate,
        // we should prefer the 2D claim. However, we need to ensure we select the
        // correct 3D claim first.
        if (smallest3DClaim != null && smallestClaim != null && smallest3DClaim != smallestClaim) {
            if (!smallest3DClaim.containsY(location.getBlockY())) {
                // If the currently selected 3D claim doesn't contain the Y coordinate,
                // look for other 3D claims that do contain it and are more specific
                Claim better3DClaim = null;
                for (Claim claim : containingClaims) {
                    if (claim.is3D() && claim.containsY(location.getBlockY())) {
                        if (better3DClaim == null) {
                            better3DClaim = claim;
                        } else {
                            // Prefer the claim with smaller Y-range (more specific), or smaller area if
                            // Y-ranges are equal
                            int currentYRange = claim.getGreaterBoundaryCorner().getBlockY()
                                    - claim.getLesserBoundaryCorner().getBlockY();
                            int bestYRange = better3DClaim.getGreaterBoundaryCorner().getBlockY()
                                    - better3DClaim.getLesserBoundaryCorner().getBlockY();

                            if (currentYRange < bestYRange ||
                                    (currentYRange == bestYRange && claim.getArea() < better3DClaim.getArea())) {
                                better3DClaim = claim;
                            }
                        }
                    }
                }

                // If we found a better 3D claim that contains the Y coordinate, use it
                if (better3DClaim != null) {
                    result = better3DClaim;
                } else {
                    // No 3D claim contains the Y coordinate, so prefer the 2D claim
                    result = smallestClaim;
                }
            }
        }

        // If subclaims are allowed and a parent claim was selected, prefer a matching
        // child (handles 2D subdivisions)
        if (!ignoreSubclaims && result != null && result.parent == null && !result.children.isEmpty()) {
            Claim bestChild = null;

            for (Claim child : result.children) {
                if (!child.inDataStore)
                    continue;
                // For child.contains: for 2D children, height is effectively ignored;
                // for 3D children, Y is enforced because ignoreHeight=false
                if (!child.contains(location, false /* respect height where applicable */, false))
                    continue;

                if (bestChild == null) {
                    bestChild = child;
                } else {
                    // Prefer more specific child:
                    // - If both 3D, choose smaller Y-range; tie-breaker by smaller area
                    // - If one is 3D and the other is not, prefer 3D (more specific)
                    // - If both non-3D, choose smaller area
                    boolean bestIs3D = bestChild.is3D();
                    boolean currIs3D = child.is3D();

                    if (bestIs3D && currIs3D) {
                        int bestYRange = bestChild.getGreaterBoundaryCorner().getBlockY()
                                - bestChild.getLesserBoundaryCorner().getBlockY();
                        int currYRange = child.getGreaterBoundaryCorner().getBlockY()
                                - child.getLesserBoundaryCorner().getBlockY();
                        if (currYRange < bestYRange
                                || (currYRange == bestYRange && child.getArea() < bestChild.getArea())) {
                            bestChild = child;
                        }
                    } else if (!bestIs3D && currIs3D) {
                        bestChild = child; // prefer 3D over 2D if both match
                    } else if (!bestIs3D && !currIs3D) {
                        if (child.getArea() < bestChild.getArea()) {
                            bestChild = child;
                        }
                    }
                }
            }

            if (bestChild != null) {
                result = bestChild;
            }
        }

        // Return the most specific claim found at this location
        return result;
    }

    private void collectClaimsContaining(Claim claim, Location location, boolean ignoreHeight, boolean ignoreSubclaims,
            List<Claim> results) {
        if (claim == null || !claim.inDataStore) {
            return;
        }

        if (!claim.contains(location, ignoreHeight, ignoreSubclaims)) {
            return;
        }

        if (results.contains(claim)) {
            return;
        }

        results.add(claim);

        if (ignoreSubclaims) {
            return;
        }

        for (Claim child : claim.children) {
            collectClaimsContaining(child, location, ignoreHeight, false, results);
        }
    }

    // finds a claim by ID
    public synchronized Claim getClaim(long id) {
        return this.claimIDMap.get(id);
    }

    public synchronized @NotNull List<ClaimSnapshot> getClaimSnapshots() {
        return this.claimSnapshotIndex.snapshots();
    }

    // returns a read-only access point for the list of all land claims
    // if you need to make changes, use provided methods like .deleteClaim() and
    // .createClaim().
    // this will ensure primary memory (RAM) and secondary memory (disk, database)
    // stay in sync
    public Collection<Claim> getClaims() {
        return Collections.unmodifiableCollection(this.claims);
    }

    public Collection<Claim> getClaims(int chunkx, int chunkz) {
        ArrayList<Claim> chunkClaims = this.chunksToClaimsMap.get(getChunkHash(chunkx, chunkz));
        if (chunkClaims != null) {
            return Collections.unmodifiableCollection(chunkClaims);
        } else {
            return Collections.unmodifiableCollection(new ArrayList<>());
        }
    }

    public @NotNull Set<Claim> getChunkClaims(@NotNull World world, @NotNull BoundingBox boundingBox) {
        Set<Claim> claims = new HashSet<>();
        int chunkXMax = boundingBox.getMaxX() >> 4;
        int chunkZMax = boundingBox.getMaxZ() >> 4;

        for (int chunkX = boundingBox.getMinX() >> 4; chunkX <= chunkXMax; ++chunkX) {
            for (int chunkZ = boundingBox.getMinZ() >> 4; chunkZ <= chunkZMax; ++chunkZ) {
                ArrayList<Claim> chunkClaims = this.chunksToClaimsMap.get(getChunkHash(chunkX, chunkZ));
                if (chunkClaims == null)
                    continue;

                for (Claim claim : chunkClaims) {
                    if (claim.inDataStore && world.equals(claim.getLesserBoundaryCorner().getWorld())) {
                        claims.add(claim);
                    }
                }
            }
        }

        return claims;
    }

    // gets an almost-unique, persistent identifier for a chunk
    public static Long getChunkHash(long chunkx, long chunkz) {
        return (chunkz ^ (chunkx << 32));
    }

    // gets an almost-unique, persistent identifier for a chunk
    public static Long getChunkHash(Location location) {
        return getChunkHash(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static ArrayList<Long> getChunkHashes(Claim claim) {
        return getChunkHashes(claim.getLesserBoundaryCorner(), claim.getGreaterBoundaryCorner());
    }

    public static ArrayList<Long> getChunkHashes(Location min, Location max) {
        ArrayList<Long> hashes = new ArrayList<>();
        int smallX = min.getBlockX() >> 4;
        int smallZ = min.getBlockZ() >> 4;
        int largeX = max.getBlockX() >> 4;
        int largeZ = max.getBlockZ() >> 4;

        for (int x = smallX; x <= largeX; x++) {
            for (int z = smallZ; z <= largeZ; z++) {
                hashes.add(getChunkHash(x, z));
            }
        }

        return hashes;
    }

    // Scans existing claims and auto-grants neighbor trust between nearby claims.
    // Called after config load when MinimumDistance is set.
    public void autoGrantNeighborTrust() {
        int minDist = GriefPrevention.instance.config_claims_minimumDistance;
        if (minDist <= 0) return;

        for (Claim claim : this.claims) {
            if (!claim.inDataStore) continue;
            if (claim.parent != null) continue;
            if (claim.getOwnerID() == null) continue;

            for (Claim otherClaim : this.claims) {
                if (!otherClaim.inDataStore) continue;
                if (otherClaim.parent != null) continue;
                if (otherClaim.getOwnerID() == null) continue;
                if (claim.id.equals(otherClaim.id)) continue;
                if (claim.getOwnerID().equals(otherClaim.getOwnerID())) continue;
                if (!claim.getLesserBoundaryCorner().getWorld().equals(otherClaim.getLesserBoundaryCorner().getWorld())) continue;

                if (claim.isNear(otherClaim.getLesserBoundaryCorner(), minDist) || claim.isNear(otherClaim.getGreaterBoundaryCorner(), minDist)) {
                    claim.addAutoNeighbor(otherClaim.getOwnerID().toString());
                    otherClaim.addAutoNeighbor(claim.getOwnerID().toString());
                }
            }
        }
    }

    // When a claim is deleted, remove the owner from auto-neighbor lists on nearby claims,
    // but only if the deleted owner has no other claims still nearby.
    void cleanupAutoNeighborsOnDelete(Claim deletedClaim) {
        if (deletedClaim.parent != null) return;
        UUID deletedOwner = deletedClaim.getOwnerID();
        if (deletedOwner == null) return;

        int minDist = GriefPrevention.instance.config_claims_minimumDistance;
        if (minDist <= 0) return;

        for (Claim claim : this.claims) {
            if (!claim.inDataStore) continue;
            if (claim.parent != null) continue;
            if (!claim.isAutoNeighbor(deletedOwner.toString())) continue;

            boolean hasOtherNearby = false;
            for (Claim otherClaim : this.claims) {
                if (!otherClaim.inDataStore) continue;
                if (otherClaim.parent != null) continue;
                if (otherClaim.id.equals(deletedClaim.id)) continue;
                // Admin claims have a null owner and can never match the deleted owner.
                if (otherClaim.getOwnerID() == null) continue;
                if (!otherClaim.getOwnerID().equals(deletedOwner)) continue;
                if (!claim.getLesserBoundaryCorner().getWorld().equals(otherClaim.getLesserBoundaryCorner().getWorld())) continue;

                if (claim.isNear(otherClaim.getLesserBoundaryCorner(), minDist) || claim.isNear(otherClaim.getGreaterBoundaryCorner(), minDist)) {
                    hasOtherNearby = true;
                    break;
                }
            }

            if (!hasOtherNearby) {
                claim.removeAutoNeighbor(deletedOwner.toString());
                this.saveClaim(claim);
            }
        }
    }

        /*
     * Creates a claim and flags it as being new....throwing a create claim event;
     */
    synchronized public CreateClaimResult createClaim(World world, int x1, int x2, int y1, int y2, int z1, int z2,
            UUID ownerID, Claim parent, Long id, Player creatingPlayer) {
        return createClaim(world, x1, x2, y1, y2, z1, z2, ownerID, parent, id, creatingPlayer, false, false);
    }

    synchronized public CreateClaimResult createClaim(World world, int x1, int x2, int y1, int y2, int z1, int z2,
            UUID ownerID, Claim parent, Long id, Player creatingPlayer, boolean is3D) {
        return createClaim(world, x1, x2, y1, y2, z1, z2, ownerID, parent, id, creatingPlayer, false, is3D);
    }

    synchronized public CreateClaimResult createShapedClaim(
            @NotNull World world,
            @NotNull OrthogonalPolygon polygon,
            int y,
            @Nullable UUID ownerID,
            @Nullable Player creatingPlayer)
    {
        CreateClaimResult result = new CreateClaimResult();

        if (ownerID != null)
        {
            Supplier<String> minimumFailure = validateShapedCreationMinimums(world, polygon);
            if (minimumFailure != null)
            {
                result.succeeded = false;
                result.denialMessage = minimumFailure;
                return result;
            }
        }

        final Location smallerBoundaryCorner = new Location(world, polygon.minX(), y, polygon.minZ());
        final Location greaterBoundaryCorner = new Location(world, polygon.maxX(), y, polygon.maxZ());
        try {
            if (!world.getWorldBorder().isInside(smallerBoundaryCorner)
                    || !world.getWorldBorder().isInside(greaterBoundaryCorner)) {
                result.succeeded = false;
                return result;
            }
        } catch (NoSuchMethodError e) {
            // 1.8.8: WorldBorder.isInside() doesn't exist; assume inside border
        }

        Claim newClaim = new Claim(
                smallerBoundaryCorner,
                greaterBoundaryCorner,
                ownerID,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                false,
                null,
                false);
        newClaim.setShapedCorners(polygon.corners());

        for (Claim otherClaim : this.claims) {
            if (otherClaim.inDataStore && otherClaim.overlaps(newClaim)) {
                result.succeeded = false;
                result.claim = otherClaim;
                return result;
            }
        }

        if (GriefPrevention.instance.config_claims_respectWorldGuard
                && this.worldGuard != null
                && creatingPlayer != null
                && !this.worldGuard.canBuild(smallerBoundaryCorner, greaterBoundaryCorner, creatingPlayer)) {
            result.succeeded = false;
            result.claim = null;
            return result;
        }

        assignClaimID(newClaim);
        ClaimCreatedEvent event = new ClaimCreatedEvent(newClaim, creatingPlayer);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            result.succeeded = false;
            result.claim = null;
            return result;
        }

        this.addClaim(newClaim, true);
        result.succeeded = true;
        result.claim = newClaim;
        return result;
    }

    /**
     * Creates a shaped 2D subdivision inside {@code parent}.
     *
     * <p>Like rectangular 2D subdivisions, the result spans the parent's floor up to the world
     * height limit so it is never treated as a 3D claim. The polygon must lie entirely inside the
     * parent and must not overlap any sibling.
     *
     * @param world the world the parent claim lives in
     * @param polygon the subdivision outline
     * @param parent the claim being subdivided
     * @param creatingPlayer the player drawing the subdivision, if any
     * @return the creation result; on failure {@code claim} holds the conflicting claim when known
     */
    synchronized public CreateClaimResult createShapedSubdivision(
            @NotNull World world,
            @NotNull OrthogonalPolygon polygon,
            @NotNull Claim parent,
            @Nullable Player creatingPlayer)
    {
        CreateClaimResult result = new CreateClaimResult();

        if (parent.is3D())
        {
            result.succeeded = false;
            result.denialMessage = () -> this.getMessage(Messages.ShapedSubdivisionOn3DClaim);
            return result;
        }

        if (parent.parent != null && !GriefPrevention.instance.config_claims_allowNestedSubClaims)
        {
            result.succeeded = false;
            result.claim = parent;
            return result;
        }

        Supplier<String> minimumFailure = validateShapedCreationMinimums(world, polygon);
        if (minimumFailure != null)
        {
            result.succeeded = false;
            result.denialMessage = minimumFailure;
            return result;
        }

        int minY = parent.getLesserBoundaryCorner().getBlockY();
        int maxY = GriefPrevention.getWorldMaxY(world);
        Location lesserBoundaryCorner = new Location(world, polygon.minX(), minY, polygon.minZ());
        Location greaterBoundaryCorner = new Location(world, polygon.maxX(), maxY, polygon.maxZ());

        Claim newClaim = new Claim(
                lesserBoundaryCorner,
                greaterBoundaryCorner,
                null,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                false,
                null,
                false);
        newClaim.parent = parent;
        newClaim.setShapedCorners(polygon.corners());

        if ("INHERIT".equalsIgnoreCase(GriefPrevention.instance.config_pvp_subdivisionPvpState))
        {
            newClaim.pvpEnabled = parent.pvpEnabled;
            newClaim.pvpToggleSet = parent.pvpToggleSet;
        }

        if (!containsChild(parent, newClaim))
        {
            result.succeeded = false;
            result.claim = parent;
            result.denialMessage = () -> this.getMessage(Messages.ShapedSubdivisionOutsideParent);
            return result;
        }

        for (Claim sibling : parent.children)
        {
            if (sibling == null || !sibling.inDataStore || sibling == newClaim) continue;

            if (sibling.overlaps(newClaim))
            {
                result.succeeded = false;
                result.claim = sibling;
                result.denialMessage = () -> this.getMessage(Messages.CreateSubdivisionOverlap);
                return result;
            }
        }

        newClaim.setSubclaimRestrictions(parent.parent != null || parent.getInheritNothingForNewSubdivisions());

        assignClaimID(newClaim);
        ClaimCreatedEvent event = new ClaimCreatedEvent(newClaim, creatingPlayer);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
        {
            result.succeeded = false;
            result.claim = null;
            return result;
        }

        this.addClaim(newClaim, true);

        // First-child subdivisions persist a copy of the parent's trust, matching createClaim().
        if (parent.parent == null)
        {
            ArrayList<String> builders = new ArrayList<>();
            ArrayList<String> containers = new ArrayList<>();
            ArrayList<String> accessors = new ArrayList<>();
            ArrayList<String> managers = new ArrayList<>();
            parent.getPermissions(builders, containers, accessors, managers);
            for (String identifier : builders) newClaim.setPermission(identifier, ClaimPermission.Build);
            for (String identifier : containers) newClaim.setPermission(identifier, ClaimPermission.Container);
            for (String identifier : accessors) newClaim.setPermission(identifier, ClaimPermission.Access);
            for (String identifier : managers) newClaim.addManager(identifier);
        }

        // Manage trust is additive, so this leaves any build/container/access trust the creator
        // inherited from the parent claim intact.
        if (creatingPlayer != null && !creatingPlayer.getUniqueId().equals(newClaim.getOwnerID()))
        {
            newClaim.addManager(creatingPlayer.getUniqueId().toString());
        }

        result.succeeded = true;
        result.claim = newClaim;
        return result;
    }

    // creates a claim.
    // if the new claim would overlap an existing claim, returns a failure along
    // with a reference to the existing claim
    // if the new claim would overlap a WorldGuard region where the player doesn't
    // have permission to build, returns a failure with NULL for claim
    // otherwise, returns a success along with a reference to the new claim
    // use ownerName == "" for administrative claims
    // for top level claims, pass parent == NULL
    // DOES adjust claim blocks available on success (players can go into negative
    // quantity available)
    // DOES check for world guard regions where the player doesn't have permission
    // does NOT check a player has permission to create a claim, or enough claim
    // blocks.
    // does NOT check minimum claim size constraints
    // does NOT visualize the new claim for any players
    synchronized public CreateClaimResult createClaim(World world, int x1, int x2, int y1, int y2, int z1, int z2,
            UUID ownerID, Claim parent, Long id, Player creatingPlayer, boolean dryRun, boolean is3D) {
        CreateClaimResult result = new CreateClaimResult();

        int smallx, bigx, smally, bigy, smallz, bigz;

        int worldMinY = GriefPrevention.getWorldMinY(world);
        // Only apply max depth clamp to non-3D claims. 3D claims have explicit Y bounds
        // set by player clicks and should not be forced toward the max depth setting.
        if (!is3D) {
            y1 = Math.max(worldMinY, Math.max(GriefPrevention.instance.getMaxDepthForWorld(world), y1));
            y2 = Math.max(worldMinY, Math.max(GriefPrevention.instance.getMaxDepthForWorld(world), y2));
        }

        // determine small versus big inputs
        if (x1 < x2) {
            smallx = x1;
            bigx = x2;
        } else {
            smallx = x2;
            bigx = x1;
        }

        if (y1 < y2) {
            smally = y1;
            bigy = y2;
        } else {
            smally = y2;
            bigy = y1;
        }

        if (z1 < z2) {
            smallz = z1;
            bigz = z2;
        } else {
            smallz = z2;
            bigz = z1;
        }

        // If is3D is explicitly provided, use it. Otherwise auto-detect for subdivisions.
        boolean autoDetectIs3D = false;
        if (parent != null) {
            Location lesser = parent.getLesserBoundaryCorner();
            Location greater = parent.getGreaterBoundaryCorner();
            if (smallx < lesser.getX() || smallz < lesser.getZ() || bigx > greater.getX() || bigz > greater.getZ()) {
                result.succeeded = false;
                result.claim = parent;
                return result;
            }

            int parentBottomY = lesser.getBlockY();
            int worldMaxY = GriefPrevention.getWorldMaxY(world);
            boolean spansFullHeight = (smally == parentBottomY) && (bigy == worldMaxY);
            autoDetectIs3D = !spansFullHeight;

            if (GriefPrevention.instance.config_claims_allowNestedSubClaims && parent.is3D()) {
                int inset = 1;

                if (inset > 0) {
                    // Only enforce Y-axis inset for 3D claims, allow X/Z boundaries to match parent
                    int minAllowedY = lesser.getBlockY() + inset;
                    int maxAllowedY = greater.getBlockY() - inset;
                    if (smally < minAllowedY || bigy > maxAllowedY) {
                        result.succeeded = false;
                        result.claim = parent;
                        return result;
                    }
                }
            }

            // No X/Z inset enforcement for subdivisions - allow them to share parent boundaries
            // Preserve exact Y boundaries for subclaims (including single-layer 3D).
            // Previously, zero-height subclaims were sanitized to parent's bottom,
            // unintentionally
            // creating tall subclaims. We no longer do that here.
        }

        // Use auto-detected value for subdivisions, or explicit is3D parameter for top-level claims
        is3D = is3D || autoDetectIs3D;

        // claims can't be made outside the world border
        final Location smallerBoundaryCorner = new Location(world, smallx, smally, smallz);
        final Location greaterBoundaryCorner = new Location(world, bigx, bigy, bigz);
        try {
            if (!world.getWorldBorder().isInside(smallerBoundaryCorner)
                    || !world.getWorldBorder().isInside(greaterBoundaryCorner)) {
                result.succeeded = false;
                return result;
            }
        } catch (NoSuchMethodError e) {
            // 1.8.8: WorldBorder.isInside() doesn't exist; assume inside border
        }

        // creative mode claims always go to bedrock
        Claim newClaim = new Claim(
                smallerBoundaryCorner,
                greaterBoundaryCorner,
                ownerID,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                false,
                id,
                is3D);

        newClaim.parent = parent;

        if (parent != null && "INHERIT".equalsIgnoreCase(GriefPrevention.instance.config_pvp_subdivisionPvpState)) {
            newClaim.pvpEnabled = parent.pvpEnabled;
            newClaim.pvpToggleSet = parent.pvpToggleSet;
        }

        // Never allow nested subdivisions when disabled, regardless of caller path.
        if (parent != null
                && parent.parent != null
                && !GriefPrevention.instance.config_claims_allowNestedSubClaims) {
            result.succeeded = false;
            result.claim = parent;
            return result;
        }

        // Subdivisions must remain fully inside the parent claim, including shaped
        // claim outlines.
        if (parent != null && !containsChild(parent, newClaim)) {
            result.succeeded = false;
            result.claim = parent;
            return result;
        }

        ArrayList<Claim> claimsToCheck;
        if (parent != null) {
            // First-child subdivisions inherit from parent; nested subdivisions do not.
            boolean isNested = parent.parent != null;
            // Also check if parent has inheritNothingForNewSubdivisions set
            boolean parentRestrictsFutureSubdivisions = parent.getInheritNothingForNewSubdivisions();
            newClaim.setSubclaimRestrictions(isNested || parentRestrictsFutureSubdivisions);
            claimsToCheck = newClaim.parent.children;
        } else {
            claimsToCheck = this.claims;
        }

        for (Claim otherClaim : claimsToCheck) {
            // Never treat the parent itself as an overlap when creating a nested
            // subdivision.
            if (otherClaim == newClaim.parent) {
                continue;
            }

            // if we find an existing claim which will be overlapped
            if (otherClaim.id != newClaim.id && otherClaim.inDataStore && otherClaim.overlaps(newClaim)) {
                // Allow vertically separated 3D subdivisions inside the same parent when
                // enabled.
                if (newClaim.parent != null
                        && GriefPrevention.instance.config_claims_allowNestedSubClaims
                        && newClaim.is3D()
                        && otherClaim.is3D()
                        && otherClaim.parent == newClaim.parent) {
                    BoundingBox newBox = new BoundingBox(newClaim);
                    BoundingBox otherBox = new BoundingBox(otherClaim);

                    boolean horizontalOverlap = newBox.getMinX() <= otherBox.getMaxX()
                            && newBox.getMaxX() >= otherBox.getMinX()
                            && newBox.getMinZ() <= otherBox.getMaxZ()
                            && newBox.getMaxZ() >= otherBox.getMinZ();
                    boolean verticalSeparated = newBox.getMaxY() < otherBox.getMinY()
                            || newBox.getMinY() > otherBox.getMaxY();

                    if (horizontalOverlap) {
                        if (newClaim.parent.is3D()) {
                            if (verticalSeparated) {
                                continue;
                            }
                        } else if (verticalSeparated) {
                            continue;
                        }
                    }
                }

                // Allow 2D siblings to touch borders without being considered overlapping.
                if (!newClaim.is3D()
                        && !otherClaim.is3D()
                        && otherClaim.parent == newClaim.parent) {
                    int overlapWidth = Math.min(
                            newClaim.getGreaterBoundaryCorner().getBlockX(),
                            otherClaim.getGreaterBoundaryCorner().getBlockX())
                            - Math.max(
                                    newClaim.getLesserBoundaryCorner().getBlockX(),
                                    otherClaim.getLesserBoundaryCorner().getBlockX())
                            + 1;
                    int overlapHeight = Math.min(
                            newClaim.getGreaterBoundaryCorner().getBlockZ(),
                            otherClaim.getGreaterBoundaryCorner().getBlockZ())
                            - Math.max(
                                    newClaim.getLesserBoundaryCorner().getBlockZ(),
                                    otherClaim.getLesserBoundaryCorner().getBlockZ())
                            + 1;

                    if (overlapWidth <= 0 || overlapHeight <= 0) {
                        continue; // Touching edge or corner—no actual overlap volume.
                    }
                }

                // For top-level claims, don't fail if the overlapping claim is one of our own
                // descendants (any depth)
                // Subdivisions are supposed to be contained within their parent claim hierarchy
                if (newClaim.parent == null && otherClaim.parent != null) {
                    Claim cursor = otherClaim.parent;
                    while (cursor != null) {
                        if (cursor.id != null && cursor.id.equals(newClaim.id)) {
                            // Overlap with a descendant; allowed
                            otherClaim = null; // mark as handled
                            break;
                        }
                        cursor = cursor.parent;
                    }
                    if (otherClaim == null) {
                        continue;
                    }
                }

                // Prevent parent/admin claims from overlapping other parent/admin claims
                // But allow subdivisions to be created on the same X/Z borders
                if (newClaim.parent == null && otherClaim.parent == null) {
                    // Allow vertically separated 3D top-level claims (e.g. stacked admin claims)
                    if (newClaim.is3D() && otherClaim.is3D()) {
                        BoundingBox newBox = new BoundingBox(newClaim);
                        BoundingBox otherBox = new BoundingBox(otherClaim);

                        boolean horizontalOverlap = newBox.getMinX() <= otherBox.getMaxX()
                                && newBox.getMaxX() >= otherBox.getMinX()
                                && newBox.getMinZ() <= otherBox.getMaxZ()
                                && newBox.getMaxZ() >= otherBox.getMinZ();
                        boolean verticalSeparated = newBox.getMaxY() < otherBox.getMinY()
                                || newBox.getMinY() > otherBox.getMaxY();

                        if (horizontalOverlap && verticalSeparated) {
                            continue; // Allow vertical stacking
                        }
                    }

                    // Both are top-level claims (parent or admin claims) - prevent overlap
                    result.succeeded = false;
                    result.claim = otherClaim;
                    return result;
                }

                // Special case: When resizing a top-level claim (dry run via createClaim during
                // resize),
                // allow zero-area intersection (edge/corner touching) with neighboring claims.
                // This prevents false-positive conflicts when expanding from a corner shared
                // with a child subdivision
                // or when only borders touch, while still blocking any real area overlap.
                if (dryRun && newClaim.parent == null) {
                    // Compute X/Z overlap extents (inclusive bounds)
                    int aMinX = newClaim.getLesserBoundaryCorner().getBlockX();
                    int aMaxX = newClaim.getGreaterBoundaryCorner().getBlockX();
                    int aMinZ = newClaim.getLesserBoundaryCorner().getBlockZ();
                    int aMaxZ = newClaim.getGreaterBoundaryCorner().getBlockZ();

                    int bMinX = otherClaim.getLesserBoundaryCorner().getBlockX();
                    int bMaxX = otherClaim.getGreaterBoundaryCorner().getBlockX();
                    int bMinZ = otherClaim.getLesserBoundaryCorner().getBlockZ();
                    int bMaxZ = otherClaim.getGreaterBoundaryCorner().getBlockZ();

                    int overlapX = Math.min(aMaxX, bMaxX) - Math.max(aMinX, bMinX);
                    int overlapZ = Math.min(aMaxZ, bMaxZ) - Math.max(aMinZ, bMinZ);

                    // If either dimension has zero overlap width, this is an edge or corner touch,
                    // not an area overlap.
                    boolean zeroAreaTouch = overlapX == 0 || overlapZ == 0;
                    if (zeroAreaTouch) {
                        // Treat border/corner touching as non-conflict in resize dry-run of a top-level
                        // claim.
                        continue;
                    }
                }

                // result = fail, return conflicting claim
                result.succeeded = false;
                result.claim = otherClaim;
                return result;
            }
        }

        // Match legacy/v16: a claim may not cover a WorldGuard region unless its
        // creator has the WorldGuard BUILD flag throughout the proposed footprint.
        if (GriefPrevention.instance.config_claims_respectWorldGuard
                && this.worldGuard != null
                && creatingPlayer != null
                && !this.worldGuard.canBuild(newClaim.lesserBoundaryCorner,
                        newClaim.greaterBoundaryCorner, creatingPlayer)) {
            result.succeeded = false;
            result.claim = null;
            return result;
        }

        // Minimum distance check for top-level claims
        if (newClaim.parent == null
                && GriefPrevention.instance.config_claims_minimumDistance > 0
                && !dryRun) {
            int minDist = GriefPrevention.instance.config_claims_minimumDistance;
            UUID ownerUUID = newClaim.getOwnerID();
            for (Claim otherClaim : this.claims) {
                if (!otherClaim.inDataStore) continue;
                if (otherClaim.parent != null) continue;
                if (otherClaim.id != null && newClaim.id != null && otherClaim.id.equals(newClaim.id)) continue;
                if (otherClaim.getOwnerID() != null && otherClaim.getOwnerID().equals(ownerUUID)) continue;
                if (!otherClaim.getLesserBoundaryCorner().getWorld().equals(world)) continue;

                if (otherClaim.isNear(newClaim.lesserBoundaryCorner, minDist) || otherClaim.isNear(newClaim.greaterBoundaryCorner, minDist)) {
                    // Check if the nearby claim allows all neighbors to bypass
                    if (otherClaim.allowAllNeighbors) {
                        continue;
                    }
                    // Check if creating player has neighbor trust (manual or auto) on the nearby claim
                    boolean hasNeighborTrust = false;
                    if (creatingPlayer != null && otherClaim.hasNeighborTrust(creatingPlayer.getUniqueId().toString())) {
                        hasNeighborTrust = true;
                    }
                    if (!hasNeighborTrust) {
                        result.succeeded = false;
                        result.claim = otherClaim;
                        result.denialMessage = () -> {
                            String ownerName = otherClaim.getOwnerName();
                            return GriefPrevention.instance.dataStore.getMessage(
                                    Messages.CreateClaimFailTooClose, ownerName, String.valueOf(minDist));
                        };
                        return result;
                    }
                }
            }
        }

        if (dryRun) {
            // since this is a dry run, just return the unsaved claim as is.
            result.succeeded = true;
            result.claim = newClaim;
            return result;
        }
        assignClaimID(newClaim); // assign a claim ID before calling event, in case a plugin wants to know the
                                 // ID.
        ClaimCreatedEvent event = new ClaimCreatedEvent(newClaim, creatingPlayer);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            result.succeeded = false;
            result.claim = null;
            return result;

        }
        // otherwise add this new claim to the data store to make it effective
        this.addClaim(newClaim, true);

        // First-child subdivisions (2D and 3D): copy parent permissions into the new claim so they are
        // persisted in YAML. 2D gets propagation when /trust runs on existing children; new subdivisions
        // created after /trust need explicit copy. 3D does not get setPermission propagation, so always copy.
        if (newClaim.parent != null && newClaim.parent.parent == null) {
            
            ArrayList<String> builders = new ArrayList<>();
            ArrayList<String> containers = new ArrayList<>();
            ArrayList<String> accessors = new ArrayList<>();
            ArrayList<String> managers = new ArrayList<>();
            newClaim.parent.getPermissions(builders, containers, accessors, managers);
            for (String identifier : builders) newClaim.setPermission(identifier, ClaimPermission.Build);
            for (String identifier : containers) newClaim.setPermission(identifier, ClaimPermission.Container);
            for (String identifier : accessors) newClaim.setPermission(identifier, ClaimPermission.Access);
            for (String identifier : managers) newClaim.addManager(identifier);
        }

        // Ensure the creating player has Manage on new subdivisions for cascading trust.  Manager
        // trust is additive, so their inherited build/container/access trust survives this.
        if (parent != null && creatingPlayer != null
                && !creatingPlayer.getUniqueId().equals(newClaim.getOwnerID())) {
            newClaim.addManager(creatingPlayer.getUniqueId().toString());
        }

        // then return success along with reference to new claim
        result.succeeded = true;
        result.claim = newClaim;
        return result;
    }

    // saves changes to player data to secondary storage. MUST be called after
    // you're done making changes, otherwise a reload will lose them
    public void savePlayerDataSync(UUID playerID, PlayerData playerData) {
        // ensure player data is already read from file before trying to save
        playerData.getAccruedClaimBlocks();
        playerData.getClaims();

        this.asyncSavePlayerData(playerID, playerData);
    }

    // saves changes to player data to secondary storage. MUST be called after
    // you're done making changes, otherwise a reload will lose them
    public void savePlayerData(UUID playerID, PlayerData playerData) {
        new SavePlayerDataThread(playerID, playerData).start();
    }

    @SuppressWarnings("null")
    public void asyncSavePlayerData(UUID playerID, PlayerData playerData) {
        // save everything except the ignore list
        this.overrideSavePlayerData(playerID, playerData);

        // save the ignore list
        if (playerData.ignoreListChanged) {
            StringBuilder fileContent = new StringBuilder();
            try {
                for (UUID uuidKey : playerData.ignoredPlayers.keySet()) {
                    Boolean value = playerData.ignoredPlayers.get(uuidKey);
                    if (value == null)
                        continue;

                    // admin-enforced ignores begin with an asterisk
                    if (value) {
                        fileContent.append("*");
                    }

                    fileContent.append(uuidKey);
                    fileContent.append("\n");
                }

                // write data to file
                File playerDataFile = new File(playerDataFolderPath + File.separator + playerID + ".ignore");
                byte[] bytes = fileContent.toString().trim().getBytes(StandardCharsets.UTF_8);
                Files.write(bytes, playerDataFile);
            }

            // if any problem, log it
            catch (Exception e) {
                Bukkit.getLogger().info("GriefPrevention: Unexpected exception saving data for player \""
                        + playerID.toString() + "\": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    abstract void overrideSavePlayerData(UUID playerID, PlayerData playerData);

    /**
     * Merges two claims together.
     * If mergeEdgeIndex is provided (for shaped claims), only merges along that specific edge/nib.
     * Collapses claim IDs by deleting the second claim and updating the first.
     *
     * @param player The player performing the merge
     * @param playerData The player's data
     * @param firstClaim The first claim (will be kept and expanded)
     * @param secondClaim The second claim (will be deleted)
     * @param mergeEdgeIndex Optional edge index for shaped claims to limit merge width
     */
    synchronized public void mergeClaims(Player player, PlayerData playerData, Claim firstClaim, Claim secondClaim, Integer mergeEdgeIndex) {
        mergeClaims(player, playerData, firstClaim, secondClaim, mergeEdgeIndex, null, null);
    }

    synchronized public void mergeClaims(Player player, PlayerData playerData, Claim firstClaim, Claim secondClaim, Integer mergeEdgeIndex, Set<OrthogonalPoint2i> preferredConnectionCells) {
        mergeClaims(player, playerData, firstClaim, secondClaim, mergeEdgeIndex, preferredConnectionCells, null);
    }

    synchronized public void mergeClaims(Player player, PlayerData playerData, Claim firstClaim, Claim secondClaim, Integer mergeEdgeIndex, Set<OrthogonalPoint2i> preferredConnectionCells, OrthogonalPolygon firstPolygonOverride) {
        // Ensure both claims are top-level
        while (firstClaim.parent != null) {
            firstClaim = firstClaim.parent;
        }
        while (secondClaim.parent != null) {
            secondClaim = secondClaim.parent;
        }

        // Validate claims are in the same world
        if (!firstClaim.getLesserBoundaryCorner().getWorld().equals(secondClaim.getLesserBoundaryCorner().getWorld())) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Cannot merge claims in different worlds.");
            playerData.claimMerging = null;
            playerData.mergeEdgeIndex = null;
            playerData.mergeSecondEdgeIndex = null;
            playerData.mergeFirstDepthPoint = null;
            playerData.mergeSecondDepthPoint = null;
            playerData.shovelMode = ShovelMode.Basic;
            return;
        }

        // Get polygons for both claims
        OrthogonalPolygon firstPolygon = firstPolygonOverride != null ? firstPolygonOverride : firstClaim.getBoundaryPolygon();
        OrthogonalPolygon secondPolygon = secondClaim.getBoundaryPolygon();

        // A direct /mergeclaims of two rectangles is always their global bounding rectangle.
        // Shaped claims instead keep their Manhattan route, widened to the depth-selected nibs.
        OrthogonalPolygon mergedPolygon = buildCorridorMerge(
                firstPolygon,
                mergeEdgeIndex,
                playerData.mergeFirstDepthPoint,
                secondPolygon,
                playerData.mergeSecondEdgeIndex,
                playerData.mergeSecondDepthPoint,
                preferredConnectionCells);

        // Adjacent claims, reshape-driven merges, and merges without enough nib information use
        // the existing polygon union behavior.
        if (mergedPolygon == null) {
            try {
                mergedPolygon = OrthogonalPolygon.union(firstPolygon, secondPolygon);
            } catch (IllegalArgumentException e) {
                // Contour tracing failed. Fall back to the bounding box of both claims,
                // which always produces a valid shape.
                int minX = Math.min(firstClaim.getLesserBoundaryCorner().getBlockX(), secondClaim.getLesserBoundaryCorner().getBlockX());
                int maxX = Math.max(firstClaim.getGreaterBoundaryCorner().getBlockX(), secondClaim.getGreaterBoundaryCorner().getBlockX());
                int minZ = Math.min(firstClaim.getLesserBoundaryCorner().getBlockZ(), secondClaim.getLesserBoundaryCorner().getBlockZ());
                int maxZ = Math.max(firstClaim.getGreaterBoundaryCorner().getBlockZ(), secondClaim.getGreaterBoundaryCorner().getBlockZ());

                List<OrthogonalPoint2i> rectCorners = new ArrayList<>();
                rectCorners.add(new OrthogonalPoint2i(minX, minZ));
                rectCorners.add(new OrthogonalPoint2i(maxX, minZ));
                rectCorners.add(new OrthogonalPoint2i(maxX, maxZ));
                rectCorners.add(new OrthogonalPoint2i(minX, maxZ));
                rectCorners.add(new OrthogonalPoint2i(minX, minZ));
                mergedPolygon = OrthogonalPolygon.fromClosedPath(rectCorners);
            }
        }

        // Validate merged polygon
        if (mergedPolygon == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Failed to merge claims: invalid resulting shape.");
            playerData.claimMerging = null;
            playerData.mergeEdgeIndex = null;
            playerData.mergeSecondEdgeIndex = null;
            playerData.mergeFirstDepthPoint = null;
            playerData.mergeSecondDepthPoint = null;
            playerData.shovelMode = ShovelMode.Basic;
            return;
        }

        // Check claim block limits
        int newArea = mergedPolygon.cellCount();
        int currentArea = firstClaim.getArea() + secondClaim.getArea();
        int areaDifference = newArea - currentArea;

        if (!firstClaim.isAdminClaim() && !secondClaim.isAdminClaim() && firstClaim.ownerID != null) {
            // Check against the claim owner's blocks, not the acting player's -
            // a trusted manager (not the owner) can also perform merges.
            PlayerData ownerData = firstClaim.ownerID.equals(playerData.playerID)
                    ? playerData
                    : this.getPlayerData(firstClaim.ownerID);
            int blocksRemaining = ownerData.getRemainingClaimBlocks() - areaDifference;
            if (blocksRemaining < 0) {
                GriefPrevention.sendMessage(player, TextMode.Err, "Not enough claim blocks to merge these claims.");
                playerData.claimMerging = null;
                playerData.mergeEdgeIndex = null;
                playerData.mergeSecondEdgeIndex = null;
                playerData.mergeFirstDepthPoint = null;
                playerData.mergeSecondDepthPoint = null;
                playerData.shovelMode = ShovelMode.Basic;
                return;
            }
        }

        // Validate that the merged shape can actually be represented before mutating
        // any state. If the union's corners don't form a valid closed polygon,
        // updateClaimPolygon() would otherwise silently fall back to the full
        // bounding-box rectangle, which can be larger than the area checked above -
        // letting a merge slip through that pushes the owner's claim blocks negative.
        List<OrthogonalPoint2i> mergedCorners = mergedPolygon.corners();
        if (mergedCorners.size() >= 4) {
            List<OrthogonalPoint2i> closedPath = new ArrayList<>(mergedCorners);
            closedPath.add(closedPath.get(0));
            try {
                OrthogonalPolygon.fromClosedPath(closedPath);
            } catch (Exception e) {
                GriefPrevention.sendMessage(player, TextMode.Err, "Failed to merge claims: invalid resulting shape.");
                playerData.claimMerging = null;
                playerData.mergeEdgeIndex = null;
                playerData.mergeSecondEdgeIndex = null;
                playerData.mergeFirstDepthPoint = null;
                playerData.mergeSecondDepthPoint = null;
                playerData.shovelMode = ShovelMode.Basic;
                return;
            }
        }

        // Check for overlaps with other claims. The merged shape can reach well past either
        // original claim, so scan everything under the merged shape rather than only the
        // area around the first claim.
        List<Claim> overlappingClaims = findMergeConflicts(
                mergedPolygon,
                getClaimsUnder(firstClaim.getLesserBoundaryCorner().getWorld(), mergedPolygon),
                firstClaim,
                secondClaim);

        if (!overlappingClaims.isEmpty()) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.MergeOverlapConflict);
            // Visualize all overlapping claims
            for (Claim overlapping : overlappingClaims) {
                BoundaryVisualization.visualizeClaim(player, overlapping, VisualizationType.CONFLICT_ZONE);
            }
            playerData.claimMerging = null;
            playerData.mergeEdgeIndex = null;
            playerData.mergeSecondEdgeIndex = null;
            playerData.mergeFirstDepthPoint = null;
            playerData.mergeSecondDepthPoint = null;
            playerData.shovelMode = ShovelMode.Basic;
            return;
        }

        // Handle subdivisions
        // Move all subdivisions from secondClaim to firstClaim
        if (secondClaim.children != null && !secondClaim.children.isEmpty()) {
            for (Claim subdivision : new ArrayList<>(secondClaim.children)) {
                subdivision.parent = firstClaim;
                if (firstClaim.children == null) {
                    firstClaim.children = new ArrayList<>();
                }
                if (!firstClaim.children.contains(subdivision)) {
                    firstClaim.children.add(subdivision);
                }
                // Save each subdivision with updated parent
                saveClaim(subdivision);
            }
            secondClaim.children.clear();
        }

        // Update first claim with new polygon
        removeFromChunkClaimMap(firstClaim);
        updateClaimPolygon(firstClaim, mergedPolygon);
        addToChunkClaimMap(firstClaim);
        indexClaimSnapshot(firstClaim);

        // Save the updated first claim to storage
        saveClaim(firstClaim);

        // Delete second claim (this clears all visualizations)
        deleteClaim(secondClaim);

        // Clear merge state but keep the player in merge mode so they can
        // merge again without re-running /mergeclaims
        playerData.claimMerging = null;
        playerData.mergeEdgeIndex = null;
        playerData.mergeSecondEdgeIndex = null;
        playerData.mergeFirstDepthPoint = null;
        playerData.mergeSecondDepthPoint = null;

        // Re-visualize the merged claim after deleteClaim cleared everything.
        // Use mergeNearbyClaims to ensure the visualization persists alongside
        // the periodic task that runs for shaped mode players.
        Set<Claim> mergedClaimSet = new HashSet<>();
        mergedClaimSet.add(firstClaim);
        BoundaryVisualization.mergeNearbyClaims(player, mergedClaimSet);

        GriefPrevention.sendMessage(player, TextMode.Success, "Claims merged successfully.");
    }

    public OrthogonalPolygon unionPolygons(OrthogonalPolygon first, OrthogonalPolygon second) {
        // Delegate to OrthogonalPolygon.union, including its existing disconnected-shape route.
        return OrthogonalPolygon.union(first, second);
    }

    /**
     * Choose the geometry added between claims during a direct merge.
     * Rectangular pairs always become one global rectangle. Shaped pairs retain a Manhattan
     * route, with each leg widened to the depth-selected nib at that end.
     *
     * @return the direct-merge shape, or null when the existing union/reshape route should handle
     *         the claims instead
     */
    static @Nullable OrthogonalPolygon buildCorridorMerge(
            @NotNull OrthogonalPolygon firstPolygon,
            @Nullable Integer firstEdgeIndex,
            @Nullable OrthogonalPoint2i firstDepthPoint,
            @NotNull OrthogonalPolygon secondPolygon,
            @Nullable Integer secondEdgeIndex,
            @Nullable OrthogonalPoint2i secondDepthPoint,
            @Nullable Set<OrthogonalPoint2i> preferredConnectionCells) {
        // A reshape path already describes how the player wants the claims joined.
        if (preferredConnectionCells != null && !preferredConnectionCells.isEmpty()) {
            return null;
        }

        // This is deliberately before the connectivity check. Adjacent, staggered rectangles
        // can otherwise union into an L, but /mergeclaims promises a rectangle for this pair.
        if (isRectangle(firstPolygon) && isRectangle(secondPolygon)) {
            return OrthogonalPolygon.fromRectangle(
                    Math.min(firstPolygon.minX(), secondPolygon.minX()),
                    Math.min(firstPolygon.minZ(), secondPolygon.minZ()),
                    Math.max(firstPolygon.maxX(), secondPolygon.maxX()),
                    Math.max(firstPolygon.maxZ(), secondPolygon.maxZ()));
        }

        Set<OrthogonalPoint2i> occupied = collectCells(firstPolygon, secondPolygon);
        if (occupied.isEmpty() || isConnected(occupied)) {
            return null;
        }

        List<MergeCorridor.Extent> corridor = MergeCorridor.connect(
                firstPolygon, firstEdgeIndex, firstDepthPoint,
                secondPolygon, secondEdgeIndex, secondDepthPoint);
        if (corridor == null || corridor.isEmpty()) {
            return null;
        }

        for (MergeCorridor.Extent leg : corridor) {
            for (int x = leg.minX(); x <= leg.maxX(); x++) {
                for (int z = leg.minZ(); z <= leg.maxZ(); z++) {
                    occupied.add(new OrthogonalPoint2i(x, z));
                }
            }
        }

        try {
            return OrthogonalPolygon.fromOccupiedPoints(occupied);
        } catch (IllegalArgumentException e) {
            // Contour tracing failed - let the caller fall back to a plain union.
            return null;
        }
    }

    private static boolean isRectangle(@NotNull OrthogonalPolygon polygon) {
        return polygon.corners().size() == 4;
    }

    private static @NotNull Set<OrthogonalPoint2i> collectCells(
            @NotNull OrthogonalPolygon first,
            @NotNull OrthogonalPolygon second) {
        Set<OrthogonalPoint2i> occupied = new HashSet<>();
        int minX = Math.min(first.minX(), second.minX());
        int maxX = Math.max(first.maxX(), second.maxX());
        int minZ = Math.min(first.minZ(), second.minZ());
        int maxZ = Math.max(first.maxZ(), second.maxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (first.containsCell(x, z) || second.containsCell(x, z)) {
                    occupied.add(new OrthogonalPoint2i(x, z));
                }
            }
        }

        return occupied;
    }

    private static boolean isConnected(@NotNull Set<OrthogonalPoint2i> occupied) {
        Set<OrthogonalPoint2i> unvisited = new HashSet<>(occupied);
        java.util.Deque<OrthogonalPoint2i> queue = new java.util.ArrayDeque<>();
        OrthogonalPoint2i start = unvisited.iterator().next();
        unvisited.remove(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            OrthogonalPoint2i cell = queue.poll();
            int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] neighbor : neighbors) {
                OrthogonalPoint2i next = new OrthogonalPoint2i(cell.x() + neighbor[0], cell.z() + neighbor[1]);
                if (unvisited.remove(next)) {
                    queue.add(next);
                }
            }
        }

        return unvisited.isEmpty();
    }

    /**
     * All top-level claims whose bounding box could touch the given shape.
     */
    @NotNull Set<Claim> getClaimsUnder(@Nullable World world, @NotNull OrthogonalPolygon polygon) {
        if (world == null) return Collections.emptySet();

        // Y is irrelevant here - chunk lookup only uses the horizontal bounds.
        return getChunkClaims(world, new BoundingBox(
                polygon.minX(), 0, polygon.minZ(),
                polygon.maxX(), 0, polygon.maxZ()));
    }

    /**
     * Find the claims a merged shape would overlap. Claims belonging to the merging owner count
     * as conflicts too - swallowing one of the owner's other claims would leave two claims
     * sitting on the same blocks.
     *
     * @param mergedPolygon the shape the merge would produce
     * @param candidates the claims to test against
     * @param firstClaim the claim being expanded
     * @param secondClaim the claim being absorbed
     * @return the conflicting claims, empty when the merge is clear
     */
    static @NotNull List<Claim> findMergeConflicts(
            @NotNull OrthogonalPolygon mergedPolygon,
            @NotNull Collection<Claim> candidates,
            @NotNull Claim firstClaim,
            @NotNull Claim secondClaim) {
        List<Claim> conflicts = new ArrayList<>();
        for (Claim candidate : candidates) {
            if (candidate.parent != null) {
                // Subdivisions move with their parent.
                continue;
            }

            if (Objects.equals(candidate.getID(), firstClaim.getID())
                    || Objects.equals(candidate.getID(), secondClaim.getID())) {
                continue;
            }

            if (polygonsOverlap(mergedPolygon, candidate.getBoundaryPolygon())) {
                conflicts.add(candidate);
            }
        }

        return conflicts;
    }

    static boolean polygonsOverlap(OrthogonalPolygon polygon1, OrthogonalPolygon polygon2) {
        // Check if bounding boxes overlap
        int minX = Math.max(polygon1.minX(), polygon2.minX());
        int maxX = Math.min(polygon1.maxX(), polygon2.maxX());
        int minZ = Math.max(polygon1.minZ(), polygon2.minZ());
        int maxZ = Math.min(polygon1.maxZ(), polygon2.maxZ());
        if (minX > maxX || minZ > maxZ) {
            return false;
        }

        // Check if any corner of either polygon is inside the other. This is cheap and settles
        // the common cases without walking the whole overlapping region.
        for (OrthogonalPoint2i corner : polygon1.corners()) {
            if (polygon2.contains(corner)) {
                return true;
            }
        }

        for (OrthogonalPoint2i corner : polygon2.corners()) {
            if (polygon1.contains(corner)) {
                return true;
            }
        }

        // Shapes can cross without either one's corners landing inside the other - a corridor
        // laid straight across a narrow claim, for example. Walk the shared region for a cell
        // that belongs to both.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (polygon1.containsCell(x, z) && polygon2.containsCell(x, z)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void updateClaimPolygon(Claim claim, OrthogonalPolygon polygon) {
        // Update claim boundaries from polygon
        List<OrthogonalPoint2i> corners = polygon.corners();
        if (corners.size() >= 4) {
            int minX = polygon.minX();
            int minZ = polygon.minZ();
            int maxX = polygon.maxX();
            int maxZ = polygon.maxZ();

            World world = claim.getLesserBoundaryCorner().getWorld();
            int minY = claim.getLesserBoundaryCorner().getBlockY();
            int maxY = claim.getGreaterBoundaryCorner().getBlockY();

            claim.lesserBoundaryCorner = new Location(world, minX, minY, minZ);
            claim.greaterBoundaryCorner = new Location(world, maxX, maxY, maxZ);
            // Preserve the shaped corners so the polygon shape is retained.
            // If the polygon fails validation (e.g., self-intersecting contour),
            // fall back to a rectangle so the claim doesn't lose its shaped status.
            try
            {
                claim.setShapedCorners(corners);
            }
            catch (Exception e)
            {
                // Fallback: use the bounding box as a simple rectangle
                List<OrthogonalPoint2i> rectangle = Arrays.asList(
                        new OrthogonalPoint2i(minX, minZ),
                        new OrthogonalPoint2i(maxX, minZ),
                        new OrthogonalPoint2i(maxX, maxZ),
                        new OrthogonalPoint2i(minX, maxZ),
                        new OrthogonalPoint2i(minX, minZ)
                );
                claim.setShapedCorners(rectangle);
            }
        }
    }

    // extends a claim to a new depth
    // respects the max depth config variable
    synchronized public void extendClaim(Claim claim, int newDepth) {
        if (claim.parent != null)
            claim = claim.parent;

        // Skip 3D claims - they have explicit Y bounds and should not be extended
        if (claim.is3D()) return;

        newDepth = sanitizeClaimDepth(claim, newDepth);

        // call event and return if event got cancelled
        ClaimExtendEvent event = new ClaimExtendEvent(claim, newDepth);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return;

        // adjust to new depth
        setNewDepth(claim, event.getNewDepth());
    }

    /**
     * Helper method for sanitizing claim depth to find the minimum expected value.
     *
     * @param claim    the claim
     * @param newDepth the new depth
     * @return the sanitized new depth
     */
    private int sanitizeClaimDepth(Claim claim, int newDepth) {
        if (claim.parent != null)
            claim = claim.parent;

        // For 3D subdivisions, don't extend parent claim to encompass them
        // Only consider non-3D children when calculating depth
        int oldDepth = Math.min(
                claim.getLesserBoundaryCorner().getBlockY(),
                claim.children.stream()
                        .filter(child -> !child.is3D()) // Only consider non-3D children
                        .mapToInt(child -> child.getLesserBoundaryCorner().getBlockY())
                        .min().orElse(Integer.MAX_VALUE));

        // Use the lowest of the old and new depths.
        newDepth = Math.min(newDepth, oldDepth);
        // Cap the depth to the world's minimum height.
        World world = Objects.requireNonNull(claim.getLesserBoundaryCorner().getWorld());
        // Cap depth to maximum depth allowed by the configuration.
        newDepth = Math.max(newDepth, GriefPrevention.instance.getMaxDepthForWorld(world));
        newDepth = Math.max(newDepth, GriefPrevention.getWorldMinY(world));

        return newDepth;
    }

    /**
     * Helper method for sanitizing and setting claim depth. Saves affected claims.
     *
     * @param claim    the claim
     * @param newDepth the new depth
     */
    private void setNewDepth(Claim claim, int newDepth) {
        if (claim.parent != null)
            claim = claim.parent;

        // Skip 3D claims entirely - they have explicit Y bounds
        if (claim.is3D()) return;

        final int depth = sanitizeClaimDepth(claim, newDepth);

        // Adjust depth for the parent claim and NON-3D children only.
        // 3D subdivisions have explicit Y bounds and must not be altered here.
        Stream.concat(
                Stream.of(claim),
                claim.children.stream().filter(child -> !child.is3D())).forEach(localClaim -> {
                    localClaim.lesserBoundaryCorner.setY(depth);
                    localClaim.greaterBoundaryCorner
                            .setY(Math.max(localClaim.greaterBoundaryCorner.getBlockY(), depth));
                    this.indexClaimSnapshot(localClaim);
                    this.saveClaim(localClaim);
                });
    }

    // Starts a siege after the command handler has validated both participants.
    synchronized public void startSiege(Player attacker, Player defender, Claim defenderClaim) {
        SiegeData siege = new SiegeData(attacker, defender, defenderClaim);
        this.getPlayerData(attacker.getUniqueId()).siegeData = siege;
        this.getPlayerData(defender.getUniqueId()).siegeData = siege;
        defenderClaim.siegeData = siege;
        new SiegeCheckupTask(siege).scheduleAnotherCheck();
    }

    // Either winnerName or loserName may be null, but not both.
    synchronized public void endSiege(SiegeData siege, String winnerName, String loserName, List<ItemStack> drops) {
        if (siege == null || siege.ended) return;
        siege.ended = true;

        if (winnerName == null && loserName != null) {
            winnerName = siege.attacker.getName().equals(loserName)
                    ? siege.defender.getName() : siege.attacker.getName();
        } else if (winnerName != null && loserName == null) {
            loserName = siege.attacker.getName().equals(winnerName)
                    ? siege.defender.getName() : siege.attacker.getName();
        }

        boolean attackerWon = siege.attacker.getName().equals(winnerName);
        PlayerData attackerData = this.getPlayerData(siege.attacker.getUniqueId());
        PlayerData defenderData = this.getPlayerData(siege.defender.getUniqueId());
        attackerData.siegeData = null;
        defenderData.siegeData = null;
        defenderData.lastSiegeEndTimeStamp = System.currentTimeMillis();

        long cooldownEnd = System.currentTimeMillis()
                + 1000L * 60L * GriefPrevention.instance.config_siege_cooldownEndInMinutes;
        siegeCooldownRemaining.put(cooldownKey(siege.attacker.getName(), siege.defender.getName()), cooldownEnd);
        for (Claim claim : siege.claims) {
            claim.siegeData = null;
            siegeCooldownRemaining.put(cooldownKey(siege.attacker.getName(), claim.getOwnerName()), cooldownEnd);
            if (attackerWon) claim.doorsOpen = true;
        }

        TaskHandle checkup = siege.checkupTask;
        if (checkup != null) checkup.cancel();

        if (winnerName != null && loserName != null) {
            final String resultMessage = winnerName + " defeated " + loserName + " in siege warfare!";
            SchedulerUtil.runLaterGlobal(GriefPrevention.instance,
                    () -> GriefPrevention.instance.getServer().broadcastMessage(resultMessage), 1L);
        }

        final Player winner = siege.attacker.getName().equals(winnerName) ? siege.attacker : siege.defender;
        if (attackerWon) {
            if (winner.isOnline()) {
                SchedulerUtil.runLaterEntity(GriefPrevention.instance, winner,
                        () -> GriefPrevention.sendMessage(winner, TextMode.Success, Messages.SiegeWinDoorsOpen), 1L);
            }
            SchedulerUtil.runLaterGlobal(GriefPrevention.instance, new SecureClaimTask(siege),
                    20L * GriefPrevention.instance.config_siege_doorsOpenSeconds);
        }

        if (drops != null) {
            final ArrayList<ItemStack> transferredDrops = new ArrayList<>();
            for (ItemStack stack : drops) {
                if (stack != null && stack.getType() != org.bukkit.Material.AIR && stack.getAmount() > 0) {
                    transferredDrops.add(stack.clone());
                }
            }
            drops.clear();

            if (winner.isOnline() && !transferredDrops.isEmpty()) {
                SchedulerUtil.runLaterEntity(GriefPrevention.instance, winner, () -> {
                    for (ItemStack stack : transferredDrops) {
                        HashMap<Integer, ItemStack> remainder = winner.getInventory().addItem(stack);
                        for (ItemStack leftover : remainder.values()) {
                            winner.getWorld().dropItemNaturally(winner.getLocation(), leftover);
                        }
                    }
                }, 1L);
            }
        }
    }

    synchronized public boolean onCooldown(Player attacker, Player defender, Claim defenderClaim) {
        long now = System.currentTimeMillis();
        String playerKey = cooldownKey(attacker.getName(), defender.getName());
        Long playerCooldown = siegeCooldownRemaining.get(playerKey);
        if (playerCooldown != null) {
            if (now < playerCooldown) return true;
            siegeCooldownRemaining.remove(playerKey);
        }

        PlayerData defenderData = this.getPlayerData(defender.getUniqueId());
        if (defenderData.lastSiegeEndTimeStamp > 0
                && now - defenderData.lastSiegeEndTimeStamp < 1000L * 60L * 15L) return true;

        String claimKey = cooldownKey(attacker.getName(), defenderClaim.getOwnerName());
        Long claimCooldown = siegeCooldownRemaining.get(claimKey);
        if (claimCooldown != null) {
            if (now < claimCooldown) return true;
            siegeCooldownRemaining.remove(claimKey);
        }
        return false;
    }

    synchronized void tryExtendSiege(Player player, Claim claim) {
        PlayerData playerData = this.getPlayerData(player.getUniqueId());
        SiegeData siege = playerData.siegeData;
        if (siege == null || siege.claims.contains(claim) || claim.isAdminClaim()) return;

        Claim current = claim;
        while (!current.hasExplicitPermission(player, ClaimPermission.Access)) {
            if (current.parent == null) return;
            current = current.parent;
        }

        siege.claims.add(claim);
        claim.siegeData = siege;
    }

    private static String cooldownKey(String attacker, String target) {
        return attacker.toLowerCase(java.util.Locale.ROOT) + "_" + target.toLowerCase(java.util.Locale.ROOT);
    }

    // deletes all claims owned by a player
    synchronized public void deleteClaimsForPlayer(UUID playerID, boolean releasePets) {
        // make a list of the player's claims
        ArrayList<Claim> claimsToDelete = new ArrayList<>();
        for (Claim claim : this.claims) {
            if (Objects.equals(playerID, claim.ownerID))
                claimsToDelete.add(claim);
        }

        // delete them one by one
        for (Claim claim : claimsToDelete) {
            this.deleteClaim(claim);
        }
    }

    // tries to resize a claim
    // see CreateClaim() for details on return value
    synchronized public CreateClaimResult resizeClaim(Claim claim, int newx1, int newx2, int newy1, int newy2,
            int newz1, int newz2, Player resizingPlayer) {
        // Allow 3D single-layer subdivisions to expand vertically when resizing.
        // PlayerEventHandler computes newy1/newy2 based on the drag direction,
        // including the
        // special case where a 3D claim is a single layer (nx1xn). Do not coerce Y to a
        // single
        // layer here; accept the requested vertical span.

        // try to create this new claim, ignoring the original when checking for overlap
        // Use the 13-parameter overload: dryRun=true, is3D from original claim
        CreateClaimResult result = this.createClaim(claim.getLesserBoundaryCorner().getWorld(), newx1, newx2, newy1,
                newy2, newz1, newz2, claim.ownerID, claim.parent, claim.id, resizingPlayer, true, claim.is3D());

        // if succeeded
        if (result.succeeded) {
            removeFromChunkClaimMap(claim); // remove the old boundary from the chunk cache
            // copy the boundary from the claim created in the dry run of createClaim() to
            // our existing claim
            claim.lesserBoundaryCorner = result.claim.lesserBoundaryCorner;
            claim.greaterBoundaryCorner = result.claim.greaterBoundaryCorner;
            // Sanitize claim depth for non-3D claims only. For 3D subdivisions, do not
            // adjust
            // parent/child depths as they have explicit Y bounds and should NOT extend to
            // claim bottom.
            if (!claim.is3D()) {
                // Expands parent down to the lowest non-3D subdivision and subdivisions down to
                // parent.
                // Also saves affected claims.
                setNewDepth(claim, claim.getLesserBoundaryCorner().getBlockY());
            } else {
                // 3D claims must be saved explicitly since setNewDepth is not called
                this.saveClaim(claim);
            }
            result.claim = claim;
            addToChunkClaimMap(claim); // add the new boundary to the chunk cache
            this.indexClaimSnapshot(claim);
        }

        return result;
    }

    synchronized public CreateClaimResult updateShapedClaim(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim claim,
            @NotNull OrthogonalPolygon polygon) {
        CreateClaimResult result = new CreateClaimResult();

        if (claim.is3D()) {
            result.succeeded = false;
            result.denialMessage = () -> "Shaped editing only works on 2D claims.";
            return result;
        }

        final boolean isSubdivision = claim.parent != null;

        World world = Objects.requireNonNull(claim.getLesserBoundaryCorner().getWorld());
        Supplier<String> minimumFailure = validateShapedResizeMinimums(player, claim, world, polygon);
        if (minimumFailure != null)
        {
            result.succeeded = false;
            result.denialMessage = minimumFailure;
            return result;
        }

        int newx1 = polygon.minX();
        int newx2 = polygon.maxX();
        int newz1 = polygon.minZ();
        int newz2 = polygon.maxZ();
        int newy1 = claim.getLesserBoundaryCorner().getBlockY();
        int newy2 = claim.getGreaterBoundaryCorner().getBlockY();

        // Subdivisions carve up land the owner already paid for, so they never move claim blocks.
        if (!isSubdivision) {
            int newArea = polygonCellArea(world, polygon);
            int blocksRemainingAfter;
            try {
                blocksRemainingAfter = playerData.getRemainingClaimBlocks() + (claim.getArea() - newArea);
            } catch (ArithmeticException e) {
                blocksRemainingAfter = Integer.MIN_VALUE + 1;
            }

            if (!claim.isAdminClaim() && player.getUniqueId().equals(claim.getOwnerID())) {
                if (blocksRemainingAfter < 0) {
                    final int blocksNeeded = Math.abs(blocksRemainingAfter);
                    result.succeeded = false;
                    result.denialMessage = () -> this.getMessage(
                            Messages.ResizeNeedMoreBlocks,
                            String.valueOf(blocksNeeded));
                    return result;
                }
            }
        }

        Claim candidate = new Claim(claim);
        candidate.lesserBoundaryCorner = new Location(world, newx1, newy1, newz1);
        candidate.greaterBoundaryCorner = new Location(world, newx2, newy2, newz2);
        candidate.setShapedCorners(polygon.corners());

        try {
            if (!world.getWorldBorder().isInside(candidate.getLesserBoundaryCorner())
                    || !world.getWorldBorder().isInside(candidate.getGreaterBoundaryCorner())) {
                result.succeeded = false;
                return result;
            }
        } catch (NoSuchMethodError e) {
            // 1.8.8: WorldBorder.isInside() doesn't exist; assume inside border
        }

        for (Claim child : claim.children) {
            if (child.inDataStore && !containsChild(candidate, child)) {
                result.succeeded = false;
                result.claim = child;
                result.denialMessage = () -> this.getMessage(Messages.ResizeFailOverlapSubdivision);
                return result;
            }
        }

        if (isSubdivision) {
            Claim parent = Objects.requireNonNull(claim.parent);
            if (!containsChild(parent, candidate)) {
                result.succeeded = false;
                result.claim = parent;
                result.denialMessage = () -> this.getMessage(Messages.ShapedSubdivisionOutsideParent);
                return result;
            }

            // A subdivision only ever competes with its siblings; it is meant to sit inside its parent.
            for (Claim sibling : parent.children) {
                if (sibling == null || !sibling.inDataStore
                        || Objects.equals(sibling.getID(), claim.getID())) {
                    continue;
                }

                if (candidate.overlaps(sibling)) {
                    result.succeeded = false;
                    result.claim = sibling;
                    result.denialMessage = () -> this.getMessage(Messages.CreateSubdivisionOverlap);
                    return result;
                }
            }
        } else {
            for (Claim otherClaim : this.claims) {
                if (!otherClaim.inDataStore || Objects.equals(otherClaim.getID(), claim.getID())) {
                    continue;
                }

                if (candidate.overlaps(otherClaim)) {
                    // Check if overlapping claim is owned by the same player - if so, return it for merging
                    if (!claim.isAdminClaim() && player.getUniqueId().equals(claim.getOwnerID())
                            && player.getUniqueId().equals(otherClaim.getOwnerID())) {
                        result.succeeded = false;
                        result.claim = otherClaim;
                        return result;
                    }
                    result.succeeded = false;
                    result.claim = otherClaim;
                    return result;
                }
            }
        }

        removeFromChunkClaimMap(claim);
        claim.lesserBoundaryCorner = candidate.lesserBoundaryCorner;
        claim.greaterBoundaryCorner = candidate.greaterBoundaryCorner;
        claim.setShapedCorners(polygon.corners());
        this.saveClaim(claim);
        addToChunkClaimMap(claim);
        this.indexClaimSnapshot(claim);

        result.succeeded = true;
        result.claim = claim;
        return result;
    }

    void resizeClaimWithChecks(Player player, PlayerData playerData, int newx1, int newx2, int newy1, int newy2,
            int newz1, int newz2) {
        // for top level claims, apply size rules and claim blocks requirement
        if (playerData.claimResizing.parent == null) {
            // measure new claim, apply size rules
            int newWidth;
            int newHeight;
            try {
                newWidth = Math.abs(Math.subtractExact(newx1, newx2)) + 1;
                newHeight = Math.abs(Math.subtractExact(newz1, newz2)) + 1;
            } catch (ArithmeticException e) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeNeedMoreBlocks,
                        String.valueOf(Integer.MAX_VALUE));
                return;
            }

            boolean smaller = newWidth < playerData.claimResizing.getWidth()
                    || newHeight < playerData.claimResizing.getHeight();

            if (!player.hasPermission("griefprevention.adminclaims") && !playerData.claimResizing.isAdminClaim()
                    && smaller) {
                if (newWidth < GriefPrevention.instance.config_claims_minWidth
                        || newHeight < GriefPrevention.instance.config_claims_minWidth) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeClaimTooNarrow,
                            String.valueOf(GriefPrevention.instance.config_claims_minWidth));
                    return;
                }

                int newArea;
                try {
                    newArea = Math.multiplyExact(newWidth, newHeight);
                } catch (ArithmeticException e) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeNeedMoreBlocks,
                            String.valueOf(Integer.MAX_VALUE));
                    return;
                }
                if (newArea < GriefPrevention.instance.config_claims_minArea) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeClaimInsufficientArea,
                            String.valueOf(GriefPrevention.instance.config_claims_minArea));
                    return;
                }
            }

            // make sure player has enough blocks to make up the difference
            if (!playerData.claimResizing.isAdminClaim()
                    && player.getUniqueId().equals(playerData.claimResizing.ownerID)) {
                int newArea;
                int blocksRemainingAfter;
                try {
                    newArea = Math.multiplyExact(newWidth, newHeight);
                    blocksRemainingAfter = playerData.getRemainingClaimBlocks()
                            + (playerData.claimResizing.getArea() - newArea);
                } catch (ArithmeticException e) {
                    blocksRemainingAfter = Integer.MIN_VALUE + 1;
                }

                if (blocksRemainingAfter < 0) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeNeedMoreBlocks,
                            String.valueOf(Math.abs(blocksRemainingAfter)));
                    this.tryAdvertiseAdminAlternatives(player);
                    return;
                }
            }
        }

        Claim oldClaim = playerData.claimResizing;
        Claim newClaim = new Claim(oldClaim);
        World world = newClaim.getLesserBoundaryCorner().getWorld();
        newClaim.lesserBoundaryCorner = new Location(world, newx1, newy1, newz1);
        newClaim.greaterBoundaryCorner = new Location(world, newx2, newy2, newz2);
        // Normalize Y coordinates: swap if needed so lesser <= greater
        int y1 = newClaim.lesserBoundaryCorner.getBlockY();
        int y2 = newClaim.greaterBoundaryCorner.getBlockY();
        if (y1 > y2)
        {
            newClaim.greaterBoundaryCorner.setY(y1);
            newClaim.lesserBoundaryCorner.setY(y2);
        }
        // For 2D claims, set lesser Y to world minimum (ground extension logic)
        if (!newClaim.is3D() && world != null) {
            newClaim.lesserBoundaryCorner.setY(GriefPrevention.getWorldMinY(world));
        }
        // Ensure resized subdivisions stay inside parent bounds and avoid sibling
        // overlap.
        if (newClaim.parent != null) {
            Claim parentClaim = newClaim.parent;
            BoundingBox newBox = new BoundingBox(newClaim);
            BoundingBox parentBox = new BoundingBox(parentClaim);

            int newMinX = newBox.getMinX();
            int newMaxX = newBox.getMaxX();
            int newMinZ = newBox.getMinZ();
            int newMaxZ = newBox.getMaxZ();
            int newMinY = newBox.getMinY();
            int newMaxY = newBox.getMaxY();

            int parentMinX = parentBox.getMinX();
            int parentMaxX = parentBox.getMaxX();
            int parentMinZ = parentBox.getMinZ();
            int parentMaxZ = parentBox.getMaxZ();
            int parentMinY = parentBox.getMinY();
            int parentMaxY = parentBox.getMaxY();

            boolean parentIsSubdivision = parentClaim.parent != null;
            int inset = parentIsSubdivision ? 1 : 0;

            boolean violatesParentBounds;
            if (parentIsSubdivision) {
                // Nested subdivisions require 1-block inset on ALL sides (X/Z and Y for 3D)
                boolean violatesXZ = newMinX < parentMinX + inset
                        || newMaxX > parentMaxX - inset
                        || newMinZ < parentMinZ + inset
                        || newMaxZ > parentMaxZ - inset;
                boolean violatesY = parentClaim.is3D() && (newMinY < parentMinY + inset
                        || newMaxY > parentMaxY - inset);
                violatesParentBounds = violatesXZ || violatesY;
            } else {
                // Direct children must remain inside the full parent footprint.
                // For shaped parents, this performs exact shape containment, not just
                // cuboid bounds.
                violatesParentBounds = !containsChild(parentClaim, newClaim);
            }

            if (violatesParentBounds) {
                if (parentIsSubdivision) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.InnerSubdivisionTooClose);
                } else {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailSubdivisionExceedsParent);
                }
                playerData.claimResizing = null;
                playerData.lastShovelLocation = null;
                return;
            }

            if (parentClaim.children != null) {
                for (Claim sibling : parentClaim.children) {
                    if (sibling == oldClaim || !sibling.inDataStore) {
                        continue;
                    }

                    BoundingBox siblingBox = new BoundingBox(sibling);

                    boolean xOverlap = newMinX <= siblingBox.getMaxX() && newMaxX >= siblingBox.getMinX();
                    boolean zOverlap = newMinZ <= siblingBox.getMaxZ() && newMaxZ >= siblingBox.getMinZ();
                    if (!xOverlap || !zOverlap) {
                        continue;
                    }

                    boolean verticalSeparated = newMaxY < siblingBox.getMinY() || newMinY > siblingBox.getMaxY();
                    if (parentClaim.is3D()) {
                        boolean allowVerticalStacking = GriefPrevention.instance.config_claims_allowNestedSubClaims
                                && newClaim.is3D()
                                && sibling.is3D();
                        if (allowVerticalStacking && verticalSeparated) {
                            continue;
                        }
                    } else if (verticalSeparated) {
                        continue;
                    }

                    if (!newClaim.is3D() && !sibling.is3D()) {
                        int overlapWidth = Math.min(newBox.getMaxX(), siblingBox.getMaxX())
                                - Math.max(newBox.getMinX(), siblingBox.getMinX()) + 1;
                        int overlapDepth = Math.min(newBox.getMaxZ(), siblingBox.getMaxZ())
                                - Math.max(newBox.getMinZ(), siblingBox.getMinZ()) + 1;

                        if (overlapWidth <= 0 || overlapDepth <= 0) {
                            continue;
                        }
                    }

                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapSubdivision);
                    playerData.claimResizing = null;
                    playerData.lastShovelLocation = null;
                    return;
                }
            }
        }

        // Check if the new boundaries would intersect with any existing subdivisions
        if (playerData.claimResizing.children != null && !playerData.claimResizing.children.isEmpty()) {
            int newMinX = Math.min(newx1, newx2);
            int newMaxX = Math.max(newx1, newx2);
            int newMinY = Math.min(newy1, newy2);
            int newMaxY = Math.max(newy1, newy2);
            int newMinZ = Math.min(newz1, newz2);
            int newMaxZ = Math.max(newz1, newz2);

            // Check if this is a subdivision being resized (has a parent)
            boolean isSubdivision = playerData.claimResizing.parent != null;
            int inset = isSubdivision ? 1 : 0;

            for (Claim child : playerData.claimResizing.children) {
                Location childMin = child.getLesserBoundaryCorner();
                Location childMax = child.getGreaterBoundaryCorner();

                int childMinX = childMin.getBlockX();
                int childMaxX = childMax.getBlockX();
                int childMinZ = childMin.getBlockZ();
                int childMaxZ = childMax.getBlockZ();

                if (isSubdivision) {
                    // Nested children require 1-block inset from parent bounds on all sides
                    boolean violatesInsetX = childMinX < newMinX + inset || childMaxX > newMaxX - inset;
                    boolean violatesInsetZ = childMinZ < newMinZ + inset || childMaxZ > newMaxZ - inset;
                    boolean violatesInsetY = false;
                    if (playerData.claimResizing.is3D() && child.is3D()) {
                        int childMinY = childMin.getBlockY();
                        int childMaxY = childMax.getBlockY();
                        violatesInsetY = childMinY < newMinY + inset || childMaxY > newMaxY - inset;
                    }

                    if (violatesInsetX || violatesInsetZ || violatesInsetY) {
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.InnerSubdivisionTooClose);
                        playerData.claimResizing = null;
                        playerData.lastShovelLocation = null;
                        return;
                    }
                } else {
                    // Top-level claim children: just need containment, no inset required
                    boolean containedX = newMinX <= childMinX && newMaxX >= childMaxX;
                    boolean containedZ = newMinZ <= childMinZ && newMaxZ >= childMaxZ;

                    if (!(containedX && containedZ)) {
                        GriefPrevention.sendMessage(player, TextMode.Err,
                                Messages.ResizeFailSubdivision);
                        return;
                    }
                }
            }
        }

        // call event here to check if it has been cancelled
        ClaimResizeEvent event = new ClaimResizeEvent(oldClaim, newClaim, player);
        Bukkit.getPluginManager().callEvent(event);

        // return here if event is cancelled
        if (event.isCancelled())
            return;

        // ask the datastore to try and resize the claim, this checks for conflicts with
        // other claims
        CreateClaimResult result = GriefPrevention.instance.dataStore.resizeClaim(
                playerData.claimResizing,
                newClaim.getLesserBoundaryCorner().getBlockX(),
                newClaim.getGreaterBoundaryCorner().getBlockX(),
                newClaim.getLesserBoundaryCorner().getBlockY(),
                newClaim.getGreaterBoundaryCorner().getBlockY(),
                newClaim.getLesserBoundaryCorner().getBlockZ(),
                newClaim.getGreaterBoundaryCorner().getBlockZ(),
                player);

        if (result.succeeded && result.claim != null) {
            // decide how many claim blocks are available for more resizing
            int claimBlocksRemaining = 0;
            if (!playerData.claimResizing.isAdminClaim()) {
                UUID ownerID = playerData.claimResizing.ownerID;
                if (playerData.claimResizing.parent != null) {
                    ownerID = playerData.claimResizing.parent.ownerID;
                }
                if (ownerID == player.getUniqueId()) {
                    claimBlocksRemaining = playerData.getRemainingClaimBlocks();
                } else {
                    PlayerData ownerData = this.getPlayerData(ownerID);
                    claimBlocksRemaining = ownerData.getRemainingClaimBlocks();
                    OfflinePlayer owner = GriefPrevention.instance.getServer().getOfflinePlayer(ownerID);
                    if (!owner.isOnline()) {
                        this.clearCachedPlayerData(ownerID);
                    }
                }
            }

            // inform about success, visualize, communicate remaining blocks available
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.ClaimResizeSuccess,
                    String.valueOf(claimBlocksRemaining));
            BoundaryVisualization.visualizeClaim(player, result.claim, VisualizationType.CLAIM);

            // if resizing someone else's claim, make a log entry
            if (!player.getUniqueId().equals(playerData.claimResizing.ownerID)
                    && playerData.claimResizing.parent == null) {
                Bukkit.getLogger().info(player.getName() + " resized " + playerData.claimResizing.getOwnerName()
                        + "'s claim at "
                        + GriefPrevention.getfriendlyLocationString(playerData.claimResizing.lesserBoundaryCorner)
                        + ".");
            }

            // if increased to a sufficiently large size and no subdivisions yet, send subdivision instructions
            if (oldClaim.getArea() < 1000 && result.claim.getArea() >= 1000 && result.claim.children.isEmpty()
                    && !player.hasPermission("griefprevention.adminclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, 201L,
                        DataStore.SUBDIVISION_VIDEO_URL);
            }

            // clean up
            playerData.claimResizing = null;
            playerData.lastShovelLocation = null;
        } else {
            if (result.claim != null) {
                // inform player
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlap);

                // show the player the conflicting claim
                BoundaryVisualization.visualizeClaim(player, result.claim, VisualizationType.CONFLICT_ZONE);
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapRegion);
            }
        }
    }

    // educates a player about /adminclaims and /acb, if he can use them
    public void tryAdvertiseAdminAlternatives(@NotNull Player player) {
        if (player.hasPermission("griefprevention.adminclaims")
                && player.hasPermission("griefprevention.adjustclaimblocks")) {
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseACandACB);
        } else if (player.hasPermission("griefprevention.adminclaims")) {
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseAdminClaims);
        } else if (player.hasPermission("griefprevention.adjustclaimblocks")) {
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseACB);
        }
    }

    protected void loadMessages()
    {
        this.messagesByLocale.clear();
        MessageLocalization.loadAllMessages(this.messagesByLocale, GriefPrevention.instance.config_locale);
    }

    // resolves a player's Minecraft client locale code to a supported locale code
    private @NotNull String resolvePlayerLocale(@Nullable Player player)
    {
        if (player == null) return GriefPrevention.instance.config_locale;

        PlayerData playerData = this.getPlayerData(player.getUniqueId());
        String playerLocale = playerData != null ? playerData.locale : null;
        if (playerLocale == null || playerLocale.trim().isEmpty())
            return GriefPrevention.instance.config_locale;

        String normalized = playerLocale.toLowerCase().replace('-', '_');

        // Try exact match (case-insensitive)
        for (String key : this.messagesByLocale.keySet())
        {
            if (key.equalsIgnoreCase(normalized)) return key;
        }

        // Try by language prefix (first 2 chars) — prefer exact base locale (e.g. "en") over variants (e.g. "en_PT")
        if (normalized.length() >= 2)
        {
            String langCode = normalized.substring(0, 2);
            String exactBase = null;
            String variantMatch = null;
            for (String key : this.messagesByLocale.keySet())
            {
                if (key.length() >= 2 && key.substring(0, 2).equalsIgnoreCase(langCode))
                {
                    if (key.equalsIgnoreCase(langCode))
                    {
                        exactBase = key;
                        break;
                    }
                    else if (variantMatch == null)
                    {
                        variantMatch = key;
                    }
                }
            }
            if (exactBase != null) return exactBase;
            if (variantMatch != null) return variantMatch;
        }

        return GriefPrevention.instance.config_locale;
    }

    // gets a message for a specific player, using their locale if per-player is enabled
    synchronized public String getMessage(@Nullable Player player, @NotNull Messages messageID, @NotNull String @NotNull... args) {
        String locale = GriefPrevention.instance.config_locale;
        if (GriefPrevention.instance.config_perPlayerLocale && player != null)
        {
            locale = this.resolvePlayerLocale(player);
        }

        String[] localeMessages = this.messagesByLocale.get(locale);
        if (localeMessages == null)
        {
            localeMessages = this.messagesByLocale.get(GriefPrevention.instance.config_locale);
        }
        if (localeMessages == null)
        {
            localeMessages = this.messagesByLocale.get("en");
        }
        if (localeMessages == null)
        {
            return messageID.defaultValue;
        }

        String message = localeMessages[messageID.ordinal()];

        for (int i = 0; i < args.length; i++)
        {
            String param = args[i];
            message = message.replace("{" + i + "}", param);
        }

        return message;
    }

    // gets a message using the server's default locale (for console/non-player contexts)
    synchronized public String getMessage(@NotNull Messages messageID, @NotNull String @NotNull... args) {
        return getMessage((Player) null, messageID, args);
    }

    // used in updating the data schema from 0 to 1.
    // converts player names in a list to uuids
    protected List<String> convertNameListToUUIDList(List<String> names) {
        // doesn't apply after schema has been updated to version 1
        if (this.getSchemaVersion() >= 1)
            return names;

        // list to build results
        List<String> resultNames = new ArrayList<>();

        for (String name : names) {
            // skip non-player-names (groups and "public"), leave them as-is
            if (name.startsWith("[") || name.equals("public")) {
                resultNames.add(name);
                continue;
            }

            // otherwise try to convert to a UUID
            UUID playerID = null;
            try {
                playerID = UUIDFetcher.getUUIDOf(name);
            } catch (Exception ex) {
            }

            // if successful, replace player name with corresponding UUID
            if (playerID != null) {
                resultNames.add(playerID.toString());
            }
        }

        return resultNames;
    }

    abstract void close();

    private class SavePlayerDataThread extends Thread {
        private final UUID playerID;
        private final PlayerData playerData;

        SavePlayerDataThread(UUID playerID, PlayerData playerData) {
            this.playerID = playerID;
            this.playerData = playerData;
        }

        public void run() {
            // ensure player data is already read from file before trying to save
            playerData.getAccruedClaimBlocks();
            playerData.getClaims();
            asyncSavePlayerData(this.playerID, this.playerData);
        }
    }

    // gets all the claims "near" a location
    Set<Claim> getNearbyClaims(Location location) {
        return getChunkClaims(
                location.getWorld(),
                new BoundingBox(location.clone().subtract(150, 0, 150), location.clone().add(150, 0, 150)));
    }

    // deletes all the land claims in a specified world
    void deleteClaimsInWorld(World world, boolean deleteAdminClaims) {
        for (int i = 0; i < claims.size(); i++) {
            Claim claim = claims.get(i);
            if (claim.getLesserBoundaryCorner().getWorld().equals(world)) {
                if (!deleteAdminClaims && claim.isAdminClaim())
                    continue;
                this.deleteClaim(claim, false, false);
                i--;
            }
        }
    }

    public void setPermission(Claim claim, String identifier, ClaimPermission permissionLevel) {
        // Always set the permission on the specific claim
        claim.setPermission(identifier, permissionLevel);

        // Propagate to children only for non-3D claims
        if (!claim.is3D()) {
            for (Claim child : claim.children) {
                // Administrative subdivisions are trusted by staff only; parent trust stops here.
                if (child.isAdminSubdivision()) continue;

                setPermission(child, identifier, permissionLevel);
            }
        }
    }

}
