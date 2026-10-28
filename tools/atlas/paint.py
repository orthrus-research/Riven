"""Paint sampler layers into maps, legends and a summary. Pure functions of the raw layers."""

import colorsys
import html
import json
import zlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

BIOME_COLORS = {
    "ocean": (0, 0, 112),
    "deep_ocean": (0, 0, 48),
    "river": (0, 0, 255),
    "swampland": (7, 249, 178),
    "jungle_edge": (98, 139, 23),
    "beaches": (250, 222, 85),
    "stone_beach": (162, 162, 132),
    "cold_beach": (250, 240, 192),
    "plains": (141, 179, 96),
    "desert": (250, 148, 24),
    "extreme_hills": (96, 96, 96),
    "extreme_hills_with_trees": (80, 112, 80),
    "forest": (5, 102, 33),
    "forest_hills": (34, 85, 28),
    "taiga": (11, 102, 89),
    "taiga_hills": (22, 57, 51),
    "taiga_cold": (49, 85, 74),
    "taiga_cold_hills": (36, 63, 54),
    "redwood_taiga": (89, 102, 81),
    "redwood_taiga_hills": (69, 79, 62),
    "ice_flats": (255, 255, 255),
    "ice_mountains": (160, 160, 160),
    "jungle": (83, 123, 9),
    "jungle_hills": (44, 66, 5),
    "birch_forest": (48, 116, 68),
    "birch_forest_hills": (31, 95, 50),
    "roofed_forest": (64, 81, 26),
    "savanna": (189, 178, 95),
    "savanna_rock": (167, 157, 100),
    "mesa": (217, 69, 21),
    "mesa_rock": (176, 151, 101),
    "mesa_clear_rock": (202, 140, 101),
    "mutated_desert": (255, 188, 64),
}
WATER_COLORS = {
    "LAKE": (40, 110, 230),
    "RIVER": (0, 190, 255),
    "WETLAND": (60, 170, 120),
    "OASIS": (0, 230, 180),
    "LAGOON": (120, 90, 230),
    "POND": (150, 200, 255),
}
STAGE_HUES = {
    "lowland": 0.30,
    "passes": 0.17,
    "volcanic": 0.00,
    "cold": 0.53,
    "plateau": 0.07,
    "mountain": 0.75,
    "hill": 0.36,
    "landmark": 0.88,
    "carver": 0.95,
    "cut": 0.62,
    "dryland": 0.12,
    "wonder": 0.82,
    "shore": 0.45,
    "rocky": 0.58,
    "sediment": 0.14,
}
LANE_STAGE = {
    "LANDMARK": "landmark",
    "CARVER": "carver",
    "HILLS": "hill",
    "MOUNTAINS": "mountain",
    "PLATEAUS": "plateau",
    "COLD": "cold",
    "VOLCANIC": "volcanic",
    "CUTS": "cut",
    "SEDIMENT": "sediment",
    "ROCKY": "rocky",
    "WONDER": "wonder",
}

Color = tuple[int, int, int]


class Atlas:
    """Raw sampler output for one render."""

    def __init__(self, raw: Path):
        self.raw = raw
        self.meta = json.loads((raw / "atlas.json").read_text(encoding="utf-8"))
        self.shape = (self.meta["height"], self.meta["width"])
        self.step = self.meta["step"]
        self.stages = [s["name"] for s in self.meta["stages"]]
        sites = raw / "sites.json"
        self.sites = json.loads(sites.read_text(encoding="utf-8")) if sites.exists() else None

    def layer(self, name, dtype):
        return np.fromfile(self.raw / name, dtype=dtype).reshape(self.shape)

    def has(self, name):
        return (self.raw / name).exists()

    def stage_meta(self, name):
        return self.meta["stages"][self.stages.index(name)]

    def deltas(self, footprint=True):
        prefix = "fp_" if footprint and self.has(f"fp_delta_{self.stages[0]}.f32") else ""
        d = np.stack([self.layer(f"{prefix}delta_{s}.f32", "<f4") for s in self.stages])
        f = np.stack([self.layer(f"{prefix}family_{s}.u8", np.uint8) for s in self.stages])
        return d, f

    def winners(self, threshold):
        """Per pixel: (stage index, family code, |delta|, featured mask) of the largest change."""
        deltas, families = self.deltas()
        mags = np.abs(deltas)
        winner = mags.argmax(axis=0)
        strength = np.take_along_axis(mags, winner[None], 0)[0]
        family = np.take_along_axis(families, winner[None], 0)[0]
        marine = (self.layer("flags.u8", np.uint8) & 1) != 0
        return winner, family, strength, (strength >= threshold) & ~marine


