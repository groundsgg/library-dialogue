package gg.grounds.dialogue.minestom.render

import com.google.gson.Gson
import com.google.gson.JsonParser
import gg.grounds.dialogue.core.port.DialogueScheduler
import gg.grounds.dialogue.core.runtime.CancellationToken
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.LocalOutputChannels
import gg.grounds.dialogue.minestom.geometry.NpcHitTest
import gg.grounds.dialogue.minestom.input.SurfaceInputTargets
import gg.grounds.dialogue.minestom.internal.ConnectionGuard
import gg.grounds.dialogue.minestom.internal.MinestomDialogueScheduler
import gg.grounds.dialogue.presentation.DialoguePresenter
import gg.grounds.dialogue.presentation.DialogueSurface
import gg.grounds.dialogue.presentation.DialogueTheme
import gg.grounds.dialogue.presentation.LayoutProfile
import gg.grounds.dialogue.presentation.WidgetFrame
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.kyori.adventure.key.Key
import net.kyori.adventure.resource.ResourcePackStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@EnvTest
class NpcDialogueSurfaceTest {
    private companion object {
        val GROUNDS_ASSETS by lazy { AssetBundle.loadDefault(MinecraftPackProfile.grounds263()) }
        val LEGACY_GROUNDS_ASSETS by lazy {
            rebindGrounds(GROUNDS_ASSETS.entries() - NATIVE_PATHS.toSet())
        }
        val NATIVE_PATHS =
            listOf(
                    "post_effect/vignette_250_enter_a.json",
                    "post_effect/vignette_250_exit_a.json",
                    "post_effect/vignette_250_enter_b.json",
                    "post_effect/vignette_250_exit_b.json",
                    "shaders/post/vignette_state.fsh",
                    "shaders/post/vignette_animated.fsh",
                    "shaders/post/vignette_copy.fsh",
                    "shaders/include/vignette_clock.glsl",
                )
                .map { "assets/dialogue/$it" }
        val FADING_EFFECTS =
            mapOf("npcBackdrop" to "true", "vignette" to "true", "npcEffectsFadeMs" to "250")

        private fun rebindGrounds(entries: Map<String, ByteArray>): AssetBundle {
            val manifest =
                JsonParser.parseString(GROUNDS_ASSETS.manifestBytes().decodeToString()).asJsonObject
            val hashes = entries.mapValues { ContentFingerprint.hash(it.value).sha256 }
            val gson = Gson()
            manifest.add("files", gson.toJsonTree(hashes))
            manifest.addProperty(
                "pin",
                ContentFingerprint.hash(
                        ContentFingerprint.canonicalBytes(
                            mapOf(
                                "profile" to GROUNDS_ASSETS.profile().id,
                                "files" to hashes,
                                "glyphs" to gson.fromJson(manifest.get("glyphs"), Map::class.java),
                            )
                        )
                    )
                    .sha256,
            )
            return AssetBundle.load(
                GROUNDS_ASSETS.profile(),
                manifest.toString().encodeToByteArray(),
                entries,
            )
        }
    }

    private fun <T> done(env: Env, stage: CompletionStage<T>): T =
        HudDialogueSurfaceTest.done(env, stage)

    private inner class Fixture(
        env: Env,
        gate: CompletableFuture<Void>,
        private val assets: AssetBundle = RenderFixtures.ASSETS,
        private val time: AtomicLong? = null,
    ) {
        val first: Player
        val second: Player
        val targets = SurfaceInputTargets()
        val hud = ArrayList<Component>()
        val output = ArrayList<List<Key>>()
        var writeGate = CompletableFuture.completedFuture<Void>(null)
        val packs = PackStateTracker()
        val token = CancellationToken()
        val channels =
            LocalOutputChannels(
                { _, component ->
                    hud.add(component)
                    CompletableFuture.completedFuture(null)
                },
                assets.profile().postEffects,
                { _, keys ->
                    output.add(keys)
                    writeGate
                },
            )
        val guard: ConnectionGuard
        val feet = AtomicReference(Vec(0.0, 42.0, 0.0))
        var anchorUnavailable = false
        val surface: NpcDialogueSurface

        init {
            val instance = env.createFlatInstance()
            first = env.createConnection().connect(instance, Pos(0.0, 42.0, 4.0, 180f, 0f))
            second = env.createConnection().connect(instance, Pos(0.0, 42.0, 4.0))
            guard = ConnectionGuard(first, 1, instance)
            val id = UUID.randomUUID()
            packs.expect(first, 1, id, assets.pin())
            packs.observe(
                first,
                1,
                id,
                assets.pin(),
                ResourcePackStatus.SUCCESSFULLY_LOADED,
            )
            val binding =
                MinestomSpeakerBinding("npc1", instance) {
                    if (anchorUnavailable) throw IOException("Anchor unavailable")
                    MinestomSpeakerBinding.AnchorSnapshot(
                        feet.get(),
                        0f,
                        feet.get().add(0.0, 1.6, 0.0),
                        true,
                    )
                }
            val scheduler = MinestomDialogueScheduler(instance)
            surface =
                NpcDialogueSurface(
                    first,
                    1,
                    guard,
                    packs,
                    assets,
                    channels,
                    targets,
                    object : DialogueScheduler by scheduler {
                        override fun nanoTime(): Long = time?.get() ?: scheduler.nanoTime()
                    },
                    { binding },
                    {},
                    { entity, world, position ->
                        entity.setInstance(world, position).thenCompose { gate }
                    },
                )
        }

        fun open(
            env: Env,
            effects: Map<String, String> = emptyMap(),
            cleanupBudget: Duration = Duration.ofSeconds(2),
            surfaceGeneration: Long = 1,
        ): DialogueSurface.SurfaceHandle =
            done(
                env,
                surface.open(
                    DialogueSurface.SurfaceContext(
                        RenderFixtures.SESSION,
                        first.uuid.toString(),
                        1,
                        "npc",
                        "profile",
                        token,
                        surfaceGeneration,
                        Long.MAX_VALUE,
                        PresentationPreferences("npc", Duration.ZERO, false, effects),
                        cleanupBudget,
                    )
                ),
            )

        fun frame(text: String = "Hello"): WidgetFrame {
            val frame = RenderFixtures.frame("npc", text, text.length)
            return WidgetFrame(
                frame.semantic(),
                frame.widgets(),
                assets.pin(),
                frame.theme(),
                frame.chrome(),
                frame.scrollbar(),
            )
        }

        fun backdrops(): List<Entity> =
            displays().filter {
                PlainTextComponentSerializer.plainText()
                    .serialize((it.entityMeta as TextDisplayMeta).text) == "\ue300"
            }

        fun backdrop(): Entity =
            backdrops().single { (it.entityMeta as TextDisplayMeta).leftRotation[1] == 0f }

        fun opacity(): Int = (backdrop().entityMeta as TextDisplayMeta).textOpacity.toInt() and 255

        fun displays(): List<Entity> =
            first.instance.entities.filter { it.entityType == EntityType.TEXT_DISPLAY }
    }

