package example;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.minestom.*;
import gg.grounds.dialogue.minestom.asset.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.input.*;
import gg.grounds.dialogue.minestom.render.*;
import gg.grounds.dialogue.presentation.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import net.minestom.server.*;
import net.minestom.server.command.builder.Command;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.event.player.*;
import net.minestom.server.instance.*;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.TaskSchedule;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;

/** Independent Java application; every library class comes from the selected Maven repository. */
public final class DialogueDemo {
  public record DemoNpc(
      MinestomSpeakerBinding binding,
      Function<Player, ActorPort> actors,
      Function<Player, CompletionStage<Void>> wave,
      Supplier<CompletionStage<Void>> close,
      Consumer<Player> forget) {}

  @FunctionalInterface
  public interface NpcFactory {
    CompletionStage<DemoNpc> spawn(Instance instance);
  }

  public static void main(String[] args) throws Exception {
    run(args, DialogueDemo::nativeAnchor, Map.of(), DialoguePostEffects.unsupported());
  }

  public static void run(
      String[] args,
      NpcFactory factory,
      Map<String, byte[]> packAssets,
      DialoguePostEffects effects)
      throws Exception {
    var profile =
        switch (System.getProperty("dialogue.profile", "public-26.2")) {
          case "public-26.2" -> MinecraftPackProfile.public262();
          case "grounds-26.3" -> MinecraftPackProfile.grounds263();
          default -> throw new IllegalArgumentException("Unknown profile");
        };
    if (MinecraftServer.PROTOCOL_VERSION != profile.protocol())
      throw new IllegalArgumentException("Server protocol does not match selected profile");
    boolean smoke = Arrays.asList(args).contains("--smoke");
    var assets = AssetBundle.loadDefault(profile);
    var server = MinecraftServer.init(new Auth.Offline());
    var world = MinecraftServer.getInstanceManager().createInstanceContainer();
    world.setChunkSupplier(LightingChunk::new);
    world.setTime(6000);
    world.defaultClock().pause();
    world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
    var pack =
        new DemoPackHost(
            assets, packAssets, smoke ? 0 : Integer.getInteger("dialogue.packPort", 25579));
    var app = new App(world, assets, pack, effects);
    Runtime.getRuntime().addShutdownHook(new Thread(app::close, "dialogue-demo-cleanup"));
    MinecraftServer.getGlobalEventHandler()
        .addListener(
            AsyncPlayerConfigurationEvent.class,
            e -> {
              e.setSpawningInstance(world);
              e.getPlayer().setRespawnPoint(new Pos(0, 40, 4, 180, 0));
            });
    server.start("127.0.0.1", smoke ? 0 : Integer.getInteger("dialogue.port", 25578));
    try {
      app.npc = factory.spawn(world).toCompletableFuture().get(5, TimeUnit.SECONDS);
      app.commands();
      MinecraftServer.getGlobalEventHandler()
          .addListener(
              PlayerSpawnEvent.class,
              e -> {
                if (e.isFirstSpawn()) app.connect(e.getPlayer());
              });
      MinecraftServer.getGlobalEventHandler()
          .addListener(
              PlayerDisconnectEvent.class,
              e -> {
                app.players.remove(e.getPlayer());
                app.npc.forget().accept(e.getPlayer());
              });
      MinecraftServer.getGlobalEventHandler()
          .addListener(
              PlayerResourcePackStatusEvent.class,
              e -> {
                var live = app.players.get(e.getPlayer());
                if (live != null && e.getPackUuid().equals(live.packId)) {
                  live.packStatus = e.getStatus().name();
                  if (live.packTesting)
                    live.player.sendMessage("Pack test client status: " + live.packStatus);
                }
              });
      System.out.println(
          "DIALOGUE_DEMO_READY profile="
              + profile.id()
              + " protocol="
              + MinecraftServer.PROTOCOL_VERSION
              + " port="
              + MinecraftServer.getServer().getPort()
              + " packPort="
              + pack.port()
              + " packSha256="
              + pack.sha256()
              + " manifestPin="
              + assets.pin());
      if (smoke) {
        app.smoke();
        System.out.println("DIALOGUE_DEMO_SMOKE_OK");
      }
    } finally {
      if (smoke) app.close();
    }
  }

