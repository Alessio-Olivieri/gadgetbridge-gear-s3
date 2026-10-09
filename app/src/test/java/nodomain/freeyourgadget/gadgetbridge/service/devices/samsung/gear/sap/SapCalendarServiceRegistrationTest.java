package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SapCalendarServiceRegistrationTest {
    private static final int AGENT = 0x1234;
    private static final int COMPONENT = 0x5678;

    @Test
    public void selectiveCalendarQueryReturnsRegisteredProviderNotPlaceholder() {
        final SapCalendarServiceRegistration calendar = new SapCalendarServiceRegistration(AGENT, COMPONENT);
        final SapNotificationServiceRegistration notifications = new SapNotificationServiceRegistration(AGENT, 0x4567);
        final SapCapabilityExchange exchange = new SapCapabilityExchange(2, 32, 0,
                Arrays.asList(notifications.toCapabilityMetadata(), calendar.toCapabilityMetadata()));

        assertEquals(Arrays.asList(SapNotificationServiceRegistration.PROFILE, SapCalendarServiceRegistration.PROFILE),
                SapCapexCodec.parseQuery(exchange.createInitialQuery(0xffffffffL).getBody()).getProfiles());
        exchange.onRecord(new SapPostAuthRecord(new SapTransportHeader(0, 0,
                SapTransportHeader.CAPABILITY_SESSION_ID, 0),
                SapCapexCodec.encodeResponse(new SapCapexCodec.Response(0, Collections.emptyList()))));
        final SapCapabilityExchange.Reply reply = exchange.onRecord(new SapPostAuthRecord(
                new SapTransportHeader(0, 0, SapTransportHeader.CAPABILITY_SESSION_ID, 0),
                SapCapexCodec.encodeQuery(new SapCapexCodec.Query(SapCapexCodec.QUERY_TYPE_SELECTIVE, 0,
                        Collections.singletonList(SapCalendarServiceRegistration.PROFILE)))));
        final SapCapexCodec.Response wireResponse = SapCapexCodec.parseResponse(reply.getRecord().getBody());

        assertEquals(1, wireResponse.getAgents().size());
        final SapAgentDescriptor agent = wireResponse.getAgents().get(0);
        assertEquals(AGENT, agent.getUuid());
        assertEquals(1, agent.getServices().size());
        final SapServiceDescriptor service = agent.getServices().get(0);
        assertTrue(calendar.matchesLocalDescriptor(agent, service));
        assertEquals(COMPONENT, service.getComponentId());
        assertEquals(0x0200, service.getAspVersion());
        assertEquals(0x04, service.getRoleFlags());
        assertEquals(0, service.getTimeout());
        assertFalse(agent.getUuid() == 0xffff || service.getComponentId() == 0xff00);
        assertEquals(101, SapCalendarServiceRegistration.API_CHANNEL);
        assertEquals(5, SapCalendarServiceRegistration.QOS_TYPE);
        assertEquals(0, SapCalendarServiceRegistration.QOS_DATA_RATE);
        assertEquals(0, SapCalendarServiceRegistration.QOS_CLASS);
        assertEquals(0, SapCalendarServiceRegistration.PAYLOAD_TYPE);
    }

    @Test
    public void calendarComponentCannotReuseAnotherLocalServiceComponent() {
        final SharedPreferences prefs = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.getInt("samsung_gear_calendar_component_id", -1)).thenReturn(COMPONENT);
        when(prefs.getInt("samsung_gear_music_component_id", -1)).thenReturn(0x6789);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putInt(anyString(), anyInt())).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
        final SapHostManagerRegistration host = new SapHostManagerRegistration(AGENT, 0x2345);
        final SapBcmServiceRegistration bcm = new SapBcmServiceRegistration(AGENT, 0x3456);
        final SapNotificationServiceRegistration notifications = new SapNotificationServiceRegistration(AGENT, 0x4567);
        final SapMusicServiceRegistration music = SapMusicServiceRegistration.getOrCreate(prefs, host, bcm, notifications);
        final SapPedometerServiceRegistration pedometer = new SapPedometerServiceRegistration(AGENT, 0x789a);
        // Model weather already owning the previously persisted Calendar component.
        final SapCalendarServiceRegistration calendar = SapCalendarServiceRegistration.getOrCreate(
                prefs, host, bcm, notifications, music, pedometer, COMPONENT);
        assertFalse(calendar.getComponentId() == COMPONENT);
        assertFalse(calendar.getComponentId() == host.getComponentId());
        assertFalse(calendar.getComponentId() == bcm.getComponentId());
        assertFalse(calendar.getComponentId() == notifications.getComponentId());
        assertFalse(calendar.getComponentId() == music.getComponentId());
        assertFalse(calendar.getComponentId() == pedometer.getComponentId());
        verify(editor).putInt("samsung_gear_calendar_component_id", calendar.getComponentId());
    }

    @Test
    public void calendarSyncSettingDoesNotGateSapProviderDiscovery() {
        // Gadgetbridge's sync_calendar preference gates CalendarManager reads, not SAP registration.
        for (final boolean syncEnabled : new boolean[]{false, true}) {
            final SharedPreferences prefs = mock(SharedPreferences.class);
            when(prefs.getInt("samsung_gear_calendar_component_id", -1)).thenReturn(COMPONENT);
            when(prefs.getInt("samsung_gear_music_component_id", -1)).thenReturn(0x6789);
            when(prefs.getBoolean("sync_calendar", false)).thenReturn(syncEnabled);
            final SapHostManagerRegistration host = new SapHostManagerRegistration(AGENT, 0x2345);
            final SapBcmServiceRegistration bcm = new SapBcmServiceRegistration(AGENT, 0x3456);
            final SapNotificationServiceRegistration notifications = new SapNotificationServiceRegistration(AGENT, 0x4567);
            final SapMusicServiceRegistration music = SapMusicServiceRegistration.getOrCreate(
                    prefs, host, bcm, notifications);
            final SapPedometerServiceRegistration pedometer = new SapPedometerServiceRegistration(AGENT, 0x789a);

            final SapCalendarServiceRegistration calendar = SapCalendarServiceRegistration.getOrCreate(
                    prefs, host, bcm, notifications, music, pedometer);
            final SapAgentDescriptor agent = calendar.toCapabilityDescriptor();
            assertTrue(calendar.matchesLocalDescriptor(agent, agent.getServices().get(0)));
            assertEquals(COMPONENT, agent.getServices().get(0).getComponentId());
            verify(prefs, never()).getBoolean("sync_calendar", false);
            assertFalse(calendar.matchesLocalDescriptor(new SapAgentDescriptor(0xffff, "missing",
                    Collections.singletonList(new SapServiceDescriptor(0xff00,
                            SapCalendarServiceRegistration.PROFILE, 0x0100, 0x01, 0))),
                    new SapServiceDescriptor(0xff00, SapCalendarServiceRegistration.PROFILE, 0x0100, 0x01, 0)));
        }
    }
}
