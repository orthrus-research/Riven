"""Evidence failure controls; these fixtures do not qualify native performance."""

import copy
import csv
import json
import tempfile
import unittest
from pathlib import Path
from typing import Any

import measure_pipeline
from observability.evidence import (
    compare_runs,
    controls,
    inspect_run,
    samples,
    verify_snapshots,
    worker_costs,
)
from observability.profiles import ROOT, class_methods, sha
from observability.trace import aggregate, union_overlap


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.base = ROOT / ".local/observability-tests"
        self.base.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="case-", dir=self.base)
        self.home = Path(self.temporary.name)
        self.assertTrue(self.home.resolve().is_relative_to(self.base.resolve()))

    def tearDown(self):
        self.temporary.cleanup()

    def sample_fixture(self):
        folder = self.home / "raw/run/server"
        folder.mkdir(parents=True)
        path = folder / "ticks.csv"
        with path.open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(["ordinal", "observationEndOffsetNanos", "durationNanos"])
            for i, cost in enumerate([1000000, 2000000, 9000000]):
                writer.writerow([i, 10000000 + i * 10000000, cost])
        summary = {
            "count": 3,
            "storedCount": 3,
            "droppedCount": 0,
            "totalNanos": 12000000,
            "p50Ms": 2,
            "p95Ms": 9,
            "p99Ms": 9,
            "maxMs": 9,
            "timelineOriginUtc": "2026-10-20T01:00:00.123456789Z",
            "chronologicalCsv": "ticks.csv",
        }
        return summary

    def test_raw_quantiles_and_nanosecond_anchor(self):
        result = samples(self.home, "run", "server", self.sample_fixture())
        self.assertEqual(result["p99Ms"], 9)
        self.assertEqual(result["rows"][0]["endEpochNanos"] % 1000000000, 133456789)
        self.assertEqual(
            result["rows"][0]["endEpochNanos"] - result["rows"][0]["startEpochNanos"], 1000000
        )

    def test_wrong_percentile_total_and_truncation_are_rejected(self):
        summary = self.sample_fixture()
        for update in [{"p99Ms": 2}, {"totalNanos": 1}, {"count": 4, "droppedCount": 1}]:
            with self.assertRaises(ValueError):
                samples(self.home, "run", "server", dict(summary, **update))

    def test_csv_cannot_escape_evidence_directory(self):
        summary = self.sample_fixture()
        summary["chronologicalCsv"] = "../../../../outside.csv"
        with self.assertRaisesRegex(ValueError, "escapes"):
            samples(self.home, "run", "server", summary)

    def test_snapshot_tamper_and_missing_inputs_are_detected(self):
        manifest = {side: str(self.home / side) for side in ["server", "client"]}
        for side in ["server", "client"]:
            (self.home / side / "config/nested").mkdir(parents=True)
            (self.home / side / "config/nested/settings.cfg").write_text("fixed\n")
        for path in ["server/server.properties", "server/user_jvm_args.txt", "client/options.txt"]:
            (self.home / path).write_text("fixed\n")
        evidence = self.home / "evidence"
        evidence.mkdir()
        hashes = {
            "files": {k: sha(p) for k, p in measure_pipeline.input_files(manifest).items()},
            "controllerSources": {
                "tools/measure_pipeline.py": sha(ROOT / "tools/measure_pipeline.py")
            },
        }
        retained = measure_pipeline.retain_inputs(manifest, hashes, evidence)
        result = {"inputHashesBeforeLaunch": hashes, "preLaunchInputSnapshots": retained}
        checked = verify_snapshots(evidence, result)
        self.assertEqual(checked["status"], "verified")
        self.assertEqual(checked["controllerSources"], "verified")
        controller = evidence / retained["controllerSources"][0]["file"]
        original = controller.read_bytes()
        controller.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "Controller snapshot mismatch"):
            verify_snapshots(evidence, result)
        controller.write_bytes(original)
        target = evidence / retained["files"][0]["file"]
        target.write_text("different\n")
        with self.assertRaisesRegex(ValueError, "snapshot mismatch"):
            verify_snapshots(evidence, result)
        (self.home / "client/options.txt").unlink()
        with self.assertRaisesRegex(RuntimeError, "missing"):
            measure_pipeline.input_files(manifest)

    def test_historical_snapshot_does_not_gain_new_guarantees(self):
        self.assertEqual(verify_snapshots(self.home, {})["status"], "unavailable")

    def test_unavailable_worker_is_not_zero_cost(self):
        result = worker_costs(
            {
                "threadCosts": {
                    "enabled": True,
                    "elapsedNanos": 100,
                    "threads": [{"available": False}],
                }
            }
        )
        self.assertFalse(result["available"])
        self.assertNotIn("cpuPercentOfOneCore", result)
        result = worker_costs(
            {
                "threadCosts": {
                    "enabled": True,
                    "elapsedNanos": 2000000000,
                    "threads": [
                        {"available": True, "cpuNanos": 1000000000, "allocatedBytes": 4000000}
                    ],
                }
            }
        )
        self.assertEqual(result["cpuPercentOfOneCore"], 50)
        self.assertEqual(result["allocationMBps"], 2)

    def test_missing_result_remains_failed_and_readable(self):
        phase = self.home / "runs/write"
        phase.mkdir(parents=True)
        (phase / "server.log").write_text("ERROR startup failed\n")
        result = inspect_run(phase)
        self.assertFalse(result["protocolPassed"])
        self.assertTrue(result["errors"])
        self.assertEqual(len(result["logs"]["entries"]), 1)

    def test_failed_native_report_is_retained_but_not_passed(self):
        import cubic_lab

        server, client, evidence = [self.home / p for p in ["server", "client", "evidence"]]
        run_id = "00000000-0000-0000-0000-000000000001"
        folder = server / "cubiclab-results" / run_id
        folder.mkdir(parents=True)
        (folder / "server.json").write_text(json.dumps({"passed": False, "failure": "timeout"}))
        (folder / "server.csv").write_text("x\n1\n")
        result: dict[str, Any] = {
            "scenarios": [],
            "scenarioStartRequests": [{"scenario": "full-view", "runId": run_id}],
        }
        cubic_lab.retain_incomplete_scenarios(result, server, client, evidence)
        self.assertEqual(result["scenarios"], [])
        self.assertFalse(result["incompleteScenarios"][0]["server"]["passed"])
        self.assertTrue((evidence / "raw" / run_id / "server/server.csv").is_file())

    def test_class_reader_rejects_untrusted_truncation(self):
        for data in [b"", b"not a class", b"\xca\xfe\xba\xbe" + b"\0" * 6]:
            with self.assertRaises(ValueError):
                class_methods(data)


