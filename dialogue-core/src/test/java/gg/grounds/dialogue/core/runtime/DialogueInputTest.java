package gg.grounds.dialogue.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DialogueInputTest {
  static DialogueInput.Confirm confirm(DialogueHandle h, long impulse) {
    var v = h.snapshot();
    return new DialogueInput.Confirm(
        new DialogueInput.InputStamp(v.sessionId(), v.renderRevision(), impulse),
        v.selectedChoiceId());
  }

  @Test
  void openingDoubleAndHeldImpulse() {
    var r = new TestRig();
    var h = r.start();
    assertEquals(DialogueView.State.REVEALING, h.snapshot().state());
    var opening = h.submit(confirm(h, 0));
    r.scheduler.drain();
    assertEquals(InputResult.STALE, TestRig.done(opening));
    var first = h.submit(confirm(h, 1));
    r.scheduler.drain();
    assertEquals(InputResult.REVEAL_ONLY, TestRig.done(first));
    assertEquals("start", h.snapshot().nodeId());
    var duplicate = h.submit(confirm(h, 1));
    r.scheduler.drain();
    assertEquals(InputResult.STALE, TestRig.done(duplicate));
    var next = h.submit(confirm(h, 2));
    r.scheduler.drain();
    assertEquals(InputResult.ACCEPTED, TestRig.done(next));
    assertEquals("bye", h.snapshot().nodeId());
    var reveal = h.submit(confirm(h, 3));
    r.scheduler.drain();
    assertEquals(InputResult.REVEAL_ONLY, TestRig.done(reveal));
    assertEquals(DialogueView.HintPhase.FINISH, h.snapshot().hint());
    var done = h.submit(confirm(h, 4));
    r.scheduler.drain();
    assertEquals(DialogueEndReason.COMPLETED, TestRig.done(h.completion()).reason());
  }

  @Test
  void clockDrivesRevealWithoutInvalidatingTargets() {
    var r = new TestRig();
    var h = r.start();
    var rev = h.snapshot().renderRevision();
    assertEquals(0, h.snapshot().revealedGraphemes());
    r.scheduler.advance(Duration.ofMillis(35));
    assertEquals(1, h.snapshot().revealedGraphemes());
    assertEquals(rev, h.snapshot().renderRevision());
    var f = h.submit(new DialogueInput.RevealFinished(h.snapshot().sessionId(), rev));
    r.scheduler.drain();
    assertEquals(1, h.snapshot().revealedGraphemes());
    assertEquals("start", h.snapshot().nodeId());
    r.scheduler.advance(Duration.ofSeconds(1));
    assertEquals(DialogueView.State.WAITING_FOR_INPUT, h.snapshot().state());
  }
}
