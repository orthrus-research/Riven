// Riven atlas map: pan, zoom and inspect one atlas render's tile pyramid.
// tools/atlas/tiles.py writes the tiles and manifest; every colour here is a function of the
// sampled fields, so switching projection re-colours loaded tiles without fetching anything.

const $ = (id) => document.getElementById(id);
const canvas = $('map');
const ctx = canvas.getContext('2d');

const MAX_SCALE = 16; // screen pixels per block at the closest zoom
// A decoded tile holds 2-5 MB of fields; phones keep fewer of them.
const CACHE_TILES = window.matchMedia('(max-width: 640px)').matches ? 96 : 192;
const PARALLEL = 6;
const VIEWS = { i16: Int16Array, u16: Uint16Array, u8: Uint8Array };
const BYTES = { i16: 2, u16: 2, u8: 1 };

const state = {
  catalogue: [],
  m: null, // the open render's manifest (state.a.m)
  a: null, // the open render's layer
  b: null, // the compared render's layer: same window and grid as a
  compare: { mode: 'swipe', split: 0.5, showB: false },
  projection: 'relief',
  cx: 0,
  cz: 0,
  scale: 1, // CSS pixels per block
  grid: false,
  inspect: null, // {x, z} of the inspected column
};

// ---------------------------------------------------------------------------------------------
// Colour helpers

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

function ramp(stops, v, out) {
  if (v <= stops[0][0]) return copy(stops[0][1], out);
  for (let s = 1; s < stops.length; s++) {
    if (v <= stops[s][0]) {
      const [a, ca] = stops[s - 1];
      const [b, cb] = stops[s];
      const t = (v - a) / (b - a);
      out[0] = ca[0] + (cb[0] - ca[0]) * t;
      out[1] = ca[1] + (cb[1] - ca[1]) * t;
      out[2] = ca[2] + (cb[2] - ca[2]) * t;
      return out;
    }
  }
  return copy(stops[stops.length - 1][1], out);
}

function copy(c, out) {
  out[0] = c[0];
  out[1] = c[1];
  out[2] = c[2];
  return out;
}

function hsl(h, s, l) {
  const k = (n) => (n + h * 12) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n) => l - a * Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1));
  return [f(0) * 255, f(8) * 255, f(4) * 255];
}

const bodyColors = new Map();
function bodyColor(code) {
  let c = bodyColors.get(code);
  if (!c) {
    c = hsl((code * 0.618034) % 1, 0.75, 0.55);
    bodyColors.set(code, c);
  }
  return c;
}

const TEMPERATURE = [
  [-30, [30, 40, 120]],
  [-15, [60, 110, 210]],
  [0, [225, 235, 245]],
  [15, [240, 190, 110]],
  [30, [210, 70, 40]],
  [40, [120, 20, 30]],
];
const MOISTURE = [
  [0, [165, 120, 70]],
  [0.25, [205, 180, 110]],
  [0.5, [120, 175, 90]],
  [0.75, [50, 140, 120]],
  [1, [30, 70, 160]],
];
const STEEPNESS = [
  [0, [40, 70, 40]],
  [15, [150, 170, 70]],
  [30, [230, 190, 60]],
  [45, [220, 90, 40]],
  [60, [250, 250, 250]],
];
const RIVER_COLORS = {
  ocean: [14, 28, 60],
  river: [0, 140, 255],
  stream: [130, 230, 255],
  still: [50, 90, 160],
  valley: [70, 130, 115],
  bar: [215, 200, 150],
  mouth: [240, 90, 200],
};

// ---------------------------------------------------------------------------------------------
// Tiles

// One published render: its manifest, its projections and the tiles loaded from it. Every layer
// shares the view's block coordinates, so a compared render draws over the same ground.
class Layer {
  constructor(m) {
    this.m = m;
    this.tiles = new Map();
    this.dead = false; // set when the layer leaves the view; its loads are dropped
    this.projections = projections(m);
  }

  top() {
    return this.m.levels.length - 1;
  }

  tileAt(level, tx, tz) {
    const k = `${level}/${tx}/${tz}`;
    let t = this.tiles.get(k);
    if (!t) {
      t = new Tile(this, level, tx, tz);
      this.tiles.set(k, t);
    }
    return t;
  }

  projection(id) {
    return this.projections.find((p) => p.id === id);
  }
}

class Tile {
  constructor(layer, level, tx, tz) {
    Object.assign(this, { layer, level, tx, tz, core: null, stages: null, failed: false });
    this.pending = { core: false, stages: false };
    this.image = null;
    this.imageKey = '';
    this.shade = null;
    this.slope = null;
    this.used = 0;
  }
}

const queue = [];
let inflight = 0;
let frame = 0;
const layers = () => [state.a, state.b].filter(Boolean);

function request(t, bundle) {
  if (t[bundle] || t.pending[bundle] || t.failed) return;
  t.pending[bundle] = true;
  queue.push({ t, bundle });
}

function pump() {
  while (inflight < PARALLEL && queue.length) {
    const { t, bundle } = queue.shift();
    // Tiles the last frame no longer wanted wait until a view asks for them again.
    if (t.layer.dead || t.used < frame - 1) {
      t.pending[bundle] = false;
      continue;
    }
    inflight++;
    load(t, bundle)
      .then((data) => {
        if (t.layer.dead) return;
        t[bundle] = data;
        t.imageKey = '';
        schedule();
        if (state.inspect) showInspector();
      })
      .catch((error) => {
        t.failed = true;
        console.warn(error);
      })
      .finally(() => {
        t.pending[bundle] = false;
        inflight--;
        pump();
      });
  }
}

