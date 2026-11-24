"""set_store finds the set's third-party files in the Store by the SHA-256 the set gives them."""

import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import set_store  # noqa: E402

GSON = b"stand-in Gson bytes"
SHA = hashlib.sha256(GSON).hexdigest()


class SetStoreTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.export = self.root / "sets" / "abc123"
        self.export.mkdir(parents=True)
        self.store = self.root / "store"
        self.home = self.root / "gradle-home"
        self.home.mkdir()
        set_store.document.cache_clear()
        set_store.third.cache_clear()
        self.addCleanup(set_store.document.cache_clear)
        self.addCleanup(set_store.third.cache_clear)

    def environment(self, **extra):
        """A job's environment: the set's export and the Store in GRADLE_OPTS, as Workbench passes them."""
        options = f"-Dorg.gradle.project.set.dir={self.export.as_posix()} -Dorg.gradle.project.set.store={self.store.as_posix()}"
        return patch.dict(
            os.environ, {"GRADLE_OPTS": options, "GRADLE_USER_HOME": str(self.home), **extra}
        )

    def write_set(self, third):
        (self.export / "set.json").write_text(json.dumps({"schema": 1, "third": third}), "utf-8")

    def keep(self, name, data=GSON, digest=SHA):
        folder = self.store / "files" / digest
        folder.mkdir(parents=True, exist_ok=True)
        (folder / name).write_bytes(data)
        return folder / name

    def module_entry(self, digest=SHA):
        return {
            "module": "com.google.code.gson:gson:2.14.0",
            "repository": "https://repo1.maven.org/maven2",
            "files": {"runtime": digest},
            "licence": "Apache-2.0",
        }

    def test_a_module_entry_is_its_runtime_file_in_the_store(self):
        self.write_set({"gson": self.module_entry()})
        kept = self.keep("gson-2.14.0.jar")
        with self.environment():
            self.assertEqual(set_store.third("gson", self.root), kept)

    def test_an_entry_without_a_coordinate_names_its_own_file(self):
        self.write_set({"gson": {"file": "gson.jar", "sha256": SHA, "source": "x", "licence": "y"}})
        kept = self.keep("gson.jar")
        with self.environment():
            self.assertEqual(set_store.third("gson", self.root), kept)

    def test_bytes_that_are_not_the_sets_are_refused(self):
        self.write_set({"gson": self.module_entry()})
        self.keep("gson-2.14.0.jar", data=b"other bytes")
        with self.environment(), self.assertRaisesRegex(RuntimeError, "not the set's gson"):
            set_store.third("gson", self.root)

    def test_without_a_store_the_same_bytes_come_from_the_gradle_cache(self):
        self.write_set({"gson": self.module_entry()})
        cached = (
            self.home
            / "caches/modules-2/files-2.1/com.google.code.gson/gson/2.14.0/f00/gson-2.14.0.jar"
        )
        cached.parent.mkdir(parents=True)
        cached.write_bytes(GSON)
        options = f"-Dorg.gradle.project.set.dir={self.export.as_posix()}"
        with patch.dict(os.environ, {"GRADLE_OPTS": options, "GRADLE_USER_HOME": str(self.home)}):
            self.assertEqual(set_store.third("gson", self.root), cached)

    def test_a_set_without_the_entry_says_what_it_has(self):
        self.write_set({"fugue": self.module_entry()})
        with (
            self.environment(),
            self.assertRaisesRegex(RuntimeError, "no third entry gson .*fugue"),
        ):
            set_store.third("gson", self.root)

    def test_by_hand_the_set_is_the_mark_above_the_checkout_in_set_home(self):
        checkout = self.root / "work" / "riven"
        checkout.mkdir(parents=True)
        (self.root / "work" / ".set").write_text(json.dumps({"set": "abc123"}), "utf-8")
        (self.home / "gradle.properties").write_text(
            f"set.home={(self.root / 'sets').as_posix()}\nset.store={self.store.as_posix()}\n",
            "utf-8",
        )
        self.write_set({"gson": self.module_entry()})
        kept = self.keep("gson-2.14.0.jar")
        with patch.dict(os.environ, {"GRADLE_OPTS": "", "GRADLE_USER_HOME": str(self.home)}):
            self.assertEqual(set_store.third("gson", checkout), kept)


if __name__ == "__main__":
    unittest.main()
