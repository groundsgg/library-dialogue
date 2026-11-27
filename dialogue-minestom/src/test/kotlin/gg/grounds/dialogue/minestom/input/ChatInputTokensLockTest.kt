package gg.grounds.dialogue.minestom.input

import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.render.RenderFixtures
import gg.grounds.dialogue.presentation.WidgetFrame
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.minestom.server.coordinate.Pos
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

@EnvTest
class ChatInputTokensLockTest {
    private fun probe(env: Env, issue: Boolean) {
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        val targets = SurfaceInputTargets()
        targets.publish(
            RenderFixtures.frame("hud", "Hello", 5),
            1,
            mapOf("ask" to WidgetFrame.Rect(0.0, 0.0, 1.0, 1.0)),
        )
        val owner = AtomicReference<ChatInputTokens>()
        val enabled = AtomicBoolean(issue)
        val tokens =
            ChatInputTokens(
                gg.grounds.dialogue.minestom.MinestomConnectionScope(player, 1, player.instance),
                targets,
            ) {
                if (enabled.get()) {
                    try {
                        CompletableFuture.runAsync { owner.get().clear() }
                            .get(300, TimeUnit.MILLISECONDS)
                    } catch (error: Exception) {
                        throw IllegalStateException("Clock callback ran under token monitor", error)
                    }
                }
                0
            }
        owner.set(tokens)
        if (issue) {
            assertDoesNotThrow { tokens.issue("ask", checkNotNull(targets.current())) }
        } else {
            val token = tokens.issue("ask", checkNotNull(targets.current()))
            enabled.set(true)
            assertDoesNotThrow { tokens.resolve(player, 1, token) }
        }
    }

    @Test fun clockCallbackIsOutsideIssueMonitor(env: Env) = probe(env, true)

    @Test fun clockCallbackIsOutsideResolveMonitor(env: Env) = probe(env, false)
}
