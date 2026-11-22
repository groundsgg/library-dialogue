package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.minestom.input.ChatInputTokens
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.ConnectionGuard
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
    fun chatRendersOncePerSemanticSection(env: Env) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val messages = ArrayList<Component>()
        val targets = SurfaceInputTargets()
        val tokens = ChatInputTokens(player, 1, targets, System::nanoTime)
        val surface =
            ChatDialogueSurface(
                ConnectionGuard(player, 1, player.instance),
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
        val tokens = ChatInputTokens(player, 1, targets, System::nanoTime)
        val surface =
            ChatDialogueSurface(
                ConnectionGuard(player, 1, player.instance),
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
        val tokens = ChatInputTokens(first, 1, targets, now::get)
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
        val tokens = ChatInputTokens(player, 1, targets, System::nanoTime)
        val output = ArrayList<Component>()
        val surface =
            ChatDialogueSurface(
                ConnectionGuard(player, 1, player.instance),
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
