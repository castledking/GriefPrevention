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

    protected final boolean waterTransparent;

    /**
     * Construct a new {@code FakeBlockVisualization}.
     *
     * @param world the {@link World} being visualized in
     * @param visualizeFrom the {@link IntVector} representing the world coordinate being visualized from
     * @param height the height of the visualization
     */
    public FakeBlockVisualization(@NotNull World world, @NotNull IntVector visualizeFrom, int height) {
        super(world, visualizeFrom, height);

        // Water is considered transparent based on whether the visualization is initiated in water.
        waterTransparent = visualizeFrom.toBlock(world).getType() == Material.WATER;
    }

    @Override
    protected @NotNull Consumer<@NotNull IntVector> addCornerElements(@NotNull Boundary boundary)
    {
        // For 3D subdivisions, place corner blocks exactly at the coordinates (even in air).
        return switch (boundary.type())
        {
            case SUBDIVISION_3D -> addExactBlockElement(Material.IRON_BLOCK.createBlockData());
            case SUBDIVISION -> addBlockElement(Material.IRON_BLOCK.createBlockData());
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
            case SUBDIVISION -> addBlockElement(Material.WHITE_WOOL.createBlockData());
            case SUBDIVISION_3D -> addExactBlockElement(Material.WHITE_WOOL.createBlockData()); // exact placement for 3D sides
            case INITIALIZE_ZONE -> addBlockElement(Material.DIAMOND_BLOCK.createBlockData());
            case CONFLICT_ZONE -> addBlockElement(Material.NETHERRACK.createBlockData());
            default -> addBlockElement(Material.GOLD_BLOCK.createBlockData());
        };
    }

    @Override
    protected void draw(@NotNull Player player, @NotNull Boundary boundary)
    {
        // For 3D subdivisions, we need to respect Y boundaries and limit visualization
        if (boundary.type() == VisualizationType.SUBDIVISION_3D && boundary.claim() != null)
        {
            drawRespectingYBoundaries(player, boundary);
        }
        else
        {
            // Use the default implementation for all other boundary types
            super.draw(player, boundary);
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
     * Create a {@link Consumer} that adds an appropriate {@link FakeBlockElement} for the given {@link IntVector}.
     *
     * @param fakeData the fake {@link BlockData}
     * @return the function for determining a visible fake block location
     */
    private @NotNull Consumer<@NotNull IntVector> addBlockElement(@NotNull BlockData fakeData)
    {
        return vector -> {
            // Obtain visible location from starting point.
            Block visibleLocation = getVisibleLocation(vector);
            // Create an element using our fake data and the determined block's real data.
            elements.add(new FakeBlockElement(new IntVector(visibleLocation), visibleLocation.getBlockData(), fakeData));
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
     * Find a location that should be visible to players. This causes the visualization to "cling" to the ground.
     *
     * @param vector the {@link IntVector} of the display location
     * @return the located {@link Block}
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
     * Helper method for determining if a {@link Block} is transparent from the top down.
     *
     * @param block the {@code Block}
     * @return true if transparent
     */
    protected boolean isTransparent(@NotNull Block block)
    {
        Material blockMaterial = block.getType();

        // Custom per-material definitions.
        switch (blockMaterial)
        {
            case WATER:
                return waterTransparent;
            case SNOW:
                return false;
        }

        if (blockMaterial.isAir()
                || Tag.FENCES.isTagged(blockMaterial)
                || Tag.FENCE_GATES.isTagged(blockMaterial)
                || Tag.SIGNS.isTagged(blockMaterial)
                || Tag.WALLS.isTagged(blockMaterial)
                || Tag.WALL_SIGNS.isTagged(blockMaterial))
            return true;

        return block.getType().isTransparent();
    }

}
