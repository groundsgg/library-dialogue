package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.validation.ContentPin;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

final class PresenterFixtures {
  static class Clock implements DialogueScheduler {
    long now;

    public void execute(Runnable r) {
      r.run();
    }

    public ScheduledTask schedule(Duration d, Runnable r) {
      return () -> {};
    }

    public long nanoTime() {
      return now;
    }
  }

  static final UUID SESSION = UUID.fromString("10000000-0000-0000-0000-000000000001");

  static DialogueView view(String text, int reveal, String selected, long revision) {
    var choices =
        List.of("c1", "c2", "c3", "c4", "c5", "c6", "leave").stream()
            .map(id -> new DialogueView.ChoiceView(id, id, DialogueView.Availability.AVAILABLE, ""))
            .toList();
    return new DialogueView(
        SESSION,
        new ContentPin("0".repeat(64)),
        "start",
        1,
        reveal < text.length()
            ? DialogueView.State.REVEALING
            : DialogueView.State.WAITING_FOR_INPUT,
        revision,
        1,
        "en",
        "Mechanic",
        text,
        choices,
        selected,
        gg.grounds.dialogue.core.text.Graphemes.split(text).size(),
        reveal,
        DialogueView.HintPhase.CHOOSE,
        "",
        null);
  }

  static class Surface implements DialogueSurface {
    int opens, closes;
    final List<WidgetFrame> frames = new ArrayList<>();
    CompletableFuture<SurfaceHandle> delayed;

    public CompletionStage<SurfaceHandle> open(SurfaceContext context) {
      opens++;
      var handle = handle();
      return delayed == null ? CompletableFuture.completedFuture(handle) : delayed;
    }

    SurfaceHandle handle() {
      return new SurfaceHandle() {
        public CompletionStage<Void> replace(WidgetFrame frame) {
          frames.add(frame);
          return CompletableFuture.completedFuture(null);
        }

        public CompletionStage<Void> closeAsync() {
          closes++;
          return CompletableFuture.completedFuture(null);
        }
      };
    }
  }

  static DialoguePresenter presenter(Surface hud, Surface npc) {
    return new DialoguePresenter(
        Map.of(
            "hud",
            new DialoguePresenter.SurfaceProfile(
                hud, LayoutProfile.hud(), DialogueTheme.neutral(), FontMetrics.defaults(), false),
            "npc",
            new DialoguePresenter.SurfaceProfile(
                npc, LayoutProfile.npc(), DialogueTheme.neutral(), FontMetrics.defaults(), false)),
        new Clock());
  }

  static DialogueRenderer.PresentationRequest request() {
    return new DialogueRenderer.PresentationRequest(
        SESSION,
        "player",
        1,
        PresentationPreferences.defaults(),
        new CancellationToken(),
        5_000_000_000L);
  }
}
