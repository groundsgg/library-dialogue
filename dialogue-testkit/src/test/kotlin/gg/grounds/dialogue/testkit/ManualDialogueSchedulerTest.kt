package gg.grounds.dialogue.testkit

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ManualDialogueSchedulerTest {
    @Test
    fun sameDeadlineIsFifoAcrossExecuteAndTimers() {
        val scheduler = ManualDialogueScheduler()
        val seen = ArrayList<Int>()
        scheduler.execute { seen.add(1) }
        scheduler.schedule(Duration.ZERO) { seen.add(2) }
        scheduler.execute { seen.add(3) }
        scheduler.runUntilIdle()
        assertEquals(listOf(1, 2, 3), seen)
        assertEquals(0L, scheduler.nanoTime())
    }

    @Test
    fun advanceVisitsDeadlinesChronologically() {
        val scheduler = ManualDialogueScheduler()
        val seen = ArrayList<Long>()
        for (millis in listOf(10L, 2L, 5L)) {
            scheduler.schedule(Duration.ofMillis(millis)) { seen.add(scheduler.nanoTime()) }
        }
        scheduler.runUntilIdle()
        assertTrue(seen.isEmpty())
        scheduler.advanceBy(Duration.ofMillis(10))
        assertEquals(listOf(2_000_000L, 5_000_000L, 10_000_000L), seen)
        assertEquals(10_000_000L, scheduler.nanoTime())
    }

    @Test
    fun cancellationIsIdempotent() {
        val scheduler = ManualDialogueScheduler()
        val seen = AtomicInteger()
        val task = scheduler.schedule(Duration.ZERO, seen::incrementAndGet)
        task.cancel()
        task.cancel()
        scheduler.runUntilIdle()
        assertEquals(0, seen.get())
    }

    @Test
    fun negativeAndOverflowRejected() {
        val scheduler = ManualDialogueScheduler()
        assertThrows<IllegalArgumentException> { scheduler.advanceBy(Duration.ofNanos(-1)) }
        assertThrows<IllegalArgumentException> {
            scheduler.schedule(Duration.ofSeconds(Long.MAX_VALUE)) {}
        }
        scheduler.advanceBy(Duration.ofNanos(Long.MAX_VALUE))
        assertThrows<IllegalArgumentException> { scheduler.advanceBy(Duration.ofNanos(1)) }
        assertThrows<IllegalArgumentException> { scheduler.schedule(Duration.ofNanos(1)) {} }
        assertEquals(Long.MAX_VALUE, scheduler.nanoTime())
    }

    @Test
    fun immediateReschedulingSharesOneBudget() {
        val scheduler = ManualDialogueScheduler()
        val seen = AtomicInteger()
        val loop =
            object : Runnable {
                override fun run() {
                    seen.incrementAndGet()
                    scheduler.schedule(Duration.ZERO) { scheduler.execute(this) }
                }
            }
        scheduler.execute(loop)
        assertEquals(
            "TESTKIT_TASK_BUDGET",
            assertThrows<IllegalStateException> { scheduler.runUntilIdle() }.message,
        )
        assertEquals(5000, seen.get())

        val timerScheduler = ManualDialogueScheduler()
        val total = AtomicInteger()
        val timer =
            object : Runnable {
                override fun run() {
                    total.incrementAndGet()
                    timerScheduler.schedule(Duration.ZERO, this)
                }
            }
        timerScheduler.execute(timer)
        assertThrows<IllegalStateException> { timerScheduler.runUntilIdle() }
        assertEquals(10_000, total.get())
    }

    @Test
    fun reentrantAndConcurrentControllersFail() {
        val scheduler = ManualDialogueScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        scheduler.execute {
            assertEquals(
                "TESTKIT_REENTRANT",
                assertThrows<IllegalStateException> { scheduler.runUntilIdle() }.message,
            )
            entered.countDown()
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS))
            } catch (failure: InterruptedException) {
                throw AssertionError(failure)
            }
        }
        Executors.newSingleThreadExecutor().use { executor ->
            val running = executor.submit(scheduler::runUntilIdle)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                assertThrows<IllegalStateException> { scheduler.advanceBy(Duration.ZERO) }
            } finally {
                release.countDown()
            }
            running.get(5, TimeUnit.SECONDS)
        }
        scheduler.runUntilIdle()
    }

    @Test
    fun foreignCompletionCanEnqueue() {
        val scheduler = ManualDialogueScheduler()
        val seen = ArrayList<Int>()
        Executors.newSingleThreadExecutor().use { executor ->
            executor.submit { scheduler.execute { seen.add(1) } }.get(5, TimeUnit.SECONDS)
        }
        scheduler.runUntilIdle()
        assertEquals(listOf(1), seen)
    }

    @Test
    fun callbackFailureReleasesController() {
        val scheduler = ManualDialogueScheduler()
        scheduler.execute { error("boom") }
        assertThrows<IllegalStateException> { scheduler.advanceBy(Duration.ofNanos(10)) }
        assertEquals(0L, scheduler.nanoTime())
        scheduler.advanceBy(Duration.ofNanos(2))
        assertEquals(2L, scheduler.nanoTime())
    }

    @Test
    fun realRuntimeRevealAndCancelUsesVirtualTime() {
        val fixture = RuntimeFixture()
        val views = ArrayList<DialogueView>()
        val renderer = DialogueRenderer {
            CompletableFuture.completedFuture(
                object : DialogueRenderer.PresentationHandle {
                    override fun replace(view: DialogueView): CompletionStage<Void> {
                        views.add(view)
                        return CompletableFuture.completedFuture(null)
                    }

                    override fun reconfigure(
                        preferences: PresentationPreferences
                    ): CompletionStage<Void> = CompletableFuture.completedFuture(null)

                    override fun closeAsync(): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)
                }
            )
        }
        val handle = fixture.start(renderer, "one")
        fixture.clock.advanceBy(Duration.ofSeconds(1))
        assertEquals(5, handle.snapshot().revealedGraphemes())
        val stop = handle.stop(DialogueEndReason.CANCELLED)
        fixture.clock.runUntilIdle()
        assertTrue(stop.toCompletableFuture().isDone)
        val size = views.size
        fixture.clock.advanceBy(Duration.ofSeconds(10))
        assertEquals(size, views.size)
    }
}
