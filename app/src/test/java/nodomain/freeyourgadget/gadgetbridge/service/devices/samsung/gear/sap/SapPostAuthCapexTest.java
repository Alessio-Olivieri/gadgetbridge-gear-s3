package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** SAP post-authentication and capability-exchange codec fixtures. */
public class SapPostAuthCapexTest {

    @Test
    public void acceptsExactSmR760SsduBoundaryAndRejectsOneByteOver() {
        final byte[] largestBody = new byte[SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH - 2];
        final SapPostAuthRecord largest = new SapPostAuthRecord(new SapTransportHeader(0, 0, 1, 0), largestBody);
        assertEquals(SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH + 6, SapPostAuthRecordCodec.encode(largest).length);
        final byte[] tooLargeBody = new byte[SapPostAuthRecordCodec.MAX_PAYLOAD_LENGTH - 1];
        assertThrows(IllegalArgumentException.class, () -> SapPostAuthRecordCodec.encode(
                new SapPostAuthRecord(new SapTransportHeader(0, 0, 1, 0), tooLargeBody)));
    }
    private static final byte[] PHONE_INITIAL_QUERY = fixture("AmKJgA/wAQP/////Hy9zeXN0ZW0vbGJzLXNlcnZlcjsvc3lzdGVtL3NrbXNhOy9zeXN0ZW0vd2VhdGhlcjI7L3N5c3RlbS9yZW1pbmRlcjsvc3lzdGVtL3dhdGNoX3BlZG9tZXRlcjsvc3lzdGVtL2VtYWlsbm90aWZpY2F0aW9uOy9zeXN0ZW0vc2ZvdGE7L3N5c3RlbS9jYWxsaGFuZGxlcjsvc3lzdGVtL2FsYXJtOy9zeXN0ZW0vd2VicHJveHk7L3N5c3RlbS9CaXhieVZvaWNlU0FQU2VydmljZTsvc3lzdGVtL2NhbGVuZGFyOy9hcHAvZ2FsbGVyeS9yZWNlaXZlcjsvd2VhcmFibGUvY2FtZXJhY29udHJvbDsvc2V0dGluZ3MvSU1FU2VydmljZTsvc3lzdGVtL3NhbXN1bmdtdXNpYzsvc3lzdGVtL211c2ljOy9zeXN0ZW0vTm90aWZpY2F0aW9uU2VydmljZTsvc3lzdGVtL2hvc3RtYW5hZ2VyOy9hcHAvZ2FsbGVyeS90cmFuc2ZlcjsvYXBwL21pbmltZXNzYWdlOy9zZXR0aW5ncy9UVFNTZXJ2aWNlOy9zeXN0ZW0vdm9pY2VtZW1vOy9zeXN0ZW0vYjJjb250YWN0czsvc3lzdGVtL211c2ljdHJhbnNmZXI7L3N5c3RlbS9maWxldHJhbnNmZXI7L3N5c3RlbS9zYXAtc2U7L3N5c3RlbS9jb250ZXh0Oy9zeXN0ZW0vYmNtc2VydmljZTsvc3lzdGVtL2ZtcDsvc3lzdGVtL3ZyY29udHJvbGxlcjsFJw==");
    private static final byte[] GEAR_RESPONSE = fixture("BoYCgg/wAgP/////AB2CMS91c3IvYmluL2xicy1zZXJ2ZXI7AAHeNi9zeXN0ZW0vbGJzLXNlcnZlcjsBAAUAAIIyY29tLnNhbXN1bmcuc2ttc2E7AAHeNy9zeXN0ZW0vc2ttc2E7AQAEAGRYDGNvbS5zYW1zdW5nLndlYXRoZXItY29uc3VtZXI7AAEWGC9zeXN0ZW0vd2VhdGhlcjI7AQAFAABYDWNvbS5zYW1zdW5nLnctcmVtaW5kZXItY29uc3VtOwABFhkvc3lzdGVtL3JlbWluZGVyOwEABQAAWBFjb20uc2Ftc3VuZy5zaGVhbHRoLnNlcnZpY2U7AAEAOS9zeXN0ZW0vd2F0Y2hfcGVkb21ldGVyOwEABAAAWBJjb20uc2Ftc3VuZy53ZW1haWwuY29uc3VtZXI7AAEWHS9zeXN0ZW0vZW1haWxub3RpZmljYXRpb247AQAFAACCN2NvbS5zYW1zdW5nLmZvdGEtc2VydmljZTsAAd48L3N5c3RlbS9zZm90YTsBAAUAAFgQY29tLnNhbXN1bmcuY2FsbC5jb25zdW1lcjsAARYcL3N5c3RlbS9jYWxsaGFuZGxlcjsBAAUAAFgWY29tLnNhbXN1bmcuYWxhcm0uY29uc3VtZXI7AAEWIS9zeXN0ZW0vYWxhcm07AQAFAACCLi91c3Ivc2Jpbi93ZWItcHJveHlkOwAB3jEvc3lzdGVtL3dlYnByb3h5OwIABQAKWBhjb20uc2Ftc3VuZy50aXplbi5iaXhieS12b2ljZTsAARYjL3N5c3RlbS9CaXhieVZvaWNlU0FQU2VydmljZTsBAAgAAFgLY29tLnNhbXN1bmcudy1jYWxlbmRhcjItY29uc3U7AAEWFy9zeXN0ZW0vY2FsZW5kYXI7AQAFAABYCXctZ2FsbGVyeS5jb25zdW1lci5yZWNlaXZlcjsAAhYVL2FwcC9nYWxsZXJ5L3RyYW5zZmVyOwMBBQAAFhQvYXBwL2dhbGxlcnkvcmVjZWl2ZXI7AwIFAAD//y93ZWFyYWJsZS9jYW1lcmFjb250cm9sOwAB/wAvd2VhcmFibGUvY2FtZXJhY29udHJvbDsBAEAAAP//L3NldHRpbmdzL0lNRVNlcnZpY2U7AAH/AC9zZXR0aW5ncy9JTUVTZXJ2aWNlOwEAQAAA//8vc3lzdGVtL3NhbXN1bmdtdXNpYzsAAf8AL3N5c3RlbS9zYW1zdW5nbXVzaWM7AQBAAACCNWNvbS5zYW1zdW5nLnctbXVzaWMtcGxheWVyLm11OwAB3jovc3lzdGVtL211c2ljOwEABQAAgi8vdXNyL2Jpbi93bm90aS1zZXJ2aWNlMjsAAt4zL3N5c3RlbS9jb250ZXh0OwEABQAA3jIvc3lzdGVtL05vdGlmaWNhdGlvblNlcnZpY2U7AQAFAABYGmNvbS5zYW1zdW5nLnctbWFuYWdlci1zZXJ2aWNlOwABFiUvc3lzdGVtL2hvc3RtYW5hZ2VyOwEABQAAWApjb20uc2Ftc3VuZy5tZXNzYWdlLmNvbnN1bWVyOwABFhYvYXBwL21pbmltZXNzYWdlOwEABQAA//8vc2V0dGluZ3MvVFRTU2VydmljZTsAAf8AL3NldHRpbmdzL1RUU1NlcnZpY2U7AQBAAAD//y9zeXN0ZW0vdm9pY2VtZW1vOwAB/wAvc3lzdGVtL3ZvaWNlbWVtbzsBAEAAAFgOY29tLnNhbXN1bmcudy1jb250YWN0czIuY29uc3U7AAEWGi9zeXN0ZW0vYjJjb250YWN0czsBAAUAAFgPY29tLnNhbXN1bmcudy1tdXNpYy10cmFuc2Zlci47AAEWGy9zeXN0ZW0vbXVzaWN0cmFuc2ZlcjsBAAUAAIIwRlQ7AALeNS9zeXN0ZW0vZmlsZXRyYW5zZmVyOwIABQAA3jQvc3lzdGVtL2ZpbGV0cmFuc2ZlcjsCAAQAAIIzdy1zZWN1cmUtZWxlbWVudDsAAd44L3N5c3RlbS9zYXAtc2U7AQAEAAqCNC91c3IvYmluL3dlY29ubmQ7AAHeOS9zeXN0ZW0vYmNtc2VydmljZTsCAAQAAFgVY29tLnNhbXN1bmcuZm1tLXBob25lOwABFiAvc3lzdGVtL2ZtcDsBAAUAAP//L3N5c3RlbS92cmNvbnRyb2xsZXI7AAH/AC9zeXN0ZW0vdnJjb250cm9sbGVyOwEAQAAAoZ4=");
    private static final byte[] GEAR_RECIPROCAL_QUERY = fixture("AeTbAQ/wAQIAAAAAGS9zeXN0ZW0vbXVzaWM7L3N5c3RlbS9iY21zZXJ2aWNlOy9zeXN0ZW0vc2FwLXNlOy9zeXN0ZW0vc2ttc2E7L3N5c3RlbS9sYnMtc2VydmVyOy9zeXN0ZW0vZmlsZXRyYW5zZmVyOy9zeXN0ZW0vY29udGV4dDsvc3lzdGVtL05vdGlmaWNhdGlvblNlcnZpY2U7L3N5c3RlbS93ZWJwcm94eTsvc3lzdGVtL2hvc3RtYW5hZ2VyOy9zeXN0ZW0vYWxhcm07L3N5c3RlbS9CaXhieVZvaWNlU0FQU2VydmljZTsvc3lzdGVtL2VtYWlsbm90aWZpY2F0aW9uOy9zeXN0ZW0vZm1wOy9zeXN0ZW0vd2F0Y2hfcGVkb21ldGVyOy9zeXN0ZW0vY2FsbGhhbmRsZXI7L3N5c3RlbS9tdXNpY3RyYW5zZmVyOy9zeXN0ZW0vYjJjb250YWN0czsvc3lzdGVtL3JlbWluZGVyOy9zeXN0ZW0vd2VhdGhlcjI7L3N5c3RlbS9jYWxlbmRhcjsvYXBwL21pbmltZXNzYWdlOy9hcHAvZ2FsbGVyeS90cmFuc2ZlcjsvYXBwL2dhbGxlcnkvcmVjZWl2ZXI7L3N5c3RlbS9zZm90YTulCA==");
    private static final byte[] PHONE_RESPONSE = fixture("Atb+gA/wAgMmLpdvAAJW8VNBU3lzdGVtUHJvdmlkZXJzOwAD2scvc3lzdGVtL2ZpbGV0cmFuc2ZlcjsCAAQACu4QL3N5c3RlbS9maWxldHJhbnNmZXI7AgAFAApNay9zeXN0ZW0vd2VicHJveHk7AgEEAAp0E0hvc3RNYW5hZ2VyOwAXk4wvc3lzdGVtL211c2ljOwIABAAA7Lsvc3lzdGVtL2JjbXNlcnZpY2U7AgAFAADfhy9zeXN0ZW0vc2FwLXNlOwEABQAKB0ovc3lzdGVtL3NrbXNhOwEABQBkA8kvc3lzdGVtL2xicy1zZXJ2ZXI7AgAEAADlvi9zeXN0ZW0vY29udGV4dDsCAAQAAJp/L3N5c3RlbS9Ob3RpZmljYXRpb25TZXJ2aWNlOwIABAAAoyMvc3lzdGVtL2hvc3RtYW5hZ2VyOwEABAAAQjAvc3lzdGVtL2FsYXJtOwIABAAAXC0vc3lzdGVtL0JpeGJ5Vm9pY2VTQVBTZXJ2aWNlOwEADQAKLjQvc3lzdGVtL2VtYWlsbm90aWZpY2F0aW9uOwIABAAA9sUvc3lzdGVtL2ZtcDsCAAQAACsiL3N5c3RlbS93YXRjaF9wZWRvbWV0ZXI7AgAFAABB7y9zeXN0ZW0vY2FsbGhhbmRsZXI7AgAEAADXCi9zeXN0ZW0vbXVzaWN0cmFuc2ZlcjsCAAQAANQlL3N5c3RlbS9iMmNvbnRhY3RzOwIABAAAKisvc3lzdGVtL3JlbWluZGVyOwIABAAAKbIvc3lzdGVtL3dlYXRoZXIyOwEABAAKbd4vc3lzdGVtL2NhbGVuZGFyOwIABAAAqnEvYXBwL21pbmltZXNzYWdlOwIABAAAo/MvYXBwL2dhbGxlcnkvdHJhbnNmZXI7AwAEAABwmC9hcHAvZ2FsbGVyeS9yZWNlaXZlcjsCAQQAADH9L3N5c3RlbS9zZm90YTsBAAQACpYd");

