/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2011 Ryan Hamshire

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

import com.griefprevention.claims.editor.BukkitClaimEditMessages;
import com.griefprevention.claims.editor.ClaimEditIntent;
import com.griefprevention.claims.editor.ClaimEditIntentType;
import com.griefprevention.claims.editor.ClaimEditPreview;
import com.griefprevention.claims.editor.ClaimEditResult;
import com.griefprevention.claims.editor.ClaimEditSource;
import com.griefprevention.claims.editor.ClaimEditTarget;
import com.griefprevention.claims.editor.ClaimEditTargetType;
import com.griefprevention.claims.editor.ClaimEditor;
import com.griefprevention.claims.editor.ClaimEditorMode;
import com.griefprevention.claims.editor.ClaimEditorSession;
import com.griefprevention.claims.editor.ClaimEditorSkeleton;
import com.griefprevention.claims.editor.ShapedPathDraft;
import com.griefprevention.compat.BlockDataCompat;
import com.griefprevention.compat.Compat;
import com.griefprevention.compat.MaterialCompat;
import com.griefprevention.compat.MaterialTagCompat;
import com.griefprevention.events.BoundaryVisualizationEvent;
import com.griefprevention.geometry.OrthogonalEdge2i;
import com.griefprevention.geometry.OrthogonalPoint2i;
import com.griefprevention.geometry.OrthogonalPolygon;
import com.griefprevention.geometry.OrthogonalPolygonValidationIssue;
import com.griefprevention.geometry.OrthogonalPolygonValidationIssueType;
import com.griefprevention.geometry.OrthogonalPolygonValidationResult;
import com.griefprevention.protection.ProtectionHelper;
import com.griefprevention.util.command.MonitorableCommand;
import com.griefprevention.util.command.MonitoredCommands;
import com.griefprevention.visualization.Boundary;
import com.griefprevention.visualization.BoundaryVisualization;
import com.griefprevention.visualization.VisualizationType;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import me.ryanhamshire.GriefPrevention.compat.CompatUtil;
import me.ryanhamshire.GriefPrevention.events.ClaimInspectionEvent;
import me.ryanhamshire.GriefPrevention.util.BoundingBox;
import me.ryanhamshire.GriefPrevention.util.SchedulerUtil;
import me.ryanhamshire.GriefPrevention.util.TaskHandle;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Animals;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Mule;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlayerEventHandler implements Listener {

    private static final @NotNull ClaimEditor claimEditor = new ClaimEditorSkeleton();
    private final DataStore dataStore;
    private final GriefPrevention instance;
    private final ClaimToolDispatcher claimToolDispatcher;

    // list of temporarily banned ip's
    // CopyOnWriteArrayList: onAsyncPlayerPreLogin writes from an async thread while
    // other handlers (onPlayerJoin, onPlayerKick) iterate from the main thread.
    private final CopyOnWriteArrayList<IpBanInfo> tempBannedIps = new CopyOnWriteArrayList<>();

    // number of milliseconds in a day
    private final long MILLISECONDS_IN_DAY = 1000 * 60 * 60 * 24;

    // timestamps of login and logout notifications in the last minute
    private final ArrayList<Long> recentLoginLogoutNotifications = new ArrayList<>();

    // Canvas fallback: track refunded pearl entity UUIDs to prevent dupe (one refund per pearl)
    private final Set<UUID> refundedEnderPearlEntities = ConcurrentHashMap.newKeySet();
    // Track players who were just rolled back from denied pearl - prevents endermite spawn at rollback location
    private final Set<UUID> recentPearlRollbackPlayers = ConcurrentHashMap.newKeySet();
    // Purpur etc: both events can fire; track which handler refunded to avoid double refund
    private final Set<UUID> refundedByTeleportEvent = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refundedByProjectileHitEvent = ConcurrentHashMap.newKeySet();

    // regex pattern for the "how do i claim land?" scanner
    private Pattern howToClaimPattern = null;

    // matcher for banned words
    private WordFinder bannedWordFinder;

    /**
     * Gets the banned word finder instance
     *
     * @return The WordFinder instance for checking and censoring banned words
     */
    public WordFinder getBannedWordFinder() {
        return bannedWordFinder;
    }

    private MonitoredCommands pvpBlockedCommands;
    private MonitoredCommands accessTrustCommands;
    private MonitoredCommands chatCommands;
    private MonitoredCommands whisperCommands;

    // spam tracker
    SpamDetector spamDetector = new SpamDetector();
    // Definitions for specific material groups that do not have a tag
    private final Set<Material> spawnEggs;
    private final Set<Material> dyes;
    private final Set<Material> mobBuckets;

    // typical constructor, yawn
    PlayerEventHandler(DataStore dataStore, GriefPrevention plugin) {
        this.dataStore = dataStore;
        this.instance = plugin;
        this.claimToolDispatcher = new ClaimToolDispatcher(plugin, this);
        // Initialize empty on load so never null just in case. Reload after plugins
        // enable.
        this.bannedWordFinder = new WordFinder(Collections.emptyList());
        this.pvpBlockedCommands = new MonitoredCommands(Collections.emptyList());
        this.accessTrustCommands = new MonitoredCommands(Collections.emptyList());
        this.chatCommands = new MonitoredCommands(Collections.emptyList());
        this.whisperCommands = new MonitoredCommands(Collections.emptyList());

        spawnEggs = new HashSet<>();
        dyes = new HashSet<>();
        mobBuckets = new HashSet<>();
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.endsWith("_SPAWN_EGG")) spawnEggs.add(material);
            else if (name.endsWith("_DYE")) dyes.add(material);
            else if (
                name.endsWith("_BUCKET") &&
                !name.startsWith("LEGACY_") &&
                // fluids are covered by PlayerBucketEmptyEvent, powder snow by BlockPlaceEvent
                !name.equals("WATER_BUCKET") &&
                !name.equals("LAVA_BUCKET") &&
                !name.equals("MILK_BUCKET") &&
                !name.equals("POWDER_SNOW_BUCKET")
            ) mobBuckets.add(material);
        }

        reload();
    }

    protected void reload() {
        this.howToClaimPattern = null;
        this.bannedWordFinder = new WordFinder(instance.dataStore.loadBannedWords());
        this.pvpBlockedCommands = new MonitoredCommands(instance.config_pvp_blockedCommands);
        this.accessTrustCommands = new MonitoredCommands(instance.config_claims_commandsRequiringAccessTrust);
        this.chatCommands = new MonitoredCommands(instance.config_spam_monitorSlashCommands);
        this.whisperCommands = new MonitoredCommands(instance.config_eavesdrop_whisperCommands);
    }

    // when a player chats, monitor for spam
    @EventHandler(ignoreCancelled = false, priority = EventPriority.LOWEST)
    synchronized void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!player.isOnline()) {
            event.setCancelled(true);
            return;
        }

        String message = event.getMessage();

        // soft muted messages only go to the sender and other soft muted players
        if (this.dataStore.isSoftMuted(player.getUniqueId())) {
            String notificationMessage = "(Muted " + player.getName() + "): " + message;
            Set<Player> recipients = event.getRecipients();
            Set<Player> recipientsToKeep = new HashSet<>();
            Set<Player> eavesdroppers = new HashSet<>();

            for (Player recipient : recipients) {
                if (recipient == null) continue;

                if (this.dataStore.isSoftMuted(recipient.getUniqueId())) {
                    recipientsToKeep.add(recipient);
                } else if (recipient.hasPermission("griefprevention.eavesdrop.softmute")) {
                    eavesdroppers.add(recipient);
                }
            }

            // the sender must always see their own message, otherwise the mute is obvious
            recipientsToKeep.add(player);

            // trim the audience rather than cancelling the event so the server's chat
            // plugin still formats the message for the recipients we keep - a manually
            // re-sent copy would use the vanilla format and give the soft mute away
            recipients.clear();
            recipients.addAll(recipientsToKeep);

            // allow admins to see the soft-muted text
            for (Player eavesdropper : eavesdroppers) {
                eavesdropper.sendMessage(ChatColor.GRAY + notificationMessage);
            }

            GriefPrevention.AddLogEntry(notificationMessage, CustomLogEntryTypes.MutedChat, false);
            return;
        }

        boolean muted = this.handlePlayerChat(player, message, event);
        Set<Player> recipients = event.getRecipients();

        // muted messages go out to only the sender
        if (muted) {
            recipients.clear();
            recipients.add(player);
        }
        // troll and excessive profanity filter
        else if (!player.hasPermission("griefprevention.spam") && this.bannedWordFinder.hasMatch(message)) {
            // allow admins to see the soft-muted text
            String notificationMessage = "(Muted " + player.getName() + "): " + message;
            for (Player recipient : recipients) {
                if (recipient.hasPermission("griefprevention.eavesdrop.softmute")) {
                    recipient.sendMessage(ChatColor.GRAY + notificationMessage);
                }
            }

            // limit recipients to sender
            recipients.clear();
            recipients.add(player);

            // if player not new warn for the first infraction per play session.
            if (!GriefPrevention.isNewToServer(player)) {
                PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
                if (!playerData.profanityWarned) {
                    playerData.profanityWarned = true;
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoProfanity);
                    event.setCancelled(true);
                    return;
                }
            }
            // otherwise assume chat troll and mute all chat from this sender until an admin
            // says otherwise
            else if (instance.config_trollFilterEnabled) {
                GriefPrevention.AddLogEntry(
                    "Auto-muted new player " +
                        player.getName() +
                        " for profanity shortly after join.  Use /SoftMute to undo.",
                    CustomLogEntryTypes.AdminActivity
                );
                GriefPrevention.AddLogEntry(notificationMessage, CustomLogEntryTypes.MutedChat, false);
                instance.dataStore.toggleSoftMute(player.getUniqueId());
            }
        }
        // remaining messages
        else {
            // enter in abridged chat logs
            makeSocialLogEntry(player.getName(), message);

            // based on ignore lists, remove some of the audience
            if (!player.hasPermission("griefprevention.notignorable")) {
                Set<Player> recipientsToRemove = new HashSet<>();
                PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
                for (Player recipient : recipients) {
                    if (!recipient.hasPermission("griefprevention.notignorable")) {
                        if (playerData.ignoredPlayers.containsKey(recipient.getUniqueId())) {
                            recipientsToRemove.add(recipient);
                        } else {
                            PlayerData targetPlayerData = this.dataStore.getPlayerData(recipient.getUniqueId());
                            if (targetPlayerData.ignoredPlayers.containsKey(player.getUniqueId())) {
                                recipientsToRemove.add(recipient);
                            }
                        }
                    }
                }

                recipients.removeAll(recipientsToRemove);
            }
        }
    }

    // returns true if the message should be muted, true if it should be sent
    private boolean handlePlayerChat(Player player, String message, PlayerEvent event) {
        // FEATURE: automatically educate players about claiming land
        // watching for message format how*claim*, and will send a link to the basics
        // video
        if (this.howToClaimPattern == null) {
            this.howToClaimPattern = Pattern.compile(
                this.dataStore.getMessage(Messages.HowToClaimRegex),
                Pattern.CASE_INSENSITIVE
            );
        }

        if (this.howToClaimPattern.matcher(message).matches()) {
            if (instance.creativeRulesApply(player.getLocation())) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Info,
                    Messages.CreativeBasicsVideo2,
                    10L,
                    DataStore.CREATIVE_VIDEO_URL
                );
            } else {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Info,
                    Messages.SurvivalBasicsVideo2,
                    10L,
                    DataStore.SURVIVAL_VIDEO_URL
                );
            }
        }

        // FEATURE: automatically educate players about the /trapped command
        // check for "trapped" or "stuck" to educate players about the /trapped command
        String trappedwords = this.dataStore.getMessage(Messages.TrappedChatKeyword);
        if (!trappedwords.isEmpty()) {
            String[] checkWords = trappedwords.split(";");

            for (String checkWord : checkWords) {
                if (!message.contains("/trapped") && message.contains(checkWord)) {
                    GriefPrevention.sendMessage(player, TextMode.Info, Messages.TrappedInstructions, 10L);
                    break;
                }
            }
        }

        // FEATURE: monitor for chat and command spam

        if (!instance.config_spam_enabled) return false;

        // if the player has permission to spam, don't bother even examining the message
        if (player.hasPermission("griefprevention.spam")) return false;

        // examine recent messages to detect spam
        SpamAnalysisResult result = this.spamDetector.AnalyzeMessage(
            player.getUniqueId(),
            message,
            System.currentTimeMillis()
        );

        // apply any needed changes to message (like lowercasing all-caps)
        if (event instanceof AsyncPlayerChatEvent) {
            ((AsyncPlayerChatEvent) event).setMessage(result.finalMessage);
        }

        // don't allow new players to chat after logging in until they move
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        if (playerData.noChatLocation != null) {
            Location currentLocation = player.getLocation();
            if (
                currentLocation.getBlockX() == playerData.noChatLocation.getBlockX() &&
                currentLocation.getBlockZ() == playerData.noChatLocation.getBlockZ()
            ) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoChatUntilMove, 10L);
                result.muteReason = "pre-movement chat";
            } else {
                playerData.noChatLocation = null;
            }
        }

        // filter IP addresses
        if (result.muteReason == null) {
            if (instance.containsBlockedIP(message)) {
                // block message
                result.muteReason = "IP address";
            }
        }

        // take action based on spam detector results
        if (result.shouldBanChatter) {
            if (instance.config_spam_banOffenders) {
                // log entry
                GriefPrevention.AddLogEntry(
                    "Banning " + player.getName() + " for spam.",
                    CustomLogEntryTypes.AdminActivity
                );

                // kick and ban
                PlayerKickBanTask task = new PlayerKickBanTask(
                    player,
                    instance.config_spam_banMessage,
                    "GriefPrevention Anti-Spam",
                    true
                );
                SchedulerUtil.runLaterEntity(instance, player, task::run, 1L);
            } else {
                // log entry
                GriefPrevention.AddLogEntry(
                    "Kicking " + player.getName() + " for spam.",
                    CustomLogEntryTypes.AdminActivity
                );

                // just kick
                PlayerKickBanTask task = new PlayerKickBanTask(player, "", "GriefPrevention Anti-Spam", false);
                SchedulerUtil.runLaterEntity(instance, player, task::run, 1L);
            }
        } else if (result.shouldWarnChatter) {
            // warn and log
            GriefPrevention.sendMessage(player, TextMode.Warn, instance.config_spam_warningMessage, 10L);
            GriefPrevention.AddLogEntry(
                "Warned " + player.getName() + " about spam penalties.",
                CustomLogEntryTypes.Debug,
                true
            );
        }

        if (result.muteReason != null) {
            // mute and log
            GriefPrevention.AddLogEntry("Muted " + result.muteReason + ".");
            GriefPrevention.AddLogEntry(
                "Muted " + player.getName() + " " + result.muteReason + ":" + message,
                CustomLogEntryTypes.Debug,
                true
            );

            return true;
        }

        return false;
    }

    // when a player uses a slash command...
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    synchronized void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        MonitorableCommand command = new MonitorableCommand(event.getMessage());

        CommandCategory category = this.getCommandCategory(command);

        Player player = event.getPlayer();
        PlayerData playerData = null;

        // if a whisper
        if (category == CommandCategory.Whisper && command.getArgumentCount() > 1) {
            // determine target player, might be NULL

            Player targetPlayer = instance.getServer().getPlayer(command.getArgument(0));

            // softmute feature
            if (
                this.dataStore.isSoftMuted(player.getUniqueId()) &&
                targetPlayer != null &&
                !this.dataStore.isSoftMuted(targetPlayer.getUniqueId())
            ) {
                event.setCancelled(true);
                return;
            }

            // if eavesdrop enabled and sender doesn't have the eavesdrop immunity
            // permission, eavesdrop
            if (instance.config_whisperNotifications && !player.hasPermission("griefprevention.eavesdropimmune")) {
                // except for when the recipient has eavesdrop immunity
                if (targetPlayer == null || !targetPlayer.hasPermission("griefprevention.eavesdropimmune")) {
                    String logMessage =
                        "[[" +
                        event.getPlayer().getName() +
                        "]] " +
                        command.getCommand().substring(command.getCommand(0).length() + 1);

                    @SuppressWarnings("unchecked")
                    Collection<Player> players = (Collection<Player>) instance.getServer().getOnlinePlayers();
                    for (Player onlinePlayer : players) {
                        if (
                            onlinePlayer.hasPermission("griefprevention.eavesdrop.pm") &&
                            !onlinePlayer.equals(targetPlayer) &&
                            !onlinePlayer.equals(player)
                        ) {
                            onlinePlayer.sendMessage(ChatColor.GRAY + logMessage);
                        }
                    }
                }
            }

            // ignore feature
            if (targetPlayer != null && targetPlayer.isOnline()) {
                // if either is ignoring the other, cancel this command
                playerData = this.dataStore.getPlayerData(player.getUniqueId());
                if (
                    playerData.ignoredPlayers.containsKey(targetPlayer.getUniqueId()) &&
                    !targetPlayer.hasPermission("griefprevention.notignorable")
                ) {
                    event.setCancelled(true);
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.IsIgnoringYou);
                    return;
                }

                PlayerData targetPlayerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
                if (
                    targetPlayerData.ignoredPlayers.containsKey(player.getUniqueId()) &&
                    !player.hasPermission("griefprevention.notignorable")
                ) {
                    event.setCancelled(true);
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.IsIgnoringYou);
                    return;
                }
            }
        }

        // if in pvp, block any pvp-banned slash commands
        if (playerData == null) playerData = this.dataStore.getPlayerData(event.getPlayer().getUniqueId());

        if ((playerData.inPvpCombat() || playerData.siegeData != null)
                && pvpBlockedCommands.isMonitoredCommand(command)) {
            event.setCancelled(true);
            GriefPrevention.sendMessage(event.getPlayer(), TextMode.Err, Messages.CommandBannedInPvP);
            return;
        }

        // soft mute for chat slash commands
        if (category == CommandCategory.Chat && this.dataStore.isSoftMuted(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        // if the slash command used is in the list of monitored commands, treat it like
        // a chat message (see above)
        boolean isMonitoredCommand = category == CommandCategory.Chat || category == CommandCategory.Whisper;
        if (isMonitoredCommand) {
            // if anti spam enabled, check for spam
            if (instance.config_spam_enabled) {
                event.setCancelled(this.handlePlayerChat(event.getPlayer(), event.getMessage(), event));
            }

            if (!player.hasPermission("griefprevention.spam") && this.bannedWordFinder.hasMatch(event.getMessage())) {
                event.setCancelled(true);
            }

            // unless cancelled, log in abridged logs
            if (!event.isCancelled()) {
                makeSocialLogEntry(event.getPlayer().getName(), event.getMessage());
            }
        }

        // if requires access trust, check for permission
        if (accessTrustCommands.isMonitoredCommand(command)) {
            Claim deepestClaim = findDeepestContainingClaim(playerData.lastClaim, player.getLocation());
            if (deepestClaim != null) {
                playerData.lastClaim = deepestClaim;
                Supplier<String> reason = deepestClaim.checkPermission(player, ClaimPermission.Access, event);
                if (reason != null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, reason.get());
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    Claim findDeepestContainingClaim(
        @org.jetbrains.annotations.Nullable Claim start,
        @org.jetbrains.annotations.Nullable Location location
    ) {
        if (start == null || location == null) {
            return start;
        }

        Claim current = start;
        while (true) {
            Claim bestChild = null;

            for (Claim child : current.children) {
                if (child == null || !child.inDataStore) {
                    continue;
                }

                if (!child.contains(location, false, false)) {
                    continue;
                }

                if (bestChild == null || isMoreSpecificChild(child, bestChild, location)) {
                    bestChild = child;
                }
            }

            if (bestChild == null) {
                break;
            }

            current = bestChild;
        }

        return current;
    }

    private boolean isMoreSpecificChild(
        @org.jetbrains.annotations.NotNull Claim candidate,
        @org.jetbrains.annotations.NotNull Claim incumbent,
        @org.jetbrains.annotations.NotNull Location location
    ) {
        boolean candidate3D = candidate.is3D();
        boolean incumbent3D = incumbent.is3D();

        if (candidate3D != incumbent3D) {
            if (candidate3D && candidate.containsY(location.getBlockY())) {
                return true;
            }
            if (incumbent3D && incumbent.containsY(location.getBlockY())) {
                return false;
            }
        }

        if (candidate3D && incumbent3D) {
            int candidateHeight =
                candidate.getGreaterBoundaryCorner().getBlockY() - candidate.getLesserBoundaryCorner().getBlockY();
            int incumbentHeight =
                incumbent.getGreaterBoundaryCorner().getBlockY() - incumbent.getLesserBoundaryCorner().getBlockY();
            if (candidateHeight != incumbentHeight) {
                return candidateHeight < incumbentHeight;
            }
        }

        return candidate.getArea() < incumbent.getArea();
    }

    private CommandCategory getCommandCategory(MonitorableCommand command) {
        if (whisperCommands.isMonitoredCommand(command)) return CommandCategory.Whisper;
        if (chatCommands.isMonitoredCommand(command)) return CommandCategory.Chat;
        return CommandCategory.None;
    }

    static int longestNameLength = 10;

    static void makeSocialLogEntry(String name, String message) {
        StringBuilder entryBuilder = new StringBuilder(name);
        entryBuilder.append(Compat.repeat(" ", Math.max(0, longestNameLength - name.length())));
        entryBuilder.append(": ").append(message);

        longestNameLength = Math.max(longestNameLength, name.length());
        GriefPrevention.AddLogEntry(entryBuilder.toString(), CustomLogEntryTypes.SocialActivity, true);
    }

    private final ConcurrentHashMap<UUID, Date> lastLoginThisServerSessionMap = new ConcurrentHashMap<>();

    // when a player attempts to join the server...
    //
    // Paper 26.x deprecates listening to PlayerLoginEvent because it fires between
    // the login and configuration phases, which blocks Paper's re-configuration APIs
    // for everyone else. AsyncPlayerPreLoginEvent runs earlier (async, before
    // configuration) and exposes the same uuid/name/address/result surface we need
    // for cooldown kicks and smart-ban IP tracking. IP recording into PlayerData is
    // moved to onPlayerJoin so DataStore writes stay on the main thread.
    @EventHandler(priority = EventPriority.HIGHEST)
    void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!instance.config_spam_enabled) return;

        long now = Calendar.getInstance().getTimeInMillis();
        UUID uuid = event.getUniqueId();
        AsyncPlayerPreLoginEvent.Result result = event.getLoginResult();

        // FEATURE: login cooldown to prevent login/logout spam with custom clients
        if (
            instance.config_spam_loginCooldownSeconds > 0 &&
            result == AsyncPlayerPreLoginEvent.Result.ALLOWED &&
            !hasSpamBypass(uuid)
        ) {
            Date lastLoginThisSession = lastLoginThisServerSessionMap.get(uuid);
            if (lastLoginThisSession != null) {
                long millisecondsSinceLastLogin = now - lastLoginThisSession.getTime();
                long secondsSinceLastLogin = millisecondsSinceLastLogin / 1000;
                long cooldownRemaining = instance.config_spam_loginCooldownSeconds - secondsSinceLastLogin;

                if (cooldownRemaining > 0) {
                    event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "You must wait " + cooldownRemaining + " seconds before logging-in again."
                    );
                    return;
                }
            }
        }

        // if logging-in account is banned, remember IP address for later
        if (instance.config_smartBan && result == AsyncPlayerPreLoginEvent.Result.KICK_BANNED) {
            this.tempBannedIps.add(new IpBanInfo(event.getAddress(), now + this.MILLISECONDS_IN_DAY, event.getName()));
        }
    }

    // Offline-safe bypass check for the griefprevention.spam permission. We cannot
    // run Player#hasPermission during AsyncPlayerPreLoginEvent because there is no
    // Player yet, so fall back to the ops file.
    // UserManager hook here so permission-manager-based bypass works for non-ops.
    private boolean hasSpamBypass(UUID uuid) {
        try {
            return Bukkit.getOfflinePlayer(uuid).isOp();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // when a player successfully joins the server...

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerID = player.getUniqueId();

        // note login time
        Date nowDate = new Date();
        long now = nowDate.getTime();
        PlayerData playerData = this.dataStore.getPlayerData(playerID);
        playerData.lastSpawn = now;
        this.lastLoginThisServerSessionMap.put(playerID, nowDate);

        // remember the player's ip address (moved from PlayerLoginEvent; Paper 26
        // deprecates that event, and writes to DataStore should stay on the main thread)
        java.net.InetSocketAddress socketAddress = player.getAddress();
        if (socketAddress != null) {
            playerData.ipAddress = socketAddress.getAddress();
        }

        // detect player's client locale for per-player message support
        playerData.locale = CompatUtil.getLocale(player);

        // if newish, prevent chat until he's moved a bit to prove he's not a bot
        if (GriefPrevention.isNewToServer(player) && !player.hasPermission("griefprevention.premovementchat")) {
            playerData.noChatLocation = player.getLocation();
        }

        // if player has never played on the server before...
        if (!player.hasPlayedBefore()) {
            // may need pvp protection
            instance.checkPvpProtectionNeeded(player);

            // if in survival claims mode, send a message about the claim basics video
            // (except for admins - assumed experts)
            if (
                instance.config_claims_manualDeliveryDelaySeconds > 0 &&
                instance.config_claims_worldModes.get(player.getWorld()) == ClaimsMode.Survival &&
                !player.hasPermission("griefprevention.adminclaims") &&
                this.dataStore.claims.size() > 10
            ) {
                WelcomeTask task = new WelcomeTask(player.getUniqueId());
                SchedulerUtil.runLaterEntity(
                    instance,
                    player,
                    task::run,
                    instance.config_claims_manualDeliveryDelaySeconds * 20L
                );
            }
        }

        // silence notifications when they're coming too fast
        if (event.getJoinMessage() != null && this.shouldSilenceNotification()) {
            event.setJoinMessage(null);
        }

        // FEATURE: auto-ban accounts who use an IP address which was very recently used
        // by another banned account
        if (instance.config_smartBan && !player.hasPlayedBefore()) {
            // search temporarily banned IP addresses for this one
            for (int i = 0; i < this.tempBannedIps.size(); i++) {
                IpBanInfo info = this.tempBannedIps.get(i);
                String address = info.address.toString();

                // eliminate any expired entries
                if (now > info.expirationTimestamp) {
                    this.tempBannedIps.remove(i--);
                }
                // if we find a match
                else if (address.equals(playerData.ipAddress.toString())) {
                    // if the account associated with the IP ban has been pardoned, remove all ip
                    // bans for that ip and we're done
                    @SuppressWarnings("deprecation")
                    OfflinePlayer bannedPlayer = instance.getServer().getOfflinePlayer(info.bannedAccountName);
                    if (!bannedPlayer.isBanned()) {
                        for (int j = 0; j < this.tempBannedIps.size(); j++) {
                            IpBanInfo info2 = this.tempBannedIps.get(j);
                            if (info2.address.toString().equals(address)) {
                                @SuppressWarnings("deprecation")
                                OfflinePlayer bannedAccount = instance
                                    .getServer()
                                    .getOfflinePlayer(info2.bannedAccountName);
                                CompatUtil.pardon(bannedAccount);
                                this.tempBannedIps.remove(j--);
                            }
                        }

                        break;
                    }
                    // otherwise if that account is still banned, ban this account, too
                    else {
                        GriefPrevention.AddLogEntry(
                            "Auto-banned new player " +
                                player.getName() +
                                " because that account is using an IP address very recently used by banned player " +
                                info.bannedAccountName +
                                " (" +
                                info.address.toString() +
                                ").",
                            CustomLogEntryTypes.AdminActivity
                        );

                        // notify any online ops
                        @SuppressWarnings("unchecked")
                        Collection<Player> players = (Collection<Player>) instance.getServer().getOnlinePlayers();
                        for (Player otherPlayer : players) {
                            if (otherPlayer.isOp()) {
                                GriefPrevention.sendMessage(
                                    otherPlayer,
                                    TextMode.Success,
                                    Messages.AutoBanNotify,
                                    player.getName(),
                                    info.bannedAccountName
                                );
                            }
                        }

                        // ban player
                        PlayerKickBanTask task = new PlayerKickBanTask(
                            player,
                            "",
                            "GriefPrevention Smart Ban - Shared Login:" + info.bannedAccountName,
                            true
                        );
                        SchedulerUtil.runLaterEntity(instance, player, task::run, 10L);

                        // silence join message
                        event.setJoinMessage("");

                        break;
                    }
                }
            }
        }

        // in case player has changed his name, on successful login, update UUID > Name
        // mapping
        GriefPrevention.cacheUUIDNamePair(player.getUniqueId(), player.getName());

        // ensure we're not over the limit for this IP address
        InetAddress ipAddress = playerData.ipAddress;
        if (ipAddress != null) {
            int ipLimit = instance.config_ipLimit;
            if (ipLimit > 0 && GriefPrevention.isNewToServer(player)) {
                int ipCount = 0;

                @SuppressWarnings("unchecked")
                Collection<Player> players = (Collection<Player>) instance.getServer().getOnlinePlayers();
                for (Player onlinePlayer : players) {
                    if (onlinePlayer.getUniqueId().equals(player.getUniqueId())) continue;

                    PlayerData otherData = instance.dataStore.getPlayerData(onlinePlayer.getUniqueId());
                    if (ipAddress.equals(otherData.ipAddress) && GriefPrevention.isNewToServer(onlinePlayer)) {
                        ipCount++;
                    }
                }

                if (ipCount >= ipLimit) {
                    // kick player
                    PlayerKickBanTask task = new PlayerKickBanTask(
                        player,
                        instance.dataStore.getMessage(Messages.TooMuchIpOverlap),
                        "GriefPrevention IP-sharing limit.",
                        false
                    );
                    SchedulerUtil.runLaterEntity(instance, player, task::run, 100L);

                    // silence join message
                    event.setJoinMessage(null);
                    return;
                }
            }
        }

        // create a thread to load ignore information
        new IgnoreLoaderThread(playerID, playerData.ignoredPlayers).start();

        // is he stuck in a portal frame?
        if (player.hasMetadata("GP_PORTALRESCUE")) {
            // If so, let him know and rescue him in 10 seconds. If he is in fact not
            // trapped, hopefully chunks will have loaded by this time so he can walk out.
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.NetherPortalTrapDetectionMessage, 20L);
            SchedulerUtil.runLaterEntity(
                instance,
                player,
                () -> {
                    try {
                        if (player.getPortalCooldown() > 8 && player.hasMetadata("GP_PORTALRESCUE")) {
                            GriefPrevention.AddLogEntry(
                                "Rescued " +
                                    player.getName() +
                                    " from a nether portal.\nTeleported from " +
                                    GriefPrevention.getfriendlyLocationString(player.getLocation()) +
                                    " to " +
                                    GriefPrevention.getfriendlyLocationString(
                                        (Location) player.getMetadata("GP_PORTALRESCUE").get(0).value()
                                    ),
                                CustomLogEntryTypes.Debug
                            );
                            player.teleport((Location) player.getMetadata("GP_PORTALRESCUE").get(0).value());
                            player.removeMetadata("GP_PORTALRESCUE", instance);
                        }
                    } catch (NoSuchMethodError ignore) {}
                },
                200L
            );
        }
        // Otherwise just reset cooldown, just in case they happened to logout again...
        else CompatUtil.setPortalCooldown(player, 0);

        // if we're holding a logout message for this player, don't send that or this
        // event's join message
        if (instance.config_spam_logoutMessageDelaySeconds > 0) {
            String joinMessage = event.getJoinMessage();
            if (joinMessage != null && !joinMessage.isEmpty()) {
                TaskHandle taskID = this.heldLogoutMessages.get(player.getUniqueId());
                if (taskID != null && taskID.isScheduled()) {
                    taskID.cancel();
                    player.sendMessage(event.getJoinMessage());
                    event.setJoinMessage("");
                }
            }
        }
    }

    // when a player spawns, conditionally apply temporary pvp protection
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
        playerData.lastSpawn = Calendar.getInstance().getTimeInMillis();
        playerData.lastPvpTimestamp = 0; // no longer in pvp combat

        // also send him any messaged from grief prevention he would have received while
        // dead
        if (playerData.messageOnRespawn != null) {
            GriefPrevention.sendMessage(
                player,
                ChatColor.RESET /* color is alrady embedded in message in this case */,
                playerData.messageOnRespawn,
                40L
            );
            playerData.messageOnRespawn = null;
        }

        instance.checkPvpProtectionNeeded(player);
    }

    // when a player dies...
    private final HashMap<UUID, Long> deathTimestamps = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    void onPlayerDeath(PlayerDeathEvent event) {
        // FEATURE: prevent death message spam by implementing a "cooldown period" for
        // death messages
        Player player = event.getEntity();
        Long lastDeathTime = this.deathTimestamps.get(player.getUniqueId());
        long now = Calendar.getInstance().getTimeInMillis();
        if (
            lastDeathTime != null &&
            now - lastDeathTime < instance.config_spam_deathMessageCooldownSeconds * 1000 &&
            event.getDeathMessage() != null
        ) {
            player.sendMessage(event.getDeathMessage()); // let the player assume his death message was broadcasted to
            // everyone
            event.setDeathMessage(null);
        }

        this.deathTimestamps.put(player.getUniqueId(), now);

        // these are related to locking dropped items on death to prevent theft
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        playerData.dropsAreUnlocked = false;
        playerData.receivedDropUnlockAdvertisement = false;

    }

    // when a player gets kicked...
    @EventHandler(priority = EventPriority.HIGHEST)
    void onPlayerKicked(PlayerKickEvent event) {
        Player player = event.getPlayer();
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        playerData.wasKicked = true;
    }

    // when a player quits...
    private final HashMap<UUID, TaskHandle> heldLogoutMessages = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerID = player.getUniqueId();
        PlayerData playerData = this.dataStore.getPlayerData(playerID);
        boolean isBanned;

        // If player is not trapped in a portal and has a pending rescue task, remove
        // the associated metadata
        // Why 9? No idea why, but this is decremented by 1 when the player disconnects.
        try {
            if (player.getPortalCooldown() < 9) {
                player.removeMetadata("GP_PORTALRESCUE", instance);
            }
        } catch (NoSuchMethodError ignore) {}

        if (playerData.wasKicked) {
            isBanned = player.isBanned();
        } else {
            isBanned = false;
        }

        // if banned, add IP to the temporary IP ban list
        if (isBanned && playerData.ipAddress != null) {
            long now = Calendar.getInstance().getTimeInMillis();
            this.tempBannedIps.add(
                new IpBanInfo(playerData.ipAddress, now + this.MILLISECONDS_IN_DAY, player.getName())
            );
        }

        // silence notifications when they're coming too fast
        if (event.getQuitMessage() != null && this.shouldSilenceNotification()) {
            event.setQuitMessage(null);
        }

        // silence notifications when the player is banned
        if (isBanned && instance.config_silenceBans) {
            event.setQuitMessage(null);
        }
        // make sure his data is all saved - he might have accrued some claim blocks
        // while playing that were not saved immediately
        else {
            this.dataStore.savePlayerData(player.getUniqueId(), playerData);
        }

        // FEATURE: players in pvp combat when they log out will die
        if (instance.config_pvp_punishLogout && playerData.inPvpCombat()) {
            player.setHealth(0);
        }

        // Logging out forfeits an active siege, even when normal PvP logout punishment is off.
        if (playerData.siegeData != null) {
            SiegeData siege = playerData.siegeData;
            if (player.getHealth() > 0) player.setHealth(0);
            if (!siege.ended) this.dataStore.endSiege(siege, null, player.getName(), null);
        }

        // drop data about this player
        this.dataStore.clearCachedPlayerData(playerID);

        // send quit message later, but only if the player stays offline
        if (instance.config_spam_logoutMessageDelaySeconds > 0) {
            String quitMessage = event.getQuitMessage();
            if (quitMessage != null && !quitMessage.isEmpty()) {
                BroadcastMessageTask task = new BroadcastMessageTask(quitMessage);
                TaskHandle handle = SchedulerUtil.runLaterGlobal(
                    instance,
                    task,
                    20L * instance.config_spam_logoutMessageDelaySeconds
                );
                this.heldLogoutMessages.put(playerID, handle);
                event.setQuitMessage("");
            }
        }
    }

    // determines whether or not a login or logout notification should be silenced,
    // depending on how many there have been in the last minute
    private boolean shouldSilenceNotification() {
        if (instance.config_spam_loginLogoutNotificationsPerMinute <= 0) {
            return false; // not silencing login/logout notifications
        }

        final long ONE_MINUTE = 60000;
        Long now = Calendar.getInstance().getTimeInMillis();

        // eliminate any expired entries (longer than a minute ago)
        for (int i = 0; i < this.recentLoginLogoutNotifications.size(); i++) {
            Long notificationTimestamp = this.recentLoginLogoutNotifications.get(i);
            if (now - notificationTimestamp > ONE_MINUTE) {
                this.recentLoginLogoutNotifications.remove(i--);
            } else {
                break;
            }
        }

        // add the new entry
        this.recentLoginLogoutNotifications.add(now);

        return this.recentLoginLogoutNotifications.size() > instance.config_spam_loginLogoutNotificationsPerMinute;
    }

    // when a player drops an item
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        // in creative worlds, dropping items is blocked
        if (instance.creativeRulesApply(player.getLocation())) {
            event.setCancelled(true);
            return;
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        if (playerData.siegeData != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.SiegeNoDrop);
            event.setCancelled(true);
            return;
        }

        // FEATURE: players under siege or in PvP combat, can't throw items on the
        // ground to hide
        // them or give them away to other players before they are defeated

        // if in combat, don't let him drop it
        if (!instance.config_pvp_allowCombatItemDrop && playerData.inPvpCombat() && !player.isDead()) {
            ItemStack dropped = event.getItemDrop().getItemStack();

            // A cancelled drop is returned to the main inventory by the server, but only if a
            // main slot is free or can absorb the stack into an existing one. Main-hand drops are
            // always safe: the server splits the stack out of the selected hotbar slot, so
            // cancelling puts it right back. A drop from the cursor or an armor or offhand slot
            // has no main slot to return to and would be silently deleted when the main inventory
            // is full (see GriefPrevention/GriefPrevention#2619, PaperMC/Paper#7726). The
            // inventory click handler below blocks those drops at the click, so anything still
            // reaching here (a leftover cursor drop on inventory close, or a hack client) falls
            // like vanilla rather than being destroyed.
            if (instance.getItemInHand(player, EquipmentSlot.HAND).getType() != Material.AIR && !mainInventoryAccepts(player, dropped)) {
                return;
            }

            GriefPrevention.sendMessage(player, TextMode.Err, Messages.PvPNoDrop);
            event.setCancelled(true);
        }
    }

    // when a player drops an item from a slot or off their cursor in an inventory window
    @EventHandler(priority = EventPriority.LOWEST)
    void onInventoryClick(InventoryClickEvent event) {
        // only the "drop" actions move an item out of a slot or off the cursor into the world
        switch (event.getAction()) {
            case DROP_ONE_SLOT:
            case DROP_ALL_SLOT:
            case DROP_ONE_CURSOR:
            case DROP_ALL_CURSOR:
                break;
            default:
                return;
        }

        // players only; anything else has no combat state
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // If in combat or siege, don't let the inventory click drop the item.
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        boolean siege = playerData.siegeData != null;
        boolean blockedByPvp = !instance.config_pvp_allowCombatItemDrop && playerData.inPvpCombat();
        if ((!siege && !blockedByPvp) || player.isDead()) return;

        // Block the click rather than the resulting PlayerDropItemEvent. A cancelled drop is
        // returned to the main inventory by the server, but an item dropped from the cursor or an
        // armor or offhand slot has no main slot to return to and is silently deleted when the
        // main inventory is full (see GriefPrevention/GriefPrevention#2619, PaperMC/Paper#7726).
        // The click is never applied, so the item simply stays where it is.
        GriefPrevention.sendMessage(player, TextMode.Err, siege ? Messages.SiegeNoDrop : Messages.PvPNoDrop);
        event.setCancelled(true);
    }

    // when a player closes an inventory window with an item on their cursor, the server drops that
    // item onto the ground. During PvP combat that drop is blocked, and a cursor item with no room
    // to return to in the main inventory would be silently deleted. Put the cursor item into the
    // inventory instead, so only the portion that cannot fit stays on the cursor and is dropped
    // (and that leftover is covered by the safety net in onPlayerDropItem).
    @EventHandler(priority = EventPriority.LOWEST)
    void onInventoryClose(InventoryCloseEvent event) {
        // players only; anything else has no combat state
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();

        // if the server already dropped the cursor item, there is nothing left to save
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) return;

        // only while inventory drops are blocked by PvP combat or siege
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        boolean siege = playerData.siegeData != null;
        boolean blockedByPvp = !instance.config_pvp_allowCombatItemDrop && playerData.inPvpCombat();
        if ((!siege && !blockedByPvp) || player.isDead()) return;

        // place the cursor item into the main inventory; only the portion that cannot fit stays
        // on the cursor and is dropped by the server
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(cursor);
        player.setItemOnCursor(leftover.isEmpty() ? new ItemStack(Material.AIR) : leftover.values().iterator().next());
    }

    // whether the main inventory can place the dropped stack in one of its 36 slots: a free slot,
    // or an existing stack of the same material with room to merge (mirrors the server's
    // cancel-reversal, which only ever returns a cancelled drop to the main inventory)
    private static boolean mainInventoryAccepts(Player player, ItemStack dropped) {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) return true;
            if (item.isSimilar(dropped) && item.getAmount() < item.getMaxStackSize()) return true;
        }
        return false;
    }

    // when a player teleports via a portal
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    void onPlayerPortal(PlayerPortalEvent event) {
        // if the player isn't going anywhere, take no action
        if (event.getTo() == null || event.getTo().getWorld() == null) return;

        Player player = event.getPlayer();
        if (event.getCause() == TeleportCause.NETHER_PORTAL) {
            // FEATURE: when players get trapped in a nether portal, send them back through
            // to the other side
            instance.startRescueTask(player, player.getLocation());

            // don't track in worlds where claims are not enabled
            if (!instance.claimsEnabledForWorld(event.getTo().getWorld())) return;
        }
    }

    // when a player teleports
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        if (instance.siegeEnabledForWorld(player.getWorld())
                && !player.hasPermission("griefprevention.siegeteleport")
                && event.getCause() != TeleportCause.UNKNOWN) {
            Claim sourceClaim = this.dataStore.getClaimAt(event.getFrom(), false, playerData.lastClaim);
            if (sourceClaim != null && sourceClaim.siegeData != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.SiegeNoTeleport);
                event.setCancelled(true);
                return;
            }
            Claim destinationClaim = this.dataStore.getClaimAt(event.getTo(), false, null);
            if (destinationClaim != null && destinationClaim.siegeData != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.BesiegedNoTeleport);
                event.setCancelled(true);
                return;
            }
        }

        // Get the claim at the destination
        Claim toClaim = this.dataStore.getClaimAt(event.getTo(), false, playerData.lastClaim);

        // Get the claim at the original location
        Claim fromClaim = playerData.lastClaim;

        // Special handling for ender pearls and chorus fruit to prevent gaining access
        // to secured claims. Must run before updating lastClaim so we don't corrupt
        // player state when cancelling. Use ProtectionHelper for proper 3D claim and
        // parent inheritance handling.
        if (instance.config_claims_enderPearlsRequireAccessTrust) {
            TeleportCause cause = event.getCause();
            boolean isChorusFruitOrEnderPearl = cause == TeleportCause.ENDER_PEARL;
            try {
                // Try to check for CHORUS_FRUIT (1.9+)
                isChorusFruitOrEnderPearl = isChorusFruitOrEnderPearl || cause == TeleportCause.CHORUS_FRUIT;
            } catch (NoSuchFieldError e) {
                // 1.8.8: CHORUS_FRUIT doesn't exist
            }
            if (isChorusFruitOrEnderPearl) {
                // Preserve the player's camera direction (yaw/pitch) when teleporting.
                // Some server software sets these to match the projectile's direction
                // instead of preserving the player's original look direction.
                Location to = event.getTo();
                to.setYaw(event.getFrom().getYaw());
                to.setPitch(event.getFrom().getPitch());

                Supplier<String> noAccessReason = ProtectionHelper.checkPermission(
                    player,
                    to,
                    ClaimPermission.Access,
                    event
                );
                if (noAccessReason != null) {
                    // Skip message if ProjectileHitEvent already handled it (Purpur/Folia: both events fire)
                    if (!refundedByProjectileHitEvent.contains(player.getUniqueId())) {
                        GriefPrevention.sendMessage(player, TextMode.Err, noAccessReason.get());
                    }
                    event.setCancelled(true);
                    if (
                        cause == TeleportCause.ENDER_PEARL &&
                        instance.config_claims_refundDeniedEnderPearls &&
                        !refundedByProjectileHitEvent.contains(player.getUniqueId())
                    ) {
                        refundedByTeleportEvent.add(player.getUniqueId());
                        SchedulerUtil.runLaterGlobal(
                            instance,
                            () -> refundedByTeleportEvent.remove(player.getUniqueId()),
                            15L
                        );
                        player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
                    }
                    return; // Don't update lastClaim when teleport is cancelled
                }
            }
        }

        // Update the lastClaim to the new location (only when teleport proceeds)
        playerData.lastClaim = toClaim;

        // If we're moving from one claim to another, or from a claim to wilderness,
        // we need to update the player's permissions
        if (fromClaim != toClaim) {
            try {
                player.updateCommands();
            } catch (NoSuchMethodError ignore) {
                // Pre-1.13: updateCommands() doesn't exist
            }
        }
    }

    // Prevent endermite spawn at rollback location when pearl was denied in claim (Canvas: endermite spawns at player).
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCreatureSpawnEndermite(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.ENDERMITE) return;
        if (event.getSpawnReason() != SpawnReason.ENDER_PEARL) return;
        if (!instance.config_claims_enderPearlsRequireAccessTrust) return;
        if (!instance.claimsEnabledForWorld(event.getLocation().getWorld())) return;

        Location spawnLoc = event.getLocation();
        Claim claim = this.dataStore.getClaimAt(spawnLoc, false, null);
        if (claim == null) return;

        for (UUID playerID : recentPearlRollbackPlayers) {
            Player p = instance.getServer().getPlayer(playerID);
            if (
                p != null &&
                p.isOnline() &&
                p.getWorld().equals(spawnLoc.getWorld()) &&
                p.getLocation().distanceSquared(spawnLoc) <= 25
            ) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // Cancel projectile landing when access denied - prevents endermite spawn and entity damage.
    // Upstream uses PlayerTeleportEvent cancel; on Canvas that doesn't fire, so we cancel here.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onProjectileHitEnderPearlCancel(ProjectileHitEvent event) {
        if (event.getEntity().getType() != EntityType.ENDER_PEARL) return;
        if (!instance.config_claims_enderPearlsRequireAccessTrust) return;
        if (!(event.getEntity().getShooter() instanceof Player)) return;
        Player shooter = (Player) event.getEntity().getShooter();
        if (!instance.claimsEnabledForWorld(event.getEntity().getWorld())) return;

        Block hitBlock = CompatUtil.getHitBlock(event);
        Location destLoc = hitBlock != null ? hitBlock.getLocation().add(0.5, 1, 0.5) : event.getEntity().getLocation();
        Supplier<String> noAccessReason = ProtectionHelper.checkPermission(
            shooter,
            destLoc,
            ClaimPermission.Access,
            null
        );
        if (noAccessReason != null) {
            // ProjectileHitEvent only became cancellable in 1.20.2; the rollback below still
            // returns the player, so older versions degrade to a teleport-and-return.
            CompatUtil.cancelIfPossible(event);
            // Skip refund if PlayerTeleportEvent already refunded (Purpur etc have both events fire)
            if (refundedByTeleportEvent.contains(shooter.getUniqueId())) return;
            // Only message/refund once - ProjectileHitEvent can fire multiple times when pearl hits entity
            UUID pearlID = event.getEntity().getUniqueId();
            if (refundedEnderPearlEntities.add(pearlID)) {
                refundedByProjectileHitEvent.add(shooter.getUniqueId());
                SchedulerUtil.runLaterGlobal(
                    instance,
                    () -> refundedByProjectileHitEvent.remove(shooter.getUniqueId()),
                    15L
                );
                recentPearlRollbackPlayers.add(shooter.getUniqueId());
                SchedulerUtil.runLaterGlobal(
                    instance,
                    () -> recentPearlRollbackPlayers.remove(shooter.getUniqueId()),
                    20L
                );
                GriefPrevention.sendMessage(shooter, TextMode.Err, noAccessReason.get());
                if (instance.config_claims_refundDeniedEnderPearls) {
                    shooter.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
                }
                // Never remove - stasis chambers keep pearl in motion, triggering many events over time
            }
        }
    }

    /** Folia/Canvas: use teleportAsync when in region threading. */
    private static void teleportFoliaSafe(Player player, Location location) {
        CompatUtil.teleportSafely(player, location);
    }

    // Canvas fallback: PlayerTeleportEvent may not fire for ender pearls. Run rollback at tick+1
    // and tick+2 to minimize the window where the player can interact with boats/minecarts.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onProjectileHitEnderPearl(ProjectileHitEvent event) {
        if (event.getEntity().getType() != EntityType.ENDER_PEARL) return;
        if (!instance.config_claims_enderPearlsRequireAccessTrust) return;
        if (!(event.getEntity().getShooter() instanceof Player)) return;
        Player shooter = (Player) event.getEntity().getShooter();
        if (!instance.claimsEnabledForWorld(event.getEntity().getWorld())) return;

        Block hitBlock = CompatUtil.getHitBlock(event);
        Location destLoc = hitBlock != null ? hitBlock.getLocation().add(0.5, 1, 0.5) : event.getEntity().getLocation();
        Location fromLoc = shooter.getLocation().clone();
        UUID playerID = shooter.getUniqueId();
        UUID pearlEntityID = event.getEntity().getUniqueId();

        Runnable rollbackTask = () -> {
            Player p = instance.getServer().getPlayer(playerID);
            if (p == null || !p.isOnline()) return;
            Location now = p.getLocation();
            if (now.getWorld() != destLoc.getWorld()) return;
            if (now.distanceSquared(destLoc) > 25) return;
            Supplier<String> noAccessReason = ProtectionHelper.checkPermission(p, now, ClaimPermission.Access, null);
            if (noAccessReason != null) {
                recentPearlRollbackPlayers.add(playerID);
                SchedulerUtil.runLaterGlobal(instance, () -> recentPearlRollbackPlayers.remove(playerID), 20L);
                // Run teleport on player's entity region for fastest possible rollback
                // Preserve the player's current camera direction so the rollback doesn't
                // force their yaw/pitch back to the pre-pearl direction.
                Location rollbackLoc = fromLoc.clone();
                rollbackLoc.setYaw(p.getLocation().getYaw());
                rollbackLoc.setPitch(p.getLocation().getPitch());
                SchedulerUtil.runLaterEntity(instance, p, () -> teleportFoliaSafe(p, rollbackLoc), 0L);
                if (!refundedEnderPearlEntities.contains(pearlEntityID)) {
                    GriefPrevention.sendMessage(p, TextMode.Err, noAccessReason.get());
                    if (instance.config_claims_refundDeniedEnderPearls) {
                        refundedEnderPearlEntities.add(pearlEntityID);
                        p.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
                        // Never remove - stasis chambers keep pearl in motion, triggering many events
                    }
                }
            }
        };
        SchedulerUtil.runLaterGlobal(instance, rollbackTask, 1L);
        SchedulerUtil.runLaterGlobal(instance, rollbackTask, 2L);
    }

    // when a player interacts with a specific part of entity...
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        // treat it the same as interacting with an entity in general
        if (event.getRightClicked().getType() == EntityType.ARMOR_STAND) {
            this.onPlayerInteractEntity((PlayerInteractEntityEvent) event);
        }
    }

    // when a player leashes an entity...
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlayerLeashEntity(PlayerLeashEntityEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getEntity();

        // Only handle boats
        if (!entity.getType().name().contains("BOAT")) {
            return;
        }

        // If claims aren't enabled in this world, skip
        if (!instance.claimsEnabledForWorld(entity.getWorld())) {
            return;
        }

        // Check if the player has permission to interact with the entity in this claim
        Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, null);
        if (claim != null) {
            Supplier<String> noBuildReason = ProtectionHelper.checkPermission(
                player,
                entity.getLocation(),
                ClaimPermission.Container,
                event
            );
            if (noBuildReason != null) {
                GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlayerUnleashEntity(PlayerUnleashEntityEvent event) {
        if (!instance.config_claims_preventTheft) {
            return;
        }

        Player player = event.getPlayer();
        Entity entity = event.getEntity();
        if (!instance.claimsEnabledForWorld(entity.getWorld())) {
            return;
        }

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        if (playerData.ignoreClaims) {
            return;
        }

        Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, playerData.lastClaim);
        if (claim == null) {
            return;
        }

        playerData.lastClaim = claim;
        Supplier<String> denial = claim.checkPermission(player, ClaimPermission.Container, event);
        if (denial != null) {
            event.setCancelled(true);
            GriefPrevention.sendRateLimitedErrorMessage(player, denial.get());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(org.bukkit.event.player.PlayerMoveEvent event) {
        // Only check if the player has moved a full block
        if (
            event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY()
        ) {
            return;
        }

        Player player = event.getPlayer();
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        // Get the claim at the new location
        Claim toClaim = this.dataStore.getClaimAt(event.getTo(), false, playerData.lastClaim);
        Claim fromClaim = playerData.lastClaim;

        // If we're moving between claims or to/from wilderness
        if (fromClaim != toClaim) {
            // Update the last claim reference
            playerData.lastClaim = toClaim;

            // Update commands to reflect the new location
            CompatUtil.updateCommands(player);
        }
    }

    // when a player interacts with an entity...
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();

        if (!instance.claimsEnabledForWorld(entity.getWorld())) return;

        // allow horse protection to be overridden to allow management from other
        // plugins
        if (!instance.config_claims_protectHorses && CompatUtil.isAbstractHorse(entity)) return;
        if (
            !instance.config_claims_protectDonkeys &&
            CompatUtil.canCheckEntityType("Donkey") &&
            entity instanceof Donkey
        ) return;
        if (
            !instance.config_claims_protectDonkeys && CompatUtil.canCheckEntityType("Mule") && entity instanceof Mule
        ) return;
        if (
            !instance.config_claims_protectLlamas && CompatUtil.canCheckEntityType("Llama") && entity instanceof Llama
        ) return;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());

        // if entity is tameable and has an owner, apply special rules
        if (entity instanceof Tameable) {
            Tameable tameable = (Tameable) entity;
            if (tameable.isTamed()) {
                if (tameable.getOwner() != null) {
                    UUID ownerID = tameable.getOwner().getUniqueId();

                    // if the player interacting is the owner or an admin in ignore claims mode,
                    // always allow
                    if (player.getUniqueId().equals(ownerID) || playerData.ignoreClaims) {
                        //if giving away pet, do that instead
                        if (playerData.petGiveawayRecipient != null) {
                            tameable.setOwner(playerData.petGiveawayRecipient);
                            playerData.petGiveawayRecipient = null;
                            GriefPrevention.sendMessage(player, TextMode.Success, Messages.PetGiveawayConfirmation);
                            event.setCancelled(true);
                        }
                        return;
                    }
                    if (!instance.pvpRulesApply(entity.getLocation().getWorld()) || instance.config_pvp_protectPets) {
                        // otherwise disallow
                        OfflinePlayer owner = instance.getServer().getOfflinePlayer(ownerID);
                        String ownerName = owner.getName();
                        if (ownerName == null) ownerName = "someone";
                        String message = instance.dataStore.getMessage(Messages.NotYourPet, ownerName);
                        if (player.hasPermission("griefprevention.ignoreclaims")) message +=
                            "  " + instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                        GriefPrevention.sendMessage(player, TextMode.Err, message);
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }

        // don't allow interaction with item frames or armor stands in claimed areas
        // without build permission
        if (entity.getType() == EntityType.ARMOR_STAND || entity instanceof Hanging) {
            Supplier<String> noBuildReason = ProtectionHelper.checkPermission(
                player,
                entity.getLocation(),
                ClaimPermission.Build,
                event
            );
            if (noBuildReason != null) {
                GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
                event.setCancelled(true);
                return;
            }
        }

        // always allow interactions when player is in ignore claims mode
        if (playerData.ignoreClaims) return;

        if (playerData.siegeData != null
                && (entity instanceof StorageMinecart || entity instanceof PoweredMinecart)) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.SiegeNoContainers);
            event.setCancelled(true);
            return;
        }

        // don't allow container access during pvp combat in claimed areas
        if (
            !instance.config_pvp_allowContainerAccess &&
            (entity instanceof StorageMinecart || entity instanceof PoweredMinecart)
        ) {
            if (playerData.inPvpCombat()) {
                Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, playerData.lastClaim);
                if (claim != null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.PvPNoContainers);
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // if the entity is a vehicle and we're preventing theft in claims
        if (instance.config_claims_preventTheft && entity instanceof Vehicle) {
            Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, null);
            if (claim != null) {
                if (entity instanceof InventoryHolder) {
                    Supplier<String> noContainersReason = claim.checkPermission(
                        player,
                        ClaimPermission.Container,
                        event
                    );
                    if (noContainersReason != null) {
                        GriefPrevention.sendRateLimitedErrorMessage(player, noContainersReason.get());
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }

        // sulfur cube (Minecraft 26.2+) - apply container rules for block insert/swap/retrieve
        if (instance.config_claims_preventTheft && entity.getType().name().equals("SULFUR_CUBE")) {
            Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, null);
            if (claim != null) {
                final Supplier<String> noContainersReason = claim.checkPermission(
                    player,
                    ClaimPermission.Container,
                    event
                );
                if (noContainersReason != null) {
                    GriefPrevention.sendRateLimitedErrorMessage(player, noContainersReason.get());
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // if the entity is an animal or copper golem, apply container rules
        if (
            (instance.config_claims_preventTheft &&
                (entity instanceof Animals ||
                    entity instanceof Fish ||
                    CompatUtil.isEntityType(entity, "COPPER_GOLEM"))) ||
            (entity.getType() == EntityType.VILLAGER && instance.config_claims_villagerTradingRequiresTrust)
        ) {
            Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, null);
            if (claim != null) {
                Supplier<String> override = () -> {
                    String message = instance.dataStore.getMessage(
                        Messages.NoDamageClaimedEntity,
                        claim.getOwnerName()
                    );
                    if (player.hasPermission("griefprevention.ignoreclaims")) message +=
                        "  " + instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);

                    return message;
                };
                final Supplier<String> noContainersReason = claim.checkPermission(
                    player,
                    ClaimPermission.Container,
                    event,
                    override
                );
                if (noContainersReason != null) {
                    GriefPrevention.sendRateLimitedErrorMessage(player, noContainersReason.get());
                    event.setCancelled(true);
                    return;
                }
            }
        }

        ItemStack itemInHand = instance.getItemInHand(player, CompatUtil.getInteractEntityEventHand(event));

        // if preventing theft, prevent leashing claimed creatures and boats
        if (
            instance.config_claims_preventTheft &&
            CompatUtil.isLead(itemInHand.getType()) &&
            (entity.getType().name().contains("BOAT") || entity instanceof Creature)
        ) {
            Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, playerData.lastClaim);
            if (claim != null) {
                Supplier<String> failureReason = claim.checkPermission(player, ClaimPermission.Container, event);
                if (failureReason != null) {
                    event.setCancelled(true);
                    GriefPrevention.sendRateLimitedErrorMessage(player, failureReason.get());
                    return;
                }
            }
        }

        // Name tags may only be used on entities that the player is allowed to kill.
        if (itemInHand.getType() == Material.NAME_TAG) {
            // don't track in worlds where claims are not enabled
            if (!instance.claimsEnabledForWorld(entity.getWorld())) return;

            Claim cachedClaim = playerData.lastClaim;
            Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, cachedClaim);

            // Require a claim to handle.
            if (claim == null) return;

            Supplier<String> override = () -> {
                String message = dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
                if (player.hasPermission("griefprevention.ignoreclaims")) message +=
                    "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                return message;
            };

            // Check for permission to access containers.
            Supplier<String> noContainersReason = claim.checkPermission(
                player,
                ClaimPermission.Container,
                event,
                override
            );

            // If player has permission, action is allowed.
            if (noContainersReason == null) return;
            event.setCancelled(true);
            GriefPrevention.sendRateLimitedErrorMessage(player, noContainersReason.get());
        }
    }

    // when a player throws an egg
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerThrowEgg(PlayerEggThrowEvent event) {
        Player player = event.getPlayer();
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = this.dataStore.getClaimAt(event.getEgg().getLocation(), false, playerData.lastClaim);

        // allow throw egg if player is in ignore claims mode
        if (playerData.ignoreClaims || claim == null) return;

        Supplier<String> failureReason = claim.checkPermission(player, ClaimPermission.Container, event);
        if (failureReason != null) {
            String reason = failureReason.get();
            if (player.hasPermission("griefprevention.ignoreclaims")) {
                reason += "  " + instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
            }

            GriefPrevention.sendRateLimitedErrorMessage(player, reason);

            // cancel the event by preventing hatching
            event.setHatching(false);

            // only give the egg back if player is in survival or adventure
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                player.getInventory().addItem(CompatUtil.getEggItem(event.getEgg()));
            }
        }
    }

    // when a player reels in his fishing rod
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerFish(PlayerFishEvent event) {
        Entity entity = event.getCaught();
        if (entity == null) return; // if nothing pulled, uninteresting event

        // if should be protected from pulling in land claims without permission
        if (entity.getType() == EntityType.ARMOR_STAND || entity instanceof Animals) {
            Player player = event.getPlayer();
            PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = instance.dataStore.getClaimAt(entity.getLocation(), false, playerData.lastClaim);
            if (claim != null) {
                Supplier<String> errorMessage = claim.checkPermission(player, ClaimPermission.Container, event);
                if (errorMessage != null) {
                    event.setCancelled(true);
                    GriefPrevention.sendRateLimitedErrorMessage(
                        player,
                        Messages.NoDamageClaimedEntity,
                        claim.getOwnerName()
                    );
                    return;
                }
            }
        }
    }

    // when a player switches in-hand items
    @EventHandler(ignoreCancelled = true)
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        GriefPrevention.AddLogEntry(
            "[GP Debug] onItemHeldChange: player=" + player.getName() + ", newSlot=" + event.getNewSlot(),
            CustomLogEntryTypes.Debug,
            false
        );

        // if he's switching to the golden shovel
        int newSlot = event.getNewSlot();
        ItemStack newItemStack = player.getInventory().getItem(newSlot);
        if (newItemStack != null && newItemStack.getType() == instance.config_claims_modificationTool) {
            GriefPrevention.AddLogEntry(
                "[GP Debug] onItemHeldChange: switching to golden shovel",
                CustomLogEntryTypes.Debug,
                false
            );
            // give the player his available claim blocks count and claiming instructions,
            // but only if he keeps the shovel equipped for a minimum time, to avoid mouse
            // wheel spam
            if (instance.claimsEnabledForWorld(player.getWorld())) {
                EquipShovelProcessingTask task = new EquipShovelProcessingTask(player);
                SchedulerUtil.runLaterEntity(instance, player, task::run, 15L); // 15L is approx. 3/4 of a second
            }
        } else {
            PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
            if (
                playerData.shovelMode == ShovelMode.Shaped ||
                playerData.shovelMode == ShovelMode.ShapedSubdivide ||
                playerData.shovelMode == ShovelMode.Merge
            ) {
                playerData.shovelMode = ShovelMode.Basic;
                playerData.claimSubdividing = null;
                playerData.claimResizing = null;
                playerData.claimMerging = null;
                playerData.mergeEdgeIndex = null;
                playerData.mergeSecondEdgeIndex = null;
                playerData.mergeFirstDepthPoint = null;
                playerData.mergeSecondDepthPoint = null;
                playerData.lastShovelLocation = null;
                playerData.setClaimEditorSession(null);
                playerData.shapedModeResetBySwitch = true;
            }
        }
    }

    // block use of buckets within other players' claims
    private final Set<Material> commonAdjacentBlocks_water = MaterialCompat.availableSet(
        "WATER",
        "FARMLAND",
        "DIRT",
        "STONE"
    );
    private final Set<Material> commonAdjacentBlocks_lava = MaterialCompat.availableSet("LAVA", "DIRT", "STONE");

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent bucketEvent) {
        if (!instance.claimsEnabledForWorld(bucketEvent.getBlockClicked().getWorld())) return;

        Player player = bucketEvent.getPlayer();
        Block block = bucketEvent.getBlockClicked().getRelative(bucketEvent.getBlockFace());
        int minLavaDistance = 10;

        // Fixes #1155:
        // Prevents waterlogging blocks placed on a claim's edge.
        // Waterlogging a block affects the clicked block, and NOT the adjacent location
        // relative to it.
        if (
            bucketEvent.getBucket() == Material.WATER_BUCKET &&
            BlockDataCompat.isWaterlogged(bucketEvent.getBlockClicked())
        ) {
            block = bucketEvent.getBlockClicked();
        }

        // make sure the player is allowed to build at the location
        Supplier<String> noBuildReason = ProtectionHelper.checkPermission(
            player,
            block.getLocation(),
            ClaimPermission.Build,
            bucketEvent
        );
        if (noBuildReason != null) {
            GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
            bucketEvent.setCancelled(true);
            return;
        }

        // if the bucket is being used in a claim, allow for dumping lava closer to
        // other players
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = this.dataStore.getClaimAt(block.getLocation(), false, playerData.lastClaim);
        if (claim != null) {
            minLavaDistance = 3;
        }
        // otherwise no wilderness dumping in creative mode worlds
        else if (instance.creativeRulesApply(block.getLocation())) {
            if (
                block.getY() >= instance.getSeaLevel(block.getWorld()) - 5 &&
                !player.hasPermission("griefprevention.lava")
            ) {
                if (bucketEvent.getBucket() == Material.LAVA_BUCKET) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoWildernessBuckets);
                    bucketEvent.setCancelled(true);
                    return;
                }
            }
        }

        // lava buckets can't be dumped near other players unless pvp is on
        if (!doesAllowLavaProximityInWorld(block.getWorld()) && !player.hasPermission("griefprevention.lava")) {
            if (bucketEvent.getBucket() == Material.LAVA_BUCKET) {
                List<Player> players = block.getWorld().getPlayers();
                for (Player otherPlayer : players) {
                    if (
                        !otherPlayer.equals(player) &&
                        otherPlayer.getGameMode() == GameMode.SURVIVAL &&
                        player.canSee(otherPlayer) &&
                        block.getY() >= otherPlayer.getLocation().getBlockY() - 1 &&
                        otherPlayer.getLocation().distanceSquared(block.getLocation()) <
                            minLavaDistance * minLavaDistance
                    ) {
                        GriefPrevention.sendRateLimitedErrorMessage(
                            player,
                            Messages.NoLavaNearOtherPlayer,
                            "another player"
                        );
                        bucketEvent.setCancelled(true);
                        return;
                    }
                }
            }
        }

        // log any suspicious placements (check sea level, world type, and adjacent
        // blocks)
        if (
            block.getY() >= instance.getSeaLevel(block.getWorld()) - 5 &&
            !player.hasPermission("griefprevention.lava") &&
            block.getWorld().getEnvironment() != Environment.NETHER
        ) {
            // if certain blocks are nearby, it's less suspicious and not worth logging
            Set<Material> exclusionAdjacentTypes;
            if (bucketEvent.getBucket() == Material.WATER_BUCKET) exclusionAdjacentTypes =
                this.commonAdjacentBlocks_water;
            else exclusionAdjacentTypes = this.commonAdjacentBlocks_lava;

            boolean makeLogEntry = true;
            BlockFace[] adjacentDirections = new BlockFace[] {
                BlockFace.EAST,
                BlockFace.WEST,
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.DOWN,
            };
            for (BlockFace direction : adjacentDirections) {
                Material adjacentBlockType = block.getRelative(direction).getType();
                if (exclusionAdjacentTypes.contains(adjacentBlockType)) {
                    makeLogEntry = false;
                    break;
                }
            }

            if (makeLogEntry) {
                GriefPrevention.AddLogEntry(
                    player.getName() +
                        " placed suspicious " +
                        bucketEvent.getBucket().name() +
                        " @ " +
                        GriefPrevention.getfriendlyLocationString(block.getLocation()),
                    CustomLogEntryTypes.SuspiciousActivity,
                    true
                );
            }
        }
    }

    private boolean doesAllowLavaProximityInWorld(World world) {
        if (GriefPrevention.instance.pvpRulesApply(world)) {
            return GriefPrevention.instance.config_pvp_allowLavaNearPlayers;
        } else {
            return GriefPrevention.instance.config_pvp_allowLavaNearPlayers_NonPvp;
        }
    }

    // see above
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerBucketFill(PlayerBucketFillEvent bucketEvent) {
        Player player = bucketEvent.getPlayer();
        Block block = bucketEvent.getBlockClicked();

        if (!instance.claimsEnabledForWorld(block.getWorld())) return;

        // exemption for cow milking (permissions will be handled by player interact
        // with entity event instead)
        Material blockType = block.getType();
        if (blockType == Material.AIR) return;
        if (blockType.isSolid()) {
            if (!BlockDataCompat.isWaterlogged(block)) return;
        }

        // make sure the player is allowed to build at the location
        Supplier<String> noBuildReason = ProtectionHelper.checkPermission(
            player,
            block.getLocation(),
            ClaimPermission.Build,
            bucketEvent
        );
        if (noBuildReason != null) {
            GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
            bucketEvent.setCancelled(true);
            return;
        }
    }

    // when a player interacts with the world
    @EventHandler(priority = EventPriority.LOW)
    void onPlayerInteract(PlayerInteractEvent event) {
        GriefPrevention.AddLogEntry(
            "[GP Debug] PlayerEventHandler.onPlayerInteract: player=" +
                event.getPlayer().getName() +
                ", action=" +
                event.getAction(),
            CustomLogEntryTypes.Debug,
            false
        );

        // not interested in left-click-on-air actions
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR) {
            GriefPrevention.AddLogEntry(
                "[GP Debug] PlayerEventHandler.onPlayerInteract: LEFT_CLICK_AIR, returning",
                CustomLogEntryTypes.Debug,
                false
            );
            return;
        }

        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock(); // null returned here means interacting with air

        Material clickedBlockType = null;
        if (clickedBlock != null) {
            clickedBlockType = clickedBlock.getType();
        } else {
            clickedBlockType = Material.AIR;
        }

        PlayerData playerData = null;

        if (event.getMaterial() == instance.config_claims_modificationTool) {
            playerData = this.dataStore.getPlayerData(player.getUniqueId());
            if (playerData.siegeData != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.SiegeNoShovel);
                event.setCancelled(true);
                return;
            }
        }

        // Turtle eggs
        if (action == Action.PHYSICAL) {
            if (clickedBlockType != Material.TURTLE_EGG) return;
            playerData = this.dataStore.getPlayerData(player.getUniqueId());
            if (clickedBlock != null) {
                Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
                if (claim != null) {
                    playerData.lastClaim = claim;

                    Supplier<String> noAccessReason = claim.checkPermission(player, ClaimPermission.Build, event);
                    if (noAccessReason != null) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            return;
        }

        // On legacy clients fire has no hitbox, so a left click arrives for the
        // supporting block. Re-send the fire when build permission is denied.
        if (action == Action.LEFT_CLICK_BLOCK && clickedBlock != null) {
            Block adjacentBlock = clickedBlock.getRelative(event.getBlockFace());
            if (adjacentBlock.getType() == Material.FIRE && adjacentBlock.getLightFromBlocks() == 15) {
                if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
                Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
                if (claim != null) {
                    playerData.lastClaim = claim;
                    Supplier<String> noBuildReason = claim.checkPermission(player, ClaimPermission.Build, event);
                    if (noBuildReason != null) {
                        event.setCancelled(true);
                        GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
                        player.sendBlockChange(adjacentBlock.getLocation(), adjacentBlock.getType(), adjacentBlock.getData());
                        return;
                    }
                }
            }

            // Most left clicks are handled by BlockBreakEvent.
            if (!this.onLeftClickWatchList(clickedBlockType)) return;
        }

        // Check for brush usage on any block
        if (clickedBlock != null) {
            // Check if player is holding a brush
            ItemStack itemInHand;
            try {
                itemInHand = player.getInventory().getItemInMainHand();
            } catch (NoSuchMethodError e) {
                // 1.8.8: fall back to getItemInHand()
                itemInHand = player.getItemInHand();
            }
            try {
                if (itemInHand.getType() == Material.valueOf("BRUSH")) {
                    if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());

                    // Check claim permissions
                    Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
                    if (claim != null) {
                        playerData.lastClaim = claim;

                        Supplier<String> noBuildReason = claim.checkPermission(player, ClaimPermission.Build, event);
                        if (noBuildReason != null) {
                            event.setCancelled(true);
                            GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
                            return;
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                // 1.8.8: BRUSH doesn't exist
            }
        }

        // Mob buckets place a live entity in the world, so they need build trust.
        // Fish, axolotl and tadpole buckets carry water and are caught by
        // onPlayerBucketEmpty, but the sulfur cube bucket (Minecraft 26.2+) carries no
        // fluid: MobBucketItem short-circuits emptyContents() for empty-fluid buckets and
        // never reaches the code that fires PlayerBucketEmptyEvent, so the cube gets
        // spawned with no event to cancel. Checking the interact covers every mob bucket.
        // This runs ahead of the block-type branches below because shift-right-clicking
        // suppresses block use and empties the bucket instead.
        if (
            clickedBlock != null &&
            action == Action.RIGHT_CLICK_BLOCK &&
            mobBuckets.contains(event.getMaterial())
        ) {
            // the mob spawns in the block adjacent to the clicked face, not in the block itself
            Block spawnBlock = clickedBlock.getRelative(event.getBlockFace());
            Supplier<String> noBuildReason = ProtectionHelper.checkPermission(
                player,
                spawnBlock.getLocation(),
                ClaimPermission.Build,
                event
            );
            if (noBuildReason != null) {
                GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
                event.setCancelled(true);
                return;
            }
        }

        // apply rules for containers and crafting blocks
        // NOTE: GRINDSTONE, LOOM, STONECUTTER, and CARTOGRAPHY_TABLE are deliberately NOT
        // protected here. They are pure crafting workstations with no persistent inventory
        // (items left in them are dropped to the floor on close), so PreventTheft does not
        // apply — the same way it does not apply to plain CRAFTING_TABLE. See upstream
        // GriefPrevention/GriefPrevention#2587.
        // NOTE: LECTERN is deliberately excluded even though it is an InventoryHolder.
        // Reading only needs access trust (handled in the doors/beds branch below, gated on
        // LecternReadingRequiresAccessTrust), while taking the book needs container trust and
        // is enforced by PlayerTakeLecternBookEventHandler. See upstream
        // GriefPrevention/GriefPrevention#579.
        if (
            clickedBlock != null &&
            instance.config_claims_preventTheft &&
            event.getAction() == Action.RIGHT_CLICK_BLOCK &&
                ((this.isInventoryHolder(clickedBlock) && !CompatUtil.isMaterial(clickedBlock.getType(), "LECTERN")) ||
                    clickedBlockType == Material.ANVIL ||
                    clickedBlockType == Material.BEACON ||
                    CompatUtil.isMaterial(clickedBlockType, "BEE_NEST") ||
                    CompatUtil.isMaterial(clickedBlockType, "BEEHIVE") ||
                    CompatUtil.isMaterial(clickedBlockType, "BELL") ||
                    clickedBlockType == Material.CAKE ||
                    clickedBlockType == Material.CAULDRON ||
                    CompatUtil.isMaterial(clickedBlockType, "WATER_CAULDRON") ||
                    CompatUtil.isMaterial(clickedBlockType, "LAVA_CAULDRON") ||
                    CompatUtil.isMaterial(clickedBlockType, "CAVE_VINES") ||
                    CompatUtil.isMaterial(clickedBlockType, "CAVE_VINES_PLANT") ||
                    CompatUtil.isMaterial(clickedBlockType, "CHIPPED_ANVIL") ||
                    CompatUtil.isMaterial(clickedBlockType, "DAMAGED_ANVIL") ||
                    clickedBlockType == Material.JUKEBOX ||
                    clickedBlockType == Material.PUMPKIN ||
                    CompatUtil.isMaterial(clickedBlockType, "RESPAWN_ANCHOR") ||
                    (CompatUtil.isMaterial(clickedBlockType, "ROOTED_DIRT") &&
                        MaterialTagCompat.isTagged("ITEMS_HOES", event.getMaterial())) ||
                    CompatUtil.isMaterial(clickedBlockType, "SWEET_BERRY_BUSH") ||
                    CompatUtil.isMaterial(clickedBlockType, "DECORATED_POT"))
        ) {
            // Only call claim tool dispatcher if player is holding a claim tool
            ItemStack itemInHand;
            try {
                itemInHand = player.getInventory().getItemInMainHand();
            } catch (NoSuchMethodError e) {
                itemInHand = player.getItemInHand();
            }
            if (
                itemInHand != null &&
                !isShelf(clickedBlockType) &&
                (itemInHand.getType() == instance.config_claims_modificationTool ||
                    itemInHand.getType() == instance.config_claims_investigationTool)
            ) {
                if (this.claimToolDispatcher.handle(event, clickedBlock, clickedBlockType)) {
                    return;
                }
            }
            if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());

            if (playerData.siegeData != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.SiegeNoContainers);
                event.setCancelled(true);
                return;
            }

            // allow players with ignoreclaims permission in spectator mode to access
            // containers
            if (player.hasPermission("griefprevention.ignoreclaims") && player.getGameMode() == GameMode.SPECTATOR) {
                // Skip container permission checks for admins in spectator mode
                return;
            }

            // check if player is in a claim for pvp and permission checks below
            Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);

            // block container use during pvp combat in claimed areas only - in wilderness, allow
            // access since blocking won't prevent attackers from getting those items anyway
            boolean respawnAnchorPvpException =
                instance.config_pvp_allowRespawnAnchor && CompatUtil.isMaterial(clickedBlockType, "RESPAWN_ANCHOR");
            if (
                playerData.inPvpCombat() &&
                claim != null &&
                !instance.config_pvp_allowContainerAccess &&
                !respawnAnchorPvpException
            ) {
                GriefPrevention.sendRateLimitedErrorMessage(player, Messages.PvPNoContainers);
                event.setCancelled(true);
                return;
            }

            if (claim != null) {
                playerData.lastClaim = claim;

                Supplier<String> noContainersReason = claim.checkPermission(player, ClaimPermission.Container, event);
                if (noContainersReason != null) {
                    event.setCancelled(true);

                    // Delay the error message to check if another plugin opened an inventory
                    SchedulerUtil.runLaterEntity(
                        instance,
                        player,
                        () -> {
                            try {
                                if (
                                    player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING
                                ) return;
                            } catch (IncompatibleClassChangeError ignored) {
                                // Pre-1.16: InventoryView is a class, not interface
                            }
                            GriefPrevention.sendRateLimitedErrorMessage(player, noContainersReason.get());
                        },
                        1L
                    ); // Check 1 tick later

                    return;
                }
            }

            // if the event hasn't been cancelled, then the player is allowed to use the
            // container
            // so drop any pvp protection
            if (playerData.pvpImmune) {
                playerData.pvpImmune = false;
                GriefPrevention.sendMessage(player, TextMode.Warn, Messages.PvPImmunityEnd);
            }
        }
        // otherwise apply rules for doors and beds, if configured that way
        else if (
            clickedBlock != null &&
            ((instance.config_claims_lockWoodenDoors && MaterialTagCompat.isTagged("DOORS", clickedBlockType)) ||
                (instance.config_claims_preventButtonsSwitches &&
                    MaterialTagCompat.isTagged("BEDS", clickedBlockType)) ||
                (instance.config_claims_lockTrapDoors && isLockableTrapdoor(clickedBlockType)) ||
                (instance.config_claims_lecternReadingRequiresAccessTrust &&
                    CompatUtil.isMaterial(clickedBlockType, "LECTERN")) ||
                (instance.config_claims_lockFenceGates && MaterialTagCompat.isTagged("FENCE_GATES", clickedBlockType)))
        ) {
            if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
            if (claim != null) {
                playerData.lastClaim = claim;

                Supplier<String> noAccessReason = claim.checkPermission(player, ClaimPermission.Access, event);
                if (noAccessReason != null) {
                    event.setCancelled(true);
                    GriefPrevention.sendRateLimitedErrorMessage(player, noAccessReason.get());
                    return;
                }
            }
        }
        // otherwise apply rules for buttons and switches
        else if (
            clickedBlock != null &&
            instance.config_claims_preventButtonsSwitches &&
            (MaterialTagCompat.isTagged("BUTTONS", clickedBlockType) || clickedBlockType == Material.LEVER)
        ) {
            if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
            if (claim != null) {
                playerData.lastClaim = claim;

                Supplier<String> noAccessReason = claim.checkPermission(player, ClaimPermission.Access, event);
                if (noAccessReason != null) {
                    event.setCancelled(true);
                    GriefPrevention.sendRateLimitedErrorMessage(player, noAccessReason.get());
                    return;
                }
            }
        }
        // otherwise apply rule for cake
        else if (
            clickedBlock != null &&
            instance.config_claims_preventTheft &&
            (clickedBlockType == Material.CAKE || MaterialTagCompat.isTagged("CANDLE_CAKES", clickedBlockType))
        ) {
            if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
            if (claim != null) {
                playerData.lastClaim = claim;

                Supplier<String> noContainerReason = claim.checkPermission(player, ClaimPermission.Access, event);
                if (noContainerReason != null) {
                    event.setCancelled(true);
                    GriefPrevention.sendRateLimitedErrorMessage(player, noContainerReason.get());
                    return;
                }
            }
        }
        // apply rule for redstone and various decor blocks that require full trust
        else if (
            clickedBlock != null &&
            (clickedBlockType == Material.NOTE_BLOCK ||
                CompatUtil.isMaterial(clickedBlockType, "REPEATER") ||
                clickedBlockType == Material.DRAGON_EGG ||
                clickedBlockType == Material.DAYLIGHT_DETECTOR ||
                CompatUtil.isMaterial(clickedBlockType, "COMPARATOR") ||
                clickedBlockType == Material.REDSTONE_WIRE ||
                MaterialTagCompat.isTagged("FLOWER_POTS", clickedBlockType) ||
                MaterialTagCompat.isTagged("CANDLES", clickedBlockType) ||
                MaterialTagCompat.isTagged("COPPER_GOLEM_STATUES", clickedBlockType))
        ) {
            if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
            if (claim != null) {
                Supplier<String> noBuildReason = claim.checkPermission(player, ClaimPermission.Build, event);
                if (noBuildReason != null) {
                    event.setCancelled(true);
                    GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
                    return;
                }
            }
        }
        // otherwise handle right click (shovel, string, bonemeal) //RoboMWM: flint and
        // steel
        else {
            // ignore all actions except right-click on a block or in the air
            if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

            // what's the player holding?
            EquipmentSlot hand = CompatUtil.getInteractEventHand(event);
            ItemStack itemInHand = instance.getItemInHand(player, hand);
            Material materialInHand = itemInHand.getType();

            // Require build permission for items that may have an effect on the world when
            // used.
            if (
                clickedBlock != null &&
                (CompatUtil.isMaterial(materialInHand, "BONE_MEAL") ||
                    materialInHand == Material.ARMOR_STAND ||
                    (spawnEggs.contains(materialInHand) &&
                        GriefPrevention.instance.config_claims_preventGlobalMonsterEggs) ||
                    CompatUtil.isMaterial(materialInHand, "END_CRYSTAL") ||
                    materialInHand == Material.FLINT_AND_STEEL ||
                    CompatUtil.isMaterial(materialInHand, "INK_SAC") ||
                    CompatUtil.isMaterial(materialInHand, "GLOW_INK_SAC") ||
                    CompatUtil.isMaterial(materialInHand, "HONEYCOMB") ||
                    dyes.contains(materialInHand))
            ) {
                Supplier<String> noBuildReason = ProtectionHelper.checkPermission(
                    player,
                    event.getClickedBlock().getLocation(),
                    ClaimPermission.Build,
                    event
                );
                if (noBuildReason != null) {
                    GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
                    event.setCancelled(true);
                }

                if (!event.isCancelled()
                        && instance.creativeRulesApply(clickedBlock.getLocation())
                        && isCreativeEntityPlacement(materialInHand)) {
                    if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
                    Claim placementClaim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
                    if (placementClaim != null) {
                        String reason = placementClaim.allowMoreEntities(false);
                        if (reason != null) {
                            GriefPrevention.sendMessage(player, TextMode.Err, reason);
                            event.setCancelled(true);
                        }
                    }
                }

                return;
            } else if (clickedBlock != null && MaterialTagCompat.isTagged("ITEMS_BOATS", materialInHand)) {
                if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
                Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
                if (claim != null) {
                    Supplier<String> reason = claim.checkPermission(player, ClaimPermission.Container, event);
                    if (reason != null) {
                        GriefPrevention.sendRateLimitedErrorMessage(player, reason.get());
                        event.setCancelled(true);
                    }
                }

                return;
            }
            // survival world minecart placement requires container trust, which is the
            // permission required to remove the minecart later
            else if (
                clickedBlock != null &&
                (materialInHand == Material.MINECART ||
                    CompatUtil.isMaterial(materialInHand, "FURNACE_MINECART") ||
                    CompatUtil.isMaterial(materialInHand, "CHEST_MINECART") ||
                    CompatUtil.isMaterial(materialInHand, "TNT_MINECART") ||
                    CompatUtil.isMaterial(materialInHand, "HOPPER_MINECART")) &&
                !instance.creativeRulesApply(clickedBlock.getLocation())
            ) {
                if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
                Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
                if (claim != null) {
                    Supplier<String> reason = claim.checkPermission(player, ClaimPermission.Container, event);
                    if (reason != null) {
                        GriefPrevention.sendRateLimitedErrorMessage(player, reason.get());
                        event.setCancelled(true);
                    }
                }

                return;
            }
            // Creative worlds cap minecarts, armor stands, frames, eggs, and
            // infested-block spawns according to claim area.
            else if (clickedBlock != null
                    && instance.creativeRulesApply(clickedBlock.getLocation())
                    && isCreativeEntityPlacement(materialInHand)) {
                Supplier<String> noBuildReason = ProtectionHelper.checkPermission(
                        player, clickedBlock.getLocation(), ClaimPermission.Build, event);
                if (noBuildReason != null) {
                    GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
                    event.setCancelled(true);
                    return;
                }

                if (playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
                Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
                if (claim != null) {
                    String reason = claim.allowMoreEntities(false);
                    if (reason != null) {
                        GriefPrevention.sendMessage(player, TextMode.Err, reason);
                        event.setCancelled(true);
                    }
                }
                return;
            }

            // Only call claim tool dispatcher if player is holding a claim tool
            if (
                itemInHand != null &&
                !isShelf(clickedBlockType) &&
                (itemInHand.getType() == instance.config_claims_modificationTool ||
                    itemInHand.getType() == instance.config_claims_investigationTool)
            ) {
                if (this.claimToolDispatcher.handle(event, clickedBlock, clickedBlockType)) {
                    return;
                }
            } else {
                // Not holding a claim tool, skip all claim tool logic below
                return;
            }

            playerData = this.dataStore.getPlayerData(player.getUniqueId());

            boolean cornerSelected = false; // track if we snapped to a 3D corner so AIR guard can be bypassed

            if (ClaimToolInteractionState.shouldAttempt3DCornerSelection(playerData)) {
                CornerHit cornerHit = raycast3DSubclaimCorner(player, 100);
                if (cornerHit != null && shouldUse3DCornerHit(player, clickedBlock, cornerHit)) {
                    clickedBlock = player.getWorld().getBlockAt(cornerHit.x, cornerHit.y, cornerHit.z);
                    clickedBlockType = clickedBlock.getType();
                    cornerSelected = true;
                }
            }

            // if no block, stop here
            if (clickedBlock == null) {
                return;
            }

            // can't use the shovel from too far away, unless we snapped to a 3D corner
            // (corner itself may be air)
            if (clickedBlockType == Material.AIR && !cornerSelected) return;

            // if the player doesn't have claims permission, don't do anything
            if (!player.hasPermission("griefprevention.createclaims")) {
                GriefPrevention.sendRateLimitedErrorMessage(player, Messages.NoCreateClaimPermission);
                return;
            }

            // Handle RestoreNature shovel modes
            if (
                playerData.shovelMode == ShovelMode.RestoreNature ||
                playerData.shovelMode == ShovelMode.RestoreNatureAggressive ||
                playerData.shovelMode == ShovelMode.RestoreNatureFill
            ) {
                handleRestoreNature(player, clickedBlock, playerData);
                return;
            }

            if (playerData.shovelMode == ShovelMode.Shaped) {
                playerData.setEphemeralBasicShapedSegmentPreview(false);
                if (!GriefPrevention.instance.config_claims_allowShapedClaims) {
                    playerData.shovelMode = ShovelMode.Basic;
                    playerData.claimSubdividing = null;
                    playerData.claimResizing = null;
                    playerData.claimMerging = null;
                    playerData.mergeEdgeIndex = null;
                    playerData.mergeSecondEdgeIndex = null;
                    playerData.mergeFirstDepthPoint = null;
                    playerData.mergeSecondDepthPoint = null;
                    playerData.lastShovelLocation = null;
                    playerData.setClaimEditorSession(null);
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedClaimsDisabled);
                    return;
                }
                if (handleShapedResizeInteraction(player, playerData, clickedBlock)) {
                    return;
                }
                handleShapedModeInteraction(player, playerData, clickedBlock);
                return;
            }

            if (playerData.shovelMode == ShovelMode.Merge) {
                handleMergeModeInteraction(player, playerData, clickedBlock);
                return;
            }

            // if he's resizing a claim and that claim hasn't been deleted since he started
            // resizing it
            if (playerData.claimResizing != null && playerData.claimResizing.inDataStore) {
                // Don't handle resize logic in shaped mode
                if (playerData.shovelMode == ShovelMode.Shaped) {
                    return;
                }
                if (playerData.lastShovelLocation == null && playerData.shovelMode != ShovelMode.Shaped) {
                    startClaimResizeSelection(player, playerData, playerData.claimResizing, clickedBlock);
                    return;
                }
                if (clickedBlock.getLocation().equals(playerData.lastShovelLocation)) return;

                if (tryResizeShapedClaim(player, playerData, clickedBlock)) {
                    return;
                }

                // figure out what the coords of his new claim would be
                int newx1, newx2, newz1, newz2, newy1, newy2;
                if (
                    playerData.lastShovelLocation.getBlockX() ==
                    playerData.claimResizing.getLesserBoundaryCorner().getBlockX()
                ) {
                    newx1 = clickedBlock.getX();
                    newx2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockX();
                } else {
                    newx1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockX();
                    newx2 = clickedBlock.getX();
                }

                if (
                    playerData.lastShovelLocation.getBlockZ() ==
                    playerData.claimResizing.getLesserBoundaryCorner().getBlockZ()
                ) {
                    newz1 = clickedBlock.getZ();
                    newz2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockZ();
                } else {
                    newz1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockZ();
                    newz2 = clickedBlock.getZ();
                }

                // Y resizing behavior
                if (playerData.claimResizing.is3D()) {
                    // For 3D subclaims, allow vertical resizing when the initial corner was at minY
                    // or maxY
                    int currentMinY = playerData.claimResizing.getLesserBoundaryCorner().getBlockY();
                    int currentMaxY = playerData.claimResizing.getGreaterBoundaryCorner().getBlockY();
                    int startY = playerData.lastShovelLocation.getBlockY();
                    int endY = clickedBlock.getY();

                    // Special case: If it's a single-layer 3D claim, allow resizing from any corner
                    boolean isSingleLayer = currentMinY == currentMaxY;

                    // Default: preserve Y range
                    newy1 = currentMinY;
                    newy2 = currentMaxY;

                    if (isSingleLayer || startY == currentMinY) {
                        // For single-layer or when dragging from bottom edge: adjust minY
                        newy1 = Math.min(endY, currentMaxY);

                        // If we're making it taller, set maxY to the new Y
                        if (endY > currentMaxY) {
                            newy2 = endY;
                        }
                    } else if (startY == currentMaxY) {
                        // Dragging from top edge: adjust maxY
                        newy2 = Math.max(endY, currentMinY);

                        // If we're making it taller from the bottom, set minY to the new Y
                        if (endY < currentMinY) {
                            newy1 = endY;
                        }
                    }

                    // Ensure ordering
                    if (newy1 > newy2) {
                        int tmp = newy1;
                        newy1 = newy2;
                        newy2 = tmp;
                    }
                } else {
                    // 2D claims keep legacy behavior: extend into ground based on clicked Y
                    newy1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockY();
                    newy2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockY();
                }

                this.dataStore.resizeClaimWithChecks(player, playerData, newx1, newx2, newy1, newy2, newz1, newz2);

                return;
            }

            // otherwise, since not currently resizing a claim, must be starting a resize,
            // creating a new claim, or creating a subdivision
            // Prefer a Y-aware lookup first so we correctly target stacked 3D subclaims at
            // this Y level.
            Claim resolvedClaim = this.dataStore.getClaimAt(
                clickedBlock.getLocation(),
                false /* respect height */,
                playerData.lastClaim
            );
            if (resolvedClaim == null) {
                // Fallback to ignore-height search to preserve legacy behavior when no 3D
                // subclaim matches Y
                resolvedClaim = this.dataStore.getClaimAt(
                    clickedBlock.getLocation(),
                    true /* ignore height */,
                    playerData.lastClaim
                );
            }
            Claim claim = findDeepestContainingClaim(resolvedClaim, clickedBlock.getLocation());
            if (claim != null) {
                playerData.lastClaim = claim;
            }

            // If this click is exactly at a shared corner, promote to the outermost parent
            // sharing that corner.
            // Don't promote single-block claims (1x1x1) where every block is a corner.
            if (claim != null) {
                Claim promoted = claim;
                while (
                    promoted.parent != null &&
                    (promoted.getLesserBoundaryCorner().getBlockX() !=
                        promoted.getGreaterBoundaryCorner().getBlockX() ||
                        promoted.getLesserBoundaryCorner().getBlockZ() !=
                            promoted.getGreaterBoundaryCorner().getBlockZ()) &&
                    isCornerMatch(promoted, clickedBlock)
                ) {
                    Claim parent = promoted.parent;
                    if (!isCornerMatch(parent, clickedBlock)) {
                        break;
                    }

                    promoted = parent;
                }

                claim = promoted;
            }

            // Fix for Issue: Clicking outside Y-bounds of a 3D claim (e.g. above/below)
            // should not resolve to that 3D claim
            // This prevents "One block inside parent" / Overlap errors when trying to
            // create/resize near a 3D subclaim.
            if (claim != null && claim.is3D() && !claim.contains(clickedBlock.getLocation(), true, false)) {
                claim = claim.parent;
            }

            // Admin3D stacking: if already creating a new 3D admin claim,
            // bypass the inside-claim restriction and proceed to finish
            if (claim != null && playerData.shovelMode == ShovelMode.Admin3D && playerData.lastShovelLocation != null) {
                if (!playerData.lastShovelLocation.getWorld().equals(clickedBlock.getWorld())) {
                    playerData.lastShovelLocation = null;
                    this.onPlayerInteract(event);
                    return;
                }
                claim = null;
            }

            // if within an existing claim, he's not creating a new one
            if (claim != null) {
                // if the player has permission to edit the claim or subdivision
                final String ownerName = claim.getOwnerName();
                Supplier<String> noEditReason = claim.checkPermission(player, ClaimPermission.Edit, event, () ->
                    instance.dataStore.getMessage(Messages.CreateClaimFailOverlapOtherPlayer, ownerName)
                );
                boolean isSubdivisionMode =
                    playerData.shovelMode == ShovelMode.Subdivide || playerData.shovelMode == ShovelMode.Subdivide3D;
                boolean canManage =
                    isSubdivisionMode && claim.checkPermission(player, ClaimPermission.Manage, null) == null;
                if (noEditReason == null || canManage) {
                    // Shift+right-click on a top-level shaped claim edge (basic mode): ephemeral segment selection for
                    // later expansion in shaped mode. Requires AllowShapedClaims; corners use normal resize below.
                    if (
                        noEditReason == null &&
                        playerData.shovelMode == ShovelMode.Basic &&
                        player.isSneaking() &&
                        instance.config_claims_allowShapedClaims &&
                        trySelectShapedBoundarySegmentBasicMode(player, playerData, claim, clickedBlock)
                    ) {
                        return;
                    }

                    // if he clicked on a corner, start resizing it (unless sneaking or in shaped mode)
                    boolean isCorner = isCornerMatch(claim, clickedBlock);
                    if (noEditReason == null && isCorner && !player.isSneaking() && playerData.shovelMode != ShovelMode.Shaped) {
                        startClaimResizeSelection(player, playerData, claim, clickedBlock);
                    }
                    // if he's in subdivision mode, he's creating a new subdivision
                    else if (
                        playerData.shovelMode == ShovelMode.Subdivide || playerData.shovelMode == ShovelMode.Subdivide3D
                    ) {
                        // if it's the first click, he's trying to start a new subdivision
                        if (playerData.lastShovelLocation == null) {
                            // if the clicked claim was a subdivision, decide whether nesting is allowed
                            if (claim.parent != null) {
                                boolean claimIs3D = claim.is3D();
                                boolean wants3DSubdivision = playerData.shovelMode == ShovelMode.Subdivide3D;
                                boolean canStartSubdivision;

                                if (!instance.config_claims_allowNestedSubClaims) {
                                    // Nested subdivisions disabled entirely.
                                    canStartSubdivision = false;
                                } else if (claimIs3D) {
                                    // When nested subclaims enabled and inside a 3D subdivision:
                                    // Only allow if NOT on the same X/Z boundary as the 3D subdivision
                                    boolean onXBoundary =
                                        clickedBlock.getX() == claim.getLesserBoundaryCorner().getBlockX() ||
                                        clickedBlock.getX() == claim.getGreaterBoundaryCorner().getBlockX();
                                    boolean onZBoundary =
                                        clickedBlock.getZ() == claim.getLesserBoundaryCorner().getBlockZ() ||
                                        clickedBlock.getZ() == claim.getGreaterBoundaryCorner().getBlockZ();

                                    // Block if on exact X/Z boundary (corners), allow nesting otherwise
                                    // Also require 3D mode to stack inside 3D claims
                                    canStartSubdivision = wants3DSubdivision && !(onXBoundary && onZBoundary);
                                } else {
                                    // Parent is 2D: allow additional subdivisions (2D or 3D) when nesting is
                                    // enabled.
                                    canStartSubdivision = true;
                                }

                                if (canStartSubdivision) {
                                    GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionStart);
                                    playerData.lastShovelLocation = clickedBlock.getLocation();
                                    if (wants3DSubdivision) {
                                        BoundaryVisualization.visualizeArea(
                                            player,
                                            new BoundingBox(clickedBlock),
                                            VisualizationType.INITIALIZE_ZONE_3D,
                                            clickedBlock.getY()
                                        );
                                    }
                                    playerData.claimSubdividing = claim;
                                } else {
                                    // Show conflict zone visualization for the existing 3D subdivision
                                    // Show conflict zone visualization for the existing 3D subdivision
                                    if (claimIs3D) {
                                        GriefPrevention.sendMessage(
                                            player,
                                            TextMode.Err,
                                            Messages.ResizeFailOverlapSubdivision
                                        );
                                        visualizeConflict(player, playerData, claim, clickedBlock, true);
                                    } else {
                                        GriefPrevention.sendMessage(
                                            player,
                                            TextMode.Err,
                                            Messages.ResizeFailOverlapSubdivision
                                        );
                                    }
                                }
                            } else {
                                // Top-level claim: always allow starting a subdivision.
                                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionStart);
                                playerData.lastShovelLocation = clickedBlock.getLocation();
                                if (playerData.shovelMode == ShovelMode.Subdivide3D) {
                                    BoundaryVisualization.visualizeArea(
                                        player,
                                        new BoundingBox(clickedBlock),
                                        VisualizationType.INITIALIZE_ZONE_3D,
                                        clickedBlock.getY()
                                    );
                                }
                                playerData.claimSubdividing = claim;
                            }
                        }
                        // otherwise, he's trying to finish creating a subdivision by setting the other
                        // boundary corner
                        else {
                            // if last shovel location was in a different world, assume the player is
                            // starting the create-claim workflow over
                            if (!playerData.lastShovelLocation.getWorld().equals(clickedBlock.getWorld())) {
                                playerData.lastShovelLocation = null;
                                this.onPlayerInteract(event);
                                return;
                            }

                            // Determine Y boundaries based on shovel mode
                            int y1 = playerData.lastShovelLocation.getBlockY();
                            int y2 = clickedBlock.getY();
                            int minY, maxY;

                            if (playerData.shovelMode == ShovelMode.Subdivide) {
                                if (playerData.claimSubdividing == null) {
                                    GriefPrevention.sendMessage(
                                        player,
                                        TextMode.Err,
                                        "No claim selected for subdivision."
                                    );
                                    return;
                                }
                                // 2D mode: Always span from parent's bottom to world max height so claim is NOT
                                // 3D.
                                // This matches default GP behavior where 2D subclaims ignore height.
                                int parentMinY = playerData.claimSubdividing.getLesserBoundaryCorner().getBlockY();
                                int worldMaxY = GriefPrevention.getWorldMaxY(player.getWorld());

                                minY = parentMinY;
                                maxY = worldMaxY;

                                // For 2D subdivisions, force the created corners to use the full-height span
                                // so DataStore.createClaim marks is3D=false.
                                y1 = minY;
                                y2 = maxY;
                            } else if (playerData.shovelMode == ShovelMode.Subdivide3D) {
                                // 3D mode: same-Y -> single-layer 3D; different Y -> bounded 3D
                                if (Math.abs(y2 - y1) == 0) {
                                    minY = y1;
                                    maxY = y1; // single layer high
                                } else {
                                    minY = Math.min(y1, y2);
                                    maxY = Math.max(y1, y2);
                                }
                            } else {
                                // Fallback: default to parent Y bounds
                                if (playerData.claimSubdividing == null) {
                                    GriefPrevention.sendMessage(
                                        player,
                                        TextMode.Err,
                                        "No claim selected for subdivision."
                                    );
                                    return;
                                }
                                minY = playerData.claimSubdividing.getLesserBoundaryCorner().getBlockY();
                                maxY = playerData.claimSubdividing.getGreaterBoundaryCorner().getBlockY();
                            }

                            // Enforce inner offset if nesting 3D subclaims is enabled
                            if (
                                instance.config_claims_allowNestedSubClaims &&
                                playerData.claimSubdividing != null &&
                                playerData.claimSubdividing.is3D() &&
                                playerData.shovelMode == ShovelMode.Subdivide3D
                            ) {
                                Claim parentClaim = playerData.claimSubdividing;
                                int inset = 1;
                                if (inset > 0) {
                                    Location parentMin = parentClaim.getLesserBoundaryCorner();
                                    Location parentMax = parentClaim.getGreaterBoundaryCorner();

                                    int parentMinY = Math.min(parentMin.getBlockY(), parentMax.getBlockY());
                                    int parentMaxY = Math.max(parentMin.getBlockY(), parentMax.getBlockY());

                                    // Only enforce Y-axis inset for 3D claims, allow X/Z boundaries to match parent
                                    int minAllowedY = parentMinY + inset;
                                    int maxAllowedY = parentMaxY - inset;

                                    if (minAllowedY > maxAllowedY) {
                                        GriefPrevention.sendMessage(
                                            player,
                                            TextMode.Err,
                                            Messages.CreateSubdivisionOverlap
                                        );
                                        return;
                                    }

                                    int subMinY = Math.min(minY, maxY);
                                    int subMaxY = Math.max(minY, maxY);

                                    if (subMinY < minAllowedY || subMaxY > maxAllowedY) {
                                        GriefPrevention.sendMessage(
                                            player,
                                            TextMode.Err,
                                            Messages.InnerSubdivisionTooClose
                                        );
                                        return;
                                    }
                                }
                            }

                            // Ensure 3D subclaims placed inside 2D parents - allow X/Z boundaries to match
                            // parent
                            if (
                                playerData.shovelMode == ShovelMode.Subdivide3D &&
                                playerData.claimSubdividing != null &&
                                !playerData.claimSubdividing.is3D()
                            ) {
                                // No X/Z inset enforcement - allow subdivisions to share parent boundaries
                                // Only sibling collision checks remain
                                Claim parentClaim = playerData.claimSubdividing;
                                int proposedMinX = Math.min(
                                    playerData.lastShovelLocation.getBlockX(),
                                    clickedBlock.getX()
                                );
                                int proposedMaxX = Math.max(
                                    playerData.lastShovelLocation.getBlockX(),
                                    clickedBlock.getX()
                                );
                                int proposedMinZ = Math.min(
                                    playerData.lastShovelLocation.getBlockZ(),
                                    clickedBlock.getZ()
                                );
                                int proposedMaxZ = Math.max(
                                    playerData.lastShovelLocation.getBlockZ(),
                                    clickedBlock.getZ()
                                );

                                for (Claim sibling : parentClaim.children) {
                                    if (!sibling.inDataStore || sibling.is3D()) continue;

                                    Location siblingMin = sibling.getLesserBoundaryCorner();
                                    Location siblingMax = sibling.getGreaterBoundaryCorner();

                                    int siblingMinX = Math.min(siblingMin.getBlockX(), siblingMax.getBlockX());
                                    int siblingMaxX = Math.max(siblingMin.getBlockX(), siblingMax.getBlockX());
                                    int siblingMinZ = Math.min(siblingMin.getBlockZ(), siblingMax.getBlockZ());
                                    int siblingMaxZ = Math.max(siblingMin.getBlockZ(), siblingMax.getBlockZ());

                                    boolean zOverlap = proposedMinZ <= siblingMaxZ && proposedMaxZ >= siblingMinZ;
                                    if (zOverlap) {
                                        int gapEast = proposedMinX - siblingMaxX;
                                        if (gapEast >= 0 && gapEast < 1) {
                                            GriefPrevention.sendMessage(
                                                player,
                                                TextMode.Err,
                                                Messages.InnerSubdivisionTooClose
                                            );
                                            return;
                                        }

                                        int gapWest = siblingMinX - proposedMaxX;
                                        if (gapWest >= 0 && gapWest < 1) {
                                            GriefPrevention.sendMessage(
                                                player,
                                                TextMode.Err,
                                                Messages.InnerSubdivisionTooClose
                                            );
                                            return;
                                        }
                                    }

                                    boolean xOverlap = proposedMinX <= siblingMaxX && proposedMaxX >= siblingMinX;
                                    if (xOverlap) {
                                        int gapSouth = proposedMinZ - siblingMaxZ;
                                        if (gapSouth >= 0 && gapSouth < 1) {
                                            GriefPrevention.sendMessage(
                                                player,
                                                TextMode.Err,
                                                Messages.InnerSubdivisionTooClose
                                            );
                                            return;
                                        }

                                        int gapNorth = siblingMinZ - proposedMaxZ;
                                        if (gapNorth >= 0 && gapNorth < 1) {
                                            GriefPrevention.sendMessage(
                                                player,
                                                TextMode.Err,
                                                Messages.InnerSubdivisionTooClose
                                            );
                                            return;
                                        }
                                    }
                                }
                            }

                            // try to create a new claim (will return null if this subdivision overlaps
                            // another)
                            CreateClaimResult subdivisionResult = this.dataStore.createClaim(
                                player.getWorld(),
                                playerData.lastShovelLocation.getBlockX(),
                                clickedBlock.getX(),
                                minY,
                                maxY,
                                playerData.lastShovelLocation.getBlockZ(),
                                clickedBlock.getZ(),
                                null, // owner is not used for subdivisions
                                playerData.claimSubdividing,
                                null,
                                player
                            );

                            // if it didn't succeed, tell the player why
                            if (!subdivisionResult.succeeded || subdivisionResult.claim == null) {
                                if (subdivisionResult.claim != null) {
                                    GriefPrevention.sendMessage(
                                        player,
                                        TextMode.Err,
                                        Messages.CreateSubdivisionOverlap
                                    );
                                    visualizeConflict(
                                        player,
                                        playerData,
                                        subdivisionResult.claim,
                                        clickedBlock,
                                        subdivisionResult.claim.is3D()
                                    );
                                } else {
                                    GriefPrevention.sendMessage(
                                        player,
                                        TextMode.Err,
                                        Messages.CreateClaimFailOverlapRegion
                                    );
                                }

                                return;
                            }
                            // otherwise, advise him on the /trust command and show him his new subdivision
                            else {
                                GriefPrevention.sendMessage(player, TextMode.Success, Messages.SubdivisionSuccess);
                                VisualizationType subdivisionViz = subdivisionResult.claim.is3D()
                                    ? VisualizationType.SUBDIVISION_3D
                                    : VisualizationType.SUBDIVISION;
                                BoundaryVisualization.visualizeClaim(
                                    player,
                                    subdivisionResult.claim,
                                    subdivisionViz,
                                    clickedBlock
                                );
                                playerData.claimSubdividing = null;
                            }
                        }
                    }
                    // otherwise tell him he can't create a claim here, and show him the existing
                    // claim
                    // also advise him to consider /abandonclaim or resizing the existing claim
                    else {
                        // Admin3D mode: allow starting a stacked 3D admin claim at a different Y level
                        if (
                            playerData.shovelMode == ShovelMode.Admin3D &&
                            claim.is3D() &&
                            claim.isAdminClaim() &&
                            playerData.lastShovelLocation == null
                        ) {
                            playerData.lastShovelLocation = clickedBlock.getLocation();
                            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimStart);
                            BoundaryVisualization.visualizeArea(
                                player,
                                new BoundingBox(clickedBlock),
                                VisualizationType.INITIALIZE_ZONE_3D,
                                clickedBlock.getY()
                            );
                            return;
                        }

                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlap);
                        visualizeConflict(player, playerData, claim, clickedBlock, claim.is3D());
                    }
                }
                // otherwise tell the player he can't claim here because it's someone else's
                // claim, and show him the claim
                else {
                    GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
                    visualizeConflict(player, playerData, claim, clickedBlock, claim.is3D());
                }

                return;
            }

            // otherwise, the player isn't in an existing claim!

            // if he hasn't already start a claim with a previous shovel action
            Location lastShovelLocation = playerData.lastShovelLocation;
            if (lastShovelLocation == null) {
                // if claims are not enabled in this world and it's not an administrative claim,
                // display an error message and stop
                if (!instance.claimsEnabledForWorld(player.getWorld())) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimsDisabledWorld);
                    return;
                }

                // if he's at the claim count per player limit already and doesn't have
                // permission to bypass, display an error message
                if (
                    instance.config_claims_maxClaimsPerPlayer > 0 &&
                    !player.hasPermission("griefprevention.overrideclaimcountlimit") &&
                    playerData.getClaims().size() >= instance.config_claims_maxClaimsPerPlayer
                ) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimCreationFailedOverClaimCountLimit);
                    return;
                }

                // remember it, and start him on the new claim
                if (playerData.shovelMode == ShovelMode.Admin3D) {
                    playerData.lastShovelLocation = clickedBlock.getLocation();
                    GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimStart);
                    BoundaryVisualization.visualizeArea(
                        player,
                        new BoundingBox(clickedBlock),
                        VisualizationType.INITIALIZE_ZONE_3D,
                        clickedBlock.getY()
                    );
                } else {
                    playerData.lastShovelLocation = clickedBlock.getLocation();
                    GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimStart);
                    BoundaryVisualization.visualizeArea(
                        player,
                        new BoundingBox(clickedBlock),
                        VisualizationType.INITIALIZE_ZONE
                    );
                }
            }
            // otherwise, he's trying to finish creating a claim by setting the other
            // boundary corner
            else {
                // if last shovel location was in a different world, assume the player is
                // starting the create-claim workflow over
                if (!lastShovelLocation.getWorld().equals(clickedBlock.getWorld())) {
                    playerData.lastShovelLocation = null;
                    this.onPlayerInteract(event);
                    return;
                }

                // apply pvp rule
                if (playerData.inPvpCombat()) {
                    GriefPrevention.sendRateLimitedErrorMessage(player, Messages.NoClaimDuringPvP);
                    return;
                }

                // apply minimum claim dimensions rule
                int newWidth;
                int newHeight;

                try {
                    newWidth = Math.abs(Math.subtractExact(lastShovelLocation.getBlockX(), clickedBlock.getX())) + 1;
                    newHeight = Math.abs(Math.subtractExact(lastShovelLocation.getBlockZ(), clickedBlock.getZ())) + 1;
                } catch (ArithmeticException e) {
                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Err,
                        Messages.CreateClaimInsufficientBlocks,
                        String.valueOf(Integer.MAX_VALUE)
                    );
                    playerData.lastShovelLocation = null;
                    playerData.claimSubdividing = null;
                    return;
                }

                if (playerData.shovelMode != ShovelMode.Admin && playerData.shovelMode != ShovelMode.Admin3D) {
                    if (newWidth < instance.config_claims_minWidth || newHeight < instance.config_claims_minWidth) {
                        // this IF block is a workaround for craftbukkit bug which fires two events for
                        // one interaction
                        if (newWidth != 1 && newHeight != 1) {
                            GriefPrevention.sendMessage(
                                player,
                                TextMode.Err,
                                Messages.NewClaimTooNarrow,
                                String.valueOf(instance.config_claims_minWidth)
                            );
                        }
                        return;
                    }

                    int newArea = newWidth * newHeight;
                    if (newArea < instance.config_claims_minArea) {
                        if (newArea != 1) {
                            GriefPrevention.sendMessage(
                                player,
                                TextMode.Err,
                                Messages.ResizeClaimInsufficientArea,
                                String.valueOf(instance.config_claims_minArea)
                            );
                        }

                        return;
                    }
                }

                UUID playerID = player.getUniqueId();

                // if not an administrative claim, verify the player has enough claim blocks for
                // this new claim
                if (playerData.shovelMode != ShovelMode.Admin && playerData.shovelMode != ShovelMode.Admin3D) {
                    int newClaimArea = newWidth * newHeight;
                    int remainingBlocks = playerData.getRemainingClaimBlocks();
                    if (newClaimArea > remainingBlocks) {
                        GriefPrevention.sendMessage(
                            player,
                            TextMode.Err,
                            Messages.CreateClaimInsufficientBlocks,
                            String.valueOf(newClaimArea - remainingBlocks)
                        );
                        instance.dataStore.tryAdvertiseAdminAlternatives(player);
                        return;
                    }
                } else {
                    playerID = null;
                }

                boolean is3dAdminClaim = playerData.shovelMode == ShovelMode.Admin3D;

                // try to create a new claim
                CreateClaimResult result = this.dataStore.createClaim(
                    player.getWorld(),
                    lastShovelLocation.getBlockX(),
                    clickedBlock.getX(),
                    is3dAdminClaim
                        ? lastShovelLocation.getBlockY()
                        : lastShovelLocation.getBlockY() - instance.config_claims_claimsExtendIntoGroundDistance,
                    is3dAdminClaim
                        ? clickedBlock.getY()
                        : clickedBlock.getY() - instance.config_claims_claimsExtendIntoGroundDistance,
                    lastShovelLocation.getBlockZ(),
                    clickedBlock.getZ(),
                    playerID,
                    null,
                    null,
                    player,
                    is3dAdminClaim
                );

                // if it didn't succeed, tell the player why
                if (!result.succeeded || result.claim == null) {
                    if (result.claim != null) {
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);
                        visualizeConflict(player, playerData, result.claim, clickedBlock, result.claim.is3D());
                    } else {
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
                    }

                    return;
                }
                // otherwise, advise him on the /trust command and show him his new claim
                else {
                    GriefPrevention.sendMessage(player, TextMode.Success, Messages.CreateClaimSuccess);
                    VisualizationType vizType = is3dAdminClaim
                        ? VisualizationType.ADMIN_CLAIM_3D
                        : VisualizationType.CLAIM;
                    BoundaryVisualization.visualizeClaim(player, result.claim, vizType, clickedBlock);
                    playerData.lastShovelLocation = null;
                }
            }
        }
    }

    private boolean isCreativeEntityPlacement(Material material) {
        return material == Material.ARMOR_STAND
                || material == Material.MINECART
                || CompatUtil.isMaterial(material, "FURNACE_MINECART")
                || CompatUtil.isMaterial(material, "CHEST_MINECART")
                || CompatUtil.isMaterial(material, "TNT_MINECART")
                || CompatUtil.isMaterial(material, "HOPPER_MINECART")
                || MaterialTagCompat.isTagged("ITEMS_BOATS", material)
                || spawnEggs.contains(material)
                || CompatUtil.isMaterial(material, "ITEM_FRAME")
                || CompatUtil.isMaterial(material, "GLOW_ITEM_FRAME")
                || material.name().startsWith("INFESTED_");
    }

    // Helper container for a corner raycast hit
    private static class CornerHit {

        @SuppressWarnings("unused")
        final Claim claim;

        final int x, y, z;
        final double t;

        CornerHit(Claim claim, int x, int y, int z, double t) {
            this.claim = claim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }
    }

    private static boolean isCornerMatch(Claim claim, Block block) {
        if (claim == null || block == null) {
            return false;
        }

        int blockX = block.getX();
        int blockZ = block.getZ();

        if (claim.getCornerIndexAt(blockX, blockZ) < 0) {
            return false;
        }

        if (!claim.is3D()) {
            return true;
        }

        int blockY = block.getY();
        Location lesser = claim.getLesserBoundaryCorner();
        Location greater = claim.getGreaterBoundaryCorner();
        int minY = Math.min(lesser.getBlockY(), greater.getBlockY());
        int maxY = Math.max(lesser.getBlockY(), greater.getBlockY());
        return blockY == minY || blockY == maxY;
    }

    private boolean shouldUse3DCornerHit(Player player, @Nullable Block clickedBlock, CornerHit cornerHit) {
        if (clickedBlock == null || clickedBlock.getType() == Material.AIR) {
            return true;
        }

        Vector eyePos = player.getEyeLocation().toVector();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Vector clickedCenter = clickedBlock
            .getLocation()
            .toVector()
            .add(new Vector(0.5, 0.5, 0.5));
        double clickedT = clickedCenter.subtract(eyePos).dot(dir);
        return clickedT < 0 || cornerHit.t <= clickedT + 2.0;
    }

    private boolean tryResizeShapedClaim(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Block clickedBlock
    ) {
        Claim claim = playerData.claimResizing;
        if (claim == null || !claim.inDataStore || !claim.isShaped() || claim.parent != null || claim.is3D()) {
            return false;
        }

        // Don't try to resize if in shaped mode - this is for boundary marker operations
        if (playerData.shovelMode == ShovelMode.Shaped) {
            return false;
        }

        Location anchor = playerData.lastShovelLocation;
        if (anchor == null) {
            return false;
        }

        int cornerIndex = claim.getCornerIndexAt(anchor.getBlockX(), anchor.getBlockZ());
        if (cornerIndex < 0) {
            return false;
        }

        OrthogonalPolygonValidationResult moveResult = resolveShapedResizeMove(
            player,
            claim,
            claim.getBoundaryPolygon(),
            cornerIndex,
            new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ())
        );
        if (!moveResult.isValid() || moveResult.polygon() == null) {
            boolean selfIntersection = moveResult
                .issues()
                .stream()
                .anyMatch(issue -> issue.type() == OrthogonalPolygonValidationIssueType.SELF_INTERSECTION);
            GriefPrevention.sendMessage(
                player,
                TextMode.Err,
                selfIntersection ? "That corner move would intersect the claim." : "That corner can't move there."
            );
            return true;
        }

        CreateClaimResult updateResult = this.dataStore.updateShapedClaim(
            player,
            playerData,
            claim,
            moveResult.polygon()
        );
        if (!updateResult.succeeded || updateResult.claim == null) {
            if (updateResult.denialMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, updateResult.denialMessage.get());
            } else if (updateResult.claim != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlap);
                BoundaryVisualization.visualizeClaim(player, updateResult.claim, VisualizationType.CONFLICT_ZONE);
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapRegion);
            }
            return true;
        }

        int claimBlocksRemaining = 0;
        if (!updateResult.claim.isAdminClaim()) {
            UUID ownerID = updateResult.claim.ownerID;
            if (ownerID != null) {
                if (ownerID.equals(player.getUniqueId())) {
                    claimBlocksRemaining = playerData.getRemainingClaimBlocks();
                } else {
                    PlayerData ownerData = this.dataStore.getPlayerData(ownerID);
                    claimBlocksRemaining = ownerData.getRemainingClaimBlocks();
                    OfflinePlayer owner = GriefPrevention.instance.getServer().getOfflinePlayer(ownerID);
                    if (!owner.isOnline()) {
                        this.dataStore.clearCachedPlayerData(ownerID);
                    }
                }
            }
        }

        GriefPrevention.sendMessage(
            player,
            TextMode.Success,
            Messages.ClaimResizeSuccess,
            String.valueOf(claimBlocksRemaining)
        );
        BoundaryVisualization.visualizeClaim(
            player,
            updateResult.claim,
            updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
            clickedBlock
        );
        playerData.claimResizing = null;
        playerData.lastShovelLocation = null;
        return true;
    }

    private @NotNull OrthogonalPolygonValidationResult resolveShapedResizeMove(
        @NotNull Player player,
        @NotNull Claim claim,
        @NotNull OrthogonalPolygon polygon,
        int cornerIndex,
        @NotNull OrthogonalPoint2i target
    ) {
        OrthogonalPoint2i anchor = polygon.corners().get(cornerIndex);
        int deltaX = target.x() - anchor.x();
        int deltaZ = target.z() - anchor.z();

        if (deltaX == 0 && deltaZ == 0) {
            return polygon.moveCorner(cornerIndex, anchor);
        }

        // Dragging a corner onto another vertex of the same claim (flattening a nib / collinear bump).
        // Must use moveCorner: face-run logic uses expandEdge/moveEdgeRun and (a) locateResizeSubsegment only
        // matches strictly interior edge points, so exact corner clicks miss, and (b) moveEdgeRun can reject
        // or distort a merge that moveCorner + normalization already handles.
        for (int i = 0; i < polygon.corners().size(); i++) {
            if (i != cornerIndex && polygon.corners().get(i).equals(target)) {
                return polygon.moveCorner(cornerIndex, target);
            }
        }

        if (deltaX != 0 && deltaZ != 0) {
            if (polygon.isRemovableNode(cornerIndex) || isConcaveCorner(polygon, cornerIndex)) {
                OrthogonalPolygonValidationResult nibResult = resolveShapedNibResizeMove(
                    player,
                    claim,
                    polygon,
                    cornerIndex,
                    target
                );
                if (isChangedShapedResize(polygon, nibResult)) {
                    return nibResult;
                }
            }
            return polygon.moveCorner(cornerIndex, target);
        }

        FaceRun faceRun = findStraightFaceRun(polygon, cornerIndex, deltaX != 0);
        if (faceRun == null) {
            return polygon.moveCorner(cornerIndex, target);
        }

        Integer selectedEdgeIndex = selectResizeSubsegment(player, polygon, faceRun, target);
        int amount = deltaX != 0 ? deltaX : deltaZ;
        if (selectedEdgeIndex != null) {
            return polygon.expandEdge(selectedEdgeIndex, amount);
        }

        if (polygon.isRemovableNode(cornerIndex) || isConcaveCorner(polygon, cornerIndex)) {
            OrthogonalPolygonValidationResult orthogonalNibResult = resolveOrthogonalShapedNibResizeMove(
                player,
                claim,
                polygon,
                cornerIndex,
                target
            );
            if (orthogonalNibResult != null && isChangedShapedResize(polygon, orthogonalNibResult)) {
                return orthogonalNibResult;
            }
        }

        // If no subsegment selected but target equals a corner on the face run, use moveCorner
        // to merge/flatten the nib (e.g., dragging (4,4) onto (8,4) on a Z=4 horizontal face)
        if (isTargetCornerOnFaceRun(polygon, faceRun, target)) {
            return polygon.moveCorner(cornerIndex, target);
        }

        return polygon.moveEdgeRun(faceRun.startEdgeIndex(), faceRun.endEdgeIndex(), amount);
    }

    private boolean isChangedShapedResize(
        @NotNull OrthogonalPolygon original,
        @NotNull OrthogonalPolygonValidationResult result
    ) {
        return result.isValid() &&
            result.polygon() != null &&
            !result.polygon().corners().equals(original.corners());
    }

    private boolean isTargetCornerOnFaceRun(
        @NotNull OrthogonalPolygon polygon,
        @NotNull FaceRun faceRun,
        @NotNull OrthogonalPoint2i target
    ) {
        int edgeIndex = faceRun.startEdgeIndex();
        while (true) {
            OrthogonalEdge2i edge = polygon.edges().get(edgeIndex);
            // Check both endpoints of this edge
            if (edge.start().equals(target) || edge.end().equals(target)) {
                return true;
            }

            if (edgeIndex == faceRun.endEdgeIndex()) {
                break;
            }
            edgeIndex = (edgeIndex + 1) % polygon.edges().size();
        }
        return false;
    }

    private @NotNull OrthogonalPolygonValidationResult resolveShapedNibResizeMove(
        @NotNull Player player,
        @NotNull Claim claim,
        @NotNull OrthogonalPolygon polygon,
        int cornerIndex,
        @NotNull OrthogonalPoint2i target
    ) {
        OrthogonalPoint2i anchor = polygon.corners().get(cornerIndex);
        List<ShapedNibResizeAttempt> attempts = new ArrayList<>(2);
        addShapedNibResizeAttempt(
            attempts,
            polygon,
            tryShapedNibResizeCandidate(
                player,
                claim,
                polygon,
                anchor,
                new OrthogonalPoint2i(anchor.x(), target.z()),
                target,
                new OrthogonalPoint2i(target.x(), anchor.z())
            )
        );
        addShapedNibResizeAttempt(
            attempts,
            polygon,
            tryShapedNibResizeCandidate(
                player,
                claim,
                polygon,
                anchor,
                new OrthogonalPoint2i(target.x(), anchor.z()),
                target,
                new OrthogonalPoint2i(anchor.x(), target.z())
            )
        );

        ShapedNibResizeAttempt bestAttempt = selectBestShapedNibResizeAttempt(attempts);
        if (bestAttempt != null) {
            return bestAttempt.result();
        }

        return polygon.moveCorner(cornerIndex, target);
    }

    private boolean isConcaveCorner(@NotNull OrthogonalPolygon polygon, int cornerIndex) {
        List<OrthogonalPoint2i> corners = polygon.corners();
        if (cornerIndex < 0 || cornerIndex >= corners.size() || corners.size() < 4) {
            return false;
        }

        OrthogonalPoint2i previous = corners.get(Math.floorMod(cornerIndex - 1, corners.size()));
        OrthogonalPoint2i current = corners.get(cornerIndex);
        OrthogonalPoint2i next = corners.get((cornerIndex + 1) % corners.size());

        long edgeAX = current.x() - previous.x();
        long edgeAZ = current.z() - previous.z();
        long edgeBX = next.x() - current.x();
        long edgeBZ = next.z() - current.z();
        long cross = edgeAX * edgeBZ - edgeAZ * edgeBX;
        int crossSign = Long.compare(cross, 0L);
        if (crossSign == 0) {
            return false;
        }

        int winding = polygonWindingSign(polygon.corners());
        return winding != 0 && crossSign != winding;
    }

    private int polygonWindingSign(@NotNull List<OrthogonalPoint2i> corners) {
        long area2 = 0L;
        for (int i = 0; i < corners.size(); i++) {
            OrthogonalPoint2i current = corners.get(i);
            OrthogonalPoint2i next = corners.get((i + 1) % corners.size());
            area2 += (long) current.x() * next.z() - (long) next.x() * current.z();
        }

        return Long.compare(area2, 0L);
    }

    private @Nullable OrthogonalPolygonValidationResult resolveOrthogonalShapedNibResizeMove(
        @NotNull Player player,
        @NotNull Claim claim,
        @NotNull OrthogonalPolygon polygon,
        int cornerIndex,
        @NotNull OrthogonalPoint2i target
    ) {
        OrthogonalPoint2i anchor = polygon.corners().get(cornerIndex);
        int deltaX = target.x() - anchor.x();
        int deltaZ = target.z() - anchor.z();
        if ((deltaX == 0) == (deltaZ == 0)) {
            return null;
        }

        OrthogonalPoint2i secondOutside;
        OrthogonalPoint2i reconnectProbe;
        if (deltaX == 0) {
            int playerX = player.getLocation().getBlockX();
            if (playerX == anchor.x()) {
                return null;
            }

            secondOutside = new OrthogonalPoint2i(playerX, target.z());
            reconnectProbe = new OrthogonalPoint2i(playerX, anchor.z());
        } else {
            int playerZ = player.getLocation().getBlockZ();
            if (playerZ == anchor.z()) {
                return null;
            }

            secondOutside = new OrthogonalPoint2i(target.x(), playerZ);
            reconnectProbe = new OrthogonalPoint2i(anchor.x(), playerZ);
        }

        if (secondOutside.equals(anchor) || secondOutside.equals(target) || reconnectProbe.equals(secondOutside)) {
            return null;
        }

        ClaimEditorSession session = ClaimEditorSession.idle(player.getUniqueId())
            .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL)
            .withTarget(new ClaimEditTarget(ClaimEditTargetType.EXISTING_PARENT_CLAIM, claim.getID()))
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
            )
            .withOpenPath(
                new com.griefprevention.claims.editor.ShapedPathDraft(
                    claim.getID(),
                    Collections.singletonList(anchor),
                    null,
                    false
                )
            );

        ClaimEditResult first = claimEditor.apply(
            session,
            new ClaimEditIntent(
                ClaimEditIntentType.ADD_CORNER,
                ClaimEditSource.TOOL,
                null,
                claim.getID(),
                target,
                null,
                true,
                Collections.emptyList()
            )
        );
        if (!first.success()) {
            return null;
        }

        ClaimEditResult second = claimEditor.apply(
            first.session(),
            new ClaimEditIntent(
                ClaimEditIntentType.ADD_CORNER,
                ClaimEditSource.TOOL,
                null,
                claim.getID(),
                secondOutside,
                null,
                true,
                Collections.emptyList()
            )
        );
        if (!second.success()) {
            return null;
        }

        ClaimEditResult merged = claimEditor.apply(
            second.session(),
            new ClaimEditIntent(
                ClaimEditIntentType.ADD_CORNER,
                ClaimEditSource.TOOL,
                null,
                claim.getID(),
                reconnectProbe,
                null,
                true,
                Collections.emptyList()
            )
        );
        if (!merged.success() || merged.preview().polygon() == null) {
            return null;
        }

        OrthogonalPolygonValidationResult result = OrthogonalPolygon.validatePath(
            merged.preview().polygon().closedPath()
        );
        if (!result.isValid() || result.polygon() == null || !polygonContainsPolygon(result.polygon(), polygon)) {
            return null;
        }

        return result;
    }

    private @Nullable OrthogonalPolygonValidationResult tryShapedNibResizeCandidate(
        @NotNull Player player,
        @NotNull Claim claim,
        @NotNull OrthogonalPolygon polygon,
        @NotNull OrthogonalPoint2i anchor,
        @NotNull OrthogonalPoint2i firstStep,
        @NotNull OrthogonalPoint2i target,
        @NotNull OrthogonalPoint2i reconnect
    ) {
        if (
            firstStep.equals(anchor) ||
            firstStep.equals(target) ||
            reconnect.equals(anchor) ||
            reconnect.equals(firstStep) ||
            reconnect.equals(target)
        ) {
            return null;
        }

        ClaimEditorSession session = ClaimEditorSession.idle(player.getUniqueId())
            .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL)
            .withTarget(new ClaimEditTarget(ClaimEditTargetType.EXISTING_PARENT_CLAIM, claim.getID()))
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
            )
            .withOpenPath(
                new com.griefprevention.claims.editor.ShapedPathDraft(
                    claim.getID(),
                    Arrays.asList(anchor, firstStep),
                    null,
                    false
                )
            );

        ClaimEditResult second = claimEditor.apply(
            session,
            new ClaimEditIntent(
                ClaimEditIntentType.ADD_CORNER,
                ClaimEditSource.TOOL,
                null,
                claim.getID(),
                target,
                null,
                true,
                Collections.emptyList()
            )
        );
        if (!second.success()) {
            return null;
        }

        ClaimEditResult merged = claimEditor.apply(
            second.session(),
            new ClaimEditIntent(
                ClaimEditIntentType.ADD_CORNER,
                ClaimEditSource.TOOL,
                null,
                claim.getID(),
                reconnect,
                null,
                true,
                Collections.emptyList()
            )
        );
        if (!merged.success() || merged.preview().polygon() == null) {
            return null;
        }

        return OrthogonalPolygon.validatePath(merged.preview().polygon().closedPath());
    }

    private void addShapedNibResizeAttempt(
        @NotNull List<ShapedNibResizeAttempt> attempts,
        @NotNull OrthogonalPolygon originalPolygon,
        @Nullable OrthogonalPolygonValidationResult result
    ) {
        if (result == null || !isChangedShapedResize(originalPolygon, result)) {
            return;
        }

        attempts.add(
            new ShapedNibResizeAttempt(
                result,
                polygonContainsPolygon(result.polygon(), originalPolygon),
                polygonArea(result.polygon()),
                polygonOverlapArea(originalPolygon, result.polygon())
            )
        );
    }

    private @Nullable ShapedNibResizeAttempt selectBestShapedNibResizeAttempt(
        @NotNull List<ShapedNibResizeAttempt> attempts
    ) {
        ShapedNibResizeAttempt best = null;
        for (ShapedNibResizeAttempt attempt : attempts) {
            if (!attempt.containsOriginal()) {
                continue;
            }

            if (best == null || compareShapedNibResizeAttempts(attempt, best) < 0) {
                best = attempt;
            }
        }

        return best;
    }

    private int compareShapedNibResizeAttempts(
        @NotNull ShapedNibResizeAttempt first,
        @NotNull ShapedNibResizeAttempt second
    ) {
        if (first.area() != second.area()) {
            return Integer.compare(second.area(), first.area());
        }

        if (first.overlap() != second.overlap()) {
            return Integer.compare(second.overlap(), first.overlap());
        }

        return 0;
    }

    private boolean polygonContains(@NotNull OrthogonalPolygon polygon, @NotNull OrthogonalPoint2i point) {
        if (polygon.corners().contains(point)) {
            return true;
        }

        if (!polygon.edgeIndexesContainingInteriorPoint(point).isEmpty()) {
            return true;
        }

        double sampleX = point.x() + 0.5D;
        double sampleZ = point.z() + 0.5D;
        boolean inside = false;
        List<OrthogonalPoint2i> corners = polygon.corners();
        for (int i = 0, j = corners.size() - 1; i < corners.size(); j = i++) {
            OrthogonalPoint2i a = corners.get(i);
            OrthogonalPoint2i b = corners.get(j);
            boolean crosses = a.z() > sampleZ != b.z() > sampleZ;
            if (!crosses) {
                continue;
            }

            double intersectionX = ((double) (b.x() - a.x()) * (sampleZ - a.z())) / (double) (b.z() - a.z()) + a.x();
            if (sampleX < intersectionX) {
                inside = !inside;
            }
        }

        return inside;
    }

    private int polygonArea(@NotNull OrthogonalPolygon polygon) {
        int area = 0;
        for (int x = polygon.minX(); x <= polygon.maxX(); x++) {
            for (int z = polygon.minZ(); z <= polygon.maxZ(); z++) {
                if (polygonContains(polygon, new OrthogonalPoint2i(x, z))) {
                    area++;
                }
            }
        }

        return area;
    }

    private boolean polygonContainsPolygon(@NotNull OrthogonalPolygon container, @NotNull OrthogonalPolygon contents) {
        for (int x = contents.minX(); x <= contents.maxX(); x++) {
            for (int z = contents.minZ(); z <= contents.maxZ(); z++) {
                OrthogonalPoint2i point = new OrthogonalPoint2i(x, z);
                if (polygonContains(contents, point) && !polygonContains(container, point)) {
                    return false;
                }
            }
        }

        return true;
    }

    private int polygonOverlapArea(@NotNull OrthogonalPolygon first, @NotNull OrthogonalPolygon second) {
        int area = 0;
        int minX = Math.max(first.minX(), second.minX());
        int maxX = Math.min(first.maxX(), second.maxX());
        int minZ = Math.max(first.minZ(), second.minZ());
        int maxZ = Math.min(first.maxZ(), second.maxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                OrthogonalPoint2i point = new OrthogonalPoint2i(x, z);
                if (polygonContains(first, point) && polygonContains(second, point)) {
                    area++;
                }
            }
        }

        return area;
    }

    private static final class ShapedNibResizeAttempt {

        private final @NotNull OrthogonalPolygonValidationResult result;
        private final boolean containsOriginal;
        private final int area;
        private final int overlap;

        private ShapedNibResizeAttempt(
            @NotNull OrthogonalPolygonValidationResult result,
            boolean containsOriginal,
            int area,
            int overlap
        ) {
            this.result = result;
            this.containsOriginal = containsOriginal;
            this.area = area;
            this.overlap = overlap;
        }

        @NotNull
        OrthogonalPolygonValidationResult result() {
            return result;
        }

        boolean containsOriginal() {
            return containsOriginal;
        }

        int area() {
            return area;
        }

        int overlap() {
            return overlap;
        }
    }

    private @Nullable FaceRun findStraightFaceRun(
        @NotNull OrthogonalPolygon polygon,
        int cornerIndex,
        boolean movingVerticalFace
    ) {
        int edgeCount = polygon.edges().size();
        int previousEdgeIndex = Math.floorMod(cornerIndex - 1, edgeCount);
        int nextEdgeIndex = cornerIndex % edgeCount;
        Integer anchorEdgeIndex = null;

        OrthogonalEdge2i previousEdge = polygon.edges().get(previousEdgeIndex);
        if ((movingVerticalFace && previousEdge.isVertical()) || (!movingVerticalFace && previousEdge.isHorizontal())) {
            anchorEdgeIndex = previousEdgeIndex;
        }

        OrthogonalEdge2i nextEdge = polygon.edges().get(nextEdgeIndex);
        if ((movingVerticalFace && nextEdge.isVertical()) || (!movingVerticalFace && nextEdge.isHorizontal())) {
            anchorEdgeIndex = nextEdgeIndex;
        }

        if (anchorEdgeIndex == null) {
            return null;
        }

        OrthogonalEdge2i referenceEdge = polygon.edges().get(anchorEdgeIndex);
        boolean horizontal = referenceEdge.isHorizontal();
        int coordinate = horizontal ? referenceEdge.start().z() : referenceEdge.start().x();

        int startEdgeIndex = anchorEdgeIndex;
        while (true) {
            int previousIndex = Math.floorMod(startEdgeIndex - 1, edgeCount);
            if (previousIndex == anchorEdgeIndex) {
                break;
            }

            OrthogonalEdge2i edge = polygon.edges().get(previousIndex);
            if (!isSameStraightFace(edge, horizontal, coordinate)) {
                break;
            }

            startEdgeIndex = previousIndex;
        }

        int endEdgeIndex = anchorEdgeIndex;
        while (true) {
            int nextIndex = (endEdgeIndex + 1) % edgeCount;
            if (nextIndex == startEdgeIndex) {
                break;
            }

            OrthogonalEdge2i edge = polygon.edges().get(nextIndex);
            if (!isSameStraightFace(edge, horizontal, coordinate)) {
                break;
            }

            endEdgeIndex = nextIndex;
        }

        return new FaceRun(startEdgeIndex, endEdgeIndex, horizontal);
    }

    private boolean isSameStraightFace(@NotNull OrthogonalEdge2i edge, boolean horizontal, int coordinate) {
        if (horizontal) {
            return edge.isHorizontal() && edge.start().z() == coordinate;
        }

        return edge.isVertical() && edge.start().x() == coordinate;
    }

    private @Nullable Integer selectResizeSubsegment(
        @NotNull Player player,
        @NotNull OrthogonalPolygon polygon,
        @NotNull FaceRun faceRun,
        @NotNull OrthogonalPoint2i target
    ) {
        int targetCoordinate = faceRun.horizontal() ? target.x() : target.z();
        Integer targetEdgeIndex = locateResizeSubsegment(polygon, faceRun, targetCoordinate);
        if (targetEdgeIndex != null) {
            return targetEdgeIndex;
        }

        int playerCoordinate = faceRun.horizontal()
            ? player.getLocation().getBlockX()
            : player.getLocation().getBlockZ();
        return locateResizeSubsegment(polygon, faceRun, playerCoordinate);
    }

    private @Nullable Integer locateResizeSubsegment(
        @NotNull OrthogonalPolygon polygon,
        @NotNull FaceRun faceRun,
        int tangentCoordinate
    ) {
        int edgeIndex = faceRun.startEdgeIndex();
        while (true) {
            OrthogonalEdge2i edge = polygon.edges().get(edgeIndex);
            int min = faceRun.horizontal() ? edge.minX() : edge.minZ();
            int max = faceRun.horizontal() ? edge.maxX() : edge.maxZ();
            if (tangentCoordinate > min && tangentCoordinate < max) {
                return edgeIndex;
            }

            if (edgeIndex == faceRun.endEdgeIndex()) {
                break;
            }

            edgeIndex = (edgeIndex + 1) % polygon.edges().size();
        }

        return null;
    }

    private static final class FaceRun {

        private final int startEdgeIndex;
        private final int endEdgeIndex;
        private final boolean horizontal;

        private FaceRun(int startEdgeIndex, int endEdgeIndex, boolean horizontal) {
            this.startEdgeIndex = startEdgeIndex;
            this.endEdgeIndex = endEdgeIndex;
            this.horizontal = horizontal;
        }

        int startEdgeIndex() {
            return startEdgeIndex;
        }

        int endEdgeIndex() {
            return endEdgeIndex;
        }

        boolean horizontal() {
            return horizontal;
        }
    }

    private boolean handleShapedResizeInteraction(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Block clickedBlock
    ) {
        if (playerData.claimResizing != null && playerData.claimResizing.inDataStore) {
            if (playerData.lastShovelLocation == null) {
                return true;
            }
            if (clickedBlock.getLocation().equals(playerData.lastShovelLocation)) {
                return true;
            }

            if (tryResizeShapedClaim(player, playerData, clickedBlock)) {
                return true;
            }

            int newx1, newx2, newz1, newz2, newy1, newy2;
            if (
                playerData.lastShovelLocation.getBlockX() ==
                playerData.claimResizing.getLesserBoundaryCorner().getBlockX()
            ) {
                newx1 = clickedBlock.getX();
                newx2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockX();
            } else {
                newx1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockX();
                newx2 = clickedBlock.getX();
            }

            if (
                playerData.lastShovelLocation.getBlockZ() ==
                playerData.claimResizing.getLesserBoundaryCorner().getBlockZ()
            ) {
                newz1 = clickedBlock.getZ();
                newz2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockZ();
            } else {
                newz1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockZ();
                newz2 = clickedBlock.getZ();
            }

            // Y resizing behavior
            if (playerData.claimResizing.is3D()) {
                // For 3D subclaims, allow vertical resizing when the initial corner was at minY
                // or maxY
                int currentMinY = playerData.claimResizing.getLesserBoundaryCorner().getBlockY();
                int currentMaxY = playerData.claimResizing.getGreaterBoundaryCorner().getBlockY();
                int startY = playerData.lastShovelLocation.getBlockY();
                int endY = clickedBlock.getY();
                boolean isSingleLayer = currentMinY == currentMaxY;
                newy1 = currentMinY;
                newy2 = currentMaxY;
                if (isSingleLayer || startY == currentMinY) {
                    newy1 = Math.min(endY, currentMaxY);
                    if (endY > currentMaxY) newy2 = endY;
                } else if (startY == currentMaxY) {
                    newy2 = Math.max(endY, currentMinY);
                    if (endY < currentMinY) newy1 = endY;
                }
                if (newy1 > newy2) {
                    int tmp = newy1;
                    newy1 = newy2;
                    newy2 = tmp;
                }
            } else {
                newy1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockY();
                newy2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockY();
            }

            this.dataStore.resizeClaimWithChecks(player, playerData, newx1, newx2, newy1, newy2, newz1, newz2);
            return true;
        }

        Claim clickedClaim = this.dataStore.getClaimAt(clickedBlock.getLocation(), true, playerData.lastClaim);
        while (clickedClaim != null && clickedClaim.parent != null) {
            clickedClaim = clickedClaim.parent;
        }

        if (clickedClaim == null) {
            return false;
        }

        Supplier<String> noEditReason = clickedClaim.checkPermission(player, ClaimPermission.Edit, null);
        if (noEditReason != null) {
            return false;
        }

        if (!isCornerMatch(clickedClaim, clickedBlock)) {
            return false;
        }

        if (playerData.getClaimEditorSession().openPath() != null) {
            if (canStartResizeFromOpenPath(playerData.getClaimEditorSession(), clickedClaim, clickedBlock)) {
                playerData.setClaimEditorSession(
                    loadClaimIntoShapedSession(playerData.getClaimEditorSession().withOpenPath(null), clickedClaim)
                );
            } else {
                return false;
            }
        }

        startClaimResizeSelection(player, playerData, clickedClaim, clickedBlock);
        return true;
    }

    private boolean canStartResizeFromOpenPath(
        @NotNull ClaimEditorSession session,
        @NotNull Claim claim,
        @NotNull Block clickedBlock
    ) {
        if (
            session.activeTarget() == null ||
            session.activeTarget().claimId() == null ||
            !session.activeTarget().claimId().equals(claim.getID()) ||
            session.openPath() == null ||
            session.openPath().points().size() != 1
        ) {
            return false;
        }

        OrthogonalPoint2i onlyPoint = session.openPath().points().get(0);
        return onlyPoint.x() == clickedBlock.getX() && onlyPoint.z() == clickedBlock.getZ();
    }

    private void startClaimResizeSelection(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Claim claim,
        @NotNull Block clickedBlock
    ) {
        Claim selection = claim;
        // Don't promote single-block claims (1x1x1) where every block is a corner.
        while (
            selection.parent != null &&
            (selection.getLesserBoundaryCorner().getBlockX() != selection.getGreaterBoundaryCorner().getBlockX() ||
                selection.getLesserBoundaryCorner().getBlockZ() != selection.getGreaterBoundaryCorner().getBlockZ())
        ) {
            Claim parent = selection.parent;
            boolean parentContains = parent.contains(clickedBlock.getLocation(), true, false);
            if (parentContains && isCornerMatch(parent, clickedBlock)) {
                selection = parent;
            } else {
                break;
            }
        }

        playerData.claimResizing = selection;
        playerData.claimSelectionActive = instance.config_claims_useClaimSelectSessions;
        playerData.lastShovelLocation = clickedBlock.getLocation();
        GriefPrevention.sendMessage(player, TextMode.Instr, claimResizeStartMessage(selection));

        VisualizationType visualizationType;
        if (selection.parent == null) {
            visualizationType = selection.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM;
        } else {
            visualizationType = selection.is3D() ? VisualizationType.SUBDIVISION_3D : VisualizationType.SUBDIVISION;
        }
        BoundaryVisualization.visualizeClaim(player, selection, visualizationType, clickedBlock);
    }

    private Messages claimResizeStartMessage(@NotNull Claim selection) {
        if (!instance.config_claims_useClaimSelectSessions || !instance.config_claims_useClaimSelectedMessages) {
            return Messages.ResizeStart;
        }

        if (selection.parent != null) {
            return Messages.SubdivisionSelected;
        }

        boolean hasNoChildren = selection.children == null || selection.children.isEmpty();
        return hasNoChildren ? Messages.ClaimSelected : Messages.ClaimSelectedTopLevel;
    }

    /**
     * Shift+right-click on a single edge of your top-level 2D shaped claim (basic mode): select that segment in memory
     * for shaped expansion later. No claim update until you expand from shaped mode. Cleared when visualization
     * reverts or session is reset. Gated by {@code AllowShapedClaims}.
     *
     * @return true if this path handled the click (including failure feedback)
     */
    private boolean trySelectShapedBoundarySegmentBasicMode(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Claim deepestClaim,
        @NotNull Block clickedBlock
    ) {
        Claim claim = deepestClaim;
        while (claim != null && claim.parent != null) {
            claim = claim.parent;
        }
        if (claim == null || !claim.isShaped() || claim.is3D()) {
            return false;
        }
        Supplier<String> noEdit = claim.checkPermission(player, ClaimPermission.Edit, null);
        if (noEdit != null) {
            return false;
        }

        OrthogonalPoint2i point = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
        if (claim.getCornerIndexAt(point.x(), point.z()) >= 0) {
            return false;
        }

        OrthogonalPolygon polygon = claim.getBoundaryPolygon();
        List<Integer> matches = polygon.edgeIndexesContainingInteriorPoint(point);
        if (matches.size() != 1) {
            return false;
        }

        ClaimEditorSession session = ClaimEditorSession.idle(player.getUniqueId()).withMode(
            ClaimEditorMode.SHAPED,
            ClaimEditSource.TOOL
        );
        session = loadClaimIntoShapedSession(session, claim);

        ClaimEditResult result = claimEditor.apply(
            session,
            new ClaimEditIntent(
                ClaimEditIntentType.SELECT_SEGMENT,
                ClaimEditSource.TOOL,
                null,
                claim.getID(),
                point,
                null,
                true,
                Collections.emptyList()
            )
        );

        if (!result.success()) {
            applyClaimEditResult(player, playerData, result);
            return true;
        }

        applyClaimEditResult(player, playerData, result);
        playerData.setEphemeralBasicShapedSegmentPreview(true);
        playerData.lastClaim = claim;
        BoundaryVisualization.visualizeClaim(
            player,
            claim,
            claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
            clickedBlock
        );
        return true;
    }

    private void handleMergeModeInteraction(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Block clickedBlock
    ) {
        Claim clickedClaim = this.dataStore.getClaimAt(clickedBlock.getLocation(), true, playerData.lastClaim);
        while (clickedClaim != null && clickedClaim.parent != null) {
            clickedClaim = clickedClaim.parent;
        }

        // If no first claim selected, select this one
        if (playerData.claimMerging == null) {
            if (clickedClaim == null) {
                GriefPrevention.sendMessage(player, TextMode.Err, "Click on a claim boundary to begin merging.");
                return;
            }

            // Check edit permission
            Supplier<String> noEditReason = clickedClaim.checkPermission(player, ClaimPermission.Edit, null);
            if (noEditReason != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
                return;
            }

            // A shaped claim needs a selected nib. Rectangles deliberately carry no edge
            // metadata because a rectangle pair always merges to its global bounding box.
            boolean requiresNib = requiresMergeNib(clickedClaim);
            Integer edgeIndex = requiresNib
                    ? resolveClickedEdge(clickedClaim, clickedBlock)
                    : null;
            if (edgeIndex == null && requiresNib) {
                GriefPrevention.sendMessage(
                    player,
                    TextMode.Err,
                    "Click on a claim boundary edge to select it for merging."
                );
                return;
            }
            playerData.mergeEdgeIndex = edgeIndex;
            playerData.mergeFirstDepthPoint = edgeIndex == null
                    ? null
                    : resolveMergeDepthPoint(player, clickedClaim, clickedBlock);
            playerData.mergeSecondEdgeIndex = null;
            playerData.mergeSecondDepthPoint = null;

            playerData.claimMerging = clickedClaim;
            BoundaryVisualization.visualizeClaim(
                player,
                clickedClaim,
                clickedClaim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM
            );
            Set<Claim> nearbyClaims = this.dataStore.getNearbyClaims(player.getLocation());
            if (!nearbyClaims.isEmpty()) {
                BoundaryVisualization.mergeNearbyClaims(player, nearbyClaims);
            }
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                "First claim selected. Right click the second claim to finish the merge."
            );
            return;
        }

        // First claim already selected - merge with second claim
        if (clickedClaim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Click on a claim to merge with the first claim.");
            return;
        }

        if (clickedClaim.getID().equals(playerData.claimMerging.getID())) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Cannot merge a claim with itself.");
            return;
        }

        // Check edit permission on second claim
        Supplier<String> noEditReason = clickedClaim.checkPermission(player, ClaimPermission.Edit, null);
        if (noEditReason != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
            return;
        }

        // Record the second shaped claim's nib so the corridor is sized from both ends.
        boolean requiresNib = requiresMergeNib(clickedClaim);
        Integer secondEdgeIndex = requiresNib
                ? resolveClickedEdge(clickedClaim, clickedBlock)
                : null;
        if (secondEdgeIndex == null && requiresNib) {
            GriefPrevention.sendMessage(
                player,
                TextMode.Err,
                "Click on a claim boundary edge to select it for merging."
            );
            return;
        }
        playerData.mergeSecondEdgeIndex = secondEdgeIndex;
        playerData.mergeSecondDepthPoint = secondEdgeIndex == null
                ? null
                : resolveMergeDepthPoint(player, clickedClaim, clickedBlock);

        // Perform the merge
        this.dataStore.mergeClaims(
            player,
            playerData,
            playerData.claimMerging,
            clickedClaim,
            playerData.mergeEdgeIndex
        );
    }

    /**
     * The boundary edge a click landed on, or null when the click was on a corner or inside
     * the claim rather than along one of its edges.
     */
    private @Nullable Integer resolveClickedEdge(@NotNull Claim claim, @NotNull Block clickedBlock) {
        OrthogonalPoint2i point = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
        List<Integer> edgeMatches = claim.getBoundaryPolygon().edgeIndexesContainingInteriorPoint(point);
        return edgeMatches.size() == 1 ? edgeMatches.get(0) : null;
    }

    private boolean requiresMergeNib(@NotNull Claim claim) {
        return claim.getBoundaryPolygon().corners().size() != 4;
    }

    /**
     * How deep into the claim the merge should reach. The player's own position is the best
     * signal, but when they are clicking a claim from outside it the clicked block is all we
     * have to go on.
     */
    private @NotNull OrthogonalPoint2i resolveMergeDepthPoint(
            @NotNull Player player,
            @NotNull Claim claim,
            @NotNull Block clickedBlock) {
        OrthogonalPoint2i playerPoint = new OrthogonalPoint2i(
                player.getLocation().getBlockX(), player.getLocation().getBlockZ());
        if (claim.getBoundaryPolygon().containsCell(playerPoint.x(), playerPoint.z())) {
            return playerPoint;
        }

        return new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
    }

    private void handleShapedModeInteraction(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Block clickedBlock
    ) {
        ClaimEditorSession session = playerData.getClaimEditorSession();
        if (session.mode() != com.griefprevention.claims.editor.ClaimEditorMode.SHAPED) {
            session = session.withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL);
        }

        if (
            session.activeTarget() != null &&
            session.activeTarget().type() == ClaimEditTargetType.EXISTING_PARENT_CLAIM &&
            session.openPath() != null &&
            session.activeTarget().claimId() != null
        ) {
            Claim targetClaim = this.dataStore.getClaim(session.activeTarget().claimId());
            if (targetClaim != null) {
                Claim clickedClaim = this.dataStore.getClaimAt(clickedBlock.getLocation(), true, playerData.lastClaim);
                while (clickedClaim != null && clickedClaim.parent != null) {
                    clickedClaim = clickedClaim.parent;
                }

                // Allow clicking on other self-owned claims for cross-claim merges
                if (clickedClaim != null && !clickedClaim.getID().equals(targetClaim.getID())) {
                    // Check if player owns both claims
                    Supplier<String> noEditReason = clickedClaim.checkPermission(player, ClaimPermission.Edit, null);
                    if (noEditReason != null) {
                        GriefPrevention.sendMessage(
                            player,
                            TextMode.Err,
                            "Finish this reshape path before editing a different claim."
                        );
                        return;
                    }
                    // Add a boundary marker on the clicked claim so the reshape path
                    // has a valid reconnection target. Then restore the session back
                    // to the original claim so the player can continue building.
                    OrthogonalPoint2i point = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
                    if (isBoundaryInteriorPoint(clickedClaim, point)) {
                        // Save original session state (claim 1 + open path)
                        ClaimEditorSession originalSession = session;
                        List<OrthogonalPoint2i> originalDraftPoints = originalSession.preview().draftPoints();

                        // Add node on claim 2
                        ClaimEditorSession clickedSession = loadClaimIntoShapedSession(session, clickedClaim);
                        ClaimEditResult result = claimEditor.apply(
                            clickedSession,
                            new ClaimEditIntent(
                                ClaimEditIntentType.ADD_NODE,
                                ClaimEditSource.TOOL,
                                null,
                                clickedClaim.getID(),
                                point,
                                null,
                                true,
                                Collections.emptyList()
                            )
                        );
                        if (result.success()) {
                            // Record the merge target
                            playerData.addCrossClaimBoundaryMarker(clickedClaim.getID());
                            GriefPrevention.sendMessage(
                                player,
                                TextMode.Success,
                                "Merge target marked. Continue shaping to connect claims."
                            );
                            // Restore the original session on claim 1 with the open path intact,
                            // but add the cross-claim point as a draft point so the path continues
                            // toward the merge target.
                            List<OrthogonalPoint2i> newDraftPoints = new ArrayList<>(originalDraftPoints);
                            newDraftPoints.add(point);
                            ClaimEditPreview restoredPreview = new ClaimEditPreview(
                                originalSession.preview().polygon(),
                                originalSession.preview().highlightedSegment(),
                                newDraftPoints,
                                originalSession.preview().snappedPoint(),
                                originalSession.preview().conflictPoints(),
                                Collections.emptyList(),
                                Collections.singletonList("Merge target marked. Continue shaping to connect claims.")
                            );
                            ShapedPathDraft restoredDraft = new ShapedPathDraft(
                                targetClaim.getID(),
                                newDraftPoints,
                                null,
                                false
                            );
                            ClaimEditorSession restoredSession = originalSession
                                .withOpenPath(restoredDraft)
                                .withPreview(restoredPreview);
                            playerData.setClaimEditorSession(restoredSession);
                            visualizeShapedEditState(player, restoredSession, clickedBlock.getY());
                            return;
                        }
                    }
                }

                BoundaryNodeEnsureResult targetBoundaryResult = ensureBoundaryNodeForShapedPath(
                    player,
                    playerData,
                    targetClaim,
                    clickedBlock,
                    loadClaimIntoShapedSession(session, targetClaim)
                );
                if (targetBoundaryResult.claim() == null) {
                    return;
                }
                targetClaim = targetBoundaryResult.claim();
                if (targetBoundaryResult.markerEdited()) {
                    return;
                }

                // Check if we're clicking back on a claim that has cross-claim boundary markers
                // This indicates a merge operation in progress
                if (playerData.hasCrossClaimBoundaryMarker(targetClaim.getID())) {
                    // This is a merge operation - show appropriate message
                    GriefPrevention.sendMessage(
                        player,
                        TextMode.Instr,
                        "Merge in progress. Continue shaping to connect claims."
                    );
                }

                ClaimEditorSession targetSession = loadClaimIntoShapedSession(
                    playerData.getClaimEditorSession(),
                    targetClaim
                );
                OrthogonalPoint2i targetPoint = snapOutsideShapedCornerToMinimumDistance(
                    targetClaim,
                    targetSession,
                    new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ())
                );
                ClaimEditResult result = claimEditor.apply(
                    targetSession,
                    new ClaimEditIntent(
                        ClaimEditIntentType.ADD_CORNER,
                        ClaimEditSource.TOOL,
                        null,
                        targetClaim.getID(),
                        targetPoint,
                        null,
                        true,
                        Collections.emptyList()
                    )
                );
                applyClaimEditResult(player, playerData, result);
                if (result.success()) {
                    if (
                        result.preview().polygon() != null &&
                        result.session().openPath() != null &&
                        result.session().openPath().closureReady()
                    ) {
                        finalizeExistingClaimReshape(player, playerData, targetClaim, result, clickedBlock);
                    } else {
                        visualizeShapedEditState(player, result.session(), clickedBlock.getY());
                    }
                }
                return;
            }
        }

        Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), true, playerData.lastClaim);
        while (claim != null && claim.parent != null) {
            claim = claim.parent;
        }

        if (claim == null) {
            Claim targetClaim = resolveExistingShapedTarget(session);
            if (targetClaim != null && (session.openPath() != null || session.activeSegment() != null)) {
                session = loadClaimIntoShapedSession(playerData.getClaimEditorSession(), targetClaim);
                OrthogonalPoint2i clickedPoint = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
                session = seedOpenPathFromActiveSegmentIfNeeded(session, targetClaim, clickedPoint);
                playerData.setClaimEditorSession(session);

                String reconnectError = validateExistingMergeReconnect(targetClaim, session, clickedPoint);
                if (reconnectError != null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, reconnectError);
                    visualizeShapedEditState(player, session, clickedBlock.getY());
                    return;
                }

                OrthogonalPoint2i adjustedPoint = snapOutsideShapedCornerToMinimumDistance(
                    targetClaim,
                    session,
                    clickedPoint
                );
                ClaimEditResult result = claimEditor.apply(
                    session,
                    new ClaimEditIntent(
                        ClaimEditIntentType.ADD_CORNER,
                        ClaimEditSource.TOOL,
                        null,
                        targetClaim.getID(),
                        adjustedPoint,
                        null,
                        true,
                        Collections.emptyList()
                    )
                );
                applyClaimEditResult(player, playerData, result);
                if (result.success()) {
                    if (
                        result.preview().polygon() != null &&
                        result.session().openPath() != null &&
                        result.session().openPath().closureReady()
                    ) {
                        finalizeExistingClaimReshape(player, playerData, targetClaim, result, clickedBlock);
                    } else {
                        visualizeShapedEditState(player, result.session(), clickedBlock.getY());
                    }
                }
                return;
            }

            // Snap to start point only when the click would create 4+ collinear corners
            // on the same boundary line (overshoot scenario).
            // Determine which axis the clicked point shares with the start, then count
            // how many existing draft points are on that SAME line. If 3+ already, adding
            // one more would be illegal collinear geometry — snap to close instead.
            // With fewer, the player may still be building a valid turning shape.
            OrthogonalPoint2i clickedPoint = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
            List<OrthogonalPoint2i> draftPoints = session.preview().draftPoints();
            if (!draftPoints.isEmpty()) {
                OrthogonalPoint2i startPoint = draftPoints.get(0);
                boolean sameX = clickedPoint.x() == startPoint.x();
                boolean sameZ = clickedPoint.z() == startPoint.z();
                if (sameX || sameZ) {
                    // Count existing draft points on the SAME line (matching axis)
                    long collinearCount = draftPoints
                        .stream()
                        .filter(p -> sameX ? p.x() == startPoint.x() : p.z() == startPoint.z())
                        .count();
                    // If 3+ already on that line (plus the new point = 4+), snap to close.
                    // 3 collinear points is the threshold where adding another is always redundant.
                    if (collinearCount >= 3) {
                        clickedPoint = startPoint;
                    }
                }
            }

            ClaimEditResult result = claimEditor.apply(
                session.withTarget(new ClaimEditTarget(ClaimEditTargetType.NEW_PARENT_CLAIM, null)),
                new ClaimEditIntent(
                    ClaimEditIntentType.ADD_CORNER,
                    ClaimEditSource.TOOL,
                    null,
                    null,
                    clickedPoint,
                    null,
                    true,
                    Collections.emptyList()
                )
            );
            applyClaimEditResult(player, playerData, result);
            if (result.success()) {
                if (result.preview().polygon() != null) {
                    finalizeShapedClaimCreation(player, playerData, result, clickedBlock);
                } else {
                    visualizeShapedEditState(player, result.session(), clickedBlock.getY());
                }
            }
            return;
        }

        playerData.lastClaim = claim;

        if (claim.is3D()) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Shaped mode only works on top-level 2D claims.");
            return;
        }

        Supplier<String> noEditReason = claim.checkPermission(player, ClaimPermission.Edit, null);
        if (noEditReason != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
            return;
        }

        // Check if player clicked inside their claim but NOT on the boundary
        OrthogonalPoint2i clickedPoint = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
        boolean isInteriorClick = !isBoundaryPoint(claim.getBoundaryPolygon(), clickedPoint)
                && claim.contains(clickedBlock.getLocation(), true, false);

        // Allow interior clicks when an active cut path exists (openPath starts on the boundary)
        if (session.openPath() != null && !session.openPath().points().isEmpty()) {
            OrthogonalPoint2i firstPoint = session.openPath().points().get(0);
            final Claim cutClaim = claim;
            if (isBoundaryPoint(cutClaim.getBoundaryPolygon(), firstPoint)) {
                boolean hasCutInteriorPoints = session.openPath().points().stream()
                        .skip(1)
                        .anyMatch(p -> !isBoundaryPoint(cutClaim.getBoundaryPolygon(), p)
                                && cutClaim.getBoundaryPolygon().containsCell(p.x(), p.z()));
                boolean clickedOnBoundary = isBoundaryPoint(cutClaim.getBoundaryPolygon(), clickedPoint)
                        && cutClaim.contains(clickedBlock.getLocation(), true, false);
                // Close the cut path: click at start, or click any boundary point with interior points
                if (hasCutInteriorPoints
                        && (clickedPoint.equals(firstPoint) || clickedOnBoundary)) {
                    if (!clickedPoint.equals(firstPoint)) {
                        ShapedPathDraft draft = session.openPath();
                        List<OrthogonalPoint2i> points = new ArrayList<>(draft.points());
                        points.add(clickedPoint);
                        ShapedPathDraft closedDraft = new ShapedPathDraft(draft.claimId(), points, null, true);
                        session = session.withOpenPath(closedDraft);
                    }
                    finalizeCutPath(player, playerData, cutClaim, session, clickedBlock);
                    return;
                }
                // Continue the cut path — add this interior point as a draft corner
                if (isInteriorClick && !clickedPoint.equals(firstPoint)) {
                    // Show cut-started message on first interior click
                    if (session.openPath().points().size() == 1) {
                        GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ShapedClaimsCutStarted);
                    }
                    OrthogonalPoint2i lastPathPoint = session.openPath().points().get(session.openPath().points().size() - 1);
                    int dX = Math.abs(clickedPoint.x() - lastPathPoint.x());
                    int dZ = Math.abs(clickedPoint.z() - lastPathPoint.z());
                    if (dX != dZ) {
                        clickedPoint = (dX < dZ)
                            ? new OrthogonalPoint2i(lastPathPoint.x(), clickedPoint.z())
                            : new OrthogonalPoint2i(clickedPoint.x(), lastPathPoint.z());
                    }
                    OrthogonalPolygon basePolygon = cutClaim.getBoundaryPolygon();
                    ShapedPathDraft draft = session.openPath();
                    List<OrthogonalPoint2i> points = new ArrayList<>(draft.points());
                    points.add(clickedPoint);
                    ShapedPathDraft updatedDraft = new ShapedPathDraft(draft.claimId(), points, null, false);
                    ClaimEditPreview preview = new ClaimEditPreview(
                            basePolygon,
                            null,
                            updatedDraft.points(),
                            null,
                            Collections.emptyList(),
                            Collections.emptyList(),
                            Collections.singletonList("Cut path point added.")
                    );
                    ClaimEditorSession updatedSession = session.withOpenPath(updatedDraft).withPreview(preview);
                    playerData.setClaimEditorSession(updatedSession);
                    visualizeShapedEditState(player, updatedSession, clickedBlock.getY());
                    return;
                }
            }
        }

        if (isInteriorClick) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedClaimInteriorClick);
            visualizeConflict(player, playerData, claim, clickedBlock, claim.is3D());
            return;
        }

        if (
            isCornerMatch(claim, clickedBlock) &&
            session.openPath() == null &&
            !player.isSneaking() &&
            playerData.shovelMode != ShovelMode.Shaped
        ) {
            startClaimResizeSelection(player, playerData, claim, clickedBlock);
            return;
        }

        session = loadClaimIntoShapedSession(session, claim);
        BoundaryNodeEnsureResult boundaryNodeResult = ensureBoundaryNodeForShapedPath(
            player,
            playerData,
            claim,
            clickedBlock,
            session
        );
        if (boundaryNodeResult.claim() == null) {
            return;
        }
        claim = boundaryNodeResult.claim();
        if (boundaryNodeResult.markerEdited() && !player.isSneaking()) {
            return;
        }

        // Cut path detection: when there's an active openPath starting from the boundary,
        // allow interior clicks to build a cut path and detect closure back to the start.
        if (session.openPath() != null && !session.openPath().points().isEmpty()) {
            OrthogonalPoint2i clickedCutPoint = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
            OrthogonalPoint2i cutFirstPoint = session.openPath().points().get(0);
            final Claim cutClaim = claim;
            if (isBoundaryPoint(cutClaim.getBoundaryPolygon(), cutFirstPoint)) {
                boolean isInteriorCutClick = !isBoundaryPoint(cutClaim.getBoundaryPolygon(), clickedCutPoint)
                        && cutClaim.contains(clickedBlock.getLocation(), true, false);
                // Check if closing the cut path back to the start
                if (clickedCutPoint.equals(cutFirstPoint) && session.openPath().points().size() >= 3) {
                    boolean hasInteriorPoints = session.openPath().points().stream()
                            .skip(1)
                            .anyMatch(p -> !isBoundaryPoint(cutClaim.getBoundaryPolygon(), p));
                    if (hasInteriorPoints) {
                        finalizeCutPath(player, playerData, cutClaim, session, clickedBlock);
                        return;
                    }
                }
                // Continue the cut path — add interior point as a draft corner
                if (isInteriorCutClick && !clickedCutPoint.equals(cutFirstPoint)) {
                    if (session.openPath().points().size() == 1) {
                        GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ShapedClaimsCutStarted);
                    }
                    OrthogonalPoint2i lastPathPoint = session.openPath().points().get(session.openPath().points().size() - 1);
                    int dX = Math.abs(clickedCutPoint.x() - lastPathPoint.x());
                    int dZ = Math.abs(clickedCutPoint.z() - lastPathPoint.z());
                    if (dX != dZ) {
                        clickedCutPoint = (dX < dZ)
                            ? new OrthogonalPoint2i(lastPathPoint.x(), clickedCutPoint.z())
                            : new OrthogonalPoint2i(clickedCutPoint.x(), lastPathPoint.z());
                    }
                    OrthogonalPolygon basePolygon = cutClaim.getBoundaryPolygon();
                    ShapedPathDraft draft = session.openPath();
                    List<OrthogonalPoint2i> points = new ArrayList<>(draft.points());
                    points.add(clickedCutPoint);
                    ShapedPathDraft updatedDraft = new ShapedPathDraft(draft.claimId(), points, null, false);
                    ClaimEditPreview preview = new ClaimEditPreview(
                            basePolygon,
                            null,
                            updatedDraft.points(),
                            null,
                            Collections.emptyList(),
                            Collections.emptyList(),
                            Collections.singletonList("Cut path point added.")
                    );
                    ClaimEditorSession updatedSession = session.withOpenPath(updatedDraft).withPreview(preview);
                    playerData.setClaimEditorSession(updatedSession);
                    visualizeShapedEditState(player, updatedSession, clickedBlock.getY());
                    return;
                }
            }
        }

        session = loadClaimIntoShapedSession(playerData.getClaimEditorSession(), claim);
        OrthogonalPoint2i adjustedPoint = snapOutsideShapedCornerToMinimumDistance(
            claim,
            session,
            new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ())
        );
        ClaimEditResult result = claimEditor.apply(
            session,
            new ClaimEditIntent(
                ClaimEditIntentType.ADD_CORNER,
                ClaimEditSource.TOOL,
                null,
                claim.getID(),
                adjustedPoint,
                null,
                true,
                Collections.emptyList()
            )
        );
        applyClaimEditResult(player, playerData, result);
        if (result.success()) {
            if (
                result.preview().polygon() != null &&
                result.session().openPath() != null &&
                result.session().openPath().closureReady()
            ) {
                finalizeExistingClaimReshape(player, playerData, claim, result, clickedBlock);
            } else {
                visualizeShapedEditState(player, result.session(), clickedBlock.getY());
            }
        }
    }

    private @NotNull BoundaryNodeEnsureResult ensureBoundaryNodeForShapedPath(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Claim claim,
        @NotNull Block clickedBlock,
        @NotNull ClaimEditorSession session
    ) {
        if (session.openPath() != null) {
            return new BoundaryNodeEnsureResult(claim, false);
        }

        OrthogonalPoint2i point = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
        if (!isBoundaryInteriorPoint(claim, point)) {
            return new BoundaryNodeEnsureResult(claim, false);
        }

        // If sneaking, try to remove the nearest boundary node instead of adding
        if (player.isSneaking()) {
            OrthogonalPolygon polygon = claim.getBoundaryPolygon();
            if (polygon != null) {
                // Find the nearest node to the clicked point
                OrthogonalPoint2i nearestNode = null;
                int nearestDistance = Integer.MAX_VALUE;
                for (OrthogonalPoint2i corner : polygon.corners()) {
                    int distance = Math.abs(corner.x() - point.x()) + Math.abs(corner.z() - point.z());
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestNode = corner;
                    }
                }
                // If the clicked point is close to a node (within 2 blocks), remove it
                if (nearestNode != null && nearestDistance <= 2) {
                    // Load claim into session and try to remove the node using ADD_NODE
                    session = loadClaimIntoShapedSession(session, claim);
                    ClaimEditResult removeResult = claimEditor.apply(
                        session,
                        new ClaimEditIntent(
                            ClaimEditIntentType.ADD_NODE,
                            ClaimEditSource.TOOL,
                            null,
                            claim.getID(),
                            nearestNode,
                            null,
                            true,
                            Collections.emptyList()
                        )
                    );
                    applyClaimEditResult(player, playerData, removeResult);
                    if (removeResult.success()) {
                        GriefPrevention.sendMessage(player, TextMode.Success, "Boundary segment marker removed.");
                        visualizeShapedEditState(player, removeResult.session(), clickedBlock.getY());
                        return new BoundaryNodeEnsureResult(claim, true);
                    } else {
                        GriefPrevention.sendMessage(player, TextMode.Err, "Failed to remove boundary segment marker.");
                    }
                }
            }
            GriefPrevention.sendMessage(player, TextMode.Err, "No boundary segment marker nearby to remove.");
            return new BoundaryNodeEnsureResult(claim, false);
        }

        if (!meetsMinimumNodeSpacing(claim, point)) {
            GriefPrevention.sendMessage(
                player,
                TextMode.Err,
                Messages.NewClaimTooNarrow,
                String.valueOf(Math.max(1, GriefPrevention.instance.config_claims_shapedMinWidth))
            );
            return new BoundaryNodeEnsureResult(null, false);
        }

        ClaimEditResult result = claimEditor.apply(
            session,
            new ClaimEditIntent(
                ClaimEditIntentType.ADD_NODE,
                ClaimEditSource.TOOL,
                null,
                claim.getID(),
                point,
                null,
                true,
                Collections.emptyList()
            )
        );
        applyClaimEditResult(player, playerData, result);
        if (!result.success()) {
            return new BoundaryNodeEnsureResult(null, false);
        }

        persistShapedBoundaryMarkers(player, playerData, claim, result, clickedBlock);
        Claim updatedClaim = this.dataStore.getClaim(claim.getID());
        return new BoundaryNodeEnsureResult(updatedClaim != null ? updatedClaim : claim, true);
    }

    private static final class BoundaryNodeEnsureResult {

        private final @Nullable Claim claim;
        private final boolean markerEdited;

        private BoundaryNodeEnsureResult(@Nullable Claim claim, boolean markerEdited) {
            this.claim = claim;
            this.markerEdited = markerEdited;
        }

        @Nullable
        Claim claim() {
            return claim;
        }

        boolean markerEdited() {
            return markerEdited;
        }
    }

    private boolean meetsMinimumNodeSpacing(@NotNull Claim claim, @NotNull OrthogonalPoint2i point) {
        int minimumEdgeLength = Math.max(0, GriefPrevention.instance.config_claims_shapedMinWidth - 1);
        if (minimumEdgeLength <= 0) {
            return true;
        }

        OrthogonalPolygon polygon = claim.getBoundaryPolygon();
        List<Integer> edgeIndexes = polygon.edgeIndexesContainingInteriorPoint(point);
        if (edgeIndexes.size() != 1) {
            return false;
        }

        OrthogonalEdge2i edge = polygon.edges().get(edgeIndexes.get(0));
        int distanceToStart = Math.abs(point.x() - edge.start().x()) + Math.abs(point.z() - edge.start().z());
        int distanceToEnd = Math.abs(point.x() - edge.end().x()) + Math.abs(point.z() - edge.end().z());
        return distanceToStart >= minimumEdgeLength && distanceToEnd >= minimumEdgeLength;
    }

    private @NotNull OrthogonalPoint2i snapOutsideShapedCornerToMinimumDistance(
        @NotNull Claim claim,
        @NotNull ClaimEditorSession session,
        @NotNull OrthogonalPoint2i clickedPoint
    ) {
        int minimumEdgeLength = Math.max(0, GriefPrevention.instance.config_claims_shapedMinWidth - 1);

        if (session.openPath() == null || session.openPath().points().size() != 1) {
            return clickedPoint;
        }

        OrthogonalPoint2i anchor = session.openPath().points().get(0);
        OrthogonalPolygon boundaryPolygon = claim.getBoundaryPolygon();
        if (!isBoundaryPoint(boundaryPolygon, anchor) || isBoundaryPoint(boundaryPolygon, clickedPoint)) {
            return clickedPoint;
        }

        World world = claim.getLesserBoundaryCorner().getWorld();
        if (world == null) {
            return clickedPoint;
        }

        Location probe = new Location(
            world,
            clickedPoint.x(),
            claim.getLesserBoundaryCorner().getBlockY(),
            clickedPoint.z()
        );
        if (claim.contains(probe, true, false)) {
            return clickedPoint;
        }

        int deltaX = clickedPoint.x() - anchor.x();
        int deltaZ = clickedPoint.z() - anchor.z();
        if (deltaX == 0 && deltaZ == 0) {
            return clickedPoint;
        }

        if (Math.abs(deltaX) < Math.abs(deltaZ)) {
            int direction = Integer.signum(deltaZ);
            if (direction == 0) {
                return clickedPoint;
            }

            // Keep the first outside step pointed away from the claim boundary.
            if (
                claim.contains(
                    new Location(
                        world,
                        anchor.x(),
                        claim.getLesserBoundaryCorner().getBlockY(),
                        anchor.z() + direction
                    ),
                    true,
                    false
                )
            ) {
                direction *= -1;
            }
            int snappedDistance = Math.max(Math.abs(deltaZ), minimumEdgeLength);
            return new OrthogonalPoint2i(anchor.x(), anchor.z() + direction * snappedDistance);
        }

        int direction = Integer.signum(deltaX);
        if (direction == 0) {
            return clickedPoint;
        }

        // Keep the first outside step pointed away from the claim boundary.
        if (
            claim.contains(
                new Location(world, anchor.x() + direction, claim.getLesserBoundaryCorner().getBlockY(), anchor.z()),
                true,
                false
            )
        ) {
            direction *= -1;
        }
        int snappedDistance = Math.max(Math.abs(deltaX), minimumEdgeLength);
        return new OrthogonalPoint2i(anchor.x() + direction * snappedDistance, anchor.z());
    }

    private @Nullable Claim resolveExistingShapedTarget(@NotNull ClaimEditorSession session) {
        if (
            session.activeTarget() == null ||
            session.activeTarget().type() != ClaimEditTargetType.EXISTING_PARENT_CLAIM ||
            session.activeTarget().claimId() == null
        ) {
            return null;
        }

        return this.dataStore.getClaim(session.activeTarget().claimId());
    }

    private @NotNull ClaimEditorSession seedOpenPathFromActiveSegmentIfNeeded(
        @NotNull ClaimEditorSession session,
        @NotNull Claim claim,
        @NotNull OrthogonalPoint2i clickedPoint
    ) {
        if (session.openPath() != null || session.activeSegment() == null || session.preview().polygon() == null) {
            return session;
        }

        OrthogonalPolygon polygon = session.preview().polygon();
        int edgeIndex = session.activeSegment().edgeIndex();
        if (edgeIndex < 0 || edgeIndex >= polygon.edges().size()) {
            return session;
        }

        OrthogonalEdge2i edge = polygon.edges().get(edgeIndex);
        OrthogonalPoint2i start = edge.start();
        OrthogonalPoint2i end = edge.end();
        OrthogonalPoint2i anchor =
            manhattanDistance(clickedPoint, start) <= manhattanDistance(clickedPoint, end) ? start : end;

        com.griefprevention.claims.editor.ShapedPathDraft draft = new com.griefprevention.claims.editor.ShapedPathDraft(
            claim.getID(),
            Collections.singletonList(anchor),
            null,
            false
        );
        ClaimEditPreview preview = new ClaimEditPreview(
            polygon,
            session.activeSegment(),
            draft.points(),
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.singletonList("Segment point added.")
        );
        return session.withOpenPath(draft).withPreview(preview);
    }

    private int manhattanDistance(@NotNull OrthogonalPoint2i first, @NotNull OrthogonalPoint2i second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
    }

    private @Nullable String validateExistingMergeReconnect(
        @NotNull Claim claim,
        @NotNull ClaimEditorSession session,
        @NotNull OrthogonalPoint2i clickedPoint
    ) {
        if (session.openPath() == null || !isBoundaryPoint(claim.getBoundaryPolygon(), clickedPoint)) {
            return null;
        }

        List<OrthogonalPoint2i> points = session.openPath().points();
        OrthogonalPolygon boundaryPolygon = claim.getBoundaryPolygon();
        boolean hasOutsideCorner = points
            .stream()
            .skip(1)
            .anyMatch(point -> !isBoundaryPoint(boundaryPolygon, point));
        if (!hasOutsideCorner) {
            return "Cannot merge yet: add at least one outside corner before reconnecting to the claim boundary.";
        }

        OrthogonalPoint2i lastPoint = points.get(points.size() - 1);
        if (!isOrthogonalStep(lastPoint, clickedPoint)) {
            return "Cannot merge yet: reconnect point must be orthogonally aligned with the current endpoint.";
        }

        return null;
    }

    private boolean isOrthogonalStep(@NotNull OrthogonalPoint2i first, @NotNull OrthogonalPoint2i second) {
        return (first.x() == second.x()) ^ (first.z() == second.z());
    }

    private boolean isBoundaryInteriorPoint(@NotNull Claim claim, @NotNull OrthogonalPoint2i point) {
        if (claim.getCornerIndexAt(point.x(), point.z()) >= 0) {
            return false;
        }

        return !claim.getBoundaryPolygon().edgeIndexesContainingInteriorPoint(point).isEmpty();
    }

    private boolean isBoundaryPoint(@NotNull OrthogonalPolygon polygon, @NotNull OrthogonalPoint2i point) {
        if (polygon.corners().contains(point)) {
            return true;
        }

        return polygon
            .edges()
            .stream()
            .anyMatch(edge -> edge.containsPoint(point));
    }

    private boolean isPointOnEdge(@NotNull OrthogonalPoint2i a, @NotNull OrthogonalPoint2i b, @NotNull OrthogonalPoint2i p) {
        if (a.x() == b.x()) {
            return p.x() == a.x()
                && Math.min(a.z(), b.z()) <= p.z()
                && p.z() <= Math.max(a.z(), b.z());
        } else {
            return p.z() == a.z()
                && Math.min(a.x(), b.x()) <= p.x()
                && p.x() <= Math.max(a.x(), b.x());
        }
    }

    private @NotNull ClaimEditorSession loadClaimIntoShapedSession(
        @NotNull ClaimEditorSession session,
        @NotNull Claim claim
    ) {
        if (
            session.activeTarget() != null &&
            session.activeTarget().claimId() != null &&
            session.activeTarget().claimId().equals(claim.getID()) &&
            session.preview().polygon() != null
        ) {
            return session;
        }

        OrthogonalPolygon polygon = claim.getBoundaryPolygon();
        return session
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

    private void finalizeShapedClaimCreation(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull ClaimEditResult result,
        @NotNull Block clickedBlock
    ) {
        OrthogonalPolygon polygon = result.preview().polygon();
        if (polygon == null) {
            return;
        }

        // Normalize the polygon to clean up collinear boundary markers
        // This removes intermediate points on straight edges for a cleaner boundary
        OrthogonalPolygonValidationResult normalizedResult = OrthogonalPolygon.validatePath(polygon.closedPath());
        if (normalizedResult.isValid() && normalizedResult.polygon() != null) {
            polygon = normalizedResult.polygon();
        }

        int claimDepth = clickedBlock.getY() - instance.config_claims_claimsExtendIntoGroundDistance;
        CreateClaimResult createResult = this.dataStore.createShapedClaim(
            player.getWorld(),
            polygon,
            claimDepth,
            player.getUniqueId(),
            player
        );

        if (!createResult.succeeded || createResult.claim == null) {
            if (createResult.denialMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, createResult.denialMessage.get());
            } else if (createResult.claim != null) {
                // Check if the overlapping claim is owned by the player - if so, allow segment marker creation
                Supplier<String> noEditReason = createResult.claim.checkPermission(player, ClaimPermission.Edit, null);
                if (noEditReason == null) {
                    // Player owns the overlapping claim - allow segment marker creation without error
                    // Don't merge immediately, just let the path continue
                    visualizeShapedEditState(player, result.session(), clickedBlock.getY());
                    return;
                }
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);
                visualizeConflict(player, playerData, createResult.claim, clickedBlock, createResult.claim.is3D());
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
            }
            visualizeShapedEditState(player, result.session(), clickedBlock.getY());
            return;
        }

        playerData.lastClaim = createResult.claim;
        ClaimEditorSession shapedSession = ClaimEditorSession.idle(playerData.playerID).withMode(
            com.griefprevention.claims.editor.ClaimEditorMode.SHAPED,
            ClaimEditSource.TOOL
        );
        playerData.setClaimEditorSession(loadClaimIntoShapedSession(shapedSession, createResult.claim));
        GriefPrevention.sendMessage(player, TextMode.Success, Messages.CreateClaimSuccess);
        BoundaryVisualization.visualizeClaim(player, createResult.claim, VisualizationType.CLAIM, clickedBlock);

        if (!player.hasPermission("griefprevention.adminclaims") && createResult.claim.getArea() >= 1000) {
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
            GriefPrevention.sendMessage(
                player,
                TextMode.Instr,
                Messages.SubdivisionVideo2,
                201L,
                DataStore.SUBDIVISION_VIDEO_URL
            );
        }

        AutoExtendClaimTask.scheduleAsync(createResult.claim);
    }

    private void finalizeExistingClaimReshape(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Claim claim,
        @NotNull ClaimEditResult result,
        @NotNull Block clickedBlock
    ) {
        OrthogonalPolygon polygon = result.preview().polygon();
        if (polygon == null) {
            return;
        }

        // Normalize the polygon to clean up collinear boundary markers
        // This removes intermediate points on straight edges for a cleaner boundary
        OrthogonalPolygonValidationResult normalizedResult = OrthogonalPolygon.validatePath(polygon.closedPath());
        if (normalizedResult.isValid() && normalizedResult.polygon() != null) {
            polygon = normalizedResult.polygon();
        }

        // Save the original polygon before updating — we need it to identify
        // the reshape corridor cells for proper cross-claim merge direction.
        OrthogonalPolygon originalPolygon = claim.getBoundaryPolygon();
        CreateClaimResult updateResult = this.dataStore.updateShapedClaim(player, playerData, claim, polygon);
        if (!updateResult.succeeded || updateResult.claim == null) {
            if (updateResult.denialMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, updateResult.denialMessage.get());
            } else if (updateResult.claim != null) {
                // Check if the overlapping claim is owned by the player - if so, merge the claims
                Supplier<String> noEditReason = updateResult.claim.checkPermission(player, ClaimPermission.Edit, null);
                if (noEditReason == null) {
                    // Player owns the overlapping claim — merge via mergeClaims
                    // with the preview polygon as firstPolygonOverride. This
                    // ensures the reshape corridor is included in the union
                    // (claim.getBoundaryPolygon() still returns the original
                    // polygon since updateShapedClaim failed).
                    this.dataStore.mergeClaims(player, playerData, claim, updateResult.claim, null, null, polygon);
                    playerData.clearCrossClaimBoundaryMarkers();
                    ClaimEditorSession shapedSession = ClaimEditorSession.idle(playerData.playerID).withMode(
                        com.griefprevention.claims.editor.ClaimEditorMode.SHAPED,
                        ClaimEditSource.TOOL
                    );
                    playerData.lastClaim = claim;
                    playerData.setClaimEditorSession(loadClaimIntoShapedSession(shapedSession, claim));
                    BoundaryVisualization.visualizeClaim(
                        player,
                        claim,
                        claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
                        clickedBlock
                    );
                    return;
                }
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);
                visualizeConflict(player, playerData, updateResult.claim, clickedBlock, updateResult.claim.is3D());
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
            }
            visualizeShapedEditState(player, result.session(), clickedBlock.getY());
            return;
        }

        // If the update succeeded but the player marked a cross-claim merge target,
        // merge the claims. The marker itself is the signal — the reshape path
        // may not geometrically overlap the adjacent claim (corridor-only connection),
        // but the player explicitly marked it for merging.
        // Note: updateShapedClaim already succeeded (claim now has the reshape polygon).
        // We need to merge with the marked claim using mergeClaims which handles
        // chunk map updates, shaped corners, and deletion properly.
        if (!playerData.getCrossClaimBoundaryMarkers().isEmpty()) {
            for (Long markedClaimId : playerData.getCrossClaimBoundaryMarkers()) {
                Claim markedClaim = this.dataStore.getClaim(markedClaimId);
                if (markedClaim == null || !markedClaim.inDataStore) {
                    continue;
                }
                // Verify same owner
                if (markedClaim.getOwnerID() == null || !markedClaim.getOwnerID().equals(player.getUniqueId())) {
                    continue;
                }
                // Find the reshape corridor's leading-edge cells (cells in the
                // preview polygon that were NOT in the original claim). These
                // guide the merge to connect from the reshape front to the
                // marked claim, preserving the player's intended path.
                Set<OrthogonalPoint2i> corridorCells = new HashSet<>();
                for (int x = polygon.minX(); x <= polygon.maxX(); x++) {
                    for (int z = polygon.minZ(); z <= polygon.maxZ(); z++) {
                        if (polygon.containsCell(x, z) && !originalPolygon.containsCell(x, z)) {
                            corridorCells.add(new OrthogonalPoint2i(x, z));
                        }
                    }
                }
                this.dataStore.mergeClaims(player, playerData, claim, markedClaim, null, corridorCells);
                playerData.clearCrossClaimBoundaryMarkers();
                return;
            }
            // No valid merge target found — clear stale markers
            playerData.clearCrossClaimBoundaryMarkers();
        }

        ClaimEditorSession shapedSession = ClaimEditorSession.idle(playerData.playerID).withMode(
            com.griefprevention.claims.editor.ClaimEditorMode.SHAPED,
            ClaimEditSource.TOOL
        );
        playerData.lastClaim = updateResult.claim;
        playerData.setClaimEditorSession(loadClaimIntoShapedSession(shapedSession, updateResult.claim));
        BoundaryVisualization.visualizeClaim(
            player,
            updateResult.claim,
            updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
            clickedBlock
        );
    }

    private void persistShapedBoundaryMarkers(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Claim claim,
        @NotNull ClaimEditResult result,
        @NotNull Block clickedBlock
    ) {
        OrthogonalPolygon polygon = result.preview().polygon();
        if (polygon == null) {
            return;
        }

        CreateClaimResult updateResult = this.dataStore.updateShapedClaim(player, playerData, claim, polygon);
        if (!updateResult.succeeded || updateResult.claim == null) {
            if (updateResult.denialMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, updateResult.denialMessage.get());
            } else if (updateResult.claim != null) {
                // Check if the overlapping claim is owned by the player - if so, merge the claims
                Supplier<String> noEditReason = updateResult.claim.checkPermission(player, ClaimPermission.Edit, null);
                if (noEditReason == null) {
                    // Player owns the overlapping claim — merge via mergeClaims
                    // with the preview polygon as firstPolygonOverride. This
                    // ensures the reshape corridor is included in the union
                    // (claim.getBoundaryPolygon() still returns the original
                    // polygon since updateShapedClaim failed).
                    this.dataStore.mergeClaims(player, playerData, claim, updateResult.claim, null, null, polygon);
                    BoundaryVisualization.visualizeClaim(
                        player,
                        claim,
                        claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
                        clickedBlock
                    );
                    return;
                }
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);
                visualizeConflict(player, playerData, updateResult.claim, clickedBlock, updateResult.claim.is3D());
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
            }
            return;
        }

        ClaimEditorSession shapedSession = loadClaimIntoShapedSession(
            ClaimEditorSession.idle(playerData.playerID).withMode(
                com.griefprevention.claims.editor.ClaimEditorMode.SHAPED,
                ClaimEditSource.TOOL
            ),
            updateResult.claim
        );
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
        playerData.setClaimEditorSession(
            shapedSession.withActiveSegment(result.session().activeSegment()).withPreview(preview)
        );
        BoundaryVisualization.visualizeClaim(
            player,
            updateResult.claim,
            updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
            clickedBlock
        );
    }

    /**
     * Finalize a shaped cut path that closes back to the starting boundary point.
     * Computes the resulting polygon by subtracting the cut area from the original claim.
     */
    private void finalizeCutPath(
        @NotNull Player player,
        @NotNull PlayerData playerData,
        @NotNull Claim claim,
        @NotNull ClaimEditorSession session,
        @NotNull Block clickedBlock
    ) {
        ShapedPathDraft openPath = session.openPath();
        if (openPath == null || openPath.points().size() < 3) {
            GriefPrevention.sendMessage(player, TextMode.Err, "A cut path needs at least 3 points to close.");
            return;
        }

        OrthogonalPolygon originalPolygon = claim.getBoundaryPolygon();
        List<OrthogonalPoint2i> pathPoints = openPath.points();

        OrthogonalPoint2i cutStart = pathPoints.get(0);
        OrthogonalPoint2i cutEnd = pathPoints.get(pathPoints.size() - 1);

        if (!isBoundaryPoint(originalPolygon, cutStart)) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Cut path must start on the claim boundary.");
            return;
        }
        if (!isBoundaryPoint(originalPolygon, cutEnd)) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Cut path must end on the claim boundary.");
            return;
        }
        if (cutStart.equals(cutEnd)) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Cut start and end must be different boundary points.");
            visualizeShapedEditState(player, session, clickedBlock.getY());
            return;
        }

        List<OrthogonalPoint2i> origPath = new ArrayList<>(originalPolygon.closedPath());
        if (origPath.size() > 1 && origPath.get(origPath.size() - 1).equals(origPath.get(0))) {
            origPath.remove(origPath.size() - 1);
        }

        int startEdgeIdx = -1;
        int endEdgeIdx = -1;
        for (int i = 0; i < origPath.size(); i++) {
            OrthogonalPoint2i a = origPath.get(i);
            OrthogonalPoint2i b = origPath.get((i + 1) % origPath.size());
            if (startEdgeIdx == -1 && isPointOnEdge(a, b, cutStart)) {
                startEdgeIdx = i;
            }
            if (endEdgeIdx == -1 && isPointOnEdge(a, b, cutEnd)) {
                endEdgeIdx = i;
            }
        }

        if (startEdgeIdx == -1 || endEdgeIdx == -1) {
            GriefPrevention.sendMessage(player, TextMode.Err, "Could not locate cut points on the claim boundary.");
            visualizeShapedEditState(player, session, clickedBlock.getY());
            return;
        }

        List<OrthogonalPoint2i> resultCorners = new ArrayList<>();
        for (OrthogonalPoint2i p : pathPoints) {
            resultCorners.add(p);
        }
        int idx = (endEdgeIdx + 1) % origPath.size();
        while (idx != startEdgeIdx) {
            resultCorners.add(origPath.get(idx));
            idx = (idx + 1) % origPath.size();
        }
        resultCorners.add(origPath.get(startEdgeIdx));
        resultCorners.add(cutStart);

        GriefPrevention.AddLogEntry("[GP Cut] resultCorners: " + resultCorners, CustomLogEntryTypes.Debug, false);
        for (int ei = 0; ei + 1 < resultCorners.size(); ei++) {
            OrthogonalPoint2i ea = resultCorners.get(ei);
            OrthogonalPoint2i eb = resultCorners.get(ei + 1);
            boolean horiz = ea.z() == eb.z();
            boolean vert = ea.x() == eb.x();
            if (!horiz && !vert) {
                GriefPrevention.AddLogEntry("[GP Cut] NON-ORTHOGONAL edge " + ei + ": " + ea + " -> " + eb, CustomLogEntryTypes.Debug, false);
            }
        }
        OrthogonalPolygonValidationResult validation = OrthogonalPolygon.validatePath(resultCorners);
        if (!validation.isValid() || validation.polygon() == null) {
            for (OrthogonalPolygonValidationIssue issue : validation.issues()) {
                GriefPrevention.AddLogEntry("[GP Cut] Validation issue: " + issue.type() + " - " + issue.message() + " at " + issue.point(), CustomLogEntryTypes.Debug, false);
            }
            GriefPrevention.sendMessage(player, TextMode.Err, "The resulting shape is not valid.");
            visualizeShapedEditState(player, session, clickedBlock.getY());
            return;
        }
        OrthogonalPolygon resultPolygon = validation.polygon();

        Set<OrthogonalPoint2i> resultCells = new HashSet<>();
        for (int x = resultPolygon.minX(); x <= resultPolygon.maxX(); x++) {
            for (int z = resultPolygon.minZ(); z <= resultPolygon.maxZ(); z++) {
                if (resultPolygon.containsCell(x, z)) {
                    resultCells.add(new OrthogonalPoint2i(x, z));
                }
            }
        }
        Set<OrthogonalPoint2i> originalCells = new HashSet<>();
        for (int x = originalPolygon.minX(); x <= originalPolygon.maxX(); x++) {
            for (int z = originalPolygon.minZ(); z <= originalPolygon.maxZ(); z++) {
                if (originalPolygon.containsCell(x, z)) {
                    originalCells.add(new OrthogonalPoint2i(x, z));
                }
            }
        }
        if (resultCells.size() >= originalCells.size()) {
            GriefPrevention.sendMessage(player, TextMode.Err, "The cut path doesn't remove any land from the claim.");
            visualizeShapedEditState(player, session, clickedBlock.getY());
            return;
        }

        CreateClaimResult updateResult = this.dataStore.updateShapedClaim(player, playerData, claim, resultPolygon);
        if (!updateResult.succeeded || updateResult.claim == null) {
            if (updateResult.denialMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, updateResult.denialMessage.get());
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
            }
            visualizeShapedEditState(player, session, clickedBlock.getY());
            return;
        }

        ClaimEditorSession shapedSession = ClaimEditorSession.idle(playerData.playerID).withMode(
            com.griefprevention.claims.editor.ClaimEditorMode.SHAPED,
            ClaimEditSource.TOOL
        );
        playerData.lastClaim = updateResult.claim;
        playerData.setClaimEditorSession(loadClaimIntoShapedSession(shapedSession, updateResult.claim));
        GriefPrevention.sendMessage(player, TextMode.Success, "Cut completed! The shaped land has been unclaimed.");
        BoundaryVisualization.visualizeClaim(
            player,
            updateResult.claim,
            updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
            clickedBlock
        );
    }

    /**
     * Check if a set of cells is connected using BFS (4-connectivity).
     */
    private static boolean isConnectedCells(@NotNull Set<OrthogonalPoint2i> cells) {
        if (cells.size() <= 1) {
            return true;
        }

        Set<OrthogonalPoint2i> visited = new HashSet<>();
        java.util.Queue<OrthogonalPoint2i> queue = new java.util.LinkedList<>();
        OrthogonalPoint2i start = cells.iterator().next();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            OrthogonalPoint2i current = queue.poll();
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                OrthogonalPoint2i neighbor = new OrthogonalPoint2i(current.x() + dir[0], current.z() + dir[1]);
                if (cells.contains(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        return visited.size() == cells.size();
    }

    private void visualizeShapedEditState(@NotNull Player player, @NotNull ClaimEditorSession session, int y) {
        Set<Boundary> boundaries = new HashSet<>();
        World world = player.getWorld();
        OrthogonalPolygon selectedPolygon = null;
        // The draft path is drawn in the colours of the claim it belongs to, so pathing out an
        // admin claim stays pumpkin rather than switching to the player-claim gold.
        VisualizationType draftType = VisualizationType.CLAIM;

        if (
            session.activeTarget() != null &&
            session.activeTarget().type() == ClaimEditTargetType.EXISTING_PARENT_CLAIM &&
            session.activeTarget().claimId() != null
        ) {
            Claim targetClaim = this.dataStore.getClaim(session.activeTarget().claimId());
            if (targetClaim != null) {
                VisualizationType type = targetClaim.isAdminClaim()
                    ? VisualizationType.ADMIN_CLAIM
                    : VisualizationType.CLAIM;
                boundaries.add(new Boundary(targetClaim, type));
                selectedPolygon = targetClaim.getBoundaryPolygon();
                draftType = type;
            }
        }

        // Visualize nearby claims for potential merges
        Set<Claim> nearbyClaims = this.dataStore.getNearbyClaims(player.getLocation());
        if (!nearbyClaims.isEmpty()) {
            for (Claim nearbyClaim : nearbyClaims) {
                if (!nearbyClaim.is3D()) {
                    VisualizationType type =
                        nearbyClaim.isAdminClaim() && nearbyClaim.parent == null
                            ? VisualizationType.ADMIN_CLAIM
                            : VisualizationType.CLAIM;
                    boundaries.add(new Boundary(nearbyClaim, type));
                }
            }
        }

        // Visualize draft points as proper segments with glowstone side markers
        List<OrthogonalPoint2i> draftPoints = session.preview().draftPoints();
        for (int i = 0; i < draftPoints.size(); i++) {
            OrthogonalPoint2i point = draftPoints.get(i);
            boolean onSelectedBoundary = selectedPolygon != null && isBoundaryPoint(selectedPolygon, point);

            // Draw segment legs for every additional draft point, even when the endpoint is on an existing boundary.
            if (i > 0) {
                OrthogonalPoint2i prevPoint = draftPoints.get(i - 1);
                Location start = new Location(world, prevPoint.x(), y, prevPoint.z());
                Location end = new Location(world, point.x(), y, point.z());
                boundaries.add(new Boundary(new BoundingBox(start, end), draftType));
            }

            if (!onSelectedBoundary) {
                boundaries.add(
                    new Boundary(
                        new BoundingBox(
                            new Location(world, point.x(), y, point.z()),
                            new Location(world, point.x(), y, point.z())
                        ),
                        VisualizationType.INITIALIZE_ZONE
                    )
                );
            }
        }

        if (session.preview().snappedPoint() != null) {
            OrthogonalPoint2i point = session.preview().snappedPoint();
            if (selectedPolygon == null || !isBoundaryPoint(selectedPolygon, point)) {
                boundaries.add(
                    new Boundary(
                        new BoundingBox(
                            new Location(world, point.x(), y, point.z()),
                            new Location(world, point.x(), y, point.z())
                        ),
                        VisualizationType.INITIALIZE_ZONE
                    )
                );
            }
        }

        for (OrthogonalPoint2i point : session.preview().conflictPoints()) {
            boundaries.add(
                new Boundary(
                    new BoundingBox(
                        new Location(world, point.x(), y, point.z()),
                        new Location(world, point.x(), y, point.z())
                    ),
                    VisualizationType.CONFLICT_ZONE
                )
            );
        }

        if (!boundaries.isEmpty()) {
            BoundaryVisualization.callAndVisualize(
                new BoundaryVisualizationEvent(player, boundaries, player.getEyeLocation().getBlockY())
            );
        }
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

    // Raycast from player's eye to detect intersection near any 3D subclaim corner
    // within maxDistance.
    // Returns the closest corner hit along the ray, or null if none.
    private CornerHit raycast3DSubclaimCorner(Player player, int maxDistance) {
        Vector eyePos = player.getEyeLocation().toVector();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        World world = player.getWorld();

        CornerHit best = null;
        double bestT = Double.POSITIVE_INFINITY;
        double threshold = 1.2; // be more forgiving when aiming at corners

        for (Claim root : this.dataStore.getClaims()) {
            if (root == null || !root.inDataStore) continue;
            if (!world.equals(root.getLesserBoundaryCorner().getWorld())) continue;
            Deque<Claim> stack = new ArrayDeque<>();
            stack.push(root);

            while (!stack.isEmpty()) {
                Claim current = stack.pop();
                if (current == null || !current.inDataStore) continue;
                if (!world.equals(current.getLesserBoundaryCorner().getWorld())) continue;

                if (current.is3D()) {
                    Location lesser = current.getLesserBoundaryCorner();
                    Location greater = current.getGreaterBoundaryCorner();

                    int minX = Math.min(lesser.getBlockX(), greater.getBlockX());
                    int maxX = Math.max(lesser.getBlockX(), greater.getBlockX());
                    int minY = Math.min(lesser.getBlockY(), greater.getBlockY());
                    int maxY = Math.max(lesser.getBlockY(), greater.getBlockY());
                    int minZ = Math.min(lesser.getBlockZ(), greater.getBlockZ());
                    int maxZ = Math.max(lesser.getBlockZ(), greater.getBlockZ());

                    int[] xs = new int[] { minX, maxX };
                    int[] ys = new int[] { minY, maxY };
                    int[] zs = new int[] { minZ, maxZ };

                    for (int xi : xs)
                        for (int yi : ys)
                            for (int zi : zs) {
                                // Evaluate the real corner itself
                                CornerHit candidate = evaluateCornerCandidate(
                                    eyePos,
                                    dir,
                                    maxDistance,
                                    threshold,
                                    current,
                                    xi + 0.5,
                                    yi + 0.5,
                                    zi + 0.5,
                                    xi,
                                    yi,
                                    zi
                                );
                                if (candidate != null && candidate.t < bestT) {
                                    bestT = candidate.t;
                                    best = candidate;
                                }

                                // Allow aiming at interior extensions (+/-1 block toward claim interior)
                                if (maxX - minX >= 1) {
                                    int offsetX = xi == minX ? xi + 1 : xi - 1;
                                    candidate = evaluateCornerCandidate(
                                        eyePos,
                                        dir,
                                        maxDistance,
                                        threshold,
                                        current,
                                        offsetX + 0.5,
                                        yi + 0.5,
                                        zi + 0.5,
                                        xi,
                                        yi,
                                        zi
                                    );
                                    if (candidate != null && candidate.t < bestT) {
                                        bestT = candidate.t;
                                        best = candidate;
                                    }
                                }

                                if (maxZ - minZ >= 1) {
                                    int offsetZ = zi == minZ ? zi + 1 : zi - 1;
                                    candidate = evaluateCornerCandidate(
                                        eyePos,
                                        dir,
                                        maxDistance,
                                        threshold,
                                        current,
                                        xi + 0.5,
                                        yi + 0.5,
                                        offsetZ + 0.5,
                                        xi,
                                        yi,
                                        zi
                                    );
                                    if (candidate != null && candidate.t < bestT) {
                                        bestT = candidate.t;
                                        best = candidate;
                                    }
                                }

                                if (maxY - minY >= 1) {
                                    int offsetY = yi == minY ? yi + 1 : yi - 1;
                                    candidate = evaluateCornerCandidate(
                                        eyePos,
                                        dir,
                                        maxDistance,
                                        threshold,
                                        current,
                                        xi + 0.5,
                                        offsetY + 0.5,
                                        zi + 0.5,
                                        xi,
                                        yi,
                                        zi
                                    );
                                    if (candidate != null && candidate.t < bestT) {
                                        bestT = candidate.t;
                                        best = candidate;
                                    }
                                }

                                if (maxY == minY) {
                                    int aboveY = yi + 1;
                                    if (aboveY <= GriefPrevention.getWorldMaxY(world)) {
                                        candidate = evaluateCornerCandidate(
                                            eyePos,
                                            dir,
                                            maxDistance,
                                            threshold,
                                            current,
                                            xi + 0.5,
                                            aboveY + 0.5,
                                            zi + 0.5,
                                            xi,
                                            yi,
                                            zi
                                        );
                                        if (candidate != null && candidate.t < bestT) {
                                            bestT = candidate.t;
                                            best = candidate;
                                        }
                                    }

                                    int belowY = yi - 1;
                                    if (belowY >= GriefPrevention.getWorldMinY(world)) {
                                        candidate = evaluateCornerCandidate(
                                            eyePos,
                                            dir,
                                            maxDistance,
                                            threshold,
                                            current,
                                            xi + 0.5,
                                            belowY + 0.5,
                                            zi + 0.5,
                                            xi,
                                            yi,
                                            zi
                                        );
                                        if (candidate != null && candidate.t < bestT) {
                                            bestT = candidate.t;
                                            best = candidate;
                                        }
                                    }
                                }
                            }
                }

                if (!current.children.isEmpty()) {
                    for (Claim child : current.children) {
                        if (child != null && child.inDataStore) {
                            stack.push(child);
                        }
                    }
                }
            }
        }

        return best;
    }

    private CornerHit evaluateCornerCandidate(
        Vector eyePos,
        Vector dir,
        int maxDistance,
        double threshold,
        Claim claim,
        double targetX,
        double targetY,
        double targetZ,
        int hitX,
        int hitY,
        int hitZ
    ) {
        Vector target = new Vector(targetX, targetY, targetZ);
        Vector toTarget = target.clone().subtract(eyePos);

        double t = toTarget.dot(dir);
        if (t < 0 || t > maxDistance) {
            return null;
        }

        Vector closestPoint = eyePos.clone().add(dir.clone().multiply(t));
        double distance = closestPoint.distance(target);
        if (distance > threshold) {
            return null;
        }

        return new CornerHit(claim, hitX, hitY, hitZ, t);
    }

    // determines whether a block type is an inventory holder. uses a caching
    // strategy to save cpu time
    private final ConcurrentHashMap<Material, Boolean> inventoryHolderCache = new ConcurrentHashMap<>();

    /**
     * Returns true for shelf blocks, which accept the held item on right-click.
     * <p>
     * The claim tools normally intercept a right-click to show claim boundaries, but a shelf's
     * vanilla behaviour is to store whatever the player is holding — so intercepting makes the
     * tool impossible to place on a shelf. Item frames already behave correctly because they are
     * entities and never reach this handler.
     * <p>
     * Shelves are {@code BlockInventoryHolder}s, so they remain protected by the container trust
     * check either way; this only decides whether the claim tools consume the interaction.
     * <p>
     * Matched by name so the plugin still compiles and runs on versions predating shelves.
     *
     * @param material the clicked block's material, may be null
     * @return true if the block is a shelf
     */
    private static boolean isShelf(Material material) {
        // Excludes BOOKSHELF and CHISELED_BOOKSHELF, which are not item-accepting shelves.
        return material != null && material.name().endsWith("_SHELF");
    }

    private boolean isInventoryHolder(Block clickedBlock) {
        Material cacheKey = clickedBlock.getType();
        Boolean cachedValue = this.inventoryHolderCache.get(cacheKey);
        if (cachedValue != null) {
            return cachedValue;
        } else {
            boolean isHolder = clickedBlock.getState() instanceof InventoryHolder;
            this.inventoryHolderCache.put(cacheKey, isHolder);
            return isHolder;
        }
    }

    /**
     * Returns true for any trapdoor that should be lockable when
     * {@code GriefPrevention.Claims.LockTrapDoors} is enabled.
     *
     * <p>Uses the TRAPDOORS material tag as the primary check, then falls back to a
     * name-based suffix match. The fallback ensures copper trapdoors (and any
     * future trapdoor variants) are protected even if the running server's
     * Bukkit API has not yet been updated to include them in the tag. See
     * upstream GriefPrevention/GriefPrevention#2550.</p>
     */
    private static boolean isLockableTrapdoor(@NotNull Material material) {
        if (MaterialTagCompat.isTagged("TRAPDOORS", material)) return true;
        String name = material.name();
        return name.endsWith("_TRAPDOOR");
    }

    private boolean onLeftClickWatchList(Material material) {
        if (MaterialTagCompat.isTagged("BUTTONS", material)) return true;
        switch (material) {
            case LEVER:
            case REPEATER:
            case CAKE:
            case DRAGON_EGG:
                return true;
            default:
                return false;
        }
    }

    static Block getTargetBlock(Player player, int maxDistance) throws IllegalStateException {
        Location eye = player.getEyeLocation();
        Material eyeMaterial = eye.getBlock().getType();
        boolean passThroughWater = eyeMaterial == Material.WATER;
        BlockIterator iterator = new BlockIterator(player.getLocation(), player.getEyeHeight(), maxDistance);
        Block result = player.getLocation().getBlock().getRelative(BlockFace.UP);
        while (iterator.hasNext()) {
            result = iterator.next();
            Material type = result.getType();
            if (
                !MaterialTagCompat.isTagged("REPLACEABLE", type) || (!passThroughWater && type == Material.WATER)
            ) return result;
        }

        return result;
    }

    /**
     * Block brushing completion against a claim's Build permission.
     *
     * <p>Brushing is a tick-driven, multi-frame action: vanilla advances brush
     * progress every tick the player holds right-click while looking at a
     * brushable block, and only fires {@link EntityChangeBlockEvent} when the
     * block actually transitions (e.g. {@code SUSPICIOUS_SAND -> SAND}).</p>
     *
     * <p>The {@link PlayerInteractEvent} brush check earlier in this class is
     * still useful as a fast-fail when a player explicitly starts brushing
     * inside a claim, but it is "front-loaded": once brushing has started, the
     * player can move and the targeted block can change without any new
     * {@code PlayerInteractEvent} firing. That left a loophole — start a brush
     * outside a claim, walk into the claim while still holding right-click,
     * and have the resulting block change land on a claimed block.</p>
     *
     * <p>Catching the change at completion time closes that loophole regardless
     * of where the player started brushing or where they are now.</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBrushComplete(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        // Only consider results that come from brushing. Brushing converts
        // SUSPICIOUS_SAND -> SAND and SUSPICIOUS_GRAVEL -> GRAVEL. Filter on
        // both the source material (suspicious_*) and the destination so we
        // don't accidentally interfere with unrelated entity-driven block
        // changes (e.g. zombies breaking doors, sheep eating grass).
        Material from = event.getBlock().getType();
        Material to = event.getTo();
        boolean isBrushFromSand = from == Material.SUSPICIOUS_SAND && to == Material.SAND;
        boolean isBrushFromGravel = from == Material.SUSPICIOUS_GRAVEL && to == Material.GRAVEL;
        if (!isBrushFromSand && !isBrushFromGravel) return;

        // Defense in depth: also confirm the player is actually holding a brush.
        // The PlayerInteractEvent path may have already swapped tools, but the
        // brushing entity-change should only originate from a brush in hand.
        ItemStack mainHand = CompatUtil.getItemInMainHand(player);
        ItemStack offHand = CompatUtil.getItemInOffHand(player);
        if (offHand == null) {
            offHand = new ItemStack(Material.AIR);
        }
        try {
            if (
                mainHand.getType() != Material.valueOf("BRUSH") && offHand.getType() != Material.valueOf("BRUSH")
            ) return;
        } catch (IllegalArgumentException e) {
            // 1.8.8: BRUSH doesn't exist, skip brush check
        }

        if (!instance.claimsEnabledForWorld(event.getBlock().getWorld())) return;

        Block block = event.getBlock();
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = this.dataStore.getClaimAt(block.getLocation(), false, playerData.lastClaim);
        if (claim == null) return;

        playerData.lastClaim = claim;
        Supplier<String> noBuildReason = claim.checkPermission(player, ClaimPermission.Build, event);
        if (noBuildReason != null) {
            event.setCancelled(true);
            GriefPrevention.sendRateLimitedErrorMessage(player, noBuildReason.get());
        }
    }

    // Prevents untrusted players from entering boats in claimed areas
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!(event.getEntered() instanceof Player)) {
            return; // Only handle player entries
        }

        Player player = (Player) event.getEntered();
        Vehicle vehicle = event.getVehicle();

        // Only handle boats
        if (!vehicle.getType().name().contains("BOAT")) {
            return;
        }

        // If claims aren't enabled in this world, skip
        if (!instance.claimsEnabledForWorld(vehicle.getWorld())) {
            return;
        }

        // Check if the location is in a claim
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        Claim claim = this.dataStore.getClaimAt(vehicle.getLocation(), false, playerData.lastClaim);

        if (claim != null) {
            playerData.lastClaim = claim;
            Supplier<String> noAccessReason = claim.checkPermission(player, ClaimPermission.Access, event);

            if (noAccessReason != null) {
                event.setCancelled(true);
                GriefPrevention.sendRateLimitedErrorMessage(player, noAccessReason.get());
            }
        }
    }

    /**
     * Handle RestoreNature shovel mode interactions.
     * Captures block snapshots and starts async processing to restore nature.
     * Folia-safe using SchedulerUtil.
     */
    private void handleRestoreNature(Player player, Block clickedBlock, PlayerData playerData) {
        World world = clickedBlock.getWorld();
        int centerX = clickedBlock.getX();
        int centerZ = clickedBlock.getZ();
        int minY = GriefPrevention.getWorldMinY(world);
        int maxY = GriefPrevention.getWorldMaxY(world);
        int radius = 5;
        if (playerData.shovelMode == ShovelMode.RestoreNatureFill) {
            radius = Math.max(1, Math.min(playerData.fillRadius, 10));
        }
        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY;
        int sizeZ = maxZ - minZ + 1;
        BlockSnapshot[][][] snapshots = new BlockSnapshot[sizeX][sizeY][sizeZ];
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    Block block = world.getBlockAt(minX + x, minY + y, minZ + z);
                    snapshots[x][y][z] = new BlockSnapshot(block);
                }
            }
        }
        Location lesserCorner = new Location(world, minX, minY, minZ);
        Location greaterCorner = new Location(world, maxX, maxY - 1, maxZ);
        boolean aggressiveMode = playerData.shovelMode == ShovelMode.RestoreNatureAggressive;
        org.bukkit.block.Biome biome = clickedBlock.getBiome();
        boolean creativeMode = instance.creativeRulesApply(clickedBlock.getLocation());
        RestoreNatureProcessingTask processingTask = new RestoreNatureProcessingTask(
            snapshots,
            minY,
            world.getEnvironment(),
            biome,
            lesserCorner,
            greaterCorner,
            world.getSeaLevel(),
            aggressiveMode,
            creativeMode,
            player
        );
        me.ryanhamshire.GriefPrevention.util.SchedulerUtil.runAsyncNow(instance, processingTask);
        GriefPrevention.sendMessage(player, TextMode.Info, "Restoring nature in the selected area...");
    }

    private void visualizeConflict(
        Player player,
        PlayerData playerData,
        Claim claim,
        Block clickedBlock,
        boolean is3D
    ) {
        VisualizationType conflictType = is3D ? VisualizationType.CONFLICT_ZONE_3D : VisualizationType.CONFLICT_ZONE;
        BoundaryVisualization.visualizeClaim(player, claim, conflictType, clickedBlock);

        // If glow is enabled, auto-clear after 5 seconds
        if (GriefPrevention.instance.config_visualizationGlow) {
            SchedulerUtil.runLaterEntity(
                GriefPrevention.instance,
                player,
                () -> {
                    playerData.setVisibleBoundaries(null);
                },
                100L
            );
        }
    }

    // Direct entrypoint for legacy right-click-air from Netty packet handler
    // This bypasses the public event system to avoid duplicates
    public void handleLegacyRightClickAir(
        Player player,
        org.bukkit.inventory.ItemStack packetItem,
        float yaw,
        float pitch
    ) {
        // Create a minimal event for the dispatcher (not fired publicly)
        PlayerInteractEvent event = new PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_AIR,
            packetItem,
            null,
            org.bukkit.block.BlockFace.SELF
        );

        this.claimToolDispatcher.handle(event, null, Material.AIR, packetItem, yaw, pitch);
    }
}
