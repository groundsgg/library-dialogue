package gg.grounds.dialogue.cli;

import gg.grounds.dialogue.json.DialogueCatalogLoader;

final class CliValidation {
  static CliReport validate(DialogueCatalogLoader.ContentSources sources, VersionInfo version) {
    var result = new DialogueCatalogLoader().reload(sources);
    return new CliReport(
        1,
        version.toolVersion(),
        version.validatorVersion(),
        1,
        result.isValid() ? CliReport.Status.VALID : CliReport.Status.INVALID,
        result.isValid() ? result.candidate().contentPin().sha256() : null,
        result.problems());
  }
}
