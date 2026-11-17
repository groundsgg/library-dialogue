# Minecraft asset profiles

The adapter uses frozen native bitmap advances, separate from the headless font
metrics. The source is the existing licensed DejaVu Sans font. The initial
Minecraft atlas covers printable ASCII, Latin-1 and the supplied control punctuation.
Other graphemes require another complete profile or semantic/chat fallback.

The compiled raster supports half-pixel horizontal positioning and even vertical
ascents from -64 through 256; unsupported layout geometry is diagnosed. Panel
stripes are one pixel wide, supporting heights 1 through 256. The source atlas
has 256-pixel cells with ink drawn at baseline 12. Native bitmap scale is one;
advance is the rightmost ink column plus two (rounded ink width plus native one).
Both installed clients were inspected for their bitmap provider scale/advance
rule. Empty spaces use the explicit four-pixel space provider.

The bundled files and advances share a verified SHA-256 manifest. They are pack
contributions; the application owns pack.mcmeta, ZIP composition and HTTP hosting.
No normal build or server start regenerates fonts from the host environment.

Maintenance with JDK 25.0.2 after compiling dialogue-core:

```sh
java -Djava.awt.headless=true -cp dialogue-core/build/classes/java/main tools/GenerateMinecraftAssets.java dialogue-presentation/src/main/resources/default-theme/DejaVuSans.ttf dialogue-minestom/src/main/resources/default-minecraft
```

Verify actual installed client metadata explicitly, for example:

```sh
python3 tools/check_client_profiles.py --profile grounds-26.3 --client /absolute/path/to/26.3.jar
```

The public profile is protocol 776 / pack 88.0; the Grounds profile is protocol
777 / pack 97.1. A matching server-side profile is not a visual client acceptance.

NPC page controls use a separate32×256 transparent atlas with two16×8
pixel-drawn chevrons. The existing even-ascent native fonts register its private
E780/E781 glyphs; frozen normal-text metrics and existing HUD/panel artwork are
unchanged. The encoder tints enabled/disabled icons and centers the visible ink
inside larger hit rectangles. Asset generation remains maintenance-only.

The compact HUD uses its own frozen 75% prototype bitmap. Its verified
`hud_metrics.properties` stores optional `ink.<codepoint>=<top>,<bottom>` bounds
in scaled GUI pixels, measured from foreground alpha during maintenance generation.
Individual answer cards use these bounds for vertical alignment; line advance
and horizontal widths remain independent. The generator freezes the required
integral ascent variants for local widgets and the combined HUD. No runtime
rasterization occurs. Existing font providers and textures are preserved; the
additional ascent definitions and metrics change the pack hash and require a
fresh client pack load. Profiles without ink metadata retain line-box alignment.
