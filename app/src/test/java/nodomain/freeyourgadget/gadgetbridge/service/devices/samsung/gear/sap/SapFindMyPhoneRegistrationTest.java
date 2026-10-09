package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import android.content.SharedPreferences;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SapFindMyPhoneRegistrationTest {
    @Test public void selectiveAndPersistentCapexExposeRealProvider() {
        for (int type : new int[]{SapCapexCodec.QUERY_TYPE_SELECTIVE,SapCapexCodec.QUERY_TYPE_PERSISTENT}) {
            SapFindMyPhoneRegistration local=new SapFindMyPhoneRegistration(0x3456,0x4567);
            SapCapabilityExchange exchange=new SapCapabilityExchange(2,1,0,Collections.singletonList(local.toCapabilityMetadata()));
            SapPostAuthRecord query=new SapPostAuthRecord(new SapTransportHeader(0,0,1020,0),SapCapexCodec.encodeQuery(new SapCapexCodec.Query(type,0,Arrays.asList("/system/fmp","/system/unknown"))));
            SapCapexCodec.Response response=SapCapexCodec.parseResponse(exchange.onRecord(query).getRecord().getBody());
            SapAgentDescriptor agent=response.getAgents().get(0);
            assertTrue(local.matchesLocalDescriptor(agent,agent.getServices().get(0)));
            assertEquals(0x0200,agent.getServices().get(0).getAspVersion());
            assertEquals(4,agent.getServices().get(0).getRoleFlags());
            assertEquals(0,agent.getServices().get(0).getTimeout());
        }
    }
    @Test public void initialQueryContainsImplementedFmpWithoutPriorWatchQuery() {
        SapFindMyPhoneRegistration local=new SapFindMyPhoneRegistration(0x3456,0x4567);
        SapCapabilityExchange exchange=new SapCapabilityExchange(1,2,0,Collections.singletonList(local.toCapabilityMetadata()));
        assertTrue(SapCapexCodec.parseQuery(exchange.createInitialQuery(0).getBody()).getProfiles().contains("/system/fmp"));
    }
    @Test public void persistedComponentCollisionAllocatesDistinctId() {
        SharedPreferences prefs=mock(SharedPreferences.class); SharedPreferences.Editor edit=mock(SharedPreferences.Editor.class);
        when(prefs.getInt(anyString(),anyInt())).thenReturn(0x4567); when(prefs.edit()).thenReturn(edit);
        when(edit.putInt(anyString(),anyInt())).thenReturn(edit); when(edit.commit()).thenReturn(true);
        Set<Integer> used=new HashSet<>(Arrays.asList(0x4567,0x3456,0x2345));
        SapFindMyPhoneRegistration local=SapFindMyPhoneRegistration.getOrCreate(prefs,new SapHostManagerRegistration(0x1234,0x2345),used);
        assertFalse(used.contains(local.getComponentId()));
        verify(edit).putInt("samsung_gear_find_my_phone_component_id",local.getComponentId());
    }
    @Test public void acceptsChangingRuntimeRemoteIdentities() {
        SapFindMyPhoneRegistration local=new SapFindMyPhoneRegistration(0x3456,0x4567);
        for(int component:new int[]{0x6789,0x789a}) {
            SapServiceDescriptor service=new SapServiceDescriptor(component,"/system/fmp",0x0100,5,0);
            local.setRemoteConsumer(new SapAgentDescriptor(component+1,"consumer",Collections.singletonList(service)),service);
            assertEquals(component,local.getRemoteConsumer().getComponentId());
        }
    }
    @Test public void wrongRoleRejected() {
        SapFindMyPhoneRegistration local=new SapFindMyPhoneRegistration(0x3456,0x4567);
        SapServiceDescriptor service=new SapServiceDescriptor(0x6789,"/system/fmp",0x0100,4,0);
        assertThrows(IllegalArgumentException.class,()->local.setRemoteConsumer(new SapAgentDescriptor(0x678a,"consumer",Collections.singletonList(service)),service));
    }
}
