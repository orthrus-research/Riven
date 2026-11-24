"""Exercise the PowerShell entry point with Python and wb replaced by argument recorders."""

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

    def test_check_runs_the_gate_in_workbench(self):
        self.assertEqual(self.invoke("ci", "--quiet"), ["wb", "check", "--quiet"])
        self.assertEqual(self.invoke("quick"), ["wb", "check"])

    def test_play_is_wb_play_from_this_worktree(self):
        self.assertEqual(self.invoke(), ["wb", "play"])
        self.assertEqual(
            self.invoke("play", "--driver", "pipeline"), ["wb", "play", "--driver", "pipeline"]
        )

    def test_build_needs_no_instance(self):
        self.assertEqual(self.invoke("build"), [str(ROOT / "tools/build_riven.py")])

    def test_the_launcher_verbs_are_gone(self):
        for verb in ("prepare", "command", "setup"):
            with self.assertRaises(AssertionError):
                self.invoke(verb)


if __name__ == "__main__":
    unittest.main()
