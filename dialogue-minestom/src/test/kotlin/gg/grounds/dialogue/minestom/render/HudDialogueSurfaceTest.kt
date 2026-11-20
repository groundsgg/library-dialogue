package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.LocalOutputChannels
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.ConnectionGuard
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.LayoutProfile
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import net.kyori.adventure.key.Key
import net.kyori.adventure.resource.ResourcePackStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class HudDialogueSurfaceTest {
    companion object {
        @JvmStatic
        fun <T> done(env: Env, stage: CompletionStage<T>): T {
            assertTrue(
                env.tickWhile(
                    { !stage.toCompletableFuture().isDone },
                    Duration.ofSeconds(2),
                )
            )
            return stage.toCompletableFuture().join()
        }
    }

    private inner class Fixture(env: Env, supported: Boolean) {
        val player: Player =
            env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val hud = ArrayList<Component>()
        val effects = ArrayList<List<Key>>()
        val diagnostics = ArrayList<String>()
        val targets = SurfaceInputTargets()
        val channels =
            LocalOutputChannels(
                { _, component ->
                    hud.add(component)
                    CompletableFuture.completedFuture(null)
                },
                supported,
                { _, keys ->
                    effects.add(keys)
                    CompletableFuture.completedFuture(null)
                },
            )
        val surface: HudDialogueSurface

        init {
            val packs = PackStateTracker()
            val id = UUID.randomUUID()
            packs.expect(player, 1, id, RenderFixtures.ASSETS.pin())
            packs.observe(
                player,
                1,
                id,
                RenderFixtures.ASSETS.pin(),
                ResourcePackStatus.SUCCESSFULLY_LOADED,
            )
            surface =
                HudDialogueSurface(
                    player,
                    1,
                    ConnectionGuard(player, 1, player.instance),
                    packs,
                    RenderFixtures.ASSETS,
                    channels,
                    targets,
                    MinestomDialogueScheduler(player.instance),
                    diagnostics::add,
                )
        }

        fun open(env: Env, effects: Map<String, String>): DialogueSurface.SurfaceHandle =
            done(
                env,
                surface.open(
                    DialogueSurface.SurfaceContext(
                        RenderFixtures.SESSION,
                        player.uuid.toString(),
                        1,
                        "hud",
                        "profile",
                        CancellationToken(),
                        1,
                        Long.MAX_VALUE,
                        PresentationPreferences("hud", Duration.ZERO, false, effects),
                    )
                ),
            )
    }

    @Test
    fun coverageAndVignetteAreIndependent(env: Env) {
        val fixture = Fixture(env, true)
        val handle = fixture.open(env, mapOf("hideHud" to "true"))
        done(env, handle.replace(RenderFixtures.frame("hud", "Hello", 5)))
        assertTrue(
            PlainTextComponentSerializer.plainText()
                .serialize(fixture.hud.last())
                .contains("\ue200")
        )
        assertEquals(128, HudCoverage.fadeGuiPixels())
        assertTrue(fixture.effects.last().isEmpty())
        done(
            env,
            handle.configure(
                PresentationPreferences(
                    "hud",
                    Duration.ZERO,
                    false,
                    mapOf("vignette" to "true"),
                )
            ),
        )
        done(env, handle.replace(RenderFixtures.frame("hud", "Hello", 5)))
        assertFalse(
            PlainTextComponentSerializer.plainText()
                .serialize(fixture.hud.last())
                .contains("\ue200")
        )
        assertEquals(listOf(Key.key("dialogue:vignette")), fixture.effects.last())
        done(env, handle.closeAsync())
    }

    @Test
    fun foreignHudReturnsAfterClose(env: Env) {
        val fixture = Fixture(env, false)
        val foreign = fixture.channels.acquireHud(fixture.player, 1, 10)
        done(env, foreign.replace(Component.text("foreign")))
        val handle = fixture.open(env, emptyMap())
        done(env, handle.replace(RenderFixtures.frame("hud", "Hello", 5)))
        done(env, handle.closeAsync())
        assertEquals(
            "foreign",
            PlainTextComponentSerializer.plainText().serialize(fixture.hud.last()),
        )
        done(env, foreign.closeAsync())
    }

    @Test
    fun unsupportedEffectDoesNotBreakHud(env: Env) {
        val fixture = Fixture(env, false)
        val health = fixture.player.health
        val food = fixture.player.food
        val selected = fixture.player.heldSlot
        val handle = fixture.open(env, mapOf("vignette" to "true"))
        done(env, handle.replace(RenderFixtures.frame("hud", "Hello", 5)))
        assertTrue(fixture.diagnostics.contains("POST_EFFECT_UNSUPPORTED"))
        assertEquals(health, fixture.player.health)
        assertEquals(food, fixture.player.food)
        assertEquals(selected, fixture.player.heldSlot)
        done(env, handle.closeAsync())
    }

    @Test
    fun finalHintSaysEnd(env: Env) {
        val fixture = Fixture(env, false)
        val handle = fixture.open(env, emptyMap())
        val view = RenderFixtures.view("Hello", 5)
        val finished =
            DialogueView(
                view.sessionId(),
                view.contentPin(),
                view.nodeId(),
                1,
                DialogueView.State.WAITING_FOR_INPUT,
                2,
                2,
                "en",
                "NPC",
                "Hello",
                emptyList(),
                null,
                5,
                5,
                DialogueView.HintPhase.FINISH,
                "",
                null,
            )
        val presenter =
            DialoguePresenter(
                mapOf(
                    "hud" to
                        DialoguePresenter.SurfaceProfile(
                            fixture.surface,
                            LayoutProfile.hud(),
                            DialogueTheme.neutral(),
                            RenderFixtures.ASSETS.metrics(),
                            false,
                        )
                ),
                MinestomDialogueScheduler(fixture.player.instance),
            )
        val frame = presenter.compose(finished, "hud", PresentationPreferences.defaults())
        done(env, handle.replace(frame))
        assertTrue(
            PlainTextComponentSerializer.plainText().serialize(fixture.hud.last()).contains("End")
        )
        done(env, handle.closeAsync())
    }

    @Test
    fun lateFrameAfterCloseIsIgnored(env: Env) {
        val fixture = Fixture(env, false)
        val handle = fixture.open(env, emptyMap())
        done(env, handle.closeAsync())
        val count = fixture.hud.size
        assertThrows(CompletionException::class.java) {
            done(env, handle.replace(RenderFixtures.frame("hud", "Hello", 5)))
        }
        assertEquals(count, fixture.hud.size)
        assertNull(fixture.targets.current())
    }
}
