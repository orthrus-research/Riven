# Debugging Riven, Cubature and Distant Horizons

The observability program is an optional developer workflow for the complete
terrain pipeline. Riven owns terrain evaluation and block/biome content;
Cubature owns cube generation, lighting, delivery and integration; official
DH owns distant-data processing and rendering. Diagnostic recording must not
change terrain, suppress failed work, edit upstream JARs or replace accepted
runtime pins.

`tools/riven_observe.py` is the command-line entry point. It uses the existing
owned runtime and native lab instead of maintaining another launcher.
Evidence remains under `.local`, including unsuccessful attempts.

The accepted Cubature artifact predates the configurable cube-count field, so
the observer reports that field as unavailable; nondefault cube-count
experiments need a runtime that exposes it. Doctor rejects a stale observer.

For startup-wide JVM sampling, `tools/measure_pipeline.py --jfr` starts JFR
on both JVM command lines. It can be combined with `--lifecycle-diagnostics`
and `--delivery-trace` without method instrumentation or NBT queue-byte
accounting. These remain instrumented diagnostic runs. JFR files, crash
logs and any recovered repository chunks stay local; a partial profile is
not a successful flight, storage or rendering result.

For an owned terrain inspection map, `tools/pregenerate_map.py` adapts this
same runtime to the lab's `pregenerate` command. It does not use the historical
schema-8 ore census or introduce another terrain generator. Prepare a fresh
instance with frozen `--artifact` and `--lab-artifact` paths, then run a pilot:

```powershell
python tools/pregenerate_map.py prepare --name riven-map-10k --artifact <riven.jar> --lab-artifact <observer.jar>
python tools/pregenerate_map.py run --name riven-map-10k --max-columns 256 --jfr
python tools/pregenerate_map.py verify --name riven-map-10k --allow-partial
python tools/pregenerate_map.py run --name riven-map-10k
python tools/pregenerate_map.py status --name riven-map-10k
python tools/pregenerate_map.py verify --name riven-map-10k
```

The retained square is `[-10000,10000)` in both horizontal block coordinates:
1,562,500 columns and 50,000,000 cubes at `[-128,384)`, the lab's band
(Cubature's lab jobs require the world to contain it; Riven's world
reaches Y-1664). A player-free server requests ordinary native LIGHT
completion, then saves, performs exhaustive native unload inspection and
flushes the storage writer every 128 columns. Work yields between whole
columns after a nominal 1000 ms tick budget; cold column planning may exceed
it. Distances remain 12, with Java 26/8 GiB/ZGC. The operation stops if
post-unload residency exceeds 32,768 cubes or disk free space falls below 30
GiB. It does not make unsafe worker-thread world calls.

Checkpoints follow completed native writer flushes. Atomic batch records
allow recovery if a process stops before publishing its next checkpoint;
changed generation fingerprints or bounds require a fresh world. This is
ordinary Minecraft storage durability, not power-loss qualification. A
successful bounded session can still leave the map incomplete. `verify`
takes the workspace's native measurement lock (one native run at a time),
counts all target region slots and decodes up to 64 complete columns of
saved cube NBT independently. Full payload validation,
terrain correctness, whole-map DH data and client visual acceptance remain
separate. Only after completion and saved coverage checks should the same
owned instance be opened with `tools/runtime.py play --name <name>`.

## Commands

Python 3.11 or later is required for the host tools; native qualification uses
the separately pinned Java 26 runtime. With the Cubature checkout and runtime
pins prepared, create a local plotting environment once:

```powershell
python -m venv .local/observability-venv
.local/observability-venv/Scripts/python.exe -m pip install -r tools/observability/requirements.txt
```

Build the optional lab observer from `dev/CubicChunks/lab` with the existing
`../cleanroom/gradlew.bat test assemble -PcubicArtifact=<frozen-cubature.jar>`.
Use a fixed copy of that observer for an entire comparison. A changed
observer starts a new comparison.

Building the observer does not change the pinned production artifact in
`dev/cc.lock.json`.

```powershell
python -B tools/riven_observe.py doctor --riven <frozen-riven.jar> --observer <frozen-observer.jar>
python -B tools/riven_observe.py capture --name <fresh-name> --riven <frozen-riven.jar> --observer <frozen-observer.jar> --mode diagnostic
.local/observability-venv/Scripts/python.exe -B tools/riven_observe.py inspect <owned-instance> --output <new-local-directory>
.local/observability-venv/Scripts/python.exe -B tools/riven_observe.py trace <owned-instance> --output <another-new-local-directory>
```

`capture --mode timing` disables JFR and additional packet/render stage
instrumentation. The same bounded worker counters remain on both variants.
`--cubature` selects a frozen candidate without changing accepted pins.
`--workload` selects a validated named workload; `--port` must be free.
`--render-ready` additionally requires native renderer demand and the GPU
marker; a timeout is retained as failure. `--storage` adds storage spans and
requires diagnostic mode. Do not build or run other experiments during a
native capture.

