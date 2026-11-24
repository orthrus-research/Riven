"""Run a sealed, serial packet-count screen through the existing native observer.

Each case is its own Workbench driver session of the plan's launch file (`wb play --from <launch> --name <case>
--driver capture`), its options passed in LAB_DRIVER_ARGS, so every case measures a fresh copy of the same instance
under the GPU lease. The plan's inputs name the launch file, the observer and the workload by path and SHA-256.
WB_CLI names another wb launcher.
"""

import argparse
import json
import os
import re
import shlex
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from observability.profiles import ROOT, sha

SESSION = re.compile(r"^session\s+(\S.*?)\s+\(driver\)\s*$", re.M)


def wb():
    return os.environ.get("WB_CLI") or "wb"


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
        options = [
            "--mode",
            plan.get("mode", "timing"),
            "--observer",
            str(ROOT / plan["inputs"]["observer"]),
            "--workload",
            str(ROOT / plan["inputs"]["workload"]),
            "--cube-packet-cubes",
            str(case["cubePacketCubes"]),
            "--cube-packet-bytes",
            str(plan["cubePacketBytes"]),
        ]
        command = [
            wb(),
            "play",
            "--from",
            str(ROOT / plan["inputs"]["launch"]),
            "--name",
            case["name"],
            "--driver",
            "capture",
        ]
        log = args.plan.parent / (case["name"] + ".log")
        if log.exists():
            raise ValueError(
                "Case already ran; preserve it and use a declared replacement: " + str(log)
            )
        event = {
            "name": case["name"],
            "index": case["index"],
            "command": command,
            "options": options,
            "startedUtc": datetime.now(timezone.utc).isoformat(),
            "planSha256": sha(args.plan),
        }
        print(json.dumps(dict(event, event="start")), flush=True)
        environment = {**os.environ, "LAB_DRIVER_ARGS": shlex.join(options)}
        with log.open("x", encoding="utf-8") as stream:
            completed = subprocess.run(
                command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, env=environment
            )
        found = SESSION.search(log.read_text(encoding="utf-8", errors="replace"))
        event.update(
            returncode=completed.returncode,
            endedUtc=datetime.now(timezone.utc).isoformat(),
            log=str(log),
            result=str(Path(found.group(1)) / "runs/write/result.json") if found else None,
        )
        with journal.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event) + "\n")
        print(json.dumps(dict(event, event="finish")), flush=True)
        if completed.returncode:
            raise RuntimeError("Native case failed; original evidence retained: " + case["name"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
