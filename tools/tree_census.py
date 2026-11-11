"""Report a tree census written by tools/checks/TreeCensus.java.

  python tools/tree_census.py .local/trees/census-v134

Reads windows.csv, area.csv and sites.csv and writes report.md beside them, per biome or
biome/variant key: dry-land area, stems per hectare by layer, height spread (mean, 10th, 50th and
90th percentiles, coefficient of variation, distinct heights), species shares, crown spread and leaf
volume, and two spatial measures of the canopy and emergent layers: the Clark-Evans ratio R of
nearest-neighbour distances (below 1 clumped, about 1 random, above 1 regular) and the index of
dispersion of stem counts in 32-block quadrats (variance over mean: about 1 random, above 1
patchy). Per species it reports the heights drawn against the definition and how crown spread and
leaves follow height (Pearson r).
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
LAYERS = list(
    json.loads((ROOT / "src/main/resources/riven-trees.json").read_text(encoding="utf-8"))["layers"]
)
QUADRAT = 32


def percentile(values: list[float], q: float) -> float:
    s = sorted(values)
    if not s:
        return float("nan")
    k = (len(s) - 1) * q
    lo, hi = math.floor(k), math.ceil(k)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def pearson(xs: list[float], ys: list[float]) -> float:
    if len(xs) < 3 or len(set(xs)) < 2 or len(set(ys)) < 2:
        return float("nan")
    return statistics.correlation(xs, ys)


def clark_evans(points: list[tuple[int, int]], area_blocks: float) -> float:
    """R = mean nearest-neighbour distance over its expectation for random points."""
    if len(points) < 5 or area_blocks <= 0:
        return float("nan")
    cell: dict[tuple[int, int], list[tuple[int, int]]] = defaultdict(list)
    for p in points:
        cell[(p[0] // 16, p[1] // 16)].append(p)
    total = 0.0
    for x, z in points:
        best = math.inf
        cx, cz = x // 16, z // 16
        for r in range(1, 6):
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    for ox, oz in cell.get((cx + dx, cz + dz), ()):
                        if (ox, oz) != (x, z):
                            best = min(best, math.hypot(ox - x, oz - z))
            if best <= r * 16:
                break
        total += best
    expected = 0.5 / math.sqrt(len(points) / area_blocks)
    return (total / len(points)) / expected


def dispersion(points: list[tuple[int, int]], window: dict) -> float:
    """Variance over mean of stem counts in the window's 32-block quadrats."""
    size, x0, z0 = (
        window["size"],
        window["cx"] - window["size"] // 2,
        window["cz"] - window["size"] // 2,
    )
    n = size // QUADRAT
    counts = [0] * (n * n)
    for x, z in points:
        i, j = (x - x0) // QUADRAT, (z - z0) // QUADRAT
        if 0 <= i < n and 0 <= j < n:
            counts[j * n + i] += 1
    mean = sum(counts) / len(counts)
    return statistics.pvariance(counts) / mean if mean > 0 else float("nan")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("census", type=Path)
    args = parser.parse_args()
    d = args.census
    windows: dict[int, dict[str, Any]] = {
        int(r["window"]): {
            "cx": int(r["cx"]),
            "cz": int(r["cz"]),
            "size": int(r["size"]),
            "key": r["key"],
        }
        for r in csv.DictReader((d / "windows.csv").open(encoding="utf-8"))
    }
    area: dict[str, float] = defaultdict(float)
    window_area: dict[tuple[int, str], float] = {}
    for r in csv.DictReader((d / "area.csv").open(encoding="utf-8")):
        blocks = int(r["columns"]) * int(r["lattice"]) ** 2
        area[r["key"]] += blocks
        window_area[(int(r["window"]), r["key"])] = blocks
    sites: list[dict[str, Any]] = list(csv.DictReader((d / "sites.csv").open(encoding="utf-8")))
    for s in sites:
        for k in (
            "window",
            "x",
            "z",
            "layer",
            "width",
            "trunk",
            "height",
            "spreadX",
            "spreadZ",
            "leaves",
            "logs",
            "roots",
        ):
            s[k] = int(s[k])

    by_key: dict[str, list[dict]] = defaultdict(list)
    for s in sites:
        by_key[s["key"]].append(s)

    lines = [
        f"# Tree census: {d.name}",
        "",
        f"{len(windows)} windows, {len(sites)} trees. Heights are whole trees (roots to crown top);",
        "R is Clark-Evans on canopy and emergent stems (below 1 clumped, 1 random, above 1 regular);",
        f"ID is the index of dispersion in {QUADRAT}-block quadrats (1 random, above 1 patchy).",
        "",
        "## Biomes and variants",
        "",
        "| key | land ha | stems/ha | "
        + " | ".join(f"{name}/ha" for name in LAYERS)
        + " | height mean | p10 | p50 | p90 | CV | distinct | spread | leaves | R | ID | species |",
        "|" + "---|" * (3 + len(LAYERS) + 11),
    ]
    for key in sorted(area, key=lambda k: -area[k]):
        ha = area[key] / 1e4
        trees = by_key.get(key, [])
        if ha < 0.5:
            continue
        per_layer = [sum(1 for t in trees if t["layer"] == i) / ha for i in range(len(LAYERS))]
        heights = [t["height"] for t in trees]
        upper = [t for t in trees if LAYERS[t["layer"]] != "understory"]
        rs, ids = [], []
        for w, info in windows.items():
            pts = [(t["x"], t["z"]) for t in upper if t["window"] == w]
            a = window_area.get((w, key), 0)
            if len(pts) >= 10 and a > 0.5 * info["size"] ** 2:
                rs.append(clark_evans(pts, a))
                ids.append(dispersion(pts, info))
        species: dict[str, int] = defaultdict(int)
        for t in trees:
            species[t["species"]] += 1
        top = ", ".join(
            f"{name} {100 * n / len(trees):.0f}%"
            for name, n in sorted(species.items(), key=lambda e: -e[1])[:4]
        )
        if heights:
            mean = statistics.fmean(heights)
            cv = statistics.pstdev(heights) / mean
            cells = [
                f"{mean:.1f}",
                f"{percentile(heights, 0.1):.0f}",
                f"{percentile(heights, 0.5):.0f}",
                f"{percentile(heights, 0.9):.0f}",
                f"{cv:.2f}",
                str(len(set(heights))),
                f"{statistics.fmean(max(t['spreadX'], t['spreadZ']) for t in trees):.1f}",
                f"{statistics.fmean(t['leaves'] for t in trees):.0f}",
            ]
        else:
            cells = ["-"] * 8
        ratio = f"{statistics.fmean(rs):.2f}" if rs else "-"
        dispersed = f"{statistics.fmean(ids):.2f}" if ids else "-"
        lines.append(
            f"| {key} | {ha:.1f} | {len(trees) / ha:.0f} | "
            + " | ".join(f"{v:.0f}" for v in per_layer)
            + " | "
            + " | ".join(cells)
            + f" | {ratio} | {dispersed} | {top or '-'} |"
        )

    defined = json.loads(
        (ROOT / "src/main/resources/riven-trees.json").read_text(encoding="utf-8")
    )["species"]
    lines += [
        "",
        "## Species",
        "",
        "| species | family | trees | defined trunk | drawn height p10-p90 | CV | spread mean | r(spread, height) | leaves mean | r(leaves, height) | biomes |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    by_species: dict[str, list[dict]] = defaultdict(list)
    for s in sites:
        by_species[s["species"]].append(s)
    for name in sorted(by_species, key=lambda n: -len(by_species[n])):
        trees = by_species[name]
        h = [t["height"] for t in trees]
        spread = [max(t["spreadX"], t["spreadZ"]) for t in trees]
        leaves = [t["leaves"] for t in trees]
        keys: dict[str, int] = defaultdict(int)
        for t in trees:
            keys[t["key"]] += 1
        where = ", ".join(k for k, _ in sorted(keys.items(), key=lambda e: -e[1])[:4])
        lines.append(
            f"| {name} | {trees[0]['family']} | {len(trees)} | {defined[name]['heightBlocks']} | "
            f"{percentile(h, 0.1):.0f}-{percentile(h, 0.9):.0f} | {statistics.pstdev(h) / statistics.fmean(h):.2f} | "
            f"{statistics.fmean(spread):.1f} | {pearson(h, spread):.2f} | {statistics.fmean(leaves):.0f} | "
            f"{pearson(h, leaves):.2f} | {where} |"
        )
    out = d / "report.md"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
