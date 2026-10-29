"""Read-only reconciliation of retained native evidence and comparison controls."""

import copy
import csv
import hashlib
import math
import re
import sys
from pathlib import Path
from statistics import mean
from typing import Any

from .profiles import ROOT, read_json, sha

sys.path.insert(0, str(ROOT / "dev/CubicChunks/tools"))
from analyze_cubic_profile import timestamp_ns
from lab_repeated_statistics import paired_interval

MAX_ROWS = 500000


def contained(root, relative):
    root = Path(root).resolve()
    candidate = (root / relative).resolve()
    if not candidate.is_relative_to(root):
        raise ValueError("Evidence reference escapes its directory: " + str(relative))
    return candidate


def phase_path(value):
    value = Path(value).resolve()
    if value.is_file():
        if value.name != "result.json":
            raise ValueError("Select result.json, its phase directory, or the owned instance")
        return value.parent
    if (value / "result.json").exists() or value.name in ("write", "read"):
        return value
    return value / "runs/write"


def percentile(values, q):
    if not values:
        return None
    return sorted(values)[max(0, math.ceil(len(values) * q) - 1)]


def samples(phase, run_id, side, summary):
    path = contained(phase / "raw" / run_id / side, summary["chronologicalCsv"])
    rows: list[dict[str, int]] = []
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != ["ordinal", "observationEndOffsetNanos", "durationNanos"]:
            raise ValueError("Unknown sample CSV columns: " + str(path))
        for csv_row in reader:
            if len(rows) >= MAX_ROWS:
                raise ValueError("Sample CSV exceeds bounded row limit")
            values = {key: int(value) for key, value in csv_row.items()}
            if values["ordinal"] != len(rows) or values["durationNanos"] < 0:
                raise ValueError("Invalid sample order or duration")
            if rows and values["observationEndOffsetNanos"] < rows[-1]["observationEndOffsetNanos"]:
                raise ValueError("Sample observation times moved backwards")
            rows.append(values)
    if (
        len(rows) != summary["storedCount"]
        or summary["count"] != len(rows) + summary["droppedCount"]
    ):
        raise ValueError("Sample count does not reconcile")
    if summary["droppedCount"]:
        raise ValueError("Truncated sample window cannot establish complete quantiles")
    durations = [r["durationNanos"] for r in rows]
    if sum(durations) != summary["totalNanos"]:
        raise ValueError("Raw durations do not reconcile with the reported total")
    for field, probability in [("p50Ms", 0.5), ("p95Ms", 0.95), ("p99Ms", 0.99)]:
        observed = percentile(durations, probability)
        if observed is not None and not math.isclose(
            observed / 1e6, summary[field], abs_tol=0.00001
        ):
            raise ValueError("Raw percentile differs from reported " + field)
    if durations and not math.isclose(max(durations) / 1e6, summary["maxMs"], abs_tol=0.00001):
        raise ValueError("Raw maximum differs from reported maximum")
    anchor = timestamp_ns(summary["timelineOriginUtc"])
    for row in rows:
        row["endEpochNanos"] = anchor + row["observationEndOffsetNanos"]
        row["startEpochNanos"] = row["endEpochNanos"] - row["durationNanos"]
    return {
        "file": str(path),
        "sha256": sha(path),
        "rows": rows,
        "count": len(rows),
        "p95Ms": percentile(durations, 0.95) / 1e6 if durations else None,
        "p99Ms": percentile(durations, 0.99) / 1e6 if durations else None,
        "maxMs": max(durations) / 1e6 if durations else None,
        "above50ms": sum(n > 50000000 for n in durations),
        "excessAbove50ms": sum(max(0, n - 50000000) for n in durations) / 1e6,
    }


