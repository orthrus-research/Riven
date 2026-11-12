# Features

One section per generator layer, in pipeline order. Ordering and planning
boundaries are in [ARCHITECTURE.md](ARCHITECTURE.md); licenses in
[THIRD-PARTY.md](../THIRD-PARTY.md). "Activity" is the share of land each stage changes by at
least one block in the default atlas render of seed 8675309 (8 km square, 16 blocks per pixel).

## World scale

- **Layout scale** (`WorldScale`, v66): the definitions are tuned for 8 km worlds. A per-world
  factor of 0.5-8 from the generator options (`scale=4`; `generator-settings` in
  server.properties, `runtime.py prepare --scale`, `terrain_atlas.py render --scale`) multiplies
  the continental layout: the continent lattice, radius, warp, bridges, bays, shelter reach and
  strait blend (crests and crest nodes follow in continent radii); temperature and
  moisture wavelengths and belt; plate cells and volcanic provinces; climate forcing's upwind
  step and lattice. Coast detail is self-similar: above 1 octaves at 2.3 and 5.3 detail
  wavelengths (amplitude 2 and 4) fade in with log(scale)/log(2.3); below 1 the base octaves and
  the coastal blend shrink with the scale. Drainage grids cover each continent's own land, found
  by a coarse scan of its support, so they grow with the layout. Landforms, coastal plains,
  rivers, mountains, massif relief and sites keep their physical size (`TerrainScale.HORIZONTAL`), so a larger world has more of them. Scale 1 is
  bit-identical to v65. Unknown options fail rather than defaulting.
  Measured (`LandShareCheck`, 64 seeds, the world square of 8 km times the scale): land 50.3 /
  50.3 / 49.7 / 49.6% and largest landmass 49.1 / 49.1 / 47.8 / 48.6% of land at scale 0.5 / 1 /
  4 / 8, with a world-spanning mass in 16-19 of 64 seeds. At scale 4 (32 km), before drainage replaced them
  (v91): 94-154 trunk rivers per world on 4 seeds (21-39 at scale 1), mean 2.4-2.9 km long since
  v67 (0.7-1.0 at scale 1); crest weight above 0.3 on 3.5% of land (3.4%); no base or
  mountain-surface seams; dense 512 x 512 windows 1.3 s highland and 0.8 s lowland on 8 threads
  (scale 1: 0.8 and 1.2 s); a native scale-4 world loads (server Done in 5.2 s). The atlas golden
  set includes a 32 km window (`world32k-8675309`).

## Climate

- **Every climate in every world** (terrain-v129, climate-v6): at 32 km each of the 15
  temperature x moisture combinations forms at least 3 separate land patches of 1 km2 or more,
  and frozen land averages 15-20%, checked over 16 seeds by `ClimateCoverageCheck` (all pass;
  frozen 19.7%, worst 26.8%). Climate lengths follow the world's layout raised to
  `layoutExponent` 0.5, so a 32 km world stretches them 2 times, not 4, and holds about two
  temperature and two moisture belt cycles (belt periods 9,000 blocks before scaling); the
  temperature noise is +/-9 C; the lapse rate starts 120 blocks above the sea (the S4 bands lifted
  uplands to +90 and tablelands to +130); and the moisture profile gives the cold bands more
  contrast (1.8 at -15 C, 1.5 at 5 C) and dries the polar edge (-0.1 at -15 C). Where a world's
  geography still leaves a climate short, the maps draw the belts' phase again
  (`ClimateCoverage`, `coverage` in `riven-climate.json`, up to 6 draws, golden-ratio shifts of
  each belt, nothing else): the first draw in which every climate forms 3 patches on a 128-cell
  grid is kept, else the one with the most for its rarest climate. A pure function of seed and
  definitions; 15 of 16 worlds keep their first draw. The paragraphs below give the 8 km
  tuning these scale from.
- **Temperature** (`TemperatureMap`, v45, `riven-climate.json`): a 16 km latitude belt (+/-19.5 C,
  offset so the origin sits on the 10 C mean) plus +/-7 C warped noise at 2,500 blocks; five bands
  (below -5, -5 to 5, 5 to 15, 15 to 25, 25 C and up). Tuned for 8 km worlds: about 20% FROZEN,
  60% COOL to WARM, 20% HOT in 8 km and 32 km windows, with no two-band jump within 512 blocks.
  Measured on land over six 8 km seeds: 17% frozen and 20% hot before province offsets, 14%
  (11-16%) and 19% (13-28%) after them (terrain-v106). A 32 km world (layout scale 4)
  stretches every climate length with the world, so it keeps the same bands and shares, 4 times
  larger.
- **Lapse rate** (climate-v5, terrain-v108): at intent, after the province offsets, a land column
  cools 0.2 C for every block its coarse base height (before landforms and fine detail) stands more
  than 70 blocks above the sea: 6 C at +100, 18 C at +160. Most land lies below +70, so lowland
  climate keeps its tuning while high ground turns cold, snowy and cold-forested inside warm bands.
  Over six seeds frozen land rose from 14.3% to 19.7%, and north-south position's correlation with
  land temperature fell from 0.978 to 0.927 (height now explains about 14% of it, 4% before); hot
  desert went from 7.5% to 6.6% and rainforest from 6.6% to 5.6%, as some hot highlands cooled.
