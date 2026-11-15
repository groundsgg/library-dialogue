# Package C client acceptance

Status: automated implementation candidate. The independent whole-branch review
found one Critical and ten Important issues; all are covered by regression-first
corrections in the candidate. The minor required-module completeness improvement
for the consumer-origin checker remains deferred; current consumer logs contain
all expected module origins. The extracted library's real-client
matrix is **pending**. Earlier 25570/25572/25576 prototype feedback is design
evidence, not acceptance of these new Maven artifacts. No merge/release claim may
replace the rows below with headless, packet or source-inspection evidence.

Latest targeted client result (2026-11-15): the user confirmed the fixed
three-slot HUD card viewport on Grounds 26.3, build `70f7e67`. Only existing
cards are painted, unused slots remain transparent, the scrollbar sits outside,
and speech/footer positions stay fixed on the last page. Card text remains
left-aligned and vertically centered. This accepts that targeted layout check;
the full matrix below, including public 26.2 and GUI-scale/two-client cases,
remains open. [CI for the tested build](https://github.com/groundsgg/library-dialogue/actions/runs/43741285806)
passed both required jobs.

Runtime evidence for that client result:

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
| Pack accept, decline, download failure and removal | Pending | Pending |
| No pack request when opening/reopening a dialogue | Pending | Pending |
| HUD: fonts, centering, typewriter, choices and final End hint | Pending | Pending |
| GUI scale 1/2/3/Auto, window sizes and screen-edge clipping | Pending | Pending |
| NPC: fixed world size, body-front yaw limit and near-head spacing | Pending | Pending |
| NPC: closed backs, no Z-fighting, hover and selection growth | Pending | Pending |
| Seven answers reachable through three cards; disabled/occluded/back targets | Pending | Pending |
| Chat: complete sections, all choices, current/stale click behaviour | Pending | Pending |
| Use/Attack/Swap with empty hands, main/offhand items and shield | Pending | Pending |
| Held button, fast clicks, scroll and jitter without accidental confirmation | Pending | Pending |
| Current inventory and server slot preserved; visible vs covered hotbar | Pending | Pending |
| Language switch during reveal and action wait | Pending | Pending |
| Optional cover/vignette/backdrop toggled independently | Vignette unsupported | Pending |
| Ordinary hearts/hunger/hotbar covered; extra-heart limits noted | Pending | Pending |
| Normal looking remains open; strong turn/distance ends after grace | Pending | Pending |
| Two visible clients: private gesture/sound and shared restoration | Pending | Pending |
| Disconnect/instance change during active/preparing dialogue | Pending | Pending |

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
and hover and requested up/down page arrows. The next candidate adds arrow
navigation using the configured confirmation input and keeps manual selection
until deliberate gaze/eye movement. Arrow activation only selects a page; it
never executes an answer. Disabled end arrows consume confirmation. This new
navigation requires its own client check; the confirmed HUD remains reference.

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
