# Grounds Dialogue

Java-first dialogue graphs, live language changes and a shared presentation pipeline.

This repository implements the **headless core, JSON authoring and presentation contracts** (D3, packages A/B). HUD and NPC layouts use the same presenter. Their actual Minestom surfaces, resource-pack output and two-client game acceptance belong to the following package C and are not included yet. No Minestom, Scene, Grounds service, database or client extension is required to build or use these three modules.

| Artifact | Responsibility | Runtime dependencies |
|---|---|---|
| `gg.grounds:dialogue-core` | Immutable definitions, validation, sessions, choices, reveal, locale and host ports | JDK 25 |
| `gg.grounds:dialogue-json` | Strict JSON codec, author context, schemas and atomic catalogue reload | Core, internal Gson |
| `gg.grounds:dialogue-presentation` | Text metrics, widgets, viewport, themes and owned surfaces | Core |

Release Please and GitHub Packages are configured. Until the first release is published, use the source build and its local review repository; do not assume the snapshot already exists on GitHub Packages.

## Build and verify

```sh
./gradlew check publishAllPublicationsToReviewRepository
python3 tools/verify_public_artifacts.py
python3 -m unittest discover -s tools -p '*_test.py'
./gradlew -p fixtures/java-consumer run "-PdialogueRepository=file:$PWD/.evidence/maven"
./gradlew -p fixtures/kotlin-consumer run "-PdialogueRepository=file:$PWD/.evidence/maven"
./gradlew :examples:headless-dialogue:run --args=--smoke
./gradlew :examples:headless-dialogue:run --args=--presentation-smoke
```

Expected markers: `PUBLIC_ARTIFACTS_OK`, `CONSUMER_OK java`, `CONSUMER_OK kotlin`, `HEADLESS_DIALOGUE_OK` and `PRESENTATION_OK`. The independent consumers resolve Maven JARs; they do not include the root build or fall back to private Grounds repositories.

`./gradlew formatJava` formats Java sources; `checkJavaFormat` verifies them. Dependency lockfiles and the official Gradle distribution checksum are checked in. Normal builds use frozen font metrics and do not depend on an installed desktop font.

## Java authoring

```java
var definition = DialogueBuilder.dialogue("demo:hello")
    .node("start", new DialogueDefinition.Message("guide", "hello", null, List.of(), "end"))
    .node("end", new DialogueDefinition.End("completed"))
    .build();
var texts = new TextCatalog(Map.of("en", Map.of("hello", "Welcome!")));
var host = new HostCatalog("v1", Set.of("guide"), Map.of(), Map.of(), Set.of(), Set.of(), false);
var report = new DialogueValidator().validate(definition, texts, host, ValidationLimits.defaults());
if (!report.isValid()) {
    throw new IllegalArgumentException(report.problems().toString());
}
var content = report.validated();
```

Builder output still requires validation. Only the common validator issues `ValidatedDialogue`. JSON produces the same model and content pin. For a complete runnable session with owned renderer, scheduler, locale switch, selection and cleanup, see [the Java example](examples/headless-dialogue/src/main/java/example/HeadlessDialogue.java). [The independent Java consumer](fixtures/java-consumer/src/main/java/example/Consumer.java) also shows a semantic surface that does not use glyph assets.

## Authoring and presentation

Map projects can keep `dialogues/`, `texts/` and generated `context/host-catalog.json` beside their own world/scene files. The library accepts named bytes and does not discover files or modify maps. [JSON schemas](dialogue-json/src/main/resources/schemas) and the [complete bilingual example](examples/headless-dialogue/src/main/resources) are public.

A message has either a `next` target or choices. Every choice list must include an unconditional choice, such as “Later”. Default means preselected, never automatically confirmed. Conditions are checked again before confirm. Actions and outcomes use explicit typed paths; they do not implement quest progress or rewards.

The session owns reveal, selection and input freshness. `DialoguePresenter` creates shared widgets; layout profiles arrange choices below the text or beside it. `DialogueSurface` owns platform output. A semantic surface receives the original `DialogueView` without measuring glyphs. Visual selection growth keeps its hit rectangle fixed. Missing glyphs/overflow raise a typed `FallbackRequired`; the host can choose a semantic/chat renderer. No content is silently cut off.

The supplied glyph profile covers the example's DE/EN characters. Arbitrary Unicode remains valid dialogue content; a graphical font profile must provide its coverage, or the host must choose a semantic fallback. HUD/NPC game geometry and ray tests are part of package C.

## Host obligations

Read [host contracts](docs/host-contracts.md) before integrating external actions, surfaces or player lifecycle. Important defaults: 2 s text/condition waits, 5 s preparation/reconfiguration, 10 s action/outcome waits and 30 s cleanup. Budgets are configurable. A timeout is not proof that an external mutation did not happen.

The core uses a host scheduler and never creates a thread. Ports must return their stages promptly; blocking I/O belongs on a host executor. Stops are idempotent. Cancellation is available before `Started`, and late acquisitions remain owned. An unconfirmed cleanup completes exceptionally rather than reporting success.

## GitHub Packages

After a version is published, use the repository-specific Maven endpoint. GitHub Packages requires a GitHub identity and a token with package-read access, even for public packages. The source build needs no Grounds credentials.

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/library-dialogue")
        credentials {
            username = providers.gradleProperty("github.user").orNull
            password = providers.gradleProperty("github.token").orNull
        }
    }
}
```

Select an actual release version. The [consumer build](fixtures/java-consumer/build.gradle.kts) additionally restricts these coordinates to that repository so credentials are not sent to unrelated repositories.

## License

Library code: MIT. The bundled unchanged DejaVu Sans font keeps its Bitstream Vera license and public-domain DejaVu changes; see [FONT-LICENSE.txt](dialogue-presentation/src/main/resources/default-theme/FONT-LICENSE.txt) and `NOTICE`. No LuxDialogues/Rivet assets, purchased NPC models or Grounds story content are distributed.
