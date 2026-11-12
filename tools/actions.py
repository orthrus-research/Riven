"""Run local build/test actions for Riven and Cubature, like a small local CI.

Workflows and jobs are defined in `tools/actions.toml`. Jobs run concurrently within a core and
memory budget, each after the jobs it needs (`--jobs 1` runs them one at a time); a job's steps
run in order. Each step's output goes to `.local/actions/<run-id>/<job>/<step>.log` and the run
ends with `summary.json`. A job is skipped when a job it needs failed. Only one run at a time.

A job that lists `inputs` is a pure check: when the files they match, its steps and the JDK are
exactly those of an earlier pass, that pass stands (`.local/actions/cache`) and the job does not
run. `--no-cache` runs it anyway.

Each summary records the Riven and Cubature trees the run tested: the trees their working trees
would commit, so a pass recorded before a commit names that commit's tree. `--if-needed` skips the
jobs that already passed for the current trees; tools/push_gate.py reads the same record.
`quick` stands for the ci jobs that the changes since HEAD can affect, by the [quick] rules.

  python tools/actions.py --list
  python tools/actions.py ci                 # quality, builds, golden atlas digests
  python tools/actions.py ci --if-needed     # only the ci jobs not yet passed for these trees
  python tools/actions.py quick              # before a commit: the ci jobs its changes can affect
  python tools/actions.py full               # ci plus a native smoke capture
  python tools/actions.py riven-build atlas-golden --keep-going
  python tools/actions.py ci --dry-run
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import tomllib
from pathlib import Path, PurePosixPath
from typing import Any

from checkouts import CUBATURE

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "tools/actions.toml"
RUNS = ROOT / ".local/actions"
REPOSITORIES = (("riven", ROOT), ("cubature", CUBATURE))
# A hook exports these for its own repository; they would point git at it from the other one.
GIT_LOCATION = {"GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_PREFIX"}


def load():
    with CONFIG.open("rb") as stream:
        return tomllib.load(stream)


def resolve(config, targets):
    """Expand workflows to jobs and add dependencies, preserving declaration order."""
    jobs, workflows = config["jobs"], config.get("workflows", {})
    wanted = []
    for target in targets:
        if target in workflows:
            wanted += workflows[target]
        elif target in jobs:
            wanted.append(target)
        else:
            sys.exit(f"Unknown workflow or job: {target} (see --list)")
    ordered = []

    def visit(name, path=()):
        if name in path:
            sys.exit("Dependency cycle: " + " -> ".join(path + (name,)))
        if name in ordered:
            return
        for need in jobs[name].get("needs", []):
            visit(need, path + (name,))
        ordered.append(name)

    for name in wanted:
        visit(name)
    return ordered


def placeholders(config, run_id):
    values = {
        "python": sys.executable,
        "run_id": run_id,
        "frozen": str(ROOT / ".local/frozen" / run_id),
        "riven": str(ROOT),
        "cubature": str(CUBATURE),
    }
    for key, relative in config.get("interpreters", {}).items():
        values[key] = str(ROOT / relative)
    return values


def expand(arguments, values):
    return [argument.format(**values) for argument in arguments]


def missing_interpreter(command):
    first = Path(command[0])
    return first.is_absolute() and not first.is_file()


TIMING = "timing "


def run_step(command, log, verbose, prefix=""):
    """(exit code, seconds, timing records): a step may print `timing {json}` lines, which
    tools/ci_timing.py reads from the run summary."""
    started = time.monotonic()
    timings = []
    with log.open("w", encoding="utf-8", errors="replace") as stream:
        stream.write("$ " + " ".join(command) + "\n\n")
        stream.flush()
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            env={**os.environ, "PYTHONUNBUFFERED": "1"},
        )
        assert process.stdout is not None
        for line in process.stdout:
            stream.write(line)
            if line.startswith(TIMING):
                try:
                    timings.append(json.loads(line[len(TIMING) :]))
                except ValueError:
                    pass
            if verbose:
                print(f"    [{prefix}] " + line if prefix else "    " + line, end="", flush=True)
        code = process.wait()
    return code, time.monotonic() - started, timings


def tail(log, lines=15):
    text = log.read_text(encoding="utf-8", errors="replace").splitlines()
    return "\n".join("    | " + line for line in text[-lines:])


def git(repo: Path, *arguments: str, index: Path | None = None) -> str:
    environment = {k: v for k, v in os.environ.items() if k not in GIT_LOCATION}
    if index is not None:
        environment["GIT_INDEX_FILE"] = str(index)
    return subprocess.run(
        ["git", *arguments], cwd=repo, env=environment, capture_output=True, text=True, check=True
    ).stdout.strip()


def working_tree(repo: Path) -> str | None:
    """The tree `git add --all` would commit in repo, through a scratch copy of its index so the
    real one is untouched; None without a checkout."""
    if not (repo / ".git").exists():
        return None
    index = repo / git(repo, "rev-parse", "--git-path", "index")
    with tempfile.TemporaryDirectory() as scratch:
        scratch_index = Path(scratch) / "index"
        if index.is_file():
            # The copy keeps the index's file stamps, so only changed files are read again.
            shutil.copyfile(index, scratch_index)
        git(repo, "add", "--all", index=scratch_index)
        return git(repo, "write-tree", index=scratch_index)


def trees() -> dict[str, str | None]:
    """The Riven and Cubature trees a run tests; None for one git could not read."""
    result: dict[str, str | None] = {}
    for name, repo in REPOSITORIES:
        try:
            result[name] = working_tree(repo)
        except OSError, subprocess.CalledProcessError:
            result[name] = None
    return result


def passed_jobs(runs: Path = RUNS) -> dict[tuple[str, str], set[str]]:
    """The jobs passed so far, by the (Riven, Cubature) trees they ran on. A run counts when it
    recorded both trees and neither changed while it ran."""
    passed: dict[tuple[str, str], set[str]] = {}
    for path in sorted(runs.glob("*/summary.json")):
        try:
            summary = json.loads(path.read_text(encoding="utf-8"))
        except OSError, ValueError:
            continue
        tested = summary.get("trees") or {}
        if summary.get("treesChanged", True) or not (
            tested.get("riven") and tested.get("cubature")
        ):
            continue
        jobs = passed.setdefault((tested["riven"], tested["cubature"]), set())
        jobs.update(n for n, r in summary.get("jobs", {}).items() if r.get("status") == "passed")
    return passed


def affected(rules: list[dict[str, Any]], paths: list[str], everything: list[str]) -> set[str]:
    """The jobs of the first rule each path matches; every job for a path no rule matches."""
    jobs: set[str] = set()
    for path in paths:
        rule = next(
            (r for r in rules if any(PurePosixPath(path).full_match(p) for p in r["paths"])), None
        )
        jobs.update(everything if rule is None else rule["jobs"])
    return jobs


def quick_jobs(config, tested: dict[str, str | None]) -> tuple[list[str], dict[str, int]]:
    """The ci jobs that each repository's changes since its HEAD can affect, and how many paths
    changed in each; every ci job when a tree could not be read."""
    ci = config["workflows"]["ci"]
    wanted: set[str] = set()
    changed = {}
    for name, repo in REPOSITORIES:
        tree = tested[name]
        if tree is None:
            wanted.update(ci)
            continue
        paths = git(repo, "diff", "--no-renames", "--name-only", "HEAD^{tree}", tree).splitlines()
        changed[name] = len(paths)
        wanted |= affected(config["quick"][name], paths, ci)
    return [job for job in ci if job in wanted], changed


def physical_memory_gib() -> float:
    """Installed memory in GiB, or 16 when it cannot be read."""
    try:
        if sys.platform == "win32":
            import ctypes

            class Status(ctypes.Structure):
                _fields_ = [
                    ("length", ctypes.c_ulong),
                    ("load", ctypes.c_ulong),
                    ("total", ctypes.c_ulonglong),
                    ("available", ctypes.c_ulonglong),
                    ("totalPage", ctypes.c_ulonglong),
                    ("availablePage", ctypes.c_ulonglong),
                    ("totalVirtual", ctypes.c_ulonglong),
                    ("availableVirtual", ctypes.c_ulonglong),
                    ("availableExtended", ctypes.c_ulonglong),
                ]

            status = Status()
            status.length = ctypes.sizeof(Status)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                return status.total / 2**30
            return 16.0
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 2**30
    except OSError, ValueError, AttributeError:
        return 16.0


# A job's claim on the machine while it runs, from tools/actions.toml: `share` of the cores
# (default DEFAULT_SHARE), `memory` in GiB (default DEFAULT_MEMORY) and `locks`, names no two
# running jobs may hold at once (a Gradle project, say). `native` jobs run alone.
DEFAULT_SHARE = 0.25
DEFAULT_MEMORY = 4.0
# Leave room for the OS and whatever else is open.
MEMORY_FRACTION = 0.75


class Budget:
    """Which jobs may start beside the running ones: their core shares and memory must fit and
    their locks be free. A job always starts when nothing else runs, however large."""

    def __init__(self, jobs: dict[str, Any], concurrency: int, memory: float | None = None):
        self.jobs = jobs
        self.concurrency = concurrency
        self.memory = (physical_memory_gib() if memory is None else memory) * MEMORY_FRACTION
        self.running: set[str] = set()

    def claim(self, name: str) -> tuple[float, float, set[str]]:
        job = self.jobs[name]
        if job.get("native"):
            return 1.0, self.memory, {"*"}
        return (
            float(job.get("share", DEFAULT_SHARE)),
            float(job.get("memory", DEFAULT_MEMORY)),
            set(job.get("locks", [])),
        )

    def fits(self, name: str) -> bool:
        if not self.running:
            return True
        if self.concurrency and len(self.running) >= self.concurrency:
            return False
        share, memory, locks = self.claim(name)
        held: set[str] = set()
        for other in self.running:
            s, m, held_locks = self.claim(other)
            share, memory = share + s, memory + m
            held |= held_locks
        if "*" in held or ("*" in locks) or (locks & held):
            return False
        return share <= 1.0 + 1e-9 and memory <= self.memory


def run_job(name, job, values, run_dir, verbose) -> dict[str, Any]:
    """Run one job's steps in order, stopping at the first failure."""
    steps, status = [], "passed"
    for index, step in enumerate(job["steps"], 1):
        command = expand(step["run"], values)
        log = run_dir / name / f"{index:02d}-{step['name'].replace(' ', '-')}.log"
        log.parent.mkdir(parents=True, exist_ok=True)
        if missing_interpreter(command):
            log.write_text(f"Missing interpreter: {command[0]}\n", encoding="utf-8")
            code, seconds, timings = 127, 0.0, []
        else:
            code, seconds, timings = run_step(command, log, verbose, name)
        ok = code == 0
        steps.append(
            {
                "name": step["name"],
                "exitCode": code,
                "seconds": round(seconds, 1),
                "log": str(log),
            }
            | ({"timing": timings} if timings else {})
        )
        line = f"  {'ok  ' if ok else 'FAIL'} {name} / {step['name']:<22} {seconds:7.1f} s"
        print(line + ("\n" + tail(log) if not ok else ""), flush=True)
        if not ok:
            status = "failed"
            break
    return {"status": status, "steps": steps}