- **Moisture** (`ClimateMap`, climate-v3, terrain-v96): independent noise at 2,000 and 800 blocks
  (weights 1 and 0.35) plus an east-west belt (amplitude 0.15, 16,000-block period, wet in the west,
  dry in the east: half a cycle per 8 km world) across the temperature belt, normalized to 0..1;
  dry below 0.35, wet from 0.65 (the climate map's own bands). Relative wetness, not rainfall. Until
  terrain-v96 the noise alone at 4,000 and 1,500 blocks put two lobes in an 8 km world, so a whole
  climate band could fall dry: seed 8675309 had no boreal, cold rainforest or temperate woodland
  climate and 52% dry land. Over six seeds (8675309, 101, 42, 7, 2024, -991) forest climates now
  hold 50-75% of land (mean 61%, was 51% with a 31-69% spread) and dry ones 16-31% (was 14-52%).
  Since terrain-v107 (climate-v4) a temperature profile in data shifts moisture and scales its
  contrast by the column's temperature: dry subtropics (-0.13 at 22 C, -0.16 at 26 C), contrast
  up to 2 between 26 and 30 C, and a wet hottest edge (+0.1 from 33 C). Over six seeds hot desert
  rose from 4.2% to 7.5% of land, dry from 18.6% to 26.2% and rainforest from 5.2% to 6.6%, wet
  land holding at 28%. A shift alone reached 7% desert but cut rainforest to 3%, since hot land
  is mostly 25-31 C and desert and rainforest compete for it.
- **Climate structure** (`ClimateStructureMap`, v35): land obstruction above the sea datum,
  `landWeight * max(0, meanSolidY + 1 - datumY)`, from the outline and base elevation only.
  Marine ownership gives zero obstruction; out-of-domain points return unavailable values.
- **Climate forcing** (`ClimateForcingMap`, v36, `riven-climate-forcing.json`): DERIVED policy
  samples eight upwind points (768-block spacing, 6,144 reach) along fixed transport (0.8, 0.6),
  response 64, strengths +0.20/-0.25, faded by land weight. LEGACY reproduces background climate
  exactly. Missing coverage stays neutral. Since v53 the eight upwind obstructions interpolate
  from a 32-block lattice in a bounded memo (the column's own read stays exact): dense generation
  is about 2.3 times faster per column, mean moisture moves 0.00007 (at most 0.0044) and 0.05% of
  land changes moisture class. Before v53 the upwind outline reads were 55% of generation time.
  The memo keeps the lattice in 16 x 16-node tiles of 2 KB, each built whole on its first read, in
  16,384 slots: 12 players spread over 8 km read 545,139 nodes (2,527 tiles), which 16,384
  single-node slots rebuilt about 36 times per pass.
  On coast-rich worlds the forcing wets on balance: windward wetting reaches its +0.20 cap at the
  95th percentile of land, while lee drying passes -0.05 on under 2% (seeds 101 and 8675309,
  terrain-v106), so a lee cap of 0.45 moved dry land by 0.2 points. Rain shadows behind the plate
  mountains are an open item.
- **Classification**: 15 `ClimateRegion` classes (temperature x moisture) select terrain-qualified
  vanilla biome palettes in `riven-habitats.json`.

## Provinces

`ProvinceMap` (`riven-provinces.json`, terrain-v103) is the parent of biomes. It's a seeded
regional map of provinces about 1,400 blocks across (times the layout scale), on a jittered grid
read at warped coordinates: a broad warp (900 blocks at 3,600) and, since terrain-v131, three
fold-free shear octaves at the cell scale (1,400, 700 and 350 blocks, amplitudes 520, 260 and 130;
each a shear pair in its own golden-angle frame, so its Jacobian's determinant is 1). A broad warp
alone is nearly affine across one cell and leaves its edges straight bisectors; the shear octaves
bend them into lobed, natural borders. Each column belongs to its nearest province, with an intensity from 0
at the edge to 1 inside (350 blocks).

Every province has two faces, a land type and an ocean class, and each column takes the face of
its own ground. Islands in a mostly sea province and bays in a land one keep their kind.

The land type is the ground's shape, chosen once per world from facts no later shaping changes,
over every province's land:
1. **Spines and rifts:** provinces whose mean spine weight over their land reaches 0.15
   (terrain-v127; plate belts until v126) become range, the strongest first, up to range's share;
   provinces within 700 blocks of a divergent plate boundary give rift the same way.
2. **Foreland:** provinces within 1.25 cells of a range take foreland next, nearest first, up to
   its share, so mountains step down through foothills.
3. **Stable types:** the rest are placed by a seeded level field and their distance to the coast.
   One capacitated assignment gives each its nearest type with room: plain (low, coastal), basin
   (low, interior), upland (high) or tableland (highest). It fills each type first to its target
   share, the middle of its range scaled to the land left, then to its maximum.

The ocean face takes a class by sea floor and climate: warm or cold, shelf or deep, or frozen.

The type budget (`share` per type, `enabled`) is data, so a pack can remove a type or narrow its
range.
- A minimum the tectonics, the ranges or whole provinces cannot meet is logged as a shortfall,
  never forced.
- A type with a nonzero maximum may always hold one province.
- A province no type has room for falls to its nearest type and is logged as an overrun.

The world's server log gives the line `Riven provinces:`, and `/riven x z` reports the province,
its neighbour, its intensity and both climates.

**Climate.** Each province offsets climate by physical effects, never height. One offset serves
both faces, so climate doesn't jump at the coast.
- An interior weight rises smoothly from 0 at the coast to 1 at 600 blocks.
- Continentality dries the interior by up to 0.08 moisture.
- The sea wets the coast by up to 0.02 and pulls its temperature 5% of the way toward 14 °C,
  within ±4 °C. Most provinces on coast-rich worlds count as coastal, so these stay gentle: until
  terrain-v106 a pull of a quarter and wetting of 0.05 took frozen land from 17% to 7% and hot
  from 20% to 12% over six seeds.
- Rain shadows are already in the base climate (climate forcing).

Offsets blend between provinces within the edge width, so they change by at most 0.03 °C and
0.0011 moisture between neighbouring blocks. `TerrainMap.intent` applies them once, so every later
reader sees effective climate.

**Measured.** `ProvinceCheck`, 64 seeds, terrain-v104:
- At 8 km, 35.5 of 36 provinces hold land.
- Mean land shares: plain 22% (16–30), basin 7%, upland 24%, tableland 10%, range 15% (absent in
  1 world), rift 9% and foreland 14%.
- 10 seeds log a shortfall, mostly range where a world's belts are few, and none an overrun.
  32 km worlds give the same mix, with 9 shortfalls.
- A low type sits straight beside tableland or a range at 6–15% of type borders. Foreland, at its
  15% cap in many worlds, cannot line every range.
- The map builds in 217 ms on its own at 8 km.
- At terrain-v103, against terrain-v102: land unchanged, heights changed at 3.4% of an 8 km
  atlas's pixels, through stages that read climate.

**Ground (S4, terrain-v126).** Every land type has a band of ground heights above sea and a
relief recipe in `riven-provinces.json`: plain 5-35 blocks, basin 2-25, upland 60-130, tableland
90-170, range 50-110, rift 10-50 and foreland 30-90, with a relief height and ridged and
terraced shares. Each province draws one level inside its band; levels, relief and shares blend
across the 500-block edge with smooth weights. `ProvinceGround` holds these fields as a bounded
regional plan: a Catmull-Rom raster at a fifth of the edge width, with exact derivatives.

**Landform classes (terrain-v129).** Every land column carries one class from the ground model,
with its strength, for biomes to key on (`Terrain.Landform`, `landforms` in `riven-terrain.json`):
MOUNTAIN where the spine weight passes 0.4 (strength rising from 0.25 to 0.55), PLATEAU on a cap,
SCARP on its edge, else its province type's class: basin BASIN, plain PLAIN, upland HILLS,
foreland FOOTHILLS, rift RIFT (a tableland off its cap reads HILLS, a range off its spine
FOOTHILLS). Selector character follows the class (FLOOR, PLAIN, HILLS, PLATEAU, RIDGE; DUNES
where hot and dry), and `Fit.PLATEAU` reads the cap and scarp. Over 16 worlds at 32 km: foothills
29%, hills 20%, plain 17%, mountain 13%, rift 8%, plateau 7%, basin 6%.

**Gating on the new ground (terrain-v129).** Land stages raise and cut within one block above
the sea to the soft ceiling, not the tectonic intent's landform band, which the S4 ground left on
54% of land; mountain kernels read the spine as their uplift; water sources skip strong spines
instead of plate belts. Plateau caps stay calm: the passes and mountains fade on them, and a cap
yields to a spine as its weight rises from 0.25 to 0.55, so ranges keep their ridges across
tablelands. Passes' ridges thin on spines, which carry their own.

**Spines (terrain-v127).** Mountain ranges follow each landmass's own axis. `SpineMap`
(`riven-provinces.json` `spines`) finds the land's medial axis: the ridge of the distance to the
coast, where its smoothed slope falls below 0.35 (away from the axis it rises one block per
block). A range is the band around the axis, half as wide as the land's half width there, and as
strong as that half width allows: none below 600 blocks, full from 2,200 (32 km layout values).
So broad continents carry long, high ranges, and narrow land low ridges or none. The weight lifts
the ground by up to 170 blocks and pulls its relief and ridged share toward a range's
(`riven-base-terrain.json` `spine`). It's a pure regional plan on a 256-block grid, in 32 km
tiles with halos wide enough that every distance is exact: an exact Euclidean distance transform
of the sea (Felzenszwalb and Huttenlocher), three box blurs, the slope, then a feature transform
from the spine cells carrying their half width.

`BaseTerrainMap` (REGIONAL) builds the ground from the province level:
- A slow character field (700 blocks) leaves most of a province calm and makes patches rugged.
- Rolling relief carries the unridged share of the province's relief.
- Ridges carry the ridged share: three gradient-noise octaves (1,100, 524 and 250 blocks), each
  rotated by its own angle and read through a 450-block domain warp at 2,400 blocks, so crests
  curve and branch with no lattice axis. Smaller octaves ride on high first-octave crests.
  Ranges are never calm: they keep at least their ridged share of strength.
- Terraces step the terraced share into 14-block treads with short risers.
- A soft ceiling eases the top 48 blocks below the highest legal surface.

**Plateaus and palettes (terrain-v128).** A type's `plateau` share makes its level a cap: where
the blended plateau share, warped by a two-octave edge field (900 and 300 blocks, amplitude 0.3),
passes one half, the ground stands on the plateau's level, and elsewhere on the blend of the other
types, joined by a scarp 0.06 of the share wide (about 100 blocks at 32 km) with terraced steps
(`riven-base-terrain.json` `plateau`). The edge field leaves outlying mesas and notches canyons
into the rim; the cap keeps a tenth of the ground's amplitude and no ridges, and mountains fade
on it. Each type also carries a landform palette in blocks (`palette`): rounded hills (two
gradient octaves shaped into domes, 420 blocks), spurs (a ridged octave, 260 blocks), broad
swells (1,600 blocks) and hollows (a flat floor with dips below zero, 180 blocks); the character
varies hills and spurs by 0.6. Uplands carry hills 28 and spurs 8, foreland hills 14 and spurs
14, ranges spurs 15 on their flanks, plains swells 6, basins hollows 5. They replace the retired
hill and lowland kernels (`HillTerrain`, `LowlandTerrain`, `RegionalFabricMap`), which at 32 km
acted on 0% and 0.3% of land.

Every term carries its exact derivative (`RegionalReliefCheck`). Elevation classes read the
ground's landform position (`BaseElevationMap.position`), not intent. Plateau buttes
(`PlateauTerrain`) were retired in v126; tableland provinces carry that ground.

## Continents and oceans

- **Outline** (`ContinentalOutlineMap`, MIT kernels; `riven-continents.json`): every continent
  draws its own family (harmonic, spine or potential; Cellular retired in v125), a log-scaled
  radius multiplier of 0.3-1.7 skewed toward small ones, an area-preserving axis ratio up to 3
  and a coast roughness of 0.6-1.6. Cells of the 3,000-block lattice (times the layout scale) are
  occupied by chance, thinned and shrunk where one smooth cluster field is low, so continents
  gather in clusters with archipelagos between them and open ocean beyond (v125). Exact zero land
  weight is marine. Owns the outline only; no water admission or bed shaping.
- **Coast character** (v125): a coast style field (2,500 blocks times the layout scale) runs
  stretches of coast from calm (0.6) to rugged (4): it scales the large coast octaves, so rugged
  coast gains inlets within bays, while the two small octaves never exceed 1. The warp is shear
  pairs, fold-free and invertible. Each built continent plans its fjords and peninsulas once (a
  pure function of the cell in a bounded cache): mouths just offshore of where its own score
  crosses the coast on 24 rays of its shape frame, kept mostly on rugged coast and never where a
  neighbour's land holds the mouth; fjords wander inland 0.2-0.6 radii, pinch and swell from a
  flared mouth, fork, and sometimes continue as finger lakes. They are carved as the distance to a
  tapered tube with rough walls (full roughness within a quarter width of the wall, fading out by
  half a width, so the score has no step), so the score stays a coast distance (gradient p50 1.03,
  p90 1.92 at 32 km on 16 seeds) and a fjord is open coast, not bay shelter. Peninsulas grow from just
  inside the coast as tubes joined to the body. Islands: each cell draws up to 8 elliptical
  islands, scattered or in an aligned chain; an open-sea cell with islands is built island-only.
  Measured at 32 km on 16 seeds (`ShapeCheck`): solidity 0.66 (v124 0.70), 3 slivers per world
  (6); 46.4% land kept on 64 seeds (`LandShareCheck`), 1.67 draws per world, the largest landmass
  68% of land and spanning the world in 31 of 64.
- **Outline traits** (v58): each column carries its owner continent's family, size, axis ratio,
  roughness and interior depth (`ContinentalMap.Sample`) for later classification. Shelter is
  read from the outline (v59): within 450 blocks of the coast, the five-point Laplacian of the
  owner's shape score at a 225-block step measures coast concavity (about 0 on straight coasts,
  positive in bays and between lobes, negative off headlands), gated so the probes must find the
  shore; shelter rises from concavity 0.2 to full at 0.8. On 4 seeds 3.6% of coastal sea is
  lagoon-grade (shelter 0.18 or more), against 2.5% with legacy bays. `cape` is the convex side
  of the same probes on land (headlands, continent tips, small-island rims; about 10% of the
  450-block coastal band), at no extra cost; `/riven` prints every owner trait. Near a seam where
  two continents' scores come within 112 (a quarter of the shelter reach) of a tie, their concavities
  blend, half and half at the tie (v68): an owner switch made shelter and cape jump, which showed
  once cape shaped rock shores. `shoaling` (v68, `shoaling` in `riven-continents.json`) is how
  broad and shallow a coast's nearshore is: one low-frequency field (1,400-block wavelength) gives
  stretches of dissipative (1) and reflective (0) coast, about half each. It is continuous by
  construction; shelter, which changes within a few blocks offshore, stays a site-level trait.
  The ray-tube bay is a
  per-continent draw, off by default (`bayProbability` 0). Potential sea deepens with distance from its kernels instead of staying
  shelf-deep. Where continents meet at sea a smooth union (`straitBlend` 800) rounds strait
  floors; before v59 their creases drew straight deep lines between continents. A candidate fades
  out of both unions across the last transition width of its search window (v132): at the rim it
  still scored inside the blend of a deeper union, and entering it stepped strait floors by up to
  54 blocks.
- **Outline cost** (8 km atlas of seed 8675309, 8 threads, v58): 2.7 s sampling, median column 28 µs on
  land and 8 µs at sea. Feature plans (v125) are cached per kernel; a coast-heavy 8 km window
  samples 28% slower with them than without (51 s against 40 s), mostly from the longer coast
  through the coastal stages. Each scratch caches the continent
  cells of its query window (anchor, draws, eligibility and the family's geometry plan: phases,
  satellites, spine nodes, cell sites with bisectors and bridge tree, potential kernels), so
  repeated reads by climate, forcing, intent and normals stop recomputing them; entries are pure
  functions of the cell and results are bit-identical to the uncached kernel.
- **Ocean water** (`ContinentalMap`, v38): every marine column holds water to Y62; marine areas
  enclosed by land are inland seas at that datum, so no column is a dry basin. (Until v37 the
  per-column `MarineSupply` search admitted water and left 0-2.6% of marine columns dry.)
  Coastal normals are finite-difference estimates; coast distance is the outline score itself
  (v65). Dividing the score by its gradient jumped across creases where lobes or continents meet
  in the union max, which left one-block spikes and terrace steps in the coastal plain.
- **Ocean floors** (`OceanBed`, MIT `OceanFloors`; `riven-ocean-floors.json`): shelf, slope,
  ridges, trenches and banks from the reference; bank detail fades offshore. Since v125 the whole
  profile stretches offshore with the coast style at the column (`shelf` in
  `riven-continents.json`): 1.6 times on the calmest coast, 0.45 on the most rugged, so shelves
  are broad off calm coast and narrow off rugged coast instead of one ring around every shore. Fitted inside the
  continental deep band. Nearshore shoaling (v68): the bed rises toward a ramp from the shore to the
  shoal floor (Y47) over 48 blocks on reflective coasts to 176 on dissipative ones, blended back to
  the shelf by that reach, and never lowered. On 2 seeds, the share of coasts at most 5 blocks
  deep 32 blocks out went from 20% to 62%; before, most coasts passed the shoal floor within 16-24
  blocks, too steep for any spit or tombolo.
- **Coast plan** (`Shore`, `riven-coasts.json`, terrain-v105): the coast comes in segments.
  Sites sit on a jittered 500-block grid (times the layout); a site within half a cell of the
  coast is kept and moved onto the shore along the outline's normal, the rest are dropped, and
  each column within 350 blocks of the coast belongs to its nearest kept site, so segment edges
  cross the coast. A segment's type, band width and foot are chosen once at its site, from facts
  read 64 blocks inland before any shore shaping (the land's level and ruggedness, the outline's
  cape, headland, shelter and shoaling, the broad grade across the shore, moisture and warmth,
  and since v125 `craggy`, how rugged the outline's coast style is), as the most suitable type
  with each score shaken by up to 0.25. Craggy coast leans to cliff (+0.6), headland (+0.8) and
  platform (+0.3), calm coast to beach (-0.4 craggy) and marsh (-0.5), so fjorded stretches carry
  rocky forms. On 8 seeds at 8 km (`CoastCheck`): headland 6.4% of band columns (v124 3.9%),
  cliff 3.5% (2.4%), beach 59.6% (63.5%), marsh 16.0% (16.2%); stacks 1,085 (863), sea caves 537
  (415), spits 38 (79). A sea cave is admitted only where its whole axis is clear of reserved
  water and aquifers, and wins its footprint over the band's height forms, so its passage is
  never walled off; a spit or tombolo's line is checked for reserved water every block.
  - **Types and profiles** (from the waterline inland, to the inland height H): cliff (band
    30-60) and headland (60-120, on capes) keep a foot of up to 4 blocks at the sea and rise to H
    within a quarter of the band; bluff (80-150) rises over the whole band from a foot of 2-8;
    platform (60-120) keeps a bench a block above the sea over 40% of the band; beach (60-200)
    lays a berm two blocks above the sea over a quarter of the band, then rises gently; marsh
    (150-350) stays a block above the sea over 70% of it. Two segments blend their heights over
    60 blocks at their edge. Past the band the ground keeps its own height exactly: the
    proportional coastal scaling and the continental envelopes before v105 are gone.
  - **Kinds and weights:** the type's kind (cliff, rock, beach, marsh) is the shore kind while
    the column is near its shore; a beach's berm is beach and the rest of its band coastal plain.
    Cliff and rock weights name the stretch of coast: whole across the band, fading over one more
    band width, so feature admission reads the stretch as rugged ground. The band's rise is the
    shore's land weight.
  - **Envelopes** are band rules: inside a band a raising feature (hills and the major features)
    may rise no higher than the sea plus the land weight times the highest surface, bent softly
    above half that room; past the band it is free.
  - **Forms** (S3b-1, terrain-v110, shapes v111; `forms` in `riven-coasts.json`): each type
    lists its forms with the blocks of shore per slot. Cliffs take joint coves every 240 blocks,
    sea caves every 120 and stacks or arches every 140; headlands take stepped headlands every
    240 and stacks or arches every 140; platforms take cliff platforms every 200 and stacks or
    arches every 140. A segment's slots lie along its site's tangent across its cell, jittered,
    and each is moved exactly onto the shore (the coast crossing along the outline's normal,
    found by bisection to a quarter block) and kept only on the segment's own stretch.
  - **Height forms** (coves, stepped headlands, cliff platforms) are the MIT rocky kernels
    (`contrib.rocky`), placed rather than certified: each reads the cliff top one and one and a
    half kernel widths inland, drops by that cliff's own relief (so it never planes a tall cliff
    down), only lowers land, shares the segment's blend, fades over the band's last fifth and
    never lowers a channel's levee below its water.
  - **Stacks and arches** are Riven's own bodies, built from each column's own ground at any
    depth, no taller than the cliff behind their shore point and only in exposed water. A slot
    holds an arch one time in five, else a group of one to three stacks: the first 9-24 blocks out
    to sea, companions smaller and lower beside it along the shore. A stack's outline is lobed and
    slightly elongated (radius 4.5-8.5 blocks); its columns rise from the water at the outline to
    its height (16-44 blocks) a taper's width in, under a rough top, so the sides stand steep and
    the foot spreads into a ledge, and every column of its outline and a 2-block moat is open,
    supplied sea. An arch is a fin from 4 blocks inside the cliff to 16-30 blocks out, 3-5.5
    blocks half-thick, wandering and uneven, lowering toward its end, pierced by a ragged
    half-ellipse rising from the water under at least 4 blocks of roof, so the sea runs under it;
    its opening stands over open sea and its end in the sea.
  - **Sea caves** are tapering vaults 16-40 blocks long, 2-4 half-wide and 4-8 high from a block
    above the water, at least 8 blocks under the surface; nearer the face, where the roof would be
    thinner, the ground is cut down to the vault's floor, a notch at the waterline through which
    the cave opens. Neither opens an aquifer.
  - Within a segment no stack, arch or sea cave overlaps another's body (the form listed first
    keeps its place), and a body claims only the columns it builds. Forms leave protected, wet and
    banked water alone.
  - **Sediment** (S3b-2, terrain-v113; `sediment` in `riven-coasts.json`) comes from the MIT
    sedimentary kernels (`contrib.coast`), placed by a type's `sediment` list (beach: bench, spit,
    tombolo) against the ground the forms left. A segment's stretch is its own shore walked from
    its site both ways, 24 blocks a step along each point's own shore and snapped onto it, keeping
    the first point of the next segment so stretches overlap. A bench follows the whole stretch. A
    spit grows, six times in ten, from a stretch end beside a cliff or rock segment, rooted 2 blocks
    inland and drifting away from the rock. A tombolo ties a slot every 96 blocks to the nearest
    stack seaward (within 40 degrees of the normal) or to an islet (land with open sea all round
    within 48 blocks), one per segment. The segment's type is the kernel's admission; a spit or
    tombolo stands only where every node of its line is unreserved, no deeper than the shoal (15
    blocks) and no higher than a beach. Sediment leaves coast-form columns alone, takes each
    column's highest edit, only builds outside a bench, keeps channel levees, and sand rising from
    the sea is emergent. Bars and lagoons across bays wait for S5.
  - **Marsh** (S3b-2, terrain-v114; `sediment.creek` and `sediment.mud` in `riven-coasts.json`)
    is Riven's own: a marsh segment lists `MUD_FLAT` and `TIDAL_CREEK`. A creek grows, three
    times in four, from a mouth every 72 blocks along the segment's stretch (a point clear of
    each end), on open sea. Its first channel runs inland from two blocks out to sea, 1.5 to 3
    blocks in half width and 2 to 3 blocks deep at the mouth, wandering up to 18 degrees a
    3-block step within 55 degrees of its heading and 75 of inland, and narrowing to a 0.75-block
    half width and a block's depth over 60 to 180 blocks. Branches leave at 35 to 70 degrees,
    half to three quarters the size, to the third order, at most 24 channels a creek. A channel
    stops before a node off the marsh's flat (marsh share 0.5, no more than 2.5 blocks above the
    water's top), without a bank 2 blocks past either edge that holds the sea's water (land at or
    above the water's top or open sea), or within a bank of another of the segment's channels. A
    creek's columns stay land and keep the marsh's biome; they take the sea's water level as
    their own and are wet as a stream is (DECISIONS 2026-11-03), on a bed a block under the water
    at the edge and the channel's depth at its line. Mud (`riven-surface.json`'s mud row, three
    blocks deep) covers every creek's bed and the flat in patches: two octaves of value noise
    (24-block wavelength) plus 0.9 fading over 20 blocks from the shore and 0.9 over 4 blocks
    from a creek's edge, above 0.5. The mud is cover; it changes no height.
  - Marsh measured with `CoastCheck` (8 seeds of 8 km): per world about 77 creeks of about 6
    channels; every checked creek column stands under the sea's water on mud, its water meets no
    lower ground that does not hold it, and it joins open sea through water at that level.
    `SurfaceWaterCheck` spills are unchanged from v113, and in the assembled columns mud covers
    about 10,000 of `AssembledColumnCheck`'s 1.5 million columns.
  - Sediment measured with `CoastCheck` (8 seeds of 8 km): per world about 96 benches, 10 spits
    and 4 tombolos. Checked benches stay between the ground they found and a beach's heights,
    spits and tombolos only build, every emergent spit column joins land through the spit's sand,
    and each tombolo's sand reaches its far end.
  - Forms measured with `CoastCheck` (8 seeds of 8 km): per world about 108 stacks in groups, 12
    arches, 52 sea caves, 36 stepped headlands, 24 cove sets and under one cliff platform
    (platforms are mostly backed by land under 8 blocks above the sea, too low for a cliff form).
    Sampled stacks are one solid run from their own ground in open sea all round; arches have a
    roofed opening over the sea and feet on land and on the sea floor with no solid over air; sea
    caves keep their water line and roof band and open through a notch; height forms only lower
    land inside their band.
  - Measured with `CoastCheck` (8 seeds, 512 x 512 per 8 km world): 39% of land lies inside a
    band; of band columns, beach 61%, marsh 20%, bluff 12%, headland 3.9%, cliff 2.4%, platform
    1.6% (by segments, beach about 45% and the rocky types about 38%). Land past its band keeps its height exactly, and the coast's height has no seam (no jump
    of a block within 1/256 of a block). In the seed 101 atlas the land median rose from +35 to
    +47 above the sea and p25 from +12 to +24; land within 5 blocks of the sea grew from 11% to
    17% (berms, platforms and marsh flats). Land share is unchanged.
