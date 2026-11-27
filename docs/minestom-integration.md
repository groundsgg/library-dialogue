# Minestom integration

`dialogue-minestom` adds one local connection owner over the existing core and
presenter. HUD, NPC panels and chat share graph/reveal/conditions/choices/actions,
layout, UI labels, native encoder and ownership contracts. The native host has no
Scene, NPC, Grounds-module, JSON-loader or pack-builder dependency.

## Custom native pipelines

For custom construction, use one supported `MinestomConnectionScope(player,
hostGeneration, instance)` per dialogue session. Share its exact reference and a
fresh `SurfaceInputTargets` across chat/HUD/NPC, `ChatInputTokens`,
`MinestomDialogueRenderer` and `MinestomInputAdapter`. Constructors derive the
borrowed player and instance from the scope. Use public
`input.DialogueInputSource` to connect rendered targets and capabilities to Core.
Renderer profile maps are copied; directly known native surfaces and explicit
chat issuer stores must have the same owner and registry. Opaque wrappers and
custom callbacks remain the application's wiring responsibility.

Prepare/open with the player UUID string, matching host generation and session.
A surface's retirement generation is independent. Rejecting a foreign request,
frame or NPC binding preserves valid output; bindings from another instance are
rejected before snapshot callbacks. Publish input only after acknowledged output,
and use its exact revisions/visit/surface generation when submitting to Core.
A proven `DialogueSurface.FrameRejected` preserves the last physically
acknowledged view and viewport and fails only observers associated with that
attempt. Newer queued work keeps its original deadline; unknown/partial writes
retain normal failure handling. The marker is recognized only through standard
completion/execution wrappers, never an arbitrary cause chain. Core's own
response to a failed render remains Core-owned. A nested callback's marker
certifies only its own operation; built-in native writes downgrade it to ordinary
failure unless their own early validation proves the enclosing frame is unchanged.
Browse correlation includes page-intent identity, and staging/drawing are one
serialized attempt. Superseded unattempted pages on the same acknowledged view
may share the selected attempt's outcome; every superseded page need not be drawn.
A layout resolver failure while staging a browse fails that selected browse group
before replacement begins. Prior acknowledged output and targets remain usable,
and newer queued work keeps its existing deadline. Actual replacement failures
retain their normal failure handling.
The short renderer overload and explicit null tokens preserve output, but require
external resolution through the surface's issuing token store.

Invalidate at the native lifetime boundary, retain resources for late cleanup,
and keep the scheduler available until close completes. `isCurrent` fences work;
`hasCurrentConnection` permits guarded restoration after invalidation. Core handle
stops, ticket cancellation and natural completion retain their own ordering; scope
invalidation alone cannot prove Core completion or resource release. Borrowed
NPCs, players and shared services are never closed by the pipeline.

