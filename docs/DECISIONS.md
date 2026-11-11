# Decisions

Dated decisions that shape the project, each true now. Add one paragraph per decision. When a
decision, or part of one, stops being true or is replaced, delete or rewrite it in the same change;
history lives in git and [CHANGELOG.md](../CHANGELOG.md).

## Standing

- **2026-10-19. Project boundaries.** Riven owns terrain content; Cubature owns cubic
  scheduling, lighting, lifetime, storage, delivery and DH integration; DH stays the official
  upstream JAR; Cleanroom stays official and unchanged. Full text:
  [PROJECT-COMMITMENTS.md](PROJECT-COMMITMENTS.md).
- **2026-10-19. Fork name.** The CubicChunks fork is publicly named Cubature to avoid confusion
  with upstream. Compatibility IDs, packages and save formats keep their `cubicchunks` names. It
  is published as `orthrus-research/Cubature`, a GitHub fork of OpenCubicChunks/CubicChunks.
- **2026-10-19. Runtime profile.** Fugue and Scalar Legacy (Scala 2.11 provider) stay in the
  profile, both pinned in `runtime.lock.json`.
- **2026-10-19. Licensing.** Original Riven material is NRR-1.0. Contributed kernels keep MIT,
  the erosion file MPL-2.0, Forge-derived Cubature I/O LGPL-2.1. See [NOTICE.md](../NOTICE.md)
  and [THIRD-PARTY.md](../THIRD-PARTY.md).
- **2026-10-19. Generation constraints.** One reviewed concept at a time;
  `useVanillaChunkWorldGenerators=false`.
- **2026-10-20. Static quality checks.** Formatting, linting and type checks
  (`tools/code_quality.py`) gate every change.
- **2026-10-21. Parent classifications.** Downstream features qualify against higher-level
  parent classifications: earlier stages (provinces, the coast plan) own broad intent,
  marine/shore ownership, supplied-water reservations and legal headroom, and downstream stages
  consume them with explicit reasons and coverage.
- **2026-10-21. Volcanoes.** Volcanoes are reserved for hot climates: a volcano needs a HOT
  anchor, and its cone fades toward cooler columns as cold forms do (terrain-v55). A dedicated
  design pass is open.

## Adopted 2026-11-09

- **Derived regional storage.** Riven may keep pure derived regional data in a deletable cache
  beside the save, verified against the world's definition fingerprint and a checksum. Disk
  capacity bounds preparation beyond the in-memory memos; eviction and deletion must only
  change the time needed to reproduce the same content.
- **Preparation at startup.** Prepare spawn and nearby terrain before the server reports
  "Done"; bake the remaining regional data and distant coverage in the background, behind
  demanded terrain and within Cubature's budget.
- **Arrival waits.** Joins, respawns, portal arrivals and teleports into unprepared terrain may
  wait visibly for their destination instead of generating synchronously on the server thread.
- **Conservative cached cube classification.** Riven may classify cubes from completed column
  summaries already held in memory, including cached neighbouring columns, using conservative
  bounds on raw terrain and feature heights from the loaded definitions. Queries return
  `UNKNOWN` when required cached facts or feature bounds are unavailable, and never build, load,
  prepare or wait to obtain them. `EMPTY` certifies that ordinary generation into a fresh primer
  produces only air and no 3D biome entries, including features rooted across column boundaries.
  Eviction may make later queries unknown but cannot invalidate an earlier `EMPTY` result.

## Adopted 2026-11-03

- **Tidal creeks hold the sea's water as land.** A marsh's creek columns stay land, so the marsh
  keeps its biome, and take the sea's water level as their own, wet as a stream is (terrain-v114).
  A creek grows from the shore through the marsh's flat between banks that hold its water, so it
  is sea-fed and cannot spill. Rejected: marking creek columns marine (ocean biome strips through
  the marsh) and dry channels.

## Adopted 2026-11-02

