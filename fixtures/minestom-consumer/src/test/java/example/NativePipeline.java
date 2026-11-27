package example;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.DialogueBuilder;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.timer.TaskSchedule;
import net.minestom.testing.Env;

/** An application-owned native pipeline, compiled solely against the published Maven artifacts. */
public final class NativePipeline {
  public static RuntimeOptions.Budgets budgets() {
    return new RuntimeOptions.Budgets(
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(5));
  }

  public static final class Wiring {
    public final MinestomConnectionScope scope;
    public final SurfaceInputTargets targets;
    public final ChatInputTokens tokens;
    public final MinestomDialogueRenderer renderer;
    public final BiFunction<CancellationToken, MinestomInputAdapter.Target, MinestomInputAdapter>
        adapter;
    public final Function<MinestomConnectionScope, CompletionStage<PrivateInteractionCatcher>>
        catcher;
    public final CompletableFuture<Void> acknowledgement;
    public final List<Component> output;

    public Wiring(
        MinestomConnectionScope scope,
        SurfaceInputTargets targets,
        ChatInputTokens tokens,
        MinestomDialogueRenderer renderer,
        BiFunction<CancellationToken, MinestomInputAdapter.Target, MinestomInputAdapter> adapter,
        Function<MinestomConnectionScope, CompletionStage<PrivateInteractionCatcher>> catcher,
        CompletableFuture<Void> acknowledgement,
        List<Component> output) {
      this.scope = scope;
      this.targets = targets;
      this.tokens = tokens;
      this.renderer = renderer;
      this.adapter = adapter;
      this.catcher = catcher;
      this.acknowledgement = acknowledgement;
      this.output = output;
    }
  }

