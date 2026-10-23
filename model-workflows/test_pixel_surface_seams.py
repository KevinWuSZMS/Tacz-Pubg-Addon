"""Regression checks for T joints, paired baked quads and unchanged source UVs."""
from copy import deepcopy
import unittest

import numpy as np

from pixel_surface_seams import (audit_seamless, boundary_polygon, pair_triangles,
                                 point_uv, seamless_model, triangulate_polygon, vertex_lines)
from render_preview import FACE_CORNERS, face_vertices, pixel_triangles


def surface(low, high, direction="north", uv=None):
    return {"from": list(low), "to": list(high), "faces": {
        direction: {"texture": "#0", "uv": list(uv or (1, 2, 9, 10))}}}


def t_joint_model():
    return {"textures": {"0": "tacz_pubg:item/test"}, "display": {"ground": {"scale": [.7, .7, .7]}},
            "elements": [surface((0, 0, 0), (4, 4, 0)), surface((4, 0, 0), (6, 2, 0)),
                         surface((4, 2, 0), (6, 4, 0))]}


class PixelSurfaceSeamChecks(unittest.TestCase):
    def test_existing_edge_hole_fails_and_repaired_boundary_passes(self):
        source = t_joint_model()
        cracked = deepcopy(source)
        cracked["seamless_quads"] = []
        for e in source["elements"]:
            vertices = face_vertices(e, "north", e["faces"]["north"])
            cracked["seamless_quads"].append({"face": "north", "vertices": [
                float(value) for p in vertices for value in (*p[:3], *(p[3:5] * 16))]})
        with self.assertRaisesRegex(ValueError, "T junction"):
            audit_seamless(cracked)
        repaired, cost = seamless_model(source)
        self.assertEqual(cost["gpu_quads"], 4)
        self.assertEqual(cost["valid_triangles"], 7)
        self.assertEqual(cost["degenerate_triangles"], 1)
        self.assertEqual(audit_seamless(repaired)["remaining_rendered_edge_t_junctions"], 0)
        self.assertEqual(repaired["elements"], source["elements"])
        self.assertEqual(repaired["display"], source["display"])
        self.assertEqual(repaired["textures"], source["textures"])
        self.assertEqual(seamless_model(repaired)[0], repaired)

    def test_affine_uv_axes_match_minecraft_all_face_orientations(self):
        for direction in FACE_CORNERS:
            low, high = [1, 2, 3], [7, 11, 13]
            axis = {"west": 0, "east": 0, "down": 1, "up": 1, "north": 2, "south": 2}[direction]
            high[axis] = low[axis]
            element = surface(low, high, direction, uv=(12, 13, 2, 3))
            actual = face_vertices(element, direction, element["faces"][direction])
            for vertex in actual:
                np.testing.assert_allclose(point_uv(element, vertex[:3]), vertex[3:5] * 16, atol=1e-12)

    def test_collinear_boundary_vertices_survive_internal_diagonal_pairing(self):
        # A naive triangle fan would silently drop points along its root edge.
        points = [(0, 0, 0), (0, 1, 0), (0, 3, 0), (0, 4, 0),
                  (2, 4, 0), (4, 4, 0), (4, 2, 0), (4, 0, 0), (3, 0, 0), (1, 0, 0)]
        triangles = triangulate_polygon(points, 2)
        self.assertEqual(len(triangles), len(points) - 2)
        quads = pair_triangles(triangles)
        self.assertLess(len(quads), len(triangles))
        covered = set(i for t in triangles for i in t)
        self.assertEqual(covered, set(range(len(points))))
        boundary_edges = {tuple(sorted((i, (i + 1) % len(points)))) for i in range(len(points))}
        rendered_edges = set()
        for t in triangles:
            for a, b in zip(t, t[1:] + t[:1]):
                rendered_edges.add(tuple(sorted((a, b))))
        self.assertTrue(boundary_edges <= rendered_edges)

    def test_software_preview_uses_actual_paired_geometry(self):
        repaired, cost = seamless_model(t_joint_model())
        triangles = pixel_triangles(repaired)
        self.assertEqual(len(triangles), cost["valid_triangles"])
        actual_area = sum(np.linalg.norm(np.cross(t[1, :3] - t[0, :3], t[2, :3] - t[0, :3])) / 2 for t in triangles)
        self.assertAlmostEqual(actual_area, 24)

    def test_uv_corruption_or_surface_loss_is_rejected(self):
        repaired, _ = seamless_model(t_joint_model())
        bad = deepcopy(repaired)
        bad["seamless_quads"][0]["vertices"][3] += .1
        with self.assertRaisesRegex(ValueError, "UV"):
            audit_seamless(bad)
        bad = deepcopy(repaired)
        bad["seamless_quads"].pop()
        with self.assertRaisesRegex(ValueError, "surface area"):
            audit_seamless(bad)

    def test_unaffected_rectangle_stays_one_gpu_quad(self):
        source = {"elements": [surface((1, 1, 0), (4, 4, 0))]}
        repaired, cost = seamless_model(source)
        self.assertEqual(cost["gpu_quads"], 1)
        self.assertEqual(cost["affected_faces"], 0)
        self.assertEqual(len(repaired["seamless_quads"][0]["vertices"]), 20)


if __name__ == "__main__":
    unittest.main()