  public static CompletionStage<DemoNpc> nativeAnchor(Instance world) {
    var entity = new Entity(EntityType.ARMOR_STAND);
    entity.setNoGravity(true);
    entity.setHasPhysics(false);
    entity.setCustomName(Component.text("Mechanic"));
    entity.setCustomNameVisible(false);
    return entity
        .setInstance(world, new Pos(0, 40, 0))
        .thenApply(
            v ->
                new DemoNpc(
                    new MinestomSpeakerBinding(
                        "native-anchor-v1",
                        world,
                        () ->
                            new MinestomSpeakerBinding.AnchorSnapshot(
                                entity.getPosition(),
                                entity.getPosition().yaw(),
                                entity.getPosition().add(0, 1.6, 0),
                                !entity.isRemoved())),
                    p -> null,
                    p ->
                        CompletableFuture.failedFuture(
                            new IllegalStateException("This basic anchor has no animation engine")),
                    () -> {
                      entity.remove();
                      return CompletableFuture.completedFuture(null);
                    },
                    p -> {}));
  }

  public static TextCatalog texts() {
    var en = new LinkedHashMap<String, String>();
    var de = new LinkedHashMap<String, String>();
    en.put("speaker.mechanic", "Mechanic");
    de.put("speaker.mechanic", "Mechaniker");
    en.put("intro", "Welcome! Choose a topic.");
    de.put("intro", "Willkommen! Wähle ein Thema.");
    String[] a = {
      "About this place",
      "Wait for a task",
      "Another greeting",
      "Look around",
      "The workshop",
      "Languages",
      "Goodbye"
    };
    String[] b = {
      "Über diesen Ort",
      "Aufgabe abwarten",
      "Noch ein Gruß",
      "Umschauen",
      "Die Werkstatt",
      "Sprachen",
      "Auf Wiedersehen"
    };
    for (int i = 0; i < 7; i++) {
      en.put("choice" + i, a[i]);
      de.put("choice" + i, b[i]);
      en.put(
          "reply" + i,
          i == 1 ? "The example action has completed." : "This is a reusable dialogue example.");
      de.put(
          "reply" + i,
          i == 1
              ? "Die Beispielaktion ist abgeschlossen."
              : "Dies ist ein wiederverwendbarer Beispieldialog.");
    }
    return new TextCatalog(Map.of("en", en, "de", de));
  }

  public static HostCatalog catalog() {
    return new HostCatalog(
        "demo-v1",
        Set.of("mechanic"),
        Map.of(),
        Map.of("demo:wait", new HostCatalog.ProviderSpec(Map.of(), false)),
        Set.of("wave", "look"),
        Set.of("actor.gesture"),
        false);
  }

  public static ValidatedDialogue content() {
    var builder =
        DialogueBuilder.dialogue("demo:mechanic")
            .locales("en", "en", "de")
            .capabilities(new Capabilities(Set.of(), Set.of("actor.gesture")));
    var choices = new ArrayList<Choice>();
    for (int i = 0; i < 7; i++) {
      choices.add(new Choice("c" + i, "choice" + i, i == 1 ? "wait" : "reply" + i, i == 0, null));
      builder.node(
          "reply" + i,
          new Message(
              "mechanic", "reply" + i, i == 2 ? "wave" : i == 3 ? "look" : null, List.of(), "end"));
    }
    builder.node("start", new Message("mechanic", "intro", "wave", choices, null));
    builder.node("wait", new Action("demo:wait", Map.of(), "reply1", "reply1", "reply1", "reply1"));
    builder.node("end", new End("completed"));
    var report =
        new DialogueValidator()
            .validate(builder.build(), texts(), catalog(), ValidationLimits.defaults());
    if (!report.isValid()) throw new IllegalStateException(report.toString());
    return report.validated();
  }

  private static final class App {
    final Instance world;
    final AssetBundle assets;
    final DemoPackHost pack;
    final MinestomDialogueHost host;
    final Map<Player, Live> players = Collections.synchronizedMap(new IdentityHashMap<>());
    final AtomicBoolean stopped = new AtomicBoolean();
    DemoNpc npc;