async function load(t, bundle) {
  const m = t.layer.m;
  const url = `renders/${m.id}/${t.level}/${t.tx}_${t.tz}.${bundle}.rvt?f=${m.format ?? 0}`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${response.status} ${url}`);
  const inflated = response.body.pipeThrough(new DecompressionStream('deflate'));
  const buffer = await new Response(inflated).arrayBuffer();
  const side = m.tile + 2 * m.apron;
  const n = side * side;
  const out = {};
  let offset = 0;
  for (const f of m.bundles[bundle]) {
    out[f.name] = new VIEWS[f.type](buffer, offset, n);
    offset += n * BYTES[f.type];
  }
  if (offset !== buffer.byteLength) throw new Error(`unexpected tile size ${url}`);
  return out;
}

// One budget for every layer's tiles; each layer keeps its single top tile.
function evict() {
  const all = layers().flatMap((l) => [...l.tiles.entries()].map(([k, t]) => [k, t]));
  if (all.length <= CACHE_TILES) return;
  const old = all.filter(([, t]) => t.used < frame && t.level < t.layer.top());
  old.sort((a, b) => a[1].used - b[1].used);
  for (const [k, t] of old.slice(0, all.length - CACHE_TILES)) t.layer.tiles.delete(k);
}

// Hillshade and slope from the tile's surface and its apron, as tools/atlas/paint.py shades.
function terrain(t) {
  if (t.shade) return t;
  const m = t.layer.m;
  const T = m.tile;
  const A = m.apron;
  const S = T + 2 * A;
  const s = t.core.surface;
  const spacing = m.step << t.level;
  const shade = new Float32Array(T * T);
  const slope = new Float32Array(T * T);
  const az = (315 * Math.PI) / 180;
  const alt = Math.PI / 4;
  for (let r = 0; r < T; r++) {
    for (let c = 0; c < T; c++) {
      const i = (r + A) * S + c + A;
      const gx = (s[i + 1] - s[i - 1]) / (2 * spacing);
      const gz = (s[i + S] - s[i - S]) / (2 * spacing);
      const g = Math.hypot(gx, gz);
      const sl = Math.PI / 2 - Math.atan(g * 1.5);
      const aspect = Math.atan2(-gx, gz);
      const v = Math.sin(alt) * Math.sin(sl) + Math.cos(alt) * Math.cos(sl) * Math.cos(az - aspect);
      shade[r * T + c] = clamp(v, 0, 1);
      slope[r * T + c] = (Math.atan(g) * 180) / Math.PI;
    }
  }
  t.shade = shade;
  t.slope = slope;
  return t;
}

// ---------------------------------------------------------------------------------------------
// Projections: each fills a tile's pixels from its fields.

// other: the compared render's tile over the same pixels, for the difference projections.
function fill(t, paint, other = null) {
  const m = t.layer.m;
  const T = m.tile;
  const A = m.apron;
  const S = T + 2 * A;
  terrain(t);
  const img = new ImageData(T, T);
  const px = img.data;
  const rgb = [0, 0, 0];
  const env = { m, c: t.core, st: t.stages, sh: t.shade, slope: t.slope, rgb, S, sea: m.waterTopY, b: other?.core };
  for (let r = 0; r < T; r++) {
    for (let col = 0; col < T; col++) {
      const k = r * T + col;
      paint(env, (r + A) * S + col + A, k);
      px[k * 4] = rgb[0];
      px[k * 4 + 1] = rgb[1];
      px[k * 4 + 2] = rgb[2];
      px[k * 4 + 3] = 255;
    }
  }
  return img;
}

const wet = (c, i) => c.water[i] > c.surface[i];
const marine = (c, i) => (c.flags[i] & 1) !== 0;

function waterColor(env, i) {
  const c = env.c;
  const d = clamp((c.water[i] - c.surface[i]) / 120, 0, 1);
  env.rgb[0] = 70 * (1 - d) + 10 * d;
  env.rgb[1] = 130 * (1 - d) + 25 * d;
  env.rgb[2] = 200 * (1 - d) + 70 * d;
}

function base(env, i, k) {
  if (wet(env.c, i)) return waterColor(env, i);
  const g = 90 + 140 * env.sh[k];
  env.rgb[0] = env.rgb[1] = env.rgb[2] = g;
}

function tint(env, color, factor) {
  env.rgb[0] = color[0] * factor;
  env.rgb[1] = color[1] * factor;
  env.rgb[2] = color[2] * factor;
}

function mix(env, color, t) {
  env.rgb[0] += (color[0] - env.rgb[0]) * t;
  env.rgb[1] += (color[1] - env.rgb[1]) * t;
  env.rgb[2] += (color[2] - env.rgb[2]) * t;
}

const GREY = [128, 128, 128];
const palette = (env, name) => env.m.legends[name]?.colors ?? [];

function categorical(field, legendName, { shade = [0.6, 0.5], water = false, landOnly = false } = {}) {
  return (env, i, k) => {
    if ((water && wet(env.c, i)) || (landOnly && marine(env.c, i))) return base(env, i, k);
    const color = palette(env, legendName)[env.c[field][i]] ?? GREY;
    tint(env, color, shade[0] + shade[1] * env.sh[k]);
  };
}

function relief(m) {
  const stops = m.elevationStops.map(([rise, c]) => [m.waterTopY + rise, c]);
  return (env, i, k) => {
    if (wet(env.c, i)) return waterColor(env, i);
    ramp(stops, env.c.surface[i], env.rgb);
    const f = 0.45 + 0.75 * env.sh[k];
    env.rgb[0] *= f;
    env.rgb[1] *= f;
    env.rgb[2] *= f;
  };
}

function continuous(field, stops, scale) {
  return (env, i, k) => {
    ramp(stops, env.c[field][i] / scale, env.rgb);
    const f = 0.7 + 0.4 * env.sh[k];
    env.rgb[0] *= f;
    env.rgb[1] *= f;
    env.rgb[2] *= f;
  };
}

function steepness(env, i, k) {
  if (wet(env.c, i)) return waterColor(env, i);
  ramp(STEEPNESS, env.slope[k], env.rgb);
}

// Walls: a dry column's largest step to its next column east or south (TerrainAtlas walls=true).
const WALL_COLORS = [null, null, [245, 160, 40], [235, 90, 30], [200, 20, 20]];

function walls(env, i, k) {
  base(env, i, k);
  const step = env.c.walls[i];
  if (step >= 2) copy(WALL_COLORS[Math.min(4, step)], env.rgb);
}

function wallsLegend() {
  return list(
    [
      { label: 'step of 2 (a walker cannot step up)', color: WALL_COLORS[2] },
      { label: 'step of 3 (the largest safe drop)', color: WALL_COLORS[3] },
      { label: 'step of 4 or more', color: WALL_COLORS[4] },
      { label: 'water (darker = deeper)', color: [40, 90, 170] },
    ],
    'Each pixel’s column against its neighbours one block east and south; coarser levels show only the pixels they keep.',
  );
}

function waterBodies(env, i, k) {
  const c = env.c;
  const g = 90 + 140 * env.sh[k];
  env.rgb[0] = env.rgb[1] = env.rgb[2] = g;
  if (marine(c, i)) copy((c.flags[i] & 2) !== 0 ? [25, 45, 110] : [200, 60, 60], env.rgb);
  const kind = c.water_kind[i];
  if (kind) copy(palette(env, 'water_kind')[kind] ?? GREY, env.rgb);
}

function rivers(env, i, k) {
  const c = env.c;
  const flags = c.river[i];
  if (marine(c, i) && !(flags & 1)) {
    tint(env, RIVER_COLORS.ocean, 0.8 + 0.3 * env.sh[k]);
  } else if (wet(c, i)) {
    if (flags & 2) copy(RIVER_COLORS.stream, env.rgb);
    else if (flags & 1) {
      copy(RIVER_COLORS.river, env.rgb);
      mix(env, [0, 60, 170], clamp((c.water[i] - c.surface[i]) / 10, 0, 1));
    } else copy(RIVER_COLORS.still, env.rgb);
  } else {
    const g = 50 + 110 * env.sh[k];
    env.rgb[0] = env.rgb[1] = env.rgb[2] = g;
    const beyond = c.channel_beyond ? c.channel_beyond[i] : flags & 4 ? 0 : 255;
    if (flags & 8) copy(RIVER_COLORS.bar, env.rgb);
    else if (beyond < 64) mix(env, RIVER_COLORS.valley, 0.6 * (1 - beyond / 64));
  }
  if (c.mouth[i]) mix(env, RIVER_COLORS.mouth, (c.mouth[i] / 255) * 0.7);
}

function riverSystems(env, i, k) {
  const c = env.c;
  if (wet(c, i) && c.water_body[i]) return copy(bodyColor(c.water_body[i]), env.rgb);
  if (marine(c, i)) return tint(env, RIVER_COLORS.ocean, 0.8 + 0.3 * env.sh[k]);
  const g = 40 + 90 * env.sh[k];
  env.rgb[0] = env.rgb[1] = env.rgb[2] = g;
}

function border(field) {
  return (env, i) => {
    const v = env.c[field];
    const x = v[i];
    return x !== v[i + 1] || x !== v[i - 1] || x !== v[i + env.S] || x !== v[i - env.S];
  };
}

const plateBorder = border('plate');
const provinceBorder = border('province');

function plates(env, i, k) {
  if (plateBorder(env, i)) return copy([0, 0, 0], env.rgb);
  const color = palette(env, 'plate')[env.c.plate[i]] ?? GREY;
  tint(env, color, (0.55 + 0.5 * env.sh[k]) * (marine(env.c, i) ? 0.7 : 1));
}

function provinces(env, i, k) {
  const c = env.c;
  if (provinceBorder(env, i)) return copy([18, 18, 18], env.rgb);
  const type = c.province_type[i];
  if (!type) return base(env, i, k);
  tint(env, palette(env, 'province_type')[type] ?? GREY, (0.6 + 0.5 * env.sh[k]) * (marine(c, i) ? 0.75 : 1));
}

function coasts(env, i, k) {
  const c = env.c;
  base(env, i, k);
  const type = c.coast[i];
  if (!type) return tint(env, env.rgb.slice(), 0.75);
  const color = palette(env, 'coast')[type] ?? GREY;
  if (wet(c, i)) return mix(env, color, 0.45);
  tint(env, color, 0.6 + 0.5 * env.sh[k]);
}

function features(env, i, k) {
  const c = env.c;
  base(env, i, k);
  const w = c.winner[i];
  if (!w) return;
  const color = env.m.stages[w - 1].familyColors[c.feature[i]] ?? GREY;
  tint(env, color, 0.55 + 0.6 * env.sh[k]);
}

function stageDelta(m, stage) {
  const units = m.deltaUnits;
  return (env, i, k) => {
    if (wet(env.c, i)) {
      waterColor(env, i);
      return tint(env, env.rgb.slice(), 0.6);
    }
    const t = clamp(env.st[`delta_${stage.name}`][i] / units / stage.deltaScale, -1, 1);
    const g = 90 + 140 * env.sh[k];
    const target = t > 0 ? [200, 40, 30] : [30, 80, 210];
    const a = Math.abs(t);
    env.rgb[0] = g * (1 - a) + target[0] * a;
    env.rgb[1] = g * (1 - a) + target[1] * a;
    env.rgb[2] = g * (1 - a) + target[2] * a;
  };
}

function stageReason(stage) {
  return (env, i, k) => {
    if (marine(env.c, i)) {
      base(env, i, k);
      return tint(env, env.rgb.slice(), 0.5);
    }
    const color = stage.reasonColors[env.st[`reason_${stage.name}`][i]] ?? GREY;
    tint(env, color, 0.6 + 0.5 * env.sh[k]);
  };
}

// Each projection: id, group, name, needs (fields), paint (built per render), legend.
function projections(m) {
  const list = [
    { id: 'relief', group: 'Terrain', name: 'Relief', paint: relief(m), legend: reliefLegend },
    { id: 'shade', group: 'Terrain', name: 'Hillshade', paint: base, legend: shadeLegend },
    { id: 'steepness', group: 'Terrain', name: 'Steepness', paint: steepness, legend: () => rampLegend(STEEPNESS, '°') },
    { id: 'walls', group: 'Terrain', name: 'Walls', needs: ['walls'], paint: walls, legend: wallsLegend },
    { id: 'biomes', group: 'Biomes and climate', name: 'Biomes', paint: categorical('habitat', 'habitat'), legend: () => categoryLegend('habitat') },
    { id: 'climate', group: 'Biomes and climate', name: 'Climate regions', paint: categorical('region', 'region', { water: true }), legend: () => categoryLegend('region') },
    { id: 'temperature', group: 'Biomes and climate', name: 'Temperature', paint: continuous('celsius', TEMPERATURE, 100), legend: () => rampLegend(TEMPERATURE, ' °C') },
    { id: 'moisture', group: 'Biomes and climate', name: 'Moisture', paint: continuous('moisture', MOISTURE, 10000), legend: () => rampLegend(MOISTURE, '') },
    { id: 'rivers', group: 'Water', name: 'Rivers', needs: ['river', 'mouth'], paint: rivers, legend: riverLegend },
    { id: 'systems', group: 'Water', name: 'River and lake bodies', needs: ['water_body'], paint: riverSystems, legend: systemsLegend },
    { id: 'water', group: 'Water', name: 'Water bodies and supply', paint: waterBodies, legend: waterLegend },
    { id: 'plates', group: 'Structure', name: 'Plates', needs: ['plate'], paint: plates, legend: () => categoryLegend('plate', (n, i) => `plate #${i}`) },
    { id: 'provinces', group: 'Structure', name: 'Provinces', needs: ['province', 'province_type'], paint: provinces, legend: () => categoryLegend('province_type', undefined, 'Cell borders drawn dark. Shares are of land.') },
    { id: 'coasts', group: 'Structure', name: 'Coast types', needs: ['coast'], paint: coasts, legend: () => categoryLegend('coast') },
    { id: 'level', group: 'Structure', name: 'Land level', paint: categorical('level', 'level'), legend: () => categoryLegend('level') },
    { id: 'character', group: 'Structure', name: 'Land character', paint: categorical('character', 'character', { landOnly: true }), legend: () => categoryLegend('character') },
    { id: 'features', group: 'Features', name: 'Selected features', paint: features, legend: featureLegend },
  ];
  for (const stage of m.stages) {
    list.push({ id: `delta:${stage.name}`, group: 'delta', name: stage.name, stages: true, paint: stageDelta(m, stage), legend: () => deltaLegend(stage), stage });
    if (stage.reasons.length > 1)
      list.push({ id: `reason:${stage.name}`, group: 'reason', name: stage.name, stages: true, paint: stageReason(stage), legend: () => reasonLegend(stage), stage });
  }
  const fields = new Set(Object.values(m.bundles).flat().map((f) => f.name));
  return list.filter((p) => (p.needs ?? []).every((n) => fields.has(n)));
}

