# Runtime

## Profile

The launch is the complete mod input list: `[launch.default]` in `project.toml` names its members, and
`wb play` resolves it into a launch file, every member by SHA-256 from the set this worktree builds against (its
`.set`) and Riven itself from the Store, by the tree CI built.

| Component | Version | Role |
| --- | --- | --- |
| Minecraft | 1.12.2 | Target game |
| Cleanroom | 0.7.14-alpha, official and unchanged | Loader; supplies MixinBooter and ConfigAnytime |
| Java | 26.0.2.1, 8 GiB heap, ZGC per JVM | Native runtime and development compiler |
| Cubature | the set's `cubature` line | The Orthrus Research CubicChunks fork: scheduling, lighting, lifetime, storage, delivery |
| Fugue | 0.25.5, the set's third entry `fugue`, placed as `+Fugue-0.25.5.jar` so it sorts first | Modern-Java compatibility layer |
| Scalar Legacy | 1.0.1, the set's third entry `scalar` (a file from CurseForge, kept in the Store) | Scala 2.11 provider; Cleanroom's patch-mod check stops the server without it |
| GTCEu: Reloaded | 3.0.0-reloaded.1-alpha.1, the set's `gtceur` line, with the third entries CodeChickenLib CRE 3.3.10 and ModularUI 3.1.6 | Optional ore provider (prototype): Orthrus Research's GTCEu continuation, mod ID `gregtech`. `riven-ores.json` transcribes its overworld vein catalog, Riven places the bodies, and GregTech supplies the ore block for each mineral and host stone type; its own world generator never runs. Riven compiles against it with the Java 26 compiler, since its classes are Java 26. It requires OrthLib, through whose render interface it declares its emissive layer and submits its glowing geometry; it draws no bloom of its own |
| OrthLib | 0.5.0-dev, the set's `orthlib` line, with its Cubature adapter through the set's hold `orthlib:cubature-adapter` (the artifact qualified against Cubature c3e240d) | Orthrus Research's shared library: random-tick participation through Cubature's listener API, the render interface (version 1.0) through which mods declare emissive layers and glowing geometry for a renderer to draw (without one, OrthLib draws that geometry plainly), and services for Orthrus mods; the adapter is the exact artifact of its native random-tick qualification, the library a later build that registers its Mixin through a CleanMix connector |
| Riven | `build/libs/riven-0.0.1-cleanroom.jar` | Terrain, water, biomes and block content |

A Riven world spans Y-1664 to Y383: the crust down to Y-512, then the realms
(`allocation` in `riven-terrain-height-reference.json`, realms in `riven-realms.json`).
Cubature fixes a new world's height range, including where out-of-world damage starts, from
`defaultMinHeight` and `defaultMaxHeight` in the server's `config/cubicchunks.cfg`, not from
Riven's generation range: set them to -1664 and 384 before creating a world. `launch/configure.py`
writes them from the height reference when Workbench builds an instance; with the old -128 a test player teleported to Y-500 took out-of-world damage and died.

Distances are 12 on all four native axes (server horizontal and vertical, client render and
vertical). Cubature development in its checkout may be ahead of the set; the launch's Cubature is
what instances use.

Cubature keeps `cubicchunks`/`cubicchunkscore` mod IDs, `io.github.opencubicchunks` packages,
registry IDs and save formats as compatibility interfaces. It is published as
`orthrus-research/Cubature`, a GitHub fork of OpenCubicChunks/CubicChunks. Riven's tools use Cubature's
lab tools at an exact commit, never a working tree (`tools/cubature_tools.py`): a driver session exports them from
Cubature's repository at the commit its launch names, and a check finds them laid beside its clone at the commit the
set names. Cubature never lives inside Riven, so nothing that cleans or deletes Riven's ignored files can reach it.

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

## Generation and delivery

Cubature generates cubes and prepares columns on worker threads for Riven,
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