def run_jobs(
    config, order, values, run_dir, results, keep_going, verbose, concurrency, use_cache=True
):
    """Run the ordered jobs, several at once within the budget, each after the jobs it needs.
    Jobs start in priority order (`priority`, higher first, then declaration order), so long
    jobs begin early. After a failure no new job starts unless keep_going; results fills as jobs
    end, and every job not run is recorded as skipped."""
    import concurrent.futures

    jobs = config["jobs"]
    budget = Budget(jobs, concurrency)
    pending = sorted(order, key=lambda n: (-jobs[n].get("priority", 0), order.index(n)))
    active: dict[concurrent.futures.Future, str] = {}
    keys: dict[str, str | None] = {}
    begun: dict[str, float] = {}
    clock = time.monotonic()
    workers = concurrency or max(1, len(order))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        while pending or active:
            failed = any(r["status"] == "failed" for r in results.values())
            for name in list(pending):
                needs = jobs[name].get("needs", [])
                blocked = [n for n in needs if n in results and results[n]["status"] != "passed"]
                if blocked or (failed and not keep_going):
                    pending.remove(name)
                    reason = f"needs {', '.join(blocked)}" if blocked else "after failure"
                    results[name] = {"status": "skipped", "reason": reason}
                    print(f"- {name:<16} skipped ({reason})", flush=True)
                    continue
                if any(n not in results for n in needs if n in order):
                    continue
                if not budget.fits(name):
                    continue
                pending.remove(name)
                key = input_key(name, jobs[name])
                earlier = cached_pass(name, key) if use_cache else None
                if earlier is not None:
                    # The same inputs passed before, determinism checks included; reuse it.
                    results[name] = {"status": "passed", "cached": earlier["runId"], "steps": []}
                    results[name]["inputKey"] = key
                    print(
                        f"= {name:<16} passed for these inputs in run {earlier['runId']}",
                        flush=True,
                    )
                    continue
                budget.running.add(name)
                print(f"> {name}", flush=True)
                keys[name] = key
                begun[name] = time.monotonic() - clock
                active[pool.submit(run_job, name, jobs[name], values, run_dir, verbose)] = name
            if not active:
                continue
            done, _ = concurrent.futures.wait(
                active, return_when=concurrent.futures.FIRST_COMPLETED
            )
            for future in done:
                name = active.pop(future)
                budget.running.discard(name)
                try:
                    results[name] = future.result()
                except Exception as error:  # a runner bug, not a step failure
                    results[name] = {"status": "failed", "steps": [], "error": repr(error)}
                if keys[name] is not None:
                    results[name]["inputKey"] = keys[name]
                # Seconds into the run, to show which jobs overlapped.
                results[name]["begun"] = round(begun[name], 1)
                results[name]["ended"] = round(time.monotonic() - clock, 1)
                # Only inputs that held still for the whole job may vouch for it later.
                if keys[name] == input_key(name, jobs[name]):
                    record_pass(name, keys[name], values["run_id"], results[name])


