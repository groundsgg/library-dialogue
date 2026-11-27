package example;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.minestom.*;

/** Compiles the unchanged Java constructors and all original JVM accessor names. */
final class LegacyRequestConsumer {
  static void verify(MinestomDialogueRequest source) {
    var defaults = new MinestomDialogueRequest(
        source.requestId(), source.player(), source.hostGeneration(), source.content(),
        source.services(), source.context(), source.locale(), source.preferences(),
        source.speakers(), source.actors(), source.controls(), source.openingImpulse());
    var listener = new MinestomDialogueSpeechListener() {
      public void onRevealed(MinestomDialogueSpeechEvent event) {}
      public void onStop(MinestomDialogueSpeechListener.StopReason reason) {}
    };
    var explicit = new MinestomDialogueRequest(
        source.requestId(), source.player(), source.hostGeneration(), source.content(),
        source.services(), source.context(), source.locale(), source.preferences(),
        source.speakers(), source.actors(), source.controls(), source.openingImpulse(), listener);
    assertSame(MinestomDialogueSpeechListener.NO_OP, defaults.speechListener());
    assertSame(listener, explicit.speechListener());
    assertEquals(source, defaults);
    assertEquals(defaults, explicit);
    assertEquals(defaults.hashCode(), explicit.hashCode());
    assertEquals(defaults.toString(), explicit.toString());
    assertSame(source.player(), defaults.player());
    assertSame(source.content(), defaults.content());
    assertSame(source.services(), defaults.services());
    assertSame(source.locale(), defaults.locale());
    assertSame(source.preferences(), defaults.preferences());
    assertSame(source.controls(), defaults.controls());
  }
}
