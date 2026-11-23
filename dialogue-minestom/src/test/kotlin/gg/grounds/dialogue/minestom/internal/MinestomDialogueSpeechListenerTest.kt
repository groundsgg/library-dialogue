package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.minestom.MinestomDialogueHostTest
import gg.grounds.dialogue.minestom.MinestomDialogueRequest
import gg.grounds.dialogue.minestom.MinestomDialogueSpeechEvent
import gg.grounds.dialogue.minestom.MinestomDialogueSpeechListener
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class MinestomDialogueSpeechListenerTest {
    private class RecordingListener : MinestomDialogueSpeechListener {
        val revealed = CopyOnWriteArrayList<MinestomDialogueSpeechEvent>()
        val stopped = CopyOnWriteArrayList<MinestomDialogueSpeechListener.StopReason>()
        var failOnReveal = false

        override fun onRevealed(event: MinestomDialogueSpeechEvent) {
            revealed.add(event)
            if (failOnReveal) error("listener failure")
        }

        override fun onStop(reason: MinestomDialogueSpeechListener.StopReason) {
            stopped.add(reason)
        }
    }

    private class Renderer(var effective: String = "npc") : DialogueRenderer, DialogueInputSource {
        var gateOnReveal: Int? = null
        val gate = CompletableFuture<Void>()
        var failOnReveal = false

        override fun effectiveSurface(): String = effective

        override fun resolveChat(
            player: Player,
            generation: Long,
            token: String,
        ): Optional<TargetSnapshot.Target> = Optional.empty()

        override fun prepare(
            request: DialogueRenderer.PresentationRequest
        ): CompletionStage<DialogueRenderer.PresentationHandle> =
            CompletableFuture.completedFuture(
                object : DialogueRenderer.PresentationHandle {
                    override fun replace(view: DialogueView): CompletionStage<Void> =
                        when {
                            failOnReveal && view.revealedGraphemes() > 0 ->
                                CompletableFuture.failedFuture(
                                    IllegalStateException("presentation failed")
                                )
                            gateOnReveal == view.revealedGraphemes() -> gate
                            else -> CompletableFuture.completedFuture(null)
                        }

                    override fun reconfigure(
                        preferences: PresentationPreferences
                    ): CompletionStage<Void> = CompletableFuture.completedFuture(null)

                    override fun closeAsync(): CompletionStage<Void> =
                        CompletableFuture.completedFuture(null)
                }
            )
    }

    private class Fixture(
        val env: Env,
        val listener: RecordingListener = RecordingListener(),
        val renderer: Renderer = Renderer(),
    ) {
        val base = MinestomDialogueHostTest.Fixture(env)
        val player = env.createConnection().connect(base.player.instance, Pos(0.0, 42.0, 0.0))
        val ready = CompletableFuture<DialogueStartResult>()
        val token = CancellationToken()
        val request: MinestomDialogueRequest
        val session: MinestomDialogueSession

        init {
            MinestomDialogueHostTest.done(env, base.host.closeAsync())
            val original =
                base.request(
                    "speech",
                    "v1",
                    PresentationPreferences("npc", Duration.ofMillis(90), false, emptyMap()),
                )
            request =
                MinestomDialogueRequest(
                    original.requestId(),
                    player,
                    original.hostGeneration(),
                    original.content(),
                    original.services(),
                    original.context(),
                    original.locale(),
                    original.preferences(),
                    original.speakers(),
                    original.actors(),
                    original.controls(),
                    original.openingImpulse(),
                    listener,
                )
            session =
                MinestomDialogueSession(
                    request,
                    base.options,
                    ConnectionGuard(player, 1, player.instance),
                    MinestomDialogueScheduler(player.instance),
                    renderer,
                    token,
                    { ready.complete(it) },
                    { _, _ -> },
                )
            session.begin()
            MinestomDialogueHostTest.done(env, ready)
        }

        fun waitForRevealed(count: Int) {
            assertTrue(
                env.tickWhile(
                    { (session.snapshot()?.revealedGraphemes() ?: 0) < count },
                    Duration.ofSeconds(2),
                )
            )
            env.tick()
        }

        fun close() = session.cancel(DialogueEndReason.CANCELLED)
    }

    @Test
    fun naturalPresentedDeltasStopOnPauseAndTerminalCloseIsStillReported(env: Env) {
        val fixture = Fixture(env)
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.state() != DialogueView.State.WAITING_FOR_INPUT },
                Duration.ofSeconds(2),
            )
        )
        env.tick()

        assertEquals("Hell", fixture.listener.revealed.joinToString("") { it.revealed() })
        assertTrue(
            fixture.listener.revealed.all { it.view().state() == DialogueView.State.REVEALING }
        )
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.PAUSED,
            fixture.listener.stopped.last(),
        )
        val event = fixture.listener.revealed.first()
        assertEquals("start", event.view().nodeId())
        assertEquals("npc", event.speakerRole())
        assertSame(fixture.request.speakers()["npc"], event.speaker())

        val view = fixture.session.snapshot()!!
        assertEquals(
            InputResult.ACCEPTED,
            MinestomDialogueHostTest.done(
                env,
                fixture.session.submit(
                    DialogueInput.Confirm(
                        DialogueInput.InputStamp(
                            view.sessionId(),
                            view.renderRevision(),
                            fixture.session.nextImpulse(),
                        ),
                        null,
                    )
                ),
            ),
        )
        env.tick()
        assertEquals("Hell", fixture.listener.revealed.joinToString("") { it.revealed() })
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.SESSION_ENDED,
            fixture.listener.stopped.last(),
        )

        fixture.close()
        env.tick()
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.SESSION_ENDED,
            fixture.listener.stopped.last(),
        )
    }

    @Test
    fun stalePresentedFrameCannotRestartSpeechAfterDisconnect(env: Env) {
        val renderer = Renderer().also { it.gateOnReveal = 1 }
        val fixture = Fixture(env, renderer = renderer)
        fixture.waitForRevealed(1)
        assertTrue(fixture.listener.revealed.isEmpty())

        fixture.session.cancel(DialogueEndReason.DISCONNECTED)
        renderer.gate.complete(null)
        env.tick()

        assertTrue(fixture.listener.revealed.isEmpty())
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.DISCONNECTED,
            fixture.listener.stopped.last(),
        )
    }

    @Test
    fun listenerFailureDisablesOnlySpeechHooks(env: Env) {
        val listener = RecordingListener().also { it.failOnReveal = true }
        val fixture = Fixture(env, listener = listener)
        fixture.waitForRevealed(1)

        assertEquals(
            MinestomDialogueSpeechListener.StopReason.LISTENER_FAILED,
            listener.stopped.last(),
        )
        assertNotNull(fixture.session.snapshot())

        fixture.close()
        env.tick()
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.SESSION_ENDED,
            listener.stopped.last(),
        )
    }
}
