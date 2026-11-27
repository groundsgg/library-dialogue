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
import gg.grounds.dialogue.minestom.MinestomConnectionScope
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
        val frames = ArrayList<WidgetFrame>()
        var draw: ((WidgetFrame) -> CompletionStage<Void>)? = null
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
                        frames.add(frame)
                        draw?.let {
                            return it(frame)
                        }
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

    private inner class Fixture(
        env: Env,
        scheduler: gg.grounds.dialogue.core.port.DialogueScheduler? = null,
    ) {
        val player =
            env.createConnection()
                .connect(
                    env.createFlatInstance(),
                    net.minestom.server.coordinate.Pos(0.0, 42.0, 0.0),
                )
        val scope = gg.grounds.dialogue.minestom.MinestomConnectionScope(player, 1, player.instance)
        val clock = scheduler ?: MinestomDialogueScheduler(player.instance)
        val hud = Surface()
        val chat = Surface()
        val npc = Surface()
        val targets = SurfaceInputTargets()
        val statuses = ArrayList<PresentationStatus>()
        val core = DialogueRuntime.create(RuntimeOptions(clock, budgets(), 65_536, 256))
        val renderer =
            MinestomDialogueRenderer(
                scope,
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
                    player.uuid.toString(),
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

    private fun request(fixture: Fixture, session: java.util.UUID = RenderFixtures.SESSION) =
        DialogueRenderer.PresentationRequest(
            session,
            fixture.player.uuid.toString(),
            1,
            PresentationPreferences("chat", Duration.ZERO, false, emptyMap()),
            CancellationToken(),
            System.nanoTime() + 5_000_000_000L,
            budgets(),
        )

    internal class Clock : gg.grounds.dialogue.core.port.DialogueScheduler {
        var now = 0L
        private val ready = java.util.ArrayDeque<Runnable>()

        private class Task(val at: Long, val action: Runnable) {
            var cancelled = false
        }

        private val timers = java.util.PriorityQueue<Task>(compareBy { it.at })

        override fun execute(command: Runnable) {
            ready.add(command)
        }

        override fun nanoTime(): Long = now

        override fun schedule(
            delay: Duration,
            command: Runnable,
        ): gg.grounds.dialogue.core.port.DialogueScheduler.ScheduledTask {
            val task = Task(now + delay.toNanos(), command)
            timers.add(task)
            return gg.grounds.dialogue.core.port.DialogueScheduler.ScheduledTask {
                task.cancelled = true
            }
        }

        fun drain() {
            var remaining = 10000
            while (ready.isNotEmpty()) {
                check(remaining-- > 0)
                ready.remove().run()
            }
        }

        fun advance(duration: Duration) {
            val end = now + duration.toNanos()
            drain()
            while (timers.isNotEmpty() && timers.peek().at <= end) {
                val task = timers.remove()
                now = task.at
                if (!task.cancelled) task.action.run()
                drain()
            }
            now = end
            drain()
        }
    }

    private fun direct(fixture: Fixture, clock: Clock): DialogueRenderer.PresentationHandle {
        val pending =
            fixture.renderer.prepare(
                DialogueRenderer.PresentationRequest(
                    RenderFixtures.SESSION,
                    fixture.player.uuid.toString(),
                    1,
                    PresentationPreferences("chat", Duration.ZERO, false, emptyMap()),
                    CancellationToken(),
                    Duration.ofSeconds(5).toNanos(),
                    budgets(),
                )
            )
        clock.drain()
        return pending.toCompletableFuture().join()
    }

    @Test
    fun rejectedWritePreservesQueuedWorkAndItsOriginalDeadline(env: Env) {
        val clock = Clock()
        val fixture = Fixture(env, clock)
        val handle = direct(fixture, clock)
        handle.replace(RenderFixtures.view("accepted", 8))
        clock.drain()
        val bad = CompletableFuture<Void>()
        val good = CompletableFuture<Void>()
        fixture.chat.draw = { if (it.semantic().text() == "bad") bad else good }
        val rejected = handle.replace(RenderFixtures.view("bad", 3))
        clock.drain()
        val queued = handle.replace(RenderFixtures.view("good", 4))
        clock.drain()
        clock.advance(Duration.ofSeconds(4))
        bad.completeExceptionally(
            java.util.concurrent.CompletionException(
                java.util.concurrent.ExecutionException(DialogueSurface.FrameRejected("owner"))
            )
        )
        clock.drain()
        assertTrue(rejected.toCompletableFuture().isCompletedExceptionally)
        assertFalse(queued.toCompletableFuture().isDone)
        assertEquals("good", fixture.chat.frames.last().semantic().text())
        clock.advance(Duration.ofSeconds(1))
        assertTrue(
            queued.toCompletableFuture().isCompletedExceptionally,
            "Rejection cannot extend already pending work",
        )
        assertEquals("", fixture.renderer.effectiveSurface())
        good.complete(null)
        clock.drain()
        handle.closeAsync()
        fixture.core.closeAsync()
        clock.drain()
    }

    @Test
    fun intermediatePhysicalAckSurvivesLaterRejectionAndExpiredOldTimer(env: Env) {
        val clock = Clock()
        val fixture = Fixture(env, clock)
        val handle = direct(fixture, clock)
        val first = CompletableFuture<Void>()
        val bad = CompletableFuture<Void>()
        fixture.chat.draw = { if (it.semantic().text() == "first") first else bad }
        val accepted = handle.replace(RenderFixtures.view("first", 5))
        clock.drain()
        val rejected = handle.replace(RenderFixtures.view("bad", 3))
        clock.drain()
        first.complete(null)
        clock.drain()
        assertFalse(
            accepted.toCompletableFuture().isDone,
            "Retain existing coalesced observer timing",
        )
        bad.completeExceptionally(DialogueSurface.FrameRejected("owner"))
        clock.drain()
        assertTrue(rejected.toCompletableFuture().isCompletedExceptionally)
        accepted.toCompletableFuture().join()
        assertEquals("chat", fixture.renderer.effectiveSurface())
        clock.advance(Duration.ofSeconds(6))
        assertEquals(
            "chat",
            fixture.renderer.effectiveSurface(),
            "Retired batch timer cannot tear down accepted output",
        )
        fixture.chat.draw = { CompletableFuture.completedFuture(null) }
        fixture.renderer.refreshOutput()
        clock.drain()
        assertEquals("first", fixture.chat.frames.last().semantic().text())
        handle.closeAsync()
        fixture.core.closeAsync()
        clock.drain()
    }

    @Test
    fun firstFrameRetryKeepsOriginalRequestDeadline(env: Env) {
        val clock = Clock()
        val fixture = Fixture(env, clock)
        val handle = direct(fixture, clock)
        val first = CompletableFuture<Void>()
        val retry = CompletableFuture<Void>()
        fixture.chat.draw = { if (it.semantic().text() == "bad") first else retry }
        val rejected = handle.replace(RenderFixtures.view("bad", 3))
        clock.drain()
        clock.advance(Duration.ofSeconds(4))
        first.completeExceptionally(DialogueSurface.FrameRejected("owner"))
        clock.drain()
        assertTrue(rejected.toCompletableFuture().isCompletedExceptionally)
        val pending = handle.replace(RenderFixtures.view("retry", 5))
        clock.drain()
        assertFalse(pending.toCompletableFuture().isDone)
        clock.advance(Duration.ofSeconds(1))
        assertTrue(pending.toCompletableFuture().isCompletedExceptionally)
        retry.complete(null)
        clock.drain()
        handle.closeAsync()
        fixture.core.closeAsync()
        clock.drain()
    }

    @Test
    fun ordinaryFailureWrappingMarkerRemainsFatal(env: Env) {
        val clock = Clock()
        val fixture = Fixture(env, clock)
        val handle = direct(fixture, clock)
        handle.replace(RenderFixtures.view("accepted", 8))
        clock.drain()
        fixture.chat.drawError =
            IllegalStateException("partial write", DialogueSurface.FrameRejected("nested"))
        val failed = handle.replace(RenderFixtures.view("bad", 3))
        clock.drain()
        assertTrue(failed.toCompletableFuture().isCompletedExceptionally)
        assertEquals(1, fixture.chat.closes)
        assertEquals("", fixture.renderer.effectiveSurface())
        val retry = handle.replace(RenderFixtures.view("retry", 5))
        clock.drain()
        assertTrue(retry.toCompletableFuture().isCompletedExceptionally)
        fixture.core.closeAsync()
        clock.drain()
    }

    @Test
    fun nativeConstructorsRequireExactScopeRegistryAndIssuer(env: Env) {
        val fixture = Fixture(env)
        val scope = fixture.scope
        val sameIdentity = MinestomConnectionScope(scope.player, scope.generation, scope.instance)
        val targets = fixture.targets
        val tokens =
            gg.grounds.dialogue.minestom.input.ChatInputTokens(scope, targets, System::nanoTime)
        val secondIssuer =
            gg.grounds.dialogue.minestom.input.ChatInputTokens(scope, targets, System::nanoTime)
        fun chat(
            owner: MinestomConnectionScope,
            registry: SurfaceInputTargets,
            issuer: gg.grounds.dialogue.minestom.input.ChatInputTokens,
        ) =
            ChatDialogueSurface(
                owner,
                registry,
                issuer,
                { CompletableFuture.completedFuture(null) },
                UiLabelSource.defaults(),
                DialoguePresenter.Controls.defaults(),
                "/native",
            )
        assertThrows(IllegalArgumentException::class.java) { chat(sameIdentity, targets, tokens) }
        assertThrows(IllegalArgumentException::class.java) {
            chat(scope, SurfaceInputTargets(), tokens)
        }
        val surface = chat(scope, targets, tokens)
        fun renderer(
            owner: MinestomConnectionScope,
            registry: SurfaceInputTargets,
            issuer: gg.grounds.dialogue.minestom.input.ChatInputTokens?,
        ) =
            MinestomDialogueRenderer(
                owner,
                mapOf("chat" to DialoguePresenter.SurfaceProfile.semantic(surface)),
                fixture.clock,
                DialoguePresenter.Controls.defaults(),
                UiLabelSource.defaults(),
                registry,
                {},
                issuer,
                { null },
            )
        assertThrows(IllegalArgumentException::class.java) {
            renderer(sameIdentity, targets, tokens)
        }
        assertThrows(IllegalArgumentException::class.java) {
            renderer(scope, SurfaceInputTargets(), tokens)
        }
        assertThrows(IllegalArgumentException::class.java) {
            renderer(scope, targets, secondIssuer)
        }
        val assets = RenderFixtures.ASSETS
        val packs = gg.grounds.dialogue.minestom.asset.PackStateTracker()
        val channels =
            gg.grounds.dialogue.minestom.channel.LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                false,
                { _, _ -> CompletableFuture.completedFuture(null) },
            )
        val known =
            listOf(
                HudDialogueSurface(scope, packs, assets, channels, targets, fixture.clock, {}),
                NpcDialogueSurface(
                    scope,
                    packs,
                    assets,
                    channels,
                    targets,
                    fixture.clock,
                    { null },
                    {},
                ),
            )
        known.forEach { native ->
            val profiles =
                mapOf(
                    "chat" to DialoguePresenter.SurfaceProfile.semantic(fixture.chat),
                    "custom-key" to DialoguePresenter.SurfaceProfile.semantic(native),
                )
            assertThrows(IllegalArgumentException::class.java) {
                MinestomDialogueRenderer(
                    sameIdentity,
                    profiles,
                    fixture.clock,
                    DialoguePresenter.Controls.defaults(),
                    UiLabelSource.defaults(),
                    targets,
                    {},
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                MinestomDialogueRenderer(
                    scope,
                    profiles,
                    fixture.clock,
                    DialoguePresenter.Controls.defaults(),
                    UiLabelSource.defaults(),
                    SurfaceInputTargets(),
                    {},
                )
            }
        }
        val hidden =
            mapOf(
                "chat" to DialoguePresenter.SurfaceProfile.semantic(fixture.chat),
                "custom-key" to DialoguePresenter.SurfaceProfile.semantic(surface),
            )
        assertThrows(IllegalArgumentException::class.java) {
            MinestomDialogueRenderer(
                sameIdentity,
                hidden,
                fixture.clock,
                DialoguePresenter.Controls.defaults(),
                UiLabelSource.defaults(),
                targets,
                {},
            )
        }
        done(env, fixture.core.closeAsync())
    }

    @Test
    fun copiedProfilesAndNullIssuerPreserveOutputAndExternalResolution(env: Env) {
        for (full in listOf(false, true)) {
            val fixture = Fixture(env)
            val scope = fixture.scope
            val tokens =
                gg.grounds.dialogue.minestom.input.ChatInputTokens(
                    scope,
                    fixture.targets,
                    System::nanoTime,
                )
            var sends = 0
            val surface =
                ChatDialogueSurface(
                    scope,
                    fixture.targets,
                    tokens,
                    {
                        sends++
                        CompletableFuture.completedFuture(null)
                    },
                    UiLabelSource.defaults(),
                    DialoguePresenter.Controls.defaults(),
                    "/native",
                )
            val profiles =
                mutableMapOf("chat" to DialoguePresenter.SurfaceProfile.semantic(surface))
            val renderer =
                if (full)
                    MinestomDialogueRenderer(
                        scope,
                        profiles,
                        fixture.clock,
                        DialoguePresenter.Controls.defaults(),
                        UiLabelSource.defaults(),
                        fixture.targets,
                        {},
                        null,
                        { null },
                    )
                else
                    MinestomDialogueRenderer(
                        scope,
                        profiles,
                        fixture.clock,
                        DialoguePresenter.Controls.defaults(),
                        UiLabelSource.defaults(),
                        fixture.targets,
                        {},
                    )
            profiles["chat"] = DialoguePresenter.SurfaceProfile.semantic(fixture.chat)
            val handle = done(env, renderer.prepare(request(fixture)))
            done(env, handle.replace(RenderFixtures.view("accepted", 8)))
            assertEquals(1, sends)
            assertEquals(0, fixture.chat.writes)
            val snapshot = fixture.targets.current()!!
            val token = tokens.issue("ask", snapshot)
            assertTrue(renderer.resolveChat(scope.player, 1, token).isEmpty)
            assertTrue(tokens.resolve(scope.player, 1, token).isPresent)
            done(env, handle.closeAsync())
            assertTrue(tokens.resolve(scope.player, 1, token).isEmpty)
            done(env, fixture.core.closeAsync())
        }
    }

    @Test
    fun wrongPrepareAndForeignFramePreserveActivePresentation(env: Env) {
        val fixture = Fixture(env)
        val handle = done(env, fixture.renderer.prepare(request(fixture)))
        done(env, handle.replace(RenderFixtures.view("accepted", 8)))
        val writes = fixture.chat.writes
        val wrongAudience =
            DialogueRenderer.PresentationRequest(
                RenderFixtures.SESSION,
                "foreign",
                1,
                PresentationPreferences("chat", Duration.ZERO, false, emptyMap()),
                CancellationToken(),
                Long.MAX_VALUE,
                budgets(),
            )
        assertTrue(
            fixture.renderer.prepare(wrongAudience).toCompletableFuture().isCompletedExceptionally
        )
        val wrongGeneration =
            DialogueRenderer.PresentationRequest(
                RenderFixtures.SESSION,
                fixture.player.uuid.toString(),
                2,
                wrongAudience.preferences,
                CancellationToken(),
                Long.MAX_VALUE,
                budgets(),
            )
        assertTrue(
            fixture.renderer.prepare(wrongGeneration).toCompletableFuture().isCompletedExceptionally
        )
        assertTrue(
            handle
                .replace(RenderFixtures.view("foreign", 7, java.util.UUID.randomUUID()))
                .toCompletableFuture()
                .isCompletedExceptionally
        )
        assertEquals(writes, fixture.chat.writes)
        done(env, handle.replace(RenderFixtures.view("accepted again", 14)))
        assertEquals(writes + 1, fixture.chat.writes)
        done(env, handle.closeAsync())
        done(env, fixture.core.closeAsync())
    }

    @Test
    fun sameSemanticViewStillWaitsForTheNewPresentationWrite(env: Env) {
        val fixture = Fixture(env)
        val handle =
            done(
                env,
                fixture.renderer.prepare(
                    DialogueRenderer.PresentationRequest(
                        RenderFixtures.SESSION,
                        fixture.player.uuid.toString(),
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
                        fixture.player.uuid.toString(),
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
