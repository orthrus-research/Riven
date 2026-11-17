# Terrain atlas

`tools/terrain_atlas.py` paints seed-driven 2D maps of what the generator actually selects, without
loading Minecraft. Use it to iterate on terrain definitions; keep game loads for checkpoints.

## Accuracy and determinism

`tools/checks/TerrainAtlas.java` reimplements no selection. Each pixel is one complete
`TerrainMap.sample`, the same call native cubes (`RivenWorldType`) and the DH preview make.
Biomes are `Habitats.at` on that sample, which equals the final `surfaceHabitat`. Site overlays
call the real `Sites.admits`. The private lane choice and anchor probability are read
reflectively, not copied. Reasons are each stage's own `reason` field after sampling. Stage
deltas are consecutive `Result.height` differences in pipeline order.

Every deterministic layer and the site decisions feed a SHA-256 digest. `render --verify`
re-samples the window in reverse row/column order on a different thread count, with fresh memo
state, and fails unless the digests match. This was verified for the default 8 km window and for
4x4 footprint renders on 2026-10-21. Per-column timing is the only nondeterministic layer and is
excluded. The atlas at spawn column (1472, -1472) gave surface Y68; the game spawned the player at
Y69.

`render --progressive` levels are exact subsamples, not approximations. Each coarse step is a
multiple of `--step` that divides `--size`, so every level has the same origin and coarse pixel k
samples the same column as fine pixel k x (coarse step / `--step`): a coarse level takes exactly
the samples a coarser render takes. Coarse levels sample with footprint 1. After the final level
the command compares every deterministic layer of each coarse level with the final one taken
every k-th sample (categorical codes by name, since each window numbers its own lists; footprint
maxima and water body ids are not compared) and fails on any difference.

Point samples can miss features narrower than `--step`. `--footprint n` also takes n x n samples
per pixel and keeps each stage's largest change; `features.png` then uses those maxima.
`--walls` also samples each pixel's neighbours one block east and south and keeps the largest step
to them (`walls.u8`), at three times the sampling cost; the map site then offers a Walls
projection, steps of 2 or more that a walker cannot climb (`tools/checks/TraversalCheck.java`
measures them per window).

## Setup

```powershell
python -m venv .local/atlas-venv
.local/atlas-venv/Scripts/python -m pip install -r tools/requirements-atlas.txt
```

The Java toolchain and Gson are the ones `riven.ps1 build` already uses. Sources compile with
`--release 25` into `.local/terrain-atlas/build/<source hash>`. Raw samples are reused only when
compiled sources, overrides and window are identical (`--resample` forces a fresh sample).

## Commands

Run with `.local/atlas-venv/Scripts/python tools/terrain_atlas.py <command>`:

| Command | Purpose |
| --- | --- |
| `render [--verify] [--footprint n]` | One window: all maps, reason maps, sites, cost, summary and `viewer.html` |
| `render --progressive [STEP ...]` | The same window at coarser steps first (default: the coarsest power-of-4 multiple of `--step` that still gives 32 pixels, then 4 x `--step`), each painted and announced as it lands, then `--step`; each level is a normal render with reusable raw samples |
| `compare --vs-ref REF / --vs-set ...` | The same window from two sources: height change, changed features, stage activity and reasons |
| `seeds --seeds 1 2 3` | One window per seed, with a contact sheet, `stats.csv` (with overall cost per column), `cost.csv` (cost by landform and climate region) and means |
| `sweep --param FILE:PATH --values a b c` | One definition value across settings, with a contact sheet |
| `sweep --param A --values ... --param B --values ... [--metric PATH]` | Two values as a grid: `sheet.png` (rows A, columns B), `heatmap.png` and `grid.csv` of one `summary.json` number (default `landPct`; `stages.<name>` is that stage's active land share), each cell's statistics under `cells/` |
| `section --line X1 Z1 X2 Z2` | Height after every stage along a line, with the largest-change stage underneath |
| `query --render DIR --where EXPR [--csv FILE]` | Columns of a render where a boolean expression holds: prints the count and share, writes `query.png` (matches over hillshade) and `query.json` to `DIR/query/<hash>/`, and the matches' `x,z` to `--csv`. Names: every raster in `raw/` by file stem, flag bits (`marine`, `supplied`, `deepOcean`, `wet`), `slope` (rise per block), `x`, `z`, and `stage.<name>.delta` / `.reason`; categorical rasters compare with names, e.g. `landform == "MOUNTAIN" and slope > 1` or `stage.cut.reason in ("ACCEPTED", "NO_SITE")`. Operators: `and or not`, comparisons (chained), `in`, `+ - * / %`, `abs()`; the expression is parsed and checked against that whitelist, never evaluated as Python |
| `history --refs REF ...` | The same window at each ref and the working tree: `history.csv`, a chart per summary metric and a contact sheet; a ref the sampler can't build is a skipped row with its reason |
| `hunt --stage\|--biome\|--variant NAME`, `hunt --coverage` | Ranks coarse windows by how much of the target is active, with ready-to-run render lines; `--coverage` proposes a golden window set (written to `.local` only) |
| `map [--deploy] [--note TEXT]`, `map --remove ID`, `map --redeploy` | One window cut into tiles for the map site, https://map.orthrus.cc; `--remove` drops a render, `--redeploy` publishes the site as it stands (below) |

Window options: `--seed`, `--center X Z`, `--size` (blocks), `--step` (blocks/pixel), `--threads`
(default half the logical cores, which sampled faster than all cores) and `--threshold` (blocks).
Source options: `--ref` samples a commit from a cached `git archive`, compiled with the working
tree's sampler, so it must share the current layout and stages (the sampler keeps no support
for older trees). The sampler builds main commits back to e97352a (2026-11-11); older trees
lack the geology rasters. `--set
FILE.json:path.to.key=VALUE` patches a packaged definition for that render only. It must address
an existing value, and the patched copy precedes the packaged one on the classpath. Values that are
hard-coded in Java (for example the `1.5 x coastal_plain` shore reservation) need a reviewed move
into their definition before they can be swept.

