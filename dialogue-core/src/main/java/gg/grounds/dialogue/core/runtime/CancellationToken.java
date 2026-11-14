package gg.grounds.dialogue.core.runtime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import gg.grounds.dialogue.core.port.OwnedResource;
/** Host-owned cancellation signal, valid before a runtime handle exists. */
public final class CancellationToken {
 private final UUID id=UUID.randomUUID();private final AtomicReference<DialogueEndReason> reason=new AtomicReference<>();private final Set<Listener> listeners=ConcurrentHashMap.newKeySet();
 private static final class Listener {final Consumer<DialogueEndReason> callback;final AtomicBoolean fired=new AtomicBoolean();Listener(Consumer<DialogueEndReason> c){callback=c;}void fire(DialogueEndReason r){if(fired.compareAndSet(false,true))callback.accept(r);}}
 public UUID id(){return id;}public boolean isCancelled(){return reason.get()!=null;}public DialogueEndReason reason(){return reason.get();}
 public OwnedResource onCancel(Consumer<DialogueEndReason> callback){var l=new Listener(Objects.requireNonNull(callback));listeners.add(l);var r=reason.get();if(r!=null){listeners.remove(l);l.fire(r);}return ()->{listeners.remove(l);return CompletableFuture.completedFuture(null);};}
 public void cancel(DialogueEndReason r){Objects.requireNonNull(r);if(!reason.compareAndSet(null,r))return;RuntimeException failure=null;for(var l:listeners){try{l.fire(r);}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}}listeners.clear();if(failure!=null)throw failure;}
}
