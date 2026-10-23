"""Small geometry and atlas checks for the offline pixel conversion workflow."""

import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

import pixel_hardcore as pixel


def cube_scene(path: Path, open_top: bool = False) -> Path:
    corners = np.asarray([[2, 2, 2], [14, 2, 2], [14, 14, 2], [2, 14, 2],
                          [2, 2, 14], [14, 2, 14], [14, 14, 14], [2, 14, 14]], dtype=float)
    faces = [(0, 1, 2, 3), (5, 4, 7, 6), (4, 0, 3, 7), (1, 5, 6, 2),
             (4, 5, 1, 0)]
    if not open_top:
        faces.append((3, 2, 6, 7))
    vertices = []
    for face in faces:
        for triangle in ((0, 1, 2), (0, 2, 3)):
            for index in triangle:
                vertices.extend([*corners[face[index]], *[(0, 0), (1, 0), (1, 1), (0, 1)][index], 0, 1, 0])
    path.write_text(json.dumps({"schema": pixel.SCHEMA,
                                "materials": [{"name": "green", "base_color_factor": [0.2, 0.6, 0.1, 1]}],
                                "meshes": [{"name": "box", "material": 0, "vertices": vertices}]}), encoding="utf-8")
    return path


class PixelWorkflowChecks(unittest.TestCase):
    def test_closest_surface_uv_weights(self):
        triangle = np.asarray([[0, 0, 0], [1, 0, 0], [0, 1, 0]])
        distances, weights = pixel.closest_barycentric(np.asarray([[0.2, 0.3, 1], [-1, -1, 0]]), triangle)
        np.testing.assert_allclose(distances, [1, 2])
        np.testing.assert_allclose(weights, [[0.5, 0.2, 0.3], [1, 0, 0]])

    def test_closed_cube_removes_hidden_interior_faces(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scene = pixel.read_scene(cube_scene(root / "scene.json"))
            shell = pixel.voxelize(scene, 12)
            self.assertFalse(shell.occupied[6, 6, 6])
            self.assertFalse(pixel.exterior_air(shell.occupied)[6, 6, 6])
            rectangles, raw_faces = pixel.surface_rectangles(shell)
            self.assertEqual(len(rectangles), 6)
            self.assertEqual(raw_faces, 6 * 12 * 12)

    def test_open_box_keeps_reachable_cavity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scene = pixel.read_scene(cube_scene(root / "scene.json", open_top=True))
            shell = pixel.voxelize(scene, 12)
            self.assertFalse(shell.occupied[6, 6, 6])
            self.assertTrue(pixel.exterior_air(shell.occupied)[6, 6, 6])
            rectangles, _ = pixel.surface_rectangles(shell)
            self.assertTrue(any(rect.direction == "up" and rect.start[1] < 4 for rect in rectangles))

    def test_atlas_preserves_face_pixel_order_and_padding(self):
        pixels = np.asarray([[[255, 0, 0, 255], [0, 255, 0, 255]],
                             [[0, 0, 255, 255], [255, 255, 0, 255]]], dtype=np.uint8)
        rectangle = pixel.Rectangle("north", [0, 0, 0], [2, 2, 0], pixels)
        atlas = np.asarray(pixel.pack_atlas([rectangle]))
        self.assertIsNotNone(rectangle.uv)
        width, height = atlas.shape[1], atlas.shape[0]
        x = round(rectangle.uv[0] / 16 * width)
        y = round(rectangle.uv[1] / 16 * height)
        np.testing.assert_array_equal(atlas[y:y + 2, x:x + 2], pixels)
        np.testing.assert_array_equal(atlas[y - 1, x - 1], pixels[0, 0])

    def test_export_is_valid_one_face_per_element(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = cube_scene(root / "scene.json")
            report = pixel.convert(source, root / "result", resolution=12, min_resolution=8)
            model = json.loads((root / "result" / "model.json").read_text())
            self.assertEqual(report["rendered_quads"], 6)
            self.assertTrue(all(len(element["faces"]) == 1 for element in model["elements"]))
            self.assertEqual(model["textures"]["0"], "tacz_pubg:item/med_kit")
            self.assertTrue((root / "result" / "texture.png").exists())

    def test_face_budget_never_silently_reduces_requested_resolution(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            source=cube_scene(root/"scene.json",open_top=True)
            report=pixel.convert(source,root/"result",resolution=36,max_elements=6)
            self.assertFalse(report["target_met"])
            self.assertEqual(report["actual_resolution"],36)
            self.assertEqual(report["minimum_resolution"],36)
            self.assertEqual(report["resolution_policy"],"fixed")
            self.assertEqual(len(report["attempts"]),1)


if __name__ == "__main__":
    unittest.main()
