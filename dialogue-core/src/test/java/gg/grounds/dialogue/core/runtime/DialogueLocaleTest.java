package gg.grounds.dialogue.core.runtime;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
class DialogueLocaleTest {
 @Test void localeRefreshKeepsVisitSelectionAndCues(){
  var d=DialogueBuilder.dialogue("demo:locale").locales("en","en","de").node("start",new Message("npc","hello","demo:wave",List.of(new Choice("again","c","start",true,null),new Choice("leave","bye","end",false,null)),null)).node("end",new End("completed")).build();
  var r=new TestRig(d,new HostCatalog("v1",Set.of("npc"),Map.of(),Map.of(),Set.of("demo:wave"),Set.of(),false));var h=r.start();var old=h.snapshot();var s=h.submit(new DialogueInput.Select(new DialogueInput.InputStamp(old.sessionId(),old.renderRevision(),1),"leave"));r.scheduler.drain();assertEquals(InputResult.ACCEPTED,TestRig.done(s));var refresh=h.refreshLocale(new LocaleContext("de",1,"en"));r.scheduler.drain();assertEquals(RefreshResult.UPDATED,TestRig.done(refresh));var after=h.snapshot();assertEquals(old.nodeVisitId(),after.nodeVisitId());assertEquals("leave",after.selectedChoiceId());assertEquals("Hallo Freund",after.text());assertEquals("Mechaniker",after.speaker());assertEquals(after.totalGraphemes(),after.revealedGraphemes());assertTrue(after.renderRevision()>old.renderRevision());assertEquals(1,r.cues.size());var stale=h.submit(new DialogueInput.Confirm(new DialogueInput.InputStamp(old.sessionId(),old.renderRevision(),2),"leave"));r.scheduler.drain();assertEquals(InputResult.STALE,TestRig.done(stale));var older=h.refreshLocale(new LocaleContext("en",0,"en"));r.scheduler.drain();assertEquals(RefreshResult.STALE,TestRig.done(older));assertEquals("de",h.snapshot().localeTag());
 }
 @Test void requestedRegionalLocaleReportsEffectiveFallback(){var r=new TestRig();var h=r.start();var f=h.refreshLocale(new LocaleContext("en-US",1,"en"));r.scheduler.drain();assertEquals(RefreshResult.UPDATED,TestRig.done(f));assertEquals("en",h.snapshot().localeTag());assertEquals("Hello friend",h.snapshot().text());}
}
