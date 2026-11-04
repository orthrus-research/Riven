package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.AssembledColumn;
import cc.orthrus.riven.worldgen.engine.Assembler;
import cc.orthrus.riven.worldgen.engine.Role;

import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Resolved native chunk columns: each 16 x 16 column is sampled once, assembled by the crust's
 * column engine into runs of blocks and roles, stored with its habitat, and reused by every cube
 * and the biome array of that column. Entries are immutable pure functions of seed, definitions
 * and position, so eviction and request order cannot change any block; concurrent misses may
 * duplicate work but publish identical values.
 */
public final class NativeColumns {
    /** One immutable resolved chunk column. Column index is (z << 4) | x. */
    public static final class Column {
        private final int[] offsets, bottoms;
        private final TerrainBlock[] blocks;
        private final byte[] roles;
        private final String[] keys;
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
                byte[] roles,
                String[] keys,
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
            this.roles = roles;
            this.keys = keys;
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
            int run = run(index, y);
            return run < 0 ? TerrainBlock.AIR : blocks[run];
        }

        /** The role of the block at Y of one column (air outside the crust). */
        public Role role(int index, int y) {
            int run = run(index, y);
            return run < 0 ? Role.AIR : Role.of(roles[run]);
        }

        private int run(int index, int y) {
            int first = offsets[index], sentinel = offsets[index + 1] - 1;
            if (y < bottoms[first] || y >= bottoms[sentinel]) return -1;
            int run = first;
            while (y >= bottoms[run + 1]) run++;
            return run;
        }

        /** The habitat key ({@link Habitats#key}) of a column, which selects its riven: biome. */
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
    private final Assembler<TerrainMap.Sample> crust;
    private final TerrainHeightReference heights;
    private final int sets, ways;
    private final AtomicReferenceArray<Entry> memo;
    private final AtomicIntegerArray next;

    NativeColumns(TerrainMap terrain, SurfaceRules rules, TerrainHeightReference heights) {
        this.terrain = terrain;
        crust = CrustShape.assembler(rules, heights);
        this.heights = heights;
        JsonObject j = Terrain.nativeColumnCache();
        sets = integer(j, "sets", 16, 4096);
        ways = integer(j, "ways", 1, 8);
        if ((sets & (sets - 1)) != 0)
            throw new IllegalStateException("Native column cache sets must be a power of two");
        memo = new AtomicReferenceArray<>(sets * ways);
        next = new AtomicIntegerArray(sets);
    }

    /** The crust's column assembler every native and preview column is built with. */
    public Assembler<TerrainMap.Sample> assembler() {
        return crust;
    }

    private static int integer(JsonObject j, String key, int min, int max) {
        double n = Rules.number(j, key, min, max);
        if (n != (int) n) throw new IllegalStateException(key);
        return (int) n;
    }

