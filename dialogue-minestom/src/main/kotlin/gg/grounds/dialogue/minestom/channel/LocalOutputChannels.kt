package gg.grounds.dialogue.minestom.channel

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiFunction
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/**
 * Local registered layers, with serialized asynchronous writes and no foreign calls under locks.
 */
class LocalOutputChannels(
    private val hudSink: BiFunction<Player, Component, CompletionStage<Void>>,
    private val effectsSupported: Boolean,
    private val effectSink: BiFunction<Player, List<Key>, CompletionStage<Void>>,
) : OutputChannels {
    private val lock = Any()
    private val players = IdentityHashMap<Player, PlayerState>()
    private var sequence = 0L
    private val expiredPlayers = ReferenceQueue<Player>()
    private val generations = HashMap<PlayerKey, Long>()

    private class PlayerKey(player: Player, queue: ReferenceQueue<Player>) :
        WeakReference<Player>(player, queue) {
        private val hash = System.identityHashCode(player)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean =
            this === other || (other is PlayerKey && get() != null && get() === other.get())
    }

    override fun supportedEffects(): Boolean = effectsSupported

    private inner class PlayerState(val player: Player, generation: Long) {
        val hud = HashMap<Long, Channel<Component>>()
        val effects = HashMap<Long, Channel<List<Key>>>()
        @Volatile var latest = generation
        @Volatile var retired = false
        var sources = 0
        var pending = 0
        var inflight = 0
    }

    private fun state(player: Player, generation: Long): PlayerState {
        require(generation >= 0) { "generation" }
        while (true) {
            val expired = expiredPlayers.poll() ?: break
            if (expired is PlayerKey) generations.remove(expired)
        }
        val key = PlayerKey(player, expiredPlayers)
        val known = generations[key]
        if (known != null && generation < known) throw IllegalArgumentException("STALE_CONNECTION")
        generations[key] = generation
        val state = players.computeIfAbsent(player) { PlayerState(player, generation) }
        if (generation < state.latest) throw IllegalArgumentException("STALE_CONNECTION")
        state.latest = generation
        return state
    }

    override fun acquireHud(
        player: Player,
        generation: Long,
        priority: Int,
    ): OutputChannels.HudLease =
        synchronized(lock) {
            val state = state(player, generation)
            val channel =
                state.hud.computeIfAbsent(generation) {
                    Channel(state, generation, true, hudSink)
                }
            val source = HudSource(channel, priority)
            channel.sources[source.id] = source
            state.sources++
            source
        }

    override fun acquireEffects(player: Player, generation: Long): OutputChannels.EffectLease {
        if (!effectsSupported) throw UnsupportedOperationException("POST_EFFECTS_UNAVAILABLE")
        return synchronized(lock) {
            val state = state(player, generation)
            val channel =
                state.effects.computeIfAbsent(generation) {
                    Channel(state, generation, false, effectSink)
                }
            val source = EffectSource(channel)
            channel.sources[source.id] = source
            state.sources++
            source
        }
    }

    private fun retire(state: PlayerState) {
        if (state.sources == 0 && state.pending == 0 && state.inflight == 0) {
            state.retired = true
            players.remove(state.player, state)
        }
    }

    private inner class HudSource(channel: Channel<Component>, priority: Int) :
        Source<Component>(channel, priority), OutputChannels.HudLease {
        override fun visible(): Boolean {
            if (closed || !channel.current()) return false
            return synchronized(lock) {
                channel.sources.values
                    .filter { it.value != null && !it.closed }
                    .maxWithOrNull(
                        compareBy<Source<Component>> { it.priority }.thenBy { it.id }
                    ) === this
            }
        }
    }

    private inner class EffectSource(channel: Channel<List<Key>>) :
        Source<List<Key>>(channel, 0), OutputChannels.EffectLease {
        override fun replace(value: List<Key>): CompletionStage<Void> =
            super.replace(value.toList())
    }

    private open inner class Source<T>(val channel: Channel<T>, val priority: Int) :
        OutputChannels.Lease<T> {
        val id = ++sequence
        var value: T? = null
        @Volatile var closed = false
        private val closeResult = CompletableFuture<Void>()
        private val closeView = closeResult.minimalCompletionStage()

        override fun replace(value: T): CompletionStage<Void> {
            val result = CompletableFuture<Void>()
            synchronized(lock) {
                if (closed || channel.state.latest != channel.generation || channel.state.retired) {
                    result.completeExceptionally(CancellationException("Layer closed"))
                    return result.minimalCompletionStage()
                }
                if (channel.queue.size >= 128) {
                    result.completeExceptionally(IllegalStateException("OUTPUT_QUEUE_FULL"))
                    return result.minimalCompletionStage()
                }
                this.value = value
                channel.enqueue(Operation(this, false, result))
            }
            channel.pump()
            return result.minimalCompletionStage()
        }

        override fun closeAsync(): CompletionStage<Void> {
            synchronized(lock) {
                if (closed) return closeView
                closed = true
                channel.sources.remove(id)
                channel.state.sources--
                channel.enqueue(Operation(this, true, closeResult))
            }
            channel.pump()
            return closeView
        }
    }

    private inner class Operation<T>(
        val source: Source<T>,
        val closing: Boolean,
        val result: CompletableFuture<Void>,
    )

    private inner class Channel<T>(
        val state: PlayerState,
        val generation: Long,
        private val isHud: Boolean,
        private val sink: BiFunction<Player, T, CompletionStage<Void>>,
    ) {
        private val instance: Instance? = state.player.instance
        val sources = LinkedHashMap<Long, Source<T>>()
        val queue = ArrayDeque<Operation<T>>()
        private val work = AtomicInteger()
        private var writing = false

        fun enqueue(operation: Operation<T>) {
            queue.add(operation)
            state.pending++
        }

        fun current(): Boolean =
            !state.retired &&
                state.latest == generation &&
                state.player.isOnline &&
                state.player.instance === instance

        @Suppress("UNCHECKED_CAST")
        private fun payload(): T {
            if (isHud) {
                return (sources.values
                    .filter { it.value != null && !it.closed }
                    .maxWithOrNull(compareBy<Source<T>> { it.priority }.thenBy { it.id })
                    ?.value ?: Component.empty())
                    as T
            }
            val keys = LinkedHashSet<Key>()
            for (source in sources.values) {
                if (!source.closed && source.value != null) {
                    keys.addAll(source.value as List<Key>)
                }
            }
            return keys.toList() as T
        }

        fun pump() {
            if (work.getAndIncrement() != 0) return
            var missed = 1
            do {
                var operation: Operation<T>? = null
                var value: T? = null
                synchronized(lock) {
                    if (!writing && queue.isNotEmpty()) {
                        operation = queue.remove()
                        state.pending--
                        writing = true
                        state.inflight++
                        value = payload()
                    }
                }
                val pendingOperation = operation
                if (pendingOperation != null) {
                    if (
                        !current() || (!pendingOperation.closing && pendingOperation.source.closed)
                    ) {
                        finish(
                            pendingOperation,
                            if (pendingOperation.closing) null
                            else CancellationException("Stale output"),
                        )
                    } else {
                        val stage =
                            try {
                                requireNotNull(sink.apply(state.player, value as T))
                            } catch (error: Throwable) {
                                CompletableFuture.failedFuture(error)
                            }
                        stage.whenComplete { _, error -> finish(pendingOperation, error) }
                    }
                }
                missed = work.addAndGet(-missed)
            } while (missed != 0)
        }

        private fun finish(operation: Operation<T>, error: Throwable?) {
            synchronized(lock) {
                writing = false
                state.inflight--
                retire(state)
            }
            if (error == null) operation.result.complete(null)
            else operation.result.completeExceptionally(error)
            pump()
        }
    }
}
