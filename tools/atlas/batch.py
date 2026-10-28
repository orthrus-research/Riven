"""Comparisons, contact sheets for seed lists or parameter sweeps, and cross-section plots."""

import csv
import html
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from atlas.paint import STYLE, Atlas, Painter, family_color, save, stage_color, swatch


def compare(a_dir: Path, b_dir: Path, out: Path, threshold: float, labels):
    """Pixel-wise differences of two renders of the same window."""
    a, b = Atlas(a_dir / "raw"), Atlas(b_dir / "raw")
    for key in ("minX", "minZ", "step", "width", "height"):
        if a.meta[key] != b.meta[key]:
            raise SystemExit(f"compare: renders differ in {key}")
    out.mkdir(parents=True, exist_ok=True)
    pa, pb = Painter(a, threshold), Painter(b, threshold)

    dh = pb.surface - pa.surface
    scale = max(4.0, float(np.percentile(np.abs(dh), 99.5)))
    t = np.clip(dh / scale, -1, 1)[..., None]
    red, blue = np.array([200, 40, 30]), np.array([30, 80, 210])
    img = np.where(t > 0, pb.grey * (1 - t) + red * t, pb.grey * (1 + t) - blue * t)
    shore_changed = pa.marine != pb.marine
    img[shore_changed] = (250, 220, 40)
    save(img, out / "height.png")

    wa, fa, _, ka = a.winners(threshold)
    wb, fb, _, kb = b.winners(threshold)
    code_a = np.where(ka, wa * 256 + fa, -1)
    code_b = np.where(kb, wb * 256 + fb, -1)
    changed = code_a != code_b
    img = pb.base.copy() * 0.7
    legend = []
    for k, stage in enumerate(b.stages):
        labels_k = ["(no family)"] + b.stage_meta(stage)["families"]
        for f, label in enumerate(labels_k):
            mask = changed & (code_b == k * 256 + f)
            if mask.any():
                color = family_color(stage, f, len(labels_k))
                img[mask] = color
                legend.append(
                    {
                        "label": f"now {stage}: {label.lower()}" if f else f"now {stage}",
                        "color": color,
                        "pct": pb.coverage(mask),
                    }
                )
    lost = changed & (code_b < 0) & ~pb.marine
    img[lost] = (230, 40, 200)
    legend.append({"label": "feature removed", "color": (230, 40, 200), "pct": pb.coverage(lost)})
    save(img, out / "features.png")

    rows = []
    for k, stage in enumerate(a.stages):
        da = np.abs(a.layer(f"delta_{stage}.f32", "<f4")) >= threshold
        db = np.abs(b.layer(f"delta_{stage}.f32", "<f4")) >= threshold
        ra = a.stage_meta(stage).get("landReasons", {})
        rb = b.stage_meta(stage).get("landReasons", {})
        keys = sorted(set(ra) | set(rb), key=lambda r: -(rb.get(r, 0) + ra.get(r, 0)))
        la, lb = sum(ra.values()) or 1, sum(rb.values()) or 1
        rows.append(
            {
                "stage": stage,
                "activeA": pa.land_share(da),
                "activeB": pb.land_share(db),
                "reasons": [
                    (r, round(100 * ra.get(r, 0) / la, 1), round(100 * rb.get(r, 0) / lb, 1))
                    for r in keys[:6]
                ],
            }
        )
    summary = {
        "a": labels[0],
        "b": labels[1],
        "digestA": a.meta.get("digest"),
        "digestB": b.meta.get("digest"),
        "identical": a.meta.get("digest") == b.meta.get("digest"),
        "landPct": [pa.coverage(~pa.marine), pb.coverage(~pb.marine)],
        "coastlineChangedPct": pb.coverage(shore_changed),
        "heightChange": {
            "meanAbs": round(float(np.abs(dh).mean()), 2),
            "max": round(float(dh.max()), 1),
            "min": round(float(dh.min()), 1),
            "changedPct": pb.coverage(np.abs(dh) >= 1),
        },
        "featureChangedPct": pb.coverage(changed),
        "stages": rows,
        "legend": legend,
    }
    (out / "compare.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    table = "".join(
        f"<tr><td>{r['stage']}</td><td>{r['activeA']}%</td><td>{r['activeB']}%</td><td>"
        + html.escape("; ".join(f"{n} {x}% -> {y}%" for n, x, y in r["reasons"]))
        + "</td></tr>"
        for r in rows
    )
    h = summary["heightChange"]
    page = [
        f"<!doctype html><meta charset=utf-8><title>Atlas compare</title>{STYLE}",
        f"<h1>{html.escape(labels[0])} &rarr; {html.escape(labels[1])}</h1>",
        f"<p>{'Identical digests: no sampled value changed.' if summary['identical'] else ''}"
        f" Land {summary['landPct'][0]}% &rarr; {summary['landPct'][1]}%; coastline moved on"
        f" {summary['coastlineChangedPct']}% of pixels. Height changed by 1+ blocks on"
        f" {h['changedPct']}% (mean |dh| {h['meanAbs']}, range {h['min']}..{h['max']}).</p>",
        "<h2>Height change (red higher, blue lower, yellow = land/sea ownership changed)</h2>",
        f"<section><img src='height.png'><p>Full colour at +/-{round(scale, 1)} blocks.</p>"
        "</section><h2>Largest-change feature that differs</h2><section>"
        f"<img src='features.png'><ul>{''.join(swatch(e) for e in legend)}</ul></section>",
        "<h2>Stage activity and land reasons</h2><table><tr><th>stage</th>"
        f"<th>A active</th><th>B active</th><th>reasons A -> B</th></tr>{table}</table>",
        f"<p><a href='{(a_dir / 'index.html').as_uri()}'>A</a> &middot; "
        f"<a href='{(b_dir / 'index.html').as_uri()}'>B</a></p>",
    ]
    (out / "index.html").write_text("\n".join(page), encoding="utf-8")
    return summary


