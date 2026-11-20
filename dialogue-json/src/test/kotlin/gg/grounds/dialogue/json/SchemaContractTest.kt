package gg.grounds.dialogue.json

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaContractTest {
    @Test
    fun schemasAndCodecAgreeOnStructures() {
        val source = javaClass.getResourceAsStream("/schemas/dialogue-v1.schema.json")
        assertNotNull(source)
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(source)
        assertTrue(schema.validate(DialogueJsonCodecTest.SOURCE, InputFormat.JSON).isEmpty())
        listOf(
                DialogueJsonCodecTest.SOURCE.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                DialogueJsonCodecTest.SOURCE.replace("\"id\":", "\"oops\":1,\"id\":"),
                DialogueJsonCodecTest.SOURCE.replace("\"next\":\"end\"", "\"choices\":[]"),
                DialogueJsonCodecTest.SOURCE.replace(
                    "\"nodes\":",
                    "\"presentation\":{\"focus\":\"unknown\",\"nearFraming\":\"none\",\"vignette\":\"none\",\"hideHud\":[]},\"nodes\":",
                ),
            )
            .forEach { invalid ->
                assertFalse(schema.validate(invalid, InputFormat.JSON).isEmpty())
                assertFalse(DialogueJsonCodecTest.decode(invalid).isValid())
            }
        val host =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(javaClass.getResourceAsStream("/schemas/host-catalog-v1.schema.json"))
        assertTrue(host.validate(DialogueJsonCodecTest.HOST, InputFormat.JSON).isEmpty())
    }
}
