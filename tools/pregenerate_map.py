"""Prepare, resume and inspect a bounded native Riven terrain pregeneration job.

Maps:
  map-10k   the 20000 by 20000 block inspection map, the lab band (Y -128 to 384)
  world-8k  a fully pregenerated 8192 by 8192 block world: every cube from the world floor to its
            top, each column handed to Distant Horizons' server database, so a later flight mostly
            fetches; `report` gives the world's size and the job's throughput
"""

import argparse
import ctypes
import gzip
import hashlib
import importlib
import json
import os
import shutil
import struct
import uuid
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import runtime
from measure_pipeline import controller_sources, input_files, retain_inputs
from observability import telemetry
from observability.profiles import sha

ROOT, lab = runtime.ROOT, runtime.lab
import lab_files  # on the path runtime adds (Cubature's tools)

BAND = (-128, 384)
MAPS: dict[str, dict[str, Any]] = {
    "map-10k": {
        "radiusBlocks": 10000,
        "fullHeight": False,
        "distantHorizons": False,
        "purpose": "Owned 20000 by 20000 block Riven inspection map",
        "scope": "Native lit terrain cubes, ordinary save/unload. Whole-map DH data and visual acceptance remain separate.",
    },
    "world-8k": {
        "radiusBlocks": 4096,
        "fullHeight": True,
        "distantHorizons": True,
        "purpose": "A fully pregenerated 8192 by 8192 block Riven world, cubes and DH data",
        "scope": "Every native lit cube from the world floor to its top, ordinary save/unload; each column handed to DH's server level. Visual acceptance remains separate.",
    },
}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode("utf-8")).hexdigest()


def allocated_height():
    """The packaged height reference's allocation, so a retained map records the real bounds."""
    reference = json.loads(
        (ROOT / "src/main/resources/riven-terrain-height-reference.json").read_text(
            encoding="utf-8"
        )
    )
    return [reference["allocation"]["minY"], reference["allocation"]["maxYExclusive"]]


def world_height():
    """The world's floor and top: every cube a fully pregenerated column holds."""
    reference = json.loads(
        (ROOT / "src/main/resources/riven-terrain-height-reference.json").read_text(
            encoding="utf-8"
        )
    )
    return reference["allocation"]["worldMinY"], reference["allocation"]["maxYExclusive"]


def extent(name):
    """The plan values a map fixes: bounds, columns, height and DH feed."""
    profile = MAPS[name]
    radius = profile["radiusBlocks"]
    width = 2 * radius // 16
    low, high = world_height() if profile["fullHeight"] else BAND
    return {
        "id": name,
        "radiusBlocks": radius,
        "blockBounds": {
            "minimumInclusive": [-radius, -radius],
            "maximumExclusive": [radius, radius],
        },
        "originChunkX": -radius // 16,
        "originChunkZ": -radius // 16,
        "widthColumns": width,
        "heightColumns": width,
        "totalColumns": width * width,
        "minY": low,
        "maxYExclusive": high,
        "cubesPerColumn": (high - low) // 16,
        "targetCubes": width * width * (high - low) // 16,
        "distantHorizonsFeed": profile["distantHorizons"],
    }


def generation_inputs(manifest):
    return {
        "mods": manifest["modHashes"],
        "java": {"sha256": sha(Path(manifest["java"])), "jvm": manifest["jvmProfile"]},
        "seed": 8675309,
        "worldType": "riven",
        "generatorOptions": "",
        "allocatedHeight": allocated_height(),
        "terrainDefinitions": "Packaged in the frozen Riven artifact; no external terrain overrides",
    }