    public Column column(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
        Column held = held(key);
        if (held != null) return held;
        int set = (int) SeededNoise.mix(key) & (sets - 1), first = set * ways;
        Column built = build(chunkX, chunkZ);
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.column;
        }
        memo.set(first + Math.floorMod(next.getAndIncrement(set), ways), new Entry(key, built));
        return built;
    }

    /** The chunk column of a key if the memo holds it now (timing only). */
    private Column held(long key) {
        int first = ((int) SeededNoise.mix(key) & (sets - 1)) * ways;
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.column;
        }
        return null;
    }

    /** Recent one-column answers of this thread, direct-mapped by position. */
    private static final int SLOTS = 4096;

    private static final int[] DRY = {};

    private static final class Resolved {
        final long[] keys = new long[SLOTS];
        final boolean[] filled = new boolean[SLOTS];
        final int[] tops = new int[SLOTS];
        // A slot's water runs, non-null once its carved answer is held; null while its top is only
        // the uncarved bound.
        final int[][] waters = new int[SLOTS][];
        final TerrainMap.Sample sample = new TerrainMap.Sample();
        final AssembledColumn column = new AssembledColumn();
        boolean resolving;
        // The last resolve: its surface, and its water runs as [bottom, top) pairs (null for a
        // bound).
        int top;
        int[] water;
    }

    private final ThreadLocal<Resolved> resolved = ThreadLocal.withInitial(Resolved::new);

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private Column held(int x, int z) {
        return held(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL));
    }

    /**
     * The top of the highest non-air run of the column at (x, z), its ground or water over it: the
     * value {@code column(x >> 4, z >> 4).surface(index)} gives. A held chunk column answers;
     * otherwise this one column is resolved as {@link #build} resolves it (sampled, carved, its
     * runs), without its ores, habitat keys or the other 255 columns. A tree site asks for every column
     * its tree reaches, so neighbouring sites ask again; each thread keeps its recent answers.
     */
    public int surface(int x, int z) {
        Column held = held(x, z);
        if (held != null) return held.surface(((z & 15) << 4) | (x & 15));
        Resolved r = resolved.get();
        return r.tops[exact(x, z, r)];
    }

    /**
     * Whether the column at (x, z) holds water at Y in its native blocks, as {@code column(x >> 4,
     * z >> 4).block(index, y) == WATER} says; answered like {@link #surface}.
     */
    public boolean water(int x, int y, int z) {
        Column held = held(x, z);
        if (held != null) return held.block(((z & 15) << 4) | (x & 15), y) == TerrainBlock.WATER;
        Resolved r = resolved.get();
        int[] water = r.waters[exact(x, z, r)];
        for (int i = 0; i < water.length; i += 2)
            if (y >= water[i] && y < water[i + 1]) return true;
        return false;
    }

    /**
     * Whether the column at (x, z) rises above {@code height}: {@code surface(x, z) > height}.
     * Carving only takes blocks away or floods them and never raises a column's top, so the
     * uncarved runs bound the surface from above. Where they reach no higher than height the
     * answer is no without carving, and only the rest is carved. Tree sites' clearance asks this.
     */
    public boolean surfaceAbove(int x, int z, int height) {
        Column held = held(x, z);
        if (held != null) return held.surface(((z & 15) << 4) | (x & 15)) > height;
        Resolved r = resolved.get();
        long key = key(x, z);
        int slot = (int) SeededNoise.mix(key) & (SLOTS - 1);
        if (r.filled[slot] && r.keys[slot] == key) {
            // An exact top, or a bound that already settles it.
            if (r.waters[slot] != null || r.tops[slot] <= height) return r.tops[slot] > height;
            return r.tops[exact(x, z, r)] > height;
        }
        Resolved scratch = r.resolving ? new Resolved() : r;
        scratch.resolving = true;
        try {
            terrain.sample(x, z, scratch.sample);
            readRuns(scratch, false);
            if (scratch.top > height) {
                terrain.caves.carve(x, z, scratch.sample);
                readRuns(scratch, true);
            }
            store(r, slot, key, scratch);
            return scratch.top > height;
        } finally {
            scratch.resolving = false;
        }
    }

    /** The slot holding the column's carved answer, resolved into it if absent or only bounded. */
    private int exact(int x, int z, Resolved r) {
        long key = key(x, z);
        int slot = (int) SeededNoise.mix(key) & (SLOTS - 1);
        if (r.filled[slot] && r.keys[slot] == key && r.waters[slot] != null) return slot;
        // A resolve never nests, but a scratch in use is never shared.
        Resolved scratch = r.resolving ? new Resolved() : r;
        scratch.resolving = true;
        try {
            resolve(x, z, scratch, true);
            store(r, slot, key, scratch);
            return slot;
        } finally {
            scratch.resolving = false;
        }
    }

    private static void store(Resolved r, int slot, long key, Resolved from) {
        r.keys[slot] = key;
        r.filled[slot] = true;
        r.tops[slot] = from.top;
        r.waters[slot] = from.water;
    }

    /** One column's runs, carved or (for the parity check) not: its surface and its water runs. */
    private void resolve(int x, int z, Resolved s, boolean carved) {
        terrain.sample(x, z, s.sample);
        if (carved) terrain.caves.carve(x, z, s.sample);
        readRuns(s, true);
    }

    /** The sample's runs as it stands: its surface, and its water runs when they are wanted. */
    private void readRuns(Resolved s, boolean water) {
        AssembledColumn c = crust.assemble(s.sample, s.column);
        int count = c.count(), top = Integer.MIN_VALUE, waters = 0;
        for (int i = 0; i < count; i++) {
            if (c.runBlock(i) != TerrainBlock.AIR) top = c.top(i);
            if (c.runBlock(i) == TerrainBlock.WATER) waters++;
        }
        s.top = top;
        if (!water) {
            s.water = null;
            return;
        }
        int[] runs = waters == 0 ? DRY : new int[2 * waters];
        for (int i = 0, w = 0; i < count; i++)
            if (c.runBlock(i) == TerrainBlock.WATER) {
                runs[w++] = c.bottom(i);
                runs[w++] = c.top(i);
            }
        s.water = runs;
    }

    /** One column's surface resolved afresh, carved or not, without the memo or cache (parity checks). */
    public int resolvedSurface(int x, int z, boolean carved) {
        Resolved s = new Resolved();
        resolve(x, z, s, carved);
        return s.top;
    }

    /**
     * One above the highest block of a column's own rock: the top of its highest bed run, so the
     * cover its setting lays (soil, sand, beach, bar and slope gravel, the sandstone under sand)
     * and its deposits stay above it. Ore stays below it.
     */
    static int bedrockTop(AssembledColumn c) {
        for (int i = c.count() - 1; i >= 0; i--) if (c.runRole(i) == Role.BED) return c.top(i);
        return c.bottomY();
    }

    /** Resolves without the cache; also the reference for parity checks. */
    public Column build(int chunkX, int chunkZ) {
        int[] offsets = new int[257], bottoms = new int[256 * 24];
        TerrainBlock[] blocks = new TerrainBlock[bottoms.length];
        byte[] roles = new byte[bottoms.length];
        String[] keys = new String[256];
        AssembledColumn assembled = new AssembledColumn();
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
                AssembledColumn c = crust.assemble(sample, assembled);
                // Ore replaces the bed's own rock only, never the cover its setting lays.
                int bedrock = bedrockTop(c);
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
                int count = c.count();
                if (n + count + 1 > bottoms.length) {
                    int grown = Math.max(2 * bottoms.length, n + count + 1);
                    bottoms = java.util.Arrays.copyOf(bottoms, grown);
                    blocks = java.util.Arrays.copyOf(blocks, grown);
                    roles = java.util.Arrays.copyOf(roles, grown);
                }
                offsets[index] = n;
                for (int i = 0; i < count; i++) {
                    bottoms[n] = c.bottom(i);
                    roles[n] = c.runRole(i).code();
                    blocks[n++] = c.runBlock(i);
                }
                // The allocation top closes the column; it is never read as a run block.
                bottoms[n] = c.topY();
                roles[n] = Role.AIR.code();
                blocks[n++] = TerrainBlock.AIR;
                keys[index] = Habitats.key(sample);
                int ground = ground(c);
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
                java.util.Arrays.copyOf(roles, n),
                keys,
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
    static int ground(AssembledColumn c) {
        for (int i = c.count() - 1; i >= 0; i--) {
            TerrainBlock block = c.runBlock(i);
            if (block != TerrainBlock.AIR
                    && block != TerrainBlock.WATER
                    && block != TerrainBlock.LAVA) return c.top(i) - 1;
        }
        return c.bottomY() - 1;
    }
}