    @Test
    fun onlyEffectsFadeAndReplacingFramesPreservesProgress(env: Env) {
        val time = AtomicLong()
        val fixture = groundsFixture(env, time)
        val foreignKey = Key.key("example:other")
        val foreign = fixture.channels.acquireEffects(fixture.first, 1)
        done(env, foreign.replace(listOf(foreignKey)))
        val handle = openFading(env, fixture)
        val frame = fixture.frame()
        done(env, handle.replace(frame))
        assertEquals(frame.widgets().size * 2 + 2, fixture.displays().size)
        assertNotNull(fixture.targets.current())
        val backdrops = fixture.backdrops()
        val panels = fixture.displays().filter { it !in backdrops }
        assertTrue(panels.all { (it.entityMeta as TextDisplayMeta).textOpacity.toInt() == -1 })
        assertEquals(255, fixture.opacity())
        assertEquals(listOf(foreignKey), fixture.output.last())

        time.set(Duration.ofMillis(50).toNanos())
        expectVignette(env, fixture, Key.key("dialogue:vignette_fade_1"))
        assertEquals(255, fixture.opacity())
        assertEquals(listOf(foreignKey, Key.key("dialogue:vignette_fade_1")), fixture.output.last())

        for (millis in listOf(100L, 150L, 200L, 250L)) {
            done(env, handle.replace(fixture.frame("Hello ${millis / 50}")))
            time.set(Duration.ofMillis(millis).toNanos())
            expectVignette(
                env,
                fixture,
                if (millis == 250L) Key.key("dialogue:vignette")
                else Key.key("dialogue:vignette_fade_${millis / 50}"),
            )
            assertEquals(255, fixture.opacity())
        }
        assertEquals(
            (1..4).map { Key.key("dialogue:vignette_fade_$it") } + Key.key("dialogue:vignette"),
            fixture.output.drop(1).map { it.single { key -> key != foreignKey } },
        )

        fixture.guard.invalidate()
        fixture.token.cancel(DialogueEndReason.COMPLETED)
        val close = handle.closeAsync()
        assertNull(fixture.targets.current())
        assertTrue(env.tickWhile({ fixture.displays().isNotEmpty() }, Duration.ofSeconds(2)))
        assertTrue(fixture.displays().isEmpty())
        assertFalse(close.toCompletableFuture().isDone)
        time.set(Duration.ofMillis(300).toNanos())
        expectVignette(env, fixture, Key.key("dialogue:vignette_fade_4"))
        assertEquals(listOf(foreignKey, Key.key("dialogue:vignette_fade_4")), fixture.output.last())
        time.set(Duration.ofMillis(500).toNanos())
        done(env, close)
        assertEquals(listOf(foreignKey), fixture.output.last())
        done(env, foreign.closeAsync())
    }

    @Test
    fun unavailableAnchorSkipsFadeWithoutFailingCleanup(env: Env) {
        val time = AtomicLong()
        val fixture = groundsFixture(env, time)
        val handle = openFading(env, fixture)
        done(env, handle.replace(fixture.frame()))
        time.set(Duration.ofMillis(100).toNanos())
        expectVignette(env, fixture, Key.key("dialogue:vignette_fade_2"))
        fixture.anchorUnavailable = true
        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
        assertTrue(fixture.output.last().isEmpty())
    }

    @Test
    fun fullPanelFrameWaitsForObsoletePendingBackdropRetirement(env: Env) {
        val gate = CompletableFuture<Void>()
        val fixture = Fixture(env, gate)
        val handle = openFading(env, fixture)
        val frame = fixture.frame()
        val pending = handle.replace(frame)
        assertTrue(
            env.tickWhile(
                { fixture.displays().size < frame.widgets().size * 2 + 2 },
                Duration.ofSeconds(2),
            )
        )
        done(
            env,
            handle.configure(PresentationPreferences("npc", Duration.ZERO, false, emptyMap())),
        )
        val template = frame.widgets().first { it.kind == WidgetFrame.Kind.TEXT }
        val widgets =
            frame.widgets() +
                (frame.widgets().size until 16).map { template.copy(id = "extra-$it") }
        val fullFrame =
            WidgetFrame(
                frame.semantic(),
                widgets,
                frame.fontPin(),
                frame.theme(),
                frame.chrome(),
                frame.scrollbar(),
            )
        val replacement = handle.replace(fullFrame)
        repeat(5) { env.tick() }
        gate.complete(null)
        done(env, replacement)
        assertTrue(pending.toCompletableFuture().isCompletedExceptionally)
        assertEquals(32, fixture.displays().size)
        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
    }

    @Test
    fun partialFadeReversesAndPackLossStopsFurtherFadeWrites(env: Env) {
        val time = AtomicLong()
        val fixture = groundsFixture(env, time)
        val handle = openFading(env, fixture)
        done(env, handle.replace(fixture.frame()))
        time.set(Duration.ofMillis(100).toNanos())
        expectVignette(env, fixture, Key.key("dialogue:vignette_fade_2"))
        val close = handle.closeAsync()
        assertTrue(env.tickWhile({ fixture.displays().isNotEmpty() }, Duration.ofSeconds(2)))
        assertTrue(fixture.displays().isEmpty())
        assertFalse(close.toCompletableFuture().isDone)
        time.set(Duration.ofMillis(200).toNanos())
        expectVignette(env, fixture, Key.key("dialogue:vignette_fade_1"))
        val previousWrites = fixture.output.size
        fixture.packs.forget(fixture.first)
        done(env, close)
        assertTrue(fixture.displays().isEmpty())
        assertEquals(listOf(emptyList<Key>()), fixture.output.drop(previousWrites))
    }

    @Test
    fun worldChangeAndDisconnectRemoveDisplaysWithoutFurtherOutput(env: Env) {
        for (reason in listOf(DialogueEndReason.HOST_CHANGED, DialogueEndReason.DISCONNECTED)) {
            val time = AtomicLong()
            val fixture = groundsFixture(env, time)
            val handle = openFading(env, fixture)
            done(env, handle.replace(fixture.frame()))
            time.set(Duration.ofMillis(100).toNanos())
            expectVignette(env, fixture, Key.key("dialogue:vignette_fade_2"))
            val originalInstance = fixture.first.instance
            val writes = fixture.output.toList()
            if (reason == DialogueEndReason.HOST_CHANGED) {
                fixture.first.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
            } else {
                fixture.first.kick("Disconnected")
                assertFalse(fixture.first.isOnline)
            }
            fixture.token.cancel(reason)
            done(env, handle.closeAsync())
            assertTrue(originalInstance.entities.none { it.entityType == EntityType.TEXT_DISPLAY })
            assertEquals(writes, fixture.output)
        }
    }

