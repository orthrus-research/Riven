# Decisions

Dated decisions that shape the project. Add one paragraph per decision; mark a decision
superseded instead of deleting it.

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
- **2026-10-19. Generation constraints.** One reviewed concept at a time. No preparation
  coordinator, background graph, terrain raster cache, global drainage simulation, geology or
  decorator framework. Vanilla palette and empty population until a layer is designed;
  `useVanillaChunkWorldGenerators=false`. *Population superseded 2026-10-23 by the Biomes O'
  Plenty decoration provider.* *Partly superseded 2026-10-22 by the two-tier
  generator:* pure per-tile region plans and per-tile drainage are allowed; the other limits stand.
- **Undated. Additional shaping deferred.** Further shaping of water bodies and basins waits
  until the hydrology layers are established.
- **2026-10-20. Static quality checks.** Formatting, linting and type checks
  (`tools/code_quality.py`) gate every change. Contribution integrations v13-v25 were committed
  without builds or tests; their runtime qualification is open.
- **2026-10-21. Parent classifications.** Downstream features qualify against higher-level
  parent classifications. Recorded direction: earlier stages own broad intent, marine/shore
  ownership, supplied-water reservations and legal headroom, and downstream stages consume them
  with explicit reasons and coverage. Not yet implemented.
- **2026-10-21. Water-shape planners.** Both remaining water-shape planners are to be revisited.
  Open.
- **2026-10-21. Volcanoes.** Volcanoes are reserved for hot biomes and will get a dedicated
  design pass; the design pass is still open. **2026-10-22 revision:** a volcano needs a HOT
  anchor, and the cone fades toward cooler columns like cold forms do (terrain-v55), replacing the
  terrain-v37 rule that every support column be HOT.

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

## Adopted 2026-10-29

- **Survey Plate art direction.** Every block Riven draws, and every vanilla block through a
  separate resource pack (Riven Survey Plate, on in prepared instances, off for any pack that
  leaves it), is drawn in one language: native 16 x 16 faces of large connected forms, ink only
  where forms meet, five-step hue-shifted ramps from one palette, a saturation budget that leaves
  color to ores, gems, fluids and light, and a color script by climate and depth
  ([ART-DIRECTION.md](ART-DIRECTION.md)). It replaces the naturalistic direction of Riven
  Resources' style guide 1.2; the 3,693 received resources are redrawn under their IDs
  (Requisition 08). Items, mobs, the GUI and the sky stay vanilla. labPBR maps cover only the
  textures where light matters. Riven Resources gates its productions itself on
  `tools/content/survey_plate.py check` and a calibration set; vanilla's noisy faces and the
  received quiet naturalistic faces both fail those tests.

- **Cubature delivery mode.** `deliveryMode` is Cubature's only delivery input. The
  `incrementalMaintenance` option and the `cubature.deliveryExperiment` JVM property are removed:
  the option with BASELINE gave an unnamed mode (incremental GC driven by player movement, 8 unloads
  per tick, no pre-unload check) that no test covered, while Riven ships PREFETCH. The stage is
  read when the server starts and shared by every dimension (`RequiresWorldRestart`).
  `maintenanceUnloadsPerTick`, now 64 by default (the value staged modes had hard-coded), applies
  wherever incremental maintenance runs. Lab and measurement tools record `deliveryMode` in
  `cubeExperiments`; a legacy `incrementalMaintenance=false` in an older config reads as BASELINE.
- **Explicit generation timing in Cubature.** Only delivery requests (cube and column watchers and
  ahead preparation) hand generation to the workers; every other request, including reads that
  Riven's population makes outside its prepared box, gets real terrain on the server thread. A
  request that does not schedule never treats work in flight as absent: it runs the job if no
  worker has started it, or waits for the worker. This replaces a scope flag under which any read
  during the delivery pass returned air, which made decoration depend on worker timing in
  principle; saved blocks of 82,215 cubes matched across builds, so no observed world changed.
  Riven's population reads outside its box cost about 2,000 synchronous cubes per minute of PREFETCH
  flight; widening its pregeneration requirements would move them to the workers.
