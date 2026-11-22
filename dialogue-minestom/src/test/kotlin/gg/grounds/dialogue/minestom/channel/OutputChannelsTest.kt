package gg.grounds.dialogue.minestom.channel

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.coordinate.Pos
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class OutputChannelsTest {
    private fun channels(seen: MutableList<String>? = null) =
        LocalOutputChannels(
            { _, component ->
                seen?.add(PlainTextComponentSerializer.plainText().serialize(component))
                CompletableFuture.completedFuture(null)
            },
            false,
            { _, _ -> CompletableFuture.completedFuture(null) },
        )

    @Test
    fun retiredLayersStillRejectAnOlderConnectionGeneration(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val channels = channels()
        val lease = channels.acquireHud(player, 2, 50)
        lease.replace(Component.text("new"))
        lease.closeAsync().toCompletableFuture().join()
        assertThrows(IllegalArgumentException::class.java) { channels.acquireHud(player, 1, 50) }
    }

    @Test
    fun higherPriorityLayerReportsSuppressedDialog(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val channels = channels()
        val dialogue = channels.acquireHud(player, 1, 50)
        dialogue.replace(Component.text("dialogue"))
        assertTrue(dialogue.visible())
        val urgent = channels.acquireHud(player, 1, 100)
        urgent.replace(Component.text("urgent"))
        assertFalse(dialogue.visible())
        urgent.closeAsync().toCompletableFuture().join()
        assertTrue(dialogue.visible())
        dialogue.closeAsync()
    }

    @Test
    fun closingOneLeaseRestoresRemainingSource(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<String>()
        val channels = channels(seen)
        val foreign = channels.acquireHud(player, 1, 10)
        foreign.replace(Component.text("foreign")).toCompletableFuture().join()
        val dialogue = channels.acquireHud(player, 1, 50)
        dialogue.replace(Component.text("dialogue")).toCompletableFuture().join()
        dialogue.closeAsync().toCompletableFuture().join()
        assertEquals(listOf("foreign", "dialogue", "foreign"), seen)
        foreign.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun oldConnectionGenerationCannotWriteToCurrentHud(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<String>()
        val channels = channels(seen)
        val old = channels.acquireHud(player, 1, 50)
        old.replace(Component.text("old"))
        val fresh = channels.acquireHud(player, 2, 50)
        fresh.replace(Component.text("new")).toCompletableFuture().join()
        old.closeAsync().toCompletableFuture().join()
        assertEquals("new", seen.last())
        assertTrue(
            old.replace(Component.text("stale")).toCompletableFuture().isCompletedExceptionally
        )
        fresh.closeAsync()
    }

    @Test
    fun cleanupWaitsForPendingWrites(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val gate = CompletableFuture<Void>()
        val count = AtomicInteger()
        val channels =
            LocalOutputChannels(
                { _, _ ->
                    if (count.getAndIncrement() == 0) gate
                    else CompletableFuture.completedFuture(null)
                },
                false,
                { _, _ -> CompletableFuture.completedFuture(null) },
            )
        val lease = channels.acquireHud(player, 1, 50)
        val write = lease.replace(Component.text("visible"))
        val close = lease.closeAsync()
        assertFalse(close.toCompletableFuture().isDone)
        gate.complete(null)
        write.toCompletableFuture().join()
        close.toCompletableFuture().join()
        assertEquals(2, count.get())
        assertSame(close, lease.closeAsync())
    }

    @Test
    fun reentrantSinkDoesNotHoldInternalMonitor(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val reference = AtomicReference<OutputChannels.HudLease>()
        val first = AtomicBoolean(true)
        Executors.newSingleThreadExecutor().use { executor ->
            val channels =
                LocalOutputChannels(
                    { _, _ ->
                        if (first.getAndSet(false)) {
                            try {
                                CompletableFuture.runAsync(
                                        { reference.get().closeAsync() },
                                        executor,
                                    )
                                    .get(1, TimeUnit.SECONDS)
                            } catch (exception: Exception) {
                                return@LocalOutputChannels CompletableFuture.failedFuture(exception)
                            }
                        }
                        CompletableFuture.completedFuture(null)
                    },
                    false,
                    { _, _ -> CompletableFuture.completedFuture(null) },
                )
            reference.set(channels.acquireHud(player, 1, 50))
            reference.get().replace(Component.text("dialogue")).toCompletableFuture().join()
            reference.get().closeAsync().toCompletableFuture().join()
        }
    }

    @Test
    fun strongestDialogueVignetteWinsAndKeepsForeignOrder(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<List<Key>>()
        val channels =
            LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                true,
                { _, effects ->
                    seen.add(effects)
                    CompletableFuture.completedFuture(null)
                },
            )
        val before = Key.key("example:before")
        val after = Key.key("example:after")
        val weaker = Key.key("dialogue:vignette_fade_2")
        val stronger = Key.key("dialogue:vignette")
        val fading = channels.acquireEffects(player, 1)
        val remaining = channels.acquireEffects(player, 1)
        fading.replace(listOf(before, weaker, after)).toCompletableFuture().join()
        remaining.replace(listOf(stronger)).toCompletableFuture().join()
        assertEquals(listOf(before, stronger, after), seen.last())
        remaining.closeAsync().toCompletableFuture().join()
        assertEquals(listOf(before, weaker, after), seen.last())
        fading.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun nativeVignettePairsStayWithinTheirNewestSourceAndYieldToLegacy(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<List<Key>>()
        val channels =
            LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                true,
                { _, effects ->
                    seen.add(effects)
                    CompletableFuture.completedFuture(null)
                },
            )
        val before = Key.key("example:before")
        val after = Key.key("example:after")
        val enterA = Key.key("dialogue:vignette_250_enter_a")
        val exitA = Key.key("dialogue:vignette_250_exit_a")
        val enterB = Key.key("dialogue:vignette_250_enter_b")
        val exitB = Key.key("dialogue:vignette_250_exit_b")
        val legacy = Key.key("dialogue:vignette_fade_2")
        val oldest = channels.acquireEffects(player, 1)
        val next = channels.acquireEffects(player, 1)
        val newest = channels.acquireEffects(player, 1)

        oldest.replace(listOf(before, enterA, exitA, after)).toCompletableFuture().join()
        next.replace(listOf(exitB)).toCompletableFuture().join()
        assertEquals(listOf(before, enterA, exitA, after), seen.last())
        newest.replace(listOf(exitA, enterB)).toCompletableFuture().join()
        assertEquals(listOf(before, enterB, after), seen.last())
        oldest.replace(listOf(before, enterA, after)).toCompletableFuture().join()
        assertEquals(listOf(before, enterB, after), seen.last())
        next.replace(listOf(legacy)).toCompletableFuture().join()
        assertEquals(listOf(before, legacy, after), seen.last())
        next.closeAsync().toCompletableFuture().join()
        newest.closeAsync().toCompletableFuture().join()
        assertEquals(listOf(before, enterA, after), seen.last())
        oldest.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun skippedNativeActivationCannotReplayTheLastDispatchedBank(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<List<Key>>()
        val stalled = CompletableFuture<Void>()
        val channels =
            LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                true,
                { _, effects ->
                    seen.add(effects)
                    if (seen.size == 1) stalled else CompletableFuture.completedFuture(null)
                },
            )
        val enterA = Key.key("dialogue:vignette_250_enter_a")
        val enterB = Key.key("dialogue:vignette_250_enter_b")
        val exitA = Key.key("dialogue:vignette_250_exit_a")
        val exitB = Key.key("dialogue:vignette_250_exit_b")
        val first = channels.acquireEffects(player, 1)
        first.replace(listOf(enterA))
        first.closeAsync()
        val skipped = channels.acquireEffects(player, 1)
        skipped.replace(listOf(enterB))
        skipped.closeAsync()
        val latest = channels.acquireEffects(player, 1)
        val dispatched = latest.replace(listOf(enterA))
        assertEquals(listOf(listOf(enterA)), seen)
        stalled.complete(null)
        dispatched.toCompletableFuture().join()
        assertEquals(listOf(enterB), seen.last())
        assertTrue(seen.drop(1).all { it == listOf(enterB) })
        latest.replace(listOf(enterA, exitA)).toCompletableFuture().join()
        assertEquals(listOf(enterB, exitB), seen.last())
        latest.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun sameDimensionNativeRetirementKeepsForeignAndNewWorldLayers(env: Env) {
        val world = env.createFlatInstance()
        val player = env.createConnection().connect(world, Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<List<Key>>()
        val channels =
            LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                true,
                { _, effects ->
                    seen.add(effects)
                    CompletableFuture.completedFuture(null)
                },
            )
        val before = Key.key("example:before")
        val after = Key.key("example:after")
        val foreign = channels.acquireEffects(player, 1)
        foreign.replace(listOf(before, after)).toCompletableFuture().join()
        val enterA = Key.key("dialogue:vignette_250_enter_a")
        val enterB = Key.key("dialogue:vignette_250_enter_b")
        val old = channels.acquireEffects(player, 1)
        old.replace(listOf(enterA)).toCompletableFuture().join()
        assertEquals(listOf(before, after, enterA), seen.last())

        player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        val current = channels.acquireEffects(player, 1)
        current.replace(listOf(enterB)).toCompletableFuture().join()
        old.closeAsync().toCompletableFuture().join()
        assertEquals(listOf(before, after, enterB), seen.last())
        current.closeAsync().toCompletableFuture().join()
        assertEquals(listOf(before, after), seen.last())
        foreign.closeAsync().toCompletableFuture().join()
        assertEquals(emptyList<Key>(), seen.last())
    }

    @Test
    fun oldWorldNativeRetirementCannotWriteOverANewerConnectionGeneration(env: Env) {
        val world = env.createFlatInstance()
        val player = env.createConnection().connect(world, Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<List<Key>>()
        val stalled = CompletableFuture<Void>()
        val channels =
            LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                true,
                { _, effects ->
                    seen.add(effects)
                    if (seen.size == 1) stalled else CompletableFuture.completedFuture(null)
                },
            )
        val enterA = Key.key("dialogue:vignette_250_enter_a")
        val enterB = Key.key("dialogue:vignette_250_enter_b")
        val old = channels.acquireEffects(player, 1)
        old.replace(listOf(enterA))
        player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        val retire = old.closeAsync()
        val current = channels.acquireEffects(player, 2)
        current.replace(listOf(enterB)).toCompletableFuture().join()
        stalled.complete(null)
        retire.toCompletableFuture().join()
        assertEquals(listOf(listOf(enterA), listOf(enterB)), seen)
        current.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun nativeRetirementRepairsSameDimensionWhenQueuedDisableNeverDispatched(env: Env) {
        val world = env.createFlatInstance()
        val player = env.createConnection().connect(world, Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<List<Key>>()
        val stalled = CompletableFuture<Void>()
        val channels =
            LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                true,
                { _, effects ->
                    seen.add(effects)
                    if (seen.size == 2) stalled else CompletableFuture.completedFuture(null)
                },
            )
        val enter = Key.key("dialogue:vignette_250_enter_a")
        val other = Key.key("example:other")
        val native = channels.acquireEffects(player, 1)
        native.replace(listOf(enter)).toCompletableFuture().join()
        val foreign = channels.acquireEffects(player, 1)
        foreign.replace(listOf(other))
        native.replace(emptyList())
        player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        val retiring = native.closeAsync()
        stalled.complete(null)
        retiring.toCompletableFuture().join()
        assertEquals(listOf(listOf(enter), listOf(enter, other), listOf(other)), seen)
        foreign.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun removingOneEffectKeepsOtherRegisteredEffects(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val seen = mutableListOf<List<Key>>()
        val channels =
            LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                true,
                { _, effects ->
                    seen.add(effects)
                    CompletableFuture.completedFuture(null)
                },
            )
        val other = channels.acquireEffects(player, 1)
        other.replace(listOf(Key.key("example:other")))
        val dialogue = channels.acquireEffects(player, 1)
        dialogue.replace(listOf(Key.key("dialogue:vignette")))
        dialogue.closeAsync().toCompletableFuture().join()
        assertEquals(listOf(Key.key("example:other")), seen.last())
        other.closeAsync()
    }
}
