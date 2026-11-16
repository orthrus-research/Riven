"""Build Riven for Cleanroom and record the artifact.

Writes `.local/builds/riven-latest.json` with each artifact's path, size and SHA-256 plus the git
commit and working-tree state. `--freeze <label>` copies the artifacts to `.local/frozen/<label>/`
so later captures and comparisons use immutable inputs.

Examples:
  python tools/build_riven.py
  python tools/build_riven.py --diagnostics --freeze candidate-01
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
# Cleanroom on Java 25 or 26 is the only target (docs/DECISIONS.md, 2026-10-30).
ARTIFACTS = {"cleanroom": ROOT / "build/libs/riven-0.0.1-cleanroom.jar"}


def settings() -> dict:
    path = ROOT / ".local/settings.json"
    if not path.is_file():
        sys.exit("Configure .local/settings.json with java25 and java26 JDK executables")
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in ("java25", "java26"):
        if not Path(data.get(key, "")).is_file():
            sys.exit(f"Missing local JDK executable in .local/settings.json: {key}")
    return data


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def git_state(root: Path) -> dict:
    def git(*args):
        result = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else None

    status = git("status", "--porcelain")
    return {"commit": git("rev-parse", "HEAD"), "dirty": bool(status)}


def record(path: Path) -> dict:
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha256(path)}


def freeze(artifacts: dict, label: str, manifest: dict) -> Path:
    target = ROOT / ".local/frozen" / label
    target.mkdir(parents=True, exist_ok=True)
    for name, entry in artifacts.items():
        destination = target / f"riven-{name}.jar"
        if destination.exists() and sha256(destination) != entry["sha256"]:
            sys.exit(f"Refusing to overwrite a different frozen artifact: {destination}")
        shutil.copy2(entry["path"], destination)
        entry["frozen"] = str(destination)
    merge_frozen_manifest(target, "riven", manifest)
    return target


def merge_frozen_manifest(target: Path, key: str, value: dict) -> None:
    """Sets one key of the frozen folder's manifest.json, safely beside another build doing the same.

    Riven's and Cubature's builds freeze into one folder and may finish together. Each reads the
    manifest, sets its own key and writes it back; done unguarded, the later write drops the other's
    key or reads a half-written file. So the merge runs under a lock on a file both builds know, and
    the result replaces the manifest in one step. Cubature's build_cubature.py holds the same lock.
    """
    path = target / "manifest.json"
    with open(target / "manifest.lock", "a+b") as lock:
        _lock(lock)
        try:
            combined = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
            combined[key] = value
            temporary = target / f"manifest.json.{key}.tmp"
            temporary.write_text(json.dumps(combined, indent=2), encoding="utf-8")
            os.replace(temporary, path)
        finally:
            _unlock(lock)


def _lock(stream, patience: float = 120.0) -> None:
    """Waits for an exclusive lock on the first byte of an open file."""
    stream.seek(0)
    if sys.platform == "win32":
        import msvcrt

        deadline = time.monotonic() + patience
        while True:
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                return
            except OSError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.05)
    else:
        import fcntl

        fcntl.flock(stream.fileno(), fcntl.LOCK_EX)


def _unlock(stream) -> None:
    stream.seek(0)
    if sys.platform == "win32":
        import msvcrt

        msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
    else:
        import fcntl

        fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--diagnostics", action="store_true", help="also compile tools/checks (Java 26)"
    )
    parser.add_argument("--freeze", metavar="LABEL", help="copy artifacts to .local/frozen/LABEL")
    parser.add_argument(
        "--no-daemon", action="store_true", help="leave no Gradle daemon (for native captures)"
    )
    args = parser.parse_args()
    local = settings()
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(Path(local["java25"]).parent.parent)
    java26_home = Path(local["java26"]).parent.parent
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    loaders = list(ARTIFACTS)
    started = time.monotonic()
    for loader in loaders:
        tasks = ["assemble"] + (["compileDiagnosticsJava"] if args.diagnostics else [])
        command = [
            str(wrapper),
            *tasks,
            "--console=plain",
            *(["--no-daemon"] if args.no_daemon else []),
            "-PrivenJavaVersion=26",
            f"-Dorg.gradle.java.installations.paths={java26_home}",
        ]
        print(f"== Riven {loader}: {' '.join(tasks)}", flush=True)
        if subprocess.run(command, cwd=ROOT, env=environment).returncode != 0:
            sys.exit(f"Riven {loader} build failed")
    artifacts = {}
    for loader in loaders:
        if not ARTIFACTS[loader].is_file():
            sys.exit(f"Expected artifact missing: {ARTIFACTS[loader]}")
        artifacts[loader] = record(ARTIFACTS[loader])
    manifest: dict[str, Any] = {
        "builtAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "seconds": round(time.monotonic() - started, 1),
        "git": git_state(ROOT),
        "artifacts": artifacts,
    }
    if args.freeze:
        manifest["frozenTo"] = str(freeze(artifacts, args.freeze, manifest))
    out = ROOT / ".local/builds/riven-latest.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    for loader, entry in artifacts.items():
        print(f"{loader}: {entry['path']}  sha256 {entry['sha256'][:16]}")
    if args.freeze:
        print(f"frozen: {manifest['frozenTo']}")
    state = "dirty" if manifest["git"]["dirty"] else "clean"
    print(f"Riven built in {manifest['seconds']} s from {manifest['git']['commit'][:8]} ({state})")


if __name__ == "__main__":
    main()