- **Riven owns reeds.** The vanilla decorator's reed stage, which placed no reed on Riven ground
  where measured but made 61% of population's reads outside its box, no longer runs in Riven worlds,
  and the provider's stands aside with the stages Riven owns. Ground cover places Riven's own Common
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
  Vanilla (no 1.12.2 release), Miniature and Lux (no DH programs). MakeUp Ultra Fast 9.6 passed
  the tour and was dropped the same day: its 1.12 `block.properties` has no entry for flowers,
  saplings, vines, lily pads or glowing blocks. This supersedes the 2026-10-23 renderer targets.

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
  tables. This takes up the underground 3D biomes that "Renderer targets" (2026-10-25) deferred:
  Cubature samples shader biomes at the camera's height (since 2026-10-25).
- **Subsurface climate first.** The parameters come before more biomes: temperature, humidity, air
  and the modifiers they allow, so the biomes they can support become visible. The variety
  proposed for depth biomes needs more discovery; the order of the remaining work and the biome
  names are not settled. Settled: all the backported cave blocks are wanted (moss, glow lichen,
  cave vines with glow berries, tuff, calcite, amethyst); light below Y-213 may vary (lava is no
  longer the only light there); infested rock and per-biome spawn pressure wait; player effects
  are built now, as a mechanism driven by the climate with a mild first set of effects. The player
  effects are a configurable option that ships with Riven (`config/riven/player-effects.json`,
  reloadable); the subsurface climate stays a packaged definition, as generated content must.
- **Underground water and hazards.** After a review of cave water: flood by water body (flood
  cells with rock dams and sump floors); the sealed lens becomes an underground lake that caves
  can reach; joint mazes become connected networks; the water-curtain pattern returns as a
  seep-wall decoration (moss, flowstone, drips) at the water table; small water pools occasionally
  in wet depth biomes; new pointed ice, salt and basalt formations, with humidity and faults
  setting density; and unstable stone. Unstable stone collapses under a walking player and is safe
  to sneak across; it is its own block family with visual clues, one variant per host rock of the
  depth biome it lies in. Its art comes from Riven Resources, the separate art project behind
  Riven's textures; the blocks are Riven content. Water comes first; flood cells and lakes landed
  in terrain-v88. *Built in terrain-v88: all of the above but the seep walls' moss and drips
  (waiting for the moss backport).* The painted crack overlay is final art, and the falls are
  balanced: most hurt without killing, about 10% kill, and 1-2% are severe drops that leave a
  player a hard way back (terrain-v89).

## Adopted 2026-10-25

- **Realms by height.** One cubic world holds what were separate dimensions, as vertical realms
  with Y biomes, reinvented for Riven rather than copied (no Nether mobs, nothing from The Beneath).
  The world spans Y-1664 to Y384: today's surface and crust keep Y384 to Y-512 unchanged, the
  Beneath takes Y-512 to Y-1088 and a Nether-themed realm Y-1088 to Y-1664. Boundaries are open
  and gradual (no barrier layers). Realm-scoped event behavior on players and machines comes later:
  where the realm lookup and event router live, and how they tie into GTCEu, is still open.
  *Players: built 2026-10-26 (player effects from the subsurface climate); machines and GTCEu
  remain open.*
- **Riven content.** Riven hosts its own blocks, items and assets, declared in
  `riven-content.json` and built into `assets/riven` by `tools/content/build_content.py`. Blocks
  from later Minecraft versions are backported as clean-room reimplementations of their gameplay
  with original art; no Mojang code, textures, models or sounds ship. First: pointed dripstone
  (stalactites and stalagmites with falling, fall damage and dripping) and the dripstone block.
- **Supersymmetry deposits.** Supersymmetry's overworld veins and deposits (pack commit
  `79cc760`) are the catalog's main source, recreated as Riven content: 13 host stones, 15 deposit
  blocks and Riven ore blocks for 86 minerals (including 15 Supersymmetry minerals GTCEu lacks),
  with Supersymmetry's textures borrowed for test builds and replaced before release. *Replaced
  2026-10-28 by Riven Resources art (Requisition 06).* Supersymmetry places platinum (cooperite,
  sperrylite, braggite) in the overworld, so Riven's rare platinum reef follows it; GTCEu's Nether
  and End veins stay out. Riven ore blocks drop GTCEu ores where GTCEu has the mineral, so GTCEu
  processing applies.
- **Cave decoration.** Life thins with depth: a twilight zone at entrances, roots and litter in
  the skin, dripstone and wet life in the layered deep, a dry and nearly empty basement, a sterile
  root of ash, magma and gravel. Glowing fungi are allowed except in the world's lowest third
  (below about Y-213), where lava stays the only light (*superseded 2026-10-26: light below Y-213
  may vary*). Gravel pockets fill the deep rock. Build
  order: formations, then floors and ceilings, then walls and the twilight zone.
