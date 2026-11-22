package gg.grounds.dialogue.minestom.channel

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiFunction
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

internal val DIALOGUE_VIGNETTE_KEYS =
    (1..4).map { Key.key("dialogue:vignette_fade_$it") } + Key.key("dialogue:vignette")

internal enum class NativeVignetteBank(suffix: String) {
    A("a"),
    B("b");

    val enter = Key.key("dialogue:vignette_250_enter_$suffix")
    val exit = Key.key("dialogue:vignette_250_exit_$suffix")

    fun other(): NativeVignetteBank = if (this == A) B else A
}

private val NATIVE_ENTERS = NativeVignetteBank.entries.map { it.enter }.toSet()
private val NATIVE_KEYS = NativeVignetteBank.entries.flatMap { listOf(it.enter, it.exit) }.toSet()

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
    private val nativeDispatches = WeakHashMap<Player, NativeDispatch>()
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
            val world = player.instance
            val existing = state.effects[generation]
            val channel =
                if (existing != null && existing.rebindEffects(world)) existing
                else
                    Channel(state, generation, false, effectSink).also {
                        state.effects[generation] = it
                        existing?.retire()
                    }
            val source = EffectSource(channel, world)
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

    private class NativeActivation(val requestedBank: NativeVignetteBank, val sourceId: Long)

    private data class NativeDispatch(
        val activation: NativeActivation,
        val bank: NativeVignetteBank,
    )

    private inner class EffectSource(channel: Channel<List<Key>>, val instance: Instance?) :
        Source<List<Key>>(channel, 0), OutputChannels.EffectLease {
        var nativeActivation: NativeActivation? = null
            private set

        override fun replace(value: List<Key>): CompletionStage<Void> =
            super.replace(value.toList())

        override fun update(value: List<Key>) {
            super.update(value)
            val requested = NativeVignetteBank.entries.firstOrNull { it.enter in value }
            if (requested != nativeActivation?.requestedBank) {
                nativeActivation = requested?.let { NativeActivation(it, id) }
            }
        }
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
                update(value)
                channel.enqueue(Operation(this, false, result))
            }
            channel.pump()
            return result.minimalCompletionStage()
        }

        protected open fun update(value: T) {
            this.value = value
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
        private var instance: Instance? = state.player.instance
        private var replaced = false
        val sources = LinkedHashMap<Long, Source<T>>()
        val queue = ArrayDeque<Operation<T>>()
        private val work = AtomicInteger()
        private var writing = false

        fun enqueue(operation: Operation<T>) {
            queue.add(operation)
            state.pending++
        }

        private fun authoritative(): Boolean =
            !replaced && !state.retired && state.latest == generation && state.player.isOnline

        fun current(): Boolean = authoritative() && state.player.instance === instance

        fun retire() {
            replaced = true
        }

        fun rebindEffects(world: Instance?): Boolean {
            if (instance === world) return true
            if (!sameDimension(world)) return false
            instance = world
            return true
        }

        private fun sameDimension(world: Instance?): Boolean =
            world != null && instance?.dimensionName == world.dimensionName

        private fun canDispatch(operation: Operation<T>, world: Instance?): Boolean {
            if (!authoritative() || (!operation.closing && operation.source.closed)) return false
            val native = (operation.source as? EffectSource)?.takeIf { it.nativeActivation != null }
            if (world === instance) {
                return operation.closing || native == null || native.instance === world
            }
            // The client retains post effects across instance changes within one dimension.
            val lastNativeOwner = nativeDispatches[state.player]?.activation?.sourceId
            return operation.closing &&
                (native != null || lastNativeOwner == operation.source.id) &&
                sameDimension(world)
        }

        @Suppress("UNCHECKED_CAST")
        private fun payload(world: Instance?): T {
            if (isHud) {
                return (sources.values
                    .filter { it.value != null && !it.closed }
                    .maxWithOrNull(compareBy<Source<T>> { it.priority }.thenBy { it.id })
                    ?.value ?: Component.empty())
                    as T
            }
            val keys = LinkedHashSet<Key>()
            var selected: EffectSource? = null
            for (source in sources.values) {
                val layer = source.value as List<Key>? ?: continue
                if (source.closed) continue
                val effect = source as EffectSource
                if (effect.instance === world) {
                    keys.addAll(layer)
                    if (effect.nativeActivation != null) selected = effect
                } else {
                    keys.addAll(layer.filterNot { it in NATIVE_KEYS })
                }
            }
            // A legacy effect takes precedence over the native chain.
            val strongest = DIALOGUE_VIGNETTE_KEYS.lastOrNull(keys::contains)
            val vignette = strongest?.let(::listOf) ?: selected?.let(::nativeKeys).orEmpty()
            val merged = ArrayList<Key>()
            var vignetteAdded = false
            for (key in keys) {
                when {
                    key in DIALOGUE_VIGNETTE_KEYS || key in NATIVE_ENTERS -> {
                        if (!vignetteAdded) {
                            merged.addAll(vignette)
                            vignetteAdded = true
                        }
                    }
                    key !in NATIVE_KEYS -> merged.add(key)
                }
            }
            return merged as T
        }

        private fun nativeKeys(source: EffectSource): List<Key> {
            val activation = checkNotNull(source.nativeActivation)
            val previous = nativeDispatches[state.player]
            val bank =
                if (previous?.activation === activation) previous.bank
                else previous?.bank?.other() ?: activation.requestedBank
            // A queued activation can disappear before the sink sees it. Alternate using the
            // last dispatched bank so the client never reuses its current clock for a new entry.
            nativeDispatches[state.player] = NativeDispatch(activation, bank)
            return if (activation.requestedBank.exit in checkNotNull(source.value)) {
                listOf(bank.enter, bank.exit)
            } else listOf(bank.enter)
        }

        fun pump() {
            if (work.getAndIncrement() != 0) return
            var missed = 1
            do {
                var operation: Operation<T>? = null
                synchronized(lock) {
                    if (!writing && queue.isNotEmpty()) {
                        operation = queue.remove()
                        state.pending--
                        writing = true
                        state.inflight++
                    }
                }
                val pendingOperation = operation
                if (pendingOperation != null) {
                    val value =
                        synchronized(lock) {
                            val world = state.player.instance
                            if (canDispatch(pendingOperation, world)) payload(world) else null
                        }
                    if (value == null) {
                        finish(
                            pendingOperation,
                            if (pendingOperation.closing) null
                            else CancellationException("Stale output"),
                        )
                    } else {
                        val stage =
                            try {
                                requireNotNull(sink.apply(state.player, value))
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
