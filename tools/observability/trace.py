"""Windowed JFR diagnosis with explicit inclusive-time and correlation limits."""

import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

from .evidence import timestamp_ns
from .profiles import ROOT, configuration, read_json, sha

sys.path.insert(0, str(ROOT / "dev/CubicChunks/tools"))
from analyze_cubic_profile import duration_ns, iter_jfr_events, methods

EVENTS = ",".join(
    "jdk." + name
    for name in [
        "ExecutionSample",
        "NativeMethodSample",
        "MethodTrace",
        "ObjectAllocationSample",
        "GCPhasePause",
        "GarbageCollection",
        "ThreadPark",
        "ThreadSleep",
        "JavaMonitorEnter",
        "JavaMonitorWait",
        "FileRead",
        "FileWrite",
        "FileForce",
        "ZAllocationStall",
        "DataLoss",
        "SafepointBegin",
        "ExecuteVMOperation",
    ]
)
LIMIT = 500000
SEMANTICS = {
    "methodSpans": "Recorded method wall time above the configured threshold; includes waits and nested calls. Never sum stages as exclusive cost.",
    "stacks": "Execution and native sample counts stay separate. Neither is elapsed CPU time.",
    "allocation": "JFR sampled allocation weights estimate pressure, not retained heap or exact per-object allocation.",
    "outliers": "Overlapping method spans, pauses and samples are diagnostic leads, not proof of cause. Method overlaps are unioned within each stage, not across stages.",
    "clocks": "Each process uses its own native UTC/monotonic anchor. Cross-process calendar alignment is approximate and does not measure transport latency.",
    "requestIdentity": "Method traces contain no coordinates or unique cross-JVM request IDs. Packet fingerprints remain separate hints.",
    "endpoint": "Native residency, native renderer readiness and DH rendering calls are distinct from complete distant coverage and physical presentation.",
    "coverage": "The first/last retained event envelope is checked against each window. It can detect a trimmed prefix/suffix; it does not prove uninterrupted interior recording or absence of missed/sub-threshold events.",
}


def union_overlap(intervals, start, end):
    clipped = sorted((max(a, start), min(b, end)) for a, b in intervals if a < end and b > start)
    total, previous = 0, start
    for a, b in clipped:
        total += max(0, b - max(a, previous))
        previous = max(previous, b)
    return total


def stage_name(stack):
    for name in stack:
        # cc.orthrus.riven since 2026-10-28; older captures name supersymmetry.riven.
        if name.startswith(("cc.orthrus.riven.", "supersymmetry.riven.")):
            return "Riven"
        if name.startswith("com.seibel.distanthorizons."):
            return "Distant Horizons"
        if name.startswith("io.github.opencubicchunks.cubicchunks."):
            return "Cubature"
        if name.startswith("cubicchunks.regionlib."):
            return "RegionLib"
    return "runtime/other"