- **Tree sites.** Riven owns where trees stand: a pure site layer (`riven-trees.json`) decides
  position, species and exact height, and native population grows each site with the provider's
  generator (BoP's shapes stay), replacing the provider's tree stage in owned biomes. The distant
  preview draws the same trunks and certain crown leaves at fine detail and canopy cover at coarse
  detail, so distant forests match the trees that load. Prototype: BoP woodland first, then biome
  families. Cubature's preview contract accepts light-filtering states (leaves) and tells the
  sampler each cell's footprint. (*Superseded 2026-10-27: Riven draws every tree with its own shapes
  from its own woods, in layered stands per riven: biome; no provider or vanilla tree or huge mushroom
  stage runs.*)

- **Full worldgen ownership** (proposal "Riven full worldgen ownership", revision 2). Riven owns
  every placement in its worlds: its own `riven:` biomes, its own feature engine and shape models,
  and composable resource mappings that bind features to any installed mod's blocks. No other
  mod's generator runs in a Riven world, so the DH preview draws the same models without extra LOD
  cost. BoP and other packs become block libraries, bootstrapped on BoP until Riven's own assets
  replace them.
- **Per-world definitions.** Mapping layers (Riven defaults, integration layers for loaded mods,
  pack layers in `config/riven/mappings/`) resolve once, when a world is created, into its snapshot
  (`data/riven/definitions.json`); the world generates from that snapshot for life, and a small
  packet carries its client-side part (fingerprint, biome colors) to each joining player. Content
  is a pure function of seed and the world's recorded definitions; CI and the atlas use the
  packaged defaults.
- **BoP's overworld biomes are withheld** from the registry by default whenever BoP is present
  (BoP's own `biome_ids.json` switch, set during Riven's preInit; `withholdProviderBiomes` opts
  out), and BoP's world type is hidden. BoP keeps its blocks, saplings and Nether biomes.
- **Renderer targets.** BSL is the one targeted shader pack (under Demonica); other packs are
  outside these deliverables. *Shader packs superseded 2026-10-28 (Shader packs).* Underground 3D biomes wait until after Phase 3, once Cubature samples
  shader biomes at the camera's height. *Underground 3D biomes taken up 2026-10-26 (Depth biomes).* No second mod integration is planned; Phase 4 proves the
  mapping scheme with vanilla-only and BoP-to-vanilla layers.

## Adopted 2026-10-23

- **Subsurface.** The underground is to be the richest and most adventurous part of the world.
  Order: geology first, then ore, then caves; the world floor moves to Y-512; caves are mixed
  and rooted in rock type and open to the sky only at planned 2D entrance sites. Ores: GregTech CE
  Unofficial 2.8.10-beta (pinned, optional) supplies a catalog of veins and deposits; Riven
  places them with its own geology- and lithology-driven generators in the cube fill, never with
  GTCEu's world generator. Supersymmetry's realistic host stones and their ore variants are
  wanted; their resources are extracted from the pack and hosted in Riven test builds as Riven
  test blocks (no Susy-Core code runs), kept apart with provenance, and replaced with Riven's own
  copies before any release. Without GTCEu the same generators place vanilla ores. Rock blocks
  map per provider (vanilla, GTCEu, Supersymmetry). Richness starts from a strong default and is
  tuned from data later.
  Phase 1: a geological-history model (an event sequence traced backward per block); surface
  outcrops come from the geology and retire the climate strata palettes; chasms both open to the
  sky (rare, planned in 2D) and hidden; hazards are flooding water-bearing layers, lava depths and
  chambers, unstable sand and gravel, and sheer drops. No glowstone or other invented blocks (it
  does not exist in nature and could break GTCEu progression); lava is the only natural
  underground light (*superseded 2026-10-26: light in the deep may vary; no glowstone still
  holds*). The rock is the adventure: no underground structures, chests or special
  creatures. No guaranteed routes between depth realms: networks break off and continue in lower
  layers.
  Phase 2 (2026-10-24): the ore catalog is GTCEu's overworld veins only. Nether and End
  veins (platinum, tungsten, uranium, beryllium, monazite) stay out so GTCEu's dimension
  progression holds; root rewards come from nickel, olivine, sulfides and diamonds. Riven may
  compose its own mineral sets from GTCEu overworld vein materials (orogenic gold veins, vanadium
  magnetite layers in gabbro) rather than only GTCEu's exact vein recipes.

