package example;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.json.DialogueCatalogLoader;
import gg.grounds.dialogue.presentation.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** A complete, deterministic Java consumer. No network, game server or real sleeps. */
public final class HeadlessDialogue {
  private HeadlessDialogue() {}

  public static final class StepClock implements DialogueScheduler {
    private long now, seq;
    private final Queue<Runnable> ready = new ArrayDeque<>();
    private final PriorityQueue<Job> jobs =
        new PriorityQueue<>(
            Comparator.comparingLong((Job j) -> j.at).thenComparingLong(j -> j.seq));

    private static final class Job {
      long at, seq;
      Runnable run;
      boolean cancelled;

      Job(long a, long s, Runnable r) {
        at = a;
        seq = s;
        run = r;
      }
    }

    public void execute(Runnable command) {
      ready.add(command);
    }

    public long nanoTime() {
      return now;
    }

    public ScheduledTask schedule(Duration delay, Runnable command) {
      var j = new Job(Math.addExact(now, delay.toNanos()), seq++, command);
      jobs.add(j);
      return () -> j.cancelled = true;
    }

    public void drain() {
      int budget = 10000;
      while (!ready.isEmpty()) {
        if (--budget == 0) throw new IllegalStateException("Nonterminating command loop");
        ready.remove().run();
      }
    }

    public void advance(Duration duration) {
      long target = Math.addExact(now, duration.toNanos());
      drain();
      while (!jobs.isEmpty() && jobs.peek().at <= target) {
        var j = jobs.remove();
        now = j.at;
        if (!j.cancelled) j.run.run();
        drain();
      }
      now = target;
      drain();
    }
  }

