"""DH LOD stage report from the lab's lod-client/lod-server traces (LabLodTrace).

Each side's times are its own JVM's monotonic clock. Client/server stages align through the
recorded wall-clock origins, which on one machine is good to about a millisecond; treat
cross-side latencies as approximate. Stage pairs match the same packed DH section position and
take the first later event, so a repeated request is measured from each of its starts.
"""

import bisect
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

T, THREAD, OP, POS, DETAIL, X, Z, A, B, C, D = range(11)
SECTION_MIN_DETAIL = 6

CLIENT_STAGES = [
    ("network queue wait", "net-queued", "net-sent"),
    ("request round trip", "net-sent", "net-response"),
    ("retrieval total", "retrieval-queued", "retrieval-done"),
    ("build queue wait", "build-queued", "build-start"),
    ("build (mesh)", "build-start", "build-end"),
    ("GPU upload", "upload-start", "upload-done"),
    ("reload to rebuild queued", "reload-queued", "build-queued"),
]
SERVER_STAGES = [
    ("request to generation queued", "srv-request", "gen-submitted"),
    ("generation queue wait", "gen-submitted", "gen-started"),
    ("preview executor wait", "preview-queued", "preview-start"),
    ("generation total", "gen-started", "gen-finished"),
    ("request to payload encoded", "srv-request", "srv-payload"),
]


def load_side(directory, side):
    path = directory / f"lod-{side}.csv"
    if not path.is_file():
        return None
    meta = directory / f"lod-{side}.json"
    summary = json.loads(meta.read_text(encoding="utf-8")) if meta.is_file() else {}
    rows, truncated = [], 0
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.reader(stream)
        if next(reader, None) != [
            "nanos",
            "thread",
            "op",
            "pos",
            "detail",
            "x",
            "z",
            "a",
            "b",
            "c",
            "d",
        ]:
            raise ValueError("Unexpected LOD trace header: " + str(path))
        for row in reader:
            # A process killed mid-write can leave a partial last line.
            if len(row) != 11:
                truncated += 1
                continue
            try:
                values = [int(v) if i != OP else v for i, v in enumerate(row)]
            except ValueError:
                truncated += 1
                continue
            rows.append(tuple(values))
    rows.sort(key=lambda r: r[T])
    return {"side": side, "rows": rows, "summary": summary, "truncated": truncated}


def window(trace, mode):
    rows = trace["rows"]
    starts = [r[T] for r in rows if r[OP] == "lab-start"]
    finishes = [r[T] for r in rows if r[OP] == "lab-finish"]
    if mode == "all" or (mode == "auto" and not (starts and finishes)):
        return rows, None
    if not (starts and finishes):
        raise ValueError(trace["side"] + " trace has no lab-start/lab-finish marks")
    start, end = starts[0], finishes[-1]
    return [r for r in rows if start <= r[T] <= end], (start, end)


def index(rows):
    """op -> pos -> sorted times."""
    out: defaultdict[str, defaultdict[int, list[int]]] = defaultdict(lambda: defaultdict(list))
    for r in rows:
        out[r[OP]][r[POS]].append(r[T])
    return out


def pair_durations(by_op, first, second):
    """(detail, nanoseconds) from each first-op event to the next second-op event at its position.
    DH re-queues positions already waiting; a repeat before that wait ends is not a new wait."""
    later = by_op.get(second, {})
    out = []
    for pos, times in by_op.get(first, {}).items():
        targets = later.get(pos)
        if not targets:
            continue
        pending_until = -1
        for t in times:
            if t <= pending_until:
                continue
            i = bisect.bisect_left(targets, t)
            if i < len(targets):
                out.append((pos & 0x7F, targets[i] - t))
                pending_until = targets[i]
    return out


def stats(nanos):
    if not nanos:
        return {"count": 0}
    values = sorted(n / 1e6 for n in nanos)

    def q(p):
        return round(values[min(len(values) - 1, int(p * (len(values) - 1) + 0.5))], 3)

    return {
        "count": len(values),
        "p50Ms": q(0.5),
        "p90Ms": q(0.9),
        "p99Ms": q(0.99),
        "maxMs": round(values[-1], 3),
        "meanMs": round(sum(values) / len(values), 3),
    }


def by_detail(pairs):
    groups = defaultdict(list)
    for detail, nanos in pairs:
        groups[detail].append(nanos)
    return {str(d): stats(v) for d, v in sorted(groups.items())}


