"""Riven's drivers in a Workbench driver session, on a small instance folder: where Cubature's lab tools and observer
come from, what a measurement and a pregeneration set up in the instance they work on, and the drivers project.toml
declares. The session itself (Cubature's lab_session) stands in as a small object with the same face."""

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import tomllib
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import cubature_tools
import measure_pipeline
import pregenerate_map

ROOT = Path(__file__).resolve().parents[1]
CONFIG = "general {\n    I:verticalCubeLoadDistance=12\n    S:deliveryMode=PREFETCH\n}\n"


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, bytes):
        path.write_bytes(data)
    else:
        path.write_text(data, encoding="utf-8")
    return path


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class FakeSession:
    """What Riven's drivers read of a session: its folder, its instance and the lab's record of it."""

    def __init__(self, root):
        self.id = "20261124-101500-a1b2c3"
        self.folder = root / "sessions" / self.id
        self.instance = root / "instances" / "riven-default"
        self.folder.mkdir(parents=True)
        for side in ("server", "client"):
            write(self.instance / side / "config/cubicchunks.cfg", CONFIG)
            write(self.instance / side / "mods/riven.jar", b"riven")
            write(self.instance / side / "mods/cubature.jar", b"cubature")
        write(
            self.instance / "server/server.properties",
            "level-name=world\nlevel-seed=8675309\nview-distance=12\n",
        )
        write(self.instance / "server/user_jvm_args.txt", "-Xmx8G\n")
        write(self.instance / "client/options.txt", "renderDistance:12\n")
        self.java = write(root / "jdk/bin/java.exe", b"java")
        self.store = root / "store"
        self.launch = {
            "members": [{"name": "cubature", "commit": "c0ffee"}],
            "store": str(self.store),
        }
        self.launch_path = self.folder / "launch.json"
        self.receipt = {
            "configure": {"record": {"worldType": "riven", "cubatureDelivery": "PREFETCH"}}
        }

    def mod_hashes(self):
        return {
            side: {p.name: digest(p) for p in sorted((self.instance / side / "mods").glob("*.jar"))}
            for side in ("server", "client")
        }

    def manifest(self):
        return {
            "name": self.instance.name,
            "server": str(self.instance / "server"),
            "client": str(self.instance / "client"),
            "java": str(self.java),
            "seed": 8675309,
            "jvmProfile": {"collector": "ZGC", "maxHeapMiB": 8192, "arguments": ["-Xmx8G"]},
            "viewDistance": {"horizontal": 12, "vertical": 12, "clientRender": 12},
            "modHashes": self.mod_hashes(),
            "labProperties": {},
        }

    def member(self, name):
        placed = (
            self.instance / "server/mods" / ("riven.jar" if name == "riven" else "cubature.jar")
        )
        return {
            "member": name + ":mod@both",
            "file": name + ".jar",
            "sha256": digest(placed),
            "placed": [placed],
        }

    def copy(self):
        return self


class Fixture(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="riven-drivers-"))
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.session = FakeSession(self.root)
        self.observer = write(self.root / "observer.jar", b"observer")


class CubatureTools(Fixture):
    def test_a_session_exports_cubatures_tools_at_the_launchs_commit(self):
        repo = self.root / "cubature"
        write(repo / "tools/lab_marker.py", "VALUE = 1\n")
        git = ["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@t"]
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        subprocess.run([*git, "add", "-A"], check=True)
        subprocess.run([*git, "commit", "-q", "-m", "tools"], check=True)
        commit = subprocess.run(
            [*git, "rev-parse", "HEAD"], capture_output=True, text=True, check=True
        ).stdout.strip()
        write(
            self.session.launch_path,
            json.dumps({"members": [{"name": "cubature", "commit": commit}]}),
        )
        environ = {"WB_SESSION": str(self.session.folder), "CUBATURE_REPOSITORY": str(repo)}
        tools, at = cubature_tools.find(environ)
        self.assertEqual((tools, at), (self.session.folder / "cubature-tools/tools", commit))
        self.assertEqual((tools / "lab_marker.py").read_text(), "VALUE = 1\n")
        self.assertEqual(cubature_tools.find(environ)[0], tools)  # a second import reuses it
        self.assertEqual(cubature_tools.find({"CUBATURE_TOOLS": str(repo / "tools")})[1], None)

    def test_the_observer_is_the_stores_build_at_the_launchs_commit(self):
        store = self.session.store
        kept = write(
            store / "files" / ("a" * 64) / "cubicchunks-lab-0.1.0-cleanroom.jar", b"observer"
        )
        write(
            store / "index/cubature/tree.json",
            json.dumps(
                {
                    "commits": ["c0ffee"],
                    "artifacts": {
                        "cubicchunks-lab-0.1.0-cleanroom.jar": {
                            "sha256": "a" * 64,
                            "from": "lab/build/cleanroom/libs/cubicchunks-lab-0.1.0-cleanroom.jar",
                        }
                    },
                }
            ),
        )
        self.assertEqual(cubature_tools.lab_observer(self.session.launch), kept)
        with self.assertRaisesRegex(RuntimeError, "no lab observer"):
            cubature_tools.lab_observer(
                {**self.session.launch, "members": [{"name": "cubature", "commit": "f00"}]}
            )


