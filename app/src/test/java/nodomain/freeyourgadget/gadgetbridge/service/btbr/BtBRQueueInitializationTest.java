package nodomain.freeyourgadget.gadgetbridge.service.btbr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class BtBRQueueInitializationTest {
    @Test
    public void protocolStateExistsBeforeTheReadThreadCanRun() {
        final AtomicBoolean initialized = new AtomicBoolean();
        final List<String> order = new ArrayList<>();
        BtBRQueue.initializeBeforeRead(() -> {
            order.add("initialize");
            initialized.set(true);
        }, () -> {
            assertTrue(initialized.get());
            order.add("read");
        });
        assertEquals(Arrays.asList("initialize", "read"), order);
    }
}
