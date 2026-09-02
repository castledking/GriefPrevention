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

import com.griefprevention.geometry.OrthogonalPoint2i;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

//manages data stored in the file system
public class DatabaseDataStore extends DataStore
{

    private static final String SQL_UPDATE_NAME =
            "UPDATE griefprevention_playerdata SET name = ? WHERE name = ?";
    @SuppressWarnings("unused")
    private static final String SQL_UPDATE_CLAIM =
            "UPDATE griefprevention_claimdata SET owner = ?, lessercorner = ?, greatercorner = ?, builders = ?, containers = ?, accessors = ?, managers = ?, inheritnothing = ?, inheritnothingfornewsubdivisions = ?, parentid = ?, expiration = ?, explosivesallowed = ?, witherexplosionsallowed = ? WHERE id = ?";
    // A brand new database is stamped at the latest schema, which skips every versioned ALTER in
    // initialize(). This has to list the full current column set or the first claim insert fails
    // against columns that were never added.
    static final String SQL_CREATE_CLAIM_TABLE =
            "CREATE TABLE IF NOT EXISTS griefprevention_claimdata (id INTEGER, owner VARCHAR(50), lessercorner VARCHAR(100), greatercorner VARCHAR(100), builders TEXT, containers TEXT, accessors TEXT, managers TEXT, denied TEXT, inheritnothing BOOLEAN, parentid INTEGER, expiration BIGINT, explosivesallowed BOOLEAN, inheritnothingfornewsubdivisions BOOLEAN, witherexplosionsallowed BOOLEAN, is3d BOOLEAN DEFAULT 0, shapecorners TEXT, modifieddate BIGINT DEFAULT 0, pvpenabled BOOLEAN DEFAULT 1, alertsenabled BOOLEAN DEFAULT 1, adminsubdivision BOOLEAN DEFAULT 0, pvptrusted TEXT, pvetrusted TEXT, pvptoggled BOOLEAN DEFAULT 0)";
    static final String SQL_INSERT_CLAIM =
            "INSERT INTO griefprevention_claimdata (id, owner, lessercorner, greatercorner, builders, containers, accessors, managers, denied, inheritnothing, inheritnothingfornewsubdivisions, parentid, expiration, explosivesallowed, witherexplosionsallowed, is3d, shapecorners, modifieddate, pvpenabled, alertsenabled, adminsubdivision, pvptrusted, pvetrusted, pvptoggled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_DELETE_CLAIM =
            "DELETE FROM griefprevention_claimdata WHERE id = ?";
    private static final String SQL_SELECT_PLAYER_DATA =
            "SELECT * FROM griefprevention_playerdata WHERE name = ?";
    private static final String SQL_DELETE_PLAYER_DATA =
            "DELETE FROM griefprevention_playerdata WHERE name = ?";
    private static final String SQL_INSERT_PLAYER_DATA =
            "INSERT INTO griefprevention_playerdata (name, lastlogin, accruedblocks, bonusblocks) VALUES (?, ?, ?, ?)";
    private static final String SQL_SET_NEXT_CLAIM_ID =
            "INSERT INTO griefprevention_nextclaimid VALUES (?)";
    private static final String SQL_DELETE_GROUP_DATA =
            "DELETE FROM griefprevention_playerdata WHERE name = ?";
    private static final String SQL_INSERT_SCHEMA_VERSION =
            "INSERT INTO griefprevention_schemaversion VALUES (?)";
    private static final String SQL_DELETE_NEXT_CLAIM_ID =
            "DELETE FROM griefprevention_nextclaimid";
    private static final String SQL_DELETE_SCHEMA_VERSION =
            "DELETE FROM griefprevention_schemaversion";
    private static final String SQL_SELECT_SCHEMA_VERSION =
            "SELECT * FROM griefprevention_schemaversion";
    @SuppressWarnings("unused")
    private static final String SQL_LOAD_CLAIM =
            "SELECT * FROM griefprevention_claimdata WHERE id = ?";
    private static final String SQL_UPDATE_SCHEMA_ADD_EXPIRATION =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS expiration BIGINT DEFAULT 0";
    private static final String SQL_UPDATE_SCHEMA_ADD_EXPLOSIVES =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS explosivesallowed BOOLEAN DEFAULT 0";
    private static final String SQL_UPDATE_SCHEMA_ADD_WITHER_EXPLOSIONS =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS witherexplosionsallowed BOOLEAN DEFAULT 0";
    private static final String SQL_UPDATE_SCHEMA_ADD_IS3D =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS is3d BOOLEAN DEFAULT 0";
    private static final String SQL_UPDATE_SCHEMA_ADD_SHAPECORNERS =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS shapecorners TEXT DEFAULT ''";
    private static final String SQL_UPDATE_SCHEMA_ADD_MODIFIEDDATE =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS modifieddate BIGINT DEFAULT 0";
    private static final String SQL_UPDATE_SCHEMA_ADD_PVPENABLED =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS pvpenabled BOOLEAN DEFAULT 1";
    private static final String SQL_UPDATE_SCHEMA_ADD_ALERTSENABLED =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS alertsenabled BOOLEAN DEFAULT 1";
    private static final String SQL_UPDATE_SCHEMA_ADD_ADMINSUBDIVISION =
            "ALTER TABLE griefprevention_claimdata ADD COLUMN IF NOT EXISTS adminsubdivision BOOLEAN DEFAULT 0";