def hashed_color(name: str) -> Color:
    h = zlib.crc32(name.encode()) / 2**32
    r, g, b = colorsys.hls_to_rgb(h, 0.5, 0.65)
    return int(r * 255), int(g * 255), int(b * 255)


def family_color(stage: str, index: int, count: int) -> Color:
    hue = STAGE_HUES.get(stage, 0.5)
    if count <= 1:
        light, sat = 0.5, 0.8
    else:
        t = index / (count - 1)
        light, sat = 0.38 + 0.3 * t, 0.9 - 0.25 * t
        hue = (hue + 0.04 * (t - 0.5)) % 1
    r, g, b = colorsys.hls_to_rgb(hue, light, sat)
    return int(r * 255), int(g * 255), int(b * 255)


def stage_color(stage: str) -> Color:
    r, g, b = colorsys.hls_to_rgb(STAGE_HUES.get(stage, 0.5), 0.5, 0.85)
    return int(r * 255), int(g * 255), int(b * 255)


def reason_color(reason: str) -> Color:
    """Stable colours by meaning: acceptance green, shore/water protection blue, no site grey."""
    upper = reason.upper()
    if upper in ("ACCEPTED", "APPLIED", "BUILT", "SELECTED") or upper.startswith("ACCEPT"):
        return (40, 200, 70)
    if upper in ("NO_SITE", "NOT_SELECTED", "NONE"):
        return (95, 95, 95)
    if "SHORE" in upper or "PROTECTED" in upper or "COAST" in upper:
        return (60, 120, 230)
    if "WATER" in upper or "MARINE" in upper or "HUMID" in upper:
        return (40, 190, 210)
    return hashed_color(reason)


def save(rgb, path: Path):
    Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").save(path, optimize=True)


def hillshade(surface, step):
    gz, gx = np.gradient(surface.astype(np.float32), step)
    slope = np.pi / 2 - np.arctan(np.hypot(gx, gz) * 1.5)
    aspect = np.arctan2(-gx, gz)
    azimuth, altitude = np.radians(315), np.radians(45)
    shade = np.sin(altitude) * np.sin(slope) + np.cos(altitude) * np.cos(slope) * np.cos(
        azimuth - aspect
    )
    return np.clip(shade, 0, 1)


