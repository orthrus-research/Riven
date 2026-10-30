"""push_gate.py's decision for a pushed Riven or Cubature commit."""

import json
import unittest

import push_gate
from test_actions import Scratch, git

CI = ["quality", "riven-build", "cubature-build"]


class RefusalTests(Scratch):
    def setUp(self):
        super().setUp()
        self.cubature = self.home / "cubature"
        self.riven = self.home / "riven"
        self.cubature.mkdir()
        git(self.cubature, "init", "-q")
        (self.cubature / "a.txt").write_text("a\n", encoding="utf-8")
        git(self.cubature, "add", "--all")
        git(self.cubature, "commit", "-q", "-m", "cubature")
        self.pin = git(self.cubature, "rev-parse", "HEAD")
        self.cubature_tree = git(self.cubature, "rev-parse", "HEAD^{tree}")
        self.commit = self.riven_commit(self.pin)
        self.riven_tree = git(self.riven, "rev-parse", "HEAD^{tree}")

    def riven_commit(self, pin):
        (self.riven / "dev").mkdir(parents=True, exist_ok=True)
        if not (self.riven / ".git").exists():
            git(self.riven, "init", "-q")
        lock = {"name": "Cubature", "commit": pin}
        (self.riven / "dev/cc.lock.json").write_text(json.dumps(lock), encoding="utf-8")
        git(self.riven, "add", "--all")
        git(self.riven, "commit", "-q", "--allow-empty", "-m", f"pin {pin[:8]}")
        return git(self.riven, "rev-parse", "HEAD")

    def refusal(self, repository, commit, passed):
        return push_gate.refusal(repository, commit, passed, CI, self.riven, self.cubature)

    def test_a_riven_commit_needs_every_job_with_its_pinned_cubature_tree(self):
        pair = (self.riven_tree, self.cubature_tree)
        self.assertIsNone(self.refusal("riven", self.commit, {pair: set(CI)}))
        reason = self.refusal("riven", self.commit, {pair: {"quality"}})
        self.assertIn("has not passed riven-build, cubature-build", reason or "")
        other = {(self.riven_tree, "another-cubature-tree"): set(CI)}
        self.assertIn("has not passed quality", self.refusal("riven", self.commit, other) or "")

    def test_a_riven_commit_pinning_an_absent_cubature_commit_is_refused(self):
        commit = self.riven_commit("f" * 40)
        tree = git(self.riven, "rev-parse", "HEAD^{tree}")
        reason = self.refusal("riven", commit, {(tree, self.cubature_tree): set(CI)})
        self.assertEqual(reason, "its Cubature pin ffffffff is not in dev/CubicChunks")

    def test_a_cubature_commit_needs_every_job_with_one_riven_tree(self):
        split = {("r1", self.cubature_tree): {"quality"}, ("r2", self.cubature_tree): set(CI[1:])}
        self.assertIn("has not passed", self.refusal("cubature", self.pin, split) or "")
        joined = {**split, ("r3", self.cubature_tree): set(CI)}
        self.assertIsNone(self.refusal("cubature", self.pin, joined))

    def test_deleted_refs_and_other_lines_are_not_checked(self):
        zero = "0" * 40
        lines = [f"(delete) {zero} refs/heads/old {self.commit}\n", "\n"]
        self.assertEqual(push_gate.check("riven", lines), 0)


if __name__ == "__main__":
    unittest.main()
