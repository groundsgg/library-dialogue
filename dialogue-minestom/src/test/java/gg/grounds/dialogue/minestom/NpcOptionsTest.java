package gg.grounds.dialogue.minestom;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.presentation.DialoguePresenter;
import java.time.Duration;
import java.util.*;
import net.minestom.server.entity.*;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class NpcOptionsTest {
  @Test
  void optionsAreBoundedAndCanDisableTheFold() {
    assertEquals(
        new NpcPanelOptions(12, 12), NpcPanelOptions.from(PresentationPreferences.defaults()));
    assertEquals(
        0,
        NpcPanelOptions.from(
                new PresentationPreferences(
                    "npc", Duration.ZERO, false, Map.of("npcChoiceAngle", "0")))
            .choiceAngle());
    for (var value : List.of("-1", "65", "12.25", "NaN"))
      assertThrows(
          DialoguePresenter.FallbackRequired.class,
          () ->
              NpcPanelOptions.from(
                  new PresentationPreferences(
                      "npc", Duration.ZERO, false, Map.of("npcChoiceGap", value))));
    assertEquals(
        12.5,
        NpcPanelOptions.from(
                new PresentationPreferences(
                    "npc", Duration.ZERO, false, Map.of("npcChoiceGap", "12.5")))
            .choiceGap());
    for (var value : List.of("NaN", "Infinity", "-1", "36", "bad"))
      assertThrows(
          DialoguePresenter.FallbackRequired.class,
          () ->
              NpcPanelOptions.from(
                  new PresentationPreferences(
                      "npc", Duration.ZERO, false, Map.of("npcChoiceAngle", value))));
  }

  @Test
  void nativeOptionsChangeLiveWithoutRestartAndScrollbarIsPrivateAndCleanedUp(Env env) {
    try (var f = new NativeReviewRegressionTest.F(env, 7)) {
      var ticket = f.start("npc", false);
      var h =
          ((DialogueStartResult.Started) NativeReviewRegressionTest.done(env, ticket.result()))
              .handle();
      long visit = h.snapshot().nodeVisitId();
      for (String angle : List.of("0", "30", "12")) {
        NativeReviewRegressionTest.done(
            env,
            h.updatePreferences(
                new PresentationPreferences(
                    "npc",
                    Duration.ZERO,
                    false,
                    Map.of("npcChoiceAngle", angle, "npcChoiceGap", "24"))));
        var displays =
            f.player.getInstance().getEntities().stream()
                .filter(e -> e.getEntityType() == EntityType.TEXT_DISPLAY)
                .toList();
        assertTrue(displays.size() <= 32);
        var option =
            displays.stream()
                .map(e -> (TextDisplayMeta) e.getEntityMeta())
                .filter(
                    m ->
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText()
                            .serialize(m.getText())
                            .contains("Choice 0"))
                .findFirst()
                .orElseThrow();
        assertEquals(
            -Math.sin(Math.toRadians(Double.parseDouble(angle)) / 2),
            option.getLeftRotation()[1],
            1e-6);
        if (angle.equals("0")) {
          double factor = option.getScale().x() / .32;
          double left = option.getTranslation().x() / .008 + 160 + (1 - 240d / 2) * factor;
          assertTrue(left - 320 >= 24 - 1e-5);
        }
        var bars =
            displays.stream()
                .filter(
                    e ->
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText()
                            .serialize(((TextDisplayMeta) e.getEntityMeta()).getText())
                            .codePoints()
                            .anyMatch(c -> c >= 0xe700 && c < 0xe780))
                .toList();
        assertEquals(
            1, bars.size(), "Scrollbar paint is front-only; no mirrored bar on panel backs");
        assertTrue(bars.stream().allMatch(e -> e.getViewers().equals(Set.of(f.player))));
        assertEquals(visit, h.snapshot().nodeVisitId());
        assertEquals("npc", ticket.status().effectiveSurface());
      }
      NativeReviewRegressionTest.done(env, ticket.cancel(DialogueEndReason.CANCELLED));
      assertTrue(
          f.player.getInstance().getEntities().stream()
              .noneMatch(e -> e.getEntityType() == EntityType.TEXT_DISPLAY));
    }
  }
}
