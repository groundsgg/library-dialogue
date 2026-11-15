package gg.grounds.dialogue.presentation;

import gg.grounds.dialogue.core.internal.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.ContentFingerprint;
import gg.grounds.dialogue.presentation.WidgetFrame.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Shared widget composition; does not own a second reveal clock or graph. */
public final class DialoguePresenter implements DialogueRenderer {
  public record SurfaceProfile(
      DialogueSurface surface,
      LayoutProfile layout,
      DialogueTheme theme,
      FontMetrics metrics,
      boolean semanticOnly,
      java.util.function.Function<PresentationPreferences, LayoutProfile> layoutResolver) {
    public SurfaceProfile(
        DialogueSurface surface,
        LayoutProfile layout,
        DialogueTheme theme,
        FontMetrics metrics,
        boolean semanticOnly) {
      this(surface, layout, theme, metrics, semanticOnly, null);
    }

    public LayoutProfile resolveLayout(PresentationPreferences preferences) {
      return layoutResolver == null
          ? layout
          : Objects.requireNonNull(layoutResolver.apply(preferences));
    }

    public SurfaceProfile {
      Objects.requireNonNull(surface);
      if (!semanticOnly) {
        Objects.requireNonNull(layout);
        Objects.requireNonNull(theme);
        Objects.requireNonNull(metrics);
      }
    }

    public static SurfaceProfile semantic(DialogueSurface surface) {
      return new SurfaceProfile(surface, null, null, null, true);
    }
  }

  public enum ConfirmAction {
    ATTACK,
    USE,
    SWAP_HANDS
  }

  public record Controls(ConfirmAction confirm, boolean scroll) {
    public Controls {
      Objects.requireNonNull(confirm);
    }

    public static Controls defaults() {
      return new Controls(ConfirmAction.SWAP_HANDS, true);
    }
  }

  public static final class FallbackRequired extends DialogueFailure {
    public FallbackRequired(String code) {
      super(code, code);
    }
  }

  private final Map<String, SurfaceProfile> profiles;
  private final DialogueScheduler clock;
  private final Controls controls;
  private final UiLabelSource labels;

  public DialoguePresenter(Map<String, SurfaceProfile> profiles, DialogueScheduler scheduler) {
    this(profiles, scheduler, Controls.defaults());
  }

  public DialoguePresenter(
      Map<String, SurfaceProfile> profiles, DialogueScheduler scheduler, Controls controls) {
    this(profiles, scheduler, controls, UiLabelSource.defaults());
  }

  public DialoguePresenter(
      Map<String, SurfaceProfile> profiles,
      DialogueScheduler scheduler,
      Controls controls,
      UiLabelSource labels) {
    this.profiles = Map.copyOf(profiles);
    clock = Objects.requireNonNull(scheduler);
    this.controls = Objects.requireNonNull(controls);
    this.labels = Objects.requireNonNull(labels);
  }

  private SurfaceProfile profile(String key) {
    var p = profiles.get(key);
    if (p == null) throw new FallbackRequired("MISSING_SURFACE");
    return p;
  }

