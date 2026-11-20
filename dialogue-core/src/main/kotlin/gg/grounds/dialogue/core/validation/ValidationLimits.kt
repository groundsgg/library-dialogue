package gg.grounds.dialogue.core.validation

@JvmRecord
data class ValidationLimits(
    val definitionBytes: Int,
    val nodes: Int,
    val choices: Int,
    val automaticSteps: Int,
    val depth: Int,
    val idCodepoints: Int,
    val textCodepoints: Int,
    val locales: Int,
    val textFileBytes: Int,
    val textPackageBytes: Int,
    val arguments: Int,
) {
    init {
        require(
            definitionBytes > 0 &&
                nodes > 0 &&
                choices > 0 &&
                automaticSteps > 0 &&
                depth > 0 &&
                idCodepoints > 0 &&
                textCodepoints > 0 &&
                locales > 0 &&
                textFileBytes > 0 &&
                textPackageBytes > 0 &&
                arguments > 0
        ) {
            "limits must be positive"
        }
    }

    override fun toString(): String =
        "ValidationLimits[definitionBytes=$definitionBytes, nodes=$nodes, choices=$choices, " +
            "automaticSteps=$automaticSteps, depth=$depth, idCodepoints=$idCodepoints, " +
            "textCodepoints=$textCodepoints, locales=$locales, textFileBytes=$textFileBytes, " +
            "textPackageBytes=$textPackageBytes, arguments=$arguments]"

    companion object {
        @JvmStatic
        fun defaults() =
            ValidationLimits(
                1_048_576,
                1024,
                64,
                32,
                32,
                128,
                16_384,
                64,
                4_194_304,
                16_777_216,
                1024,
            )
    }
}
