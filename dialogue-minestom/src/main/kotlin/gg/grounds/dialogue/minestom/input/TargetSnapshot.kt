package gg.grounds.dialogue.minestom.input

import gg.grounds.dialogue.presentation.WidgetFrame
import java.util.Collections
import java.util.HashMap
import java.util.UUID

class TargetSnapshot(
    private val sessionId: UUID,
    private val renderRevision: Long,
    private val nodeVisitId: Long,
    private val surfaceGeneration: Long,
    choices: Map<String, WidgetFrame.Rect>,
    private val stateRevision: Long,
    navigation: Map<WidgetFrame.NavigationDirection, WidgetFrame.NavigationButton>,
) {
    private val choices = Collections.unmodifiableMap(HashMap(choices))
    private val navigation = Collections.unmodifiableMap(HashMap(navigation))

    constructor(
        sessionId: UUID,
        renderRevision: Long,
        nodeVisitId: Long,
        surfaceGeneration: Long,
        choices: Map<String, WidgetFrame.Rect>,
        stateRevision: Long,
    ) : this(
        sessionId,
        renderRevision,
        nodeVisitId,
        surfaceGeneration,
        choices,
        stateRevision,
        emptyMap(),
    )

    constructor(
        sessionId: UUID,
        renderRevision: Long,
        nodeVisitId: Long,
        surfaceGeneration: Long,
        choices: Map<String, WidgetFrame.Rect>,
    ) : this(sessionId, renderRevision, nodeVisitId, surfaceGeneration, choices, -1)

    fun sessionId(): UUID = sessionId

    fun renderRevision(): Long = renderRevision

    fun nodeVisitId(): Long = nodeVisitId

    fun surfaceGeneration(): Long = surfaceGeneration

    fun choices(): Map<String, WidgetFrame.Rect> = choices

    fun stateRevision(): Long = stateRevision

    fun navigation(): Map<WidgetFrame.NavigationDirection, WidgetFrame.NavigationButton> =
        navigation

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is TargetSnapshot &&
                sessionId == other.sessionId &&
                renderRevision == other.renderRevision &&
                nodeVisitId == other.nodeVisitId &&
                surfaceGeneration == other.surfaceGeneration &&
                choices == other.choices &&
                stateRevision == other.stateRevision &&
                navigation == other.navigation)

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + renderRevision.hashCode()
        result = 31 * result + nodeVisitId.hashCode()
        result = 31 * result + surfaceGeneration.hashCode()
        result = 31 * result + choices.hashCode()
        result = 31 * result + stateRevision.hashCode()
        return 31 * result + navigation.hashCode()
    }

    override fun toString(): String =
        "TargetSnapshot[sessionId=$sessionId, renderRevision=$renderRevision, " +
            "nodeVisitId=$nodeVisitId, surfaceGeneration=$surfaceGeneration, choices=$choices, " +
            "stateRevision=$stateRevision, navigation=$navigation]"

    @JvmRecord
    data class Target(
        val sessionId: UUID,
        val renderRevision: Long,
        val nodeVisitId: Long,
        val surfaceGeneration: Long,
        val choiceId: String?,
    )
}
