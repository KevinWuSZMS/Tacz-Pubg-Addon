"""Focused regression checks for source fidelity, cost guards and offline install."""
from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
from PIL import Image

from workflow_common import normalize_scene, png_lossless, write_json
from playful_world import export
from install_assets import install


def triangle_scene(texture: Path, handedness="right"):
    return {"schema": "agent-model-scene/1", "materials": [{"name": "bitmap", "base_color_texture": str(texture)}],
            "meshes": [{"name": "triangle", "material": 0, "vertices": [
                0,0,0, .1,.2, 0,0,1, 1,0,0, .8,.2, 0,0,1, 0,1,0, .1,.9, 0,0,1]}],
            "metadata": {"handedness": handedness}}


class FidelityChecks(unittest.TestCase):
    def test_left_handed_conversion_preserves_uv_corners_and_outward_normal(self):
        scene = triangle_scene(Path("unused"), "left")
        normalized = normalize_scene(scene)
        a = np.array(normalized["meshes"][0]["vertices"]).reshape(3, 8)
        np.testing.assert_allclose(a[:, 3:5], [[.1,.2],[.1,.9],[.8,.2]])
        np.testing.assert_allclose(a[:, 5:], [[0,0,-1]]*3)
        normal = np.cross(a[1,:3]-a[0,:3], a[2,:3]-a[0,:3])
        self.assertGreater(float(normal @ a[0,5:]), 0)
        self.assertEqual(normalized["metadata"]["handedness"], "right")
        # Re-normalizing an already RH scene never mirrors it again.
        again = np.array(normalize_scene(normalized)["meshes"][0]["vertices"]).reshape(3,8)
        np.testing.assert_allclose(again, a)

    def test_zero_span_rejected(self):
        scene = triangle_scene(Path("unused"))
        a = np.array(scene["meshes"][0]["vertices"]).reshape(3,8)
        a[:,:3] = 0
        scene["meshes"][0]["vertices"] = a.ravel().tolist()
        with self.assertRaisesRegex(ValueError, "no measurable size"):
            normalize_scene(scene)

    def test_tga_png_pixel_identity_and_budget_guard(self):
        with tempfile.TemporaryDirectory(prefix="agent-model-fidelity-") as tmp:
            root = Path(tmp)
            image = Image.fromarray(np.arange(4*4*3, dtype=np.uint8).reshape(4,4,3))
            image.save(root / "source.tga")
            report = png_lossless(root / "source.tga", root / "direct.png")
            self.assertTrue(report["pixels_preserved"])
            scene = normalize_scene(triangle_scene(root / "source.tga"))
            report = export(scene, root / "export", triangle_budget=1)
            self.assertEqual(report["exported_triangles"], 1)
            scene["meshes"][0]["vertices"] *= 2
            with self.assertRaisesRegex(ValueError, "above budget"):
                export(scene, root / "rejected", triangle_budget=1)
            self.assertFalse((root / "rejected/model.json").exists())

    def test_explicit_installer_backs_up_and_preserves_display(self):
        with tempfile.TemporaryDirectory(prefix="agent-model-install-") as tmp:
            root = Path(tmp)
            assets = root / "assets/tacz_pubg"
            original = {"display": {"ground": {"scale": [.4,.4,.4]}}}
            write_json(assets / "models/item/med_kit.json", original)
            for style in ("playful", "pixel"):
                folder = root / style
                folder.mkdir()
                Image.new("RGB", (2,2), "green").save(folder / "texture.png")
            write_json(root / "playful/model.json", {"texture": "tacz_pubg:textures/item/detail/med_kit.png", "vertices": []})
            write_json(root / "pixel/model.json", {"textures": {"0": "tacz_pubg:item/med_kit"}, "elements": []})
            record = install(root / "playful", root / "pixel", assets, root / "backups")
            old = Path(record["backup"]) / "previous/models/item/med_kit.json"
            self.assertEqual(json.loads(old.read_text()), original)
            self.assertEqual(json.loads((assets / "models/item/med_kit.json").read_text())["display"], original["display"])
            self.assertEqual(len(record["files"]), 4)
            with self.assertRaisesRegex(ValueError, "existing assets"):
                install(root / "playful", root / "pixel", root, root / "backups")


if __name__ == "__main__":
    unittest.main()
