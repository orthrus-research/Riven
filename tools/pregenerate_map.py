"""A bounded native Riven terrain pregeneration, run as Workbench driver sessions of Riven's launch.

Workbench builds the instance and holds the GPU lease and quiet. A pregeneration works on the instance itself, not a
copy, so each session resumes the journal the last one left: give the map an instance of its own
(`wb play --driver pregenerate --name map-10k`). The first session prepares it, on an instance without a world: the
lab observer from the Store (built at the launch's Cubature commit), for world-8k Cubature's generation workers, and
the plan in the instance's riven-pregeneration.json. Every later session checks the instance against that plan before
the server starts. Each session keeps the lab's record (lab.json) and its run's evidence (runs/) in its folder.

Maps:
  map-10k   the 20000 by 20000 block inspection map, the lab band (Y -128 to 384)
  world-8k  a fully pregenerated 8192 by 8192 block world: every cube from the world floor to its
            top, so a later flight mostly loads; `report` gives the world's size and the job's
            throughput

Commands:
  run      (driver pregenerate) prepare once, then generate up to --max-columns more columns
  verify   (driver pregenerate-verify) count every saved target slot and decode a sample of real NBT
  status   the journal's progress (--instance <folder>, or the session's)
  report   the world's size and the job's throughput (--instance <folder>, or the session's)

Options the launch's fixed driver command cannot carry go in LAB_DRIVER_ARGS (`--map world-8k`).
"""

import argparse
import ctypes
import gzip
import hashlib
import importlib
import json
import struct
import sys
import uuid
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import cubature_tools
from measure_pipeline import (
    add_observer,
    controller_sources,
    driver_args,
    input_files,
    level_dat,
    retain_inputs,
    session,
)
from observability import telemetry
from observability.profiles import sha

import cubic_lab as lab
import lab_files

ROOT = cubature_tools.ROOT

