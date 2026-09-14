package com.rpgcore.plugin.util;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The one thread that writes this plugin's files.
 *
 * Deliberately not Bukkit's async scheduler. That scheduler hands work to its
 * pool from the main thread's own heartbeat, so an async task only starts once
 * the main thread comes back round - which is fine in normal play and useless
 * in exactly the situations a background write is for: a main thread that is
 * busy, stalling, or on its way down. A thread of our own starts writing
 * immediately and keeps writing regardless of what the server is doing.
 *
 * One thread, not a pool, so writes happen in the order they were queued.
 * Files written by different stores land in submission order too, which is
 * what keeps an auction lot and the mailbox entry it paid into consistent
 * across a crash.
 */
public final class SaveQueue {

    private final Plugin plugin;
    private final ExecutorService executor;

    public SaveQueue(Plugin plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "RPGCore-save");
            // Daemon: a stuck write must never be the reason a server cannot
            // finish shutting down. Shutdown drains the queue explicitly.
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queues a write. Silently ignored once the queue is closed. */
    public void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // Shutting down: whatever this was, the synchronous final write
            // has already covered it or is about to.
            task.run();
        }
    }

    /**
     * Stops accepting work and waits a bounded time for what is queued.
     * Called after the stores have each written synchronously, so anything
     * still in flight here is already superseded - the wait is only so the
     * process does not exit mid-write and leave a half-written file.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("A file write did not finish within ten seconds; "
                        + "giving up on it so shutdown can continue.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
