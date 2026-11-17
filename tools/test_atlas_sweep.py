import unittest

from atlas.batch import metric_value

SUMMARY = {
    "landPct": 41.5,
    "surfaceY": {"p50Land": 120, "label": "x"},
    "cost": {"overall": {"medianMicros": None}},
    "stages": [{"name": "mountain", "activeLandPct": 12.5}],
}


class MetricValueTest(unittest.TestCase):
    def test_top_level_and_dotted_paths(self) -> None:
        self.assertEqual(metric_value(SUMMARY, "landPct"), 41.5)
        self.assertEqual(metric_value(SUMMARY, "surfaceY.p50Land"), 120)
        self.assertIsNone(metric_value(SUMMARY, "cost.overall.medianMicros"))

    def test_stage_shorthand(self) -> None:
        self.assertEqual(metric_value(SUMMARY, "stages.mountain"), 12.5)

    def test_unknown_or_non_numeric_paths_fail(self) -> None:
        for path in ("nope", "surfaceY.nope", "surfaceY.label", "surfaceY", "stages.cold"):
            with self.subTest(path=path), self.assertRaises(SystemExit):
                metric_value(SUMMARY, path)


if __name__ == "__main__":
    unittest.main()
