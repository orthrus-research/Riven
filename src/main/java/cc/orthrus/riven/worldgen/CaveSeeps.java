package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/**
 * Seep walls (riven-cave-decor.json seeps): groundwater wets cave walls in a band where a cave
 * meets the water. At most one seep per cell of a jittered grid, centred on the first of a few
 * candidate columns around the cell's point (spaced candidateSpacingBlocks) that passes, at least
 * minimumDepthBlocks below the ground: in a flooded cell the band lies just above its waterline,
 * where water wicks up the walls; elsewhere it lies at the column's water table where the rock is
 * at least minimumPermeability permeable. A dry cave must cross the band there. The seep
 * reads the real voids of a patch around the centre at the band's heights, and every rock block of
 * the band within radiusBlocks that faces cave air becomes the wet wall, fading out over the outer
 * half and patchy in the band's upper and lower rows (edgeChance): flowstone (dripstone block) in
 * soluble rock where the depth biome grows dripstone, mud where it has mud, packed ice where its
 * floors are ice; drier depth biomes keep their rock. Water, lakes and entrances stay as they are: only rock turns wet. Every seep is a pure
 * function of seed, definitions and coordinates; a bounded memo keeps the cells.
 */
final class CaveSeeps {
    /** One seep: the wet blocks of its patch, by column offset, as bits of the band. */
    private static final class Seep {
        final int x, z, bottom, reach;
        final byte[] wet;

        Seep(int x, int z, int bottom, int reach, byte[] wet) {
            this.x = x;
            this.z = z;
            this.bottom = bottom;
            this.reach = reach;
            this.wet = wet;
        }
    }

    private static final Seep[] NONE = new Seep[0];
    private static final int[] RING_X = {1, 1, 0, -1, -1, -1, 0, 1},
            RING_Z = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final Rules RULES = Rules.load("riven-cave-decor.json", 1);
    static final double CELL, CHANCE, RADIUS, PERMEABILITY, EDGE;
    static final int BELOW, ABOVE, DEPTH, SPACING;

    static {
        JsonObject j = RULES.json.getAsJsonObject("seeps");
        CELL = Rules.number(j, "cellBlocks", 16, 1024);
        CHANCE = Rules.number(j, "chance", 0, 1);
        RADIUS = Rules.number(j, "radiusBlocks", 1, 16);
        SPACING = (int) Rules.number(j, "candidateSpacingBlocks", 1, 64);
        // Every patch stays within the cell and its neighbours, which apply reads.
        if (RADIUS + 2 + SPACING >= 0.15 * CELL + CELL)
            throw new IllegalStateException("seeps: radiusBlocks too wide for cellBlocks");
        BELOW = (int) Rules.number(j, "belowTableBlocks", 0, 4);
        ABOVE = (int) Rules.number(j, "aboveTableBlocks", 0, 4);
        PERMEABILITY = Rules.number(j, "minimumPermeability", 0, 1);
        EDGE = Rules.number(j, "edgeChance", 0, 1);
        DEPTH = (int) Rules.number(j, "minimumDepthBlocks", 0, 128);
    }

    private final long seed;
    private final CavePools.Carver carver;
    private final CellMemo<Seep[]> cells = new CellMemo<>(1024);
    private final ThreadLocal<TerrainMap.Sample> scratch =
            ThreadLocal.withInitial(TerrainMap.Sample::new);

    CaveSeeps(long worldSeed, CavePools.Carver carver) {
        seed =
                SeededNoise.mix(
                        worldSeed ^ Rules.seed(RULES.json, "variationSeed") ^ 0x5345455053313131L);
        this.carver = carver;
    }

