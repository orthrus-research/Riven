"""Measure the terrain pipeline with the lab's observers: a Workbench driver session of Riven's launch.

Workbench builds the instance from the launch file, holds the GPU lease and quiet, and runs this driver
(`wb play --driver pipeline`). The measurement works on a fresh copy of the instance in the session folder: the
observer from the Store (built at the launch's Cubature commit), the workload and the packet policy are added there,
the lab's record (lab.json) and every result (runs/write) are kept beside session.json, and the lab's runner
(Cubature's cubic_lab.run) starts the server and client from the instance's receipt. Options the launch's fixed
command cannot carry go in LAB_DRIVER_ARGS, split like a shell line (quotes group, backslashes are kept).
"""

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import sys
import zipfile
from pathlib import Path
from types import SimpleNamespace

import cubature_tools
from observability import telemetry
from observability.profiles import sha

import cubic_lab as lab
import lab_files
import lab_profiles
import lab_workload

ROOT = cubature_tools.ROOT

DRIVER_ARGS = "LAB_DRIVER_ARGS"
# Cubature's delivery modes in stage order, as command lines spell them.
DELIVERY_MODES = tuple(mode.lower() for mode in lab_profiles.DELIVERY_MODES)
# The lab's record of the session's instance, beside session.json (Cubature's report tools read it too).
RECORD = "lab.json"


def split(text):
    """A shell-like line: whitespace separates, quotes group, backslashes stay (Windows paths)."""
    lexer = shlex.shlex(text, posix=True)
    lexer.whitespace_split = True
    lexer.escape = ""
    return list(lexer)


def driver_args(argv=None):
    """The arguments given, else this process's command line with the person's further options from
    LAB_DRIVER_ARGS after it."""
    if argv is not None:
        return list(argv)
    return sys.argv[1:] + split(os.environ.get(DRIVER_ARGS, ""))


def session():
    """The driver session this process runs in (Cubature's lab_session at the launch's commit)."""
    import lab_session

    return lab_session.Session.from_environment()


def level_dat(manifest):
    """The server world's level.dat, by the instance's level-name."""
    server = Path(manifest["server"])
    names = re.findall(
        r"^level-name=(.*)$", (server / "server.properties").read_text(encoding="utf-8"), re.M
    )
    return server / (names[-1].strip() if names else "world") / "level.dat"


def input_files(manifest):
    """Files owned by this minimal preparer, before either JVM starts."""
    files = {}
    for side in ["server", "client"]:
        home = Path(manifest[side])
        paths = sorted((home / "config").rglob("*"))
        required = (
            [home / "server.properties", home / "user_jvm_args.txt"]
            if side == "server"
            else [home / "options.txt"]
        )
        for path in required:
            if not path.is_file():
                raise RuntimeError("Prepared input is missing: " + str(path))
        paths += required
        for path in paths:
            if path.is_file():
                files[side + "/" + path.relative_to(home).as_posix()] = path
    return files


def controller_sources():
    """Freeze the imported workspace Python modules that prepare and run this capture."""
    paths = {Path(__file__).resolve(), ROOT / "tools/riven_observe.py"}
    for module in list(sys.modules.values()):
        filename = getattr(module, "__file__", None)
        if filename:
            path = Path(filename).resolve()
            if path.suffix == ".py" and path.is_relative_to(ROOT) and ".local" not in path.parts:
                paths.add(path)
    return {path.relative_to(ROOT).as_posix(): path for path in sorted(paths)}