// ---------------------------------------------------------------------------------------------
// Difference projections: the compared render (B) against the open one (A), pixel for pixel.
// Both renders share the window and grid, so tile L/tx/tz covers the same samples in each.

const DIFF_RANGE = 32; // blocks of height change at full colour (square-root ramp below it)
const DIFF = {
  gained: [40, 120, 255],
  lost: [240, 140, 30],
  moved: [120, 230, 240],
  removed: [230, 60, 200],
};

// Code translations from B's legends to A's, since two terrain versions may order names apart.
function diffTables(a, b) {
  const translate = (name) => {
    const index = new Map((a.legends[name]?.names ?? []).map((n, i) => [n, i]));
    return Int32Array.from(b.legends[name]?.names ?? [], (n) => index.get(n) ?? -1);
  };
  const keys = new Map();
  const featureKeys = (m) => {
    const out = new Int32Array((m.stages.length + 1) * 256);
    m.stages.forEach((s, w) =>
      s.families.forEach((f, k) => {
        const key = `${s.name}/${f}`;
        if (!keys.has(key)) keys.set(key, keys.size + 1);
        out[(w + 1) * 256 + k] = keys.get(key);
      }),
    );
    return out;
  };
  return { habitat: translate('habitat'), featureA: featureKeys(a), featureB: featureKeys(b) };
}

function unchanged(env, k) {
  const g = 70 + 90 * env.sh[k];
  env.rgb[0] = env.rgb[1] = env.rgb[2] = g;
}

function diffHeight(env, i, k) {
  const d = env.b.surface[i] - env.c.surface[i];
  if (d === 0) return unchanged(env, k);
  const t = Math.min(1, Math.sqrt(Math.abs(d) / DIFF_RANGE));
  const g = 90 + 140 * env.sh[k];
  const target = d > 0 ? [200, 40, 30] : [30, 80, 210];
  env.rgb[0] = g * (1 - t) + target[0] * t;
  env.rgb[1] = g * (1 - t) + target[1] * t;
  env.rgb[2] = g * (1 - t) + target[2] * t;
}

function diffBiome(env, i, k) {
  const hb = env.b.habitat[i];
  if (state.diff.habitat[hb] === env.c.habitat[i]) return unchanged(env, k);
  tint(env, state.b.m.legends.habitat.colors[hb] ?? GREY, 0.6 + 0.5 * env.sh[k]);
}

function diffFeature(env, i, k) {
  const a = state.diff.featureA[env.c.winner[i] * 256 + env.c.feature[i]];
  const wb = env.b.winner[i];
  const fb = env.b.feature[i];
  if (state.diff.featureB[wb * 256 + fb] === a) return unchanged(env, k);
  if (!wb) return tint(env, DIFF.removed, 0.6 + 0.5 * env.sh[k]);
  tint(env, state.b.m.stages[wb - 1].familyColors[fb] ?? GREY, 0.55 + 0.6 * env.sh[k]);
}

function diffWater(env, i, k) {
  const wa = wet(env.c, i);
  const wb = wet(env.b, i);
  if (wa !== wb) return copy(wb ? DIFF.gained : DIFF.lost, env.rgb);
  if (wa && env.c.water[i] !== env.b.water[i]) return copy(DIFF.moved, env.rgb);
  unchanged(env, k);
  if (wa) mix(env, [20, 40, 90], 0.6);
}

function diffProjections() {
  const group = 'Compare: B against A';
  return [
    { id: 'diff:height', group, name: 'Height change', diff: true, paint: diffHeight, legend: diffHeightLegend },
    { id: 'diff:biome', group, name: 'Changed biome', diff: true, paint: diffBiome, legend: diffBiomeLegend },
    { id: 'diff:feature', group, name: 'Changed feature', diff: true, paint: diffFeature, legend: diffFeatureLegend },
    { id: 'diff:water', group, name: 'Changed water', diff: true, paint: diffWater, legend: diffWaterLegend },
  ];
}

const DIFFS = diffProjections();
const allProjections = () => (state.a ? state.a.projections : []).concat(state.b ? DIFFS : []);
const projection = () => allProjections().find((p) => p.id === state.projection) ?? allProjections()[0];

// id: the view's projection. A layer without it (or a stage projection whose stage bundle has not
// arrived) shows hillshade. other: the compared tile a difference projection reads.
function imageOf(t, id, other = null) {
  const layer = t.layer;
  let p = other ? DIFFS.find((d) => d.id === id) : layer.projection(id);
  if (!p || (p.stages && !t.stages)) p = layer.projection('shade');
  const key = other ? `${p.id}|${other.layer.m.id}` : p.id;
  if (t.imageKey === key && t.image) return t.image;
  const img = fill(t, p.paint, other);
  if (!t.image) {
    t.image = document.createElement('canvas');
    t.image.width = t.image.height = layer.m.tile;
  }
  t.image.getContext('2d').putImageData(img, 0, 0);
  t.imageKey = key;
  return t.image;
}