`inspect` writes `inspection.json` and chronological tick/frame PNGs.
`trace` also writes `trace.json`, stack-sample PNGs, raw windowed event JSON
and `timeline.json` in Chrome Trace Event format. Use a local trace viewer;
the program does not upload recordings. It keeps runtime-skipped
instrumentation explicit. Missing method spans can mean a skipped target,
no invocation or only sub-threshold calls; sampled stack counts are a
different observation and are never converted into method durations.

Trace export is bounded to 500,000 selected events per process and a
180-second export deadline. The first/last retained event envelope is checked
against each capture window; a trimmed prefix or suffix is reported.
This check and the absence of JFR data-loss events do not prove uninterrupted
interior coverage. Original recordings and exporter logs remain available.

`trace` can inspect failed captures. Partial native reports remain separate
from validated scenarios. If no completed capture window exists, an available
JFR is labeled as the whole retained process lifetime, including startup and
shutdown. If no recording exists, it reports that absence.

```powershell
.local/observability-venv/Scripts/python.exe -B tools/riven_observe.py compare --baseline <baseline-instance> --candidate <candidate-instance> --treatment cubature --output <new-local-directory>
```

Repeat `--baseline` and `--candidate` in the predeclared pair order for
repetitions. Controls are exact, including prepared file bytes, workload,
observer, runtime, graphics settings and source-independent configuration.
Current captures also retain the imported workspace controller Python
sources; their hashes must match across pairs. Older captures without that
record remain inspectable, without acquiring the newer guarantee.
Different ports/config bytes require a fresh matched plan, not an automatic
normalization. `--treatment riven` permits only the selected Riven artifact;
`--treatment none` permits no artifact difference. Old pilot records without
complete input snapshots remain inspectable but cannot pass this comparator.

For cube-batch screening, fresh `capture --mode timing --cube-packet-cubes
<1..1024> --cube-packet-bytes <bytes>` writes both sides' configuration and
records the effective server packet policy. Use the rebuilt observer with that
record. `compare --treatment packet-cubes` permits only the single verified
`maxCubePacketCubes` value to differ. Exact snapshot hashes are checked before
canonicalizing that field; byte caps, artifacts, controller sources and all
other input bytes remain controls. `tools/cube_batch_experiment.py --plan <plan.json>` runs a
sealed plan's cases through `capture` serially, checking its input hashes before each case and
journaling every start and finish beside the plan. The 1024-cube default is unchanged. Short
screening runs remain exploratory and do not promote runtime pins.

`capture --mode lifecycle` uses the spectator lifecycle workload to measure
60 wall seconds of real client flight at 16 blocks/s, moving residency and
natural writes/unloads, followed by final-view settling and explicit
save/purge/flush. Storage spans are enabled without JFR or NBT queue-byte
accounting. Use its rebuilt observer and retained client/server traces.
`compare --diagnostic-screen` explicitly permits matched instrumented screens,
keeps all controls strict and never accepts a production optimization.

`capture --mode lifecycle --delivery-trace` additionally records bounded
coordinate events in per-side `delivery.csv`, native outstanding render sections
at 5 Hz, DH section-selection transitions, and screenshots every five seconds
during movement. JSON records carry operation dictionaries, clock origins and
dropped counts. Packet fingerprints and process-local mesh identities are not
revision IDs or cross-JVM latency measurements. Timing includes this overhead.
`--delivery-experiment baseline|maintenance|save|prefetch` sets Cubature's
`deliveryMode` and records that candidate stage in `cubeExperiments`; these are
cumulative, opt-in experiments, not accepted defaults.
Different stages are intentional treatment changes and do not pass the existing
single-artifact strict comparator automatically.

## DH LOD stage trace

The lab's `LabLodTrace` records each DH section's path through generation, delivery and
rendering, on both JVMs, from start until exit. Build the observer first
(`dev/CubicChunks/tools/build_cubature.py`, or the lab's Gradle build against the pinned Cubature).

- Directed route: `riven_observe.py capture ... --lod-trace` (any workload, such as
  `dev/benchmarks/cube-flight-lifecycle.json`) writes `<instance>/lod-trace`. The lab job's
  start and finish are marked, and the report windows to them.
- Free flight: `python tools/runtime.py prepare --name <fresh> --lod-trace [OBSERVER_JAR]`
  installs the observer on both sides of a fresh instance. Each
  `python tools/runtime.py play --name <fresh>` (tours too) records to
  `<instance>/lod-traces/<start time>` until you close the client.
- Report: `python tools/riven_observe.py lod <trace-dir> --output <new-dir> [--window auto|all|lab]`
  writes `lod-report.json`, `lod-report.txt` and, with the observability venv, `lod-timeline.png`
  (throughput, client quad-tree queue depths, player speed) and `lod-stages.png`.

Server stages: request received, generation queued, generation started, preview executor wait,
preview sampling (Cubature's sampler time per tile), generation finished, payload encoded (with
bytes), response sent after DH's bandwidth pacing. Client stages: network queue wait, request
round trip, retrieval done, data reload queued, build queue wait, mesh build, GPU upload (empty or
with geometry) and render-state changes (enabled, can render, upload complete), plus the quad
tree's enabled, missing, queued and waiting-to-load counts at 4 Hz and the player position at 2 Hz.
Stages are tabulated per detail level. A parent counts as promoted once all four children have
rendered; the report gives that delay from the last child's creation and names the last child's
slower phase (awaiting data and build, or selection after upload). Sections that first uploaded
empty are counted, since those draw nothing until their data arrives.

Each side's times are its own monotonic clock. Client/server stages (send to receive, response to
receive) align through each JVM's wall-clock origin, about a millisecond on one machine. Events
queue without blocking; drops, a reached cap and an unclosed trace (a killed process loses its
last second, as when a tour ends its client) are reported as warnings. Positions are DH's packed
section positions, so stages match by position, not by request identity. The observer's
injections are exact DH 3.3.3 names, all required.

