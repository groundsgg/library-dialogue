package gg.grounds.dialogue.presentation

@JvmRecord
data class LayoutProfile(
    val key: String,
    val width: Double,
    val choiceWidth: Double,
    val padding: Double,
    val maxTextLines: Int,
    val choiceCapacity: Int,
    val maxChoiceLines: Int,
    val placement: ChoicePlacement,
    val gap: Double,
    val choicePadding: Double,
    val style: Style,
    val columnGap: Double,
    val navigationButtons: Boolean,
) {
    constructor(
        key: String,
        width: Double,
        choiceWidth: Double,
        padding: Double,
        maxTextLines: Int,
        choiceCapacity: Int,
        maxChoiceLines: Int,
        placement: ChoicePlacement,
        gap: Double,
        choicePadding: Double,
        style: Style,
        columnGap: Double,
    ) : this(
        key,
        width,
        choiceWidth,
        padding,
        maxTextLines,
        choiceCapacity,
        maxChoiceLines,
        placement,
        gap,
        choicePadding,
        style,
        columnGap,
        false,
    )

    constructor(
        key: String,
        width: Double,
        choiceWidth: Double,
        padding: Double,
        maxTextLines: Int,
        choiceCapacity: Int,
        maxChoiceLines: Int,
        placement: ChoicePlacement,
        gap: Double,
        choicePadding: Double,
        style: Style,
    ) : this(
        key,
        width,
        choiceWidth,
        padding,
        maxTextLines,
        choiceCapacity,
        maxChoiceLines,
        placement,
        gap,
        choicePadding,
        style,
        gap,
    )

    constructor(
        key: String,
        width: Double,
        choiceWidth: Double,
        padding: Double,
        maxTextLines: Int,
        choiceCapacity: Int,
        maxChoiceLines: Int,
        placement: ChoicePlacement,
        gap: Double,
        choicePadding: Double,
    ) : this(
        key,
        width,
        choiceWidth,
        padding,
        maxTextLines,
        choiceCapacity,
        maxChoiceLines,
        placement,
        gap,
        choicePadding,
        Style.PANELS,
    )

    constructor(
        key: String,
        width: Double,
        choiceWidth: Double,
        padding: Double,
        maxTextLines: Int,
        choiceCapacity: Int,
        maxChoiceLines: Int,
        placement: ChoicePlacement,
        gap: Double,
    ) : this(
        key,
        width,
        choiceWidth,
        padding,
        maxTextLines,
        choiceCapacity,
        maxChoiceLines,
        placement,
        gap,
        padding,
    )

    init {
        require(
            width.isFinite() &&
                choiceWidth.isFinite() &&
                padding.isFinite() &&
                gap.isFinite() &&
                choicePadding.isFinite() &&
                columnGap.isFinite() &&
                width > 2 * padding &&
                choiceWidth > 2 * choicePadding &&
                padding >= 0 &&
                gap >= 0 &&
                choicePadding >= 0 &&
                columnGap >= 0 &&
                maxTextLines >= 1 &&
                choiceCapacity >= 1 &&
                maxChoiceLines >= 1
        ) {
            "Invalid layout profile"
        }
    }

    enum class Style {
        PANELS,
        HUD,
        HUD_SPLIT,
        HUD_CARDS,
    }

    enum class ChoicePlacement {
        BELOW,
        RIGHT,
        LEFT,
    }

    companion object {
        @JvmStatic
        fun hud(): LayoutProfile =
            LayoutProfile("hud", 320.0, 320.0, 12.0, 3, 3, 2, ChoicePlacement.BELOW, 6.0)

        /**
         * Compact HUD columns; chrome and optional overflow indicators determine the full bounds.
         */
        @JvmStatic
        fun compactHud(placement: ChoicePlacement): LayoutProfile = compactHud(placement, Style.HUD)

        @JvmStatic
        fun compactHud(placement: ChoicePlacement, style: Style): LayoutProfile {
            require(style != Style.PANELS) { "HUD style required" }
            val below = placement == ChoicePlacement.BELOW
            return LayoutProfile(
                "hud-preview",
                if (below) 240.0 else 162.0,
                if (below) 222.0 else 117.0,
                9.0,
                3,
                3,
                2,
                placement,
                3.0,
                0.0,
                style,
            )
        }

        @JvmStatic fun npc(): LayoutProfile = npc(12.0)

        /** Minimum clear column gap; the presenter additionally reserves selection growth. */
        @JvmStatic
        fun npc(columnGap: Double): LayoutProfile =
            LayoutProfile(
                "npc",
                320.0,
                240.0,
                12.0,
                3,
                3,
                2,
                ChoicePlacement.RIGHT,
                6.0,
                12.0,
                Style.PANELS,
                columnGap,
                true,
            )
    }
}
