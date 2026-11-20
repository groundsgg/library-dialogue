package gg.grounds.dialogue.minestom.input

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.presentation.WidgetFrame
import java.util.EnumMap
import java.util.HashSet
import java.util.NavigableMap
import java.util.TreeMap
import java.util.function.BooleanSupplier

/** Successfully published frames, owned and retired by their exact surface generation. */
class SurfaceInputTargets {
    @JvmRecord
    private data class Published(
        val state: Long,
        val render: Long,
        val snapshot: TargetSnapshot?,
        val valid: BooleanSupplier,
    )

    private val live: NavigableMap<Long, Published> = TreeMap()
    private var highWater = -1L

    fun publish(
        frame: WidgetFrame,
        generation: Long,
        choices: Map<String, WidgetFrame.Rect>,
    ) {
        publish(frame, generation, choices) { true }
    }

    @Synchronized
    fun publish(
        frame: WidgetFrame,
        generation: Long,
        choices: Map<String, WidgetFrame.Rect>,
        valid: BooleanSupplier,
    ) {
        val old = live[generation]
        val view = frame.semantic()
        if (old == null && generation <= highWater) return
        if (
            old != null &&
                (view.stateRevision() < old.state ||
                    (view.stateRevision() == old.state && view.renderRevision() < old.render))
        ) {
            return
        }
        val allowed = HashSet<String>()
        view
            .choices()
            .filter { it.availability == DialogueView.Availability.AVAILABLE }
            .forEach { allowed.add(it.id) }
        if (!allowed.containsAll(choices.keys)) throw IllegalArgumentException("UNISSUED_CHOICE")
        val navigation =
            EnumMap<WidgetFrame.NavigationDirection, WidgetFrame.NavigationButton>(
                WidgetFrame.NavigationDirection::class.java
            )
        frame.scrollbar()?.buttons()?.forEach { button ->
            if (button.choiceId != null && !allowed.contains(button.choiceId)) {
                throw IllegalArgumentException("UNISSUED_NAVIGATION_CHOICE")
            }
            val pageStart = button.pageStart
            if (pageStart != null && pageStart >= view.choices().size) {
                throw IllegalArgumentException("UNISSUED_NAVIGATION_PAGE")
            }
            navigation[button.direction] = button
        }
        live[generation] =
            Published(
                view.stateRevision(),
                view.renderRevision(),
                TargetSnapshot(
                    view.sessionId(),
                    view.renderRevision(),
                    view.nodeVisitId(),
                    generation,
                    choices,
                    view.stateRevision(),
                    navigation,
                ),
                valid,
            )
        highWater = maxOf(highWater, generation)
    }

    @Synchronized
    fun invalidateCurrent(generation: Long) {
        val old = live[generation]
        if (old != null) live[generation] = Published(old.state, old.render, null, old.valid)
    }

    @Synchronized
    fun clear(generation: Long) {
        highWater = maxOf(highWater, generation)
        live.remove(generation)
    }

    fun current(): TargetSnapshot? {
        val issued = synchronized(this) { if (live.isEmpty()) null else live.lastEntry().value }
        val snapshot = issued?.snapshot
        if (snapshot == null || !issued.valid.asBoolean) return null
        return synchronized(this) {
            if (live[snapshot.surfaceGeneration()] === issued) snapshot else null
        }
    }
}