// ---------------------------------------------------------------------------------------------
// Drawing

let scheduled = false;
// requestAnimationFrame never fires in some embedded panes, so a timer stands in for it.
function schedule() {
  if (scheduled) return;
  scheduled = true;
  const run = () => {
    if (!scheduled) return;
    scheduled = false;
    draw();
  };
  requestAnimationFrame(run);
  setTimeout(run, 40);
}

function levelFor(layer) {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const l = Math.round(Math.log2(1 / (state.scale * dpr * layer.m.step)));
  return clamp(l, 0, layer.top());
}

function screenX(x) {
  return (x - state.cx) * state.scale + canvas.clientWidth / 2;
}

function screenZ(z) {
  return (z - state.cz) * state.scale + canvas.clientHeight / 2;
}

const snapper = (dpr) => (v) => Math.round(v * dpr) / dpr;

function draw() {
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  const dpr = window.devicePixelRatio || 1;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = '#0b0b0b';
  ctx.fillRect(0, 0, w, h);
  const m = state.m;
  if (!m) return;
  frame++;
  ctx.imageSmoothingEnabled = false;
  const snap = snapper(dpr);
  const p = projection();
  const wanted = [];
  const b = state.b;
  if (p.diff && b) drawLayer(state.a, p.id, wanted, { pair: b });
  else if (b && state.compare.mode === 'toggle') drawLayer(state.compare.showB ? b : state.a, p.id, wanted);
  else {
    drawLayer(state.a, p.id, wanted, { right: b ? snap(state.compare.split * w) : w });
    if (b) {
      const x = snap(state.compare.split * w);
      ctx.save();
      ctx.beginPath();
      ctx.rect(x, 0, w - x, h);
      ctx.clip();
      drawLayer(b, p.id, wanted, { left: x });
      ctx.restore();
    }
  }
  wanted.sort((s, t) => distance(s, w / 2, h / 2) - distance(t, w / 2, h / 2));
  for (const t of wanted) request(t, 'core');
  pump();

  // The render's edge.
  ctx.strokeStyle = 'rgba(255,255,255,0.5)';
  ctx.lineWidth = 1;
  ctx.strokeRect(
    snap(screenX(m.minX)) + 0.5,
    snap(screenZ(m.minZ)) + 0.5,
    snap(m.width * m.step * state.scale),
    snap(m.height * m.step * state.scale),
  );
  if (state.grid) drawGrid(w, h);
  if (state.inspect) drawMarker();
  drawScale();
  showCompareChrome();
  evict();
}

// Paints one layer's visible tiles; tiles still loading go on wanted. left/right bound the screen
// columns this layer shows (the swipe), pair is the compared layer a difference projection reads.
function drawLayer(layer, id, wanted, { left = 0, right = canvas.clientWidth, pair = null } = {}) {
  const snap = snapper(window.devicePixelRatio || 1);
  const p = layer.projection(id) ?? DIFFS.find((d) => d.id === id);
  const L = levelFor(layer);
  for (const l of pair ? [layer, pair] : [layer]) {
    const top = l.tileAt(l.top(), 0, 0);
    top.used = frame;
    request(top, 'core');
  }
  for (const { t, vw, vh, x0, z0, x1, z1 } of visible(layer, L, left, right)) {
    t.used = frame;
    const other = pair && pair.tileAt(t.level, t.tx, t.tz);
    if (other) other.used = frame;
    const box = [snap(x0), snap(z0), snap(x1), snap(z1)];
    if (t.core && (!other || other.core)) {
      if (p?.stages) request(t, 'stages');
      paintTile(imageOf(t, id, other), vw, vh, ...box);
    } else {
      if (!t.core) wanted.push(t);
      if (other && !other.core) wanted.push(other);
      fallback(t, id, pair, ...box);
    }
  }
}

function distance(t, cx, cz) {
  const m = t.layer.m;
  const span = m.step * (1 << t.level) * m.tile;
  const x = screenX(m.minX + (t.tx + 0.5) * span);
  const z = screenZ(m.minZ + (t.tz + 0.5) * span);
  return Math.hypot(x - cx, z - cz);
}

function* visible(layer, L, left = 0, right = canvas.clientWidth) {
  const m = layer.m;
  const lv = m.levels[L];
  const span = m.step * (1 << L) * m.tile;
  const h = canvas.clientHeight;
  const bx0 = state.cx + (left - canvas.clientWidth / 2) / state.scale;
  const bx1 = state.cx + (right - canvas.clientWidth / 2) / state.scale;
  const bz0 = state.cz - h / 2 / state.scale;
  const bz1 = state.cz + h / 2 / state.scale;
  const tx0 = Math.max(0, Math.floor((bx0 - m.minX) / span));
  const tx1 = Math.min(lv.cols - 1, Math.floor((bx1 - m.minX) / span));
  const tz0 = Math.max(0, Math.floor((bz0 - m.minZ) / span));
  const tz1 = Math.min(lv.rows - 1, Math.floor((bz1 - m.minZ) / span));
  const pixel = m.step << L;
  for (let tz = tz0; tz <= tz1; tz++) {
    for (let tx = tx0; tx <= tx1; tx++) {
      const vw = Math.min(m.tile, lv.width - tx * m.tile);
      const vh = Math.min(m.tile, lv.height - tz * m.tile);
      const x0 = screenX(m.minX + tx * span);
      const z0 = screenZ(m.minZ + tz * span);
      yield {
        t: layer.tileAt(L, tx, tz),
        vw,
        vh,
        x0,
        z0,
        x1: x0 + vw * pixel * state.scale,
        z1: z0 + vh * pixel * state.scale,
      };
    }
  }
}

// vw x vh is the tile's own pixel count; recomputing it from snapped screen edges can ask for a
// column past the image, which shrinks the drawn tile and opens a gap at its edge.
function paintTile(image, vw, vh, x0, z0, x1, z1) {
  ctx.drawImage(image, 0, 0, vw, vh, x0, z0, x1 - x0, z1 - z0);
}

// Until a tile arrives, its loaded ancestor covers the same ground at a coarser level.
function fallback(t, id, pair, x0, z0, x1, z1) {
  const layer = t.layer;
  const m = layer.m;
  for (let p = t.level + 1; p <= layer.top(); p++) {
    const d = p - t.level;
    const k = `${p}/${t.tx >> d}/${t.tz >> d}`;
    const a = layer.tiles.get(k);
    const other = pair && pair.tiles.get(k);
    if (!a?.core || (pair && !other?.core)) continue;
    const lv = m.levels[p];
    const span = m.step * (1 << p) * m.tile;
    const ax0 = screenX(m.minX + a.tx * span);
    const az0 = screenZ(m.minZ + a.tz * span);
    const pixel = (m.step << p) * state.scale;
    const vw = Math.min(m.tile, lv.width - a.tx * m.tile);
    const vh = Math.min(m.tile, lv.height - a.tz * m.tile);
    ctx.save();
    ctx.beginPath();
    ctx.rect(x0, z0, x1 - x0, z1 - z0);
    ctx.clip();
    a.used = frame;
    if (other) other.used = frame;
    ctx.drawImage(imageOf(a, id, other), 0, 0, vw, vh, ax0, az0, vw * pixel, vh * pixel);
    ctx.restore();
    return;
  }
}

function drawGrid(w, h) {
  let spacing = 16;
  while (spacing * state.scale < 64) spacing *= 2;
  ctx.strokeStyle = 'rgba(255,255,255,0.22)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  const x0 = Math.floor((state.cx - w / 2 / state.scale) / spacing) * spacing;
  for (let x = x0; screenX(x) < w; x += spacing) {
    const sx = Math.round(screenX(x)) + 0.5;
    ctx.moveTo(sx, 0);
    ctx.lineTo(sx, h);
  }
  const z0 = Math.floor((state.cz - h / 2 / state.scale) / spacing) * spacing;
  for (let z = z0; screenZ(z) < h; z += spacing) {
    const sz = Math.round(screenZ(z)) + 0.5;
    ctx.moveTo(0, sz);
    ctx.lineTo(w, sz);
  }
  ctx.stroke();
  state.gridSpacing = spacing;
}

