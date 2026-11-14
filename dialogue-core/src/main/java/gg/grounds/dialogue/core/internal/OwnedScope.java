package gg.grounds.dialogue.core.internal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.DialogueFailure;
/** Queue-confined ownership; acquisitions register before invoking foreign code. */
public final class OwnedScope {
 private final SerialCommandQueue queue;private final DialogueScheduler clock;private final Duration budget;private final List<OwnedResource> resources=new ArrayList<>();private final IdentityHashMap<OwnedResource,CompletableFuture<Void>> released=new IdentityHashMap<>();private final CompletableFuture<Void> completion=new CompletableFuture<>();private boolean closed;private int pending,cleaning;private Throwable failure;private DialogueScheduler.ScheduledTask timeout;
 public OwnedScope(SerialCommandQueue q,DialogueScheduler c,Duration b){queue=q;clock=c;budget=b;}
 public <T extends OwnedResource> CompletionStage<T> prepare(Supplier<CompletionStage<T>> supplier){if(closed)return CompletableFuture.failedFuture(new CancellationException("Scope closed"));pending++;var result=new CompletableFuture<T>();CompletionStage<T> stage;try{stage=Objects.requireNonNull(supplier.get());}catch(Throwable e){stage=CompletableFuture.failedFuture(e);}stage.whenComplete((value,error)->queue.execute(()->{pending--;if(value!=null){if(closed)dispose(value);else resources.add(value);}if(error!=null)result.completeExceptionally(error);else if(value==null)result.completeExceptionally(new NullPointerException("Preparation returned null resource"));else if(closed)result.completeExceptionally(new CancellationException("Scope closed during preparation"));else result.complete(value);check();}));return result;}
 public CompletionStage<Void> release(OwnedResource resource){resources.removeIf(r->r==resource);return dispose(resource);}
 public CompletionStage<Void> closeAsync(){if(closed)return completion;closed=true;timeout=clock.schedule(budget,()->queue.execute(()->{if(!completion.isDone())completion.completeExceptionally(new DialogueFailure("CLEANUP_TIMEOUT","Owned cleanup could not be confirmed before deadline"));}));var reverse=new ArrayList<>(resources);resources.clear();Collections.reverse(reverse);reverse.forEach(this::dispose);check();return completion;}
 private CompletionStage<Void> dispose(OwnedResource resource){var previous=released.get(resource);if(previous!=null)return previous;var done=new CompletableFuture<Void>();released.put(resource,done);cleaning++;CompletionStage<Void> stage;try{stage=Objects.requireNonNull(resource.closeAsync());}catch(Throwable e){stage=CompletableFuture.failedFuture(e);}stage.whenComplete((v,error)->queue.execute(()->{cleaning--;if(error!=null){if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error);done.completeExceptionally(error);}else done.complete(null);check();}));return done;}
 private void check(){if(closed&&pending==0&&cleaning==0){if(timeout!=null)timeout.cancel();released.clear();if(failure==null)completion.complete(null);else completion.completeExceptionally(failure);}}
}
