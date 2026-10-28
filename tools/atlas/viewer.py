"""Self-contained interactive viewer: layer switching, pan/zoom and a per-pixel inspector.

Every value shown on hover comes from the raw sampler layers, packed losslessly into RGB PNGs
(no alpha, so browsers cannot premultiply them) and decoded in the page.
"""

import base64
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image

from atlas.paint import LANE_STAGE, Atlas, stage_color


def _png(path_or_array) -> str:
    buffer = io.BytesIO()
    if isinstance(path_or_array, Path):
        Image.open(path_or_array).convert("RGB").save(buffer, "PNG", optimize=True)
    else:
        Image.fromarray(path_or_array.astype(np.uint8), "RGB").save(buffer, "PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()


def _pack16(values):
    v = (values.astype(np.int32) + 32768).astype(np.uint32)
    return (v >> 8) & 0xFF, v & 0xFF


def write_viewer(atlas: Atlas, out: Path, threshold: float):
    meta = atlas.meta
    surface = atlas.layer("surface.i16", "<i2")
    water = atlas.layer("water.i16", "<i2")
    s_hi, s_lo = _pack16(surface)
    w_hi, w_lo = _pack16(water)
    winner, family, _, featured = atlas.winners(threshold)
    data = {
        "d0": np.stack([s_hi, s_lo, atlas.layer("habitat.u8", np.uint8)], axis=-1),
        "d1": np.stack([w_hi, w_lo, atlas.layer("region.u8", np.uint8)], axis=-1),
        "d2": np.stack(
            [
                atlas.layer("flags.u8", np.uint8),
                atlas.layer("water_kind.u8", np.uint8),
                atlas.layer("level.u8", np.uint8),
            ],
            axis=-1,
        ),
        "d3": np.stack(
            [
                atlas.layer("character.u8", np.uint8),
                np.where(featured, winner + 1, 0),
                np.where(featured, family, 0),
            ],
            axis=-1,
        ),
    }
    climate = np.stack(
        [
            np.clip(np.round(atlas.layer("celsius.f32", "<f4") * 2) + 128, 0, 255),
            np.clip(np.round(atlas.layer("moisture.f32", "<f4") * 255), 0, 255),
            np.clip(
                np.round(np.log10(np.maximum(atlas.layer("micros.f32", "<f4"), 1)) * 40), 0, 255
            ),
        ],
        axis=-1,
    )
    data["d4"] = climate
    for stage in atlas.stages:
        d = np.clip(np.round(atlas.layer(f"delta_{stage}.f32", "<f4")), -127, 127) + 128
        data["s_" + stage] = np.stack(
            [
                atlas.layer(f"family_{stage}.u8", np.uint8),
                atlas.layer(f"reason_{stage}.u8", np.uint8),
                d,
            ],
            axis=-1,
        )
    layers = [
        ("Features", out / "features.png"),
        ("Elevation", out / "elevation.png"),
        ("Biomes", out / "biomes.png"),
        ("Climate", out / "climate.png"),
        ("Water", out / "water.png"),
        ("Sampling cost", out / "cost.png"),
    ]
    for stage in atlas.stages:
        layers.append((f"{stage}: height change", out / "stages" / f"{stage}.png"))
        reasons = out / "stages" / f"{stage}_reasons.png"
        if reasons.exists():
            layers.append((f"{stage}: reasons", reasons))
    payload = {
        "meta": {
            k: meta[k]
            for k in (
                "seed",
                "terrainVersion",
                "minX",
                "minZ",
                "step",
                "width",
                "height",
                "habitats",
                "regions",
                "levels",
                "characters",
                "waterKinds",
                "digest",
            )
        },
        "stages": [
            {
                "name": s["name"],
                "families": s["families"],
                "reasons": s["reasons"],
                "color": stage_color(s["name"]),
            }
            for s in meta["stages"]
        ],
        "sites": atlas.sites or [],
        "laneStage": LANE_STAGE,
        "threshold": threshold,
        "layers": [{"name": n, "src": _png(p)} for n, p in layers if p.exists()],
        "data": {k: _png(v) for k, v in data.items()},
    }
    page = TEMPLATE.replace("__PAYLOAD__", json.dumps(payload, separators=(",", ":")))
    (out / "viewer.html").write_text(page, encoding="utf-8")


TEMPLATE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Riven atlas viewer</title>
<style>
:root{--bg:#111;--panel:#1b1b1b;--fg:#ddd;--muted:#888;--line:#333;--accent:#8cf}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:13px system-ui;display:grid;
 grid-template-columns:300px 1fr;height:100vh;overflow:hidden}
aside{background:var(--panel);border-right:1px solid var(--line);padding:12px;overflow:auto}
main{position:relative;overflow:hidden;cursor:crosshair}
canvas{position:absolute;inset:0;width:100%;height:100%}
h1{font-size:15px;margin:0 0 4px}
.muted{color:var(--muted)}
select,input{width:100%;margin:4px 0 10px;background:#222;color:var(--fg);border:1px solid var(--line);
 padding:4px}
label.row{display:flex;gap:6px;align-items:center;margin:4px 0}
label.row input{width:auto;margin:0}
table{border-collapse:collapse;width:100%;margin-top:6px}
td{border-top:1px solid var(--line);padding:2px 4px;vertical-align:top}
td:first-child{color:var(--muted);white-space:nowrap}
.pin{color:var(--accent)}
button{background:#222;color:var(--fg);border:1px solid var(--line);padding:3px 8px;cursor:pointer}
@media (max-width:700px){body{grid-template-columns:1fr;grid-template-rows:45vh 1fr}
 aside{order:2}}
</style></head><body>
<aside>
<h1 id="title"></h1><div class="muted" id="sub"></div>
<label>Layer<select id="layer"></select></label>
<label class="row"><input type="checkbox" id="sites" checked> Major-feature sites</label>
<label class="row"><input type="checkbox" id="vetoed"> Vetoed site anchors</label>
<div class="muted">Wheel to zoom, drag to pan, click to pin.
 Keys 1-6 switch the main layers.</div>
<table id="info"></table>
<p><button id="copy" disabled>Copy /tp for pinned column</button></p>
</aside>
<main id="main"><canvas id="view"></canvas></main>
<script>
const P = __PAYLOAD__;
const M = P.meta, W = M.width, H = M.height;
const $ = id => document.getElementById(id);
$("title").textContent = `Seed ${M.seed} - terrain-v${M.terrainVersion}`;
$("sub").textContent = `x [${M.minX}, ${M.minX + W * M.step}) z [${M.minZ}, ` +
  `${M.minZ + H * M.step}) - ${M.step} blocks/px - digest ${(M.digest || "").slice(0, 12)}`;

function load(src) {
  return new Promise(ok => { const i = new Image(); i.onload = () => ok(i); i.src = src; });
}
function pixels(img) {
  const c = document.createElement("canvas"); c.width = W; c.height = H;
  const x = c.getContext("2d"); x.drawImage(img, 0, 0);
  return x.getImageData(0, 0, W, H).data;
}
const layers = [], data = {};
let view = {scale: 1, x: 0, y: 0}, hover = null, pin = null;
const canvas = $("view"), ctx = canvas.getContext("2d");

function fit() {
  const r = $("main").getBoundingClientRect();
  canvas.width = r.width * devicePixelRatio; canvas.height = r.height * devicePixelRatio;
  view.scale = Math.min(canvas.width / W, canvas.height / H);
  view.x = (canvas.width - W * view.scale) / 2; view.y = (canvas.height - H * view.scale) / 2;
}
function draw() {
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.fillStyle = "#111"; ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.setTransform(view.scale, 0, 0, view.scale, view.x, view.y);
  ctx.imageSmoothingEnabled = false;
  const layer = layers[$("layer").selectedIndex];
  if (layer) ctx.drawImage(layer.img, 0, 0);
  const k = 1 / M.step, lw = 1.5 / view.scale;
  if ($("sites").checked || $("vetoed").checked) for (const s of P.sites) {
    const x = (s.x - M.minX) * k, z = (s.z - M.minZ) * k, r = Math.max(1, s.radius * k);
    const c = P.stages.find(t => t.name === P.laneStage[s.lane]).color;
    ctx.lineWidth = lw;
    if (!s.admitted) {
      if (!$("vetoed").checked) continue;
      ctx.strokeStyle = "rgba(220,220,220,.6)";
      ctx.beginPath(); ctx.moveTo(x - 3 * lw, z - 3 * lw); ctx.lineTo(x + 3 * lw, z + 3 * lw);
      ctx.moveTo(x - 3 * lw, z + 3 * lw); ctx.lineTo(x + 3 * lw, z - 3 * lw); ctx.stroke();
      continue;
    }
    if (!$("sites").checked) continue;
    const built = !s.marine && Math.abs(s.deltaAtAnchor || 0) >= P.threshold;
    ctx.strokeStyle = s.marine ? `rgba(${c},.35)` : built ? `rgb(${c})` : "rgb(230,60,60)";
    ctx.beginPath(); ctx.arc(x, z, r, 0, 7); ctx.stroke();
    ctx.fillStyle = ctx.strokeStyle;
    ctx.beginPath(); ctx.arc(x, z, 2.5 * lw, 0, 7); ctx.fill();
  }
  for (const [p, color] of [[hover, "#fff"], [pin, "#8cf"]]) if (p) {
    ctx.strokeStyle = color; ctx.lineWidth = lw; ctx.strokeRect(p.c, p.r, 1, 1);
  }
}
function cell(ev) {
  const r = canvas.getBoundingClientRect();
  const px = (ev.clientX - r.left) * devicePixelRatio, py = (ev.clientY - r.top) * devicePixelRatio;
  const c = Math.floor((px - view.x) / view.scale), row = Math.floor((py - view.y) / view.scale);
  return c >= 0 && row >= 0 && c < W && row < H ? {c, r: row} : null;
}
const u16 = (a, i) => a[i] * 256 + a[i + 1] - 32768;
function describe(p) {
  const i = (p.r * W + p.c) * 4, d0 = data.d0, d1 = data.d1, d2 = data.d2, d3 = data.d3;
  const x = M.minX + p.c * M.step, z = M.minZ + p.r * M.step;
  const surface = u16(d0, i), water = u16(d1, i), flags = d2[i];
  const rows = [
    ["block", `${x}, ${z}`],
    ["surface Y", surface],
    ["water Y", water === -32768 ? "none" : water],
    ["biome", M.habitats[d0[i + 2]]],
    ["climate", M.regions[d1[i + 2]] + ` (${((data.d4[i] - 128) / 2).toFixed(1)} C, ` +
      `moisture ${(data.d4[i + 1] / 255).toFixed(2)})`],
    ["ownership", (flags & 1 ? "marine" : "land") + (flags & 2 ? ", supplied" : "") +
      (flags & 4 ? ", deep" : "") + (flags & 8 ? ", wet" : "")],
    ["broad intent", `${M.levels[d2[i + 2]]} / ${M.characters[d3[i]]}`],
    ["hydro", M.waterKinds[d2[i + 1]]],
    ["sample cost", `~${Math.round(Math.pow(10, data.d4[i + 2] / 40))} us`],
  ];
  if (d3[i + 1]) {
    const s = P.stages[d3[i + 1] - 1];
    rows.push(["largest change", s.name + (d3[i + 2] ? ": " + s.families[d3[i + 2] - 1] : "")]);
  }
  for (const s of P.stages) {
    const a = data["s_" + s.name], fam = a[i], why = a[i + 1], dh = a[i + 2] - 128;
    const parts = [];
    if (dh) parts.push((dh > 0 ? "+" : "") + dh);
    if (fam) parts.push(s.families[fam - 1].toLowerCase());
    if (why) parts.push(s.reasons[why]);
    if (parts.length) rows.push([s.name, parts.join(" - ")]);
  }
  return {rows, x, z, surface};
}
function show(p) {
  const target = pin || p; if (!target) return;
  const d = describe(target);
  $("info").innerHTML = d.rows.map(([k, v]) =>
    `<tr><td>${k}</td><td${pin ? ' class="pin"' : ""}>${v}</td></tr>`).join("");
}
canvas.addEventListener("wheel", ev => {
  ev.preventDefault();
  const r = canvas.getBoundingClientRect();
  const px = (ev.clientX - r.left) * devicePixelRatio, py = (ev.clientY - r.top) * devicePixelRatio;
  const f = Math.exp(-ev.deltaY * 0.0015);
  view.x = px - (px - view.x) * f; view.y = py - (py - view.y) * f; view.scale *= f; draw();
}, {passive: false});
let drag = null;
canvas.addEventListener("mousedown", ev => { drag = {x: ev.clientX, y: ev.clientY, moved: 0}; });
addEventListener("mouseup", ev => {
  if (drag && drag.moved < 4) {
    const p = cell(ev); pin = pin && p && pin.c === p.c && pin.r === p.r ? null : p;
    $("copy").disabled = !pin; show(hover); draw();
  }
  drag = null;
});
canvas.addEventListener("mousemove", ev => {
  if (drag) {
    const dx = ev.clientX - drag.x, dy = ev.clientY - drag.y;
    drag.moved += Math.abs(dx) + Math.abs(dy); drag.x = ev.clientX; drag.y = ev.clientY;
    view.x += dx * devicePixelRatio; view.y += dy * devicePixelRatio; draw(); return;
  }
  hover = cell(ev); show(hover); draw();
});
$("copy").onclick = () => {
  const d = describe(pin); navigator.clipboard.writeText(`/tp ${d.x} ${d.surface + 2} ${d.z}`);
};
addEventListener("keydown", ev => {
  const n = Number(ev.key); if (n >= 1 && n <= 6) { $("layer").selectedIndex = n - 1; draw(); }
});
for (const id of ["layer", "sites", "vetoed"]) $(id).addEventListener("change", draw);
addEventListener("resize", () => { fit(); draw(); });
(async () => {
  for (const l of P.layers) {
    layers.push({name: l.name, img: await load(l.src)});
    $("layer").add(new Option(l.name));
  }
  for (const [k, src] of Object.entries(P.data)) data[k] = pixels(await load(src));
  fit(); draw();
})();
</script></body></html>
"""
