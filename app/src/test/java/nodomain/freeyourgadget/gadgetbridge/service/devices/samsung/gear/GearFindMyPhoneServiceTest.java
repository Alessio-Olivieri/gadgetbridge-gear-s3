package nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear;

import org.junit.Test;
import java.util.*;
import java.util.function.Consumer;
import nodomain.freeyourgadget.gadgetbridge.service.devices.samsung.gear.sap.*;
import static org.junit.Assert.*;

public class GearFindMyPhoneServiceTest {
    private static final String START="{\"msgId\":\"fmp-startalert-req\",\"time\":\"180\"}";
    private static final String STOP="{\"msgId\":\"fmp-stopalert-req\"}";
    private static final class Task { long due; Runnable run; boolean canceled; }
    private static final class Fixture implements GearFindMyPhoneService.Alert, GearFindMyPhoneService.Emitter, GearFindMyPhoneService.Scheduler {
        final List<SapPostAuthRecord> sent=new ArrayList<>(); final List<Task> tasks=new ArrayList<>();
        boolean connected=true, available=true, active; long now; int starts, stops; Runnable accepted, started; Consumer<Boolean> finished;
        final SapFindMyPhoneRegistration registration=new SapFindMyPhoneRegistration(0x1234,0x2345);
        final GearFindMyPhoneService service=new GearFindMyPhoneService(registration,this,this,this);
        Fixture() { service.learnRemote(Collections.singletonList(new SapAgentDescriptor(0x5678,"consumer",Collections.singletonList(new SapServiceDescriptor(0x6789,"/system/fmp",0x0100,5,0))))); }
        public void send(SapPostAuthRecord r,long generation) { sent.add(r); }
        public boolean connected() { return connected; }
        public boolean start(Runnable a,Runnable s,Consumer<Boolean> f) { starts++; accepted=a;started=s;finished=f;active=available; return available; }
        public void stop() { if(active) stops++;active=false; }
        public Runnable later(Runnable runnable,long delay) { Task task=new Task();task.run=runnable;task.due=now+delay;tasks.add(task);return ()->task.canceled=true; }
        void advance(long delta) { long end=now+delta; while(true) { Task next=null; for(Task t:tasks) if(!t.canceled&&t.due<=end&&(next==null||t.due<next.due))next=t; if(next==null)break;now=next.due;next.canceled=true;next.run.run(); } now=end; }
        SapServiceConnectionRequest request(int id,int qos) { return new SapServiceConnectionRequest(0x2345,0x6789,"/system/fmp",Collections.singletonList(new SapServiceConnectionRequest.Session(id,103,new SapQosDescriptor(qos,qos==4?1:0,qos==4?2:0),0))); }
        void open(int id,int qos) { assertEquals(0,service.accept(request(id,qos),Collections.emptySet()).getStatus()); }
        void command(String json) { record(SapFindMyPhoneCodec.frame(json),0); }
        void record(byte[] body,int type) { service.onRecord(new SapPostAuthRecord(new SapTransportHeader(0,type,service.session(),0),body),service.generation()); }
        void reliable(int seq,String json) { byte[] sdk=SapFindMyPhoneCodec.frame(json),body=new byte[sdk.length+2];body[0]=(byte)(seq>>8);body[1]=(byte)seq;System.arraycopy(sdk,0,body,2,sdk.length);record(body,0); }
        byte[] last() { return sent.get(sent.size()-1).getBody(); }
    }
    @Test public void dynamicSessionsAndResponseSlotDirection() {
        for(int id:new int[]{71,603}) { Fixture f=new Fixture(); SapServiceConnectionResponse rsp=f.service.accept(f.request(id,4),Collections.emptySet());assertEquals(0,rsp.getStatus());assertEquals(id,f.service.session());assertEquals(0x6789,rsp.getAcceptorSlot());assertEquals(0x2345,rsp.getInitiatorSlot()); }
    }
    @Test public void collisionsDoNotClaimSession() {
        Fixture f=new Fixture();assertEquals(SapServiceConnectionResponse.STATUS_SESSION_COLLISION,f.service.accept(f.request(71,4),Collections.singleton(71)).getStatus());assertEquals(-1,f.service.session());
    }
    @Test public void wrongChannelPayloadQosAndIdentityRejected() {
        for(int kind=0;kind<6;kind++) { Fixture f=new Fixture();SapServiceConnectionRequest r=new SapServiceConnectionRequest(kind==0?0x2346:0x2345,kind==1?0x6788:0x6789,kind==2?"/system/hostmanager":"/system/fmp",Collections.singletonList(new SapServiceConnectionRequest.Session(71,kind==3?104:103,new SapQosDescriptor(kind==4?7:4,0,0),kind==5?1:0)));assertNotEquals(0,f.service.accept(r,Collections.emptySet()).getStatus());assertEquals(-1,f.service.session()); }
    }
    @Test public void repeatCreateDoesNotReplaceLiveSession() { Fixture f=new Fixture();f.open(71,4);assertNotEquals(0,f.service.accept(f.request(72,4),Collections.emptySet()).getStatus());assertEquals(71,f.service.session()); }
    @Test public void acceptedSchedulingRespondsBeforePlaybackThenStop() { Fixture f=new Fixture();f.open(71,4);f.command(START);assertTrue(f.sent.isEmpty());f.accepted.run();assertArrayEquals(SapFindMyPhoneCodec.frame("{\"msgId\":\"fmp-startalert-rsp\",\"result\":0,\"reason\":0}"),f.last());f.started.run();assertEquals(1,f.sent.size());f.command(STOP);assertFalse(f.active);assertEquals(1,f.stops);assertArrayEquals(SapFindMyPhoneCodec.stopResponse(),f.last()); }
    @Test public void duplicatesDoNotLaunchTwiceOrExtendDeadline() { Fixture f=new Fixture();f.open(71,4);f.command(START);f.command(START);f.started.run();f.advance(90000);f.command(START);assertEquals(1,f.starts);f.advance(90000);assertFalse(f.active);assertArrayEquals(SapFindMyPhoneCodec.stopResponse(),f.last()); }
    @Test public void duplicateStopIsIdempotentAndAcknowledged() { Fixture f=new Fixture();f.open(71,4);f.command(STOP);f.command(STOP);assertEquals(0,f.starts);assertEquals(0,f.stops);assertEquals(2,f.sent.size()); }
    @Test public void stopBeforePlaybackInvalidatesLateSuccess() { Fixture f=new Fixture();f.open(71,4);f.command(START);Runnable stale=f.started;f.command(STOP);int count=f.sent.size();stale.run();assertEquals(count,f.sent.size());assertFalse(f.active); }
    @Test public void oldCallbackCannotAffectNextStart() { Fixture f=new Fixture();f.open(71,4);f.command(START);Consumer<Boolean> old=f.finished;f.command(STOP);f.command(START);f.started.run();int count=f.sent.size();old.accept(false);assertEquals(count,f.sent.size());assertTrue(f.active); }
    @Test public void localStopNotifiesWatchAndLateFoundAckCannotStopNextStart() { Fixture f=new Fixture();f.open(71,4);f.command(START);f.started.run();f.finished.accept(false);assertArrayEquals(SapFindMyPhoneCodec.foundRequest(),f.last());f.command(START);f.started.run();f.command("{\"msgId\":\"fmp-founddevice-rsp\"}");assertTrue(f.active); }
    @Test public void unavailableAlertReturnsFailure() { Fixture f=new Fixture();f.available=false;f.open(71,4);f.command(START);assertArrayEquals(SapFindMyPhoneCodec.startResponse(false),f.last()); }
    @Test public void launchWatchdogStopsPendingOperation() { Fixture f=new Fixture();f.open(71,4);f.command(START);f.advance(10000);assertFalse(f.active);assertArrayEquals(SapFindMyPhoneCodec.startResponse(false),f.last()); }
    @Test public void sessionTerminationStopsAndAllowsReconnect() { Fixture f=new Fixture();f.open(71,4);f.command(START);assertEquals(0,f.service.terminate(new SapServiceConnectionTermination(0x2345,0x6789,"/system/fmp")));assertFalse(f.active);f.open(83,4);f.command(START);assertEquals(2,f.starts); }
    @Test public void wrongTerminationCannotStopRing() { Fixture f=new Fixture();f.open(71,4);f.command(START);assertNotEquals(0,f.service.terminate(new SapServiceConnectionTermination(0x2346,0x6789,"/system/fmp")));assertTrue(f.active); }
    @Test public void linkLossCancelsWithinMonitorInterval() { Fixture f=new Fixture();f.open(71,4);f.command(START);f.started.run();f.connected=false;f.advance(1000);assertFalse(f.active);assertEquals(-1,f.service.session()); }
    @Test public void disposeCancelsTimersAndRejectsFurtherCreate() { Fixture f=new Fixture();f.open(71,4);f.command(START);f.service.dispose();f.advance(200000);assertFalse(f.active);assertTrue(f.sent.isEmpty());assertNotEquals(0,f.service.accept(f.request(83,4),Collections.emptySet()).getStatus()); }
    @Test public void staleGenerationOrSessionDoesNotStart() { Fixture f=new Fixture();f.open(71,4);long generation=f.service.generation();f.service.close();f.open(83,4);f.service.onRecord(new SapPostAuthRecord(new SapTransportHeader(0,0,83,0),SapFindMyPhoneCodec.frame(START)),generation);f.service.onRecord(new SapPostAuthRecord(new SapTransportHeader(0,0,71,0),SapFindMyPhoneCodec.frame(START)),f.service.generation());assertEquals(0,f.starts); }
    @Test public void unknownAndMalformedCannotRing() { Fixture f=new Fixture();f.open(71,4);for(String json:new String[]{"{\"msgId\":\"mgr_host_location_req\"}","{}","[]","{\"msgId\":\"fmp-startalert-req\"}"})f.command(json);assertEquals(0,f.starts);assertTrue(f.sent.isEmpty()); }
    @Test public void reliableDuplicateIsAckedWithoutSecondStart() { Fixture f=new Fixture();f.open(71,5);f.reliable(1,START);f.reliable(1,START);assertEquals(1,f.starts);assertArrayEquals(new byte[]{1,0,1},f.last());assertEquals(1,f.sent.get(0).getHeader().getFrameType()); }
    @Test public void reliableOutOfOrderWaitsAndDrainsInOrder() { Fixture f=new Fixture();f.open(71,5);f.reliable(2,STOP);assertEquals(0,f.starts);assertEquals(2,f.last()[0]);f.reliable(1,START);assertEquals(1,f.starts);assertFalse(f.active);assertArrayEquals(new byte[]{1,0,2},f.last()); }
    @Test public void reliableMalformedCommandStillAdvancesTransport() { Fixture f=new Fixture();f.open(71,5);f.reliable(1,"{}");f.reliable(2,START);assertEquals(1,f.starts);assertArrayEquals(new byte[]{1,0,2},f.last()); }
    @Test public void reliableResponsesUseIndependentSequenceAndNakReplaysExactBytes() { Fixture f=new Fixture();f.open(71,5);f.reliable(1,START);f.started.run();byte[] response=f.last();assertEquals(0,response[0]);assertEquals(1,response[1]);f.record(new byte[]{2,1,0,1,0,1},1);assertArrayEquals(response,f.last());f.record(new byte[]{1,0,1},1);int count=f.sent.size();f.advance(3000);assertEquals(count,f.sent.size()); }
    @Test public void missingReliableAckIsBoundedAndStopsAlert() { Fixture f=new Fixture();f.open(71,5);f.reliable(1,START);f.started.run();f.advance(8000);assertFalse(f.active);assertEquals(-1,f.service.session()); }
    @Test public void reconnectResetsReliableSequences() { Fixture f=new Fixture();f.open(71,5);f.reliable(1,START);f.service.close();f.open(83,5);f.reliable(1,START);assertEquals(2,f.starts); }
    @Test public void acceptedDuplicateBeforePlaybackDoesNotLaunchAgain() {
        Fixture f=new Fixture();f.open(71,4);f.command(START);f.accepted.run();f.command(START);
        assertEquals(1,f.starts);assertEquals(2,f.sent.size());assertArrayEquals(SapFindMyPhoneCodec.startResponse(true),f.last());
        f.started.run();assertEquals(2,f.sent.size());
    }
    @Test public void dispatchFailureBeforeAcceptanceRetainsFailureResponse() {
        Fixture f=new Fixture();f.open(71,4);f.command(START);f.finished.accept(true);
        assertArrayEquals(SapFindMyPhoneCodec.frame("{\"msgId\":\"fmp-startalert-rsp\",\"result\":1,\"reason\":-1}"),f.last());
        f.accepted.run();f.started.run();assertEquals(1,f.sent.size());assertFalse(f.active);
    }
    @Test public void stopAfterAcceptanceInvalidatesBothLateCallbacks() {
        Fixture f=new Fixture();f.open(71,4);f.command(START);f.accepted.run();f.command(STOP);
        int count=f.sent.size();f.accepted.run();f.started.run();f.finished.accept(false);
        assertEquals(count,f.sent.size());assertFalse(f.active);assertArrayEquals(SapFindMyPhoneCodec.stopResponse(),f.last());
    }
    @Test public void watchdogAfterAcceptanceStopsWithoutContradictoryStartFailure() {
        Fixture f=new Fixture();f.open(71,4);f.command(START);f.accepted.run();f.advance(10000);
        assertEquals(2,f.sent.size());assertArrayEquals(SapFindMyPhoneCodec.startResponse(true),f.sent.get(0).getBody());
        assertArrayEquals(SapFindMyPhoneCodec.stopResponse(),f.last());assertFalse(f.active);
    }
    @Test public void playbackFailureAfterAcceptanceStopsWithoutSecondStartResponse() {
        Fixture f=new Fixture();f.open(71,4);f.command(START);f.accepted.run();f.finished.accept(true);
        assertEquals(2,f.sent.size());assertArrayEquals(SapFindMyPhoneCodec.stopResponse(),f.last());assertFalse(f.active);
    }
    @Test public void disconnectOrDisposeAfterAcceptanceInvalidatesPendingActivity() {
        for(boolean dispose:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.open(71,4);f.command(START);f.accepted.run();
            if(dispose)f.service.dispose();else { f.connected=false;f.advance(1000); }
            f.started.run();f.accepted.run();f.finished.accept(true);
            assertEquals(1,f.sent.size());assertFalse(f.active);assertEquals(-1,f.service.session());
        }
    }

}