See the [candidate migration](migration-2.0-candidate.md#native-connection-scope-and-input-source)
for exact Java/Kotlin signatures and the executable [external Maven example](../fixtures/minestom-consumer/src/test/java/example/NativePipeline.java)
for output ACK, capability input, owner rejection and close.

## Exact profiles

| Profile        | Explicit server runtime                                    | Protocol | Resource pack | Native vignette                        |
| -------------- | ---------------------------------------------------------- | -------- | ------------- | -------------------------------------- |
| `public-26.2`  | `net.minestom:minestom:2026.10.07-26.2`                    | 776      | 88.0          | Unsupported transport                  |
| `grounds-26.3` | `2026.11.08-26.3-grounds`, explicit complete local runtime | 777      | 97.1          | Injected `PostEffectsPacket` transport |

The public Maven artifact uses Minestom as compile-only. Applications declare their
runtime explicitly. The normal build needs Maven Central and JDK 25, without
Grounds credentials. The opt-in fork profile does not publish local file
classpath entries or add the public Minestom JAR alongside the fork.

## Host and resource pack

Create `MinestomDialogueOptions` with budgets, matching `MinecraftPackProfile` and
`AssetBundle`, shared `OutputChannels`, `UiLabelSource` and distance grace. Create
one `MinestomDialogueHost` and retain it for its intended host lifetime. Its
request dedupe register has a 65,536-entry cap without silent ID reuse.

Compose `AssetBundle.entries()` into the application's normal pack, preserving
the verified `dialogue` namespace and metrics pin. The application owns
`pack.mcmeta`, ZIP/HTTP hosting and pack requests. Before sending a pack:

```java
host.packState().expect(player, connectionGeneration, packId, assets.pin());
player.sendResourcePacks(packRequest);
```

The host observes native pack-status events. Only `SUCCESSFULLY_LOADED` for the
registered concrete player connection, generation, pack UUID and manifest pin
allows graphics. Acceptance/download alone does not. Keep pack IDs unique for
changed registrations. Retired UUIDs cannot be reused on the same connection;
the bounded history accepts at most 65,536 distinct registrations. Opening a dialogue never sends or switches a pack.

`MinestomDialogueRequest` supplies a fresh request ID, concrete local `Player`,
trusted connection generation, `ValidatedDialogue`, versioned `DialogueServices`,
context, locale, preferences, borrowed speaker bindings, optional `ActorPort`,
`InputProfile` and opening impulse. The [independent consumer](../fixtures/minestom-consumer/src/main/java/example/Consumer.java)
shows the complete public API and actual cleanup. The standalone demo also shows
native anchors, resource-pack hosting and live changes.

The start ticket also exposes `refreshLocale` and `updatePreferences`. These
retain the latest pending intent while preparation or an initial write is still
in flight, then reconcile it through the same core handle before the public
native `Started` result. Already admitted transport output cannot be unsent;
startup acknowledgement waits for the corrected output. Cancellation drains its
predecessor cleanup barrier even if this ticket never attached a session.
Uncertain cleanup fences further starts on that concrete connection.

## Three surfaces and fallback

`hud` projects encoded widgets into a registered action-bar lease. `npc` projects
the same widgets into private text-display front/back pairs. `chat` receives
semantic text with clickable opaque capabilities, routed through normal core selection and confirmation, without glyph measurement or
one message per reveal character. Seven choices page through three graphic cards;
chat keeps every available choice reachable. All 64 validated choices fit its
64-token budget; an extra generic hint token is unnecessary when choices exist.

A missing/revoked pack, missing glyph, overflow, unsupported native raster,
font-pin mismatch or a suppressed HUD source falls back once to semantic chat.
The same core session, visit, actor and action state survive, including failures
before the first frame. Requested/effective surface and diagnostics remain
separate through the start ticket. There is no automatic upgrade after a later
pack load. Unknown transport, cleanup and timeout failures are not hidden by
fallback. An explicit later preference change may request graphics again.

Default host surfaces use the neutral profiles. `DialoguePresenter`,
`MinestomDialogueRenderer`, the native surfaces and widget encoder are public
building blocks for a custom presentation pipeline; the default host's internal
factory is not a general dependency-injection framework.

## Speakers and personal actors

Bindings contain generation, instance and a snapshot supplier for feet, body yaw,
look origin and validity. They borrow the existing body; the library never closes
that body. Logical speaker IDs come from definitions, not translated names.
Declared anchors are checked before entry effects. A provider/action wait retains
the previous logical anchor; a single declared anchor can position an initial
busy frame. An ambiguous initial NPC frame without an established anchor rejects
rather than guessing.

NPC panels are upright at a fixed 0.32 scale (0.008 blocks per GUI pixel), with
speech's lower edge at feet + 1.9 blocks. They follow 25% of viewer bearing within
±15°, without pitch or screen-edge enlargement. Front/back grow together to 1.08
over 150 ms; hit rectangles stay unscaled. Back depth is initially 0.012 blocks.
Native one-line/bearing offsets are shared with the hit-plane proof tests.

For personal gestures, use the [published NPC consumer](../fixtures/npc-dialogue-consumer).
Its `ActorPort` prepares hidden private presentations, publishes once during
activation, cues private clips/head look and closes only the owned presentations.
It reuses NPC 1.0.0's engine, clock, rendering and viewer-only sounds. The shared
parent survives. The base adapter requires no NPC package. Scene/Map/Grounds
adapters and quest services remain later integration work.

## Controls, zones and lifecycle

Confirm is a Minecraft action (`USE`, `ATTACK`, `SWAP_HANDS`), not a guaranteed
physical key. Defaults accept Use + Swap hands, with Use as the hint action. The
opening impulse cannot confirm. A 350 ms quiet-period heuristic groups repeated
use/interact packets; it cannot prove physical key release. A bounded 256-packet
queue preserves arrival time and the acknowledged target visit, render and state
revision across output waits. A confirm admitted before a selection/page change
cannot confirm the later selection; same-visit scroll intent may continue.

Scroll walks all available choices while the server-held slot stays unchanged.
Visible hotbars get a correction + ping fence; only its own connection's matching
pong resets virtual history. Covered hotbars do not continually send corrections.
A private interaction entity catches empty-hand air use/attack for graphical
surfaces. Ambiguous arm-animation packets are not treated as Attack. This
public-base route has scoped hand/held-button client results in the acceptance matrix.

NPC hover uses the exact published world plane and original rectangles. A
stationary hover does not undo manual scroll, including during confirmation and
page changes. After wheel or arrow selection, hover resumes when gaze moves at
least1.5 degrees or the eye moves more than0.08 blocks; sub-threshold jitter
keeps the manual selection. A newly changed hover target selects at most and
needs a fresh confirm impulse. Back faces and block-occluded fronts do
not hover or confirm. A click on an unavailable card is consumed without
confirming a different, previously selected answer. Geometry retains bounds for
all rendered cards, including unavailable ones; the original three-argument
Geometry constructor remains available. Wheel-selected answers can still be
confirmed while looking at the NPC, provided their center has a clear front-side
sightline; directly pointing at an exposed part of the selected card also works.
Native collision shapes include partial blocks. Default hit testing
assumes first-person view; unregistered third-person cameras are not supported.

Look-away defaults to >110° for 600 ms, with reset below 95°. NPC and visible
answer fronts, including disabled visible cards, protect the angle timer. They
never protect distance: >6 blocks for 600 ms independently ends the dialogue.
Disabling look-away leaves distance, anchor and connection checks active. Invalid
numbers, invalid anchors, disconnect and instance changes stop immediately.
Speaker changes reset the leave timers. Local sessions end at backend/proxy
transfer; no live handles migrate and no remote player is looked up as local.

## Optional visuals and budgets

Preferences `hideHud`, `vignette`, `npcBackdrop` are independent, default false.
The HUD cover is a spatial 128-GUI-pixel soft fade, masking ordinary hotbar,
hearts and hunger visually. It does not change inventory, health, hunger, game
mode or client world age. Extra/stacked HUD rows are outside the initial claim.
The optional NPC focus plane is 2.88 × 3.84 blocks, at most roughly 25% black.
NPC surfaces accept `npcEffectsFadeMs` as an integer from 0 through 1000, with
0 (the default) applying the vignette immediately. With the Grounds native
vignette assets, 250 runs a client shader over 250 ms on entry and normal exit.
The server keeps the entry effect installed and removes the entry and exit
effects together after a 500 ms close grace period. A new dialogue for the
same player waits for the preceding dialogue to finish cleanup. The host skips
the grace period when the cleanup budget is insufficient or the connection,
pack or anchor is no longer valid. Other durations and older Grounds bundles
use the existing stepped vignette when the intermediate assets are available. The NPC backdrop
appears at full opacity with the panels, and the backdrop, panels and input
are removed immediately on close. The fade setting leaves HUD coverage
unchanged and is independent of reduced motion.
A public unsupported effect transport diagnoses the request without breaking HUD.
Grounds effects are explicit, namespaced post effects, without vanilla shader
replacement, world-age control, mod or dialogue-time pack swap.

Limits per active connection: 32 UI displays (including pending and closing candidates) + 1 interaction catcher, 64 active
128-bit random chat tokens with 30 s TTL, 64 KiB encoded frame and 32 MiB asset
bundle. Native glyph coverage is frozen printable ASCII/Latin-1 plus supplied
punctuation, half-pixel horizontal spacing and even ascents -64…256. Unsupported
content remains intact in chat. No normal build/server start rasterizes fonts.

Default waits are 2 s text, 5 s preparation/activation/surface change, 10 s
external actions and 30 s cleanup, all configurable. Native scopes receive the
configured cleanup budget. Preparation timeout keeps late acquisitions owned;
it is not proof of release. New starts wait confirmed prior cleanup. Host close
removes its own event node/command route and drains core/input/output/actors;
it does not stop a shared Minestom runtime.

## Explicit fork checks

Keep the complete fork runtime in an untracked absolute `.local` classpath file;
never commit it or credentials. It must contain exactly
`minestom-2026.11.08-26.3-grounds.jar`, its complete runtime JARs and no public
Minestom or replacement application libraries. Run from the repository root:

```sh
./gradlew check publishAllPublicationsToReviewRepository
python3 tools/verify_public_artifacts.py
./gradlew :dialogue-minestom:checkGroundsProfile -PgroundsClasspathFile=/absolute/runtime.local
./gradlew -p examples/standalone-minestom runGroundsProfile --args=--smoke \
  "-PdialogueRepository=file:$PWD/.evidence/maven" -PgroundsClasspathFile=/absolute/runtime.local
```

The separate source profile compiles all adapter sources plus the typed effect
transport, runs its server-side tests and records only dependency filenames/hashes
in `.evidence/grounds-profile.json`. The typed transport source also ships in the
sources JAR under `grounds-26.3/`; it is not loaded in the public runtime.
The demo's tiny Grounds application transport is compiled against the fork while
its application libraries still come from the Maven repository.

Use the equivalent command in `fixtures/npc-dialogue-consumer` for personal NPCs,
with package-read credentials. Omit `--args=--smoke` for the 25578/25579 client demo.
After `installDist`, `tools/minestom_smoke.py --profile grounds-26.3
--grounds-classpath-file /absolute/runtime.local --consumer npc` checks the status
socket and downloadable 97.1 pack. `--client /absolute/26.3.jar` additionally
validates installed client metadata before launch. These checks do not claim
visual acceptance; see the separate client matrix.

## Preview graphics

The neutral native panels use the original blue/gold pixel-frame art from the
approved dialogue prototype. Border and fill are baked into each adjacent tile;
no overlapping coplanar stripe glyphs paint their layers at runtime. Fronts use
the dark-blue normal or gold-selected frame, and backs use closed dark material.
The text palette remains theme-controlled; panel colours are part of the verified
asset bundle. Replacement panel art must therefore supply a matching bundle pin.
The baked panel heights are 24, 28, 32, 40, 48, 56, 64 and 72 GUI pixels (the compact HUD and standard NPC nameplate/cards); other custom heights diagnose
`UNSUPPORTED_RASTER` and fall back to chat. Widths remain bounded by 512 pixels.

## Stable NPC display slots

Native answer displays are owned by viewport slot, independent of the semantic
choice ID. Paging reuses the same front/back entities; spare slots on a partial
page keep an empty transparent component and remain inside the existing32-display
connection budget. No-answer nodes and cleanup release them. If a new frame
needs the budget, hidden slot reservations are evicted before acquisition.

Each pair retains its asynchronous preparation, so an overlapping replacement
can adopt a still-pending slot and retired slots wait for preparation before
release. Preparation/removal failures propagate. Text, frame, pose and scale use
Minestom's metadata batching and one native packet bundle per visual frame.
New slot contents snap to the correct pose; hover growth of the same choice keeps
its animation. Semantic hit targets are still published only after output.

## NPC answer column

NPC answers reserve a minimum visible gap from the speech even while selected
cards grow. `effects.npcChoiceGap` is that minimum in GUI pixels (default12,
range0–64 in half-pixel steps); the shared presenter adds the half-width needed for selection growth.
Vertical card gaps retain their existing layout values. `effects.npcChoiceAngle`
is an inward fold around the answer column's left edge (default12 degrees,
range0–35); 0 keeps the column flat. Both settings are per viewer and change live
without restarting the dialogue. The demo exposes `/npcgap 12` and `/npcangle 12`.

