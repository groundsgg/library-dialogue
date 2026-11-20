package example

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.port.OutcomeSink
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueHandle
import gg.grounds.dialogue.core.runtime.DialogueInput
import gg.grounds.dialogue.core.runtime.DialogueRuntime
import gg.grounds.dialogue.core.runtime.DialogueStart
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.json.DialogueCatalogLoader
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.FontMetrics
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.WidgetFrame
import java.io.UncheckedIOException
import java.time.Duration
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** A complete, deterministic Kotlin consumer. No network, game server or real sleeps. */
object HeadlessDialogue {
    class StepClock : DialogueScheduler {
        private var now = 0L
        private var sequence = 0L
        private val ready = ArrayDeque<Runnable>()
        private val jobs = PriorityQueue(compareBy<Job> { it.at }.thenBy { it.sequence })

        private class Job(val at: Long, val sequence: Long, val run: Runnable) {
            var cancelled = false
        }

        override fun execute(command: Runnable) {
            ready.add(command)
        }

        override fun nanoTime(): Long = now

        override fun schedule(delay: Duration, command: Runnable): DialogueScheduler.ScheduledTask {
            val job = Job(Math.addExact(now, delay.toNanos()), sequence++, command)
            jobs.add(job)
            return DialogueScheduler.ScheduledTask { job.cancelled = true }
        }

        fun drain() {
            var budget = 10_000
            while (ready.isNotEmpty()) {
                if (--budget == 0) error("Nonterminating command loop")
                ready.remove().run()
            }
        }

        fun advance(duration: Duration) {
            val target = Math.addExact(now, duration.toNanos())
            drain()
            while (jobs.isNotEmpty() && jobs.peek().at <= target) {
                val job = jobs.remove()
                now = job.at
                if (!job.cancelled) job.run.run()
                drain()
            }
            now = target
            drain()
        }
    }

    @JvmStatic
    fun bytes(name: String): ByteArray {
        try {
            val input =
                HeadlessDialogue::class.java.getResourceAsStream("/$name")
                    ?: error("Missing resource $name")
            input.use {
                val value = it.readNBytes(16_777_217)
                if (value.size > 16_777_216) error("Input too large")
                return value
            }
        } catch (exception: java.io.IOException) {
            throw UncheckedIOException(exception)
        }
    }

    @JvmStatic
    fun sources() =
        DialogueCatalogLoader.ContentSources(
            mapOf("dialogues/mechanic.json" to bytes("dialogues/mechanic.json")),
            mapOf("en" to bytes("texts/en.json"), "de" to bytes("texts/de.json")),
            bytes("context/host-catalog.json"),
        )

    @JvmStatic
    fun recording(views: MutableList<DialogueView>): DialogueRenderer = DialogueRenderer {
        CompletableFuture.completedFuture(
            object : DialogueRenderer.PresentationHandle {
                override fun replace(view: DialogueView): CompletionStage<Void> {
                    views += view
                    return CompletableFuture.completedFuture(null)
                }

                override fun reconfigure(
                    preferences: PresentationPreferences
                ): CompletionStage<Void> = CompletableFuture.completedFuture(null)

                override fun closeAsync(): CompletionStage<Void> =
                    CompletableFuture.completedFuture(null)
            }
        )
    }

    @JvmStatic
    fun <T> done(stage: CompletionStage<T>, clock: StepClock): T {
        clock.drain()
        check(stage.toCompletableFuture().isDone) { "Scenario left a pending stage" }
        return stage.toCompletableFuture().join()
    }

    @JvmStatic
    fun stamp(handle: DialogueHandle, impulse: Long): DialogueInput.InputStamp {
        val view = handle.snapshot()
        return DialogueInput.InputStamp(view.sessionId(), view.renderRevision(), impulse)
    }

    @JvmStatic fun runSmoke(): String = scenario(graphical = false)

    @JvmStatic fun runPresentationSmoke(): String = scenario(graphical = true)

    private class RecordedSurface : DialogueSurface {
        var opens = 0
        var closes = 0
        val frames = ArrayList<WidgetFrame>()

        override fun open(
            context: DialogueSurface.SurfaceContext
        ): CompletionStage<DialogueSurface.SurfaceHandle> {
            opens++
            return CompletableFuture.completedFuture(
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
            )
        }
    }

    private fun scenario(graphical: Boolean): String {
        val loader = DialogueCatalogLoader()
        val loaded = loader.reload(sources())
        check(loaded.isValid()) { loaded.problems().toString() }
        val content = loader.current().orElseThrow().entries()["demo:mechanic"]!!
        val clock = StepClock()
        val frames = ArrayList<DialogueView>()
        val events = ArrayList<OutcomeSink.OutcomeEvent>()
        val hud = RecordedSurface()
        val npc = RecordedSurface()
        val renderer: DialogueRenderer =
            if (graphical) {
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
                    clock,
                )
            } else {
                recording(frames)
            }
        val bindings =
            HostBindings(
                "headless-v1",
                emptyMap(),
                emptyMap(),
                {
                    events += it
                    CompletableFuture.completedFuture(OutcomeSink.OutcomeResult.ACKNOWLEDGED)
                },
                TextResolver.catalogue(content.texts(), "en"),
                renderer,
                null,
                setOf("dialogue.choices"),
            )
        val runtime = DialogueRuntime.create(RuntimeOptions.defaults(clock))
        val request =
            DialogueStart(
                "smoke",
                "demo-player",
                1,
                content,
                bindings,
                emptyMap(),
                LocaleContext("en", 0, "en"),
                PresentationPreferences("hud", Duration.ZERO, true, emptyMap()),
                CancellationToken(),
                0,
            )
        val handle = (done(runtime.start(request), clock) as DialogueStartResult.Started).handle
        check(handle.snapshot().choices().size == 4) { "Expected four choices" }
        val visit = handle.snapshot().nodeVisitId()
        if (graphical) {
            done(
                handle.updatePreferences(
                    PresentationPreferences("npc", Duration.ZERO, true, emptyMap())
                ),
                clock,
            )
            check(handle.snapshot().nodeVisitId() == visit && hud.closes == 1 && npc.opens == 1) {
                "Surface switch changed the session or leaked a surface"
            }
        }
        done(handle.refreshLocale(LocaleContext("de", 1, "en")), clock)
        check(handle.snapshot().nodeVisitId() == visit) { "Locale restarted the node" }
        done(handle.submit(DialogueInput.Select(stamp(handle, 1), "ask_lift")), clock)
        done(handle.submit(DialogueInput.Confirm(stamp(handle, 2), "ask_lift")), clock)
        check(handle.snapshot().nodeId() == "explain_lift") { "Choice was not followed" }
        done(handle.submit(DialogueInput.Confirm(stamp(handle, 3), null)), clock)
        check(events.size == 1 && handle.snapshot().hint() == DialogueView.HintPhase.FINISH) {
            "Expected explicit milestone and final hint"
        }
        done(handle.submit(DialogueInput.Confirm(stamp(handle, 4), null)), clock)
        check(done(handle.completion(), clock).reason() == DialogueEndReason.COMPLETED) {
            "Did not complete"
        }
        done(runtime.closeAsync(), clock)
        check(!graphical || npc.closes == 1 && npc.frames.isNotEmpty()) {
            "Surface did not render or close"
        }
        return if (graphical) "PRESENTATION_OK" else "HEADLESS_DIALOGUE_OK"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println(
            if (args.firstOrNull() == "--presentation-smoke") runPresentationSmoke() else runSmoke()
        )
    }
}
