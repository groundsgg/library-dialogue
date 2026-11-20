package gg.grounds.dialogue.json

import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.core.validation.ContentPin

class ContentCanonicalizer {
    fun pin(definition: DialogueDefinition, texts: TextCatalog): ContentPin =
        ContentFingerprint.pin(definition, texts)
}