function drawMarker() {
  const { x, z } = state.inspect;
  const size = Math.max(state.m.step * state.scale, 6);
  const sx = Math.round(screenX(x)) + 0.5;
  const sz = Math.round(screenZ(z)) + 0.5;
  ctx.strokeStyle = '#fff';
  ctx.lineWidth = 1;
  ctx.strokeRect(sx - size / 2, sz - size / 2, size, size);
  ctx.strokeStyle = '#000';
  ctx.strokeRect(sx - size / 2 - 1, sz - size / 2 - 1, size + 2, size + 2);
}

function drawScale() {
  const el = $('scale');
  let blocks = 1;
  const nice = [1, 2, 5];
  for (let e = 0; e < 7; e++) {
    for (const n of nice) {
      const b = n * 10 ** e;
      if (b * state.scale >= 70) {
        blocks = b;
        e = 7;
        break;
      }
    }
  }
  el.querySelector('i').style.width = `${Math.round(blocks * state.scale)}px`;
  const grid = state.grid ? ` · grid ${state.gridSpacing}` : '';
  el.querySelector('span').textContent = `${blocks.toLocaleString('en-US')} blocks${grid}`;
}

// ---------------------------------------------------------------------------------------------
// Legend

function swatchRow(color, label, pct) {
  const li = document.createElement('li');
  const sw = document.createElement('span');
  sw.className = 'swatch';
  sw.style.background = `rgb(${color.map(Math.round).join(',')})`;
  const name = document.createElement('span');
  name.textContent = label;
  const share = document.createElement('span');
  share.className = 'pct';
  share.textContent = pct == null ? '' : `${pct < 0.01 ? '<0.01' : pct.toFixed(pct < 10 ? 2 : 1)}%`;
  li.append(sw, name, share);
  return li;
}

function list(rows, note) {
  const box = document.createElement('div');
  const ul = document.createElement('ul');
  for (const r of rows) ul.append(swatchRow(r.color, r.label, r.pct));
  box.append(ul);
  if (note) box.append(noteEl(note));
  return box;
}

function noteEl(text) {
  const p = document.createElement('p');
  p.className = 'note';
  p.textContent = text;
  return p;
}

function categoryLegend(name, labelOf = (n) => n, note = '') {
  const lg = state.m.legends[name];
  const rows = lg.names
    .map((n, i) => ({ label: labelOf(n, i), color: lg.colors[i], pct: lg.shares[i], named: n !== '' }))
    .filter((r) => r.named && r.pct > 0)
    .sort((a, b) => b.pct - a.pct)
    .slice(0, 48);
  return list(rows, note || 'Shares are of the whole render.');
}

function rampLegend(stops, unit) {
  const box = document.createElement('div');
  const bar = document.createElement('div');
  bar.className = 'ramp';
  const lo = stops[0][0];
  const hi = stops[stops.length - 1][0];
  bar.style.background = `linear-gradient(to right, ${stops
    .map(([v, c]) => `rgb(${c.join(',')}) ${(((v - lo) / (hi - lo)) * 100).toFixed(1)}%`)
    .join(', ')})`;
  const labels = document.createElement('div');
  labels.className = 'ramp-labels';
  for (const [v] of [stops[0], stops[Math.floor(stops.length / 2)], stops[stops.length - 1]]) {
    const s = document.createElement('span');
    s.textContent = `${v}${unit}`;
    labels.append(s);
  }
  box.append(bar, labels);
  return box;
}

function reliefLegend() {
  const m = state.m;
  const rows = m.elevationStops.map(([rise, c]) => ({ label: `Y${m.waterTopY + rise}`, color: c }));
  rows.push({ label: 'water (darker = deeper)', color: [40, 90, 170] });
  return list(rows, `Water surface at Y${m.waterTopY}. Lit from the north-west.`);
}

function shadeLegend() {
  return list([{ label: 'water (darker = deeper)', color: [40, 90, 170] }], 'Lit from the north-west.');
}

function riverLegend() {
  const c = RIVER_COLORS;
  return list(
    [
      { label: 'river (darker = deeper)', color: c.river },
      { label: 'stream (too narrow for a river)', color: c.stream },
      { label: 'lake, wetland or pond', color: c.still },
      { label: 'dry ground near a channel (fades over 64 blocks)', color: c.valley },
      { label: 'bar at a channel’s water level', color: c.bar },
      { label: 'river mouth reach', color: c.mouth },
      { label: 'ocean', color: c.ocean },
    ],
    'From each column’s water sample: kind, stream flag, distance beyond the nearest channel, mouth weight.',
  );
}

function systemsLegend() {
  return list(
    [{ label: 'one colour per wetting channel or lake', color: bodyColor(3) }],
    'Wet columns coloured by the id of the water that wets them.',
  );
}

function waterLegend() {
  const lg = state.m.legends.water_kind;
  const rows = [
    { label: 'supplied ocean', color: [25, 45, 110] },
    { label: 'marine, unsupplied', color: [200, 60, 60] },
  ];
  lg.names.forEach((n, i) => {
    if (i > 0 && lg.shares[i] > 0) rows.push({ label: n.toLowerCase(), color: lg.colors[i], pct: lg.shares[i] });
  });
  return list(rows);
}

function featureLegend() {
  const m = state.m;
  const rows = [];
  for (const s of m.stages) {
    s.featureShares.forEach((pct, f) => {
      if (pct > 0) rows.push({ label: f ? `${s.name}: ${s.families[f].toLowerCase()}` : s.name, color: s.familyColors[f], pct });
    });
  }
  rows.sort((a, b) => b.pct - a.pct);
  return list(rows.slice(0, 48), `Largest stage change per column, at least ${m.threshold} block.`);
}

function deltaLegend(stage) {
  const box = rampLegend(
    [
      [-stage.deltaScale, [30, 80, 210]],
      [0, [160, 160, 160]],
      [stage.deltaScale, [200, 40, 30]],
    ],
    '',
  );
  box.append(noteEl(`${stage.name} changes ${stage.activeLandPct}% of land. Red raises, blue lowers; full colour at ±${stage.deltaScale} blocks.`));
  return box;
}

function reasonLegend(stage) {
  const rows = stage.reasons
    .map((n, i) => ({ label: n || '(none)', color: stage.reasonColors[i], pct: stage.reasonShares[i] }))
    .filter((r, i) => i > 0 && r.pct > 0)
    .sort((a, b) => b.pct - a.pct);
  return list(rows, 'The stage’s own recorded reason. Shares are of land.');
}

function compareNote() {
  const a = state.a.m;
  const b = state.b.m;
  return `B is v${b.terrainVersion} (${b.digest.slice(0, 8)}), A is v${a.terrainVersion} (${a.digest.slice(0, 8)}).`;
}

function diffHeightLegend() {
  const box = rampLegend(
    [
      [-DIFF_RANGE, [30, 80, 210]],
      [0, [160, 160, 160]],
      [DIFF_RANGE, [200, 40, 30]],
    ],
    '',
  );
  box.append(noteEl(`Surface of B minus A. Red is higher in B, blue lower; colour grows with the square root of the change, full at ±${DIFF_RANGE} blocks. Unchanged columns are grey. ${compareNote()}`));
  return box;
}

function diffBiomeLegend() {
  return list(
    [{ label: 'unchanged', color: [130, 130, 130] }],
    `Changed columns take B’s biome colour (the Biomes legend of B). ${compareNote()}`,
  );
}

function diffFeatureLegend() {
  return list(
    [
      { label: 'unchanged', color: [130, 130, 130] },
      { label: 'A’s feature gone in B', color: DIFF.removed },
    ],
    `Other changed columns take B’s feature colour (stage and family, matched by name). ${compareNote()}`,
  );
}

function diffWaterLegend() {
  return list(
    [
      { label: 'wet in B, dry in A', color: DIFF.gained },
      { label: 'dry in B, wet in A', color: DIFF.lost },
      { label: 'water level moved', color: DIFF.moved },
      { label: 'unchanged water', color: [45, 60, 95] },
      { label: 'unchanged dry ground', color: [130, 130, 130] },
    ],
    compareNote(),
  );
}

function showLegend() {
  const p = projection();
  const body = $('legend-body');
  body.replaceChildren(p.legend());
  const title = p.group === 'delta' ? `Change: ${p.name}` : p.group === 'reason' ? `Reasons: ${p.name}` : p.name;
  $('projection-name').textContent = title;
  $('legend-toggle').textContent = `Legend · ${title}`;
}

