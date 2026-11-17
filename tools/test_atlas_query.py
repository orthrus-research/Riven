import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from atlas.paint import Atlas
from atlas.query import Query, QueryError


def atlas(directory: Path) -> Atlas:
    """A 2 x 3 render: west column marine, a mountain stage with reasons."""
    meta = {
        "minX": -24,
        "minZ": 8,
        "step": 16,
        "width": 3,
        "height": 2,
        "flags": {"marine": 1, "wet": 8},
        "landforms": ["PLAIN", "HILLS", "MOUNTAIN"],
        "stages": [{"name": "mountain", "reasons": ["", "ACCEPTED", "LOW_UPLIFT"]}],
    }
    (directory / "atlas.json").write_text(json.dumps(meta))
    np.array([[40, 70, 120], [40, 72, 200]], "<i2").tofile(directory / "surface.i16")
    np.array([[1, 0, 0], [1, 0, 0]], np.uint8).tofile(directory / "flags.u8")
    np.array([[255, 0, 2], [255, 1, 2]], np.uint8).tofile(directory / "landform.u8")
    np.array([[0, 0, 5.5], [0, -1, 30]], "<f4").tofile(directory / "delta_mountain.f32")
    np.array([[0, 2, 1], [0, 2, 1]], np.uint8).tofile(directory / "reason_mountain.u8")
    return Atlas(directory)


class QueryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = tempfile.TemporaryDirectory()
        self.query = Query(atlas(Path(self.root.name)))

    def tearDown(self) -> None:
        self.root.cleanup()

    def mask(self, expression: str) -> list[list[bool]]:
        return self.query.run(expression).tolist()

    def test_names_flags_and_categories(self) -> None:
        self.assertEqual(self.mask("marine"), [[True, False, False], [True, False, False]])
        self.assertEqual(self.mask('landform == "MOUNTAIN"'), [[False, False, True]] * 2)
        self.assertEqual(self.mask('"HILLS" == landform'), [[False] * 3, [False, True, False]])
        self.assertEqual(self.mask('landform in ("PLAIN", "HILLS")'), [[False, True, False]] * 2)

    def test_stage_layers_arithmetic_and_chains(self) -> None:
        self.assertEqual(
            self.mask('stage.mountain.reason == "ACCEPTED" and stage.mountain.delta > 10'),
            [[False] * 3, [False, False, True]],
        )
        self.assertEqual(self.mask("abs(stage.mountain.delta) >= 1"), [[0, 0, 1], [0, 1, 1]])
        self.assertEqual(self.mask("60 < surface - 10 <= 110"), [[0, 0, 1], [0, 1, 0]])
        self.assertEqual(self.mask("x == -8 and z == 24"), [[0, 0, 0], [0, 1, 0]])
        self.assertEqual(self.mask("not marine and slope > 2"), [[0, 1, 1], [0, 1, 1]])

    def test_a_name_never_recorded_matches_nothing(self) -> None:
        self.assertFalse(self.query.run('stage.mountain.reason == "NO_SITE"').any())

    def test_refuses_everything_outside_the_whitelist(self) -> None:
        for expression in (
            "__import__('os').system('echo hi')",
            "surface.__class__",
            "stage.mountain.__dict__",
            "stage.mountain",
            "stage.nope.delta",
            "open('x')",
            "(lambda: 1)()",
            "surface[0]",
            "surface ** 2 > 1",
            "[s for s in surface]",
            "nope > 1",
            "surface",
            "surface > 1 + True",
            "landform > 'HILLS'",
            "landform == 2",
            "surface == 'HILLS'",
            "landform in ('HILLS', 2)",
            "surface in (x,)",
            "surface > 1 if marine else surface < 1",
            "f'{surface}' == 'a'",
            "surface > 1 and 3",
            "(" * 400 + "surface > 1" + ")" * 400,
            "surface >",
        ):
            with self.subTest(expression=expression), self.assertRaises(QueryError):
                self.query.run(expression)


if __name__ == "__main__":
    unittest.main()
