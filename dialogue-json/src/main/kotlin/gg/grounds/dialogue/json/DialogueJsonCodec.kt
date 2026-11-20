package gg.grounds.dialogue.json

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import gg.grounds.dialogue.core.definition.ArgumentValue
import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.definition.DialogueDefinition.Action
import gg.grounds.dialogue.core.definition.DialogueDefinition.Branch
import gg.grounds.dialogue.core.definition.DialogueDefinition.Capabilities
import gg.grounds.dialogue.core.definition.DialogueDefinition.Case
import gg.grounds.dialogue.core.definition.DialogueDefinition.Choice
import gg.grounds.dialogue.core.definition.DialogueDefinition.Condition
import gg.grounds.dialogue.core.definition.DialogueDefinition.Delivery
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.definition.DialogueDefinition.Interaction
import gg.grounds.dialogue.core.definition.DialogueDefinition.LookAway
import gg.grounds.dialogue.core.definition.DialogueDefinition.Message
import gg.grounds.dialogue.core.definition.DialogueDefinition.Node
import gg.grounds.dialogue.core.definition.DialogueDefinition.Outcome
import gg.grounds.dialogue.core.definition.DialogueDefinition.Presentation
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** Bounded strict codec. Serializer classes never escape this module. */
class DialogueJsonCodec
@JvmOverloads
constructor(private val limits: ValidationLimits = ValidationLimits.defaults()) {
    class DecodeResult(problems: List<Problem>, private val validated: ValidatedDialogue?) {
        private val problems = java.util.List.copyOf(problems)

        fun problems(): List<Problem> = problems

        fun validated(): ValidatedDialogue? = validated

        fun isValid(): Boolean = validated != null

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is DecodeResult &&
                    problems == other.problems &&
                    validated == other.validated)

        override fun hashCode(): Int = 31 * problems.hashCode() + (validated?.hashCode() ?: 0)

        override fun toString(): String = "DecodeResult[problems=$problems, validated=$validated]"
    }

    class PreparedContext
    private constructor(
        private val texts: TextCatalog,
        private val catalog: HostCatalog,
        internal val limits: ValidationLimits,
    ) {
        fun texts(): TextCatalog = texts

        fun catalog(): HostCatalog = catalog

        companion object {
            internal fun create(
                texts: TextCatalog,
                catalog: HostCatalog,
                limits: ValidationLimits,
            ): PreparedContext = PreparedContext(texts, catalog, limits)
        }
    }

    class ContextResult(problems: List<Problem>, private val context: PreparedContext?) {
        private val problems = java.util.List.copyOf(problems)

        fun problems(): List<Problem> = problems

        fun context(): PreparedContext? = context

        fun isValid(): Boolean = context != null

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is ContextResult && problems == other.problems && context == other.context)

        override fun hashCode(): Int = 31 * problems.hashCode() + (context?.hashCode() ?: 0)

        override fun toString(): String = "ContextResult[problems=$problems, context=$context]"
    }

    fun prepareContext(texts: Map<String, ByteArray>, hostCatalog: ByteArray): ContextResult {
        var file = "texts"
        try {
            var total = 0L
            val bundles = LinkedHashMap<String, Map<String, String>>()
            if (texts.size > limits.locales) {
                throw fail("LOCALE_LIMIT", "texts", "/", "Too many text files")
            }
            texts.forEach { (locale, bytes) ->
                file = "texts/$locale.json"
                total += bytes.size
                if (total > limits.textPackageBytes) {
                    throw fail("TEXT_PACKAGE_LIMIT", "texts", "/", "Text package exceeds budget")
                }
                val raw = obj(parse(bytes, limits.textFileBytes, file))
                val map = LinkedHashMap<String, String>()
                raw.entrySet().forEach { (key, value) -> map[key] = stringValue(value) }
                bundles[locale] = map
            }
            val catalogue = TextCatalog(bundles)
            val problems = catalogue.structuralProblems(limits)
            if (problems.isNotEmpty()) return ContextResult(problems, null)
            file = "context/host-catalog.json"
            val host = host(obj(parse(hostCatalog, limits.textPackageBytes, file)))
            val hostProblems = host.structuralProblems(limits)
            if (hostProblems.isNotEmpty()) return ContextResult(hostProblems, null)
            return ContextResult(emptyList(), PreparedContext.create(catalogue, host, limits))
        } catch (failure: DecodeFailure) {
            return ContextResult(
                listOf(
                    Problem(
                        failure.code,
                        Severity.ERROR,
                        if (failure.file == "dialogue.json") file else failure.file,
                        failure.path,
                        null,
                        checkNotNull(failure.message),
                    )
                ),
                null,
            )
        } catch (failure: RuntimeException) {
            return ContextResult(
                listOf(
                    Problem(
                        "CONTEXT_INVALID",
                        Severity.ERROR,
                        file,
                        "/",
                        null,
                        failure.message ?: "Invalid author context",
                    )
                ),
                null,
            )
        }
    }

    fun decode(
        definition: ByteArray,
        texts: Map<String, ByteArray>,
        hostCatalog: ByteArray,
    ): DecodeResult {
        val context = prepareContext(texts, hostCatalog)
        return if (context.isValid()) {
            decodeDefinition(definition, context.context()!!)
        } else {
            DecodeResult(context.problems(), null)
        }
    }

    fun decodeDefinition(definition: ByteArray, context: PreparedContext): DecodeResult {
        try {
            if (limits != context.limits) {
                throw fail(
                    "CONTEXT_POLICY_MISMATCH",
                    "context",
                    "/",
                    "Prepared context uses different validation limits",
                )
            }
            val dialogue =
                definition(obj(parse(definition, limits.definitionBytes, "dialogue.json")))
            val result =
                DialogueValidator().validate(dialogue, context.texts(), context.catalog(), limits)
            return DecodeResult(result.problems(), result.validated())
        } catch (failure: DecodeFailure) {
            val node =
                if (failure.path.startsWith("/nodes/")) {
                    failure.path
                        .substring(7)
                        .split("/", limit = 2)[0]
                        .replace("~1", "/")
                        .replace("~0", "~")
                } else {
                    null
                }
            return DecodeResult(
                listOf(
                    Problem(
                        failure.code,
                        Severity.ERROR,
                        failure.file,
                        failure.path,
                        node,
                        checkNotNull(failure.message),
                    )
                ),
                null,
            )
        } catch (failure: RuntimeException) {
            return DecodeResult(
                listOf(
                    Problem(
                        "FORMAT_INVALID",
                        Severity.ERROR,
                        "dialogue.json",
                        "/",
                        null,
                        failure.message ?: "Invalid dialogue",
                    )
                ),
                null,
            )
        }
    }

    fun encode(definition: DialogueDefinition): ByteArray =
        ContentFingerprint.definitionBytes(definition)

    private fun parse(bytes: ByteArray, max: Int, file: String): JsonElement {
        if (bytes.size > max) throw fail("CONTENT_LIMIT", file, "/", "Input byte limit exceeded")
        try {
            val source =
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            if (source.startsWith('\ufeff')) {
                throw fail("UTF8_BOM", file, "/", "UTF-8 BOM is not accepted")
            }
            val reader = JsonReader(StringReader(normalizeNumbers(source, file)))
            reader.strictness = Strictness.STRICT
            val result = read(reader, 0, file)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw fail("TRAILING_DATA", file, reader.path, "Trailing JSON data")
            }
            return result
        } catch (failure: DecodeFailure) {
            throw failure
        } catch (failure: IOException) {
            throw fail("JSON_INVALID", file, "/", failure.message ?: "Invalid JSON")
        } catch (failure: RuntimeException) {
            throw fail("JSON_INVALID", file, "/", failure.message ?: "Invalid JSON")
        }
    }

    @Throws(IOException::class)
    private fun read(reader: JsonReader, depth: Int, file: String): JsonElement {
        if (depth > limits.depth) {
            throw fail("JSON_DEPTH", file, reader.path, "JSON nesting budget exceeded")
        }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JsonObject()
                val keys = HashSet<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    unicode(name, file, reader.path)
                    if (!keys.add(name)) {
                        throw fail("JSON_DUPLICATE_KEY", file, reader.path, "Duplicate key $name")
                    }
                    result.add(name, read(reader, depth + 1, file))
                }
                reader.endObject()
                result
            }
            JsonToken.BEGIN_ARRAY -> {
                val result = JsonArray()
                reader.beginArray()
                while (reader.hasNext()) result.add(read(reader, depth + 1, file))
                reader.endArray()
                result
            }
            JsonToken.STRING -> {
                val value = reader.nextString()
                unicode(value, file, reader.path)
                JsonPrimitive(value)
            }
            JsonToken.NUMBER -> {
                val token = reader.nextString()
                if (token.length > 440) {
                    throw fail("NUMBER_LIMIT", file, reader.path, "Oversized number token")
                }
                val number = BigDecimal(token).stripTrailingZeros()
                if (
                    number.precision() > 128 ||
                        kotlin.math.abs(number.precision().toLong() - number.scale() - 1) > 308
                ) {
                    throw fail(
                        "NUMBER_LIMIT",
                        file,
                        reader.path,
                        "Numeric precision/exponent exceeded",
                    )
                }
                JsonPrimitive(number)
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }
            else -> throw fail("JSON_INVALID", file, reader.path, "Expected a JSON value")
        }
    }

    private fun definition(source: JsonObject): DialogueDefinition {
        fields(
            source,
            "schemaVersion",
            "id",
            "contentVersion",
            "entry",
            "locales",
            "capabilities",
            "interaction",
            "presentation",
            "nodes",
        )
        if (number(source, "schemaVersion").intValueExact() != 1) {
            throw fail(
                "SCHEMA_VERSION",
                "dialogue.json",
                "/schemaVersion",
                "Unsupported schema major version",
            )
        }
        val builder =
            DialogueBuilder.dialogue(s(source, "id"))
                .version(s(source, "contentVersion"))
                .entry(s(source, "entry"))
        val locale = obj(source.get("locales"))
        fields(locale, "default", "supported")
        builder.locales(s(locale, "default"), *strings(locale, "supported").toTypedArray())
        if (source.has("capabilities")) {
            val capabilities = obj(source.get("capabilities"))
            fields(capabilities, "required", "optional")
            builder.capabilities(
                Capabilities(
                    stringSet(capabilities, "required"),
                    stringSet(capabilities, "optional"),
                )
            )
        }
        if (source.has("interaction")) {
            val interaction = obj(source.get("interaction"))
            fields(interaction, "lookAway", "maxDistanceBlocks")
            val lookAway = obj(interaction.get("lookAway"))
            fields(lookAway, "enabled", "exitAngleDegrees", "exitDelayMs", "resetAngleDegrees")
            builder.interaction(
                Interaction(
                    LookAway(
                        bool(lookAway, "enabled", true),
                        number(lookAway, "exitAngleDegrees").toDouble(),
                        number(lookAway, "exitDelayMs").longValueExact(),
                        number(lookAway, "resetAngleDegrees").toDouble(),
                    ),
                    number(interaction, "maxDistanceBlocks").toDouble(),
                )
            )
        }
        if (source.has("presentation")) {
            val presentation = obj(source.get("presentation"))
            fields(presentation, "focus", "nearFraming", "vignette", "hideHud")
            builder.presentation(
                Presentation(
                    s(presentation, "focus"),
                    s(presentation, "nearFraming"),
                    s(presentation, "vignette"),
                    stringSet(presentation, "hideHud"),
                )
            )
        }
        obj(source.get("nodes")).entrySet().forEach { (id, value) ->
            val path = "/nodes/" + id.replace("~", "~0").replace("/", "~1")
            try {
                builder.node(id, node(obj(value)))
            } catch (failure: DecodeFailure) {
                throw fail(
                    failure.code,
                    failure.file,
                    path + failure.path,
                    failure.message ?: "Invalid node",
                )
            } catch (failure: RuntimeException) {
                throw fail("NODE_INVALID", "dialogue.json", path, failure.message ?: "Invalid node")
            }
        }
        return builder.build()
    }

    private fun node(source: JsonObject): Node =
        when (s(source, "type")) {
            "message" -> {
                fields(source, "type", "speaker", "text", "presentation", "choices", "next")
                if (source.has("choices") == source.has("next")) {
                    throw fail(
                        "MESSAGE_SHAPE",
                        "dialogue.json",
                        "/nodes",
                        "Message requires exactly one of choices or next",
                    )
                }
                var gesture: String? = null
                if (source.has("presentation")) {
                    val presentation = obj(source.get("presentation"))
                    fields(presentation, "gesture")
                    gesture = s(presentation, "gesture")
                }
                val choices = mutableListOf<Choice>()
                if (source.has("choices")) {
                    array(source, "choices").forEach { element ->
                        val choice = obj(element)
                        fields(choice, "id", "text", "to", "default", "condition")
                        choices +=
                            Choice(
                                s(choice, "id"),
                                s(choice, "text"),
                                s(choice, "to"),
                                bool(choice, "default", false),
                                if (choice.has("condition")) condition(choice.get("condition"))
                                else null,
                            )
                    }
                }
                Message(
                    s(source, "speaker"),
                    s(source, "text"),
                    gesture,
                    choices,
                    if (source.has("next")) s(source, "next") else null,
                )
            }
            "branch" -> {
                fields(source, "type", "cases", "defaultTo", "unavailableTo")
                val cases =
                    array(source, "cases").map { element ->
                        val case = obj(element)
                        fields(case, "condition", "to")
                        Case(condition(case.get("condition")), s(case, "to"))
                    }
                Branch(cases, s(source, "defaultTo"), s(source, "unavailableTo"))
            }
            "action" -> {
                fields(
                    source,
                    "type",
                    "action",
                    "arguments",
                    "onSuccess",
                    "onRejected",
                    "onUnavailable",
                    "onUncertain",
                )
                Action(
                    s(source, "action"),
                    arguments(source),
                    s(source, "onSuccess"),
                    s(source, "onRejected"),
                    s(source, "onUnavailable"),
                    s(source, "onUncertain"),
                )
            }
            "outcome" -> {
                fields(
                    source,
                    "type",
                    "milestone",
                    "delivery",
                    "onAcknowledged",
                    "onUnavailable",
                    "onUncertain",
                )
                Outcome(
                    s(source, "milestone"),
                    delivery(s(source, "delivery")),
                    s(source, "onAcknowledged"),
                    s(source, "onUnavailable"),
                    s(source, "onUncertain"),
                )
            }
            "end" -> {
                fields(source, "type", "reason")
                End(s(source, "reason"))
            }
            else -> throw fail("NODE_TYPE", "dialogue.json", "/nodes", "Unknown node type")
        }

    private class DecodeFailure(
        val code: String,
        val file: String,
        val path: String,
        message: String,
    ) : RuntimeException(message)

    private fun kind(tag: String): HostCatalog.Kind =
        when (tag) {
            "text" -> HostCatalog.Kind.TEXT
            "number" -> HostCatalog.Kind.NUMBER
            "boolean" -> HostCatalog.Kind.BOOLEAN
            "null" -> HostCatalog.Kind.NULL
            else -> throw IllegalArgumentException("Unknown argument type $tag")
        }

    // Gson's long accumulator can misclassify valid large integers ending in zeros.
    // Normalize only lexically valid JSON number tokens; strict structural parsing stays
    // enabled.
    private fun normalizeNumbers(source: String, file: String): String {
        val result = StringBuilder(source.length)
        var quoted = false
        var escaped = false
        var index = 0
        while (index < source.length) {
            val character = source[index]
            if (quoted) {
                result.append(character)
                index++
                if (escaped) escaped = false
                else if (character == '\\') escaped = true else if (character == '"') quoted = false
                continue
            }
            if (character == '"') {
                quoted = true
                result.append(character)
                index++
                continue
            }
            if (character == '-' || character in '0'..'9') {
                var end = index + 1
                while (end < source.length && source[end] !in " \t\r\n,]}:{") end++
                val token = source.substring(index, end)
                if (token.length > 440) {
                    throw fail("NUMBER_LIMIT", file, "/", "Oversized number token")
                }
                if (
                    !token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))
                ) {
                    throw fail("JSON_INVALID", file, "/", "Invalid JSON number")
                }
                val number = BigDecimal(token).stripTrailingZeros()
                if (
                    number.precision() > 128 ||
                        kotlin.math.abs(number.precision().toLong() - number.scale() - 1) > 308
                ) {
                    throw fail("NUMBER_LIMIT", file, "/", "Numeric precision/exponent exceeded")
                }
                result.append(number.toString())
                index = end
            } else {
                result.append(character)
                index++
            }
        }
        return result.toString()
    }

    private fun unicode(value: String, file: String, path: String) {
        if (!DialogueValidator.validUnicode(value)) {
            throw fail("UNICODE_INVALID", file, path, "Unpaired Unicode surrogate")
        }
    }

    private fun obj(element: JsonElement?): JsonObject {
        require(element != null && element.isJsonObject) { "Expected object" }
        return element.asJsonObject
    }

    private fun stringValue(element: JsonElement?): String {
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "Expected string"
        }
        return element.asString
    }

    private fun s(source: JsonObject, key: String): String = stringValue(source.get(key))

    private fun bool(source: JsonObject, key: String, fallback: Boolean): Boolean {
        if (!source.has(key)) return fallback
        val element = source.get(key)
        require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
            "Expected boolean: $key"
        }
        return element.asBoolean
    }

    private fun number(source: JsonObject, key: String): BigDecimal {
        val element = source.get(key)
        require(element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            "Expected number: $key"
        }
        return element.asBigDecimal
    }

    private fun array(source: JsonObject, key: String): List<JsonElement> {
        require(source.has(key) && source.get(key).isJsonArray) { "Expected array: $key" }
        return source.getAsJsonArray(key).asList()
    }

    private fun strings(source: JsonObject, key: String): List<String> =
        array(source, key).map(::stringValue)

    private fun stringSet(source: JsonObject, key: String): Set<String> {
        val values = strings(source, key)
        val result = LinkedHashSet(values)
        require(result.size == values.size) { "Duplicate array value: $key" }
        return result
    }

    private fun fields(source: JsonObject, vararg allowed: String) {
        val names = allowed.toSet()
        source.keySet().forEach { key ->
            if (key !in names) {
                throw fail("FIELD_UNKNOWN", "dialogue.json", "/$key", "Unknown field $key")
            }
        }
    }

    private fun optionalObject(source: JsonObject, key: String): JsonObject =
        if (source.has(key)) obj(source.get(key)) else JsonObject()

    private fun arguments(source: JsonObject): Map<String, ArgumentValue> {
        val result = LinkedHashMap<String, ArgumentValue>()
        optionalObject(source, "arguments").entrySet().forEach { (key, value) ->
            result[key] =
                when {
                    value.isJsonNull -> ArgumentValue.Nil.INSTANCE
                    value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                        ArgumentValue.Text(value.asString)
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                        ArgumentValue.Flag(value.asBoolean)
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber ->
                        ArgumentValue.Number(value.asBigDecimal)
                    else -> throw IllegalArgumentException("Arguments must be scalars")
                }
        }
        return result
    }

    private fun condition(element: JsonElement): Condition {
        val source = obj(element)
        fields(source, "key", "arguments", "negated")
        return Condition(s(source, "key"), arguments(source), bool(source, "negated", false))
    }

    private fun delivery(value: String): Delivery =
        when (value) {
            "local" -> Delivery.LOCAL
            "durable" -> Delivery.DURABLE
            else -> throw IllegalArgumentException("Unknown delivery mode")
        }

    private fun host(source: JsonObject): HostCatalog {
        fields(
            source,
            "schemaVersion",
            "generation",
            "speakers",
            "conditions",
            "actions",
            "gestures",
            "capabilities",
            "durableOutcomes",
        )
        require(number(source, "schemaVersion").intValueExact() == 1) {
            "Unknown host catalogue version"
        }
        return HostCatalog(
            s(source, "generation"),
            stringSet(source, "speakers"),
            providers(obj(source.get("conditions"))),
            providers(obj(source.get("actions"))),
            stringSet(source, "gestures"),
            stringSet(source, "capabilities"),
            bool(source, "durableOutcomes", false),
        )
    }

    private fun providers(source: JsonObject): Map<String, HostCatalog.ProviderSpec> {
        val result = LinkedHashMap<String, HostCatalog.ProviderSpec>()
        source.entrySet().forEach { (key, value) ->
            val provider = obj(value)
            fields(provider, "arguments", "durable")
            val arguments = LinkedHashMap<String, HostCatalog.ArgumentSpec>()
            optionalObject(provider, "arguments").entrySet().forEach { (name, argument) ->
                val shape = obj(argument)
                fields(shape, "type", "required", "nullable")
                arguments[name] =
                    HostCatalog.ArgumentSpec(
                        kind(s(shape, "type")),
                        bool(shape, "required", false),
                        bool(shape, "nullable", false),
                    )
            }
            result[key] = HostCatalog.ProviderSpec(arguments, bool(provider, "durable", false))
        }
        return result
    }

    private fun fail(code: String, file: String, path: String, message: String): DecodeFailure =
        DecodeFailure(code, file, path, message)
}
