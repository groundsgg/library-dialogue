package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Access under the owning recorder monitor. */
internal class RecordingState<T : Any> {
    val frames = ArrayList<T>()
    val configurations = ArrayList<PresentationPreferences>()
    private val cleanup = CompletableFuture.completedFuture<Void>(null).minimalCompletionStage()
    var closed = false
        private set

    var closeCalls = 0
        private set

    var rejectedWrites = 0
        private set

    fun write(frame: T): CompletionStage<Void> {
        if (closed) return rejected()
        frames.add(frame)
        return CompletableFuture.completedFuture(null)
    }

    fun configure(preferences: PresentationPreferences): CompletionStage<Void> {
        if (closed) return rejected()
        configurations.add(preferences)
        return CompletableFuture.completedFuture(null)
    }

    private fun rejected(): CompletionStage<Void> {
        rejectedWrites++
        return CompletableFuture.failedFuture(IllegalStateException("Recording handle is closed"))
    }

    fun close(): CompletionStage<Void> {
        closeCalls++
        closed = true
        return cleanup
    }
}