- **No wonders.** The generator has no wonders (pillar forests, tepuis, hoodoos, tower karst,
  sinkholes, fin arches, tsingy, ring structures, basalt columns), no wonder site lane, strata
  palettes or wonder void carving (removed at terrain-v112 at the user's direction). Arches and
  stacks are coast forms.
- **No compatibility code.** Tools serve the current tree only: the atlas sampler compiles
  against the present layout and stages, with no renamed-package copies, pre-catalogue
  fallbacks or entries kept for retired stages and lanes.

## Adopted 2026-11-01

- **A data-driven engine.** Riven is an engine in Java, and data configures it. Definitions choose
  blocks for conditions the engine defines and set its numbers; they never express logic, and Riven
  gains no extra language. Configuration is something that can be done, not the paradigm.
  Definitions are read once at initialization and built into immutable, array-backed structures,
  so generation reads no strings, maps or JSON. Reproducing the earlier world is not a goal: each
  step proves its own world correct by properties (determinism, well-formed runs, role rules, held
  water, cost, the atlas diversity target) rather than by matching the old one. The engine lands in
  buildable, playable steps on two tracks in parallel, blocks (B1 to B5) and surface (S1 to S7),
  each raising the terrain version. The generator review's CI expansion waits behind this work.
  B1 (terrain-v100) and S1 (terrain-v101) are the first steps; `docs/ARCHITECTURE.md` describes
  what has landed.
- **The column: zones, horizons, cavities and roles.** A column is assembled once per zone, as
  runs of catalogue entry, role and zone (`worldgen.engine`, Minecraft-free), and every per-block
  answer reads those runs. Zones are data from a fixed menu: boundaries by `y`, `depth` or
  `horizon`, nesting, absence in a setting, and transition bands where both zones' rock blends,
  including around a carve that crosses them. Every zone boundary is a surface; geology publishes
  the unconformity and the basement top as horizons, and a horizon a column lacks makes what reads
  it absent. The default tree is the crust (surface, skin, layered, basement and root sub-zones),
  the **beneath** and the **infernal** (`nether` becomes `infernal`). A zone holds a list of
  cavities, open space with ground: the crust's is the sky, and the realms have inner ground
  between floor and roof fields, with tunnels and caves winding through it. Overlapping cavities
  join, soil forms only at a cavity's floor, and realm cavities may hold deposits and built forms.
  Roles are a fixed vocabulary (solid: `top`, `soil`, `subsoil`, `bed`, `deposit`, `roof`,
  `lining`, `floor`; open: `air`, `fluid`, `decor`), and modifiers such as ore act only inside
  their role and zone masks.
- **Caves and fills.** Shape operations run in a fixed order (raise, lay, build, carve, pocket).
  Each cave form picks a carve family: `field`, `sphere`, `segment`, analytic `worm`, or
  `entrance`, the only families that may breach a cavity floor. Worms carry trunk routes, lava
  tubes and karst tubes, and field families carry the crust's mazes and networks, which stay its
  identity. Routes between zones are left to chance (worm density is a parameter), and worm
  mouths may open onto realm cavities. Fill precedence is fixed in the engine: lava, pocket,
  pool, flood, cavity fluid, then air. Every carve keeps a roof band under each cavity floor (8
  blocks under dry ground, 16 under fluid); the numbers are data, the rule is the engine's.
- **The surface: land share, provinces, coast and drainage.** The surface is a chain: outline
  with land check, provinces, coast plan, base ground, drainage, landform primitives, biomes,
  blocks. Land targets a 45% mean, held by redrawing an outline that misses its band (35 to 65%);
  the band is data and the mean is a target the atlas measures. Provinces are a seeded regional
  map precomputed as a pure plan, covering sea (a few ocean classes, such as warm or cold, deep or
  shallow) as well as land. A province is the parent of its biomes and owns the rules that ease
  transitions between them, blending recipe parameters, not heights, at its margins. The coastal
  band's width is set per coast type. Drainage comes after the coast plan: a coarse network per
  land body, a pure function of seed and definitions held in a bounded memo and prepared ahead of
  demand within bounds, and fine channels refined per region on demand, never over open sea. A
  feature that never appears is a tuning failure of the 8 km world's diversity, not a reason to
  loosen its rules.
- **Province types from facts later shaping keeps, and heights in four layers.** A province's
  type is chosen from plate tectonics (convergent boundaries give ranges, divergent ones rifts),
  the distance to the coast and a seeded level field. Never from base height, uplift or crest,
  which the provinces' own relief recipes will replace. Every province has a land face and an
  ocean face, and each column takes its own ground's, so the budget counts every province's land.
  Foreland is budgeted like the other types, taken after the tectonic types by the provinces
  nearest a range. Stable cells take their nearest type with room in one capacitated assignment,
  so the budget is the mix itself rather than a correction of weak scores; the budget is data
  (shares, enabled). Heights are built in four layers, each owning
  one scale: the province's band (plain 5–35 above the sea, basin −5 to 25, foreland 30–90,
  upland 60–130, tableland 90–170, range 110–280 with peaks to 288, rift floor 10–50 and shoulders
  80–150), character patches of 300 to 800 blocks (strong ones on about 15% of a province), texture
  of 16 to 128 blocks and micro variation of 2 to 8 blocks. Roughness grows with height, and only
  the calm character is planar.