    @Test
    fun slowEffectTransportSkipsMissedStepsWithoutDelayingPanels(env: Env) {
        val time = AtomicLong()
        val fixture = groundsFixture(env, time)
        val handle = openFading(env, fixture)
        val gate = CompletableFuture<Void>()
        fixture.writeGate = gate
        done(env, handle.replace(fixture.frame()))
        time.set(Duration.ofMillis(50).toNanos())
        expectVignette(env, fixture, Key.key("dialogue:vignette_fade_1"))
        time.set(Duration.ofMillis(250).toNanos())
        assertEquals(255, fixture.opacity())
        assertEquals(listOf(listOf(Key.key("dialogue:vignette_fade_1"))), fixture.output)
        fixture.writeGate = CompletableFuture.completedFuture(null)
        gate.complete(null)
        assertTrue(env.tickWhile({ fixture.output.size < 2 }, Duration.ofSeconds(2)))
        assertEquals(listOf(Key.key("dialogue:vignette")), fixture.output.last())
        fixture.token.cancel(DialogueEndReason.HOST_CLOSED)
        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
        assertEquals(3, fixture.output.size)
    }

    @Test
    fun zeroFadeStillPublishesFullOpacityImmediately(env: Env) {
        val fixture = groundsFixture(env, AtomicLong())
        val handle = fixture.open(env, mapOf("npcBackdrop" to "true", "vignette" to "true"))
        done(env, handle.replace(fixture.frame()))
        assertEquals(255, fixture.opacity())
        assertEquals(listOf(listOf(Key.key("dialogue:vignette"))), fixture.output)
        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
    }

    @Test
    fun nativeVignetteKeepsPanelsImmediateAndAppendsMatchingExitOnEarlyClose(env: Env) {
        val time = AtomicLong()
        val fixture = nativeFixture(env, time)
        val handle = openFading(env, fixture)
        val entryGate = CompletableFuture<Void>()
        fixture.writeGate = entryGate
        val frame = fixture.frame()
        done(env, handle.replace(frame))
        val enter = Key.key("dialogue:vignette_250_enter_a")
        val exit = Key.key("dialogue:vignette_250_exit_a")
        assertEquals(listOf(listOf(enter)), fixture.output)
        assertEquals(255, fixture.opacity())
        assertNotNull(fixture.targets.current())
        assertEquals(frame.widgets().size * 2 + 2, fixture.displays().size)

        time.set(Duration.ofMillis(50).toNanos())
        val close = handle.closeAsync()
        assertTrue(env.tickWhile({ fixture.displays().isNotEmpty() }, Duration.ofSeconds(2)))
        assertNull(fixture.targets.current())
        assertFalse(close.toCompletableFuture().isDone)
        fixture.writeGate = CompletableFuture.completedFuture(null)
        entryGate.complete(null)
        expectVignette(env, fixture, exit)
        assertEquals(listOf(listOf(enter), listOf(enter, exit)), fixture.output)
        time.set(Duration.ofMillis(549).toNanos())
        env.tick()
        assertFalse(close.toCompletableFuture().isDone)
        time.set(Duration.ofMillis(550).toNanos())
        done(env, close)
        assertEquals(listOf(listOf(enter), listOf(enter, exit), emptyList()), fixture.output)
    }

    @Test
    fun nativeEntryPersistsWithoutPaletteWritesAndNextActivationUsesOtherBank(env: Env) {
        val time = AtomicLong()
        val fixture = nativeFixture(env, time)
        val first = openFading(env, fixture)
        val enterA = Key.key("dialogue:vignette_250_enter_a")
        val exitA = Key.key("dialogue:vignette_250_exit_a")
        val enterB = Key.key("dialogue:vignette_250_enter_b")
        val exitB = Key.key("dialogue:vignette_250_exit_b")
        done(env, first.replace(fixture.frame()))
        for (elapsed in listOf(50L, 100L, 250L, 400L)) {
            time.set(Duration.ofMillis(elapsed).toNanos())
            done(env, first.replace(fixture.frame("More")))
            assertEquals(255, fixture.opacity())
            assertEquals(listOf(listOf(enterA)), fixture.output)
        }
        val firstClose = first.closeAsync()
        expectVignette(env, fixture, exitA)
        val second = fixture.open(env, FADING_EFFECTS, surfaceGeneration = 2)
        done(env, second.replace(fixture.frame()))
        assertEquals(listOf(enterB), fixture.output.last())
        time.set(Duration.ofMillis(900).toNanos())
        done(env, firstClose)
        assertEquals(listOf(enterB), fixture.output.last())
        assertNotNull(fixture.targets.current())

        val secondClose = second.closeAsync()
        expectVignette(env, fixture, exitB)
        assertEquals(listOf(enterB, exitB), fixture.output.last())
        time.set(Duration.ofMillis(1400).toNanos())
        done(env, secondClose)
        val third = fixture.open(env, FADING_EFFECTS, surfaceGeneration = 3)
        done(env, third.replace(fixture.frame()))
        assertEquals(listOf(enterA), fixture.output.last())
        fixture.token.cancel(DialogueEndReason.HOST_CLOSED)
        done(env, third.closeAsync())
    }

    @Test
    fun pendingTransportCannotKeepNativeLeasePastCloseDeadline(env: Env) {
        val time = AtomicLong()
        val fixture = nativeFixture(env, time)
        val enter = Key.key("dialogue:vignette_250_enter_a")
        val gate = CompletableFuture<Void>()
        fixture.writeGate = gate
        val handle = openFading(env, fixture)
        done(env, handle.replace(fixture.frame()))
        val close = handle.closeAsync()
        assertTrue(env.tickWhile({ fixture.displays().isNotEmpty() }, Duration.ofSeconds(2)))
        time.set(Duration.ofMillis(500).toNanos())
        // Schedule enough instance work for the guard poll to retire the effect source.
        assertFalse(env.tickWhile({ true }, Duration.ofMillis(150)))
        fixture.writeGate = CompletableFuture.completedFuture(null)
        gate.complete(null)
        done(env, close)
        assertEquals(listOf(listOf(enter), emptyList()), fixture.output)
    }

