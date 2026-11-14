package gg.grounds.dialogue.core.internal;
import java.util.*;
import java.util.concurrent.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
/** Serialized owner of one dialogue. Extended by the following lifecycle/input tasks. */
public final class SessionDriver implements DialogueHandle {
 private DialogueStart start;private final RuntimeOptions options;private final UUID id=UUID.randomUUID();private final SerialCommandQueue queue;private final CompletableFuture<DialogueStartResult> started;private final CompletableFuture<DialogueEnd> ended=new CompletableFuture<>();private volatile DialogueView view;private OwnedResource cancellationRegistration;
 public SessionDriver(DialogueStart start,RuntimeOptions options,CompletableFuture<DialogueStartResult> ready){this.start=start;this.options=options;started=ready;queue=new SerialCommandQueue(options.scheduler(),e->ended.completeExceptionally(e));view=new DialogueView(id,start.content().contentPin(),start.content().definition().entry(),0,DialogueView.State.PREPARING,0,0,start.locale().tag(),"","",List.of(),null,0,0,DialogueView.HintPhase.BUSY,"",null);}
 public void begin(){cancellationRegistration=start.cancellation().onCancel(this::stop);queue.execute(()->{if(start.cancellation().isCancelled()){stopNow(start.cancellation().reason());return;}var node=start.content().definition().nodes().get(view.nodeId());String text=node instanceof Message m?start.content().texts().locales().getOrDefault(start.locale().tag(),Map.of()).getOrDefault(m.text(),m.text()):"";view=new DialogueView(id,view.contentPin(),view.nodeId(),1,DialogueView.State.WAITING_FOR_INPUT,1,1,start.locale().tag(),node instanceof Message m?m.speaker():"",text,List.of(),null,text.length(),text.length(),DialogueView.HintPhase.CONTINUE,"",null);started.complete(new DialogueStartResult.Started(this));});}
 public CompletionStage<InputResult> submit(DialogueInput input){return CompletableFuture.completedFuture(ended.isDone()?InputResult.ENDED:InputResult.BUSY);}
 public CompletionStage<RefreshResult> refreshLocale(LocaleContext locale){return CompletableFuture.completedFuture(ended.isDone()?RefreshResult.ENDED:RefreshResult.STALE);}
 public CompletionStage<Void> updatePreferences(PresentationPreferences preferences){return CompletableFuture.failedFuture(new UnsupportedOperationException("Presentation reconfiguration not installed"));}
 public DialogueView snapshot(){return view;}
 public CompletionStage<DialogueEnd> completion(){return ended;}
 public CompletionStage<DialogueEnd> stop(DialogueEndReason reason){queue.execute(()->stopNow(reason));return ended;}
 private void stopNow(DialogueEndReason reason){if(ended.isDone())return;view=new DialogueView(id,view.contentPin(),view.nodeId(),view.nodeVisitId(),DialogueView.State.CLOSED,view.stateRevision()+1,view.renderRevision()+1,view.localeTag(),"","",List.of(),null,0,0,DialogueView.HintPhase.BUSY,"",null);if(cancellationRegistration!=null)cancellationRegistration.closeAsync();started.complete(new DialogueStartResult.Rejected(reason.name(),List.of()));ended.complete(new DialogueEnd(id,reason,List.of()));start=null;}
}
