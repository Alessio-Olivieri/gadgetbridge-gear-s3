package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class SapWeatherServiceAcceptorTest {
    @Test
    public void acceptsCapturedWeatherShapeWithDynamicIdsAndSession() {
        final SapWeatherServiceRegistration local = new SapWeatherServiceRegistration(0x1234, 0x3547);
        final SapAgentDescriptor gear = new SapAgentDescriptor(0x8235, "gear-weather",
                Collections.singletonList(new SapServiceDescriptor(0xde3a,
                        SapWeatherServiceRegistration.PROFILE, 0x0100, 0x05, 0)));
        local.setRemoteConsumer(gear, gear.getServices().get(0));

        final SapServiceConnectionRequest request = new SapServiceConnectionRequest(local.getComponentId(), 0xde3a,
                SapWeatherServiceRegistration.PROFILE, Collections.singletonList(
                new SapServiceConnectionRequest.Session(0x02c1, SapWeatherServiceRegistration.API_CHANNEL,
                        new SapQosDescriptor(4, 0, 0), 0)));

        final SapServiceConnectionResponse response = new SapWeatherServiceAcceptor(local).handle(request,
                Collections.<Integer>emptySet());

        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS, response.getStatus());
        assertEquals(0x02c1, response.getSessionIds().get(0).intValue());
        assertEquals(0xde3a, response.getAcceptorSlot());
        assertEquals(local.getComponentId(), response.getInitiatorSlot());
    }

    @Test
    public void rejectsWrongQosWithoutChangingSessionState() {
        final SapWeatherServiceRegistration local = new SapWeatherServiceRegistration(0x1234, 0x3547);
        final SapAgentDescriptor gear = new SapAgentDescriptor(0x8235, "gear-weather",
                Collections.singletonList(new SapServiceDescriptor(0xde3a,
                        SapWeatherServiceRegistration.PROFILE, 0x0100, 0x05, 0)));
        local.setRemoteConsumer(gear, gear.getServices().get(0));
        final SapServiceConnectionRequest request = new SapServiceConnectionRequest(local.getComponentId(), 0xde3a,
                SapWeatherServiceRegistration.PROFILE, Collections.singletonList(
                new SapServiceConnectionRequest.Session(1, SapWeatherServiceRegistration.API_CHANNEL,
                        new SapQosDescriptor(SapWeatherServiceRegistration.DECLARED_QOS_TYPE, 0, 0), 0)));

        final SapServiceConnectionResponse response = new SapWeatherServiceAcceptor(local).handle(request,
                Collections.<Integer>emptySet());

        assertEquals(SapServiceConnectionResponse.STATUS_REJECTED, response.getStatus());
    }

    @Test
    public void terminationClearsOnlyTheWeatherSession() {
        final SapWeatherServiceRegistration local = new SapWeatherServiceRegistration(0x1234, 0x3547);
        final SapAgentDescriptor gear = new SapAgentDescriptor(0x8235, "gear-weather",
                Collections.singletonList(new SapServiceDescriptor(0xde3a,
                        SapWeatherServiceRegistration.PROFILE, 0x0100, 0x05, 0)));
        local.setRemoteConsumer(gear, gear.getServices().get(0));
        final SapWeatherServiceAcceptor acceptor = new SapWeatherServiceAcceptor(local);
        final SapServiceConnectionRequest request = new SapServiceConnectionRequest(local.getComponentId(), 0xde3a,
                SapWeatherServiceRegistration.PROFILE, Collections.singletonList(
                new SapServiceConnectionRequest.Session(971, SapWeatherServiceRegistration.API_CHANNEL,
                        new SapQosDescriptor(4, 0, 0), 0)));

        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS,
                acceptor.handle(request, Collections.<Integer>emptySet()).getStatus());
        assertTrue(acceptor.matchesPeerTermination(new SapServiceConnectionTermination(
                local.getComponentId(), 0xde3a, SapWeatherServiceRegistration.PROFILE)));
        acceptor.clear();

        assertEquals(-1, acceptor.getApplicationSessionId());
        assertFalse(acceptor.isApplicationSession(971));
    }

    @Test
    public void matchingEnvelopeAckCanCloseGearInitiatedServiceWithDynamicComponents() {
        final SapWeatherServiceRegistration local = new SapWeatherServiceRegistration(0x1234, 0x3547);
        final SapAgentDescriptor gear = new SapAgentDescriptor(0x8235, "gear-weather",
                Collections.singletonList(new SapServiceDescriptor(0xde3a,
                        SapWeatherServiceRegistration.PROFILE, 0x0100, 0x05, 0)));
        local.setRemoteConsumer(gear, gear.getServices().get(0));
        final SapWeatherServiceAcceptor acceptor = new SapWeatherServiceAcceptor(local);
        final SapServiceConnectionRequest request = new SapServiceConnectionRequest(local.getComponentId(), 0xde3a,
                SapWeatherServiceRegistration.PROFILE, Collections.singletonList(
                new SapServiceConnectionRequest.Session(225, SapWeatherServiceRegistration.API_CHANNEL,
                        new SapQosDescriptor(4, 0, 0), 0)));
        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS,
                acceptor.handle(request, Collections.<Integer>emptySet()).getStatus());

        final SapServiceConnectionTermination close = acceptor.createTermination();
        assertEquals(0xde3a, close.getAcceptorComponentId());
        assertEquals(local.getComponentId(), close.getInitiatorComponentId());
        assertThrows(IllegalStateException.class, acceptor::createTermination);
        assertThrows(IllegalArgumentException.class, () -> acceptor.handleTerminationResponse(
                new SapServiceConnectionTerminationResponse(0xde3a, local.getComponentId(),
                        SapWeatherServiceRegistration.PROFILE, 0)));
        assertEquals(225, acceptor.getApplicationSessionId());
        acceptor.handleTerminationResponse(new SapServiceConnectionTerminationResponse(
                local.getComponentId(), 0xde3a, SapWeatherServiceRegistration.PROFILE, 0));
        assertEquals(-1, acceptor.getApplicationSessionId());
    }
}
