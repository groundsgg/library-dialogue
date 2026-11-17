package gg.grounds.dialogue.minestom.input;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.minestom.render.RenderFixtures;
import gg.grounds.dialogue.presentation.WidgetFrame;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minestom.server.coordinate.Pos;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class ChatInputTokensLockTest {
  private void probe(Env env, boolean issue) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var targets = new SurfaceInputTargets();
    targets.publish(
        RenderFixtures.frame("hud", "Hello", 5),
        1,
        Map.of("ask", new WidgetFrame.Rect(0, 0, 1, 1)));
    var owner = new AtomicReference<ChatInputTokens>();
    var enabled = new AtomicBoolean(issue);
    var tokens =
        new ChatInputTokens(
            player,
            1,
            targets,
            () -> {
              if (enabled.get())
                try {
                  CompletableFuture.runAsync(() -> owner.get().clear())
                      .get(300, TimeUnit.MILLISECONDS);
                } catch (Exception error) {
                  throw new IllegalStateException("Clock callback ran under token monitor", error);
                }
              return 0;
            });
    owner.set(tokens);
    if (issue) assertDoesNotThrow(() -> tokens.issue("ask", targets.current()));
    else {
      var token = tokens.issue("ask", targets.current());
      enabled.set(true);
      assertDoesNotThrow(() -> tokens.resolve(player, 1, token));
    }
  }

  @Test
  void clockCallbackIsOutsideIssueMonitor(Env env) {
    probe(env, true);
  }

  @Test
  void clockCallbackIsOutsideResolveMonitor(Env env) {
    probe(env, false);
  }
}
