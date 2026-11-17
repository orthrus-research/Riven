import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from atlas.batch import subsample_mismatches
from atlas.paint import Atlas
from terrain_atlas import progressive_steps


def raw(directory: Path, step: int, surface, habitat, habitats, micros) -> Atlas:
    directory.mkdir(parents=True)
    surface = np.asarray(surface, "<i2")
    meta = {
        "minX": -16,
        "minZ": -16,
        "step": step,
        "width": surface.shape[1],
        "height": surface.shape[0],
        "stages": [{"name": "cut", "reasons": habitats}],
        "habitats": habitats,
    }
    (directory / "atlas.json").write_text(json.dumps(meta))
    surface.tofile(directory / "surface.i16")
    np.asarray(habitat, np.uint8).tofile(directory / "habitat.u8")
    # Reasons use the same per-window naming as habitats here.
    np.asarray(habitat, np.uint8).tofile(directory / "reason_cut.u8")
    np.asarray(micros, "<f4").tofile(directory / "micros.f32")
    return Atlas(directory)


class ProgressiveStepsTest(unittest.TestCase):
    def test_default_levels_coarsest_then_four_times_the_step(self) -> None:
        self.assertEqual(progressive_steps(8192, 1, []), [256, 4])
        self.assertEqual(progressive_steps(2048, 16, []), [64])
        self.assertEqual(progressive_steps(512, 16, []), [])

    def test_requested_levels_are_sorted_coarsest_first(self) -> None:
        self.assertEqual(progressive_steps(2048, 16, [32, 128, 64, 32]), [128, 64, 32])

    def test_levels_must_share_the_lattice(self) -> None:
        for bad in (16, 8, 48, 768):
            with self.subTest(step=bad), self.assertRaises(SystemExit):
                progressive_steps(2048, 16, [bad])


class SubsampleTest(unittest.TestCase):
    def test_every_kth_sample_matches_with_codes_compared_by_name(self) -> None:
        fine_surface = np.arange(16).reshape(4, 4)
        fine_habitat = np.array([[0, 1, 1, 1], [1] * 4, [1, 1, 0, 1], [1] * 4])
        with tempfile.TemporaryDirectory() as root:
            fine = raw(Path(root, "f"), 8, fine_surface, fine_habitat, ["ocean", "plains"], 1)
            # The coarse window lists only its own habitats, so codes differ while names match.
            coarse = raw(
                Path(root, "c"),
                16,
                fine_surface[::2, ::2],
                [[1, 0], [0, 1]],
                ["plains", "ocean"],
                [[9, 9], [9, 9]],
            )
            self.assertEqual(subsample_mismatches(coarse, fine), (3, []))

    def test_a_changed_sample_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            fine = raw(Path(root, "f"), 8, np.zeros((4, 4)), np.zeros((4, 4)), ["ocean"], 1)
            coarse = raw(Path(root, "c"), 16, [[0, 0], [0, 5]], np.zeros((2, 2)), ["ocean"], 1)
            self.assertEqual(subsample_mismatches(coarse, fine), (3, ["surface.i16"]))


if __name__ == "__main__":
    unittest.main()