  public static byte[] bytes(String name) {
    try (var in = HeadlessDialogue.class.getResourceAsStream("/" + name)) {
      if (in == null) throw new IllegalStateException("Missing resource " + name);
      byte[] value = in.readNBytes(16777217);
      if (value.length > 16777216) throw new IllegalStateException("Input too large");
      return value;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static DialogueCatalogLoader.ContentSources sources() {
    return new DialogueCatalogLoader.ContentSources(
        Map.of("dialogues/mechanic.json", bytes("dialogues/mechanic.json")),
        Map.of("en", bytes("texts/en.json"), "de", bytes("texts/de.json")),
        bytes("context/host-catalog.json"));
  }

  public static DialogueRenderer recording(List<DialogueView> views) {
    return req ->
        CompletableFuture.completedFuture(
            new DialogueRenderer.PresentationHandle() {
              public CompletionStage<Void> replace(DialogueView view) {
                views.add(view);
                return CompletableFuture.completedFuture(null);
              }

              public CompletionStage<Void> reconfigure(PresentationPreferences p) {
                return CompletableFuture.completedFuture(null);
              }

              public CompletionStage<Void> closeAsync() {
                return CompletableFuture.completedFuture(null);
              }
            });
  }

  public static <T> T done(CompletionStage<T> stage, StepClock clock) {
    clock.drain();
    if (!stage.toCompletableFuture().isDone())
      throw new IllegalStateException("Scenario left a pending stage");
    return stage.toCompletableFuture().join();
  }

  public static DialogueInput.InputStamp stamp(DialogueHandle h, long impulse) {
    var v = h.snapshot();
    return new DialogueInput.InputStamp(v.sessionId(), v.renderRevision(), impulse);
  }

  public static String runSmoke() {
    return scenario(false);
  }

  public static String runPresentationSmoke() {
    return scenario(true);
  }

  private static final class RecordedSurface implements DialogueSurface {
    int opens, closes;
    final List<WidgetFrame> frames = new ArrayList<>();

    public CompletionStage<SurfaceHandle> open(SurfaceContext c) {
      opens++;
      return CompletableFuture.completedFuture(
          new SurfaceHandle() {
            public CompletionStage<Void> replace(WidgetFrame frame) {
              frames.add(frame);
              return CompletableFuture.completedFuture(null);
            }

            public CompletionStage<Void> closeAsync() {
              closes++;
              return CompletableFuture.completedFuture(null);
            }
          });
    }
  }

  private static String scenario(boolean graphical) {
    var loader = new DialogueCatalogLoader();
    var loaded = loader.reload(sources());
    if (!loaded.isValid()) throw new IllegalStateException(loaded.problems().toString());
    var content = loader.current().orElseThrow().entries().get("demo:mechanic");
    var clock = new StepClock();
    var frames = new ArrayList<DialogueView>();
    var events = new ArrayList<OutcomeSink.OutcomeEvent>();
    var hud = new RecordedSurface();
    var npc = new RecordedSurface();
    DialogueRenderer renderer =
        graphical
            ? new DialoguePresenter(
                Map.of(
                    "hud",
                    new DialoguePresenter.SurfaceProfile(
                        hud,
                        LayoutProfile.hud(),
                        DialogueTheme.neutral(),
                        FontMetrics.defaults(),
                        false),
                    "npc",
                    new DialoguePresenter.SurfaceProfile(
                        npc,
                        LayoutProfile.npc(),
                        DialogueTheme.neutral(),
                        FontMetrics.defaults(),
                        false)),
                clock)
            : recording(frames);
    var bindings =
        new HostBindings(
            "headless-v1",
            Map.of(),
            Map.of(),
            event -> {
              events.add(event);
              return CompletableFuture.completedFuture(OutcomeSink.OutcomeResult.ACKNOWLEDGED);
            },
            TextResolver.catalogue(content.texts(), "en"),
            renderer,
            null,
            Set.of("dialogue.choices"));
    var runtime = DialogueRuntime.create(RuntimeOptions.defaults(clock));
    var request =
        new DialogueStart(
            "smoke",
            "demo-player",
            1,
            content,
            bindings,
            Map.of(),
            new LocaleContext("en", 0, "en"),
            new PresentationPreferences("hud", Duration.ZERO, true, Map.of()),
            new CancellationToken(),
            0);
    var h = ((DialogueStartResult.Started) done(runtime.start(request), clock)).handle();
    if (h.snapshot().choices().size() != 4)
      throw new IllegalStateException("Expected four choices");
    long visit = h.snapshot().nodeVisitId();
    if (graphical) {
      done(
          h.updatePreferences(new PresentationPreferences("npc", Duration.ZERO, true, Map.of())),
          clock);
      if (h.snapshot().nodeVisitId() != visit || hud.closes != 1 || npc.opens != 1)
        throw new IllegalStateException("Surface switch changed the session or leaked a surface");
    }
    done(h.refreshLocale(new LocaleContext("de", 1, "en")), clock);
    if (h.snapshot().nodeVisitId() != visit)
      throw new IllegalStateException("Locale restarted the node");
    done(h.submit(new DialogueInput.Select(stamp(h, 1), "ask_lift")), clock);
    done(h.submit(new DialogueInput.Confirm(stamp(h, 2), "ask_lift")), clock);
    if (!h.snapshot().nodeId().equals("explain_lift"))
      throw new IllegalStateException("Choice was not followed");
    done(h.submit(new DialogueInput.Confirm(stamp(h, 3), null)), clock);
    if (events.size() != 1 || h.snapshot().hint() != DialogueView.HintPhase.FINISH)
      throw new IllegalStateException("Expected explicit milestone and final hint");
    done(h.submit(new DialogueInput.Confirm(stamp(h, 4), null)), clock);
    if (done(h.completion(), clock).reason() != DialogueEndReason.COMPLETED)
      throw new IllegalStateException("Did not complete");
    done(runtime.closeAsync(), clock);
    if (graphical && (npc.closes != 1 || npc.frames.isEmpty()))
      throw new IllegalStateException("Surface did not render or close");
    return graphical ? "PRESENTATION_OK" : "HEADLESS_DIALOGUE_OK";
  }

  public static void main(String[] args) {
    System.out.println(
        args.length > 0 && args[0].equals("--presentation-smoke")
            ? runPresentationSmoke()
            : runSmoke());
  }
}
