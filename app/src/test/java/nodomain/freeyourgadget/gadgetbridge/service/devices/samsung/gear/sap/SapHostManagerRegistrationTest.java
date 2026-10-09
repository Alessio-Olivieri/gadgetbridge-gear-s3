package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SapHostManagerRegistrationTest {
    @Test
    public void reusesPersistedOwnedIds() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getInt(SapHostManagerRegistration.AGENT_KEY, -1)).thenReturn(0x1234);
        when(preferences.getInt(SapHostManagerRegistration.COMPONENT_KEY, -1)).thenReturn(0x4567);
        final SapHostManagerRegistration registration = SapHostManagerRegistration.getOrCreate(preferences);
        assertEquals(0x1234, registration.getAgentUuid());
        assertEquals(0x4567, registration.getComponentId());
    }

    @Test
    public void replacesInvalidOrSamsungCapturedIds() {
        final SharedPreferences preferences = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.getInt(SapHostManagerRegistration.AGENT_KEY, -1)).thenReturn(0x7413);
        when(preferences.getInt(SapHostManagerRegistration.COMPONENT_KEY, -1)).thenReturn(0xa323);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putInt(eq(SapHostManagerRegistration.AGENT_KEY), anyInt())).thenReturn(editor);
        when(editor.putInt(eq(SapHostManagerRegistration.COMPONENT_KEY), anyInt())).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
        final SapHostManagerRegistration registration = SapHostManagerRegistration.getOrCreate(preferences);
        assertFalse(registration.getAgentUuid() == 0x7413 || registration.getAgentUuid() == 0xffff);
        assertFalse(registration.getComponentId() == 0xa323 || registration.getComponentId() == 0xff00);
        verify(editor).commit();
    }
}
