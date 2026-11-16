"""runtime.py's tour files and a run's view distances: the lab's baseline unless raised."""

import json
import tempfile
import unittest
from pathlib import Path

import runtime
from checkouts import CUBATURE_TOOLS

VIEW = {"label": "a", "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0}


def written(content) -> Path:
    folder = tempfile.mkdtemp()
    path = Path(folder) / "tour.json"
    path.write_text(json.dumps(content), encoding="utf-8")
    return path


class TourFileTests(unittest.TestCase):
    def test_a_list_of_views_asks_for_no_distances(self):
        path = written([VIEW])
        self.assertEqual(runtime.load_tour(path), [VIEW])
        self.assertEqual(runtime.tour_view_distance(path), {})

    def test_an_object_carries_views_and_distances(self):
        path = written({"viewDistance": {"clientRender": 20}, "views": [VIEW]})
        self.assertEqual(runtime.load_tour(path), [VIEW])
        self.assertEqual(runtime.tour_view_distance(path), {"clientRender": 20})

    def test_unknown_fields_and_distances_are_refused(self):
        for content in [
            {"views": [VIEW], "speed": 2},
            {"views": [VIEW], "viewDistance": {"render": 20}},
            {"viewDistance": {"clientRender": 20}},
            "views",
        ]:
            with self.assertRaises(ValueError, msg=str(content)):
                runtime.read_tour(written(content))

    def test_the_stale_mesh_walk_asks_for_a_wider_client_view(self):
        path = CUBATURE_TOOLS.parent / "lab/tours/stale-mesh-walk-2485331.json"
        views = runtime.load_tour(path)
        # 61 views walking east, then two stepping back west, facing the edge the cubes left from.
        self.assertEqual(len(views), 63)
        self.assertEqual([v["yaw"] for v in views[-2:]], [90.0, 90.0])
        self.assertLess(views[-1]["x"], views[-3]["x"])
        profile = runtime.run_view_distance(runtime.tour_view_distance(path))
        self.assertEqual(profile, {"horizontal": 12, "vertical": 12, "clientRender": 20})


class RunViewDistanceTests(unittest.TestCase):
    def test_the_baseline_when_nothing_asks(self):
        self.assertEqual(
            runtime.run_view_distance(), {"horizontal": 12, "vertical": 12, "clientRender": 12}
        )

    def test_an_option_wins_over_the_tour(self):
        profile = runtime.run_view_distance(
            {"clientRender": 20, "horizontal": 14}, client_render=24
        )
        self.assertEqual(profile, {"horizontal": 14, "vertical": 12, "clientRender": 24})

    def test_each_distance_may_be_raised(self):
        self.assertEqual(
            runtime.run_view_distance(horizontal=16, vertical=18, client_render=32),
            {"horizontal": 16, "vertical": 18, "clientRender": 32},
        )

    def test_below_the_baseline_is_refused(self):
        with self.assertRaises(RuntimeError):
            runtime.run_view_distance(horizontal=8)
        with self.assertRaises(RuntimeError):
            runtime.run_view_distance({"clientRender": 6})


class ReducedProfileTests(unittest.TestCase):
    REDUCED = {"viewProfile": "near-8"}

    def test_a_reduced_instance_keeps_its_profile(self):
        self.assertEqual(
            runtime.run_view_distance(manifest=self.REDUCED),
            {"horizontal": 8, "vertical": 8, "clientRender": 8},
        )

    def test_a_reduced_instance_refuses_options_and_tour_distances(self):
        with self.assertRaisesRegex(RuntimeError, "reduced view profile near-8"):
            runtime.run_view_distance(client_render=20, manifest=self.REDUCED)
        with self.assertRaisesRegex(RuntimeError, "reduced view profile near-8"):
            runtime.run_view_distance({"clientRender": 20}, manifest=self.REDUCED)

    def test_an_instance_without_a_profile_is_baseline(self):
        self.assertEqual(
            runtime.run_view_distance(client_render=20, manifest={})["clientRender"], 20
        )


class LeaveWorldTests(unittest.TestCase):
    def test_the_client_is_kicked_and_given_time_to_leave(self):
        sent: list[str] = []
        waited: list[float] = []
        original = runtime.lab.send
        runtime.lab.send = lambda server, command: sent.append(command)
        try:
            runtime.leave_world(object(), "CubicLab", wait=waited.append)
        finally:
            runtime.lab.send = original
        self.assertEqual(sent, ["kick CubicLab Tour finished"])
        self.assertEqual(waited, [runtime.LEAVE_SECONDS])


if __name__ == "__main__":
    unittest.main()
