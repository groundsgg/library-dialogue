package gg.grounds.dialogue.core.text;

import java.util.*;
import java.util.regex.Pattern;

public final class Graphemes {
  private static final Pattern CLUSTER = Pattern.compile("\\X");

  private Graphemes() {}

  public static List<String> split(String text) {
    return CLUSTER.matcher(text).results().map(r -> r.group()).toList();
  }

  public static String reveal(String text, int count) {
    if (count < 0) throw new IllegalArgumentException("Negative reveal");
    var clusters = split(text);
    return String.join("", clusters.subList(0, Math.min(count, clusters.size())));
  }
}
