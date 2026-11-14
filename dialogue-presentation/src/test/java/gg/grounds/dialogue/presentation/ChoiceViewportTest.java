package gg.grounds.dialogue.presentation;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import gg.grounds.dialogue.core.runtime.DialogueView.*;
class ChoiceViewportTest {
 @Test void sevenChoicesThreeCards(){var offers=List.of("c1","c2","c3","c4","c5","c6","leave").stream().map(id->new ChoiceView(id,id,Availability.AVAILABLE,"")).toList();var reached=new HashSet<String>();var v=new ChoiceViewport();for(var c:offers){var window=v.window(offers,c.id(),3);assertTrue(window.choices().size()<=3);assertTrue(window.choices().stream().anyMatch(o->o.id().equals(c.id())));window.choices().forEach(o->reached.add(o.id()));}assertEquals(Set.of("c1","c2","c3","c4","c5","c6","leave"),reached);assertEquals(6,v.window(offers,"leave",3).start());}
}
