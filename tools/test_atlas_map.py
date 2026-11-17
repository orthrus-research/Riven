"""Tests for the map site's curated per-render files (tools/atlas/tiles.py)."""

import gzip
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from atlas.overlay import merge, read_source  # noqa: E402
from atlas.tiles import (  # noqa: E402
    pin,
    read_index,
    read_pins,
    remove,
    set_overlay,
    set_pins,
    write_index,
)

RID = "101-v148-0_0-2048-s16-abcdef01"


class Site:
    """A site directory holding one published render's catalogue entry."""

    def __init__(self, root: Path):
        self.root = root
        (root / "renders" / RID).mkdir(parents=True)
        write_index(root, [{"id": RID, "created": "2026-11-17 12:00", "note": ""}])

    def entry(self):
        return next(e for e in read_index(self.root) if e["id"] == RID)


class PinTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.site = Site(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def test_pin_normalizes_its_note_and_coordinates(self):
        self.assertEqual(
            pin("12", -3, "  a  ridge\n top "), {"x": 12, "z": -3, "note": "a ridge top"}
        )

    def test_pin_refuses_an_empty_or_long_note(self):
        with self.assertRaises(ValueError):
            pin(0, 0, "   ")
        with self.assertRaises(ValueError):
            pin(0, 0, "x" * 201)

    def test_adding_pins_merges_by_column_and_counts_them(self):
        set_pins(self.site.root, RID, [{"x": 1, "z": 2, "note": "first"}])
        pins = set_pins(
            self.site.root,
            RID,
            [{"x": 1, "z": 2, "note": "renamed"}, {"x": 5, "z": -9, "note": "b"}],
        )
        self.assertEqual(
            pins, [{"x": 5, "z": -9, "note": "b"}, {"x": 1, "z": 2, "note": "renamed"}]
        )
        self.assertEqual(self.site.entry()["pins"], 2)
        self.assertEqual(read_pins(self.site.root / "renders" / RID / "pins.json"), pins)

    def test_replacing_with_no_pins_drops_the_file_and_the_count(self):
        set_pins(self.site.root, RID, [{"x": 1, "z": 2, "note": "a"}])
        self.assertEqual(set_pins(self.site.root, RID, [], replace=True), [])
        self.assertFalse((self.site.root / "renders" / RID / "pins.json").exists())
        self.assertNotIn("pins", self.site.entry())

    def test_pins_need_a_published_render(self):
        with self.assertRaises(SystemExit):
            set_pins(self.site.root, "missing", [{"x": 1, "z": 2, "note": "a"}])

    def test_a_pins_file_must_be_a_list(self):
        path = Path(self.tmp.name) / "pins.json"
        path.write_text(json.dumps({"x": 1}), encoding="utf-8")
        with self.assertRaises(ValueError):
            read_pins(path)

    def test_remove_drops_the_render_and_its_pins(self):
        set_pins(self.site.root, RID, [{"x": 1, "z": 2, "note": "a"}])
        remove(self.site.root, RID)
        self.assertEqual(read_index(self.site.root), [])
        self.assertFalse((self.site.root / "renders" / RID).exists())


def nbt_level(path: Path, spawn=(64, 70, -64), seed=8675309):
    """A gzipped level.dat holding Data {SpawnX, SpawnY, SpawnZ, RandomSeed, LevelName}."""

    def name(text):
        raw = text.encode()
        return struct.pack(">H", len(raw)) + raw

    data = b"".join(
        bytes([3]) + name(k) + struct.pack(">i", v)
        for k, v in zip(("SpawnX", "SpawnY", "SpawnZ"), spawn)
    )
    data += bytes([4]) + name("RandomSeed") + struct.pack(">q", seed)
    data += bytes([8]) + name("LevelName") + name("world")
    data += bytes([9]) + name("ServerBrands") + bytes([8]) + struct.pack(">i", 1) + name("forge")
    root = bytes([10]) + name("") + bytes([10]) + name("Data") + data + b"\0" + b"\0"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(root))


def capture(instance: Path, poses, seed=8675309):
    """A capture's runs/write/result.json with one flight scenario."""
    result = {
        "benchmarkWorkload": {"id": "flight-test-v1", "seed": seed},
        "scenarios": [
            {"scenario": "full-view", "client": {}},
            {
                "scenario": "spectator-flight",
                "client": {"spectatorFlight": {"observations": [{"pose": p} for p in poses]}},
            },
        ],
    }
    path = instance / "runs/write/result.json"
    path.parent.mkdir(parents=True)
    path.write_text(json.dumps(result), encoding="utf-8")
    return path


class OverlayTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_tour_file_gives_its_views_with_the_seed_in_its_name(self):
        path = self.root / "coasts-2485331.json"
        views = [{"label": "cliff", "x": 960.5, "y": 240, "z": -13589.5, "yaw": 180.0}]
        path.write_text(json.dumps({"views": views}), encoding="utf-8")
        (point,) = read_source(path)["points"]
        self.assertEqual(point["kind"], "tour")
        self.assertEqual((point["x"], point["z"], point["seed"]), (960.5, -13589.5, 2485331))
        self.assertEqual(point["label"], "cliff")

    def test_a_capture_gives_its_recorded_path_without_repeats(self):
        poses = [[0, 144, 5, -90, 15], [16, 144, 5, -90, 15], [16, 144, 5, -90, 15], [32, 144, 5]]
        found = read_source(capture(self.root / "fly-01", poses))
        (path,) = found["paths"]
        self.assertEqual(path["points"], [[0, 5, 144], [16, 5, 144], [32, 5, 144]])
        self.assertEqual((path["seed"], path["source"]), (8675309, "capture fly-01"))

    def test_a_level_dat_gives_the_world_spawn_and_seed(self):
        nbt_level(self.root / "world/level.dat", spawn=(-12, 81, 300), seed=-42)
        (point,) = read_source(self.root / "world/level.dat")["points"]
        self.assertEqual((point["x"], point["y"], point["z"], point["seed"]), (-12, 81, 300, -42))

    def test_an_instance_directory_gives_spawn_and_flights(self):
        instance = self.root / "fly-02"
        nbt_level(instance / "server/world/level.dat")
        capture(instance, [[0, 100, 0], [10, 100, 0]])
        found = read_source(instance)
        self.assertEqual([p["source"] for p in found["points"]], ["world fly-02"])
        self.assertEqual(len(found["paths"]), 1)

    def test_an_unknown_file_is_refused(self):
        path = self.root / "other.json"
        path.write_text(json.dumps({"something": 1}), encoding="utf-8")
        with self.assertRaises(ValueError):
            read_source(path)

    def test_a_source_added_again_replaces_its_items(self):
        a = {
            "points": [{"kind": "tour", "x": 0, "z": 0, "label": "a", "source": "tour t"}],
            "paths": [],
        }
        b = {
            "points": [{"kind": "spawn", "x": 1, "z": 1, "label": "s", "source": "world w"}],
            "paths": [],
        }
        again = {
            "points": [{"kind": "tour", "x": 5, "z": 5, "label": "a2", "source": "tour t"}],
            "paths": [],
        }
        merged = merge(merge(merge({}, a), b), again)
        self.assertEqual([p["label"] for p in merged["points"]], ["s", "a2"])

    def test_set_overlay_counts_the_items_in_the_catalogue(self):
        site = Site(self.root / "site")
        added = read_source(capture(self.root / "fly-03", [[0, 1, 0], [9, 1, 0]]))
        set_overlay(site.root, RID, added)
        self.assertEqual(site.entry()["overlay"], 1)


if __name__ == "__main__":
    unittest.main()
