"""Format or statically check active Riven code without launching a game or tests."""

import argparse
import hashlib
import importlib.metadata
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path
from typing import Literal

ROOT = Path(__file__).resolve().parents[1]
FORMATTER_VERSION = "1.35.0"
FORMATTER_SHA256 = "bfb7f9ead6cd328389bc2da53860443bc0e805dfd08cc889bfdf43b26cb2a6e8"
PYTHON_TOOLS = {"ruff": "0.17.1", "mypy": "2.4.0"}
PYTHON_PATHS = ["tools"]


def run(arguments: list[str], environment: dict[str, str] | None = None) -> None:
    display = (
        arguments
        if len(arguments) <= 12
        else arguments[:10] + [f"... ({len(arguments) - 10} more arguments)"]
    )
    print("Running: " + " ".join(display), flush=True)
    subprocess.run(arguments, cwd=ROOT, env=environment, check=True)


def settings() -> dict[str, str]:
    path = ROOT / ".local/settings.json"
    if not path.is_file():
        raise ValueError("Configure .local/settings.json with java25 and java26 JDK executables")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("Local settings must be a JSON object")
    result = {}
    for key in ("java25", "java26"):
        value = data.get(key)
        if not isinstance(value, str) or not Path(value).is_file():
            raise ValueError("Missing local JDK executable: " + key)
        result[key] = value
    return result


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


def check_java(local: dict[str, str]) -> None:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(Path(local["java25"]).parent.parent)
    java26 = Path(local["java26"]).parent.parent
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    for loader in ("cleanroom", "forge"):
        run(
            [
                str(wrapper),
                "assemble",
                "compileDiagnosticsJava",
                "--console=plain",
                "--no-daemon",
                f"-PrivenLoader={loader}",
                "-PrivenJavaVersion=26",
                f"-Dorg.gradle.java.installations.paths={java26}",
            ],
            environment,
        )


def quality(action: Literal["format", "check"]) -> None:
    python_tools()
    local = settings()
    java_format(local["java26"], action == "format")
    python = sys.executable
    if action == "format":
        run([python, "-m", "ruff", "check", "--select", "I,E401", "--fix", *PYTHON_PATHS])
        run([python, "-m", "ruff", "format", *PYTHON_PATHS])
        return
    run([python, "-m", "ruff", "check", *PYTHON_PATHS])
    run([python, "-m", "ruff", "format", "--check", *PYTHON_PATHS])
    run([python, "-m", "mypy", "--config-file", "pyproject.toml"])
    check_java(local)
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
