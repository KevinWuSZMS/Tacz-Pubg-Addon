"""Regression checks for separating items before either existing export workflow."""
from __future__ import annotations

from collections import Counter
import copy
from pathlib import Path
import unittest

import numpy as np

from workflow_common import normalize_scene, select_meshes


def mesh_points(mesh):
    return np.asarray(mesh["vertices"], dtype=np.float64).reshape(-1, 8)


def triangle_counts(scene):
    """Count full source records, including UVs/normals and duplicate triangles."""
    return Counter(tuple(triangle.ravel())
                   for mesh in scene["meshes"]
                   for triangle in mesh_points(mesh).reshape(-1, 3, 8))


def separated_scene():
    meshes = []
    for index, (name, offset, width, height, material) in enumerate((
            ("short_grip", -20, 3, 5, 2),
            ("vertical_grip", 4, 2, 13, 1),
            ("angled_grip", 25, 12, 4, 0))):
        triangle = np.asarray([
            [offset, 2, -1, .1, .2, 0, 0, 1],
            [offset + width, 2, -1, .8, .2, 0, 0, 1],
            [offset, 2 + height, 1, .1, .9, 0, 0, 1]], dtype=float)
        # Duplicates deliberately verify conservation as a multiset, not a set.
        vertices = np.tile(triangle, (index + 1, 1)).ravel().tolist()
        meshes.append({"name": name, "material": material, "vertices": vertices})
    return {
        "schema": "agent-model-scene/1", "uv_origin": "top_left",
        "materials": [{"name": f"bitmap_{i}", "base_color_texture": f"texture_{i}.tga"}
                      for i in range(4)],
        "meshes": meshes,
        "metadata": {"handedness": "right", "source": "synthetic.c4d"},
    }


class ItemExtractionChecks(unittest.TestCase):
    def test_three_selections_partition_every_source_triangle_without_changes(self):
        scene = separated_scene()
        before = copy.deepcopy(scene)
        combined = Counter()
        for index, source_mesh in enumerate(scene["meshes"]):
            selected = select_meshes(scene, [source_mesh["name"]])
            self.assertEqual(len(selected["meshes"]), 1)
            self.assertEqual(selected["meshes"][0]["vertices"], source_mesh["vertices"])
            np.testing.assert_array_equal(mesh_points(selected["meshes"][0]), mesh_points(source_mesh))
            combined.update(triangle_counts(selected))
            self.assertEqual(selected["metadata"]["selection"]["mesh_indices"], [index])
        self.assertEqual(combined, triangle_counts(scene))
        self.assertEqual(scene, before)

    def test_each_item_is_centered_and_scaled_independently(self):
        scene = separated_scene()
        scales = []
        for index in range(3):
            selected = select_meshes(scene, [index])
            normalized = normalize_scene(selected, max_size=14)
            points = mesh_points(normalized["meshes"][0])
            original = mesh_points(scene["meshes"][index])
            low, high = points[:, :3].min(0), points[:, :3].max(0)
            self.assertAlmostEqual(float(np.max(high - low)), 14)
            np.testing.assert_allclose((low + high)[[0, 2]] / 2, [8, 8])
            self.assertAlmostEqual(float(low[1]), 1)
            np.testing.assert_array_equal(points[:, 3:5], original[:, 3:5])
            np.testing.assert_allclose(points[:, 5:8], original[:, 5:8])
            self.assertEqual(len(points), len(original))
            scales.append(normalized["metadata"]["normalization"]["uniform_scale"])
        self.assertEqual(len(set(scales)), 3)

    def test_materials_are_pruned_and_remapped_in_source_material_order(self):
        scene = separated_scene()
        selected = select_meshes(scene, ["0", "angled_grip"])
        self.assertEqual([m["name"] for m in selected["meshes"]], ["short_grip", "angled_grip"])
        self.assertEqual([m["name"] for m in selected["materials"]], ["bitmap_0", "bitmap_2"])
        self.assertEqual([m["material"] for m in selected["meshes"]], [1, 0])
        selection = selected["metadata"]["selection"]
        self.assertEqual(selection["source_mesh_count"], 3)
        self.assertEqual(selection["mesh_indices"], [0, 2])
        self.assertEqual(selection["mesh_names"], ["short_grip", "angled_grip"])
        self.assertEqual(selection["material_indices"], [0, 2])
        selected["materials"][0]["name"] = "changed"
        selected["meshes"][0]["vertices"][0] = -999
        self.assertEqual(scene["materials"][0]["name"], "bitmap_0")
        self.assertNotEqual(scene["meshes"][0]["vertices"][0], -999)

    def test_invalid_or_duplicate_selectors_fail_explicitly(self):
        scene = separated_scene()
        for selectors in ([], ["missing_grip"], [-1], [3], [0, "short_grip"], [0, "0"]):
            with self.subTest(selectors=selectors):
                with self.assertRaises(ValueError):
                    select_meshes(scene, selectors)
        scene["meshes"][1]["name"] = scene["meshes"][0]["name"]
        with self.assertRaises(ValueError):
            select_meshes(scene, ["short_grip"])
        self.assertEqual(len(select_meshes(scene, [0])["meshes"]), 1)


PACK_25 = (Path(__file__).resolve().parent.parent
           / "82个绝地求生PUBG吃鸡游戏模型" / "绝地求生 (25).c4d")


@unittest.skipUnless(PACK_25.is_file(), "Optional supplied C4D pack is absent")
class Pack25ExtractionChecks(unittest.TestCase):
    def test_three_real_grips_remain_distinct_and_conserve_all_4136_triangles(self):
        from c4d_reader import read_scene

        scene = read_scene(PACK_25)
        expected = {"Vector_VerGrip_Setting": 1110, "Grip_01": 898, "AngledForeGrip": 2128}
        self.assertEqual({mesh["name"] for mesh in scene["meshes"]}, set(expected))
        combined = Counter()
        for name, count in expected.items():
            selected = select_meshes(scene, [name])
            self.assertEqual(len(selected["meshes"]), 1)
            self.assertEqual(len(selected["materials"]), 1)
            self.assertEqual(selected["meshes"][0]["material"], 0)
            self.assertEqual(len(selected["meshes"][0]["vertices"]) // 24, count)
            combined.update(triangle_counts(selected))
            normalized = normalize_scene(selected)
            points = mesh_points(normalized["meshes"][0])[:, :3]
            low, high = points.min(0), points.max(0)
            self.assertAlmostEqual(float(np.max(high - low)), 14)
            np.testing.assert_allclose((low + high)[[0, 2]] / 2, [8, 8])
            self.assertAlmostEqual(float(low[1]), 1)
        self.assertEqual(combined, triangle_counts(scene))
        self.assertEqual(sum(combined.values()), 4136)


if __name__ == "__main__":
    unittest.main()
