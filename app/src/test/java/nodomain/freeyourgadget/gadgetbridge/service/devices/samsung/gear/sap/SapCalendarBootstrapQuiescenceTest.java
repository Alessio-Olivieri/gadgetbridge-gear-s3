package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/** INSPECT and BOOTSTRAP call the same predicate while GearS3DeviceSupport holds calendarLock. */
public class SapCalendarBootstrapQuiescenceTest {
    private static SapCalendarBootstrap.Quiescence state(
            final SapCalendarServiceConnection.State connection,
            final boolean update, final boolean fullSync,
            final boolean ingress, final boolean reliable,
            final boolean refresh, final boolean mirrorUnknown,
            final boolean sequenceReady) {
        return new SapCalendarBootstrap.Quiescence(true, connection,
                connection == SapCalendarServiceConnection.State.ESTABLISHED, true,
                mirrorUnknown, update, fullSync, ingress, reliable, refresh, sequenceReady);
    }

    private static SapCalendarBootstrap.Quiescence establishedIdle() {
        return state(SapCalendarServiceConnection.State.ESTABLISHED,
                false, false, false, false, false, true, true);
    }

    @Test
    public void establishedIdleApi101AllowsInspectPredicate() {
        assertNull(establishedIdle().refusalReason());
    }

    @Test
    public void establishedIdleApi101AllowsBootstrapPredicate() {
        establishedIdle().requireIdle();
    }

    @Test
    public void idleWithoutSessionAlsoRemainsEligible() {
        assertNull(state(SapCalendarServiceConnection.State.IDLE,
                false, false, false, false, false, true, true).refusalReason());
    }

    @Test
    public void pendingCreateAndInconsistentRouteRefuse() {
        assertEquals("active-service-create", state(SapCalendarServiceConnection.State.PENDING,
                false, false, false, false, false, true, true).refusalReason());
        assertEquals("session-state-inconsistent", new SapCalendarBootstrap.Quiescence(true,
                SapCalendarServiceConnection.State.ESTABLISHED, false, false,
                true, false, false, false, false, false, true).refusalReason());
    }

    @Test
    public void updateAndFullSyncRefuseBeforeAnyDiagnosticQuery() {
        assertEquals("update-pending", state(SapCalendarServiceConnection.State.ESTABLISHED,
                true, false, false, false, false, true, true).refusalReason());
        assertEquals("full-sync-active", state(SapCalendarServiceConnection.State.ESTABLISHED,
                false, true, false, false, false, true, true).refusalReason());
    }

    @Test
    public void queuedApplicationAndReliableRxRefuse() {
        assertEquals("application-rx-pending", state(SapCalendarServiceConnection.State.ESTABLISHED,
                false, false, true, false, false, true, true).refusalReason());
        assertEquals("reliable-rx-pending", state(SapCalendarServiceConnection.State.ESTABLISHED,
                false, false, false, true, false, true, true).refusalReason());
        assertEquals("refresh-running", state(SapCalendarServiceConnection.State.ESTABLISHED,
                false, false, false, false, true, true, true).refusalReason());
    }

    @Test
    public void mirrorAndSequenceGuardsRemainStrict() {
        assertEquals("mirror-not-unknown", state(SapCalendarServiceConnection.State.ESTABLISHED,
                false, false, false, false, false, false, true).refusalReason());
        assertEquals("sequence-mismatch", state(SapCalendarServiceConnection.State.ESTABLISHED,
                false, false, false, false, false, true, false).refusalReason());
    }

    @Test
    public void explicitRefusalLogContainsOnlyFixedReason() {
        final SapCalendarBootstrap.Refusal refusal = assertThrows(SapCalendarBootstrap.Refusal.class,
                () -> state(SapCalendarServiceConnection.State.ESTABLISHED,
                        false, false, false, true, false, true, true).requireIdle());
        assertEquals("REFUSED reliable-rx-pending", refusal.diagnosticLine());
    }

    @Test
    public void successfulBootstrapHasNoCalendarApplicationTx() {
        SapCalendarBootstrap.requireNoSapTx(7, 7);
        final SapCalendarBootstrap.Refusal unexpected = assertThrows(SapCalendarBootstrap.Refusal.class,
                () -> SapCalendarBootstrap.requireNoSapTx(7, 8));
        assertEquals("sap-tx-during-bootstrap", unexpected.reason);
    }
}
