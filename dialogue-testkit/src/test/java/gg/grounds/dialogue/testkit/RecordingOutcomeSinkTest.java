package gg.grounds.dialogue.testkit;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.DialogueDefinition.Delivery;
import gg.grounds.dialogue.core.port.OutcomeSink.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecordingOutcomeSinkTest {
  @Test
  void recordsExactEventAndAcknowledgesWithoutMutatingEarlierSnapshot() {
    var s = new RecordingOutcomeSink();
    var event =
        new OutcomeEvent(
            UUID.randomUUID(), "test", Delivery.LOCAL, "a", 1, UUID.randomUUID(), 2, "hash");
    assertEquals(OutcomeResult.ACKNOWLEDGED, RuntimeFixture.done(s.deliver(event)));
    var before = s.events();
    RuntimeFixture.done(s.deliver(event));
    assertEquals(List.of(event), before);
    assertEquals(2, s.events().size());
    assertThrows(UnsupportedOperationException.class, () -> before.clear());
  }
}
