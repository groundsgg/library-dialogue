package gg.grounds.dialogue.core.runtime

import java.util.UUID

sealed interface DialogueInput {
    @JvmRecord
    data class InputStamp(val sessionId: UUID?, val renderRevision: Long, val impulseId: Long)

    @JvmRecord data class Select(val stamp: InputStamp?, val choiceId: String?) : DialogueInput

    @JvmRecord data class Confirm(val stamp: InputStamp?, val choiceId: String?) : DialogueInput

    @JvmRecord
    data class RevealFinished(val sessionId: UUID?, val renderRevision: Long) : DialogueInput

    @JvmRecord data class Cancel(val reason: DialogueEndReason?) : DialogueInput
}
