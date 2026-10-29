"""Run the existing native lab observers in a fresh minimal Riven/DH instance."""

import argparse
import json
import shutil
from pathlib import Path
from types import SimpleNamespace

import runtime

ROOT = runtime.ROOT
lab = runtime.lab
import lab_profiles
import lab_workload


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
    import sys

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
    if {key: lab.sha(path) for key, path in files.items()} != hashes["files"]:
        raise RuntimeError("Prepared inputs changed before the snapshot")
    records = []
    for key, original in files.items():
        retained = evidence / "prepared-files" / key
        retained.parent.mkdir(parents=True, exist_ok=True)
        if retained.exists():
            raise RuntimeError("Refusing to replace a retained input: " + str(retained))
        shutil.copy2(original, retained)
        if lab.sha(retained) != hashes["files"][key]:
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
        if lab.sha(original) != digest:
            raise RuntimeError("Capture controller changed before launch: " + key)
        retained.parent.mkdir(parents=True, exist_ok=True)
        if retained.exists():
            raise RuntimeError("Refusing to replace retained controller: " + key)
        shutil.copy2(original, retained)
        if lab.sha(retained) != digest:
            raise RuntimeError("Controller snapshot mismatch: " + key)
        controllers.append(
            {
                "file": retained.relative_to(evidence).as_posix(),
                "sha256": digest,
                "bytes": retained.stat().st_size,
            }
        )
    lab.write_json(evidence / "prepared-inputs.json", manifest)
    return {
        "schema": 2,
        "manifest": "prepared-inputs.json",
        "sha256": lab.sha(evidence / "prepared-inputs.json"),
        "files": records,
        "controllerSources": controllers,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument(
        "--cubic-artifact",
        type=Path,
        help="Frozen Cubature candidate; defaults to the committed runtime pin",
    )
    parser.add_argument("--port", type=int, default=25940)
    parser.add_argument(
        "--workload", type=Path, default=ROOT / "dev/benchmarks/terrain-pipeline.json"
    )
    parser.add_argument(
        "--lab-artifact",
        type=Path,
        default=ROOT
        / "dev/CubicChunks/lab/build/cleanroom/libs/cubicchunks-lab-0.1.0-cleanroom.jar",
    )
    parser.add_argument(
        "--jfr",
        action="store_true",
        help="Diagnostic lane; keep separate from ordinary timing comparisons",
    )
    parser.add_argument(
        "--diagnostic",
        action="store_true",
        help="Trace Riven, Cubature and DH methods on both JVMs, plus packet/render stages",
    )
    parser.add_argument(
        "--render-ready",
        action="store_true",
        help="Require native mesh queues and a deferred frame-command GPU marker",
    )
    parser.add_argument(
        "--storage-diagnostics",
        action="store_true",
        help="Add storage spans; requires --diagnostic",
    )
    parser.add_argument(
        "--lifecycle-diagnostics",
        action="store_true",
        help="Storage spans without JFR or NBT queue-byte accounting; instrumented lifecycle lane",
    )
    parser.add_argument(
        "--delivery-trace",
        action="store_true",
        help="Coordinate/renderer traces and periodic screenshots in lifecycle mode",
    )
    parser.add_argument(
        "--delivery-experiment",
        choices=runtime.DELIVERY_MODES,
        default="baseline",
    )
    parser.add_argument(
        "--cube-packet-cubes", type=int, default=1024, choices=range(1, 1025), metavar="1..1024"
    )
    parser.add_argument(
        "--cube-packet-bytes",
        type=int,
        default=0,
        help="Soft whole-cube packet byte target; zero disables",
    )
    parser.add_argument("--dh-workers", type=int, default=4)
    parser.add_argument("--dh-ignore-server-health", action="store_true")
    parser.add_argument("--dh-native-feed", choices=["all", "edits"], default="all")
    args = parser.parse_args(argv)
    if not 0 <= args.cube_packet_bytes <= 2147483647:
        parser.error("Packet byte target must be 0..2147483647")
    if args.lifecycle_diagnostics:
        args.storage_diagnostics = True
    if args.delivery_trace and not args.lifecycle_diagnostics:
        parser.error("--delivery-trace requires --lifecycle-diagnostics")
    if args.storage_diagnostics and not (args.diagnostic or args.lifecycle_diagnostics):
        parser.error("--storage-diagnostics requires --diagnostic")
    args.jfr = args.jfr or args.diagnostic
    workload = lab_workload.validate(json.loads(args.workload.read_text(encoding="utf-8")))
    if workload["seed"] != 8675309:
        parser.error("The minimal Riven preparer currently fixes seed 8675309")
    if not args.lab_artifact.is_file() or not args.artifact.is_file():
        parser.error("Build or select the exact Riven and observer artifacts first")
    from observability.profiles import packet_policy_capabilities

    selected_cubature = (
        args.cubic_artifact or ROOT / runtime.load_json(ROOT / "dev/cc.lock.json")["artifact"]
    )
    packet_capabilities = packet_policy_capabilities(selected_cubature)
    if (
        not packet_capabilities["configurableCubeCount"]
        and args.cube_packet_cubes != packet_capabilities["knownFixedCubeCap"]
    ):
        parser.error("This Cubature artifact does not expose the requested cube-count control")
    with lab.native_measurement_lease(ROOT, args.name):
        runtime.prepare(
            args.name,
            args.port,
            selected_artifact=args.artifact.resolve(),
            cubic_artifact=args.cubic_artifact.resolve() if args.cubic_artifact else None,
            spectator=True,
            dh_workers=args.dh_workers,
            dh_ignore_server_health=args.dh_ignore_server_health,
            dh_native_feed=args.dh_native_feed,
            # The config file selects the delivery mode; Cubature reads it as the server starts.
            cubature_delivery=args.delivery_experiment,
        )
        directory = runtime.target(args.name)
        manifest = runtime.load_json(directory / "standalone.json")
        policy = SimpleNamespace(
            delivery_mode=args.delivery_experiment.upper(),
            cube_send_ms=0,
            cube_send_bytes=0,
            cube_packet_bytes=args.cube_packet_bytes,
            cube_packet_cubes=args.cube_packet_cubes,
        )
        for side in ["server", "client"]:
            configured = lab_profiles.configure_cube_experiments(Path(manifest[side]), policy)
        manifest["cubeExperiments"] = configured
        manifest["packetPolicyCapabilities"] = packet_capabilities
        for side in ["server", "client"]:
            home = Path(manifest[side])
            shutil.copy2(args.lab_artifact, home / "mods/cubicchunks-lab.jar")
            manifest["modHashes"][side]["cubicchunks-lab.jar"] = lab.sha(args.lab_artifact)
            identity = lab_workload.install(workload, home)
        manifest["benchmarkWorkload"] = identity
        manifest["labJar"] = {
            "source": str(args.lab_artifact.resolve()),
            "sha256": lab.sha(args.lab_artifact),
            "reason": "Optional measurement observer only; identical binary on both variants",
        }
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
            "Measured minimal Riven/DH pipeline; optional observer is outside the normal runtime profile"
        )
        trace_settings = None
        if args.lifecycle_diagnostics:
            manifest["observability"] = {
                "mode": "lifecycle-diagnostic",
                "scope": (
                    "Instrumented storage/purge spans; "
                    + ("startup JFR sampling enabled" if args.jfr else "no JFR")
                    + "; no NBT queue-byte accounting, production gain or presentation acceptance."
                ),
            }
        if args.diagnostic:
            from observability.profiles import configuration, write_profile

            trace_settings = directory / "pipeline-settings.jfc"
            write_profile(trace_settings)
            manifest["jfrMaxSizeMiB"] = configuration()["recordingMaxSizeMiB"]
            manifest["observability"] = {
                "configuration": configuration(),
                "jfrSettingsSha256": lab.sha(trace_settings),
                "mode": "diagnostic",
                "scope": "Instrumentation changes costs; no optimization acceptance.",
            }
        lab.write_json(directory / "standalone.json", manifest)
        runtime.load(args.name)
        lab_workload.verify(manifest)
        if (Path(manifest["server"]) / "world/level.dat").exists():
            raise RuntimeError("This measurement requires a fresh world")

        def frozen_inputs(value):
            runtime.load(args.name)
            lab_profiles.validate_cube_experiments(value)
            return {
                "mods": value["modHashes"],
                "files": {key: lab.sha(path) for key, path in input_files(value).items()},
                "controllerSources": {
                    key: lab.sha(path) for key, path in controller_sources().items()
                },
                "workloadSha256": value["benchmarkWorkload"]["sha256"],
            }

        def validate_pose(value, server, client):
            lab_workload.validate_capture(value, server, client)
            expected: dict[str, int] = {
                key: int(val)
                for key, val in value["cubeExperiments"].items()
                if key != "deliveryMode"
            }
            if not packet_capabilities["configurableCubeCount"]:
                # Gson omits the observer's unavailable, null-valued entry.
                expected.pop("maxCubePacketCubes", None)
            if server.get("packetPolicy") != expected:
                raise RuntimeError(
                    "Observed server packet policy differs from the frozen declaration"
                )
            for captured in [server, client]:
                counters = captured.get("threadCosts", {})
                if (
                    not counters.get("enabled")
                    or not counters.get("threads")
                    or any(not row.get("available") for row in counters["threads"])
                ):
                    raise RuntimeError("DH worker CPU/allocation counters were unavailable")
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
        # Adapt only the minimal manifest/preparation boundary. The reviewed runner
        # retains native lifecycle, real-client, GUI, JVM and workload validation.
        return lab.run(
            run_args,
            load=lambda unused: (directory, manifest),
            prime_lab=lambda unused: None,
            select=lambda unused, value: value["benchmarkWorkload"]["value"]["scenarios"]["write"],
            record=frozen_inputs,
            retain=retain_inputs,
            validate=validate_pose,
        )


if __name__ == "__main__":
    raise SystemExit(main())
