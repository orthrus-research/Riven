import unittest

import numpy as np
from atlas.paint import cost_groups, cost_stats


class CostStatsTest(unittest.TestCase):
    def test_median_p95_and_max(self) -> None:
        stats = cost_stats(np.arange(1, 101, dtype=np.float32))
        self.assertEqual(stats["columns"], 100)
        self.assertEqual(stats["medianMicros"], 50.5)
        self.assertEqual(stats["p95Micros"], 95.0)
        self.assertEqual(stats["maxMicros"], 100.0)

    def test_empty_has_no_values(self) -> None:
        self.assertEqual(cost_stats(np.array([]))["medianMicros"], None)

    def test_groups_name_codes_and_report_255_as_other(self) -> None:
        micros = np.array([[1, 2], [30, 40]], np.float32)
        codes = np.array([[0, 0], [255, 1]], np.uint8)
        groups = cost_groups(micros, codes, ["PLAIN", "MOUNTAIN"], "marine")
        self.assertEqual(groups["PLAIN"]["columns"], 2)
        self.assertEqual(groups["PLAIN"]["maxMicros"], 2.0)
        self.assertEqual(groups["MOUNTAIN"]["medianMicros"], 40.0)
        self.assertEqual(groups["marine"]["medianMicros"], 30.0)


if __name__ == "__main__":
    unittest.main()
