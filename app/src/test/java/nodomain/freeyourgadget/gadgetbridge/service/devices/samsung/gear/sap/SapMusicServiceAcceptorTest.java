package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SapMusicServiceAcceptorTest {
    @Test
    public void physicalGearRequestReturnsModernTypeTwoResponse() {
        final SharedPreferences prefs = mock(SharedPreferences.class);
        when(prefs.getInt("samsung_gear_music_component_id", -1)).thenReturn(0x3547);
        final SapHostManagerRegistration host = new SapHostManagerRegistration(0x1234, 0x3456);
        final SapBcmServiceRegistration bcm = new SapBcmServiceRegistration(0x1234, 0x4567);
        final SapNotificationServiceRegistration notifications = new SapNotificationServiceRegistration(0x1234, 0x5678);
        final SapMusicServiceRegistration music = SapMusicServiceRegistration.getOrCreate(prefs, host, bcm, notifications);
        final SapAgentDescriptor gear = new SapAgentDescriptor(0x8235, "gear-music",
                Collections.singletonList(new SapServiceDescriptor(0xde3a, "/system/music", 0x0100, 0x05, 0)));
        music.setRemoteConsumer(gear, gear.getServices().get(0));

        final byte[] physical = new byte[]{0x01, 0x35, 0x47, (byte) 0xde, 0x3a,
                '/', 's', 'y', 's', 't', 'e', 'm', '/', 'm', 'u', 's', 'i', 'c', ';',
                0, 1, 0x03, (byte) 0xd9, 0, 0x64, 0x04, 0, 0x02, 0};
        final SapServiceConnectionRequest request = SapServiceConnectionCodec.parseRequest(physical);
        final SapServiceConnectionResponse response = new SapMusicServiceAcceptor(music).handle(request, Collections.<Integer>emptySet());
        final byte[] encoded = SapServiceConnectionCodec.encode(response);
        assertArrayEquals(new byte[]{0x02, (byte) 0xde, 0x3a, 0x35, 0x47,
                '/', 's', 'y', 's', 't', 'e', 'm', '/', 'm', 'u', 's', 'i', 'c', ';',
                0, 0, 1, 0x03, (byte) 0xd9}, encoded);
        final SapServiceConnectionResponse decoded = SapServiceConnectionCodec.parseResponse(encoded);

        assertEquals(0x02, SapServiceConnectionResponse.TYPE);
        assertEquals(0xde3a, decoded.getAcceptorSlot());
        assertEquals(0x3547, decoded.getInitiatorSlot());
        assertEquals("/system/music", decoded.getProfile());
        assertEquals(SapServiceConnectionResponse.STATUS_SUCCESS, decoded.getStatus());
        assertEquals(Collections.singletonList(0x03d9), decoded.getSessionIds());
    }
}