def retain_inputs(manifest, hashes, evidence):
    """Keep exact prepared bytes; hashes alone cannot recover rewritten configs."""
    files = input_files(manifest)
    if {key: sha(path) for key, path in files.items()} != hashes["files"]:
        raise RuntimeError("Prepared inputs changed before the snapshot")
    records = []
    for key, original in files.items():
        retained = evidence / "prepared-files" / key
        retained.parent.mkdir(parents=True, exist_ok=True)
        if retained.exists():
            raise RuntimeError("Refusing to replace a retained input: " + str(retained))
        shutil.copy2(original, retained)
        if sha(retained) != hashes["files"][key]:
            raise RuntimeError("Input snapshot hash mismatch: " + key)
        records.append(
            {
                "original": str(original),
                "file": retained.relative_to(evidence).as_posix(),
                "sha256": hashes["files"][key],
                "bytes": retained.stat().st_size,
            }
        )
    controllers = []
    for key, digest in hashes.get("controllerSources", {}).items():
        original, retained = ROOT / key, evidence / "controller-sources" / key
        if sha(original) != digest:
            raise RuntimeError("Capture controller changed before launch: " + key)
        retained.parent.mkdir(parents=True, exist_ok=True)
        if retained.exists():
            raise RuntimeError("Refusing to replace retained controller: " + key)
        shutil.copy2(original, retained)
        if sha(retained) != digest:
            raise RuntimeError("Controller snapshot mismatch: " + key)
        controllers.append(
            {
                "file": retained.relative_to(evidence).as_posix(),
                "sha256": digest,
                "bytes": retained.stat().st_size,
            }
        )
    lab_files.write_json(evidence / "prepared-inputs.json", manifest)
    return {
        "schema": 2,
        "manifest": "prepared-inputs.json",
        "sha256": sha(evidence / "prepared-inputs.json"),
        "files": records,
        "controllerSources": controllers,
    }


def riven_manifest(current):
    """The lab's record of the session's instance with what Riven's preparation records beside it: the Riven and
    Cubature files the launch placed, the generator lab.build's configure step recorded and the delivery mode."""
    manifest = current.manifest()
    for field, name in (("sourceArtifact", "riven"), ("sourceCubicArtifact", "cubature")):
        member = current.member(name)
        manifest[field] = {
            "path": str(member["placed"][0]),
            "file": member.get("file"),
            "sha256": member["sha256"],
            "member": member["member"],
        }
    configured = (current.receipt.get("configure") or {}).get("record") or {}
    manifest["generator"] = {k: v for k, v in configured.items() if k != "cubatureDelivery"}
    manifest["cubatureDelivery"] = configured.get("cubatureDelivery")
    manifest["runtimeProfile"] = "cleanroom"
    return manifest


def packaged_definitions(jar):
    """The riven-*.json definitions a Riven artifact packages, by name, as SHA-256 digests."""
    with zipfile.ZipFile(jar) as archive:
        return {
            entry: hashlib.sha256(archive.read(entry)).hexdigest()
            for entry in archive.namelist()
            if re.fullmatch(r"riven-[^/]+\.json", entry)
        }


def adopt_world(manifest, source):
    """Starts the copy from a full copy of another stopped instance's world (its region files, level data and
    Riven's recorded definitions), so a run starts on terrain already generated. The source is only read. Both
    instances' Riven must package the same definitions (every riven-*.json): a code change between them is the run's
    treatment."""
    source = Path(source).resolve()
    jars = [source / "server/mods/riven.jar", Path(manifest["server"]) / "mods/riven.jar"]
    if packaged_definitions(jars[0]) != packaged_definitions(jars[1]):
        raise RuntimeError("The source world was generated with different Riven definitions")
    world = level_dat(manifest).parent
    copied = level_dat({"server": source / "server"}).parent
    if not (copied / "level.dat").is_file():
        raise RuntimeError("The source instance has no generated world: " + str(source))
    if world.exists():
        raise RuntimeError("The copy already has a world")
    shutil.copytree(copied, world, ignore=shutil.ignore_patterns("session.lock"))
    size = sum(p.stat().st_size for p in world.rglob("*") if p.is_file())
    manifest["worldFrom"] = {
        "instance": str(source),
        "bytes": size,
        "levelSha256": sha(world / "level.dat"),
        "sourceRivenSha256": sha(jars[0]),
        "scope": "A full copy taken before the first launch; the source world is unchanged.",
    }
    print(f"Copied {size / 1e9:.1f} GB of world from {source}", flush=True)