def stage_table(by_op, stages):
    out = {}
    for name, first, second in stages:
        pairs = pair_durations(by_op, first, second)
        out[name] = {
            "from": first,
            "to": second,
            **stats([n for _, n in pairs]),
            "byDetail": by_detail(pairs),
        }
    return out


def children(pos):
    detail = pos & 0x7F
    x, z = _signed((pos >> 8) & 0x0FFFFFFF), _signed((pos >> 36) & 0x0FFFFFFF)
    return [_encode(detail - 1, 2 * x + i, 2 * z + j) for i in (0, 1) for j in (0, 1)]


def _signed(value):
    return value - (1 << 28) if value & (1 << 27) else value


def _encode(detail, x, z):
    return (detail & 0x7F) | ((x & 0x0FFFFFFF) << 8) | ((z & 0x0FFFFFFF) << 36)


def section_lifecycles(by_op, rows):
    """Per client section: creation, first geometry upload, first rendered state, empty uploads."""

    def first(op, pos):
        return (by_op.get(op, {}).get(pos) or [None])[0]

    geometry: dict[int, int] = {}
    rendered: dict[int, int] = {}
    empties: Counter[int] = Counter()
    for r in rows:
        if r[OP] == "upload-done":
            if r[A] == 1:
                geometry.setdefault(r[POS], r[T])
            elif r[A] == 2 and r[POS] not in geometry:
                empties[r[POS]] += 1
        elif r[OP] == "render-state" and r[A] & 3 == 3:
            rendered.setdefault(r[POS], r[T])
    out = {}
    for pos, times in by_op.get("section-new", {}).items():
        created = times[0]
        out[pos] = {
            "created": created,
            "geometry": geometry.get(pos),
            "rendered": rendered.get(pos),
            "emptyUploads": empties.get(pos, 0),
            "buildQueued": first("build-queued", pos),
        }
    return out


def promotions(sections):
    """A parent is promoted once all four children have rendered; the delay runs from the last
    child's creation. The blocking child's slowest phase is named."""
    delays: list[tuple[int, int]] = []
    reasons: Counter[str] = Counter()
    held: Counter[int] = Counter()
    parents = {}
    for pos in sections:
        if pos & 0x7F > SECTION_MIN_DETAIL:
            kids = children(pos)
            if any(k in sections for k in kids):
                parents[pos] = kids
    for parent, kids in parents.items():
        info = [sections.get(k) for k in kids]
        if any(i is None for i in info):
            held[parent & 0x7F] += 1
            continue
        if any(i["rendered"] is None for i in info):
            held[parent & 0x7F] += 1
            continue
        created = max(i["created"] for i in info)
        promoted = max(i["rendered"] for i in info)
        if promoted < created:
            continue
        delays.append((parent & 0x7F, promoted - created))
        slow = max(info, key=lambda i: i["rendered"])
        if slow["geometry"] is None:
            reasons["rendered without geometry"] += 1
        else:
            phases = {
                "awaiting data and build": slow["geometry"] - slow["created"],
                "selection after upload": slow["rendered"] - slow["geometry"],
            }
            reasons[max(phases, key=lambda k: phases[k])] += 1
    return {
        "promotedParents": len(delays),
        "delay": {**stats([n for _, n in delays]), "byParentDetail": by_detail(delays)},
        "blockingPhase": dict(reasons),
        "heldParentsByDetail": {str(k): v for k, v in sorted(held.items())},
        "note": "Delay is from the last child's creation to all four children rendering; a held parent "
        "had a child missing or never rendered inside the window.",
    }


