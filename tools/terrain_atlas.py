"""Seed-driven maps of what Riven's production generator selects; never launches Minecraft.

Every value is sampled from the real worldgen code (working tree or a git ref) through
tools/checks/TerrainAtlas.java: one complete native-path TerrainMap.sample per pixel, the real
Sites decisions and each stage's own reason field. Renders carry a SHA-256 digest of every
deterministic layer; --verify re-samples with another thread count and order and requires the
same digest.

Setup once:
  python -m venv .local/atlas-venv
  .local/atlas-venv/Scripts/python -m pip install -r tools/requirements-atlas.txt

Commands (run with .local/atlas-venv/Scripts/python):
  render   one window: maps, reason maps, sites, cost, summary and an interactive viewer
  compare  the same window from two sources (git refs and/or --set overrides)
  seeds    the same window for several seeds, with a contact sheet and statistics
  sweep    one definition value across several settings, with a contact sheet
  section  heights after every stage along a line

Examples:
  tools/terrain_atlas.py render --seed 8675309 --verify
  tools/terrain_atlas.py render --center 1472 -1472 --size 2048 --step 4 --footprint 2
  tools/terrain_atlas.py compare --ref HEAD~1 --vs-ref HEAD
  tools/terrain_atlas.py compare --vs-set riven-terrain.json:sites.anchorProbability=0.9
  tools/terrain_atlas.py seeds --seeds 8675309 101 -991 7 42 --size 4096 --step 32
  tools/terrain_atlas.py sweep --param riven-continents.json:scale --values 0.5 1 2
  tools/terrain_atlas.py section --line -4000 -2000 4000 -2000
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from atlas.batch import compare, contact_sheet, plot_section  # noqa: E402
from atlas.build import CACHE, Source, prepare, run_sampler  # noqa: E402
from atlas.paint import Atlas, paint  # noqa: E402
from atlas.viewer import write_viewer  # noqa: E402


def window_options(args):
    pixels = args.size // args.step
    return {
        "seed": args.seed,
        "cx": args.center[0],
        "cz": args.center[1],
        "width": pixels,
        "height": pixels,
        "step": args.step,
        "footprint": args.footprint,
        # More threads than physical cores slowed sampling (31: 18 s, 16: 8.5 s on 8675309).
        "threads": args.threads or max(1, (os.cpu_count() or 2) // 2),
        "sites": "true" if args.sites else None,
        "scale": args.scale if args.scale != 1 else None,
    }


def render_dir(args, source: Source, seed=None):
    seed = args.seed if seed is None else seed
    fp = f"_f{args.footprint}" if args.footprint > 1 else ""
    fp += f"_x{args.scale:g}" if args.scale != 1 else ""
    name = f"{seed}_{args.center[0]}_{args.center[1]}_{args.size}_s{args.step}{fp}_{source.label}"
    return CACHE / "renders" / name


def render(args, source: Source, seed=None, out=None, viewer=True):
    """Sample (unless reusable) and paint one window; returns the render directory."""
    seed = args.seed if seed is None else seed
    out = (out or render_dir(args, source, seed)).resolve()
    options = window_options(args) | {"seed": seed}
    # Raw samples are reused only for identical compiled sources, overrides and window.
    classes = prepare(source)[1]
    # Thread count is excluded: --verify proves it cannot change sampled values.
    key = {k: v for k, v in options.items() if k != "threads"}
    spec = {"classes": classes.name, "sets": list(source.sets), "options": key}
    spec_file = out / "raw/spec.json"
    reuse = (
        not args.resample
        and spec_file.exists()
        and json.loads(spec_file.read_text(encoding="utf-8")) == spec
    )
    if not reuse:
        if out.exists():
            shutil.rmtree(out)
        run_sampler(source, out / "raw", options, out / "overrides")
        spec_file.write_text(json.dumps(spec), encoding="utf-8")
    atlas = Atlas(out / "raw")
    if getattr(args, "verify", False):
        verify(source, out, options, atlas.meta["digest"])
    summary = paint(atlas, out, args.threshold)
    if viewer:
        write_viewer(atlas, out, args.threshold)
        paint_index_link(out)
    print(
        f"seed {seed}: land {summary['landPct']}%, digest {summary['digest'][:16]},"
        f" sampling {summary['seconds']['sampling']}s -> {out / 'index.html'}"
    )
    return out


def paint_index_link(out):
    index = out / "index.html"
    text = index.read_text(encoding="utf-8")
    link = "<p><a href='viewer.html'>Open the interactive viewer</a></p>"
    if link not in text:
        text = text.replace("</h1>", "</h1>" + link, 1)
        index.write_text(text, encoding="utf-8")


def verify(source, out, options, digest):
    """Re-sample in reverse order on a different thread count; the digests must match."""
    threads = max(2, (os.cpu_count() or 4) // 4)
    if options.get("threads") == threads:
        threads += 1
    check = out / "verify"
    run_sampler(source, check, options | {"threads": threads, "order": "reverse"}, check / "ov")
    other = json.loads((check / "atlas.json").read_text(encoding="utf-8"))["digest"]
    record = {"digest": digest, "verifyDigest": other, "threads": threads, "order": "reverse"}
    (out / "determinism.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    shutil.rmtree(check)
    if other != digest:
        sys.exit(f"DETERMINISM FAILURE: {digest} != {other} (threads {threads}, reverse order)")
    print(f"determinism verified: reverse order on {threads} threads gives the same digest")


def add_window(p, size=8192, step=16):
    p.add_argument("--seed", type=int, default=8675309)
    p.add_argument("--center", type=int, nargs=2, default=(0, 0), metavar=("X", "Z"))
    p.add_argument("--size", type=int, default=size, help=f"square side in blocks ({size})")
    p.add_argument("--step", type=int, default=step, help=f"blocks per pixel ({step})")
    p.add_argument(
        "--scale", type=float, default=1, help="Riven layout scale, 0.5-8 (4 = 32 km worlds)"
    )
    p.add_argument(
        "--footprint",
        type=int,
        default=1,
        help="n: also take n x n samples per pixel and keep each stage's largest change",
    )
    p.add_argument("--threads", type=int, help="sampler threads (default: cores - 1)")
    p.add_argument("--threshold", type=float, default=1.0, help="feature change in blocks")
    p.add_argument("--no-sites", dest="sites", action="store_false", help="skip site overlay")
    p.add_argument("--resample", action="store_true", help="ignore reusable raw samples")


def add_source(p, prefix=""):
    dest = prefix.replace("-", "_")
    p.add_argument(f"--{prefix}ref", dest=f"{dest}ref", help="git commit/ref (default worktree)")
    p.add_argument(
        f"--{prefix}set",
        dest=f"{dest}set",
        action="append",
        default=[],
        metavar="FILE:PATH=VALUE",
        help="override a packaged definition value (JSON), e.g. riven-terrain.json:a.b=2",
    )


def source_of(args, prefix=""):
    return Source(getattr(args, f"{prefix}ref"), tuple(getattr(args, f"{prefix}set")))


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("render", help="one window")
    add_window(p)
    add_source(p)
    p.add_argument("--verify", action="store_true", help="prove thread/order independence")
    p.add_argument("--out", type=Path)

    p = sub.add_parser("compare", help="two sources over the same window")
    add_window(p)
    add_source(p)
    add_source(p, "vs-")

    p = sub.add_parser("seeds", help="several seeds over the same window")
    add_window(p, size=4096, step=32)
    add_source(p)
    p.add_argument("--seeds", type=int, nargs="+", default=[8675309, 101, -991, 7, 42, 1234])

    p = sub.add_parser("sweep", help="one definition value across settings")
    add_window(p, size=4096, step=32)
    add_source(p)
    p.add_argument("--param", required=True, metavar="FILE:PATH")
    p.add_argument("--values", required=True, nargs="+", metavar="JSON")

    p = sub.add_parser("section", help="heights after every stage along a line")
    p.add_argument("--seed", type=int, default=8675309)
    p.add_argument("--line", type=float, nargs=4, required=True, metavar=("X1", "Z1", "X2", "Z2"))
    p.add_argument("--spacing", type=int, default=2, help="blocks between columns (2)")
    add_source(p)
    args = parser.parse_args()

    if args.command == "render":
        render(args, source_of(args), out=args.out)
    elif args.command == "compare":
        a, b = source_of(args), source_of(args, "vs_")
        if a == b:
            parser.error("compare needs --vs-ref and/or --vs-set to differ from the base")
        da, db = render(args, a), render(args, b)
        key = hashlib.sha256(f"{da}|{db}".encode()).hexdigest()[:10]
        out = CACHE / "compare" / f"{args.seed}_{a.label}_vs_{b.label}_{key}"
        result = compare(da, db, out, args.threshold, (a.label, b.label))
        print(
            f"identical={result['identical']} feature change {result['featureChangedPct']}%,"
            f" height change {result['heightChange']['changedPct']}% -> {out / 'index.html'}"
        )
    elif args.command == "seeds":
        source = source_of(args)
        entries = [(s, render(args, source, seed=s, viewer=False)) for s in args.seeds]
        out = CACHE / "seeds" / f"{source.label}_{args.size}_s{args.step}_{len(args.seeds)}"
        contact_sheet(entries, out, f"Seeds over {args.size} blocks ({source.label})")
        print(out / "index.html")
    elif args.command == "sweep":
        base = source_of(args)
        entries = []
        for value in args.values:
            source = Source(base.ref, base.sets + (f"{args.param}={value}",))
            entries.append((f"{args.param.split(':')[1]}={value}", render(args, source)))
        key = hashlib.sha256(" ".join(args.values).encode()).hexdigest()[:8]
        out = CACHE / "sweeps" / f"{args.seed}_{args.param.replace(':', '_')}_{key}"
        contact_sheet(entries, out, f"Sweep {args.param} (seed {args.seed})")
        print(out / "index.html")
    else:
        source = source_of(args)
        x1, z1, x2, z2 = args.line
        name = f"{args.seed}_{x1:g}_{z1:g}_{x2:g}_{z2:g}_s{args.spacing}_{source.label}"
        out = CACHE / "sections" / name
        options = {
            "seed": args.seed,
            "section": f"{x1},{z1},{x2},{z2}",
            "spacing": args.spacing,
        }
        run_sampler(source, out, options, out / "overrides")
        plot_section(out)
        print(out / "section.png")


if __name__ == "__main__":
    main()
