package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.rocky.RockyCoasts.Edit;

import java.util.Arrays;

/**
 * Shared block rules for native cubes and the distant preview, free of Minecraft types. A column
 * resolves either block by block or as bottom-to-top runs; both give the same block at every Y.
 */
public final class ColumnBlocks {
    private ColumnBlocks() {}

    public static TerrainBlock at(
            long y,
            TerrainMap.Sample sample,
            boolean bareStone,
            SurfaceRules rules,
            TerrainHeightReference heights) {
        int surface = sample.blockY(), waterY = sample.waterY();
        ClimateRegion region = sample.climate.region;
        boolean marine = sample.ocean.marine;
        // The crust floor is open rock: the realms continue below it (RealmMap).
        if (y < heights.minimumY || y >= heights.maximumYExclusive) return TerrainBlock.AIR;
        Edit edit = RockyTerrain.edit(sample.rocky, (int) y);
        if (edit == Edit.SOLID) return Materials.stratum(sample, y, surface);
        if (edit == Edit.VOID) return y <= waterY ? TerrainBlock.WATER : TerrainBlock.AIR;
        if (sample.wonders.voids(y)) return y <= waterY ? TerrainBlock.WATER : TerrainBlock.AIR;
        TerrainBlock decor = sample.caves.decor(y);
        if (decor != null) return decor;
        if (sample.caves.voids(y)) return sample.caves.fill(y, sample);
        TerrainBlock lining = sample.caves.lining(y);
        if (lining != null) return lining;
        boolean volume = sample.rocky.hasVolume();
        if (volume) {
            surface = (int) Math.floor(sample.rocky.parentHeight);
            bareStone = rules.bareStoneBeforeRocky(sample);
        }
        if (y >= sample.water.aquiferMin && y <= sample.water.aquiferMax)
            return y <= sample.water.aquiferLevel ? TerrainBlock.WATER : TerrainBlock.AIR;
        if (y > surface) return y <= waterY ? TerrainBlock.WATER : TerrainBlock.AIR;
        if (sample.sediment.weight > 0
                && (y > Math.floor(sample.sediment.parentHeight)
                        || y >= surface - rules.soilDepth + 1)) return TerrainBlock.SAND;
        if (!volume
                && sample.rocky.weight > 0
                && (y > Math.floor(sample.rocky.parentHeight)
                        || y >= surface - rules.soilDepth + 1))
            return Materials.stratum(sample, y, surface);
        if (marine || sample.water.kind == Hydro.Kind.LAGOON) {
            if (y >= surface - rules.soilDepth + 1)
                return surface >= heights.seaLevel + heights.feature("shoal").elevationOffset.min
                        ? TerrainBlock.SAND
                        : TerrainBlock.GRAVEL;
            return Materials.stratum(sample, y, surface);
        }
        if (waterY > surface && y >= surface - rules.soilDepth + 1) {
            return sample.water.bed == Hydro.Bed.SAND
                    ? TerrainBlock.SAND
                    : sample.water.bed == Hydro.Bed.DIRT
                            ? (Materials.clay(sample) ? TerrainBlock.CLAY : TerrainBlock.DIRT)
                            : TerrainBlock.GRAVEL;
        }
        // A channel's dry bar (braided bed or point bar) is sand or gravel near the water's level.
        if (sample.water.bar != null
                && surface <= Math.floor(sample.water.channelHead) + 1
                && y >= surface - rules.soilDepth + 1)
            return sample.water.bar == Hydro.Bed.SAND ? TerrainBlock.SAND : TerrainBlock.GRAVEL;
        boolean green =
                (sample.water.kind == Hydro.Kind.OASIS || sample.water.kind == Hydro.Kind.WETLAND)
                        && sample.water.weight > .5;
        if (green && y >= surface - rules.soilDepth + 1)
            return y == surface ? TerrainBlock.GRASS : TerrainBlock.DIRT;
        if (bareStone) return Materials.stratum(sample, y, surface);
        if (sample.shore.sandy && y >= surface - rules.soilDepth + 1)
            return reflective(sample) ? TerrainBlock.GRAVEL : TerrainBlock.SAND;
        if (y >= surface - rules.soilDepth + 1) {
            SurfaceRules.CoverReason dryland = rules.drylandCover(sample);
            if (dryland == SurfaceRules.CoverReason.DRY_PAVEMENT) return TerrainBlock.GRAVEL;
            if (dryland == SurfaceRules.CoverReason.DRY_SAND) return TerrainBlock.SAND;
        }
        if (y >= surface - rules.soilDepth + 1
                && region != null
                && region.surface == ClimateRegion.Surface.SAND) return TerrainBlock.SAND;
        if (y == surface) {
            if (region != null && region.surface == ClimateRegion.Surface.SNOW)
                return TerrainBlock.SNOW;
            double slope = Math.hypot(sample.broadDx, sample.broadDz);
            return slope > Materials.GRAVEL_SLOPE
                    ? TerrainBlock.GRAVEL
                    : slope > Materials.COARSE_SLOPE
                            ? TerrainBlock.COARSE_DIRT
                            : TerrainBlock.GRASS;
        }
        if (y >= surface - rules.soilDepth + 1) return TerrainBlock.DIRT;
        if (y >= surface - rules.soilDepth + 1 - Materials.SANDSTONE_DEPTH
                && waterY <= surface
                && sandSoil(sample, rules)) return TerrainBlock.SANDSTONE;
        // Below the soil lies the column's geology (or a wonder's own rock, down its walls).
        return Materials.stratum(sample, y, surface);
    }

