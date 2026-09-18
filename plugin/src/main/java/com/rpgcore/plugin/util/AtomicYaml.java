package com.rpgcore.plugin.util;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a config file so that a crash cannot leave half of one behind.
 *
 * Bukkit's own {@code FileConfiguration.save(File)} opens a FileOutputStream
 * over the target, which truncates it to zero length before a single byte of
 * the new content is written. Everything between that truncation and the last
 * byte is a window in which the file on disk is empty or cut in half - and if
 * the process stops there, that is what is on disk when it comes back.
 *
 * The window is not theoretical. A fifty-guild file measured 571,293
 * characters and tens of milliseconds to write, it is rewritten whenever a
 * guild changes, and what it holds is every guild's vault: other people's
 * items. The same goes for the auction book, which holds escrowed items and
 * bids, and for the mailbox, which holds things the server already owes people.
 *
 * So the new content goes to a sibling temp file, is forced to disk, and is
 * then moved over the target in one step. A reader - the server on its next
 * start - sees either the whole old file or the whole new one, never a
 * fragment of either.
 */
public final class AtomicYaml {

    private AtomicYaml() {
    }

    /**
     * Saves {@code config} to {@code target}, replacing it atomically.
     *
     * @throws IOException if the content could not be written; the existing
     *                     file is left untouched in that case
     */
    public static void save(FileConfiguration config, File target) throws IOException {
        File folder = target.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("could not create " + folder);
        }

        // Built before anything on disk is touched: turning a config into text
        // is the expensive half and the half that can throw, and failing there
        // must not have cost the file that is already there.
        String data = config.saveToString();

        Path targetPath = target.toPath();
        // In the same directory, so the move below stays on one filesystem -
        // a rename across filesystems is a copy, and a copy is not atomic.
        Path temp = Files.createTempFile(
                folder == null ? targetPath.getParent() : folder.toPath(),
                target.getName(), ".tmp");
        try {
            Files.writeString(temp, data, StandardCharsets.UTF_8);
            // The rename can otherwise reach the disk before the bytes do,
            // which would publish a file whose contents are not there yet.
            force(temp);
            try {
                Files.move(temp, targetPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems cannot promise it. A plain replace is still
                // far better than truncating the original and writing into it.
                Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Only reachable when the move did not happen; a successful move
            // has already consumed the temp file.
            Files.deleteIfExists(temp);
        }
    }

    /** Pushes the file's bytes to the device, not just to the page cache. */
    private static void force(Path path) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.getFD().sync();
        }
    }
}
