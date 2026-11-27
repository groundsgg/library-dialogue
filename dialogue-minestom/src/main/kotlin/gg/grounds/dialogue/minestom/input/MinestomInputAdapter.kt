package gg.grounds.dialogue.minestom.input

import gg.grounds.dialogue.core.internal.OwnedScope
import gg.grounds.dialogue.core.internal.PendingOperation
import gg.grounds.dialogue.core.internal.SerialCommandQueue
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.OwnedResource
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueInput
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.InputResult
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.minestom.MinestomConnectionScope
import gg.grounds.dialogue.minestom.geometry.NpcHitTest
import gg.grounds.dialogue.minestom.render.NpcDialogueSurface
import java.time.Duration
import java.util.ArrayDeque
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Vec
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.network.packet.client.ClientPacket
import net.minestom.server.network.packet.client.common.ClientPongPacket
import net.minestom.server.network.packet.client.play.ClientAttackPacket
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket
import net.minestom.server.network.packet.client.play.ClientInteractEntityPacket
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket
import net.minestom.server.network.packet.client.play.ClientUseItemPacket
import net.minestom.server.network.packet.server.common.PingPacket
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.BundlePacket
import net.minestom.server.network.packet.server.play.HeldItemChangePacket

/**
 * Session-owned packet routing sharing [scope] with the renderer and its target registry. The
 * application wires [target] to that renderer and Core handle; Core alone authorizes offers. Both
 * public overloads preserve asynchronous activation and cleanup budgets. Keep [clock] schedulable
 * until close settles: a late catcher is still owned and must be released. After invalidation,
 * restoration is allowed only for the same online connection and owning instance.
 */