def timeline(side_rows, origin, bin_seconds=1.0):
    """Per-bin counts for throughput and the latest queue depths."""
    width = int(bin_seconds * 1e9)
    bins: defaultdict[int, Counter[str]] = defaultdict(Counter)
    depths = {}
    track = {}
    for side, rows in side_rows.items():
        for r in rows:
            b = (r[T] + origin[side] - origin["base"]) // width
            op = r[OP]
            if op == "preview-end":
                bins[b]["tilesGenerated"] += 1
            elif op == "srv-payload":
                bins[b]["payloadBytes"] += max(0, r[A])
            elif op == "net-response" and r[A] == 0:
                bins[b]["dataReceived"] += 1
            elif op == "upload-done" and r[A] == 1:
                bins[b]["geometryUploads"] += 1
            elif op == "upload-done" and r[A] == 2:
                bins[b]["emptyUploads"] += 1
            elif op == "tree":
                depths[b] = {
                    "enabled": r[A],
                    "missing": r[B],
                    "queued": r[C],
                    "waitingToLoad": r[D],
                }
            elif op == "player":
                track[b] = (r[A], r[B], r[C])
    out = []
    previous = None
    occupied = bins.keys() | depths.keys() | track.keys()
    for b in range(min(occupied, default=0), max(occupied, default=-1) + 1):
        row = {"second": round(b * bin_seconds, 1), **bins.get(b, {})}
        if b in depths:
            row.update(depths[b])
        if b in track:
            row["player"] = list(track[b])
            if previous is not None:
                (pb, p) = previous
                row["speedBlocksPerSecond"] = round(
                    sum((u - v) ** 2 for u, v in zip(track[b], p)) ** 0.5
                    / ((b - pb) * bin_seconds),
                    1,
                )
            previous = (b, track[b])
        out.append(row)
    return out


def cross_side(client, server, offset):
    """Client send to server receive, and server response sent to client receive, by position."""
    out = {}
    sent = client.get("net-sent", {})
    received = server.get("srv-request", {})
    pairs = []
    for pos, times in sent.items():
        targets = sorted(t + offset for t in received.get(pos, []))
        for t in times:
            i = bisect.bisect_left(targets, t)
            if i < len(targets):
                pairs.append((pos & 0x7F, targets[i] - t))
    out["client send to server receive"] = {
        **stats([n for _, n in pairs]),
        "byDetail": by_detail(pairs),
    }
    return out


def response_pairs(server_rows, client_by_op, offset):
    payload_pos = {}
    encoded = []
    responded = []
    for r in server_rows:
        if r[OP] == "srv-payload":
            payload_pos[r[B]] = r[POS]
            encoded.append(r)
        elif r[OP] == "srv-response":
            responded.append(r)
    encoded_at = {r[B]: r[T] for r in encoded}
    wait, transfer = [], []
    received = client_by_op.get("net-response", {})
    for r in responded:
        pos = payload_pos.get(r[B])
        if pos is None:
            continue
        if r[B] in encoded_at:
            wait.append((pos & 0x7F, r[T] - encoded_at[r[B]]))
        targets = received.get(pos, [])
        t = r[T] + offset
        i = bisect.bisect_left(targets, t)
        if i < len(targets):
            transfer.append((pos & 0x7F, targets[i] - t))
    return {
        "payload encoded to response sent": {
            **stats([n for _, n in wait]),
            "byDetail": by_detail(wait),
        },
        "server response to client receive": {
            **stats([n for _, n in transfer]),
            "byDetail": by_detail(transfer),
        },
    }


POOL_NAMES = [
    "World Gen",
    "LOD Builder",
    "IO",
    "Network Compression",
    "Update Propagator",
    "Render Loader",
]


def pool_summary(rows):
    """DH's shared executors from the 4 Hz pool rows: queue, running threads and paused share."""
    samples = defaultdict(list)
    for r in rows:
        if r[OP] == "pool":
            samples[r[POS]].append(r)
    out = {}
    for i, pool in sorted(samples.items()):
        n = len(pool)
        out[POOL_NAMES[i] if i < len(POOL_NAMES) else str(i)] = {
            "samples": n,
            "meanQueued": round(sum(r[A] for r in pool) / n, 1),
            "maxQueued": max(r[A] for r in pool),
            "meanRunning": round(sum(r[B] for r in pool) / n, 2),
            "pausedShare": round(sum(1 for r in pool if r[C] == 0) / n, 3),
        }
    return out


def server_ticks(rows):
    """Tick event spans, and the share of DH health checks that would pause generation."""
    ticks = [r[A] for r in rows if r[OP] == "server-tick"]
    health = [r for r in rows if r[OP] == "dh-health"]
    out = {"ticks": stats(ticks)}
    if health:
        out["dhHealth"] = {
            "samples": len(health),
            "unhealthyShare": round(sum(1 for r in health if r[C] == 0) / len(health), 3),
            "meanAverageMs": round(sum(r[A] for r in health) / len(health) / 1000, 2),
            "meanP99Ms": round(sum(r[B] for r in health) / len(health) / 1000, 2),
        }
    return out