class Painter:
    """Shared bases for one atlas."""

    def __init__(self, atlas: Atlas, threshold: float):
        self.atlas, self.threshold = atlas, threshold
        self.surface = atlas.layer("surface.i16", "<i2").astype(np.float32)
        self.water = atlas.layer("water.i16", "<i2").astype(np.float32)
        self.flags = atlas.layer("flags.u8", np.uint8)
        self.marine = (self.flags & 1) != 0
        self.wet = self.water > self.surface
        self.shade = hillshade(self.surface, atlas.step)[..., None]
        depth = np.clip((self.water - self.surface) / 120.0, 0, 1)[..., None]
        self.water_rgb = (1 - depth) * np.array([70, 130, 200]) + depth * np.array([10, 25, 70])
        self.grey = np.repeat(90 + 140 * self.shade, 3, axis=2)
        self.base = np.where(self.wet[..., None], self.water_rgb, self.grey)
        self.total = self.surface.size

    def coverage(self, mask):
        return round(100.0 * float(mask.sum()) / self.total, 3)

    def land_share(self, mask):
        land = (~self.marine).sum()
        return round(100.0 * float((mask & ~self.marine).sum()) / land, 2) if land else 0.0

    def elevation(self, path):
        sea = self.atlas.meta["waterTopY"]
        stops = [
            (sea, (70, 140, 60)),
            (sea + 30, (150, 175, 90)),
            (sea + 80, (200, 175, 110)),
            (sea + 160, (150, 120, 100)),
            (sea + 260, (240, 240, 240)),
        ]
        ys = np.array([s[0] for s in stops], np.float32)
        tint = np.stack(
            [np.interp(self.surface, ys, [s[1][c] for s in stops]) for c in range(3)], axis=-1
        )
        save(np.where(self.wet[..., None], self.water_rgb, tint * (0.45 + 0.75 * self.shade)), path)
        return [{"label": f"Y{int(y)}", "color": c} for y, c in stops] + [
            {"label": "water (darker = deeper)", "color": (40, 90, 170)}
        ]

    def biomes(self, path):
        habitat = self.atlas.layer("habitat.u8", np.uint8)
        names = self.atlas.meta["habitats"]
        palette = np.array([BIOME_COLORS.get(n, hashed_color(n)) for n in names], np.float32)
        save(palette[habitat] * (0.6 + 0.5 * self.shade), path)
        return [
            {
                "label": n,
                "color": tuple(int(v) for v in palette[i]),
                "pct": self.coverage(habitat == i),
            }
            for i, n in enumerate(names)
            if (habitat == i).any()
        ]

    def climate(self, path):
        region = self.atlas.layer("region.u8", np.uint8)
        temps = {"FROZEN": 0.62, "COOL": 0.52, "MILD": 0.33, "WARM": 0.15, "HOT": 0.0}
        moist = {"DRY": (0.72, 0.35), "MODERATE": (0.5, 0.6), "WET": (0.32, 0.85)}
        colors: list[Color] = []
        for n in self.atlas.meta["regions"]:
            t, m = n.split("_")
            light, sat = moist[m]
            r, g, b = colorsys.hls_to_rgb(temps[t], light, sat)
            colors.append((int(r * 255), int(g * 255), int(b * 255)))
        pal = np.array(colors, np.float32)
        save(np.where(self.wet[..., None], self.base, pal[region] * (0.6 + 0.5 * self.shade)), path)
        dry = ~self.wet
        return [
            {"label": n, "color": colors[i], "pct": self.coverage((region == i) & dry)}
            for i, n in enumerate(self.atlas.meta["regions"])
            if ((region == i) & dry).any()
        ]

    def water_bodies(self, path):
        kind = self.atlas.layer("water_kind.u8", np.uint8)
        img = self.grey.copy()
        supplied = (self.flags & 2) != 0
        img[self.marine & supplied] = (25, 45, 110)
        img[self.marine & ~supplied] = (200, 60, 60)
        legend = [
            {
                "label": "supplied ocean",
                "color": (25, 45, 110),
                "pct": self.coverage(self.marine & supplied),
            },
            {
                "label": "marine, unsupplied",
                "color": (200, 60, 60),
                "pct": self.coverage(self.marine & ~supplied),
            },
        ]
        for i, n in enumerate(self.atlas.meta["waterKinds"]):
            mask = kind == i
            if n == "NONE" or not mask.any():
                continue
            color = WATER_COLORS.get(n, hashed_color(n))
            img[mask] = color
            legend.append({"label": n.lower(), "color": color, "pct": self.coverage(mask)})
        save(img, path)
        return legend

    def features(self, path):
        winner, family, _, featured = self.atlas.winners(self.threshold)
        img = self.base.copy()
        legend = []
        for k, stage in enumerate(self.atlas.stages):
            labels = ["(no family)"] + self.atlas.stage_meta(stage)["families"]
            for f, label in enumerate(labels):
                mask = featured & (winner == k) & (family == f)
                if not mask.any():
                    continue
                color = family_color(stage, f, len(labels))
                img[mask] = np.array(color) * (0.55 + 0.6 * self.shade[mask])
                legend.append(
                    {
                        "label": f"{stage}: {label.lower()}" if f else stage,
                        "color": color,
                        "pct": self.coverage(mask),
                    }
                )
        save(img, path)
        fp = self.atlas.meta.get("footprint", 1)
        note = f" (max over {fp}x{fp} samples per pixel)" if fp > 1 else ""
        return sorted(legend, key=lambda e: -e["pct"]) + [
            {
                "label": f"land with no stage change >= {self.threshold:g} blocks{note}",
                "color": (160, 160, 160),
            }
        ]

    def stage_delta(self, k, path):
        d = self.atlas.layer(f"delta_{self.atlas.stages[k]}.f32", "<f4")
        land = ~self.marine
        scale = max(4.0, float(np.percentile(np.abs(d[land]), 99.5))) if land.any() else 4.0
        t = np.clip(d / scale, -1, 1)[..., None]
        red, blue = np.array([200, 40, 30]), np.array([30, 80, 210])
        signed = np.where(t > 0, self.grey * (1 - t) + red * t, self.grey * (1 + t) - blue * t)
        save(np.where(self.wet[..., None], self.base * 0.6, signed), path)
        return round(scale, 1), self.land_share(np.abs(d) >= self.threshold)

    def stage_reasons(self, k, path):
        """Categorical map of the stage's own recorded reason; None if it keeps no reason."""
        stage = self.atlas.stages[k]
        names = self.atlas.stage_meta(stage)["reasons"]
        if len(names) <= 1:
            return None
        codes = self.atlas.layer(f"reason_{stage}.u8", np.uint8)
        pal = np.array([(0, 0, 0)] + [reason_color(n) for n in names[1:]], np.float32)
        img = np.where(
            self.marine[..., None], self.base * 0.5, pal[codes] * (0.6 + 0.5 * self.shade)
        )
        save(img, path)
        return [
            {
                "label": names[c],
                "color": reason_color(names[c]),
                "pct": self.land_share(codes == c),
            }
            for c in range(1, len(names))
            if (codes == c).any()
        ]

    def cost(self, path):
        micros = self.atlas.layer("micros.f32", "<f4")
        log = np.log10(np.maximum(micros, 1.0))
        lo, hi = float(np.percentile(log, 1)), float(np.percentile(log, 99.5))
        t = np.clip((log - lo) / max(hi - lo, 1e-6), 0, 1)
        try:
            from matplotlib import colormaps

            rgb = colormaps["magma"](t)[..., :3] * 255
        except ImportError:  # pragma: no cover - matplotlib is in requirements-atlas.txt
            rgb = np.stack([t * 255, t * 120, 60 + t * 40], axis=-1)
        save(rgb, path)
        land = ~self.marine
        return {
            "medianLandMicros": round(float(np.median(micros[land])), 1) if land.any() else None,
            "medianMarineMicros": round(float(np.median(micros[self.marine])), 1)
            if self.marine.any()
            else None,
            "p99Micros": round(float(np.percentile(micros, 99)), 1),
            "scale": [round(10**lo, 1), round(10**hi, 1)],
        }

    def sites(self, path, scale=2):
        """Site anchors over the feature map: filled = changed terrain at its anchor."""
        sites = self.atlas.sites
        if sites is None:
            return None
        meta = self.atlas.meta
        img = Image.open(path.with_name("features.png")).convert("RGB")
        img = img.resize((img.width * scale, img.height * scale), Image.Resampling.NEAREST)
        draw = ImageDraw.Draw(img, "RGBA")
        k = scale / meta["step"]
        lanes: dict[str, dict] = {}
        for site in sites:
            lane = site["lane"]
            row = lanes.setdefault(
                lane,
                {"lane": lane, "raw": 0, "vetoed": 0, "marine": 0, "built": 0, "rejected": {}},
            )
            row["raw"] += 1
            x, z = (site["x"] - meta["minX"]) * k, (site["z"] - meta["minZ"]) * k
            r = max(2.0, site["radius"] * k)
            color = stage_color(LANE_STAGE[lane])
            if not site["admitted"]:
                row["vetoed"] += 1
                draw.line((x - 3, z - 3, x + 3, z + 3), fill=(220, 220, 220, 160))
                draw.line((x - 3, z + 3, x + 3, z - 3), fill=(220, 220, 220, 160))
                continue
            if site.get("marine"):
                row["marine"] += 1
                draw.ellipse((x - r, z - r, x + r, z + r), outline=(*color, 90))
                continue
            built = abs(site.get("deltaAtAnchor", 0)) >= self.threshold
            if built:
                row["built"] += 1
                draw.ellipse((x - r, z - r, x + r, z + r), outline=(*color, 255), width=2)
                draw.ellipse((x - 4, z - 4, x + 4, z + 4), fill=(*color, 255))
            else:
                why = site.get("reasonAtAnchor", "no change at anchor")
                row["rejected"][why] = row["rejected"].get(why, 0) + 1
                draw.ellipse((x - r, z - r, x + r, z + r), outline=(230, 60, 60, 220), width=2)
                draw.ellipse((x - 3, z - 3, x + 3, z + 3), fill=(230, 60, 60, 255))
        img.save(path, optimize=True)
        return sorted(lanes.values(), key=lambda r: r["lane"])