def prepare(args):
    if not args.artifact or not args.lab_artifact:
        raise ValueError("Select frozen Riven and pregeneration observer artifacts")
    for path in [args.artifact, args.lab_artifact]:
        if not path.is_file():
            raise ValueError("Artifact is missing: " + str(path))
    runtime.prepare(
        args.name,
        args.port,
        selected_artifact=args.artifact.resolve(),
        cubic_artifact=args.cubic_artifact.resolve() if args.cubic_artifact else None,
        start=[8102.5, 144, 4552.5, -90, 15],
        capture=True,
        capture_ticks=1200,
        spectator=True,
    )
    directory = runtime.target(args.name)
    manifest = runtime.load_json(directory / "standalone.json")
    for side in ["server", "client"]:
        shutil.copy2(args.lab_artifact, Path(manifest[side]) / "mods/cubicchunks-lab.jar")
        manifest["modHashes"][side]["cubicchunks-lab.jar"] = sha(args.lab_artifact)
    profile = MAPS[args.map]
    if profile["fullHeight"]:
        # More generation workers than a player's default, with a queue that holds the columns
        # scheduled ahead: timing only, never content. Storage settings stay the player's.
        config = Path(manifest["server"]) / "config/cubicchunks.cfg"
        text = config.read_text(encoding="utf-8")
        if text.count("general {") != 1 or "concurrentGeneration" in text:
            raise RuntimeError("Expected one Cubature general section without generation workers")
        queue = args.ahead * extent(args.map)["cubesPerColumn"] + 1024
        config.write_text(
            text.replace(
                "general {",
                f"general {{\n    I:concurrentGenerationThreads={args.workers}"
                f"\n    I:concurrentGenerationQueue={queue}",
                1,
            ),
            encoding="utf-8",
        )
    inputs = generation_inputs(manifest)
    manifest["pregeneration"] = {
        "schema": 2,
        **extent(args.map),
        "generationWorkers": args.workers if profile["fullHeight"] else "Cubature default",
        "inputs": inputs,
        "inputsSha256": digest(inputs),
        "scope": profile["scope"],
    }
    manifest["labProperties"] = {
        "cubiclab.pregenInputs": digest(inputs),
        "cubiclab.pregenBatchColumns": "128",
        "cubiclab.pregenTickMillis": "1000",
        **(
            {
                "cubiclab.pregenFullHeight": "true",
                "cubiclab.pregenDhFeed": "true",
                "cubiclab.pregenAhead": str(args.ahead),
                "cubiclab.pregenFlushBatches": "4",
            }
            if profile["fullHeight"]
            else {}
        ),
    }
    manifest["labJar"] = {
        "source": str(args.lab_artifact.resolve()),
        "sha256": sha(args.lab_artifact),
        "reason": "Optional native terrain pregeneration command; production runtime pins remain unchanged",
    }
    manifest["purpose"] = profile["purpose"]
    lab_files.write_json(directory / "standalone.json", manifest)
    load(args.name)
    print(directory, flush=True)


def load(name):
    directory, manifest = runtime.load(name)
    plan = manifest.get("pregeneration")
    if not plan or plan["inputs"] != generation_inputs(manifest):
        raise RuntimeError("Prepared generation inputs changed; use a fresh world")
    if plan.get("schema") != 2 or plan.get("id") not in MAPS:
        raise RuntimeError("Prepared before map profiles; use a fresh world")
    expected = extent(plan["id"])
    if any(plan.get(key) != value for key, value in expected.items()):
        raise RuntimeError("Prepared extent differs from the " + plan["id"] + " map")
    if plan["inputsSha256"] != digest(plan["inputs"]):
        raise RuntimeError("Pregeneration input fingerprint is invalid")
    if manifest["labProperties"].get("cubiclab.pregenInputs") != plan["inputsSha256"]:
        raise RuntimeError("Native pregenerator fingerprint differs")
    return directory, manifest


def journal_directory(manifest):
    return Path(manifest["server"]) / "cubiclab-pregeneration" / manifest["pregeneration"]["id"]


@contextmanager
def keep_system_awake():
    """Hold a thread-owned sleep request only while this bulk controller runs."""
    if os.name != "nt":
        yield
        return
    setter = ctypes.WinDLL("kernel32", use_last_error=True).SetThreadExecutionState
    setter.argtypes = [ctypes.c_uint]
    setter.restype = ctypes.c_uint
    previous = setter(0x80000001)  # ES_CONTINUOUS | ES_SYSTEM_REQUIRED
    if not previous:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        yield
    finally:
        setter(previous)


