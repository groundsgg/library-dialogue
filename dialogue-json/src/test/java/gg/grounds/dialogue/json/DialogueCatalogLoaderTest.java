package gg.grounds.dialogue.json;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DialogueCatalogLoaderTest {
  static DialogueCatalogLoader.ContentSources source(String line, String definition) {
    return new DialogueCatalogLoader.ContentSources(
        Map.of("mechanic.json", DialogueJsonCodecTest.bytes(definition)),
        Map.of("en", DialogueJsonCodecTest.bytes("{\"line\":\"" + line + "\"}")),
        DialogueJsonCodecTest.bytes(DialogueJsonCodecTest.HOST));
  }

  @Test
  void failedReloadKeepsPinnedSessions() {
    var loader = new DialogueCatalogLoader();
    assertTrue(loader.reload(source("Hello", DialogueJsonCodecTest.SOURCE)).isValid());
    var pinned = loader.current().orElseThrow().entries().get("demo:test");
    var first = pinned.contentPin();
    assertTrue(loader.reload(source("Changed", DialogueJsonCodecTest.SOURCE)).isValid());
    var second = loader.current().orElseThrow().entries().get("demo:test").contentPin();
    assertNotEquals(first, second);
    assertFalse(
        loader
            .reload(
                source(
                    "Broken",
                    DialogueJsonCodecTest.SOURCE.replace(
                        "\"next\":\"end\"", "\"next\":\"unknown\"")))
            .isValid());
    assertEquals(second, loader.current().orElseThrow().entries().get("demo:test").contentPin());
    assertEquals("Hello", pinned.texts().locales().get("en").get("line"));
  }

  @Test
  void sourcesAreImmutableAndDuplicateIdsRejectWholeReload() {
    var def = DialogueJsonCodecTest.bytes(DialogueJsonCodecTest.SOURCE);
    var sources = source("Hello", DialogueJsonCodecTest.SOURCE);
    var bytes = sources.dialogues().get("mechanic.json");
    bytes[0] = 0;
    var loader = new DialogueCatalogLoader();
    assertTrue(loader.reload(sources).isValid());
    var last = loader.current().orElseThrow();
    var repeated =
        new DialogueCatalogLoader.ContentSources(
            Map.of("a.json", def, "b.json", def), sources.texts(), sources.hostCatalog());
    assertFalse(loader.reload(repeated).isValid());
    assertSame(last, loader.current().orElseThrow());
  }

  @Test
  void runningSessionsKeepTheirOwnContentPin() {
    var loader = new DialogueCatalogLoader();
    loader.reload(source("Hello", DialogueJsonCodecTest.SOURCE));
    var first = loader.current().orElseThrow().entries().get("demo:test");
    var clock =
        new gg.grounds.dialogue.core.port.DialogueScheduler() {
          public void execute(Runnable r) {
            r.run();
          }

          public ScheduledTask schedule(java.time.Duration d, Runnable r) {
            return () -> {};
          }

          public long nanoTime() {
            return 0;
          }
        };
    var runtime =
        gg.grounds.dialogue.core.runtime.DialogueRuntime.create(
            gg.grounds.dialogue.core.runtime.RuntimeOptions.defaults(clock));
    java.util.function.BiFunction<
            String,
            gg.grounds.dialogue.core.validation.ValidatedDialogue,
            gg.grounds.dialogue.core.runtime.DialogueHandle>
        start =
            (id, v) -> {
              gg.grounds.dialogue.core.port.DialogueRenderer renderer =
                  req ->
                      java.util.concurrent.CompletableFuture.completedFuture(
                          new gg.grounds.dialogue.core.port.DialogueRenderer.PresentationHandle() {
                            public java.util.concurrent.CompletionStage<Void> replace(
                                gg.grounds.dialogue.core.runtime.DialogueView view) {
                              return java.util.concurrent.CompletableFuture.completedFuture(null);
                            }

                            public java.util.concurrent.CompletionStage<Void> reconfigure(
                                gg.grounds.dialogue.core.runtime.PresentationPreferences p) {
                              return java.util.concurrent.CompletableFuture.completedFuture(null);
                            }

                            public java.util.concurrent.CompletionStage<Void> closeAsync() {
                              return java.util.concurrent.CompletableFuture.completedFuture(null);
                            }
                          });
              var bindings =
                  new gg.grounds.dialogue.core.port.HostBindings(
                      id,
                      Map.of(),
                      Map.of(),
                      null,
                      gg.grounds.dialogue.core.text.TextResolver.catalogue(v.texts(), "en"),
                      renderer,
                      null,
                      Set.of());
              var request =
                  new gg.grounds.dialogue.core.runtime.DialogueStart(
                      id,
                      id,
                      1,
                      v,
                      bindings,
                      Map.of(),
                      new gg.grounds.dialogue.core.text.LocaleContext("en", 0, "en"),
                      new gg.grounds.dialogue.core.runtime.PresentationPreferences(
                          "headless", java.time.Duration.ZERO, true, Map.of()),
                      new gg.grounds.dialogue.core.runtime.CancellationToken(),
                      0);
              return ((gg.grounds.dialogue.core.runtime.DialogueStartResult.Started)
                      runtime.start(request).toCompletableFuture().join())
                  .handle();
            };
    var a = start.apply("a", first);
    loader.reload(source("Changed", DialogueJsonCodecTest.SOURCE));
    var second = loader.current().orElseThrow().entries().get("demo:test");
    var b = start.apply("b", second);
    assertEquals("Hello", a.snapshot().text());
    assertEquals("Changed", b.snapshot().text());
    assertEquals(first.contentPin(), a.snapshot().contentPin());
    assertEquals(second.contentPin(), b.snapshot().contentPin());
    runtime.closeAsync().toCompletableFuture().join();
  }

  @Test
  void sharedAuthorContextIsReusedAcrossDefinitions() {
    var a = source("Hello", DialogueJsonCodecTest.SOURCE);
    var sources =
        new DialogueCatalogLoader.ContentSources(
            Map.of(
                "a.json",
                DialogueJsonCodecTest.bytes(DialogueJsonCodecTest.SOURCE),
                "b.json",
                DialogueJsonCodecTest.bytes(
                    DialogueJsonCodecTest.SOURCE.replace("demo:test", "demo:other"))),
            a.texts(),
            a.hostCatalog());
    var loader = new DialogueCatalogLoader();
    assertTrue(loader.reload(sources).isValid());
    var entries = loader.current().orElseThrow().entries();
    assertSame(entries.get("demo:test").texts(), entries.get("demo:other").texts());
    assertSame(entries.get("demo:test").catalog(), entries.get("demo:other").catalog());
  }
}
