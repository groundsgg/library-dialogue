package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.port.ActionHandler.ActionRef
import gg.grounds.dialogue.core.validation.ContentPin
import java.util.UUID

class DialogueView(
    private val sessionId: UUID,
    private val contentPin: ContentPin,
    private val nodeId: String,
    private val nodeVisitId: Long,
    private val state: State,
    private val stateRevision: Long,
    private val renderRevision: Long,
    private val localeTag: String,
    private val speaker: String,
    private val text: String,
    choices: List<ChoiceView>,
    private val selectedChoiceId: String?,
    private val totalGraphemes: Int,
    private val revealedGraphemes: Int,
    private val hint: HintPhase,
    private val notice: String,
    private val pendingAction: ActionRef?,
) {
    private val choices = java.util.List.copyOf(choices)

    fun sessionId(): UUID = sessionId

    fun contentPin(): ContentPin = contentPin

    fun nodeId(): String = nodeId

    fun nodeVisitId(): Long = nodeVisitId

    fun state(): State = state

    fun stateRevision(): Long = stateRevision

    fun renderRevision(): Long = renderRevision

    fun localeTag(): String = localeTag

    fun speaker(): String = speaker

    fun text(): String = text

    fun choices(): List<ChoiceView> = choices

    fun selectedChoiceId(): String? = selectedChoiceId

    fun totalGraphemes(): Int = totalGraphemes

    fun revealedGraphemes(): Int = revealedGraphemes

    fun hint(): HintPhase = hint

    fun notice(): String = notice

    fun pendingAction(): ActionRef? = pendingAction

    override fun equals(other: Any?): Boolean =
        other is DialogueView &&
            sessionId == other.sessionId &&
            contentPin == other.contentPin &&
            nodeId == other.nodeId &&
            nodeVisitId == other.nodeVisitId &&
            state == other.state &&
            stateRevision == other.stateRevision &&
            renderRevision == other.renderRevision &&
            localeTag == other.localeTag &&
            speaker == other.speaker &&
            text == other.text &&
            choices == other.choices &&
            selectedChoiceId == other.selectedChoiceId &&
            totalGraphemes == other.totalGraphemes &&
            revealedGraphemes == other.revealedGraphemes &&
            hint == other.hint &&
            notice == other.notice &&
            pendingAction == other.pendingAction

    override fun hashCode(): Int {
        var hash = (sessionId?.hashCode() ?: 0)
        hash = 31 * hash + (contentPin?.hashCode() ?: 0)
        hash = 31 * hash + (nodeId?.hashCode() ?: 0)
        hash = 31 * hash + nodeVisitId.hashCode()
        hash = 31 * hash + (state?.hashCode() ?: 0)
        hash = 31 * hash + stateRevision.hashCode()
        hash = 31 * hash + renderRevision.hashCode()
        hash = 31 * hash + (localeTag?.hashCode() ?: 0)
        hash = 31 * hash + (speaker?.hashCode() ?: 0)
        hash = 31 * hash + (text?.hashCode() ?: 0)
        hash = 31 * hash + choices.hashCode()
        hash = 31 * hash + (selectedChoiceId?.hashCode() ?: 0)
        hash = 31 * hash + totalGraphemes.hashCode()
        hash = 31 * hash + revealedGraphemes.hashCode()
        hash = 31 * hash + (hint?.hashCode() ?: 0)
        hash = 31 * hash + (notice?.hashCode() ?: 0)
        hash = 31 * hash + (pendingAction?.hashCode() ?: 0)
        return hash
    }

    override fun toString(): String =
        "DialogueView[sessionId=$sessionId, contentPin=$contentPin, nodeId=$nodeId, nodeVisitId=$nodeVisitId, state=$state, stateRevision=$stateRevision, renderRevision=$renderRevision, localeTag=$localeTag, speaker=$speaker, text=$text, choices=$choices, selectedChoiceId=$selectedChoiceId, totalGraphemes=$totalGraphemes, revealedGraphemes=$revealedGraphemes, hint=$hint, notice=$notice, pendingAction=$pendingAction]"

    enum class State {
        PREPARING,
        REVEALING,
        WAITING_FOR_INPUT,
        WAITING_FOR_PROVIDER,
        CLOSING,
        CLOSED,
    }

    enum class HintPhase {
        REVEAL,
        CHOOSE,
        CONTINUE,
        FINISH,
        BUSY,
    }

    enum class Availability {
        AVAILABLE,
        UNAVAILABLE,
    }

    @JvmRecord
    data class ChoiceView(
        val id: String,
        val label: String,
        val availability: Availability,
        val unavailableReason: String?,
    )
}
