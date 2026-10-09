package nodomain.freeyourgadget.gadgetbridge.util;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class FindPhoneSessionTest {
    private static class Listener implements FindPhoneSession.Listener {
        int starts,finishes;boolean failed;
        public void started(){starts++;}
        public void finished(boolean f){finishes++;failed=f;}
    }
    @Test public void canceledPendingIntentCannotPlay() { FindPhoneSession s=new FindPhoneSession();Listener l=new Listener();String t=s.begin(l);s.cancel(t);assertFalse(s.attachAndRun(t,()->fail("cleanup without playback"),()->fail("late sound")));assertEquals(0,l.starts);assertEquals(0,l.finishes); }
    @Test public void duplicateStartHasSinglePlayer() { FindPhoneSession s=new FindPhoneSession();Listener l=new Listener();String t=s.begin(l);AtomicInteger starts=new AtomicInteger();assertTrue(s.attachAndRun(t,()->{},starts::incrementAndGet));assertFalse(s.attachAndRun(t,()->{},starts::incrementAndGet));assertEquals(1,starts.get());assertEquals(1,l.starts); }
    @Test public void duplicateCancelCleansOnceWithoutLocalFoundCallback() { FindPhoneSession s=new FindPhoneSession();Listener l=new Listener();String t=s.begin(l);AtomicInteger stops=new AtomicInteger();s.attachAndRun(t,stops::incrementAndGet,()->{});s.cancel(t);s.cancel(t);s.finish(t,false);assertEquals(1,stops.get());assertEquals(0,l.finishes); }
    @Test public void localFinishReportsOnce() { FindPhoneSession s=new FindPhoneSession();Listener l=new Listener();String t=s.begin(l);s.attachAndRun(t,()->{},()->{});s.finish(t,false);s.finish(t,false);assertEquals(1,l.finishes);assertFalse(l.failed);assertTrue(s.idle()); }
    @Test public void failedStartupCannotReportSuccess() { FindPhoneSession s=new FindPhoneSession();Listener l=new Listener();String t=s.begin(l);s.attachAndRun(t,()->{},()->s.finish(t,true));assertEquals(0,l.starts);assertEquals(1,l.finishes);assertTrue(l.failed); }
    @Test public void staleCancellationCannotAffectNewOwner() { FindPhoneSession s=new FindPhoneSession();String a=s.begin(new Listener());s.cancel(a);String b=s.begin(new Listener());s.cancel(a);s.finish(a,false);assertTrue(s.current(b));assertFalse(s.current(a)); }
    @Test public void anotherDeviceCannotTakeOwnership() { FindPhoneSession s=new FindPhoneSession();String a=s.begin(new Listener());assertNull(s.begin(new Listener()));assertFalse(s.enterLegacy());assertTrue(s.current(a)); }
    @Test public void legacyPlaybackPreventsOwnedLaunch() { FindPhoneSession s=new FindPhoneSession();assertTrue(s.enterLegacy());assertNull(s.begin(new Listener()));s.leaveLegacy();assertNotNull(s.begin(new Listener())); }
    @Test public void cancellationSerializesWithActualPlaybackStartup() throws Exception {
        FindPhoneSession s=new FindPhoneSession();String t=s.begin(new Listener());CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger players=new AtomicInteger();
        Thread start=new Thread(()->s.attachAndRun(t,players::decrementAndGet,()->{entered.countDown();try{assertTrue(release.await(2,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}players.incrementAndGet();}));
        start.start();assertTrue(entered.await(2,TimeUnit.SECONDS));Thread stop=new Thread(()->s.cancel(t));stop.start();release.countDown();start.join(2000);stop.join(2000);assertFalse(start.isAlive());assertFalse(stop.isAlive());assertEquals(0,players.get());assertFalse(s.current(t));
    }
}
