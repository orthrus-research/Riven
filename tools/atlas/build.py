"""Compile the production worldgen sources, apply definition overrides and run the sampler."""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / ".local/terrain-atlas"
SAMPLER = ROOT / "tools/checks/TerrainAtlas.java"
PACKAGE = "cc.orthrus.riven"
# Commits before 2026-10-28 keep Riven under these packages (new prefix, old prefix).
LEGACY_PACKAGES = (("cc.orthrus.riven.contrib", "riven.contrib"), (PACKAGE, "supersymmetry.riven"))


@dataclass(frozen=True)
class Source:
    """What to sample: a git ref (None = working tree) plus packaged-definition overrides."""

    ref: str | None = None
    sets: tuple[str, ...] = field(default_factory=tuple)

    @property
    def label(self) -> str:
        label = (self.ref or "worktree").replace("/", "-")
        if self.sets:
            label += "_" + hashlib.sha256("\n".join(self.sets).encode()).hexdigest()[:8]
        return label


def settings():
    return json.loads((ROOT / ".local/settings.json").read_text(encoding="utf-8"))


def java():
    return Path(settings()["java26"])


def gson():
    base = Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson"
    jars = [j for j in sorted(base.rglob("gson-*.jar")) if "sources" not in j.name]
    if not jars:
        raise RuntimeError("Gson not found in the Gradle cache; run riven.ps1 build once")
    return jars[-1]


def source_tree(ref):
    """src/main for the working tree, or a cached git-archive extraction of a commit."""
    if ref is None:
        return ROOT / "src/main"
    commit = subprocess.run(
        ["git", "rev-parse", "--verify", ref + "^{commit}"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    target = CACHE / "refs" / commit
    if not (target / ".complete").exists():
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        archive = subprocess.run(
            ["git", "archive", commit, "src/main"], cwd=ROOT, capture_output=True, check=True
        ).stdout
        subprocess.run(["tar", "-x", "-C", str(target)], input=archive, check=True)
        (target / ".complete").write_text(commit)
    return target / "src/main"


def package_of(source_dir):
    """Riven's package in this tree: cc.orthrus.riven, or supersymmetry.riven before the rename."""
    return PACKAGE if (source_dir / "java/cc/orthrus/riven").is_dir() else "supersymmetry.riven"


def sampler_for(package, sampler=SAMPLER):
    """The sampler source in the tree's package; older trees get a renamed copy."""
    if package == PACKAGE:
        return sampler
    text = sampler.read_text(encoding="utf-8")
    for new, old in LEGACY_PACKAGES:
        text = text.replace(new, old)
    target = (
        CACHE / "legacy-sampler" / hashlib.sha256(text.encode()).hexdigest()[:16] / sampler.name
    )
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    return target


def compile_sampler(source_dir, check=SAMPLER):
    """Classes for these sources plus one tools/checks entry point (the sampler by default),
    cached by content hash."""
    package = package_of(source_dir)
    base = source_dir / "java" / package.replace(".", "/")
    kernels = base if package == PACKAGE else source_dir / "java/riven"
    java_files = sorted((base / "worldgen").glob("*.java"))
    # Trees before 2026-10-29 keep the continents kernel under contribution/.
    for name in ("contrib", "contribution"):
        java_files += sorted((kernels / name).rglob("*.java"))
    sampler = sampler_for(package, check)
    resources = sorted((source_dir / "resources").glob("*.json"))
    digest = hashlib.sha256()
    for path in [*java_files, *resources, sampler]:
        digest.update(path.name.encode())
        digest.update(path.read_bytes())
    classes = CACHE / "build" / digest.hexdigest()[:16]
    if (classes / ".complete").exists():
        return classes
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    argfile = classes / "sources.txt"
    argfile.write_text("\n".join(f'"{p.as_posix()}"' for p in [*java_files, sampler]))
    started = time.monotonic()
    result = subprocess.run(
        [str(java().with_name("javac.exe")), "--release", "25", "-nowarn"]
        + ["-cp", str(gson()), "-d", str(classes), "@" + str(argfile)],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        shutil.rmtree(classes)
        sys.exit("javac failed:\n" + result.stdout + result.stderr)
    for resource in resources:
        shutil.copy2(resource, classes / resource.name)
    (classes / ".complete").write_text("")
    print(f"compiled {len(java_files) + 1} sources in {time.monotonic() - started:.1f}s")
    return classes


def parse_set(spec):
    """'riven-x.json:a.b.0.c=<json value>' -> (file, [keys], value)."""
    try:
        target, value = spec.split("=", 1)
        name, path = target.split(":", 1)
    except ValueError:
        raise SystemExit(f"--set expects FILE.json:path.to.key=VALUE, got {spec!r}") from None
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        parsed = value
    keys: list[str | int] = [int(k) if k.isdigit() else k for k in path.split(".")]
    return name, keys, parsed


def write_overrides(source_dir, sets, target):
    """Patched copies of packaged definitions; they precede the packaged ones on the classpath."""
    if target.exists():
        shutil.rmtree(target)
    if not sets:
        return None
    target.mkdir(parents=True)
    patched: dict[str, object] = {}
    for spec in sets:
        name, keys, value = parse_set(spec)
        if name not in patched:
            original = source_dir / "resources" / name
            if not original.is_file():
                raise SystemExit(f"--set: no packaged definition {name}")
            patched[name] = json.loads(original.read_text(encoding="utf-8"))
        node = patched[name]
        for key in keys[:-1]:
            try:
                node = node[key]  # type: ignore[index]
            except KeyError, IndexError, TypeError:
                raise SystemExit(f"--set: {spec!r} does not address an existing value") from None
        try:
            node[keys[-1]]  # type: ignore[index]
        except KeyError, IndexError, TypeError:
            raise SystemExit(f"--set: {spec!r} does not address an existing value") from None
        node[keys[-1]] = value  # type: ignore[index]
    for name, document in patched.items():
        (target / name).write_text(json.dumps(document, indent=2), encoding="utf-8")
    return target


def prepare(source: Source):
    """(source tree, compiled classes); the classes directory name is the source content hash."""
    tree = source_tree(source.ref)
    return tree, compile_sampler(tree)


def run_sampler(source: Source, out: Path, options: dict, overrides_dir: Path):
    """Run TerrainAtlas with key=value options; overrides precede packaged definitions."""
    tree, classes = prepare(source)
    overrides = write_overrides(tree, source.sets, overrides_dir)
    classpath = [str(p) for p in (overrides, classes, gson()) if p is not None]
    command = [
        str(java()),
        "-Xmx8G",
        "-XX:+UseZGC",
        "-cp",
        os.pathsep.join(classpath),
        package_of(tree) + ".worldgen.TerrainAtlas",
        f"out={out}",
    ] + [f"{k}={v}" for k, v in options.items() if v is not None]
    out.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    result = subprocess.run(command, capture_output=True, text=True)
    (out / "sampler.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0:
        sys.exit(f"sampler failed; see {out / 'sampler.log'}\n" + result.stderr[-2000:])
    lines = [line for line in result.stdout.splitlines() if not line.startswith("rows ")]
    print("\n".join(lines))
    return time.monotonic() - started
