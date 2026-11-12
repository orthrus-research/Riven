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


def compile_sampler(source_dir, check=SAMPLER):
    """Classes for these sources plus one tools/checks entry point (the sampler by default),
    cached by content hash."""
    base = source_dir / "java" / PACKAGE.replace(".", "/")
    # Worldgen with its subpackages (the column engine), and the contributed kernels.
    java_files = sorted((base / "worldgen").rglob("*.java")) + sorted(
        (base / "contrib").rglob("*.java")
    )
    sampler = check
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


def heap_gib(options: dict) -> int:
    """Heap for one raster: about 140 bytes per pixel and ZGC wants half again as headroom; large
    map windows need more than the 8 GB floor (an 8 km window at one block per pixel takes 18 GB)."""
    pixels = int(options.get("width") or 512) * int(options.get("height") or 512)
    return max(8, -(-pixels * 210 // 2**30) + 4)


def sampler_command(classpath: list[str], heap: int, arguments: list[str]) -> list[str]:
    return [
        str(java()),
        f"-Xmx{heap}G",
        "-XX:+UseZGC",
        "-cp",
        os.pathsep.join(classpath),
        PACKAGE + ".worldgen.TerrainAtlas",
        *arguments,
    ]


def run_logged(command: list[str], log: Path) -> float:
    """Run the sampler, keep its output in log and echo it without progress lines; seconds."""
    started = time.monotonic()
    result = subprocess.run(command, capture_output=True, text=True)
    log.write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0:
        sys.exit(f"sampler failed; see {log}\n" + result.stderr[-2000:])
    lines = [line for line in result.stdout.splitlines() if not line.startswith("rows ")]
    print("\n".join(lines))
    return time.monotonic() - started


def run_sampler(source: Source, out: Path, options: dict, overrides_dir: Path):
    """Run TerrainAtlas with key=value options; overrides precede packaged definitions."""
    tree, classes = prepare(source)
    overrides = write_overrides(tree, source.sets, overrides_dir)
    classpath = [str(p) for p in (overrides, classes, gson()) if p is not None]
    arguments = [f"out={out}"] + [f"{k}={v}" for k, v in options.items() if v is not None]
    out.mkdir(parents=True, exist_ok=True)
    return run_logged(sampler_command(classpath, heap_gib(options), arguments), out / "sampler.log")


def run_batch(classes: Path, windows: list[tuple[Path, dict]], parallel: int, log: Path) -> float:
    """Sample several working-tree windows in one TerrainAtlas JVM, in the listed order and
    `parallel` at a time; windows of one seed and scale share their regional plans. Seconds."""
    spec = {
        "parallel": parallel,
        "windows": [
            {"out": str(out)} | {k: str(v) for k, v in options.items() if v is not None}
            for out, options in windows
        ],
    }
    for out, _ in windows:
        out.mkdir(parents=True, exist_ok=True)
    log.parent.mkdir(parents=True, exist_ok=True)
    batch = log.with_suffix(".json")
    batch.write_text(json.dumps(spec, indent=2), encoding="utf-8")
    # The rasters at once in the heap; the plans they share are counted once per window as before.
    heaps = sorted((heap_gib(options) for _, options in windows), reverse=True)
    heap = sum(heaps[: max(1, parallel)])
    command = sampler_command([str(classes), str(gson())], heap, [f"batch={batch}"])
    return run_logged(command, log)
