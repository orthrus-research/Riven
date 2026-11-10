# Runtime

## Profile

`runtime.lock.json` is the complete mod input list; every external artifact is pinned by hash.

| Component | Version | Role |
| --- | --- | --- |
| Minecraft | 1.12.2 | Target game |
| Cleanroom | 0.7.9-alpha, official and unchanged | Loader; supplies MixinBooter and ConfigAnytime |
| Java | 26.0.2.1, 8 GiB heap, ZGC per JVM | Native runtime and development compiler |
| Cubature | `dev/pins/Cubature-cleanroom.jar` (`dev/cc.lock.json`) | The Orthrus Research CubicChunks fork: scheduling, lighting, lifetime, storage, delivery, DH integration |
| Distant Horizons | 3.3.3-1.12.2, official upstream (`dev/dh.lock.json`) | Distant terrain renderer and delivery, both sides |
| Fugue | 0.25.5 | Modern-Java compatibility layer |
| Scalar Legacy | 1.0.1 | Scala 2.11 provider |
| GregTech CE Unofficial | 1.12.2-2.8.10-beta, official upstream (`runtime.lock.json`), with CodeChickenLib 1.12.2-3.2.3.358 | Optional ore provider (prototype): `riven-ores.json` transcribes its overworld vein catalog, Riven places the bodies, and GTCEu supplies the ore block for each mineral and host stone type; GTCEu's own world generator never runs |
| Demonica | 0.9.0, official release (`runtime.lock.json`, optional `shaders`) | Client shader stack: Iris on its bundled Celeritas d94ec1e5; installed only by `prepare --shaders` |
| Shader packs | BSL 10.1.8, Complementary Reimagined r5.9.3, Complementary Unbound r5.9.3, official Modrinth releases (`runtime.lock.json` `shaderPacks`) | The targeted packs under Demonica (docs/DECISIONS.md, Shader packs); local zips in `dev/pins/shaderpacks`, never committed or redistributed |
| Riven | `build/libs/riven-0.0.1-cleanroom.jar` | Terrain, water, biomes and block content |

A Riven world spans Y-1664 to Y383: the crust down to Y-512, then the realms
(`allocation` in `riven-terrain-height-reference.json`, realms in `riven-realms.json`).
Cubature fixes a new world's height range, including where out-of-world damage starts, from
`defaultMinHeight` and `defaultMaxHeight` in the server's `config/cubicchunks.cfg`, not from
Riven's generation range: set them to -1664 and 384 before creating a world. `runtime.py prepare`
writes them from the height reference; with the old -128 a test player teleported to Y-500 took out-of-world damage and died.

Distances are 12 on all four native axes (server horizontal and vertical, client render and
vertical). Cubature development in its checkout may be ahead of the pinned artifact; the pin is what
instances use.

Cubature keeps `cubicchunks`/`cubicchunkscore` mod IDs, `io.github.opencubicchunks` packages,
registry IDs and save formats as compatibility interfaces. It is published as
`orthrus-research/Cubature`, a GitHub fork of OpenCubicChunks/CubicChunks, and checked out beside
Riven at `../Cubature`, the path `dev/cc.lock.json`'s `checkout` names. Riven's tools find it through
`tools/checkouts.py` and export `RIVEN_WORKSPACE`, which Cubature's tools and Gradle build read to find
Riven (without it they assume a sibling `../Riven`). Cubature never lives inside Riven, so nothing
that cleans or deletes Riven's ignored files can reach it.

## Derived regional cache

When the host supplies a derived-data directory, server worlds use a sibling of the save named
`<save-directory-name>.cubature-cache`. A null directory leaves generation memory-only.
Riven namespaces its drainage files there by the complete generation identity and kind/codec
version. A memo miss loads a checked file or builds the same pure value and writes it on the
builder's thread. Store ownership follows biome-provider construction, world unload and server
stop. Offline tools use memory-only constructors unless a check explicitly supplies a store.

The directory is derived data: removing it makes later misses rebuild the same terrain. Files
remain until removal; the current cache has no automatic disk eviction. Memory hits return the
held value directly, and a later memory miss can rebuild a missing file.

## Distant Horizons

