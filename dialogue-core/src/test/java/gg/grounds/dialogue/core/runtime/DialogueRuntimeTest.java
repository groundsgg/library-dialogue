package gg.grounds.dialogue.core.runtime;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class DialogueRuntimeTest {
 @Test void serializedStartAndInput(){
  var r=new TestRig();var request=r.request("same");var first=r.runtime.start(request);var second=r.runtime.start(request);assertSame(first,second);
  var conflict=new DialogueStart("same","other",1,r.content,r.bindings,java.util.Map.of(),request.locale(),request.preferences(),r.cancellation,0);
  assertEquals("START_REQUEST_CONFLICT",((DialogueStartResult.Rejected)TestRig.done(r.runtime.start(conflict))).code());
  r.scheduler.drain();var h=((DialogueStartResult.Started)TestRig.done(first)).handle();var id=h.snapshot().sessionId();
  var end=h.stop(DialogueEndReason.CANCELLED);r.scheduler.drain();assertEquals(DialogueEndReason.CANCELLED,TestRig.done(end).reason());assertEquals(id,h.snapshot().sessionId());assertEquals(DialogueView.State.CLOSED,h.snapshot().state());
 }
 @Test void cancelledPendingStartIsReachable(){var r=new TestRig();r.cancellation.cancel(DialogueEndReason.DISCONNECTED);var f=r.runtime.start(r.request("cancelled"));r.scheduler.drain();assertInstanceOf(DialogueStartResult.Rejected.class,TestRig.done(f));}
 @Test void closeRuntimeRejectsNewStarts(){var r=new TestRig();var f=r.runtime.closeAsync();r.scheduler.drain();TestRig.done(f);assertEquals("HOST_CLOSED",((DialogueStartResult.Rejected)TestRig.done(r.runtime.start(r.request("late")))).code());}
 @Test void equivalentContextMapsShareTheSameStart(){var r=new TestRig();var first=new java.util.LinkedHashMap<String,gg.grounds.dialogue.core.definition.ArgumentValue>();first.put("Aa",gg.grounds.dialogue.core.definition.ArgumentValue.of("x"));first.put("BB",gg.grounds.dialogue.core.definition.ArgumentValue.of("y"));var reversed=new java.util.LinkedHashMap<String,gg.grounds.dialogue.core.definition.ArgumentValue>();reversed.put("BB",gg.grounds.dialogue.core.definition.ArgumentValue.of("y"));reversed.put("Aa",gg.grounds.dialogue.core.definition.ArgumentValue.of("x"));var q=r.request("same-map");var a=new DialogueStart(q.requestId(),q.audienceId(),q.hostGeneration(),q.content(),q.bindings(),first,q.locale(),q.preferences(),q.cancellation(),0);var b=new DialogueStart(q.requestId(),q.audienceId(),q.hostGeneration(),q.content(),q.bindings(),reversed,q.locale(),q.preferences(),q.cancellation(),0);assertSame(r.runtime.start(a),r.runtime.start(b));r.scheduler.drain();}
}
