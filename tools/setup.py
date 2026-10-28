"""Prepare a fresh clone for building and testing Riven.

Steps, each skipped when already done: record the JDK 25 and Java 26 paths in
.local/settings.json, clone Cubature into dev/CubicChunks, download every third-party pin named
in runtime.lock.json (verified by SHA-256), build the Cubature artifacts that dev/cc.lock.json
pins, and create the Python environments the local CI uses. --check reports without changing
anything. The tooling targets Windows.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import venv
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CUBATURE = ROOT / "dev/CubicChunks"
SETTINGS = ROOT / ".local/settings.json"
ENVIRONMENTS = {
    "quality-venv": "tools/requirements-quality.txt",
    "atlas-venv": "tools/requirements-atlas.txt",
    "observability-venv": "tools/observability/requirements.txt",
}
JDK_ROOTS = [
    Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / vendor
    for vendor in ("Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto")
] + [ROOT / ".local/toolchains"]


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


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
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin/java.exe")
    for root in JDK_ROOTS:
        if root.is_dir():
            candidates += sorted(root.glob(f"*{major}*/bin/java.exe"), reverse=True)
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
            print(f"missing  Java {major}: install it and pass --{key} PATH to its java.exe")
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
    pin = load(ROOT / "dev/cc.lock.json")
    if not (CUBATURE / ".git").exists():
        if args.check:
            print("missing  dev/CubicChunks (Cubature checkout)")
            return False
        source = args.cubature_url or pin["repository"]
        subprocess.run(["git", "clone", source, str(CUBATURE)], check=True)
    head = subprocess.run(
        ["git", "-C", str(CUBATURE), "rev-parse", "HEAD"], capture_output=True, text=True
    ).stdout.strip()
    state = (
        "at the pinned commit"
        if head == pin["commit"]
        else f"at {head[:12]}, pin {pin['commit'][:12]}"
    )
    print(f"present  dev/CubicChunks ({state})")
    return True


def download(url: str, target: Path, expected: str) -> None:
    partial = target.with_name(target.name + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "orthrus-research/riven-setup"})
    with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)
    actual = sha256(partial)
    if actual != expected:
        partial.unlink()
        raise SystemExit(f"{target.name}: SHA-256 {actual} does not match the pinned {expected}")
    partial.replace(target)


def third_party_pins(args: argparse.Namespace) -> bool:
    ready = True
    for mod in load(ROOT / "runtime.lock.json")["mods"]:
        # Optional pins too: Cubature compiles against Demonica, a runtime option.
        if "download" not in mod:
            continue
        target = ROOT / mod["file"]
        if target.is_file() and sha256(target) == mod["sha256"]:
            print(f"present  {mod['file']}")
        elif args.check:
            print(f"missing  {mod['file']}")
            ready = False
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            download(mod["download"], target, mod["sha256"])
            print(f"fetched  {mod['file']}")
    return ready


def cubature_pins() -> list[tuple[Path, str, str]]:
    """(pinned file, expected SHA-256, loader) for both loaders' binary and sources JARs."""
    pin = load(ROOT / "dev/cc.lock.json")
    forge = pin["forgeArtifact"]
    return [
        (ROOT / pin["artifact"], pin["sha256"], "cleanroom"),
        (ROOT / pin["sourcesArtifact"], pin["sourcesSha256"], "cleanroom"),
        (ROOT / forge["file"], forge["sha256"], "forge"),
        (ROOT / forge["sourcesFile"], forge["sourcesSha256"], "forge"),
    ]


def build_cubature(args: argparse.Namespace) -> bool:
    pins = cubature_pins()
    if all(path.is_file() and sha256(path) == expected for path, expected, _ in pins):
        print("present  Cubature artifacts (dev/cc.lock.json)")
        return True
    if args.check:
        print("missing  Cubature artifacts: run setup without --check to build them")
        return False
    print("building Cubature from dev/CubicChunks (both loaders, with tests)")
    subprocess.run(
        [sys.executable, str(CUBATURE / "tools/build_cubature.py"), "--forge", "--no-lab"],
        check=True,
    )
    exact = True
    for path, expected, loader in pins:
        sources = path.name.endswith("-sources.jar")
        libs = CUBATURE / f"cleanroom/build/{loader}/libs"
        pattern = "Cubature-*-sources.jar" if sources else f"Cubature-*-{loader}.jar"
        (built,) = libs.glob(pattern)
        shutil.copy2(built, path)
        exact &= sha256(path) == expected
    if not exact:
        print(
            "note     The built Cubature differs from the pinned bytes (a different commit or "
            "build). Riven builds against it; native instances need the pinned artifact."
        )
    return True


def environments(args: argparse.Namespace) -> bool:
    ready = True
    for name, requirements in ENVIRONMENTS.items():
        python = ROOT / ".local" / name / "Scripts/python.exe"
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
    parser.add_argument("--java25", type=Path, help="java.exe of a JDK 25 (Gradle and checks)")
    parser.add_argument("--java26", type=Path, help="java.exe of a Java 26 (tests and runtime)")
    parser.add_argument(
        "--cubature-url", help="clone Cubature from here instead of dev/cc.lock.json's repository"
    )
    parser.add_argument("--check", action="store_true", help="report only; change nothing")
    args = parser.parse_args()
    if sys.version_info < (3, 14):
        raise SystemExit("Riven's tools need Python 3.14 or newer")
    ready = configure_java(args)
    if not ready and not args.check:
        raise SystemExit("Install the missing Java first; nothing else was changed.")
    ready &= clone_cubature(args)
    ready &= third_party_pins(args)
    ready &= build_cubature(args)
    ready &= environments(args)
    if not ready:
        raise SystemExit("Setup is incomplete; see the lines marked missing.")
    print("Ready. Next: riven.ps1 build, then riven.ps1 ci.")


if __name__ == "__main__":
    main()