- **Plates own the mountain belts.** High ground comes from one tectonic source: convergent
  plate boundaries carry mountain belts, which base elevation, the mountain stage's massif,
  hydrology and the provinces' ranges all read. The outline's authored continent crests are not
  read; continents stay as the outline draws them. Plates are a power diagram read at warped
  coordinates, so they vary in size and their boundaries curve. Belts vary along their length,
  and uplift is signed, lowering where plates spread. A province's belt is the mean over its
  land, a province-scale fact rather than one point's. About 12 plates per world.
- **Biomes within provinces.** A province restricts its biomes to a subset: strong expressions
  toward its centre, mild members between, and shared transition biomes at its edges, so
  climatically opposite biomes are never neighbours. Ecotone widths are per biome pair in data
  (about 16 blocks for abrupt pairs, 64 for gradual ones). Provinces carry bounded climate offsets
  (about ±4 °C and ±0.1 moisture) blended by intensity, and effective climate is published once
  for every reader. Elevation reaches biomes as climate, through a lapse rate on the smoothed
  height before landforms; snowline, treeline and alpine cover are column-height settings with
  the same lapse rate.

- **Art lives outside the repositories.** Commissioned art is fabricated by Asset Factory
  (`orthrus-research/AssetFactory`), organized and catalogued by Riven Resources
  (`orthrus-research/RivenResources`), and kept in the art store; none of the three repositories
  takes new commissioned textures. The sets already imported (Decoration Library 01, Requisitions
  02, 03, 05 and 06: 954 files under `tools/content/riven-resources` and their copies in
  `assets/riven`) stay at the tip until the art pack exists; history is not rewritten. Riven will
  ship its art as a resource pack: generation needs none of it (no Riven code reads assets, and
  1.12 servers never load resource packs), so the JAR keeps registration, definitions,
  blockstates, models, lang and Riven's own painted textures as placeholders, and the pack
  carries the commissioned textures under their `riven:` paths with Survey Plate's vanilla
  redraws. The commit that makes the build take textures from the pack removes the commissioned
  textures from the tip. Requisitions 04 and 07 and the Survey Plate rework arrive by the pack.

## Adopted 2026-10-30

- **8-core baseline that scales up.** Designs and default configuration assume the median player
  has 8 cores and must serve them well, then keep improving up to 24 cores. Thread pools, budgets
  and queue sizes derive from the machine's processor count with that baseline in mind, not from
  fixed numbers tuned on the development workstation (an i9-13900K, 24 cores and 32 threads).
  This is a design assumption; measurements run on the workstation as it is.

- **Own the Distant Horizons pipeline.** DH 3.3.3 is the long-term base; few upstream updates
  are expected. Cubature takes ownership of DH's generation, scheduling and delivery behavior
  and tunes it, preferring code we control over DH's defaults wherever we can change it: its own
  code where DH's API allows, Mixins or ASM on DH otherwise (each patch under DH's LGPL-3.0-only
  terms, bound to 3.3.3). A fork stays possible if patching stops being enough; it would need its
  own decision, since it supersedes the commitment that this project maintains no derivative DH
  distribution ([PROJECT-COMMITMENTS.md](PROJECT-COMMITMENTS.md)).

- **Cubature supplies Riven's in-build executor.** Riven's in-build parallelism (drainage scans
  and network builds, hydrology plan builds, and any later parallel regional build) runs on an
  executor that Cubature supplies. Cubature sizes it from the processor count inside its worker
  budget, where its governor sees it. It never runs on the JVM's common pool, and tools outside the
  game supply their own bounded executor. Riven starts no threads of its own, and results stay a
  pure function of seed, definitions and layout scale. Evidence (`RegionalCacheProbe`, 12 players,
  cold pass): drainage network builds still left 49 s of waiting on the common pool at 8 km and
  86 s at 32 km. That pool holds P - 1 threads outside the budget, and its bursts lined up with
  98 ms ticks in the land run. Shrinking it instead stretched 32 km network builds from 4.7 s to
  13.1 s on average and 41 s at worst.

