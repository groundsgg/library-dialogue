package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.internal.OwnedScope
import gg.grounds.dialogue.core.internal.SerialCommandQueue
import gg.grounds.dialogue.core.port.OwnedResource
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnedScopeTest {
    @Test
    fun deferredCloseCannotExtendItsConfiguredBudget() {
        val clock = ManualScheduler()
        val queue = SerialCommandQueue(clock) { throw AssertionError(it) }
        val budget = Duration.ofMillis(100)
        val scope = OwnedScope(queue, clock, budget)
        val gate = CompletableFuture<Void>()
        scope.prepare { CompletableFuture.completedFuture(OwnedResource { gate }) }
        clock.drain()

        val deadline = clock.nanoTime() + Duration.ofSeconds(1).toNanos()
        val closing = scope.closeBy(deadline).toCompletableFuture()
        clock.advance(budget)
        assertTrue(closing.isCompletedExceptionally)
        val error = assertThrows(CompletionException::class.java) { closing.join() }
        assertEquals("CLEANUP_TIMEOUT", (error.cause as DialogueFailure).code())
        gate.complete(null)
        clock.drain()
    }

    @Test
    fun deferredCloseUsesRemainingCleanupBudget() {
        val clock = ManualScheduler()
        val queue = SerialCommandQueue(clock) { throw AssertionError(it) }
        val budget = Duration.ofMillis(600)
        val deadline = clock.nanoTime() + budget.toNanos()
        val scope = OwnedScope(queue, clock, budget)
        val gate = CompletableFuture<Void>()
        scope.prepare { CompletableFuture.completedFuture(OwnedResource { gate }) }
        clock.advance(Duration.ofMillis(500))

        val closing = scope.closeBy(deadline).toCompletableFuture()
        clock.advance(Duration.ofMillis(99))
        assertFalse(closing.isDone)
        clock.advance(Duration.ofMillis(1))
        assertTrue(closing.isCompletedExceptionally)
        val error = assertThrows(CompletionException::class.java) { closing.join() }
        assertEquals("CLEANUP_TIMEOUT", (error.cause as DialogueFailure).code())
        gate.complete(null)
        clock.drain()
    }
}