Use `effects.npcVerticalOffset` to raise the NPC dialogue and its hit targets
for a taller model. It defaults to 0 and accepts values from 0 through 16 blocks.
The offset follows the NPC while preserving its feet and look origin for the
host's interaction policies.

Answers, their hints and the overflow indicator share the same hinged plane.
Native front/back quaternions, positions, hover rays, block occlusion and leave
zones use that plane. Selection growth keeps the original hit rectangles.
Angle/gap reconfiguration snaps the new plane before targets are acknowledged;
ordinary selection growth stays animated. NPC following preserves the fold,
bounded body-front yaw and constant world scale.

More than three answers use the same `WidgetFrame.Scrollbar` and `ChoiceViewport`
as the HUD. The native encoder provides one additional private front/back pair,
using scroll glyphs and an explicit paint baseline. It adds no clock or NPC-library
dependency. Empty/fewer-choice nodes release that pair. NPC page controls sit
centered above and below the answer column, separate from the indicator-only
scrollbar at the right. Small16×8 pixel-drawn chevrons have generous64×24 hit
rectangles; hints reserve space above the upper target. Both controls stay on
the same folded plane, with fixed positions across pages. Each
confirmation over an enabled arrow only changes the visible three-answer page.
`ChoiceViewport.Cursor` keeps the semantic selected ID, even when it is outside
the visible window. Such a page paints no selection; browsing back restores its
highlight. Disabled-only pages remain browseable. A real selection change (wheel
or hover) reveals the selected answer again. Visit changes reset the cursor;
reveal/locale refreshes preserve it and shorter offers clamp the page.