- **Preparation within bounds.** Preparing pure regional plans before a worker needs them is
  encouraged: hydrology plans, drainage networks and region tiles built ahead of the players'
  motion, the native view and Distant Horizons' reach, on Cubature's bounded pool. Preparation
  becomes too planned, and stops, when any of these holds:
  - it changes content, or lets a result depend on what was prepared or in what order (prepared
    values are the same pure functions, published through the same bounded memos);
  - it exceeds the derived cache's disk budget, or, without persistent storage, prepares more
    than the memos hold with headroom so plans are evicted before they are read;
  - it runs ahead of demanded work: the native view comes first, then previews, then preparation,
    within the governor's permits and never on the server thread;
  - without persistent storage, it reaches further than players can arrive before the plans
    would be evicted.

  Order-dependent state and unbounded memory stay forbidden.

- **Bounded pools for preview sampling.** Cubature may run one bounded preview pool per server
  in place of DH's World Gen executor for Distant Horizons preview sampling, sized from the
  processor count and paced by its own governor (Cubature `docs/LOD-DELIVERY.md`, step 3). DH
  still chooses which tile comes next and owns its request limits, propagation, persistence and
  delivery. The pool changes when previews are produced, never their content, and the native view
  still comes first.

- **Cleanroom on Java 25 and later only.** Riven and Cubature target the latest Cleanroom on Java
  25 or 26, and no Java 8 compatibility will ship. There is no Forge 14.23.5.2860 / Java 8 build,
  and the atlas and checks compile with `--release 25`. Code on critical paths uses modern Java
  where it helps instead of working around Java 8; for example, the build pool can take a hard
  thread cap and a governor can resize it live.

## Adopted 2026-10-29

- **Survey Plate art direction.** Every block Riven draws, and every vanilla block through Riven's
  art pack, is drawn in one language: native 16 x 16 faces of large connected forms, ink only
  where forms meet, five-step hue-shifted ramps from one palette, a saturation budget that leaves
  color to ores, gems, fluids and light, and a color script by climate and depth
  (Riven Resources' `docs/ART-DIRECTION.md`). It replaces the naturalistic direction of Riven
  Resources' style guide 1.2; the 3,693 received resources are redrawn under their IDs
  (Requisition 08). Items, mobs, the GUI and the sky stay vanilla. labPBR maps cover only the
  textures where light matters. Riven Resources gates its productions itself on
  `tools/survey_plate.py check` and a calibration set; vanilla's noisy faces and the
  received quiet naturalistic faces both fail those tests.

- **Cubature delivery mode.** `deliveryMode` is Cubature's only delivery input; there is no
  `incrementalMaintenance` option or `cubature.deliveryExperiment` JVM property, since together
  with BASELINE they gave an unnamed mode no test covered, while Riven ships PREFETCH. The stage
  is read when the server starts and shared by every dimension (`RequiresWorldRestart`).
  `maintenanceUnloadsPerTick`, 64 by default, applies wherever incremental maintenance runs. Lab
  and measurement tools record `deliveryMode` in `cubeExperiments`; a legacy
  `incrementalMaintenance=false` in an older config reads as BASELINE.
- **Explicit generation timing in Cubature.** Only delivery requests (cube and column watchers and
  ahead preparation) hand generation to the workers; every other request gets real terrain on the
  server thread. A
  request that does not schedule never treats work in flight as absent: it runs the job if no
  worker has started it, or waits for the worker, so population never depends on worker timing.
  Since terrain-v123 Riven's population reads only inside its pregeneration box (the cubes above
  and below), where it had made about 2,000 synchronous reads a minute of PREFETCH flight.
- **Riven owns reeds.** The vanilla decorator's reed stage, which placed no reed on Riven ground
  where measured but made 61% of population's reads outside its box, does not run in Riven worlds
  (no biome decorator does, terrain-v123). Ground cover places Riven's own Common
  Reed, a cane block, at water margins in warm and temperate biomes. It grows on gravel and sandstone
  as well as grass, dirt and sand, where vanilla sugar cane would have fit about 40% of the margins.
  Each piece drops a sugar cane, so the item stays obtainable; planted, it grows vanilla sugar cane
  under vanilla's rules.
