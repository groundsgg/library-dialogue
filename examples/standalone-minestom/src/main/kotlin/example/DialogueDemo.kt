package example

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.Action
import gg.grounds.dialogue.core.definition.DialogueDefinition.Capabilities
import gg.grounds.dialogue.core.definition.DialogueDefinition.Choice
import gg.grounds.dialogue.core.definition.DialogueDefinition.Condition
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.port.ActionHandler
import gg.grounds.dialogue.core.port.ActorPort
import gg.grounds.dialogue.core.port.ConditionProvider
import gg.grounds.dialogue.core.runtime.DialogueEndReason
import gg.grounds.dialogue.core.runtime.DialogueHandle
import gg.grounds.dialogue.core.runtime.DialogueInput
import gg.grounds.dialogue.core.runtime.DialogueStartResult
import gg.grounds.dialogue.core.runtime.DialogueView
import gg.grounds.dialogue.core.runtime.InputResult
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.DialogueServices
import gg.grounds.dialogue.minestom.MinestomDialogueHost
import gg.grounds.dialogue.minestom.MinestomDialogueOptions
import gg.grounds.dialogue.minestom.MinestomDialogueRequest
import gg.grounds.dialogue.minestom.MinestomDialogueStart
import gg.grounds.dialogue.minestom.MinestomSpeakerBinding
import gg.grounds.dialogue.minestom.NpcPanelOptions
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.minestom.asset.PackStateTracker
import gg.grounds.dialogue.minestom.channel.LocalOutputChannels
import gg.grounds.dialogue.minestom.geometry.MinestomOcclusion
import gg.grounds.dialogue.minestom.input.InputProfile
import gg.grounds.dialogue.minestom.render.DialoguePostEffects
import gg.grounds.dialogue.presentation.UiLabelSource
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import net.kyori.adventure.resource.ResourcePackStatus
import net.kyori.adventure.text.Component
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerResourcePackStatusEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.timer.TaskSchedule
import net.minestom.server.utils.inventory.PlayerInventoryUtils

/** Standalone application backed by the dialogue library from the selected Maven repository. */
class DialogueDemo private constructor() {
    @JvmRecord
    data class DemoNpc(
        val binding: MinestomSpeakerBinding,
        val actors: Function<Player, out ActorPort?>,
        val wave: Function<Player, CompletionStage<Void>>,
        val close: Supplier<CompletionStage<Void>>,
        val forget: Consumer<Player>,
    )

    fun interface NpcFactory {
        fun spawn(instance: Instance): CompletionStage<DemoNpc>
    }

    companion object {
        // The application listener can run before the host's child event node.
        @JvmStatic
        fun refreshAfterPackStatus(
            tracker: PackStateTracker,
            event: PlayerResourcePackStatusEvent,
            refresh: Runnable,
        ) {
            tracker.observe(event.player, event.packUuid, event.status)
            refresh.run()
        }

        @JvmStatic
        fun main(args: Array<String>) {
            run(args, ::nativeAnchor, emptyMap(), DialoguePostEffects.unsupported())
        }

        @JvmStatic
        @Throws(Exception::class)
        fun run(
            args: Array<String>,
            factory: NpcFactory,
            packAssets: Map<String, ByteArray>,
            effects: DialoguePostEffects,
        ) {
            // Release application-owned resources before stopping the engine.
            // Set this before Minestom initializes its immutable startup properties.
            System.setProperty("minestom.shutdown-on-signal", "false")
            val profile =
                when (System.getProperty("dialogue.profile", "public-26.2")) {
                    "public-26.2" -> MinecraftPackProfile.public262()
                    "grounds-26.3" -> MinecraftPackProfile.grounds263()
                    else -> throw IllegalArgumentException("Unknown profile")
                }
            require(MinecraftServer.PROTOCOL_VERSION == profile.protocol) {
                "Server protocol does not match selected profile"
            }
            val smoke = "--smoke" in args
            val assets = AssetBundle.loadDefault(profile)
            val server = MinecraftServer.init(Auth.Offline())
            val world = MinecraftServer.getInstanceManager().createInstanceContainer()
            world.setChunkSupplier(::LightingChunk)
            world.time = 6000
            checkNotNull(world.defaultClock()).pause()
            world.setGenerator { it.modifier().fillHeight(0, 40, Block.GRASS_BLOCK) }
            val pack =
                DemoPackHost(
                    assets,
                    packAssets,
                    if (smoke) 0 else Integer.getInteger("dialogue.packPort", 25579),
                )
            val app = App(world, assets, pack, effects)
            Runtime.getRuntime().addShutdownHook(Thread(app::close, "dialogue-demo-cleanup"))
            val events = MinecraftServer.getGlobalEventHandler()
            events.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
                event.spawningInstance = world
                event.player.respawnPoint = Pos(0.0, 40.0, 4.0, 180f, 0f)
            }
            server.start("127.0.0.1", if (smoke) 0 else Integer.getInteger("dialogue.port", 25578))
            try {
                app.npc = factory.spawn(world).toCompletableFuture().get(5, TimeUnit.SECONDS)
                app.commands()
                events.addListener(PlayerSpawnEvent::class.java) { event ->
                    if (event.isFirstSpawn) app.connect(event.player)
                }
                events.addListener(PlayerDisconnectEvent::class.java) { event ->
                    app.players.remove(event.player)
                    app.npc.forget.accept(event.player)
                }
                events.addListener(PlayerResourcePackStatusEvent::class.java) { event ->
                    val live = app.players[event.player] ?: return@addListener
                    if (event.packUuid != live.packId) return@addListener
                    live.packStatus = event.status.name
                    if (live.packTesting) {
                        live.player.sendMessage("Pack test client status: ${live.packStatus}")
                    }
                    if (
                        live.restoreGraphics &&
                            event.status == ResourcePackStatus.SUCCESSFULLY_LOADED
                    ) {
                        live.restoreGraphics = false
                        refreshAfterPackStatus(app.host.packState(), event) {
                            live.refresh().whenComplete { _, error ->
                                if (error != null) {
                                    live.player.sendMessage(
                                        "Demo pack restore failed: ${error.message}"
                                    )
                                }
                            }
                        }
                    }
                }
                println(
                    "DIALOGUE_DEMO_READY profile=${profile.id} protocol=${MinecraftServer.PROTOCOL_VERSION}" +
                        " port=${MinecraftServer.getServer().port} packPort=${pack.port()}" +
                        " packSha256=${pack.sha256()} manifestPin=${assets.pin()}"
                )
                if ("--shutdown-probe" in args) {
                    app.prepareShutdownProbe()
                    println("DIALOGUE_DEMO_ACTIVE_SHUTDOWN_READY")
                }
                if (smoke) {
                    app.smoke()
                    println("DIALOGUE_DEMO_SMOKE_OK")
                }
            } finally {
                if (smoke) app.close()
            }
        }

