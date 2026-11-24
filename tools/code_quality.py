"""Format or statically check active Riven code without launching a game or tests."""

import argparse
import hashlib
import importlib.metadata
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from typing import Literal

ROOT = Path(__file__).resolve().parents[1]
FORMATTER_VERSION = "1.35.0"
FORMATTER_SHA256 = "bfb7f9ead6cd328389bc2da53860443bc0e805dfd08cc889bfdf43b26cb2a6e8"
PYTHON_TOOLS = {"ruff": "0.17.1", "mypy": "2.4.0"}
PYTHON_PATHS = ["tools", "launch"]


def run(arguments: list[str], environment: dict[str, str] | None = None) -> None:
    display = (
        arguments
        if len(arguments) <= 12
        else arguments[:10] + [f"... ({len(arguments) - 10} more arguments)"]
    )
    print("Running: " + " ".join(display), flush=True)
    started = time.monotonic()
    subprocess.run(arguments, cwd=ROOT, env=environment, check=True)
    # The actions runner keeps "timing " lines in the run summary (its report shows them).
    name = " ".join(Path(a).name if i == 0 else a for i, a in enumerate(arguments[:4]))
    timing = {"command": name, "seconds": round(time.monotonic() - started, 1)}
    print("timing " + json.dumps(timing), flush=True)


def java() -> str:
    """google-java-format's Java: JAVA_HOME's, which a Workbench job sets to the set's JDK (Temurin 26)."""
    home = os.environ.get("JAVA_HOME")
    if not home:
        raise ValueError("Set JAVA_HOME to a JDK 26 (a Workbench job sets the set's)")
    return str(Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java"))


def python_tools() -> None:
    for package, expected in PYTHON_TOOLS.items():
        try:
            actual = importlib.metadata.version(package)
        except importlib.metadata.PackageNotFoundError:
            actual = "not installed"
        if actual != expected:
            raise ValueError(
                f"Expected {package}=={expected}; found {actual}. "
                "Install tools/requirements-quality.txt in this Python environment."
            )


def formatter() -> Path:
    name = f"google-java-format-{FORMATTER_VERSION}-all-deps.jar"
    path = ROOT / ".local/quality-tools" / name
    if not path.is_file():
        url = (
            "https://repo.maven.apache.org/maven2/com/google/googlejavaformat/"
            f"google-java-format/{FORMATTER_VERSION}/{name}"
        )
        print("Downloading pinned Java formatter: " + url, flush=True)
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read()
        if hashlib.sha256(content).hexdigest() != FORMATTER_SHA256:
            raise ValueError("Downloaded Java formatter hash mismatch")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    if hashlib.sha256(path.read_bytes()).hexdigest() != FORMATTER_SHA256:
        raise ValueError("Cached Java formatter hash mismatch: " + str(path))
    return path


def java_format(java: str, apply: bool) -> None:
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    sources += sorted((ROOT / "tools/checks").glob("*.java"))
    mode = ["--replace"] if apply else ["--dry-run", "--set-exit-if-changed"]
    arguments = [
        java,
        "-jar",
        str(formatter()),
        "--aosp",
        "--skip-reflowing-long-strings",
        "--skip-javadoc-formatting",
        *mode,
        *(str(path.relative_to(ROOT)) for path in sources),
    ]
    run(arguments)


def check_java() -> None:
    # The set, the Store and the JDKs Gradle may use come from the job's GRADLE_OPTS, as for every {gradle} step
    # (by hand, from the Gradle home's block that wb setup writes); the toolchain is the set's Java.
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run([str(wrapper), "assemble", "compileDiagnosticsJava", "--console=plain"])


def quality(action: Literal["format", "check"]) -> None:
    python_tools()
    java_format(java(), action == "format")
    python = sys.executable
    if action == "format":
        run([python, "-m", "ruff", "check", "--select", "I,E401", "--fix", *PYTHON_PATHS])
        run([python, "-m", "ruff", "format", *PYTHON_PATHS])
        return
    run([python, "-m", "ruff", "check", *PYTHON_PATHS])
    run([python, "-m", "ruff", "format", "--check", *PYTHON_PATHS])
    run([python, "-m", "mypy", "--config-file", "pyproject.toml"])
    run([python, "tools/strict_math.py"])
    check_java()
    print("Code-quality checks passed; no tests or game launches were run.", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("format", "check"))
    action = parser.parse_args().action
    try:
        quality(action)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
