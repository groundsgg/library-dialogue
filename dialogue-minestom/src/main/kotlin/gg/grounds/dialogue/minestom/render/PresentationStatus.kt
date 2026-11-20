package gg.grounds.dialogue.minestom.render

@JvmRecord
data class PresentationStatus(
    val requestedSurface: String,
    val effectiveSurface: String,
    val diagnostic: String,
    val surfaceGeneration: Long,
) {
    init {
        require(surfaceGeneration >= 0) { "generation" }
    }
}
