"""The telemetry ledger: a run's compact record, its dated place, the index order and the export."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from observability import tables, telemetry
from observability.profiles import ROOT


def timing(p50, p99, count=100, over50=0):
    return {
        "count": count,
        "p50Ms": p50,
        "p95Ms": p99 * 0.9,
        "p99Ms": p99,
        "maxMs": p99 * 2,
        "meanMs": p50,
        "exceedanceCountsAboveNanos": {"16666667": 3, "33333333": 1, "50000000": over50},
        "excessOver50msNanos": 0,
    }


def scenario(name, start, passed=True):
    return {
        "scenario": name,
        "server": {
            "passed": passed,
            "startUtc": start,
            "elapsedNanos": 30_000_000_000,
            "measurementMode": "diagnostic-storage-profile",
            "completeServerTicks": timing(20.0, 50.0, over50=2),
            "runtime": {
                "os": "Windows 11",
                "processors": 32,
                "java": "26",
                "maxHeapBytes": 8 << 30,
            },
            "packets": {"cubeCount": 900, "packetCount": 9, "serializedPayloadBytes": 1 << 20},
            "saveSubmissionNanos": 2_000_000,
            "blockingFlushNanos": 0,
        },
        "client": {"frameIntervals": timing(8.0, 20.0), "glRenderer": "GPU", "gcCollections": 3},
    }


class Ledger(unittest.TestCase):
    def setUp(self):
        base = ROOT / ".local/actions-tests"
        base.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="telemetry-", dir=base)
        home = Path(self.temporary.name)
        self.instances, self.ledger = home / "instances", home / "ledger"
        patches = [
            mock.patch.object(telemetry, "INSTANCES", self.instances),
            mock.patch.object(telemetry, "LEDGER", self.ledger),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def tearDown(self):
        self.temporary.cleanup()

    def instance(self, name, start, passed=True):
        directory = self.instances / name
        (directory / "runs/write").mkdir(parents=True)
        result = {
            "passed": passed,
            "scenarios": [scenario("full-view", start), scenario("idle", start, passed)],
            "benchmarkWorkload": {"id": "terrain-pipeline-fixed-v1"},
        }
        manifest = {
            "name": name,
            "seed": 8675309,
            "observability": {"mode": "timing"},
            "cubatureDelivery": "BASELINE",
            "viewDistance": {"horizontal": 12},
            "sourceArtifact": {
                "path": "E:/x/.local/frozen/build-abc1234/riven.jar",
                "sha256": "f" * 64,
            },
        }
        (directory / "runs/write/result.json").write_text(json.dumps(result), encoding="utf-8")
        (directory / "standalone.json").write_text(json.dumps(manifest), encoding="utf-8")
        return directory

    def test_a_capture_becomes_one_dated_compact_record(self):
        directory = self.instance("riven-a-001", "2026-11-01T04:57:58.884096800Z")
        (path,) = telemetry.collect(directory, commits=False)
        self.assertEqual(path, self.ledger / "runs/2026/11-01/045758Z-capture-riven-a-001.json")
        record = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(record["schema"], telemetry.SCHEMA)
        self.assertEqual(
            record["versions"]["riven"], {"build": "build-abc1234", "sha256": "f" * 12}
        )
        full = record["scenarios"][0]
        self.assertEqual(full["tick"]["p50"], 20.0)
        self.assertEqual(full["tick"]["over50ms"], 2)
        self.assertEqual(full["frame"]["over16_7ms"], 3)
        self.assertEqual(full["cubes"]["payloadMiB"], 1.0)
        self.assertEqual(full["save"]["submission"], 2.0)
        self.assertLess(path.stat().st_size, 4096)

    def test_offsets_are_normalized_to_utc(self):
        self.assertEqual(telemetry._utc("2026-11-01T00:30:00-04:00"), "2026-11-01T04:30:00Z")
        self.assertEqual(telemetry._utc("2026-11-01T04:30:00.123456+00:00"), "2026-11-01T04:30:00Z")

    def test_delivery_costs_keep_phase_units_counts_and_unknown_empty_install_cost(self):
        fixture = scenario("full-view", "2026-11-01T04:30:00Z")
        fixture["server"]["deliveryTickCosts"] = {
            "ticks": 100,
            "storedTicks": 90,
            "droppedTicks": 10,
            "maximumTickAccountingErrorNanos": 0,
            "complete": timing(20, 50),
            "phases": {"CUBE_CONSTRUCT": timing(2, 4), "OTHER": timing(18, 46)},
            "installedCubes": 50,
            "emptyCubes": 20,
            "emptyCubeShare": 0.4,
            "installMsPerCube": 4.123456,
            "installPartsMsPerCube": {"CUBE_CONSTRUCT": 4.123456},
            "scope": "Exclusive server-thread phases inside MinecraftServer.tick",
        }
        result = telemetry._scenario(fixture, None)["delivery"]
        self.assertEqual(result["tick"], {"mean": 20, "p95": 45.0, "count": 100})
        self.assertEqual(result["phases"]["CUBE_CONSTRUCT"]["mean"], 2)
        self.assertEqual(result["installedCubes"], 50)
        self.assertEqual(result["emptyCubeShare"], 0.4)
        self.assertEqual(result["installMsPerCube"], 4.1235)
        self.assertEqual(result["installPartsMsPerCube"], {"CUBE_CONSTRUCT": 4.1235})
        self.assertEqual((result["storedTicks"], result["droppedTicks"]), (90, 10))
        self.assertEqual(result["maximumTickAccountingErrorNanos"], 0)
        fixture["server"]["deliveryTickCosts"]["installMsPerCube"] = None
        fixture["server"]["deliveryTickCosts"]["emptyCubeShare"] = None
        self.assertIsNone(telemetry._scenario(fixture, None)["delivery"]["installMsPerCube"])
        self.assertIsNone(telemetry._scenario(fixture, None)["delivery"]["emptyCubeShare"])

    def test_missing_delivery_costs_remain_absent(self):
        fixture = scenario("idle", "2026-11-01T04:30:00Z")
        self.assertNotIn("delivery", telemetry._scenario(fixture, None))

    def test_the_index_numbers_runs_in_the_order_they_ran(self):
        telemetry.collect(self.instance("riven-late-001", "2026-11-02T10:00:00Z"), commits=False)
        telemetry.collect(self.instance("smoke-early", "2026-10-30T09:00:00Z"), commits=False)
        index = json.loads((self.ledger / "index.json").read_text(encoding="utf-8"))
        self.assertEqual(index["runs"], 2)
        self.assertEqual(
            [r["instance"] for r in index["runsInOrder"]], ["smoke-early", "riven-late-001"]
        )
        self.assertEqual([r["run"] for r in index["runsInOrder"]], [1, 2])
        self.assertEqual(index["byKind"], {"smoke": 1, "capture": 1})

    def test_the_export_carries_no_private_detail(self):
        telemetry.collect(
            self.instance("riven-secret-001", "2026-11-01T04:57:58Z", passed=False), commits=False
        )
        target = self.ledger / "export/harness-runs.json"
        summary = telemetry.export(target)
        self.assertEqual((summary["runs"], summary["records"]), (1, 2))
        text = target.read_text(encoding="utf-8")
        for private in ["riven-secret-001", "build-abc1234", "ffffffff", ".local", "E:/"]:
            self.assertNotIn(private, text)
        records = json.loads(text)["records"]
        self.assertEqual({r["mode"] for r in records}, {"diagnostic"})
        self.assertEqual(
            [r["passed"] for r in records], [False, False], "a failed run fails its scenarios"
        )
        self.assertEqual(records[0]["tick"]["p99"], 50.0)

    def test_tables_flatten_runs_scenarios_and_ci_summaries(self):
        telemetry.collect(
            self.instance("riven-table-001", "2026-11-01T04:57:58Z", passed=False), commits=False
        )
        actions = self.ledger.parent / "actions"
        (actions / "20261101-120000").mkdir(parents=True)
        summary = {
            "runId": "20261101-120000",
            "targets": ["full"],
            "trees": {"riven": "a" * 40, "cubature": "b" * 40},
            "treesChanged": False,
            "seconds": 12.34,
            "jobs": {
                "quality": {
                    "status": "passed",
                    "steps": [{"name": "q", "exitCode": 0, "seconds": 2}],
                },
                "smoke": {
                    "status": "failed",
                    "steps": [
                        {"name": "capture", "exitCode": 0, "seconds": 3},
                        {"name": "inspect", "exitCode": 1, "seconds": 1.5},
                    ],
                },
                "atlas-golden": {"status": "skipped", "reason": "needs smoke"},
            },
        }
        (actions / "20261101-120000/summary.json").write_text(json.dumps(summary), "utf-8")
        target = self.ledger / "tables"
        with mock.patch.object(tables, "ACTIONS", actions):
            counts = tables.write(target)
        self.assertEqual(counts, {"runs": 1, "scenarios": 2, "ci-runs": 1, "ci-jobs": 3})
        (run,) = json.loads((target / "runs.json").read_text(encoding="utf-8"))
        self.assertEqual(
            (run["run"], run["instance"], run["passed"]), (1, "riven-table-001", False)
        )
        scenarios = json.loads((target / "scenarios.json").read_text(encoding="utf-8"))
        self.assertEqual([s["key"] for s in scenarios], ["1:full-view", "1:idle"])
        self.assertEqual(scenarios[0]["tickP99"], 50.0)
        (ci,) = json.loads((target / "ci-runs.json").read_text(encoding="utf-8"))
        self.assertEqual(
            (ci["verdict"], ci["passed"], ci["failedJobs"], ci["jobsSkipped"], ci["rivenTree"]),
            ("failed", False, "smoke", 1, "aaaaaaaa"),
        )
        self.assertTrue(ci["startedUtc"].endswith("Z"))
        jobs = {j["job"]: j for j in json.loads((target / "ci-jobs.json").read_text("utf-8"))}
        self.assertEqual((jobs["smoke"]["seconds"], jobs["smoke"]["failedStep"]), (4.5, "inspect"))
        self.assertEqual(
            (jobs["atlas-golden"]["seconds"], jobs["atlas-golden"]["reason"]), (None, "needs smoke")
        )


if __name__ == "__main__":
    unittest.main()
