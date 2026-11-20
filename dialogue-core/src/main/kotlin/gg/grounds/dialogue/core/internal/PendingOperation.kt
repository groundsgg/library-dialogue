package gg.grounds.dialogue.core.internal

import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.runtime.DialogueFailure
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.function.BiConsumer

/**
 * Bounds waiting without asserting cancellation of an external side effect. All state is
 * queue-confined.
 */
class PendingOperation<T>(
    queue: SerialCommandQueue,
    clock: DialogueScheduler,
    budget: Duration,
    stage: CompletionStage<T>,
    callback: BiConsumer<T?, Throwable?>,
) {
    private var done = false
    private var callback: BiConsumer<T?, Throwable?>? = callback
    private var timer: DialogueScheduler.ScheduledTask? = null

    init {
        timer =
            clock.schedule(budget) {
                queue.execute {
                    finish(null, DialogueFailure("PROVIDER_TIMEOUT", "Provider deadline elapsed"))
                }
            }
        stage.whenComplete { value, failure -> queue.execute { finish(value, failure) } }
    }

    private fun finish(value: T?, error: Throwable?) {
        if (done) return
        done = true
        timer?.cancel()
        val completed = checkNotNull(callback)
        callback = null
        completed.accept(value, error)
    }

    fun isDone(): Boolean = done

    fun cancel() {
        done = true
        timer?.cancel()
        callback = null
    }
}
