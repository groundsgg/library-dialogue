package example
import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.*
import gg.grounds.dialogue.json.DialogueJsonCodec
import gg.grounds.dialogue.presentation.*
import gg.grounds.dialogue.core.text.ResolvedText
fun main() {
    val definition = DialogueBuilder.dialogue("consumer:kotlin")
        .node("start", DialogueDefinition.Message("npc", "line", null, emptyList(), "end"))
        .node("end", DialogueDefinition.End("completed")).build()
    val texts = TextCatalog(mapOf("en" to mapOf("line" to "Hello Kotlin")))
    val catalog = HostCatalog("public", setOf("npc"), emptyMap(), emptyMap(), emptySet(), emptySet(), false)
    val report = DialogueValidator().validate(definition, texts, catalog, ValidationLimits.defaults())
    check(report.isValid) { report.problems().toString() }
    val codec = DialogueJsonCodec()
    val decoded = codec.decode(codec.encode(definition), mapOf("en" to """{"line":"Hello Kotlin"}""".toByteArray()), """{"schemaVersion":1,"generation":"public","speakers":["npc"],"conditions":{},"actions":{},"gestures":[],"capabilities":[],"durableOutcomes":false}""".toByteArray())
    check(decoded.isValid) { decoded.problems().toString() }
    check(decoded.validated().contentPin() == report.validated().contentPin())
    val layout = TextLayout().layout(ResolvedText("Hello Kotlin", "en"), FontMetrics.defaults(), TextLayout.TextLimits(320.0, 3))
    check(layout.isValid) { layout.problem() }
    check(layout.visible(5).single() == "Hello")
    for (type in listOf(DialogueBuilder::class.java, DialogueJsonCodec::class.java, DialoguePresenter::class.java)) {
        val origin = type.protectionDomain.codeSource.location
        check(origin.path.endsWith(".jar")) { "Composite/class-directory origin: $origin" }
        println("MAVEN_ORIGIN ${type.name} $origin")
    }
    println("CONSUMER_OK kotlin")
}
