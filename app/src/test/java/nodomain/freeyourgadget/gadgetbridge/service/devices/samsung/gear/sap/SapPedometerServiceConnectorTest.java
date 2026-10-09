package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SapPedometerServiceConnectorTest {
    @Test
    public void createsApi999RequestAndAcceptsDynamicSession() {
        final SapPedometerServiceRegistration registration = configured();
        final SapPedometerServiceConnector connector = new SapPedometerServiceConnector(registration, new FixedRandom(312));
        final SapServiceConnectionRequest request = connector.createRequest(new HashSet<>(Collections.singletonList(314)));
        final SapServiceConnectionRequest.Session session = request.getSessions().get(0);
        assertEquals(0x5678, request.getAcceptorComponentId());
        assertEquals(0x4567, request.getInitiatorComponentId());
        assertEquals(313, session.getSessionId());
        assertEquals(999, session.getChannelId());
        assertEquals(4, session.getQos().getType());
        assertEquals(0, session.getQos().getDataRate());
        assertEquals(0, session.getQos().getClassType());
        assertEquals(0, session.getPayloadType());
        connector.handleResponse(new SapServiceConnectionResponse(0x5678, 0x4567,
                SapPedometerServiceRegistration.PROFILE, 0, Collections.singletonList(313)));
        assertEquals(SapPedometerServiceConnector.State.ESTABLISHED, connector.getState());
        assertTrue(connector.isApplicationSession(313));
        assertTrue(connector.matchesPeerTermination(new SapServiceConnectionTermination(0x4567, 0x5678,
                SapPedometerServiceRegistration.PROFILE)));
        assertFalse(connector.matchesPeerTermination(new SapServiceConnectionTermination(0x4567, 0x5678,
                "/system/weather2")));
    }

    @Test
    public void terminatesOnlyTheEstablishedDynamicPedometerService() {
        final SapPedometerServiceConnector connector = new SapPedometerServiceConnector(configured(), new FixedRandom(312));
        connector.createRequest(Collections.emptySet());
        connector.handleResponse(new SapServiceConnectionResponse(0x5678, 0x4567,
                SapPedometerServiceRegistration.PROFILE, 0, Collections.singletonList(313)));

        final SapServiceConnectionTermination termination = connector.createTermination();
        assertEquals(0x5678, termination.getAcceptorComponentId());
        assertEquals(0x4567, termination.getInitiatorComponentId());
        assertEquals(SapPedometerServiceConnector.State.TERMINATING, connector.getState());

        connector.handleTerminationResponse(new SapServiceConnectionTerminationResponse(0x4567, 0x5678,
                SapPedometerServiceRegistration.PROFILE, SapServiceConnectionTerminationResponse.STATUS_SUCCESS));
        assertEquals(SapPedometerServiceConnector.State.IDLE, connector.getState());
        assertEquals(-1, connector.getApplicationSessionId());
    }

    private static SapPedometerServiceRegistration configured() {
        final SapPedometerServiceRegistration registration = new SapPedometerServiceRegistration(0x1234, 0x4567);
        final SapServiceDescriptor remote = new SapServiceDescriptor(0x5678, SapPedometerServiceRegistration.PROFILE, 0x0100, 0x04, 0);
        registration.setRemoteProvider(new SapAgentDescriptor(0x2345, "Gear", Collections.singletonList(remote)), remote);
        return registration;
    }

    private static final class FixedRandom extends SecureRandom {
        private final int value;
        private FixedRandom(final int value) { this.value = value; }
        @Override public int nextInt(final int bound) { return value % bound; }
    }
}