`ChoiceViewport.Controller` changes presentation state without a core input.
The native renderer redraws through its existing coordinator and waits for real
output acknowledgement, with the same fallback/deadline/cleanup rules. Current
issued target identity rejects queued confirms from a previous page even if the
core state/render revisions are unchanged. A selected but offscreen answer is
never confirmed invisibly. Disabled end controls consume confirmation.

Arrow glyphs use a dedicated frozen32×256 two-cell atlas in the existing native
font positions. The scrollbar is visible on both faces. Its back ink is reflected
inside the full component bounds before the native180-degree turn, so the track
occupies the same world position on both sides (only the0.012-block back depth
differs). Arrows remain front-facing controls. The existing display pair and
per-connection budget remain unchanged.
The default native world scrollbar supports track heights up to192 GUI
pixels; unsupported custom geometry falls back intact to chat. All displays
remain inside the shared per-connection32 budget, including pending/closing ones.

## Optional typing sound

Set `PresentationPreferences.effects()["typingSound"]` to `"true"` to enable a
quiet vanilla note-block hat tick during natural text reveal on HUD and NPC
surfaces. The default is silent. Ticks are private to the conversation player,
limited to one per 90 ms, and only accompany newly revealed letters or digits.
Whitespace, punctuation, manual reveal skips, full-text rendering and chat
fallback stay silent. Reduced motion does not disable audio. No resource-pack
change is required.