def set_cubature_option(home, kind, name, value):
    """One line of Cubature's general config section, replaced or added."""
    path = Path(home) / "config/cubicchunks.cfg"
    text = path.read_text(encoding="utf-8")
    line = f"    {kind}:{name}={value}"
    pattern = rf"^\s*{kind}:{name}=.*$"
    if re.search(pattern, text, flags=re.M):
        text = re.sub(pattern, line, text, flags=re.M)
    else:
        text, count = re.subn(r"general\s*\{", "general {\n" + line, text, count=1)
        if count != 1:
            raise RuntimeError("Expected one CubicChunks general config section: " + str(path))
    path.write_text(text, encoding="utf-8")


def add_observer(manifest, observer, reason):
    """The lab observer in both sides' mods, recorded with the mod hashes."""
    for side in ["server", "client"]:
        shutil.copy2(observer, Path(manifest[side]) / "mods/cubicchunks-lab.jar")
        manifest["modHashes"][side]["cubicchunks-lab.jar"] = sha(observer)
    manifest["labJar"] = {
        "source": str(Path(observer).resolve()),
        "sha256": sha(observer),
        "reason": reason,
    }


def check_mods(manifest):
    """The copy's mods are the ones recorded: the launch's members and the observer, by SHA-256."""
    for side in ["server", "client"]:
        actual = {p.name: sha(p) for p in (Path(manifest[side]) / "mods").glob("*.jar")}
        if actual != manifest["modHashes"][side]:
            raise RuntimeError("Prepared mod set changed: " + side)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--workload", type=Path, default=ROOT / "dev/benchmarks/terrain-pipeline.json")
    p.add_argument(
        "--lab-artifact",
        type=Path,
        help="The lab observer; default: the Store's, built at the launch's Cubature commit",
    )
    p.add_argument(
        "--jfr",
        action="store_true",
        help="Diagnostic lane; keep separate from ordinary timing comparisons",
    )
    p.add_argument(
        "--diagnostic",
        action="store_true",
        help="Trace Riven and Cubature methods on both JVMs, plus packet/render stages",
    )
    p.add_argument(
        "--render-ready",
        action="store_true",
        help="Require native mesh queues and a deferred frame-command GPU marker",
    )
    p.add_argument(
        "--storage-diagnostics",
        action="store_true",
        help="Add storage spans; requires --diagnostic",
    )
    p.add_argument(
        "--lifecycle-diagnostics",
        action="store_true",
        help="Storage spans without JFR or NBT queue-byte accounting; instrumented lifecycle lane",
    )
    p.add_argument(
        "--delivery-trace",
        action="store_true",
        help="Coordinate/renderer traces and periodic screenshots in lifecycle mode",
    )
    p.add_argument("--delivery-experiment", choices=DELIVERY_MODES, default="baseline")
    p.add_argument(
        "--cube-packet-cubes", type=int, default=1024, choices=range(1, 1025), metavar="1..1024"
    )
    p.add_argument(
        "--cube-packet-bytes",
        type=int,
        default=0,
        help="Soft whole-cube packet byte target; zero disables",
    )
    p.add_argument(
        "--preview-threads",
        type=int,
        choices=range(-1, 17),
        metavar="-1..16",
        help="Cubature previewThreads; unset keeps the instance's",
    )
    p.add_argument(
        "--world-from",
        type=Path,
        help="start from a full copy of this stopped instance's world (a pregenerated instance's folder)",
    )
    return p


