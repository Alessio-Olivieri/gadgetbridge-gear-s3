package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SapPedometerServiceRegistrationTest {
    @Test
    public void advertisesOnlyTheReceiveProbeContract() {
        final SapPedometerServiceRegistration registration = new SapPedometerServiceRegistration(0x1234, 0x4567);
        final SapServiceDescriptor service = registration.toCapabilityDescriptor().getServices().get(0);
        assertEquals(SapPedometerServiceRegistration.PROFILE, service.getProfileId());
        assertEquals(0x0200, service.getAspVersion());
        assertEquals(0x05, service.getRoleFlags());
        assertEquals(0, service.getTimeout());
        assertEquals(0x1234, registration.toCapabilityDescriptor().getUuid());
        assertEquals(999, SapPedometerServiceRegistration.API_CHANNEL);
        assertEquals(4, SapPedometerServiceRegistration.QOS_TYPE);
        assertEquals(0, SapPedometerServiceRegistration.QOS_DATA_RATE);
        assertEquals(0, SapPedometerServiceRegistration.QOS_CLASS);
        assertEquals(0, SapPedometerServiceRegistration.PAYLOAD_TYPE);
    }

    @Test
    public void learnsGearProviderComponentFromDescriptor() {
        final SapPedometerServiceRegistration registration = new SapPedometerServiceRegistration(0x1234, 0x4567);
        final SapAgentDescriptor gear = new SapAgentDescriptor(0x2345, "Gear", Collections.singletonList(
                new SapServiceDescriptor(0x5678, SapPedometerServiceRegistration.PROFILE, 0x0100, 0x04, 0)));
        registration.setRemoteProvider(gear, gear.getServices().get(0));
        assertEquals(0x2345, registration.getRemoteProvider().getAgentUuid());
        assertEquals(0x5678, registration.getRemoteProvider().getComponentId());
        assertNotEquals(0x4567, registration.getRemoteProvider().getComponentId());
    }
}