// ---------------------------------------------------------------------------------------------
// Inspector

function columnAt(layer, x, z) {
  const m = layer.m;
  const c0 = Math.floor((x - m.minX) / m.step);
  const r0 = Math.floor((z - m.minZ) / m.step);
  if (c0 < 0 || r0 < 0 || c0 >= m.width || r0 >= m.height) return null;
  for (let L = 0; L <= layer.top(); L++) {
    const c = c0 >> L;
    const r = r0 >> L;
    const t = layer.tiles.get(`${L}/${Math.floor(c / m.tile)}/${Math.floor(r / m.tile)}`);
    if (!t?.core) continue;
    const S = m.tile + 2 * m.apron;
    const i = ((r % m.tile) + m.apron) * S + (c % m.tile) + m.apron;
    return { t, i, L, x: m.minX + c * (m.step << L), z: m.minZ + r * (m.step << L) };
  }
  return null;
}

function cellOf(id) {
  if (!id) return '';
  const v = BigInt(id);
  return `${BigInt.asIntN(32, v >> 32n)}, ${BigInt.asIntN(32, v)}`;
}

function name(legendName, code, m = state.m) {
  const n = m.legends[legendName]?.names[code];
  return n == null || n === '' ? '—' : n;
}

// Requests the tile holding a column at the view's level; false outside the render.
function wantColumn(layer, x, z) {
  const m = layer.m;
  const c = Math.floor((x - m.minX) / m.step);
  const r = Math.floor((z - m.minZ) / m.step);
  if (c < 0 || r < 0 || c >= m.width || r >= m.height) return false;
  const L = levelFor(layer);
  const t = layer.tileAt(L, Math.floor((c >> L) / m.tile), Math.floor((r >> L) / m.tile));
  t.used = frame;
  request(t, 'core');
  pump();
  return true;
}

function featureName(m, w, f) {
  return w ? `${m.stages[w - 1].name}: ${m.stages[w - 1].families[f].toLowerCase()}` : '—';
}

// The compared render's column at the same sample, when its tile has arrived.
function compareRows(add, rule, at) {
  const b = state.b;
  const t = b.tileAt(at.L, at.t.tx, at.t.tz);
  rule();
  if (!t.core) {
    t.used = frame;
    request(t, 'core');
    pump();
    return add('B', 'loading…');
  }
  const a = at.t.core;
  const c = t.core;
  const i = at.i;
  const d = c.surface[i] - a.surface[i];
  add('B surface', `Y${c.surface[i]} (${d > 0 ? '+' : ''}${d})`);
  add('B water', wet(c, i) ? `Y${c.water[i]}, ${c.water[i] - c.surface[i]} deep` : '—');
  add('B biome', name('habitat', c.habitat[i], b.m));
  add('B feature', featureName(b.m, c.winner[i], c.feature[i]));
}

function showInspector() {
  const m = state.m;
  const at = state.inspect && columnAt(state.a, state.inspect.x, state.inspect.z);
  const panel = $('inspector');
  if (!at) {
    // Nothing loaded there yet (a toggle showing B loads only B's tiles): fetch A's tile.
    if (state.inspect && wantColumn(state.a, state.inspect.x, state.inspect.z)) return;
    panel.hidden = true;
    return;
  }
  const { t, i, L } = at;
  const c = t.core;
  request(t, 'stages');
  pump();
  const rows = [];
  const add = (k, v) => rows.push([k, v]);
  const rule = () => rows.push(null);
  add('sample', `${at.x}, ${at.z}${L ? ` (every ${m.step << L})` : ''}`);
  add('surface', `Y${c.surface[i]}`);
  add('water', c.water[i] > -32768 && wet(c, i) ? `Y${c.water[i]}, ${c.water[i] - c.surface[i]} deep` : '—');
  add('biome', name('habitat', c.habitat[i]));
  add('climate', name('region', c.region[i]));
  add('°C', (c.celsius[i] / 100).toFixed(2));
  add('moisture', (c.moisture[i] / 10000).toFixed(3));
  add('level', name('level', c.level[i]));
  add('character', name('character', c.character[i]));
  const f = c.flags[i];
  add('ocean', [f & 1 && 'marine', f & 2 && 'supplied', f & 4 && 'deep', f & 8 && 'wet'].filter(Boolean).join(', ') || '—');
  rule();
  add('water kind', name('water_kind', c.water_kind[i]).toLowerCase());
  if (c.river) {
    const r = c.river[i];
    const beyond = c.channel_beyond?.[i];
    const near = r & 4 && (beyond == null || beyond === 255 ? 'channel in reach' : `${beyond}${beyond === 254 ? '+' : ''} blocks beyond a channel`);
    const parts = [r & 2 ? 'stream' : r & 1 && 'river', near, r & 8 && 'bar'].filter(Boolean);
    add('river', parts.join(', ') || '—');
    add('mouth', c.mouth[i] ? `${Math.round((c.mouth[i] / 255) * 100)}%` : '—');
  }
  if (c.water_body) add('water id', c.water_body[i] ? `#${c.water_body[i]}` : '—');
  rule();
  if (c.plate) add('plate', `#${c.plate[i]}`);
  if (c.province_type) add('province', name('province_type', c.province_type[i]));
  if (c.province) add('cell', cellOf(m.provinceCells[c.province[i]]) || '—');
  if (c.coast) add('coast', name('coast', c.coast[i]));
  const w = c.winner[i];
  add('feature', featureName(m, w, c.feature[i]));
  if (state.b) compareRows(add, rule, at);
  if (t.stages) {
    rule();
    for (const s of m.stages) {
      const d = t.stages[`delta_${s.name}`][i] / m.deltaUnits;
      const reason = s.reasons[t.stages[`reason_${s.name}`]?.[i] ?? 0] || '';
      if (d === 0 && !reason) continue;
      const change = d ? `${d > 0 ? '+' : ''}${d.toFixed(2)} ` : '';
      add(s.name, `${change}${reason}`.trim());
    }
  } else {
    add('stages', 'loading…');
  }
  const body = $('inspector-body');
  body.replaceChildren(
    ...rows.flatMap((r) => {
      if (!r) {
        const hr = document.createElement('div');
        hr.className = 'rule';
        return [hr];
      }
      const dt = document.createElement('dt');
      dt.textContent = r[0];
      const dd = document.createElement('dd');
      dd.textContent = r[1];
      return [dt, dd];
    }),
  );
  $('inspector-title').textContent = `X ${Math.floor(state.inspect.x)}  Z ${Math.floor(state.inspect.z)}`;
  // Stand on the water where the column is wet, else one above its surface.
  const y = Math.max(c.surface[i], wet(c, i) ? c.water[i] : -32768) + 1;
  state.teleport = `/tp @p ${Math.floor(state.inspect.x)} ${y} ${Math.floor(state.inspect.z)}`;
  panel.hidden = false;
}

// ---------------------------------------------------------------------------------------------
// Interaction

function blockAt(px, py) {
  return {
    x: state.cx + (px - canvas.clientWidth / 2) / state.scale,
    z: state.cz + (py - canvas.clientHeight / 2) / state.scale,
  };
}

function minScale() {
  const m = state.m;
  const size = Math.max(m.width, m.height) * m.step;
  return Math.max(1, Math.min(canvas.clientWidth, canvas.clientHeight)) / size / 2;
}

function zoomAbout(px, py, factor) {
  const before = blockAt(px, py);
  state.scale = clamp(state.scale * factor, minScale(), MAX_SCALE);
  const after = blockAt(px, py);
  state.cx += before.x - after.x;
  state.cz += before.z - after.z;
  changed();
}

function fit() {
  const m = state.m;
  const sw = m.width * m.step;
  const sh = m.height * m.step;
  state.cx = m.minX + sw / 2;
  state.cz = m.minZ + sh / 2;
  const fitted = Math.min(canvas.clientWidth / sw, (canvas.clientHeight - 60) / sh) * 0.96;
  state.scale = clamp(fitted, minScale(), MAX_SCALE);
  changed();
}

let hashTimer = 0;
function changed() {
  schedule();
  clearTimeout(hashTimer);
  hashTimer = setTimeout(writeHash, 250);
}