DH is installed byte-for-byte from the official release on client and server. Cubature binds a
seed-only surface provider (`ICubicTerrainPreview`) to DH's executor and normal server delivery;
Riven supplies the sampler, which shares native block rules and maps; the sampler learns each
preview cell's footprint and may emit light-filtering blocks such as leaves (sky light falls by
their opacity per block), which Riven's tree sites use. Capable cubic levels
negotiate SURFACE_ONLY; other cubic generators must not fall back to DH's vanilla chunk
generator. Newer compatible DH releases are adopted by an explicit pin update.

Fresh instances set, on both sides: 2,000 decimal KB/s per player, 40 generation requests per
second and outstanding, eight shared DH workers per JVM, LOD radius 256 chunks, refinement active
during movement, server-health guard on. `prepare` accepts `--dh-bandwidth-kbps`,
`--dh-request-limit`, `--dh-workers` and `--dh-pause-when-moving`, and for experiments
`--dh-ignore-server-health`, `--dh-adaptive-transfer`, `--dh-progress-log` (DH's 2-second
generation progress in the server log) and `--dh-native-feed edits` (Cubature hands DH only edited
columns); launch checks the recorded settings. `riven_observe.py
capture` passes `--dh-workers`, `--dh-ignore-server-health` and `--dh-native-feed`, and
`--dh-visited-only` installs DH on the client only, fed by native cubes with no distant generation
(the native-only floor; it excludes `--lod-trace`). Existing instances keep their frozen settings.

