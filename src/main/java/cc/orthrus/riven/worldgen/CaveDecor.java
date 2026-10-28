package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Cave decoration (riven-cave-decor.json), keyed by each floor's and ceiling's depth biome
 * (riven-realms.json): formations by setting (calcite dripstone in soluble rock and along faults,
 * ice, salt and basalt where the depth biome names them, more in humid caves), gravel floors and
 * pockets, mud,
 * salt, sand, packed ice, ash and magma floors, litter with mushrooms, hanging roots, Miner's
 * Delight, glowshrooms above the world's lowest third, algae in flooded caves and cobwebs.
 * Placed on each native column's cave floors and ceilings as runs, so column runs stay exact.
 * Every choice is a pure function of seed, definitions and the column.
 */
final class CaveDecor {
    private static final Rules RULES = Rules.load("riven-cave-decor.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;
    static final int CAP;
    private static final double PATCH_WAVELENGTH, CLUSTER_WAVELENGTH;
    private static final double DRIP_WAVELENGTH,
            DRIP_MASK,
            DRIP_SOLUBILITY,
            STALACTITE,
            STALAGMITE,
            DRIP_BLOCK;
    private static final int DRIP_LENGTH;
    private static final double HUMID, HUMID_LOW, HUMID_HIGH, FAULT_REACH;
    // Formation materials, and their stalagmite and stalactite blocks.
    private static final int CALCITE = 1, WATER_ICE = 2, HALITE = 3, BASALT = 4;
    private static final TerrainBlock[]
            UP =
                    {
                        null,
                        TerrainBlock.DRIPSTONE_UP,
                        TerrainBlock.ICE_UP,
                        TerrainBlock.SALT_UP,
                        TerrainBlock.BASALT_UP
                    },
            DOWN =
                    {
                        null,
                        TerrainBlock.DRIPSTONE_DOWN,
                        TerrainBlock.ICE_DOWN,
                        TerrainBlock.SALT_DOWN,
                        TerrainBlock.BASALT_DOWN
                    };
    private static final double GRAVEL_FLOOR, POCKET_TOP, POCKET_CELL, POCKET_CHANCE;
    private static final double[] POCKET_RADIUS;
    private static final double WET_BAND, MUD, ALGAE;
    private static final double LITTER, LITTER_MUSHROOM, ROOT_MAT, MINERS_DELIGHT, MUSHROOM;
    private static final double GLOW_MIN_Y, GLOW_CLUSTER, GLOW_CHANCE;
    private static final double COBWEB, ASH, SAND, ICE;
    private static final int UNSTABLE_THICKNESS, UNSTABLE_DROP, UNSTABLE_HEADROOM;
    private static final int UNSTABLE_LETHAL, UNSTABLE_SEVERE, UNSTABLE_CELL;
    private static final double UNSTABLE_KEEP_LETHAL, UNSTABLE_KEEP_SEVERE;
    private static final double UNSTABLE_WAVELENGTH, UNSTABLE_MASK;
    private static final GeologyMap.Unit EVAPORITE = GeologyMap.named("evaporite"),
            MAGMA = GeologyMap.named("magma"),
            BASALT_ROCK = GeologyMap.named("basalt");

    static {
        JsonObject j = RULES.json;
        CAP = (int) Rules.number(j, "maximumRuns", 4, 256);
        PATCH_WAVELENGTH = Rules.number(j, "patchWavelengthBlocks", 2, 256);
        CLUSTER_WAVELENGTH = Rules.number(j, "clusterWavelengthBlocks", 2, 1024);
        JsonObject d = j.getAsJsonObject("dripstone");
        DRIP_WAVELENGTH = Rules.number(d, "maskWavelengthBlocks", 8, 4096);
        DRIP_MASK = Rules.number(d, "maskThreshold", -1, 1);
        DRIP_SOLUBILITY = Rules.number(d, "minimumSolubility", 0, 1);
        STALACTITE = Rules.number(d, "stalactiteChance", 0, 1);
        STALAGMITE = Rules.number(d, "stalagmiteChance", 0, 1);
        DRIP_LENGTH = (int) Rules.number(d, "maximumLength", 1, 32);
        DRIP_BLOCK = Rules.number(d, "blockThreshold", -1, 1);
        HUMID = Rules.number(d, "humidityReference", 0.01, 1);
        JsonArray humid = d.getAsJsonArray("humidityRange");
        HUMID_LOW = humid.get(0).getAsDouble();
        HUMID_HIGH = humid.get(1).getAsDouble();
        if (!(HUMID_LOW > 0 && HUMID_HIGH >= HUMID_LOW))
            throw new IllegalStateException("Range: humidityRange");
        FAULT_REACH = Rules.number(d, "faultReachBlocks", 0, 256);
        JsonObject g = j.getAsJsonObject("gravel");
        GRAVEL_FLOOR = Rules.number(g, "floorPatchThreshold", -1, 1);
        POCKET_TOP = Rules.number(g, "pocketTopY", -512, 384);
        POCKET_CELL = Rules.number(g, "pocketCellBlocks", 6, 128);
        POCKET_CHANCE = Rules.number(g, "pocketChance", 0, 1);
        JsonArray r = g.getAsJsonArray("pocketRadiusBlocks");
        POCKET_RADIUS = new double[] {r.get(0).getAsDouble(), r.get(1).getAsDouble()};
        if (!(POCKET_RADIUS[0] > 0
                && POCKET_RADIUS[1] >= POCKET_RADIUS[0]
                && POCKET_RADIUS[1] < POCKET_CELL / 2))
            throw new IllegalStateException("Range: pocketRadiusBlocks");
        JsonObject w = j.getAsJsonObject("wet");
        WET_BAND = Rules.number(w, "bandBlocks", 0, 256);
        MUD = Rules.number(w, "mudThreshold", -1, 1);
        ALGAE = Rules.number(w, "algaeChance", 0, 1);
        JsonObject o = j.getAsJsonObject("organics");
        LITTER = Rules.number(o, "litterThreshold", -1, 1);
        LITTER_MUSHROOM = Rules.number(o, "mushroomOnLitterChance", 0, 1);
        ROOT_MAT = Rules.number(o, "rootMatChance", 0, 1);
        MINERS_DELIGHT = Rules.number(o, "minersDelightChance", 0, 1);
        MUSHROOM = Rules.number(o, "mushroomChance", 0, 1);
        JsonObject gl = j.getAsJsonObject("glow");
        GLOW_MIN_Y = Rules.number(gl, "minimumY", -512, 384);
        GLOW_CLUSTER = Rules.number(gl, "clusterThreshold", -1, 1);
        GLOW_CHANCE = Rules.number(gl, "chance", 0, 1);
        COBWEB = Rules.number(j.getAsJsonObject("dry"), "cobwebChance", 0, 1);
        SAND = Rules.number(j.getAsJsonObject("dry"), "sandThreshold", -1, 1);
        ICE = Rules.number(j.getAsJsonObject("frost"), "packedIceThreshold", -1, 1);
        ASH = Rules.number(j.getAsJsonObject("root"), "ashThreshold", -1, 1);
        JsonObject u = j.getAsJsonObject("unstable");
        UNSTABLE_THICKNESS = (int) Rules.number(u, "maximumThickness", 1, 8);
        UNSTABLE_DROP = (int) Rules.number(u, "minimumDropBlocks", 1, 256);
        UNSTABLE_HEADROOM = (int) Rules.number(u, "headroomBlocks", 1, 8);
        UNSTABLE_WAVELENGTH = Rules.number(u, "maskWavelengthBlocks", 4, 4096);
        UNSTABLE_MASK = Rules.number(u, "maskThreshold", -1, 1);
        UNSTABLE_LETHAL = (int) Rules.number(u, "lethalFallBlocks", 4, 256);
        UNSTABLE_SEVERE = (int) Rules.number(u, "severeFallBlocks", UNSTABLE_LETHAL, 512);
        UNSTABLE_KEEP_LETHAL = Rules.number(u, "keepLethal", 0, 1);
        UNSTABLE_KEEP_SEVERE = Rules.number(u, "keepSevere", 0, 1);
        UNSTABLE_CELL = (int) Rules.number(u, "fallCellBlocks", 4, 1024);
    }

    private final long seed;
    private final int minimumY;
    private final SeededNoise patch, cluster, drip, loose;

    CaveDecor(long worldSeed, TerrainHeightReference heights) {
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(RULES.json, "variationSeed"));
        minimumY = heights.minimumY;
        patch = new SeededNoise(SeededNoise.mix(seed ^ 0x5041544348313131L));
        cluster = new SeededNoise(SeededNoise.mix(seed ^ 0x434c555354523131L));
        drip = new SeededNoise(SeededNoise.mix(seed ^ 0x4452495053544f31L));
        loose = new SeededNoise(SeededNoise.mix(seed ^ 0x554e535441424c31L));
    }

