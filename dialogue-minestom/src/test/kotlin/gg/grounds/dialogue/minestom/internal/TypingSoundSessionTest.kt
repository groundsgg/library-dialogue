package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.minestom.MinestomDialogueHostTest
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.SoundEffectPacket
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class TypingSoundSessionTest {
    private class Renderer(var effective: String = "hud") : DialogueRenderer, DialogueInputSource {
        var fail = false
        var gateCount = -1
        val gate = CompletableFuture<Void>()

        override fun effectiveSurface() = effective

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
                        if (view.revealedGraphemes() == gateCount) gate
                        else if (fail && view.revealedGraphemes() > 0)
                            CompletableFuture.failedFuture(
                                IllegalStateException("presentation failed")
                            )
                        else CompletableFuture.completedFuture(null)

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
        val renderer: Renderer = Renderer(),
        enabled: Boolean = true,
    ) {
        val base = MinestomDialogueHostTest.Fixture(env)
        val connection = env.createConnection()
        val player = connection.connect(base.player.instance, Pos(0.0, 42.0, 0.0))
        val sounds = connection.trackIncoming(SoundEffectPacket::class.java)
        val bystander = env.createConnection()
        val other = bystander.connect(base.player.instance, Pos(1.0, 42.0, 0.0))
        val otherSounds = bystander.trackIncoming(SoundEffectPacket::class.java)
        val ready = CompletableFuture<DialogueStartResult>()
        val token = CancellationToken()
        val session: MinestomDialogueSession

        init {
            MinestomDialogueHostTest.done(env, base.host.closeAsync())
            val original =
                base.request(
                    "typing",
                    "v1",
                    PresentationPreferences(
                        "hud",
                        Duration.ofMillis(90),
                        true,
                        mapOf("typingSound" to enabled.toString()),
                    ),
                )
            val request =
                gg.grounds.dialogue.minestom.MinestomDialogueRequest(
                    original.requestId,
                    player,
                    original.hostGeneration,
                    original.content,
                    original.services,
                    original.context,
                    original.locale,
                    original.preferences,
                    original.speakers,
                    original.actors,
                    original.controls,
                    original.openingImpulse,
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

        fun finishReveal() {
            assertTrue(
                env.tickWhile(
                    { session.snapshot()?.state() == DialogueView.State.REVEALING },
                    Duration.ofSeconds(2),
                )
            )
            env.tick()
        }

        fun close() = session.cancel(DialogueEndReason.HOST_CHANGED)
    }

    @Test
    fun optInSendsQuietPrivateSoundEvenWithReducedMotion(env: Env) {
        val fixture = Fixture(env)
        fixture.finishReveal()
        assertTrue(fixture.sounds.collect().isNotEmpty())
        fixture.otherSounds.assertEmpty()
        fixture.close()
    }

    @Test
    fun defaultAndChatFallbackStaySilent(env: Env) {
        val disabled = Fixture(env, enabled = false)
        disabled.finishReveal()
        disabled.sounds.assertEmpty()
        disabled.close()
        val chat = Fixture(env, Renderer("chat"))
        chat.finishReveal()
        chat.sounds.assertEmpty()
        chat.close()
    }

    @Test
    fun failedPresentationAndClosedSessionStaySilent(env: Env) {
        val fixture = Fixture(env, Renderer().also { it.fail = true })
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.revealedGraphemes() == 0 },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        fixture.sounds.assertEmpty()
        fixture.close()
        env.tick()
        fixture.sounds.assertEmpty()
    }

    @Test
    fun manualRevealWithOneCharacterRemainingIsSilent(env: Env) {
        val fixture = Fixture(env)
        assertTrue(
            env.tickWhile(
                { (fixture.session.snapshot()?.revealedGraphemes() ?: 0) < 4 },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        fixture.sounds.collect()
        val afterSkip = fixture.connection.trackIncoming(SoundEffectPacket::class.java)
        val view = fixture.session.snapshot()!!
        assertEquals(4, view.revealedGraphemes())
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
        )
        env.tick()
        afterSkip.assertEmpty()
        fixture.close()
    }

    @Test
    fun staleAndClosedPresentationCompletionsNeverPlayAudio(env: Env) {
        val renderer = Renderer().also { it.gateCount = 1 }
        val fixture = Fixture(env, renderer)
        assertTrue(
            env.tickWhile(
                { (fixture.session.snapshot()?.revealedGraphemes() ?: 0) < 2 },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        val late = fixture.connection.trackIncoming(SoundEffectPacket::class.java)
        renderer.gate.complete(null)
        env.tick()
        late.assertEmpty()
        fixture.close()
        env.tick()
        val closedRenderer = Renderer().also { it.gateCount = 1 }
        val closed = Fixture(env, closedRenderer)
        assertTrue(
            env.tickWhile(
                { (closed.session.snapshot()?.revealedGraphemes() ?: 0) < 1 },
                Duration.ofSeconds(2),
            )
        )
        val afterClose = closed.connection.trackIncoming(SoundEffectPacket::class.java)
        closed.close()
        closedRenderer.gate.complete(null)
        env.tick()
        afterClose.assertEmpty()
    }
}
