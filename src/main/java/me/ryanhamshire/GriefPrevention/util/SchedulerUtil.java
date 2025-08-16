package me.ryanhamshire.GriefPrevention.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Folia-compatible scheduling helpers with Bukkit fallback.
 */
public final class SchedulerUtil {
    private static final boolean FOLIA_PRESENT = hasMethod(Bukkit.getServer().getClass(), "getGlobalRegionScheduler");

    private SchedulerUtil() {}

    private static boolean hasMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }

    public static TaskHandle runLaterGlobal(Plugin plugin, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                Object global = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Method runDelayed = global.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                Consumer<Object> consumer = (ignored) -> runnable.run();
                Object scheduled = runDelayed.invoke(global, plugin, consumer, delayTicks);
                return new TaskHandle(scheduled);
            } catch (Throwable t) {
                // Fallback best effort
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        return new TaskHandle(task);
    }

    public static TaskHandle runRepeatingGlobal(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                Object global = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Method runAtFixedRate = global.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                Consumer<Object> consumer = (ignored) -> runnable.run();
                Object scheduled = runAtFixedRate.invoke(global, plugin, consumer, delayTicks, periodTicks);
                return new TaskHandle(scheduled);
            } catch (Throwable t) {
                // Fallback best effort
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
        return new TaskHandle(task);
    }

    public static TaskHandle runLaterEntity(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                // entity.getScheduler().runDelayed(plugin, Consumer<ScheduledTask>, delay)
                Method getScheduler = entity.getClass().getMethod("getScheduler");
                Object scheduler = getScheduler.invoke(entity);
                Method runDelayed = scheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                Consumer<Object> consumer = (ignored) -> runnable.run();
                Object scheduled = runDelayed.invoke(scheduler, plugin, consumer, delayTicks);
                return new TaskHandle(scheduled);
            } catch (Throwable t) {
                // Fallback best effort
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        return new TaskHandle(task);
    }

    // Convenience overload for Player
    public static TaskHandle runLaterEntity(Plugin plugin, Player player, Runnable runnable, long delayTicks) {
        return runLaterEntity(plugin, (Entity) player, runnable, delayTicks);
    }
}
