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

import com.griefprevention.claims.ClaimBounds;
import com.griefprevention.claims.ClaimSnapshot;
import com.griefprevention.claims.ClaimTrustLevel;
import com.griefprevention.claims.ClaimTrustSnapshot;
import com.griefprevention.geometry.OrthogonalPoint2i;
import com.griefprevention.geometry.OrthogonalPolygon;
import com.griefprevention.compat.MaterialCompat;
import me.ryanhamshire.GriefPrevention.events.ClaimPermissionCheckEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.Collections;

//represents a player claim
//creating an instance doesn't make an effective claim
//only claims which have been added to the datastore have any effect
public class Claim
{
    //two locations, which together define the boundaries of the claim
    //for subdivisions, if is3D is true, the Y boundaries are respected
    public Location lesserBoundaryCorner;
    public Location greaterBoundaryCorner;
     
     //whether this claim respects Y boundaries (for 3D subdivisions)
     private boolean is3D = false;
 
     //modification date.  this comes from the file timestamp during load, and is updated with runtime changes
     public Date modifiedDate;
 
     //id number.  unique to this claim, never changes.
     Long id = null;
 
     //use getOwnerName() to get a friendly name (will be "an administrator" for admin claims)
     public UUID ownerID;
 
     //list of players who have neighbor trust (can bypass minimum distance checks)
     public ArrayList<String> neighbors = new ArrayList<>();

     //list of players who were auto-granted neighbor trust due to existing nearby claims
     private ArrayList<String> autoNeighbors = new ArrayList<>();

     //when true, all players can bypass minimum distance checks for this claim
     public boolean allowAllNeighbors = false;

     //permissions for this claim, see ClaimPermission class
     //holds interaction trust only (build/container/access) - manager status lives in managerIdentifiers
     private HashMap<String, ClaimPermission> playerIDToClaimPermissionMap = new HashMap<>();

    //players/permissions who may hand out trust in this claim. tracked separately from the
    //interaction trust map so granting manage trust does not overwrite an explicit interaction
    //grant, and losing manage trust does not take that explicit grant away
    private final HashSet<String> managerIdentifiers = new HashSet<>();

    //players/permissions explicitly denied in this claim (override parent inheritance)
    private final HashSet<String> deniedPermissions = new HashSet<>();

    //standalone combat trusts. tracked separately from the interaction trust map so granting
    //pvp/pve trust never overwrites an explicit build/container/access grant, and neither
    //hierarchy level implies them. Only enforced when the matching Claims.Allow*Trust config
    //flag is enabled; stored unconditionally so toggling the flag never loses grants.
    private final HashSet<String> pvpTrusted = new HashSet<>();
    private final HashSet<String> pveTrusted = new HashSet<>();

     //whether or not this claim is in the data store
     //if a claim instance isn't in the data store, it isn't "active" - players can't interract with it
     //why keep this?  so that claims which have been removed from the data store can be correctly
     //ignored even though they may have references floating around
     public boolean inDataStore = false;
 
      public boolean areExplosivesAllowed = false;
      public boolean areWitherExplosionsAllowed = false;

      // PvP toggle for this claim
      public boolean pvpEnabled = true;

      // Whether pvpEnabled was explicitly toggled by a player rather than left at its default.
      // Claims that never had the toggle applied follow the global PvP config settings instead
      // of being treated as an explicit override in either direction.
      public boolean pvpToggleSet = false;

      // per-claim alert toggle
      public boolean alertsEnabled = true;
 
     //parent claim
     //only used for claim subdivisions.  top level claims have null here
     public Claim parent = null;
 
     // intended for subclaims - they inherit no permissions
     private boolean inheritNothing = false;
  
     // for main claims - when true, future subdivisions will NOT inherit permissions
     private boolean inheritNothingForNewSubdivisions = false;
 
     //children (subdivisions)
     //note subdivisions themselves never have children
     public ArrayList<Claim> children = new ArrayList<>();

     // optional orthogonal X/Z boundary for 2D shaped claims and 2D shaped subdivisions
     private @Nullable List<OrthogonalPoint2i> shapedCorners = null;

     // subdivisions flagged as staff-administered: the parent claim's owner can neither reshape
     // them, hand out trust inside them, nor abandon the claim containing them
     private boolean adminSubdivision = false;

     // cached boundary polygon; invalidated whenever the shape changes.
     // Avoids rebuilding/validating the polygon on every containsColumn/overlap call.
     private transient @Nullable OrthogonalPolygon cachedBoundaryPolygon = null;
 
     // active siege affecting this claim, if any
     public SiegeData siegeData = null;

     //following a siege, buttons/levers are unlocked temporarily.  this represents that state
     public boolean doorsOpen = false;

     //expiration date for this claim (0 = never expires)
     private long expirationDate = 0;
 
     //set whether this claim should respect Y boundaries (for 3D subdivisions)
     public void set3D(boolean is3D) {
         this.is3D = is3D;
     }
     
     //check if this is a 3D claim (respects Y boundaries)
     public boolean is3D() {
        return this.is3D;
     }
 
     //whether or not this is an administrative claim
     //administrative claims are created and maintained by players with the griefprevention.adminclaims permission.
     public boolean isAdminClaim()
     {
         return this.getOwnerID() == null;
     }
 
     //accessor for ID
     public Long getID()
     {
         return this.id;
     }
 
     //basic constructor, just notes the creation time
     //see above declarations for other defaults
     Claim()
     {
         this.modifiedDate = Calendar.getInstance().getTime();
     }
 
    //main constructor.  note that only creating a claim instance does nothing - a claim must be added to the data store to be effective
    Claim(Location lesserBoundaryCorner, Location greaterBoundaryCorner, UUID ownerID, List<String> builderIDs, List<String> containerIDs, List<String> accessorIDs, List<String> managerIDs, boolean inheritNothing, Long id, boolean is3D)
    {
        //modification date
        this.modifiedDate = Calendar.getInstance().getTime();

        //id
        this.id = id;

        //set 3D flag early so boundary logic can use it
        this.is3D = is3D;

        //store corners
        this.lesserBoundaryCorner = lesserBoundaryCorner.clone();
        this.greaterBoundaryCorner = greaterBoundaryCorner.clone();

        // Sanitize corners
        int x1 = this.lesserBoundaryCorner.getBlockX();
        int x2 = this.greaterBoundaryCorner.getBlockX();
        if (x1 > x2)
        {
            this.greaterBoundaryCorner.setX(x1);
            this.lesserBoundaryCorner.setX(x2);
        }
        int z1 = this.lesserBoundaryCorner.getBlockZ();
        int z2 = this.greaterBoundaryCorner.getBlockZ();
        if (z1 > z2)
        {
            this.greaterBoundaryCorner.setZ(z1);
            this.lesserBoundaryCorner.setZ(z2);
        }
        // Normalize Y coordinates: swap if needed so lesser <= greater
        int y1 = this.lesserBoundaryCorner.getBlockY();
        int y2 = this.greaterBoundaryCorner.getBlockY();
        if (y1 > y2)
        {
            this.greaterBoundaryCorner.setY(y1);
            this.lesserBoundaryCorner.setY(y2);
        }
        // For 2D claims, set lesser Y to world minimum (ground extension logic)
        if (!this.is3D && this.lesserBoundaryCorner.getWorld() != null) {
            this.lesserBoundaryCorner.setY(GriefPrevention.getWorldMinY(this.lesserBoundaryCorner.getWorld()));
        }

        //owner
        this.ownerID = ownerID;

        //other permissions
        for (String builderID : builderIDs)
        {
            this.setPermission(builderID, ClaimPermission.Build);
        }

        for (String containerID : containerIDs)
        {
            this.setPermission(containerID, ClaimPermission.Container);
        }

        for (String accessorID : accessorIDs)
        {
            this.setPermission(accessorID, ClaimPermission.Access);
        }

        for (String managerID : managerIDs)
        {
            this.addManager(managerID);
        }

        this.inheritNothing = inheritNothing;
    }
 
