package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.DialogueView.ChoiceView
import java.util.UUID
import java.util.concurrent.CompletionStage

class ChoiceViewport {
    /** Optional presentation capability; browsing never submits a core selection. */
    fun interface Controller {
        fun setChoiceStart(expected: DialogueView, start: Int): CompletionStage<Boolean>
    }

    /** Owned by one presentation; reveal/locale refreshes preserve an explicit page. */
    class Cursor {
        private var session: UUID? = null
        private var visit = 0L
        private var selection: String? = null
        private var start = 0

        fun startFor(view: DialogueView, capacity: Int): Int {
            val viewport = ChoiceViewport()
            start =
                if (
                    view.sessionId() != session ||
                        view.nodeVisitId() != visit ||
                        view.selectedChoiceId() != selection
                ) {
                    viewport.window(view.choices(), view.selectedChoiceId(), capacity).start()
                } else {
                    viewport
                        .window(view.choices(), view.selectedChoiceId(), capacity, start)
                        .start()
                }
            session = view.sessionId()
            visit = view.nodeVisitId()
            selection = view.selectedChoiceId()
            return start
        }

        fun browse(view: DialogueView, capacity: Int, next: Int): Boolean {
            startFor(view, capacity)
            if (next < 0 || next % capacity != 0 || next >= view.choices().size) return false
            start = next
            return true
        }
    }

    class ChoiceWindow(start: Int, choices: List<ChoiceView>, selectedIndex: Int, total: Int) {
        private val start = start
        private val choices = java.util.List.copyOf(choices)
        private val selectedIndex = selectedIndex
        private val total = total

        fun start(): Int = start

        fun choices(): List<ChoiceView> = choices

        fun selectedIndex(): Int = selectedIndex

        fun total(): Int = total

        override fun equals(other: Any?): Boolean =
            other is ChoiceWindow &&
                start == other.start &&
                choices == other.choices &&
                selectedIndex == other.selectedIndex &&
                total == other.total

        override fun hashCode(): Int =
            ((start * 31 + choices.hashCode()) * 31 + selectedIndex) * 31 + total

        override fun toString(): String =
            "ChoiceWindow[start=$start, choices=$choices, selectedIndex=$selectedIndex, total=$total]"
    }

    fun window(choices: List<ChoiceView>, selectedId: String?, capacity: Int): ChoiceWindow =
        window(choices, selectedId, capacity, null)

    fun window(
        choices: List<ChoiceView>,
        selectedId: String?,
        capacity: Int,
        requestedStart: Int?,
    ): ChoiceWindow {
        require(capacity >= 1) { "Positive viewport capacity required" }
        if (choices.isEmpty()) return ChoiceWindow(0, emptyList(), -1, 0)
        val selected = choices.indexOfFirst { it.id == selectedId }
        if (selected < 0 && selectedId != null)
            throw IllegalArgumentException("Selection is not offered")
        val start =
            requestedStart?.let {
                minOf(maxOf(0, it) / capacity * capacity, (choices.size - 1) / capacity * capacity)
            } ?: (maxOf(0, selected) / capacity * capacity)
        return ChoiceWindow(
            start,
            choices.subList(start, minOf(start + capacity, choices.size)),
            if (selected < start || selected >= start + capacity) -1 else selected - start,
            choices.size,
        )
    }
}