def paint(atlas: Atlas, out: Path, threshold: float):
    """All maps, summary.json and index.html for one render directory."""
    p = Painter(atlas, threshold)
    stages_dir = out / "stages"
    stages_dir.mkdir(exist_ok=True)
    legends = {
        "features": p.features(out / "features.png"),
        "elevation": p.elevation(out / "elevation.png"),
        "biomes": p.biomes(out / "biomes.png"),
        "climate": p.climate(out / "climate.png"),
        "water": p.water_bodies(out / "water.png"),
    }
    stages = []
    for k, name in enumerate(atlas.stages):
        scale, active = p.stage_delta(k, stages_dir / f"{name}.png")
        reasons = p.stage_reasons(k, stages_dir / f"{name}_reasons.png")
        stages.append(
            {
                "name": name,
                "activeLandPct": active,
                "deltaScale": scale,
                "reasons": reasons,
                "landReasons": atlas.stage_meta(name).get("landReasons", {}),
            }
        )
    cost = p.cost(out / "cost.png")
    sites = p.sites(out / "sites.png")
    meta = atlas.meta
    summary = {
        "seed": meta["seed"],
        "terrainVersion": meta["terrainVersion"],
        "digest": meta.get("digest"),
        "bounds": {
            "minX": meta["minX"],
            "minZ": meta["minZ"],
            "maxX": meta["minX"] + meta["width"] * meta["step"],
            "maxZ": meta["minZ"] + meta["height"] * meta["step"],
            "step": meta["step"],
            "footprint": meta.get("footprint", 1),
        },
        "seconds": meta["seconds"],
        "threshold": threshold,
        "landPct": p.coverage(~p.marine),
        "dryAboveWaterPct": p.coverage(~p.wet & (p.surface > meta["waterTopY"])),
        "surfaceY": {
            "min": int(p.surface.min()),
            "p50Land": int(np.median(p.surface[~p.marine])) if (~p.marine).any() else None,
            "max": int(p.surface.max()),
        },
        "stages": stages,
        "sites": sites,
        "cost": cost,
        "fingerprints": meta.get("fingerprints", {}),
        "legends": legends,
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    write_index(out, summary)
    return summary


STYLE = (
    "<style>body{font:14px system-ui;background:#111;color:#ddd;margin:16px}"
    "section{display:flex;gap:16px;flex-wrap:wrap;margin:0 0 28px}"
    "img{max-width:min(900px,100%);image-rendering:pixelated;border:1px solid #333}"
    "ul{list-style:none;padding:0;margin:0;columns:2;min-width:320px}"
    "li{margin:2px 0;break-inside:avoid}a{color:#8cf}"
    "i{display:inline-block;width:12px;height:12px;margin-right:6px;vertical-align:-1px}"
    ".pct{color:#888}h2{margin:8px 0}table{border-collapse:collapse;margin:8px 0 24px}"
    "td,th{border:1px solid #333;padding:3px 8px;text-align:right}th{background:#222}"
    "td:first-child,th:first-child{text-align:left}"
    ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px}"
    ".grid img{width:100%}</style>"
)


