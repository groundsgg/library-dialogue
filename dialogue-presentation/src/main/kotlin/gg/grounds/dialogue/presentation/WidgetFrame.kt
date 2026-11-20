package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.text.ResolvedText
import java.time.Duration

/** Explicit interaction metadata; visual scaling never mutates hit rectangles. */
class WidgetFrame(
    private val semantic: DialogueView,
    widgets: List<Widget>,
    private val fontPin: String,
    private val theme: DialogueTheme,
    private val chrome: Chrome?,
    private val scrollbar: Scrollbar?,
) {
    private val widgets = java.util.List.copyOf(widgets)

    constructor(
        semantic: DialogueView,
        widgets: List<Widget>,
        fontPin: String,
        theme: DialogueTheme,
        chrome: Chrome?,
    ) : this(semantic, widgets, fontPin, theme, chrome, null)

    constructor(
        semantic: DialogueView,
        widgets: List<Widget>,
        fontPin: String,
        theme: DialogueTheme,
    ) : this(semantic, widgets, fontPin, theme, null)

    constructor(
        semantic: DialogueView,
        widgets: List<Widget>,
        fontPin: String,
        themeId: String,
    ) : this(semantic, widgets, fontPin, DialogueTheme(themeId, emptyMap(), 1.0, Duration.ZERO))

    fun semantic(): DialogueView = semantic

    fun widgets(): List<Widget> = widgets

    fun fontPin(): String = fontPin

    fun theme(): DialogueTheme = theme

    fun chrome(): Chrome? = chrome

    fun scrollbar(): Scrollbar? = scrollbar

    /** Union includes negative coordinates, e.g. choices to the left of the speaker. */
    fun canvasBounds(): Rect {
        val rects = ArrayList<Rect>()
        widgets.forEach { rects.add(it.bounds) }
        chrome?.let { rects.add(it.bounds) }
        scrollbar?.let {
            rects.add(it.track())
            it.buttons().forEach { button -> rects.add(button.bounds) }
        }
        if (rects.isEmpty()) return Rect(0.0, 0.0, 0.0, 0.0)
        val left = rects.minOf { it.x }
        val top = rects.minOf { it.y }
        val right = rects.maxOf { it.x + it.width }
        val bottom = rects.maxOf { it.y + it.height }
        return Rect(left, top, right - left, bottom - top)
    }

    fun themeId(): String = theme.id()

    override fun equals(other: Any?): Boolean =
        other is WidgetFrame &&
            semantic == other.semantic &&
            widgets == other.widgets &&
            fontPin == other.fontPin &&
            theme == other.theme &&
            chrome == other.chrome &&
            scrollbar == other.scrollbar

    override fun hashCode(): Int =
        (((((semantic.hashCode() * 31 + widgets.hashCode()) * 31 + fontPin.hashCode()) * 31 +
            theme.hashCode()) * 31 + (chrome?.hashCode() ?: 0)) * 31 + (scrollbar?.hashCode() ?: 0))

    override fun toString(): String =
        "WidgetFrame[semantic=$semantic, widgets=$widgets, fontPin=$fontPin, theme=$theme, chrome=$chrome, scrollbar=$scrollbar]"

    /** Shared backdrop metadata, independent of a concrete game/client API. */
    @JvmRecord
    data class Chrome(val key: String, val bounds: Rect, val baseline: Double) {
        init {
            require(baseline.isFinite()) { "Invalid chrome baseline" }
        }
    }

    enum class NavigationDirection {
        PREVIOUS,
        NEXT,
    }

    /** Page offsets browse presentation only; the legacy choice target constructor is retained. */
    @JvmRecord
    data class NavigationButton(
        val direction: NavigationDirection,
        val bounds: Rect,
        val choiceId: String?,
        val pageStart: Int?,
    ) {
        constructor(
            direction: NavigationDirection,
            bounds: Rect,
            choiceId: String?,
        ) : this(direction, bounds, choiceId, null)

        init {
            require(pageStart == null || (pageStart >= 0 && choiceId == null)) {
                "Invalid navigation page"
            }
            require(choiceId == null || choiceId.isNotBlank()) { "Invalid navigation choice" }
        }

        fun enabled(): Boolean = choiceId != null || pageStart != null

        companion object {
            @JvmStatic
            fun page(direction: NavigationDirection, bounds: Rect, start: Int?): NavigationButton =
                NavigationButton(direction, bounds, null, start)
        }
    }

    /** Overflow indicator and optional page controls in the shared layout coordinate system. */
    class Scrollbar(
        private val track: Rect,
        private val thumb: Rect,
        buttons: List<NavigationButton>,
    ) {
        private val buttons = java.util.List.copyOf(buttons)

        constructor(track: Rect, thumb: Rect) : this(track, thumb, emptyList())

        init {
            require(
                buttons.size <= 2 && buttons.map { it.direction }.distinct().size == buttons.size
            ) {
                "Duplicate navigation direction"
            }
            require(
                track.width > 0 &&
                    track.height > 0 &&
                    thumb.width == track.width &&
                    thumb.height > 0 &&
                    thumb.x == track.x &&
                    thumb.y >= track.y &&
                    thumb.y + thumb.height <= track.y + track.height
            ) {
                "Invalid scrollbar"
            }
        }

        fun track(): Rect = track

        fun thumb(): Rect = thumb

        fun buttons(): List<NavigationButton> = buttons

        override fun equals(other: Any?): Boolean =
            other is Scrollbar &&
                track == other.track &&
                thumb == other.thumb &&
                buttons == other.buttons

        override fun hashCode(): Int =
            (track.hashCode() * 31 + thumb.hashCode()) * 31 + buttons.hashCode()

        override fun toString(): String = "Scrollbar[track=$track, thumb=$thumb, buttons=$buttons]"
    }

    enum class Kind {
        SPEAKER,
        TEXT,
        CHOICE,
        HINT,
        SCROLLBAR,
    }

    @JvmRecord
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
        init {
            require(
                x.isFinite() &&
                    y.isFinite() &&
                    width.isFinite() &&
                    height.isFinite() &&
                    width >= 0 &&
                    height >= 0
            ) {
                "Invalid rectangle"
            }
        }

        fun contains(px: Double, py: Double): Boolean =
            px >= x && px <= x + width && py >= y && py <= y + height
    }

    @JvmRecord
    data class Widget(
        val id: String,
        val kind: Kind,
        val bounds: Rect,
        val textBounds: Rect,
        val text: ResolvedText,
        val layout: TextLayout.TextLayoutResult,
        val visibleGraphemes: Int,
        val selected: Boolean,
        val enabled: Boolean,
        val visualScale: Double,
        val transition: Duration,
        val choiceId: String?,
    ) {
        fun hitRect(): Rect = bounds
    }
}
