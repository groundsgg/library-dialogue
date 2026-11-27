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
these legacy gates. The separate development major candidate audit below does
not authorize accepting a new API or schema baseline.

## Explicit development major candidate

`python3 tools/check_dialogue_major_candidate.py --check --repository .evidence/maven --version 0.1.0-SNAPSHOT`
is CI's explicit target-major-2 API comparator for the native constructor/SPI
migration. It checks the exact reviewed breaks against both the pinned 1.8
inventory and the older floors. Schema pins, six-artifact checks, frozen binary
consumers and current Java/Kotlin Maven consumers remain active.

The original strict command remains available:

```sh
python3 tools/check_dialogue_compatibility.py --check --repository .evidence/maven --version 0.1.0-SNAPSHOT
```

It is expected to fail on the listed native constructor and renderer-superinterface
breaks. This reviewed CI switch selects the major comparator explicitly; no file
or branch name activates a relaxed mode automatically. It accepts no release
baseline and exempts no schema changes.

`major-candidate/reference-1.8/manifest.json` is pinned by SHA-256 in the checker.
It binds all six source-derived reference inventories, original JAR hashes,
immutable source commit `f6772e81efd070cf8ca52a770061da5a90b67bf5`, source release
metadata `1.8.0`, local coordinates `0.1.0-SNAPSHOT`, Java 25, the source archive
hash, unchanged historical floor files and frozen consumer provenance. These are
local archive builds, not downloaded release bytes. Each inventory preserves raw
JVM signatures, actual class/member flags and the filtered comparison surface.
Missing, corrupted or truncated reference files fail before candidate JARs are
read. Reproduce into a scratch directory with Java 25:

```sh
python3 tools/capture_dialogue_major_reference.py --source /path/to/isolated-source --output /path/to/reference-output
```

Capture requires an absent source directory or an empty real directory; files,
symlinks and every preexisting entry are rejected before extraction or Gradle.
It verifies and extracts the immutable source archive, builds local review
publications and writes evidence. Reference reproduction applies only the closed
set of 104 exclusions derived from the hash-pinned raw/filtered inventories and
classfile metadata, and must reproduce the pinned evidence exactly. A discrepancy
requires explicit provenance review. Capture never updates the checker's trust anchor.
The current development inventory is explicitly labeled as such in
`major-candidate/candidate.json`; regenerate it for a reviewed candidate change:

```sh
python3 tools/check_dialogue_major_candidate.py --capture-candidate compatibility/major-candidate/candidate.json --repository .evidence/maven --version 0.1.0-SNAPSHOT --evidence .evidence/major-candidate-raw
```

Every actual candidate JAR must match the complete recorded module inventory,
including additions. Additions are recorded API, not binary breaks. Both the
full six-module 1.8 reference and the older historical floors are audited. V1
schema hashes must remain exact and cannot be excepted.

`major-candidate/breaks.json` binds target major 2 to the reference commit and
manifest hash. Each reviewed `changes` entry requires `module`, `owner`, `kind`
(`type`, `header`, `member`, or `permitted`), exact `before`, exact `after`,
`rationale` and `migration`. Member values are two-item arrays containing the
exact declaration and `descriptor: ...` line. A removed member/type uses
`after: null`; its migration identifies the replacement. An optional member
replacement must identify an exact candidate declaration/descriptor that exists;
there is no overload-name inference. A changed declaration with the same exact
JVM member name and descriptor requires its exact actual `after` declaration;
it cannot be approved as a removal. Header and permitted-subclass changes must
match both exact values. A type removal includes its full original inventory.
Unknown fields/kinds, duplicate JSON keys or entries, unused entries and any
unlisted differences fail. Patterns and prefixes never match changes.

Member comparisons use the exact classfile tuple (module, owner, field/method,
full JVM name, descriptor), including constructors and modified UTF-8 names.
Ordered public/protected classfile tables are checked against javap declaration
and descriptor pairs; ambiguous or unrepresentable mappings fail closed. Historical
floor members are bound uniquely to the pinned reference before candidate checks.
The comparison keeps the legacy explicit record-kind exceptions and redundant
method-final normalization on final classes. It conservatively retains Kotlin
JVM-public declarations outside excluded `.internal` packages, Kotlin default
argument masks, `DefaultConstructorMarker` overloads, generic bridges,
companions, `DefaultImpls` and ordinary dollar-named types. Synthetic generated
types are excluded only using actual class `ACC_SYNTHETIC`. The candidate policy
`jvm-conservative-v2-retain-members` retains every public/protected member of each
included type, including generated accessors, generic bridges and user-declared
`@JvmSynthetic` methods. Reference reproduction preserves the pinned v1 policy
and its exact historical exclusions. Additional candidate members remain visible
even when their identity and flags equal a historical helper. Raw evidence retains
every declaration.

This development gate neither accepts a 2.0 release baseline nor proves full
Kotlin source compatibility or supported SPI classification. Excluded-package
leaks still require the later supported-SPI gate. Historical precompiled consumer
checks and current-source consumer checks remain separate and active.