        @JvmStatic
        fun nativeAnchor(world: Instance): CompletionStage<DemoNpc> {
            val entity = Entity(EntityType.ARMOR_STAND)
            entity.setNoGravity(true)
            entity.setHasPhysics(false)
            entity.customName = Component.text("Mechanic")
            entity.isCustomNameVisible = false
            return entity.setInstance(world, Pos(0.0, 40.0, 0.0)).thenApply {
                DemoNpc(
                    MinestomSpeakerBinding("native-anchor-v1", world) {
                        MinestomSpeakerBinding.AnchorSnapshot(
                            entity.position,
                            entity.position.yaw(),
                            entity.position.add(0.0, 1.6, 0.0),
                            !entity.isRemoved,
                        )
                    },
                    { null },
                    {
                        CompletableFuture.failedFuture(
                            IllegalStateException("This basic anchor has no animation engine")
                        )
                    },
                    {
                        entity.remove()
                        CompletableFuture.completedFuture(null)
                    },
                    {},
                )
            }
        }

        @JvmStatic
        fun texts(): TextCatalog {
            val en =
                linkedMapOf("speaker.mechanic" to "Mechanic", "intro" to "Welcome! Choose a topic.")
            val de =
                linkedMapOf(
                    "speaker.mechanic" to "Mechaniker",
                    "intro" to "Willkommen! Wähle ein Thema.",
                )
            val englishChoices =
                listOf(
                    "About this place",
                    "Wait for a task",
                    "Another greeting",
                    "Look around",
                    "The workshop",
                    "Languages",
                    "Goodbye",
                )
            val germanChoices =
                listOf(
                    "Über diesen Ort",
                    "Aufgabe abwarten",
                    "Noch ein Gruß",
                    "Umschauen",
                    "Die Werkstatt",
                    "Sprachen",
                    "Auf Wiedersehen",
                )
            for (i in englishChoices.indices) {
                en["choice$i"] = englishChoices[i]
                de["choice$i"] = germanChoices[i]
                en["reply$i"] =
                    if (i == 1) "The example action has completed."
                    else "This is a reusable dialogue example."
                de["reply$i"] =
                    if (i == 1) "Die Beispielaktion ist abgeschlossen."
                    else "Dies ist ein wiederverwendbarer Beispieldialog."
            }
            return TextCatalog(mapOf("en" to en, "de" to de))
        }

        @JvmStatic fun catalog(): HostCatalog = catalog(false)

        private fun catalog(targetTest: Boolean): HostCatalog =
            HostCatalog(
                "demo-v1",
                setOf("mechanic"),
                if (targetTest)
                    mapOf("demo:unavailable" to HostCatalog.ProviderSpec(emptyMap(), false))
                else emptyMap(),
                mapOf("demo:wait" to HostCatalog.ProviderSpec(emptyMap(), false)),
                setOf("wave", "look"),
                setOf("actor.gesture"),
                false,
            )

        @JvmStatic fun content(): ValidatedDialogue = content(false)

