package gg.grounds.dialogue.testkit;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RecordingDialogueRendererTest {
  static DialogueRenderer.PresentationRequest request(String id) {
    return new DialogueRenderer.PresentationRequest(
        UUID.randomUUID(), id, 7, PresentationPreferences.defaults(), new CancellationToken(), 100);
  }

  @Test
  void immutableSnapshotsDoNotChangeAfterLaterWrites() {
    var r = new RecordingDialogueRenderer();
    var h = RuntimeFixture.done(r.prepare(request("a")));
    var f = new RuntimeFixture();
    var actual = f.start(r, "live");
    var view = actual.snapshot();
    RuntimeFixture.done(h.replace(view));
    var before = r.snapshots().getFirst();
    RuntimeFixture.done(h.replace(view));
    assertEquals(1, before.views().size());
    assertEquals(2, r.snapshots().getFirst().views().size());
    assertThrows(UnsupportedOperationException.class, () -> before.views().clear());
    assertThrows(UnsupportedOperationException.class, () -> r.snapshots().clear());
  }

  @Test
  void twoSessionsCloseIndependentlyAndRejectLateWrites() {
    var r = new RecordingDialogueRenderer();
    var h = RuntimeFixture.done(r.prepare(request("a")));
    RuntimeFixture.done(r.prepare(request("b")));
    var close = h.closeAsync();
    assertSame(close, h.closeAsync());
    // Completing a derived future cannot change the handle's cleanup result.
    close.toCompletableFuture().obtrudeException(new RuntimeException());
    RuntimeFixture.done(h.closeAsync());
    assertThrows(
        CompletionException.class,
        () -> h.reconfigure(PresentationPreferences.defaults()).toCompletableFuture().join());
    var f = new RuntimeFixture();
    var live = f.start(new RecordingDialogueRenderer(), "live");
    assertThrows(
        CompletionException.class, () -> h.replace(live.snapshot()).toCompletableFuture().join());
    var a = r.snapshots().getFirst();
    assertTrue(a.closed());
    assertEquals(3, a.closeCalls());
    assertEquals(2, a.rejectedWrites());
    assertTrue(a.views().isEmpty());
    assertFalse(r.snapshots().get(1).closed());
  }

  @Test
  void rendererUsesRealCorePort() {
    var f = new RuntimeFixture();
    var r = new RecordingDialogueRenderer();
    var h = f.start(r, "one");
    f.clock.advanceBy(Duration.ofSeconds(1));
    assertEquals("Hello", r.snapshots().getFirst().views().getLast().text());
    var lang = h.refreshLocale(new LocaleContext("de", 1, "en"));
    f.clock.runUntilIdle();
    RuntimeFixture.done(lang);
    assertEquals("Hallo", r.snapshots().getFirst().views().getLast().text());
    h.stop(DialogueEndReason.CANCELLED);
    f.clock.runUntilIdle();
    assertTrue(r.snapshots().getFirst().closed());
    int n = r.snapshots().getFirst().views().size();
    f.clock.advanceBy(Duration.ofSeconds(20));
    assertEquals(n, r.snapshots().getFirst().views().size());
  }
}
