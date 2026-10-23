"""Regression checks against the supplied pack's real material edge cases."""
from pathlib import Path
import tempfile
import unittest

from PIL import Image

import c4d_reader
from c4d_materials import read_materials


PACK = Path(__file__).resolve().parent.parent / "82个绝地求生PUBG吃鸡游戏模型"


class ExplicitTextureRootChecks(unittest.TestCase):
    def test_explicit_override_precedes_local_without_changing_reference(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'vehicle.c4d'
            source.write_bytes(b'fixture')
            (root / 'C130_interior_D.jpg').write_bytes(b'existing local')
            replacement = root / 'actual_color.tga'
            replacement.write_bytes(b'explicit replacement')
            record = {'filename': 'C130_interior_D.jpg'}
            resolved = c4d_reader._resolve_texture(
                source, record['filename'], texture_overrides={'C130_interior_D.jpg': replacement},
                resolution_info=record)
            self.assertEqual(resolved, replacement.resolve())
            self.assertEqual(record['filename'], 'C130_interior_D.jpg')
            self.assertEqual(record['resolution_method'], 'explicit_override')
            self.assertEqual(len(record['texture_sha256']), 64)

    def test_source_local_wins_and_external_resolution_records_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'source' / 'vehicle.c4d'
            source.parent.mkdir()
            source.write_bytes(b'fixture')
            local = source.parent / 'tex' / 'Exact_D.tga'
            local.parent.mkdir()
            external_root = root / 'extra'
            external_root.mkdir()
            external = external_root / 'Exact_D.tga'
            local.write_bytes(b'local')
            external.write_bytes(b'external')
            self.assertEqual(c4d_reader._resolve_texture(source, 'Exact_D.tga', [external_root]), local.resolve())
            local.unlink()
            details = {}
            self.assertEqual(c4d_reader._resolve_texture(source, 'Exact_D.tga', [external_root],
                                                        resolution_info=details), external.resolve())
            self.assertEqual(details['resolution_source'], 'explicit_texture_root')
            self.assertEqual(details['resolution_root'], str(external_root.resolve()))
            self.assertEqual(len(details['texture_sha256']), 64)
            with self.assertRaisesRegex(c4d_reader.C4DReadError, 'found 0'):
                c4d_reader._resolve_texture(source, 'Exact_N.tga', [external_root])

    def test_exact_duplicate_files_require_identical_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'source' / 'vehicle.c4d'
            source.parent.mkdir()
            roots = [root / 'one', root / 'two']
            for directory in roots:
                directory.mkdir()
                (directory / 'color.tga').write_bytes(b'identical')
            details = {}
            c4d_reader._resolve_texture(source, 'color.tga', roots, resolution_info=details)
            self.assertEqual(len(details['identical_texture_copies']), 2)
            (roots[1] / 'color.tga').write_bytes(b'different')
            with self.assertRaises(c4d_reader.C4DTextureAmbiguityError):
                c4d_reader._resolve_texture(source, 'color.tga', roots)


@unittest.skipUnless(PACK.is_dir(), "Supplied C4D fixture pack is not present")
class MaterialExtractionChecks(unittest.TestCase):
    def material_set(self, number):
        source = next(PACK.glob(f"* ({number}).c4d"))
        return read_materials(c4d_reader._read_document(source.read_bytes()), source)

    def test_original_medical_bitmap_and_normal_links(self):
        materials, _ = self.material_set(17)
        medical = next(m for m in materials if m["name"] == "MI_SM_DoctorSCase")
        self.assertEqual(Path(medical["base_color_texture"]).name, "T_SM_DoctorSCase_D.tga")
        self.assertEqual(Path(medical["normal_texture"]).name, "T_SM_DoctorSCase_N.tga")
        self.assertEqual(Path(medical["roughness_metallic_texture"]).name, "T_SM_DoctorSCase_RM.tga")
        self.assertNotIn("texture_fallback", medical)

    def test_color_link_does_not_depend_on_d_suffix(self):
        materials, _ = self.material_set(8)
        color = next(m for m in materials if m["name"] == "Material #86")
        self.assertEqual(Path(color["base_color_texture"]).name, "Backpack_a.tga")

    def test_numbered_basecolor_names_use_actual_shader_link(self):
        materials, _ = self.material_set(40)
        tire = next(m for m in materials if m["name"] == "ve_motorcycle_tire")
        self.assertEqual(Path(tire["base_color_texture"]).name,
                         "bike_lowpoly_ve_motorcycle_tire_BaseColor_1.tga")
        self.assertEqual(tire["base_color_texture"], tire["source_channels"]["color"]["texture"])

    def test_unused_plugin_sky_does_not_block_mesh_material(self):
        materials, _ = self.material_set(2)
        self.assertTrue(any(m["name"] == "Aimpoint2X_01" for m in materials))
        self.assertFalse(any(m.get("source_material_type") == 5705 for m in materials))

    def test_actual_constant_material_has_explicit_bitmap(self):
        materials, _ = self.material_set(1)
        glass = next(m for m in materials if m["name"] == "ACOG_InnerGlass")
        self.assertEqual(glass["base_color_source"], "constant_material_color")
        self.assertNotIn("texture_fallback", glass)
        with Image.open(glass["base_color_texture"]) as image:
            self.assertEqual(image.size, (1, 1))
        self.assertFalse(Path(glass["base_color_texture"]).is_relative_to(PACK))

    def test_missing_color_is_reported_without_guessing(self):
        materials, _ = self.material_set(43)
        missing = [m for m in materials if m.get("base_color_source") == "declared_material_color_fallback"]
        self.assertEqual({m["name"] for m in missing},
                         {"ButtStock_01", "SKS_CheePad", "Vector_Stock", "Sniper_CheekPad_AWM"})
        for material in missing:
            self.assertTrue(material["texture_fallback"])
            self.assertEqual(material["source_channels"]["color"]["status"], "missing_bitmap")
            self.assertTrue(material["extraction_warnings"])

    def test_missing_normal_does_not_discard_valid_color(self):
        materials, _ = self.material_set(65)
        rifle = next(m for m in materials if m["name"] == "Wp_Gun_M24")
        self.assertEqual(Path(rifle["base_color_texture"]).name, "Wp_Gun_M24_d.tga")
        self.assertEqual(rifle["source_channels"]["normal"]["status"], "missing_bitmap")
        self.assertNotIn("normal_texture", rifle)
        self.assertNotIn("texture_fallback", rifle)

    def test_null_and_unassigned_aliases_are_explicit(self):
        materials, aliases = self.material_set(21)
        self.assertEqual(aliases[None], aliases[b"\0" * 8])
        fallback = materials[aliases[None]]
        self.assertEqual(fallback["name"], "__unassigned_source_material__")
        self.assertTrue(fallback["texture_fallback"])

    def test_glass_container_nil_parameter_is_readable(self):
        materials, _ = self.material_set(12)
        self.assertTrue(any(m["base_color_source"] == "constant_material_color" for m in materials))


if __name__ == "__main__":
    unittest.main()
