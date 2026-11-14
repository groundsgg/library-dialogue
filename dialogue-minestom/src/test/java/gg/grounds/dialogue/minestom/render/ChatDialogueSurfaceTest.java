package gg.grounds.dialogue.minestom.render;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.internal.ConnectionGuard;
import gg.grounds.dialogue.presentation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class ChatDialogueSurfaceTest {
  @Test
  void chatRendersOncePerSemanticSection(Env env) {
    var p = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var messages = new ArrayList<Component>();
    var targets = new SurfaceInputTargets();
    var tokens = new ChatInputTokens(p, 1, targets, System::nanoTime);
    var s =
        new ChatDialogueSurface(
            new ConnectionGuard(p, 1, p.getInstance()),
            targets,
            tokens,
            c -> {
              messages.add(c);
              return CompletableFuture.completedFuture(null);
            },
            UiLabelSource.defaults(),
            DialoguePresenter.Controls.defaults(),
            "/testinput");
    var h =
        s.open(
                new DialogueSurface.SurfaceContext(
                    RenderFixtures.SESSION,
                    p.getUuid().toString(),
                    1,
                    "chat",
                    "semantic",
                    new CancellationToken(),
                    1,
                    Long.MAX_VALUE))
            .toCompletableFuture()
            .join();
    for (int i = 0; i < 10; i++) {
      var v = RenderFixtures.view("Hello there", i);
      h.replace(new WidgetFrame(v, List.of(), "semantic", "semantic")).toCompletableFuture().join();
    }
    assertEquals(1, messages.size());
    assertTrue(
        PlainTextComponentSerializer.plainText()
            .serialize(messages.getFirst())
            .contains("Hello there"));
    h.closeAsync().toCompletableFuture().join();
    assertNull(targets.current());
  }

  @Test
  void closeWaitsForPendingTransport(Env env) {
    var p = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var targets = new SurfaceInputTargets();
    var gate = new CompletableFuture<Void>();
    var tokens = new ChatInputTokens(p, 1, targets, System::nanoTime);
    var s =
        new ChatDialogueSurface(
            new ConnectionGuard(p, 1, p.getInstance()),
            targets,
            tokens,
            c -> gate,
            UiLabelSource.defaults(),
            DialoguePresenter.Controls.defaults(),
            "/testinput");
    var h =
        s.open(
                new DialogueSurface.SurfaceContext(
                    RenderFixtures.SESSION,
                    p.getUuid().toString(),
                    1,
                    "chat",
                    "semantic",
                    new CancellationToken(),
                    1,
                    Long.MAX_VALUE))
            .toCompletableFuture()
            .join();
    var write =
        h.replace(
            new WidgetFrame(RenderFixtures.view("Hello", 5), List.of(), "semantic", "semantic"));
    var close = h.closeAsync();
    assertFalse(close.toCompletableFuture().isDone());
    gate.complete(null);
    close.toCompletableFuture().join();
    assertTrue(write.toCompletableFuture().isCompletedExceptionally());
    assertNull(targets.current());
  }

  @Test
  void staleAndForeignTokensCannotConfirm(Env env) {
    var instance = env.createFlatInstance();
    var a = env.createConnection().connect(instance, new Pos(0, 42, 0));
    var b = env.createConnection().connect(instance, new Pos(0, 42, 0));
    var now = new AtomicLong();
    var targets = new SurfaceInputTargets();
    var f = RenderFixtures.frame("hud", "Hello", 5);
    targets.publish(f, 1, Map.of("ask", new WidgetFrame.Rect(0, 0, 1, 1)));
    var t = new ChatInputTokens(a, 1, targets, now::get);
    var token = t.issue("ask", targets.current());
    assertTrue(token.matches("[A-Za-z0-9_-]{22}"));
    assertTrue(t.resolve(b, 1, token).isEmpty());
    assertTrue(t.resolve(a, 2, token).isEmpty());
    assertEquals("ask", t.resolve(a, 1, token).orElseThrow().choiceId());
    now.set(30_000_000_001L);
    assertTrue(t.resolve(a, 1, token).isEmpty());
    var token2 = t.issue("ask", targets.current());
    targets.clear(1);
    assertTrue(t.resolve(a, 1, token2).isEmpty());
  }

  @Test
  void sevenChoicesRemainReachable(Env env) {
    var p = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var targets = new SurfaceInputTargets();
    var t = new ChatInputTokens(p, 1, targets, System::nanoTime);
    var out = new ArrayList<Component>();
    var s =
        new ChatDialogueSurface(
            new ConnectionGuard(p, 1, p.getInstance()),
            targets,
            t,
            c -> {
              out.add(c);
              return CompletableFuture.completedFuture(null);
            },
            UiLabelSource.defaults(),
            DialoguePresenter.Controls.defaults(),
            "/testinput");
    var h =
        s.open(
                new DialogueSurface.SurfaceContext(
                    RenderFixtures.SESSION,
                    p.getUuid().toString(),
                    1,
                    "chat",
                    "semantic",
                    new CancellationToken(),
                    1,
                    Long.MAX_VALUE))
            .toCompletableFuture()
            .join();
    var v = RenderFixtures.view("Hello", 5);
    var choices = new ArrayList<DialogueView.ChoiceView>();
    for (int i = 0; i < 7; i++)
      choices.add(
          new DialogueView.ChoiceView(
              "c" + i, "Choice " + i, DialogueView.Availability.AVAILABLE, ""));
    var next =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            DialogueView.State.WAITING_FOR_INPUT,
            2,
            2,
            v.localeTag(),
            v.speaker(),
            v.text(),
            choices,
            "c0",
            5,
            5,
            DialogueView.HintPhase.CHOOSE,
            "",
            null);
    h.replace(new WidgetFrame(next, List.of(), "semantic", "semantic"))
        .toCompletableFuture()
        .join();
    assertEquals(7, targets.current().choices().size());
    var plain = PlainTextComponentSerializer.plainText().serialize(out.getFirst());
    for (int i = 0; i < 7; i++) assertTrue(plain.contains("Choice " + i));
    h.closeAsync().toCompletableFuture().join();
  }

  @Test
  void sixtyFourChoicesFitTokenBudget(Env env) {
    var p = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var targets = new SurfaceInputTargets();
    var t = new ChatInputTokens(p, 1, targets, System::nanoTime);
    var out = new ArrayList<Component>();
    var s =
        new ChatDialogueSurface(
            new ConnectionGuard(p, 1, p.getInstance()),
            targets,
            t,
            c -> {
              out.add(c);
              return CompletableFuture.completedFuture(null);
            },
            UiLabelSource.defaults(),
            DialoguePresenter.Controls.defaults(),
            "/testinput");
    var h =
        s.open(
                new DialogueSurface.SurfaceContext(
                    RenderFixtures.SESSION,
                    p.getUuid().toString(),
                    1,
                    "chat",
                    "semantic",
                    new CancellationToken(),
                    1,
                    Long.MAX_VALUE))
            .toCompletableFuture()
            .join();
    var v = RenderFixtures.view("Hello", 5);
    var choices = new ArrayList<DialogueView.ChoiceView>();
    for (int i = 0; i < 64; i++)
      choices.add(
          new DialogueView.ChoiceView(
              "c" + i, "Choice " + i, DialogueView.Availability.AVAILABLE, ""));
    var next =
        new DialogueView(
            v.sessionId(),
            v.contentPin(),
            v.nodeId(),
            v.nodeVisitId(),
            DialogueView.State.WAITING_FOR_INPUT,
            2,
            2,
            v.localeTag(),
            v.speaker(),
            v.text(),
            choices,
            "c0",
            5,
            5,
            DialogueView.HintPhase.CHOOSE,
            "",
            null);
    h.replace(new WidgetFrame(next, List.of(), "semantic", "semantic"))
        .toCompletableFuture()
        .join();
    assertEquals(64, targets.current().choices().size());
    var plain = PlainTextComponentSerializer.plainText().serialize(out.getFirst());
    for (int i = 0; i < 64; i++) assertTrue(plain.contains("Choice " + i));
    h.closeAsync().toCompletableFuture().join();
  }
}
