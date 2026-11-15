# Minestom integration

`dialogue-minestom` adds one local connection owner over the existing core and
presenter. HUD, NPC panels and chat share graph/reveal/conditions/choices/actions,
layout, UI labels, native encoder and ownership contracts. The native host has no
Scene, NPC, Grounds-module, JSON-loader or pack-builder dependency.

## Exact profiles

| Profile | Explicit server runtime | Protocol | Resource pack | Native vignette |
|---|---|---|---|---|
| `public-26.2` | `net.minestom:minestom:2026.10.07-26.2` | 776 | 88.0 | Unsupported transport |
| `grounds-26.3` | `2026.11.08-26.3-grounds`, explicit complete local runtime | 777 | 97.1 | Injected `PostEffectsPacket` transport |

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
It reuses NPC 0.2.0's engine, clock, rendering and viewer-only sounds. The shared
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
public-base route still needs explicit 26.3 hand/held-button client acceptance.

NPC hover uses the exact published world plane and original rectangles. A
stationary hover does not undo manual scroll, including during confirmation and
page changes. After wheel or arrow selection, hover resumes when gaze moves at
least1.5 degrees or the eye moves more than0.08 blocks; sub-threshold jitter
keeps the manual selection. A newly changed hover target selects at most and
needs a fresh confirm impulse. Back faces and block-occluded fronts do
not hover. Native collision shapes include partial blocks. Default hit testing
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

## NPC answer column

NPC answers reserve a minimum visible gap from the speech even while selected
cards grow. `effects.npcChoiceGap` is that minimum in GUI pixels (default12,
range0–64 in half-pixel steps); the shared presenter adds the half-width needed for selection growth.
Vertical card gaps retain their existing layout values. `effects.npcChoiceAngle`
is an inward fold around the answer column's left edge (default12 degrees,
range0–35); 0 keeps the column flat. Both settings are per viewer and change live
without restarting the dialogue. The demo exposes `/npcgap 12` and `/npcangle 12`.

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
confirmation over an enabled arrow selects the first available answer on the
adjacent three-answer page without confirming that answer or executing an
action. Pages without available answers are skipped. Disabled end arrows are
muted. Their hits, including blocked/back hits, consume confirmation without
falling through to the current answer. Targets are stamped and validated against
the current issued offers, state, visit and surface generation. Arrow glyphs use
a dedicated frozen32×256 two-cell atlas registered in the existing native font
positions, appear on the front only and need no additional display pair.
The default native world scrollbar supports track heights up to192 GUI
pixels; unsupported custom geometry falls back intact to chat. All displays
remain inside the shared per-connection32 budget, including pending/closing ones.

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
centers its text vertically while keeping all lines left-aligned with a 9px
inner margin. Every background is a precomposed texture contribution,
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
