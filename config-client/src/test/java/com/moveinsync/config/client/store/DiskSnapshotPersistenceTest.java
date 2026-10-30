package com.moveinsync.config.client.store;

import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.ValidationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DiskSnapshotPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Persisting to disk and reloading reconstructs identical snapshot")
    void saveAndLoad() {
        Path snapshotPath = tempDir.resolve("cache").resolve("snapshot.json");
        DiskSnapshotPersistence persistence = new DiskSnapshotPersistence(snapshotPath);

        SettingDefinition def = new SettingDefinition(
                "booking.cutoff",
                SettingType.NUMBER,
                "Cutoff",
                ValidationRule.empty(),
                60.0
        );
        OverrideValue ov = new OverrideValue("booking.cutoff", Scope.client("clientA"), Level.CLIENT, 45.0);
        Snapshot original = new Snapshot(42L, Map.of(def.key(), def), Map.of(def.key(), Map.of(ov.scope(), ov)));

        // Act: Save to disk
        persistence.save(original);

        // Verify target file exists
        assertThat(Files.exists(snapshotPath)).isTrue();

        // Act: Load from disk
        Optional<Snapshot> loadedOpt = persistence.load();
        assertThat(loadedOpt).isPresent();
        Snapshot loaded = loadedOpt.get();

        // Assert
        assertThat(loaded.version()).isEqualTo(42L);
        assertThat(loaded.hasDefinition("booking.cutoff")).isTrue();
        assertThat(loaded.getOverride("booking.cutoff", Scope.client("clientA")).value()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("Non-existent or empty disk file returns empty Optional gracefully")
    void nonExistentOrEmptyFile() throws IOException {
        Path missing = tempDir.resolve("missing.json");
        DiskSnapshotPersistence persistence = new DiskSnapshotPersistence(missing);
        assertThat(persistence.load()).isEmpty();

        Path empty = tempDir.resolve("empty.json");
        Files.createFile(empty);
        DiskSnapshotPersistence emptyPersistence = new DiskSnapshotPersistence(empty);
        assertThat(emptyPersistence.load()).isEmpty();
    }
}
