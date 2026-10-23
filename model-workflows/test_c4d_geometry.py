"""Checks for topology, hierarchy and source-material partition fidelity."""
from __future__ import annotations

import math
from pathlib import Path
import unittest

import numpy as np

import c4d_reader as c


def transform_node(*, color=(.8, .8, .8), axis=None, flags=0):
    axis = axis or (19., 7., 3., 1., 0., 0., 0., 0., 1., 0., -1., 0.)
    entries = [(23, (2., 3., 4.)), (23, (1., 1., 1.)), (23, (0., 0., 0.)),
               (15, 2), (15, 2), (15, 0), (21, False), (23, color),
               (15, 2), (23, (0., 0., 0.)), (15, 0), (25, axis),
               (21, False), (21, False), (16, flags), (15, 6)]
    return c.Chunk(5154, 0, 0, [c.Chunk(110052, 10, 0,
                   [c.Value(kind, data, 0) for kind, data in entries])])


class GeometryRecords(unittest.TestCase):
    def test_display_colors_and_modeling_axis_do_not_transform_geometry(self):
        matrix, info = c._object_matrix(transform_node())
        np.testing.assert_allclose(c._transform(matrix, (1., 0., 0.)), (3., 3., 4.))
        self.assertEqual(info['position'], (2., 3., 4.))

    def test_actual_frozen_flags_still_fail_explicitly(self):
        with self.assertRaisesRegex(c.C4DReadError, 'Frozen-coordinate'):
            c._object_matrix(transform_node(flags=2))

    def test_dtype_none_does_not_consume_next_container_entry(self):
        seq = [(15, -1), (15, 11), (15, 0), (15, 12), (15, 19), (19, .7),
               (15, 13), (15, 0)]
        node = c.Chunk(0, 0, 0, [c.Chunk(110050, 8, 0, [c.Chunk(132, 0, 0,
                    [c.Value(kind, data, 0) for kind, data in seq])])])
        self.assertEqual(c._parameters(node)[12].data, .7)
        self.assertEqual(set(c._parameters(node)), {12})

    def test_omitted_zero_area_faces_do_not_renumber_native_hard_edges(self):
        points = [(0., 0., 0.), (1., 0., 0.), (0., 1., 0.), (0., 0., 1.)]
        polygons = [(0, 1, 2), (0, 3, 1)]
        # Native face 1 was omitted. Shared edge 3 on native face 2 is still 11.
        split = c._normal_fans(points, polygons, {11}, math.pi, [0, 2])
        np.testing.assert_allclose(split[0][0], (0, 0, 1))
        np.testing.assert_allclose(split[1][0], (0, 1, 0))
        smooth = c._normal_fans(points, polygons, set(), math.pi, [0, 2])
        np.testing.assert_allclose(smooth[0][0], smooth[1][0])
        self.assertGreater(smooth[0][0][1], .5)


PACK = Path(__file__).resolve().parent.parent / '82个绝地求生PUBG吃鸡游戏模型'


@unittest.skipUnless(PACK.is_dir(), 'Optional original asset pack absent')
class ActualPackGeometry(unittest.TestCase):
    def scene(self, number):
        return c.read_scene(next(PACK.glob(f'* ({number}).c4d')))

    def test_partial_material_selection_keeps_every_polygon_and_object_identity(self):
        scene = self.scene(26)
        parts = [m for m in scene['meshes'] if m['object_name'] == 'F_Hair_08']
        self.assertEqual(len(parts), 2)
        self.assertEqual(len({m['source_object_index'] for m in parts}), 1)
        self.assertEqual(sorted(len(m['vertices']) // 24 for m in parts), [132, 6001])
        neutral = next(m for m in parts if len(m['vertices']) // 24 == 132)
        self.assertIn('extraction_warnings', scene['materials'][neutral['material']])
        self.assertEqual(sum(len(m['vertices']) // 24 for m in scene['meshes']), 29744)

    def test_direct_child_hierarchy_is_applied_without_losing_nested_meshes(self):
        scene = self.scene(24)
        self.assertEqual(len(scene['metadata']['objects']), 3)
        self.assertEqual(sum(len(m['vertices']) // 24 for m in scene['meshes']), 5712)
        self.assertTrue(any(n['type'] == 1011146 for n in scene['metadata']['scene_notes']))
        for mesh in scene['meshes']:
            self.assertTrue(np.isfinite(mesh['vertices']).all())

    def test_degenerate_source_record_is_reported_and_valid_triangles_survive(self):
        scene = self.scene(58)
        obj = scene['metadata']['objects'][0]
        self.assertEqual(obj['source_triangles'], 9926)
        self.assertEqual(obj['zero_area_triangles_omitted'], [3297])
        self.assertEqual(obj['triangles'], 9925)
        points = np.asarray(scene['meshes'][0]['vertices']).reshape(-1, 8)
        np.testing.assert_allclose(np.linalg.norm(points[:, 5:], axis=1), 1)


if __name__ == '__main__':
    unittest.main()
