package com.griefprevention.visualization.impl;

import com.griefprevention.visualization.Boundary;
import com.griefprevention.visualization.VisualizationType;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import com.griefprevention.util.IntVector;
import me.ryanhamshire.GriefPrevention.util.BoundingBox;
import me.ryanhamshire.GriefPrevention.util.SchedulerUtil;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.AxisAngle4f;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

public class GlowingVisualization extends FakeBlockVisualization {

    private final Map<UUID, Set<BlockDisplay>> playerDisplays = new HashMap<>();
    private final Map<IntVector, BlockData> displayLocations = new HashMap<>();
    // Optional per-position glow color overrides (e.g., ADMIN_CLAIM glowstone corners -> orange)
    private final Map<IntVector, org.bukkit.Color> glowColorOverrides = new HashMap<>();
    private final GriefPrevention plugin;
    private final boolean waterTransparent;
    
    public GlowingVisualization(@NotNull World world, @NotNull com.griefprevention.util.IntVector visualizeFrom, int height) {
        super(world, visualizeFrom, height);
        this.plugin = GriefPrevention.instance;
        // Water is considered transparent based on whether the visualization is initiated in water
        this.waterTransparent = visualizeFrom.toBlock(world).getType() == Material.WATER;
    }

    @Override
    public void handleBlockBreak(@NotNull Player player, @NotNull Block block) {
        // Remove any BlockDisplay(s) for this player at this exact block position
        Set<BlockDisplay> displays = playerDisplays.get(player.getUniqueId());
        if (displays != null && !displays.isEmpty()) {
            int bx = block.getX();
            int by = block.getY();
            int bz = block.getZ();
            // Remove matching display entities
            displays.removeIf(d -> {
                if (d == null || !d.isValid()) return true; // clean up invalid
                Location dl = d.getLocation();
                boolean match = dl.getBlockX() == bx && dl.getBlockY() == by && dl.getBlockZ() == bz;
                if (match) {
                    try { d.remove(); } catch (Exception ignored) {}
                }
                return match;
            });
        }

        // Remove from our recorded display locations so future refreshes don't recreate it
        synchronized (this) {
            displayLocations.remove(new IntVector(block.getX(), block.getY(), block.getZ()));
            glowColorOverrides.remove(new IntVector(block.getX(), block.getY(), block.getZ()));
        }

        // Also remove the underlying fake block visualization element at this coordinate
        // so the clientside block (gold/iron/wool/etc.) disappears as well.
        removeElementAt(player, new IntVector(block.getX(), block.getY(), block.getZ()));
    }

