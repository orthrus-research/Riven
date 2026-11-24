"""Exercise the PowerShell entry point with Python and wb replaced by argument recorders."""

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

POWERSHELL = shutil.which("pwsh") or shutil.which("powershell")
ROOT = Path(__file__).resolve().parents[1]


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


@unittest.skipUnless(POWERSHELL, "PowerShell is required to exercise its parameter binder")
class LauncherTests(unittest.TestCase):
    def invoke(self, *arguments, root=ROOT):
        assert POWERSHELL is not None
        command = (
            "function global:python { "
            "ConvertTo-Json -InputObject @($args) -Compress; $global:LASTEXITCODE = 0 }; "
            "function global:wb { "
            "ConvertTo-Json -InputObject @(@('wb') + $args) -Compress; $global:LASTEXITCODE = 0 }; "
            "$env:WB_CLI = 'wb'; "
            f"& {quote(root / 'riven.ps1')} " + " ".join(arguments) + "; if (-not $?) { exit 1 }"
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

    def test_build_is_gradle_here(self):
        # A copy of the launcher beside a stand-in wrapper that prints the arguments it was given.
        record = "import json, sys; print(json.dumps(['gradlew', *sys.argv[1:]]))"
        with tempfile.TemporaryDirectory() as scratch:
            folder = Path(scratch)
            shutil.copy(ROOT / "riven.ps1", folder)
            (folder / "gradlew.bat").write_text(f'@"{sys.executable}" -c "{record}" %*\n')
            wrapper = folder / "gradlew"
            wrapper.write_text(f'#!/bin/sh\nexec "{sys.executable}" -c "{record}" "$@"\n')
            wrapper.chmod(0o755)
            self.assertEqual(self.invoke("build", root=folder), ["gradlew", "assemble"])
            self.assertEqual(
                self.invoke("build", "compileDiagnosticsJava", root=folder),
                ["gradlew", "assemble", "compileDiagnosticsJava"],
            )

    def test_the_launcher_verbs_are_gone(self):
        for verb in ("prepare", "command", "setup"):
            with self.assertRaises(AssertionError):
                self.invoke(verb)


if __name__ == "__main__":
    unittest.main()
