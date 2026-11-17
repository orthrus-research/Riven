"""Tests for the map site's curated per-render files (tools/atlas/tiles.py)."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from atlas.tiles import pin, read_index, read_pins, remove, set_pins, write_index  # noqa: E402

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


if __name__ == "__main__":
    unittest.main()