Analysis commands also accept `--no-charts` for a standard-library-only
readout. Nonzero exits indicate invalid inputs, a native failure or analysis
failure; retained evidence is not replaced. Never reuse an output directory.

## Preview survey

The lab's `cubiclab preview-survey` samples the overworld's DH preview over a square window at
each footprint as Cubature's generator does, writing per-footprint rasters (top, ground top, water
top, topmost kind) and each tile's CPU time. `tools/preview_survey.py` runs it on a copy of a
capture's server with a chosen Riven jar, so the source capture is never touched:

```
.local/observability-venv/Scripts/python tools/preview_survey.py run <capture> <riven.jar> <fresh-name>
.local/observability-venv/Scripts/python tools/preview_survey.py compare <survey-a> <survey-b>
```

`run` defaults to the land route's 2,048-block window at (3120, -26032), footprints 64 to 1, on
16 threads, and prints CPU per column per footprint. `compare` byte-compares every raster and
exits 1 with the count and first block coordinates of differing cells. Identical rasters prove a
preview-path change left distant terrain unchanged; the atlas digests do not cover the preview.

## Required capabilities

1. **Doctor:** verify exact runtime/mod inputs, Java 26, observer availability
   and the version-specific trace targets before allocating a native instance.
2. **Capture:** prepare a fresh isolated client/server pair with a frozen
   workload and observer. Retain complete prepared config bytes, artifact
   hashes, controller sources, effective runtime/distances, process ownership
   and failure logs.
   Timing and diagnostic captures are different modes. Diagnostics include
   JFR on both sides, cube packet/queue observations and renderer stage probes.
3. **Inspect:** reconcile chronological tick/frame samples with their reported
   totals, expose unavailable/truncated data, list failures and native
   captures, and produce readable charts from retained evidence.
4. **Trace:** locate Riven generation, Cubature lighting/delivery, DH update,
   data conversion and rendering work on a common time axis. Show thread
   identity, method spans, sampled stacks, allocation estimates, GC pauses
   and overlaps with slow ticks/frames. Preserve original recordings.
5. **Compare:** reject changed controls or invalid runs. Keep each run and
   scenario separate; compare only the declared treatment. Short pilots are
   exploratory. Diagnostic timings never establish optimization acceptance.
6. **Verify the program:** exercise invalid/missing evidence, tampered inputs,
   numerical reconciliation and time-boundary handling; run a fresh Java 26
   native diagnostic and inspect actual captures and stage activity.

`tools/test_observability.py` covers evidence reconciliation, changed-input
rejection, trace boundaries and thread identity.

Open defects seen through this workflow: the renderer-gate capture can time out
with visible boundary sections uncompiled (263 in the first run) although all
15,625 target cubes arrive; distant water renders raised or stepped; and on
Java 26 JFR skips `CubeProviderServer` instrumentation, so Cubature
lighting/generation appear only in sampled stacks. There is no unique
cube-to-present request tracing.

## Measurement boundaries

- A generated cube, client-resident cube, finished native mesh, submitted
  frame, completed GPU marker and physical screen presentation are distinct.
  The existing optional render-ready gate observes native renderer demand
  and a deferred GPU marker. It does not prove complete DH coverage or
  physical presentation.
- Method spans include waiting and nested calls. Do not add inclusive spans
  to claim exclusive time. JFR stack samples are counts, not CPU duration;
  allocation samples estimate pressure, not live heap.
- A thread's MXBean CPU/allocation deltas measure only that thread and its
  recorded interval. An unavailable counter is never zero cost.
- Packet fingerprints are correlation hints, not unique cross-JVM request
  IDs. Time coincidence does not establish causation or transport latency.
- Existing early-loading p99 results are a diagnostic lead. They are not
  explained merely by concurrent GC counters.
- Complete native cube/view and visual checks remain mandatory. Existing
  water/rendering defects must remain visible in the result.

## Acceptance

Use the Cubature lab's current long-window and repeated-pair protocol for
performance acceptance. Freeze the plan and inputs before running; retain
rejected pairs and their replacements. The program reports eligibility and
evidence, and does not automatically promote an artifact.

The accepted runtime continues to come from `runtime.lock.json` and
`dev/cc.lock.json`. Diagnostic observers, custom JFR settings and candidate
artifacts are installed only in their new owned instances.
