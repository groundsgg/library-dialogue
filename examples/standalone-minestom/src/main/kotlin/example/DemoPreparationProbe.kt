package example

import gg.grounds.dialogue.core.port.ActorPort
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Supplier
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule

/** Explicit demo probe: a real prepared actor is delivered late after a real interruption. */
internal class DemoPreparationProbe(
    private val mode: String,
    private val delegate: ActorPort,
    private val interrupt: Supplier<CompletionStage<Void>>,
    private val progress: Consumer<String>,
) : ActorPort {
    @JvmField val prepared = CompletableFuture<Void>()
    @JvmField val closed = CompletableFuture<Void>()
    @JvmField val interrupted = CompletableFuture<Void>()
    private val activations = AtomicInteger()
    private val cues = AtomicInteger()
    @Volatile private var reasonAtHandoff: DialogueEndReason? = null
    @Volatile private var phase = "PREPARING"
    @Volatile private var handed = false

    override fun prepare(request: ActorPort.ActorRequest): CompletionStage<ActorPort.ActorHandle> =
        delegate.prepare(request).thenCompose { actor ->
            val late = CompletableFuture<ActorPort.ActorHandle>()
            phase = "HOLDING_PREPARED_ACTOR"
            val observed =
                object : ActorPort.ActorHandle {
                    override fun activate(): CompletionStage<Void> {
                        activations.incrementAndGet()
                        return actor.activate()
                    }

                    override fun cue(cue: ActorPort.ActorCue): CompletionStage<Void> {
                        cues.incrementAndGet()
                        return actor.cue(cue)
                    }

                    override fun closeAsync(): CompletionStage<Void> {
                        return try {
                            actor.closeAsync().also { release ->
                                release.whenComplete { _, error ->
                                    if (error == null) closed.complete(null)
                                    else closed.completeExceptionally(error)
                                }
                            }
                        } catch (error: Throwable) {
                            closed.completeExceptionally(error)
                            CompletableFuture.failedFuture(error)
                        }
                    }
                }
            MinecraftServer.getSchedulerManager()
                .buildTask {
                    reasonAtHandoff = request.cancellation()?.reason()
                    handed = true
                    phase = "LATE_HANDOFF"
                    late.complete(observed)
                }
                .delay(TaskSchedule.duration(Duration.ofSeconds(3)))
                .schedule()
            MinecraftServer.getSchedulerManager()
                .buildTask {
                    phase = "INTERRUPTING"
                    try {
                        interrupt.get().whenComplete { _, error ->
                            if (error == null) interrupted.complete(null)
                            else interrupted.completeExceptionally(error)
                        }
                    } catch (error: Throwable) {
                        interrupted.completeExceptionally(error)
                    }
                }
                .delay(TaskSchedule.duration(Duration.ofSeconds(1)))
                .schedule()
            prepared.complete(null)
            progress.accept("Actor prepared; interrupt in 1s, delayed handoff in 3s ($mode).")
            late.minimalCompletionStage()
        }

    fun validHandoff(): Boolean {
        val expected =
            when (mode) {
                "cancel" -> reasonAtHandoff == DialogueEndReason.CANCELLED
                "instance" -> reasonAtHandoff == DialogueEndReason.HOST_CHANGED
                "disconnect" ->
                    reasonAtHandoff == DialogueEndReason.DISCONNECTED ||
                        reasonAtHandoff == DialogueEndReason.HOST_CHANGED
                else -> false
            }
        return handed && expected && activations.get() == 0 && cues.get() == 0
    }

    fun passed(): Boolean =
        validHandoff() &&
            closed.isDone &&
            !closed.isCompletedExceptionally &&
            interrupted.isDone &&
            !interrupted.isCompletedExceptionally

    fun status(): String =
        "mode=$mode; phase=$phase; reason=$reasonAtHandoff; activated=${activations.get()}; " +
            "cues=${cues.get()}; closed=${closed.isDone && !closed.isCompletedExceptionally}; " +
            "PASS=${passed()}"
}
