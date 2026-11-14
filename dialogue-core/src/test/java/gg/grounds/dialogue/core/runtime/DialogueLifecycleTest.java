package gg.grounds.dialogue.core.runtime;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import gg.grounds.dialogue.core.port.*;
class DialogueLifecycleTest {
 static class Surface implements DialogueRenderer.PresentationHandle {int closes;boolean fail;Runnable onReplace=()->{};public CompletionStage<Void> replace(DialogueView v){onReplace.run();return CompletableFuture.completedFuture(null);}public CompletionStage<Void> reconfigure(PresentationPreferences p){return CompletableFuture.completedFuture(null);}public CompletionStage<Void> closeAsync(){closes++;return fail?CompletableFuture.failedFuture(new IllegalStateException("cleanup failed")):CompletableFuture.completedFuture(null);}}
 static void renderer(TestRig r,DialogueRenderer renderer,ActorPort actors){r.bindings=new HostBindings("custom",Map.of(),Map.of(),null,r.bindings.texts(),renderer,actors,Set.of());}
 @Test void cancelDuringLatePrepare(){
  var r=new TestRig();var late=new CompletableFuture<DialogueRenderer.PresentationHandle>();renderer(r,req->late,null);var f=r.runtime.start(r.request("late"));r.scheduler.drain();assertFalse(f.toCompletableFuture().isDone());
  r.cancellation.cancel(DialogueEndReason.DISCONNECTED);r.scheduler.drain();assertFalse(f.toCompletableFuture().isDone(),"cleanup waits for registered acquisition");
  var s=new Surface();late.complete(s);r.scheduler.drain();assertEquals(1,s.closes);assertInstanceOf(DialogueStartResult.Rejected.class,TestRig.done(f));
 }
 @Test void synchronousFailureAndReentrantCancellationDoNotHang(){
  var r=new TestRig();renderer(r,req->{throw new IllegalStateException("prepare");},null);var f=r.runtime.start(r.request("sync"));r.scheduler.drain();assertInstanceOf(DialogueStartResult.Rejected.class,TestRig.done(f));
  var x=new TestRig();var s=new Surface();s.onReplace=()->x.cancellation.cancel(DialogueEndReason.CANCELLED);renderer(x,req->CompletableFuture.completedFuture(s),null);var start=x.runtime.start(x.request("reentrant"));x.scheduler.drain();assertInstanceOf(DialogueStartResult.Rejected.class,TestRig.done(start));assertEquals(1,s.closes);
 }
 @Test void cleanupFailureStillClosesOtherResources(){
  var r=new TestRig();var s=new Surface();s.fail=true;var actorCloses=new AtomicInteger();ActorPort actors=req->CompletableFuture.completedFuture(new ActorPort.ActorHandle(){public CompletionStage<Void> cue(ActorPort.ActorCue c){return CompletableFuture.completedFuture(null);}public CompletionStage<Void> closeAsync(){actorCloses.incrementAndGet();return CompletableFuture.completedFuture(null);}});renderer(r,req->CompletableFuture.completedFuture(s),actors);var h=r.start();var end=h.stop(DialogueEndReason.CANCELLED);r.scheduler.drain();assertTrue(end.toCompletableFuture().isCompletedExceptionally());assertEquals(1,actorCloses.get());assertEquals(1,s.closes);
 }
 @Test void uncooperativeCleanupDeadlineKeepsLateOwnership(){
  var r=new TestRig();var late=new CompletableFuture<DialogueRenderer.PresentationHandle>();renderer(r,req->late,null);var f=r.runtime.start(r.request("never"));r.scheduler.drain();r.cancellation.cancel(DialogueEndReason.CANCELLED);r.scheduler.drain();r.scheduler.advance(Duration.ofSeconds(30));assertInstanceOf(DialogueStartResult.Rejected.class,TestRig.done(f));var s=new Surface();late.complete(s);r.scheduler.drain();assertEquals(1,s.closes);
 }
 @Test void optionalActorFailureAndTimeoutDoNotRejectDialogue(){
  var r=new TestRig();renderer(r,req->CompletableFuture.completedFuture(new Surface()),req->CompletableFuture.failedFuture(new IllegalStateException("optional")));assertNotNull(r.start());
  var x=new TestRig();var late=new CompletableFuture<ActorPort.ActorHandle>();renderer(x,req->CompletableFuture.completedFuture(new Surface()),req->late);var f=x.runtime.start(x.request("optional"));x.scheduler.drain();x.scheduler.advance(Duration.ofSeconds(5));assertInstanceOf(DialogueStartResult.Started.class,TestRig.done(f));
 }
}
