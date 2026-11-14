package gg.grounds.dialogue.json;
import static org.junit.jupiter.api.Assertions.*;
import com.networknt.schema.*;
import org.junit.jupiter.api.Test;
class SchemaContractTest {
 @Test void schemasAndCodecAgreeOnStructures() {
  var source=getClass().getResourceAsStream("/schemas/dialogue-v1.schema.json");assertNotNull(source);
  var schema=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(source);
  assertTrue(schema.validate(DialogueJsonCodecTest.SOURCE,InputFormat.JSON).isEmpty());
  for(String invalid:java.util.List.of(DialogueJsonCodecTest.SOURCE.replace("\"schemaVersion\":1","\"schemaVersion\":2"),DialogueJsonCodecTest.SOURCE.replace("\"id\":","\"oops\":1,\"id\":"),DialogueJsonCodecTest.SOURCE.replace("\"next\":\"end\"","\"choices\":[]"))) {
   assertFalse(schema.validate(invalid,InputFormat.JSON).isEmpty());assertFalse(DialogueJsonCodecTest.decode(invalid).isValid());
  }
  var host=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(getClass().getResourceAsStream("/schemas/host-catalog-v1.schema.json"));
  assertTrue(host.validate(DialogueJsonCodecTest.HOST,InputFormat.JSON).isEmpty());
 }
}
