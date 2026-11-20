package gg.grounds.dialogue.core.internal

import gg.grounds.dialogue.core.port.DialogueScheduler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/** Queue coordination never calls user code while holding a monitor. */
class SerialCommandQueue(
    private val scheduler: DialogueScheduler,
    private val errors: Consumer<Throwable>,
) {
    private val commands = ConcurrentLinkedQueue<Runnable>()
    private val work = AtomicInteger()

    fun execute(command: Runnable) {
        commands.add(command)
        if (work.getAndIncrement() == 0) {
            try {
                scheduler.execute(::drain)
            } catch (failure: Throwable) {
                drain()
                errors.accept(failure)
            }
        }
    }

    private fun drain() {
        var missed = 1
        do {
            while (true) {
                val command = commands.poll() ?: break
                try {
                    command.run()
                } catch (failure: Throwable) {
                    errors.accept(failure)
                }
            }
            missed = work.addAndGet(-missed)
        } while (missed != 0)
    }
}
