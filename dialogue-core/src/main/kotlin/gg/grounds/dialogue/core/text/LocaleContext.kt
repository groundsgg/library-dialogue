package gg.grounds.dialogue.core.text

import java.util.Locale

class LocaleContext(
    tag: String,
    private val revision: Long,
    fallbackTag: String,
) {
    private val tag = normalize(tag)
    private val fallbackTag = normalize(fallbackTag)

    init {
        require(revision >= 0) { "Negative locale revision" }
    }

    fun tag(): String = tag

    fun revision(): Long = revision

    fun fallbackTag(): String = fallbackTag

    override fun equals(other: Any?): Boolean =
        other is LocaleContext &&
            tag == other.tag &&
            revision == other.revision &&
            fallbackTag == other.fallbackTag

    override fun hashCode(): Int {
        var hash = tag.hashCode()
        hash = 31 * hash + revision.hashCode()
        hash = 31 * hash + fallbackTag.hashCode()
        return hash
    }

    override fun toString(): String =
        "LocaleContext[tag=$tag, revision=$revision, fallbackTag=$fallbackTag]"

    private companion object {
        fun normalize(tag: String): String {
            val locale = Locale.Builder().setLanguageTag(tag).build()
            require(locale.language.isNotEmpty()) { "Empty locale" }
            return locale.toLanguageTag()
        }
    }
}