def prepare(current, args, workload):
    """The lab's record of a fresh copy of the session's instance, set up for this measurement: the packet
    policy and delivery mode, the observer, the workload and the lab's JVM properties."""
    observer = args.lab_artifact or cubature_tools.lab_observer(current.launch)
    if not Path(observer).is_file():
        raise ValueError("The lab observer does not exist: " + str(observer))
    from observability.profiles import packet_policy_capabilities

    manifest = riven_manifest(current)
    if manifest.get("seed") != workload["seed"]:
        raise ValueError(
            f"The workload's seed {workload['seed']} is not the instance's {manifest.get('seed')}"
        )
    capabilities = packet_policy_capabilities(manifest["sourceCubicArtifact"]["path"])
    if (
        not capabilities["configurableCubeCount"]
        and args.cube_packet_cubes != capabilities["knownFixedCubeCap"]
    ):
        raise ValueError("This Cubature artifact does not expose the requested cube-count control")
    if args.world_from:
        adopt_world(manifest, args.world_from)
    policy = SimpleNamespace(
        delivery_mode=args.delivery_experiment.upper(),
        cube_send_ms=0,
        cube_send_bytes=0,
        cube_packet_bytes=args.cube_packet_bytes,
        cube_packet_cubes=args.cube_packet_cubes,
    )
    configured = {}
    for side in ["server", "client"]:
        home = Path(manifest[side])
        # The config file selects the delivery mode; Cubature reads it as the server starts.
        configured = lab_profiles.configure_cube_experiments(home, policy)
        if args.preview_threads is not None:
            set_cubature_option(home, "I", "previewThreads", args.preview_threads)
    manifest["cubatureDelivery"] = policy.delivery_mode
    manifest["cubeExperiments"] = configured
    manifest["packetPolicyCapabilities"] = capabilities
    add_observer(
        manifest, observer, "Optional measurement observer only; identical binary on both variants"
    )
    identity = None
    for side in ["server", "client"]:
        identity = lab_workload.install(workload, manifest[side])
    manifest["benchmarkWorkload"] = identity
    manifest["labProperties"] = {
        "cubiclab.workload": lab_workload.RELATIVE_PATH,
        "cubiclab.tickTimer": "complete-v1",
        "cubiclab.diagnostics": str(args.diagnostic or args.delivery_trace).lower(),
        "cubiclab.deliveryTrace": str(args.delivery_trace).lower(),
        "cubiclab.storageDiagnostics": str(args.storage_diagnostics).lower(),
        "cubiclab.storageQueueAccounting": str(not args.lifecycle_diagnostics).lower(),
        "cubiclab.storageTraceCapacity": "500000" if args.lifecycle_diagnostics else "120000",
        "cubiclab.renderDiagnostics": str(args.diagnostic).lower(),
        "cubiclab.fullViewRenderReady": str(args.render_ready).lower(),
        "cubiclab.threadCosts": "true",
    }
    if not any(m["name"] == "quadrature" for m in current.launch["members"]):
        # Cubature's client stops at loading without Quadrature, which draws cubic worlds. A
        # capture without it times the game's own renderer, which then draws heights 0 to 255.
        manifest["labProperties"]["cubature.clientWithoutRenderer"] = "true"
    # The full-view observer reads JVM properties, independently of the
    # workload file. Supply both and check the observed pose below.
    manifest["labProperties"].update(
        {
            "cubiclab.view" + key: str(value)
            for key, value in zip(["X", "Y", "Z", "Yaw", "Pitch"], workload["camera"])
        }
    )
    manifest["labProperties"]["cubiclab.fullViewTimeoutSeconds"] = str(
        workload.get("timeoutSeconds", 300)
    )
    manifest["purpose"] = (
        "Measured minimal Riven pipeline; optional observer is outside the normal runtime profile"
    )
    if args.lifecycle_diagnostics:
        manifest["observability"] = {
            "mode": "lifecycle-diagnostic",
            "scope": (
                "Instrumented storage/purge spans; "
                + ("startup JFR sampling enabled" if args.jfr else "no JFR")
                + "; no NBT queue-byte accounting, production gain or presentation acceptance."
            ),
        }
    return manifest


def check(manifest):
    """What the copy holds is what its record says, before either JVM starts."""
    check_mods(manifest)
    lab_workload.verify(manifest)
    lab_profiles.validate_view_distance_inputs(manifest)
    lab_profiles.validate_cube_experiments(manifest)
    # A world already present must be the untouched --world-from copy recorded at adoption.
    level, adopted = level_dat(manifest), manifest.get("worldFrom")
    if level.exists() and not (adopted and sha(level) == adopted["levelSha256"]):
        raise RuntimeError(
            "This measurement requires a fresh world or an untouched --world-from copy"
        )