def run(args):
    with lab.native_measurement_lease(ROOT, "pregenerate-" + args.name), keep_system_awake():
        directory, manifest = load(args.name)
        plan = manifest["pregeneration"]

        def frozen_inputs(value):
            load(args.name)
            return {
                "mods": value["modHashes"],
                "files": {key: sha(path) for key, path in input_files(value).items()},
                "controllerSources": {key: sha(path) for key, path in controller_sources().items()},
                "generationInputsSha256": plan["inputsSha256"],
            }

        # Keep native launch, bounded progress waits and orderly save/stop in the
        # maintained lab runner. Only its manifest/preparation boundary is adapted.
        try:
            return lab.survey(
                SimpleNamespace(
                    native_operation="pregenerate",
                    survey_id=plan["id"],
                    survey_origin_x=plan["originChunkX"],
                    survey_origin_z=plan["originChunkZ"],
                    survey_width=plan["widthColumns"],
                    survey_height=plan["heightColumns"],
                    survey_max_columns=args.max_columns,
                    jfr=args.jfr,
                ),
                load=lambda unused: (directory, manifest),
                prime_lab=lambda unused: None,
                record=frozen_inputs,
                retain=retain_inputs,
            )
        finally:
            telemetry.collect_quietly(args.name)


def status(args):
    _, manifest = load(args.name)
    plan = manifest["pregeneration"]
    output = journal_directory(manifest)
    checkpoint = output / "checkpoint.json"
    value = runtime.load_json(checkpoint) if checkpoint.exists() else {"committedColumns": 0}
    value.update(
        targetColumns=plan["totalColumns"],
        targetCubes=plan["targetCubes"],
        savedTargetCubes=value["committedColumns"] * plan["cubesPerColumn"],
        journal=str(output),
    )
    if value["committedColumns"]:
        first = value["lastBatchFirstColumn"]
        with gzip.open(output / f"batch-{first:07d}.json.gz", "rt", encoding="utf-8") as stream:
            batch = json.load(stream)
        seconds = batch["elapsedNanos"] / 1e9
        rate = (batch["endColumn"] - first) / seconds
        value["lastBatch"] = {
            "columnsPerSecond": rate,
            "generationSeconds": batch["generationNanos"] / 1e9,
            "saveSeconds": batch["saveNanos"] / 1e9,
            "loadedCubesAfterGc": batch["loadedCubesAfterGc"],
            "heapUsedBytes": batch["heapUsedBytes"],
        }
        value["remainingHoursAtLastBatchRate"] = (
            (plan["totalColumns"] - value["committedColumns"]) / rate / 3600
        )
        value["estimateLimit"] = (
            "Local batch extrapolation only; terrain and cold planning costs vary across the map"
        )
    print(json.dumps(value, indent=2))
    return value


