"""setup.py's pins built from source: the command a recipe gives, what --check reports, and a
build in a worktree of a real git checkout whose wrapper copies a committed file."""

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import setup

PAYLOAD = b"pinned artifact bytes\n"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(directory: Path, *arguments: str) -> None:
    subprocess.run(
        ["git", "-C", str(directory), "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        + list(arguments),
        check=True,
        capture_output=True,
    )


class SourcePinTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / "Riven"
        (self.root / ".local").mkdir(parents=True)
        self.settings = self.root / ".local/settings.json"
        java = self.base / "jdk-26/bin/java.exe"
        self.settings.write_text(json.dumps({"java26": str(java)}), encoding="utf-8")
        patches = [
            mock.patch.object(setup, "ROOT", self.root),
            mock.patch.object(setup, "SETTINGS", self.settings),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def tearDown(self):
        self.temp.cleanup()

    def source(self) -> tuple[Path, str]:
        """A checkout whose wrapper copies payload.bin to build/libs/out.jar; its commit."""
        checkout = self.base / "Source"
        checkout.mkdir()
        # No line-ending conversion: the worktree must hold the committed bytes, as a jar's are.
        (checkout / ".gitattributes").write_text("* -text\n", encoding="utf-8")
        (checkout / "payload.bin").write_bytes(PAYLOAD)
        (checkout / "gradlew.bat").write_text(
            "@echo off\r\nmkdir build\\libs 2>nul\r\ncopy /y payload.bin build\\libs\\out.jar >nul\r\n",
            encoding="utf-8",
        )
        (checkout / "gradlew").write_text(
            "#!/bin/sh\nmkdir -p build/libs && cp payload.bin build/libs/out.jar\n",
            encoding="utf-8",
        )
        os.chmod(checkout / "gradlew", 0o755)
        git(checkout, "init", "-q")
        git(checkout, "add", ".")
        git(checkout, "commit", "-q", "-m", "source")
        commit = subprocess.run(
            ["git", "-C", str(checkout), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        return checkout, commit

    def mod(self, commit: str, sha256: str, file: str = "dev/pins/out.jar") -> dict:
        return {
            "file": file,
            "sha256": sha256,
            "source": "https://example.invalid/source.git",
            "sourceCommit": commit,
            "build": {
                "checkout": "../Source",
                "java": 26,
                "tasks": ["assemble"],
                "artifact": "build/libs/out.jar",
            },
        }

    def write_lock(self, *mods: dict) -> None:
        (self.root / "runtime.lock.json").write_text(
            json.dumps({"mods": list(mods)}), encoding="utf-8"
        )

    def test_command_names_tasks_java_and_properties(self):
        recipe = {"tasks": ["a", "b"], "properties": {"input": "{checkout}/x.jar"}}
        checkout, home, work = Path("/s"), Path("/jdk"), Path("/w")
        command = setup.build_command(recipe, checkout, home, work)
        self.assertEqual(Path(command[0]).name, "gradlew.bat" if os.name == "nt" else "gradlew")
        self.assertEqual(
            command[1:],
            [
                "a",
                "b",
                "--console=plain",
                "--no-daemon",
                f"-Dorg.gradle.java.installations.paths={home}",
                f"-Pinput={checkout}/x.jar",
            ],
        )

    def test_check_reports_present_and_missing_without_building(self):
        present = self.root / "dev/pins/here.jar"
        present.parent.mkdir(parents=True)
        present.write_bytes(PAYLOAD)
        self.write_lock(
            self.mod("0" * 40, digest(PAYLOAD), "dev/pins/here.jar"),
            self.mod("1" * 40, digest(b"other"), "dev/pins/absent.jar"),
            {"file": "dev/pins/downloaded.jar", "sha256": "", "download": "https://x"},
        )
        with mock.patch.object(setup, "build_source_pin", side_effect=AssertionError("built")):
            ready = setup.source_pins(argparse.Namespace(check=True))
        self.assertFalse(ready)
        self.assertFalse((self.root / "dev/pins/absent.jar").exists())

    def test_build_places_the_pinned_bytes_and_drops_its_worktree(self):
        checkout, commit = self.source()
        self.write_lock(self.mod(commit, digest(PAYLOAD)))
        self.assertTrue(setup.source_pins(argparse.Namespace(check=False)))
        self.assertEqual((self.root / "dev/pins/out.jar").read_bytes(), PAYLOAD)
        self.assertEqual(list((self.root / ".local/source-builds").iterdir()), [])
        worktrees = subprocess.run(
            ["git", "-C", str(checkout), "worktree", "list"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.splitlines()
        self.assertEqual(len(worktrees), 1)

    def test_build_of_other_bytes_leaves_dev_pins_unchanged(self):
        _, commit = self.source()
        target = self.root / "dev/pins/out.jar"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"previous")
        self.write_lock(self.mod(commit, digest(b"something else")))
        with self.assertRaises(SystemExit) as refused:
            setup.source_pins(argparse.Namespace(check=False))
        self.assertIn("dev/pins is unchanged", str(refused.exception))
        self.assertEqual(target.read_bytes(), b"previous")
        self.assertEqual(list((self.root / ".local/source-builds").iterdir()), [])


if __name__ == "__main__":
    unittest.main()
