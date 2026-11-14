package gg.grounds.dialogue.core.definition;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
class DialogueDefinitionTest {
 @Test void builderAndMutationIsolation() {
  var choices = new ArrayList<>(List.of(new Choice("ask","question","end",false,null), new Choice("leave","bye","end",true,null)));
  var message = new Message("mechanic","hello",null,choices,null);
  var d = DialogueBuilder.dialogue("demo:mechanic").entry("start").node("start",message).node("end",new End("completed")).build();
  choices.clear();
  assertEquals(List.of("ask","leave"), ((Message)d.nodes().get("start")).choices().stream().map(Choice::id).toList());
  assertThrows(UnsupportedOperationException.class, () -> message.choices().clear());
  assertThrows(UnsupportedOperationException.class, () -> d.nodes().clear());
 }
 @Test void duplicateNodesNeverOverwriteContent() {
  var b=DialogueBuilder.dialogue("demo:test").node("end",new End("completed"));
  assertThrows(IllegalArgumentException.class, () -> b.node("end",new End("cancelled")));
 }
 @Test void messageHasExactlyOneContinuation() {
  assertThrows(IllegalArgumentException.class, () -> new Message("npc","line",null,List.of(),null));
  assertThrows(IllegalArgumentException.class, () -> new Message("npc","line",null,List.of(new Choice("c","c","end",false,null)),"end"));
 }
}
