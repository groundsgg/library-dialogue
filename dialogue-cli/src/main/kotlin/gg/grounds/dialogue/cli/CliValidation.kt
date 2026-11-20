package gg.grounds.dialogue.cli

import gg.grounds.dialogue.json.DialogueCatalogLoader

internal object CliValidation {
    @JvmStatic
    fun validate(sources: DialogueCatalogLoader.ContentSources, version: VersionInfo): CliReport {
        val result = DialogueCatalogLoader().reload(sources)
        val valid = result.isValid()
        return CliReport(
            1,
            version.toolVersion,
            version.validatorVersion,
            1,
            if (valid) CliReport.Status.VALID else CliReport.Status.INVALID,
            if (valid) checkNotNull(result.candidate()).contentPin().sha256 else null,
            result.problems(),
        )
    }
}
