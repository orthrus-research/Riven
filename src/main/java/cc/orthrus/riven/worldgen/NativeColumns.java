package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Resolved native chunk columns: each 16 x 16 column is sampled once, stored as block runs and
 * habitat, and reused by every cube and the biome array of that column. Entries are immutable
 * pure functions of seed, definitions and position, so eviction and request order cannot change
 * any block; concurrent misses may duplicate work but publish identical values.
 */
public final class NativeColumns {
    /** One immutable resolved chunk column. Column index is (z << 4) | x. */
    public static final class Column {
        private final int[] offsets, bottoms;
        private final TerrainBlock[] blocks;
        private final String[] habitats, keys;
        private final byte[] climates;
        private final OreMap ores;
        private final int[] oreOffsets, oreLo, oreHi;
        private final short[] oreDeposits;
        private final int[] cellOffsets, cellBottoms;
        private final byte[] cellDepths;
        private final int[] unstableOffsets, unstableLo, unstableHi;
        private final int minimumGround;

        private Column(
                int[] offsets,
                int[] bottoms,
                TerrainBlock[] blocks,
                String[] habitats,
                String[] keys,
                byte[] climates,
                OreMap ores,
                int[] oreOffsets,
                int[] oreLo,
                int[] oreHi,
                short[] oreDeposits,
                int[] cellOffsets,
                int[] cellBottoms,
                byte[] cellDepths,
                int[] unstableOffsets,
                int[] unstableLo,
                int[] unstableHi,
                int minimumGround) {
            this.offsets = offsets;
            this.bottoms = bottoms;
            this.blocks = blocks;
            this.habitats = habitats;
            this.keys = keys;
            this.climates = climates;
            this.ores = ores;
            this.oreOffsets = oreOffsets;
            this.oreLo = oreLo;
            this.oreHi = oreHi;
            this.oreDeposits = oreDeposits;
            this.cellOffsets = cellOffsets;
            this.cellBottoms = cellBottoms;
            this.cellDepths = cellDepths;
            this.unstableOffsets = unstableOffsets;
            this.unstableLo = unstableLo;
            this.unstableHi = unstableHi;
            this.minimumGround = minimumGround;
        }

        /**
         * The lowest ground of the chunk column's 256 columns: the top of each one's highest
         * block that is neither air nor fluid. A cube wholly below it holds no surface.
         */
        public int minimumGround() {
            return minimumGround;
        }

        /**
         * The depth biome ({@link RealmMap#depths} index plus one) of cell column {@code cell},
         * ((z / 4) * 4 + x / 4), at Y, or 0 where the column's own biome reports (at and above
         * the surface band).
         */
        public int depth(int cell, int y) {
            int code = 0;
            for (int i = cellOffsets[cell]; i < cellOffsets[cell + 1] && cellBottoms[i] <= y; i++)
                code = cellDepths[i];
            return code;
        }

        /**
         * The ore mineral ({@link OreMap#MINERALS}) replacing the rock at world (x, y, z) of a
         * column, or -1. Callers apply it only to rock blocks.
         */
        public int ore(int index, int x, int y, int z) {
            return ores.mineral(
                    x, y, z, oreLo, oreHi, oreDeposits, oreOffsets[index], oreOffsets[index + 1]);
        }

        /**
         * Whether the rock at Y of a column is unstable ({@link CaveMap.Voids#unstable}). Callers
         * apply it only to host rock that no ore replaced.
         */
        public boolean unstable(int index, int y) {
            for (int i = unstableOffsets[index]; i < unstableOffsets[index + 1]; i++)
                if (y >= unstableLo[i] && y < unstableHi[i]) return true;
            return false;
        }

        /** The provider climate grid cell ({@link Habitats#climate}) of a column. */
        public int climate(int index) {
            return climates[index];
        }

        /** Writes the 16 blocks from cubeBottomY upward for one column into out. */
        public void cube(int index, int cubeBottomY, TerrainBlock[] out) {
            // A column's entries are its sorted runs, then a sentinel whose bottom is the
            // allocation top; Y outside [first bottom, top) is outside the allocation.
            int first = offsets[index], sentinel = offsets[index + 1] - 1, run = first;
            for (int y = 0; y < 16; y++) {
                int worldY = cubeBottomY + y;
                if (worldY < bottoms[first] || worldY >= bottoms[sentinel]) {
                    out[y] = TerrainBlock.AIR;
                    continue;
                }
                while (worldY >= bottoms[run + 1]) run++;
                out[y] = blocks[run];
            }
        }

