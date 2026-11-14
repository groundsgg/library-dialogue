package gg.grounds.dialogue.core.internal;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import gg.grounds.dialogue.core.port.DialogueScheduler;
import gg.grounds.dialogue.core.runtime.DialogueFailure;
/** Bounds waiting, never asserts cancellation of an external side effect. All state is queue-confined. */
public final class PendingOperation<T> {
 private boolean done;private DialogueScheduler.ScheduledTask timer;private BiConsumer<T,Throwable> callback;
 public PendingOperation(SerialCommandQueue queue,DialogueScheduler clock,Duration budget,CompletionStage<T> stage,BiConsumer<T,Throwable> callback){this.callback=callback;timer=clock.schedule(budget,()->queue.execute(()->finish(null,new DialogueFailure("PROVIDER_TIMEOUT","Provider deadline elapsed"))));stage.whenComplete((v,e)->queue.execute(()->finish(v,e)));}
 private void finish(T value,Throwable error){if(done)return;done=true;if(timer!=null)timer.cancel();var c=callback;callback=null;c.accept(value,error);}
 public void cancel(){done=true;if(timer!=null)timer.cancel();callback=null;}
}
