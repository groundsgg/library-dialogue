package gg.grounds.dialogue.minestom.asset;

/** Exact supported wire/pack combinations; the application supplies its corresponding runtime. */
public record MinecraftPackProfile(
    String id, String minecraft, int protocol, int packMajor, int packMinor, boolean postEffects) {
  public MinecraftPackProfile {
    if (!((id.equals("public-26.2")
            && minecraft.equals("26.2")
            && protocol == 776
            && packMajor == 88
            && packMinor == 0
            && !postEffects)
        || (id.equals("grounds-26.3")
            && minecraft.equals("26.3")
            && protocol == 777
            && packMajor == 97
            && packMinor == 1
            && postEffects))) throw new IllegalArgumentException("UNSUPPORTED_PROFILE");
  }

  public static MinecraftPackProfile public262() {
    return new MinecraftPackProfile("public-26.2", "26.2", 776, 88, 0, false);
  }

  public static MinecraftPackProfile grounds263() {
    return new MinecraftPackProfile("grounds-26.3", "26.3", 777, 97, 1, true);
  }
}
