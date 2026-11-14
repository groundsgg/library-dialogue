package gg.grounds.dialogue.core.runtime;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.text.LocaleContext;
import gg.grounds.dialogue.core.validation.*;
class DialogueOutcomesTest {
 @Test void outcomeIdentityAndExplicitConfirmation(){var d=DialogueBuilder.dialogue("demo:outcomes").locales("en","en","de").node("start",new Message("npc","hello",null,List.of(new Choice("again","c","milestone",true,null),new Choice("leave","bye","end",false,null)),null)).node("milestone",new Outcome("demo:explained",Delivery.LOCAL,"start","end","end")).node("end",new End("completed")).build();var r=new TestRig(d,new HostCatalog("v1",Set.of("npc"),Map.of(),Map.of(),Set.of(),Set.of(),false));var events=new ArrayList<OutcomeSink.OutcomeEvent>();r.bindings=new HostBindings("events",Map.of(),Map.of(),event->{events.add(event);return CompletableFuture.completedFuture(OutcomeSink.OutcomeResult.ACKNOWLEDGED);},r.bindings.texts(),r.bindings.renderer(),null,Set.of());var h=r.start();assertTrue(events.isEmpty());h.submit(DialogueInputTest.confirm(h,1));r.scheduler.drain();assertTrue(events.isEmpty());h.submit(DialogueInputTest.confirm(h,2));r.scheduler.drain();assertEquals(1,events.size());h.refreshLocale(new LocaleContext("de",1,"en"));r.scheduler.drain();assertEquals(1,events.size());h.submit(DialogueInputTest.confirm(h,3));r.scheduler.drain();assertEquals(2,events.size());assertNotEquals(events.get(0).eventId(),events.get(1).eventId());}
 @Test void durableOutcomeRequiresDeclaredHostCapabilityAndAcknowledgement(){
  var d=DialogueBuilder.dialogue("demo:durable").node("start",new Message("npc","hello",null,List.of(),"milestone")).node("milestone",new Outcome("demo:stored",Delivery.DURABLE,"ack","unavailable","uncertain")).node("ack",new Message("npc","bye",null,List.of(),"end")).node("unavailable",new Message("npc","hello",null,List.of(),"end")).node("uncertain",new Message("npc","hello",null,List.of(),"end")).node("end",new End("completed")).build();
  for(boolean declared:List.of(false,true)){var r=new TestRig(d,new HostCatalog("v1",Set.of("npc"),Map.of(),Map.of(),Set.of(),Set.of(),declared));var log=new HashMap<UUID,OutcomeSink.OutcomeEvent>();var ack=new CompletableFuture<OutcomeSink.OutcomeResult>();r.bindings=new HostBindings("durable",Map.of(),Map.of(),event->{log.putIfAbsent(event.eventId(),event);return ack;},r.bindings.texts(),r.bindings.renderer(),null,declared?Set.of("outcome.durable"):Set.of());var h=r.start();h.submit(DialogueInputTest.confirm(h,1));r.scheduler.drain();h.submit(DialogueInputTest.confirm(h,2));r.scheduler.drain();if(!declared){assertEquals("unavailable",h.snapshot().nodeId());assertTrue(log.isEmpty());}else{assertEquals(1,log.size());assertEquals("milestone",h.snapshot().nodeId());ack.complete(OutcomeSink.OutcomeResult.ACKNOWLEDGED);r.scheduler.drain();assertEquals("ack",h.snapshot().nodeId());}}
 }
}
