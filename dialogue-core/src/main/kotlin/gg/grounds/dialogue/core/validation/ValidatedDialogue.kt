package gg.grounds.dialogue.core.validation

import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.text.TextCatalog

/** Only the validator issues activation proof. */
class ValidatedDialogue
private constructor(
    private val definition: DialogueDefinition,
    private val texts: TextCatalog,
    private val catalog: HostCatalog,
    private val limits: ValidationLimits,
) {
    private val contentPin = ContentFingerprint.pin(definition, texts)

    fun contentPin(): ContentPin = contentPin

    fun definition(): DialogueDefinition = definition

    fun texts(): TextCatalog = texts

    fun catalog(): HostCatalog = catalog

    fun limits(): ValidationLimits = limits

    companion object {
        @JvmSynthetic
        internal fun issue(
            definition: DialogueDefinition,
            texts: TextCatalog,
            host: HostCatalog,
            limits: ValidationLimits,
        ): ValidatedDialogue = ValidatedDialogue(definition, texts, host, limits)
    }
}
