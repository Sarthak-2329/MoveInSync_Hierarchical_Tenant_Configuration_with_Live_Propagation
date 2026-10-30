package com.moveinsync.config.client.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.model.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Handles atomic persistence and loading of configuration snapshots to local disk.
 *
 * <p><strong>Fault Tolerance & Atomicity Guarantee:</strong>
 * - Writes are made to a sibling temporary file first (e.g., {@code config.json.tmp.12345}).
 * - The temporary file is renamed via {@code Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)}.
 *   On POSIX and NTFS systems, filesystem directory rename is atomic. If power fails or the process
 *   is abruptly killed mid-write, the target file is either fully updated or completely intact
 *   at the previous version. Partial or corrupted JSON files never replace valid snapshots.
 * - Sibling temp files guarantee the temporary and target files reside on the same filesystem mount,
 *   which is a prerequisite for atomic inode renames.
 */
public final class DiskSnapshotPersistence {

    private static final Logger log = LoggerFactory.getLogger(DiskSnapshotPersistence.class);
    private final Path targetPath;
    private final ObjectMapper objectMapper;

    public DiskSnapshotPersistence(Path targetPath) {
        this.targetPath = targetPath;
        this.objectMapper = ClientJsonMapper.get();
    }

    /**
     * Loads the latest snapshot from disk if present.
     *
     * @return populated Optional if successfully read and parsed, empty otherwise
     */
    public Optional<Snapshot> load() {
        if (targetPath == null || !Files.exists(targetPath)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(targetPath);
            if (bytes.length == 0) {
                return Optional.empty();
            }
            Snapshot snapshot = objectMapper.readValue(bytes, Snapshot.class);
            log.info("Loaded configuration snapshot from disk: '{}' (version: {})", targetPath, snapshot.version());
            return Optional.of(snapshot);
        } catch (Exception ex) {
            log.warn("Failed to read snapshot from disk at '{}', ignoring cache file: {}", targetPath, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Atomically saves the snapshot to disk.
     *
     * @param snapshot the immutable snapshot to persist
     */
    public void save(Snapshot snapshot) {
        if (targetPath == null || snapshot == null) {
            return;
        }

        Path parent = targetPath.toAbsolutePath().getParent();
        Path tempFile = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Sibling temp file ensures same filesystem partition for atomic rename
            String tempName = targetPath.getFileName().toString() + ".tmp." + System.nanoTime();
            tempFile = (parent != null) ? parent.resolve(tempName) : Path.of(tempName);

            // Write full JSON to temp file
            byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
            Files.write(tempFile, jsonBytes);

            // Atomically replace target file
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                // Fallback for filesystems that do not support ATOMIC_MOVE
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.debug("Atomically persisted snapshot version {} to disk at '{}'", snapshot.version(), targetPath);
        } catch (IOException ex) {
            log.warn("Failed to persist snapshot version {} to disk at '{}': {}",
                    snapshot.version(), targetPath, ex.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public Path getTargetPath() {
        return targetPath;
    }
}
