package gg.grounds.dialogue.minestom.input;

/** A client slot history is reset only by this connection's outstanding fence. */
public final class SlotCursor {
  private final long generation;
  private int slot;
  private Integer pending;

  public SlotCursor(int slot, long generation) {
    validate(slot);
    this.slot = slot;
    this.generation = generation;
  }

  public synchronized int scroll(int next) {
    validate(next);
    int delta = Math.floorMod(next - slot, 9);
    slot = next;
    return delta == 0 ? 0 : delta <= 4 ? 1 : -1;
  }

  public synchronized int slot() {
    return slot;
  }

  public synchronized void fence(int id) {
    pending = id;
  }

  public synchronized boolean awaiting() {
    return pending != null;
  }

  public synchronized boolean matches(int id) {
    return pending != null && pending == id;
  }

  public synchronized boolean pong(int id, long generation, int anchor) {
    validate(anchor);
    if (this.generation != generation || !matches(id)) return false;
    slot = anchor;
    pending = null;
    return true;
  }

  private static void validate(int slot) {
    if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot");
  }
}
