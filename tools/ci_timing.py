"""Report where an actions run spent its time, as Markdown beside its summary.json.

Reads `.local/actions/<run-id>/summary.json` (the latest run by default): every job and step, and
the `timing` records steps print (tools/actions.py keeps them): golden windows by pass, with map
building, sampling, a profiled second pass and painting; each check's compile and run; and each
code-quality command. Writes timing.md next to the summary and prints it.

  python tools/ci_timing.py
  python tools/ci_timing.py 20261112-150000
"""

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / ".local/actions"


def table(headers: list[str], rows: list[list[object]]) -> list[str]:
    def cell(value: object) -> str:
        if value is None or value == "":
            return ""
        if isinstance(value, float):
            return f"{value:.1f}"
        return str(value)

    align = ["---" if i == 0 else "---:" for i in range(len(headers))]
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join(align) + " |"]
    lines += ["| " + " | ".join(cell(v) for v in row) + " |" for row in rows]
    return lines


def timings(summary: dict) -> list[tuple[str, str, dict]]:
    """(job, step, record) for every timing record in the run."""
    found = []
    for job, result in summary.get("jobs", {}).items():
        for step in result.get("steps", []):
            for record in step.get("timing", []):
                found.append((job, step["name"], record))
    return found


def golden_section(golden: dict) -> list[str]:
    lines = ["## Golden windows", ""]
    passes = [["compile sampler", golden.get("compile")]]
    for name in ("forward", "verify"):
        if name in golden:
            entry = golden[name]
            passes.append([f"{name} JVM ({entry['windows']} windows)", entry["seconds"]])
    passes.append(["paint and viewer", golden.get("paint")])
    passes.append(["whole step", golden.get("seconds")])
    lines += table(["Phase", "Seconds"], passes)
    if golden.get("reused"):
        lines += ["", "Reused forward samples: " + ", ".join(golden["reused"]) + "."]
    rows = []
    for window, entry in golden.get("windows", {}).items():
        for name in ("forward", "verify"):
            s = entry.get(name)
            if not s:
                continue
            resampling = s.get("resampling")
            plans = None if resampling is None else s.get("sampling", 0) - resampling
            rows.append(
                [
                    f"{window} {name}",
                    s.get("begun"),
                    s.get("startup"),
                    s.get("maps"),
                    "yes" if s.get("mapsShared") else "",
                    s.get("sampling"),
                    resampling,
                    plans,
                    s.get("busyCores"),
                    s.get("sites"),
                    s.get("finish"),
                    entry.get("paint") if name == "forward" else None,
                ]
            )
    if rows:
        lines += [
            "",
            "Per window, in seconds. *Begun* is when the window started in its JVM; *maps* is "
            "building the seed's maps or waiting for the window that builds them; *plans* is "
            "sampling minus the profiled second pass over the same plans (`--profile` only); "
            "*sites* is the sequential site decisions and *write* renumbering and writing layers.",
            "",
        ]
        lines += table(
            [
                "Window",
                "Begun",
                "JVM start",
                "Maps",
                "Shared",
                "Sampling",
                "2nd pass",
                "Plans",
                "Cores busy",
                "Sites",
                "Write",
                "Paint",
            ],
            rows,
        )
    return lines


def report(summary: dict) -> str:
    lines = [f"# Timing for run {summary.get('runId')}", ""]
    lines.append(
        f"Targets: {' '.join(summary.get('targets', []))}; {round(summary.get('seconds') or 0, 1)} s."
    )
    lines += ["", "## Jobs", ""]
    rows = []
    for job, result in summary.get("jobs", {}).items():
        steps = result.get("steps", [])
        rows.append([job, result.get("status"), sum(s.get("seconds", 0) for s in steps)])
    lines += table(["Job", "Status", "Seconds"], rows)
    lines += ["", "## Steps", ""]
    rows = []
    for job, result in summary.get("jobs", {}).items():
        for step in result.get("steps", []):
            rows.append([f"{job} / {step['name']}", step.get("exitCode"), step.get("seconds")])
    lines += table(["Step", "Exit", "Seconds"], rows)
    records = timings(summary)
    for _, _, record in records:
        if "atlasGolden" in record:
            lines += [""] + golden_section(record["atlasGolden"])
    checks = [
        [f"{job} / {record['check']}", record.get("compile"), record.get("run")]
        for job, _, record in records
        if "check" in record
    ]
    if checks:
        lines += ["", "## Checks", ""] + table(["Check", "Compile", "Run"], checks)
    commands = [
        [f"{job} / {record['command']}", record.get("seconds")]
        for job, _, record in records
        if "command" in record
    ]
    if commands:
        lines += ["", "## Commands", ""] + table(["Command", "Seconds"], commands)
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("run", nargs="?", help="run id under .local/actions (latest)")
    args = parser.parse_args()
    if args.run:
        path = RUNS / args.run / "summary.json"
    else:
        runs = sorted(RUNS.glob("*/summary.json"))
        if not runs:
            sys.exit(f"no runs under {RUNS}")
        path = runs[-1]
    summary = json.loads(path.read_text(encoding="utf-8"))
    text = report(summary)
    (path.parent / "timing.md").write_text(text, encoding="utf-8")
    print(text)
    print(f"wrote {path.parent / 'timing.md'}")


if __name__ == "__main__":
    main()
