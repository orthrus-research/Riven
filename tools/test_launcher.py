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
        recorded[0] = str(Path(recorded[0]))
        return recorded

    def test_full_forwards_multiple_options_without_binding_name_or_port(self):
        self.assertEqual(
            self.invoke("full", "--if-needed", "--dry-run"),
            [str(ROOT / "tools/actions.py"), "full", "--if-needed", "--dry-run"],
        )

    def test_quick_forwards_its_option(self):
        self.assertEqual(
            self.invoke("quick", "--if-needed"),
            [str(ROOT / "tools/actions.py"), "quick", "--if-needed"],
        )

    def test_actions_forwards_a_subcommand_and_options(self):
        self.assertEqual(
            self.invoke("actions", "ci", "--if-needed", "--dry-run"),
            [str(ROOT / "tools/actions.py"), "ci", "--if-needed", "--dry-run"],
        )

    def test_setup_forwards_its_option(self):
        self.assertEqual(
            self.invoke("setup", "--check"),
            [str(ROOT / "tools/setup.py"), "--check"],
        )

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
