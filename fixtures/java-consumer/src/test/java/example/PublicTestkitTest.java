package example;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.presentation.*;
import gg.grounds.dialogue.testkit.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PublicTestkitTest {
  static <T> T done(CompletionStage<T> stage) {
    assertTrue(stage.toCompletableFuture().isDone());
    return stage.toCompletableFuture().join();
  }

  @Test
  void realRuntimeUsesPublishedTestkitWithLocaleStaleInputAndLateAction() throws Exception {
    var clock = new ManualDialogueScheduler();
    var surface = new RecordingDialogueSurface();
    var outcomes = new RecordingOutcomeSink();
    var presenter =
        new DialoguePresenter(
            Map.of("chat", DialoguePresenter.SurfaceProfile.semantic(surface)), clock);
    var definition =
        DialogueBuilder.dialogue("consumer:testkit")
            .locales("en", "en", "de")
            .node("start", new Message("npc", "hello", null, List.of(), "action"))
            .node("action", new Action("test:accept", Map.of(), "after", "after", "after", "after"))
            .node("after", new Message("npc", "bye", null, List.of(), "end"))
            .node("end", new End("completed"))
            .build();
    var catalog =
        new HostCatalog(
            "public",
            Set.of("npc"),
            Map.of(),
            Map.of("test:accept", new HostCatalog.ProviderSpec(Map.of(), false)),
            Set.of(),
            Set.of(),
            false);
    var texts =
        new TextCatalog(
            Map.of(
                "en",
                Map.of("hello", "Hello", "bye", "Bye"),
                "de",
                Map.of("hello", "Hallo", "bye", "Tschüss")));
    var report =
        new DialogueValidator().validate(definition, texts, catalog, ValidationLimits.defaults());
    assertTrue(report.isValid(), report.problems().toString());
    var pending = new CompletableFuture<ActionHandler.ActionResult>();
    var late = new CompletableFuture<ActionHandler.ActionResult>();
    var responses =
        new ArrayDeque<CompletableFuture<ActionHandler.ActionResult>>(List.of(pending, late));
    int[] calls = {0};
    var action =
        new ActionHandler() {
          public CompletionStage<ActionResult> execute(ActionRequest req) {
            calls[0]++;
            return responses.removeFirst();
          }

          public CompletionStage<ActionStatus> reconcile(ActionRef ref) {
            return CompletableFuture.completedFuture(ActionStatus.PENDING);
          }
        };
    var bindings =
        new HostBindings(
            "public",
            Map.of(),
            Map.of("test:accept", action),
            outcomes,
            TextResolver.catalogue(texts, "en"),
            presenter,
            null,
            Set.of());
    var runtime = DialogueRuntime.create(RuntimeOptions.defaults(clock));
    var started =
        runtime.start(
            new DialogueStart(
                "java",
                "player",
                1,
                report.validated(),
                bindings,
                Map.of(),
                new LocaleContext("en", 0, "en"),
                new PresentationPreferences("chat", Duration.ZERO, true, Map.of()),
                new CancellationToken(),
                0));
    clock.runUntilIdle();
    var h = ((DialogueStartResult.Started) done(started)).handle();
    var before = h.snapshot();
    h.refreshLocale(new LocaleContext("de", 1, "en"));
    clock.runUntilIdle();
    assertEquals("Hallo", h.snapshot().text());
    var stale =
        h.submit(
            new DialogueInput.Confirm(
                new DialogueInput.InputStamp(before.sessionId(), before.renderRevision(), 1),
                null));
    clock.runUntilIdle();
    assertEquals(InputResult.STALE, done(stale));
    assertEquals(0, calls[0]);
    var current = h.snapshot();
    h.submit(
        new DialogueInput.Confirm(
            new DialogueInput.InputStamp(current.sessionId(), current.renderRevision(), 2), null));
    clock.runUntilIdle();
    assertEquals(1, calls[0]);
    assertEquals(DialogueView.State.WAITING_FOR_PROVIDER, h.snapshot().state());
    h.refreshLocale(new LocaleContext("en", 2, "en"));
    clock.runUntilIdle();
    assertEquals(1, calls[0]);
    pending.complete(ActionHandler.ActionResult.SUCCESS);
    clock.runUntilIdle();
    assertEquals("Bye", h.snapshot().text());
    assertEquals(1, calls[0]);
    var closing = h.stop(DialogueEndReason.CANCELLED);
    clock.runUntilIdle();
    done(closing);
    assertTrue(surface.snapshots().getFirst().closed());
    var second =
        runtime.start(
            new DialogueStart(
                "java-late",
                "other",
                1,
                report.validated(),
                bindings,
                Map.of(),
                new LocaleContext("en", 0, "en"),
                new PresentationPreferences("chat", Duration.ZERO, true, Map.of()),
                new CancellationToken(),
                0));
    clock.runUntilIdle();
    var h2 = ((DialogueStartResult.Started) done(second)).handle();
    var v2 = h2.snapshot();
    h2.submit(
        new DialogueInput.Confirm(
            new DialogueInput.InputStamp(v2.sessionId(), v2.renderRevision(), 1), null));
    clock.runUntilIdle();
    assertEquals(2, calls[0]);
    h2.stop(DialogueEndReason.CANCELLED);
    clock.runUntilIdle();
    int frames = surface.snapshots().getLast().frames().size();
    late.complete(ActionHandler.ActionResult.SUCCESS);
    clock.advanceBy(Duration.ofSeconds(10));
    assertEquals(frames, surface.snapshots().getLast().frames().size());
    assertTrue(surface.snapshots().getLast().closed());
    assertEquals(2, calls[0]);
    assertTrue(outcomes.events().isEmpty());
    runtime.closeAsync();
    clock.runUntilIdle();
    StringBuilder origins = new StringBuilder();
    for (Class<?> type :
        List.of(DialogueRuntime.class, DialoguePresenter.class, ManualDialogueScheduler.class)) {
      var uri = type.getProtectionDomain().getCodeSource().getLocation().toURI();
      assertTrue(uri.getPath().endsWith(".jar"));
      origins
          .append("MAVEN_ORIGIN ")
          .append(type.getName())
          .append(' ')
          .append(uri.toASCIIString())
          .append('\n');
    }
    Path log = Path.of("build/test-origins/java.log");
    Files.createDirectories(log.getParent());
    Files.writeString(log, origins);
  }
}
