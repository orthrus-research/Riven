"""launch/configure.py, Riven's changes to Cubature's standalone world, run on a small instance folder: the height
range from the packaged height reference, Riven's level type and layout scale, Cubature's delivery settings and the
client's graphics, splash and compatibility check."""

import importlib.util
import io
import json
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "riven_launch_configure", ROOT / "launch/configure.py"
)
assert SPEC is not None and SPEC.loader is not None
configure_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(configure_module)

CUBIC = "general {\n    I:defaultMinHeight=-128\n    I:defaultMaxHeight=384\n}\n"
REFERENCE = {
    "revision": 7,
    "allocation": {"worldMinY": -2048, "minY": -2000, "maxYExclusive": 4096},
    "datum": {"engineSeaLevelY": 63, "highestOceanWaterBlockY": 62},
}


class Configure(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="riven-configure-"))
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        for side in ("server", "client"):
            (self.root / side / "config").mkdir(parents=True)
            (self.root / side / "config/cubicchunks.cfg").write_text(CUBIC)
            (self.root / side / "config/forge_early.cfg").write_text("")
        (self.root / "server/server.properties").write_text(
            "level-type=DEFAULT\nmotd=CubicChunks only - local performance\n"
        )
        (self.root / "client/options.txt").write_text("fancyGraphics:false\n")
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as jar:
            jar.writestr("riven-terrain-height-reference.json", json.dumps(REFERENCE))
            jar.writestr("riven-biomes.json", json.dumps({"revision": 3}))
        (self.root / "server/mods").mkdir()
        (self.root / "server/mods/riven.jar").write_bytes(buffer.getvalue())
        self.launch = {"members": [{"member": "riven:mod@both", "kind": "product"}]}
        self.placed = {"riven:mod@both": {"server": self.root / "server/mods/riven.jar"}}

    def run_configure(self, **params):
        return configure_module.configure(
            instance=self.root, launch=self.launch, params=params, placed=self.placed
        )

    def test_the_world_and_client_take_rivens_settings(self):
        record = self.run_configure(scale=2.0, delivery="save", preview_threads=4)
        cubic = (self.root / "server/config/cubicchunks.cfg").read_text()
        self.assertIn("I:defaultMinHeight=-2048", cubic)
        self.assertIn("I:defaultMaxHeight=4096", cubic)
        self.assertIn("S:deliveryMode=SAVE", cubic)
        self.assertIn(
            "I:previewThreads=4", (self.root / "client/config/cubicchunks.cfg").read_text()
        )
        properties = (self.root / "server/server.properties").read_text()
        self.assertIn("level-type=riven", properties)
        self.assertIn("generator-settings=scale=2\n", properties)
        self.assertIn("spawn-animals=false", properties)
        self.assertIn("fancyGraphics:true", (self.root / "client/options.txt").read_text())
        self.assertEqual(
            (self.root / "client/config/splash.properties").read_text(), "enabled=false\n"
        )
        self.assertIn("=false", (self.root / "server/config/forge_early.cfg").read_text())
        self.assertEqual(record["layoutScale"], 2.0)
        self.assertEqual(record["definitions"]["riven-biomes.json"]["revision"], 3)

    def test_defaults_and_refusals(self):
        record = self.run_configure()
        self.assertEqual(record["cubatureDelivery"], "PREFETCH")
        self.assertIn("generator-settings=\n", (self.root / "server/server.properties").read_text())
        with self.assertRaisesRegex(ValueError, "scale must be"):
            self.run_configure(scale=9)


if __name__ == "__main__":
    unittest.main()
