"""Compile the working tree's worldgen with one tools/checks entry point and run it.

The check compiles exactly as the terrain atlas sampler does (worldgen plus contributed kernels,
Java 25 release, cached by content hash) and runs on the Java 26 from .local/settings.json. Run
with the atlas venv interpreter; the exit status is the check's.

  .local/atlas-venv/Scripts/python tools/run_check.py SampleHistoryCheck [argument ...]
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from atlas import build  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description="Run one tools/checks entry point.")
    parser.add_argument("check", help="class name under tools/checks, e.g. SampleHistoryCheck")
    parser.add_argument("arguments", nargs="*", help="passed to the check's main")
    args = parser.parse_args()
    source = build.ROOT / "tools/checks" / f"{args.check}.java"
    if not source.is_file():
        sys.exit(f"no check {source}")
    started = time.monotonic()
    tree = build.source_tree(None)
    classes = build.compile_sampler(tree, source)
    compiled = time.monotonic()
    command = [
        str(build.java()),
        "-cp",
        os.pathsep.join([str(classes), str(build.gson())]),
        f"{build.PACKAGE}.worldgen.{args.check}",
        *args.arguments,
    ]
    code = subprocess.run(command).returncode
    # tools/actions.py keeps "timing " lines in the run summary for tools/ci_timing.py.
    seconds = {
        "compile": round(compiled - started, 1),
        "run": round(time.monotonic() - compiled, 1),
    }
    print("timing " + json.dumps({"check": args.check} | seconds), flush=True)
    sys.exit(code)


if __name__ == "__main__":
    main()
