# Dialogue quickstart

Use JDK 25. The current released coordinates use version `1.8.0`; a source build defaults to **`0.1.0-SNAPSHOT`** unless `versionOverride` is supplied. A local review publication is not evidence that a version exists remotely.

## Run from source without credentials

From a fresh checkout of this repository, with `JAVA_HOME` pointing to JDK 25:

```sh
./gradlew --no-parallel --max-workers=1 publishAllPublicationsToReviewRepository
./gradlew --no-parallel --max-workers=1 :examples:headless-dialogue:run --args=--smoke
./gradlew --no-parallel --max-workers=1 :examples:headless-dialogue:run --args=--presentation-smoke
./gradlew --no-parallel --max-workers=1 -p fixtures/kotlin-consumer run "-PdialogueRepository=file:$PWD/.evidence/maven"
./gradlew --no-parallel --max-workers=1 -p fixtures/java-consumer run "-PdialogueRepository=file:$PWD/.evidence/maven"
```

Expected markers are `HEADLESS_DIALOGUE_OK`, `PRESENTATION_OK`, `CONSUMER_OK kotlin` and `CONSUMER_OK java`. The consumers resolve actual review JARs and print `MAVEN_ORIGIN` locations; they use neither MavenLocal nor a composite source build. The first run downloads Gradle and public dependencies. No GitHub package token or Grounds service is required for these commands.

The [headless Kotlin example](../examples/headless-dialogue/src/main/kotlin/example/HeadlessDialogue.kt) owns a renderer and scheduler, starts validated content, changes locale, selects/confirms and closes resources. It is the runnable session example. The [Kotlin consumer](../fixtures/kotlin-consumer/src/main/kotlin/example/Consumer.kt) is a smaller Maven/authoring/JSON/layout check. The [Java consumer](../fixtures/java-consumer/src/main/java/example/Consumer.java) also exercises a real runtime and semantic surface.

## Use the released Maven artifacts

Configure the authenticated repository as shown in the [README](../README.md#install). Add only the modules needed by your application:

```kotlin
dependencies {
    implementation("gg.grounds:dialogue-core:1.8.0")
    implementation("gg.grounds:dialogue-json:1.8.0")
    implementation("gg.grounds:dialogue-presentation:1.8.0")
    testImplementation("gg.grounds:dialogue-testkit:1.8.0")
}
```

To use the existing independent fixtures against that release instead of local review JARs:

```sh
./gradlew --no-parallel --max-workers=1 -p fixtures/kotlin-consumer run \
  -PdialogueVersion=1.8.0 \
  -PdialogueRepository=https://maven.pkg.github.com/groundsgg/library-dialogue
./gradlew --no-parallel --max-workers=1 -p fixtures/java-consumer run \
  -PdialogueVersion=1.8.0 \
  -PdialogueRepository=https://maven.pkg.github.com/groundsgg/library-dialogue
```

Both fixtures accept `github.user` / `github.token` Gradle properties or `GITHUB_ACTOR` / `GITHUB_TOKEN` environment variables. Do not commit secrets. Authenticated remote-release qualification is separate from the credential-free local commands above; anonymous requests cannot verify publication contents.

## Java authoring

Add the same Core dependency in a Java application targeting JDK 25. The API retains Java-callable constructors, factories and record-style accessors:

```java
import gg.grounds.dialogue.core.definition.DialogueBuilder;
import gg.grounds.dialogue.core.definition.DialogueDefinition;
import gg.grounds.dialogue.core.text.TextCatalog;
import gg.grounds.dialogue.core.validation.DialogueValidator;
import gg.grounds.dialogue.core.validation.HostCatalog;
import gg.grounds.dialogue.core.validation.ValidationLimits;
import java.util.List;
import java.util.Map;
import java.util.Set;

var definition = DialogueBuilder.dialogue("demo:hello")
    .node("start", new DialogueDefinition.Message("guide", "hello", null, List.of(), "end"))
    .node("end", new DialogueDefinition.End("completed"))
    .build();
var texts = new TextCatalog(Map.of("en", Map.of("hello", "Welcome!")));
var host = new HostCatalog("v1", Set.of("guide"), Map.of(), Map.of(), Set.of(), Set.of(), false);
var report = new DialogueValidator().validate(definition, texts, host, ValidationLimits.defaults());
if (!report.isValid()) throw new IllegalArgumentException(report.problems().toString());
var content = report.validated();
```

These are method-body statements. The [complete Java application](../fixtures/java-consumer/src/main/java/example/Consumer.java) shows imports, `main`, scheduler, renderer, start, input and cleanup. Kotlin implementation does not require a coroutine host; the common asynchronous contract is `CompletionStage` plus explicit cancellation.

## Connect your host

1. Build or decode definitions and validate against your `HostCatalog`, text catalogue and limits. JSON and builder authoring produce the same model and content pin.
2. Provide a monotonic `DialogueScheduler`, versioned bindings, locale/preferences, a renderer and any action/condition/outcome ports. Return stages promptly and run blocking I/O on your own executor.
3. Retain the `DialogueRuntime`, start ticket/cancellation token and `DialogueHandle`. Use current server-issued session/revision/impulse identities for input. A default choice is preselected, never auto-confirmed.
4. Stop on disconnect/transfer; await `closeAsync` before stopping the host scheduler. Late acquisitions remain owned. A timeout on an external action does not prove that its mutation did not happen.

See [architecture](architecture.md) for the corresponding types and [host contracts](host-contracts.md) for the complete rules. A content pin identifies the validated read snapshot, not map activation or durable quest progress.

## Add Minestom or author tooling

For a neutral graphical demo, first publish the local review artifacts, then run:

```sh
./gradlew --no-parallel --max-workers=1 -p examples/standalone-minestom run \
  "-PdialogueRepository=file:$PWD/.evidence/maven"
```

This is an independent Kotlin application with public Minestom `2026.10.07-26.2`, no Grounds token and no NPC engine. Connect to `localhost:25578`, accept the loopback pack and run `/dialogue`. Rejected/missing packs use semantic chat. [Demo commands and profiles](../examples/standalone-minestom/README.md) cover the larger showcase; [Minestom integration](minestom-integration.md) explains pack registration, input and borrowed speakers. Declare Minestom yourself: it is compile-only in the adapter. The optional Grounds 26.3 profile requires an explicit matching fork runtime.

[CLI](../dialogue-cli/README.md) validates named authoring inputs; [testkit](../dialogue-testkit/README.md) supplies deterministic virtual time. For source changes and artifact/distribution verification, see [Contributing](../CONTRIBUTING.md).
