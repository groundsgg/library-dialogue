package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.WidgetFrame
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Thread-safe observation of the shared presentation surface port. */
class RecordingDialogueSurface : DialogueSurface {
    class SurfaceSnapshot(
        private val sessionId: UUID?,
        private val audienceId: String?,
        private val hostGeneration: Long,
        private val surfaceKey: String?,
        private val generation: Long,
        frames: List<WidgetFrame>,
        configurations: List<PresentationPreferences>,
        private val closed: Boolean,
        private val closeCalls: Int,
        private val rejectedWrites: Int,
    ) {
        private val frames = java.util.List.copyOf(frames)
        private val configurations = java.util.List.copyOf(configurations)

        fun sessionId(): UUID? = sessionId

        fun audienceId(): String? = audienceId

        fun hostGeneration(): Long = hostGeneration

        fun surfaceKey(): String? = surfaceKey

        fun generation(): Long = generation

        fun frames(): List<WidgetFrame> = frames

        fun configurations(): List<PresentationPreferences> = configurations

        fun closed(): Boolean = closed

        fun closeCalls(): Int = closeCalls

        fun rejectedWrites(): Int = rejectedWrites

        override fun equals(other: Any?): Boolean =
            other is SurfaceSnapshot &&
                sessionId == other.sessionId &&
                audienceId == other.audienceId &&
                hostGeneration == other.hostGeneration &&
                surfaceKey == other.surfaceKey &&
                generation == other.generation &&
                frames == other.frames &&
                configurations == other.configurations &&
                closed == other.closed &&
                closeCalls == other.closeCalls &&
                rejectedWrites == other.rejectedWrites

        override fun hashCode(): Int {
            var hash = (sessionId?.hashCode() ?: 0)
            hash = 31 * hash + (audienceId?.hashCode() ?: 0)
            hash = 31 * hash + hostGeneration.hashCode()
            hash = 31 * hash + (surfaceKey?.hashCode() ?: 0)
            hash = 31 * hash + generation.hashCode()
            hash = 31 * hash + frames.hashCode()
            hash = 31 * hash + configurations.hashCode()
            hash = 31 * hash + closed.hashCode()
            hash = 31 * hash + closeCalls.hashCode()
            hash = 31 * hash + rejectedWrites.hashCode()
            return hash
        }

        override fun toString(): String =
            "SurfaceSnapshot[sessionId=$sessionId, audienceId=$audienceId, hostGeneration=$hostGeneration, surfaceKey=$surfaceKey, generation=$generation, frames=$frames, configurations=$configurations, closed=$closed, closeCalls=$closeCalls, rejectedWrites=$rejectedWrites]"
    }

    private val entries = ArrayList<Entry>()

    private class Entry(
        val sessionId: UUID?,
        val audienceId: String?,
        val hostGeneration: Long,
        val surfaceKey: String?,
        val generation: Long,
        val state: RecordingState<WidgetFrame>,
    )

    @Synchronized
    override fun open(
        request: DialogueSurface.SurfaceContext
    ): CompletionStage<DialogueSurface.SurfaceHandle> {
        val state = RecordingState<WidgetFrame>()
        state.configure(request.preferences)
        entries.add(
            Entry(
                request.sessionId,
                request.audienceId,
                request.hostGeneration,
                request.surfaceKey,
                request.generation,
                state,
            )
        )
        return CompletableFuture.completedFuture(
            object : DialogueSurface.SurfaceHandle {
                override fun replace(frame: WidgetFrame): CompletionStage<Void> =
                    synchronized(this@RecordingDialogueSurface) { state.write(frame) }

                override fun configure(
                    preferences: PresentationPreferences
                ): CompletionStage<Void> =
                    synchronized(this@RecordingDialogueSurface) { state.configure(preferences) }

                override fun closeAsync(): CompletionStage<Void> =
                    synchronized(this@RecordingDialogueSurface) { state.close() }
            }
        )
    }

    @Synchronized
    fun snapshots(): List<SurfaceSnapshot> =
        entries
            .stream()
            .map { entry ->
                SurfaceSnapshot(
                    entry.sessionId,
                    entry.audienceId,
                    entry.hostGeneration,
                    entry.surfaceKey,
                    entry.generation,
                    entry.state.frames,
                    entry.state.configurations,
                    entry.state.closed,
                    entry.state.closeCalls,
                    entry.state.rejectedWrites,
                )
            }
            .toList()
}