    @Test
    fun failedNativeEntryRemovesPublishedPanelsAndOwnedEffect(env: Env) {
        val fixture = nativeFixture(env, AtomicLong())
        val gate = CompletableFuture<Void>()
        fixture.writeGate = gate
        val handle = openFading(env, fixture)
        done(env, handle.replace(fixture.frame()))
        assertNotNull(fixture.targets.current())
        assertTrue(fixture.displays().isNotEmpty())
        fixture.writeGate = CompletableFuture.completedFuture(null)
        gate.completeExceptionally(IOException("Effect write failed"))
        assertTrue(env.tickWhile({ fixture.token.reason() == null }, Duration.ofSeconds(2)))
        done(env, handle.closeAsync())
        assertEquals(DialogueEndReason.FAILED, fixture.token.reason())
        assertNull(fixture.targets.current())
        assertTrue(fixture.displays().isEmpty())
        assertEquals(emptyList<Key>(), fixture.output.last())
    }

    @Test
    fun nativeGraceEndsWhenPackOrConnectionGuardIsLost(env: Env) {
        for (loseGuard in listOf(false, true)) {
            val fixture = nativeFixture(env, AtomicLong())
            val enter = Key.key("dialogue:vignette_250_enter_a")
            val exit = Key.key("dialogue:vignette_250_exit_a")
            val handle = openFading(env, fixture)
            done(env, handle.replace(fixture.frame()))
            val close = handle.closeAsync()
            expectVignette(env, fixture, exit)
            if (loseGuard) fixture.guard.invalidate() else fixture.packs.forget(fixture.first)
            done(env, close)
            assertEquals(listOf(listOf(enter), listOf(enter, exit), emptyList()), fixture.output)
        }
    }

    @Test
    fun hardNativeCloseDoesNotSendAnExitEffect(env: Env) {
        for (reason in listOf(DialogueEndReason.HOST_CHANGED, DialogueEndReason.DISCONNECTED)) {
            val fixture = nativeFixture(env, AtomicLong())
            val handle = openFading(env, fixture)
            done(env, handle.replace(fixture.frame()))
            val world = fixture.first.instance
            val before = fixture.output.toList()
            if (reason == DialogueEndReason.HOST_CHANGED) {
                fixture.first.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
            } else {
                fixture.first.kick("Disconnected")
                assertFalse(fixture.first.isOnline)
            }
            fixture.token.cancel(reason)
            done(env, handle.closeAsync())
            assertTrue(world.entities.none { it.entityType == EntityType.TEXT_DISPLAY })
            assertNull(fixture.targets.current())
            assertEquals(
                if (reason == DialogueEndReason.HOST_CHANGED) before + listOf(emptyList())
                else before,
                fixture.output,
            )
        }
    }

    @Test
    fun nativeCloseWithInsufficientBudgetReleasesEffectsImmediately(env: Env) {
        val fixture = nativeFixture(env, AtomicLong())
        val handle = fixture.open(env, FADING_EFFECTS, Duration.ofMillis(500))
        done(env, handle.replace(fixture.frame()))
        done(env, handle.closeAsync())
        assertEquals(
            listOf(listOf(Key.key("dialogue:vignette_250_enter_a")), emptyList()),
            fixture.output,
        )
    }

    @Test
    fun livePreferencesReplaceNativeEffectAndRestartWithOtherBank(env: Env) {
        val fixture = nativeFixture(env, AtomicLong())
        val handle = openFading(env, fixture)
        val frame = fixture.frame()
        done(env, handle.replace(frame))
        assertEquals(listOf(Key.key("dialogue:vignette_250_enter_a")), fixture.output.last())

        fun renderWith(effects: Map<String, String>) {
            done(
                env,
                handle.configure(PresentationPreferences("npc", Duration.ZERO, false, effects)),
            )
            done(env, handle.replace(frame))
        }

        renderWith(FADING_EFFECTS + ("npcEffectsFadeMs" to "100"))
        assertEquals(listOf(Key.key("dialogue:vignette")), fixture.output.last())
        renderWith(FADING_EFFECTS)
        assertEquals(listOf(Key.key("dialogue:vignette_250_enter_b")), fixture.output.last())
        renderWith(FADING_EFFECTS + ("vignette" to "false"))
        assertEquals(emptyList<Key>(), fixture.output.last())
        renderWith(FADING_EFFECTS)
        assertEquals(listOf(Key.key("dialogue:vignette_250_enter_a")), fixture.output.last())
        fixture.token.cancel(DialogueEndReason.HOST_CLOSED)
        done(env, handle.closeAsync())
    }

    @Test
    fun nativeAssetsKeepLegacyPaletteForOtherDurations(env: Env) {
        val time = AtomicLong()
        val fixture = nativeFixture(env, time)
        val handle = fixture.open(env, FADING_EFFECTS + ("npcEffectsFadeMs" to "100"))
        done(env, handle.replace(fixture.frame()))
        assertEquals(emptyList<List<Key>>(), fixture.output)
        time.set(Duration.ofMillis(50).toNanos())
        expectVignette(env, fixture, Key.key("dialogue:vignette_fade_3"))
        fixture.token.cancel(DialogueEndReason.HOST_CLOSED)
        done(env, handle.closeAsync())
    }

    @Test
    fun missingNativeClockAssetUsesLegacyPalette(env: Env) {
        val entries =
            GROUNDS_ASSETS.entries() - "assets/dialogue/shaders/include/vignette_clock.glsl"
        val time = AtomicLong()
        val fixture =
            Fixture(env, CompletableFuture.completedFuture(null), rebindGrounds(entries), time)
        val handle = openFading(env, fixture)
        done(env, handle.replace(fixture.frame()))
        assertEquals(emptyList<List<Key>>(), fixture.output)
        time.set(Duration.ofMillis(50).toNanos())
        expectVignette(env, fixture, Key.key("dialogue:vignette_fade_1"))
        fixture.token.cancel(DialogueEndReason.HOST_CLOSED)
        done(env, handle.closeAsync())
    }

    @Test
    fun closingWhileNativePanelsAreSpawningCannotDispatchAnEnterOrExit(env: Env) {
        val spawnGate = CompletableFuture<Void>()
        val fixture = Fixture(env, spawnGate, GROUNDS_ASSETS, AtomicLong())
        val handle = openFading(env, fixture)
        val rendering = handle.replace(fixture.frame())
        assertTrue(env.tickWhile({ fixture.displays().isEmpty() }, Duration.ofSeconds(2)))
        assertTrue(fixture.output.isEmpty())
        val closing = handle.closeAsync()
        assertTrue(
            env.tickWhile({ !rendering.toCompletableFuture().isDone }, Duration.ofSeconds(2))
        )
        assertNull(fixture.targets.current())
        assertFalse(closing.toCompletableFuture().isDone)
        spawnGate.complete(null)
        done(env, closing)
        assertTrue(fixture.displays().isEmpty())
        assertEquals(listOf(emptyList<Key>()), fixture.output)
    }

    private fun nativeFixture(env: Env, time: AtomicLong): Fixture =
        Fixture(env, CompletableFuture.completedFuture(null), GROUNDS_ASSETS, time)

