package com.moveinsync.config.client.proofs;

import com.moveinsync.config.client.ConfigView;
import com.moveinsync.config.client.ConfigViewImpl;
import com.moveinsync.config.client.store.SnapshotStore;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.OverrideValue;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingType;
import com.moveinsync.config.core.model.Snapshot;
import com.moveinsync.config.core.model.ValidationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROOF (a): Atomic Multi-Key Consistency under High-Concurrency Mutation.
 *
 * <p><strong>The Core Challenge:</strong>
 * In a distributed tenant configuration system, correlated settings (e.g. night safety start and end)
 * must be updated together. In naive mutable systems, concurrent readers can observe
 * the new start with the old end, leading to catastrophic runtime anomalies (e.g. invalid escort periods).
 *
 * <p><strong>The Mathematical Guarantee:</strong>
 * 1. The writer alternates the two-key pair between:
 *    - State A: start=20.0, end=4.0
 *    - State B: start=22.0, end=6.0
 * 2. Eight concurrent reader threads execute continuous, non-blocking reads.
 *    Each reader captures an immutable {@link ConfigView} and verifies that the two keys
 *    belong to the EXACT same state.
 * 3. <strong>Invariant:</strong> The reader MUST NEVER observe (20.0, 6.0) or (22.0, 4.0).
 */
public class AtomicMixedStateConcurrencyTest {

    private static final String KEY_START = "night.safety.start";
    private static final String KEY_END = "night.safety.end";
    private static final Scope SCOPE = Scope.client("client-uber");

    @Test
    @DisplayName("Proof (a): Continuous reads during rapid alternating writes NEVER observe a mixed state")
    void verifyZeroMixedStatesUnderConcurrentWrites() throws InterruptedException {
        SettingDefinition startDef = new SettingDefinition(KEY_START, SettingType.NUMBER, "Start", ValidationRule.empty(), 20.0);
        SettingDefinition endDef = new SettingDefinition(KEY_END, SettingType.NUMBER, "End", ValidationRule.empty(), 4.0);
        Map<String, SettingDefinition> definitions = Map.of(KEY_START, startDef, KEY_END, endDef);

        // State A: (20.0, 4.0)
        Snapshot stateA = new Snapshot(
                1L,
                definitions,
                Map.of(
                        KEY_START, Map.of(SCOPE, new OverrideValue(KEY_START, SCOPE, Level.CLIENT, 20.0)),
                        KEY_END, Map.of(SCOPE, new OverrideValue(KEY_END, SCOPE, Level.CLIENT, 4.0))
                )
        );

        // State B: (22.0, 6.0)
        Snapshot stateB = new Snapshot(
                2L,
                definitions,
                Map.of(
                        KEY_START, Map.of(SCOPE, new OverrideValue(KEY_START, SCOPE, Level.CLIENT, 22.0)),
                        KEY_END, Map.of(SCOPE, new OverrideValue(KEY_END, SCOPE, Level.CLIENT, 6.0))
                )
        );

        SnapshotStore store = new SnapshotStore(stateA);

        int readerCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicBoolean stopSignal = new AtomicBoolean(false);

        AtomicLong totalReads = new AtomicLong(0);
        AtomicLong tornReads = new AtomicLong(0);
        AtomicLong totalWrites = new AtomicLong(0);

        // 1. Writer Thread: alternates rapidly between State A and State B
        pool.submit(() -> {
            try {
                startGate.await();
                long version = 3L;
                while (!stopSignal.get()) {
                    Snapshot target = (version % 2 == 1) ? stateA : stateB;
                    Snapshot next = new Snapshot(version, target.definitions(), target.overridesByKey());
                    store.updateIfNewer(next);
                    totalWrites.incrementAndGet();
                    version++;
                    // Extremely tight loop: minimal yield to maximize contention
                    if (version % 100 == 0) {
                        Thread.yield();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 2. Reader Threads: continuous verification of multi-key atomicity
        CountDownLatch readersDone = new CountDownLatch(readerCount);
        for (int i = 0; i < readerCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    while (!stopSignal.get()) {
                        // Capture atomic snapshot view for this request
                        ConfigView view = new ConfigViewImpl(store.get(), SCOPE);
                        double start = view.getDouble(KEY_START);
                        double end = view.getDouble(KEY_END);

                        // Check consistency invariant
                        boolean isStateA = (start == 20.0 && end == 4.0);
                        boolean isStateB = (start == 22.0 && end == 6.0);

                        if (!isStateA && !isStateB) {
                            // TORN READ DETECTED!
                            tornReads.incrementAndGet();
                        }
                        totalReads.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    readersDone.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startGate.countDown();

        // Let the test run under heavy load for 2.5 seconds
        Thread.sleep(2500);
        stopSignal.set(true);

        readersDone.await(5, TimeUnit.SECONDS);
        pool.shutdownNow();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        // Assertions & Proof
        System.out.printf("[Proof a - Mixed State Test] Total Writes: %,d | Total Reads: %,d | Torn/Mixed Reads: %d%n",
                totalWrites.get(), totalReads.get(), tornReads.get());

        assertThat(totalReads.get()).as("Reader threads must have executed substantial iterations").isGreaterThan(100_000L);
        assertThat(totalWrites.get()).as("Writer thread must have alternated versions").isGreaterThan(10_000L);
        assertThat(tornReads.get()).as("Torn reads must be mathematically ZERO due to immutable snapshot views").isZero();
    }
}