    /** A steep, reflective coast: its sandy shore is gravel. */
    private static boolean reflective(TerrainMap.Sample sample) {
        return sample.ocean.shoaling < Materials.GRAVEL_BEACH_SHOALING;
    }

    /** Whether the soil this column's rules place is sand (it rests on sandstone). */
    private static boolean sandSoil(TerrainMap.Sample sample, SurfaceRules rules) {
        if (sample.shore.sandy) return !reflective(sample);
        if (rules.drylandCover(sample) == SurfaceRules.CoverReason.DRY_SAND) return true;
        return sample.climate.region != null
                && sample.climate.region.surface == ClimateRegion.Surface.SAND;
    }

    /**
     * Depth below the surface that soil, sandstone and rock strata can reach; below it every column
     * is plain stone, so cube shortcuts and the bare-stone gate stop there.
     */
    public static int materialDepth(SurfaceRules rules) {
        return Math.max(rules.soilDepth + Materials.SANDSTONE_DEPTH, Materials.STRATA_DEPTH);
    }

    /** Bare stone as native cubes and the preview apply it; marine columns never use it. */
    public static boolean bareStone(TerrainMap.Sample sample, SurfaceRules rules) {
        return !sample.ocean.marine && rules.bareStone(sample);
    }

    /**
     * Writes the column as runs: run i covers [bottoms[i], bottoms[i + 1]) with blocks[i], and
     * bottoms[count] is the allocation top. Every Y where {@link #at} can change is a boundary.
     * Returns the run count; the arrays must hold {@link #maximumRuns} entries plus one bottom.
     */
    public static int runs(
            TerrainMap.Sample sample,
            boolean bareStone,
            SurfaceRules rules,
            TerrainHeightReference heights,
            int[] bottoms,
            TerrainBlock[] blocks) {
        int surface = sample.blockY(), waterY = sample.waterY();
        boolean aquifer = sample.water.aquiferMin <= sample.water.aquiferMax,
                sediment = sample.sediment.weight > 0,
                rock = sample.rocky.weight > 0;
        boolean volume = sample.rocky.hasVolume(), hollow = sample.wonders.hasVoid();
        // A wonder's own strata change only at band edges, from its floor's depth up to the
        // surface.
        boolean banded = sample.wonders.palette != null;
        int strataBottom =
                banded ? Materials.strataFloor(sample, surface) - Materials.STRATA_DEPTH + 1 : 0;
        int strata = banded ? (surface - strataBottom + 1) / Materials.THICKNESS + 2 : 0;
        // Geology changes at formation, bed and tint-band surfaces; a rocky volume also reads its
        // earlier surface, so both surfaces' tint bands count.
        strata += GeologyMap.MAXIMUM_BOUNDARIES * (volume ? 3 : 1) + CaveMap.maximumBoundaries();
        int[] boundaries =
                new int
                        [8
                                + strata
                                + (hollow ? 2 : 0)
                                + (aquifer ? 3 : 0)
                                + (sediment ? 1 : 0)
                                + (rock ? 1 : 0)
                                + (volume ? 2 : 0)
                                + sample.rocky.cutCount];
        int n = 0;
        boundaries[n++] = heights.minimumY;
        boundaries[n++] = heights.minimumY + 1;
        boundaries[n++] = surface - rules.soilDepth + 1;
        boundaries[n++] = surface;
        boundaries[n++] = surface + 1;
        boundaries[n++] = waterY == Integer.MIN_VALUE ? surface + 1 : waterY + 1;
        boundaries[n++] = heights.maximumYExclusive;
        boundaries[n++] = surface - rules.soilDepth + 1 - Materials.SANDSTONE_DEPTH;
        if (banded) {
            boundaries[n++] = strataBottom;
            long previous = Materials.band(sample, strataBottom);
            for (int y = strataBottom + 1; y <= surface; y++) {
                long band = Materials.band(sample, y);
                if (band != previous) boundaries[n++] = y;
                previous = band;
            }
        }
        n = sample.geology.boundaries(heights.minimumY, surface, boundaries, n);
        if (volume)
            n =
                    sample.geology.boundaries(
                            heights.minimumY,
                            (int) Math.floor(sample.rocky.parentHeight),
                            boundaries,
                            n);
        // A volume can hold rock above both surfaces: its beds count up to the allocation top.
        if (volume)
            n =
                    sample.geology.boundaries(
                            heights.minimumY, heights.maximumYExclusive - 2, boundaries, n);
        n = sample.caves.boundaries(sample, boundaries, n);
        if (hollow) {
            boundaries[n++] = sample.wonders.voidBottom;
            boundaries[n++] = sample.wonders.voidTop;
        }
        if (aquifer) {
            boundaries[n++] = sample.water.aquiferMin;
            boundaries[n++] = sample.water.aquiferMax + 1;
            boundaries[n++] = sample.water.aquiferLevel + 1;
        }
        if (sediment) boundaries[n++] = (int) Math.floor(sample.sediment.parentHeight) + 1;
        if (rock) boundaries[n++] = (int) Math.floor(sample.rocky.parentHeight) + 1;
        if (volume) {
            int earlier = (int) Math.floor(sample.rocky.parentHeight);
            boundaries[n++] = earlier;
            boundaries[n++] = earlier - rules.soilDepth + 1;
        }
        for (int i = 0; i < sample.rocky.cutCount; i++)
            boundaries[n++] = sample.rocky.boundary(i, surface + 1);
        // The strata count is an upper bound: only the n boundaries written take part.
        Arrays.sort(boundaries, 0, n);
        int count = 0;
        for (int i = 0; i < n - 1; i++) {
            int bottom = Math.max(heights.minimumY, boundaries[i]),
                    top = Math.min(heights.maximumYExclusive, boundaries[i + 1]);
            if (top <= bottom) continue;
            TerrainBlock block = at(bottom, sample, bareStone, rules, heights);
            // Adjacent equal runs merge, so the stored column stays compact.
            if (count > 0 && blocks[count - 1] == block) continue;
            bottoms[count] = bottom;
            blocks[count++] = block;
        }
        bottoms[count] = heights.maximumYExclusive;
        return count;
    }

    /** Upper bound on runs for any column: one per boundary interval. */
    public static int maximumRuns() {
        int strata =
                (Materials.STRATA_DEPTH + WonderTerrain.MAXIMUM_SPAN + 1) / Materials.THICKNESS
                        + 2
                        + 3 * GeologyMap.MAXIMUM_BOUNDARIES
                        + CaveMap.maximumBoundaries();
        return 8 + strata + 2 + 3 + 1 + 1 + 2 + RockyTerrain.MAXIMUM_CUTS;
    }
}