  public static Wiring javaWiring(Player player, DialogueScheduler clock) {
    var scope = new MinestomConnectionScope(player, 7, player.getInstance());
    var targets = new SurfaceInputTargets();
    var tokens = new ChatInputTokens(scope, targets, clock::nanoTime);
    var acknowledgement = new CompletableFuture<Void>();
    var output = new ArrayList<Component>();
    var labels = UiLabelSource.defaults();
    var controls = DialoguePresenter.Controls.defaults();
    var chat =
        new ChatDialogueSurface(
            scope,
            targets,
            tokens,
            component -> {
              output.add(component);
              return acknowledgement;
            },
            labels,
            controls,
            "/native");
    var assets = AssetBundle.loadDefault(MinecraftPackProfile.public262());
    var channels =
        new LocalOutputChannels(
            (p, c) -> CompletableFuture.completedFuture(null),
            false,
            (p, keys) -> CompletableFuture.completedFuture(null));
    var packs = new PackStateTracker();
    var hud = new HudDialogueSurface(scope, packs, assets, channels, targets, clock, ignored -> {});
    var npc =
        new NpcDialogueSurface(
            scope, packs, assets, channels, targets, clock, view -> null, ignored -> {});
    var profiles =
        Map.of(
            "chat",
            DialoguePresenter.SurfaceProfile.semantic(chat),
            "hud",
            DialoguePresenter.SurfaceProfile.semantic(hud),
            "npc",
            DialoguePresenter.SurfaceProfile.semantic(npc));
    var shortRenderer =
        new MinestomDialogueRenderer(
            scope, profiles, clock, controls, labels, targets, ignored -> {});
    assertTrue(shortRenderer.resolveChat(player, 7, "unused").isEmpty());
    var renderer =
        new MinestomDialogueRenderer(
            scope,
            profiles,
            clock,
            controls,
            labels,
            targets,
            ignored -> {},
            tokens,
            npc::geometry);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatDialogueSurface(
                new MinestomConnectionScope(player, 7, player.getInstance()),
                targets,
                tokens,
                ignored -> CompletableFuture.completedFuture(null),
                labels,
                controls,
                "/native"));
    return new Wiring(
        scope,
        targets,
        tokens,
        renderer,
        (cancellation, target) -> {
          var shortAdapter =
              new MinestomInputAdapter(
                  scope, InputProfile.defaults(), clock, cancellation, target, (from, to) -> true);
          shortAdapter.closeAsync();
          return new MinestomInputAdapter(
              scope,
              InputProfile.defaults(),
              clock,
              cancellation,
              target,
              (from, to) -> true,
              budgets());
        },
        PrivateInteractionCatcher::open,
        acknowledgement,
        output);
  }

  public static <T> T await(Env env, CompletionStage<T> stage) {
    var future = stage.toCompletableFuture();
    assertTrue(
        env.tickWhile(() -> !future.isDone(), Duration.ofSeconds(5)), "Native operation timed out");
    return future.join();
  }

  public static void exercise(Env env, BiFunction<Player, DialogueScheduler, Wiring> factory) {
    var instance = env.createFlatInstance();
    var player = env.createConnection().connect(instance, new Pos(0, 42, 0));
    DialogueScheduler clock =
        new DialogueScheduler() {
          public void execute(Runnable command) {
            instance.scheduler().scheduleNextProcess(command);
          }

          public ScheduledTask schedule(Duration delay, Runnable command) {
            var task =
                instance
                    .scheduler()
                    .buildTask(command)
                    .delay(TaskSchedule.duration(delay))
                    .schedule();
            return task::cancel;
          }

          public long nanoTime() {
            return System.nanoTime();
          }
        };
    var wiring = factory.apply(player, clock);
    DialogueInputSource source = wiring.renderer;
    var catcher = await(env, wiring.catcher.apply(wiring.scope));
    catcher.publish();
    int catcherId = catcher.entityId();
    assertTrue(
        instance.getEntities().stream().anyMatch(entity -> entity.getEntityId() == catcherId));
    var texts =
        new TextCatalog(Map.of("en", Map.of("line", "Choose a route", "answer", "Continue")));
    var definition =
        DialogueBuilder.dialogue("consumer:custom-native")
            .node(
                "start",
                new Message(
                    "guide",
                    "line",
                    null,
                    List.of(new Choice("answer", "answer", "end", true, null)),
                    null))
            .node("end", new End("completed"))
            .build();
    var content =
        new DialogueValidator()
            .validate(
                definition,
                texts,
                new HostCatalog(
                    "public", Set.of("guide"), Map.of(), Map.of(), Set.of(), Set.of(), false),
                ValidationLimits.defaults())
            .validated();
    assertNotNull(content);
    var cancellation = new CancellationToken();
    var core = DialogueRuntime.create(new RuntimeOptions(clock, budgets(), 65536, 256));
    var pending =
        core.start(
            new DialogueStart(
                "native",
                player.getUuid().toString(),
                7,
                content,
                new HostBindings(
                    "public",
                    Map.of(),
                    Map.of(),
                    null,
                    TextResolver.catalogue(texts, "en"),
                    wiring.renderer,
                    null,
                    Set.of()),
                Map.of(),
                new LocaleContext("en", 0, "en"),
                new PresentationPreferences("chat", Duration.ZERO, false, Map.of()),
                cancellation,
                0));
    assertTrue(env.tickWhile(() -> wiring.output.isEmpty(), Duration.ofSeconds(5)));
    assertFalse(pending.toCompletableFuture().isDone(), "Output ACK gates Core admission");
    assertNull(wiring.targets.current(), "Targets are published after the output ACK");
    wiring.acknowledgement.complete(null);
    var started = assertInstanceOf(DialogueStartResult.Started.class, await(env, pending));
    var handle = started.handle();
    var impulses = new AtomicLong();
    MinestomInputAdapter.Target target =
        new MinestomInputAdapter.Target() {
          public DialogueView view() {
            return handle.snapshot();
          }

          public TargetSnapshot targets() {
            return source.targets();
          }

          public NpcDialogueSurface.Geometry npcGeometry() {
            return source.npcGeometry();
          }

          public String effectiveSurface() {
            return source.effectiveSurface();
          }

          public boolean hiddenHotbar() {
            return source.hiddenHotbar();
          }

          public long nextImpulse() {
            return impulses.incrementAndGet();
          }

          public CompletionStage<InputResult> submit(DialogueInput input) {
            return handle.submit(input);
          }
        };
    var adapter = wiring.adapter.apply(cancellation, target);
    await(env, adapter.activate());
    assertEquals("chat", source.effectiveSurface());
    var snapshot = source.targets();
    assertNotNull(snapshot);
    assertTrue(snapshot.choices().containsKey("answer"));
    var json = GsonComponentSerializer.gson().serialize(wiring.output.getFirst());
    var match = Pattern.compile("/native ([A-Za-z0-9_-]{22})").matcher(json);
    assertTrue(match.find(), "Real chat output must carry a server-issued capability");
    var token = match.group(1);
    assertTrue(source.resolveChat(player, 8, token).isEmpty());
    var foreign = env.createConnection().connect(instance, new Pos(1, 42, 0));
    assertTrue(source.resolveChat(foreign, 7, token).isEmpty());
    var offered = source.resolveChat(player, 7, token).orElseThrow();
    assertEquals("answer", offered.choiceId());
    assertEquals(handle.snapshot().sessionId(), offered.sessionId());
    var wrong =
        wiring.renderer.prepare(
            new DialogueRenderer.PresentationRequest(
                UUID.randomUUID(),
                foreign.getUuid().toString(),
                7,
                new PresentationPreferences("chat", Duration.ZERO, false, Map.of()),
                new CancellationToken(),
                Long.MAX_VALUE,
                budgets()));
    assertTrue(wrong.toCompletableFuture().isCompletedExceptionally());
    assertSame(snapshot, source.targets());
    var stamp =
        new DialogueInput.InputStamp(
            offered.sessionId(), offered.renderRevision(), target.nextImpulse());
    assertEquals(
        InputResult.ACCEPTED,
        await(env, target.submit(new DialogueInput.Confirm(stamp, offered.choiceId()))));
    await(env, handle.completion());
    assertTrue(source.resolveChat(player, 7, token).isEmpty());
    assertNull(source.targets());
    assertEquals(
        InputResult.ENDED,
        await(env, target.submit(new DialogueInput.Confirm(stamp, offered.choiceId()))));
    await(env, adapter.closeAsync());
    wiring.scope.invalidate();
    await(env, catcher.closeAsync());
    assertFalse(
        instance.getEntities().stream().anyMatch(entity -> entity.getEntityId() == catcherId));
    assertTrue(wiring.scope.hasCurrentConnection());
    assertFalse(wiring.scope.isCurrent());
    await(env, core.closeAsync());
  }
}
