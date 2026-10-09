package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SapBcmServiceRegistrationTest {
    @Test
    public void reusesHostAgentAndPersistedDistinctComponent() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getInt(SapBcmServiceRegistration.COMPONENT_KEY, -1)).thenReturn(0x4567);

        final SapHostManagerRegistration host = new SapHostManagerRegistration(0x1234, 0x3456);
        final SapBcmServiceRegistration registration = SapBcmServiceRegistration.getOrCreate(preferences, host);

        assertEquals(0x1234, registration.getAgentUuid());
        assertEquals(0x4567, registration.getComponentId());
        assertFalse(registration.getComponentId() == host.getComponentId());
    }

    @Test
    public void replacesMissingComponentWithAValidDistinctValue() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.getInt(SapBcmServiceRegistration.COMPONENT_KEY, -1)).thenReturn(-1);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putInt(eq(SapBcmServiceRegistration.COMPONENT_KEY), anyInt())).thenReturn(editor);
        when(editor.commit()).thenReturn(true);

        final SapBcmServiceRegistration registration = SapBcmServiceRegistration.getOrCreate(preferences,
                new SapHostManagerRegistration(0x1234, 0x3456));

        assertEquals(0x1234, registration.getAgentUuid());
        assertTrue(SapHostManagerRegistration.validComponent(registration.getComponentId()));
        assertFalse(registration.getComponentId() == 0x3456);
        verify(editor).commit();
    }

    @Test
    public void advertisesTruthfulConsumerDescriptorAndInitialProfiles() {
        final SapHostManagerRegistration host = new SapHostManagerRegistration(0x1234, 0x3456);
        final SapBcmServiceRegistration bcm = new SapBcmServiceRegistration(host.getAgentUuid(), 0x4567);
        final SapAgentDescriptor descriptor = bcm.toCapabilityDescriptor();
        final SapServiceDescriptor service = descriptor.getServices().get(0);

        assertEquals(host.getAgentUuid(), descriptor.getUuid());
        assertEquals(0x4567, service.getComponentId());
        assertEquals("/system/bcmservice", service.getProfileId());
        assertEquals(0x0200, service.getAspVersion());
        assertEquals(0x05, service.getRoleFlags());
        assertEquals(0, service.getTimeout());

        final SapCapabilityExchange exchange = new SapCapabilityExchange(2, 31, 0,
                Arrays.asList(host.toCapabilityMetadata(), bcm.toCapabilityMetadata()));
        final SapPostAuthRecord query = exchange.createInitialQuery(0xffffffffL);
        final SapCapexCodec.Query parsed = SapCapexCodec.parseQuery(query.getBody());
        assertEquals(Arrays.asList("/system/bcmservice", "/system/hostmanager"), parsed.getProfiles());
        assertEquals(SapTransportHeader.CAPABILITY_SESSION_ID, query.getHeader().getSessionId());
    }

    @Test
    public void checksumChangesWhenBcmserviceBecomesPersistentAndNoServiceControlRecordIsCreated() {
        final SapHostManagerRegistration host = new SapHostManagerRegistration(0x1234, 0x3456);
        final SapBcmServiceRegistration bcm = new SapBcmServiceRegistration(host.getAgentUuid(), 0x4567);
        final SapCapabilityExchange exchange = new SapCapabilityExchange(2, 31, 0,
                Arrays.asList(host.toCapabilityMetadata(), bcm.toCapabilityMetadata()));
        exchange.onRecord(responseRecord(Collections.<SapAgentDescriptor>emptyList()));

        final SapCapabilityExchange.Reply hostReply = exchange.onRecord(queryRecord("/system/hostmanager"));
        final SapCapabilityExchange.Reply bcmReply = exchange.onRecord(queryRecord("/system/bcmservice"));

        assertTrue(hostReply.getResponse().getChecksum() != bcmReply.getResponse().getChecksum());
        assertEquals(SapTransportHeader.CAPABILITY_SESSION_ID, hostReply.getRecord().getHeader().getSessionId());
        assertEquals(SapTransportHeader.CAPABILITY_SESSION_ID, bcmReply.getRecord().getHeader().getSessionId());
        assertFalse(hostReply.getRecord().getHeader().getSessionId() == SapTransportHeader.SERVICE_CONTROL_SESSION_ID);
        assertFalse(bcmReply.getRecord().getHeader().getSessionId() == SapTransportHeader.SERVICE_CONTROL_SESSION_ID);
    }

    @Test
    public void retainsDynamicallyParsedGearProvider() {
        final SapAgentDescriptor dynamicAgent = new SapAgentDescriptor(0x2345, "dynamic-gear-agent",
                Collections.singletonList(new SapServiceDescriptor(0x5678, SapBcmServiceRegistration.PROFILE,
                        0x0200, 0x04, 0)));
        final byte[] body = SapCapexCodec.encodeResponse(new SapCapexCodec.Response(0, Collections.singletonList(dynamicAgent)));
        final List<SapAgentDescriptor> parsedAgents = SapCapexCodec.parseResponse(body).getAgents();
        final SapBcmServiceRegistration bcm = new SapBcmServiceRegistration(0x1234, 0x4567);

        assertNull(bcm.getRemoteProvider());
        bcm.setRemoteProvider(parsedAgents.get(0), parsedAgents.get(0).getServices().get(0));

        final SapBcmServiceRegistration.RemoteDescriptor remote = bcm.getRemoteProvider();
        assertEquals(0x2345, remote.getAgentUuid());
        assertEquals(0x5678, remote.getComponentId());
        assertEquals(0x0200, remote.getAspVersion());
        assertEquals(0x04, remote.getRoleFlags());
        assertEquals(0, remote.getTimeout());
    }

    private static SapPostAuthRecord responseRecord(final List<SapAgentDescriptor> agents) {
        return new SapPostAuthRecord(new SapTransportHeader(0, 0, SapTransportHeader.CAPABILITY_SESSION_ID, 0),
                SapCapexCodec.encodeResponse(new SapCapexCodec.Response(0, agents)));
    }

    private static SapPostAuthRecord queryRecord(final String profile) {
        return new SapPostAuthRecord(new SapTransportHeader(0, 0, SapTransportHeader.CAPABILITY_SESSION_ID, 0),
                SapCapexCodec.encodeQuery(new SapCapexCodec.Query(SapCapexCodec.QUERY_TYPE_SELECTIVE, 0,
                        Collections.singletonList(profile))));
    }
}
