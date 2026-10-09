package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SapWeatherServiceConnectorTest {
    private static final int LOCAL = 0x3547;
    private static final int REMOTE = 0xde3a;

    @Test
    public void createsSourceDeclaredPhoneRequestAndValidatesResponseBeforeUse() {
        final SapWeatherServiceConnector connector = new SapWeatherServiceConnector(configured(), new FixedRandom(224));
        final SapServiceConnectionRequest request = connector.createRequest(Collections.emptySet());
        final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
        assertEquals(REMOTE, request.getAcceptorComponentId());
        assertEquals(LOCAL, request.getInitiatorComponentId());
        assertEquals(225, session.getSessionId());
        assertEquals(109, session.getChannelId());
        assertEquals(5, session.getQos().getType());
        assertEquals(0, session.getQos().getDataRate());
        assertEquals(0, session.getQos().getClassType());
        assertEquals(0, session.getPayloadType());
        assertEquals(request.getProfile(), ((SapServiceConnectionRequest) SapServiceConnectionCodec.parse(
                SapServiceConnectionCodec.encode(request))).getProfile());
        assertThrows(IllegalStateException.class, () -> connector.createRequest(Collections.emptySet()));
        assertThrows(IllegalArgumentException.class, () -> connector.handleResponse(new SapServiceConnectionResponse(
                REMOTE, LOCAL, SapWeatherServiceRegistration.PROFILE, 0, Collections.singletonList(226))));
        assertEquals(SapWeatherServiceConnector.State.PENDING, connector.getState());
        final SapServiceConnectionResponse accepted = new SapServiceConnectionResponse(REMOTE, LOCAL,
                SapWeatherServiceRegistration.PROFILE, 0, Collections.singletonList(225));
        connector.handleResponse((SapServiceConnectionResponse) SapServiceConnectionCodec.parse(
                SapServiceConnectionCodec.encode(accepted)));
        assertTrue(connector.isApplicationSession(225));
    }

    @Test
    public void delayedSuccessAfterDiagnosticTimeoutStillEstablishesTheSameSession() {
        final SapWeatherServiceConnector connector = new SapWeatherServiceConnector(configured(), new FixedRandom(308));
        connector.createRequest(Collections.emptySet());
        assertEquals(309, connector.getSessionId());
        assertFalse(connector.markOverdue(310));
        assertEquals(SapWeatherServiceConnector.State.PENDING, connector.getState());
        assertTrue(connector.markOverdue(309));
        assertEquals(SapWeatherServiceConnector.State.OVERDUE, connector.getState());
        assertTrue(connector.isReservedSession(309));
        assertThrows(IllegalStateException.class, () -> connector.createRequest(Collections.emptySet()));
        assertThrows(IllegalArgumentException.class, () -> connector.handleResponse(new SapServiceConnectionResponse(
                REMOTE, LOCAL, SapWeatherServiceRegistration.PROFILE, 0, Collections.singletonList(310))));
        assertEquals(SapWeatherServiceConnector.State.OVERDUE, connector.getState());
        connector.handleResponse(new SapServiceConnectionResponse(REMOTE, LOCAL,
                SapWeatherServiceRegistration.PROFILE, 0, Collections.singletonList(309)));
        assertTrue(connector.isApplicationSession(309));
    }

    @Test
    public void rejectionReturnsToIdleWithoutExposingApplicationSession() {
        final SapWeatherServiceConnector connector = new SapWeatherServiceConnector(configured(), new FixedRandom(224));
        connector.createRequest(Collections.emptySet());
        assertThrows(IllegalStateException.class, () -> connector.handleResponse(new SapServiceConnectionResponse(
                REMOTE, LOCAL, SapWeatherServiceRegistration.PROFILE, SapServiceConnectionResponse.STATUS_REJECTED,
                Collections.singletonList(225))));
        assertEquals(SapWeatherServiceConnector.State.IDLE, connector.getState());
        assertEquals(-1, connector.getSessionId());
    }

    @Test
    public void terminatesOnlyValidatedPhoneWeatherSession() {
        final SapWeatherServiceConnector connector = new SapWeatherServiceConnector(configured(), new FixedRandom(224));
        connector.createRequest(Collections.emptySet());
        connector.handleResponse(new SapServiceConnectionResponse(REMOTE, LOCAL,
                SapWeatherServiceRegistration.PROFILE, 0, Collections.singletonList(225)));
        final SapServiceConnectionTermination close = connector.createTermination();
        assertEquals(REMOTE, close.getAcceptorComponentId());
        assertEquals(LOCAL, close.getInitiatorComponentId());
        assertEquals(SapWeatherServiceConnector.State.TERMINATING, connector.getState());
        assertTrue(connector.isApplicationSession(225)); // Delayed QoS-5 transport ACK can arrive before 0x04.
        assertThrows(IllegalArgumentException.class, () -> connector.handleTerminationResponse(
                new SapServiceConnectionTerminationResponse(REMOTE, LOCAL,
                        SapWeatherServiceRegistration.PROFILE, 0)));
        connector.handleTerminationResponse(new SapServiceConnectionTerminationResponse(LOCAL, REMOTE,
                SapWeatherServiceRegistration.PROFILE, 0));
        assertEquals(SapWeatherServiceConnector.State.IDLE, connector.getState());
    }

    private static SapWeatherServiceRegistration configured() {
        final SapWeatherServiceRegistration local = new SapWeatherServiceRegistration(0x1234, LOCAL);
        final SapAgentDescriptor gear = new SapAgentDescriptor(0x8235, "gear-weather",
                Collections.singletonList(new SapServiceDescriptor(REMOTE,
                        SapWeatherServiceRegistration.PROFILE, 0x0100, 0x05, 0)));
        local.setRemoteConsumer(gear, gear.getServices().get(0));
        return local;
    }

    private static final class FixedRandom extends SecureRandom {
        private final int value;
        private FixedRandom(final int value) { this.value = value; }
        @Override public int nextInt(final int bound) { return value % bound; }
    }
}
