"""Run a sealed, serial packet-count screen through the existing native observer."""

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from observability.profiles import ROOT, sha


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--start-index", type=int, default=0)
    parser.add_argument("--limit", type=int)
    args = parser.parse_args(argv)
    plan = json.loads(args.plan.read_text())
    if plan["schema"] != 1 or plan["treatment"] != "packet-cubes":
        raise ValueError("Unsupported sealed experiment")
    if args.start_index < 0 or (args.limit is not None and args.limit < 1):
        raise ValueError("Invalid case range")
    journal = args.plan.parent / "journal.jsonl"
    selected = plan["cases"][args.start_index :]
    if args.limit is not None:
        selected = selected[: args.limit]
    for case in selected:
        for name, digest in plan["inputHashes"].items():
            if sha(ROOT / name) != digest:
                raise ValueError("Sealed experiment input changed: " + name)
        output = ROOT / ".local/cubic-standalone" / case["name"]
        if output.exists():
            raise ValueError(
                "Case already allocated; preserve it and use a declared replacement: " + str(output)
            )
        command = [
            sys.executable,
            "-B",
            str(ROOT / "tools/riven_observe.py"),
            "capture",
            "--name",
            case["name"],
            "--mode",
            plan.get("mode", "timing"),
            "--port",
            str(plan["port"]),
            "--riven",
            str(ROOT / plan["inputs"]["riven"]),
            "--cubature",
            str(ROOT / plan["inputs"]["cubature"]),
            "--observer",
            str(ROOT / plan["inputs"]["observer"]),
            "--workload",
            str(ROOT / plan["inputs"]["workload"]),
            "--cube-packet-cubes",
            str(case["cubePacketCubes"]),
            "--cube-packet-bytes",
            str(plan["cubePacketBytes"]),
        ]
        log = args.plan.parent / (case["name"] + ".log")
        event = {
            "name": case["name"],
            "index": case["index"],
            "command": command,
            "startedUtc": datetime.now(timezone.utc).isoformat(),
            "planSha256": sha(args.plan),
        }
        print(json.dumps(dict(event, event="start")), flush=True)
        with log.open("x", encoding="utf-8") as stream:
            completed = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        event.update(
            returncode=completed.returncode,
            endedUtc=datetime.now(timezone.utc).isoformat(),
            log=str(log),
            result=str(output / "runs/write/result.json"),
        )
        with journal.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event) + "\n")
        print(json.dumps(dict(event, event="finish")), flush=True)
        if completed.returncode:
            raise RuntimeError("Native case failed; original evidence retained: " + case["name"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
