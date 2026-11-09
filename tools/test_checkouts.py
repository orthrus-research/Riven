import json
import tempfile
import unittest
from pathlib import Path

import checkouts


class CheckoutsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name).resolve()
        self.main = self.base / "Riven"
        (self.main / ".git/worktrees/side").mkdir(parents=True)
        (self.main / ".git/worktrees/side/commondir").write_text("../..\n", encoding="utf-8")
        self.worktree = self.base / "elsewhere/deep/side"
        self.worktree.mkdir(parents=True)
        (self.worktree / ".git").write_text(
            f"gitdir: {self.main / '.git/worktrees/side'}\n", encoding="utf-8"
        )
        for root in (self.main, self.worktree):
            (root / "dev").mkdir()
            (root / "dev/cc.lock.json").write_text(
                json.dumps({"checkout": "../Cubature"}), encoding="utf-8"
            )

    def tearDown(self):
        self.temp.cleanup()

    def test_main_checkout_is_itself(self):
        self.assertEqual(checkouts.main_checkout(self.main), self.main)

    def test_linked_worktree_resolves_from_its_main_checkout(self):
        self.assertEqual(checkouts.main_checkout(self.worktree), self.main)
        self.assertEqual(checkouts.cubature_checkout(self.worktree), self.base / "Cubature")

    def test_main_checkout_names_its_sibling(self):
        self.assertEqual(checkouts.cubature_checkout(self.main), self.base / "Cubature")


if __name__ == "__main__":
    unittest.main()