class Measurement(Fixture):
    def prepared(self, *options):
        args = measure_pipeline.parser().parse_args(
            ["--lab-artifact", str(self.observer), *options]
        )
        workload = json.loads(
            (ROOT / "dev/benchmarks/terrain-pipeline.json").read_text(encoding="utf-8")
        )
        capabilities = {"configurableCubeCount": True, "knownFixedCubeCap": None}
        with mock.patch(
            "observability.profiles.packet_policy_capabilities", return_value=capabilities
        ):
            return measure_pipeline.prepare(self.session, args, workload)

    def test_the_copy_gets_the_observer_workload_and_packet_policy_and_passes_its_checks(self):
        manifest = self.prepared("--cube-packet-cubes", "64", "--preview-threads", "4")
        server = Path(manifest["server"])
        self.assertEqual(
            manifest["modHashes"]["client"]["cubicchunks-lab.jar"], digest(self.observer)
        )
        self.assertTrue((server / "config/cubiclab-workload.json").is_file())
        self.assertEqual(manifest["cubeExperiments"]["maxCubePacketCubes"], "64")
        self.assertIn("I:previewThreads=4", (server / "config/cubicchunks.cfg").read_text())
        self.assertEqual(manifest["cubatureDelivery"], "BASELINE")
        self.assertEqual(manifest["sourceArtifact"]["sha256"], digest(server / "mods/riven.jar"))
        self.assertEqual(manifest["generator"], {"worldType": "riven"})
        self.assertEqual(manifest["labProperties"]["cubature.clientWithoutRenderer"], "true")
        measure_pipeline.check(manifest)
        write(server / "mods/extra.jar", b"extra")
        with self.assertRaisesRegex(RuntimeError, "mod set changed"):
            measure_pipeline.check(manifest)

    def test_a_world_is_adopted_only_from_an_instance_with_the_same_definitions(self):
        source = self.root / "instances/map-10k"
        write(source / "server/server.properties", "level-name=world\n")
        write(source / "server/world/level.dat", b"level")
        jar = self.root / "riven-defs.jar"
        import zipfile

        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("riven-terrain.json", "{}")
        for home in (source / "server", self.session.instance / "server"):
            (home / "mods").mkdir(parents=True, exist_ok=True)
            shutil.copy2(jar, home / "mods/riven.jar")
        manifest = self.prepared("--world-from", str(source))
        self.assertEqual(
            manifest["worldFrom"]["levelSha256"], digest(source / "server/world/level.dat")
        )
        measure_pipeline.check(manifest)

    def test_further_options_come_from_lab_driver_args_with_windows_paths_kept(self):
        line = r"--diagnostic --world-from '..\lab\instances\map 10k'"
        with mock.patch.dict(os.environ, {"LAB_DRIVER_ARGS": line}), mock.patch("sys.argv", ["x"]):
            self.assertEqual(
                measure_pipeline.driver_args(),
                ["--diagnostic", "--world-from", r"..\lab\instances\map 10k"],
            )
        self.assertEqual(measure_pipeline.driver_args(["--jfr"]), ["--jfr"])


class Pregeneration(Fixture):
    def args(self, **values):
        return SimpleNamespace(
            **{"map": None, "workers": 16, "ahead": 24, "lab_artifact": self.observer, **values}
        )

    def test_the_first_session_prepares_the_plan_and_later_sessions_resume_it(self):
        manifest = pregenerate_map.setup(self.session, self.args(map="world-8k"), prepare_new=True)
        plan = json.loads((self.session.instance / pregenerate_map.PLAN).read_text())
        self.assertEqual(plan["pregeneration"]["id"], "world-8k")
        self.assertIn(
            "I:concurrentGenerationThreads=16",
            (self.session.instance / "server/config/cubicchunks.cfg").read_text(),
        )
        self.assertEqual(json.loads((self.session.folder / "lab.json").read_text()), manifest)
        again = pregenerate_map.setup(self.session, self.args(), prepare_new=False)
        self.assertEqual(again["pregeneration"], manifest["pregeneration"])
        with self.assertRaisesRegex(RuntimeError, "holds the world-8k map"):
            pregenerate_map.setup(self.session, self.args(map="map-10k"), prepare_new=True)
        write(self.session.instance / "server/mods/extra.jar", b"extra")
        with self.assertRaisesRegex(RuntimeError, "inputs changed"):
            pregenerate_map.setup(self.session, self.args(), prepare_new=False)

    def test_a_pregeneration_is_prepared_only_on_an_instance_without_a_world(self):
        write(self.session.instance / "server/world/level.dat", b"played")
        with self.assertRaisesRegex(RuntimeError, "without a world"):
            pregenerate_map.setup(self.session, self.args(), prepare_new=True)
        with self.assertRaisesRegex(RuntimeError, "holds no pregeneration"):
            pregenerate_map.setup(self.session, self.args(), prepare_new=False)


class Declared(unittest.TestCase):
    def test_each_driver_runs_a_command_of_a_riven_tool(self):
        project = tomllib.loads((ROOT / "project.toml").read_text(encoding="utf-8"))
        drivers = project["launch"]["default"]["drivers"]
        self.assertEqual(set(drivers), {"pipeline", "capture", "pregenerate", "pregenerate-verify"})
        commands = {
            "riven_observe.py": {"capture"},
            "pregenerate_map.py": {"run", "verify"},
            "measure_pipeline.py": set(),
        }
        for name, driver in drivers.items():
            python, script, *rest = driver["command"]
            self.assertEqual((python, driver["cwd"]), ("{python}", "{root}"), name)
            tool = Path(script.replace("{root}/", ""))
            self.assertTrue((ROOT / tool).is_file(), name)
            self.assertLessEqual(set(rest), commands[tool.name], name)


if __name__ == "__main__":
    unittest.main()
