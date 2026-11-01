"""Lightweight telemetry: one dated JSON record per native run, an ordered index, a website export.

Every capture and pregeneration leaves a large evidence tree under .local/cubic-standalone. This
module distils each run into one compact record (server tick, frame time, cube delivery, save
stalls, memory, LOD delivery) and files it by date and kind:

    .local/telemetry/runs/<YYYY>/<MM-DD>/<HHMMSS>Z-<kind>-<instance>.json
    .local/telemetry/index.json        every record in run order, numbered

Records are private (instance names, artifact names, hashes); `export` writes the sanitized
website file: numbers, workload and scenario names, dates and an anonymous run number only.
Times are milliseconds unless a key says otherwise. Percentiles are the lab's nearest-rank values.
"""

import json
import math
import subprocess
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

from observability.profiles import ROOT

SCHEMA = "riven.telemetry.v1"
LEDGER = ROOT / ".local/telemetry"
INSTANCES = ROOT / ".local/cubic-standalone"
# DH's MEDIUM quality: 1-block cells to 2 x 192 blocks, then each doubling of distance doubles them.
DH_UNIT, DH_BASE, NATIVE_HALF = 192.0, 2.0, 200
COVERAGE_BANDS = [(200, 384), (384, 768), (768, 1536), (1536, 3072), (3072, 4096)]
STORAGE_SPANS = [
    "writerFlush",
    "backendFlush",
    "regionFlushForce",
    "writerLockWait",
    "cubePurgePass",
]


def _r(value, digits=3):
    return None if value is None else round(value, digits)


def _timing(sample, keys=("p50", "p95", "p99", "max", "mean")):
    """A lab timing sample ({count, p50Ms, ...}) as {p50, p95, p99, max, mean} milliseconds."""
    if not sample or not sample.get("count"):
        return None
    out = {key: _r(sample.get(key + "Ms")) for key in keys}
    out["count"] = sample["count"]
    return out


def _over(sample, nanos):
    return (sample or {}).get("exceedanceCountsAboveNanos", {}).get(str(nanos))