The pinned Cubature generates cubes and prepares columns on worker threads for Riven,
which declares concurrent generation (`concurrentGenerationThreads`: a quarter of the processors,
1 to 4); the server thread installs them within `concurrentGenerationTickMillis` (20 ms).
Interactive preparations set Cubature `deliveryMode=PREFETCH` (`--cubature-delivery`): incremental maintenance,
continuous autosave discovery and ahead preparation. Observation captures, including the
`full` workflow's smoke, default to `BASELINE`; `riven_observe.py capture
--delivery-experiment prefetch` selects the other mode explicitly. Compare captures made in
the same delivery mode. A teleport (`/tp` and any other `setPlayerLocation`) into an unprepared
column waits while workers prepare its destination, with urgent regional preparation ahead of
ordinary work. It remains held until ready, replaced, abandoned or explicitly forced. Players
receive a preparation message at most once a second; waits beyond 600 ticks produce a diagnostic
instead of forcing generation on the server thread. The optional retained-column contract keeps
prepared data through installation. Generators without that capability keep their existing
readiness and failure path; worlds without generation workers arrive immediately. Respawn,
portal and join arrivals still load their column at once.

DH pauses its world-generation executor while the server is unhealthy (average tick 25 ms or more,
or 99th-percentile tick 50 ms or more) or more than 1,000 chunk-to-LOD tasks are queued, and all
of its executors share `--dh-workers` threads. Stationary measurements on 2026-10-23 (v56, 5 min
after join): the horizon queue drained in about 42 s with defaults, 29 s with 8 workers and 24 s
with 8 workers and the health pause off; bandwidth and request limits were not limiting, and
adaptive transfer (start 50 KB/s, +50 KB/s per second, cut to received minus 25 KB/s) was slowest.

Cubature corrects three DH rendering assumptions that hold only for vanilla height. DH's section
frustum takes `min + getMaxHeight()` as its top, while its other callers read `getMaxHeight()` as
the top Y; a client mixin passes `max - min` there, so sections are culled against the level's real
span (the old box of Y -512..-128 dropped sections 300-570 blocks out when seen from altitude). While
a cubic world is loaded, DH's `vanillaFadeMode` is overridden to NONE through its non-persistent API
(the vanilla volume is vertically bounded, so the fade blended terrain into clipped LOD and sky);
leaving restores the player's setting. Native-fed snapshots report each column's lowest received
opaque block, or stone, below the received slice, so visited columns stay solid to the floor. DH names shader materials by block class and modern registry names, so 1.12 `minecraft:grass` and most modded rock, soil and plants reached shader packs' LOD passes as UNKNOWN; Cubature gives those the material their Minecraft material implies (grass, dirt, sand, stone, wood, metal, leaves, snow, water, lava).
A cube with fewer than 64 changed blocks reaches the client as block changes, which the client
relights itself; more resends the whole cube with the server's light. Cubature applies the blocks
before taking the server's column heights, so a block that rises above its column's top relights
the column as it does on the server. Taking the heights first left the edited columns of a cube the
client held as empty unlit above the edit (sky light 0 over a new slab where the save held 15), a
dark band on smooth-lit faces on both renderers (`tools/tours/sky-light-band-8675309.json`,
`tools/tours/stones-8675309.json`). For such checks, `riven.lightDump=R` (passed through
`JAVA_TOOL_OPTIONS` to `play`) writes the client's sky and block light within R blocks of each tour
view to the client's `riven-light/`, to compare with the saved world. Open: the first capture after
such an edit sometimes shows a ring of stretched distant-terrain walls around the edited columns
(seen with the pinned Cubature), gone by the next view a few seconds later.
Under Demonica, Celeritas replaces the vanilla terrain renderer. It reads 16 sections per column
from `Chunk.getBlockStorageArray()` and learns columns from `ChunkProviderClient.loadChunk`, which
Cubature's client cache overrides, so unpatched it drew no terrain near the player in a cubic world.
Cubature gives its section manager the world's full height (sections -104 to 23 in Riven), clones
section blocks and tile entities from cubes and reports columns to its chunk tracker; the vanilla
vertical-render-distance mixins stand aside when Celeritas is present. A cube without storage is all
air with implicit sky light, none below its column's top; Celeritas read it as its own empty section,
which has full sky light at any depth, so cave walls beside such a cube drew a daylit rectangle one
cube wide (Riven's caverns hold many: 239 below the ground within 384 blocks of spawn on seed
8675309). Cubature gives it a section holding its implicit sky light, and those walls are dark as on
the vanilla renderer (`tools/tours/celeritas-empty-cubes-8675309.json`, checked 2026-10-27 with and
without BSL). Celeritas gathers the entities it draws from each column's 16 entity lists, which stay
empty in a cubic world, so mobs were missing from the main pass while Iris's shadow pass, which reads
the world's entity list, still drew their shadows; Cubature hands the gatherer the world's list.
Celeritas's renderer also starts its build height at 0 and treats boxes outside it as visible, so
entities below Y 0 escaped occlusion culling; Cubature starts it at the world's floor. Unpatched,
Celeritas creates a render section for every section of a column it learns about, loaded or not:
128 per column since the realms, 67,712 at the 12-chunk view against 15,625 loaded cubes, and its
occlusion search could wander through unloaded sections, which read as empty. Cubature gives a
ready column sections only for its loaded cubes and adds or removes a cube's section where
Celeritas applies its own column events (13,225 at that view). Frame rate did not change: on one
persisted world (seed 8675309, shaders off, five surface views, 15-second averages) both ran at
330-608 fps, and retained heap was about 75 MB lower. Fresh worlds measure lower and
noisier, because the server on the same machine is still generating DH tiles (one view ran at
228 fps fresh and 584 persisted), so compare frame rates on persisted worlds. The targeted packs
(DECISIONS.md, Shader packs) shade near terrain and DH LODs under Demonica: BSL 10.1.8 (its
`block.properties` lists 1.12 names) since 2026-10-25 with the woodland tour, and Complementary
Reimagined r5.9.3 and Complementary Unbound r5.9.3 on 2026-10-28
(`tools/tours/shader-packs-8675309.json`: coast and high horizons, woodland, a chasm at Y-60,
night horizon and lights, one persisted instance, every pinned pack and no pack). Every pack
captured all six views without a shader compile error, drew the far islands and water in its own
fog and color, and lit the sky slit above the chasm; frame rates held the 120 fps cap except on
the woodland canopy (Unbound 102, Reimagined 114) and BSL's first view. Complementary drops one
custom uniform, `inSulfurCaves`, which names a biome 1.12.2 lacks. DH logs "Unexpected; somehow
the Opaque + Translucent pass ran with shaders on" once per run with any pack, as on every
earlier BSL instance. Complementary places its clouds at `cloudHeight` (Riven's Y256, and a
second layer 96 above) and reads the same world uniforms as BSL, which Cubature already gives the
cubic world; its license allows no modified copy, so `/riven shaderprops` must not be used on it
and Riven's plants and glowing rocks keep vanilla handling there.
Complementary sets its cave fog by depth below a constant Y61.9 (`GetCaveFactor`,
`oceanAltitude`), capped by the eye's sky light, not by enclosure. Riven caves above sea level get
none and those below get its gray fog: on `tools/tours/shader-caves-8675309.json` the dripstone
cave at Y137 is as clear as under BSL, the great cavern at Y-54 is fogged. No world uniform
reaches that constant. Cubature's opt-in `complementaryCaveFog` (`prepare
--complementary-cave-fog`) rewrites that one line as Demonica reads it to use `cubicSurfaceDepth`,
the camera's depth below its column's top block. Toggled on one persisted instance (2026-10-28,
two runs each way), the dripstone cave's far contrast halves
(standard deviation 15.5 to 8.0, identical across runs) while the great cavern's stays 8.5, frame
rates hold the 120 fps cap either way, and the six surface views of the shader-packs tour are
unchanged. Open: Riven's local
instances run with it on; nothing distributed enables it until Complementary's authors permit
the change, since its license grants none (contact: https://www.complementary.dev/contact).

Riven's cloud base is Y256 (`atmosphere.cloudBaseY` in the height reference) through
`WorldType.getCloudHeight`, which vanilla clouds and Iris's `cloudHeight` uniform both read: BSL's
default clouds sit above ordinary terrain and below high peaks. BSL keys its 1.12 biome effects
(`isCold`, `isDesert`, `isSwamp` and others) to vanilla biome IDs, not `biome_category`, and lists
no modded blocks. `/riven shaderprops [player]` asks that player's client to write
`shaderpacks/<pack>-riven`, a copy of the active pack. In the copy, `block.properties` gives each
modded block the entry of the vanilla block it behaves like, and every numeric biome list holds
the riven: biomes whose vanilla twin it lists. Riven biomes using IDs the pack lists for other
biomes (BSL's 48 and 49 under `isJungle`) leave those lists. Rules and twins are in
`riven-shaderprops.json`, and the copy carries a `riven-shaderprops.txt` report. Rain hills, taiga
and stone beaches have no twin, because BSL marks their vanilla twins cold above Y93 and Y123.
On BSL 10.1.8 the export maps Riven's plants, flowers, double plants, leaves, vines, lily pads,
saplings and ground cover, GTCEu's rubber trees and ores, and every full cube that gives light,
which joins glowstone (`lightAtLeast`): the deep realms' moonstone, molten seams, soul ash and
heat-cracked eclogite, and GTCEu's 32 lamps (414 block names on 2026-10-27, when Biomes O'
Plenty's blocks were installed too). BSL lights an
emissive pixel by its brightness and saturation, so those rocks glow along their streaks and cracks
while the rock around them stays dark (`tools/tours/lighting-8675309.json`, night and sealed-chamber
views). It adds 32 biome IDs, and Demonica gives all 73 riven: biomes their intended
`biome_category`. Leaves wave: on the woodland view of
`tools/tours/shaders-8675309.json`, two frames from one camera differ only on leaf canopies and
plant tufts (0.8% of pixels), while terrain, trunks and shadows stay identical. With the
`-riven` copy active, the modded flowers and plants on that view wave too (1.0%), and the copy loads
without block-name warnings. `prepare` gives Riven clients fancy graphics (the lab's benchmark
default is fast): fast leaves are opaque cubes, so the faces beneath them are culled and waving
leaves open holes to nothing. `tools/tours/render-check-8675309.json` builds a grass platform with
a leaf hedge and four animals at Y200, then an animal pocket at Y-700 in solid Beneath rock, seen
from a pocket one full section away and again through a tunnel; prepare with `--spawn-animals`,
because a server without it removes summoned animals. Each tour capture logs the F3 counters
(`Riven view stats`: frame rate, Celeritas sections visible/total, entities rendered/total, loaded
cubes/columns) and the heap after a collection.
Demonica reports `bedrockLevel` as 0, and BSL fades sky, fog, clouds and sun within six blocks
below it. Cubature replaces that uniform with the cubic world's floor (Y-1664 in Riven), so a
camera at Y-60 in a chasm sees the sky, which was black before (`tools/tours/shaders-depth-8675309.json`).
Cubes wholly under the ground report Riven's depth biomes (FEATURES.md, Realms and depth biomes),
which Demonica's biome uniforms follow at the camera; their void parent has no shader twin. Vanilla
reads a column's rain or snow, and a snow golem's melting, from the biome at Y 0, so Cubature reads
them at the precipitation height and where the golem stands in cubic worlds, and surface weather
is unchanged.
`tools/tours/dh-artifacts-8675309.json` captures the views that showed these faults (high-south,
high-east-down, high-west, low-coast); compare like with like on instances whose LODs have
persisted, since a fresh world fills about 500 tiles in its first three minutes.

Flying fast toward terrain seen from afar tears DH's LOD terrain (reported 2026-10-25). Read from
DH 3.3.3: its client quad tree counts a render section as ready once a buffer is uploaded, and a
position with no stored data reads as `FullDataSourceV2.createEmpty` and uploads an empty one, so
drawn terrain can be swapped for nothing until the data request returns; it requests data only for
the sections it switches on; and it swaps a parent with all four children or none. The preview
point-samples each coarse level at cell centers, while DH builds a coarse level from the one below
by a per-slice vote over each 2x2 of columns. Neither fix tried was adopted: holding swaps until a
section has geometry stalled refinement for minutes (one slow child keeps its quad coarse), and a
3-second bound refined fully but showed walls at other moments; building coarse preview levels
by DH's vote
kept the walls, raised flat water at the horizon and slowed tile generation 2.5 times. Open: the
vertical walls at boundaries between sections of different detail, which neither change removed,
and a reproduction of continuous fast flight (a tour that teleports and settles shows the
walls but not the flight tears).

The former `3.3.3-riven.4` DH fork, its patch chain and the first DH queue-backoff candidate are
retired. Their fixes are absent from the official artifact and their measurements do not qualify
the current runtime.

Riven worlds need Riven on client and server alike: chunks report `riven:` biomes, and each
joining client receives the world's definitions packet (fingerprint, biome colors) that DH,
Celeritas and shader packs read.

## Launching

```powershell
riven.ps1 build
python tools/runtime.py prepare --name <fresh-name> [--scale 0.5-8] [--start X Y Z YAW PITCH] [--spectator] [--capture]
python tools/runtime.py play --name <fresh-name>
python tools/runtime.py command --name <fresh-name> --text "<server command>"
```

Instances live under `.local/cubic-standalone/<name>` (seed 8675309, port 25870). `prepare`
records the runtime profile, exact mod hashes and every generator fingerprint; `play` refuses an
instance whose frozen inputs changed. Changed definitions need a fresh instance. `--start` fixes
daylight and weather and teleports on first join; `--scale` sets Riven's layout scale through
`generator-settings` (0.5-8: 1 = 8 km tuning, 0.5 = 4 km, 4 = 32 km worlds; see FEATURES.md); `--capture` saves one native screenshot after
400 client ticks. `--spawn-animals` allows animals (off for terrain captures). `--shaders` adds the
optional shader pins (Demonica) to the client, and `--shader-pack <pack> [<pack> ...]` copies packs
into its `shaderpacks` and enables the first in Demonica's `config/shaders.properties`: a pinned
pack by name (`bsl`, `complementary-reimagined`, `complementary-unbound`),
refused unless its zip matches the pin, or any zip or folder by path (a `-riven` copy). `play
--shader-pack <pack|off>` enables another prepared pack, or none, for that run, so packs compare
on one instance whose DH tiles have persisted. With `--shaders`, `prepare` also turns on
Cubature's opt-in Complementary cave fog patch in the client's `cubicchunks.cfg` (see Distant
Horizons below) for local work; `--no-complementary-cave-fog` leaves it off, as Cubature ships it. `/riven` in game
prints climate, terrain, biome and feature diagnostics at the player's position. `/riven digest
[radius [x z]]` hashes the world's blocks around a point by state name, one line per chunk column
whose band (32 blocks under its lowest surface to 48 over its highest, from the generator's
columns) is wholly loaded, into the save's `riven/digests/<x>_<z>_r<radius>.txt` and one total, so
two worlds of one seed compare block for block, and the first animals of those chunk columns by
origin, with what each keeps for life, into `<x>_<z>_r<radius>_animals.txt` and their own total.
`/riven animals [radius]` compares the first animals planned near the player with the world. A tour view's `commands` run before its teleport,
so `execute {player} ~ ~ ~ riven digest 48 <x> <z>` there digests the previous view. `--lod-trace`
installs the lab observer so every `play` records DH LOD stages (OBSERVABILITY.md, DH LOD stage
trace).

`tools/runtime.py` imports `cubic_lab` and `cubic_standalone` from Cubature's `tools`, so the
Cubature checkout must be present. `prepare` writes the local runtime identity to
`.local/native-runtime.json`: the pinned Cleanroom release from `dev/native-runtime.json` plus the
Java 26 named in `.local/settings.json` and the hashes of its critical files. A Java 26 other than
the reviewed build is noted, not refused.

## Building and local actions

`tools/actions.py` runs the workflows in `tools/actions.toml`, one at a time, with a log per step
under `.local/actions/<run-id>/` and a `summary.json`. A failed job stops the run unless
`--keep-going`; jobs that need it are skipped.

Each `summary.json` records the Riven and Cubature trees the run tested, hashed as `git add --all`
would commit them (through a scratch copy of the index; the real index is untouched), and whether
either changed while it ran. A pass recorded before a commit therefore names that commit's tree.
`--if-needed` skips the jobs that already passed for the current trees and keeps the jobs a
remaining job needs. The record assumes the tool environments, `.local/settings.json` and the
pinned JARs are unchanged between runs.

`python tools/push_gate.py install` writes a pre-push hook into Riven and Cubature; it
stays local, since git never commits `.git/hooks`. The hook refuses a push whose tip has not
passed every `ci` job: a Riven commit on its own tree together with the tree of the Cubature
commit its `dev/cc.lock.json` pins, a Cubature commit on its tree with one Riven tree. Running
`ci --if-needed` with the pushed commits checked out and nothing uncommitted supplies the missing
jobs; `git push --no-verify` skips the check.

| Command | Runs |
| --- | --- |
| `python tools/setup.py` (`riven.ps1 setup`) | One-time preparation of a clone: Java paths, Cubature checkout, pinned JARs, Python environments; `--check` only reports |
| `riven.ps1 quick` | Before a commit: the `ci` jobs the changes since HEAD (Riven's and Cubature's) can affect, by the `[quick]` rules in `tools/actions.toml`; a path no rule matches takes every `ci` job |
| `riven.ps1 check` | `quality`, `regional-store`, `atlas-golden`, `definitions` and `sample-history` |
| `riven.ps1 ci` | `quality`, `riven-build`, `cubature-build`, `regional-store`, `atlas-golden`, `definitions`, `sample-history` |
| `python tools/actions.py ci --if-needed` | The `ci` jobs that have not passed for the current trees |
| `riven.ps1 full` | `ci` plus `smoke`, a native timing capture of the run's frozen artifacts |
| `python tools/actions.py --list` / `<job> ...` / `--dry-run` | Inspect or run individual jobs |

The PowerShell launcher forwards workflow options, so `riven.ps1 full --if-needed`
reuses jobs already passed for the same source trees. Runtime commands take named
`-Name`, `-Port` and `-Text` parameters.

The lab's `deliveryTickCosts` records exclusive server-thread phase times for every captured
tick, alongside installed-cube counts and the share installed without block storage. Its
bounded chronological CSV keeps the per-tick phase vectors for replay; dropped rows remain
visible in the summary. The telemetry ledger keeps each phase's mean and p95 and the install
cost per cube, split into construction, population, first light and surface tracking. The
cost divides aggregate work by unique generated-or-loaded cube registrations in the capture;
it does not follow an individual cube across ticks. Construction excludes work on I/O workers.
The `OTHER` phase closes the full server-tick envelope, so phases can be added without counting
nested lighting or encoding twice.

- `tools/build_riven.py [--diagnostics] [--freeze LABEL]` builds Riven for Cleanroom and
  writes `.local/builds/riven-latest.json`.
- `../Cubature/tools/build_cubature.py [--workspace RIVEN] [--skip-tests] [--no-lab] [--freeze LABEL]`
  builds Cubature with its Java 26 tests, then the lab observer against that exact artifact, and
  writes `.local/builds/cubature-latest.json` with test counts.
- `--freeze LABEL` copies both projects' artifacts into `.local/frozen/LABEL/` with a shared
  `manifest.json`; action runs freeze under their run id.
- `tools/atlas_golden.py check|update [--verify]` compares four fixed atlas windows
  (`tools/atlas-golden.json`) with their stored digests. A content-changing commit updates the
  goldens in the same commit, after reviewing `terrain_atlas.py compare`.
- `tools/run_check.py <Check> [argument ...]` compiles the working tree's worldgen with one
  `tools/checks` entry point, as the atlas compiles its sampler, and runs it. The `definitions` job
  runs `DefinitionsCheck`: `worldgen/Definitions` lists exactly the packaged `riven-*.json` files,
  and `TerrainMap.VERSION` equals the newest CHANGELOG terrain row and the version README names.
  The `sample-history`
  job runs `SampleHistoryCheck` on six seeds: every stage result in `TerrainMap.Sample` is
  registered with one phase, and a sample reused across 640 shuffled land and sea visits equals a
  fresh one in every field except the scratch and count-bounded arrays the check names.
- The `regional-store` job runs `RegionalStoreCheck` on disposable fixture directories. It
  checks complete definition identities, corrupt and oversized entries, raw floating bits,
  concurrent publication and mapped-read lifetime without constructing terrain. It also compiles
  the codec and loader-free ownership/cleanup checks into fresh output and runs their fixture-only
  modes. Changes to connected production helpers retain full validation; only the exact diagnostic
  paths this job executes have a quality-and-filesystem quick rule.
- `run_check.py DrainageCodecCheck PARENT fixtures` checks the flat graph's primitive bits,
  nullable fields, indexes and malformed input without constructing terrain. Its default mode
  also runs six seeds through real file writes, memo drops and complete-field reload comparison;
  `verify ROOT seed=N scale=N entries=N random=N` recomputes a random stored sample without
  repairing files. `RegionalOwnershipCheck` and `RegionalCleanupCheck` cover loader-free world
  identity, construction tokens, terminal epochs and cleanup exception handling. They are in the
  root Java package and compile with their helpers; `run_check.py` targets worldgen entry points.
- `run_check.py RegionalCacheProbe out=DIR scale=1|4 players=N [spread=dispersed|clustered]`
  measures what Riven's memos must hold under N players' demand: Distant Horizons' 4,096-block
  reach every 32 blocks plus the native view every 4, nearest first, on 12 threads, inside the
  8 km x scale world, with in-build work on a pool shaped as Cubature's build pool
  (`buildThreads=`, by default a quarter of the processors, 1 to 4). `prepareReach=` first
  prepares every hydrology owner cell within that reach of the players on the build pool, as
  Cubature's regional preparation would before they arrive, and reports its cost and declines. Its passes are cold, repeat (the same demand again, so any build is a
  capacity miss) and moved (960 blocks on). For every memo it reports keys built, rebuilds,
  flights that found their key already held (raced, which build nothing),
  single-flight waits by thread kind (worker, common pool or build pool), keys that cannot all be held at
  once, slots replaced per pass and retained heap, plus each continent's drainage scan; memos
  without a `SharedBuild` report slot replacements only. It swaps each `SharedBuild`'s flight map
  by reflection (Java 26 warns once) and changes no generator code. It drives `TerrainMap.sample`
  only: trees, ores and native column builds are outside it, and retained heap is coarse below
  about 1 MB.
  Optional `store=ROOT drainageSlots=2` runs demand with a test-only small drainage memo and
  separately clears it to replay every unique cold key from disk. Its file hits and zero store
  misses establish disk reuse even while hydrology plans remain warm. In store mode,
  build/rebuild counters count memo flights, including file decodes; store misses count drainage
  build attempts, and `buildSeconds` sums flight wall time, including waits. It retains aggregate
  counters and cold keys while omitting per-flight continent detail. Store demand cannot be
  combined with attribution or ahead preparation. `RegionalCacheProbeReportingCheck` exercises
  the bounded summary and the unchanged default report schema.
- `run_check.py RegionalCacheProbe attribute=spawn|arrival out=DIR seed=8675309 scale=1
  buildThreads=1|4` attributes cold startup and arrival cost without launching the game.
  Run each combination in a fresh JVM so the land-share memo is cold. Spawn mode reconstructs
  and times each world-map constructor and climate draw, then the exact dry-land spawn search.
  One thread means caller-only construction; larger values supply that many build-pool workers,
  with the caller also helping. Source fingerprints reject a stale reconstruction before timing;
  production-constructor metadata, spawn and native block/ore/unstable parity are checked afterward.
  `oracle=PATH` reuses an independent spawn report for another thread count, checking the same
  generator and definition fingerprint, seed, scale, kept-draw metadata, spawn and native-column
  digest. Arrival mode uses the actual game constructor with the requested pool from construction,
  then times the native chunk column at `x=8102 z=4552`, recording
  its digest directly. Each report ranks memo build time, count and worst build, records waits
  by thread kind, and lists built keys. Nested build and wait times overlap and must not be added
  as elapsed cost. The ordinary demand probe constructs its maps on the tools' all-processors-but-one
  pool before installing `buildThreads`; its `mapSeconds` is not a startup measurement at the
  requested pool size. Its demand square is `8000 * scale` blocks across; the generation layout
  is tuned over `8192 * scale`; the game's legal X and Z range is `[-30000000,30000000)`.
- `run_check.py DrainageFootprint <seed> <scale>` builds the drainage network of every continent
  reaching the world square and reports its heap: channel arrays against the bin index.
- `StoredAtlasParityCheck` compares the actual atlas layer digest for a fixed 4x4 window with a
  fresh cache and with fresh maps reading its populated cache. It records constructor memo state,
  cold writes and full-pass file hits without rebuilding. The same owned pool and a possible
  process-local land-share decision reuse make it a content/persistence check, not a startup
  timing measurement. It compiles with `TerrainAtlas`; the reporting check similarly compiles
  with `RegionalCacheProbe`, rather than using the single-source `run_check.py` path. Bound the
  atlas check's process lifetime because the sampler's executor can survive a failed task.

Riven and Cubature build for Cleanroom only, as Java 25 bytecode run on Java 25 or 26; the Java 8
Forge fallback was retired on 2026-10-30 (docs/DECISIONS.md), so code may use any Java 25 API.

`tools/setup.py` creates the pinned tool environments; static checks then run with:

```powershell
.local/quality-venv/Scripts/python.exe tools/code_quality.py check
```

`check` verifies Google Java Format (AOSP) and Ruff formatting, runs Ruff and gradual Mypy, and
compiles production and `tools/checks` Java with Java 26 with lint warnings as errors. It runs no tests or game. `.local/settings.json` supplies the `java25` and `java26`
paths. The `quality` job adds `tools/content/build_content.py --check` and both projects' tool
tests, `unittest discover` over `tools` and Cubature's `tools` in the observability
environment.

## Known runtime warnings

The recorded native timing predates connected drainage storage and describes the earlier Riven
build with the same Cubature pin. That capture passed stationary timing and a 60 s, 16 blocks/s
spectator flight in BASELINE with four DH workers per JVM on Java 26.0.2.1, using 8 GiB/ZGC.
Independent tick totals and phase counters agreed within a 1% weighted gap. Destination teleports remained held during preparation;
arrival was first observed 70-77 s after target-view capture start and full client residency by
93 s. Captured join/flight ticks reached 145 ms, startup reached 546 ms, and an explicit post-flight
save/purge/flush tick reached 1.861 s, including 1.441 s of blocking flush. Existing refmap warnings
and controlled-shutdown connection resets remain; client rendering and held maps are unqualified.
Unimined prints deprecation notices.
