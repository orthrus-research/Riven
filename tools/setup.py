"""Prepare a fresh clone for building and testing Riven.

Steps, each skipped when already done: record the JDK 25 and Java 26 paths in
.local/settings.json, clone Cubature where dev/cc.lock.json's checkout names (../Cubature),
download every third-party pin named in runtime.lock.json (verified by SHA-256), build every pin
runtime.lock.json gives a build recipe from its source checkout at the pinned commit (kept only
when it is the pinned bytes), build the Cubature artifacts dev/cc.lock.json pins at its commit in
the same way, and create the Python environments the local CI uses. Each source build runs in a
worktree of its checkout under .local/source-builds, so the checkouts themselves are never
changed. --check reports without changing anything. The tooling targets Windows.
"""

import argparse
import contextlib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import venv
from collections.abc import Iterator
from pathlib import Path

import jdks
from checkouts import CUBATURE, main_checkout

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / ".local/settings.json"
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
    pin = load(ROOT / "dev/cc.lock.json")
    if not (CUBATURE / ".git").exists():
        if args.check:
            print(f"missing  {CUBATURE} (Cubature checkout)")
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
    print(f"present  {CUBATURE} ({state})")
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


def git(checkout: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(checkout), *arguments], capture_output=True, text=True, check=False
    )


SOURCE_BUILDS = ".local/source-builds"
SOURCE_INPUTS = ".local/source-inputs"
# One build and done: no daemon left running, and no file-system watching, whose open handles
# would keep the worktree from being removed after the build.
GRADLE_ONCE = ["--console=plain", "--no-daemon", "--no-watch-fs"]


def remove_tree(path: Path) -> OSError | None:
    """Remove a folder and everything in it; the error when something stays. On Windows the path
    takes the extended form: a Gradle build's deepest files pass 260 characters, and long paths
    are off unless the system enables them (git worktree remove gives up on them too)."""
    text = os.path.abspath(path)
    try:
        shutil.rmtree("\\\\?\\" + text if os.name == "nt" and not text.startswith("\\\\") else text)
    except FileNotFoundError:
        pass
    except OSError as error:
        return error
    return None


def drop_worktree(checkout: Path, work: Path, attempts: int = 10) -> None:
    """Remove a build worktree of checkout. Windows can hold a just-built tree's files open for a
    while after the build returns, so removal is retried; a tree that still cannot go is reported
    and swept before a later build (each build has a folder of its own, so it blocks nothing)."""
    if work.exists():
        git(checkout, "worktree", "remove", "--force", str(work))
    for attempt in range(attempts):
        error = remove_tree(work)
        if error is None:
            break
        if attempt == attempts - 1:
            print(f"note     could not remove {work} yet ({error}); a later build sweeps it")
            break
        time.sleep(1)
    git(checkout, "worktree", "prune")


def sweep_source_builds() -> None:
    """Remove what earlier builds could not, where nothing holds it any more."""
    folder = ROOT / SOURCE_BUILDS
    for stale in folder.iterdir() if folder.is_dir() else []:
        if error := remove_tree(stale):
            print(f"note     {stale} is still held ({error})")


def build_command(
    recipe: dict, properties: dict[str, str], java_home: Path, work: Path
) -> list[str]:
    """The Gradle command a recipe names, run in work, with its resolved properties."""
    wrapper = work / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [
        str(wrapper),
        *recipe["tasks"],
        *GRADLE_ONCE,
        "-Dorg.gradle.java.installations.paths="
        + ",".join(dict.fromkeys(str(home) for home in [java_home, *jdks.installations()])),
        *(f"-P{name}={value}" for name, value in properties.items()),
    ]


def build_properties(recipe: dict, settings: dict) -> dict[str, str]:
    """A recipe's Gradle properties: text as given, or a Cubature JAR the build compiles against,
    named by commit and SHA-256 ({"cubature": commit, "sha256": hash}) and built for it."""
    return {
        name: str(cubature_input(value, settings)) if isinstance(value, dict) else value
        for name, value in recipe.get("properties", {}).items()
    }


@contextlib.contextmanager
def source_worktree(checkout: Path, source: str, commit: str, name: str) -> Iterator[Path]:
    """A worktree of checkout at commit, removed afterwards; the checkout is cloned from source,
    or the commit fetched from it, when missing. The checkout itself is never changed."""
    if not (checkout / ".git").exists():
        subprocess.run(["git", "clone", source, str(checkout)], check=True)
    if git(checkout, "cat-file", "-e", f"{commit}^{{commit}}").returncode != 0:
        subprocess.run(["git", "-C", str(checkout), "fetch", source, commit], check=True)
    sweep_source_builds()
    git(checkout, "worktree", "prune")
    work = ROOT / SOURCE_BUILDS / f"{name}-{commit[:12]}-{os.getpid()}"
    work.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["git", "-C", str(checkout), "worktree", "add", "--detach", str(work), commit], check=True
    )
    try:
        yield work
    finally:
        drop_worktree(checkout, work)


