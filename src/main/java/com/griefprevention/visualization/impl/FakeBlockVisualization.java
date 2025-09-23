package com.griefprevention.visualization.impl;

import com.griefprevention.util.IntVector;
import com.griefprevention.visualization.BlockBoundaryVisualization;
import com.griefprevention.visualization.Boundary;
import com.griefprevention.visualization.BoundaryVisualization;
import com.griefprevention.visualization.VisualizationType;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.util.BoundingBox;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * A {@link BoundaryVisualization} implementation that displays clientside blocks along
 * {@link com.griefprevention.visualization.Boundary Boundaries}.
 */
public class FakeBlockVisualization extends BlockBoundaryVisualization
{

    /**
     * Construct a new {@code FakeBlockVisualization}.
     *
     * @param world the {@link World} being visualized in
     * @param visualizeFrom the {@link IntVector} representing the world coordinate being visualized from
     * @param height the height of the visualization
     */
    public FakeBlockVisualization(@NotNull World world, @NotNull IntVector visualizeFrom, int height) {
        super(world, visualizeFrom, height);
    }

    @Override
    protected @NotNull Consumer<@NotNull IntVector> addCornerElements(@NotNull Boundary boundary)
    {
        // For 3D subdivisions, place corner blocks exactly at the coordinates (even in air).
        return switch (boundary.type())
        {
            case SUBDIVISION_3D -> addExactBlockElement(Material.IRON_BLOCK.createBlockData());
            case SUBDIVISION -> addExactBlockElement(Material.IRON_BLOCK.createBlockData());
            case ADMIN_CLAIM -> {
                BlockData orangeGlass = Material.GLOWSTONE.createBlockData();
                yield addBlockElement(orangeGlass);
            }
            case INITIALIZE_ZONE -> addBlockElement(Material.DIAMOND_BLOCK.createBlockData());
            case CONFLICT_ZONE -> {
                BlockData fakeData = Material.REDSTONE_ORE.createBlockData();
                ((Lightable) fakeData).setLit(true);
                yield addBlockElement(fakeData);
            }
            default -> addBlockElement(Material.GLOWSTONE.createBlockData());
        };
    }


    @Override
    protected @NotNull Consumer<@NotNull IntVector> addSideElements(@NotNull Boundary boundary)
    {
        // Determine BlockData from boundary type to cache for reuse in function.
        return switch (boundary.type())
        {
            case ADMIN_CLAIM -> addBlockElement(Material.PUMPKIN.createBlockData());
            case SUBDIVISION -> addExactBlockElement(Material.WHITE_WOOL.createBlockData());
            case SUBDIVISION_3D -> addExactBlockElement(Material.WHITE_WOOL.createBlockData()); // exact placement for 3D sides
            case INITIALIZE_ZONE -> addBlockElement(Material.DIAMOND_BLOCK.createBlockData());
            case CONFLICT_ZONE -> addBlockElement(Material.NETHERRACK.createBlockData());
            default -> addBlockElement(Material.GOLD_BLOCK.createBlockData());
        };
    }

    private BoundingBox displayZone; // Store display zone for use in helper methods

    @Override
    protected void draw(@NotNull Player player, @NotNull Boundary boundary)
    {
        // For 3D subdivisions, we need to respect Y boundaries and limit visualization
        if (boundary.type() == VisualizationType.SUBDIVISION_3D && boundary.claim() != null)
        {
            drawRespectingYBoundaries(player, boundary);
        }
        // For 2D subdivisions, use custom logic similar to 3D but at terrain level
        else if (boundary.type() == VisualizationType.SUBDIVISION && boundary.claim() != null)
        {
            draw2DSubdivision(player, boundary);
        }
        // For main claims, use custom logic to ensure proper terrain-level placement
        else if (boundary.type() == VisualizationType.CLAIM && boundary.claim() != null)
        {
            drawMainClaim(player, boundary);
        }
        else
        {
            // Use the default implementation for all other boundary types
            super.draw(player, boundary);
        }
    }

