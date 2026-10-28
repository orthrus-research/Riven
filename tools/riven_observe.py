"""Debug and observe the owned Riven/Cubature/official-DH runtime."""

import argparse
import json
import sys
from pathlib import Path

from observability.profiles import ROOT, doctor


def save(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def artifact_args(parser):
    parser.add_argument("--riven", type=Path, required=True, help="Frozen Riven Cleanroom JAR")
    parser.add_argument(
        "--cubature", type=Path, help="Frozen candidate; otherwise the accepted pin"
    )
    parser.add_argument(
        "--observer", type=Path, help="Frozen lab observer; use identical bytes across comparisons"
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("doctor", help="Read-only input and instrumentation preflight")
    artifact_args(check)
    check.add_argument("--output", type=Path, help="New JSON record; does not overwrite")
    capture = commands.add_parser("capture", help="Run a fresh owned native client/server capture")
    artifact_args(capture)
    capture.add_argument("--name", required=True)
    capture.add_argument(
        "--mode", choices=["timing", "diagnostic", "lifecycle"], default="diagnostic"
    )
    capture.add_argument(
        "--workload", type=Path, default=ROOT / "dev/benchmarks/terrain-pipeline.json"
    )
    capture.add_argument("--port", type=int, default=25940)
    capture.add_argument("--render-ready", action="store_true")
    capture.add_argument(
        "--storage", action="store_true", help="Include storage spans in diagnostic mode"
    )
    capture.add_argument(
        "--delivery-trace",
        action="store_true",
        help="Bounded coordinate/render-selection traces and periodic screenshots; diagnostic overhead",
    )
    capture.add_argument(
        "--delivery-experiment",
        choices=["baseline", "maintenance", "save", "prefetch"],
        default="baseline",
    )
    capture.add_argument(
        "--cube-packet-cubes", type=int, default=1024, choices=range(1, 1025), metavar="1..1024"
    )
    capture.add_argument("--cube-packet-bytes", type=int, default=0)
    capture.add_argument(
        "--dh-workers", type=int, default=4, help="Shared DH worker budget per JVM (1..16)"
    )
    capture.add_argument("--dh-native-feed", choices=["all", "edits"], default="all")
    capture.add_argument(
        "--dh-ignore-server-health",
        action="store_true",
        help="Turn off DH's limitIfServerUnhealthy world-generation pause",
    )
    for name, description in [
        ("inspect", "Reconcile raw samples, inputs, failures and native captures"),
        ("trace", "Attribute bounded JFR windows and export a timeline"),
    ]:
        command = commands.add_parser(name, help=description)
        command.add_argument("run", type=Path)
        command.add_argument(
            "--output", type=Path, required=True, help="New local evidence directory"
        )
        command.add_argument("--no-charts", action="store_true")
    compare = commands.add_parser(
        "compare", help="Compare exact controls and declared source treatment"
    )
    compare.add_argument("--baseline", type=Path, action="append", required=True)
    compare.add_argument("--candidate", type=Path, action="append", required=True)
    compare.add_argument(
        "--treatment", choices=["cubature", "riven", "packet-cubes", "none"], required=True
    )
    compare.add_argument("--output", type=Path, required=True)
    compare.add_argument("--no-charts", action="store_true")
    compare.add_argument(
        "--diagnostic-screen",
        action="store_true",
        help="Explicitly compare matched instrumented screens; never optimization acceptance",
    )
    args = parser.parse_args(argv)
    if args.command in ("doctor", "capture"):
        result = doctor(args.riven, args.cubature, args.observer)
        if args.command == "doctor":
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                save(args.output, result)
            print(json.dumps(result, indent=2))
            return 0 if result["passed"] else 2
        if not result["passed"]:
            print(json.dumps(result, indent=2))
            raise ValueError("Input preflight failed; no native instance was allocated")
        if args.storage and args.mode not in ("diagnostic", "lifecycle"):
            raise ValueError("--storage requires diagnostic mode")
        import measure_pipeline

        options = [
            "--name",
            args.name,
            "--artifact",
            str(args.riven),
            "--port",
            str(args.port),
            "--workload",
            str(args.workload),
            "--cube-packet-cubes",
            str(args.cube_packet_cubes),
            "--cube-packet-bytes",
            str(args.cube_packet_bytes),
        ]
        if args.cubature:
            options += ["--cubic-artifact", str(args.cubature)]
        if args.observer:
            options += ["--lab-artifact", str(args.observer)]
        if args.mode == "diagnostic":
            options.append("--diagnostic")
        if args.mode == "lifecycle":
            options.append("--lifecycle-diagnostics")
        if args.render_ready:
            options.append("--render-ready")
        if args.delivery_trace:
            options.append("--delivery-trace")
        options += ["--delivery-experiment", args.delivery_experiment]
        options += ["--dh-workers", str(args.dh_workers)]
        options += ["--dh-native-feed", args.dh_native_feed]
        if args.dh_ignore_server_health:
            options.append("--dh-ignore-server-health")
        if args.storage:
            options.append("--storage-diagnostics")
        try:
            return measure_pipeline.main(options)
        finally:
            print(
                "Native evidence: "
                + str(ROOT / ".local/cubic-standalone" / args.name / "runs/write")
            )
    from observability.evidence import compare_runs, inspect_run, public_summary

    if args.output.exists():
        raise ValueError("Output already exists; select a new evidence directory")
    if args.command == "compare":
        before = [inspect_run(path) for path in args.baseline]
        after = [inspect_run(path) for path in args.candidate]
        result = compare_runs(
            before, after, args.treatment, diagnostic_screen=args.diagnostic_screen
        )
        args.output.mkdir(parents=True)
        save(args.output / "comparison.json", result)
        if not args.no_charts:
            from observability.charts import comparison_charts

            comparison_charts(result, args.output)
    else:
        report = inspect_run(args.run)
        args.output.mkdir(parents=True)
        save(args.output / "inspection.json", public_summary(report))
        if not args.no_charts:
            from observability.charts import inspect_charts

            inspect_charts(report, args.output)
        if args.command == "trace":
            from observability.trace import trace_run

            result = trace_run(report, args.output)
            if not args.no_charts:
                from observability.charts import trace_charts

                trace_charts(result, args.output)
        else:
            result = public_summary(report)
    print(
        json.dumps(
            {
                "output": str(args.output.resolve()),
                "errors": result.get("errors", []),
                "warnings": result.get("warnings", []),
                "scope": result.get(
                    "scope", "Diagnostic evidence; inspect the retained JSON and charts."
                ),
            },
            indent=2,
        )
    )
    return 2 if result.get("errors") else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, KeyError, RuntimeError) as error:
        print("Observation failed: " + str(error), file=sys.stderr)
        raise SystemExit(2)
