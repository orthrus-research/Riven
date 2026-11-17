"""GPU timeshare: one queue for every thread that needs the native-measurement lease.

    python tools/gpu_queue.py status [--json]
    python tools/gpu_queue.py turn --lane near-terrain --note TEXT [--session NAME] [--detach] -- COMMAND ...
    python tools/gpu_queue.py leave --lane near-terrain
    python tools/gpu_queue.py stuck [--json]

The lease is the OS lock on byte 0 of .local/cubic-lab/native-measurement.lock (Cubature's
cubic_lab.native_measurement_lease). Each lane publishes .local/coordination/<lane>.json:
state idle|waiting|running, waiting_since, a heartbeat (updatedUtc, refreshed every 30 s by the
process that wrote it) and that process's pid.

`turn` does the whole rule in one process: it queues, waits until the lease has been free for the
cooldown (180 s) and no live lane is running or waiting from an earlier time (a waiter ahead that
has not moved for 10 minutes of free lease is passed), takes the lease, and keeps it for the whole
turn: COMMAND and every launcher under it that takes the lease (runtime.py play,
measure_pipeline.py, pregenerate_map.py) join the held lease through CUBIC_LAB_TURN_LEASE instead
of taking it per tour. On exit the lease is released
and the lane goes idle. With --detach it runs as its own process that outlives the shell, the
session and app restarts; its log is under .local/coordination/logs/.

A lane is live only while its heartbeat is under 5 minutes old and its pid runs. A waiting or
running entry whose writer is gone, or that has no heartbeat at all (written by hand), does not
hold anyone back. `stuck` reports what needs a person or a coordinator: a lease left free while a
lane waits with no live process, and lanes marked running whose holder is gone. Exit 1 when it
reports anything.

Checks never take the lock: on Windows they read byte 0, which the holder's lock makes
unreadable. Taking the lock even briefly can make a launch that starts at that moment fail.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main_checkout(root: Path) -> Path:
    """The main checkout of root's repository: root itself, or the one a linked worktree's .git
    file names (as checkouts.main_checkout, without that module's environment side effects)."""
    marker = root / ".git"
    if not marker.is_file():
        return root
    gitdir = Path(marker.read_text(encoding="utf-8").split("gitdir:", 1)[1].strip())
    gitdir = gitdir if gitdir.is_absolute() else (root / gitdir).resolve()
    common = gitdir / "commondir"
    shared = (
        (gitdir / common.read_text(encoding="utf-8").strip()).resolve()
        if common.is_file()
        else gitdir
    )
    return shared.parent


# The lease and the queue are the main checkout's, whichever worktree this runs from: launchers in
# the main checkout take that lock, so a worktree's own .local would be a lock nobody else sees.
LAB = main_checkout(ROOT) / ".local"
LOCK = LAB / "cubic-lab/native-measurement.lock"
QUEUE = LAB / "coordination"
TOKEN_ENV = "CUBIC_LAB_TURN_LEASE"
LANE_ENV = "GPU_QUEUE_LANE"

COOLDOWN = 180  # seconds the lease stays free after a release before anyone takes it
PASS_AFTER = 600  # free that long with the waiter ahead not moving: the next waiter goes
STALE = 300  # a heartbeat older than this is a lane nobody is behind
HEARTBEAT = 30
POLL = 5
LIMIT = 3600  # longest turn; past it the turn's command is stopped and the lease released


def now() -> float:
    return time.time()


def stamp(t: float | None = None) -> str:
    return datetime.fromtimestamp(now() if t is None else t, timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )


def parse(value) -> float | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except OSError, ValueError:
        return None


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    temporary.write_text(json.dumps(value) + "\n", encoding="utf-8")
    # Windows refuses the replace while another process has the file open to read it; that
    # lasts milliseconds, so retry rather than lose a heartbeat.
    for attempt in range(50):
        try:
            os.replace(temporary, path)
            return
        except PermissionError:
            if attempt == 49:
                temporary.unlink(missing_ok=True)
                raise
            time.sleep(0.02)


def pid_alive(pid) -> bool:
    if not isinstance(pid, int) or pid <= 0:
        return False
    if os.name != "nt":
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
        return True
    import ctypes
    from ctypes import wintypes

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.restype = wintypes.HANDLE
    handle = kernel.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not handle:
        return ctypes.get_last_error() == 5  # access denied: it exists
    try:
        code = wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
            return True
        return code.value == 259  # STILL_ACTIVE
    finally:
        kernel.CloseHandle(handle)


# The lease ------------------------------------------------------------------------------------


def lease_occupied(path: Path = LOCK) -> bool:
    try:
        stream = open(path, "rb", buffering=0)
    except OSError:
        return False
    with stream:
        if os.name == "nt":
            try:
                stream.read(1)
                return False
            except OSError:
                return True
        import fcntl

        try:
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            return True
        fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
        return False


def lease_owner(path: Path = LOCK) -> dict | None:
    try:
        with open(path, "rb", buffering=0) as stream:
            stream.seek(1)
            value = json.loads(stream.read(65536).decode("utf-8"))
    except OSError, UnicodeError, ValueError:
        return None
    return value if isinstance(value, dict) else None


class Lease:
    """The lease held by this process for a whole turn. Released by close() or by the OS on exit."""

    def __init__(self, path: Path, owner: dict):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.stream = os.fdopen(os.open(path, os.O_RDWR | os.O_CREAT, 0o600), "r+b", buffering=0)
        try:
            if os.path.getsize(path) == 0:
                self.stream.write(b" ")
            self.stream.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(self.stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(self.stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.stream.close()
            raise
        self.owner = owner
        self.stream.seek(1)
        self.stream.write(json.dumps(owner).encode("utf-8"))
        self.stream.truncate()

    def close(self) -> None:
        if self.stream.closed:
            return
        try:
            # Stamped while still held, so the record says when the lease became free.
            self.stream.seek(1)
            self.stream.write(json.dumps(dict(self.owner, releasedUtc=stamp())).encode("utf-8"))
            self.stream.truncate()
        except OSError:
            pass
        self.stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(self.stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(self.stream.fileno(), fcntl.LOCK_UN)
        finally:
            self.stream.close()


# The queue ------------------------------------------------------------------------------------


class Queue:
    def __init__(self, directory: Path = QUEUE, lock: Path = LOCK):
        self.directory = directory
        self.lock = lock

    def path(self, lane: str) -> Path:
        return self.directory / f"{lane}.json"

    def entries(self) -> dict[str, dict]:
        found = {}
        for path in sorted(self.directory.glob("*.json")) if self.directory.is_dir() else []:
            if path.name.startswith(".") or path.stem in ("last-release",):
                continue
            value = read_json(path)
            if isinstance(value, dict):
                found[path.stem] = value
        return found

    def last_release(self) -> float | None:
        value = read_json(self.directory / "last-release.json") or {}
        return parse(value.get("utc"))

    def record_release(self, lane: str) -> None:
        write_json(self.directory / "last-release.json", {"utc": stamp(), "lane": lane})


def live(entry: dict, at: float | None = None) -> bool:
    """Someone is behind this entry: a fresh heartbeat from a process that still runs."""
    beat = parse(entry.get("updatedUtc"))
    if beat is None or (now() if at is None else at) - beat > STALE:
        return False
    return pid_alive(entry.get("pid")) if "pid" in entry else True


def active(entry: dict) -> bool:
    return str(entry.get("state", "")).startswith(("waiting", "running"))


def free_since(queue: Queue, last_seen_held: float | None) -> float | None:
    """When the lease became free, as far as anything recorded shows; None when nothing does
    (no lock file and no release: it has never been held)."""
    marks = [
        last_seen_held,
        queue.last_release(),
        parse((lease_owner(queue.lock) or {}).get("releasedUtc")),
    ]
    try:
        marks.append(
            os.path.getmtime(queue.lock)
        )  # written on taking, and on release where stamped
    except OSError:
        pass
    known = [m for m in marks if m is not None]
    return max(known) if known else None


def free_for(queue: Queue, last_seen_held: float | None, at: float) -> float:
    since = free_since(queue, last_seen_held)
    return float("inf") if since is None else max(0.0, at - since)


def may_take(
    lane: str, mine: float, entries: dict[str, dict], free_for: float, at: float
) -> tuple[bool, str]:
    """Whether `lane`, waiting since `mine`, may take a lease free for `free_for` seconds."""
    if free_for < COOLDOWN:
        return False, f"lease free {free_for:.0f} s of the {COOLDOWN} s cooldown"
    for name, entry in entries.items():
        if name == lane or not live(entry, at):
            continue
        if str(entry.get("state", "")).startswith("running"):
            return False, f"{name} is running"
        since = parse(entry.get("waiting_since"))
        if str(entry.get("state", "")).startswith("waiting") and since is not None and since < mine:
            if free_for < PASS_AFTER:
                return False, f"{name} has waited since {entry.get('waiting_since')}"
    return True, "your turn"


# Commands -------------------------------------------------------------------------------------


def status(queue: Queue) -> dict:
    at = now()
    occupied = lease_occupied(queue.lock)
    lanes = {}
    for name, entry in queue.entries().items():
        beat = parse(entry.get("updatedUtc"))
        lanes[name] = {
            "state": entry.get("state"),
            "waiting_since": entry.get("waiting_since"),
            "heartbeatAge": None if beat is None else round(at - beat),
            "live": live(entry, at),
            "pid": entry.get("pid"),
            "session": entry.get("session"),
            "note": entry.get("note"),
        }
    free = None if occupied else free_for(queue, None, at)
    return {
        "lease": {
            "occupied": occupied,
            ("owner" if occupied else "lastOwner"): lease_owner(queue.lock),
            "freeForSeconds": None
            if free is None
            else (None if free == float("inf") else round(free)),
        },
        "lanes": lanes,
    }


def stuck(queue: Queue) -> list[dict]:
    report = status(queue)
    issues = []
    free_for = report["lease"]["freeForSeconds"]
    for name, lane in report["lanes"].items():
        if not active({"state": lane["state"]}) or lane["live"]:
            continue
        if str(lane["state"]).startswith("running"):
            issues.append(
                {
                    "lane": name,
                    "session": lane["session"],
                    "problem": "marked running but its holder is gone",
                    "note": lane["note"],
                }
            )
        elif free_for is not None and free_for >= PASS_AFTER:
            issues.append(
                {
                    "lane": name,
                    "session": lane["session"],
                    "problem": f"waiting with no live process while the lease has been free {free_for // 60} min",
                    "note": lane["note"],
                }
            )
    owner = report["lease"].get("owner") or {}
    started = parse(owner.get("startedUtc"))
    if report["lease"]["occupied"] and started and now() - started > LIMIT + 600:
        issues.append(
            {
                "lane": owner.get("lane"),
                "session": None,
                "problem": f"lease held {round((now() - started) / 60)} min by {owner.get('name')}",
            }
        )
    return issues


def leave(queue: Queue, lane: str) -> None:
    entry = read_json(queue.path(lane)) or {}
    entry.update(state="idle", waiting_since=None, updatedUtc=stamp(), pid=os.getpid())
    write_json(queue.path(lane), entry)


def detach(argv: list[str], queue: Queue, lane: str) -> dict:
    logs = queue.directory / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    log = logs / f"{lane}-{time.strftime('%Y%m%d-%H%M%S')}.log"
    split = argv.index("--") if "--" in argv else len(argv)
    own = list(argv[:split])
    own.remove("--detach")  # only this tool's flag; the turn's command keeps all of its arguments
    command = [sys.executable, "-B", str(Path(__file__).resolve())] + own + list(argv[split:])
    flags = 0
    if os.name == "nt":
        flags = (
            subprocess.DETACHED_PROCESS
            | subprocess.CREATE_NEW_PROCESS_GROUP
            | subprocess.CREATE_NO_WINDOW
        )
    with open(log, "w", encoding="utf-8") as stream:
        kwargs = dict(
            stdin=subprocess.DEVNULL,
            stdout=stream,
            stderr=subprocess.STDOUT,
            cwd=str(ROOT),
            close_fds=True,
        )
        try:
            # Outside the launching shell's job, so closing that shell or the app does not end the turn.
            process = subprocess.Popen(
                command,
                creationflags=flags | getattr(subprocess, "CREATE_BREAKAWAY_FROM_JOB", 0),
                **kwargs,
            )
            breakaway = os.name == "nt"
        except OSError:
            process = subprocess.Popen(command, creationflags=flags, **kwargs)
            breakaway = False
    return {"detached": process.pid, "log": str(log), "leftJob": breakaway}


class Heartbeat(threading.Thread):
    def __init__(self, beat):
        super().__init__(daemon=True)
        self.beat = beat
        self.stopped = threading.Event()

    def run(self) -> None:
        while not self.stopped.wait(HEARTBEAT):
            try:
                self.beat()
            except OSError as error:  # one missed beat is harmless; a dead heartbeat thread is not
                print(f"{stamp()} heartbeat write failed: {error}", file=sys.stderr, flush=True)


def stop_tree(process: subprocess.Popen) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)], capture_output=True)
    else:
        process.kill()


def turn(
    queue: Queue,
    lane: str,
    note: str,
    session: str | None,
    command: list[str],
    limit: float,
    log=print,
) -> int:
    path = queue.path(lane)
    earlier = read_json(path) or {}
    # A lane that was already waiting keeps its place: a restarted turn is the same request.
    since = (
        earlier.get("waiting_since")
        if str(earlier.get("state", "")).startswith("waiting")
        else None
    )
    mine = parse(since) or now()
    entry = {
        "state": "waiting",
        "waiting_since": stamp(mine),
        "note": note,
        "session": session,
        "command": command,
        "pid": os.getpid(),
        "updatedUtc": stamp(),
    }
    lock_entry = threading.Lock()

    def publish(**changes) -> None:
        with lock_entry:
            entry.update(changes, updatedUtc=stamp())
            write_json(path, entry)

    publish()
    heartbeat = Heartbeat(publish)
    heartbeat.start()
    held_seen = None
    reason = None
    log(f"{stamp()} queued {lane} (waiting since {entry['waiting_since']})")
    lease = None
    try:
        while lease is None:
            at = now()
            if lease_occupied(queue.lock):
                held_seen = at
                why = "lease held by " + str((lease_owner(queue.lock) or {}).get("name"))
            else:
                ok, why = may_take(lane, mine, queue.entries(), free_for(queue, held_seen, at), at)
                if ok:
                    token = uuid.uuid4().hex
                    try:
                        lease = Lease(
                            queue.lock,
                            {
                                "pid": os.getpid(),
                                "name": f"turn-{lane}",
                                "lane": lane,
                                "startedUtc": datetime.now(timezone.utc).isoformat(),
                                "turnToken": token,
                                "note": note,
                            },
                        )
                        break
                    except OSError:
                        held_seen = now()
                        why = "another job took the lease first"
            if why != reason:
                log(f"{stamp()} waiting: {why}")
                reason = why
            time.sleep(POLL)
        publish(state="running", startedUtc=stamp())
        log(f"{stamp()} running {lane}: {' '.join(command)}")
        env = dict(os.environ, **{TOKEN_ENV: token, LANE_ENV: lane})
        process = subprocess.Popen(command, env=env)
        try:
            code = process.wait(timeout=limit)
        except subprocess.TimeoutExpired:
            log(f"{stamp()} turn passed its {limit:.0f} s limit; stopping its command")
            stop_tree(process)
            code = process.wait()
        log(f"{stamp()} turn finished with exit {code}")
        return code
    finally:
        heartbeat.stopped.set()
        if lease is not None:
            lease.close()
            queue.record_release(lane)
            log(f"{stamp()} released the lease")
        publish(
            state="idle",
            waiting_since=None,
            releasedUtc=stamp() if lease is not None else entry.get("releasedUtc"),
        )


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = parser.add_subparsers(dest="action", required=True)
    show = commands.add_parser("status", help="The lease and every lane")
    show.add_argument("--json", action="store_true")
    report = commands.add_parser("stuck", help="What needs a nudge; exit 1 when anything does")
    report.add_argument("--json", action="store_true")
    go = commands.add_parser(
        "turn", help="Queue, wait for the turn, hold the lease while COMMAND runs"
    )
    go.add_argument("--lane", required=True, help="near-terrain, shader, lod, ...")
    go.add_argument("--note", default="", help="What the turn is for")
    go.add_argument("--session", help="Which session to wake if this lane stalls")
    go.add_argument(
        "--limit", type=float, default=LIMIT, help=f"Longest turn in seconds (default {LIMIT})"
    )
    go.add_argument(
        "--detach", action="store_true", help="Run as a process of its own and return at once"
    )
    go.add_argument("command", nargs=argparse.REMAINDER)
    out = commands.add_parser("leave", help="Mark a lane idle (withdraw a request)")
    out.add_argument("--lane", required=True)
    args = parser.parse_args(argv)
    queue = Queue()
    if args.action == "status":
        result = status(queue)
        if args.json:
            print(json.dumps(result, indent=2))
        else:
            lease = result["lease"]
            print(
                "lease: "
                + (
                    "HELD by " + json.dumps((lease.get("owner") or {}).get("name"))
                    if lease["occupied"]
                    else f"free {lease['freeForSeconds']} s (last: {(lease.get('lastOwner') or {}).get('name')})"
                )
            )
            for name, lane in result["lanes"].items():
                print(
                    f"  {name:<14} {str(lane['state']):<9} {'live' if lane['live'] else 'stale':<6} "
                    f"since {lane['waiting_since'] or '-':<21} beat {lane['heartbeatAge']} s  {lane['note'] or ''}"
                )
        return 0
    if args.action == "stuck":
        issues = stuck(queue)
        print(
            json.dumps(issues, indent=2)
            if args.json
            else "\n".join(f"{i['lane']}: {i['problem']}" for i in issues) or "ok"
        )
        return 1 if issues else 0
    if args.action == "leave":
        leave(queue, args.lane)
        return 0
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("turn needs a command after --")
    if args.detach:
        print(json.dumps(detach(argv, queue, args.lane)))
        return 0
    return turn(
        queue,
        args.lane,
        args.note,
        args.session,
        command,
        args.limit,
        log=lambda line: print(line, flush=True),
    )


if __name__ == "__main__":
    raise SystemExit(main())