def swatch(entry):
    r, g, b = entry.get("color") or (128, 128, 128)
    pct = f" <span class=pct>{entry['pct']}%</span>" if "pct" in entry else ""
    return f"<li><i style='background:rgb({r},{g},{b})'></i>{html.escape(entry['label'])}{pct}</li>"


def write_index(out: Path, summary):
    b = summary["bounds"]
    title = f"Riven seed {summary['seed']} - terrain-v{summary['terrainVersion']}"
    parts = [
        f"<!doctype html><meta charset=utf-8><title>{html.escape(title)}</title>{STYLE}",
        f"<h1>{html.escape(title)}</h1>",
        f"<p>x [{b['minX']}, {b['maxX']}) z [{b['minZ']}, {b['maxZ']}), {b['step']} blocks/pixel,"
        f" north up. Land {summary['landPct']}%, dry above water {summary['dryAboveWaterPct']}%."
        f" Sampling {summary['seconds']['sampling']} s. Digest <code>"
        f"{(summary.get('digest') or '')[:16]}</code>.</p>",
    ]
    if (out / "viewer.html").exists():
        parts.append("<p><a href='viewer.html'>Open the interactive viewer</a></p>")
    for key, label in [
        ("features", "Selected features (largest stage change per column)"),
        ("elevation", "Elevation"),
        ("biomes", "Final biomes"),
        ("climate", "Climate regions"),
        ("water", "Water bodies and marine supply"),
    ]:
        items = "".join(swatch(e) for e in summary["legends"][key])
        parts.append(
            f"<h2>{label}</h2><section><img src='{key}.png' alt='{key}'><ul>{items}</ul></section>"
        )
    if summary["sites"] is not None:
        rows = "".join(
            f"<tr><td>{s['lane'].lower()}</td><td>{s['raw']}</td><td>{s['vetoed']}</td>"
            f"<td>{s['marine']}</td><td>{s['built']}</td><td>"
            + html.escape(", ".join(f"{k} {v}" for k, v in sorted(s["rejected"].items())))
            + "</td></tr>"
            for s in summary["sites"]
        )
        parts.append(
            "<h2>Major-feature sites</h2><p>Every raw <code>Sites</code> anchor near the window."
            " Grey x: vetoed by a higher-priority neighbour. Faint ring: admitted but marine."
            " Filled lane colour: changed terrain at its anchor. Red: admitted on land, no change;"
            " the reason is the stage's own field at the anchor.</p>"
            "<section><img src='sites.png' alt='sites'><table><tr><th>lane</th><th>raw</th>"
            f"<th>vetoed</th><th>marine</th><th>built</th><th>land rejections</th></tr>{rows}"
            "</table></section>"
        )
    c = summary["cost"]
    parts.append(
        "<h2>Sampling cost per column</h2><p>Wall time of one <code>TerrainMap.sample</code>"
        f" (log scale {c['scale'][0]}-{c['scale'][1]} us). Median land {c['medianLandMicros']} us,"
        f" marine {c['medianMarineMicros']} us. Includes cold plan construction for whichever"
        " column touched an owner first; not deterministic and not part of the digest.</p>"
        "<section><img src='cost.png' alt='cost'></section>"
    )
    parts.append("<h2>Stage height changes (red raises, blue lowers)</h2><div class=grid>")
    for s in summary["stages"]:
        parts.append(
            f"<figure><img src='stages/{s['name']}.png' alt='{s['name']}'>"
            f"<figcaption>{s['name']}: changes {s['activeLandPct']}% of land,"
            f" full colour at +/-{s['deltaScale']} blocks</figcaption></figure>"
        )
    parts.append("</div><h2>Where each stage was accepted or rejected (land)</h2><div class=grid>")
    for s in summary["stages"]:
        if not s["reasons"]:
            continue
        items = "".join(swatch(e) for e in sorted(s["reasons"], key=lambda e: -e["pct"]))
        parts.append(
            f"<figure><img src='stages/{s['name']}_reasons.png' alt='{s['name']} reasons'>"
            f"<figcaption><b>{s['name']}</b><ul style='columns:1'>{items}</ul>"
            "</figcaption></figure>"
        )
    parts.append("</div>")
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8")