A cube with fewer than 64 changed blocks reaches the client as block changes, which the client
relights itself; more resends the whole cube with the server's light. Cubature applies the blocks
before taking the server's column heights, so a block that rises above its column's top relights
the column as it does on the server. Taking the heights first left the edited columns of a cube the
client held as empty unlit above the edit (sky light 0 over a new slab where the save held 15), a
dark band on smooth-lit faces on both renderers (`tools/tours/sky-light-band-8675309.json`,
`tools/tours/stones-8675309.json`). For such checks, `riven.lightDump=R` (passed through
`JAVA_TOOL_OPTIONS` to `play`) writes the client's sky and block light within R blocks of each tour
view to the client's `riven-light/`, to compare with the saved world. Open: the first capture after
such an edit sometimes shows a ring of stretched distant-terrain walls around the edited columns,
gone by the next view a few seconds later.

Riven's cloud base is Y256 (`atmosphere.cloudBaseY` in the height reference) through
`WorldType.getCloudHeight`, which vanilla clouds read, so they sit above ordinary terrain and below
high peaks. `launch/configure.py` gives Riven clients fancy graphics (the lab's benchmark
default is fast), so instances show leaves as players see them. `tools/tours/render-check-8675309.json` builds a grass platform with
a leaf hedge and four animals at Y200, then an animal pocket at Y-700 in solid Beneath rock, seen
from a pocket one full section away and again through a tunnel; build its instance with
`--param spawn_animals=true`,
because a server without it removes summoned animals. Each tour capture logs the F3 counters
(`Riven view stats`: frame rate, sections visible/total, entities rendered/total, loaded
cubes/columns) and the heap after a collection.
Cubes wholly under the ground report Riven's depth biomes (FEATURES.md, Realms and depth biomes). Vanilla
reads a column's rain or snow, and a snow golem's melting, from the biome at Y 0, so Cubature reads
them at the precipitation height and where the golem stands in cubic worlds, and surface weather
is unchanged.

Riven worlds need Riven on client and server alike: chunks report `riven:` biomes, and each
joining client receives the world's definitions packet (fingerprint, biome colors).

## Launching

Workbench plays the game, from this worktree at its `.set` (`riven.ps1 play` is the same):

```powershell
riven.ps1 check                                    # CI builds this tree; the Store keeps its JAR
wb play                                            # resolve the launch, build or reuse its instance, play
wb play --name canonical --param seed=2485331 --param scale=4    # the canonical 32 km world, an instance of its own
wb play --tour tools/tours/<tour>.json             # each view captured into the session folder
wb play --driver pipeline                          # a driver session (Drivers, below)
wb play --from <launch.json> --name <instance>     # replay a launch file's exact bytes
```

`wb play` resolves `[launch.default]` into a launch file once: every mod by SHA-256, each re-hashed from the Store,
so an instance loads one build of each mod, the one the set names, and Riven compiled against the same Cubature and
GTCEuR. A launch is resolved for a committed tree CI has built; `--with <project>=<folder>` puts a stand-in in place
of a member and marks the session as not evidence. The instance starts from Cubature's standalone world, and
`launch/configure.py` makes Riven's changes on a machine whose `[lab] trusted` names Riven: the world's height range,
Riven's level type and layout scale, Cubature's delivery settings, fancy graphics, no splash screen. `--param` sets the
launch's typed params for an instance (`seed`, `scale`, `delivery`, `preview_threads`, `spawn_animals`, `capture`).
Without params the world is seed 8675309 at layout scale 1 (the 8 km tuning world); `scale` travels in
`generator-settings` (0.5-8: 1 = 8 km, 0.5 = 4 km, 4 = 32 km worlds; see FEATURES.md), and the canonical world is
seed 2485331 at scale 4. `capture` gives a 2560x1440 client; `spawn_animals` allows animals (off for terrain
captures).

