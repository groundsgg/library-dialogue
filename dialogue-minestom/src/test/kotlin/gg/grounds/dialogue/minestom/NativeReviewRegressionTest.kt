package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.Choice
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RefreshResult
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.channel.LocalOutputChannels
import gg.grounds.dialogue.minestom.input.InputProfile
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import net.kyori.adventure.resource.ResourcePackStatus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.event.player.PlayerResourcePackStatusEvent
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket
import net.minestom.server.network.packet.client.play.ClientInteractEntityPacket
import net.minestom.server.network.packet.client.play.ClientUseItemPacket
import net.minestom.server.network.packet.server.play.SystemChatPacket
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import net.minestom.testing.TestConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class NativeReviewRegressionTest {
    class F
    @JvmOverloads
    constructor(@JvmField val env: Env, answers: Int, wrapped: Boolean = false) : AutoCloseable {
        @JvmField val connection: TestConnection
        @JvmField val player: net.minestom.server.entity.Player
        @JvmField val host: MinestomDialogueHost
        @JvmField val channels: LocalOutputChannels
        @JvmField val write = AtomicReference(CompletableFuture.completedFuture<Void>(null))
        @JvmField val pendingWrites = AtomicInteger()
        @JvmField val hudFrames: MutableList<Component> = CopyOnWriteArrayList()
        @JvmField val options: MinestomDialogueOptions
        @JvmField val pack: UUID = UUID.randomUUID()
        @JvmField val content: ValidatedDialogue
        @JvmField val texts: TextCatalog

        init {
            val base = MinestomDialogueHostTest.Fixture(env)
            done(env, base.host.closeAsync())
            options = base.options
            connection = env.createConnection()
            player = connection.connect(env.createFlatInstance(), Pos(0.0, 42.0, 4.0, 180f, 0f))
            channels =
                LocalOutputChannels(
                    { _, component ->
                        hudFrames.add(component)
                        val gate = write.get()
                        if (!gate.isDone) pendingWrites.incrementAndGet()
                        gate
                    },
                    false,
                    { _, _ -> CompletableFuture.completedFuture(null) },
                )
            host =
                MinestomDialogueHost(
                    MinestomDialogueOptions(
                        options.budgets,
                        options.profile,
                        options.assets,
                        channels,
                        options.labels,
                        options.distanceGrace,
                    )
                )
            val english = linkedMapOf("line" to "Hello")
            val german = linkedMapOf("line" to "Hallo")
            val choices = mutableListOf<Choice>()
            val builder = DialogueBuilder.dialogue("test:review").locales("en", "en", "de")
            repeat(answers) { index ->
                english["c$index"] =
                    "Choice $index" + if (wrapped && index == 0) "\nDetails" else ""
                german["c$index"] = "Antwort $index"
                choices.add(Choice("c$index", "c$index", "reply$index", index == 0, null))
                builder.node("reply$index", Message("npc", "c$index", null, emptyList(), "end"))
            }
            texts = TextCatalog(mapOf("en" to english, "de" to german))
            val dialogue =
                builder
                    .node("start", Message("npc", "line", null, choices, null))
                    .node("end", End("completed"))
                    .build()
            val report =
                DialogueValidator()
                    .validate(
                        dialogue,
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
            assertTrue(report.isValid(), report.toString())
            content = checkNotNull(report.validated())
            host.packState().expect(player, 1, pack, options.assets.pin())
            host.packState().observe(player, pack, ResourcePackStatus.SUCCESSFULLY_LOADED)
        }

        @JvmOverloads
        fun start(
            surface: String,
            hide: Boolean,
            controls: InputProfile = InputProfile.defaults(),
        ): MinestomDialogueStart {
            val binding =
                MinestomSpeakerBinding(
                    "npc",
                    player.instance,
                    {
                        MinestomSpeakerBinding.AnchorSnapshot(
                            Vec(0.0, 42.0, 0.0),
                            0f,
                            Vec(0.0, 43.6, 0.0),
                            true,
                        )
                    },
                )
            return host.begin(
                MinestomDialogueRequest(
                    UUID.randomUUID().toString(),
                    player,
                    1,
                    content,
                    DialogueServices(
                        "v1",
                        emptyMap(),
                        emptyMap(),
                        null,
                        TextResolver.catalogue(texts, "en"),
                        emptySet(),
                    ),
                    emptyMap(),
                    LocaleContext("en", 0, "en"),
                    PresentationPreferences(
                        surface,
                        Duration.ZERO,
                        false,
                        mapOf("hideHud" to hide.toString()),
                    ),
                    mapOf("npc" to binding),
                    null,
                    controls,
                    0,
                )
            )
        }

        fun packet(packet: Any) {
            EventDispatcher.call(
                PlayerPacketEvent(
                    player,
                    packet as net.minestom.server.network.packet.client.ClientPacket,
                )
            )
        }

        fun commands(packets: List<SystemChatPacket>): List<String> {
            val output = mutableListOf<String>()
            for (packet in packets) {
                val matcher =
                    Pattern.compile("/(dialogue_input_[a-z0-9]+ [A-Za-z0-9_-]{22})")
                        .matcher(GsonComponentSerializer.gson().serialize(packet.message()))
                while (matcher.find()) output.add(matcher.group(1))
            }
            return output
        }

        override fun close() {
            write.get().complete(null)
            done(env, host.closeAsync())
        }
    }

    @Test fun queuedConfirmCannotRunUnacknowledgedSelection(env: Env) = selectionRace(env, 2, 0)

    @Test fun queuedConfirmCannotRunUnacknowledgedNextPage(env: Env) = selectionRace(env, 7, 2)

    private fun selectionRace(env: Env, count: Int, initial: Int) {
        F(env, count).use { fixture ->
            val ticket = fixture.start("hud", false)
            val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
            for (slot in 1..initial) {
                fixture.packet(ClientHeldItemChangePacket(slot.toShort()))
                ticks(env)
            }
            assertEquals("c$initial", handle.snapshot().selectedChoiceId())
            ticks(env)
            Thread.sleep(375)
            fixture.write.set(CompletableFuture())
            fixture.packet(ClientHeldItemChangePacket((initial + 1).toShort()))
            fixture.packet(ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
            ticks(env)
            assertEquals(
                "start",
                handle.snapshot().nodeId(),
                "No unseen answer may execute before output acknowledgement",
            )
            fixture.write.get().complete(null)
            ticks(env)
            assertEquals(
                "start",
                handle.snapshot().nodeId(),
                "A stale queued confirm must be discarded after the new frame",
            )
        }
    }

    @Test
    fun startupObserversMayReenterTicketFromAnotherThread(env: Env) {
        F(env, 2).use { fixture ->
            fixture.write.set(CompletableFuture())
            val ticket = fixture.start("hud", false)
            val observer =
                ticket.result().thenAccept {
                    try {
                        CompletableFuture.runAsync { ticket.cancel(DialogueEndReason.CANCELLED) }
                            .get(1, TimeUnit.SECONDS)
                    } catch (error: Exception) {
                        throw CompletionException(error)
                    }
                }
            fixture.write.get().complete(null)
            done(env, observer)
        }
    }

    @Test
    fun startupRetainsLatestLocaleAndSurfaceBeforePublicStarted(env: Env) {
        F(env, 2).use { fixture ->
            fixture.write.set(CompletableFuture())
            val ticket = fixture.start("hud", false)
            assertTrue(env.tickWhile({ fixture.pendingWrites.get() == 0 }, Duration.ofSeconds(2)))
            val language = ticket.refreshLocale(LocaleContext("de", 1, "en"))
            val renderer =
                ticket.updatePreferences(
                    PresentationPreferences("chat", Duration.ZERO, false, emptyMap())
                )
            assertFalse(ticket.result().toCompletableFuture().isDone)
            fixture.write.get().complete(null)
            val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
            assertEquals("de", handle.snapshot().localeTag())
            assertEquals("Hallo", handle.snapshot().text())
            assertEquals("chat", ticket.status().effectiveSurface)
            assertEquals(RefreshResult.UPDATED, done(env, language))
            done(env, renderer)
        }
    }

    @Test
    fun cancelledNativeCandidateRetainsWorkingHudInput(env: Env) {
        F(env, 2).use { fixture ->
            val ticket = fixture.start("hud", false)
            val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
            fixture.write.set(CompletableFuture())
            handle.updatePreferences(
                PresentationPreferences("npc", Duration.ZERO, false, mapOf("hideHud" to "true"))
            )
            assertTrue(env.tickWhile({ fixture.pendingWrites.get() == 0 }, Duration.ofSeconds(2)))
            val back =
                handle.updatePreferences(
                    PresentationPreferences("hud", Duration.ZERO, false, emptyMap())
                )
            ticks(env)
            fixture.write.get().complete(null)
            done(env, back)
            Thread.sleep(375)
            fixture.packet(ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
            ticks(env)
            assertEquals("reply0", handle.snapshot().nodeId())
        }
    }

    @Test
    fun nonDefaultChatChoiceRunsItsOwnDestination(env: Env) {
        F(env, 2).use { fixture ->
            val packets = fixture.connection.trackIncoming(SystemChatPacket::class.java)
            val ticket = fixture.start("chat", false)
            val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
            val commands = fixture.commands(packets.collect())
            MinecraftServer.getCommandManager().execute(fixture.player, commands[1])
            ticks(env)
            assertEquals("reply1", handle.snapshot().nodeId())
        }
    }

    @Test
    fun sameChatPreferencesKeepWorkingLinks(env: Env) {
        F(env, 2).use { fixture ->
            val packets = fixture.connection.trackIncoming(SystemChatPacket::class.java)
            val ticket = fixture.start("chat", false)
            val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
            val commands = fixture.commands(packets.collect())
            done(
                env,
                handle.updatePreferences(
                    PresentationPreferences(
                        "chat",
                        Duration.ZERO,
                        false,
                        mapOf("npcBackdrop" to "true"),
                    )
                ),
            )
            MinecraftServer.getCommandManager().execute(fixture.player, commands.first())
            ticks(env)
            assertEquals("reply0", handle.snapshot().nodeId())
            MinecraftServer.getCommandManager().execute(fixture.player, commands[1])
            ticks(env)
            assertEquals(
                "reply0",
                handle.snapshot().nodeId(),
                "Links from the previous visit must remain stale",
            )
        }
    }

    @Test
    fun packRemovalDuringNpcDrawFallsBackBeforeStarted(env: Env) {
        packRemovalFallsBack(env, "npc", true)
    }

    @Test
    fun packRemovalDuringHudWriteFallsBackBeforeStarted(env: Env) {
        packRemovalFallsBack(env, "hud", false)
    }

    private fun packRemovalFallsBack(env: Env, surface: String, hide: Boolean) {
        F(env, 2).use { fixture ->
            fixture.write.set(CompletableFuture())
            val ticket = fixture.start(surface, hide)
            assertTrue(env.tickWhile({ fixture.pendingWrites.get() == 0 }, Duration.ofSeconds(2)))
            EventDispatcher.call(
                PlayerResourcePackStatusEvent(
                    fixture.player,
                    fixture.pack,
                    ResourcePackStatus.DISCARDED,
                )
            )
            fixture.write.get().complete(null)
            assertInstanceOf(DialogueStartResult.Started::class.java, done(env, ticket.result()))
            assertEquals("chat", ticket.status().effectiveSurface)
        }
    }

    @Test
    fun suppressedHudCannotConfirmItsHiddenChoice(env: Env) {
        F(env, 2).use { fixture ->
            val ticket = fixture.start("hud", false)
            val handle = (done(env, ticket.result()) as DialogueStartResult.Started).handle
            Thread.sleep(375)
            val other = fixture.channels.acquireHud(fixture.player, 1, 100)
            done(env, other.replace(Component.text("Other owner")))
            fixture.packet(ClientUseItemPacket(PlayerHand.MAIN, 1, 0f, 0f))
            ticks(env)
            assertEquals("start", handle.snapshot().nodeId())
            assertTrue(
                env.tickWhile(
                    { ticket.status().effectiveSurface != "chat" },
                    Duration.ofSeconds(2),
                )
            )
            done(env, other.closeAsync())
        }
    }

    @Test
    fun unknownAndRetiredCatchersCannotConfirm(env: Env) {
        F(env, 2).use { fixture ->
            val first = fixture.start("hud", false)
            done(env, first.result())
            val old =
                fixture.player.instance!!
                    .entities
                    .first { it.entityType == EntityType.INTERACTION }
                    .entityId
            done(env, first.cancel(DialogueEndReason.CANCELLED))
            val second = fixture.start("hud", false)
            val handle = (done(env, second.result()) as DialogueStartResult.Started).handle
            val fresh =
                fixture.player.instance!!
                    .entities
                    .first { it.entityType == EntityType.INTERACTION }
                    .entityId
            for (id in listOf(9_999_999, old, fresh)) {
                Thread.sleep(375)
                fixture.packet(ClientInteractEntityPacket(id, PlayerHand.MAIN, Vec.ZERO, false))
                ticks(env)
                assertEquals(if (id == fresh) "reply0" else "start", handle.snapshot().nodeId())
            }
        }
    }

    companion object {
        @JvmStatic
        fun <T> done(env: Env, stage: CompletionStage<T>): T =
            MinestomDialogueHostTest.done(env, stage)

        @JvmStatic
        fun ticks(env: Env) {
            repeat(30) { env.tick() }
        }
    }
}
