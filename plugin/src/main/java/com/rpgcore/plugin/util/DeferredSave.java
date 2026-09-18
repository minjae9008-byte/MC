package com.rpgcore.plugin.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Writes a yml file without spending the main thread on it.
 *
 * Every store in this plugin used to call save() on each change, and each save
 * rebuilt the whole file synchronously. Measured on a live server with fifty
 * guilds holding full vaults, one such save cost about 65 ms - longer than a
 * server tick - and closing a vault, making a deposit or winning a war each
 * paid it. Splitting that cost shows where it goes: assembling the data is
 * under a millisecond, and dumping it to YAML text is essentially all of it.
 *
 * So the two halves run in different places. {@link #markDirty()} costs
 * nothing and is what callers use; a periodic flush builds the snapshot on the
 * main thread, where reading live inventories is legal, and hands it to an
 * async write. A burst of changes in one tick collapses into one write, which
 * is the other half of the win: a busy auction used to write the whole book
 * once per bid.
 *
 * A store does NOT queue its own writes. {@link #pendingWrite()} hands the
 * write back to the caller, which submits the whole round of stores as one
 * task, in one fixed order. That order is not cosmetic: an auction lot that
 * sells moves an item from auctions.yml into mail.yml, and if the mailbox
 * lands first, a crash in between leaves the item in both files.
 *
 * Each store used to queue itself and loop on its own pending snapshot, which
 * let two stores drift apart however far the disk allowed. Measured: over 3,000
 * rounds that wrote both files in giver-then-receiver order, the receiving file
 * was ahead of the giving one 7 times, by as much as 649 rounds.
 *
 * Every snapshot also carries a stamp, and the file only ever moves forward. A
 * snapshot that has been overtaken is dropped rather than written, so a batch
 * write cannot undo a {@link #flushBlocking()} that overtook it. That is what
 * lets flushBlocking promise that what it wrote stays written - the whole basis
 * for trusting these files with an item that has just left someone's inventory.
 */
public final class DeferredSave {

    private final Plugin plugin;
    private final File file;
    /** Builds the snapshot. Called on the main thread, never off it. */
    private final Supplier<YamlConfiguration> builder;

    private boolean dirty;
    /**
     * Handed out in the order snapshots are built - which is a single order,
     * because every snapshot is built on the main thread.
     */
    private final AtomicLong stamps = new AtomicLong();
    /** The newest stamp on disk. Guarded by {@link #writeLock}. */
    private long written;
    /**
     * Held for the duration of every write. Two threads writing one file at
     * once does not produce the older of the two, it produces neither.
     */
    private final Object writeLock = new Object();
    /**
     * Set by the shutdown write. After it, the snapshot on disk is the newest
     * there will ever be, so an async write still holding an older one has to
     * be told to drop it rather than land on top.
     */
    private volatile boolean closed;

    public DeferredSave(Plugin plugin, String fileName,
                        Supplier<YamlConfiguration> builder) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), fileName);
        this.builder = builder;
    }

    /** Cheap. Says the file no longer matches memory; says nothing about when. */
    public void markDirty() {
        dirty = true;
    }

    public boolean dirty() {
        return dirty;
    }

    /**
     * Builds a snapshot if anything changed and returns the write that puts it
     * on disk, or null if nothing changed.
     *
     * The write is deliberately not submitted here. The caller collects one of
     * these from each store and submits them together, which is the only way
     * the file giving an item away is guaranteed to land before the file
     * receiving it.
     *
     * Called from the tick pump, so it must stay cheap on the main thread.
     */
    public Runnable pendingWrite() {
        if (!dirty) {
            return null;
        }
        // Cleared only once a snapshot exists: if assembling one throws, the
        // change is still pending rather than quietly never written.
        Stamped snapshot = snapshot();
        dirty = false;
        return () -> {
            synchronized (writeLock) {
                if (closed) {
                    // Shutdown already wrote something newer than this.
                    return;
                }
                write(snapshot);
            }
        };
    }

    /**
     * Builds and writes on the calling thread, for shutdown - which must not
     * return until the file is actually on disk. After this the writer stands
     * down: anything it was still holding is older than what just landed.
     */
    public void flushNow() {
        dirty = false;
        // Built before taking the lock: assembling reads live state and must
        // happen on this thread, and holding the lock across it would block a
        // writer for no reason.
        Stamped snapshot = snapshot();
        synchronized (writeLock) {
            // Anything queued or in flight is older than this by construction,
            // and the stamp on it will say so when it gets the lock.
            closed = true;
            write(snapshot);
        }
    }

    /**
     * Writes now, on this thread, and carries on accepting writes afterwards.
     *
     * Unlike {@link #flushNow()} this is not a shutdown: it exists for the
     * moment an item crosses between this file and a player's inventory, where
     * the two halves have to reach disk together or a crash between them
     * duplicates or loses the item.
     */
    public void flushBlocking() {
        if (closed) {
            return;
        }
        Stamped snapshot = snapshot();
        dirty = false;
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            write(snapshot);
        }
    }

    /** Builds a snapshot and stamps it. Main thread only, like the builder. */
    private Stamped snapshot() {
        YamlConfiguration config = builder.get();
        return new Stamped(stamps.incrementAndGet(), config);
    }

    /** Must be called holding {@link #writeLock}. */
    private void write(Stamped snapshot) {
        // Overtaken while this thread was on its way to the lock. Writing it
        // now would undo a newer state that is already on disk.
        if (snapshot.stamp() <= written) {
            return;
        }
        try {
            File folder = file.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                plugin.getLogger().severe("Could not create " + folder + " - " + file.getName()
                        + " will not be written.");
                return;
            }
            // Atomic: a crash between truncating this file and finishing the
            // write would otherwise leave an empty or half-written one, and
            // what these files hold is other people's items.
            AtomicYaml.save(snapshot.config(), file);
            // Only on success: a write that failed left the older file there,
            // so a snapshot newer than THAT is still worth landing.
            written = snapshot.stamp();
        } catch (IOException e) {
            plugin.getLogger().severe("Could not write " + file.getName() + ": " + e.getMessage());
        }
    }

    /** A snapshot and its place in the order they were built. */
    private record Stamped(long stamp, YamlConfiguration config) {
    }
}