    private fun groundsFixture(env: Env, time: AtomicLong) =
        Fixture(
            env,
            CompletableFuture.completedFuture(null),
            LEGACY_GROUNDS_ASSETS,
            time,
        )

    private fun openFading(env: Env, fixture: Fixture): DialogueSurface.SurfaceHandle =
        fixture.open(
            env,
            FADING_EFFECTS,
        )

    private fun expectVignette(env: Env, fixture: Fixture, key: Key) {
        assertTrue(
            env.tickWhile(
                { fixture.output.lastOrNull()?.contains(key) != true },
                Duration.ofSeconds(2),
            ),
            "Expected $key in vignette writes, got ${fixture.output}",
        )
    }

    @Test
    fun idleNpcCoverageIsRenewedAndCannotReappearAfterClose(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val handle = fixture.open(env, mapOf("hideHud" to "true"))
        done(env, handle.replace(RenderFixtures.frame("npc", "Hello", 5)))
        assertEquals(1, fixture.hud.size)
        val cover = fixture.hud.first()
        assertFalse(cover == Component.empty())
        assertTrue(
            env.tickWhile({ fixture.hud.size < 2 }, Duration.ofMillis(1600)),
            "An idle NPC dialogue must refresh its native ActionBar cover before it fades",
        )
        assertEquals(cover, fixture.hud.last())
        done(env, handle.closeAsync())
        assertEquals(Component.empty(), fixture.hud.last())
        val closedWrites = fixture.hud.size
        assertFalse(
            env.tickWhile({ fixture.hud.size == closedWrites }, Duration.ofMillis(1200)),
            "No cover should be repainted after close",
        )
        assertEquals(closedWrites, fixture.hud.size)
    }

    @Test
    fun supersedingPendingSlotsReusesTheirPreparationAndOwnsRetirement(env: Env) {
        for (removeChoices in listOf(false, true)) {
            val gate = CompletableFuture<Void>()
            val fixture = Fixture(env, gate)
            val handle = fixture.open(env)
            val first = RenderFixtures.frame("npc", "Hello", 5)
            val older = handle.replace(first)
            assertTrue(
                env.tickWhile(
                    { fixture.displays().size < first.widgets().size * 2 },
                    Duration.ofSeconds(2),
                )
            )
            var updated = RenderFixtures.frame("npc", "Updated", 7)
            if (removeChoices) {
                updated =
                    WidgetFrame(
                        updated.semantic(),
                        updated.widgets().filter { it.kind != WidgetFrame.Kind.CHOICE },
                        updated.fontPin(),
                        updated.theme(),
                    )
            }
            val newer = handle.replace(updated)
            repeat(10) { env.tick() }
            assertFalse(newer.toCompletableFuture().isDone, "Await adopted slot preparation")
            assertEquals(
                first.widgets().size * 2,
                fixture.displays().size,
                "No duplicate pending displays",
            )
            gate.complete(null)
            done(env, newer)
            assertTrue(older.toCompletableFuture().isCompletedExceptionally)
            assertEquals(
                updated.widgets().size * 2,
                fixture.displays().size,
                "Retired slots fully removed",
            )
            assertTrue(
                fixture
                    .displays()
                    .map { it.entityMeta as TextDisplayMeta }
                    .any {
                        PlainTextComponentSerializer.plainText()
                            .serialize(it.text)
                            .contains("Updated")
                    }
            )
            done(env, handle.closeAsync())
            assertTrue(fixture.displays().isEmpty())
        }
    }

    @Test
    fun retiringCandidateRestoresRetainedGeometry(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val old = fixture.open(env)
        done(env, old.replace(RenderFixtures.frame("npc", "Hello", 5)))
        val candidate =
            done(
                env,
                fixture.surface.open(
                    DialogueSurface.SurfaceContext(
                        RenderFixtures.SESSION,
                        fixture.first.uuid.toString(),
                        1,
                        "npc",
                        "profile",
                        CancellationToken(),
                        2,
                        Long.MAX_VALUE,
                        PresentationPreferences("npc", Duration.ZERO, false, emptyMap()),
                        Duration.ofSeconds(2),
                    )
                ),
            )
        done(env, candidate.replace(RenderFixtures.frame("npc", "Hello", 5)))
        done(env, candidate.closeAsync())
        assertNotNull(fixture.surface.geometry())
        assertEquals(1, checkNotNull(fixture.surface.geometry()).surfaceGeneration())
        done(env, old.closeAsync())
    }

    @Test
    fun overlappingPendingHandlesShareOneConnectionDisplayBudget(env: Env) {
        val gate = CompletableFuture<Void>()
        val fixture = Fixture(env, gate)
        val handles = ArrayList<DialogueSurface.SurfaceHandle>()
        repeat(4) {
            val handle = fixture.open(env)
            handles.add(handle)
            handle.replace(RenderFixtures.frame("npc", "Hello", 5))
            repeat(10) { env.tick() }
            assertTrue(
                fixture.displays().size <= 32,
                "Pending spawns count against the per-connection budget",
            )
        }
        gate.complete(null)
        handles.forEach { done(env, it.closeAsync()) }
        assertTrue(fixture.displays().isEmpty())
        val handle = fixture.open(env)
        done(env, handle.replace(RenderFixtures.frame("npc", "Hello", 5)))
        assertFalse(fixture.displays().isEmpty())
        done(env, handle.closeAsync())
    }

    @Test
    fun backdropIsBundledForBothProfiles() {
        assertTrue(RenderFixtures.ASSETS.hasEntry("assets/dialogue/font/focus.json"))
        assertTrue(GROUNDS_ASSETS.hasEntry("assets/dialogue/textures/font/npc-focus.png"))
    }

