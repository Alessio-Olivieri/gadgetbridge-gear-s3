package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Find My Phone message codec. */
public final class SapFindMyPhoneCodec {
    public enum Command { START, STOP, FOUND_ACK, UNKNOWN }
    private SapFindMyPhoneCodec() { }

    /** Input is exactly one clear SDK body after any negotiated reliable prefix. */
    public static Command decode(final byte[] body) {
        if (body == null || body.length < 3 || body.length > 1024 || body[0] != 0) {
            throw new IllegalArgumentException("Invalid FMP clear SDK body");
        }
        try {
            final String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body, 1, body.length - 1)).toString();
            final JSONTokener parser = new JSONTokener(json);
            final Object value = parser.nextValue();
            if (!(value instanceof JSONObject) || parser.nextClean() != 0) {
                throw new IllegalArgumentException("Invalid FMP JSON envelope");
            }
            final JSONObject object = (JSONObject) value;
            final Object id = object.opt("msgId");
            if (!(id instanceof String)) throw new IllegalArgumentException("Missing FMP command");
            switch ((String) id) {
                case "fmp-startalert-req":
                    if (!"180".equals(object.opt("time"))
                            || (object.has("callstate") && !(object.opt("callstate") instanceof Boolean))) {
                        throw new IllegalArgumentException("Unsupported FMP Start fields");
                    }
                    return Command.START;
                case "fmp-stopalert-req": return Command.STOP;
                case "fmp-founddevice-rsp": return Command.FOUND_ACK;
                default: return Command.UNKNOWN;
            }
        } catch (final JSONException | CharacterCodingException e) {
            // Never include untrusted JSON or personal fields in diagnostics.
            throw new IllegalArgumentException("Malformed FMP JSON");
        }
    }

    public static byte[] startResponse(final boolean success) {
        return frame(success ? "{\"msgId\":\"fmp-startalert-rsp\",\"result\":0,\"reason\":0}"
                : "{\"msgId\":\"fmp-startalert-rsp\",\"result\":1,\"reason\":-1}");
    }
    public static byte[] stopResponse() {
        return frame("{\"msgId\":\"fmp-stopalert-rsp\",\"result\":\"success\",\"reason\":\"0\"}");
    }
    public static byte[] foundRequest() {
        return frame("{\"msgId\":\"fmp-founddevice-req\",\"result\":0}");
    }
    public static byte[] frame(final String json) {
        final byte[] text = json.getBytes(StandardCharsets.UTF_8);
        final byte[] result = new byte[text.length + 1];
        System.arraycopy(text, 0, result, 1, text.length);
        return result;
    }
}
