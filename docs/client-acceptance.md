# Package C client acceptance

Status: automated implementation candidate. The independent whole-branch review
found one Critical and ten Important issues; all are covered by regression-first
corrections in the candidate. The minor required-module completeness improvement
for the consumer-origin checker remains deferred; current consumer logs contain
all expected module origins. The extracted library's real-client
matrix is **pending**. Earlier 25570/25572/25576 prototype feedback is design
evidence, not acceptance of these new Maven artifacts. No merge/release claim may
replace the rows below with headless, packet or source-inspection evidence.

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
