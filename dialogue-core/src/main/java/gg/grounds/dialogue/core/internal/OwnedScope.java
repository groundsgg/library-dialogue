package gg.grounds.dialogue.core.internal;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.DialogueFailure;
import java.lang.ref.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Queue-confined acquisition ownership; closed resources are weakly remembered, not retained. */
public final class OwnedScope {
  private final SerialCommandQueue queue;
  private final DialogueScheduler clock;
  private final Duration budget;
  private final List<OwnedResource> resources = new ArrayList<>();
  private final ReferenceQueue<OwnedResource> collected = new ReferenceQueue<>();
  private final Map<IdentityKey, CompletionStage<Void>> released = new HashMap<>();
  private final CompletableFuture<Void> completion = new CompletableFuture<>();
  private final CompletionStage<Void> observation = completion.minimalCompletionStage();
  private boolean closed;
  private int pending, cleaning;
  private Throwable failure;
  private DialogueScheduler.ScheduledTask timeout;

  private static final class IdentityKey extends WeakReference<OwnedResource> {
    final int hash;

    IdentityKey(OwnedResource r, ReferenceQueue<OwnedResource> q) {
      super(r, q);
      hash = System.identityHashCode(r);
    }

    public int hashCode() {
      return hash;
    }

    public boolean equals(Object o) {
      return this == o || (o instanceof IdentityKey k && get() != null && get() == k.get());
    }
  }

  public OwnedScope(SerialCommandQueue queue, DialogueScheduler clock, Duration budget) {
    this.queue = queue;
    this.clock = clock;
    this.budget = budget;
  }

  public static Throwable unwrap(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    return error;
  }

  private void record(Throwable error) {
    error = unwrap(error);
    if (failure == null) failure = error;
    else if (failure != error) failure.addSuppressed(error);
  }

  public <T extends OwnedResource> CompletionStage<T> prepare(
      Supplier<CompletionStage<T>> supplier) {
    if (closed) return CompletableFuture.failedFuture(new CancellationException("Scope closed"));
    pending++;
    var result = new CompletableFuture<T>();
    CompletionStage<T> stage;
    try {
      stage = Objects.requireNonNull(supplier.get());
    } catch (Throwable error) {
      stage = CompletableFuture.failedFuture(error);
    }
    stage.whenComplete(
        (value, error) ->
            queue.execute(
                () -> {
                  pending--;
                  if (value != null) {
                    if (closed) dispose(value);
                    else resources.add(value);
                  }
                  if (error != null) {
                    if (unwrap(error) instanceof OwnedResource.PreparationFailure) record(error);
                    result.completeExceptionally(error);
                  } else if (value == null)
                    result.completeExceptionally(
                        new NullPointerException("Preparation returned null resource"));
                  else if (closed)
                    result.completeExceptionally(
                        new CancellationException("Scope closed during preparation"));
                  else result.complete(value);
                  check();
                }));
    return result.minimalCompletionStage();
  }

  public CompletionStage<Void> release(OwnedResource resource) {
    resources.removeIf(r -> r == resource);
    return dispose(resource);
  }

  public CompletionStage<Void> closeAsync() {
    if (closed) return observation;
    closed = true;
    timeout =
        clock.schedule(
            budget,
            () ->
                queue.execute(
                    () -> {
                      if (!completion.isDone())
                        completion.completeExceptionally(
                            new DialogueFailure(
                                "CLEANUP_TIMEOUT",
                                "Owned cleanup could not be confirmed before deadline"));
                    }));
    var reverse = new ArrayList<>(resources);
    resources.clear();
    Collections.reverse(reverse);
    reverse.forEach(this::dispose);
    check();
    return observation;
  }

  private CompletionStage<Void> dispose(OwnedResource resource) {
    Objects.requireNonNull(resource);
    Reference<? extends OwnedResource> key;
    while ((key = collected.poll()) != null) released.remove(key);
    var lookup = new IdentityKey(resource, collected);
    var previous = released.get(lookup);
    if (previous != null) return previous;
    var done = new CompletableFuture<Void>();
    var view = done.minimalCompletionStage();
    released.put(lookup, view);
    cleaning++;
    CompletionStage<Void> stage;
    try {
      stage = Objects.requireNonNull(resource.closeAsync());
    } catch (Throwable error) {
      stage = CompletableFuture.failedFuture(error);
    }
    stage.whenComplete(
        (v, error) ->
            queue.execute(
                () -> {
                  cleaning--;
                  if (error != null) {
                    record(error);
                    done.completeExceptionally(error);
                  } else done.complete(null);
                  check();
                }));
    return view;
  }

  private void check() {
    if (closed && pending == 0 && cleaning == 0) {
      if (timeout != null) timeout.cancel();
      released.clear();
      if (failure == null) completion.complete(null);
      else completion.completeExceptionally(failure);
    }
  }
}