        /** The block at Y of one column. */
        public TerrainBlock block(int index, int y) {
            int first = offsets[index], sentinel = offsets[index + 1] - 1;
            if (y < bottoms[first] || y >= bottoms[sentinel]) return TerrainBlock.AIR;
            int run = first;
            while (y >= bottoms[run + 1]) run++;
            return blocks[run];
        }

        public String habitat(int index) {
            return habitats[index];
        }

        /** The decoration-provider key ({@link Habitats#key}) of a column. */
        public String key(int index) {
            return keys[index];
        }

        /** Top of the highest non-air run (ground, or water over it) of a column. */
        public int surface(int index) {
            int top = Integer.MIN_VALUE;
            for (int i = offsets[index]; i < offsets[index + 1] - 1; i++)
                if (blocks[i] != TerrainBlock.AIR) top = bottoms[i + 1];
            return top;
        }
    }

    private static final class Entry {
        final long key;
        final Column column;

        Entry(long key, Column column) {
            this.key = key;
            this.column = column;
        }
    }

    private final TerrainMap terrain;
    private final SurfaceRules rules;
    private final TerrainHeightReference heights;
    private final int sets, ways;
    private final AtomicReferenceArray<Entry> memo;
    private final AtomicIntegerArray next;

    NativeColumns(TerrainMap terrain, SurfaceRules rules, TerrainHeightReference heights) {
        this.terrain = terrain;
        this.rules = rules;
        this.heights = heights;
        JsonObject j = Terrain.nativeColumnCache();
        sets = integer(j, "sets", 16, 4096);
        ways = integer(j, "ways", 1, 8);
        if ((sets & (sets - 1)) != 0)
            throw new IllegalStateException("Native column cache sets must be a power of two");
        memo = new AtomicReferenceArray<>(sets * ways);
        next = new AtomicIntegerArray(sets);
    }

    private static int integer(JsonObject j, String key, int min, int max) {
        double n = Rules.number(j, key, min, max);
        if (n != (int) n) throw new IllegalStateException(key);
        return (int) n;
    }

