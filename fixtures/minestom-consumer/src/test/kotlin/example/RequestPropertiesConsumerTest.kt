package example

import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition.End
import gg.grounds.dialogue.core.runtime.PresentationPreferences
import gg.grounds.dialogue.core.text.LocaleContext
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.text.TextResolver
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.ValidationLimits
import gg.grounds.dialogue.minestom.DialogueServices
import gg.grounds.dialogue.minestom.MinestomDialogueRequest
import gg.grounds.dialogue.minestom.MinestomDialogueSpeechListener
import gg.grounds.dialogue.minestom.input.InputProfile
import gg.grounds.dialogue.presentation.DialoguePresenter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import net.minestom.server.coordinate.Pos
import net.minestom.testing.Env
import net.minestom.testing.EnvTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@EnvTest
class RequestPropertiesConsumerTest {
    @Test
    fun kotlinPropertiesAndListenerDefaultLinkWithLegacyJavaAgainstPublishedJars(env: Env) {
        val player = env.createPlayer(env.createEmptyInstance(), Pos(0.0, 42.0, 0.0))
        val texts = TextCatalog(mapOf("en" to emptyMap()))
        val content =
            DialogueValidator()
                .validate(
                    DialogueBuilder.dialogue("consumer:request")
                        .node("start", End("completed"))
                        .build(),
                    texts,
                    HostCatalog(
                        "public",
                        emptySet(),
                        emptyMap(),
                        emptyMap(),
                        emptySet(),
                        emptySet(),
                        false,
                    ),
                    ValidationLimits.defaults(),
                )
                .also { assertTrue(it.isValid(), it.problems().toString()) }
                .validated()!!
        val services =
            DialogueServices(
                "public",
                emptyMap(),
                emptyMap(),
                null,
                TextResolver.catalogue(texts, "en"),
                emptySet(),
            )
        val locale = LocaleContext("en", 2, "en")
        val preferences = PresentationPreferences(reducedMotion = true)
        val controls = InputProfile.defaults()
        val request =
            MinestomDialogueRequest(
                openingImpulse = 4,
                controls = controls,
                actors = null,
                speakers = emptyMap(),
                preferences = preferences,
                locale = locale,
                context = emptyMap(),
                services = services,
                content = content,
                hostGeneration = 3,
                player = player,
                requestId = "published-request",
            )
        assertEquals("published-request", request.requestId)
        assertSame(player, request.player)
        assertEquals(3L, request.hostGeneration)
        assertSame(content, request.content)
        assertSame(services, request.services)
        assertTrue(request.context.isEmpty())
        assertSame(locale, request.locale)
        assertSame(preferences, request.preferences)
        assertTrue(request.speakers.isEmpty())
        assertNull(request.actors)
        assertSame(controls, request.controls)
        assertEquals(4L, request.openingImpulse)
        assertSame(MinestomDialogueSpeechListener.NO_OP, request.speechListener)
        LegacyRequestConsumer.verify(request)
        val origins =
            listOf(
                    MinestomDialogueRequest::class.java,
                    PresentationPreferences::class.java,
                    DialoguePresenter::class.java,
                )
                .joinToString("") { type ->
                    val path = Path.of(type.protectionDomain.codeSource.location.toURI())
                    assertTrue(
                        path.fileName.toString().endsWith(".jar"),
                        "Expected published Maven JAR: $path",
                    )
                    val hash =
                        HexFormat.of()
                            .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(path))
                            )
                    "MAVEN_ORIGIN ${type.name} ${path.toUri()}\nCONSUMER_JAR dialogue-${type.packageName.removePrefix("gg.grounds.dialogue.").substringBefore('.')} sha256=$hash\n"
                }
        val log = Path.of("build/test-origins/request.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, origins)
    }
}
