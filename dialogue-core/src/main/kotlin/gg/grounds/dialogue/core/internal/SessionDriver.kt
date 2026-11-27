package gg.grounds.dialogue.core.internal

import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.definition.DialogueDefinition.*
import gg.grounds.dialogue.core.port.*
import gg.grounds.dialogue.core.port.ActionHandler.*
import gg.grounds.dialogue.core.port.ConditionProvider.*
import gg.grounds.dialogue.core.port.OutcomeSink.*
import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.core.runtime.DialogueView.*
import gg.grounds.dialogue.core.runtime.TypingTiming
import gg.grounds.dialogue.core.text.*
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.core.validation.ContentPin
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import java.util.Optional
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.function.BiConsumer
import java.util.function.Supplier

@Suppress("REDUNDANT_ELSE_IN_WHEN")
class SessionDriver(
    private var start: DialogueStart?,
    private val options: RuntimeOptions,
    private val started: CompletableFuture<DialogueStartResult>,
) : DialogueHandle {
    private val id = UUID.randomUUID()
    private val pin: ContentPin = start!!.content().contentPin()
    private val queue = SerialCommandQueue(options.scheduler, ::unexpected)
    private val scope = OwnedScope(queue, options.scheduler, options.budgets.cleanup)
    private val ended = CompletableFuture<DialogueEnd>()
    private val endObservation = ended.minimalCompletionStage()
    private val dispatchGate = Any()
    private val stopping = AtomicReference<DialogueEndReason>()
    private val inputCount = AtomicInteger()
    private val cancellation = CancellationToken()
    private val waits = ArrayList<PendingOperation<*>>()
    private val renderAcknowledgements = HashSet<CompletableFuture<Void>>()
    private val preferenceResults = HashSet<CompletableFuture<Void>>()
    private var resolvedChoiceLabels: Map<String, ResolvedText> = emptyMap()
    private val pendingActions = LinkedHashMap<UUID, ActionRef>()
    private var pendingAction: ActionRef? = null
    private var automaticSteps = 0
    private var confirming = false
    private var pendingConfirm: CompletableFuture<InputResult>? = null
    @Volatile private lateinit var view: DialogueView
    private var renderer: DialogueRenderer.PresentationHandle? = null
    private var actor: ActorPort.ActorHandle? = null
    private var rendererReady = false
    private var actorReady = false
    private var actorSkipped = false
    private var activated = false
    private var activating = false
    private var closing = false
    private var translating = false
    private var switching = false
    private var startupCode: String? = null
    private var nodeId = start!!.content().definition().entry()
    private var visit = 0L
    private var renderRevision = 0L
    private var stateRevision = 0L
    private var textGeneration = 0L
    private var lastImpulse = start!!.openingImpulse()
    private var enteredNanos = 0L
    private var preferenceGeneration = 0L
    private var revealGeneration = 0L
    private var locale = effectiveLocale(start!!.locale())
    private var preferences = start!!.preferences()
    private var revealTimer: DialogueScheduler.ScheduledTask? = null
    private var localePending: CompletableFuture<RefreshResult>? = null

    init {
        setView(State.PREPARING, "", "", emptyList(), null, 0, HintPhase.BUSY, "")
    }

    private fun active() =
        stopping.get() == null &&
            !ended.isDone &&
            start != null &&
            !start!!.cancellation().isCancelled() &&
            !cancellation.isCancelled()

    private fun unexpected(error: Throwable) {
        if (!ended.isDone) {
            startupCode = "SESSION_FAILED"
            stop(DialogueEndReason.FAILED)
        }
    }

    private fun <T> call(s: Supplier<CompletionStage<T>>): CompletionStage<T> =
        try {
            Objects.requireNonNull(s.get())
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }

    private fun <T> await(
        stage: CompletionStage<T>,
        budget: Duration,
        callback: BiConsumer<T?, Throwable?>,
    ) {
        waits.removeIf { it.isDone() }
        waits.add(PendingOperation(queue, options.scheduler, budget, stage, callback))
    }

    private fun <T> observeBounded(
        stage: CompletionStage<T>,
        callback: BiConsumer<T?, Throwable?>,
    ) {
        stage.whenComplete { v, e -> queue.execute { callback.accept(v, e) } }
    }

    private fun admitEffect() = synchronized(dispatchGate) { active() }

    private fun invalidateTyping() {
        try {
            renderer?.invalidateTyping()
        } catch (_: Throwable) {
            // Optional typing notifications must not interrupt the owner queue.
        }
    }

    private fun cancelReveal() {
        revealGeneration++
        revealTimer?.cancel()
        revealTimer = null
    }

    private fun fail(code: String) {
        startupCode = code
        stop(DialogueEndReason.FAILED)
    }

    private fun effectiveLocale(requested: LocaleContext): LocaleContext {
        val s = start!!.content().definition().locales()
        var tag = requested.tag()
        if (!s.supported().contains(tag)) tag = Locale.forLanguageTag(tag).language
        if (!s.supported().contains(tag)) tag = requested.fallbackTag()
        if (!s.supported().contains(tag)) tag = s.defaultTag()
        return LocaleContext(tag, requested.revision(), s.defaultTag())
    }

    private fun current(): Node = start!!.content().definition().nodes()[nodeId]!!

    private fun setView(
        state: State,
        speaker: String,
        text: String,
        choices: List<ChoiceView>,
        selected: String?,
        revealed: Int,
        hint: HintPhase,
        notice: String,
    ) {
        view =
            DialogueView(
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
                Graphemes.split(text).size,
                revealed,
                hint,
                notice,
                pendingAction,
            )
    }

    private fun publish(): CompletionStage<Void> {
        val ack = CompletableFuture<Void>()
        val r = renderer
        if (!active() || r == null)
            return CompletableFuture.failedFuture(
                CancellationException("Presentation is no longer active")
            )
        renderAcknowledgements.add(ack)
        val frame = view
        val timing =
            if (frame.state() == State.REVEALING && !preferences.graphemeDelay.isZero)
                Optional.of(
                    TypingTiming(
                        id,
                        visit,
                        renderRevision,
                        preferences.graphemeDelay.toNanos(),
                        enteredNanos,
                        enteredNanos + frame.totalGraphemes() * preferences.graphemeDelay.toNanos(),
                    )
                )
            else Optional.empty()
        await(call { r.replace(frame, timing) }, options.budgets.text) { _, e ->
            renderAcknowledgements.remove(ack)
            if (!active()) {
                ack.completeExceptionally(CancellationException("Session closed"))
                return@await
            }
            if (e != null) {
                ack.completeExceptionally(e)
                if (
                    !started.isDone ||
                        frame.renderRevision() == renderRevision &&
                            frame.stateRevision() == view.stateRevision()
                )
                    fail(if (e is DialogueFailure) e.code() else "RENDER_FAILED")
            } else {
                started.complete(DialogueStartResult.Started(this))
                ack.complete(null)
            }
        }
        return ack
    }

    fun begin() {
        queue.execute {
            if (start != null && start!!.cancellation().isCancelled()) {
                stop(checkNotNull(start!!.cancellation().reason()))
                return@execute
            }
            if (!active()) {
                stopNow()
                return@execute
            }
            scope.prepare { CompletableFuture.completedFuture(cancellation.onCancel(::stop)) }
            scope.prepare {
                CompletableFuture.completedFuture(start!!.cancellation().onCancel(::stop))
            }
            if (!active()) return@execute
            val required = start!!.content().definition().capabilities().required()
            if (
                !start!!.bindings().capabilities().containsAll(required) ||
                    required.contains("actor.gesture") && start!!.bindings().actors() == null
            ) {
                fail("CAPABILITY_MISSING")
                return@execute
            }
            val deadline = options.scheduler.nanoTime() + options.budgets.prepare.toNanos()
            val presentation = scope.prepare {
                start!!
                    .bindings()
                    .renderer()
                    .prepare(
                        DialogueRenderer.PresentationRequest(
                            id,
                            start!!.audienceId(),
                            start!!.hostGeneration(),
                            preferences,
                            cancellation,
                            deadline,
                            options.budgets,
                        )
                    )
            }
            if (!active()) return@execute
            await(presentation, options.budgets.prepare) { v, e ->
                if (active()) {
                    if (e != null) fail("PREPARE_FAILED")
                    else {
                        renderer = v
                        rendererReady = true
                        tryReady()
                    }
                }
            }
            val actors = start!!.bindings().actors()
            if (actors == null) {
                actorReady = true
                tryReady()
            } else {
                val speakers = HashSet<String>()
                start!!.content().definition().nodes().values.forEach {
                    if (it is Message) speakers.add(it.speaker())
                }
                val prep = scope.prepare {
                    actors.prepare(
                        ActorPort.ActorRequest(
                            id,
                            start!!.audienceId(),
                            start!!.hostGeneration(),
                            speakers,
                            cancellation,
                            deadline,
                        )
                    )
                }
                prep.whenComplete { v, _ ->
                    queue.execute { if (v != null && actorSkipped) scope.release(v) }
                }
                await(prep, options.budgets.prepare) { v, e ->
                    if (!active()) return@await
                    if (e != null) {
                        if (OwnedScope.unwrap(e) is OwnedResource.PreparationFailure) {
                            fail("ACTOR_CLEANUP_FAILED")
                            return@await
                        }
                        if (required.contains("actor.gesture")) {
                            fail("ACTOR_PREPARE_FAILED")
                            return@await
                        }
                        actorSkipped = true
                    } else actor = v
                    actorReady = true
                    tryReady()
                }
            }
        }
    }

    private fun tryReady() {
        if (!active() || activated || activating || !rendererReady || !actorReady) return
        val prepared =
            actor
                ?: run {
                    activated = true
                    enter(nodeId)
                    return
                }
        activating = true
        await(call { prepared.activate() }, options.budgets.prepare) { _, e ->
            if (!active()) return@await
            if (e == null) {
                activating = false
                activated = true
                enter(nodeId)
                return@await
            }
            if (
                start!!.content().definition().capabilities().required().contains("actor.gesture")
            ) {
                fail("ACTOR_ACTIVATION_FAILED")
                return@await
            }
            actor = null
            actorSkipped = true
            await(scope.release(prepared), options.budgets.cleanup) { _, cleanup ->
                if (active()) {
                    if (cleanup != null) fail("ACTOR_CLEANUP_FAILED")
                    else {
                        activating = false
                        activated = true
                        enter(nodeId)
                    }
                }
            }
        }
    }

    private fun enter(target: String) {
        if (!active()) return
        cancelReveal()
        nodeId = target
        visit++
        renderRevision++
        textGeneration++
        translating = false
        when (val n = current()) {
            is End -> {
                started.complete(DialogueStartResult.Started(this))
                stop(DialogueEndReason.valueOf(n.reason.uppercase(Locale.ROOT)))
            }
            is Message -> {
                automaticSteps = 0
                setView(
                    State.WAITING_FOR_PROVIDER,
                    "",
                    "",
                    emptyList(),
                    null,
                    0,
                    HintPhase.BUSY,
                    "",
                )
                resolveMessage(false, true, null)
            }
            else -> {
                if (++automaticSteps > start!!.content().limits().automaticSteps) {
                    fail("AUTOMATIC_STEP_LIMIT")
                    return
                }
                setView(
                    State.WAITING_FOR_PROVIDER,
                    "",
                    "",
                    emptyList(),
                    null,
                    0,
                    HintPhase.BUSY,
                    "",
                )
                when (n) {
                    is Branch -> {
                        publish()
                        branch(n, 0, visit)
                    }
                    is Action -> action(n)
                    is Outcome -> outcome(n)
                    else -> fail("NODE_EXECUTION_UNAVAILABLE")
                }
            }
        }
    }

    private fun text(key: String) = call {
        start!!.bindings().texts().resolve(TextResolver.TextRequest(key, start!!.context(), locale))
    }

    private fun condition(c: Condition?): CompletionStage<ConditionResult> {
        if (c == null) return CompletableFuture.completedFuture(ConditionResult.TRUE)
        val p =
            start!!.bindings().conditions()[c.key()]
                ?: return CompletableFuture.completedFuture(ConditionResult.UNAVAILABLE)
        val result = CompletableFuture<ConditionResult>()
        val req =
            ConditionProvider.ConditionRequest(
                c.key(),
                c.arguments(),
                start!!.audienceId(),
                start!!.hostGeneration(),
                id,
                visit,
            )
        await(call { p.evaluate(req) }, options.budgets.text) { v, e ->
            var r = if (e == null && v != null) v else ConditionResult.UNAVAILABLE
            if (c.negated() && r != ConditionResult.UNAVAILABLE)
                r = if (r == ConditionResult.TRUE) ConditionResult.FALSE else ConditionResult.TRUE
            result.complete(r)
        }
        return result
    }

    private fun choices(m: Message, labels: List<ResolvedText>): CompletionStage<List<ChoiceView>> {
        val q = ArrayList<CompletableFuture<ChoiceView?>>()
        m.choices().forEachIndexed { i, c ->
            q.add(
                condition(c.condition)
                    .thenApply { v ->
                        if (v == ConditionResult.FALSE) null
                        else
                            ChoiceView(
                                c.id,
                                labels[i].plain,
                                if (v == ConditionResult.TRUE) Availability.AVAILABLE
                                else Availability.UNAVAILABLE,
                                if (v == ConditionResult.TRUE) "" else "CHOICE_UNAVAILABLE",
                            )
                    }
                    .toCompletableFuture()
            )
        }
        return CompletableFuture.allOf(*q.toTypedArray()).thenApply { q.mapNotNull { it.join() } }
    }

    private fun branch(b: Branch, i: Int, expected: Long) {
        if (!active() || visit != expected) return
        if (i >= b.cases().size) {
            enter(b.defaultTo())
            return
        }
        val c = b.cases()[i]
        condition(c.condition).whenComplete { r, e ->
            queue.execute {
                if (!active() || visit != expected) return@execute
                if (e != null || r == ConditionResult.UNAVAILABLE) enter(b.unavailableTo())
                else if (r == ConditionResult.TRUE) enter(c.to) else branch(b, i + 1, expected)
            }
        }
    }

    private fun payloadHash(key: String, args: Map<String, ArgumentValue>) =
        ContentFingerprint.hash(
                ContentFingerprint.canonicalBytes(
                    mapOf(
                        "key" to key,
                        "arguments" to ContentFingerprint.argumentData(args),
                        "audience" to start!!.audienceId(),
                        "hostGeneration" to start!!.hostGeneration(),
                        "contentPin" to pin.sha256,
                    )
                )
            )
            .sha256

    private fun action(a: Action) {
        val h =
            start!!.bindings().actions()[a.key()]
                ?: run {
                    enter(a.onUnavailable())
                    return
                }
        if (pendingActions.size >= 128) {
            fail("PENDING_ACTION_LIMIT")
            return
        }
        val expected = visit
        val ref =
            ActionRef(
                UUID.nameUUIDFromBytes("$id:action:$visit".toByteArray(StandardCharsets.UTF_8)),
                payloadHash(a.key(), a.arguments()),
                start!!.audienceId(),
                start!!.hostGeneration(),
                id,
                visit,
            )
        setView(State.WAITING_FOR_PROVIDER, "", "", emptyList(), null, 0, HintPhase.BUSY, "")
        publish()
        if (!admitEffect()) return
        pendingAction = ref
        pendingActions[ref.requestId] = ref
        val execution = call {
            h.execute(
                ActionRequest(
                    ref,
                    a.key(),
                    a.arguments(),
                    start!!.content().catalog().actions()[a.key()]!!.durable(),
                )
            )
        }
        if (!active()) return
        setView(State.WAITING_FOR_PROVIDER, "", "", emptyList(), null, 0, HintPhase.BUSY, "")
        publish()
        publish()
        if (!active()) return
        await(execution, options.budgets.action) { v, e ->
            if (!active() || visit != expected) return@await
            val r = if (e == null && v != null) v else ActionResult.UNCERTAIN
            if (r != ActionResult.UNCERTAIN) {
                pendingActions.remove(ref.requestId)
                if (pendingAction == ref) pendingAction = null
            }
            enter(
                when (r) {
                    ActionResult.SUCCESS -> a.onSuccess()
                    ActionResult.REJECTED -> a.onRejected()
                    ActionResult.UNAVAILABLE -> a.onUnavailable()
                    ActionResult.UNCERTAIN -> a.onUncertain()
                }
            )
        }
    }

    private fun outcome(o: Outcome) {
        val sink = start!!.bindings().outcomes()
        if (
            sink == null ||
                o.delivery == Delivery.DURABLE &&
                    (!start!!.content().catalog().durableOutcomes() ||
                        !start!!.bindings().capabilities().contains("outcome.durable"))
        ) {
            enter(o.onUnavailable)
            return
        }
        val expected = visit
        val event =
            OutcomeEvent(
                UUID.nameUUIDFromBytes("$id:outcome:$visit".toByteArray(StandardCharsets.UTF_8)),
                o.milestone,
                o.delivery,
                start!!.audienceId(),
                start!!.hostGeneration(),
                id,
                visit,
                payloadHash(o.milestone, emptyMap()),
            )
        publish()
        if (!admitEffect()) return
        val delivery = call { sink.deliver(event) }
        if (!active()) return
        await(delivery, options.budgets.action) { v, e ->
            if (!active() || visit != expected) return@await
            enter(
                when (val r = if (e == null && v != null) v else OutcomeResult.UNCERTAIN) {
                    OutcomeResult.ACKNOWLEDGED -> o.onAcknowledged
                    OutcomeResult.UNAVAILABLE -> o.onUnavailable
                    OutcomeResult.UNCERTAIN -> o.onUncertain
                }
            )
        }
    }

    private fun resolveMessage(
        revealAll: Boolean,
        cue: Boolean,
        refresh: CompletableFuture<RefreshResult>?,
    ) {
        val m = current() as? Message ?: return
        if (!active()) return
        val generation = ++textGeneration
        val nodeVisit = visit
        translating = true
        val previous = view.selectedChoiceId()
        val requests = ArrayList<CompletionStage<ResolvedText>>()
        requests.add(text(m.text()))
        val speakerKey = "speaker.${m.speaker()}"
        val named =
            linkedSetOf(
                    locale.tag(),
                    Locale.forLanguageTag(locale.tag()).language,
                    locale.fallbackTag(),
                    start!!.content().definition().locales().defaultTag(),
                )
                .any {
                    start!!
                        .content()
                        .texts()
                        .locales()
                        .getOrDefault(it, emptyMap())
                        .containsKey(speakerKey)
                }
        requests.add(
            if (named) text(speakerKey)
            else CompletableFuture.completedFuture(ResolvedText(m.speaker(), locale.tag()))
        )
        m.choices().forEach { requests.add(text(it.text)) }
        await(
            CompletableFuture.allOf(*requests.map { it.toCompletableFuture() }.toTypedArray()),
            options.budgets.text,
        ) { _, error ->
            if (!active() || visit != nodeVisit || textGeneration != generation) return@await
            if (error != null) {
                fail("TEXT_UNAVAILABLE")
                return@await
            }
            val resolved = requests.map { it.toCompletableFuture().join() }
            observeBounded(choices(m, resolved.subList(2, resolved.size))) { offers, choiceError ->
                if (!active() || visit != nodeVisit || textGeneration != generation)
                    return@observeBounded
                if (choiceError != null || offers == null) {
                    fail("CHOICES_UNAVAILABLE")
                    return@observeBounded
                }
                translating = false
                resolvedChoiceLabels =
                    m.choices().mapIndexed { i, c -> c.id to resolved[i + 2] }.toMap()
                var selected = previous
                if (
                    selected == null ||
                        offers.none {
                            it.id == previous && it.availability == Availability.AVAILABLE
                        }
                )
                    selected = initialSelection(m, offers)
                val line = resolved[0].plain
                val total = Graphemes.split(line).size
                val immediate = revealAll || preferences.graphemeDelay.isZero || total == 0
                enteredNanos = options.scheduler.nanoTime()
                setView(
                    if (immediate) State.WAITING_FOR_INPUT else State.REVEALING,
                    resolved[1].plain,
                    line,
                    offers,
                    selected,
                    if (immediate) total else 0,
                    if (immediate) hint(m, offers) else HintPhase.REVEAL,
                    "",
                )
                if (cue && m.gesture() != null && actor != null) {
                    val ac =
                        ActorPort.ActorCue(
                            m.speaker(),
                            m.gesture(),
                            visit,
                            "$id:$visit:${m.gesture()}",
                        )
                    await(call { actor!!.cue(ac) }, options.budgets.prepare) { _, e ->
                        if (
                            active() &&
                                e != null &&
                                start!!
                                    .content()
                                    .definition()
                                    .capabilities()
                                    .required()
                                    .contains("actor.gesture")
                        )
                            fail("ACTOR_CUE_FAILED")
                    }
                }
                publish()
                if (!immediate) scheduleReveal()
                if (refresh != null) {
                    refresh.complete(RefreshResult.UPDATED)
                    if (localePending === refresh) localePending = null
                }
            }
        }
    }

    private fun initialSelection(m: Message, offers: List<ChoiceView>): String? {
        m.choices()
            .firstOrNull { c ->
                c.defaultChoice &&
                    offers.any { it.id == c.id && it.availability == Availability.AVAILABLE }
            }
            ?.let {
                return it.id
            }
        return offers.firstOrNull { it.availability == Availability.AVAILABLE }?.id
    }

    private fun hint(m: Message, choices: List<ChoiceView>) =
        if (choices.isEmpty()) {
            if (start!!.content().definition().nodes()[m.next()] is End) HintPhase.FINISH
            else HintPhase.CONTINUE
        } else HintPhase.CHOOSE

    private fun scheduleReveal() {
        if (!active() || switching || translating || view.state() != State.REVEALING) return
        cancelReveal()
        val expected = visit
        val expectedReveal = revealGeneration
        revealTimer =
            options.scheduler.schedule(preferences.graphemeDelay) {
                queue.execute {
                    if (
                        !active() ||
                            visit != expected ||
                            revealGeneration != expectedReveal ||
                            switching ||
                            view.state() != State.REVEALING ||
                            translating
                    )
                        return@execute
                    val elapsed = options.scheduler.nanoTime() - enteredNanos
                    val count =
                        minOf(
                                view.totalGraphemes().toLong(),
                                maxOf(
                                    0L,
                                    if (preferences.graphemeDelay.isZero)
                                        view.totalGraphemes().toLong()
                                    else elapsed / preferences.graphemeDelay.toNanos(),
                                ),
                            )
                            .toInt()
                    val done = count >= view.totalGraphemes()
                    setView(
                        if (done) State.WAITING_FOR_INPUT else State.REVEALING,
                        view.speaker(),
                        view.text(),
                        view.choices(),
                        view.selectedChoiceId(),
                        count,
                        if (done) hint(current() as Message, view.choices()) else HintPhase.REVEAL,
                        view.notice(),
                    )
                    publish()
                    if (!done) scheduleReveal()
                }
            }
    }

    override fun submit(input: DialogueInput): CompletionStage<InputResult> {
        if (input is DialogueInput.Cancel) {
            stop(requireNotNull(input.reason))
            return CompletableFuture.completedFuture(InputResult.ACCEPTED)
        }
        if (inputCount.incrementAndGet() > options.inputQueueLimit) {
            inputCount.decrementAndGet()
            return CompletableFuture.completedFuture(InputResult.BUSY)
        }
        val result = CompletableFuture<InputResult>()
        queue.execute {
            inputCount.decrementAndGet()
            try {
                handleInput(input, result)
            } catch (e: Throwable) {
                result.completeExceptionally(e)
                unexpected(e)
            }
        }
        return result.minimalCompletionStage()
    }

    private fun handleInput(input: DialogueInput, result: CompletableFuture<InputResult>) {
        if (!active()) {
            result.complete(InputResult.ENDED)
            return
        }
        if (input is DialogueInput.RevealFinished) {
            result.complete(
                if (id == input.sessionId && renderRevision == input.renderRevision)
                    InputResult.ACCEPTED
                else InputResult.STALE
            )
            return
        }
        val stamp =
            checkNotNull(
                if (input is DialogueInput.Select) input.stamp
                else (input as DialogueInput.Confirm).stamp
            )
        if (
            id != stamp.sessionId ||
                stamp.renderRevision != renderRevision ||
                stamp.impulseId <= lastImpulse
        ) {
            result.complete(InputResult.STALE)
            return
        }
        lastImpulse = stamp.impulseId
        val m = current() as? Message
        if (
            translating ||
                switching ||
                confirming ||
                view.state() == State.WAITING_FOR_PROVIDER ||
                m == null
        ) {
            result.complete(InputResult.BUSY)
            return
        }
        if (input is DialogueInput.Select) {
            if (
                view.choices().none {
                    it.id == input.choiceId && it.availability == Availability.AVAILABLE
                }
            ) {
                result.complete(InputResult.INVALID)
                return
            }
            setView(
                view.state(),
                view.speaker(),
                view.text(),
                view.choices(),
                input.choiceId,
                view.revealedGraphemes(),
                view.hint(),
                view.notice(),
            )
            publish()
            result.complete(InputResult.ACCEPTED)
            return
        }
        if (view.state() == State.REVEALING) {
            invalidateTyping()
            cancelReveal()
            setView(
                State.WAITING_FOR_INPUT,
                view.speaker(),
                view.text(),
                view.choices(),
                view.selectedChoiceId(),
                view.totalGraphemes(),
                hint(m, view.choices()),
                view.notice(),
            )
            publish()
            result.complete(InputResult.REVEAL_ONLY)
            return
        }
        val confirm = input as DialogueInput.Confirm
        if (m.choices().isEmpty()) {
            if (confirm.choiceId != null) result.complete(InputResult.INVALID)
            else {
                enter(checkNotNull(m.next()))
                result.complete(InputResult.ACCEPTED)
            }
            return
        }
        if (confirm.choiceId != view.selectedChoiceId()) {
            result.complete(InputResult.INVALID)
            return
        }
        val chosen = m.choices().firstOrNull { it.id == confirm.choiceId }
        if (chosen == null) {
            result.complete(InputResult.INVALID)
            return
        }
        confirming = true
        pendingConfirm = result
        val expectedVisit = visit
        val expectedRender = renderRevision
        val oldOffers = view.choices()
        val selected = view.selectedChoiceId()
        val labels = m.choices().map { resolvedChoiceLabels[it.id]!! }
        setView(
            State.WAITING_FOR_PROVIDER,
            view.speaker(),
            view.text(),
            oldOffers,
            selected,
            view.totalGraphemes(),
            HintPhase.BUSY,
            "",
        )
        publish()
        observeBounded(choices(m, labels)) { offers, error ->
            if (!active()) {
                result.complete(InputResult.ENDED)
                return@observeBounded
            }
            confirming = false
            pendingConfirm = null
            if (visit != expectedVisit || renderRevision != expectedRender) {
                if (visit == expectedVisit && !translating && offers != null)
                    refreshOffers(
                        m,
                        offers.mapNotNull { o ->
                            resolvedChoiceLabels[o.id]?.let {
                                ChoiceView(
                                    o.id,
                                    it.plain,
                                    o.availability,
                                    o.unavailableReason,
                                )
                            }
                        },
                        "CHOICES_CHANGED",
                    )
                restoreMessageState()
                result.complete(InputResult.STALE)
                return@observeBounded
            }
            if (error != null || offers == null) {
                setView(
                    State.WAITING_FOR_INPUT,
                    view.speaker(),
                    view.text(),
                    oldOffers,
                    selected,
                    view.totalGraphemes(),
                    hint(m, oldOffers),
                    "CHOICES_UNAVAILABLE",
                )
                publish()
                result.complete(InputResult.BUSY)
                return@observeBounded
            }
            if (
                offers != oldOffers ||
                    offers.none {
                        it.id == selected && it.availability == Availability.AVAILABLE
                    }
            ) {
                renderRevision++
                val next =
                    if (
                        offers.any {
                            it.id == selected && it.availability == Availability.AVAILABLE
                        }
                    )
                        selected
                    else initialSelection(m, offers)
                setView(
                    State.WAITING_FOR_INPUT,
                    view.speaker(),
                    view.text(),
                    offers,
                    next,
                    view.totalGraphemes(),
                    hint(m, offers),
                    "CHOICES_CHANGED",
                )
                publish()
                result.complete(InputResult.STALE)
                return@observeBounded
            }
            enter(chosen.to)
            result.complete(InputResult.ACCEPTED)
        }
    }

    private fun refreshOffers(m: Message, offers: List<ChoiceView>, notice: String) {
        val previous = view.selectedChoiceId()
        val selected =
            if (offers.any { it.id == previous && it.availability == Availability.AVAILABLE })
                previous
            else initialSelection(m, offers)
        renderRevision++
        val busy = translating || confirming || switching
        setView(
            if (busy) State.WAITING_FOR_PROVIDER else State.WAITING_FOR_INPUT,
            view.speaker(),
            view.text(),
            offers,
            selected,
            view.totalGraphemes(),
            if (busy) HintPhase.BUSY else hint(m, offers),
            notice,
        )
        publish()
    }

    private fun restoreMessageState() {
        val m = current() as? Message ?: return
        if (!active() || translating || confirming || switching) return
        if (view.state() == State.WAITING_FOR_PROVIDER) {
            val reveal = view.revealedGraphemes() < view.totalGraphemes()
            setView(
                if (reveal) State.REVEALING else State.WAITING_FOR_INPUT,
                view.speaker(),
                view.text(),
                view.choices(),
                view.selectedChoiceId(),
                view.revealedGraphemes(),
                if (reveal) HintPhase.REVEAL else hint(m, view.choices()),
                view.notice(),
            )
            if (reveal) scheduleReveal()
        }
    }

    override fun refreshLocale(requested: LocaleContext): CompletionStage<RefreshResult> {
        val result = CompletableFuture<RefreshResult>()
        queue.execute {
            if (!active()) {
                result.complete(RefreshResult.ENDED)
                return@execute
            }
            if (requested.revision() <= locale.revision()) {
                result.complete(RefreshResult.STALE)
                return@execute
            }
            invalidateTyping()
            localePending?.complete(RefreshResult.STALE)
            localePending = result
            locale = effectiveLocale(requested)
            renderRevision++
            cancelReveal()
            if (current() is Message) resolveMessage(true, false, result)
            else {
                setView(
                    view.state(),
                    view.speaker(),
                    view.text(),
                    view.choices(),
                    view.selectedChoiceId(),
                    view.revealedGraphemes(),
                    view.hint(),
                    view.notice(),
                )
                publish()
                result.complete(RefreshResult.UPDATED)
                localePending = null
            }
        }
        return result.minimalCompletionStage()
    }

    override fun updatePreferences(next: PresentationPreferences): CompletionStage<Void> {
        val result = CompletableFuture<Void>()
        queue.execute {
            if (!active()) {
                result.completeExceptionally(DialogueFailure("ENDED", "Session ended"))
                return@execute
            }
            preferenceResults.removeIf { it.isDone() }
            preferenceResults.add(result)
            val previous = preferences
            val generation = ++preferenceGeneration
            switching = true
            invalidateTyping()
            renderRevision++
            cancelReveal()
            await(call { renderer!!.reconfigure(next) }, options.budgets.prepare) { _, error ->
                if (!active()) {
                    result.completeExceptionally(DialogueFailure("ENDED", "Session ended"))
                    return@await
                }
                if (generation != preferenceGeneration) {
                    result.completeExceptionally(CancellationException("Superseded preferences"))
                    return@await
                }
                switching = false
                val e = error?.let { OwnedScope.unwrap(it) }
                if (e is DialogueRenderer.ReconfigurationRejected)
                    preferences = e.activePreferences() ?: previous
                else if (e != null) {
                    result.completeExceptionally(e)
                    fail("PRESENTATION_HANDOFF_FAILED")
                    return@await
                } else preferences = next
                val m = current() as? Message
                if (m != null && !translating && view.revealedGraphemes() < view.totalGraphemes()) {
                    if (preferences.graphemeDelay.isZero)
                        setView(
                            if (confirming) State.WAITING_FOR_PROVIDER else State.WAITING_FOR_INPUT,
                            view.speaker(),
                            view.text(),
                            view.choices(),
                            view.selectedChoiceId(),
                            view.totalGraphemes(),
                            if (confirming) HintPhase.BUSY else hint(m, view.choices()),
                            view.notice(),
                        )
                    else {
                        enteredNanos =
                            options.scheduler.nanoTime() -
                                view.revealedGraphemes() * preferences.graphemeDelay.toNanos()
                        if (!confirming)
                            setView(
                                State.REVEALING,
                                view.speaker(),
                                view.text(),
                                view.choices(),
                                view.selectedChoiceId(),
                                view.revealedGraphemes(),
                                HintPhase.REVEAL,
                                view.notice(),
                            )
                        scheduleReveal()
                    }
                }
                restoreMessageState()
                setView(
                    view.state(),
                    view.speaker(),
                    view.text(),
                    view.choices(),
                    view.selectedChoiceId(),
                    view.revealedGraphemes(),
                    view.hint(),
                    view.notice(),
                )
                publish().whenComplete { _, renderError ->
                    if (e != null) result.completeExceptionally(e)
                    else if (renderError != null) result.completeExceptionally(renderError)
                    else if (generation != preferenceGeneration)
                        result.completeExceptionally(
                            CancellationException("Superseded preferences")
                        )
                    else result.complete(null)
                }
            }
        }
        return result.minimalCompletionStage()
    }

    override fun snapshot() = view

    override fun completion(): CompletionStage<DialogueEnd> = endObservation

    override fun stop(reason: DialogueEndReason): CompletionStage<DialogueEnd> {
        val admitted = synchronized(dispatchGate) { stopping.compareAndSet(null, reason) }
        if (admitted) {
            var failure: Throwable? = null
            try {
                cancellation.cancel(reason)
            } catch (e: RuntimeException) {
                failure = e
            }
            val callbackFailure = failure
            queue.execute {
                if (callbackFailure != null && startupCode == null)
                    startupCode = "CANCEL_CALLBACK_FAILED"
                stopNow()
            }
        }
        return endObservation
    }

    private fun stopNow() {
        if (closing) return
        closing = true
        invalidateTyping()
        val e = CancellationException("Session closing")
        renderAcknowledgements.forEach { it.completeExceptionally(e) }
        renderAcknowledgements.clear()
        preferenceResults.forEach { it.completeExceptionally(e) }
        preferenceResults.clear()
        waits.forEach { it.cancel() }
        waits.clear()
        cancelReveal()
        localePending?.complete(RefreshResult.ENDED)
        localePending = null
        pendingConfirm?.complete(InputResult.ENDED)
        pendingConfirm = null
        confirming = false
        if (stopping.get() == null) stopping.compareAndSet(null, DialogueEndReason.CANCELLED)
        renderRevision++
        setView(State.CLOSING, "", "", emptyList(), null, 0, HintPhase.BUSY, "")
        scope.closeAsync().whenComplete { _, error -> queue.execute { finish(error) } }
    }

    private fun finish(error: Throwable?) {
        setView(
            State.CLOSED,
            "",
            "",
            emptyList(),
            null,
            0,
            HintPhase.BUSY,
            if (error == null) startupCode ?: "" else "CLEANUP_FAILED",
        )
        start = null
        renderer = null
        actor = null
        resolvedChoiceLabels = emptyMap()
        started.complete(
            DialogueStartResult.Rejected(
                if (error == null) startupCode ?: stopping.get().name else "CLEANUP_FAILED",
                emptyList(),
            )
        )
        if (error != null) ended.completeExceptionally(error)
        else ended.complete(DialogueEnd(id, stopping.get(), pendingActions.values.toList()))
        pendingActions.clear()
    }
}
