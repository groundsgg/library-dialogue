package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.port.OwnedResource
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Host-owned cancellation signal, valid before a runtime handle exists. */
class CancellationToken {
    private val id = UUID.randomUUID()
    private val reason = AtomicReference<DialogueEndReason>()
    private val listeners = ConcurrentHashMap.newKeySet<Listener>()

    private class Listener(private val callback: Consumer<DialogueEndReason>) {
        private val fired = AtomicBoolean()

        fun fire(reason: DialogueEndReason) {
            if (fired.compareAndSet(false, true)) callback.accept(reason)
        }
    }

    fun id(): UUID = id

    fun isCancelled(): Boolean = reason.get() != null

    fun reason(): DialogueEndReason? = reason.get()

    fun onCancel(callback: Consumer<DialogueEndReason>): OwnedResource {
        val listener = Listener(callback)
        listeners.add(listener)
        reason.get()?.let {
            listeners.remove(listener)
            listener.fire(it)
        }
        return OwnedResource {
            listeners.remove(listener)
            CompletableFuture.completedFuture(null)
        }
    }

    fun cancel(reason: DialogueEndReason) {
        if (!this.reason.compareAndSet(null, reason)) return
        var failure: RuntimeException? = null
        for (listener in listeners) {
            try {
                listener.fire(reason)
            } catch (cause: RuntimeException) {
                if (failure == null) failure = cause else failure.addSuppressed(cause)
            }
        }
        listeners.clear()
        failure?.let { throw it }
    }
}
