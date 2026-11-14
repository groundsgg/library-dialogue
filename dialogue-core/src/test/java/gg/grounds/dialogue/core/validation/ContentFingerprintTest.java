package gg.grounds.dialogue.core.validation;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
class ContentFingerprintTest {
 @Test void canonicalHashVectors() {
  byte[] a=ContentFingerprint.canonicalBytes(Map.of("b","é","a",new BigDecimal("1.00")));
  assertEquals("{\"a\":1,\"b\":\"é\"}",new String(a,StandardCharsets.UTF_8));
  assertArrayEquals(a,ContentFingerprint.canonicalBytes(new LinkedHashMap<>(Map.of("a",1,"b","é"))));
  assertFalse(Arrays.equals(a,ContentFingerprint.canonicalBytes(Map.of("a",1,"b","e\u0301"))));
  assertEquals("0",new String(ContentFingerprint.canonicalBytes(new BigDecimal("-0.000")),StandardCharsets.UTF_8));
 }
}
