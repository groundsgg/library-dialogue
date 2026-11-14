package gg.grounds.dialogue.core.text;
import java.util.*;
public record LocaleContext(String tag,long revision,String fallbackTag) {
 public LocaleContext {tag=normalize(tag);fallbackTag=normalize(fallbackTag);if(revision<0)throw new IllegalArgumentException("Negative locale revision");}
 private static String normalize(String s){var l=new Locale.Builder().setLanguageTag(Objects.requireNonNull(s)).build();if(l.getLanguage().isEmpty())throw new IllegalArgumentException("Empty locale");return l.toLanguageTag();}
}
