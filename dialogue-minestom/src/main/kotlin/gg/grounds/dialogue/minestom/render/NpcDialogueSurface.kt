package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.internal.OwnedScope
import gg.grounds.dialogue.core.internal.SerialCommandQueue
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueFailure
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding
import gg.grounds.dialogue.minestom.NpcPanelOptions
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.DIALOGUE_VIGNETTE_KEYS
import gg.grounds.dialogue.minestom.channel.NativeVignetteBank
import gg.grounds.dialogue.minestom.channel.OutputChannels
import gg.grounds.dialogue.minestom.geometry.NpcPanelTransform
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.WidgetFrame
import java.time.Duration
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Function
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.play.BundlePacket

/**
 * Private world panels around a borrowed NPC anchor, sharing [scope] and [targets]. Open and frame
 * identities are checked before native work. A speaker binding must refer to the scope's exact
 * instance before its snapshot is inspected or it becomes a follow/cleanup source. Foreign bindings
 * leave previously accepted output usable. Genuine anchor loss retains the existing host-change
 * stop behavior. Owns private displays and leases, never the shared NPC; close retires input and
 * releases late acquisitions through [clock].
 */
class NpcDialogueSurface
internal constructor(
    private val scope: MinestomConnectionScope,
    private val packs: PackStateTracker,
    private val assets: AssetBundle,
    private val channels: OutputChannels,
    private val targets: SurfaceInputTargets,
    private val clock: DialogueScheduler,
    private val speaker: Function<DialogueView, MinestomSpeakerBinding?>,
    private val diagnostic: Consumer<String>,
    private val spawn: SpawnOperation,
) : DialogueSurface {
    private val player = scope.player
    private val generation = scope.generation

    internal fun interface SpawnOperation {
        fun spawn(entity: Entity, instance: Instance, position: Pos): CompletionStage<Void>
    }

    constructor(
        scope: MinestomConnectionScope,
        packs: PackStateTracker,
        assets: AssetBundle,
        channels: OutputChannels,
        targets: SurfaceInputTargets,
        clock: DialogueScheduler,
        speaker: Function<DialogueView, MinestomSpeakerBinding?>,
        diagnostic: Consumer<String>,
    ) : this(
        scope,
        packs,
        assets,
        channels,
        targets,
        clock,
        speaker,
        diagnostic,
        Entity::setInstance,
    )

    class Geometry(
        private val transform: NpcPanelTransform,
        fronts: List<WidgetFrame.Rect>,
        private val surfaceGeneration: Long,
        choiceBounds: Map<String, WidgetFrame.Rect>,
    ) {
        private val fronts = java.util.List.copyOf(fronts)
        private val choiceBounds = java.util.Map.copyOf(choiceBounds)

        constructor(
            transform: NpcPanelTransform,
            fronts: List<WidgetFrame.Rect>,
            surfaceGeneration: Long,
        ) : this(transform, fronts, surfaceGeneration, emptyMap())

        fun transform(): NpcPanelTransform = transform

        fun fronts(): List<WidgetFrame.Rect> = fronts

        fun surfaceGeneration(): Long = surfaceGeneration

        fun choiceBounds(): Map<String, WidgetFrame.Rect> = choiceBounds

        override fun equals(other: Any?): Boolean =
            other is Geometry &&
                transform == other.transform &&
                fronts == other.fronts &&
                surfaceGeneration == other.surfaceGeneration &&
                choiceBounds == other.choiceBounds

        override fun hashCode(): Int =
            ((31 * transform.hashCode() + fronts.hashCode()) * 31 + surfaceGeneration.hashCode()) *
                31 + choiceBounds.hashCode()

        override fun toString(): String =
            "Geometry[transform=" +
                transform +
                ", fronts=" +
                fronts +
                ", surfaceGeneration=" +
                surfaceGeneration +
                ", choiceBounds=" +
                choiceBounds +
                "]"
    }

    internal fun requireOwner(scope: MinestomConnectionScope, targets: SurfaceInputTargets) {
        require(this.scope === scope && this.targets === targets) {
            "Native surface owner mismatch"
        }
    }

    private val encoder = MinecraftWidgetEncoder()
    private val geometries = ConcurrentHashMap<Long, Geometry>()
    private val displayCount = synchronized(COUNTS) { COUNTS.getOrPut(player, ::AtomicInteger) }

    fun geometry(): Geometry? = targets.current()?.let { geometries[it.surfaceGeneration()] }

    private fun nextNativeBank(): NativeVignetteBank =
        synchronized(NATIVE_BANKS) {
            val bank = NATIVE_BANKS[player]?.other() ?: NativeVignetteBank.A
            NATIVE_BANKS[player] = bank
            bank
        }

    private fun available(): Boolean =
        scope.isCurrent() && packs.isReady(player, generation, assets.pin())

    override fun open(
        context: DialogueSurface.SurfaceContext
    ): CompletionStage<DialogueSurface.SurfaceHandle> {
        if (
            context.audienceId != scope.player.uuid.toString() ||
                context.hostGeneration != scope.generation
        ) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("Native context owner mismatch")
            )
        }
        return if (available()) CompletableFuture.completedFuture(Handle(context))
        else CompletableFuture.failedFuture(DialoguePresenter.FallbackRequired("MISSING_PACK"))
    }

    private inner class Display : OwnedResource {
        val entity: Entity
        private val removed = AtomicBoolean()

        init {
            if (displayCount.incrementAndGet() > 32) {
                displayCount.decrementAndGet()
                throw DialogueFailure("DISPLAY_LIMIT", "NPC connection display budget exceeded")
            }
            entity = Entity(EntityType.TEXT_DISPLAY)
            entity.setAutoViewable(false)
            entity.setNoGravity(true)
            entity.setHasPhysics(false)
            val meta = entity.entityMeta as TextDisplayMeta
            meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.FIXED)
            meta.setBrightness(15, 15)
            meta.setLineWidth(32_767)
            meta.setAlignLeft(true)
            meta.setUseDefaultBackground(false)
            meta.setBackgroundColor(0)
            meta.setShadow(false)
            meta.setSeeThrough(false)
            meta.setWidth(12f)
            meta.setHeight(8f)
            meta.setText(Component.empty())
        }

        override fun closeAsync(): CompletionStage<Void> {
            if (removed.compareAndSet(false, true)) {
                entity.removeViewer(player)
                entity.remove()
                displayCount.decrementAndGet()
            }
            return CompletableFuture.completedFuture(null)
        }
    }

    private data class PanelSlot(val kind: WidgetFrame.Kind, val key: String)

    private fun panelSlots(frame: EncodedFrame): Map<PanelSlot, EncodedFrame.EncodedWidget> {
        var choice = 0
        return frame.widgets().associateByTo(LinkedHashMap()) { widget ->
            PanelSlot(
                widget.kind,
                if (widget.kind == WidgetFrame.Kind.CHOICE) (choice++).toString() else widget.id,
            )
        }
    }

    private inner class Handle(private val context: DialogueSurface.SurfaceContext) :
        DialogueSurface.SurfaceHandle {
        private val writes = ArrayList<CompletableFuture<Void>>()
        private val closed = AtomicBoolean()
        private val cleanup = CompletableFuture<Void>()
        private val cleanupView = cleanup.minimalCompletionStage()
        private val panels = LinkedHashMap<PanelSlot, PanelPair>()
        private var preferences = context.preferences
        private val queue =
            SerialCommandQueue(clock) { error ->
                writes.forEach { it.completeExceptionally(error) }
                if (closed.get()) finishCleanup(error)
                else {
                    context.cancellation.cancel(DialogueEndReason.FAILED)
                    closeAsync()
                }
            }
        private val resources = OwnedScope(queue, clock, context.cleanupBudget)
        private var binding: MinestomSpeakerBinding? = null
        private var transform: NpcPanelTransform? = null
        private var appliedAngle = Double.NaN
        private var hingePosition = 0.0
        private var appliedHinge = Double.NaN
        private var follow: DialogueScheduler.ScheduledTask? = null
        private var backdrop: PanelPair? = null
        private var backdropPreparation: CompletableFuture<Void>? = null
        private var vignetteLevel = NpcEffectLevel()
        private val hasVignetteSteps =
            DIALOGUE_VIGNETTE_KEYS.dropLast(1).all {
                assets.hasEntry("assets/dialogue/post_effect/${it.value()}.json")
            }
        private val hasNativeVignette = NATIVE_VIGNETTE_ASSETS.all {
            assets.hasEntry("assets/dialogue/$it")
        }
        private var nativeBank: NativeVignetteBank? = null
        private var effectRevision = 0L
        private var nativeCloseStarted = 0L
        private var nativeCleanupDeadline: Long? = null
        private var fadeDuration = Duration.ZERO
        private var fadeTask: DialogueScheduler.ScheduledTask? = null
        private var fadeGeneration = 0L
        private var effectStep = 0
        private var effectWriting = false
        private var hud: OutputChannels.HudLease? = null
        private var effects: OutputChannels.EffectLease? = null
        private var effectsPreparation: CompletableFuture<Void>? = null
        private var epoch = 0L
        private var nextHudRefresh = 0L
        private var hudRefreshing = false
        private var movement: CompletionStage<Void> = CompletableFuture.completedFuture(null)

        private inner class PanelPair(position: Pos) {
            val displays = arrayOfNulls<Display>(2)
            val ready = CompletableFuture<Void>()
            var shown = false
            var choice: String? = null

            init {
                val spawns =
                    displays.indices.map { index ->
                        create(position).thenAccept { displays[index] = it }.toCompletableFuture()
                    }
                CompletableFuture.allOf(*spawns.toTypedArray()).whenComplete { _, error ->
                    if (error == null) ready.complete(null) else ready.completeExceptionally(error)
                }
            }

            fun release(): CompletionStage<Void> {
                val result = CompletableFuture<Void>()
                ready.whenComplete { _, creationError ->
                    queue.execute {
                        val removals =
                            displays.filterNotNull().map {
                                resources.release(it).toCompletableFuture()
                            }
                        CompletableFuture.allOf(*removals.toTypedArray()).whenComplete {
                            _,
                            removalError ->
                            val error = removalError ?: creationError
                            if (error == null) result.complete(null)
                            else result.completeExceptionally(error)
                        }
                    }
                }
                return result.minimalCompletionStage()
            }
        }

        override fun configure(preferences: PresentationPreferences): CompletionStage<Void> {
            val result = CompletableFuture<Void>()
            queue.execute {
                if (closed.get()) result.completeExceptionally(CancellationException())
                else {
                    this.preferences = preferences
                    result.complete(null)
                }
            }
            return result.minimalCompletionStage()
        }

        private fun create(position: Pos): CompletionStage<Display> = resources.prepare {
            val display = Display()
            val result = CompletableFuture<Display>()
            try {
                spawn.spawn(display.entity, scope.instance, position).whenComplete { _, error ->
                    if (error == null) result.complete(display)
                    else
                        display.closeAsync().whenComplete { _, cleanupError ->
                            result.completeExceptionally(
                                if (cleanupError == null) error
                                else OwnedResource.PreparationFailure(cleanupError)
                            )
                        }
                }
            } catch (error: Throwable) {
                display.closeAsync()
                result.completeExceptionally(error)
            }
            result
        }

        override fun replace(frame: WidgetFrame): CompletionStage<Void> {
            if (frame.semantic().sessionId() != context.sessionId) {
                return CompletableFuture.failedFuture(
                    IllegalArgumentException("Native frame session mismatch")
                )
            }
            val result = CompletableFuture<Void>()
            queue.execute {
                writes.removeIf { it.isDone }
                writes.add(result)
                if (closed.get() || context.cancellation.isCancelled()) {
                    result.completeExceptionally(CancellationException())
                    return@execute
                }
                if (!available()) {
                    result.completeExceptionally(DialoguePresenter.FallbackRequired("MISSING_PACK"))
                    return@execute
                }
                try {
                    replacePanel(frame, result)
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
            return result.minimalCompletionStage()
        }

        private fun replacePanel(frame: WidgetFrame, result: CompletableFuture<Void>) {
            val speaker =
                speaker.apply(frame.semantic())
                    ?: throw NullPointerException("Missing logical speaker binding")
            require(speaker.instance === scope.instance) { "NPC binding owner mismatch" }
            val snapshot = speaker.snapshot.get()
            if (!snapshot.valid) {
                context.cancellation.cancel(DialogueEndReason.HOST_CHANGED)
                throw CancellationException("NPC anchor changed")
            }
            binding = speaker
            val encoded = encoder.encode(frame, assets)
            val verticalOffset = verticalOffset(preferences)
            val focus = VisualOptions.enabled(preferences, "npcBackdrop", diagnostic)
            val hide = VisualOptions.enabled(preferences, "hideHud", diagnostic)
            val vignette = VisualOptions.enabled(preferences, "vignette", diagnostic)
            fadeDuration = effectFadeDuration()
            if (encoded.widgets().size * 2 + (if (focus) 2 else 0) > 32) {
                throw DialogueFailure("DISPLAY_LIMIT", "NPC display budget exceeded")
            }
            val keepBackdrop = focus
            val operation = ++epoch
            follow?.cancel()
            follow = null
            targets.invalidateCurrent(context.generation)
            val height =
                frame.widgets().firstOrNull { it.kind == WidgetFrame.Kind.TEXT }?.bounds?.height
                    ?: 72.0
            val hinge =
                frame
                    .widgets()
                    .filter {
                        it.kind == WidgetFrame.Kind.CHOICE ||
                            it.kind == WidgetFrame.Kind.HINT && it.bounds.x >= 320
                    }
                    .minOfOrNull { it.bounds.x } ?: 320.0
            hingePosition = hinge
            val pose =
                NpcPanelTransform.from(snapshot, eye())
                    .withVerticalOffset(verticalOffset)
                    .withSpeechHeight(height)
                    .withChoiceFold(hinge, NpcPanelOptions.from(preferences).choiceAngle)
            transform = pose
            val slots = panelSlots(encoded)
            val wanted = LinkedHashSet(slots.keys)
            // Keep unused answer slots on partial pages while respecting the display budget.
            if (slots.keys.any { it.kind == WidgetFrame.Kind.CHOICE }) {
                for (key in panels.keys) {
                    if (
                        key.kind == WidgetFrame.Kind.CHOICE &&
                            wanted.size < (32 - (if (keepBackdrop) 2 else 0)) / 2
                    ) {
                        wanted.add(key)
                    }
                }
            }
            val pending = ArrayList<CompletableFuture<*>>()
            for (key in panels.keys.filter { it !in wanted }) {
                pending.add(checkNotNull(panels.remove(key)).release().toCompletableFuture())
            }
            if (!keepBackdrop) {
                val preparation = backdropPreparation
                val retirement =
                    if (preparation == null) releaseBackdrop()
                    else {
                        preparation
                            .handle { _, _ -> null }
                            .thenCompose {
                                if (closed.get() || operation != epoch)
                                    CompletableFuture.completedFuture(null)
                                else releaseBackdrop()
                            }
                    }
                pending.add(retirement.toCompletableFuture())
            }
            if (!hide)
                hud?.let {
                    pending.add(resources.release(it).toCompletableFuture())
                    hud = null
                }
            CompletableFuture.allOf(*pending.toTypedArray()).whenComplete { _, releaseError ->
                queue.execute {
                    if (releaseError != null) {
                        result.completeExceptionally(releaseError)
                        return@execute
                    }
                    if (closed.get() || operation != epoch) {
                        result.completeExceptionally(CancellationException())
                        return@execute
                    }
                    val acquisitions = ArrayList<CompletableFuture<*>>()
                    for (slot in wanted) acquisitions.add(
                        panels.getOrPut(slot) { PanelPair(pose.position()) }.ready
                    )
                    if (focus && backdrop == null) {
                        if (backdropPreparation == null) {
                            val pair = PanelPair(pose.position())
                            backdrop = pair
                            backdropPreparation = pair.ready
                            checkNotNull(backdropPreparation).whenComplete { _, _ ->
                                queue.execute { backdropPreparation = null }
                            }
                        }
                    }
                    backdropPreparation?.let(acquisitions::add)
                    if (hide && hud == null) {
                        acquisitions.add(
                            resources
                                .prepare {
                                    CompletableFuture.completedFuture(
                                        channels.acquireHud(player, generation, 50)
                                    )
                                }
                                .thenAccept { hud = it }
                                .toCompletableFuture()
                        )
                    }
                    if (
                        channels.supportedEffects() && effects == null && effectsPreparation == null
                    ) {
                        effectsPreparation =
                            resources
                                .prepare {
                                    CompletableFuture.completedFuture(
                                        channels.acquireEffects(player, generation)
                                    )
                                }
                                .thenAccept { effects = it }
                                .toCompletableFuture()
                        checkNotNull(effectsPreparation).whenComplete { _, _ ->
                            queue.execute { effectsPreparation = null }
                        }
                    }
                    effectsPreparation?.let(acquisitions::add)
                    CompletableFuture.allOf(*acquisitions.toTypedArray()).whenComplete { _, error ->
                        queue.execute {
                            if (writeReady(operation, error, result)) {
                                finishPanel(
                                    frame,
                                    slots,
                                    pose,
                                    operation,
                                    hide,
                                    focus,
                                    vignette,
                                    verticalOffset,
                                    result,
                                )
                            }
                        }
                    }
                }
            }
        }

        private fun writeReady(
            operation: Long,
            error: Throwable?,
            result: CompletableFuture<Void>,
        ): Boolean {
            val rejection =
                when {
                    error != null -> error
                    closed.get() || operation != epoch || context.cancellation.isCancelled() ->
                        CancellationException()
                    !available() -> DialoguePresenter.FallbackRequired("MISSING_PACK")
                    else -> null
                }
            if (rejection == null) return true
            result.completeExceptionally(rejection)
            return false
        }

        private fun finishPanel(
            frame: WidgetFrame,
            slots: Map<PanelSlot, EncodedFrame.EncodedWidget>,
            pose: NpcPanelTransform,
            operation: Long,
            hide: Boolean,
            focus: Boolean,
            vignette: Boolean,
            verticalOffset: Double,
            result: CompletableFuture<Void>,
        ) {
            val outputs = ArrayList<CompletableFuture<*>>()
            outputs.add(positionDisplays(pose.position()).toCompletableFuture())
            hud?.let {
                outputs.add(
                    it.replace(HudCoverage.compose(Component.empty(), 320.0, hide))
                        .toCompletableFuture()
                )
            }
            if (vignette && effects == null) diagnostic.accept("POST_EFFECT_UNSUPPORTED")
            val native = fadeDuration == NATIVE_FADE && hasNativeVignette && effects != null
            // Keep immediate legacy effects in the first frame's acknowledgement.
            if (!native && nativeBank == null && (fadeDuration.isZero || !hasVignetteSteps)) {
                retargetVignette(vignette, clock.nanoTime())
                writeVignette(reportFailure = false)?.let { outputs.add(it.toCompletableFuture()) }
            }
            CompletableFuture.allOf(*outputs.toTypedArray()).whenComplete { _, error ->
                queue.execute {
                    if (writeReady(operation, error, result)) {
                        nextHudRefresh = clock.nanoTime() + HUD_REFRESH_NANOS
                        apply(slots, pose)
                        publish(frame, pose)
                        startFollow(verticalOffset)
                        if (native) {
                            updateNativeVignette(vignette)
                        } else {
                            if (nativeBank != null) switchToLegacyVignette(vignette)
                            retargetEffects(vignette)
                            scheduleFade()
                        }
                        result.complete(null)
                    }
                }
            }
        }

        private fun eye(): Point = player.position.add(0.0, player.eyeHeight, 0.0)

        private fun apply(
            slots: Map<PanelSlot, EncodedFrame.EncodedWidget>,
            pose: NpcPanelTransform,
        ) {
            player.sendPacket(BundlePacket())
            try {
                for ((slot, pair) in panels) {
                    if (slot !in slots && pair.shown) {
                        for (display in pair.displays) {
                            checkNotNull(display).entity.editEntityMeta(
                                TextDisplayMeta::class.java
                            ) { meta ->
                                meta.setText(Component.empty())
                                meta.setTransformationInterpolationDuration(0)
                            }
                        }
                        pair.shown = false
                    }
                }
                val fold = pose.fold
                val nextAngle = fold?.degrees ?: 0.0
                val poseChanged =
                    java.lang.Double.compare(appliedAngle, nextAngle) != 0 ||
                        java.lang.Double.compare(appliedHinge, hingePosition) != 0
                for ((slot, widget) in slots) {
                    val pair = panels.getValue(slot)
                    val contentChanged = !pair.shown || pair.choice != widget.choiceId
                    val factor = widget.visualScale
                    val scale = 0.32 * factor
                    val bounds = widget.bounds
                    val angle =
                        if (pose.planeFor(bounds) === pose) 0.0 else Math.toRadians(nextAngle)
                    for ((side, entry) in pair.displays.withIndex()) {
                        val display = checkNotNull(entry)
                        val back = side == 1
                        display.entity.editEntityMeta(TextDisplayMeta::class.java) { meta ->
                            meta.setText(if (back) widget.back else widget.front)
                            meta.setTransformationInterpolationStartDelta(-1)
                            meta.setTransformationInterpolationDuration(
                                if (poseChanged || contentChanged) 0
                                else ceil(widget.transition.toMillis() / 50.0).toInt()
                            )
                            val turn = (if (back) PI else 0.0) - angle
                            meta.setLeftRotation(
                                floatArrayOf(
                                    0f,
                                    sin(turn / 2).toFloat(),
                                    0f,
                                    cos(turn / 2).toFloat(),
                                )
                            )
                            meta.setScale(Vec(scale, scale, scale))
                            // Native one-line origin uses x = 1 - width / 2 and y = -9 + 7.
                            val x =
                                (bounds.x + bounds.width / 2 - 160 +
                                    (if (back) factor else -factor)) * NpcPanelTransform.UNIT
                            val z = if (back) -0.012 else 0.0
                            val hinge =
                                fold?.let { (it.pivot - 160) * NpcPanelTransform.UNIT } ?: 0.0
                            val rotatedX =
                                if (angle == 0.0) x
                                else hinge + (x - hinge) * cos(angle) - z * sin(angle)
                            val rotatedZ =
                                if (angle == 0.0) z else (x - hinge) * sin(angle) + z * cos(angle)
                            val y =
                                (pose.speechHeight - bounds.y + bounds.height * (factor - 1) / 2 -
                                    (2 + widget.paintBaseline) * factor) * NpcPanelTransform.UNIT
                            meta.setTranslation(Vec(rotatedX, y, rotatedZ))
                            meta.setTransformationInterpolationStartDelta(0)
                        }
                        display.entity.addViewer(player)
                    }
                }
                for ((slot, widget) in slots) {
                    val pair = panels.getValue(slot)
                    pair.shown = true
                    pair.choice = widget.choiceId
                }
                appliedAngle = nextAngle
                appliedHinge = hingePosition
                backdrop?.displays?.forEachIndexed { side, display ->
                    val entity = checkNotNull(display).entity
                    val back = side == 1
                    val meta = entity.entityMeta as TextDisplayMeta
                    val turn = if (back) PI else 0.0
                    meta.setLeftRotation(
                        floatArrayOf(
                            0f,
                            sin(turn / 2).toFloat(),
                            0f,
                            cos(turn / 2).toFloat(),
                        )
                    )
                    meta.setScale(Vec(0.6, 0.6, 0.6))
                    meta.setTranslation(
                        if (back) Vec(0.0075, -0.93, -0.562) else Vec(-0.0075, -0.93, -0.55)
                    )
                    meta.setText(
                        Component.text("\ue300")
                            .font(Key.key("dialogue:focus"))
                            .color(TextColor.color(0xffffff))
                            .shadowColor(ShadowColor.none())
                    )
                    meta.setTextOpacity(255.toByte())
                    entity.addViewer(player)
                }
            } finally {
                player.sendPacket(BundlePacket())
            }
        }

        private fun publish(frame: WidgetFrame, pose: NpcPanelTransform) {
            val choices = LinkedHashMap<String, WidgetFrame.Rect>()
            val choiceBounds = LinkedHashMap<String, WidgetFrame.Rect>()
            val fronts = ArrayList<WidgetFrame.Rect>()
            for (widget in frame.widgets()) {
                if (widget.kind != WidgetFrame.Kind.HINT) fronts.add(widget.hitRect())
                if (widget.kind == WidgetFrame.Kind.CHOICE) {
                    choiceBounds[checkNotNull(widget.choiceId)] = widget.hitRect()
                    if (widget.enabled) choices[checkNotNull(widget.choiceId)] = widget.hitRect()
                }
            }
            frame.scrollbar()?.let { scrollbar ->
                fronts.add(scrollbar.track())
                scrollbar.buttons().forEach { fronts.add(it.bounds) }
            }
            geometries[context.generation] =
                Geometry(pose, fronts, context.generation, choiceBounds)
            targets.publish(frame, context.generation, choices) { !closed.get() && available() }
        }

        private fun positionDisplays(position: Pos): CompletionStage<Void> {
            val result = CompletableFuture<Void>()
            val previous = movement
            movement = result.minimalCompletionStage()
            previous.whenComplete { _, oldError ->
                queue.execute {
                    if (oldError != null) {
                        result.completeExceptionally(oldError)
                        return@execute
                    }
                    if (closed.get() || !scope.isCurrent() || context.cancellation.isCancelled()) {
                        result.completeExceptionally(CancellationException())
                        return@execute
                    }
                    try {
                        val moves = ArrayList<CompletableFuture<*>>()
                        for (pair in panels.values) {
                            for (display in pair.displays) {
                                val entity = display?.entity ?: continue
                                if (entity.position != position)
                                    moves.add(entity.teleport(position))
                            }
                        }
                        backdrop?.displays?.forEach { display ->
                            val entity = checkNotNull(display).entity
                            if (entity.position != position) moves.add(entity.teleport(position))
                        }
                        CompletableFuture.allOf(*moves.toTypedArray()).whenComplete { _, error ->
                            if (error == null) result.complete(null)
                            else result.completeExceptionally(error)
                        }
                    } catch (error: Throwable) {
                        result.completeExceptionally(error)
                    }
                }
            }
            return result.minimalCompletionStage()
        }

        private fun renewHudIfDue() {
            val lease = hud ?: return
            if (
                closed.get() ||
                    context.cancellation.isCancelled() ||
                    !available() ||
                    hudRefreshing ||
                    clock.nanoTime() < nextHudRefresh
            )
                return
            val renewingEpoch = epoch
            hudRefreshing = true
            nextHudRefresh = clock.nanoTime() + HUD_REFRESH_NANOS
            lease.replace(HudCoverage.compose(Component.empty(), 320.0, true)).whenComplete {
                _,
                error ->
                queue.execute {
                    hudRefreshing = false
                    if (
                        closed.get() ||
                            context.cancellation.isCancelled() ||
                            renewingEpoch != epoch ||
                            lease !== hud
                    ) {
                        return@execute
                    }
                    if (error != null) context.cancellation.cancel(DialogueEndReason.FAILED)
                }
            }
        }

        private fun startFollow(verticalOffset: Double) {
            follow?.cancel()
            val followingEpoch = epoch
            follow =
                clock.schedule(Duration.ofMillis(50)) {
                    queue.execute following@{
                        if (
                            closed.get() ||
                                context.cancellation.isCancelled() ||
                                followingEpoch != epoch
                        )
                            return@following
                        try {
                            val anchor = checkNotNull(binding)
                            if (
                                !scope.isCurrent() ||
                                    anchor.instance !== player.instance ||
                                    !anchor.snapshot.get().valid
                            ) {
                                context.cancellation.cancel(DialogueEndReason.HOST_CHANGED)
                                return@following
                            }
                            renewHudIfDue()
                            val pose = checkNotNull(transform)
                            val next =
                                NpcPanelTransform.from(anchor.snapshot.get(), eye())
                                    .withVerticalOffset(verticalOffset)
                                    .withSpeechHeight(pose.speechHeight)
                                    .withFold(pose.fold)
                            positionDisplays(next.position()).whenComplete { _, error ->
                                queue.execute movement@{
                                    if (closed.get() || followingEpoch != epoch) return@movement
                                    if (error != null) {
                                        context.cancellation.cancel(DialogueEndReason.HOST_CHANGED)
                                        return@movement
                                    }
                                    transform = next
                                    geometries.computeIfPresent(context.generation) { key, old ->
                                        Geometry(next, old.fronts(), key, old.choiceBounds())
                                    }
                                    startFollow(verticalOffset)
                                }
                            }
                        } catch (_: Throwable) {
                            context.cancellation.cancel(DialogueEndReason.HOST_CHANGED)
                        }
                    }
                }
        }

        private fun effectFadeDuration(): Duration {
            val value = preferences.effects["npcEffectsFadeMs"] ?: return Duration.ZERO
            val millis = value.toLongOrNull()
            if (millis == null || millis !in 0L..1000L) {
                diagnostic.accept("INVALID_EFFECT_VALUE:npcEffectsFadeMs")
                return Duration.ZERO
            }
            return Duration.ofMillis(millis)
        }

        private fun updateNativeVignette(vignette: Boolean) {
            if (vignette) {
                if (nativeBank != null) return
                cancelFadeTask()
                effectRevision++
                effectWriting = false
                effectStep = 0
                vignetteLevel = NpcEffectLevel()
                val bank = nextNativeBank()
                nativeBank = bank
                writeNativeVignette(listOf(bank.enter))
            } else if (
                nativeBank != null || vignetteLevel.enabled || effectWriting || effectStep > 0
            ) {
                cancelFadeTask()
                nativeBank = null
                effectRevision++
                effectWriting = false
                effectStep = 0
                vignetteLevel = NpcEffectLevel()
                writeNativeVignette(emptyList())
            }
        }

        private fun switchToLegacyVignette(vignette: Boolean) {
            nativeBank = null
            effectRevision++
            if (vignette) {
                // Keep the installed vignette visible when preferences change duration.
                vignetteLevel.retarget(true, clock.nanoTime(), Duration.ZERO)
                writeVignette(reportFailure = true)
            } else {
                writeNativeVignette(emptyList())
            }
        }

        private fun writeNativeVignette(keys: List<Key>) {
            val lease = effects ?: return
            val revision = effectRevision
            val stage =
                try {
                    lease.replace(keys)
                } catch (error: Throwable) {
                    CompletableFuture.failedFuture(error)
                }
            stage.whenComplete { _, error ->
                if (error != null) {
                    queue.execute {
                        if (revision != effectRevision) return@execute
                        if (closed.get()) finishCleanup(if (canFadeNative()) error else null)
                        else if (available() && !context.cancellation.isCancelled()) {
                            context.cancellation.cancel(DialogueEndReason.FAILED)
                            closeAsync()
                        }
                    }
                }
            }
        }

        // Session cancellation disables input before requesting surface cleanup.
        private fun canFadeNative(): Boolean =
            canFadeOut() && (context.cancellation.reason() != null || scope.isCurrent())

        private fun tickNativeClose() {
            val elapsed = clock.nanoTime() - nativeCloseStarted
            val remaining = NATIVE_CLOSE_GRACE.minusNanos(elapsed)
            if (!canFadeNative() || remaining.isNegative || remaining.isZero) {
                finishCleanup()
                return
            }
            fadeTask =
                clock.schedule(minOf(FADE_TICK, remaining)) {
                    queue.execute {
                        if (nativeBank != null) tickNativeClose()
                    }
                }
        }

        private fun retargetEffects(vignette: Boolean) {
            val now = clock.nanoTime()
            val vignetteChanged = retargetVignette(vignette, now)
            if (vignetteChanged) cancelFadeTask()
        }

        private fun retargetVignette(vignette: Boolean, now: Long): Boolean =
            vignetteLevel.retarget(
                vignette && effects != null,
                now,
                if (hasVignetteSteps) fadeDuration else Duration.ZERO,
                effectStep.toDouble() / DIALOGUE_VIGNETTE_KEYS.size,
            )

        private fun vignetteStep(): Int =
            (vignetteLevel.current * DIALOGUE_VIGNETTE_KEYS.size).roundToInt()

        private fun writeVignette(reportFailure: Boolean): CompletionStage<Void>? {
            val lease = effects ?: return null
            val step = vignetteStep()
            if (effectWriting || step == effectStep) return null
            effectWriting = true
            val previousStep = effectStep
            effectStep = step
            val keys = DIALOGUE_VIGNETTE_KEYS.getOrNull(step - 1)?.let(::listOf) ?: emptyList()
            val revision = effectRevision
            return lease.replace(keys).whenComplete { _, error ->
                queue.execute {
                    if (revision != effectRevision) return@execute
                    effectWriting = false
                    if (error != null) {
                        effectStep = previousStep
                        if (reportFailure) {
                            if (closed.get()) finishCleanup(if (canFadeOut()) error else null)
                            else if (available() && !context.cancellation.isCancelled()) {
                                context.cancellation.cancel(DialogueEndReason.FAILED)
                                closeAsync()
                            }
                        }
                    } else if (
                        !closed.get() && available() && !context.cancellation.isCancelled()
                    ) {
                        scheduleFade()
                    }
                }
            }
        }

        private fun cancelFadeTask() {
            fadeGeneration++
            fadeTask?.cancel()
            fadeTask = null
        }

        private fun scheduleFade() {
            if (fadeTask != null) return
            val now = clock.nanoTime()
            val remaining = vignetteLevel.remaining(now)
            if (
                vignetteLevel.settled &&
                    (closed.get() || effectWriting || vignetteStep() == effectStep)
            ) {
                if (closed.get()) finishCleanup()
                return
            }
            val delay =
                if (remaining == 0L) FADE_TICK else minOf(FADE_TICK, Duration.ofNanos(remaining))
            val animation = fadeGeneration
            fadeTask =
                clock.schedule(delay) {
                    queue.execute {
                        if (animation == fadeGeneration) {
                            fadeTask = null
                            tickFade()
                        }
                    }
                }
        }

        private fun tickFade() {
            if (closed.get()) {
                if (!canFadeOut()) {
                    finishCleanup()
                    return
                }
            } else if (context.cancellation.isCancelled() || !available()) {
                return
            }
            val now = clock.nanoTime()
            vignetteLevel.advance(now)
            // Releasing the lease publishes the endpoint at zero during surface retirement.
            if (!closed.get() || vignetteStep() != 0) writeVignette(reportFailure = true)
            scheduleFade()
        }

        private fun releaseBackdrop(): CompletionStage<Void> {
            val pair = backdrop ?: return CompletableFuture.completedFuture(null)
            backdrop = null
            return pair.release()
        }

        private fun canFadeOut(): Boolean {
            if (
                context.cancellation.reason() !in NORMAL_FADE_ENDS ||
                    !scope.hasCurrentConnection() ||
                    !packs.isReady(player, generation, assets.pin())
            )
                return false
            return try {
                binding?.snapshot?.get()?.valid == true
            } catch (_: Throwable) {
                false
            }
        }

        override fun closeAsync(): CompletionStage<Void> {
            if (closed.compareAndSet(false, true)) {
                queue.execute {
                    epoch++
                    writes.forEach { it.completeExceptionally(CancellationException()) }
                    writes.clear()
                    follow?.cancel()
                    targets.clear(context.generation)
                    geometries.remove(context.generation)
                    cancelFadeTask()
                    val bank = nativeBank
                    if (bank != null) {
                        if (context.cleanupBudget > NATIVE_CLOSE_GRACE && canFadeNative()) {
                            nativeCloseStarted = clock.nanoTime()
                            nativeCleanupDeadline =
                                nativeCloseStarted + context.cleanupBudget.toNanos()
                            releaseVisibleOutput()
                            writeNativeVignette(listOf(bank.enter, bank.exit))
                            tickNativeClose()
                        } else finishCleanup()
                    } else if (
                        !fadeDuration.isZero &&
                            context.cleanupBudget > fadeDuration.plus(FADE_TICK) &&
                            hasVignetteSteps &&
                            effectStep > 0 &&
                            canFadeOut()
                    ) {
                        releaseVisibleOutput()
                        retargetEffects(vignette = false)
                        scheduleFade()
                    } else finishCleanup()
                }
            }
            return cleanupView
        }

        private fun releaseVisibleOutput() {
            panels.values.forEach { pair ->
                pair.displays.filterNotNull().forEach(resources::release)
            }
            panels.clear()
            releaseBackdrop()
            hud?.let(resources::release)
            hud = null
        }

        private fun finishCleanup(fadeError: Throwable? = null) {
            nativeBank = null
            effectRevision++
            cancelFadeTask()
            panels.clear()
            binding = null
            transform = null
            val retirement =
                nativeCleanupDeadline?.let(resources::closeBy) ?: resources.closeAsync()
            retirement.whenComplete { _, error ->
                val failure = error ?: fadeError
                if (failure == null) cleanup.complete(null)
                else cleanup.completeExceptionally(failure)
            }
        }
    }

    private companion object {
        val FADE_TICK = Duration.ofMillis(50)
        val NATIVE_FADE = Duration.ofMillis(250)
        val NATIVE_CLOSE_GRACE = Duration.ofMillis(500)
        val NATIVE_BANKS = WeakHashMap<Player, NativeVignetteBank>()
        val NATIVE_VIGNETTE_ASSETS =
            NativeVignetteBank.entries.flatMap {
                listOf(
                    "post_effect/${it.enter.value()}.json",
                    "post_effect/${it.exit.value()}.json",
                )
            } +
                listOf(
                    "shaders/post/vignette_state.fsh",
                    "shaders/post/vignette_animated.fsh",
                    "shaders/post/vignette_copy.fsh",
                    "shaders/include/vignette_clock.glsl",
                )
        val NORMAL_FADE_ENDS =
            setOf(
                null,
                DialogueEndReason.CANCELLED,
                DialogueEndReason.COMPLETED,
                DialogueEndReason.OUT_OF_RANGE,
                DialogueEndReason.LOOKED_AWAY,
            )
        val HUD_REFRESH_NANOS = Duration.ofSeconds(1).toNanos()
        val COUNTS = WeakHashMap<Player, AtomicInteger>()

        fun verticalOffset(preferences: PresentationPreferences): Double {
            val value = preferences.effects["npcVerticalOffset"] ?: return 0.0
            val offset = value.toDoubleOrNull()
            if (offset == null || offset !in 0.0..16.0) {
                throw DialoguePresenter.FallbackRequired("UNSUPPORTED_RASTER")
            }
            return offset
        }

        fun NpcPanelTransform.withVerticalOffset(offset: Double): NpcPanelTransform =
            copy(origin = origin.add(0.0, offset, 0.0))
    }
}