def verify_snapshots(phase, result) -> dict[str, Any]:
    snapshot = result.get("preLaunchInputSnapshots", {})
    if snapshot.get("schema") != 2:
        return {
            "status": "unavailable",
            "reason": "No complete schema-2 input snapshot; historical evidence remains exploratory.",
        }
    manifest = contained(phase, snapshot["manifest"])
    if sha(manifest) != snapshot["sha256"]:
        raise ValueError("Prepared manifest hash mismatch")
    expected = result["inputHashesBeforeLaunch"]["files"]
    found = {}
    for item in snapshot["files"]:
        path = contained(phase, item["file"])
        if path.stat().st_size != item["bytes"] or sha(path) != item["sha256"]:
            raise ValueError("Prepared file snapshot mismatch: " + str(path))
        key = Path(item["file"]).relative_to("prepared-files").as_posix()
        if key in found:
            raise ValueError("Duplicate prepared file snapshot")
        found[key] = item["sha256"]
    if found != expected:
        raise ValueError("Snapshot set differs from the launch input hashes")
    controllers = {}
    for item in snapshot.get("controllerSources", []):
        path = contained(phase, item["file"])
        key = Path(item["file"]).relative_to("controller-sources").as_posix()
        if (
            key in controllers
            or path.stat().st_size != item["bytes"]
            or sha(path) != item["sha256"]
        ):
            raise ValueError("Controller snapshot mismatch: " + str(path))
        controllers[key] = item["sha256"]
    if controllers != result["inputHashesBeforeLaunch"].get("controllerSources", {}):
        raise ValueError("Controller snapshot set differs from launch hashes")
    return {
        "status": "verified",
        "files": len(found),
        "manifestSha256": snapshot["sha256"],
        "controllerSources": "verified" if controllers else "unavailable",
    }


def worker_costs(capture):
    counters = capture.get("threadCosts", {})
    threads = counters.get("threads", [])
    if not counters.get("enabled") or not threads or counters.get("elapsedNanos", 0) <= 0:
        return {"available": False, "reason": "No complete DH worker counter window"}
    if any(not row.get("available") for row in threads):
        return {
            "available": False,
            "reason": "A selected worker/counter was unavailable",
            "threads": threads,
        }
    if any(row.get("cpuNanos", -1) < 0 or row.get("allocatedBytes", -1) < 0 for row in threads):
        raise ValueError("Negative DH worker counter delta")
    elapsed = counters["elapsedNanos"]
    return {
        "available": True,
        "threads": threads,
        "elapsedNanos": elapsed,
        "cpuPercentOfOneCore": 100 * sum(r["cpuNanos"] for r in threads) / elapsed,
        "allocationMBps": sum(r["allocatedBytes"] for r in threads) * 1000 / elapsed,
        "scope": counters.get("scope"),
    }


def logs(phase):
    rows = []
    for side in ["server", "client"]:
        path = phase / (side + ".log")
        if not path.is_file():
            continue
        with path.open(encoding="utf-8", errors="replace") as stream:
            for number, line in enumerate(stream, 1):
                if re.search(
                    r"\b(ERROR|FATAL)\b|Exception|Mixin.*(fail|missing)|Data loss|Could not instrument|Skipping instrumentation",
                    line,
                    re.I,
                ):
                    rows.append({"side": side, "line": number, "text": line.rstrip()[:1400]})
                    if len(rows) >= 120:
                        return {
                            "entries": rows,
                            "bounded": True,
                            "scope": "Log leads, not a causal classification; inspect the complete logs.",
                        }
    return {
        "entries": rows,
        "bounded": False,
        "scope": "Log leads, not a causal classification; inspect the complete logs.",
    }


