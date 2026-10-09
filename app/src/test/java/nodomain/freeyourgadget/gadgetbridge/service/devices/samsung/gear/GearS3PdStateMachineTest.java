package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class GearS3PdStateMachineTest {
    @Test
    public void legacyPdWaitsForSuccessBeforeStartingWsm() {
        final GearS3PdStateMachine stateMachine = new GearS3PdStateMachine();

        assertEquals(GearS3PdStateMachine.Action.SEND_LEGACY_RESPONSE,
                stateMachine.onLegacyRequestAccepted());
        assertEquals(GearS3PdStateMachine.Action.SEND_WSM_INIT,
                stateMachine.onLegacySuccess());
    }

    @Test
    public void pd33StartsWsmImmediatelyAfterAnswerAndRejectsLateSuccess() {
        final GearS3PdStateMachine stateMachine = new GearS3PdStateMachine();

        assertEquals(GearS3PdStateMachine.Action.SEND_CONFIGURATION_CHANGED,
                stateMachine.onReconnectProbe());
        assertEquals(GearS3PdStateMachine.Action.SEND_WSM_INIT,
                stateMachine.onPd33OfferAccepted());
        assertThrows(IllegalStateException.class, stateMachine::onLegacySuccess);
    }
}
