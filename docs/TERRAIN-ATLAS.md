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

## Setup

```powershell
python -m venv .local/atlas-venv
.local/atlas-venv/Scripts/python -m pip install -r tools/requirements-atlas.txt
```

The Java toolchain and Gson are the ones `riven.ps1 build` already uses. Sources compile with
`--release 8` into `.local/terrain-atlas/build/<source hash>`. Raw samples are reused only when
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

Window options: `--seed`, `--center X Z`, `--size` (blocks), `--step` (blocks/pixel), `--threads`
(default half the logical cores, which sampled faster than all cores) and `--threshold` (blocks).
Source options: `--ref` samples any commit from a cached `git archive`. `--set
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
| `elevation.png`, `biomes.png`, `climate.png`, `water.png`, `cost.png` | Context layers; `cost.png` is the per-column wall time |
| `summary.json`, `raw/` | Coverage, activity, reasons, sites, fingerprints, digest; little-endian layers plus `atlas.json` |

To browse renders in a browser, serve the folder locally:
`python -m http.server 8737 --bind 127.0.0.1 --directory .local/terrain-atlas`.

## Findings (seed 8675309, terrain-v36, 2026-10-21)

- Land is 14% of the default 8 km square, and the origin is open ocean. Across seeds 8675309, 101,
  -991, 7, 42 and 1234, land in a 4 km square ranges from 0% to 16%.
- About 90% of raw site anchors are vetoed by `Sites.admits` neighbor competition (mountains
  116/122, hills 185/206, carvers 110/121). Most survivors are marine.
- In the major stages, 86% of land columns record shore protection (`COLUMN_PROTECTED`,
  `PROTECTED_SHORE` or `SHORE_RESERVED`). The shared predicate rejects columns within
  `1.5 x coastal_plain` width (768 blocks) of the coast; this seed's landmasses are a few kilometres
  across. The few admitted land sites are rejected there, and one landmark is built.
- Volcanic, cold, plateau, mountain, hill, general-cut, dryland, rocky and sediment stages change
  no sampled land column. Regional passes and shore shaping are the active stages.
- Raising `sites.anchorProbability` to 1.0 changes 0.016% of feature pixels. Raising
  `minimumSeparationInCells` from 1.8 to 3.5 cuts admitted sites from 15 to 5 with no terrain
  change. Both are consistent with the shore reservation and marine placement being the binding
  limits.
- Terrain-v26 has the same feature picture; height differs on 3.5% of pixels
  after the v31-v36 relief/elevation refactors.