def aggregate(events, windows, side, targets=None, limit=LIMIT):
    """Pure event consumer, shared by the native exporter and corruption/boundary tests."""
    targets = targets or {
        t["class"] + "." + t["method"]: t["stage"] for t in configuration()["targets"]
    }
    results = {}
    for name, start, end, slow in windows:
        results[name] = {
            "start": start,
            "end": end,
            "slow": slow,
            "events": Counter(),
            "samples": Counter(),
            "nativeSamples": Counter(),
            "stacks": Counter(),
            "allocations": Counter(),
            "methodCounts": Counter(),
            "sampledTargets": Counter(),
            "methodSpans": [],
            "pauses": [],
            "sampleTimes": [],
            "dataLossBytes": 0,
        }
    timeline = []
    thread_names: dict[str, tuple[int, str]] = {}
    primary_threads = set()
    count = 0
    for event in events:
        count += 1
        if count > limit:
            raise ValueError(
                "JFR event limit exceeded; retain recordings and select a shorter window"
            )
        value, kind = event["values"], event["type"]
        start = timestamp_ns(value["startTime"])
        duration = duration_ns(value.get("duration"))
        if duration < 0:
            raise ValueError("Negative JFR event duration")
        end = start + duration
        owner = value.get("sampledThread") or value.get("eventThread") or {}
        thread_name = owner.get("javaName") or owner.get("osName") or "(unknown)"
        thread_id = owner.get("javaThreadId")
        thread_key = (
            "java:" + str(thread_id)
            if thread_id and thread_id > 0
            else "os:" + str(owner["osThreadId"])
            if owner.get("osThreadId")
            else "name:" + thread_name
        )
        if thread_key not in thread_names:
            thread_names[thread_key] = (len(thread_names) + 1, thread_name)
        stack = methods(value)
        method = value.get("method")
        method_name = (
            method["type"]["name"].replace("/", ".") + "." + method["name"] if method else None
        )
        stage = targets.get(method_name, method_name) if method_name else kind
        # JFR can retain the initial "main" name after the game renames its thread.
        # Identify the owner from actual game-loop/render method evidence instead.
        if (
            side == "server"
            and (
                stage == "server.tick"
                or "net.minecraft.server.MinecraftServer.func_71217_p" in stack
            )
        ) or (
            side == "client"
            and (
                stage in ("dh.render", "dh.renderDeferred")
                or "net.minecraft.client.Minecraft.func_71411_J" in stack
            )
        ):
            primary_threads.add(thread_key)
        selected = []
        for name, result in results.items():
            a, b = result["start"], result["end"]
            if not (a <= start < b or start < b and end > a):
                continue
            selected.append(name)
            result["events"][kind] += 1
            if kind == "jdk.ExecutionSample":
                result["samples"][
                    stage_name(stack) + " | " + thread_name + " [" + thread_key + "]"
                ] += 1
                result["stacks"][" <- ".join(stack[:12])] += 1
                result["sampleTimes"].append((start, thread_name, stage_name(stack)))
                for target in set(stack):
                    if target in targets:
                        result["sampledTargets"][targets[target]] += 1
            elif kind == "jdk.NativeMethodSample":
                result["nativeSamples"][thread_key] += 1
            elif kind == "jdk.ObjectAllocationSample":
                result["allocations"][stage_name(stack)] += value.get("weight", 0)
            elif kind == "jdk.MethodTrace":
                result["methodCounts"][stage] += 1
                result["methodSpans"].append((start, end, stage, thread_name, thread_key))
            elif kind == "jdk.GCPhasePause":
                result["pauses"].append((start, end))
            elif kind == "jdk.DataLoss":
                result["dataLossBytes"] += value.get("amount", 0)
        if selected and kind not in (
            "jdk.ExecutionSample",
            "jdk.NativeMethodSample",
            "jdk.ObjectAllocationSample",
        ):
            timeline.append(
                {
                    "name": stage,
                    "cat": kind,
                    "ph": "X" if duration else "i",
                    "pid": 1 if side == "server" else 2,
                    "tid": thread_names[thread_key][0],
                    "ts": start / 1000,
                    "dur": duration / 1000,
                    "args": {
                        "scenarios": selected,
                        "scope": "inclusive wall time; not causal attribution",
                    },
                }
            )
    reports = {}
    for name, result in results.items():
        outliers = []
        for row in sorted(result["slow"], key=lambda r: r["durationNanos"], reverse=True)[:12]:
            start, end = row["startEpochNanos"], row["endEpochNanos"]
            grouped: dict[str, list[tuple[int, int]]] = {}
            for a, b, stage, thread_name, thread_key in result["methodSpans"]:
                if (
                    len(primary_threads) == 1
                    and thread_key in primary_threads
                    and a < end
                    and b > start
                ):
                    grouped.setdefault(stage, []).append((a, b))
            outliers.append(
                {
                    **row,
                    "sameThreadStageOverlapMs": {
                        k: union_overlap(v, start, end) / 1e6 for k, v in grouped.items()
                    },
                    "gcPauseOverlapMs": union_overlap(result["pauses"], start, end) / 1e6,
                    "concurrentSampleCounts": dict(
                        Counter(owner for t, _, owner in result["sampleTimes"] if start <= t < end)
                    ),
                }
            )
        reports[name] = {
            "events": dict(result["events"]),
            "executionSamples": result["samples"].most_common(),
            "nativeSamples": result["nativeSamples"].most_common(),
            "sampledStacks": result["stacks"].most_common(30),
            "allocationWeightBytes": result["allocations"].most_common(),
            "methodCounts": dict(result["methodCounts"]),
            "inclusiveTargetSampleCounts": dict(result["sampledTargets"]),
            "gcPauseUnionMs": union_overlap(result["pauses"], result["start"], result["end"]) / 1e6,
            "dataLossBytes": result["dataLossBytes"],
            "outliers": outliers,
            "primaryThreadIdentities": sorted(primary_threads),
            "primaryThreadScope": "Derived from recorded server tick or client render/game-loop methods; ambiguous or absent identities disable same-thread overlap attribution.",
            "unobservedStages": sorted(set(targets.values()) - set(result["methodCounts"])),
            "unobservedScope": "No recorded above-threshold invocation in this window; not a zero-cost claim.",
        }
    for key, (tid, name) in thread_names.items():
        timeline.append(
            {
                "name": "thread_name",
                "ph": "M",
                "pid": 1 if side == "server" else 2,
                "tid": tid,
                "args": {"name": side + ": " + name, "nativeIdentity": key},
            }
        )
    return reports, timeline


