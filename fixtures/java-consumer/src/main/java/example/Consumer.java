package example;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.json.*;
import gg.grounds.dialogue.presentation.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class Consumer {
  static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static <T> T done(CompletionStage<T> s) {
    if (!s.toCompletableFuture().isDone())
      throw new IllegalStateException("Consumer unexpectedly waits");
    return s.toCompletableFuture().join();
  }

  public static void main(String[] args) {
    String definition =
        """
        {"schemaVersion":1,"id":"consumer:hello","contentVersion":"1","entry":"hello","locales":{"default":"en","supported":["en"]},"nodes":{"hello":{"type":"message","speaker":"npc","text":"line","next":"end"},"end":{"type":"end","reason":"completed"}}}
        """;
    String host =
        """
        {"schemaVersion":1,"generation":"public","speakers":["npc"],"conditions":{},"actions":{},"gestures":[],"capabilities":[],"durableOutcomes":false}
        """;
    var decoded =
        new DialogueJsonCodec()
            .decode(
                bytes(definition),
                Map.of("en", bytes("{\"line\":\"Hello external consumer\"}")),
                bytes(host));
    if (!decoded.isValid()) throw new AssertionError(decoded.problems());
    var content = decoded.validated();
    var clock =
        new DialogueScheduler() {
          public void execute(Runnable r) {
            r.run();
          }

          public ScheduledTask schedule(Duration d, Runnable r) {
            return () -> {};
          }

          public long nanoTime() {
            return 0;
          }
        };
    var frames = new ArrayList<WidgetFrame>();
    int[] closes = {0};
    DialogueSurface surface =
        context ->
            CompletableFuture.completedFuture(
                new DialogueSurface.SurfaceHandle() {
                  public CompletionStage<Void> replace(WidgetFrame frame) {
                    frames.add(frame);
                    return CompletableFuture.completedFuture(null);
                  }

                  public CompletionStage<Void> closeAsync() {
                    closes[0]++;
                    return CompletableFuture.completedFuture(null);
                  }
                });
    var presenter =
        new DialoguePresenter(
            Map.of("chat", DialoguePresenter.SurfaceProfile.semantic(surface)), clock);
    var bindings =
        new HostBindings(
            "public",
            Map.of(),
            Map.of(),
            null,
            TextResolver.catalogue(content.texts(), "en"),
            presenter,
            null,
            Set.of());
    var runtime = DialogueRuntime.create(RuntimeOptions.defaults(clock));
    var result =
        done(
            runtime.start(
                new DialogueStart(
                    "java-consumer",
                    "external",
                    1,
                    content,
                    bindings,
                    Map.of(),
                    new LocaleContext("en", 0, "en"),
                    new PresentationPreferences("chat", Duration.ZERO, true, Map.of()),
                    new CancellationToken(),
                    0)));
    var h = ((DialogueStartResult.Started) result).handle();
    if (!h.snapshot().text().equals("Hello external consumer") || frames.isEmpty())
      throw new AssertionError("Actual runtime did not render");
    var v = h.snapshot();
    done(
        h.submit(
            new DialogueInput.Confirm(
                new DialogueInput.InputStamp(v.sessionId(), v.renderRevision(), 1), null)));
    if (done(h.completion()).reason() != DialogueEndReason.COMPLETED || closes[0] != 1)
      throw new AssertionError("Owned close failed");
    done(runtime.closeAsync());
    for (Class<?> type :
        List.of(DialogueRuntime.class, DialogueJsonCodec.class, DialoguePresenter.class)) {
      var url = type.getProtectionDomain().getCodeSource().getLocation();
      if (!url.getPath().endsWith(".jar"))
        throw new AssertionError("Composite/class-directory origin: " + url);
      System.out.println("MAVEN_ORIGIN " + type.getName() + " " + url);
    }
    System.out.println("CONSUMER_OK java");
  }
}
