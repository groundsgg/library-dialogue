package gg.grounds.dialogue.minestom;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.port.*;
import gg.grounds.dialogue.core.runtime.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.minestom.channel.*;
import gg.grounds.dialogue.minestom.input.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minestom.server.*;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.event.*;
import net.minestom.server.event.player.*;
import net.minestom.server.network.packet.client.play.*;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.testing.*;
import org.junit.jupiter.api.Test;

@EnvTest
class NativeReviewRegressionTest {
  static <T> T done(Env e, CompletionStage<T> s) {
    return MinestomDialogueHostTest.done(e, s);
  }

  static void ticks(Env e) {
    for (int i = 0; i < 30; i++) e.tick();
  }

  static class F implements AutoCloseable {
    final Env env;
    final TestConnection connection;
    final Player player;
    final MinestomDialogueHost host;
    final LocalOutputChannels channels;
    final AtomicReference<CompletableFuture<Void>> write =
        new AtomicReference<>(CompletableFuture.completedFuture(null));
    final AtomicInteger pendingWrites = new AtomicInteger();
    final List<Component> hudFrames = new CopyOnWriteArrayList<>();
    final MinestomDialogueOptions options;
    final UUID pack = UUID.randomUUID();
    final ValidatedDialogue content;
    final TextCatalog texts;

    F(Env e, int answers) {
      this(e, answers, false);
    }

    F(Env e, int answers, boolean wrapped) {
      env = e;
      var base = new MinestomDialogueHostTest.Fixture(e);
      done(e, base.host.closeAsync());
      options = base.options;
      connection = e.createConnection();
      player = connection.connect(e.createFlatInstance(), new Pos(0, 42, 4, 180, 0));
      channels =
          new LocalOutputChannels(
              (p, c) -> {
                hudFrames.add(c);
                var gate = write.get();
                if (!gate.isDone()) pendingWrites.incrementAndGet();
                return gate;
              },
              false,
              (p, k) -> CompletableFuture.completedFuture(null));
      host =
          new MinestomDialogueHost(
              new MinestomDialogueOptions(
                  options.budgets(),
                  options.profile(),
                  options.assets(),
                  channels,
                  options.labels(),
                  options.distanceGrace()));
      var en = new LinkedHashMap<String, String>();
      var de = new LinkedHashMap<String, String>();
      en.put("line", "Hello");
      de.put("line", "Hallo");
      var choices = new ArrayList<Choice>();
      var b = DialogueBuilder.dialogue("test:review").locales("en", "en", "de");
      for (int i = 0; i < answers; i++) {
        en.put("c" + i, "Choice " + i + (wrapped && i == 0 ? "\nDetails" : ""));
        de.put("c" + i, "Antwort " + i);
        choices.add(new Choice("c" + i, "c" + i, "reply" + i, i == 0, null));
        b.node("reply" + i, new Message("npc", "c" + i, null, List.of(), "end"));
      }
      texts = new TextCatalog(Map.of("en", en, "de", de));
      var d =
          b.node("start", new Message("npc", "line", null, choices, null))
              .node("end", new End("completed"))
              .build();
      var report =
          new DialogueValidator()
              .validate(
                  d,
                  texts,
                  new HostCatalog(
                      "v1", Set.of("npc"), Map.of(), Map.of(), Set.of(), Set.of(), false),
                  ValidationLimits.defaults());
      assertTrue(report.isValid(), report.toString());
      content = report.validated();
      host.packState().expect(player, 1, pack, options.assets().pin());
      host.packState().observe(player, pack, ResourcePackStatus.SUCCESSFULLY_LOADED);
    }

    MinestomDialogueStart start(String surface, boolean hide) {
      return start(surface, hide, InputProfile.defaults());
    }

    MinestomDialogueStart start(String surface, boolean hide, InputProfile controls) {
      var binding =
          new MinestomSpeakerBinding(
              "npc",
              player.getInstance(),
              () ->
                  new MinestomSpeakerBinding.AnchorSnapshot(
                      new Vec(0, 42, 0), 0, new Vec(0, 43.6, 0), true));
      return host.begin(
          new MinestomDialogueRequest(
              UUID.randomUUID().toString(),
              player,
              1,
              content,
              new DialogueServices(
                  "v1", Map.of(), Map.of(), null, TextResolver.catalogue(texts, "en"), Set.of()),
              Map.of(),
              new LocaleContext("en", 0, "en"),
              new PresentationPreferences(
                  surface, Duration.ZERO, false, Map.of("hideHud", Boolean.toString(hide))),
              Map.of("npc", binding),
              null,
              controls,
              0));
    }

    void packet(Object packet) {
      EventDispatcher.call(
          new PlayerPacketEvent(
              player, (net.minestom.server.network.packet.client.ClientPacket) packet));
    }

