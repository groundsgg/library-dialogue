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

## Minestom request properties and optional listener

`MinestomDialogueRequest` now exposes thirteen immutable Kotlin properties.
This is another unpublished 2.0 candidate Kotlin source change. Java source and
existing JVM signatures remain compatible:

| Previous Kotlin call       | Candidate Kotlin property | Retained Java/JVM call     |
| -------------------------- | ------------------------- | -------------------------- |
| `request.requestId()`      | `request.requestId`       | `request.requestId()`      |
| `request.player()`         | `request.player`          | `request.player()`         |
| `request.hostGeneration()` | `request.hostGeneration`  | `request.hostGeneration()` |
| `request.content()`        | `request.content`         | `request.content()`        |
| `request.services()`       | `request.services`        | `request.services()`       |
| `request.context()`        | `request.context`         | `request.context()`        |
| `request.locale()`         | `request.locale`          | `request.locale()`         |
| `request.preferences()`    | `request.preferences`     | `request.preferences()`    |
| `request.speakers()`       | `request.speakers`        | `request.speakers()`       |
| `request.actors()`         | `request.actors`          | `request.actors()`         |
| `request.controls()`       | `request.controls`        | `request.controls()`       |
| `request.openingImpulse()` | `request.openingImpulse`  | `request.openingImpulse()` |
| `request.speechListener()` | `request.speechListener`  | `request.speechListener()` |

Kotlin supports named arguments and may omit only `speechListener`:

```kotlin
val request = MinestomDialogueRequest(
    requestId = "guide-start",
    player = player,
    hostGeneration = generation,
    content = content,
    services = services,
    context = context,
    locale = locale,
    preferences = preferences,
    speakers = speakers,
    actors = null,
    controls = controls,
    openingImpulse = openingImpulse,
)
```

The omitted listener is the same `MinestomDialogueSpeechListener.NO_OP` singleton.
Pass `speechListener = listener` to retain an explicit listener. Every other
parameter remains required, including the nullable `actors` parameter. Request
IDs must remain nonblank; host generation and opening impulse must remain
nonnegative. Context and speaker maps remain defensive, unmodifiable
`Map.copyOf` snapshots that reject null keys and values. Equality, hash code and
text representation preserve the existing omission of `speechListener`.

Java keeps both existing constructors and all thirteen original accessor names;
no JavaBean getters or duplicate Kotlin method aliases are added:

```java
var defaults = new MinestomDialogueRequest(
    requestId, player, hostGeneration, content, services, context, locale,
    preferences, speakers, actors, controls, openingImpulse);
var explicit = new MinestomDialogueRequest(
    requestId, player, hostGeneration, content, services, context, locale,
    preferences, speakers, actors, controls, openingImpulse, listener);
assert defaults.speechListener() == MinestomDialogueSpeechListener.NO_OP;
assert explicit.speechListener() == listener;
```

The Kotlin default generates a synthetic default constructor in addition to the
two retained Java constructors. It does not change native connection ownership,
session lifecycle, input, sound, scheduling or presentation behavior.

The historical binary consumer snapshot and all API/schema baselines remain
unchanged. A broader manifest and gate for intentionally incompatible native
2.0 JVM signatures remains future work; this unit does not add a major-version
checker mode or authorize baseline acceptance.
