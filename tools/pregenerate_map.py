"""Prepare, resume and inspect a bounded native Riven terrain pregeneration job."""

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

import lab_files
import runtime
from measure_pipeline import controller_sources, input_files, retain_inputs
from observability.profiles import sha

ROOT, lab = runtime.ROOT, runtime.lab


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
    if args.radius != 10000:
        raise ValueError("This retained map definition requires the requested 10000-block radius")
    if not args.artifact or not args.lab_artifact:
        raise ValueError("Select frozen Riven and pregeneration observer artifacts")
    for path in [args.artifact, args.lab_artifact]:
        if not path.is_file():
            raise ValueError("Artifact is missing: " + str(path))
    runtime.prepare(
        args.name,
        args.port,
        selected_artifact=args.artifact.resolve(),
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
    inputs = generation_inputs(manifest)
    manifest["pregeneration"] = {
        "schema": 1,
        "id": "map-10k",
        "radiusBlocks": 10000,
        "blockBounds": {"minimumInclusive": [-10000, -10000], "maximumExclusive": [10000, 10000]},
        "originChunkX": -625,
        "originChunkZ": -625,
        "widthColumns": 1250,
        "heightColumns": 1250,
        "totalColumns": 1562500,
        "targetCubes": 50000000,
        "inputs": inputs,
        "inputsSha256": digest(inputs),
        "scope": "Native lit terrain cubes, ordinary save/unload. Whole-map DH data and visual acceptance remain separate.",
    }
    manifest["labProperties"] = {
        "cubiclab.pregenInputs": digest(inputs),
        "cubiclab.pregenBatchColumns": "128",
        "cubiclab.pregenTickMillis": "1000",
    }
    manifest["labJar"] = {
        "source": str(args.lab_artifact.resolve()),
        "sha256": sha(args.lab_artifact),
        "reason": "Optional native terrain pregeneration command; production runtime pins remain unchanged",
    }
    manifest["purpose"] = "Owned 20000 by 20000 block Riven inspection map"
    lab_files.write_json(directory / "standalone.json", manifest)
    load(args.name)
    print(directory, flush=True)


def load(name):
    directory, manifest = runtime.load(name)
    plan = manifest.get("pregeneration")
    if not plan or plan["inputs"] != generation_inputs(manifest):
        raise RuntimeError("Prepared generation inputs changed; use a fresh world")
    expected = {
        "radiusBlocks": 10000,
        "originChunkX": -625,
        "originChunkZ": -625,
        "widthColumns": 1250,
        "heightColumns": 1250,
        "totalColumns": 1562500,
        "targetCubes": 50000000,
    }
    if any(plan.get(key) != value for key, value in expected.items()):
        raise RuntimeError("Prepared extent differs from the requested 10000-block radius")
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


def status(args):
    _, manifest = load(args.name)
    plan = manifest["pregeneration"]
    output = journal_directory(manifest)
    checkpoint = output / "checkpoint.json"
    value = runtime.load_json(checkpoint) if checkpoint.exists() else {"committedColumns": 0}
    value.update(
        targetColumns=plan["totalColumns"],
        targetCubes=plan["targetCubes"],
        savedTargetCubes=value["committedColumns"] * 32,
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
                        target = -8 <= y < 24 and in_prefix(x, z)
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
        if counts != {"column": committed, "cube": committed * 32}:
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
            for y in range(-8, 24):
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "run", "status", "verify"])
    parser.add_argument("--name", required=True)
    parser.add_argument("--port", type=int, default=25970)
    parser.add_argument("--radius", type=int, default=10000)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--lab-artifact", type=Path)
    parser.add_argument("--max-columns", type=int, default=1562500)
    parser.add_argument("--jfr", action="store_true")
    parser.add_argument("--allow-partial", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.max_columns <= 1562500:
        parser.error("--max-columns must be 1..1562500")
    if args.command == "prepare":
        prepare(args)
    elif args.command == "run":
        raise SystemExit(run(args))
    elif args.command == "verify":
        verify(args)
    else:
        status(args)


if __name__ == "__main__":
    main()