Workbench keeps the instances in its lab home, each with its receipt (`instance.json`: every file by SHA-256 and the
server's and client's command lines), and a folder per session with `session.json` (the members, the JDK build, the
GPU and its driver, the session's inputs) and the consoles. A session refuses an instance whose files differ from its
receipt; changed definitions need a fresh instance. Lines appended to an open session's `commands.txt` go to the
server's console. A tour's views (`label`, `x`, `y`, `z`, `yaw`, `pitch`, and optional `commands` run before the
teleport) are filed as `tour/NN-<label>.png`; a tour's `viewDistance` raises the session's distances.
A view is captured once the player has held still for `riven.captureTicks` (400) and, where Quadrature draws
distant terrain, once Quadrature has called its picture settled for `riven.settlePasses` world passes in a row (40):
every distant tile that can arrive stored and faded in, near terrain caught up and the cube source settled. The client
logs `Riven view settled` with the ticks it waited past the hold. The first view after joining waits longest, since
every distant tile is new: on the forest tour about 2,400 ticks past the hold, later views 0 to 400. A view still
unsettled `riven.settleLimitTicks` (6000) past the hold is not captured, and the client logs `Riven view unsettled`
with what it waited on and quits, so the session fails at that view.

An instance has no Distant Horizons, which is not carried forward. The launch's JVM arguments reach both sides, with
what each member's own `[launch.member]` adds: Quadrature's gives the client `-Dquadrature.distant=own`, so
Quadrature draws distant terrain itself from Riven's preview. The client draws cubic worlds through Quadrature, which takes over the game's chunk system
there; Cubature's client stops at loading without it, and the launch names it for the client. `/riven` in game
prints climate, terrain, biome and feature diagnostics at the player's position. `/riven digest
[radius [x z]]` hashes the world's blocks around a point by state name, one line per chunk column
whose band (32 blocks under its lowest surface to 48 over its highest, from the generator's
columns) is wholly loaded, into the save's `riven/digests/<x>_<z>_r<radius>.txt` and one total, so
two worlds of one seed compare block for block, and the first animals of those chunk columns by
origin, with what each keeps for life, into `<x>_<z>_r<radius>_animals.txt` and their own total.
`/riven animals [radius]` compares the first animals planned near the player with the world. A tour view's `commands` run before its teleport,
so `execute {player} ~ ~ ~ riven digest 48 <x> <z>` there digests the previous view.

### Drivers

A driver is a program of Riven's that starts its own server and client from the instance's receipt. They are declared
in `[launch.default].drivers` and run as `wb play --driver <name>`:

| Driver | Runs |
| --- | --- |
| `pipeline` | `tools/measure_pipeline.py`: the terrain-pipeline workload on a fresh copy of the instance |
| `capture` | `tools/riven_observe.py capture`: the same workload with a timing, diagnostic or lifecycle mode's instrumentation |
| `pregenerate` | `tools/pregenerate_map.py run`: a bounded pregeneration on the instance itself, which each session resumes |
| `pregenerate-verify` | `tools/pregenerate_map.py verify`: the pregeneration's saved coverage, counted with every server stopped |

Workbench builds the instance, takes the GPU lease and quiet for the whole session and runs the driver with
`WB_INSTANCE` (the receipt), `WB_SESSION` (the session folder) and `WB_LAUNCH` (the launch file). The driver runs
Cubature's lab tools exported at the commit its launch names, fills the receipt's `{instance}`, `{cache}`,
`{jdk:N}` and `{port}` in, and keeps the lab's record of the instance (`lab.json`) and every result (`runs/`) in the
session folder. A driver's further options go in `LAB_DRIVER_ARGS`, for example `--diagnostic` or `--map world-8k`.
Drivers run only on a machine whose `[lab] trusted` names Riven. [OBSERVABILITY.md](OBSERVABILITY.md) covers the
measurements.

### On Linux

Workbench's sessions run on Linux as on Windows: the client takes the Linux natives of its libraries and Cleanroom's
`unix_args.txt`. (Checked 2026-11-20 on Ubuntu 26.04 with Temurin 26.0.2.1+1 for Linux, with the launcher before
Workbench's sessions: a fresh instance prepared and a two-view tour played and captured.) A machine that only plays
needs Workbench with its lab, a launch file and the Store files it names, and a display with OpenGL 4.6 in the
compatibility profile on the GPU. A machine with no monitor, such as a rented GPU in a container, gets one from a
virtual display (`Xvfb`) and VirtualGL's EGL back end (`vglrun -d egl`), which renders on the GPU through the
driver's EGL library.

### The GPU lease

One native game runs at a time per machine. Every `wb play` session holds Workbench's GPU lease from before its
first JVM starts until its last one ends, and a driver's JVMs run inside that turn; nothing of Riven's takes the
lease itself. The lease is an OS lock on byte 0 of Workbench's lease file, which the OS drops when its holder
exits, so a crashed run never keeps it.

Other work that shares the GPU takes a turn through Workbench:

```powershell
wb gpu                                             # the holder, the waiters and stuck lanes
wb gpu run --note "<purpose>" [--limit 2h] [--detach] -- <command> ...
wb gpu stuck
wb gpu leave <lane>
```

Each lane is one line of work with its own file; waiters are served in the order they asked, a restarted waiter
keeps its place, a turn ends at its limit (an hour unless `--limit` says otherwise), `--detach` runs the command as a
process of its own that outlives the shell, and `stuck` names lanes whose writer has gone.

## Building and CI

Riven's checks are the `[checks]` in `project.toml`. Workbench runs them, `wb check` (or `riven.ps1 check`),
from a clean clone with the set's Gradle and JDKs: the build takes Cubature and GTCEuR as the files the set names
(`set.jar` in `build.gradle`), from the Store by their SHA-256, and a push needs the `gate`'s checks passed. Steps run
on Temurin 26, with `{python}` the environment made from
`tools/requirements-quality.txt`, `tools/requirements-atlas.txt` and
`tools/observability/requirements.txt`. A log per step and a `summary.json` stay in Workbench's
run records. Jobs run concurrently once the jobs they need have passed, as long as their declared
core `share`, `memory` and `locks` fit the machine; longer jobs start first (`priority`). After a
failed job no new job starts unless `keep_going=true`; jobs that need it are skipped.

A job with `inputs` is a pure check. Its key hashes its steps, every file its input globs match
and the JDK release it runs with. A pass for the same key stands for the job, so a commit that
leaves worldgen and a check untouched does not run the check again. A failure is never cached. A
job's inputs must list every file its verdict reads.

A Cubature or GTCEuR change reaches Riven when it lands in the set and this worktree moves to that revision
(`wb work sync`); nothing in Riven names their commits. The pre-push hook refuses a push whose tip has not passed the
gate; `git push --no-verify` skips it.

The offline checks compile and run worldgen with Gson, the set's third entry `gson`: `tools/set_store.py`
takes it from the Store by the SHA-256 the set gives it, so a check never depends on what a Gradle cache
holds. Every Gradle run, a
`{gradle}` step or the `./gradlew` that `tools/code_quality.py` starts, takes the set, the Store and the
JDKs it may use from the job (`GRADLE_OPTS`), or by hand from the block `wb setup` writes in the Gradle
home; the toolchain is the set's Java. The atlas and observability tools find a JDK through
`tools/jdks.py`: this checkout's `.local/settings.json` first, then `JAVA_HOME_<major>_X64`, which
Workbench and CI set.

| Command | Runs |
| --- | --- |
| `riven.ps1 play` | `wb play` from this worktree, its options passed on (Launching) |
| `riven.ps1 build` | `./gradlew assemble` here, further tasks passed on (`compileDiagnosticsJava`); CI's build is the `riven-build` check, whose files Workbench keeps with their SHA-256 |
| `riven.ps1 check` (also `quick`, `ci`) | `wb check`: the gate (`quality`, `riven-build`, `regional-store`, `drainage-prune`, `regional-bake`, `atlas-golden`, `definitions`, `sample-history`, `variant-coverage`) from this worktree; pure checks whose inputs passed before do not run again |
| `riven.ps1 profile` | `atlas-golden-profile`: the golden windows resampled and timed; each step's timing lines are in the run's summary |
| `wb run ci.status project=riven` | What HEAD has passed, and what the push gate still needs |
| `wb run ci.run project=riven workflow=<job>` | One job (or several: `jobs=[...]`); `dry_run=true` lists the steps |

A native timing capture needs the GPU host, so it is not part of CI: it is a driver session,
`wb play --driver capture` (Drivers).

The lab's `deliveryTickCosts` records exclusive server-thread phase times for every captured
tick, alongside installed-cube counts and the share installed without block storage. Its
bounded chronological CSV keeps the per-tick phase vectors for replay; dropped rows remain
visible in the summary. The telemetry ledger keeps each phase's mean and p95 and the install
cost per cube, split into construction, population, first light and surface tracking. The
cost divides aggregate work by unique generated-or-loaded cube registrations in the capture;
it does not follow an individual cube across ticks. Construction excludes work on I/O workers.
The `OTHER` phase closes the full server-tick envelope, so phases can be added without counting
nested lighting or encoding twice. Besides cube installation, lighting, sending, encoding, DH feed,
unload/save, arrivals and regional preparation, phases separate the cubic world's block ticks
(`BLOCK_TICKS`: column weather and light checks, random ticks of tickable cubes; `TICK_LISTENERS` is
the time random-tick listeners such as OrthLib's take inside it), the player cube
map's own bookkeeping (`PLAYER_MAP`), queued packet handlers with the network tick (`NETWORK`) and
Forge's server and world tick events (`TICK_EVENTS`, where the lab observer runs). Only the lab
charges `NETWORK` and `TICK_EVENTS`, by wrapping those vanilla call sites; the mod adds no mixin
there, because other mods may overwrite or move them. `OTHER` is about 2% of creative-flight tick
time. `riven_observe.py capture --jfr`
records both JVMs with JFR's profile settings for attributing a phase further.

- `tools/atlas_golden.py check|update [--verify]` compares four fixed atlas windows
  (`tools/atlas-golden.json`) with their stored digests. A content-changing commit updates the
  goldens in the same commit, after reviewing `terrain_atlas.py compare`.
- Gradle's `runDiagnostics -PrivenDiagnosticMain=<class> --args="<arguments>"` runs a selected
  `tools/checks` entry point on Java 26. `compileDiagnosticsJava` compiles the helpers as Java 26
  bytecode. Every run has the game bootstrap's class path: Cleanroom's development runtime with the
  pinned Cubature and GTCEuR deobfuscated into development names by CleanroomGradle. Each run
  records the plugin, the loader, Cubature's input and mapped artifact and the ordered classpath in
  `build/diagnostics/cubature-mapping.json`.
- `cc.orthrus.riven.CubePrimerBootstrapCheck` takes `<fixture parent> [registries|generate]`.
  Its `registries` mode validates real block and biome registrations and primer IDs. `generate`
  also checks the actual generator's sky, surface and realm cubes, scanning every block and biome
  cell and recording an oracle receipt. Use a fresh fixture parent. Ordinary CI compiles these bootstrap helpers; their exact paths have a
  quality-and-build quick rule. Invoke their registry or generation workload explicitly.
- `cc.orthrus.riven.TreeCensus` takes `<new output dir> [seed [layout [windows per key [window
  blocks]]]]` (defaults 2485331, 4, 5, 256) through `runDiagnostics` with the mapped game option.
  It samples biomes and variants on a 1024-block grid, takes windows per biome or variant by hash,
  and records every production tree site there with its drawn shape and its column's effective climate, plus
  each key's dry-land area. `tools/tree_census.py <dir>` writes `report.md`: stems per hectare by layer, height spread,
  species shares, crown spread and leaf volume against height, and the Clark-Evans ratio and
  quadrat dispersion of the upper layers. The canonical census takes about 70 s.
- `cc.orthrus.riven.TreeGallery` takes `<out.png> [trees per species]` through `runDiagnostics`
  with the mapped game option and draws every species' side elevation across its own height band
  (logs brown, leaves green, depth shaded), from the production shapes.
- `cc.orthrus.riven.CubeClassificationCheck` takes `<new receipt.json> [seed ...]` through
  `runDiagnostics` with the mapped game option. The receipt path must be new. Without seed
  arguments it checks all six intended seeds; optional seed arguments select a partition. Each
  unique `EMPTY` cube is generated into a fresh checked primer. Receipts separate
  `selectedSeedsPassed` from `fullIntendedProofPassed`; the full flag requires all six intended
  seeds to finish successfully. CI compiles this check; invoke its workload explicitly.
- `cc.orthrus.riven.PreviewParityCheck` takes `<out> <seed> <scale> <threads> <sources>
  <name:x:z:high-low[:tiles]> ...` through `runDiagnostics` with the mapped game option. It
  samples the terrain preview over windows of 64 x 64-column tiles at each level (cells of
  2^level blocks, sampled at their integer centres) and digests every column's biome and runs,
  so preview content can be compared exactly between two trees. The source `generator` is the
  server's cube generator on a fixture world; `join` is what a client builds: the generator's
  offer written as Cubature's join message, and Riven's registered factory given the bytes of that
  message alone. With both, their identities and every level's digest must be equal, the identity
  must change with the seed, the scale and a recorded role, and the factory must decline a message
  whose seed is not its identity's. CI compiles this check; invoke its workload explicitly.
- `CUBATURE_LAB_JVM=g1-ship8-nmt` prepares a capture on the shipping G1 profile with native memory
  tracking. After every scenario it records each process's NMT summary, the JVM's tagged
  address-space map (`jcmd System.map`) and, with Sysinternals VMMap on PATH, VMMap's CSV, and joins
  them into `<scenario>-<side>-memory-account.json`: Java heap, other JVM reservations, JVM malloc,
  native malloc beyond it, native VirtualAlloc the JVM did not reserve, thread stacks and image pages,
  closing on VMMap's private total, with write-combined (GPU-visible) pages and the largest modules.
  `python ../Cubature/tools/lab_memory.py <evidence directory>` prints the accounts.
- `tools/run_check.py <Check> [argument ...]` compiles the working tree's worldgen with one
  `tools/checks` entry point, as the atlas compiles its sampler, and runs it. The `definitions` job
  runs `DefinitionsCheck`: `worldgen/Definitions` lists exactly the packaged `riven-*.json` files,
  and `TerrainMap.VERSION` equals the newest CHANGELOG terrain row and the version README names.
  The `sample-history`
  job runs `SampleHistoryCheck` on six seeds: every stage result in `TerrainMap.Sample` is
  registered with one phase, and a sample reused across 640 shuffled land and sea visits equals a
  fresh one in every field except the scratch and count-bounded arrays the check names. It also
  runs `CoastStretchCheck`: a shore walk that runs into a cusp ends its stretch there, where it
  once handed the sedimentary kernel two coinciding points (seed 8675309, column 11840, 42816).
- The `drainage-prune` job runs `DrainagePruneCheck` at layout scales 1 and 4: drainage answers
  over a grid and scattered columns, with continent pruning on and off, must be bit-identical.
- The `regional-bake` job runs `RegionalBakeCheck` at layout scales 1 and 4: preparing a region
  with a store writes its networks to disk, builds none through the memory memo, writes nothing
  when repeated, and a baked network reads back with the same bytes as a fresh build.
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

With the pinned tool environment made from `tools/requirements-quality.txt` (as Workbench makes `{python}`), static
checks run with:

```powershell
python -m venv .local/quality-venv
.local/quality-venv/Scripts/python.exe -m pip install -r tools/requirements-quality.txt
.local/quality-venv/Scripts/python.exe tools/code_quality.py check
```

`check` verifies Google Java Format (AOSP) and Ruff formatting, runs Ruff and gradual Mypy, and
compiles production and `tools/checks` Java with Java 26 with lint warnings as errors. It runs no tests or game. The formatter runs on
`JAVA_HOME`'s Java (a job's is the set's JDK 26); Gradle takes its JDKs from the job, or from the Gradle home by hand. The `quality` job adds `tools/content/build_content.py --check` and both projects' tool
tests, `unittest discover` over `tools` and Cubature's `tools` in the observability
environment.

## Known runtime warnings

The recorded native timing comes from captures of this Riven build with this Cubature pin (seed
8675309, BASELINE, four DH workers per JVM, Java 26.0.2.1, 8 GiB/ZGC), each beside a control run
in the same session. Against the Riven main and pin before the regional work, a lifecycle capture
went from 86.9 s to 38.9 s from server launch to ready (207 to 86 CPU s), and from 104.9 s to
22.9 s to deliver the full view. With baking, against the same build without it, a flight
lifecycle capture (full view, a 60 s spectator flight at 16 blocks/s, settle, save and purge)
delivered the full view's first cubes 0.55 s after capture start instead of 8.9 s and the whole
view in 10.8 s instead of 20.9 s; worst ticks: full view 159 ms, flight 78 ms (113 without), settle
39 ms. Tick p95 is about 42 ms in the full view and 34 ms in flight, where the same delivery runs in
fewer, busier ticks. With this pin's storage changes (a flush writes each region's backlog in one
batch, compression runs on reused deflaters and bounded workers, and empty cubes Riven reports
regenerable are not saved), the explicit post-flight save/purge/flush tick fell from 2.72 s to
0.61 s and its blocking flush from 2.22 to 0.33 s; the run saved 52,610 cubes instead of 75,122
and 31.8 MB of cube regions instead of 43.8 MB. Worst flight ticks varied from 110 to 176 ms across
these captures. Newly prepared lab instances use the shipping G1 profile (8 GiB, 4 MB regions, a
50 ms pause target, concurrent explicit collections). Against G1's defaults on the same heap it cut
server young pauses from 43/63/76 ms (median/p95/max) to 29/44/48 ms and removed the 180-220 ms full
collections; a 12 GiB heap with the same flags measured no better. The figures above flew a spectator,
which exempts the server from random ticks and spawning; its flight tick p95 stayed near 35 ms under
every collector setting tried. The flight workload (v3) now flies a creative player. A quarter of its
tick time was the provider's per-cube tick, which walked every tickable cube watcher to drain a
tile-entity queue nothing fills; with this pin it is gone, and two captures measured flight tick mean
12.1 and 11.6 ms (from 14.1 ms), p95 35.7 and 35.0 ms (from 37.1 ms) and settle p95 8.1 and 8.2 ms
(from 10.0 ms). The block ticks themselves (`BLOCK_TICKS`) take 15-16% of flight tick time. Install bursts
still set the flight p95: once a second the player crosses a cube boundary, and the first two burst
ticks spend the 20 ms concurrent install budget and then 6-9 ms of light queue. Native memory tracking showed 2.26 GB of the client's memory outside the heap
in vanilla's chunk-mesh builders, which reserve 10 MiB of direct memory each, about ten per
processor; with this pin they start small and grow on demand, and in the same capture the client's
direct allocations fell to 0.41 GB and its private commit from 12.7 to 10.9 GB. The full account after flight
on the client: 8.0 GiB heap, 0.38 GiB other JVM reservations, 0.52 GiB JVM malloc, 0.65 GiB native
malloc beyond the JVM and 0.97 GiB native VirtualAlloc (0.53 GiB of it write-combined, GPU-visible
driver memory); the server carries almost nothing beside the JVM. A stationary capture of the earlier
pin showed saved blocks, storage and completed light unchanged, apart from surface water that
random ticks froze or thawed differently. Existing refmap warnings and controlled-shutdown
connection resets remain; client rendering and held maps are unqualified.