- **Land fraction** (v101, `LandShare`, `land` in `riven-continents.json`): a world's land share is the part of its square (8 km times the layout scale, centred on the origin) whose outline land weight is above zero, measured at world creation on 64 x 64 cell centres (under 0.2 s an attempt). A share outside the band (35-65%) redraws the outline from its next seeded attempt, which salts only the outline's own seed; the first attempt inside the band is kept, and after 8 the closest. The startup log reports it (seed 8675309: `Riven land: 40.0% (attempt 1 of 8)`). The mean comes from the draw: a 3,000-block lattice, radius 1,550 (0.52 spacing; 1,350 before v101) times each continent's size, every cell occupied (`stratifiedOccupancy` 4). On 64 seeds (`LandShareCheck`) 8 km worlds keep 50.3% land (35.7-62.7%; first draws 33.2-66.3%), 62 of 64 on the first attempt and none outside the band, 32 km worlds 49.7% (38.7-61.3%), 62 of 64 on the first; the largest landmass holds 49% of land and spans the world (touches three edges) in 19 of 64 seeds at 8 km, against 39% and 8 of 64 at radius 1,350. The 50% mean is a target the atlas measures, not a value the generator reads. Optional bays (900 blocks deep, 170 half-width) cut only their own continent; corridors every 12 cells stay outside a 32 km world.

## Inland base

- **Plates** (`PlateMap`, `riven-plates.json`, terrain-v104): a power diagram of one weighted
  site per 3,072-block cell (times the layout), each site anywhere in its cell with a weight up to
  0.45 squared cells, read at coordinates warped by up to 820 blocks over 4,600, so plates come in
  many sizes with curving boundaries. A boundary is convergent where the plates' relative motion
  along its normal is below −0.2, divergent above 0.2, shear between.
  - **Mountain belts:** convergent boundaries are the source of high ground. A belt's strength
    rises from 0 at motion −0.2 to 1 at −1.1 and reaches up to 620 blocks either side (a weak
    belt 45% of that), falling off smoothly. Slow fields at 1,700 blocks move strength along the
    belt (a factor from 0.15 to 1.9, about 1 on average, easing smoothly toward 1) and width by
    up to 45%, so ranges swell, narrow and break into massifs. Where belts overlap, the strongest
    counts.
  - **Uplift** is signed and continuous: tanh(compression / 1.9) of a smooth blend of the plates'
    velocities, raising where they converge and lowering where they spread. Readers that want
    only raising (geology, the mountain and volcanic stages, hydrology) take its positive part
    plus the belt.
  - **Measured** (`PlateCheck`, 32 seeds, 8 and 32 km):
    - 12.4 plates own at least 1% of a world (9–16), their sizes spreading 0.7 (std / mean).
    - 30% of land lies in a belt, 8% in a strong one.
    - 90–91% of high ground (base elevation 100 or more above the sea) lies in a belt, where the
      outline's crests coincided with plate convergence on 27%.
    - Uplift is nonzero on 97% of land (raised 52%, lowered 45%), where the one-sided uplift was
      zero on half the land.
    - A plate sample costs about 0.3 µs, with each thread memoizing plate sites and each owner
      plate's boundaries.
    - Native chunk generation costs the same within 4% as terrain-v103. An 8 km atlas samples
      18% slower.
