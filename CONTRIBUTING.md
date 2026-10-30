# Contributing to Riven

Pull requests are welcome. The organization's
[contributing guide](https://github.com/orthrus-research/.github/blob/main/CONTRIBUTING.md),
[code of conduct](https://github.com/orthrus-research/.github/blob/main/CODE_OF_CONDUCT.md) and
[AI usage terms](https://github.com/orthrus-research/.github/blob/main/AI.md) apply. This page adds
what is specific to Riven. For a change to generated terrain, open an issue first or ask on
[Discord](https://discord.gg/qCVqhXtSzR): such changes need fresh worlds and new golden digests.

## Rules that keep worlds reproducible

- **Determinism.** Generated content is a pure function of the seed and the world's recorded
  definitions (packaged defaults plus mapping layers, resolved when a world is created into
  `data/riven/definitions.json`). Request order, threads, caches and eviction must not change
  results.
- **Definitions.** Tunable values belong in `src/main/resources/riven-*.json`. Each definition is
  fingerprinted, so editing one, even its notes, is a content change that needs fresh worlds.
- **Bounded, pure planning.** Region tiles, per-continent drainage and column caches are pure
  functions of seed, definitions and coordinates, held in bounded caches whose eviction cannot
  change results. Riven has no background jobs, preparation coordinator or order-dependent state;
  Cubature's worker pools change timing, never content.
- **One owner per placement.** Riven decides every block in its worlds; no other mod's world
  generator runs there (`useVanillaChunkWorldGenerators=false`). Native cubes and the Distant
  Horizons preview share one sampler and one set of block rules, so a new fluid, light emitter or
  decoration needs matching preview support before distant parity is claimed.
- **Upstream stays upstream.** Distant Horizons and Cleanroom are used as official, unmodified
  releases. Distant Horizons behavior changes only through Cubature's Mixins or ASM.

## Licensing

Original Riven material is NRR-1.0. Keep the MIT, MPL-2.0 and LGPL notices intact (`NOTICE.md`,
`THIRD-PARTY.md`, `src/main/resources/META-INF/licenses`). Code from FT's Geology, SeedGen or
Supersymmetry is not imported. Adapted material states its source, its license and what changed.

## Checks

| Command | When |
| --- | --- |
| `riven.ps1 quick` | Before each commit: the `ci` jobs its changes can affect |
| `riven.ps1 ci` | Before every push or pull request (the pre-push hook from `python tools/push_gate.py install` refuses a tip without it): formatting, lint and types, Riven and Cubature tool tests, Riven and Cubature builds with tests, golden atlas digests rendered twice for determinism |
| `riven.ps1 full` | Changes to runtime behavior: `ci` plus a native client and server smoke capture |
| `.local/quality-venv/Scripts/python tools/code_quality.py format` | Applies the pinned Java and Python formatting |
| `tools/terrain_atlas.py compare` | Before updating golden digests: shows what changed and where |

A change that alters generated terrain updates `tools/atlas-golden.json` in the same commit, adds
one line to [CHANGELOG.md](CHANGELOG.md), and proves itself with
`tools/terrain_atlas.py render --verify`. Setup for each tool is in [docs/RUNTIME.md](docs/RUNTIME.md).

## Commits and documentation

- One reviewed change per commit. The message says what was run and what it showed.
- Documentation describes the current system: update the owning document rather than adding a
  new one. History is one line in `CHANGELOG.md`; decisions go in `docs/DECISIONS.md`.
- Run output and evidence stay under `.local`, which git ignores. Editor and assistant
  configuration stays local as well (see `.gitignore`).
