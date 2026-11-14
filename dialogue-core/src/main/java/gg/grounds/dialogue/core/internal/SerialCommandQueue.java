package gg.grounds.dialogue.core.internal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import gg.grounds.dialogue.core.port.DialogueScheduler;
/** Queue coordination never calls user code while holding a monitor. */
public final class SerialCommandQueue {
 private final ConcurrentLinkedQueue<Runnable> commands=new ConcurrentLinkedQueue<>();private final AtomicInteger work=new AtomicInteger();private final DialogueScheduler scheduler;private final Consumer<Throwable> errors;
 public SerialCommandQueue(DialogueScheduler scheduler,Consumer<Throwable> errors){this.scheduler=scheduler;this.errors=errors;}
 public void execute(Runnable command){commands.add(command);if(work.getAndIncrement()==0){try{scheduler.execute(this::drain);}catch(Throwable e){drain();errors.accept(e);}}}
 private void drain(){int missed=1;for(;;){Runnable command;while((command=commands.poll())!=null)try{command.run();}catch(Throwable e){errors.accept(e);}missed=work.addAndGet(-missed);if(missed==0)return;}}
}
