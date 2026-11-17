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
| `compare --vs-ref REF / --vs-set ...` | The same window from two sources: height change, changed features, stage activity and reasons |
| `seeds --seeds 1 2 3` | One window per seed, with a contact sheet, `stats.csv` and means |
| `sweep --param FILE:PATH --values a b c` | One definition value across settings, with a contact sheet |
| `section --line X1 Z1 X2 Z2` | Height after every stage along a line, with the largest-change stage underneath |
| `map [--deploy] [--note TEXT]`, `map --remove ID`, `map --redeploy` | One window cut into tiles for the map site, https://map.orthrus.cc; `--remove` drops a render, `--redeploy` publishes the site as it stands (below) |

Window options: `--seed`, `--center X Z`, `--size` (blocks), `--step` (blocks/pixel), `--threads`
(default half the logical cores, which sampled faster than all cores) and `--threshold` (blocks).
Source options: `--ref` samples a commit from a cached `git archive`, compiled with the working
tree's sampler, so it must share the current layout and stages (the sampler keeps no support
for older trees). `--set
FILE.json:path.to.key=VALUE` patches a packaged definition for that render only. It must address
an existing value, and the patched copy precedes the packaged one on the classpath. Values that are
hard-coded in Java (for example the `1.5 x coastal_plain` shore reservation) need a reviewed move
into their definition before they can be swept.

Outputs live under `.local/terrain-atlas/{renders,compare,seeds,sweeps,sections}/`:

| File | Shows |
| --- | --- |
| `index.html` | All maps with legends, the site table and per-stage reason maps |
| `viewer.html` | Self-contained viewer: layer switching, pan/zoom, hover inspector with every stage's change, family and reason, pin and `/tp` copy |
| `features.png` | Per land column, the stage that changed height most, colored by stage and family |
| `sites.png` | Every raw `Sites` anchor: vetoed, marine, built or rejected (with the stage reason at the anchor) |
| `stages/<stage>.png`, `stages/<stage>_reasons.png` | Signed height change and recorded reasons per stage |
| `elevation.png`, `biomes.png`, `variants.png`, `climate.png`, `water.png`, `cost.png` | Context layers; `terranes.png` paints geology's terrane inputs (volcanic potential red, craton green, spine weight blue; rasters `geo_*.f32`); `variants.png` colours biome variants over faded biomes (raster `variant.u8`, 0 = none, names in the metadata's `variants`); `cost.png` is the per-column wall time |
| `summary.json`, `raw/` | Coverage, activity, reasons, sites, fingerprints, digest; little-endian layers plus `atlas.json` |

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
- Compare (drawer, "Compare with (B)") opens a second published render of the same window and
  grid (origin, step, size) over the open one (A): a swipe, whose divider shows B to its right, or
  a toggle (the chip or `T` flips A and B). Four difference projections read both renders' tiles
  pixel for pixel: height change (B minus A), and changed biome, feature and water (gained, lost,
  level moved). Biomes, stages and families are matched by name, so renders of different terrain
  versions compare; the inspector adds B's values. Nothing new is published for it, and the URL
  keeps B, the mode, the divider and the toggle's side.
- Pins mark a column with a short note (up to 200 characters). Tapping a column offers "Pin" in
  the inspector; such pins live in the link (`pin=X,Z,note`, repeated), so sharing the link shares
  them. A render can also carry curated pins in its `pins.json`, drawn white (the link's are
  amber): `map --render ID --pin X Z "note"` adds one (a pin on the same column replaces it), and
  `map --render ID --pins FILE` replaces them all from a JSON list of `{x, z, note}`; without
  `--render` the pins go to the render the same command publishes. Re-publishing a render keeps
  its pins, and the catalogue counts them so the page fetches only files that exist. Tapping a
  pin, or picking it from the drawer's list, opens its note, its coordinates and the `/tp` copy.
- Game overlays draw the world spawn (a star), tour views (a dot and a stroke where the view
  looks) and recorded flight paths (a line from a ring to an arrowhead) over any projection; the
  drawer switches each kind (the URL keeps the switched-off ones as `oh=`), and tapping an item
  names it. `map --render ID --overlay SOURCE` (repeatable) adds them to the render's
  `overlay.json` (`tools/atlas/overlay.py`): a tour file under `tools/tours` gives its views; a
  capture's `result.json`, or an instance directory holding `runs/*/result.json`, gives the
  client's recorded positions (each scenario's spectator flight observations, about one a second);
  a world's `level.dat`, or a world or instance directory holding one, gives its spawn. Items take
  the seed of their capture's workload, their world or their tour file's name (`<name>-<seed>`),
  and the page leaves out items of another seed; adding a source again replaces its items. Labels
  are published, so they keep only the capture's, world's or tour's short name, never a path. The
  atlas itself does not compute spawn: the game picks it (`RivenSpawn`) when it creates a world.
- `map --world` renders the whole canonical world: seed 2485331 (or `--seed`), layout scale 4,
  32768 blocks about the origin, 32 blocks per pixel (or `--step 64`). On a 16-thread desktop it
  took 21 s and 7.2 MiB (42 files) at 32 blocks per pixel, 18 s and 2.0 MiB (10 files) at 64.
  Finer renders of the same seed, terrain version and layout scale whose window lies inside a
  coarser one are listed under "Fine layers" and drawn over it inside their windows (dashed), at
  the zoom levels their own pyramid reaches; zoomed further out the coarse render alone shows, and
  the inspector reads the finest layer under the tap. All candidates are on unless the link says
  otherwise (`f=` lists the ones on). Both sample the same columns where their grids meet: a
  4 km window at 2 blocks per pixel (46 s, 17.6 MiB) matched the 32-block world on all 16384
  shared samples.
- The site is built in `.local/map/site` and deployed as the assets-only Worker `orthrus-map`
  (`tools/atlas/map/wrangler.jsonc`, through the wrangler login). Renders are content-addressed
  (seed, version, window and digest) and tile URLs carry the tile format, so tiles are cached as
  immutable; the catalogue and manifests revalidate.

## Findings (seed 8675309, terrain-v36, 2026-10-21)

- Land is 14% of the default 8 km square, and the origin is open ocean. Across seeds 8675309, 101,
  -991, 7, 42 and 1234, land in a 4 km square ranges from 0% to 16%.
- About 90% of raw site anchors are vetoed by `Sites.admits` neighbor competition (mountains
  116/122, hills 185/206, carvers 110/121). Most survivors are marine.
- In the major stages, 86% of land columns record shore protection (`COLUMN_PROTECTED`,
  `PROTECTED_SHORE` or `SHORE_RESERVED`). The shared predicate rejects columns within
  `1.5 x coastal_plain` width (768 blocks) of the coast; this seed's landmasses are a few kilometres
  across. The few admitted land sites are rejected there, and one landmark is built.
- Volcanic, cold, mountain, hill, general-cut, dryland, rocky and sediment stages change
  no sampled land column. Regional passes and shore shaping are the active stages.
- Raising `sites.anchorProbability` to 1.0 changes 0.016% of feature pixels. Raising
  `minimumSeparationInCells` from 1.8 to 3.5 cuts admitted sites from 15 to 5 with no terrain
  change. Both are consistent with the shore reservation and marine placement being the binding
  limits.
- Terrain-v26 has the same feature picture; height differs on 3.5% of pixels
  after the v31-v36 relief/elevation refactors.
