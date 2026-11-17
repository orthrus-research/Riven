"""The same window rendered at several commits and the working tree, with metrics across them.

Each ref is sampled through `--ref` (a cached `git archive` compiled with the working tree's
sampler), so a commit whose layout or stages the sampler cannot build is a skipped row with the
reason. Metrics are read generically from each render's `summary.json`: every numeric leaf becomes
a column, so new summary metrics appear without changes here.
"""

import argparse
import csv
import hashlib
import html
import json
import re
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

# Identity, not metrics: the window, the threshold and definition fingerprints.
SKIP_TOP = {"seed", "bounds", "threshold", "fingerprints", "digest", "terrainVersion"}
# Presentation fields and the per-reason breakdowns (thousands of sparse columns).
SKIP_ANY = {"color", "reasons", "landReasons"}
ID_KEYS = ("name", "lane", "label")
WALL_BLOCKS = 2  # the traversal check's walls: steps of 2 or more blocks
THUMB = 224


def flatten(summary: dict) -> dict[str, float]:
    """Every numeric leaf of summary.json as a dotted column; list items are keyed by their
    name, lane or label, so a column means the same thing at every ref."""
    out: dict[str, float] = {}

    def walk(value, prefix):
        if isinstance(value, bool):
            out[prefix] = int(value)
        elif isinstance(value, (int, float)):
            out[prefix] = value
        elif isinstance(value, dict):
            for key, item in value.items():
                if key not in SKIP_ANY and not (not prefix and key in SKIP_TOP):
                    walk(item, f"{prefix}.{key}" if prefix else key)
        elif isinstance(value, list):
            for i, item in enumerate(value):
                ident = next((item[k] for k in ID_KEYS if isinstance(item, dict) and k in item), i)
                walk(item, f"{prefix}.{ident}")

    walk(summary, "")
    return out


def walls_share(raw: Path) -> float | None:
    """Share of land pixels whose step to the next column east or south is a wall."""
    walls_file = raw / "walls.u8"
    if not walls_file.exists():
        return None
    meta = json.loads((raw / "atlas.json").read_text(encoding="utf-8"))
    shape = (meta["height"], meta["width"])
    walls = np.fromfile(walls_file, np.uint8).reshape(shape)
    land = (np.fromfile(raw / "flags.u8", np.uint8).reshape(shape) & 1) == 0
    return (
        round(100.0 * float((walls[land] >= WALL_BLOCKS).sum()) / land.sum(), 3)
        if land.any()
        else 0.0
    )


def failure_reason(error: BaseException) -> str:
    """One line saying why a ref could not be sampled: the first javac error, the sampler's last
    exception line, or the error itself."""
    if isinstance(error, subprocess.CalledProcessError):
        stderr = error.stderr
        text = (
            stderr.decode(errors="replace") if isinstance(stderr, bytes) else str(stderr or error)
        )
    elif isinstance(error, SystemExit):
        text = str(error.code)
    else:
        text = f"{type(error).__name__}: {error}"
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if text.startswith("javac failed"):
        errors = [line for line in lines if ": error:" in line]
        total = next((line for line in lines if re.fullmatch(r"\d+ errors?", line)), None)
        if errors:
            # Keep the file name, not the cache path it was extracted to.
            first = re.sub(r"^.*[\\/]", "", errors[0])
            suffix = f" ({total})" if total else ""
            return f"sampler does not compile against this tree: {first}{suffix}"[:300]
    if text.startswith("sampler failed"):
        thrown = [line for line in lines if re.match(r"([\w.$]+(Exception|Error)|Caused by)", line)]
        return ("sampler failed: " + (thrown[-1] if thrown else lines[-1]))[:300]
    return (lines[0] if lines else type(error).__name__)[:300]