def inspect_run(value):
    phase = phase_path(value)
    result_path = phase / "result.json"
    result: dict[str, Any] = (
        read_json(result_path)
        if result_path.is_file()
        else {"passed": False, "failure": "No completed result.json"}
    )
    manifest_path = phase / "prepared-inputs.json"
    manifest = read_json(manifest_path) if manifest_path.is_file() else {}
    errors, warnings, scenarios = [], [], []
    if not result.get("passed"):
        errors.append(result.get("failure") or "Native protocol did not pass")
    if not result.get("hostWorkloadGuard", {}).get("passed"):
        errors.append("Java workload guard is missing or did not pass")
    try:
        snapshots = verify_snapshots(phase, result)
    except (ValueError, OSError, KeyError) as error:
        snapshots = {"status": "invalid", "reason": str(error)}
        errors.append(str(error))
    if snapshots["status"] == "unavailable":
        warnings.append(snapshots["reason"])
    elif snapshots.get("controllerSources") == "unavailable":
        warnings.append(
            "Capture controller source snapshot unavailable; cannot establish current comparison controls."
        )
    props = manifest.get("labProperties", {})
    diagnostic = result.get("jfr", False) or any(
        props.get(key) == "true"
        for key in [
            "cubiclab.diagnostics",
            "cubiclab.storageDiagnostics",
            "cubiclab.renderDiagnostics",
        ]
    )
    captured = result.get("scenarios", []) + result.get("incompleteScenarios", [])
    if result.get("passed") and not captured:
        errors.append("Native result contains no scenarios")
    if len({s["runId"] for s in captured}) != len(captured):
        errors.append("Duplicate native run identities")
    if result.get("passed") and [s["scenario"] for s in captured] != result.get("scenarioSequence"):
        errors.append("Captured scenario sequence differs from the requested sequence")
    for scenario in captured:
        server, client = scenario.get("server") or {}, scenario.get("client") or {}
        name, run_id = scenario["scenario"], scenario["runId"]
        row = {"scenario": name, "runId": run_id, "errors": [], "warnings": []}
        if scenario.get("validationStatus"):
            row["errors"].append("Incomplete/failed capture; retained for diagnosis only")
        if not server.get("passed") or server.get("worldSeed") != manifest.get("seed"):
            row["errors"].append("Server scenario/seed validation failed")
        if any(client.get(key, 0) for key in ["framesWithGui", "framesWithOverlay"]) or client.get(
            "overlayProbeFailure"
        ):
            row["errors"].append("Client capture was obscured or its overlay probe failed")
        distances = [
            server.get("horizontalViewDistance"),
            server.get("verticalViewDistance"),
            client.get("renderDistance"),
            client.get("verticalViewDistance"),
        ]
        if distances != [12, 12, 12, 12]:
            row["errors"].append("Effective native view distances differ from 12")
        for side, capture, metric in [
            ("server", server, "completeServerTicks"),
            ("client", client, "frameIntervals"),
        ]:
            runtime = capture.get("runtime", {})
            if (
                not runtime.get("java", "").startswith("26.")
                or runtime.get("maxHeapBytes") != 8 * 1024**3
                or not runtime.get("garbageCollectors")
                or not all(n.startswith("ZGC") for n in runtime["garbageCollectors"])
            ):
                row["errors"].append(side + " runtime is outside Java 26 / 8 GiB / ZGC")
            try:
                row[side] = samples(phase, run_id, side, capture[metric])
                if not row[side]["count"]:
                    row["errors"].append(side + " capture has no observations")
            except (KeyError, ValueError, OSError) as error:
                row[side] = {"unavailable": str(error), "rows": []}
                row["errors"].append(side + ": " + str(error))
            try:
                row[side]["workers"] = worker_costs(capture)
            except (ValueError, KeyError) as error:
                row[side]["workers"] = {"available": False, "reason": str(error)}
                row["errors"].append(side + ": " + str(error))
            if not row[side]["workers"]["available"]:
                row["warnings"].append(side + ": DH worker counters unavailable")
        row["cubeResidencySeconds"] = None
        if name == "full-view":
            view = client.get("fullView", {})
            delivery = server.get("preparationView", {})
            if (
                view.get("resident") != 15625
                or delivery.get("serverReady") != 15625
                or delivery.get("serverSent") != 15625
            ):
                row["errors"].append("Full native target was not ready, sent and resident")
            geometry = view.get("geometry", {})
            expected = manifest.get("benchmarkWorkload", {}).get("value", {}).get("camera", [])
            actual = [geometry.get(k) for k in ["x", "y", "z", "yaw", "pitch"]]
            if len(expected) != 5 or any(
                a is None or abs(a - b) > 0.001 for a, b in zip(actual, expected)
            ):
                row["errors"].append("Observed full-view camera differs from the frozen workload")
            delivered = delivery.get("deliveredNanos")
            if isinstance(delivered, (int, float)) and delivered > 0:
                row["cubeResidencySeconds"] = delivered / 1e9
            else:
                row["errors"].append("Cube residency completion time unavailable")
            row["nativeView"] = view
        row["renderer"] = client.get("rendererDiagnostics", {"enabled": False})
        row["packetDiagnostics"] = {
            side: capture.get("packets", {})
            for side, capture in [("server", server), ("client", client)]
        }
        row["sufficientTickWindowForTailReview"] = row["server"].get("count", 0) >= 3000
        screenshot = (
            Path(manifest.get("client", str(phase)))
            / "screenshots"
            / ("cubiclab-" + run_id + ".png")
        )
        row["screenshot"] = {
            "file": str(screenshot),
            "sha256": sha(screenshot) if screenshot.is_file() else None,
            "review": "Not inferred from protocol success; a retained manual review is required.",
        }
        if server.get("preparationStart", {}).get("error"):
            row["warnings"].append(
                "Legacy Riven preparation observer is unavailable; no attribution uses it."
            )
        scenarios.append(row)
        errors.extend(name + ": " + s for s in row["errors"])
    return {
        "schema": 1,
        "phase": str(phase),
        "resultSha256": sha(result_path) if result_path.exists() else None,
        "mode": "diagnostic" if diagnostic else "timing",
        "protocolPassed": result.get("passed", False),
        "errors": errors,
        "warnings": warnings,
        "snapshots": snapshots,
        "scenarios": scenarios,
        "logs": logs(phase),
        "manifest": manifest,
        "result": result,
        "scope": "Native tick/frame intervals and cube residency; physical presentation and complete DH coverage are not measured.",
    }


