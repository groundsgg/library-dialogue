package gg.grounds.dialogue.core.text

import gg.grounds.dialogue.core.definition.ArgumentValue
import java.util.concurrent.CompletionStage

fun interface TextResolver {
    @JvmSuppressWildcards
    class TextRequest(
        private val key: String?,
        arguments: Map<String, ArgumentValue>,
        private val locale: LocaleContext?,
    ) {
        private val arguments = java.util.Map.copyOf(arguments)

        fun key(): String? = key

        fun arguments(): Map<String, ArgumentValue> = arguments

        fun locale(): LocaleContext? = locale

        override fun equals(other: Any?): Boolean =
            other is TextRequest &&
                key == other.key &&
                arguments == other.arguments &&
                locale == other.locale

        override fun hashCode(): Int {
            var hash = (key?.hashCode() ?: 0)
            hash = 31 * hash + arguments.hashCode()
            hash = 31 * hash + (locale?.hashCode() ?: 0)
            return hash
        }

        override fun toString(): String =
            "TextRequest[key=$key, arguments=$arguments, locale=$locale]"
    }

    fun resolve(request: TextRequest): CompletionStage<ResolvedText>

    companion object {
        @JvmStatic
        fun catalogue(catalog: TextCatalog, defaultTag: String): TextResolver =
            CatalogueTextResolver(catalog, defaultTag)
    }
}
