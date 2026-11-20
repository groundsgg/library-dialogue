package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Thread-safe observation of the public core renderer port. */
class RecordingDialogueRenderer : DialogueRenderer {
    class PresentationSnapshot(
        private val sessionId: UUID?,
        private val audienceId: String?,
        private val hostGeneration: Long,
        views: List<DialogueView>,
        configurations: List<PresentationPreferences>,
        private val closed: Boolean,
        private val closeCalls: Int,
        private val rejectedWrites: Int,
    ) {
        private val views = java.util.List.copyOf(views)
        private val configurations = java.util.List.copyOf(configurations)

        fun sessionId(): UUID? = sessionId

        fun audienceId(): String? = audienceId

        fun hostGeneration(): Long = hostGeneration

        fun views(): List<DialogueView> = views

        fun configurations(): List<PresentationPreferences> = configurations

        fun closed(): Boolean = closed

        fun closeCalls(): Int = closeCalls

        fun rejectedWrites(): Int = rejectedWrites

        override fun equals(other: Any?): Boolean =
            other is PresentationSnapshot &&
                sessionId == other.sessionId &&
                audienceId == other.audienceId &&
                hostGeneration == other.hostGeneration &&
                views == other.views &&
                configurations == other.configurations &&
                closed == other.closed &&
                closeCalls == other.closeCalls &&
                rejectedWrites == other.rejectedWrites

        override fun hashCode(): Int {
            var hash = (sessionId?.hashCode() ?: 0)
            hash = 31 * hash + (audienceId?.hashCode() ?: 0)
            hash = 31 * hash + hostGeneration.hashCode()
            hash = 31 * hash + views.hashCode()
            hash = 31 * hash + configurations.hashCode()
            hash = 31 * hash + closed.hashCode()
            hash = 31 * hash + closeCalls.hashCode()
            hash = 31 * hash + rejectedWrites.hashCode()
            return hash
        }

        override fun toString(): String =
            "PresentationSnapshot[sessionId=$sessionId, audienceId=$audienceId, hostGeneration=$hostGeneration, views=$views, configurations=$configurations, closed=$closed, closeCalls=$closeCalls, rejectedWrites=$rejectedWrites]"
    }

    private val entries = ArrayList<Entry>()

    private class Entry(
        val sessionId: UUID?,
        val audienceId: String?,
        val hostGeneration: Long,
        val state: RecordingState<DialogueView>,
    )

    @Synchronized
    override fun prepare(
        request: DialogueRenderer.PresentationRequest
    ): CompletionStage<DialogueRenderer.PresentationHandle> {
        val state = RecordingState<DialogueView>()
        state.configure(request.preferences)
        entries.add(
            Entry(
                request.sessionId,
                request.audienceId,
                request.hostGeneration,
                state,
            )
        )
        return CompletableFuture.completedFuture(
            object : DialogueRenderer.PresentationHandle {
                override fun replace(frame: DialogueView): CompletionStage<Void> =
                    synchronized(this@RecordingDialogueRenderer) { state.write(frame) }

                override fun reconfigure(
                    preferences: PresentationPreferences
                ): CompletionStage<Void> =
                    synchronized(this@RecordingDialogueRenderer) { state.configure(preferences) }

                override fun closeAsync(): CompletionStage<Void> =
                    synchronized(this@RecordingDialogueRenderer) { state.close() }
            }
        )
    }

    @Synchronized
    fun snapshots(): List<PresentationSnapshot> =
        entries
            .stream()
            .map { entry ->
                PresentationSnapshot(
                    entry.sessionId,
                    entry.audienceId,
                    entry.hostGeneration,
                    entry.state.frames,
                    entry.state.configurations,
                    entry.state.closed,
                    entry.state.closeCalls,
                    entry.state.rejectedWrites,
                )
            }
            .toList()
}