  private static <T> CompletionStage<T> call(Supplier<CompletionStage<T>> supplier) {
    try {
      return Objects.requireNonNull(supplier.get());
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  public WidgetFrame compose(DialogueView view, String key, PresentationPreferences preferences) {
    var p = profile(key);
    if (p.semanticOnly()) return new WidgetFrame(view, List.of(), "semantic", "semantic");
    var l = p.resolveLayout(preferences);
    var metrics = p.metrics();
    var widgets = new ArrayList<Widget>();
    var reasons = new LinkedHashMap<String, String>();
    view.choices().stream()
        .filter(c -> c.availability() != DialogueView.Availability.AVAILABLE)
        .forEach(c -> reasons.put(c.id(), c.unavailableReason()));
    var ui =
        Objects.requireNonNull(
            labels.resolve(
                new UiLabelSource.LabelRequest(
                    view.localeTag(), view.hint(), controls, view.notice(), reasons)));
    if (l.style() != LayoutProfile.Style.PANELS)
      return composePrototypeHud(view, p, l, preferences, ui);
    var text = new ResolvedText(view.text(), view.localeTag());
    var body = layout(text, metrics, l.width() - 2 * l.padding(), l.maxTextLines());
    double bodyHeight = body.height() + 2 * l.padding();
    widgets.add(
        widget(
            "text",
            Kind.TEXT,
            new Rect(0, 0, l.width(), bodyHeight),
            text,
            body,
            l.padding(),
            l.padding(),
            view.revealedGraphemes(),
            false,
            true,
            null,
            p,
            preferences));
    var name = new ResolvedText(view.speaker(), view.localeTag());
    var nameLayout = layout(name, metrics, l.width() - 2 * l.padding(), 1);
    double nameWidth = Math.max(nameLayout.width() + 2 * l.padding(), 40);
    double nameHeight = metrics.lineHeight() + l.padding();
    double nameX = (l.width() - nameWidth) / 2, nameY = -nameHeight - l.gap();
    widgets.add(
        widget(
            "speaker",
            Kind.SPEAKER,
            new Rect(nameX, nameY, nameWidth, nameHeight),
            name,
            nameLayout,
            (nameWidth - nameLayout.width()) / 2,
            (nameHeight - nameLayout.height()) / 2,
            Graphemes.split(name.plain()).size(),
            false,
            true,
            null,
            p,
            preferences));
    var window =
        new ChoiceViewport().window(view.choices(), view.selectedChoiceId(), l.choiceCapacity());
    var labels =
        window.choices().stream()
            .map(
                c -> {
                  String reason =
                      c.availability() == DialogueView.Availability.AVAILABLE
                          ? ""
                          : ui.choiceReasons().getOrDefault(c.id(), "");
                  return new ResolvedText(
                      c.label() + (reason.isEmpty() ? "" : "\n" + reason), view.localeTag());
                })
            .toList();
    var layouts =
        labels.stream()
            .map(
                t ->
                    layout(t, metrics, l.choiceWidth() - 2 * l.choicePadding(), l.maxChoiceLines()))
            .toList();
    double cardHeight =
        layouts.stream()
                .mapToDouble(TextLayout.TextLayoutResult::height)
                .max()
                .orElse(metrics.lineHeight())
            + 2 * l.choicePadding();
    double growth = Math.ceil(l.choiceWidth() * (p.theme().selectedScale() - 1) / 2);
    double columnGap = l.columnGap() + growth;
    double x =
        switch (l.placement()) {
          case BELOW -> (l.width() - l.choiceWidth()) / 2;
          case RIGHT -> l.width() + columnGap;
          case LEFT -> -l.choiceWidth() - columnGap;
        };
    double y = l.placement() != LayoutProfile.ChoicePlacement.BELOW ? 0 : bodyHeight + l.gap();
    for (int i = 0; i < window.choices().size(); i++) {
      var c = window.choices().get(i);
      var bounds = new Rect(x, y + i * (cardHeight + l.gap()), l.choiceWidth(), cardHeight);
      widgets.add(
          widget(
              "choice:" + c.id(),
              Kind.CHOICE,
              bounds,
              labels.get(i),
              layouts.get(i),
              l.choicePadding(),
              (cardHeight - layouts.get(i).height()) / 2,
              Graphemes.split(labels.get(i).plain()).size(),
              c.id().equals(view.selectedChoiceId()),
              c.availability() == DialogueView.Availability.AVAILABLE,
              c.id(),
              p,
              preferences));
    }
    String hint = ui.hint();
    var hintText = new ResolvedText(hint, view.localeTag());
    double hintWidth =
        l.placement() != LayoutProfile.ChoicePlacement.BELOW ? l.choiceWidth() : l.width();
    var hintLayout = layout(hintText, metrics, hintWidth, 2);
    double hintX = l.placement() != LayoutProfile.ChoicePlacement.BELOW ? x : 0;
    double hintY =
        l.placement() != LayoutProfile.ChoicePlacement.BELOW
            ? -hintLayout.height() - l.gap()
            : y + window.choices().size() * (cardHeight + l.gap()) + l.gap();
    widgets.add(
        widget(
            "hint",
            Kind.HINT,
            new Rect(hintX, hintY, hintWidth, hintLayout.height()),
            hintText,
            hintLayout,
            0,
            0,
            Graphemes.split(hint).size(),
            false,
            true,
            null,
            p,
            preferences));
    if (!ui.notice().isEmpty()) {
      var statusText = new ResolvedText(ui.notice(), view.localeTag());
      var statusLayout = layout(statusText, metrics, hintWidth, 2);
      double statusY = hintY + hintLayout.height() + l.gap();
      if (l.placement() != LayoutProfile.ChoicePlacement.BELOW)
        statusY = y + window.choices().size() * (cardHeight + l.gap()) + l.gap();
      widgets.add(
          widget(
              "status",
              Kind.HINT,
              new Rect(hintX, statusY, hintWidth, statusLayout.height()),
              statusText,
              statusLayout,
              0,
              0,
              Graphemes.split(statusText.plain()).size(),
              false,
              true,
              null,
              p,
              preferences));
    }
    WidgetFrame.Scrollbar scroll =
        window.total() > l.choiceCapacity()
            ? scrollbar(
                window,
                new Rect(
                    x + l.choiceWidth() + growth + 8,
                    y,
                    2,
                    l.choiceCapacity() * cardHeight + (l.choiceCapacity() - 1) * l.gap()))
            : null;
    return new WidgetFrame(view, widgets, metrics.pin(), p.theme(), null, scroll);
  }

  private static WidgetFrame composePrototypeHud(
      DialogueView v,
      SurfaceProfile p,
      LayoutProfile l,
      PresentationPreferences preferences,
      UiLabelSource.UiLabels ui) {
    var metrics = p.metrics();
    if (metrics.lineHeight() != 10
        || l.choiceCapacity() != 3
        || l.maxTextLines() > 3
        || l.maxChoiceLines() > 2) throw new FallbackRequired("UNSUPPORTED_RASTER");
    var widgets = new ArrayList<Widget>();
    var body = new ResolvedText(v.text(), v.localeTag());
    var bodyLayout = layout(body, metrics, l.width() - 18, 3);
    widgets.add(
        widget(
            "text",
            Kind.TEXT,
            new Rect(0, 0, l.width(), 48),
            body,
            bodyLayout,
            9,
            17,
            v.revealedGraphemes(),
            false,
            true,
            null,
            p,
            preferences));
    var name = new ResolvedText(v.speaker(), v.localeTag());
    var nameLayout = layout(name, metrics, l.width() - 18, 1);
    widgets.add(
        widget(
            "speaker",
            Kind.SPEAKER,
            new Rect(9, 7, l.width() - 18, 10),
            name,
            nameLayout,
            0,
            0,
            Graphemes.split(name.plain()).size(),
            false,
            true,
            null,
            p,
            preferences));
    var window = new ChoiceViewport().window(v.choices(), v.selectedChoiceId(), 3);
    var labels = new ArrayList<ResolvedText>();
    var lines = new ArrayList<TextLayout.TextLayoutResult>();
    for (var c : window.choices()) {
      String reason =
          c.availability() == DialogueView.Availability.AVAILABLE
              ? ""
              : ui.choiceReasons().getOrDefault(c.id(), "");
      String prefix = c.id().equals(v.selectedChoiceId()) ? "> " : "  ";
      var text =
          new ResolvedText(
              prefix + c.label() + (reason.isEmpty() ? "" : "\n  " + reason), v.localeTag());
      labels.add(text);
      lines.add(layout(text, metrics, l.choiceWidth(), 2));
    }
    double rowHeight =
        lines.stream().mapToDouble(TextLayout.TextLayoutResult::height).max().orElse(10);
    boolean below = l.placement() == LayoutProfile.ChoicePlacement.BELOW;
    boolean shared = l.style() == LayoutProfile.Style.HUD;
    boolean cards = l.style() == LayoutProfile.Style.HUD_CARDS;
    int count = window.choices().size();
    if (cards) {
      // Reserve the largest offered answer, including its selection marker, across all pages.
      for (var c : v.choices()) {
        String reason =
            c.availability() == DialogueView.Availability.AVAILABLE
                ? ""
                : ui.choiceReasons().getOrDefault(c.id(), "");
        var label =
            new ResolvedText(
                "> " + c.label() + (reason.isEmpty() ? "" : "\n  " + reason), v.localeTag());
        rowHeight = Math.max(rowHeight, layout(label, metrics, l.choiceWidth(), 2).height());
      }
    }
    double gap = cards && l.placement() == LayoutProfile.ChoicePlacement.LEFT ? 15 : l.gap();
    double columnWidth = l.choiceWidth() + 18;
    double left = l.placement() == LayoutProfile.ChoicePlacement.LEFT ? -columnWidth - gap : 0;
    double width = below ? l.width() : l.width() + gap + columnWidth;
    if (cards && l.placement() != LayoutProfile.ChoicePlacement.LEFT) width += 9;
    double step = cards ? rowHeight + 13 : rowHeight;
    double answerHeight = cards ? 3 * (rowHeight + 10) + 6 : 3 * rowHeight + 14;
    double height =
        shared
            ? (below ? 84 + 3 * (rowHeight - 10) : rowHeight == 10 ? 51 : 75)
            : below ? 54 + answerHeight : Math.max(48, answerHeight);
    double x =
        switch (l.placement()) {
          case BELOW -> 9;
          case RIGHT -> l.width() + gap + 9;
          case LEFT -> left + 9;
        };
    double y = below ? (shared ? 50 : 61) : 7;
    for (int n = 0; n < window.choices().size(); n++) {
      var c = window.choices().get(n);
      var text = labels.get(n);
      var bounds =
          cards
              ? new Rect(x - 9, y - 7 + n * step, l.choiceWidth() + 18, rowHeight + 10)
              : new Rect(x, y + n * step, l.choiceWidth(), rowHeight);
      widgets.add(
          widget(
              "choice:" + c.id(),
              Kind.CHOICE,
              bounds,
              text,
              lines.get(n),
              cards ? 9 : 0,
              cards ? (bounds.height() - lines.get(n).height()) / 2 : 0,
              Graphemes.split(text.plain()).size(),
              c.id().equals(v.selectedChoiceId()),
              c.availability() == DialogueView.Availability.AVAILABLE,
              c.id(),
              p,
              preferences));
    }
    var hint = new ResolvedText(ui.hint(), v.localeTag());
    var hintLayout = layout(hint, metrics, width - 18, 2);
    widgets.add(
        widget(
            "hint",
            Kind.HINT,
            new Rect(left + 9, height - 1, width - 18, hintLayout.height()),
            hint,
            hintLayout,
            0,
            0,
            Graphemes.split(hint.plain()).size(),
            false,
            true,
            null,
            p,
            preferences));
    if (!ui.notice().isEmpty()) {
      var notice = new ResolvedText(ui.notice(), v.localeTag());
      var noticeLayout = layout(notice, metrics, width - 18, 2);
      widgets.add(
          widget(
              "status",
              Kind.HINT,
              new Rect(
                  left + 9,
                  height - 1 + hintLayout.height() + 4,
                  width - 18,
                  noticeLayout.height()),
              notice,
              noticeLayout,
              0,
              0,
              Graphemes.split(notice.plain()).size(),
              false,
              true,
              null,
              p,
              preferences));
    }
    var chrome =
        new WidgetFrame.Chrome(
            (shared ? "hud_" : cards ? "hud_cards_" : "hud_split_")
                + l.placement().name().toLowerCase(Locale.ROOT)
                + "_"
                + (int) rowHeight
                + "_"
                + (cards ? count : 3),
            new Rect(left, 0, width, height),
            height + 10);
    WidgetFrame.Scrollbar scrollbar = null;
    if (window.total() > l.choiceCapacity()) {
      double trackHeight = cards ? answerHeight : 3 * rowHeight;
      double top = cards ? y - 7 : y;
      var track = new Rect(x + l.choiceWidth() + (cards ? 14 : 3), top, 2, trackHeight);
      scrollbar = scrollbar(window, track);
    }
    return new WidgetFrame(v, widgets, metrics.pin(), p.theme(), chrome, scrollbar);
  }

  private static WidgetFrame.Scrollbar scrollbar(ChoiceViewport.ChoiceWindow window, Rect track) {
    double thumbHeight =
        Math.max(6, Math.floor(track.height() * window.choices().size() / window.total()));
    double offset =
        window.start() + window.choices().size() == window.total()
            ? track.height() - thumbHeight
            : Math.min(
                track.height() - thumbHeight,
                Math.round(track.height() * window.start() / window.total()));
    return new WidgetFrame.Scrollbar(
        track, new Rect(track.x(), track.y() + offset, track.width(), thumbHeight));
  }

  private static TextLayout.TextLayoutResult layout(
      ResolvedText text, FontMetrics metrics, double width, int lines) {
    var result = new TextLayout().layout(text, metrics, new TextLayout.TextLimits(width, lines));
    if (!result.isValid()) throw new FallbackRequired(result.problem());
    return result;
  }

  private static Widget widget(
      String id,
      Kind kind,
      Rect bounds,
      ResolvedText text,
      TextLayout.TextLayoutResult layout,
      double dx,
      double dy,
      int revealed,
      boolean selected,
      boolean enabled,
      String choiceId,
      SurfaceProfile p,
      PresentationPreferences preferences) {
    return new Widget(
        id,
        kind,
        bounds,
        new Rect(bounds.x() + dx, bounds.y() + dy, layout.width(), layout.height()),
        text,
        layout,
        revealed,
        selected,
        enabled,
        selected ? p.theme().selectedScale() : 1,
        preferences.reducedMotion() ? Duration.ZERO : p.theme().transition(),
        choiceId);
  }

  public CompletionStage<PresentationHandle> prepare(PresentationRequest request) {
    var session = new SurfaceSession(request);
    session.begin();
    return session.ready.minimalCompletionStage();
  }

  private static String profilePin(SurfaceProfile profile, PresentationPreferences preferences) {
    if (profile.semanticOnly()) return "semantic";
    var l = profile.resolveLayout(preferences);
    var t = profile.theme();
    var layout = new LinkedHashMap<String, Object>();
    layout.put("key", l.key());
    if (l.style() != LayoutProfile.Style.PANELS) layout.put("style", l.style().name());
    layout.put("width", l.width());
    layout.put("choiceWidth", l.choiceWidth());
    layout.put("padding", l.padding());
    // Preserve existing profile pins when choices retain the legacy shared padding.
    if (l.choicePadding() != l.padding()) layout.put("choicePadding", l.choicePadding());
    layout.put("maxTextLines", l.maxTextLines());
    layout.put("choiceCapacity", l.choiceCapacity());
    layout.put("maxChoiceLines", l.maxChoiceLines());
    layout.put("placement", l.placement().name().toLowerCase(Locale.ROOT));
    layout.put("gap", l.gap());
    if (l.columnGap() != l.gap()) layout.put("columnGap", l.columnGap());
    return ContentFingerprint.hash(
            ContentFingerprint.canonicalBytes(
                Map.of(
                    "layout",
                    layout,
                    "theme",
                    Map.of(
                        "id",
                        t.id(),
                        "colours",
                        t.colours(),
                        "selectedScale",
                        t.selectedScale(),
                        "transitionNanos",
                        t.transition().toNanos()),
                    "font",
                    profile.metrics().pin())))
        .sha256();
  }

  private final class SurfaceSession implements PresentationHandle {
    final PresentationRequest request;
    final SerialCommandQueue queue;
    final OwnedScope scope;
    final CompletableFuture<PresentationHandle> ready = new CompletableFuture<>();
    final AtomicBoolean closed = new AtomicBoolean();
    final List<CompletableFuture<Void>> outstanding = new ArrayList<>();
    final List<PendingOperation<?>> waits = new ArrayList<>();
    CompletableFuture<Void> closeResult;
    CompletionStage<Void> closeView;
    Throwable preparationFailure;
    PresentationPreferences preferences;
    DialogueSurface.SurfaceHandle surface;
    String surfaceKey;
    DialogueView lastView;
    long generation, outputGeneration, lastWritten = -1;
    PendingFrame pending, inflight;
    Switch changing;

    final class PendingFrame {
      final DialogueView view;
      final List<CompletableFuture<Void>> waiters = new ArrayList<>();

      PendingFrame(DialogueView view) {
        this.view = view;
      }
    }

    final class Switch {
      final long gen, deadline;
      final PresentationPreferences next;
      final CompletableFuture<Void> result;
      final CompletableFuture<Void> work = new CompletableFuture<>();
      DialogueSurface.SurfaceHandle candidate;
      boolean committed;

      Switch(
          long gen, long deadline, PresentationPreferences next, CompletableFuture<Void> result) {
        this.gen = gen;
        this.deadline = deadline;
        this.next = next;
        this.result = result;
      }
    }

    SurfaceSession(PresentationRequest request) {
      this.request = request;
      preferences = request.preferences();
      surfaceKey = preferences.surfaceKey();
      queue =
          new SerialCommandQueue(
              clock,
              error -> {
                preparationFailure = error;
                closeAsync();
              });
      scope = new OwnedScope(queue, clock, request.budgets().cleanup());
    }

    boolean valid(Switch op) {
      return !closed.get()
          && op == changing
          && op.gen == generation
          && !op.result.isDone()
          && clock.nanoTime() < op.deadline;
    }

    void begin() {
      queue.execute(
          () -> {
            if (request.cancellation().isCancelled()) {
              closeAsync();
              return;
            }
            scope.prepare(
                () ->
                    CompletableFuture.completedFuture(
                        request.cancellation().onCancel(reason -> closeAsync())));
            if (closed.get()) return;
            var opened = open(surfaceKey, ++generation, request.deadlineNanos(), preferences);
            waits.add(
                new PendingOperation<>(
                    queue,
                    clock,
                    remaining(request.deadlineNanos()),
                    opened,
                    (value, error) -> {
                      if (closed.get()) return;
                      if (error != null) {
                        preparationFailure = error;
                        closeAsync();
                      } else {
                        surface = value;
                        ready.complete(this);
                      }
                    }));
          });
    }

    Duration remaining(long deadline) {
      return Duration.ofNanos(Math.max(1, deadline - clock.nanoTime()));
    }

    CompletionStage<DialogueSurface.SurfaceHandle> open(
        String key, long gen, long deadline, PresentationPreferences openingPreferences) {
      return scope.prepare(
          () -> {
            var p = profile(key);
            return p.surface()
                .open(
                    new DialogueSurface.SurfaceContext(
                        request.sessionId(),
                        request.audienceId(),
                        request.hostGeneration(),
                        key,
                        profilePin(p, openingPreferences),
                        request.cancellation(),
                        gen,
                        deadline,
                        openingPreferences,
                        request.budgets().cleanup()));
          });
    }

    void track(CompletableFuture<Void> result) {
      outstanding.removeIf(CompletableFuture::isDone);
      outstanding.add(result);
    }

    public CompletionStage<Void> replace(DialogueView view) {
      var result = new CompletableFuture<Void>();
      queue.execute(
          () -> {
            if (closed.get()) {
              result.completeExceptionally(new CancellationException("Surface closed"));
              return;
            }
            if (!request.sessionId().equals(view.sessionId())) {
              result.completeExceptionally(new IllegalArgumentException("Foreign session view"));
              return;
            }
            track(result);
            if (lastView != null && view.stateRevision() < lastView.stateRevision()) {
              if (lastWritten >= view.stateRevision()) {
                result.complete(null);
                return;
              }
              if (pending != null) pending.waiters.add(result);
              else if (inflight != null) inflight.waiters.add(result);
              else enqueue(lastView, List.of(result));
              return;
            }
            lastView = view;
            enqueue(view, List.of(result));
          });
      return result.minimalCompletionStage();
    }

    void enqueue(DialogueView view, List<CompletableFuture<Void>> waiters) {
      var frame = new PendingFrame(view);
      if (pending != null) frame.waiters.addAll(pending.waiters);
      frame.waiters.addAll(waiters);
      pending = frame;
      flush();
    }

    void finishFrame(PendingFrame frame, Throwable error) {
      for (var waiter : frame.waiters) {
        if (error == null) waiter.complete(null);
        else waiter.completeExceptionally(error);
      }
      frame.waiters.clear();
    }

    void flush() {
      if (closed.get() || inflight != null || pending == null || surface == null) return;
      var frame = pending;
      pending = null;
      WidgetFrame widgets;
      try {
        widgets = compose(frame.view, surfaceKey, preferences);
      } catch (Throwable error) {
        finishFrame(frame, error);
        flush();
        return;
      }
      inflight = frame;
      long gen = outputGeneration;
      var target = surface;
      call(() -> target.replace(widgets))
          .whenComplete(
              (v, error) ->
                  queue.execute(
                      () -> {
                        if (gen != outputGeneration) return;
                        if (error == null)
                          lastWritten = Math.max(lastWritten, frame.view.stateRevision());
                        finishFrame(frame, error);
                        inflight = null;
                        flush();
                      }));
    }

    void releaseCandidate(DialogueSurface.SurfaceHandle value) {
      if (value == null) return;
      scope
          .release(value)
          .whenComplete(
              (v, error) ->
                  queue.execute(
                      () -> {
                        if (error != null) {
                          preparationFailure = error;
                          request.cancellation().cancel(DialogueEndReason.FAILED);
                          closeAsync();
                        }
                      }));
    }

    void reject(Switch op, String code, Throwable error) {
      releaseCandidate(op.candidate);
      op.work.completeExceptionally(new ReconfigurationRejected(code, error, preferences));
    }

    public CompletionStage<Void> reconfigure(PresentationPreferences next) {
      Objects.requireNonNull(next);
      var result = new CompletableFuture<Void>();
      long deadline = clock.nanoTime() + request.budgets().prepare().toNanos();
      queue.execute(
          () -> {
            if (closed.get()) {
              result.completeExceptionally(new CancellationException("Surface closed"));
              return;
            }
            track(result);
            generation++;
            if (changing != null) {
              changing.result.completeExceptionally(
                  new ReconfigurationRejected(
                      "SURFACE_SUPERSEDED",
                      new CancellationException("Superseded surface"),
                      preferences));
              releaseCandidate(changing.committed ? null : changing.candidate);
              changing.work.completeExceptionally(new CancellationException("Superseded surface"));
              changing = null;
            }
            if (next.surfaceKey().equals(surfaceKey)) {
              long configureGeneration = generation;
              call(() -> surface.configure(next))
                  .whenComplete(
                      (v, error) ->
                          queue.execute(
                              () -> {
                                if (closed.get() || configureGeneration != generation) {
                                  result.completeExceptionally(
                                      new ReconfigurationRejected(
                                          "SURFACE_SUPERSEDED",
                                          new CancellationException(),
                                          preferences));
                                  return;
                                }
                                if (error != null) result.completeExceptionally(error);
                                else {
                                  preferences = next;
                                  result.complete(null);
                                }
                              }));
              return;
            }
            var op = new Switch(generation, deadline, next, result);
            changing = op;
            waits.removeIf(PendingOperation::isDone);
            waits.add(
                new PendingOperation<>(
                    queue,
                    clock,
                    remaining(deadline),
                    op.work,
                    (v, error) -> {
                      if (op != changing || closed.get()) return;
                      changing = null;
                      if (error != null) {
                        if (!op.committed) releaseCandidate(op.candidate);
                        Throwable cause = OwnedScope.unwrap(error);
                        if (!op.committed && !(cause instanceof ReconfigurationRejected))
                          cause =
                              new ReconfigurationRejected(
                                  "SURFACE_PREPARE_FAILED", cause, preferences);
                        result.completeExceptionally(cause);
                      } else result.complete(null);
                    }));
            open(next.surfaceKey(), op.gen, deadline, next)
                .whenComplete(
                    (value, error) ->
                        queue.execute(
                            () -> {
                              op.candidate = value;
                              if (!valid(op)) {
                                releaseCandidate(value);
                                op.work.completeExceptionally(
                                    new ReconfigurationRejected(
                                        "SURFACE_EXPIRED",
                                        new CancellationException("Surface request expired"),
                                        preferences));
                                return;
                              }
                              if (error != null) {
                                reject(op, "SURFACE_PREPARE_FAILED", error);
                                return;
                              }
                              WidgetFrame frame;
                              try {
                                frame =
                                    lastView == null
                                        ? null
                                        : compose(lastView, next.surfaceKey(), next);
                              } catch (Throwable e) {
                                reject(op, "SURFACE_LAYOUT_REJECTED", e);
                                return;
                              }
                              var first =
                                  frame == null
                                      ? CompletableFuture.<Void>completedFuture(null)
                                      : call(() -> value.replace(frame));
                              first.whenComplete(
                                  (v, drawError) ->
                                      queue.execute(
                                          () -> {
                                            if (!valid(op)) {
                                              releaseCandidate(value);
                                              op.work.completeExceptionally(
                                                  new ReconfigurationRejected(
                                                      "SURFACE_EXPIRED",
                                                      new CancellationException(
                                                          "Surface request expired"),
                                                      preferences));
                                              return;
                                            }
                                            if (drawError != null) {
                                              reject(op, "SURFACE_DRAW_REJECTED", drawError);
                                              return;
                                            }
                                            var old = surface;
                                            surface = value;
                                            surfaceKey = next.surfaceKey();
                                            preferences = next;
                                            op.committed = true;
                                            outputGeneration++;
                                            lastWritten =
                                                frame == null
                                                    ? -1
                                                    : frame.semantic().stateRevision();
                                            var transferred =
                                                new ArrayList<CompletableFuture<Void>>();
                                            if (inflight != null) {
                                              transferred.addAll(inflight.waiters);
                                              inflight.waiters.clear();
                                              inflight = null;
                                            }
                                            if (lastView != null) enqueue(lastView, transferred);
                                            scope
                                                .release(old)
                                                .whenComplete(
                                                    (released, cleanup) ->
                                                        queue.execute(
                                                            () -> {
                                                              if (cleanup != null) {
                                                                preparationFailure = cleanup;
                                                                op.work.completeExceptionally(
                                                                    new DialogueFailure(
                                                                        "SURFACE_CLEANUP_FAILED",
                                                                        cleanup));
                                                                request
                                                                    .cancellation()
                                                                    .cancel(
                                                                        DialogueEndReason.FAILED);
                                                                closeAsync();
                                                              } else op.work.complete(null);
                                                            }));
                                          }));
                            }));
          });
      return result.minimalCompletionStage();
    }

    public CompletionStage<Void> closeAsync() {
      synchronized (this) {
        if (closeResult != null) return closeView;
        closeResult = new CompletableFuture<>();
        closeView = closeResult.minimalCompletionStage();
        closed.set(true);
      }
      queue.execute(
          () -> {
            generation++;
            waits.forEach(PendingOperation::cancel);
            waits.clear();
            var error = new CancellationException("Surface closed");
            outstanding.forEach(f -> f.completeExceptionally(error));
            outstanding.clear();
            if (pending != null) {
              finishFrame(pending, error);
              pending = null;
            }
            if (inflight != null) {
              finishFrame(inflight, error);
              inflight = null;
            }
            changing = null;
            scope
                .closeAsync()
                .whenComplete(
                    (v, cleanup) ->
                        queue.execute(
                            () -> {
                              surface = null;
                              lastView = null;
                              Throwable failure =
                                  cleanup == null
                                      ? preparationFailure
                                      : new OwnedResource.PreparationFailure(cleanup);
                              ready.completeExceptionally(failure == null ? error : failure);
                              if (cleanup == null) closeResult.complete(null);
                              else closeResult.completeExceptionally(cleanup);
                            }));
          });
      return closeView;
    }
  }
}
