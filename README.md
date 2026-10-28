<p align="center"><img src=".github/assets/banner.svg" alt="Riven by Orthrus Research" width="100%"></p>

<p align="center">
<a href="#status"><img alt="Status: research preview" src="https://img.shields.io/badge/status-research%20preview-000000?style=flat-square&labelColor=000000"></a>
<a href="LICENSE"><img alt="License: NRR-1.0" src="https://img.shields.io/badge/license-NRR--1.0-000000?style=flat-square&labelColor=000000"></a>
<a href="https://discord.gg/qCVqhXtSzR"><img alt="Discord" src="https://img.shields.io/badge/discord-join-000000?style=flat-square&labelColor=000000"></a>
</p>

Riven is a deterministic terrain generator for Minecraft 1.12.2 cubic worlds. From one seed it
builds continents and oceans, climate, relief, drainage, coasts, geology, caves, biomes and
vegetation, and the same terrain model supplies both the world and its distant view in
[Distant Horizons](https://modrinth.com/mod/distanthorizons). It runs on official
[Cleanroom](https://github.com/CleanroomMC/Cleanroom) with
[Cubature](https://github.com/orthrus-research/Cubature), the Orthrus Research fork of
CubicChunks.

## Status

Research preview: public for study and testing. Every terrain version changes generated content
and needs a fresh world; no version yet promises compatibility with existing worlds. The current
version is terrain-v98 ([CHANGELOG.md](CHANGELOG.md)). There are no releases yet; they will be
published on GitHub Releases. Known structural limits are listed in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#measured-structural-limits).

## What Riven generates

| Layer | Contents |
| --- | --- |
| World | Y-1664 to Y383: the surface and crust down to Y-512, then the Beneath and the Infernal Deep, with gradual boundaries. Layout tuned for 8 km worlds and scalable from 4 to 64 km. |
| Continents and climate | Seeded continents and oceans; temperature and moisture with an upwind moisture correction. |
| Land | Base relief with erosion and regional passes, and fifteen contributed landform families, from continental outlines and ocean floors to mountains, coasts and rivers ([THIRD-PARTY.md](THIRD-PARTY.md)). |
| Water | Drainage networks per continent, from headwater streams to rivers; lakes, wetlands and lagoons; underground lakes and flooded caves. |
| Underground | Geology with 13 host stones and their deposits, and 21 rocks of the deep realms' own; ores from a mineral catalog (GregTech CE Unofficial's overworld veins when it is installed, vanilla ores otherwise); caves through ten depth biomes. |
| Surface life | 61 `riven:` biomes chosen from climate and terrain; trees from 34 species; boulders and ground cover. |
| Distant view | Distant Horizons samples the same model as native cubes. |

Every layer and its definition file: [docs/FEATURES.md](docs/FEATURES.md).

## For players and pack makers

| Component | Version | |
| --- | --- | --- |
| Minecraft | 1.12.2 | |
| Cleanroom | 0.7.9-alpha, official | Required |
| Java | 26 | The tested runtime |
| Cubature | 0.1.4.0, pinned in [dev/cc.lock.json](dev/cc.lock.json) | Required |
| Distant Horizons | 3.3.3-1.12.2, official | Optional: the distant view |
| GregTech CE Unofficial | 1.12.2-2.8.10-beta | Optional: the ore catalog |
| Fugue, Scalar Legacy | 0.25.5, 1.0.1 | In the tested profile |

The complete tested profile, with every file's SHA-256, is [runtime.lock.json](runtime.lock.json).

Before creating a world, set `defaultMinHeight=-1664` and `defaultMaxHeight=384` in the server's
`config/cubicchunks.cfg`, then choose the **Riven** world type (`level-type=riven`). Cubature
fixes a world's height when the world is created. In game, `/riven` reports climate, terrain,
biome and feature diagnostics at your position, and F3 shows the biome.

## For addon and mod developers

Riven decides every block in its worlds: no other mod's world generator runs there
(`useVanillaChunkWorldGenerators=false`). Its biomes are registered as `riven:` biomes that extend
the vanilla class of their kind. Packs bind Riven's materials to their own blocks through mapping
layers in `config/riven/mappings/`, which resolve once when a world is created into
`data/riven/definitions.json`. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Building from source

The tooling targets Windows (PowerShell) and needs JDK 25, Java 26, Python 3.14 and Git.

```powershell
git clone https://github.com/orthrus-research/Riven.git
cd Riven
python tools/setup.py
.\riven.ps1 build
.\riven.ps1 ci
```

`tools/setup.py` records the Java paths in `.local/settings.json` (it looks in the usual install
folders; pass `--java25` and `--java26` to choose), clones Cubature into `dev/CubicChunks`,
downloads the third-party JARs pinned in [runtime.lock.json](runtime.lock.json) and checks each
against its SHA-256, builds the Cubature artifacts that [dev/cc.lock.json](dev/cc.lock.json) pins,
and creates the Python environments for the checks and the atlas. `--check` reports what is
missing without changing anything. A Cubature build that differs from the pinned bytes still
builds Riven; native instances need the pinned artifact.

To see what the generator selects without starting the game:

```powershell
.local/atlas-venv/Scripts/python tools/terrain_atlas.py render --seed 8675309 --verify
```

[docs/RUNTIME.md](docs/RUNTIME.md) covers instances, launching and the local CI;
[CONTRIBUTING.md](CONTRIBUTING.md) lists the checks a change must pass.

## Documentation

| Document | Contents |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Pipeline, determinism contract, planning boundaries, measured limits |
| [docs/FEATURES.md](docs/FEATURES.md) | Every generator layer, its families and definition file |
| [docs/RUNTIME.md](docs/RUNTIME.md) | Runtime profile, pins, launching, building, DH settings |
| [docs/TERRAIN-ATLAS.md](docs/TERRAIN-ATLAS.md) | Offline seed maps, comparisons, sweeps and sections |
| [docs/OBSERVABILITY.md](docs/OBSERVABILITY.md) | Instrumented native captures and comparisons |
| [docs/DECISIONS.md](docs/DECISIONS.md), [docs/PROJECT-COMMITMENTS.md](docs/PROJECT-COMMITMENTS.md) | Standing and adopted decisions |
| [CHANGELOG.md](CHANGELOG.md) | Terrain versions and other changes |
| [THIRD-PARTY.md](THIRD-PARTY.md), [NOTICE.md](NOTICE.md) | Contributed sources and licenses |

## Community

Questions and discussion: [Discord](https://discord.gg/qCVqhXtSzR). Bugs and requests: this
repository's issues. Security and private matters: admin@orthrus.cc. Pull requests are welcome;
see [CONTRIBUTING.md](CONTRIBUTING.md).

Orthrus Research develops with AI assistance (Claude Code and Codex). Every change meets the same
verification gates whatever its origin. See
[AI usage](https://github.com/orthrus-research/.github/blob/main/AI.md).

## License

Original Riven material is offered under [NRR 1.0](LICENSE): a public-domain dedication with an
unconditional fallback grant, permitting use, transformation, monetization and distribution
without permission or attribution. The adapted erosion file remains MPL-2.0 and the contributed
kernels remain MIT; see [NOTICE.md](NOTICE.md) and [THIRD-PARTY.md](THIRD-PARTY.md). Binary and
sources JARs include every license and scope notice.

## Credits

The adapted [erosion filter](src/main/java/cc/orthrus/riven/worldgen/ErosionFilter.java) credits
[Rune Skovbo Johansen](https://blog.runevision.com/2026/03/fast-and-gorgeous-erosion-filter.html)
and [Luke Mitchell's CPU reference](https://github.com/lpmitchell/AdvancedTerrainErosion), and is
provided under MPL-2.0. Base terrain, plate blending and regional passes are independently
implemented. Regional shape research drew on
[libnoise's terrain composition](https://libnoise.sourceforge.net/tutorials/tutorial5.html),
[Red Blob Games' terracing discussion](https://www.redblobgames.com/maps/terrain-from-noise/#terraces)
and [Inigo Quilez's explanation of fBM and detail bands](https://iquilezles.org/articles/fbm/).

Water-layer concepts were checked against the
[USGS study of depressional-wetland groundwater connectivity](https://www.usgs.gov/publications/a-hydrologic-landscapes-perspective-groundwater-connectivity-depressional-wetlands)
and [NOAA's lagoon definition](https://oceanservice.noaa.gov/facts/lagoon.html). Riven's bounded
constructions are independent algorithms; these sources do not establish physical discharge,
salinity, permeability or ecosystem simulation.

The broader direction draws conceptual inspiration from
[FT's Geology](https://github.com/Jelada-Studios/fts-geology) by
[Jelada Studios](https://github.com/Jelada-Studios). Riven contains none of its implementation or
assets. Riven targets 1.12.2; players on newer versions should explore FT's Geology.
