package gg.grounds.dialogue.cli

import gg.grounds.dialogue.core.validation.ValidationReport.Problem

internal class CliReport(
    val reportVersion: Int,
    val toolVersion: String,
    val validatorVersion: String,
    val formatVersion: Int,
    private val status: Status,
    contentPin: String?,
    problems: List<Problem>,
) {
    enum class Status {
        VALID,
        INVALID,
        ERROR,
    }

    val contentPin = contentPin.takeIf { status == Status.VALID }
    private val problems =
        java.util.List.copyOf(
            problems.sortedWith(
                compareBy(
                    { it.file },
                    { it.path },
                    { it.nodeId },
                    { it.severity.name },
                    { it.code },
                    { it.message },
                )
            )
        )

    fun status(): Status = status

    fun contentPin(): String? = contentPin

    fun problems(): List<Problem> = problems

    fun exitCode(): Int =
        when (status) {
            Status.VALID -> 0
            Status.INVALID -> 1
            Status.ERROR -> 2
        }

    companion object {
        fun error(version: VersionInfo, problems: List<Problem>): CliReport =
            CliReport(
                1,
                version.toolVersion,
                version.validatorVersion,
                1,
                Status.ERROR,
                null,
                problems,
            )
    }
}