def report_fixture(path, cc):
    scenario = {
        "scenario": "full-view",
        "cubeResidencySeconds": 20,
        "sufficientTickWindowForTailReview": False,
        "server": {"p95Ms": 60, "p99Ms": 70, "workers": {"cpuPercentOfOneCore": 80}},
        "client": {"p99Ms": 12, "workers": {"cpuPercentOfOneCore": 80}},
    }
    return {
        "phase": path,
        "errors": [],
        "snapshots": {"status": "verified", "controllerSources": "verified"},
        "mode": "timing",
        "scenarios": [scenario],
        "result": {
            "inputHashesBeforeLaunch": {
                "mods": {
                    side: {"cc.jar": cc, "riven.jar": "riven", "observer.jar": "observer"}
                    for side in ["server", "client"]
                },
                "files": {"client/config.cfg": "config"},
            },
            "scenarios": [{"client": {"frameCap": 120}}],
        },
        "manifest": {
            "sourceCubicArtifact": {"sha256": cc},
            "distantHorizons": {"version": "3.3.3", "qualification": "candidate"},
            "javaRuntime": {"version": "26", "criticalArtifacts": [{"sha256": "jvm"}]},
        },
    }


class ComparisonTests(unittest.TestCase):
    def test_diagnostic_screen_is_explicit_and_cannot_mix_measurement_modes(self):
        before, after = report_fixture("a", "a"), report_fixture("b", "b")
        before["mode"] = after["mode"] = "diagnostic"
        with self.assertRaisesRegex(ValueError, "Diagnostic"):
            compare_runs([before], [after], "cubature")
        result = compare_runs([before], [after], "cubature", diagnostic_screen=True)
        self.assertTrue(result["diagnosticScreen"])
        self.assertFalse(result["accepted"])
        self.assertTrue(any("observer overhead" in limit for limit in result["reviewLimits"]))
        after["mode"] = "timing"
        with self.assertRaisesRegex(ValueError, "Non-treatment"):
            compare_runs([before], [after], "cubature", diagnostic_screen=True)

    def test_packet_count_axis_preserves_byte_cap_and_detects_snapshot_and_runtime_drift(self):
        base = ROOT / ".local/observability-tests"
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="packet-axis-", dir=base) as temporary:

            def fixture(name, count, byte_cap=131072):
                home = Path(temporary) / name
                report = report_fixture(str(home), "same-cubature")
                policy = {
                    "maxCubePacketCubes": count,
                    "maxCubePacketBytes": byte_cap,
                    "maxCubeSendTimeMillis": 0,
                    "maxCubeSendBytesPerTick": 0,
                }
                report["manifest"]["cubeExperiments"] = {
                    key: str(val) for key, val in policy.items()
                }
                report["manifest"]["cubeExperiments"]["incrementalMaintenance"] = "false"
                report["result"]["scenarios"][0]["server"] = {"packetPolicy": policy}
                for side in ["server", "client"]:
                    key = side + "/config/cubicchunks.cfg"
                    path = home / "prepared-files" / key
                    path.parent.mkdir(parents=True)
                    path.write_bytes(
                        (
                            "general {\n I:maxCubePacketCubes="
                            + str(count)
                            + "\n I:maxCubePacketBytes="
                            + str(byte_cap)
                            + "\n}\n"
                        ).encode()
                    )
                    report["result"]["inputHashesBeforeLaunch"]["files"][key] = sha(path)
                return report

            before, after = fixture("base", 1024), fixture("small", 16)
            self.assertEqual(controls(before, "packet-cubes"), controls(after, "packet-cubes"))
            comparison = compare_runs([before], [after], "packet-cubes")
            self.assertTrue(comparison["controlsMatched"])
            self.assertEqual(comparison["treatmentValues"], {"baseline": "1024", "candidate": "16"})
            self.assertEqual(comparison["treatmentHashes"], {})
            changed_bytes = fixture("changed-bytes", 16, 65536)
            with self.assertRaisesRegex(ValueError, "Non-treatment"):
                compare_runs([before], [changed_bytes], "packet-cubes")
            after["result"]["scenarios"][0]["server"]["packetPolicy"]["maxCubePacketCubes"] = 32
            with self.assertRaisesRegex(ValueError, "Effective"):
                controls(after, "packet-cubes")
            after["result"]["scenarios"][0]["server"]["packetPolicy"]["maxCubePacketCubes"] = 16
            snapshot = Path(after["phase"]) / "prepared-files/client/config/cubicchunks.cfg"
            snapshot.write_bytes(snapshot.read_bytes() + b" I:maxCubePacketCubes=16\n")
            with self.assertRaisesRegex(ValueError, "snapshot hash"):
                controls(after, "packet-cubes")
            after["result"]["inputHashesBeforeLaunch"]["files"]["client/config/cubicchunks.cfg"] = (
                sha(snapshot)
            )
            with self.assertRaisesRegex(ValueError, "duplicated"):
                controls(after, "packet-cubes")

    def test_only_declared_treatment_differs_and_control_read_does_not_mutate(self):
        before, after = report_fixture("a", "a"), report_fixture("b", "b")
        original = copy.deepcopy(before)
        self.assertEqual(controls(before, "cubature"), controls(after, "cubature"))
        self.assertEqual(before, original)
        result = compare_runs([before], [after], "cubature")
        self.assertFalse(result["accepted"])
        self.assertTrue(any("five" in s for s in result["reviewLimits"]))

    def test_changed_observer_config_or_profile_cannot_pass(self):
        for kind in ["observer", "config", "mode"]:
            before, after = report_fixture("a", "a"), report_fixture("b", "b")
            if kind == "observer":
                after["result"]["inputHashesBeforeLaunch"]["mods"]["client"]["observer.jar"] = (
                    "other"
                )
            elif kind == "config":
                after["result"]["inputHashesBeforeLaunch"]["files"]["client/config.cfg"] = "other"
            else:
                after["mode"] = "diagnostic"
            with self.assertRaises(ValueError):
                compare_runs([before], [after], "cubature")

    def test_duplicate_and_invalid_runs_cannot_be_repetitions(self):
        before, after = report_fixture("a", "a"), report_fixture("b", "b")
        with self.assertRaisesRegex(ValueError, "Repeated"):
            compare_runs([before, before], [after, after], "cubature")
        after["errors"].append("Foreign Java workload")
        with self.assertRaises(ValueError):
            compare_runs([before], [after], "cubature")

    def test_changed_or_unrecorded_controllers_and_mixed_treatments_are_rejected(self):
        before, after = report_fixture("a", "a"), report_fixture("b", "b")
        before["result"]["inputHashesBeforeLaunch"]["controllerSources"] = {"runner.py": "first"}
        after["result"]["inputHashesBeforeLaunch"]["controllerSources"] = {"runner.py": "second"}
        with self.assertRaisesRegex(ValueError, "Non-treatment"):
            compare_runs([before], [after], "cubature")
        before["snapshots"]["controllerSources"] = "unavailable"
        with self.assertRaisesRegex(ValueError, "controller"):
            controls(before, "cubature")
        with self.assertRaisesRegex(ValueError, "within"):
            compare_runs(
                [report_fixture("a", "a"), report_fixture("a2", "a2")],
                [report_fixture("b", "b"), report_fixture("b2", "b")],
                "cubature",
            )
        with self.assertRaisesRegex(ValueError, "identical"):
            compare_runs([report_fixture("a", "a")], [report_fixture("b", "a")], "cubature")


