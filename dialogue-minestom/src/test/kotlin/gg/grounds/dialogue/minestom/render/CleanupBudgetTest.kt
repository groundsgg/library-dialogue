package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.OutputChannels
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.presentation.DialogueSurface
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import net.kyori.adventure.resource.ResourcePackStatus
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class CleanupBudgetTest {
    @Test
    fun nativeSurfaceUsesConfiguredCleanupDeadline(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val packs = PackStateTracker()
        val pack = UUID.randomUUID()
        packs.expect(player, 1, pack, RenderFixtures.ASSETS.pin())
        packs.observe(
            player,
            1,
            pack,
            RenderFixtures.ASSETS.pin(),
            ResourcePackStatus.SUCCESSFULLY_LOADED,
        )
        val gate = CompletableFuture<Void>()
        val channels =
            object : OutputChannels {
                override fun acquireHud(
                    player: Player,
                    generation: Long,
                    priority: Int,
                ): OutputChannels.HudLease =
                    object : OutputChannels.HudLease {
                        override fun replace(value: Component): CompletionStage<Void> =
                            CompletableFuture.completedFuture(null)

                        override fun closeAsync(): CompletionStage<Void> = gate
                    }

                override fun acquireEffects(
                    player: Player,
                    generation: Long,
                ): OutputChannels.EffectLease = throw UnsupportedOperationException()

                override fun supportedEffects(): Boolean = false
            }
        val surface =
            HudDialogueSurface(
                MinestomConnectionScope(player, 1, player.instance),
                packs,
                RenderFixtures.ASSETS,
                channels,
                SurfaceInputTargets(),
                MinestomDialogueScheduler(player.instance),
                {},
            )
        val context =
            DialogueSurface.SurfaceContext(
                RenderFixtures.SESSION,
                player.uuid.toString(),
                1,
                "hud",
                "profile",
                CancellationToken(),
                1,
                Long.MAX_VALUE,
                PresentationPreferences.defaults(),
                Duration.ofMillis(25),
            )
        val handle = HudDialogueSurfaceTest.done(env, surface.open(context))
        HudDialogueSurfaceTest.done(
            env,
            handle.replace(RenderFixtures.frame("hud", "Hello", 5)),
        )
        val close = handle.closeAsync()
        assertTrue(
            env.tickWhile(
                { !close.toCompletableFuture().isDone },
                Duration.ofSeconds(1),
            )
        )
        assertThrows(CompletionException::class.java) { close.toCompletableFuture().join() }
        gate.complete(null)
        env.tick()
    }
}
