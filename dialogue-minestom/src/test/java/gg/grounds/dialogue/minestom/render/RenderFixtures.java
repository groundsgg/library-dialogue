package gg.grounds.dialogue.minestom.render;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.validation.ContentPin;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class RenderFixtures {
  public static final UUID SESSION = UUID.fromString("10000000-0000-0000-0000-000000000001");
  public static final AssetBundle ASSETS =
      AssetBundle.loadDefault(MinecraftPackProfile.public262());

  public static DialogueView view(String text, int revealed) {
    return new DialogueView(
        SESSION,
        new ContentPin("0".repeat(64)),
        "start",
        1,
        DialogueView.State.REVEALING,
        1,
        1,
        "en",
        "Mechanic",
        text,
        List.of(
            new DialogueView.ChoiceView("ask", "Ask", DialogueView.Availability.AVAILABLE, ""),
            new DialogueView.ChoiceView("leave", "Leave", DialogueView.Availability.AVAILABLE, "")),
        "ask",
        text.length(),
        revealed,
        DialogueView.HintPhase.CHOOSE,
        "",
        null);
  }

  public static WidgetFrame frame(String key, String text, int reveal) {
    DialogueScheduler clock =
        new DialogueScheduler() {
          public void execute(Runnable r) {
            r.run();
          }

          public ScheduledTask schedule(Duration d, Runnable r) {
            return () -> {};
          }

          public long nanoTime() {
            return 0;
          }
        };
    var presenter =
        new DialoguePresenter(
            Map.of(
                key,
                new DialoguePresenter.SurfaceProfile(
                    context -> CompletableFuture.failedFuture(new UnsupportedOperationException()),
                    key.equals("npc") ? LayoutProfile.npc() : LayoutProfile.hud(),
                    DialogueTheme.neutral(),
                    ASSETS.metrics(),
                    false)),
            clock);
    return presenter.compose(view(text, reveal), key, PresentationPreferences.defaults());
  }
}
