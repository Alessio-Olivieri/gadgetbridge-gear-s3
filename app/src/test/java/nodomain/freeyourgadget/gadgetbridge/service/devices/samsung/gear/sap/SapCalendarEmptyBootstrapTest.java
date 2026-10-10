package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SapCalendarEmptyBootstrapTest {
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));
    private static final long NOW = 1_000_000L;

    @Test
    public void freshZeroRowZeroSequenceProofIsAccepted() {
        SapCalendarBootstrap.requireEmptyWatchProof(HASH, 0, 0, NOW - 1000, NOW);
    }

    @Test
    public void nonemptyWatchNonzeroSequenceMissingOrExpiredProofIsRefused() {
        refusal("watch-not-empty", HASH, 1, 0, NOW);
        refusal("watch-sequence-mismatch", HASH, 0, 1, NOW);
        refusal("watch-proof-missing", null, 0, 0, NOW);
        refusal("watch-proof-missing", "invalid", 0, 0, NOW);
        refusal("watch-proof-expired", HASH, 0, 0, NOW - 300_001);
        refusal("watch-proof-expired", HASH, 0, 0, NOW + 1);
    }

    @Test
    public void zeroSapTxInvariantStillRequired() {
        SapCalendarBootstrap.requireNoSapTx(3, 3);
        assertEquals("sap-tx-during-bootstrap", assertThrows(SapCalendarBootstrap.Refusal.class,
                () -> SapCalendarBootstrap.requireNoSapTx(3, 4)).reason);
    }

    private void refusal(final String reason, final String hash, final int count,
                         final long sequence, final long stamp) {
        assertEquals(reason, assertThrows(SapCalendarBootstrap.Refusal.class,
                () -> SapCalendarBootstrap.requireEmptyWatchProof(hash, count, sequence, stamp, NOW)).reason);
    }
}
