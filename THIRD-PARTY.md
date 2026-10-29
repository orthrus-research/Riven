# Third-party and contributed source

Riven's own material is NRR-1.0 ([LICENSE](LICENSE)). The components below keep their own
terms; [NOTICE.md](NOTICE.md) holds the full license statements. Every license listed here is
packaged under `src/main/resources/META-INF/licenses/` and ships in the binary and sources
JARs.

On 2026-10-28 the kernels moved from `riven.contrib` to `cc.orthrus.riven.contrib`. In the
copies marked "Unchanged except package" the package declaration is the only difference from
the original delivery.

## Contributed terrain kernels (MIT, Copyright (c) 2026 Riven contributors)

| Kernel | Production source | Riven adapter (NRR) | Packaged license | Archived delivery | Copy |
| --- | --- | --- | --- | --- | --- |
| Continental outlines | `cc/orthrus/riven/contrib/continents/ContinentalKernels.java` | `ContinentalOutlineMap`, `ContinentalMap` | `continents/MIT.txt`, `SOURCES.md` | `continental-outlines-20261019` (rev. 2) | Adapted |
| Ocean floors | `cc/orthrus/riven/contrib/ocean/OceanFloors.java` | `OceanBed` | `ocean-floors/MIT.txt` | `ocean-floors-20261019` | Unchanged except package |
| Lowlands | `cc/orthrus/riven/contrib/lowland/Lowlands.java` | `LowlandTerrain` | `lowland/MIT.txt` | `lowland-terrain-20261019` | Unchanged except package |
| Hills and uplands | `cc/orthrus/riven/contrib/hills/Hills.java` | `HillTerrain` | `hills/MIT.txt` | `hills-uplands-20261019` | Adapted |
| Mountains | `cc/orthrus/riven/contrib/mountains/MountainKernels.java` | `MountainTerrain` | `mountains/MIT.txt` | `mountain-systems-20261019` | Unchanged except package |
| Plateaus and badlands | `cc/orthrus/riven/contrib/plateau/PlateauKernels.java` | `PlateauTerrain` | `plateaus/MIT.txt` | `plateaus-badlands-20261019` | Adapted |
| Drylands | `cc/orthrus/riven/contrib/dryland/Drylands.java` | `DrylandTerrain` | `drylands/MIT.txt` | `dryland-formations-20261019` | Unchanged except package |
| Cold regions | `cc/orthrus/riven/contrib/cold/ColdRegions.java` | `ColdTerrain` | `cold-regions/MIT.txt`, `NOTICE.txt` | `cold-regions-10` | Adapted |
| Volcanic | `cc/orthrus/riven/contrib/volcanic/VolcanicKernels.java` | `VolcanicTerrain` | `volcanic/MIT.txt` | `volcanic-formations-20261019` | Adapted |
| General surface cuts | `cc/orthrus/riven/contrib/carvers/SurfaceCuts.java` | `GeneralSurfaceCuts` | `surface-cuts/MIT.txt` | `general-surface-carvers-20261019` | Adapted |
| Lakes and ponds | `cc/orthrus/riven/contrib/lakes/Lakes.java` | `LakeShape` | `lakes/MIT.txt` | `lakes-ponds-20261019` | Adapted |
| Rivers and streams | `cc/orthrus/riven/contrib/river/Rivers.java` | `RiverShape` | `rivers/MIT.txt` | `rivers-streams-20261019` | Adapted |
| Lagoons and estuaries | `cc/orthrus/riven/contrib/estuaries/EstuaryKernels.java` | `CoastShape` | `estuaries/MIT.txt` | `lagoons-estuaries-15-20261019` (second pass) | Adapted |
| Sedimentary coasts | `cc/orthrus/riven/contrib/coast/SedimentaryCoasts.java` | `SedimentTerrain` | `sedimentary-coasts/MIT.txt` | `sedimentary-coasts-20261019` | Adapted |
| Rocky coasts | `cc/orthrus/riven/contrib/rocky/RockyCoasts.java` | `RockyTerrain` | `rocky-coasts/MIT.txt`, `SOURCES.md` | `rocky-coasts-20261019` | Adapted |

Production sources are under `src/main/java/`. "Adapted" files carry a modification notice in
their header; the adaptations (Java 8 syntax, native column APIs, shared-owner anchors) remain MIT.
`SOURCES.md` and `NOTICE.txt` files retain the SplitMix64 public-domain credit to Sebastiano Vigna
where used.

## Other components

| Component | Path | License |
| --- | --- | --- |
| Erosion filter (after Rune Skovbo Johansen via Luke Mitchell's CPU reference, commit `57e79cc`) | `supersymmetry/riven/worldgen/ErosionFilter.java` | MPL-2.0, `META-INF/licenses/erosion/` |
| Terrain height reference data | `src/main/resources/riven-terrain-height-reference.json` | Independently authored contract data, NRR |
| Gradle wrapper | `gradle/`, `gradlew*` | Upstream terms |
| Cubature | `dev/CubicChunks` (separate repository) | CubicChunks MIT, Forge-derived LGPL-2.1; see its `NOTICE.md` and `MODIFICATIONS.md` |
| Runtime dependencies | `runtime.lock.json` | Cleanroom, Distant Horizons (LGPL), Fugue, Scalar Legacy and Minecraft keep their own terms |
| GregTech CE Unofficial (optional runtime dependency, compile-only) | `dev/pins/gregtech-1.12.2-2.8.10-beta.jar`, called from `supersymmetry/riven/GregTechOres.java`; `riven-ores.json` names its overworld veins' minerals and layer roles (facts, no copied files or code) | LGPL-3.0; not bundled, redistributed or modified |
| Biomes O' Plenty (optional runtime dependency, compile-only) | `dev/pins/BiomesOPlenty-1.12.2-7.0.1.2445-universal.jar`, called from `supersymmetry/riven/BopDecoration.java` | CC BY-NC-ND 4.0; not bundled, redistributed or modified |

When distributing a Riven binary, ship the matching `*-sources.jar`; it carries the MPL-covered
source and every packaged license.
