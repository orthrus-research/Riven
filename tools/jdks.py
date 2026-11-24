"""The JDKs Riven's tools build and run with: Temurin 26 for everything (one JDK across our projects, 2026-11-21).

A JDK is this checkout's choice first, ``java<major>`` in ``.local/settings.json`` (a java executable's path),
then the environment CI and Workbench give every job: ``JAVA_HOME_<major>_X64``, else ``JAVA_HOME`` when it is that
major. A clean clone has no settings file, so its jobs use the environment.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / ".local" / "settings.json"
MAJOR = 26
EXE = ".exe" if os.name == "nt" else ""


def major_of(home: Path) -> int | None:
    """The feature release a JDK home reports in its ``release`` file."""
    try:
        for line in (home / "release").read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("JAVA_VERSION="):
                head = line.split("=", 1)[1].strip().strip('"').split(".")[0]
                return int(head) if head.isdigit() else None
    except OSError:
        return None
    return None


def home(major: int = MAJOR) -> Path:
    if SETTINGS.is_file():
        java = json.loads(SETTINGS.read_text(encoding="utf-8")).get(f"java{major}")
        if isinstance(java, str) and Path(java).is_file():
            return Path(java).parent.parent
    for value in (os.environ.get(f"JAVA_HOME_{major}_X64"), os.environ.get("JAVA_HOME")):
        if (
            value
            and (Path(value) / "bin" / f"java{EXE}").is_file()
            and major_of(Path(value)) == major
        ):
            return Path(value)
    raise SystemExit(
        f"No JDK {major}: record java{major} in .local/settings.json or set "
        f"JAVA_HOME_{major}_X64 (Workbench and CI set it)"
    )


def java(major: int = MAJOR) -> Path:
    return home(major) / "bin" / f"java{EXE}"


def javac(major: int = MAJOR) -> Path:
    return home(major) / "bin" / f"javac{EXE}"


def installations() -> list[Path]:
    """Every JDK home this machine provides, one per major (this checkout's settings first, then
    JAVA_HOME_<major>_X64): what Gradle may choose toolchains from. One build per major keeps pinned
    builds byte-reproducible; listing them all lets a build use any toolchain it names (Cleanroom's
    remapper runs on Java 8)."""
    homes: dict[int, Path] = {}
    if SETTINGS.is_file():
        for key, java in json.loads(SETTINGS.read_text(encoding="utf-8")).items():
            if (
                key.startswith("java")
                and key[4:].isdigit()
                and isinstance(java, str)
                and Path(java).is_file()
            ):
                homes.setdefault(int(key[4:]), Path(java).parent.parent)
    for name, value in os.environ.items():
        if name.startswith("JAVA_HOME_") and name.endswith("_X64") and name[10:-4].isdigit():
            home = Path(value)
            if (home / "bin" / f"java{EXE}").is_file():
                homes.setdefault(int(name[10:-4]), home)
    return [homes[major] for major in sorted(homes)]


def installations_property() -> str:
    """The Gradle property that names them, for -D or -P."""
    return "org.gradle.java.installations.paths=" + ",".join(str(home) for home in installations())


def gradle_caches() -> Path:
    """Gradle's dependency caches: GRADLE_USER_HOME's when set (CI keeps one per machine), else the user's."""
    return Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle") / "caches"
