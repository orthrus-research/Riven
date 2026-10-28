package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Cave pools (riven-cave-decor.json pools): now and then a small still pool on a wet cave floor.
 * At most one pool per cell of a jittered grid: its centre column offers its cave floors, in an
 * order of the cell's own, and the first that passes becomes the pool. A floor passes when it is
 * dry, its subsurface climate is wet, its void is tall enough above the water, and the pool's
 * patch holds the water: inside the disc every cave block up to the level lies at most a few
 * blocks above its floor, in the ring around it every cave block up to the level can take a low
 * rim (dripstone block in soluble rock where the depth biome grows dripstone, the host rock
 * elsewhere), and no other cave, entrance, flood band, lake or lava lies near. So a pool's water
 * meets only rock, rim or its own surface. A pool in a frozen depth biome is ice. Every pool is a
 * pure function of seed, definitions and coordinates; a bounded memo keeps the cells.
 */
final class CavePools {
    /** The geology and cave voids of a column, without pools or decoration. */
    interface Carver {
        void sample(double x, double z, TerrainMap.Sample out);

        /** Adds the column's 3D cave voids within [from, to] to its sample. */
        void voids(int x, int z, TerrainMap.Sample s, int from, int to);
    }

    /** One pool: its centre column, disc radius and water level. */
    private static final class Pool {
        final int x, z, level;
        final double radius;

        Pool(int x, int z, double radius, int level) {
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.level = level;
        }
    }

    private static final Pool[] NONE = new Pool[0];
    private static final Rules RULES = Rules.load("riven-cave-decor.json", 1);
    static final double CELL, CHANCE, RING, HUMIDITY, CELSIUS;
    static final double[] RADIUS;
    static final int DEPTH, RIM, AIR_ABOVE, MINIMUM_WATER, ATTEMPTS;

    static {
        JsonObject j = RULES.json.getAsJsonObject("pools");
        CELL = Rules.number(j, "cellBlocks", 16, 1024);
        CHANCE = Rules.number(j, "chance", 0, 1);
        JsonArray r = j.getAsJsonArray("radiusBlocks");
        RADIUS = new double[] {r.get(0).getAsDouble(), r.get(1).getAsDouble()};
        RING = Rules.number(j, "ringBlocks", 1, 4);
        // Pool centres lie within the middle 70% of their cell, so two cells' pools never meet.
        if (!(RADIUS[0] >= 1 && RADIUS[1] >= RADIUS[0] && 2 * (RADIUS[1] + RING) < 0.3 * CELL))
            throw new IllegalStateException("pools: radiusBlocks must keep two cells' pools apart");
        DEPTH = (int) Rules.number(j, "maximumDepthBlocks", 1, 8);
        RIM = (int) Rules.number(j, "maximumRimBlocks", 1, 4);
        AIR_ABOVE = (int) Rules.number(j, "airAboveBlocks", 1, 16);
        MINIMUM_WATER = (int) Rules.number(j, "minimumWaterColumns", 1, 64);
        ATTEMPTS = (int) Rules.number(j, "attempts", 1, 8);
        HUMIDITY = Rules.number(j, "minimumHumidity", 0, 1);
        CELSIUS = Rules.number(j, "maximumCelsius", 0, 200);
    }

    private final long seed;
    private final Carver carver;
    private final CellMemo<Pool[]> cells = new CellMemo<>(1024);
    private final ThreadLocal<TerrainMap.Sample>
            centre = ThreadLocal.withInitial(TerrainMap.Sample::new),
            patch = ThreadLocal.withInitial(TerrainMap.Sample::new);
    private final ThreadLocal<SubsurfaceClimate.Reading> climate =
            ThreadLocal.withInitial(SubsurfaceClimate.Reading::new);

    CavePools(long worldSeed, Carver carver) {
        seed =
                SeededNoise.mix(
                        worldSeed ^ Rules.seed(RULES.json, "variationSeed") ^ 0x504f4f4c53313131L);
        this.carver = carver;
    }

