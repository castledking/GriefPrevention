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
import com.griefprevention.claims.editor.ShapedPathDraft;
import com.griefprevention.claims.editor.ClaimEditTarget;
import com.griefprevention.claims.editor.ClaimEditTargetType;
import com.griefprevention.claims.editor.ClaimEditor;
import com.griefprevention.claims.editor.ClaimEditorMode;
import com.griefprevention.claims.editor.ClaimEditorSession;
import com.griefprevention.claims.editor.ClaimEditorSkeleton;
import com.griefprevention.compat.MaterialTagCompat;
import com.griefprevention.events.BoundaryVisualizationEvent;
import com.griefprevention.geometry.OrthogonalEdge2i;
import com.griefprevention.geometry.OrthogonalPoint2i;
import com.griefprevention.geometry.OrthogonalPolygon;
import com.griefprevention.geometry.OrthogonalPolygonValidationIssue;
import com.griefprevention.geometry.OrthogonalPolygonValidationIssueType;
import com.griefprevention.geometry.OrthogonalPolygonValidationResult;
import com.griefprevention.visualization.Boundary;
import com.griefprevention.visualization.BoundaryVisualization;
import com.griefprevention.visualization.VisualizationType;
import me.ryanhamshire.GriefPrevention.compat.CompatUtil;
import me.ryanhamshire.GriefPrevention.events.ClaimInspectionEvent;
import me.ryanhamshire.GriefPrevention.events.StartClaimCreationEvent;
import me.ryanhamshire.GriefPrevention.events.StartClaimResizeEvent;
import me.ryanhamshire.GriefPrevention.events.StartSubdivideClaimCreationEvent;
import me.ryanhamshire.GriefPrevention.util.BoundingBox;
import me.ryanhamshire.GriefPrevention.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.Arrays;
import java.util.Collections;

final class ClaimToolDispatcher
{
    private static final @NotNull ClaimEditor claimEditor = new ClaimEditorSkeleton();

    private final @NotNull GriefPrevention instance;
    private final @NotNull PlayerEventHandler playerEventHandler;
    private final @NotNull DataStore dataStore;

    ClaimToolDispatcher(@NotNull GriefPrevention instance, @NotNull PlayerEventHandler playerEventHandler)
    {
        this.instance = instance;
        this.playerEventHandler = playerEventHandler;
        this.dataStore = instance.dataStore;
    }

    boolean handle(PlayerInteractEvent event, Block clickedBlock, Material clickedBlockType)
    {
        return handle(event, clickedBlock, clickedBlockType, null);
    }

    boolean handle(PlayerInteractEvent event, Block clickedBlock, Material clickedBlockType, ItemStack overrideItem)
    {
        return handle(event, clickedBlock, clickedBlockType, overrideItem, null, null);
    }

    boolean handle(PlayerInteractEvent event, Block clickedBlock, Material clickedBlockType, ItemStack overrideItem, Float yaw, Float pitch)
    {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR)
        {
            return false;
        }

        EquipmentSlot hand = CompatUtil.getInteractEventHand(event);
        if (hand != null && hand != EquipmentSlot.HAND)
        {
            return false;
        }

        Player player = event.getPlayer();
        ItemStack itemInHand = overrideItem != null ? overrideItem : instance.getItemInHand(player, hand);
        if (itemInHand == null)
        {
            return false;
        }
        Material materialInHand = itemInHand.getType();

        if (materialInHand != instance.config_claims_investigationTool
                && materialInHand != instance.config_claims_modificationTool)
        {
            return false;
        }

        suppressVanillaClaimToolUse(event);

        PlayerData playerData = null;

        if (materialInHand == instance.config_claims_investigationTool)
        {
            return handleInvestigationTool(event, player, clickedBlock, clickedBlockType, materialInHand, playerData, yaw, pitch);
        }

