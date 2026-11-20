package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.port.ActionHandler.ActionRef
import java.util.UUID

class DialogueEnd(
    private val sessionId: UUID?,
    private val reason: DialogueEndReason?,
    pendingActions: List<ActionRef>,
) {
    private val pendingActions = java.util.List.copyOf(pendingActions)

    fun sessionId(): UUID? = sessionId

    fun reason(): DialogueEndReason? = reason

    fun pendingActions(): List<ActionRef> = pendingActions

    override fun equals(other: Any?): Boolean =
        other is DialogueEnd &&
            sessionId == other.sessionId &&
            reason == other.reason &&
            pendingActions == other.pendingActions

    override fun hashCode(): Int {
        var hash = (sessionId?.hashCode() ?: 0)
        hash = 31 * hash + (reason?.hashCode() ?: 0)
        hash = 31 * hash + pendingActions.hashCode()
        return hash
    }

    override fun toString(): String =
        "DialogueEnd[sessionId=$sessionId, reason=$reason, pendingActions=$pendingActions]"
}