- **Cubature's layer boundary.** The cube provider and lighting sit below cube delivery and never
  name `PlayerCubeMap`: they reach the world's player map through `CubeDemand` and
  `SurfaceHeightListener`, which the map implements, and Cubature's `TestLayerBoundary` holds the
  rule. Garbage collection stays with the map, where upstream put it, because it must run right
  after the map reconciles player positions. The lab's probes in Cubature's own packages are
  same-package tests; the members they use stay package-private.

## Adopted 2026-10-28

- **Shader packs.** Riven targets three packs under Demonica, each pinned by hash in
  `runtime.lock.json` (`shaderPacks`) to an official release: BSL 10.1.8, Complementary
  Reimagined r5.9.3 and Complementary Unbound r5.9.3. A targeted pack
  lists Minecraft 1.12.2 in the pinned release itself (one zip often spans 1.7.10 to 26.3 and
  branches on `MC_VERSION`, and later releases may drop 1.12.2), carries DH programs
  (`dh_terrain`, `dh_water`) so the same pack shades distant terrain, and passes
  `tools/tours/shader-packs-8675309.json` before its pin moves. Pack zips stay local, never
  committed or redistributed. Complementary's license (1.7) allows no modified copy, block
  property additions included, so its packs run unmodified. Its cave fog follows depth below a
  constant Y61.9 that no uniform reaches; Cubature carries an opt-in patch (`complementaryCaveFog`,
  off by default) that follows the camera's depth below its column's surface instead. Development
  instances turn it on; nothing distributed enables it until Complementary's authors permit it.
  Shader support adds no mods: Euphoria Patches, a Forge mod on 1.12.2, and other pack add-ons are
  out. Solas 3.8 is the reserve; it keys biome effects to named `BIOME_*` constants, which
  `/riven shaderprops` leaves alone. Excluded: Bliss, Photon, Rethinking Voxels and Super Duper
  Vanilla (no 1.12.2 release), Miniature and Lux (no DH programs), and MakeUp Ultra Fast 9.6 (its
  1.12 `block.properties` has no entry for flowers, saplings, vines, lily pads or glowing blocks).

## Adopted 2026-10-26

- **Overworld ground and rivers.** After a review of the overworld, in this order: soil before
  rock (rocky coasts bare only in the splash zone and on cliff faces, cut floors keep soil where
  the climate grows it back, a treeline set by temperature); rivers from per-continent drainage,
  with headwater streams, widths from the drained area, meanders set by slope and size, walkable
  banks and braided reaches, built together; Riven's own ground features (boulders from the rock
  below, forest floors, meadows, more tree species) replacing Biomes O' Plenty's decorators; new
  leaf litter, moss and berry bush blocks; biome variants within each climate by rock, water,
  relief and chance, with rich forests and jungles of varied canopy heights a priority; and
  vanilla animals only, in a spawn table per biome.

- **Depth biomes.** Caves tell a story through the descent: each crust realm (skin, layered deep,
  broken basement, molten root) reports its own `riven:` depth biomes, chosen by its rock, depth,
  water table, lava level and the land's climate (`riven-realms.json` crust rules), and each depth
  biome decides what its caves hold, so a biome's name tells what is there: Frost, Dust and Root
  Hollows near the surface, Drowned and Karst Galleries, Salt Halls and the Layered Deep, the
  Broken Basement, Lava Shores and the Molten Root. Only cubes wholly under the ground report
  them, so the surface keeps its biomes. Vanilla mobs thin with depth through per-biome spawn
  tables. Cubature samples shader biomes at the camera's height.
- **Subsurface climate first.** The parameters come before more biomes: temperature, humidity, air
  and the modifiers they allow, so the biomes they can support become visible. The variety
  proposed for depth biomes needs more discovery; the order of the remaining work and the biome
  names are not settled. Settled: all the backported cave blocks are wanted (moss, glow lichen,
  cave vines with glow berries, tuff, calcite, amethyst); light below Y-213 may vary (lava is not
  the only light there); infested rock and per-biome spawn pressure wait; player effects
  are a mechanism driven by the climate with a mild first set of effects. The player
  effects are a configurable option that ships with Riven (`config/riven/player-effects.json`,
  reloadable); the subsurface climate stays a packaged definition, as generated content must.
