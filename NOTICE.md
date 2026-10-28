# Riven licensing

Original Riven code, tools, configuration and documentation are offered under
[NRR 1.0](LICENSE), except material with a separate file, directory or upstream
license. NRR requires no permission, attribution, retained notices or source
disclosure for the material it covers. Its public-domain dedication and
fallback grant apply only to rights the contributor owns or can grant.

`LicenseRef-NRR-1.0` is this project's custom identifier. NRR is a new license;
it is not claimed to be an SPDX-listed or OSI-approved license, or a guarantee
of identical legal effect in every jurisdiction. NRR does not relicense the
components below. Their conditions continue to apply to those components.

## Adapted erosion implementation

`src/main/java/cc/orthrus/riven/worldgen/ErosionFilter.java` is covered by
MPL-2.0, including Riven's changes to that file. It adapts Rune Skovbo
Johansen's work through Luke Mitchell's CPU reference at commit
`57e79cc331d17e2a45c897bea9c4c022d1fc5551`. Its copyright and provenance
notices remain in the source.

The complete MPL text and original upstream notices are retained in
`src/main/resources/META-INF/licenses/erosion/` and bundled with the binary
and sources JARs. The upstream notice document also identifies MIT material
in that reference project; statements about "this repository" inside that
document describe the upstream project, not a blanket license for Riven.

When distributing a Riven binary, provide its matching `*-sources.jar`
alongside it and tell recipients that it contains the MPL-covered source.
The build produces that archive with the complete adapted file. Changes to
the covered file remain under MPL-2.0. NRR's absence of attribution or source
conditions does not remove MPL's requirements for that file.

## Ocean-floor implementation

`src/main/java/cc/orthrus/riven/contrib/ocean/OceanFloors.java` is a copy, changed only in its package declaration, of
the independently authored MIT contribution in
`contributions/ocean-floors-20261019/src/riven/contrib/ocean/OceanFloors.java`.
Copyright (c) 2026 Riven contributors. Its complete MIT grant and notice are
bundled in `META-INF/licenses/ocean-floors/MIT.txt` in binary and sources JARs.
The Riven adapter `OceanBed.java` and its configuration are original NRR
material; that does not relicense the imported kernels.

## Other separately licensed material

The `contributions/` packages named in this notice are the original deliveries of this material;
[THIRD-PARTY.md](THIRD-PARTY.md) maps each production file to its package and packaged license.

`src/main/java/cc/orthrus/riven/contribution/continents/ContinentalKernels.java` adapts
the MIT package `contributions/continental-outlines-20261019` revision 2.
It preserves all four outline constructions, stable family salts, original
point geometry, admission and reservation precedence. Adaptations replace
Java records/switch expressions with Java-8 value classes/switches and add
optional conservative region bounds, coordinate access and cape-intent
metadata. These extensions remain MIT. Copyright (c) 2026 Riven contributors.
Binary and source artifacts include the complete grant and research notices
at `META-INF/licenses/continents/{MIT.txt,SOURCES.md}`. References in those
notes are conceptual sources; their text, figures and implementations are
not bundled. `ContinentalMap`, `MarineSupply`, the earlier geographic prior
and the native water/reservation adapters remain original NRR material.

`src/main/java/cc/orthrus/riven/contrib/rocky/RockyCoasts.java` adapts the MIT package
`contributions/rocky-coasts-20261019`. It preserves the second-pass roof/detail corrections, geometric
formulas, occurrence controls and family salts. Modifications add package
placement, primitive sampling, explicit height targets, separate proposal-bound
native column certificates, canonical shoreline blending and exact integer
volume boundaries. Original continuous APIs remain; native certificates do
not qualify them. Copyright (c) 2026 Riven contributors. Native extensions
remain MIT. Binary and source artifacts include the full grant and research/
utility notices in `META-INF/licenses/rocky-coasts/{MIT.txt,SOURCES.md}`.
The latter retains Sebastiano Vigna's SplitMix64 public-domain dedication,
permission and disclaimer. `RockyTerrain`, the shared resolver changes and
`riven-rocky-coasts.json` remain original NRR material. Retained comparative
figures are covered by the contribution's MIT grant; third-party reference
illustrations and text are not included.

