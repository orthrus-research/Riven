"""Find windows where a stage, biome or variant is active, and windows that exercise every stage.

One coarse raster per seed covers the search area (`--extent` blocks around `--center`); windows of
`--size` blocks slide over it at half-window strides and are scored from integral images, so the
search costs one sampler run per seed. A stage's score is the share of a window's land it changes
by at least the threshold (the render's stage activity); a biome's or variant's is its share of
the window. `--coverage` picks a few windows that together exercise every stage and writes them
as a proposed golden windows list, beside the hunt's output and never into tools/.
"""

import argparse
import csv
import html
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

DEFAULT_SEEDS = [8675309, 101, -991, 7, 42, 1234]
THUMB = 192


@dataclass(frozen=True)
class Window:
    seed: int
    row: int  # top-left pixel in the seed's search raster
    col: int
    cx: int  # centre in blocks
    cz: int
    land: float  # % of the window that is land
    score: float


def window_sums(mask: np.ndarray, side: int, stride: int) -> np.ndarray:
    """Sum of mask over every side x side window at stride, from an integral image."""
    table = np.zeros((mask.shape[0] + 1, mask.shape[1] + 1), np.int64)
    table[1:, 1:] = mask.astype(np.int64).cumsum(0).cumsum(1)
    starts_r = np.arange(0, mask.shape[0] - side + 1, stride)
    starts_c = np.arange(0, mask.shape[1] - side + 1, stride)
    r0, c0 = np.meshgrid(starts_r, starts_c, indexing="ij")
    return table[r0 + side, c0 + side] - table[r0, c0 + side] - table[r0 + side, c0] + table[r0, c0]


def grid(shape: tuple[int, int], side: int, stride: int) -> tuple[np.ndarray, np.ndarray]:
    starts_r = np.arange(0, shape[0] - side + 1, stride)
    starts_c = np.arange(0, shape[1] - side + 1, stride)
    return np.meshgrid(starts_r, starts_c, indexing="ij")


def shares(target: np.ndarray, land: np.ndarray | None, side: int, stride: int) -> np.ndarray:
    """% of each window's land (or of the whole window, land=None) where target holds."""
    hits = window_sums(target if land is None else target & land, side, stride)
    base = window_sums(land, side, stride) if land is not None else np.full(hits.shape, side * side)
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where(base > 0, 100.0 * hits / np.maximum(base, 1), 0.0)


def spaced(windows: list[Window], side: int, count: int) -> list[Window]:
    """The best windows, best first, skipping any that overlaps a better one of the same seed by
    more than half its side."""
    picked: list[Window] = []
    for w in sorted(windows, key=lambda w: (-w.score, w.seed, w.row, w.col)):
        if all(
            w.seed != p.seed or max(abs(w.row - p.row), abs(w.col - p.col)) > side // 2
            for p in picked
        ):
            picked.append(w)
            if len(picked) == count:
                break
    return picked


def cover(table: dict[tuple, dict[str, float]], stages: list[str], minimum: float):
    """Greedy set cover: windows (keys of table, each with its per-stage shares) that together
    exercise every stage at `minimum` % of their land or more. A stage active somewhere but never
    that much takes its best window (a picked one if any picked window shows it). Returns (picked
    keys, {stage: (key, share)}, stages below the minimum, stages active nowhere)."""
    best_share = {s: max((v.get(s, 0) for v in table.values()), default=0) for s in stages}
    left = [s for s in stages if best_share[s] >= minimum]
    weak = [s for s in stages if 0 < best_share[s] < minimum]
    uncovered = [s for s in stages if best_share[s] <= 0]
    picked: list[tuple] = []
    covered: dict[str, tuple[tuple, float]] = {}
    while left:

        def gain(key):
            hits = [s for s in left if table[key].get(s, 0) >= minimum]
            return (len(hits), sum(table[key][s] for s in hits))

        best = max(sorted(table), key=gain)
        hits = [s for s in left if table[best].get(s, 0) >= minimum]
        picked.append(best)
        for s in hits:
            covered[s] = (best, table[best][s])
        left = [s for s in left if s not in hits]
    for s in weak:
        if s in covered:
            continue
        shown = [k for k in picked if table[k].get(s, 0) > 0]
        best = max(shown or sorted(table), key=lambda k: table[k].get(s, 0))
        if best not in picked:
            picked.append(best)
        for t in weak:  # a window added for one weak stage may show others
            if t not in covered and table[best].get(t, 0) > 0:
                covered[t] = (best, table[best][t])
    return picked, covered, weak, uncovered


