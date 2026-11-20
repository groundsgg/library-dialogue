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
