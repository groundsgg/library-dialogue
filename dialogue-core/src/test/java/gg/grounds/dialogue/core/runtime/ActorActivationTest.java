package gg.grounds.dialogue.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.text.LocaleContext;
import gg.grounds.dialogue.core.validation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ActorActivationTest {
  static class Actor implements ActorPort.ActorHandle {
    final List<String> calls;
    final CompletableFuture<Void> activation = new CompletableFuture<>();
    final CompletableFuture<Void> cleanup = new CompletableFuture<>();
    boolean throwsOnActivation;

    Actor(List<String> calls) {
      this.calls = calls;
    }

    public CompletionStage<Void> activate() {
      calls.add("activate");
      if (throwsOnActivation) throw new IllegalStateException("activation");
      return activation;
    }

    public CompletionStage<Void> cue(ActorPort.ActorCue cue) {
      calls.add("cue");
      return CompletableFuture.completedFuture(null);
    }

    public CompletionStage<Void> closeAsync() {
      calls.add("close");
      return cleanup;
    }
  }

  static TestRig rig(Actor actor, boolean required, boolean action) {
    var d =
        DialogueBuilder.dialogue("test:activation")
            .capabilities(new Capabilities(required ? Set.of("actor.gesture") : Set.of(), Set.of()))
            .node(
                "start",
                action
                    ? new Action(
                        "test:action", Map.of(), "message", "message", "message", "message")
                    : new Message("npc", "hello", "demo:wave", List.of(), "end"))
            .node("message", new Message("npc", "hello", "demo:wave", List.of(), "end"))
            .node("end", new End("completed"))
            .build();
    var catalog =
        new HostCatalog(
            "v1",
            Set.of("npc"),
            Map.of(),
            Map.of("test:action", new HostCatalog.ProviderSpec(Map.of(), false)),
            Set.of("demo:wave"),
            Set.of("actor.gesture"),
            false);
    var r = new TestRig(d, catalog);
    var old = r.bindings;
    ActionHandler handler =
        new ActionHandler() {
          public CompletionStage<ActionResult> execute(ActionRequest request) {
            actor.calls.add("execute");
            return CompletableFuture.completedFuture(ActionResult.SUCCESS);
          }

          public CompletionStage<ActionStatus> reconcile(ActionRef ref) {
            return CompletableFuture.completedFuture(ActionStatus.SUCCEEDED);
          }
        };
    r.bindings =
        new HostBindings(
            "activation",
            Map.of(),
            Map.of("test:action", handler),
            null,
            old.texts(),
            request ->
                CompletableFuture.completedFuture(
                    new DialogueRenderer.PresentationHandle() {
                      public CompletionStage<Void> replace(DialogueView v) {
                        actor.calls.add("draw");
                        r.frames.add(v);
                        return CompletableFuture.completedFuture(null);
                      }

                      public CompletionStage<Void> reconfigure(PresentationPreferences p) {
                        return CompletableFuture.completedFuture(null);
                      }

                      public CompletionStage<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                      }
                    }),
            request -> {
              actor.calls.add("prepare");
              return CompletableFuture.completedFuture(actor);
            },
            Set.of("actor.gesture"));
    return r;
  }

  @Test
  void activationPrecedesCueAndDispatch() {
    var calls = new ArrayList<String>();
    var actor = new Actor(calls);
    var r = rig(actor, false, false);
    var start = r.runtime.start(r.request("activation"));
    r.scheduler.drain();
    assertFalse(start.toCompletableFuture().isDone());
    assertEquals(List.of("prepare", "activate"), calls);
    actor.activation.complete(null);
    r.scheduler.drain();
    var h = ((DialogueStartResult.Started) TestRig.done(start)).handle();
    assertEquals(List.of("prepare", "activate", "cue", "draw"), calls);
    actor.cleanup.complete(null);
    h.stop(DialogueEndReason.CANCELLED);
    r.scheduler.drain();
  }

  @Test
  void noActionDispatchBeforeActivation() {
    var actor = new Actor(new ArrayList<>());
    var r = rig(actor, false, true);
    var start = r.runtime.start(r.request("action"));
    r.scheduler.drain();
    assertFalse(actor.calls.contains("execute"));
    actor.activation.complete(null);
    r.scheduler.drain();
    assertTrue(actor.calls.indexOf("activate") < actor.calls.indexOf("execute"));
    var h = ((DialogueStartResult.Started) TestRig.done(start)).handle();
    actor.cleanup.complete(null);
    h.stop(DialogueEndReason.CANCELLED);
    r.scheduler.drain();
  }

  @Test
  void cancellationDuringActivationOwnsLateCleanup() {
    var actor = new Actor(new ArrayList<>());
    var r = rig(actor, false, false);
    var start = r.runtime.start(r.request("cancel"));
    r.scheduler.drain();
    r.cancellation.cancel(DialogueEndReason.DISCONNECTED);
    r.scheduler.drain();
    assertFalse(start.toCompletableFuture().isDone());
    actor.activation.complete(null);
    r.scheduler.drain();
    assertTrue(r.frames.isEmpty());
    actor.cleanup.complete(null);
    r.scheduler.drain();
    assertEquals("DISCONNECTED", ((DialogueStartResult.Rejected) TestRig.done(start)).code());
    assertEquals(1, Collections.frequency(actor.calls, "close"));
  }

  @Test
  void optionalFailureContinuesOnlyAfterCleanup() {
    var actor = new Actor(new ArrayList<>());
    var r = rig(actor, false, false);
    var start = r.runtime.start(r.request("optional"));
    r.scheduler.drain();
    actor.activation.completeExceptionally(new IllegalStateException("activation"));
    r.scheduler.drain();
    assertFalse(start.toCompletableFuture().isDone());
    assertTrue(r.frames.isEmpty());
    actor.cleanup.complete(null);
    r.scheduler.drain();
    var h = ((DialogueStartResult.Started) TestRig.done(start)).handle();
    assertFalse(actor.calls.contains("cue"));
    h.stop(DialogueEndReason.CANCELLED);
    r.scheduler.drain();
    assertEquals(1, Collections.frequency(actor.calls, "close"));
  }

  @Test
  void optionalCleanupFailureRejectsRatherThanHidingFailure() {
    var actor = new Actor(new ArrayList<>());
    var r = rig(actor, false, false);
    var start = r.runtime.start(r.request("cleanup"));
    r.scheduler.drain();
    actor.activation.completeExceptionally(new IllegalStateException("activation"));
    actor.cleanup.completeExceptionally(new IllegalStateException("cleanup"));
    r.scheduler.drain();
    assertEquals("CLEANUP_FAILED", ((DialogueStartResult.Rejected) TestRig.done(start)).code());
    assertTrue(r.frames.isEmpty());
  }

  @Test
  void requiredFailureRejects() {
    var actor = new Actor(new ArrayList<>());
    var r = rig(actor, true, false);
    var start = r.runtime.start(r.request("required"));
    r.scheduler.drain();
    actor.activation.completeExceptionally(new IllegalStateException("activation"));
    actor.cleanup.complete(null);
    r.scheduler.drain();
    assertEquals(
        "ACTOR_ACTIVATION_FAILED", ((DialogueStartResult.Rejected) TestRig.done(start)).code());
    assertTrue(r.frames.isEmpty());
  }

  @Test
  void optionalActivationTimeoutWaitsForCleanup() {
    var actor = new Actor(new ArrayList<>());
    var r = rig(actor, false, false);
    var start = r.runtime.start(r.request("timeout"));
    r.scheduler.drain();
    r.scheduler.advance(Duration.ofSeconds(5));
    assertFalse(start.toCompletableFuture().isDone());
    assertTrue(r.frames.isEmpty());
    actor.cleanup.complete(null);
    r.scheduler.drain();
    assertInstanceOf(DialogueStartResult.Started.class, TestRig.done(start));
  }

  @Test
  void synchronousActivationFailureIsBounded() {
    var actor = new Actor(new ArrayList<>());
    actor.throwsOnActivation = true;
    actor.cleanup.complete(null);
    var r = rig(actor, true, false);
    var start = r.runtime.start(r.request("throw"));
    r.scheduler.drain();
    assertEquals(
        "ACTOR_ACTIVATION_FAILED", ((DialogueStartResult.Rejected) TestRig.done(start)).code());
  }

  @Test
  void localeAndSurfaceChangesNeverReactivate() {
    var actor = new Actor(new ArrayList<>());
    actor.activation.complete(null);
    actor.cleanup.complete(null);
    var r = rig(actor, false, false);
    var h = r.start();
    h.refreshLocale(new LocaleContext("de", 1, "en"));
    h.updatePreferences(new PresentationPreferences("npc", Duration.ZERO, false, Map.of()));
    r.scheduler.drain();
    assertEquals(1, Collections.frequency(actor.calls, "activate"));
    assertEquals(1, Collections.frequency(actor.calls, "cue"));
    h.stop(DialogueEndReason.CANCELLED);
    r.scheduler.drain();
  }
}
