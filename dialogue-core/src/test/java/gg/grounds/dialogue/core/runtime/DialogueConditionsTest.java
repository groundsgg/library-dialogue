package gg.grounds.dialogue.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.validation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DialogueConditionsTest {
  @Test
  void conditionChangedBeforeConfirm() {
    var condition = new Condition("demo:ok", Map.of(), false);
    var d =
        DialogueBuilder.dialogue("demo:choices")
            .node(
                "start",
                new Message(
                    "npc",
                    "hello",
                    null,
                    List.of(
                        new Choice("offer", "c", "end", true, condition),
                        new Choice("leave", "bye", "end", false, null)),
                    null))
            .node("end", new End("completed"))
            .build();
    var r =
        new TestRig(
            d,
            new HostCatalog(
                "v1",
                Set.of("npc"),
                Map.of("demo:ok", new HostCatalog.ProviderSpec(Map.of(), false)),
                Map.of(),
                Set.of(),
                Set.of(),
                false));
    var available = new AtomicReference<>(ConditionProvider.ConditionResult.TRUE);
    r.bindings =
        new HostBindings(
            "conditions",
            Map.of("demo:ok", req -> CompletableFuture.completedFuture(available.get())),
            Map.of(),
            null,
            r.bindings.texts(),
            r.bindings.renderer(),
            null,
            Set.of());
    var h = r.start();
    assertEquals("offer", h.snapshot().selectedChoiceId());
    h.submit(DialogueInputTest.confirm(h, 1));
    r.scheduler.drain();
    available.set(ConditionProvider.ConditionResult.FALSE);
    var f = h.submit(DialogueInputTest.confirm(h, 2));
    r.scheduler.drain();
    assertEquals(InputResult.STALE, TestRig.done(f));
    assertEquals("start", h.snapshot().nodeId());
    assertEquals("leave", h.snapshot().selectedChoiceId());
    assertEquals(
        List.of("leave"),
        h.snapshot().choices().stream().map(DialogueView.ChoiceView::id).toList());
  }

  @Test
  void unavailableIncludingNegationIsNotFalse() {
    var c = new Condition("demo:unknown", Map.of(), true);
    var d =
        DialogueBuilder.dialogue("demo:branch")
            .node("start", new Branch(List.of(new Case(c, "wrong")), "wrong", "safe"))
            .node("wrong", new Message("npc", "hello", null, List.of(), "end"))
            .node("safe", new Message("npc", "bye", null, List.of(), "end"))
            .node("end", new End("completed"))
            .build();
    var r =
        new TestRig(
            d,
            new HostCatalog(
                "v1",
                Set.of("npc"),
                Map.of("demo:unknown", new HostCatalog.ProviderSpec(Map.of(), false)),
                Map.of(),
                Set.of(),
                Set.of(),
                false));
    var h = r.start();
    assertEquals("safe", h.snapshot().nodeId());
  }

  @Test
  void unchangedChoiceConfirmUsesResolvedLabels() {
    var d =
        DialogueBuilder.dialogue("demo:resolved")
            .node(
                "start",
                new Message(
                    "npc",
                    "hello",
                    null,
                    List.of(new Choice("leave", "c", "end", true, null)),
                    null))
            .node("end", new End("completed"))
            .build();
    var r =
        new TestRig(
            d, new HostCatalog("v1", Set.of("npc"), Map.of(), Map.of(), Set.of(), Set.of(), false));
    var resolver = r.bindings.texts();
    r.bindings =
        new HostBindings(
            "translated",
            Map.of(),
            Map.of(),
            null,
            req ->
                resolver
                    .resolve(req)
                    .thenApply(
                        t ->
                            req.key().equals("c")
                                ? new gg.grounds.dialogue.core.text.ResolvedText(
                                    "Resolved answer", t.localeTag())
                                : t),
            r.bindings.renderer(),
            null,
            Set.of());
    var h = r.start();
    h.submit(DialogueInputTest.confirm(h, 1));
    r.scheduler.drain();
    var result = h.submit(DialogueInputTest.confirm(h, 2));
    r.scheduler.drain();
    assertEquals(InputResult.ACCEPTED, TestRig.done(result));
    assertEquals(DialogueEndReason.COMPLETED, TestRig.done(h.completion()).reason());
  }

  @Test
  void automaticStepGuardStopsLongAcyclicChain() {
    var b = DialogueBuilder.dialogue("demo:chain").entry("b0").node("end", new End("completed"));
    for (int i = 0; i < 33; i++)
      b.node("b" + i, new Branch(List.of(), i == 32 ? "end" : "b" + (i + 1), "end"));
    var r =
        new TestRig(
            b.build(),
            new HostCatalog("v1", Set.of(), Map.of(), Map.of(), Set.of(), Set.of(), false));
    var f = r.runtime.start(r.request("chain"));
    r.scheduler.drain();
    assertEquals("AUTOMATIC_STEP_LIMIT", ((DialogueStartResult.Rejected) TestRig.done(f)).code());
  }
}
