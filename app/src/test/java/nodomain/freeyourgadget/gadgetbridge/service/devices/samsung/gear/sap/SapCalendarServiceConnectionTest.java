package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SapCalendarServiceConnectionTest {
    private static SapCalendarServiceRegistration registration() {
        final SapCalendarServiceRegistration local = new SapCalendarServiceRegistration(0x7123, 0x3456);
        final SapAgentDescriptor peer = new SapAgentDescriptor(0x6123, "calendar-consumer",
                Collections.singletonList(new SapServiceDescriptor(0x4567, "/system/calendar", 0x0100, 0x05, 0)));
        local.setRemoteConsumer(peer, peer.getServices().get(0));
        return local;
    }

    private static SapCalendarServiceConnection pendingAt142(final SapCalendarServiceRegistration local) {
        final SecureRandom deterministic = new SecureRandom() {
            @Override public int nextInt(final int bound) { return 141; }
        };
        final SapCalendarServiceConnection connection = new SapCalendarServiceConnection(local, deterministic);
        assertEquals(142, connection.createRequest(Collections.emptySet()).getSessions().get(0).getSessionId());
        return connection;
    }

    private static SapServiceConnectionRequest gearRequest(final int session) {
        return new SapServiceConnectionRequest(0x3456, 0x4567, "/system/calendar",
                Collections.singletonList(new SapServiceConnectionRequest.Session(session, 101,
                        new SapQosDescriptor(5, 0, 0), 0)));
    }

    @Test
    public void phoneInitiatedRequestUsesCapturedChannelAndDynamicPeer() {
        final SapCalendarServiceRegistration local = new SapCalendarServiceRegistration(0x7123, 0x3456);
        final SapAgentDescriptor peer = new SapAgentDescriptor(0x6123, "calendar-consumer",
                Collections.singletonList(new SapServiceDescriptor(0x4567, "/system/calendar", 0x0100, 0x05, 0)));
        local.setRemoteConsumer(peer, peer.getServices().get(0));
        final SapCalendarServiceConnection connection = new SapCalendarServiceConnection(local);
        final SapServiceConnectionRequest request = connection.createRequest(Set.of(1, 2, 3));
        assertEquals(0x4567, request.getAcceptorComponentId());
        assertEquals(0x3456, request.getInitiatorComponentId());
        assertEquals(101, request.getSessions().get(0).getChannelId());
        assertEquals(5, request.getSessions().get(0).getQos().getType());
        assertEquals(0, request.getSessions().get(0).getQos().getClassType());
        final int session = request.getSessions().get(0).getSessionId();
        assertTrue(session >= 4 && session <= 992);
        connection.handleResponse(new SapServiceConnectionResponse(0x4567, 0x3456, "/system/calendar", 0,
                Collections.singletonList(session)));
        assertTrue(connection.isApplicationSession(session));
    }

    @Test
    public void watchInitiatedRequestRejectsWrongQosAndCollision() {
        final SapCalendarServiceRegistration local = new SapCalendarServiceRegistration(0x7123, 0x3456);
        final SapAgentDescriptor peer = new SapAgentDescriptor(0x6123, "calendar-consumer",
                Collections.singletonList(new SapServiceDescriptor(0x4567, "/system/calendar", 0x0100, 0x05, 0)));
        local.setRemoteConsumer(peer, peer.getServices().get(0));
        final SapCalendarServiceConnection connection = new SapCalendarServiceConnection(local);
        final SapServiceConnectionRequest bad = new SapServiceConnectionRequest(0x3456, 0x4567, "/system/calendar",
                Collections.singletonList(new SapServiceConnectionRequest.Session(100, 101,
                        new SapQosDescriptor(4, 0, 0), 0)));
        assertEquals(SapServiceConnectionResponse.STATUS_REJECTED,
                connection.handleRequest(bad, Collections.emptySet()).getStatus());
        final SapServiceConnectionRequest valid = new SapServiceConnectionRequest(0x3456, 0x4567, "/system/calendar",
                Collections.singletonList(new SapServiceConnectionRequest.Session(100, 101,
                        new SapQosDescriptor(5, 0, 0), 0)));
        assertEquals(SapServiceConnectionResponse.STATUS_SESSION_COLLISION,
                connection.handleRequest(valid, Collections.singleton(100)).getStatus());
        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS,
                connection.handleRequest(valid, Collections.emptySet()).getStatus());
        assertTrue(connection.isApplicationSession(100));
    }

    @Test
    public void simultaneousGearRequestIsAcceptedAndLatePhoneConfirmationCannotEraseIt() {
        final SapCalendarServiceConnection connection = pendingAt142(registration());
        final SapServiceConnectionResponse accepted = connection.handleRequest(gearRequest(259), Collections.singleton(142));
        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS, accepted.getStatus());
        assertEquals(SapCalendarServiceConnection.Decision.ACCEPTED_SIMULTANEOUS, connection.getLastDecision());
        assertEquals(Collections.singletonList(142), accepted.getSessionIds()); // Samsung chooses min(142, 259).
        assertEquals(0x4567, accepted.getAcceptorSlot());
        assertEquals(0x3456, accepted.getInitiatorSlot());
        assertTrue(connection.isApplicationSession(142));
        final byte[] wire = SapServiceConnectionCodec.encode(accepted);
        assertEquals(SapServiceConnectionResponse.TYPE, wire[0] & 0xff);
        assertEquals(0x45, wire[1] & 0xff); // Gear request initiator becomes response acceptor slot.
        assertEquals(0x67, wire[2] & 0xff);
        assertEquals(0x34, wire[3] & 0xff);
        assertEquals(0x56, wire[4] & 0xff);
        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS,
                wire[5 + "/system/calendar".length() + 1] & 0xff);
        assertEquals(Collections.singletonList(142), SapServiceConnectionCodec.parseResponse(wire).getSessionIds());
        assertFalse(connection.handleResponse(new SapServiceConnectionResponse(0x4567, 0x3456,
                "/system/calendar", SapServiceConnectionResponse.STATUS_REJECTED, Collections.singletonList(142))));
        assertTrue(connection.isApplicationSession(142));
        assertEquals(-1, connection.getPendingPhoneSessionId());
    }

    @Test
    public void simultaneousLowerGearSessionWinsAndOldPhoneIdIsReleased() {
        final SapCalendarServiceConnection connection = pendingAt142(registration());
        final SapServiceConnectionResponse accepted = connection.handleRequest(gearRequest(100), Collections.singleton(142));
        assertEquals(Collections.singletonList(100), accepted.getSessionIds());
        assertTrue(connection.isApplicationSession(100));
        connection.expirePendingPhoneRequest();
        assertEquals(-1, connection.getPendingPhoneSessionId());
        assertTrue(connection.isApplicationSession(100));
        assertFalse(connection.handleResponse(new SapServiceConnectionResponse(0x4567, 0x3456,
                "/system/calendar", SapServiceConnectionResponse.STATUS_SUCCESS, Collections.singletonList(142))));
        assertTrue(connection.isApplicationSession(100));
        connection.clear();
        assertEquals(-1, connection.getSessionId());
        assertEquals(-1, connection.getPendingPhoneSessionId());
        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS,
                connection.handleRequest(gearRequest(200), Collections.emptySet()).getStatus());
        assertTrue(connection.isApplicationSession(200));
    }

    @Test
    public void pendingCalendarRequestOnlyRejectsRealMismatchOrOtherServiceCollision() {
        final SapCalendarServiceConnection connection = pendingAt142(registration());
        assertEquals(SapServiceConnectionResponse.STATUS_SESSION_COLLISION,
                connection.handleRequest(gearRequest(100), Set.of(100, 142)).getStatus());
        assertEquals(SapCalendarServiceConnection.Decision.SESSION_COLLISION, connection.getLastDecision());
        assertEquals(SapCalendarServiceConnection.State.PENDING, connection.getState());
        final SapServiceConnectionRequest wrongComponent = new SapServiceConnectionRequest(0x3457, 0x4567,
                "/system/calendar", gearRequest(200).getSessions());
        assertEquals(SapServiceConnectionResponse.STATUS_REJECTED,
                connection.handleRequest(wrongComponent, Collections.singleton(142)).getStatus());
        assertEquals(SapCalendarServiceConnection.Decision.REJECTED_DESCRIPTOR, connection.getLastDecision());
        assertEquals(SapCalendarServiceConnection.State.PENDING, connection.getState());
    }

    @Test
    public void rejectedPhoneProposalCanBeFollowedByGearInitiatedSuccess() {
        final SapCalendarServiceConnection connection = pendingAt142(registration());
        try {
            connection.handleResponse(new SapServiceConnectionResponse(0x4567, 0x3456,
                    "/system/calendar", SapServiceConnectionResponse.STATUS_REJECTED,
                    Collections.singletonList(142)));
            fail("Rejected phone proposal must fail and release its session");
        } catch (final IllegalArgumentException expected) {
            assertEquals(SapCalendarServiceConnection.State.IDLE, connection.getState());
        }
        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS,
                connection.handleRequest(gearRequest(259), Collections.emptySet()).getStatus());
        assertTrue(connection.isApplicationSession(259));
    }
}
