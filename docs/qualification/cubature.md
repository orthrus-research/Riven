# Cubature qualification

These notes were the prose of `dev/cc.lock.json`, the Cubature pin Riven kept before it built from
the set. Riven now takes Cubature from the set, and an instance takes it from the launch file
(RUNTIME.md, Launching), so nothing reads them as a pin. They record what was qualified on the last
pinned commit.

## The fork

Cubature is the final fork name: the GitHub repository `orthrus-research/Cubature`
(https://github.com/orthrus-research/Cubature.git), a fork of
[OpenCubicChunks/CubicChunks](https://github.com/OpenCubicChunks/CubicChunks). Its working branch is
`main`, checked out beside Riven at `../Cubature`.

Cubature owns cubic scheduling, lighting, lifetime, storage and DH runtime compatibility and
optimization patches; Riven owns generated content. Compatibility IDs, APIs, packets and world
formats are retained: mod ID `cubicchunks`, core mod ID `cubicchunkscore`, Java packages
`io.github.opencubicchunks`. The upstream logo is not used.

## The last pin

| Field | Value |
| --- | --- |
| Commit | `a3536c322b4c164d5a33f2fa7b44a022b6b49fee` |
| Cleanroom JAR | `Cubature-cleanroom.jar`, SHA-256 `d982ff7eccf0d9ca93d3018cd0e283aeeff7cedc49dfcb0bda5c3c15758dea40` |
| Sources JAR | `Cubature-cleanroom-sources.jar`, SHA-256 `1d5a80e68d91b700b532e9a01123cc79aa946c7ee81f33b8ccbcc5e3522ed6ed` |
| Java sources matched to the sources JAR | 347 |

## Status

Light qualification. Built from this clean Cubature commit as Java 26 bytecode (Temurin
26.0.2.1+1, built in Workbench, where a second build reproduced it byte for byte) with
CleanroomGradle against Cleanroom 0.7.14-alpha, from its own compile-only inputs
(`cleanroom/build-inputs.json`). All 347 Java sources in the sources artifact match the commit, and
Cubature's own CI passed on it in Workbench: its build inputs, the cleanroom build with its
regressions on Java 26, the lab and the tool tests. Its Java sources are those of a970a130: the
move to Java 26 changed only build files.

Since 30bd6d77 it removes its integration with the game's chunk renderer in cubic worlds, which
Quadrature takes over there: a client needs Quadrature as a client mod and without it stops at
loading, unless `-Dcubature.clientWithoutRenderer=true`, under which the game's renderer draws
heights 0 to 255 of a cubic world (observability captures without a client mod set it). The count
of cubes holding entities kept for the game's entity pass went with that pass. With Quadrature, the
forest tour was drawn identically byte for byte on this removal (69e1b2d2) and on 30bd6d77, on
copies of one world. No BASELINE capture was run on this pin; the last full qualification, on
cbcb09b7, passed a native timing capture and a creative flight lifecycle capture.

## Contents

- Bounded native generation, regional preparation, previews, storage and DH delivery.
- Host-owned build pools available before biome-provider construction and preserved after
  adoption; save-level derived-data directory requests.
- Cached cube classification and surface bands, used to install EMPTY cubes without generation and
  to order delivery visible-first; early initial light and surface tracking for empty sky cubes.
- Retained-column preparation and urgent held teleports; regional preparation around spawn while
  no player is present.
- Exclusive server-thread phase accounting, with block ticks and player-map bookkeeping separated
  (the lab alone wraps vanilla call sites for network and tick-event phases), and client native
  capture counters.
- Flushes that write each region's backlog in one batch, storage compression on reused deflaters
  and bounded workers, and regenerable empty cubes left unsaved.
- A public random-tick listener API on the cubic pass; entity chunk-loading tickets that keep their
  entity's cube.
- A client cube source API (versioned snapshots, a held box, a settled signal) with a small client
  build pool and a client terrain-preview pool that share one governor of work permits, cut on
  frame and server-tick p95 and raised on queued work, each logging why; a terrain preview's own
  small build pool, stopped with it.
- Every client pool logging a stop line as its world goes, and counting its threads still alive 1,
  2, 5 and 10 seconds after while any is; a client governor that rests while no world is attached.
- An optional delivery of only the light that changed (off by default).
- The lab's named reduced view profiles below its baseline, fixed when an instance is prepared.
- Mixin configurations registered through a CleanMix connector, with MixinExtras wraps and
  Expressions in place of redirects and constant modifiers.
- No compatibility code for mods or platforms outside the runtime, no support for clients without
  Cubature, a client that stops at loading without Quadrature (which draws cubic worlds), no
  upstream code that nothing calls, of the vanilla generator fixes only those a player can still
  reach (sapling trees and the huge mushroom), and one build (`cleanroom/`).

Compatibility IDs, packets, save formats and the upstream DH binary are preserved.

## Limits

- Respawn, portal and join arrivals still load their column synchronously.
- Client rendering remains unqualified.

## Known warnings

Arrival was last measured on an earlier pin: first observed 70-77 s after target-view capture
start, with startup ticks up to 546 ms. On this pin the post-flight save/purge/flush tick measured
0.44 s. Distant Horizons' own refmap warning and connection resets during controlled client
shutdown remain. Client rendering and held maps remain unqualified.

## Licensing

MIT by default, with separately licensed components: the Forge-derived async code is LGPL-2.1 (see
its retained package and modification notices). Cubature's `NOTICE.md` and `MODIFICATIONS.md` in
`../Cubature` hold the details. Riven's NRR license does not relicense this fork.