def verify(args):
    """Count every saved target slot; decode a deterministic sample of real NBT."""
    with lab.native_measurement_lease(ROOT, "verify-pregeneration-" + args.name):
        directory, manifest = load(args.name)
        plan = manifest["pregeneration"]
        checkpoint = runtime.load_json(journal_directory(manifest) / "checkpoint.json")
        committed = checkpoint["committedColumns"]
        if not 0 < committed <= plan["totalColumns"]:
            raise RuntimeError("No valid saved prefix to inspect")
        if checkpoint["totalColumns"] != plan["totalColumns"] or checkpoint["complete"] != (
            committed == plan["totalColumns"]
        ):
            raise RuntimeError("Checkpoint completion does not match the requested map")
        if not args.allow_partial and not checkpoint["complete"]:
            raise RuntimeError("Full map is incomplete; use --allow-partial only for the pilot")
        world = Path(manifest["server"]) / "world"
        headers, counts = [], {"column": 0, "cube": 0}
        x0, z0, width = plan["originChunkX"], plan["originChunkZ"], plan["widthColumns"]
        low, high = plan["minY"] >> 4, plan["maxYExclusive"] >> 4
        layers = plan["cubesPerColumn"]

        def in_prefix(x, z):
            return 0 <= x - x0 < width and 0 <= (z - z0) * width + x - x0 < committed

        for folder, suffix, entries, kind in [
            ("region2d", "2dr", 1024, "column"),
            ("region3d", "3dr", 4096, "cube"),
        ]:
            home = world / folder
            if any(home.glob("*.ext")):
                raise RuntimeError("External RegionLib entries require a separate verified reader")
            for path in sorted(home.glob("*." + suffix)):
                region = tuple(map(int, path.stem.split(".")))
                if path.name != ".".join(map(str, region)) + "." + suffix:
                    raise RuntimeError("Noncanonical region filename")
                before = path.stat()
                with path.open("rb") as stream:
                    header = stream.read(entries * 4)
                if len(header) != entries * 4:
                    raise RuntimeError("Truncated region header: " + str(path))
                for index, (packed,) in enumerate(struct.iter_unpack(">I", header)):
                    if not packed:
                        continue
                    if (
                        packed == 0xFFFFFFFF
                        or packed & 255 == 0
                        or (packed >> 8) * 512 < len(header)
                        or (packed >> 8) * 512 + 4 > before.st_size
                    ):
                        raise RuntimeError("Invalid ordinary region slot")
                    if kind == "column":
                        x, z = region[0] * 32 + (index >> 5), region[1] * 32 + (index & 31)
                        target = in_prefix(x, z)
                    else:
                        x, y, z = (
                            region[0] * 16 + (index >> 8),
                            region[1] * 16 + ((index >> 4) & 15),
                            region[2] * 16 + (index & 15),
                        )
                        target = low <= y < high and in_prefix(x, z)
                    counts[kind] += int(target)
                after = path.stat()
                if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                    raise RuntimeError("World changed during stopped-world verification")
                headers.append(
                    {
                        "file": str(path.relative_to(world)),
                        "bytes": before.st_size,
                        "headerSha256": hashlib.sha256(header).hexdigest(),
                    }
                )
        if counts != {"column": committed, "cube": committed * layers}:
            raise RuntimeError("Saved target coverage is incomplete: " + str(counts))
        reader = importlib.import_module("inspect_cubic_lab_world").read_regionlib_entries
        witnesses = []
        sample_count = min(64, committed)
        for i in range(sample_count):
            position = i * (committed - 1) // max(1, sample_count - 1)
            x, z = x0 + position % width, z0 + position // width
            path = world / "region2d" / f"{x >> 5}.{z >> 5}.2dr"
            rows = list(reader(path, [((x & 31) << 5) | (z & 31)], 1024))
            if len(rows) != 1 or tuple(int(rows[0][1][k]) for k in ["x", "z"]) != (x, z):
                raise RuntimeError("Saved column coordinate mismatch")
            for y in range(low, high):
                path = world / "region3d" / f"{x >> 4}.{y >> 4}.{z >> 4}.3dr"
                index = ((x & 15) << 8) | ((y & 15) << 4) | (z & 15)
                rows = list(reader(path, [index], 4096))
                if len(rows) != 1:
                    raise RuntimeError("Missing sampled cube")
                _, cube, record = rows[0]
                if tuple(int(cube[k]) for k in ["x", "y", "z"]) != (x, y, z) or not all(
                    bool(cube.get(k))
                    for k in ["initLightDone", "isSurfaceTracked", "fullyPopulated"]
                ):
                    raise RuntimeError("Saved cube coordinates or LIGHT flags differ")
                witnesses.append({"cube": [x, y, z], "payloadSha256": record["payloadSha256"]})
        result = {
            "passed": True,
            "fullMap": bool(checkpoint["complete"]),
            "inputsSha256": plan["inputsSha256"],
            "savedTargetColumns": counts["column"],
            "savedTargetCubes": counts["cube"],
            "sampledCubeNbt": len(witnesses),
            "worldFileBytes": sum(p.stat().st_size for p in world.rglob("*") if p.is_file()),
            "scope": "Complete target region-slot coverage and sampled NBT coordinates/LIGHT flags. Full payload validation, terrain correctness, complete DH data and client visual acceptance are separate.",
            "headers": headers,
            "witnesses": witnesses,
        }
        output = directory / ("saved-coverage-" + uuid.uuid4().hex[:8] + ".json")
        lab_files.write_json(output, result)
        print(
            json.dumps(
                {
                    key: value
                    for key, value in result.items()
                    if key not in ["headers", "witnesses"]
                },
                indent=2,
            )
        )
        print(output, flush=True)
        return result