## Optional choice sounds

Set `PresentationPreferences.effects()["choiceSounds"]` to `"true"` to enable
quiet private vanilla button clicks for answer navigation and confirmation on
HUD and NPC surfaces. This is independent of `typingSound` and silent by default.
Navigation plays only for accepted selection changes after successful display,
limited to one per 85 ms. Confirmation uses a distinct lower tone, including
choices that finish the conversation. Initial selection, repeated hover,
provider refreshes, reveal skips, plain Continue and chat fallback stay silent.
No resource-pack change is required.

## Compact HUD answer placement

The HUD reuses the original prototype's rounded modal and frozen Monospaced
bitmap text at 75% native size. The speaker sits inside the speech region. Answers
are compact text rows in a shared darker region, with a gold `> ` marker and gold
text for the selected answer. This shared style is the default. NPC panels,
fonts, hover growth and fixed world scale remain unchanged.

`PresentationPreferences.effects()` accepts `Map.of("hudChoices", "left")`;
apply changes through the normal `updatePreferences` path. Valid values are
`below` (default), `left`, and `right`. The demo provides
`/hudchoices below|left|right` for live changes. The below modal is 240 GUI pixels
wide; shared/split side layouts combine a 162px speech region, a 135px answer region and a 3px
gap, for 300px total. Answer text widths are 222px below and 117px beside the
speech; rows are 10px or 20px high depending on wrapping. Three answers remain
visible through the shared viewport. Unsupported text still falls back intact
to chat.

