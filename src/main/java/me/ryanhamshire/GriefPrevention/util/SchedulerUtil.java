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

    // Helper to obtain Folia's GlobalRegionScheduler via reflection (static or instance accessor)
    private static Object getGlobalScheduler() throws Exception {
        try {
            Method staticGetter = Bukkit.class.getMethod("getGlobalRegionScheduler");
            return staticGetter.invoke(null);
        } catch (Throwable ignored) {
            Object server = Bukkit.getServer();
            Method instanceGetter = server.getClass().getMethod("getGlobalRegionScheduler");
            return instanceGetter.invoke(server);
        }
    }

    // Helper to obtain Folia's AsyncScheduler via reflection (static or instance accessor)
    private static Object getAsyncScheduler() throws Exception {
        try {
            Method staticGetter = Bukkit.class.getMethod("getAsyncScheduler");
            return staticGetter.invoke(null);
        } catch (Throwable ignored) {
            Object server = Bukkit.getServer();
            Method instanceGetter = server.getClass().getMethod("getAsyncScheduler");
            return instanceGetter.invoke(server);
        }
    }

    public static TaskHandle runLaterGlobal(Plugin plugin, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                long safeDelay = Math.max(1L, delayTicks);
                Consumer<Object> consumer = (ignored2) -> runnable.run();
                try {
                    // Prefer signature without TimeUnit
                    Method runDelayed = global.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                    Object scheduled = runDelayed.invoke(global, plugin, consumer, safeDelay);
                    return new TaskHandle(scheduled);
                } catch (NoSuchMethodException e) {
                    // Fallback to signature with TimeUnit
                    Method runDelayed = global.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
                    long delayMs = safeDelay * 50L;
                    Object scheduled = runDelayed.invoke(global, plugin, consumer, delayMs, TimeUnit.MILLISECONDS);
                    return new TaskHandle(scheduled);
                }
            } catch (Throwable t) {
                // On Folia, do not use Bukkit scheduler fallback
                throw new UnsupportedOperationException("Folia detected but failed to schedule on GlobalRegionScheduler", t);
            }
        }
        // Non-Folia fallback
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        return new TaskHandle(task);
    }

    public static TaskHandle runRepeatingGlobal(Plugin plugin, Runnable runnable, long delayTicks, long periodTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                Object global = getGlobalScheduler();
                long safeDelay = Math.max(1L, delayTicks);
                long safePeriod = Math.max(1L, periodTicks);
                Consumer<Object> consumer = (ignored2) -> runnable.run();
                try {
                    // Prefer signature without TimeUnit
                    Method runAtFixedRate = global.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                    Object scheduled = runAtFixedRate.invoke(global, plugin, consumer, safeDelay, safePeriod);
                    return new TaskHandle(scheduled);
                } catch (NoSuchMethodException e) {
                    // Fallback to signature with TimeUnit
                    Method runAtFixedRate = global.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                    long delayMs = safeDelay * 50L;
                    long periodMs = safePeriod * 50L;
                    Object scheduled = runAtFixedRate.invoke(global, plugin, consumer, delayMs, periodMs, TimeUnit.MILLISECONDS);
                    return new TaskHandle(scheduled);
                }
            } catch (Throwable t) {
                // On Folia, do not use Bukkit scheduler fallback
                throw new UnsupportedOperationException("Folia detected but failed to schedule repeating task on GlobalRegionScheduler", t);
            }
        }
        // Non-Folia fallback
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
        return new TaskHandle(task);
    }

    // Schedules a task on Folia's AsyncScheduler (or Bukkit async fallback) immediately.
    public static TaskHandle runAsyncNow(Plugin plugin, Runnable runnable) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                Object async = getAsyncScheduler();
                Consumer<Object> consumer = (ignored2) -> runnable.run();
                // Prefer runNow(Plugin, Consumer)
                for (Method m : async.getClass().getMethods()) {
                    if (!m.getName().equals("runNow")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length >= 2 && Plugin.class.isAssignableFrom(params[0]) && Consumer.class.isAssignableFrom(params[1])) {
                        Object scheduled = (params.length == 2)
                                ? m.invoke(async, plugin, consumer)
                                : m.invoke(async, plugin, consumer, (Object[]) java.lang.reflect.Array.newInstance(Object.class, params.length - 2));
                        return new TaskHandle(scheduled);
                    }
                }
                // Fallback: emulate immediate with zero-delay runDelayed
                try {
                    Method runDelayed = async.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                    Object scheduled = runDelayed.invoke(async, plugin, consumer, 0L);
                    return new TaskHandle(scheduled);
                } catch (NoSuchMethodException e) {
                    Method runDelayed = async.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
                    Object scheduled = runDelayed.invoke(async, plugin, consumer, 0L, TimeUnit.MILLISECONDS);
                    return new TaskHandle(scheduled);
                }
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Folia detected but failed to schedule on AsyncScheduler", t);
            }
        }
        // Non-Folia fallback
        BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        return new TaskHandle(task);
    }

    // Schedules a task on Folia's AsyncScheduler (or Bukkit async fallback) after a delay in ticks.
    public static TaskHandle runAsyncLater(Plugin plugin, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(runnable);
        long safeDelay = Math.max(0L, delayTicks);
        if (FOLIA_PRESENT) {
            try {
                Object async = getAsyncScheduler();
                Consumer<Object> consumer = (ignored2) -> runnable.run();
                try {
                    Method runDelayed = async.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                    Object scheduled = runDelayed.invoke(async, plugin, consumer, safeDelay);
                    return new TaskHandle(scheduled);
                } catch (NoSuchMethodException e) {
                    Method runDelayed = async.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
                    long delayMs = safeDelay * 50L;
                    Object scheduled = runDelayed.invoke(async, plugin, consumer, delayMs, TimeUnit.MILLISECONDS);
                    return new TaskHandle(scheduled);
                }
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Folia detected but failed to schedule delayed task on AsyncScheduler", t);
            }
        }
        // Non-Folia fallback
        BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, safeDelay);
        return new TaskHandle(task);
    }

    public static TaskHandle runLaterEntity(Plugin plugin, Entity entity, Runnable runnable, long delayTicks) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(runnable);
        if (FOLIA_PRESENT) {
            try {
                // entity.getScheduler().runDelayed(plugin, Consumer<ScheduledTask>, delay[, TimeUnit])
                Method getScheduler = entity.getClass().getMethod("getScheduler");
                Object scheduler = getScheduler.invoke(entity);
                long safeDelay = Math.max(1L, delayTicks);
                Consumer<Object> consumer = (ignored2) -> runnable.run();

                // Try to find a compatible runDelayed overload dynamically (parameter order may vary across builds)
                Method[] methods = scheduler.getClass().getMethods();
                for (Method m : methods) {
                    if (!m.getName().equals("runDelayed")) continue;
                    Class<?>[] params = m.getParameterTypes();

                    // We support 3 to 5 parameters with these required types (in any order):
                    // - Plugin
                    // - long (delay) OR java.time.Duration
                    // - Consumer or Runnable (task)
                    // Optional:
                    // - TimeUnit (if present, delay is interpreted with that unit)
                    // - SchedulerPriority (if present, default to NORMAL)
                    if (params.length < 3 || params.length > 5) continue;

                    int idxPlugin = -1, idxDelay = -1, idxTimeUnit = -1, idxTask = -1, idxPriority = -1; // task is Runnable or Consumer
                    boolean taskIsConsumer = false;
                    boolean delayIsDuration = false;

                    for (int i = 0; i < params.length; i++) {
                        Class<?> p = params[i];
                        if (idxPlugin == -1 && Plugin.class.isAssignableFrom(p)) {
                            idxPlugin = i;
                            continue;
                        }
                        if (idxDelay == -1) {
                            if (p == long.class || p == Long.TYPE) {
                                idxDelay = i;
                                delayIsDuration = false;
                                continue;
                            }
                            // Support java.time.Duration without importing the class
                            if ("java.time.Duration".equals(p.getName())) {
                                idxDelay = i;
                                delayIsDuration = true;
                                continue;
                            }
                        }
                        if (idxTimeUnit == -1 && p == TimeUnit.class) {
                            idxTimeUnit = i;
                            continue;
                        }
                        // Detect priority enum by name to avoid compile-time dependency
                        if (idxPriority == -1 && p.isEnum()) {
                            String n = p.getName();
                            if (n.endsWith("SchedulerPriority") || n.endsWith("ScheduledTask$Priority") || n.contains("SchedulerPriority") || n.endsWith("TaskPriority") || n.contains("Priority")) {
                                idxPriority = i;
                                continue;
                            }
                        }
                        if (idxTask == -1) {
                            if (Consumer.class.isAssignableFrom(p)) {
                                idxTask = i;
                                taskIsConsumer = true;
                                continue;
                            }
                            if (Runnable.class.isAssignableFrom(p)) {
                                idxTask = i;
                                taskIsConsumer = false;
                                continue;
                            }
                        }
                    }

                    // Validate we found the essentials
                    if (idxPlugin == -1 || idxDelay == -1 || idxTask == -1) {
                        continue;
                    }
                    if (params.length >= 4 && idxTimeUnit == -1 && !delayIsDuration && idxPriority == -1 && params.length == 4) {
                        // 4 params but no TimeUnit and delay isn't Duration and no priority => not a match we support
                        continue;
                    }

                    // Build arguments in the method's declared order
                    Object[] args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        if (i == idxPlugin) {
                            args[i] = plugin;
                        } else if (i == idxDelay) {
                            if (delayIsDuration) {
                                try {
                                    Class<?> durationClass = Class.forName("java.time.Duration");
                                    Method ofMillis = durationClass.getMethod("ofMillis", long.class);
                                    args[i] = ofMillis.invoke(null, safeDelay * 50L);
                                } catch (Throwable reflectiveError) {
                                    // If Duration construction fails for some reason, skip this overload
                                    args = null;
                                    break;
                                }
                            } else if (idxTimeUnit != -1) {
                                // TimeUnit present: provide milliseconds
                                args[i] = safeDelay * 50L;
                            } else {
                                // Ticks
                                args[i] = safeDelay;
                            }
                        } else if (i == idxTimeUnit) {
                            args[i] = TimeUnit.MILLISECONDS;
                        } else if (i == idxPriority) {
                            // Default to NORMAL priority
                            try {
                                Class<?> enumClass = params[i];
                                Object[] constants = enumClass.getEnumConstants();
                                Object normal = null;
                                for (Object c : constants) {
                                    if (String.valueOf(c).equalsIgnoreCase("NORMAL")) { normal = c; break; }
                                }
                                if (normal == null && constants.length > 0) normal = constants[0];
                                args[i] = normal;
                            } catch (Throwable ignored3) {
                                args[i] = null; // should not happen, but fill placeholder
                            }
                        } else if (i == idxTask) {
                            args[i] = taskIsConsumer ? consumer : runnable;
                        }
                    }

                    if (args == null) continue; // Try next overload if Duration reflection failed

                    Object scheduled = m.invoke(scheduler, args);
                    return new TaskHandle(scheduled);
                }

                // If we reached here, no compatible method was found.
                // Fallback: schedule delay on GlobalRegionScheduler, then immediately run on the entity's scheduler using an immediate method (run/execute).
                return runLaterGlobal(plugin, () -> {
                    try {
                        Method getScheduler2 = entity.getClass().getMethod("getScheduler");
                        Object scheduler2 = getScheduler2.invoke(entity);
                        // Find an immediate execution method: "run" or "execute"
                        Method immediate = null;
                        for (Method m : scheduler2.getClass().getMethods()) {
                            String name = m.getName();
                            if (!name.equals("run") && !name.equals("execute")) continue;
                            Class<?>[] params = m.getParameterTypes();
                            if (params.length < 2 || params.length > 4) continue;
                            boolean hasPlugin = false, hasTask = false; int idxP = -1, idxT = -1, idxPr = -1;
                            boolean taskIsConsumerNow = false;
                            for (int i = 0; i < params.length; i++) {
                                Class<?> p = params[i];
                                if (!hasPlugin && Plugin.class.isAssignableFrom(p)) { hasPlugin = true; idxP = i; }
                                if (!hasTask && (Consumer.class.isAssignableFrom(p) || Runnable.class.isAssignableFrom(p))) {
                                    hasTask = true; idxT = i; taskIsConsumerNow = Consumer.class.isAssignableFrom(p);
                                }
                                if (idxPr == -1 && p.isEnum()) {
                                    String n = p.getName();
                                    if (n.endsWith("SchedulerPriority") || n.endsWith("ScheduledTask$Priority") || n.contains("Priority")) idxPr = i;
                                }
                            }
                            if (!hasPlugin || !hasTask) continue;
                            // Build args
                            Object[] args = new Object[params.length];
                            for (int i = 0; i < params.length; i++) {
                                if (i == idxP) args[i] = plugin;
                                else if (i == idxT) args[i] = taskIsConsumerNow ? (Consumer<Object>) (ignored) -> runnable.run() : runnable;
                                else if (i == idxPr) {
                                    try {
                                        Class<?> enumClass = params[i];
                                        Object[] constants = enumClass.getEnumConstants();
                                        Object normal = null;
                                        for (Object c : constants) {
                                            if (String.valueOf(c).equalsIgnoreCase("NORMAL")) { normal = c; break; }
                                        }
                                        if (normal == null && constants.length > 0) normal = constants[0];
                                        args[i] = normal;
                                    } catch (Throwable ignored4) {
                                        args[i] = null;
                                    }
                                }
                            }
                            immediate = m;
                            immediate.invoke(scheduler2, args);
                            return; // success
                        }
                        throw new UnsupportedOperationException("Folia detected but no compatible immediate EntityScheduler#run/execute overload found on " + scheduler2.getClass().getName());
                    } catch (Throwable inner) {
                        throw new RuntimeException(inner);
                    }
                }, safeDelay);
            } catch (Throwable t) {
                // On Folia, do not use Bukkit scheduler fallback
                throw new UnsupportedOperationException("Folia detected but failed to schedule entity task", t);
            }
        }
        // Non-Folia fallback
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        return new TaskHandle(task);
    }

    // Convenience overload for Player
    public static TaskHandle runLaterEntity(Plugin plugin, Player player, Runnable runnable, long delayTicks) {
        return runLaterEntity(plugin, (Entity) player, runnable, delayTicks);
    }
}
