package gg.grounds.dialogue.json;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.text.TextCatalog;
import gg.grounds.dialogue.core.validation.*;
class DialogueJsonCodecTest {
 static final String SOURCE="""
 {"schemaVersion":1,"id":"demo:test","contentVersion":"1.0.0","entry":"start","locales":{"default":"en","supported":["en"]},"nodes":{"start":{"type":"message","speaker":"npc","text":"line","next":"end"},"end":{"type":"end","reason":"completed"}}}
 """;
 static final String HOST="""
 {"schemaVersion":1,"generation":"v1","speakers":["npc"],"conditions":{},"actions":{},"gestures":[],"capabilities":[],"durableOutcomes":false}
 """;
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static DialogueJsonCodec.DecodeResult decode(String s){return new DialogueJsonCodec().decode(bytes(s),Map.of("en",bytes("{\"line\":\"Hello\"}")),bytes(HOST));}
 @Test void javaAndJsonShareDefinitionAndPin() {
  var r=decode(SOURCE);assertTrue(r.isValid(),r.problems().toString());
  var d=DialogueBuilder.dialogue("demo:test").node("start",new Message("npc","line",null,List.of(),"end")).node("end",new End("completed")).build();
  var v=new DialogueValidator().validate(d,new TextCatalog(Map.of("en",Map.of("line","Hello"))),new HostCatalog("v1",Set.of("npc"),Map.of(),Map.of(),Set.of(),Set.of(),false),ValidationLimits.defaults());
  assertEquals(d,r.validated().definition());assertEquals(v.validated().contentPin(),r.validated().contentPin());
  assertEquals(r.validated().contentPin(),decode(new String(new DialogueJsonCodec().encode(d),StandardCharsets.UTF_8)).validated().contentPin());
 }
 @Test void strictBoundaryFailures() {
  for(String s:List.of(SOURCE.replace("\"schemaVersion\":1","\"schemaVersion\":2"),SOURCE.replace("\"entry\":","\"entry\":\"x\",\"entry\":"),SOURCE+"{}",SOURCE.replace("\"id\":","\"unknown\":1,\"id\":"),"\ufeff"+SOURCE,SOURCE.replace("Hello","bad"))) {
   if(s.equals(SOURCE))continue;assertFalse(decode(s).isValid(),s);
  }
  var codec=new DialogueJsonCodec();
  assertFalse(codec.decode(new byte[]{(byte)0xc3,0x28},Map.of(),bytes(HOST)).isValid());
  assertFalse(decode(SOURCE.replace("demo:test","\\ud800")).isValid());
  assertFalse(decode(SOURCE.replace("\"id\":", "\"nested\":"+"[".repeat(33)+"0"+"]".repeat(33)+",\"id\":")).isValid());
  assertFalse(codec.decode(new byte[1048577],Map.of(),bytes(HOST)).isValid());
 }
 @Test void malformedJsonDiagnosticsKeepFileAndPath() {
  var r=decode(SOURCE.replace("\"end\":{","\"end\":{\"reason\":\"completed\","));
  assertFalse(r.isValid());assertNotNull(r.problems().getFirst().path());assertEquals("dialogue.json",r.problems().getFirst().file());
 }
}
