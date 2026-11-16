package gg.grounds.dialogue.minestom;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.geometry.NpcPanelTransform;
import gg.grounds.dialogue.minestom.input.InputProfile;
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.packet.client.play.*;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class NpcNavigationTest {
  static InputProfile controls() {
    return new InputProfile(
        Set.of(InputProfile.Trigger.USE),
        Set.of(InputProfile.Trigger.SWAP_HANDS),
        InputProfile.Trigger.SWAP_HANDS,
        true,
        Duration.ofMillis(50));
  }

  static WidgetFrame frame(NativeReviewRegressionTest.F f, DialogueHandle h) {
    var presenter =
        new DialoguePresenter(
            Map.of(
                "npc",
                new DialoguePresenter.SurfaceProfile(
                    c -> CompletableFuture.failedFuture(new UnsupportedOperationException()),
                    LayoutProfile.npc(),
                    DialogueTheme.neutral(),
                    f.options.assets().metrics(),
                    false)),
            new MinestomDialogueScheduler(f.player.getInstance()));
    return presenter.compose(
        h.snapshot(), "npc", new PresentationPreferences("npc", Duration.ZERO, false, Map.of()));
  }

  static void aim(
      Env env, NativeReviewRegressionTest.F f, WidgetFrame frame, WidgetFrame.Rect rect) {
    var eye = f.player.getPosition().add(0, f.player.getEyeHeight(), 0);
    double height =
        frame.widgets().stream()
            .filter(w -> w.kind() == WidgetFrame.Kind.TEXT)
            .findFirst()
            .orElseThrow()
            .bounds()
            .height();
    double hinge =
        frame.widgets().stream()
            .filter(w -> w.kind() == WidgetFrame.Kind.CHOICE)
            .mapToDouble(w -> w.bounds().x())
            .min()
            .orElseThrow();
    var transform =
        NpcPanelTransform.from(
                new MinestomSpeakerBinding.AnchorSnapshot(
                    new Vec(0, 42, 0), 0, new Vec(0, 43.6, 0), true),
                eye)
            .withSpeechHeight(height)
            .withChoiceFold(hinge, 12);
    var point =
        transform
            .planeFor(rect)
            .widgetToWorld(rect.x() + rect.width() / 2, rect.y() + rect.height() / 2);
    // Aim geometrically: this public Minestom version's withLookAt pitch uses
    // max(abs(dx), abs(dz)), which misses narrow targets on diagonal rays.
    var delta = point.sub(eye);
    f.player.setView(
        (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z())),
        (float) -Math.toDegrees(Math.atan2(delta.y(), Math.hypot(delta.x(), delta.z()))));
    NativeReviewRegressionTest.ticks(env);
  }

  static void click(Env env, NativeReviewRegressionTest.F f, int sequence) throws Exception {
    Thread.sleep(60);
    f.packet(
        new ClientPlayerActionPacket(
            ClientPlayerActionPacket.Status.SWAP_ITEM_HAND, Vec.ZERO, BlockFace.TOP, sequence));
    NativeReviewRegressionTest.ticks(env);
  }

  @Test
  void arrowsOnlyPageAndDisabledEndCannotConfirmAnAnswer(Env env) throws Exception {
    for (boolean wrapped : List.of(false, true))
      try (var f = new NativeReviewRegressionTest.F(env, 7, wrapped)) {
        f.player.setNoGravity(true);
        var ticket = f.start("npc", false, controls());
        var h =
            ((DialogueStartResult.Started) NativeReviewRegressionTest.done(env, ticket.result()))
                .handle();
        assertTrue(
            f.player.getInstance().getEntities().stream()
                .filter(e -> e.getEntityType() == EntityType.TEXT_DISPLAY)
                .map(
                    e ->
                        PlainTextComponentSerializer.plainText()
                            .serialize(((TextDisplayMeta) e.getEntityMeta()).getText()))
                .anyMatch(t -> t.contains("\ue780") && t.contains("\ue781")));
        long visit = h.snapshot().nodeVisitId();
        long state = h.snapshot().stateRevision();
        for (int page = 1; page <= 2; page++) {
          var frame = frame(f, h);
          var down =
              frame.scrollbar().buttons().stream()
                  .filter(b -> b.direction() == WidgetFrame.NavigationDirection.NEXT)
                  .findFirst()
                  .orElseThrow();
          // Hit the generous target outside the much smaller painted chevron.
          var target = down.bounds();
          aim(env, f, frame, new WidgetFrame.Rect(target.x() + 3, target.y() + 3, 2, 2));
          click(env, f, page);
          assertEquals(
              "c0", h.snapshot().selectedChoiceId(), "Browsing must not select a different answer");
          assertEquals(state, h.snapshot().stateRevision());
          String visible = "Choice " + page * 3;
          assertTrue(choiceTexts(f).stream().anyMatch(t -> t.contains(visible)));
          assertTrue(choiceTexts(f).stream().noneMatch(t -> t.contains("Choice 0")));
          assertTrue(choiceScales(f).stream().allMatch(scale -> scale <= .320001));
          assertEquals(visit, h.snapshot().nodeVisitId());
        }
        click(env, f, 3);
        assertEquals("c0", h.snapshot().selectedChoiceId());
        assertEquals(visit, h.snapshot().nodeVisitId());
        var last = frame(f, h);
        var up =
            last.scrollbar().buttons().stream()
                .filter(b -> b.direction() == WidgetFrame.NavigationDirection.PREVIOUS)
                .findFirst()
                .orElseThrow();
        aim(env, f, last, up.bounds());
        click(env, f, 4);
        assertEquals("c0", h.snapshot().selectedChoiceId());
        assertEquals(visit, h.snapshot().nodeVisitId());
        assertTrue(choiceTexts(f).stream().anyMatch(t -> t.contains("Choice 3")));
        // A confirm away from the cards must not execute the now-invisible selection.
        aim(
            env,
            f,
            last,
            last.widgets().stream()
                .filter(w -> w.kind() == WidgetFrame.Kind.TEXT)
                .findFirst()
                .orElseThrow()
                .bounds());
        click(env, f, 5);
        assertEquals(visit, h.snapshot().nodeVisitId());
        aim(env, f, last, up.bounds());
        click(env, f, 6);
        assertTrue(choiceTexts(f).stream().anyMatch(t -> t.contains("Choice 0")));
        assertTrue(choiceScales(f).stream().anyMatch(scale -> scale > .33));
        assertEquals(state, h.snapshot().stateRevision());
      }
  }

  @Test
  void wheelAfterBrowsingRevealsSelectionAndFreshHoverCanChooseAnotherAnswer(Env env)
      throws Exception {
    try (var f = new NativeReviewRegressionTest.F(env, 7)) {
      f.player.setNoGravity(true);
      var ticket = f.start("npc", false, controls());
      var h =
          ((DialogueStartResult.Started) NativeReviewRegressionTest.done(env, ticket.result()))
              .handle();
      var first = frame(f, h);
      var down =
          first.scrollbar().buttons().stream()
              .filter(b -> b.direction() == WidgetFrame.NavigationDirection.NEXT)
              .findFirst()
              .orElseThrow();
      aim(env, f, first, down.bounds());
      click(env, f, 1);
      assertEquals("c0", h.snapshot().selectedChoiceId());
      f.packet(new ClientHeldItemChangePacket((short) 1));
      NativeReviewRegressionTest.ticks(env);
      assertEquals("c1", h.snapshot().selectedChoiceId());
      assertTrue(choiceTexts(f).stream().anyMatch(t -> t.contains("Choice 1")));
      aim(env, f, first, down.bounds());
      click(env, f, 2);
      assertEquals("c1", h.snapshot().selectedChoiceId());
      // The second visible row now represents c4, not the old c1.
      var row =
          first.widgets().stream().filter(w -> "c1".equals(w.choiceId())).findFirst().orElseThrow();
      aim(env, f, first, row.bounds());
      assertEquals("c4", h.snapshot().selectedChoiceId());
      click(env, f, 3);
      assertEquals("reply4", h.snapshot().nodeId());
    }
  }

  static List<String> choiceTexts(NativeReviewRegressionTest.F f) {
    return f.player.getInstance().getEntities().stream()
        .filter(e -> e.getEntityType() == EntityType.TEXT_DISPLAY)
        .map(
            e ->
                PlainTextComponentSerializer.plainText()
                    .serialize(((TextDisplayMeta) e.getEntityMeta()).getText()))
        .filter(t -> t.contains("Choice "))
        .toList();
  }

  static List<Double> choiceScales(NativeReviewRegressionTest.F f) {
    return f.player.getInstance().getEntities().stream()
        .filter(e -> e.getEntityType() == EntityType.TEXT_DISPLAY)
        .map(e -> (TextDisplayMeta) e.getEntityMeta())
        .filter(
            m ->
                PlainTextComponentSerializer.plainText().serialize(m.getText()).contains("Choice "))
        .map(m -> m.getScale().x())
        .toList();
  }

  @Test
  void wheelPageChangeAndConfirmKeepTheManualSelectionUnderStationaryGaze(Env env)
      throws Exception {
    try (var f = new NativeReviewRegressionTest.F(env, 7)) {
      f.player.setNoGravity(true);
      var ticket = f.start("npc", false, controls());
      var h =
          ((DialogueStartResult.Started) NativeReviewRegressionTest.done(env, ticket.result()))
              .handle();
      var frame = frame(f, h);
      var second =
          frame.widgets().stream().filter(w -> "c1".equals(w.choiceId())).findFirst().orElseThrow();
      aim(env, f, frame, second.bounds());
      assertEquals("c1", h.snapshot().selectedChoiceId());
      f.packet(new ClientHeldItemChangePacket((short) 1));
      NativeReviewRegressionTest.ticks(env);
      assertEquals("c2", h.snapshot().selectedChoiceId());
      f.packet(new ClientHeldItemChangePacket((short) 2));
      NativeReviewRegressionTest.ticks(env);
      assertEquals("c3", h.snapshot().selectedChoiceId());
      click(env, f, 1);
      assertEquals("reply3", h.snapshot().nodeId());
    }
  }
}
