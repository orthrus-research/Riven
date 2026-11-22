"""Build the Cubature dev/cc.lock.json pins, with its tests and the lab observer, and freeze them.

ci's cubature-build job. The pinned commit is built in a worktree of the Cubature checkout with
that commit's own compile-only inputs (tools/setup.py's builder), never from whatever the checkout
has out: its Cleanroom build with the regression tests on Java 26, then the lab observer against
exactly that JAR. The run fails unless the Cubature JARs are the pinned bytes. `--freeze <label>`
copies `cubature-cleanroom.jar` and `lab-cleanroom.jar` to `.local/frozen/<label>/` beside Riven's
build and records them under the folder manifest's `cubature` key.

  python tools/build_cubature_pin.py --freeze candidate-01
"""

import argparse
import shutil
import sys
import time
import xml.etree.ElementTree as ElementTree
from pathlib import Path

import build_riven
import setup

ROOT = setup.ROOT


def test_counts(results: Path) -> dict[str, int]:
    """Tests, failures, errors and skips in a Gradle test-results folder's JUnit reports."""
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for report in results.rglob("TEST-*.xml"):
        suite = ElementTree.parse(report).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, 0))
    return counts


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--freeze", metavar="LABEL", help="copy the JARs to .local/frozen/LABEL")
    args = parser.parse_args(argv)
    pin = setup.load(ROOT / "dev/cc.lock.json")
    settings = setup.load(setup.SETTINGS) if setup.SETTINGS.is_file() else {}
    commit = pin["commit"]
    started = time.monotonic()
    print(f"== Cubature {commit[:12]} (dev/cc.lock.json): build, tests, lab", flush=True)
    with setup.built_cubature(commit, pin["repository"], settings, tests=True, lab=True) as work:
        # Check the pinned bytes before anything is frozen.
        built = setup.cubature_artifacts(work, setup.cubature_pins())
        for source, target, expected in built:
            if setup.sha256(source) != expected:
                sys.exit(
                    f"Cubature {commit[:12]} builds {source.name} to SHA-256 "
                    f"{setup.sha256(source)}, not the pinned {expected} ({target.name})"
                )
        (lab,) = (work / setup.LAB_LIBS).glob("cubicchunks-lab-*-cleanroom.jar")
        artifacts = {"cubature-cleanroom": built[0][0], "lab-cleanroom": lab}
        manifest = {
            "builtAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "seconds": round(time.monotonic() - started, 1),
            "commit": commit,
            "source": "a worktree of the Cubature checkout at the commit dev/cc.lock.json pins",
            "artifacts": {name: build_riven.record(path) for name, path in artifacts.items()},
            "tests": {
                "cubature-cleanroom": test_counts(work / "cleanroom/build/cleanroom/test-results"),
                "lab-cleanroom": test_counts(work / "lab/build/cleanroom/test-results"),
            },
        }
        if args.freeze:
            target = ROOT / ".local/frozen" / args.freeze
            target.mkdir(parents=True, exist_ok=True)
            for name, path in artifacts.items():
                destination = target / f"{name}.jar"
                entry = manifest["artifacts"][name]
                if destination.exists() and setup.sha256(destination) != entry["sha256"]:
                    sys.exit(f"Refusing to overwrite a different frozen artifact: {destination}")
                shutil.copy2(path, destination)
                entry["frozen"] = str(destination)
            build_riven.merge_frozen_manifest(target, "cubature", manifest)
    for name, counts in manifest["tests"].items():
        print(f"{name}: {counts['tests']} tests, {counts['failures'] + counts['errors']} failed")
    print(f"Cubature {commit[:12]}: the pinned bytes, in {manifest['seconds']} s", flush=True)


if __name__ == "__main__":
    main()