    App(Instance world, AssetBundle assets, DemoPackHost pack, DialoguePostEffects effects) {
      this.world = world;
      this.assets = assets;
      this.pack = pack;
      var channels =
          new LocalOutputChannels(
              (p, c) -> {
                p.sendActionBar(c);
                return CompletableFuture.completedFuture(null);
              },
              effects.supported(),
              effects::replace);
      host =
          new MinestomDialogueHost(
              new MinestomDialogueOptions(
                  new RuntimeOptions.Budgets(
                      Duration.ofSeconds(2),
                      Duration.ofSeconds(5),
                      Duration.ofSeconds(10),
                      Duration.ofSeconds(30)),
                  assets.profile(),
                  assets,
                  channels,
                  UiLabelSource.defaults(),
                  Duration.ofMillis(600)));
    }

    Live connect(Player p) {
      var live = new Live(p);
      players.put(p, live);
      host.packState().expect(p, 1, pack.packId(), assets.pin());
      p.sendResourcePacks(pack.request());
      p.sendMessage(
          "/dialogue · /renderer hud|npc|chat · /hudchoices below|left|right · /lang en|de ·"
              + " /confirm use|attack|swap · /hidehud · /vignette · /npcbackdrop · /cancel ·"
              + " /personalwave · /testitems main|offhand|shield|restore · /checkitems · /packtest"
              + " remove|fail|restore|status");
      return live;
    }

    void commands() {
      command("dialogue", (l, args) -> l.begin());
      command(
          "cancel",
          (l, args) ->
              l.ticket == null
                  ? CompletableFuture.completedFuture(null)
                  : l.ticket.cancel(DialogueEndReason.CANCELLED));
      command(
          "renderer",
          (l, args) -> {
            if (args.length < 2 || !Set.of("hud", "npc", "chat").contains(args[1]))
              throw new IllegalArgumentException("/renderer hud|npc|chat");
            String selected = args[1];
            l.renderer = selected;
            return l.refresh().thenRun(() -> l.player.sendMessage("Renderer set to: " + selected));
          });
      command(
          "hudchoices",
          (l, args) -> {
            if (args.length != 2 || !Set.of("below", "left", "right").contains(args[1]))
              throw new IllegalArgumentException("/hudchoices below|left|right");
            l.effects.put("hudChoices", args[1]);
            return l.refresh();
          });
      command(
          "hudstyle",
          (l, args) -> {
            if (args.length != 2 || !Set.of("shared", "split", "cards").contains(args[1]))
              throw new IllegalArgumentException("/hudstyle shared|split|cards");
            l.effects.put("hudStyle", args[1]);
            return l.refresh();
          });
      for (String name : List.of("npcangle", "npcgap"))
        command(
            name,
            (l, args) -> {
              if (args.length != 2) throw new IllegalArgumentException("/" + name + " <number>");
              var effects = new HashMap<>(l.effects);
              effects.put(name.equals("npcangle") ? "npcChoiceAngle" : "npcChoiceGap", args[1]);
              NpcPanelOptions.from(
                  new PresentationPreferences("npc", Duration.ZERO, false, effects));
              l.effects.clear();
              l.effects.putAll(effects);
              return l.refresh();
            });
      command(
          "lang",
          (l, args) -> {
            if (args.length < 2 || !Set.of("en", "de").contains(args[1]))
              throw new IllegalArgumentException("/lang en|de");
            l.locale = new LocaleContext(args[1], l.locale.revision() + 1, "en");
            String selected = args[1];
            CompletionStage<Void> change =
                l.ticket == null
                        || (l.handle == null && l.ticket.result().toCompletableFuture().isDone())
                    ? CompletableFuture.completedFuture(null)
                    : l.ticket.refreshLocale(l.locale).thenApply(v -> null);
            return change.thenRun(() -> l.player.sendMessage("Language set to: " + selected));
          });
      command(
          "confirm",
          (l, args) -> {
            if (args.length < 2) throw new IllegalArgumentException("/confirm use|attack|swap");
            var trigger =
                switch (args[1]) {
                  case "use", "right" -> InputProfile.Trigger.USE;
                  case "attack", "left" -> InputProfile.Trigger.ATTACK;
                  case "swap", "f" -> InputProfile.Trigger.SWAP_HANDS;
                  default -> throw new IllegalArgumentException("/confirm use|attack|swap");
                };
            l.controls =
                new InputProfile(
                    Set.of(InputProfile.Trigger.USE),
                    Set.of(trigger),
                    trigger,
                    true,
                    Duration.ofMillis(350));
            l.player.sendMessage("Confirm profile set for the next /dialogue: " + trigger);
            return CompletableFuture.completedFuture(null);
          });
      for (var key : List.of("hideHud", "vignette", "npcBackdrop"))
        command(
            switch (key) {
              case "hideHud" -> "hidehud";
              case "npcBackdrop" -> "npcbackdrop";
              default -> key;
            },
            (l, args) -> {
              l.effects.put(
                  key,
                  Boolean.toString(!Boolean.parseBoolean(l.effects.getOrDefault(key, "false"))));
              return l.refresh();
            });
      command(
          "testitems",
          (l, args) -> {
            if (args.length != 2
                || !Set.of("main", "offhand", "shield", "restore").contains(args[1]))
              throw new IllegalArgumentException("/testitems main|offhand|shield|restore");
            return l.cancelForItems().thenRun(() -> l.testItems(args[1]));
          });
      command("checkitems", (l, args) -> l.cancelForItems().thenRun(l::checkItems));
      command(
          "packtest",
          (l, args) -> {
            if (args.length != 2
                || !Set.of("remove", "fail", "restore", "status").contains(args[1]))
              throw new IllegalArgumentException("/packtest remove|fail|restore|status");
            return l.testPack(args[1]);
          });
      command("personalwave", (l, args) -> npc.wave().apply(l.player));
    }