    @Test
    fun npcBackdropHasPrivateOpposingFacesOnTheSameNpcSide(env: Env) {
        val fixture = groundsFixture(env, AtomicLong())
        val handle = fixture.open(env, mapOf("npcBackdrop" to "true"))
        val frame = fixture.frame()
        done(env, handle.replace(frame))

        val backdrops = fixture.backdrops()
        assertEquals(2, backdrops.size)
        assertEquals(frame.widgets().size * 2 + 2, fixture.displays().size)
        for (entity in backdrops) {
            val meta = entity.entityMeta as TextDisplayMeta
            assertEquals("\ue300", PlainTextComponentSerializer.plainText().serialize(meta.text))
            assertEquals(255, meta.textOpacity.toInt() and 255)
            assertEquals(setOf(fixture.first), entity.viewers)
            assertFalse(entity.viewers.contains(fixture.second))
            assertEquals(
                AbstractDisplayMeta.BillboardConstraints.FIXED,
                meta.billboardRenderConstraints,
            )
            assertEquals(Vec(0.6, 0.6, 0.6), meta.scale)
        }
        val front = backdrops.single { (it.entityMeta as TextDisplayMeta).leftRotation[1] == 0f }
        val rear = backdrops.single { (it.entityMeta as TextDisplayMeta).leftRotation[1] > 0.99f }
        val frontMeta = front.entityMeta as TextDisplayMeta
        val rearMeta = rear.entityMeta as TextDisplayMeta
        assertEquals(Vec(-0.0075, -0.93, -0.55), frontMeta.translation)
        assertEquals(0.0, frontMeta.leftRotation[1].toDouble(), 1e-6)
        assertEquals(1.0, rearMeta.leftRotation[1].toDouble(), 1e-6)
        assertEquals(0.0, rearMeta.leftRotation[3].toDouble(), 1e-6)
        val transform = checkNotNull(fixture.surface.geometry()).transform()
        val frontNormal = rotate(Vec(0.0, 0.0, 1.0), frontMeta.leftRotation)
        val rearNormal = rotate(Vec(0.0, 0.0, 1.0), rearMeta.leftRotation)
        assertEquals(-1.0, frontNormal.dot(rearNormal), 1e-6)
        // The native one-line glyph has a one-pixel visual-center offset along local x.
        // At scale 0.6 each pixel is 0.0075 blocks; the rear rotation reverses that offset.
        val frontCenter =
            frontMeta.translation.add(rotate(Vec(0.0075, 0.0, 0.0), frontMeta.leftRotation))
        val rearCenter =
            rearMeta.translation.add(rotate(Vec(0.0075, 0.0, 0.0), rearMeta.leftRotation))
        assertEquals(0.0, frontCenter.x(), 1e-6)
        assertEquals(frontCenter.x(), rearCenter.x(), 1e-6)
        assertEquals(frontCenter.y(), rearCenter.y(), 1e-6)
        assertTrue(frontCenter.z() < 0.0)
        assertTrue(rearCenter.z() < 0.0)
        assertEquals(-0.012, rearCenter.sub(frontCenter).dot(frontNormal), 1e-6)
        assertEquals(transform.position(), front.position)
        assertEquals(transform.position(), rear.position)

        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
    }

    @Test
    fun npcBackdropReservesTwoSlotsAtTheConnectionDisplayBudget(env: Env) {
        val fixture = groundsFixture(env, AtomicLong())
        val handle = fixture.open(env, mapOf("npcBackdrop" to "true"))
        val base = fixture.frame()
        val template = base.widgets().first { it.kind == WidgetFrame.Kind.TEXT }
        fun frameWithWidgetCount(count: Int): WidgetFrame {
            val widgets =
                base.widgets() +
                    (base.widgets().size until count).map { template.copy(id = "extra-$it") }
            return WidgetFrame(
                base.semantic(),
                widgets,
                base.fontPin(),
                base.theme(),
                base.chrome(),
                base.scrollbar(),
            )
        }

        done(env, handle.replace(frameWithWidgetCount(15)))
        assertEquals(32, fixture.displays().size)
        assertEquals(2, fixture.backdrops().size)
        assertThrows(CompletionException::class.java) {
            done(env, handle.replace(frameWithWidgetCount(16)))
        }
        assertEquals(32, fixture.displays().size)
        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
    }

    @Test
    fun unavailableChoiceBoundsSurviveNativePublicationAndFollow(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val handle = fixture.open(env)
        val frame = RenderFixtures.frame("npc", "Hello", 5)
        val widgets =
            frame.widgets().map { widget ->
                if (widget.choiceId == "leave") {
                    WidgetFrame.Widget(
                        widget.id,
                        widget.kind,
                        widget.bounds,
                        widget.textBounds,
                        widget.text,
                        widget.layout,
                        widget.visibleGraphemes,
                        false,
                        false,
                        1.0,
                        Duration.ZERO,
                        widget.choiceId,
                    )
                } else widget
            }
        done(
            env,
            handle.replace(
                WidgetFrame(
                    frame.semantic(),
                    widgets,
                    frame.fontPin(),
                    frame.theme(),
                    frame.chrome(),
                    frame.scrollbar(),
                )
            ),
        )
        val before = checkNotNull(fixture.surface.geometry())
        assertEquals(setOf("ask", "leave"), before.choiceBounds().keys)
        assertEquals(setOf("ask"), checkNotNull(fixture.targets.current()).choices().keys)
        assertTrue(env.tickWhile({ fixture.surface.geometry() === before }, Duration.ofSeconds(1)))
        assertEquals(before.choiceBounds(), checkNotNull(fixture.surface.geometry()).choiceBounds())
        done(env, handle.closeAsync())
    }

    @Test
    fun allFrontBackPairsClose(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val handle = fixture.open(env)
        val frame = RenderFixtures.frame("npc", "Hello", 5)
        done(env, handle.replace(frame))
        assertEquals(frame.widgets().size * 2, fixture.displays().size)
        assertTrue(fixture.displays().size <= 32)
        for (entity in fixture.displays()) {
            assertEquals(setOf(fixture.first), entity.viewers)
            assertFalse(entity.viewers.contains(fixture.second))
            assertEquals(
                AbstractDisplayMeta.BillboardConstraints.FIXED,
                (entity.entityMeta as TextDisplayMeta).billboardRenderConstraints,
            )
        }
        assertEquals(2, checkNotNull(fixture.targets.current()).choices().size)
        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
        assertNull(fixture.targets.current())
    }