def search_args(args, extent):
    """The window options for the whole search area instead of one window."""
    return argparse.Namespace(**(vars(args) | {"size": extent}))


def sample_seed(args, seed):
    """Sample and paint one seed's search raster; returns (render dir, Atlas)."""
    import terrain_atlas as cli

    from atlas.build import Source
    from atlas.paint import paint

    source = Source(args.ref, tuple(args.set))
    out, atlas, _ = cli.sample(search_args(args, args.extent), source, seed)
    # Painting is cheap at search steps, and a stale summary would mislead the thumbnails.
    paint(atlas, out, args.threshold)
    return out, atlas


def target_mask(atlas, args) -> tuple[np.ndarray, bool]:
    """(mask, measured over land?) for the requested target."""
    if args.stage:
        if args.stage not in atlas.stages:
            raise SystemExit(f"hunt: no stage {args.stage!r}; stages: {', '.join(atlas.stages)}")
        deltas, _ = atlas.deltas()
        return np.abs(deltas[atlas.stages.index(args.stage)]) >= args.threshold, True
    kind, name = ("habitats", args.biome) if args.biome else ("variants", args.variant)
    names = [n.lower() for n in atlas.meta.get(kind, [])]
    if name.lower() not in names or (kind == "variants" and name == ""):
        raise SystemExit(
            f"hunt: no {kind[:-1]} {name!r}; known: {', '.join(n for n in names if n)}"
        )
    layer = atlas.layer("habitat.u8" if kind == "habitats" else "variant.u8", np.uint8)
    return layer == names.index(name.lower()), False


def highlight(render: Path, mask: np.ndarray, path: Path):
    """The target in magenta over the render's elevation, dimmed."""
    base = np.asarray(Image.open(render / "elevation.png").convert("RGB"), np.float32) * 0.55
    base[mask] = (255, 40, 220)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(base.astype(np.uint8)).save(path, optimize=True)


def windows_of(atlas, seed, side, stride, score) -> list[Window]:
    meta = atlas.meta
    land = (atlas.layer("flags.u8", np.uint8) & 1) == 0
    land_pct = shares(land, None, side, stride)
    r0, c0 = grid(atlas.shape, side, stride)
    half = side * meta["step"] // 2
    return [
        Window(
            seed,
            int(r),
            int(c),
            meta["minX"] + int(c) * meta["step"] + half,
            meta["minZ"] + int(r) * meta["step"] + half,
            round(float(lp), 2),
            round(float(s), 3),
        )
        for r, c, lp, s in zip(r0.ravel(), c0.ravel(), land_pct.ravel(), score.ravel())
    ]


def render_command(args, w: Window) -> str:
    parts = [
        "tools/terrain_atlas.py render",
        f"--seed {w.seed}",
        f"--center {w.cx} {w.cz}",
        f"--size {args.size}",
        f"--step {args.render_step}",
    ]
    if args.footprint > 1:
        parts.append(f"--footprint {args.footprint}")
    if args.scale != 1:
        parts.append(f"--scale {args.scale:g}")
    if args.ref:
        parts.append(f"--ref {args.ref}")
    parts += [f"--set {s}" for s in args.set]
    return " ".join(parts)


def thumbnails(entries, images_of: dict[int, Path], side, path: Path):
    """Crops of each window from its seed's search-raster image, labelled."""
    cols = min(4, max(1, len(entries)))
    rows = (len(entries) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * THUMB, max(1, rows) * (THUMB + 30)), (17, 17, 17))
    draw = ImageDraw.Draw(sheet)
    images: dict[int, Image.Image] = {}
    for i, (label, w) in enumerate(entries):
        if w.seed not in images:
            images[w.seed] = Image.open(images_of[w.seed]).convert("RGB")
        crop = images[w.seed].crop((w.col, w.row, w.col + side, w.row + side))
        x, y = (i % cols) * THUMB, (i // cols) * (THUMB + 30)
        sheet.paste(crop.resize((THUMB, THUMB), Image.Resampling.NEAREST), (x, y + 30))
        draw.text((x + 4, y + 2), label, fill=(230, 230, 230))
        draw.text((x + 4, y + 15), f"seed {w.seed} ({w.cx}, {w.cz})", fill=(160, 160, 160))
    sheet.save(path, optimize=True)


def write_page(out: Path, title: str, records: list[dict], note: str = ""):
    keys = [k for k in records[0] if k != "command"] if records else []
    body = "".join(
        "<tr>"
        + "".join(f"<td>{html.escape(str(r[k]))}</td>" for k in keys)
        + f"<td><code>{html.escape(r['command'])}</code></td></tr>"
        for r in records
    )
    page = [
        "<!doctype html><meta charset=utf-8>",
        f"<title>{html.escape(title)}</title>",
        "<style>body{font:14px system-ui;background:#111;color:#ddd;margin:16px}"
        "img{max-width:100%;border:1px solid #333}table{border-collapse:collapse;margin:8px 0}"
        "td,th{border:1px solid #333;padding:3px 8px}th{background:#222}code{color:#9c9}</style>",
        f"<h1>{html.escape(title)}</h1>{note}<img src='sheet.png'>",
        "<table><tr>" + "".join(f"<th>{k}</th>" for k in keys) + "<th>render</th></tr>",
        body + "</table>",
    ]
    (out / "index.html").write_text("\n".join(page), encoding="utf-8")


def write_csv(records: list[dict], path: Path):
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(records[0]) if records else ["rank"])
        writer.writeheader()
        writer.writerows(records)


