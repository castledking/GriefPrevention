package com.griefprevention.protection;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.ClaimsMode;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.Messages;
import me.ryanhamshire.GriefPrevention.PlayerData;
import me.ryanhamshire.GriefPrevention.events.PreventBlockBreakEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;
import java.util.Set;
import java.util.HashSet;
import java.util.Collection;

/**
 * A utility used to simplify various protection-related checks.
 */
public final class ProtectionHelper
{
    private ProtectionHelper() {}

    /**
     * Check the {@link ClaimPermission} state for a {@link Player} at a particular {@link Location}.
     *
     * <p>This respects ignoring claims, wilderness rules, etc.</p>
     *
     * @param player the person performing the action
     * @param location the affected {@link Location}
     * @param permission the required permission
     * @param trigger the triggering {@link Event}, if any
     * @return the denial message supplier, or {@code null} if the action is not denied
     */
    public static @Nullable Supplier<String> checkPermission(
            @NotNull Player player,
            @NotNull Location location,
            @NotNull ClaimPermission permission,
            @Nullable Event trigger)
    {
        World world = location.getWorld();
        if (world == null || !GriefPrevention.instance.claimsEnabledForWorld(world)) return null;

        PlayerData playerData = GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId());

        // Administrators ignoring claims always have permission.
        if (playerData.ignoreClaims) return null;

        // Get claim at location, respecting 3D boundaries (ignoreHeight = false)
        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, playerData.lastClaim);

        // If there is no claim here, use wilderness rules.
        if (claim == null)
        {
            ClaimsMode mode = GriefPrevention.instance.config_claims_worldModes.get(world);
            if (mode == ClaimsMode.Creative || mode == ClaimsMode.SurvivalRequiringClaims)
            {
                // Allow placing chest if it would create an automatic claim.
                if (trigger instanceof BlockPlaceEvent placeEvent
                        && placeEvent.getBlock().getType() == Material.CHEST
                        && playerData.getClaims().isEmpty()
                        && GriefPrevention.instance.config_claims_automaticClaimsForNewPlayersRadius > -1)
                    return null;

                // If claims are required, provide relevant information.
                return () ->
                {
                    String reason = GriefPrevention.instance.dataStore.getMessage(Messages.NoBuildOutsideClaims);
                    if (player.hasPermission("griefprevention.ignoreclaims"))
                        reason += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                    reason += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.CreativeBasicsVideo2, DataStore.CREATIVE_VIDEO_URL);
                    return reason;
                };
            }

            // If claims are not required, then the player has permission.
            return null;
        }

        // Update cached claim.
        playerData.lastClaim = claim;

        // Apply claim rules.
        Supplier<String> cancel = claim.checkPermission(player, permission, trigger);

        // Check if we're dealing with a block-related event that needs Y boundary checking
        if (cancel == null && (trigger instanceof BlockBreakEvent || trigger instanceof BlockPlaceEvent)) {
            int y = location.getBlockY();
            
            // For 3D claims or subdivisions, enforce strict Y boundaries
            if (claim.is3D() || claim.parent != null) {
                int minY = Math.min(claim.getLesserBoundaryCorner().getBlockY(), claim.getGreaterBoundaryCorner().getBlockY());
                int maxY = Math.max(claim.getLesserBoundaryCorner().getBlockY(), claim.getGreaterBoundaryCorner().getBlockY());
                
                if (y < minY || y > maxY) {
                    // For subdivisions, check if another claim explicitly covers this location
                    Claim coveringClaim = GriefPrevention.instance.dataStore.getClaimAt(location, false, null);
                    if (coveringClaim != null && coveringClaim != claim) {
                        cancel = coveringClaim.checkPermission(player, permission, trigger);
                    } else {
                        cancel = () -> "You don't have permission to build outside this claim's Y boundaries.";
                    }
                }
            } else {
                // For regular claims, ensure we're within world boundaries
                int worldMinY = location.getWorld() != null ? location.getWorld().getMinHeight() : -64;
                int worldMaxY = location.getWorld() != null ? location.getWorld().getMaxHeight() : 319;
                
                if (y < worldMinY || y > worldMaxY) {
                    cancel = () -> "You can't build outside the world's height limits.";
                }
            }
            
            // If we still haven't denied the action, check for claims in this X/Z column
            if (cancel == null) {
                // Get all claims at this X/Z coordinate by checking chunks around the location
                Set<Claim> claimsAtLocation = new HashSet<>();
                int x = location.getBlockX();
                int z = location.getBlockZ();
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                
                // Check current chunk and adjacent chunks to cover all possible claims
                for (int cx = -1; cx <= 1; cx++) {
                    for (int cz = -1; cz <= 1; cz++) {
                        Collection<Claim> chunkClaims = GriefPrevention.instance.dataStore.getClaims(chunkX + cx, chunkZ + cz);
                        if (chunkClaims != null) {
                            for (Claim chunkClaim : chunkClaims) {
                                // Only add claims that include this X/Z coordinate
                                if (chunkClaim.contains(new Location(location.getWorld(), x, 0, z), true, false)) {
                                    claimsAtLocation.add(chunkClaim);
                                }
                            }
                        }
                    }
                }
                
                // Check all claims in this X/Z column
                for (Claim otherClaim : claimsAtLocation) {
                    if (otherClaim == claim) continue; // Skip the current claim
                    
                    // For 3D claims or subdivisions, check if we're outside their Y bounds
                    if (otherClaim.is3D() || otherClaim.parent != null) {
                        int otherMinY = Math.min(otherClaim.getLesserBoundaryCorner().getBlockY(), 
                                              otherClaim.getGreaterBoundaryCorner().getBlockY());
                        int otherMaxY = Math.max(otherClaim.getLesserBoundaryCorner().getBlockY(), 
                                              otherClaim.getGreaterBoundaryCorner().getBlockY());
                        
                        // Deny actions above or below other claims
                        if (y < otherMinY) {
                            cancel = () -> "You don't have permission to build below a claim in this area.";
                            break;
                        } else if (y > otherMaxY) {
                            cancel = () -> "You don't have permission to build above a claim in this area.";
                            break;
                        }
                    }
                }
            }
        }

        // Apply additional specific rules for block breaks
        if (cancel != null && trigger instanceof BlockBreakEvent breakEvent) {
            PreventBlockBreakEvent preventionEvent = new PreventBlockBreakEvent(breakEvent);
            Bukkit.getPluginManager().callEvent(preventionEvent);
            if (preventionEvent.isCancelled()) {
                cancel = null;
            }
        }

        return cancel;
    }
}