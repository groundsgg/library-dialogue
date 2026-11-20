package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.definition.ArgumentValue
import java.util.LinkedHashMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class DialogueRuntimeTest {
    @Test
    fun serializedStartAndInput() {
        val rig = TestRig()
        val request = rig.request("same")
        val first = rig.runtime.start(request)
        val second = rig.runtime.start(request)
        assertSame(first, second)
        val conflict =
            DialogueStart(
                "same",
                "other",
                1,
                rig.content,
                rig.bindings,
                emptyMap(),
                request.locale(),
                request.preferences(),
                rig.cancellation,
                0,
            )
        assertEquals(
            "START_REQUEST_CONFLICT",
            (TestRig.done(rig.runtime.start(conflict)) as DialogueStartResult.Rejected).code(),
        )
        rig.scheduler.drain()
        val handle = (TestRig.done(first) as DialogueStartResult.Started).handle
        val id = handle.snapshot().sessionId()
        val end = handle.stop(DialogueEndReason.CANCELLED)
        rig.scheduler.drain()
        assertEquals(DialogueEndReason.CANCELLED, TestRig.done(end).reason())
        assertEquals(id, handle.snapshot().sessionId())
        assertEquals(DialogueView.State.CLOSED, handle.snapshot().state())
    }

    @Test
    fun cancelledPendingStartIsReachable() {
        val rig = TestRig()
        rig.cancellation.cancel(DialogueEndReason.DISCONNECTED)
        val future = rig.runtime.start(rig.request("cancelled"))
        rig.scheduler.drain()
        assertInstanceOf(DialogueStartResult.Rejected::class.java, TestRig.done(future))
    }

    @Test
    fun closeRuntimeRejectsNewStarts() {
        val rig = TestRig()
        val future = rig.runtime.closeAsync()
        rig.scheduler.drain()
        TestRig.done(future)
        assertEquals(
            "HOST_CLOSED",
            (TestRig.done(rig.runtime.start(rig.request("late"))) as DialogueStartResult.Rejected)
                .code(),
        )
    }

    @Test
    fun equivalentContextMapsShareTheSameStart() {
        val rig = TestRig()
        val first = LinkedHashMap<String, ArgumentValue>()
        first["Aa"] = ArgumentValue.of("x")
        first["BB"] = ArgumentValue.of("y")
        val reversed = LinkedHashMap<String, ArgumentValue>()
        reversed["BB"] = ArgumentValue.of("y")
        reversed["Aa"] = ArgumentValue.of("x")
        val request = rig.request("same-map")
        val a =
            DialogueStart(
                request.requestId(),
                request.audienceId(),
                request.hostGeneration(),
                request.content(),
                request.bindings(),
                first,
                request.locale(),
                request.preferences(),
                request.cancellation(),
                0,
            )
        val b =
            DialogueStart(
                request.requestId(),
                request.audienceId(),
                request.hostGeneration(),
                request.content(),
                request.bindings(),
                reversed,
                request.locale(),
                request.preferences(),
                request.cancellation(),
                0,
            )
        assertSame(rig.runtime.start(a), rig.runtime.start(b))
        rig.scheduler.drain()
    }
}