def geometry(args, step):
    side = args.size // step
    if side < 2 or args.extent < args.size:
        raise SystemExit("hunt: --size must cover 2+ pixels and fit inside --extent")
    return side, max(1, side // 2)


def hunt(args) -> Path:
    from atlas.build import CACHE

    target = args.stage or args.biome or args.variant
    kind = "stage" if args.stage else "biome" if args.biome else "variant"
    name = f"{kind}-{target}_{args.size}_e{args.extent}_s{args.step}_{len(args.seeds)}seeds"
    out = CACHE / "hunt" / name
    images, found = {}, []
    side = stride = 0
    for seed in args.seeds:
        render, atlas = sample_seed(args, seed)
        side, stride = geometry(args, atlas.step)
        mask, over_land = target_mask(atlas, args)
        land = (atlas.layer("flags.u8", np.uint8) & 1) == 0
        score = shares(mask, land if over_land else None, side, stride)
        images[seed] = out / "targets" / f"{seed}.png"
        highlight(render, mask, images[seed])
        windows = windows_of(atlas, seed, side, stride, score)
        found += [w for w in windows if not over_land or w.land >= args.min_land]
    best = [w for w in spaced(found, side, args.candidates) if w.score > 0]
    measure = "% of window land changed" if args.stage else "% of window"
    records = [
        {
            "rank": i + 1,
            "seed": w.seed,
            "centerX": w.cx,
            "centerZ": w.cz,
            "size": args.size,
            "score": w.score,
            "landPct": w.land,
            "command": render_command(args, w),
        }
        for i, w in enumerate(best)
    ]
    write_csv(records, out / "hunt.csv")
    labels = [(f"#{i + 1}: {w.score}%", w) for i, w in enumerate(best)]
    thumbnails(labels, images, side, out / "sheet.png")
    active = sum(1 for w in found if w.score > 0)
    note = (
        f"<p>Score: {measure} where {kind} {html.escape(target)} holds"
        f" ({'|delta| >= ' + str(args.threshold) + ' blocks' if args.stage else 'final raster'})."
        f" {active} of {len(found)} windows over {len(args.seeds)} seeds score above zero"
        + (f" (windows under {args.min_land}% land left out)" if args.stage else "")
        + ". Thumbnails show the target in magenta over dimmed elevation.</p>"
    )
    write_page(out, f"Hunt: {kind} {target}", records, note)
    print(f"{active} of {len(found)} windows active; top {len(records)} -> {out / 'index.html'}")
    for r in records[:3]:
        print(f"  #{r['rank']} {r['score']}%: {r['command']}")
    return out


def coverage(args) -> Path:
    from atlas.build import CACHE

    table: dict[tuple, dict[str, float]] = {}
    windows: dict[tuple, Window] = {}
    renders: dict[int, Path] = {}
    stages: list[str] = []
    side = 0
    for seed in args.seeds:
        out, atlas = sample_seed(args, seed)
        renders[seed] = out
        side, stride = geometry(args, atlas.step)
        stages = stages or atlas.stages
        land = (atlas.layer("flags.u8", np.uint8) & 1) == 0
        deltas, _ = atlas.deltas()
        per_stage = {
            s: shares(np.abs(deltas[k]) >= args.threshold, land, side, stride)
            for k, s in enumerate(atlas.stages)
        }
        base = windows_of(atlas, seed, side, stride, np.zeros_like(per_stage[stages[0]]))
        flat = {s: v.ravel() for s, v in per_stage.items()}
        for i, w in enumerate(base):
            if w.land < args.min_land:
                continue
            key = (seed, w.row, w.col)
            windows[key] = w
            table[key] = {s: round(float(flat[s][i]), 3) for s in atlas.stages}
    picked, covered, weak, uncovered = cover(table, stages, args.min_share)
    out = (
        CACHE / "hunt" / f"coverage_{args.size}_e{args.extent}_s{args.step}_{len(args.seeds)}seeds"
    )
    out.mkdir(parents=True, exist_ok=True)
    proposal, records, entries = [], [], []
    for i, key in enumerate(picked):
        w = windows[key]
        mine = [s for s, (k, _) in covered.items() if k == key]
        name = f"cover-{i + 1}-{mine[0]}-{w.seed}"
        entry = {"name": name, "seed": w.seed, "center": [w.cx, w.cz], "size": args.size}
        entry["step"] = args.render_step
        if args.footprint > 1:
            entry["footprint"] = args.footprint
        if args.scale != 1:
            entry["scale"] = args.scale
        proposal.append(entry)
        records.append(
            {
                "rank": i + 1,
                "name": name,
                "seed": w.seed,
                "centerX": w.cx,
                "centerZ": w.cz,
                "size": args.size,
                "landPct": w.land,
                "covers": " ".join(
                    f"{s} {table[key][s]}%" + ("*" if s in weak else "") for s in mine
                ),
                "command": render_command(args, w),
            }
        )
        entries.append((name, w))
    document = {
        "comment": (
            "Proposed golden windows that together exercise every stage, from terrain_atlas.py"
            " hunt --coverage; digests are filled by atlas_golden.py update when adopted."
        ),
        "windows": proposal,
        "stages": {
            s: {"window": f"cover-{picked.index(k) + 1}", "landPct": v}
            for s, (k, v) in covered.items()
        },
        "belowMinimum": weak,
        "uncovered": uncovered,
        "search": {
            "seeds": args.seeds,
            "center": list(args.center),
            "extent": args.extent,
            "step": args.step,
            "footprint": args.footprint,
            "threshold": args.threshold,
            "minShare": args.min_share,
        },
    }
    (out / "golden-windows.json").write_text(
        json.dumps(document, indent=2) + "\n", encoding="utf-8"
    )
    write_csv(records, out / "hunt.csv")
    thumbnails(
        entries, {s: r / "features.png" for s, r in renders.items()}, side, out / "sheet.png"
    )
    note = (
        f"<p>{len(picked)} windows exercise {len(covered)} of {len(stages)} stages, each at"
        f" {args.min_share}%+ of a window's land except those marked * (never that active in"
        f" the search: {html.escape(', '.join(weak) or 'none')}). Never active in the search:"
        f" {html.escape(', '.join(uncovered) or 'none')}."
        " Proposed list: <a href='golden-windows.json'>golden-windows.json</a>.</p>"
    )
    write_page(out, "Hunt: stage coverage", records, note)
    print(
        f"{len(picked)} windows cover {len(covered)}/{len(stages)} stages"
        f" (below {args.min_share}%: {', '.join(weak) or 'none'};"
        f" never active: {', '.join(uncovered) or 'none'}) -> {out / 'golden-windows.json'}"
    )
    return out


def add_parser(subparsers) -> argparse.ArgumentParser:
    import terrain_atlas as cli

    p = subparsers.add_parser(
        "hunt", help="rank windows by how active a stage, biome or variant is"
    )
    cli.add_window(p, size=2048, step=64)
    cli.add_source(p)
    target = p.add_mutually_exclusive_group(required=True)
    target.add_argument("--stage", help="score by the share of land this stage changes")
    target.add_argument("--biome", help="score by this final biome's share")
    target.add_argument("--variant", help="score by this biome variant's share")
    target.add_argument(
        "--coverage", action="store_true", help="propose windows that exercise every stage"
    )
    p.add_argument("--seeds", type=int, nargs="+", default=DEFAULT_SEEDS)
    p.add_argument(
        "--extent", type=int, default=16384, help="side of the searched square per seed (16384)"
    )
    p.add_argument("--candidates", type=int, default=8, help="windows to report (8)")
    p.add_argument(
        "--render-step", type=int, default=8, help="step in the suggested render commands (8)"
    )
    p.add_argument(
        "--min-land",
        type=float,
        default=10.0,
        help="stage and coverage: leave out windows under this %% land (10)",
    )
    p.add_argument(
        "--min-share", type=float, default=1.0, help="coverage: %% of land a stage must change (1)"
    )
    p.set_defaults(run=run)
    return p


def run(args):
    return coverage(args) if args.coverage else hunt(args)
