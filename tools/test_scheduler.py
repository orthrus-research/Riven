"""actions.py running jobs concurrently within a budget, and reusing passes for unchanged inputs."""

import sys
import time
from pathlib import Path
from unittest import mock

import actions
from test_actions import Scratch


def sleeper(seconds, code=0):
    return [sys.executable, "-c", f"import time, sys; time.sleep({seconds}); sys.exit({code})"]


def job(command, **claim):
    return {"steps": [{"name": "step", "run": command}]} | claim


class SchedulerTests(Scratch):
    def run_jobs(self, jobs, order=None, concurrency=0, keep_going=False, use_cache=False):
        results: dict = {}
        config = {"jobs": jobs}
        values = {"run_id": "test"}
        with mock.patch.object(actions, "physical_memory_gib", return_value=64.0):
            actions.run_jobs(
                config,
                order or list(jobs),
                values,
                self.home,
                results,
                keep_going,
                False,
                concurrency,
                use_cache,
            )
        return results

    def test_independent_jobs_overlap_and_needs_wait(self):
        jobs = {
            "a": job(sleeper(0.6)),
            "b": job(sleeper(0.6)),
            "c": job(sleeper(0), needs=["a"]),
        }
        started = time.monotonic()
        results = self.run_jobs(jobs)
        self.assertLess(time.monotonic() - started, 1.1)
        self.assertEqual({r["status"] for r in results.values()}, {"passed"})

    def test_serial_with_one_job_at_a_time(self):
        started = time.monotonic()
        self.run_jobs({"a": job(sleeper(0.4)), "b": job(sleeper(0.4))}, concurrency=1)
        self.assertGreaterEqual(time.monotonic() - started, 0.8)

    def test_a_shared_lock_serializes(self):
        jobs = {"a": job(sleeper(0.4), locks=["g"]), "b": job(sleeper(0.4), locks=["g"])}
        started = time.monotonic()
        self.run_jobs(jobs)
        self.assertGreaterEqual(time.monotonic() - started, 0.8)

    def test_failure_skips_dependents_and_later_jobs(self):
        jobs = {
            "a": job(sleeper(0, 3)),
            "b": job(sleeper(0), needs=["a"]),
            "c": job(sleeper(0)),
        }
        results = self.run_jobs(jobs, concurrency=1)
        self.assertEqual(results["a"]["status"], "failed")
        self.assertEqual(results["b"], {"status": "skipped", "reason": "needs a"})
        self.assertEqual(results["c"], {"status": "skipped", "reason": "after failure"})

    def test_keep_going_runs_the_rest(self):
        jobs = {"a": job(sleeper(0, 1)), "c": job(sleeper(0))}
        results = self.run_jobs(jobs, concurrency=1, keep_going=True)
        self.assertEqual(results["c"]["status"], "passed")


class BudgetTests(Scratch):
    def test_shares_memory_and_native_jobs(self):
        jobs = {
            "big": {"share": 0.75},
            "small": {"share": 0.25, "memory": 4},
            "more": {},
            "heavy": {"memory": 40},
            "native": {"native": True},
        }
        budget = actions.Budget(jobs, 0, memory=32)
        self.assertTrue(budget.fits("big"))
        budget.running.add("big")
        self.assertTrue(budget.fits("small"))
        budget.running.add("small")
        self.assertFalse(budget.fits("more"))
        budget.running = {"small"}
        self.assertFalse(budget.fits("heavy"))
        self.assertFalse(budget.fits("native"))
        budget.running = set()
        self.assertTrue(budget.fits("heavy"))


class CacheTests(Scratch):
    def setUp(self):
        super().setUp()
        self.patch = mock.patch.object(actions, "CACHE", self.home / "cache")
        self.patch.start()
        self.root = self.home / "tree"
        (self.root / "src").mkdir(parents=True)
        (self.root / "src/a.java").write_text("a", encoding="utf-8")

    def tearDown(self):
        self.patch.stop()
        super().tearDown()

    def test_the_key_follows_inputs_steps_and_paths(self):
        pure = job(["x"], inputs=["src/**/*.java"])
        key = actions.input_key("j", pure, self.root)
        self.assertEqual(key, actions.input_key("j", pure, self.root))
        self.assertIsNone(actions.input_key("j", job(["x"]), self.root))
        self.assertNotEqual(
            key, actions.input_key("j", job(["y"], inputs=pure["inputs"]), self.root)
        )
        (self.root / "src/a.java").write_text("b", encoding="utf-8")
        changed = actions.input_key("j", pure, self.root)
        self.assertNotEqual(key, changed)
        (self.root / "src/b.java").write_text("", encoding="utf-8")
        self.assertNotEqual(changed, actions.input_key("j", pure, self.root))

    def test_a_pass_stands_for_the_same_inputs_and_a_failure_never_does(self):
        jobs = {"pure": job(sleeper(0), inputs=["tools/actions.py"])}
        first = SchedulerTests.run_jobs(self, jobs, use_cache=True)  # type: ignore[arg-type]
        self.assertNotIn("cached", first["pure"])
        second = SchedulerTests.run_jobs(self, jobs, use_cache=True)  # type: ignore[arg-type]
        self.assertEqual(second["pure"]["cached"], "test")
        uncached = SchedulerTests.run_jobs(self, jobs, use_cache=False)  # type: ignore[arg-type]
        self.assertNotIn("cached", uncached["pure"])
        failing = {"bad": job(sleeper(0, 1), inputs=["tools/actions.py"])}
        SchedulerTests.run_jobs(self, failing, use_cache=True)  # type: ignore[arg-type]
        again = SchedulerTests.run_jobs(self, failing, use_cache=True)  # type: ignore[arg-type]
        self.assertEqual(again["bad"]["status"], "failed")
        self.assertTrue(Path(actions.CACHE).is_dir())