def public_summary(report):
    value = copy.deepcopy(report)
    value.pop("manifest")
    value.pop("result")
    for scenario in value["scenarios"]:
        for side in ["server", "client"]:
            rows = scenario[side].pop("rows")
            scenario[side]["slowest"] = sorted(
                rows, key=lambda r: r["durationNanos"], reverse=True
            )[:12]
    return value


def controls(report, treatment, diagnostic_screen=False):
    if report["errors"] or report["snapshots"]["status"] != "verified":
        raise ValueError(
            "Comparison requires valid runs and complete verified input snapshots: "
            + report["phase"]
        )
    if report["snapshots"].get("controllerSources") != "verified":
        raise ValueError(
            "Comparison requires frozen capture controller sources: " + report["phase"]
        )
    if report["mode"] != "timing" and not diagnostic_screen:
        raise ValueError("Diagnostic captures cannot enter an optimization comparison")
    result, manifest = report["result"], report["manifest"]
    values = copy.deepcopy(result["inputHashesBeforeLaunch"])
    if treatment in ("cubature", "riven"):
        field = "sourceCubicArtifact" if treatment == "cubature" else "sourceArtifact"
        selected = manifest[field]["sha256"]
        for side, mods in values["mods"].items():
            matches = [name for name, digest in mods.items() if digest == selected]
            if len(matches) != 1:
                raise ValueError("Cannot identify one exact treatment artifact on " + side)
            del mods[matches[0]]
    if treatment == "packet-cubes":
        declarations = manifest.get("cubeExperiments", {})
        selected = int(declarations.get("maxCubePacketCubes", 0))
        if not 1 <= selected <= 1024:
            raise ValueError("Missing or invalid packet cube-count declaration")
        # Delivery mode is not a packet setting; reports before it existed declare the legacy flag.
        expected = {
            key: int(val)
            for key, val in declarations.items()
            if key not in ("deliveryMode", "incrementalMaintenance")
        }
        for row in result["scenarios"]:
            if row["server"].get("packetPolicy") != expected:
                raise ValueError("Effective packet policy differs from declaration")
        pattern = rb"(?m)^([ \t]*I:maxCubePacketCubes=)([0-9]+)([ \t]*\r?)$"
        for side in ["server", "client"]:
            key = side + "/config/cubicchunks.cfg"
            data = (Path(report["phase"]) / "prepared-files" / key).read_bytes()
            if hashlib.sha256(data).hexdigest() != values["files"].get(key):
                raise ValueError("Packet policy snapshot hash mismatch")
            matches = list(re.finditer(pattern, data))
            if len(matches) != 1 or int(matches[0].group(2)) != selected:
                raise ValueError("Packet cube setting missing, duplicated or inconsistent")
            normalized = re.sub(pattern, rb"\g<1><packet-cubes>\g<3>", data)
            values["files"][key] = "packet-cubes-v1:" + hashlib.sha256(normalized).hexdigest()
        values["cubeExperiments"] = {
            key: val for key, val in declarations.items() if key != "maxCubePacketCubes"
        }
    # Exact byte hashes stay strict: no blanket config normalization or exclusion.
    values.update(
        copy.deepcopy(
            {
                key: manifest.get(key)
                for key in [
                    "runtimeVersion",
                    "jvmProfile",
                    "viewDistance",
                    "labProperties",
                    "generator",
                    "distantHorizons",
                ]
            }
        )
    )
    # Candidate designation explains provenance, not runtime behavior.
    values["distantHorizons"].pop("qualification", None)
    # A Riven treatment carries its own definition fingerprints; they are not a separate control.
    if treatment == "riven":
        values.pop("generator", None)
    values["javaRuntime"] = {
        k: v for k, v in manifest.get("javaRuntime", {}).items() if k != "criticalArtifacts"
    }
    values["javaArtifactHashes"] = sorted(
        a["sha256"] for a in manifest.get("javaRuntime", {}).get("criticalArtifacts", [])
    )
    values["serverRuntimeHashes"] = manifest.get("serverRuntimeHashes")
    values["observedClientSettings"] = [
        {
            key: row["client"].get(key)
            for key in [
                "frameCap",
                "vsync",
                "fancyGraphics",
                "fullscreen",
                "width",
                "height",
                "glVendor",
                "glRenderer",
                "glVersion",
            ]
        }
        for row in result["scenarios"]
    ]
    values["scenarioSequence"] = [s["scenario"] for s in report["scenarios"]]
    values["captureMode"] = report["mode"]
    return values


