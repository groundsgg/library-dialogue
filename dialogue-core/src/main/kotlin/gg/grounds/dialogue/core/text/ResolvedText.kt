package gg.grounds.dialogue.core.text

import gg.grounds.dialogue.core.validation.DialogueValidator

@JvmRecord
data class ResolvedText(val plain: String, val localeTag: String) {
    init {
        require(
            DialogueValidator.validUnicode(plain) && plain.codePointCount(0, plain.length) <= 16_384
        ) {
            "Invalid resolved text"
        }
    }
}
