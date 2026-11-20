package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.port.DialogueScheduler
import java.time.Duration

@JvmRecord
data class RuntimeOptions(
    val scheduler: DialogueScheduler,
    val budgets: Budgets,
    val startRegisterLimit: Int,
    val inputQueueLimit: Int,
) {
    init {
        require(startRegisterLimit > 0 && inputQueueLimit > 0) { "Positive capacity required" }
    }

    @JvmRecord
    data class Budgets(
        val text: Duration,
        val prepare: Duration,
        val action: Duration,
        val cleanup: Duration,
    ) {
        init {
            val durations = listOf(text, prepare, action, cleanup)
            require(durations.all { !it.isNegative && !it.isZero }) {
                "Positive finite duration required"
            }
            durations.forEach { it.toNanos() }
        }
    }

    companion object {
        @JvmStatic
        fun defaults(s: DialogueScheduler): RuntimeOptions =
            RuntimeOptions(
                s,
                Budgets(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30),
                ),
                65_536,
                256,
            )
    }
}