    void command(String name, BiFunction<Live, String[], CompletionStage<?>> action) {
      var cmd = new Command(name);
      cmd.setDefaultExecutor(
          (sender, context) -> {
            if (!(sender instanceof Player p)) return;
            var l = players.get(p);
            if (l == null) return;
            try {
              action
                  .apply(l, context.getInput().trim().split("\\s+"))
                  .whenComplete(
                      (v, error) -> {
                        if (error != null) p.sendMessage("Demo: " + error.getMessage());
                      });
            } catch (Throwable error) {
              p.sendMessage("Demo: " + error.getMessage());
            }
          });
      MinecraftServer.getCommandManager().register(cmd);
    }

    private record InventorySnapshot(ItemStack[] items, ItemStack cursor, byte slot) {
      static InventorySnapshot capture(Player player) {
        return new InventorySnapshot(
            player.getInventory().getItemStacks(),
            player.getInventory().getCursorItem(),
            player.getHeldSlot());
      }

      void restore(Player player) {
        player.getInventory().copyContents(items);
        player.getInventory().setCursorItem(cursor);
        player.setHeldItemSlot(slot);
      }

      String differences(Player player) {
        var differences = new ArrayList<String>();
        if (!Arrays.equals(items, player.getInventory().getItemStacks()))
          differences.add("inventory items");
        if (!cursor.equals(player.getInventory().getCursorItem())) differences.add("cursor item");
        if (slot != player.getHeldSlot())
          differences.add(
              "selected slot (expected "
                  + (slot + 1)
                  + ", got "
                  + (player.getHeldSlot() + 1)
                  + ")");
        return String.join(", ", differences);
      }
    }

    final class Live {
      final Player player;
      LocaleContext locale = new LocaleContext("en", 0, "en");
      String renderer = "hud";
      InputProfile controls = InputProfile.defaults();
      final Map<String, String> effects = new HashMap<>();
      MinestomDialogueStart ticket;
      DialogueHandle handle;
      InventorySnapshot originalItems, expectedItems;
      String itemMode;
      UUID packId = pack.packId();
      String packStatus = "REQUESTED";
      boolean packTesting;
      long packOperation;

      Live(Player player) {
        this.player = player;
      }

