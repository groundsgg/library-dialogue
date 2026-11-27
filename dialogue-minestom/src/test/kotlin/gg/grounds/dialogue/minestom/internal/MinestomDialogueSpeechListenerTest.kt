package gg.grounds.dialogue.minestom.internal

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.DialogueServices
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.MinestomDialogueHostTest
import gg.grounds.dialogue.minestom.MinestomDialogueRequest
import gg.grounds.dialogue.minestom.MinestomDialogueSpeechEvent
import gg.grounds.dialogue.minestom.MinestomDialogueSpeechListener
import gg.grounds.dialogue.minestom.input.DialogueInputSource
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class MinestomDialogueSpeechListenerTest {
    private companion object {
        fun speechContent(
            translations: Map<String, String>,
            twoMessages: Boolean,
        ): ValidatedDialogue {
            val textRows = translations.mapValues { (_, line) ->
                if (twoMessages) mapOf("line" to line, "line2" to "Second line")
                else mapOf("line" to line)
            }
            val definition =
                DialogueBuilder.dialogue("test:speech")
                    .locales("en", *translations.keys.toTypedArray())
                    .node(
                        "start",
                        Message(
                            "npc",
                            "line",
                            null,
                            emptyList(),
                            if (twoMessages) "next" else "end",
                        ),
                    )
                    .let { builder ->
                        if (twoMessages) {
                            builder.node("next", Message("npc", "line2", null, emptyList(), "end"))
                        } else builder
                    }
                    .node("end", End("completed"))
                    .build()
            val report =
                DialogueValidator()
                    .validate(
                        definition,
                        TextCatalog(textRows),
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
            return checkNotNull(report.validated()) { report.toString() }
        }
    }

    private open class RecordingListener : MinestomDialogueSpeechListener {
        val begun =
            CopyOnWriteArrayList<gg.grounds.dialogue.minestom.MinestomDialogueSpeechBeginEvent>()
        val revealed = CopyOnWriteArrayList<MinestomDialogueSpeechEvent>()
        val stopped = CopyOnWriteArrayList<MinestomDialogueSpeechListener.StopReason>()
        var failOnReveal = false
        var failOnStop: MinestomDialogueSpeechListener.StopReason? = null

        override fun onRevealed(event: MinestomDialogueSpeechEvent) {
            revealed.add(event)
            if (failOnReveal) error("listener failure")
        }

        override fun onStop(reason: MinestomDialogueSpeechListener.StopReason) {
            stopped.add(reason)
            if (reason == failOnStop) error("stop listener failure")
        }
    }

    private class TimedRecordingListener :
        RecordingListener(), gg.grounds.dialogue.minestom.MinestomDialogueTimedSpeechListener {
        override fun onBegin(event: gg.grounds.dialogue.minestom.MinestomDialogueSpeechBeginEvent) {
            begun.add(event)
        }
    }

    private class Renderer(var effective: String = "npc") : DialogueRenderer, DialogueInputSource {
        var gateOnReveal: Int? = null
        var gateAllPositive = false
        val gate = CompletableFuture<Void>()
        var failOnReveal = false

        override fun effectiveSurface(): String = effective

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
                        when {
                            failOnReveal && view.revealedGraphemes() > 0 ->
                                CompletableFuture.failedFuture(
                                    IllegalStateException("presentation failed")
                                )
                            gateAllPositive &&
                                view.revealedGraphemes() > 0 &&
                                view.state() == DialogueView.State.REVEALING -> gate
                            gateOnReveal == view.revealedGraphemes() -> gate
                            else -> CompletableFuture.completedFuture(null)
                        }

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
        val listener: RecordingListener = RecordingListener(),
        val renderer: Renderer = Renderer(),
        translations: Map<String, String>? = null,
        twoMessages: Boolean = false,
    ) {
        val base = MinestomDialogueHostTest.Fixture(env)
        val player = env.createConnection().connect(base.player.instance, Pos(0.0, 42.0, 0.0))
        val ready = CompletableFuture<DialogueStartResult>()
        val token = CancellationToken()
        val request: MinestomDialogueRequest
        val session: MinestomDialogueSession

        init {
            MinestomDialogueHostTest.done(env, base.host.closeAsync())
            val original =
                base.request(
                    "speech",
                    "v1",
                    PresentationPreferences("npc", Duration.ofMillis(90), false, emptyMap()),
                )
            val services =
                if (translations == null) original.services
                else {
                    val source = original.services
                    DialogueServices(
                        source.generation(),
                        source.conditions(),
                        source.actions(),
                        source.outcomes(),
                        TextResolver.catalogue(
                            TextCatalog(
                                translations.mapValues { (_, line) ->
                                    if (twoMessages) {
                                        mapOf("line" to line, "line2" to "Second line")
                                    } else mapOf("line" to line)
                                }
                            ),
                            "en",
                        ),
                        source.capabilities(),
                    )
                }
            request =
                MinestomDialogueRequest(
                    original.requestId,
                    player,
                    original.hostGeneration,
                    if (translations == null) original.content
                    else speechContent(translations, twoMessages),
                    services,
                    original.context,
                    original.locale,
                    original.preferences,
                    original.speakers,
                    original.actors,
                    original.controls,
                    original.openingImpulse,
                    listener,
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
                    { _, _ -> },
                )
            session.begin()
            MinestomDialogueHostTest.done(env, ready)
        }

        fun waitForRevealed(count: Int) {
            assertTrue(
                env.tickWhile(
                    { (session.snapshot()?.revealedGraphemes() ?: 0) < count },
                    Duration.ofSeconds(2),
                )
            )
            env.tick()
        }

        fun close() = session.cancel(DialogueEndReason.CANCELLED)

        fun coreHandle(): DialogueHandle {
            val field = MinestomDialogueSession::class.java.getDeclaredField("handle")
            field.isAccessible = true
            return checkNotNull(field.get(session) as DialogueHandle?)
        }
    }

    @Test
    fun naturalPresentedDeltasStopOnPauseAndTerminalCloseIsStillReported(env: Env) {
        val fixture = Fixture(env, TimedRecordingListener())
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.state() != DialogueView.State.WAITING_FOR_INPUT },
                Duration.ofSeconds(2),
            )
        )
        env.tick()

        assertEquals(1, fixture.listener.begun.size)
        val beginning = fixture.listener.begun.single()
        assertTrue(beginning.view().revealedGraphemes() > 0)
        assertEquals(beginning.view().sessionId(), beginning.timing().sessionId())
        assertEquals(
            beginning.view().totalGraphemes() * 90_000_000L,
            beginning.timing().endNanos() - beginning.timing().enteredNanos(),
        )
        assertEquals("Hell", fixture.listener.revealed.joinToString("") { it.revealed() })
        assertTrue(
            fixture.listener.revealed.all { it.view().state() == DialogueView.State.REVEALING }
        )
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.PAUSED,
            fixture.listener.stopped.last(),
        )
        val event = fixture.listener.revealed.first()
        assertEquals("start", event.view().nodeId())
        assertEquals("npc", event.speakerRole())
        assertSame(fixture.request.speakers["npc"], event.speaker())

        val view = fixture.session.snapshot()!!
        assertEquals(
            InputResult.ACCEPTED,
            MinestomDialogueHostTest.done(
                env,
                fixture.session.submit(
                    DialogueInput.Confirm(
                        DialogueInput.InputStamp(
                            view.sessionId(),
                            view.renderRevision(),
                            fixture.session.nextImpulse(),
                        ),
                        null,
                    )
                ),
            ),
        )
        env.tick()
        assertEquals("Hell", fixture.listener.revealed.joinToString("") { it.revealed() })
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.SESSION_ENDED,
            fixture.listener.stopped.last(),
        )

        fixture.close()
        env.tick()
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.SESSION_ENDED,
            fixture.listener.stopped.last(),
        )
    }

    @Test
    fun delayedCompletedPresentationDoesNotBeginSpeech(env: Env) {
        val renderer = Renderer().also { it.gateAllPositive = true }
        val fixture = Fixture(env, TimedRecordingListener(), renderer)
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.state() != DialogueView.State.WAITING_FOR_INPUT },
                Duration.ofSeconds(2),
            )
        )
        renderer.gate.complete(null)
        env.tick()
        assertTrue(fixture.listener.begun.isEmpty())
        fixture.close()
        env.tick()
    }

    @Test
    fun acceptedLocalePreventsDelayedOldBegin(env: Env) {
        val renderer = Renderer().also { it.gateAllPositive = true }
        val fixture = Fixture(env, TimedRecordingListener(), renderer)
        fixture.waitForRevealed(1)
        MinestomDialogueHostTest.done(
            env,
            fixture.coreHandle().refreshLocale(LocaleContext("de", 1, "en")),
        )
        renderer.gate.complete(null)
        env.tick()
        assertTrue(fixture.listener.begun.isEmpty())
        fixture.close()
        env.tick()
    }

    @Test
    fun unicodeGraphemesAndPunctuationArePreservedInNaturalDeltas(env: Env) {
        val fixture = Fixture(env, translations = mapOf("en" to "A👩‍💻, B."))
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.state() != DialogueView.State.WAITING_FOR_INPUT },
                Duration.ofSeconds(2),
            )
        )
        env.tick()

        val deltas = fixture.listener.revealed.map { it.revealed() }
        assertEquals("A👩‍💻, B", deltas.joinToString(""))
        assertTrue(deltas.contains("👩‍💻"))
        assertTrue(deltas.contains(","))
        assertTrue(deltas.contains(" "))
        fixture.close()
        env.tick()
    }

    @Test
    fun localeReplacementStopsOldSpeechAndBaselinesNewText(env: Env) {
        val fixture =
            Fixture(
                env,
                translations = mapOf("en" to "Hello there, traveller", "de" to "Hallo Reisender"),
            )
        fixture.waitForRevealed(1)
        assertTrue(fixture.listener.stopped.isEmpty())
        val previousEventCount = fixture.listener.revealed.size
        assertEquals(
            RefreshResult.UPDATED,
            MinestomDialogueHostTest.done(
                env,
                fixture.coreHandle().refreshLocale(LocaleContext("de", 1, "en")),
            ),
        )
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.localeTag() != "de" },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        assertTrue(fixture.listener.stopped.isNotEmpty(), fixture.listener.stopped.toString())
        assertEquals(previousEventCount, fixture.listener.revealed.size)
        assertEquals(DialogueView.State.WAITING_FOR_INPUT, fixture.session.snapshot()?.state())
        fixture.close()
        env.tick()
    }

    @Test
    fun nodeReplacementStartsWithNewLineProgress(env: Env) {
        val fixture =
            Fixture(
                env,
                translations = mapOf("en" to "First line"),
                twoMessages = true,
            )
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.state() != DialogueView.State.WAITING_FOR_INPUT },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        val previousCount = fixture.listener.revealed.size
        val view = fixture.session.snapshot()!!
        assertEquals(
            InputResult.ACCEPTED,
            MinestomDialogueHostTest.done(
                env,
                fixture.session.submit(
                    DialogueInput.Confirm(
                        DialogueInput.InputStamp(
                            view.sessionId(),
                            view.renderRevision(),
                            fixture.session.nextImpulse(),
                        ),
                        null,
                    )
                ),
            ),
        )
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.nodeId() != "next" },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        val nextNodeStart = fixture.listener.revealed.size
        assertTrue(nextNodeStart >= previousCount)
        assertTrue(
            env.tickWhile(
                { fixture.listener.revealed.size <= nextNodeStart },
                Duration.ofSeconds(2),
            )
        )
        val nextLine = fixture.listener.revealed.drop(nextNodeStart)
        assertTrue(nextLine.all { it.view().nodeId() == "next" })
        assertTrue(nextLine.joinToString("") { it.revealed() }.startsWith("S"))
        fixture.close()
        env.tick()
    }

    @Test
    fun fallbackToChatStopsNpcSpeech(env: Env) {
        val renderer = Renderer()
        val fixture =
            Fixture(
                env,
                renderer = renderer,
                translations = mapOf("en" to "A longer line to keep speech active"),
            )
        fixture.waitForRevealed(1)
        assertTrue(fixture.listener.revealed.isNotEmpty())
        assertEquals(DialogueView.State.REVEALING, fixture.session.snapshot()?.state())
        renderer.effective = "chat"

        assertTrue(
            env.tickWhile(
                {
                    !fixture.listener.stopped.contains(
                        MinestomDialogueSpeechListener.StopReason.PRESENTATION_FALLBACK
                    )
                },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        val stoppedAt = fixture.listener.revealed.size
        env.tickWhile(
            { fixture.session.snapshot()?.state() != DialogueView.State.WAITING_FOR_INPUT },
            Duration.ofSeconds(2),
        )
        env.tick()
        assertEquals(stoppedAt, fixture.listener.revealed.size)
        fixture.close()
        env.tick()
    }

    @Test
    fun reconfigurationStopsActiveSpeechBeforeOutputChanges(env: Env) {
        val fixture = Fixture(env)
        fixture.waitForRevealed(1)
        assertTrue(fixture.listener.stopped.isEmpty())

        MinestomDialogueHostTest.done(
            env,
            fixture
                .coreHandle()
                .updatePreferences(
                    PresentationPreferences("npc", Duration.ofMillis(120), false, emptyMap())
                ),
        )
        env.tick()

        assertTrue(
            fixture.listener.stopped.contains(
                MinestomDialogueSpeechListener.StopReason.CONTENT_REPLACED
            )
        )
        fixture.close()
        env.tick()
    }

    @Test
    fun manualSkipDoesNotEmitTheSkippedTail(env: Env) {
        val fixture = Fixture(env)
        assertTrue(
            env.tickWhile(
                { (fixture.session.snapshot()?.revealedGraphemes() ?: 0) < 4 },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        val eventCount = fixture.listener.revealed.size
        val view = fixture.session.snapshot()!!
        assertEquals(
            InputResult.REVEAL_ONLY,
            MinestomDialogueHostTest.done(
                env,
                fixture.session.submit(
                    DialogueInput.Confirm(
                        DialogueInput.InputStamp(
                            view.sessionId(),
                            view.renderRevision(),
                            fixture.session.nextImpulse(),
                        ),
                        null,
                    )
                ),
            ),
        )
        env.tick()
        assertEquals(eventCount, fixture.listener.revealed.size)
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.PAUSED,
            fixture.listener.stopped.last(),
        )
        fixture.close()
        env.tick()
    }

    @Test
    fun activeSpeechStopsWhenAReplacementPresentationFails(env: Env) {
        val renderer = Renderer().also { it.gateOnReveal = 2 }
        val fixture = Fixture(env, renderer = renderer)
        fixture.waitForRevealed(1)
        assertTrue(fixture.listener.revealed.isNotEmpty())
        assertEquals(DialogueView.State.REVEALING, fixture.session.snapshot()?.state())
        val coreHandle = fixture.coreHandle()

        renderer.gate.completeExceptionally(IllegalStateException("presentation failed"))
        env.tickWhile(
            {
                fixture.listener.stopped.none {
                    it == MinestomDialogueSpeechListener.StopReason.PRESENTATION_FAILED ||
                        it == MinestomDialogueSpeechListener.StopReason.SESSION_FAILED
                }
            },
            Duration.ofSeconds(2),
        )
        env.tick()
        val end = MinestomDialogueHostTest.done(env, coreHandle.completion())
        assertEquals(DialogueEndReason.FAILED, end.reason())
        assertTrue(
            fixture.listener.stopped.any {
                it == MinestomDialogueSpeechListener.StopReason.PRESENTATION_FAILED ||
                    it == MinestomDialogueSpeechListener.StopReason.SESSION_FAILED
            },
            fixture.listener.stopped.toString(),
        )
    }

    @Test
    fun closingWhileSpeakingDeliversTerminalStop(env: Env) {
        val fixture = Fixture(env)
        fixture.waitForRevealed(1)
        assertTrue(fixture.listener.revealed.isNotEmpty())

        fixture.close()
        env.tick()

        assertEquals(
            MinestomDialogueSpeechListener.StopReason.SESSION_ENDED,
            fixture.listener.stopped.last(),
        )
    }

    @Test
    fun speechCallbacksRemainScopedToEachPlayer(env: Env) {
        val first = Fixture(env)
        val second = Fixture(env)
        first.waitForRevealed(1)
        second.waitForRevealed(1)
        val secondCount = second.listener.revealed.size
        first.session.cancel(DialogueEndReason.DISCONNECTED)
        env.tick()

        assertTrue(
            second.listener.stopped.none {
                it == MinestomDialogueSpeechListener.StopReason.DISCONNECTED ||
                    it == MinestomDialogueSpeechListener.StopReason.SESSION_ENDED
            }
        )
        assertTrue(
            env.tickWhile(
                { second.listener.revealed.size <= secondCount },
                Duration.ofSeconds(2),
            )
        )
        assertTrue(second.listener.revealed.size > secondCount)
        second.close()
        env.tick()
    }

    @Test
    fun stalePresentedFrameCannotRestartSpeechAfterDisconnect(env: Env) {
        val renderer = Renderer().also { it.gateOnReveal = 1 }
        val fixture = Fixture(env, renderer = renderer)
        fixture.waitForRevealed(1)
        assertTrue(fixture.listener.revealed.isEmpty())

        fixture.session.cancel(DialogueEndReason.DISCONNECTED)
        renderer.gate.complete(null)
        env.tick()

        assertTrue(fixture.listener.revealed.isEmpty())
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.DISCONNECTED,
            fixture.listener.stopped.last(),
        )
    }

    @Test
    fun failedStalePresentationCannotStopNewerSpeech(env: Env) {
        val renderer = Renderer().also { it.gateOnReveal = 1 }
        val fixture = Fixture(env, renderer = renderer)
        assertTrue(
            env.tickWhile(
                { fixture.listener.revealed.isEmpty() },
                Duration.ofSeconds(2),
            )
        )
        assertTrue(fixture.listener.revealed.isNotEmpty())

        renderer.gate.completeExceptionally(IllegalStateException("stale presentation failed"))
        env.tick()

        assertFalse(
            fixture.listener.stopped.contains(
                MinestomDialogueSpeechListener.StopReason.PRESENTATION_FAILED
            )
        )
        assertFalse(fixture.session.snapshot()?.state() == DialogueView.State.CLOSED)
        fixture.close()
        env.tick()
    }

    @Test
    fun listenerFailureDisablesOnlySpeechHooks(env: Env) {
        val listener = RecordingListener().also { it.failOnReveal = true }
        val fixture = Fixture(env, listener = listener)
        fixture.waitForRevealed(1)

        assertEquals(
            MinestomDialogueSpeechListener.StopReason.LISTENER_FAILED,
            listener.stopped.last(),
        )
        assertNotNull(fixture.session.snapshot())

        fixture.close()
        env.tick()
        assertEquals(
            MinestomDialogueSpeechListener.StopReason.SESSION_ENDED,
            listener.stopped.last(),
        )
    }

    @Test
    fun throwingPauseCallbackDoesNotSuppressDisconnectCleanup(env: Env) {
        val listener =
            RecordingListener().also {
                it.failOnStop = MinestomDialogueSpeechListener.StopReason.PAUSED
            }
        val fixture = Fixture(env, listener = listener)
        assertTrue(
            env.tickWhile(
                { fixture.session.snapshot()?.state() != DialogueView.State.WAITING_FOR_INPUT },
                Duration.ofSeconds(2),
            )
        )
        env.tick()
        assertTrue(listener.stopped.contains(MinestomDialogueSpeechListener.StopReason.PAUSED))
        assertTrue(
            listener.stopped.contains(MinestomDialogueSpeechListener.StopReason.LISTENER_FAILED)
        )

        fixture.session.cancel(DialogueEndReason.DISCONNECTED)
        env.tick()

        assertEquals(
            MinestomDialogueSpeechListener.StopReason.DISCONNECTED,
            listener.stopped.last(),
        )
    }
}
