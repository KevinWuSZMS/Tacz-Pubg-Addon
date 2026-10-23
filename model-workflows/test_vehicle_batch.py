"""Fixed vehicle resolution and source identity are part of the batch contract."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from vehicle_batch import fixed_pixel, texture_remaps, vehicle_id, warnings_for


class VehicleBatchChecks(unittest.TestCase):
    def test_over_budget_vehicle_retains_the_requested_resolution(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            report = {"actual_resolution": 36, "target_met": False}
            with patch("vehicle_batch.convert", return_value=report) as convert:
                result = fixed_pixel(output / "scene.json", output, name="car", namespace="test",
                                     resolution=36, face_budget=1800)
            self.assertEqual(convert.call_args.kwargs["resolution"], 36)
            self.assertEqual(convert.call_args.kwargs["min_resolution"], 36)
            self.assertTrue(result["archive_only"])
            self.assertFalse(result["runtime_ready"])
            saved = json.loads((output / "report.json").read_text())
            self.assertEqual(saved["actual_resolution"], 36)

    def test_duplicate_or_colliding_source_names_have_distinct_stable_ids(self):
        paths = [Path("A/Car.c4d"), Path("B/Car.c4d"), Path("A-Car.c4d"), Path("A_Car.c4d")]
        values = [vehicle_id(path) for path in paths]
        self.assertEqual(len(set(values)), len(paths))
        self.assertEqual(values, [vehicle_id(path) for path in paths])

    def test_unused_neutral_material_is_not_reported_as_missing_vehicle_color(self):
        scene = {"meshes": [{"material": 0}], "materials": [
            {"name": "real", "warnings": ["retained warning"]},
            {"name": "unused", "texture_fallback": True, "extraction_warnings": ["not used"]}]}
        self.assertEqual(warnings_for(scene), [{"material": "real", "warning": "retained warning"}])

    def test_cross_extension_bitmap_requires_explicit_original_name_mapping(self):
        with tempfile.TemporaryDirectory() as temporary:
            bitmap = Path(temporary) / "C130_interior_D.tga"
            bitmap.write_bytes(b"test file")
            self.assertEqual(texture_remaps([f"C130_interior_D.jpg={bitmap}"]),
                             {"C130_interior_D.jpg": str(bitmap.resolve())})
            with self.assertRaises(ValueError):
                texture_remaps([f"C130_interior_D.jpg={bitmap}", f"c130_INTERIOR_d.JPG={bitmap}"])


if __name__ == "__main__":
    unittest.main()