        return handleModificationTool(event, player, clickedBlock, clickedBlockType, materialInHand, playerData, yaw, pitch);
    }

    private static void suppressVanillaClaimToolUse(@NotNull PlayerInteractEvent event)
    {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
        {
            return;
        }

        try
        {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
        }
        catch (NoSuchMethodError ignored)
        {
            event.setCancelled(true);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Investigation Tool (stick)
    // ----------------------------------------------------------------------------------------

    private boolean handleInvestigationTool(
            PlayerInteractEvent event,
            Player player,
            Block clickedBlock,
            Material clickedBlockType,
            Material materialInHand,
            PlayerData playerData,
            Float yaw,
            Float pitch)
    {
        Action action = event.getAction();

        // if claims are disabled in this world, do nothing
        if (!instance.claimsEnabledForWorld(player.getWorld()))
            return true;

        // if holding shift (sneaking), show all claims in area
        if (player.isSneaking() && player.hasPermission("griefprevention.visualizenearbyclaims")) {
            // find nearby claims
            Set<Claim> claims = this.dataStore.getNearbyClaims(player.getLocation());

            // alert plugins of a claim inspection, return if cancelled
            ClaimInspectionEvent inspectionEvent = new ClaimInspectionEvent(player, null, claims, true);
            Bukkit.getPluginManager().callEvent(inspectionEvent);
            if (inspectionEvent.isCancelled())
                return true;

            // visualize boundaries
            BoundaryVisualization.visualizeNearbyClaims(player, inspectionEvent.getClaims(),
                    player.getEyeLocation().getBlockY());
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.ShowNearbyClaims,
                    String.valueOf(claims.size()));

            return true;
        }

        // FEATURE: shovel and stick can be used from a distance away
        if (action == Action.RIGHT_CLICK_AIR) {
            // try to find a far away non-air block along line of sight
            if (yaw != null && pitch != null) {
                clickedBlock = getTargetBlock(player, 100, yaw.floatValue(), pitch.floatValue());
            } else {
                clickedBlock = getTargetBlock(player, 100);
            }
            clickedBlockType = clickedBlock.getType();
        }

        // if no block, stop here
        if (clickedBlock == null) {
            return true;
        }

        playerData = this.dataStore.getPlayerData(player.getUniqueId());

        // air indicates too far away
        if (clickedBlockType == Material.AIR) {
            if (materialInHand != instance.config_claims_modificationTool) {
                GriefPrevention.sendRateLimitedErrorMessage(player, Messages.TooFarAway);
                // Remove visualizations
                playerData.setVisibleBoundaries(null);
                return true;
            }
            // else: do not message/return here; shovel path below will handle it
        }

        // if the player is currently resizing a claim, stop resizing and proceed with
        // inspection.
        // This allows the stick to "fix" a stuck visualization or conflict zone.
        if (playerData.claimResizing != null) {
            playerData.claimResizing = null;
            playerData.lastShovelLocation = null;
        }

        Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false /* ignore height */,
                playerData.lastClaim);

        // no claim case
        if (claim == null) {
            // alert plugins of a claim inspection, return if cancelled
            ClaimInspectionEvent inspectionEvent = new ClaimInspectionEvent(player, clickedBlock, null);
            Bukkit.getPluginManager().callEvent(inspectionEvent);
            if (inspectionEvent.isCancelled())
                return true;

            GriefPrevention.sendMessage(player, TextMode.Info, Messages.BlockNotClaimed);

            // Clear any existing visualization
            playerData.setVisibleBoundaries(null);
            return true; // Important: Add this return to prevent further processing
        }

        // claim case
        else {
            // alert plugins of a claim inspection, return if cancelled
            ClaimInspectionEvent inspectionEvent = new ClaimInspectionEvent(player, clickedBlock, claim);
            Bukkit.getPluginManager().callEvent(inspectionEvent);
            if (inspectionEvent.isCancelled())
                return true;

            playerData.lastClaim = claim;
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.BlockClaimed, claim.getOwnerName());

            // visualize boundary. Preserve subdivision colors when probing 3D claims so
            // parents stay intact.
            VisualizationType inspectType = claim.is3D() ? VisualizationType.SUBDIVISION_3D
                    : VisualizationType.CLAIM;
            BoundaryVisualization.visualizeClaim(player, claim, inspectType);

            if (player.hasPermission("griefprevention.seeclaimsize")) {
                GriefPrevention.sendMessage(player, TextMode.Info,
                        "  " + claim.getWidth() + "x" + claim.getHeight() + "=" + claim.getArea());
            }

            // if permission, tell about the player's offline time
            if (!claim.isAdminClaim() && (player.hasPermission("griefprevention.deleteclaims")
                    || player.hasPermission("griefprevention.seeinactivity"))) {
                if (claim.parent != null) {
                    claim = claim.parent;
                }
                Date lastLogin = new Date(Bukkit.getOfflinePlayer(claim.ownerID).getLastPlayed());
                Date now = new Date();
                long daysElapsed = (now.getTime() - lastLogin.getTime()) / (1000 * 60 * 60 * 24);

                GriefPrevention.sendMessage(player, TextMode.Info, Messages.PlayerOfflineTime,
                        String.valueOf(daysElapsed));

                // drop the data we just loaded, if the player isn't online
                if (instance.getServer().getPlayer(claim.ownerID) == null)
                    this.dataStore.clearCachedPlayerData(claim.ownerID);
            }
        }

        return true;
    }

    // ----------------------------------------------------------------------------------------
    // Modification Tool (golden shovel)
    // ----------------------------------------------------------------------------------------

    private boolean handleModificationTool(
            PlayerInteractEvent event,
            Player player,
            Block clickedBlock,
            Material clickedBlockType,
            Material materialInHand,
            PlayerData playerData,
            Float yaw,
            Float pitch)
    {
        Action action = event.getAction();

        boolean cornerSelected = false; // track if we snapped to a 3D corner so AIR guard can be bypassed

        if (action == Action.RIGHT_CLICK_AIR) {
            // try to find a far away non-air block along line of sight first
            if (yaw != null && pitch != null) {
                clickedBlock = getTargetBlock(player, 100, yaw.floatValue(), pitch.floatValue());
            } else {
                clickedBlock = getTargetBlock(player, 100);
            }
            if (clickedBlock == null) {
                GriefPrevention.AddLogEntry("[GP Debug] getTargetBlock returned null for " + player.getName(), CustomLogEntryTypes.Debug, false);
                return true;
            }
            clickedBlockType = clickedBlock.getType();
            GriefPrevention.AddLogEntry("[GP Debug] getTargetBlock found " + clickedBlockType.name() + " @ " + clickedBlock.getX() + "," + clickedBlock.getY() + "," + clickedBlock.getZ() + " for " + player.getName(), CustomLogEntryTypes.Debug, false);
        }

        // Load playerData early so we can correctly gate 3D corner snapping.
        // Critical: during an in-progress resize/subdivide we must NOT snap to existing 3D
        // corners, otherwise the second click gets pulled back onto a corner of the claim
        // being resized — either matching lastShovelLocation (no-op) or collapsing the claim
        // to a 1-block-thin sliver. PlayerEventHandler's path already gates via
        // ClaimToolInteractionState.shouldAttempt3DCornerSelection; mirror that here.
        if (playerData == null) {
            playerData = this.dataStore.getPlayerData(player.getUniqueId());
        }

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
            GriefPrevention.AddLogEntry("[GP Debug] clickedBlock is null for " + player.getName(), CustomLogEntryTypes.Debug, false);
            return true;
        }

        // can't use the shovel from too far away, unless we snapped to a 3D corner
        // (corner itself may be air)
        if (clickedBlockType == Material.AIR && !cornerSelected) {
            GriefPrevention.AddLogEntry("[GP Debug] clickedBlock is AIR for " + player.getName(), CustomLogEntryTypes.Debug, false);
            return true;
        }

        GriefPrevention.AddLogEntry("[GP Debug] Passed initial checks, continuing for " + player.getName(), CustomLogEntryTypes.Debug, false);

        // if the player doesn't have claims permission, don't do anything
        if (!player.hasPermission("griefprevention.createclaims")) {
            GriefPrevention.sendRateLimitedErrorMessage(player, Messages.NoCreateClaimPermission);
            return true;
        }

        playerData = this.dataStore.getPlayerData(player.getUniqueId());

        // Handle RestoreNature shovel modes
        if (playerData.shovelMode == ShovelMode.RestoreNature ||
                playerData.shovelMode == ShovelMode.RestoreNatureAggressive ||
                playerData.shovelMode == ShovelMode.RestoreNatureFill) {
            handleRestoreNature(player, clickedBlock, playerData);
            return true;
        }

        if (playerData.shovelMode == ShovelMode.Shaped) {
            playerData.setEphemeralBasicShapedSegmentPreview(false);
            if (!GriefPrevention.instance.config_claims_allowShapedClaims) {
                resetToBasicMode(playerData);
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedClaimsDisabled);
                return true;
            }
            if (handleShapedResizeInteraction(player, playerData, clickedBlock)) {
                return true;
            }
            handleShapedModeInteraction(player, playerData, clickedBlock);
            return true;
        }

        if (playerData.shovelMode == ShovelMode.ShapedSubdivide) {
            playerData.setEphemeralBasicShapedSegmentPreview(false);
            if (!GriefPrevention.instance.config_claims_allowShapedSubClaims) {
                resetToBasicMode(playerData);
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubClaimsDisabled);
                return true;
            }
            handleShapedSubdivideModeInteraction(player, playerData, clickedBlock);
            return true;
        }

        if (playerData.shovelMode == ShovelMode.Merge) {
            handleMergeModeInteraction(player, playerData, clickedBlock);
            return true;
        }

        // if he's resizing a claim and that claim hasn't been deleted since he started
        // resizing it
        if (playerData.claimResizing != null && playerData.claimResizing.inDataStore) {
            // Don't handle resize logic in shaped mode
            if (playerData.shovelMode == ShovelMode.Shaped) {
                return true;
            }
            if (playerData.lastShovelLocation == null && playerData.shovelMode != ShovelMode.Shaped) {
                startClaimResizeSelection(player, playerData, playerData.claimResizing, clickedBlock);
                return true;
            }
            if (clickedBlock.getLocation().equals(playerData.lastShovelLocation))
                return true;

            if (tryResizeShapedClaim(player, playerData, clickedBlock)) {
                return true;
            }

            // figure out what the coords of his new claim would be
            int newx1, newx2, newz1, newz2, newy1, newy2;
            if (playerData.lastShovelLocation.getBlockX() == playerData.claimResizing.getLesserBoundaryCorner()
                    .getBlockX()) {
                newx1 = clickedBlock.getX();
                newx2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockX();
            } else {
                newx1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockX();
                newx2 = clickedBlock.getX();
            }

            if (playerData.lastShovelLocation.getBlockZ() == playerData.claimResizing.getLesserBoundaryCorner()
                    .getBlockZ()) {
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
                boolean isSingleLayer = (currentMinY == currentMaxY);

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

            return true;
        }

        // otherwise, since not currently resizing a claim, must be starting a resize,
        // creating a new claim, or creating a subdivision
        // Prefer a Y-aware lookup first so we correctly target stacked 3D subclaims at
        // this Y level.
        Claim resolvedClaim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false /* respect height */,
                playerData.lastClaim);
        if (resolvedClaim == null) {
            // Fallback to ignore-height search to preserve legacy behavior when no 3D
            // subclaim matches Y
            resolvedClaim = this.dataStore.getClaimAt(clickedBlock.getLocation(), true /* ignore height */,
                    playerData.lastClaim);
        }
        Claim claim = playerEventHandler.findDeepestContainingClaim(resolvedClaim, clickedBlock.getLocation());
        if (claim != null) {
            playerData.lastClaim = claim;
        }

        // If this click is exactly at a shared corner, promote to the outermost parent
        // sharing that corner.
        if (claim != null) {
            Claim promoted = claim;
            while (promoted.parent != null && isCornerMatch(promoted, clickedBlock)) {
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
                this.playerEventHandler.onPlayerInteract(event);
                return true;
            }
            claim = null;
        }

        // if within an existing claim, he's not creating a new one
        if (claim != null) {
            // if the player has permission to edit the claim or subdivision
            final String ownerName = claim.getOwnerName();
            Supplier<String> noEditReason = claim.checkPermission(player, ClaimPermission.Edit, event,
                    () -> instance.dataStore.getMessage(Messages.CreateClaimFailOverlapOtherPlayer, ownerName));
            boolean isSubdivisionMode = playerData.shovelMode == ShovelMode.Subdivide
                    || playerData.shovelMode == ShovelMode.Subdivide3D;
            boolean canManage = isSubdivisionMode
                    && claim.checkPermission(player, ClaimPermission.Manage, null) == null;
            if (noEditReason == null || canManage) {
                // Shift+right-click on a top-level shaped claim edge (basic mode): ephemeral segment selection for
                // later expansion in shaped mode. Requires AllowShapedClaims; corners use normal resize below.
                if (noEditReason == null
                        && playerData.shovelMode == ShovelMode.Basic
                        && player.isSneaking()
                        && instance.config_claims_allowShapedClaims
                        && trySelectShapedBoundarySegmentBasicMode(player, playerData, claim, clickedBlock)) {
                    return true;
                }

                // if he clicked on a corner, start resizing it (unless sneaking or in shaped mode)
                boolean isCorner = isCornerMatch(claim, clickedBlock);
                if (noEditReason == null && isCorner && !player.isSneaking() && playerData.shovelMode != ShovelMode.Shaped) {
                    startClaimResizeSelection(player, playerData, claim, clickedBlock);
                }

                // if he's in subdivision mode, he's creating a new subdivision
                else if (playerData.shovelMode == ShovelMode.Subdivide
                        || playerData.shovelMode == ShovelMode.Subdivide3D) {
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
                                boolean onXBoundary = (clickedBlock.getX() == claim.getLesserBoundaryCorner()
                                        .getBlockX() ||
                                        clickedBlock.getX() == claim.getGreaterBoundaryCorner().getBlockX());
                                boolean onZBoundary = (clickedBlock.getZ() == claim.getLesserBoundaryCorner()
                                        .getBlockZ() ||
                                        clickedBlock.getZ() == claim.getGreaterBoundaryCorner().getBlockZ());

                                // Block if on exact X/Z boundary (corners), allow nesting otherwise
                                // Also require 3D mode to stack inside 3D claims
                                canStartSubdivision = wants3DSubdivision && !(onXBoundary && onZBoundary);
                            } else {
                                // Parent is 2D: allow additional subdivisions (2D or 3D) when nesting is
                                // enabled.
                                canStartSubdivision = true;
                            }

                            if (canStartSubdivision) {
                                if (startSubdivisionCreationCancelled(player, clickedBlock, claim)) {
                                    return true;
                                }
                                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionStart);
                                playerData.lastShovelLocation = clickedBlock.getLocation();
                                if (wants3DSubdivision) {
                                    BoundaryVisualization.visualizeArea(player, new BoundingBox(clickedBlock),
                                            VisualizationType.INITIALIZE_ZONE_3D, clickedBlock.getY());
                                } else {
                                    BoundaryVisualization.visualizeArea(player, new BoundingBox(clickedBlock),
                                            VisualizationType.INITIALIZE_ZONE);
                                }
                                playerData.claimSubdividing = claim;
                            } else {
                                // Show conflict zone visualization for the existing 3D subdivision
                                if (claimIs3D) {
                                    GriefPrevention.sendMessage(player, TextMode.Err,
                                            Messages.ResizeFailOverlapSubdivision);
                                    visualizeConflict(player, playerData, claim, clickedBlock, true);
                                } else {
                                    GriefPrevention.sendMessage(player, TextMode.Err,
                                            Messages.ResizeFailOverlapSubdivision);
                                }
                            }
                        } else {
                            // Top-level claim: always allow starting a subdivision.
                            if (startSubdivisionCreationCancelled(player, clickedBlock, claim)) {
                                return true;
                            }
                            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionStart);
                            playerData.lastShovelLocation = clickedBlock.getLocation();
                            if (playerData.shovelMode == ShovelMode.Subdivide3D) {
                                BoundaryVisualization.visualizeArea(player, new BoundingBox(clickedBlock),
                                        VisualizationType.INITIALIZE_ZONE_3D, clickedBlock.getY());
                            } else {
                                BoundaryVisualization.visualizeArea(player, new BoundingBox(clickedBlock),
                                        VisualizationType.INITIALIZE_ZONE);
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
                            this.playerEventHandler.onPlayerInteract(event);
                            return true;
                        }

                        // Determine Y boundaries based on shovel mode
                        int y1 = playerData.lastShovelLocation.getBlockY();
                        int y2 = clickedBlock.getY();
                        int minY, maxY;

                        if (playerData.shovelMode == ShovelMode.Subdivide) {
                            if (playerData.claimSubdividing == null) {
                                GriefPrevention.sendMessage(player, TextMode.Err, "No claim selected for subdivision.");
                                return true;
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
                                GriefPrevention.sendMessage(player, TextMode.Err, "No claim selected for subdivision.");
                                return true;
                            }
                            minY = playerData.claimSubdividing.getLesserBoundaryCorner().getBlockY();
                            maxY = playerData.claimSubdividing.getGreaterBoundaryCorner().getBlockY();
                        }

                        // Enforce inner offset if nesting 3D subclaims is enabled
                        if (instance.config_claims_allowNestedSubClaims && playerData.claimSubdividing != null
                                && playerData.claimSubdividing.is3D()
                                && playerData.shovelMode == ShovelMode.Subdivide3D) {
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
                                    GriefPrevention.sendMessage(player, TextMode.Err,
                                            Messages.CreateSubdivisionOverlap);
                                    return true;
                                }

                                int subMinY = Math.min(minY, maxY);
                                int subMaxY = Math.max(minY, maxY);

                                if (subMinY < minAllowedY || subMaxY > maxAllowedY) {
                                    GriefPrevention.sendMessage(player, TextMode.Err,
                                            Messages.InnerSubdivisionTooClose);
                                    return true;
                                }
                            }
                        }

                        // Ensure 3D subclaims placed inside 2D parents - allow X/Z boundaries to match
                        // parent
                        if (playerData.shovelMode == ShovelMode.Subdivide3D
                                && playerData.claimSubdividing != null
                                && !playerData.claimSubdividing.is3D()) {
                            // No X/Z inset enforcement - allow subdivisions to share parent boundaries
                            // Only sibling collision checks remain
                            Claim parentClaim = playerData.claimSubdividing;
                            int proposedMinX = Math.min(playerData.lastShovelLocation.getBlockX(),
                                    clickedBlock.getX());
                            int proposedMaxX = Math.max(playerData.lastShovelLocation.getBlockX(),
                                    clickedBlock.getX());
                            int proposedMinZ = Math.min(playerData.lastShovelLocation.getBlockZ(),
                                    clickedBlock.getZ());
                            int proposedMaxZ = Math.max(playerData.lastShovelLocation.getBlockZ(),
                                    clickedBlock.getZ());

                            for (Claim sibling : parentClaim.children) {
                                if (!sibling.inDataStore || sibling.is3D())
                                    continue;

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
                                        GriefPrevention.sendMessage(player, TextMode.Err,
                                                Messages.InnerSubdivisionTooClose);
                                        return true;
                                    }

                                    int gapWest = siblingMinX - proposedMaxX;
                                    if (gapWest >= 0 && gapWest < 1) {
                                        GriefPrevention.sendMessage(player, TextMode.Err,
                                                Messages.InnerSubdivisionTooClose);
                                        return true;
                                    }
                                }

                                boolean xOverlap = proposedMinX <= siblingMaxX && proposedMaxX >= siblingMinX;
                                if (xOverlap) {
                                    int gapSouth = proposedMinZ - siblingMaxZ;
                                    if (gapSouth >= 0 && gapSouth < 1) {
                                        GriefPrevention.sendMessage(player, TextMode.Err,
                                                Messages.InnerSubdivisionTooClose);
                                        return true;
                                    }

                                    int gapNorth = siblingMinZ - proposedMaxZ;
                                    if (gapNorth >= 0 && gapNorth < 1) {
                                        GriefPrevention.sendMessage(player, TextMode.Err,
                                                Messages.InnerSubdivisionTooClose);
                                        return true;
                                    }
                                }
                            }
                        }

                        // try to create a new claim (will return null if this subdivision overlaps
                        // another)
                        CreateClaimResult subdivisionResult = this.dataStore.createClaim(
                                player.getWorld(),
                                playerData.lastShovelLocation.getBlockX(), clickedBlock.getX(),
                                minY, maxY,
                                playerData.lastShovelLocation.getBlockZ(), clickedBlock.getZ(),
                                null, // owner is not used for subdivisions
                                playerData.claimSubdividing,
                                null, player);

                        // if it didn't succeed, tell the player why
                        if (!subdivisionResult.succeeded || subdivisionResult.claim == null) {
                            if (subdivisionResult.claim != null) {
                                GriefPrevention.sendMessage(player, TextMode.Err,
                                        Messages.CreateSubdivisionOverlap);
                                visualizeConflict(player, playerData, subdivisionResult.claim, clickedBlock,
                                        subdivisionResult.claim.is3D());
                            } else {
                                GriefPrevention.sendMessage(player, TextMode.Err,
                                        Messages.CreateClaimFailOverlapRegion);
                            }

                            return true;
                        }

                        // otherwise, advise him on the /trust command and show him his new subdivision
                        else {
                            GriefPrevention.sendMessage(player, TextMode.Success, Messages.SubdivisionSuccess);
                            VisualizationType subdivisionViz = subdivisionResult.claim.is3D()
                                    ? VisualizationType.SUBDIVISION_3D
                                    : VisualizationType.SUBDIVISION;
                            BoundaryVisualization.visualizeClaim(player, subdivisionResult.claim, subdivisionViz,
                                    clickedBlock);
                            playerData.claimSubdividing = null;
                        }
                    }
                }

                // otherwise tell him he can't create a claim here, and show him the existing
                // claim
                // also advise him to consider /abandonclaim or resizing the existing claim
                else {
                    // Admin3D mode: allow starting a stacked 3D admin claim at a different Y level
                    if (playerData.shovelMode == ShovelMode.Admin3D && claim.is3D() && claim.isAdminClaim()
                            && playerData.lastShovelLocation == null) {
                        if (startClaimCreationCancelled(player, clickedBlock)) {
                            return true;
                        }
                        playerData.lastShovelLocation = clickedBlock.getLocation();
                        GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimStart);
                        BoundaryVisualization.visualizeArea(player, new BoundingBox(clickedBlock),
                                VisualizationType.INITIALIZE_ZONE_3D, clickedBlock.getY());
                        return true;
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

            return true;
        }

        // otherwise, the player isn't in an existing claim!

        // if he hasn't already start a claim with a previous shovel action
        Location lastShovelLocation = playerData.lastShovelLocation;
        GriefPrevention.AddLogEntry("[GP Debug] lastShovelLocation: " + (lastShovelLocation == null ? "null" : lastShovelLocation.getX() + "," + lastShovelLocation.getY() + "," + lastShovelLocation.getZ()), CustomLogEntryTypes.Debug, false);
        if (lastShovelLocation == null) {
            GriefPrevention.AddLogEntry("[GP Debug] Starting new claim (lastShovelLocation is null)", CustomLogEntryTypes.Debug, false);
            // if claims are not enabled in this world and it's not an administrative claim,
            // display an error message and stop
            if (!instance.claimsEnabledForWorld(player.getWorld())) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimsDisabledWorld);
                return true;
            }

            // if he's at the claim count per player limit already and doesn't have
            // permission to bypass, display an error message
            if (instance.config_claims_maxClaimsPerPlayer > 0 &&
                    !player.hasPermission("griefprevention.overrideclaimcountlimit") &&
                    playerData.getClaims().size() >= instance.config_claims_maxClaimsPerPlayer) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimCreationFailedOverClaimCountLimit);
                return true;
            }

            // remember it, and start him on the new claim
            if (startClaimCreationCancelled(player, clickedBlock)) {
                return true;
            }
            if (playerData.shovelMode == ShovelMode.Admin3D) {
                playerData.lastShovelLocation = clickedBlock.getLocation();
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimStart);
                BoundaryVisualization.visualizeArea(player, new BoundingBox(clickedBlock),
                        VisualizationType.INITIALIZE_ZONE_3D, clickedBlock.getY());
            } else {
                playerData.lastShovelLocation = clickedBlock.getLocation();
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimStart);
                BoundaryVisualization.visualizeArea(player, new BoundingBox(clickedBlock),
                        VisualizationType.INITIALIZE_ZONE);
            }
        }

        // otherwise, he's trying to finish creating a claim by setting the other
        // boundary corner
        else {
            GriefPrevention.AddLogEntry("[GP Debug] Entered else block - finishing claim", CustomLogEntryTypes.Debug, false);
            // if last shovel location was in a different world, assume the player is
            // starting the create-claim workflow over
            if (!lastShovelLocation.getWorld().equals(clickedBlock.getWorld())) {
                playerData.lastShovelLocation = null;
                this.playerEventHandler.onPlayerInteract(event);
                return true;
            }

            // apply pvp rule
            if (playerData.inPvpCombat()) {
                GriefPrevention.sendRateLimitedErrorMessage(player, Messages.NoClaimDuringPvP);
                return true;
            }

            // apply minimum claim dimensions rule
            int newWidth;
            int newHeight;

            GriefPrevention.AddLogEntry("[GP Debug] Calculating claim dimensions", CustomLogEntryTypes.Debug, false);
            try
            {
                newWidth = Math.abs(Math.subtractExact(lastShovelLocation.getBlockX(), clickedBlock.getX())) + 1;
                newHeight = Math.abs(Math.subtractExact(lastShovelLocation.getBlockZ(), clickedBlock.getZ())) + 1;
                GriefPrevention.AddLogEntry("[GP Debug] Calculated dimensions: " + newWidth + "x" + newHeight, CustomLogEntryTypes.Debug, false);
            }
            catch (ArithmeticException e)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimInsufficientBlocks, String.valueOf(Integer.MAX_VALUE));
                playerData.lastShovelLocation = null;
                playerData.claimSubdividing = null;
                return true;
            }

            if (playerData.shovelMode != ShovelMode.Admin && playerData.shovelMode != ShovelMode.Admin3D) {
                if (newWidth < instance.config_claims_minWidth
                        || newHeight < instance.config_claims_minWidth) {
                    // this IF block is a workaround for craftbukkit bug which fires two events for
                    // one interaction
                    if (newWidth != 1 && newHeight != 1) {
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.NewClaimTooNarrow,
                                String.valueOf(instance.config_claims_minWidth));
                    }
                    return true;
                }

                int newArea = newWidth * newHeight;
                if (newArea < instance.config_claims_minArea) {
                    if (newArea != 1) {
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeClaimInsufficientArea,
                                String.valueOf(instance.config_claims_minArea));
                    }
                    return true;
                }
            }

            UUID playerID = player.getUniqueId();

            // if not an administrative claim, verify the player has enough claim blocks for
            // this new claim
            if (playerData.shovelMode != ShovelMode.Admin && playerData.shovelMode != ShovelMode.Admin3D) {
                int newClaimArea = newWidth * newHeight;
                int remainingBlocks = playerData.getRemainingClaimBlocks();
                if (newClaimArea > remainingBlocks) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimInsufficientBlocks,
                            String.valueOf(newClaimArea - remainingBlocks));
                    instance.dataStore.tryAdvertiseAdminAlternatives(player);
                    return true;
                }
            } else {
                playerID = null;
            }

            boolean is3dAdminClaim = playerData.shovelMode == ShovelMode.Admin3D;

            // try to create a new claim
            CreateClaimResult result = this.dataStore.createClaim(
                    player.getWorld(),
                    lastShovelLocation.getBlockX(), clickedBlock.getX(),
                    is3dAdminClaim ? lastShovelLocation.getBlockY() : lastShovelLocation.getBlockY() - instance.config_claims_claimsExtendIntoGroundDistance,
                    is3dAdminClaim ? clickedBlock.getY() : clickedBlock.getY() - instance.config_claims_claimsExtendIntoGroundDistance,
                    lastShovelLocation.getBlockZ(), clickedBlock.getZ(),
                    playerID,
                    null, null,
                    player,
                    is3dAdminClaim);

            // if it didn't succeed, tell the player why
            if (!result.succeeded || result.claim == null) {
                if (result.claim != null) {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);
                    visualizeConflict(player, playerData, result.claim, clickedBlock, result.claim.is3D());
                } else {
                    GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
                }

                return true;
            }

            // otherwise, advise him on the /trust command and show him his new claim
            else {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.CreateClaimSuccess);
                VisualizationType vizType = is3dAdminClaim ? VisualizationType.ADMIN_CLAIM_3D : VisualizationType.CLAIM;
                BoundaryVisualization.visualizeClaim(player, result.claim, vizType, clickedBlock);
                playerData.lastShovelLocation = null;

                // if it's a big claim, tell the player about subdivisions
                if (!player.hasPermission("griefprevention.adminclaims") && result.claim.getArea() >= 1000) {
                    GriefPrevention.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
                    GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, 201L,
                            DataStore.SUBDIVISION_VIDEO_URL);
                }

                AutoExtendClaimTask.scheduleAsync(result.claim);
            }
        }

        return true;
    }

    // ----------------------------------------------------------------------------------------
    // Helper methods
    // ----------------------------------------------------------------------------------------

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
        Vector clickedCenter = clickedBlock.getLocation().toVector().add(new Vector(0.5, 0.5, 0.5));
        double clickedT = clickedCenter.subtract(eyePos).dot(dir);
        return clickedT < 0 || cornerHit.t <= clickedT + 2.0;
    }

    private boolean tryResizeShapedClaim(@NotNull Player player, @NotNull PlayerData playerData, @NotNull Block clickedBlock)
    {
        Claim claim = playerData.claimResizing;
        if (claim == null || !claim.inDataStore || !claim.isShaped() || claim.is3D())
        {
            return false;
        }

        // Don't try to resize if in shaped mode - this is for boundary marker operations
        if (playerData.shovelMode == ShovelMode.Shaped)
        {
            return false;
        }

        Location anchor = playerData.lastShovelLocation;
        if (anchor == null)
        {
            return false;
        }

        int cornerIndex = claim.getCornerIndexAt(anchor.getBlockX(), anchor.getBlockZ());
        if (cornerIndex < 0)
        {
            return false;
        }

        OrthogonalPolygonValidationResult moveResult = resolveShapedResizeMove(
                player,
                claim,
                claim.getBoundaryPolygon(),
                cornerIndex,
                new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ()));
        if (!moveResult.isValid() || moveResult.polygon() == null)
        {
            boolean selfIntersection = moveResult.issues().stream()
                    .anyMatch(issue -> issue.type() == OrthogonalPolygonValidationIssueType.SELF_INTERSECTION);
            GriefPrevention.sendMessage(player, TextMode.Err,
                    selfIntersection ? "That corner move would intersect the claim." : "That corner can't move there.");
            return true;
        }

        CreateClaimResult updateResult = this.dataStore.updateShapedClaim(player, playerData, claim, moveResult.polygon());
        if (!updateResult.succeeded || updateResult.claim == null)
        {
            if (updateResult.denialMessage != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, updateResult.denialMessage.get());
            }
            else if (updateResult.claim != null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlap);
                BoundaryVisualization.visualizeClaim(player, updateResult.claim, VisualizationType.CONFLICT_ZONE);
            }
            else
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapRegion);
            }
            return true;
        }

        int claimBlocksRemaining = 0;
        if (!updateResult.claim.isAdminClaim())
        {
            UUID ownerID = updateResult.claim.ownerID;
            if (ownerID != null)
            {
                if (ownerID.equals(player.getUniqueId()))
                {
                    claimBlocksRemaining = playerData.getRemainingClaimBlocks();
                }
                else
                {
                    PlayerData ownerData = this.dataStore.getPlayerData(ownerID);
                    claimBlocksRemaining = ownerData.getRemainingClaimBlocks();
                    OfflinePlayer owner = GriefPrevention.instance.getServer().getOfflinePlayer(ownerID);
                    if (!owner.isOnline())
                    {
                        this.dataStore.clearCachedPlayerData(ownerID);
                    }
                }
            }
        }

        GriefPrevention.sendMessage(player, TextMode.Success, Messages.ClaimResizeSuccess,
                String.valueOf(claimBlocksRemaining));
        BoundaryVisualization.visualizeClaim(player, updateResult.claim,
                updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
                clickedBlock);
        playerData.claimResizing = null;
        playerData.lastShovelLocation = null;
        return true;
    }

    private @NotNull OrthogonalPolygonValidationResult resolveShapedResizeMove(
            @NotNull Player player,
            @NotNull Claim claim,
            @NotNull OrthogonalPolygon polygon,
            int cornerIndex,
            @NotNull OrthogonalPoint2i target)
    {
        OrthogonalPoint2i anchor = polygon.corners().get(cornerIndex);
        int deltaX = target.x() - anchor.x();
        int deltaZ = target.z() - anchor.z();

        if (deltaX == 0 && deltaZ == 0)
        {
            return polygon.moveCorner(cornerIndex, anchor);
        }

        // Dragging a corner onto another vertex of the same claim (flattening a nib / collinear bump).
        // Must use moveCorner: face-run logic uses expandEdge/moveEdgeRun and (a) locateResizeSubsegment only
        // matches strictly interior edge points, so exact corner clicks miss, and (b) moveEdgeRun can reject
        // or distort a merge that moveCorner + normalization already handles.
        for (int i = 0; i < polygon.corners().size(); i++)
        {
            if (i != cornerIndex && polygon.corners().get(i).equals(target))
            {
                return polygon.moveCorner(cornerIndex, target);
            }
        }

        if (deltaX != 0 && deltaZ != 0)
        {
            if (polygon.isRemovableNode(cornerIndex) || isConcaveCorner(polygon, cornerIndex))
            {
                OrthogonalPolygonValidationResult nibResult = resolveShapedNibResizeMove(player, claim, polygon, cornerIndex, target);
                if (isChangedShapedResize(polygon, nibResult))
                {
                    return nibResult;
                }
            }
            return polygon.moveCorner(cornerIndex, target);
        }

        FaceRun faceRun = findStraightFaceRun(polygon, cornerIndex, deltaX != 0);
        if (faceRun == null)
        {
            return polygon.moveCorner(cornerIndex, target);
        }

        Integer selectedEdgeIndex = selectResizeSubsegment(player, polygon, faceRun, target);
        int amount = deltaX != 0 ? deltaX : deltaZ;
        if (selectedEdgeIndex != null)
        {
            return polygon.expandEdge(selectedEdgeIndex, amount);
        }

        if (polygon.isRemovableNode(cornerIndex) || isConcaveCorner(polygon, cornerIndex))
        {
            OrthogonalPolygonValidationResult orthogonalNibResult = resolveOrthogonalShapedNibResizeMove(
                    player,
                    claim,
                    polygon,
                    cornerIndex,
                    target);
            if (orthogonalNibResult != null && isChangedShapedResize(polygon, orthogonalNibResult))
            {
                return orthogonalNibResult;
            }
        }

        // If no subsegment selected but target equals a corner on the face run, use moveCorner
        // to merge/flatten the nib (e.g., dragging (4,4) onto (8,4) on a Z=4 horizontal face)
        if (isTargetCornerOnFaceRun(polygon, faceRun, target))
        {
            return polygon.moveCorner(cornerIndex, target);
        }

        return polygon.moveEdgeRun(faceRun.startEdgeIndex(), faceRun.endEdgeIndex(), amount);
    }

    private boolean isChangedShapedResize(
            @NotNull OrthogonalPolygon original,
            @NotNull OrthogonalPolygonValidationResult result)
    {
        return result.isValid()
                && result.polygon() != null
                && !result.polygon().corners().equals(original.corners());
    }

    private boolean isTargetCornerOnFaceRun(
            @NotNull OrthogonalPolygon polygon,
            @NotNull FaceRun faceRun,
            @NotNull OrthogonalPoint2i target)
    {
        int edgeIndex = faceRun.startEdgeIndex();
        while (true)
        {
            OrthogonalEdge2i edge = polygon.edges().get(edgeIndex);
            // Check both endpoints of this edge
            if (edge.start().equals(target) || edge.end().equals(target))
            {
                return true;
            }

            if (edgeIndex == faceRun.endEdgeIndex())
            {
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
            @NotNull OrthogonalPoint2i target)
    {
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
                        new OrthogonalPoint2i(target.x(), anchor.z()))
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
                        new OrthogonalPoint2i(anchor.x(), target.z()))
        );

        ShapedNibResizeAttempt bestAttempt = selectBestShapedNibResizeAttempt(attempts);
        if (bestAttempt != null)
        {
            return bestAttempt.result();
        }

        return polygon.moveCorner(cornerIndex, target);
    }

    private boolean isConcaveCorner(@NotNull OrthogonalPolygon polygon, int cornerIndex)
    {
        List<OrthogonalPoint2i> corners = polygon.corners();
        if (cornerIndex < 0 || cornerIndex >= corners.size() || corners.size() < 4)
        {
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
        if (crossSign == 0)
        {
            return false;
        }

        int winding = polygonWindingSign(polygon.corners());
        return winding != 0 && crossSign != winding;
    }

    private int polygonWindingSign(@NotNull List<OrthogonalPoint2i> corners)
    {
        long area2 = 0L;
        for (int i = 0; i < corners.size(); i++)
        {
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
            @NotNull OrthogonalPoint2i target)
    {
        OrthogonalPoint2i anchor = polygon.corners().get(cornerIndex);
        int deltaX = target.x() - anchor.x();
        int deltaZ = target.z() - anchor.z();
        if ((deltaX == 0) == (deltaZ == 0))
        {
            return null;
        }

        OrthogonalPoint2i secondOutside;
        OrthogonalPoint2i reconnectProbe;
        if (deltaX == 0)
        {
            int playerX = player.getLocation().getBlockX();
            if (playerX == anchor.x())
            {
                return null;
            }

            secondOutside = new OrthogonalPoint2i(playerX, target.z());
            reconnectProbe = new OrthogonalPoint2i(playerX, anchor.z());
        }
        else
        {
            int playerZ = player.getLocation().getBlockZ();
            if (playerZ == anchor.z())
            {
                return null;
            }

            secondOutside = new OrthogonalPoint2i(target.x(), playerZ);
            reconnectProbe = new OrthogonalPoint2i(anchor.x(), playerZ);
        }

        if (secondOutside.equals(anchor) || secondOutside.equals(target) || reconnectProbe.equals(secondOutside))
        {
            return null;
        }

        ClaimEditorSession session = ClaimEditorSession.idle(player.getUniqueId())
                .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL)
                .withTarget(new ClaimEditTarget(ClaimEditTargetType.EXISTING_PARENT_CLAIM, claim.getID()))
                .withPreview(new ClaimEditPreview(polygon, null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()))
                .withOpenPath(new com.griefprevention.claims.editor.ShapedPathDraft(claim.getID(), Collections.singletonList(anchor), null, false));

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
        if (!first.success())
        {
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
        if (!second.success())
        {
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
        if (!merged.success() || merged.preview().polygon() == null)
        {
            return null;
        }

        OrthogonalPolygonValidationResult result = OrthogonalPolygon.validatePath(merged.preview().polygon().closedPath());
        if (!result.isValid() || result.polygon() == null || !polygonContainsPolygon(result.polygon(), polygon))
        {
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
            @NotNull OrthogonalPoint2i reconnect)
    {
        if (firstStep.equals(anchor)
                || firstStep.equals(target)
                || reconnect.equals(anchor)
                || reconnect.equals(firstStep)
                || reconnect.equals(target))
        {
            return null;
        }

        ClaimEditorSession session = ClaimEditorSession.idle(player.getUniqueId())
                .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL)
                .withTarget(new ClaimEditTarget(ClaimEditTargetType.EXISTING_PARENT_CLAIM, claim.getID()))
                .withPreview(new ClaimEditPreview(polygon, null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()))
                .withOpenPath(new com.griefprevention.claims.editor.ShapedPathDraft(
                        claim.getID(),
                        Arrays.asList(anchor, firstStep),
                        null,
                        false));

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
        if (!second.success())
        {
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
        if (!merged.success() || merged.preview().polygon() == null)
        {
            return null;
        }

        return OrthogonalPolygon.validatePath(merged.preview().polygon().closedPath());
    }

    private void addShapedNibResizeAttempt(
            @NotNull List<ShapedNibResizeAttempt> attempts,
            @NotNull OrthogonalPolygon originalPolygon,
            @Nullable OrthogonalPolygonValidationResult result)
    {
        if (result == null || !isChangedShapedResize(originalPolygon, result))
        {
            return;
        }

        attempts.add(new ShapedNibResizeAttempt(
                result,
                polygonContainsPolygon(result.polygon(), originalPolygon),
                polygonArea(result.polygon()),
                polygonOverlapArea(originalPolygon, result.polygon())
        ));
    }

    private @Nullable ShapedNibResizeAttempt selectBestShapedNibResizeAttempt(
            @NotNull List<ShapedNibResizeAttempt> attempts)
    {
        ShapedNibResizeAttempt best = null;
        for (ShapedNibResizeAttempt attempt : attempts)
        {
            if (!attempt.containsOriginal())
            {
                continue;
            }

            if (best == null || compareShapedNibResizeAttempts(attempt, best) < 0)
            {
                best = attempt;
            }
        }

        return best;
    }

    private int compareShapedNibResizeAttempts(
            @NotNull ShapedNibResizeAttempt first,
            @NotNull ShapedNibResizeAttempt second)
    {
        if (first.area() != second.area())
        {
            return Integer.compare(second.area(), first.area());
        }

        if (first.overlap() != second.overlap())
        {
            return Integer.compare(second.overlap(), first.overlap());
        }

        return 0;
    }

    private boolean polygonContains(@NotNull OrthogonalPolygon polygon, @NotNull OrthogonalPoint2i point)
    {
        if (polygon.corners().contains(point))
        {
            return true;
        }

        if (!polygon.edgeIndexesContainingInteriorPoint(point).isEmpty())
        {
            return true;
        }

        double sampleX = point.x() + 0.5D;
        double sampleZ = point.z() + 0.5D;
        boolean inside = false;
        List<OrthogonalPoint2i> corners = polygon.corners();
        for (int i = 0, j = corners.size() - 1; i < corners.size(); j = i++)
        {
            OrthogonalPoint2i a = corners.get(i);
            OrthogonalPoint2i b = corners.get(j);
            boolean crosses = (a.z() > sampleZ) != (b.z() > sampleZ);
            if (!crosses)
            {
                continue;
            }

            double intersectionX = (double) (b.x() - a.x()) * (sampleZ - a.z()) / (double) (b.z() - a.z()) + a.x();
            if (sampleX < intersectionX)
            {
                inside = !inside;
            }
        }

        return inside;
    }

    private int polygonArea(@NotNull OrthogonalPolygon polygon)
    {
        int area = 0;
        for (int x = polygon.minX(); x <= polygon.maxX(); x++)
        {
            for (int z = polygon.minZ(); z <= polygon.maxZ(); z++)
            {
                if (polygonContains(polygon, new OrthogonalPoint2i(x, z)))
                {
                    area++;
                }
            }
        }

        return area;
    }

    private boolean polygonContainsPolygon(@NotNull OrthogonalPolygon container, @NotNull OrthogonalPolygon contents)
    {
        for (int x = contents.minX(); x <= contents.maxX(); x++)
        {
            for (int z = contents.minZ(); z <= contents.maxZ(); z++)
            {
                OrthogonalPoint2i point = new OrthogonalPoint2i(x, z);
                if (polygonContains(contents, point) && !polygonContains(container, point))
                {
                    return false;
                }
            }
        }

        return true;
    }

    private int polygonOverlapArea(@NotNull OrthogonalPolygon first, @NotNull OrthogonalPolygon second)
    {
        int area = 0;
        int minX = Math.max(first.minX(), second.minX());
        int maxX = Math.min(first.maxX(), second.maxX());
        int minZ = Math.max(first.minZ(), second.minZ());
        int maxZ = Math.min(first.maxZ(), second.maxZ());

        for (int x = minX; x <= maxX; x++)
        {
            for (int z = minZ; z <= maxZ; z++)
            {
                OrthogonalPoint2i point = new OrthogonalPoint2i(x, z);
                if (polygonContains(first, point) && polygonContains(second, point))
                {
                    area++;
                }
            }
        }

        return area;
    }

    private static final class ShapedNibResizeAttempt
    {
        private final @NotNull OrthogonalPolygonValidationResult result;
        private final boolean containsOriginal;
        private final int area;
        private final int overlap;

        private ShapedNibResizeAttempt(
                @NotNull OrthogonalPolygonValidationResult result,
                boolean containsOriginal,
                int area,
                int overlap)
        {
            this.result = result;
            this.containsOriginal = containsOriginal;
            this.area = area;
            this.overlap = overlap;
        }

        @NotNull OrthogonalPolygonValidationResult result()
        {
            return result;
        }

        boolean containsOriginal()
        {
            return containsOriginal;
        }

        int area()
        {
            return area;
        }

        int overlap()
        {
            return overlap;
        }
    }

    private @Nullable FaceRun findStraightFaceRun(
            @NotNull OrthogonalPolygon polygon,
            int cornerIndex,
            boolean movingVerticalFace)
    {
        int edgeCount = polygon.edges().size();
        int previousEdgeIndex = Math.floorMod(cornerIndex - 1, edgeCount);
        int nextEdgeIndex = cornerIndex % edgeCount;
        Integer anchorEdgeIndex = null;

        OrthogonalEdge2i previousEdge = polygon.edges().get(previousEdgeIndex);
        if ((movingVerticalFace && previousEdge.isVertical()) || (!movingVerticalFace && previousEdge.isHorizontal()))
        {
            anchorEdgeIndex = previousEdgeIndex;
        }

        OrthogonalEdge2i nextEdge = polygon.edges().get(nextEdgeIndex);
        if ((movingVerticalFace && nextEdge.isVertical()) || (!movingVerticalFace && nextEdge.isHorizontal()))
        {
            anchorEdgeIndex = nextEdgeIndex;
        }

        if (anchorEdgeIndex == null)
        {
            return null;
        }

        OrthogonalEdge2i referenceEdge = polygon.edges().get(anchorEdgeIndex);
        boolean horizontal = referenceEdge.isHorizontal();
        int coordinate = horizontal ? referenceEdge.start().z() : referenceEdge.start().x();

        int startEdgeIndex = anchorEdgeIndex;
        while (true)
        {
            int previousIndex = Math.floorMod(startEdgeIndex - 1, edgeCount);
            if (previousIndex == anchorEdgeIndex)
            {
                break;
            }

            OrthogonalEdge2i edge = polygon.edges().get(previousIndex);
            if (!isSameStraightFace(edge, horizontal, coordinate))
            {
                break;
            }

            startEdgeIndex = previousIndex;
        }

        int endEdgeIndex = anchorEdgeIndex;
        while (true)
        {
            int nextIndex = (endEdgeIndex + 1) % edgeCount;
            if (nextIndex == startEdgeIndex)
            {
                break;
            }

            OrthogonalEdge2i edge = polygon.edges().get(nextIndex);
            if (!isSameStraightFace(edge, horizontal, coordinate))
            {
                break;
            }

            endEdgeIndex = nextIndex;
        }

        return new FaceRun(startEdgeIndex, endEdgeIndex, horizontal);
    }

    private boolean isSameStraightFace(@NotNull OrthogonalEdge2i edge, boolean horizontal, int coordinate)
    {
        if (horizontal)
        {
            return edge.isHorizontal() && edge.start().z() == coordinate;
        }

        return edge.isVertical() && edge.start().x() == coordinate;
    }

    private @Nullable Integer selectResizeSubsegment(
            @NotNull Player player,
            @NotNull OrthogonalPolygon polygon,
            @NotNull FaceRun faceRun,
            @NotNull OrthogonalPoint2i target)
    {
        int targetCoordinate = faceRun.horizontal() ? target.x() : target.z();
        Integer targetEdgeIndex = locateResizeSubsegment(polygon, faceRun, targetCoordinate);
        if (targetEdgeIndex != null)
        {
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
            int tangentCoordinate)
    {
        int edgeIndex = faceRun.startEdgeIndex();
        while (true)
        {
            OrthogonalEdge2i edge = polygon.edges().get(edgeIndex);
            int min = faceRun.horizontal() ? edge.minX() : edge.minZ();
            int max = faceRun.horizontal() ? edge.maxX() : edge.maxZ();
            if (tangentCoordinate > min && tangentCoordinate < max)
            {
                return edgeIndex;
            }

            if (edgeIndex == faceRun.endEdgeIndex())
            {
                break;
            }

            edgeIndex = (edgeIndex + 1) % polygon.edges().size();
        }

        return null;
    }

    private static final class FaceRun
    {
        private final int startEdgeIndex;
        private final int endEdgeIndex;
        private final boolean horizontal;

        private FaceRun(int startEdgeIndex, int endEdgeIndex, boolean horizontal)
        {
            this.startEdgeIndex = startEdgeIndex;
            this.endEdgeIndex = endEdgeIndex;
            this.horizontal = horizontal;
        }

        int startEdgeIndex()
        {
            return startEdgeIndex;
        }

        int endEdgeIndex()
        {
            return endEdgeIndex;
        }

        boolean horizontal()
        {
            return horizontal;
        }
    }

    private boolean handleShapedResizeInteraction(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Block clickedBlock)
    {
        if (playerData.claimResizing != null && playerData.claimResizing.inDataStore) {
            if (clickedBlock.getLocation().equals(playerData.lastShovelLocation)) {
                return true;
            }

            if (tryResizeShapedClaim(player, playerData, clickedBlock)) {
                return true;
            }

            int newx1, newx2, newz1, newz2, newy1, newy2;
            if (playerData.lastShovelLocation.getBlockX() == playerData.claimResizing.getLesserBoundaryCorner()
                    .getBlockX()) {
                newx1 = clickedBlock.getX();
                newx2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockX();
            } else {
                newx1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockX();
                newx2 = clickedBlock.getX();
            }

            if (playerData.lastShovelLocation.getBlockZ() == playerData.claimResizing.getLesserBoundaryCorner()
                    .getBlockZ()) {
                newz1 = clickedBlock.getZ();
                newz2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockZ();
            } else {
                newz1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockZ();
                newz2 = clickedBlock.getZ();
            }

            if (playerData.claimResizing.is3D()) {
                int currentMinY = playerData.claimResizing.getLesserBoundaryCorner().getBlockY();
                int currentMaxY = playerData.claimResizing.getGreaterBoundaryCorner().getBlockY();
                int startY = playerData.lastShovelLocation.getBlockY();
                int endY = clickedBlock.getY();
                boolean isSingleLayer = (currentMinY == currentMaxY);
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
                        loadClaimIntoShapedSession(playerData.getClaimEditorSession().withOpenPath(null), clickedClaim));
            } else {
                return false;
            }
        }

        // Don't intercept shaped mode — let handleShapedModeInteraction process it
        if (playerData.shovelMode == ShovelMode.Shaped || playerData.shovelMode == ShovelMode.ShapedSubdivide) {
            return false;
        }

        startClaimResizeSelection(player, playerData, clickedClaim, clickedBlock);
        return true;
    }

    private boolean canStartResizeFromOpenPath(
            @NotNull ClaimEditorSession session,
            @NotNull Claim claim,
            @NotNull Block clickedBlock)
    {
        if (session.activeTarget() == null
                || session.activeTarget().claimId() == null
                || !session.activeTarget().claimId().equals(claim.getID())
                || session.openPath() == null
                || session.openPath().points().size() != 1) {
            return false;
        }

        OrthogonalPoint2i onlyPoint = session.openPath().points().get(0);
        return onlyPoint.x() == clickedBlock.getX() && onlyPoint.z() == clickedBlock.getZ();
    }

    private void startClaimResizeSelection(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim claim,
            @NotNull Block clickedBlock)
    {
        Claim selection = claim;
        while (selection.parent != null) {
            Claim parent = selection.parent;
            boolean parentContains = parent.contains(clickedBlock.getLocation(), true, false);
            if (parentContains && isCornerMatch(parent, clickedBlock)) {
                selection = parent;
            } else {
                break;
            }
        }

        StartClaimResizeEvent event = new StartClaimResizeEvent(player, selection, clickedBlock);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
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

    private boolean startClaimCreationCancelled(@NotNull Player player, @NotNull Block clickedBlock)
    {
        StartClaimCreationEvent event = new StartClaimCreationEvent(player, clickedBlock);
        Bukkit.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean startSubdivisionCreationCancelled(
            @NotNull Player player,
            @NotNull Block clickedBlock,
            @NotNull Claim parent)
    {
        StartSubdivideClaimCreationEvent event = new StartSubdivideClaimCreationEvent(player, clickedBlock, parent);
        Bukkit.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private Messages claimResizeStartMessage(@NotNull Claim selection)
    {
        if (!instance.config_claims_useClaimSelectSessions || !instance.config_claims_useClaimSelectedMessages) {
            return Messages.ResizeStart;
        }

        if (selection.parent != null) {
            return Messages.SubdivisionSelected;
        }

        boolean hasNoChildren = selection.children == null || selection.children.isEmpty();
        return hasNoChildren ? Messages.ClaimSelected : Messages.ClaimSelectedTopLevel;
    }

    private boolean trySelectShapedBoundarySegmentBasicMode(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim deepestClaim,
            @NotNull Block clickedBlock)
    {
        Claim claim = deepestClaim;
        while (claim != null && claim.parent != null)
        {
            claim = claim.parent;
        }
        if (claim == null || !claim.isShaped() || claim.is3D())
        {
            return false;
        }
        Supplier<String> noEdit = claim.checkPermission(player, ClaimPermission.Edit, null);
        if (noEdit != null)
        {
            return false;
        }

        OrthogonalPoint2i point = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
        if (claim.getCornerIndexAt(point.x(), point.z()) >= 0)
        {
            return false;
        }

        OrthogonalPolygon polygon = claim.getBoundaryPolygon();
        List<Integer> matches = polygon.edgeIndexesContainingInteriorPoint(point);
        if (matches.size() != 1)
        {
            return false;
        }

        ClaimEditorSession session = ClaimEditorSession.idle(player.getUniqueId())
                .withMode(ClaimEditorMode.SHAPED, ClaimEditSource.TOOL);
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

        if (!result.success())
        {
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
                clickedBlock);
        return true;
    }

    private void handleMergeModeInteraction(@NotNull Player player, @NotNull PlayerData playerData, @NotNull Block clickedBlock) {
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
                GriefPrevention.sendMessage(player, TextMode.Err, "Click on a claim boundary edge to select it for merging.");
                return;
            }
            playerData.mergeEdgeIndex = edgeIndex;
            playerData.mergeFirstDepthPoint = edgeIndex == null
                    ? null
                    : resolveMergeDepthPoint(player, clickedClaim, clickedBlock);
            playerData.mergeSecondEdgeIndex = null;
            playerData.mergeSecondDepthPoint = null;

            playerData.claimMerging = clickedClaim;
            BoundaryVisualization.visualizeClaim(player, clickedClaim,
                    clickedClaim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM);
            Set<Claim> nearbyClaims = this.dataStore.getNearbyClaims(player.getLocation());
            if (!nearbyClaims.isEmpty()) {
                BoundaryVisualization.mergeNearbyClaims(player, nearbyClaims);
            }
            GriefPrevention.sendMessage(player, TextMode.Instr, "First claim selected. Right click the second claim to finish the merge.");
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
            GriefPrevention.sendMessage(player, TextMode.Err, "Click on a claim boundary edge to select it for merging.");
            return;
        }
        playerData.mergeSecondEdgeIndex = secondEdgeIndex;
        playerData.mergeSecondDepthPoint = secondEdgeIndex == null
                ? null
                : resolveMergeDepthPoint(player, clickedClaim, clickedBlock);

        // Perform the merge
        this.dataStore.mergeClaims(player, playerData, playerData.claimMerging, clickedClaim, playerData.mergeEdgeIndex);
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

    private void handleShapedModeInteraction(@NotNull Player player, @NotNull PlayerData playerData, @NotNull Block clickedBlock) {
        ClaimEditorSession session = playerData.getClaimEditorSession();
        GriefPrevention.AddLogEntry("[GP Debug] handleShapedModeInteraction: mode=" + session.mode() + " activeTarget=" + session.activeTarget() + " openPath=" + session.openPath() + " click=" + clickedBlock.getX() + "," + clickedBlock.getY() + "," + clickedBlock.getZ(), CustomLogEntryTypes.Debug, false);
        if (session.mode() != com.griefprevention.claims.editor.ClaimEditorMode.SHAPED) {
            session = session.withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL);
        }
        if (session.activeTarget() != null
                && session.activeTarget().type() == ClaimEditTargetType.EXISTING_PARENT_CLAIM
                && session.openPath() != null
                && session.activeTarget().claimId() != null) {
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
                        GriefPrevention.sendMessage(player, TextMode.Err, "Finish this reshape path before editing a different claim.");
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
                            GriefPrevention.sendMessage(player, TextMode.Success, "Merge target marked. Continue shaping to connect claims.");
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

                // Cut path detection: when there's an active openPath starting from the boundary,
                // allow interior clicks to build a cut path and detect closure back to the start.
                OrthogonalPoint2i clickedCutPoint = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());
                OrthogonalPoint2i cutFirstPoint = session.openPath().points().get(0);
                final Claim cutTargetClaim = targetClaim;
                if (isBoundaryPoint(cutTargetClaim.getBoundaryPolygon(), cutFirstPoint)) {
                    boolean isInteriorCutClick = !isBoundaryPoint(cutTargetClaim.getBoundaryPolygon(), clickedCutPoint)
                            && cutTargetClaim.getBoundaryPolygon().containsCell(clickedCutPoint.x(), clickedCutPoint.z());
                    boolean hasCutInteriorPoints = session.openPath().points().stream()
                            .skip(1)
                            .anyMatch(p -> !isBoundaryPoint(cutTargetClaim.getBoundaryPolygon(), p)
                                    && cutTargetClaim.getBoundaryPolygon().containsCell(p.x(), p.z()));
                    // Closing the cut path: click back at the start point OR click any boundary point
                    // while the path has interior points
                    if (hasCutInteriorPoints
                            && (clickedCutPoint.equals(cutFirstPoint)
                                || isBoundaryPoint(cutTargetClaim.getBoundaryPolygon(), clickedCutPoint))) {
                        // Add the boundary closure point if it's not the start
                        if (!clickedCutPoint.equals(cutFirstPoint)) {
                            ShapedPathDraft draft = session.openPath();
                            List<OrthogonalPoint2i> points = new ArrayList<>(draft.points());
                            points.add(clickedCutPoint);
                            ShapedPathDraft closedDraft = new ShapedPathDraft(draft.claimId(), points, null, true);
                            session = session.withOpenPath(closedDraft);
                        }
                        finalizeCutPath(player, playerData, cutTargetClaim, session, clickedBlock);
                        return;
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
                        OrthogonalPolygon basePolygon = cutTargetClaim.getBoundaryPolygon();
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

                // Check if we're clicking back on a claim that has cross-claim boundary markers
                // This indicates a merge operation in progress
                if (playerData.hasCrossClaimBoundaryMarker(targetClaim.getID())) {
                    // This is a merge operation - show appropriate message
                    GriefPrevention.sendMessage(player, TextMode.Instr, "Merge in progress. Continue shaping to connect claims.");
                }

                ClaimEditorSession targetSession = loadClaimIntoShapedSession(playerData.getClaimEditorSession(), targetClaim);
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
                    if (result.preview().polygon() != null
                            && result.session().openPath() != null
                            && result.session().openPath().closureReady()) {
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
        GriefPrevention.AddLogEntry("[GP Debug] handleShapedModeInteraction: claim=" + (claim != null ? claim.getID() : "null") + " is3D=" + (claim != null && claim.is3D()), CustomLogEntryTypes.Debug, false);

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
                    if (result.preview().polygon() != null
                            && result.session().openPath() != null
                            && result.session().openPath().closureReady()) {
                        finalizeExistingClaimReshape(player, playerData, targetClaim, result, clickedBlock);
                    } else {
                        visualizeShapedEditState(player, result.session(), clickedBlock.getY());
                    }
                }
                return;
            }

            OrthogonalPoint2i clickedPoint = new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ());

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
        GriefPrevention.AddLogEntry("[GP Debug] handleShapedModeInteraction: clickedPoint=" + clickedPoint + " isBoundary=" + isBoundaryPoint(claim.getBoundaryPolygon(), clickedPoint) + " containsCell=" + claim.getBoundaryPolygon().containsCell(clickedPoint.x(), clickedPoint.z()) + " isInteriorClick=" + isInteriorClick, CustomLogEntryTypes.Debug, false);

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
                        && cutClaim.getBoundaryPolygon().containsCell(clickedPoint.x(), clickedPoint.z());
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

        if (isCornerMatch(claim, clickedBlock) && session.openPath() == null && !player.isSneaking() && playerData.shovelMode != ShovelMode.Shaped) {
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
            if (result.preview().polygon() != null
                    && result.session().openPath() != null
                    && result.session().openPath().closureReady()) {
                finalizeExistingClaimReshape(player, playerData, claim, result, clickedBlock);
            } else {
                visualizeShapedEditState(player, result.session(), clickedBlock.getY());
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Shaped subdivisions
    // ----------------------------------------------------------------------------------------

    private void resetToBasicMode(@NotNull PlayerData playerData)
    {
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
    }

    /**
     * Shaped subdivision mode. Clicking an existing shaped subdivision's boundary starts a reshape
     * of that subdivision; clicking anywhere else inside a claim the player can edit starts a
     * free-form subdivision outline that closes when the player clicks the first corner again.
     */
    private void handleShapedSubdivideModeInteraction(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Block clickedBlock)
    {
        ClaimEditorSession session = playerData.getClaimEditorSession();
        if (session.mode() != ClaimEditorMode.SHAPED) {
            session = session.withMode(ClaimEditorMode.SHAPED, ClaimEditSource.TOOL);
            playerData.setClaimEditorSession(session);
        }

        ClaimEditTarget target = session.activeTarget();

        // Mid-reshape of an existing shaped subdivision: keep feeding corners into the open path.
        if (target != null
                && target.type() == ClaimEditTargetType.EXISTING_SUBDIVISION_CLAIM
                && target.claimId() != null
                && session.openPath() != null) {
            Claim subdivision = this.dataStore.getClaim(target.claimId());
            if (subdivision != null && subdivision.inDataStore && subdivision.isShapedSubdivision()) {
                reshapeShapedSubdivision(player, playerData, subdivision, clickedBlock);
                return;
            }

            clearShapedSubdivisionSession(playerData);
            target = null;
        }

        // Mid-draw of a new subdivision: every further corner belongs to the same parent claim.
        if (target != null
                && target.type() == ClaimEditTargetType.NEW_SUBDIVISION_CLAIM
                && session.openPath() != null
                && playerData.claimSubdividing != null
                && playerData.claimSubdividing.inDataStore) {
            addNewShapedSubdivisionCorner(player, playerData, playerData.claimSubdividing, clickedBlock);
            return;
        }

        Claim parent = resolveShapedSubdivisionParent(player, playerData, clickedBlock);
        if (parent == null) {
            return;
        }

        Claim reshapeTarget = findShapedSubdivisionBoundaryAt(parent, clickedBlock);
        if (reshapeTarget != null) {
            Supplier<String> noEditReason = reshapeTarget.checkPermission(player, ClaimPermission.Edit, null);
            if (noEditReason != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
                return;
            }

            playerData.claimSubdividing = null;
            playerData.lastClaim = reshapeTarget;
            playerData.setClaimEditorSession(
                    loadSubdivisionIntoShapedSession(playerData.getClaimEditorSession(), reshapeTarget));
            reshapeShapedSubdivision(player, playerData, reshapeTarget, clickedBlock);
            return;
        }

        playerData.claimSubdividing = parent;
        playerData.lastClaim = parent;
        playerData.setClaimEditorSession(
                playerData.getClaimEditorSession()
                        .withTarget(new ClaimEditTarget(ClaimEditTargetType.NEW_SUBDIVISION_CLAIM, null))
                        .withOpenPath(null)
                        .withActiveSegment(null)
                        .withPreview(ClaimEditPreview.empty()));
        addNewShapedSubdivisionCorner(player, playerData, parent, clickedBlock);
    }

    private void addNewShapedSubdivisionCorner(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim parent,
            @NotNull Block clickedBlock)
    {
        if (!parent.inDataStore) {
            clearShapedSubdivisionSession(playerData);
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubdivisionNoParent);
            return;
        }

        ClaimEditorSession before = playerData.getClaimEditorSession();
        if (!parent.contains(clickedBlock.getLocation(), true, false)) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubdivisionOutsideParent);
            visualizeShapedSubdivisionEditState(player, before, parent, clickedBlock.getY());
            return;
        }

        ClaimEditResult result = claimEditor.apply(
                before,
                new ClaimEditIntent(
                        ClaimEditIntentType.ADD_CORNER,
                        ClaimEditSource.TOOL,
                        null,
                        parent.getID(),
                        new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ()),
                        null,
                        true,
                        Collections.emptyList()
                )
        );
        applyClaimEditResult(player, playerData, result);
        if (!result.success()) {
            return;
        }

        // The editor snaps corners onto the previous corner's axis, so a legal click can still
        // land the snapped corner outside the parent. Roll back rather than commit an escape.
        if (draftEscapesParent(parent, result.session())) {
            playerData.setClaimEditorSession(before);
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubdivisionOutsideParent);
            visualizeShapedSubdivisionEditState(player, before, parent, clickedBlock.getY());
            return;
        }

        OrthogonalPolygon polygon = result.preview().polygon();
        if (polygon == null) {
            visualizeShapedSubdivisionEditState(player, result.session(), parent, clickedBlock.getY());
            return;
        }

        finalizeShapedSubdivisionCreation(player, playerData, parent, polygon, result, clickedBlock);
    }

    private void reshapeShapedSubdivision(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim subdivision,
            @NotNull Block clickedBlock)
    {
        Claim parent = subdivision.parent;
        if (parent == null || !parent.inDataStore) {
            clearShapedSubdivisionSession(playerData);
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubdivisionNoParent);
            return;
        }

        ClaimEditorSession before = loadSubdivisionIntoShapedSession(playerData.getClaimEditorSession(), subdivision);
        playerData.setClaimEditorSession(before);

        if (!parent.contains(clickedBlock.getLocation(), true, false)) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubdivisionOutsideParent);
            visualizeShapedSubdivisionEditState(player, before, parent, clickedBlock.getY());
            return;
        }

        ClaimEditResult result = claimEditor.apply(
                before,
                new ClaimEditIntent(
                        ClaimEditIntentType.ADD_CORNER,
                        ClaimEditSource.TOOL,
                        null,
                        subdivision.getID(),
                        new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ()),
                        null,
                        true,
                        Collections.emptyList()
                )
        );
        applyClaimEditResult(player, playerData, result);
        if (!result.success()) {
            return;
        }

        if (draftEscapesParent(parent, result.session())) {
            playerData.setClaimEditorSession(before);
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubdivisionOutsideParent);
            visualizeShapedSubdivisionEditState(player, before, parent, clickedBlock.getY());
            return;
        }

        if (result.preview().polygon() != null
                && result.session().openPath() != null
                && result.session().openPath().closureReady()) {
            finalizeShapedSubdivisionReshape(player, playerData, subdivision, result, clickedBlock);
        } else {
            visualizeShapedSubdivisionEditState(player, result.session(), parent, clickedBlock.getY());
        }
    }

    private void finalizeShapedSubdivisionCreation(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim parent,
            @NotNull OrthogonalPolygon closedPolygon,
            @NotNull ClaimEditResult result,
            @NotNull Block clickedBlock)
    {
        OrthogonalPolygon polygon = closedPolygon;
        OrthogonalPolygonValidationResult normalizedResult = OrthogonalPolygon.validatePath(polygon.closedPath());
        if (normalizedResult.isValid() && normalizedResult.polygon() != null) {
            polygon = normalizedResult.polygon();
        }

        CreateClaimResult createResult = this.dataStore.createShapedSubdivision(
                player.getWorld(),
                polygon,
                parent,
                player);

        if (!createResult.succeeded || createResult.claim == null) {
            if (createResult.denialMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, createResult.denialMessage.get());
            } else if (createResult.claim != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateSubdivisionOverlap);
                visualizeConflict(player, playerData, createResult.claim, clickedBlock, createResult.claim.is3D());
                return;
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
            }
            visualizeShapedSubdivisionEditState(player, result.session(), parent, clickedBlock.getY());
            return;
        }

        playerData.claimSubdividing = null;
        playerData.lastClaim = createResult.claim;
        playerData.setClaimEditorSession(loadSubdivisionIntoShapedSession(
                ClaimEditorSession.idle(playerData.playerID).withMode(ClaimEditorMode.SHAPED, ClaimEditSource.TOOL),
                createResult.claim));
        GriefPrevention.sendMessage(player, TextMode.Success, Messages.ShapedSubdivisionSuccess);
        BoundaryVisualization.visualizeClaim(player, createResult.claim, VisualizationType.SUBDIVISION, clickedBlock);
    }

    private void finalizeShapedSubdivisionReshape(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim subdivision,
            @NotNull ClaimEditResult result,
            @NotNull Block clickedBlock)
    {
        OrthogonalPolygon polygon = result.preview().polygon();
        if (polygon == null) {
            return;
        }

        OrthogonalPolygonValidationResult normalizedResult = OrthogonalPolygon.validatePath(polygon.closedPath());
        if (normalizedResult.isValid() && normalizedResult.polygon() != null) {
            polygon = normalizedResult.polygon();
        }

        Claim parent = subdivision.parent;
        CreateClaimResult updateResult = this.dataStore.updateShapedClaim(player, playerData, subdivision, polygon);
        if (!updateResult.succeeded || updateResult.claim == null) {
            if (updateResult.denialMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, updateResult.denialMessage.get());
            } else if (updateResult.claim != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateSubdivisionOverlap);
                visualizeConflict(player, playerData, updateResult.claim, clickedBlock, updateResult.claim.is3D());
                return;
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
            }
            visualizeShapedSubdivisionEditState(player, result.session(), parent, clickedBlock.getY());
            return;
        }

        playerData.lastClaim = updateResult.claim;
        playerData.setClaimEditorSession(loadSubdivisionIntoShapedSession(
                ClaimEditorSession.idle(playerData.playerID).withMode(ClaimEditorMode.SHAPED, ClaimEditSource.TOOL),
                updateResult.claim));
        GriefPrevention.sendMessage(player, TextMode.Success, Messages.ShapedSubdivisionReshaped);
        BoundaryVisualization.visualizeClaim(player, updateResult.claim, VisualizationType.SUBDIVISION, clickedBlock);
    }

    /**
     * The claim a new shaped subdivision would be created inside, or null when the click can't
     * start one (the player is told why in that case).
     */
    private @Nullable Claim resolveShapedSubdivisionParent(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Block clickedBlock)
    {
        Claim resolved = this.dataStore.getClaimAt(clickedBlock.getLocation(), true, playerData.lastClaim);
        Claim claim = playerEventHandler.findDeepestContainingClaim(resolved, clickedBlock.getLocation());
        if (claim == null) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubdivisionNoParent);
            return null;
        }

        if (!instance.config_claims_allowNestedSubClaims) {
            while (claim.parent != null) {
                claim = claim.parent;
            }
        }

        if (claim.is3D()) {
            GriefPrevention.sendMessage(player, TextMode.Err, Messages.ShapedSubdivisionOn3DClaim);
            return null;
        }

        Supplier<String> noEditReason = claim.checkPermission(player, ClaimPermission.Edit, null);
        if (noEditReason != null && claim.checkPermission(player, ClaimPermission.Manage, null) != null) {
            GriefPrevention.sendMessage(player, TextMode.Err, noEditReason.get());
            return null;
        }

        return claim;
    }

    /**
     * The shaped subdivision whose boundary the click landed on, searching the whole claim tree so
     * a click resolves the same way no matter which claim the player was last near.
     */
    private @Nullable Claim findShapedSubdivisionBoundaryAt(@NotNull Claim claim, @NotNull Block clickedBlock)
    {
        Claim root = claim;
        while (root.parent != null) {
            root = root.parent;
        }

        return findShapedSubdivisionBoundaryAt(root, new OrthogonalPoint2i(clickedBlock.getX(), clickedBlock.getZ()));
    }

    private @Nullable Claim findShapedSubdivisionBoundaryAt(@NotNull Claim claim, @NotNull OrthogonalPoint2i point)
    {
        for (Claim child : claim.children) {
            if (child == null || !child.inDataStore) {
                continue;
            }

            if (child.isShapedSubdivision() && isBoundaryPoint(child.getBoundaryPolygon(), point)) {
                return child;
            }

            Claim nested = findShapedSubdivisionBoundaryAt(child, point);
            if (nested != null) {
                return nested;
            }
        }

        return null;
    }

    private boolean draftEscapesParent(@NotNull Claim parent, @NotNull ClaimEditorSession session)
    {
        if (session.openPath() == null) {
            return false;
        }

        World world = parent.getLesserBoundaryCorner().getWorld();
        if (world == null) {
            return false;
        }

        int probeY = parent.getLesserBoundaryCorner().getBlockY();
        for (OrthogonalPoint2i point : session.openPath().points()) {
            if (!parent.contains(new Location(world, point.x(), probeY, point.z()), true, false)) {
                return true;
            }
        }

        return false;
    }

    private @NotNull ClaimEditorSession loadSubdivisionIntoShapedSession(
            @NotNull ClaimEditorSession session,
            @NotNull Claim subdivision)
    {
        if (session.activeTarget() != null
                && session.activeTarget().type() == ClaimEditTargetType.EXISTING_SUBDIVISION_CLAIM
                && session.activeTarget().claimId() != null
                && session.activeTarget().claimId().equals(subdivision.getID())
                && session.preview().polygon() != null) {
            return session;
        }

        return session
                .withTarget(new ClaimEditTarget(ClaimEditTargetType.EXISTING_SUBDIVISION_CLAIM, subdivision.getID()))
                .withOpenPath(null)
                .withActiveSegment(null)
                .withPreview(new ClaimEditPreview(
                        subdivision.getBoundaryPolygon(),
                        null,
                        Collections.emptyList(),
                        null,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList()));
    }

    private void clearShapedSubdivisionSession(@NotNull PlayerData playerData)
    {
        playerData.claimSubdividing = null;
        playerData.setClaimEditorSession(
                ClaimEditorSession.idle(playerData.playerID).withMode(ClaimEditorMode.SHAPED, ClaimEditSource.TOOL));
    }

    /**
     * Draws the parent claim, its existing subdivisions, and the in-progress outline. The outline
     * uses subdivision colours (iron corners, white wool sides) while draft corners keep the
     * initialize-zone marker shared with every other claim tool.
     */
    private void visualizeShapedSubdivisionEditState(
            @NotNull Player player,
            @NotNull ClaimEditorSession session,
            @Nullable Claim parent,
            int y)
    {
        Set<Boundary> boundaries = new HashSet<>();
        World world = player.getWorld();
        OrthogonalPolygon selectedPolygon = null;

        if (parent != null && parent.inDataStore) {
            boundaries.add(new Boundary(
                    parent,
                    parent.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM));
            collectSubdivisionBoundaries(parent, boundaries);
        }

        if (session.activeTarget() != null
                && session.activeTarget().type() == ClaimEditTargetType.EXISTING_SUBDIVISION_CLAIM
                && session.activeTarget().claimId() != null) {
            Claim targetClaim = this.dataStore.getClaim(session.activeTarget().claimId());
            if (targetClaim != null && targetClaim.inDataStore) {
                boundaries.add(new Boundary(targetClaim, VisualizationType.SUBDIVISION));
                selectedPolygon = targetClaim.getBoundaryPolygon();
            }
        }

        List<OrthogonalPoint2i> draftPoints = session.preview().draftPoints();
        for (int i = 0; i < draftPoints.size(); i++) {
            OrthogonalPoint2i point = draftPoints.get(i);
            boolean onSelectedBoundary = selectedPolygon != null && isBoundaryPoint(selectedPolygon, point);

            if (i > 0) {
                OrthogonalPoint2i prevPoint = draftPoints.get(i - 1);
                Location start = new Location(world, prevPoint.x(), y, prevPoint.z());
                Location end = new Location(world, point.x(), y, point.z());
                boundaries.add(new Boundary(new BoundingBox(start, end), VisualizationType.SUBDIVISION));
            }

            if (!onSelectedBoundary) {
                boundaries.add(new Boundary(
                        new BoundingBox(new Location(world, point.x(), y, point.z()),
                                new Location(world, point.x(), y, point.z())),
                        VisualizationType.INITIALIZE_ZONE));
            }
        }

        if (session.preview().snappedPoint() != null) {
            OrthogonalPoint2i point = session.preview().snappedPoint();
            if (selectedPolygon == null || !isBoundaryPoint(selectedPolygon, point)) {
                boundaries.add(new Boundary(
                        new BoundingBox(new Location(world, point.x(), y, point.z()),
                                new Location(world, point.x(), y, point.z())),
                        VisualizationType.INITIALIZE_ZONE));
            }
        }

        for (OrthogonalPoint2i point : session.preview().conflictPoints()) {
            boundaries.add(new Boundary(
                    new BoundingBox(new Location(world, point.x(), y, point.z()),
                            new Location(world, point.x(), y, point.z())),
                    VisualizationType.CONFLICT_ZONE));
        }

        if (!boundaries.isEmpty()) {
            BoundaryVisualization.callAndVisualize(
                    new BoundaryVisualizationEvent(player, boundaries, player.getEyeLocation().getBlockY()));
        }
    }

    private void collectSubdivisionBoundaries(@NotNull Claim claim, @NotNull Set<Boundary> boundaries)
    {
        for (Claim child : claim.children) {
            if (child == null || !child.inDataStore || child.is3D()) {
                continue;
            }

            boundaries.add(new Boundary(child, VisualizationType.SUBDIVISION));
            collectSubdivisionBoundaries(child, boundaries);
        }
    }

    private @NotNull BoundaryNodeEnsureResult ensureBoundaryNodeForShapedPath(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim claim,
            @NotNull Block clickedBlock,
            @NotNull ClaimEditorSession session)
    {
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

        if (!meetsMinimumNodeSpacing(claim, point))
        {
            GriefPrevention.sendMessage(
                    player,
                    TextMode.Err,
                    Messages.NewClaimTooNarrow,
                    String.valueOf(Math.max(1, GriefPrevention.instance.config_claims_shapedMinWidth)));
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

    private static final class BoundaryNodeEnsureResult
    {
        private final @Nullable Claim claim;
        private final boolean markerEdited;

        private BoundaryNodeEnsureResult(@Nullable Claim claim, boolean markerEdited)
        {
            this.claim = claim;
            this.markerEdited = markerEdited;
        }

        @Nullable Claim claim()
        {
            return claim;
        }

        boolean markerEdited()
        {
            return markerEdited;
        }
    }

    private boolean meetsMinimumNodeSpacing(@NotNull Claim claim, @NotNull OrthogonalPoint2i point)
    {
        int minimumEdgeLength = Math.max(0, GriefPrevention.instance.config_claims_shapedMinWidth - 1);
        if (minimumEdgeLength <= 0)
        {
            return true;
        }

        OrthogonalPolygon polygon = claim.getBoundaryPolygon();
        List<Integer> edgeIndexes = polygon.edgeIndexesContainingInteriorPoint(point);
        if (edgeIndexes.size() != 1)
        {
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
            @NotNull OrthogonalPoint2i clickedPoint)
    {
        int minimumEdgeLength = Math.max(0, GriefPrevention.instance.config_claims_shapedMinWidth - 1);

        if (session.openPath() == null || session.openPath().points().size() != 1)
        {
            return clickedPoint;
        }

        OrthogonalPoint2i anchor = session.openPath().points().get(0);
        OrthogonalPolygon boundaryPolygon = claim.getBoundaryPolygon();
        if (!isBoundaryPoint(boundaryPolygon, anchor) || isBoundaryPoint(boundaryPolygon, clickedPoint))
        {
            return clickedPoint;
        }

        World world = claim.getLesserBoundaryCorner().getWorld();
        if (world == null)
        {
            return clickedPoint;
        }

        Location probe = new Location(world, clickedPoint.x(), claim.getLesserBoundaryCorner().getBlockY(), clickedPoint.z());
        if (claim.contains(probe, true, false))
        {
            return clickedPoint;
        }

        int deltaX = clickedPoint.x() - anchor.x();
        int deltaZ = clickedPoint.z() - anchor.z();
        if (deltaX == 0 && deltaZ == 0)
        {
            return clickedPoint;
        }

        if (Math.abs(deltaX) < Math.abs(deltaZ))
        {
            int direction = Integer.signum(deltaZ);
            if (direction == 0)
            {
                return clickedPoint;
            }

            // Keep the first outside step pointed away from the claim boundary.
            if (claim.contains(new Location(world, anchor.x(), claim.getLesserBoundaryCorner().getBlockY(), anchor.z() + direction), true, false))
            {
                direction *= -1;
            }
            int snappedDistance = Math.max(Math.abs(deltaZ), minimumEdgeLength);
            return new OrthogonalPoint2i(anchor.x(), anchor.z() + direction * snappedDistance);
        }

        int direction = Integer.signum(deltaX);
        if (direction == 0)
        {
            return clickedPoint;
        }

        // Keep the first outside step pointed away from the claim boundary.
        if (claim.contains(new Location(world, anchor.x() + direction, claim.getLesserBoundaryCorner().getBlockY(), anchor.z()), true, false))
        {
            direction *= -1;
        }
        int snappedDistance = Math.max(Math.abs(deltaX), minimumEdgeLength);
        return new OrthogonalPoint2i(anchor.x() + direction * snappedDistance, anchor.z());
    }

    private @Nullable Claim resolveExistingShapedTarget(@NotNull ClaimEditorSession session)
    {
        if (session.activeTarget() == null
                || session.activeTarget().type() != ClaimEditTargetType.EXISTING_PARENT_CLAIM
                || session.activeTarget().claimId() == null)
        {
            return null;
        }

        return this.dataStore.getClaim(session.activeTarget().claimId());
    }

    private @NotNull ClaimEditorSession seedOpenPathFromActiveSegmentIfNeeded(
            @NotNull ClaimEditorSession session,
            @NotNull Claim claim,
            @NotNull OrthogonalPoint2i clickedPoint)
    {
        if (session.openPath() != null || session.activeSegment() == null || session.preview().polygon() == null)
        {
            return session;
        }

        OrthogonalPolygon polygon = session.preview().polygon();
        int edgeIndex = session.activeSegment().edgeIndex();
        if (edgeIndex < 0 || edgeIndex >= polygon.edges().size())
        {
            return session;
        }

        OrthogonalEdge2i edge = polygon.edges().get(edgeIndex);
        OrthogonalPoint2i start = edge.start();
        OrthogonalPoint2i end = edge.end();
        OrthogonalPoint2i anchor = manhattanDistance(clickedPoint, start) <= manhattanDistance(clickedPoint, end)
                ? start
                : end;

        com.griefprevention.claims.editor.ShapedPathDraft draft =
                new com.griefprevention.claims.editor.ShapedPathDraft(claim.getID(), Collections.singletonList(anchor), null, false);
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

    private int manhattanDistance(@NotNull OrthogonalPoint2i first, @NotNull OrthogonalPoint2i second)
    {
        return Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
    }

    private @Nullable String validateExistingMergeReconnect(
            @NotNull Claim claim,
            @NotNull ClaimEditorSession session,
            @NotNull OrthogonalPoint2i clickedPoint)
    {
        if (session.openPath() == null || !isBoundaryPoint(claim.getBoundaryPolygon(), clickedPoint))
        {
            return null;
        }

        List<OrthogonalPoint2i> points = session.openPath().points();
        OrthogonalPolygon boundaryPolygon = claim.getBoundaryPolygon();
        boolean hasOutsideCorner = points.stream()
                .skip(1)
                .anyMatch(point -> !isBoundaryPoint(boundaryPolygon, point));
        if (!hasOutsideCorner)
        {
            return "Cannot merge yet: add at least one outside corner before reconnecting to the claim boundary.";
        }

        OrthogonalPoint2i lastPoint = points.get(points.size() - 1);
        if (!isOrthogonalStep(lastPoint, clickedPoint))
        {
            return "Cannot merge yet: reconnect point must be orthogonally aligned with the current endpoint.";
        }

        return null;
    }

    private boolean isOrthogonalStep(@NotNull OrthogonalPoint2i first, @NotNull OrthogonalPoint2i second)
    {
        return (first.x() == second.x()) ^ (first.z() == second.z());
    }

    private boolean isBoundaryInteriorPoint(@NotNull Claim claim, @NotNull OrthogonalPoint2i point)
    {
        if (claim.getCornerIndexAt(point.x(), point.z()) >= 0) {
            return false;
        }

        return !claim.getBoundaryPolygon().edgeIndexesContainingInteriorPoint(point).isEmpty();
    }

    private boolean isBoundaryPoint(@NotNull OrthogonalPolygon polygon, @NotNull OrthogonalPoint2i point)
    {
        if (polygon.corners().contains(point)) {
            return true;
        }

        return polygon.edges().stream().anyMatch(edge -> edge.containsPoint(point));
    }

    private boolean isPointOnEdge(@NotNull OrthogonalPoint2i a, @NotNull OrthogonalPoint2i b, @NotNull OrthogonalPoint2i p)
    {
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

    private @NotNull ClaimEditorSession loadClaimIntoShapedSession(@NotNull ClaimEditorSession session, @NotNull Claim claim) {
        if (session.activeTarget() != null
                && session.activeTarget().claimId() != null
                && session.activeTarget().claimId().equals(claim.getID())
                && session.preview().polygon() != null) {
            return session;
        }

        OrthogonalPolygon polygon = claim.getBoundaryPolygon();
        return session.withTarget(new ClaimEditTarget(ClaimEditTargetType.EXISTING_PARENT_CLAIM, claim.getID()))
                .withOpenPath(null)
                .withActiveSegment(null)
                .withPreview(new ClaimEditPreview(polygon, null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
    }

    private void finalizeShapedClaimCreation(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull ClaimEditResult result,
            @NotNull Block clickedBlock) {
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
                player);

        if (!createResult.succeeded || createResult.claim == null) {
            if (createResult.denialMessage != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, createResult.denialMessage.get());
            } else if (createResult.claim != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);
                visualizeConflict(player, playerData, createResult.claim, clickedBlock, createResult.claim.is3D());
            } else {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
            }
            visualizeShapedEditState(player, result.session(), clickedBlock.getY());
            return;
        }

        playerData.lastClaim = createResult.claim;
        ClaimEditorSession shapedSession = ClaimEditorSession.idle(playerData.playerID)
                .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL);
        playerData.setClaimEditorSession(loadClaimIntoShapedSession(shapedSession, createResult.claim));
        GriefPrevention.sendMessage(player, TextMode.Success, Messages.CreateClaimSuccess);
        BoundaryVisualization.visualizeClaim(player, createResult.claim, VisualizationType.CLAIM, clickedBlock);

        if (!player.hasPermission("griefprevention.adminclaims") && createResult.claim.getArea() >= 1000) {
            GriefPrevention.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, 201L,
                    DataStore.SUBDIVISION_VIDEO_URL);
        }

        AutoExtendClaimTask.scheduleAsync(createResult.claim);
    }

    private void finalizeExistingClaimReshape(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim claim,
            @NotNull ClaimEditResult result,
            @NotNull Block clickedBlock) {
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
                    ClaimEditorSession shapedSession = ClaimEditorSession.idle(playerData.playerID)
                            .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL);
                    playerData.lastClaim = claim;
                    playerData.setClaimEditorSession(loadClaimIntoShapedSession(shapedSession, claim));
                    BoundaryVisualization.visualizeClaim(player, claim,
                            claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
                            clickedBlock);
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

        ClaimEditorSession shapedSession = ClaimEditorSession.idle(playerData.playerID)
                .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL);
        playerData.lastClaim = updateResult.claim;
        playerData.setClaimEditorSession(loadClaimIntoShapedSession(shapedSession, updateResult.claim));
        BoundaryVisualization.visualizeClaim(player, updateResult.claim,
                updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
                clickedBlock);
    }

    private void persistShapedBoundaryMarkers(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim claim,
            @NotNull ClaimEditResult result,
            @NotNull Block clickedBlock) {
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
                    BoundaryVisualization.visualizeClaim(player, claim,
                            claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
                            clickedBlock);
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
                ClaimEditorSession.idle(playerData.playerID)
                        .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL),
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
        playerData.setClaimEditorSession(shapedSession.withActiveSegment(result.session().activeSegment()).withPreview(preview));
        BoundaryVisualization.visualizeClaim(player, updateResult.claim,
                updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
                clickedBlock);
    }

    /**
     * Finalize a shaped cut path that closes back to the boundary.
     * Constructs the result polygon by splicing the cut path into the original boundary.
     */
    private void finalizeCutPath(
            @NotNull Player player,
            @NotNull PlayerData playerData,
            @NotNull Claim claim,
            @NotNull ClaimEditorSession session,
            @NotNull Block clickedBlock) {
        ShapedPathDraft openPath = session.openPath();
        if (openPath == null || openPath.points().size() < 3) {
            GriefPrevention.sendMessage(player, TextMode.Err, "A cut path needs at least 3 points to close.");
            return;
        }

        OrthogonalPolygon originalPolygon = claim.getBoundaryPolygon();
        List<OrthogonalPoint2i> pathPoints = openPath.points();

        // The cut path: [boundaryStart, interior1, ..., boundaryEnd]
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

        // Build result polygon by walking original boundary from cutEnd back to cutStart
        // (the long way around), then following the cut path from cutStart through interior to cutEnd.
        List<OrthogonalPoint2i> origPath = new ArrayList<>(originalPolygon.closedPath());
        // Remove closing duplicate
        if (origPath.size() > 1 && origPath.get(origPath.size() - 1).equals(origPath.get(0))) {
            origPath.remove(origPath.size() - 1);
        }

        // Find which edge of the original boundary contains cutStart and cutEnd
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

        // Build the result polygon:
        // 1) Follow the cut path from cutStart through interior to cutEnd
        // 2) Walk original boundary from endEdgeIdx forward back to startEdgeIdx
        List<OrthogonalPoint2i> resultCorners = new ArrayList<>();

        // Add cut path (start → interior → end)
        for (OrthogonalPoint2i p : pathPoints) {
            resultCorners.add(p);
        }

        // Walk original boundary from endEdgeIdx+1 back to startEdgeIdx
        int idx = (endEdgeIdx + 1) % origPath.size();
        while (idx != startEdgeIdx) {
            resultCorners.add(origPath.get(idx));
            idx = (idx + 1) % origPath.size();
        }
        // Add the vertex at startEdgeIdx (the corner before cutStart on the edge)
        resultCorners.add(origPath.get(startEdgeIdx));

        // Close the polygon back to cutStart
        resultCorners.add(cutStart);

        // Validate and construct the result polygon
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

        // Verify the result is smaller than the original
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

        ClaimEditorSession shapedSession = ClaimEditorSession.idle(playerData.playerID)
                .withMode(com.griefprevention.claims.editor.ClaimEditorMode.SHAPED, ClaimEditSource.TOOL);
        playerData.lastClaim = updateResult.claim;
        playerData.setClaimEditorSession(loadClaimIntoShapedSession(shapedSession, updateResult.claim));
        GriefPrevention.sendMessage(player, TextMode.Success, "Cut completed! The shaped land has been unclaimed.");
        BoundaryVisualization.visualizeClaim(player, updateResult.claim,
                updateResult.claim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM,
                clickedBlock);
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

    private void visualizeShapedEditState(
            @NotNull Player player,
            @NotNull ClaimEditorSession session,
            int y) {
        Set<Boundary> boundaries = new HashSet<>();
        World world = player.getWorld();
        OrthogonalPolygon selectedPolygon = null;
        // The draft path is drawn in the colours of the claim it belongs to, so pathing out an
        // admin claim stays pumpkin rather than switching to the player-claim gold.
        VisualizationType draftType = VisualizationType.CLAIM;

        if (session.activeTarget() != null
                && session.activeTarget().type() == ClaimEditTargetType.EXISTING_PARENT_CLAIM
                && session.activeTarget().claimId() != null) {
            Claim targetClaim = this.dataStore.getClaim(session.activeTarget().claimId());
            if (targetClaim != null) {
                VisualizationType type = targetClaim.isAdminClaim() ? VisualizationType.ADMIN_CLAIM : VisualizationType.CLAIM;
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
                    VisualizationType type = nearbyClaim.isAdminClaim() && nearbyClaim.parent == null
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
                boundaries.add(new Boundary(
                        new BoundingBox(new Location(world, point.x(), y, point.z()), new Location(world, point.x(), y, point.z())),
                        VisualizationType.INITIALIZE_ZONE));
            }
        }

        if (session.preview().snappedPoint() != null) {
            OrthogonalPoint2i point = session.preview().snappedPoint();
            if (selectedPolygon == null || !isBoundaryPoint(selectedPolygon, point)) {
                boundaries.add(new Boundary(
                        new BoundingBox(new Location(world, point.x(), y, point.z()), new Location(world, point.x(), y, point.z())),
                        VisualizationType.INITIALIZE_ZONE));
            }
        }

        for (OrthogonalPoint2i point : session.preview().conflictPoints()) {
            boundaries.add(new Boundary(
                    new BoundingBox(new Location(world, point.x(), y, point.z()), new Location(world, point.x(), y, point.z())),
                    VisualizationType.CONFLICT_ZONE));
        }

        if (!boundaries.isEmpty()) {
            BoundaryVisualization.callAndVisualize(new BoundaryVisualizationEvent(player, boundaries, player.getEyeLocation().getBlockY()));
        }
    }

    private void applyClaimEditResult(@NotNull Player player, @NotNull PlayerData playerData, @NotNull ClaimEditResult result) {
        playerData.setClaimEditorSession(result.session());

        if (!result.success()) {
            if (result.fallbackMessage() != null) {
                GriefPrevention.sendMessage(player, TextMode.Err, BukkitClaimEditMessages.toMessage(result.fallbackMessage()));
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
            if (root == null || !root.inDataStore)
                continue;
            if (!world.equals(root.getLesserBoundaryCorner().getWorld()))
                continue;
            Deque<Claim> stack = new ArrayDeque<>();
            stack.push(root);

            while (!stack.isEmpty()) {
                Claim current = stack.pop();
                if (current == null || !current.inDataStore)
                    continue;
                if (!world.equals(current.getLesserBoundaryCorner().getWorld()))
                    continue;

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
                                        eyePos, dir, maxDistance, threshold, current,
                                        xi + 0.5, yi + 0.5, zi + 0.5,
                                        xi, yi, zi);
                                if (candidate != null && candidate.t < bestT) {
                                    bestT = candidate.t;
                                    best = candidate;
                                }

                                // Allow aiming at interior extensions (+/-1 block toward claim interior)
                                if (maxX - minX >= 1) {
                                    int offsetX = xi == minX ? xi + 1 : xi - 1;
                                    candidate = evaluateCornerCandidate(
                                            eyePos, dir, maxDistance, threshold, current,
                                            offsetX + 0.5, yi + 0.5, zi + 0.5,
                                            xi, yi, zi);
                                    if (candidate != null && candidate.t < bestT) {
                                        bestT = candidate.t;
                                        best = candidate;
                                    }
                                }

                                if (maxZ - minZ >= 1) {
                                    int offsetZ = zi == minZ ? zi + 1 : zi - 1;
                                    candidate = evaluateCornerCandidate(
                                            eyePos, dir, maxDistance, threshold, current,
                                            xi + 0.5, yi + 0.5, offsetZ + 0.5,
                                            xi, yi, zi);
                                    if (candidate != null && candidate.t < bestT) {
                                        bestT = candidate.t;
                                        best = candidate;
                                    }
                                }

                                if (maxY - minY >= 1) {
                                    int offsetY = yi == minY ? yi + 1 : yi - 1;
                                    candidate = evaluateCornerCandidate(
                                            eyePos, dir, maxDistance, threshold, current,
                                            xi + 0.5, offsetY + 0.5, zi + 0.5,
                                            xi, yi, zi);
                                    if (candidate != null && candidate.t < bestT) {
                                        bestT = candidate.t;
                                        best = candidate;
                                    }
                                }

                                if (maxY == minY) {
                                    int aboveY = yi + 1;
                                    if (aboveY <= GriefPrevention.getWorldMaxY(world)) {
                                        candidate = evaluateCornerCandidate(
                                                eyePos, dir, maxDistance, threshold, current,
                                                xi + 0.5, aboveY + 0.5, zi + 0.5,
                                                xi, yi, zi);
                                        if (candidate != null && candidate.t < bestT) {
                                            bestT = candidate.t;
                                            best = candidate;
                                        }
                                    }

                                    int belowY = yi - 1;
                                    if (belowY >= GriefPrevention.getWorldMinY(world)) {
                                        candidate = evaluateCornerCandidate(
                                                eyePos, dir, maxDistance, threshold, current,
                                                xi + 0.5, belowY + 0.5, zi + 0.5,
                                                xi, yi, zi);
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
            int hitZ) {
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

    static Block getTargetBlock(Player player, int maxDistance) throws IllegalStateException {
        return getTargetBlock(player, maxDistance, player.getLocation().getYaw(), player.getLocation().getPitch());
    }

    static Block getTargetBlock(Player player, int maxDistance, float yaw, float pitch) throws IllegalStateException {
        Location loc = player.getLocation();
        Location eye = loc.clone().add(0, player.getEyeHeight(), 0);
        eye.setYaw(yaw);
        eye.setPitch(pitch);

        // Check if player is in water by checking feet and eye position
        boolean playerInWater = (loc.getBlock().getType() == Material.WATER) || (eye.getBlock().getType() == Material.WATER);
        boolean hasTags = MaterialTagCompat.isTagged("REPLACEABLE", Material.AIR);

        Vector direction = eye.getDirection();
        BlockIterator iterator = new BlockIterator(
            player.getWorld(),
            eye.toVector(),
            direction,
            0,
            maxDistance
        );

        Block result = eye.getBlock();
        int iterations = 0;

        while (iterator.hasNext()) {
            result = iterator.next();
            iterations++;
            Material type = result.getType();
            if (hasTags) {
                // Stop at water if player is not in water
                if (!playerInWater && type == Material.WATER) {
                    GriefPrevention.AddLogEntry("[GP Debug] getTargetBlock: found block after " + iterations + " iterations: " + type.name() + " @ " + result.getX() + "," + result.getY() + "," + result.getZ(), CustomLogEntryTypes.Debug, false);
                    return result;
                }
                // Stop at non-replaceable blocks
                if (!MaterialTagCompat.isTagged("REPLACEABLE", type)) {
                    GriefPrevention.AddLogEntry("[GP Debug] getTargetBlock: found block after " + iterations + " iterations: " + type.name() + " @ " + result.getX() + "," + result.getY() + "," + result.getZ(), CustomLogEntryTypes.Debug, false);
                    return result;
                }
            } else {
                // Pre-1.13: Tags don't exist, check transparent materials manually
                if (type == Material.AIR
                        || type == Material.WATER
                        || type == Material.LAVA
                        || type == Material.DEAD_BUSH
                        || type == Material.SNOW
                        || type == Material.VINE
                        || type == Material.FIRE
                        || isTallGrass(type))
                    continue;
                GriefPrevention.AddLogEntry("[GP Debug] getTargetBlock: found block after " + iterations + " iterations: " + type.name() + " @ " + result.getX() + "," + result.getY() + "," + result.getZ(), CustomLogEntryTypes.Debug, false);
                return result;
            }
        }

        GriefPrevention.AddLogEntry("[GP Debug] getTargetBlock: no block found after " + iterations + " iterations, returning last result", CustomLogEntryTypes.Debug, false);
        return result;
    }

    // Resolves tall grass material name across versions (LONG_GRASS on 1.8.8, GRASS on 1.13+)
    private static boolean isTallGrass(Material material) {
        if (material == null) return false;
        String name = material.name();
        return "LONG_GRASS".equals(name) || "GRASS".equals(name) || "TALL_GRASS".equals(name);
    }

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
                player);
        SchedulerUtil.runAsyncNow(instance, processingTask);
        GriefPrevention.sendMessage(player, TextMode.Info, "Restoring nature in the selected area...");
    }

    private void visualizeConflict(Player player, PlayerData playerData, Claim claim, Block clickedBlock,
            boolean is3D) {
        VisualizationType conflictType = is3D ? VisualizationType.CONFLICT_ZONE_3D : VisualizationType.CONFLICT_ZONE;
        BoundaryVisualization.visualizeClaim(player, claim, conflictType, clickedBlock);

        // If glow is enabled, auto-clear after 5 seconds
        if (GriefPrevention.instance.config_visualizationGlow) {
            SchedulerUtil.runLaterEntity(GriefPrevention.instance, player, () -> {
                playerData.setVisibleBoundaries(null);
            }, 100L);
        }
    }
}
