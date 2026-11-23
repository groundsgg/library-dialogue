package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.text.Graphemes

/** Observes presented frames; skipped and throttled graphemes are never replayed. */
internal class TypingSound {
    private var previous: DialogueView? = null
    private var graphemes: List<String> = emptyList()
    private var lastSound: Long? = null

    fun observe(view: DialogueView, now: Long, suppressed: Boolean = false): Boolean {
        val before = previous
        previous = view
        if (
            before == null ||
                before.sessionId() != view.sessionId() ||
                before.nodeVisitId() != view.nodeVisitId() ||
                before.nodeId() != view.nodeId() ||
                before.text() != view.text() ||
                before.localeTag() != view.localeTag()
        ) {
            graphemes = Graphemes.split(view.text())
            lastSound = null
            return false
        }
        val start = before.revealedGraphemes().coerceIn(0, graphemes.size)
        val end = view.revealedGraphemes().coerceIn(0, graphemes.size)
        if (
            suppressed ||
                before.state() != DialogueView.State.REVEALING ||
                end <= start ||
                view.state() !in
                    setOf(DialogueView.State.REVEALING, DialogueView.State.WAITING_FOR_INPUT)
        )
            return false
        if (
            !(start until end).any {
                graphemes[it].codePoints().anyMatch(Character::isLetterOrDigit)
            }
        )
            return false
        if (lastSound?.let { now - it < 90_000_000 } == true) return false
        lastSound = now
        return true
    }

    fun reset() {
        previous = null
        graphemes = emptyList()
        lastSound = null
    }
}
