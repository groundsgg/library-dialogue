package gg.grounds.dialogue.json;

import static org.junit.jupiter.api.Assertions.*;

import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.text.*;
import gg.grounds.dialogue.core.validation.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReviewFormatRegressionsTest {
  @Test
  void i11NumericNormalizationKeepsStrictLexicalGrammar() {
    for (String token :
        List.of(
            "01",
            "+1",
            "1.",
            ".1",
            "1e",
            "1e+",
            "NaN",
            "Infinity",
            "1true",
            "--1",
            "1e309",
            "1".repeat(441))) {
      assertFalse(
          DialogueJsonCodecTest.decode(
                  DialogueJsonCodecTest.SOURCE.replace(
                      "\"schemaVersion\":1", "\"schemaVersion\":" + token))
              .isValid(),
          token);
    }
  }

  @Test
  void i10ExplicitNextAndEmptyChoicesIsNotNormalizedIntoAValidMessage() {
    assertFalse(
        DialogueJsonCodecTest.decode(
                DialogueJsonCodecTest.SOURCE.replace(
                    "\"next\":\"end\"", "\"next\":\"end\",\"choices\":[]"))
            .isValid());
  }

  @Test
  void i10UnreferencedHostDeclarationsAndOptionalIdsAreValidated() {
    var codec = new DialogueJsonCodec();
    for (String host :
        List.of(
            DialogueJsonCodecTest.HOST.replace("\"v1\"", "\"\""),
            DialogueJsonCodecTest.HOST.replace(
                "\"speakers\":[\"npc\"]", "\"speakers\":[\"npc\",\"bad speaker\"]"),
            DialogueJsonCodecTest.HOST.replace(
                "\"conditions\":{}",
                "\"conditions\":{\"unused\":{\"arguments\":{\"x\":{\"type\":\"TEXT\"}}}}"))) {
      assertFalse(
          codec
              .decode(
                  DialogueJsonCodecTest.bytes(DialogueJsonCodecTest.SOURCE),
                  Map.of("en", DialogueJsonCodecTest.bytes("{\"line\":\"Hi\"}")),
                  DialogueJsonCodecTest.bytes(host))
              .isValid(),
          host);
    }
    for (String optional : List.of("bad capability", "\ud800")) {
      var d =
          DialogueBuilder.dialogue("review:ids")
              .capabilities(new Capabilities(Set.of(), Set.of(optional)))
              .node("start", new Message("npc", "line", null, List.of(), "end"))
              .node("end", new End("completed"))
              .build();
      var report =
          assertDoesNotThrow(
              () ->
                  new DialogueValidator()
                      .validate(
                          d,
                          new TextCatalog(Map.of("en", Map.of("line", "Hi"))),
                          new HostCatalog(
                              "v1", Set.of("npc"), Map.of(), Map.of(), Set.of(), Set.of(), false),
                          ValidationLimits.defaults()));
      assertFalse(report.isValid());
    }
  }

  @Test
  void i10VersionLimitsCountCodepoints() {
    String valid = "😀".repeat(80);
    assertTrue(
        DialogueJsonCodecTest.decode(
                DialogueJsonCodecTest.SOURCE.replace(
                    "\"contentVersion\":\"1.0.0\"", "\"contentVersion\":\"" + valid + "\""))
            .isValid());
    assertFalse(
        DialogueJsonCodecTest.decode(
                DialogueJsonCodecTest.SOURCE.replace(
                    "\"contentVersion\":\"1.0.0\"",
                    "\"contentVersion\":\"" + "😀".repeat(129) + "\""))
            .isValid());
  }

  @Test
  void i11CanonicalNumericSpellingsRoundTripAcrossExponentBounds() {
    var codec = new DialogueJsonCodec();
    String host =
        """
        {"schemaVersion":1,"generation":"v1","speakers":["npc"],"conditions":{},"actions":{"review:number":{"arguments":{"x":{"type":"number","required":true}},"durable":false}},"gestures":[],"capabilities":[],"durableOutcomes":false}
        """;
    var texts = new TextCatalog(Map.of("en", Map.of("line", "Hi")));
    var catalog =
        new HostCatalog(
            "v1",
            Set.of("npc"),
            Map.of(),
            Map.of(
                "review:number",
                new HostCatalog.ProviderSpec(
                    Map.of("x", new HostCatalog.ArgumentSpec(HostCatalog.Kind.NUMBER, true, false)),
                    false)),
            Set.of(),
            Set.of(),
            false);
    for (String number : List.of("1e128", "1e308", "1e-308", "1.2300e308", "1.2300")) {
      var d =
          DialogueBuilder.dialogue("review:numbers")
              .node(
                  "start",
                  new Action(
                      "review:number",
                      Map.of("x", new ArgumentValue.Number(new BigDecimal(number))),
                      "end",
                      "end",
                      "end",
                      "end"))
              .node("end", new End("completed"))
              .build();
      var v = new DialogueValidator().validate(d, texts, catalog, ValidationLimits.defaults());
      assertTrue(v.isValid(), number);
      var decoded =
          codec.decode(
              codec.encode(d),
              Map.of("en", DialogueJsonCodecTest.bytes("{\"line\":\"Hi\"}")),
              DialogueJsonCodecTest.bytes(host));
      assertTrue(decoded.isValid(), number + " " + decoded.problems());
      assertEquals(v.validated().contentPin(), decoded.validated().contentPin());
    }
  }
}
