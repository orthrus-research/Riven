"""Cut one atlas render into the map site's tile pyramid; tools/atlas/map is the page that reads it.

Every field is quantized to a little-endian integer. A tile is TILE x TILE pixels plus an APRON of
its neighbours' pixels (the page needs them for hillshading and borders) and is written as two
zlib streams: the core bundle every projection reads, and the stage bundle (each stage's change
and reason) that the page loads only for stage projections and the inspector. Within a bundle the
fields follow the manifest's order, two-byte fields first so the page can view them in place.
Level L takes every 2**L-th sample of the render, so each level is exactly what a coarser atlas
render samples there; nothing is averaged.
"""

import json
import shutil
import time
import zlib
from pathlib import Path

import numpy as np

from atlas.paint import (
    BIOME_COLORS,
    ELEVATION_STOPS,
    WATER_COLORS,
    Atlas,
    family_color,
    hashed_color,
    reason_color,
    region_color,
)

TILE = 256
APRON = 1
# Bump when the tile bytes change: the page puts it in tile URLs, which are cached as immutable.
TILE_FORMAT = 1
DELTA_UNITS = 16  # stage changes are kept in sixteenths of a block
COAST_COLORS = {
    "cliff": (95, 95, 105),
    "headland": (150, 110, 170),
    "bluff": (175, 125, 70),
    "platform": (110, 160, 185),
    "beach": (235, 215, 140),
    "marsh": (85, 150, 90),
}
LEVEL_COLORS = {
    "OCEAN": (30, 50, 110),
    "LOW": (110, 160, 80),
    "MIDDLE": (190, 170, 100),
    "HIGH": (225, 225, 225),
}
RANGES = {"i16": (-32768, 32767), "u16": (0, 65535), "u8": (0, 255)}
DTYPES = {"i16": "<i2", "u16": "<u2", "u8": "u1"}
CORE_BYTES = (
    "flags",
    "habitat",
    "region",
    "level",
    "character",
    "water_kind",
    "province_type",
    "coast",
    "river",
    "mouth",
    "channel_beyond",
)


class Field:
    """One map field: the raw layer it reads (None when derived) and its quantization."""

    def __init__(self, name, kind, raw=None, raw_dtype=None, scale=1):
        self.name, self.kind, self.raw, self.raw_dtype, self.scale = (
            name,
            kind,
            raw,
            raw_dtype,
            scale,
        )


def bundles(atlas: Atlas) -> dict[str, list[Field]]:
    """Each bundle's fields in byte order. Layers an older render lacks are left out."""
    fp = "fp_" if atlas.has(f"fp_delta_{atlas.stages[0]}.f32") else ""
    core = [
        Field("surface", "i16", "surface.i16", "<i2"),
        Field("water", "i16", "water.i16", "<i2"),
        Field("celsius", "i16", "celsius.f32", "<f4", 100),
        Field("moisture", "i16", "moisture.f32", "<f4", 10000),
        Field("plate", "u16", "plate.u16", "<u2"),
        Field("province", "u16", "province.u16", "<u2"),
        Field("water_body", "u16", "water_body.u16", "<u2"),
    ]
    core += [Field(n, "u8", f"{n}.u8", "u1") for n in CORE_BYTES]
    core += [Field("winner", "u8"), Field("feature", "u8")]
    stages = [
        Field(f"delta_{s}", "i16", f"{fp}delta_{s}.f32", "<f4", DELTA_UNITS) for s in atlas.stages
    ]
    stages += [Field(f"reason_{s}", "u8", f"reason_{s}.u8", "u1") for s in atlas.stages]
    keep = {"core": core, "stages": stages}
    return {k: [f for f in v if f.raw is None or atlas.has(f.raw)] for k, v in keep.items()}


def legend(names, color, counts, total):
    return {
        "names": list(names),
        "colors": [list(color(n)) for n in names],
        "shares": [round(100.0 * float(c) / max(1, total), 3) for c in counts],
    }


