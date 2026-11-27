package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.port.ConditionProvider
import gg.grounds.dialogue.core.port.ConditionProvider.ConditionResult
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.DialogueServices
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.MinestomDialogueHostTest
import gg.grounds.dialogue.minestom.MinestomDialogueRequest
import gg.grounds.dialogue.minestom.input.DialogueInputSource
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
class ChoiceSoundSessionTest {
    private class Renderer(var effective: String = "hud") : DialogueRenderer, DialogueInputSource {
        var fail = false
        var gateCount = -1
        var gateChoice: String? = null
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
                        if (
                            view.revealedGraphemes() == gateCount ||
                                view.selectedChoiceId() == gateChoice && gateChoice != null
                        )
                            gate
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
        val condition: () -> CompletionStage<ConditionResult> = {
            CompletableFuture.completedFuture(ConditionResult.TRUE)
        },
    ) {
        val base = MinestomDialogueHostTest.Fixture(env)
        val connection = env.createConnection()
        val player = connection.connect(base.player.instance, Pos(0.0, 42.0, 0.0))
        val sounds = connection.trackIncoming(SoundEffectPacket::class.java)
        val bystander = env.createConnection()
        val other = bystander.connect(base.player.instance, Pos(1.0, 42.0, 0.0))
        val otherSounds = bystander.trackIncoming(SoundEffectPacket::class.java)
        val ready = CompletableFuture<DialogueStartResult>()
        val closed = CompletableFuture<Void>()
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
                        mapOf("choiceSounds" to enabled.toString()),
                    ),
                )
            val request =
                MinestomDialogueRequest(
                    original.requestId,
                    player,
                    original.hostGeneration,
                    content(),
                    DialogueServices(
                        "v1",
                        mapOf("test:available" to ConditionProvider { condition() }),
                        original.services.actions(),
                        original.services.outcomes(),
                        original.services.texts(),
                        original.services.capabilities(),
                    ),
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
                    MinestomConnectionScope(player, 1, player.instance),
                    MinestomDialogueScheduler(player.instance),
                    renderer,
                    token,
                    { ready.complete(it) },
                    { _, failure ->
                        if (failure == null) closed.complete(null)
                        else closed.completeExceptionally(failure)
                    },
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

        fun close() {
            session.cancel(DialogueEndReason.HOST_CHANGED)
            MinestomDialogueHostTest.done(env, closed)
        }
    }

    private fun Fixture.select(id: String): InputResult {
        val v = session.snapshot()!!
        return MinestomDialogueHostTest.done(
            env,
            session.submit(
                DialogueInput.Select(
                    DialogueInput.InputStamp(
                        v.sessionId(),
                        v.renderRevision(),
                        session.nextImpulse(),
                    ),
                    id,
                )
            ),
        )
    }

    private fun Fixture.confirm(id: String?): InputResult {
        val v = session.snapshot()!!
        return MinestomDialogueHostTest.done(
            env,
            session.submit(
                DialogueInput.Confirm(
                    DialogueInput.InputStamp(
                        v.sessionId(),
                        v.renderRevision(),
                        session.nextImpulse(),
                    ),
                    id,
                )
            ),
        )
    }

    @Test
    fun selectionAndFinalConfirmationPlayDistinctPrivateSounds(env: Env) {
        val f = Fixture(env)
        f.finishReveal()
        f.sounds.assertEmpty()
        val browsing = f.connection.trackIncoming(SoundEffectPacket::class.java)
        assertEquals(InputResult.ACCEPTED, f.select("b"))
        env.tick()
        env.tick()
        val browse = browsing.collect()
        assertEquals(1, browse.size)
        assertEquals(0.08f, browse.single().volume())
        assertEquals(1.4f, browse.single().pitch())
        val confirmed = f.connection.trackIncoming(SoundEffectPacket::class.java)
        assertEquals(InputResult.ACCEPTED, f.confirm("b"))
        env.tick()
        env.tick()
        val confirmation = confirmed.collect()
        assertEquals(1, confirmation.size)
        assertEquals(0.14f, confirmation.single().volume())
        assertEquals(1.0f, confirmation.single().pitch())
        f.otherSounds.assertEmpty()
    }

    @Test
    fun unchangedInvalidAndRevealOnlyInputsStaySilent(env: Env) {
        val f = Fixture(env)
        assertEquals(InputResult.REVEAL_ONLY, f.confirm("a"))
        env.tick()
        assertEquals(InputResult.ACCEPTED, f.select("a"))
        assertEquals(InputResult.INVALID, f.select("missing"))
        assertEquals(InputResult.INVALID, f.confirm("b"))
        env.tick()
        f.sounds.assertEmpty()
        f.close()
    }

    @Test
    fun disabledAndChatFallbackStaySilent(env: Env) {
        for (f in listOf(Fixture(env, enabled = false), Fixture(env, Renderer("chat")))) {
            f.finishReveal()
            f.select("b")
            f.confirm("b")
            env.tick()
            f.sounds.assertEmpty()
        }
    }

    @Test
    fun selectionWaitsForSuccessfulPresentationAndCancelledReceiptsStaySilent(env: Env) {
        val renderer = Renderer().also { it.gateChoice = "b" }
        val f = Fixture(env, renderer)
        f.finishReveal()
        assertEquals(InputResult.ACCEPTED, f.select("b"))
        env.tick()
        f.sounds.assertEmpty()
        val late = f.connection.trackIncoming(SoundEffectPacket::class.java)
        f.close()
        renderer.gate.complete(null)
        env.tick()
        late.assertEmpty()
    }

    @Test
    fun successfulDelayedPresentationPlaysOnceAndObsoleteFrameStaysSilent(env: Env) {
        val renderer = Renderer().also { it.gateChoice = "b" }
        val f = Fixture(env, renderer)
        f.finishReveal()
        f.select("b")
        env.tick()
        f.sounds.assertEmpty()
        val completed = f.connection.trackIncoming(SoundEffectPacket::class.java)
        renderer.gate.complete(null)
        env.tick()
        env.tick()
        assertEquals(1, completed.collect().size)
        f.close()

        val staleRenderer = Renderer().also { it.gateChoice = "b" }
        val stale = Fixture(env, staleRenderer)
        stale.finishReveal()
        stale.select("b")
        stale.select("a")
        env.tick()
        stale.sounds.collect()
        val late = stale.connection.trackIncoming(SoundEffectPacket::class.java)
        staleRenderer.gate.complete(null)
        env.tick()
        env.tick()
        late.assertEmpty()
        stale.close()
    }

    @Test
    fun changedProviderOffersAndCancelledPendingConfirmationStaySilent(env: Env) {
        var gate: CompletionStage<ConditionResult> =
            CompletableFuture.completedFuture(ConditionResult.TRUE)
        val f = Fixture(env, condition = { gate })
        f.finishReveal()
        f.select("b")
        env.tick()
        f.sounds.collect()
        val refresh = f.connection.trackIncoming(SoundEffectPacket::class.java)
        gate = CompletableFuture.completedFuture(ConditionResult.FALSE)
        assertEquals(InputResult.STALE, f.confirm("b"))
        env.tick()
        refresh.assertEmpty()
        f.close()

        for (reason in listOf(DialogueEndReason.HOST_CHANGED, DialogueEndReason.SUPERSEDED)) {
            var waiting = false
            val pending = CompletableFuture<ConditionResult>()
            val cancelled =
                Fixture(
                    env,
                    condition = {
                        if (waiting) pending
                        else CompletableFuture.completedFuture(ConditionResult.TRUE)
                    },
                )
            cancelled.finishReveal()
            cancelled.select("b")
            env.tick()
            cancelled.sounds.collect()
            val after = cancelled.connection.trackIncoming(SoundEffectPacket::class.java)
            waiting = true
            val v = cancelled.session.snapshot()!!
            val result =
                cancelled.session.submit(
                    DialogueInput.Confirm(
                        DialogueInput.InputStamp(
                            v.sessionId(),
                            v.renderRevision(),
                            cancelled.session.nextImpulse(),
                        ),
                        "b",
                    )
                )
            assertTrue(
                env.tickWhile(
                    {
                        cancelled.session.snapshot()?.state() !=
                            DialogueView.State.WAITING_FOR_PROVIDER
                    },
                    Duration.ofSeconds(2),
                )
            )
            cancelled.session.cancel(reason)
            pending.complete(ConditionResult.TRUE)
            MinestomDialogueHostTest.done(env, result)
            env.tick()
            after.assertEmpty()
        }
    }

    @Test
    fun failedPresentationAndChangedConnectionNeverPlayBrowse(env: Env) {
        val failed = Fixture(env)
        failed.finishReveal()
        failed.renderer.fail = true
        failed.select("b")
        env.tick()
        env.tick()
        failed.sounds.assertEmpty()
        failed.close()

        val renderer = Renderer().also { it.gateChoice = "b" }
        val moved = Fixture(env, renderer)
        moved.finishReveal()
        moved.select("b")
        MinestomDialogueHostTest.done(
            env,
            moved.player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)),
        )
        renderer.gate.complete(null)
        env.tick()
        env.tick()
        moved.sounds.assertEmpty()
        moved.close()
    }

    @Test
    fun chatInputCannotAcquireAudioBySwitchingSurfaceDuringConfirmation(env: Env) {
        var waiting = false
        val gate = CompletableFuture<ConditionResult>()
        val renderer = Renderer("chat")
        val f =
            Fixture(
                env,
                renderer,
                condition = {
                    if (waiting) gate else CompletableFuture.completedFuture(ConditionResult.TRUE)
                },
            )
        f.finishReveal()
        waiting = true
        val v = f.session.snapshot()!!
        val accepted =
            f.session.submit(
                DialogueInput.Confirm(
                    DialogueInput.InputStamp(
                        v.sessionId(),
                        v.renderRevision(),
                        f.session.nextImpulse(),
                    ),
                    "a",
                )
            )
        // Choice a has no provider, but b is rechecked as part of the accepted choice set.
        assertTrue(
            env.tickWhile(
                { f.session.snapshot()?.state() != DialogueView.State.WAITING_FOR_PROVIDER },
                Duration.ofSeconds(2),
            )
        )
        renderer.effective = "hud"
        gate.complete(ConditionResult.TRUE)
        assertEquals(InputResult.ACCEPTED, MinestomDialogueHostTest.done(env, accepted))
        env.tick()
        f.sounds.assertEmpty()
    }

    companion object {
        private fun content(): ValidatedDialogue {
            val texts = TextCatalog(mapOf("en" to mapOf("line" to "Hello")))
            val definition =
                DialogueBuilder.dialogue("test:choices")
                    .node(
                        "start",
                        DialogueDefinition.Message(
                            "npc",
                            "line",
                            null,
                            listOf(
                                DialogueDefinition.Choice("a", "line", "end", true, null),
                                DialogueDefinition.Choice(
                                    "b",
                                    "line",
                                    "end",
                                    false,
                                    DialogueDefinition.Condition(
                                        "test:available",
                                        emptyMap(),
                                        false,
                                    ),
                                ),
                            ),
                            null,
                        ),
                    )
                    .node("end", DialogueDefinition.End("completed"))
                    .build()
            return DialogueValidator()
                .validate(
                    definition,
                    texts,
                    HostCatalog(
                        "v1",
                        setOf("npc"),
                        mapOf("test:available" to HostCatalog.ProviderSpec(emptyMap(), false)),
                        emptyMap(),
                        emptySet(),
                        emptySet(),
                        false,
                    ),
                    ValidationLimits.defaults(),
                )
                .validated()!!
        }
    }
}