function writeHash() {
  if (!state.m) return;
  const p = new URLSearchParams({
    r: state.m.id,
    p: state.projection,
    x: Math.round(state.cx),
    z: Math.round(state.cz),
    s: state.scale.toPrecision(4),
  });
  if (state.b) {
    // The comparison: B's id, the view mode, the swipe divider and the toggle's side.
    p.set('c', state.b.m.id);
    p.set('cm', state.compare.mode);
    if (state.compare.mode === 'swipe') p.set('cs', state.compare.split.toFixed(3));
    else if (state.compare.showB) p.set('cb', '1');
  }
  history.replaceState(null, '', `#${p}`);
}

function readHash() {
  const p = new URLSearchParams(location.hash.slice(1));
  return {
    r: p.get('r'),
    p: p.get('p'),
    x: p.has('x') ? Number(p.get('x')) : null,
    z: p.has('z') ? Number(p.get('z')) : null,
    s: p.has('s') ? Number(p.get('s')) : null,
    c: p.get('c'),
    cm: p.get('cm') === 'toggle' ? 'toggle' : 'swipe',
    cs: p.has('cs') ? clamp(Number(p.get('cs')) || 0.5, 0, 1) : 0.5,
    cb: p.get('cb') === '1',
  };
}

const pointers = new Map();
let drag = null;

canvas.addEventListener('pointerdown', (e) => {
  try {
    canvas.setPointerCapture(e.pointerId);
  } catch {
    // Synthetic pointers (tests, some assistive tools) cannot be captured; dragging still works.
  }
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  drag = { moved: 0, start: performance.now(), pinch: pointers.size > 1 ? pinchState() : null };
  canvas.classList.add('dragging');
});

function pinchState() {
  const [a, b] = [...pointers.values()];
  return { d: Math.hypot(a.x - b.x, a.y - b.y), mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 };
}

canvas.addEventListener('pointermove', (e) => {
  const prev = pointers.get(e.pointerId);
  if (!prev) {
    hover(e.clientX, e.clientY);
    return;
  }
  const dx = e.clientX - prev.x;
  const dy = e.clientY - prev.y;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  drag.moved += Math.abs(dx) + Math.abs(dy);
  if (pointers.size > 1) {
    const now = pinchState();
    if (drag.pinch) {
      // The ground under the fingers' last midpoint stays under their new midpoint.
      const held = blockAt(drag.pinch.mx, drag.pinch.my);
      const ratio = now.d / Math.max(1, drag.pinch.d);
      state.scale = clamp(state.scale * ratio, minScale(), MAX_SCALE);
      state.cx = held.x - (now.mx - canvas.clientWidth / 2) / state.scale;
      state.cz = held.z - (now.my - canvas.clientHeight / 2) / state.scale;
    }
    drag.pinch = now;
  } else {
    state.cx -= dx / state.scale;
    state.cz -= dy / state.scale;
  }
  changed();
});

function release(e) {
  if (!pointers.has(e.pointerId)) return;
  pointers.delete(e.pointerId);
  if (pointers.size === 0) {
    canvas.classList.remove('dragging');
    if (drag && drag.moved < 8 && !drag.pinch && performance.now() - drag.start < 600) inspect(e.clientX, e.clientY);
    drag = null;
  } else if (drag) drag.pinch = pointers.size > 1 ? pinchState() : null;
}

canvas.addEventListener('pointerup', release);
canvas.addEventListener('pointercancel', release);
canvas.addEventListener('pointerleave', () => ($('cursor').textContent = ''));

canvas.addEventListener(
  'wheel',
  (e) => {
    e.preventDefault();
    const delta = e.deltaMode === 1 ? e.deltaY * 33 : e.deltaY;
    zoomAbout(e.clientX, e.clientY, Math.exp(-delta * 0.0015));
  },
  { passive: false },
);

canvas.addEventListener('dblclick', (e) => zoomAbout(e.clientX, e.clientY, 2));

function inspect(px, py) {
  if (!state.m) return;
  const b = blockAt(px, py);
  state.inspect = { x: b.x, z: b.z };
  showInspector();
  schedule();
}

function hover(px, py) {
  if (!state.m) return;
  const b = blockAt(px, py);
  const at = columnAt(state.a, b.x, b.z);
  let text = `X ${Math.floor(b.x)}  Z ${Math.floor(b.z)}`;
  if (at) text += `  Y${at.t.core.surface[at.i]}  ${name('habitat', at.t.core.habitat[at.i])}`;
  $('cursor').textContent = text;
}

window.addEventListener('keydown', (e) => {
  if (e.target instanceof HTMLSelectElement || e.target instanceof HTMLInputElement) return;
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  const pan = Math.min(w, h) / 4 / state.scale;
  const keys = {
    ArrowLeft: () => (state.cx -= pan),
    ArrowRight: () => (state.cx += pan),
    ArrowUp: () => (state.cz -= pan),
    ArrowDown: () => (state.cz += pan),
    '+': () => zoomAbout(w / 2, h / 2, 1.5),
    '=': () => zoomAbout(w / 2, h / 2, 1.5),
    '-': () => zoomAbout(w / 2, h / 2, 1 / 1.5),
    t: () => flipCompare(),
    Escape: () => {
      setDrawer(false);
      closeInspector();
    },
  };
  if (!keys[e.key] || !state.m) return;
  e.preventDefault();
  keys[e.key]();
  changed();
});

window.addEventListener('resize', schedule);

$('zoom-in').addEventListener('click', () => zoomAbout(canvas.clientWidth / 2, canvas.clientHeight / 2, 2));
$('zoom-out').addEventListener('click', () => zoomAbout(canvas.clientWidth / 2, canvas.clientHeight / 2, 0.5));
$('zoom-fit').addEventListener('click', fit);
$('menu-open').addEventListener('click', () => setDrawer(true));
$('menu-close').addEventListener('click', () => setDrawer(false));
$('inspector-close').addEventListener('click', closeInspector);
$('inspector-tp').addEventListener('click', async () => {
  const button = $('inspector-tp');
  try {
    await navigator.clipboard.writeText(state.teleport);
    button.textContent = 'copied';
  } catch {
    button.textContent = state.teleport;
  }
  setTimeout(() => (button.textContent = '/tp'), 1500);
});
$('legend-toggle').addEventListener('click', () => {
  const legend = $('legend');
  const collapsed = legend.classList.toggle('collapsed');
  $('legend-toggle').setAttribute('aria-expanded', String(!collapsed));
});
$('opt-grid').addEventListener('change', (e) => {
  state.grid = e.target.checked;
  schedule();
});
$('render').addEventListener('change', (e) => open(e.target.value, null));

function closeInspector() {
  state.inspect = null;
  $('inspector').hidden = true;
  schedule();
}

function setDrawer(open) {
  $('drawer').hidden = !open;
  $('menu-open').setAttribute('aria-expanded', String(open));
  if (open) $('drawer').querySelector('[aria-pressed="true"], button, select')?.focus();
}

const phone = () => window.matchMedia('(max-width: 640px)').matches;

// ---------------------------------------------------------------------------------------------
// Menu

function buildMenu() {
  const box = $('projections');
  box.replaceChildren();
  let group = '';
  const groups = { delta: null, reason: null };
  for (const p of allProjections()) {
    const button = document.createElement('button');
    button.className = 'choice';
    button.dataset.id = p.id;
    button.setAttribute('aria-pressed', String(p.id === state.projection));
    button.addEventListener('click', () => choose(p.id));
    if (p.group === 'delta' || p.group === 'reason') {
      if (!groups[p.group]) {
        const details = document.createElement('details');
        const summary = document.createElement('summary');
        summary.textContent = p.group === 'delta' ? 'Stage height changes' : 'Stage reasons';
        details.append(summary);
        box.append(details);
        groups[p.group] = details;
      }
      button.textContent = p.group === 'delta' ? `${p.name} · ${p.stage.activeLandPct}% of land` : p.name;
      if (p.id === state.projection) groups[p.group].open = true;
      groups[p.group].append(button);
      continue;
    }
    if (p.group !== group) {
      const h = document.createElement('div');
      h.className = 'group';
      h.textContent = p.group;
      box.append(h);
      group = p.group;
    }
    button.textContent = p.name;
    box.append(button);
  }
}

function choose(id) {
  state.projection = id;
  for (const b of document.querySelectorAll('#projections .choice')) b.setAttribute('aria-pressed', String(b.dataset.id === id));
  showLegend();
  changed();
  if (phone()) setDrawer(false);
}

