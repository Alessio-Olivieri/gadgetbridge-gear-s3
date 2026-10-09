package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import android.content.SharedPreferences;

import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SapClientIdentityTest {
    @Test
    public void generatesSamsungCompatible36BytePeerIdWithoutBluetoothMac() {
        final String identity = SapClientIdentity.generate(new FixedRandom());
        assertEquals("SAP_B000102030405060708090A0B0C0D0E0", identity);
        assertEquals(36, identity.length());
        assertTrue(SapClientIdentity.isValid(identity));
    }

    @Test
    public void rejectsAddressesAndMalformedPeerIds() {
        assertFalse(SapClientIdentity.isValid("02:00:00:00:00:01"));
        assertFalse(SapClientIdentity.isValid("SAP_B0123"));
        assertFalse(SapClientIdentity.isValid("SAP_Z000102030405060708090A0B0C0D0E"));
    }

    @Test
    public void reusesThePersistedIdentity() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final String identity = "SAP_B000102030405060708090A0B0C0D0E0";
        when(preferences.getString(SapClientIdentity.PREFERENCE_KEY, null)).thenReturn(identity);

        assertEquals(identity, SapClientIdentity.getOrCreate(preferences));
        verify(preferences).getString(SapClientIdentity.PREFERENCE_KEY, null);
    }

    @Test
    public void persistsANewIdentityWhenNoValidValueExists() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.getString(SapClientIdentity.PREFERENCE_KEY, null)).thenReturn(null);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(eq(SapClientIdentity.PREFERENCE_KEY), anyString())).thenReturn(editor);
        when(editor.commit()).thenReturn(true);

        final String identity = SapClientIdentity.getOrCreate(preferences);
        assertTrue(SapClientIdentity.isValid(identity));
        verify(editor).putString(SapClientIdentity.PREFERENCE_KEY, identity);
        verify(editor).commit();
    }

    private static final class FixedRandom extends SecureRandom {
        @Override
        public void nextBytes(final byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        }
    }
}