Outputs live under `.local/terrain-atlas/{renders,compare,seeds,sweeps,sections,history,hunt}/`:

| File | Shows |
| --- | --- |
| `index.html` | All maps with legends, the site table and per-stage reason maps |
| `viewer.html` | Self-contained viewer: layer switching, pan/zoom, hover inspector with every stage's change, family and reason, pin and `/tp` copy |
| `features.png` | Per land column, the stage that changed height most, colored by stage and family |
| `sites.png` | Every raw `Sites` anchor: vetoed, marine, built or rejected (with the stage reason at the anchor) |
| `stages/<stage>.png`, `stages/<stage>_reasons.png` | Signed height change and recorded reasons per stage |
| `elevation.png`, `biomes.png`, `variants.png`, `climate.png`, `water.png`, `cost.png` | Context layers; `terranes.png` paints geology's terrane inputs (volcanic potential red, craton green, spine weight blue; rasters `geo_*.f32`); `variants.png` colours biome variants over faded biomes (raster `variant.u8`, 0 = none, names in the metadata's `variants`); `cost.png` is the per-column wall time |
| `summary.json`, `raw/` | Coverage, activity, reasons, sites, cost, fingerprints, digest; little-endian layers plus `atlas.json`. `cost` gives per-column wall time (median, p95, max, columns) overall, `byLandform` (marine columns as `marine`) and `byRegion`; maxima include cold plan construction |

To browse renders in a browser, serve the folder locally:
`python -m http.server 8737 --bind 127.0.0.1 --directory .local/terrain-atlas`.

## Map site

`map` publishes renders to https://map.orthrus.cc, a pan-and-zoom map for phones and desktops.
Its defaults are an 8 km window at one block per pixel, every column sampled: about five minutes
(four sampling, one tiling) and 170 MiB of tiles. The site keeps every render it has published,
listed newest first. The page is anyone-with-the-link
and kept out of search indexes (`X-Robots-Tag`, `robots.txt`).

- `tools/atlas/tiles.py` quantizes every sampled field to an integer and cuts the render into a
  pyramid of 256-pixel tiles with a one-pixel apron. Each tile is two zlib files: the core fields
  every projection reads, and each stage's change (in sixteenths of a block) and reason. Level L
  takes every 2^L-th sample, so a coarser level is exactly what a coarser render samples there.
- `tools/atlas/map/` is the page: the hamburger menu picks the render and the projection (relief,
  hillshade, steepness, walls (renders sampled with `--walls`), biomes, climate, temperature,
  moisture, rivers, water bodies, plates, provinces, coast types, land level and character, selected
  features, and every stage's change and reasons). Colours are computed in the browser from the
  fields, so switching projection fetches nothing; tapping a column lists its sampled values. The
  URL keeps the render, projection and view.
- The site is built in `.local/map/site` and deployed as the assets-only Worker `orthrus-map`
  (`tools/atlas/map/wrangler.jsonc`, through the wrangler login). Renders are content-addressed
  (seed, version, window and digest) and tile URLs carry the tile format, so tiles are cached as
  immutable; the catalogue and manifests revalidate.

## Findings (terrain-v148, 2026-11-17)

From `seeds` with its default seeds (8675309, 101, -991, 7, 42, 1234) over the 4 km square at the
origin, 32 blocks per pixel:

- Land is 21% to 66% of the square (seed 101 lowest, 8675309 highest); median land surface is
  Y97 to Y156.
- `Sites.admits` neighbour competition vetoes 91-92% of raw site anchors on every seed (8675309:
  1097 of 1207). Cold anchors are almost all vetoed (396-397 of 400 per seed). Of the 99-110
  admitted sites per seed, 28-63 stand on land and 12-29 change terrain at their anchor.
- Passes (43-71% of land), mountains (14-29%) and shore shaping (17-38%) are the active stages.
  Cold changes no sampled land column in this square on any seed (8675309's land reasons:
  `SHARED_BUDGET` 64%, `WARM_COLUMN` 28%), volcanic at most 0.08%, and the other stages 3.2% or
  less.