`effects.hudStyle` independently selects `shared`, `split`, or `cards`:
shared keeps one rounded modal; split gives the speech and answer list separate
rounded backgrounds; cards gives every visible answer its own compact background.
All three work below, left and right. `/hudstyle shared|split|cards` changes the
current demo live, preserving selection, reveal and the current node. Separated
below layouts leave a 6px gap after the speech; individual cards add 10px vertical
padding and 3px gaps. Columns and bitmap text metrics stay the same. The individual-card variant
centers the visible foreground glyphs vertically while keeping all lines left-aligned
with a 9px inner margin. Frozen per-glyph ink bounds exclude transparent font-cell
padding and spaces; the complete wrapped block is centered once and rounded to
the native integral ascent. Custom metrics without ink bounds retain line-box
centering through the default `FontMetrics.inkBounds` method. Every background is a precomposed texture contribution,
with real transparent gaps instead of
layered coplanar rectangles. Unknown style values use the typed chat fallback.

Card backgrounds are generated for exactly zero, one, two or three visible
answers. Unused cards are absent, including the final message without answers.
The viewport always reserves three slots; missing cards are transparent, and the
speech/footer anchors stay fixed across pages. The largest offered answer
(including its selected marker) determines the common row height. A card scrollbar sits
5px outside the cards; the left layout reserves a 15px gap to the speech so it
fits between them. Card canvases reserve the indicator gutter even without overflow: 249px below,
309px on the right and 312px on the left. Native background advances are frozen
from actual PNG ink, so transparent slots/gutters do not change centering.

When more than three answers exist, a narrow scrollbar appears at the right edge
of the answer region in every HUD style/placement. Its gold thumb represents the
visible proportion and current viewport offset, including a partial last page.
At most three answers produce no scrollbar. It is a visual indicator; scrolling
and confirmation keep the same input path. `WidgetFrame.Scrollbar` carries the
track/thumb rectangles independently of Minestom; the native encoder draws
adjacent, nonoverlapping segments with frozen glyphs. It does not add another
session, viewport or input target.

The framework-free presenter produces the same semantic widgets and choice IDs.
A `WidgetFrame.Chrome` describes the shared background and stable baseline;
`canvasBounds()` includes it and negative left-column coordinates. The native
encoder draws that background once, then places the shared widgets. HUD metrics
have their own verified pin derived from the asset bundle. The frozen source
pixels are in `tools/prototype-hud`; normal builds and server starts do not
rasterize fonts.

`SurfaceProfile` optionally resolves a `LayoutProfile` from current preferences.
Existing constructors keep fixed-profile behaviour. `LayoutProfile.Style.PANELS`
and shared padding remain the default for existing consumers, including NPC.
`choicePadding()` remains available for custom panel profiles. Only explicitly
nondefault style/padding contributes additional profile-pin fields.

The standalone demo application owns engine shutdown: it configures
`minestom.shutdown-on-signal=false` before Minestom initializes, then its single
application signal hook awaits dialogue-host and NPC cleanup before stopping
Minestom. Competing engine/application hooks can otherwise stop the scheduler
before asynchronous resource release completes. This setting belongs to the
standalone application; the library never stops a borrowed Minestom engine.
The optional `--shutdown-probe` plus `tools/minestom_smoke.py --active-shutdown`
verifies that order with an active server-side dialogue and a real SIGTERM.

## Focused NPC target test

The demo command `/targettest` opens a separate content variant with the second
answer unavailable and the first always available. It places two fixed stone
pillars at x=-3/+3, z=2 in the demo world as real line-of-sight obstacles. They
stay in that disposable world for repeated tests; no map files are changed.
The ordinary `/dialogue` still opens the unchanged seven-answer content.
Use the target variant to check unavailable-card clicks, blocked selected
answers and panel backs, then verify normal front-side wheel confirmation.
These are test fixtures, not a new library condition or content format.
