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
changed registrations. Opening a dialogue never sends or switches a pack.

`MinestomDialogueRequest` supplies a fresh request ID, concrete local `Player`,
trusted connection generation, `ValidatedDialogue`, versioned `DialogueServices`,
context, locale, preferences, borrowed speaker bindings, optional `ActorPort`,
`InputProfile` and opening impulse. The [independent consumer](../fixtures/minestom-consumer/src/main/java/example/Consumer.java)
shows the complete public API and actual cleanup. The standalone demo also shows
native anchors, resource-pack hosting and live changes.

## Three surfaces and fallback

`hud` projects encoded widgets into a registered action-bar lease. `npc` projects
the same widgets into private text-display front/back pairs. `chat` receives
semantic text with clickable opaque capabilities, without glyph measurement or
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
queue preserves arrival time and target visit/revision across output waits.

Scroll walks all available choices while the server-held slot stays unchanged.
Visible hotbars get a correction + ping fence; only its own connection's matching
pong resets virtual history. Covered hotbars do not continually send corrections.
A private interaction entity catches empty-hand air use/attack for graphical
surfaces. Ambiguous arm-animation packets are not treated as Attack. This
public-base route still needs explicit 26.3 hand/held-button client acceptance.

NPC hover uses the exact published world plane and original rectangles. A
stationary hover does not undo manual scroll. A newly changed hover target selects
at most and needs a fresh confirm impulse. Back faces and block-occluded fronts do
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

Limits per active connection: 32 UI displays + 1 interaction catcher, 64 active
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
