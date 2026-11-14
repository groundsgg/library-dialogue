package gg.grounds.dialogue.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.text.LocaleContext;
import gg.grounds.dialogue.core.validation.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DialogueActionsTest {
  static final class Jobs implements ActionHandler {
    int executes, effects;
    ActionRequest request;
    final CompletableFuture<ActionResult> response = new CompletableFuture<>();
    final Map<UUID, ActionStatus> registry = new HashMap<>();

    public CompletionStage<ActionResult> execute(ActionRequest r) {
      executes++;
      request = r;
      registry.putIfAbsent(r.reference().requestId(), ActionStatus.PENDING);
      response.thenAccept(
          s -> {
            if (s == ActionResult.SUCCESS) {
              effects++;
              registry.put(r.reference().requestId(), ActionStatus.SUCCEEDED);
            }
          });
      return response;
    }

    public CompletionStage<ActionStatus> reconcile(ActionRef r) {
      return CompletableFuture.completedFuture(
          registry.getOrDefault(r.requestId(), ActionStatus.UNAVAILABLE));
    }
  }

  static TestRig rig(Jobs jobs) {
    var d =
        DialogueBuilder.dialogue("demo:actions")
            .locales("en", "en", "de")
            .node("start", new Message("npc", "hello", null, List.of(), "execute"))
            .node(
                "execute",
                new Action(
                    "demo:accept", Map.of(), "success", "rejected", "unavailable", "uncertain"))
            .node("success", new Message("npc", "bye", null, List.of(), "end"))
            .node("rejected", new Message("npc", "bye", null, List.of(), "end"))
            .node("unavailable", new Message("npc", "bye", null, List.of(), "end"))
            .node("uncertain", new Message("npc", "hello", null, List.of(), "end"))
            .node("end", new End("completed"))
            .build();
    var r =
        new TestRig(
            d,
            new HostCatalog(
                "v1",
                Set.of("npc"),
                Map.of(),
                Map.of("demo:accept", new HostCatalog.ProviderSpec(Map.of(), true)),
                Set.of(),
                Set.of(),
                false));
    r.bindings =
        new HostBindings(
            "jobs",
            Map.of(),
            Map.of("demo:accept", jobs),
            null,
            r.bindings.texts(),
            r.bindings.renderer(),
            null,
            Set.of());
    return r;
  }

  static DialogueHandle execute(TestRig r) {
    var h = r.start();
    h.submit(DialogueInputTest.confirm(h, 1));
    r.scheduler.drain();
    h.submit(DialogueInputTest.confirm(h, 2));
    r.scheduler.drain();
    assertEquals(DialogueView.State.WAITING_FOR_PROVIDER, h.snapshot().state());
    return h;
  }

  @Test
  void localeChangesDuringAction() {
    var jobs = new Jobs();
    var r = rig(jobs);
    var h = execute(r);
    assertEquals(1, jobs.registry.size());
    var f = h.refreshLocale(new LocaleContext("de", 1, "en"));
    r.scheduler.drain();
    assertEquals(RefreshResult.UPDATED, TestRig.done(f));
    jobs.response.complete(ActionHandler.ActionResult.SUCCESS);
    r.scheduler.drain();
    assertEquals("success", h.snapshot().nodeId());
    assertEquals("Tschüss", h.snapshot().text());
    assertEquals(1, jobs.executes);
    assertEquals(1, jobs.effects);
  }

  @Test
  void lateSuccessNeverLeavesUncertainPath() {
    var jobs = new Jobs();
    var r = rig(jobs);
    var h = execute(r);
    var ref = h.snapshot().pendingAction();
    assertNotNull(ref);
    r.scheduler.advance(Duration.ofSeconds(10));
    assertEquals("uncertain", h.snapshot().nodeId());
    long visit = h.snapshot().nodeVisitId();
    jobs.response.complete(ActionHandler.ActionResult.SUCCESS);
    r.scheduler.drain();
    assertEquals(visit, h.snapshot().nodeVisitId());
    assertEquals(1, jobs.effects);
    assertEquals(ActionHandler.ActionStatus.SUCCEEDED, TestRig.done(jobs.reconcile(ref)));
    assertEquals(1, jobs.executes);
  }

  @Test
  void cancelledActionDoesNotReopenUi() {
    var jobs = new Jobs();
    var r = rig(jobs);
    var h = execute(r);
    var done = h.stop(DialogueEndReason.DISCONNECTED);
    r.scheduler.drain();
    assertEquals(DialogueEndReason.DISCONNECTED, TestRig.done(done).reason());
    jobs.response.complete(ActionHandler.ActionResult.SUCCESS);
    r.scheduler.drain();
    assertEquals(DialogueView.State.CLOSED, h.snapshot().state());
    assertEquals(1, jobs.executes);
  }

  @Test
  void allExplicitActionResultsUseTheirOwnPaths() {
    for (var item :
        Map.of(
                ActionHandler.ActionResult.SUCCESS,
                "success",
                ActionHandler.ActionResult.REJECTED,
                "rejected",
                ActionHandler.ActionResult.UNAVAILABLE,
                "unavailable",
                ActionHandler.ActionResult.UNCERTAIN,
                "uncertain")
            .entrySet()) {
      var jobs = new Jobs();
      var r = rig(jobs);
      var h = execute(r);
      jobs.response.complete(item.getKey());
      r.scheduler.drain();
      assertEquals(item.getValue(), h.snapshot().nodeId());
      assertEquals(1, jobs.executes);
    }
  }

  @Test
  void surfaceSwitchDuringActionKeepsTheJob() {
    var jobs = new Jobs();
    var r = rig(jobs);
    var h = execute(r);
    long visit = h.snapshot().nodeVisitId();
    var f =
        h.updatePreferences(
            new PresentationPreferences("npc", Duration.ofMillis(35), false, Map.of()));
    r.scheduler.drain();
    TestRig.done(f);
    assertEquals(visit, h.snapshot().nodeVisitId());
    assertEquals(1, jobs.executes);
    jobs.response.complete(ActionHandler.ActionResult.SUCCESS);
    r.scheduler.drain();
    assertEquals("success", h.snapshot().nodeId());
    assertEquals(1, jobs.executes);
  }
}
