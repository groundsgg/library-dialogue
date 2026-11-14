package gg.grounds.dialogue.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ReviewRegressionsTest {
  static TestRig choiceRig(ConditionProvider provider) {
    var d =
        DialogueBuilder.dialogue("review:choices")
            .node(
                "start",
                new Message(
                    "npc",
                    "hello",
                    null,
                    List.of(
                        new Choice(
                            "offer", "c", "end", true, new Condition("review:ok", Map.of(), false)),
                        new Choice("leave", "bye", "end", false, null)),
                    null))
            .node("end", new End("completed"))
            .build();
    var r =
        new TestRig(
            d,
            new HostCatalog(
                "v1",
                Set.of("npc"),
                Map.of("review:ok", new HostCatalog.ProviderSpec(Map.of(), false)),
                Map.of(),
                Set.of(),
                Set.of(),
                false));
    r.bindings =
        new HostBindings(
            "b",
            Map.of("review:ok", provider),
            Map.of(),
            null,
            r.bindings.texts(),
            r.bindings.renderer(),
            null,
            Set.of());
    return r;
  }

  @Test
  void c1CancelledBusyFrameCannotDispatchActionOrOutcome() {
    for (boolean action : List.of(true, false)) {
      var d =
          DialogueBuilder.dialogue("review:cancel")
              .node(
                  "start",
                  action
                      ? new Action("review:mutate", Map.of(), "end", "end", "end", "end")
                      : new Outcome("review:milestone", Delivery.LOCAL, "end", "end", "end"))
              .node("end", new End("completed"))
              .build();
      var catalog =
          new HostCatalog(
              "v1",
              Set.of(),
              Map.of(),
              Map.of("review:mutate", new HostCatalog.ProviderSpec(Map.of(), true)),
              Set.of(),
              Set.of(),
              false);
      var r = new TestRig(d, catalog);
      var calls = new AtomicInteger();
      ActionHandler handler =
          new ActionHandler() {
            public CompletionStage<ActionResult> execute(ActionRequest request) {
              calls.incrementAndGet();
              return CompletableFuture.completedFuture(ActionResult.SUCCESS);
            }

            public CompletionStage<ActionStatus> reconcile(ActionRef ref) {
              return CompletableFuture.completedFuture(ActionStatus.SUCCEEDED);
            }
          };
      var surface = new DialogueLifecycleTest.Surface();
      surface.onReplace = () -> r.cancellation.cancel(DialogueEndReason.DISCONNECTED);
      r.bindings =
          new HostBindings(
              "cancel",
              Map.of(),
              Map.of("review:mutate", handler),
              event -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(OutcomeSink.OutcomeResult.ACKNOWLEDGED);
              },
              r.bindings.texts(),
              req -> CompletableFuture.completedFuture(surface),
              null,
              Set.of());
      var start = r.runtime.start(r.request("cancel"));
      r.scheduler.drain();
      assertInstanceOf(DialogueStartResult.Rejected.class, TestRig.done(start));
      assertEquals(0, calls.get(), "No new effect after cancellation won dispatch admission");
    }
  }

  @Test
  void i2SurfaceSwitchDuringConfirmRestoresActionableChoices() {
    var calls = new AtomicInteger();
    var pending = new CompletableFuture<ConditionProvider.ConditionResult>();
    var r =
        choiceRig(
            req ->
                calls.getAndIncrement() == 0
                    ? CompletableFuture.completedFuture(ConditionProvider.ConditionResult.TRUE)
                    : pending);
    var h = r.start();
    h.submit(DialogueInputTest.confirm(h, 1));
    r.scheduler.drain();
    var confirm = h.submit(DialogueInputTest.confirm(h, 2));
    r.scheduler.drain();
    var change =
        h.updatePreferences(
            new PresentationPreferences("npc", Duration.ofMillis(35), false, Map.of()));
    r.scheduler.drain();
    TestRig.done(change);
    pending.complete(ConditionProvider.ConditionResult.FALSE);
    r.scheduler.drain();
    assertEquals(InputResult.STALE, TestRig.done(confirm));
    assertEquals(DialogueView.State.WAITING_FOR_INPUT, h.snapshot().state());
    assertEquals("leave", h.snapshot().selectedChoiceId());
    var next = h.submit(DialogueInputTest.confirm(h, 3));
    r.scheduler.drain();
    assertEquals(InputResult.ACCEPTED, TestRig.done(next));
    assertEquals(DialogueEndReason.COMPLETED, TestRig.done(h.completion()).reason());
  }

  @Test
  void i3BatchedConditionDeadlinesKeepUnconditionalChoiceOnEntry() {
    var r = choiceRig(req -> new CompletableFuture<>());
    var f = r.runtime.start(r.request("batch"));
    r.scheduler.drain();
    r.scheduler.advanceBatched(Duration.ofSeconds(2));
    var h = ((DialogueStartResult.Started) TestRig.done(f)).handle();
    assertEquals(
        List.of("offer", "leave"),
        h.snapshot().choices().stream().map(DialogueView.ChoiceView::id).toList());
    assertEquals("leave", h.snapshot().selectedChoiceId());
    assertEquals(
        DialogueView.Availability.UNAVAILABLE, h.snapshot().choices().getFirst().availability());
  }

  @Test
  void i3BatchedConditionDeadlinesKeepUnconditionalChoiceOnConfirm() {
    var calls = new AtomicInteger();
    var r =
        choiceRig(
            req ->
                calls.getAndIncrement() == 0
                    ? CompletableFuture.completedFuture(ConditionProvider.ConditionResult.TRUE)
                    : new CompletableFuture<>());
    var h = r.start();
    h.submit(DialogueInputTest.confirm(h, 1));
    r.scheduler.drain();
    var f = h.submit(DialogueInputTest.confirm(h, 2));
    r.scheduler.drain();
    r.scheduler.advanceBatched(Duration.ofSeconds(2));
    assertEquals(InputResult.STALE, TestRig.done(f));
    assertEquals("leave", h.snapshot().selectedChoiceId());
    assertEquals(DialogueView.State.WAITING_FOR_INPUT, h.snapshot().state());
  }

  @Test
  void i6ImmediateRevealIsCommittedOnlyAfterReconfigure() {
    var r = new TestRig();
    var gate = new CompletableFuture<Void>();
    var s =
        new DialogueLifecycleTest.Surface() {
          public CompletionStage<Void> reconfigure(PresentationPreferences p) {
            return gate;
          }
        };
    DialogueLifecycleTest.renderer(r, req -> CompletableFuture.completedFuture(s), null);
    var h = r.start();
    var f = h.updatePreferences(new PresentationPreferences("hud", Duration.ZERO, false, Map.of()));
    r.scheduler.drain();
    r.scheduler.advance(Duration.ofMillis(35));
    assertNotEquals(DialogueView.State.CLOSED, h.snapshot().state());
    gate.complete(null);
    r.scheduler.drain();
    TestRig.done(f);
    assertEquals(DialogueView.State.WAITING_FOR_INPUT, h.snapshot().state());
    assertEquals(h.snapshot().totalGraphemes(), h.snapshot().revealedGraphemes());
  }

  @Test
  void i8StartAndEndObserversCannotMutateLifecycle() {
    var r = new TestRig();
    var request = r.request("observers");
    var stage = r.runtime.start(request);
    stage.toCompletableFuture().cancel(false);
    assertSame(stage, r.runtime.start(request));
    r.scheduler.drain();
    var h = ((DialogueStartResult.Started) TestRig.done(stage)).handle();
    var observer = h.completion().toCompletableFuture();
    observer.complete(
        new DialogueEnd(h.snapshot().sessionId(), DialogueEndReason.COMPLETED, List.of()));
    assertFalse(h.completion().toCompletableFuture().isDone());
    var cancelled = h.completion().toCompletableFuture();
    cancelled.cancel(false);
    var stop = h.stop(DialogueEndReason.DISCONNECTED);
    r.scheduler.drain();
    assertEquals(DialogueEndReason.DISCONNECTED, TestRig.done(stop).reason());
    assertEquals(DialogueView.State.CLOSED, h.snapshot().state());
  }

  @Test
  void i9AuthorGenerationAndPolicyParticipateInDeduplication() {
    var r = new TestRig();
    var q = r.request("pin");
    var old = r.runtime.start(q);
    var catalog = r.content.catalog();
    var v2 =
        new HostCatalog(
            "v2",
            catalog.speakers(),
            catalog.conditions(),
            catalog.actions(),
            catalog.gestures(),
            catalog.capabilities(),
            catalog.durableOutcomes());
    var content =
        new DialogueValidator()
            .validate(r.content.definition(), r.content.texts(), v2, r.content.limits())
            .validated();
    assertEquals(r.content.contentPin(), content.contentPin());
    var other =
        new DialogueStart(
            q.requestId(),
            q.audienceId(),
            q.hostGeneration(),
            content,
            q.bindings(),
            q.context(),
            q.locale(),
            q.preferences(),
            q.cancellation(),
            0);
    assertEquals(
        "START_REQUEST_CONFLICT",
        ((DialogueStartResult.Rejected) TestRig.done(r.runtime.start(other))).code());
    var l = r.content.limits();
    var stricter =
        new ValidationLimits(
            l.definitionBytes(),
            l.nodes(),
            l.choices(),
            16,
            l.depth(),
            l.idCodepoints(),
            l.textCodepoints(),
            l.locales(),
            l.textFileBytes(),
            l.textPackageBytes(),
            l.arguments());
    var c2 =
        new DialogueValidator()
            .validate(r.content.definition(), r.content.texts(), catalog, stricter)
            .validated();
    var request2 =
        new DialogueStart(
            q.requestId(),
            q.audienceId(),
            q.hostGeneration(),
            c2,
            q.bindings(),
            q.context(),
            q.locale(),
            q.preferences(),
            q.cancellation(),
            0);
    assertEquals(
        "START_REQUEST_CONFLICT",
        ((DialogueStartResult.Rejected) TestRig.done(r.runtime.start(request2))).code());
    r.scheduler.drain();
  }

  @Test
  void i12OptionalSpeakerLabelUsesOnlyEffectiveFallbackChain() {
    var r = new TestRig();
    var texts =
        new TextCatalog(
            Map.of(
                "en",
                Map.of("hello", "Hello", "bye", "Bye"),
                "de",
                Map.of("hello", "Hallo", "bye", "Tschüss", "speaker.npc", "Mechaniker")));
    var validated =
        new DialogueValidator()
            .validate(r.content.definition(), texts, r.content.catalog(), r.content.limits())
            .validated();
    var q = r.request("labels");
    var bindings =
        new HostBindings(
            "labels",
            Map.of(),
            Map.of(),
            null,
            TextResolver.catalogue(texts, "en"),
            r.bindings.renderer(),
            null,
            Set.of());
    var f =
        r.runtime.start(
            new DialogueStart(
                q.requestId(),
                q.audienceId(),
                q.hostGeneration(),
                validated,
                bindings,
                Map.of(),
                q.locale(),
                q.preferences(),
                q.cancellation(),
                0));
    r.scheduler.drain();
    var h = ((DialogueStartResult.Started) TestRig.done(f)).handle();
    assertEquals("npc", h.snapshot().speaker());
    h.refreshLocale(new LocaleContext("de", 1, "en"));
    r.scheduler.drain();
    assertEquals("Mechaniker", h.snapshot().speaker());
    h.refreshLocale(new LocaleContext("en", 2, "en"));
    r.scheduler.drain();
    assertEquals("npc", h.snapshot().speaker());
  }
}