`src/main/java/cc/orthrus/riven/contrib/coast/SedimentaryCoasts.java` adapts the MIT
package `contributions/sedimentary-coasts-20261019`, retaining the reviewed
barrier-centerline, convex-beach-vertex and spit-join corrections. Original
continuous APIs, geometry and stable salts remain. Native additions provide
separate geometry proposals, proposal-bound integer-column facts/plans,
primitive sampling and explicit tentative-footprint inspection. Native
certificates do not qualify the original continuous API. Copyright (c) 2026
Riven contributors. The complete grant is packaged in
`META-INF/licenses/sedimentary-coasts/MIT.txt` in binary and source artifacts.
Native extensions remain MIT. `SedimentTerrain`, the common biome/material
integration and `riven-sedimentary-coasts.json` remain original NRR material.

`src/main/java/cc/orthrus/riven/contrib/estuaries/EstuaryKernels.java` adapts the MIT
package `contributions/lagoons-estuaries-15-20261019`, including its reviewed
second-pass corrections. Original continuous APIs remain. Native additions
provide separate geometry proposals, proposal-bound column facts/plans,
scaled footprint envelopes, actual per-column marine ownership and outer
foundation feathers. A shared opportunity gate retains the authored
thresholds. Geometry formulas and stable salts remain. Copyright (c) 2026
Riven contributors. Binary and source artifacts include the complete grant
at `META-INF/licenses/estuaries/MIT.txt`. Native extensions remain MIT;
the `CoastShape` adapter and `riven-estuaries.json` remain original NRR.

`src/main/java/cc/orthrus/riven/contrib/river/Rivers.java` adapts the independently
authored MIT package `contributions/rivers-streams-20261019`. Its original
continuous API remains, alongside separate proposal-bound native column
admission/plan types, physical reference-width scaling, bank/bar foundation
weights, dry-bank containment guards and shared finite-segment clearance
helpers. The channel geometry, salts, grade, fold, junction-head and
voxel-width checks are retained.
Copyright (c) 2026 Riven contributors. Binary and source artifacts include
the full grant at `META-INF/licenses/rivers/MIT.txt`. The `RiverShape`
adapter and `riven-rivers.json` are original NRR material; the kernel and
its native API extensions remain MIT.

`src/main/java/cc/orthrus/riven/contrib/carvers/SurfaceCuts.java` adapts the independently
authored MIT package `contributions/general-surface-carvers-20261019`. The
production copy adds separate shared-anchor native-column proposal/facts/plan
types; original planning contracts, dry-cut equations and plan-owned guards
remain. Copyright (c) 2026 Riven contributors. The full MIT grant is bundled at
`META-INF/licenses/surface-cuts/MIT.txt` in binary and source artifacts. The
standard public-domain SplitMix64 helper retains its Vigna credit in the source.
`GeneralSurfaceCuts` and its original integration material use Riven's NRR;
imported kernels and their tuning provenance retain their applicable MIT grant.

`src/main/java/cc/orthrus/riven/contrib/volcanic/VolcanicKernels.java` adapts the independently
authored MIT package `contributions/volcanic-formations-20261019`. The production
copy adds a separate shared-anchor native-column proposal/certificate/plan API.
The original continuous-certificate contract, planner and shape arithmetic
remain. Copyright (c) 2026 Riven contributors. Binary and source artifacts
retain the complete MIT grant at `META-INF/licenses/volcanic/MIT.txt`.
`VolcanicTerrain` and its definition are original Riven NRR integration material;
the adapted kernel retains MIT.

`src/main/java/cc/orthrus/riven/contrib/cold/ColdRegions.java` adapts the independently
authored MIT package `contributions/cold-regions-10`. The production copy adds
explicit shared-owner anchors and a configured support-bound query, and declares
the host's integer-column certificate domain. Authored shape arithmetic and
the standalone grid remain. Copyright (c) 2026 Riven contributors. The complete
MIT grant and the contribution's SplitMix64 provenance notice are packaged at
`META-INF/licenses/cold-regions/MIT.txt` and `NOTICE.txt` in binary and source
artifacts. `ColdTerrain` and its configuration are original Riven NRR material;
the adapted kernel retains MIT.

`src/main/java/cc/orthrus/riven/contrib/dryland/Drylands.java` is a copy, changed only in its package declaration, of the
independently authored MIT package `contributions/dryland-formations-20261019`.
Copyright (c) 2026 Riven contributors. Its complete license is packaged at
`META-INF/licenses/drylands/MIT.txt` in binary and source artifacts.
`DrylandTerrain`, its definition and the surface-cover integration are original
Riven NRR material; the imported dryland kernel retains MIT.

