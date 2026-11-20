package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.Graphemes
import gg.grounds.dialogue.core.validation.ContentPin
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

object PresenterFixtures {
    class Clock : DialogueScheduler {
        var now = 0L

        override fun execute(runnable: Runnable) = runnable.run()

        override fun schedule(
            duration: Duration,
            runnable: Runnable,
        ): DialogueScheduler.ScheduledTask = DialogueScheduler.ScheduledTask {}

        override fun nanoTime(): Long = now
    }

    val SESSION: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")

    fun view(text: String, reveal: Int, selected: String, revision: Long): DialogueView {
        val choices =
            listOf("c1", "c2", "c3", "c4", "c5", "c6", "leave").map {
                DialogueView.ChoiceView(it, it, DialogueView.Availability.AVAILABLE, "")
            }
        return DialogueView(
            SESSION,
            ContentPin("0".repeat(64)),
            "start",
            1,
            if (reveal < text.length) DialogueView.State.REVEALING
            else DialogueView.State.WAITING_FOR_INPUT,
            revision,
            1,
            "en",
            "Mechanic",
            text,
            choices,
            selected,
            Graphemes.split(text).size,
            reveal,
            DialogueView.HintPhase.CHOOSE,
            "",
            null,
        )
    }

    open class Surface : DialogueSurface {
        var opens = 0
        var closes = 0
        val frames = ArrayList<WidgetFrame>()
        var delayed: CompletableFuture<DialogueSurface.SurfaceHandle>? = null

        override fun open(
            context: DialogueSurface.SurfaceContext
        ): CompletionStage<DialogueSurface.SurfaceHandle> {
            opens++
            val handle = handle()
            return delayed ?: CompletableFuture.completedFuture(handle)
        }

        open fun handle(): DialogueSurface.SurfaceHandle =
            object : DialogueSurface.SurfaceHandle {
                override fun replace(frame: WidgetFrame): CompletionStage<Void> {
                    frames += frame
                    return CompletableFuture.completedFuture(null)
                }

                override fun closeAsync(): CompletionStage<Void> {
                    closes++
                    return CompletableFuture.completedFuture(null)
                }
            }
    }

    fun presenter(hud: Surface, npc: Surface): DialoguePresenter =
        DialoguePresenter(
            mapOf(
                "hud" to
                    DialoguePresenter.SurfaceProfile(
                        hud,
                        LayoutProfile.hud(),
                        DialogueTheme.neutral(),
                        FontMetrics.defaults(),
                        false,
                    ),
                "npc" to
                    DialoguePresenter.SurfaceProfile(
                        npc,
                        LayoutProfile.npc(),
                        DialogueTheme.neutral(),
                        FontMetrics.defaults(),
                        false,
                    ),
            ),
            Clock(),
        )

    fun request(): DialogueRenderer.PresentationRequest =
        DialogueRenderer.PresentationRequest(
            SESSION,
            "player",
            1,
            PresentationPreferences.defaults(),
            CancellationToken(),
            5_000_000_000L,
        )
}