- **Underground water and hazards.** After a review of cave water: flood by water body (flood
  cells with rock dams and sump floors); the sealed lens is an underground lake that caves
  can reach; joint mazes are connected networks; the water-curtain pattern is a seep-wall
  decoration (moss, flowstone, drips) at the water table, whose moss and drips wait for the moss
  backport; small water pools occasionally in wet depth biomes; pointed ice, salt and basalt
  formations, with humidity and faults setting density; and unstable stone. Unstable stone
  collapses under a walking player and is safe to sneak across; it is its own block family with
  visual clues, one variant per host rock of the depth biome it lies in. Its art comes from Riven
  Resources, the separate art project behind Riven's textures; the blocks are Riven content. The
  painted crack overlay is final art, and the falls are balanced: most hurt without killing,
  about 10% kill, and 1-2% are severe drops that leave a player a hard way back.

## Adopted 2026-10-25

- **Realms by height.** One cubic world holds what were separate dimensions, as vertical realms
  with Y biomes, reinvented for Riven rather than copied (no Nether mobs, nothing from The Beneath).
  The world spans Y-1664 to Y384: the surface and crust take Y384 to Y-512, the Beneath Y-512 to
  Y-1088 and a Nether-themed realm Y-1088 to Y-1664. Boundaries are open and gradual (no barrier
  layers). Players feel each realm through the subsurface climate's player effects; realm-scoped
  behavior on machines, where its lookup and event router live, and how it ties into GTCEu are
  open.
- **Riven content.** Riven hosts its own blocks, items and assets, declared in
  `riven-content.json` and built into `assets/riven` by `tools/content/build_content.py`. Blocks
  from later Minecraft versions are backported as clean-room reimplementations of their gameplay
  with original art; no Mojang code, textures, models or sounds ship. First: pointed dripstone
  (stalactites and stalagmites with falling, fall damage and dripping) and the dripstone block.
- **Supersymmetry deposits.** Supersymmetry's overworld veins and deposits (pack commit
  `79cc760`) are the catalog's main source, recreated as Riven content with Riven Resources art
  (Requisition 06): 13 host stones, 15 deposit blocks and Riven ore blocks for 86 minerals
  (including 15 Supersymmetry minerals GTCEu lacks). Supersymmetry places platinum (cooperite,
  sperrylite, braggite) in the overworld, so Riven's rare platinum reef follows it; GTCEu's Nether
  and End veins stay out. Riven ore blocks drop GTCEu ores where GTCEu has the mineral, so GTCEu
  processing applies.
- **Cave decoration.** Life thins with depth: a twilight zone at entrances, roots and litter in
  the skin, dripstone and wet life in the layered deep, a dry and nearly empty basement, a sterile
  root of ash, magma and gravel. Glowing fungi are allowed. Gravel pockets fill the deep rock.
  Build order: formations, then floors and ceilings, then walls and the twilight zone.
- **Tree sites.** Riven owns where trees stand and how they grow: a pure site layer
  (`riven-trees.json`) decides position, species and exact height, and Riven draws every tree
  with its own shapes from its own woods, in layered stands per `riven:` biome; no provider or
  vanilla tree or huge mushroom stage runs. The distant preview draws the same trunks and certain
  crown leaves at fine detail and canopy cover at coarse detail, so distant forests match the
  trees that load. Cubature's preview contract accepts light-filtering states (leaves) and tells
  the sampler each cell's footprint.

- **Full worldgen ownership** (proposal "Riven full worldgen ownership", revision 2). Riven owns
  every placement in its worlds: its own `riven:` biomes, its own feature engine and shape models,
  and composable resource mappings that bind features to any installed mod's blocks, so the DH
  preview draws the same models without extra LOD cost. Riven depends on no other mod for content:
  it places every soil, tree, plant, reed, water plant, cactus and pumpkin itself, and Biomes O'
  Plenty, which had lent decoration and blocks while Riven's own were made, has no role, no code
  in Riven (terrain-v124) and no pin: no prepared instance or CI run installs it. No biome decorator runs (terrain-v123): population is Riven's own, a chunk's first animals
  (`riven-population.json`), which writes no block and reads only the cube it spawns into and the
  cubes above and below, so no content depends on the order cubes populate in; terrain, water and
  biome identity stay pure, and Distant Horizons previews draw the same boulders, trees and ground cover as native cubes.
