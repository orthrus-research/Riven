"""Exercise the PowerShell entry point with Python replaced by an argument recorder."""

import json
import shutil
import subprocess
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
