package gg.grounds.dialogue.core.internal;

import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.port.ActionHandler.*;
import gg.grounds.dialogue.core.port.ConditionProvider.*;
import gg.grounds.dialogue.core.port.OutcomeSink.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.runtime.DialogueView.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.ContentFingerprint;
import gg.grounds.dialogue.core.validation.ContentPin;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Serialized owner of graph state, input, reveal and resource lifetime. */
public final class SessionDriver implements DialogueHandle {
  private DialogueStart start;
  private final RuntimeOptions options;
  private final UUID id = UUID.randomUUID();
  private final ContentPin pin;
  private final SerialCommandQueue queue;
  private final OwnedScope scope;
  private final CompletableFuture<DialogueStartResult> started;
  private final CompletableFuture<DialogueEnd> ended = new CompletableFuture<>();
  private final CompletionStage<DialogueEnd> endObservation = ended.minimalCompletionStage();
  private final Object dispatchGate = new Object();
  private final AtomicReference<DialogueEndReason> stopping = new AtomicReference<>();
  private final AtomicInteger inputCount = new AtomicInteger();
  private final CancellationToken cancellation = new CancellationToken();
  private final List<PendingOperation<?>> waits = new ArrayList<>();
  private final Set<CompletableFuture<Void>> renderAcknowledgements = new HashSet<>(),
      preferenceResults = new HashSet<>();
  private Map<String, ResolvedText> resolvedChoiceLabels = Map.of();
  private final Map<UUID, ActionRef> pendingActions = new LinkedHashMap<>();
  private ActionRef pendingAction;
  private int automaticSteps;
  private boolean confirming;
  private CompletableFuture<InputResult> pendingConfirm;
  private volatile DialogueView view;
  private DialogueRenderer.PresentationHandle renderer;
  private ActorPort.ActorHandle actor;
  private boolean rendererReady,
      actorReady,
      actorSkipped,
      activated,
      closing,
      translating,
      switching;
  private String startupCode, nodeId;
  private long visit,
      renderRevision,
      stateRevision,
      textGeneration,
      lastImpulse,
      enteredNanos,
      preferenceGeneration,
      revealGeneration;
  private LocaleContext locale;
  private PresentationPreferences preferences;
  private DialogueScheduler.ScheduledTask revealTimer;
  private CompletableFuture<RefreshResult> localePending;

  public SessionDriver(
      DialogueStart start, RuntimeOptions options, CompletableFuture<DialogueStartResult> ready) {
    this.start = start;
    this.options = options;
    started = ready;
    pin = start.content().contentPin();
    nodeId = start.content().definition().entry();
    locale = effectiveLocale(start.locale());
    preferences = start.preferences();
    lastImpulse = start.openingImpulse();
    queue = new SerialCommandQueue(options.scheduler(), this::unexpected);
    scope = new OwnedScope(queue, options.scheduler(), options.budgets().cleanup());
    setView(State.PREPARING, "", "", List.of(), null, 0, HintPhase.BUSY, "");
  }

  private boolean active() {
    return stopping.get() == null
        && !ended.isDone()
        && start != null
        && !start.cancellation().isCancelled()
        && !cancellation.isCancelled();
  }

  private void unexpected(Throwable error) {
    if (!ended.isDone()) {
      startupCode = "SESSION_FAILED";
      stop(DialogueEndReason.FAILED);
    }
  }

