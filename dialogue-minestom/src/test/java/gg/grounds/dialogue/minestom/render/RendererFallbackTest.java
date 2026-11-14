package gg.grounds.dialogue.minestom.render;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class RendererFallbackTest {
  static class Surface implements DialogueSurface {
    int writes, closes;
    boolean fail;
    Throwable openError, drawError;
    CompletableFuture<Void> gate = CompletableFuture.completedFuture(null);
    CompletableFuture<Void> cleanup = CompletableFuture.completedFuture(null);

    public CompletionStage<SurfaceHandle> open(SurfaceContext c) {
      if (openError != null) return CompletableFuture.failedFuture(openError);
      return CompletableFuture.completedFuture(
          new SurfaceHandle() {
            public CompletionStage<Void> replace(WidgetFrame f) {
              writes++;
              if (drawError != null) return CompletableFuture.failedFuture(drawError);
              return fail
                  ? CompletableFuture.failedFuture(
                      new DialoguePresenter.FallbackRequired("MISSING_PACK"))
                  : gate;
            }

            public CompletionStage<Void> closeAsync() {
              closes++;
              return cleanup;
            }
          });
    }
  }

  static <T> T done(Env env, CompletionStage<T> stage) {
    assertTrue(env.tickWhile(() -> !stage.toCompletableFuture().isDone(), Duration.ofSeconds(2)));
    return stage.toCompletableFuture().join();
  }

  static class Fixture {
    final MinestomDialogueScheduler clock;
    final Surface hud = new Surface(), chat = new Surface(), npc = new Surface();
    final SurfaceInputTargets targets = new SurfaceInputTargets();
    final List<PresentationStatus> statuses = new ArrayList<>();
    final DialogueRuntime core;
    final MinestomDialogueRenderer renderer;

    Fixture(Env env) {
      clock = new MinestomDialogueScheduler(env.createFlatInstance());
      core = DialogueRuntime.create(new RuntimeOptions(clock, budgets(), 65536, 256));
      renderer =
          new MinestomDialogueRenderer(
              Map.of(
                  "hud",
                  new DialoguePresenter.SurfaceProfile(
                      hud,
                      LayoutProfile.hud(),
                      DialogueTheme.neutral(),
                      RenderFixtures.ASSETS.metrics(),
                      false),
                  "npc",
                  new DialoguePresenter.SurfaceProfile(
                      npc,
                      LayoutProfile.npc(),
                      DialogueTheme.neutral(),
                      RenderFixtures.ASSETS.metrics(),
                      false),
                  "chat",
                  DialoguePresenter.SurfaceProfile.semantic(chat)),
              clock,
              DialoguePresenter.Controls.defaults(),
              UiLabelSource.defaults(),
              targets,
              statuses::add);
    }

    CompletionStage<DialogueStartResult> start(String text) {
      var catalog = new TextCatalog(Map.of("en", Map.of("line", text)));
      var d =
          DialogueBuilder.dialogue("test:fallback")
              .node("start", new Message("npc", "line", null, List.of(), "end"))
              .node("end", new End("completed"))
              .build();
      var content =
          new DialogueValidator()
              .validate(
                  d,
                  catalog,
                  new HostCatalog(
                      "v1", Set.of("npc"), Map.of(), Map.of(), Set.of(), Set.of(), false),
                  ValidationLimits.defaults())
              .validated();
      return core.start(
          new DialogueStart(
              "test",
              "player",
              1,
              content,
              new HostBindings(
                  "v1",
                  Map.of(),
                  Map.of(),
                  null,
                  TextResolver.catalogue(catalog, "en"),
                  renderer,
                  null,
                  Set.of()),
              Map.of(),
              new LocaleContext("en", 0, "en"),
              new PresentationPreferences("hud", Duration.ZERO, false, Map.of()),
              new CancellationToken(),
              0));
    }
  }

  static RuntimeOptions.Budgets budgets() {
    return new RuntimeOptions.Budgets(
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30));
  }

  @Test
  void firstFrameMissingGlyphStartsInChat(Env env) {
    var f = new Fixture(env);
    f.chat.gate = new CompletableFuture<>();
    var start = f.start("Hello 世界");
    assertTrue(env.tickWhile(() -> f.chat.writes == 0, Duration.ofSeconds(2)));
    assertFalse(start.toCompletableFuture().isDone());
    f.chat.gate.complete(null);
    var started = (DialogueStartResult.Started) done(env, start);
    assertEquals("chat", f.statuses.getLast().effectiveSurface());
    assertEquals("hud", f.statuses.getLast().requestedSurface());
    assertEquals(1, started.handle().snapshot().nodeVisitId());
    assertEquals(0, f.hud.writes);
    done(env, f.core.closeAsync());
  }

  @Test
  void missingPackAtPreparationUsesSemanticSurface(Env env) {
    var f = new Fixture(env);
    f.hud.openError = new DialoguePresenter.FallbackRequired("MISSING_PACK");
    assertInstanceOf(DialogueStartResult.Started.class, done(env, f.start("Hello")));
    assertEquals("chat", f.statuses.getLast().effectiveSurface());
    assertEquals(0, f.hud.writes);
    done(env, f.core.closeAsync());
  }

  @Test
  void transportFailureDuringPreferenceChangeIsFatal(Env env) {
    var f = new Fixture(env);
    var h = ((DialogueStartResult.Started) done(env, f.start("Hello"))).handle();
    f.npc.drawError = new IllegalStateException("uncertain transport");
    var change =
        h.updatePreferences(new PresentationPreferences("npc", Duration.ZERO, false, Map.of()));
    assertThrows(CompletionException.class, () -> done(env, change));
    assertTrue(
        env.tickWhile(() -> !h.completion().toCompletableFuture().isDone(), Duration.ofSeconds(2)));
    assertEquals(DialogueEndReason.FAILED, done(env, h.completion()).reason());
    done(env, f.core.closeAsync());
  }

  @Test
  void revokedPackInvalidatesOldTargets(Env env) {
    var f = new Fixture(env);
    var h = ((DialogueStartResult.Started) done(env, f.start("Hello"))).handle();
    f.targets.publish(RenderFixtures.frame("hud", "Hello", 5), 1, Map.of());
    f.hud.fail = true;
    done(env, h.refreshLocale(new LocaleContext("en", 1, "en")));
    assertEquals("chat", f.statuses.getLast().effectiveSurface());
    assertNull(f.targets.current());
    done(env, f.core.closeAsync());
  }

  @Test
  void unknownCleanupFailureRemainsFatal(Env env) {
    var f = new Fixture(env);
    f.hud.cleanup =
        CompletableFuture.failedFuture(new IllegalStateException("transport uncertain"));
    var result = done(env, f.start("Hello 世界"));
    assertInstanceOf(DialogueStartResult.Rejected.class, result);
    assertThrows(CompletionException.class, () -> done(env, f.core.closeAsync()));
  }

  @Test
  void newerPreferenceSupersedesFallback(Env env) {
    var f = new Fixture(env);
    var h = ((DialogueStartResult.Started) done(env, f.start("Hello"))).handle();
    f.chat.gate = new CompletableFuture<>();
    f.hud.fail = true;
    var refresh = h.refreshLocale(new LocaleContext("en", 1, "en"));
    assertTrue(env.tickWhile(() -> f.chat.writes == 0, Duration.ofSeconds(2)));
    var change =
        h.updatePreferences(new PresentationPreferences("npc", Duration.ZERO, false, Map.of()));
    done(env, change);
    f.chat.gate.complete(null);
    done(env, refresh);
    assertEquals("npc", f.statuses.getLast().effectiveSurface());
    assertEquals("npc", f.statuses.getLast().requestedSurface());
    done(env, f.core.closeAsync());
  }
}
