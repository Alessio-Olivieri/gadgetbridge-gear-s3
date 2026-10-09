package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

/**
 * PD branch ordering before WSM v2 starts. The PD 3.3 server does not
 * send PD success (0x09); it waits for the phone's WSM INIT after type 0x15.
 */
final class GearS3PdStateMachine {
    enum Action {
        SEND_CONFIGURATION_CHANGED,
        SEND_LEGACY_RESPONSE,
        SEND_WSM_INIT
    }

    private State state = State.WAIT_FOR_PD;

    Action onReconnectProbe() {
        require(State.WAIT_FOR_PD, "PD 3.3 reconnect probe");
        state = State.WAIT_FOR_PD33_OFFER;
        return Action.SEND_CONFIGURATION_CHANGED;
    }

    Action onPd33OfferAccepted() {
        require(State.WAIT_FOR_PD33_OFFER, "PD 3.3 configuration offer");
        state = State.WSM_STARTED;
        return Action.SEND_WSM_INIT;
    }

    Action onLegacyRequestAccepted() {
        require(State.WAIT_FOR_PD, "legacy PD request");
        state = State.WAIT_FOR_LEGACY_SUCCESS;
        return Action.SEND_LEGACY_RESPONSE;
    }

    Action onLegacySuccess() {
        require(State.WAIT_FOR_LEGACY_SUCCESS, "legacy PD success");
        state = State.WSM_STARTED;
        return Action.SEND_WSM_INIT;
    }

    private void require(final State expected, final String event) {
        if (state != expected) {
            throw new IllegalStateException("Unexpected " + event + " in state " + state);
        }
    }

    private enum State {
        WAIT_FOR_PD,
        WAIT_FOR_PD33_OFFER,
        WAIT_FOR_LEGACY_SUCCESS,
        WSM_STARTED
    }
}