    /**
     * Draw a main claim boundary at terrain height with proper corner placement
     */
    private void drawMainClaim(@NotNull Player player, @NotNull Boundary boundary) {
        BoundingBox area = boundary.bounds();

        // Replicate display zone logic from parent class
        final int displayZoneRadius = 75;
        IntVector visualizeFrom = new IntVector(player.getEyeLocation().getBlockX(),
                                               player.getEyeLocation().getBlockY(),
                                               player.getEyeLocation().getBlockZ());
        int baseX = visualizeFrom.x();
        int baseZ = visualizeFrom.z();
        BoundingBox displayZoneArea = new BoundingBox(
                new IntVector(baseX - displayZoneRadius, world.getMinHeight(), baseZ - displayZoneRadius),
                new IntVector(baseX + displayZoneRadius, world.getMaxHeight(), baseZ + displayZoneRadius));

        // Trim to area - allows for simplified display containment check later.
        BoundingBox displayZone = displayZoneArea.intersection(area);

        // If area is not inside display zone, there is nothing to display.
        if (displayZone == null) return;

        Consumer<@NotNull IntVector> addCorner = addCornerElements(boundary);
        Consumer<@NotNull IntVector> addSide = addSideElements(boundary);

        int minX = area.getMinX();
        int maxX = area.getMaxX();
        int minZ = area.getMinZ();
        int maxZ = area.getMaxZ();
        final int STEP = 10; // Match the step size from parent class

        // Get terrain height for corners
        int corner1Y = getSurfaceYAt(minX, minZ, player); // SW corner
        int corner2Y = getSurfaceYAt(maxX, minZ, player); // SE corner
        int corner3Y = getSurfaceYAt(minX, maxZ, player); // NW corner
        int corner4Y = getSurfaceYAt(maxX, maxZ, player); // NE corner

        // Add corners at terrain level
        addDisplayedForMainClaim(displayZone, new IntVector(minX, corner1Y, minZ), addCorner, boundary.type()); // SW
        addDisplayedForMainClaim(displayZone, new IntVector(maxX, corner2Y, minZ), addCorner, boundary.type()); // SE
        addDisplayedForMainClaim(displayZone, new IntVector(minX, corner3Y, maxZ), addCorner, boundary.type()); // NW
        addDisplayedForMainClaim(displayZone, new IntVector(maxX, corner4Y, maxZ), addCorner, boundary.type()); // NE

        // Add side markers along X axis (north and south sides) - following terrain
        for (int x = Math.max(minX + STEP, minX + STEP); x < maxX - STEP / 2 && x < maxX - STEP / 2; x += STEP) {
            if (x > minX + 1 && x < maxX - 1) {
                int terrainY = getSurfaceYAt(x, minZ, player);
                addDisplayedForMainClaim(displayZone, new IntVector(x, terrainY, minZ), addSide, boundary.type()); // North side
                terrainY = getSurfaceYAt(x, maxZ, player);
                addDisplayedForMainClaim(displayZone, new IntVector(x, terrainY, maxZ), addSide, boundary.type()); // South side
            }
        }

        // Additional markers directly adjacent to corners if area is large enough
        if (maxX - minX > 2) {
            int terrainY = getSurfaceYAt(minX + 1, minZ, player);
            addDisplayedForMainClaim(displayZone, new IntVector(minX + 1, terrainY, minZ), addSide, boundary.type());
            terrainY = getSurfaceYAt(minX + 1, maxZ, player);
            addDisplayedForMainClaim(displayZone, new IntVector(minX + 1, terrainY, maxZ), addSide, boundary.type());
            terrainY = getSurfaceYAt(maxX - 1, minZ, player);
            addDisplayedForMainClaim(displayZone, new IntVector(maxX - 1, terrainY, minZ), addSide, boundary.type());
            terrainY = getSurfaceYAt(maxX - 1, maxZ, player);
            addDisplayedForMainClaim(displayZone, new IntVector(maxX - 1, terrainY, maxZ), addSide, boundary.type());
        }

        // Add side markers along Z axis (east and west sides) - following terrain
        for (int z = Math.max(minZ + STEP, minZ + STEP); z < maxZ - STEP / 2 && z < maxZ - 2; z += STEP) {
            if (z > minZ + 1 && z < maxZ - 1) {
                int terrainY = getSurfaceYAt(minX, z, player);
                addDisplayedForMainClaim(displayZone, new IntVector(minX, terrainY, z), addSide, boundary.type()); // West side
                terrainY = getSurfaceYAt(maxX, z, player);
                addDisplayedForMainClaim(displayZone, new IntVector(maxX, terrainY, z), addSide, boundary.type()); // East side
            }
        }

        // Additional markers directly adjacent to corners if area is large enough
        if (maxZ - minZ > 2) {
            int terrainY = getSurfaceYAt(minX, minZ + 1, player);
            addDisplayedForMainClaim(displayZone, new IntVector(minX, terrainY, minZ + 1), addSide, boundary.type());
            terrainY = getSurfaceYAt(maxX, minZ + 1, player);
            addDisplayedForMainClaim(displayZone, new IntVector(maxX, terrainY, minZ + 1), addSide, boundary.type());
            terrainY = getSurfaceYAt(minX, maxZ - 1, player);
            addDisplayedForMainClaim(displayZone, new IntVector(minX, terrainY, maxZ - 1), addSide, boundary.type());
            terrainY = getSurfaceYAt(maxX, maxZ - 1, player);
            addDisplayedForMainClaim(displayZone, new IntVector(maxX, terrainY, maxZ - 1), addSide, boundary.type());
        }
    }

