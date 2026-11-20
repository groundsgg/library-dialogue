package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueHandle
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RefreshResult
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.input.ChatInputTokens
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.ConnectionGuard
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.minestom.internal.MinestomDialogueSession
import gg.grounds.dialogue.minestom.render.ChatDialogueSurface
import gg.grounds.dialogue.minestom.render.HudDialogueSurface
import gg.grounds.dialogue.minestom.render.MinestomDialogueRenderer
import gg.grounds.dialogue.minestom.render.NpcDialogueSurface
import gg.grounds.dialogue.minestom.render.PresentationStatus
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.LayoutProfile
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.event.player.PlayerResourcePackStatusEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.player.PlayerTickEvent

/** Owns local dialogue lifetimes. The application supplies identity and language authority. */
class MinestomDialogueHost
internal constructor(
    private val options: MinestomDialogueOptions,
    rendererFactory: RendererFactory?,
) {
    internal fun interface RendererFactory {
        fun create(
            request: MinestomDialogueRequest,
            guard: ConnectionGuard,
            clock: DialogueScheduler,
            status: Consumer<PresentationStatus>,
        ): DialogueRenderer
    }

    constructor(options: MinestomDialogueOptions) : this(options, null)

    private val lock = Any()
    private val renderers = rendererFactory ?: RendererFactory(::defaultRenderer)
    private val starts = HashMap<String, Ticket>()
    private val active = IdentityHashMap<Player, Ticket>()
    private val failedOwners = ArrayList<MinestomDialogueSession>()
    private val events = EventNode.all("dialogue-" + UUID.randomUUID())
    private val packs = PackStateTracker()
    private val inputRoute =
        Command("dialogue_input_" + UUID.randomUUID().toString().replace("-", ""))
    private var closeView: CompletionStage<Void>? = null

    init {
        inputRoute.setDefaultExecutor { sender, context ->
            if (sender !is Player) return@setDefaultExecutor
            val args = context.input.trim().split(COMMAND_SPACE)
            if (args.size != 2 || !CHAT_TOKEN.matches(args[1])) return@setDefaultExecutor
            current(sender)?.session?.chat(args[1])
        }
        MinecraftServer.getCommandManager().register(inputRoute)
        events.addListener(PlayerDisconnectEvent::class.java) { event ->
            packs.forget(event.player)
            current(event.player)?.cancel(DialogueEndReason.DISCONNECTED)
        }
        events.addListener(PlayerTickEvent::class.java) { event ->
            current(event.player)?.session?.let {
                it.checkConnection()
                it.tickInput()
            }
        }
        events.addListener(PlayerSpawnEvent::class.java) { event ->
            current(event.player)?.session?.checkConnection()
        }
        events.addListener(PlayerResourcePackStatusEvent::class.java) { event ->
            packs.observe(event.player, event.packUuid, event.status)
            current(event.player)?.session?.refreshPresentation()
        }
        events.addListener(PlayerPacketEvent::class.java) { event ->
            current(event.player)?.session?.packet(event)
        }
        MinecraftServer.getGlobalEventHandler().addChild(events)
    }

    fun packState(): PackStateTracker = packs

    // JVM name used by the local protocol fixtures.
    fun inputRoute(): String = "/" + inputRoute.name

    private fun defaultRenderer(
        request: MinestomDialogueRequest,
        guard: ConnectionGuard,
        clock: DialogueScheduler,
        status: Consumer<PresentationStatus>,
    ): DialogueRenderer {
        val targets = SurfaceInputTargets()
        val tokens =
            ChatInputTokens(request.player(), request.hostGeneration(), targets, clock::nanoTime)
        val controls =
            DialoguePresenter.Controls(
                DialoguePresenter.ConfirmAction.valueOf(request.controls().primaryConfirm().name),
                request.controls().scroll(),
            )
        val chat =
            ChatDialogueSurface(
                guard,
                targets,
                tokens,
                {
                    request.player().sendMessage(it)
                    CompletableFuture.completedFuture(null)
                },
                options.labels,
                controls,
                inputRoute(),
            )
        val diagnostics =
            Consumer<String> { code ->
                System.getLogger(MinestomDialogueHost::class.java.name)
                    .log(
                        System.Logger.Level.DEBUG,
                        "Dialogue presentation degraded (reason=" + code + ")",
                    )
            }
        val hud =
            HudDialogueSurface(
                request.player(),
                request.hostGeneration(),
                guard,
                packs,
                options.assets,
                options.channels,
                targets,
                clock,
                diagnostics,
            )
        val lastSpeaker = AtomicReference(request.speakers().values.singleOrNull())
        val npc =
            NpcDialogueSurface(
                request.player(),
                request.hostGeneration(),
                guard,
                packs,
                options.assets,
                options.channels,
                targets,
                clock,
                { view ->
                    val node = request.content().definition().nodes()[view.nodeId()]
                    if (node is Message) lastSpeaker.set(request.speakers()[node.speaker()])
                    lastSpeaker.get()
                },
                diagnostics,
            )
        val profiles =
            mapOf(
                "chat" to DialoguePresenter.SurfaceProfile.semantic(chat),
                "hud" to
                    DialoguePresenter.SurfaceProfile(
                        hud,
                        LayoutProfile.compactHud(LayoutProfile.ChoicePlacement.BELOW),
                        DialogueTheme.hudPrototype(),
                        options.assets.hudMetrics(),
                        false,
                        ::hudLayout,
                    ),
                "npc" to
                    DialoguePresenter.SurfaceProfile(
                        npc,
                        LayoutProfile.npc(),
                        DialogueTheme.neutral(),
                        options.assets.metrics(),
                        false,
                        { LayoutProfile.npc(NpcPanelOptions.from(it).choiceGap) },
                    ),
            )
        return MinestomDialogueRenderer(
            profiles,
            clock,
            controls,
            options.labels,
            targets,
            status,
            tokens,
            npc::geometry,
        )
    }

    private fun current(player: Player): Ticket? = synchronized(lock) { active[player] }

    fun begin(request: MinestomDialogueRequest): MinestomDialogueStart {
        val identity = identity(request)
        val admissionInstance = request.player().instance
        val previous: Ticket?
        val ticket =
            synchronized(lock) {
                val old = starts[request.requestId()]
                if (old != null) {
                    if (old.identity == identity && old.player.get() === request.player())
                        return old
                    return rejected(request, "START_REQUEST_CONFLICT")
                }
                if (closeView != null) return rejected(request, "HOST_CLOSED")
                if (starts.size >= 65_536) return rejected(request, "START_REGISTER_FULL")
                val candidate = Ticket(request, identity, ::finished)
                starts[request.requestId()] = candidate
                previous = if (admissionInstance == null) null else active[request.player()]
                if (previous != null) candidate.predecessor = previous.closeObservation
                if (admissionInstance != null) active[request.player()] = candidate
                candidate
            }
        if (admissionInstance == null) {
            ticket.reject("HOST_CHANGED", null)
            return ticket
        }
        val before =
            previous?.cancel(DialogueEndReason.SUPERSEDED)
                ?: CompletableFuture.completedFuture(null)
        before.whenComplete { _, error ->
            if (error != null) {
                ticket.reject("CLEANUP_FAILED", error)
                return@whenComplete
            }
            if (ticket.cancelled()) return@whenComplete
            val guard =
                ConnectionGuard(request.player(), request.hostGeneration(), admissionInstance)
            if (!guard.isCurrent()) {
                ticket.reject("HOST_CHANGED", null)
                return@whenComplete
            }
            try {
                val clock = MinestomDialogueScheduler(admissionInstance)
                val renderer = renderers.create(request, guard, clock, ticket::status)
                val session =
                    MinestomDialogueSession(
                        request,
                        options,
                        guard,
                        clock,
                        renderer,
                        ticket.token,
                        ticket::ready,
                        ticket::settled,
                    )
                if (ticket.attach(session)) session.begin()
            } catch (failure: Throwable) {
                ticket.reject("START_FAILED", failure)
            }
        }
        return ticket
    }

    private fun finished(ticket: Ticket, failed: MinestomDialogueSession?) {
        synchronized(lock) {
            val player = ticket.player.get()
            if (player != null && !ticket.close.isCompletedExceptionally && !ticket.cleanupFailed) {
                active.remove(player, ticket)
            }
            if (failed != null) failedOwners.add(failed)
        }
    }

    private fun rejected(request: MinestomDialogueRequest, code: String): Ticket =
        Ticket(request, "") { _, _ -> }.also { it.reject(code, null) }

    fun closeAsync(): CompletionStage<Void> {
        val closed = CompletableFuture<Void>()
        val observation = closed.minimalCompletionStage()
        val tickets =
            synchronized(lock) {
                closeView?.let {
                    return it
                }
                closeView = observation
                starts.values.toList()
            }
        MinecraftServer.getGlobalEventHandler().removeChild(events)
        MinecraftServer.getCommandManager().unregister(inputRoute)
        packs.clear()
        val pending = tickets.map { it.cancel(DialogueEndReason.HOST_CLOSED).toCompletableFuture() }
        CompletableFuture.allOf(*pending.toTypedArray()).whenComplete { _, error ->
            synchronized(lock) {
                starts.clear()
                active.clear()
            }
            if (error == null) closed.complete(null) else closed.completeExceptionally(error)
        }
        return observation
    }

    private class Ticket(
        request: MinestomDialogueRequest,
        val identity: String,
        private var finished: ((Ticket, MinestomDialogueSession?) -> Unit)?,
    ) : MinestomDialogueStart {
        private val lock = Any()
        val player = WeakReference(request.player())
        val token = CancellationToken()
        private val cancelReason = AtomicReference<DialogueEndReason>()
        private val result = CompletableFuture<DialogueStartResult>()
        private val observation = result.minimalCompletionStage()
        val close = CompletableFuture<Void>()
        val closeObservation = close.minimalCompletionStage()
        @Volatile
        private var status =
            PresentationStatus(request.preferences().surfaceKey(), "", "PREPARING", 0)
        @Volatile
        var session: MinestomDialogueSession? = null
            private set

        var cleanupFailed = false
            private set

        private var settled = false
        private var applyingIntents = false
        private var running: DialogueHandle? = null
        private var pendingLocale: LocaleContext? = null
        private var pendingPreferences: PresentationPreferences? = null
        private var localeRevision = request.locale().revision()
        private val intentWaiters = ArrayList<CompletableFuture<Void>>()
        var predecessor: CompletionStage<Void> = CompletableFuture.completedFuture(null)

        fun cancelled(): Boolean = cancelReason.get() != null || token.isCancelled() || close.isDone

        fun attach(value: MinestomDialogueSession): Boolean =
            synchronized(lock) {
                if (cancelled()) return false
                session = value
                true
            }

        override fun result(): CompletionStage<DialogueStartResult> = observation

        override fun status(): PresentationStatus = status

        fun status(value: PresentationStatus) {
            synchronized(lock) {
                if (!settled && value.surfaceGeneration >= status.surfaceGeneration) status = value
            }
        }

        fun ready(value: DialogueStartResult) {
            if (value !is DialogueStartResult.Started) {
                result.complete(value)
                return
            }
            synchronized(lock) {
                if (settled) return
                running = value.handle
            }
            applyIntents()
        }

        override fun updatePreferences(
            preferences: PresentationPreferences
        ): CompletionStage<Void> {
            val observer = CompletableFuture<Void>()
            synchronized(lock) {
                if (settled || cancelled()) {
                    return CompletableFuture.failedFuture(CancellationException("Dialogue ended"))
                }
                if (intentWaiters.size >= 256) {
                    return CompletableFuture.failedFuture(IllegalStateException("INTENT_LIMIT"))
                }
                pendingPreferences = preferences
                intentWaiters.add(observer)
            }
            applyIntents()
            return observer.minimalCompletionStage()
        }

        override fun refreshLocale(locale: LocaleContext): CompletionStage<RefreshResult> {
            val observer = CompletableFuture<Void>()
            synchronized(lock) {
                if (settled || cancelled())
                    return CompletableFuture.completedFuture(RefreshResult.ENDED)
                if (locale.revision() <= localeRevision)
                    return CompletableFuture.completedFuture(RefreshResult.STALE)
                if (intentWaiters.size >= 256) {
                    return CompletableFuture.failedFuture(IllegalStateException("INTENT_LIMIT"))
                }
                localeRevision = locale.revision()
                pendingLocale = locale
                intentWaiters.add(observer)
            }
            applyIntents()
            return observer.thenApply { RefreshResult.UPDATED }.minimalCompletionStage()
        }

        private fun applyIntents() {
            val locale: LocaleContext?
            val preferences: PresentationPreferences?
            val observers: List<CompletableFuture<Void>>
            val owner =
                synchronized(lock) {
                    val handle = running
                    if (settled || cancelled() || handle == null || applyingIntents) return
                    locale = pendingLocale
                    preferences = pendingPreferences
                    pendingLocale = null
                    pendingPreferences = null
                    observers = intentWaiters.toList()
                    intentWaiters.clear()
                    applyingIntents = observers.isNotEmpty()
                    handle
                }
            if (observers.isEmpty()) {
                result.complete(DialogueStartResult.Started(owner))
                return
            }
            var work: CompletionStage<*> =
                if (locale == null) CompletableFuture.completedFuture(null)
                else owner.refreshLocale(locale)
            if (preferences != null)
                work = work.thenCompose { owner.updatePreferences(preferences) }
            work.whenComplete { _, error ->
                synchronized(lock) { applyingIntents = false }
                if (error != null) {
                    observers.forEach { it.completeExceptionally(error) }
                    cancel(DialogueEndReason.FAILED)
                } else {
                    observers.forEach { it.complete(null) }
                    applyIntents()
                }
            }
        }

        override fun cancel(reason: DialogueEndReason): CompletionStage<Void> {
            val current =
                synchronized(lock) {
                    cancelReason.compareAndSet(null, reason)
                    session
                }
            val selectedReason = checkNotNull(cancelReason.get())
            try {
                token.cancel(selectedReason)
            } catch (failure: Throwable) {
                if (current == null) {
                    reject("CANCELLATION_FAILED", failure)
                    return closeObservation
                }
            }
            if (current != null) current.cancel(selectedReason)
            else if (!close.isDone) reject(checkNotNull(token.reason()).name, null)
            return closeObservation
        }

        fun reject(code: String, error: Throwable?) {
            result.complete(DialogueStartResult.Rejected(code, emptyList()))
            predecessor.whenComplete { _, beforeError -> settled(null, beforeError ?: error) }
        }

        fun settled(source: MinestomDialogueSession?, error: Throwable?) {
            val abandoned: List<CompletableFuture<Void>>
            val callback =
                synchronized(lock) {
                    if (settled) return
                    settled = true
                    cleanupFailed = error != null
                    session = null
                    running = null
                    pendingLocale = null
                    pendingPreferences = null
                    abandoned = intentWaiters.toList()
                    intentWaiters.clear()
                    finished.also { finished = null }
                }
            abandoned.forEach { it.completeExceptionally(CancellationException("Dialogue ended")) }
            if (!result.isDone) {
                val code =
                    if (error != null) "CLEANUP_FAILED" else token.reason()?.name ?: "START_FAILED"
                result.complete(DialogueStartResult.Rejected(code, emptyList()))
            }
            callback?.invoke(this, if (error == null) null else source)
            status =
                PresentationStatus(
                    status.requestedSurface,
                    "",
                    if (error == null) "CLOSED" else "CLEANUP_FAILED",
                    status.surfaceGeneration,
                )
            if (error == null) close.complete(null) else close.completeExceptionally(error)
        }
    }

    private companion object {
        val COMMAND_SPACE = Regex("\\s+")
        val CHAT_TOKEN = Regex("[A-Za-z0-9_-]{22}")

        fun hudLayout(preferences: PresentationPreferences): LayoutProfile =
            try {
                val effects = preferences.effects()
                val placement =
                    LayoutProfile.ChoicePlacement.valueOf(
                        effects.getOrDefault("hudChoices", "below").uppercase(Locale.ROOT)
                    )
                val style =
                    when (effects.getOrDefault("hudStyle", "shared").lowercase(Locale.ROOT)) {
                        "shared" -> LayoutProfile.Style.HUD
                        "split" -> LayoutProfile.Style.HUD_SPLIT
                        "cards" -> LayoutProfile.Style.HUD_CARDS
                        else -> throw IllegalArgumentException("Unknown HUD style")
                    }
                LayoutProfile.compactHud(placement, style)
            } catch (_: IllegalArgumentException) {
                throw DialoguePresenter.FallbackRequired("UNSUPPORTED_RASTER")
            }

        fun identity(request: MinestomDialogueRequest): String {
            val preferences = request.preferences()
            val controls = request.controls()
            val locale = request.locale()
            val values =
                mapOf(
                    "player" to request.player().uuid.toString(),
                    "hostGeneration" to request.hostGeneration(),
                    "content" to request.content().contentPin().sha256,
                    "author" to request.content().catalog().generation(),
                    "policy" to request.content().limits().toString(),
                    "services" to request.services().generation(),
                    "speakers" to
                        request.speakers().mapValues { (_, binding) ->
                            mapOf(
                                "generation" to binding.generation,
                                "instance" to binding.instance.uuid.toString(),
                            )
                        },
                    "actors" to (request.actors() != null),
                    "capabilities" to request.services().capabilities().sorted(),
                    "locale" to listOf(locale.tag(), locale.revision(), locale.fallbackTag()),
                    "preferences" to
                        mapOf(
                            "surface" to preferences.surfaceKey(),
                            "delay" to preferences.graphemeDelay().toNanos(),
                            "reducedMotion" to preferences.reducedMotion(),
                            "effects" to preferences.effects(),
                        ),
                    "context" to ContentFingerprint.argumentData(request.context()),
                    "controls" to
                        mapOf(
                            "open" to controls.open().map { it.name }.sorted(),
                            "confirm" to controls.confirm().map { it.name }.sorted(),
                            "primary" to controls.primaryConfirm().name,
                            "scroll" to controls.scroll(),
                            "quiet" to controls.quietPeriod().toNanos(),
                        ),
                    "openingImpulse" to request.openingImpulse(),
                )
            return ContentFingerprint.hash(ContentFingerprint.canonicalBytes(values)).sha256
        }
    }
}
