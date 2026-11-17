"""The GPU queue on temporary lock files: no GPU, no game, never the real lease."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_queue as gq


def entry(state, age=0, since=None, pid=None, **extra):
    value = {
        "state": state,
        "updatedUtc": gq.stamp(time.time() - age),
        "pid": os.getpid() if pid is None else pid,
    }
    if since is not None:
        value["waiting_since"] = gq.stamp(since)
    return dict(value, **extra)


class QueueTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.queue = gq.Queue(root / "coordination", root / "cubic-lab/native-measurement.lock")
        patcher = mock.patch.multiple(gq, COOLDOWN=0, POLL=0.02, HEARTBEAT=0.05)
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, lane, value):
        gq.write_json(self.queue.path(lane), value)

    def test_only_a_fresh_heartbeat_from_a_running_process_is_live(self):
        self.assertTrue(gq.live(entry("waiting")))
        self.assertFalse(gq.live(entry("waiting", age=gq.STALE + 1)))
        self.assertFalse(
            gq.live({"state": "waiting", "waiting_since": gq.stamp()}), "hand-written, no heartbeat"
        )
        self.assertFalse(gq.live(entry("waiting", pid=2**31 - 1)), "writer gone")

    def test_turn_order(self):
        at, mine = time.time(), time.time() - 60
        with mock.patch.object(gq, "COOLDOWN", 180):
            self.assertFalse(gq.may_take("a", mine, {}, 100, at)[0], "inside the cooldown")
            self.assertTrue(gq.may_take("a", mine, {}, 180, at)[0])
            ahead = {"b": entry("waiting", since=mine - 30)}
            self.assertFalse(
                gq.may_take("a", mine, ahead, 200, at)[0], "an earlier live waiter goes first"
            )
            self.assertTrue(
                gq.may_take("a", mine, ahead, gq.PASS_AFTER, at)[0],
                "not moving for 10 minutes: passed",
            )
            behind = {"b": entry("waiting", since=mine + 30)}
            self.assertTrue(gq.may_take("a", mine, behind, 200, at)[0])
            dead = {"b": entry("waiting", since=mine - 30, age=gq.STALE + 5)}
            self.assertTrue(
                gq.may_take("a", mine, dead, 200, at)[0], "a stale waiter holds nobody back"
            )
            running = {"b": entry("running")}
            self.assertFalse(gq.may_take("a", mine, running, gq.PASS_AFTER, at)[0])

    def test_checks_read_the_lease_without_taking_it(self):
        self.assertFalse(gq.lease_occupied(self.queue.lock))
        lease = gq.Lease(self.queue.lock, {"name": "holder", "turnToken": "x"})
        try:
            self.assertTrue(gq.lease_occupied(self.queue.lock))
            self.assertEqual((gq.lease_owner(self.queue.lock) or {}).get("name"), "holder")
            with self.assertRaises(OSError):
                gq.Lease(self.queue.lock, {"name": "second"})
        finally:
            lease.close()
        self.assertFalse(gq.lease_occupied(self.queue.lock))

    def test_a_turn_holds_the_lease_for_its_command_and_passes_the_token(self):
        probe = (
            "import json,os,sys; sys.path.insert(0, sys.argv[1]); import gpu_queue as q; from pathlib import Path;"
            "lock=Path(sys.argv[2]); owner=q.lease_owner(lock);"
            "sys.exit(7 if q.lease_occupied(lock) and owner['turnToken']==os.environ[q.TOKEN_ENV]"
            " and os.environ[q.LANE_ENV]=='near-terrain' else 3)"
        )
        code = gq.turn(
            self.queue,
            "near-terrain",
            "test",
            "s-1",
            [sys.executable, "-c", probe, str(Path(gq.__file__).parent), str(self.queue.lock)],
            60,
            log=lambda _: None,
        )
        self.assertEqual(code, 7)
        final = json.loads(self.queue.path("near-terrain").read_text())
        self.assertEqual(
            (final["state"], final["waiting_since"], final["session"]), ("idle", None, "s-1")
        )
        self.assertFalse(gq.lease_occupied(self.queue.lock))
        self.assertIsNotNone(self.queue.last_release())

    def test_a_turn_waits_for_the_holder_and_keeps_its_place(self):
        self.write("near-terrain", {"state": "waiting", "waiting_since": "2026-11-17T01:58:13Z"})
        holder = gq.Lease(self.queue.lock, {"name": "shader-matrix"})
        done: dict[str, int] = {}
        worker = threading.Thread(
            target=lambda: done.setdefault(
                "code",
                gq.turn(
                    self.queue,
                    "near-terrain",
                    "t",
                    None,
                    [sys.executable, "-c", "pass"],
                    60,
                    log=lambda _: None,
                ),
            )
        )
        worker.start()
        time.sleep(0.3)
        waiting = json.loads(self.queue.path("near-terrain").read_text())
        self.assertEqual(
            (waiting["state"], waiting["waiting_since"]), ("waiting", "2026-11-17T01:58:13Z")
        )
        self.assertTrue(gq.live(waiting))
        holder.close()
        worker.join(10)
        self.assertEqual(done.get("code"), 0)

    def test_a_turn_past_its_limit_is_stopped_and_releases(self):
        code = gq.turn(
            self.queue,
            "lod",
            "t",
            None,
            [sys.executable, "-c", "import time; time.sleep(30)"],
            0.5,
            log=lambda _: None,
        )
        self.assertNotEqual(code, 0)
        self.assertFalse(gq.lease_occupied(self.queue.lock))

    def test_stuck_names_stalled_lanes(self):
        old = time.time() - 3600
        self.write(
            "near-terrain",
            {
                "state": "waiting",
                "waiting_since": gq.stamp(old - 60),
                "session": "quadrature-stage-07",
            },
        )
        self.write("shader", entry("running", age=gq.STALE + 60))
        self.write("lod", {"state": "idle"})
        gq.write_json(
            self.queue.directory / "last-release.json", {"utc": gq.stamp(old), "lane": "shader"}
        )
        issues = {i["lane"]: i for i in gq.stuck(self.queue)}
        self.assertEqual(set(issues), {"near-terrain", "shader"})
        self.assertEqual(issues["near-terrain"]["session"], "quadrature-stage-07")
        self.write("near-terrain", entry("waiting", since=old))
        self.assertNotIn(
            "near-terrain", {i["lane"] for i in gq.stuck(self.queue)}, "a live waiter is not stuck"
        )

    def test_a_worktree_uses_the_main_checkouts_lease(self):
        root = Path(self.temp.name)
        main, worktree = root / "main", root / "wt"
        (main / ".git/worktrees/wt").mkdir(parents=True)
        worktree.mkdir()
        (main / ".git/worktrees/wt/commondir").write_text("../..\n", encoding="utf-8")
        (worktree / ".git").write_text(f"gitdir: {main / '.git/worktrees/wt'}\n", encoding="utf-8")
        self.assertEqual(gq.main_checkout(worktree), main.resolve())
        self.assertEqual(gq.main_checkout(main), main)

    def test_detach_keeps_the_commands_own_arguments(self):
        with mock.patch.object(gq.subprocess, "Popen") as popen:
            popen.return_value.pid = 42
            result = gq.detach(
                ["turn", "--lane", "lod", "--detach", "--", "tool", "--detach"], self.queue, "lod"
            )
        command = popen.call_args.args[0]
        self.assertEqual(command[-6:], ["turn", "--lane", "lod", "--", "tool", "--detach"])
        self.assertEqual(result["detached"], 42)


if __name__ == "__main__":
    unittest.main()