PLAN = "riven-pregeneration.json"
RECORD = "lab.json"
BAND = (-128, 384)
MAPS: dict[str, dict[str, Any]] = {
    "map-10k": {
        "radiusBlocks": 10000,
        "fullHeight": False,
        "purpose": "Owned 20000 by 20000 block Riven inspection map",
        "scope": "Native lit terrain cubes, ordinary save/unload. Visual acceptance remains separate.",
    },
    "world-8k": {
        "radiusBlocks": 4096,
        "fullHeight": True,
        "purpose": "A fully pregenerated 8192 by 8192 block Riven world",
        "scope": "Every native lit cube from the world floor to its top, ordinary save/unload. Visual acceptance remains separate.",
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
    """The plan values a map fixes: bounds, columns and height."""
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
    }


def generation_inputs(manifest):
    return {
        "mods": manifest["modHashes"],
        "java": {"sha256": sha(Path(manifest["java"])), "jvm": manifest["jvmProfile"]},
        "seed": manifest.get("seed"),
        "worldType": "riven",
        "generatorOptions": "",
        "allocatedHeight": allocated_height(),
        "terrainDefinitions": "Packaged in the launch's Riven; no external terrain overrides",
    }


def prepare(current, manifest, args):
    """The first session of a map: the observer and, for world-8k, the generation workers in the instance, and the
    plan beside its receipt."""
    if level_dat(manifest).exists():
        raise RuntimeError(
            "A pregeneration is prepared on an instance without a world: give it one of its own "
            "(wb play --driver pregenerate --name <map>)"
        )
    observer = args.lab_artifact or cubature_tools.lab_observer(current.launch)
    add_observer(
        manifest,
        observer,
        "Optional native terrain pregeneration command; production runtime pins remain unchanged",
    )
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
    plan = {
        "pregeneration": {
            "schema": 3,
            **extent(args.map),
            "generationWorkers": args.workers if profile["fullHeight"] else "Cubature default",
            "inputs": inputs,
            "inputsSha256": digest(inputs),
            "scope": profile["scope"],
        },
        "labProperties": {
            "cubiclab.pregenInputs": digest(inputs),
            "cubiclab.pregenBatchColumns": "128",
            "cubiclab.pregenTickMillis": "1000",
            **(
                {
                    "cubiclab.pregenFullHeight": "true",
                    "cubiclab.pregenAhead": str(args.ahead),
                    "cubiclab.pregenFlushBatches": "4",
                }
                if profile["fullHeight"]
                else {}
            ),
        },
        "labJar": manifest["labJar"],
        "purpose": profile["purpose"],
        "preparedIn": current.id,
    }
    lab_files.write_json(current.instance / PLAN, plan)
    return plan


def check(manifest):
    """The instance is the one its plan was made for: the same mods, Java, JVM profile, seed and extent."""
    plan = manifest.get("pregeneration")
    if not plan or plan["inputs"] != generation_inputs(manifest):
        raise RuntimeError("Prepared generation inputs changed; use a fresh instance")
    if plan.get("schema") != 3 or plan.get("id") not in MAPS:
        raise RuntimeError("Prepared before driver sessions; use a fresh instance")
    expected = extent(plan["id"])
    if any(plan.get(key) != value for key, value in expected.items()):
        raise RuntimeError("Prepared extent differs from the " + plan["id"] + " map")
    if plan["inputsSha256"] != digest(plan["inputs"]):
        raise RuntimeError("Pregeneration input fingerprint is invalid")
    if manifest["labProperties"].get("cubiclab.pregenInputs") != plan["inputsSha256"]:
        raise RuntimeError("Native pregenerator fingerprint differs")
    return manifest


def setup(current, args, prepare_new):
    """The lab's record of the session's instance with its plan, checked; the first session prepares the plan when
    prepare_new allows it. The record is kept in the session folder."""
    manifest = current.manifest()
    path = current.instance / PLAN
    if path.is_file():
        plan = json.loads(path.read_text(encoding="utf-8"))
        if args.map and plan["pregeneration"]["id"] != args.map:
            raise RuntimeError(
                f"This instance holds the {plan['pregeneration']['id']} map, not {args.map}"
            )
    elif prepare_new:
        args.map = args.map or "map-10k"
        plan = prepare(current, manifest, args)
    else:
        raise RuntimeError("This instance holds no pregeneration; run the pregenerate driver first")
    manifest.update(
        {key: plan[key] for key in ("pregeneration", "labProperties", "labJar", "purpose")}
    )
    manifest["modHashes"] = current.mod_hashes()
    check(manifest)
    lab_files.write_json(current.folder / RECORD, manifest)
    return manifest


def stopped(args):
    """The lab's record of a stopped instance's plan, for status and report: --instance, else the session's."""
    folder = Path(args.instance).resolve() if args.instance else session().instance
    path = folder / PLAN
    if not path.is_file():
        raise RuntimeError(f"{folder} holds no pregeneration ({PLAN})")
    plan = json.loads(path.read_text(encoding="utf-8"))
    return folder, {**plan, "server": str(folder / "server")}


def journal_directory(manifest):
    return Path(manifest["server"]) / "cubiclab-pregeneration" / manifest["pregeneration"]["id"]


@contextmanager
def keep_system_awake():
    """Hold a thread-owned sleep request only while this bulk controller runs."""
    if sys.platform != "win32":
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
    current = session()
    try:
        with keep_system_awake():
            manifest = setup(current, args, prepare_new=True)
            plan = manifest["pregeneration"]

            def frozen_inputs(value):
                value["modHashes"] = current.mod_hashes()
                check(value)
                return {
                    "mods": value["modHashes"],
                    "files": {key: sha(path) for key, path in input_files(value).items()},
                    "controllerSources": {
                        key: sha(path) for key, path in controller_sources().items()
                    },
                    "generationInputsSha256": plan["inputsSha256"],
                }

            # Keep native launch, bounded progress waits and orderly save/stop in the
            # maintained lab runner. Only its preparation boundary is adapted.
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
                load=lambda unused: (current.folder, manifest),
                prime_lab=lambda unused: None,
                record=frozen_inputs,
                retain=retain_inputs,
            )
    finally:
        telemetry.collect_quietly(current.folder)


