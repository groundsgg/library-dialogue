package gg.grounds.dialogue.minestom.input;

import gg.grounds.dialogue.core.port.OwnedResource;
import gg.grounds.dialogue.minestom.internal.ConnectionGuard;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minestom.server.coordinate.*;
import net.minestom.server.entity.*;
import net.minestom.server.entity.metadata.other.InteractionMeta;

/** One invisible viewer-only interaction volume. Does not replace either hand's item. */
public final class PrivateInteractionCatcher implements OwnedResource {
  private final Entity entity = new Entity(EntityType.INTERACTION);
  private final Player player;
  private final ConnectionGuard guard;
  private final AtomicBoolean closed = new AtomicBoolean();

  private PrivateInteractionCatcher(Player player, ConnectionGuard guard) {
    this.player = player;
    this.guard = guard;
    entity.setAutoViewable(false);
    entity.setNoGravity(true);
    entity.setHasPhysics(false);
    var meta = (InteractionMeta) entity.getEntityMeta();
    meta.setWidth(1.6f);
    meta.setHeight(2);
    meta.setResponse(false);
  }

  public static CompletionStage<PrivateInteractionCatcher> open(
      Player player, ConnectionGuard guard) {
    var catcher = new PrivateInteractionCatcher(player, guard);
    var ready = new CompletableFuture<PrivateInteractionCatcher>();
    try {
      catcher
          .entity
          .setInstance(player.getInstance(), catcher.position())
          .whenComplete(
              (v, error) -> {
                if (error == null) {
                  if (guard.isCurrent()) ready.complete(catcher);
                  else
                    catcher
                        .closeAsync()
                        .whenComplete(
                            (u, e) -> ready.completeExceptionally(new CancellationException()));
                } else
                  catcher.closeAsync().whenComplete((u, e) -> ready.completeExceptionally(error));
              });
    } catch (Throwable error) {
      catcher.closeAsync();
      ready.completeExceptionally(error);
    }
    return ready.minimalCompletionStage();
  }

  private Pos position() {
    var eye = player.getPosition().add(0, player.getEyeHeight(), 0);
    return eye.add(player.getPosition().direction().mul(.8)).sub(0, 1, 0).withView(0, 0);
  }

  public void publish() {
    if (!closed.get() && guard.isCurrent()) entity.addViewer(player);
  }

  public void follow() {
    if (!closed.get() && guard.isCurrent()) {
      var p = position();
      if (!p.equals(entity.getPosition())) entity.teleport(p);
    }
  }

  public int entityId() {
    return entity.getEntityId();
  }

  public CompletionStage<Void> closeAsync() {
    if (closed.compareAndSet(false, true)) {
      entity.removeViewer(player);
      entity.remove();
    }
    return CompletableFuture.completedFuture(null);
  }
}
