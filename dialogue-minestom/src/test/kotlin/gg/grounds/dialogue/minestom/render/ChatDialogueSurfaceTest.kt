package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.input.ChatInputTokens
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.UiLabelSource
import gg.grounds.dialogue.presentation.WidgetFrame
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.coordinate.Pos
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class ChatDialogueSurfaceTest {
    @Test
    fun rebaseRetainsExpiryAndResolutionHasItsOwnLifetimePredicate(env: Env) {
        val instance = env.createFlatInstance()
        val player = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        val scope = MinestomConnectionScope(player, 1, instance)
        val targets = SurfaceInputTargets()
        val now = AtomicLong()
        val tokens = ChatInputTokens(scope, targets, now::get)
        val old = RenderFixtures.view("same section", 1)
        val frame = WidgetFrame(old, emptyList(), "semantic", "semantic")
        val choices = mapOf("ask" to WidgetFrame.Rect(0.0, 0.0, 0.0, 0.0))
        targets.publish(frame, 9, choices)
        val token = tokens.issue("ask", targets.current()!!)
        scope.invalidate()
        player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        assertFalse(scope.hasCurrentConnection())
        assertTrue(tokens.resolve(player, 1, token).isPresent)
        val next =
            DialogueView(
                old.sessionId(),
                old.contentPin(),
                old.nodeId(),
                old.nodeVisitId(),
                old.state(),
                old.stateRevision(),
                2,
                old.localeTag(),
                old.speaker(),
                old.text(),
                old.choices(),
                old.selectedChoiceId(),
                old.totalGraphemes(),
                2,
                old.hint(),
                old.notice(),
                old.pendingAction(),
            )
        targets.publish(WidgetFrame(next, emptyList(), "semantic", "semantic"), 9, choices)
        now.set(30_000_000_000L)
        tokens.rebase(targets.current()!!)
        assertEquals(2L, tokens.resolve(player, 1, token).orElseThrow().renderRevision)
        now.incrementAndGet()
        assertTrue(tokens.resolve(player, 1, token).isEmpty)
        targets.clear(9)
        targets.publish(frame, 9, choices)
        assertNull(targets.current(), "Late output cannot resurrect a retired generation")
    }

    @Test
    fun rejectsForeignContextBeforeOutput(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val targets = SurfaceInputTargets()
        val scope = MinestomConnectionScope(player, 1, player.instance)
        val tokens = ChatInputTokens(scope, targets, System::nanoTime)
        var writes = 0
        val surface =
            ChatDialogueSurface(
                scope,
                targets,
                tokens,
                {
                    writes++
                    CompletableFuture.completedFuture(null)
                },
                UiLabelSource.defaults(),
                DialoguePresenter.Controls.defaults(),
                "/testinput",
            )
        for ((audience, generation) in listOf("foreign" to 1L, player.uuid.toString() to 2L)) {
            val rejected =
                surface.open(
                    DialogueSurface.SurfaceContext(
                        RenderFixtures.SESSION,
                        audience,
                        generation,
                        "chat",
                        "semantic",
                        CancellationToken(),
                        19,
                        Long.MAX_VALUE,
                    )
                )
            assertTrue(rejected.toCompletableFuture().isCompletedExceptionally)
        }
        assertEquals(0, writes)
        assertNull(targets.current())
    }

    @Test
    fun foreignFramePreservesAcceptedTargetsAndTransport(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val targets = SurfaceInputTargets()
        val scope = MinestomConnectionScope(player, 1, player.instance)
        val tokens = ChatInputTokens(scope, targets, System::nanoTime)
        var writes = 0
        val surface =
            ChatDialogueSurface(
                scope,
                targets,
                tokens,
                {
                    writes++
                    CompletableFuture.completedFuture(null)
                },
                UiLabelSource.defaults(),
                DialoguePresenter.Controls.defaults(),
                "/testinput",
            )
        val context =
            DialogueSurface.SurfaceContext(
                RenderFixtures.SESSION,
                player.uuid.toString(),
                1,
                "chat",
                "semantic",
                CancellationToken(),
                19,
                Long.MAX_VALUE,
            )
        val handle = surface.open(context).toCompletableFuture().join()
        val valid = RenderFixtures.view("valid", 5)
        handle
            .replace(WidgetFrame(valid, emptyList(), "semantic", "semantic"))
            .toCompletableFuture()
            .join()
        val accepted = targets.current()
        val capability = tokens.issue("ask", accepted!!)
        val foreign =
            DialogueView(
                java.util.UUID.randomUUID(),
                valid.contentPin(),
                valid.nodeId(),
                valid.nodeVisitId(),
                valid.state(),
                valid.stateRevision(),
                valid.renderRevision(),
                valid.localeTag(),
                valid.speaker(),
                "foreign",
                valid.choices(),
                valid.selectedChoiceId(),
                valid.totalGraphemes(),
                valid.revealedGraphemes(),
                valid.hint(),
                valid.notice(),
                valid.pendingAction(),
            )
        val rejected = handle.replace(WidgetFrame(foreign, emptyList(), "semantic", "semantic"))
        assertTrue(rejected.toCompletableFuture().isCompletedExceptionally)
        assertEquals(1, writes)
        org.junit.jupiter.api.Assertions.assertSame(accepted, targets.current())
        assertTrue(tokens.resolve(player, 1, capability).isPresent)
        handle.closeAsync().toCompletableFuture().join()
        assertFalse(tokens.resolve(player, 1, capability).isPresent)
    }

    @Test
    fun chatRendersOncePerSemanticSection(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val messages = ArrayList<Component>()
        val targets = SurfaceInputTargets()
        val scope = MinestomConnectionScope(player, 1, player.instance)
        val tokens = ChatInputTokens(scope, targets, System::nanoTime)
        val surface =
            ChatDialogueSurface(
                scope,
                targets,
                tokens,
                {
                    messages.add(it)
                    CompletableFuture.completedFuture(null)
                },
                UiLabelSource.defaults(),
                DialoguePresenter.Controls.defaults(),
                "/testinput",
            )
        val handle =
            surface
                .open(
                    DialogueSurface.SurfaceContext(
                        RenderFixtures.SESSION,
                        player.uuid.toString(),
                        1,
                        "chat",
                        "semantic",
                        CancellationToken(),
                        1,
                        Long.MAX_VALUE,
                    )
                )
                .toCompletableFuture()
                .join()
        repeat(10) { revealed ->
            val view = RenderFixtures.view("Hello there", revealed)
            handle
                .replace(WidgetFrame(view, emptyList(), "semantic", "semantic"))
                .toCompletableFuture()
                .join()
        }
        assertEquals(1, messages.size)
        assertTrue(
            PlainTextComponentSerializer.plainText()
                .serialize(messages.first())
                .contains("Hello there")
        )
        val plainMessages = messages.map { PlainTextComponentSerializer.plainText().serialize(it) }
        assertTrue(plainMessages.none { text -> text.any { it in '\ue790'..'\ue793' } })
        handle.closeAsync().toCompletableFuture().join()
        assertNull(targets.current())
    }

    @Test
    fun closeWaitsForPendingTransport(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val targets = SurfaceInputTargets()
        val gate = CompletableFuture<Void>()
        val scope = MinestomConnectionScope(player, 1, player.instance)
        val tokens = ChatInputTokens(scope, targets, System::nanoTime)
        val surface =
            ChatDialogueSurface(
                scope,
                targets,
                tokens,
                { gate },
                UiLabelSource.defaults(),
                DialoguePresenter.Controls.defaults(),
                "/testinput",
            )
        val handle =
            surface
                .open(
                    DialogueSurface.SurfaceContext(
                        RenderFixtures.SESSION,
                        player.uuid.toString(),
                        1,
                        "chat",
                        "semantic",
                        CancellationToken(),
                        1,
                        Long.MAX_VALUE,
                    )
                )
                .toCompletableFuture()
                .join()
        val write =
            handle.replace(
                WidgetFrame(
                    RenderFixtures.view("Hello", 5),
                    emptyList(),
                    "semantic",
                    "semantic",
                )
            )
        val close = handle.closeAsync()
        assertFalse(close.toCompletableFuture().isDone)
        gate.complete(null)
        close.toCompletableFuture().join()
        assertTrue(write.toCompletableFuture().isCompletedExceptionally)
        assertNull(targets.current())
    }

    @Test
    fun staleAndForeignTokensCannotConfirm(env: Env) {
        val instance = env.createFlatInstance()
        val first = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        val second = env.createConnection().connect(instance, Pos(0.0, 42.0, 0.0))
        val now = AtomicLong()
        val targets = SurfaceInputTargets()
        val frame = RenderFixtures.frame("hud", "Hello", 5)
        targets.publish(frame, 1, mapOf("ask" to WidgetFrame.Rect(0.0, 0.0, 1.0, 1.0)))
        val tokens =
            ChatInputTokens(MinestomConnectionScope(first, 1, first.instance), targets, now::get)
        val token = tokens.issue("ask", checkNotNull(targets.current()))
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{22}")))
        assertTrue(tokens.resolve(second, 1, token).isEmpty)
        assertTrue(tokens.resolve(first, 2, token).isEmpty)
        assertEquals("ask", tokens.resolve(first, 1, token).orElseThrow().choiceId)
        now.set(30_000_000_001L)
        assertTrue(tokens.resolve(first, 1, token).isEmpty)
        val token2 = tokens.issue("ask", checkNotNull(targets.current()))
        targets.clear(1)
        assertTrue(tokens.resolve(first, 1, token2).isEmpty)
    }

    @Test
    fun sevenChoicesRemainReachable(env: Env) {
        assertChoicesReachable(env, 7)
    }

    @Test
    fun sixtyFourChoicesFitTokenBudget(env: Env) {
        assertChoicesReachable(env, 64)
    }

    private fun assertChoicesReachable(env: Env, count: Int) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val targets = SurfaceInputTargets()
        val scope = MinestomConnectionScope(player, 1, player.instance)
        val tokens = ChatInputTokens(scope, targets, System::nanoTime)
        val output = ArrayList<Component>()
        val surface =
            ChatDialogueSurface(
                scope,
                targets,
                tokens,
                {
                    output.add(it)
                    CompletableFuture.completedFuture(null)
                },
                UiLabelSource.defaults(),
                DialoguePresenter.Controls.defaults(),
                "/testinput",
            )
        val handle =
            surface
                .open(
                    DialogueSurface.SurfaceContext(
                        RenderFixtures.SESSION,
                        player.uuid.toString(),
                        1,
                        "chat",
                        "semantic",
                        CancellationToken(),
                        1,
                        Long.MAX_VALUE,
                    )
                )
                .toCompletableFuture()
                .join()
        val original = RenderFixtures.view("Hello", 5)
        val choices =
            (0 until count).map {
                DialogueView.ChoiceView(
                    "c$it",
                    "Choice $it",
                    DialogueView.Availability.AVAILABLE,
                    "",
                )
            }
        val next =
            DialogueView(
                original.sessionId(),
                original.contentPin(),
                original.nodeId(),
                original.nodeVisitId(),
                DialogueView.State.WAITING_FOR_INPUT,
                2,
                2,
                original.localeTag(),
                original.speaker(),
                original.text(),
                choices,
                "c0",
                5,
                5,
                DialogueView.HintPhase.CHOOSE,
                "",
                null,
            )
        handle
            .replace(WidgetFrame(next, emptyList(), "semantic", "semantic"))
            .toCompletableFuture()
            .join()
        assertEquals(count, checkNotNull(targets.current()).choices().size)
        val plain = PlainTextComponentSerializer.plainText().serialize(output.first())
        repeat(count) { assertTrue(plain.contains("Choice $it")) }
        handle.closeAsync().toCompletableFuture().join()
    }
}