    public Column column(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
        int set = (int) SeededNoise.mix(key) & (sets - 1), first = set * ways;
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.column;
        }
        Column built = build(chunkX, chunkZ);
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.column;
        }
        memo.set(first + Math.floorMod(next.getAndIncrement(set), ways), new Entry(key, built));
        return built;
    }

    /**
     * One above the highest block of a column's own rock: below the cover its surface rules lay
     * (soil, sand, beach, bar and slope gravel, the sandstone under sand), past air and water. Where
     * the cover reaches the material depth, ore stays below it.
     */
    static int bedrockTop(
            TerrainMap.Sample sample, SurfaceRules rules, TerrainHeightReference heights) {
        int surface = sample.blockY(), floor = surface - ColumnBlocks.materialDepth(rules);
        boolean bare = ColumnBlocks.bareStone(sample, rules);
        for (int y = surface; y > floor; y--) {
            TerrainBlock block = ColumnBlocks.at(y, sample, bare, rules, heights);
            if (block == TerrainBlock.AIR || block == TerrainBlock.WATER) continue;
            if (block == Materials.stratum(sample, y, surface)) return y + 1;
        }
        return floor + 1;
    }

    /** Resolves without the cache; also the reference for parity checks. */
    public Column build(int chunkX, int chunkZ) {
        int capacity = ColumnBlocks.maximumRuns();
        int[] offsets = new int[257], bottoms = new int[256 * (capacity + 1)];
        TerrainBlock[] blocks = new TerrainBlock[256 * capacity];
        String[] habitats = new String[256], keys = new String[256];
        byte[] climates = new byte[256];
        int[] runBottoms = new int[capacity + 1];
        TerrainBlock[] runBlocks = new TerrainBlock[capacity];
        TerrainMap.Sample sample = new TerrainMap.Sample();
        OreMap.Bodies bodies = new OreMap.Bodies();
        int[] oreOffsets = new int[257],
                oreLo = new int[256 * OreMap.maximumBodies()],
                oreHi = new int[oreLo.length];
        short[] oreDeposits = new short[oreLo.length];
        // Each 4x4 cell column's depth biomes, as runs of 4-block cells from the crust floor.
        int[] cellOffsets = new int[17],
                cellBottoms =
                        new int[16 * ((heights.maximumYExclusive - heights.minimumY) / 4 + 1)];
        byte[] cellDepths = new byte[cellBottoms.length];
        int[] unstableOffsets = new int[257], unstableLo = new int[512], unstableHi = new int[512];
        int n = 0, ores = 0, cells = 0, unstables = 0, minimumGround = Integer.MAX_VALUE;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int index = (z << 4) | x;
                terrain.sample(chunkX * 16 + x, chunkZ * 16 + z, sample);
                terrain.caves.carve(chunkX * 16 + x, chunkZ * 16 + z, sample);
                terrain.ores.bodies(chunkX * 16 + x, chunkZ * 16 + z, sample, bodies);
                // Ore replaces the bed's own rock only, never the cover the surface rules lay.
                int bedrock = bedrockTop(sample, rules, heights);
                oreOffsets[index] = ores;
                for (int i = 0; i < bodies.count(); i++) {
                    int hi = Math.min(bodies.hi(i), bedrock);
                    if (bodies.lo(i) >= hi) continue;
                    oreLo[ores] = bodies.lo(i);
                    oreHi[ores] = hi;
                    oreDeposits[ores++] = (short) bodies.deposit(i);
                }
                CaveMap.Voids voids = sample.caves;
                unstableOffsets[index] = unstables;
                for (int i = 0; i < voids.unstables(); i++) {
                    if (unstables == unstableLo.length) {
                        unstableLo = java.util.Arrays.copyOf(unstableLo, 2 * unstables);
                        unstableHi = java.util.Arrays.copyOf(unstableHi, 2 * unstables);
                    }
                    unstableLo[unstables] = voids.unstableLo(i);
                    unstableHi[unstables++] = voids.unstableHi(i);
                }
                int count =
                        ColumnBlocks.runs(
                                sample,
                                ColumnBlocks.bareStone(sample, rules),
                                rules,
                                heights,
                                runBottoms,
                                runBlocks);
                offsets[index] = n;
                for (int i = 0; i < count; i++) {
                    bottoms[n] = runBottoms[i];
                    blocks[n++] = runBlocks[i];
                }
                // The allocation top closes the column; it is never read as a run block.
                bottoms[n] = runBottoms[count];
                blocks[n++] = TerrainBlock.AIR;
                habitats[index] = Habitats.at(sample);
                keys[index] = Habitats.key(sample);
                climates[index] = (byte) Habitats.climate(sample);
                int ground = ground(runBottoms, runBlocks, count);
                minimumGround = Math.min(minimumGround, ground);
                // Cell columns read their centre column (x and z of 2 within the cell).
                if ((x & 3) == 2 && (z & 3) == 2) {
                    cellOffsets[(z >> 2) * 4 + (x >> 2)] = cells;
                    // Runs start where the code changes; before the first run it is 0.
                    int previous = 0;
                    for (int bottom = heights.minimumY; bottom < ground; bottom += 4) {
                        int centre = bottom + 2,
                                code =
                                        ground - centre >= RealmMap.surfaceBand()
                                                ? RealmMap.depth(centre, sample).index + 1
                                                : 0;
                        if (code == previous) continue;
                        cellBottoms[cells] = bottom;
                        cellDepths[cells++] = (byte) code;
                        previous = code;
                    }
                }
            }
        offsets[256] = n;
        oreOffsets[256] = ores;
        cellOffsets[16] = cells;
        unstableOffsets[256] = unstables;
        return new Column(
                offsets,
                java.util.Arrays.copyOf(bottoms, n),
                java.util.Arrays.copyOf(blocks, n),
                habitats,
                keys,
                climates,
                terrain.ores,
                oreOffsets,
                java.util.Arrays.copyOf(oreLo, ores),
                java.util.Arrays.copyOf(oreHi, ores),
                java.util.Arrays.copyOf(oreDeposits, ores),
                cellOffsets,
                java.util.Arrays.copyOf(cellBottoms, cells),
                java.util.Arrays.copyOf(cellDepths, cells),
                unstableOffsets,
                java.util.Arrays.copyOf(unstableLo, unstables),
                java.util.Arrays.copyOf(unstableHi, unstables),
                minimumGround);
    }

    /**
     * The ground of a column's runs: the Y of its highest block that is neither air nor fluid
     * (an entrance's floor where one opens, the bed under surface water).
     */
    static int ground(int[] bottoms, TerrainBlock[] blocks, int count) {
        for (int i = count - 1; i >= 0; i--)
            if (blocks[i] != TerrainBlock.AIR
                    && blocks[i] != TerrainBlock.WATER
                    && blocks[i] != TerrainBlock.LAVA) return bottoms[i + 1] - 1;
        return bottoms[0] - 1;
    }
}
