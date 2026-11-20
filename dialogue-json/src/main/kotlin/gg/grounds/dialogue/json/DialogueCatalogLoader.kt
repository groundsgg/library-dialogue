package gg.grounds.dialogue.json

import gg.grounds.dialogue.core.validation.ContentFingerprint
import gg.grounds.dialogue.core.validation.ContentPin
import gg.grounds.dialogue.core.validation.ValidatedDialogue
import gg.grounds.dialogue.core.validation.ValidationReport.Problem
import gg.grounds.dialogue.core.validation.ValidationReport.Severity
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Atomically installs an entirely valid catalogue. Does not discover or read files. */
class DialogueCatalogLoader {
    private val codec = DialogueJsonCodec()
    private val current = AtomicReference<ValidatedCatalogue>()
    private val requests = AtomicLong()

    class ContentSources(
        dialogues: Map<String, ByteArray>,
        texts: Map<String, ByteArray>,
        hostCatalog: ByteArray,
    ) {
        private val dialogueData = copy(dialogues)
        private val textData = copy(texts)
        private val hostCatalogData = hostCatalog.clone()

        fun dialogues(): Map<String, ByteArray> = copy(dialogueData)

        fun texts(): Map<String, ByteArray> = copy(textData)

        fun hostCatalog(): ByteArray = hostCatalogData.clone()

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is ContentSources &&
                    dialogueData == other.dialogueData &&
                    textData == other.textData &&
                    hostCatalogData === other.hostCatalogData)

        override fun hashCode(): Int =
            31 * (31 * dialogueData.hashCode() + textData.hashCode()) + hostCatalogData.hashCode()

        override fun toString(): String =
            "ContentSources[dialogues=$dialogueData, texts=$textData, hostCatalog=$hostCatalogData]"

        private companion object {
            fun copy(source: Map<String, ByteArray>): Map<String, ByteArray> {
                val result = LinkedHashMap<String, ByteArray>()
                source.forEach { (key, value) -> result[key] = value.clone() }
                return Collections.unmodifiableMap(result)
            }
        }
    }

    class ValidatedCatalogue(
        entries: Map<String, ValidatedDialogue>,
        private val contentPin: ContentPin,
        private val generation: Long,
    ) {
        private val entries = java.util.Map.copyOf(entries)

        fun entries(): Map<String, ValidatedDialogue> = entries

        fun contentPin(): ContentPin = contentPin

        fun generation(): Long = generation

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is ValidatedCatalogue &&
                    entries == other.entries &&
                    contentPin == other.contentPin &&
                    generation == other.generation)

        override fun hashCode(): Int =
            31 * (31 * entries.hashCode() + contentPin.hashCode()) + generation.hashCode()

        override fun toString(): String =
            "ValidatedCatalogue[entries=$entries, contentPin=$contentPin, generation=$generation]"
    }

    class ReloadResult(
        problems: List<Problem>,
        private val candidate: ValidatedCatalogue?,
        private val activated: Boolean,
    ) {
        private val problems = java.util.List.copyOf(problems)

        fun problems(): List<Problem> = problems

        fun candidate(): ValidatedCatalogue? = candidate

        fun activated(): Boolean = activated

        fun isValid(): Boolean = candidate != null

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is ReloadResult &&
                    problems == other.problems &&
                    candidate == other.candidate &&
                    activated == other.activated)

        override fun hashCode(): Int =
            31 * (31 * problems.hashCode() + (candidate?.hashCode() ?: 0)) + activated.hashCode()

        override fun toString(): String =
            "ReloadResult[problems=$problems, candidate=$candidate, activated=$activated]"
    }

    fun current(): Optional<ValidatedCatalogue> = Optional.ofNullable(current.get())

    fun reload(sources: ContentSources): ReloadResult {
        val generation = requests.incrementAndGet()
        val dialogues = sources.dialogues()
        val texts = sources.texts()
        val host = sources.hostCatalog()
        val size =
            host.size.toLong() + dialogues.values.sumOf { it.size } + texts.values.sumOf { it.size }
        if (size > 16_777_216L || dialogues.isEmpty() || dialogues.size > 1024) {
            return ReloadResult(
                listOf(
                    Problem(
                        "CATALOGUE_LIMIT",
                        Severity.ERROR,
                        "manifest",
                        "/",
                        null,
                        "Catalogue must contain 1–1024 dialogues and at most 16 MiB",
                    )
                ),
                null,
                false,
            )
        }
        val context = codec.prepareContext(texts, host)
        if (!context.isValid()) return ReloadResult(context.problems(), null, false)
        val problems = mutableListOf<Problem>()
        val entries = LinkedHashMap<String, ValidatedDialogue>()
        dialogues.forEach { (source, bytes) ->
            val decoded = codec.decodeDefinition(bytes, context.context()!!)
            decoded.problems().forEach { problem ->
                problems +=
                    Problem(
                        problem.code,
                        problem.severity,
                        if (problem.file == "dialogue.json") source else problem.file,
                        problem.path,
                        problem.nodeId,
                        problem.message,
                    )
            }
            if (decoded.isValid()) {
                val validated = decoded.validated()!!
                val id = validated.definition().id()
                if (entries.putIfAbsent(id, validated) != null) {
                    problems +=
                        Problem(
                            "DIALOGUE_DUPLICATE",
                            Severity.ERROR,
                            source,
                            "/id",
                            null,
                            "Duplicate dialogue ID $id",
                        )
                }
            }
        }
        if (problems.any { it.severity == Severity.ERROR })
            return ReloadResult(problems, null, false)
        val pins = LinkedHashMap<String, String>()
        entries.forEach { (id, validated) -> pins[id] = validated.contentPin().sha256 }
        val pin =
            ContentFingerprint.hash(
                ContentFingerprint.canonicalBytes(
                    mapOf("manifestVersion" to 1, "dialogues" to pins)
                )
            )
        val candidate = ValidatedCatalogue(entries, pin, generation)
        while (true) {
            val old = current.get()
            if (old != null && old.generation() > generation) {
                return ReloadResult(problems, candidate, false)
            }
            if (current.compareAndSet(old, candidate))
                return ReloadResult(problems, candidate, true)
        }
    }
}