     Claim(Location lesserBoundaryCorner, Location greaterBoundaryCorner, UUID ownerID, List<String> builderIDs, List<String> containerIDs, List<String> accessorIDs, List<String> managerIDs, Long id)
     {
         this(lesserBoundaryCorner, greaterBoundaryCorner, ownerID, builderIDs, containerIDs, accessorIDs, managerIDs, false, id, false);
     }
 
     //produces a copy of a claim.
     public Claim(Claim claim) {
         this.modifiedDate = claim.modifiedDate;
         this.lesserBoundaryCorner = claim.lesserBoundaryCorner.clone();
         this.greaterBoundaryCorner = claim.greaterBoundaryCorner.clone();
         this.id = claim.id;
         this.ownerID = claim.ownerID;
         this.neighbors = new ArrayList<>(claim.neighbors);
         this.autoNeighbors = new ArrayList<>(claim.autoNeighbors);
         this.allowAllNeighbors = claim.allowAllNeighbors;
         this.playerIDToClaimPermissionMap = new HashMap<>(claim.playerIDToClaimPermissionMap);
        this.managerIdentifiers.addAll(claim.managerIdentifiers);
        this.deniedPermissions.addAll(claim.deniedPermissions);
         this.inDataStore = false; //since it's a copy of a claim, not in datastore!
         this.areExplosivesAllowed = claim.areExplosivesAllowed;
         this.areWitherExplosionsAllowed = claim.areWitherExplosionsAllowed;
         this.parent = claim.parent;
         this.inheritNothing = claim.inheritNothing;
         this.children = new ArrayList<>(claim.children);
         this.siegeData = claim.siegeData;
         this.doorsOpen = claim.doorsOpen;
         this.is3D = claim.is3D;
         this.expirationDate = claim.expirationDate;
         this.shapedCorners = claim.shapedCorners == null ? null : Collections.unmodifiableList(new ArrayList<>(claim.shapedCorners));
         this.adminSubdivision = claim.adminSubdivision;
         this.cachedBoundaryPolygon = null;
     }
 
     //measurements.  all measurements are in blocks
     public int getArea()
     {
         return this.getClaimBounds().area();
     }
 
     public int getWidth()
     {
         return this.getClaimBounds().xLength();
     }
 
     public int getHeight()
     {
         return this.getClaimBounds().zLength();
     }

     public boolean isShaped()
     {
         return !this.is3D && this.shapedCorners != null && !this.shapedCorners.isEmpty();
     }

     /**
      * Whether this claim is a shaped subdivision, i.e. a non-rectangular 2D subclaim created with
      * {@code /shapedsubdivideclaims}.
      *
      * @return true if this is a subdivision with a shaped boundary
      */
     public boolean isShapedSubdivision()
     {
         return this.parent != null && this.isShaped();
     }

     /**
      * Whether this subdivision is administered by staff rather than by the owner of the claim it
      * sits inside. Administrative subdivisions can only be resized, trusted in, or removed by
      * players holding {@code griefprevention.adminclaims}, and they block the parent claim from
      * being abandoned.
      *
      * @return true if this is an administrative subdivision
      */
     public boolean isAdminSubdivision()
     {
         return this.parent != null && this.adminSubdivision;
     }

     public void setAdminSubdivision(boolean adminSubdivision)
     {
         this.adminSubdivision = adminSubdivision;
     }

     /**
      * Whether this claim or any claim beneath it is an administrative subdivision.
      *
      * @return true if an administrative subdivision exists in this claim's subtree
      */
     public boolean containsAdminSubdivision()
     {
         for (Claim child : this.children)
         {
             if (child == null) continue;
             if (child.isAdminSubdivision() || child.containsAdminSubdivision()) return true;
         }

         return false;
     }

     public @Nullable List<OrthogonalPoint2i> getShapedCorners()
     {
         return this.shapedCorners == null ? null : Collections.unmodifiableList(new ArrayList<>(this.shapedCorners));
     }

     public void setShapedCorners(@Nullable List<OrthogonalPoint2i> shapedCorners)
     {
         this.cachedBoundaryPolygon = null;
         if (shapedCorners == null || shapedCorners.isEmpty())
         {
             this.shapedCorners = null;
             return;
         }

         List<OrthogonalPoint2i> closedPath = new ArrayList<>(shapedCorners);
         if (!closedPath.get(0).equals(closedPath.get(closedPath.size() - 1)))
         {
             closedPath.add(closedPath.get(0));
         }

         OrthogonalPolygon polygon = OrthogonalPolygon.fromClosedPath(closedPath);
         this.shapedCorners = Collections.unmodifiableList(new ArrayList<>(polygon.corners()));
         // Reuse the validated polygon we just built instead of re-validating on first access.
         this.cachedBoundaryPolygon = polygon;
     }

     public @NotNull OrthogonalPolygon getBoundaryPolygon()
     {
         if (this.isShaped())
         {
             OrthogonalPolygon cached = this.cachedBoundaryPolygon;
             if (cached != null)
             {
                 return cached;
             }
             List<OrthogonalPoint2i> closedPath = new ArrayList<>(Objects.requireNonNull(this.shapedCorners));
             closedPath.add(this.shapedCorners.get(0));
             OrthogonalPolygon polygon = OrthogonalPolygon.fromClosedPath(closedPath);
             this.cachedBoundaryPolygon = polygon;
             return polygon;
         }

         return OrthogonalPolygon.fromRectangle(
                 this.getLesserBoundaryCorner().getBlockX(),
                 this.getLesserBoundaryCorner().getBlockZ(),
                 this.getGreaterBoundaryCorner().getBlockX(),
                 this.getGreaterBoundaryCorner().getBlockZ()
         );
     }

     public @NotNull ClaimBounds getClaimBounds()
     {
         int minY = this.getMinY();
         int maxY = this.getMaxY();
         if (this.isShaped())
         {
             return ClaimBounds.shaped(this.getBoundaryPolygon(), minY, maxY);
         }

         return ClaimBounds.rectangle(
                 this.getLesserBoundaryCorner().getBlockX(),
                 minY,
                 this.getLesserBoundaryCorner().getBlockZ(),
                 this.getGreaterBoundaryCorner().getBlockX(),
                 maxY,
                 this.getGreaterBoundaryCorner().getBlockZ()
         );
     }

     public @NotNull ClaimSnapshot getSnapshot()
     {
         World world = this.lesserBoundaryCorner.getWorld();
         String worldKey = world == null ? "" : world.getName();
         return new ClaimSnapshot(
                 this.id,
                 worldKey,
                 this.getOwnerID(),
                 this.parent == null ? null : this.parent.id,
                 this.getClaimBounds(),
                 this.is3D,
                 this.parent != null
         );
     }

