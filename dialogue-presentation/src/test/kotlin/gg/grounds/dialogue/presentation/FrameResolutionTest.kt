package gg.grounds.dialogue.presentation

import gg.grounds.dialogue.core.runtime.PresentationPreferences
import java.time.Duration
import java.util.function.Function
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameResolutionTest {
    @Test
    fun semanticCompositionBypassesAllGraphicalCallbacks() {
        val profile =
            DialoguePresenter.SurfaceProfile(
                PresenterFixtures.Surface(),
                null,
                null,
                null,
                true,
                Function { error("Semantic layout must not resolve") },
            )
        val presenter =
            DialoguePresenter(
                mapOf("hud" to profile),
                PresenterFixtures.Clock(),
                DialoguePresenter.Controls.defaults(),
                UiLabelSource { error("Semantic labels must not resolve") },
            )
        val view = PresenterFixtures.view("你好", 2, "c1", 1)
        val frame = presenter.compose(view, "hud", PresentationPreferences.defaults(), 3)
        assertSame(view, frame.semantic())
        assertTrue(frame.widgets().isEmpty())
        assertEquals("semantic", frame.fontPin())
    }

    @Test
    fun graphicalCallbacksRunOnceInOrderBeforeTextLayout() {
        val events = arrayListOf<String>()
        val metrics =
            object : FontMetrics {
                override fun supportsControlGlyphs(): Boolean {
                    events += "capability"
                    return false
                }

                override fun width(grapheme: String): Double {
                    events += "width"
                    return 6.0
                }

                override fun lineHeight() = 10.0

                override fun pin(): String {
                    events += "pin"
                    return "fixture"
                }
            }
        val presenter =
            presenter(
                metrics,
                Function {
                    events += "layout"
                    LayoutProfile.hud()
                },
                UiLabelSource {
                    events += "labels"
                    UiLabelSource.UiLabels("Continue", "", emptyMap())
                },
            )
        presenter.compose(
            PresenterFixtures.view("Hello", 5, "c1", 1),
            "hud",
            PresentationPreferences.defaults(),
        )
        assertEquals(listOf("layout", "capability", "labels"), events.take(3))
        assertEquals(1, events.count { it == "layout" })
        assertEquals(1, events.count { it == "capability" })
        assertEquals(1, events.count { it == "labels" })
        assertTrue(events.indexOf("width") > events.indexOf("labels"))
        assertEquals("pin", events.last())
    }

    @Test
    fun resolutionFailuresPrecedeLayoutFallbacks() {
        val failure = IllegalStateException("Resolution failed")
        val view = PresenterFixtures.view("你好", 2, "c1", 1)
        val layoutFailure =
            presenter(
                FontMetrics.defaults(),
                Function { throw failure },
                UiLabelSource { error("Unexpected labels") },
            )
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                layoutFailure.compose(view, "hud", PresentationPreferences.defaults())
            },
        )
        val labelFailure =
            presenter(
                FontMetrics.defaults(),
                Function { LayoutProfile.hud() },
                UiLabelSource { throw failure },
            )
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                labelFailure.compose(view, "hud", PresentationPreferences.defaults())
            },
        )
        val missing =
            assertThrows(DialoguePresenter.FallbackRequired::class.java) {
                labelFailure.compose(view, "missing", PresentationPreferences.defaults())
            }
        assertEquals("MISSING_SURFACE", missing.code())
    }

    @Test
    fun reducedMotionPreservesThemeAndGeometryForPanelsAndHud() {
        val theme = DialogueTheme.neutral()
        for (layout in
            listOf(
                LayoutProfile.hud(),
                LayoutProfile.compactHud(LayoutProfile.ChoicePlacement.RIGHT),
            )) {
            val presenter =
                DialoguePresenter(
                    mapOf(
                        "hud" to
                            DialoguePresenter.SurfaceProfile(
                                PresenterFixtures.Surface(),
                                layout,
                                theme,
                                HudPlacementTest.hudMetrics(),
                                false,
                            )
                    ),
                    PresenterFixtures.Clock(),
                )
            val view = PresenterFixtures.view("Hello", 5, "c1", 1)
            val normal = presenter.compose(view, "hud", PresentationPreferences.defaults())
            val reduced =
                presenter.compose(
                    view,
                    "hud",
                    PresentationPreferences("hud", Duration.ZERO, true, emptyMap()),
                )
            assertSame(theme, normal.theme())
            assertSame(theme, reduced.theme())
            assertEquals(
                normal.widgets().map { it.hitRect() },
                reduced.widgets().map { it.hitRect() },
            )
            assertEquals(
                normal.widgets().map { it.visualScale },
                reduced.widgets().map { it.visualScale },
            )
            assertTrue(normal.widgets().all { it.transition == theme.transition() })
            assertTrue(reduced.widgets().all { it.transition == Duration.ZERO })
        }
    }

    private fun presenter(
        metrics: FontMetrics,
        resolver: Function<PresentationPreferences, LayoutProfile>,
        labels: UiLabelSource,
    ) =
        DialoguePresenter(
            mapOf(
                "hud" to
                    DialoguePresenter.SurfaceProfile(
                        PresenterFixtures.Surface(),
                        LayoutProfile.hud(),
                        DialogueTheme.neutral(),
                        metrics,
                        false,
                        resolver,
                    )
            ),
            PresenterFixtures.Clock(),
            DialoguePresenter.Controls.defaults(),
            labels,
        )
}