def tree_bytes(path):
    return sum(p.stat().st_size for p in path.rglob("*") if p.is_file()) if path.exists() else 0


def report(args):
    """The pregenerated world's size by kind and the job's throughput, from the stopped instance."""
    directory, manifest = load(args.name)
    plan = manifest["pregeneration"]
    server = Path(manifest["server"])
    world = server / "world"
    checkpoint = runtime.load_json(journal_directory(manifest) / "checkpoint.json")
    dh = [p for p in server.rglob("*.sqlite*") if p.is_file()]
    dh_bytes = sum(p.stat().st_size for p in dh)
    cubes, columns = tree_bytes(world / "region3d"), tree_bytes(world / "region2d")
    in_world = sum(p.stat().st_size for p in dh if world in p.parents)
    sizes = {
        "cubes (region3d)": cubes,
        "columns (region2d)": columns,
        "distantHorizons": dh_bytes,
        "other world files": tree_bytes(world) - cubes - columns - in_world,
    }
    sizes["total"] = sum(sizes.values())
    batches = []
    for path in sorted(journal_directory(manifest).glob("batch-*.json.gz")):
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            batches.append(json.load(stream))
    seconds = sum(b["elapsedNanos"] for b in batches) / 1e9
    done = sum(b["endColumn"] - b["firstColumn"] for b in batches)
    saved_cubes = checkpoint["committedColumns"] * plan["cubesPerColumn"]
    result = {
        "map": plan["id"],
        "complete": checkpoint["complete"],
        "committedColumns": checkpoint["committedColumns"],
        "targetColumns": plan["totalColumns"],
        "cubes": saved_cubes,
        "bytes": sizes,
        "cubeBytesPerCube": round(cubes / max(1, saved_cubes), 1),
        "distantHorizonsFiles": [str(p.relative_to(server)) for p in dh],
        "batchSeconds": round(seconds, 1),
        "columnsPerSecond": round(done / seconds, 2) if seconds else None,
        "cubesPerSecond": round(done * plan["cubesPerColumn"] / seconds, 1) if seconds else None,
        "generationSeconds": round(sum(b["generationNanos"] for b in batches) / 1e9, 1),
        "saveSeconds": round(sum(b["saveNanos"] for b in batches) / 1e9, 1),
        "dhWaitTicks": max((b.get("dhWaitTicks", 0) for b in batches), default=0),
        "scope": "Files on disk in the stopped instance; batch seconds exclude server start and the final DH drain.",
    }
    output = directory / ("pregeneration-report-" + uuid.uuid4().hex[:8] + ".json")
    lab_files.write_json(output, result)
    print(json.dumps(result, indent=2))
    print(output, flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("command", choices=["prepare", "run", "status", "verify", "report"])
    parser.add_argument("--name", required=True)
    parser.add_argument("--map", choices=sorted(MAPS), default="map-10k")
    parser.add_argument("--port", type=int, default=25970)
    parser.add_argument(
        "--workers", type=int, default=16, help="world-8k: Cubature generation workers (1..32)"
    )
    parser.add_argument(
        "--ahead", type=int, default=24, help="world-8k: columns scheduled ahead (0..64)"
    )
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--lab-artifact", type=Path)
    parser.add_argument(
        "--cubic-artifact", type=Path, help="a Cubature JAR in place of the pinned one"
    )
    parser.add_argument("--max-columns", type=int, default=1562500)
    parser.add_argument("--jfr", action="store_true")
    parser.add_argument("--allow-partial", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.max_columns <= 1562500:
        parser.error("--max-columns must be 1..1562500")
    if not 1 <= args.workers <= 32 or not 0 <= args.ahead <= 64:
        parser.error("--workers must be 1..32 and --ahead 0..64")
    if args.command == "prepare":
        prepare(args)
    elif args.command == "run":
        raise SystemExit(run(args))
    elif args.command == "verify":
        verify(args)
    elif args.command == "report":
        report(args)
    else:
        status(args)


if __name__ == "__main__":
    main()
