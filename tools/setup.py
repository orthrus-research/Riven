"""Prepare a fresh clone for building and testing Riven.

Steps, each skipped when already done: record the JDK 25 and Java 26 paths in
.local/settings.json, clone Cubature beside Riven (../Cubature, whose lab tools runtime.py
imports), and create the Python environments the local CI uses. The mods an instance loads come
from a launch file, not from here (docs/RUNTIME.md). --check reports without changing anything.
The tooling targets Windows.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import venv
from pathlib import Path

from checkouts import CUBATURE

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / ".local/settings.json"
CUBATURE_REPOSITORY = "https://github.com/orthrus-research/Cubature.git"
ENVIRONMENTS = {
    "quality-venv": "tools/requirements-quality.txt",
    "atlas-venv": "tools/requirements-atlas.txt",
    "observability-venv": "tools/observability/requirements.txt",
}
JAVA = "java.exe" if os.name == "nt" else "java"
JDK_ROOTS = (
    [
        Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / vendor
        for vendor in ("Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto")
    ]
    if os.name == "nt"
    else [Path("/usr/lib/jvm"), Path("/opt/java"), Path.home() / ".jdks"]
) + [ROOT / ".local/toolchains"]


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def java_major(java: Path) -> int | None:
    try:
        completed = subprocess.run(
            [str(java), "-version"], capture_output=True, text=True, timeout=15, check=True
        )
    except OSError, subprocess.SubprocessError:
        return None
    match = re.search(r'version "(\d+)', completed.stderr + completed.stdout)
    return int(match[1]) if match else None


def find_java(major: int, given: Path | None, recorded: str | None) -> Path | None:
    """The given path, else the recorded one, else a JAVA_HOME or standard install of that major."""
    candidates = [given] if given else []
    if recorded:
        candidates.append(Path(recorded))
    if os.environ.get(f"JAVA_HOME_{major}_X64"):
        candidates.append(Path(os.environ[f"JAVA_HOME_{major}_X64"]) / "bin" / JAVA)
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / JAVA)
    for root in JDK_ROOTS:
        if root.is_dir():
            candidates += sorted(root.glob(f"*{major}*/bin/{JAVA}"), reverse=True)
    for java in candidates:
        if java.is_file() and java_major(java) == major:
            return java.resolve()
    if given:
        raise SystemExit(f"{given} is not a Java {major} executable")
    return None


def configure_java(args: argparse.Namespace) -> bool:
    settings = load(SETTINGS) if SETTINGS.is_file() else {}
    ready = True
    for key, major, given in (("java25", 25, args.java25), ("java26", 26, args.java26)):
        java = find_java(major, given, settings.get(key))
        if java is None:
            print(f"missing  Java {major}: install it and pass --{key} PATH to its {JAVA}")
            ready = False
        elif settings.get(key) != str(java):
            print(f"set      {key} = {java}")
            settings[key] = str(java)
        else:
            print(f"present  {key} = {java}")
    if not args.check:
        SETTINGS.parent.mkdir(parents=True, exist_ok=True)
        SETTINGS.write_text(json.dumps(settings, indent=2) + "\n", encoding="utf-8")
    return ready


def clone_cubature(args: argparse.Namespace) -> bool:
    if not (CUBATURE / ".git").exists():
        if args.check:
            print(f"missing  {CUBATURE} (Cubature checkout)")
            return False
        source = args.cubature_url or CUBATURE_REPOSITORY
        subprocess.run(["git", "clone", source, str(CUBATURE)], check=True)
    head = subprocess.run(
        ["git", "-C", str(CUBATURE), "rev-parse", "HEAD"], capture_output=True, text=True
    ).stdout.strip()
    print(f"present  {CUBATURE} (at {head[:12]})")
    return True


def environments(args: argparse.Namespace) -> bool:
    ready = True
    for name, requirements in ENVIRONMENTS.items():
        python = (
            ROOT / ".local" / name / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
        )
        if python.is_file():
            print(f"present  .local/{name}")
            continue
        if args.check:
            print(f"missing  .local/{name}")
            ready = False
            continue
        venv.create(ROOT / ".local" / name, with_pip=True)
        subprocess.run(
            [str(python), "-m", "pip", "install", "-q", "-r", str(ROOT / requirements)], check=True
        )
        print(f"created  .local/{name}")
    return ready


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java25", type=Path, help=f"{JAVA} of a JDK 25 (Gradle and checks)")
    parser.add_argument("--java26", type=Path, help=f"{JAVA} of a Java 26 (tests and runtime)")
    parser.add_argument(
        "--cubature-url", help=f"clone Cubature from here instead of {CUBATURE_REPOSITORY}"
    )
    parser.add_argument("--check", action="store_true", help="report only; change nothing")
    args = parser.parse_args()
    if sys.version_info < (3, 14):
        raise SystemExit("Riven's tools need Python 3.14 or newer")
    ready = configure_java(args)
    if not ready and not args.check:
        raise SystemExit("Install the missing Java first; nothing else was changed.")
    ready &= clone_cubature(args)
    ready &= environments(args)
    if not ready:
        raise SystemExit("Setup is incomplete; see the lines marked missing.")
    print(
        "Ready. Next: riven.ps1 build, then riven.ps1 ci."
        if os.name == "nt"
        else "Ready. Next: python3 tools/runtime.py prepare and play (docs/RUNTIME.md, on Linux)."
    )


if __name__ == "__main__":
    main()
