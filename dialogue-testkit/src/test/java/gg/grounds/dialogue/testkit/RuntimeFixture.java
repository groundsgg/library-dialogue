package gg.grounds.dialogue.testkit;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import java.util.*;
import java.util.concurrent.*;

final class RuntimeFixture {
  final ManualDialogueScheduler clock = new ManualDialogueScheduler();
  final DialogueRuntime runtime = DialogueRuntime.create(RuntimeOptions.defaults(clock));
  final ValidatedDialogue content;

  RuntimeFixture() {
    var definition =
        DialogueBuilder.dialogue("test:hello")
            .locales("en", "en", "de")
            .node("start", new Message("npc", "hello", null, List.of(), "end"))
            .node("end", new End("completed"))
            .build();
    var report =
        new DialogueValidator()
            .validate(
                definition,
                new TextCatalog(
                    Map.of("en", Map.of("hello", "Hello"), "de", Map.of("hello", "Hallo"))),
                new HostCatalog("v1", Set.of("npc"), Map.of(), Map.of(), Set.of(), Set.of(), false),
                ValidationLimits.defaults());
    if (!report.isValid()) throw new AssertionError(report.problems());
    content = report.validated();
  }

  DialogueHandle start(DialogueRenderer renderer, String id) {
    var bindings =
        new HostBindings(
            "v1",
            Map.of(),
            Map.of(),
            null,
            TextResolver.catalogue(content.texts(), "en"),
            renderer,
            null,
            Set.of());
    var stage =
        runtime.start(
            new DialogueStart(
                id,
                id,
                1,
                content,
                bindings,
                Map.of(),
                new LocaleContext("en", 0, "en"),
                PresentationPreferences.defaults(),
                new CancellationToken(),
                0));
    clock.runUntilIdle();
    return ((DialogueStartResult.Started) done(stage)).handle();
  }

  static <T> T done(CompletionStage<T> s) {
    if (!s.toCompletableFuture().isDone()) throw new AssertionError("pending");
    return s.toCompletableFuture().join();
  }
}
