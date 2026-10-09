package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SapWeatherDeliveryStateTest {
    @Test
    public void coalescesLatestWhileOnlyOneEnvelopeIsInFlight() {
        final SapWeatherDeliveryState state = new SapWeatherDeliveryState();
        final WeatherSpec lodz = weather("Lodz", 293);
        final WeatherSpec rome = weather("Rome, Monti", 298);
        state.open(225);
        state.update(lodz);
        assertSame(lodz, state.beginSend());
        state.sent(100);

        state.update(rome);
        state.update(weather("Rome, Monti", 298));
        assertFalse(state.hasPending());
        assertFalse(state.beginTermination());
        assertEquals(SapWeatherDeliveryState.AckResult.UNEXPECTED, state.acknowledge(225, 99));
        assertEquals(100, state.getExpectedAck());
        assertEquals(SapWeatherDeliveryState.AckResult.SEND_PENDING, state.acknowledge(225, 100));

        assertTrue(state.hasPending());
        assertEquals("Rome, Monti", state.beginSend().getLocation());
        state.sent(101);
        assertEquals(SapWeatherDeliveryState.AckResult.UNEXPECTED, state.acknowledge(225, 100));
        assertEquals(SapWeatherDeliveryState.AckResult.COMPLETE, state.acknowledge(225, 101));
        assertTrue(state.beginTermination());
        assertFalse(state.beginTermination());
    }

    @Test
    public void fetchSendsCachedWeatherAndClosePreservesItForNextSession() {
        final SapWeatherDeliveryState state = new SapWeatherDeliveryState();
        final WeatherSpec rome = weather("Rome, Monti", 298);
        state.update(rome);
        state.open(225);
        assertFalse(state.hasPending());
        state.fetch(225);
        assertSame(rome, state.beginSend());
        state.sent(200);
        assertEquals(SapWeatherDeliveryState.AckResult.COMPLETE, state.acknowledge(225, 200));
        assertTrue(state.beginTermination());
        state.clearSession();
        state.open(233);
        state.fetch(233);
        assertSame(rome, state.beginSend());
    }

    @Test
    public void duplicateProviderUpdateDoesNotCreateSecondEnvelope() {
        final SapWeatherDeliveryState state = new SapWeatherDeliveryState();
        state.open(225);
        state.update(weather("Rome, Monti", 298));
        state.beginSend();
        state.sent(300);
        state.update(weather("Rome, Monti", 298));
        assertEquals(SapWeatherDeliveryState.AckResult.COMPLETE, state.acknowledge(225, 300));
        assertFalse(state.hasPending());
    }

    @Test
    public void timeoutClosesOnlyTheMatchingOutstandingEnvelope() {
        final SapWeatherDeliveryState state = new SapWeatherDeliveryState();
        state.open(225);
        state.update(weather("Lodz", 293));
        state.beginSend();
        state.sent(400);
        state.update(weather("Rome, Monti", 298));
        assertFalse(state.timeOut(225, 399));
        assertFalse(state.timeOut(226, 400));
        assertTrue(state.timeOut(225, 400));
        assertTrue(state.beginTermination());
        assertEquals(SapWeatherDeliveryState.AckResult.UNEXPECTED, state.acknowledge(225, 400));
    }

    @Test
    public void newestUpdateDuringTerminationRequestsOneNewSession() {
        final SapWeatherDeliveryState state = new SapWeatherDeliveryState();
        final WeatherSpec rome = weather("Rome, Monti", 298);
        final WeatherSpec lodz = weather("Lodz", 293);
        state.open(225);
        state.update(rome);
        state.beginSend();
        state.sent(500);
        assertEquals(SapWeatherDeliveryState.AckResult.COMPLETE, state.acknowledge(225, 500));
        assertTrue(state.beginTermination());
        state.update(lodz);
        assertTrue(state.needsReconnectAfterTermination());
        state.update(rome);
        assertFalse(state.needsReconnectAfterTermination());
        state.update(lodz);
        assertTrue(state.needsReconnectAfterTermination());
        state.clearSession();
        state.open(226);
        state.fetch(226);
        assertSame(lodz, state.beginSend());
    }

    private static WeatherSpec weather(final String location, final int kelvin) {
        final WeatherSpec weather = new WeatherSpec();
        weather.setLocation(location);
        weather.setTimestamp(123);
        weather.setCurrentTemp(kelvin);
        return weather;
    }
}
