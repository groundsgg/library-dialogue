package gg.grounds.dialogue.json

import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.port.HostBindings
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueRuntime
import gg.grounds.dialogue.core.runtime.DialogueStart
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogueCatalogLoaderTest {
    @Test
    fun failedReloadKeepsPinnedSessions() {
        val loader = DialogueCatalogLoader()
        assertTrue(loader.reload(source("Hello", DialogueJsonCodecTest.SOURCE)).isValid())
        val pinned = loader.current().orElseThrow().entries()["demo:test"]!!
        val first = pinned.contentPin()
        assertTrue(loader.reload(source("Changed", DialogueJsonCodecTest.SOURCE)).isValid())
        val second = loader.current().orElseThrow().entries()["demo:test"]!!.contentPin()
        assertNotEquals(first, second)
        assertFalse(
            loader
                .reload(
                    source(
                        "Broken",
                        DialogueJsonCodecTest.SOURCE.replace(
                            "\"next\":\"end\"",
                            "\"next\":\"unknown\"",
                        ),
                    )
                )
                .isValid()
        )
        assertEquals(second, loader.current().orElseThrow().entries()["demo:test"]!!.contentPin())
        assertEquals("Hello", pinned.texts().locales()["en"]!!["line"])
    }

    @Test
    fun sourcesAreImmutableAndDuplicateIdsRejectWholeReload() {
        val definition = DialogueJsonCodecTest.bytes(DialogueJsonCodecTest.SOURCE)
        val sources = source("Hello", DialogueJsonCodecTest.SOURCE)
        val bytes = sources.dialogues()["mechanic.json"]!!
        bytes[0] = 0
        val loader = DialogueCatalogLoader()
        assertTrue(loader.reload(sources).isValid())
        val last = loader.current().orElseThrow()
        val repeated =
            DialogueCatalogLoader.ContentSources(
                mapOf("a.json" to definition, "b.json" to definition),
                sources.texts(),
                sources.hostCatalog(),
            )
        assertFalse(loader.reload(repeated).isValid())
        assertSame(last, loader.current().orElseThrow())
    }

    @Test
    fun runningSessionsKeepTheirOwnContentPin() {
        val loader = DialogueCatalogLoader()
        loader.reload(source("Hello", DialogueJsonCodecTest.SOURCE))
        val first = loader.current().orElseThrow().entries()["demo:test"]!!
        val clock =
            object : DialogueScheduler {
                override fun execute(runnable: Runnable) = runnable.run()

                override fun schedule(
                    duration: Duration,
                    runnable: Runnable,
                ): DialogueScheduler.ScheduledTask = DialogueScheduler.ScheduledTask {}

                override fun nanoTime(): Long = 0
            }
        val runtime = DialogueRuntime.create(RuntimeOptions.defaults(clock))
        val start = { id: String, validated: ValidatedDialogue ->
            val renderer = DialogueRenderer {
                CompletableFuture.completedFuture(
                    object : DialogueRenderer.PresentationHandle {
                        override fun replace(view: DialogueView): CompletionStage<Void> =
                            CompletableFuture.completedFuture(null)

                        override fun reconfigure(
                            preferences: PresentationPreferences
                        ): CompletionStage<Void> = CompletableFuture.completedFuture(null)

                        override fun closeAsync(): CompletionStage<Void> =
                            CompletableFuture.completedFuture(null)
                    }
                )
            }
            val bindings =
                HostBindings(
                    id,
                    emptyMap(),
                    emptyMap(),
                    null,
                    TextResolver.catalogue(validated.texts(), "en"),
                    renderer,
                    null,
                    emptySet(),
                )
            val request =
                DialogueStart(
                    id,
                    id,
                    1,
                    validated,
                    bindings,
                    emptyMap(),
                    LocaleContext("en", 0, "en"),
                    PresentationPreferences("headless", Duration.ZERO, true, emptyMap()),
                    CancellationToken(),
                    0,
                )
            (runtime.start(request).toCompletableFuture().join() as DialogueStartResult.Started)
                .handle
        }
        val firstHandle = start("a", first)
        loader.reload(source("Changed", DialogueJsonCodecTest.SOURCE))
        val second = loader.current().orElseThrow().entries()["demo:test"]!!
        val secondHandle = start("b", second)
        assertEquals("Hello", firstHandle.snapshot().text())
        assertEquals("Changed", secondHandle.snapshot().text())
        assertEquals(first.contentPin(), firstHandle.snapshot().contentPin())
        assertEquals(second.contentPin(), secondHandle.snapshot().contentPin())
        runtime.closeAsync().toCompletableFuture().join()
    }

    @Test
    fun sharedAuthorContextIsReusedAcrossDefinitions() {
        val initial = source("Hello", DialogueJsonCodecTest.SOURCE)
        val sources =
            DialogueCatalogLoader.ContentSources(
                mapOf(
                    "a.json" to DialogueJsonCodecTest.bytes(DialogueJsonCodecTest.SOURCE),
                    "b.json" to
                        DialogueJsonCodecTest.bytes(
                            DialogueJsonCodecTest.SOURCE.replace("demo:test", "demo:other")
                        ),
                ),
                initial.texts(),
                initial.hostCatalog(),
            )
        val loader = DialogueCatalogLoader()
        assertTrue(loader.reload(sources).isValid())
        val entries = loader.current().orElseThrow().entries()
        assertSame(entries["demo:test"]!!.texts(), entries["demo:other"]!!.texts())
        assertSame(entries["demo:test"]!!.catalog(), entries["demo:other"]!!.catalog())
    }

    companion object {
        fun source(line: String, definition: String): DialogueCatalogLoader.ContentSources =
            DialogueCatalogLoader.ContentSources(
                mapOf("mechanic.json" to DialogueJsonCodecTest.bytes(definition)),
                mapOf("en" to DialogueJsonCodecTest.bytes("{\"line\":\"$line\"}")),
                DialogueJsonCodecTest.bytes(DialogueJsonCodecTest.HOST),
            )
    }
}