- **Per-world definitions.** Mapping layers (Riven defaults, integration layers for loaded mods,
  pack layers in `config/riven/mappings/`) resolve once, when a world is created, into its snapshot
  (`data/riven/definitions.json`); the world generates from that snapshot for life, and a small
  packet carries its client-side part (fingerprint, biome colors) to each joining player. Content
  is a pure function of seed and the world's recorded definitions; CI and the atlas use the
  packaged defaults.

## Adopted 2026-10-23

- **Subsurface.** The underground is to be the richest and most adventurous part of the world.
  Order: geology first, then ore, then caves; the world floor moves to Y-512; caves are mixed
  and rooted in rock type and open to the sky only at planned 2D entrance sites. Ores: GTCEu:
  Reloaded, Orthrus Research's GTCEu continuation (pinned, optional; it replaced GregTech CE
  Unofficial 2.8.10-beta on 2026-11-10), supplies a catalog of veins and deposits; Riven
  places them with its own geology- and lithology-driven generators in the cube fill, never with
  GTCEu's world generator. Supersymmetry's realistic host stones and their ore variants are
  recreated as Riven content (no Susy-Core code runs). Without GTCEu the same generators place
  vanilla ores. Rock blocks map per provider (vanilla, GTCEu, Supersymmetry). Richness starts from
  a strong default and is tuned from data later.
  Phase 1: a geological-history model (an event sequence traced backward per block); surface
  outcrops come from the geology; chasms both open to the sky (rare, planned in 2D) and hidden;
  hazards are flooding water-bearing layers, lava depths and chambers, unstable sand and gravel,
  and sheer drops. No glowstone or other invented blocks (it does not exist in nature and could
  break GTCEu progression). The rock is the adventure: no underground structures, chests or
  special creatures. No guaranteed routes between depth realms: networks break off and continue
  in lower layers.
  Phase 2 (2026-10-24): the ore catalog is GTCEu's overworld veins only. Nether and End
  veins (platinum, tungsten, uranium, beryllium, monazite) stay out so GTCEu's dimension
  progression holds; root rewards come from nickel, olivine, sulfides and diamonds. Riven may
  compose its own mineral sets from GTCEu overworld vein materials (orogenic gold veins, vanadium
  magnetite layers in gabbro) rather than only GTCEu's exact vein recipes.

- **Cubature delivery scheduler.** Cubature may run bounded worker pools that generate cubes off
  the server thread (Riven's generator is a pure function of seed, definitions and coordinates,
  proven thread- and order-independent) and that write storage continuously, with the server
  thread installing finished cubes within a per-tick budget and blocking on a full write drain
  only at durability barriers (shutdown, explicit flush). Riven itself has no order-dependent
  state. Evidence: server ticks sat near 60 ms while loading or moving because generation filled a
  50 ms server-thread budget, which kept DH's health pause on; save/purge stalls reached 2,024 ms
  waiting for the writer to drain its backlog (lifecycle captures, 2026-10-20).

## Adopted 2026-10-22

- **Two-tier generator.** Deterministic region-plan tiles (continents, coast distance, elevation,
  climate, drainage, placement) plus column synthesis. Allowed: lazily computed region tiles and
  per-continent drainage that are pure functions of seed, definitions and tile coordinates, held
  in bounded caches whose eviction cannot change results, and a bounded per-column sample cache
  for native cubes. Forbidden: order-dependent state and unbounded memory. Every phase is proven
  with `terrain_atlas.py render --verify` and a `compare` against the previous phase.

- **Coast-first landforms.** Protect the coast with a coastal height envelope instead of exclusion
  tests, give the world plate-margin classes and provinces, place features by suitability, and
  decide ocean water at plan level; hills were the first family moved to the envelope.

- **Locked biome layer.** A layer selects and locks each column's final biome after base terrain
  (`Biomes`), on an axis grid with transition bands, with overrides limited to water, shore and
  height. Columns report Riven's own `riven:` biomes (`riven-biome-registry.json`).

## Adopted 2026-10-21

- **Documentation reset.** History lives in [CHANGELOG.md](../CHANGELOG.md); contributions,
  research, dated documents and superseded patches live outside the source tree.
