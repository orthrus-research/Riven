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

    def test_slot_breakdown_pools_and_server_health(self):
        tile = _encode(6, 2, 2)
        server = [
            (0, "gen-started", tile, 0),
            (3 * MS, "preview-start", tile),
            (10 * MS, "preview-end", tile, 7 * MS),
            (11 * MS, "gen-finished", tile, 0),
            (11 * MS, "retrieval-done", tile, 1),
            (15 * MS, "retrieval-end", tile, 4 * MS),
            (20 * MS, "pool", 0, 6, 2, 0, 10),
            (40 * MS, "pool", 0, 2, 4, 1, 12),
            (20 * MS, "pool", 1, 0, 1, 1, 3),
            (30 * MS, "server-tick", 0, 40 * MS),
            (80 * MS, "server-tick", 0, 60 * MS),
            (90 * MS, "dh-health", 0, 30_000, 60_000, 0),
            (99 * MS, "dh-health", 0, 10_000, 20_000, 1),
        ]
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / "trace"
            trace.mkdir()
            write(trace, "server", server, 0)
            result = lod_report(trace, Path(temporary) / "out", "all", charts=False)
        s = result["report"]["server"]
        slot = s["slot"]
        self.assertEqual(slot["waiting for a thread"]["p50Ms"], 3.0)
        self.assertEqual(slot["sampling"]["p50Ms"], 7.0)
        self.assertEqual(slot["handoff to DH"]["p50Ms"], 1.0)
        self.assertEqual(slot["whole slot"]["p50Ms"], 11.0)
        self.assertEqual(slot["DH update after the slot"]["p50Ms"], 4.0)
        world_gen = s["pools"]["World Gen"]
        self.assertEqual(
            (world_gen["meanQueued"], world_gen["meanRunning"], world_gen["pausedShare"]),
            (4.0, 3.0, 0.5),
        )
        self.assertEqual(s["pools"]["LOD Builder"]["pausedShare"], 0.0)
        self.assertEqual(s["ticks"]["maxMs"], 60.0)
        self.assertEqual(s["dhHealth"]["unhealthyShare"], 0.5)
        self.assertIn("World Gen", result["text"])

    def test_tile_use_places_tiles_against_the_native_view_and_travel(self):
        second = 1000 * MS
        # The player flies +x at 20 blocks/s and is at (40, 0), cube (2, 0), when the tiles
        # arrive; with 12 view cubes the native square spans x and z [-160, 240) and [-192, 208).
        inside, outside = _encode(6, 1, 0), _encode(6, 5, 0)  # [64, 128) and [320, 384)
        straddling = _encode(7, 1, 0)  # [128, 256) x [0, 128): 112 of its 128 columns inside
        behind = _encode(6, -3, 0)  # [-192, -128): 32 of its 64 columns inside
        client: list[tuple] = [(t * second, "player", 0, 20 * t, 64, 0, -90) for t in (0, 1, 2)]
        for tile in (inside, outside, straddling, behind):
            client += [
                (second // 2, "net-queued", tile, 0, 1),
                (2 * second, "net-response", tile, 0, 1),
            ]
        client.append(
            (2 * second, "net-response", _encode(6, 9, 9), 2, 1)
        )  # a failure: not counted
        with tempfile.TemporaryDirectory() as temporary:
            instance = Path(temporary)
            trace = instance / "lod-trace"
            trace.mkdir()
            write(trace, "client", client, 0)
            (instance / "standalone.json").write_text(
                json.dumps({"viewDistance": {"horizontal": 12}}), encoding="utf-8"
            )
            result = lod_report(trace, instance / "out", "all", charts=False)
        use = result["report"]["client"]["tileUse"]
        self.assertEqual(use["viewCubes"], 12)
        full, half = use["byDetail"]["6"], use["byDetail"]["7"]
        self.assertEqual(full["all"]["tiles"], 3)
        self.assertEqual(full["ahead"]["tiles"], 2)
        self.assertEqual(full["ahead"]["nativeCoveredShare"], 0.5)
        self.assertEqual(full["behind"]["nativeCoveredShare"], 0.5)
        self.assertEqual(half["ahead"]["nativeCoveredShare"], 0.875)
        self.assertEqual(full["ahead"]["wantedToArrival"]["p50Ms"], 1500.0)
        self.assertNotIn("assumes", " ".join(result["warnings"]))
        self.assertIn("1-block: 3 tiles", result["text"])

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


class ReversionTest(unittest.TestCase):
    def test_lost_columns_and_hidden_children_split_by_movement(self):
        coarse, fine = _encode(10, 1, 1), _encode(7, 3, 3)
        second = 1_000 * MS
        # Player flies 20 blocks/s for 3 s, then rests 3 s.
        player = [(t * second, "player", 0, 20 * min(t, 3), 100, 0) for t in range(7)]
        client = player + [
            (int(1.5 * second), "merge-same", coarse, 900, 40, 12, 5),
            (int(2.5 * second), "merge-same", fine, 100, 0, 0, 5),
            (int(2.5 * second), "merge-stamp", fine, 64),
            (int(2.6 * second), "fallback-start", fine, 3, 4),
            (int(2.7 * second), "fallback", 0, 6, 2, 5),
            (int(5.5 * second), "merge-same", coarse, 0, 0, 30, 5),
            (int(5.6 * second), "merge-restore", coarse, 800),
            (int(5.7 * second), "fallback", 0, 1, 0, 0),
            (int(0.5 * second), "render-data", fine, 0, 4096, 0),
            (int(2.2 * second), "render-data", fine, 3000, 1000, 96),
            (int(5.2 * second), "render-data", fine, 0, 4096, 0),
        ]
        server = [(int(1.2 * second), "merge-same", coarse, 7, 0, 0, 5)]
        with tempfile.TemporaryDirectory() as tmp:
            trace = Path(tmp) / "trace"
            trace.mkdir()
            write(trace, "client", client, 1_000_000)
            write(trace, "server", server, 1_000_000)
            report = lod_report(trace, Path(tmp) / "out", charts=False)["report"]
        rv = report["reversion"]
        self.assertEqual(rv["moving"]["fineColumnsLost"], 1000)
        self.assertEqual(rv["moving"]["columnsStamped"], 64)
        self.assertEqual(rv["moving"]["serverFineColumnsLost"], 7)
        self.assertEqual(rv["moving"]["fallbackStartsHiding"], 1)
        self.assertEqual(rv["moving"]["meanHiddenChildren"], 5)
        self.assertEqual(rv["resting"]["fineColumnsLost"], 0)
        self.assertEqual(rv["resting"]["columnsFilled"], 30)
        self.assertEqual(rv["resting"]["fineColumnsRestored"], 800)
        self.assertEqual(rv["resting"]["meanHiddenChildren"], 0)
        self.assertEqual(rv["fineColumnsLostByDetail"], {"7": 100, "10": 900})
        shown = rv["shown"]
        self.assertEqual(shown["moving"]["7"], {"builds": 1, "coarse": 1, "regressions": 1})
        self.assertEqual(shown["resting"]["7"], {"builds": 1, "recoveries": 1})
        self.assertEqual(shown["unknown"]["7"], {"builds": 1})
        self.assertIn("LOD reversion", lod_report_text(report))


def lod_report_text(report):
    from observability.lod import render_text

    return render_text(report)