`src/main/java/cc/orthrus/riven/contrib/plateau/PlateauKernels.java` adapts the independently
authored MIT package `contributions/plateaus-badlands-20261019`. The production
copy exposes read-only support/identity and early eligibility queries, retaining
the authored geometry and ordered admission. Its integer-column certificate
domain is documented in the source. Copyright (c) 2026 Riven contributors.
The full MIT grant is packaged at `META-INF/licenses/plateaus/MIT.txt` in binary
and source artifacts. `PlateauTerrain` and its configuration are original NRR
integration material; the adapted kernel retains MIT.

`src/main/java/cc/orthrus/riven/contrib/mountains/MountainKernels.java` is a copy,
changed only in its package declaration, of the independently authored MIT contribution in
`contributions/mountain-systems-20261019`. Copyright (c) 2026 Riven contributors.
Its complete grant is packaged at `META-INF/licenses/mountains/MIT.txt` in
binary and source artifacts. `MountainTerrain` and its configuration are
original Riven integration material under NRR; the imported kernel remains MIT.

`src/main/java/cc/orthrus/riven/contrib/hills/Hills.java` adapts the independently authored
MIT package `contributions/hills-uplands-20261019`. The production copy adds
preparation at a shared-owner anchor, retaining the original standalone-grid
entry point and authored geometry. Its host certification domain is documented
as integer columns plus the anchor, rather than the standalone continuous fixture.
Copyright (c) 2026 Riven contributors. The complete MIT license is packaged at
`META-INF/licenses/hills/MIT.txt`. `HillTerrain` and `riven-hills.json` are
original Riven integration material under NRR; the kernel remains MIT.

`src/main/java/cc/orthrus/riven/contrib/lowland/Lowlands.java` is a copy, changed only in its package declaration, of the
independently authored MIT package `contributions/lowland-terrain-20261019`.
Copyright (c) 2026 Riven contributors. Binary and source artifacts include its
full license at `META-INF/licenses/lowland/MIT.txt`. The `LowlandTerrain`
adapter and `riven-lowlands.json` are original NRR material; the kernel retains MIT.

`src/main/java/cc/orthrus/riven/contrib/lakes/Lakes.java` is adapted from the independently
authored MIT package `contributions/lakes-ponds-20261019`. Records and switch
syntax were converted for Java 8; geometry arithmetic is retained. Additional
aligned entry points let an earlier river supply the basin/outlet orientation,
using the same exact trigonometric axis for its support and plan. The original
seed-oriented entry points remain. The sample also exposes its existing
shoreline field for the integration's dry-bank blend. Copyright
(c) 2026 Riven contributors. Binary and source artifacts include its full grant
at `META-INF/licenses/lakes/MIT.txt`. The `LakeShape` integration is NRR material;
the adapted kernel remains MIT.

- Each `contributions/` package retains its own license and provenance
  notices. Their existing MIT or other grants remain in force. Incorporating
  material from these packages requires preserving its applicable notices;
  the NRR default alone does not remove those obligations.
- Gradle wrapper scripts, wrapper binaries and other third-party build tools
  retain their upstream licenses and notices.
- Dependencies, downloaded JARs and material derived from third-party source
  retain their applicable licenses. Their use by Riven does not place them
  under NRR.
- Cubature is a separate fork with retained CubicChunks MIT and Forge-derived
  LGPL-2.1 material. Its notices, sources and distribution obligations are
  separate from Riven's NRR grant.
- Distant Horizons is an official upstream dependency with its own LGPL and
  component licenses. Cubature's runtime patches do not erase those terms.
  Cleanroom, Fugue, Scalar and Minecraft also retain their own terms.

Riven's conceptual references and voluntary credits remain in the README.
No FT's Geology or SeedGen code, translated implementation or assets are
authorized for import by NRR. New functionality must be independently
authored from Riven's requirements or use a deliberately reviewed license.

## Contributions and packaging

Contribute original Riven material under NRR 1.0 only when you can make that
grant. Identify any separately licensed contribution and preserve its
provenance and conditions. Modifications to existing covered files retain
their applicable license; do not replace those notices with NRR.

Both binary and sources JARs carry `META-INF/licenses/riven/NRR-1.0.txt`, this
notice as `META-INF/NOTICE-RIVEN.md`, and the existing erosion notices.
These are distribution records for this project, not an added NRR retention
condition. This notice records license scope; it is not blanket clearance
for redistribution of the entire runtime or every historical reference.
