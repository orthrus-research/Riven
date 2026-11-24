"""Exercise the PowerShell entry point with Python replaced by an argument recorder."""

import inspect
import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

POWERSHELL = shutil.which("pwsh") or shutil.which("powershell")
ROOT = Path(__file__).resolve().parents[1]


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


@unittest.skipUnless(POWERSHELL, "PowerShell is required to exercise its parameter binder")
class LauncherTests(unittest.TestCase):
    def invoke(self, *arguments):
        assert POWERSHELL is not None
        command = (
            "function global:python { "
            "ConvertTo-Json -InputObject @($args) -Compress; $global:LASTEXITCODE = 0 }; "
            "function global:wb { "
            "ConvertTo-Json -InputObject @(@('wb') + $args) -Compress; $global:LASTEXITCODE = 0 }; "
            "$env:WB_CLI = 'wb'; "
            f"& {quote(ROOT / 'riven.ps1')} " + " ".join(arguments) + "; if (-not $?) { exit 1 }"
        )
        result = subprocess.run(
            [POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", command],
            capture_output=True,
            text=True,
            timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        recorded = json.loads(result.stdout)
        if recorded[0] != "wb":
            recorded[0] = str(Path(recorded[0]))
        return recorded

    def test_ci_runs_in_workbench_from_head(self):
        self.assertEqual(
            self.invoke("ci", "keep_going=true"),
            [
                "wb",
                "run",
                "ci.run",
                "project=riven",
                "commit=HEAD",
                "workflow=ci",
                "keep_going=true",
            ],
        )

    def test_quick_runs_the_working_tree(self):
        self.assertEqual(
            self.invoke("quick"),
            ["wb", "run", "ci.run", "project=riven", "commit=worktree", "workflow=quick"],
        )

    def test_setup_forwards_its_option(self):
        self.assertEqual(
            self.invoke("setup", "--check"),
            [str(ROOT / "tools/setup.py"), "--check"],
        )

    def test_build_needs_no_instance(self):
        self.assertEqual(self.invoke("build"), [str(ROOT / "tools/runtime.py"), "build"])

    def test_runtime_named_parameters_preserve_port_and_text(self):
        self.assertEqual(
            self.invoke(
                "command", "-Name", "test-instance", "-Port", "25999", "-Text", quote("say hello")
            ),
            [
                str(ROOT / "tools/runtime.py"),
                "command",
                "--name",
                "test-instance",
                "--port",
                "25999",
                "--text",
                "say hello",
            ],
        )

    def test_prepare_forwards_its_options(self):
        self.assertEqual(
            self.invoke("prepare", "-Name", "test-instance", "--preset", "canonical"),
            [
                str(ROOT / "tools/runtime.py"),
                "prepare",
                "--name",
                "test-instance",
                "--port",
                "25870",
                "--preset",
                "canonical",
            ],
        )


class PrepareDefaultsTests(unittest.TestCase):
    """runtime.py prepare's world and distant terrain, with prepare itself replaced by a recorder."""

    def prepared(self, *arguments):
        import runtime

        signature = inspect.signature(runtime.prepare)
        argv = ["runtime.py", "prepare", "--name", "test-instance", *arguments]
        with mock.patch.object(sys, "argv", argv), mock.patch.object(runtime, "prepare") as called:
            runtime.main()
        given = signature.bind(*called.call_args.args, **called.call_args.kwargs).arguments
        return {**given, "scale": given["layout_scale"]}

    def test_the_launch_file_reaches_prepare_and_the_default_world_stands(self):
        given = self.prepared("--launch", "launch.json")
        self.assertEqual(given["launch"], Path("launch.json"))
        self.assertEqual((given["seed"], given["scale"]), (8675309, 1.0))

    def test_distant_horizons_is_no_longer_an_option(self):
        with mock.patch("sys.stderr"), self.assertRaises(SystemExit):
            self.prepared("--distant-horizons")

    def test_canonical_preset_is_the_32_km_world(self):
        given = self.prepared("--preset", "canonical")
        self.assertEqual((given["seed"], given["scale"]), (2485331, 4.0))

    def test_a_preset_refuses_its_own_seed_or_scale(self):
        with mock.patch("sys.stderr"), self.assertRaises(SystemExit):
            self.prepared("--preset", "canonical", "--seed", "1")


class PrepareRefusalTests(unittest.TestCase):
    """prepare builds an instance only from a launch file, without Distant Horizons or extra client mods."""

    def test_without_a_launch_file_prepare_names_how_to_make_one(self):
        import runtime

        with self.assertRaisesRegex(RuntimeError, "wb run lab.resolve"):
            runtime.prepare("test-instance", 25565)

    def test_distant_horizons_and_extra_client_mods_are_refused(self):
        import runtime

        with self.assertRaisesRegex(ValueError, "not carried forward"):
            runtime.prepare("test-instance", 25565, distant_horizons=True, launch="launch.json")
        with self.assertRaisesRegex(ValueError, "names every mod"):
            runtime.prepare("test-instance", 25565, client_mods=["q.jar"], launch="launch.json")


class BuildTests(unittest.TestCase):
    def test_build_leaves_no_gradle_daemon(self):
        import runtime

        with (
            mock.patch.object(runtime.jdks, "home", return_value=Path("jdk")),
            mock.patch.object(runtime.jdks, "installations_property", return_value="x=y"),
            mock.patch.object(runtime.subprocess, "run") as run,
        ):
            runtime.build()
        command = run.call_args.args[0]
        self.assertIn("--no-daemon", command)
        self.assertIn("--no-watch-fs", command)


if __name__ == "__main__":
    unittest.main()
