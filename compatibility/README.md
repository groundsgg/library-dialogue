# Compatibility inputs

The current source consumers live in `fixtures/java-consumer` and
`fixtures/kotlin-consumer`. They compile and run against the candidate Maven
publications, including separate Testkit tests, and can evolve with the current
SDK.

The historical binary consumers are the versioned snapshot in
[`consumers/e299b88-v1`](consumers/e299b88-v1/manifest.json). Its six source,
Gradle build and settings files are byte-for-byte copies from `fixtures` at source
commit `f6772e81efd070cf8ca52a770061da5a90b67bf5`. The target SDK is separately
identified as commit `e299b88d56df3e745ac45b319bdc88199d1ddaee`, version
`0.0.0-compat-e299b88-SNAPSHOT`, on Java 25. This preserves the already-qualified
legacy consumer behavior; it is not a complete 1.8 migration consumer or a 2.0
compatibility baseline. No test sources, build caches or licensed assets are
copied into this snapshot.

`check_dialogue_compatibility.py --binary-consumers` validates provenance, the
pinned manifest SHA-256, the exact file inventory and every file SHA-256 before
any Gradle invocation, including an uncached legacy SDK publication. The frozen
build scripts pin the consumer tasks and Kotlin plugin; the repository Gradle
wrapper remains the launcher. Compilation uses only these historical inputs and
the explicit legacy SDK. The checker then freezes the resulting `.class` bytes,
removes the sources and build classes, and runs those unchanged bytes against the
candidate JARs. It checks nonempty classes, no recompilation, unchanged class
hashes, candidate JAR origins/hashes and Testkit exclusion from the main runtime.
Current fixture edits cannot redefine this historical binary check.

The existing API, schema and tooling baseline files remain authoritative and
unchanged. Any future accepted consumer snapshot must use a new versioned
directory with reviewed provenance and integrity; do not regenerate this snapshot
from the current fixtures. An intentionally incompatible 2.0 candidate can fail
these legacy gates. A future major-version candidate mode is not implemented by
this fix, and these inputs do not authorize accepting a new API or schema
baseline.
