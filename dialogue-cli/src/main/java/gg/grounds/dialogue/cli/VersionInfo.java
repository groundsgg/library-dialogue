package gg.grounds.dialogue.cli;

import java.io.*;
import java.util.Properties;

record VersionInfo(String toolVersion, String validatorVersion) {
  static VersionInfo current() {
    var props = new Properties();
    try (var stream = VersionInfo.class.getResourceAsStream("version.properties")) {
      if (stream == null) throw new IllegalStateException("Missing build version");
      props.load(stream);
    } catch (IOException e) {
      throw new IllegalStateException("Unreadable build version", e);
    }
    String version = props.getProperty("version");
    if (version == null || version.isBlank())
      throw new IllegalStateException("Missing build version");
    return new VersionInfo(version, version);
  }
}