class MinestomInputAdapter
internal constructor(
    private val scope: MinestomConnectionScope,
    private val profile: InputProfile,
    private val clock: DialogueScheduler,
    private val cancellation: CancellationToken,
    private val target: Target,
    private val occlusion: NpcHitTest.OcclusionTest,
    private val budgets: RuntimeOptions.Budgets,
    private val catcherFactory: Supplier<CompletionStage<PrivateInteractionCatcher>>,
) : OwnedResource {
    private val player = scope.player
    private val generation = scope.generation

    interface Target {
        fun view(): DialogueView?

        fun targets(): TargetSnapshot?

        fun npcGeometry(): NpcDialogueSurface.Geometry?

        fun effectiveSurface(): String

        fun hiddenHotbar(): Boolean

        fun nextImpulse(): Long

        fun submit(input: DialogueInput): CompletionStage<InputResult>

        fun browse(displayed: TargetSnapshot, start: Int): CompletionStage<InputResult> =
            CompletableFuture.completedFuture(InputResult.STALE)
    }

    @JvmRecord
    private data class PendingPacket(
        val packet: ClientPacket,
        val arrived: Long,
        val admitted: TargetSnapshot?,
    )

    private val queue =
        SerialCommandQueue(clock) {
            cancellation.cancel(DialogueEndReason.FAILED)
            closeAsync()
        }
    private val resources = OwnedScope(queue, clock, budgets.cleanup)
    private val burst = ClickBurstGate(profile.quietPeriod())
    private val slots = SlotCursor(player.heldSlot.toInt(), generation)
    private val closed = AtomicBoolean()
    private val cleanup = CompletableFuture<Void>()
    private val cleanupView = cleanup.minimalCompletionStage()
    private val packets = ArrayDeque<PendingPacket>()
    private var dispatching = false
    private var activated = false
    private var acquiring = false
    private var scrolled = false
    private var followup = 0
    private var catcher: PrivateInteractionCatcher? = null
    private var preparing: CompletableFuture<Void>? = null
    private var acquireWait: PendingOperation<PrivateInteractionCatcher>? = null
    private var lastHover: String? = null
    private var observed: TargetSnapshot.Target? = null
    private var manualDirection: Vec? = null
    private var manualEye: Point? = null
    private var manualSession: UUID? = null
    private var manualVisit = 0L
    private var manualSurface = 0L

    constructor(
        scope: MinestomConnectionScope,
        profile: InputProfile,
        clock: DialogueScheduler,
        cancellation: CancellationToken,
        target: Target,
        occlusion: NpcHitTest.OcclusionTest,
    ) : this(
        scope,
        profile,
        clock,
        cancellation,
        target,
        occlusion,
        RuntimeOptions.Budgets(
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
        ),
    )

    constructor(
        scope: MinestomConnectionScope,
        profile: InputProfile,
        clock: DialogueScheduler,
        cancellation: CancellationToken,
        target: Target,
        occlusion: NpcHitTest.OcclusionTest,
        budgets: RuntimeOptions.Budgets,
    ) : this(
        scope,
        profile,
        clock,
        cancellation,
        target,
        occlusion,
        budgets,
        Supplier { PrivateInteractionCatcher.open(scope) },
    )

    init {
        burst.opened(clock.nanoTime())
    }

    private fun active(): Boolean =
        !closed.get() && scope.isCurrent() && !cancellation.isCancelled()

    fun activate(): CompletionStage<Void> {
        val result = CompletableFuture<Void>()
        queue.execute {
            if (!active()) {
                result.completeExceptionally(CancellationException())
                return@execute
            }
            activated = true
            ensureCatcher().whenComplete { _, error ->
                if (error == null) result.complete(null) else result.completeExceptionally(error)
            }
        }
        return result.minimalCompletionStage()
    }

    private fun graphical(): Boolean =
        target.effectiveSurface() == "hud" || target.effectiveSurface() == "npc"

    private fun ensureCatcher(): CompletionStage<Void> {
        val needed =
            activated &&
                graphical() &&
                (InputProfile.Trigger.USE in profile.confirm() ||
                    InputProfile.Trigger.ATTACK in profile.confirm())
        if (!needed) {
            val currentCatcher = catcher
            if (currentCatcher != null) {
                catcher = null
                return resources.release(currentCatcher)
            }
            return preparing?.minimalCompletionStage() ?: CompletableFuture.completedFuture(null)
        }
        catcher?.let {
            it.follow()
            return CompletableFuture.completedFuture(null)
        }
        if (acquiring) return requireNotNull(preparing).minimalCompletionStage()
        acquiring = true
        val promise = CompletableFuture<Void>()
        preparing = promise
        acquireWait =
            PendingOperation(
                queue,
                clock,
                budgets.prepare,
                resources.prepare(catcherFactory),
            ) { value, error ->
                acquiring = false
                if (error != null) {
                    promise.completeExceptionally(error)
                } else if (!active() || !graphical()) {
                    resources.release(checkNotNull(value)).whenComplete { _, releaseError ->
                        if (releaseError == null) promise.complete(null)
                        else promise.completeExceptionally(releaseError)
                    }
                } else {
                    catcher = value
                    checkNotNull(value).publish()
                    promise.complete(null)
                }
            }
        return promise.minimalCompletionStage()
    }

    fun receive(event: PlayerPacketEvent) {
        if (event.player !== player || !active()) return
        val packet = event.packet
        if (packet is ClientPongPacket) {
            if (slots.matches(packet.id())) {
                event.isCancelled = true
                enqueue(packet)
            }
            return
        }
        val reserved =
            packet is ClientHeldItemChangePacket ||
                packet is ClientUseItemPacket ||
                packet is ClientInteractEntityPacket ||
                packet is ClientAttackPacket ||
                packet is ClientPlayerBlockPlacementPacket ||
                packet is ClientPlayerActionPacket
        if (!reserved) return
        event.isCancelled = true
        enqueue(packet)
    }

    private fun enqueue(packet: ClientPacket) {
        val arrived = clock.nanoTime()
        val admitted = target.targets()
        queue.execute {
            if (!active()) return@execute
            if (packets.size >= 256) {
                cancellation.cancel(DialogueEndReason.FAILED)
                closeAsync()
                return@execute
            }
            packets.addLast(PendingPacket(packet, arrived, admitted))
            pump()
        }
    }

    private fun pump() {
        if (dispatching || !active() || packets.isEmpty()) return
        if (
            target.view() != null && current() == null && packets.first.packet !is ClientPongPacket
        ) {
            return
        }
        val packet = packets.removeFirst()
        dispatching = true
        val stage =
            try {
                route(packet)
            } catch (error: Throwable) {
                CompletableFuture.failedFuture(error)
            }
        stage.whenComplete { _, error ->
            queue.execute {
                dispatching = false
                if (error != null && active()) {
                    cancellation.cancel(DialogueEndReason.FAILED)
                    closeAsync()
                } else {
                    pump()
                }
            }
        }
    }

    private fun sameVisit(admitted: TargetSnapshot?): Boolean {
        val now = current()
        return admitted != null &&
            now != null &&
            admitted.sessionId() == now.sessionId() &&
            admitted.nodeVisitId() == now.nodeVisitId() &&
            admitted.surfaceGeneration() == now.surfaceGeneration()
    }

    private fun route(input: PendingPacket): CompletionStage<InputResult> {
        val packet = input.packet
        if (packet is ClientPongPacket) {
            slots.pong(packet.id(), generation, player.heldSlot.toInt())
            return ignored()
        }
        if (packet is ClientHeldItemChangePacket) {
            val slot = packet.slot().toInt()
            if (slot !in 0..8) return ignored()
            val direction = slots.scroll(slot)
            scrolled = true
            if (!target.hiddenHotbar() && !slots.awaiting() && slot != player.heldSlot.toInt())
                fence()
            return if (profile.scroll() && direction != 0 && sameVisit(input.admitted)) {
                scroll(direction)
            } else {
                ignored()
            }
        }
        if (
            packet is ClientInteractEntityPacket &&
                (catcher == null || packet.targetId() != catcher?.entityId())
        ) {
            return ignored()
        }
        if (
            packet is ClientAttackPacket &&
                (catcher == null || packet.targetId() != catcher?.entityId())
        ) {
            return ignored()
        }
        var trigger: InputProfile.Trigger? = null
        when (packet) {
            is ClientInteractEntityPacket,
            is ClientUseItemPacket,
            is ClientPlayerBlockPlacementPacket -> trigger = InputProfile.Trigger.USE
            is ClientAttackPacket -> trigger = InputProfile.Trigger.ATTACK
            is ClientPlayerActionPacket -> {
                when (packet.status()) {
                    ClientPlayerActionPacket.Status.STARTED_DIGGING,
                    ClientPlayerActionPacket.Status.STAB -> trigger = InputProfile.Trigger.ATTACK
                    ClientPlayerActionPacket.Status.SWAP_ITEM_HAND -> {
                        trigger = InputProfile.Trigger.SWAP_HANDS
                        player.inventory.update(player)
                    }
                    ClientPlayerActionPacket.Status.DROP_ITEM,
                    ClientPlayerActionPacket.Status.DROP_ITEM_STACK -> {
                        player.inventory.update(player)
                        return ignored()
                    }
                    else -> Unit
                }
                if (packet.status().name.contains("DIGGING")) {
                    refreshBlock(packet.blockPosition())
                    player.sendPacket(AcknowledgeBlockChangePacket(packet.sequence()))
                }
            }
            else -> Unit
        }
        repair(packet)
        return if (
            trigger != null &&
                trigger in profile.confirm() &&
                burst.accept(input.arrived) &&
                sameVisit(input.admitted) &&
                input.admitted === current()
        ) {
            confirm()
        } else {
            ignored()
        }
    }

    private fun stamp(snapshot: TargetSnapshot): DialogueInput.InputStamp =
        DialogueInput.InputStamp(
            snapshot.sessionId(),
            snapshot.renderRevision(),
            target.nextImpulse(),
        )

    private fun current(): TargetSnapshot? {
        val snapshot = target.targets()
        val view = target.view()
        return if (
            snapshot != null &&
                view != null &&
                snapshot.sessionId() == view.sessionId() &&
                snapshot.nodeVisitId() == view.nodeVisitId() &&
                snapshot.renderRevision() == view.renderRevision() &&
                (snapshot.stateRevision() < 0 || snapshot.stateRevision() == view.stateRevision())
        ) {
            snapshot
        } else {
            null
        }
    }

    private fun scroll(direction: Int): CompletionStage<InputResult> {
        val snapshot = current()
        val view = target.view()
        if (
            snapshot == null ||
                view == null ||
                view.state() == DialogueView.State.WAITING_FOR_PROVIDER
        ) {
            return ignored()
        }
        val choices =
            view.choices().filter { it.availability == DialogueView.Availability.AVAILABLE }
        if (choices.isEmpty()) return ignored()
        var selected = 0
        choices.forEachIndexed { index, choice ->
            if (choice.id == view.selectedChoiceId()) selected = index
        }
        val id = choices[Math.floorMod(selected + direction, choices.size)].id
        manualSelection(snapshot)
        return target.submit(DialogueInput.Select(stamp(snapshot), id))
    }

    private fun manualSelection(snapshot: TargetSnapshot) {
        manualDirection = player.position.direction()
        manualEye = player.position.add(0.0, player.eyeHeight.toDouble(), 0.0)
        manualSession = snapshot.sessionId()
        manualVisit = snapshot.nodeVisitId()
        manualSurface = snapshot.surfaceGeneration()
        lastHover = null
        observed = null
    }

    private fun manualHoverSuppressed(snapshot: TargetSnapshot): Boolean {
        val direction = manualDirection ?: return false
        val eye = player.position.add(0.0, player.eyeHeight.toDouble(), 0.0)
        val changed =
            snapshot.sessionId() != manualSession ||
                snapshot.nodeVisitId() != manualVisit ||
                snapshot.surfaceGeneration() != manualSurface ||
                player.position.direction().dot(direction) < Math.cos(Math.toRadians(1.5)) ||
                eye.distanceSquared(manualEye) > .08 * .08
        if (changed) {
            manualDirection = null
            manualEye = null
        }
        return !changed
    }

    private fun hover(snapshot: TargetSnapshot): Optional<String> {
        val geometry = target.npcGeometry()
        if (geometry == null || geometry.surfaceGeneration() != snapshot.surfaceGeneration()) {
            return Optional.empty()
        }
        if (manualHoverSuppressed(snapshot)) return Optional.empty()
        return NpcHitTest.hit(
            player.position.add(0.0, player.eyeHeight.toDouble(), 0.0),
            player.position.direction(),
            geometry.transform(),
            snapshot,
            occlusion,
        )
    }

    private fun hoverStamp(snapshot: TargetSnapshot, id: String): TargetSnapshot.Target =
        TargetSnapshot.Target(
            snapshot.sessionId(),
            snapshot.renderRevision(),
            snapshot.nodeVisitId(),
            snapshot.surfaceGeneration(),
            id,
        )

    private fun confirm(): CompletionStage<InputResult> {
        val snapshot = current()
        val view = target.view()
        if (snapshot == null || view == null) return ignored()
        val geometry = target.npcGeometry()
        var pointedChoice: NpcHitTest.ChoiceHit? = null
        if (geometry != null && geometry.surfaceGeneration() == snapshot.surfaceGeneration()) {
            val button =
                NpcHitTest.navigationHit(
                    player.position.add(0.0, player.eyeHeight.toDouble(), 0.0),
                    player.position.direction(),
                    geometry.transform(),
                    snapshot,
                    occlusion,
                )
            if (button.isPresent) {
                val hit = button.get()
                if (!hit.usable) return ignored()
                manualSelection(snapshot)
                val pageStart = hit.button.pageStart
                if (pageStart != null) return target.browse(snapshot, pageStart)
                return target.submit(DialogueInput.Select(stamp(snapshot), hit.button.choiceId))
            }
            pointedChoice =
                NpcHitTest.choiceHit(
                        player.position.add(0.0, player.eyeHeight.toDouble(), 0.0),
                        player.position.direction(),
                        geometry.transform(),
                        geometry.choiceBounds(),
                        snapshot,
                        occlusion,
                    )
                    .orElse(null)
            if (pointedChoice != null && !pointedChoice.usable) return ignored()
        }
        val hovered = hover(snapshot).orElse(null)
        if (
            hovered != null &&
                (hovered != view.selectedChoiceId() || hoverStamp(snapshot, hovered) != observed)
        ) {
            val selection = target.submit(DialogueInput.Select(stamp(snapshot), hovered))
            lastHover = hovered
            observed = hoverStamp(snapshot, hovered)
            return selection
        }
        if (target.effectiveSurface() == "npc" && view.choices().isNotEmpty()) {
            val bounds = snapshot.choices()[view.selectedChoiceId()]
            if (
                bounds == null ||
                    geometry == null ||
                    geometry.surfaceGeneration() != snapshot.surfaceGeneration()
            ) {
                return ignored()
            }
            if (pointedChoice == null || pointedChoice.choiceId != view.selectedChoiceId()) {
                val eye = player.position.add(0.0, player.eyeHeight.toDouble(), 0.0)
                val center =
                    geometry
                        .transform()
                        .widgetToWorld(
                            bounds.x + bounds.width / 2,
                            bounds.y + bounds.height / 2,
                        )
                val ray = center.sub(eye).asVec()
                if (
                    ray.lengthSquared() < 1e-12 ||
                        !NpcHitTest.hit(
                                eye,
                                ray.normalize(),
                                geometry.transform(),
                                snapshot,
                                occlusion,
                            )
                            .filter(view.selectedChoiceId()::equals)
                            .isPresent
                ) {
                    return ignored()
                }
            }
        }
        return target.submit(DialogueInput.Confirm(stamp(snapshot), view.selectedChoiceId()))
    }

    fun tick() {
        queue.execute {
            if (!active() || !activated) return@execute
            ensureCatcher().whenComplete { _, error ->
                if (error != null && active()) cancellation.cancel(DialogueEndReason.FAILED)
            }
            if (
                scrolled &&
                    !target.hiddenHotbar() &&
                    !slots.awaiting() &&
                    slots.slot() != player.heldSlot.toInt() &&
                    ++followup >= 2
            ) {
                fence()
            }
            pump()
            if (dispatching || packets.isNotEmpty()) return@execute
            val snapshot = current() ?: return@execute
            val hovered = hover(snapshot).orElse(null)
            if (hovered != null) {
                observed = hoverStamp(snapshot, hovered)
                if (hovered != lastHover && hovered != target.view()?.selectedChoiceId()) {
                    dispatching = true
                    target.submit(DialogueInput.Select(stamp(snapshot), hovered)).whenComplete {
                        _,
                        error ->
                        queue.execute {
                            dispatching = false
                            if (error != null && active()) {
                                cancellation.cancel(DialogueEndReason.FAILED)
                                closeAsync()
                            } else {
                                pump()
                            }
                        }
                    }
                }
            } else {
                observed = null
            }
            lastHover = hovered
        }
    }

    private fun fence() {
        val id = FENCES.incrementAndGet()
        slots.fence(id)
        followup = 0
        player.sendPackets(
            listOf(
                BundlePacket(),
                HeldItemChangePacket(player.heldSlot.toByte()),
                PingPacket(id),
                BundlePacket(),
            )
        )
    }

    private fun repair(packet: ClientPacket) {
        when (packet) {
            is ClientUseItemPacket -> {
                player.inventory.update(player)
                player.sendPacket(AcknowledgeBlockChangePacket(packet.sequence()))
            }
            is ClientPlayerBlockPlacementPacket -> {
                player.inventory.update(player)
                refreshBlock(packet.blockPosition())
                refreshBlock(packet.blockPosition().relative(packet.blockFace()))
                player.sendPacket(AcknowledgeBlockChangePacket(packet.sequence()))
            }
            else -> Unit
        }
    }

    private fun refreshBlock(point: Point) {
        val instance = player.instance
        if (instance != null && instance.isChunkLoaded(point)) {
            player.sendPacket(BlockChangePacket(point, instance.getBlock(point)))
        }
    }

    override fun closeAsync(): CompletionStage<Void> {
        if (closed.compareAndSet(false, true)) {
            queue.execute {
                if (scope.hasCurrentConnection()) {
                    player.inventory.update(player)
                    for (pending in packets) {
                        val packet = pending.packet
                        repair(packet)
                        if (
                            packet is ClientPlayerActionPacket &&
                                packet.status().name.contains("DIGGING")
                        ) {
                            refreshBlock(packet.blockPosition())
                            player.sendPacket(AcknowledgeBlockChangePacket(packet.sequence()))
                        }
                    }
                }
                packets.clear()
                acquireWait?.cancel()
                preparing?.takeUnless { it.isDone }?.completeExceptionally(CancellationException())
                if (scrolled && scope.hasCurrentConnection()) {
                    player.sendPacket(HeldItemChangePacket(player.heldSlot.toByte()))
                }
                resources.closeAsync().whenComplete { _, error ->
                    catcher = null
                    observed = null
                    lastHover = null
                    if (error == null) cleanup.complete(null)
                    else cleanup.completeExceptionally(error)
                }
            }
        }
        return cleanupView
    }

    companion object {
        private val FENCES = AtomicInteger(Int.MIN_VALUE / 2)

        private fun ignored(): CompletionStage<InputResult> =
            CompletableFuture.completedFuture(InputResult.STALE)
    }
}