def place_pinned(built: list[tuple[Path, Path, str]], what: str) -> None:
    """Copy each built file to its pinned target, but only once every one is the pinned bytes;
    otherwise nothing is placed."""
    for source, target, expected in built:
        actual = sha256(source)
        if actual != expected:
            raise SystemExit(
                f"{target.relative_to(ROOT)}: {what} builds to SHA-256 {actual}, not the pinned "
                f"{expected}; nothing was placed"
            )
    for source, target, _ in built:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def java_home(settings: dict, major: int, what: str) -> Path:
    """A JDK for building ``what``: the one recorded in these settings, else what tools/jdks.py finds."""
    java = settings.get(f"java{major}")
    if java:
        return Path(java).parent.parent
    try:
        return jdks.home(major)
    except SystemExit:
        raise SystemExit(
            f"{what}: no Java {major} recorded in {SETTINGS} or in the environment"
        ) from None


def build_source_pin(mod: dict, settings: dict) -> None:
    """Build mod from its source checkout at sourceCommit, in a worktree of its own, and place
    it only if it is the pinned bytes."""
    recipe, commit = mod["build"], mod["sourceCommit"]
    checkout = (main_checkout(ROOT) / recipe["checkout"]).resolve()
    home = java_home(settings, recipe["java"], mod["file"])
    name = Path(mod["file"]).stem
    properties = build_properties(recipe, settings)
    with source_worktree(checkout, mod["source"], commit, name) as work:
        environment = os.environ | {"JAVA_HOME": str(home)}
        command = build_command(recipe, properties, home, work)
        if subprocess.run(command, cwd=work, env=environment).returncode != 0:
            raise SystemExit(f"{mod['file']}: the build of {checkout.name} {commit[:12]} failed")
        built = [(work / recipe["artifact"], ROOT / mod["file"], mod["sha256"])]
        place_pinned(built, f"{checkout.name} {commit[:12]}")


def source_pins(args: argparse.Namespace) -> bool:
    """The runtime.lock.json pins with a build recipe: present when dev/pins holds the pinned
    bytes, else built from their source checkouts."""
    settings = load(SETTINGS) if SETTINGS.is_file() else {}
    ready = True
    for mod in load(ROOT / "runtime.lock.json")["mods"]:
        if "build" not in mod:
            continue
        target = ROOT / mod["file"]
        commit = mod["sourceCommit"][:12]
        if target.is_file() and sha256(target) == mod["sha256"]:
            print(f"present  {mod['file']}")
        elif args.check:
            print(f"missing  {mod['file']}: run setup without --check to build it at {commit}")
            ready = False
        else:
            print(f"building {mod['file']} from {mod['build']['checkout']} at {commit}", flush=True)
            build_source_pin(mod, settings)
            print(f"built    {mod['file']}")
    return ready


def cubature_pins() -> list[tuple[Path, str, str]]:
    """(pinned file, expected SHA-256, loader) for the Cleanroom binary and sources JARs."""
    pin = load(ROOT / "dev/cc.lock.json")
    return [
        (ROOT / pin["artifact"], pin["sha256"], "cleanroom"),
        (ROOT / pin["sourcesArtifact"], pin["sourcesSha256"], "cleanroom"),
    ]


CUBATURE_LIBS = "cleanroom/build/cleanroom/libs"
LAB_LIBS = "lab/build/cleanroom/libs"


def cubature_command(work: Path, java_homes: list[Path], tests: bool = False) -> list[str]:
    """The Cleanroom build of a Cubature worktree from the inputs fetched into it: assembled, or
    built with its regression tests on Java 26. Naming the inputs folder keeps an older commit's
    build from reading Riven's dev/pins."""
    wrapper = work / "cleanroom" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [
        str(wrapper),
        *(["-PcubicTestJava=26", "build"] if tests else ["assemble"]),
        *GRADLE_ONCE,
        "-Porg.gradle.java.installations.paths="
        + ",".join(dict.fromkeys(str(home) for home in [*java_homes, *jdks.installations()])),
        f"-PcubicBuildInputs={work / 'cleanroom/build-inputs'}",
    ]


