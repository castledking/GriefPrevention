package com.griefprevention.visualization;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A data holder defining an area to be visualized.
 */
public record Boundary(
        @NotNull BoundingBox bounds,
        @NotNull VisualizationType type,
        @Nullable Claim claim)
{

    /**
     * Construct a new {@code Boundary} for a {@link BoundingBox} with the given visualization style.
     *
     * @param bounds the {@code BoundingBox}
     * @param type the {@link VisualizationType}
     */
    public Boundary(@NotNull BoundingBox bounds, @NotNull VisualizationType type)
    {
        this(bounds, type, null);
    }

    /**
     * Construct a new {@code Boundary} for a {@link Claim} with the given visualization style.
     *
     * @param claim the {@code Claim}
     * @param type the {@link VisualizationType}
     */
    public Boundary(@NotNull Claim claim, @NotNull VisualizationType type)
    {
        this(createBoundingBox(claim), type, claim);
    }
    
    /**
     * Create a BoundingBox for a claim, respecting 3D boundaries if applicable.
     *
     * @param claim the claim to create a bounding box for
     * @return the bounding box
     */
    private static @NotNull BoundingBox createBoundingBox(@NotNull Claim claim) {
        // For 3D subdivisions, use the actual Y coordinates
        if (claim.is3D() || (claim.parent != null && claim.parent.is3D())) {
            Claim parentClaim = claim.parent != null ? claim.parent : claim;
            return new BoundingBox(
                claim.getLesserBoundaryCorner(),
                claim.getGreaterBoundaryCorner()
            );
        }
        // For regular claims, use the default behavior (full height)
        return new BoundingBox(claim);
    }

    /**
     * Get the {@link Claim} represented by the {@code Boundary}, if any.
     *
     * @return the {@code Claim} or {@code null} if not present
     */
    @Override
    public @Nullable Claim claim()
    {
        return claim;
    }

}
