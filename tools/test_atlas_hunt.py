import unittest

import numpy as np
from atlas import hunt


def window(seed, row, col, score):
    return hunt.Window(seed, row, col, col, row, 50.0, score)


class WindowSumTest(unittest.TestCase):
    def test_integral_image_matches_direct_sums(self) -> None:
        rng = np.random.default_rng(3)
        mask = rng.random((9, 11)) < 0.4
        sums = hunt.window_sums(mask, 4, 2)
        self.assertEqual(sums.shape, (3, 4))
        for i, r in enumerate(range(0, 6, 2)):
            for j, c in enumerate(range(0, 8, 2)):
                self.assertEqual(sums[i, j], mask[r : r + 4, c : c + 4].sum())

    def test_shares_are_of_land_or_of_the_window(self) -> None:
        target = np.zeros((4, 4), bool)
        target[0, :2] = True
        land = np.zeros((4, 4), bool)
        land[0] = True
        self.assertEqual(hunt.shares(target, land, 4, 4)[0, 0], 50.0)
        self.assertEqual(hunt.shares(target, None, 4, 4)[0, 0], 12.5)
        self.assertEqual(hunt.shares(target, np.zeros((4, 4), bool), 4, 4)[0, 0], 0.0)


class SpacedTest(unittest.TestCase):
    def test_overlapping_windows_of_one_seed_yield_to_the_better(self) -> None:
        found = [window(1, 0, 0, 5.0), window(1, 2, 0, 9.0), window(2, 2, 0, 1.0)]
        found.append(window(1, 8, 8, 3.0))
        picked = hunt.spaced(found, side=8, count=5)
        self.assertEqual([(w.seed, w.score) for w in picked], [(1, 9.0), (1, 3.0), (2, 1.0)])


class CoverTest(unittest.TestCase):
    def test_greedy_cover_and_never_active_stages(self) -> None:
        table = {
            ("a",): {"passes": 50.0, "shore": 20.0, "cold": 0.0, "volcanic": 0.0},
            ("b",): {"passes": 40.0, "shore": 0.0, "cold": 3.0, "volcanic": 0.2},
            ("c",): {"passes": 0.0, "shore": 0.0, "cold": 0.5, "volcanic": 1.5},
        }
        stages = ["passes", "shore", "cold", "volcanic", "carver"]
        picked, covered, weak, uncovered = hunt.cover(table, stages, 1.0)
        self.assertEqual(picked, [("a",), ("b",), ("c",)])
        self.assertEqual(covered["cold"], (("b",), 3.0))
        self.assertEqual(covered["volcanic"], (("c",), 1.5))
        self.assertEqual((weak, uncovered), ([], ["carver"]))

    def test_sparse_stage_takes_a_picked_window_that_shows_it(self) -> None:
        table = {
            ("a",): {"passes": 50.0, "cut": 0.2},
            ("b",): {"passes": 0.0, "cut": 0.6},
        }
        picked, covered, weak, uncovered = hunt.cover(table, ["passes", "cut"], 1.0)
        self.assertEqual((picked, weak, uncovered), ([("a",)], ["cut"], []))
        self.assertEqual(covered["cut"], (("a",), 0.2))

    def test_sparse_stage_adds_its_best_window_when_none_picked_shows_it(self) -> None:
        table = {
            ("a",): {"passes": 50.0, "cut": 0.0},
            ("b",): {"passes": 0.0, "cut": 0.6},
            ("c",): {"passes": 0.0, "cut": 0.3},
        }
        picked, covered, weak, _ = hunt.cover(table, ["passes", "cut"], 1.0)
        self.assertEqual(picked, [("a",), ("b",)])
        self.assertEqual(covered["cut"], (("b",), 0.6))


if __name__ == "__main__":
    unittest.main()