def compare_runs(baseline, candidate, treatment, diagnostic_screen=False):
    if len(baseline) != len(candidate) or not baseline:
        raise ValueError(
            "Supply equal nonempty baseline/candidate run lists in predeclared pair order"
        )
    reports = baseline + candidate
    paths = [r["phase"] for r in reports]
    if len(set(paths)) != len(paths):
        raise ValueError("Repeated evidence cannot count as independent runs")
    treatments = {}
    if treatment == "packet-cubes":
        for label, group in [("baseline", baseline), ("candidate", candidate)]:
            identities = {r["manifest"]["cubeExperiments"]["maxCubePacketCubes"] for r in group}
            if len(identities) != 1:
                raise ValueError("Packet cube count changed within the " + label + " group")
            treatments[label] = next(iter(identities))
        if treatments["baseline"] == treatments["candidate"]:
            raise ValueError("Declared packet cube counts are identical")
    elif treatment != "none":
        field = "sourceCubicArtifact" if treatment == "cubature" else "sourceArtifact"
        for label, group in [("baseline", baseline), ("candidate", candidate)]:
            identities = {r["manifest"][field]["sha256"] for r in group}
            if len(identities) != 1:
                raise ValueError("Treatment artifact changed within the " + label + " group")
            treatments[label] = next(iter(identities))
        if treatments["baseline"] == treatments["candidate"]:
            raise ValueError(
                "Declared treatment has identical artifact bytes; select treatment none"
            )
    reference = controls(baseline[0], treatment, diagnostic_screen)
    for report in reports[1:]:
        if controls(report, treatment, diagnostic_screen) != reference:
            raise ValueError("Non-treatment inputs differ: " + report["phase"])
    metrics = []
    for index, scenario in enumerate(baseline[0]["scenarios"]):
        getters = {
            "serverTickP95Ms": lambda s: s["server"]["p95Ms"],
            "serverTickP99Ms": lambda s: s["server"]["p99Ms"],
            "frameIntervalP99Ms": lambda s: s["client"]["p99Ms"],
            "cubeResidencySeconds": lambda s: s["cubeResidencySeconds"],
            "serverQueueCpuPercentOfOneCore": lambda s: s["server"]["workers"].get(
                "cpuPercentOfOneCore"
            ),
            "clientQueueCpuPercentOfOneCore": lambda s: s["client"]["workers"].get(
                "cpuPercentOfOneCore"
            ),
        }
        for metric, getter in getters.items():
            pairs = [
                (getter(a["scenarios"][index]), getter(b["scenarios"][index]))
                for a, b in zip(baseline, candidate)
            ]
            if any(a is None or b is None for a, b in pairs):
                metrics.append(
                    {"scenario": scenario["scenario"], "metric": metric, "available": False}
                )
                continue
            row = {
                "scenario": scenario["scenario"],
                "metric": metric,
                "available": True,
                "pairs": pairs,
                "baselineMean": mean(a for a, b in pairs),
                "candidateMean": mean(b for a, b in pairs),
            }
            if all(a > 0 and b > 0 for a, b in pairs):
                row["pairedStatistics"] = paired_interval(pairs)
            metrics.append(row)
    gates = []
    if diagnostic_screen:
        gates.append(
            "Matched diagnostic screen includes observer overhead; no production performance acceptance"
        )
    if len(baseline) < 5:
        gates.append("Fewer than five independent matched run pairs")
    if any(not s["sufficientTickWindowForTailReview"] for r in reports for s in r["scenarios"]):
        gates.append("A scenario has fewer than 3000 complete server ticks")
    gates.append(
        "Manual visual/content/durability and complete output acceptance remain separate; this program never promotes artifacts"
    )
    return {
        "schema": 1,
        "treatment": treatment,
        "pairs": len(baseline),
        "controlsMatched": True,
        "diagnosticScreen": diagnostic_screen,
        "treatmentHashes": treatments if treatment != "packet-cubes" else {},
        "treatmentValues": treatments if treatment == "packet-cubes" else {},
        "runs": paths,
        "metrics": metrics,
        "reviewLimits": gates,
        "accepted": False,
        "scope": "Paired run-level changes, not pooled independent ticks or automatic performance acceptance.",
    }
