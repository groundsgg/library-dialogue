package gg.grounds.dialogue.minestom.render;

import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.internal.ConnectionGuard;
import gg.grounds.dialogue.presentation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Semantic, complete sections with plain text and server-issued click capabilities. */
public final class ChatDialogueSurface implements DialogueSurface {
  private final ConnectionGuard guard;
  private final SurfaceInputTargets targets;
  private final ChatInputTokens tokens;
  private final Function<Component, CompletionStage<Void>> sink;
  private final UiLabelSource labels;
  private final DialoguePresenter.Controls controls;
  private final String route;

  public ChatDialogueSurface(
      ConnectionGuard guard,
      SurfaceInputTargets targets,
      ChatInputTokens tokens,
      Function<Component, CompletionStage<Void>> sink,
      UiLabelSource labels,
      DialoguePresenter.Controls controls,
      String route) {
    this.guard = guard;
    this.targets = targets;
    this.tokens = tokens;
    this.sink = sink;
    this.labels = labels;
    this.controls = controls;
    if (!route.matches("/[a-z0-9_]+")) throw new IllegalArgumentException("Invalid route");
    this.route = route;
  }

  public CompletionStage<SurfaceHandle> open(SurfaceContext context) {
    return CompletableFuture.completedFuture(
        new SurfaceHandle() {
          volatile boolean closed;
          final List<CompletableFuture<Void>> writes = new ArrayList<>();
          CompletionStage<Void> closing;
          Object last;
          volatile long epoch;

          public CompletionStage<Void> replace(WidgetFrame frame) {
            if (closed || !guard.isCurrent() || context.cancellation().isCancelled())
              return CompletableFuture.failedFuture(new CancellationException());
            var v = frame.semantic();
            var key =
                List.of(
                    v.sessionId(),
                    v.nodeVisitId(),
                    v.localeTag(),
                    v.speaker(),
                    v.text(),
                    v.choices(),
                    v.hint(),
                    v.notice());
            var choices = new LinkedHashMap<String, WidgetFrame.Rect>();
            v.choices().stream()
                .filter(c -> c.availability() == DialogueView.Availability.AVAILABLE)
                .forEach(c -> choices.put(c.id(), new WidgetFrame.Rect(0, 0, 0, 0)));
            var snapshot =
                new TargetSnapshot(
                    v.sessionId(),
                    v.renderRevision(),
                    v.nodeVisitId(),
                    context.generation(),
                    choices);
            if (key.equals(last)) {
              targets.publish(frame, context.generation(), choices);
              tokens.rebase(snapshot);
              return CompletableFuture.completedFuture(null);
            }
            tokens.clear();
            targets.invalidateCurrent(context.generation());
            long operation = ++epoch;
            var reasons = new LinkedHashMap<String, String>();
            v.choices().stream()
                .filter(c -> c.availability() != DialogueView.Availability.AVAILABLE)
                .forEach(c -> reasons.put(c.id(), c.unavailableReason()));
            var ui =
                labels.resolve(
                    new UiLabelSource.LabelRequest(
                        v.localeTag(), v.hint(), controls, v.notice(), reasons));
            Component out =
                Component.text(v.speaker(), NamedTextColor.GOLD)
                    .appendNewline()
                    .append(Component.text(v.text(), NamedTextColor.WHITE));
            for (var c : v.choices()) {
              Component row =
                  Component.text(
                      c.label(),
                      c.availability() == DialogueView.Availability.AVAILABLE
                          ? NamedTextColor.YELLOW
                          : NamedTextColor.GRAY);
              if (c.availability() == DialogueView.Availability.AVAILABLE
                  && v.state() != DialogueView.State.WAITING_FOR_PROVIDER)
                row =
                    row.clickEvent(
                        ClickEvent.runCommand(route + " " + tokens.issue(c.id(), snapshot)));
              else if (c.availability() != DialogueView.Availability.AVAILABLE)
                row =
                    row.append(Component.text(" · " + ui.choiceReasons().getOrDefault(c.id(), "")));
              out = out.appendNewline().append(row);
            }
            Component hint = Component.text(ui.hint(), NamedTextColor.GRAY);
            if (v.hint() != DialogueView.HintPhase.BUSY)
              hint =
                  hint.clickEvent(
                      ClickEvent.runCommand(route + " " + tokens.issue(null, snapshot)));
            out = out.appendNewline().append(hint);
            if (!ui.notice().isEmpty())
              out = out.appendNewline().append(Component.text(ui.notice(), NamedTextColor.GRAY));
            if (GsonComponentSerializer.gson()
                    .serialize(out)
                    .getBytes(StandardCharsets.UTF_8)
                    .length
                > 65536)
              return CompletableFuture.failedFuture(new IllegalArgumentException("FRAME_BUDGET"));
            var promise = new CompletableFuture<Void>();
            var transport = new CompletableFuture<Void>();
            writes.removeIf(CompletableFuture::isDone);
            writes.add(transport);
            try {
              sink.apply(out)
                  .whenComplete(
                      (unused, error) -> {
                        if (error == null) transport.complete(null);
                        else transport.completeExceptionally(error);
                        if (error != null) promise.completeExceptionally(error);
                        else if (closed
                            || epoch != operation
                            || !guard.isCurrent()
                            || context.cancellation().isCancelled())
                          promise.completeExceptionally(new CancellationException());
                        else {
                          last = key;
                          targets.publish(frame, context.generation(), choices);
                          promise.complete(null);
                        }
                      });
            } catch (Throwable error) {
              transport.completeExceptionally(error);
              promise.completeExceptionally(error);
            }
            return promise.minimalCompletionStage();
          }

          public CompletionStage<Void> closeAsync() {
            if (closing != null) return closing;
            closed = true;
            epoch++;
            tokens.clear();
            targets.clear(context.generation());
            closing =
                CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                    .minimalCompletionStage();
            return closing;
          }
        });
  }
}
