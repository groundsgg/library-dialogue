package gg.grounds.dialogue.minestom.input

import java.time.Duration
import java.util.Collections
import java.util.HashSet

/** Minecraft actions, independent of the physical key chosen by the player. */
@JvmSuppressWildcards
class InputProfile(
    private val open: Set<Trigger>,
    private val confirm: Set<Trigger>,
    private val primaryConfirm: Trigger,
    private val scroll: Boolean,
    private val quietPeriod: Duration,
) {
    enum class Trigger {
        ATTACK,
        USE,
        SWAP_HANDS,
    }

    private val copiedOpen = Collections.unmodifiableSet(HashSet(open))
    private val copiedConfirm = Collections.unmodifiableSet(HashSet(confirm))

    init {
        if (
            open.isEmpty() ||
                Trigger.SWAP_HANDS in open ||
                confirm.isEmpty() ||
                primaryConfirm !in confirm ||
                quietPeriod < Duration.ofMillis(50) ||
                quietPeriod > Duration.ofSeconds(2)
        ) {
            throw IllegalArgumentException("Invalid controls")
        }
    }

    fun open(): Set<Trigger> = copiedOpen

    fun confirm(): Set<Trigger> = copiedConfirm

    fun primaryConfirm(): Trigger = primaryConfirm

    fun scroll(): Boolean = scroll

    fun quietPeriod(): Duration = quietPeriod

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is InputProfile &&
                copiedOpen == other.copiedOpen &&
                copiedConfirm == other.copiedConfirm &&
                primaryConfirm == other.primaryConfirm &&
                scroll == other.scroll &&
                quietPeriod == other.quietPeriod)

    override fun hashCode(): Int {
        var result = copiedOpen.hashCode()
        result = 31 * result + copiedConfirm.hashCode()
        result = 31 * result + primaryConfirm.hashCode()
        result = 31 * result + scroll.hashCode()
        return 31 * result + quietPeriod.hashCode()
    }

    override fun toString(): String =
        "InputProfile[open=$copiedOpen, confirm=$copiedConfirm, " +
            "primaryConfirm=$primaryConfirm, scroll=$scroll, quietPeriod=$quietPeriod]"

    companion object {
        @JvmStatic
        fun defaults(): InputProfile =
            InputProfile(
                setOf(Trigger.USE),
                setOf(Trigger.USE, Trigger.SWAP_HANDS),
                Trigger.USE,
                true,
                Duration.ofMillis(350),
            )
    }
}
