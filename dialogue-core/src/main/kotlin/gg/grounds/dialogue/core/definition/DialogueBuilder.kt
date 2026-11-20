package gg.grounds.dialogue.core.definition

import gg.grounds.dialogue.core.definition.DialogueDefinition.Capabilities
import gg.grounds.dialogue.core.definition.DialogueDefinition.Interaction
import gg.grounds.dialogue.core.definition.DialogueDefinition.LocaleSpec
import gg.grounds.dialogue.core.definition.DialogueDefinition.LookAway
import gg.grounds.dialogue.core.definition.DialogueDefinition.Node
import gg.grounds.dialogue.core.definition.DialogueDefinition.Presentation

/** Authoring path. Activation requires DialogueValidator. */
class DialogueBuilder private constructor(private val id: String) {
    private var version = "1.0.0"
    private var entry = "start"
    private var locales = LocaleSpec("en", listOf("en"))
    private var capabilities = Capabilities(emptySet(), emptySet())
    private var interaction = Interaction(LookAway(true, 110.0, 600, 95.0), 6.0)
    private var presentation = Presentation("none", "none", "none", emptySet())
    private val nodes = LinkedHashMap<String, Node>()

    fun version(value: String): DialogueBuilder = apply { version = value }

    fun entry(value: String): DialogueBuilder = apply { entry = value }

    fun locales(defaultTag: String, vararg supported: String): DialogueBuilder = apply {
        locales = LocaleSpec(defaultTag, supported.asList())
    }

    fun capabilities(value: Capabilities): DialogueBuilder = apply { capabilities = value }

    fun interaction(value: Interaction): DialogueBuilder = apply { interaction = value }

    fun presentation(value: Presentation): DialogueBuilder = apply { presentation = value }

    fun node(id: String, node: Node): DialogueBuilder = apply {
        require(nodes.putIfAbsent(id, node) == null) { "DUPLICATE_NODE_ID: $id" }
    }

    fun build(): DialogueDefinition =
        DialogueDefinition(
            id,
            version,
            entry,
            locales,
            capabilities,
            interaction,
            presentation,
            nodes,
        )

    companion object {
        @JvmStatic fun dialogue(id: String): DialogueBuilder = DialogueBuilder(id)
    }
}