    /** The least solubility in which dripstone grows (riven-cave-decor.json dripstone). */
    static double dripSolubility() {
        return DRIP_SOLUBILITY;
    }

    /**
     * Unstable floors' most thickness, least drop below, least headroom above, and the lethal and
     * severe fall heights, in blocks.
     */
    static int[] unstableShape() {
        return new int[] {
            UNSTABLE_THICKNESS, UNSTABLE_DROP, UNSTABLE_HEADROOM, UNSTABLE_LETHAL, UNSTABLE_SEVERE
        };
    }

    /** How near a fault calcite grows where a depth biome allows fault dripstone. */
    static double faultReach() {
        return FAULT_REACH;
    }

    /**
     * The formation material of a floor or ceiling: the depth biome's own (ice, salt in
     * evaporite, basalt), basalt under any basalt roof (lava tubes), calcite in soluble rock where
     * the depth biome allows dripstone and the mask does, or calcite along a fault where it allows
     * fault dripstone; 0 for none.
     */
    private static int formation(
            RealmMap.Depth d, GeologyMap.Unit unit, GeologyMap.Column g, double mask) {
        if (d.formation == RealmMap.Formation.ICE) return WATER_ICE;
        if (d.formation == RealmMap.Formation.SALT) return unit == EVAPORITE ? HALITE : 0;
        if (d.formation == RealmMap.Formation.BASALT || unit == BASALT_ROCK) return BASALT;
        if (d.dripstone && mask > DRIP_MASK && unit.solubility >= DRIP_SOLUBILITY) return CALCITE;
        if (d.faultDripstone && g.faultThrow != 0 && g.faultDistance < FAULT_REACH) return CALCITE;
        return 0;
    }

