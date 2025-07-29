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
        return addBlockElement(switch (boundary.type())
        {
            case SUBDIVISION -> Material.IRON_BLOCK.createBlockData();
            case SUBDIVISION_3D -> Material.EMERALD_BLOCK.createBlockData(); // Distinctive green corners for 3D subdivisions
            case INITIALIZE_ZONE -> Material.DIAMOND_BLOCK.createBlockData();
            case CONFLICT_ZONE -> {
                BlockData fakeData = Material.REDSTONE_ORE.createBlockData();
                ((Lightable) fakeData).setLit(true);
                yield fakeData;
            }
            default -> Material.GLOWSTONE.createBlockData();
        });
    }


    @Override
    protected @NotNull Consumer<@NotNull IntVector> addSideElements(@NotNull Boundary boundary)
    {
        // Determine BlockData from boundary type to cache for reuse in function.
        return addBlockElement(switch (boundary.type())
        {
            case ADMIN_CLAIM -> Material.PUMPKIN.createBlockData();
            case SUBDIVISION -> Material.WHITE_WOOL.createBlockData();
            case SUBDIVISION_3D -> Material.LIME_WOOL.createBlockData(); // Distinctive lime green sides for 3D subdivisions
            case INITIALIZE_ZONE -> Material.DIAMOND_BLOCK.createBlockData();
            case CONFLICT_ZONE -> Material.NETHERRACK.createBlockData();
            default -> Material.GOLD_BLOCK.createBlockData();
        });
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
        
        // Allow visualization one block above and below the claim boundaries
        int visualizationMinY = Math.max(claimMinY - 1, world.getMinHeight());
        int visualizationMaxY = Math.min(claimMaxY + 1, world.getMaxHeight());
        
        // Determine the best height for visualization within the allowed range
        int playerY = player.getEyeLocation().getBlockY();
        int visualizationHeight;
        
        if (playerY >= visualizationMinY && playerY <= visualizationMaxY)
        {
            // Player is within or near the 3D claim, use their Y level
            visualizationHeight = playerY;
        }
        else if (playerY < visualizationMinY)
        {
            // Player is below the claim, show at the bottom boundary
            visualizationHeight = visualizationMinY;
        }
        else
        {
            // Player is above the claim, show at the top boundary
            visualizationHeight = visualizationMaxY;
        }

        // Replicate display zone logic (default values: step=10, displayZoneRadius=75)
        final int step = 10;
        final int displayZoneRadius = 75;
        IntVector visualizeFrom = new IntVector(player.getEyeLocation().getBlockX(), 
                                               player.getEyeLocation().getBlockY(), 
                                               player.getEyeLocation().getBlockZ());
        BoundingBox displayZoneArea = new BoundingBox(
                visualizeFrom.add(-displayZoneRadius, -displayZoneRadius, -displayZoneRadius),
                visualizeFrom.add(displayZoneRadius, displayZoneRadius, displayZoneRadius));
        
        // Trim to area - allows for simplified display containment check later.
        BoundingBox displayZone = displayZoneArea.intersection(area);

        // If area is not inside display zone, there is nothing to display.
        if (displayZone == null) return;

        Consumer<@NotNull IntVector> addCorner = addCornerElements(boundary);
        Consumer<@NotNull IntVector> addSide = addSideElements(boundary);

        // North and south boundaries
        for (int x = Math.max(area.getMinX() + step, displayZone.getMinX()); x < area.getMaxX() - step / 2 && x < displayZone.getMaxX(); x += step)
        {
            addDisplayed3D(displayZone, new IntVector(x, visualizationHeight, area.getMaxZ()), addSide);
            addDisplayed3D(displayZone, new IntVector(x, visualizationHeight, area.getMinZ()), addSide);
        }
        // First and last step are always directly adjacent to corners
        if (area.getLength() > 2)
        {
            addDisplayed3D(displayZone, new IntVector(area.getMinX() + 1, visualizationHeight, area.getMaxZ()), addSide);
            addDisplayed3D(displayZone, new IntVector(area.getMinX() + 1, visualizationHeight, area.getMinZ()), addSide);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX() - 1, visualizationHeight, area.getMaxZ()), addSide);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX() - 1, visualizationHeight, area.getMinZ()), addSide);
        }

        // East and west boundaries
        for (int z = Math.max(area.getMinZ() + step, displayZone.getMinZ()); z < area.getMaxZ() - step / 2 && z < displayZone.getMaxZ(); z += step)
        {
            addDisplayed3D(displayZone, new IntVector(area.getMinX(), visualizationHeight, z), addSide);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX(), visualizationHeight, z), addSide);
        }
        if (area.getWidth() > 2)
        {
            addDisplayed3D(displayZone, new IntVector(area.getMinX(), visualizationHeight, area.getMinZ() + 1), addSide);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX(), visualizationHeight, area.getMinZ() + 1), addSide);
            addDisplayed3D(displayZone, new IntVector(area.getMinX(), visualizationHeight, area.getMaxZ() - 1), addSide);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX(), visualizationHeight, area.getMaxZ() - 1), addSide);
        }

        // Add corners last to override any other elements created by very small claims.
        addDisplayed3D(displayZone, new IntVector(area.getMinX(), visualizationHeight, area.getMaxZ()), addCorner);
        addDisplayed3D(displayZone, new IntVector(area.getMaxX(), visualizationHeight, area.getMaxZ()), addCorner);
        addDisplayed3D(displayZone, new IntVector(area.getMinX(), visualizationHeight, area.getMinZ()), addCorner);
        addDisplayed3D(displayZone, new IntVector(area.getMaxX(), visualizationHeight, area.getMinZ()), addCorner);
        
        // Add vertical indicators at the Y boundaries to show the height limits
        // Only add these if the claim has significant height (more than 2 blocks)
        if (claimMaxY - claimMinY > 2)
        {
            // Add height indicators at corners to show the vertical extent
            if (visualizationHeight != claimMinY && claimMinY >= world.getMinHeight())
            {
                // Show bottom boundary indicators
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), claimMinY, area.getMaxZ()), addCorner);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), claimMinY, area.getMaxZ()), addCorner);
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), claimMinY, area.getMinZ()), addCorner);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), claimMinY, area.getMinZ()), addCorner);
            }
            
            if (visualizationHeight != claimMaxY && claimMaxY <= world.getMaxHeight())
            {
                // Show top boundary indicators
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), claimMaxY, area.getMaxZ()), addCorner);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), claimMaxY, area.getMaxZ()), addCorner);
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), claimMaxY, area.getMinZ()), addCorner);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), claimMaxY, area.getMinZ()), addCorner);
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
