package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class SapFindMyPhoneCodecTest {
    private SapFindMyPhoneCodec.Command decode(String json) { return SapFindMyPhoneCodec.decode(SapFindMyPhoneCodec.frame(json)); }
    @Test public void staticWatchStartIsJsonWithStringTime() {
        assertEquals(SapFindMyPhoneCodec.Command.START, decode("{\"msgId\":\"fmp-startalert-req\",\"time\":\"180\"}"));
    }
    @Test public void stopAndFoundAckAreIdOnly() {
        assertEquals(SapFindMyPhoneCodec.Command.STOP, decode("{\"msgId\":\"fmp-stopalert-req\"}"));
        assertEquals(SapFindMyPhoneCodec.Command.FOUND_ACK, decode("{\"msgId\":\"fmp-founddevice-rsp\"}"));
    }
    @Test public void fieldOrderAndAdditionalFieldsAreNotCommands() {
        assertEquals(SapFindMyPhoneCodec.Command.START, decode("{\"other\":true,\"time\":\"180\",\"msgId\":\"fmp-startalert-req\",\"callstate\":false}"));
        assertEquals(SapFindMyPhoneCodec.Command.UNKNOWN, decode("{\"msgId\":\"mgr_host_location_req\",\"text\":\"fmp-stopalert-req\"}"));
    }
    @Test public void wrongStartFieldsRejected() {
        for (String time : new String[]{"180", "null", "\"181\"", "true", "[]"})
            assertThrows(IllegalArgumentException.class, () -> decode("{\"msgId\":\"fmp-startalert-req\",\"time\":"+time+"}"));
        assertThrows(IllegalArgumentException.class, () -> decode("{\"msgId\":\"fmp-startalert-req\"}"));
    }
    @Test public void invalidJsonAndEnvelopesRejected() {
        for (String text : new String[]{"[]", "null", "{}", "{", "{\"msgId\":1}", "{\"msgId\":null}", "{} {}"})
            assertThrows(IllegalArgumentException.class, () -> decode(text));
    }
    @Test public void sdkFlagsRawEnumsAndBadUtf8Rejected() {
        for (byte[] bytes : new byte[][]{new byte[]{0}, new byte[]{1}, new byte[]{0, (byte)0xc0, (byte)0x80}, new byte[]{16,123,125}, new byte[1025]})
            assertThrows(IllegalArgumentException.class, () -> SapFindMyPhoneCodec.decode(bytes));
    }
    private JSONObject object(byte[] body) throws Exception { return new JSONObject(new String(body,1,body.length-1,StandardCharsets.UTF_8)); }
    @Test public void startResponseUsesNumericResultAndReason() throws Exception {
        JSONObject success=object(SapFindMyPhoneCodec.startResponse(true)), failure=object(SapFindMyPhoneCodec.startResponse(false));
        assertEquals(0,success.get("result")); assertEquals(0,success.get("reason"));
        assertEquals(1,failure.get("result")); assertEquals(-1,failure.get("reason"));
    }
    @Test public void stopUsesStringsAndLocalFoundUsesNumber() throws Exception {
        JSONObject stop=object(SapFindMyPhoneCodec.stopResponse()), found=object(SapFindMyPhoneCodec.foundRequest());
        assertEquals("success",stop.get("result")); assertEquals("0",stop.get("reason"));
        assertEquals("fmp-founddevice-req",found.get("msgId")); assertEquals(0,found.get("result"));
        assertEquals(2,found.length()); assertEquals(3,stop.length());
    }
}