    @Test
    public void decodesAndReencodesAllFourCapturedRecords() {
        assertCaptured(PHONE_INITIAL_QUERY, 1, 31, 0, 0);
        assertCaptured(GEAR_RESPONSE, 2, 0, 29, 32);
        assertCaptured(GEAR_RECIPROCAL_QUERY, 1, 25, 0, 0);
        assertCaptured(PHONE_RESPONSE, 2, 0, 2, 26);
    }

    @Test
    public void reassemblesGearResponseAtEverySplitPoint() {
        for (int split = 1; split < GEAR_RESPONSE.length; split++) {
            final SapPostAuthRecordCodec codec = new SapPostAuthRecordCodec();
            assertEquals(0, codec.accept(Arrays.copyOfRange(GEAR_RESPONSE, 0, split)).size());
            assertEquals(1, codec.accept(Arrays.copyOfRange(GEAR_RESPONSE, split, GEAR_RESPONSE.length)).size());
        }
    }

    @Test
    public void acceptsCoalescedRecordsAndRejectsMalformedFraming() {
        final byte[] joined = concat(PHONE_INITIAL_QUERY, GEAR_RECIPROCAL_QUERY);
        assertEquals(2, new SapPostAuthRecordCodec().accept(joined).size());
        final byte[] badLength = PHONE_INITIAL_QUERY.clone();
        badLength[2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> new SapPostAuthRecordCodec().accept(badLength));
        final byte[] badPayload = PHONE_INITIAL_QUERY.clone();
        badPayload[10] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> new SapPostAuthRecordCodec().accept(badPayload));
    }

    @Test
    public void rejectsInvalidCapexBodyAndTransportHeader() {
        assertThrows(IllegalArgumentException.class, () -> SapCapexCodec.parseQuery(new byte[]{1, 3, 0, 0, 0, 0, 1, 'x'}));
        assertThrows(IllegalArgumentException.class, () -> SapCapexCodec.parseResponse(new byte[]{2, 3, 0, 0, 0, 0, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> new SapTransportHeader(0, 0, 1021, 0).requireCapexDataFrame());
        assertThrows(IllegalArgumentException.class, () -> new SapTransportHeader(0, 0, 1020, 1).requireCapexDataFrame());
    }

    @Test
    public void buildsCurrentZeroProfileInitialQuery() {
        final SapCapabilityExchange exchange = new SapCapabilityExchange(0, 31, 0);
        assertArrayEquals(hex("000906c00ff00103ffffffff00870b"), SapPostAuthRecordCodec.encode(exchange.createInitialQuery(0xffffffffL)));
        assertEquals(SapCapabilityExchange.State.WAIT_RESPONSE, exchange.getState());
    }

    @Test
    public void answersEmptyReciprocalQueryWithZeroAgentResponse() {
        final SapCapabilityExchange exchange = new SapCapabilityExchange(0, 32, 0);
        exchange.onRecord(responseRecord(0, new SapAgentDescriptor[0]));

        final SapCapabilityExchange.Reply reply = exchange.onRecord(queryRecord(2, 0, new String[0]));

        assertEquals(SapCapabilityExchange.State.WAIT_FOLLOW_UP, exchange.getState());
        assertTrue(reply.isChecksumCurrent());
        assertEquals(0, reply.getResponse().getChecksum());
        assertEquals(0, reply.getResponse().getAgents().size());
        assertArrayEquals(hex("000a07800ff00203000000000000ed86"), SapPostAuthRecordCodec.encode(reply.getRecord()));
    }

    @Test
    public void buildsSourceDerivedUnavailableProfileResponses() {
        final SapCapabilityExchange exchange = completedExchange();

        final SapCapabilityExchange.Reply webProxy = exchange.onRecord(queryRecord(2, 0, new String[]{"/system/webproxy"}));
        assertFalse(webProxy.isChecksumCurrent());
        assertEquals(0xd1a192f2L, webProxy.getResponse().getChecksum());
        assertDummy(webProxy, "/system/webproxy");
        assertArrayEquals(hex("0037d6410ff00203d1a192f20001ffff2f73797374656d2f77656270726f78793b0001ff002f73797374656d2f77656270726f78793b0100010000fabd"),
                SapPostAuthRecordCodec.encode(webProxy.getRecord()));

        final SapCapabilityExchange.Reply bcmService = exchange.onRecord(queryRecord(2, 0, new String[]{"/system/bcmservice"}));
        assertFalse(bcmService.isChecksumCurrent());
        assertEquals(0xeb2f068bL, bcmService.getResponse().getChecksum());
        assertDummy(bcmService, "/system/bcmservice");
        assertArrayEquals(hex("003bd3410ff00203eb2f068b0001ffff2f73797374656d2f62636d736572766963653b0001ff002f73797374656d2f62636d736572766963653b010001000070d5"),
                SapPostAuthRecordCodec.encode(bcmService.getRecord()));
    }

    @Test
    public void deduplicatesProfilesAndReturnsEmptyResponseForCurrentChecksum() {
        final SapCapabilityExchange exchange = completedExchange();
        final SapCapabilityExchange.Reply first = exchange.onRecord(queryRecord(2, 0, new String[]{"/system/webproxy"}));

        final SapCapabilityExchange.Reply duplicate = exchange.onRecord(queryRecord(2, first.getResponse().getChecksum(), new String[]{"/system/webproxy"}));

        assertTrue(duplicate.isChecksumCurrent());
        assertEquals(1, duplicate.getPersistentProfiles().size());
        assertEquals("/system/webproxy", duplicate.getPersistentProfiles().get(0));
        assertEquals(0, duplicate.getResponse().getAgents().size());
    }

    @Test
    public void groupsMultipleUnavailableProfilesAndSortsChecksumInput() {
        final SapCapabilityExchange first = completedExchange();
        final SapCapabilityExchange second = completedExchange();

        final SapCapabilityExchange.Reply firstReply = first.onRecord(queryRecord(2, 0, new String[]{"/b", "/a"}));
        second.onRecord(queryRecord(2, 0, new String[]{"/a"}));
        final SapCapabilityExchange.Reply secondReply = second.onRecord(queryRecord(2, 0, new String[]{"/b"}));

        assertEquals(firstReply.getResponse().getChecksum(), secondReply.getResponse().getChecksum());
        assertEquals(1, firstReply.getResponse().getAgents().size());
        assertEquals(2, firstReply.getResponse().getAgents().get(0).getServices().size());
        assertEquals(0x01, firstReply.getResponse().getAgents().get(0).getServices().get(0).getRoleFlags());
        assertFalse(0x40 == firstReply.getResponse().getAgents().get(0).getServices().get(0).getRoleFlags());
    }

    private static void assertCaptured(final byte[] wire,
                                       final int type,
                                       final int profiles,
                                       final int agents,
                                       final int services) {
        final SapPostAuthRecordCodec codec = new SapPostAuthRecordCodec();
        final List<SapPostAuthRecord> records = codec.accept(wire);
        assertEquals(1, records.size());
        final SapPostAuthRecord record = records.get(0);
        record.getHeader().requireCapexDataFrame();
        assertArrayEquals(wire, SapPostAuthRecordCodec.encode(record));
        assertEquals(type, record.getBody()[0] & 0xff);
        if (type == 1) {
            final SapCapexCodec.Query query = SapCapexCodec.parseQuery(record.getBody());
            assertEquals(profiles, query.getProfiles().size());
            assertArrayEquals(record.getBody(), SapCapexCodec.encodeQuery(query));
        } else {
            final SapCapexCodec.Response response = SapCapexCodec.parseResponse(record.getBody());
            assertEquals(agents, response.getAgents().size());
            int count = 0;
            for (final SapAgentDescriptor agent : response.getAgents()) count += agent.getServices().size();
            assertEquals(services, count);
            assertArrayEquals(record.getBody(), SapCapexCodec.encodeResponse(response));
        }
    }

    private static SapPostAuthRecord decode(final byte[] wire) {
        final List<SapPostAuthRecord> records = new SapPostAuthRecordCodec().accept(wire);
        assertEquals(1, records.size());
        return records.get(0);
    }

    private static SapCapabilityExchange completedExchange() {
        final SapCapabilityExchange exchange = new SapCapabilityExchange(0, 32, 0);
        exchange.onRecord(responseRecord(0, new SapAgentDescriptor[0]));
        exchange.onRecord(queryRecord(2, 0, new String[0]));
        return exchange;
    }

    private static SapPostAuthRecord queryRecord(final int type, final long checksum, final String[] profiles) {
        return new SapPostAuthRecord(new SapTransportHeader(0, 0, 1020, 0),
                SapCapexCodec.encodeQuery(new SapCapexCodec.Query(type, checksum, Arrays.asList(profiles))));
    }

    private static SapPostAuthRecord responseRecord(final long checksum, final SapAgentDescriptor[] agents) {
        return new SapPostAuthRecord(new SapTransportHeader(0, 0, 1020, 0),
                SapCapexCodec.encodeResponse(new SapCapexCodec.Response(checksum, Arrays.asList(agents))));
    }

    private static void assertDummy(final SapCapabilityExchange.Reply reply, final String profile) {
        assertEquals(1, reply.getResponse().getAgents().size());
        final SapAgentDescriptor agent = reply.getResponse().getAgents().get(0);
        assertEquals(0xffff, agent.getUuid());
        assertEquals(profile, agent.getFriendlyName());
        assertEquals(1, agent.getServices().size());
        final SapServiceDescriptor service = agent.getServices().get(0);
        assertEquals(0xff00, service.getComponentId());
        assertEquals(profile, service.getProfileId());
        assertEquals(0x0100, service.getAspVersion());
        assertEquals(0x01, service.getRoleFlags());
        assertEquals(0, service.getTimeout());
    }

    private static byte[] fixture(final String base64) {
        return Base64.getDecoder().decode(base64);
    }

    private static byte[] hex(final String string) {
        final byte[] result = new byte[string.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(string.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    private static byte[] concat(final byte[] first, final byte[] second) {
        final byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
