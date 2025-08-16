package me.ryanhamshire.GriefPrevention.util;

import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A lightweight abstraction over a scheduled task that supports both Bukkit and Folia.
 */
public final class TaskHandle {
    private final @Nullable BukkitTask bukkitTask;
    private final @Nullable Object foliaTask; // io.papermc.paper.threadedregions.scheduler.ScheduledTask
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    TaskHandle(BukkitTask bukkitTask) {
        this.bukkitTask = bukkitTask;
        this.foliaTask = null;
    }

    TaskHandle(Object foliaTask) {
        this.bukkitTask = null;
        this.foliaTask = foliaTask;
    }

    /** Cancel the task if possible. */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            try {
                if (bukkitTask != null) {
                    bukkitTask.cancel();
                } else if (foliaTask != null) {
                    // foliaTask is ScheduledTask with a cancel() method
                    Method cancel = foliaTask.getClass().getMethod("cancel");
                    cancel.invoke(foliaTask);
                }
            } catch (Throwable ignored) {
                // Best-effort cancel
            }
        }
    }

    /** Returns true if we believe the task is still scheduled (best-effort). */
    public boolean isScheduled() {
        if (cancelled.get()) return false;
        try {
            if (bukkitTask != null) {
                return !bukkitTask.isCancelled();
            } else if (foliaTask != null) {
                // No public isCancelled on ScheduledTask; assume scheduled until cancel() called
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
