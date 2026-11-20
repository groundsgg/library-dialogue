package gg.grounds.dialogue.core.runtime

import gg.grounds.dialogue.core.validation.ValidationReport.Problem

sealed interface DialogueStartResult {
    @JvmRecord data class Started(val handle: DialogueHandle) : DialogueStartResult

    class Rejected(
        private val code: String?,
        problems: List<Problem>,
    ) : DialogueStartResult {
        private val problems = java.util.List.copyOf(problems)

        fun code(): String? = code

        fun problems(): List<Problem> = problems

        override fun equals(other: Any?): Boolean =
            other is Rejected && code == other.code && problems == other.problems

        override fun hashCode(): Int {
            var hash = (code?.hashCode() ?: 0)
            hash = 31 * hash + problems.hashCode()
            return hash
        }

        override fun toString(): String = "Rejected[code=$code, problems=$problems]"
    }
}
