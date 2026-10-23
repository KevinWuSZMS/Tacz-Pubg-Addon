"""Verified R14 VehicleS records, including material-selection cache variants."""
from __future__ import annotations

from pathlib import Path
import struct
import unittest

import numpy as np

import c4d_reader as c
from test_c4d_geometry import transform_node


class R14Records(unittest.TestCase):
    def test_only_verified_document_versions_are_accepted(self):
        for version in (14042, 19024, 19068):
            root = c._read_document(b'QC4DC4D6' + struct.pack('>I', version) + b'\0')
            self.assertEqual(root.level, version)
        with self.assertRaisesRegex(c.C4DReadError, 'Unsupported file version'):
            c._read_document(b'QC4DC4D6' + struct.pack('>I', 14043) + b'\0')

    def test_level9_uses_the_verified_complete_transform_layout(self):
        node = transform_node()
        node.children(110052)[0].level = 9
        matrix, info = c._object_matrix(node)
        self.assertEqual(info['position'], (2., 3., 4.))
        np.testing.assert_allclose(c._transform(matrix, (1., 0., 0.)), (3., 3., 4.))
        node.children(110052)[0].items[8].kind = 19
        with self.assertRaisesRegex(c.C4DReadError, 'field layout'):
            c._object_matrix(node)

    def test_material_selection_cache_does_not_change_inclusive_ranges(self):
        def node(cache):
            return c.Chunk(5673, 0, 0, [c.Chunk(110011, 1, 0,
                [c.Value(15, v, 0) for v in (2, 4, 7, 10, 12, cache)])])
        for cache in (0, 1, 12):
            self.assertEqual(c._selection(node(cache), 13), {4, 5, 6, 7, 10, 11, 12})
        with self.assertRaisesRegex(c.C4DReadError, 'cache'):
            c._selection(node(13), 13)


VEHICLES = Path(__file__).resolve().parent.parent / '20180214152952' / 'VehicleS'


@unittest.skipUnless(VEHICLES.is_dir(), 'Optional original VehicleS pack absent')
class ActualVehicleGeometry(unittest.TestCase):
    def test_boat_preserves_all_material_selections_and_uvs(self):
        scene = c.read_scene(VEHICLES / 'Boat_PG117' / 'Boat_PG117.c4d')
        self.assertEqual(scene['metadata']['source_file_version'], 14042)
        self.assertEqual(len(scene['metadata']['objects']), 1)
        self.assertEqual(sorted(len(m['vertices']) // 24 for m in scene['meshes']),
                         [12, 18864, 25698])
        self.assertEqual(sum(len(m['vertices']) // 24 for m in scene['meshes']), 44574)
        self.assertEqual(scene['metadata']['objects'][0]['points'], 23787)
        for mesh in scene['meshes']:
            vertices = np.asarray(mesh['vertices']).reshape(-1, 8)
            self.assertTrue(np.isfinite(vertices).all())
            np.testing.assert_allclose(np.linalg.norm(vertices[:, 5:], axis=1), 1)
            material = scene['materials'][mesh['material']]
            self.assertEqual(material['base_color_source'],
                             'constant_material_color' if len(vertices) == 36 else 'linked_color_bitmap')

    def test_uaz_preserves_complete_body_and_five_material_submeshes(self):
        scene = c.read_scene(VEHICLES / 'Uaz' / 'Uaz.c4d')
        self.assertEqual(len(scene['metadata']['objects']), 1)
        self.assertEqual(len(scene['meshes']), 5)
        self.assertEqual(sum(len(m['vertices']) // 24 for m in scene['meshes']), 70587)
        self.assertFalse(scene['metadata']['objects'][0]['warnings'])


if __name__ == '__main__':
    unittest.main()
