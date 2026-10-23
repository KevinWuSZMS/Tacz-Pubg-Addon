"""Verify the exact supplied R19.024 first-aid source before accepting its version."""
from pathlib import Path
import unittest

import numpy as np

import c4d_reader as c


SOURCE = (Path(__file__).resolve().parent.parent / '82个绝地求生PUBG吃鸡游戏模型'
          / '急救包' / '绝地求生 (22).c4d')


@unittest.skipUnless(SOURCE.is_file(), 'Optional original first-aid scene absent')
class ActualFirstAidGeometry(unittest.TestCase):
    def test_r19024_preserves_complete_mesh_uvs_and_real_bitmap_links(self):
        scene = c.read_scene(SOURCE)
        self.assertEqual(scene['metadata']['source_file_version'], 19024)
        self.assertEqual(scene['metadata']['source_sha256'],
                         '6062bcff3923d52577ea4e42708de9b1be70f9286e394391d80a17d6eadca2a1')
        self.assertEqual(len(scene['meshes']), 1)
        obj = scene['metadata']['objects'][0]
        self.assertEqual(obj['name'], 'SM_FirstAidPackMilitary_01')
        self.assertEqual((obj['points'], obj['source_triangles'], obj['triangles']),
                         (825, 998, 998))
        self.assertEqual(obj['uv_channels'], 2)
        self.assertEqual(obj['selected_uv_channels'], ['UVChannel_1'])
        self.assertEqual(obj['zero_area_triangles_omitted'], [])
        self.assertEqual(obj['warnings'], [])
        mesh = scene['meshes'][0]
        vertices = np.asarray(mesh['vertices']).reshape(-1, 8)
        self.assertEqual(len(vertices), 998 * 3)
        self.assertTrue(np.isfinite(vertices).all())
        np.testing.assert_allclose(np.linalg.norm(vertices[:, 5:], axis=1), 1)
        material = scene['materials'][mesh['material']]
        self.assertEqual(material['name'], 'MI_SM_FirstAidPackMilitary')
        self.assertEqual(material['base_color_source'], 'linked_color_bitmap')
        for key, suffix in [('base_color_texture', 'D'), ('normal_texture', 'N'),
                            ('roughness_metallic_texture', 'RM')]:
            texture = Path(material[key])
            self.assertEqual(texture.name, f'T_SM_FirstAidPackMilitary_{suffix}.tga')
            self.assertTrue(texture.is_file())


if __name__ == '__main__':
    unittest.main()