    @Test
    fun sevenChoicesUseThreeCardsAndNativeOriginMatchesHitPlane(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val handle = fixture.open(env)
        val base = RenderFixtures.view("Hello", 5)
        val choices =
            (0 until 7).map {
                DialogueView.ChoiceView(
                    "c$it",
                    "Choice $it",
                    DialogueView.Availability.AVAILABLE,
                    "",
                )
            }
        val view =
            DialogueView(
                base.sessionId(),
                base.contentPin(),
                base.nodeId(),
                base.nodeVisitId(),
                base.state(),
                2,
                2,
                "en",
                "NPC",
                "Hello",
                choices,
                "c4",
                5,
                5,
                DialogueView.HintPhase.CHOOSE,
                "",
                null,
            )
        val presenter =
            DialoguePresenter(
                mapOf(
                    "npc" to
                        DialoguePresenter.SurfaceProfile(
                            fixture.surface,
                            LayoutProfile.npc(),
                            DialogueTheme.neutral(),
                            RenderFixtures.ASSETS.metrics(),
                            false,
                        )
                ),
                MinestomDialogueScheduler(fixture.first.instance),
            )
        val frame =
            presenter.compose(
                view,
                "npc",
                PresentationPreferences("npc", Duration.ZERO, false, emptyMap()),
            )
        done(env, handle.replace(frame))
        assertEquals(
            setOf("c3", "c4", "c5"),
            checkNotNull(fixture.targets.current()).choices().keys,
        )
        val front =
            fixture.displays().first {
                PlainTextComponentSerializer.plainText()
                    .serialize((it.entityMeta as TextDisplayMeta).text)
                    .contains("Hello")
            }
        val meta = front.entityMeta as TextDisplayMeta
        val transform = checkNotNull(fixture.surface.geometry()).transform()
        val unit = 0.025 * meta.scale.x()
        val local = meta.translation
        val origin =
            front.position
                .add(transform.right.mul(local.x() + (1 - 320 / 2.0) * unit))
                .add(0.0, local.y() + (9 - 7) * unit, 0.0)
        assertEquals(0.0, origin.distance(transform.widgetToWorld(0.0, 0.0)), 1e-6)
        val selected = frame.widgets().first { it.choiceId == "c4" }
        assertEquals(1.08, selected.visualScale)
        assertEquals(selected.bounds, checkNotNull(fixture.targets.current()).choices()["c4"])
        assertNotNull(frame.scrollbar())
        val bar =
            MinecraftWidgetEncoder().encode(frame, RenderFixtures.ASSETS).widgets().first {
                it.kind == WidgetFrame.Kind.SCROLLBAR
            }
        val barEntity =
            fixture.displays().first {
                val m = it.entityMeta as TextDisplayMeta
                m.text == bar.front && m.leftRotation[1] < 0
            }
        val barMeta = barEntity.entityMeta as TextDisplayMeta
        val barUnit = 0.025 * barMeta.scale.x()
        val barCorner =
            rotate(
                Vec(
                    (1 - bar.bounds.width / 2) * barUnit,
                    (2 + bar.paintBaseline) * barUnit,
                    0.0,
                ),
                barMeta.leftRotation,
            )
        val barLocal = barMeta.translation.add(barCorner)
        val barPosition =
            barEntity.position
                .add(transform.right.mul(barLocal.x()))
                .add(0.0, barLocal.y(), 0.0)
                .add(transform.normal.mul(barLocal.z()))
        assertEquals(
            0.0,
            barPosition.distance(
                transform.planeFor(bar.bounds).widgetToWorld(bar.bounds.x, bar.bounds.y)
            ),
            1e-6,
        )
        val backEntity =
            fixture.displays().first {
                val m = it.entityMeta as TextDisplayMeta
                m.text == bar.back && m.leftRotation[1] > 0
            }
        val backMeta = backEntity.entityMeta as TextDisplayMeta
        val backUnit = 0.025 * backMeta.scale.x()
        val backInk = MinecraftWidgetEncoderTest.barOffset(bar.back)
        val track = checkNotNull(frame.scrollbar()).track()
        val localBack =
            backMeta.translation.add(
                rotate(
                    Vec(
                        (1 - bar.bounds.width / 2 + backInk + track.width) * backUnit,
                        (2 + bar.paintBaseline - (track.y - bar.bounds.y)) * backUnit,
                        0.0,
                    ),
                    backMeta.leftRotation,
                )
            )
        val actualBack =
            backEntity.position
                .add(transform.right.mul(localBack.x()))
                .add(0.0, localBack.y(), 0.0)
                .add(transform.normal.mul(localBack.z()))
        val plane = transform.planeFor(bar.bounds)
        val expectedBack = plane.widgetToWorld(track.x, track.y).sub(plane.normal.mul(0.012))
        assertEquals(
            0.0,
            actualBack.distance(expectedBack),
            1e-6,
            "Both indicator faces must occupy the same folded track, separated only by back depth",
        )
        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
    }

    private fun rotate(vector: Vec, quaternion: FloatArray): Vec {
        val axis = Vec(quaternion[0].toDouble(), quaternion[1].toDouble(), quaternion[2].toDouble())
        val cross = axis.cross(vector).mul(2.0)
        return vector.add(cross.mul(quaternion[3].toDouble())).add(axis.cross(cross))
    }

    @Test
    fun foldedDisplayCornersBackDepthAndHitPlaneAgreeAndLiveGapDoesNotInterpolate(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val handle = fixture.open(env)
        val preferences =
            PresentationPreferences("npc", Duration.ZERO, false, mapOf("npcChoiceAngle" to "30"))
        done(env, handle.configure(preferences))
        val frame = RenderFixtures.frame("npc", "Hello", 5)
        done(env, handle.replace(frame))
        val widget = frame.widgets().first { it.selected }
        val entity =
            fixture.displays().first {
                PlainTextComponentSerializer.plainText()
                    .serialize((it.entityMeta as TextDisplayMeta).text)
                    .contains("Ask")
            }
        val meta = entity.entityMeta as TextDisplayMeta
        val transform = checkNotNull(fixture.surface.geometry()).transform()
        val plane = transform.planeFor(widget.bounds)
        val unit = 0.025 * meta.scale.x()
        val nativeCorner =
            rotate(Vec((1 - widget.bounds.width / 2) * unit, 2 * unit, 0.0), meta.leftRotation)
        val local = meta.translation.add(nativeCorner)
        val actual =
            entity.position
                .add(transform.right.mul(local.x()))
                .add(0.0, local.y(), 0.0)
                .add(transform.normal.mul(local.z()))
        val expected =
            plane.widgetToWorld(
                widget.bounds.x - widget.bounds.width * (widget.visualScale - 1) / 2,
                widget.bounds.y - widget.bounds.height * (widget.visualScale - 1) / 2,
            )
        assertEquals(0.0, actual.distance(expected), 1e-6)
        val frontNormal = rotate(Vec(0.0, 0.0, 1.0), meta.leftRotation)
        assertEquals(0.0, frontNormal.distance(plane.normal), 1e-6)
        val encodedBack =
            MinecraftWidgetEncoder()
                .encode(frame, RenderFixtures.ASSETS)
                .widgets()
                .first { it.id == widget.id }
                .back
        val back =
            fixture
                .displays()
                .map { it.entityMeta as TextDisplayMeta }
                .first { it.text == encodedBack && it.scale.x() > 0.33 }
        assertEquals(-1.0, rotate(Vec(0.0, 0.0, 1.0), back.leftRotation).dot(frontNormal), 1e-6)
        assertEquals(-0.012, back.translation.sub(meta.translation).dot(frontNormal), 1e-6)
        val eye = fixture.first.position.add(0.0, fixture.first.eyeHeight.toDouble(), 0.0)
        val center =
            plane.widgetToWorld(
                widget.bounds.x + widget.bounds.width / 2,
                widget.bounds.y + widget.bounds.height / 2,
            )
        assertEquals(
            widget.choiceId,
            NpcHitTest.hit(
                    eye,
                    center.sub(eye).asVec().normalize(),
                    transform,
                    checkNotNull(fixture.targets.current()),
                ) { _, _ ->
                    true
                }
                .orElseThrow(),
        )
        done(
            env,
            handle.configure(
                PresentationPreferences("npc", Duration.ZERO, false, mapOf("npcChoiceAngle" to "0"))
            ),
        )
        done(env, handle.replace(frame))
        assertNull(checkNotNull(fixture.surface.geometry()).transform().fold)
        assertEquals(0.0, meta.leftRotation[1].toDouble(), 1e-6)
        done(
            env,
            handle.configure(
                PresentationPreferences(
                    "npc",
                    Duration.ZERO,
                    false,
                    mapOf("npcChoiceAngle" to "0", "npcChoiceGap" to "28"),
                )
            ),
        )
        val presenter =
            DialoguePresenter(
                mapOf(
                    "npc" to
                        DialoguePresenter.SurfaceProfile(
                            fixture.surface,
                            LayoutProfile.npc(28.0),
                            DialogueTheme.neutral(),
                            RenderFixtures.ASSETS.metrics(),
                            false,
                        )
                ),
                MinestomDialogueScheduler(fixture.first.instance),
            )
        val moved = presenter.compose(frame.semantic(), "npc", preferences)
        done(env, handle.replace(moved))
        assertEquals(
            0,
            meta.transformationInterpolationDuration,
            "New hit planes must not race an interpolating gap change",
        )
        done(env, handle.closeAsync())
        assertTrue(fixture.displays().isEmpty())
    }

