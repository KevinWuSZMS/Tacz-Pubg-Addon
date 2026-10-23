"""The fast batch preset must preserve previously accepted per-asset quality."""
import unittest

from batch_extract import pixel_settings


class BatchQualityChecks(unittest.TestCase):
    def test_medical_case_retains_approved_density(self):
        self.assertEqual(pixel_settings(17,24),{"resolution":36,"min_resolution":36})
        self.assertEqual(pixel_settings(17,48),{"resolution":48,"min_resolution":48})

    def test_every_item_keeps_the_same_quality_floor_without_adaptation(self):
        self.assertEqual(pixel_settings(25,24),{"resolution":36,"min_resolution":36})
        self.assertEqual(pixel_settings(25,36),{"resolution":36,"min_resolution":36})


if __name__=="__main__":
    unittest.main()
