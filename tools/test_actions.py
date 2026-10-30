"""actions.py's record of the trees a run tested and the jobs that passed on them."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import actions
from observability.profiles import ROOT


def git(repo, *arguments):
    command = ["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid", *arguments]
    return subprocess.run(
        command, cwd=repo, capture_output=True, text=True, check=True
    ).stdout.strip()


class Scratch(unittest.TestCase):
    def setUp(self):
        base = ROOT / ".local/actions-tests"
        base.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="case-", dir=base)
        self.home = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()


class WorkingTreeTests(Scratch):
    def setUp(self):
        super().setUp()
        self.repo = self.home / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-q")
        (self.repo / "kept.txt").write_text("kept\n", encoding="utf-8")
        (self.repo / "changed.txt").write_text("before\n", encoding="utf-8")
        (self.repo / "removed.txt").write_text("removed\n", encoding="utf-8")
        git(self.repo, "add", "--all")
        git(self.repo, "commit", "-q", "-m", "base")

    def test_a_clean_checkout_is_its_head_tree(self):
        self.assertEqual(
            actions.working_tree(self.repo), git(self.repo, "rev-parse", "HEAD^{tree}")
        )

    def test_uncommitted_work_hashes_as_its_commit_and_leaves_the_index_alone(self):
        (self.repo / "changed.txt").write_text("after\n", encoding="utf-8")
        (self.repo / "removed.txt").unlink()
        (self.repo / "added.txt").write_text("added\n", encoding="utf-8")
        status = git(self.repo, "status", "--porcelain")
        tested = actions.working_tree(self.repo)
        self.assertEqual(git(self.repo, "status", "--porcelain"), status)
        git(self.repo, "add", "--all")
        git(self.repo, "commit", "-q", "-m", "work")
        self.assertEqual(tested, git(self.repo, "rev-parse", "HEAD^{tree}"))

    def test_no_checkout_has_no_tree(self):
        self.assertIsNone(actions.working_tree(self.home))


class PassedJobsTests(Scratch):
    def run_summary(self, run_id, summary):
        path = self.home / run_id / "summary.json"
        path.parent.mkdir()
        path.write_text(summary if isinstance(summary, str) else json.dumps(summary), "utf-8")

    def test_passes_join_by_tree_pair_and_unrecorded_or_moving_trees_do_not_count(self):
        trees = {"riven": "r1", "cubature": "c1"}

        def jobs(**statuses):
            return {name.replace("_", "-"): {"status": s} for name, s in statuses.items()}

        self.run_summary(
            "a",
            {
                "trees": trees,
                "treesChanged": False,
                "jobs": jobs(quality="passed", riven_build="failed"),
            },
        )
        self.run_summary(
            "b", {"trees": trees, "treesChanged": False, "jobs": jobs(atlas_golden="passed")}
        )
        self.run_summary(
            "c",
            {
                "trees": {"riven": "r2", "cubature": "c1"},
                "treesChanged": False,
                "jobs": jobs(definitions="passed"),
            },
        )
        self.run_summary(
            "d", {"trees": trees, "treesChanged": True, "jobs": jobs(sample_history="passed")}
        )
        self.run_summary("e", {"jobs": jobs(cubature_build="passed")})
        self.run_summary(
            "f",
            {
                "trees": {"riven": "r1", "cubature": None},
                "treesChanged": False,
                "jobs": jobs(riven_build="passed"),
            },
        )
        self.run_summary("g", "{not json")
        self.assertEqual(
            actions.passed_jobs(self.home),
            {("r1", "c1"): {"quality", "atlas-golden"}, ("r2", "c1"): {"definitions"}},
        )


class QuickRuleTests(unittest.TestCase):
    """The [quick] rules in tools/actions.toml, as committed."""

    config = actions.load()
    ci = config["workflows"]["ci"]

    def jobs(self, repository, *paths):
        found = actions.affected(self.config["quick"][repository], list(paths), self.ci)
        return [job for job in self.ci if job in found]

    def test_riven_paths(self):
        atlas = ["quality", "atlas-golden", "definitions", "sample-history"]
        cases = {
            "docs/ART-DIRECTION.md": [],
            "tools/tours/caves-8675309.json": [],
            "CHANGELOG.md": ["definitions"],
            "README.md": ["definitions"],
            "NOTICE.md": ["quality", "riven-build"],
            "src/main/resources/assets/riven/textures/blocks/a.png": ["quality", "riven-build"],
            "tools/atlas/build.py": atlas,
            "tools/checks/TerrainAtlas.java": atlas,
            "tools/atlas-golden.json": atlas,
            "tools/build_riven.py": ["quality", "riven-build"],
            "tools/actions.py": ["quality"],
            "tools/observability/profiles.py": ["quality"],
            "tools/content/riven-content-source.json": ["quality"],
            "src/main/java/riven/worldgen/TerrainMap.java": self.ci,
            "src/main/resources/riven-terrain.json": self.ci,
            "tools/actions.toml": self.ci,
            "dev/cc.lock.json": self.ci,
            "docs.md": self.ci,
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(self.jobs("riven", path), expected)

    def test_cubature_paths_reach_only_its_build_and_quality(self):
        self.assertEqual(self.jobs("cubature", "docs/delivery.md"), [])
        for path in ("src/main/java/a/B.java", "tools/lab_files.py", "build.gradle"):
            with self.subTest(path=path):
                self.assertEqual(self.jobs("cubature", path), ["quality", "cubature-build"])

    def test_a_change_takes_every_path_jobs(self):
        self.assertEqual(
            self.jobs("riven", "docs/RUNTIME.md", "README.md", "tools/build_riven.py"),
            ["quality", "riven-build", "definitions"],
        )


if __name__ == "__main__":
    unittest.main()
