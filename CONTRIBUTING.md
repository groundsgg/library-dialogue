# Contributing to Dialogue

## Prerequisites

Use JDK 25 (`JAVA_HOME`), the checked-in Gradle wrapper, Python 3 for verification tools and network access to Maven Central/Gradle distribution and plugin repositories. Production library sources are Kotlin; Java files in fixtures verify interoperability. No Grounds credentials, purchased models or desktop fonts are needed for the normal SDK build. The optional personal-NPC fixture and Grounds fork profile have additional access/runtime requirements.

Start with the [quickstart](docs/quickstart.md) and [architecture](docs/architecture.md). The six existing modules and host ports are the current architecture; plans for a future major version are not shipped API.

## Build, test and format

From the repository root:

```sh
./gradlew --no-parallel --max-workers=1 test
./gradlew --no-parallel --max-workers=1 spotlessApply
./gradlew --no-parallel --max-workers=1 build
./gradlew --no-parallel --max-workers=1 spotlessCheck
```

`spotlessApply` formats Kotlin, Gradle Kotlin DSL and configured Java fixtures. Run Prettier on changed Markdown when available; use `prettier --write PATH` and `prettier --check PATH`. Keep tables aligned using the formatter. Do not edit generated assets or dependency locks to hide a failing check. Normal builds use frozen font metrics. Code changes require the test, format and build sequence; docs-only changes need relevant command/link/format checks rather than an unrelated full runtime suite.

## Review Maven artifacts and consumers

```sh
./gradlew --no-parallel --max-workers=1 check publishAllPublicationsToReviewRepository :dialogue-cli:distZip
python3 tools/verify_public_artifacts.py
python3 -m unittest discover -s tools -p '*_test.py'
python3 tools/check_dialogue_compatibility.py --check --repository .evidence/maven --version 0.1.0-SNAPSHOT
./gradlew --no-parallel --max-workers=1 -p fixtures/java-consumer test run "-PdialogueRepository=file:$PWD/.evidence/maven"
./gradlew --no-parallel --max-workers=1 -p fixtures/kotlin-consumer test run "-PdialogueRepository=file:$PWD/.evidence/maven"
```

Expected outputs include `PUBLIC_ARTIFACTS_OK`, `CONSUMER_OK java` and `CONSUMER_OK kotlin`. The consumer tests exercise the real runtime with virtual time, locale changes, stale input, delayed actions and cleanup; testkit must not leak into application runtime classpaths. Review publication defaults to `0.1.0-SNAPSHOT`, independently of [version.txt](version.txt). Release Please supplies actual release versions to publication via `versionOverride`.

For fresh Maven consumers and the actual standalone CLI ZIP, select an **empty/nonexistent** work directory:

```sh
python3 tools/verify_dialogue_release.py \
  --repository "file:$PWD/.evidence/maven" --version 0.1.0-SNAPSHOT \
  --cli-zip dialogue-cli/build/distributions/dialogue-cli-0.1.0-SNAPSHOT.zip \
  --work-dir .evidence/fresh-local-review --allow-local-review
```

Local qualification emits `DIALOGUE_LOCAL_CONSUMERS_OK`, not remote publication proof. Remote qualification omits `--allow-local-review`, uses the exact HTTPS registry/release version and downloaded release ZIP, and requires package-read credentials. `DIALOGUE_REMOTE_CONSUMERS_OK` additionally checks all six remote publications and consumer origins; `CLI_DISTRIBUTION_OK` alone proves only standalone launcher checks. There is no MavenLocal/composite fallback. See [CI](.github/workflows/ci.yml) and [release verifier](tools/verify_dialogue_release.py) for the complete gates, including the separate binary-consumer check.

## Boundaries to preserve

Read [host contracts](docs/host-contracts.md) before changing lifecycle, scheduler or effect dispatch. Keep Core free of platform/service dependencies, one session state owner, idempotent cleanup, stale-generation fencing and ownership of late acquisitions. Test actual observable contracts with the existing testkit and consumer seams.

The [compatibility baseline](compatibility/baseline.json) captures the original four APIs and JSON-v1 schemas from accepted Package C; its strict equality check is not a general SemVer checker. Immutable Kotlin value classes retain Java constructors/accessors but are not Java records; record reflection/pattern users need to account for that distinction. Public declarations in `.internal` are implementation details even where JVM visibility permits access; do not add dependencies on them.

Use Conventional Commits. Keep unrelated plans, asset content and deployment changes out of focused contributions. Preserve the provenance and scope of [historical client acceptance](docs/client-acceptance.md), including explicit test waivers. Automated/server checks do not establish a new native-client or multi-proxy product acceptance result.
