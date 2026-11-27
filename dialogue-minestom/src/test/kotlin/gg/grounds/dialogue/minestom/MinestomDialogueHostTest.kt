package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.DialogueRenderer
import gg.grounds.dialogue.core.runtime.*
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.minestom.channel.LocalOutputChannels
import gg.grounds.dialogue.minestom.input.InputProfile
import gg.grounds.dialogue.minestom.input.TargetSnapshot
import gg.grounds.dialogue.minestom.internal.DialogueInputSource
import gg.grounds.dialogue.presentation.UiLabelSource
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import net.kyori.adventure.key.Key
import net.kyori.adventure.resource.ResourcePackStatus
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.instance.AddEntityToInstanceEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.event.player.PlayerResourcePackStatusEvent
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
internal class MinestomDialogueHostTest {
    internal class Surface : DialogueRenderer.PresentationHandle {
        @JvmField var frames = 0
        @JvmField var closes = 0
        @JvmField var cleanup: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

        override fun replace(view: DialogueView): CompletionStage<Void> {
            frames++
            return CompletableFuture.completedFuture(null)
        }

        override fun reconfigure(preferences: PresentationPreferences): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)

        override fun closeAsync(): CompletionStage<Void> {
            closes++
            return cleanup
        }
    }

    internal class Fixture(env: Env) {
        @JvmField
        val player = env.createConnection().connect(env.createFlatInstance(), Pos(0.0, 42.0, 0.0))
        @JvmField val content = content()
        @JvmField val services = services()
        @JvmField val options: MinestomDialogueOptions
        @JvmField val prepares = AtomicInteger()
        @JvmField val surfaces = mutableListOf<Surface>()
        @JvmField var prepareGate: CompletableFuture<DialogueRenderer.PresentationHandle>? = null
        @JvmField val host: MinestomDialogueHost

        init {
            val budgets =
                RuntimeOptions.Budgets(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30),
                )
            options =
                MinestomDialogueOptions(
                    budgets,
                    MinecraftPackProfile.public262(),
                    AssetBundle.loadDefault(MinecraftPackProfile.public262()),
                    LocalOutputChannels(
                        { _, _ -> CompletableFuture.completedFuture(null) },
                        false,
                        { _, _ -> CompletableFuture.completedFuture(null) },
                    ),
                    UiLabelSource.defaults(),
                    Duration.ofMillis(600),
                )
            host =
                MinestomDialogueHost(options) { _, _, _, _ ->
                    DialogueRenderer {
                        prepares.incrementAndGet()
                        val surface = Surface()
                        surfaces.add(surface)
                        prepareGate ?: CompletableFuture.completedFuture(surface)
                    }
                }
        }

        fun request(
            id: String,
            binding: String,
            preferences: PresentationPreferences =
                PresentationPreferences("hud", Duration.ZERO, false, emptyMap()),
        ): MinestomDialogueRequest =
            MinestomDialogueRequest(
                id,
                player,
                1,
                content,
                DialogueServices(
                    binding,
                    services.conditions(),
                    services.actions(),
                    services.outcomes(),
                    services.texts(),
                    services.capabilities(),
                ),
                emptyMap(),
                LocaleContext("en", 0, "en"),
                preferences,
                mapOf(
                    "npc" to
                        MinestomSpeakerBinding(
                            "anchor-1",
                            player.instance,
                            {
                                MinestomSpeakerBinding.AnchorSnapshot(
                                    player.position,
                                    0f,
                                    player.position.add(0.0, 1.6, 0.0),
                                    true,
                                )
                            },
                        )
                ),
                null,
                InputProfile.defaults(),
                0,
            )
    }

    companion object {
        @JvmStatic
        fun content(): ValidatedDialogue {
            val texts = TextCatalog(mapOf("en" to mapOf("line" to "Hello")))
            val definition =
                DialogueBuilder.dialogue("test:minestom")
                    .node("start", Message("npc", "line", null, emptyList(), "end"))
                    .node("end", End("completed"))
                    .build()
            return DialogueValidator()
                .validate(
                    definition,
                    texts,
                    HostCatalog(
                        "v1",
                        setOf("npc"),
                        emptyMap(),
                        emptyMap(),
                        emptySet(),
                        emptySet(),
                        false,
                    ),
                    ValidationLimits.defaults(),
                )
                .validated()!!
        }

        @JvmStatic
        fun services(): DialogueServices {
            val texts = TextCatalog(mapOf("en" to mapOf("line" to "Hello")))
            return DialogueServices(
                "v1",
                emptyMap(),
                emptyMap(),
                null,
                TextResolver.catalogue(texts, "en"),
                emptySet(),
            )
        }

        @JvmStatic
        fun <T> done(env: Env, stage: CompletionStage<T>): T {
            assertTrue(
                env.tickWhile(
                    { !stage.toCompletableFuture().isDone },
                    Duration.ofSeconds(2),
                )
            )
            return stage.toCompletableFuture().join()
        }
    }

    @Test
    fun requestPropertiesAndNamedConstructionRetainExplicitValuesAndDefaultListener(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val source = fixture.request("properties", "v1")
        val request = requestFrom(source)
        assertEquals("properties", request.requestId)
        assertSame(fixture.player, request.player)
        assertEquals(1L, request.hostGeneration)
        assertSame(fixture.content, request.content)
        assertSame(source.services, request.services)
        assertEquals(emptyMap<String, Any>(), request.context)
        assertSame(source.locale, request.locale)
        assertSame(source.preferences, request.preferences)
        assertSame(source.speakers["npc"], request.speakers["npc"])
        assertNull(request.actors)
        assertSame(source.controls, request.controls)
        assertEquals(0L, request.openingImpulse)
        assertSame(MinestomDialogueSpeechListener.NO_OP, request.speechListener)
        val listener =
            object : MinestomDialogueSpeechListener {
                override fun onRevealed(event: MinestomDialogueSpeechEvent) = Unit

                override fun onStop(reason: MinestomDialogueSpeechListener.StopReason) = Unit
            }
        val explicit = requestFrom(source, listener = listener)
        assertSame(listener, explicit.speechListener)
        assertEquals(request, explicit)
        assertEquals(request.hashCode(), explicit.hashCode())
        assertEquals(request.toString(), explicit.toString())
        assertFalse(request.toString().contains("speechListener"))
        assertNotEquals(request, requestFrom(source, id = "other"))
        assertNotEquals(request, requestFrom(source, generation = 2))
        assertNotEquals(request, requestFrom(source, impulse = 1))
    }

    @Test
    fun requestMapsAreDefensiveUnmodifiableSnapshotsAndRejectNulls(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val source = fixture.request("maps", "v1")
        val value = gg.grounds.dialogue.core.definition.ArgumentValue.Text("before")
        val context =
            mutableMapOf<String, gg.grounds.dialogue.core.definition.ArgumentValue>("key" to value)
        val speakers = source.speakers.toMutableMap()
        val request = requestFrom(source, context = context, speakers = speakers)
        context.clear()
        speakers.clear()
        assertEquals(mapOf("key" to value), request.context)
        assertEquals(setOf("npc"), request.speakers.keys)
        assertThrows(UnsupportedOperationException::class.java) {
            (request.context as MutableMap).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (request.speakers as MutableMap).clear()
        }
        @Suppress("UNCHECKED_CAST")
        val nullKey =
            mapOf(null to value) as Map<String, gg.grounds.dialogue.core.definition.ArgumentValue>
        @Suppress("UNCHECKED_CAST")
        val nullValue =
            mapOf("key" to null) as Map<String, gg.grounds.dialogue.core.definition.ArgumentValue>
        @Suppress("UNCHECKED_CAST")
        val nullSpeakerKey =
            mapOf(null to source.speakers.getValue("npc")) as Map<String, MinestomSpeakerBinding>
        @Suppress("UNCHECKED_CAST")
        val nullSpeaker = mapOf("npc" to null) as Map<String, MinestomSpeakerBinding>
        assertThrows(NullPointerException::class.java) { requestFrom(source, context = nullKey) }
        assertThrows(NullPointerException::class.java) { requestFrom(source, context = nullValue) }
        assertThrows(NullPointerException::class.java) {
            requestFrom(source, speakers = nullSpeakerKey)
        }
        assertThrows(NullPointerException::class.java) {
            requestFrom(source, speakers = nullSpeaker)
        }
    }

    @Test
    fun requestIdentityRejectsBlankAndNegativeValuesButAcceptsZero(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val source = fixture.request("identity", "v1")
        for (id in listOf("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException::class.java) { requestFrom(source, id = id) }
        }
        assertThrows(IllegalArgumentException::class.java) { requestFrom(source, generation = -1) }
        assertThrows(IllegalArgumentException::class.java) { requestFrom(source, impulse = -1) }
        val zero = requestFrom(source, generation = 0, impulse = 0)
        assertEquals(0L, zero.hostGeneration)
        assertEquals(0L, zero.openingImpulse)
    }

    private fun requestFrom(
        source: MinestomDialogueRequest,
        id: String = source.requestId,
        generation: Long = source.hostGeneration,
        impulse: Long = source.openingImpulse,
        context: Map<String, gg.grounds.dialogue.core.definition.ArgumentValue> = source.context,
        speakers: Map<String, MinestomSpeakerBinding> = source.speakers,
        listener: MinestomDialogueSpeechListener? = null,
    ): MinestomDialogueRequest {
        if (listener != null)
            return MinestomDialogueRequest(
                requestId = id,
                player = source.player,
                hostGeneration = generation,
                content = source.content,
                services = source.services,
                context = context,
                locale = source.locale,
                preferences = source.preferences,
                speakers = speakers,
                actors = source.actors,
                controls = source.controls,
                openingImpulse = impulse,
                speechListener = listener,
            )
        return MinestomDialogueRequest(
            openingImpulse = impulse,
            controls = source.controls,
            actors = source.actors,
            speakers = speakers,
            preferences = source.preferences,
            locale = source.locale,
            context = context,
            services = source.services,
            content = source.content,
            hostGeneration = generation,
            player = source.player,
            requestId = id,
        )
    }

    @Test
    fun nativeInputRouteOwnsOnlyActiveConnectionAndWaitsItsCatcher(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val host = MinestomDialogueHost(fixture.options)
        val id = UUID.randomUUID()
        host.packState().expect(fixture.player, 1, id, fixture.options.assets.pin())
        host
            .packState()
            .observe(
                fixture.player,
                1,
                id,
                fixture.options.assets.pin(),
                net.kyori.adventure.resource.ResourcePackStatus.SUCCESSFULLY_LOADED,
            )
        val ticket = host.begin(fixture.request("native-input", "v1"))
        val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
        assertEquals(
            1,
            fixture.player.instance.entities.count { it.entityType == EntityType.INTERACTION },
        )
        val packet =
            ClientPlayerActionPacket(
                ClientPlayerActionPacket.Status.SWAP_ITEM_HAND,
                Vec.ZERO,
                BlockFace.TOP,
                0,
            )
        val opening = PlayerPacketEvent(fixture.player, packet)
        EventDispatcher.call(opening)
        assertTrue(opening.isCancelled)
        env.tick()
        assertFalse(handle.completion().toCompletableFuture().isDone)
        Thread.sleep(375)
        val confirm = PlayerPacketEvent(fixture.player, packet)
        EventDispatcher.call(confirm)
        assertTrue(confirm.isCancelled)
        assertEquals(DialogueEndReason.COMPLETED, done(env, handle.completion()).reason())
        done(env, ticket.cancel(DialogueEndReason.CANCELLED))
        assertTrue(
            fixture.player.instance.entities.none { it.entityType == EntityType.INTERACTION }
        )
        val after = PlayerPacketEvent(fixture.player, packet)
        EventDispatcher.call(after)
        assertFalse(after.isCancelled)
        done(env, host.closeAsync())
    }

    @Test
    fun nativeHostFallsBackAndDoesNotRestartSessionOnPackRevocation(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val host = MinestomDialogueHost(fixture.options)
        val id = UUID.randomUUID()
        host.packState().expect(fixture.player, 1, id, fixture.options.assets.pin())
        host
            .packState()
            .observe(
                fixture.player,
                1,
                id,
                fixture.options.assets.pin(),
                net.kyori.adventure.resource.ResourcePackStatus.SUCCESSFULLY_LOADED,
            )
        val ticket = host.begin(fixture.request("native", "v1"))
        val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
        assertEquals("hud", ticket.status().effectiveSurface)
        val visit = handle.snapshot().nodeVisitId()
        EventDispatcher.call(
            PlayerResourcePackStatusEvent(
                fixture.player,
                id,
                net.kyori.adventure.resource.ResourcePackStatus.DECLINED,
            )
        )
        assertTrue(
            env.tickWhile(
                { ticket.status().effectiveSurface != "chat" },
                Duration.ofSeconds(2),
            )
        )
        assertEquals(visit, handle.snapshot().nodeVisitId())
        assertEquals("hud", ticket.status().requestedSurface)
        done(env, host.closeAsync())
    }

    @Test
    fun normalHostCancellationRetiresInputWhileNativeVignetteFades(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val profile = MinecraftPackProfile.grounds263()
        val assets = AssetBundle.loadDefault(profile)
        val effects = mutableListOf<List<Key>>()
        val channels =
            LocalOutputChannels(
                { _, _ -> CompletableFuture.completedFuture(null) },
                true,
                { _, keys ->
                    effects.add(keys)
                    CompletableFuture.completedFuture(null)
                },
            )
        val host =
            MinestomDialogueHost(
                fixture.options.copy(profile = profile, assets = assets, channels = channels)
            )
        val pack = UUID.randomUUID()
        host.packState().expect(fixture.player, 1, pack, assets.pin())
        host
            .packState()
            .observe(fixture.player, 1, pack, assets.pin(), ResourcePackStatus.SUCCESSFULLY_LOADED)
        val preferences =
            PresentationPreferences(
                "npc",
                Duration.ZERO,
                false,
                mapOf("vignette" to "true", "npcBackdrop" to "true", "npcEffectsFadeMs" to "250"),
            )
        val ticket = host.begin(fixture.request("native-vignette", "v1", preferences))
        assertInstanceOf(DialogueStartResult.Started::class.java, done(env, ticket.result()))
        assertEquals("npc", ticket.status().effectiveSurface)
        val enter = Key.key("dialogue:vignette_250_enter_a")
        val exit = Key.key("dialogue:vignette_250_exit_a")
        assertEquals(listOf(listOf(enter)), effects)
        val closing = ticket.cancel(DialogueEndReason.CANCELLED)
        assertTrue(
            env.tickWhile({ effects.lastOrNull()?.contains(exit) != true }, Duration.ofSeconds(2))
        )
        assertTrue(
            fixture.player.instance.entities.none { it.entityType == EntityType.TEXT_DISPLAY }
        )
        assertFalse(closing.toCompletableFuture().isDone)
        done(env, closing)
        assertEquals(listOf(listOf(enter), listOf(enter, exit), emptyList()), effects)
        done(env, host.closeAsync())
    }

    @Test
    fun noInstanceAtAdmissionRejectsRatherThanHangs(env: Env) {
        val fixture = Fixture(env)
        val request = fixture.request("departed", "v1")
        fixture.player.remove()
        val ticket = fixture.host.begin(request)
        assertEquals(
            "HOST_CHANGED",
            (done(env, ticket.result()) as DialogueStartResult.Rejected).code(),
        )
        assertEquals(0, fixture.prepares.get())
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun admissionWorldCannotChangeWhilePreviousCleanupWaits(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.begin(fixture.request("first-world", "v1")).result())
        val gate = CompletableFuture<Void>()
        fixture.surfaces.first().cleanup = gate
        val next = fixture.host.begin(fixture.request("queued-world", "v1"))
        fixture.player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        gate.complete(null)
        assertEquals(
            "HOST_CHANGED",
            (done(env, next.result()) as DialogueStartResult.Rejected).code(),
        )
        assertEquals(1, fixture.prepares.get(), "No new preparation or actions in another world")
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun duplicatePendingRequestReturnsSameTicket(env: Env) {
        val fixture = Fixture(env)
        fixture.prepareGate = CompletableFuture()
        val request = fixture.request("same", "v1")
        val first = fixture.host.begin(request)
        assertSame(first, fixture.host.begin(request))
        env.tick()
        env.tick()
        assertEquals(1, fixture.prepares.get())
        val close = first.cancel(DialogueEndReason.CANCELLED)
        fixture.prepareGate!!.complete(fixture.surfaces.first())
        done(env, close)
        assertEquals(1, fixture.surfaces.first().closes)
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun changedBindingGenerationConflicts(env: Env) {
        val fixture = Fixture(env)
        val first = fixture.host.begin(fixture.request("conflict", "v1"))
        val other = fixture.host.begin(fixture.request("conflict", "v2"))
        assertEquals(
            "START_REQUEST_CONFLICT",
            (done(env, other.result()) as DialogueStartResult.Rejected).code(),
        )
        assertInstanceOf(DialogueStartResult.Started::class.java, done(env, first.result()))
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun pendingCancelCleansLateRenderer(env: Env) {
        val fixture = Fixture(env)
        fixture.prepareGate = CompletableFuture()
        val start = fixture.host.begin(fixture.request("late", "v1"))
        assertTrue(env.tickWhile({ fixture.prepares.get() == 0 }, Duration.ofSeconds(2)))
        val close = start.cancel(DialogueEndReason.DISCONNECTED)
        env.tick()
        assertFalse(close.toCompletableFuture().isDone)
        fixture.prepareGate!!.complete(fixture.surfaces.first())
        done(env, close)
        assertEquals(
            "DISCONNECTED",
            (done(env, start.result()) as DialogueStartResult.Rejected).code(),
        )
        assertEquals(1, fixture.surfaces.first().closes)
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun cancelledMiddleStartPreservesCleanupBarrier(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.begin(fixture.request("barrier-a", "v1")).result())
        val gate = CompletableFuture<Void>()
        fixture.surfaces.first().cleanup = gate
        fixture.host.begin(fixture.request("barrier-b", "v1"))
        val third = fixture.host.begin(fixture.request("barrier-c", "v1"))
        repeat(20) { env.tick() }
        assertEquals(1, fixture.prepares.get())
        assertFalse(third.result().toCompletableFuture().isDone)
        gate.complete(null)
        assertInstanceOf(DialogueStartResult.Started::class.java, done(env, third.result()))
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun rendererFactoryFailureBeforeOwnershipAllowsFreshStart(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val factoryCalls = AtomicInteger()
        val host =
            MinestomDialogueHost(fixture.options) { _, _, _, _ ->
                if (factoryCalls.incrementAndGet() == 1)
                    throw IllegalStateException("factory failed before ownership")
                DialogueRenderer {
                    val surface = Surface()
                    fixture.surfaces.add(surface)
                    CompletableFuture.completedFuture(surface)
                }
            }

        val first = host.begin(fixture.request("factory-failure-first", "v1"))
        assertEquals(
            "START_FAILED",
            (done(env, first.result()) as DialogueStartResult.Rejected).code(),
        )
        assertTrue(fixture.surfaces.isEmpty(), "The failing factory acquired no handle")
        val second = host.begin(fixture.request("factory-failure-second", "v1"))
        assertInstanceOf(DialogueStartResult.Started::class.java, done(env, second.result()))
        assertEquals(2, factoryCalls.get())
        done(env, first.cancel(DialogueEndReason.CANCELLED))
        done(env, host.closeAsync())
        assertEquals(1, fixture.surfaces.single().closes)
    }

    @Test
    fun hostCloseAfterFactoryFailureConfirmsCleanup(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val host =
            MinestomDialogueHost(fixture.options) { _, _, _, _ ->
                throw IllegalStateException("factory failed before ownership")
            }
        val first = host.begin(fixture.request("factory-failure-close", "v1"))
        assertEquals(
            "START_FAILED",
            (done(env, first.result()) as DialogueStartResult.Rejected).code(),
        )
        done(env, host.closeAsync())
        assertEquals("CLOSED", first.status().diagnostic)
    }

    @Test
    fun inputPreparationFailureWaitsCleanupAcrossCancelledSuccessor(env: Env) {
        val fixture = Fixture(env)
        done(env, fixture.host.closeAsync())
        val cleanup = CompletableFuture<Void>()
        val surfaces = mutableListOf<Surface>()
        val host =
            MinestomDialogueHost(fixture.options) { _, _, _, _ ->
                object : DialogueRenderer, DialogueInputSource {
                    override fun effectiveSurface() = "hud"

                    override fun resolveChat(
                        player: Player,
                        generation: Long,
                        token: String,
                    ): Optional<TargetSnapshot.Target> = Optional.empty()

                    override fun prepare(
                        request: DialogueRenderer.PresentationRequest
                    ): CompletionStage<DialogueRenderer.PresentationHandle> {
                        val surface = Surface()
                        if (surfaces.isEmpty()) surface.cleanup = cleanup
                        surfaces.add(surface)
                        return CompletableFuture.completedFuture(surface)
                    }
                }
            }
        val spawnAttempts = AtomicInteger()
        fixture.player.instance.eventNode().addListener(AddEntityToInstanceEvent::class.java) {
            if (
                it.entity.entityType == EntityType.INTERACTION &&
                    spawnAttempts.incrementAndGet() == 1
            )
                it.isCancelled = true
        }

        val first = host.begin(fixture.request("input-failure-first", "v1"))
        assertTrue(env.tickWhile({ surfaces.firstOrNull()?.closes != 1 }, Duration.ofSeconds(2)))
        assertFalse(
            first.result().toCompletableFuture().isDone,
            "Rejection waits for owned cleanup",
        )
        assertTrue(
            fixture.player.instance.entities.none { it.entityType == EntityType.INTERACTION }
        )
        val cancelled = host.begin(fixture.request("input-failure-cancelled", "v1"))
        val cancellation = cancelled.cancel(DialogueEndReason.CANCELLED)
        val third = host.begin(fixture.request("input-failure-third", "v1"))
        repeat(20) { env.tick() }
        assertFalse(cancellation.toCompletableFuture().isDone)
        assertFalse(third.result().toCompletableFuture().isDone)
        assertEquals(1, surfaces.size)

        cleanup.complete(null)
        assertEquals(
            "START_FAILED",
            (done(env, first.result()) as DialogueStartResult.Rejected).code(),
        )
        done(env, cancellation)
        assertEquals(
            "CANCELLED",
            (done(env, cancelled.result()) as DialogueStartResult.Rejected).code(),
        )
        assertInstanceOf(DialogueStartResult.Started::class.java, done(env, third.result()))
        assertEquals(2, surfaces.size)
        assertEquals(2, spawnAttempts.get())
        done(env, host.closeAsync())
        assertTrue(surfaces.all { it.closes == 1 })
        assertTrue(
            fixture.player.instance.entities.none { it.entityType == EntityType.INTERACTION }
        )
    }

    @Test
    fun cancelledMiddleStartPreservesFailedCleanupBarrier(env: Env) {
        val fixture = Fixture(env)
        val first = fixture.host.begin(fixture.request("failed-barrier-a", "v1"))
        done(env, first.result())
        val gate = CompletableFuture<Void>()
        fixture.surfaces.first().cleanup = gate
        val middle = fixture.host.begin(fixture.request("failed-barrier-b", "v1"))
        val cancellation = middle.cancel(DialogueEndReason.CANCELLED)
        val third = fixture.host.begin(fixture.request("failed-barrier-c", "v1"))
        repeat(20) { env.tick() }
        assertFalse(cancellation.toCompletableFuture().isDone)
        assertFalse(third.result().toCompletableFuture().isDone)
        gate.completeExceptionally(IllegalStateException("uncertain removal"))
        assertEquals(
            "CLEANUP_FAILED",
            (done(env, third.result()) as DialogueStartResult.Rejected).code(),
        )
        assertNotNull(done(env, cancellation.handle { _, failure -> failure }))
        assertNotNull(done(env, fixture.host.closeAsync().handle { _, failure -> failure }))
        assertEquals(1, fixture.prepares.get())
        assertEquals(1, fixture.surfaces.single().closes)
    }

    @Test
    fun failedCleanupFencesLaterAdmissions(env: Env) {
        val fixture = Fixture(env)
        val first = fixture.host.begin(fixture.request("failed-a", "v1"))
        done(env, first.result())
        fixture.surfaces.first().cleanup =
            CompletableFuture.failedFuture(IllegalStateException("uncertain removal"))
        val second = fixture.host.begin(fixture.request("failed-b", "v1"))
        assertInstanceOf(DialogueStartResult.Rejected::class.java, done(env, second.result()))
        val third = fixture.host.begin(fixture.request("failed-c", "v1"))
        assertInstanceOf(DialogueStartResult.Rejected::class.java, done(env, third.result()))
        assertEquals(1, fixture.prepares.get())
    }

    @Test
    fun nextStartWaitsPreviousCleanup(env: Env) {
        val fixture = Fixture(env)
        val first = fixture.host.begin(fixture.request("first", "v1"))
        done(env, first.result())
        val gate = CompletableFuture<Void>()
        fixture.surfaces.first().cleanup = gate
        val next = fixture.host.begin(fixture.request("next", "v1"))
        env.tick()
        env.tick()
        assertEquals(1, fixture.prepares.get())
        assertFalse(next.result().toCompletableFuture().isDone)
        gate.complete(null)
        assertInstanceOf(DialogueStartResult.Started::class.java, done(env, next.result()))
        assertEquals(2, fixture.prepares.get())
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun disconnectAndInstanceChangeInvalidate(env: Env) {
        val fixture = Fixture(env)
        val first = fixture.host.begin(fixture.request("disconnect", "v1"))
        val handle = (done(env, first.result()) as DialogueStartResult.Started).handle
        EventDispatcher.call(PlayerDisconnectEvent(fixture.player))
        assertEquals(DialogueEndReason.DISCONNECTED, done(env, handle.completion()).reason())
        val second = fixture.host.begin(fixture.request("world", "v1"))
        val secondHandle = (done(env, second.result()) as DialogueStartResult.Started).handle
        fixture.player.setInstance(env.createFlatInstance(), Pos(0.0, 42.0, 0.0)).join()
        assertEquals(
            DialogueEndReason.HOST_CHANGED,
            done(env, secondHandle.completion()).reason(),
        )
        done(env, fixture.host.closeAsync())
    }

    @Test
    fun startAndShutdownObserversCannotCancelOwnedOperations(env: Env) {
        val fixture = Fixture(env)
        val start = fixture.host.begin(fixture.request("observer", "v1"))
        start.result().toCompletableFuture().cancel(false)
        assertInstanceOf(DialogueStartResult.Started::class.java, done(env, start.result()))
        val close = fixture.host.closeAsync()
        close.toCompletableFuture().cancel(false)
        done(env, fixture.host.closeAsync())
    }
}