    /**
     * Add a display element if accessible (override to use terrain-level placement for main claims)
     */
    protected void addDisplayedForMainClaim(@NotNull BoundingBox displayZone, @NotNull IntVector coordinate, @NotNull Consumer<@NotNull IntVector> addElement, @NotNull VisualizationType boundaryType)
    {
        if (isAccessible(displayZone, coordinate)) {
            // Use the original addElement consumer instead of overriding with side block data
            addElement.accept(coordinate);
        }
    }

    /**
     * Draw a 2D subdivision boundary at terrain height with proper corner placement
     */
    private void draw2DSubdivision(@NotNull Player player, @NotNull Boundary boundary) {
        BoundingBox area = boundary.bounds();
        Claim claim = boundary.claim();

        if (claim == null) {
            // Fallback to default behavior if claim is null
            super.draw(player, boundary);
            return;
        }

        // Replicate display zone logic from 3D case
        final int displayZoneRadius = 75;
        IntVector visualizeFrom = new IntVector(player.getEyeLocation().getBlockX(),
                                               player.getEyeLocation().getBlockY(),
                                               player.getEyeLocation().getBlockZ());
        int baseX = visualizeFrom.x();
        int baseZ = visualizeFrom.z();
        BoundingBox displayZoneArea = new BoundingBox(
                new IntVector(baseX - displayZoneRadius, world.getMinHeight(), baseZ - displayZoneRadius),
                new IntVector(baseX + displayZoneRadius, world.getMaxHeight(), baseZ + displayZoneRadius));

        // Trim to area - allows for simplified display containment check later.
        this.displayZone = displayZoneArea.intersection(area);

        // If area is not inside display zone, there is nothing to display.
        if (this.displayZone == null) return;

        // Get coordinates for 2D subdivision
        int minX = area.getMinX();
        int maxX = area.getMaxX();
        int minZ = area.getMinZ();
        int maxZ = area.getMaxZ();

        // Get surface Y for each corner to properly follow terrain
        int nwY = getSurfaceYAt(minX, minZ, player);  // NW corner
        int swY = getSurfaceYAt(minX, maxZ, player);  // SW corner
        int neY = getSurfaceYAt(maxX, minZ, player);  // NE corner
        int seY = getSurfaceYAt(maxX, maxZ, player);  // SE corner

        // Render exactly 4 iron corners and 8 white wool side blocks (one off each side of each corner toward the interior)
        BlockData iron = Material.IRON_BLOCK.createBlockData();
        BlockData wool = Material.WHITE_WOOL.createBlockData();

        // Place iron corner blocks
        addDisplayLocation(new IntVector(minX, nwY, minZ), iron, boundary.type()); // NW
        addDisplayLocation(new IntVector(minX, swY, maxZ), iron, boundary.type()); // SW
        addDisplayLocation(new IntVector(maxX, neY, minZ), iron, boundary.type()); // NE
        addDisplayLocation(new IntVector(maxX, seY, maxZ), iron, boundary.type()); // SE

        // From each corner, place one white wool block along the interior X side and one along the interior Z side
        // NW corner interior directions: +X, +Z
        if (minX + 1 <= maxX) {
            int stubY = getSurfaceYAt(minX + 1, minZ, player);
            addDisplayLocation(new IntVector(minX + 1, stubY, minZ), wool, boundary.type());
        }
        if (minZ + 1 <= maxZ) {
            int stubY = getSurfaceYAt(minX, minZ + 1, player);
            addDisplayLocation(new IntVector(minX, stubY, minZ + 1), wool, boundary.type());
        }

        // SW corner interior directions: +X, -Z
        if (minX + 1 <= maxX) {
            int stubY = getSurfaceYAt(minX + 1, maxZ, player);
            addDisplayLocation(new IntVector(minX + 1, stubY, maxZ), wool, boundary.type());
        }
        if (maxZ - 1 >= minZ) {
            int stubY = getSurfaceYAt(minX, maxZ - 1, player);
            addDisplayLocation(new IntVector(minX, stubY, maxZ - 1), wool, boundary.type());
        }

        // NE corner interior directions: -X, +Z
        if (maxX - 1 >= minX) {
            int stubY = getSurfaceYAt(maxX - 1, minZ, player);
            addDisplayLocation(new IntVector(maxX - 1, stubY, minZ), wool, boundary.type());
        }
        if (minZ + 1 <= maxZ) {
            int stubY = getSurfaceYAt(maxX, minZ + 1, player);
            addDisplayLocation(new IntVector(maxX, stubY, minZ + 1), wool, boundary.type());
        }

        // SE corner interior directions: -X, -Z
        if (maxX - 1 >= minX) {
            int stubY = getSurfaceYAt(maxX - 1, maxZ, player);
            addDisplayLocation(new IntVector(maxX - 1, stubY, maxZ), wool, boundary.type());
        }
        if (maxZ - 1 >= minZ) {
            int stubY = getSurfaceYAt(maxX, maxZ - 1, player);
            addDisplayLocation(new IntVector(maxX, stubY, maxZ - 1), wool, boundary.type());
        }
    }