def lab_command(work: Path, java_homes: list[Path], artifact: Path) -> list[str]:
    """The lab observer's build and checks, against exactly the Cubature JAR just built."""
    wrapper = work / "cleanroom" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [
        str(wrapper),
        "build",
        *GRADLE_ONCE,
        "-Porg.gradle.java.installations.paths="
        + ",".join(dict.fromkeys(str(home) for home in [*java_homes, *jdks.installations()])),
        f"-PcubicArtifact={artifact}",
        f"-PcubicBuildInputs={work / 'cleanroom/build-inputs'}",
    ]


@contextlib.contextmanager
def built_cubature(
    commit: str, repository: str, settings: dict, tests: bool = False, lab: bool = False
) -> Iterator[Path]:
    """A worktree of the Cubature checkout at commit, its Cleanroom build made (with its tests on
    Java 26, and the lab observer built against it, when asked) from that commit's own compile-only
    inputs; removed afterwards. The JARs are under CUBATURE_LIBS and LAB_LIBS."""
    homes = [java_home(settings, 25, "Cubature")]
    if tests or lab:
        homes.append(java_home(settings, 26, "Cubature"))
    with source_worktree(CUBATURE, repository, commit, "cubature") as work:
        # The commit's own compile-only inputs, fetched and checked by its own tool.
        fetch = [sys.executable, "-B", str(work / "tools/fetch_build_inputs.py")]
        subprocess.run(fetch, cwd=work, check=True)
        environment = os.environ | {"JAVA_HOME": str(homes[0])}
        environment.pop("RIVEN_WORKSPACE", None)
        command = cubature_command(work, homes, tests)
        if subprocess.run(command, cwd=work / "cleanroom", env=environment).returncode != 0:
            raise SystemExit(f"Cubature: the build of {commit[:12]} failed")
        if lab:
            (artifact,) = (work / CUBATURE_LIBS).glob("Cubature-*-cleanroom.jar")
            command = lab_command(work, homes, artifact)
            if subprocess.run(command, cwd=work / "lab", env=environment).returncode != 0:
                raise SystemExit(f"Cubature: the lab build of {commit[:12]} failed")
        yield work


def cubature_input(spec: dict, settings: dict) -> Path:
    """A Cubature JAR a source-built pin compiles against, named by commit and SHA-256: built at
    that commit and kept under .local/source-inputs once it is those bytes."""
    commit, expected = spec["cubature"], spec["sha256"]
    target = ROOT / SOURCE_INPUTS / f"Cubature-{commit[:12]}-cleanroom.jar"
    if target.is_file() and sha256(target) == expected:
        return target
    repository = load(ROOT / "dev/cc.lock.json")["repository"]
    print(f"building Cubature at {commit[:12]} as a build input", flush=True)
    with built_cubature(commit, repository, settings) as work:
        (found,) = (work / CUBATURE_LIBS).glob("Cubature-*-cleanroom.jar")
        place_pinned([(found, target, expected)], f"Cubature {commit[:12]}")
    return target


def cubature_artifacts(
    work: Path, pins: list[tuple[Path, str, str]]
) -> list[tuple[Path, Path, str]]:
    """(built file, pinned target, expected SHA-256) for each pinned Cubature JAR in a worktree."""
    built = []
    for path, expected, _ in pins:
        pattern = (
            "Cubature-*-sources.jar"
            if path.name.endswith("-sources.jar")
            else "Cubature-*-cleanroom.jar"
        )
        (found,) = (work / CUBATURE_LIBS).glob(pattern)
        built.append((found, path, expected))
    return built


def build_cubature(args: argparse.Namespace) -> bool:
    """The Cubature artifacts dev/cc.lock.json pins, built at its commit in a worktree of the
    Cubature checkout (whatever that checkout has out stays as it is)."""
    pins = cubature_pins()
    if all(path.is_file() and sha256(path) == expected for path, expected, _ in pins):
        print("present  Cubature artifacts (dev/cc.lock.json)")
        return True
    pin = load(ROOT / "dev/cc.lock.json")
    if args.check:
        print(
            f"missing  Cubature artifacts: run setup without --check to build {pin['commit'][:12]}"
        )
        return False
    settings = load(SETTINGS) if SETTINGS.is_file() else {}
    commit = pin["commit"]
    print(f"building Cubature at {commit[:12]} from {CUBATURE}", flush=True)
    with built_cubature(commit, pin["repository"], settings) as work:
        place_pinned(cubature_artifacts(work, pins), f"Cubature {commit[:12]}")
    print("built    Cubature artifacts (dev/cc.lock.json)")
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
    ready &= source_pins(args)
    ready &= build_cubature(args)
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
