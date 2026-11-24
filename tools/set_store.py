"""The set this checkout builds from, and the third-party files it names, read from Workbench's Store.

A Workbench job names the set's export and the Store in GRADLE_OPTS (the ``set.dir`` and ``set.store`` Gradle
properties), as it does for every ``{gradle}`` step. By hand they come from the block ``wb setup`` writes in the
Gradle home (``set.home``, ``set.store``) and the ``.set`` file ``wb work sync`` keeps above the checkout, which is
how settings.gradle finds the set too. A file is handed out only when its bytes have the SHA-256 the set gives it.
"""

from __future__ import annotations

import functools
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def gradle_property(name: str) -> str | None:
    """A Gradle property as a build started here sees it: GRADLE_OPTS, ORG_GRADLE_PROJECT_<name>, then
    gradle.properties in the Gradle home."""
    for option in os.environ.get("GRADLE_OPTS", "").split():
        for prefix in (f"-Dorg.gradle.project.{name}=", f"-P{name}="):
            if option.startswith(prefix):
                return option[len(prefix) :]
    if os.environ.get(f"ORG_GRADLE_PROJECT_{name}"):
        return os.environ[f"ORG_GRADLE_PROJECT_{name}"]
    home = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
    try:
        lines = (home / "gradle.properties").read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    for line in lines:
        key, separator, value = line.partition("=")
        if separator and key.strip() == name:
            return value.strip()
    return None


def set_dir(root: Path = ROOT) -> Path:
    """The folder of the set's export: the set.dir property, else set.home and the nearest .set above root."""
    folder = gradle_property("set.dir")
    if folder is None:
        mark = next(
            (
                d / ".set"
                for d in (root.resolve(), *root.resolve().parents)
                if (d / ".set").is_file()
            ),
            None,
        )
        home = gradle_property("set.home")
        if mark is None or home is None:
            raise RuntimeError(
                "No set for this checkout: run `wb work sync` (or `wb setup`), or pass "
                "-Dorg.gradle.project.set.dir=<the set's export> in GRADLE_OPTS"
            )
        folder = f"{home}/{json.loads(mark.read_text(encoding='utf-8'))['set']}"
    return Path(folder)


@functools.cache
def document(root: Path = ROOT) -> dict:
    """set.json of the set this checkout builds from."""
    path = set_dir(root) / "set.json"
    if not path.is_file():
        raise RuntimeError(f"The set has no set.json at {path}: run `wb work sync`")
    return json.loads(path.read_text(encoding="utf-8"))


def third_file(entry: dict) -> tuple[str, str, str | None]:
    """A third entry's runtime file as (file name, SHA-256, group/artifact/version folder of a Maven module, if it
    is one): a module's runtime file is <artifact>-<version>.jar, a file without a coordinate names its own."""
    if entry.get("module"):
        group, artifact, version = str(entry["module"]).split(":")
        return (
            f"{artifact}-{version}.jar",
            str(entry["files"]["runtime"]),
            f"{group}/{artifact}/{version}",
        )
    return str(entry["file"]), str(entry["sha256"]), None


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


@functools.cache
def third(name: str, root: Path = ROOT) -> Path:
    """The runtime file of the set's third entry ``name``: from the Store by its SHA-256, else (by hand, with no
    Store) the same bytes from the Gradle cache; refused when the bytes differ from the set's."""
    entries = document(root).get("third") or {}
    if name not in entries:
        raise RuntimeError(
            f"The set has no third entry {name} (it has: {', '.join(sorted(entries)) or 'none'}): "
            "run `wb work sync` once it has landed"
        )
    file_name, sha256, module = third_file(entries[name])
    candidates = []
    store = gradle_property("set.store")
    if store:
        candidates.append(Path(store) / "files" / sha256 / file_name)
    if module:
        group, artifact, version = module.split("/")
        caches = Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle") / "caches"
        candidates += sorted(
            (caches / "modules-2/files-2.1" / group / artifact / version).glob(f"*/{file_name}")
        )
    for path in candidates:
        if path.is_file():
            found = _sha256(path)
            if found != sha256:
                raise RuntimeError(f"{path} is {found[:12]}, not the set's {name} {sha256[:12]}")
            return path
    raise RuntimeError(
        f"The Store has no {file_name} ({name} {sha256[:12]}): give the Store as set.store "
        "(a Workbench job does; by hand, `wb setup` writes it in the Gradle home)"
    )
