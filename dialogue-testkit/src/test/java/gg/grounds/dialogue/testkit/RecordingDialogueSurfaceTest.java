package gg.grounds.dialogue.testkit;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.presentation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RecordingDialogueSurfaceTest {
  @Test
  void surfaceUsesRealPresenterPortAndImmutableSnapshots() {
    var f = new RuntimeFixture();
    var s = new RecordingDialogueSurface();
    var presenter =
        new DialoguePresenter(Map.of("hud", DialoguePresenter.SurfaceProfile.semantic(s)), f.clock);
    var h = f.start(presenter, "a");
    var before = s.snapshots().getFirst();
    assertFalse(before.frames().isEmpty());
    int n = before.frames().size();
    f.clock.advanceBy(Duration.ofSeconds(1));
    assertEquals(n, before.frames().size());
    assertTrue(s.snapshots().getFirst().frames().size() > n);
    h.refreshLocale(new LocaleContext("de", 1, "en"));
    f.clock.runUntilIdle();
    assertEquals("Hallo", s.snapshots().getFirst().frames().getLast().semantic().text());
    h.stop(DialogueEndReason.CANCELLED);
    f.clock.runUntilIdle();
    assertTrue(s.snapshots().getFirst().closed());
  }

  @Test
  void closeIsIndependentAndRejectsReplaceAndConfigure() {
    var s = new RecordingDialogueSurface();
    var c =
        new DialogueSurface.SurfaceContext(
            UUID.randomUUID(), "a", 1, "hud", "font", new CancellationToken(), 3, 100);
    var a = RuntimeFixture.done(s.open(c));
    RuntimeFixture.done(s.open(c));
    var close = a.closeAsync();
    assertSame(close, a.closeAsync());
    assertThrows(
        CompletionException.class,
        () -> a.configure(PresentationPreferences.defaults()).toCompletableFuture().join());
    var f = new RuntimeFixture();
    var r = new RecordingDialogueRenderer();
    var h = f.start(r, "x");
    var frame = new WidgetFrame(h.snapshot(), List.of(), "font", "theme");
    assertThrows(CompletionException.class, () -> a.replace(frame).toCompletableFuture().join());
    var snapshot = s.snapshots().getFirst();
    assertEquals(2, snapshot.closeCalls());
    assertEquals(2, snapshot.rejectedWrites());
    assertTrue(snapshot.frames().isEmpty());
    assertFalse(s.snapshots().get(1).closed());
  }
}
