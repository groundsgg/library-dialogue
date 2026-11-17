# Package C client acceptance

Status: automated implementation candidate. The independent whole-branch review
found one Critical and ten Important issues; all are covered by regression-first
corrections in the candidate. The minor required-module completeness improvement
for the consumer-origin checker remains deferred; current consumer logs contain
all expected module origins. The extracted library's real-client
matrix is **pending**. Earlier 25570/25572/25576 prototype feedback is design
evidence, not acceptance of these new Maven artifacts. No merge/release claim may
replace the rows below with headless, packet or source-inspection evidence.

Accepted HUD client result (2026-11-15): the user confirmed the fixed
three-slot HUD card viewport on Grounds 26.3, build `70f7e67`. Only existing
cards are painted, unused slots remain transparent, the scrollbar sits outside,
and speech/footer positions stay fixed on the last page. Card text was accepted as left-aligned and vertically centered in that earlier
check; the later screenshot below reopened foreground vertical alignment, now separately
accepted on 1ff1860. This
accepts the fixed-viewport layout check;
the full matrix below, including public 26.2 and GUI-scale/two-client cases,
remains open. [CI for the tested build](https://github.com/groundsgg/library-dialogue/actions/runs/43741285806)
passed both required jobs.

Runtime evidence for that HUD client result:

| Artifact | SHA-256 |
|---|---|
| Grounds 26.3 pack | `a959fe0361131e898b62c0d4ea0deee5191bb8556cd2f36dfb3fbc4295c208ab` |
| dialogue-core | `febb99136f126137de8c8852eeb5b5196c7aefd2dac335510162c06f75c145a9` |
| dialogue-presentation | `6644449433312cce80eeb18382ace6826438cc190f1c090392f0e86a08d73b83` |
| dialogue-minestom | `fe18909401cba1e2e1eefdd4b3400d236611d34d4e0ad08cfa94642316a73627` |

The neutral personal-NPC demo uses loopback 25578, pack 25579 and the explicit
Grounds 26.3 profile. The old prototype is unchanged. Run the candidate build only
after current publication pins, independent consumers and the fork gate pass.
Record commit, actual JAR hashes and pack SHA-256 with each client result. Smoke
reports under `.evidence` deliberately set `visual_client_verified=false`.

| Check | Public 26.2 client | Grounds 26.3 client |
|---|---|---|
| Actual client metadata and correct pack formats | Pending visual run | Pending visual run |
| Pack accept, decline, download failure and removal | Pending | Explicit decline/chat fallback and re-allow/graphical return acceptedb133d53; download failure and removal pending |
| No pack request when opening/reopening a dialogue | Pending | Accepted7186e71: reopen/cancel and HUD/NPC/chat switching within one connection without request/download/interruption |
| HUD: fonts, centering, typewriter, choices and final End hint | Pending | Pending |
| GUI scale 1/2/3/Auto, window sizes and screen-edge clipping | Pending | Targeted layout pass4e5c121 for left/right cards at scales1/2/3/Auto and smaller window; foreground vertical alignment subsequently accepted1ff1860 |
| NPC: fixed world size, body-front yaw limit and near-head spacing | Pending | Pending |
| NPC: closed backs, no Z-fighting, hover and selection growth | Pending | Pending |
| Seven answers reachable through three cards; disabled/occluded/back targets | Pending | Pending |
| Chat: complete sections, all choices, current/stale click behaviour | Pending | Targeted pass4e5c121: non-first answer and stale links; full matrix still open |
| Use/Attack/Swap with empty hands, main/offhand items and shield | Pending | Targeted HUD pass: empty hands1ff1860; Use/Attack/Swap with main/offhand/shield7186e71 |
| Held button, fast clicks, scroll and jitter without accidental confirmation | Pending | Targeted empty-hand HUD pass1ff1860: fast clicks, held confirmation and wheel; other cases pending |
| Current inventory and server slot preserved; visible vs covered hotbar | Pending | HUD inventory/cursor/slot PASS7186e71 for all bindings and item modes; Use+shield covered-HUD return also confirmed |
| Language switch during reveal and action wait | Pending | Accepted4e5c121 in HUD/NPC, no restart/duplicate task |
| Optional cover/vignette/backdrop toggled independently | Vignette unsupported | Targeted NPC pass7186e71: backdrop remains while vignette/cover toggle; cancel clears all effects/panels |
| Ordinary hearts/hunger/hotbar covered; extra-heart limits noted | Pending | Targeted Use+shield HUD cover, controls and return accepted7186e71; extra-heart variants not client-tested |
| Normal looking remains open; strong turn/distance ends after grace | Pending | Targeted NPC pass7186e71: normal gaze/answers remain open, strong turn/distance end and restore play |
| Two visible clients: private gesture/sound and shared restoration | Pending | Accepted7186e71: A-only personalwave private to A; sequential cancel restores shared without disturbing other dialogue |
| Disconnect/instance change during active/preparing dialogue | Pending | Disconnect during reveal/reconnect clean pass7186e71; preparing/instance change pending |

Suggested first pass: accept the new pack, `/dialogue`, then `/renderer npc` and
`/renderer chat`; switch `/lang de` and `/lang en` during reveal and the task wait.
Use `/hidehud`, `/vignette`, `/npcbackdrop` to opt into the visual effects (default
off). `/confirm use|attack|swap` applies to the next `/dialogue`; physical keys use
Minecraft's client bindings. `/cancel` must restore native output and the common
NPC. Once layout/controls are confirmed, complete GUI-scale and two-client rows.

A new pack hash requires a fresh pack download/acceptance for that build. Opening
the dialogue afterwards must not produce another pack prompt. Headless demo runs
may mark a simulated connection's pack ready solely for server-side tests; they
are never evidence that a real client downloaded or rendered it.

NPC follow-up: the user confirmed hover selection/growth and fixed placement
while moving/off-screen on the accepted 70f7e67 demo. Highlighted cards still
needed more space from the speech and lacked the overflow indicator. The user
also requested a configurable inward angle. On build `eb8cb5e`, the user
confirmed the highlight-safe spacing, shared world scrollbar and hinged answer
column (default12 degrees, `/npcangle 0` for flat, `/npcgap 12` for the minimum
visible gap) as good. The same client run identified contention between wheel
and hover and requested up/down page arrows. Build72daede added navigation via
the configured confirmation input and manual-selection priority. The user found
the small targets beside the scrollbar fiddly and requested centered drawn
controls. The next candidate places small pixel chevrons centered above/below
the options with larger64×24 hit areas; the scrollbar remains an indicator.
Hints reserve separate space above the upper control. Arrow activation only
selects a page and never executes an answer. Disabled end arrows consume
confirmation. This refined navigation still requires its own client check;
the confirmed HUD remains reference.

2026-11-16 follow-up: the user clarified that only the arrows browse the viewport;
the mouse wheel continues to change selection. The selected answer must remain
selected even offscreen, with no highlight on unrelated pages. The screenshot
also showed a mirrored scrollbar crossing closed answer-panel backs. The new
candidate separates the viewport from semantic selection, rejects confirms of
invisible selections, keeps wheel/hover selection behaviour, and corrects the
back component's incorrect horizontal placement. The first correction 02ef31f
hid that component entirely; the user requested its visible back. The follow-up
restores it with horizontally reflected ink coordinates so the native back
rotation returns it to the same world track. Tests reconstruct actual font
spacing, entity rotations and folded world positions. The selection refinement
and corrected visible back still need targeted client checks.

## Targeted NPC acceptance, 2026-11-16

The user confirmed both independent arrow paging/selection and the visible,
aligned scrollbar back on `03623c2`. The wheel still changes the selection;
arrows can scroll the selected answer out of view and back without changing it.
That same run reported intermittent flicker while scrolling, so this is scoped
acceptance of the controls and back position, not smoothness or the full matrix.

| Accepted runtime artifact | SHA-256 |
|---|---|
| Grounds26.3 pack | `fe5df36b8589ea87e71ef679831e1c1eb13323214fd9f49f8c94b58eb36c5435` |
| dialogue-core | `febb99136f126137de8c8852eeb5b5196c7aefd2dac335510162c06f75c145a9` |
| dialogue-presentation | `ba399f5952f6a82fa5bf531a272f293dd85552ed3d2d1131627f1e6a6e7b17eb` |
| dialogue-minestom | `ad00267ac931a6ede4c97da43ff9163ff297b28506fac159c93e5832ff078600` |

The follow-up reuses physical viewport display slots, including transparent
unused slots on the partial last page, and batches visual metadata updates.
Native packet tests verify stable entity IDs, no spawn/destroy packets while
paging, coherent per-display metadata and cleanup of cached/pending slots. The user subsequently confirmed smooth arrow/wheel paging, including the partial
last page and back, on `6e3c91c`: “Ja passt”. Selection and scrollbar backs remained
correct. This accepts that targeted refinement; all other matrix rows remain
open. [CI for the tested product build](https://github.com/groundsgg/library-dialogue/actions/runs/43908532029)
is recorded separately from the client result.

| Smooth-paging runtime artifact | SHA-256 |
|---|---|
| Grounds26.3 pack | `fe5df36b8589ea87e71ef679831e1c1eb13323214fd9f49f8c94b58eb36c5435` |
| dialogue-core | `febb99136f126137de8c8852eeb5b5196c7aefd2dac335510162c06f75c145a9` |
| dialogue-presentation | `ba399f5952f6a82fa5bf531a272f293dd85552ed3d2d1131627f1e6a6e7b17eb` |
| dialogue-minestom | `7079efd610634bbadac163268f58be178775f311b5616a880c9884604b8c75f1` |


## Earlier iteration history

The entries below describe the check state at each earlier iteration; the latest
targeted result above takes precedence for the fixed card viewport.

2026-11-15 follow-up: the first real 26.3 client run reported graphical errors.
The supplied NPC screenshot shows vertical stripes across panel fills and frames;
this row is failed/pending correction, not accepted. The candidate now replaces
coplanar stripe painting with precomposed tiles using the approved prototype art.
Both pack profiles have new pins; visual confirmation of this corrected pack is
still required.

The user subsequently confirmed both HUD and NPC graphics as correct on
3953b83 / 26.3 pack `666bd90b852d1fc7ec0e330e0c75e74a59cbf86eec16585c2e5de028af96dcfb`.
This accepts the corrected blue/gold panel appearance and disappearance of the
stripes; it does not complete the full input/GUI-scale/two-client matrix.

The next requested change makes only the HUD more compact and adds answer stacks
below, left and right. NPC world size remains unchanged. The updated HUD layouts
and their left/right wrapping/centering need a fresh client check; use
`/renderer hud`, `/dialogue`, and `/hudchoices below|left|right`.

The user confirmed the compact HUD's below/left/right arrangement on 984232e,
then requested smaller answer cards matching the prototype's proportions.
The smaller individual cards on 9551511 were still unlike the requested HUD;
the user did not accept that styling. The next correction uses the actual original
HUD source pixels: one rounded modal, embedded speaker name, smaller bitmap text
and compact arrow-selected answer rows. Below/left/right placement remains.
This faithful HUD correction requires a fresh pack download and visual check.
The full client matrix above remains pending.

The user requested optional separation as well: both speech/list backgrounds and
individual answer cards. `hudStyle=shared|split|cards` is independent of
`hudChoices=below|left|right`; all nine combinations need a fresh visual check.
Use `/hudstyle` and `/hudchoices` in the current demo. The previous shared HUD is
preserved as default; no broad visual acceptance is inferred from this request.

On 54788db the user confirmed the three background variants, with one remaining
visual defect: option-card text was not centered. The follow-up centers the text
block within the full card and centers each wrapped line independently. Shared
and split-list text alignment remain unchanged. This alignment correction awaits
a fresh client check; the broader acceptance matrix remains pending.

The user confirmed both horizontal and vertical centering on 5f30730, then
clarified that only vertical centering is wanted. The follow-up restores left
alignment in cards while retaining vertical centering. The user also requested
an overflow scrollbar: it appears only for more than three answers, in all HUD
background/placement combinations, and shows the visible page proportion.
Both refinements require a fresh client check. The 5f30730 CI run failed in the
independent Minestom consumer test; the earlier log suppressed its detailed
report. Failure output is now printed for that check, and a new green head CI
is required before proceeding. This is not marked as a resolved CI failure
without fresh evidence.

On edeb89f the user requested two further refinements: no empty card placeholders,
and the scrollbar beside the cards. The next candidate paints exactly the
visible number of cards (including zero), shrinks the stack/footer, and places
the indicator 5px outside it. Left placement reserves a wider gap to the speech.
The edeb89f head CI completed successfully. The older 5f30730 consumer failure
was not reproduced locally or on edeb89f; detailed diagnostics remain enabled.
The count/outer-scrollbar refinement still requires its own visual check.

The user then clarified the viewport must retain space for three options even
when only one exists. The follow-up keeps the three-slot height and indicator
gutter reserved, paints only existing cards, and leaves speech/footer anchors
fixed across pages. It reserves the largest offered answer height even when that
answer is off-page. Tests cover a wrapped answer on another page, actual native
width with transparent gutters, and final messages without any options. The
fixed-viewport candidate still needs its own visual check.

The 6e0b4b1 CI diagnostics identified the intermittent independent-consumer
failure: Minestom Player.setInstance().join() processes the scheduler itself,
then asserts completion, racing the already running ticker. The fixture now
uses bounded Future.get() to await actual spawn completion; the standalone
headless smoke uses the same correction. The runtime library/fork is unchanged.
A new-head CI run is required to confirm the correction.

## Demo shutdown observation

Stopping the72daede preview with SIGTERM logged a demo cleanup timeout after
Minestom's shutdown started. The process exited, but that is not proof of
successful asynchronous host cleanup. Preserve this as an open example-shutdown
lifecycle check alongside the still-pending disconnect/instance-change matrix;
normal smoke teardown and automated library checks do not resolve this actual
process-shutdown observation.

## Demo command feedback, 2026-11-16

The user reported no response to `/renderer chat` and `/dialogue`. Read-only
inspection of the actual running process confirmed a registered command, the
correct connected-player context and the renderer already set to chat. The
start completed, then ended with `OUT_OF_RANGE`; the player stood about6.3
blocks from the NPC with a6-block limit. Moving to3–4 blocks and reopening
made the chat dialogue appear, explicitly confirmed by the user. The demo now
acknowledges renderer/language changes and explains this distance cancellation.
The library's leave policy is unchanged. This confirms command/start diagnosis;
The user subsequently accepted renderer feedback, selecting a non-first chat
answer, stale links having no effect after progressing, and live de/en switching
in HUD/NPC during reveal/action wait without restart or duplicate execution.
Accepted demo-App build:4e5c121; library JARs and pack are unchanged from the
accepted6e3c91c runtime. [Its CI](https://github.com/groundsgg/library-dialogue/actions/runs/43917885891)
passed both jobs. Other client-matrix rows remain open.

## HUD foreground alignment follow-up (2026-11-16)

The user confirmed the targeted left/right card GUI-scale and smaller-window
layout check on the existing 4e5c121 preview, then supplied a screenshot showing
that answer glyphs sit too low. That correction supersedes the vertical-alignment
part of the earlier positive feedback. The new candidate centers actual frozen
foreground bitmap ink, rather than the font line box, across one- and two-line
answers, accents and descenders. Text remains left-aligned; card geometry, the
three reserved slots, scrollbar and speech/footer positions are unchanged. NPC
rendering is unchanged. The new metrics/ascent files require a new pack load.
The automated test reads actual atlas pixels and emitted native font ascents;
it permits at most half a GUI pixel from integral-ascent rounding. This is
automated evidence. The user subsequently confirmed the corrected visual position
on the new 1ff1860 pack with “Ja, vertikal mittig”. This accepts the targeted HUD
card alignment check; the remaining client matrix stays open.

Accepted runtime: Grounds 26.3 on localhost:25578, product
`1ff18606fb3064a0b7f5fc6ac4998882a75095bf`; [CI](https://github.com/groundsgg/library-dialogue/actions/runs/43927232691)
passed both required jobs.

| Artifact | SHA-256 |
|---|---|
| Grounds 26.3 pack | `15a7eaf6163538a695ed859013e0a13e7e0f10a57d588062dd635d49a62ee1dc` |
| dialogue-core | `febb99136f126137de8c8852eeb5b5196c7aefd2dac335510162c06f75c145a9` |
| dialogue-presentation | `2cdd43bef760546d32c59c28e09110b4fedcaed279c0f199f3205294d79911b5` |
| dialogue-minestom | `8eae1e7a61afb38cfb8c9e13d6c101ff83dee24d206374fcf0a7f683527b74b5` |

## HUD empty-hand input accepted (2026-11-16)

On the same accepted 1ff1860 runtime and pack recorded above, the user confirmed
all three configurable confirmation bindings and their matching control hints:
Use/right-click also works in the air slightly beside the NPC, Attack/left-click
and Swap/offhand (default F) work with empty hands. Each binding was applied to
a newly opened dialogue. The separate fast-click, held-confirmation and wheel
check was accepted as controlled, without accidental multiple-step progression
or wrong-answer confirmation. User replies: “Alle drei funktionieren” and
“Ja, funktioniert kontrolliert”. This is targeted HUD empty-hand evidence;
main/offhand items, shield, inventory/slot restoration, other renderer cases
and the rest of the client matrix remain open. No product change or restart
was required for this acceptance record.

## Item and slot client check helper

The demo provides `/testitems main|offhand|shield` to cancel/await any prior
dialogue, save the original inventory once and equip a reproducible hand setup.
Slot3 is selected, with distinguishable stacks in slots1 and9. `main` holds an
iron sword, `offhand` has an empty main hand and12 torches offhand, and `shield`
holds a sword with a shield offhand. `/checkitems` ends/awaits the current
dialogue and reports PASS/FAIL against the equipped inventory, cursor item and
selected server slot; it does not repair differences. Run it immediately after
normal completion or use it to cancel, without scrolling outside the dialogue.
The original state can be restored with `/testitems restore`; the snapshot is
connection-local and does not survive reconnecting or restarting the demo.
Do not rearrange/drop/use items outside the dialogue during the comparison.
Run each mode with `/confirm use|attack|swap` followed by `/dialogue`. Repeat
with visible HUD and `/hidehud` enabled, including scrolling/confirmation and
normal end/cancellation. Actual client hand rendering and behavior remain a
manual check; server comparison alone is not visual acceptance.

## HUD Use with items and inventory accepted (2026-11-16)

On demo-App `7186e71624ab6bc48a1b7dac3edd3f578efd22ba`, the user confirmed
Use/right-click with all three `/testitems main|offhand|shield` setups, scrolling
and confirmation without item use/placement, and `/checkitems` PASS following
cancellation: “Alle drei funktionieren und PASS”. The separate normal-completion
check also passed: “Ja, PASS nach normalem Ende”. This confirms the scoped
server item/cursor/selected-slot comparison and client input behavior; it does
not replace Attack/Swap-with-items or visible/covered-HUD comparison. The demo
uses exactly the accepted 1ff1860 library JARs and15a7eaf6 pack listed above.
[Demo CI](https://github.com/groundsgg/library-dialogue/actions/runs/43930630173)
passed both required jobs. No new product fix was needed after the client check.

## Remaining HUD item bindings and cover accepted (2026-11-16)

On unchanged demo-App7186e71, the user confirmed Attack and Swap with each of
main/offhand/shield, including scrolling/confirmation and `/checkitems` PASS,
without unintended item use or hand swapping: “Beide Belegungen: alle Varianten
PASS”. Together with the previous Use check, all three HUD bindings are accepted
for the three equipped setups. Empty hands were accepted separately.

The separate Use+shield `/hidehud` check was also accepted: hotbar, ordinary
hearts and hunger covered, dialogue/selection usable, normal HUD visible again
after `/checkitems`, and inventory comparison PASS. Reply: “Abdeckung, Bedienung,
Rückkehr und PASS passen”. This accepts that specific cover/restoration case,
not every binding/equipment/extra-heart combination or other renderer. Runtime
JARs and pack are identical to the preceding accepted7186e71 checks. The rest
of the public26.2/Grounds26.3 client matrix remains open; no product change or
restart was needed.

## NPC leave policy and reconnect accepted (2026-11-16)

On unchanged demo-App7186e71, the user confirmed that ordinary looking and
viewing the NPC answer panels keep the dialogue open, while separately testing
a strong turn and walking beyond the dialogue range ends it, removes the panels
and restores normal play: “Beide Abbrucharten und Rückkehr passen”.

The user also disconnected while text was revealing and reconnected: the normal
HUD returned, old panels were absent and a new nearby `/dialogue` worked cleanly.
Reply: “Wiederverbinden ist sauber”. This accepts disconnection during active
reveal and the clean reconnect, not interruption during preparation or an
instance change. Runtime JARs and pack match the previous7186e71 acceptance
records; the remaining client matrix stays open. No product change or restart
was needed for these results.

## Independent effects and no repeated pack loading accepted (2026-11-16)

On unchanged demo-App7186e71 at localhost:25578, the user confirmed that the
NPC backdrop remains while vignette and HUD cover are each toggled on/off, with
each toggle affecting only its own effect; `/cancel` removes all dialogue
effects and panels. Reply: “Effekte unabhängig, Cleanup passt”.

Repeated dialogue open/cancel and live HUD/NPC/chat switching within the same
connection were accepted without a new pack request, download or loading
interruption: “Keine erneute Pack-Abfrage oder Ladeunterbrechung”.

The preceding missing-pack report was not reproduced on the specified local
demo: HTTP download from Linux and Windows returned the expected866316-byte
15a7eaf6 pack; the local server initially had no player while the current client
log showed a Stage connection. After connecting to localhost:25578, the user
confirmed visible NPC panels. Read-only runtime inspection confirmed the
expected manifest ready and requested/effective surface NPC. No product, pack
or runtime change was made to obtain that result; it is not a claim about Stage
pack behavior. The two subsequent explicit replies above accept these scoped
local checks. Pack decline/failure/removal and the rest of the matrix remain open.

## Two visible clients accepted (2026-11-16)

On the unchanged7186e71 demo with the recorded library JARs/pack, the user
tested two visible nearby clients, each with its own NPC dialogue. After the
initial greeting, only A executed `/personalwave`: only A saw/heard the personal
gesture and B retained its independent presentation. Cancelling A restored
the common NPC for A without disturbing B's ongoing private dialogue; then
cancelling B restored the common NPC for B. Reply: “Mit zwei Clients getestet:
Trennung und Rückkehr passen”. This accepts that real two-client private
render/sound/restoration case, not the remaining public26.2, pack-failure,
preparation-interruption or instance-change cases. No product change or
restart was required.

## Explicit pack decline accepted (2026-11-17)

The demo was restarted on documentation HEAD
`b133d5358a170a4a8a4b436caaff230dd8f9e170`, with shared demo source byte-identical
to7186e71 and the same verified1ff1860 library JARs/15a7eaf6 pack recorded above.
The initial attempt did not establish a decline: the client loaded a cached
pack and the saved visible127.0.0.1:25578 entry still had packs enabled; the
separate hidden localhost entry is distinct client configuration. No server
fix or client configuration write was made by the agent.

The user then set the exact visible server entry to Prompt, connected through
that entry and explicitly declined the prompt. Requesting NPC output produced
a complete usable chat dialogue with clickable choices: “Abfrage abgelehnt;
Chat-Fallback funktioniert”. This accepts explicit decline and the resulting
chat fallback. Re-enabling/reconnecting remains a separate pending check, as
do actual download failure and removal during a session. No product change or
restart was needed for the successful decline check.

## Re-allowed pack graphical return accepted (2026-11-17)

After the explicit decline check, the user re-enabled/allowed the pack on the
same visible local server entry, reconnected and opened NPC dialogue again.
Reply: “Grafische Darstellung funktioniert wieder”. This accepts the return
from declined-pack chat fallback to graphical NPC panels after allowing the
pack. The running documentation HEADb133d53 still uses the accepted7186e71 app
source and1ff1860 library JARs/15a7eaf6 pack. Download failure and removal during
a connection remain separate pending checks. No product fix was required.

## Explicit pack failure/removal helper

`/packtest remove` removes only this connection's demo contribution, invalidates
its application-owned readiness and refreshes the current dialogue into chat.
It does not cancel or restart the semantic session. `/packtest fail` then
requests a fresh optional pack identity at an unserved local HTTP404 URL with
an uncached hash. Allow the request if prompted: this tests an actual failed
client download, rather than a fabricated success/failure status.
`/packtest restore` requests a fresh identity for the original healthy URL/hash;
client success can restore the preferred graphic surface.
`/packtest status` reports the last actual client status (or explicit test
removal), readiness and requested/effective output. Only the test commands
change the pack: normal connection behavior and dialogue starts are unchanged.
The removal case explicitly invalidates application-owned pack state; it does
not prove detection of an unsolicited external removal. Both real-client
failure/removal/recovery checks remain pending until confirmed.