    private static double unit(long key, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(key + parameter * 0x9e3779b97f4a7c15L));
    }

    private Seep[] cell(int i, int j) {
        return cells.get(
                i,
                j,
                () -> {
                    long key = SeededNoise.cell(seed, i, j);
                    if (unit(key, 1) >= CHANCE) return NONE;
                    int px = (int) Math.floor((i + 0.15 + 0.7 * unit(key, 2)) * CELL),
                            pz = (int) Math.floor((j + 0.15 + 0.7 * unit(key, 3)) * CELL);
                    TerrainMap.Sample s = scratch.get();
                    // The cell's point, then the eight around it in an order of the cell's own.
                    int turn = (int) (unit(key, 4) * 8), x = 0, z = 0, bottom = 0;
                    boolean found = false;
                    for (int n = 0; n < 9 && !found; n++) {
                        int a = n == 0 ? -1 : (n - 1 + turn) % 8;
                        x = px + (a < 0 ? 0 : RING_X[a] * SPACING);
                        z = pz + (a < 0 ? 0 : RING_Z[a] * SPACING);
                        carver.sample(x, z, s);
                        if (s.caves.count() > 0) continue;
                        // Over a flooded cell's water, or at a permeable water table.
                        int table = (int) Math.floor(s.geology.waterTable);
                        if (s.flood.flooded) bottom = s.flood.level + 1;
                        else if (s.flood.waterTop == Integer.MIN_VALUE
                                && s.geology.formation(table).permeability >= PERMEABILITY)
                            bottom = table - BELOW;
                        else continue;
                        if (s.blockY() - (bottom + BELOW + ABOVE) < DEPTH
                                || s.geology.lavaY >= bottom - 2) continue;
                        carver.voids(x, z, s, bottom, bottom + BELOW + ABOVE);
                        int middle = bottom + BELOW;
                        for (int k = 0; k < s.caves.count(); k++)
                            if (s.caves.lo[k] <= middle
                                    && s.caves.hi[k] > middle
                                    && s.caves.fill(middle, s) == TerrainBlock.AIR) found = true;
                    }
                    if (!found) return NONE;
                    int top = bottom + BELOW + ABOVE, span = top - bottom + 1;
                    // The patch's open air by column and height, one ring wider than the seep.
                    int reach = (int) Math.ceil(RADIUS) + 1, side = 2 * reach + 1;
                    // Open air and plain rock by column and height; a block that is neither (water,
                    // ice, a lake) is left alone. Entrance columns stay out: the preview shows
                    // them.
                    boolean[] air = new boolean[side * side * span],
                            rock = new boolean[side * side * span];
                    for (int dz = -reach; dz <= reach; dz++)
                        for (int dx = -reach; dx <= reach; dx++) {
                            carver.sample(x + dx, z + dz, s);
                            if (s.caves.count() > 0) continue;
                            carver.voids(x + dx, z + dz, s, bottom, top);
                            int at = ((dz + reach) * side + dx + reach) * span;
                            for (int y = bottom; y <= top; y++) {
                                boolean open = s.caves.voids(y);
                                air[at + y - bottom] =
                                        open && s.caves.fill(y, s) == TerrainBlock.AIR;
                                rock[at + y - bottom] =
                                        !open && (y < s.water.aquiferMin || y > s.water.aquiferMax);
                            }
                        }
                    byte[] wet = new byte[side * side];
                    boolean any = false;
                    for (int dz = -reach + 1; dz < reach; dz++)
                        for (int dx = -reach + 1; dx < reach; dx++) {
                            double d = Math.hypot(dx, dz);
                            if (d >= RADIUS) continue;
                            // The band fades out over its outer half, and its upper and lower
                            // rows are patchy, so a seep has ragged edges.
                            double fade = Math.min(1, 2 * (RADIUS - d) / RADIUS);
                            int c = (dz + reach) * side + dx + reach;
                            for (int y = 0; y < span; y++) {
                                if (!rock[c * span + y]) continue;
                                long block =
                                        SeededNoise.mix(
                                                key ^ SeededNoise.cell(bottom + y, x + dx, z + dz));
                                if (unit(block, 1) >= fade * (y == BELOW ? 1 : EDGE)) continue;
                                if (air[(c - 1) * span + y]
                                        || air[(c + 1) * span + y]
                                        || air[(c - side) * span + y]
                                        || air[(c + side) * span + y]) {
                                    wet[c] |= (byte) (1 << y);
                                    any = true;
                                }
                            }
                        }
                    return any ? new Seep[] {new Seep(x, z, bottom, reach, wet)} : NONE;
                });
    }

    /** For checks: a cell's seep as {x, z, band bottom, wet blocks}, or null. */
    int[] site(int i, int j) {
        Seep[] p = cell(i, j);
        if (p.length == 0) return null;
        int count = 0;
        for (byte b : p[0].wet) count += Integer.bitCount(b & 0xff);
        return new int[] {p[0].x, p[0].z, p[0].bottom, count};
    }

    /** Adds the wet wall blocks of the seeps around a column to its voids' decoration. */
    void apply(int x, int z, TerrainMap.Sample s, CaveMap.Voids v) {
        int ci = (int) Math.floor(x / CELL), cj = (int) Math.floor(z / CELL);
        for (int dj = -1; dj <= 1; dj++)
            for (int di = -1; di <= 1; di++)
                for (Seep p : cell(ci + di, cj + dj)) {
                    int dx = x - p.x, dz = z - p.z;
                    if (Math.abs(dx) >= p.reach || Math.abs(dz) >= p.reach) continue;
                    int side = 2 * p.reach + 1;
                    byte bits = p.wet[(dz + p.reach) * side + dx + p.reach];
                    for (int y = 0; bits != 0; y++, bits >>= 1)
                        if ((bits & 1) != 0) {
                            int at = p.bottom + y;
                            TerrainBlock wall = wall(at, s);
                            if (wall != null) v.addDecor(at, at + 1, wall);
                        }
                }
    }

    /**
     * A wet wall block: flowstone where dripstone grows, mud where the depth biome has mud, packed
     * ice where its floors are ice (both read just above, as for a floor block), or null where the
     * depth biome is too dry for any.
     */
    private static TerrainBlock wall(int y, TerrainMap.Sample s) {
        if (RealmMap.cell(y, s).dripstone
                && s.geology.unit(y).solubility >= CaveDecor.dripSolubility())
            return TerrainBlock.DRIPSTONE_BLOCK;
        RealmMap.Depth above = RealmMap.cell(y + 1L, s);
        if (above.mud) return TerrainBlock.MUD;
        return above.floor == RealmMap.Floor.ICE ? TerrainBlock.PACKED_ICE : null;
    }
}
