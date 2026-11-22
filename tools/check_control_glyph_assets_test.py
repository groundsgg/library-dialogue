"""Standard-library checks for control glyph manifests and font providers."""

import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "dialogue-minestom/src/main/resources/default-minecraft"
CODEPOINTS = (0xE790, 0xE791, 0xE792, 0xE793)
# Baselines reviewed from the 1.3.1 published JAR (SHA-256 a301e7b9664833ba180f31256763c063904a22b99f3ab17a5f1c0549ea317e52), equivalent to BASE ee1d9ba7d8415bff38465dded07ef78ea28fe6d0.
EXPECTED_GLYPHS_SHA256 = "b8c25241aa46a8daaf4cf36c954fa34bd39a3734595fed8701573855349d3546"
EXPECTED_ATLAS_SHA256 = {
    "letters.png": "038f30b5be092bec0bbe48c4c2c1f91e1d0b233426b806ec82cd81fb6f6b3dd0",
    "hud_letters.png": "df195b65efc268acfa47bc4e8b3a847860136e2b3955fd249aa2709331b7526c",
}


class ControlGlyphAssetsTest(unittest.TestCase):
    def test_both_default_profiles_ship_complete_control_glyph_assets(self):
        for profile in ("public-26.2", "grounds-26.3"):
            with self.subTest(profile=profile):
                root = RESOURCES / profile
                sprite = root / "assets/dialogue/textures/font/controls.png"
                metadata = root / "assets/dialogue/control_glyphs.properties"
                manifest = json.loads((root / "manifest.json").read_text())

                self.assertTrue(sprite.is_file(), f"missing {sprite}")
                self.assertTrue(metadata.is_file(), f"missing {metadata}")
                files = manifest["files"]
                self.assertIn("assets/dialogue/textures/font/controls.png", files)
                self.assertIn("assets/dialogue/control_glyphs.properties", files)
                canonical_glyphs = json.dumps(
                    manifest["glyphs"], sort_keys=True, separators=(",", ":")
                )
                self.assertEqual(
                    hashlib.sha256(canonical_glyphs.encode()).hexdigest(),
                    EXPECTED_GLYPHS_SHA256,
                )
                self.assertEqual(len(manifest["glyphs"]), 200)
                for old_atlas, expected_hash in EXPECTED_ATLAS_SHA256.items():
                    relative = f"assets/dialogue/textures/font/{old_atlas}"
                    self.assertEqual(expected_hash, files[relative])
                    self.assertEqual(
                        hashlib.sha256((root / relative).read_bytes()).hexdigest(),
                        files[relative],
                    )
                for relative in (
                    "assets/dialogue/textures/font/controls.png",
                    "assets/dialogue/control_glyphs.properties",
                ):
                    self.assertEqual(
                        hashlib.sha256((root / relative).read_bytes()).hexdigest(),
                        files[relative],
                    )

                for prefix in ("native", "hud"):
                    font_path = (
                        "assets/dialogue/font/a_0.json"
                        if prefix == "native"
                        else "assets/dialogue/font/hud_a_0.json"
                    )
                    document = json.loads((root / font_path).read_text())
                    control_provider = next(
                        (
                            provider
                            for provider in document["providers"]
                            if provider.get("file") == "dialogue:font/controls.png"
                        ),
                        None,
                    )
                    self.assertIsNotNone(control_provider, f"{prefix} provider missing")
                    self.assertEqual(
                        control_provider["height"], 256 if prefix == "native" else 192
                    )
                    chars = "".join(control_provider["chars"])
                    for codepoint in CODEPOINTS:
                        self.assertIn(chr(codepoint), chars)

                values = dict(
                    line.split("=", 1)
                    for line in metadata.read_text().splitlines()
                    if line and not line.startswith("#")
                )
                self.assertEqual(values.get("schemaVersion"), "1")
                expected_keys = {"schemaVersion"}
                for codepoint in CODEPOINTS:
                    for prefix in ("native", "hud"):
                        expected_keys.add(f"{prefix}.advance.{codepoint}")
                        expected_keys.add(f"{prefix}.ink.{codepoint}")
                self.assertEqual(set(values), expected_keys)


if __name__ == "__main__":
    unittest.main()