def contact_sheet(entries, out: Path, title: str, thumb=256):
    """Grid of feature thumbnails plus a stats table; entries = [(label, render dir)]."""
    out.mkdir(parents=True, exist_ok=True)
    cols = min(4, len(entries))
    rows_n = (len(entries) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * thumb, rows_n * (thumb + 20)), (17, 17, 17))
    draw = ImageDraw.Draw(sheet)
    records = []
    for i, (label, directory) in enumerate(entries):
        summary = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
        img = Image.open(directory / "features.png").convert("RGB")
        img = img.resize((thumb, thumb), Image.Resampling.NEAREST)
        x, y = (i % cols) * thumb, (i // cols) * (thumb + 20)
        sheet.paste(img, (x, y + 20))
        draw.text((x + 4, y + 4), str(label), fill=(230, 230, 230))
        record = {
            "label": label,
            "landPct": summary["landPct"],
            "dryPct": summary["dryAboveWaterPct"],
            "p50LandY": summary["surfaceY"]["p50Land"],
            "digest": (summary.get("digest") or "")[:16],
        }
        for s in summary["stages"]:
            record[s["name"]] = s["activeLandPct"]
        if summary.get("sites"):
            record["sitesAdmitted"] = sum(s["raw"] - s["vetoed"] for s in summary["sites"])
            record["sitesBuilt"] = sum(s["built"] for s in summary["sites"])
            record["sitesOnLand"] = sum(
                s["raw"] - s["vetoed"] - s["marine"] for s in summary["sites"]
            )
        record["index"] = (directory / "index.html").as_uri()
        records.append(record)
    sheet.save(out / "sheet.png", optimize=True)
    keys = [k for k in records[0] if k != "index"]
    with (out / "stats.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=keys, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)
    head = "".join(f"<th>{html.escape(k)}</th>" for k in keys)
    body = "".join(
        "<tr>"
        + "".join(
            f"<td><a href='{r['index']}'>{html.escape(str(r[k]))}</a></td>"
            if k == "label"
            else f"<td>{html.escape(str(r.get(k, '')))}</td>"
            for k in keys
        )
        + "</tr>"
        for r in records
    )
    stage_keys = [k for k in keys if k not in ("label", "landPct", "dryPct", "p50LandY", "digest")]
    means = {k: round(float(np.mean([r.get(k, 0) or 0 for r in records])), 3) for k in stage_keys}
    page = [
        f"<!doctype html><meta charset=utf-8><title>{html.escape(title)}</title>{STYLE}",
        f"<h1>{html.escape(title)}</h1><img src='sheet.png'>",
        "<p>Stage columns are the % of land each stage changes by at least the threshold.</p>",
        f"<table><tr>{head}</tr>{body}</table>",
        "<h2>Means</h2><table>"
        + "".join(f"<tr><td>{k}</td><td>{v}</td></tr>" for k, v in means.items())
        + "</table>",
    ]
    (out / "index.html").write_text("\n".join(page), encoding="utf-8")
    return records


def plot_section(out: Path):
    """Height after every stage along the line, with the largest-change stage underneath."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    meta = json.loads((out / "section.json").read_text(encoding="utf-8"))
    with (out / "section.csv").open(encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    stages = meta["stages"]
    dist = np.array([float(r["distance"]) for r in rows])
    heights = {name: np.array([float(r[name]) for r in rows]) for name in ["base", *stages]}
    final = np.array([float(r["blockY"]) for r in rows])
    water = np.array([float(r["waterY"]) if r["waterY"] else np.nan for r in rows])
    marine = np.array([r["marine"] == "true" for r in rows])
    fig, (ax, strip) = plt.subplots(
        2, 1, figsize=(14, 6), height_ratios=[6, 1], sharex=True, constrained_layout=True
    )
    ax.fill_between(
        dist, final, np.where(np.isnan(water), final, water), color="#3a78c8", alpha=0.5
    )
    ax.plot(dist, heights["base"], color="#999", lw=1, label="base + erosion")
    previous = heights["base"]
    deltas = []
    for name in stages:
        d = heights[name] - previous
        if name == "sediment":
            d = heights[name] - heights["rocky"]
        deltas.append(d)
        if np.abs(d).max() >= 0.5:
            c = np.array(stage_color(name)) / 255
            ax.plot(dist, heights[name], color=c, lw=1, label=f"after {name}")
        previous = heights[name]
    ax.plot(dist, final, color="black", lw=1.6, label="final surface")
    ax.axhline(meta["waterTopY"], color="#3a78c8", lw=0.8, ls="--")
    ax.set_ylabel("Y")
    ax.legend(loc="upper right", fontsize=8, ncols=3)
    ax.set_title(
        f"seed {meta['seed']} terrain-v{meta['terrainVersion']}: "
        f"({meta['x1']:.0f},{meta['z1']:.0f}) -> ({meta['x2']:.0f},{meta['z2']:.0f})"
    )
    mags = np.abs(np.stack(deltas))
    winner = mags.argmax(axis=0)
    colors = np.array([stage_color(n) for n in stages], np.float32) / 255
    band = colors[winner]
    band[(mags.max(axis=0) < 1) | marine] = (0.85, 0.85, 0.85)
    strip.imshow(
        band[None], aspect="auto", extent=(dist[0], dist[-1], 0, 1), interpolation="nearest"
    )
    strip.set_yticks([])
    strip.set_xlabel("distance along section (blocks)")
    strip.set_title("largest-change stage (grey: none or marine)", fontsize=9)
    fig.savefig(out / "section.png", dpi=110)
    plt.close(fig)