    private static double unit(long key, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(key + parameter * 0x9e3779b97f4a7c15L));
    }

    /** A cell's pool, if any (an empty array where none). */
    private Pool[] cell(int i, int j) {
        return cells.get(
                i,
                j,
                () -> {
                    long key = SeededNoise.cell(seed, i, j);
                    if (unit(key, 1) >= CHANCE) return NONE;
                    int x = (int) Math.floor((i + 0.15 + 0.7 * unit(key, 2)) * CELL),
                            z = (int) Math.floor((j + 0.15 + 0.7 * unit(key, 3)) * CELL);
                    double radius = RADIUS[0] + (RADIUS[1] - RADIUS[0]) * unit(key, 4);
                    TerrainMap.Sample s = centre.get();
                    carver.sample(x, z, s);
                    // No pool in a column an entrance opens through: the preview would miss it.
                    if (s.caves.count() > 0) return NONE;
                    carver.voids(x, z, s, Integer.MIN_VALUE, Integer.MAX_VALUE);
                    CaveMap.Voids v = s.caves;
                    int count = v.count();
                    // The centre's floors, in an order of the cell's own; the first that passes.
                    int start = (int) (unit(key, 5) * Math.max(1, count)), tried = 0;
                    for (int n = 0; n < count && tried < ATTEMPTS; n++) {
                        int k = (start + n) % count, floor = v.lo[k];
                        int level = floor + (int) (unit(key, 6 + n) * Math.min(2, DEPTH));
                        if (v.hi[k] - level <= AIR_ABOVE
                                || v.fill(floor, s) != TerrainBlock.AIR
                                || v.fill(level, s) != TerrainBlock.AIR) continue;
                        SubsurfaceClimate.Reading r =
                                SubsurfaceClimate.at(level, s, -1, climate.get());
                        if (r.humidity < HUMIDITY || r.celsius > CELSIUS) continue;
                        tried++;
                        if (holds(x, z, radius, level))
                            return new Pool[] {new Pool(x, z, radius, level)};
                    }
                    return NONE;
                });
    }

    /** Whether the patch around a pool holds its water (see the class comment). */
    private boolean holds(int cx, int cz, double radius, int level) {
        TerrainMap.Sample s = patch.get();
        int reach = (int) Math.ceil(radius + RING), from = level - DEPTH - 1, to = level + 1;
        int water = 0;
        // The ring first: a candidate fails there most often, and any failing column ends it.
        for (int pass = 0; pass < 2; pass++)
            for (int dz = -reach; dz <= reach; dz++)
                for (int dx = -reach; dx <= reach; dx++) {
                    double d = Math.hypot(dx, dz);
                    if (d >= radius + RING || (pass == 0) == (d < radius)) continue;
                    carver.sample(cx + dx, cz + dz, s);
                    CaveMap.Voids v = s.caves;
                    // Entrances arrive with the sample: a pool opens onto no shaft.
                    for (int k = 0; k < v.count(); k++)
                        if (v.lo[k] <= to + AIR_ABOVE && v.hi[k] > from) return false;
                    if ((s.flood.waterTop != Integer.MIN_VALUE && s.flood.waterTop >= from)
                            || (s.water.aquiferShellLo <= to && s.water.aquiferShellHi >= from)
                            || (s.water.aquiferMin <= to + AIR_ABOVE && s.water.aquiferMax >= from)
                            || s.geology.lavaY >= from) return false;
                    carver.voids(cx + dx, cz + dz, s, from, to);
                    int holding = -1;
                    for (int k = 0; k < v.count(); k++) {
                        if (v.lo[k] <= level && v.hi[k] > level) holding = k;
                        else if (v.lo[k] <= level && v.hi[k] > from + 1) return false;
                    }
                    if (holding < 0) continue;
                    int floor = v.lo[holding];
                    if (d < radius) {
                        if (floor <= from) return false;
                        water++;
                    } else if (floor < level - RIM + 1) return false;
                }
        return water >= MINIMUM_WATER;
    }

    /**
     * Adds the pools around a column to its voids: water from the floor up to the level inside a
     * disc, a rim filling the ring's cave up to the level.
     */
    void apply(int x, int z, TerrainMap.Sample s, CaveMap.Voids v) {
        int ci = (int) Math.floor(x / CELL), cj = (int) Math.floor(z / CELL);
        for (int dj = -1; dj <= 1; dj++)
            for (int di = -1; di <= 1; di++)
                for (Pool p : cell(ci + di, cj + dj)) {
                    double d = Math.hypot(x - p.x, z - p.z);
                    if (d >= p.radius + RING) continue;
                    for (int k = 0; k < v.count(); k++) {
                        if (v.lo[k] > p.level || v.hi[k] <= p.level) continue;
                        if (d < p.radius) v.addPool(v.lo[k], p.level + 1);
                        else v.addDecor(v.lo[k], p.level + 1, rim(v.lo[k], s));
                    }
                }
    }

    /** For checks: a cell's pool as {x, z, level}, or null. */
    int[] site(int i, int j) {
        Pool[] p = cell(i, j);
        return p.length == 0 ? null : new int[] {p[0].x, p[0].z, p[0].level};
    }

    /** A rim block: rimstone where dripstone grows, the host rock elsewhere. */
    private static TerrainBlock rim(int y, TerrainMap.Sample s) {
        GeologyMap.Unit unit = s.geology.unit(y - 1);
        if (RealmMap.cell(y, s).dripstone && unit.solubility >= CaveDecor.dripSolubility())
            return TerrainBlock.DRIPSTONE_BLOCK;
        return unit.block;
    }
}
