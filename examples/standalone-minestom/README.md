# Standalone Java dialogue demo

This independent build resolves the three runtime libraries from the selected Maven
repository. It does not include project sources or use a composite build. Public
Minestom 2026.10.07-26.2 is explicit; no NPC, Scene, Grounds service or token is needed.
The armor stand is only a borrowed body anchor, without an animation implementation.

From the repository root, using JDK 25:

```sh
./gradlew publishAllPublicationsToReviewRepository
./gradlew -p examples/standalone-minestom test run \
  "-PdialogueRepository=file:$PWD/.evidence/maven"
```

Connect to `localhost:25578`, accept the original demo pack hosted on loopback
`25579`, then use `/dialogue`. The pack is sent once on first spawn, never on
opening a dialogue. Rejected/failed packs use semantic chat fallback. `/renderer
hud|npc|chat` changes the current presentation, `/lang en|de` changes the current
language, and `/cancel` closes it. Seven choices use the shared three-answer viewport.
The task choice waits two seconds as a local example action, without a quest service.

`/confirm use|attack|swap` selects the next dialogue's confirm profile. Defaults
accept Use and Swap hands, with Use in the hint. Actual physical keys depend on the
client's bindings. `/hidehud`, `/vignette` and `/npcbackdrop` independently toggle
optional preferences. Public 26.2 has no injected native vignette transport.
`/personalwave` needs the separate NPC consumer.

Use `run --args=--smoke` for the real server with application-owned headless
players, renderer/language changes and cleanup. A protocol status/ping/pack smoke
is supplied by `tools/minestom_smoke.py` after artifact verification and
`installDist`; neither proves visual acceptance. Grounds 26.3 is an explicit
compatibility profile, documented in `docs/minestom-integration.md` after the
profile gate is in place. The existing 25570 prototype is not touched.
