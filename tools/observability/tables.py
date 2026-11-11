"""Flat row tables of every native run and local CI run, for the run ledger dashboard.

The telemetry ledger nests each run's numbers by scenario, and each local CI run keeps its own
summary.json. This module flattens both into four JSON arrays of plain rows, one file each:

    runs.json       one row per capture, smoke or pregeneration in the ledger
    scenarios.json  one row per scenario of those runs (tick, frame, cubes, saves, LOD coverage)
    ci-runs.json    one row per tools/actions.py run (targets, verdict, job counts, trees)
    ci-jobs.json    one row per job of those runs (status, step time, first failing step)

Unlike the website export these rows are private: they name instances, builds, commits and failure
messages. Every time is UTC; CI run ids are local time and are converted.
"""

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from observability import telemetry
from observability.profiles import ROOT

ACTIONS = ROOT / ".local/actions"
FAILURE_CHARACTERS = 220


def _n(value: Any, digits: int = 3) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return round(value, digits)


def _short(value: Any, length: int = 8) -> str | None:
    return value[:length] if isinstance(value, str) else None


def _run_row(number: int, record: dict) -> dict:
    setup = record.get("setup") or {}
    process = record.get("process") or {}
    server, client = process.get("server") or {}, process.get("client") or {}
    versions = record.get("versions") or {}
    commits = versions.get("toolsCommits") or {}
    pregeneration = record.get("pregeneration") or {}
    failure = record.get("failure")
    return {
        "run": number,
        "startedUtc": record["startedUtc"],
        "kind": record["kind"],
        "workload": record.get("workload"),
        "instance": record["instance"],
        "mode": record.get("mode"),
        "passed": bool(record.get("passed")),
        "failure": failure[:FAILURE_CHARACTERS] if isinstance(failure, str) else None,
        "deliveryMode": setup.get("deliveryMode"),
        "viewDistance": setup.get("viewDistance"),
        "dhWorkers": (setup.get("distantHorizons") or {}).get("workers"),
        "lodTrace": setup.get("lodTrace"),
        "rivenBuild": (versions.get("riven") or {}).get("build"),
        "cubatureBuild": (versions.get("cubature") or {}).get("build"),
        "rivenCommit": commits.get("riven"),
        "cubatureCommit": commits.get("cubature"),
        "maxHeapGiB": (record.get("machine") or {}).get("maxHeapGiB"),
        "serverReadySeconds": _n(server.get("launchToReadySeconds"), 1),
        "serverPeakGiB": _n(server.get("peakWorkingSetGiB"), 2),
        "clientPeakGiB": _n(client.get("peakWorkingSetGiB"), 2),
        "scenarioCount": len(record.get("scenarios") or []),
        "pregenSeconds": _n(pregeneration.get("seconds"), 1),
        "pregenColumnsPerSecond": _n(pregeneration.get("columnsPerSecond"), 2),
        "pregenPeakHeapGiB": _n(pregeneration.get("peakHeapGiB"), 2),
    }


def _scenario_row(number: int, record: dict, scenario: dict) -> dict:
    tick, frame = scenario.get("tick") or {}, scenario.get("frame") or {}
    cubes, save = scenario.get("cubes") or {}, scenario.get("save") or {}
    lod = scenario.get("lod") or {}
    coverage = lod.get("coverageAtEnd") or {}
    return {
        "key": f"{number}:{scenario['scenario']}",
        "run": number,
        "startedUtc": scenario.get("startedUtc") or record["startedUtc"],
        "kind": record["kind"],
        "workload": record.get("workload"),
        "scenario": scenario["scenario"],
        "passed": bool(scenario.get("passed")),
        "seconds": _n(scenario.get("seconds"), 2),
        "tickP50": _n(tick.get("p50")),
        "tickP95": _n(tick.get("p95")),
        "tickP99": _n(tick.get("p99")),
        "tickMax": _n(tick.get("max")),
        "tickOver50ms": tick.get("over50ms"),
        "frameP50": _n(frame.get("p50")),
        "frameP95": _n(frame.get("p95")),
        "frameP99": _n(frame.get("p99")),
        "frameOver16ms": frame.get("over16_7ms"),
        "cubesDelivered": cubes.get("delivered"),
        "payloadMiB": _n(cubes.get("payloadMiB"), 2),
        "unloads": cubes.get("unloads"),
        "saves": cubes.get("saves"),
        "blockingFlushMs": _n(save.get("blockingFlush")),
        "gcCollections": scenario.get("gcCollections"),
        "lodAnswersPerSecond": _n(lod.get("answersPerSecond"), 2),
        "lodServiceP50Ms": _n((lod.get("serviceMs") or {}).get("p50Ms"), 1),
        "lodOnTarget": _n((coverage.get("all") or {}).get("onTarget"), 4),
        "lodNearOnTarget": _n((coverage.get("near") or {}).get("onTarget"), 4),
    }


