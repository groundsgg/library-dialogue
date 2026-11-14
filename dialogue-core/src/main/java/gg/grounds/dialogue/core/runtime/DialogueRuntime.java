package gg.grounds.dialogue.core.runtime;

import gg.grounds.dialogue.core.internal.SessionDriver;
import java.util.*;
import java.util.concurrent.*;

/** Local runtime. Network authority and durable job registries belong to the host. */
public final class DialogueRuntime {
  private final RuntimeOptions options;
  private final Object lock = new Object();
  private final Map<String, Entry> starts = new HashMap<>();
  private boolean closing;
  private CompletableFuture<Void> closed;
  private CompletionStage<Void> closedView;

  private record Entry(
      String identity,
      SessionDriver driver,
      CompletableFuture<DialogueStartResult> result,
      CompletionStage<DialogueStartResult> observation) {}

  private DialogueRuntime(RuntimeOptions options) {
    this.options = Objects.requireNonNull(options);
  }

  public static DialogueRuntime create(RuntimeOptions options) {
    return new DialogueRuntime(options);
  }

  private static String identity(DialogueStart s) {
    var values = new LinkedHashMap<String, Object>();
    values.put("audience", s.audienceId());
    values.put("hostGeneration", s.hostGeneration());
    values.put("content", s.content().contentPin().sha256());
    values.put("bindings", s.bindings().generation());
    values.put("authorGeneration", s.content().catalog().generation());
    values.put("validationPolicy", s.content().limits().toString());
    values.put(
        "locale",
        Map.of(
            "tag",
            s.locale().tag(),
            "revision",
            s.locale().revision(),
            "fallback",
            s.locale().fallbackTag()));
    values.put(
        "preferences",
        Map.of(
            "surface",
            s.preferences().surfaceKey(),
            "revealNanos",
            s.preferences().graphemeDelay().toNanos(),
            "reducedMotion",
            s.preferences().reducedMotion(),
            "effects",
            s.preferences().effects()));
    values.put(
        "context",
        gg.grounds.dialogue.core.validation.ContentFingerprint.argumentData(s.context()));
    values.put("cancellation", s.cancellation().id().toString());
    values.put("openingImpulse", s.openingImpulse());
    return gg.grounds.dialogue.core.validation.ContentFingerprint.hash(
            gg.grounds.dialogue.core.validation.ContentFingerprint.canonicalBytes(values))
        .sha256();
  }

  public CompletionStage<DialogueStartResult> start(DialogueStart request) {
    Objects.requireNonNull(request);
    String identity = identity(request);
    Entry entry;
    String rejection = null;
    synchronized (lock) {
      if (closing) rejection = "HOST_CLOSED";
      else {
        var existing = starts.get(request.requestId());
        if (existing != null) {
          if (existing.identity.equals(identity)) return existing.observation;
          rejection = "START_REQUEST_CONFLICT";
        } else if (starts.size() >= options.startRegisterLimit()) rejection = "START_REGISTER_FULL";
      }
      if (rejection == null) {
        var result = new CompletableFuture<DialogueStartResult>();
        entry =
            new Entry(
                identity,
                new SessionDriver(request, options, result),
                result,
                result.minimalCompletionStage());
        starts.put(request.requestId(), entry);
      } else entry = null;
    }
    if (rejection != null)
      return CompletableFuture.completedFuture(
          new DialogueStartResult.Rejected(rejection, List.of()));
    entry.driver.begin();
    return entry.observation;
  }

  public CompletionStage<Void> closeAsync() {
    List<Entry> entries;
    CompletableFuture<Void> result;
    synchronized (lock) {
      if (closed != null) return closedView;
      closing = true;
      closed = new CompletableFuture<>();
      closedView = closed.minimalCompletionStage();
      result = closed;
      entries = List.copyOf(starts.values());
    }
    var futures =
        entries.stream()
            .map(e -> e.driver.stop(DialogueEndReason.HOST_CLOSED).toCompletableFuture())
            .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(futures)
        .whenComplete(
            (v, e) -> {
              synchronized (lock) {
                starts.clear();
              }
              if (e == null) result.complete(null);
              else result.completeExceptionally(e);
            });
    return closedView;
  }
}
