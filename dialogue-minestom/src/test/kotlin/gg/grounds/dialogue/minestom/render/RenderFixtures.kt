package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.validation.ContentPin
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.WidgetFrame
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RenderFixtures private constructor() {
    companion object {
        @JvmField val SESSION: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")

        @JvmField
        val ASSETS: AssetBundle = AssetBundle.loadDefault(MinecraftPackProfile.public262())

        @JvmStatic
        fun view(text: String, revealed: Int, session: UUID = SESSION): DialogueView =
            DialogueView(
                session,
                ContentPin("0".repeat(64)),
                "start",
                1,
                DialogueView.State.REVEALING,
                1,
                1,
                "en",
                "Mechanic",
                text,
                listOf(
                    DialogueView.ChoiceView(
                        "ask",
                        "Ask",
                        DialogueView.Availability.AVAILABLE,
                        "",
                    ),
                    DialogueView.ChoiceView(
                        "leave",
                        "Leave",
                        DialogueView.Availability.AVAILABLE,
                        "",
                    ),
                ),
                "ask",
                text.length,
                revealed,
                DialogueView.HintPhase.CHOOSE,
                "",
                null,
            )

        @JvmStatic
        fun frame(key: String, text: String, reveal: Int): WidgetFrame {
            val clock =
                object : DialogueScheduler {
                    override fun execute(command: Runnable) = command.run()

                    override fun schedule(
                        delay: Duration,
                        command: Runnable,
                    ): DialogueScheduler.ScheduledTask = DialogueScheduler.ScheduledTask {}

                    override fun nanoTime(): Long = 0
                }
            val presenter =
                DialoguePresenter(
                    mapOf(
                        key to
                            DialoguePresenter.SurfaceProfile(
                                {
                                    CompletableFuture.failedFuture(UnsupportedOperationException())
                                },
                                if (key == "npc") LayoutProfile.npc() else LayoutProfile.hud(),
                                DialogueTheme.neutral(),
                                ASSETS.metrics(),
                                false,
                            )
                    ),
                    clock,
                )
            return presenter.compose(view(text, reveal), key, PresentationPreferences.defaults())
        }
    }
}
