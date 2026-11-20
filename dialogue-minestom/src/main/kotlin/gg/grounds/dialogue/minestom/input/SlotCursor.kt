package gg.grounds.dialogue.minestom.input

/** A client slot history is reset only by this connection's outstanding fence. */
class SlotCursor(slot: Int, private val generation: Long) {
    private var slot = slot
    private var pending: Int? = null

    init {
        validate(slot)
    }

    @Synchronized
    fun scroll(next: Int): Int {
        validate(next)
        val delta = Math.floorMod(next - slot, 9)
        slot = next
        return if (delta == 0) 0 else if (delta <= 4) 1 else -1
    }

    @Synchronized fun slot(): Int = slot

    @Synchronized
    fun fence(id: Int) {
        pending = id
    }

    @Synchronized fun awaiting(): Boolean = pending != null

    @Synchronized fun matches(id: Int): Boolean = pending == id

    @Synchronized
    fun pong(id: Int, generation: Long, anchor: Int): Boolean {
        validate(anchor)
        if (this.generation != generation || !matches(id)) return false
        slot = anchor
        pending = null
        return true
    }

    companion object {
        private fun validate(slot: Int) {
            require(slot in 0..8) { "slot" }
        }
    }
}