def main(argv=None):
    options = parser()
    args = options.parse_args(driver_args(argv))
    if not 0 <= args.cube_packet_bytes <= 2147483647:
        options.error("Packet byte target must be 0..2147483647")
    if args.lifecycle_diagnostics:
        args.storage_diagnostics = True
    if args.delivery_trace and not args.lifecycle_diagnostics:
        options.error("--delivery-trace requires --lifecycle-diagnostics")
    if args.storage_diagnostics and not (args.diagnostic or args.lifecycle_diagnostics):
        options.error("--storage-diagnostics requires --diagnostic")
    args.jfr = args.jfr or args.diagnostic
    workload = lab_workload.validate(json.loads(args.workload.read_text(encoding="utf-8")))
    current = session().copy()
    directory = current.folder
    try:
        manifest = prepare(current, args, workload)
        capabilities = manifest["packetPolicyCapabilities"]
        trace_settings = None
        if args.diagnostic:
            from observability.profiles import configuration, write_profile

            trace_settings = directory / "pipeline-settings.jfc"
            write_profile(trace_settings)
            manifest["jfrMaxSizeMiB"] = configuration()["recordingMaxSizeMiB"]
            manifest["observability"] = {
                "configuration": configuration(),
                "jfrSettingsSha256": sha(trace_settings),
                "mode": "diagnostic",
                "scope": "Instrumentation changes costs; no optimization acceptance.",
            }
        lab_files.write_json(directory / RECORD, manifest)
        check(manifest)

        def frozen_inputs(value):
            check_mods(value)
            lab_profiles.validate_cube_experiments(value)
            return {
                "mods": value["modHashes"],
                "files": {key: sha(path) for key, path in input_files(value).items()},
                "controllerSources": {key: sha(path) for key, path in controller_sources().items()},
                "workloadSha256": value["benchmarkWorkload"]["sha256"],
            }

        def validate_pose(value, server, client):
            lab_workload.validate_capture(value, server, client)
            expected: dict[str, int] = {
                key: int(val)
                for key, val in value["cubeExperiments"].items()
                if key != "deliveryMode"
            }
            if not capabilities["configurableCubeCount"]:
                # Gson omits the observer's unavailable, null-valued entry.
                expected.pop("maxCubePacketCubes", None)
            if server.get("packetPolicy") != expected:
                raise RuntimeError(
                    "Observed server packet policy differs from the frozen declaration"
                )
            for captured in [server, client]:
                counters = captured.get("threadCosts", {})
                if not counters.get("threads"):
                    continue  # Neither side runs worker threads the observer counts.
                if (
                    not counters.get("enabled")
                    or not counters.get("threads")
                    or any(not row.get("available") for row in counters["threads"])
                ):
                    raise RuntimeError("Worker CPU/allocation counters were unavailable")
            if server["scenario"] == "full-view":
                geometry = client["fullView"]["geometry"]
                actual = [geometry[key] for key in ["x", "y", "z", "yaw", "pitch"]]
                if any(abs(a - b) > 0.001 for a, b in zip(actual, workload["camera"])):
                    raise RuntimeError(
                        "Observed full-view camera differs from the declared workload"
                    )

        run_args = SimpleNamespace(
            phase="write",
            jfr=args.jfr,
            server_jfr_settings=trace_settings,
            client_jfr_settings=trace_settings,
            prepare_decoration_study=False,
            prepare_river_mouth=False,
            scenario_start_timeout=120,
            client_report_timeout=60,
        )
        # Adapt only the preparation boundary. The reviewed runner retains native lifecycle,
        # real-client, GUI, JVM and workload validation.
        return lab.run(
            run_args,
            load=lambda unused: (directory, manifest),
            prime_lab=lambda unused: None,
            select=lambda unused, value: value["benchmarkWorkload"]["value"]["scenarios"]["write"],
            record=frozen_inputs,
            retain=retain_inputs,
            validate=validate_pose,
        )
    finally:
        # Every capture, passed or failed, joins the telemetry ledger.
        telemetry.collect_quietly(directory)


if __name__ == "__main__":
    raise SystemExit(main())