        private fun content(targetTest: Boolean): ValidatedDialogue {
            val builder =
                DialogueBuilder.dialogue("demo:mechanic")
                    .locales("en", "en", "de")
                    .capabilities(Capabilities(emptySet(), setOf("actor.gesture")))
            val choices = ArrayList<Choice>()
            for (i in 0 until 7) {
                choices.add(
                    Choice(
                        "c$i",
                        "choice$i",
                        if (i == 1) "wait" else "reply$i",
                        i == 0,
                        if (targetTest && i == 1) Condition("demo:unavailable", emptyMap(), false)
                        else null,
                    )
                )
                val gesture =
                    when (i) {
                        2 -> "wave"
                        3 -> "look"
                        else -> null
                    }
                builder.node("reply$i", Message("mechanic", "reply$i", gesture, emptyList(), "end"))
            }
            builder.node("start", Message("mechanic", "intro", "wave", choices, null))
            builder.node(
                "wait",
                Action("demo:wait", emptyMap(), "reply1", "reply1", "reply1", "reply1"),
            )
            builder.node("end", End("completed"))
            val report =
                DialogueValidator()
                    .validate(
                        builder.build(),
                        texts(),
                        catalog(targetTest),
                        ValidationLimits.defaults(),
                    )
            check(report.isValid()) { report.toString() }
            return checkNotNull(report.validated())
        }
    }

    private class App(
        val world: Instance,
        val assets: AssetBundle,
        val pack: DemoPackHost,
        effects: DialoguePostEffects,
    ) {
        val host: MinestomDialogueHost
        val players = Collections.synchronizedMap(IdentityHashMap<Player, Live>())
        val stopped = AtomicBoolean()
        lateinit var npc: DemoNpc
        private var lifecycleWorld: Instance? = null

        init {
            val channels =
                LocalOutputChannels(
                    { player, component ->
                        player.sendActionBar(component)
                        CompletableFuture.completedFuture(null)
                    },
                    effects.supported(),
                    effects::replace,
                )
            host =
                MinestomDialogueHost(
                    MinestomDialogueOptions(
                        RuntimeOptions.Budgets(
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(5),
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(30),
                        ),
                        assets.profile(),
                        assets,
                        channels,
                        UiLabelSource.defaults(),
                        Duration.ofMillis(600),
                    )
                )
        }

        @Synchronized
        fun lifecycleWorld(): Instance {
            lifecycleWorld?.let {
                return it
            }
            val testWorld = MinecraftServer.getInstanceManager().createInstanceContainer()
            testWorld.setChunkSupplier(::LightingChunk)
            testWorld.time = 6000
            checkNotNull(testWorld.defaultClock()).pause()
            testWorld.setGenerator { it.modifier().fillHeight(0, 40, Block.STONE) }
            lifecycleWorld = testWorld
            return testWorld
        }

        fun connect(player: Player): Live {
            val live = Live(player)
            players[player] = live
            host.packState().expect(player, 1, pack.packId(), assets.pin())
            player.sendResourcePacks(pack.request())
            player.sendMessage(
                "/dialogue · /renderer hud|npc|chat · /hudchoices below|left|right · /lang en|de ·" +
                    " /confirm use|attack|swap · /hidehud · /vignette · /npcbackdrop · /cancel ·" +
                    " /personalwave · /testitems main|offhand|shield|restore · /checkitems · /packtest" +
                    " remove|fail|restore|status · /instance away|home · /preparetest" +
                    " cancel|disconnect|instance|status · /targettest"
            )
            return live
        }

        fun commands() {
            command("dialogue") { live, _ -> live.begin() }
            command("targettest") { live, _ -> live.targetTest() }
            command("cancel") { live, _ -> live.cancelForItems() }
            command("renderer") { live, args ->
                require(args.size >= 2 && args[1] in setOf("hud", "npc", "chat")) {
                    "/renderer hud|npc|chat"
                }
                val selected = args[1]
                live.renderer = selected
                live.refresh().thenRun { live.player.sendMessage("Renderer set to: $selected") }
            }
            command("hudchoices") { live, args ->
                require(args.size == 2 && args[1] in setOf("below", "left", "right")) {
                    "/hudchoices below|left|right"
                }
                live.effects["hudChoices"] = args[1]
                live.refresh()
            }
            command("hudstyle") { live, args ->
                require(args.size == 2 && args[1] in setOf("shared", "split", "cards")) {
                    "/hudstyle shared|split|cards"
                }
                live.effects["hudStyle"] = args[1]
                live.refresh()
            }
            for (name in listOf("npcangle", "npcgap")) {
                command(name) { live, args ->
                    require(args.size == 2) { "/$name <number>" }
                    val effects = HashMap(live.effects)
                    effects[if (name == "npcangle") "npcChoiceAngle" else "npcChoiceGap"] = args[1]
                    NpcPanelOptions.from(
                        PresentationPreferences("npc", Duration.ZERO, false, effects)
                    )
                    live.effects.clear()
                    live.effects.putAll(effects)
                    live.refresh()
                }
            }
            command("lang") { live, args ->
                require(args.size >= 2 && args[1] in setOf("en", "de")) { "/lang en|de" }
                val selected = args[1]
                live.locale = LocaleContext(selected, live.locale.revision() + 1, "en")
                val ticket = live.ticket
                val change =
                    if (
                        ticket == null ||
                            (live.handle == null && ticket.result().toCompletableFuture().isDone)
                    ) {
                        CompletableFuture.completedFuture(null)
                    } else {
                        ticket.refreshLocale(live.locale)
                    }
                change.thenRun { live.player.sendMessage("Language set to: $selected") }
            }
            command("confirm") { live, args ->
                require(args.size >= 2) { "/confirm use|attack|swap" }
                val trigger =
                    when (args[1]) {
                        "use",
                        "right" -> InputProfile.Trigger.USE
                        "attack",
                        "left" -> InputProfile.Trigger.ATTACK
                        "swap",
                        "f" -> InputProfile.Trigger.SWAP_HANDS
                        else -> throw IllegalArgumentException("/confirm use|attack|swap")
                    }
                live.controls =
                    InputProfile(
                        setOf(InputProfile.Trigger.USE),
                        setOf(trigger),
                        trigger,
                        true,
                        Duration.ofMillis(350),
                    )
                live.player.sendMessage("Confirm profile set for the next /dialogue: $trigger")
                CompletableFuture.completedFuture(null)
            }
            for (key in listOf("hideHud", "vignette", "npcBackdrop")) {
                val name =
                    when (key) {
                        "hideHud" -> "hidehud"
                        "npcBackdrop" -> "npcbackdrop"
                        else -> key
                    }
                command(name) { live, _ ->
                    live.effects[key] = (!live.effects[key].toBoolean()).toString()
                    live.refresh()
                }
            }
            command("testitems") { live, args ->
                require(
                    args.size == 2 && args[1] in setOf("main", "offhand", "shield", "restore")
                ) {
                    "/testitems main|offhand|shield|restore"
                }
                live.cancelForItems().thenRun { live.testItems(args[1]) }
            }
            command("checkitems") { live, _ -> live.cancelForItems().thenRun(live::checkItems) }
            command("packtest") { live, args ->
                require(args.size == 2 && args[1] in setOf("remove", "fail", "restore", "status")) {
                    "/packtest remove|fail|restore|status"
                }
                live.testPack(args[1])
            }
            command("instance") { live, args ->
                require(args.size == 2 && args[1] in setOf("away", "home")) {
                    "/instance away|home"
                }
                live.changeInstance(args[1])
            }
            command("preparetest") { live, args ->
                require(
                    args.size == 2 && args[1] in setOf("cancel", "disconnect", "instance", "status")
                ) {
                    "/preparetest cancel|disconnect|instance|status"
                }
                if (args[1] == "status") {
                    live.reply(
                        live.preparationProbe?.status() ?: "No preparation test on this connection."
                    )
                    CompletableFuture.completedFuture(null)
                } else {
                    live.preparationTest(args[1])
                }
            }
            command("personalwave") { live, _ -> npc.wave.apply(live.player) }
        }

        private fun command(name: String, action: (Live, List<String>) -> CompletionStage<*>) {
            val command = Command(name)
            command.setDefaultExecutor { sender, context ->
                val player = sender as? Player ?: return@setDefaultExecutor
                val live = players[player] ?: return@setDefaultExecutor
                try {
                    action(live, context.input.trim().split(Regex("\\s+"))).whenComplete { _, error
                        ->
                        if (error != null) player.sendMessage("Demo: ${error.message}")
                    }
                } catch (error: Throwable) {
                    player.sendMessage("Demo: ${error.message}")
                }
            }
            MinecraftServer.getCommandManager().register(command)
        }

        private class InventorySnapshot(
            private val items: Array<ItemStack>,
            private val cursor: ItemStack,
            private val slot: Byte,
        ) {
            companion object {
                fun capture(player: Player): InventorySnapshot =
                    InventorySnapshot(
                        player.inventory.itemStacks,
                        player.inventory.cursorItem,
                        player.heldSlot,
                    )
            }

            fun restore(player: Player) {
                player.inventory.copyContents(items)
                player.inventory.cursorItem = cursor
                player.setHeldItemSlot(slot)
            }

            fun differences(player: Player): String {
                val differences = ArrayList<String>()
                if (!items.contentEquals(player.inventory.itemStacks))
                    differences.add("inventory items")
                if (cursor != player.inventory.cursorItem) differences.add("cursor item")
                if (slot != player.heldSlot) {
                    differences.add(
                        "selected slot (expected ${slot + 1}, got ${player.heldSlot + 1})"
                    )
                }
                return differences.joinToString(", ")
            }
        }

        inner class Live(val player: Player) {
            var locale = LocaleContext("en", 0, "en")
            var renderer = "hud"
            var controls = InputProfile.defaults()
            val effects = HashMap<String, String>()
            @Volatile var ticket: MinestomDialogueStart? = null
            @Volatile var handle: DialogueHandle? = null
            private var originalItems: InventorySnapshot? = null
            private var expectedItems: InventorySnapshot? = null
            private var itemMode: String? = null
            var packId: UUID? = pack.packId()
            var packStatus = "REQUESTED"
            var packTesting = false
            var restoreGraphics = false
            private var packOperation = 0L
            @Volatile var preparationProbe: DemoPreparationProbe? = null

            fun reply(message: String) {
                println("DEMO_LIFECYCLE: $message")
                if (player.isOnline) player.sendMessage(message)
            }

            fun changeInstance(direction: String): CompletionStage<Void> {
                val destination = if (direction == "home") world else lifecycleWorld()
                if (player.instance === destination) return CompletableFuture.completedFuture(null)
                // The host must detect the actual instance change.
                return player.setInstance(destination, Pos(0.0, 40.0, 4.0, 180f, 0f)).thenRun {
                    reply(
                        if (direction == "home")
                            "Returned to the NPC world. /dialogue can start again."
                        else "Moved to the stone test world. /instance home returns."
                    )
                }
            }

            @Synchronized
            fun preparationTest(mode: String): CompletionStage<Void> {
                if (player.instance !== world) {
                    return CompletableFuture.failedFuture(
                        IllegalStateException("Use /instance home before a preparation test.")
                    )
                }
                val actors =
                    npc.actors.apply(player)
                        ?: return CompletableFuture.failedFuture(
                            IllegalStateException(
                                "Preparation probe requires the personal-NPC demo."
                            )
                        )
                val previous = ticket
                return cancelForItems().thenCompose {
                    startPreparationProbe(mode, actors, previous)
                }
            }

            @Synchronized
            private fun interruptPreparationProbe(
                mode: String,
                target: MinestomDialogueStart?,
            ): CompletionStage<Void> {
                if (target == null || ticket !== target || !player.isOnline) {
                    return CompletableFuture.failedFuture(
                        CancellationException("Preparation probe superseded")
                    )
                }
                return when (mode) {
                    "cancel" -> target.cancel(DialogueEndReason.CANCELLED)
                    "instance" -> changeInstance("away")
                    "disconnect" -> {
                        player.kick(
                            Component.text(
                                "Preparation disconnect test. Reconnect after a few seconds."
                            )
                        )
                        CompletableFuture.completedFuture(null)
                    }
                    else -> throw IllegalArgumentException("Unknown preparation probe")
                }
            }

            @Synchronized
            private fun startPreparationProbe(
                mode: String,
                actors: ActorPort,
                previous: MinestomDialogueStart?,
            ): CompletionStage<Void> {
                if (ticket !== previous) {
                    return CompletableFuture.failedFuture(
                        CancellationException("Preparation probe superseded")
                    )
                }
                val owned = AtomicReference<MinestomDialogueStart>()
                val probe =
                    DemoPreparationProbe(
                        mode,
                        actors,
                        { interruptPreparationProbe(mode, owned.get()) },
                        ::reply,
                    )
                preparationProbe = probe
                val opening = begin(probe)
                val target = checkNotNull(ticket)
                owned.set(target)
                return opening
                    .thenCompose { target.result() }
                    .thenCompose { result ->
                        if (
                            result !is DialogueStartResult.Rejected ||
                                result.code()?.contains("CLEANUP") == true ||
                                !probe.validHandoff()
                        ) {
                            return@thenCompose target
                                .cancel(DialogueEndReason.CANCELLED)
                                .thenCompose {
                                    CompletableFuture.failedFuture(
                                        IllegalStateException(
                                            "Preparation test FAIL: ${probe.status()}"
                                        )
                                    )
                                }
                        }
                        CompletableFuture.allOf(probe.closed, probe.interrupted).thenRun {
                            check(probe.passed()) { "Preparation test FAIL: ${probe.status()}" }
                            reply("Preparation test PASS: ${probe.status()}")
                        }
                    }
            }

            fun testPack(mode: String): CompletionStage<Void> {
                if (mode == "status") {
                    val ready = host.packState().isReady(player, 1, assets.pin())
                    val output = ticket?.status()?.effectiveSurface ?: "no dialogue"
                    player.sendMessage(
                        "Pack test: $packStatus; ready=$ready; requested=$renderer; output=$output"
                    )
                    return CompletableFuture.completedFuture(null)
                }
                val operation = ++packOperation
                packTesting = true
                restoreGraphics = mode == "restore"
                // Invalidate the removed pack's readiness before asking the presenter to fall back.
                host.packState().forget(player)
                packId?.let { player.removeResourcePacks(it) }
                packId = null
                packStatus = "REMOVED_BY_TEST"
                return refresh().thenRun {
                    if (operation != packOperation) return@thenRun
                    if (mode == "remove") {
                        player.sendMessage(
                            "Demo pack removed; dialogue uses chat. /packtest restore reloads it."
                        )
                        return@thenRun
                    }
                    val requestedPack = UUID.randomUUID()
                    packId = requestedPack
                    packStatus = "REQUESTED"
                    host.packState().expect(player, 1, requestedPack, assets.pin())
                    player.sendResourcePacks(pack.request(requestedPack, mode == "fail"))
                    player.sendMessage(
                        if (mode == "fail") {
                            "Download-failure test requested (real HTTP 404). Allow it if prompted;" +
                                " /packtest status shows the result."
                        } else {
                            "Original demo pack requested. Allow it if prompted; /packtest status shows readiness."
                        }
                    )
                }
            }

            fun cancelForItems(): CompletionStage<Void> =
                ticket?.cancel(DialogueEndReason.CANCELLED)
                    ?: CompletableFuture.completedFuture(null)

            fun testItems(mode: String) {
                if (mode == "restore") {
                    val saved = originalItems
                    if (saved == null) {
                        player.sendMessage("No saved test inventory.")
                        return
                    }
                    saved.restore(player)
                    originalItems = null
                    expectedItems = null
                    itemMode = null
                    player.sendMessage("Original inventory, cursor and selected slot restored.")
                    return
                }
                if (originalItems == null) originalItems = InventorySnapshot.capture(player)
                val inventory = player.inventory
                inventory.setItemStack(0, ItemStack.of(Material.GOLD_INGOT, 3))
                inventory.setItemStack(8, ItemStack.of(Material.DIAMOND, 7))
                inventory.setItemStack(
                    2,
                    if (mode == "offhand") ItemStack.AIR else ItemStack.of(Material.IRON_SWORD),
                )
                inventory.setItemStack(
                    PlayerInventoryUtils.OFFHAND_SLOT,
                    when (mode) {
                        "offhand" -> ItemStack.of(Material.TORCH, 12)
                        "shield" -> ItemStack.of(Material.SHIELD)
                        else -> ItemStack.AIR
                    },
                )
                player.setHeldItemSlot(2)
                expectedItems = InventorySnapshot.capture(player)
                itemMode = mode
                player.sendMessage(
                    "Test items: $mode; selected slot 3. Open /dialogue, then /checkitems to end and compare." +
                        " /testitems restore restores your original inventory."
                )
            }

            fun checkItems() {
                val expected = expectedItems
                if (expected == null) {
                    player.sendMessage("First use /testitems main|offhand|shield.")
                    return
                }
                val differences = expected.differences(player)
                player.sendMessage(
                    if (differences.isEmpty())
                        "Inventory check PASS ($itemMode): items, cursor and selected slot unchanged."
                    else "Inventory check FAIL ($itemMode): changed $differences"
                )
            }

            private fun preferences() =
                PresentationPreferences(renderer, Duration.ofMillis(35), false, effects)

            fun begin(): CompletionStage<Void> = begin(npc.actors.apply(player))

            fun targetTest(): CompletionStage<Void> {
                if (player.instance !== world) {
                    return CompletableFuture.failedFuture(
                        IllegalStateException("Return with /instance home first")
                    )
                }
                val pillars = intArrayOf(-3, 3)
                for (x in pillars) {
                    if (!world.isChunkLoaded(Vec(x.toDouble(), 40.0, 2.0))) {
                        return CompletableFuture.failedFuture(
                            IllegalStateException("Wait for the test area to load")
                        )
                    }
                    for (y in 40 until 46) {
                        val block = world.getBlock(x, y, 2)
                        if (block !== Block.AIR && block !== Block.STONE) {
                            return CompletableFuture.failedFuture(
                                IllegalStateException("The test pillar location is occupied")
                            )
                        }
                    }
                }
                // Fixed obstacles remain available for repeated line-of-sight tests.
                for (x in pillars) {
                    for (y in 40 until 46) world.setBlock(x, y, 2, Block.STONE)
                }
                renderer = "npc"
                player.sendMessage(
                    "Target test: answer 2 is unavailable; side pillars block line of sight. /dialogue" +
                        " opens normal content."
                )
                return begin(npc.actors.apply(player), targetTest = true)
            }

            @Synchronized
            fun begin(actors: ActorPort?, targetTest: Boolean = false): CompletionStage<Void> {
                handle = null
                val dialogue = content(targetTest)
                val action =
                    object : ActionHandler {
                        override fun execute(
                            request: ActionHandler.ActionRequest
                        ): CompletionStage<ActionHandler.ActionResult> {
                            val result = CompletableFuture<ActionHandler.ActionResult>()
                            world
                                .scheduler()
                                .buildTask { result.complete(ActionHandler.ActionResult.SUCCESS) }
                                .delay(TaskSchedule.duration(Duration.ofSeconds(2)))
                                .schedule()
                            return result.minimalCompletionStage()
                        }

                        override fun reconcile(
                            reference: ActionHandler.ActionRef
                        ): CompletionStage<ActionHandler.ActionStatus> =
                            CompletableFuture.completedFuture(
                                ActionHandler.ActionStatus.UNAVAILABLE
                            )
                    }
                val conditions =
                    if (targetTest) {
                        mapOf(
                            "demo:unavailable" to
                                ConditionProvider {
                                    CompletableFuture.completedFuture(
                                        ConditionProvider.ConditionResult.UNAVAILABLE
                                    )
                                }
                        )
                    } else {
                        emptyMap()
                    }
                val start =
                    host.begin(
                        MinestomDialogueRequest(
                            UUID.randomUUID().toString(),
                            player,
                            1,
                            dialogue,
                            DialogueServices(
                                "demo-v1",
                                conditions,
                                mapOf("demo:wait" to action),
                                null,
                                TextResolver.catalogue(texts(), "en"),
                                setOf("actor.gesture"),
                            ),
                            emptyMap(),
                            locale,
                            preferences(),
                            mapOf("mechanic" to npc.binding),
                            actors,
                            controls,
                            0,
                        )
                    )
                ticket = start
                return start.result().thenAccept { result ->
                    if (ticket !== start) return@thenAccept
                    when (result) {
                        is DialogueStartResult.Started -> {
                            val active = result.handle
                            handle = active
                            active.completion().whenComplete { end, error ->
                                if (ticket === start) {
                                    handle = null
                                    if (
                                        error == null &&
                                            end.reason() == DialogueEndReason.OUT_OF_RANGE
                                    ) {
                                        val maxDistance =
                                            dialogue.definition().interaction().maxDistanceBlocks
                                        player.sendMessage(
                                            "Dialogue ended: too far from the NPC (max $maxDistance" +
                                                " blocks). Move closer and use /dialogue again."
                                        )
                                    }
                                }
                            }
                            val status = start.status()
                            player.sendMessage(
                                "Dialogue: ${status.effectiveSurface} (requested ${status.requestedSurface})"
                            )
                        }
                        is DialogueStartResult.Rejected ->
                            player.sendMessage("Dialogue rejected: ${result.code()}")
                    }
                }
            }

            fun refresh(): CompletionStage<Void> {
                val current = ticket
                return if (
                    current == null ||
                        (handle == null && current.result().toCompletableFuture().isDone)
                ) {
                    CompletableFuture.completedFuture(null)
                } else {
                    current.updatePreferences(preferences())
                }
            }
        }

        fun prepareShutdownProbe() {
            val player = smokePlayer()
            val live = players[player] ?: connect(player)
            // Simulated readiness is confined to this server-side lifecycle probe.
            host
                .packState()
                .observe(
                    player,
                    1,
                    pack.packId(),
                    assets.pin(),
                    ResourcePackStatus.SUCCESSFULLY_LOADED,
                )
            live.renderer = "npc"
            live.begin().toCompletableFuture().get(5, TimeUnit.SECONDS)
            if (live.handle == null) throw AssertionError("Shutdown probe dialogue did not start")
        }

        fun smoke() {
            repeat(2) {
                val player = smokePlayer()
                val live = players[player] ?: connect(player)
                host
                    .packState()
                    .observe(
                        player,
                        1,
                        pack.packId(),
                        assets.pin(),
                        ResourcePackStatus.SUCCESSFULLY_LOADED,
                    )
                live.begin().toCompletableFuture().get(5, TimeUnit.SECONDS)
                val handle = live.handle ?: throw AssertionError("Dialogue did not start")
                val ticket = checkNotNull(live.ticket)
                for (renderer in listOf("npc", "chat", "hud")) {
                    live.renderer = renderer
                    live.refresh().toCompletableFuture().get(5, TimeUnit.SECONDS)
                }
                val beforePackTest = handle.snapshot()
                live.testPack("remove").toCompletableFuture().get(5, TimeUnit.SECONDS)
                if (ticket.status().effectiveSurface != "chat") {
                    throw AssertionError("Pack removal did not fall back to chat")
                }
                live.testPack("restore").toCompletableFuture().get(5, TimeUnit.SECONDS)
                // Simulated load exercises the same event path as a successful client download.
                EventDispatcher.call(
                    PlayerResourcePackStatusEvent(
                        player,
                        checkNotNull(live.packId),
                        ResourcePackStatus.SUCCESSFULLY_LOADED,
                    )
                )
                val restoredDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (
                    ticket.status().effectiveSurface != "hud" &&
                        System.nanoTime() < restoredDeadline
                ) {
                    Thread.sleep(10)
                }
                if (ticket.status().effectiveSurface != "hud") {
                    throw AssertionError("Loaded test pack did not restore requested graphics")
                }
                val afterPackTest = handle.snapshot()
                if (
                    beforePackTest.sessionId() != afterPackTest.sessionId() ||
                        beforePackTest.nodeVisitId() != afterPackTest.nodeVisitId() ||
                        beforePackTest.selectedChoiceId() != afterPackTest.selectedChoiceId()
                ) {
                    throw AssertionError("Pack test restarted or changed the semantic dialogue")
                }
                handle
                    .refreshLocale(LocaleContext("de", 1, "en"))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)
                ticket
                    .cancel(DialogueEndReason.CANCELLED)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)
                live.targetTest().toCompletableFuture().get(5, TimeUnit.SECONDS)
                val targetTicket = checkNotNull(live.ticket)
                if (
                    targetTicket.status().effectiveSurface != "npc" ||
                        MinestomOcclusion.visible(
                            world,
                            Vec(3.0, 41.6, 4.0),
                            Vec(3.0, 41.6, 0.0),
                        ) ||
                        !MinestomOcclusion.visible(world, Vec(0.0, 41.6, 4.0), Vec(0.0, 41.6, 0.0))
                ) {
                    throw AssertionError("Target-test graphics or real pillar occlusion failed")
                }
                val targetHandle = checkNotNull(live.handle)
                var targetView = targetHandle.snapshot()
                if (
                    targetView.choices().size != 7 ||
                        targetView.choices()[1].availability !=
                            DialogueView.Availability.UNAVAILABLE
                ) {
                    throw AssertionError(
                        "The target-test provider did not publish its unavailable answer"
                    )
                }
                targetHandle
                    .submit(
                        DialogueInput.Confirm(
                            DialogueInput.InputStamp(
                                targetView.sessionId(),
                                targetView.renderRevision(),
                                10001,
                            ),
                            targetView.selectedChoiceId(),
                        )
                    )
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)
                targetView = targetHandle.snapshot()
                val blocked =
                    targetHandle
                        .submit(
                            DialogueInput.Select(
                                DialogueInput.InputStamp(
                                    targetView.sessionId(),
                                    targetView.renderRevision(),
                                    10002,
                                ),
                                "c1",
                            )
                        )
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                if (
                    blocked != InputResult.INVALID ||
                        targetHandle.snapshot().selectedChoiceId() == "c1"
                ) {
                    throw AssertionError("The target-test unavailable answer was selectable")
                }
                targetTicket
                    .cancel(DialogueEndReason.CANCELLED)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)
                players.remove(player)
                npc.forget.accept(player)
                player.remove()
            }
            lifecycleSmoke()
            if (
                world.entities.any {
                    it.entityType == EntityType.TEXT_DISPLAY ||
                        it.entityType == EntityType.INTERACTION
                }
            ) {
                throw AssertionError("Dialogue entities leaked")
            }
        }

        private fun lifecycleSmoke() {
            val player = smokePlayer()
            val live = players[player] ?: connect(player)
            host
                .packState()
                .observe(
                    player,
                    1,
                    pack.packId(),
                    assets.pin(),
                    ResourcePackStatus.SUCCESSFULLY_LOADED,
                )
            live.begin().toCompletableFuture().get(5, TimeUnit.SECONDS)
            val oldSession = checkNotNull(live.handle)
            live.changeInstance("away").toCompletableFuture().get(5, TimeUnit.SECONDS)
            val ended = oldSession.completion().toCompletableFuture().get(5, TimeUnit.SECONDS)
            if (ended.reason() != DialogueEndReason.HOST_CHANGED) {
                throw AssertionError("Instance change did not invalidate the active dialogue")
            }
            live.changeInstance("home").toCompletableFuture().get(5, TimeUnit.SECONDS)
            if (npc.actors.apply(player) != null) {
                for (mode in listOf("cancel", "instance")) {
                    live.preparationTest(mode).toCompletableFuture().get(8, TimeUnit.SECONDS)
                    val probe = checkNotNull(live.preparationProbe)
                    if (!probe.passed())
                        throw AssertionError("Late actor handoff failed: ${probe.status()}")
                    live.changeInstance("home").toCompletableFuture().get(5, TimeUnit.SECONDS)
                }
            }
            if (npc.actors.apply(player) != null) {
                val obsolete = live.preparationTest("disconnect")
                val obsoleteProbe = checkNotNull(live.preparationProbe)
                obsoleteProbe.prepared.get(5, TimeUnit.SECONDS)
                live.begin().toCompletableFuture().get(8, TimeUnit.SECONDS)
                try {
                    obsolete.toCompletableFuture().get(8, TimeUnit.SECONDS)
                    throw AssertionError("Superseded preparation test reported success")
                } catch (_: ExecutionException) {
                    // Superseding the preparation probe can complete it exceptionally.
                } catch (_: CancellationException) {
                    // Superseding the preparation probe can also cancel it directly.
                }
                if (!obsoleteProbe.closed.isDone || obsoleteProbe.closed.isCompletedExceptionally) {
                    throw AssertionError("Superseded preparation did not release its late actor")
                }
                if (!player.isOnline || live.handle == null) {
                    throw AssertionError("Old preparation timer affected the new session")
                }
                println("DEMO_LIFECYCLE: Superseded disconnect probe left the new session online.")
                live.cancelForItems().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }
            live.begin().toCompletableFuture().get(5, TimeUnit.SECONDS)
            if (live.handle == null) throw AssertionError("Could not reopen after lifecycle tests")
            live.cancelForItems().toCompletableFuture().get(5, TimeUnit.SECONDS)
            players.remove(player)
            npc.forget.accept(player)
            player.remove()
        }

        fun close() {
            if (!stopped.compareAndSet(false, true)) return
            try {
                host.closeAsync().toCompletableFuture().get(35, TimeUnit.SECONDS)
                if (::npc.isInitialized)
                    npc.close.get().toCompletableFuture().get(35, TimeUnit.SECONDS)
                println("DEMO_RESOURCES_CLOSED")
            } catch (error: Exception) {
                System.err.println("DEMO_CLEANUP_FAILED: $error")
            } finally {
                pack.close()
                MinecraftServer.stopCleanly()
            }
        }

        private fun smokePlayer(): Player {
            val connection =
                object : PlayerConnection() {
                    override fun sendPacket(packet: SendablePacket) {}

                    override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
                }
            val player = Player(connection, GameProfile(UUID.randomUUID(), "smoke"))
            // Await the live tick loop to exercise normal instance changes.
            player.setInstance(world, Pos(0.0, 40.0, 4.0, 180f, 0f)).get(5, TimeUnit.SECONDS)
            return player
        }
    }
}
