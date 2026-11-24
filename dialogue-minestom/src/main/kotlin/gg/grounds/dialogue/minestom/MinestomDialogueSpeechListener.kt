package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.TypingTiming

/**
 * Optional application hooks for speaker-specific presentation effects.
 *
 * Both callbacks run on the player's instance scheduler. [onRevealed] reports only grapheme deltas
 * from successfully presented NPC-surface REVEALING frames; skipped text and the final
 * WAITING_FOR_INPUT frame are excluded. [MinestomDialogueSpeechEvent.speakerRole] is the dialogue
 * graph role key, not its localized display label. An ordinary stop may be followed by a terminal
 * stop, so cleanup should be idempotent. Exceptions disable these optional hooks without failing
 * the dialogue.
 */
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

/** The first positive NPC frame presented before the authoritative typing deadline. */
class MinestomDialogueSpeechBeginEvent(
    private val view: DialogueView,
    private val speakerRole: String,
    private val speaker: MinestomSpeakerBinding,
    private val timing: TypingTiming,
) {
    fun view(): DialogueView = view

    fun speakerRole(): String = speakerRole

    fun speaker(): MinestomSpeakerBinding = speaker

    fun timing(): TypingTiming = timing
}

/** Opt-in hooks for authoritative typing phase deadlines, preserving legacy listener behavior. */
interface MinestomDialogueTimedSpeechListener : MinestomDialogueSpeechListener {
    fun onBegin(event: MinestomDialogueSpeechBeginEvent)
}