    @Test
    fun followingTheNpcRetainsTheFoldAndFixedWorldSize(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val handle = fixture.open(env)
        done(env, handle.replace(RenderFixtures.frame("npc", "Hello", 5)))
        val fold = checkNotNull(checkNotNull(fixture.surface.geometry()).transform().fold)
        fixture.feet.set(Vec(4.0, 42.0, 0.0))
        assertTrue(
            env.tickWhile(
                { checkNotNull(fixture.surface.geometry()).transform().origin.x() != 4.0 },
                Duration.ofSeconds(2),
            )
        )
        val transform = checkNotNull(fixture.surface.geometry()).transform()
        assertEquals(fold, transform.fold)
        assertEquals(
            240 * 0.008,
            transform
                .widgetToWorld(fold.pivot, 20.0)
                .distance(transform.widgetToWorld(fold.pivot + 240, 20.0)),
            1e-6,
        )
        fixture.displays().forEach { assertEquals(4.0, it.position.x(), 1e-6) }
        done(env, handle.closeAsync())
    }

    @Test
    fun verticalOffsetRaisesDisplaysAndHitGeometryAcrossFollowUpdates(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val handle = fixture.open(env, mapOf("npcVerticalOffset" to "1.0"))
        done(env, handle.replace(RenderFixtures.frame("npc", "Hello", 5)))

        assertEquals(44.9, checkNotNull(fixture.surface.geometry()).transform().origin.y(), 1e-6)
        fixture.displays().forEach { assertEquals(44.9, it.position.y(), 1e-6) }

        fixture.feet.set(Vec(4.0, 43.0, 0.0))
        assertTrue(
            env.tickWhile(
                { checkNotNull(fixture.surface.geometry()).transform().origin.x() != 4.0 },
                Duration.ofSeconds(2),
            )
        )
        val transform = checkNotNull(fixture.surface.geometry()).transform()
        assertEquals(45.9, transform.origin.y(), 1e-6)
        fixture.displays().forEach { assertEquals(45.9, it.position.y(), 1e-6) }

        val worldPoint = transform.widgetToWorld(160.0, 20.0)
        assertEquals(45.9 + (transform.speechHeight - 20.0) * 0.008, worldPoint.y(), 1e-6)
        val widgetPoint = transform.worldToWidget(worldPoint)
        assertEquals(160.0, widgetPoint.x(), 1e-6)
        assertEquals(20.0, widgetPoint.y(), 1e-6)
        assertEquals(0.0, widgetPoint.z(), 1e-6)
        done(env, handle.closeAsync())
    }

    @Test
    fun invalidNpcVerticalOffsetsFallBack(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val frame = RenderFixtures.frame("npc", "Hello", 5)
        for (offset in listOf("NaN", "Infinity", "-0.01", "16.01")) {
            val handle = fixture.open(env, mapOf("npcVerticalOffset" to offset))
            val replacement = handle.replace(frame).toCompletableFuture()
            assertTrue(env.tickWhile({ !replacement.isDone }, Duration.ofSeconds(2)))
            val failure =
                assertThrows(java.util.concurrent.CompletionException::class.java) {
                    replacement.join()
                }
            assertTrue(failure.cause is DialoguePresenter.FallbackRequired)
            assertEquals("UNSUPPORTED_RASTER", failure.cause?.message)
            done(env, handle.closeAsync())
        }
    }

    @Test
    fun movedAnchorIsAppliedBeforeFrameAcknowledgement(env: Env) {
        val fixture = Fixture(env, CompletableFuture.completedFuture(null))
        val handle = fixture.open(env)
        val frame = RenderFixtures.frame("npc", "Hello", 5)
        done(env, handle.replace(frame))
        fixture.feet.set(Vec(4.0, 42.0, 0.0))
        done(env, handle.replace(frame))
        fixture.displays().forEach { assertEquals(4.0, it.position.x(), 1e-6) }
        done(env, handle.closeAsync())
    }

    @Test
    fun lateSpawnIsRemoved(env: Env) {
        val gate = CompletableFuture<Void>()
        val fixture = Fixture(env, gate)
        val handle = fixture.open(env, mapOf("npcBackdrop" to "true", "npcEffectsFadeMs" to "250"))
        val write = handle.replace(RenderFixtures.frame("npc", "Hello", 5))
        assertTrue(env.tickWhile({ fixture.displays().isEmpty() }, Duration.ofSeconds(2)))
        assertTrue(fixture.displays().all { it.viewers.isEmpty() })
        val close = handle.closeAsync()
        env.tick()
        assertFalse(close.toCompletableFuture().isDone)
        gate.complete(null)
        done(env, close)
        assertTrue(fixture.displays().isEmpty())
        assertTrue(write.toCompletableFuture().isCompletedExceptionally)
    }

    @Test
    fun backdropOnlyCloseRemovesDisplaysImmediately(env: Env) {
        val time = AtomicLong()
        val fixture = groundsFixture(env, time)
        val handle = fixture.open(env, mapOf("npcBackdrop" to "true", "npcEffectsFadeMs" to "250"))
        done(env, handle.replace(fixture.frame()))
        assertEquals(255, fixture.opacity())

        val close = handle.closeAsync()
        assertTrue(env.tickWhile({ fixture.displays().isNotEmpty() }, Duration.ofSeconds(2)))

        assertTrue(close.toCompletableFuture().isDone)
        assertTrue(fixture.displays().isEmpty())
        done(env, close)
    }
}
