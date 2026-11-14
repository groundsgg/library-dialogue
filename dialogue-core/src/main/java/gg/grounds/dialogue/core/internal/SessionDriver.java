package gg.grounds.dialogue.core.internal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
/** Single serialized session owner; foreign callbacks are invoked without internal monitors. */
public final class SessionDriver implements DialogueHandle {
 private DialogueStart start;private final RuntimeOptions options;private final UUID id=UUID.randomUUID();private final SerialCommandQueue queue;private final OwnedScope scope;private final CompletableFuture<DialogueStartResult> started;private final CompletableFuture<DialogueEnd> ended=new CompletableFuture<>();private final AtomicReference<DialogueEndReason> stopping=new AtomicReference<>();private final CancellationToken cancellation=new CancellationToken();private final List<PendingOperation<?>> waits=new ArrayList<>();private volatile DialogueView view;private DialogueRenderer.PresentationHandle renderer;private ActorPort.ActorHandle actor;private boolean rendererReady,actorReady,actorSkipped,activated,closing;private String startupCode;
 public SessionDriver(DialogueStart start,RuntimeOptions options,CompletableFuture<DialogueStartResult> ready){this.start=start;this.options=options;started=ready;queue=new SerialCommandQueue(options.scheduler(),this::unexpected);scope=new OwnedScope(queue,options.scheduler(),options.budgets().cleanup());view=new DialogueView(id,start.content().contentPin(),start.content().definition().entry(),0,DialogueView.State.PREPARING,0,0,start.locale().tag(),"","",List.of(),null,0,0,DialogueView.HintPhase.BUSY,"",null);}
 private boolean active(){return stopping.get()==null&&!ended.isDone();}
 private void unexpected(Throwable error){if(!ended.isDone()){startupCode="SESSION_FAILED";stop(DialogueEndReason.FAILED);}}
 private <T> void await(CompletionStage<T> stage,java.time.Duration budget,java.util.function.BiConsumer<T,Throwable> callback){waits.add(new PendingOperation<>(queue,options.scheduler(),budget,stage,callback));}
 public void begin(){queue.execute(()->{if(!active()){stopNow();return;}if(start.cancellation().isCancelled()){stop(start.cancellation().reason());return;}
  scope.prepare(()->CompletableFuture.completedFuture(start.cancellation().onCancel(this::stop)));
  if(!start.bindings().capabilities().containsAll(start.content().definition().capabilities().required())||(start.content().definition().capabilities().required().contains("actor.gesture")&&start.bindings().actors()==null)){startupCode="CAPABILITY_MISSING";stop(DialogueEndReason.FAILED);return;}
  long deadline=options.scheduler().nanoTime()+options.budgets().prepare().toNanos();
  var presentation=scope.prepare(()->start.bindings().renderer().prepare(new DialogueRenderer.PresentationRequest(id,start.audienceId(),start.hostGeneration(),start.preferences(),cancellation,deadline)));
  await(presentation,options.budgets().prepare(),(v,e)->{if(!active())return;if(e!=null){startupCode="PREPARE_FAILED";stop(DialogueEndReason.FAILED);}else{renderer=v;rendererReady=true;tryReady();}});
  if(start.bindings().actors()==null){actorReady=true;tryReady();}else{
   var speakers=new HashSet<String>();start.content().definition().nodes().values().forEach(n->{if(n instanceof Message m)speakers.add(m.speaker());});
   var preparation=scope.prepare(()->start.bindings().actors().prepare(new ActorPort.ActorRequest(id,start.audienceId(),start.hostGeneration(),speakers,cancellation,deadline)));
   preparation.whenComplete((v,e)->queue.execute(()->{if(v!=null&&actorSkipped)scope.release(v);}));
   await(preparation,options.budgets().prepare(),(v,e)->{if(!active())return;if(e!=null){if(start.content().definition().capabilities().required().contains("actor.gesture")){startupCode="ACTOR_PREPARE_FAILED";stop(DialogueEndReason.FAILED);return;}actorSkipped=true;}else actor=v;actorReady=true;tryReady();});
  }
 });}
 private void tryReady(){if(!active()||activated||!rendererReady||!actorReady)return;activated=true;var node=start.content().definition().nodes().get(view.nodeId());String text=node instanceof Message m?start.content().texts().locales().getOrDefault(start.locale().tag(),Map.of()).getOrDefault(m.text(),m.text()):"";view=new DialogueView(id,view.contentPin(),view.nodeId(),1,DialogueView.State.WAITING_FOR_INPUT,view.stateRevision()+1,view.renderRevision()+1,start.locale().tag(),node instanceof Message m?m.speaker():"",text,List.of(),null,text.codePointCount(0,text.length()),text.codePointCount(0,text.length()),DialogueView.HintPhase.CONTINUE,"",null);CompletionStage<Void> stage;try{stage=Objects.requireNonNull(renderer.replace(view));}catch(Throwable e){stage=CompletableFuture.failedFuture(e);}await(stage,options.budgets().prepare(),(v,e)->{if(!active())return;if(e!=null){startupCode="RENDER_FAILED";stop(DialogueEndReason.FAILED);}else started.complete(new DialogueStartResult.Started(this));});}
 public CompletionStage<InputResult> submit(DialogueInput input){return CompletableFuture.completedFuture(ended.isDone()?InputResult.ENDED:InputResult.BUSY);}
 public CompletionStage<RefreshResult> refreshLocale(LocaleContext locale){return CompletableFuture.completedFuture(ended.isDone()?RefreshResult.ENDED:RefreshResult.STALE);}
 public CompletionStage<Void> updatePreferences(PresentationPreferences preferences){return CompletableFuture.failedFuture(new UnsupportedOperationException("Presentation reconfiguration not installed"));}
 public DialogueView snapshot(){return view;}
 public CompletionStage<DialogueEnd> completion(){return ended;}
 public CompletionStage<DialogueEnd> stop(DialogueEndReason reason){Objects.requireNonNull(reason);if(stopping.compareAndSet(null,reason)){cancellation.cancel(reason);queue.execute(this::stopNow);}return ended;}
 private void stopNow(){if(closing)return;closing=true;waits.forEach(PendingOperation::cancel);waits.clear();var reason=stopping.get();if(reason==null){reason=DialogueEndReason.CANCELLED;stopping.compareAndSet(null,reason);}view=new DialogueView(id,view.contentPin(),view.nodeId(),view.nodeVisitId(),DialogueView.State.CLOSING,view.stateRevision()+1,view.renderRevision()+1,view.localeTag(),"","",List.of(),null,0,0,DialogueView.HintPhase.BUSY,"",null);scope.closeAsync().whenComplete((v,error)->queue.execute(()->finish(error)));}
 private void finish(Throwable error){view=new DialogueView(id,view.contentPin(),view.nodeId(),view.nodeVisitId(),DialogueView.State.CLOSED,view.stateRevision()+1,view.renderRevision()+1,view.localeTag(),"","",List.of(),null,0,0,DialogueView.HintPhase.BUSY,error==null?"":"CLEANUP_FAILED",null);start=null;renderer=null;actor=null;started.complete(new DialogueStartResult.Rejected(error==null?(startupCode==null?stopping.get().name():startupCode):"CLEANUP_FAILED",List.of()));if(error!=null)ended.completeExceptionally(error);else ended.complete(new DialogueEnd(id,stopping.get(),List.of()));}
}
