# NPC and Dialogue integration consumer

This consumer uses the released `gg.grounds:npc-minestom:1.0.0` and its
transitive `npc-core`, plus the selected Dialogue Maven artifacts. NPC resolves
from its canonical GitHub Packages repository by default. `-PnpcVersion=...`
and `-PnpcRepository=file:///...` select an explicit compatible local override.
Only the basic demo's application sources are shared. No library sources or composite
builds substitute for Maven JARs; tests check the actual class origins.

Supply `GITHUB_ACTOR` and a `GITHUB_TOKEN` with package-read access through your
normal local environment. They are sent only to the exact canonical Packages
repository. From the repository root:

```sh
./gradlew publishAllPublicationsToReviewRepository
./gradlew -p fixtures/npc-dialogue-consumer test run \
  "-PdialogueRepository=file:$PWD/.evidence/maven" \
  --args=--smoke
```

Use `--args=--smoke` for the automated application run. The normal Kotlin demo uses
`localhost:25578` and pack port `25579`; stop another instance using those demo
ports first. The exact Grounds 26.3 profile is opt-in, without changing the public
source-build dependency. No stage/production service is used.

`PersonalNpcActors` owns personal presentation starts before observing readiness.
Core activation publishes them once, cues call the existing private clip/head
channels, and close cancels only those personal starts. The shared parent is
borrowed throughout a dialogue. The application closes that parent after the host
has drained at shutdown. Unexpected personal expiry cancels the actor request.

The neutral two-part bot, item/model JSON and clip are the original MIT example
from `groundsgg/library-npc`. There are no LuxDialogues/Rivet assets. The JSON
refers to vanilla textures without copying their files into this repository.

Two clients can independently `/dialogue`, `/personalwave`, `/renderer npc` and
`/lang de`. Verify each viewer's gesture and sound, then `/cancel`: the common NPC
should reappear for that viewer. The automated tests also cover private A/B
channels, renderer switches without repeated cues, preparation cancellation,
private expiry, cleanup failure and restoration. Real visual/two-client acceptance
is tracked separately; server-side tests are not visual evidence.

The model uses typed loop/interpolation/channel definitions and personal actors
call `NpcHandle.preparePresentation` directly. Options use named builders. The
base `dialogue-minestom` module continues to have no NPC dependency. The consumer is pinned to the published NPC 1.0.0 package.