function facts(m) {
  const size = m.width * m.step;
  return [
    `seed ${m.seed} · terrain-v${m.terrainVersion}`,
    `${size.toLocaleString('en-US')} blocks around ${m.minX + size / 2}, ${m.minZ + (m.height * m.step) / 2}`,
    `${m.step} block${m.step > 1 ? 's' : ''} per pixel · land ${m.landPct}%`,
    `rendered ${m.created} UTC · digest ${(m.digest || '').slice(0, 12)}`,
    m.note,
  ]
    .filter(Boolean)
    .join('\n');
}

function label(e) {
  const note = e.note ? ` · ${e.note}` : '';
  return `seed ${e.seed} · v${e.terrainVersion} · ${e.size} @${e.step} · ${e.created.slice(0, 10)}${note}`;
}

async function manifest(id) {
  const response = await fetch(`renders/${id}/manifest.json`, { cache: 'no-cache' });
  if (!response.ok) throw new Error(`Could not load render ${id}`);
  return response.json();
}

async function open(id, view) {
  $('status').textContent = 'Loading…';
  let m;
  try {
    m = await manifest(id);
  } catch (error) {
    $('status').textContent = error.message;
    return;
  }
  const previous = state.m;
  if (state.a) state.a.dead = true;
  state.a = new Layer(m);
  state.m = m;
  queue.length = 0;
  // A comparison survives a change of A only while both still share the window and grid.
  if (state.b && (state.b.m.id === id || !sameGrid(m, state.b.m))) dropCompare();
  else if (state.b) state.diff = diffTables(m, state.b.m);
  if (!allProjections().some((p) => p.id === state.projection)) state.projection = 'relief';
  $('render').value = id;
  $('render-name').textContent = `seed ${m.seed} · terrain-v${m.terrainVersion} · ${m.step} b/px`;
  $('render-facts').textContent = facts(m);
  $('status').textContent = '';
  buildMenu();
  buildCompareMenu();
  showLegend();
  const inside = (x, z) =>
    x >= m.minX && z >= m.minZ && x < m.minX + m.width * m.step && z < m.minZ + m.height * m.step;
  if (view && view.x != null && view.s) {
    state.cx = view.x;
    state.cz = view.z;
    state.scale = clamp(view.s, minScale(), MAX_SCALE);
    changed();
  } else if (previous && inside(state.cx, state.cz)) {
    // Comparing renders of the same ground keeps the view where it was.
    state.scale = clamp(state.scale, minScale(), MAX_SCALE);
    changed();
  } else fit();
  if (state.inspect) showInspector();
}

// ---------------------------------------------------------------------------------------------
// Compare: a second render of the same window and grid, as a swipe, a toggle or a difference.

const GRID_KEYS = ['minX', 'minZ', 'step', 'width', 'height', 'tile', 'apron'];
const sameGrid = (a, b) => GRID_KEYS.every((k) => a[k] === b[k]);
// The catalogue's window fields; the manifests settle the rest when B opens.
const sameWindow = (a, b) => ['centerX', 'centerZ', 'size', 'step'].every((k) => a[k] === b[k]);

function buildCompareMenu() {
  const select = $('compare');
  const here = state.catalogue.find((e) => e.id === state.m.id);
  const others = here ? state.catalogue.filter((e) => e.id !== here.id && sameWindow(e, here)) : [];
  const none = document.createElement('option');
  none.value = '';
  none.textContent = others.length ? 'none' : 'no other render of this window and grid';
  select.replaceChildren(none);
  for (const e of others) {
    const option = document.createElement('option');
    option.value = e.id;
    option.textContent = label(e);
    select.append(option);
  }
  select.disabled = !others.length;
  select.value = state.b ? state.b.m.id : '';
  for (const b of document.querySelectorAll('[data-compare-mode]')) {
    b.setAttribute('aria-pressed', String(b.dataset.compareMode === state.compare.mode));
    b.disabled = !state.b;
  }
}

async function setCompare(id) {
  if (!id) {
    dropCompare();
  } else {
    let m;
    try {
      m = await manifest(id);
    } catch (error) {
      $('status').textContent = error.message;
      return;
    }
    if (!sameGrid(m, state.m)) {
      $('status').textContent = 'That render samples another window or grid; it cannot be compared.';
      setTimeout(() => ($('status').textContent = ''), 3000);
      buildCompareMenu();
      return;
    }
    if (state.b) state.b.dead = true;
    state.b = new Layer(m);
    state.diff = diffTables(state.m, m);
  }
  if (!allProjections().some((p) => p.id === state.projection)) state.projection = 'relief';
  buildMenu();
  buildCompareMenu();
  showLegend();
  if (state.inspect) showInspector();
  changed();
}

function dropCompare() {
  if (state.b) state.b.dead = true;
  state.b = null;
  state.diff = null;
}

function setCompareMode(mode) {
  state.compare.mode = mode;
  buildCompareMenu();
  changed();
}

function flipCompare() {
  if (!state.b || state.compare.mode !== 'toggle') return;
  state.compare.showB = !state.compare.showB;
  changed();
}

// The divider and the A/B chip follow the comparison's mode; drawn with every frame.
function showCompareChrome() {
  const swipe = $('swipe');
  const chip = $('compare-chip');
  const b = state.b;
  const mode = !b ? '' : projection().diff ? 'diff' : state.compare.mode;
  swipe.hidden = mode !== 'swipe';
  chip.hidden = !mode;
  if (!mode) return;
  const v = (m) => `v${m.terrainVersion} ${(m.digest || '').slice(0, 6)}`;
  const a = state.a.m;
  if (mode === 'swipe') {
    swipe.style.left = `${Math.round(state.compare.split * canvas.clientWidth)}px`;
    chip.textContent = `A ${v(a)} ◂ ▸ B ${v(b.m)}`;
  } else if (mode === 'toggle') {
    chip.textContent = state.compare.showB ? `Showing B · ${v(b.m)} ⇄` : `Showing A · ${v(a)} ⇄`;
  } else chip.textContent = `B ${v(b.m)} against A ${v(a)}`;
  chip.disabled = mode !== 'toggle';
}

$('compare').addEventListener('change', (e) => setCompare(e.target.value));
for (const b of document.querySelectorAll('[data-compare-mode]'))
  b.addEventListener('click', () => setCompareMode(b.dataset.compareMode));
$('compare-chip').addEventListener('click', flipCompare);

// Dragging the divider's handle moves the swipe; the map under it does not pan.
const handle = $('swipe-handle');
handle.addEventListener('pointerdown', (e) => {
  e.stopPropagation();
  try {
    handle.setPointerCapture(e.pointerId);
  } catch {
    // As on the canvas: synthetic pointers cannot be captured.
  }
  handle.dataset.dragging = 'true';
});
handle.addEventListener('pointermove', (e) => {
  if (handle.dataset.dragging !== 'true') return;
  state.compare.split = clamp(e.clientX / canvas.clientWidth, 0, 1);
  changed();
});
for (const type of ['pointerup', 'pointercancel']) handle.addEventListener(type, () => delete handle.dataset.dragging);
handle.addEventListener('keydown', (e) => {
  const move = { ArrowLeft: -0.02, ArrowRight: 0.02 }[e.key];
  if (!move) return;
  e.preventDefault();
  e.stopPropagation();
  state.compare.split = clamp(state.compare.split + move, 0, 1);
  changed();
});

async function start() {
  if (phone()) $('legend').classList.add('collapsed');
  const response = await fetch('renders/index.json', { cache: 'no-cache' });
  state.catalogue = response.ok ? await response.json() : [];
  if (!state.catalogue.length) {
    $('status').textContent = 'No renders published yet.';
    return;
  }
  const select = $('render');
  for (const e of state.catalogue) {
    const option = document.createElement('option');
    option.value = e.id;
    option.textContent = label(e);
    select.append(option);
  }
  const hash = readHash();
  if (hash.p) state.projection = hash.p;
  const id = state.catalogue.some((e) => e.id === hash.r) ? hash.r : state.catalogue[0].id;
  state.compare = { mode: hash.cm, split: hash.cs, showB: hash.cb };
  await open(id, hash);
  if (hash.c && hash.c !== id && state.catalogue.some((e) => e.id === hash.c)) {
    // A difference projection exists only once B is open.
    await setCompare(hash.c);
    if (hash.p && allProjections().some((p) => p.id === hash.p)) choose(hash.p);
  }
}

start().catch((error) => {
  $('status').textContent = 'The map failed to load.';
  console.error(error);
});