    private Connection databaseConnection = null;

    private final String databaseUrl;
    private final String userName;
    private final String password;

    DatabaseDataStore(String url, String userName, String password) throws Exception
    {
        this.databaseUrl = url;
        this.userName = userName;
        this.password = password;

        this.initialize();
    }

    @Override
    void initialize() throws Exception
    {
        try
        {
            this.refreshDataConnection();
        }
        catch (Exception e2)
        {
            GriefPrevention.AddLogEntry("ERROR: Unable to connect to database.  Check your config file settings.");
            throw e2;
        }

        try (Statement statement = databaseConnection.createStatement())
        {
            //ensure the data tables exist
            statement.execute("CREATE TABLE IF NOT EXISTS griefprevention_nextclaimid (nextid INTEGER)");
            statement.execute(SQL_CREATE_CLAIM_TABLE);
            statement.execute("CREATE TABLE IF NOT EXISTS griefprevention_playerdata (name VARCHAR(50), lastlogin DATETIME, accruedblocks INTEGER, bonusblocks INTEGER)");
            statement.execute("CREATE TABLE IF NOT EXISTS griefprevention_schemaversion (version INTEGER)");

            // By making this run only for MySQL, we technically support SQLite too, as this is the only invalid
            // SQL we use that SQLite does not support. Seeing as its only use is to update VERY old, existing, MySQL
            // databases, this is of no concern.
            if (databaseUrl.startsWith("jdbc:mysql://"))
            {
                statement.execute("ALTER TABLE griefprevention_claimdata MODIFY builders TEXT");
                statement.execute("ALTER TABLE griefprevention_claimdata MODIFY containers TEXT");
                statement.execute("ALTER TABLE griefprevention_claimdata MODIFY accessors TEXT");
                statement.execute("ALTER TABLE griefprevention_claimdata MODIFY managers TEXT");
            }

            // Deny entries record trust a subdivision revoked, so an existing database that
            // predates the column has to gain it before any claim is written. Checked against
            // JDBC metadata rather than ADD COLUMN IF NOT EXISTS, which real MySQL rejects.
            this.addClaimColumnIfMissing(statement, "denied", "TEXT");

            //if the next claim id table is empty, this is a brand new database which will write using the latest schema
            //otherwise, schema version is determined by schemaversion table (or =0 if table is empty, see getSchemaVersion())
            ResultSet results = statement.executeQuery("SELECT * FROM griefprevention_nextclaimid");
            if (!results.next())
            {
                this.setSchemaVersion(latestSchemaVersion);
            }
        }
        catch (Exception e3)
        {
            GriefPrevention.AddLogEntry("ERROR: Unable to create the necessary database table.  Details:");
            GriefPrevention.AddLogEntry(e3.getMessage());
            e3.printStackTrace();
            throw e3;
        }

        //load group data into memory
        Statement statement = databaseConnection.createStatement();
        ResultSet results = statement.executeQuery("SELECT * FROM griefprevention_playerdata");

        while (results.next())
        {
            String name = results.getString("name");

            //ignore non-groups.  all group names start with a dollar sign.
            if (!name.startsWith("$")) continue;

            String groupName = name.substring(1);
            if (groupName == null || groupName.isEmpty()) continue;  //defensive coding, avoid unlikely cases

            int groupBonusBlocks = results.getInt("bonusblocks");

            this.permissionToBonusBlocksMap.put(groupName, groupBonusBlocks);
        }

        //load next claim number into memory
        results = statement.executeQuery("SELECT * FROM griefprevention_nextclaimid");

        //if there's nothing yet, add it
        if (!results.next())
        {
            statement.execute("INSERT INTO griefprevention_nextclaimid VALUES (0)");
            this.nextClaimID = (long) 0;
        }

        //otherwise load it
        else
        {
            this.nextClaimID = results.getLong("nextid");
        }

        if (this.getSchemaVersion() == 0)
        {
            try
            {
                this.refreshDataConnection();

                //pull ALL player data from the database
                statement = this.databaseConnection.createStatement();
                results = statement.executeQuery("SELECT * FROM griefprevention_playerdata");

                //make a list of changes to be made
                HashMap<String, UUID> changes = new HashMap<>();

                ArrayList<String> namesToConvert = new ArrayList<>();
                while (results.next())
                {
                    //get the id
                    String playerName = results.getString("name");

                    //add to list of names to convert to UUID
                    namesToConvert.add(playerName);
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

                //reset results cursor
                results.beforeFirst();

                //for each result
                while (results.next())
                {
                    //get the id
                    String playerName = results.getString("name");

                    //try to convert player name to UUID
                    try
                    {
                        UUID playerID = UUIDFetcher.getUUIDOf(playerName);

                        //if successful, update the playerdata row by replacing the player's name with the player's UUID
                        if (playerID != null)
                        {
                            changes.put(playerName, playerID);
                        }
                    }
                    //otherwise leave it as-is. no harm done - it won't be requested by name, and this update only happens once.
                    catch (Exception ex) { }
                }

                //refresh data connection in case data migration took a long time
                this.refreshDataConnection();

                for (String name : changes.keySet())
                {
                    try (PreparedStatement updateStmnt = this.databaseConnection.prepareStatement(SQL_UPDATE_NAME))
                    {
                        updateStmnt.setString(1, changes.get(name).toString());
                        updateStmnt.setString(2, name);
                        updateStmnt.executeUpdate();
                    }
                    catch (SQLException e)
                    {
                        GriefPrevention.AddLogEntry("Unable to convert player data for " + name + ".  Skipping.");
                        GriefPrevention.AddLogEntry(e.getMessage());
                    }
                }
            }
            catch (SQLException e)
            {
                GriefPrevention.AddLogEntry("Unable to convert player data.  Details:");
                GriefPrevention.AddLogEntry(e.getMessage());
                e.printStackTrace();
            }
        }

        if (this.getSchemaVersion() <= 2)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute("ALTER TABLE griefprevention_claimdata ADD inheritNothing BOOLEAN DEFAULT 0 AFTER managers");
        }

        if (this.getSchemaVersion() <= 3)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_EXPIRATION);
        }

        if (this.getSchemaVersion() <= 4)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_EXPLOSIVES);
        }

        if (this.getSchemaVersion() <= 5)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute("ALTER TABLE griefprevention_claimdata ADD inheritNothingForNewSubdivisions BOOLEAN DEFAULT 0 AFTER inheritNothing");
        }

        if (this.getSchemaVersion() <= 6)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_WITHER_EXPLOSIONS);
        }

        if (this.getSchemaVersion() <= 7)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_IS3D);
        }

        if (this.getSchemaVersion() <= 8)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_SHAPECORNERS);
        }

        if (this.getSchemaVersion() <= 9)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_MODIFIEDDATE);
        }

        if (this.getSchemaVersion() <= 10)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_PVPENABLED);
        }

        if (this.getSchemaVersion() <= 11)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_ALERTSENABLED);
        }

        if (this.getSchemaVersion() <= 12)
        {
            statement = this.databaseConnection.createStatement();
            statement.execute(SQL_UPDATE_SCHEMA_ADD_ADMINSUBDIVISION);
        }

        if (this.getSchemaVersion() <= 13)
        {
            // Combat trust lists. Checked against JDBC metadata rather than ADD COLUMN IF NOT
            // EXISTS, which real MySQL rejects (see the "denied" column above).
            this.addClaimColumnIfMissing(statement, "pvptrusted", "TEXT");
            this.addClaimColumnIfMissing(statement, "pvetrusted", "TEXT");
        }

        if (this.getSchemaVersion() <= 14)
        {
            // Marks claims whose PvP state was explicitly toggled (rather than left at default).
            this.addClaimColumnIfMissing(statement, "pvptoggled", "BOOLEAN DEFAULT 0");
        }

        //load claims data into memory

        results = statement.executeQuery("SELECT * FROM griefprevention_claimdata");

        ArrayList<Claim> claimsToRemove = new ArrayList<>();
        ArrayList<Claim> subdivisionsToLoad = new ArrayList<>();
        List<World> validWorlds = Bukkit.getServer().getWorlds();

        Long claimID = null;
        while (results.next())
        {
            try
            {
                //problematic claims will be removed from secondary storage, and never added to in-memory data store
                boolean removeClaim = false;

                long parentId = results.getLong("parentid");
                claimID = results.getLong("id");
                boolean inheritNothing = results.getBoolean("inheritnothing");
                boolean inheritNothingForNewSubdivisions = results.getBoolean("inheritnothingfornewsubdivisions");

                //expiration date
                long expirationDate = results.getLong("expiration");

                Location lesserBoundaryCorner = null;
                Location greaterBoundaryCorner = null;
                String lesserCornerString = "(location not available)";
                try
                {
                    lesserCornerString = results.getString("lessercorner");
                    lesserBoundaryCorner = this.locationFromString(lesserCornerString, validWorlds);
                    String greaterCornerString = results.getString("greatercorner");
                    greaterBoundaryCorner = this.locationFromString(greaterCornerString, validWorlds);
                }
                catch (Exception e)
                {
                    if (e.getMessage() != null && e.getMessage().contains("World not found"))
                    {
                        GriefPrevention.AddLogEntry("Failed to load a claim (ID:" + claimID + ") because its world isn't loaded (yet?).  Please delete the claim or contact the GriefPrevention developer with information about which plugin(s) you're using to load or create worlds.  " + lesserCornerString);
                        continue;
                    }
                    else
                    {
                        throw e;
                    }
                }

                String ownerName = results.getString("owner");
                UUID ownerID = null;
                if (ownerName.isEmpty() || ownerName.startsWith("--"))
                {
                    ownerID = null;  //administrative land claim or subdivision
                }
                else if (this.getSchemaVersion() < 1)
                {
                    try
                    {
                        ownerID = UUIDFetcher.getUUIDOf(ownerName);
                    }
                    catch (Exception ex)
                    {
                        GriefPrevention.AddLogEntry("This owner name did not convert to a UUID: " + ownerName + ".");
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
                        GriefPrevention.AddLogEntry("This owner entry is not a UUID: " + ownerName + ".");
                        GriefPrevention.AddLogEntry("  Converted land claim to administrative @ " + lesserBoundaryCorner.toString());
                    }
                }

                String buildersString = results.getString("builders");
                List<String> builderNames = Arrays.asList(buildersString.split(";"));
                builderNames = this.convertNameListToUUIDList(builderNames);

                String containersString = results.getString("containers");
                List<String> containerNames = Arrays.asList(containersString.split(";"));
                containerNames = this.convertNameListToUUIDList(containerNames);

                String accessorsString = results.getString("accessors");
                List<String> accessorNames = Arrays.asList(accessorsString.split(";"));
                accessorNames = this.convertNameListToUUIDList(accessorNames);

                String managersString = results.getString("managers");
                List<String> managerNames = Arrays.asList(managersString.split(";"));
                managerNames = this.convertNameListToUUIDList(managerNames);

                // Deny entries carry a trust-level suffix and may name a permission node, so they
                // are stored verbatim rather than run through the legacy name-to-UUID conversion.
                List<String> deniedIdentifiers;
                try {
                    deniedIdentifiers = parseStorageList(results.getString("denied"));
                } catch (SQLException e) {
                    deniedIdentifiers = Collections.emptyList(); // Default if column doesn't exist
                }
                boolean explosivesAllowed = results.getBoolean("explosivesallowed");
                boolean witherExplosionsAllowed = results.getBoolean("witherexplosionsallowed");
                boolean is3d = results.getBoolean("is3d");
                boolean pvpEnabled;
                try {
                    pvpEnabled = results.getBoolean("pvpenabled");
                } catch (SQLException e) {
                    pvpEnabled = true; // Default if column doesn't exist
                }
                boolean alertsEnabled;
                try {
                    alertsEnabled = results.getBoolean("alertsenabled");
                } catch (SQLException e) {
                    alertsEnabled = true; // Default if column doesn't exist
                }
                boolean adminSubdivision;
                try {
                    adminSubdivision = results.getBoolean("adminsubdivision");
                } catch (SQLException e) {
                    adminSubdivision = false; // Default if column doesn't exist
                }
                boolean pvpToggleSet;
                try {
                    pvpToggleSet = results.getBoolean("pvptoggled");
                } catch (SQLException e) {
                    pvpToggleSet = false; // Default if column doesn't exist
                }
                List<String> pvpTrusted;
                try {
                    pvpTrusted = parseStorageList(results.getString("pvptrusted"));
                } catch (SQLException e) {
                    pvpTrusted = Collections.emptyList(); // Default if column doesn't exist
                }
                List<String> pveTrusted;
                try {
                    pveTrusted = parseStorageList(results.getString("pvetrusted"));
                } catch (SQLException e) {
                    pveTrusted = Collections.emptyList(); // Default if column doesn't exist
                }
                String shapecornersStr = results.getString("shapecorners");
                long modifiedDate = results.getLong("modifieddate");

                Claim claim = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, ownerID, builderNames, containerNames, accessorNames, managerNames, inheritNothing, claimID, is3d);
                claim.setExpirationDate(expirationDate);
                claim.areExplosivesAllowed = explosivesAllowed;
                claim.areWitherExplosionsAllowed = witherExplosionsAllowed;
                claim.pvpEnabled = pvpEnabled;
                claim.pvpToggleSet = pvpToggleSet;
                claim.alertsEnabled = alertsEnabled;
                claim.setInheritNothingForNewSubdivisions(inheritNothingForNewSubdivisions);
                claim.setShapedCorners(parseCornersFromDb(shapecornersStr));
                claim.setAdminSubdivision(adminSubdivision);
                claim.restoreDeniedPermissions(deniedIdentifiers);
                for (String identifier : pvpTrusted)
                {
                    claim.addPvpTrust(identifier);
                }
                for (String identifier : pveTrusted)
                {
                    claim.addPveTrust(identifier);
                }
                if (modifiedDate > 0) claim.modifiedDate = new Date(modifiedDate);

                if (removeClaim)
                {
                    claimsToRemove.add(claim);
                }
                else if (parentId == -1)
                {
                    //top level claim
                    this.addClaim(claim, false);
                }
                else
                {
                    //subdivision
                    subdivisionsToLoad.add(claim);
                }
            }
            catch (SQLException e)
            {
                GriefPrevention.AddLogEntry("Unable to load a claim.  Details: " + e.getMessage() + " ... " + results);
                e.printStackTrace();
            }
        }

        //add subdivisions to their parent claims
        for (Claim childClaim : subdivisionsToLoad)
        {
            //find top level claim parent
            Claim topLevelClaim = this.getClaimAt(childClaim.getLesserBoundaryCorner(), true, null);

            if (topLevelClaim == null)
            {
                claimsToRemove.add(childClaim);
                GriefPrevention.AddLogEntry("Removing orphaned claim subdivision: " + childClaim.getLesserBoundaryCorner().toString());
                continue;
            }

            //add this claim to the list of children of the current top level claim
            childClaim.parent = topLevelClaim;
            topLevelClaim.children.add(childClaim);
            childClaim.inDataStore = true;
        }

        for (Claim claim : claimsToRemove)
        {
            this.deleteClaimFromSecondaryStorage(claim);
        }

        if (this.getSchemaVersion() <= 2)
        {
            this.refreshDataConnection();
            statement = this.databaseConnection.createStatement();
            statement.execute("DELETE FROM griefprevention_claimdata WHERE id = '-1'");
        }

        super.initialize();
    }

    @Override
    synchronized void writeClaimToStorage(Claim claim)  //see datastore.cs.  this will ALWAYS be a top level claim
    {
        try
        {
            this.refreshDataConnection();

            //wipe out any existing data about this claim
            this.deleteClaimFromSecondaryStorage(claim);

            //write claim data to the database
            this.writeClaimData(claim);
        }
        catch (SQLException e)
        {
            GriefPrevention.AddLogEntry("Unable to save data for claim at " + this.locationToString(claim.lesserBoundaryCorner) + ".  Details:");
            GriefPrevention.AddLogEntry(e.getMessage());
        }
    }

    //actually writes claim data to the database
    synchronized private void writeClaimData(Claim claim) throws SQLException
    {
        String lesserCornerString = this.locationToString(claim.getLesserBoundaryCorner());
        String greaterCornerString = this.locationToString(claim.getGreaterBoundaryCorner());
        String owner = "";
        if (claim.ownerID != null) owner = claim.ownerID.toString();

        ArrayList<String> builders = new ArrayList<>();
        ArrayList<String> containers = new ArrayList<>();
        ArrayList<String> accessors = new ArrayList<>();
        ArrayList<String> managers = new ArrayList<>();

        claim.getPermissions(builders, containers, accessors, managers);

        String buildersString = this.storageStringBuilder(builders);
        String containersString = this.storageStringBuilder(containers);
        String accessorsString = this.storageStringBuilder(accessors);
        String managersString = this.storageStringBuilder(managers);
        // Revoked inherited trust is stored as a deny entry, not as missing trust, so it has to be
        // written alongside the grant lists or a reload hands that trust back.
        String deniedString = this.storageStringBuilder(
                new ArrayList<>(claim.getTrustSnapshot().deniedIdentifiers())
        );
        boolean inheritNothing = claim.getSubclaimRestrictions();
        boolean inheritNothingForNewSubdivisions = claim.getInheritNothingForNewSubdivisions();
        long parentId = claim.parent == null ? -1 : claim.parent.id;
        long expirationDate = claim.getExpirationDate();
        boolean explosivesAllowed = claim.areExplosivesAllowed;
        boolean witherExplosionsAllowed = claim.areWitherExplosionsAllowed;
        boolean is3d = claim.is3D();
        boolean pvpEnabled = claim.pvpEnabled;
        String shapecorners = serializeCornersForDb(claim);
        long modifiedDate = claim.modifiedDate != null ? claim.modifiedDate.getTime() : System.currentTimeMillis();

        try (PreparedStatement insertStmt = this.databaseConnection.prepareStatement(SQL_INSERT_CLAIM))
        {

            insertStmt.setLong(1, claim.id);
            insertStmt.setString(2, owner);
            insertStmt.setString(3, lesserCornerString);
            insertStmt.setString(4, greaterCornerString);
            insertStmt.setString(5, buildersString);
            insertStmt.setString(6, containersString);
            insertStmt.setString(7, accessorsString);
            insertStmt.setString(8, managersString);
            insertStmt.setString(9, deniedString);
            insertStmt.setBoolean(10, inheritNothing);
            insertStmt.setBoolean(11, inheritNothingForNewSubdivisions);
            insertStmt.setLong(12, parentId);
            insertStmt.setLong(13, expirationDate);
            insertStmt.setBoolean(14, explosivesAllowed);
            insertStmt.setBoolean(15, witherExplosionsAllowed);
            insertStmt.setBoolean(16, is3d);
            insertStmt.setString(17, shapecorners);
            insertStmt.setLong(18, modifiedDate);
            insertStmt.setBoolean(19, pvpEnabled);
            insertStmt.setBoolean(20, claim.alertsEnabled);
            insertStmt.setBoolean(21, claim.isAdminSubdivision());
            insertStmt.setString(22, this.storageStringBuilder(new ArrayList<>(claim.getPvpTrustedIdentifiers())));
            insertStmt.setString(23, this.storageStringBuilder(new ArrayList<>(claim.getPveTrustedIdentifiers())));
            insertStmt.setBoolean(24, claim.pvpToggleSet);
            insertStmt.executeUpdate();
        }
        catch (SQLException e)
        {
            GriefPrevention.AddLogEntry("Unable to save data for claim at " + this.locationToString(claim.lesserBoundaryCorner) + ".  Details:");
            GriefPrevention.AddLogEntry(e.getMessage());
        }
    }

    //deletes a claim from the database
    @Override
    synchronized void deleteClaimFromSecondaryStorage(Claim claim)
    {
        boolean debugEnabled = GriefPrevention.instance.config_logs_debugEnabled;
        
        try (PreparedStatement deleteStmnt = this.databaseConnection.prepareStatement(SQL_DELETE_CLAIM))
        {
            deleteStmnt.setLong(1, claim.id);
            int rowsAffected = deleteStmnt.executeUpdate();
            
            if (debugEnabled) {
                String claimType = claim.parent != null ? "subdivision" : "claim";
                GriefPrevention.AddLogEntry("[DEBUG] Database: Deleted " + claimType + " " + claim.id 
                    + " from database (rows affected: " + rowsAffected + ")", CustomLogEntryTypes.Debug, true);
            }
        }
        catch (SQLException e)
        {
            GriefPrevention.AddLogEntry("Unable to delete data for claim " + claim.id + ".  Details:");
            GriefPrevention.AddLogEntry(e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    PlayerData getPlayerDataFromStorage(UUID playerID)
    {
        PlayerData playerData = new PlayerData();
        playerData.playerID = playerID;

        try (PreparedStatement selectStmnt = this.databaseConnection.prepareStatement(SQL_SELECT_PLAYER_DATA))
        {
            selectStmnt.setString(1, playerID.toString());
            ResultSet results = selectStmnt.executeQuery();

            //if data for this player exists, use it
            if (results.next())
            {
                playerData.setAccruedClaimBlocks(results.getInt("accruedblocks"));
                playerData.setBonusClaimBlocks(results.getInt("bonusblocks"));
            }
        }
        catch (SQLException e)
        {
            // Flag this PlayerData as having failed to load. Callers must NOT
            // treat a failed read as "this player has no saved data" — that
            // would let a transient connection failure (e.g. wait_timeout,
            // EOFException) silently overwrite a player's real claim blocks
            // with defaults on logout. See upstream issues #2589 / #666.
            playerData.loadFailedFromStorage = true;
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            GriefPrevention.AddLogEntry(playerID + " " + errors, CustomLogEntryTypes.Exception);
            GriefPrevention.AddLogEntry(
                    "Failed to load player data for " + playerID + " from the database. Saves for this session will be skipped to protect existing data.",
                    CustomLogEntryTypes.Exception, false);
        }

        return playerData;
    }

    //saves changes to player data.  MUST be called after you're done making changes, otherwise a reload will lose them
    @Override
    public void overrideSavePlayerData(UUID playerID, PlayerData playerData)
    {
        //never save data for the "administrative" account.  an empty string for player name indicates administrative account
        if (playerID == null) return;

        // Refuse to save a PlayerData that failed to load. Otherwise we would
        // be writing default zeros over the player's real accrued/bonus blocks.
        if (playerData != null && playerData.loadFailedFromStorage)
        {
            GriefPrevention.AddLogEntry(
                    "Refusing to save PlayerData for " + playerID + " because the most recent storage read failed. The on-disk record is being preserved.",
                    CustomLogEntryTypes.Exception, false);
            return;
        }

        this.savePlayerData(playerID.toString(), playerData);
    }

    private void savePlayerData(String playerID, PlayerData playerData)
    {
        try (PreparedStatement deleteStmnt = this.databaseConnection.prepareStatement(SQL_DELETE_PLAYER_DATA);
             PreparedStatement insertStmnt = this.databaseConnection.prepareStatement(SQL_INSERT_PLAYER_DATA))
        {
            OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(playerID));

            SimpleDateFormat sqlFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String dateString = sqlFormat.format(new Date(player.getLastPlayed()));
            deleteStmnt.setString(1, playerID);
            deleteStmnt.executeUpdate();

            insertStmnt.setString(1, playerID);
            insertStmnt.setString(2, dateString);
            insertStmnt.setInt(3, playerData.getAccruedClaimBlocks());
            insertStmnt.setInt(4, playerData.getBonusClaimBlocks());
            insertStmnt.executeUpdate();
        }
        catch (SQLException e)
        {
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            GriefPrevention.AddLogEntry(playerID + " " + errors, CustomLogEntryTypes.Exception);
        }
    }

    @Override
    synchronized void incrementNextClaimID()
    {
        this.setNextClaimID(this.nextClaimID + 1);
    }

    //sets the next claim ID.  used by incrementNextClaimID() above, and also while migrating data from a flat file data store
    synchronized void setNextClaimID(long nextID)
    {
        this.nextClaimID = nextID;

        try (PreparedStatement deleteStmnt = this.databaseConnection.prepareStatement(SQL_DELETE_NEXT_CLAIM_ID);
             PreparedStatement insertStmnt = this.databaseConnection.prepareStatement(SQL_SET_NEXT_CLAIM_ID))
        {
            deleteStmnt.execute();
            insertStmnt.setLong(1, nextID);
            insertStmnt.executeUpdate();
        }
        catch (SQLException e)
        {
            GriefPrevention.AddLogEntry("Unable to set next claim ID to " + nextID + ".  Details:");
            GriefPrevention.AddLogEntry(e.getMessage());
        }
    }

    //updates the database with a group's bonus blocks
    @Override
    synchronized void saveGroupBonusBlocks(String groupName, int currentValue)
    {
        //group bonus blocks are stored in the player data table, with player name = $groupName
        try (PreparedStatement deleteStmnt = this.databaseConnection.prepareStatement(SQL_DELETE_GROUP_DATA);
             PreparedStatement insertStmnt = this.databaseConnection.prepareStatement(SQL_INSERT_PLAYER_DATA))
        {
            SimpleDateFormat sqlFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String dateString = sqlFormat.format(new Date());
            deleteStmnt.setString(1, '$' + groupName);
            deleteStmnt.executeUpdate();

            insertStmnt.setString(1, '$' + groupName);
            insertStmnt.setString(2, dateString);
            insertStmnt.setInt(3, 0);
            insertStmnt.setInt(4, currentValue);
            insertStmnt.executeUpdate();
        }
        catch (SQLException e)
        {
            GriefPrevention.AddLogEntry("Unable to save data for group " + groupName + ".  Details:");
            GriefPrevention.AddLogEntry(e.getMessage());
        }
    }

    @Override
    synchronized void close()
    {
        if (this.databaseConnection != null)
        {
            try
            {
                if (!this.databaseConnection.isClosed())
                {
                    this.databaseConnection.close();
                }
            }
            catch (SQLException e) {}
        }

        this.databaseConnection = null;
    }

    private synchronized void refreshDataConnection() throws SQLException
    {
        if (this.databaseConnection == null || !this.databaseConnection.isValid(3))
        {
            if (this.databaseConnection != null && !this.databaseConnection.isClosed())
            {
                this.databaseConnection.close();
            }

            //set username/pass properties
            Properties connectionProps = new Properties();
            connectionProps.put("user", this.userName);
            connectionProps.put("password", this.password);
            connectionProps.put("autoReconnect", "true");
            connectionProps.put("maxReconnects", String.valueOf(Integer.MAX_VALUE));

            //establish connection
            this.databaseConnection = DriverManager.getConnection(this.databaseUrl, connectionProps);
        }
    }

    @Override
    protected int getSchemaVersionFromStorage()
    {
        try (PreparedStatement selectStmnt = this.databaseConnection.prepareStatement(SQL_SELECT_SCHEMA_VERSION))
        {
            ResultSet results = selectStmnt.executeQuery();

            //if there's nothing yet, assume 0 and add it
            if (!results.next())
            {
                this.setSchemaVersion(0);
                return 0;
            }
            //otherwise return the value that's in the table
            else
            {
                return results.getInt("version");
            }
        }
        catch (SQLException e)
        {
            GriefPrevention.AddLogEntry("Unable to retrieve schema version from database.  Details:");
            GriefPrevention.AddLogEntry(e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    @Override
    protected void updateSchemaVersionInStorage(int versionToSet)
    {
        try (PreparedStatement deleteStmnt = this.databaseConnection.prepareStatement(SQL_DELETE_SCHEMA_VERSION);
             PreparedStatement insertStmnt = this.databaseConnection.prepareStatement(SQL_INSERT_SCHEMA_VERSION))
        {
            deleteStmnt.execute();

            insertStmnt.setInt(1, versionToSet);
            insertStmnt.executeUpdate();
        }
        catch (SQLException e)
        {
            GriefPrevention.AddLogEntry("Unable to set next schema version to " + versionToSet + ".  Details:");
            GriefPrevention.AddLogEntry(e.getMessage());
        }
    }

    private String serializeCornersForDb(Claim claim)
    {
        List<OrthogonalPoint2i> corners = claim.getShapedCorners();
        if (corners == null || corners.isEmpty())
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (OrthogonalPoint2i corner : corners)
        {
            if (sb.length() > 0) sb.append(";");
            sb.append(corner.x()).append(",").append(corner.z());
        }
        return sb.toString();
    }

    private List<OrthogonalPoint2i> parseCornersFromDb(String serialized)
    {
        if (serialized == null || serialized.isEmpty())
        {
            return Collections.emptyList();
        }
        List<OrthogonalPoint2i> corners = new ArrayList<>();
        for (String entry : serialized.split(";"))
        {
            String[] parts = entry.split(",");
            if (parts.length != 2) continue;
            corners.add(new OrthogonalPoint2i(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())));
        }
        return corners;
    }

    /**
     * Concats an array to a string divided with the ; sign
     *
     * @param input Arraylist with strings to concat
     * @return String with all values from input array
     */
    private String storageStringBuilder(ArrayList<String> input)
    {
        String output = "";
        for (String string : input)
        {
            output += string + ";";
        }
        return output;
    }

    /**
     * Adds a claim data column when the connected database does not already have it.
     *
     * <p>Uses JDBC metadata instead of {@code ADD COLUMN IF NOT EXISTS} so the statement stays
     * portable across MySQL, MariaDB, and SQLite.
     *
     * @param statement an open statement on the claim database
     * @param columnName the column to ensure exists
     * @param columnDefinition the SQL type to create the column with
     */
    private void addClaimColumnIfMissing(Statement statement, String columnName, String columnDefinition)
            throws SQLException
    {
        if (this.claimDataColumnExists(columnName)) return;

        statement.execute(
                "ALTER TABLE griefprevention_claimdata ADD COLUMN " + columnName + " " + columnDefinition
        );
    }

    private boolean claimDataColumnExists(String columnName) throws SQLException
    {
        DatabaseMetaData metaData = this.databaseConnection.getMetaData();
        // Identifier casing varies per driver, so check both stored forms before giving up.
        for (String table : new String[] {"griefprevention_claimdata", "GRIEFPREVENTION_CLAIMDATA"})
        {
            for (String column : new String[] {columnName, columnName.toUpperCase(Locale.ROOT)})
            {
                try (ResultSet columns = metaData.getColumns(null, null, table, column))
                {
                    if (columns.next()) return true;
                }
            }
        }
        return false;
    }

    /**
     * Splits a stored {@code ;}-delimited list, dropping the empty trailing entry the writer adds.
     */
    static List<String> parseStorageList(String stored)
    {
        if (stored == null || stored.isEmpty()) return Collections.emptyList();

        List<String> values = new ArrayList<>();
        for (String value : stored.split(";"))
        {
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

}