      CompletionStage<Void> testPack(String mode) {
        if (mode.equals("status")) {
          player.sendMessage(
              "Pack test: "
                  + packStatus
                  + "; ready="
                  + host.packState().isReady(player, 1, assets.pin())
                  + "; requested="
                  + renderer
                  + "; output="
                  + (ticket == null ? "no dialogue" : ticket.status().effectiveSurface()));
          return CompletableFuture.completedFuture(null);
        }
        long operation = ++packOperation;
        packTesting = true;
        // The application owns this one contribution and knows it has been removed.
        // Invalidate its readiness before asking the presenter to fall back.
        host.packState().forget(player);
        if (packId != null) player.removeResourcePacks(packId);
        packId = null;
        packStatus = "REMOVED_BY_TEST";
        return refresh()
            .thenRun(
                () -> {
                  if (operation != packOperation) return;
                  if (mode.equals("remove")) {
                    player.sendMessage(
                        "Demo pack removed; dialogue uses chat. /packtest restore reloads it.");
                    return;
                  }
                  packId = UUID.randomUUID();
                  packStatus = "REQUESTED";
                  host.packState().expect(player, 1, packId, assets.pin());
                  player.sendResourcePacks(pack.request(packId, mode.equals("fail")));
                  player.sendMessage(
                      mode.equals("fail")
                          ? "Download-failure test requested (real HTTP 404). Allow it if prompted;"
                                + " /packtest status shows the result."
                          : "Original demo pack requested. Allow it if prompted; /packtest status"
                                + " shows readiness.");
                });
      }

      CompletionStage<Void> cancelForItems() {
        return ticket == null
            ? CompletableFuture.completedFuture(null)
            : ticket.cancel(DialogueEndReason.CANCELLED);
      }

      void testItems(String mode) {
        if (mode.equals("restore")) {
          if (originalItems == null) {
            player.sendMessage("No saved test inventory.");
            return;
          }
          originalItems.restore(player);
          originalItems = expectedItems = null;
          itemMode = null;
          player.sendMessage("Original inventory, cursor and selected slot restored.");
          return;
        }
        if (originalItems == null) originalItems = InventorySnapshot.capture(player);
        var inventory = player.getInventory();
        inventory.setItemStack(0, ItemStack.of(Material.GOLD_INGOT, 3));
        inventory.setItemStack(8, ItemStack.of(Material.DIAMOND, 7));
        inventory.setItemStack(
            2, mode.equals("offhand") ? ItemStack.AIR : ItemStack.of(Material.IRON_SWORD));
        inventory.setItemStack(
            PlayerInventoryUtils.OFFHAND_SLOT,
            switch (mode) {
              case "offhand" -> ItemStack.of(Material.TORCH, 12);
              case "shield" -> ItemStack.of(Material.SHIELD);
              default -> ItemStack.AIR;
            });
        player.setHeldItemSlot((byte) 2);
        expectedItems = InventorySnapshot.capture(player);
        itemMode = mode;
        player.sendMessage(
            "Test items: "
                + mode
                + "; selected slot 3. Open /dialogue, then /checkitems to end and compare."
                + " /testitems restore restores your original inventory.");
      }

      void checkItems() {
        if (expectedItems == null) {
          player.sendMessage("First use /testitems main|offhand|shield.");
          return;
        }
        var differences = expectedItems.differences(player);
        player.sendMessage(
            differences.isEmpty()
                ? "Inventory check PASS ("
                    + itemMode
                    + "): items, cursor and selected slot unchanged."
                : "Inventory check FAIL (" + itemMode + "): changed " + differences);
      }

      PresentationPreferences preferences() {
        return new PresentationPreferences(renderer, Duration.ofMillis(35), false, effects);
      }

