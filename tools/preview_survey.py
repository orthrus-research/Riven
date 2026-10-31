"""Run the lab's preview survey on a copy of a prepared capture's server, and compare two surveys.

The survey (`cubiclab preview-survey`, the lab's LabPreviewSurvey) samples the overworld's terrain
preview over a square window at each footprint as Cubature's Distant Horizons generator does, and
writes rasters of each cell's top, ground top, water top and the kind of its topmost run, with each
tile's CPU time. Two surveys of one window prove a change left the preview identical (`compare`
exits 1 if any raster differs), and their CPU per column measures what it cost.

  .local/observability-venv/Scripts/python tools/preview_survey.py run SOURCE RIVEN_JAR NAME
  .local/observability-venv/Scripts/python tools/preview_survey.py compare SURVEY_A SURVEY_B

SOURCE is a capture under .local/cubic-standalone (`riven_observe.py capture`); its server, world
and launch command are copied to .local/cubic-standalone/NAME with RIVEN_JAR as its riven.jar, and
the survey lands in server/cubiclab-surveys/NAME there. By default the window is the land route's
2,048 blocks at (3120, -26032), footprints 64 to 1, on 16 threads.
"""

import argparse
import json
import queue
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
STANDALONE = ROOT / ".local/cubic-standalone"
RASTERS = (("top", "i16", 2), ("ground", "i16", 2), ("water", "i16", 2), ("kind", "u8", 1))


def run(source: str, riven: Path, name: str, window: str, footprints: str, threads: int) -> int:
    origin, target = STANDALONE / source, STANDALONE / name
    if target.exists():
        sys.exit(f"{target} exists; choose a fresh name")
    command: list[str] = json.loads((origin / "runs/write/server-process.json").read_text())[
        "command"
    ]
    shutil.copytree(
        origin / "server",
        target / "server",
        ignore=shutil.ignore_patterns("java-tmp", "cubiclab-surveys", "crash-reports", "logs"),
    )
    shutil.copy2(riven, target / "server/mods/riven.jar")
    command = [part.replace(str(origin), str(target)) for part in command]
    (target / "server/java-tmp").mkdir(exist_ok=True)
    lines: "queue.Queue[str | None]" = queue.Queue()
    with open(target / "survey-server.log", "w", encoding="utf-8") as log:
        server = subprocess.Popen(
            command,
            cwd=target / "server",
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        assert server.stdin is not None and server.stdout is not None
        output, console = server.stdout, server.stdin

        def pump() -> None:
            for line in output:
                log.write(line)
                log.flush()
                lines.put(line)
            lines.put(None)

        def wait(marker: str, seconds: float) -> str:
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                try:
                    line = lines.get(timeout=1)
                except queue.Empty:
                    continue
                if line is None:
                    sys.exit(f"the server exited waiting for {marker}")
                if marker in line:
                    return line
            sys.exit(f"timed out waiting for {marker}")

        def send(text: str) -> None:
            console.write(text + "\n")
            console.flush()

        threading.Thread(target=pump, daemon=True).start()
        started = time.monotonic()
        try:
            wait("Done (", 300)
            x, z, size = window.split(",")
            send(f"cubiclab preview-survey {name} {x} {z} {size} {footprints} {threads}")
            wait("CUBICLAB_SURVEY_STARTED", 60)
            print(wait("CUBICLAB_SURVEY_RESULT", 3600).strip())
            send("stop")
            server.wait(timeout=180)
        finally:
            if server.poll() is None:
                server.terminate()
                server.wait(timeout=30)
    survey = target / "server/cubiclab-surveys" / name
    summary: dict[str, Any] = json.loads((survey / "summary.json").read_text())
    for f in summary["footprints"]:
        print(
            f"footprint {f['footprint']:>2}: {f['tiles']:>5} tiles,"
            f" {f['cpuNanosPerColumn'] / 1000:8.1f} us CPU per column,"
            f" wall {f['wallNanos'] / 1e9:6.1f} s"
        )
    print(f"survey {survey} in {time.monotonic() - started:.0f} s")
    return 0


def compare(a: Path, b: Path) -> list[str]:
    """Per footprint, each raster's verdict: identical, or how many cells differ and where."""
    first, second = (json.loads((d / "summary.json").read_text()) for d in (a, b))
    for key in ("seed", "minX", "minZ", "size"):
        if first[key] != second[key]:
            raise ValueError(
                f"The surveys cover different windows: {key} {first[key]} vs {second[key]}"
            )
    cells = {f["footprint"]: f["cells"] for f in first["footprints"]}
    report = []
    for footprint in sorted(cells, reverse=True):
        verdicts = []
        for kind, suffix, width in RASTERS:
            left = (a / f"f{footprint}-{kind}.{suffix}").read_bytes()
            right = (b / f"f{footprint}-{kind}.{suffix}").read_bytes()
            if left == right:
                verdicts.append(f"{kind} same")
                continue
            n = cells[footprint]
            differing = [
                i // width
                for i in range(0, max(len(left), len(right)), width)
                if left[i : i + width] != right[i : i + width]
            ]
            where = ", ".join(
                f"({first['minX'] + (c % n) * footprint},{first['minZ'] + (c // n) * footprint})"
                for c in differing[:3]
            )
            verdicts.append(f"{kind} {len(differing)} differ [{where}]")
        report.append(
            f"footprint {footprint:>2} ({cells[footprint]}x{cells[footprint]}): "
            + "; ".join(verdicts)
        )
    return report


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = parser.add_subparsers(dest="command", required=True)
    survey = commands.add_parser("run", help="survey a copy of a capture's server with a Riven jar")
    survey.add_argument("source", help="capture name under .local/cubic-standalone")
    survey.add_argument("riven", type=Path, help="the Riven jar to survey")
    survey.add_argument("name", help="a fresh name for the copy and its survey")
    survey.add_argument("--window", default="3120,-26032,2048", help="minX,minZ,size in blocks")
    survey.add_argument("--footprints", default="64,32,16,8,4,2,1")
    survey.add_argument("--threads", type=int, default=16)
    diff = commands.add_parser("compare", help="compare two survey directories raster by raster")
    diff.add_argument("a", type=Path)
    diff.add_argument("b", type=Path)
    args = parser.parse_args()
    if args.command == "run":
        return run(args.source, args.riven, args.name, args.window, args.footprints, args.threads)
    lines = compare(args.a, args.b)
    print("\n".join(lines))
    return 0 if all("differ" not in line for line in lines) else 1


if __name__ == "__main__":
    sys.exit(main())
