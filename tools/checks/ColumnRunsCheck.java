package cc.orthrus.riven.worldgen;

import java.util.TreeMap;

/**
 * Column runs, per-block resolution and the native per-cube shortcuts agree at every Y. Usage:
 * ColumnRunsCheck [seed ...]. Samples a coarse grid plus dense coastal strips per seed.
 */
public final class ColumnRunsCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /**
     * Chunk columns along a diagonal crossing land and coast: every cube equals per-block
     * resolution, each habitat equals surfaceHabitat, every block's ore equals a fresh per-column
     * resolution, and a fresh cache filled in reverse order returns identical columns.
     */
    private static int nativeColumns(GenerationMaps maps, TerrainHeightReference h, long seed) {
        int[][] chunks = new int[160][];
        for (int i = 0; i < chunks.length; i++) chunks[i] = new int[] {i * 3 - 240, i * 2 - 160};
        NativeColumns.Column[] forward = new NativeColumns.Column[chunks.length];
        for (int i = 0; i < chunks.length; i++)
            forward[i] = maps.columns.column(chunks[i][0], chunks[i][1]);
        NativeColumns reverse = new GenerationMaps(seed).columns;
        TerrainMap.Sample s = new TerrainMap.Sample();
        TerrainBlock[] line = new TerrainBlock[16], other = new TerrainBlock[16];
        OreMap.Bodies bodies = new OreMap.Bodies();
        for (int i = chunks.length - 1; i >= 0; i--) {
            int cx = chunks[i][0], cz = chunks[i][1];
            NativeColumns.Column again = reverse.column(cx, cz);
            for (int index = 0; index < 256; index++) {
                int x = cx * 16 + (index & 15), z = cz * 16 + (index >> 4);
                maps.terrain.sample(x, z, s);
                maps.terrain.caves.carve(x, z, s);
                maps.terrain.ores.bodies(x, z, s, bodies);
                require(
                        forward[i].habitat(index).equals(Habitats.at(s))
                                && again.habitat(index).equals(forward[i].habitat(index)),
                        "Cached habitat differs at " + x + "," + z + " seed " + seed);
                boolean bare = ColumnBlocks.bareStone(s, maps.surfaces);
                for (int bottom = h.minimumY - 16;
                        bottom < h.maximumYExclusive + 16;
                        bottom += 16) {
                    forward[i].cube(index, bottom, line);
                    again.cube(index, bottom, other);
                    for (int y = 0; y < 16; y++) {
                        TerrainBlock expected =
                                ColumnBlocks.at(bottom + y, s, bare, maps.surfaces, h);
                        require(
                                line[y] == expected && other[y] == expected,
                                "Cached cube differs at " + x + "," + (bottom + y) + "," + z);
                        int wy = bottom + y, ore = maps.terrain.ores.mineral(x, wy, z, bodies);
                        require(
                                forward[i].ore(index, x, wy, z) == ore
                                        && again.ore(index, x, wy, z) == ore,
                                "Cached ore differs at " + x + "," + wy + "," + z);
                    }
                }
            }
        }
        return chunks.length;
    }

    public static void main(String[] args) {
        long[] seeds =
                args.length == 0 ? new long[] {8675309L, 101L, 1L, 5L} : new long[args.length];
        for (int i = 0; i < args.length; i++) seeds[i] = Long.parseLong(args[i]);
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        int[] bottoms = new int[ColumnBlocks.maximumRuns() + 1];
        TerrainBlock[] blocks = new TerrainBlock[ColumnBlocks.maximumRuns()];
        TreeMap<String, Integer> coverage = new TreeMap<>();
        long columns = 0, blocksChecked = 0, chunkColumns = 0;
        int maximumRuns = 0;
        for (long seed : seeds) {
            GenerationMaps maps = new GenerationMaps(seed);
            TerrainMap terrain = maps.terrain;
            SurfaceRules rules = maps.surfaces;
            int minimumSoilY = terrain.minimumSurfaceY - ColumnBlocks.materialDepth(rules) + 1;
            TerrainMap.Sample s = new TerrainMap.Sample();
            ContinentalMap.Sample probe = new ContinentalMap.Sample();
            for (int z = -8192; z < 8192; z += 37)
                for (int x = -8192; x < 8192; x += 37) {
                    // Coarse grid everywhere; every column of a short strip where the coast is
                    // near.
                    boolean dense = false;
                    if (x % 1369 == 0 || z % 1369 == 0) {
                        maps.continents.sample(x, z, probe);
                        dense = Math.abs(probe.coastCoordinate) < 96;
                    }
                    // Wonders carry voids under their surfaces: check whole strips through them.
                    if (!dense && x % 185 == 0) {
                        terrain.sample(x, z, s);
                        dense = s.wonders.family != null;
                    }
                    int strip = dense ? 37 : 1;
                    for (int dx = 0; dx < strip; dx++) {
                        int wx = x + dx;
                        terrain.sample(wx, z, s);
                        columns++;
                        boolean full = ColumnBlocks.bareStone(s, rules);
                        int count = ColumnBlocks.runs(s, full, rules, h, bottoms, blocks);
                        maximumRuns = Math.max(maximumRuns, count);
                        require(
                                bottoms[0] == h.minimumY && bottoms[count] == h.maximumYExclusive,
                                "Runs must cover the allocation");
                        int surface = s.blockY(), run = 0;
                        for (long y = h.minimumY - 2; y < h.maximumYExclusive + 2; y++) {
                            TerrainBlock expected = ColumnBlocks.at(y, s, full, rules, h);
                            if (y >= h.minimumY && y < h.maximumYExclusive) {
                                while (y >= bottoms[run + 1]) run++;
                                require(
                                        blocks[run] == expected,
                                        "Run "
                                                + blocks[run]
                                                + " != "
                                                + expected
                                                + " at "
                                                + wx
                                                + ","
                                                + y
                                                + ","
                                                + z
                                                + " seed "
                                                + seed);
                            }
                            long cubeBottom = Math.floorDiv(y, 16) * 16, cubeTop = cubeBottom + 15;
                            boolean gate =
                                    cubeTop >= surface - ColumnBlocks.materialDepth(rules) + 1
                                            && cubeBottom <= surface;
                            require(
                                    ColumnBlocks.at(y, s, gate && full, rules, h) == expected,
                                    "Per-cube bare-stone gate differs at "
                                            + wx
                                            + ","
                                            + y
                                            + ","
                                            + z);
                            boolean lens = y >= s.water.aquiferMin && y <= s.water.aquiferMax;
                            if (y > terrain.maximumSurfaceY && y < h.maximumYExclusive)
                                require(expected == TerrainBlock.AIR, "Above highest surface");
                            if (y > h.minimumY && y < minimumSoilY && !lens)
                                require(
                                        expected == s.geology.block(y, surface),
                                        "Below lowest soil only geology varies");
                            blocksChecked++;
                        }
                        if (s.ocean.marine) coverage.merge("marine", 1, Integer::sum);
                        if (s.water.wet) coverage.merge("wet", 1, Integer::sum);
                        if (s.water.kind == Hydro.Kind.LAGOON)
                            coverage.merge("lagoon", 1, Integer::sum);
                        if (s.water.aquiferMin <= s.water.aquiferMax)
                            coverage.merge("aquifer", 1, Integer::sum);
                        if (s.sediment.weight > 0) coverage.merge("sediment", 1, Integer::sum);
                        if (s.rocky.weight > 0) coverage.merge("rocky", 1, Integer::sum);
                        if (s.rocky.hasVolume()) coverage.merge("rockyVolume", 1, Integer::sum);
                        if (s.wonders.family != null) coverage.merge("wonder", 1, Integer::sum);
                        if (s.wonders.hasVoid()) coverage.merge("wonderVoid", 1, Integer::sum);
                        if (s.caves.count() > 0) coverage.merge("caveEntrance", 1, Integer::sum);
                        if (full) coverage.merge("bareStone", 1, Integer::sum);
                        if (s.shore.sandy) coverage.merge("sandyShore", 1, Integer::sum);
                    }
                }
            chunkColumns += nativeColumns(maps, h, seed);
        }
        require(maximumRuns <= ColumnBlocks.maximumRuns(), "Run bound");
        System.out.println(
                "PASS native chunk columns = per-block, habitat = full-sample habitat, ore = per-column ore, reverse replay"
                        + " identical; chunkColumns="
                        + chunkColumns);
        System.out.println(
                "PASS column runs = per-block = per-cube gate; columns="
                        + columns
                        + " blocks="
                        + blocksChecked
                        + " maxRuns="
                        + maximumRuns
                        + " coverage="
                        + coverage
                        + (coverage.containsKey("rockyVolume")
                                ? ""
                                : "; coverage gap: no rocky volume column sampled"));
    }
}
