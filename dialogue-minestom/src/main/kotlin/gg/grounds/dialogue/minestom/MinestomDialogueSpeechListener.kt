package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.runtime.DialogueView

/** Receives naturally presented dialogue text while a message is being revealed. */
interface MinestomDialogueSpeechListener {
    fun onRevealed(event: MinestomDialogueSpeechEvent)

    fun onStop(reason: StopReason)

    enum class StopReason {
        PAUSED,
        CONTENT_REPLACED,
        PRESENTATION_FALLBACK,
        PRESENTATION_FAILED,
        SESSION_ENDED,
        SESSION_FAILED,
        DISCONNECTED,
        LISTENER_FAILED,
    }

    companion object {
        @JvmField
        val NO_OP: MinestomDialogueSpeechListener =
            object : MinestomDialogueSpeechListener {
                override fun onRevealed(event: MinestomDialogueSpeechEvent) = Unit

                override fun onStop(reason: StopReason) = Unit
            }
    }
}

/** A naturally revealed grapheme delta for one message and its resolved speaker. */
class MinestomDialogueSpeechEvent(
    private val view: DialogueView,
    private val speakerRole: String,
    private val speaker: MinestomSpeakerBinding,
    private val revealed: String,
) {
    fun view(): DialogueView = view

    fun speakerRole(): String = speakerRole

    fun speaker(): MinestomSpeakerBinding = speaker

    fun revealed(): String = revealed
}