def slot_breakdown(by_op, rows):
    """Where a generation slot's time goes, per tile: waiting for a DH thread, sampling, handing
    the result back; DH's update after the slot ends runs on the same thread."""
    parts = {
        "waiting for a thread": pair_durations(by_op, "gen-started", "preview-start"),
        "sampling": pair_durations(by_op, "preview-start", "preview-end"),
        "handoff to DH": pair_durations(by_op, "preview-end", "gen-finished"),
        "whole slot": pair_durations(by_op, "gen-started", "gen-finished"),
        "DH update after the slot": [
            (r[POS] & 0x7F, r[A]) for r in rows if r[OP] == "retrieval-end"
        ],
    }
    out = {}
    for name, pairs in parts.items():
        values = [n for _, n in pairs]
        out[name] = {
            **stats(values),
            "totalSeconds": round(sum(values) / 1e9, 2),
            "byDetail": by_detail(pairs),
        }
    return out


def lod_report(directory, output, mode="auto", charts=True):
    directory = Path(directory)
    if not directory.is_dir():
        raise ValueError("No LOD trace directory: " + str(directory))
    sides = {s: load_side(directory, s) for s in ("client", "server")}
    if not any(sides.values()):
        raise ValueError("No lod-client.csv or lod-server.csv in " + str(directory))
    warnings = []
    for side, trace in sides.items():
        if trace is None:
            warnings.append(side + ": no trace")
            continue
        summary = trace["summary"]
        if summary.get("dropped"):
            warnings.append(f"{side}: {summary['dropped']} events dropped (queue full or capped)")
        if summary.get("capped"):
            warnings.append(f"{side}: event cap reached")
        if summary and not summary.get("closed"):
            warnings.append(
                f"{side}: process did not close the trace; its last second may be missing"
            )
        if trace["truncated"]:
            warnings.append(f"{side}: {trace['truncated']} partial lines skipped")
        if summary.get("failure"):
            warnings.append(f"{side}: writer failure {summary['failure']}")
    report = {"trace": str(directory.resolve()), "window": mode, "sides": {}}
    windowed, indexes, epochs = {}, {}, {}
    for side, trace in sides.items():
        if trace is None:
            continue
        rows, bounds = window(trace, mode)
        windowed[side] = rows
        indexes[side] = index(rows)
        epochs[side] = trace["summary"].get("originEpochMicros")
        span = (rows[-1][T] - rows[0][T]) / 1e9 if rows else 0
        report["sides"][side] = {
            "events": len(rows),
            "seconds": round(span, 1),
            "labWindow": [round(b / 1e9, 3) for b in bounds] if bounds else None,
            "operations": dict(Counter(r[OP] for r in rows)),
        }
    if "client" in indexes:
        by_op = indexes["client"]
        sections = section_lifecycles(by_op, windowed["client"])
        report["client"] = {
            "stages": stage_table(by_op, CLIENT_STAGES),
            "sections": {
                "created": len(sections),
                "withGeometry": sum(1 for s in sections.values() if s["geometry"] is not None),
                "rendered": sum(1 for s in sections.values() if s["rendered"] is not None),
                "uploadedEmptyFirst": sum(1 for s in sections.values() if s["emptyUploads"]),
                "createdToFirstGeometry": {
                    **stats(
                        [
                            s["geometry"] - s["created"]
                            for s in sections.values()
                            if s["geometry"] is not None
                        ]
                    ),
                    "byDetail": by_detail(
                        [
                            (p & 0x7F, s["geometry"] - s["created"])
                            for p, s in sections.items()
                            if s["geometry"] is not None
                        ]
                    ),
                },
                "createdToFirstRender": {
                    **stats(
                        [
                            s["rendered"] - s["created"]
                            for s in sections.values()
                            if s["rendered"] is not None
                        ]
                    ),
                    "byDetail": by_detail(
                        [
                            (p & 0x7F, s["rendered"] - s["created"])
                            for p, s in sections.items()
                            if s["rendered"] is not None
                        ]
                    ),
                },
            },
            "promotions": promotions(sections),
        }
    if "server" in indexes:
        by_op = indexes["server"]
        sampling = [(r[POS] & 0x7F, r[A]) for r in windowed["server"] if r[OP] == "preview-end"]
        report["server"] = {
            "stages": {
                **stage_table(by_op, SERVER_STAGES),
                "preview sampling": {
                    "from": "preview-start",
                    "to": "preview-end",
                    **stats([n for _, n in sampling]),
                    "byDetail": by_detail(sampling),
                },
            },
            "tilesGenerated": len(sampling),
            "payloadBytes": sum(max(0, r[A]) for r in windowed["server"] if r[OP] == "srv-payload"),
            "slot": slot_breakdown(by_op, windowed["server"]),
            "pools": pool_summary(windowed["server"]),
            **server_ticks(windowed["server"]),
        }
    if "client" in report:
        report["client"]["pools"] = pool_summary(windowed["client"])
    origin = {"base": 0}
    if "client" in epochs and "server" in epochs and epochs["client"] and epochs["server"]:
        offset = (epochs["server"] - epochs["client"]) * 1000
        origin.update(client=0, server=offset)
        report["crossSide"] = {
            **cross_side(indexes["client"], indexes["server"], offset),
            **response_pairs(windowed["server"], indexes["client"], offset),
            "note": "Wall-clock aligned (same machine); approximate to about a millisecond.",
        }
    else:
        origin.update({side: 0 for side in windowed})
        if len(windowed) == 2:
            warnings.append("No wall-clock origins; cross-side stages skipped")
    report["timeline"] = timeline(windowed, origin)
    report["warnings"] = warnings
    output = Path(output)
    output.mkdir(parents=True)
    (output / "lod-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    text = render_text(report)
    (output / "lod-report.txt").write_text(text, encoding="utf-8")
    if charts:
        try:
            lod_charts(report, output)
        except ImportError:
            warnings.append(
                "matplotlib unavailable; charts skipped (use .local/observability-venv)"
            )
    return {"text": text, "warnings": warnings, "report": report}


def _row(name, s):
    if not s.get("count"):
        return f"  {name:<34} {'-':>7}"
    return f"  {name:<34} {s['count']:>7} {s['p50Ms']:>9} {s['p90Ms']:>9} {s['p99Ms']:>9} {s['maxMs']:>9}"


def _pool_lines(title, pools):
    if not pools:
        return []
    lines = [
        "",
        f"{title} (4 Hz samples)",
        f"  {'executor':<20} {'queued mean':>12} {'queued max':>11} {'running mean':>13} {'paused':>8}",
    ]
    for name, p in pools.items():
        lines.append(
            f"  {name:<20} {p['meanQueued']:>12} {p['maxQueued']:>11} {p['meanRunning']:>13} {p['pausedShare']:>8.0%}"
        )
    return lines


def render_text(report):
    head = f"  {'stage':<34} {'count':>7} {'p50 ms':>9} {'p90 ms':>9} {'p99 ms':>9} {'max ms':>9}"
    lines = [f"DH LOD trace: {report['trace']} (window {report['window']})"]
    for side, info in report["sides"].items():
        lines.append(
            f"{side}: {info['events']} events over {info['seconds']} s"
            + (f", lab window {info['labWindow']}" if info["labWindow"] else "")
        )
    if "server" in report:
        s = report["server"]
        lines += [
            "",
            f"Server: {s['tilesGenerated']} preview tiles, {s['payloadBytes'] / 1e6:.1f} MB payloads",
            head,
        ]
        lines += [_row(name, v) for name, v in s["stages"].items()]
        if s["slot"]["whole slot"].get("count"):
            lines += [
                "",
                "Server generation slot, per tile (DH's update runs after the slot)",
                head,
            ]
            lines += [
                _row(f"{name} ({v['totalSeconds']} s total)", v) for name, v in s["slot"].items()
            ]
        lines += _pool_lines("Server DH executors", s["pools"])
        if s["ticks"].get("count"):
            t = s["ticks"]
            lines.append(
                f"Server ticks: {t['count']}, p50 {t['p50Ms']} ms, p90 {t['p90Ms']} ms, max {t['maxMs']} ms"
            )
        if "dhHealth" in s:
            h = s["dhHealth"]
            lines.append(
                f"DH health checks: {h['unhealthyShare']:.0%} unhealthy (generation paused) of "
                f"{h['samples']}; mean average {h['meanAverageMs']} ms, mean p99 {h['meanP99Ms']} ms"
            )
    if "crossSide" in report:
        lines += ["", "Across client and server (wall-clock aligned)", head]
        lines += [_row(name, v) for name, v in report["crossSide"].items() if isinstance(v, dict)]
    if "client" in report:
        c = report["client"]
        sec = c["sections"]
        lines += [
            "",
            f"Client sections: {sec['created']} created, {sec['withGeometry']} with geometry, {sec['rendered']} rendered, "
            f"{sec['uploadedEmptyFirst']} uploaded empty before data",
            head,
        ]
        lines += [_row(name, v) for name, v in c["stages"].items()]
        lines += [
            _row("created to first geometry", sec["createdToFirstGeometry"]),
            _row("created to first render", sec["createdToFirstRender"]),
        ]
        p = c["promotions"]
        lines += [
            "",
            f"Promotions: {p['promotedParents']} parents promoted; held by parent detail {p['heldParentsByDetail']}",
            head,
        ]
        lines.append(_row("last child created to promoted", p["delay"]))
        for detail, v in p["delay"]["byParentDetail"].items():
            lines.append(_row(f"  parent detail {detail}", v))
        lines.append(f"  blocking phase of the last child: {p['blockingPhase']}")
        lines += _pool_lines("Client DH executors", c.get("pools", {}))
        lines += ["", "Detail level p50/p90 ms (created to first geometry):"]
        for detail, v in sec["createdToFirstGeometry"]["byDetail"].items():
            lines.append(
                f"  detail {detail} ({2 ** (int(detail) - SECTION_MIN_DETAIL)}-block cells): {v['count']} sections, {v['p50Ms']} / {v['p90Ms']}"
            )
    if report["warnings"]:
        lines += ["", "Warnings:"] + ["  " + w for w in report["warnings"]]
    return "\n".join(lines) + "\n"


def lod_charts(report, output):
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    rows = report["timeline"]
    if rows:
        seconds = [r["second"] for r in rows]
        fig, axes = plt.subplots(3, 1, figsize=(11, 9), sharex=True)
        for key, label in [
            ("tilesGenerated", "server tiles generated"),
            ("dataReceived", "client data received"),
            ("geometryUploads", "geometry uploads"),
            ("emptyUploads", "empty uploads"),
        ]:
            axes[0].plot(seconds, [r.get(key, 0) for r in rows], label=label, linewidth=1)
        axes[0].set_ylabel("per second")
        axes[0].legend(frameon=False, fontsize=8)
        for key in ["enabled", "missing", "queued", "waitingToLoad"]:
            points = [(r["second"], r[key]) for r in rows if key in r]
            if points:
                axes[1].plot(*zip(*points), label=key, linewidth=1)
        axes[1].set_ylabel("client quad tree")
        axes[1].legend(frameon=False, fontsize=8)
        speed = [
            (r["second"], r["speedBlocksPerSecond"]) for r in rows if "speedBlocksPerSecond" in r
        ]
        if speed:
            axes[2].plot(*zip(*speed), linewidth=1)
        axes[2].set_ylabel("player blocks/s")
        axes[2].set_xlabel("seconds from trace start")
        for axis in axes:
            axis.grid(alpha=0.2)
        fig.suptitle("DH LOD throughput, queues and player speed")
        fig.tight_layout()
        fig.savefig(output / "lod-timeline.png", dpi=110)
        plt.close(fig)
    tables = [
        ("server", report.get("server", {}).get("stages", {})),
        ("between", report.get("crossSide", {})),
        ("client", report.get("client", {}).get("stages", {})),
    ]
    stages = [
        (f"{side}: {name}", v["p50Ms"], v["p90Ms"])
        for side, table in tables
        for name, v in table.items()
        if isinstance(v, dict) and v.get("count")
    ]
    if stages:
        fig, axis = plt.subplots(figsize=(10, 0.35 * len(stages) + 1.5))
        names = [s[0] for s in stages]
        axis.barh(names, [s[2] for s in stages], color="#c9d6ea", label="p90")
        axis.barh(names, [s[1] for s in stages], color="#3b6cb7", label="p50")
        axis.set_xscale("log")
        axis.set_xlabel("milliseconds (log)")
        axis.invert_yaxis()
        axis.legend(frameon=False)
        axis.grid(axis="x", alpha=0.2)
        fig.tight_layout()
        fig.savefig(output / "lod-stages.png", dpi=110)
        plt.close(fig)