    private final ThreadLocal<SubsurfaceClimate.Reading> reading =
            ThreadLocal.withInitial(SubsurfaceClimate.Reading::new);

    /** How much more (or less) a void grows formations for its humidity. */
    private double wetness(int lo, int hi, TerrainMap.Sample s) {
        double h = SubsurfaceClimate.at((lo + hi) / 2, s, -1, reading.get()).humidity;
        return Math.max(HUMID_LOW, Math.min(HUMID_HIGH, h / HUMID));
    }

    private static boolean bareStone(GeologyMap.Unit unit) {
        TerrainBlock b = unit.block;
        return b == TerrainBlock.STONE
                || b == TerrainBlock.GRANITE
                || b == TerrainBlock.DIORITE
                || b == TerrainBlock.ANDESITE;
    }

    private static double unit(long key, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(key + parameter * 0x9e3779b97f4a7c15L));
    }

    /**
     * Decorates the column's carved voids (all but the first {@code entrances}, which every
     * sample carries, and any carved void touching one).
     */
    void decorate(
            int x,
            int z,
            TerrainMap.Sample s,
            CaveMap.Voids v,
            int[] entranceLo,
            int[] entranceHi,
            int entrances) {
        GeologyMap.Column g = s.geology;
        int surface = s.blockY();
        double p = patch.sample(x / PATCH_WAVELENGTH, z / PATCH_WAVELENGTH),
                c = cluster.sample(x / CLUSTER_WAVELENGTH, z / CLUSTER_WAVELENGTH),
                m = drip.sample(x / DRIP_WAVELENGTH, z / DRIP_WAVELENGTH);
        long column = SeededNoise.cell(seed, x, z);
        int voids = v.count;
        int[] lo = java.util.Arrays.copyOf(v.lo, voids), hi = java.util.Arrays.copyOf(v.hi, voids);
        boolean[] entrance = new boolean[voids];
        for (int i = 0; i < voids; i++)
            for (int e = 0; e < entrances; e++)
                if (lo[i] < entranceHi[e] && entranceLo[e] < hi[i]) entrance[i] = true;
        // Unstable floors first: they and the ceilings under them stay bare, so the cracks show.
        boolean[] thin = unstable(x, z, s, v, lo, hi, entrance);
        for (int i = 0; i < voids; i++) {
            if (entrance[i] || lo[i] - 1 <= minimumY) continue;
            decorate(
                    lo[i],
                    hi[i],
                    SeededNoise.mix(column + lo[i] * 0xc2b2ae3d27d4eb4fL),
                    p,
                    c,
                    m,
                    g,
                    s,
                    v,
                    i > 0 && thin[i - 1],
                    thin[i]);
        }
        // Pockets fill rock only, and never an unstable floor: one span covers each such floor
        // and the voids it parts.
        int spans = 0;
        int[] spanLo = new int[voids], spanHi = new int[voids];
        for (int i = 0; i < voids; i++)
            if (i > 0 && thin[i - 1]) spanHi[spans - 1] = hi[i];
            else {
                spanLo[spans] = lo[i];
                spanHi[spans++] = hi[i];
            }
        pockets(x, z, g, surface, v, spanLo, spanHi, spans);
    }

    /**
     * Marks the column's thin floors unstable (riven-cave-decor.json unstable): rock at most
     * maximumThickness thick between a void whose top holds minimumDropBlocks of open air and a
     * void whose floor has headroomBlocks of open air, where the mask allows, away from entrances,
     * never where the rock seals flood water or already holds a decoration (a seep or a pool's
     * rim). Every survivable fall stays; a lethal or severe one stays only in some fall cells,
     * decided per cell so a patch keeps or loses its floor together. Returns, per void, whether its
     * ceiling is such a floor.
     */
    private boolean[] unstable(
            int x,
            int z,
            TerrainMap.Sample s,
            CaveMap.Voids v,
            int[] lo,
            int[] hi,
            boolean[] entrance) {
        boolean[] floors = new boolean[lo.length];
        if (lo.length < 2
                || loose.sample(x / UNSTABLE_WAVELENGTH, z / UNSTABLE_WAVELENGTH) <= UNSTABLE_MASK)
            return floors;
        for (int i = 0; i + 1 < lo.length; i++) {
            int from = hi[i], to = lo[i + 1];
            if (to - from > UNSTABLE_THICKNESS || entrance[i] || entrance[i + 1]) continue;
            if (!open(from - UNSTABLE_DROP, from, s, v) || !open(to, to + UNSTABLE_HEADROOM, s, v))
                continue;
            boolean held = false;
            for (int y = from; y < to; y++) held |= s.flood.sealed(y) || v.decor(y) != null;
            if (held) continue;
            if (!keepFall(x, z, from, to, lo[i], s, v)) continue;
            floors[i] = v.addUnstable(from, to);
        }
        return floors;
    }

    /**
     * Whether a floor over [from, to) stays unstable for its fall: from its top to the first block
     * below the open air of the void under it (bottom lo). Water under the air breaks the fall.
     */
    private boolean keepFall(
            int x, int z, int from, int to, int lo, TerrainMap.Sample s, CaveMap.Voids v) {
        int air = 0;
        while (from - 1 - air >= lo && v.fill(from - 1 - air, s) == TerrainBlock.AIR) air++;
        boolean water = from - 1 - air >= lo && v.fill(from - 1 - air, s) == TerrainBlock.WATER;
        int fall = air + to - from;
        if (water || fall < UNSTABLE_LETHAL) return true;
        boolean severe = fall >= UNSTABLE_SEVERE;
        long cell =
                SeededNoise.mix(
                        SeededNoise.cell(
                                        seed,
                                        Math.floorDiv(x, UNSTABLE_CELL),
                                        Math.floorDiv(z, UNSTABLE_CELL))
                                + Math.floorDiv(from, 8) * 0x9e3779b97f4a7c15L);
        return unit(cell, severe ? 15 : 14)
                < (severe ? UNSTABLE_KEEP_SEVERE : UNSTABLE_KEEP_LETHAL);
    }

    /** Whether every block of [from, to) is open air in a void. */
    private static boolean open(int from, int to, TerrainMap.Sample s, CaveMap.Voids v) {
        for (int y = from; y < to; y++)
            if (!v.voids(y) || v.fill(y, s) != TerrainBlock.AIR) return false;
        return true;
    }

    private void decorate(
            int lo,
            int hi,
            long key,
            double p,
            double c,
            double m,
            GeologyMap.Column g,
            TerrainMap.Sample s,
            CaveMap.Voids v,
            boolean bareFloor,
            boolean bareCeiling) {
        TerrainBlock floorFill = v.fill(lo, s), ceilingFill = v.fill(hi - 1, s);
        GeologyMap.Unit floorUnit = g.unit(lo - 1), ceilingUnit = g.unit(hi);
        // A cave's floor and ceiling follow the biome reported where the space they bound lies
        // (the cell rows of its lowest and highest block), so they match the biome a player stands
        // in even where a void cuts down to the formation below; the rock tests read the rock.
        RealmMap.Depth floor = RealmMap.cell(lo, s), ceiling = RealmMap.cell(hi - 1L, s);
        // Nothing decorates lava: a magma chamber's pool is the floor of the room above it.
        boolean floorAir = floorFill == TerrainBlock.AIR && floorUnit != MAGMA,
                ceilingAir = ceilingFill == TerrainBlock.AIR && ceilingUnit != MAGMA;
        int height = hi - lo, below = 0, above = 0;
        TerrainBlock floorBlock = null, ceilingBlock = null;

        // Formations by setting, more of them in humid caves.
        int roof = ceilingAir ? formation(ceiling, ceilingUnit, g, m) : 0,
                base = floorAir ? formation(floor, floorUnit, g, m) : 0;
        if (roof != 0 || base != 0) {
            double wet = wetness(lo, hi, s);
            int up = 0, down = 0;
            if (roof != 0) {
                if (roof == CALCITE && m > DRIP_BLOCK && !bareCeiling)
                    ceilingBlock = TerrainBlock.DRIPSTONE_BLOCK;
                if (unit(key, 1) < STALACTITE * wet) down = 1 + (int) (unit(key, 2) * DRIP_LENGTH);
            }
            if (base != 0) {
                if (base == CALCITE && m > DRIP_BLOCK && !bareFloor)
                    floorBlock = TerrainBlock.DRIPSTONE_BLOCK;
                if (unit(key, 3) < STALAGMITE * wet) up = 1 + (int) (unit(key, 4) * DRIP_LENGTH);
            }
            // Never longer together than the void is tall; filling it exactly merges the tips.
            if (up + down > height) {
                up = up * height / (up + down);
                down = height - up;
            }
            if (down > 0) v.addDecor(hi - down, hi, DOWN[roof]);
            if (up > 0) v.addDecor(lo, lo + up, UP[base]);
            above = down;
            below = up;
        }

        if (floorAir && floorBlock == null && !bareFloor) {
            if (floor.floor == RealmMap.Floor.MAGMA) floorBlock = TerrainBlock.MAGMA;
            else if (floor.floor == RealmMap.Floor.ASH)
                floorBlock = p > ASH ? TerrainBlock.ASH : p < -ASH ? TerrainBlock.GRAVEL : null;
            else if (floor.floor == RealmMap.Floor.GRAVEL)
                floorBlock = p > GRAVEL_FLOOR ? TerrainBlock.GRAVEL : null;
            else if (floorUnit == EVAPORITE) floorBlock = TerrainBlock.WHITE_SAND;
            else if (floor.mud && lo - g.waterTable <= WET_BAND && p > MUD)
                floorBlock = TerrainBlock.MUD;
            else if (floor.floor == RealmMap.Floor.LITTER && c > LITTER) {
                floorBlock = TerrainBlock.DIRT;
                if (floor.life
                        && below == 0
                        && height - above > 0
                        && unit(key, 5) < LITTER_MUSHROOM) {
                    v.addDecor(
                            lo,
                            lo + 1,
                            unit(key, 6) < 0.5 ? TerrainBlock.TOADSTOOL : TerrainBlock.PORTOBELLO);
                    below = 1;
                }
            } else if (floor.floor == RealmMap.Floor.SAND && p > SAND)
                floorBlock = TerrainBlock.SAND;
            else if (floor.floor == RealmMap.Floor.SALT && p > SAND)
                floorBlock = TerrainBlock.WHITE_SAND;
            else if (floor.floor == RealmMap.Floor.ICE && p > ICE)
                floorBlock = TerrainBlock.PACKED_ICE;
        }
        // Plants on bare floors, where the depth biome holds life.
        if (floorAir && below == 0 && height - above > 0 && floor.life) {
            TerrainBlock plant = null;
            if (lo > GLOW_MIN_Y && c > GLOW_CLUSTER && unit(key, 7) < GLOW_CHANCE)
                plant = TerrainBlock.GLOWSHROOM;
            else if (floorBlock == null && bareStone(floorUnit) && unit(key, 8) < MINERS_DELIGHT)
                plant = TerrainBlock.MINERS_DELIGHT;
            else if (unit(key, 9) < MUSHROOM)
                plant =
                        unit(key, 10) < 0.5
                                ? TerrainBlock.BROWN_MUSHROOM
                                : TerrainBlock.RED_MUSHROOM;
            if (plant != null) {
                v.addDecor(lo, lo + 1, plant);
                below = 1;
            }
        }
        if (floorFill == TerrainBlock.WATER && floor.life && unit(key, 11) < ALGAE) {
            v.addDecor(lo, lo + 1, TerrainBlock.ALGAE);
            below = 1;
        }
        // Ceilings: root mats under living ground, cobwebs in dry caves.
        if (ceilingAir && above == 0 && height - below > 0 && ceilingBlock == null) {
            if (ceiling.ceiling == RealmMap.Ceiling.ROOTS
                    && !bareCeiling
                    && unit(key, 12) < ROOT_MAT) {
                ceilingBlock = TerrainBlock.DIRT;
                v.addDecor(hi - 1, hi, TerrainBlock.ROOT);
            } else if (ceiling.ceiling == RealmMap.Ceiling.COBWEBS && unit(key, 13) < COBWEB)
                v.addDecor(hi - 1, hi, TerrainBlock.COBWEB);
        }
        if (floorBlock != null) v.addDecor(lo - 1, lo, floorBlock);
        if (ceilingBlock != null) v.addDecor(hi, hi + 1, ceilingBlock);
    }

    /** Gravel pockets in the rock below pocketTopY: balls on a jittered grid, never in a void. */
    private void pockets(
            int x,
            int z,
            GeologyMap.Column g,
            int surface,
            CaveMap.Voids v,
            int[] lo,
            int[] hi,
            int voids) {
        int top = (int) Math.min(POCKET_TOP, surface - 4);
        if (top <= minimumY + 1) return;
        int cx0 = (int) Math.floor(x / POCKET_CELL), cz0 = (int) Math.floor(z / POCKET_CELL);
        int cy0 = (int) Math.floor(minimumY / POCKET_CELL),
                cy1 = (int) Math.floor(top / POCKET_CELL);
        for (int cy = cy0; cy <= cy1; cy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    long h =
                            SeededNoise.mix(
                                    seed
                                            ^ 0x504f434b45543131L
                                            ^ SeededNoise.mix((cx0 + dx) * 0x9e3779b97f4a7c15L)
                                            ^ SeededNoise.mix(cy * 0xc2b2ae3d27d4eb4fL)
                                            ^ SeededNoise.mix((cz0 + dz) * 0xd1b54a32d192ed03L));
                    if (unit(h, 1) >= POCKET_CHANCE) continue;
                    double nx = (cx0 + dx + unit(h, 2)) * POCKET_CELL,
                            ny = (cy + unit(h, 3)) * POCKET_CELL,
                            nz = (cz0 + dz + unit(h, 4)) * POCKET_CELL;
                    double r =
                            POCKET_RADIUS[0] + (POCKET_RADIUS[1] - POCKET_RADIUS[0]) * unit(h, 5);
                    double d2 = (x + 0.5 - nx) * (x + 0.5 - nx) + (z + 0.5 - nz) * (z + 0.5 - nz);
                    if (d2 >= r * r || g.unit((long) Math.floor(ny)) == MAGMA) continue;
                    double half = Math.sqrt(r * r - d2);
                    int a = Math.max(minimumY + 1, (int) Math.ceil(ny - half - 0.5)),
                            b = Math.min(top + 1, (int) Math.floor(ny + half - 0.5) + 1);
                    // Split around voids: a pocket fills rock only.
                    for (int i = 0; i < voids && a < b; i++) {
                        if (hi[i] <= a || lo[i] >= b) continue;
                        if (lo[i] > a) v.addDecor(a, lo[i], TerrainBlock.GRAVEL);
                        a = Math.max(a, hi[i]);
                    }
                    if (a < b) v.addDecor(a, b, TerrainBlock.GRAVEL);
                }
    }
}