class TraceTests(unittest.TestCase):
    def test_nested_intervals_are_union_counted(self):
        self.assertEqual(union_overlap([(0, 10), (2, 8), (9, 20)], 5, 15), 10)
        self.assertEqual(union_overlap([(0, 5), (15, 20)], 5, 15), 0)

    def test_boundaries_and_pause_overlap_do_not_claim_causality(self):
        slow = [{"durationNanos": 10000000}]
        events = [
            {
                "type": "jdk.GCPhasePause",
                "values": {"startTime": "2026-10-20T01:00:00.003Z", "duration": "PT0.004S"},
            },
            {
                "type": "jdk.GCPhasePause",
                "values": {"startTime": "2026-10-20T01:00:00.006Z", "duration": "PT0.004S"},
            },
            {"type": "jdk.ExecutionSample", "values": {"startTime": "2026-10-20T01:00:00.020Z"}},
        ]
        # Use the same exact parser as native CSV/JFR boundaries.
        from observability.evidence import timestamp_ns

        base = timestamp_ns("2026-10-20T01:00:00Z")
        slow[0].update(startEpochNanos=base + 5000000, endEpochNanos=base + 15000000)
        result, _ = aggregate(events, [("window", base + 5000000, base + 20000000, slow)], "server")
        self.assertEqual(result["window"]["gcPauseUnionMs"], 5)
        self.assertEqual(result["window"]["outliers"][0]["gcPauseOverlapMs"], 5)
        self.assertNotIn("jdk.ExecutionSample", result["window"]["events"])

    def test_event_expansion_is_bounded(self):
        event = {"type": "jdk.ExecutionSample", "values": {"startTime": "2026-10-20T01:00:00Z"}}
        with self.assertRaisesRegex(ValueError, "limit exceeded"):
            aggregate([event, event], [("all", 0, 2**63 - 1, [])], "server", limit=1)

    def test_client_thread_is_identified_by_methods_after_jfr_name_changes(self):
        from analyze_cubic_profile import compact_event
        from observability.evidence import timestamp_ns

        start = timestamp_ns("2026-10-20T01:00:00Z")
        events = [
            compact_event(
                {
                    "type": "jdk.MethodTrace",
                    "values": {
                        "startTime": "2026-10-20T01:00:00.001Z",
                        "duration": "PT0.005S",
                        "eventThread": {"javaName": name, "javaThreadId": 1, "osThreadId": 42},
                        "method": {"name": "render", "type": {"name": "Renderer"}},
                    },
                }
            )
            for name in ["main", "Client thread"]
        ]
        slow = [
            {"startEpochNanos": start, "endEpochNanos": start + 10000000, "durationNanos": 10000000}
        ]
        result, timeline = aggregate(
            events,
            [("view", start, start + 10000000, slow)],
            "client",
            targets={"Renderer.render": "dh.render"},
        )
        self.assertEqual(result["view"]["primaryThreadIdentities"], ["java:1"])
        self.assertEqual(
            result["view"]["outliers"][0]["sameThreadStageOverlapMs"], {"dh.render": 5}
        )
        self.assertTrue(all(isinstance(e["tid"], int) for e in timeline))


if __name__ == "__main__":
    unittest.main()
