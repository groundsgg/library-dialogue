# API migration for the 2.0 candidate

This is an unpublished 2.0 candidate API migration. It does not publish a
2.0 release, change the current review Maven coordinates, or accept a new API or
schema baseline. CI explicitly selects the reviewed major-candidate comparator for the native
JVM breaks below. The strict legacy CLI remains available and is expected to
reject those breaks. Schema pins, artifacts, frozen binaries and current Maven
consumer gates remain active.

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
unchanged. Native JVM constructor and SPI breaks are listed in the exact reviewed
major-candidate manifest. The broader supported-signature classifier and shared
helper opt-in remain separate work. No accepted baseline changes.

## Native connection scope and input source

Replace `gg.grounds.dialogue.minestom.internal.ConnectionGuard` with supported
`gg.grounds.dialogue.minestom.MinestomConnectionScope`. Replace
`internal.DialogueInputSource` imports with
`gg.grounds.dialogue.minestom.input.DialogueInputSource`; the renderer implements
this public SPI. No compatibility aliases are provided. Recompile custom native
pipelines against the candidate: old native constructor descriptors, renderer
superinterface and catcher static/companion signatures intentionally change.
Compiler-emitted constructor/test-seam forms are included in the reviewed JVM
break inventory; Kotlin-internal seams remain unsupported for application use.

Create one scope, fresh registry and renderer per session, even for the same
player/generation/instance. Share their exact references across native components:

```kotlin
val scope = MinestomConnectionScope(player, generation, player.instance)
val targets = SurfaceInputTargets()
val tokens = ChatInputTokens(scope, targets, clock::nanoTime)
val chat = ChatDialogueSurface(scope, targets, tokens, sink, labels, controls, "/dialogue_input")
val renderer = MinestomDialogueRenderer(
    scope, mapOf("chat" to DialoguePresenter.SurfaceProfile.semantic(chat)),
    clock, controls, labels, targets, status, tokens, { null },
)
val input: DialogueInputSource = renderer
```

Java uses the same argument order:

```java
var scope = new MinestomConnectionScope(player, generation, player.getInstance());
var targets = new SurfaceInputTargets();
var tokens = new ChatInputTokens(scope, targets, clock::nanoTime);
var chat = new ChatDialogueSurface(scope, targets, tokens, sink, labels, controls, "/dialogue_input");
var renderer = new MinestomDialogueRenderer(scope,
    Map.of("chat", DialoguePresenter.SurfaceProfile.semantic(chat)),
    clock, controls, labels, targets, status, tokens, () -> null);
DialogueInputSource input = renderer;
```

The renderer's seven-argument overload omits tokens and geometry. It and the full
constructor with null tokens still render output; their own `resolveChat` returns
empty. Resolve externally through the exact chat issuer store in that case.
An explicit store must be the identical object used by every directly known chat
surface, even if another store has the same scope and registry. Profile maps are
copied and directly known native surfaces under every key are checked. Custom
wrappers, sink/speaker/geometry callbacks and input `Target` wiring remain the
application's responsibility.

HUD now takes `(scope, packs, assets, channels, targets, clock, diagnostic)`;
NPC appends `speaker` before `diagnostic`. Input adapter constructors take
`(scope, profile, clock, cancellation, target, occlusion)` and optionally append
`RuntimeOptions.Budgets`; the short overload retains its existing budgets.
Catcher acquisition becomes `PrivateInteractionCatcher.open(scope)` in Java,
or `PrivateInteractionCatcher.Companion.open(scope)` in Kotlin. Spawn targets the
scope's owning instance. There is no separate player or generation parameter.

Renderer requests and direct surface contexts must use the player's UUID string
and the scope's host generation. `SurfaceContext.generation` independently retires
surface output and may differ. Every frame must identify its prepared/open
session. Invalid ownership fails the asynchronous operation before fallback,
callbacks, targets or acquisitions change; valid prior output stays usable.
A borrowed NPC binding must use the exact owning instance before its snapshot is
read. Foreign bindings cannot become follow or close callback sources.

`isCurrent()` requires validity, online state and exact instance. `matches()` also
requires player reference and generation. `hasCurrentConnection()` intentionally
ignores invalidation so accepted terminal sound and guarded restoration can run.
Invalidation is idempotent and generation is nonnegative. It fences work rather
than proving Core stopped or cleanup completed. Native cancellation invalidates
before its own stop work; ticket cancellation signals Core first. Direct Core
stops follow Core, and native completion observers run after Core completion.
Exceptional completion/deadline expiry can leave owned late resources: retain the
scope and keep the scheduler alive until final asynchronous cleanup settles.
Token resolution retains separate player/generation/online, expiry and current
semantic-target checks, including same-section rebasing with the original expiry.

The external Maven fixture contains complete [Java](../fixtures/minestom-consumer/src/test/java/example/NativePipeline.java)
and [Kotlin](../fixtures/minestom-consumer/src/test/kotlin/example/NativePipelineConsumerTest.kt)
custom pipelines. Both run real output ACK, native targets, chat capability input
through Core, owner rejection, retirement and owned entity cleanup using published
JARs and a public scheduler implementation.

### Proven frame rejection

`DialogueSurface.FrameRejected(message)` is an additive Presentation SPI marker
for a failed `SurfaceHandle.replace` that committed no owned output, input or
resource mutation. Native foreign-session and foreign-instance binding checks
use it. The presenter and native renderer keep the last physically acknowledged
view/page and reject observers associated with the offending attempted write;
newer queued work keeps its existing absolute deadline. A first rejected frame
may be retried before the original request deadline. Duplicate/lower-revision
observers associated with the rejected attempt cannot become successful by
acknowledging restored old output. Unattempted coalesced/stale views retain the
existing acknowledgement contract and are not individually preflighted.

The marker is recognized through `CompletionException`/`ExecutionException`
wrappers only. An ordinary failure with this marker somewhere in its cause chain
is still ordinary. Custom surfaces must make the no-mutation guarantee before
emitting it; partial writes, unknown errors, failed acquisitions, genuine lost
anchors and timeouts do not qualify. Those failures retain existing failure and
cleanup handling. Core remains free to stop when its own render operation fails.

Browse cursor changes commit on frame ACK. A rejected browse retains the matching
visible page and targets; validated native writes still retire old inputs before
publishing their new acknowledged targets. The existing adapter impulse and
surface-retirement fences remain in place. Refresh after rejection uses the last
physical ACK rather than replaying the rejected desired view.

A nested surface's `FrameRejected` certifies only that nested operation. Built-in
native surfaces preserve the marker only for their own proven early validation;
a sink, output/effect lease, release or acquisition failure cannot certify the
enclosing frame. After owned work may have begun, nested markers become ordinary
failures with their cause retained, and normal fatal/cleanup handling applies.

Browse observers also carry page-intent identity. An immediate duplicate shares
the current pending intent; returning to an offset after an intervening intent
creates a new one. Rejecting offset 3 keeps a newer queued offset 6 eligible for
its own write/ACK. Native page staging happens immediately before that selected
write in the existing serial queue. Unattempted pages on the same acknowledged
semantic view may coalesce and share the selected attempt's `ACCEPTED`/`STALE`
outcome; this does not promise that every superseded page was drawn. `ACCEPTED`
requires its selected actual frame ACK, while a selected invalid page is `STALE`
after that ACK. Already-stale target/view admission is still denied, and queued
work keeps the existing absolute deadline. A failed intent cannot be applied
later by an unrelated refresh.