     public @NotNull ClaimTrustSnapshot getTrustSnapshot()
     {
         Map<String, ClaimTrustLevel> permissions = new HashMap<>();
         Set<String> neighborIdentifiers = new HashSet<>(this.neighbors);
         for (Map.Entry<String, ClaimPermission> entry : this.playerIDToClaimPermissionMap.entrySet())
         {
             ClaimTrustLevel level = toClaimTrustLevel(entry.getValue());
             if (level == ClaimTrustLevel.NEIGHBOR)
             {
                 neighborIdentifiers.add(entry.getKey());
             }
             else
             {
                 permissions.put(entry.getKey(), level);
             }
         }

        return new ClaimTrustSnapshot(this.getOwnerID(), permissions, this.managerIdentifiers,
                neighborIdentifiers, this.deniedPermissions, this.pvpTrusted, this.pveTrusted);
     }

     private static @NotNull ClaimTrustLevel toClaimTrustLevel(@NotNull ClaimPermission permission)
     {
         switch (permission)
         {
             case Edit:
                 return ClaimTrustLevel.EDIT;
             case Manage:
                 return ClaimTrustLevel.MANAGE;
             case Build:
                 return ClaimTrustLevel.BUILD;
             case Container:
             case Inventory:
                 return ClaimTrustLevel.CONTAINER;
             case Access:
                 return ClaimTrustLevel.ACCESS;
             case Neighbor:
                 return ClaimTrustLevel.NEIGHBOR;
             default:
                 throw new IllegalStateException("Unknown claim permission: " + permission);
         }
     }

     public int getCornerIndexAt(int x, int z)
     {
         if (!this.isShaped())
         {
             int minX = Math.min(this.lesserBoundaryCorner.getBlockX(), this.greaterBoundaryCorner.getBlockX());
             int maxX = Math.max(this.lesserBoundaryCorner.getBlockX(), this.greaterBoundaryCorner.getBlockX());
             int minZ = Math.min(this.lesserBoundaryCorner.getBlockZ(), this.greaterBoundaryCorner.getBlockZ());
             int maxZ = Math.max(this.lesserBoundaryCorner.getBlockZ(), this.greaterBoundaryCorner.getBlockZ());

             boolean onXCorner = x == minX || x == maxX;
             boolean onZCorner = z == minZ || z == maxZ;
             if (!onXCorner || !onZCorner)
             {
                 return -1;
             }

             if (x == minX && z == minZ) return 0;
             if (x == maxX && z == minZ) return 1;
             if (x == maxX && z == maxZ) return 2;
             return 3;
         }

         List<OrthogonalPoint2i> corners = this.getBoundaryPolygon().corners();
         for (int i = 0; i < corners.size(); i++)
         {
             OrthogonalPoint2i corner = corners.get(i);
             if (corner.x() == x && corner.z() == z)
             {
                 return i;
             }
         }

         return -1;
     }
 
     public boolean getSubclaimRestrictions()
     {
         return inheritNothing;
     }
 
     public void setSubclaimRestrictions(boolean inheritNothing)
     {
         this.inheritNothing = inheritNothing;
     }
     
     public boolean getInheritNothingForNewSubdivisions()
     {
         return this.inheritNothingForNewSubdivisions;
     }
     
     public void setInheritNothingForNewSubdivisions(boolean inheritNothingForNewSubdivisions)
     {
         this.inheritNothingForNewSubdivisions = inheritNothingForNewSubdivisions;
     }
 