def telemetry_rows() -> tuple[list[dict], list[dict]]:
    runs: list[dict] = []
    scenarios: list[dict] = []
    for entry in telemetry.build_index()["runs"]:
        record = json.loads((telemetry.LEDGER / entry["path"]).read_text(encoding="utf-8"))
        runs.append(_run_row(entry["run"], record))
        scenarios += [_scenario_row(entry["run"], record, s) for s in record.get("scenarios") or []]
    return runs, scenarios


def _ci_started(run_id: str) -> str:
    local = datetime.strptime(run_id, "%Y%m%d-%H%M%S").astimezone()
    return local.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def ci_rows(actions: Path | None = None) -> tuple[list[dict], list[dict]]:
    runs: list[dict] = []
    jobs: list[dict] = []
    for path in sorted((actions or ACTIONS).glob("*/summary.json")):
        summary = json.loads(path.read_text(encoding="utf-8"))
        run_id = summary["runId"]
        started = _ci_started(run_id)
        results = summary.get("jobs") or {}
        statuses = [job.get("status") for job in results.values()]
        failed = [name for name, job in results.items() if job.get("status") == "failed"]
        trees = summary.get("trees") or {}
        runs.append(
            {
                "runId": run_id,
                "startedUtc": started,
                "targets": " ".join(summary.get("targets") or []),
                "passed": bool(results) and statuses.count("passed") == len(statuses),
                "verdict": "failed" if failed else ("passed" if results else "empty"),
                "seconds": _n(summary.get("seconds"), 1),
                "jobs": len(results),
                "jobsPassed": statuses.count("passed"),
                "jobsFailed": len(failed),
                "jobsSkipped": statuses.count("skipped"),
                "failedJobs": " ".join(failed) or None,
                "rivenTree": _short(trees.get("riven")),
                "cubatureTree": _short(trees.get("cubature")),
                "treesChanged": summary.get("treesChanged"),
            }
        )
        for name, job in results.items():
            steps = job.get("steps") or []
            failing = next((s["name"] for s in steps if s.get("exitCode") not in (0, None)), None)
            jobs.append(
                {
                    "key": f"{run_id}:{name}",
                    "runId": run_id,
                    "startedUtc": started,
                    "job": name,
                    "status": job.get("status"),
                    "seconds": _n(sum(s.get("seconds") or 0 for s in steps), 1) if steps else None,
                    "steps": len(steps),
                    "failedStep": failing,
                    "reason": job.get("reason"),
                }
            )
    return runs, jobs


def write(target: Path) -> dict[str, int]:
    """Write the four row tables into `target`; returns each file's row count."""
    runs, scenarios = telemetry_rows()
    ci_runs, ci_jobs = ci_rows()
    target.mkdir(parents=True, exist_ok=True)
    counts = {}
    for name, rows in [
        ("runs", runs),
        ("scenarios", scenarios),
        ("ci-runs", ci_runs),
        ("ci-jobs", ci_jobs),
    ]:
        (target / f"{name}.json").write_text(json.dumps(rows, separators=(",", ":")), "utf-8")
        counts[name] = len(rows)
    return counts