    @Override
    protected void apply(@NotNull Player player, @NotNull me.ryanhamshire.GriefPrevention.PlayerData playerData) {
        // Clear any existing displays first
        Set<BlockDisplay> existingDisplays = playerDisplays.remove(player.getUniqueId());
        if (existingDisplays != null) {
            for (BlockDisplay display : existingDisplays) {
                try {
                    if (display != null && display.isValid()) {
                        display.remove();
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
        
        // Clear display locations to ensure fresh visualization
        synchronized (this) {
            displayLocations.clear();
            glowColorOverrides.clear();
        }
        
        // Call super.apply() to show the underlying FakeBlockVisualization (yellow outline blocks)
        super.apply(player, playerData);
        
        // Run with 2 tick delay to ensure proper world loading and that draw() has been called
        Plugin gpPlugin = (Plugin) plugin;
        SchedulerUtil.runLaterEntity(gpPlugin, player, new Runnable() {
            @Override
            public void run() {
                // Double check player is still online
                if (!player.isOnline()) {
                    return;
                }
                
                // Create a copy of the current display locations (populated by draw())
                Map<IntVector, BlockData> locationsToDisplay;
                synchronized (GlowingVisualization.this) {
                    locationsToDisplay = new HashMap<>(displayLocations);
                }
                
                try {
                    // Create new displays set for this player
                    Set<BlockDisplay> displays = new HashSet<>();
                    playerDisplays.put(player.getUniqueId(), displays);
                    
                    // Create displays for each location
                    for (Map.Entry<IntVector, BlockData> entry : locationsToDisplay.entrySet()) {
                        IntVector pos = entry.getKey();
                        if (pos != null && world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                            createBlockDisplay(player, pos, entry.getValue(), displays);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error creating block displays: " + e.getMessage());
                    if (e.getCause() != null) {
                        e.getCause().printStackTrace();
                    }
                }
            }
        }, 1L);
        
        // Store the task handle if needed for later cancellation
        // task.cancel() can be called to cancel the task if needed
    }

    @Override
    public void revert(@NotNull Player player) {
        super.revert(player);
        
        // Get and clear displays for this player
        Set<BlockDisplay> displays = playerDisplays.remove(player.getUniqueId());
        
        // Clear displays immediately in the main thread
        if (displays != null && !displays.isEmpty()) {
            for (BlockDisplay display : displays) {
                try {
                    if (display != null && display.isValid()) {
                        display.remove();
                    }
                } catch (Exception e) {
                    // Ignore - entity already removed
                }
            }
        }
        
        // Clear display locations
        displayLocations.clear();
    }

    @Override
    protected void draw(@NotNull Player player, @NotNull Boundary boundary) {
        // Call super.draw() for all types to show underlying visualization (white/yellow outline blocks)
        super.draw(player, boundary);
        
        BoundingBox area = boundary.bounds();
        boolean is3D = boundary.type() == VisualizationType.SUBDIVISION_3D;
        boolean isSubdivision = boundary.type() == VisualizationType.SUBDIVISION;
        boolean isMainClaim = boundary.claim() != null && boundary.claim().parent == null;
        
        if (is3D) {
            // Check if this is a 1x1x1 subdivision (should show as single block)
            boolean is1x1x1 = (area.getMinX() == area.getMaxX()) &&
                             (area.getMinZ() == area.getMaxZ()) &&
                             (area.getMinY() == area.getMaxY());

            if (is1x1x1) {
                // For 1x1x1 subdivisions, show only the single corner block at each Y level
                int minY = area.getMinY();
                int maxY = area.getMaxY();
                int x = area.getMinX();
                int z = area.getMinZ();

                // Add single block at bottom
                if (minY >= world.getMinHeight() && minY <= world.getMaxHeight()) {
                    addDisplayLocation(new IntVector(x, minY, z), getCornerBlockData(boundary.type()));
                }

                // Add single block at top
                if (maxY >= world.getMinHeight() && maxY <= world.getMaxHeight() && maxY != minY) {
                    addDisplayLocation(new IntVector(x, maxY, z), getCornerBlockData(boundary.type()));
                }

                // Add vertical connection if there's height
                if (maxY > minY + 1) {
                    BlockData sideBlock = getSideBlockData(boundary.type());
                    for (int y = minY + 1; y < maxY; y++) {
                        if (y >= world.getMinHeight() && y <= world.getMaxHeight()) {
                            addDisplayLocation(new IntVector(x, y, z), sideBlock);
                        }
                    }
                }
            } else {
                // For larger 3D subdivisions, show corners with extensions
                int minY = area.getMinY();
                int maxY = area.getMaxY();
                int minX = area.getMinX();
                int maxX = area.getMaxX();
                int minZ = area.getMinZ();
                int maxZ = area.getMaxZ();

                // Determine if the footprint is only 1 block thick along X or Z
                boolean thinX = (minX == maxX);
                boolean thinZ = (minZ == maxZ);

                // Bottom layer (minY) - add extensions only along the long axis and avoid duplicate corners
                if (thinX) {
                    // Only two unique corners along Z, extend along Z only
                    addCornerWithExtensions(minX, minY, minZ, 0, -1, 0, boundary.type());
                    addCornerWithExtensions(minX, minY, maxZ, 0,  1, 0, boundary.type());
                } else if (thinZ) {
                    // Only two unique corners along X, extend along X only
                    addCornerWithExtensions(minX, minY, minZ, -1, 0, 0, boundary.type());
                    addCornerWithExtensions(maxX, minY, minZ,  1, 0, 0, boundary.type());
                } else {
                    addCornerWithExtensions(minX, minY, minZ, -1, -1, 0, boundary.type());  // Min X, Min Z, Min Y
                    addCornerWithExtensions(minX, minY, maxZ, -1,  1, 0, boundary.type());  // Min X, Max Z, Min Y
                    addCornerWithExtensions(maxX, minY, minZ,  1, -1, 0, boundary.type());  // Max X, Min Z, Min Y
                    addCornerWithExtensions(maxX, minY, maxZ,  1,  1, 0, boundary.type());  // Max X, Max Z, Min Y
                }

                // Top layer (maxY) - add extensions only along the long axis and avoid duplicate corners
                if (thinX) {
                    addCornerWithExtensions(minX, maxY, minZ, 0, -1, 0, boundary.type());
                    addCornerWithExtensions(minX, maxY, maxZ, 0,  1, 0, boundary.type());
                } else if (thinZ) {
                    addCornerWithExtensions(minX, maxY, minZ, -1, 0, 0, boundary.type());
                    addCornerWithExtensions(maxX, maxY, minZ,  1, 0, 0, boundary.type());
                } else {
                    addCornerWithExtensions(minX, maxY, minZ, -1, -1, 0, boundary.type());  // Min X, Min Z, Max Y
                    addCornerWithExtensions(minX, maxY, maxZ, -1,  1, 0, boundary.type());  // Min X, Max Z, Max Y
                    addCornerWithExtensions(maxX, maxY, minZ,  1, -1, 0, boundary.type());  // Max X, Min Z, Max Y
                    addCornerWithExtensions(maxX, maxY, maxZ,  1,  1, 0, boundary.type());  // Max X, Max Z, Max Y
                }

                // Add vertical edges for 3D visualization
                addVerticalEdges(minX, minY, maxY, minZ, boundary.type());  // Min X, Min Z
                addVerticalEdges(minX, minY, maxY, maxZ, boundary.type());  // Min X, Max Z
                addVerticalEdges(maxX, minY, maxY, minZ, boundary.type());  // Max X, Min Z
                addVerticalEdges(maxX, minY, maxY, maxZ, boundary.type());  // Max X, Max Z

                // Add inner Y bound extensions (inside the 3D volume) at corner positions
                BlockData extensionBlock = getSideBlockData(boundary.type());
                
                // Add extension blocks INSIDE the claim volume (not outside)
                // Place them one block in from the top and bottom boundaries
                int insideBottomY = minY + 1;  // One block up from bottom boundary (inside)
                int insideTopY = maxY - 1;     // One block down from top boundary (inside)
                
                // Only add if there's enough vertical space (at least 3 blocks tall)
                if (insideBottomY < insideTopY) {
                    // Add blocks near the bottom boundary (inside the volume)
                    addDisplayLocation(new IntVector(minX, insideBottomY, minZ), extensionBlock);
                    addDisplayLocation(new IntVector(minX, insideBottomY, maxZ), extensionBlock);
                    addDisplayLocation(new IntVector(maxX, insideBottomY, minZ), extensionBlock);
                    addDisplayLocation(new IntVector(maxX, insideBottomY, maxZ), extensionBlock);
                    
                    // Add blocks near the top boundary (inside the volume)
                    addDisplayLocation(new IntVector(minX, insideTopY, minZ), extensionBlock);
                    addDisplayLocation(new IntVector(minX, insideTopY, maxZ), extensionBlock);
                    addDisplayLocation(new IntVector(maxX, insideTopY, minZ), extensionBlock);
                    addDisplayLocation(new IntVector(maxX, insideTopY, maxZ), extensionBlock);
                }

                // 3D subdivisions don't need side markers - they have their own 3D structure
                // Don't call addMainClaimSideMarkers for 3D subdivisions to avoid underground blocks
            }
        } else if (isSubdivision) {
            // For 2D subdivisions, render exactly: 4 iron corners + 8 white wool stubs one block toward interior
            int minX = area.getMinX();
            int maxX = area.getMaxX();
            int minZ = area.getMinZ();
            int maxZ = area.getMaxZ();

            // Corner heights
            int nwY = getTerrainYAt(minX, minZ, player);  // NW
            int swY = getTerrainYAt(minX, maxZ, player);  // SW
            int neY = getTerrainYAt(maxX, minZ, player);  // NE
            int seY = getTerrainYAt(maxX, maxZ, player);  // SE

            // Iron corners (no horizontal lines)
            addCornerWithExtensions(minX, nwY, minZ, 0, 0, 0, boundary.type());
            addCornerWithExtensions(minX, swY, maxZ, 0, 0, 0, boundary.type());
            addCornerWithExtensions(maxX, neY, minZ, 0, 0, 0, boundary.type());
            addCornerWithExtensions(maxX, seY, maxZ, 0, 0, 0, boundary.type());

            // 8 white wool stubs one block inward from each corner, each snapped independently to surface
            BlockData wool = Material.WHITE_WOOL.createBlockData();

            // NW corner stubs (+X, +Z)
            if (minX + 1 <= maxX) {
                int y = getTerrainYAt(minX + 1, minZ, null);
                addDisplayLocation(new IntVector(minX + 1, y, minZ), wool);
            }
            if (minZ + 1 <= maxZ) {
                int y = getTerrainYAt(minX, minZ + 1, null);
                addDisplayLocation(new IntVector(minX, y, minZ + 1), wool);
            }

            // SW corner stubs (+X, -Z)
            if (minX + 1 <= maxX) {
                int y = getTerrainYAt(minX + 1, maxZ, null);
                addDisplayLocation(new IntVector(minX + 1, y, maxZ), wool);
            }
            if (maxZ - 1 >= minZ) {
                int y = getTerrainYAt(minX, maxZ - 1, null);
                addDisplayLocation(new IntVector(minX, y, maxZ - 1), wool);
            }

            // NE corner stubs (-X, +Z)
            if (maxX - 1 >= minX) {
                int y = getTerrainYAt(maxX - 1, minZ, null);
                addDisplayLocation(new IntVector(maxX - 1, y, minZ), wool);
            }
            if (minZ + 1 <= maxZ) {
                int y = getTerrainYAt(maxX, minZ + 1, null);
                addDisplayLocation(new IntVector(maxX, y, minZ + 1), wool);
            }

            // SE corner stubs (-X, -Z)
            if (maxX - 1 >= minX) {
                int y = getTerrainYAt(maxX - 1, maxZ, null);
                addDisplayLocation(new IntVector(maxX - 1, y, maxZ), wool);
            }
            if (maxZ - 1 >= minZ) {
                int y = getTerrainYAt(maxX, maxZ - 1, null);
                addDisplayLocation(new IntVector(maxX, y, maxZ - 1), wool);
            }
        } else {
            // For regular 2D visualization, snap each corner to its own terrain height
            int minX = area.getMinX();
            int maxX = area.getMaxX();
            int minZ = area.getMinZ();
            int maxZ = area.getMaxZ();
            
            // Get terrain height for each corner
            int y1 = getTerrainYAt(minX, minZ, player);
            int y2 = getTerrainYAt(minX, maxZ, player);
            int y3 = getTerrainYAt(maxX, minZ, player);
            int y4 = getTerrainYAt(maxX, maxZ, player);
            
            // Add corner blocks at their respective heights with correct extension directions
            // Directions: 1 = positive direction (east/south), -1 = negative direction (west/north), 0 = no extension
            // For each corner, we want to extend outward from the claim
            addCornerWithExtensions(minX, y1, minZ, -1, -1, 0, boundary.type());  // NW corner: extend west and north
            addCornerWithExtensions(minX, y2, maxZ, -1, 1, 0, boundary.type());   // NE corner: extend west and south
            addCornerWithExtensions(maxX, y3, minZ, 1, -1, 0, boundary.type());    // SW corner: extend east and north
            addCornerWithExtensions(maxX, y4, maxZ, 1, 1, 0, boundary.type());     // SE corner: extend east and south
            
            // For main claims, admin claims, and conflict zones, add side markers at each corner's height
            if (isMainClaim || boundary.type() == VisualizationType.ADMIN_CLAIM || boundary.type() == VisualizationType.CONFLICT_ZONE) {
                addMainClaimSideMarkers(area, -1, boundary.type(), world); // -1 indicates to use per-corner heights
            }
        }
    }
    
    /**
     * Adds a corner block with extensions in the specified directions
     * @param x X coordinate of the corner
     * @param y Y coordinate of the corner
     * @param z Z coordinate of the corner
     * @param dx X direction modifier (-1, 0, or 1)
     * @param dz Z direction modifier (-1, 0, or 1)
     * @param dy Y direction modifier (-1, 0, or 1)
     * @param type The type of visualization
     */
    private void addCornerWithExtensions(int x, int y, int z, int dx, int dz, int dy, VisualizationType type) {
        // Add the corner block
        BlockData cornerBlock = getCornerBlockData(type);
        addDisplayLocation(new IntVector(x, y, z), cornerBlock, type);

        // For main claims, admin claims, initialize zones, and conflict zones, only add the corner block - no extensions
        if (type == VisualizationType.CLAIM || type == VisualizationType.ADMIN_CLAIM || 
            type == VisualizationType.INITIALIZE_ZONE || type == VisualizationType.CONFLICT_ZONE) {
            return; // Exit early for these types to avoid extensions
        }

        // For 3D subdivisions, add horizontal extensions from corners
        if (type == VisualizationType.SUBDIVISION_3D) {
            // Add horizontal extensions in the correct direction (outward from claim)
            // Invert the direction to extend outward from the claim boundary
            if (dx != 0) {
                // Use side block material (white wool) for horizontal extensions
                addDisplayLocation(new IntVector(x - dx, y, z), getSideBlockData(type));
            }
            if (dz != 0) {
                // Use side block material (white wool) for horizontal extensions
                addDisplayLocation(new IntVector(x, y, z - dz), getSideBlockData(type));
            }
        }
    }
    
    /**
     * Adds vertical edges for 3D visualization
     * @param x The x-coordinate of the edge
     * @param minY The minimum Y level
     * @param maxY The maximum Y level
     * @param z The z-coordinate of the edge
     * @param type The visualization type
     */
    private void addVerticalEdges(int x, int minY, int maxY, int z, VisualizationType type) {
        BlockData edgeBlock = getSideBlockData(type);

        // For 3D subdivisions, avoid placing white-wool markers exactly on the corner positions
        // Corners are already drawn as iron blocks and have their own 3 white-wool extensions.
        if (type != VisualizationType.SUBDIVISION_3D && type != VisualizationType.SUBDIVISION) {
            // For other claim types (but not 2D subdivisions), show the full vertical line
            for (int y = minY + 1; y < maxY; y++) {
                addDisplayLocation(new IntVector(x, y, z), edgeBlock);
            }
        }
        // For 2D subdivisions and 3D subdivisions (handled elsewhere), do not add vertical edges here
    }
    
    /**
     * Gets the terrain Y coordinate at a specific x,z position with improved surface detection
     */
    private int getTerrainYAt(int x, int z, Player player) {
        if (world.isChunkLoaded(x >> 4, z >> 4)) {
            // Use the same logic as the original GP visualization to handle transparent blocks properly
            IntVector vector = new IntVector(x, world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE), z);
            Block block = getVisibleLocation(vector);
            return block.getY();
        }
        // If chunk not loaded, return player's Y level or a safe default
        return player != null ? player.getLocation().getBlockY() - 1 : world.getMinHeight() + 63;
    }

    /**
     * Find a location that should be visible to players. This causes the visualization to "cling" to the ground.
     * Implementation mirrors FakeBlockVisualization to avoid underground artifacts and match block visualization.
     */
    private Block getVisibleLocation(@NotNull IntVector vector)
    {
        Block block = vector.toBlock(world);
        BlockFace direction = (isTransparent(block)) ? BlockFace.DOWN : BlockFace.UP;

        while (block.getY() >= world.getMinHeight() &&
                block.getY() < world.getMaxHeight() - 1 &&
                (!isTransparent(block.getRelative(BlockFace.UP)) || isTransparent(block)))
        {
            block = block.getRelative(direction);
        }

        return block;
    }

    /**
     * Helper method for determining if a Block is transparent from the top down.
     * Mirrors the logic from FakeBlockVisualization.
     */
    protected boolean isTransparent(Block block) {
        Material blockMaterial = block.getType();

        // Custom per-material definitions matching FakeBlockVisualization
        switch (blockMaterial) {
            case WATER:
                return waterTransparent; // Use dynamic water transparency based on context
            case SNOW:
                return false; // Snow is not transparent
        }

        if (blockMaterial.isAir()
                || Tag.FENCES.isTagged(blockMaterial)
                || Tag.FENCE_GATES.isTagged(blockMaterial)
                || Tag.SIGNS.isTagged(blockMaterial)
                || Tag.WALLS.isTagged(blockMaterial)
                || Tag.WALL_SIGNS.isTagged(blockMaterial)) {
            return true;
        }

        return block.getType().isTransparent();
    }
    
    private BlockData getCornerBlockData(VisualizationType type) {
        return switch (type) {
            case CLAIM -> {
                // Use glowstone for main claim corners to match the true GP block visualization
                BlockData data = Material.GLOWSTONE.createBlockData();
                if (data instanceof Lightable) {
                    ((Lightable) data).setLit(true);
                }
                yield data;
            }
            case SUBDIVISION_3D, SUBDIVISION -> {
                // Use iron blocks for subdivision corners (both 2D and 3D)
                BlockData data = Material.IRON_BLOCK.createBlockData();
                if (data instanceof Lightable) {
                    ((Lightable) data).setLit(true);
                }
                yield data;
            }
            case INITIALIZE_ZONE -> {
                // Use diamond blocks for initialization zones
                BlockData data = Material.DIAMOND_BLOCK.createBlockData();
                if (data instanceof Lightable) {
                    ((Lightable) data).setLit(true);
                }
                yield data;
            }
            case CONFLICT_ZONE -> {
                // Use redstone ore for conflict zones
                BlockData data = Material.REDSTONE_ORE.createBlockData();
                if (data instanceof Lightable) {
                    ((Lightable) data).setLit(true);
                }
                yield data;
            }
            case ADMIN_CLAIM -> {
                // Use glowstone for admin claim corners
                BlockData data = Material.GLOWSTONE.createBlockData();
                if (data instanceof Lightable) {
                    ((Lightable) data).setLit(true);
                }
                yield data;
            }
            default -> {
                // Default to glowstone for unknown types
                BlockData data = Material.GLOWSTONE.createBlockData();
                if (data instanceof Lightable) {
                    ((Lightable) data).setLit(true);
                }
                yield data;
            }
        };
    }
    
    private void addMainClaimSideMarkers(BoundingBox area, int y, VisualizationType type, World world) {
        int minX = area.getMinX();
        int maxX = area.getMaxX();
        int minZ = area.getMinZ();
        int maxZ = area.getMaxZ();
        boolean is3D = type == VisualizationType.SUBDIVISION_3D;

        BlockData sideBlock = getSideBlockData(type);
        BlockData cornerBlock = getCornerBlockData(type);

        // Match the step size from BlockBoundaryVisualization
        final int STEP = 10;

        if (is3D) {
            // For 3D claims, show both top and bottom layers
            int minY = area.getMinY();
            int maxY = area.getMaxY();

            // Add corners (use overload with type so ADMIN_CLAIM corners get orange glow override)
            addDisplayLocation(new IntVector(minX, minY, minZ), cornerBlock, type);  // Bottom -X, -Z corner
            addDisplayLocation(new IntVector(minX, minY, maxZ), cornerBlock, type);  // Bottom -X, +Z corner
            addDisplayLocation(new IntVector(maxX, minY, minZ), cornerBlock, type);  // Bottom +X, -Z corner
            addDisplayLocation(new IntVector(maxX, minY, maxZ), cornerBlock, type);  // Bottom +X, +Z corner
            addDisplayLocation(new IntVector(minX, maxY, minZ), cornerBlock, type);  // Top -X, -Z corner
            addDisplayLocation(new IntVector(minX, maxY, maxZ), cornerBlock, type);  // Top -X, +Z corner
            addDisplayLocation(new IntVector(maxX, maxY, minZ), cornerBlock, type);  // Top +X, -Z corner
            addDisplayLocation(new IntVector(maxX, maxY, maxZ), cornerBlock, type);  // Top +X, +Z corner

            // Add internal vertical extensions to show height connection (1 block up from bottom, 1 block down from top)
            if (maxY > minY + 1) {  // Only if there's height to show
                // From bottom corners: 1 block up (internal) - ensure above ground
                int bottomVerticalY = Math.max(world.getMinHeight(), minY + 1);
                if (bottomVerticalY < world.getMaxHeight()) {
                    addDisplayLocation(new IntVector(minX, bottomVerticalY, minZ), sideBlock);
                    addDisplayLocation(new IntVector(minX, bottomVerticalY, maxZ), sideBlock);
                    addDisplayLocation(new IntVector(maxX, bottomVerticalY, minZ), sideBlock);
                    addDisplayLocation(new IntVector(maxX, bottomVerticalY, maxZ), sideBlock);
                }

                // From top corners: 1 block down (internal) - ensure above ground
                int topVerticalY = Math.min(world.getMaxHeight(), maxY - 1);
                if (topVerticalY > world.getMinHeight()) {
                    addDisplayLocation(new IntVector(minX, topVerticalY, minZ), sideBlock);
                    addDisplayLocation(new IntVector(minX, topVerticalY, maxZ), sideBlock);
                    addDisplayLocation(new IntVector(maxX, topVerticalY, minZ), sideBlock);
                    addDisplayLocation(new IntVector(maxX, topVerticalY, maxZ), sideBlock);
                }
            }

            // Add side markers along X axis (north and south sides) - match BlockBoundaryVisualization logic
            // Main loop - start after first step, end before last step/2
            for (int x = Math.max(minX + STEP, minX + STEP); x < maxX - STEP / 2 && x < maxX - STEP / 2; x += STEP) {
                if (x > minX + 1 && x < maxX - 1) {
                    int terrainY = getTerrainYAt(x, minZ, null); // Main claim side markers snap to grass_block level
                    addDisplayLocation(new IntVector(x, terrainY, minZ), sideBlock);  // Bottom north
                    addDisplayLocation(new IntVector(x, terrainY, maxZ), sideBlock);  // Bottom south
                    terrainY = getTerrainYAt(x, minZ, null); // Recalculate for top
                    addDisplayLocation(new IntVector(x, terrainY, minZ), sideBlock);  // Top north
                    addDisplayLocation(new IntVector(x, terrainY, maxZ), sideBlock);  // Top south
                }
            }
            // Additional markers directly adjacent to corners if area is large enough
            if (maxX - minX > 2) {
                int terrainY = getTerrainYAt(minX + 1, minZ, null);
                addDisplayLocation(new IntVector(minX + 1, terrainY, minZ), sideBlock);  // Bottom north corner-adjacent
                addDisplayLocation(new IntVector(minX + 1, terrainY, maxZ), sideBlock);  // Bottom south corner-adjacent
                terrainY = getTerrainYAt(maxX - 1, minZ, null);
                addDisplayLocation(new IntVector(maxX - 1, terrainY, minZ), sideBlock);  // Bottom north corner-adjacent
                addDisplayLocation(new IntVector(maxX - 1, terrainY, maxZ), sideBlock);  // Bottom south corner-adjacent
                terrainY = getTerrainYAt(minX + 1, minZ, null); // Recalculate for top
                addDisplayLocation(new IntVector(minX + 1, terrainY, minZ), sideBlock);  // Top north corner-adjacent
                addDisplayLocation(new IntVector(minX + 1, terrainY, maxZ), sideBlock);  // Top south corner-adjacent
                terrainY = getTerrainYAt(maxX - 1, minZ, null);
                addDisplayLocation(new IntVector(maxX - 1, terrainY, minZ), sideBlock);  // Top north corner-adjacent
                addDisplayLocation(new IntVector(maxX - 1, terrainY, maxZ), sideBlock);  // Top south corner-adjacent
            }

            // Add side markers along Z axis (east and west sides) - match BlockBoundaryVisualization logic
            // Main loop - start after first step, end before last step/2
            for (int z = Math.max(minZ + STEP, minZ + STEP); z < maxZ - STEP / 2 && z < maxZ - STEP / 2; z += STEP) {
                if (z > minZ + 1 && z < maxZ - 1) {
                    int terrainY = getTerrainYAt(minX, z, null);
                    addDisplayLocation(new IntVector(minX, terrainY, z), sideBlock);  // Bottom west
                    addDisplayLocation(new IntVector(maxX, terrainY, z), sideBlock);  // Bottom east
                    terrainY = getTerrainYAt(minX, z, null); // Recalculate for top
                    addDisplayLocation(new IntVector(minX, terrainY, z), sideBlock);  // Top west
                    addDisplayLocation(new IntVector(maxX, terrainY, z), sideBlock);  // Top east
                }
            }
            // Additional markers directly adjacent to corners if area is large enough
            if (maxZ - minZ > 2) {
                int terrainY = getTerrainYAt(minX, minZ + 1, null);
                addDisplayLocation(new IntVector(minX, terrainY, minZ + 1), sideBlock);  // Bottom west corner-adjacent
                addDisplayLocation(new IntVector(maxX, terrainY, minZ + 1), sideBlock);  // Bottom east corner-adjacent
                terrainY = getTerrainYAt(minX, maxZ - 1, null);
                addDisplayLocation(new IntVector(minX, terrainY, maxZ - 1), sideBlock);  // Bottom west corner-adjacent
                addDisplayLocation(new IntVector(maxX, terrainY, maxZ - 1), sideBlock);  // Bottom east corner-adjacent
                terrainY = getTerrainYAt(minX, minZ + 1, null); // Recalculate for top
                addDisplayLocation(new IntVector(minX, terrainY, minZ + 1), sideBlock);  // Top west corner-adjacent
                addDisplayLocation(new IntVector(maxX, terrainY, minZ + 1), sideBlock);  // Top east corner-adjacent
                terrainY = getTerrainYAt(minX, maxZ - 1, null);
                addDisplayLocation(new IntVector(minX, terrainY, maxZ - 1), sideBlock);  // Top west corner-adjacent
                addDisplayLocation(new IntVector(maxX, terrainY, maxZ - 1), sideBlock);  // Top east corner-adjacent
            }
        } else {
            // For 2D claims, show only a single layer at the specified height - match BlockBoundaryVisualization logic
            // Don't add corners here - they're already added in the main draw() method with proper terrain snapping
            
            // Add side markers along X axis (north and south sides) - match BlockBoundaryVisualization logic
            // Main loop - start after first step, end before last step/2
            for (int x = Math.max(minX + STEP, minX + STEP); x < maxX - STEP / 2 && x < maxX - STEP / 2; x += STEP) {
                if (x > minX + 1 && x < maxX - 1) {
                    // Get terrain height for each marker position
                    int markerTerrainY = getTerrainYAt(x, minZ, null); // 2D claims snap to surface level
                    addDisplayLocation(new IntVector(x, markerTerrainY, minZ), sideBlock);  // North side
                    markerTerrainY = getTerrainYAt(x, maxZ, null); // 2D claims snap to surface level
                    addDisplayLocation(new IntVector(x, markerTerrainY, maxZ), sideBlock);  // South side
                }
            }
            // Additional markers directly adjacent to corners if area is large enough
            if (maxX - minX > 2) {
                // Get terrain height for each corner-adjacent position
                int markerTerrainY = getTerrainYAt(minX + 1, minZ, null); // 2D claims snap to surface level
                addDisplayLocation(new IntVector(minX + 1, markerTerrainY, minZ), sideBlock);  // North corner-adjacent
                markerTerrainY = getTerrainYAt(minX + 1, maxZ, null);  // Use correct Z for south side
                addDisplayLocation(new IntVector(minX + 1, markerTerrainY, maxZ), sideBlock);  // South corner-adjacent
                markerTerrainY = getTerrainYAt(maxX - 1, minZ, null); // 2D claims snap to surface level
                addDisplayLocation(new IntVector(maxX - 1, markerTerrainY, minZ), sideBlock);  // North corner-adjacent
                markerTerrainY = getTerrainYAt(maxX - 1, maxZ, null);  // Use correct Z for south side
                addDisplayLocation(new IntVector(maxX - 1, markerTerrainY, maxZ), sideBlock);  // South corner-adjacent
            }

            // Add side markers along Z axis (east and west sides) - match BlockBoundaryVisualization logic
            // Main loop - start after first step, end before last step/2
            for (int z = Math.max(minZ + STEP, minZ + STEP); z < maxZ - STEP / 2 && z < maxZ - STEP / 2; z += STEP) {
                if (z > minZ + 1 && z < maxZ - 1) {
                    // Get terrain height for each marker position
                    int markerTerrainY = getTerrainYAt(minX, z, null); // 2D claims snap to surface level
                    addDisplayLocation(new IntVector(minX, markerTerrainY, z), sideBlock);  // West side
                    markerTerrainY = getTerrainYAt(maxX, z, null); // 2D claims snap to surface level
                    addDisplayLocation(new IntVector(maxX, markerTerrainY, z), sideBlock);  // East side
                }
            }
            // Additional markers directly adjacent to corners if area is large enough
            if (maxZ - minZ > 2) {
                // Get terrain height for each corner-adjacent position
                int markerTerrainY = getTerrainYAt(minX, minZ + 1, null); // 2D claims snap to surface level
                addDisplayLocation(new IntVector(minX, markerTerrainY, minZ + 1), sideBlock);  // West corner-adjacent
                markerTerrainY = getTerrainYAt(maxX, minZ + 1, null);  // Use maxX for east side terrain
                addDisplayLocation(new IntVector(maxX, markerTerrainY, minZ + 1), sideBlock);  // East corner-adjacent
                markerTerrainY = getTerrainYAt(minX, maxZ - 1, null); // 2D claims snap to surface level
                addDisplayLocation(new IntVector(minX, markerTerrainY, maxZ - 1), sideBlock);  // West corner-adjacent
                markerTerrainY = getTerrainYAt(maxX, maxZ - 1, null);  // Use maxX for east side terrain
                addDisplayLocation(new IntVector(maxX, markerTerrainY, maxZ - 1), sideBlock);  // East corner-adjacent
            }
        }
    }
    
    protected void addDisplayLocation(@NotNull IntVector location, @NotNull BlockData blockData) {
        if (location == null || blockData == null) {
            return;
        }
        synchronized (this) {
            // Create a new IntVector with the same x/z but use the exact y position from the location
            displayLocations.put(new IntVector(location.x(), location.y(), location.z()), blockData);
        }
    }

    // Overload that allows tagging the position with a glow color override based on type.
    protected void addDisplayLocation(@NotNull IntVector location, @NotNull BlockData blockData, @NotNull VisualizationType type) {
        addDisplayLocation(location, blockData);
        // If this is an ADMIN_CLAIM corner using glowstone, force orange glow like pumpkins
        if (type == VisualizationType.ADMIN_CLAIM && blockData.getMaterial() == Material.GLOWSTONE) {
            synchronized (this) {
                glowColorOverrides.put(new IntVector(location.x(), location.y(), location.z()), org.bukkit.Color.ORANGE);
            }
        }
    }
    
    
    private BlockData getSideBlockData(VisualizationType type) {
        if (type == VisualizationType.SUBDIVISION_3D) {
            // Use white wool for 3D subdivision extensions
            BlockData data = Material.WHITE_WOOL.createBlockData();
            if (data instanceof Lightable) {
                ((Lightable) data).setLit(true);
            }
            return data;
        } else if (type == VisualizationType.SUBDIVISION) {
            // Use white wool for 2D subdivision extensions (same as 3D)
            BlockData data = Material.WHITE_WOOL.createBlockData();
            if (data instanceof Lightable) {
                ((Lightable) data).setLit(true);
            }
            return data;
        } else if (type == VisualizationType.CLAIM) {
            // Use gold block for main claim side markers and extensions to match corners
            BlockData data = Material.GOLD_BLOCK.createBlockData();
            if (data instanceof Lightable) {
                ((Lightable) data).setLit(true);
            }
            return data;
        } else if (type == VisualizationType.CONFLICT_ZONE) {
            // Use netherrack for conflict zone side markers and extensions
            BlockData data = Material.NETHERRACK.createBlockData();
            if (data instanceof Lightable) {
                ((Lightable) data).setLit(true);
            }
            return data;
        } else if (type == VisualizationType.ADMIN_CLAIM) {
            // Use pumpkins for admin claim side markers and extensions
            BlockData data = Material.PUMPKIN.createBlockData();
            if (data instanceof Lightable) {
                ((Lightable) data).setLit(true);
            }
            return data;
        }
        return getCornerBlockData(type); // Use corner block data for other types
    }

    private void createBlockDisplay(Player player, IntVector pos, BlockData blockData, Set<BlockDisplay> displays) {
        if (pos == null || blockData == null || displays == null || !player.isOnline()) {
            plugin.getLogger().fine("Skipping display creation - invalid parameters");
            return;
        }

        // Get the actual block location from the world
        if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            plugin.getLogger().fine("Skipping display at " + pos.x() + "," + pos.y() + "," + pos.z() + " - chunk not loaded");
            return;
        }

        // Don't create new displays if the player logged out
        if (!player.isOnline()) {
            return;
        }
        
        // Don't create duplicate displays
        if (displays.stream().anyMatch(d ->
            d != null && d.isValid() &&
            d.getLocation().getBlockX() == pos.x() &&
            d.getLocation().getBlockY() == pos.y() &&
            d.getLocation().getBlockZ() == pos.z())) {
            return;
        }

        // Create location at exact block position (no offset)
        // Ensure Y is within world bounds to match the terrain calculation bounds
        int y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight(), pos.y()));
        Location loc = new Location(world, pos.x(), y, pos.z());
        
        // Skip if location is in an unloaded chunk
        if (!loc.getChunk().isLoaded()) {
            return;
        }
        
        // Check for existing displays at this location and remove them
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, 0.1, 0.1, 0.1)) {
            if (entity instanceof BlockDisplay) {
                entity.remove();
            }
        }
        
        // Schedule the display creation on the main thread
        SchedulerUtil.runLaterEntity((Plugin) plugin, player, () -> {
            if (!player.isOnline() || !loc.getChunk().isLoaded()) {
                return;
            }
            
            try {
                // Create and initialize the display entity atomically to avoid global visibility flicker
                BlockDisplay display = world.spawn(loc, BlockDisplay.class, spawned -> {
                    // Make per-player only
                    spawned.setVisibleByDefault(false);
                    // Paper/Folia-compatible per-player visibility
                    player.showEntity((Plugin) plugin, spawned);

                    spawned.setBlock(blockData);
                    spawned.setGlowing(true);
                    spawned.setBrightness(new Display.Brightness(0, 0));
                    spawned.setShadowStrength(0.1f);
                    spawned.setShadowRadius(0.1f);

                    // Slightly reduce the size to prevent Z-fighting
                    float size = 0.98f; // Slightly smaller than full block
                    float offset = (1.0f - size) / 2; // Center the smaller block
                    spawned.setTransformation(new Transformation(
                        new Vector3f(offset, offset, offset),
                        new AxisAngle4f(0, 0, 0, 0),
                        new Vector3f(size, size, size),
                        new AxisAngle4f()
                    ));

                    spawned.setViewRange(96);
                    spawned.setInterpolationDuration(1);

                    // Apply glow color override
                    org.bukkit.Color override;
                    synchronized (GlowingVisualization.this) {
                        override = glowColorOverrides.get(new IntVector(pos.x(), pos.y(), pos.z()));
                    }
                    if (override != null) {
                        spawned.setGlowColorOverride(override);
                    } else if (blockData.getMaterial() == Material.GOLD_BLOCK) {
                        spawned.setGlowColorOverride(org.bukkit.Color.YELLOW);
                    } else if (blockData.getMaterial() == Material.IRON_BLOCK) {
                        spawned.setGlowColorOverride(org.bukkit.Color.WHITE);
                    } else if (blockData.getMaterial() == Material.WHITE_WOOL) {
                        spawned.setGlowColorOverride(org.bukkit.Color.WHITE);
                    } else if (blockData.getMaterial() == Material.DIAMOND_BLOCK) {
                        spawned.setGlowColorOverride(org.bukkit.Color.AQUA);
                    } else if (blockData.getMaterial() == Material.REDSTONE_ORE) {
                        spawned.setGlowColorOverride(org.bukkit.Color.RED);
                    } else if (blockData.getMaterial() == Material.NETHERRACK) {
                        spawned.setGlowColorOverride(org.bukkit.Color.RED);
                    } else if (blockData.getMaterial() == Material.GLOWSTONE) {
                        spawned.setGlowColorOverride(org.bukkit.Color.YELLOW);
                    } else if (blockData.getMaterial() == Material.PUMPKIN) {
                        spawned.setGlowColorOverride(org.bukkit.Color.ORANGE);
                    } else {
                        spawned.setGlowColorOverride(org.bukkit.Color.YELLOW);
                    }
                });

                // Track display for this player
                synchronized (displays) {
                    displays.add(display);
                }

                // Schedule a check to ensure the display is still valid
                SchedulerUtil.runLaterEntity((Plugin) plugin, player, () -> {
                    if (display.isValid() && !player.getWorld().equals(display.getWorld())) {
                        display.remove();
                    }
                }, 20L);

            } catch (Exception e) {
                plugin.getLogger().warning("Error creating block display at " + loc + ": " + e.getMessage());
                if (e.getCause() != null) {
                    e.getCause().printStackTrace();
                }
            }
        }, 1L);
    }
}