- **Novel terrain features.** Strong realism is balanced back toward novel features: exaggerated
  real wonders (pillar forests, tepuis, hoodoos, tower karst, sinkholes, arches) rather than
  stylized fantasy; some land features may carve through rock (arches, overhangs, bridges) with
  the same column-run machinery in native cubes and the DH preview; rare landmarks only, not
  provinces or common texture; existing carvers and landmarks are exaggerated as well. Implemented
  on the prototype branch as `WonderTerrain` (terrain-v71).

- **Biomes O' Plenty as decoration provider.** Biomes O' Plenty (1.12.2-7.0.1.2445,
  CC BY-NC-ND 4.0, pinned in `runtime.lock.json`, compiled against but never bundled or modified)
  is the first worldgen mod, used for its decorators only, as an optional dependency. Riven maps
  its habitat keys to BoP biomes (`riven-bop.json`): (1) decoration is the one recorded exception
  to order independence, since placements that cross chunk borders depend on population order
  (terrain, water and biome identity stay pure); (2) vegetation plus surface patches that swap
  blocks without moving the surface run, but not ores, lakes, boulders, spikes, columns, crystals
  or hives; (3) chunks report the BoP biome (colors, sky, weather and spawns). Riven still decides
  every block; BoP supplies the biome's top and filler blocks where Riven places soil, and never
  runs its terrain or surface routines. Without BoP the vanilla palette and empty population
  stand. Supersedes "empty population" and "no decorator framework" in the 2026-10-19 generation
  constraints. Distant Horizons previews carry the surface blocks but not decorations until chunks
  generate natively.

- **Cubature delivery scheduler.** The two-tier rule against background work is revised:
  Cubature may run bounded worker pools that generate cubes off the server thread (Riven's
  generator is a pure function of seed, definitions and coordinates, proven thread- and
  order-independent) and that write storage continuously, with the server thread installing
  finished cubes within a per-tick budget and blocking on a full write drain only at durability
  barriers (shutdown, explicit flush). Riven itself still has no background jobs, preparation
  coordinator or order-dependent state. Evidence: server ticks sat near 60 ms while loading or
  moving because generation filled a 50 ms server-thread budget, which kept DH's health pause on;
  save/purge stalls reached 2,024 ms waiting for the writer to drain its backlog (lifecycle
  captures, 2026-10-20).

## Adopted 2026-10-22

- **Two-tier generator.** Deterministic region-plan tiles (continents, coast distance, elevation,
  climate, drainage, placement) plus column synthesis, migrated in phases. Allowed: lazily
  computed region tiles and per-continent drainage that are pure functions of seed, definitions
  and tile coordinates, held in bounded caches whose eviction cannot change results, and a bounded
  per-column sample cache for native cubes. Still forbidden: background jobs, preparation
  coordinators, order-dependent state and unbounded memory. Every phase is proven with
  `terrain_atlas.py render --verify` and a `compare` against the previous phase. Continents 4-20 km
  across; land target as set under Coast-first landforms below.

- **Coast-first landforms.** Protect the coast with a coastal height envelope instead of exclusion
  tests, give the world plate-margin classes and provinces, place features by suitability, and
  decide ocean water at plan level. Modifications: the land target is biased toward a mean of 40%
  land, preferring more land to less (supersedes the 25-40% working target above); hills are the
  first family moved to the envelope; the envelope also yields a height class that combines with
  climate into biome sub-classes (for example HIGH + HOT + DRY = a high-mesa sub-class;
  illustration, not a fixed list).

- **Locked biome layer.** A layer selects and locks the final biome, about 250 biomes with
  transitions across the major inputs: hybrid identity (logical biomes mapped to vanilla IDs now,
  a curated registered subset under about 195 later), locked after base terrain, overrides limited
  to water, shore and height, and an axis grid with transition bands. Implemented as `Biomes`
  (terrain-v44, 260 biomes); it supersedes the v41 sub-class table.

## Adopted 2026-10-21

- **Documentation reset.** History moved to [CHANGELOG.md](../CHANGELOG.md); contributions,
  research, dated documents and superseded patches moved out of the source tree.