- **Base elevation** (`BaseElevationMap`, v60, `riven-base-elevation.json` revision 4): mean
  elevation, legal bands and analytic gradients from plates, regional noise and the height
  reference. State is 0.40 + 0.3 signed plate uplift + 0.3 regional + 1.0 mountain belt (softly
  capped at 0.45; until terrain-v104 the owner continent's crest).
- **Regional relief** (`RegionalProfileMap`, `BaseTerrainMap`, v31; `riven-terrain-regions.json`,
  `riven-base-terrain.json`): bounded regional amplitude and spectral profiles. REGIONAL is
  packaged and builds on province ground ([Provinces](#provinces)); LEGACY is the exact earlier
  comparator.
- **Broad intent** (`Terrain`, `riven-terrain.json`): low/middle/high level and ruggedness
  character, read by every selector.
- **Erosion** (`ErosionFilter`, MPL-2.0): three directional octaves at 64, 32 and 16 blocks.
- **Substrate** (`SubstrateMap`, v30, `riven-substrate.json`): three material permissions, all
  0.9, read by drylands.

## Land stages

| Stage | Families | Definition | Activity |
| --- | --- | --- | --- |
| Regional passes (`RegionalTerrainPasses`) | hummocks, knolls, ridges, benches, dunes | `riven-terrain-passes.json` | 6.4% |
| Volcanic (`VolcanicTerrain`) | cone, caldera, fissure, lobe plateau | `riven-volcanic.json` | 3-12 volcanoes per 32 km world (v130): potential from rifts, the arcs along ranges (spine weight 0.15-0.5) and sparse hotspots, in any climate; only the core (0.4 of the radius) stands clear of water, rivers keep their beds through the skirt |
| Cold regions (`ColdTerrain`) | cirque, trough, drumlins, moraines | `riven-cold-regions.json` | 0.19% of land on seed 8675309 (prototype; a warm seed) |
| Mountains (`MountainTerrain`) | ridge tree, folded belts, summit graph, warped ridges; spine massif | `riven-mountains.json` | 13-16% of land at 32 km (v130: plans at nodes along the spines, where the range weight reaches 0.5, heading down the ridge; the massif rises by spine weight; the stage fades on plateau caps) |
| Landmarks (`RegionalLandmarks`) | capped hill, curved ridge, rimmed basin, fan apron, branching ravine, sculpted dune | `riven-landmarks.json` | 0.8% of land on seed 8675309 at step 16, 1.4x taller (prototype; 0.7% before) |
| Surface carvers (`SurfaceCarvers`) | 24 profiles (V ravine to en-echelon clefts) | `riven-surface-carvers.json` | 1.7% of land on seed 8675309, 1.5x deeper (prototype; 1.2% before) |
| General cuts (`GeneralSurfaceCuts`) | ribbon ravine, branch gully, pinched slot, breach saddle | `riven-general-surface-cuts.json` | 1-2 cuts per 8 km world (prototype; 0 before) |
| Drylands (`DrylandTerrain`) | crescents, crossing ridges, deflation pavement, yardangs | `riven-drylands.json` | 0% |

Shared rules for the land stages:

- Volcanic, cold, landmark, carver, cut and sediment features
  are owned by `Sites` lanes ([ARCHITECTURE.md](ARCHITECTURE.md#feature-sites)). Passes and
  drylands are continuous fields with no site; hills and lowland forms are the provinces'
  landform palettes ([Provinces](#provinces)).
- Major forms certify their admitted site against `sampleBeforeMajorFeatures` over the support
  plus an anchor and halo: headroom, water reservations, family-specific fit. Since v56 the
  certificate reads the parent on a shared world-aligned lattice every 8 blocks
  (`certificateLatticeBlocks` in `riven-terrain.json`), the same for native cubes and the DH
  preview; headroom tests and parent extremes widen by 8 blocks (`certificateHeightMarginBlocks`),
  water uses the analytic clearance over half the node diagonal, and volcanic slope is measured
  between nodes. A failed certificate leaves the site empty; there is no reroll. Cold admission
  also measures P95-P05 relief over a 256 x 256 window on the same lattice. On DH-style 64 x 64
  tiles (seed 8675309) this cut generation from 97 / 500 / 1,206 / 7,469 ms per tile at detail
  0 / 2 / 4 / 6 to 61 / 72 / 128 / 510 ms; built cold forms, mountains and plateaus per 8 km world
  are unchanged and volcanoes rise from 0.3 to 1.0.
- Mountains follow continent crests (v62, mountains-v4): every crest carries a chain of MIT
  mountain plans, 1.3 range half-lengths apart along it and aligned with its tangent, each from
  earlier intent at its node; a column takes the largest raise of the nodes within one
  half-length. The kernel's own fades (mountain intent from 0.55, uplift from 0.25, parent band
  from Y164, headroom) and per-column water preservation keep ranges continuous, so there is no
  site lane (`laneDensity` MOUNTAINS 0) and no footprint certificate. Final-surface slopes on
  mountains match at 1 and 0.25 block spacing (no seams; worst 4.5 per block). 8 km atlas of seed
  8675309: mountains 1.9% of land at v62 (0.8% with v61 sites; 1.0% at v63 with the wider coast fade), highest land Y272; dense 512 x 512
  windows on 8 threads 0.7 s on the highland and 1.2 s on lowland (v61: 1.4 and 1.5 s).
- Crest highlands are rugged, not flat (v64). Base elevation saturates the crest term softly
  (`crestCap` 0.45 in `riven-base-elevation.json`), so crest cores form a dome at position about
  2.4-2.8 instead of a table at the clamp (position 3.00 over about 1.1 km of the seed 8675309
  massif at v63). Mountain plans are twice as wide (1,024 x 2,252 blocks), and a massif term
  covers the whole crest band: four octaves of isotropic, domain-warped ridged noise (wavelength
  640 halving), 80 blocks times a crest mask 1 - exp(-1.5 crest), under the mountain ceiling
  (Y288) and fading over 64 blocks to water; kernel peaks combine with it by maximum. Ridges are
  not oriented along the crest: the crest gradient flips across the crest line and where the
  strongest crest switches, which stepped the surface (slopes per block grew from 21 to 64 at
  finer spacing). Mountain-stage activity covers 12% of land in the 8 km atlas of seed 8675309
  (relief of at least one block anywhere on crest bands). Mountain surface steps over 3 per block
  that remain come from the shore stage's cliff classes near coasts.
- Hills keep the coast with the coast band's envelope: their certificates accept coastal and
  marine support columns, and `TerrainMap` caps each hill raise with `Shore.envelope`. Volcanic,
  cold, plateau and mountain stages (v50) drop their shore-zone exclusions and skirts; `TerrainMap`
  caps each raise with `Shore.majorEnvelope`, the same band rule since v105, and they keep water,
  marine-anchor and marine-footprint exclusions. Since v65 carver and general-cut lowering eases to nothing within
  48 blocks of surface water (`cutWaterSkirtBlocks` in `riven-terrain.json`); a cut beside a river
  left the river's bank, held up so it cannot spill, standing as a fin. Cuts and
  drylands keep the shore reservation,
  `shoreReservationInCoastalPlainWidths` (0.5) coastal-plain widths in `riven-terrain.json`.
- Drylands require hot dry climate and attenuate around earlier major relief. Dunes in regional
  passes need hot dry climate and fade on steep slopes; benches need dry warm or hot climate.
- Volcanoes are reserved for hot biomes ([DECISIONS.md](DECISIONS.md)): since v55 the anchor column must be in
  `supportTemperatureBand` (HOT, 25 C and up) or the site is rejected (`CLIMATE_SUPPORT`), and the
  cone fades smoothly from full at 25 C to nothing at 20 C (`climateFadeFullCelsius`,
  `climateFadeZeroCelsius`). Since v54 marine columns leave the volcanic certificate and slope
  stencil, so cones may overhang the sea.
  Coverage gap: `VolcanicBoundaryCheck discover` finds no built volcano among 285 admitted owners
  in cells 0..512 of seed 8675309 (47 of 52 land anchors are shore-protected columns), so only
  `capture-rejections` applies.
- Surface rules put soil before rock (surface-rules-v9, terrain-v90). A rocky coast shows rock
  only within 20 blocks of the water (40 on a full cape) and on cliff faces within 40 blocks;
  inland of that it carries soil like any other ground (before, rock reached about 250 blocks
  inland and covered a fifth of all land). High ground shows rock above a treeline: Y 176 at
  5 C, 4 blocks higher per degree warmer and lower per degree colder, between Y 156 and Y 272,
  read from the climate before the lapse rate (terrain-v108), since the treeline has its own height
  rule. Two rates remain (this one is 0.25 C per block, the climate's 0.2 from 70 blocks above the
  sea); unifying them, as D34 intends, is open. Above a snowline (terrain-v109) snow covers the
  rock: a dry floor whose temperature at its own height, by the climate's lapse rate, is below
  -5 C takes snow: snow over stone (the crust's snowcap, which precedes bare stone) where the rock
  would show, snow over soil elsewhere. That puts the
  snowline near Y 158 in cool climates, Y 208 in mild, Y 258 in warm and Y 298 in hot ones;
  mountain landforms (intent 0.5 or more) show rock within 12 blocks below it. A cut shows rock
  when deeper than the three-block soil where nothing grows back, and only when deeper than 48
  blocks (a gorge) where plants return: plants need moisture above 0.18 plus 0.012 per degree
  above 10 C (so grassland and savanna regrow while desert and scrub stay rocky), and none return
  below -6 C. Bare rock on land, on a 32-block grid over 8 km: seed 8675309 29.5% to 4.7%, seed
  101 22.7% to 6.2%. Native blocks and DH use the same decision.

## Water

`Hydro` (`riven-hydrology.json`) plans up to four finite coastal networks (lake, flat outlet,
non-rising river to ocean Y62) per 2,048-block owner cell, the square cell that holds their
sources, plus up to three supplemental `Basin` bodies and one sealed `Lens`; basins and coast
shapes attach to the first network. Sources are tried in
upland order (48 attempts, 12 route directions, a 0.38 moisture floor, a route reach up to 1,228
blocks); each takes its first route that keeps clear of the networks already taken and whose lake
takes a natural `LakeShape` (v70; the legacy ellipse stood in steep walls on slopes). Every planned
lake drains to the sea. On 4 seeds there are 26 networks at 8 km and 304 at 32 km (about 6.5 and 76
per world; 7 and 87 at v69, 1.75 and 22 per world, when straight routes with a preset profile
failed 476 of 1,164 rays on cut, fill or fall rate). Like a drainage channel, a network's level is
the running minimum of the ground less one block from its lake head (v70): it never fills, crosses
hollows at their floor, cuts rises as gorges within the cut limit, and its bank widens into a valley
2.5 times the cut. Its outlet nodes hold the lake head (filling up to the fill limit) and run straight into the
lake's own outlet channel; a meandering first segment missed it and left a dry dam in 7 of 19 lakes.
A route has one node per reach step (about 77 blocks), at least four, so a short route keeps room
for its lake's outlet. A route marches out from the lake to the first supplied sea within its reach
and ends one segment past the coast (v69): before, a fixed-length ray had to end at sea inside the
owner cell, which 60% of rays failed, and the routes that fit ran on across the seabed as a wet
channel (mean 265 blocks at 8 km, now about 60). On a shoaling coast the mouth becomes the same
funnel estuary as a drainage river's, clear of the lake outlet, wherever the cut limit allows: 159
of 304 network mouths at 32 km, 145 of the 157 on shoaling coasts. It reads only intent, earlier groundwater head, moisture and reference dimensions; plans
never consult neighbors or finished terrain. A memo keeps immutable geometry: `cacheSets` x
`cacheWays` (512) plans at the 8 km layout, growing with the layout scale squared, since plans
keep their 2,048 blocks while continents scale (8,192 at 32 km). With 12 players spread over the
world, `RegionalCacheProbe` counts 122 plans at 8 km and 726 at 32 km.

**Drainage networks** (terrain-v91, `riven-hydrology.json` drainage, `Drainage`): every built
continent drains its own land on a grid of 32-block cells (a coarse scan of its support finds the
land first; in larger layouts the grid coarsens to at most 256 cells a side and keeps the head area
in blocks), over its intent height plus a gentle routing relief (12 blocks of noise at 800, 400 and
200 blocks) that gathers flats into valleys. A priority flood routes each cell to the sea or to the
lake or route of a cell network that receives it, so hollows fill and spill and nothing is left
without an outlet. Receiving water reads each own-land cell's hydrology plan, so the scan finds
its own land first, builds exactly the plans those cells read, one task per plan, and only then
reads receiving water: no row waits for another row's plan build (timing only). A network keeps
each channel's points (an array that is +0.0 along the whole course is left out, riffles as bytes)
and a bin index stored as runs of consecutive segments behind a bitmap: about 0.2 MB a continent
at 8 km and 6.6 MB at 32 km on seed 8675309 (`tools/checks/DrainageFootprint`). Rain-weighted area accumulates downstream, a channel begins where it drains 60 (18 until v128, when parallel streams on even slopes cut a washboard of valleys)
cells, and every channel continues the upstream channel that brings it the most water: heads join
into creeks, creeks into rivers. Width grows with the square root of the area drained (1.5 blocks
at a head, 14 blocks more per extra root square kilometre, at most 48), so rivers widen below every
junction; depth grows from 1 to 8. A channel's level is the running minimum of the ground less one
block from its head, never below the water it joins; its points sit at seeded spots within each
cell, smoothed twice, and a tributary ends on its main channel's centreline at that channel's level.
Channels 6 blocks wide or more are rivers: they reserve bed, bank and valley (2.5 times their cut)
like any river, so landforms keep their skirts, and they mark river-mouth weights; narrower streams
reserve nothing, so no landform site reads them, and they keep the land's biome. Every channel's
bank and valley is cut at the hydrology constraint from the column's own height: the valley widens
by 2.5 blocks per block the ground stands above the water (at most 48). A river on a shoaling coast
flares into a funnel estuary (up to 3.5 times its width over the last 320 blocks). Channel form:

**Drainage lakes** (terrain-v127, `riven-hydrology.json` drainage.lakes): a second priority flood
of each continent's own intent ground, from every outlet (the sea, receiving water, another
continent's land), fills each hollow to its spill level. The outlet cuts the rim by 12 blocks, so a
lake stands that far below its spill and only a hollow's deeper part stays wet; a shallow basin
drains through its gorge. A lake needs 6 grid cells below its level and stands above the sea.
Channels crossing a lake run at its level, so its outlet leaves at the spill. The shore is the
contour where the ground, interpolated between the lake's cells, meets the level; a 3-block bank
eases the ground to the water, and dry columns within 6 blocks of the level stand at least level
with it. Landforms keep clear of a lake by the distance to its nearest wet cell (less half a cell diagonal; v130, the whole box until then, which reserved entire ranges). On the canonical world at 32 km
they cover 4.0% of land (1.8-4.2% over eight seeds), most in the valleys and piedmonts of ranges.

- **Meanders:** a wave 11 channel widths long (at least 24 blocks, drifting 30% along the course)
  moves the course sideways by 0.3 of its length on flat ground, easing to none where the channel
  falls faster than 3%; a third harmonic leans bends downstream, and the wave fades in at the head
  and out at a junction or an estuary.
- **Pools and riffles:** the water holds flat in pools and falls in riffles instead of a block at a
  time all along the course. A unit runs at least 5 widths (16 blocks) and until the course has
  fallen 3 blocks; its riffle takes the fall over 3 blocks per block of drop, 1 block deep over
  gravel. Riffles are 19-20% of channel length.
- **Braided reaches:** a channel over 4 blocks wide (fully from 8) on frozen ground (below 2 C) or
  dry ground (moisture below 0.3), falling 0.6% to 6% (easing from 0.3% and out to 12%), spreads
  into a gravel bed up to four times its width where three shallow channels weave across bars at
  the water's level; an estuary's funnel fades them out. They take 21-32% of wide cold-river length on seeds 8675309 and
  101, about 10% of all river length.
- **Beds and point bars:** beds are sand where a channel falls less than 1% and gravel elsewhere and
  on riffles; the inside of a strong bend holds a point bar of the same material at the water's
  level.
- **Flush banks:** the columns within 1.5 blocks of a bed stand level with the highest water beside
  them, a riffle's step upstream or a neighboring channel included; network lakes and routes keep
  flush banks the same way, and no drainage valley cuts into them. `tools/checks/SurfaceWaterCheck`
  counts wet columns beside lower dry ground, where water would spill: 2 of 180,597 on seed
  8675309 and 6 of 372,732 on seed 101 (1,659 and 3,423 before these rules), where network rivers
  meet unbanked ground or a drainage valley; it fails above one in 10,000.

Drainage replaces the crest trunk rivers (v63-v68). Over 8 km, 36% of land on seed 8675309 lies
within 100 blocks of a channel (6.9% before) and 43% on seed 101; each seed has 500-770 streams and
22-30 rivers, and 20% of channel length is 4 blocks wide or more. A continent's network builds once,
the first time a column needs it. A build samples its grid rows in parallel (each cell a pure
function in its own slot, so any order gives the same network), reads only the continental map for
its coarse land scan, and a column that needs several unbuilt networks builds them together; the
continents within 16 km of the origin (59-61 on seeds 8675309, 101 and 5) build in 25-40 s on 32
threads, 61-87 s one at a time. Cold, on 4 workers, a new place's center column is ready in 3.3 s
and its 12-chunk view in 6.5 s (0.9 and 3.9 s at v90), and sampling a 16 km square every 64 blocks
takes 64 s (52 s at v90): each build also brings forward the hydrology plans of its whole
continent. `terrain_atlas.py render --verify` proves the networks independent of order and threads.
In game (`tools/measure_pipeline.py`, terrain-pipeline workload, 8 km out) the full 25-cube view
completes in 27.0 s (26.7 s at v90, 35.4 s before the parallel builds). A teleport into new
country made the server thread provide the arrival column at once, river networks included, a
2.0 s tick (0.16 s at v90); Cubature holds such a teleport while its workers prepare the
column (RUNTIME.md), so that tick takes 37-43 ms and the player arrives after 2.3 s.

River routing and sampling (v46):

- **Heads prefer mountains:** the planner scores each source attempt by intent (high weight 1.0,
  middle 0.4, uplift 0.5; `riverHeads`) and tries them in descending score, so the first valid
  source is the most mountainous one.
- **Meander:** each route segment carries one lateral bulge, `A sin(pi t)` with A = 3 channel radii
  scaled 0.6-1.0 per segment and sides alternating along the river from a seeded start (`meander`).
  The offset is zero at every node, so lakes, mouths and `RiverShape` bridges stay joined; it costs
  one sine per nearby segment. Plan bounds and `surfaceClearance` grow by the amplitude.
- **Mouths:** the plan records its mouth, the first route node at sea level; columns within
  `mouthRadiusBlocks` (192) get a mouth weight for the biome layer.

| Layer | Families | Definition | Notes |
| --- | --- | --- | --- |
| Lakes and ponds (`LakeShape`, v21) | harmonic, lobes, swept, cellular; optional islands, deep profile | `riven-lakes.json` | Ponds stay closed and groundwater-fed; connected lakes hand off to the river at the first flat segment |
| Rivers (`RiverShape`, v22) | bend, split, pocket, junction | `riven-rivers.json` | Shapes existing supplied segments; no catchment discovery |
| Lagoons and estuaries (`CoastShape`, v23) | lobed lagoon, crescent lagoon, branching estuary, funnel estuary | `riven-estuaries.json` | At existing supplied coastal sites; up to 1,048,576 extra columns per site |
| Basins | wetland, oasis, pond, lagoon | `riven-hydrology.json` | Wetlands need storage or groundwater; oases need hot dry climate and groundwater |
| Underground lake (`Lens`) | water chamber with an air dome | `riven-hydrology.json` `layers.lakes` | One per owner cell at most; see Caves |

Activity: rivers 0.01% of pixels, lakes 0% in the default render.

## Coastal formations

Coastal formations belong to the coast plan's segments: rocky forms since terrain-v110 and
sediment since terrain-v113 (see the coast plan, above). No coastal stage has a site lane.

## Biomes and blocks

**Logical biomes** (`Biomes`, v44, boundaries v49, `riven-biomes.json`): 260 biomes on an axis
grid. Temperature (5 cores, boundaries -5/5/15/25 C), moisture (3 cores, 0.40/0.62) and base
elevation (LOW, MID, HIGH split at Y106 and Y129) each add a transition position at every core
boundary (half-widths 1 C, 0.025, 3 blocks). The elevation boundaries sit near the land's
percentiles in 8 km worlds (below the coastal-plain height pile at Y109-112) and the moisture
boundaries at its 23rd and 61st (biomes-v4), favoring the moderate and wet climates that grow
forest; on 8 seeds 41% of land holds a transition position (58% at v48) and elevation splits
19/12/37/4/29% from LOW to HIGH (LOW was unused). 9 x 5 x 5 = 225 inland biomes, plus beach, rocky shore and marsh at 9
temperatures (27) and 8 water biomes. The director locks the three positions at the end of
`intent`, after base terrain; later stages read `BaseSample.biome` and never recompute climate
tests. `Biomes.resolve` applies only water (ocean, deep, frozen, warm shallows, river, lake,
wetland, oasis), shore (emergent or littoral rocky, sandy, marsh) and height overrides; height may
move the elevation position one step toward the final surface. On three seeds (32 km, habitat-level
probe) 217 of the 260 appear, at 5.17 bits; 59% of inland samples sit in a transition position.

**River mouths** (v46): where a column's mouth weight is at least 0.35 and it is shallow marine, on
the river, or within 96 blocks of the coast, the first matching mouth rule by climate core selects
FROZEN_DELTA, FJORD_MOUTH (cool, rocky shore), SALT_MARSH, TIDAL_FLAT, DELTA_WETLAND, MANGROVE,
SABKHA or ESTUARY (268 logical biomes). This precedes shore and shallow-water overrides but not
deep ocean; mangrove and salt marsh map to swampland until a registered subset exists.

**Vanilla mapping** (hybrid identity): inland biomes use the `riven-habitats.json` palette of their
core climate region and elevation level (the core on the value's side of each transition; HOT
DRY HIGH is `mesa_rock`), with the second entry on rugged columns; coastal and water biomes use the
vanilla table in `riven-biomes.json`. Registering a curated subset as real biomes is future work.
Native chunk biomes, biome queries and the DH preview all resolve through
`Habitats.at(TerrainMap.Sample)` via `NativeColumns`. The crust floor is at Y -512 (since terrain-v72, 400 blocks under the lowest surface for geology, ore and caves); sea level is Y63 with still water through Y62.

**Population** (population-v1, terrain-v123, `riven-population.json`, `FirstAnimals`): Riven's own
decorator, which adds what a chunk holds once its cubes exist and writes no block: a chunk column's
first animals. Groups are drawn while a draw falls under `groupChance` (0.1, vanilla's), at most
`maxGroups`; each takes a species by weight from the creature list of the riven: biome at the
chunk's centre (Riven biomes, spawn tables) and a size between that entry's minimum and maximum
(at most 8), and its members walk from a start column inside the chunk onto the columns' surfaces,
never water. The cube holding a member's feet spawns it where the block below carries it and its
feet and head are free of anything solid, so population reads only that cube and the cubes above
and below it (Cubature's population boxes: none to write into, one cube up and down to
pregenerate), and content stays independent of the order cubes populate in. Every random choice
an animal makes as it first spawns (sheep colour, horse coat and strength, the vanilla spawn bonus)
comes from its member's own draw, so two worlds of one seed hold the same animals; each carries its
origin (`riven:origin`: chunk, group, member) in its Forge data. Groups are drawn whether or not a
member stands, so the plan is fixed per chunk; a member whose column does not carry it is left out
rather than moved. `/riven animals [radius]` compares the plan with the world (planned, fitting,
found by origin, creatures without one) and counts what population has done since the server
started (cubes, members in them, fitting, spawned, refused); `/riven digest` also writes the digested
chunk columns' animals by origin with what they keep for life. Instances keep animals off for
terrain captures (`spawn-animals=false`, which removes every animal on its first tick); prepare with
`--spawn-animals` to see them. `runtime.py play --tour
tools/tours/standard-8675309.json` captures nine labeled views and closes the client; prepare
tour instances with `--capture-ticks 900`, since populated chunks take longer to arrive.
`tools/tours/separation-2485331.json` runs `/riven cover`, `/riven trees`, `/riven animals` and
`/riven digest` at seven stops of seed 2485331 at 32 km (prepare with `--scale 4`).

**Riven biomes** (prototype, terrain-v84, `riven-biome-registry.json`, `RivenBiomeTable`, `RivenBiomes`, `RivenBiomeTypes`): every column reports one of 61 `riven:` biomes, chosen from its habitat key: 30 inland by climate region and calm elevation (lowland for LOW and MID, highland for HIGH), 10 rugged by temperature band (hills, peaks), 13 coast and water biomes and 8 river mouths. Each extends the vanilla class of its kind (snow, taiga, plains, forest, savanna, jungle, desert, hills, mesa, beach, stone beach, swamp, ocean, river), which Demonica reads as its shader category and other mods test with `instanceof`; names avoid substrings shader packs misread ("end", "ice", "nether"); temperature, rainfall and precipitation drive vanilla weather; grass and foliage colors follow the vanilla color maps unless the world's mappings override them. They empty their decorators and surface hooks: Riven places every block itself. Every surface biome names a spawn table (`spawns`); a kind of mob its table lists (creature, monster, ambient) replaces its parent class's list, and the surface tables list creatures, starting as vanilla's lists for each parent class (savannas without llamas, as Riven's biomes keep vanilla's base height), which Population and natural spawning both read. Spawn search uses the 40 inland biomes. Underground, cubes report the 12 realm and depth biomes below (73 `riven:` biomes in all). A Riven world uses 135 of 256 biome IDs (62 vanilla, 73 Riven). `/riven biomes` lists them with IDs, parents and categories; `tools/checks/BiomeTableCheck.java` proves all 163 habitat keys select a biome.

**Biome variants** (prototype, variants-v1, terrain-v134, `riven-variants.json`, `Variants`): named
country inside parent `riven:` biomes that changes only what is decided after the biome. One
world-level field of patches (a Voronoi diagram of points jittered inside 500-block cells, over
coordinates warped by two octaves of smooth noise at 600 and 300 blocks, 110 blocks deep) gives
each patch one variant: a draw under `plainShare` (0.4) gives none, otherwise one of the variants
whose conditions the patch point's intent meets (effective climate ranges, province type, landform
class; a marine point meets none), by weight. Patch choices sit in a bounded memo of pure values. A
column has its patch's variant where its own biome is one of the variant's parents; within 16 blocks
of the bisector with the second-nearest patch it takes that patch's instead with a chance falling
from one half at the bisector, so edges dither. Tree sites try a variant's stands before the parent
biome's, ground cover uses a variant's entries in place of the parent's (their draws keyed by the
variant), and boulders a variant's owner where `riven-boulders.json` lists one; native cubes and the
preview call the same functions. The client never learns the variant, so it cannot change grass
colour, weather or spawns. Three variants: **Lichen Woodland** (boreal forest and snow taiga, -6 to
3 C, moisture 0.40-0.62, plain, basin and upland provinces): spruce and pine at under a fifth of
the boreal canopy over a reindeer-lichen carpet in drifts; **Tallgrass Prairie** (grassland, 5-15 C,
moisture 0.27-0.42, plains, basins, forelands and uplands on plain, basin, foothill and hill
landforms): tall meadow grass, prairie fescue and bottlebrush grass under sweeps of goldenrod,
aster and lupine, an oak in every few hundred cells (a prairie patch over dryland dunes or
pavement leaves that ground bare, as the parent does: no grass Riven has grows on inland sand);
**Pine Barrens** (temperate and warm
woodland, 8-22 C, moisture 0.35-0.50, plains, basins and forelands on plain, basin and foothill
landforms): open pine (an eighth of a cell's chance) with juniper over the heath palette, bracken and pine duff. On seed 2485331
at 32 km they cover 0.61, 0.25 and 0.19% of the world. `tools/checks/VariantCoverageCheck.java`
(ci job `variant-coverage`) requires every variant to have at least 3 patches in a parent biome's
climate in each of 8 worlds at 32 km (fewest now: 10, 4 and 10). The column report (`/riven`)
names the variant; `/riven find variant:<id>` finds one; the atlas writes `variant.u8` and
`variants.png`.

**Resource mappings** (prototype, mappings-v1, `riven-mappings.json`, `Mappings`, `RivenNetwork`): roles name materials (`wood.oak.log`), each an ordered list of block states whose first loaded entry wins; layers add or replace roles and riven: biome colors: Riven's defaults (Riven's own and vanilla blocks; every wood's log and leaves), integration layers for loaded mods, then pack layers in `config/riven/mappings/*.json` by file name. A world resolves them once, when it is created, into `data/riven/definitions.json` with a SHA-256 fingerprint, and loads that snapshot for life; each joining client receives its fingerprint and biome colors in one packet. `/riven mappings` compares the world's snapshot with the current layers.

**Boulders** (prototype, boulders-v1, terrain-v92, `riven-boulders.json`, `Boulders`): rock lying on
the land, made of the hardest unit within 64 blocks under each site's soil (hard rock outlasts its
surroundings; quartzite and granite first, shale and soapstone last, loose sand and gravel never;
without the near-surface climate tint), drawn by every cube and by the preview at fine detail (cells
under 4 blocks). Each 20-block cell holds at most one candidate; one within 9 blocks of a
higher-priority neighbor stands down, so footprints (at most 4 blocks from the center) never
overlap. The riven: biome at the center gives the chance (0.006 in grassland, 0.025-0.035 on cold
and dry uplands, 0.045-0.05 on polar ground, none on beaches, marshes and wetlands; a tenth of the
first tuning) and the kinds: domes (boulders 1-3 blocks tall, erratics up to 5
tall and 7 wide), tors (two to four slabs shrinking upward, up to 8 tall) and outcrops (rough spires
up to 5 tall). Every kind is a heightfield: each footprint column holds rock from its ground block
to the site's top there, so a boulder never overhangs, a tree site on it fails its sapling test, and
leaves, which fill only air, stop at it. A site stands only on dry land whose columns lie within 2
blocks of its center's ground. Over seed 8675309's 8 km world: 902 sites (600 boulders, 176
outcrops, 90 erratics, 36 tors), 43 per square kilometre of land, rock on 0.036% of land columns
(seed 101: 1,446 sites, 45 per square kilometre); the polar barrens hold 178, rocky shores 118,
scrubland 94, savanna 89. Around each tour site the boulders share one or two rocks (sandstone
across a polar field, quartzite on a steppe). `/riven boulders [radius]` compares the world with the
sites block by block.

**Tree sites** (prototype, trees-v3, terrain-v93, `riven-trees.json`, `TreeSites`, `TreeShapes`):
Riven decides where trees stand and draws every tree itself, so the distant preview shows exactly the
trees that load; no biome decorator runs in a Riven world.
Three layers of candidate cells are visited in order: emergent (16-block cells, trunks 9 apart),
canopy (6, 4) and understory (5, 3). A candidate stands down within its layer's spacing of a
higher-priority candidate of its layer or of a valid site of an earlier layer, so understory trees
stand under canopy and emergent crowns but never against their trunks. Each riven: biome lists
stands, the first whose climate holds at the candidate deciding; a stand gives each layer a chance
and species weights, which each species' niche scales by the column's climate (Species niches,
below). A site is valid on its species' ground (grass and dirt, mud included; palms and
mangroves also sand; cold conifers and birch also snow), every trunk column of a 2 x 2 trunk at one height, and with every column its tree reaches
open sky from the tree's lowest block there up (`NativeColumns.surface`, one column's query), so no tree grows into a
cliff, an overhang or water. 34 species over the 28 woods draw from four families: broadleaf (a
crown cluster at the trunk top and branches ending in clusters; willows hang strands; kapok,
mahogany and strangler fig stand on buttresses and mangroves on prop roots that keep only where
their foot meets the trunk's ground), conifer (rows of discs, cone, round or column, with whorl
gaps), palm (a leaning trunk with drooping fronds; tree ferns lean little) and umbrella (limbs under
flat discs: acacia, mesquite, the 2 x 2 baobab). Giants with 2 x 2 trunks stand in the emergent
layer: kapok to 30 blocks, mahogany, spruce, fir and cedar. Clusters keep every leaf within four
steps of a log, and generated leaves wait for a neighbor change before checking decay, as vanilla
trees do, so a felled tree's crown decays. Each cube writes the tree blocks inside itself during
generation, sites layer by layer in cell order: leaves fill only air, logs fill air or leaves, grass
under a trunk turns to dirt. The preview draws the same blocks, filtered to each column, over DH
cells up to 2 blocks wide, and over coarser cells the stand's most covering layer as a leaf run at
its mean crown heights where the layers' combined cover reaches one half. 38 of the 61 surface
biomes grow trees; peaks, deserts, polar ground, salt flats, rocky shores and open water do not.
`/riven trees [radius]` compares preview and world block by block and counts sites whose every log
stands; `/riven find <biome>` names the nearest column of a riven: biome.

**Forest signatures** (trees revision 6, terrain-v135): each forest's stand gives its layers a band
of visible tree heights (`heightBlocks` on the layer), so forests differ by stature rather than
sharing one band; a species keeps its own `heightBlocks` where a stand gives none and a
`limitBlocks` band it never leaves, and draws a stand band as stems, less the crown it measures
above its stem on sample trees, inside its limit (loading fails when a species cannot reach a
band). A tree's crown follows its stem through its growth form (below); palms and the umbrella
family scale fronds, limbs and discs by the square root of the stem's ratio to the species'
reference height (0.6 to 1.6), clusters held within four steps of a log. Cube classification and the
coarse preview canopy follow: `maximumTop` and `reach` bound every tree a species' limit allows at
its largest scale (loading fails at a reach of 16), and a layer's distant leaf run stands at the
crowns of its own band. Measured realised heights and densities (tree census, seed 2485331 at
32 km, every forest within its bands): tropical rainforest emergents 24-29 at 10/ha over a 14-19
canopy (239 stems/ha); temperate and cold rainforest conifer giants 22-28 over 13-19 and 14-18;
subtropical 13-17 under emergents 19-23; montane 12-16; temperate woodland 10-15 with veteran oaks
14-18 (153/ha); boreal forest dense spires 10-14 (196/ha); snow taiga 8-12; cloud forest stunted
7-12 (217/ha); wooded highlands 9-13; warm woodland and highlands open at 6-10 (83 and 71/ha);
boreal highlands 7-11 (94/ha, was 32), snow taiga highlands 6-10, misty highlands 5-9; wetlands
10-15; mangrove 5-8; savanna 6-9. Crown spread now follows height (Pearson r 0.64-0.90 by species,
was about 0). The temperate rainforest's emergents are conifer giants (cedar, spruce).
`tools/checks/TreeCensus` and `tools/tree_census.py` measure all of this (docs/RUNTIME.md).

**Growth forms** (trees revision 7, terrain-v136): a broadleaf names a growth form, an architecture
the engine draws, and its crown proportions (`crown`: cluster `radius` and `depth`, envelope
`width` and `base` as fractions of the tree's height, `leaders`, `fill`). DOME forks a third of
the way up into leaders and keeps a central leader (oak, beech, maple, mahogany, strangler fig);
VETERAN forks low into near-level limbs and loses its leader (old oak); OVOID keeps one leader
(birch, aspen, alder, rubber, rowan); WEEPING is a dome with strands (willow); EMERGENT holds a
clear bole under a broad flat crown (kapok, giant mahogany); MULTISTEM rises as stems from the base
(hazel, cacao, olive, mangrove). After the skeleton, twigs grow from the nearest skeleton log to
points spread through the crown envelope, as many as the envelope's volume over a cluster's times
the fill, each ending in a leaf cluster, so crowns fill their envelope and every leaf stays within
four steps of a log. Conifers give a crown `width` fraction too: spires (spruce 0.30-0.40, fir
0.25-0.32), tiered cedar, cypress as a column, pine as a flat top on a bare bole, juniper and dwarf
spruce as bushes. Every tree's shape varies by its id (fork height, leader count and directions,
twig placement). Census, seed 2485331 at 32 km: mean crown spread of domes about their height (oak
12.2 on trees 8-17 tall, old oak 17.7, strangler fig 14.2), of ovoids about half (birch 5.7);
leaves per hectare at most 1.95 times v135 in any forest (temperate rainforest; 1.47 overall),
inside the approved cap of double; every forest signature still holds. `tools/checks/TreeGallery`
renders every species' side elevation across its band (docs/RUNTIME.md).

**Stand structure** (trees revision 8, terrain-v137, `riven-trees.json` structure): three pure
fields shape where trees stand within their stands. Groves and clearings: two octaves of smooth
noise (72 and 18 blocks) multiply the canopy layer's chance by up to 1.8 in groves and to nothing
where they fall under 0.3, and the understory takes the opposite, thickest where the canopy
opens, as saplings fill a gap; emergents stand regardless. Ecotones: a candidate takes its stand
from a point jittered up to 20 blocks (a triangular draw per axis), so neighbouring stands feather
into each other across their edges, except that water lends no stand and the column's own is
used. Treeline: over the last 16 blocks below the treeline (`SurfaceRules.treeline`), chance falls
to 35% and stems to 60% of their draw, never below the species' limit. A tree's stem draws from
its own hash, no longer the one that places it in its cell. Census, seed 2485331 at 32 km: quadrat
dispersion of canopy stems rose (temperate woodland 1.18 to 2.30, tropical rainforest 2.11 to
3.51, boreal forest 4.37 to 6.43) and Clark-Evans R eased toward random (1.18-1.45 to 1.13-1.33);
every forest signature holds its density within 15% except boreal highlands (71 of 100 stems/ha at
chance 1, held by its ground and the treeline; 32 at v134) and savanna, whose 90th-percentile
height includes its neighbours' trees within the ecotone. Leaves per hectare stay under double
v135 in every forest (temperate rainforest 1.73x); two open hill biomes pass it as forest trees
feather in (rugged hills 1,961 to 5,708, cold hills 1,307 to 3,128).

**Species niches** (trees revision 9, terrain-v138): every species gives the climate it grows in
(`niche`: `celsius` and `moisture`, each a trapezoid a, b, c, d: nothing below a, full from b to c,
nothing above d), and a candidate chooses its species by stand weight times how well its column's
effective climate suits each one; where none suits, no tree stands (`NICHE`). Species therefore
turn over along climate gradients inside one stand, and the stands that switched at hard
temperatures (boreal forest, temperate woodland, beach, delta wetland) are single stands again,
their palms and mangroves held to warm columns by niche. Tree ferns are montane (full 11-21 C on
wet ground) and lowland rainforest understory weights them half as before; oaks reach dry ground
(full from moisture 0.32), as in oak savanna. Census, seed 2485331 at 32 km: tree fern falls from the
most common species (11.0% of all stems) out of the first six; beech gains toward the warm end of
temperate woodland; the cloud forest, 3-15 C by its climate, holds alder, maple and oak and no
strangler fig; forest signatures hold as at v137; open biomes thin at their dry and cold margins
(grassland 10.8 to 7.4 stems/ha, cold uplands 9.7 to 7.4).

**Ground cover** (prototype, ground-cover-v2, terrain-v99, `riven-ground-cover.json`,
`GroundCover`): Riven places the plants and decals on the ground itself, on land and in and under
water (shallows and underwater plants); no biome decorator runs in a Riven world. Vanilla cactus stands one to three high in deserts and badlands, at most one per
4-block cell inside its inner square so no two stand side by side (`cell`), and keeps its item in
Riven worlds as the Common Reed keeps sugar cane; pumpkins turn up rarely in meadows and
grassland. A column's cover is a pure function of seed, its riven: biome and the
block it stands on: the biome lists palettes (temperate meadow, woodland, conifer, temperate and
cold rainforest, tropical and cloud forest floors, tundra, polar, alpine rock, heath, steppe,
grassland, wetland, scrub, Mediterranean, savanna, desert, badlands, four shores, reed margins and
shallows)
and entries in order, and the first entry whose ground fits and whose draw falls under its density
decides. Patch noise gathers an entry into drifts over a share of the land at the same mean density,
so flowers come in sweeps and litter under its trees' crowns of color. Cover stands on the column's
surface (the top of its highest non-air run) where terrain, boulders and trees leave air, never on a
boulder (in the preview from 4-block cells, which draw no boulders, cover no longer avoids them); a
two-block plant or a cane needs all its blocks free of terrain and trees (the tree sites answer
which blocks they hold). Reeds are Riven's: the Common Reed (`riven:cane_common_reed`, Requisition
03's reed art as stem and top pieces) is a cane of 2 to 4 blocks on grass, dirt, mud, sand, gravel
or sandstone that stands only where a neighbouring column's native blocks hold water beside its
ground, as it needs to stay. Its palette leads the lists of 26 warm and temperate biomes (1.6 times
as dense in wetlands, half on open shores), so it takes a margin before other cover, and each piece
drops a sugar cane, which keeps that source in Riven worlds. Around seed 8675309's spawn, 374 stacks
stood 2 to 4 high on grass, sand, gravel and dirt, each with water beside its ground, and `/riven
cover` matched preview and world at seven margins. Each cube writes the cover inside itself after
its trees, reading the ground block below as native generation writes it even in the cube under it;
the preview draws the same blocks over every cell (a cell over two blocks wide draws no reed:
checking a margin reads a native chunk column, and such a cell's column is rarely one), which
Distant Horizons skips as non-colliding while tinting the ground under them, as it does with world
data. Palettes cover a quarter to two fifths of fitting ground in meadows, forests and wetlands, a
tenth on rock and shores and a twentieth in deserts. `/riven cover [radius]` compares preview and
world block by block and names what each path decided from where they differ, and counts columns
whose ground block itself differs; `/riven column x z` prints one column through both paths. Since terrain-v116
no provider generator reworks ground after cover and preview decide.

Shallows (terrain-v117, the `shallows` palette): a column under still water is decided too, its
ground the water, which only floating plants fit, and each entry names the water depths it takes.
Reeds stand in water one block deep (Shallow Bent Reed) or up to two (velvet cattail, bronze rush,
arrow waterleaf, Decoration Library 01's land art drawn again in the water by `texturesOf`), and
pads float over one to five blocks: blush waterlily, ivory waterstar and water shield, Decoration
Library 01's floating subjects as painted placeholders, and vanilla's lily pad. The palette follows
the reed margins in 26 biomes at their scale, at half strength in boreal forests and cold
rainforests, and alone on lakes (rivers at half). The preview counts the same depth down its own
runs and draws the same pads and reeds.

Underwater (terrain-v119, the `waterweeds`, `kelp_bed` and `reef` palettes): a submerged plant is
decided by the bed it stands on, under at least two blocks of water, and stands from the bed up in
place of the water, so the surface stays whole. Whorled waterweed and eel ribbon grow wherever the
shallows do, two to ten blocks deep; `riven:ocean` and `riven:deep_ocean` grow kelp beds (fork
kelp 3 to 14 blocks and ladder kelp 3 to 12, each a stack of stems under its top piece, no higher
than the block below the surface, five to thirty deep, with eel ribbon in the shallows), and
`riven:warm_shallows` reefs (clay finger and pebble tower corals, tidal lace fans and mauve cup
anemones). Cubes below a surface cube decide their columns too, so a deep bed is written in its own
cube. The preview draws them as the water they stand in: its lighting takes no decoration of
water's material, and nothing under water shows from a preview's distance; `/riven cover` counts
them apart (submerged).

**Geology** (prototype, phase 1a of the subsurface design; `riven-geology.json`, `GeologyMap`): every
block below the soil, and all exposed rock, comes from a geological history evaluated per column.
Nine rock units (gneiss, schist, granite, limestone, dolostone, shale, sandstone, red beds,
ironstone) each carry a vanilla block and the properties later phases read: solubility,
permeability, strength and ore hosts. A nine-formation sedimentary sequence sits over a gneiss
basement with schist belts and granite bodies. It includes two marker beds (a white limestone
and a red bed), and several formations alternate with a second unit in beds. The sequence top
lies above the smooth base elevation by the rock erosion removed. That depth varies regionally
(0 to 230 blocks), grows with compression and with base elevation above Y140, so regions expose
different levels and uplifted cores reach the basement. Folds from three blended orientations
grow with compression; sequences thin under it. The base blends to Y100 across the coast, so beds
continue under the sea. Within 24 blocks of the surface, arid climates recolour sedimentary units
as terracotta bands. This retires the climate strata palettes.
Phase 1b breaks and covers the sequence. Steep faults come from 1.5 km cells, denser near plate
boundaries, with bigger throws at rifts and sideways slip at transforms; each side moves half,
tapering at the tips. Thrust sheets in compressed ground lift older beds over younger. Granite
plutons with domed roofs (likelier at converging margins) send out dolerite dikes. An erosion
surface truncates all of this, and a flat upper sequence rests on it: dune sands with loose sand
lenses, evaporites, red beds and a basal gravel that collapses when undercut. Above its top the
upper sequence repeats in cycles, so high ground cuts banded young cover. Over 8 km, seed 8675309
has plutons under 2.7% of land (1.9% exposed), thrust sheets under 9.3%, 1.1% of land within six
blocks of a fault, and basement at 33% of the surface; seed 101 has 14% basement. Phase 1c adds the deep and volcanic rock. Below a deep surface near Y-300 the
basement is gabbro, layered with anorthosite in broad regions. Rifts follow a new smooth plate
extension field (`PlateMap.extension`, the blended velocities' divergence) rather than nearest-plate
switches, and lift the deep surface and the lava level (a field for the cave phase, near Y-470) and
thicken a flood basalt in the upper sequence. Magma chambers (lava-filled lenses at Y-380 to -160,
likelier near rifts and converging margins) send basalt feeder pipes to the surface as volcanic
necks; kimberlite pipes flare from the floor to the surface. Native cubes hold the lava; the
preview shows stone there, since its lighting cannot take emitting blocks. Over 8 km, chambers lie
under 0.1-0.2% of land and kimberlites reach two seeds of three. The water table field is the
column's groundwater head. A column's stack is still a set of 2D surfaces and plan-view objects, so
its runs are exact and cost 3-11% of a chunk-column build. Below the lowest soil, cubes fill from the column runs. `tools/checks/GeologySection.java`
draws vertical slices and outcrop maps, and probes columns.

**Caves** (prototype, phase 3 of the subsurface design; `riven-caves.json`, `CaveMap`): voids
rooted in the geology. Each block belongs to a realm by its rock, so the deep reads differently
from the middle layers (each realm also reports depth biomes; see Realms and depth biomes):
- Skin (within 40 blocks of the surface): caverns need a higher noise value and tunnels narrow,
  so the near surface stays mostly solid.
- Layered deep (sedimentary formations): caverns scaled by each rock's solubility and strength,
  worm tunnels wider in weak rock, karst tube levels with vadose canyons around the water table
  in soluble rock, bedding-plane halls (low, wide slots hugging formation boundaries), collapse
  domes in weak rock and pillared salt halls in evaporite.
- Broken basement (gneiss, schist, granite, dolerite): sparse caverns, narrow tunnels, joint mazes
  and vein galleries where dolerite dikes weathered out. Joint mazes are vertical slots along two
  joint sets, straight within joint domains of about 512 blocks whose orientation and spacing come
  from slow fields at each domain's point (terrain-v88; before, the sets turned about the world's
  origin, so far from it their planes crowded into noise). Each joint plane breaks into segments
  of its own length and phase, and the maze stacks in 32-block levels. A segment opens whole, by
  its own share and a 2D mask at its midpoint, and only where an open segment of the other set
  crosses it; open slots rise from the level's shared floor, some reach down to the level below
  as shafts, and round rooms open at some crossings. Slots never pinch below 0.8 blocks either
  side, so a diagonal slot stays one passage. Before v88 slots opened over random bands and
  pinched block by block: 99% of the 22,020 air pockets sealed in rock in `WaterCheck`'s windows
  on seed 8675309 were joint-maze pieces; now 236 pockets remain of every family.
- Molten root (below the deep surface): pillar halls (vast, tall rooms from a two-octave noise,
  with rock pillars left standing), lava rivers along the lava level, lined geodes in gabbro,
  anorthosite, basalt and dolerite, and air rooms over every magma chamber.
- Everywhere: fissures along faults, rift chasms along major ones, lava tubes in basalt.
- Fills: voids below the lava level hold lava. Water comes in bodies, not columns (terrain-v88,
  `FloodMap`, `riven-caves.json` fill). The land splits into flood cells of about 96 blocks, each
  nearest its own jittered point, with edges that wander up to 10 blocks along a smooth warp. A
  cell takes one flat level, its center's water table, and floods when the formation at that level
  there is at least 0.45 permeable: every cave block from about 40 blocks below the level up to it
  is water (ice in a frozen depth biome). Carving leaves rock to hold it: a 2-block sump floor under
  the band, undulating by up to 3 blocks, and a dam along each edge a flooded cell shares, from the
  lower band floor up to the higher level, 2 blocks thick at its crest and widening below like a
  rock sill (up to 8), so flooded caves step from pool to pool over sills. Entrance shafts end two
  blocks above any water their column holds or holds back. The distant preview cannot light air
  under water, so air below a column's water (a lake's dome under a pond) previews as stone, like
  buried lava. Before v88 each column flooded on its own floor's depth and rock, and 9% of cave water stood
  beside air as curtains up to 40 blocks tall on dry cave walls. In frozen ground fewer caves flood now:
  `DescentCheck` finds 13 frozen pools over 8 km on seed 8675309 (841 before). A memo keeps
  32,768 cells at the 8 km layout, growing with the layout scale squared (524,288 at 32 km): each
  column reads 25 cells, a cell costs a geology sample, and 12 players spread over the world read
  every cell of an 8 km world (7,744 with the ring past its edge) and 59,216 at 32 km, where the
  4,096 slots before rebuilt each cell about 100 times per pass.
- Underground lakes (`Lens`, `riven-hydrology.json` layers.lakes): at most one per 2,048-block
  owner cell, at 80% where groundwater feeds a basin or moisture is at least 0.3, an elliptical
  chamber (radius about 30-45 blocks) holds still water up to one waterline and an air dome 1.5
  times as tall above it. Lakes lie above Y-100, clear of the magma chambers, and at least 24
  blocks below every flood band; caves keep 2 blocks of rock around the lake below its waterline,
  so they can open only into the dome. Before v88 the lens was solid water that caves cut into.
- `tools/checks/WaterCheck.java` builds whole 32-column windows over 4 km and every lake there,
  and requires no water beside air, no water over air, no air below a lake's waterline, and the
  same blocks in reverse build order. On seed 8675309 (288,499 columns) caves hold 238,466 water
  blocks and lakes 73,019; on seed 101, 325,184 and 121,654. Over 12 km seed 8675309 has six lakes
  (two before), at Y-86 to Y-19.
- Entrances: sinkholes in soluble rock and rift chasm openings, the only places caves reach the
  sky. They are part of every column sample, so the distant preview shows them. Since terrain-v102
  an entrance never opens beside surface water (none of its four neighbours may hold water over
  its ground or be reserved for water); before, a few dozen columns per seed opened into a bank
  with the water standing against them.

Each kind of cave is a carve form (`CaveMap.FORMS`), and `riven-zones.json` names the forms each
crust sub-zone allows: the root takes chamber rooms, fissures, lava tubes, geodes, lava rivers,
pillar halls and its tunnels; the skin and the body take the basement's and the layered rock's
forms, the skin without joint mazes or bedding planes. The 3D caves are carved only into native
chunk columns, from rotated value noise on a 4-block lattice cached per chunk, each block taking
the first form its sub-zone allows that opens it; the column engine keeps them under the sky's
roof band and fills them by its fixed precedence (see ARCHITECTURE, the column engine). On seed
8675309 (terrain-v88), caves fill 2.6% of the skin, 10.2% of the
layered deep, 6.1% of the basement and 14.3% of the root. By volume, pillar halls are 49%,
caverns 13%, joint mazes 14%, tunnels 10% and bedding planes 6%, and each remaining family is
under 5% (`GeologySection families`). Flood cells and the new joint mazes add about 1.5 ms (6%)
to a chunk column's build in one of `GeologySection cost`'s two sample areas and nothing
measurable in the other.

**Cave decoration** (prototype, `riven-cave-decor.json`, `CaveDecor`): placed on each native column's
cave floors and ceilings as runs (`AssembledColumnCheck` covers them); voids touching a planned entrance
stay bare so the distant preview still matches the sky view. What a floor or ceiling holds follows
the depth biome (riven-realms.json; table below) reported where the cave space it bounds lies,
the 4-block cell row of the void's lowest or highest block, so a biome's name tells what its caves
hold even where a void cuts down to the formation below; rock tests (solubility, evaporite, lava) read the rock itself:
- Formations by setting (terrain-v88): stalactites and stalagmites up to 6 long that merge into
  columns when they meet. Calcite (Riven's pointed dripstone, on dripstone block floors and
  ceilings) grows in soluble rock where the depth biome allows it and a mask does, and in the
  Broken Basement within 16 blocks of a fault, where fault water carries it into any rock. Frost
  Hollows grow pointed ice, Salt Halls pointed salt in evaporite, Lava Shores pointed basalt, and
  every basalt roof (the lava tubes) grows pointed basalt too. Stalactite and stalagmite chances
  scale with the subsurface humidity at the void (humidity over 0.5, from 0.3 to 2 times). On
  seed 8675309 over 8 km (`DescentCheck`), the Broken Basement holds 867 stalactite runs (none
  before), Lava Shores 1,081 basalt stalactites and 164 spires, and Salt Halls 39 salt stalactites
  where they had calcite.
- Floors: litter (dirt) with ink caps and pale cave mushrooms, sand, salt sand, packed ice, gravel, ash
  (or gravel) or magma by depth biome; salt sand on every evaporite floor; mud within 14 blocks
  above the water table (and below it) in the layered deep. Gravel pockets (balls of 2-4 blocks radius on an 18-block
  grid) fill rock below Y-150, never an unstable floor, and fall when a cave opens under them.
- Ceilings: hanging roots under root mats in Root Hollows, cobwebs in the Broken Basement and Dust
  Hollows.
- Life, where the depth biome holds it: stonebells on bare stone, vanilla mushrooms,
  glowing mushrooms in clusters above Y-213 and green biofilm on floors under at least two blocks of water. Salt, frost, dust and the root
  stay lifeless, and nothing decorates lava (magma-chamber pools carried dirt and mud patches
  before terrain-v87).
- Pools (terrain-v88, `CavePools`, `riven-cave-decor.json` pools): now and then a small still
  pool on a wet cave floor, at most one per 48-block cell. The cell's center column offers its
  floors; a floor passes where it is dry and the subsurface climate there is at least 0.5 humid and
  at most 45 C. The pool fills a disc of radius 2-4 from each column's floor up to one level, at
  most 2 blocks deep, and the cave in a ring around it takes a rim up to that level, at most 2
  blocks tall: dripstone block (rimstone) in soluble rock where dripstone grows, the host rock
  elsewhere. A pool forms only where its whole patch holds it that way, five or more columns hold
  water and no other cave, entrance, flood band, lake or lava lies near, so `WaterCheck` covers it.
  In a frozen depth biome a pool is ice. Seed 8675309 has about 6 per km2.
- Seep walls (terrain-v88, `CaveSeeps`, `riven-cave-decor.json` seeps): groundwater wets cave
  walls in a band 3 blocks tall, immediately above a flooded cell's waterline or at a permeable water
  table, at most one seep per 64-block cell (the first of nine candidate columns a dry cave
  crosses at the band). Within 6 blocks, every plain rock block of the band that faces cave air
  (read from the real voids of the patch) turns to flowstone (dripstone block) in soluble rock
  where dripstone grows, mud where the depth biome has mud (Root Hollows have mud since v88: moist
  ground near the water table) or packed ice under ice floors; the band fades out over its outer
  half and its upper and lower rows are patchy; drier depth biomes keep their rock. Seed 8675309 has about 5 per km2, each marking about 15 blocks. Moss and dripping water
  wait for the moss backport.
- Unstable floors (terrain-v88, `riven-cave-decor.json` unstable): rock at most 2 blocks thick
  between a cave whose top holds at least 4 blocks of open air and a cave whose floor has 2 blocks
  of headroom becomes its host rock's unstable stone (Riven content, below), in patches where a
  64-block mask passes, away from entrances, never where it seals flood water or already holds a
  seep or pool rim. Such floors and the ceilings under them stay bare of floor and ceiling blocks,
  so the cracks show from above and the dust falls from below; ore wins over unstable stone. The
  generator keeps the rock and marks the floor; native columns carry the marks, the world writer
  turns host rock there into unstable stone, and the distant preview shows the host rock. Falls
  are balanced (terrain-v89): every fall a healthy player survives (under 23 blocks, or into water)
  stays, while lethal falls (23-63 blocks) stay in 55% and severe ones (64 or more) in 40% of
  32-block cells, so a patch keeps or loses its floor together. `DescentCheck` proves each floor is
  thin rock over open air with headroom, bare, and stored as sampled, and counts the falls: on seed
  8675309 0.64% of its window's columns hold a floor, 88.2% of falls are survivable, 9.9% lethal
  and 1.8% severe (seed 101: 0.44%, 88.8%, 10.1%, 1.1%), mostly in Karst Galleries, the Layered
  Deep and the Broken Basement. Before the balance, 17% of falls were lethal and 3% severe.
- Pools and seeps read a patch of neighboring columns once per cell and keep the result in a
  bounded memo. Rebuilding warm chunk columns costs about 1-2 ms more each (about 10%); a cold
  first build of an isolated area pays up to about 25 ms more, as pool and seep sites wake terrain
  planners immediately outside it that neighboring chunks would otherwise wake later. A carve over a few
  heights builds only the cave lattice levels it reads, and `CellMemo` gives each cell two slots
  (a pair of cells that shared one slot recomputed on every alternate read).
- Every block cave decoration places is Riven content (below) or vanilla's; none comes from
  another mod.

On seed 8675309 (4 km, 32-block grid) 81% of land columns carry some decoration (84% before
terrain-v87, when dry, frozen and wet skins also took roots and litter; `GeologySection decor`
counts each kind by realm and finds a spot for it).

**Ores** (prototype, phase 2 of the subsurface design; `riven-ores.json`, `OreMap`): 54 deposits by
type, their mineral sets taken from Supersymmetry's overworld veins and deposits, GTCEu 2.8.10-beta's
overworld veins, or composed by Riven from those materials, each placed by a geological origin:
- Organic: lignite seams (shallow), coal seams, anthracite seams (deep), graphite schist bodies.
- Marine: banded iron, oolitic ironstone, phosphorite beds, glauconite greensand, sedimentary
  manganese, a marine evaporite sequence (carbonate, sulfate, halide) and potash, lead-zinc in
  carbonates with an oxidised cap (anglesite, cerussite, vanadinite, smithsonite), saltpeter.
- Continental: red-bed copper, non-marine evaporites, kieselguhr, caliche, bauxite and nickel
  laterites, bog iron, placers (gold, tin, garnet, platinum), mineral sands, volcanic sulfur, native
  copper in basalt.
- Hydrothermal: porphyry copper around plutons, zoned skarns, copper, cobalt, tin, redstone, silver,
  electrum, orogenic gold, mercury-antimony and fluorite veins, massive sulfides.
- Magmatic: vanadium titanomagnetite layers, magmatic copper-nickel, a platinum reef, nickel at the
  gabbro base, dunite, eclogite with diamonds, igneous apatite, mica and lithium pegmatites,
  kimberlite diamonds.
- Metamorphic: garnet gem schist, soapstone bodies, talc and asbestos, slate and phyllite belts.

Origins: lenses follow their host bed through folds and faults; sheets are planar veins of a strike,
dip and elliptical extent; contacts are bands where a host meets a partner, or rims along pluton
walls; pipes fill kimberlite. Some deposits only reach a set depth below the surface. Seams,
evaporites, placers and rock bodies fill their bodies solid with Riven blocks; other deposits grow
ore nodes of one mineral (10-30 blocks, mega nodes of 50-90 at 8%, on a jittered 6-block grid,
doubling in chance from Y0 to Y-400), picked by GTCEu's layered roles. In Riven's host stones a
mineral is its Riven ore block, elsewhere its GTCEu ore in the host's stone type, or a vanilla
fallback. Each native column keeps its bodies as Y intervals; cubes replace only a bed's own rock,
never the cover the surface rules lay over it (soil, sand, beach, bar and slope gravel, the
sandstone under sand), so placers still fill sand and gravel units while beaches keep their sand.
A lens takes its height from its bed's whole thickness, the part the land has eroded included, so
it follows the bed and the surface cuts it in an outcrop line. Until terrain-v97 the top bed was
measured only to the ground, which laid its lenses along the ground: on seed 8675309, 0.92% of land
columns showed ore or a seam at the surface (coal on 16% of those, in fields as wide as a lens,
often on beach gravel), now 0.17%, all of it the bed's own rock where the land cuts a body.

On seed 8675309 ore is 1.7% of skin rock, 2.3% of the layered deep, 0.5% of the basement and 1.2%
of the root (1.1% overall); seams and rock bodies add 2.7%, 2.3% and 0.4% in the upper three, at
about 3 ms per column (`GeologySection ores`, `orebands`, `orespots`). Skarns, basalt deposits and
diamonds depend on rare hosts and are absent from many areas, and the basement is the poorest realm.

**Realms and depth biomes** (prototype, terrain-v86 and v87, `riven-realms.json`, `RealmMap`): the
descent. In the crust every block lies in a realm by its rock and depth, as the caves define them,
except that a magma chamber belongs to the root and the skin only to dry land (under the sea and
surface water the skin takes its rock's realm). It takes the depth biome of the first rule of its
realm that holds. Rules read the formation, not the bed (an interbed reads as the formation it lies
in), so a biome holds across thin beds:

| Realm | Depth biome | Where | Its caves |
| --- | --- | --- | --- |
| Skin | Salt Halls | evaporite | salt sand floors, pointed salt; lifeless |
| Skin | Frost Hollows | under FROZEN land | pools frozen to ice, packed ice floors and seeps, pointed ice; lifeless |
| Skin | Dust Hollows | under DRY land | sand floors, cobwebs, dripstone; lifeless |
| Skin | Root Hollows | other land | hanging roots, litter with ink caps, mud near the water table, dripstone, fungi |
| Layered deep | Drowned Galleries | a flooded cell's band (see Caves) | flooded passages, green biofilm, mud |
| Layered deep | Salt Halls | evaporite | as in the skin |
| Layered deep | Karst Galleries | rock at least 0.5 soluble | dripstone, mud, mushrooms, glowing mushrooms |
| Layered deep | The Layered Deep | other sediment | mud, litter, mushrooms, glowing mushrooms |
| Basement | The Broken Basement | crystalline rock | gravel floors, cobwebs, sparse fungi, calcite along faults |
| Root | Lava Shores | magma chambers, and up to 8 blocks above the lava level | magma floors, lava lakes, pointed basalt; lifeless |
| Root | The Molten Root | the rest below the deep surface | ash and gravel floors; lifeless |

A cube whose every block lies below the ground of all 256 columns of its chunk column (each
column's highest block that is neither air nor fluid, so an open entrance's floor or the bed under
water) reports a biome per 4x4x4 cell: the depth biome of the cell's center column, or that
column's own biome within 8 blocks of its ground (the cave roof). Cubes that hold any ground keep
the column biomes, so surfaces, open entrances and weather are unchanged. Cubature keeps every
4-block row of a generated cube's biomes (its cube primer kept one biome per 16 blocks until
terrain-v87, realms included) and reads vanilla's two Y 0 column-biome probes elsewhere (rain at
the precipitation height, a snow golem where it stands). Depth biomes extend the void's class (no shader category) with the UNDERGROUND
dictionary type; their temperature, which Demonica's shader uniforms follow at the camera, rises
from about 0.6 in the skin to 1.4 in the root and 2.0 at the lava. They spawn only the vanilla mobs
of their spawn table (`riven-biome-registry.json`): the skin keeps its climate's night creatures
(strays in Frost Hollows, husks in Dust Hollows), the layered deep loses witches, the basement
belongs to spiders and cave spiders, the root to skeletons and endermen, and Salt Halls and the
root have no bats. `/riven find riven:<depth biome>` names the nearest column and height.

On seed 8675309 over 8 km (`tools/checks/DescentCheck.java`, 16-chunk grid), 95% of crust cubes
below the ground report depth biomes. By blocks below the ground: 8-39, the three Hollows 30%,
Karst, Drowned and Layered 38%, Basement 31% (under the sea and on shields); 40-99, Karst and
Layered 40%, Basement 56%; 100-299, Basement 74%; 300-399, Molten Root 46%, Basement 39%, Lava
Shores 15%; below 400, Molten Root and Lava Shores. Salt Halls are rare (0.1%; 0.7% of the top
band), since evaporite is a thin basin formation; Frost Hollows hold 3% of the top band. The check proves each cell's stored
biome against its rule, the ground against a per-block scan, every decoration run against its
floor's or ceiling's depth biome, frozen pools, and order independence.
`tools/tours/descent-8675309.json` stands in one cave per depth biome, from a rain view over a
chunk whose Y 0 cube reports a depth biome down to Lava Shores, then out to the Hollows and Salt
Halls; each view logs the previous view's biome through `/riven`.

Below the crust floor at Y-512 the world continues to Y-1664 through two realms, the Beneath (to
Y-1088) and a Nether-themed deep (to the world floor). Each realm reports its own `riven:` biome as per-cube 3D biomes (4x4x4 cells),
so `World.getBiome` follows depth while the crust keeps its column biomes: `riven:beneath`
(Beneath, extends `BiomeVoid`) and `riven:nether` (Infernal Deep, extends `BiomeHell` with the
NETHER dictionary type, no Nether mobs). Boundaries are open. Immediately under the crust floor the
crust's own floor rock thins out into Beneath rock over 40 blocks, and the Beneath-to-Nether top
undulates by up to 32 blocks over a 512-block wavelength with the same dithered fade; in each band
the realm's boundary rock (leucobasalt with granulite inclusions under the crust, heat-cracked
eclogite, light 6, above the Infernal Deep) rims the rocks above and below, about two thirds of
the band's middle. Each realm is built of its own rocks from Art Requisition 05 (`riven-realms.json`
geology, `riven-content.json` rocks; each block shows a main or alternate face by position). Rock
bodies fill warped cells (48 by 22 blocks in the Beneath, 40 by 18 in the Infernal Deep), each
taking a rock by its zone's weights, which are volume shares; a body's zone follows its center's
depth, so zones interlock along body walls. The Beneath runs from granulite and migmatite through
blueschist and eclogite (garnet-studded pods) to peridotite (chromite pods) and serpentinite
(jadeite veins), crossed by moonstone seams (light 4, with a light 7 core). The Infernal Deep runs
from sulfurous scoria (`infernal_rock`), scoria (`cinder_rock`, soul ash pods, light 3) and
tuffaceous shale (`ash_shale`) through jasper bands to clinker (`slag_stone`, brimstone pods), crossed by animated molten seams (light 12). Seams and veins follow the
zero sheets of gradient noise, so they wander freely. Bedrock lies on the world floor. The distant
preview draws each realm as its representative rock (granulite); the deep realms have no caves yet. Realm cubes never run crust rules (the crust floor lies on a cube boundary), so their cost is
a fill and the crust is unchanged except that its floor row is rock instead of bedrock.
`tools/checks/RealmCheck.java` verifies solid realms, bedrock only on the world floor and order
independence, prints the fades and each realm's shares, and draws a section in the rocks' colors.
On seed 8675309 the Beneath is 21% peridotite, 21% granulite, 18% eclogite, 14% blueschist, 10%
serpentinite, 9% migmatite and 0.8% moonstone; the Infernal Deep 43% sulfurous scoria, 24% clinker,
12% jasper, 9% tuffaceous shale, 7% scoria, 3% brimstone and 1% each soul ash and molten seams. Seed 101
matches within half a percent.

**Subsurface climate** (prototype, `riven-subsurface-climate.json`, `SubsurfaceClimate`): the
parameter space for underground biomes. Every block below the ground has a temperature, a
humidity and an air freshness, from the land's climate, the depth below the ground and the
geology:
- Temperature: the land's mean temperature, rising 0.05 C per block of depth (2.5 times that under
  the strongest rifts), plus up to 70 C at and toward the lava level, 50 C around magma chambers
  and 25 C along rift faults. Cold land stays frozen down to where the gradient lifts it.
- Humidity: saturated at the water table, wet in permeable formations far below it (confined
  aquifers) and dry in shale and crystalline rock, wet along faults, rain-fed near the surface,
  damper under wet land, dried by heat except where groundwater or fault water keeps it (hot
  faults steam), and dry in evaporite.
- Air: fresh near the surface, staler with depth, fresher along faults, fouled by heat near lava
  and chambers.
- States: frozen at 0 C or below; steam at 60 C or above with humidity 0.6 or more.

The realms below the crust have placeholder climates (45 C at the Beneath's top, 90 C at the
Infernal Deep's) until their content is designed. Biome rules may test the climate (`celsius`,
`humidity` and `air` ranges in `riven-realms.json` crust rules); no packaged rule does, so no
generated block changes. On seed 8675309 (4 km, 64-block grid, `tools/checks/ClimateSurvey.java
stats`), cave space spans, from the 10th to the 90th percentile: skin -1 to 24 C, humidity 0.25 to
0.90; layered deep 3 to 32 C, humidity 0.18 to 0.70; basement 6 to 39 C, humidity 0.16 to 0.51;
root 24 to 127 C, humidity 0.01 to 0.17, air 0.01 to 0.34. Lava Shores run 94 to 141 C; 10.5% of
the skin's caves are frozen and 0.7% of the root's steam. `ClimateSurvey space` draws a
temperature by humidity map of each realm and `ClimateSurvey section` a vertical slice of all
three fields.

**Player effects** (prototype, `config/riven/player-effects.json`, `PlayerEffects`,
`RivenClimate`): every 40 ticks each survival or adventure player underground in a Riven world (below the crust's
surface band, or in a realm below it) takes every effect whose conditions hold at its feet:
climate ranges, the frozen and steam states, realms and depth biomes. Effects give potions held a
little past the next check, or food exhaustion; `unless` lists potions that block an effect. The
first set exercises the mechanism: heat (40 C or more: faster hunger), scorching (70 C or more:
Slowness I), both blocked by Fire Resistance; frostbite (-5 C or below: Slowness I and faster
hunger); foul air (0.1 or less: Weakness I). The effects are a server option shipped with Riven:
the first server start writes the packaged defaults (`riven-player-effects.json`) to
`config/riven/player-effects.json` and never overwrites it; the server reads that copy at start
and on `/riven effects reload` (operators). Unknown keys, unregistered potions (any mod's, by
`namespace:name`) and invalid JSON are refused with the reason, and the effects in force stay;
`/riven effects` shows what is in force and where it came from. `config/riven.cfg` `playerEffects`
turns the router off. `RivenClimate.at(world, pos)` gives any server-side caller the climate, realm and depth
biome at a block, for machines later; `/riven` prints them with the effects that apply.

**Ground cover** (`riven-surface.json`, the column engine's crust settings): each column's ground
takes the first of 32 settings that holds, in the crust table's order, and the table names its top
(one block), soil (two) and subsoil (three). Sediment and a rocky shore's build-up lie over their
own deposits; a marsh's mud and its creeks' beds are three blocks of mud; marine and lagoon beds are sand down to the shoal level and gravel below; river and
lake beds are sand, dirt, gravel or clay in patches; channel bars, mud in a wetland's core and at
warm river mouths (above 20 C), green oasis and wetland margins,
bare stone (riven-surface-rules.json), sandy shores (gravel on reflective coasts, shoaling under
0.25), dryland pavement and sand (red sand on red sandstone from 28 C), climate sand and snow follow; grass on the steepest soil (broad
slope over 0.12, about 7% of soil) is coarse dirt, and gravel over 0.2. Elsewhere the climate at
the floor's own height (terrain-v115) chooses the soil under the grass: peat on cold, wet flats
(below 6 C, moisture from 0.78, broad slope under 0.03), podzol over loam in the cool wet band (0
to 7 C, moisture from 0.72, 6 or more blocks above the sea), silt below 2 C, sandy loam where it is dry (under 0.42) from 12 C,
loam where it is wet (from 0.6) and plain dirt between. Over `AssembledColumnCheck`'s 16 km grid on seeds
8675309 and 101 grass-topped land is 32-37% loam, 25-26% dirt, 20-22% silt, 14-19% sandy
loam, 1.4-2.1% podzol and 0.7-1.1% peat, and 15-16% of dryland and climate sand is red. Sand Riven places as soil
rests on three blocks of sandstone, and covers over it (bars, green margins, pavement, beach sand)
keep it. Rock comes from the geology (above).

## Riven content

Riven's own blocks and items (`riven-content.json`, `cc.orthrus.riven.content`). Each block
declares an id, a kind (its Java implementation), name, creative tab, hardness, resistance, harvest
tool, behavior values and textures. `tools/content/build_content.py` writes the blockstates,
models, item models, lang file and textures under `assets/riven` (textures are original pixel art
painted by `tools/content/painters.py`, or Riven Resources art copied unchanged); `--check` runs in the CI quality job and fails when the
committed assets drift from the definitions. The world writer places content blocks: formations
from cave decoration, ore blocks, and unstable stone on the floors cave decoration marks.

- **Dripstone block** (kind `stone`): a full rock block.
- **Host stones** (kind `stone`, Art Requisition 06): gabbro, gneiss, graphite schist, limestone,
  mica schist, phyllite, quartzite, shale, slate, soapstone, kimberlite, anorthosite and
  leucobasalt. The geology places them for its units (gneiss, schist, limestone and dolostone,
  shale, ironstone as quartzite, basalt as leucobasalt, gabbro, anorthosite, kimberlite). Each
  shows a main or an alternate face by position; layered rocks have a bedded side and a
  bedding-plane top.
- **Deposit blocks** (kind `stone`, Art Requisition 06, and Requisition 05's bedded rock salt,
  anhydrite and sylvite for the halide, sulfate and potash): coal, lignite and anthracite seams,
  halide, sulfate, carbonate and non-marine evaporites, potash, bauxite, caliche, phosphorite,
  sulfur, native copper, platinum placer and gold alluvium, faced like the host stones. They drop
  themselves.
- **Mud, salt sand and volcanic ash** (kinds `soil` and `sediment`): the catalogue's `soil.mud`,
  `sediment.salt_sand` and `sediment.volcanic_ash`, which were Biomes O' Plenty's blocks. Mud is ground
  that decorations take as dirt; walkers sink an eighth of a block into it and keep 40% of their
  horizontal motion, as on soul sand. Salt sand and ash fall like sand. Until the art pack fills
  their slots they show placeholder faces painted on ramps taken from Requisition 04's tidal mud,
  coral white sand and volcanic ash (DECISIONS 2026-11-01).
- **Soils** (kind `soil`, terrain-v115): loam, silt, sandy loam and peat, the crust table's soils
  under the grass (Ground cover, above), with vanilla's podzol and red sand. They hold plains plants
  as dirt does. Their faces are placeholders on Requisition 05's rooted loam and frozen silt and
  Requisition 04's peat (sandy loam has no art yet) until the art pack fills their slots; grass over
  them stays vanilla grass.
- **Ore blocks** (`ores`): one block per mineral (86, including 15 Supersymmetry minerals GTCEu
  lacks) over the 13 host stones, drawn as the stone's face, main or alternate as the stone shows
  it, under a painted overlay tinted with the mineral's color. Every mineral names a GTCEu
  material id (`gregtech`): bare for GTCEu's own materials (71), `riven:` for the 15 that are
  Riven's (`riven:anglesite`). With GTCEu, a mineral whose material is registered drops its GTCEu
  ore so GTCEu processing applies; otherwise the block drops itself.
- **Riven's GTCEu materials** (`GregTechMaterials`, GTCEu only): Riven creates GTCEu's `riven`
  material registry and builds its 15 `riven:` materials there from each mineral's `material`
  (an id that never changes once worlds hold it, a composition from the mineral's formula, and a
  name where the mineral's would repeat a GTCEu material's: Native Electrum), with dust and ore
  properties, the mineral's color and an icon set by its shine. GTCEu then makes their ores, dusts
  and processing, and registers its per-registry items and blocks under `riven:` (`meta_dust` and
  the like). A material name is global across a pack: GTCEu names ore blocks by bare name
  (`gregtech:ore_anglesite_0`), so no other mod may define these 15. At startup Riven logs how
  GTCEu took its names: on a fresh instance with GTCEu 2.8.10 it unifies 1,200 of 1,258 Riven ore
  dictionary stacks, leaving `oreLignite`, `oreAnthracite`, `treeLeaves` and `treeSapling`, and the
  world's registry holds GTCEu's ores for all 15 `riven:` materials and 40 `riven:meta_*` items.
- **Ore dictionary** (`oreDictionary`, registered with the items, before any recipe): GTCEu:
  Reloaded is Riven's first-class integration, and GTCEu recognizes other mods' items by these
  names. Each ore block registers `ore` plus its material id in upper camel case, without the
  namespace as GTCEu names ores (`oreCopper`, `oreRawElectrum` for `riven:raw_electrum`), on
  every host state, so GTCEu machines process it and its prospectors see it. Host stones register `stone`,
  and `stoneSoapstone`, `stoneQuartzite` and `stoneBasalt` (leucobasalt) where GTCEu has the
  material; graphite schist also `oreGraphite`. Deposits register the ore of what they hold:
  `oreCoal`, `oreLignite`, `oreAnthracite`, `oreSalt` (halide), `oreGypsum` (sulfate),
  `oreCalcite` (carbonate, caliche), `oreTrona` (non-marine), `oreRockSalt` (potash, sylvite),
  `oreBauxite`, `oreTricalciumPhosphate` (phosphorite), `oreSulfur`, `oreCopper`, `orePlatinum`
  and `oreGold`. Names GTCEu has no material for (`oreLignite`, `oreAnthracite`, a missing
  Supersymmetry mineral) still serve recipes and scripts that ask for them.
- **Pointed dripstone** (kind `pointed_dripstone`), backported from modern Minecraft: a stalactite
  or stalagmite whose thickness (tip, merged tip, frustum, middle, base) follows its neighbors.
  A stalactite that loses its anchor falls and shatters, hurting what it lands on (6 per block
  fallen, at most 40); a stalagmite without a base breaks; landing on a stalagmite tip adds 2 blocks
  to the fall and doubles the damage. A stalactite tip drips water or lava when that fluid sits on
  the block its column hangs from, and water drips fill a cauldron up to 11 blocks below. 1.12 has
  no lava cauldron or dripstone sounds, so lava drips are visual and the block uses stone sounds.
- **Pointed ice, pointed salt, pointed basalt** (terrain-v88, kind `pointed_dripstone`): the same
  pieces and behavior in other formation materials, painted from their own palettes
  (`tools/content/painters.py`: pale blue ice, off-white halite, dark basalt). A piece's thickness
  follows only neighbors of its own block, so two materials never merge into one column.
- **Woods** (`woods`): 28 tree species from Riven Resources, their art imported unchanged
  (`tools/content/riven-resources/*/PROVENANCE.md`): from Decoration Library 01 broad oak, silver
  beech, paper birch, river alder, quaking aspen, copper maple, mountain rowan, coppice hazel,
  weeping willow, swamp cypress, blue spruce, highland fir, Scots pine, red cedar, tidal mangrove
  and dryland baobab; from Art Requisition 02 kapok, mahogany, strangler fig, rubber tree, tree
  fern, cacao, coconut and date palms, umbrella acacia, mesquite, olive and juniper. Each has a log
  (bark sides, end-grain caps, any axis), leaves (its two foliage textures at random; cutout or
  opaque with the game's leaves setting; decaying away from logs and dropping the sapling 1 in 20;
  placed by a player they never decay), a sapling (it grows into one of its wood's tree species with the same shapes, four in a square
  into a 2 x 2 species where the wood has one; logs need free space and leaves fill what is free;
  palm saplings also take sand),
  leaf litter (a thin decal on any solid ground with no collision, which placed blocks replace) and
  planks. A log crafts into four of its planks and smelts to charcoal; logs, planks, leaves and
  saplings carry the ore dictionary's wood names (`woods.oreDictionary`), so recipes that take
  any planks take Riven's.
  Art requests and deliveries are tracked in [ART-REQUISITIONS.md](ART-REQUISITIONS.md).
- **Ground decorations** (`decorations`): 151 subjects from Riven Resources, their art imported
  unchanged: 109 from Decoration Library 01 (grasses, ferns, wildflowers, herbs, shrubs, fungi,
  mosses and lichens, leaf litter, wood debris, wetland plants, arid and alpine plants, natural
  props), 8 tropical floor plants and litters from Art Requisition 02 and 34 from Art Requisition 03
  (tundra, desert and dune cover, seven two-block-tall plants). Each is one block: a plant (crossed
  planes offset within its block like vanilla flowers), a decal (a thin plane like leaf litter) or a
  tall plant (bottom and top pieces that join), showing one of four forms by position (a tall plant
  keeps its form in its state). Each stands only on its grounds (grass; dirt, mud included; sand;
  gravel; snow; stone) and drops without them; grasses, litter, moss, lichen and props give way to
  placed blocks like tall grass; none has collision; all but the stone props burn. Lianas,
  epiphytes and picked berry shrubs wait for the blocks that hold them.
- **Underwater plants** (`decorations`, kind submerged, model cross, terrain-v119): whorled
  waterweed, eel ribbon, fork and ladder kelp with their top pieces, clay finger and pebble tower
  coral, tidal lace fan and mauve cup anemone, Decoration Library 01's underwater subjects as
  painted placeholders. They are of water's material and level like green biofilm, stand on sand,
  gravel or stone (the freshwater ones on grass and dirt too), and a kelp piece stands on the piece
  below it; broken, they leave water.
- **Floating plants** (`decorations`, kind floating, terrain-v117): blush waterlily, ivory
  waterstar and water shield (pads; painted placeholders on Decoration Library 01's tones) and
  shallow bent reed, bronze rush, velvet cattail and arrow waterleaf (crossed, drawn with the land
  plants' textures). They stand on still water (a source or a submerged decoration); pads can be
  stood on as lily pads can, boats break them, they burn, and their items set them on the water the
  player looks at.
- **Cave decorations** (`decorations`): what cave decoration places (above), all Riven's own
  since Biomes O' Plenty's blocks left the catalogue. Ink cap, pale cave mushroom and glowing
  mushroom (light 6) are plants (`plant.ink_cap`, `plant.pale_cave_mushroom`,
  `plant.glowing_mushroom`; formerly BoP's toadstool, portobello and glowshroom) on grass, dirt,
  sand (Riven's sediments included), gravel and stone. The stonebell (`plant.stonebell`, formerly
  Miner's Delight) is a plant on stone and gravel. Hanging roots (`plant.hanging_roots`, kind
  hanging) hang under dirt or grass ceilings, give way to placed blocks and burn. Green biofilm
  (`plant.green_biofilm`, kind submerged, formerly algae) is a plane on a flooded floor of water's
  material and level, so the water around it stays whole and swimmers swim through it; broken, it
  leaves water. The fungi, stonebell and biofilm do not burn or give way. Their four forms are
  placeholders painted by `painters.py` on the tones of Requisition 07 (fungi, biofilm) and 05
  (root threads) until the art pack fills their slots; the stonebell's and hanging roots' art is
  requested (ART-REQUISITIONS.md, Requisition 09).
- **Unstable stone** (terrain-v88, `unstable`): one block per host rock, 19 in all (stone, granite,
  diorite, andesite, sandstone, red sandstone and the 13 host stones), named Unstable <host> and
  drawn as the host's own faces under a painted crack network that crosses every edge, so a floor
  reads as one crazed surface; it sheds dust of the host's color into open space below. A player
  who steps or lands on it without sneaking sets it off: it creaks, and half a second later the
  unstable stone joined to it within 3 blocks (at most 64, any host) falls ring by ring outward,
  each column from the bottom up. Every falling piece shatters where it lands, hurting what it lands
  on (0.5 per block fallen, at most 4) and leaving its host's drops one time in four. Sneaking is
  safe, spectators and mobs never set it off, and nothing else does: no neighbor change or missing
  support. Mined, it drops its host's drops (stone gives cobblestone); silk touch keeps it, and
  placed again it behaves the same. The painted crack overlay is the approved final art.
