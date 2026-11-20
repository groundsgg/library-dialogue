package gg.grounds.dialogue.core.text

import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.core.validation.ContentPin
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity
import java.util.Collections
import java.util.Locale

/** Immutable translations, with a single bounded validation memo for shared catalogues. */
@JvmSuppressWildcards
class TextCatalog(source: Map<String, Map<String, String>>) {
    private val locales: Map<String, Map<String, String>> =
        Collections.unmodifiableMap(
            source.mapValues { (_, translations) -> java.util.Map.copyOf(translations) }
        )

    @Volatile private var cached: Cached? = null
    @Volatile private var fingerprint: ContentPin? = null

    private data class Cached(val limits: ValidationLimits, val problems: List<Problem>)

    fun locales(): Map<String, Map<String, String>> = locales

    fun contentPin(): ContentPin =
        fingerprint
            ?: ContentFingerprint.hash(ContentFingerprint.canonicalBytes(locales)).also {
                fingerprint = it
            }

    fun structuralProblems(limits: ValidationLimits): List<Problem> {
        cached
            ?.takeIf { it.limits == limits }
            ?.let {
                return it.problems
            }
        val problems = ArrayList<Problem>()
        fun problem(code: String, file: String, path: String, message: String) {
            problems.add(Problem(code, Severity.ERROR, file, path, null, message))
        }

        var bytes = 0L
        if (locales.size > limits.locales)
            problem("LOCALE_LIMIT", "texts", "/", "Too many text files")
        for ((tag, translations) in locales) {
            val file = "texts/" + tag + ".json"
            var safe =
                try {
                    val locale = Locale.Builder().setLanguageTag(tag).build()
                    locale.language.isNotEmpty() && locale.toLanguageTag() == tag
                } catch (_: RuntimeException) {
                    false
                }
            if (!safe) problem("LOCALE_INVALID", file, "/", "Invalid text locale")
            for ((key, value) in translations) {
                if (
                    key.isBlank() ||
                        key.codePointCount(0, key.length) > limits.idCodepoints ||
                        !TEXT_KEY.matches(key)
                ) {
                    problem("TEXT_KEY_INVALID", file, "/", "Invalid text key")
                    safe = false
                }
                if (
                    !DialogueValidator.validUnicode(value) ||
                        value.codePointCount(0, value.length) > limits.textCodepoints
                ) {
                    problem("TEXT_LIMIT", file, "/" + key, "Invalid or oversized translation")
                    safe = false
                }
            }
            if (safe) {
                val size = ContentFingerprint.canonicalBytes(translations).size
                bytes += size
                if (size > limits.textFileBytes) {
                    problem("TEXT_FILE_LIMIT", file, "/", "Canonical text file exceeds budget")
                }
            }
        }
        if (bytes > limits.textPackageBytes)
            problem("TEXT_PACKAGE_LIMIT", "texts", "/", "Text package exceeds budget")
        return java.util.List.copyOf(problems).also { cached = Cached(limits, it) }
    }

    override fun equals(other: Any?): Boolean = other is TextCatalog && locales == other.locales

    override fun hashCode(): Int = locales.hashCode()

    private companion object {
        val TEXT_KEY = Regex("[a-zA-Z0-9_.:/-]+")
    }
}
