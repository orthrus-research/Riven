import json
import tempfile
import unittest
from pathlib import Path

from observability.lod import _encode, children, lod_report

HEADER = "nanos,thread,op,pos,detail,x,z,a,b,c,d\n"
MS = 1_000_000


def write(directory, side, rows, epoch_micros, closed=True):
    lines = [HEADER]
    for t, op, pos, *values in rows:
        values = (list(values) + [0, 0, 0, 0])[:4]
        detail = pos & 0x7F if pos else 0
        lines.append(",".join(str(v) for v in [t, 1, op, pos, detail, 0, 0, *values]) + "\n")
    (directory / f"lod-{side}.csv").write_text("".join(lines), encoding="utf-8")
    (directory / f"lod-{side}.json").write_text(
        json.dumps(
            {"schema": 1, "originEpochMicros": epoch_micros, "closed": closed, "dropped": 0}
        ),
        encoding="utf-8",
    )


class LodTraceTest(unittest.TestCase):
    def test_children_cover_the_parent_with_negative_coordinates(self):
        parent = _encode(8, -1, 2)
        self.assertEqual(
            sorted(children(parent)),
            sorted(_encode(7, x, z) for x in (-2, -1) for z in (4, 5)),
        )

    def test_stages_promotions_and_cross_side_alignment(self):
        parent = _encode(8, 0, 0)
        kids = children(parent)
        tile = _encode(8, 3, 3)
        # The server JVM started 5 ms after the client; its times are 5 ms behind on the client clock.
        server = [
            (0, "srv-request", tile, 1),
            (1 * MS, "gen-submitted", tile, 2),
            (4 * MS, "gen-started", tile, 2),
            (4 * MS, "preview-queued", tile, 2),
            (6 * MS, "preview-start", tile),
            (16 * MS, "preview-end", tile, 10 * MS),
            (17 * MS, "gen-finished", tile, 0),
            (20 * MS, "srv-payload", tile, 4096, 7),
            (30 * MS, "srv-response", 0, 0, 7),
        ]
        client = [
            (0, "lab-start", 0),
            (2 * MS, "net-queued", tile, 0, 1),
            (3 * MS, "net-sent", tile),
            (45 * MS, "net-response", tile, 0, 1),
            (50 * MS, "section-new", parent),
            (51 * MS, "upload-done", parent, 1),
            (52 * MS, "render-state", parent, 7),
        ]
        for i, kid in enumerate(kids):
            client += [
                (60 * MS, "section-new", kid),
                (61 * MS, "build-queued", kid),
                (62 * MS, "build-start", kid),
                ((64 + i) * MS, "build-end", kid, 1),
                ((64 + i) * MS, "upload-start", kid, 100, 0),
                ((70 + i) * MS, "upload-done", kid, 2 if i == 0 else 1),
            ]
        client += [((80 + i) * MS, "render-state", kid, 7) for i, kid in enumerate(kids)]
        client += [(90 * MS, "upload-done", kids[0], 1), (200 * MS, "lab-finish", 0)]
        client += [(300 * MS, "section-new", _encode(6, 9, 9))]
        with tempfile.TemporaryDirectory() as temporary:
            trace, out = Path(temporary) / "trace", Path(temporary) / "out"
            trace.mkdir()
            write(trace, "client", client, 1_000_000)
            write(trace, "server", server, 1_005_000)
            report = lod_report(trace, out, "auto", charts=False)["report"]
            self.assertTrue((out / "lod-report.txt").is_file())
        server_stages = report["server"]["stages"]
        self.assertEqual(server_stages["generation queue wait"]["p50Ms"], 3.0)
        self.assertEqual(server_stages["preview executor wait"]["p50Ms"], 2.0)
        self.assertEqual(server_stages["preview sampling"]["p50Ms"], 10.0)
        client_stages = report["client"]["stages"]
        self.assertEqual(client_stages["network queue wait"]["p50Ms"], 1.0)
        self.assertEqual(client_stages["request round trip"]["p50Ms"], 42.0)
        self.assertEqual(client_stages["build (mesh)"]["count"], 4)
        sections = report["client"]["sections"]
        # The lab window excludes the section created after lab-finish.
        self.assertEqual(sections["created"], 5)
        self.assertEqual(sections["uploadedEmptyFirst"], 1)
        promotions = report["client"]["promotions"]
        self.assertEqual(promotions["promotedParents"], 1)
        self.assertEqual(promotions["delay"]["p50Ms"], 23.0)
        cross = report["crossSide"]
        # Client sent at 3 ms; the server received at its 0 ms, which is 5 ms on the client clock.
        self.assertEqual(cross["client send to server receive"]["p50Ms"], 2.0)
        self.assertEqual(cross["payload encoded to response sent"]["p50Ms"], 10.0)
        # Response sent at server 30 ms = client 35 ms; received at 45 ms.
        self.assertEqual(cross["server response to client receive"]["p50Ms"], 10.0)
        self.assertEqual(report["warnings"], [])

    def test_a_repeated_queue_event_does_not_start_a_new_wait(self):
        pos = _encode(6, 1, 1)
        rows = [
            (0, "build-queued", pos),
            (5 * MS, "build-queued", pos),
            (8 * MS, "build-start", pos),
        ]
        rows += [(20 * MS, "build-queued", pos), (21 * MS, "build-start", pos)]
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / "trace"
            trace.mkdir()
            write(trace, "client", rows, 0)
            report = lod_report(trace, Path(temporary) / "out", "all", charts=False)["report"]
        wait = report["client"]["stages"]["build queue wait"]
        self.assertEqual((wait["count"], wait["meanMs"], wait["maxMs"]), (2, 4.5, 8.0))

    def test_partial_last_line_and_open_trace_are_reported(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / "trace"
            trace.mkdir()
            write(trace, "client", [(0, "section-new", _encode(6, 0, 0))], 0, closed=False)
            with (trace / "lod-client.csv").open("a", encoding="utf-8") as stream:
                stream.write("12,1,upload-do")
            warnings = lod_report(trace, Path(temporary) / "out", "all", charts=False)["warnings"]
        self.assertIn("server: no trace", warnings)
        self.assertTrue(any("did not close" in w for w in warnings))
        self.assertTrue(any("partial lines" in w for w in warnings))


if __name__ == "__main__":
    unittest.main()
