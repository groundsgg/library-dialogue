package gg.grounds.dialogue.core.validation

class ValidationReport(
    problems: List<Problem>,
    private val validated: ValidatedDialogue?,
) {
    private val problems = java.util.List.copyOf(problems)

    fun problems(): List<Problem> = problems

    fun validated(): ValidatedDialogue? = validated

    override fun equals(other: Any?): Boolean =
        other is ValidationReport && problems == other.problems && validated == other.validated

    override fun hashCode(): Int {
        var hash = problems.hashCode()
        hash = 31 * hash + (validated?.hashCode() ?: 0)
        return hash
    }

    override fun toString(): String = "ValidationReport[problems=$problems, validated=$validated]"

    enum class Severity {
        ERROR,
        WARNING,
    }

    @JvmRecord
    data class Problem(
        val code: String,
        val severity: Severity,
        val file: String?,
        val path: String?,
        val nodeId: String?,
        val message: String,
    )

    fun isValid(): Boolean = validated != null && problems.none { it.severity == Severity.ERROR }
}
