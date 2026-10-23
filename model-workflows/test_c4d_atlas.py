"""Small pixel/UV regressions for repeated, multi-material C4D atlases."""
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
from PIL import Image

from playful_world import _material_atlas, _pack_rectangles, _repeat_domain, export


class RepeatedAtlasChecks(unittest.TestCase):
    def test_whole_repeat_domain_keeps_continuous_uvs(self):
        uv = np.array([[0.75, 0.2], [1.25, 0.2], [0.75, 0.8]])
        original = uv.copy()
        domain, corrected = _repeat_domain(uv)
        self.assertEqual(domain, (0, 0, 2, 1))
        self.assertEqual(corrected, 0)
        np.testing.assert_equal(uv, original)

    def test_roundoff_does_not_add_an_extra_texture_tile(self):
        uv = np.array([[-3.0517578125e-5, 0.0], [1.00003, 1.]])
        domain, corrected = _repeat_domain(uv)
        self.assertEqual(domain, (0, 0, 1, 1))
        self.assertEqual(corrected, 2)
        np.testing.assert_equal(uv, [[0., 0.], [1., 1.]])

    def test_true_negative_uv_still_repeats(self):
        uv = np.array([[-0.2, -1.1], [1.4, 0.8]])
        self.assertEqual(_repeat_domain(uv)[0], (-1, -2, 2, 1))

    def test_large_material_layout_is_two_dimensional_without_bitmap_allocation(self):
        rectangles = [(2052, 2052)] * 4 + [(1028, 1028)] * 2 + [(516, 516), (260, 260)]
        width, height, placements = _pack_rectangles(rectangles)
        self.assertLessEqual(max(width, height), 8192)
        self.assertGreater(len({x for x, y in placements.values()}), 1)
        for i, (x, y) in placements.items():
            w, h = rectangles[i]
            self.assertLessEqual(x + w, width)
            self.assertLessEqual(y + h, height)
            for j, (xx, yy) in placements.items():
                if i >= j:
                    continue
                ww, hh = rectangles[j]
                self.assertTrue(x + w <= xx or xx + ww <= x or y + h <= yy or yy + hh <= y)

    def test_repeat_pixels_and_affine_seam_interpolation(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            pattern = np.zeros((4, 4, 4), dtype=np.uint8)
            pattern[:, :, 3] = 255
            pattern[:, :, 0] = np.arange(4)[None, :] * 60
            pattern[:, :, 1] = np.arange(4)[:, None] * 60
            texture = directory / "source.png"
            Image.fromarray(pattern).save(texture)
            scene = {"materials": [{"base_color_texture": str(texture)}],
                     "meshes": [{"material": 0}]}
            arrays = [np.array([[0, 0, 0, .9, .2, 0, 0, 1],
                                [1, 0, 0, 1.2, .2, 0, 0, 1],
                                [0, 1, 0, 2., .8, 0, 0, 1]], dtype=float)]
            reports = _material_atlas(scene, arrays, [0], directory / "atlas.png", maximum=64)
            report = reports[0]
            x, y = report["atlas_position"]
            self.assertEqual(report["repeat_domain"], [0, 0, 2, 1])
            with Image.open(directory / "atlas.png") as image:
                pixels = np.asarray(image)
                np.testing.assert_array_equal(pixels[y:y + 4, x:x + 4], pattern)
                np.testing.assert_array_equal(pixels[y:y + 4, x + 4:x + 8], pattern)
                # Interpolating .9 and 1.2 crosses the source seam at u=1.
                uv = (arrays[0][0, 3:5] + arrays[0][1, 3:5]) / 2
                actual = pixels[int(uv[1] * image.height), int(uv[0] * image.width)]
                expected = pattern[int(.2 * 4), int(1.05 % 1 * 4)]
                np.testing.assert_array_equal(actual, expected)
                # Right gutter repeats the first column for the exact u=2 end.
                np.testing.assert_array_equal(pixels[y:y + 4, x + 8], pattern[:, 0])

    def test_single_material_export_keeps_original_uv_and_pixels(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source = directory / "source.png"
            original = Image.new("RGBA", (3, 2), (10, 20, 30, 255))
            original.save(source)
            vertices = [0, 0, 0, -.2, 0., 0, 0, 1,
                        1, 0, 0, 1.5, 0., 0, 0, 1,
                        0, 1, 0, 0., 1., 0, 0, 1]
            scene = {"materials": [{"base_color_texture": str(source)}],
                     "meshes": [{"material": 0, "vertices": vertices}]}
            export(scene, directory / "result", name="check", namespace="check")
            result = json.loads((directory / "result/model.json").read_text())
            np.testing.assert_array_equal(np.array(result["vertices"]).reshape(-1, 8)[:, 3:5],
                                          np.array(vertices).reshape(-1, 8)[:, 3:5])
            with Image.open(directory / "result/texture.png") as image:
                self.assertEqual(image.size, original.size)
                np.testing.assert_array_equal(np.asarray(image), np.asarray(original))

    def test_tight_texel_fallback_preserves_interpolation_and_periodic_gutters(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            scene={"materials":[],"meshes":[]}; arrays=[]; patterns=[]
            for i in range(3):
                pattern=np.random.default_rng(i).integers(0,256,(4,4,4),dtype=np.uint8)
                source=root/f"source_{i}.png";Image.fromarray(pattern).save(source)
                scene["materials"].append({"base_color_texture":str(source)})
                scene["meshes"].append({"material":i})
                arrays.append(np.array([[0,0,0,.9,0,0,0,1],[1,0,0,1.1,0,0,0,1],
                                        [0,1,0,.95,1,0,0,1]],dtype=float))
                patterns.append(pattern)
            originals=[a.copy() for a in arrays]
            # Three full-repeat 12x8 tiles cannot fit16x16; cropped6x8 tiles can.
            reports=_material_atlas(scene,arrays,[0,1,2],root/"atlas.png",maximum=16)
            with Image.open(root/"atlas.png") as image:
                pixels=np.asarray(image);size=np.array(image.size)
            weights=np.random.default_rng(42).dirichlet([1,1,1],100)
            def sample(texture,uv):
                xy=uv*np.array([texture.shape[1],texture.shape[0]])-.5
                low=np.floor(xy).astype(int);t=xy-low
                x,y=low.T;tx,ty=t.T
                h,w=texture.shape[:2]
                return ((1-ty)[:,None]*((1-tx)[:,None]*texture[y%h,x%w]+tx[:,None]*texture[y%h,(x+1)%w])
                        +ty[:,None]*((1-tx)[:,None]*texture[(y+1)%h,x%w]+tx[:,None]*texture[(y+1)%h,(x+1)%w]))
            for i,report in enumerate(reports):
                self.assertIn("exact used texel",report["atlas_domain_mode"])
                np.testing.assert_array_equal(arrays[i][:,:3],originals[i][:,:3])
                np.testing.assert_array_equal(arrays[i][:,5:],originals[i][:,5:])
                source_uv=weights@originals[i][:,3:5];atlas_uv=weights@arrays[i][:,3:5]
                np.testing.assert_allclose(sample(pixels,atlas_uv),sample(patterns[i],source_uv),atol=1e-10)


if __name__ == "__main__":
    unittest.main()
