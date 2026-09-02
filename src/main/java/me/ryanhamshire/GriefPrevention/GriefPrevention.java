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

import com.google.common.base.Predicate;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.griefprevention.api.ClaimToolHandlerRegistry;
import com.griefprevention.claims.ClaimTrustCommandPermissions;
import com.griefprevention.claims.ClaimTrustIdentifier;
import com.griefprevention.claims.editor.BukkitClaimEditMessages;
import com.griefprevention.claims.editor.ClaimEditIntent;
import com.griefprevention.claims.editor.ClaimEditIntentType;
import com.griefprevention.claims.editor.ClaimEditPreview;
import com.griefprevention.claims.editor.ClaimEditResult;
import com.griefprevention.claims.editor.ClaimEditSource;
import com.griefprevention.claims.editor.ClaimEditTarget;
import com.griefprevention.claims.editor.ClaimEditTargetType;
import com.griefprevention.claims.editor.ClaimEditor;
import com.griefprevention.claims.editor.ClaimEditorSession;
import com.griefprevention.claims.editor.ClaimEditorSkeleton;
import com.griefprevention.claims.editor.SegmentSelection;
import com.griefprevention.commands.CommandAliasConfiguration;
import com.griefprevention.commands.TabCompletions;
import com.griefprevention.economy.VaultEconomyFormatting;
import com.griefprevention.compat.Compat;
import com.griefprevention.compat.WorldHeightCompatProvider;
import com.griefprevention.geometry.OrthogonalDirection;
import com.griefprevention.geometry.OrthogonalEdge2i;
import com.griefprevention.geometry.OrthogonalPoint2i;
import com.griefprevention.geometry.OrthogonalPolygon;
import com.griefprevention.platform.PlatformDetection;
import com.griefprevention.platform.knockback.KnockbackProtectionListener;
import com.griefprevention.protection.InteractionProtectionHandler;
import com.griefprevention.protection.ProtectionHelper;
import com.griefprevention.visualization.BoundaryVisualization;
import com.griefprevention.visualization.VisualizationStyleRegistry;
import com.griefprevention.visualization.VisualizationType;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.ryanhamshire.GriefPrevention.DataStore.NoTransferException;
import me.ryanhamshire.GriefPrevention.compat.CompatUtil;
import me.ryanhamshire.GriefPrevention.compat.LegacyRightClickAirHandler;
import me.ryanhamshire.GriefPrevention.events.SaveTrappedPlayerEvent;
import me.ryanhamshire.GriefPrevention.events.TrustChangedEvent;
import me.ryanhamshire.GriefPrevention.integration.DiscordSRVSoftMuteBridge;
import me.ryanhamshire.GriefPrevention.integration.PlaceholderAPIExpansion;
import me.ryanhamshire.GriefPrevention.util.SchedulerUtil;
import me.ryanhamshire.GriefPrevention.util.TaskHandle;
import org.bukkit.BanList;
import org.bukkit.BanList.Type;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GriefPrevention extends JavaPlugin {

    private static final @NotNull ClaimEditor claimEditor = new ClaimEditorSkeleton();

    // for convenience, a reference to the instance of this plugin
    public static GriefPrevention instance;
    private final ClaimToolHandlerRegistry claimToolHandlerRegistry = new ClaimToolHandlerRegistry();
    private final VisualizationStyleRegistry visualizationStyleRegistry = new VisualizationStyleRegistry();

    // for logging to the console and log file
    private static Logger log;

    private CommandAliasConfiguration commandAliases = CommandAliasConfiguration.empty();

    // this handles data storage, like player and region data
    public DataStore dataStore;
    long startupStartTime;

    // Event handlers with common functionality
    EntityEventHandler entityEventHandler;
    EntityDamageHandler entityDamageHandler;

    // this tracks item stacks expected to drop which will need protection
    ArrayList<PendingItemProtection> pendingItemWatchList = new ArrayList<>();

    // log entry manager for GP's custom log files
    CustomLogger customLogger;

    // Player event handler
    public PlayerEventHandler playerEventHandler;

    public ClaimToolHandlerRegistry getClaimToolHandlerRegistry() {
        return claimToolHandlerRegistry;
    }

    public VisualizationStyleRegistry getVisualizationStyleRegistry() {
        return visualizationStyleRegistry;
    }

    // configuration variables, loaded/saved from a config.yml

    // claim mode for each world
    public ConcurrentHashMap<World, ClaimsMode> config_claims_worldModes;
    private boolean config_creativeWorldsExist; // note on whether there are any creative mode worlds, to save cpu
    // cycles on a common hash lookup

    public boolean config_claims_preventGlobalMonsterEggs; // whether monster eggs can be placed regardless of trust.
    public boolean config_claims_preventTheft; // whether containers and crafting blocks are protectable
    public boolean config_claims_protectCreatures; // whether claimed animals may be injured by players without
    // permission
    public boolean config_claims_protectHorses; // whether horses on a claim should be protected by that claim's rules
    public boolean config_claims_protectDonkeys; // whether donkeys on a claim should be protected by that claim's rules
    public boolean config_claims_protectLlamas; // whether llamas on a claim should be protected by that claim's rules
    public boolean config_claims_preventButtonsSwitches; // whether buttons and switches are protectable
    public boolean config_claims_lockWoodenDoors; // whether wooden doors should be locked by default (require
    // /accesstrust)
    public boolean config_claims_lockTrapDoors; // whether trap doors should be locked by default (require /accesstrust)
    public boolean config_claims_lockFenceGates; // whether fence gates should be locked by default (require
    // /accesstrust)
    public boolean config_claims_preventNonPlayerCreatedPortals; // whether portals where we cannot determine the
    // creating player should be prevented from creation in
    // claims
    public boolean config_claims_enderPearlsRequireAccessTrust; // whether teleporting into a claim with a pearl
    // requires access trust
    public boolean config_claims_refundDeniedEnderPearls; // whether to refund ender pearls when teleport is denied
    public boolean config_claims_raidTriggersRequireBuildTrust; // whether raids are triggered by a player that doesn't
    // have build permission in that claim
    public int config_claims_maxClaimsPerPlayer; // maximum number of claims per player
    public boolean config_claims_respectWorldGuard; // whether claim creation requires WG build permission
    public boolean config_claims_villagerTradingRequiresTrust; // whether trading with a claimed villager requires
    // permission

    public int config_claims_initialBlocks; // the number of claim blocks a new player starts with
    public double config_claims_abandonReturnRatio; // the portion of claim blocks returned to a player when a claim is abandoned
    public int config_claims_blocksAccruedPerHour_default; // how many additional blocks players get each hour of play (can be zero) without any special permissions
    public int config_claims_maxAccruedBlocks_default; // the limit on accrued blocks (over time) for players without any special permissions. doesn't limit purchased or admin-gifted blocks
    public int config_claims_accruedIdleThreshold; // distance a player must move between accrual checks to be active
    public int config_claims_accruedIdlePercent; // percentage of normal accrual granted while idle
    public HashMap<String, Integer> config_claims_maxDepth; // limit on how deep claims can go
    public int config_claims_minY; // minimum Y coordinate claims can reach
    public int config_claims_expirationDays; // how many days of inactivity before a player loses his claims
    public int config_claims_expirationExemptionTotalBlocks; // total claim blocks amount which will exempt a player from claim expiration
    public int config_claims_expirationExemptionBonusBlocks; // bonus claim blocks amount which will exempt a player from claim expiration

    public int config_claims_automaticClaimsForNewPlayersRadius; // how big automatic new player claims (when they place
    // a chest) should be. -1 to disable
    public int config_claims_automaticClaimsForNewPlayersRadiusMin; // how big automatic new player claims must be. 0 to
    // disable
    public int config_claims_claimsExtendIntoGroundDistance; // how far below the shoveled block a new claim will reach
    public int config_claims_minWidth; // minimum width for non-admin claims
    public int config_claims_minArea; // minimum area for non-admin claims
    public int config_claims_shapedMinWidth; // minimum width for shaped-claim segments/corridors
    public int config_claims_shapedMinArea; // minimum area for shaped claims

    public int config_claims_chestClaimExpirationDays; // number of days of inactivity before an automatic chest claim
    // will be deleted
    public int config_claims_unusedClaimExpirationDays; // inactivity days before low-investment creative claims expire
    public boolean config_claims_survivalAutoNatureRestoration; // whether survival claims will be automatically
    // restored to nature when auto-deleted
    public boolean config_claims_allowTrappedInAdminClaims; // whether it should be allowed to use /trapped in
    // adminclaims.
    public boolean config_claims_allowNestedSubClaims; // whether nested subdivisions may be created inside other
    // subdivisions
    public boolean config_claims_allowShapedClaims; // whether shaped claim creation and editing tools are enabled
    public boolean config_claims_allowPvPTrust; // whether /pvptrust and PvP combat trust in claims are enabled
    public boolean config_claims_allowPvETrust; // whether /pvetrust and PvE (creature) combat trust in claims are enabled
    public boolean config_claims_allowShapedSubClaims; // whether shaped 2D subdivisions may be created inside claims
    public boolean config_claims_allow3DSubdivisions; // whether 3D height-limited subdivisions are enabled
    public boolean config_claims_allow3DAdminClaims; // whether 3D height-limited admin claims are enabled
    public boolean config_claims_useClaimSelectSessions; // whether selected resize corners become command targets
    public boolean config_claims_useClaimSelectedMessages; // whether selected-claim command help replaces ResizeStart
    public boolean config_claims_legacySubdivisionFormat; // whether to use original GP subdivision format (separate files)
    // REQUIRED for GPExpansion compatibility. Default: false
    public int config_claims_minimumDistance; // minimum distance between top-level claims. 0 = disabled.
    public boolean config_claims_preventLavaPlaceNearClaims; // whether to prevent lava placement near claims when minimum distance is set

    public Material config_claims_investigationTool; // which material will be used to investigate claims with a right
    // click
    public Material config_claims_modificationTool; // which material will be used to create/resize claims with a right
    // click

    public ArrayList<String> config_claims_commandsRequiringAccessTrust; // the list of slash commands requiring access
    // trust when in a claim
    public boolean config_claims_supplyPlayerManual; // whether to give new players a book with land claim help in it
    public int config_claims_manualDeliveryDelaySeconds; // how long to wait before giving a book to a new player

    public boolean config_claims_firespreads; // whether fire will spread in claims
    public boolean config_claims_firedamages; // whether fire will damage in claims

    public boolean config_claims_lecternReadingRequiresAccessTrust; // reading lecterns requires access trust
    public boolean config_claims_hoppersRequireBuildTrust; // hoppers require build trust
    public int config_claims_unprotectedChestWarningCooldownSeconds; // cooldown between unprotected chest warnings
    public int config_claims_unprotectedChestWarningDistance; // minimum distance before repeating chest warning
    public List<String> config_claims_actionBarMessages; // message IDs to render in action bar

    // Economy settings for buying/selling claim blocks
    public boolean config_economy_claimBlocksEnabled; // whether players can buy/sell claim blocks
    public int config_economy_claimBlocksMaxBonus; // maximum purchasable bonus blocks; zero is unlimited
    public double config_economy_claimBlocksPurchaseCost; // cost per claim block when buying
    public double config_economy_claimBlocksSellValue; // value per claim block when selling

    // Legacy siege settings
    public ArrayList<World> config_siege_enabledWorlds;
    public Set<Material> config_siege_blocks;
    public int config_siege_doorsOpenSeconds;
    public int config_siege_cooldownEndInMinutes;

    // PvP toggle cost configuration
    public boolean config_pvp_toggleCostClaimEnabled; // whether PvP toggle is enabled for main claims
    public double config_pvp_toggleCostClaimPrice; // cost to toggle PvP in main claims
    public boolean config_pvp_toggleCostSubdivisionEnabled; // whether PvP toggle is enabled for subdivisions
    public double config_pvp_toggleCostSubdivisionPrice; // cost to toggle PvP in subdivisions
    public String config_pvp_subdivisionPvpState; // PvP state for new subdivisions: INHERIT or DEFAULT

    public boolean config_spam_enabled; // whether or not to monitor for spam
    public int config_spam_loginCooldownSeconds; // how long players must wait between logins. combats login spam.
    public int config_spam_loginLogoutNotificationsPerMinute; // how many login/logout notifications to show per minute
    // (global, not per player)
    public ArrayList<String> config_spam_monitorSlashCommands; // the list of slash commands monitored for spam
    public boolean config_spam_banOffenders; // whether or not to ban spammers automatically
    public String config_spam_banMessage; // message to show an automatically banned player
    public String config_spam_warningMessage; // message to show a player who is close to spam level
    public String config_spam_allowedIpAddresses; // IP addresses which will not be censored
    public int config_spam_deathMessageCooldownSeconds; // cooldown period for death messages (per player) in seconds
    public int config_spam_logoutMessageDelaySeconds; // delay before a logout message will be shown (only if the player
    // stays offline that long)

    HashMap<World, Boolean> config_pvp_specifiedWorlds; // list of worlds where pvp anti-grief rules apply, according to
    // the config file
    public boolean config_pvp_protectFreshSpawns; // whether to make newly spawned players immune until they pick up an
    // item
    public boolean config_pvp_punishLogout; // whether to kill players who log out during PvP combat
    public int config_pvp_combatTimeoutSeconds; // how long combat is considered to continue after the most recent
    // damage
    public boolean config_pvp_allowCombatItemDrop; // whether a player can drop items during combat to hide them
    public boolean config_pvp_allowContainerAccess; // whether players may access claimed containers during PvP combat
    public boolean config_pvp_allowRespawnAnchor; // whether players may use respawn anchors during PvP combat
    public ArrayList<String> config_pvp_blockedCommands; // list of commands which may not be used during pvp combat
    public boolean config_pvp_noCombatInPlayerLandClaims; // whether players may fight in player-owned land claims
    public boolean config_pvp_noCombatInAdminLandClaims; // whether players may fight in admin-owned land claims
    public boolean config_pvp_noCombatInAdminSubdivisions; // whether players may fight in subdivisions of admin-owned
    // land claims
    public boolean config_pvp_allowLavaNearPlayers; // whether players may dump lava near other players in pvp worlds
    public boolean config_pvp_allowLavaNearPlayers_NonPvp; // whather this applies in non-PVP rules worlds
    // <ArchdukeLiamus>
    public boolean config_pvp_allowFireNearPlayers; // whether players may start flint/steel fires near other players in
    // pvp worlds
    public boolean config_pvp_allowFireNearPlayers_NonPvp; // whether this applies in non-PVP rules worlds
    // <ArchdukeLiamus>
    public boolean config_pvp_protectPets; // whether players may damage pets outside of land claims in pvp worlds

    public boolean config_lockDeathDropsInPvpWorlds; // whether players' dropped on death items are protected in pvp
    // worlds
    public boolean config_lockDeathDropsInNonPvpWorlds; // whether players' dropped on death items are protected in
    // non-pvp worlds

    public boolean config_blockClaimExplosions; // whether explosions may destroy claimed blocks
    public boolean config_blockSurfaceCreeperExplosions; // whether creeper explosions near or above the surface destroy
    // blocks
    public boolean config_blockSurfaceOtherExplosions; // whether non-creeper explosions near or above the surface
    // destroy blocks
    public boolean config_blockSkyTrees; // whether players can build trees on platforms in the sky

    public boolean config_fireSpreads; // whether fire spreads outside of claims
    public boolean config_fireDestroys; // whether fire destroys blocks outside of claims

    public boolean config_whisperNotifications; // whether whispered messages will broadcast to administrators in game
    public boolean config_signNotifications; // whether sign content will broadcast to administrators in game
    public ArrayList<String> config_eavesdrop_whisperCommands; // list of whisper commands to eavesdrop on

    public boolean config_visualizationAntiCheatCompat; // whether to engage compatibility mode for anti-cheat plugins
    public boolean config_visualizationGlow; // whether to add glow effect to visualization outlines
    public boolean config_smartBan; // whether to be smart about banning players (check for new players with the
    // same ip)

    public boolean config_endermenMoveBlocks; // whether or not endermen may move blocks around
    public boolean config_claims_ravagersBreakBlocks; // whether or not ravagers may break blocks in claims
    public boolean config_silverfishBreakBlocks; // whether silverfish may break blocks
    public boolean config_creaturesTrampleCrops; // whether or not non-player entities may trample crops
    public boolean config_rabbitsEatCrops; // whether or not rabbits may eat crops
    public boolean config_zombiesBreakDoors; // whether or not hard-mode zombies may break down wooden doors
    public boolean config_mobProjectilesChangeBlocks; // whether mob projectiles can change blocks (skeleton arrows
    // lighting TNT or drowned tridents dropping pointed dripstone)

    public int config_ipLimit; // how many players can share an IP address

    public boolean config_trollFilterEnabled; // whether to auto-mute new players who use banned words right after joining
    public boolean config_silenceBans; // whether to remove quit messages on banned players

    public HashMap<String, Integer> config_seaLevelOverride; // override for sea level, because bukkit doesn't report the right value for all situations
    public HashMap<String, Integer> config_claims_minYOverride; // per-world override for minimum Y coordinate

    public boolean config_limitTreeGrowth; // whether trees should be prevented from growing into a claim from outside
    public PistonMode config_pistonMovement; // Setting for piston check options
    public boolean config_pistonExplosionSound; // whether pistons make an explosion sound when they get removed

    public boolean config_advanced_fixNegativeClaimblockAmounts; // whether to attempt to fix negative claim block
    // amounts (some addons cause/assume players can go
    // into negative amounts)
    public int config_advanced_claim_expiration_check_rate; // How often GP should check for expired claims, amount in
    // seconds
    public int config_advanced_offlineplayer_cache_days; // Cache players who have logged in within the last x number of
    // days

    // custom log settings
    public int config_logs_daysToKeep;
    public boolean config_logs_socialEnabled;
    public boolean config_logs_suspiciousEnabled;
    public boolean config_logs_adminEnabled;
    public boolean config_logs_debugEnabled;
    public boolean config_logs_mutedChatEnabled;
    public String config_locale;
    public boolean config_perPlayerLocale = true;

    // ban management plugin interop settings
    public boolean config_ban_useCommand;
    public String config_ban_commandFormat;

    // how far away to search from a tree trunk for its branch blocks
    public static final int TREE_RADIUS = 5;

    // how long to wait before deciding a player is staying online or staying
    // offline, for notication messages
    public static final int NOTIFICATION_SECONDS = 20;

    // error message rate limiting - tracks last error message time per player (10
    // second cooldown)
    private static final ConcurrentHashMap<UUID, Long> lastErrorMessageTime = new ConcurrentHashMap<>();
    private static final long ERROR_MESSAGE_COOLDOWN_MS = 10000; // 10 seconds

    // Conditionally register a Listener only if the given event class is available at runtime.
    private void registerIfClassPresent(
        org.bukkit.plugin.PluginManager pluginManager,
        String eventClassName,
        java.util.function.Supplier<? extends org.bukkit.event.Listener> factory
    ) {
        try {
            Class.forName(eventClassName, false, getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return;
        }
        org.bukkit.event.Listener listener;
        try {
            listener = factory.get();
        } catch (LinkageError | RuntimeException exception) {
            AddLogEntry("Skipping handler for " + eventClassName + " (construction failed): " + exception.getMessage());
            return;
        }
        try {
            pluginManager.registerEvents(listener, this);
        } catch (LinkageError | RuntimeException exception) {
            AddLogEntry("Skipping handler for " + eventClassName + " (registration failed): " + exception.getMessage());
        }
    }

    // adds a server log entry
    public static synchronized void AddLogEntry(
        String entry,
        CustomLogEntryTypes customLogType,
        boolean excludeFromServerLogs
    ) {
        if (
            customLogType == CustomLogEntryTypes.Debug &&
            entry.startsWith("[GP Debug]") &&
            GriefPrevention.instance != null &&
            !GriefPrevention.instance.config_logs_debugEnabled
        ) {
            return;
        }

        if (customLogType != null && GriefPrevention.instance.customLogger != null) {
            GriefPrevention.instance.customLogger.AddEntry(entry, customLogType);
        }
        if (!excludeFromServerLogs) Bukkit.getLogger().info(entry);
    }

    public static synchronized void AddLogEntry(String entry, CustomLogEntryTypes customLogType) {
        AddLogEntry(entry, customLogType, false);
    }

    public static synchronized void AddLogEntry(String entry) {
        AddLogEntry(entry, CustomLogEntryTypes.Debug);
    }

    // initializes well... everything
    public void onEnable() {
        instance = this;
        log = instance.getLogger();
        this.startupStartTime = System.currentTimeMillis();

        this.loadConfig();
        this.loadCommandAliases();

        this.customLogger = new CustomLogger();

        // when datastore initializes, it loads player and claim data, and posts some stats to the log
        String dbUrl = this.getConfig().getString("database.url", "");
        String dbUser = this.getConfig().getString("database.username", "");
        String dbPass = this.getConfig().getString("database.password", "");

        // Initialize database if configured
        if (!dbUrl.isEmpty()) {
            try {
                DatabaseDataStore databaseStore = new DatabaseDataStore(dbUrl, dbUser, dbPass);

                if (FlatFileDataStore.hasData()) {
                    GriefPrevention.AddLogEntry(
                        "There appears to be some data on the hard drive.  Migrating those data to the database..."
                    );
                    FlatFileDataStore flatFileStore = new FlatFileDataStore();
                    this.dataStore = flatFileStore;
                    flatFileStore.migrateData(databaseStore);
                    flatFileStore.close();
                    GriefPrevention.AddLogEntry("Data migration process complete.");
                }

                this.dataStore = databaseStore;
            } catch (Exception e) {
                GriefPrevention.AddLogEntry(
                    "Because there was a problem with the database, GriefPrevention will not function properly.  Either update the database config settings resolve the issue, or delete those lines from your config.yml so that GriefPrevention can use the file system to store data."
                );
                e.printStackTrace();
                this.getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        // if not using the database because it's not configured or because there was a
        // problem, use the file system to store data
        // this is the preferred method, as it's simpler than the database scenario
        if (this.dataStore == null) {
            File oldclaimdata = new File(getDataFolder(), "ClaimData");
            if (oldclaimdata.exists()) {
                if (!FlatFileDataStore.hasData()) {
                    File claimdata = new File(
                        "plugins" + File.separator + "GriefPreventionData" + File.separator + "ClaimData"
                    );
                    oldclaimdata.renameTo(claimdata);
                    File oldplayerdata = new File(getDataFolder(), "PlayerData");
                    File playerdata = new File(
                        "plugins" + File.separator + "GriefPreventionData" + File.separator + "PlayerData"
                    );
                    oldplayerdata.renameTo(playerdata);
                }
            }
            try {
                this.dataStore = new FlatFileDataStore();
            } catch (Exception e) {
                GriefPrevention.AddLogEntry("Unable to initialize the file system data store.  Details:");
                GriefPrevention.AddLogEntry(e.getMessage());
                e.printStackTrace();
            }
        }

        String dataMode = this.dataStore instanceof FlatFileDataStore ? "(File Mode)" : "(Database Mode)";
        AddLogEntry("[GP Debug] Finished loading data " + dataMode + ".");

        // Auto-grant neighbor trust for existing nearby claims
        this.dataStore.autoGrantNeighborTrust();

        // unless claim block accrual is disabled, start the recurring per 10 minute
        // event to give claim blocks to online players
        // 20L ~ 1 second
        if (this.config_claims_blocksAccruedPerHour_default > 0) {
            DeliverClaimBlocksTask task = new DeliverClaimBlocksTask(null, this);
            SchedulerUtil.runRepeatingGlobal(this, task, 20L * 60 * 10, 20L * 60 * 10);
        }

        // periodic visualization of nearby claims for players in shaped mode (every 5 seconds)
        // Only visualize nearby claims when player is NOT actively editing a shaped claim
        SchedulerUtil.runRepeatingGlobal(
            this,
            () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
                    if (playerData.shovelMode == ShovelMode.Shaped && playerData.getClaimEditorSession() == null) {
                        Set<Claim> claims = this.dataStore.getNearbyClaims(player.getLocation());
                        if (!claims.isEmpty()) {
                            BoundaryVisualization.mergeNearbyClaims(player, claims);
                        }
                    }
                }
            },
            20L * 5,
            20L * 5
        );

        // start recurring cleanup scan for unused claims belonging to inactive players
        FindUnusedClaimsTask task2 = new FindUnusedClaimsTask();
        SchedulerUtil.runRepeatingGlobal(this, task2, 20L * 60, 20L * config_advanced_claim_expiration_check_rate);

        // Restore legacy creative-world entity cleanup. The task partitions work by
        // loaded chunk and reschedules each chunk on its owning region.
        SchedulerUtil.runLaterGlobal(this, new EntityCleanupTask(0), 20L * 60L * 2L);

        // register for events
        PluginManager pluginManager = this.getServer().getPluginManager();

        // player events
        playerEventHandler = new PlayerEventHandler(this.dataStore, this);
        pluginManager.registerEvents(playerEventHandler, this);
        // Load monitored commands on a 1-tick delay to allow plugins to enable and
        // Bukkit to load commands.yml.
        SchedulerUtil.runLaterGlobal(this, playerEventHandler::reload, 1L);

        // block events
        BlockEventHandler blockEventHandler = new BlockEventHandler(this.dataStore);
        pluginManager.registerEvents(blockEventHandler, this);

        // entity events
        entityEventHandler = new EntityEventHandler(this.dataStore, this);
        pluginManager.registerEvents(entityEventHandler, this);

        // combat/damage-specific entity events
        entityDamageHandler = new EntityDamageHandler(this.dataStore, this);
        pluginManager.registerEvents(entityDamageHandler, this);

        // Siege modifies the normal claim permission checks while a battle is active.
        pluginManager.registerEvents(new SiegeEventHandler(), this);

        // knockback protection - handles melee, projectile, and other player-caused knockback in claims
        new KnockbackProtectionListener(this.dataStore, this).register(this);

        // special interaction-related events
        pluginManager.registerEvents(new InteractionProtectionHandler(), this);

        // sulfur cube punch protection (Minecraft 26.2+)
        // PlayerAnimationEvent exists on all Bukkit versions; the handler is a no-op on pre-26.2 servers
        // because no entity will match the "SULFUR_CUBE" type name.
        pluginManager.registerEvents(new SulfurCubeHandler(this, this.dataStore), this);

        // Conditionally register modern event handlers (only if the event classes exist at runtime).
        // Note: use lambdas (not method references) so the target handler class is loaded lazily,
        // inside the registerIfClassPresent try/catch, on servers that lack the modern event API.
        registerIfClassPresent(pluginManager, "org.bukkit.event.block.BlockFertilizeEvent", () ->
            new BlockFertilizeEventHandler(blockEventHandler)
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.block.CauldronLevelChangeEvent", () ->
            new CauldronLevelChangeEventHandler()
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.entity.AreaEffectCloudApplyEvent", () ->
            new AreaEffectCloudApplyEventHandler(entityDamageHandler)
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.entity.EntityMountEvent", () ->
            new EntityMountEventHandler(this.dataStore, this)
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.entity.EntityPickupItemEvent", () ->
            new EntityPickupItemEventHandler(this.dataStore)
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.block.BlockExplodeEvent", () ->
            new BlockExplodeEventHandler(this.dataStore)
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.entity.ItemMergeEvent", () ->
            new ItemMergeEventHandler()
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.player.PlayerSignOpenEvent", () ->
            new PlayerSignOpenEventHandler()
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.player.PlayerTakeLecternBookEvent", () ->
            new PlayerTakeLecternBookEventHandler(this.dataStore)
        );
        registerIfClassPresent(pluginManager, "org.bukkit.event.raid.RaidTriggerEvent", () ->
            new RaidTriggerEventHandler(this.dataStore, this)
        );

        // Pre-1.13: inject packet handler for right-click-on-air with claim tools
        // PaperSpigot 1.8.8 suppresses PlayerInteractEvent(RIGHT_CLICK_AIR) for
        // non-interactive items (shovels, sticks). This handler intercepts the
        // PacketPlayInBlockPlace(-1,-1,-1) and fires a synthetic event.
        if (LegacyRightClickAirHandler.isAvailable() && !isClassPresent("org.bukkit.block.data.BlockData")) {
            try {
                pluginManager.registerEvents(new LegacyRightClickAirHandler(), this);
                AddLogEntry("Registered legacy right-click air handler (pre-1.13).");
            } catch (Exception e) {
                AddLogEntry("Failed to register legacy right-click air handler: " + e.getMessage());
            }
        }

        if (pluginManager.getPlugin("PlaceholderAPI") != null) {
            new PlaceholderAPIExpansion(this).register();
            AddLogEntry("Registered PlaceholderAPI expansion.");
        }

        // DiscordSRV soft-mute bridge - prevents soft-muted players' messages from reaching Discord
        new DiscordSRVSoftMuteBridge(this, this.dataStore).registerIfAvailable();

        // cache offline players
        OfflinePlayer[] offlinePlayers = this.getServer().getOfflinePlayers();
        CacheOfflinePlayerNamesThread namesThread = new CacheOfflinePlayerNamesThread(
            offlinePlayers,
            GriefPrevention.playerNameToIDMap
        );
        namesThread.setPriority(Thread.MIN_PRIORITY);
        namesThread.start();

        // load ignore lists for any already-online players
        @SuppressWarnings("unchecked")
        Collection<Player> players = (Collection<Player>) GriefPrevention.instance.getServer().getOnlinePlayers();
        for (Player player : players) {
            new IgnoreLoaderThread(
                player.getUniqueId(),
                this.dataStore.getPlayerData(player.getUniqueId()).ignoredPlayers
            ).start();
        }

        setUpCommands();

        displayEnabledHeader();
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void loadCommandAliases() {
        File folder = new File(DataStore.dataLayerFolderPath);
        if (!folder.exists() && !folder.mkdirs()) {
            log.warning("Could not create GriefPreventionData folder for alias configuration.");
        }

        File aliasFile = new File(folder, "alias.yml");

        // Always get the latest default YAML
        String defaultYaml = Alias.getDefaultYaml();

        // If the file doesn't exist, create it with defaults
        if (!aliasFile.exists()) {
            try {
                Files.write(aliasFile.toPath(), defaultYaml.getBytes(StandardCharsets.UTF_8));
                log.info("Created default alias.yml with built-in defaults.");
            } catch (IOException e) {
                log.log(Level.SEVERE, "Failed to create default alias.yml. Using built-in defaults only.", e);
                this.commandAliases = CommandAliasConfiguration.empty();
                return;
            }
        } else {
            // File exists, update it with any new defaults while preserving user
            // customizations
            try {
                // Load the current file content
                String currentYaml = new String(Files.readAllBytes(aliasFile.toPath()), StandardCharsets.UTF_8);

                // If the file is empty or contains only whitespace, write the default content
                if (currentYaml.trim().isEmpty()) {
                    Files.write(aliasFile.toPath(), defaultYaml.getBytes(StandardCharsets.UTF_8));
                    log.info("Updated empty alias.yml with default configuration.");
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to check/update alias.yml. Will continue with current content.", e);
            }
        }

        try {
            // Load the configuration, which will merge user customizations with defaults
            this.commandAliases = CommandAliasConfiguration.load(aliasFile.toPath(), new CommandAliasConfiguration.Logger() {
                @Override public void info(String msg) { log.info(msg); }
                @Override public void warning(String msg) { log.warning(msg); }
                @Override public void severe(String msg) { log.severe(msg); }
            });

            // Check if we need to update the file with new default keys
            YamlConfiguration defaultConfig = new YamlConfiguration();
            defaultConfig.loadFromString(defaultYaml);

            YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(aliasFile);

            // Only save if the user config is missing keys from the default
            // We use the default YAML string directly to preserve formatting
            boolean needsUpdate = false;
            for (String key : defaultConfig.getKeys(true)) {
                if (!userConfig.contains(key)) {
                    needsUpdate = true;
                    break;
                }
            }

            if (needsUpdate) {
                // Persist the same semantic merge used at runtime. Writing defaultYaml here
                // would silently erase renamed commands, translations, and addon-owned keys
                // whenever GP3D introduces a new default subcommand.
                this.commandAliases = CommandAliasConfiguration.load(aliasFile.toPath(), new CommandAliasConfiguration.Logger() {
                @Override public void info(String msg) { log.info(msg); }
                @Override public void warning(String msg) { log.warning(msg); }
                @Override public void severe(String msg) { log.severe(msg); }
            });
                log.info("Updated alias.yml with latest default configuration while preserving customizations.");
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to load alias.yml. Falling back to defaults.", e);
            this.commandAliases = CommandAliasConfiguration.empty();
        }
    }

    public @NotNull CommandAliasConfiguration getCommandAliases() {
        return this.commandAliases;
    }

    public void reloadCommandAliases() {
        GriefPrevention.AddLogEntry("Reloading command aliases...");

        // Unregister old dynamic commands before re-registering
        com.griefprevention.commands.UnifiedCommandHandler.unregisterAllDynamicCommands(this);
        loadCommandAliases();
        setUpCommands();

        GriefPrevention.AddLogEntry("Refreshing command map for online players...");
        refreshCommandMapForAllPlayers();

        GriefPrevention.AddLogEntry("Command aliases reloaded successfully.");
    }

    /**
     * Refresh the Bukkit command map for all online players.
     * Modern clients receive an explicit command tree update; legacy clients query
     * tab completions live, so there is no client command tree to resend.
     */
    private void refreshCommandMapForAllPlayers() {
        @SuppressWarnings("unchecked")
        java.util.Collection<Player> onlinePlayers = (java.util.Collection<Player>) getServer().getOnlinePlayers();

        if (onlinePlayers.isEmpty()) {
            return;
        }

        int refreshed = 0;
        for (Player player : onlinePlayers) {
            if (!player.isOnline()) continue;

            try {
                player.updateCommands();
                refreshed++;
            } catch (NoSuchMethodError e) {
                GriefPrevention.AddLogEntry(
                    "Command map refresh skipped; this server does not support Player#updateCommands()."
                );
                return;
            } catch (Exception e) {
                getLogger().warning("Failed to update commands for " + player.getName() + ": " + e.getMessage());
            }
        }

        GriefPrevention.AddLogEntry("Command map refresh complete for " + refreshed + " player(s).");
    }

    private void loadConfig() {
        // load the config if it exists
        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(DataStore.configFilePath));
        // locale for message localization
        this.config_locale = config.getString("GriefPrevention.Locale", "en");

        this.config_perPlayerLocale = config.getBoolean("GriefPrevention.PerPlayerLocale", true);

        FileConfiguration outConfig = new YamlConfiguration();
        try {
            outConfig
                .options()
                .setHeader(
                    Arrays.asList(
                        "Default values are perfect for most servers.  If you want to customize and have a question, look for the answer here first: http://dev.bukkit.org/bukkit-plugins/grief-prevention/pages/setup-and-configuration/",
                        "",
                        "Locale settings:",
                        "  GriefPrevention.Locale: Set to 'es' for Spanish, 'pt_BR' for Brazilian Portuguese, or 'en' for English.",
                        "  To use localized messages, copy messages_XX.yml from the Lang/ folder to GriefPreventionData/ and rename it to messages.yml.",
                        "  If no messages.yml exists, the plugin will use messages_XX.yml from Lang/ based on this locale setting.",
                        "  GriefPrevention.PerPlayerLocale: If true, send messages in the player's client language (if supported).",
                        "    Players with Spanish client locale will receive Spanish messages, Portuguese will receive Portuguese.",
                        "    Players with unsupported locales will receive the server's default locale."
                    )
                );
        } catch (NoSuchMethodError e) {
            // setHeader(List) doesn't exist in older Bukkit versions (1.8.8), ignore
        }

        // get (deprecated node) claims world names from the config file
        List<World> worlds = this.getServer().getWorlds();
        List<String> deprecated_claimsEnabledWorldNames = config.getStringList("GriefPrevention.Claims.Worlds");

        // validate that list
        for (int i = 0; i < deprecated_claimsEnabledWorldNames.size(); i++) {
            String worldName = deprecated_claimsEnabledWorldNames.get(i);
            World world = this.getServer().getWorld(worldName);
            if (world == null) {
                deprecated_claimsEnabledWorldNames.remove(i--);
            }
        }

        // get (deprecated node) creative world names from the config file
        List<String> deprecated_creativeClaimsEnabledWorldNames = config.getStringList(
            "GriefPrevention.Claims.CreativeRulesWorlds"
        );

        // validate that list
        for (int i = 0; i < deprecated_creativeClaimsEnabledWorldNames.size(); i++) {
            String worldName = deprecated_creativeClaimsEnabledWorldNames.get(i);
            World world = this.getServer().getWorld(worldName);
            if (world == null) {
                deprecated_claimsEnabledWorldNames.remove(i--);
            }
        }

        // get (deprecated) pvp fire placement proximity note and use it if it exists
        // (in the new config format it will be overwritten later).
        config_pvp_allowFireNearPlayers = config.getBoolean(
            "GriefPrevention.PvP.AllowFlintAndSteelNearOtherPlayers",
            false
        );
        // get (deprecated) pvp lava dump proximity note and use it if it exists (in the
        // new config format it will be overwritten later).
        config_pvp_allowLavaNearPlayers = config.getBoolean(
            "GriefPrevention.PvP.AllowLavaDumpingNearOtherPlayers",
            false
        );

        // decide claim mode for each world
        this.config_claims_worldModes = new ConcurrentHashMap<>();
        this.config_creativeWorldsExist = false;
        for (World world : worlds) {
            // is it specified in the config file?
            String configSetting = config.getString("GriefPrevention.Claims.Mode." + world.getName());
            if (configSetting != null) {
                ClaimsMode claimsMode = this.configStringToClaimsMode(configSetting);
                if (claimsMode != null) {
                    this.config_claims_worldModes.put(world, claimsMode);
                    if (claimsMode == ClaimsMode.Creative) this.config_creativeWorldsExist = true;
                    continue;
                } else {
                    GriefPrevention.AddLogEntry(
                        "Error: Invalid claim mode \"" +
                            configSetting +
                            "\".  Options are Survival, Creative, and Disabled."
                    );
                    this.config_claims_worldModes.put(world, ClaimsMode.Creative);
                    this.config_creativeWorldsExist = true;
                }
            }

            // was it specified in a deprecated config node?
            if (deprecated_creativeClaimsEnabledWorldNames.contains(world.getName())) {
                this.config_claims_worldModes.put(world, ClaimsMode.Creative);
                this.config_creativeWorldsExist = true;
            } else if (deprecated_claimsEnabledWorldNames.contains(world.getName())) {
                this.config_claims_worldModes.put(world, ClaimsMode.Survival);
            }
            // does the world's name indicate its purpose?
            else if (world.getName().toLowerCase().contains("survival")) {
                this.config_claims_worldModes.put(world, ClaimsMode.Survival);
            } else if (world.getName().toLowerCase().contains("creative")) {
                this.config_claims_worldModes.put(world, ClaimsMode.Creative);
                this.config_creativeWorldsExist = true;
            }
            // decide a default based on server type and world type
            else if (this.getServer().getDefaultGameMode() == GameMode.CREATIVE) {
                this.config_claims_worldModes.put(world, ClaimsMode.Creative);
                this.config_creativeWorldsExist = true;
            } else if (world.getEnvironment() == Environment.NORMAL) {
                this.config_claims_worldModes.put(world, ClaimsMode.Survival);
            } else {
                this.config_claims_worldModes.put(world, ClaimsMode.Disabled);
            }

            // if the setting WOULD be disabled but this is a server upgrading from the old
            // config format,
            // then default to survival mode for safety's sake (to protect any admin claims
            // which may
            // have been created there)
            if (
                this.config_claims_worldModes.get(world) == ClaimsMode.Disabled &&
                !deprecated_claimsEnabledWorldNames.isEmpty()
            ) {
                this.config_claims_worldModes.put(world, ClaimsMode.Survival);
            }
        }

        // pvp worlds list
        this.config_pvp_specifiedWorlds = new HashMap<>();
        for (World world : worlds) {
            boolean pvpWorld = config.getBoolean(
                "GriefPrevention.PvP.RulesEnabledInWorld." + world.getName(),
                world.getPVP()
            );
            this.config_pvp_specifiedWorlds.put(world, pvpWorld);
        }

        List<String> siegeEnabledWorldNames = config.getStringList("GriefPrevention.Siege.Worlds");
        this.config_siege_enabledWorlds = new ArrayList<>();
        for (String worldName : siegeEnabledWorldNames) {
            World world = this.getServer().getWorld(worldName);
            if (world == null) {
                AddLogEntry("Error: Siege Configuration: There's no world named \"" + worldName + "\".");
            } else {
                this.config_siege_enabledWorlds.add(world);
            }
        }

        List<String> defaultSiegeBlockNames = Arrays.asList(
            "DIRT", "GRASS_BLOCK", "GRASS", "SHORT_GRASS", "FERN", "DEAD_BUSH",
            "COBBLESTONE", "GRAVEL", "SAND", "GLASS", "GLASS_PANE",
            "WOOD", "WOOL", "OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS",
            "JUNGLE_PLANKS", "ACACIA_PLANKS", "DARK_OAK_PLANKS", "WHITE_WOOL",
            "ORANGE_WOOL", "MAGENTA_WOOL", "LIGHT_BLUE_WOOL", "YELLOW_WOOL",
            "LIME_WOOL", "PINK_WOOL", "GRAY_WOOL", "LIGHT_GRAY_WOOL", "CYAN_WOOL",
            "PURPLE_WOOL", "BLUE_WOOL", "BROWN_WOOL", "GREEN_WOOL", "RED_WOOL",
            "BLACK_WOOL", "SNOW"
        );
        List<String> siegeBreakableBlockNames = config.isList("GriefPrevention.Siege.BreakableBlocks")
            ? config.getStringList("GriefPrevention.Siege.BreakableBlocks")
            : defaultSiegeBlockNames;
        this.config_siege_blocks = new HashSet<>();
        for (String materialName : siegeBreakableBlockNames) {
            Material material = Material.getMaterial(materialName.toUpperCase(Locale.ROOT));
            if (material != null) this.config_siege_blocks.add(material);
        }
        this.config_siege_doorsOpenSeconds = Math.max(0,
            config.getInt("GriefPrevention.Siege.DoorsOpenDelayInSeconds", 5 * 60));
        this.config_siege_cooldownEndInMinutes = Math.max(0,
            config.getInt("GriefPrevention.Siege.CooldownEndInMinutes", 60));

        // sea level
        this.config_seaLevelOverride = new HashMap<>();
        for (World world : worlds) {
            int seaLevelOverride = config.getInt("GriefPrevention.SeaLevelOverrides." + world.getName(), -1);
            outConfig.set("GriefPrevention.SeaLevelOverrides." + world.getName(), seaLevelOverride);
            this.config_seaLevelOverride.put(world.getName(), seaLevelOverride);
        }

        this.config_claims_preventGlobalMonsterEggs = config.getBoolean(
            "GriefPrevention.Claims.PreventGlobalMonsterEggs",
            true
        );
        this.config_claims_preventTheft = config.getBoolean("GriefPrevention.Claims.PreventTheft", true);
        this.config_claims_protectCreatures = config.getBoolean("GriefPrevention.Claims.ProtectCreatures", true);
        this.config_claims_protectHorses = config.getBoolean("GriefPrevention.Claims.ProtectHorses", true);
        this.config_claims_protectDonkeys = config.getBoolean("GriefPrevention.Claims.ProtectDonkeys", true);
        this.config_claims_protectLlamas = config.getBoolean("GriefPrevention.Claims.ProtectLlamas", true);
        this.config_claims_preventButtonsSwitches = config.getBoolean(
            "GriefPrevention.Claims.PreventButtonsSwitches",
            true
        );
        this.config_claims_lockWoodenDoors = config.getBoolean("GriefPrevention.Claims.LockWoodenDoors", false);
        this.config_claims_lockTrapDoors = config.getBoolean("GriefPrevention.Claims.LockTrapDoors", false);
        this.config_claims_lockFenceGates = config.getBoolean("GriefPrevention.Claims.LockFenceGates", true);
        this.config_claims_preventNonPlayerCreatedPortals = config.getBoolean(
            "GriefPrevention.Claims.PreventNonPlayerCreatedPortals",
            false
        );
        this.config_claims_enderPearlsRequireAccessTrust = config.getBoolean(
            "GriefPrevention.Claims.EnderPearlsRequireAccessTrust",
            true
        );
        this.config_claims_refundDeniedEnderPearls = config.getBoolean(
            "GriefPrevention.Claims.RefundDeniedEnderPearls",
            true
        );
        this.config_claims_raidTriggersRequireBuildTrust = config.getBoolean(
            "GriefPrevention.Claims.RaidTriggersRequireBuildTrust",
            true
        );
        this.config_claims_initialBlocks = config.getInt("GriefPrevention.Claims.InitialBlocks", 100);
        this.config_claims_blocksAccruedPerHour_default = config.getInt(
            "GriefPrevention.Claims.BlocksAccruedPerHour",
            100
        );
        this.config_claims_blocksAccruedPerHour_default = config.getInt(
            "GriefPrevention.Claims.Claim Blocks Accrued Per Hour.Default",
            config_claims_blocksAccruedPerHour_default
        );
        this.config_claims_maxAccruedBlocks_default = config.getInt("GriefPrevention.Claims.MaxAccruedBlocks", 80000);
        this.config_claims_maxAccruedBlocks_default = config.getInt(
            "GriefPrevention.Claims.Max Accrued Claim Blocks.Default",
            this.config_claims_maxAccruedBlocks_default
        );
        this.config_claims_accruedIdleThreshold = config.getInt(
            "GriefPrevention.Claims.Accrued Idle Threshold",
            config.getInt("GriefPrevention.Claims.AccruedIdleThreshold", 0)
        );
        this.config_claims_accruedIdlePercent = Math.max(
            0,
            config.getInt("GriefPrevention.Claims.AccruedIdlePercent", 0)
        );
        this.config_claims_abandonReturnRatio = config.getDouble("GriefPrevention.Claims.AbandonReturnRatio", 1.0D);
        this.config_claims_automaticClaimsForNewPlayersRadius = config.getInt(
            "GriefPrevention.Claims.AutomaticNewPlayerClaimsRadius",
            4
        );
        this.config_claims_automaticClaimsForNewPlayersRadiusMin = Math.max(
            0,
            Math.min(
                this.config_claims_automaticClaimsForNewPlayersRadius,
                config.getInt("GriefPrevention.Claims.AutomaticNewPlayerClaimsRadiusMinimum", 0)
            )
        );
        this.config_claims_claimsExtendIntoGroundDistance = Math.abs(
            config.getInt("GriefPrevention.Claims.ExtendIntoGroundDistance", 5)
        );
        this.config_claims_minWidth = config.getInt("GriefPrevention.Claims.MinimumWidth", 5);
        this.config_claims_minArea = config.getInt("GriefPrevention.Claims.MinimumArea", 100);
        this.config_claims_shapedMinWidth = Math.max(1, config.getInt("GriefPrevention.Claims.ShapedMinimumWidth", 1));
        this.config_claims_shapedMinArea = Math.max(1, config.getInt("GriefPrevention.Claims.ShapedMinimumArea", 25));
        // per-world max depth
        this.config_claims_maxDepth = new HashMap<>();
        int defaultMaxDepth = config.getInt("GriefPrevention.Claims.MaximumDepth.Default", Integer.MIN_VALUE);
        outConfig.set("GriefPrevention.Claims.MaximumDepth.Default", defaultMaxDepth);

        for (World world : worlds) {
            int maxDepth = config.getInt("GriefPrevention.Claims.MaximumDepth." + world.getName(), defaultMaxDepth);
            this.config_claims_maxDepth.put(world.getName(), maxDepth);
            outConfig.set("GriefPrevention.Claims.MaximumDepth." + world.getName(), maxDepth);
        }

        this.config_claims_minY = config.getInt("GriefPrevention.Claims.MinimumY", Integer.MIN_VALUE);
        this.config_claims_minimumDistance = Math.max(0, config.getInt("GriefPrevention.Claims.MinimumDistance", 0));
        this.config_claims_preventLavaPlaceNearClaims = config.getBoolean("GriefPrevention.Claims.PreventLavaPlaceNearClaims", true);
        // Warn if MinimumY is set above sea level, as this is likely unintended
        if (this.config_claims_minY != Integer.MIN_VALUE) {
            for (World world : worlds) {
                if (!this.claimsEnabledForWorld(world)) continue;
                int minY = this.config_claims_minY;
                int seaLevel = this.getSeaLevel(world);
                if (minY > seaLevel) {
                    getLogger().warning(
                        "MinimumY (" +
                            minY +
                            ") is set above sea level (" +
                            seaLevel +
                            ") " +
                            "for world '" +
                            world.getName() +
                            "'. This prevents claims extending below Y=" +
                            minY +
                            ", which may not have been intended."
                    );
                    break;
                }
            }
        }

        // minimum Y per world
        this.config_claims_minYOverride = new HashMap<>();
        for (World world : worlds) {
            String configPath = "GriefPrevention.Claims.MinimumYOverrides." + world.getName();
            if (config.contains(configPath)) {
                int minYOverride = config.getInt(configPath);
                outConfig.set(configPath, minYOverride);
                this.config_claims_minYOverride.put(world.getName(), minYOverride);
            } else {
                // Don't write default values to config - absence means "use global setting"
                outConfig.set(configPath, null);
            }
        }

        this.config_claims_chestClaimExpirationDays = config.getInt(
            "GriefPrevention.Claims.Expiration.ChestClaimDays",
            7
        );
        this.config_claims_unusedClaimExpirationDays = config.getInt(
            "GriefPrevention.Claims.Expiration.UnusedClaimDays",
            14
        );
        this.config_claims_expirationDays = config.getInt(
            "GriefPrevention.Claims.Expiration.AllClaims.DaysInactive",
            60
        );
        this.config_claims_expirationExemptionTotalBlocks = config.getInt(
            "GriefPrevention.Claims.Expiration.AllClaims.ExceptWhenOwnerHasTotalClaimBlocks",
            10000
        );
        this.config_claims_expirationExemptionBonusBlocks = config.getInt(
            "GriefPrevention.Claims.Expiration.AllClaims.ExceptWhenOwnerHasBonusClaimBlocks",
            5000
        );
        this.config_claims_survivalAutoNatureRestoration = config.getBoolean(
            "GriefPrevention.Claims.Expiration.AutomaticNatureRestoration.SurvivalWorlds",
            false
        );
        this.config_claims_allowTrappedInAdminClaims = config.getBoolean(
            "GriefPrevention.Claims.AllowTrappedInAdminClaims",
            false
        );
        this.config_claims_allowNestedSubClaims = config.getBoolean(
            "GriefPrevention.Claims.AllowNestedSubClaims",
            false
        );
        this.config_claims_allowShapedClaims = config.getBoolean("GriefPrevention.Claims.AllowShapedClaims", false);
        this.config_claims_allowPvPTrust = config.getBoolean("GriefPrevention.Claims.AllowPvPTrust", false);
        this.config_claims_allowPvETrust = config.getBoolean("GriefPrevention.Claims.AllowPvETrust", false);
        this.config_claims_allowShapedSubClaims = config.getBoolean(
            "GriefPrevention.Claims.AllowShapedSubClaims",
            false
        );
        this.config_claims_allow3DSubdivisions = config.getBoolean("GriefPrevention.Claims.Allow3DSubdivisions", true);
        this.config_claims_allow3DAdminClaims = config.getBoolean("GriefPrevention.Claims.Allow3DAdminClaims", true);
        this.config_claims_useClaimSelectSessions = config.getBoolean(
            "GriefPrevention.Claims.UseClaimSelectSessions",
            true
        );
        this.config_claims_useClaimSelectedMessages = config.getBoolean(
            "GriefPrevention.Claims.UseClaimSelectedMessages",
            false
        );
        this.config_claims_legacySubdivisionFormat = config.getBoolean(
            "GriefPrevention.Claims.LegacySubdivisionFormat",
            false
        );

        this.config_claims_maxClaimsPerPlayer = config.getInt(
            "GriefPrevention.Claims.MaximumNumberOfClaimsPerPlayer",
            0
        );
        this.config_claims_respectWorldGuard = config.getBoolean(
            "GriefPrevention.Claims.CreationRequiresWorldGuardBuildPermission",
            true
        );
        this.config_claims_villagerTradingRequiresTrust = config.getBoolean(
            "GriefPrevention.Claims.VillagerTradingRequiresPermission",
            true
        );
        String commandsRequiringAccessTrust = config.getString(
            "GriefPrevention.Claims.CommandsRequiringAccessTrust",
            "/sethome"
        );
        this.config_claims_commandsRequiringAccessTrust = new ArrayList<>(
            Arrays.asList(commandsRequiringAccessTrust.split(";"))
        );
        this.config_claims_supplyPlayerManual = config.getBoolean("GriefPrevention.Claims.DeliverManuals", true);
        this.config_claims_manualDeliveryDelaySeconds = config.getInt(
            "GriefPrevention.Claims.ManualDeliveryDelaySeconds",
            30
        );
        this.config_claims_ravagersBreakBlocks = config.getBoolean("GriefPrevention.Claims.RavagersBreakBlocks", true);

        this.config_claims_firespreads = config.getBoolean("GriefPrevention.Claims.FireSpreadsInClaims", false);
        this.config_claims_firedamages = config.getBoolean("GriefPrevention.Claims.FireDamagesInClaims", false);
        this.config_claims_lecternReadingRequiresAccessTrust = config.getBoolean(
            "GriefPrevention.Claims.LecternReadingRequiresAccessTrust",
            true
        );
        this.config_claims_hoppersRequireBuildTrust = config.getBoolean(
            "GriefPrevention.Claims.HoppersRequireBuildTrust",
            false
        );

        this.config_claims_unprotectedChestWarningCooldownSeconds = config.getInt(
            "GriefPrevention.Claims.UnprotectedChestWarningCooldownSeconds",
            300
        );
        this.config_claims_unprotectedChestWarningDistance = config.getInt(
            "GriefPrevention.Claims.UnprotectedChestWarningDistance",
            100
        );

        List<String> defaultActionBarMessages = Arrays.asList(
            "UnprotectedChestWarning",
            "ExternalLiquidBoundaryViolation",
            "InternalLiquidBoundaryViolation",
            "ExternalPistonBoundaryViolation",
            "InternalPistonBoundaryViolation"
        );
        this.config_claims_actionBarMessages = config.getStringList("GriefPrevention.ActionBarMessages");
        if (this.config_claims_actionBarMessages.isEmpty()) {
            this.config_claims_actionBarMessages = new ArrayList<>(defaultActionBarMessages);
        }

        // Economy settings - disabled by default
        this.config_economy_claimBlocksEnabled = config.getBoolean("GriefPrevention.Economy.ClaimBlocksEnabled", false);
        this.config_economy_claimBlocksMaxBonus = Math.max(
            0,
            config.getInt("GriefPrevention.Economy.ClaimBlocksMaxBonus", 0)
        );
        this.config_economy_claimBlocksPurchaseCost = config.getDouble(
            "GriefPrevention.Economy.ClaimBlocksPurchaseCost",
            1.0
        );
        this.config_economy_claimBlocksSellValue = config.getDouble(
            "GriefPrevention.Economy.ClaimBlocksSellValue",
            0.5
        );

        this.config_spam_enabled = config.getBoolean("GriefPrevention.Spam.Enabled", true);
        this.config_spam_loginCooldownSeconds = config.getInt("GriefPrevention.Spam.LoginCooldownSeconds", 60);
        this.config_spam_loginLogoutNotificationsPerMinute = config.getInt(
            "GriefPrevention.Spam.LoginLogoutNotificationsPerMinute",
            5
        );
        this.config_spam_warningMessage = config.getString(
            "GriefPrevention.Spam.WarningMessage",
            "Please reduce your noise level.  Spammers will be banned."
        );
        this.config_spam_allowedIpAddresses = config.getString(
            "GriefPrevention.Spam.AllowedIpAddresses",
            "1.2.3.4; 5.6.7.8"
        );
        this.config_spam_banOffenders = config.getBoolean("GriefPrevention.Spam.BanOffenders", true);
        this.config_spam_banMessage = config.getString("GriefPrevention.Spam.BanMessage", "Banned for spam.");
        this.config_spam_deathMessageCooldownSeconds = config.getInt(
            "GriefPrevention.Spam.DeathMessageCooldownSeconds",
            120
        );
        this.config_spam_logoutMessageDelaySeconds = config.getInt(
            "GriefPrevention.Spam.Logout Message Delay In Seconds",
            0
        );
        String slashCommandsToMonitor = config.getString(
            "GriefPrevention.Spam.MonitorSlashCommands",
            "/me;/global;/local"
        );
        slashCommandsToMonitor = config.getString("GriefPrevention.Spam.ChatSlashCommands", slashCommandsToMonitor);

        this.config_pvp_protectFreshSpawns = config.getBoolean("GriefPrevention.PvP.ProtectFreshSpawns", true);
        this.config_pvp_punishLogout = config.getBoolean("GriefPrevention.PvP.PunishLogout", true);
        this.config_pvp_combatTimeoutSeconds = config.getInt("GriefPrevention.PvP.CombatTimeoutSeconds", 15);
        this.config_pvp_allowCombatItemDrop = config.getBoolean("GriefPrevention.PvP.AllowCombatItemDrop", false);
        this.config_pvp_allowContainerAccess = config.getBoolean("GriefPrevention.PvP.AllowContainerAccess", false);
        this.config_pvp_allowRespawnAnchor = config.getBoolean("GriefPrevention.PvP.AllowRespawnAnchor", false);
        String bannedPvPCommandsList = config.getString(
            "GriefPrevention.PvP.BlockedSlashCommands",
            "/home;/vanish;/spawn;/tpa"
        );

        this.config_lockDeathDropsInPvpWorlds = config.getBoolean(
            "GriefPrevention.ProtectItemsDroppedOnDeath.PvPWorlds",
            false
        );
        this.config_lockDeathDropsInNonPvpWorlds = config.getBoolean(
            "GriefPrevention.ProtectItemsDroppedOnDeath.NonPvPWorlds",
            true
        );

        this.config_blockClaimExplosions = config.getBoolean("GriefPrevention.BlockLandClaimExplosions", true);
        this.config_blockSurfaceCreeperExplosions = config.getBoolean(
            "GriefPrevention.BlockSurfaceCreeperExplosions",
            true
        );
        this.config_blockSurfaceOtherExplosions = config.getBoolean(
            "GriefPrevention.BlockSurfaceOtherExplosions",
            true
        );
        this.config_blockSkyTrees = config.getBoolean("GriefPrevention.LimitSkyTrees", true);
        this.config_limitTreeGrowth = config.getBoolean("GriefPrevention.LimitTreeGrowth", false);
        this.config_pistonExplosionSound = config.getBoolean("GriefPrevention.PistonExplosionSound", true);
        this.config_pistonMovement = PistonMode.of(config.getString("GriefPrevention.PistonMovement", "CLAIMS_ONLY"));
        if (
            config.isBoolean("GriefPrevention.LimitPistonsToLandClaims") &&
            !config.getBoolean("GriefPrevention.LimitPistonsToLandClaims")
        ) this.config_pistonMovement = PistonMode.EVERYWHERE_SIMPLE;
        if (
            config.isBoolean("GriefPrevention.CheckPistonMovement") &&
            !config.getBoolean("GriefPrevention.CheckPistonMovement")
        ) this.config_pistonMovement = PistonMode.IGNORED;

        this.config_fireSpreads = config.getBoolean("GriefPrevention.FireSpreads", false);
        this.config_fireDestroys = config.getBoolean("GriefPrevention.FireDestroys", false);

        this.config_whisperNotifications = config.getBoolean("GriefPrevention.AdminsGetWhispers", true);
        this.config_signNotifications = config.getBoolean("GriefPrevention.AdminsGetSignNotifications", true);
        String whisperCommandsToMonitor = config.getString(
            "GriefPrevention.WhisperCommands",
            "/tell;/pm;/r;/whisper;/msg"
        );
        whisperCommandsToMonitor = config.getString(
            "GriefPrevention.Spam.WhisperSlashCommands",
            whisperCommandsToMonitor
        );

        this.config_visualizationAntiCheatCompat = config.getBoolean(
            "GriefPrevention.VisualizationAntiCheatCompatMode",
            false
        );
        this.config_visualizationGlow = config.getBoolean("GriefPrevention.VisualizationGlow", false);
        this.config_smartBan = config.getBoolean("GriefPrevention.SmartBan", true);
        this.config_trollFilterEnabled = config.getBoolean("GriefPrevention.Mute New Players Using Banned Words", true);
        this.config_ipLimit = config.getInt("GriefPrevention.MaxPlayersPerIpAddress", 3);
        this.config_silenceBans = config.getBoolean("GriefPrevention.SilenceBans", true);

        this.config_endermenMoveBlocks = config.getBoolean("GriefPrevention.EndermenMoveBlocks", false);
        this.config_silverfishBreakBlocks = config.getBoolean("GriefPrevention.SilverfishBreakBlocks", false);
        this.config_creaturesTrampleCrops = config.getBoolean("GriefPrevention.CreaturesTrampleCrops", false);
        this.config_rabbitsEatCrops = config.getBoolean("GriefPrevention.RabbitsEatCrops", true);
        this.config_zombiesBreakDoors = config.getBoolean("GriefPrevention.HardModeZombiesBreakDoors", false);
        this.config_mobProjectilesChangeBlocks = config.getBoolean("GriefPrevention.MobProjectilesChangeBlocks", false);
        this.config_ban_useCommand = config.getBoolean("GriefPrevention.UseBanCommand", false);
        this.config_ban_commandFormat = config.getString("GriefPrevention.BanCommandPattern", "ban %name% %reason%");

        // default for claim investigation tool
        String investigationToolMaterialName = Material.STICK.name();

        // get investigation tool from config
        investigationToolMaterialName = config.getString(
            "GriefPrevention.Claims.InvestigationTool",
            investigationToolMaterialName
        );

        // validate investigation tool
        this.config_claims_investigationTool = Material.getMaterial(investigationToolMaterialName);
        if (this.config_claims_investigationTool == null) {
            GriefPrevention.AddLogEntry(
                "ERROR: Material " +
                    investigationToolMaterialName +
                    " not found.  Defaulting to the stick.  Please update your config.yml."
            );
            this.config_claims_investigationTool = Material.STICK;
        }

        // default for claim creation/modification tool
        Material defaultModTool = com.griefprevention.compat.MaterialCompat.get("GOLDEN_SHOVEL");
        if (defaultModTool == null) {
            defaultModTool = com.griefprevention.compat.MaterialCompat.get("GOLDEN_SHOVEL");
        }
        String modificationToolMaterialName = defaultModTool != null ? defaultModTool.name() : "GOLDEN_SHOVEL";

        // get modification tool from config
        modificationToolMaterialName = config.getString(
            "GriefPrevention.Claims.ModificationTool",
            modificationToolMaterialName
        );

        // validate modification tool
        this.config_claims_modificationTool = Material.getMaterial(modificationToolMaterialName);
        if (this.config_claims_modificationTool == null) {
            GriefPrevention.AddLogEntry(
                "ERROR: Material " +
                    modificationToolMaterialName +
                    " not found.  Defaulting to the golden shovel.  Please update your config.yml."
            );
            try {
                this.config_claims_modificationTool = Material.valueOf("GOLDEN_SHOVEL");
            } catch (IllegalArgumentException e) {
                // 1.8.8: use GOLD_SPADE
                this.config_claims_modificationTool = Material.valueOf("GOLD_SPADE");
            }
        }

        this.config_pvp_noCombatInPlayerLandClaims = config.getBoolean(
            "GriefPrevention.PvP.ProtectPlayersInLandClaims.PlayerOwnedClaims",
            this.config_siege_enabledWorlds.isEmpty()
        );
        this.config_pvp_noCombatInAdminLandClaims = config.getBoolean(
            "GriefPrevention.PvP.ProtectPlayersInLandClaims.AdministrativeClaims",
            this.config_siege_enabledWorlds.isEmpty()
        );
        this.config_pvp_noCombatInAdminSubdivisions = config.getBoolean(
            "GriefPrevention.PvP.ProtectPlayersInLandClaims.AdministrativeSubdivisions",
            this.config_siege_enabledWorlds.isEmpty()
        );
        this.config_pvp_allowLavaNearPlayers = config.getBoolean(
            "GriefPrevention.PvP.AllowLavaDumpingNearOtherPlayers.PvPWorlds",
            true
        );
        this.config_pvp_allowLavaNearPlayers_NonPvp = config.getBoolean(
            "GriefPrevention.PvP.AllowLavaDumpingNearOtherPlayers.NonPvPWorlds",
            false
        );
        this.config_pvp_allowFireNearPlayers = config.getBoolean(
            "GriefPrevention.PvP.AllowFlintAndSteelNearOtherPlayers.PvPWorlds",
            true
        );
        this.config_pvp_allowFireNearPlayers_NonPvp = config.getBoolean(
            "GriefPrevention.PvP.AllowFlintAndSteelNearOtherPlayers.NonPvPWorlds",
            false
        );
        this.config_pvp_protectPets = config.getBoolean("GriefPrevention.PvP.ProtectPetsOutsideLandClaims", false);

        // PvP toggle cost configuration
        this.config_pvp_toggleCostClaimEnabled = config.getBoolean(
            "GriefPrevention.Claims.PvPToggle.Claim.Enabled",
            false
        );
        this.config_pvp_toggleCostClaimPrice = config.getDouble("GriefPrevention.Claims.PvPToggle.Claim.Price", 0.0);
        this.config_pvp_toggleCostSubdivisionEnabled = config.getBoolean(
            "GriefPrevention.Claims.PvPToggle.Subdivision.Enabled",
            false
        );
        this.config_pvp_toggleCostSubdivisionPrice = config.getDouble(
            "GriefPrevention.Claims.PvPToggle.Subdivision.Price",
            0.0
        );
        this.config_pvp_subdivisionPvpState = config.getString(
            "GriefPrevention.Claims.PvPToggle.Subdivision.DefaultState",
            "INHERIT"
        );

        // optional database settings
        loadDatabaseSettings(config);

        this.config_advanced_fixNegativeClaimblockAmounts = config.getBoolean(
            "GriefPrevention.Advanced.fixNegativeClaimblockAmounts",
            true
        );
        this.config_advanced_claim_expiration_check_rate = config.getInt(
            "GriefPrevention.Advanced.ClaimExpirationCheckRate",
            60
        );
        this.config_advanced_offlineplayer_cache_days = config.getInt(
            "GriefPrevention.Advanced.OfflinePlayer_cache_days",
            90
        );

        // custom logger settings
        this.config_logs_daysToKeep = config.getInt("GriefPrevention.Abridged Logs.Days To Keep", 7);
        this.config_logs_socialEnabled = config.getBoolean(
            "GriefPrevention.Abridged Logs.Included Entry Types.Social Activity",
            true
        );
        this.config_logs_suspiciousEnabled = config.getBoolean(
            "GriefPrevention.Abridged Logs.Included Entry Types.Suspicious Activity",
            true
        );
        this.config_logs_adminEnabled = config.getBoolean(
            "GriefPrevention.Abridged Logs.Included Entry Types.Administrative Activity",
            false
        );
        this.config_logs_debugEnabled = config.getBoolean(
            "GriefPrevention.Abridged Logs.Included Entry Types.Debug",
            false
        );
        this.config_logs_mutedChatEnabled = config.getBoolean(
            "GriefPrevention.Abridged Logs.Included Entry Types.Muted Chat Messages",
            false
        );

        // claims mode by world
        for (World world : this.config_claims_worldModes.keySet()) {
            outConfig.set(
                "GriefPrevention.Claims.Mode." + world.getName(),
                this.config_claims_worldModes.get(world).name()
            );
        }

        outConfig.set("GriefPrevention.Claims.PreventGlobalMonsterEggs", this.config_claims_preventGlobalMonsterEggs);
        outConfig.set("GriefPrevention.Claims.PreventTheft", this.config_claims_preventTheft);
        outConfig.set("GriefPrevention.Claims.ProtectCreatures", this.config_claims_protectCreatures);
        outConfig.set("GriefPrevention.Claims.PreventButtonsSwitches", this.config_claims_preventButtonsSwitches);
        outConfig.set("GriefPrevention.Claims.LockWoodenDoors", this.config_claims_lockWoodenDoors);
        outConfig.set("GriefPrevention.Claims.LockTrapDoors", this.config_claims_lockTrapDoors);
        outConfig.set("GriefPrevention.Claims.LockFenceGates", this.config_claims_lockFenceGates);
        outConfig.set(
            "GriefPrevention.Claims.EnderPearlsRequireAccessTrust",
            this.config_claims_enderPearlsRequireAccessTrust
        );
        outConfig.set("GriefPrevention.Claims.RefundDeniedEnderPearls", this.config_claims_refundDeniedEnderPearls);
        outConfig.set(
            "GriefPrevention.Claims.RaidTriggersRequireBuildTrust",
            this.config_claims_raidTriggersRequireBuildTrust
        );
        outConfig.set("GriefPrevention.Claims.ProtectHorses", this.config_claims_protectHorses);
        outConfig.set("GriefPrevention.Claims.ProtectDonkeys", this.config_claims_protectDonkeys);
        outConfig.set("GriefPrevention.Claims.ProtectLlamas", this.config_claims_protectLlamas);
        outConfig.set("GriefPrevention.Claims.InitialBlocks", this.config_claims_initialBlocks);
        outConfig.set(
            "GriefPrevention.Claims.Claim Blocks Accrued Per Hour.Default",
            this.config_claims_blocksAccruedPerHour_default
        );
        outConfig.set(
            "GriefPrevention.Claims.Max Accrued Claim Blocks.Default",
            this.config_claims_maxAccruedBlocks_default
        );
        outConfig.set("GriefPrevention.Claims.Accrued Idle Threshold", this.config_claims_accruedIdleThreshold);
        outConfig.set("GriefPrevention.Claims.AccruedIdlePercent", this.config_claims_accruedIdlePercent);
        outConfig.set("GriefPrevention.Claims.AbandonReturnRatio", this.config_claims_abandonReturnRatio);
        outConfig.set("GriefPrevention.Siege.Worlds", siegeEnabledWorldNames);
        ArrayList<String> configuredSiegeBlocks = new ArrayList<>();
        for (Material material : this.config_siege_blocks) configuredSiegeBlocks.add(material.name());
        Collections.sort(configuredSiegeBlocks);
        outConfig.set("GriefPrevention.Siege.BreakableBlocks", configuredSiegeBlocks);
        outConfig.set("GriefPrevention.Siege.DoorsOpenDelayInSeconds", this.config_siege_doorsOpenSeconds);
        outConfig.set("GriefPrevention.Siege.CooldownEndInMinutes", this.config_siege_cooldownEndInMinutes);
        outConfig.set(
            "GriefPrevention.Claims.AutomaticNewPlayerClaimsRadius",
            this.config_claims_automaticClaimsForNewPlayersRadius
        );
        outConfig.set(
            "GriefPrevention.Claims.AutomaticNewPlayerClaimsRadiusMinimum",
            this.config_claims_automaticClaimsForNewPlayersRadiusMin
        );
        outConfig.set(
            "GriefPrevention.Claims.ExtendIntoGroundDistance",
            this.config_claims_claimsExtendIntoGroundDistance
        );
        outConfig.set("GriefPrevention.Claims.MinimumWidth", this.config_claims_minWidth);
        outConfig.set("GriefPrevention.Claims.MinimumArea", this.config_claims_minArea);
        outConfig.set("GriefPrevention.Claims.ShapedMinimumWidth", this.config_claims_shapedMinWidth);
        outConfig.set("GriefPrevention.Claims.ShapedMinimumArea", this.config_claims_shapedMinArea);
        outConfig.set("GriefPrevention.Claims.MinimumY", this.config_claims_minY);
        outConfig.set("GriefPrevention.Claims.MinimumDistance", this.config_claims_minimumDistance);
        outConfig.set("GriefPrevention.Claims.PreventLavaPlaceNearClaims", this.config_claims_preventLavaPlaceNearClaims);
        outConfig.set(
            "GriefPrevention.Claims.CreationRequiresWorldGuardBuildPermission",
            this.config_claims_respectWorldGuard
        );
        outConfig.set("GriefPrevention.Claims.InvestigationTool", this.config_claims_investigationTool.name());
        outConfig.set("GriefPrevention.Claims.ModificationTool", this.config_claims_modificationTool.name());
        outConfig.set("GriefPrevention.Claims.Expiration.ChestClaimDays", this.config_claims_chestClaimExpirationDays);
        outConfig.set("GriefPrevention.Claims.Expiration.UnusedClaimDays", this.config_claims_unusedClaimExpirationDays);
        outConfig.set("GriefPrevention.Claims.Expiration.AllClaims.DaysInactive", this.config_claims_expirationDays);
        outConfig.set(
            "GriefPrevention.Claims.Expiration.AllClaims.ExceptWhenOwnerHasTotalClaimBlocks",
            this.config_claims_expirationExemptionTotalBlocks
        );
        outConfig.set(
            "GriefPrevention.Claims.Expiration.AllClaims.ExceptWhenOwnerHasBonusClaimBlocks",
            this.config_claims_expirationExemptionBonusBlocks
        );
        outConfig.set(
            "GriefPrevention.Claims.Expiration.AutomaticNatureRestoration.SurvivalWorlds",
            this.config_claims_survivalAutoNatureRestoration
        );
        outConfig.set("GriefPrevention.Claims.AllowTrappedInAdminClaims", this.config_claims_allowTrappedInAdminClaims);
        outConfig.set("GriefPrevention.Claims.AllowNestedSubClaims", this.config_claims_allowNestedSubClaims);
        outConfig.set("GriefPrevention.Claims.AllowShapedClaims", this.config_claims_allowShapedClaims);
        outConfig.set("GriefPrevention.Claims.AllowPvPTrust", this.config_claims_allowPvPTrust);
        outConfig.set("GriefPrevention.Claims.AllowPvETrust", this.config_claims_allowPvETrust);
        outConfig.set("GriefPrevention.Claims.AllowShapedSubClaims", this.config_claims_allowShapedSubClaims);
        outConfig.set("GriefPrevention.Claims.UseClaimSelectSessions", this.config_claims_useClaimSelectSessions);
        outConfig.set("GriefPrevention.Claims.UseClaimSelectedMessages", this.config_claims_useClaimSelectedMessages);
        outConfig.set("GriefPrevention.Claims.LegacySubdivisionFormat", this.config_claims_legacySubdivisionFormat);
        outConfig.set("GriefPrevention.Claims.MaximumNumberOfClaimsPerPlayer", this.config_claims_maxClaimsPerPlayer);
        outConfig.set(
            "GriefPrevention.Claims.VillagerTradingRequiresPermission",
            this.config_claims_villagerTradingRequiresTrust
        );
        outConfig.set("GriefPrevention.Claims.CommandsRequiringAccessTrust", commandsRequiringAccessTrust);
        outConfig.set("GriefPrevention.Claims.DeliverManuals", this.config_claims_supplyPlayerManual);
        outConfig.set(
            "GriefPrevention.Claims.ManualDeliveryDelaySeconds",
            this.config_claims_manualDeliveryDelaySeconds
        );
        outConfig.set("GriefPrevention.Claims.RavagersBreakBlocks", this.config_claims_ravagersBreakBlocks);

        outConfig.set("GriefPrevention.Claims.FireSpreadsInClaims", config_claims_firespreads);
        outConfig.set("GriefPrevention.Claims.FireDamagesInClaims", config_claims_firedamages);
        outConfig.set(
            "GriefPrevention.Claims.LecternReadingRequiresAccessTrust",
            config_claims_lecternReadingRequiresAccessTrust
        );
        outConfig.set("GriefPrevention.Claims.HoppersRequireBuildTrust", this.config_claims_hoppersRequireBuildTrust);
        outConfig.set("GriefPrevention.Claims.UnprotectedChestWarningCooldownSeconds", this.config_claims_unprotectedChestWarningCooldownSeconds);
        outConfig.set("GriefPrevention.Claims.UnprotectedChestWarningDistance", this.config_claims_unprotectedChestWarningDistance);
        outConfig.set("GriefPrevention.ActionBarMessages", this.config_claims_actionBarMessages);

        // Economy settings
        outConfig.set("GriefPrevention.Economy.ClaimBlocksEnabled", this.config_economy_claimBlocksEnabled);
        outConfig.set("GriefPrevention.Economy.ClaimBlocksMaxBonus", this.config_economy_claimBlocksMaxBonus);
        outConfig.set("GriefPrevention.Economy.ClaimBlocksPurchaseCost", this.config_economy_claimBlocksPurchaseCost);
        outConfig.set("GriefPrevention.Economy.ClaimBlocksSellValue", this.config_economy_claimBlocksSellValue);

        outConfig.set("GriefPrevention.Spam.Enabled", this.config_spam_enabled);
        outConfig.set("GriefPrevention.Spam.LoginCooldownSeconds", this.config_spam_loginCooldownSeconds);
        outConfig.set(
            "GriefPrevention.Spam.LoginLogoutNotificationsPerMinute",
            this.config_spam_loginLogoutNotificationsPerMinute
        );
        outConfig.set("GriefPrevention.Spam.ChatSlashCommands", slashCommandsToMonitor);
        outConfig.set("GriefPrevention.Spam.WhisperSlashCommands", whisperCommandsToMonitor);
        outConfig.set("GriefPrevention.Spam.WarningMessage", this.config_spam_warningMessage);
        outConfig.set("GriefPrevention.Spam.BanOffenders", this.config_spam_banOffenders);
        outConfig.set("GriefPrevention.Spam.BanMessage", this.config_spam_banMessage);
        outConfig.set("GriefPrevention.Spam.AllowedIpAddresses", this.config_spam_allowedIpAddresses);
        outConfig.set("GriefPrevention.Spam.DeathMessageCooldownSeconds", this.config_spam_deathMessageCooldownSeconds);
        outConfig.set(
            "GriefPrevention.Spam.Logout Message Delay In Seconds",
            this.config_spam_logoutMessageDelaySeconds
        );

        for (World world : worlds) {
            outConfig.set("GriefPrevention.PvP.RulesEnabledInWorld." + world.getName(), this.pvpRulesApply(world));
        }
        outConfig.set("GriefPrevention.PvP.ProtectFreshSpawns", this.config_pvp_protectFreshSpawns);
        outConfig.set("GriefPrevention.PvP.PunishLogout", this.config_pvp_punishLogout);
        outConfig.set("GriefPrevention.PvP.CombatTimeoutSeconds", this.config_pvp_combatTimeoutSeconds);
        outConfig.set("GriefPrevention.PvP.AllowCombatItemDrop", this.config_pvp_allowCombatItemDrop);
        outConfig.set("GriefPrevention.PvP.AllowContainerAccess", this.config_pvp_allowContainerAccess);
        outConfig.set("GriefPrevention.PvP.AllowRespawnAnchor", this.config_pvp_allowRespawnAnchor);
        outConfig.set("GriefPrevention.PvP.BlockedSlashCommands", bannedPvPCommandsList);
        outConfig.set(
            "GriefPrevention.PvP.ProtectPlayersInLandClaims.PlayerOwnedClaims",
            this.config_pvp_noCombatInPlayerLandClaims
        );
        outConfig.set(
            "GriefPrevention.PvP.ProtectPlayersInLandClaims.AdministrativeClaims",
            this.config_pvp_noCombatInAdminLandClaims
        );
        outConfig.set(
            "GriefPrevention.PvP.ProtectPlayersInLandClaims.AdministrativeSubdivisions",
            this.config_pvp_noCombatInAdminSubdivisions
        );
        outConfig.set(
            "GriefPrevention.PvP.AllowLavaDumpingNearOtherPlayers.PvPWorlds",
            this.config_pvp_allowLavaNearPlayers
        );
        outConfig.set(
            "GriefPrevention.PvP.AllowLavaDumpingNearOtherPlayers.NonPvPWorlds",
            this.config_pvp_allowLavaNearPlayers_NonPvp
        );
        outConfig.set(
            "GriefPrevention.PvP.AllowFlintAndSteelNearOtherPlayers.PvPWorlds",
            this.config_pvp_allowFireNearPlayers
        );
        outConfig.set(
            "GriefPrevention.PvP.AllowFlintAndSteelNearOtherPlayers.NonPvPWorlds",
            this.config_pvp_allowFireNearPlayers_NonPvp
        );
        outConfig.set("GriefPrevention.PvP.ProtectPetsOutsideLandClaims", this.config_pvp_protectPets);

        outConfig.set("GriefPrevention.Claims.PvPToggle.Claim.Enabled", this.config_pvp_toggleCostClaimEnabled);
        outConfig.set("GriefPrevention.Claims.PvPToggle.Claim.Price", this.config_pvp_toggleCostClaimPrice);
        outConfig.set(
            "GriefPrevention.Claims.PvPToggle.Subdivision.Enabled",
            this.config_pvp_toggleCostSubdivisionEnabled
        );
        outConfig.set("GriefPrevention.Claims.PvPToggle.Subdivision.Price", this.config_pvp_toggleCostSubdivisionPrice);
        outConfig.set("GriefPrevention.Claims.PvPToggle.Subdivision.DefaultState", this.config_pvp_subdivisionPvpState);

        outConfig.set("GriefPrevention.ProtectItemsDroppedOnDeath.PvPWorlds", this.config_lockDeathDropsInPvpWorlds);
        outConfig.set(
            "GriefPrevention.ProtectItemsDroppedOnDeath.NonPvPWorlds",
            this.config_lockDeathDropsInNonPvpWorlds
        );

        outConfig.set("GriefPrevention.BlockLandClaimExplosions", this.config_blockClaimExplosions);
        outConfig.set("GriefPrevention.BlockSurfaceCreeperExplosions", this.config_blockSurfaceCreeperExplosions);
        outConfig.set("GriefPrevention.BlockSurfaceOtherExplosions", this.config_blockSurfaceOtherExplosions);
        outConfig.set("GriefPrevention.LimitSkyTrees", this.config_blockSkyTrees);
        outConfig.set("GriefPrevention.LimitTreeGrowth", this.config_limitTreeGrowth);
        outConfig.set("GriefPrevention.PistonMovement", this.config_pistonMovement.name());
        outConfig.set("GriefPrevention.CheckPistonMovement", null);
        outConfig.set("GriefPrevention.LimitPistonsToLandClaims", null);
        outConfig.set("GriefPrevention.PistonExplosionSound", this.config_pistonExplosionSound);

        outConfig.set("GriefPrevention.FireSpreads", this.config_fireSpreads);
        outConfig.set("GriefPrevention.FireDestroys", this.config_fireDestroys);

        outConfig.set("GriefPrevention.AdminsGetWhispers", this.config_whisperNotifications);
        outConfig.set("GriefPrevention.AdminsGetSignNotifications", this.config_signNotifications);

        outConfig.set("GriefPrevention.VisualizationAntiCheatCompatMode", this.config_visualizationAntiCheatCompat);
        outConfig.set("GriefPrevention.VisualizationGlow", this.config_visualizationGlow);
        outConfig.set("GriefPrevention.SmartBan", this.config_smartBan);
        outConfig.set("GriefPrevention.Mute New Players Using Banned Words", this.config_trollFilterEnabled);
        outConfig.set("GriefPrevention.MaxPlayersPerIpAddress", this.config_ipLimit);
        outConfig.set("GriefPrevention.SilenceBans", this.config_silenceBans);

        outConfig.set("GriefPrevention.EndermenMoveBlocks", this.config_endermenMoveBlocks);
        outConfig.set("GriefPrevention.SilverfishBreakBlocks", this.config_silverfishBreakBlocks);
        outConfig.set("GriefPrevention.CreaturesTrampleCrops", this.config_creaturesTrampleCrops);
        outConfig.set("GriefPrevention.RabbitsEatCrops", this.config_rabbitsEatCrops);
        outConfig.set("GriefPrevention.HardModeZombiesBreakDoors", this.config_zombiesBreakDoors);
        outConfig.set("GriefPrevention.MobProjectilesChangeBlocks", this.config_mobProjectilesChangeBlocks);

        outConfig.set("GriefPrevention.UseBanCommand", this.config_ban_useCommand);
        outConfig.set("GriefPrevention.BanCommandPattern", this.config_ban_commandFormat);

        outConfig.set(
            "GriefPrevention.Advanced.fixNegativeClaimblockAmounts",
            this.config_advanced_fixNegativeClaimblockAmounts
        );
        outConfig.set(
            "GriefPrevention.Advanced.ClaimExpirationCheckRate",
            this.config_advanced_claim_expiration_check_rate
        );
        outConfig.set(
            "GriefPrevention.Advanced.OfflinePlayer_cache_days",
            this.config_advanced_offlineplayer_cache_days
        );

        // custom logger settings
        outConfig.set("GriefPrevention.Abridged Logs.Days To Keep", this.config_logs_daysToKeep);
        outConfig.set(
            "GriefPrevention.Abridged Logs.Included Entry Types.Social Activity",
            this.config_logs_socialEnabled
        );
        outConfig.set(
            "GriefPrevention.Abridged Logs.Included Entry Types.Suspicious Activity",
            this.config_logs_suspiciousEnabled
        );
        outConfig.set(
            "GriefPrevention.Abridged Logs.Included Entry Types.Administrative Activity",
            this.config_logs_adminEnabled
        );
        outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Debug", this.config_logs_debugEnabled);
        outConfig.set(
            "GriefPrevention.Abridged Logs.Included Entry Types.Muted Chat Messages",
            this.config_logs_mutedChatEnabled
        );
        outConfig.set("GriefPrevention.Locale", this.config_locale);
        outConfig.set("GriefPrevention.PerPlayerLocale", this.config_perPlayerLocale);
        outConfig.set("GriefPrevention.ConfigVersion", 1);

        try {
            outConfig.save(DataStore.configFilePath);
        } catch (IOException exception) {
            AddLogEntry("Unable to write to the configuration file at \"" + DataStore.configFilePath + "\"");
        }

        // try to parse the list of commands requiring access trust in land claims
        this.config_claims_commandsRequiringAccessTrust = new ArrayList<>();
        String[] commands = commandsRequiringAccessTrust.split(";");
        for (String command : commands) {
            if (!command.isEmpty()) {
                this.config_claims_commandsRequiringAccessTrust.add(command.trim().toLowerCase());
            }
        }

        // try to parse the list of commands which should be monitored for spam
        this.config_spam_monitorSlashCommands = new ArrayList<>();
        commands = slashCommandsToMonitor.split(";");
        for (String command : commands) {
            this.config_spam_monitorSlashCommands.add(command.trim().toLowerCase());
        }

        // try to parse the list of commands which should be included in eavesdropping
        this.config_eavesdrop_whisperCommands = new ArrayList<>();
        commands = whisperCommandsToMonitor.split(";");
        for (String command : commands) {
            this.config_eavesdrop_whisperCommands.add(command.trim().toLowerCase());
        }

        // try to parse the list of commands which should be banned during pvp combat
        this.config_pvp_blockedCommands = new ArrayList<>();
        commands = bannedPvPCommandsList.split(";");
        for (String command : commands) {
            this.config_pvp_blockedCommands.add(command.trim().toLowerCase());
        }
    }

    private void loadDatabaseSettings(@NotNull FileConfiguration legacyConfig) {
        File databasePropsFile = new File(DataStore.dataLayerFolderPath, "database.properties");
        Properties databaseProps = new Properties();

        // If properties file exists, use it - old config has already been migrated.
        if (databasePropsFile.exists() && databasePropsFile.isFile()) {
            try (
                InputStreamReader reader = new InputStreamReader(
                    new java.io.FileInputStream(databasePropsFile),
                    StandardCharsets.UTF_8
                )
            ) {
                // Load properties from file.
                databaseProps.load(reader);

                // Set values from loaded properties.
                // Removed unused database fields
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Unable to read database.properties", e);
            }

            return;
        }

        // Otherwise, database details may not have been migrated from legacy
        // configuration.
        // Try to load them.
        // Removed unused database fields
    }

    private ClaimsMode configStringToClaimsMode(String configSetting) {
        if (configSetting.equalsIgnoreCase("Survival")) {
            return ClaimsMode.Survival;
        } else if (configSetting.equalsIgnoreCase("Creative")) {
            return ClaimsMode.Creative;
        } else if (configSetting.equalsIgnoreCase("Disabled")) {
            return ClaimsMode.Disabled;
        } else if (configSetting.equalsIgnoreCase("SurvivalRequiringClaims")) {
            return ClaimsMode.SurvivalRequiringClaims;
        } else {
            return null;
        }
    }

    public int getMaxDepthForWorld(World world) {
        Integer maxDepth = this.config_claims_maxDepth.get(world.getName());
        if (maxDepth == null) {
            return Integer.MIN_VALUE; // default to min value if not set
        }
        return maxDepth;
    }

    private void setUpCommands() {
        this.claimCommand = new com.griefprevention.commands.UnifiedClaimCommand(this);
        new com.griefprevention.commands.UnifiedAdminClaimCommand(this);
        syncShapedCommandRegistration();
        sync3DCommandRegistration();
        syncCombatTrustCommandRegistration();

        // Add tab completion for old trust commands
        TrustTabCompleter trustTabCompleter = new TrustTabCompleter();
        getCommand("trust").setTabCompleter(trustTabCompleter);
        getCommand("accesstrust").setTabCompleter(trustTabCompleter);
        getCommand("containertrust").setTabCompleter(trustTabCompleter);
        getCommand("managetrust").setTabCompleter(trustTabCompleter);
        getCommand("untrust").setTabCompleter(trustTabCompleter);
        getCommand("neighbortrust").setTabCompleter(trustTabCompleter);
        getCommand("distancetrust").setTabCompleter(trustTabCompleter);
    }

    // handle to the unified /claim command so legacy onCommand paths can reuse its handlers
    private @Nullable com.griefprevention.commands.UnifiedClaimCommand claimCommand;

    private com.griefprevention.commands.UnifiedClaimCommand unifiedClaimCommand() {
        if (this.claimCommand == null) {
            this.claimCommand = new com.griefprevention.commands.UnifiedClaimCommand(this);
        }
        return this.claimCommand;
    }

    /**
     * Adds or removes {@code /pvptrust} and {@code /pvetrust} (plus their plugin.yml aliases)
     * from the live command map based on the AllowPvPTrust/AllowPvETrust options, so toggling
     * either option and running {@code /gpreload} takes effect immediately.
     */
    private void syncCombatTrustCommandRegistration() {
        try {
            CommandMap commandMap = obtainCommandMap();
            if (commandMap == null) {
                return;
            }

            String prefix = this.getName().toLowerCase(Locale.ROOT);
            java.util.Map<String, org.bukkit.command.Command> knownCommands = findKnownCommandsMap(commandMap);

            syncOptionalCommand(commandMap, knownCommands, prefix, "pvptrust", this.config_claims_allowPvPTrust);
            syncOptionalCommand(commandMap, knownCommands, prefix, "pvetrust", this.config_claims_allowPvETrust);
        } catch (Exception e) {
            this.getLogger().warning("Failed to sync combat trust commands: " + e.getMessage());
        }
    }

    /**
     * Paper exposes {@link org.bukkit.Server#getCommandMap()} on the implementation; Spigot uses a private field.
     */
    private @Nullable CommandMap obtainCommandMap() {
        try {
            java.lang.reflect.Method method = this.getServer().getClass().getMethod("getCommandMap");
            return (CommandMap) method.invoke(this.getServer());
        } catch (NoSuchMethodException ignored) {
            // Spigot: command map is a field on CraftServer
        } catch (ReflectiveOperationException e) {
            this.getLogger().warning("obtainCommandMap: " + e.getMessage());
            return null;
        }
        try {
            java.lang.reflect.Field commandMapField = this.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            return (CommandMap) commandMapField.get(this.getServer());
        } catch (Exception e) {
            this.getLogger().warning("obtainCommandMap: " + e.getMessage());
            return null;
        }
    }

    /**
     * {@link org.bukkit.command.SimpleCommandMap} stores commands here; Paper may subclass — walk the hierarchy.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable java.util.Map<String, org.bukkit.command.Command> findKnownCommandsMap(
        @NotNull CommandMap commandMap
    ) {
        Class<?> clazz = commandMap.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field knownCommandsField = clazz.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                return (java.util.Map<String, org.bukkit.command.Command>) knownCommandsField.get(commandMap);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return null;
    }

    private void syncShapedCommandRegistration() {
        try {
            CommandMap commandMap = obtainCommandMap();
            if (commandMap == null) {
                return;
            }

            String prefix = this.getName().toLowerCase(Locale.ROOT);
            java.util.Map<String, org.bukkit.command.Command> knownCommands = findKnownCommandsMap(commandMap);

            syncOptionalCommand(commandMap, knownCommands, prefix, "shapedclaims", this.config_claims_allowShapedClaims);
            syncOptionalCommand(
                commandMap,
                knownCommands,
                prefix,
                "shapedsubdivideclaims",
                this.config_claims_allowShapedSubClaims
            );
        } catch (Exception e) {
            this.getLogger().warning("Failed to sync shaped claim commands: " + e.getMessage());
        }
    }

    private void sync3DCommandRegistration() {
        try {
            CommandMap commandMap = obtainCommandMap();
            if (commandMap == null) {
                return;
            }

            String prefix = this.getName().toLowerCase(Locale.ROOT);
            java.util.Map<String, org.bukkit.command.Command> knownCommands = findKnownCommandsMap(commandMap);

            syncOptionalCommand(
                commandMap,
                knownCommands,
                prefix,
                "3dsubdivideclaims",
                this.config_claims_allow3DSubdivisions
            );
            syncOptionalCommand(
                commandMap,
                knownCommands,
                prefix,
                "3dadminclaims",
                this.config_claims_allow3DAdminClaims
            );
        } catch (Exception e) {
            this.getLogger().warning("Failed to sync 3D claim commands: " + e.getMessage());
        }
    }

    /**
     * Adds or removes a config-gated command and every one of its plugin.yml aliases from the live
     * command map, so toggling the option and running {@code /gpreload} takes effect immediately.
     *
     * @param commandMap the server command map
     * @param knownCommands the command map's backing label lookup, or null if it couldn't be reached
     * @param prefix this plugin's command namespace
     * @param commandName the plugin.yml command name
     * @param enabled whether the feature backing the command is currently on
     */
    private void syncOptionalCommand(
        CommandMap commandMap,
        java.util.Map<String, org.bukkit.command.Command> knownCommands,
        String prefix,
        String commandName,
        boolean enabled
    ) {
        org.bukkit.command.PluginCommand command = this.getCommand(commandName);
        if (command == null) {
            return;
        }

        // Snapshot the labels before touching the command map: SimpleCommandMap.register() drops any
        // alias it considers a conflict from Command#getAliases(), and the plugin loader's own
        // registration always looks like one. Reading the aliases afterwards would come back empty
        // and leave the aliases live after the feature is switched off.
        java.util.List<String> labels = commandLabels(command);

        if (enabled) {
            // Register through the command map so the implementation (Paper/Brigadier) stays
            // consistent with plugin.yml, then put back the labels it declined to re-add.
            commandMap.register(prefix, command);
            if (knownCommands == null) {
                return;
            }

            for (String label : labels) {
                org.bukkit.command.Command existing = knownCommands.get(label);
                if (existing == null || existing == command) {
                    knownCommands.put(label, command);
                }
                knownCommands.put(prefix + ":" + label, command);
            }

            return;
        }

        if (knownCommands == null) {
            return;
        }

        for (String label : labels) {
            if (knownCommands.get(label) == command) {
                knownCommands.remove(label);
            }
            knownCommands.remove(prefix + ":" + label);
        }
    }

    // plugin.yml label sets (command name plus aliases), captured the first time each command is
    // synced and reused from then on. See syncOptionalCommand for why they can't be re-read later.
    private final java.util.Map<String, java.util.List<String>> commandLabelsByName = new java.util.HashMap<>();

    private java.util.List<String> commandLabels(org.bukkit.command.PluginCommand command) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        java.util.List<String> cached = this.commandLabelsByName.get(name);
        if (cached != null) {
            return cached;
        }

        java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add(name);
        for (String alias : command.getAliases()) {
            if (alias == null || Compat.isBlank(alias)) {
                continue;
            }

            String normalized = alias.toLowerCase(Locale.ROOT);
            if (!labels.contains(normalized)) {
                labels.add(normalized);
            }
        }

        labels = java.util.Collections.unmodifiableList(labels);
        this.commandLabelsByName.put(name, labels);
        return labels;
    }

    private void displayEnabledHeader() {
        try {
            StartupHeader startupHeader = new StartupHeader(this);
            String message = startupHeader.getRandomHeader();
            if (message == null) return;

            Bukkit.getConsoleSender().sendMessage(message);
        } catch (Exception e) {
            this.getLogger().warning("Failed to display enabled header: " + e.getMessage());
        }
    }

    private boolean setDebugLoggingEnabled(boolean enabled) {
        this.config_logs_debugEnabled = enabled;

        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(DataStore.configFilePath));
        config.set("GriefPrevention.Abridged Logs.Included Entry Types.Debug", enabled);
        try {
            config.save(DataStore.configFilePath);
            return true;
        } catch (IOException exception) {
            AddLogEntry("Unable to write to the configuration file at \"" + DataStore.configFilePath + "\"");
            return false;
        }
    }

    private static class TrustTabCompleter implements org.bukkit.command.TabCompleter {

        @Override
        public java.util.List<String> onTabComplete(
            org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command,
            String alias,
            String[] args
        ) {
            if (args.length == 1 && sender instanceof org.bukkit.entity.Player) {
                org.bukkit.entity.Player player = (org.bukkit.entity.Player) sender;
                java.util.List<String> players = TabCompletions.visiblePlayers(sender, new String[] { args[0] });
                // Exclude the sender themselves
                players.removeIf(name -> name.equalsIgnoreCase(player.getName()));
                return players;
            }
            return java.util.Collections.emptyList();
        }
    }

    // handles slash commands
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        Player player = null;
        if (sender instanceof Player) {
            player = (Player) sender;
        }

        // Legacy administrator diagnostic command.
        if (cmd.getName().equalsIgnoreCase("gpblockinfo") && player != null) {
            ItemStack inHand = this.getItemInHand(player, EquipmentSlot.HAND);
            player.sendMessage("In Hand: " + inHand.getType().name());

            Block inWorld = PlayerEventHandler.getTargetBlock(player, 300);
            if (inWorld == null) inWorld = player.getEyeLocation().getBlock();
            player.sendMessage("In World: " + inWorld.getType().name());
            return true;
        }

        // extendclaim
        if (
            (cmd.getName().equalsIgnoreCase("extendclaim") ||
                cmd.getName().equalsIgnoreCase("expandclaim") ||
                cmd.getName().equalsIgnoreCase("resizeclaim")) &&
            player != null
        ) {
            if (!player.hasPermission("griefprevention.extendclaim")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleExtendClaimCommand(player, args);
        }

        // abandonclaim
        if (cmd.getName().equalsIgnoreCase("abandonclaim") && player != null) {
            if (!player.hasPermission("griefprevention.abandonclaim")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.abandonClaimHandler(player, false);
        }

        // abandontoplevelclaim
        if (cmd.getName().equalsIgnoreCase("abandontoplevelclaim") && player != null) {
            if (!player.hasPermission("griefprevention.abandontoplevelclaim")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.abandonClaimHandler(player, true);
        }

        // ignoreclaims
        if (cmd.getName().equalsIgnoreCase("ignoreclaims") && player != null) {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.ignoreClaims = !playerData.ignoreClaims;

            // toggle ignore claims mode on or off
            if (!playerData.ignoreClaims) {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.RespectingClaims);
            } else {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.IgnoringClaims);
            }

            return true;
        }
        // abandonallclaims
        else if (cmd.getName().equalsIgnoreCase("abandonallclaims") && player != null) {
            if (!player.hasPermission("griefprevention.abandonallclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            if (args.length > 1) return false;

            if (args.length != 1 || !"confirm".equalsIgnoreCase(args[0])) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ConfirmAbandonAllClaims);
                return true;
            }

            // count claims
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            int originalClaimCount = playerData.getClaims().size();

            // check count
            if (originalClaimCount == 0) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.YouHaveNoClaims);
                return true;
            }

            // a single staff-administered subdivision anywhere pins the whole batch
            if (!player.hasPermission("griefprevention.adminclaims")) {
                for (Claim claim : playerData.getClaims()) {
                    if (claim.containsAdminSubdivision()) {
                        GriefPrevention.sendMessage(
                            player,
                            TextMode.Err,
                            Messages.AbandonBlockedAdminSubdivision
                        );
                        return true;
                    }
                }
            }

            if (this.config_claims_abandonReturnRatio != 1.0D) {
                // adjust claim blocks
                for (Claim claim : playerData.getClaims()) {
                    playerData.setAccruedClaimBlocks(
                        playerData.getAccruedClaimBlocks() -
                            (int) Math.ceil(claim.getArea() * (1 - this.config_claims_abandonReturnRatio))
                    );
                }
            }

            // delete them
            this.dataStore.deleteClaimsForPlayer(player.getUniqueId(), false);

            // inform the player
            int remainingBlocks = playerData.getRemainingClaimBlocks();
            GriefPrevention.sendMessage(
                player,
                TextMode.Success,
                Messages.SuccessfulAbandon,
                String.valueOf(remainingBlocks)
            );

            // revert any current visualization
            playerData.setVisibleBoundaries(null);

            return true;
        }
        // trust <player>
        else if (cmd.getName().equalsIgnoreCase("trust") && player != null) {
            if (!player.hasPermission("griefprevention.trust")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            // requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            // most trust commands use this helper method, it keeps them consistent
            this.handleTrustCommand(player, ClaimPermission.Build, args[0], false);

            return true;
        }
        // transferclaim <player>
        else if (cmd.getName().equalsIgnoreCase("transferclaim") && player != null) {
            // which claim is the user in?
            Claim claim = getSelectedOrCurrentClaim(player, false);
            if (claim == null) {
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.TransferClaimMissing);
                return true;
            }

            // check additional permission for admin claims
            if (claim.isAdminClaim() && !player.hasPermission("griefprevention.adminclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.TransferClaimPermission);
                return true;
            }

            UUID newOwnerID = null; // no argument = make an admin claim
            String ownerName = "admin";

            if (args.length > 0) {
                OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
                if (targetPlayer == null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                    return true;
                }
                newOwnerID = targetPlayer.getUniqueId();
                ownerName = targetPlayer.getName();
            }

            // change ownerhsip
            try {
                this.dataStore.changeClaimOwner(claim, newOwnerID);
            } catch (NoTransferException e) {
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.TransferTopLevel);
                return true;
            }

            // confirm
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.TransferSuccess);
            GriefPrevention.AddLogEntry(
                player.getName() +
                    " transferred a claim at " +
                    GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()) +
                    " to " +
                    ownerName +
                    ".",
                CustomLogEntryTypes.AdminActivity
            );

            return true;
        }
        // trustlist
        else if (cmd.getName().equalsIgnoreCase("trustlist") && player != null) {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

            if (claim == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrustListNoClaim);
                return true;
            }

            // if no permission to manage permissions, error message
            Supplier<String> errorMessage = claim.checkPermission(player, ClaimPermission.Manage, null);
            if (errorMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, errorMessage.get());
                return true;
            }

            // otherwise build a list of explicit permissions by permission level
            // and send that to the player
            ArrayList<String> builders = new ArrayList<>();
            ArrayList<String> containers = new ArrayList<>();
            ArrayList<String> accessors = new ArrayList<>();
            ArrayList<String> managers = new ArrayList<>();
            claim.getPermissions(builders, containers, accessors, managers);

            // Also collect inherited permissions from parent claim if this claim inherits
            // permissions
            ArrayList<String> inheritedBuilders = new ArrayList<>();
            ArrayList<String> inheritedContainers = new ArrayList<>();
            ArrayList<String> inheritedAccessors = new ArrayList<>();
            ArrayList<String> inheritedManagers = new ArrayList<>();

            boolean canInheritForDisplay =
                claim.parent != null && claim.parent.parent == null && !claim.getSubclaimRestrictions();

            if (canInheritForDisplay) {
                claim.parent.getPermissions(
                    inheritedBuilders,
                    inheritedContainers,
                    inheritedAccessors,
                    inheritedManagers
                );

                Predicate<String> isDeniedBuilder = id -> claim.isPermissionDenied(id, ClaimPermission.Build);
                Predicate<String> isDeniedContainer = id -> claim.isPermissionDenied(id, ClaimPermission.Container);
                Predicate<String> isDeniedAccessor = id -> claim.isPermissionDenied(id, ClaimPermission.Access);
                Predicate<String> isDeniedManager = id -> claim.isPermissionDenied(id, ClaimPermission.Manage);

                inheritedBuilders.removeIf(isDeniedBuilder);
                inheritedContainers.removeIf(isDeniedContainer);
                inheritedAccessors.removeIf(isDeniedAccessor);
                inheritedManagers.removeIf(isDeniedManager);
            }

            GriefPrevention.sendMessage(player, TextMode.Info, Messages.TrustListHeader, claim.getOwnerName());

            StringBuilder permissions = new StringBuilder();
            permissions.append(ChatColor.GOLD).append('>');

            // Show managers (both explicit and inherited)
            Set<String> allManagers = new HashSet<>(managers);
            allManagers.addAll(inheritedManagers);
            if (!allManagers.isEmpty()) {
                for (String manager : allManagers) permissions.append(this.trustEntryToPlayerName(manager)).append(' ');
            }

            player.sendMessage(permissions.toString());
            permissions = new StringBuilder();
            permissions.append(ChatColor.YELLOW).append('>');

            // Show builders (both explicit and inherited)
            Set<String> allBuilders = new HashSet<>(builders);
            allBuilders.addAll(inheritedBuilders);
            if (!allBuilders.isEmpty()) {
                for (String builder : allBuilders) permissions.append(this.trustEntryToPlayerName(builder)).append(' ');
            }

            player.sendMessage(permissions.toString());
            permissions = new StringBuilder();
            permissions.append(ChatColor.GREEN).append('>');

            // Show containers (both explicit and inherited)
            Set<String> allContainers = new HashSet<>(containers);
            allContainers.addAll(inheritedContainers);
            if (!allContainers.isEmpty()) {
                for (String container : allContainers)
                    permissions.append(this.trustEntryToPlayerName(container)).append(' ');
            }

            player.sendMessage(permissions.toString());
            permissions = new StringBuilder();
            permissions.append(ChatColor.BLUE).append('>');

            // Show accessors (both explicit and inherited)
            Set<String> allAccessors = new HashSet<>(accessors);
            allAccessors.addAll(inheritedAccessors);
            if (!allAccessors.isEmpty()) {
                for (String accessor : allAccessors)
                    permissions.append(this.trustEntryToPlayerName(accessor)).append(' ');
            }

            player.sendMessage(permissions.toString());

            // Show neighbors
            ArrayList<String> neighbors = claim.getAllNeighbors();
            if (!neighbors.isEmpty()) {
                permissions = new StringBuilder();
                permissions.append(ChatColor.LIGHT_PURPLE).append('>');
                for (String neighbor : neighbors) permissions.append(this.trustEntryToPlayerName(neighbor)).append(' ');
                player.sendMessage(permissions.toString());
            }

            player.sendMessage(
                ChatColor.GOLD +
                    this.dataStore.getMessage(Messages.Manage) +
                    " " +
                    ChatColor.YELLOW +
                    this.dataStore.getMessage(Messages.Build) +
                    " " +
                    ChatColor.GREEN +
                    this.dataStore.getMessage(Messages.Containers) +
                    " " +
                    ChatColor.BLUE +
                    this.dataStore.getMessage(Messages.Access) +
                    (neighbors.isEmpty()
                        ? ""
                        : " " + ChatColor.LIGHT_PURPLE + this.dataStore.getMessage(Messages.Neighbor))
            );

            if (claim.getSubclaimRestrictions()) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.HasSubclaimRestriction);
            }

            return true;
        }
        // untrust <player> or untrust [<group>]
        else if (cmd.getName().equalsIgnoreCase("untrust") && player != null) {
            if (!player.hasPermission("griefprevention.untrust")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            // requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

            // determine which claim is selected or being stood in
            Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

            // determine whether a single player or clearing permissions entirely
            boolean clearPermissions = false;
            OfflinePlayer otherPlayer = null;
            if (args[0].equals("all")) {
                if (claim == null || claim.checkPermission(player, ClaimPermission.Edit, null) == null) {
                    clearPermissions = true;
                } else {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClearPermsOwnerOnly);
                    return true;
                }
            } else {
                // validate player argument or group argument
                if (!args[0].startsWith("[") || !args[0].endsWith("]")) {
                    otherPlayer = this.resolvePlayerByName(args[0]);
                    if (!clearPermissions && otherPlayer == null && !args[0].equals("public")) {
                        // bracket any permissions - at this point it must be a permission without
                        // brackets
                        if (args[0].contains(".")) {
                            args[0] = "[" + args[0] + "]";
                        } else {
                            GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                            return true;
                        }
                    }

                    // correct to proper casing
                    if (otherPlayer != null) args[0] = otherPlayer.getName();
                }
            }

            // if no claim here, apply changes to all his claims
            if (claim == null) {
                String idToDrop = args[0];
                if (otherPlayer != null) {
                    idToDrop = otherPlayer.getUniqueId().toString();
                }

                // calling event
                TrustChangedEvent event = new TrustChangedEvent(player, playerData.getClaims(), null, false, idToDrop);
                Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    return true;
                }

                // dropping permissions
                for (Claim targetClaim : event.getClaims()) {
                    claim = targetClaim;

                    // if untrusting "all" drop all permissions
                    if (clearPermissions) {
                        claim.clearPermissions();
                    }
                    // otherwise drop individual permissions
                    else {
                        claim.dropPermission(idToDrop);

                        // Check if this claim has inherited permissions that need to be explicitly
                        // removed
                        if (claim.parent != null && !claim.getSubclaimRestrictions()) {
                            // Get all permissions from parent that would be inherited
                            ArrayList<String> parentBuilders = new ArrayList<>();
                            ArrayList<String> parentContainers = new ArrayList<>();
                            ArrayList<String> parentAccessors = new ArrayList<>();
                            ArrayList<String> parentManagers = new ArrayList<>();
                            claim.parent.getPermissions(
                                parentBuilders,
                                parentContainers,
                                parentAccessors,
                                parentManagers
                            );

                            // Check if the player being untrusted is in any of the parent's permission
                            // lists
                            String playerIdToCheck = idToDrop.toLowerCase();
                            if (
                                parentManagers.contains(playerIdToCheck) ||
                                parentBuilders.contains(playerIdToCheck) ||
                                parentContainers.contains(playerIdToCheck) ||
                                parentAccessors.contains(playerIdToCheck)
                            ) {
                                // Remove the player from this claim's explicit permissions to override
                                // inheritance
                                claim.dropPermission(idToDrop);
                            }
                        }
                    }

                    // Propagate trust removal to child claims that inherit permissions
                    List<Claim> modifiedClaims = new ArrayList<>();
                    modifiedClaims.add(claim);
                    propagateTrustToChildren(claim, idToDrop, null, false, modifiedClaims);

                    // save changes in one batch so subdivisions aren't written one at a time
                    this.dataStore.saveClaims(modifiedClaims);
                }

                // confirmation message
                if (!clearPermissions) {
                    GriefPrevention.sendMessage(player, TextMode.Success, Messages.UntrustIndividualAllClaims, args[0]);
                } else {
                    GriefPrevention.sendMessage(player, TextMode.Success, Messages.UntrustEveryoneAllClaims);
                }
            }
            // otherwise, apply changes to only this claim
            else if (claim.checkPermission(player, ClaimPermission.Manage, null) != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoManageTrust, claim.getOwnerName());
                return true;
            } else {
                // if clearing all
                if (clearPermissions) {
                    // requires owner
                    if (claim.checkPermission(player, ClaimPermission.Edit, null) != null) {
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.UntrustAllOwnerOnly);
                        return true;
                    }

                    // calling the event
                    TrustChangedEvent event = new TrustChangedEvent(player, claim, null, false, args[0]);
                    Bukkit.getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return true;
                    }

                    event.getClaims().forEach(Claim::clearPermissions);
                    GriefPrevention.sendMessage(player, TextMode.Success, Messages.ClearPermissionsOneClaim);
                }
                // otherwise individual permission drop
                else {
                    String idToDrop = args[0];
                    if (otherPlayer != null) {
                        idToDrop = otherPlayer.getUniqueId().toString();
                    }
                    boolean targetIsManager = claim.isManager(idToDrop);
                    // only claim owners can untrust managers
                    if (
                        targetIsManager &&
                        claim.checkPermission(player, ClaimPermission.Edit, null) != null
                    )
                    {
                        GriefPrevention.sendMessage(
                            player,
                            TextMode.Err,
                            Messages.ManagersDontUntrustManagers,
                            claim.getOwnerName()
                        );
                        return true;
                    } else {
                        // calling the event
                        TrustChangedEvent event = new TrustChangedEvent(player, claim, null, false, idToDrop);
                        Bukkit.getPluginManager().callEvent(event);

                        if (event.isCancelled()) {
                            return true;
                        }

                        // Check if the player being untrusted has inherited permissions from parent
                        ArrayList<String> parentBuilders = new ArrayList<>();
                        ArrayList<String> parentContainers = new ArrayList<>();
                        ArrayList<String> parentAccessors = new ArrayList<>();
                        ArrayList<String> parentManagers = new ArrayList<>();

                        String normalizedIdentifier = Claim.normalizeIdentifier(event.getIdentifier());
                        String normalizedIdToDrop = Claim.normalizeIdentifier(idToDrop);

                        if (claim.parent != null && !claim.getSubclaimRestrictions()) {
                            claim.parent.getPermissions(
                                parentBuilders,
                                parentContainers,
                                parentAccessors,
                                parentManagers
                            );
                        }

                        boolean inheritsManager = parentManagers.contains(normalizedIdToDrop);
                        boolean inheritsBuilder = parentBuilders.contains(normalizedIdToDrop);
                        boolean inheritsContainer = parentContainers.contains(normalizedIdToDrop);
                        boolean inheritsAccessor = parentAccessors.contains(normalizedIdToDrop);

                        if (inheritsManager || inheritsBuilder || inheritsContainer || inheritsAccessor) {
                            event.getClaims().forEach(targetClaim -> {
                                // Record denials to block inherited trust without granting new permissions
                                if (inheritsManager) {
                                    targetClaim.denyPermission(normalizedIdToDrop + "#manage");
                                }
                                if (inheritsBuilder) {
                                    targetClaim.denyPermission(normalizedIdToDrop + "#build");
                                }
                                if (inheritsContainer) {
                                    targetClaim.denyPermission(normalizedIdToDrop + "#inventory");
                                }
                                if (inheritsAccessor) {
                                    targetClaim.denyPermission(normalizedIdToDrop + "#access");
                                }

                                // Remove any explicit trust that might still exist
                                targetClaim.dropPermission(normalizedIdentifier);
                            });
                        } else {
                            // Normal case - just drop the explicit permission
                            event.getClaims().forEach(targetClaim -> targetClaim.dropPermission(normalizedIdentifier));
                        }

                        // beautify for output
                        if (args[0].equals("public")) {
                            args[0] = "the public";
                        }

                        GriefPrevention.sendMessage(
                            player,
                            TextMode.Success,
                            Messages.UntrustIndividualSingleClaim,
                            args[0]
                        );
                    }
                }

                // save changes
                this.dataStore.saveClaim(claim);
            }

            return true;
        }
        // accesstrust <player>
        else if (cmd.getName().equalsIgnoreCase("accesstrust") && player != null) {
            if (!player.hasPermission("griefprevention.accesstrust")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            // requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            this.handleTrustCommand(player, ClaimPermission.Access, args[0], false);

            return true;
        }
        // containertrust <player>
        else if (cmd.getName().equalsIgnoreCase("containertrust") && player != null) {
            if (!player.hasPermission("griefprevention.containertrust")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            // requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            this.handleTrustCommand(player, ClaimPermission.Container, args[0], false);

            return true;
        }
        // managetrust <player>
        else if (cmd.getName().equalsIgnoreCase("managetrust") && player != null) {
            if (!player.hasPermission(ClaimTrustCommandPermissions.MANAGE_TRUST)) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            // requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            this.handleTrustCommand(player, ClaimPermission.Manage, args[0], false);

            return true;
        }
        // pvptrust <player> - combat trust against players (requires AllowPvPTrust)
        else if (cmd.getName().equalsIgnoreCase("pvptrust") && player != null) {
            return unifiedClaimCommand().handlePvpTrust(player, args);
        }
        // pvetrust <player> - combat trust against creatures (requires AllowPvETrust)
        else if (cmd.getName().equalsIgnoreCase("pvetrust") && player != null) {
            return unifiedClaimCommand().handlePveTrust(player, args);
        }
        // restrictsubclaim
        else if (cmd.getName().equalsIgnoreCase("restrictsubclaim") && player != null) {
            if (!player.hasPermission("griefprevention.restrictsubclaim")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleRestrictSubclaimCommand(player, new String[] {});
        }
        // adminclaims
        else if (cmd.getName().equalsIgnoreCase("adminclaims") && player != null) {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Admin;
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.AdminClaimsMode);

            return true;
        }
        // 3dadminclaims (both singular and plural)
        else if (
            (cmd.getName().equalsIgnoreCase("3dadminclaims") || cmd.getName().equalsIgnoreCase("3dadminclaim")) &&
            player != null
        ) {
            if (!this.config_claims_allow3DAdminClaims) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.AdminClaims3DDisabled);
                return true;
            }
            if (!player.hasPermission("griefprevention.adminclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Admin3D;
            GriefPrevention.sendMessage(player, TextMode.Success, Messages._3DAdminClaimsMode);

            return true;
        }
        // basicclaims
        else if (cmd.getName().equalsIgnoreCase("basicclaims") && player != null) {
            if (!player.hasPermission("griefprevention.basicclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Basic;
            playerData.claimSubdividing = null;
            playerData.setClaimEditorSession(null);
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.BasicClaimsMode);

            return true;
        }
        // shapedclaims / shapedclaim
        else if (
            (cmd.getName().equalsIgnoreCase("shapedclaims") || cmd.getName().equalsIgnoreCase("shapedclaim")) &&
            player != null
        ) {
            if (!this.config_claims_allowShapedClaims) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedClaimsDisabled);
                return true;
            }
            if (!player.hasPermission("griefprevention.shapedclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Shaped;
            playerData.claimSubdividing = null;
            playerData.claimResizing = null;
            playerData.claimMerging = null;
            playerData.mergeEdgeIndex = null;
            playerData.mergeSecondEdgeIndex = null;
            playerData.mergeFirstDepthPoint = null;
            playerData.mergeSecondDepthPoint = null;
            playerData.lastShovelLocation = null;
            playerData.setClaimEditorSession(null);
            // Always visualize nearby claims when entering shaped mode so players
            // can see existing claim boundaries to shape against
            Set<Claim> claims = this.dataStore.getNearbyClaims(player.getLocation());
            if (!claims.isEmpty()) {
                BoundaryVisualization.mergeNearbyClaims(player, claims);
            }
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ShapedClaimsMode);

            return true;
        }
        // mergeclaims
        else if (cmd.getName().equalsIgnoreCase("mergeclaims") && player != null) {
            if (!player.hasPermission("griefprevention.mergeclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleMergeClaimsCommand(player);
        }
        // subdivideclaims
        else if (cmd.getName().equalsIgnoreCase("subdivideclaims") && player != null) {
            if (!player.hasPermission("griefprevention.subdivideclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Subdivide;
            playerData.claimSubdividing = null;
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionMode);
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                Messages.SubdivisionVideo2,
                DataStore.SUBDIVISION_VIDEO_URL
            );

            return true;
        }
        // shapedsubdivideclaims
        else if (
            (cmd.getName().equalsIgnoreCase("shapedsubdivideclaims") ||
                cmd.getName().equalsIgnoreCase("shapedsubdivideclaim")) &&
            player != null
        ) {
            if (!this.config_claims_allowShapedSubClaims) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubClaimsDisabled);
                return true;
            }
            if (!player.hasPermission("griefprevention.shapedsubdivideclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            this.enterShapedSubdivideMode(player);

            return true;
        }
        // 3dsubdivideclaims
        else if (cmd.getName().equalsIgnoreCase("3dsubdivideclaims") && player != null) {
            if (!this.config_claims_allow3DSubdivisions) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.Subdivisions3DDisabled);
                return true;
            }
            if (!player.hasPermission("griefprevention.3dsubdivideclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.shovelMode = ShovelMode.Subdivide3D;
            playerData.claimSubdividing = null;
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionMode3D);
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                Messages.SubdivisionVideo2,
                DataStore.SUBDIVISION_VIDEO_URL
            );

            return true;
        }
        // deleteclaim
        else if (cmd.getName().equalsIgnoreCase("deleteclaim") && player != null) {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

            if (claim == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.DeleteClaimMissing);
            } else {
                // deleting an admin claim additionally requires the adminclaims permission
                if (!claim.isAdminClaim() || player.hasPermission("griefprevention.adminclaims")) {
                    if (!claim.children.isEmpty() && !playerData.warnedAboutMajorDeletion) {
                        GriefPrevention.sendMessage(player, TextMode.Warn, Messages.DeletionSubdivisionWarning);
                        playerData.warnedAboutMajorDeletion = true;
                    } else {
                        claim.removeSurfaceFluids(null);
                        if (!this.dataStore.deleteClaimWithResult(claim, true, true)) {
                            return true;
                        }

                        // if in a creative mode world, or if configured to do so in survival, restore the area
                        if (
                            this.creativeRulesApply(claim.getLesserBoundaryCorner()) ||
                            this.config_claims_survivalAutoNatureRestoration
                        ) {
                            this.restoreClaim(claim, 0);
                        }

                        if (playerData.claimResizing == claim) {
                            playerData.claimResizing = null;
                            playerData.claimSelectionActive = false;
                            playerData.lastShovelLocation = null;
                        }

                        GriefPrevention.sendMessage(player, TextMode.Success, Messages.DeleteSuccess);
                        GriefPrevention.AddLogEntry(
                            player.getName() +
                                " deleted " +
                                claim.getOwnerName() +
                                "'s claim at " +
                                GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()),
                            CustomLogEntryTypes.AdminActivity
                        );

                        // revert any current visualization
                        playerData.setVisibleBoundaries(null);

                        playerData.warnedAboutMajorDeletion = false;
                    }
                } else {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.CantDeleteAdminClaim);
                }
            }

            return true;
        } else if (cmd.getName().equalsIgnoreCase("claimexplosions") && player != null) {
            if (!player.hasPermission("griefprevention.claimexplosions")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleClaimExplosionsCommand(sender, args);
        } else if (cmd.getName().equalsIgnoreCase("claimpvp") && player != null) {
            if (!player.hasPermission("griefprevention.claims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleClaimPvpCommand(sender, args);
        } else if (cmd.getName().equalsIgnoreCase("claimpvpconfirm") && player != null) {
            if (!player.hasPermission("griefprevention.claims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleClaimPvpConfirmCommand(sender, args);
        } else if (cmd.getName().equalsIgnoreCase("witherexplosions") && player != null) {
            if (!player.hasPermission("griefprevention.witherexplosions")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleWitherExplosionsCommand(sender, args);
        }
        // neighbortrust <player>
        else if (cmd.getName().equalsIgnoreCase("neighbortrust") && player != null) {
            if (!player.hasPermission("griefprevention.neighbortrust")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            if (args.length != 1) return false;
            return this.handleNeighborTrustCommand(sender, args);
        }
        // distancetrust <player> (alias for neighbortrust, handled by plugin.yml alias)
        // checkclaimdistance
        else if (cmd.getName().equalsIgnoreCase("checkclaimdistance") && player != null) {
            if (!player.hasPermission("griefprevention.checkclaimdistance")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleCheckClaimDistanceCommand(sender, args);
        }
        // toggleclaimdistance
        else if (cmd.getName().equalsIgnoreCase("toggleclaimdistance") && player != null) {
            if (!player.hasPermission("griefprevention.toggleclaimdistance")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            return this.handleToggleClaimDistanceCommand(sender, args);
        }
        // checkclaimexpiry
        else if (cmd.getName().equalsIgnoreCase("checkclaimexpiry")) {
            // Check permission
            if (player != null && !player.hasPermission("griefprevention.checkclaimexpiry")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }

            // If no arguments and sender is a player, check the claim they're standing in
            if (args.length == 0 && player != null) {
                Claim claim = getSelectedOrCurrentClaim(player, false);
                if (claim == null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimExpiryNoClaim);
                    return true;
                }

                // Check if the claim will expire
                if (claim.lesserBoundaryCorner == null || claim.greaterBoundaryCorner == null) {
                    GriefPrevention.sendMessage(player, TextMode.Info, Messages.ClaimExpiryNeverExpires);
                    return true;
                }

                // Check if claim is admin claim (they don't expire)
                if (claim.isAdminClaim()) {
                    GriefPrevention.sendMessage(player, TextMode.Info, Messages.ClaimExpiryNeverExpires);
                    return true;
                }

                // Calculate expiration time
                long expirationDate = claim.getExpirationDate();
                if (expirationDate == 0) {
                    GriefPrevention.sendMessage(player, TextMode.Info, Messages.ClaimExpiryNeverExpires);
                    return true;
                }

                long now = System.currentTimeMillis();
                if (expirationDate <= now) {
                    GriefPrevention.sendMessage(player, TextMode.Warn, Messages.ClaimExpiryExpired);
                } else {
                    long timeLeft = expirationDate - now;
                    String timeLeftStr = getFriendlyTimeString(timeLeft);
                    GriefPrevention.sendMessage(player, TextMode.Info, Messages.ClaimExpiryExpiresIn, timeLeftStr);
                }

                return true;
            }
            // If player name is provided, list their expiring claims
            else if (args.length == 1) {
                // Check admin permission for checking other players
                if (player != null && !player.hasPermission("griefprevention.admin.checkclaimexpiry")) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                    return true;
                }

                String playerName = args[0];
                OfflinePlayer targetPlayer = this.resolvePlayerByName(playerName);
                if (targetPlayer == null) {
                    if (sender instanceof Player) {
                        GriefPrevention.sendMessage(
                            (Player) sender,
                            ChatColor.RED,
                            GriefPrevention.instance.dataStore.getMessage(Messages.PlayerNotFound2)
                        );
                    } else {
                        getLogger().info("Player not found: " + playerName);
                    }
                    return true;
                }

                // Get all claims for the player
                List<Claim> claims = this.dataStore.getPlayerData(targetPlayer.getUniqueId()).getClaims();
                if (claims.isEmpty()) {
                    if (sender instanceof Player) {
                        GriefPrevention.sendMessage(
                            (Player) sender,
                            ChatColor.RED,
                            GriefPrevention.instance.dataStore.getMessage(Messages.PlayerNotFound2)
                        );
                    } else {
                        // Fallback for console/command blocks
                        getLogger().info("Player " + playerName + " has no claims");
                    }
                    return true;
                }

                long now = System.currentTimeMillis();
                boolean foundExpiring = false;

                // Check each claim for expiration
                for (Claim claim : claims) {
                    if (claim.lesserBoundaryCorner == null || claim.greaterBoundaryCorner == null) continue;

                    long expirationDate = claim.getExpirationDate();
                    if (expirationDate == 0) continue;

                    long timeLeft = expirationDate - now;
                    if (
                        timeLeft > 0 // Only show claims that haven't expired yet
                    ) {
                        foundExpiring = true;
                        String timeLeftStr = getFriendlyTimeString(timeLeft);
                        String location = String.format(
                            "%s (%d,%d,%d)",
                            claim.getLesserBoundaryCorner().getWorld().getName(),
                            claim.getLesserBoundaryCorner().getBlockX(),
                            claim.getLesserBoundaryCorner().getBlockY(),
                            claim.getLesserBoundaryCorner().getBlockZ()
                        );

                        if (sender instanceof Player) {
                            GriefPrevention.sendMessage(
                                (Player) sender,
                                TextMode.Info,
                                Messages.ClaimExpiryListEntry,
                                location,
                                timeLeftStr
                            );
                        } else {
                            getLogger().info("Claim at " + location + " expires in " + timeLeftStr);
                        }
                    }
                }

                if (!foundExpiring) {
                    if (sender instanceof Player) {
                        GriefPrevention.sendMessage(
                            (Player) sender,
                            TextMode.Info,
                            Messages.ClaimExpiryPlayerNoClaims,
                            targetPlayer.getName()
                        );
                    } else {
                        getLogger().info("Player " + targetPlayer.getName() + " has no expiring claims");
                    }
                }

                return true;
            }
            // Invalid usage
            else {
                if (player != null) {
                    GriefPrevention.sendMessage(player, TextMode.Info, Messages.ClaimExpiryUsage);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /checkclaimexpiry <player>");
                }
                return true;
            }
        }
        // deleteallclaims <player>
        else if (cmd.getName().equalsIgnoreCase("deleteallclaims")) {
            // requires exactly one parameter, the other player's name
            if (args.length != 1) return false;

            // try to find that player
            OfflinePlayer otherPlayer = this.resolvePlayerByName(args[0]);
            if (otherPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            // delete all that player's claims
            this.dataStore.deleteClaimsForPlayer(otherPlayer.getUniqueId(), true);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.DeleteAllSuccess, otherPlayer.getName());
            if (player != null) {
                GriefPrevention.AddLogEntry(
                    player.getName() + " deleted all claims belonging to " + otherPlayer.getName() + ".",
                    CustomLogEntryTypes.AdminActivity
                );

                // revert any current visualization
                if (player.isOnline()) {
                    this.dataStore.getPlayerData(player.getUniqueId()).setVisibleBoundaries(null);
                }
            }

            return true;
        } else if (cmd.getName().equalsIgnoreCase("deleteclaimsinworld")) {
            // must be executed at the console
            if (player != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ConsoleOnlyCommand);
                return true;
            }

            // requires exactly one parameter, the world name
            if (args.length != 1) return false;

            // try to find the specified world
            World world = Bukkit.getServer().getWorld(args[0]);
            if (world == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.WorldNotFound);
                return true;
            }

            // delete all claims in that world
            this.dataStore.deleteClaimsInWorld(world, true);
            GriefPrevention.AddLogEntry(
                "Deleted all claims in world: " + world.getName() + ".",
                CustomLogEntryTypes.AdminActivity
            );
            return true;
        } else if (cmd.getName().equalsIgnoreCase("deleteuserclaimsinworld")) {
            // must be executed at the console
            if (player != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ConsoleOnlyCommand);
                return true;
            }

            // requires exactly one parameter, the world name
            if (args.length != 1) return false;

            // try to find the specified world
            World world = Bukkit.getServer().getWorld(args[0]);
            if (world == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.WorldNotFound);
                return true;
            }

            // delete all USER claims in that world
            this.dataStore.deleteClaimsInWorld(world, false);
            GriefPrevention.AddLogEntry(
                "Deleted all user claims in world: " + world.getName() + ".",
                CustomLogEntryTypes.AdminActivity
            );
            return true;
        }
        // claimbook
        else if (cmd.getName().equalsIgnoreCase("claimbook")) {
            // requires one parameter
            if (args.length != 1) return false;

            // try to find the specified player
            Player otherPlayer = this.getServer().getPlayer(args[0]);
            if (otherPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            } else {
                WelcomeTask task = new WelcomeTask(otherPlayer.getUniqueId());
                task.run();
                return true;
            }
        }
        // claimslist or claimslist <player>
        else if (cmd.getName().equalsIgnoreCase("claimslist")) {
            // at most one parameter
            if (args.length > 1) return false;

            // player whose claims will be listed
            OfflinePlayer otherPlayer;

            // if another player isn't specified, assume current player
            if (args.length < 1) {
                if (player != null) otherPlayer = player;
                else return false;
            }
            // otherwise if no permission to delve into another player's claims data
            else if (player != null && !player.hasPermission("griefprevention.claimslistother")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimsListNoPermission);
                return true;
            }
            // otherwise try to find the specified player
            else {
                otherPlayer = this.resolvePlayerByName(args[0]);
                if (otherPlayer == null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                    return true;
                }
            }

            // load the target player's data
            PlayerData playerData = this.dataStore.getPlayerData(otherPlayer.getUniqueId());
            Vector<Claim> claims = playerData.getClaims();
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                Messages.StartBlockMath,
                String.valueOf(playerData.getAccruedClaimBlocks()),
                String.valueOf(
                    playerData.getBonusClaimBlocks() + this.dataStore.getGroupBonusBlocks(otherPlayer.getUniqueId())
                ),
                String.valueOf(
                    playerData.getAccruedClaimBlocks() +
                        playerData.getBonusClaimBlocks() +
                        this.dataStore.getGroupBonusBlocks(otherPlayer.getUniqueId())
                )
            );
            if (!claims.isEmpty()) {
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimsListHeader);
                for (int i = 0; i < playerData.getClaims().size(); i++) {
                    Claim claim = playerData.getClaims().get(i);
                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Instr,
                        getfriendlyLocationString(claim.getLesserBoundaryCorner()) +
                            this.dataStore.getMessage(Messages.ContinueBlockMath, String.valueOf(claim.getArea()))
                    );
                }

                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    Messages.EndBlockMath,
                    String.valueOf(playerData.getRemainingClaimBlocks())
                );
            }

            // drop the data we just loaded, if the player isn't online
            if (!otherPlayer.isOnline()) this.dataStore.clearCachedPlayerData(otherPlayer.getUniqueId());

            return true;
        }
        // adminclaimslist
        else if (cmd.getName().equalsIgnoreCase("adminclaimslist")) {
            // find admin claims
            Vector<Claim> claims = new Vector<>();
            for (Claim claim : this.dataStore.claims) {
                if (
                    claim.ownerID == null // admin claim
                ) {
                    claims.add(claim);
                }
            }
            if (!claims.isEmpty()) {
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimsListHeader);
                for (Claim claim : claims) {
                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Instr,
                        getfriendlyLocationString(claim.getLesserBoundaryCorner())
                    );
                }
            }

            return true;
        }
        // unlockItems
        else if (cmd.getName().equalsIgnoreCase("unlockdrops") && player != null) {
            PlayerData playerData;

            if (player.hasPermission("griefprevention.unlockothersdrops") && args.length == 1) {
                Player otherPlayer = Bukkit.getPlayer(args[0]);
                if (otherPlayer == null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                    return true;
                }

                playerData = this.dataStore.getPlayerData(otherPlayer.getUniqueId());
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Success,
                    Messages.DropUnlockOthersConfirmation,
                    otherPlayer.getName()
                );
            } else {
                playerData = this.dataStore.getPlayerData(player.getUniqueId());
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.DropUnlockConfirmation);
            }

            playerData.dropsAreUnlocked = true;

            return true;
        }
        // deletealladminclaims
        else if (cmd.getName().equalsIgnoreCase("deletealladminclaims")) {
            if (player != null && !player.hasPermission("griefprevention.deleteclaims")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }

            // delete all admin claims
            this.dataStore.deleteClaimsForPlayer(null, true); // null for owner id indicates an administrative claim

            if (player != null) {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.AllAdminDeleted);
                this.dataStore.getPlayerData(player.getUniqueId()).setVisibleBoundaries(null);
                GriefPrevention.AddLogEntry(player.getName() + " deleted all administrative claims.", CustomLogEntryTypes.AdminActivity);
            } else {
                GriefPrevention.AddLogEntry("Deleted all administrative claims.", CustomLogEntryTypes.AdminActivity);
            }
            return true;
        }
        // adjustbonusclaimblocks <player> <amount> or [<permission>] amount
        else if (cmd.getName().equalsIgnoreCase("adjustbonusclaimblocks")) {
            // requires exactly two parameters, the other player or group's name and the
            // adjustment
            if (args.length != 2) return false;

            // parse the adjustment amount
            int adjustment;
            try {
                adjustment = Integer.parseInt(args[1]);
            } catch (NumberFormatException numberFormatException) {
                return false; // causes usage to be displayed
            }

            // if granting blocks to all players with a specific permission
            if (args[0].startsWith("[") && args[0].endsWith("]")) {
                String permissionIdentifier = args[0].substring(1, args[0].length() - 1);
                int newTotal = this.dataStore.adjustGroupBonusBlocks(permissionIdentifier, adjustment);

                GriefPrevention.sendMessage(
                    player,
                    TextMode.Success,
                    Messages.AdjustGroupBlocksSuccess,
                    permissionIdentifier,
                    String.valueOf(adjustment),
                    String.valueOf(newTotal)
                );
                if (player != null) GriefPrevention.AddLogEntry(
                    player.getName() +
                        " adjusted " +
                        permissionIdentifier +
                        "'s bonus claim blocks by " +
                        adjustment +
                        "."
                );

                return true;
            }

            // otherwise, find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);

            if (targetPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            // give blocks to player
            PlayerData playerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
            playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + adjustment);
            this.dataStore.savePlayerData(targetPlayer.getUniqueId(), playerData);

            GriefPrevention.sendMessage(
                player,
                TextMode.Success,
                Messages.AdjustBlocksSuccess,
                targetPlayer.getName(),
                String.valueOf(adjustment),
                String.valueOf(playerData.getBonusClaimBlocks())
            );
            if (player != null) GriefPrevention.AddLogEntry(
                player.getName() +
                    " adjusted " +
                    targetPlayer.getName() +
                    "'s bonus claim blocks by " +
                    adjustment +
                    ".",
                CustomLogEntryTypes.AdminActivity
            );

            return true;
        }
        // adjustbonusclaimblocksall <amount>
        else if (cmd.getName().equalsIgnoreCase("adjustbonusclaimblocksall")) {
            // requires exactly one parameter, the amount of adjustment
            if (args.length != 1) return false;

            // parse the adjustment amount
            int adjustment;
            try {
                adjustment = Integer.parseInt(args[0]);
            } catch (NumberFormatException numberFormatException) {
                return false; // causes usage to be displayed
            }

            // for each online player
            @SuppressWarnings("unchecked")
            Collection<Player> players = (Collection<Player>) this.getServer().getOnlinePlayers();
            StringBuilder builder = new StringBuilder();
            for (Player onlinePlayer : players) {
                UUID playerID = onlinePlayer.getUniqueId();
                PlayerData playerData = this.dataStore.getPlayerData(playerID);
                playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + adjustment);
                this.dataStore.savePlayerData(playerID, playerData);
                builder.append(onlinePlayer.getName()).append(' ');
            }

            GriefPrevention.sendMessage(
                player,
                TextMode.Success,
                Messages.AdjustBlocksAllSuccess,
                String.valueOf(adjustment)
            );
            GriefPrevention.AddLogEntry(
                "Adjusted all " + players.size() + "players' bonus claim blocks by " + adjustment + ".  " + builder,
                CustomLogEntryTypes.AdminActivity
            );

            return true;
        }
        // setaccruedclaimblocks <player> <amount>
        else if (cmd.getName().equalsIgnoreCase("setaccruedclaimblocks")) {
            // requires exactly two parameters, the other player's name and the new amount
            if (args.length != 2) return false;

            // parse the adjustment amount
            int newAmount;
            try {
                newAmount = Integer.parseInt(args[1]);
            } catch (NumberFormatException numberFormatException) {
                return false; // causes usage to be displayed
            }

            // find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);

            if (targetPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            // set player's blocks
            PlayerData playerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
            playerData.setAccruedClaimBlocks(newAmount);
            this.dataStore.savePlayerData(targetPlayer.getUniqueId(), playerData);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.SetClaimBlocksSuccess);
            if (player != null) GriefPrevention.AddLogEntry(
                player.getName() + " set " + targetPlayer.getName() + "'s accrued claim blocks to " + newAmount + ".",
                CustomLogEntryTypes.AdminActivity
            );

            return true;
        }
        // givepet
        else if (cmd.getName().equalsIgnoreCase("givepet") && player != null) {
            if (!player.hasPermission("griefprevention.givepet")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            //requires one parameter
            if (args.length < 1) return false;

            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

            //special case: cancellation
            if (args[0].equalsIgnoreCase("cancel")) {
                playerData.petGiveawayRecipient = null;
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.PetTransferCancellation);
                return true;
            }

            //find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (
                targetPlayer == null ||
                (!targetPlayer.isOnline() && !targetPlayer.hasPlayedBefore()) ||
                targetPlayer.getName() == null
            ) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            //remember the player's ID for later pet transfer
            playerData.petGiveawayRecipient = targetPlayer;

            //send instructions
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ReadyToTransferPet);

            return true;
        }
        // trapped
        else if (cmd.getName().equalsIgnoreCase("trapped") && player != null) {
            // FEATURE: empower players who get "stuck" in an area where they don't have
            // permission to build to save themselves

            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), false, playerData.lastClaim);

            // if another /trapped is pending, ignore this slash command
            if (playerData.pendingTrapped) {
                return true;
            }

            // if the player isn't in a claim or has permission to build, tell him to man up
            if (claim == null || claim.checkPermission(player, ClaimPermission.Build, null) == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotTrappedHere);
                return true;
            }

            //rescue destination may be set by GPFlags or other plugin, ask to find out
            SaveTrappedPlayerEvent event = new SaveTrappedPlayerEvent(player, claim);
            Bukkit.getPluginManager().callEvent(event);

            // if the player is in the nether or end, he's screwed (there's no way to
            // programmatically find a safe place for him)
            if (player.getWorld().getEnvironment() != Environment.NORMAL && event.getDestination() == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrappedWontWorkHere);
                return true;
            }

            // if the player is in an administrative claim and AllowTrappedInAdminClaims is
            // false, he should contact an admin
            if (
                !GriefPrevention.instance.config_claims_allowTrappedInAdminClaims &&
                claim.isAdminClaim() &&
                event.getDestination() == null
            ) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrappedWontWorkHere);
                return true;
            }
            // send instructions
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.RescuePending);

            // create a task to rescue this player in a little while
            PlayerRescueTask task = new PlayerRescueTask(player, player.getLocation(), event.getDestination());
            SchedulerUtil.runLaterEntity(this, player, task::run, 200L); // 20L ~ 1 second

            return true;
        } else if (cmd.getName().equalsIgnoreCase("softmute")) {
            // requires one parameter
            if (args.length != 1) return false;

            // find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            // toggle mute for player
            boolean isMuted = this.dataStore.toggleSoftMute(targetPlayer.getUniqueId());
            if (isMuted) {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.SoftMuted, targetPlayer.getName());
                String executorName = "console";
                if (player != null) {
                    executorName = player.getName();
                }

                GriefPrevention.AddLogEntry(
                    executorName + " muted " + targetPlayer.getName() + ".",
                    CustomLogEntryTypes.AdminActivity,
                    true
                );
            } else {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.UnSoftMuted, targetPlayer.getName());
            }

            return true;
        } else if (cmd.getName().equalsIgnoreCase("gpreload")) {
            this.loadConfig();
            this.dataStore.loadMessages();
            this.reloadCommandAliases();
            playerEventHandler.reload();
            if (player != null) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Success,
                    "Configuration updated.  If you have updated your Grief Prevention JAR, you still need to /reload or reboot your server."
                );
            } else {
                GriefPrevention.AddLogEntry(
                    "Configuration updated.  If you have updated your Grief Prevention JAR, you still need to /reload or reboot your server."
                );
            }

            return true;
        } else if (cmd.getName().equalsIgnoreCase("gpdebug")) {
            if (args.length > 1) {
                return false;
            }

            boolean enabled;
            if (args.length == 0) {
                enabled = !this.config_logs_debugEnabled;
            } else if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("true")) {
                enabled = true;
            } else if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("false")) {
                enabled = false;
            } else {
                return false;
            }

            boolean saved = this.setDebugLoggingEnabled(enabled);
            String message = "Debug logging is now " + (enabled ? "enabled" : "disabled") + ".";
            if (!saved) {
                message += " The in-memory setting was changed, but config.yml could not be saved.";
            }
            sender.sendMessage((saved ? TextMode.Success : TextMode.Err) + message);

            return true;
        }
        // ignoreplayer
        else if (cmd.getName().equalsIgnoreCase("ignoreplayer") && player != null) {
            if (!player.hasPermission("griefprevention.ignore")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            // requires target player name
            if (args.length < 1) return false;

            // validate target player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            this.setIgnoreStatus(player, targetPlayer, IgnoreMode.StandardIgnore);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.IgnoreConfirmation);

            return true;
        }
        // unignoreplayer
        else if (cmd.getName().equalsIgnoreCase("unignoreplayer") && player != null) {
            if (!player.hasPermission("griefprevention.ignore")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            // requires target player name
            if (args.length < 1) return false;

            // validate target player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Boolean ignoreStatus = playerData.ignoredPlayers.get(targetPlayer.getUniqueId());
            if (ignoreStatus == null || ignoreStatus == true) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotIgnoringPlayer);
                return true;
            }

            this.setIgnoreStatus(player, targetPlayer, IgnoreMode.None);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.UnIgnoreConfirmation);

            return true;
        }
        // ignoredplayerlist
        else if (cmd.getName().equalsIgnoreCase("ignoredplayerlist") && player != null) {
            if (!player.hasPermission("griefprevention.ignore")) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                return true;
            }
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            StringBuilder builder = new StringBuilder();
            for (Entry<UUID, Boolean> entry : playerData.ignoredPlayers.entrySet()) {
                if (entry.getValue() != null) {
                    // if not an admin ignore, add it to the list
                    if (!entry.getValue()) {
                        builder.append(GriefPrevention.lookupPlayerName(entry.getKey()));
                        builder.append(" ");
                    }
                }
            }

            String list = builder.toString().trim();
            if (list.isEmpty()) {
                GriefPrevention.sendMessage(player, TextMode.Info, Messages.NotIgnoringAnyone);
            } else {
                GriefPrevention.sendMessage(player, TextMode.Info, list);
            }

            return true;
        }
        // separateplayers
        else if (cmd.getName().equalsIgnoreCase("separate")) {
            // requires two player names
            if (args.length < 2) return false;

            // validate target players
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            OfflinePlayer targetPlayer2 = this.resolvePlayerByName(args[1]);
            if (targetPlayer2 == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            this.setIgnoreStatus(targetPlayer, targetPlayer2, IgnoreMode.AdminIgnore);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.SeparateConfirmation);

            return true;
        }
        // unseparateplayers
        else if (cmd.getName().equalsIgnoreCase("unseparate")) {
            // requires two player names
            if (args.length < 2) return false;

            // validate target players
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if (targetPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            OfflinePlayer targetPlayer2 = this.resolvePlayerByName(args[1]);
            if (targetPlayer2 == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }

            this.setIgnoreStatus(targetPlayer, targetPlayer2, IgnoreMode.None);
            this.setIgnoreStatus(targetPlayer2, targetPlayer, IgnoreMode.None);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.UnSeparateConfirmation);

            return true;
        }
        return false;
    }

    void setIgnoreStatus(OfflinePlayer ignorer, OfflinePlayer ignoree, IgnoreMode mode) {
        PlayerData playerData = this.dataStore.getPlayerData(ignorer.getUniqueId());
        if (mode == IgnoreMode.None) {
            playerData.ignoredPlayers.remove(ignoree.getUniqueId());
        } else {
            playerData.ignoredPlayers.put(ignoree.getUniqueId(), mode == IgnoreMode.StandardIgnore ? false : true);
        }

        playerData.ignoreListChanged = true;
        if (!ignorer.isOnline()) {
            this.dataStore.savePlayerData(ignorer.getUniqueId(), playerData);
            this.dataStore.clearCachedPlayerData(ignorer.getUniqueId());
        }
    }

    public enum IgnoreMode {
        None,
        StandardIgnore,
        AdminIgnore,
    }

    private String trustEntryToPlayerName(String entry) {
        if (entry.startsWith("[") || entry.equals("public")) {
            return entry;
        } else {
            return GriefPrevention.lookupPlayerName(entry);
        }
    }

    public static String getfriendlyLocationString(Location location) {
        return location.getWorld().getName() + ": x" + location.getBlockX() + ", z" + location.getBlockZ();
    }

    public boolean abandonClaimHandler(Player player, boolean deleteTopLevelClaim) {
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        // Prefer claim selected via shovel corner (selection session) when set
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

        // if no claim here, nothing to abandon
        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.BlockNotClaimed);
            return true;
        }

        // verify ownership
        if (claim.checkPermission(player, ClaimPermission.Edit, null) != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotYourClaim);
            return true;
        }

        // an administrative subdivision anywhere below this claim pins the whole claim in place
        if (
            !player.hasPermission("griefprevention.adminclaims") &&
            (claim.isAdminSubdivision() || claim.containsAdminSubdivision())
        ) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.AbandonBlockedAdminSubdivision);
            return true;
        }

        // warn if has children and we're not explicitly deleting a top level claim
        if (!claim.children.isEmpty() && !deleteTopLevelClaim) {
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.DeleteTopLevelClaim);
            return true;
        } else {
            // delete it
            claim.removeSurfaceFluids(null);
            if (!this.dataStore.deleteClaimWithResult(claim, true, false)) {
                return true;
            }

            // if in a creative mode world, restore the claim area
            if (this.creativeRulesApply(claim.getLesserBoundaryCorner())) {
                GriefPrevention.AddLogEntry(
                    player.getName() +
                        " abandoned a claim @ " +
                        GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner())
                );
                GriefPrevention.sendMessage(player, TextMode.Warn, Messages.UnclaimCleanupWarning);
                this.restoreClaim(claim, 20L * 60 * 2);
            }

            // clear selection session if we were deleting the selected claim
            if (playerData.claimResizing == claim) {
                playerData.claimResizing = null;
                playerData.lastShovelLocation = null;
            }

            // also reset claim selection and shaped editor state
            playerData.claimSelectionActive = false;
            playerData.setClaimEditorSession(null);

            // adjust claim blocks when abandoning a top level claim
            if (
                this.config_claims_abandonReturnRatio != 1.0D &&
                claim.parent == null &&
                claim.ownerID != null &&
                claim.ownerID.equals(playerData.playerID)
            ) {
                playerData.setAccruedClaimBlocks(
                    playerData.getAccruedClaimBlocks() -
                        (int) Math.ceil(claim.getArea() * (1 - this.config_claims_abandonReturnRatio))
                );
            }

            // tell the player how many claim blocks he has left
            int remainingBlocks = playerData.getRemainingClaimBlocks();
            GriefPrevention.sendMessage(
                player,
                TextMode.Success,
                Messages.AbandonSuccess,
                String.valueOf(remainingBlocks)
            );

            // revert any current visualization
            playerData.setVisibleBoundaries(null);

            playerData.warnedAboutMajorDeletion = false;
        }

        return true;
    }

    private @Nullable Claim getSelectedOrCurrentClaim(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        boolean ignoreHeight
    ) {
        if (
            this.config_claims_useClaimSelectSessions &&
            playerData.claimSelectionActive &&
            playerData.claimResizing != null &&
            playerData.claimResizing.inDataStore
        ) {
            return playerData.claimResizing;
        }

        return this.dataStore.getClaimAt(player.getLocation(), ignoreHeight, playerData.lastClaim);
    }

    public @Nullable Claim getSelectedOrCurrentClaim(@NotNull Player player, boolean ignoreHeight) {
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        return this.getSelectedOrCurrentClaim(player, playerData, ignoreHeight);
    }

    /**
     * The claim a player explicitly selected by right-clicking one of its corners with the golden
     * shovel, ignoring whatever claim they happen to be standing in.
     *
     * <p>Commands that must not act on a claim by accident — converting a subdivision to and from
     * administrative ownership, for instance — use this instead of {@link
     * #getSelectedOrCurrentClaim(Player, boolean)}.
     *
     * @param player the player
     * @return the selected claim, or null when nothing is selected
     */
    public @Nullable Claim getSelectedClaim(@NotNull Player player) {
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        if (
            this.config_claims_useClaimSelectSessions &&
            playerData.claimSelectionActive &&
            playerData.claimResizing != null &&
            playerData.claimResizing.inDataStore
        ) {
            return playerData.claimResizing;
        }

        return null;
    }

    /**
     * Propagates trust changes to child claims that inherit permissions.
     *
     * <p>Children are only modified in memory and collected into {@code modifiedClaims}, so
     * that the caller can hand them to the data store in one batch. Saving each child as it
     * is modified rewrites the entire claim tree once per subdivision on flat file storage,
     * which stalls the calling (region) thread for seconds on claims with many subdivisions.
     *
     * @param parentClaim     The parent claim whose trust changes should be
     *                        propagated
     * @param identifier      The player/permission identifier to add/remove trust
     *                        for
     * @param permissionLevel The permission level, or null for manager permissions
     * @param isAddingTrust   true if adding trust, false if removing trust
     * @param modifiedClaims  Collects every child claim modified here, for the caller to save
     */
    private void propagateTrustToChildren(
        Claim parentClaim,
        String identifier,
        ClaimPermission permissionLevel,
        boolean isAddingTrust,
        List<Claim> modifiedClaims
    ) {
        if (parentClaim.children.isEmpty()) return;

        for (Claim childClaim : parentClaim.children) {
            // Administrative subdivisions are trusted by staff only, so parent-level trust changes
            // must not reach into them or anything nested below them.
            if (childClaim.isAdminSubdivision()) continue;

            // Only propagate to children that inherit permissions (inheritNothing = false)
            if (!childClaim.getSubclaimRestrictions()) {
                if (isAddingTrust) {
                    // Add trust to child claim
                    childClaim.setPermission(identifier, permissionLevel);
                } else {
                    // Remove trust from child claim, but only if it's explicitly set there.
                    // Works for both UUID strings and [permission.node] identifiers.
                    if (childClaim.getPermission(identifier.toLowerCase()) != null) {
                        childClaim.dropPermission(identifier);
                    }
                }

                modifiedClaims.add(childClaim);

                // Recursively propagate to grandchildren
                propagateTrustToChildren(childClaim, identifier, permissionLevel, isAddingTrust, modifiedClaims);
            }
        }
    }

    // helper method keeps the trust commands consistent and eliminates duplicate
    // code
    public void handleTrustCommand(
        Player player,
        ClaimPermission permissionLevel,
        String recipientName,
        boolean clearPermissions
    ) {
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        // determine which claim the player is standing in (use false to respect 3D
        // subclaim boundaries)
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

        // validate player or group argument
        String permissionIdentifier = null;
        OfflinePlayer otherPlayer = null;
        UUID recipientID = null;
        boolean hasBracketSyntax = recipientName.startsWith("[") || recipientName.endsWith("]");
        if (hasBracketSyntax) {
            permissionIdentifier = ClaimTrustIdentifier.fromPermissionTarget(recipientName);
            if (permissionIdentifier == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.InvalidPermissionID);
                return;
            }
        } else if (!recipientName.equalsIgnoreCase("public")) {
            otherPlayer = this.resolvePlayerByName(recipientName);
            if (otherPlayer == null) {
                permissionIdentifier = ClaimTrustIdentifier.fromPermissionTarget(recipientName);
                if (!clearPermissions && permissionIdentifier == null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                    return;
                }
            }

            if (otherPlayer != null) {
                String resolvedName = otherPlayer.getName();
                if (resolvedName != null) recipientName = resolvedName;
                recipientID = otherPlayer.getUniqueId();
            }
        }

        if (permissionIdentifier != null &&
            !player.hasPermission(ClaimTrustCommandPermissions.PERMISSION_TRUST)) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
            return;
        }

        if (otherPlayer == null && permissionIdentifier == null) {
            recipientName = "public";
        }

        List<Claim> targetClaims = new ArrayList<>();
        if (claim == null) {
            targetClaims.addAll(playerData.getClaims());
        } else {
            // Check permission on the claim where trust will be applied
            if (claim.checkPermission(player, ClaimPermission.Manage, null) != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoManageTrust, claim.getOwnerName());
                return;
            }
            targetClaims.add(claim);
        }

        String identifierToAdd = recipientName;
        if (permissionIdentifier != null) {
            identifierToAdd = permissionIdentifier;
            // replace recipientName as well so the success message clearly signals a
            // permission
            recipientName = identifierToAdd;
        } else if (recipientID != null) {
            identifierToAdd = recipientID.toString();
        }

        // calling the event
        TrustChangedEvent event = new TrustChangedEvent(
            player,
            targetClaims,
            permissionLevel,
            true,
            identifierToAdd
        );
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return;
        }

        // apply changes
        for (Claim currentClaim : event.getClaims()) {
            currentClaim.setPermission(identifierToAdd, permissionLevel);

            // Propagate trust changes to child claims that inherit permissions
            List<Claim> modifiedClaims = new ArrayList<>();
            modifiedClaims.add(currentClaim);
            propagateTrustToChildren(currentClaim, identifierToAdd, permissionLevel, true, modifiedClaims);

            // save changes in one batch so subdivisions aren't written one at a time
            this.dataStore.saveClaims(modifiedClaims);
        }

        // notify player
        if (recipientName.equals("public")) recipientName = this.dataStore.getMessage(Messages.CollectivePublic);
        String permissionDescription;
        if (permissionLevel == ClaimPermission.Manage) {
            permissionDescription = this.dataStore.getMessage(Messages.ManagePermission);
        } else if (permissionLevel == ClaimPermission.Build) {
            permissionDescription = this.dataStore.getMessage(Messages.BuildPermission);
        } else if (permissionLevel == ClaimPermission.Access) {
            permissionDescription = this.dataStore.getMessage(Messages.AccessPermission);
        } else if (permissionLevel == ClaimPermission.PVP) {
            permissionDescription = this.dataStore.getMessage(Messages.PvPTrustPermission);
        } else if (permissionLevel == ClaimPermission.PVE) {
            permissionDescription = this.dataStore.getMessage(Messages.PvETrustPermission);
        } else {
            permissionDescription = this.dataStore.getMessage(Messages.ContainersPermission);
        }

        String location;
        if (claim == null) {
            location = this.dataStore.getMessage(Messages.LocationAllClaims);
        } else {
            location = this.dataStore.getMessage(Messages.LocationCurrentClaim);
        }
        GriefPrevention.sendMessage(
            player,
            TextMode.Success,
            Messages.GrantPermissionConfirmation,
            recipientName,
            permissionDescription,
            location
        );
    }

    // helper method to resolve a player by name
    public static ConcurrentHashMap<String, UUID> playerNameToIDMap = new ConcurrentHashMap<>();

    // thread to build the above cache
    private class CacheOfflinePlayerNamesThread extends Thread {

        private final OfflinePlayer[] offlinePlayers;
        private final ConcurrentHashMap<String, UUID> playerNameToIDMap;

        CacheOfflinePlayerNamesThread(
            OfflinePlayer[] offlinePlayers,
            ConcurrentHashMap<String, UUID> playerNameToIDMap
        ) {
            this.offlinePlayers = offlinePlayers;
            this.playerNameToIDMap = playerNameToIDMap;
        }

        public void run() {
            long now = System.currentTimeMillis();
            final long millisecondsPerDay = 1000 * 60 * 60 * 24;
            for (OfflinePlayer player : offlinePlayers) {
                try {
                    UUID playerID = player.getUniqueId();
                    if (playerID == null) continue;
                    long lastSeen = player.getLastPlayed();

                    // if the player has been seen in the last 90 days, cache his name/UUID pair
                    long diff = now - lastSeen;
                    long daysDiff = diff / millisecondsPerDay;
                    if (daysDiff <= config_advanced_offlineplayer_cache_days) {
                        String playerName = player.getName();
                        if (playerName == null) continue;
                        this.playerNameToIDMap.put(playerName, playerID);
                        this.playerNameToIDMap.put(playerName.toLowerCase(), playerID);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public OfflinePlayer resolvePlayerByName(String name) {
        // try online players first
        Player targetPlayer = this.getServer().getPlayerExact(name);
        if (targetPlayer != null) return targetPlayer;

        UUID bestMatchID = null;

        // try exact match first
        bestMatchID = playerNameToIDMap.get(name);

        // if failed, try ignore case
        if (bestMatchID == null) {
            bestMatchID = playerNameToIDMap.get(name.toLowerCase());
        }
        if (bestMatchID == null) {
            try {
                // Try to parse UUID from string.
                bestMatchID = UUID.fromString(name);
            } catch (IllegalArgumentException ignored) {
                // Not a valid UUID string either.
                return null;
            }
        }

        return this.getServer().getOfflinePlayer(bestMatchID);
    }

    private static final Cache<UUID, String> PLAYER_NAME_CACHE = CacheBuilder.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .build();

    // helper method to resolve a player name from the player's UUID
    static @NotNull String lookupPlayerName(@Nullable UUID playerID) {
        // parameter validation
        if (playerID == null) return getDefaultName(null);

        // check the cache
        String cached = PLAYER_NAME_CACHE.getIfPresent(playerID);
        if (cached != null) return cached;

        // If name is not cached, fetch player.
        OfflinePlayer player = GriefPrevention.instance.getServer().getOfflinePlayer(playerID);
        return lookupPlayerName(player);
    }

    /**
     * Converts a duration in milliseconds to a human-readable string.
     *
     * @param durationMs Duration in milliseconds
     * @return Human-readable string (e.g. "2 days, 3 hours, 5 minutes")
     */
    public String getFriendlyTimeString(long durationMs) {
        if (durationMs < 0) return "0 seconds";

        long seconds = durationMs / 1000;
        if (seconds < 60) return seconds + (seconds == 1 ? " second" : " seconds");

        long minutes = seconds / 60;
        if (minutes < 60) return minutes + (minutes == 1 ? " minute" : " minutes");

        long hours = minutes / 60;
        minutes = minutes % 60;
        if (hours < 24) {
            String result = hours + (hours == 1 ? " hour" : " hours");
            if (minutes > 0) {
                result += ", " + minutes + (minutes == 1 ? " minute" : " minutes");
            }
            return result;
        }

        long days = hours / 24;
        hours = hours % 24;
        String result = days + (days == 1 ? " day" : " days");

        if (hours > 0) {
            result += ", " + hours + (hours == 1 ? " hour" : " hours");
        }

        return result;
    }

    static @NotNull String lookupPlayerName(@NotNull AnimalTamer tamer) {
        // If the tamer is not a player, fetch their name directly.
        if (!(tamer instanceof OfflinePlayer)) {
            String name = tamer.getName();
            if (name != null) return name;
            // Fall back to tamer's UUID.
            return getDefaultName(tamer.getUniqueId());
        }
        OfflinePlayer player = (OfflinePlayer) tamer;

        // If the player is online, their name is available immediately.
        if (player instanceof Player) {
            String name = ((Player) player).getName();
            if (name != null) {
                // Store name in cache.
                if (name != null) {
                    PLAYER_NAME_CACHE.put(player.getUniqueId(), name);
                }
                return name;
            }
        }

        // Use cached name if available.
        @SuppressWarnings("null")
        String name = PLAYER_NAME_CACHE.getIfPresent(player.getUniqueId());

        if (name == null) {
            // If they're an existing player, they likely have a name. Load from disk.
            if (player.hasPlayedBefore()) {
                name = player.getName();
            }

            // If no name is available, fall through to default.
            if (name == null) {
                name = getDefaultName(player.getUniqueId());
            }

            // Store name in cache.
            PLAYER_NAME_CACHE.put(player.getUniqueId(), name);
        }

        return name;
    }

    private static @NotNull String getDefaultName(@Nullable UUID playerId) {
        String someone = instance.dataStore.getMessage(Messages.UnknownPlayerName);

        if (someone == null || Compat.isBlank(someone)) {
            someone = "someone";
        }

        if (playerId == null) return someone;

        return someone + " (" + playerId + ")";
    }

    // cache for player name lookups, to save searches of all offline players
    static void cacheUUIDNamePair(UUID playerID, String playerName) {
        // store the reverse mapping
        playerNameToIDMap.put(playerName, playerID);
        playerNameToIDMap.put(playerName.toLowerCase(), playerID);
    }

    // string overload for above helper
    static String lookupPlayerName(String playerID) {
        UUID id;
        try {
            id = UUID.fromString(playerID);
        } catch (IllegalArgumentException ex) {
            GriefPrevention.AddLogEntry("Error: Tried to look up a local player name for invalid UUID: " + playerID);
            return "someone";
        }

        return lookupPlayerName(id);
    }

    public void onDisable() {
        // save data for any online players
        @SuppressWarnings("unchecked")
        Collection<Player> players = (Collection<Player>) this.getServer().getOnlinePlayers();
        for (Player player : players) {
            UUID playerID = player.getUniqueId();
            PlayerData playerData = this.dataStore.getPlayerData(playerID);
            this.dataStore.savePlayerDataSync(playerID, playerData);
        }

        this.dataStore.close();

        // dump any remaining unwritten log entries
        this.customLogger.WriteEntries();

        Bukkit.getLogger().info("GriefPrevention disabled.");
    }

    // called when a player spawns, applies protection for that player if necessary
    public void checkPvpProtectionNeeded(Player player) {
        // if anti spawn camping feature is not enabled, do nothing
        if (!this.config_pvp_protectFreshSpawns) return;

        // if pvp is disabled, do nothing
        if (!pvpRulesApply(player.getWorld())) return;

        // if player is in creative mode, do nothing
        if (player.getGameMode() == GameMode.CREATIVE) return;

        // if the player has the damage any player permission enabled, do nothing
        if (player.hasPermission("griefprevention.nopvpimmunity")) return;

        // check inventory for well, anything
        if (GriefPrevention.isInventoryEmpty(player)) {
            // if empty, apply immunity
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            playerData.pvpImmune = true;

            // inform the player after he finishes respawning
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.PvPImmunityStart, 5L);

            // start a task to re-check this player's inventory every minute until his
            // immunity is gone
            PvPImmunityValidationTask task = new PvPImmunityValidationTask(player);
            SchedulerUtil.runLaterEntity(this, player, task::run, 1200L);
        }
    }

    static boolean isInventoryEmpty(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] armorStacks = inventory.getArmorContents();

        // check armor slots, stop if any items are found
        for (ItemStack armorStack : armorStacks) {
            if (!(armorStack == null || armorStack.getType() == Material.AIR)) return false;
        }

        // check other slots, stop if any items are found
        ItemStack[] generalStacks = inventory.getContents();
        for (ItemStack generalStack : generalStacks) {
            if (!(generalStack == null || generalStack.getType() == Material.AIR)) return false;
        }

        return true;
    }

    // moves a player from the claim he's in to a nearby wilderness location
    public Location ejectPlayer(Player player) {
        // look for a suitable location
        Location candidateLocation = player.getLocation();
        while (true) {
            Claim claim = null;
            claim = GriefPrevention.instance.dataStore.getClaimAt(candidateLocation, false, null);

            // if there's a claim here, keep looking
            if (claim != null) {
                candidateLocation = new Location(
                    claim.lesserBoundaryCorner.getWorld(),
                    claim.lesserBoundaryCorner.getBlockX() - 1,
                    claim.lesserBoundaryCorner.getBlockY(),
                    claim.lesserBoundaryCorner.getBlockZ() - 1
                );
                continue;
            }
            // otherwise find a safe place to teleport the player
            else {
                // find a safe height, a couple of blocks above the surface
                GuaranteeChunkLoaded(candidateLocation);
                Block highestBlock = candidateLocation
                    .getWorld()
                    .getHighestBlockAt(candidateLocation.getBlockX(), candidateLocation.getBlockZ());
                Location destination = new Location(
                    highestBlock.getWorld(),
                    highestBlock.getX(),
                    highestBlock.getY() + 2,
                    highestBlock.getZ()
                );
                // Use Folia-compatible teleport via reflection
                try {
                    // Try teleportAsync first (Paper/Folia 1.19+)
                    player.getClass().getMethod("teleportAsync", Location.class).invoke(player, destination);
                } catch (Exception e) {
                    // Fallback to regular teleport (shouldn't reach here on Folia)
                    player.teleport(destination);
                }
                return destination;
            }
        }
    }

    // ensures a piece of the managed world is loaded into server memory
    // (generates the chunk if necessary)
    private static void GuaranteeChunkLoaded(Location location) {
        Chunk chunk = location.getChunk();
        while (!chunk.isLoaded() || !chunk.load(true));
    }

    public static void sendMessage(@Nullable CommandSender sender, @NotNull TextMode mode, @Nullable String message) {
        if (!hasVisibleMessageContent(message)) return;

        if (sender == null) {
            Bukkit.getLogger().info(mode.getColor() + message);
            GriefPrevention.AddLogEntry(message, CustomLogEntryTypes.Debug, true);
        } else {
            sender.sendMessage(mode.getColor() + message);
        }
    }

    // sends a color-coded message to a player
    public static void sendMessage(
        @Nullable Player player,
        @NotNull ChatColor color,
        @NotNull Messages messageID,
        @NotNull String @NotNull... args
    ) {
        sendMessage(player, color, messageID, 0, args);
    }

    // sends a color-coded message to a player
    public static void sendMessage(
        @Nullable Player player,
        @NotNull ChatColor color,
        @NotNull Messages messageID,
        long delayInTicks,
        @NotNull String @NotNull... args
    ) {
        String message = GriefPrevention.instance.dataStore.getMessage(player, messageID, args);
        if (player != null && GriefPrevention.instance.isActionBarMessage(messageID)) {
            try {
                // prefix the mode's color just like chat does, so action bar messages are colored
                // by default. Any color code in the message itself comes after and still wins.
                player.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(color + message)
                );
            } catch (NoSuchMethodError ignored) {
                sendMessage(player, color, message, delayInTicks);
            }
            return;
        }
        sendMessage(player, color, message, delayInTicks);
    }

    public static void sendMessage(
        @Nullable Player player,
        @NotNull ChatColor color,
        @Nullable String message,
        long delayInTicks
    ) {
        if (!hasVisibleMessageContent(message)) return;

        SendPlayerMessageTask task = new SendPlayerMessageTask(player, color, message);

        // Only schedule if there should be a delay. Otherwise, send the message right
        // now, else the message will appear out of order.
        if (delayInTicks > 0) {
            SchedulerUtil.runLaterEntity(GriefPrevention.instance, player, task::run, delayInTicks);
        } else {
            task.run();
        }
    }

    public boolean isActionBarMessage(@NotNull Messages messageID) {
        String name = messageID.name();
        return this.config_claims_actionBarMessages != null && this.config_claims_actionBarMessages.contains(name);
    }

    public static boolean hasVisibleMessageContent(@Nullable String message) {
        if (message == null) {
            return false;
        }

        String stripped = ChatColor.stripColor(message);
        return stripped != null && !Compat.isBlank(stripped);
    }

    // sends a rate-limited error message to a player (max once per 10 seconds)
    public static void sendRateLimitedErrorMessage(
        @Nullable Player player,
        @NotNull Messages messageID,
        @NotNull String @NotNull... args
    ) {
        sendRateLimitedErrorMessage(player, messageID, 0, args);
    }

    // sends a rate-limited error message to a player with delay (max once per 10
    // seconds)
    public static void sendRateLimitedErrorMessage(
        @Nullable Player player,
        @NotNull Messages messageID,
        long delayInTicks,
        @NotNull String @NotNull... args
    ) {
        if (player == null) {
            // If no player, send normally (for console/logs)
            sendMessage(player, TextMode.Err, messageID, delayInTicks, args);
            return;
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastMessageTime = lastErrorMessageTime.get(playerId);

        // Check if enough time has passed since the last error message
        if (lastMessageTime == null || currentTime - lastMessageTime >= ERROR_MESSAGE_COOLDOWN_MS) {
            // Update the last message time
            lastErrorMessageTime.put(playerId, currentTime);
            // Send the message
            sendMessage(player, TextMode.Err, messageID, delayInTicks, args);
        }
        // If not enough time has passed, silently ignore the message
    }

    // sends a rate-limited error message to a player with direct string (max once
    // per 10 seconds)
    public static void sendRateLimitedErrorMessage(@Nullable Player player, @NotNull String message) {
        sendRateLimitedErrorMessage(player, message, 0);
    }

    // sends a rate-limited error message to a player with direct string and delay
    // (max once per 10 seconds)
    public static void sendRateLimitedErrorMessage(
        @Nullable Player player,
        @NotNull String message,
        long delayInTicks
    ) {
        if (player == null) {
            // If no player, send normally (for console/logs)
            sendMessage(player, TextMode.Err, message, delayInTicks);
            return;
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastMessageTime = lastErrorMessageTime.get(playerId);

        // Check if enough time has passed since the last error message
        if (lastMessageTime == null || currentTime - lastMessageTime >= ERROR_MESSAGE_COOLDOWN_MS) {
            // Update the last message time
            lastErrorMessageTime.put(playerId, currentTime);
            // Send the message
            sendMessage(player, TextMode.Err, message, delayInTicks);
        }
        // If not enough time has passed, silently ignore the message
    }

    // checks whether players can create claims in a world
    public boolean claimsEnabledForWorld(World world) {
        ClaimsMode mode = this.config_claims_worldModes.get(world);
        return mode != null && mode != ClaimsMode.Disabled;
    }

    // determines whether creative anti-grief rules apply at a location
    public boolean creativeRulesApply(@NotNull Location location) {
        if (!this.config_creativeWorldsExist) return false;

        return this.config_claims_worldModes.get(location.getWorld()) == ClaimsMode.Creative;
    }

    /**
     * @deprecated use
     *             {@link ProtectionHelper#checkPermission(Player, Location, ClaimPermission, org.bukkit.event.Event)}
     */
    @Deprecated
    public @Nullable String allowBuild(Player player, Location location) {
        return this.allowBuild(player, location, location.getBlock().getType());
    }

    /**
     * @deprecated use
     *             {@link ProtectionHelper#checkPermission(Player, Location, ClaimPermission, org.bukkit.event.Event)}
     */
    @Deprecated
    public @Nullable String allowBuild(Player player, Location location, Material material) {
        if (!GriefPrevention.instance.claimsEnabledForWorld(location.getWorld())) return null;

        ItemStack placed = CompatUtil.createItemStack(material);

        Block block = location.getBlock();
        Supplier<String> result = ProtectionHelper.checkPermission(
            player,
            location,
            ClaimPermission.Build,
            CompatUtil.createBlockPlaceEvent(block, block.getState(), block, placed, player, true)
        );
        return result == null ? null : result.get();
    }

    /**
     * @deprecated use
     *             {@link ProtectionHelper#checkPermission(Player, Location, ClaimPermission, org.bukkit.event.Event)}
     */
    @Deprecated
    public @Nullable String allowBreak(Player player, Block block, Location location) {
        return this.allowBreak(player, block, location, new BlockBreakEvent(block, player));
    }

    /**
     * @deprecated use
     *             {@link ProtectionHelper#checkPermission(Player, Location, ClaimPermission, org.bukkit.event.Event)}
     */
    @Deprecated
    public @Nullable String allowBreak(
        Player player,
        Material material,
        Location location,
        BlockBreakEvent breakEvent
    ) {
        return this.allowBreak(player, location.getBlock(), location, breakEvent);
    }

    /**
     * @deprecated use
     *             {@link ProtectionHelper#checkPermission(Player, Location, ClaimPermission, org.bukkit.event.Event)}
     */
    @Deprecated
    public @Nullable String allowBreak(Player player, Block block, Location location, BlockBreakEvent breakEvent) {
        Supplier<String> result = ProtectionHelper.checkPermission(player, location, ClaimPermission.Build, breakEvent);
        return result == null ? null : result.get();
    }

    public int getSeaLevel(World world) {
        Integer overrideValue = this.config_seaLevelOverride.get(world.getName());
        if (overrideValue == null || overrideValue == -1) {
            return world.getSeaLevel();
        } else {
            return overrideValue;
        }
    }

    public int getMinY(World world) {
        return this.config_claims_minYOverride.getOrDefault(world.getName(), this.config_claims_minY);
    }

    public boolean containsBlockedIP(String message) {
        message = message.replace("\r\n", "");
        Pattern ipAddressPattern = Pattern.compile("([0-9]{1,3}\\.){3}[0-9]{1,3}");
        Matcher matcher = ipAddressPattern.matcher(message);

        // if it looks like an IP address
        if (matcher.find()) {
            // and it's not in the list of allowed IP addresses
            if (!GriefPrevention.instance.config_spam_allowedIpAddresses.contains(matcher.group())) {
                return true;
            }
        }

        return false;
    }

    public boolean pvpRulesApply(World world) {
        Boolean configSetting = this.config_pvp_specifiedWorlds.get(world);
        if (configSetting != null) return configSetting;
        return world.getPVP();
    }

    public boolean siegeEnabledForWorld(World world) {
        return this.config_siege_enabledWorlds.contains(world);
    }

    public static boolean isNewToServer(Player player) {
        try {
            if (
                player.getStatistic(Statistic.PICKUP, Material.OAK_LOG) > 0 ||
                player.getStatistic(Statistic.PICKUP, Material.SPRUCE_LOG) > 0 ||
                player.getStatistic(Statistic.PICKUP, Material.BIRCH_LOG) > 0 ||
                player.getStatistic(Statistic.PICKUP, Material.JUNGLE_LOG) > 0 ||
                player.getStatistic(Statistic.PICKUP, Material.ACACIA_LOG) > 0 ||
                player.getStatistic(Statistic.PICKUP, Material.DARK_OAK_LOG) > 0
            ) return false;
        } catch (NoSuchFieldError e) {
            // 1.8.8: Statistic.PICKUP doesn't exist, skip this check
        }

        PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
        if (!playerData.getClaims().isEmpty()) return false;

        return true;
    }

    static void banPlayer(Player player, String reason, String source) {
        if (GriefPrevention.instance.config_ban_useCommand) {
            Bukkit.getServer().dispatchCommand(
                Bukkit.getConsoleSender(),
                GriefPrevention.instance.config_ban_commandFormat
                    .replace("%name%", player.getName())
                    .replace("%reason%", reason)
            );
        } else {
            CompatUtil.addBan(player, reason, source);

            // kick
            if (player.isOnline()) {
                player.kickPlayer(reason);
            }
        }
    }

    public ItemStack getItemInHand(Player player, EquipmentSlot hand) {
        if (hand != null && CompatUtil.hasOffHandEquipmentSlot() && hand == EquipmentSlot.OFF_HAND) return player
            .getInventory()
            .getItemInOffHand();
        return CompatUtil.getItemInMainHand(player);
    }

    public boolean claimIsPvPSafeZone(Claim claim) {
        // Per-claim PvP toggle: only applies to the claim type the server enabled it for,
        // mirroring getPvpToggleContext(). When active AND the claim was explicitly toggled,
        // its own setting is authoritative in both directions — OFF protects, and ON overrides
        // any global "no combat in claims" fallback so toggling PvP on actually enables combat.
        // Claims never toggled keep their default pvpEnabled and always fall back to the global
        // settings below (in particular admin claims must not silently enable PvP by default).
        if (claim.parent == null) {
            if (this.config_pvp_toggleCostClaimEnabled) {
                if (claim.pvpToggleSet) {
                    return !claim.pvpEnabled;
                }
            }
        } else {
            if (this.config_pvp_toggleCostSubdivisionEnabled) {
                if (claim.pvpToggleSet) {
                    return !claim.pvpEnabled;
                }
            }
        }

        // Toggle feature disabled for this claim type: fall back to global config settings
        return (
            (claim.isAdminClaim() &&
                claim.parent == null &&
                GriefPrevention.instance.config_pvp_noCombatInAdminLandClaims) ||
            (claim.isAdminClaim() &&
                claim.parent != null &&
                GriefPrevention.instance.config_pvp_noCombatInAdminSubdivisions) ||
            (!claim.isAdminClaim() && GriefPrevention.instance.config_pvp_noCombatInPlayerLandClaims)
        );
    }

    /*
     * protected boolean isPlayerTrappedInPortal(Block block)
     * {
     * Material playerBlock = block.getType();
     * if (playerBlock == Material.PORTAL)
     * return true;
     * //Most blocks you can "stand" inside but cannot pass through (isSolid)
     * usually can be seen through (!isOccluding)
     * //This can cause players to technically be considered not in a portal block,
     * yet in reality is still stuck in the portal animation.
     * if ((!playerBlock.isSolid() || playerBlock.isOccluding())) //If it is _not_
     * such a block,
     * {
     * //Check the block above
     * playerBlock = block.getRelative(BlockFace.UP).getType();
     * if ((!playerBlock.isSolid() || playerBlock.isOccluding()))
     * return false; //player is not stuck
     * }
     * //Check if this block is also adjacent to a portal
     * return block.getRelative(BlockFace.EAST).getType() == Material.PORTAL
     * || block.getRelative(BlockFace.WEST).getType() == Material.PORTAL
     * || block.getRelative(BlockFace.NORTH).getType() == Material.PORTAL
     * || block.getRelative(BlockFace.SOUTH).getType() == Material.PORTAL;
     * }
     *
     * public void rescuePlayerTrappedInPortal(final Player player)
     * {
     * final Location oldLocation = player.getLocation();
     * if (!isPlayerTrappedInPortal(oldLocation.getBlock()))
     * {
     * //Note that he 'escaped' the portal frame
     * instance.portalReturnMap.remove(player.getUniqueId());
     * instance.portalReturnTaskMap.remove(player.getUniqueId());
     * return;
     * }
     *
     * Location rescueLocation = portalReturnMap.get(player.getUniqueId());
     *
     * if (rescueLocation == null)
     * return;
     *
     * //Temporarily store the old location, in case the player wishes to undo the
     * rescue
     * dataStore.getPlayerData(player.getUniqueId()).portalTrappedLocation =
     * oldLocation;
     *
     * player.teleport(rescueLocation);
     * sendMessage(player, TextMode.Info, Messages.RescuedFromPortalTrap);
     * portalReturnMap.remove(player.getUniqueId());
     *
     * new BukkitRunnable()
     * {
     * public void run()
     * {
     * if (oldLocation ==
     * dataStore.getPlayerData(player.getUniqueId()).portalTrappedLocation)
     * dataStore.getPlayerData(player.getUniqueId()).portalTrappedLocation = null;
     * }
     * }.runTaskLater(this, 600L);
     * }
     */

    // Track scheduled "rescues" so we can cancel them if the player happens to
    // teleport elsewhere so we can cancel it.
    ConcurrentHashMap<UUID, TaskHandle> portalReturnTaskMap = new ConcurrentHashMap<>();

    public void startRescueTask(Player player, Location location) {
        // Schedule task to reset player's portal cooldown after 30 seconds (Maximum
        // timeout time for client, in case their network is slow and taking forever to
        // load chunks)
        TaskHandle task = SchedulerUtil.runLaterEntity(
            GriefPrevention.instance,
            player,
            () -> new CheckForPortalTrapTask(player, this, location).run(),
            600L
        );

        // Cancel existing rescue task
        if (portalReturnTaskMap.containsKey(player.getUniqueId())) portalReturnTaskMap
            .put(player.getUniqueId(), task)
            .cancel();
        else portalReturnTaskMap.put(player.getUniqueId(), task);
    }

    // Helper methods for unified command handling

    public boolean handleTrustCommand(CommandSender sender, String[] args) {
        // Extract trust command logic from onCommand
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (args.length != 1) return false;

        this.handleTrustCommand(player, ClaimPermission.Build, args[0], false);
        return true;
    }

    public boolean handleUntrustCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (args.length != 1) return false;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        // determine which claim is selected or being stood in
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

        // determine whether a single player or clearing permissions entirely
        boolean clearPermissions = false;
        OfflinePlayer otherPlayer = null;
        if (args[0].equals("all")) {
            if (claim == null || claim.checkPermission(player, ClaimPermission.Edit, null) == null) {
                clearPermissions = true;
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClearPermsOwnerOnly);
                return true;
            }
        } else {
            // validate player argument or group argument
            if (!args[0].startsWith("[") || !args[0].endsWith("]")) {
                otherPlayer = this.resolvePlayerByName(args[0]);
                if (!clearPermissions && otherPlayer == null && !args[0].equals("public")) {
                    // bracket any permissions - at this point it must be a permission without brackets
                    if (args[0].contains(".")) {
                        args[0] = "[" + args[0] + "]";
                    } else {
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                        return true;
                    }
                }

                // correct to proper casing
                if (otherPlayer != null) args[0] = otherPlayer.getName();
            }
        }

        // if no claim here, apply changes to all player's claims
        if (claim == null) {
            String idToDrop = args[0];
            if (otherPlayer != null) {
                idToDrop = otherPlayer.getUniqueId().toString();
            }

            // calling event
            TrustChangedEvent event = new TrustChangedEvent(player, playerData.getClaims(), null, false, idToDrop);
            Bukkit.getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                return true;
            }

            // dropping permissions
            for (Claim targetClaim : event.getClaims()) {
                claim = targetClaim;

                // if untrusting "all" drop all permissions
                if (clearPermissions) {
                    claim.clearPermissions();
                }
                // otherwise drop individual permissions
                else {
                    claim.dropPermission(idToDrop);

                    // Check if this claim has inherited permissions that need to be explicitly removed
                    if (claim.parent != null && !claim.getSubclaimRestrictions()) {
                        ArrayList<String> parentBuilders = new ArrayList<>();
                        ArrayList<String> parentContainers = new ArrayList<>();
                        ArrayList<String> parentAccessors = new ArrayList<>();
                        ArrayList<String> parentManagers = new ArrayList<>();
                        claim.parent.getPermissions(parentBuilders, parentContainers, parentAccessors, parentManagers);

                        // Check if the player being untrusted is in any of the parent's permission lists
                        String playerIdToCheck = idToDrop.toLowerCase();
                        if (
                            parentManagers.contains(playerIdToCheck) ||
                            parentBuilders.contains(playerIdToCheck) ||
                            parentContainers.contains(playerIdToCheck) ||
                            parentAccessors.contains(playerIdToCheck)
                        ) {
                            // Remove the player from this claim's explicit permissions to override inheritance
                            claim.dropPermission(idToDrop);
                        }
                    }
                }

                // Propagate trust removal to child claims that inherit permissions
                List<Claim> modifiedClaims = new ArrayList<>();
                modifiedClaims.add(claim);
                propagateTrustToChildren(claim, idToDrop, null, false, modifiedClaims);

                // save changes in one batch so subdivisions aren't written one at a time
                this.dataStore.saveClaims(modifiedClaims);
            }

            // confirmation message
            if (!clearPermissions) {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.UntrustIndividualAllClaims, args[0]);
            } else {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.UntrustEveryoneAllClaims);
            }
        }
        // otherwise, apply changes to only this claim
        else if (claim.checkPermission(player, ClaimPermission.Manage, null) != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoManageTrust, claim.getOwnerName());
            return true;
        } else {
            // if clearing all
            if (clearPermissions) {
                // requires owner
                if (claim.checkPermission(player, ClaimPermission.Edit, null) != null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.UntrustAllOwnerOnly);
                    return true;
                }

                // calling the event
                TrustChangedEvent event = new TrustChangedEvent(player, claim, null, false, args[0]);
                Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    return true;
                }

                event.getClaims().forEach(Claim::clearPermissions);
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.ClearPermissionsOneClaim);
            }
            // otherwise individual permission drop
            else {
                String idToDrop = args[0];
                if (otherPlayer != null) {
                    idToDrop = otherPlayer.getUniqueId().toString();
                }
                boolean targetIsManager = claim.isManager(idToDrop);
                if (targetIsManager && claim.checkPermission(player, ClaimPermission.Edit, null) != null) {
                    // only claim owners can untrust managers
                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Err,
                        Messages.ManagersDontUntrustManagers,
                        claim.getOwnerName()
                    );
                    return true;
                } else {
                    // calling the event
                    TrustChangedEvent event = new TrustChangedEvent(player, claim, null, false, idToDrop);
                    Bukkit.getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return true;
                    }

                    // Check if the player being untrusted has inherited permissions from parent
                    ArrayList<String> parentBuilders = new ArrayList<>();
                    ArrayList<String> parentContainers = new ArrayList<>();
                    ArrayList<String> parentAccessors = new ArrayList<>();
                    ArrayList<String> parentManagers = new ArrayList<>();

                    String normalizedIdentifier = Claim.normalizeIdentifier(event.getIdentifier());
                    String normalizedIdToDrop = Claim.normalizeIdentifier(idToDrop);

                    if (claim.parent != null && !claim.getSubclaimRestrictions()) {
                        claim.parent.getPermissions(parentBuilders, parentContainers, parentAccessors, parentManagers);
                    }

                    boolean inheritsManager = parentManagers.contains(normalizedIdToDrop);
                    boolean inheritsBuilder = parentBuilders.contains(normalizedIdToDrop);
                    boolean inheritsContainer = parentContainers.contains(normalizedIdToDrop);
                    boolean inheritsAccessor = parentAccessors.contains(normalizedIdToDrop);

                    if (inheritsManager || inheritsBuilder || inheritsContainer || inheritsAccessor) {
                        event.getClaims().forEach(targetClaim -> {
                            // Record denials to block inherited trust without granting new permissions
                            if (inheritsManager) {
                                targetClaim.denyPermission(normalizedIdToDrop + "#manage");
                            }
                            if (inheritsBuilder) {
                                targetClaim.denyPermission(normalizedIdToDrop + "#build");
                            }
                            if (inheritsContainer) {
                                targetClaim.denyPermission(normalizedIdToDrop + "#inventory");
                            }
                            if (inheritsAccessor) {
                                targetClaim.denyPermission(normalizedIdToDrop + "#access");
                            }

                            // Remove any explicit trust that might still exist
                            targetClaim.dropPermission(normalizedIdentifier);
                        });
                    } else {
                        // Normal case - just drop the explicit permission
                        event.getClaims().forEach(targetClaim -> targetClaim.dropPermission(normalizedIdentifier));
                    }

                    // beautify for output
                    if (args[0].equals("public")) {
                        args[0] = "the public";
                    }

                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Success,
                        Messages.UntrustIndividualSingleClaim,
                        args[0]
                    );
                }
            }

            // save changes
            this.dataStore.saveClaim(claim);
        }

        return true;
    }

    public boolean handleTrustListCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);
        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrustListNoClaim);
            return true;
        }

        Supplier<String> errorMessage = claim.checkPermission(player, ClaimPermission.Manage, null);
        if (errorMessage != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, errorMessage.get());
            return true;
        }

        ArrayList<String> builders = new ArrayList<>();
        ArrayList<String> containers = new ArrayList<>();
        ArrayList<String> accessors = new ArrayList<>();
        ArrayList<String> managers = new ArrayList<>();
        claim.getPermissions(builders, containers, accessors, managers);

        ArrayList<String> inheritedBuilders = new ArrayList<>();
        ArrayList<String> inheritedContainers = new ArrayList<>();
        ArrayList<String> inheritedAccessors = new ArrayList<>();
        ArrayList<String> inheritedManagers = new ArrayList<>();

        boolean canInheritForDisplay =
            claim.parent != null && claim.parent.parent == null && !claim.getSubclaimRestrictions();

        if (canInheritForDisplay) {
            claim.parent.getPermissions(inheritedBuilders, inheritedContainers, inheritedAccessors, inheritedManagers);

            java.util.function.Predicate<String> isDeniedBuilder = id ->
                claim.isPermissionDenied(id, ClaimPermission.Build);
            java.util.function.Predicate<String> isDeniedContainer = id ->
                claim.isPermissionDenied(id, ClaimPermission.Container);
            java.util.function.Predicate<String> isDeniedAccessor = id ->
                claim.isPermissionDenied(id, ClaimPermission.Access);
            java.util.function.Predicate<String> isDeniedManager = id ->
                claim.isPermissionDenied(id, ClaimPermission.Manage);

            inheritedBuilders.removeIf(isDeniedBuilder);
            inheritedContainers.removeIf(isDeniedContainer);
            inheritedAccessors.removeIf(isDeniedAccessor);
            inheritedManagers.removeIf(isDeniedManager);
        }

        GriefPrevention.sendMessage(player, TextMode.Info, Messages.TrustListHeader, claim.getOwnerName());

        StringBuilder permissions = new StringBuilder();
        permissions.append(ChatColor.GOLD).append('>');

        Set<String> allManagers = new HashSet<>(managers);
        allManagers.addAll(inheritedManagers);
        if (!allManagers.isEmpty()) {
            for (String manager : allManagers) permissions.append(this.trustEntryToPlayerName(manager)).append(' ');
        }

        player.sendMessage(permissions.toString());
        permissions = new StringBuilder();
        permissions.append(ChatColor.YELLOW).append('>');

        Set<String> allBuilders = new HashSet<>(builders);
        allBuilders.addAll(inheritedBuilders);
        if (!allBuilders.isEmpty()) {
            for (String builder : allBuilders) permissions.append(this.trustEntryToPlayerName(builder)).append(' ');
        }

        player.sendMessage(permissions.toString());
        permissions = new StringBuilder();
        permissions.append(ChatColor.GREEN).append('>');

        Set<String> allContainers = new HashSet<>(containers);
        allContainers.addAll(inheritedContainers);
        if (!allContainers.isEmpty()) {
            for (String container : allContainers)
                permissions.append(this.trustEntryToPlayerName(container)).append(' ');
        }

        player.sendMessage(permissions.toString());
        permissions = new StringBuilder();
        permissions.append(ChatColor.BLUE).append('>');

        Set<String> allAccessors = new HashSet<>(accessors);
        allAccessors.addAll(inheritedAccessors);
        if (!allAccessors.isEmpty()) {
            for (String accessor : allAccessors) permissions.append(this.trustEntryToPlayerName(accessor)).append(' ');
        }

        player.sendMessage(permissions.toString());

        // Combat trusts are only listed while their features are enabled.
        if (this.config_claims_allowPvPTrust || this.config_claims_allowPvETrust) {
            Set<String> pvp = new HashSet<>(claim.getPvpTrustedIdentifiers());
            Set<String> pve = new HashSet<>(claim.getPveTrustedIdentifiers());

            if (canInheritForDisplay) {
                // Inherited grants show through unless this claim explicitly denies the identifier.
                java.util.function.Predicate<String> notDeniedHere =
                        id -> !claim.isPermissionDenied(id, ClaimPermission.PVP)
                                && !claim.isPermissionDenied(id);
                pvp.addAll(claim.parent.getPvpTrustedIdentifiers().stream().filter(notDeniedHere).collect(Collectors.toSet()));
                pve.addAll(claim.parent.getPveTrustedIdentifiers().stream()
                        .filter(id -> !claim.isPermissionDenied(id, ClaimPermission.PVE)
                                && !claim.isPermissionDenied(id))
                        .collect(Collectors.toSet()));
            }

            if (this.config_claims_allowPvPTrust && !pvp.isEmpty()) {
                permissions = new StringBuilder();
                permissions.append(ChatColor.RED).append('>');
                for (String id : pvp) permissions.append(this.trustEntryToPlayerName(id)).append(' ');
                player.sendMessage(permissions.toString());
            }

            if (this.config_claims_allowPvETrust && !pve.isEmpty()) {
                permissions = new StringBuilder();
                permissions.append(ChatColor.DARK_AQUA).append('>');
                for (String id : pve) permissions.append(this.trustEntryToPlayerName(id)).append(' ');
                player.sendMessage(permissions.toString());
            }
        }

        // Show neighbors
        ArrayList<String> neighbors = claim.getAllNeighbors();
        if (!neighbors.isEmpty()) {
            permissions = new StringBuilder();
            permissions.append(ChatColor.LIGHT_PURPLE).append('>');
            for (String neighbor : neighbors) permissions.append(this.trustEntryToPlayerName(neighbor)).append(' ');
            player.sendMessage(permissions.toString());
        }

        player.sendMessage(
            ChatColor.GOLD +
                this.dataStore.getMessage(Messages.Manage) +
                " " +
                ChatColor.YELLOW +
                this.dataStore.getMessage(Messages.Build) +
                " " +
                ChatColor.GREEN +
                this.dataStore.getMessage(Messages.Containers) +
                " " +
                ChatColor.BLUE +
                this.dataStore.getMessage(Messages.Access) +
                (this.config_claims_allowPvPTrust ? " " + ChatColor.RED + this.dataStore.getMessage(Messages.PvPTrustPermission) : "") +
                (this.config_claims_allowPvETrust ? " " + ChatColor.DARK_AQUA + this.dataStore.getMessage(Messages.PvETrustPermission) : "") +
                (neighbors.isEmpty() ? "" : " " + ChatColor.LIGHT_PURPLE + this.dataStore.getMessage(Messages.Neighbor))
        );

        if (claim.getSubclaimRestrictions()) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.HasSubclaimRestriction);
        }

        return true;
    }

    public boolean handleClaimsListCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;
        if (!player.hasPermission("griefprevention.claimslist")) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
            return true;
        }

        OfflinePlayer otherPlayer = player;
        if (args.length > 0 && player.hasPermission("griefprevention.claimslistother")) {
            otherPlayer = this.resolvePlayerByName(args[0]);
            if (otherPlayer == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
        }

        PlayerData playerData = this.dataStore.getPlayerData(otherPlayer.getUniqueId());
        Vector<Claim> claims = playerData.getClaims();

        GriefPrevention.sendMessage(
            player,
            TextMode.Instr,
            Messages.StartBlockMath,
            String.valueOf(playerData.getAccruedClaimBlocks()),
            String.valueOf(
                playerData.getBonusClaimBlocks() + this.dataStore.getGroupBonusBlocks(otherPlayer.getUniqueId())
            ),
            String.valueOf(
                playerData.getAccruedClaimBlocks() +
                    playerData.getBonusClaimBlocks() +
                    this.dataStore.getGroupBonusBlocks(otherPlayer.getUniqueId())
            )
        );

        if (claims.size() > 0) {
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimsListHeader);
            for (int i = 0; i < claims.size(); i++) {
                Claim claim = claims.get(i);
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    getfriendlyLocationString(claim.getLesserBoundaryCorner()) +
                        this.dataStore.getMessage(Messages.ContinueBlockMath, String.valueOf(claim.getArea()))
                );
            }
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                Messages.EndBlockMath,
                String.valueOf(playerData.getRemainingClaimBlocks())
            );
        }

        if (!otherPlayer.isOnline()) {
            this.dataStore.clearCachedPlayerData(otherPlayer.getUniqueId());
        }

        return true;
    }

    public boolean handleModeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        // No args = standalone /basicclaims command - set basic mode directly
        if (args.length == 0) {
            playerData.shovelMode = ShovelMode.Basic;
            playerData.claimSubdividing = null;
            playerData.claimResizing = null;
            playerData.lastShovelLocation = null;
            playerData.setClaimEditorSession(null);
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.BasicClaimsMode);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "basic":
                playerData.shovelMode = ShovelMode.Basic;
                playerData.claimSubdividing = null;
                playerData.claimResizing = null;
                playerData.lastShovelLocation = null;
                playerData.setClaimEditorSession(null);
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.BasicClaimsMode);
                break;
            case "shaped":
                if (!this.config_claims_allowShapedClaims) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedClaimsDisabled);
                    return true;
                }
                if (
                    !player.hasPermission("griefprevention.shapedclaims") ||
                    !player.hasPermission("griefprevention.claims")
                ) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                    return true;
                }
                playerData.shovelMode = ShovelMode.Shaped;
                playerData.claimSubdividing = null;
                playerData.claimResizing = null;
                playerData.lastShovelLocation = null;
                playerData.setClaimEditorSession(null);
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ShapedClaimsMode);
                break;
            case "shapedsubdivide":
            case "shapedsub":
                if (!this.config_claims_allowShapedSubClaims) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubClaimsDisabled);
                    return true;
                }
                if (
                    !player.hasPermission("griefprevention.shapedsubdivideclaims") ||
                    !player.hasPermission("griefprevention.claims")
                ) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                    return true;
                }
                this.enterShapedSubdivideMode(player);
                break;
            case "2d":
                playerData.shovelMode = ShovelMode.Subdivide;
                playerData.claimSubdividing = null;
                playerData.setClaimEditorSession(null);
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionMode);
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    Messages.SubdivisionVideo2,
                    DataStore.SUBDIVISION_VIDEO_URL
                );
                break;
            case "3d":
                if (!this.config_claims_allow3DSubdivisions) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.Subdivisions3DDisabled);
                    return true;
                }
                if (!player.hasPermission("griefprevention.3dsubdivideclaims")) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
                    return true;
                }
                playerData.shovelMode = ShovelMode.Subdivide3D;
                playerData.claimSubdividing = null;
                playerData.setClaimEditorSession(null);
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionMode3D);
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    Messages.SubdivisionVideo2,
                    DataStore.SUBDIVISION_VIDEO_URL
                );
                break;
            default:
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CommandInvalidMode);
                return true;
        }

        return true;
    }

    /**
     * Puts a player into shaped subdivision mode and shows the claims they can subdivide, so the
     * boundaries they have to stay inside are visible before the first corner goes down.
     */
    private void enterShapedSubdivideMode(@NotNull Player player) {
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        playerData.shovelMode = ShovelMode.ShapedSubdivide;
        playerData.claimSubdividing = null;
        playerData.claimResizing = null;
        playerData.claimMerging = null;
        playerData.mergeEdgeIndex = null;
        playerData.mergeSecondEdgeIndex = null;
        playerData.mergeFirstDepthPoint = null;
        playerData.mergeSecondDepthPoint = null;
        playerData.lastShovelLocation = null;
        playerData.setClaimEditorSession(null);

        Set<Claim> claims = this.dataStore.getNearbyClaims(player.getLocation());
        if (!claims.isEmpty()) {
            BoundaryVisualization.mergeNearbyClaims(player, claims);
        }
        GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ShapedSubdivisionMode);
    }

    public boolean handleRestrictSubclaimCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, true);

        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.StandInSubclaim);
            return true;
        }

        // If in a main claim (no parent), toggle the flag for future subdivisions
        if (claim.parent == null) {
            Supplier<String> noManageReason = claim.checkPermission(player, ClaimPermission.Manage, null);
            if (
                !playerData.ignoreClaims &&
                (claim.isAdminClaim() ? !player.hasPermission("griefprevention.adminclaims") : noManageReason != null)
            ) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Err,
                    Messages.OnlyOwnersModifyClaims,
                    claim.getOwnerName()
                );
                return true;
            }

            if (claim.getInheritNothingForNewSubdivisions()) {
                claim.setInheritNothingForNewSubdivisions(false);
                // Also unrestrict all existing subdivisions
                for (Claim child : claim.children) {
                    child.setSubclaimRestrictions(false);
                }
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.MainClaimSubdivisionInheritEnabled);
            } else {
                claim.setInheritNothingForNewSubdivisions(true);
                // Also restrict all existing subdivisions
                for (Claim child : claim.children) {
                    if (!child.getSubclaimRestrictions()) {
                        removeInheritedPermissions(child);
                    }
                    child.setSubclaimRestrictions(true);
                }
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.MainClaimSubdivisionInheritDisabled);
            }
            this.dataStore.saveClaim(claim);
            return true;
        }

        // Trust inheritance in an administrative subdivision is staff's call, not the surrounding
        // claim owner's.
        if (claim.isAdminSubdivision() && !player.hasPermission("griefprevention.adminclaims")) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.AdminSubdivisionRestricted);
            return true;
        }

        // Original logic for subdivisions
        if (claim.getSubclaimRestrictions()) {
            claim.setSubclaimRestrictions(false);
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.SubclaimUnrestricted);
        } else {
            removeInheritedPermissions(claim);
            claim.setSubclaimRestrictions(true);
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.SubclaimRestricted);
        }

        this.dataStore.saveClaim(claim);
        return true;
    }

    public boolean handleNeighborTrustCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (args.length != 1) return false;

        // Determine target player
        OfflinePlayer otherPlayer = this.resolvePlayerByName(args[0]);
        if (otherPlayer == null && !args[0].equals("public")) {
            if (args[0].contains(".")) {
                args[0] = "[" + args[0] + "]";
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

        if (claim == null) {
            // Apply to all claims
            String identifierToAdd = otherPlayer != null ? otherPlayer.getUniqueId().toString() : args[0];
            for (Claim targetClaim : playerData.getClaims()) {
                targetClaim.addNeighbor(identifierToAdd);
                this.dataStore.saveClaim(targetClaim);
            }
            if (otherPlayer != null) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Success,
                    Messages.NeighborTrustGrantedAll,
                    otherPlayer.getName()
                );
            } else {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.NeighborTrustGrantedAll, args[0]);
            }
        } else {
            // Check permission
            if (claim.checkPermission(player, ClaimPermission.Manage, null) != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoManageTrust, claim.getOwnerName());
                return true;
            }

            String identifierToAdd = otherPlayer != null ? otherPlayer.getUniqueId().toString() : args[0];
            claim.addNeighbor(identifierToAdd);
            this.dataStore.saveClaim(claim);
            if (otherPlayer != null) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Success,
                    Messages.NeighborTrustGranted,
                    otherPlayer.getName()
                );
            } else {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.NeighborTrustGranted, args[0]);
            }
        }

        return true;
    }

    public boolean handleNeighborUntrustCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (args.length != 1) return false;

        boolean clearAll = args[0].equals("all");
        OfflinePlayer otherPlayer = null;
        String idToDrop = null;

        if (!clearAll) {
            otherPlayer = this.resolvePlayerByName(args[0]);
            if (otherPlayer != null) {
                idToDrop = otherPlayer.getUniqueId().toString();
            } else {
                idToDrop = args[0];
            }
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

        if (claim == null) {
            // Apply to all claims - only remove manual neighbors, not auto-neighbors
            for (Claim targetClaim : playerData.getClaims()) {
                if (clearAll) {
                    targetClaim.neighbors.clear();
                } else {
                    targetClaim.removeNeighbor(idToDrop);
                }
                this.dataStore.saveClaim(targetClaim);
            }
            if (clearAll) {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.UntrustEveryoneAllClaims);
            } else if (otherPlayer != null) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Success,
                    Messages.NeighborTrustRevokedAll,
                    otherPlayer.getName()
                );
            } else {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.NeighborTrustRevokedAll, idToDrop);
            }
        } else {
            // Check permission
            if (claim.checkPermission(player, ClaimPermission.Manage, null) != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoManageTrust, claim.getOwnerName());
                return true;
            }

            if (clearAll) {
                claim.neighbors.clear();
                this.dataStore.saveClaim(claim);
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.ClearPermissionsOneClaim);
            } else {
                // Don't allow removing auto-neighbors via /untrust
                if (claim.isAutoNeighbor(idToDrop)) {
                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Err,
                        "That player has auto-granted neighbor trust from nearby claims. It will be removed when the nearby claim is abandoned."
                    );
                    return true;
                }
                claim.removeNeighbor(idToDrop);
                this.dataStore.saveClaim(claim);
                if (otherPlayer != null) {
                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Success,
                        Messages.NeighborTrustRevoked,
                        otherPlayer.getName()
                    );
                } else {
                    GriefPrevention.sendMessage(player, TextMode.Success, Messages.NeighborTrustRevoked, idToDrop);
                }
            }
        }

        return true;
    }

    public boolean handleCheckClaimDistanceCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        int minDistance = this.config_claims_minimumDistance;
        GriefPrevention.sendMessage(player, TextMode.Info, Messages.MinimumDistance, String.valueOf(minDistance));

        // Find nearby claims and show distances, visualize them
        Location loc = player.getLocation();
        int found = 0;
        for (Claim claim : this.dataStore.claims) {
            if (!claim.inDataStore) continue;
            if (claim.parent != null) continue;
            if (claim.getOwnerID() != null && claim.getOwnerID().equals(player.getUniqueId())) continue;
            if (!claim.getLesserBoundaryCorner().getWorld().equals(loc.getWorld())) continue;

            if (claim.isNear(loc, minDistance)) {
                int distance = getClaimDistance(loc, claim);
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Info,
                    "  " +
                        claim.getOwnerName() +
                        "'s claim at " +
                        getfriendlyLocationString(claim.getLesserBoundaryCorner()) +
                        " - distance: " +
                        distance
                );
                // Visualize the nearby claim boundary as conflict zone
                BoundaryVisualization.visualizeClaim(player, claim, VisualizationType.CONFLICT_ZONE);
                found++;
            }
        }

        GriefPrevention.sendMessage(player, TextMode.Info, Messages.NearbyClaims, String.valueOf(found));

        return true;
    }

    public boolean handleToggleClaimDistanceCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.StandInClaimToResize);
            return true;
        }

        // Toggle per-claim allowAllNeighbors (requires owner or manager)
        Supplier<String> noManage = claim.checkPermission(player, ClaimPermission.Manage, null);
        if (noManage != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, noManage.get());
            return true;
        }
        claim.allowAllNeighbors = !claim.allowAllNeighbors;
        this.dataStore.saveClaim(claim);
        if (claim.allowAllNeighbors) {
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.NeighborBypassEnabled);
        } else {
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.NeighborBypassDisabled);
        }

        return true;
    }

    // Calculate the minimum distance between a location and a claim boundary
    private int getClaimDistance(Location loc, Claim claim) {
        int lx = loc.getBlockX();
        int lz = loc.getBlockZ();
        int minX = Math.min(claim.getLesserBoundaryCorner().getBlockX(), claim.getGreaterBoundaryCorner().getBlockX());
        int maxX = Math.max(claim.getLesserBoundaryCorner().getBlockX(), claim.getGreaterBoundaryCorner().getBlockX());
        int minZ = Math.min(claim.getLesserBoundaryCorner().getBlockZ(), claim.getGreaterBoundaryCorner().getBlockZ());
        int maxZ = Math.max(claim.getLesserBoundaryCorner().getBlockZ(), claim.getGreaterBoundaryCorner().getBlockZ());

        // If inside the claim, distance is 0
        if (lx >= minX && lx <= maxX && lz >= minZ && lz <= maxZ) {
            return 0;
        }

        // Calculate distance to nearest edge
        int dx = 0;
        if (lx < minX) dx = minX - lx;
        else if (lx > maxX) dx = lx - maxX;

        int dz = 0;
        if (lz < minZ) dz = minZ - lz;
        else if (lz > maxZ) dz = lz - maxZ;

        return Math.max(dx, dz); // Chebyshev distance for the "band" approach
    }

    private void removeInheritedPermissions(Claim claim) {
        if (claim.parent == null) return;

        ArrayList<String> parentBuilders = new ArrayList<>();
        ArrayList<String> parentContainers = new ArrayList<>();
        ArrayList<String> parentAccessors = new ArrayList<>();
        ArrayList<String> parentManagers = new ArrayList<>();
        claim.parent.getPermissions(parentBuilders, parentContainers, parentAccessors, parentManagers);

        ArrayList<String> currentBuilders = new ArrayList<>();
        ArrayList<String> currentContainers = new ArrayList<>();
        ArrayList<String> currentAccessors = new ArrayList<>();
        ArrayList<String> currentManagers = new ArrayList<>();
        claim.getPermissions(currentBuilders, currentContainers, currentAccessors, currentManagers);

        // Each track is dropped on its own so that clearing an inherited copy of one kind of trust
        // never takes away trust of the other kind that was granted directly on this claim.
        for (String manager : parentManagers) {
            if (claim.isManager(manager)) {
                claim.dropManager(manager);
            }
        }

        for (String builder : parentBuilders) {
            ClaimPermission childPerm = claim.getPermission(builder.toLowerCase());
            if (childPerm == ClaimPermission.Build) {
                claim.dropInteractionPermission(builder);
            }
        }

        for (String container : parentContainers) {
            ClaimPermission childPerm = claim.getPermission(container.toLowerCase());
            if (childPerm != null && childPerm.isContainer()) {
                claim.dropInteractionPermission(container);
            }
        }

        for (String accessor : parentAccessors) {
            ClaimPermission childPerm = claim.getPermission(accessor.toLowerCase());
            if (childPerm == ClaimPermission.Access) {
                claim.dropInteractionPermission(accessor);
            }
        }
    }

    public boolean handleDropsCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;
        if (!player.hasPermission("griefprevention.unlockdrops")) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
            return true;
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        if (args.length > 0 && "unlock".equalsIgnoreCase(args[0])) {
            playerData.dropsAreUnlocked = true;
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.DropUnlockConfirmation);
        } else {
            playerData.dropsAreUnlocked = false;
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.DropLockConfirmation);
        }

        return true;
    }

    public boolean handleClaimExplosionsCommand(CommandSender sender, String[] args) {
        return this.handleClaimExplosionToggleCommand(sender, args, false);
    }

    public boolean handleClaimPvpCommand(CommandSender sender, String[] args) {
        return this.handlePvpCommand(sender, args);
    }

    public boolean handleClaimAlertsCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.dataStore.getMessage(Messages.CommandRequiresPlayer));
            return true;
        }
        Player player = (Player) sender;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);

        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimAlertsUsage);
            return true;
        }

        boolean enable;
        if (args.length > 0) {
            String arg = args[0].toLowerCase();
            if (arg.equals("on") || arg.equals("enable") || arg.equals("true")) {
                enable = true;
            } else if (arg.equals("off") || arg.equals("disable") || arg.equals("false")) {
                enable = false;
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimAlertsUsage);
                return true;
            }
        } else {
            enable = !claim.alertsEnabled;
        }

        claim.alertsEnabled = enable;
        this.dataStore.saveClaim(claim);

        GriefPrevention.sendMessage(player, enable ? TextMode.Success : TextMode.Warn,
            enable ? Messages.ClaimAlertsEnabled : Messages.ClaimAlertsDisabled);
        return true;
    }

    public boolean handleClaimToggleAlertsCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.dataStore.getMessage(Messages.CommandRequiresPlayer));
            return true;
        }
        Player player = (Player) sender;

        boolean enable;
        if (args.length > 0) {
            String arg = args[0].toLowerCase();
            if (arg.equals("on") || arg.equals("enable") || arg.equals("true")) {
                enable = true;
            } else if (arg.equals("off") || arg.equals("disable") || arg.equals("false")) {
                enable = false;
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimAlertsUsage);
                return true;
            }
        } else {
            enable = false;
            Claim currentClaim = this.dataStore.getClaimAt(player.getLocation(), false, null);
            if (currentClaim != null) {
                enable = !currentClaim.alertsEnabled;
            }
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        for (Claim claim : playerData.getClaims()) {
            claim.alertsEnabled = enable;
            this.dataStore.saveClaim(claim);
        }

        GriefPrevention.sendMessage(player, enable ? TextMode.Success : TextMode.Warn,
            enable ? Messages.ClaimAlertsEnabledGlobal : Messages.ClaimAlertsDisabledGlobal);
        return true;
    }

    public boolean handleClaimPvpConfirmCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.dataStore.getMessage(Messages.CommandRequiresPlayer));
            return true;
        }
        Player player = (Player) sender;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        if (playerData.pendingPvpToggle == null || playerData.pendingPvpClaimId == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPendingPvpToggle);
            return true;
        }

        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);
        if (claim == null || !claim.id.equals(playerData.pendingPvpClaimId)) {
            clearPendingPvpToggle(playerData);
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.PendingPvpToggleExpired);
            return true;
        }

        // Admin claims have no owner, so hasExplicitPermission can never pass there. Use the
        // claim permission check which lets griefprevention.adminclaims (and ops) modify them.
        if (claim.checkPermission(player, ClaimPermission.Edit, null) != null) {
            clearPendingPvpToggle(playerData);
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
            return true;
        }

        String[] confirmArgs = new String[] {
            playerData.pendingPvpToggle ? "true" : "false",
            "confirm",
        };
        clearPendingPvpToggle(playerData);
        return this.handlePvpCommand(sender, confirmArgs);
    }

    public boolean handlePvpCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.dataStore.getMessage(Messages.CommandRequiresPlayer));
            return true;
        }
        Player player = (Player) sender;

        if (!this.config_pvp_toggleCostClaimEnabled && !this.config_pvp_toggleCostSubdivisionEnabled) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.PvpToggleNotEnabled);
            return true;
        }

        if (args.length > 2) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.PvpToggleUsage);
            return true;
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);
        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.DeleteClaimMissing);
            return true;
        }

        // Admin claims have no owner, so hasExplicitPermission can never pass there. Use the
        // claim permission check which lets griefprevention.adminclaims (and ops) modify them.
        if (claim.checkPermission(player, ClaimPermission.Edit, null) != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
            return true;
        }

        Supplier<String> noBuildReason = claim.checkPermission(player, ClaimPermission.Build, null);
        if (noBuildReason != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason.get());
            return true;
        }

        PvpToggleContext toggleContext = getPvpToggleContext(claim);
        if (toggleContext == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.PvpToggleNotEnabledForClaimType);
            return true;
        }

        boolean toggleTo;
        if (args.length == 0) {
            toggleTo = !claim.pvpEnabled;
        } else {
            Boolean parsedState = parsePvpToggleArg(args[0]);
            if (parsedState == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PvpToggleUsage);
                return true;
            }
            toggleTo = parsedState;
        }

        boolean confirmed = args.length > 1 && "confirm".equalsIgnoreCase(args[1]);
        String claimTypeLabel = getPvpToggleClaimTypeLabel(toggleContext.mainClaim);

        if (!confirmed) {
            sendPvpToggleConfirmation(player, playerData, claim, toggleTo, toggleContext, claimTypeLabel);
            return true;
        }

        // Only report an already-toggled state when the claim has been explicitly toggled to it.
        // A still-default claim (pvpToggleSet == false) with a matching pvpEnabled still needs
        // the toggle applied to become an explicit, persisted override in either direction.
        if (claim.pvpToggleSet && claim.pvpEnabled == toggleTo) {
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                toggleTo ? Messages.PvpToggleAlreadyEnabled : Messages.PvpToggleAlreadyDisabled,
                claimTypeLabel
            );
            clearPendingPvpToggle(playerData);
            return true;
        }

        if (!chargePvpToggleFee(player, claim, toggleTo, toggleContext)) {
            return true;
        }

        claim.pvpEnabled = toggleTo;
        claim.pvpToggleSet = true;
        this.dataStore.saveClaim(claim);
        clearPendingPvpToggle(playerData);
        sendPvpToggleSuccess(player, toggleTo, toggleContext, claimTypeLabel);
        return true;
    }

    private void clearPendingPvpToggle(PlayerData playerData) {
        playerData.pendingPvpToggle = null;
        playerData.pendingPvpClaimId = null;
    }

    private @Nullable Boolean parsePvpToggleArg(String arg) {
        if ("true".equalsIgnoreCase(arg) || "on".equalsIgnoreCase(arg) || "enable".equalsIgnoreCase(arg)) {
            return true;
        }
        if ("false".equalsIgnoreCase(arg) || "off".equalsIgnoreCase(arg) || "disable".equalsIgnoreCase(arg)) {
            return false;
        }
        return null;
    }

    private @Nullable PvpToggleContext getPvpToggleContext(Claim claim) {
        if (claim.parent == null) {
            if (!this.config_pvp_toggleCostClaimEnabled) {
                return null;
            }
            return new PvpToggleContext(this.config_pvp_toggleCostClaimPrice, true);
        }

        if (!this.config_pvp_toggleCostSubdivisionEnabled) {
            return null;
        }
        return new PvpToggleContext(this.config_pvp_toggleCostSubdivisionPrice, false);
    }

    private String getPvpToggleClaimTypeLabel(boolean mainClaim) {
        return this.dataStore.getMessage(
            mainClaim ? Messages.ClaimLabel : Messages.SubdivisionLabel
        );
    }

    private void sendPvpToggleConfirmation(
        Player player,
        PlayerData playerData,
        Claim claim,
        boolean toggleTo,
        PvpToggleContext toggleContext,
        String claimTypeLabel
    ) {
        if (toggleContext.fee > 0.0) {
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                toggleTo ? Messages.ConfirmPvpToggleEnabledWithFee : Messages.ConfirmPvpToggleDisabledWithFee,
                VaultEconomyFormatting.format(this.getServer(), toggleContext.fee),
                claimTypeLabel
            );
        } else {
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                toggleTo ? Messages.ConfirmPvpToggleEnabledNoFee : Messages.ConfirmPvpToggleDisabledNoFee,
                claimTypeLabel
            );
        }

        playerData.pendingPvpToggle = toggleTo;
        playerData.pendingPvpClaimId = claim.id;
        GriefPrevention.sendMessage(
            player,
            TextMode.Instr,
            Messages.ConfirmPvpToggleInstruction,
            toggleTo ? "true" : "false"
        );
    }

    private boolean chargePvpToggleFee(
        Player player,
        Claim claim,
        boolean toggleTo,
        PvpToggleContext toggleContext
    ) {
        if (toggleContext.fee <= 0.0) {
            return true;
        }

        net.milkbowl.vault.economy.Economy economy = null;
        try {
            if (this.getServer().getPluginManager().getPlugin("Vault") != null) {
                @SuppressWarnings("null")
                org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> registration =
                    this.getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
                if (registration != null) {
                    economy = registration.getProvider();
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Vault is not installed
        }

        if (economy == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.EconomyNoVault);
            return false;
        }

        double balance = economy.getBalance(player);
        String formattedFee = VaultEconomyFormatting.format(economy, toggleContext.fee);
        String formattedBalance = VaultEconomyFormatting.format(economy, balance);
        if (balance < toggleContext.fee) {
            GriefPrevention.sendMessage(
                player,
                TextMode.Err,
                Messages.EconomyNotEnoughMoney,
                formattedFee,
                formattedBalance
            );
            return false;
        }

        net.milkbowl.vault.economy.EconomyResponse withdrawal = economy.withdrawPlayer(player, toggleContext.fee);
        if (!withdrawal.transactionSuccess()) {
            GriefPrevention.sendMessage(
                player,
                TextMode.Err,
                Messages.EconomyNotEnoughMoney,
                formattedFee,
                formattedBalance
            );
            return false;
        }

        GriefPrevention.AddLogEntry(
            "Player " +
                player.getName() +
                " toggled PvP " +
                toggleTo +
                " in " +
                (toggleContext.mainClaim ? "main claim" : "subdivision") +
                " at " +
                getfriendlyLocationString(claim.getLesserBoundaryCorner()) +
                " for fee: " +
                formattedFee,
            CustomLogEntryTypes.AdminActivity
        );
        return true;
    }

    private void sendPvpToggleSuccess(
        Player player,
        boolean toggleTo,
        PvpToggleContext toggleContext,
        String claimTypeLabel
    ) {
        if (toggleContext.fee > 0.0) {
            GriefPrevention.sendMessage(
                player,
                TextMode.Success,
                toggleTo ? Messages.PvPToggleEnabledWithFee : Messages.PvPToggleDisabledWithFee,
                claimTypeLabel,
                VaultEconomyFormatting.format(this.getServer(), toggleContext.fee)
            );
        } else {
            GriefPrevention.sendMessage(
                player,
                TextMode.Success,
                toggleTo ? Messages.PvPToggleEnabled : Messages.PvPToggleDisabled,
                claimTypeLabel
            );
        }
    }

    private static final class PvpToggleContext {
        private final double fee;
        private final boolean mainClaim;

        private PvpToggleContext(double fee, boolean mainClaim) {
            this.fee = fee;
            this.mainClaim = mainClaim;
        }
    }

    public boolean handleWitherExplosionsCommand(CommandSender sender, String[] args) {
        return this.handleClaimExplosionToggleCommand(sender, args, true);
    }

    private boolean handleClaimExplosionToggleCommand(CommandSender sender, String[] args, boolean witherOnly) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        if (witherOnly && !player.hasPermission("griefprevention.witherexplosions")) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
            return true;
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, false);
        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.DeleteClaimMissing);
            return true;
        }

        Supplier<String> noBuildReason = claim.checkPermission(player, ClaimPermission.Build, null);
        if (noBuildReason != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason.get());
            return true;
        }

        Boolean enabled = null;
        if (args.length > 1) {
            return false;
        }
        if (args.length == 1) {
            if ("on".equalsIgnoreCase(args[0])) {
                enabled = true;
            } else if ("off".equalsIgnoreCase(args[0])) {
                enabled = false;
            } else {
                return false;
            }
        }

        if (enabled == null) {
            enabled = witherOnly ? !claim.areWitherExplosionsAllowed : !claim.areExplosivesAllowed;
        }

        if (witherOnly) {
            claim.areWitherExplosionsAllowed = enabled;
            GriefPrevention.sendMessage(
                player,
                TextMode.Success,
                enabled ? Messages.WitherExplosionsEnabled : Messages.WitherExplosionsDisabled
            );
        } else {
            claim.areExplosivesAllowed = enabled;
            GriefPrevention.sendMessage(
                player,
                TextMode.Success,
                enabled ? Messages.ExplosivesEnabled : Messages.ExplosivesDisabled
            );
        }

        // Save the claim to persist the change
        this.dataStore.saveClaim(claim);

        return true;
    }

    public boolean abandonAllClaimsHandler(CommandSender sender) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        int originalClaimCount = playerData.getClaims().size();

        if (originalClaimCount == 0) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.YouHaveNoClaims);
            return true;
        }

        // Confirmation check would go here, but simplified for now
        this.dataStore.deleteClaimsForPlayer(player.getUniqueId(), false);
        GriefPrevention.sendMessage(player, TextMode.Success, Messages.SuccessfulAbandon, String.valueOf(0));

        playerData.setVisibleBoundaries(null);
        playerData.claimResizing = null;
        playerData.lastShovelLocation = null;
        playerData.claimSelectionActive = false;
        playerData.setClaimEditorSession(null);
        return true;
    }

    public boolean handleTrappedCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;
        if (!player.hasPermission("griefprevention.trapped")) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
            return true;
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = this.dataStore.getClaimAt(player.getLocation(), false, playerData.lastClaim);

        // if another /trapped is pending, silently ignore
        if (playerData.pendingTrapped) {
            return true;
        }

        // if the player isn't in a claim or has permission to build, tell him to man up
        if (claim == null || claim.checkPermission(player, ClaimPermission.Build, null) == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotTrappedHere);
            return true;
        }

        // rescue destination may be set by GPFlags or other plugin, ask to find out
        SaveTrappedPlayerEvent event = new SaveTrappedPlayerEvent(player, claim);
        Bukkit.getPluginManager().callEvent(event);

        // if the player is in the nether or end, there's no way to programmatically find a safe place
        if (
            player.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL && event.getDestination() == null
        ) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrappedWontWorkHere);
            return true;
        }

        // if the player is in an administrative claim and AllowTrappedInAdminClaims is false, contact an admin
        if (!this.config_claims_allowTrappedInAdminClaims && claim.isAdminClaim() && event.getDestination() == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrappedWontWorkHere);
            return true;
        }

        // send instructions and schedule rescue
        GriefPrevention.sendMessage(player, TextMode.Instr, Messages.RescuePending);
        PlayerRescueTask task = new PlayerRescueTask(player, player.getLocation(), event.getDestination());
        SchedulerUtil.runLaterEntity(this, player, task::run, 200L); // 10 seconds (200 ticks)

        return true;
    }

    /**
     * Gets the minimum Y coordinate (build height limit) for the specified world.
     * This is part of the public API for extensions.
     *
     * @param world the World to query
     * @return the minimum Y coordinate
     */
    public static int getWorldMinY(@NotNull World world) {
        return WorldHeightCompatProvider.current().minHeight(world);
    }

    /**
     * Gets the maximum Y coordinate (build height limit) for the specified world.
     * This is part of the public API for extensions.
     *
     * @param world the World to query
     * @return the maximum Y coordinate
     */
    public static int getWorldMaxY(@NotNull World world) {
        return WorldHeightCompatProvider.current().maxHeight(world);
    }

    public static void sendMessage(Player player, ChatColor color, String message) {
        if (!hasVisibleMessageContent(message)) return;
        if (player != null) {
            player.sendMessage(color + message);
        } else {
            Bukkit.getLogger().info(color + message);
        }
    }

    /**
     * Restores nature in every chunk touched by a claim. Intended for claims that have just been deleted,
     * abandoned, or expired - if the claim is still in the data store, its blocks are left alone and only
     * the area bordering it is restored.
     *
     * @param claim the claim whose area should be restored
     * @param delayInTicks how long to wait before capturing and restoring, giving players time to move away
     */
    public void restoreClaim(Claim claim, long delayInTicks) {
        // admin claims aren't automatically cleaned up when deleted or abandoned
        if (claim.isAdminClaim()) return;

        // it's too expensive to do this for huge claims
        if (claim.getArea() > 10000 || claim.getWidth() > 250 || claim.getHeight() > 250) return;

        Location lesser = claim.getLesserBoundaryCorner();
        Location greater = claim.getGreaterBoundaryCorner();
        World world = lesser.getWorld();
        if (world == null) return;

        int miny = this.getSeaLevel(world) - 15;
        // derive chunk coordinates arithmetically rather than via Claim#getChunks, which would load chunks
        // off the owning region thread
        for (int chunkX = lesser.getBlockX() >> 4; chunkX <= greater.getBlockX() >> 4; chunkX++) {
            for (int chunkZ = lesser.getBlockZ() >> 4; chunkZ <= greater.getBlockZ() >> 4; chunkZ++) {
                this.restoreChunkAt(world, chunkX, chunkZ, miny, false, delayInTicks, null);
            }
        }
    }

    /**
     * Restores nature in a single chunk. Block data is captured on the chunk's own region thread, processed
     * off-thread, then applied back on the region thread.
     *
     * @param chunk the chunk to restore
     * @param miny lowest world Y to consider; blocks below this are left untouched
     * @param aggressiveMode whether to restore claimed blocks too
     * @param delayInTicks how long to wait before capturing and restoring
     * @param playerReceivingVisualization player to show the restored area to, or null for automatic restoration
     */
    public void restoreChunk(
        Chunk chunk,
        int miny,
        boolean aggressiveMode,
        long delayInTicks,
        Player playerReceivingVisualization
    ) {
        this.restoreChunkAt(
            chunk.getWorld(),
            chunk.getX(),
            chunk.getZ(),
            miny,
            aggressiveMode,
            delayInTicks,
            playerReceivingVisualization
        );
    }

    private void restoreChunkAt(
        World world,
        int chunkX,
        int chunkZ,
        int miny,
        boolean aggressiveMode,
        long delayInTicks,
        Player playerReceivingVisualization
    ) {
        // snapshot a 1-block boundary outside the chunk all the way around, for reference during processing
        int minX = (chunkX << 4) - 1;
        int minZ = (chunkZ << 4) - 1;
        int floorY = Math.max(getWorldMinY(world), miny);
        int maxY = getWorldMaxY(world);
        Location snapshotOrigin = new Location(world, minX, floorY, minZ);

        SchedulerUtil.runAtLocationLater(
            this,
            snapshotOrigin,
            () -> {
                int sizeY = maxY - floorY;
                if (sizeY <= 0) return;

                BlockSnapshot[][][] snapshots = new BlockSnapshot[18][sizeY][18];
                for (int x = 0; x < 18; x++) {
                    for (int z = 0; z < 18; z++) {
                        for (int y = 0; y < sizeY; y++) {
                            snapshots[x][y][z] = new BlockSnapshot(world.getBlockAt(minX + x, floorY + y, minZ + z));
                        }
                    }
                }

                // the boundary corners describe the chunk proper - the outer border ring is reference data only,
                // and the execution task never applies changes there
                Location lesserBoundaryCorner = new Location(world, minX + 1, floorY, minZ + 1);
                Location greaterBoundaryCorner = new Location(world, minX + 16, maxY - 1, minZ + 16);

                // when done processing, this task schedules a region task to actually update the world
                RestoreNatureProcessingTask task = new RestoreNatureProcessingTask(
                    snapshots,
                    0,
                    world.getEnvironment(),
                    lesserBoundaryCorner.getBlock().getBiome(),
                    lesserBoundaryCorner,
                    greaterBoundaryCorner,
                    this.getSeaLevel(world),
                    aggressiveMode,
                    this.creativeRulesApply(lesserBoundaryCorner),
                    playerReceivingVisualization
                );
                SchedulerUtil.runAsyncNow(this, task);
            },
            delayInTicks
        );
    }

    // Public helper methods for command handlers in other packages

    /**
     * Deletes a claim. For use by command handlers in other packages.
     * @param claim The claim to delete
     * @param fireEvent Whether to fire the claim deletion event
     */
    public void deleteClaimPublic(Claim claim, boolean fireEvent) {
        this.dataStore.deleteClaim(claim, fireEvent, false);
    }

    /**
     * Deletes all claims in a world. For use by command handlers in other packages.
     * @param world The world to delete claims in
     * @param deleteAdminClaims Whether to also delete admin claims
     */
    public void deleteClaimsInWorldPublic(World world, boolean deleteAdminClaims) {
        this.dataStore.deleteClaimsInWorld(world, deleteAdminClaims);
    }

    /**
     * Handles the extendclaim command logic.
     * @param player The player executing the command
     * @param args The command arguments
     * @return true if the command was handled successfully
     */
    public boolean handleExtendClaimCommand(Player player, String[] args) {
        if (args.length < 1) {
            // link to a video demo of land claiming, based on world type
            if (GriefPrevention.instance.creativeRulesApply(player.getLocation())) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    Messages.CreativeBasicsVideo2,
                    DataStore.CREATIVE_VIDEO_URL
                );
            } else if (GriefPrevention.instance.claimsEnabledForWorld(player.getLocation().getWorld())) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    Messages.SurvivalBasicsVideo2,
                    DataStore.SURVIVAL_VIDEO_URL
                );
            }
            return false;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            // link to a video demo of land claiming, based on world type
            if (GriefPrevention.instance.creativeRulesApply(player.getLocation())) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    Messages.CreativeBasicsVideo2,
                    DataStore.CREATIVE_VIDEO_URL
                );
            } else if (GriefPrevention.instance.claimsEnabledForWorld(player.getLocation().getWorld())) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    Messages.SurvivalBasicsVideo2,
                    DataStore.SURVIVAL_VIDEO_URL
                );
            }
            return false;
        }

        boolean holdingModificationTool =
            player.getGameMode() == GameMode.CREATIVE ||
            CompatUtil.getItemInMainHand(player).getType() == GriefPrevention.instance.config_claims_modificationTool ||
            player.hasPermission("griefprevention.extendclaim.toolbypass");
        if (!holdingModificationTool) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.MustHoldModificationToolForThat);
            return true;
        }

        // must be standing in a land claim
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = getSelectedOrCurrentClaim(player, playerData, true);
        if (claim != null && claim.parent != null && playerData.claimResizing == null) {
            claim = claim.parent;
        }

        if (claim != null && claim.isShaped() && claim.parent == null && !claim.is3D()) {
            ClaimEditorSession shapedSession = resolveShapedExtendSession(player, playerData, claim);
            if (shapedSession == null) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Err,
                    "Stand in the shaped section you want to expand and face its boundary."
                );
                return true;
            }

            ClaimEditResult result = claimEditor.apply(
                shapedSession,
                new ClaimEditIntent(
                    ClaimEditIntentType.EXPAND_SEGMENT,
                    ClaimEditSource.COMMAND,
                    null,
                    claim.getID(),
                    null,
                    amount,
                    holdingModificationTool,
                    Collections.emptyList()
                )
            );
            if (!result.success()) {
                applyClaimEditResult(player, playerData, result);
                return true;
            }

            CreateClaimResult updateResult = this.dataStore.updateShapedClaim(
                player,
                playerData,
                claim,
                result.preview().polygon()
            );
            if (!updateResult.succeeded || updateResult.claim == null) {
                if (updateResult.denialMessage != null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, updateResult.denialMessage.get());
                } else if (updateResult.claim != null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);
                    BoundaryVisualization.visualizeClaim(player, updateResult.claim, VisualizationType.CONFLICT_ZONE);
                } else {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
                }
                return true;
            }

            ClaimEditPreview preview = new ClaimEditPreview(
                updateResult.claim.getBoundaryPolygon(),
                result.session().activeSegment(),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                result.messages()
            );
            playerData.lastClaim = updateResult.claim;
            playerData.setClaimEditorSession(result.session().withPreview(preview));
            for (String message : result.messages()) {
                GriefPrevention.sendMessage(player, TextMode.Instr, message);
            }
            BoundaryVisualization.visualizeClaim(
                player,
                updateResult.claim,
                updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM
            );
            return true;
        }

        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.StandInClaimToResize);
            return true;
        }

        // must have permission to edit the land claim you're in
        Supplier<String> errorMessage = claim.checkPermission(player, ClaimPermission.Edit, null);
        if (errorMessage != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotYourClaim);
            return true;
        }

        // determine new corner coordinates
        org.bukkit.util.Vector direction = player.getLocation().getDirection();
        Location lc = claim.getLesserBoundaryCorner();
        Location gc = claim.getGreaterBoundaryCorner();
        int newx1 = lc.getBlockX();
        int newx2 = gc.getBlockX();
        int newy1 = lc.getBlockY();
        int newy2 = gc.getBlockY();
        int newz1 = lc.getBlockZ();
        int newz2 = gc.getBlockZ();

        // Handle vertical extension for 3D claims
        boolean is3D = claim.is3D();
        if (direction.getY() > .75) {
            if (!is3D) {
                GriefPrevention.sendMessage(player, TextMode.Info, Messages.ClaimsExtendToSky);
                return true;
            }
            // Looking up - extend upward (increase max Y)
            newy2 += amount;
        } else if (direction.getY() < -.75) {
            if (!is3D) {
                GriefPrevention.sendMessage(player, TextMode.Info, Messages.ClaimsAutoExtendDownward);
                return true;
            }
            // Looking down - extend downward (decrease min Y)
            newy1 -= amount;
        } else if (Math.abs(direction.getX()) < .3) {
            // if changing Z only
            if (direction.getZ() > 0) {
                newz2 += amount; // north
            } else {
                newz1 -= amount; // south
            }
        } else if (Math.abs(direction.getZ()) < .3) {
            // if changing X only
            if (direction.getX() > 0) {
                newx2 += amount; // east
            } else {
                newx1 -= amount; // west
            }
        } else {
            // diagonals
            if (direction.getX() > 0) {
                newx2 += amount;
            } else {
                newx1 -= amount;
            }

            if (direction.getZ() > 0) {
                newz2 += amount;
            } else {
                newz1 -= amount;
            }
        }

        // For 3D subdivisions, validate that extension stays within parent bounds
        if (is3D && claim.parent != null) {
            Claim parent = claim.parent;
            int parentMinY = parent.getLesserBoundaryCorner().getBlockY();
            int parentMaxY = parent.getGreaterBoundaryCorner().getBlockY();

            if (newy1 < parentMinY || newy2 > parentMaxY) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapSubdivision);
                return true;
            }
        }

        // attempt resize
        playerData.claimResizing = claim;
        this.dataStore.resizeClaimWithChecks(player, playerData, newx1, newx2, newy1, newy2, newz1, newz2);
        playerData.claimResizing = null;

        return true;
    }

    /**
     * Handles the /mergeclaims command.
     * If standing inside a claim, starts merge mode with the current claim's edge.
     * If standing outside a claim, enters merge mode requiring two boundary clicks.
     *
     * @param player The player executing the command
     * @return true if the command was handled successfully
     */
    public boolean handleMergeClaimsCommand(Player player) {
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        // If already in merge mode, check if standing in second claim to complete merge
        if (playerData.shovelMode == ShovelMode.Merge) {
            Claim currentClaim = this.dataStore.getClaimAt(player.getLocation(), false, playerData.lastClaim);
            if (currentClaim != null) {
                while (currentClaim.parent != null) {
                    currentClaim = currentClaim.parent;
                }

                if (playerData.claimMerging != null && !currentClaim.getID().equals(playerData.claimMerging.getID())) {
                    Supplier<String> noEditReason = currentClaim.checkPermission(player, ClaimPermission.Edit, null);
                    if (noEditReason != null) {
                        GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
                        return true;
                    }

                    // Standing in the second shaped claim: the edge the player faces and how
                    // deep they stand inside it size the corridor. Rectangles use no nib data.
                    boolean requiresNib = requiresMergeNib(currentClaim);
                    Integer secondEdgeIndex = requiresNib
                        ? resolveBoundarySegmentForPlayer(currentClaim.getBoundaryPolygon(), player.getLocation())
                        : null;
                    if (secondEdgeIndex == null && requiresNib) {
                        GriefPrevention.sendMessage(
                            player,
                            TextMode.Err,
                            "Stand in the shaped section you want to merge and face its boundary."
                        );
                        return true;
                    }
                    playerData.mergeSecondEdgeIndex = secondEdgeIndex;
                    playerData.mergeSecondDepthPoint = secondEdgeIndex == null ? null : new OrthogonalPoint2i(
                        player.getLocation().getBlockX(),
                        player.getLocation().getBlockZ()
                    );
                    this.dataStore.mergeClaims(
                        player,
                        playerData,
                        playerData.claimMerging,
                        currentClaim,
                        playerData.mergeEdgeIndex
                    );
                    return true;
                }

                if (playerData.claimMerging == null) {
                    // No first claim selected yet - use current claim as first claim
                    Supplier<String> noEditReason = currentClaim.checkPermission(player, ClaimPermission.Edit, null);
                    if (noEditReason != null) {
                        GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
                        return true;
                    }
                    boolean requiresNib = requiresMergeNib(currentClaim);
                    Integer edgeIndex = requiresNib
                        ? resolveBoundarySegmentForPlayer(currentClaim.getBoundaryPolygon(), player.getLocation())
                        : null;
                    if (edgeIndex == null && requiresNib) {
                        GriefPrevention.sendMessage(
                            player,
                            TextMode.Err,
                            "Stand in the shaped section you want to merge and face its boundary."
                        );
                        return true;
                    }
                    playerData.mergeEdgeIndex = edgeIndex;
                    playerData.mergeFirstDepthPoint = edgeIndex == null ? null : new OrthogonalPoint2i(
                        player.getLocation().getBlockX(),
                        player.getLocation().getBlockZ()
                    );
                    playerData.mergeSecondEdgeIndex = null;
                    playerData.mergeSecondDepthPoint = null;
                    playerData.claimMerging = currentClaim;
                    BoundaryVisualization.visualizeClaim(
                        player,
                        currentClaim,
                        currentClaim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM
                    );
                    Set<Claim> nearbyClaims = this.dataStore.getNearbyClaims(player.getLocation());
                    if (!nearbyClaims.isEmpty()) {
                        BoundaryVisualization.mergeNearbyClaims(player, nearbyClaims);
                    }
                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Instr,
                        "Merge started. Right click the second claim to finish the merge."
                    );
                    return true;
                }
            }
            // Either standing in same claim or no claim - show merge in progress message
            if (playerData.claimMerging != null) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    "Merge in progress. Right click the second claim with golden shovel to merge. Or run /basicclaims to exit merge claims mode."
                );
            } else {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Instr,
                    "Merge mode active. Right click a claim boundary to begin merging."
                );
            }
            return true;
        }

        Claim currentClaim = this.dataStore.getClaimAt(player.getLocation(), false, playerData.lastClaim);

        // If standing inside a claim, use it as the first claim
        if (currentClaim != null) {
            // Navigate to top-level claim
            while (currentClaim.parent != null) {
                currentClaim = currentClaim.parent;
            }

            // Check edit permission
            Supplier<String> noEditReason = currentClaim.checkPermission(player, ClaimPermission.Edit, null);
            if (noEditReason != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
                return true;
            }

            // Detect which edge/nib the player is standing in for shaped claims. Rectangles
            // merge from their whole extent and deliberately carry no nib metadata.
            boolean requiresNib = requiresMergeNib(currentClaim);
            Integer edgeIndex = requiresNib
                ? resolveBoundarySegmentForPlayer(currentClaim.getBoundaryPolygon(), player.getLocation())
                : null;
            if (edgeIndex == null && requiresNib) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Err,
                    "Stand in the shaped section you want to merge and face its boundary."
                );
                return true;
            }
            playerData.mergeEdgeIndex = edgeIndex;
            playerData.mergeFirstDepthPoint = edgeIndex == null ? null : new OrthogonalPoint2i(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
            );
            playerData.mergeSecondEdgeIndex = null;
            playerData.mergeSecondDepthPoint = null;

            playerData.claimMerging = currentClaim;
            playerData.shovelMode = ShovelMode.Merge;
            playerData.claimResizing = null;
            playerData.claimSubdividing = null;
            playerData.lastShovelLocation = null;

            // Visualize the claim being merged and nearby claims
            BoundaryVisualization.visualizeClaim(
                player,
                currentClaim,
                currentClaim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM
            );
            Set<Claim> nearbyClaims = this.dataStore.getNearbyClaims(player.getLocation());
            if (!nearbyClaims.isEmpty()) {
                BoundaryVisualization.mergeNearbyClaims(player, nearbyClaims);
            }

            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                "Merge started. Right click the second claim to finish the merge."
            );
            return true;
        }

        // Standing outside a claim - enter merge mode requiring two clicks
        playerData.claimMerging = null;
        playerData.mergeEdgeIndex = null;
        playerData.mergeSecondEdgeIndex = null;
        playerData.mergeFirstDepthPoint = null;
        playerData.mergeSecondDepthPoint = null;
        playerData.shovelMode = ShovelMode.Merge;
        playerData.claimResizing = null;
        playerData.claimSubdividing = null;
        playerData.lastShovelLocation = null;

        // Visualize nearby claims
        Set<Claim> nearbyClaims = this.dataStore.getNearbyClaims(player.getLocation());
        if (!nearbyClaims.isEmpty()) {
            BoundaryVisualization.mergeNearbyClaims(player, nearbyClaims);
        }

        GriefPrevention.sendMessage(
            player,
            TextMode.Instr,
            "Merge mode active. Right click a claim boundary to begin merging. Use /basicclaims to cancel."
        );
        return true;
    }

    private @Nullable ClaimEditorSession resolveShapedExtendSession(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Claim claim
    ) {
        ClaimEditorSession currentSession = playerData.getClaimEditorSession();
        ClaimEditorSession session = loadClaimIntoShapedSession(currentSession, claim);

        Integer edgeIndex = resolveBoundarySegmentForPlayer(claim.getBoundaryPolygon(), player.getLocation());
        if (edgeIndex != null) {
            OrthogonalEdge2i edge = claim.getBoundaryPolygon().edges().get(edgeIndex);
            OrthogonalDirection expansionDirection = resolveExpansionDirectionForPlayer(edge, player.getLocation());
            SegmentSelection selection = new SegmentSelection(claim.getID(), edgeIndex, null, null, expansionDirection);
            ClaimEditPreview preview = new ClaimEditPreview(
                claim.getBoundaryPolygon(),
                selection,
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
            );
            return session.withActiveSegment(selection).withPreview(preview);
        }

        if (session.activeSegment() != null) {
            int activeEdgeIndex = session.activeSegment().edgeIndex();
            OrthogonalPolygon polygon = session.preview().polygon();
            if (
                polygon != null &&
                activeEdgeIndex >= 0 &&
                activeEdgeIndex < polygon.edges().size() &&
                session.activeTarget() != null &&
                claim.getID().equals(session.activeTarget().claimId())
            ) {
                return session;
            }
        }

        return null;
    }

    private @NotNull ClaimEditorSession loadClaimIntoShapedSession(
        @NotNull ClaimEditorSession session,
        @NotNull Claim claim
    ) {
        OrthogonalPolygon polygon = claim.getBoundaryPolygon();
        if (
            session.activeTarget() != null &&
            session.activeTarget().claimId() != null &&
            session.activeTarget().claimId().equals(claim.getID()) &&
            session.preview().polygon() != null &&
            session.preview().polygon().corners().equals(polygon.corners())
        ) {
            return session;
        }

        return session
            .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.COMMAND)
            .withTarget(new ClaimEditTarget(ClaimEditTargetType.EXISTING_PARENT_CLAIM, claim.getID()))
            .withOpenPath(null)
            .withActiveSegment(null)
            .withPreview(
                new ClaimEditPreview(
                    polygon,
                    null,
                    Collections.emptyList(),
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList()
                )
            );
    }

    private @Nullable Integer resolveBoundarySegmentForPlayer(
        @NotNull OrthogonalPolygon polygon,
        @NotNull Location playerLocation
    ) {
        org.bukkit.util.Vector direction = playerLocation.getDirection();
        if (Math.abs(direction.getX()) >= Math.abs(direction.getZ())) {
            return direction.getX() >= 0
                ? nearestVerticalEdge(polygon, playerLocation.getBlockZ(), playerLocation.getX(), true)
                : nearestVerticalEdge(polygon, playerLocation.getBlockZ(), playerLocation.getX(), false);
        }

        return direction.getZ() >= 0
            ? nearestHorizontalEdge(polygon, playerLocation.getBlockX(), playerLocation.getZ(), true)
            : nearestHorizontalEdge(polygon, playerLocation.getBlockX(), playerLocation.getZ(), false);
    }

    private boolean requiresMergeNib(@NotNull Claim claim) {
        return claim.getBoundaryPolygon().corners().size() != 4;
    }

    private @NotNull OrthogonalDirection resolveExpansionDirectionForPlayer(
        @NotNull OrthogonalEdge2i edge,
        @NotNull Location playerLocation
    ) {
        org.bukkit.util.Vector direction = playerLocation.getDirection();
        if (edge.isHorizontal()) {
            return direction.getZ() >= 0 ? OrthogonalDirection.SOUTH : OrthogonalDirection.NORTH;
        }

        if (edge.isVertical()) {
            return direction.getX() >= 0 ? OrthogonalDirection.EAST : OrthogonalDirection.WEST;
        }

        return edge.outwardDirectionForPositiveOffset();
    }

    private @Nullable Integer nearestVerticalEdge(
        @NotNull OrthogonalPolygon polygon,
        int playerZ,
        double playerX,
        boolean towardPositiveX
    ) {
        Integer bestIndex = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < polygon.edges().size(); i++) {
            OrthogonalEdge2i edge = polygon.edges().get(i);
            if (!edge.isVertical() || playerZ < edge.minZ() || playerZ > edge.maxZ()) {
                continue;
            }

            double edgeX = edge.start().x();
            double distance = edgeX - playerX;
            if (towardPositiveX ? distance < -0.5D : distance > 0.5D) {
                continue;
            }

            double absoluteDistance = Math.abs(distance);
            if (absoluteDistance < bestDistance) {
                bestDistance = absoluteDistance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private @Nullable Integer nearestHorizontalEdge(
        @NotNull OrthogonalPolygon polygon,
        int playerX,
        double playerZ,
        boolean towardPositiveZ
    ) {
        Integer bestIndex = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < polygon.edges().size(); i++) {
            OrthogonalEdge2i edge = polygon.edges().get(i);
            if (!edge.isHorizontal() || playerX < edge.minX() || playerX > edge.maxX()) {
                continue;
            }

            double edgeZ = edge.start().z();
            double distance = edgeZ - playerZ;
            if (towardPositiveZ ? distance < -0.5D : distance > 0.5D) {
                continue;
            }

            double absoluteDistance = Math.abs(distance);
            if (absoluteDistance < bestDistance) {
                bestDistance = absoluteDistance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private void applyClaimEditResult(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull ClaimEditResult result
    ) {
        playerData.setClaimEditorSession(result.session());

        if (!result.success()) {
            if (result.fallbackMessage() != null) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Err,
                    BukkitClaimEditMessages.toMessage(result.fallbackMessage())
                );
                return;
            }

            for (String message : result.messages()) {
                GriefPrevention.sendMessage(player, TextMode.Err, message);
            }
            return;
        }

        for (String message : result.messages()) {
            GriefPrevention.sendMessage(player, TextMode.Instr, message);
        }
    }

    /**
     * Changes the owner of a claim. For use by command handlers in other packages.
     * @param claim The claim to transfer
     * @param newOwnerID The UUID of the new owner (null for admin claim)
     * @throws DataStore.NoTransferException if the claim cannot be transferred
     */
    public void changeClaimOwnerPublic(Claim claim, UUID newOwnerID) throws DataStore.NoTransferException {
        this.dataStore.changeClaimOwner(claim, newOwnerID);
    }
}