    List<String> commands(List<SystemChatPacket> packets) {
      var out = new ArrayList<String>();
      for (var p : packets) {
        var m =
            java.util.regex.Pattern.compile("/(dialogue_input_[a-z0-9]+ [A-Za-z0-9_-]{22})")
                .matcher(GsonComponentSerializer.gson().serialize(p.message()));
        while (m.find()) out.add(m.group(1));
      }
      return out;
    }

    public void close() {
      write.get().complete(null);
      done(env, host.closeAsync());
    }
  }

  @Test
  void queuedConfirmCannotRunUnacknowledgedSelection(Env env) throws Exception {
    selectionRace(env, 2, 0);
  }

  @Test
  void queuedConfirmCannotRunUnacknowledgedNextPage(Env env) throws Exception {
    selectionRace(env, 7, 2);
  }

  void selectionRace(Env env, int count, int initial) throws Exception {
    try (var f = new F(env, count)) {
      var ticket = f.start("hud", false);
      var h = ((DialogueStartResult.Started) done(env, ticket.result())).handle();
      for (int n = 1; n <= initial; n++) {
        f.packet(new ClientHeldItemChangePacket((short) n));
        ticks(env);
      }
      assertEquals("c" + initial, h.snapshot().selectedChoiceId());
      ticks(env);
      Thread.sleep(375);
      f.write.set(new CompletableFuture<>());
      f.packet(new ClientHeldItemChangePacket((short) (initial + 1)));
      f.packet(new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
      ticks(env);
      assertEquals(
          "start",
          h.snapshot().nodeId(),
          "No unseen answer may execute before output acknowledgement");
      f.write.get().complete(null);
      ticks(env);
      assertEquals(
          "start",
          h.snapshot().nodeId(),
          "A stale queued confirm must be discarded after the new frame");
    }
  }

  @Test
  void startupObserversMayReenterTicketFromAnotherThread(Env env) {
    try (var f = new F(env, 2)) {
      f.write.set(new CompletableFuture<>());
      var t = f.start("hud", false);
      var observer =
          t.result()
              .thenAccept(
                  v -> {
                    try {
                      CompletableFuture.runAsync(() -> t.cancel(DialogueEndReason.CANCELLED))
                          .get(1, TimeUnit.SECONDS);
                    } catch (Exception error) {
                      throw new CompletionException(error);
                    }
                  });
      f.write.get().complete(null);
      done(env, observer);
    }
  }

  @Test
  void startupRetainsLatestLocaleAndSurfaceBeforePublicStarted(Env env) {
    try (var f = new F(env, 2)) {
      f.write.set(new CompletableFuture<>());
      var ticket = f.start("hud", false);
      assertTrue(env.tickWhile(() -> f.pendingWrites.get() == 0, Duration.ofSeconds(2)));
      var language = ticket.refreshLocale(new LocaleContext("de", 1, "en"));
      var renderer =
          ticket.updatePreferences(
              new PresentationPreferences("chat", Duration.ZERO, false, Map.of()));
      assertFalse(ticket.result().toCompletableFuture().isDone());
      f.write.get().complete(null);
      var h = ((DialogueStartResult.Started) done(env, ticket.result())).handle();
      assertEquals("de", h.snapshot().localeTag());
      assertEquals("Hallo", h.snapshot().text());
      assertEquals("chat", ticket.status().effectiveSurface());
      assertEquals(RefreshResult.UPDATED, done(env, language));
      done(env, renderer);
    }
  }

  @Test
  void cancelledNativeCandidateRetainsWorkingHudInput(Env env) throws Exception {
    try (var f = new F(env, 2)) {
      var t = f.start("hud", false);
      var h = ((DialogueStartResult.Started) done(env, t.result())).handle();
      f.write.set(new CompletableFuture<>());
      h.updatePreferences(
          new PresentationPreferences("npc", Duration.ZERO, false, Map.of("hideHud", "true")));
      assertTrue(env.tickWhile(() -> f.pendingWrites.get() == 0, Duration.ofSeconds(2)));
      var back =
          h.updatePreferences(new PresentationPreferences("hud", Duration.ZERO, false, Map.of()));
      ticks(env);
      f.write.get().complete(null);
      done(env, back);
      Thread.sleep(375);
      f.packet(new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
      ticks(env);
      assertEquals("reply0", h.snapshot().nodeId());
    }
  }

  @Test
  void nonDefaultChatChoiceRunsItsOwnDestination(Env env) {
    try (var f = new F(env, 2)) {
      var packets = f.connection.trackIncoming(SystemChatPacket.class);
      var t = f.start("chat", false);
      var h = ((DialogueStartResult.Started) done(env, t.result())).handle();
      var cmds = f.commands(packets.collect());
      MinecraftServer.getCommandManager().execute(f.player, cmds.get(1));
      ticks(env);
      assertEquals("reply1", h.snapshot().nodeId());
    }
  }

  @Test
  void sameChatPreferencesKeepWorkingLinks(Env env) {
    try (var f = new F(env, 2)) {
      var packets = f.connection.trackIncoming(SystemChatPacket.class);
      var t = f.start("chat", false);
      var h = ((DialogueStartResult.Started) done(env, t.result())).handle();
      var cmds = f.commands(packets.collect());
      done(
          env,
          h.updatePreferences(
              new PresentationPreferences(
                  "chat", Duration.ZERO, false, Map.of("npcBackdrop", "true"))));
      MinecraftServer.getCommandManager().execute(f.player, cmds.getFirst());
      ticks(env);
      assertEquals("reply0", h.snapshot().nodeId());
      MinecraftServer.getCommandManager().execute(f.player, cmds.get(1));
      ticks(env);
      assertEquals(
          "reply0", h.snapshot().nodeId(), "Links from the previous visit must remain stale");
    }
  }

  @Test
  void packRemovalDuringNpcDrawFallsBackBeforeStarted(Env env) {
    try (var f = new F(env, 2)) {
      f.write.set(new CompletableFuture<>());
      var t = f.start("npc", true);
      assertTrue(env.tickWhile(() -> f.pendingWrites.get() == 0, Duration.ofSeconds(2)));
      EventDispatcher.call(
          new PlayerResourcePackStatusEvent(f.player, f.pack, ResourcePackStatus.DISCARDED));
      f.write.get().complete(null);
      assertInstanceOf(DialogueStartResult.Started.class, done(env, t.result()));
      assertEquals("chat", t.status().effectiveSurface());
    }
  }

  @Test
  void packRemovalDuringHudWriteFallsBackBeforeStarted(Env env) {
    try (var f = new F(env, 2)) {
      f.write.set(new CompletableFuture<>());
      var t = f.start("hud", false);
      assertTrue(env.tickWhile(() -> f.pendingWrites.get() == 0, Duration.ofSeconds(2)));
      EventDispatcher.call(
          new PlayerResourcePackStatusEvent(f.player, f.pack, ResourcePackStatus.DISCARDED));
      f.write.get().complete(null);
      assertInstanceOf(DialogueStartResult.Started.class, done(env, t.result()));
      assertEquals("chat", t.status().effectiveSurface());
    }
  }

  @Test
  void suppressedHudCannotConfirmItsHiddenChoice(Env env) throws Exception {
    try (var f = new F(env, 2)) {
      var t = f.start("hud", false);
      var h = ((DialogueStartResult.Started) done(env, t.result())).handle();
      Thread.sleep(375);
      var other = f.channels.acquireHud(f.player, 1, 100);
      done(env, other.replace(Component.text("Other owner")));
      f.packet(new ClientUseItemPacket(PlayerHand.MAIN, 1, 0, 0));
      ticks(env);
      assertEquals("start", h.snapshot().nodeId());
      assertTrue(
          env.tickWhile(
              () -> !t.status().effectiveSurface().equals("chat"), Duration.ofSeconds(2)));
      done(env, other.closeAsync());
    }
  }

  @Test
  void unknownAndRetiredCatchersCannotConfirm(Env env) throws Exception {
    try (var f = new F(env, 2)) {
      var a = f.start("hud", false);
      done(env, a.result());
      int old =
          f.player.getInstance().getEntities().stream()
              .filter(e -> e.getEntityType() == EntityType.INTERACTION)
              .findFirst()
              .orElseThrow()
              .getEntityId();
      done(env, a.cancel(DialogueEndReason.CANCELLED));
      var b = f.start("hud", false);
      var h = ((DialogueStartResult.Started) done(env, b.result())).handle();
      int fresh =
          f.player.getInstance().getEntities().stream()
              .filter(e -> e.getEntityType() == EntityType.INTERACTION)
              .findFirst()
              .orElseThrow()
              .getEntityId();
      Thread.sleep(375);
      f.packet(new ClientInteractEntityPacket(9999999, PlayerHand.MAIN, Vec.ZERO, false));
      ticks(env);
      assertEquals("start", h.snapshot().nodeId());
      Thread.sleep(375);
      f.packet(new ClientInteractEntityPacket(old, PlayerHand.MAIN, Vec.ZERO, false));
      ticks(env);
      assertEquals("start", h.snapshot().nodeId());
      Thread.sleep(375);
      f.packet(new ClientInteractEntityPacket(fresh, PlayerHand.MAIN, Vec.ZERO, false));
      ticks(env);
      assertEquals("reply0", h.snapshot().nodeId());
    }
  }
}
