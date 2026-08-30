package me.ryanhamshire.GriefPrevention;

import com.griefprevention.compat.PotionEffectTypeCompat;
import me.ryanhamshire.GriefPrevention.compat.CompatUtil;
import me.ryanhamshire.GriefPrevention.events.PreventPvPEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Mule;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.projectiles.BlockProjectileSource;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class EntityDamageHandler implements Listener {

    private static volatile Set<Object> GRIEF_EFFECTS;
    private static volatile Set<Object> TEMPTABLE_SEMI_HOSTILES;

    private static Set<Object> getGriefEffects() {
        Set<Object> cached = GRIEF_EFFECTS;
        if (cached != null) {
            return cached;
        }
        GRIEF_EFFECTS = PotionEffectTypeCompat.getGriefEffects();
        return GRIEF_EFFECTS;
    }

    private static Set<Object> getTemptableSemiHostiles() {
        Set<Object> cached = TEMPTABLE_SEMI_HOSTILES;
        if (cached != null) {
            return cached;
        }
        Set<Object> types = new HashSet<>();
        addEntityTypeByName(types, "HOGLIN");
        addEntityTypeByName(types, "POLAR_BEAR");
        addEntityTypeByName(types, "PANDA");
        TEMPTABLE_SEMI_HOSTILES = types;
        return types;
    }

    private static void addEntityTypeByName(Set<Object> set, String name) {
        try {
            Class<?> entityTypeClass = Class.forName("org.bukkit.entity.EntityType", false, EntityDamageHandler.class.getClassLoader());
            Method valueOf = entityTypeClass.getMethod("valueOf", String.class);
            Object type = valueOf.invoke(null, name);
            set.add(type);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Entity type doesn't exist in this version
        }
    }

    private static volatile Object LURED_BY_PLAYER;

    private static Object getLuredByPlayerKey() {
        Object cached = LURED_BY_PLAYER;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey", false, EntityDamageHandler.class.getClassLoader());
            Method fromString = namespacedKeyClass.getMethod("fromString", String.class);
            LURED_BY_PLAYER = fromString.invoke(null, "griefprevention:lured_by_player");
            return LURED_BY_PLAYER;
        } catch (ReflectiveOperationException | LinkageError exception) {
            // NamespacedKey doesn't exist in this version, return null
            return null;
        }
    }

    private static Object getPersistentDataByteType() {
        try {
            Class<?> dataTypeClass = Class.forName("org.bukkit.persistence.PersistentDataType", false, EntityDamageHandler.class.getClassLoader());
            return dataTypeClass.getField("BYTE").get(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static void setPersistentData(Entity entity, Object key, Object type, Object value) {
        if (key == null || type == null) return;
        try {
            ClassLoader cl = EntityDamageHandler.class.getClassLoader();
            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey", false, cl);
            Class<?> dataTypeClass = Class.forName("org.bukkit.persistence.PersistentDataType", false, cl);
            Object container = entity.getPersistentDataContainer();
            Class<?> containerClass = container.getClass();
            Method setMethod = containerClass.getMethod("set", namespacedKeyClass, dataTypeClass, Object.class);
            setMethod.invoke(container, key, type, value);
        } catch (ReflectiveOperationException | LinkageError exception) {
            // PersistentDataContainer doesn't support this in this version
        }
    }

    private static void removePersistentData(Entity entity, Object key) {
        if (key == null) return;
        try {
            Object container = entity.getPersistentDataContainer();
            Class<?> containerClass = container.getClass();
            Method removeMethod = containerClass.getMethod("remove", Class.forName("org.bukkit.NamespacedKey", false, EntityDamageHandler.class.getClassLoader()));
            removeMethod.invoke(container, key);
        } catch (ReflectiveOperationException | LinkageError exception) {
            // PersistentDataContainer doesn't support this in this version
        }
    }

    private static boolean hasPersistentData(Entity entity, Object key) {
        if (key == null) return false;
        try {
            ClassLoader cl = EntityDamageHandler.class.getClassLoader();
            Class<?> namespacedKeyClass = Class.forName("org.bukkit.NamespacedKey", false, cl);
            Class<?> dataTypeClass = Class.forName("org.bukkit.persistence.PersistentDataType", false, cl);
            Object byteType = dataTypeClass.getField("BYTE").get(null);
            Object container = entity.getPersistentDataContainer();
            Class<?> containerClass = container.getClass();
            Method hasMethod = containerClass.getMethod("has", namespacedKeyClass, dataTypeClass);
            return (Boolean) hasMethod.invoke(container, key, byteType);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    private static Object getPotionEffectType(PotionEffect effect) {
        try {
            return PotionEffect.class.getMethod("getType").invoke(effect);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }

    private static boolean isBeneficialEffect(Object effectType) {
        if (effectType == null) return false;
        try {
            Class<?> potionEffectTypeClass = Class.forName("org.bukkit.potion.PotionEffectType", false, EntityDamageHandler.class.getClassLoader());
            Method getCategory = potionEffectTypeClass.getMethod("getCategory");
            Object category = getCategory.invoke(effectType);
            Class<?> categoryClass = Class.forName("org.bukkit.potion.PotionEffectTypeCategory", false, EntityDamageHandler.class.getClassLoader());
            Object beneficial = categoryClass.getField("BENEFICIAL").get(null);
            return category.equals(beneficial);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    final @NotNull DataStore dataStore;
    final @NotNull GriefPrevention instance;

    EntityDamageHandler(@NotNull DataStore dataStore, @NotNull GriefPrevention plugin) {
        this.dataStore = dataStore;
        instance = plugin;
    }

    // Tag passive animals that can become aggressive so that we can tell whether
    // they are hostile later
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityTarget(@NotNull EntityTargetEvent event) {
        if (!instance.claimsEnabledForWorld(event.getEntity().getWorld()))
            return;

        if (!getTemptableSemiHostiles().contains(event.getEntityType()))
            return;

        if (event.getReason() == EntityTargetEvent.TargetReason.TEMPT)
            setPersistentData(event.getEntity(), getLuredByPlayerKey(), getPersistentDataByteType(), (byte) 1);
        else
            removePersistentData(event.getEntity(), getLuredByPlayerKey());

    }

    // when an entity is damaged
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        this.handleEntityDamageEvent(new EntityDamageInstance(event), true);
    }

    // when an entity is set on fire
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntityCombustByEntity(@NotNull EntityCombustByEntityEvent event) {
        this.handleEntityDamageEvent(new EntityDamageInstance(event), false);
    }

    private void handleEntityDamageEvent(@NotNull EntityDamageInstance event, boolean sendMessages) {
        // monsters are never protected
        if (isHostile(event.damaged()))
            return;

        // horse protections can be disabled
        if (event.damaged() instanceof Horse && !instance.config_claims_protectHorses)
            return;
        if (CompatUtil.canCheckEntityType("Donkey") && event.damaged() instanceof Donkey && !instance.config_claims_protectDonkeys)
            return;
        if (CompatUtil.canCheckEntityType("Mule") && event.damaged() instanceof Mule && !instance.config_claims_protectDonkeys)
            return;
        if (CompatUtil.canCheckEntityType("Llama") && event.damaged() instanceof Llama && !instance.config_claims_protectLlamas)
            return;
        // protected death loot can't be destroyed, only picked up or despawned due to
        // expiration
        if (event.damaged() instanceof org.bukkit.entity.Item) {
            if (event.damaged().hasMetadata("GP_ITEMOWNER")) {
                event.setCancelled(true);
            }
        }

        // Handle environmental damage to tamed animals that could easily be caused
        // maliciously.
        if (handlePetDamageByEnvironment(event))
            return;

        // Handle entity damage by block explosions.
        if (handleEntityDamageByBlockExplosion(event))
            return;

        // the rest is only interested in entities damaging entities (ignoring
        // environmental damage)
        if (event.damager() == null)
            return;

        if (event.damager() instanceof LightningStrike && event.damager().hasMetadata("GP_TRIDENT")) {
            event.setCancelled(true);
            return;
        }

        // determine which player is attacking, if any
        Player attacker = null;
        Projectile arrow = null;
        Entity damageSource = event.damager();
        if (damageSource instanceof Player) {
            attacker = (Player) damageSource;
        } else if (damageSource instanceof Projectile) {
            arrow = (Projectile) damageSource;
            if (arrow.getShooter() instanceof Player) {
                attacker = (Player) arrow.getShooter();
            }
        }

        // Handle wind charge damage - wind charges are projectiles that deal knockback and damage
        // When PVP rules are enabled for the world, protect players in claims from wind charge attacks
        String damagerTypeName = damageSource != null ? damageSource.getType().name() : "";
        if ((damagerTypeName.contains("WIND_CHARGE") || damagerTypeName.equals("BREEZE_WIND_CHARGE")) && event.damaged() instanceof Player) {
            Player defender = (Player) event.damaged();
            if (attacker != null && attacker != defender && instance.pvpRulesApply(defender.getWorld())) {
                // Check if defender is in a protected claim
                PlayerData defenderData = dataStore.getPlayerData(defender.getUniqueId());
                Claim claim = dataStore.getClaimAt(defender.getLocation(), false, defenderData.lastClaim);
                if (claim != null && instance.claimIsPvPSafeZone(claim)) {
                    defenderData.lastClaim = claim;
                    event.setCancelled(true);
                    if (sendMessages) {
                        GriefPrevention.sendRateLimitedErrorMessage(attacker, Messages.CantFightWhileImmune);
                    }
                    return;
                }
            }
        }

        // Specific handling for PVP-enabled situations.
        if (instance.pvpRulesApply(event.damaged().getWorld())) {
            if (event.damaged() instanceof Player) {
                Player defender = (Player) event.damaged();
                // Protect players from other players' pets when protected from PVP.
                if (handlePvpDamageByPet(event, attacker, defender))
                    return;

                // Protect players from lingering splash potions when protected from PVP.
                if (handlePvpDamageByLingeringPotion(event, attacker, defender))
                    return;

                // Handle regular PVP with an attacker and defender.
                if (attacker != null && handlePvpDamageByPlayer(event, attacker, defender, sendMessages)) {
                    return;
                }
            } else if (event.damaged() instanceof Tameable) {
                Tameable tameable = (Tameable) event.damaged();
                if (attacker != null && handlePvpPetDamageByPlayer(event, tameable, attacker, sendMessages)) {
                    return;
                }
            }
        }

        // don't track in worlds where claims are not enabled
        if (!instance.claimsEnabledForWorld(event.damaged().getWorld()))
            return;

        // if the damaged entity is a claimed item frame or armor stand, the damager
        // needs to be a player with build trust in the claim
        if (handleClaimedBuildTrustDamageByEntity(event, attacker, sendMessages))
            return;

        // if the entity is a non-monster creature (remember monsters disqualified
        // above), or a vehicle
        if (handleCreatureDamageByEntity(event, attacker, arrow, sendMessages))
            return;
    }

    /**
     * Checks if the given entity is considered hostile.
     * <p>
     * Used to determine whether an entity should be protected in claims.
     * Hostile entities are never protected.
     *
     * @param entity the {@code Entity}
     * @return true if the {@code Entity} is hostile
     */
    public static boolean isHostile(@NotNull Entity entity)
    {
        if (entity instanceof Monster) return true;

        EntityType type = entity.getType();
        if (type == EntityType.GHAST || type == EntityType.MAGMA_CUBE)
            return true;

        if (isNamedEntityType(type, "SHULKER"))
            return true;

        if (entity instanceof Slime) {
            Slime slime = (Slime) entity;
            // Size 0 "baby" slimes cannot deal damage and are often kept as pets.
            // This is really inconvenient for players who are trying to harvest slimeballs
            // in areas with claims;
            // the full-sized slimes are considered dangerous, but the ones that actually
            // drop the slimeballs are not.
            // To make this protection less obnoxious, only protect baby slimes that have
            // lived for a minute or more.
            return slime.getSize() > 0 || slime.getTicksLived() < 1200;
        }

        if (entity instanceof Rabbit)
            return ((Rabbit) entity).getRabbitType() == Rabbit.Type.THE_KILLER_BUNNY;

        if ((getTemptableSemiHostiles().contains(type)) && entity instanceof Mob)
            return !hasPersistentData(entity, getLuredByPlayerKey()) && ((Mob) entity).getTarget() != null;

        return false;
    }

    /**
     * Handle damage to {@link Tameable} entities by environmental sources.
     *
     * @param event the {@link EntityDamageInstance}
     * @return true if the damage is handled
     */
    private boolean handlePetDamageByEnvironment(@NotNull EntityDamageInstance event) {
        // If PVP is enabled, the damaged entity is not a pet, or the pet has no owner,
        // allow.
        if (instance.pvpRulesApply(event.damaged().getWorld())
                || !(event.damaged() instanceof Tameable)
                || !((Tameable) event.damaged()).isTamed()) {
            return false;
        }
        switch (event.cause()) {
            // Block environmental and easy-to-cause damage sources.
            case BLOCK_EXPLOSION:
            case ENTITY_EXPLOSION:
            case FALLING_BLOCK:
            case FIRE:
            case FIRE_TICK:
            case LAVA:
            case SUFFOCATION:
            case CONTACT:
            case DROWNING:
                event.setCancelled(true);
                return true;
            default:
                return false;
        }
    }

    /**
     * Handle entity damage caused by block explosions.
     *
     * @param event the {@link EntityDamageInstance}
     * @return true if the damage is handled
     */
    private boolean handleEntityDamageByBlockExplosion(@NotNull EntityDamageInstance event) {
        if (event.cause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)
            return false;

        Entity entity = event.damaged();

        // Skip players - does allow players to use block explosions to bypass PVP
        // protections,
        // but also doesn't disable self-damage.
        if (entity instanceof Player)
            return false;

        Location location = entity.getLocation();
        Claim claim = dataStore.getClaimAt(location, false, null);
        if (claim == null)
            return false;
        // Check claim setting for explosives
        if (claim.areExplosivesAllowed)
            return false;

        event.setCancelled(true);
        return true;
    }

    /**
     * Handle PVP damage caused by a lingering splash potion.
     *
     * <p>
     * For logical simplicity, this method does not check the state of the PVP
     * rules. PVP rules should be confirmed
     * to be enabled before calling this method.
     *
     * @param event    the {@link EntityDamageInstance}
     * @param attacker the attacking {@link Player}, if any
     * @param damaged  the defending {@link Player}
     * @return true if the damage is handled
     */
    private boolean handlePvpDamageByLingeringPotion(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            @NotNull Player damaged) {
        Entity damager = event.damager();
        if (damager == null || !isNamedEntityType(damager.getType(), "AREA_EFFECT_CLOUD"))
            return false;

        // Only intervene when the cloud was actually produced by a player. Mob-sourced
        // AreaEffectClouds (most importantly the Ender Dragon's breath attack) are not
        // PvP and must not be cancelled here, otherwise dragon fights become trivial in
        // admin-claimed end islands. See upstream GriefPrevention/GriefPrevention#2577.
        if (!(invokeNoArg(damager, "getSource") instanceof Player))
            return false;

        PlayerData damagedData = dataStore.getPlayerData(damaged.getUniqueId());

        // case 1: recently spawned
        if (instance.config_pvp_protectFreshSpawns && damagedData.pvpImmune) {
            event.setCancelled(true);
            return true;
        }

        // case 2: in a pvp safe zone
        Claim damagedClaim = dataStore.getClaimAt(damaged.getLocation(), false, damagedData.lastClaim);
        if (damagedClaim != null) {
            damagedData.lastClaim = damagedClaim;
            if (instance.claimIsPvPSafeZone(damagedClaim)) {
                PreventPvPEvent pvpEvent = new PreventPvPEvent(damagedClaim, attacker, damaged);
                Bukkit.getPluginManager().callEvent(pvpEvent);
                if (!pvpEvent.isCancelled()) {
                    event.setCancelled(true);
                }
                return true;
            }
        }

        return false;
    }

    /**
     * General PVP handler.
     *
     * @param event        the {@link EntityDamageInstance}
     * @param attacker     the attacking {@link Player}
     * @param defender     the defending {@link Player}
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handlePvpDamageByPlayer(
            @NotNull EntityDamageInstance event,
            @NotNull Player attacker,
            @NotNull Player defender,
            boolean sendMessages) {
        if (attacker == defender)
            return false;

        PlayerData defenderData = this.dataStore.getPlayerData(defender.getUniqueId());
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());

        // FEATURE: prevent pvp in the first minute after spawn and when one or both
        // players have no inventory
        if (instance.config_pvp_protectFreshSpawns) {
            if (attackerData.pvpImmune || defenderData.pvpImmune) {
                event.setCancelled(true);
                if (sendMessages)
                    GriefPrevention.sendRateLimitedErrorMessage(
                            attacker,
                            attackerData.pvpImmune ? Messages.CantFightWhileImmune : Messages.ThatPlayerPvPImmune);
                return true;
            }
        }

        // FEATURE: prevent players from engaging in PvP combat inside land claims (when
        // it's disabled)
        // Ignoring claims bypasses this feature.
        boolean perClaimToggleEnabled = instance.config_pvp_toggleCostClaimEnabled
                || instance.config_pvp_toggleCostSubdivisionEnabled;
        if (attackerData.ignoreClaims
                || !perClaimToggleEnabled
                        && !instance.config_pvp_noCombatInPlayerLandClaims
                                && !instance.config_pvp_noCombatInAdminLandClaims) {
            return false;
        }
        Consumer<Messages> cancelHandler = message -> {
            event.setCancelled(true);
            if (sendMessages)
                GriefPrevention.sendRateLimitedErrorMessage(attacker, message);
        };
        // Return whether PVP is handled by a claim at the attacker or defender's
        // locations.
        return handlePvpInClaim(attacker, defender, attacker.getLocation(), attackerData,
                () -> cancelHandler.accept(Messages.CantFightWhileImmune))
                || handlePvpInClaim(attacker, defender, defender.getLocation(), defenderData,
                        () -> cancelHandler.accept(Messages.PlayerInPvPSafeZone));
    }

    /**
     * Handle PVP damage caused by an owned pet.
     *
     * @param event    the {@link EntityDamageInstance}
     * @param attacker the attacking {@link Player}, if any
     * @return true if the damage is handled
     */
    private boolean handlePvpDamageByPet(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            @NotNull Player defender) {
        if (!(event.damager() instanceof Tameable))
            return false;
        Tameable pet = (Tameable) event.damager();
        if (!pet.isTamed() || pet.getOwner() == null)
            return false;

        PlayerData defenderData = dataStore.getPlayerData(defender.getUniqueId());
        Runnable cancelHandler = () -> {
            event.setCancelled(true);
            // Tameable doesn't extend Creature pre-1.9, so the target is cleared through a checked cast.
            CompatUtil.clearTarget(pet);
        };

        // If the defender is PVP-immune, prevent the attack.
        if (defenderData.pvpImmune) {
            cancelHandler.run();
            return true;
        }

        // Return whether PVP is handled by a claim at the defender's location.
        return handlePvpInClaim(attacker, defender, defender.getLocation(), defenderData, cancelHandler);
    }

    /**
     * Handle PVP damage to an owned pet.
     *
     * @param event        the {@link EntityDamageInstance}
     * @param pet          the potential pet being damaged
     * @param attacker     the attacking {@link Player}
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handlePvpPetDamageByPlayer(
            @NotNull EntityDamageInstance event,
            @NotNull Tameable pet,
            @NotNull Player attacker,
            boolean sendMessages) {

        if (!pet.isTamed())
            return false;

        AnimalTamer owner = pet.getOwner();
        if (owner == null)
            return false;

        // If the player interacting is the owner, always allow.
        if (attacker.equals(owner))
            return true;

        // Allow admin override.
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());
        if (attackerData.ignoreClaims)
            return true;

        // Disallow provocations while PVP-immune.
        if (attackerData.pvpImmune) {
            event.setCancelled(true);
            if (sendMessages)
                GriefPrevention.sendRateLimitedErrorMessage(attacker, Messages.CantFightWhileImmune);
            return true;
        }

        // Wolves are exempt from pet protections in PVP worlds when their target is the
        // attacker
        if (event.damaged().getType() == EntityType.WOLF && CompatUtil.getTarget(pet) == attacker)
            return true;

        Claim claim;
        // Note: Internal name is not descriptive. Actual node is
        // "GriefPrevention.PVP.ProtectPetsOutsideLandClaims"
        if (!instance.config_pvp_protectPets) {
            claim = dataStore.getClaimAt(event.damaged().getLocation(), false, attackerData.lastClaim);
            if (claim == null) {
                // Pet is not in a claim, allow attack.
                return true;
            }
            attackerData.lastClaim = claim;
        } else {
            // Create a dummy claim to signify blanket pet protection.
            claim = new Claim(event.damaged().getLocation(), event.damaged().getLocation(), null, new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
        }

        PreventPvPEvent pvpEvent = new PreventPvPEvent(claim, attacker, pet);
        Bukkit.getPluginManager().callEvent(pvpEvent);
        if (!pvpEvent.isCancelled()) {
            event.setCancelled(true);
            if (sendMessages) {
                String ownerName = GriefPrevention.lookupPlayerName(owner);
                String message = dataStore.getMessage(Messages.NoDamageClaimedEntity, ownerName);
                if (attacker.hasPermission("griefprevention.ignoreclaims"))
                    message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                GriefPrevention.sendMessage(attacker, TextMode.Err, message);
            }
        }
        return true;
    }

    /**
     * Handle a PVP action depending on configured rules. Fires a
     * {@link PreventPvPEvent} to allow addons to change
     * default behavior.
     *
     * @param attacker      the attacking {@link Player}, or null for indirect PVP
     *                      like pet-induced damage
     * @param defender      the defending {@link Player}
     * @param location      the {@link Location} to be checked
     * @param playerData    the {@link PlayerData} used for caching last claim
     * @param cancelHandler the {@link Runnable} to run if PVP is disallowed
     * @return true if PVP is handled by claim rules
     */
    boolean handlePvpInClaim(
            @Nullable Player attacker,
            @NotNull Player defender,
            @NotNull Location location,
            @NotNull PlayerData playerData,
            @NotNull Runnable cancelHandler) {
        if (playerData.inPvpCombat())
            return false;

        Claim claim = this.dataStore.getClaimAt(location, false, playerData.lastClaim);

        if (claim == null || !instance.claimIsPvPSafeZone(claim))
            return false;

        playerData.lastClaim = claim;
        PreventPvPEvent pvpEvent = new PreventPvPEvent(claim, attacker, defender);
        Bukkit.getPluginManager().callEvent(pvpEvent);

        // if other plugins aren't making an exception to the rule
        if (!pvpEvent.isCancelled()) {
            cancelHandler.run();
        }
        return true;
    }

    /**
     * Handle actions requiring build trust.
     *
     * @param event        the {@link EntityDamageInstance}
     * @param attacker     the attacking {@link Player}, if any
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handleClaimedBuildTrustDamageByEntity(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            boolean sendMessages) {
        EntityType entityType = event.damaged().getType();
        if (entityType != EntityType.ITEM_FRAME
                && !isNamedEntityType(entityType, "GLOW_ITEM_FRAME")
                && entityType != EntityType.ARMOR_STAND
                && entityType != EntityType.VILLAGER
                && !isEndCrystal(entityType)
                // Item Displays have no hitbox, but display plugins may manually fire events
                // where appropriate.
                && !isNamedEntityType(entityType, "ITEM_DISPLAY")) {
            return false;
        }

        if (entityType == EntityType.VILLAGER
                // Allow disabling villager protections in the config.
                && (!instance.config_claims_protectCreatures
                        // Always allow zombies and raids to target villagers.
                        // why exception? so admins can set up a village which can't be CHANGED by
                        // players, but must be "protected" by players.
                        || event.damager() instanceof Zombie
                        || isRaidEntity(event.damager())
                        || isNamedEntity(event.damager(), "VEX")
                        || projectileShooterIsRaidEntity(event.damager())
                        || evokerFangsOwnerIsRaidEntity(event.damager()))) {
            return true;
        }

        // Use attacker's cached claim to speed up lookup.
        Claim cachedClaim = null;
        if (attacker != null) {
            PlayerData playerData = this.dataStore.getPlayerData(attacker.getUniqueId());
            cachedClaim = playerData.lastClaim;
        }

        Claim claim = this.dataStore.getClaimAt(event.damaged().getLocation(), false, cachedClaim);

        // If the area is not claimed, do not handle.
        if (claim == null)
            return false;

        // If attacker isn't a player, only allow armor stands to be broken by
        // projectiles (like from dispensers)
        // when the projectile source is within the same claim
        if (attacker == null) {
            // Allow projectiles (like arrows from dispensers) to break armor stands
            if (entityType == EntityType.ARMOR_STAND && event.damager() instanceof Projectile) {
                Projectile projectile = (Projectile) event.damager();
                // Get the projectile source (dispenser, etc.)
                ProjectileSource source = projectile.getShooter();
                if (source instanceof BlockProjectileSource) {
                    BlockProjectileSource blockSource = (BlockProjectileSource) source;
                    // Check if the dispenser is in the same claim as the armor stand
                    Claim sourceClaim = this.dataStore.getClaimAt(blockSource.getBlock().getLocation(), false, null);
                    if (sourceClaim != null && sourceClaim.getID().equals(claim.getID())) {
                        return false; // Allow the damage to proceed
                    }
                }
            }

            event.setCancelled(true);
            return true;
        }

        Supplier<String> failureReason = claim.checkPermission(attacker, ClaimPermission.Build, event.original());

        // If player has build trust, fall through to next checks.
        if (failureReason == null)
            return false;

        event.setCancelled(true);
        if (sendMessages)
            GriefPrevention.sendMessage(attacker, TextMode.Err, failureReason.get());
        return true;
    }

    /**
     * Handle damage to a {@link Creature} by an {@link Entity}. Because monsters
     * are
     * already discounted, any qualifying entity is livestock or a pet.
     *
     * @param event        the {@link EntityDamageInstance}
     * @param attacker     the attacking {@link Player}, if any
     * @param arrow        the {@link Projectile} dealing the damage, if any
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handleCreatureDamageByEntity(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            @Nullable Projectile arrow,
            boolean sendMessages) {
        if (!(event.damaged() instanceof Creature) || !instance.config_claims_protectCreatures)
            return false;

        // if entity is tameable and has an owner, apply special rules
        if (handlePetDamageByEntity(event, attacker, sendMessages))
            return true;

        Entity damageSource = event.damager();

        // Can't be hit, but for simplicity
        if (damageSource == null)
            return false;

        EntityType damageSourceType = damageSource.getType();
        // if not a player, explosive, or ranged/area of effect attack, allow
        if (attacker == null
                && damageSourceType != EntityType.CREEPER
                && damageSourceType != EntityType.WITHER
                && !isEndCrystal(damageSourceType)
                && !isNamedEntityType(damageSourceType, "AREA_EFFECT_CLOUD")
                && damageSourceType != EntityType.WITCH
                && !(damageSource instanceof Projectile)
                && !(damageSource instanceof Explosive)
                && !(damageSource instanceof ExplosiveMinecart)) {
            return true;
        }

        Claim cachedClaim = null;
        PlayerData playerData = null;
        if (attacker != null) {
            playerData = this.dataStore.getPlayerData(attacker.getUniqueId());
            cachedClaim = playerData.lastClaim;
        }

        Claim claim = this.dataStore.getClaimAt(event.damaged().getLocation(), false, cachedClaim);

        // Require a claim to handle.
        if (claim == null)
            return false;

        // If damaged by anything other than a player, cancel the event.
        if (attacker == null) {
            event.setCancelled(true);
            // Always remove projectiles shot by non-players.
            if (arrow != null)
                arrow.remove();
            return true;
        }

        // cache claim for later
        if (playerData != null) {
            playerData.lastClaim = claim;
        }

        // Do not message players about fireworks to prevent spam due to multi-hits.
        sendMessages &= !isNamedEntityType(damageSourceType, "FIREWORK_ROCKET");

        Supplier<String> override = null;
        if (sendMessages) {
            final Player finalAttacker = attacker;
            override = () -> {
                String message = dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
                if (finalAttacker.hasPermission("griefprevention.ignoreclaims"))
                    message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                return message;
            };
        }

        // Check for permission to access containers.
        Supplier<String> noContainersReason = claim.checkPermission(attacker, ClaimPermission.Container, event.original(), override);

        // If player has permission, action is allowed.
        if (noContainersReason == null)
            return true;

        event.setCancelled(true);

        // Prevent projectiles from bouncing infinitely.
        preventInfiniteBounce(arrow, event.damaged());

        if (sendMessages)
            GriefPrevention.sendMessage(attacker, TextMode.Err, noContainersReason.get());

        return true;
    }

    /**
     * Handle damage to a {@link Tameable} by a {@link Player}.
     *
     * @param event        the {@link EntityDamageInstance}
     * @param attacker     the attacking {@link Player}, if any
     * @param sendMessages whether to send denial messages to users involved
     * @return true if the damage is handled
     */
    private boolean handlePetDamageByEntity(
            @NotNull EntityDamageInstance event,
            @Nullable Player attacker,
            boolean sendMessages) {
        if (!(event.damaged() instanceof Tameable) || !((Tameable) event.damaged()).isTamed()) {
            // If the animal is not owned, specifically allow attacks only if the animal is
            // a wolf.
            return event.damaged().getType() == EntityType.WOLF;
        }

        Tameable tameable = (Tameable) event.damaged();
        AnimalTamer owner = tameable.getOwner();
        if (owner == null) {
            // Treat invalid state of tamed with no owner identically to untamed.
            return event.damaged().getType() == EntityType.WOLF;
        }

        // limit attacks by players to owners and admins in ignore claims mode
        if (attacker == null)
            return false;

        // if the player interacting is the owner, always allow
        if (attacker.equals(owner))
            return true;

        // allow for admin override
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());
        if (attackerData.ignoreClaims)
            return true;

        // Allow players to attack wolves (dogs) if under attack by them.
        if (event.damaged().getType() == EntityType.WOLF && CompatUtil.getTarget(tameable) == attacker) {
            return true;
        }

        event.setCancelled(true);
        if (sendMessages) {
            String ownerName = GriefPrevention.lookupPlayerName(owner);
            String message = dataStore.getMessage(Messages.NoDamageClaimedEntity, ownerName);
            if (attacker.hasPermission("griefprevention.ignoreclaims"))
                message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
            GriefPrevention.sendMessage(attacker, TextMode.Err, message);
        }
        return true;
    }

    /**
     * Prevent infinite bounces for cancelled projectile hits by removing or
     * grounding {@link Projectile Projectiles}
     * as necessary.
     *
     * @param projectile the {@code Projectile} that has been prevented from hitting
     * @param entity     the {@link Entity} being hit
     */
    private void preventInfiniteBounce(@Nullable Projectile projectile, @NotNull Entity entity) {
        if (projectile != null) {
            if (isNamedEntityType(projectile.getType(), "TRIDENT")) {
                // Instead of removing a trident, teleport it to the entity's foot location and
                // remove velocity.
                projectile.teleport(entity);
                projectile.setVelocity(new Vector());
            }
            // Otherwise remove the projectile.
            else
                projectile.remove();
        }
    }

    // Flag players engaging in PVP.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDamageByEntityMonitor(@NotNull EntityDamageByEntityEvent event) {
        // FEATURE: prevent players who very recently participated in pvp combat from
        // hiding inventory to protect it from looting
        // FEATURE: prevent players who are in pvp combat from logging out to avoid
        // being defeated

        // If there is no damage (snowballs, eggs, etc.) or the defender is not a player
        // in a PVP world, do nothing.
        if (event.getDamage() == 0
                || !(event.getEntity() instanceof Player)
                || !instance.pvpRulesApply(event.getEntity().getWorld()))
            return;
        Player defender = (Player) event.getEntity();

        // determine which player is attacking, if any
        Player attacker = null;
        Entity damageSource = event.getDamager();

        if (damageSource instanceof Player) {
            attacker = (Player) damageSource;
        } else if (damageSource instanceof Projectile && ((Projectile) damageSource).getShooter() instanceof Player) {
            attacker = (Player) ((Projectile) damageSource).getShooter();
        }

        // If not PVP or attacking self, do nothing.
        if (attacker == null || attacker == defender)
            return;

        PlayerData defenderData = this.dataStore.getPlayerData(defender.getUniqueId());
        PlayerData attackerData = this.dataStore.getPlayerData(attacker.getUniqueId());

        long now = Calendar.getInstance().getTimeInMillis();
        defenderData.lastPvpTimestamp = now;
        defenderData.lastPvpPlayer = attacker.getName();
        attackerData.lastPvpTimestamp = now;
        attackerData.lastPvpPlayer = defender.getName();
    }

    // when a vehicle is damaged
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onVehicleDamage(@NotNull VehicleDamageEvent event) {
        // all of this is anti theft code
        if (!instance.config_claims_preventTheft)
            return;

        // don't track in worlds where claims are not enabled
        if (!instance.claimsEnabledForWorld(event.getVehicle().getWorld()))
            return;

        // determine which player is attacking, if any
        Player attacker = null;
        Projectile arrow = null;
        Entity damageSource = event.getAttacker();
        EntityType damageSourceType = null;

        // if damage source is null or a creeper, don't allow the damage when the
        // vehicle is in a land claim
        if (damageSource != null) {
            damageSourceType = damageSource.getType();

            if (damageSource instanceof Player) {
                attacker = (Player) damageSource;
            } else if (damageSource instanceof Projectile) {
                arrow = (Projectile) damageSource;
                if (arrow.getShooter() instanceof Player) {
                    attacker = (Player) arrow.getShooter();
                }
            }
        }

        // if not a player and not an explosion, always allow
        if (attacker == null && damageSourceType != EntityType.CREEPER && damageSourceType != EntityType.WITHER
                && !isNamedEntityType(damageSourceType, "TNT", "PRIMED_TNT")) {
            return;
        }

        // NOTE: vehicles can be pushed around.
        // so unless precautions are taken by the owner, a resourceful thief might find
        // ways to steal anyway
        Claim cachedClaim = null;
        PlayerData playerData = null;

        if (attacker != null) {
            playerData = this.dataStore.getPlayerData(attacker.getUniqueId());
            cachedClaim = playerData.lastClaim;
        }

        Claim claim = this.dataStore.getClaimAt(event.getVehicle().getLocation(), false, cachedClaim);

        // Require a claim.
        if (claim == null)
            return;

        // if damaged by anything other than a player, cancel the event
        if (attacker == null) {
            event.setCancelled(true);
            if (arrow != null)
                arrow.remove();
            return;
        }

        // otherwise the player damaging the entity must have permission
        final Player finalAttacker = attacker;
        Supplier<String> override = () -> {
            String message = dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
            if (finalAttacker.hasPermission("griefprevention.ignoreclaims"))
                message += "  " + dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
            return message;
        };
        Supplier<String> noContainersReason = claim.checkPermission(attacker, ClaimPermission.Container, event, override);
        if (noContainersReason != null) {
            event.setCancelled(true);
            preventInfiniteBounce(arrow, event.getVehicle());
            GriefPrevention.sendMessage(attacker, TextMode.Err, noContainersReason.get());
        }

        // cache claim for later
        if (playerData != null) {
            playerData.lastClaim = claim;
        }
    }

    
    // when a splash potion affects one or more entities...
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPotionSplash(@NotNull PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();

        ProjectileSource projectileSource = potion.getShooter();
        // Ignore potions with no source.
        if (projectileSource == null)
            return;
        final Player thrower;
        if ((projectileSource instanceof Player))
            thrower = (Player) projectileSource;
        else
            thrower = null;
        AtomicBoolean messagedPlayer = new AtomicBoolean(false);

        Collection<PotionEffect> effects = potion.getEffects();
        for (PotionEffect effect : effects) {
            Object effectType = getPotionEffectType(effect);

            // Restrict some potions on claimed villagers and animals.
            // Griefers could use potions to kill entities or steal them over fences.
            if (getGriefEffects().contains(effectType)) {
                Claim cachedClaim = null;
                for (LivingEntity affected : event.getAffectedEntities()) {
                    // Always impact the thrower.
                    if (affected == thrower)
                        continue;

                    if (affected.getType() == EntityType.VILLAGER || affected instanceof Animals) {
                        Claim claim = this.dataStore.getClaimAt(affected.getLocation(), false, cachedClaim);
                        if (claim != null) {
                            cachedClaim = claim;

                            if (thrower == null) {
                                // Non-player source: Witches, dispensers, etc.
                                if (!EntityEventHandler.isBlockSourceInClaim(projectileSource, claim)) {
                                    // If the source is not a block in the same claim as the affected entity,
                                    // disallow.
                                    event.setIntensity(affected, 0);
                                }
                            } else {
                                // Source is a player. Determine if they have permission to access entities in the claim.
                                Supplier<String> override = () -> instance.dataStore
                                        .getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
                                final Supplier<String> noContainersReason = claim.checkPermission(thrower,
                                        ClaimPermission.Container, event, override);
                                if (noContainersReason != null) {
                                    event.setIntensity(affected, 0);
                                    if (messagedPlayer.compareAndSet(false, true)) {
                                        GriefPrevention.sendMessage(thrower, TextMode.Err, noContainersReason.get());
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Otherwise, ignore potions not thrown by players
            if (thrower == null)
                return;

            // otherwise, no restrictions for positive effects
            if (isBeneficialEffect(effectType))
                continue;

            for (LivingEntity affected : event.getAffectedEntities()) {
                // always impact the thrower
                if (affected == thrower)
                    continue;

                // always impact non players
                if (!(affected instanceof Player))
                    continue;
                Player affectedPlayer = (Player) affected;

                // otherwise if in no-pvp zone, stop effect
                // FEATURE: prevent players from engaging in PvP combat inside land claims (when
                // it's disabled)
                if (instance.config_pvp_noCombatInPlayerLandClaims || instance.config_pvp_noCombatInAdminLandClaims) {
                    PlayerData playerData = this.dataStore.getPlayerData(thrower.getUniqueId());
                    Consumer<Messages> cancelHandler = message -> {
                        event.setIntensity(affected, 0);
                        if (messagedPlayer.compareAndSet(false, true))
                            GriefPrevention.sendRateLimitedErrorMessage(thrower, message);
                    };
                    if (handlePvpInClaim(thrower, affectedPlayer, thrower.getLocation(), playerData,
                            () -> cancelHandler.accept(Messages.CantFightWhileImmune))) {
                        continue;
                    }
                    playerData = this.dataStore.getPlayerData(affectedPlayer.getUniqueId());
                    handlePvpInClaim(thrower, affectedPlayer, affectedPlayer.getLocation(), playerData,
                            () -> cancelHandler.accept(Messages.PlayerInPvPSafeZone));
                }
            }
        }
    }

    private static boolean isEndCrystal(@NotNull EntityType entityType) {
        return isNamedEntityType(entityType, "END_CRYSTAL", "ENDER_CRYSTAL");
    }

    private static boolean isNamedEntity(@Nullable Entity entity, @NotNull String... names) {
        return entity != null && isNamedEntityType(entity.getType(), names);
    }

    private static boolean isRaidEntity(@Nullable Entity entity) {
        return isNamedEntity(entity,
                "PILLAGER",
                "RAVAGER",
                "EVOKER",
                "VINDICATOR",
                "ILLUSIONER",
                "WITCH");
    }

    private static boolean projectileShooterIsRaidEntity(@Nullable Entity entity) {
        if (!(entity instanceof Projectile)) {
            return false;
        }

        ProjectileSource shooter = ((Projectile) entity).getShooter();
        return shooter instanceof Entity && isRaidEntity((Entity) shooter);
    }

    private static boolean evokerFangsOwnerIsRaidEntity(@Nullable Entity entity) {
        if (!isNamedEntity(entity, "EVOKER_FANGS")) {
            return false;
        }

        Object owner = invokeNoArg(entity, "getOwner");
        return owner instanceof Entity && isRaidEntity((Entity) owner);
    }

    private static @Nullable Object invokeNoArg(@NotNull Object target, @NotNull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isNamedEntityType(@Nullable EntityType entityType, @NotNull String... names) {
        if (entityType == null) {
            return false;
        }

        String actualName = entityType.name();
        for (String name : names) {
            if (actualName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static final class EntityDamageInstance {
        private final @NotNull Entity damaged;
        private final @Nullable Entity damager;
        private final @NotNull EntityDamageEvent.DamageCause cause;
        private final @NotNull Event original;

        private EntityDamageInstance(
                @NotNull Entity damaged,
                @Nullable Entity damager,
                @NotNull EntityDamageEvent.DamageCause cause,
                @NotNull Event original)
        {
            this.damaged = damaged;
            this.damager = damager;
            this.cause = cause;
            this.original = original;
        }

        EntityDamageInstance(@NotNull EntityDamageEvent event) {
            this(
                    event.getEntity(),
                    event instanceof EntityDamageByEntityEvent ? ((EntityDamageByEntityEvent) event).getDamager() : null,
                    event.getCause(),
                    event);
        }

        EntityDamageInstance(@NotNull EntityCombustEvent event) {
            this(
                    event.getEntity(),
                    event instanceof EntityCombustByEntityEvent ? ((EntityCombustByEntityEvent) event).getCombuster() : null,
                    EntityDamageEvent.DamageCause.FIRE_TICK,
                    event);
        }

        public void setCancelled(boolean cancelled) {
            if (this.original instanceof Cancellable)
                ((Cancellable) this.original).setCancelled(cancelled);
        }

        @NotNull Entity damaged() {
            return damaged;
        }

        @Nullable Entity damager() {
            return damager;
        }

        @NotNull EntityDamageEvent.DamageCause cause() {
            return cause;
        }

        @NotNull Event original() {
            return original;
        }
    }

}
