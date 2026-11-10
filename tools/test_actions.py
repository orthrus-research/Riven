"""actions.py's record of the trees a run tested and the jobs that passed on them."""

import ast
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest import mock

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
        store = ["quality", "regional-store"]
        cases = {
            "docs/ART-REQUISITIONS.md": [],
            "tools/tours/caves-8675309.json": [],
            "CHANGELOG.md": ["definitions"],
            "README.md": ["definitions"],
            "NOTICE.md": ["quality", "riven-build"],
            "src/main/resources/assets/riven/textures/blocks/a.png": ["quality", "riven-build"],
            "tools/atlas/build.py": atlas,
            "tools/checks/TerrainAtlas.java": atlas,
            "src/main/java/cc/orthrus/riven/worldgen/RegionalData.java": self.ci,
            "src/main/java/cc/orthrus/riven/worldgen/RegionalIdentity.java": self.ci,
            "src/main/java/cc/orthrus/riven/worldgen/RegionalStore.java": self.ci,
            "src/main/java/cc/orthrus/riven/RegionalOwnership.java": self.ci,
            "src/main/java/cc/orthrus/riven/RegionalCleanup.java": self.ci,
            "src/main/java/cc/orthrus/riven/RegionalWorlds.java": self.ci,
            "src/main/java/cc/orthrus/riven/RivenWorldType.java": self.ci,
            "tools/checks/RegionalStoreCheck.java": store,
            "tools/checks/DrainageCodecCheck.java": store,
            "tools/checks/RegionalOwnershipCheck.java": store,
            "tools/checks/RegionalCleanupCheck.java": store,
            "tools/checks/CubePrimerBootstrapCheck.java": ["quality", "riven-build"],
            "tools/checks/CubePrimerFixture.java": ["quality", "riven-build"],
            "tools/checks/CubeClassificationCheck.java": ["quality", "riven-build"],
            "tools/checks/DrainagePruneCheck.java": ["quality", "drainage-prune"],
            "tools/checks/RegionalCacheProbeReportingCheck.java": atlas,
            "tools/checks/StoredAtlasParityCheck.java": atlas,
            "tools/atlas-golden.json": atlas,
            "tools/build_riven.py": ["quality", "riven-build"],
            "tools/actions.py": ["quality"],
            "tools/observability/profiles.py": ["quality"],
            "tools/content/riven-content-source.json": ["quality"],
            "src/main/java/riven/worldgen/TerrainMap.java": self.ci,
            "src/main/java/cc/orthrus/riven/worldgen/Drainage.java": self.ci,
            "src/main/java/cc/orthrus/riven/worldgen/GenerationMaps.java": self.ci,
            "src/main/java/cc/orthrus/riven/worldgen/WorldGenerationContext.java": self.ci,
            "src/main/java/cc/orthrus/riven/worldgen/RegionalTerrainPasses.java": self.ci,
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
        self.assertEqual(
            self.jobs(
                "riven",
                "src/main/java/cc/orthrus/riven/worldgen/RegionalStore.java",
                "src/main/java/cc/orthrus/riven/worldgen/Drainage.java",
            ),
            self.ci,
        )


class RegionalStoreCommandTests(Scratch):
    """Execute the configured Python with a fake compiler; never start a JVM."""

    def setUp(self):
        super().setUp()
        self.riven = self.home / "riven with 'quotes' and {braces}"
        self.frozen = self.home / "frozen with 'quotes' and {braces}"
        self.values = {
            "atlas_python": "atlas-python",
            "riven": str(self.riven),
            "frozen": str(self.frozen),
        }
        self.steps = actions.load()["jobs"]["regional-store"]["steps"]
        self.command = actions.expand(self.steps[1]["run"], self.values)
        self.base = self.home / "cached worldgen"
        self.base.mkdir()
        (self.base / "sentinel.class").write_bytes(b"unchanged cache")
        self.java = self.home / "Java 26" / "bin" / "java.exe"
        self.gson = self.home / "Gson cache" / "gson.jar"
        self.build = SimpleNamespace(
            compile_sampler=mock.Mock(return_value=self.base),
            java=mock.Mock(return_value=self.java),
            gson=mock.Mock(return_value=self.gson),
        )
        self.atlas = ModuleType("atlas")
        setattr(self.atlas, "build", self.build)

    def execute(self, run):
        with (
            mock.patch.object(sys, "argv", ["-c", *self.command[4:]]),
            mock.patch.object(sys, "path", list(sys.path)),
            mock.patch.dict(sys.modules, {"atlas": self.atlas}),
            mock.patch.object(subprocess, "run", run),
        ):
            exec(compile(self.command[3], "regional-store action", "exec"), {})

    def test_filesystem_step_stays_and_inline_paths_are_separate_arguments(self):
        self.assertEqual(len(self.steps), 2)
        self.assertEqual(
            actions.expand(self.steps[0]["run"], self.values),
            [
                "atlas-python",
                "tools/run_check.py",
                "RegionalStoreCheck",
                f"{self.frozen}/regional-store-fixtures",
            ],
        )
        self.assertEqual(self.command[:3], ["atlas-python", "-B", "-c"])
        ast.parse(self.command[3])
        self.assertNotIn(str(self.riven), self.command[3])
        self.assertNotIn(str(self.frozen), self.command[3])
        self.assertEqual(
            self.command[4:],
            [
                f"{self.riven}/tools",
                f"{self.riven}/src/main",
                f"{self.riven}/tools/checks/RegionalStoreCheck.java",
                f"{self.frozen}/regional-graph-fixtures",
                f"{self.riven}/tools/checks/DrainageCodecCheck.java",
                f"{self.riven}/src/main/java/cc/orthrus/riven/RegionalOwnership.java",
                f"{self.riven}/src/main/java/cc/orthrus/riven/RegionalCleanup.java",
                f"{self.riven}/tools/checks/RegionalOwnershipCheck.java",
                f"{self.riven}/tools/checks/RegionalCleanupCheck.java",
            ],
        )

    def test_strict_fresh_compile_then_only_fixture_and_lifecycle_mains(self):
        outputs = []
        for _ in range(2):
            run = mock.Mock()
            self.execute(run)
            self.assertEqual(run.call_count, 4)
            compiler = run.call_args_list[0]
            self.assertEqual(compiler.kwargs, {"check": True})
            command = compiler.args[0]
            output = Path(command[9])
            outputs.append(output)
            self.assertTrue(output.is_dir())
            self.assertEqual(list(output.iterdir()), [])
            self.assertEqual(output.parent, self.frozen / "regional-graph-fixtures")
            self.assertEqual(
                command,
                [
                    str(self.java.with_name("javac.exe")),
                    "--release",
                    "25",
                    "-Xlint:all,-options",
                    "-Werror",
                    "-implicit:none",
                    "-cp",
                    os.pathsep.join(map(str, (self.base, self.gson))),
                    "-d",
                    str(output),
                    *map(str, map(Path, self.command[8:])),
                ],
            )
            classpath = os.pathsep.join(map(str, (output, self.base, self.gson)))
            self.assertEqual(
                run.call_args_list[1:],
                [
                    mock.call(
                        [
                            str(self.java),
                            "-cp",
                            classpath,
                            "cc.orthrus.riven.worldgen.DrainageCodecCheck",
                            str(output / "codec"),
                            "fixtures",
                        ],
                        check=True,
                    ),
                    mock.call(
                        [
                            str(self.java),
                            "-cp",
                            classpath,
                            "cc.orthrus.riven.RegionalOwnershipCheck",
                            str(output / "ownership"),
                        ],
                        check=True,
                    ),
                    mock.call(
                        [
                            str(self.java),
                            "-cp",
                            classpath,
                            "cc.orthrus.riven.RegionalCleanupCheck",
                            str(output / "cleanup"),
                        ],
                        check=True,
                    ),
                ],
            )
        self.assertNotEqual(*outputs)
        self.build.compile_sampler.assert_has_calls(
            [
                mock.call(
                    self.riven / "src/main", self.riven / "tools/checks/RegionalStoreCheck.java"
                )
            ]
            * 2
        )
        self.assertEqual(list(self.base.iterdir()), [self.base / "sentinel.class"])
        self.assertEqual((self.base / "sentinel.class").read_bytes(), b"unchanged cache")

    def test_compile_or_main_failure_stops_the_configured_sequence(self):
        for position in range(4):
            with self.subTest(position=position):
                failure = subprocess.CalledProcessError(9, ["deliberate fake failure"])
                run = mock.Mock(side_effect=[None] * position + [failure])
                with self.assertRaises(subprocess.CalledProcessError) as raised:
                    self.execute(run)
                self.assertIs(raised.exception, failure)
                self.assertEqual(run.call_count, position + 1)


if __name__ == "__main__":
    unittest.main()