     //distance check for claims, distance in this case is a band around the outside of the claim rather then euclidean distance
     public boolean isNear(Location location, int howNear)
     {
         Claim claim = new Claim
                 (new Location(this.lesserBoundaryCorner.getWorld(), this.lesserBoundaryCorner.getBlockX() - howNear, this.lesserBoundaryCorner.getBlockY(), this.lesserBoundaryCorner.getBlockZ() - howNear),
                         new Location(this.greaterBoundaryCorner.getWorld(), this.greaterBoundaryCorner.getBlockX() + howNear, this.greaterBoundaryCorner.getBlockY(), this.greaterBoundaryCorner.getBlockZ() + howNear),
                         null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
 
         return claim.contains(location, false, true);
     }

     public boolean canSiege(Player defender)
     {
         return !this.isAdminClaim()
                 && this.checkPermission(defender, ClaimPermission.Access, null) == null;
     }
 
     /**
      * @deprecated Check {@link ClaimPermission#Edit} with {@link #checkPermission(Player, ClaimPermission, Event)}.
      * @param player the Player
      * @return the denial message, or null if the action is allowed
      */
     @Deprecated
     public @Nullable String allowEdit(@NotNull Player player)
     {
         Supplier<String> supplier = checkPermission(player, ClaimPermission.Edit, null);
         return supplier != null ? supplier.get() : null;
     }
 
     private static final Set<Material> PLACEABLE_FARMING_BLOCKS = MaterialCompat.availableSet(
             "PUMPKIN_STEM",
             "WHEAT",
             "CROPS",
             "MELON_STEM",
             "CARROTS",
             "CARROT",
             "POTATOES",
             "POTATO",
             "NETHER_WART",
             "NETHER_WARTS",
             "BEETROOTS",
             "COCOA",
             "GLOW_BERRIES",
             "CAVE_VINES",
             "CAVE_VINES_PLANT");

    public static @NotNull String normalizeIdentifier(@Nullable String identifier)
    {
        if (identifier == null)
        {
            return "";
        }

        return identifier.trim().toLowerCase(Locale.ROOT);
    }
    private static @NotNull String getPermissionSuffix(@NotNull ClaimPermission permission)
    {
        switch (permission)
        {
            case Manage:
                return "#manage";
            case Build:
                return "#build";
            case Container:
            case Inventory:
                return "#inventory";
            case Access:
                return "#access";
            case Neighbor:
                return "#neighbor";
            case PVP:
                return "#pvp";
            case PVE:
                return "#pve";
            case Edit:
                return "";
            default:
                throw new IllegalStateException("Unknown claim permission: " + permission);
        }
    }
 
     private static boolean placeableForFarming(Material material)
     {
         return PLACEABLE_FARMING_BLOCKS.contains(material);
     }
 
     /**
      * @deprecated Check {@link ClaimPermission#Build} with {@link #checkPermission(Player, ClaimPermission, Event)}.
      * @param player the Player
      * @return the denial message, or null if the action is allowed
      */
     @Deprecated
     //build permission check
     public @Nullable String allowBuild(@NotNull Player player, @NotNull Material material)
     {
         Supplier<String> supplier = checkPermission(player, ClaimPermission.Build, new CompatBuildBreakEvent(material, false));
         return supplier != null ? supplier.get() : null;
     }
 
     public static class CompatBuildBreakEvent extends Event
     {
         private final Material material;
         private final boolean isBreak;
 
         private CompatBuildBreakEvent(Material material, boolean isBreak)
         {
             this.material = material;
             this.isBreak = isBreak;
         }
 
         public Material getMaterial()
         {
             return material;
         }
 
         public boolean isBreak()
         {
             return isBreak;
         }
 
         @Override
         public @NotNull HandlerList getHandlers()
         {
             return new HandlerList();
         }
 
     }
     public void denyPermission(@NotNull String identifier)
    {
        this.deniedPermissions.add(normalizeIdentifier(identifier));

        for (Claim child : this.children)
        {
            child.denyPermission(identifier);
        }
    }

    public void allowPermission(@NotNull String identifier)
    {
        String normalized = normalizeIdentifier(identifier);
        this.deniedPermissions.remove(normalized);

        for (Claim child : this.children)
        {
            child.allowPermission(identifier);
        }
    }

    /**
     * Replaces this claim's deny entries with the persisted set, without touching subdivisions.
     *
     * <p>{@link #denyPermission(String)} cascades to children because a deny handed out at runtime
     * applies to everything below it. Storage already records the resulting entry on every claim it
     * reached, so loading must restore each claim exactly as written instead of cascading again.
     *
     * @param identifiers the persisted deny entries for this claim alone
     */
    void restoreDeniedPermissions(@NotNull Collection<String> identifiers)
    {
        this.deniedPermissions.clear();
        for (String identifier : identifiers)
        {
            String normalized = normalizeIdentifier(identifier);
            if (!normalized.isEmpty())
            {
                this.deniedPermissions.add(normalized);
            }
        }
    }

    public boolean isPermissionDenied(@Nullable String identifier)
    {
        return this.isPermissionDenied(identifier, null);
    }

    public boolean isPermissionDenied(@Nullable String identifier, @Nullable ClaimPermission permission)
    {
        if (identifier == null || identifier.isEmpty())
        {
            return false;
        }

        String normalized = normalizeIdentifier(identifier);
        if (normalized.isEmpty())
        {
            return false;
        }

        if (this.deniedPermissions.contains(normalized))
        {
            return true;
        }

        if (permission != null && permission != ClaimPermission.Edit)
        {
            String key = normalized + getPermissionSuffix(permission);
            if (this.deniedPermissions.contains(key))
            {
                return true;
            }
        }

        return false;
    }
 
     /**
      * Check whether an identifier is granted a ClaimPermission by the trust stored in this claim.
      * Manage and interaction trust are stored independently so either grant can satisfy the check.
      *
      * @param normalizedIdentifier a {@link #normalizeIdentifier(String) normalized} identifier
      * @param level the ClaimPermission required
      * @return true if the identifier holds the required trust
      */
     private boolean isGranted(@NotNull String normalizedIdentifier, @NotNull ClaimPermission level)
     {
         // Combat trusts live in their own sets, outside the interaction trust map and manager
         // identifiers, so no other grant can satisfy them.
         if (level == ClaimPermission.PVP) return this.pvpTrusted.contains(normalizedIdentifier);
         if (level == ClaimPermission.PVE) return this.pveTrusted.contains(normalizedIdentifier);

         if (this.managerIdentifiers.contains(normalizedIdentifier)
                 && level.isGrantedBy(ClaimPermission.Manage))
             return true;

         return level.isGrantedBy(this.playerIDToClaimPermissionMap.get(normalizedIdentifier));
     }

     public boolean hasExplicitPermission(@NotNull UUID uuid, @NotNull ClaimPermission level)
     {
         if (uuid.equals(this.getOwnerID())) return true;

         return this.isGranted(normalizeIdentifier(uuid.toString()), level);
     }

    public boolean hasExplicitPermission(@NotNull Player player, @NotNull ClaimPermission level)
    {
        // Check explicit ClaimPermission for UUID
        if (this.hasExplicitPermission(player.getUniqueId(), level)) return true;

        // Check permission-based ClaimPermission across both trust tracks
        Set<String> nodes = new HashSet<>(this.playerIDToClaimPermissionMap.keySet());
        nodes.addAll(this.managerIdentifiers);
        nodes.addAll(this.pvpTrusted);
        nodes.addAll(this.pveTrusted);

         for (String node : nodes)
         {
             // Ensure valid permission format for permissions - [permission.node]
             if (node.length() < 3 || node.charAt(0) != '[' || node.charAt(node.length() - 1) != ']') continue;

             // Explicitly denied nodes never grant trust
             if (this.isPermissionDenied(node)) continue;

             // Check if level is high enough and player has node
             if (this.isGranted(node, level)
                     && player.hasPermission(node.substring(1, node.length() - 1)))
                 return true;
         }

         return false;
     }
 
     /**
      * Check whether a Player has a certain level of trust.
      *
      * @param player the Player being checked for permissions
      * @param permission the ClaimPermission level required
      * @param event the Event triggering the permission check
      * @return the denial message or null if permission is granted
      */
     public @Nullable Supplier<String> checkPermission(
             @NotNull Player player,
             @NotNull ClaimPermission permission,
             @Nullable Event event)
     {
         return checkPermission(player, permission, event, null);
     }
 
     /**
      * Check whether a Player has a certain level of trust with a custom denial message.
      *
      * @param player the Player being checked for permissions
      * @param permission the ClaimPermission level required
      * @param event the Event triggering the permission check
      * @param denialOverride a custom denial message supplier, or null to use default
      * @return the denial message or null if permission is granted
      */
     public @Nullable Supplier<String> checkPermission(
             @NotNull Player player,
             @NotNull ClaimPermission permission,
             @Nullable Event event,
             @Nullable Supplier<String> denialOverride)
     {
         return checkPermission(player.getUniqueId(), permission, event, denialOverride);
     }
 
     /**
      * Check whether a UUID has a certain level of trust.
      *
      * @param uuid the UUID being checked for permissions
      * @param permission the ClaimPermission level required
      * @param event the Event triggering the permission check
      * @return the denial message or null if permission is granted
      */
     public @Nullable Supplier<String> checkPermission(
             @NotNull UUID uuid,
             @NotNull ClaimPermission permission,
             @Nullable Event event)
     {
         return checkPermission(uuid, permission, event, null);
     }
 
    /**
    * Check whether a UUID has a certain level of trust with a custom denial message.
    *
    * @param uuid the UUID being checked for permissions
    * @param permission the ClaimPermission level required
    * @param event the Event triggering the permission check
    * @param denialOverride a custom denial message supplier, or null to use default
    * @return the denial message or null if permission is granted
    */
     public @Nullable Supplier<String> checkPermission(
        @NotNull UUID uuid,
        @NotNull ClaimPermission permission,
        @Nullable Event event,
        @Nullable Supplier<String> denialOverride)
    {
        // For wilderness claims, only block if explicitly denied
        if (this.isWilderness()) {
            return isPermissionDenied(uuid.toString(), permission) ? 
                (denialOverride != null ? denialOverride : () -> "You don't have permission to do that in the wilderness.") : 
                null;
        }
        return callPermissionCheck(new ClaimPermissionCheckEvent(uuid, this, permission, event), denialOverride);
    }

    /**
     * Checks if this claim represents a wilderness (unclaimed) area.
     * @return true if this is a wilderness claim, false otherwise
     */
     public boolean isWilderness() {
        return this.id == null;
    }
 
     /**
      * Helper method for calling a ClaimPermissionCheckEvent.
      *
      * @param event the ClaimPermissionCheckEvent to call
      * @param denialOverride a message overriding the default denial for clarity
      * @return the denial reason or null if permission is granted
      */
     private @Nullable Supplier<String> callPermissionCheck(
             @NotNull ClaimPermissionCheckEvent event,
             @Nullable Supplier<String> denialOverride)
     {
         // Set denial message (if any) using default behavior.
         Supplier<String> defaultDenial = getDefaultDenial(event.getCheckedPlayer(), event.getCheckedUUID(),
                 event.getRequiredPermission(), event.getTriggeringEvent());
         
         // If permission is denied and a clarifying override is provided, use override.
         if (defaultDenial != null && denialOverride != null) {
             defaultDenial = denialOverride;
         }
 
         event.setDenialReason(defaultDenial);
         Bukkit.getPluginManager().callEvent(event);
         return event.getDenialReason();
     }
 
     /**
      * Get the default reason for denial of a ClaimPermission.
      *
      * @param player the Player being checked for permissions
      * @param uuid the UUID being checked for permissions
      * @param permission the ClaimPermission required
      * @param event the Event triggering the permission check
      * @return the denial reason or null if permission is granted
      */
     private @Nullable Supplier<String> getDefaultDenial(
             @Nullable Player player,
             @NotNull UUID uuid,
             @NotNull ClaimPermission permission,
             @Nullable Event event)
     {
        
         // Administrative subdivisions are staff space inside someone else's claim.
         if (this.isAdminSubdivision())
         {
             // Staff run them the same way they run an administrative claim.
             if (player != null && player.hasPermission("griefprevention.adminclaims")) return null;

             // Everyone else keeps whatever build/container/access trust reaches them, but
             // reshaping the subdivision and handing out trust inside it stays with staff.
             if (permission == ClaimPermission.Edit || permission == ClaimPermission.Manage)
             {
                 return () -> GriefPrevention.instance.dataStore.getMessage(Messages.AdminSubdivisionRestricted);
             }
         }

         if (player != null)
         {
             // Admin claims need adminclaims permission only.
             if (this.isAdminClaim())
             {
                 if (player.hasPermission("griefprevention.adminclaims")) return null;
             }

             // Anyone with deleteclaims permission can edit non-admin claims at any time.
             else if (permission == ClaimPermission.Edit && player.hasPermission("griefprevention.deleteclaims"))
                 return null;
         }

         // Claim owner and admins in ignoreclaims mode have access.
         if (uuid.equals(this.getOwnerID())
                 || GriefPrevention.instance.dataStore.getPlayerData(uuid).ignoreClaims
                 && hasBypassPermission(player, permission))
             return null;

         // Look for explicit individual permission.
         if (player != null)
         {
             if (this.hasExplicitPermission(player, permission)) return null;
         }
         else
         {
             if (this.hasExplicitPermission(uuid, permission)) return null;
         }

         // Check for public permission.
         if (this.isGranted("public", permission)) return null;

         // Special building-only rules.
        if (permission == ClaimPermission.Build)
        {
            // No building while in PVP.
            PlayerData playerData = GriefPrevention.instance.dataStore.getPlayerData(uuid);
            if (playerData.inPvpCombat())
            {
                return () -> GriefPrevention.instance.dataStore.getMessage(Messages.NoBuildPvP);
            }

            // Allow farm crops with container trust.
            Material material = null;
            if (event instanceof BlockBreakEvent || event instanceof BlockPlaceEvent)
            {
                material = ((BlockEvent) event).getBlock().getType();
            }

            if (material != null && placeableForFarming(material)
                    && this.getDefaultDenial(player, uuid, ClaimPermission.Container, event) == null)
                return null;
        }

        // First-child subdivisions inherit from parent; nested subdivisions do not.
        if (this.parent != null)
        {
            boolean isFirstChild = this.parent.parent == null;
            boolean inheritsFromParent = isFirstChild && !inheritNothing;
            if (inheritsFromParent)
            {
                // Block inherited permission if explicitly denied here
                if (this.isPermissionDenied(uuid.toString(), permission))
                {
                    return () -> GriefPrevention.instance.dataStore.getMessage(permission.getDenialMessage(), this.getOwnerName());
                }

                // Only inherit if current claim doesn't explicitly deny permission
                Supplier<String> parentDenial = this.parent.getDefaultDenial(player, uuid, permission, event);
                if (parentDenial == null)
                {
                    return null;
                }
            }
        }

         // Catch-all error message for all other cases.
         return () ->
         {
             String reason = GriefPrevention.instance.dataStore.getMessage(permission.getDenialMessage(), this.getOwnerName());
             if (hasBypassPermission(player, permission))
                 reason += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
             return reason;
         };
     }

     /**
      * Check if the {@link Player} has bypass permissions for a {@link ClaimPermission}. Owner-exclusive edit actions
      * require {@code griefprevention.deleteclaims}. All other actions require {@code griefprevention.ignoreclaims}.
      *
      * @param player the {@code Player}
      * @param permission the {@code ClaimPermission} whose bypass permission is being checked
      * @return whether the player has the bypass node
      */
     @Contract("null, _ -> false")
     private boolean hasBypassPermission(@Nullable Player player, @NotNull ClaimPermission permission)
     {
         if (player == null) return false;

         if (permission == ClaimPermission.Edit) return player.hasPermission("griefprevention.deleteclaims");

         return player.hasPermission("griefprevention.ignoreclaims");
     }

     /**
      * @deprecated Check {@link ClaimPermission#Build} with {@link #checkPermission(Player, ClaimPermission, Event)}.
      * @param player the Player
      * @return the denial message, or null if the action is allowed
      */
     @Deprecated
     public @Nullable String allowBreak(@NotNull Player player, @NotNull Material material)
     {
         Supplier<String> supplier = checkPermission(player, ClaimPermission.Build, new CompatBuildBreakEvent(material, true));
         return supplier != null ? supplier.get() : null;
     }

     /**
      * @deprecated Check {@link ClaimPermission#Access} with {@link #checkPermission(Player, ClaimPermission, Event)}.
      * @param player the Player
      * @return the denial message, or null if the action is allowed
      */
     @Deprecated
     public @Nullable String allowAccess(@NotNull Player player)
     {
         Supplier<String> supplier = checkPermission(player, ClaimPermission.Access, null);
         return supplier != null ? supplier.get() : null;
     }

     /**
      * @deprecated Check {@link ClaimPermission#Container} with {@link #checkPermission(Player, ClaimPermission, Event)}.
      * @param player the Player
      * @return the denial message, or null if the action is allowed
      */
     @Deprecated
     public @Nullable String allowContainers(@NotNull Player player)
     {
         Supplier<String> supplier = checkPermission(player, ClaimPermission.Container, null);
         return supplier != null ? supplier.get() : null;
     }

     /**
      * @deprecated Check {@link ClaimPermission#Manage} with {@link #checkPermission(Player, ClaimPermission, Event)}.
      * @param player the Player
      * @return the denial message, or null if the action is allowed
      */
     @Deprecated
     public @Nullable String allowGrantPermission(@NotNull Player player)
     {
         Supplier<String> supplier = checkPermission(player, ClaimPermission.Manage, null);
         return supplier != null ? supplier.get() : null;
     }

     @Contract("null -> null")
    public @Nullable ClaimPermission getPermission(@Nullable String playerID)
    {
        if (playerID == null || playerID.isEmpty()) return null;

        String normalized = normalizeIdentifier(playerID);
        ClaimPermission interactionLevel = this.playerIDToClaimPermissionMap.get(normalized);

        // Interaction trust is what callers care about when it exists.  Manager status is only
        // reported here when it is all the player has, so that legacy callers comparing the result
        // against ClaimPermission.Manage keep working.  Use isManager to test manager status.
        if (interactionLevel != null) return interactionLevel;

        return this.managerIdentifiers.contains(normalized) ? ClaimPermission.Manage : null;
    }

    /**
     * Check whether a player or permission identifier may hand out trust in this claim.
     *
     * @param playerID the UUID string, {@code public}, or {@code [permission.node]} identifier
     * @return true if the identifier has manage trust here
     */
    public boolean isManager(@Nullable String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        return !normalized.isEmpty() && this.managerIdentifiers.contains(normalized);
    }

    //grants manage trust to a player or the public, leaving interaction trust untouched
    public void addManager(@Nullable String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        if (normalized.isEmpty()) return;

        this.managerIdentifiers.add(normalized);
    }

    //revokes manage trust, leaving interaction trust untouched
    public void dropManager(@NotNull String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        if (normalized.isEmpty())
        {
            return;
        }

        this.managerIdentifiers.remove(normalized);

        for (Claim child : this.children)
        {
            child.dropManager(normalized);
        }
    }

    //grants combat trust against players (pvp) or creatures (pve), leaving other trust untouched
    public void addPvpTrust(@Nullable String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        if (normalized.isEmpty()) return;

        this.pvpTrusted.add(normalized);
    }

    public void addPveTrust(@Nullable String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        if (normalized.isEmpty()) return;

        this.pveTrusted.add(normalized);
    }

    //revokes combat trust, leaving other trust untouched
    public void dropPvpTrust(@NotNull String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        if (normalized.isEmpty()) return;

        this.pvpTrusted.remove(normalized);

        for (Claim child : this.children)
        {
            child.dropPvpTrust(normalized);
        }
    }

    public void dropPveTrust(@NotNull String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        if (normalized.isEmpty()) return;

        this.pveTrusted.remove(normalized);

        for (Claim child : this.children)
        {
            child.dropPveTrust(normalized);
        }
    }

    public boolean isPvpTrusted(@Nullable String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        return !normalized.isEmpty() && this.pvpTrusted.contains(normalized);
    }

    public boolean isPveTrusted(@Nullable String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        return !normalized.isEmpty() && this.pveTrusted.contains(normalized);
    }

    public @NotNull Set<String> getPvpTrustedIdentifiers()
    {
        return Collections.unmodifiableSet(new HashSet<>(this.pvpTrusted));
    }

    public @NotNull Set<String> getPveTrustedIdentifiers()
    {
        return Collections.unmodifiableSet(new HashSet<>(this.pveTrusted));
    }

     //grants a permission for a player or the public
     public void setPermission(@Nullable String playerID, @Nullable ClaimPermission permissionLevel)
     {
         String normalized = normalizeIdentifier(playerID);
        if (permissionLevel == ClaimPermission.Edit) throw new IllegalArgumentException("Cannot add editors!");

        if (normalized.isEmpty()) return;

        if (permissionLevel == null)
            dropPermission(normalized);
        else if (permissionLevel == ClaimPermission.Manage)
            addManager(normalized);
        else if (permissionLevel == ClaimPermission.Neighbor)
            addNeighbor(normalized);
        else if (permissionLevel == ClaimPermission.PVP)
            addPvpTrust(normalized);
        else if (permissionLevel == ClaimPermission.PVE)
            addPveTrust(normalized);
        else
            this.playerIDToClaimPermissionMap.put(normalized, permissionLevel);
    }

    //revokes all trust - both interaction and manage - for a player or the public
    public void dropPermission(@NotNull String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        if (normalized.isEmpty())
        {
            return;
        }

        this.playerIDToClaimPermissionMap.remove(normalized);
        this.managerIdentifiers.remove(normalized);
        this.neighbors.remove(normalized);
        this.pvpTrusted.remove(normalized);
        this.pveTrusted.remove(normalized);

        for (Claim child : this.children)
        {
            child.dropPermission(normalized);
        }
    }

    //revokes build/container/access trust, leaving manage trust untouched
    public void dropInteractionPermission(@NotNull String playerID)
    {
        String normalized = normalizeIdentifier(playerID);
        if (normalized.isEmpty())
        {
            return;
        }

        this.playerIDToClaimPermissionMap.remove(normalized);

        for (Claim child : this.children)
        {
            child.dropInteractionPermission(normalized);
        }
    }

     //clears all permissions (except owner of course)
     public void clearPermissions()
     {
         this.playerIDToClaimPermissionMap.clear();
         this.managerIdentifiers.clear();
         //deny entries are part of this claim's trust state, so a full reset drops them too.
         //leaving them would make a subdivision keep refusing trust the parent grants it later.
         this.deniedPermissions.clear();
         this.neighbors.clear();
         this.autoNeighbors.clear();
         this.allowAllNeighbors = false;
         this.pvpTrusted.clear();
         this.pveTrusted.clear();

         for (Claim child : this.children)
         {
             child.clearPermissions();
         }
     }

     //gets ALL permissions
     //useful for  making copies of permissions during a claim resize and listing all permissions in a claim
     public void getPermissions(ArrayList<String> builders, ArrayList<String> containers, ArrayList<String> accessors, ArrayList<String> managers)
     {
         //loop through all the entries in the hash map
         for (Map.Entry<String, ClaimPermission> entry : this.playerIDToClaimPermissionMap.entrySet())
         {
             //build up a list for each permission level
             if (entry.getValue() == ClaimPermission.Build)
             {
                 builders.add(entry.getKey());
             }
             else if (entry.getValue().isContainer())
             {
                 containers.add(entry.getKey());
             }
             else if (entry.getValue() == ClaimPermission.Access)
             {
                 accessors.add(entry.getKey());
             }
             //Neighbor trust has its own list (see getNeighbors) and no bucket here.  Lumping it in
             //with accessors would silently promote neighbors to access trust on the next load.
         }

         //manage trust is tracked separately, so a player can appear here and in a trust list above
         managers.addAll(this.managerIdentifiers);
     }

     //gets neighbor trust list
     public ArrayList<String> getNeighbors()
     {
         return new ArrayList<>(this.neighbors);
     }

     //gets the full display list (manual + auto neighbors, no duplicates)
     public ArrayList<String> getAllNeighbors()
     {
         ArrayList<String> all = new ArrayList<>(this.neighbors);
         for (String auto : this.autoNeighbors) {
             if (!all.contains(auto)) {
                 all.add(auto);
             }
         }
         return all;
     }

     //add a manual neighbor
     public void addNeighbor(String playerID) {
         String normalized = normalizeIdentifier(playerID);
         if (!this.neighbors.contains(normalized)) {
             this.neighbors.add(normalized);
         }
     }

     //remove a manual neighbor (does NOT remove auto-neighbors)
     public void removeNeighbor(String playerID) {
         String normalized = normalizeIdentifier(playerID);
         this.neighbors.remove(normalized);
     }

     //add an auto-granted neighbor
     public void addAutoNeighbor(String playerID) {
         String normalized = normalizeIdentifier(playerID);
         if (!this.neighbors.contains(normalized) && !this.autoNeighbors.contains(normalized)) {
             this.autoNeighbors.add(normalized);
         }
     }

     //remove an auto-granted neighbor (only if no longer nearby)
     public void removeAutoNeighbor(String playerID) {
         String normalized = normalizeIdentifier(playerID);
         this.autoNeighbors.remove(normalized);
     }

     //check if a player is an auto-neighbor
     public boolean isAutoNeighbor(String playerID) {
         String normalized = normalizeIdentifier(playerID);
         return this.autoNeighbors.contains(normalized);
     }

     //check if a player has any neighbor trust (manual or auto)
     public boolean hasNeighborTrust(String playerID) {
         String normalized = normalizeIdentifier(playerID);
         return this.neighbors.contains(normalized) || this.autoNeighbors.contains(normalized);
     }

     //get only manual (non-auto) neighbors
     public ArrayList<String> getManualNeighbors()
     {
         return new ArrayList<>(this.neighbors);
     }

     //returns a copy of the location representing lower x, y, z limits
     public Location getLesserBoundaryCorner()
     {
         return this.lesserBoundaryCorner.clone();
     }

     //returns a copy of the location representing upper x, y, z limits
     //NOTE: remember upper Y will always be ignored, all claims always extend to the sky
     public Location getGreaterBoundaryCorner()
     {
         return this.greaterBoundaryCorner.clone();
     }

     //returns a friendly owner name (for admin claims, returns "an administrator" as the owner)
     public String getOwnerName()
     {
         if (this.parent != null)
             return this.parent.getOwnerName();

         if (this.ownerID == null)
             return GriefPrevention.instance.dataStore.getMessage(Messages.OwnerNameForAdminClaims);

         return GriefPrevention.lookupPlayerName(this.ownerID);
     }

     public UUID getOwnerID()
     {
         if (this.parent != null)
         {
             return this.parent.ownerID;
         }
         return this.ownerID;
     }

     /**
      * Set the direct owner UUID for this claim.
      * <p>
      * Kept as a small public wrapper for addons that use the classic GP claim surface by reflection.
      * Ownership changes that need datastore bookkeeping should still go through {@link DataStore#changeClaimOwner(Claim, UUID)}.
      * </p>
      *
      * @param ownerID the new owner UUID, or null for an admin claim
      */
     public void setOwnerID(UUID ownerID)
     {
         this.ownerID = ownerID;
     }

     /**
      * Returns this claim's child subdivisions.
      *
      * @return mutable child subdivision list, matching the historical public field behavior
      */
     public ArrayList<Claim> getChildren()
     {
         return this.children;
     }

     /**
      * Legacy synonym for {@link #getChildren()} used by older addons.
      *
      * @return mutable child subdivision list
      */
     public ArrayList<Claim> getSubclaims()
     {
         return this.children;
     }

     public long getExpirationDate()
     {
         return this.expirationDate;
     }

     public void setExpirationDate(long expirationDate)
     {
         this.expirationDate = expirationDate;
     }
     public boolean contains(Location location, boolean ignoreHeight, boolean excludeSubdivisions) {
         if (!Objects.equals(location.getWorld(), this.lesserBoundaryCorner.getWorld())) {
             return false;
         }

         int x = location.getBlockX();
         int y = location.getY() % 1 == 0 ? location.getBlockY() : location.getBlockY() + 1;
         int z = location.getBlockZ();

         boolean ignoreY = ignoreHeight || (!this.is3D && this.parent != null);
         if (!this.getClaimBounds().contains(x, y, z, ignoreY)) {
             return false;
         }
     
         // Handle subdivision exclusion - properly respect 3D boundaries
         if (excludeSubdivisions && !this.children.isEmpty()) {
             for (Claim child : this.children) {
                 // For 3D subdivisions, always check height boundaries
                 boolean childContains = child.contains(location, child.is3D ? false : ignoreHeight, false);
                 
                 if (childContains) {
                     return false;
                 }
             }
         }
         
         return true;
     }

    // Check if a Y coordinate is within this claim's boundaries (for 3D claims only)
    public boolean containsY(int y) {
        if (!this.is3D) {
            // For non-3D claims, Y boundaries are not enforced, so always return true.
            return true;
        }
        return this.getClaimBounds().containsY(y);
    }

    /**
     * Gets the minimum Y coordinate for this claim.
     * <p>
     * For 3D claims (subdivisions with is3D=true), returns the actual defined minimum Y boundary.
     * For top-level claims and 2D subdivisions, returns the world's minimum build height.
     * </p>
     * This is part of the public API for extensions.
     *
     * @return the minimum Y coordinate for this claim
     */
    public int getMinY() {
        if (this.is3D) {
            return Math.min(this.lesserBoundaryCorner.getBlockY(), this.greaterBoundaryCorner.getBlockY());
        }
        // For non-3D claims, use world boundaries
        World world = this.lesserBoundaryCorner.getWorld();
        return world != null ? GriefPrevention.getWorldMinY(world) : 0;
    }

    /**
     * Gets the maximum Y coordinate for this claim.
     * <p>
     * For 3D claims (subdivisions with is3D=true), returns the actual defined maximum Y boundary.
     * For top-level claims and 2D subdivisions, returns the world's maximum build height.
     * </p>
     * This is part of the public API for extensions.
     *
     * @return the maximum Y coordinate for this claim
     */
    public int getMaxY() {
        if (this.is3D) {
            return Math.max(this.lesserBoundaryCorner.getBlockY(), this.greaterBoundaryCorner.getBlockY());
        }
        // For non-3D claims, use world boundaries
        World world = this.lesserBoundaryCorner.getWorld();
        return world != null ? GriefPrevention.getWorldMaxY(world) : 256;
    }

    /**
     * Gets the Y-axis height (vertical size) of this claim in blocks.
     * <p>
     * For 3D claims, returns the actual vertical span of the claim.
     * For non-3D claims, returns the full world height.
     * </p>
     * This is part of the public API for extensions.
     *
     * @return the vertical height of this claim in blocks
     */
    public int getYHeight() {
        return getMaxY() - getMinY() + 1;
    }

    /**
     * Gets detailed information about this claim's Y boundaries.
     * This is part of the public API for extensions.
     *
     * @return a ClaimYInfo object containing min/max Y and claim type information
     */
    public @NotNull ClaimYInfo getYInfo() {
        return new ClaimYInfo(this);
    }

    /**
     * Holds detailed Y-coordinate information for a claim.
     * This is part of the public API for extensions.
     */
    public static class ClaimYInfo {
        private final int minY;
        private final int maxY;
        private final boolean is3D;
        private final boolean isSubdivision;
        private final boolean isAdminClaim;
        private final String claimType;

        ClaimYInfo(Claim claim) {
            this.minY = claim.getMinY();
            this.maxY = claim.getMaxY();
            this.is3D = claim.is3D();
            this.isSubdivision = claim.parent != null;
            this.isAdminClaim = claim.isAdminClaim();
            
            if (claim.isAdminClaim()) {
                this.claimType = claim.parent != null 
                    ? (claim.is3D() ? "Admin 3D Subdivision" : "Admin 2D Subdivision")
                    : "Admin Claim";
            } else {
                if (claim.parent != null) {
                    this.claimType = claim.is3D() ? "3D Subdivision" : "2D Subdivision";
                } else {
                    this.claimType = "Main Claim";
                }
            }
        }

        /** @return the minimum Y coordinate */
        public int getMinY() { return minY; }
        
        /** @return the maximum Y coordinate */
        public int getMaxY() { return maxY; }
        
        /** @return the vertical height in blocks */
        public int getHeight() { return maxY - minY + 1; }
        
        /** @return true if this is a 3D claim with custom Y boundaries */
        public boolean is3D() { return is3D; }
        
        /** @return true if this is a subdivision (has a parent claim) */
        public boolean isSubdivision() { return isSubdivision; }
        
        /** @return true if this is an admin claim */
        public boolean isAdminClaim() { return isAdminClaim; }
        
        /** @return a human-readable claim type string (e.g., "Main Claim", "3D Subdivision", "Admin Claim") */
        public String getClaimType() { return claimType; }

        @Override
        public String toString() {
            return "ClaimYInfo{type=" + claimType + ", minY=" + minY + ", maxY=" + maxY + ", height=" + getHeight() + "}";
        }
    }

    //whether or not two claims overlap
    //used internally to prevent overlaps when creating claims
    boolean overlaps(Claim otherClaim)
    {
        return this.getSnapshot().overlaps(otherClaim.getSnapshot());
    }

     public @Nullable String allowMoreEntities(boolean remove)
     {
         if (this.parent != null) return this.parent.allowMoreEntities(remove);
         if (!GriefPrevention.instance.creativeRulesApply(this.getLesserBoundaryCorner())) return null;
         if (this.isAdminClaim() || this.getArea() > 10000) return null;

         int maxEntities = this.getArea() / 50;
         if (maxEntities == 0) return GriefPrevention.instance.dataStore.getMessage(Messages.ClaimTooSmallForEntities);

         int totalEntities = 0;
         for (Chunk chunk : this.getLoadedChunks())
         {
             Entity[] entities;
             try
             {
                 entities = chunk.getEntities();
             }
             catch (IllegalStateException ignored)
             {
                 // Another Folia region owns this chunk; its cleanup pass will enforce it.
                 continue;
             }
             for (Entity entity : entities)
             {
                 if (!(entity instanceof Player) && this.contains(entity.getLocation(), false, false))
                 {
                     totalEntities++;
                     if (remove && totalEntities > maxEntities) entity.remove();
                 }
             }
         }
         if (totalEntities >= maxEntities)
             return GriefPrevention.instance.dataStore.getMessage(Messages.TooManyEntitiesInClaim);
         return null;
     }

     public @Nullable String allowMoreActiveBlocks()
     {
         if (this.parent != null) return this.parent.allowMoreActiveBlocks();
         int maxActives = this.getArea() / 100;
         if (maxActives == 0)
             return GriefPrevention.instance.dataStore.getMessage(Messages.ClaimTooSmallForActiveBlocks);

         int totalActives = 0;
         for (Chunk chunk : this.getLoadedChunks())
         {
             BlockState[] actives;
             try
             {
                 actives = chunk.getTileEntities();
             }
             catch (IllegalStateException ignored)
             {
                 continue;
             }
             for (BlockState active : actives)
             {
                 if (BlockEventHandler.isActiveBlock(active) && this.contains(active.getLocation(), false, false))
                     totalActives++;
             }
         }
         if (totalActives >= maxActives)
             return GriefPrevention.instance.dataStore.getMessage(Messages.TooManyActiveBlocksInClaim);
         return null;
     }

    long getPlayerInvestmentScore()
    {
        Location lesser = this.getLesserBoundaryCorner();
        World world = lesser.getWorld();
        if (world == null) return 0;

        Set<Material> playerBlocks = RestoreNatureProcessingTask.getPlayerBlocks(
                world.getEnvironment(), lesser.getBlock().getBiome());
        double score = 0;
        boolean creativeMode = GriefPrevention.instance.creativeRulesApply(lesser);
        int scanBottom = Math.max(GriefPrevention.instance.getWorldMinY(world), lesser.getBlockY());
        int scanTop = GriefPrevention.instance.getWorldMaxY(world);
        int deepCutoff = GriefPrevention.instance.getSeaLevel(world) - 5;

        for (int x = lesser.getBlockX(); x <= this.greaterBoundaryCorner.getBlockX(); x++)
        {
            for (int z = lesser.getBlockZ(); z <= this.greaterBoundaryCorner.getBlockZ(); z++)
            {
                for (int y = scanBottom; y < scanTop; y++)
                {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (!playerBlocks.contains(type)) continue;
                    if (type == Material.CHEST && !creativeMode) score += 10;
                    else if (creativeMode && type == Material.LAVA) score -= 10;
                    else score += y < deepCutoff ? .5 : 1;
                }
            }
        }
        return (long) score;
    }

     //implements a strict ordering of claims, used to keep the claims collection sorted for faster searching
     boolean greaterThan(Claim otherClaim)
     {
         Location thisCorner = this.getLesserBoundaryCorner();
         Location otherCorner = otherClaim.getLesserBoundaryCorner();

         if (thisCorner.getBlockX() > otherCorner.getBlockX()) return true;

         if (thisCorner.getBlockX() < otherCorner.getBlockX()) return false;

         if (thisCorner.getBlockZ() > otherCorner.getBlockZ()) return true;

         if (thisCorner.getBlockZ() < otherCorner.getBlockZ()) return false;

         return thisCorner.getWorld().getName().compareTo(otherCorner.getWorld().getName()) < 0;
     }


     //removes any lava or water above sea level in a claim
     //exclusionClaim is another claim indicating a sub-area to be excluded from this operation
     //it may be null
     public void removeSurfaceFluids(Claim exclusionClaim)
     {
         //don't do this for administrative claims
         if (this.isAdminClaim()) return;

         //don't do it for very large claims
         if (this.getArea() > 10000) return;

         //only in creative mode worlds
         if (!GriefPrevention.instance.creativeRulesApply(this.lesserBoundaryCorner)) return;

         Location lesser = this.getLesserBoundaryCorner();
         Location greater = this.getGreaterBoundaryCorner();
         World world = lesser.getWorld();
         if (world == null) return;

         if (world.getEnvironment() == World.Environment.NETHER) return;  //don't clean up lava in the nether

         int seaLevel = 0;  //clean up all fluids in the end

         //respect sea level in normal worlds
         if (world.getEnvironment() == World.Environment.NORMAL)
             seaLevel = GriefPrevention.instance.getSeaLevel(world);

         int maxY = GriefPrevention.getWorldMaxY(world);

         for (int x = lesser.getBlockX(); x <= greater.getBlockX(); x++)
         {
             for (int z = lesser.getBlockZ(); z <= greater.getBlockZ(); z++)
             {
                 for (int y = seaLevel - 1; y <= maxY; y++)
                 {
                     //dodge the exclusion claim
                     Block block = world.getBlockAt(x, y, z);
                     if (exclusionClaim != null && exclusionClaim.contains(block.getLocation(), true, false)) continue;

                     if (block.getType() == Material.LAVA || block.getType() == Material.WATER)
                     {
                         block.setType(Material.AIR);
                     }
                 }
             }
         }
     }

     public ArrayList<Chunk> getChunks()
     {
         ArrayList<Chunk> chunks = new ArrayList<>();

         World world = this.getLesserBoundaryCorner().getWorld();
         Chunk lesserChunk = this.getLesserBoundaryCorner().getChunk();
         Chunk greaterChunk = this.getGreaterBoundaryCorner().getChunk();

         for (int x = lesserChunk.getX(); x <= greaterChunk.getX(); x++)
         {
             for (int z = lesserChunk.getZ(); z <= greaterChunk.getZ(); z++)
             {
                 chunks.add(world.getChunkAt(x, z));
             }
         }

         return chunks;
     }

     private ArrayList<Chunk> getLoadedChunks()
     {
         ArrayList<Chunk> chunks = new ArrayList<>();
         World world = this.getLesserBoundaryCorner().getWorld();
         int lesserX = this.getLesserBoundaryCorner().getBlockX() >> 4;
         int greaterX = this.getGreaterBoundaryCorner().getBlockX() >> 4;
         int lesserZ = this.getLesserBoundaryCorner().getBlockZ() >> 4;
         int greaterZ = this.getGreaterBoundaryCorner().getBlockZ() >> 4;
         for (int x = lesserX; x <= greaterX; x++)
         {
             for (int z = lesserZ; z <= greaterZ; z++)
             {
                 if (world.isChunkLoaded(x, z)) chunks.add(world.getChunkAt(x, z));
             }
         }
         return chunks;
     }

     ArrayList<Long> getChunkHashes()
     {
         return DataStore.getChunkHashes(this);
     }
 }
