package gg.grounds.dialogue.core.runtime;
import java.util.*;
import java.util.concurrent.*;
import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.core.port.*;
final class TestRig {
 final ManualScheduler scheduler=new ManualScheduler();
 final List<DialogueView> frames=new ArrayList<>();
 final List<ActorPort.ActorCue> cues=new ArrayList<>();
 final ValidatedDialogue content;
 final CancellationToken cancellation=new CancellationToken();
 HostBindings bindings;
 final DialogueRuntime runtime;
 TestRig(){this(DialogueBuilder.dialogue("demo:runtime").node("start",new Message("npc","hello","demo:wave",List.of(),"bye")).node("bye",new Message("npc","bye",null,List.of(),"end")).node("end",new End("completed")).build(),new HostCatalog("v1",Set.of("npc"),Map.of(),Map.of(),Set.of("demo:wave"),Set.of(),false));}
 TestRig(DialogueDefinition d,HostCatalog catalog){
  var report=new DialogueValidator().validate(d,new TextCatalog(Map.of("en",Map.of("hello","Hello friend","bye","Goodbye","c","Continue","speaker.npc","Mechanic"),"de",Map.of("hello","Hallo Freund","bye","Tschüss","c","Weiter","speaker.npc","Mechaniker"))),catalog,ValidationLimits.defaults());
  if(!report.isValid())throw new AssertionError(report.problems());content=report.validated();
  DialogueRenderer renderer=req->CompletableFuture.completedFuture(new DialogueRenderer.PresentationHandle(){public CompletionStage<Void> replace(DialogueView v){frames.add(v);return CompletableFuture.completedFuture(null);}public CompletionStage<Void> reconfigure(PresentationPreferences p){return CompletableFuture.completedFuture(null);}public CompletionStage<Void> closeAsync(){return CompletableFuture.completedFuture(null);}});
  ActorPort actors=req->CompletableFuture.completedFuture(new ActorPort.ActorHandle(){public CompletionStage<Void> cue(ActorPort.ActorCue c){cues.add(c);return CompletableFuture.completedFuture(null);}public CompletionStage<Void> closeAsync(){return CompletableFuture.completedFuture(null);}});
  bindings=new HostBindings("b1",Map.of(),Map.of(),null,TextResolver.catalogue(content.texts(),content.definition().locales().defaultTag()),renderer,actors,Set.of());
  runtime=DialogueRuntime.create(RuntimeOptions.defaults(scheduler));
 }
 DialogueStart request(String id){return new DialogueStart(id,"player-a",1,content,bindings,Map.of(),new LocaleContext("en",0,"en"),PresentationPreferences.defaults(),cancellation,0);}
 DialogueHandle start(){var f=runtime.start(request("start-1"));scheduler.drain();return ((DialogueStartResult.Started)done(f)).handle();}
 static <T>T done(CompletionStage<T> s){var f=s.toCompletableFuture();org.junit.jupiter.api.Assertions.assertTrue(f.isDone(),"stage must not remain pending");return f.join();}
}
