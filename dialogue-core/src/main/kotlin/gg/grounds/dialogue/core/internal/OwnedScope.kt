package gg.grounds.dialogue.core.internal

import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.core.runtime.DialogueFailure
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.function.Supplier

/** Queue-confined acquisition ownership. Closed resources are weakly remembered. */
class OwnedScope(
    private val queue: SerialCommandQueue,
    private val clock: DialogueScheduler,
    private val budget: Duration,
) {
    private val resources = ArrayList<OwnedResource>()
    private val collected = ReferenceQueue<OwnedResource>()
    private val released = HashMap<IdentityKey, CompletionStage<Void>>()
    private val completion = CompletableFuture<Void>()
    private val observation = completion.minimalCompletionStage()
    private var closed = false
    private var pending = 0
    private var cleaning = 0
    private var failure: Throwable? = null
    private var timeout: DialogueScheduler.ScheduledTask? = null

    private class IdentityKey(resource: OwnedResource, collected: ReferenceQueue<OwnedResource>) :
        WeakReference<OwnedResource>(resource, collected) {
        private val hash = System.identityHashCode(resource)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val owned = get() ?: return false
            return other is IdentityKey && owned === other.get()
        }
    }

    private fun record(error: Throwable) {
        val unwrapped = unwrap(error)
        val prior = failure
        if (prior == null) failure = unwrapped
        else if (prior !== unwrapped) prior.addSuppressed(unwrapped)
    }

    fun <T : OwnedResource> prepare(supplier: Supplier<CompletionStage<T>>): CompletionStage<T> {
        if (closed) return CompletableFuture.failedFuture(CancellationException("Scope closed"))
        pending++
        val result = CompletableFuture<T>()
        val stage =
            try {
                supplier.get() ?: throw NullPointerException()
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
        stage.whenComplete { value, error ->
            queue.execute {
                pending--
                if (value != null) {
                    if (closed) dispose(value) else resources.add(value)
                }
                when {
                    error != null -> {
                        if (unwrap(error) is OwnedResource.PreparationFailure) record(error)
                        result.completeExceptionally(error)
                    }
                    value == null ->
                        result.completeExceptionally(
                            NullPointerException("Preparation returned null resource")
                        )
                    closed ->
                        result.completeExceptionally(
                            CancellationException("Scope closed during preparation")
                        )
                    else -> result.complete(value)
                }
                checkCleanup()
            }
        }
        return result.minimalCompletionStage()
    }

    fun release(resource: OwnedResource): CompletionStage<Void> {
        resources.removeIf { it === resource }
        return dispose(resource)
    }

    fun closeAsync(): CompletionStage<Void> = closeWithin(budget)

    /** Shares the caller's existing cleanup deadline after a deferred release. */
    fun closeBy(deadlineNanos: Long): CompletionStage<Void> =
        closeWithin(
            minOf(budget, Duration.ofNanos((deadlineNanos - clock.nanoTime()).coerceAtLeast(0)))
        )

    private fun closeWithin(remaining: Duration): CompletionStage<Void> {
        if (closed) return observation
        closed = true
        timeout =
            clock.schedule(remaining) {
                queue.execute {
                    if (!completion.isDone) {
                        completion.completeExceptionally(
                            DialogueFailure(
                                "CLEANUP_TIMEOUT",
                                "Owned cleanup could not be confirmed before deadline",
                            )
                        )
                    }
                }
            }
        val reverse = resources.reversed()
        resources.clear()
        reverse.forEach(::dispose)
        checkCleanup()
        return observation
    }

    private fun dispose(resource: OwnedResource): CompletionStage<Void> {
        while (true) {
            val key = collected.poll() ?: break
            released.remove(key)
        }
        val lookup = IdentityKey(resource, collected)
        released[lookup]?.let {
            return it
        }
        val done = CompletableFuture<Void>()
        val view = done.minimalCompletionStage()
        released[lookup] = view
        cleaning++
        val stage =
            try {
                val closing: CompletionStage<Void>? = resource.closeAsync()
                closing ?: throw NullPointerException()
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
        stage.whenComplete { _, error ->
            queue.execute {
                cleaning--
                if (error != null) {
                    record(error)
                    done.completeExceptionally(error)
                } else done.complete(null)
                checkCleanup()
            }
        }
        return view
    }

    private fun checkCleanup() {
        if (closed && pending == 0 && cleaning == 0) {
            timeout?.cancel()
            released.clear()
            val cause = failure
            if (cause == null) completion.complete(null)
            else completion.completeExceptionally(cause)
        }
    }

    companion object {
        @JvmStatic
        fun unwrap(error: Throwable): Throwable {
            var current = error
            while (current is CompletionException || current is ExecutionException) {
                current = current.cause ?: break
            }
            return current
        }
    }
}
