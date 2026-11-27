package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.OutputChannels
import gg.grounds.dialogue.minestom.input.ChatInputTokens
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.presentation.*
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import net.kyori.adventure.key.Key
import net.kyori.adventure.resource.ResourcePackStatus
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.instance.Instance
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class NativeFrameCommitTest {
    private fun rejected(sync: Boolean): CompletionStage<Void> {
        // A conforming nested surface: its own frame has no owned output or input side effects.
        val nested =
            object : DialogueSurface.SurfaceHandle {
                override fun replace(frame: WidgetFrame): CompletionStage<Void> {
                    if (sync)
                        throw DialogueSurface.FrameRejected("Nested frame rejected before commit")
                    return CompletableFuture.failedFuture(
                        DialogueSurface.FrameRejected("Nested frame rejected before commit")
                    )
                }

                override fun closeAsync(): CompletionStage<Void> =
                    CompletableFuture.completedFuture(null)
            }
        return nested.replace(RenderFixtures.frame("npc", "nested", 6))
    }

    private fun ordinary(env: Env, work: CompletionStage<Void>): Throwable {
        val failure =
            assertThrows(CompletionException::class.java) { HudDialogueSurfaceTest.done(env, work) }
        var cause: Throwable = failure
        while (cause is CompletionException || cause is ExecutionException) cause = cause.cause!!
        assertFalse(
            cause is DialogueSurface.FrameRejected,
            "An enclosing mutated frame cannot forward its child's guarantee",
        )
        return cause
    }

    private class Channels : OutputChannels {
        var hudWrites = 0
        var effectWrites = 0
        var hudCloses = 0
        var effectCloses = 0
        var supported = true
        var hud: () -> CompletionStage<Void> = { CompletableFuture.completedFuture(null) }
        var effect: () -> CompletionStage<Void> = { CompletableFuture.completedFuture(null) }
        var release: () -> CompletionStage<Void> = { CompletableFuture.completedFuture(null) }

        override fun supportedEffects(): Boolean = supported

        override fun acquireHud(
            player: net.minestom.server.entity.Player,
            generation: Long,
            priority: Int,
        ) =
            object : OutputChannels.HudLease {
                override fun replace(value: Component): CompletionStage<Void> {
                    hudWrites++
                    return hud()
                }

                override fun closeAsync(): CompletionStage<Void> {
                    hudCloses++
                    return release()
                }
            }

        override fun acquireEffects(player: net.minestom.server.entity.Player, generation: Long) =
            object : OutputChannels.EffectLease {
                override fun replace(value: List<Key>): CompletionStage<Void> {
                    effectWrites++
                    return effect()
                }

                override fun closeAsync(): CompletionStage<Void> {
                    effectCloses++
                    return CompletableFuture.completedFuture(null)
                }
            }
    }

    private class Fixture(env: Env) {
        val world = env.createFlatInstance()
        val player = env.createConnection().connect(world, Pos(0.0, 42.0, 4.0, 180f, 0f))
        val scope = MinestomConnectionScope(player, 1, world)
        val clock = MinestomDialogueScheduler(world)
        val targets = SurfaceInputTargets()
        val packs = PackStateTracker()
        val channels = Channels()
        val token = CancellationToken()
        val assets = RenderFixtures.ASSETS
        var spawn: (Entity, Instance, Pos) -> CompletionStage<Void> =
            { entity, instance, position ->
                entity.setInstance(instance, position)
            }

        init {
            val pack = UUID.randomUUID()
            packs.expect(player, 1, pack, assets.pin())
            packs.observe(player, 1, pack, assets.pin(), ResourcePackStatus.SUCCESSFULLY_LOADED)
        }

        fun context(key: String, effects: Map<String, String> = emptyMap()) =
            DialogueSurface.SurfaceContext(
                RenderFixtures.SESSION,
                player.uuid.toString(),
                1,
                key,
                "profile",
                token,
                1,
                Long.MAX_VALUE,
                PresentationPreferences(key, Duration.ZERO, false, effects),
            )

        fun hud() = HudDialogueSurface(scope, packs, assets, channels, targets, clock, {})

        fun npc() =
            NpcDialogueSurface(
                scope,
                packs,
                assets,
                channels,
                targets,
                clock,
                {
                    MinestomSpeakerBinding("npc", world) {
                        MinestomSpeakerBinding.AnchorSnapshot(
                            Vec(0.0, 42.0, 0.0),
                            0f,
                            Vec(0.0, 43.6, 0.0),
                            true,
                        )
                    }
                },
                {},
                { entity, instance, position -> spawn(entity, instance, position) },
            )
    }

    @Test
    fun chatNestedSinkFailureAfterTokenMutationIsFatal(env: Env) {
        for (sync in listOf(false, true)) {
            val f = Fixture(env)
            val tokens = ChatInputTokens(f.scope, f.targets, f.clock::nanoTime)
            var sends = 0
            val chat =
                ChatDialogueSurface(
                    f.scope,
                    f.targets,
                    tokens,
                    {
                        sends++
                        if (sends == 1) CompletableFuture.completedFuture(null) else rejected(sync)
                    },
                    UiLabelSource.defaults(),
                    DialoguePresenter.Controls.defaults(),
                    "/test",
                )
            val renderer =
                MinestomDialogueRenderer(
                    f.scope,
                    mapOf("chat" to DialoguePresenter.SurfaceProfile.semantic(chat)),
                    f.clock,
                    DialoguePresenter.Controls.defaults(),
                    UiLabelSource.defaults(),
                    f.targets,
                    {},
                    tokens,
                    { null },
                )
            val h =
                HudDialogueSurfaceTest.done(
                    env,
                    renderer.prepare(
                        DialogueRenderer.PresentationRequest(
                            RenderFixtures.SESSION,
                            f.player.uuid.toString(),
                            1,
                            PresentationPreferences("chat", Duration.ZERO, false, emptyMap()),
                            f.token,
                            Long.MAX_VALUE,
                        )
                    ),
                )
            HudDialogueSurfaceTest.done(env, h.replace(RenderFixtures.view("accepted", 8)))
            val capability = tokens.issue("ask", f.targets.current()!!)
            ordinary(env, h.replace(RenderFixtures.view("next section", 12)))
            repeat(10) { env.tick() }
            assertEquals(DialogueEndReason.FAILED, f.token.reason())
            assertTrue(tokens.resolve(f.player, 1, capability).isEmpty)
            assertEquals("", renderer.effectiveSurface())
            assertNull(f.targets.current())
        }
    }

    @Test
    fun hudNestedOutputFailureAfterEffectAckIsOrdinary(env: Env) {
        for (sync in listOf(false, true)) {
            val f = Fixture(env)
            val h = HudDialogueSurfaceTest.done(env, f.hud().open(f.context("hud")))
            HudDialogueSurfaceTest.done(env, h.replace(RenderFixtures.frame("hud", "accepted", 8)))
            f.channels.hud = { rejected(sync) }
            ordinary(env, h.replace(RenderFixtures.frame("hud", "next", 4)))
            assertEquals(
                2,
                f.channels.effectWrites,
                "The enclosing effect operation already acknowledged",
            )
            HudDialogueSurfaceTest.done(env, h.closeAsync())
            assertEquals(1, f.channels.hudCloses)
            assertEquals(1, f.channels.effectCloses)
        }
    }

    @Test
    fun hudNestedEffectFailureIsOrdinary(env: Env) {
        for (sync in listOf(false, true)) {
            val f = Fixture(env)
            val h = HudDialogueSurfaceTest.done(env, f.hud().open(f.context("hud")))
            HudDialogueSurfaceTest.done(env, h.replace(RenderFixtures.frame("hud", "accepted", 8)))
            f.channels.effect = { rejected(sync) }
            ordinary(env, h.replace(RenderFixtures.frame("hud", "next", 4)))
            assertEquals(1, f.channels.hudWrites)
            HudDialogueSurfaceTest.done(env, h.closeAsync())
        }
    }

    @Test
    fun npcNestedOutputFailureAfterInputInvalidationIsOrdinary(env: Env) {
        for (sync in listOf(false, true)) {
            val f = Fixture(env)
            f.channels.supported = false
            val h =
                HudDialogueSurfaceTest.done(
                    env,
                    f.npc().open(f.context("npc", mapOf("hideHud" to "true"))),
                )
            HudDialogueSurfaceTest.done(env, h.replace(RenderFixtures.frame("npc", "accepted", 8)))
            assertNotNull(f.targets.current())
            f.channels.hud = { rejected(sync) }
            ordinary(env, h.replace(RenderFixtures.frame("npc", "next", 4)))
            assertNull(f.targets.current())
            HudDialogueSurfaceTest.done(env, h.closeAsync())
            assertTrue(f.world.entities.none { it.entityType == EntityType.TEXT_DISPLAY })
        }
    }

    @Test
    fun npcNestedEffectFailureAfterInputInvalidationIsOrdinary(env: Env) {
        for (sync in listOf(false, true)) {
            val f = Fixture(env)
            val h =
                HudDialogueSurfaceTest.done(
                    env,
                    f.npc().open(f.context("npc", mapOf("vignette" to "true"))),
                )
            HudDialogueSurfaceTest.done(env, h.replace(RenderFixtures.frame("npc", "accepted", 8)))
            f.channels.effect = { rejected(sync) }
            HudDialogueSurfaceTest.done(
                env,
                h.configure(PresentationPreferences("npc", Duration.ZERO, false, emptyMap())),
            )
            ordinary(env, h.replace(RenderFixtures.frame("npc", "next", 4)))
            assertEquals(2, f.channels.effectWrites)
            assertNull(f.targets.current())
            HudDialogueSurfaceTest.done(env, h.closeAsync())
        }
    }

    @Test
    fun npcNestedReleaseFailureAfterInputInvalidationIsOrdinary(env: Env) {
        for (sync in listOf(false, true)) {
            val f = Fixture(env)
            f.channels.supported = false
            val h =
                HudDialogueSurfaceTest.done(
                    env,
                    f.npc().open(f.context("npc", mapOf("hideHud" to "true"))),
                )
            HudDialogueSurfaceTest.done(env, h.replace(RenderFixtures.frame("npc", "accepted", 8)))
            HudDialogueSurfaceTest.done(
                env,
                h.configure(PresentationPreferences("npc", Duration.ZERO, false, emptyMap())),
            )
            f.channels.release = { rejected(sync) }
            ordinary(env, h.replace(RenderFixtures.frame("npc", "next", 4)))
            assertEquals(1, f.channels.hudCloses)
            assertNull(f.targets.current())
            assertThrows(CompletionException::class.java) {
                HudDialogueSurfaceTest.done(env, h.closeAsync())
            }
        }
    }

    @Test
    fun npcNestedAcquisitionFailureAfterInputInvalidationIsOrdinary(env: Env) {
        for (sync in listOf(false, true)) {
            val f = Fixture(env)
            f.channels.supported = false
            val h = HudDialogueSurfaceTest.done(env, f.npc().open(f.context("npc")))
            val frame = RenderFixtures.frame("npc", "accepted", 8)
            val small =
                WidgetFrame(
                    frame.semantic(),
                    frame.widgets().filter { it.kind != WidgetFrame.Kind.CHOICE },
                    frame.fontPin(),
                    frame.theme(),
                    frame.chrome(),
                    frame.scrollbar(),
                )
            HudDialogueSurfaceTest.done(env, h.replace(small))
            f.spawn = { _, _, _ -> rejected(sync) }
            ordinary(env, h.replace(frame))
            assertNull(f.targets.current())
            HudDialogueSurfaceTest.done(env, h.closeAsync())
            assertTrue(f.world.entities.none { it.entityType == EntityType.TEXT_DISPLAY })
        }
    }
}
