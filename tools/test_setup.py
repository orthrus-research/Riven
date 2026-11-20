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
        recipe = {"tasks": ["a", "b"]}
        home, work = Path("/jdk"), Path("/w")
        command = setup.build_command(recipe, {"input": "x.jar"}, home, work)
        self.assertEqual(Path(command[0]).name, "gradlew.bat" if os.name == "nt" else "gradlew")
        self.assertEqual(
            command[1:],
            [
                "a",
                "b",
                "--console=plain",
                "--no-daemon",
                "--no-watch-fs",
                f"-Dorg.gradle.java.installations.paths={home}",
                "-Pinput=x.jar",
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
        self.assertIn("nothing was placed", str(refused.exception))
        self.assertEqual(target.read_bytes(), b"previous")
        self.assertEqual(list((self.root / ".local/source-builds").iterdir()), [])


BINARY, SOURCES = b"cubature binary\n", b"cubature sources\n"
FETCH = """from pathlib import Path
inputs = Path(__file__).resolve().parents[1] / "cleanroom/build-inputs"
inputs.mkdir(parents=True, exist_ok=True)
(inputs / "fetched").write_text("yes")
"""
# Fails where Riven's workspace reaches the build or the commit's own inputs were not fetched.
WRAPPER_BAT = (
    "@echo off\r\nif defined RIVEN_WORKSPACE exit /b 3\r\n"
    "if not exist build-inputs\\fetched exit /b 4\r\n"
    "mkdir build\\cleanroom\\libs 2>nul\r\n"
    "copy /y binary.bin build\\cleanroom\\libs\\Cubature-1-cleanroom.jar >nul\r\n"
    "copy /y sources.bin build\\cleanroom\\libs\\Cubature-1-sources.jar >nul\r\n"
)
WRAPPER_SH = (
    '#!/bin/sh\n[ -n "$RIVEN_WORKSPACE" ] && exit 3\n[ -f build-inputs/fetched ] || exit 4\n'
    "mkdir -p build/cleanroom/libs && cp binary.bin build/cleanroom/libs/Cubature-1-cleanroom.jar"
    " && cp sources.bin build/cleanroom/libs/Cubature-1-sources.jar\n"
)


class CubaturePinTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / "Riven"
        (self.root / ".local").mkdir(parents=True)
        (self.root / "dev").mkdir()
        self.settings = self.root / ".local/settings.json"
        java = self.base / "jdk-25/bin/java.exe"
        self.settings.write_text(json.dumps({"java25": str(java)}), encoding="utf-8")
        self.checkout = self.base / "Cubature"
        (self.checkout / "tools").mkdir(parents=True)
        (self.checkout / "cleanroom").mkdir()
        (self.checkout / ".gitattributes").write_text("* -text\n", encoding="utf-8")
        (self.checkout / "tools/fetch_build_inputs.py").write_text(FETCH, encoding="utf-8")
        (self.checkout / "cleanroom/binary.bin").write_bytes(BINARY)
        (self.checkout / "cleanroom/sources.bin").write_bytes(SOURCES)
        (self.checkout / "cleanroom/gradlew.bat").write_text(WRAPPER_BAT, encoding="utf-8")
        (self.checkout / "cleanroom/gradlew").write_text(WRAPPER_SH, encoding="utf-8")
        os.chmod(self.checkout / "cleanroom/gradlew", 0o755)
        git(self.checkout, "init", "-q")
        git(self.checkout, "add", ".")
        git(self.checkout, "commit", "-q", "-m", "cubature")
        self.commit = subprocess.run(
            ["git", "-C", str(self.checkout), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        for name, value in (
            ("ROOT", self.root),
            ("SETTINGS", self.settings),
            ("CUBATURE", self.checkout),
        ):
            patch = mock.patch.object(setup, name, value)
            patch.start()
            self.addCleanup(patch.stop)
        # Riven's tools export this; the Cubature build must not see it.
        environment = mock.patch.dict(os.environ, {"RIVEN_WORKSPACE": str(self.root)})
        environment.start()
        self.addCleanup(environment.stop)

    def tearDown(self):
        self.temp.cleanup()

    def lock(self, binary: bytes) -> None:
        (self.root / "dev/cc.lock.json").write_text(
            json.dumps(
                {
                    "commit": self.commit,
                    "repository": "https://example.invalid/cubature.git",
                    "artifact": "dev/pins/Cubature-cleanroom.jar",
                    "sha256": digest(binary),
                    "sourcesArtifact": "dev/pins/Cubature-cleanroom-sources.jar",
                    "sourcesSha256": digest(SOURCES),
                }
            ),
            encoding="utf-8",
        )

    def test_builds_the_pinned_commit_in_a_worktree_of_its_own(self):
        self.lock(BINARY)
        (self.checkout / "cleanroom/binary.bin").write_bytes(b"uncommitted change")
        self.assertTrue(setup.build_cubature(argparse.Namespace(check=False)))
        self.assertEqual((self.root / "dev/pins/Cubature-cleanroom.jar").read_bytes(), BINARY)
        self.assertEqual(
            (self.root / "dev/pins/Cubature-cleanroom-sources.jar").read_bytes(), SOURCES
        )
        self.assertEqual(
            (self.checkout / "cleanroom/binary.bin").read_bytes(), b"uncommitted change"
        )
        self.assertEqual(list((self.root / ".local/source-builds").iterdir()), [])

    def test_a_cubature_input_is_built_once_and_kept(self):
        self.lock(BINARY)
        settings = json.loads(self.settings.read_text(encoding="utf-8"))
        spec = {"cubature": self.commit, "sha256": digest(BINARY)}
        built = setup.cubature_input(spec, settings)
        self.assertEqual(built.read_bytes(), BINARY)
        self.assertEqual(built.parent, self.root / ".local/source-inputs")
        with mock.patch.object(setup, "built_cubature", side_effect=AssertionError("rebuilt")):
            self.assertEqual(setup.cubature_input(spec, settings), built)
        recipe = {"properties": {"jar": spec, "plain": "text"}}
        self.assertEqual(
            setup.build_properties(recipe, settings), {"jar": str(built), "plain": "text"}
        )

    def test_other_bytes_place_neither_artifact(self):
        self.lock(b"another build")
        with self.assertRaises(SystemExit):
            setup.build_cubature(argparse.Namespace(check=False))
        self.assertFalse((self.root / "dev/pins/Cubature-cleanroom-sources.jar").exists())
        self.assertFalse((self.root / "dev/pins/Cubature-cleanroom.jar").exists())


class RemoveTreeTest(unittest.TestCase):
    def test_a_tree_deeper_than_260_characters_is_removed(self):
        with tempfile.TemporaryDirectory() as folder:
            top = Path(folder) / "build"
            deep = os.path.abspath(top / ("d" * 60) / ("e" * 60) / ("f" * 60) / ("g" * 60))
            extended = "\\\\?\\" + deep if os.name == "nt" else deep
            os.makedirs(extended)
            with open(os.path.join(extended, "h" * 40 + ".class"), "w", encoding="utf-8") as out:
                out.write("x")
            self.assertGreater(len(deep), 260)
            self.assertIsNone(setup.remove_tree(top))
            self.assertFalse(top.exists())


if __name__ == "__main__":
    unittest.main()
