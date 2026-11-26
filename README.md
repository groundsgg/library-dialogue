# Grounds Dialogue

Kotlin dialogue graphs, live language changes and shared HUD, NPC-panel and chat presentation for Kotlin and Java applications. The current release is **1.8.0**; see [release history](CHANGELOG.md).

The headless SDK needs JDK 25 and no Grounds services, Scene, NPC engine, database or client extension. The optional Minestom adapter uses an application-supplied, explicitly matched server runtime. Library production code is Kotlin; Java consumers are supported by independent Maven fixtures.

## Install

Use Maven Central for public dependencies and the repository-specific GitHub Packages endpoint for Dialogue. Package consumption requires a GitHub username and a token with package-read access, including access to the selected package. Keep credentials in your user Gradle properties or CI secret store.

```kotlin
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                url = uri("https://maven.pkg.github.com/groundsgg/library-dialogue")
                credentials {
                    username = providers.gradleProperty("github.user").orNull
                    password = providers.gradleProperty("github.token").orNull
                }
            }
        }
        filter {
            listOf("core", "json", "presentation", "minestom", "testkit", "cli")
                .forEach { includeModule("gg.grounds", "dialogue-$it") }
        }
    }
}

dependencies {
    implementation("gg.grounds:dialogue-core:1.8.0")
}
```

Anonymous Maven access is unavailable at this endpoint (HTTP 401 in the 2026-11-26 onboarding check). The [source quickstart](docs/quickstart.md#run-from-source-without-credentials) needs no Grounds or GitHub credentials and publishes a separate local review snapshot.

## Minimal Kotlin authoring

```kotlin
import gg.grounds.dialogue.core.definition.DialogueBuilder
import gg.grounds.dialogue.core.definition.DialogueDefinition
import gg.grounds.dialogue.core.text.TextCatalog
import gg.grounds.dialogue.core.validation.HostCatalog
import gg.grounds.dialogue.core.validation.DialogueValidator
import gg.grounds.dialogue.core.validation.ValidationLimits

val definition = DialogueBuilder.dialogue("demo:hello")
    .node("start", DialogueDefinition.Message("guide", "hello", null, emptyList(), "end"))
    .node("end", DialogueDefinition.End("completed"))
    .build()
val texts = TextCatalog(mapOf("en" to mapOf("hello" to "Welcome!")))
val host = HostCatalog("v1", setOf("guide"), emptyMap(), emptyMap(), emptySet(), emptySet(), false)
val report = DialogueValidator().validate(definition, texts, host, ValidationLimits.defaults())
val content = requireNotNull(report.validated()) { report.problems().toString() }
```

Only validation produces executable `ValidatedDialogue`. For a running session and Java usage, follow the [quickstart](docs/quickstart.md). The existing 1.x API includes record-style accessors such as `report.validated()` in both languages.

## Choose modules

All six artifacts use group `gg.grounds` and share one version.

| Artifact                | Use it for                                                | Dependencies                                                 |
| ----------------------- | --------------------------------------------------------- | ------------------------------------------------------------ |
| `dialogue-core`         | Definitions, validation, sessions, host ports             | JDK 25, Kotlin standard library                              |
| `dialogue-json`         | JSON authoring, schemas, catalogue reload                 | Core, internal Gson                                          |
| `dialogue-presentation` | Layout, themes, widgets, owned surfaces                   | Core                                                         |
| `dialogue-minestom`     | Local connection host, HUD/NPC/chat, input and pack state | Core, Presentation, internal Gson; explicit Minestom runtime |
| `dialogue-testkit`      | Virtual time and lifecycle testing                        | Core, Presentation; use `testImplementation`                 |
| `dialogue-cli`          | Standalone content validation and diagnostics             | Core, JSON; also distributed as a CLI ZIP                    |

## Read next

- [Quickstart: Kotlin, Java and source consumers](docs/quickstart.md)
- [Architecture and API map](docs/architecture.md)
- [Host contracts: scheduler, actions, input and cleanup](docs/host-contracts.md)
- [Minestom integration and matching runtimes](docs/minestom-integration.md)
- [CLI guide](dialogue-cli/README.md), [testkit guide](dialogue-testkit/README.md) and [JSON schemas](dialogue-json/src/main/resources/schemas)
- [Contributing, builds and distribution checks](CONTRIBUTING.md)
- [Historical native-client acceptance and explicit test waivers](docs/client-acceptance.md)

## License

Library code is [MIT](LICENSE). The bundled unchanged DejaVu Sans font retains its [font license](dialogue-presentation/src/main/resources/default-theme/FONT-LICENSE.txt); see [NOTICE](NOTICE). Purchased NPC models, LuxDialogues/Rivet assets and Grounds story content are not distributed.
