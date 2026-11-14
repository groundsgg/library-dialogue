package gg.grounds.dialogue.core.validation;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.text.TextCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

class DialogueValidatorTest {
  static final TextCatalog TEXTS =
      new TextCatalog(Map.of("en", Map.of("line", "Hello", "c", "Continue")));
  static final HostCatalog CATALOG =
      new HostCatalog(
          "v1",
          Set.of("npc"),
          Map.of("demo:ok", new HostCatalog.ProviderSpec(Map.of(), false)),
          Map.of(),
          Set.of(),
          Set.of(),
          true);

  static ValidationReport check(DialogueDefinition d) {
    return new DialogueValidator().validate(d, TEXTS, CATALOG, ValidationLimits.defaults());
  }

  static DialogueBuilder base() {
    return DialogueBuilder.dialogue("demo:test").node("end", new End("completed"));
  }

  static Condition condition() {
    return new Condition("demo:ok", Map.of(), false);
  }

  @Test
  void conditionalOnlyChoicesRejected() {
    var conditional = new Choice("c", "c", "end", true, condition());
    var bad =
        check(
            base()
                .node("start", new Message("npc", "line", null, List.of(conditional), null))
                .build());
    assertTrue(
        bad.problems().stream().anyMatch(p -> p.code().equals("CHOICE_ALWAYS_AVAILABLE_REQUIRED")));
    assertFalse(bad.isValid());
    assertTrue(
        check(
                base()
                    .node(
                        "start",
                        new Message(
                            "npc",
                            "line",
                            null,
                            List.of(conditional, new Choice("leave", "c", "end", false, null)),
                            null))
                    .build())
            .isValid());
  }

  @Test
  void automaticCyclesRejectedButQuestionLoopAllowed() {
    var cycle =
        base()
            .node("start", new Branch(List.of(new Case(condition(), "end")), "loop", "end"))
            .node("loop", new Branch(List.of(), "start", "end"))
            .build();
    assertTrue(check(cycle).problems().stream().anyMatch(p -> p.code().equals("AUTOMATIC_CYCLE")));
    assertTrue(
        check(
                base()
                    .node(
                        "start",
                        new Message(
                            "npc",
                            "line",
                            null,
                            List.of(
                                new Choice("again", "c", "start", false, null),
                                new Choice("leave", "c", "end", false, null)),
                            null))
                    .build())
            .isValid());
  }

  @Test
  void graphAndCatalogFailuresAreLocated() {
    var d =
        base()
            .node(
                "start",
                new Message(
                    "unknown",
                    "missing",
                    null,
                    List.of(
                        new Choice("same", "c", "gone", false, null),
                        new Choice("same", "c", "end", false, null)),
                    null))
            .build();
    var codes = check(d).problems().stream().map(ValidationReport.Problem::code).toList();
    assertTrue(
        codes.containsAll(
            List.of("SPEAKER_UNKNOWN", "TEXT_MISSING", "REFERENCE_UNKNOWN", "CHOICE_DUPLICATE")));
    assertTrue(
        check(d).problems().stream().allMatch(p -> p.path() != null && !p.message().isBlank()));
  }

  @Test
  void boundariesAreAppliedToJavaDefinitions() {
    var b = base().node("start", new Message("npc", "line", null, List.of(), "end"));
    for (int i = 0; i < 1022; i++) b.node("n" + i, new End("completed"));
    assertTrue(check(b.build()).isValid());
    assertFalse(check(b.node("extra", new End("completed")).build()).isValid());
    var choices = new ArrayList<Choice>();
    for (int i = 0; i < 64; i++) choices.add(new Choice("c" + i, "c", "end", false, null));
    assertTrue(
        check(base().node("start", new Message("npc", "line", null, choices, null)).build())
            .isValid());
    choices.add(new Choice("extra", "c", "end", false, null));
    assertFalse(
        check(base().node("start", new Message("npc", "line", null, choices, null)).build())
            .isValid());
  }

  @Test
  void missingEndAndInvalidArgumentsRejected() {
    assertFalse(
        check(
                DialogueBuilder.dialogue("demo:loop")
                    .node("start", new Message("npc", "line", null, List.of(), "start"))
                    .build())
            .isValid());
    var c = new Condition("demo:ok", Map.of("injected", ArgumentValue.of("/op @s")), false);
    assertTrue(
        check(base().node("start", new Branch(List.of(new Case(c, "end")), "end", "end")).build())
            .problems()
            .stream()
            .anyMatch(p -> p.code().equals("ARGUMENT_UNKNOWN")));
  }

  @Test
  void malformedUnusedTextKeyIsAReportNotAnException() {
    var d = base().node("start", new Message("npc", "line", null, List.of(), "end")).build();
    var bad = new TextCatalog(Map.of("en", Map.of("line", "Hello", "\ud800", "bad")));
    var report =
        assertDoesNotThrow(
            () -> new DialogueValidator().validate(d, bad, CATALOG, ValidationLimits.defaults()));
    assertFalse(report.isValid());
  }
}
