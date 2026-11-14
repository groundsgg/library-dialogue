package gg.grounds.dialogue.core.text;
import java.util.Objects;
import gg.grounds.dialogue.core.validation.DialogueValidator;
public record ResolvedText(String plain,String localeTag) {public ResolvedText {Objects.requireNonNull(plain);Objects.requireNonNull(localeTag);if(!DialogueValidator.validUnicode(plain)||plain.codePointCount(0,plain.length())>16384)throw new IllegalArgumentException("Invalid resolved text");}}
