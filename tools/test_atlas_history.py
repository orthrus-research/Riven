import csv
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import numpy as np
from atlas import history

SUMMARY = {
    "seed": 1,
    "terrainVersion": 148,
    "digest": "abc",
    "bounds": {"minX": 0, "step": 16},
    "threshold": 1.0,
    "fingerprints": {"riven-x.json": "ff"},
    "landPct": 65.2,
    "surfaceY": {"min": 39, "p50Land": None},
    "stages": [
        {
            "name": "volcanic",
            "activeLandPct": 0.5,
            "reasons": [{"label": "COLUMN_SLOPE", "pct": 2.0, "color": [1, 2, 3]}],
            "landReasons": {"COLUMN_SLOPE": 4},
        }
    ],
    "sites": [{"lane": "COLD", "built": 0, "rejected": {"SHARED_BUDGET": 1}}],
    "legends": {"biomes": [{"label": "plains", "color": [1, 2, 3], "pct": 12.5}]},
    "cost": {"scale": [14.7, 803.7]},
    "future": {"contourRuns": 7, "flag": True},
}


class FlattenTest(unittest.TestCase):
    def test_numeric_leaves_become_columns_keyed_by_identity(self) -> None:
        self.assertEqual(
            history.flatten(SUMMARY),
            {
                "landPct": 65.2,
                "surfaceY.min": 39,
                "stages.volcanic.activeLandPct": 0.5,
                "sites.COLD.built": 0,
                "sites.COLD.rejected.SHARED_BUDGET": 1,
                "legends.biomes.plains.pct": 12.5,
                "cost.scale.0": 14.7,
                "cost.scale.1": 803.7,
                "future.contourRuns": 7,
                "future.flag": 1,
            },
        )


class WallsTest(unittest.TestCase):
    def test_share_counts_land_steps_of_two_or_more(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            raw = Path(root)
            (raw / "atlas.json").write_text(json.dumps({"width": 2, "height": 2}))
            np.array([0, 1, 0, 0], np.uint8).tofile(raw / "flags.u8")  # one marine pixel
            np.array([5, 9, 2, 1], np.uint8).tofile(raw / "walls.u8")
            self.assertEqual(history.walls_share(raw), 66.667)

    def test_absent_without_the_walls_raster(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            self.assertIsNone(history.walls_share(Path(root)))


class FailureReasonTest(unittest.TestCase):
    def test_javac_failure_names_the_first_error_and_the_count(self) -> None:
        error = SystemExit(
            "javac failed:\nC:\\cache\\tools\\checks\\TerrainAtlas.java:676: error: cannot find"
            " symbol\n    geo.craton()\n4 errors\n"
        )
        self.assertEqual(
            history.failure_reason(error),
            "sampler does not compile against this tree: TerrainAtlas.java:676: error: cannot"
            " find symbol (4 errors)",
        )

    def test_sampler_failure_names_the_last_exception(self) -> None:
        error = SystemExit(
            "sampler failed; see log\nException in thread main\n"
            "java.lang.IllegalStateException: no stage cold\n\tat X.y(X.java:1)"
        )
        self.assertEqual(
            history.failure_reason(error),
            "sampler failed: java.lang.IllegalStateException: no stage cold",
        )

    def test_git_failure_uses_its_stderr(self) -> None:
        error = subprocess.CalledProcessError(128, ["git"], stderr=b"fatal: bad object\n")
        self.assertEqual(history.failure_reason(error), "fatal: bad object")


class OutputTest(unittest.TestCase):
    rows: list[dict] = [
        {"ref": "old", "commit": "1" * 12, "status": "skipped", "reason": "no compile"},
        {"ref": "a", "commit": "2" * 12, "status": "ok", "metrics": {"x": 1, "y": 5}},
        {"ref": "worktree", "commit": "", "status": "ok", "metrics": {"x": 2, "y": 5, "z": 3}},
    ]

    def test_csv_keeps_skipped_rows_and_unions_metrics(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "history.csv"
            metrics = history.write_csv(self.rows, path)
            self.assertEqual(metrics, ["x", "y", "z"])
            with path.open(encoding="utf-8") as stream:
                read = list(csv.DictReader(stream))
            self.assertEqual([r["status"] for r in read], ["skipped", "ok", "ok"])
            self.assertEqual(read[0]["reason"], "no compile")
            self.assertEqual((read[1]["z"], read[2]["z"]), ("", "3"))

    def test_varying_ignores_skipped_rows(self) -> None:
        self.assertEqual(history.varying(self.rows, ["x", "y", "z"]), ["x", "z"])


if __name__ == "__main__":
    unittest.main()