def status(args):
    _, manifest = stopped(args)
    plan = manifest["pregeneration"]
    output = journal_directory(manifest)
    checkpoint = output / "checkpoint.json"
    value = (
        json.loads(checkpoint.read_text(encoding="utf-8"))
        if checkpoint.exists()
        else {"committedColumns": 0}
    )
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
    """Count every saved target slot; decode a deterministic sample of real NBT. A driver session: the lease keeps
    every server off the world while it is read."""
    current = session()
    manifest = setup(current, args, prepare_new=False)
    plan = manifest["pregeneration"]
    checkpoint = json.loads(
        (journal_directory(manifest) / "checkpoint.json").read_text(encoding="utf-8")
    )
    committed = checkpoint["committedColumns"]
    if not 0 < committed <= plan["totalColumns"]:
        raise RuntimeError("No valid saved prefix to inspect")
    if checkpoint["totalColumns"] != plan["totalColumns"] or checkpoint["complete"] != (
        committed == plan["totalColumns"]
    ):
        raise RuntimeError("Checkpoint completion does not match the requested map")
    if not args.allow_partial and not checkpoint["complete"]:
        raise RuntimeError("Full map is incomplete; use --allow-partial only for the pilot")
    world = level_dat(manifest).parent
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
                bool(cube.get(k)) for k in ["initLightDone", "isSurfaceTracked", "fullyPopulated"]
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
        "scope": "Complete target region-slot coverage and sampled NBT coordinates/LIGHT flags. Full payload validation, terrain correctness and client visual acceptance are separate.",
        "headers": headers,
        "witnesses": witnesses,
    }
    output = current.folder / ("saved-coverage-" + uuid.uuid4().hex[:8] + ".json")
    lab_files.write_json(output, result)
    print(
        json.dumps(
            {key: value for key, value in result.items() if key not in ["headers", "witnesses"]},
            indent=2,
        )
    )
    print(output, flush=True)
    return result


def tree_bytes(path):
    return sum(p.stat().st_size for p in path.rglob("*") if p.is_file()) if path.exists() else 0


def report(args):
    """The pregenerated world's size by kind and the job's throughput, from the stopped instance."""
    directory, manifest = stopped(args)
    plan = manifest["pregeneration"]
    world = level_dat(manifest).parent
    checkpoint = json.loads(
        (journal_directory(manifest) / "checkpoint.json").read_text(encoding="utf-8")
    )
    cubes, columns = tree_bytes(world / "region3d"), tree_bytes(world / "region2d")
    sizes = {
        "cubes (region3d)": cubes,
        "columns (region2d)": columns,
        "other world files": tree_bytes(world) - cubes - columns,
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
        "batchSeconds": round(seconds, 1),
        "columnsPerSecond": round(done / seconds, 2) if seconds else None,
        "cubesPerSecond": round(done * plan["cubesPerColumn"] / seconds, 1) if seconds else None,
        "generationSeconds": round(sum(b["generationNanos"] for b in batches) / 1e9, 1),
        "saveSeconds": round(sum(b["saveNanos"] for b in batches) / 1e9, 1),
        "scope": "Files on disk in the stopped instance; batch seconds exclude server start.",
    }
    output = directory / ("pregeneration-report-" + uuid.uuid4().hex[:8] + ".json")
    lab_files.write_json(output, result)
    print(json.dumps(result, indent=2))
    print(output, flush=True)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("command", choices=["run", "status", "verify", "report"])
    parser.add_argument(
        "--map", choices=sorted(MAPS), help="run: the map a new instance is prepared for (map-10k)"
    )
    parser.add_argument("--instance", help="status, report: a pregenerated instance's folder")
    parser.add_argument(
        "--workers", type=int, default=16, help="world-8k: Cubature generation workers (1..32)"
    )
    parser.add_argument(
        "--ahead", type=int, default=24, help="world-8k: columns scheduled ahead (0..64)"
    )
    parser.add_argument(
        "--lab-artifact",
        type=Path,
        help="the lab observer; default: the Store's, built at the launch's Cubature commit",
    )
    parser.add_argument("--max-columns", type=int, default=1562500)
    parser.add_argument("--jfr", action="store_true")
    parser.add_argument("--allow-partial", action="store_true")
    args = parser.parse_args(driver_args(argv))
    if not 1 <= args.max_columns <= 1562500:
        parser.error("--max-columns must be 1..1562500")
    if not 1 <= args.workers <= 32 or not 0 <= args.ahead <= 64:
        parser.error("--workers must be 1..32 and --ahead 0..64")
    if args.command == "run":
        return run(args)
    if args.command == "verify":
        verify(args)
    elif args.command == "report":
        report(args)
    else:
        status(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
