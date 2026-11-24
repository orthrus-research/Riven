"""setup.py: what --check reports without changing anything, and the Cubature clone beside Riven."""

import argparse
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import setup


def git(directory: Path, *arguments: str) -> None:
    subprocess.run(
        ["git", "-C", str(directory), "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        + list(arguments),
        check=True,
        capture_output=True,
    )


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / "Riven"
        (self.root / ".local").mkdir(parents=True)
        self.settings = self.root / ".local/settings.json"
        self.checkout = self.base / "Cubature"
        for name, value in (
            ("ROOT", self.root),
            ("SETTINGS", self.settings),
            ("CUBATURE", self.checkout),
        ):
            patch = mock.patch.object(setup, name, value)
            patch.start()
            self.addCleanup(patch.stop)

    def tearDown(self):
        self.temp.cleanup()

    def options(self, check: bool, url: str | None = None) -> argparse.Namespace:
        return argparse.Namespace(check=check, cubature_url=url, java25=None, java26=None)

    def test_check_reports_a_missing_cubature_without_cloning(self):
        with mock.patch.object(setup.subprocess, "run", side_effect=AssertionError("ran")):
            self.assertFalse(setup.clone_cubature(self.options(check=True)))
        self.assertFalse(self.checkout.exists())

    def test_cubature_is_cloned_beside_riven_from_the_given_url(self):
        source = self.base / "Source"
        source.mkdir()
        (source / "README.md").write_text("cubature\n", encoding="utf-8")
        git(source, "init", "-q")
        git(source, "add", ".")
        git(source, "commit", "-q", "-m", "cubature")
        self.assertTrue(setup.clone_cubature(self.options(check=False, url=str(source))))
        self.assertTrue((self.checkout / "README.md").is_file())
        # Present now: --check finds it and clones nothing.
        self.assertTrue(setup.clone_cubature(self.options(check=True)))

    def test_check_records_no_java_paths(self):
        java = self.base / "jdk/bin/java.exe"
        with mock.patch.object(setup, "find_java", return_value=java):
            self.assertTrue(setup.configure_java(self.options(check=True)))
        self.assertFalse(self.settings.exists())
        with mock.patch.object(setup, "find_java", return_value=java):
            self.assertTrue(setup.configure_java(self.options(check=False)))
        recorded = json.loads(self.settings.read_text(encoding="utf-8"))
        self.assertEqual(recorded, {"java25": str(java), "java26": str(java)})


if __name__ == "__main__":
    unittest.main()