def resolve(ref: str) -> tuple[str, str, str]:
    """(full commit, subject, commit date) of a ref."""
    from atlas.build import ROOT

    result = subprocess.run(
        ["git", "log", "-1", "--format=%H%x00%s%x00%cs", ref + "^{commit}", "--"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"history: {ref!r} is not a commit")
    commit, subject, date = result.stdout.strip().split("\x00")
    return commit, subject, date


def sample_ref(args, ref: str | None) -> dict:
    """One row: render the window at ref (None = working tree) or record why it could not."""
    import terrain_atlas as cli

    from atlas.build import Source

    if ref is None:
        row: dict[str, object] = {
            "ref": "worktree",
            "commit": "",
            "subject": "(working tree)",
            "date": "",
        }
        source = Source()
    else:
        commit, subject, date = resolve(ref)
        row = {"ref": ref, "commit": commit[:12], "subject": subject, "date": date}
        source = Source(commit[:12])
    try:
        out = cli.render(args, source, viewer=False)
    except (SystemExit, subprocess.CalledProcessError, OSError, KeyError, ValueError) as error:
        print(f"{row['ref']}: skipped, {failure_reason(error)}")
        return row | {"status": "skipped", "reason": failure_reason(error)}
    summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
    row |= {
        "status": "ok",
        "reason": "",
        "terrainVersion": summary.get("terrainVersion"),
        "digest": summary.get("digest") or "",
        "render": out,
    }
    walls = walls_share(out / "raw")
    metrics = flatten(summary)
    if walls is not None:
        metrics["wallsLandPct"] = walls
    row["metrics"] = metrics
    return row


IDENTITY = ["ref", "commit", "date", "subject", "status", "reason", "terrainVersion", "digest"]


def write_csv(rows: list[dict], path: Path) -> list[str]:
    """history.csv: identity columns, then the union of metric columns in first-seen order."""
    metrics: list[str] = []
    for row in rows:
        for key in row.get("metrics", {}):
            if key not in metrics:
                metrics.append(key)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(IDENTITY + metrics)
        for row in rows:
            values = row.get("metrics", {})
            writer.writerow(
                [row.get(k, "") for k in IDENTITY] + [values.get(k, "") for k in metrics]
            )
    return metrics


def varying(rows: list[dict], metrics: list[str]) -> list[str]:
    """Metrics that differ between the sampled refs (missing counts as a difference)."""
    ok = [r for r in rows if r["status"] == "ok"]
    return [m for m in metrics if len({r["metrics"].get(m) for r in ok}) > 1]


def tick(row: dict) -> str:
    version = f" v{row['terrainVersion']}" if row.get("terrainVersion") is not None else ""
    return (row["commit"][:8] or "worktree") + version


def charts(rows: list[dict], metrics: list[str], out: Path) -> list[tuple[str, str]]:
    """One PNG per varying metric across refs; skipped refs stay on the axis, marked."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    out.mkdir(parents=True, exist_ok=True)
    labels = [tick(r) for r in rows]
    files = []
    for metric in metrics:
        values = [r.get("metrics", {}).get(metric, np.nan) for r in rows]
        fig, ax = plt.subplots(
            figsize=(max(4.0, 0.9 * len(rows) + 1.5), 2.6), constrained_layout=True
        )
        ax.plot(range(len(rows)), values, marker="o", color="#3a78c8")
        for i, row in enumerate(rows):
            if row["status"] != "ok":
                ax.axvline(i, color="#c84", lw=1, ls=":")
        ax.set_xticks(range(len(rows)), labels, rotation=30, ha="right", fontsize=7)
        ax.set_title(metric, fontsize=9)
        ax.tick_params(axis="y", labelsize=7)
        name = re.sub(r"[^\w.-]+", "_", metric) + ".png"
        fig.savefig(out / name, dpi=90)
        plt.close(fig)
        files.append((metric, f"charts/{name}"))
    return files


def contact_sheet(rows: list[dict], path: Path):
    """Elevation over features, one column per ref; a skipped ref's column says why."""
    gap = 34
    sheet = Image.new("RGB", (len(rows) * THUMB, 2 * THUMB + gap), (17, 17, 17))
    draw = ImageDraw.Draw(sheet)
    for i, row in enumerate(rows):
        x = i * THUMB
        draw.text((x + 4, 3), row["ref"][:34], fill=(230, 230, 230))
        draw.text((x + 4, 17), tick(row), fill=(160, 160, 160))
        if row["status"] == "ok":
            for j, layer in enumerate(("elevation.png", "features.png")):
                img = Image.open(row["render"] / layer).convert("RGB")
                img = img.resize((THUMB, THUMB), Image.Resampling.NEAREST)
                sheet.paste(img, (x, gap + j * THUMB))
        else:
            draw.rectangle((x + 2, gap, x + THUMB - 3, gap + 2 * THUMB - 3), outline=(200, 120, 60))
            words, line, y = row["reason"].split(), "", gap + 8
            for word in ["skipped:"] + words:
                if len(line) + len(word) > 32:
                    draw.text((x + 8, y), line, fill=(220, 160, 110))
                    line, y = "", y + 14
                line = f"{line} {word}".strip()
            draw.text((x + 8, y), line, fill=(220, 160, 110))
    sheet.save(path, optimize=True)


def write_index(rows, metrics, changed, files, out: Path, title: str):
    def cell(row, key):
        value = row.get(key, "")
        return html.escape(str(value)[:16] if key == "digest" else str(value))

    head = "".join(f"<th>{k}</th>" for k in IDENTITY)
    body = "".join(
        "<tr>" + "".join(f"<td>{cell(r, k)}</td>" for k in IDENTITY) + "</tr>" for r in rows
    )
    steady = [m for m in metrics if m not in changed]
    page = [
        "<!doctype html><meta charset=utf-8>",
        f"<title>{html.escape(title)}</title>",
        "<style>body{font:14px system-ui;background:#111;color:#ddd;margin:16px}"
        "img{max-width:100%;border:1px solid #333}table{border-collapse:collapse;margin:8px 0}"
        "td,th{border:1px solid #333;padding:3px 8px}th{background:#222}"
        ".grid{display:flex;flex-wrap:wrap;gap:8px}a{color:#8cf}</style>",
        f"<h1>{html.escape(title)}</h1><p><a href='history.csv'>history.csv</a></p>",
        f"<table><tr>{head}</tr>{body}</table>",
        "<h2>Elevation and features</h2><img src='sheet.png'>",
        f"<h2>Metrics that change ({len(changed)})</h2><div class=grid>",
        "".join(f"<img src='{f}' title='{html.escape(m)}'>" for m, f in files),
        f"</div><h2>Unchanged across sampled refs ({len(steady)})</h2>",
        f"<p>{html.escape(', '.join(steady))}</p>",
    ]
    (out / "index.html").write_text("\n".join(page), encoding="utf-8")


def history(args) -> Path:
    from atlas.build import CACHE

    refs = list(dict.fromkeys(args.refs))
    rows = [sample_ref(args, ref) for ref in refs] + [sample_ref(args, None)]
    key = hashlib.sha256(" ".join(r["commit"] or r["ref"] for r in rows).encode()).hexdigest()[:10]
    size = f"{args.size}_s{args.step}" + (f"_f{args.footprint}" if args.footprint > 1 else "")
    size += "_w" if args.walls else ""
    out = CACHE / "history" / f"{args.seed}_{args.center[0]}_{args.center[1]}_{size}_{key}"
    out.mkdir(parents=True, exist_ok=True)
    metrics = write_csv(rows, out / "history.csv")
    changed = varying(rows, metrics)
    files = charts(rows, changed, out / "charts")
    contact_sheet(rows, out / "sheet.png")
    title = (
        f"History of seed {args.seed} at ({args.center[0]}, {args.center[1]}), {args.size} blocks"
    )
    write_index(rows, metrics, changed, files, out, title)
    skipped = [r for r in rows if r["status"] != "ok"]
    print(
        f"{len(rows) - len(skipped)} sampled, {len(skipped)} skipped; {len(changed)} of"
        f" {len(metrics)} metrics change -> {out / 'index.html'}"
    )
    return out


def add_parser(subparsers) -> argparse.ArgumentParser:
    import terrain_atlas as cli

    p = subparsers.add_parser("history", help="the same window at several commits and the worktree")
    cli.add_window(p, size=2048, step=16)
    p.add_argument("--refs", nargs="+", required=True, metavar="REF", help="commits, oldest first")
    p.set_defaults(run=run)
    return p


def run(args):
    return history(args)
