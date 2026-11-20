package gg.grounds.dialogue.core.text

import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.validation.DialogueValidator
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.regex.Pattern
import kotlin.math.abs

/** Bounded single-pass plain-text substitution with explicit per-key locale fallback. */
internal class CatalogueTextResolver(
    private val catalog: TextCatalog,
    private val defaultTag: String,
) : TextResolver {
    override fun resolve(request: TextResolver.TextRequest): CompletionStage<ResolvedText> =
        try {
            val locale = request.locale() ?: throw NullPointerException("locale")
            val chain =
                linkedSetOf(
                    locale.tag(),
                    Locale.forLanguageTag(locale.tag()).language,
                    locale.fallbackTag(),
                    defaultTag,
                )
            val (tag, value) =
                chain.firstNotNullOfOrNull { candidate ->
                    catalog.locales()[candidate]?.get(request.key())?.let { candidate to it }
                } ?: throw IllegalArgumentException("TEXT_MISSING: " + request.key())
            require(value.length <= LIMIT * 2 && value.codePointCount(0, value.length) <= LIMIT) {
                "TEXT_OUTPUT_LIMIT"
            }

            val out = StringBuilder(minOf(value.length, LIMIT))
            val matcher = TOKEN.matcher(value)
            var end = 0
            var remaining = LIMIT
            while (matcher.find()) {
                remaining = append(out, value.substring(end, matcher.start()), remaining)
                val replacement =
                    when (matcher.group()) {
                        "{{" -> "{"
                        "}}" -> "}"
                        else -> {
                            val key = matcher.group(1)
                            val argument =
                                request.arguments()[key]
                                    ?: throw IllegalArgumentException(
                                        "TEXT_ARGUMENT_MISSING: " + key
                                    )
                            replacement(argument)
                        }
                    }
                remaining = append(out, replacement, remaining)
                end = matcher.end()
            }
            append(out, value.substring(end), remaining)
            CompletableFuture.completedFuture(ResolvedText(out.toString(), tag))
        } catch (failure: RuntimeException) {
            CompletableFuture.failedFuture(failure)
        }

    private fun replacement(argument: ArgumentValue): String =
        when (argument) {
            is ArgumentValue.Text -> argument.value
            is ArgumentValue.Flag -> argument.value.toString()
            ArgumentValue.Nil.INSTANCE -> ""
            is ArgumentValue.Number -> {
                val number = argument.value().stripTrailingZeros()
                require(
                    number.precision() <= 128 &&
                        abs(number.precision().toLong() - number.scale() - 1) <= 308
                ) {
                    "NUMBER_LIMIT"
                }
                number.toPlainString()
            }
        }

    private fun append(out: StringBuilder, value: String, remaining: Int): Int {
        require(value.length <= remaining * 2) { "TEXT_OUTPUT_LIMIT" }
        val size = value.codePointCount(0, value.length)
        require(size <= remaining) { "TEXT_OUTPUT_LIMIT" }
        require(DialogueValidator.validUnicode(value)) { "UNICODE_INVALID" }
        out.append(value)
        return remaining - size
    }

    private companion object {
        const val LIMIT = 16_384
        val TOKEN: Pattern = Pattern.compile("\\{\\{|\\}\\}|\\{([a-zA-Z0-9_.-]+)\\}")
    }
}
