"""The content check: a texture is up to date when its pixels are, however zlib packed them."""

import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent / "content"))
import build_content  # noqa: E402

IMAGE = [[(255, 0, 0, 255), (0, 255, 0, 128)], [(0, 0, 255, 255), (9, 9, 9, 0)]]
COMPRESS = zlib.compress


def packed_by_another_zlib(image):
    """The same PNG with its image data packed differently, as another zlib build would."""
    with mock.patch.object(build_content.zlib, "compress", lambda data, level: COMPRESS(data, 0)):
        return build_content.png(image)


class HoldsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.path = Path(self.folder.name) / "texture.png"

    def tearDown(self):
        self.folder.cleanup()

    def test_the_same_pixels_packed_differently_are_up_to_date(self):
        wanted = build_content.png(IMAGE)
        self.path.write_bytes(packed_by_another_zlib(IMAGE))
        self.assertNotEqual(self.path.read_bytes(), wanted)
        self.assertTrue(build_content.holds(self.path, wanted))

    def test_a_changed_pixel_is_out_of_date(self):
        changed = [row[:] for row in IMAGE]
        changed[1][1] = (9, 9, 10, 0)
        self.path.write_bytes(packed_by_another_zlib(changed))
        self.assertFalse(build_content.holds(self.path, build_content.png(IMAGE)))

    def test_other_files_compare_by_bytes(self):
        text = self.path.with_suffix(".json")
        text.write_bytes(b'{"a": 1}\n')
        self.assertTrue(build_content.holds(text, b'{"a": 1}\n'))
        self.assertFalse(build_content.holds(text, b'{"a":1}\n'))
        self.path.write_bytes(b"\x89PNG\r\n\x1a\nnot a png")
        self.assertFalse(build_content.holds(self.path, build_content.png(IMAGE)))
        self.assertFalse(build_content.holds(self.path.with_name("absent.png"), b""))


if __name__ == "__main__":
    unittest.main()