class Exporter:
    def __init__(self, atlas: Atlas, threshold: float):
        self.atlas, self.threshold = atlas, threshold
        self.bundles = bundles(atlas)
        fp = "fp_" if atlas.has(f"fp_delta_{atlas.stages[0]}.f32") else ""

        def layer(name, dtype):
            return np.memmap(atlas.raw / name, dtype=dtype, mode="r", shape=atlas.shape)

        self.read = [f for fields in self.bundles.values() for f in fields if f.raw is not None]
        self.maps = {f.name: layer(f.raw, f.raw_dtype) for f in self.read}
        # The feature pair ranks the same layers as Atlas.winners: footprint maxima when present.
        self.deltas = [layer(f"{fp}delta_{s}.f32", "<f4") for s in atlas.stages]
        self.families = [layer(f"{fp}family_{s}.u8", "u1") for s in atlas.stages]
        self.counts: dict[str, np.ndarray] = {}
        self.marine = 0

    def window(self, rows, cols):
        """Every quantized field and the derived feature pair at full-resolution indices."""
        ix = np.ix_(rows, cols)
        data = {}
        for field in self.read:
            v = self.maps[field.name][ix]
            if field.scale != 1:
                v = np.round(v.astype(np.float64) * field.scale)
            lo, hi = RANGES[field.kind]
            data[field.name] = np.clip(v, lo, hi).astype(DTYPES[field.kind])
        mags = np.abs(np.stack([d[ix] for d in self.deltas]))
        winner = mags.argmax(axis=0)
        strength = np.take_along_axis(mags, winner[None], 0)[0]
        family = np.take_along_axis(np.stack([f[ix] for f in self.families]), winner[None], 0)[0]
        featured = (strength >= self.threshold) & ((data["flags"] & 1) == 0)
        data["winner"] = np.where(featured, winner + 1, 0).astype("u1")
        data["feature"] = np.where(featured, family, 0).astype("u1")
        return data

    def tally(self, data, inner):
        """Category counts over a level-0 tile's own pixels; land-only counts are prefixed."""
        land = (data["flags"][inner] & 1) == 0
        self.marine += int((~land).sum())
        for name in ("habitat", "region", "level", "character", "water_kind", "coast", "plate"):
            if name in data:
                self.add(name, data[name][inner])
        if "province_type" in data:
            self.add("land:province_type", data["province_type"][inner][land])
        self.add("features", data["winner"][inner].astype(np.int32) * 256 + data["feature"][inner])
        for s in self.atlas.stages:
            self.add(f"land:reason_{s}", data[f"reason_{s}"][inner][land])
            moved = np.abs(data[f"delta_{s}"][inner][land]) >= self.threshold * DELTA_UNITS
            self.add(f"land:active_{s}", moved.astype(np.int32))

    def add(self, name, values):
        counts = np.bincount(values.ravel().astype(np.int64))
        old = self.counts.get(name, np.zeros(0, np.int64))
        n = max(len(old), len(counts))
        self.counts[name] = np.pad(old, (0, n - len(old))) + np.pad(counts, (0, n - len(counts)))

    def count(self, name, n):
        c = self.counts.get(name, np.zeros(0, np.int64))
        return np.pad(c, (0, max(0, n - len(c))))[:n]

    def export(self, out: Path):
        """Write every level's tiles under out; returns (levels, file count, bytes)."""
        height, width = self.atlas.shape
        levels, files, size, level = [], 0, 0, 0
        while True:
            f = 1 << level
            w, h = -(-width // f), -(-height // f)
            cols_n, rows_n = -(-w // TILE), -(-h // TILE)
            levels.append({"width": w, "height": h, "cols": cols_n, "rows": rows_n})
            for tz in range(rows_n):
                rows = np.clip(np.arange(tz * TILE - APRON, (tz + 1) * TILE + APRON), 0, h - 1)
                for tx in range(cols_n):
                    cols = np.arange(tx * TILE - APRON, (tx + 1) * TILE + APRON)
                    data = self.window(rows * f, np.clip(cols, 0, w - 1) * f)
                    if level == 0:
                        self.tally(
                            data,
                            (
                                slice(APRON, APRON + min(TILE, h - tz * TILE)),
                                slice(APRON, APRON + min(TILE, w - tx * TILE)),
                            ),
                        )
                    for bundle, fields in self.bundles.items():
                        raw = b"".join(data[field.name].tobytes() for field in fields)
                        blob = zlib.compress(raw, 6)
                        path = out / str(level) / f"{tx}_{tz}.{bundle}.rvt"
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(blob)
                        files += 1
                        size += len(blob)
            if cols_n == 1 and rows_n == 1:
                return levels, files, size
            level += 1

    def delta_scale(self, k):
        """Full colour at the 99.5th percentile of the stage's change on land, at least 4 blocks."""
        h, w = self.atlas.shape
        step = max(1, int((h * w / 4e6) ** 0.5))
        d = np.asarray(self.deltas[k][::step, ::step])
        land = (np.asarray(self.maps["flags"][::step, ::step]) & 1) == 0
        if not land.any():
            return 4.0
        return round(max(4.0, float(np.percentile(np.abs(d[land]), 99.5))), 1)

    def manifest(self, render_id, levels, extra):
        meta, atlas = self.atlas.meta, self.atlas
        total = atlas.shape[0] * atlas.shape[1]
        land = total - self.marine

        def named(field, names, color, denominator=total, key=None):
            return legend(names, color, self.count(key or field, len(names)), denominator)

        legends = {
            "habitat": named(
                "habitat", meta["habitats"], lambda n: BIOME_COLORS.get(n, hashed_color(n))
            ),
            "region": named("region", meta["regions"], region_color),
            "level": named("level", meta["levels"], lambda n: LEVEL_COLORS.get(n, hashed_color(n))),
            "character": named("character", meta["characters"], hashed_color),
            "water_kind": named(
                "water_kind", meta["waterKinds"], lambda n: WATER_COLORS.get(n, hashed_color(n))
            ),
        }
        if "coastTypes" in meta:
            legends["coast"] = named(
                "coast", meta["coastTypes"], lambda n: COAST_COLORS.get(n, hashed_color(n))
            )
            legends["province_type"] = named(
                "province_type", meta["provinceTypes"], hashed_color, land, "land:province_type"
            )
            legends["plate"] = named("plate", meta["plates"], lambda n: hashed_color("plate" + n))
        features = self.counts["features"]
        stages = []
        for k, name in enumerate(atlas.stages):
            sm = atlas.stage_meta(name)
            labels = ["(no family)"] + sm["families"]
            reasons = sm["reasons"]
            moved = self.count(f"land:active_{name}", 2)[1]
            stages.append(
                {
                    "name": name,
                    "families": labels,
                    "familyColors": [
                        list(family_color(name, f, len(labels))) for f in range(len(labels))
                    ],
                    "featureShares": [
                        round(100.0 * float(features[c]) / total, 3) if c < len(features) else 0.0
                        for c in ((k + 1) * 256 + f for f in range(len(labels)))
                    ],
                    "reasons": reasons,
                    "reasonColors": [[0, 0, 0]] + [list(reason_color(n)) for n in reasons[1:]],
                    "reasonShares": legend(
                        reasons,
                        lambda n: (0, 0, 0),
                        self.count(f"land:reason_{name}", len(reasons)),
                        land,
                    )["shares"],
                    "deltaScale": self.delta_scale(k),
                    "activeLandPct": round(100.0 * float(moved) / max(1, land), 2),
                }
            )
        fields = {
            bundle: [{"name": f.name, "type": f.kind, "scale": f.scale} for f in b]
            for bundle, b in self.bundles.items()
        }
        return {
            "id": render_id,
            "seed": meta["seed"],
            "terrainVersion": meta["terrainVersion"],
            "digest": meta.get("digest"),
            "minX": meta["minX"],
            "minZ": meta["minZ"],
            "step": meta["step"],
            "width": meta["width"],
            "height": meta["height"],
            "footprint": meta.get("footprint", 1),
            "waterTopY": meta["waterTopY"],
            "seaLevel": meta["seaLevel"],
            "threshold": self.threshold,
            "deltaUnits": DELTA_UNITS,
            "format": TILE_FORMAT,
            "tile": TILE,
            "apron": APRON,
            "levels": levels,
            "bundles": fields,
            "flags": meta["flags"],
            "riverFlags": meta.get("riverFlags", {}),
            "elevationStops": [[rise, list(color)] for rise, color in ELEVATION_STOPS],
            "legends": legends,
            "plates": meta.get("plates", []),
            "provinceCells": meta.get("provinceCells", []),
            "stages": stages,
            "landPct": round(100.0 * land / total, 3),
            **extra,
        }


def render_id(meta):
    size = meta["width"] * meta["step"]
    cx, cz = meta["minX"] + size // 2, meta["minZ"] + meta["height"] * meta["step"] // 2
    digest = (meta.get("digest") or "nodigest")[:8]
    return f"{meta['seed']}-v{meta['terrainVersion']}-{cx}_{cz}-{size}-s{meta['step']}-{digest}"


def publish(atlas: Atlas, site: Path, threshold: float, note: str | None = None):
    """Export one render into site/renders/<id>/ and list it in site/renders/index.json."""
    meta = atlas.meta
    rid = render_id(meta)
    out = site / "renders" / rid
    if out.exists():
        shutil.rmtree(out)
    started = time.monotonic()
    exporter = Exporter(atlas, threshold)
    levels, files, size = exporter.export(out)
    created = time.strftime("%Y-%m-%d %H:%M", time.gmtime())
    manifest = exporter.manifest(rid, levels, {"created": created, "note": note or ""})
    (out / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    index_path = site / "renders" / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else []
    index = [e for e in index if e["id"] != rid]
    index.append(
        {
            "id": rid,
            "seed": meta["seed"],
            "terrainVersion": meta["terrainVersion"],
            "centerX": meta["minX"] + meta["width"] * meta["step"] // 2,
            "centerZ": meta["minZ"] + meta["height"] * meta["step"] // 2,
            "size": meta["width"] * meta["step"],
            "step": meta["step"],
            "landPct": manifest["landPct"],
            "created": created,
            "note": note or "",
        }
    )
    index.sort(key=lambda e: (e["created"], e["id"]), reverse=True)
    index_path.write_text(json.dumps(index, indent=1), encoding="utf-8")
    return rid, files, size, round(time.monotonic() - started, 1)


def remove(site: Path, rid: str):
    """Drop one render from the site and its catalogue."""
    index_path = site / "renders" / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else []
    if not any(e["id"] == rid for e in index):
        raise SystemExit(f"no render {rid} in {index_path}")
    kept = [e for e in index if e["id"] != rid]
    index_path.write_text(json.dumps(kept, indent=1), encoding="utf-8")
    shutil.rmtree(site / "renders" / rid, ignore_errors=True)
