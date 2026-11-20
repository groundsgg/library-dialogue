package gg.grounds.dialogue.minestom.render

import gg.grounds.dialogue.core.internal.OwnedScope
import gg.grounds.dialogue.core.internal.SerialCommandQueue
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueFailure
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding
import gg.grounds.dialogue.minestom.NpcPanelOptions
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.OutputChannels
import gg.grounds.dialogue.minestom.geometry.NpcPanelTransform
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.ConnectionGuard
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

/** Projects shared widgets into private world panels around a borrowed NPC anchor. */
class NpcDialogueSurface
internal constructor(
    private val player: Player,
    private val generation: Long,
    private val guard: ConnectionGuard,
    private val packs: PackStateTracker,
    private val assets: AssetBundle,
    private val channels: OutputChannels,
    private val targets: SurfaceInputTargets,
    private val clock: DialogueScheduler,
    private val speaker: Function<DialogueView, MinestomSpeakerBinding?>,
    private val diagnostic: Consumer<String>,
    private val spawn: SpawnOperation,
) : DialogueSurface {
    internal fun interface SpawnOperation {
        fun spawn(entity: Entity, instance: Instance, position: Pos): CompletionStage<Void>
    }

    constructor(
        player: Player,
        generation: Long,
        guard: ConnectionGuard,
        packs: PackStateTracker,
        assets: AssetBundle,
        channels: OutputChannels,
        targets: SurfaceInputTargets,
        clock: DialogueScheduler,
        speaker: Function<DialogueView, MinestomSpeakerBinding?>,
        diagnostic: Consumer<String>,
    ) : this(
        player,
        generation,
        guard,
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

    private val encoder = MinecraftWidgetEncoder()
    private val geometries = ConcurrentHashMap<Long, Geometry>()
    private val displayCount = synchronized(COUNTS) { COUNTS.getOrPut(player, ::AtomicInteger) }

    fun geometry(): Geometry? = targets.current()?.let { geometries[it.surfaceGeneration()] }

    private fun available(): Boolean =
        guard.isCurrent() && packs.isReady(player, generation, assets.pin())

    override fun open(
        context: DialogueSurface.SurfaceContext
    ): CompletionStage<DialogueSurface.SurfaceHandle> =
        if (available()) CompletableFuture.completedFuture(Handle(context))
        else CompletableFuture.failedFuture(DialoguePresenter.FallbackRequired("MISSING_PACK"))

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
                context.cancellation.cancel(DialogueEndReason.FAILED)
                closeAsync()
            }
        private val scope = OwnedScope(queue, clock, context.cleanupBudget)
        private var binding: MinestomSpeakerBinding? = null
        private var transform: NpcPanelTransform? = null
        private var appliedAngle = Double.NaN
        private var hingePosition = 0.0
        private var appliedHinge = Double.NaN
        private var follow: DialogueScheduler.ScheduledTask? = null
        private var backdrop: Display? = null
        private var hud: OutputChannels.HudLease? = null
        private var effects: OutputChannels.EffectLease? = null
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
                            displays.filterNotNull().map { scope.release(it).toCompletableFuture() }
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

        private fun create(position: Pos): CompletionStage<Display> = scope.prepare {
            val display = Display()
            val result = CompletableFuture<Display>()
            try {
                spawn.spawn(display.entity, player.instance, position).whenComplete { _, error ->
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
            binding = speaker
            if (speaker.instance !== player.instance || !speaker.snapshot.get().valid) {
                context.cancellation.cancel(DialogueEndReason.HOST_CHANGED)
                throw CancellationException("NPC anchor changed")
            }
            val encoded = encoder.encode(frame, assets)
            val verticalOffset = verticalOffset(preferences)
            val focus = VisualOptions.enabled(preferences, "npcBackdrop", diagnostic)
            val hide = VisualOptions.enabled(preferences, "hideHud", diagnostic)
            val vignette = VisualOptions.enabled(preferences, "vignette", diagnostic)
            if (encoded.widgets().size * 2 + (if (focus) 1 else 0) > 32) {
                throw DialogueFailure("DISPLAY_LIMIT", "NPC display budget exceeded")
            }
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
                NpcPanelTransform.from(speaker.snapshot.get(), eye())
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
                            wanted.size < (32 - (if (focus) 1 else 0)) / 2
                    ) {
                        wanted.add(key)
                    }
                }
            }
            val pending = ArrayList<CompletableFuture<*>>()
            for (key in panels.keys.filter { it !in wanted }) {
                pending.add(checkNotNull(panels.remove(key)).release().toCompletableFuture())
            }
            if (!focus)
                backdrop?.let {
                    pending.add(scope.release(it).toCompletableFuture())
                    backdrop = null
                }
            if (!hide)
                hud?.let {
                    pending.add(scope.release(it).toCompletableFuture())
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
                        acquisitions.add(
                            create(pose.position())
                                .thenAccept { backdrop = it }
                                .toCompletableFuture()
                        )
                    }
                    if (hide && hud == null) {
                        acquisitions.add(
                            scope
                                .prepare {
                                    CompletableFuture.completedFuture(
                                        channels.acquireHud(player, generation, 50)
                                    )
                                }
                                .thenAccept { hud = it }
                                .toCompletableFuture()
                        )
                    }
                    if (channels.supportedEffects() && effects == null) {
                        acquisitions.add(
                            scope
                                .prepare {
                                    CompletableFuture.completedFuture(
                                        channels.acquireEffects(player, generation)
                                    )
                                }
                                .thenAccept { effects = it }
                                .toCompletableFuture()
                        )
                    }
                    CompletableFuture.allOf(*acquisitions.toTypedArray()).whenComplete { _, error ->
                        queue.execute {
                            if (writeReady(operation, error, result)) {
                                finishPanel(
                                    frame,
                                    slots,
                                    pose,
                                    operation,
                                    hide,
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
            val lease = effects
            if (lease != null) {
                outputs.add(
                    lease
                        .replace(
                            if (vignette) listOf(Key.key("dialogue:vignette")) else emptyList()
                        )
                        .toCompletableFuture()
                )
            } else if (vignette) {
                diagnostic.accept("POST_EFFECT_UNSUPPORTED")
            }
            CompletableFuture.allOf(*outputs.toTypedArray()).whenComplete { _, error ->
                queue.execute {
                    if (writeReady(operation, error, result)) {
                        nextHudRefresh = clock.nanoTime() + HUD_REFRESH_NANOS
                        apply(slots, pose)
                        publish(frame, pose)
                        startFollow(verticalOffset)
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
                backdrop?.entity?.let { entity ->
                    val meta = entity.entityMeta as TextDisplayMeta
                    meta.setScale(Vec(0.6, 0.6, 0.6))
                    meta.setTranslation(Vec(-0.0075, -0.93, -0.55))
                    meta.setText(
                        Component.text("\ue300")
                            .font(Key.key("dialogue:focus"))
                            .color(TextColor.color(0xffffff))
                            .shadowColor(ShadowColor.none())
                    )
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
                    if (closed.get() || !guard.isCurrent() || context.cancellation.isCancelled()) {
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
                        backdrop?.entity?.let {
                            if (it.position != position) moves.add(it.teleport(position))
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
                                !guard.isCurrent() ||
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

        override fun closeAsync(): CompletionStage<Void> {
            if (closed.compareAndSet(false, true)) {
                queue.execute {
                    epoch++
                    writes.forEach { it.completeExceptionally(CancellationException()) }
                    writes.clear()
                    follow?.cancel()
                    targets.clear(context.generation)
                    geometries.remove(context.generation)
                    scope.closeAsync().whenComplete { _, error ->
                        panels.clear()
                        binding = null
                        transform = null
                        if (error == null) cleanup.complete(null)
                        else cleanup.completeExceptionally(error)
                    }
                }
            }
            return cleanupView
        }
    }

    private companion object {
        val HUD_REFRESH_NANOS = Duration.ofSeconds(1).toNanos()
        val COUNTS = WeakHashMap<Player, AtomicInteger>()

        fun verticalOffset(preferences: PresentationPreferences): Double {
            val value = preferences.effects()["npcVerticalOffset"] ?: return 0.0
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