    /**
     * Gets the surface Y coordinate at a specific x,z position with grass block handling
     * This ensures visualizations snap to grass_block instead of grass, matching GlowingVisualization
     */
    private int getSurfaceYAt(int x, int z, Player player) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return player != null ? player.getLocation().getBlockY() - 1 : world.getMinHeight() + 63;
        }

        // Start from the highest block at this x,z position
        int y = world.getMaxHeight() - 1;

        // Find the highest non-air block (including water as valid surface)
        while (y >= world.getMinHeight()) {
            Block block = world.getBlockAt(x, y, z);

            // If we find a non-air block, we've found the surface
            if (block.getType() != Material.AIR) {
                // For transparent blocks (except water), find the actual surface below
                if (isTransparent(block) && block.getType() != Material.WATER) {
                    return findSurfaceBelowTransparentBlocks(x, z, y);
                }
                return y; // Return the Y of the highest non-air block (e.g., water, glass, or solid)
            }

            y--;
        }

        // If we get here, return the minimum height
        return world.getMinHeight();
    }

    /**
     * Finds the actual surface Y coordinate below a stack of transparent blocks
     * @param x The x coordinate
     * @param z The z coordinate
     * @param startY The Y coordinate where transparent blocks start
     * @return The Y coordinate of the actual surface below the transparent blocks
     */
    private int findSurfaceBelowTransparentBlocks(int x, int z, int startY) {
        int y = startY;

        // Count how many transparent blocks are stacked
        int transparentBlockCount = 0;
        while (y >= world.getMinHeight() && isTransparent(world.getBlockAt(x, y, z)) && world.getBlockAt(x, y, z).getType() != Material.WATER) {
            transparentBlockCount++;
            y--;
        }

        // The surface is the first non-transparent block we find
        Block surfaceBlock = world.getBlockAt(x, y, z);

        // If we found a valid surface block, return its Y coordinate
        if (surfaceBlock.getType() != Material.AIR) {
            return y;
        }

        // If no valid surface found, return the original Y minus the transparent block count
        // This handles cases where transparent blocks are floating above air
        return startY - transparentBlockCount;
    }

    /**
     * Add a display element if accessible (override to use terrain-level placement for main claims)
     */
    protected void addDisplayedForMainClaim(@NotNull BoundingBox displayZone, @NotNull IntVector coordinate, @NotNull Consumer<@NotNull IntVector> addElement)
    {
        if (isAccessible(displayZone, coordinate)) {
            addElement.accept(coordinate);
        }
    }

    /**
     * Gets the corner block data for the given visualization type
     */
    private BlockData getCornerBlockData(VisualizationType type) {
        return switch (type) {
            case CLAIM -> Material.GLOWSTONE.createBlockData(); // Main claim corners should be glowstone
            case SUBDIVISION_3D, SUBDIVISION -> Material.IRON_BLOCK.createBlockData();
            case INITIALIZE_ZONE -> Material.DIAMOND_BLOCK.createBlockData();
            case CONFLICT_ZONE -> Material.NETHERRACK.createBlockData();
            case ADMIN_CLAIM -> Material.GLOWSTONE.createBlockData();
            default -> Material.GLOWSTONE.createBlockData();
        };
    }

    /**
     * Gets the side block data for the given visualization type
     */
    private BlockData getSideBlockData(VisualizationType type) {
        return switch (type) {
            case SUBDIVISION_3D, SUBDIVISION -> Material.WHITE_WOOL.createBlockData();
            case CLAIM -> Material.GOLD_BLOCK.createBlockData();
            case CONFLICT_ZONE -> Material.NETHERRACK.createBlockData();
            case ADMIN_CLAIM -> Material.PUMPKIN.createBlockData();
            default -> getCornerBlockData(type);
        };
    }

    /**
     * Adds a corner block with extensions in the specified directions
     */
    private void addCornerWithExtensions(int x, int y, int z, int dx, int dz, int dy, VisualizationType type) {
        // Add the corner block
        BlockData cornerBlock = getCornerBlockData(type);
        addDisplayLocation(new IntVector(x, y, z), cornerBlock, type);

        // For subdivisions (both 2D and 3D), add horizontal extensions from corners
        if (type == VisualizationType.SUBDIVISION || type == VisualizationType.SUBDIVISION_3D) {
            // Add horizontal extensions in the correct direction (outward from claim)
            // Invert the direction to extend outward from the claim boundary
            if (dx != 0) {
                addDisplayLocation(new IntVector(x - dx, y, z), getCornerBlockData(type), type);
            }
            if (dz != 0) {
                addDisplayLocation(new IntVector(x, y, z - dz), getCornerBlockData(type), type);
            }
        }
    }

    /**
     * Add a display element if accessible.
     */
    private void addDisplayLocation(@NotNull IntVector coordinate, @NotNull BlockData blockData, @NotNull VisualizationType type) {
        // Check if coordinate is within display zone
        if (this.displayZone != null &&
            coordinate.x() >= this.displayZone.getMinX() && coordinate.x() <= this.displayZone.getMaxX() &&
            coordinate.y() >= this.displayZone.getMinY() && coordinate.y() <= this.displayZone.getMaxY() &&
            coordinate.z() >= this.displayZone.getMinZ() && coordinate.z() <= this.displayZone.getMaxZ()) {
            // For 2D subdivisions, use exact placement to avoid moving blocks from glass surfaces
            // Other visualization types use getVisibleLocation for terrain snapping
            if (blockData.getMaterial() == Material.IRON_BLOCK || blockData.getMaterial() == Material.WHITE_WOOL) {
                // This is a 2D subdivision - use exact placement
                Block exactLocation = coordinate.toBlock(world);
                elements.add(new FakeBlockElement(coordinate, exactLocation.getBlockData(), blockData));
            } else {
                // Other visualization types - use terrain snapping
                Block visibleLocation = getVisibleLocation(coordinate);
                int x = coordinate.x();
                int y = visibleLocation.getY();
                int z = coordinate.z();
                elements.add(new FakeBlockElement(new IntVector(x, y, z), visibleLocation.getBlockData(), blockData));
            }
        }
    }

    /**
     * Draw a 3D subdivision boundary while respecting Y boundaries and limiting visualization
     * to only one block above and below the subclaim's Y limits.
     */
    private void drawRespectingYBoundaries(@NotNull Player player, @NotNull Boundary boundary)
    {
        BoundingBox area = boundary.bounds();
        Claim claim = boundary.claim();
        
        if (claim == null || !claim.is3D()) 
        {
            // Fallback to default behavior if claim is null or not 3D
            super.draw(player, boundary);
            return;
        }

        // Get the Y boundaries of the 3D subclaim
        int claimMinY = area.getMinY();
        int claimMaxY = area.getMaxY();

        // Replicate display zone logic (default values: displayZoneRadius=75)
        final int displayZoneRadius = 75;
        IntVector visualizeFrom = new IntVector(player.getEyeLocation().getBlockX(), 
                                               player.getEyeLocation().getBlockY(), 
                                               player.getEyeLocation().getBlockZ());
        // For 3D subdivisions, ensure we include the entire claim's vertical span so both top and bottom are shown.
        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight();
        int minShowY = Math.max(worldMinY, claimMinY - 1);
        int maxShowY = Math.min(worldMaxY, claimMaxY + 1);
        int baseX = visualizeFrom.x();
        int baseZ = visualizeFrom.z();
        BoundingBox displayZoneArea = new BoundingBox(
                new IntVector(baseX - displayZoneRadius, minShowY, baseZ - displayZoneRadius),
                new IntVector(baseX + displayZoneRadius, maxShowY, baseZ + displayZoneRadius));
        
        // Trim to area - allows for simplified display containment check later.
        BoundingBox displayZone = displayZoneArea.intersection(area);

        // If area is not inside display zone, there is nothing to display.
        if (displayZone == null) return;

        Consumer<@NotNull IntVector> addCorner = addCornerElements(boundary);
        Consumer<@NotNull IntVector> addSide = addSideElements(boundary);

        // We only render at the top and bottom Y boundaries for 3D subdivisions.
        int[] yLevels = new int[] { claimMinY, claimMaxY };
        for (int y : yLevels)
        {
            if (y < world.getMinHeight() || y > world.getMaxHeight()) continue;

            // Short directional side markers next to corners only (no full ring)
            if (area.getLength() > 2)
            {
                addDisplayed3D(displayZone, new IntVector(area.getMinX() + 1, y, area.getMaxZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMinX() + 1, y, area.getMinZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX() - 1, y, area.getMaxZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX() - 1, y, area.getMinZ()), addSide);
            }
            if (area.getWidth() > 2)
            {
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), y, area.getMinZ() + 1), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), y, area.getMinZ() + 1), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), y, area.getMaxZ() - 1), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), y, area.getMaxZ() - 1), addSide);
            }

            // Corners at this Y level
            addDisplayed3D(displayZone, new IntVector(area.getMinX(), y, area.getMaxZ()), addCorner);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX(), y, area.getMaxZ()), addCorner);
            addDisplayed3D(displayZone, new IntVector(area.getMinX(), y, area.getMinZ()), addCorner);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX(), y, area.getMinZ()), addCorner);

            // Vertical indicator: exactly one white wool block above bottom corners and below top corners
            int verticalY;
            if (y == claimMinY) {
                verticalY = y + 1; // one block above bottom ring
            } else if (y == claimMaxY) {
                verticalY = y - 1; // one block below top ring
            } else {
                continue; // shouldn't happen, but guards future changes
            }
            if (verticalY >= world.getMinHeight() && verticalY <= world.getMaxHeight()) {
                // reuse exact-placement white wool consumer for 3D sides
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), verticalY, area.getMaxZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), verticalY, area.getMaxZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), verticalY, area.getMinZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), verticalY, area.getMinZ()), addSide);
            }
        }
    }

    /**
     * Add a display element if accessible (3D version that doesn't call parent's addDisplayed).
     */
    private void addDisplayed3D(
            @NotNull BoundingBox displayZone,
            @NotNull IntVector coordinate,
            @NotNull Consumer<@NotNull IntVector> addElement)
    {
        // Check if coordinate is within display zone
        if (coordinate.x() >= displayZone.getMinX() && coordinate.x() <= displayZone.getMaxX() &&
            coordinate.y() >= displayZone.getMinY() && coordinate.y() <= displayZone.getMaxY() &&
            coordinate.z() >= displayZone.getMinZ() && coordinate.z() <= displayZone.getMaxZ())
        {
            addElement.accept(coordinate);
        }
    }

    /**
     * Create a {@link Consumer} that adds a {@link FakeBlockElement} at a terrain-snapped location (not exact coordinates).
     * This is used for most visualization types to ensure blocks appear at visible surface levels.
     *
     * @param fakeData the fake {@link BlockData}
     * @return the function for placing a fake block at a terrain-snapped location
     */
    private @NotNull Consumer<@NotNull IntVector> addBlockElement(@NotNull BlockData fakeData)
    {
        return vector -> {
            Block visibleLocation = getVisibleLocation(vector);
            int x = vector.x();
            int y = visibleLocation.getY();
            int z = vector.z();
            elements.add(new FakeBlockElement(new IntVector(x, y, z), visibleLocation.getBlockData(), fakeData));
        };
    }

    /**
     * Create a {@link Consumer} that adds a {@link FakeBlockElement} exactly at the given {@link IntVector}
     * coordinate without searching for a nearby visible ground block. This is used for 3D subdivision corners
     * so they are highlighted even when floating in air.
     *
     * @param fakeData the fake {@link BlockData}
     * @return the function for placing a fake block at the exact location
     */
    private @NotNull Consumer<@NotNull IntVector> addExactBlockElement(@NotNull BlockData fakeData)
    {
        return vector -> {
            Block exactLocation = vector.toBlock(world);
            elements.add(new FakeBlockElement(new IntVector(exactLocation), exactLocation.getBlockData(), fakeData));
        };
    }

    /**
     * Checks if a material is a glass block that should be treated as a solid surface
     * @param material The material to check
     * @return true if the material is a glass block
     */
    private boolean isGlassBlock(Material material) {
        return material == Material.GLASS ||
               material == Material.GLASS_PANE ||
               material == Material.TINTED_GLASS ||
               material.name().contains("GLASS") ||
               material.name().endsWith("_GLASS") ||
               material.name().endsWith("_GLASS_PANE");
    }

    /**
     * Find a location that should be visible to players. This causes the visualization to "cling" to the ground.
     *
     * @param vector the {@link IntVector} of the display location
     * @return the located {@link Block}
     */
    private Block getVisibleLocation(@NotNull IntVector vector)
    {
        Block block = vector.toBlock(world);

        // Special handling for water surfaces
        if (block.getType() == Material.WATER) {
            return block; // Stay at water surface
        }

        // Check if the block is glass - if so, treat it as solid and stay on it
        if (isGlassBlock(block.getType())) {
            return block; // Stay at glass surface
        }

        BlockFace direction = (isTransparent(block)) ? BlockFace.DOWN : BlockFace.UP;

        while (block.getY() >= world.getMinHeight() &&
                block.getY() < world.getMaxHeight() - 1 &&
                (!isTransparent(block.getRelative(BlockFace.UP)) || isTransparent(block)) &&
                block.getType() != Material.WATER &&
                !isGlassBlock(block.getType()))
        {
            block = block.getRelative(direction);
        }

        return block;
    }

    /**
     * Helper method for determining if a {@link Block} is transparent from the top down.
     *
     * @param block the {@code Block}
     * @return true if transparent
     */
    protected boolean isTransparent(@NotNull Block block)
    {
        Material blockMaterial = block.getType();

        // Check if it's glass first - glass should be treated as solid for visualization purposes
        if (isGlassBlock(blockMaterial)) {
            return false; // Glass is not transparent for visualization purposes
        }

        // Custom per-material definitions matching GlowingVisualization
        switch (blockMaterial)
        {
            case WATER:
                return true; // Treat water as transparent so visualizations float on water surface
            case SNOW_BLOCK:
                return true;
            case SNOW:
                return false;
            default:
                // Fall through to the general logic below
                break;
        }

        if (blockMaterial.isAir()
                || Tag.FENCES.isTagged(blockMaterial)
                || Tag.FENCE_GATES.isTagged(blockMaterial)
                || Tag.SIGNS.isTagged(blockMaterial)
                || Tag.WALLS.isTagged(blockMaterial)
                || Tag.WALL_SIGNS.isTagged(blockMaterial))
            return true;

        return !block.getType().isOccluding();
    }

}
