package gg.grounds.dialogue.presentation

import java.time.Duration

class DialogueTheme(
    private val id: String,
    colours: Map<String, Int>,
    private val selectedScale: Double,
    private val transition: Duration,
) {
    private val colours = java.util.Map.copyOf(colours)

    init {
        require(selectedScale.isFinite() && selectedScale in 1.0..2.0 && !transition.isNegative) {
            "Invalid visual transition"
        }
    }

    fun id(): String = id

    fun colours(): Map<String, Int> = colours

    fun selectedScale(): Double = selectedScale

    fun transition(): Duration = transition

    override fun equals(other: Any?): Boolean =
        other is DialogueTheme &&
            id == other.id &&
            colours == other.colours &&
            selectedScale == other.selectedScale &&
            transition == other.transition

    override fun hashCode(): Int =
        ((id.hashCode() * 31 + colours.hashCode()) * 31 + selectedScale.hashCode()) * 31 +
            transition.hashCode()

    override fun toString(): String =
        "DialogueTheme[id=$id, colours=$colours, selectedScale=$selectedScale, transition=$transition]"

    companion object {
        @JvmStatic
        fun hudPrototype(): DialogueTheme =
            DialogueTheme(
                "hud-prototype-v1",
                mapOf(
                    "text" to 0xf3eee4,
                    "selected" to 0xffcd75,
                    "muted" to 0xbac5cf,
                    "hint" to 0x9ba9b7,
                ),
                1.0,
                Duration.ZERO,
            )

        @JvmStatic
        fun neutral(): DialogueTheme =
            DialogueTheme(
                "neutral-v1",
                mapOf(
                    "text" to 0xf3eee4,
                    "selected" to 0xffcd75,
                    "muted" to 0x9ba9b7,
                    "panel" to 0x152335,
                ),
                1.08,
                Duration.ofMillis(150),
            )
    }
}
