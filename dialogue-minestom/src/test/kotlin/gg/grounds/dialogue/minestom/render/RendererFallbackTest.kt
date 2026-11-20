package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueRuntime
import gg.grounds.dialogue.core.runtime.DialogueStart
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.UiLabelSource
import gg.grounds.dialogue.presentation.WidgetFrame
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class RendererFallbackTest {
    private class Surface : DialogueSurface {
        var writes = 0
        var closes = 0
        var fail = false
        var openError: Throwable? = null
        var drawError: Throwable? = null
        var gate: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
        var cleanup: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

        override fun open(
            context: DialogueSurface.SurfaceContext
        ): CompletionStage<DialogueSurface.SurfaceHandle> {
            openError?.let {
                return CompletableFuture.failedFuture(it)
            }
            return CompletableFuture.completedFuture(
                object : DialogueSurface.SurfaceHandle {
                    override fun replace(frame: WidgetFrame): CompletionStage<Void> {
                        writes++
                        drawError?.let {
                            return CompletableFuture.failedFuture(it)
                        }
                        return if (fail) {
                            CompletableFuture.failedFuture(
                                DialoguePresenter.FallbackRequired("MISSING_PACK")
                            )
                        } else gate
                    }

                    override fun closeAsync(): CompletionStage<Void> {
                        closes++
                        return cleanup
                    }
                }
            )
        }
    }

    private fun <T> done(env: Env, stage: CompletionStage<T>): T =
        HudDialogueSurfaceTest.done(env, stage)

    private inner class Fixture(env: Env) {
        val clock = MinestomDialogueScheduler(env.createFlatInstance())
        val hud = Surface()
        val chat = Surface()
        val npc = Surface()
        val targets = SurfaceInputTargets()
        val statuses = ArrayList<PresentationStatus>()
        val core = DialogueRuntime.create(RuntimeOptions(clock, budgets(), 65_536, 256))
        val renderer =
            MinestomDialogueRenderer(
                mapOf(
                    "hud" to
                        DialoguePresenter.SurfaceProfile(
                            hud,
                            LayoutProfile.hud(),
                            DialogueTheme.neutral(),
                            RenderFixtures.ASSETS.metrics(),
                            false,
                        ),
                    "npc" to
                        DialoguePresenter.SurfaceProfile(
                            npc,
                            LayoutProfile.npc(),
                            DialogueTheme.neutral(),
                            RenderFixtures.ASSETS.metrics(),
                            false,
                        ),
                    "chat" to DialoguePresenter.SurfaceProfile.semantic(chat),
                ),
                clock,
                DialoguePresenter.Controls.defaults(),
                UiLabelSource.defaults(),
                targets,
                statuses::add,
            )

        fun start(text: String): CompletionStage<DialogueStartResult> {
            val catalog = TextCatalog(mapOf("en" to mapOf("line" to text)))
            val definition =
                DialogueBuilder.dialogue("test:fallback")
                    .node("start", Message("npc", "line", null, emptyList(), "end"))
                    .node("end", End("completed"))
                    .build()
            val content =
                DialogueValidator()
                    .validate(
                        definition,
                        catalog,
                        HostCatalog(
                            "v1",
                            setOf("npc"),
                            emptyMap(),
                            emptyMap(),
                            emptySet(),
                            emptySet(),
                            false,
                        ),
                        ValidationLimits.defaults(),
                    )
                    .validated()!!
            return core.start(
                DialogueStart(
                    "test",
                    "player",
                    1,
                    content,
                    HostBindings(
                        "v1",
                        emptyMap(),
                        emptyMap(),
                        null,
                        TextResolver.catalogue(catalog, "en"),
                        renderer,
                        null,
                        emptySet(),
                    ),
                    emptyMap(),
                    LocaleContext("en", 0, "en"),
                    PresentationPreferences("hud", Duration.ZERO, false, emptyMap()),
                    CancellationToken(),
                    0,
                )
            )
        }
    }

    private fun budgets() =
        RuntimeOptions.Budgets(
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
        )

    @Test
    fun sameSemanticViewStillWaitsForTheNewPresentationWrite(env: Env) {
        val fixture = Fixture(env)
        val handle =
            done(
                env,
                fixture.renderer.prepare(
                    DialogueRenderer.PresentationRequest(
                        RenderFixtures.SESSION,
                        "player",
                        1,
                        PresentationPreferences("hud", Duration.ZERO, false, emptyMap()),
                        CancellationToken(),
                        System.nanoTime() + 5_000_000_000L,
                        budgets(),
                    )
                ),
            )
        val view = RenderFixtures.view("Hello", 5)
        val firstGate = CompletableFuture<Void>()
        fixture.hud.gate = firstGate
        val first = handle.replace(view)
        assertTrue(env.tickWhile({ fixture.hud.writes < 1 }, Duration.ofSeconds(1)))
        val next = handle.replace(view)
        repeat(10) { env.tick() }
        val secondGate = CompletableFuture<Void>()
        fixture.hud.gate = secondGate
        firstGate.complete(null)
        repeat(30) { env.tick() }
        assertEquals(
            2,
            fixture.hud.writes,
            "A presentation change must redraw even with identical core view",
        )
        assertFalse(next.toCompletableFuture().isDone, "Await actual new output acknowledgement")
        secondGate.complete(null)
        done(env, first)
        done(env, next)
        done(env, handle.closeAsync())
        done(env, fixture.core.closeAsync())
    }

    @Test
    fun closeSettlesOutstandingDirectReconfiguration(env: Env) {
        val fixture = Fixture(env)
        val handle =
            done(
                env,
                fixture.renderer.prepare(
                    DialogueRenderer.PresentationRequest(
                        RenderFixtures.SESSION,
                        "player",
                        1,
                        PresentationPreferences("hud", Duration.ZERO, false, emptyMap()),
                        CancellationToken(),
                        System.nanoTime() + 5_000_000_000L,
                        budgets(),
                    )
                ),
            )
        done(env, handle.replace(RenderFixtures.view("Hello", 5)))
        fixture.npc.gate = CompletableFuture()
        val change =
            handle.reconfigure(PresentationPreferences("npc", Duration.ZERO, false, emptyMap()))
        assertTrue(env.tickWhile({ fixture.npc.writes == 0 }, Duration.ofSeconds(2)))
        val close = handle.closeAsync()
        fixture.npc.gate.complete(null)
        done(env, close)
        assertTrue(
            change.toCompletableFuture().isCompletedExceptionally,
            "Closing must settle the public operation",
        )
        done(env, fixture.core.closeAsync())
    }

    @Test
    fun firstFrameMissingGlyphStartsInChat(env: Env) {
        val fixture = Fixture(env)
        fixture.chat.gate = CompletableFuture()
        val start = fixture.start("Hello 世界")
        assertTrue(env.tickWhile({ fixture.chat.writes == 0 }, Duration.ofSeconds(2)))
        assertFalse(start.toCompletableFuture().isDone)
        fixture.chat.gate.complete(null)
        val started = done(env, start) as DialogueStartResult.Started
        assertEquals("chat", fixture.statuses.last().effectiveSurface)
        assertEquals("hud", fixture.statuses.last().requestedSurface)
        assertEquals(1, started.handle.snapshot().nodeVisitId())
        assertEquals(0, fixture.hud.writes)
        done(env, fixture.core.closeAsync())
    }

    @Test
    fun missingPackAtPreparationUsesSemanticSurface(env: Env) {
        val fixture = Fixture(env)
        fixture.hud.openError = DialoguePresenter.FallbackRequired("MISSING_PACK")
        assertInstanceOf(DialogueStartResult.Started::class.java, done(env, fixture.start("Hello")))
        assertEquals("chat", fixture.statuses.last().effectiveSurface)
        assertEquals(0, fixture.hud.writes)
        done(env, fixture.core.closeAsync())
    }

    @Test
    fun transportFailureDuringPreferenceChangeIsFatal(env: Env) {
        val fixture = Fixture(env)
        val handle = (done(env, fixture.start("Hello")) as DialogueStartResult.Started).handle
        fixture.npc.drawError = IllegalStateException("uncertain transport")
        val change =
            handle.updatePreferences(
                PresentationPreferences("npc", Duration.ZERO, false, emptyMap())
            )
        assertThrows(CompletionException::class.java) { done(env, change) }
        assertTrue(
            env.tickWhile(
                { !handle.completion().toCompletableFuture().isDone },
                Duration.ofSeconds(2),
            )
        )
        assertEquals(DialogueEndReason.FAILED, done(env, handle.completion()).reason())
        done(env, fixture.core.closeAsync())
    }

    @Test
    fun revokedPackInvalidatesOldTargets(env: Env) {
        val fixture = Fixture(env)
        val handle = (done(env, fixture.start("Hello")) as DialogueStartResult.Started).handle
        fixture.targets.publish(RenderFixtures.frame("hud", "Hello", 5), 1, emptyMap())
        fixture.hud.fail = true
        done(env, handle.refreshLocale(LocaleContext("en", 1, "en")))
        assertEquals("chat", fixture.statuses.last().effectiveSurface)
        assertNull(fixture.targets.current())
        done(env, fixture.core.closeAsync())
    }

    @Test
    fun unknownCleanupFailureRemainsFatal(env: Env) {
        val fixture = Fixture(env)
        fixture.hud.cleanup =
            CompletableFuture.failedFuture(IllegalStateException("transport uncertain"))
        assertInstanceOf(
            DialogueStartResult.Rejected::class.java,
            done(env, fixture.start("Hello 世界")),
        )
        assertThrows(CompletionException::class.java) { done(env, fixture.core.closeAsync()) }
    }

    @Test
    fun newerPreferenceSupersedesFallback(env: Env) {
        val fixture = Fixture(env)
        val handle = (done(env, fixture.start("Hello")) as DialogueStartResult.Started).handle
        fixture.chat.gate = CompletableFuture()
        fixture.hud.fail = true
        val refresh = handle.refreshLocale(LocaleContext("en", 1, "en"))
        assertTrue(env.tickWhile({ fixture.chat.writes == 0 }, Duration.ofSeconds(2)))
        val change =
            handle.updatePreferences(
                PresentationPreferences("npc", Duration.ZERO, false, emptyMap())
            )
        done(env, change)
        fixture.chat.gate.complete(null)
        done(env, refresh)
        assertEquals("npc", fixture.statuses.last().effectiveSurface)
        assertEquals("npc", fixture.statuses.last().requestedSurface)
        done(env, fixture.core.closeAsync())
    }
}
