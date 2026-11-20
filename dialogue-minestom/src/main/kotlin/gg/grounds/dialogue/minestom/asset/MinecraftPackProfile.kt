package gg.grounds.dialogue.minestom.asset

/** Exact supported wire/pack combinations; the application supplies its corresponding runtime. */
@JvmRecord
data class MinecraftPackProfile(
    val id: String,
    val minecraft: String,
    val protocol: Int,
    val packMajor: Int,
    val packMinor: Int,
    val postEffects: Boolean,
) {
    init {
        require(
            (id == "public-26.2" &&
                minecraft == "26.2" &&
                protocol == 776 &&
                packMajor == 88 &&
                packMinor == 0 &&
                !postEffects) ||
                (id == "grounds-26.3" &&
                    minecraft == "26.3" &&
                    protocol == 777 &&
                    packMajor == 97 &&
                    packMinor == 1 &&
                    postEffects)
        ) {
            "UNSUPPORTED_PROFILE"
        }
    }

    companion object {
        @JvmStatic
        fun public262(): MinecraftPackProfile =
            MinecraftPackProfile("public-26.2", "26.2", 776, 88, 0, false)

        @JvmStatic
        fun grounds263(): MinecraftPackProfile =
            MinecraftPackProfile("grounds-26.3", "26.3", 777, 97, 1, true)
    }
}
