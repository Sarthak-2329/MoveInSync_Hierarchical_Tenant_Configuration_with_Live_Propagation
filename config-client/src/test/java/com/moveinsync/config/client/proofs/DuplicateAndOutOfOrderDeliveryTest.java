package com.moveinsync.config.client.proofs;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROOF (f): Duplicate and Out-of-Order Message Delivery Resilience.
 *
 * <p><strong>The Core Challenge & Interview Question:</strong>
 * "Versions must only move forward. Explain what a client does when it receives version 41 after version 43."
 *
 * <p><strong>The Architectural Resolution:</strong>
 * In real-world networks, concurrent SSE push hints, HTTP response delays, or retried TCP packets
 * can cause messages to arrive out of order.
 *
 * <p>The client library enforces strict monotonicity via {@link SnapshotStore#updateIfNewer(Snapshot)}:
 * <pre>
 *   if (newSnapshot.version() &lt;= current.version()) {
 *       return false; // Drop immediately
 *   }
 * </pre>
 *
 * <p><strong>Test Scenario:</strong>
 * 1. Client starts at version 40.
 * 2. Client receives version 43 (booking.cutoff = 30.0) -> APPLIED.
 * 3. Client receives delayed out-of-order version 41 (booking.cutoff = 45.0) -> DROPPED.
 * 4. Client receives duplicate version 43 -> DROPPED.
 * 5. Client receives version 44 (booking.cutoff = 20.0) -> APPLIED.
 */
public class DuplicateAndOutOfOrderDeliveryTest {

    private static final String KEY = "booking.cutoff.minutes";
    private static final Scope SCOPE = Scope.client("clientA");

    @Test
    @DisplayName("Proof (f): Receiving version 41 after version 43 is strictly dropped; versions only move forward")
    void testOutOfOrderAndDuplicateRejections() {
        SettingDefinition def = new SettingDefinition(KEY, SettingType.NUMBER, "Cutoff", ValidationRule.empty(), 60.0);
        Map<String, SettingDefinition> defs = Map.of(KEY, def);

        // Snapshot versions
        Snapshot v40 = createSnapshot(40L, defs, 50.0);
        Snapshot v41 = createSnapshot(41L, defs, 45.0);
        Snapshot v43 = createSnapshot(43L, defs, 30.0);
        Snapshot v44 = createSnapshot(44L, defs, 20.0);

        // 1. Client starts at version 40
        SnapshotStore store = new SnapshotStore(v40);
        assertThat(store.getVersion()).isEqualTo(40L);
        assertThat(store.get().getOverride(KEY, SCOPE).value()).isEqualTo(50.0);

        // 2. Client receives version 43
        boolean appliedV43 = store.updateIfNewer(v43);
        assertThat(appliedV43).isTrue();
        assertThat(store.getVersion()).isEqualTo(43L);
        assertThat(store.get().getOverride(KEY, SCOPE).value()).isEqualTo(30.0);

        // 3. OUT-OF-ORDER EVENT: Client receives version 41 after version 43!
        System.out.println("[Proof f] Delivering out-of-order version 41 to client at version 43...");
        boolean appliedV41 = store.updateIfNewer(v41);

        // MUST BE DROPPED!
        assertThat(appliedV41).as("Version 41 must be dropped when client is at version 43").isFalse();
        assertThat(store.getVersion()).as("Clock must not rewind to 41").isEqualTo(43L);
        assertThat(store.get().getOverride(KEY, SCOPE).value())
                .as("Value from version 43 must remain active and not be overwritten by 41")
                .isEqualTo(30.0);

        // 4. DUPLICATE EVENT: Client receives duplicate version 43
        System.out.println("[Proof f] Delivering duplicate version 43 to client...");
        boolean appliedDuplicate = store.updateIfNewer(v43);
        assertThat(appliedDuplicate).as("Duplicate version 43 must be rejected").isFalse();
        assertThat(store.getVersion()).isEqualTo(43L);

        // 5. NORMAL PROGRESSION: Client receives version 44
        System.out.println("[Proof f] Delivering monotonic version 44 to client...");
        boolean appliedV44 = store.updateIfNewer(v44);
        assertThat(appliedV44).as("Version 44 must be accepted").isTrue();
        assertThat(store.getVersion()).isEqualTo(44L);
        assertThat(store.get().getOverride(KEY, SCOPE).value()).isEqualTo(20.0);

        System.out.println("[Proof f] Monotonicity test successfully verified forward-only behavior.");
    }

    private Snapshot createSnapshot(long version, Map<String, SettingDefinition> defs, double value) {
        OverrideValue ov = new OverrideValue(KEY, SCOPE, Level.CLIENT, value);
        return new Snapshot(version, defs, Map.of(KEY, Map.of(SCOPE, ov)));
    }
}
