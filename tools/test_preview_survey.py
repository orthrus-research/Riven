import json
import tempfile
import unittest
from pathlib import Path

import preview_survey


def survey(directory: Path, top: bytes) -> Path:
    directory.mkdir()
    summary = {
        "seed": 1,
        "minX": 100,
        "minZ": -50,
        "size": 4,
        "footprints": [{"footprint": 2, "cells": 2}],
    }
    (directory / "summary.json").write_text(json.dumps(summary))
    (directory / "f2-top.i16").write_bytes(top)
    for kind in ("ground", "water"):
        (directory / f"f2-{kind}.i16").write_bytes(bytes(8))
    (directory / "f2-kind.u8").write_bytes(bytes(4))
    return directory


class CompareTest(unittest.TestCase):
    def test_identical_surveys_report_every_raster_the_same(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            a = survey(Path(root) / "a", bytes(8))
            b = survey(Path(root) / "b", bytes(8))
            (line,) = preview_survey.compare(a, b)
            self.assertEqual("differ" in line, False)
            self.assertIn("top same", line)

    def test_a_differing_cell_is_counted_and_placed_in_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            a = survey(Path(root) / "a", bytes(8))
            # The third cell (row 1, column 0) of a 2 x 2 raster of 2-block cells.
            b = survey(Path(root) / "b", bytes(4) + b"\x05\x00" + bytes(2))
            (line,) = preview_survey.compare(a, b)
            self.assertIn("top 1 differ [(100,-48)]", line)
            self.assertIn("ground same", line)

    def test_different_windows_are_refused(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            a = survey(Path(root) / "a", bytes(8))
            b = survey(Path(root) / "b", bytes(8))
            summary = json.loads((b / "summary.json").read_text())
            summary["minX"] = 101
            (b / "summary.json").write_text(json.dumps(summary))
            with self.assertRaises(ValueError):
                preview_survey.compare(a, b)


if __name__ == "__main__":
    unittest.main()
