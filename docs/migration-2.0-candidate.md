# Kotlin API migration for the 2.0 candidate

This is an unpublished 2.0 candidate source-API change. It does not publish a
2.0 release, change the current review Maven coordinates, or accept a new API or
schema baseline. The ordinary strict legacy JVM/API check remains enabled.

`PresentationPreferences` now exposes four immutable Kotlin properties. Update
Kotlin source as follows:

| Previous Kotlin call          | Candidate Kotlin property   | Retained Java/JVM call        |
| ----------------------------- | --------------------------- | ----------------------------- |
| `preferences.surfaceKey()`    | `preferences.surfaceKey`    | `preferences.surfaceKey()`    |
| `preferences.graphemeDelay()` | `preferences.graphemeDelay` | `preferences.graphemeDelay()` |
| `preferences.reducedMotion()` | `preferences.reducedMotion` | `preferences.reducedMotion()` |
| `preferences.effects()`       | `preferences.effects`       | `preferences.effects()`       |

Getter-level `@JvmName` preserves the original JVM method names and descriptors.
Java source and already compiled Kotlin consumers continue to call those methods.
Kotlin source using the old method syntax must migrate to the properties; there
are no duplicate method aliases or JavaBean getters.

Kotlin construction supports named arguments and defaults:

```kotlin
val defaults = PresentationPreferences()
val npc = PresentationPreferences(surfaceKey = "npc", reducedMotion = true)
```

The constructor defaults are `surfaceKey = "hud"`,
`graphemeDelay = Duration.ofMillis(35)`, `reducedMotion = false`, and
`effects = emptyMap()`. The existing static `PresentationPreferences.defaults()`
factory remains equivalent. Java retains the four-argument constructor and gains
a zero-argument constructor. Delay validation remains inclusive from zero to
60 seconds. Effects remain a defensive, unmodifiable `Map.copyOf` snapshot that
rejects null keys and values; equality, hashing and text representation retain
their existing value behavior.

The historical binary consumer snapshot and all API/schema baselines remain
unchanged. A broader manifest and gate for intentionally incompatible native
2.0 JVM signatures remains future work; this unit does not add a major-version
checker mode or authorize baseline acceptance.
