package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap;

import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;

/** One outstanding weather envelope, with only the newest provider update retained. */
public final class SapWeatherDeliveryState {
    public enum AckResult { UNEXPECTED, SEND_PENDING, COMPLETE }

    private int sessionId = -1;
    private WeatherSpec latest;
    private WeatherSpec pending;
    private WeatherSpec lastSent;
    private boolean pendingSend;
    private boolean inFlight;
    private boolean terminating;
    private boolean reconnectAfterTermination;
    private long expectedAck;

    public void open(final int sessionId) {
        if (sessionId < 1) throw new IllegalArgumentException("Invalid weather session");
        clearSession();
        this.sessionId = sessionId;
    }

    public void update(final WeatherSpec weather) {
        latest = weather;
        if (terminating) {
            reconnectAfterTermination = weather != null && (lastSent == null || !lastSent.equals(weather));
            return;
        }
        if (sessionId < 1 || weather == null) return;
        // Suppress duplicate weather content independently of ACK sequence.
        if (lastSent != null && lastSent.equals(weather)) {
            pending = null;
            pendingSend = false;
        } else {
            pending = weather;
            pendingSend = true;
        }
    }

    public void fetch(final int sessionId) {
        if (this.sessionId != sessionId || terminating) return;
        pending = latest;
        pendingSend = true; // An empty envelope still answers a fetch without cached weather.
    }

    public boolean hasPending() {
        return sessionId >= 1 && !terminating && !inFlight && pendingSend;
    }

    public WeatherSpec beginSend() {
        if (!hasPending()) throw new IllegalStateException("No sendable weather update");
        final WeatherSpec weather = pending;
        pending = null;
        pendingSend = false;
        lastSent = weather;
        inFlight = true;
        expectedAck = 0;
        return weather;
    }

    public void sent(final long sequence) {
        if (!inFlight || sequence < 1) throw new IllegalStateException("No weather envelope in flight");
        expectedAck = sequence;
    }

    public AckResult acknowledge(final int sessionId, final long sequence) {
        if (this.sessionId != sessionId || !inFlight || expectedAck == 0 || sequence != expectedAck) {
            return AckResult.UNEXPECTED;
        }
        inFlight = false;
        expectedAck = 0;
        return pendingSend ? AckResult.SEND_PENDING : AckResult.COMPLETE;
    }

    public boolean beginTermination() {
        if (sessionId < 1 || inFlight || pendingSend || terminating) return false;
        terminating = true;
        return true;
    }

    public void abortSend() {
        inFlight = false;
        expectedAck = 0;
        lastSent = null;
    }

    public void cancelTermination() {
        terminating = false;
        if (reconnectAfterTermination) {
            pending = latest;
            pendingSend = true;
        }
        reconnectAfterTermination = false;
    }

    public boolean needsReconnectAfterTermination() { return reconnectAfterTermination; }

    public boolean timeOut(final int sessionId, final long sequence) {
        if (this.sessionId != sessionId || !inFlight || expectedAck != sequence) return false;
        inFlight = false;
        expectedAck = 0;
        pending = null;
        pendingSend = false;
        return true;
    }

    public void clearSession() {
        sessionId = -1;
        pending = null;
        lastSent = null;
        pendingSend = false;
        inFlight = false;
        terminating = false;
        reconnectAfterTermination = false;
        expectedAck = 0;
    }

    public void reset() {
        clearSession();
        latest = null;
    }

    public int getSessionId() { return sessionId; }
    public long getExpectedAck() { return expectedAck; }
}
