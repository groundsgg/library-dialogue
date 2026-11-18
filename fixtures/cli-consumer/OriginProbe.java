import java.util.List;

public final class OriginProbe {
  public static void main(String[] args) throws Exception {
    for (String name :
        List.of(
            "gg.grounds.dialogue.core.runtime.DialogueRuntime",
            "gg.grounds.dialogue.json.DialogueCatalogLoader",
            "gg.grounds.dialogue.cli.DialogueCli")) {
      Class<?> type = Class.forName(name);
      var origin = type.getProtectionDomain().getCodeSource().getLocation();
      if (!origin.getPath().endsWith(".jar")) throw new AssertionError("Expected JAR origin");
      System.out.println("MAVEN_ORIGIN " + name + " " + origin.toURI().toASCIIString());
    }
  }
}
