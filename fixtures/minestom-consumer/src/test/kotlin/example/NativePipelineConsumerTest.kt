package example

import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.LocalOutputChannels
import gg.grounds.dialogue.minestom.input.*
import gg.grounds.dialogue.minestom.render.*
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.UiLabelSource
import java.util.concurrent.CompletableFuture
import net.kyori.adventure.text.Component
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class KotlinNativePipelineConsumerTest {
    @Test
    fun kotlinCustomNativePipelineAcknowledgesOutputAcceptsInputAndCloses(env: Env) {
        NativePipeline.exercise(env) { player, clock ->
            val scope = MinestomConnectionScope(player, 7, player.instance)
            val targets = SurfaceInputTargets()
            val tokens = ChatInputTokens(scope, targets, clock::nanoTime)
            val acknowledgement = CompletableFuture<Void>()
            val output = ArrayList<Component>()
            val labels = UiLabelSource.defaults()
            val controls = DialoguePresenter.Controls.defaults()
            val chat =
                ChatDialogueSurface(
                    scope,
                    targets,
                    tokens,
                    {
                        output += it
                        acknowledgement
                    },
                    labels,
                    controls,
                    "/native",
                )
            val assets = AssetBundle.loadDefault(MinecraftPackProfile.public262())
            val channels =
                LocalOutputChannels(
                    { _, _ -> CompletableFuture.completedFuture(null) },
                    false,
                    { _, _ -> CompletableFuture.completedFuture(null) },
                )
            val packs = PackStateTracker()
            val hud = HudDialogueSurface(scope, packs, assets, channels, targets, clock, {})
            val npc =
                NpcDialogueSurface(scope, packs, assets, channels, targets, clock, { null }, {})
            val profiles =
                mapOf(
                    "chat" to DialoguePresenter.SurfaceProfile.semantic(chat),
                    "hud" to DialoguePresenter.SurfaceProfile.semantic(hud),
                    "npc" to DialoguePresenter.SurfaceProfile.semantic(npc),
                )
            val shortRenderer =
                MinestomDialogueRenderer(scope, profiles, clock, controls, labels, targets, {})
            val spi: DialogueInputSource = shortRenderer
            assertTrue(spi.resolveChat(player, 7, "unused").isEmpty)
            val renderer =
                MinestomDialogueRenderer(
                    scope,
                    profiles,
                    clock,
                    controls,
                    labels,
                    targets,
                    {},
                    tokens,
                    npc::geometry,
                )
            assertThrows(IllegalArgumentException::class.java) {
                ChatDialogueSurface(
                    MinestomConnectionScope(player, 7, player.instance),
                    targets,
                    tokens,
                    { CompletableFuture.completedFuture(null) },
                    labels,
                    controls,
                    "/native",
                )
            }
            NativePipeline.Wiring(
                scope,
                targets,
                tokens,
                renderer,
                { cancellation, target ->
                    MinestomInputAdapter(
                            scope,
                            InputProfile.defaults(),
                            clock,
                            cancellation,
                            target,
                            { _, _ -> true },
                        )
                        .closeAsync()
                    MinestomInputAdapter(
                        scope,
                        InputProfile.defaults(),
                        clock,
                        cancellation,
                        target,
                        { _, _ -> true },
                        NativePipeline.budgets(),
                    )
                },
                { PrivateInteractionCatcher.Companion.open(it) },
                acknowledgement,
                output,
            )
        }
    }
}