def _utc(text):
    """An ISO instant (Z, an offset or none, any fraction) normalized to seconds: 2026-11-01T04:57:58Z."""
    if not text:
        return None
    value = datetime.strptime(text[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
    zone = text[19:].lstrip("0123456789.")
    if zone[:1] in "+-" and len(zone) >= 6:
        sign = 1 if zone[0] == "+" else -1
        value -= sign * timedelta(hours=int(zone[1:3]), minutes=int(zone[4:6]))
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


def _artifact(entry):
    """A frozen artifact as its build name (the frozen folder, e.g. native-ground-ca52295d)."""
    if not entry:
        return None
    path = Path(entry.get("path") or entry.get("source") or "")
    name = path.parent.name if path.parent.name not in ("", "pins", "libs") else path.name
    return {"build": name, "sha256": (entry.get("sha256") or "")[:12] or None}


def _scenario(scenario, lod):
    server, client = scenario.get("server") or {}, scenario.get("client") or {}
    ticks = server.get("completeServerTicks") or server.get("serverTicks")
    frames = client.get("frameIntervals")
    out = {
        "scenario": scenario.get("scenario"),
        "passed": bool(server.get("passed")),
        "startedUtc": _utc(server.get("startUtc") or client.get("startUtc")),
        "seconds": _r((server.get("elapsedNanos") or 0) / 1e9, 2),
        "measurement": server.get("measurementMode"),
        "tick": _timing(ticks),
        "frame": _timing(frames),
        "clientTick": _timing(client.get("clientTicks"), ("p50", "p95", "p99", "max")),
        "renderWork": _timing(client.get("renderEventWork"), ("p50", "p95", "p99", "max")),
    }
    if out["tick"]:
        out["tick"]["over50ms"] = _over(ticks, 50000000)
        out["tick"]["over100ms"] = _over(ticks, 100000000)
        out["tick"]["excessOver50ms"] = _r(((ticks or {}).get("excessOver50msNanos") or 0) / 1e6, 1)
    if out["frame"]:
        out["frame"]["over16_7ms"] = _over(frames, 16666667)
        out["frame"]["over33_3ms"] = _over(frames, 33333333)
    packets = client.get("packets") or server.get("packets") or {}
    out["cubes"] = {
        "delivered": packets.get("cubeCount"),
        "packets": packets.get("packetCount"),
        "payloadMiB": _r((packets.get("serializedPayloadBytes") or 0) / 2**20, 2),
        "loadedAtStart": server.get("captureStartLoadedCubes"),
        "loadedAtEnd": server.get("finalLoadedCubes"),
        "loads": server.get("cubeLoadEvents"),
        "unloads": server.get("cubeUnloadEvents"),
        "saves": server.get("cubeDataSaveEvents"),
        "readsFromDisk": server.get("cubeDataLoadEvents"),
    }
    save = {
        "submission": _r((server.get("saveSubmissionNanos") or 0) / 1e6, 1),
        "blockingFlush": _r((server.get("blockingFlushNanos") or 0) / 1e6, 1),
    }
    storage = server.get("storageDiagnostics") or {}
    if storage.get("enabled"):
        timings = storage.get("timings") or {}
        save["spans"] = {
            name: _timing(timings[name], ("p99", "max"))
            for name in STORAGE_SPANS
            if (timings.get(name) or {}).get("count")
        }
        save["events"] = storage.get("events")
    out["save"] = save
    out["gcCollections"] = client.get("gcCollections")
    if lod:
        out["lod"] = lod
    return out


def _process(lifecycle):
    endpoint = (lifecycle.get("endpoints") or {}).get("endBeforeQuit") or {}
    if not endpoint.get("available"):
        return None
    return {
        "launchToReadySeconds": _r((lifecycle.get("launchToReadyNanos") or 0) / 1e9, 1),
        "peakWorkingSetGiB": _r(endpoint.get("peakWorkingSetBytes", 0) / 2**30, 2),
        "cpuSeconds": _r(
            (endpoint.get("kernelCpuNanos", 0) + endpoint.get("userCpuNanos", 0)) / 1e9, 1
        ),
    }


def _git(directory):
    try:
        head = subprocess.run(
            ["git", "-C", str(directory), "rev-parse", "--short=8", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        dirty = subprocess.run(
            ["git", "-C", str(directory), "status", "--porcelain", "--untracked-files=no"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        return head + ("+changes" if dirty else "")
    except OSError, subprocess.CalledProcessError:
        return None


# --- LOD delivery from the lab's DH trace --------------------------------------------------------


def _wanted(distance):
    return 1 << max(0, int(math.log(distance / DH_UNIT, DH_BASE))) if distance > DH_UNIT else 1


def _coverage(track, data):
    """Share of the ring 200..4096 blocks around the player whose finest real data is at DH's wanted
    cell width or finer, at the window's end (a 32-block grid). Native terrain covers the square
    along the player's track; answered sections cover the rest, a section without its own data
    counting at its nearest answered ancestor's width."""
    if not track:
        return None
    px, pz = track[-1]
    step = 32
    # Grid points (i, j) at (px + 32i, pz + 32j) inside the native square of some track point.
    native: set[tuple[int, int]] = set()
    for tx, tz in track[:: max(1, len(track) // 400)] + [track[-1]]:
        i0, i1 = (
            math.ceil((tx - NATIVE_HALF - px) / step),
            math.floor((tx + NATIVE_HALF - px) / step),
        )
        j0, j1 = (
            math.ceil((tz - NATIVE_HALF - pz) / step),
            math.floor((tz + NATIVE_HALF - pz) / step),
        )
        native.update((i, j) for i in range(i0, i1 + 1) for j in range(j0, j1 + 1))
    bands: dict[tuple[int, int], Counter[str]] = {band: Counter() for band in COVERAGE_BANDS}
    for gx in range(-4096, 4097, step):
        for gz in range(-4096, 4097, step):
            distance = math.hypot(gx, gz)
            band = next((b for b in COVERAGE_BANDS if b[0] <= distance < b[1]), None)
            if band is None or max(abs(gx), abs(gz)) <= NATIVE_HALF:
                continue
            x, z = px + gx, pz + gz
            best = None
            if (gx // step, gz // step) in native:
                best = 1
            else:
                for detail in range(6, 14):
                    width = 64 << (detail - 6)
                    if (detail, math.floor(x / width), math.floor(z / width)) in data:
                        best = 1 << (detail - 6)
                        break
            wanted = _wanted(distance)
            key = "none" if best is None else "onTarget" if best <= wanted else "coarser"
            bands[band][key] += 1
    out = {}

    def shares(counts):
        whole = sum(counts.values()) or 1
        return {k: _r(counts[k] / whole, 3) for k in ("onTarget", "coarser", "none")}

    out = {f"{low}-{high}": shares(counts) for (low, high), counts in bands.items()}
    # "near" is the 200-768 block ring the eye judges most; "all" weighs the whole ring by area.
    for label, chosen in (("near", COVERAGE_BANDS[:2]), ("all", COVERAGE_BANDS)):
        merged: Counter[str] = Counter()
        for band in chosen:
            merged.update(bands[band])
        out[label] = shares(merged)
    return out


def _lod_windows(trace_dir, scenarios):
    """Per lab window (one per scenario, in order): requests, answers, service time, coverage."""
    from observability.lod import DETAIL, OP, POS, A, T, X, Z, load_side, pair_durations, stats

    client = load_side(trace_dir, "client")
    if not client:
        return {}
    server = load_side(trace_dir, "server")
    rows = client["rows"]
    starts = [r[T] for r in rows if r[OP] == "lab-start"]
    finishes = [r[T] for r in rows if r[OP] == "lab-finish"]
    server_rows = server["rows"] if server else []
    s_starts = [r[T] for r in server_rows if r[OP] == "lab-start"]
    s_finishes = [r[T] for r in server_rows if r[OP] == "lab-finish"]
    out: dict[str, dict] = {}
    data: set[tuple[int, int, int]] = set()
    track: list[tuple[int, int]] = []
    cursor = 0
    for index, (name, start, end) in enumerate(zip(scenarios, starts, finishes)):
        by_op: defaultdict[str, defaultdict[int, list[int]]] = defaultdict(
            lambda: defaultdict(list)
        )
        answers: Counter[str] = Counter()
        while cursor < len(rows) and rows[cursor][T] <= end:
            r = rows[cursor]
            cursor += 1
            op = r[OP]
            if op == "player":
                point = (r[A], r[A + 2])
                # A teleport (the lab's join or a scenario's start) leaves the old view behind.
                if track and max(abs(point[0] - track[-1][0]), abs(point[1] - track[-1][1])) > 256:
                    track.clear()
                track.append(point)
            elif op == "net-response" and r[A] == 0:
                data.add((r[DETAIL], r[X], r[Z]))
            if r[T] < start:
                continue
            if op in ("net-sent", "net-response"):
                by_op[op][r[POS]].append(r[T])
            if op == "net-response":
                answers[("data", "empty", "failed")[min(r[A], 2)]] += 1
        seconds = (end - start) / 1e9
        service = pair_durations(by_op, "net-sent", "net-response")
        per_detail = defaultdict(list)
        for detail, nanos in service:
            per_detail[detail].append(nanos)
        window = {
            "seconds": _r(seconds, 2),
            "requestsSent": sum(len(v) for v in by_op["net-sent"].values()),
            "answers": dict(answers),
            "answersPerSecond": _r(sum(answers.values()) / seconds, 2) if seconds else None,
            "serviceMs": {k: v for k, v in stats([n for _, n in service]).items() if k != "p90Ms"},
            "serviceP50MsByDetail": {
                str(1 << (d - 6)) + "-block": stats(v)["p50Ms"]
                for d, v in sorted(per_detail.items())
                if d >= 6
            },
            "coverageAtEnd": _coverage(track, data),
        }
        if index < len(s_starts) and index < len(s_finishes):
            s0, s1 = s_starts[index], s_finishes[index]
            generated = [r for r in server_rows if s0 <= r[T] <= s1 and r[OP] == "gen-finished"]
            window["tilesGenerated"] = len(generated)
        out[name] = window
    return out


# --- records -------------------------------------------------------------------------------------


def capture_record(instance, commits=None):
    """The record of an instance's native capture, or None when it has none."""
    result_path = instance / "runs/write/result.json"
    manifest_path = instance / "standalone.json"
    if not result_path.is_file() or not manifest_path.is_file():
        return None
    result = json.loads(result_path.read_text(encoding="utf-8"))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    scenarios = result.get("scenarios") or []
    sequence = result.get("scenarioSequence") or [s.get("scenario") for s in scenarios]
    lod = {}
    if (instance / "lod-trace").is_dir():
        try:
            lod = _lod_windows(instance / "lod-trace", sequence)
        except (OSError, ValueError) as error:
            lod = {"_error": str(error)}
    rows = [_scenario(s, lod.get(s.get("scenario"))) for s in scenarios]
    lifecycle = result.get("processLifecycle") or {}
    started = min((r["startedUtc"] for r in rows if r["startedUtc"]), default=None) or _utc(
        (lifecycle.get("server") or {}).get("launchUtc")
    )
    first = scenarios[0] if scenarios else {}
    client = first.get("client") or {}
    runtime = (first.get("server") or {}).get("runtime") or client.get("runtime") or {}
    dh = manifest.get("distantHorizons") or {}
    name = manifest.get("name") or instance.name
    record = {
        "schema": SCHEMA,
        "kind": "smoke" if name.startswith("smoke-") else "capture",
        "mode": (manifest.get("observability") or {}).get("mode"),
        "instance": name,
        "startedUtc": started,
        "workload": (
            result.get("benchmarkWorkload") or manifest.get("benchmarkWorkload") or {}
        ).get("id"),
        "seed": manifest.get("seed"),
        "passed": bool(result.get("passed")),
        "failure": result.get("failure")
        or (
            "incomplete: " + ", ".join(result["incompleteScenarios"])
            if result.get("incompleteScenarios")
            else None
        ),
        "setup": {
            "deliveryMode": manifest.get("cubatureDelivery"),
            "viewDistance": (manifest.get("viewDistance") or {}).get("horizontal")
            if isinstance(manifest.get("viewDistance"), dict)
            else manifest.get("viewDistance"),
            "worldFrom": (manifest.get("worldFrom") or {}).get("instance"),
            "distantHorizons": {
                "side": dh.get("side"),
                "workers": dh.get("workers"),
                "lodRadiusChunks": dh.get("lodRadiusChunks"),
                "arrivalPlan": dh.get("cubatureArrivalPlan", True) if dh else None,
                "nativeFeed": dh.get("cubatureNativeFeed", "ALL") if dh else None,
                "previewThreads": dh.get("cubaturePreviewThreads"),
                "lodOnly": bool(dh.get("lodOnlyMode")),
            }
            if dh
            else None,
            "lodTrace": (instance / "lod-trace").is_dir(),
        },
        "machine": {
            "os": runtime.get("os"),
            "processors": runtime.get("processors"),
            "java": runtime.get("java"),
            "maxHeapGiB": _r((runtime.get("maxHeapBytes") or 0) / 2**30, 1),
            "gpu": client.get("glRenderer"),
        },
        "versions": {
            "riven": _artifact(manifest.get("sourceArtifact")),
            "cubature": _artifact(manifest.get("sourceCubicArtifact")),
            "observer": _artifact(
                manifest.get("labJar") if isinstance(manifest.get("labJar"), dict) else None
            ),
            **({"toolsCommits": commits} if commits else {}),
        },
        "process": {
            side: _process(lifecycle.get(side) or {})
            for side in ("server", "client")
            if lifecycle.get(side)
        },
        "scenarios": rows,
    }
    if lod.get("_error"):
        record["lodError"] = lod["_error"]
    return record


def pregeneration_records(instance):
    """One record per pregeneration run of an instance (runs/pregenerate-*/result.json)."""
    import gzip

    manifest_path = instance / "standalone.json"
    if not manifest_path.is_file():
        return []
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    plan = manifest.get("pregeneration") or {}
    journal = (
        Path(manifest.get("server", instance / "server"))
        / "cubiclab-pregeneration"
        / str(plan.get("id"))
    )
    batches = []
    for path in sorted(journal.glob("batch-*.json.gz")) if plan else []:
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            batches.append(json.load(stream))
    checkpoint = journal / "checkpoint.json"
    checkpoint = json.loads(checkpoint.read_text(encoding="utf-8")) if checkpoint.is_file() else {}
    reports = sorted(instance.glob("pregeneration-report-*.json"), key=lambda p: p.stat().st_mtime)
    report = json.loads(reports[-1].read_text(encoding="utf-8")) if reports else {}
    out = []
    for run in sorted((instance / "runs").glob("pregenerate-*/result.json")):
        result = json.loads(run.read_text(encoding="utf-8"))
        process = run.parent / "server-process.json"
        lifecycle = json.loads(process.read_text(encoding="utf-8")) if process.is_file() else {}
        started = _utc(lifecycle.get("launchUtc")) or datetime.fromtimestamp(
            run.stat().st_mtime - (result.get("seconds") or 0), timezone.utc
        ).strftime("%Y-%m-%dT%H:%M:%SZ")
        seconds = sum(b["elapsedNanos"] for b in batches) / 1e9
        done = sum(b["endColumn"] - b["firstColumn"] for b in batches)
        out.append(
            {
                "schema": SCHEMA,
                "kind": "pregeneration",
                "mode": "pregeneration",
                "instance": manifest.get("name") or instance.name,
                "run": run.parent.name,
                "startedUtc": started,
                "workload": plan.get("id"),
                "seed": manifest.get("seed"),
                "passed": bool(result.get("passed")),
                "failure": result.get("failure"),
                "versions": {
                    "riven": _artifact(manifest.get("sourceArtifact")),
                    "cubature": _artifact(manifest.get("sourceCubicArtifact")),
                },
                "pregeneration": {
                    "seconds": _r(result.get("seconds"), 1),
                    # The rest is the whole job's journal, across every run that added to it.
                    "targetColumns": plan.get("totalColumns"),
                    "committedColumns": checkpoint.get("committedColumns"),
                    "complete": checkpoint.get("complete"),
                    "columnsPerSecond": _r(done / seconds, 2) if seconds else None,
                    "cubesPerSecond": _r(done * plan.get("cubesPerColumn", 0) / seconds, 1)
                    if seconds
                    else None,
                    "generationSeconds": _r(sum(b["generationNanos"] for b in batches) / 1e9, 1),
                    "saveSeconds": _r(sum(b["saveNanos"] for b in batches) / 1e9, 1),
                    "peakHeapGiB": _r(
                        max((b.get("heapUsedBytes", 0) for b in batches), default=0) / 2**30, 2
                    ),
                    "worldGiB": _r((report.get("bytes") or {}).get("total", 0) / 2**30, 2) or None,
                    "distantHorizonsGiB": _r(
                        (report.get("bytes") or {}).get("distantHorizons", 0) / 2**30, 2
                    )
                    or None,
                },
            }
        )
    return out


def _path(record):
    stamp = record["startedUtc"] or "1970-01-01T00:00:00Z"
    suffix = "-" + record["run"] if record.get("run") else ""
    return (
        LEDGER
        / "runs"
        / stamp[:4]
        / stamp[5:10]
        / (
            stamp[11:19].replace(":", "")
            + "Z-"
            + record["kind"]
            + "-"
            + record["instance"]
            + suffix
            + ".json"
        )
    )


def write(record):
    path = _path(record)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, separators=(",", ":")) + "\n", encoding="utf-8")
    return path


def collect(instance, commits=True):
    """Record an instance's runs in the ledger and refresh the index; returns the written paths."""
    instance = Path(instance)
    tools = {"riven": _git(ROOT), "cubature": _git(ROOT / "dev/CubicChunks")} if commits else None
    records = [capture_record(instance, tools)] + pregeneration_records(instance)
    paths = [write(r) for r in records if r]
    build_index()
    return paths


def collect_quietly(name):
    """The harness's end-of-run hook: record, and report instead of failing the run."""
    try:
        for path in collect(INSTANCES / name):
            print("Telemetry: " + str(path))
    except Exception as error:  # noqa: BLE001 - telemetry must never change a run's outcome
        print("Telemetry not recorded: " + repr(error))


def backfill():
    """Record every instance under .local/cubic-standalone; existing records are rewritten."""
    written, skipped = 0, []
    for instance in sorted(p for p in INSTANCES.iterdir() if p.is_dir()):
        try:
            records = [capture_record(instance)] + pregeneration_records(instance)
        except (OSError, ValueError, KeyError, TypeError) as error:
            skipped.append(f"{instance.name}: {error!r}")
            continue
        for record in records:
            if record:
                write(record)
                written += 1
    index = build_index()
    return {"written": written, "indexed": len(index["runs"]), "skipped": skipped}


def build_index():
    runs = []
    for path in (LEDGER / "runs").rglob("*.json") if (LEDGER / "runs").is_dir() else []:
        record = json.loads(path.read_text(encoding="utf-8"))
        runs.append(
            {
                "startedUtc": record["startedUtc"],
                "kind": record["kind"],
                "mode": record.get("mode"),
                "workload": record.get("workload"),
                "instance": record["instance"],
                "passed": record["passed"],
                "scenarios": [s["scenario"] for s in record.get("scenarios", [])],
                "path": path.relative_to(LEDGER).as_posix(),
            }
        )
    runs.sort(key=lambda r: (r["startedUtc"] or "", r["path"]))
    for number, run in enumerate(runs, 1):
        run["run"] = number
    index = {
        "schema": SCHEMA + ".index",
        "generatedUtc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "runs": len(runs),
        "byKind": dict(Counter(r["kind"] for r in runs)),
        "byWorkload": dict(Counter(r["workload"] for r in runs).most_common()),
        "first": runs[0]["startedUtc"] if runs else None,
        "last": runs[-1]["startedUtc"] if runs else None,
        "runsInOrder": runs,
    }
    LEDGER.mkdir(parents=True, exist_ok=True)
    (LEDGER / "index.json").write_text(json.dumps(index, indent=1) + "\n", encoding="utf-8")
    index["runs"] = runs
    return index


# --- website export ------------------------------------------------------------------------------


def _public_lod(lod):
    if not lod:
        return None
    coverage = lod.get("coverageAtEnd") or {}
    return {
        "answersPerSecond": lod.get("answersPerSecond"),
        "serviceP50": (lod.get("serviceMs") or {}).get("p50Ms"),
        "serviceP99": (lod.get("serviceMs") or {}).get("p99Ms"),
        "onTarget": (coverage.get("all") or {}).get("onTarget"),
        "onTargetNear": (coverage.get("near") or {}).get("onTarget"),
        "missing": (coverage.get("all") or {}).get("none"),
    }


def export(target):
    """Write the sanitized website dataset: one record per scenario of every capture run."""
    index = build_index()
    records: list[dict] = []
    runs = 0
    processors: Counter[int | None] = Counter()
    systems: Counter[str] = Counter()
    for run in index["runs"]:
        if run["kind"] == "pregeneration":
            continue
        record = json.loads((LEDGER / run["path"]).read_text(encoding="utf-8"))
        runs += 1
        machine = record.get("machine") or {}
        processors[machine.get("processors")] += 1
        systems[(machine.get("os") or "").split(" ")[0]] += 1
        for scenario in record["scenarios"]:
            tick, frame = scenario.get("tick") or {}, scenario.get("frame") or {}
            if not tick or not frame:
                continue
            spans = (scenario.get("save") or {}).get("spans") or {}
            row = {
                "run": run["run"],
                "startedUtc": scenario.get("startedUtc") or record["startedUtc"],
                "kind": record["kind"],
                "workload": record["workload"],
                "scenario": scenario["scenario"],
                "mode": "diagnostic"
                if "diagnostic" in (scenario.get("measurement") or "")
                else "ordinary",
                "passed": scenario["passed"] and record["passed"],
                "seconds": scenario.get("seconds"),
                "tick": {k: tick.get(k) for k in ("p50", "p95", "p99", "max", "mean", "over50ms")},
                "frame": {
                    k: frame.get(k) for k in ("p50", "p95", "p99", "max", "mean", "over16_7ms")
                },
                "cubes": (scenario.get("cubes") or {}).get("delivered"),
                "save": {
                    "blockingFlush": (scenario.get("save") or {}).get("blockingFlush"),
                    **{name: span.get("max") for name, span in spans.items() if span},
                }
                or None,
            }
            lod = _public_lod(scenario.get("lod"))
            if lod:
                row["lod"] = lod
            records.append(row)
    cores = processors.most_common(1)[0][0] if processors else None
    system = systems.most_common(1)[0][0] if systems else None
    data = {
        "meta": {
            "runs": runs,
            "records": len(records),
            "generatedUtc": index["generatedUtc"],
            "hardware": f"one desktop, {cores} logical processors, {system}",
            "units": "milliseconds; tick = server tick, frame = client frame interval; lod.onTarget (onTargetNear) = share of the 200-4096 (200-768) block ring at DH's wanted detail at the scenario's end",
            "note": "Server ticks are every tick overlapping the capture. Diagnostic-mode records include observer overhead.",
        },
        "records": records,
    }
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data, separators=(",", ":")) + "\n", encoding="utf-8")
    return {
        "path": str(target),
        "runs": runs,
        "records": len(records),
        "bytes": target.stat().st_size,
    }