CACHE = RUNS / "cache"


def environment_identity() -> list[str]:
    """What else a pure check's verdict depends on: the JDK build that runs it and the Gson it
    compiles against (tools/atlas/build.py)."""
    identity = []
    try:
        settings = json.loads((ROOT / ".local/settings.json").read_text(encoding="utf-8"))
        java = Path(settings["java26"])
        identity.append((java.parent.parent / "release").read_text(encoding="utf-8"))
    except OSError, ValueError, KeyError:
        identity.append("java: unknown")
    gson = Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson"
    identity += sorted(p.name for p in gson.rglob("gson-*.jar")) if gson.is_dir() else []
    return identity


def input_key(name: str, job: dict[str, Any], root: Path = ROOT) -> str | None:
    """A digest of everything a cacheable job reads: its step templates, the files its `inputs`
    globs match (by path and content) and the environment; None for a job without inputs."""
    patterns = job.get("inputs")
    if not patterns:
        return None
    digest = hashlib.sha256()
    digest.update(json.dumps([name, job["steps"], patterns], sort_keys=True).encode())
    files = sorted({p for pattern in patterns for p in root.glob(pattern) if p.is_file()})
    for path in files:
        digest.update(path.relative_to(root).as_posix().encode() + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    for line in environment_identity():
        digest.update(line.encode() + b"\0")
    return digest.hexdigest()


def cached_pass(name: str, key: str | None) -> dict[str, Any] | None:
    if key is None:
        return None
    try:
        return json.loads((CACHE / f"{name}-{key}.json").read_text(encoding="utf-8"))
    except OSError, ValueError:
        return None


def record_pass(name: str, key: str | None, run_id: str, result: dict[str, Any]) -> None:
    if key is None or result["status"] != "passed":
        return
    CACHE.mkdir(parents=True, exist_ok=True)
    record = {"job": name, "key": key, "runId": run_id, "steps": result["steps"]}
    (CACHE / f"{name}-{key}.json").write_text(json.dumps(record, indent=2), encoding="utf-8")


def acquire_lock():
    RUNS.mkdir(parents=True, exist_ok=True)
    lock = RUNS / ".lock"
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        owner = lock.read_text(encoding="utf-8", errors="replace").strip()
        sys.exit(f"Another actions run holds {lock} ({owner}); delete it if that run is gone.")
    os.write(descriptor, f"pid {os.getpid()} since {time.strftime('%H:%M:%S')}".encode())
    os.close(descriptor)
    return lock


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("targets", nargs="*", help="workflows and/or jobs")
    parser.add_argument("--list", action="store_true", help="show workflows and jobs")
    parser.add_argument("--dry-run", action="store_true", help="print the plan only")
    parser.add_argument("--keep-going", action="store_true", help="continue after a failure")
    parser.add_argument("--verbose", "-v", action="store_true", help="stream step output")
    parser.add_argument(
        "--no-cache",
        action="store_true",
        help="run cacheable jobs even when their exact inputs passed before",
    )
    parser.add_argument(
        "--jobs",
        "-j",
        type=int,
        default=0,
        help="jobs at once (default: as many as the core and memory budget allows; 1 = serial)",
    )
    parser.add_argument(
        "--if-needed",
        action="store_true",
        help="skip jobs that already passed for the current Riven and Cubature trees",
    )
    args = parser.parse_args()
    config = load()
    if args.list or not args.targets:
        print("Workflows:")
        for name, jobs in config.get("workflows", {}).items():
            print(f"  {name:<16} {', '.join(jobs)}")
        print(f"  {'quick':<16} the ci jobs the changes since HEAD can affect ([quick] rules)")
        print("Jobs:")
        for name, job in config["jobs"].items():
            flags = " [native]" if job.get("native") else ""
            print(f"  {name:<16} {job.get('description', '')}{flags}")
        return
    targets, tested = args.targets, None
    if "quick" in targets:
        tested = trees()
        jobs, changed = quick_jobs(config, tested)
        counts = ", ".join(f"{count} {name}" for name, count in changed.items())
        print(f"quick: {counts} paths changed since HEAD; {', '.join(jobs) or 'no ci job'} to run")
        targets = [target for target in targets if target != "quick"] + jobs
        if not targets:
            return
    order = resolve(config, targets)
    if args.if_needed:
        tested = tested or trees()
        done = passed_jobs().get((tested["riven"] or "", tested["cubature"] or ""), set())
        if any(name in done for name in order):
            print("Already passed for these trees: " + ", ".join(n for n in order if n in done))
        remaining = [name for name in order if name not in done]
        if not remaining:
            return
        # Keep the jobs a remaining job needs: their artifacts are frozen under this run's id.
        order = resolve(config, remaining)
    run_id = time.strftime("%Y%m%d-%H%M%S")
    values = placeholders(config, run_id)
    if args.dry_run:
        for name in order:
            print(f"{name}:")
            for step in config["jobs"][name]["steps"]:
                print("  $ " + " ".join(expand(step["run"], values)))
        return
    lock = acquire_lock()
    tested = tested or trees()
    run_dir = RUNS / run_id
    results: dict[str, dict[str, Any]] = {}
    started = time.monotonic()
    print(f"run {run_id}: {', '.join(order)}  (logs in {run_dir.relative_to(ROOT)})", flush=True)
    try:
        run_jobs(
            config,
            order,
            values,
            run_dir,
            results,
            args.keep_going,
            args.verbose,
            args.jobs,
            not args.no_cache,
        )
    finally:
        lock.unlink(missing_ok=True)
        summary = {
            "runId": run_id,
            "targets": args.targets,
            "trees": tested,
            "treesChanged": trees() != tested,
            "seconds": round(time.monotonic() - started, 1),
            "jobs": results,
        }
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    failed = [n for n, r in results.items() if r["status"] == "failed"]
    skipped = [n for n, r in results.items() if r["status"] == "skipped"]
    verdict = "FAILED" if failed else "PASSED"
    print(
        f"{verdict} in {summary['seconds']} s; failed: {failed or 'none'}; skipped: {skipped or 'none'}"
    )
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