def trace_run(report, output):
    phase = Path(report["phase"])
    java = read_json(ROOT / ".local/settings.json")["java26"]
    helper = ROOT / "dev/CubicChunks/tools/JfrWindowExport.java"
    registry = report["manifest"].get("observability", {}).get("configuration")
    registry_source = (
        "frozen capture configuration"
        if registry
        else "current registry fallback for historical recording"
    )
    registry = registry or configuration()
    targets = {t["class"] + "." + t["method"]: t["stage"] for t in registry["targets"]}
    result = {
        "schema": 1,
        "sourceResultSha256": report["resultSha256"],
        "semantics": SEMANTICS,
        "exporterSha256": sha(helper),
        "processes": {},
        "errors": list(report["errors"]),
        "warnings": list(report["warnings"]),
        "nativeProtocolPassed": report["protocolPassed"],
        "targetRegistrySource": registry_source,
        "targetRegistry": registry,
    }
    timeline = []
    for side in ["server", "client"]:
        recording = phase / (side + ".jfr")
        windows = []
        for row in report["scenarios"]:
            all_rows = report["result"].get("scenarios", []) + report["result"].get(
                "incompleteScenarios", []
            )
            capture = next(s.get(side) or {} for s in all_rows if s["runId"] == row["runId"])
            samples = row[side]["rows"]
            if samples:
                start = min(
                    timestamp_ns(capture["startUtc"]), min(r["startEpochNanos"] for r in samples)
                )
                end = max(timestamp_ns(capture["endUtc"]), max(r["endEpochNanos"] for r in samples))
                windows.append((row["scenario"], start, end, samples))
        if not recording.is_file():
            result["processes"][side] = {"available": False, "reason": "Recording is unavailable"}
            result["warnings"].append(side + ": JFR recording unavailable")
            continue
        lifetime = not windows
        if lifetime:
            windows = [("process-lifetime", 0, 2**63 - 1, [])]
        raw = output / (side + "-events.json")
        command = [
            java,
            "-Djava.io.tmpdir=" + str(output),
            str(helper),
            str(recording),
            str(min(w[1] for w in windows)),
            str(max(w[2] for w in windows)),
            EVENTS,
            str(output / (side + "-recording-method-counts.json")),
            str(output / (side + "-recording-envelope.json")),
            str(LIMIT),
        ]
        try:
            with (
                raw.open("w", encoding="utf-8") as stream,
                (output / (side + "-export.log")).open("w") as log,
            ):
                process = subprocess.run(command, stdout=stream, stderr=log, timeout=180)
        except subprocess.TimeoutExpired:
            result["processes"][side] = {
                "available": False,
                "reason": "JFR export exceeded 180 seconds; partial output retained",
            }
            result["errors"].append(side + " JFR export timed out")
            continue
        if process.returncode:
            result["processes"][side] = {"available": False, "reason": "JFR export failed"}
            result["errors"].append(side + " JFR export failed; inspect the retained export log")
            continue
        envelope = read_json(output / (side + "-recording-envelope.json"))
        envelope["windows"] = (
            {
                name: bool(
                    envelope["recordedEvents"]
                    and envelope["firstEventEpochNanos"] <= start
                    and envelope["lastEventEpochNanos"] >= end
                )
                for name, start, end, _ in windows
            }
            if not lifetime
            else {}
        )
        if any(not covered for covered in envelope["windows"].values()):
            result["warnings"].append(
                side + ": retained event envelope does not cover every complete capture window"
            )
        try:
            with raw.open(encoding="utf-8") as stream:
                rows, events = aggregate(iter_jfr_events(stream), windows, side, targets=targets)
        except (ValueError, KeyError) as error:
            result["processes"][side] = {
                "available": False,
                "reason": str(error),
                "recordingSha256": sha(recording),
            }
            result["errors"].append(side + ": " + str(error))
            continue
        skipped = []
        log_path = phase / (side + ".log")
        if log_path.is_file():
            with log_path.open(encoding="utf-8", errors="replace") as log:
                for line in log:
                    match = re.search(r"Skipping instrumentation for ([\w.$]+)", line)
                    if match:
                        skipped.append({"class": match[1], "message": line.strip()[:1800]})
        result["processes"][side] = {
            "available": True,
            "recordingSha256": sha(recording),
            "exportSha256": sha(raw),
            "scenarios": rows,
            "skippedInstrumentation": skipped,
            "recordingEnvelope": envelope,
            "windowScope": "Whole retained process recording, including startup/shutdown; no completed capture window."
            if lifetime
            else "Native capture windows including complete first/final tick samples.",
        }
        if skipped:
            result["warnings"].append(
                side
                + ": runtime skipped method instrumentation; sampled target counts remain separate from method spans"
            )
        if any(r["events"].get("jdk.DataLoss") for r in rows.values()):
            result["errors"].append(side + " JFR reported data loss")
        elif envelope["dataLossEvents"]:
            result["warnings"].append(side + ": JFR data loss exists outside the selected windows")
        timeline.extend(events)
    # Use a small relative epoch to preserve sub-millisecond numbers in JS viewers.
    starts = [e["ts"] for e in timeline if "ts" in e]
    origin = min(starts) if starts else 0
    for event in timeline:
        if "ts" in event:
            event["ts"] -= origin
        if event["ph"] == "i":
            event["s"] = "t"
    result["timelineOriginEpochMicros"] = origin
    (output / "timeline.json").write_text(
        json.dumps(
            {
                "traceEvents": timeline,
                "displayTimeUnit": "ms",
                "otherData": {"originEpochMicros": origin, "semantics": SEMANTICS},
            }
        ),
        encoding="utf-8",
    )
    (output / "trace.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result