      CompletionStage<Void> begin() {
        handle = null;
        var dialogue = content();
        var action =
            new ActionHandler() {
              public CompletionStage<ActionResult> execute(ActionRequest r) {
                var result = new CompletableFuture<ActionResult>();
                world
                    .scheduler()
                    .buildTask(() -> result.complete(ActionResult.SUCCESS))
                    .delay(TaskSchedule.duration(Duration.ofSeconds(2)))
                    .schedule();
                return result.minimalCompletionStage();
              }

              public CompletionStage<ActionStatus> reconcile(ActionRef ref) {
                return CompletableFuture.completedFuture(ActionStatus.UNAVAILABLE);
              }
            };
        var start =
            host.begin(
                new MinestomDialogueRequest(
                    UUID.randomUUID().toString(),
                    player,
                    1,
                    dialogue,
                    new DialogueServices(
                        "demo-v1",
                        Map.of(),
                        Map.of("demo:wait", action),
                        null,
                        TextResolver.catalogue(texts(), "en"),
                        Set.of("actor.gesture")),
                    Map.of(),
                    locale,
                    preferences(),
                    Map.of("mechanic", npc.binding()),
                    npc.actors().apply(player),
                    controls,
                    0));
        ticket = start;
        return start
            .result()
            .thenAccept(
                result -> {
                  if (ticket != start) return;
                  if (result instanceof DialogueStartResult.Started ready) {
                    handle = ready.handle();
                    handle
                        .completion()
                        .whenComplete(
                            (end, error) -> {
                              if (ticket == start) {
                                handle = null;
                                if (error == null && end.reason() == DialogueEndReason.OUT_OF_RANGE)
                                  player.sendMessage(
                                      "Dialogue ended: too far from the NPC (max "
                                          + dialogue.definition().interaction().maxDistanceBlocks()
                                          + " blocks). Move closer and use /dialogue again.");
                              }
                            });
                    player.sendMessage(
                        "Dialogue: "
                            + start.status().effectiveSurface()
                            + " (requested "
                            + start.status().requestedSurface()
                            + ")");
                  } else
                    player.sendMessage(
                        "Dialogue rejected: " + ((DialogueStartResult.Rejected) result).code());
                });
      }

      CompletionStage<Void> refresh() {
        return ticket == null || (handle == null && ticket.result().toCompletableFuture().isDone())
            ? CompletableFuture.completedFuture(null)
            : ticket.updatePreferences(preferences());
      }
    }

    void smoke() throws Exception {
      for (int i = 0; i < 2; i++) {
        var player = smokePlayer(world);
        var live = players.get(player);
        if (live == null) live = connect(player);
        host.packState()
            .observe(
                player,
                1,
                pack.packId(),
                assets.pin(),
                net.kyori.adventure.resource.ResourcePackStatus.SUCCESSFULLY_LOADED);
        live.begin().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (live.handle == null) throw new AssertionError("Dialogue did not start");
        for (var renderer : List.of("npc", "chat", "hud")) {
          live.renderer = renderer;
          live.refresh().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        live.handle
            .refreshLocale(new LocaleContext("de", 1, "en"))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        live.ticket
            .cancel(DialogueEndReason.CANCELLED)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
        players.remove(player);
        npc.forget().accept(player);
        player.remove();
      }
      if (world.getEntities().stream()
          .anyMatch(
              e ->
                  e.getEntityType() == EntityType.TEXT_DISPLAY
                      || e.getEntityType() == EntityType.INTERACTION))
        throw new AssertionError("Dialogue entities leaked");
    }

    void close() {
      if (!stopped.compareAndSet(false, true)) return;
      try {
        host.closeAsync().toCompletableFuture().get(35, TimeUnit.SECONDS);
        if (npc != null) npc.close().get().toCompletableFuture().get(35, TimeUnit.SECONDS);
      } catch (Exception error) {
        System.err.println("DEMO_CLEANUP_FAILED: " + error);
      } finally {
        pack.close();
        MinecraftServer.stopCleanly();
      }
    }
  }

  private static Player smokePlayer(Instance world) throws Exception {
    var connection =
        new net.minestom.server.network.player.PlayerConnection() {
          public void sendPacket(net.minestom.server.network.packet.server.SendablePacket packet) {}

          public java.net.SocketAddress getRemoteAddress() {
            return new InetSocketAddress(0);
          }
        };
    var p =
        new Player(
            connection,
            new net.minestom.server.network.player.GameProfile(UUID.randomUUID(), "smoke"));
    // Await the live tick loop rather than entering Player.join()'s scheduler-draining path.
    p.setInstance(world, new Pos(0, 40, 4, 180, 0)).get(5, TimeUnit.SECONDS);
    return p;
  }
}
