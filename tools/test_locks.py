"""The runtime lock agrees with the pin files it names.

A mod whose sourcePin is another lock (dev/cc.lock.json) must carry that lock's artifact SHA-256: setup.py places
the JAR the pin file names, and runtime.py prepare accepts only the bytes runtime.lock.json names.
"""

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class Locks(unittest.TestCase):
    def test_every_source_pinned_mod_matches_its_pin_file(self) -> None:
        runtime = json.loads((ROOT / "runtime.lock.json").read_text(encoding="utf-8"))
        pinned = [mod for mod in runtime["mods"] if mod.get("sourcePin")]
        self.assertTrue(pinned, "runtime.lock.json names no source-pinned mod")
        for mod in pinned:
            with self.subTest(mod=mod["file"]):
                pin = json.loads((ROOT / mod["sourcePin"]).read_text(encoding="utf-8"))
                self.assertEqual(pin["artifact"], mod["file"])
                self.assertEqual(
                    pin["sha256"],
                    mod["sha256"],
                    f"{mod['file']}: runtime.lock.json and {mod['sourcePin']} pin different bytes",
                )


if __name__ == "__main__":
    unittest.main()