  private static <T> CompletionStage<T> call(Supplier<CompletionStage<T>> supplier) {
    try {
      return Objects.requireNonNull(supplier.get());
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private <T> void await(
      CompletionStage<T> stage, Duration budget, BiConsumer<T, Throwable> callback) {
    waits.removeIf(PendingOperation::isDone);
    waits.add(new PendingOperation<>(queue, options.scheduler(), budget, stage, callback));
  }

  private <T> void observeBounded(CompletionStage<T> stage, BiConsumer<T, Throwable> callback) {
    stage.whenComplete((value, error) -> queue.execute(() -> callback.accept(value, error)));
  }

  private boolean admitEffect() {
    synchronized (dispatchGate) {
      return active();
    }
  }

  private void cancelReveal() {
    revealGeneration++;
    if (revealTimer != null) revealTimer.cancel();
    revealTimer = null;
  }

  private void fail(String code) {
    startupCode = code;
    stop(DialogueEndReason.FAILED);
  }

  private LocaleContext effectiveLocale(LocaleContext requested) {
    var supported = start.content().definition().locales();
    String tag = requested.tag();
    if (!supported.supported().contains(tag)) tag = Locale.forLanguageTag(tag).getLanguage();
    if (!supported.supported().contains(tag)) tag = requested.fallbackTag();
    if (!supported.supported().contains(tag)) tag = supported.defaultTag();
    return new LocaleContext(tag, requested.revision(), supported.defaultTag());
  }

  private Node current() {
    return start.content().definition().nodes().get(nodeId);
  }

  private void setView(
      State state,
      String speaker,
      String text,
      List<ChoiceView> choices,
      String selected,
      int revealed,
      HintPhase hint,
      String notice) {
    view =
        new DialogueView(
            id,
            pin,
            nodeId,
            visit,
            state,
            ++stateRevision,
            renderRevision,
            locale.tag(),
            speaker,
            text,
            choices,
            selected,
            Graphemes.split(text).size(),
            revealed,
            hint,
            notice,
            pendingAction);
  }

  private CompletionStage<Void> publish() {
    var ack = new CompletableFuture<Void>();
    if (!active() || renderer == null)
      return CompletableFuture.failedFuture(
          new CancellationException("Presentation is no longer active"));
    renderAcknowledgements.add(ack);
    var frame = view;
    await(
        call(() -> renderer.replace(frame)),
        options.budgets().text(),
        (v, e) -> {
          renderAcknowledgements.remove(ack);
          if (!active()) {
            ack.completeExceptionally(new CancellationException("Session closed"));
            return;
          }
          if (e != null) {
            ack.completeExceptionally(e);
            if (!started.isDone()
                || (frame.renderRevision() == renderRevision
                    && frame.stateRevision() == view.stateRevision()))
              fail(e instanceof DialogueFailure f ? f.code() : "RENDER_FAILED");
          } else {
            started.complete(new DialogueStartResult.Started(this));
            ack.complete(null);
          }
        });
    return ack;
  }

  public void begin() {
    queue.execute(
        () -> {
          if (start != null && start.cancellation().isCancelled()) {
            stop(start.cancellation().reason());
            return;
          }
          if (!active()) {
            stopNow();
            return;
          }
          scope.prepare(() -> CompletableFuture.completedFuture(cancellation.onCancel(this::stop)));
          scope.prepare(
              () -> CompletableFuture.completedFuture(start.cancellation().onCancel(this::stop)));
          if (!active()) return;
          if (!start
                  .bindings()
                  .capabilities()
                  .containsAll(start.content().definition().capabilities().required())
              || (start.content().definition().capabilities().required().contains("actor.gesture")
                  && start.bindings().actors() == null)) {
            fail("CAPABILITY_MISSING");
            return;
          }
          long deadline = options.scheduler().nanoTime() + options.budgets().prepare().toNanos();
          var presentation =
              scope.prepare(
                  () ->
                      start
                          .bindings()
                          .renderer()
                          .prepare(
                              new DialogueRenderer.PresentationRequest(
                                  id,
                                  start.audienceId(),
                                  start.hostGeneration(),
                                  preferences,
                                  cancellation,
                                  deadline,
                                  options.budgets())));
          if (!active()) return;
          await(
              presentation,
              options.budgets().prepare(),
              (v, e) -> {
                if (!active()) return;
                if (e != null) fail("PREPARE_FAILED");
                else {
                  renderer = v;
                  rendererReady = true;
                  tryReady();
                }
              });
          if (start.bindings().actors() == null) {
            actorReady = true;
            tryReady();
          } else {
            var speakers = new HashSet<String>();
            start
                .content()
                .definition()
                .nodes()
                .values()
                .forEach(
                    n -> {
                      if (n instanceof Message m) speakers.add(m.speaker());
                    });
            var preparation =
                scope.prepare(
                    () ->
                        start
                            .bindings()
                            .actors()
                            .prepare(
                                new ActorPort.ActorRequest(
                                    id,
                                    start.audienceId(),
                                    start.hostGeneration(),
                                    speakers,
                                    cancellation,
                                    deadline)));
            preparation.whenComplete(
                (v, e) ->
                    queue.execute(
                        () -> {
                          if (v != null && actorSkipped) scope.release(v);
                        }));
            await(
                preparation,
                options.budgets().prepare(),
                (v, e) -> {
                  if (!active()) return;
                  if (e != null) {
                    if (OwnedScope.unwrap(e) instanceof OwnedResource.PreparationFailure) {
                      fail("ACTOR_CLEANUP_FAILED");
                      return;
                    }
                    if (start
                        .content()
                        .definition()
                        .capabilities()
                        .required()
                        .contains("actor.gesture")) {
                      fail("ACTOR_PREPARE_FAILED");
                      return;
                    }
                    actorSkipped = true;
                  } else actor = v;
                  actorReady = true;
                  tryReady();
                });
          }
        });
  }

  private void tryReady() {
    if (!active() || activated || !rendererReady || !actorReady) return;
    activated = true;
    enter(nodeId);
  }

  private void enter(String target) {
    if (!active()) return;
    cancelReveal();
    nodeId = target;
    visit++;
    renderRevision++;
    textGeneration++;
    translating = false;
    if (current() instanceof End e) {
      started.complete(new DialogueStartResult.Started(this));
      stop(DialogueEndReason.valueOf(e.reason().toUpperCase(Locale.ROOT)));
      return;
    }
    if (current() instanceof Message) {
      automaticSteps = 0;
      setView(State.WAITING_FOR_PROVIDER, "", "", List.of(), null, 0, HintPhase.BUSY, "");
      resolveMessage(false, true, null);
      return;
    }
    if (++automaticSteps > start.content().limits().automaticSteps()) {
      fail("AUTOMATIC_STEP_LIMIT");
      return;
    }
    setView(State.WAITING_FOR_PROVIDER, "", "", List.of(), null, 0, HintPhase.BUSY, "");
    switch (current()) {
      case Branch b -> {
        publish();
        branch(b, 0, visit);
      }
      case Action a -> action(a);
      case Outcome o -> outcome(o);
      default -> fail("NODE_EXECUTION_UNAVAILABLE");
    }
  }

  private CompletionStage<ResolvedText> text(String key) {
    return call(
        () ->
            start
                .bindings()
                .texts()
                .resolve(new TextResolver.TextRequest(key, start.context(), locale)));
  }

  private CompletionStage<ConditionResult> condition(Condition c) {
    if (c == null) return CompletableFuture.completedFuture(ConditionResult.TRUE);
    var provider = start.bindings().conditions().get(c.key());
    if (provider == null) return CompletableFuture.completedFuture(ConditionResult.UNAVAILABLE);
    var result = new CompletableFuture<ConditionResult>();
    var req =
        new ConditionProvider.ConditionRequest(
            c.key(), c.arguments(), start.audienceId(), start.hostGeneration(), id, visit);
    await(
        call(() -> provider.evaluate(req)),
        options.budgets().text(),
        (value, error) -> {
          ConditionResult r = error == null && value != null ? value : ConditionResult.UNAVAILABLE;
          if (c.negated() && r != ConditionResult.UNAVAILABLE)
            r = r == ConditionResult.TRUE ? ConditionResult.FALSE : ConditionResult.TRUE;
          result.complete(r);
        });
    return result;
  }

  private CompletionStage<List<ChoiceView>> choices(Message m, List<ResolvedText> labels) {
    var queries = new ArrayList<CompletableFuture<ChoiceView>>();
    for (int i = 0; i < m.choices().size(); i++) {
      var c = m.choices().get(i);
      String label = labels.get(i).plain();
      queries.add(
          condition(c.condition())
              .thenApply(
                  value ->
                      value == ConditionResult.FALSE
                          ? null
                          : new ChoiceView(
                              c.id(),
                              label,
                              value == ConditionResult.TRUE
                                  ? Availability.AVAILABLE
                                  : Availability.UNAVAILABLE,
                              value == ConditionResult.TRUE ? "" : "CHOICE_UNAVAILABLE"))
              .toCompletableFuture());
    }
    return CompletableFuture.allOf(queries.toArray(CompletableFuture[]::new))
        .thenApply(
            v -> queries.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList());
  }

  private void branch(Branch b, int index, long expectedVisit) {
    if (!active() || visit != expectedVisit) return;
    if (index >= b.cases().size()) {
      enter(b.defaultTo());
      return;
    }
    var c = b.cases().get(index);
    condition(c.condition())
        .whenComplete(
            (result, error) ->
                queue.execute(
                    () -> {
                      if (!active() || visit != expectedVisit) return;
                      if (error != null || result == ConditionResult.UNAVAILABLE)
                        enter(b.unavailableTo());
                      else if (result == ConditionResult.TRUE) enter(c.to());
                      else branch(b, index + 1, expectedVisit);
                    }));
  }

  private String payloadHash(
      String key, Map<String, gg.grounds.dialogue.core.definition.ArgumentValue> args) {
    return ContentFingerprint.hash(
            ContentFingerprint.canonicalBytes(
                Map.of(
                    "key",
                    key,
                    "arguments",
                    ContentFingerprint.argumentData(args),
                    "audience",
                    start.audienceId(),
                    "hostGeneration",
                    start.hostGeneration(),
                    "contentPin",
                    pin.sha256())))
        .sha256();
  }

  private void action(Action a) {
    var handler = start.bindings().actions().get(a.key());
    if (handler == null) {
      enter(a.onUnavailable());
      return;
    }
    if (pendingActions.size() >= 128) {
      fail("PENDING_ACTION_LIMIT");
      return;
    }
    long expectedVisit = visit;
    var ref =
        new ActionRef(
            UUID.nameUUIDFromBytes((id + ":action:" + visit).getBytes(StandardCharsets.UTF_8)),
            payloadHash(a.key(), a.arguments()),
            start.audienceId(),
            start.hostGeneration(),
            id,
            visit);
    setView(State.WAITING_FOR_PROVIDER, "", "", List.of(), null, 0, HintPhase.BUSY, "");
    publish();
    if (!admitEffect()) return;
    pendingAction = ref;
    pendingActions.put(ref.requestId(), ref);
    boolean durable = start.content().catalog().actions().get(a.key()).durable();
    var request = new ActionRequest(ref, a.key(), a.arguments(), durable);
    var execution = call(() -> handler.execute(request));
    if (!active()) return;
    setView(State.WAITING_FOR_PROVIDER, "", "", List.of(), null, 0, HintPhase.BUSY, "");
    publish();
    if (!active()) return;
    await(
        execution,
        options.budgets().action(),
        (value, error) -> {
          if (!active() || visit != expectedVisit) return;
          var result = error == null && value != null ? value : ActionResult.UNCERTAIN;
          if (result != ActionResult.UNCERTAIN) {
            pendingActions.remove(ref.requestId());
            if (Objects.equals(pendingAction, ref)) pendingAction = null;
          }
          enter(
              switch (result) {
                case SUCCESS -> a.onSuccess();
                case REJECTED -> a.onRejected();
                case UNAVAILABLE -> a.onUnavailable();
                case UNCERTAIN -> a.onUncertain();
              });
        });
  }

  private void outcome(Outcome o) {
    var sink = start.bindings().outcomes();
    if (sink == null
        || (o.delivery() == Delivery.DURABLE
            && (!start.content().catalog().durableOutcomes()
                || !start.bindings().capabilities().contains("outcome.durable")))) {
      enter(o.onUnavailable());
      return;
    }
    long expectedVisit = visit;
    var event =
        new OutcomeEvent(
            UUID.nameUUIDFromBytes((id + ":outcome:" + visit).getBytes(StandardCharsets.UTF_8)),
            o.milestone(),
            o.delivery(),
            start.audienceId(),
            start.hostGeneration(),
            id,
            visit,
            payloadHash(o.milestone(), Map.of()));
    publish();
    if (!admitEffect()) return;
    var delivery = call(() -> sink.deliver(event));
    if (!active()) return;
    await(
        delivery,
        options.budgets().action(),
        (value, error) -> {
          if (!active() || visit != expectedVisit) return;
          var result = error == null && value != null ? value : OutcomeResult.UNCERTAIN;
          enter(
              switch (result) {
                case ACKNOWLEDGED -> o.onAcknowledged();
                case UNAVAILABLE -> o.onUnavailable();
                case UNCERTAIN -> o.onUncertain();
              });
        });
  }

  private void resolveMessage(
      boolean revealAll, boolean cue, CompletableFuture<RefreshResult> refresh) {
    if (!active() || !(current() instanceof Message m)) return;
    long generation = ++textGeneration;
    long nodeVisit = visit;
    translating = true;
    String previousSelection = view.selectedChoiceId();
    var requests = new ArrayList<CompletionStage<ResolvedText>>();
    requests.add(text(m.text()));
    String speakerKey = "speaker." + m.speaker();
    boolean named =
        new LinkedHashSet<>(
                List.of(
                    locale.tag(),
                    Locale.forLanguageTag(locale.tag()).getLanguage(),
                    locale.fallbackTag(),
                    start.content().definition().locales().defaultTag()))
            .stream()
                .anyMatch(
                    tag ->
                        start
                            .content()
                            .texts()
                            .locales()
                            .getOrDefault(tag, Map.of())
                            .containsKey(speakerKey));
    requests.add(
        named
            ? text(speakerKey)
            : CompletableFuture.completedFuture(new ResolvedText(m.speaker(), locale.tag())));
    for (var c : m.choices()) requests.add(text(c.text()));
    var futures =
        requests.stream()
            .map(CompletionStage::toCompletableFuture)
            .toArray(CompletableFuture[]::new);
    await(
        CompletableFuture.allOf(futures),
        options.budgets().text(),
        (v, error) -> {
          if (!active() || visit != nodeVisit || textGeneration != generation) return;
          if (error != null) {
            fail("TEXT_UNAVAILABLE");
            return;
          }
          var resolved = requests.stream().map(s -> s.toCompletableFuture().join()).toList();
          observeBounded(
              choices(m, resolved.subList(2, resolved.size())),
              (offers, choiceError) -> {
                if (!active() || visit != nodeVisit || textGeneration != generation) return;
                if (choiceError != null) {
                  fail("CHOICES_UNAVAILABLE");
                  return;
                }
                translating = false;
                var labelMap = new LinkedHashMap<String, ResolvedText>();
                for (int i = 0; i < m.choices().size(); i++)
                  labelMap.put(m.choices().get(i).id(), resolved.get(i + 2));
                resolvedChoiceLabels = Map.copyOf(labelMap);
                String selected = previousSelection;
                if (selected == null
                    || offers.stream()
                        .noneMatch(
                            c ->
                                c.id().equals(previousSelection)
                                    && c.availability() == Availability.AVAILABLE))
                  selected = initialSelection(m, offers);
                String line = resolved.get(0).plain();
                int total = Graphemes.split(line).size();
                boolean immediate = revealAll || preferences.graphemeDelay().isZero() || total == 0;
                enteredNanos = options.scheduler().nanoTime();
                setView(
                    immediate ? State.WAITING_FOR_INPUT : State.REVEALING,
                    resolved.get(1).plain(),
                    line,
                    offers,
                    selected,
                    immediate ? total : 0,
                    immediate ? hint(m, offers) : HintPhase.REVEAL,
                    "");
                if (cue && m.gesture() != null && actor != null) {
                  var actorCue =
                      new ActorPort.ActorCue(
                          m.speaker(), m.gesture(), visit, id + ":" + visit + ":" + m.gesture());
                  await(
                      call(() -> actor.cue(actorCue)),
                      options.budgets().prepare(),
                      (ignored, cueError) -> {
                        if (active()
                            && cueError != null
                            && start
                                .content()
                                .definition()
                                .capabilities()
                                .required()
                                .contains("actor.gesture")) fail("ACTOR_CUE_FAILED");
                      });
                }
                publish();
                if (!immediate) scheduleReveal();
                if (refresh != null) {
                  refresh.complete(RefreshResult.UPDATED);
                  if (localePending == refresh) localePending = null;
                }
              });
        });
  }

  private static String initialSelection(Message m, List<ChoiceView> offers) {
    for (var c : m.choices())
      if (c.defaultChoice()
          && offers.stream()
              .anyMatch(o -> o.id().equals(c.id()) && o.availability() == Availability.AVAILABLE))
        return c.id();
    return offers.stream()
        .filter(o -> o.availability() == Availability.AVAILABLE)
        .map(ChoiceView::id)
        .findFirst()
        .orElse(null);
  }

  private HintPhase hint(Message m, List<ChoiceView> choices) {
    return choices.isEmpty()
        ? (start.content().definition().nodes().get(m.next()) instanceof End
            ? HintPhase.FINISH
            : HintPhase.CONTINUE)
        : HintPhase.CHOOSE;
  }

  private void scheduleReveal() {
    if (!active() || switching || translating || view.state() != State.REVEALING) return;
    cancelReveal();
    long expected = visit, expectedReveal = revealGeneration;
    revealTimer =
        options
            .scheduler()
            .schedule(
                preferences.graphemeDelay(),
                () ->
                    queue.execute(
                        () -> {
                          if (!active()
                              || visit != expected
                              || revealGeneration != expectedReveal
                              || switching
                              || view.state() != State.REVEALING
                              || translating) return;
                          long elapsed = options.scheduler().nanoTime() - enteredNanos;
                          int count =
                              (int)
                                  Math.min(
                                      view.totalGraphemes(),
                                      Math.max(
                                          0,
                                          (preferences.graphemeDelay().isZero()
                                              ? view.totalGraphemes()
                                              : elapsed / preferences.graphemeDelay().toNanos())));
                          boolean done = count >= view.totalGraphemes();
                          setView(
                              done ? State.WAITING_FOR_INPUT : State.REVEALING,
                              view.speaker(),
                              view.text(),
                              view.choices(),
                              view.selectedChoiceId(),
                              count,
                              done ? hint((Message) current(), view.choices()) : HintPhase.REVEAL,
                              view.notice());
                          publish();
                          if (!done) scheduleReveal();
                        }));
  }

  public CompletionStage<InputResult> submit(DialogueInput input) {
    Objects.requireNonNull(input);
    if (input instanceof DialogueInput.Cancel c) {
      stop(c.reason());
      return CompletableFuture.completedFuture(InputResult.ACCEPTED);
    }
    if (inputCount.incrementAndGet() > options.inputQueueLimit()) {
      inputCount.decrementAndGet();
      return CompletableFuture.completedFuture(InputResult.BUSY);
    }
    var result = new CompletableFuture<InputResult>();
    queue.execute(
        () -> {
          inputCount.decrementAndGet();
          try {
            handleInput(input, result);
          } catch (Throwable error) {
            result.completeExceptionally(error);
            unexpected(error);
          }
        });
    return result.minimalCompletionStage();
  }

  private void handleInput(DialogueInput input, CompletableFuture<InputResult> result) {
    if (!active()) {
      result.complete(InputResult.ENDED);
      return;
    }
    if (input instanceof DialogueInput.RevealFinished ack) {
      result.complete(
          id.equals(ack.sessionId()) && renderRevision == ack.renderRevision()
              ? InputResult.ACCEPTED
              : InputResult.STALE);
      return;
    }
    var stamp =
        input instanceof DialogueInput.Select s
            ? s.stamp()
            : ((DialogueInput.Confirm) input).stamp();
    if (!id.equals(stamp.sessionId())
        || stamp.renderRevision() != renderRevision
        || stamp.impulseId() <= lastImpulse) {
      result.complete(InputResult.STALE);
      return;
    }
    lastImpulse = stamp.impulseId();
    if (translating
        || switching
        || confirming
        || view.state() == State.WAITING_FOR_PROVIDER
        || !(current() instanceof Message m)) {
      result.complete(InputResult.BUSY);
      return;
    }
    if (input instanceof DialogueInput.Select select) {
      if (view.choices().stream()
          .noneMatch(
              c ->
                  c.id().equals(select.choiceId()) && c.availability() == Availability.AVAILABLE)) {
        result.complete(InputResult.INVALID);
        return;
      }
      setView(
          view.state(),
          view.speaker(),
          view.text(),
          view.choices(),
          select.choiceId(),
          view.revealedGraphemes(),
          view.hint(),
          view.notice());
      publish();
      result.complete(InputResult.ACCEPTED);
      return;
    }
    if (view.state() == State.REVEALING) {
      cancelReveal();
      setView(
          State.WAITING_FOR_INPUT,
          view.speaker(),
          view.text(),
          view.choices(),
          view.selectedChoiceId(),
          view.totalGraphemes(),
          hint(m, view.choices()),
          view.notice());
      publish();
      result.complete(InputResult.REVEAL_ONLY);
      return;
    }
    var confirm = (DialogueInput.Confirm) input;
    if (m.choices().isEmpty()) {
      if (confirm.choiceId() != null) {
        result.complete(InputResult.INVALID);
        return;
      }
      enter(m.next());
      result.complete(InputResult.ACCEPTED);
      return;
    }
    if (!Objects.equals(confirm.choiceId(), view.selectedChoiceId())) {
      result.complete(InputResult.INVALID);
      return;
    }
    var chosen =
        m.choices().stream()
            .filter(c -> c.id().equals(confirm.choiceId()))
            .findFirst()
            .orElse(null);
    if (chosen == null) {
      result.complete(InputResult.INVALID);
      return;
    }
    confirming = true;
    pendingConfirm = result;
    long expectedVisit = visit, expectedRender = renderRevision;
    var oldOffers = view.choices();
    String selected = view.selectedChoiceId();
    var labels = m.choices().stream().map(c -> resolvedChoiceLabels.get(c.id())).toList();
    setView(
        State.WAITING_FOR_PROVIDER,
        view.speaker(),
        view.text(),
        oldOffers,
        selected,
        view.totalGraphemes(),
        HintPhase.BUSY,
        "");
    publish();
    observeBounded(
        choices(m, labels),
        (offers, error) -> {
          if (!active()) {
            result.complete(InputResult.ENDED);
            return;
          }
          confirming = false;
          pendingConfirm = null;
          if (visit != expectedVisit || renderRevision != expectedRender) {
            if (visit == expectedVisit && !translating && offers != null) {
              var refreshed = new ArrayList<ChoiceView>();
              for (var offer : offers) {
                var label = resolvedChoiceLabels.get(offer.id());
                if (label != null)
                  refreshed.add(
                      new ChoiceView(
                          offer.id(),
                          label.plain(),
                          offer.availability(),
                          offer.unavailableReason()));
              }
              refreshOffers(m, refreshed, "CHOICES_CHANGED");
            }
            restoreMessageState();
            result.complete(InputResult.STALE);
            return;
          }
          if (error != null) {
            setView(
                State.WAITING_FOR_INPUT,
                view.speaker(),
                view.text(),
                oldOffers,
                selected,
                view.totalGraphemes(),
                hint(m, oldOffers),
                "CHOICES_UNAVAILABLE");
            publish();
            result.complete(InputResult.BUSY);
            return;
          }
          if (!offers.equals(oldOffers)
              || offers.stream()
                  .noneMatch(
                      c -> c.id().equals(selected) && c.availability() == Availability.AVAILABLE)) {
            renderRevision++;
            String nextSelection =
                offers.stream()
                        .anyMatch(
                            c ->
                                c.id().equals(selected)
                                    && c.availability() == Availability.AVAILABLE)
                    ? selected
                    : initialSelection(m, offers);
            setView(
                State.WAITING_FOR_INPUT,
                view.speaker(),
                view.text(),
                offers,
                nextSelection,
                view.totalGraphemes(),
                hint(m, offers),
                "CHOICES_CHANGED");
            publish();
            result.complete(InputResult.STALE);
            return;
          }
          enter(chosen.to());
          result.complete(InputResult.ACCEPTED);
        });
  }

  private void refreshOffers(Message message, List<ChoiceView> offers, String notice) {
    String previousSelection = view.selectedChoiceId();
    String selected = previousSelection;
    if (offers.stream()
        .noneMatch(
            o -> o.id().equals(previousSelection) && o.availability() == Availability.AVAILABLE))
      selected = initialSelection(message, offers);
    renderRevision++;
    boolean busy = translating || confirming || switching;
    setView(
        busy ? State.WAITING_FOR_PROVIDER : State.WAITING_FOR_INPUT,
        view.speaker(),
        view.text(),
        offers,
        selected,
        view.totalGraphemes(),
        busy ? HintPhase.BUSY : hint(message, offers),
        notice);
    publish();
  }

  private void restoreMessageState() {
    if (!active()
        || translating
        || confirming
        || switching
        || !(current() instanceof Message message)) return;
    if (view.state() == State.WAITING_FOR_PROVIDER) {
      boolean reveal = view.revealedGraphemes() < view.totalGraphemes();
      setView(
          reveal ? State.REVEALING : State.WAITING_FOR_INPUT,
          view.speaker(),
          view.text(),
          view.choices(),
          view.selectedChoiceId(),
          view.revealedGraphemes(),
          reveal ? HintPhase.REVEAL : hint(message, view.choices()),
          view.notice());
      if (reveal) scheduleReveal();
    }
  }

  public CompletionStage<RefreshResult> refreshLocale(LocaleContext requested) {
    Objects.requireNonNull(requested);
    var result = new CompletableFuture<RefreshResult>();
    queue.execute(
        () -> {
          if (!active()) {
            result.complete(RefreshResult.ENDED);
            return;
          }
          if (requested.revision() <= locale.revision()) {
            result.complete(RefreshResult.STALE);
            return;
          }
          if (localePending != null) localePending.complete(RefreshResult.STALE);
          localePending = result;
          locale = effectiveLocale(requested);
          renderRevision++;
          cancelReveal();
          if (current() instanceof Message) resolveMessage(true, false, result);
          else {
            setView(
                view.state(),
                view.speaker(),
                view.text(),
                view.choices(),
                view.selectedChoiceId(),
                view.revealedGraphemes(),
                view.hint(),
                view.notice());
            publish();
            result.complete(RefreshResult.UPDATED);
            localePending = null;
          }
        });
    return result.minimalCompletionStage();
  }

  public CompletionStage<Void> updatePreferences(PresentationPreferences next) {
    Objects.requireNonNull(next);
    var result = new CompletableFuture<Void>();
    queue.execute(
        () -> {
          if (!active()) {
            result.completeExceptionally(new DialogueFailure("ENDED", "Session ended"));
            return;
          }
          preferenceResults.removeIf(CompletableFuture::isDone);
          preferenceResults.add(result);
          var previous = preferences;
          long gen = ++preferenceGeneration;
          switching = true;
          renderRevision++;
          cancelReveal();
          await(
              call(() -> renderer.reconfigure(next)),
              options.budgets().prepare(),
              (v, error) -> {
                if (!active()) {
                  result.completeExceptionally(new DialogueFailure("ENDED", "Session ended"));
                  return;
                }
                if (gen != preferenceGeneration) {
                  result.completeExceptionally(new CancellationException("Superseded preferences"));
                  return;
                }
                switching = false;
                Throwable e = error == null ? null : OwnedScope.unwrap(error);
                if (e instanceof DialogueRenderer.ReconfigurationRejected rejected) {
                  preferences =
                      rejected.activePreferences() == null
                          ? previous
                          : rejected.activePreferences();
                } else if (e != null) {
                  result.completeExceptionally(e);
                  fail("PRESENTATION_HANDOFF_FAILED");
                  return;
                } else preferences = next;
                if (current() instanceof Message message
                    && !translating
                    && view.revealedGraphemes() < view.totalGraphemes()) {
                  if (preferences.graphemeDelay().isZero()) {
                    setView(
                        confirming ? State.WAITING_FOR_PROVIDER : State.WAITING_FOR_INPUT,
                        view.speaker(),
                        view.text(),
                        view.choices(),
                        view.selectedChoiceId(),
                        view.totalGraphemes(),
                        confirming ? HintPhase.BUSY : hint(message, view.choices()),
                        view.notice());
                  } else {
                    enteredNanos =
                        options.scheduler().nanoTime()
                            - view.revealedGraphemes() * preferences.graphemeDelay().toNanos();
                    if (!confirming)
                      setView(
                          State.REVEALING,
                          view.speaker(),
                          view.text(),
                          view.choices(),
                          view.selectedChoiceId(),
                          view.revealedGraphemes(),
                          HintPhase.REVEAL,
                          view.notice());
                    scheduleReveal();
                  }
                }
                restoreMessageState();
                setView(
                    view.state(),
                    view.speaker(),
                    view.text(),
                    view.choices(),
                    view.selectedChoiceId(),
                    view.revealedGraphemes(),
                    view.hint(),
                    view.notice());
                publish()
                    .whenComplete(
                        (ignored, renderError) -> {
                          if (e != null) result.completeExceptionally(e);
                          else if (renderError != null) result.completeExceptionally(renderError);
                          else if (gen != preferenceGeneration)
                            result.completeExceptionally(
                                new CancellationException("Superseded preferences"));
                          else result.complete(null);
                        });
              });
        });
    return result.minimalCompletionStage();
  }

  public DialogueView snapshot() {
    return view;
  }

  public CompletionStage<DialogueEnd> completion() {
    return endObservation;
  }

  public CompletionStage<DialogueEnd> stop(DialogueEndReason reason) {
    Objects.requireNonNull(reason);
    boolean admitted;
    synchronized (dispatchGate) {
      admitted = stopping.compareAndSet(null, reason);
    }
    if (admitted) {
      Throwable failure = null;
      try {
        cancellation.cancel(reason);
      } catch (RuntimeException error) {
        failure = error;
      }
      final Throwable callbackFailure = failure;
      queue.execute(
          () -> {
            if (callbackFailure != null && startupCode == null)
              startupCode = "CANCEL_CALLBACK_FAILED";
            stopNow();
          });
    }
    return endObservation;
  }

  private void stopNow() {
    if (closing) return;
    closing = true;
    var cancelError = new CancellationException("Session closing");
    renderAcknowledgements.forEach(f -> f.completeExceptionally(cancelError));
    renderAcknowledgements.clear();
    preferenceResults.forEach(f -> f.completeExceptionally(cancelError));
    preferenceResults.clear();
    waits.forEach(PendingOperation::cancel);
    waits.clear();
    cancelReveal();
    if (localePending != null) {
      localePending.complete(RefreshResult.ENDED);
      localePending = null;
    }
    if (pendingConfirm != null) {
      pendingConfirm.complete(InputResult.ENDED);
      pendingConfirm = null;
    }
    confirming = false;
    if (stopping.get() == null) stopping.compareAndSet(null, DialogueEndReason.CANCELLED);
    renderRevision++;
    setView(State.CLOSING, "", "", List.of(), null, 0, HintPhase.BUSY, "");
    scope.closeAsync().whenComplete((v, error) -> queue.execute(() -> finish(error)));
  }

  private void finish(Throwable error) {
    setView(
        State.CLOSED,
        "",
        "",
        List.of(),
        null,
        0,
        HintPhase.BUSY,
        error == null ? (startupCode == null ? "" : startupCode) : "CLEANUP_FAILED");
    start = null;
    renderer = null;
    actor = null;
    resolvedChoiceLabels = Map.of();
    started.complete(
        new DialogueStartResult.Rejected(
            error == null
                ? (startupCode == null ? stopping.get().name() : startupCode)
                : "CLEANUP_FAILED",
            List.of()));
    if (error != null) ended.completeExceptionally(error);
    else ended.complete(new DialogueEnd(id, stopping.get(), List.copyOf(pendingActions.values())));
    pendingActions.clear();
  }
}
