package gg.grounds.dialogue.core.runtime;
import java.time.Duration;
import java.util.*;
import gg.grounds.dialogue.core.port.DialogueScheduler;
final class ManualScheduler implements DialogueScheduler {
 private long now,seq;private final Queue<Runnable> ready=new ArrayDeque<>();
 private final PriorityQueue<Job> jobs=new PriorityQueue<>(Comparator.comparingLong((Job j)->j.at).thenComparingLong(j->j.seq));
 private static final class Job {long at,seq;Runnable run;boolean cancelled;Job(long a,long s,Runnable r){at=a;seq=s;run=r;}}
 public void execute(Runnable r){ready.add(r);}
 public ScheduledTask schedule(Duration delay,Runnable r){var j=new Job(Math.addExact(now,delay.toNanos()),seq++,r);jobs.add(j);return ()->j.cancelled=true;}
 public long nanoTime(){return now;}
 void drain(){int limit=10000;while(!ready.isEmpty()){if(--limit==0)throw new AssertionError("nonterminating ready queue");ready.remove().run();}}
 void advance(Duration d){long target=Math.addExact(now,d.toNanos());drain();while(!jobs.isEmpty()&&jobs.peek().at<=target){var j=jobs.remove();now=j.at;if(!j.cancelled)j.run.run();drain();}now=target;drain();}
}
