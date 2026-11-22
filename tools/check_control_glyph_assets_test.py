"""Asset contract checks for the four optional control glyphs."""

import json
import hashlib
import math
import subprocess
import unittest
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "dialogue-minestom/src/main/resources/default-minecraft"
CODEPOINTS = (0xE790, 0xE791, 0xE792, 0xE793)


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
                original_manifest = json.loads(
                    subprocess.check_output(
                        [
                            "git",
                            "show",
                            f"HEAD:dialogue-minestom/src/main/resources/default-minecraft/{profile}/manifest.json",
                        ],
                        cwd=ROOT,
                    )
                )
                self.assertEqual(manifest["glyphs"], original_manifest["glyphs"])
                self.assertEqual(len(manifest["glyphs"]), 200)
                for old_atlas in ("letters.png", "hud_letters.png"):
                    relative = f"assets/dialogue/textures/font/{old_atlas}"
                    original = subprocess.check_output(
                        ["git", "show", f"HEAD:dialogue-minestom/src/main/resources/default-minecraft/{profile}/{relative}"],
                        cwd=ROOT,
                    )
                    self.assertEqual(hashlib.sha256(original).hexdigest(), files[relative])
                with Image.open(sprite) as sheet:
                    self.assertEqual(sheet.size, (64, 256))
                    alpha = sheet.getchannel("A")
                    for cell, points in (
                        (0, ((5, 2), (6, 2), (4, 3), (6, 4))),
                        (1, ((8, 2), (9, 2), (8, 3), (10, 4))),
                        (2, ((7, 2), (7, 3), (7, 4))),
                    ):
                        for x, y in points:
                            self.assertGreater(alpha.getpixel((cell * 16 + x, y)), 0)

                for prefix in ("native", "hud"):
                    for codepoint in CODEPOINTS:
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
                        self.assertEqual(control_provider["height"], 256 if prefix == "native" else 192)
                        self.assertIn(
                            chr(codepoint), "".join(control_provider["chars"])
                        )

                values = dict(
                    line.split("=", 1)
                    for line in metadata.read_text().splitlines()
                    if line and not line.startswith("#")
                )
                self.assertEqual(values.get("schemaVersion"), "1")
                with Image.open(sprite) as sheet:
                    for cell, codepoint in enumerate(CODEPOINTS):
                        alpha = sheet.getchannel("A").crop((cell * 16, 0, (cell + 1) * 16, 256))
                        bbox = alpha.getbbox()
                        self.assertIsNotNone(bbox)
                        left, top, right, bottom = bbox
                        self.assertLessEqual(bottom, 12)
                        for prefix, scale in (("native", 1.0), ("hud", 0.75)):
                            expected_advance = math.floor((right * scale) + 0.5) + 1
                            expected_ink = f"{top * scale},{bottom * scale}"
                            self.assertEqual(
                                values[f"{prefix}.advance.{codepoint}"], str(expected_advance)
                            )
                            self.assertEqual(values[f"{prefix}.ink.{codepoint}"], expected_ink)
                for codepoint in CODEPOINTS:
                    for prefix in ("native", "hud"):
                        self.assertIn(f"{prefix}.advance.{codepoint}", values)
                        self.assertIn(f"{prefix}.ink.{codepoint}", values)


if __name__ == "__main__":
    unittest.main()
