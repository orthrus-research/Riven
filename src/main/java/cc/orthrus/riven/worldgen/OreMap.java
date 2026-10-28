package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Riven's ore bodies (riven-ores.json): GTCEu's overworld vein catalogue placed by geological
 * origin. Lenses follow their host bed through folds and faults; sheets are planar veins in hard
 * rock; contacts are bands where a host unit meets a partner (skarns at granite, nickel at the base
 * of the gabbro, olivine along its layering); pipes fill kimberlite. Each native chunk column keeps
 * its bodies as Y intervals, and a block inside one is ore by a per-block hash. Every body is a
 * pure function of seed, definitions and coordinates.
 */
public final class OreMap {
    public static final int VERSION = 3;
    private static final Rules RULES = Rules.load("riven-ores.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    private static final int LENS = 0, SHEET = 1, CONTACT = 2, PIPE = 3;
    private static final int PRIMARY = 0, SECONDARY = 1, BETWEEN = 2, SPORADIC = 3;

    /** GTCEu material names, and the vanilla ore each becomes without GTCEu ("NONE" keeps rock). */
    public static final String[] MINERALS, VANILLA;

    /** Per mineral, the Riven block it always is (a seam or rock body), or null for an ore. */
    public static final String[] BLOCK;

    private static final Deposit[] DEPOSITS;
    private static final int CAP;
    private static final double DENSITY_SCALE,
            GAIN_FROM,
            GAIN_TO,
            GAIN,
            SPORADIC_SHARE,
            PRIMARY_ABOVE,
            SECONDARY_BELOW;
    private static final double NODE_CELL, MEGA_CHANCE, NODE_PAD, NODE_SHAPE, NODE_REACH;
    private static final double[] NODE_ORE, MEGA_ORE;

    private static final class Deposit {
        final String name, source;
        final int origin;
        final Set<GeologyMap.Unit> hosts = new HashSet<>(), partners = new HashSet<>();
        final int yLo, yHi;
        final double cell, chance, nodeChance;
        final boolean solid;
        final int maxDepth;
        final double[] radius, thickness, length, height, band, dip, aureole;
        final int[] layers = new int[4];
        final long salt;

        Deposit(JsonObject j, int index, Map<String, Integer> minerals) {
            name = j.get("name").getAsString();
            source = j.get("source").getAsString();
            String o = j.get("origin").getAsString();
            origin =
                    o.equals("lens")
                            ? LENS
                            : o.equals("sheet")
                                    ? SHEET
                                    : o.equals("contact") ? CONTACT : o.equals("pipe") ? PIPE : -1;
            if (origin < 0) throw new IllegalStateException("Origin: " + o);
            units(j.getAsJsonArray("hosts"), hosts);
            if (origin == CONTACT) units(j.getAsJsonArray("partners"), partners);
            double[] y = range(j, "y", -512, 384);
            yLo = (int) y[0];
            yHi = (int) y[1];
            boolean cells = origin != PIPE;
            cell = cells ? Rules.number(j, "cellBlocks", 16, 1024) : 0;
            chance = cells ? Rules.number(j, "chance", 0, 1) : 1;
            solid = j.has("fill") && j.get("fill").getAsString().equals("solid");
            nodeChance = solid ? 1 : Rules.number(j, "nodeChance", 0, 1);
            maxDepth =
                    j.has("maxDepthBlocks")
                            ? (int) Rules.number(j, "maxDepthBlocks", 1, 1024)
                            : 1 << 20;
            // A body must stay inside the 3 x 3 cells a column searches (lobes reach 1.2 radii).
            radius =
                    origin == LENS || origin == CONTACT
                            ? range(j, "radiusBlocks", 2, cell * 0.95 / 1.2)
                            : null;
            thickness =
                    origin == LENS || origin == SHEET ? range(j, "thicknessBlocks", 0.5, 64) : null;
            length = origin == SHEET ? range(j, "lengthBlocks", 2, cell * 0.95) : null;
            height = origin == SHEET ? range(j, "heightBlocks", 2, 512) : null;
            dip = origin == SHEET ? range(j, "dipDegrees", 5, 90) : null;
            band = origin == CONTACT ? range(j, "bandBlocks", 1, 64) : null;
            aureole =
                    origin == CONTACT && j.has("aureoleBlocks")
                            ? range(j, "aureoleBlocks", 1, 64)
                            : null;
            JsonObject l = j.getAsJsonObject("layers");
            String[] keys = {"primary", "secondary", "between", "sporadic"};
            for (int k = 0; k < 4; k++) {
                Integer m = minerals.get(l.get(keys[k]).getAsString());
                if (m == null) throw new IllegalStateException("Mineral: " + l.get(keys[k]));
                layers[k] = m;
            }
            salt = SeededNoise.mix(0x4f5245424f445931L + index * 0x9e3779b97f4a7c15L);
        }
    }

    static {
        JsonObject j = RULES.json;
        CAP = (int) Rules.number(j, "maximumBodies", 1, 256);
        JsonObject r = j.getAsJsonObject("richness");
        DENSITY_SCALE = Rules.number(r, "densityScale", 0, 4);
        JsonObject nodes = j.getAsJsonObject("nodes");
        NODE_CELL = Rules.number(nodes, "cellBlocks", 3, 32);
        NODE_ORE = range(nodes, "orePerNode", 1, 512);
        MEGA_CHANCE = Rules.number(nodes, "megaChance", 0, 1);
        MEGA_ORE = range(nodes, "megaOrePerNode", 1, 512);
        NODE_PAD = Rules.number(nodes, "padBlocks", 0, 8);
        NODE_SHAPE = Rules.number(nodes, "shapeVariation", 0, 0.9);
        // A node must stay within the 3 x 3 x 3 cells a block searches.
        if (radius(MEGA_ORE[1]) * (1 + NODE_SHAPE) * 1.5 > NODE_CELL)
            throw new IllegalStateException("Ore nodes outgrow their cells");
        NODE_REACH = radius(MEGA_ORE[1]) * (1 + NODE_SHAPE) + 1;
        GAIN_FROM = Rules.number(r, "deepGainFromY", -512, 384);
        GAIN_TO = Rules.number(r, "deepGainToY", -512, 384);
        GAIN = Rules.number(r, "deepGain", 0, 8);
        JsonObject lay = j.getAsJsonObject("layering");
        SPORADIC_SHARE = Rules.number(lay, "sporadicShare", 0, 1);
        PRIMARY_ABOVE = Rules.number(lay, "primaryAbove", 0, 1);
        SECONDARY_BELOW = Rules.number(lay, "secondaryBelow", 0, 1);
        Map<String, Integer> index = new LinkedHashMap<>();
        List<String> fallback = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("minerals").entrySet()) {
            index.put(e.getKey(), index.size());
            fallback.add(e.getValue().getAsString());
        }
        MINERALS = index.keySet().toArray(new String[0]);
        VANILLA = fallback.toArray(new String[0]);
        BLOCK = new String[MINERALS.length];
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("blocks").entrySet()) {
            Integer m = index.get(e.getKey());
            if (m == null) throw new IllegalStateException("Block mineral: " + e.getKey());
            BLOCK[m] = e.getValue().getAsString();
        }
        JsonArray d = j.getAsJsonArray("deposits");
        DEPOSITS = new Deposit[d.size()];
        for (int i = 0; i < DEPOSITS.length; i++)
            DEPOSITS[i] = new Deposit(d.get(i).getAsJsonObject(), i, index);
    }

    private static void units(JsonArray names, Set<GeologyMap.Unit> out) {
        for (JsonElement e : names) {
            GeologyMap.Unit u = GeologyMap.named(e.getAsString());
            if (u == null) throw new IllegalStateException("Unit: " + e);
            out.add(u);
        }
    }

    private static double[] range(JsonObject j, String key, double min, double max) {
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(lo >= min && hi >= lo && hi <= max)) throw new IllegalStateException("Range: " + key);
        return new double[] {lo, hi};
    }

    /** Deposit names, as diagnostics report them. */
    public static String deposit(int index) {
        return DEPOSITS[index].name;
    }

    public static int deposits() {
        return DEPOSITS.length;
    }

    public static int maximumBodies() {
        return CAP;
    }

    /** One column's ore bodies: half-open Y intervals [lo, hi); the first containing Y wins. */
    public static final class Bodies {
        final int[] lo = new int[CAP], hi = new int[CAP];
        final short[] deposit = new short[CAP];
        int count;

        public int count() {
            return count;
        }

        public int lo(int i) {
            return lo[i];
        }

        public int hi(int i) {
            return hi[i];
        }

        public int deposit(int i) {
            return deposit[i];
        }

        void add(int a, int b, int d) {
            if (a >= b || count == CAP) return;
            lo[count] = a;
            hi[count] = b;
            deposit[count++] = (short) d;
        }
    }

    /** How far above the surface a bed's own top is sought: more than any unit is thick. */
    private static final int BED_REACH = 512;

    private final long seed;
    private final int minimumY;

    OreMap(long seed, TerrainHeightReference heights) {
        this.seed = seed ^ Rules.seed(RULES.json, "variationSeed");
        minimumY = heights.minimumY;
    }

    /** Collects the column's ore bodies (after {@link TerrainMap#sample}). */
    public void bodies(int x, int z, TerrainMap.Sample s, Bodies out) {
        out.count = 0;
        GeologyMap.Column g = s.geology;
        int surface = s.blockY();
        // The column's rock as unit runs [runLo, runHi), each bed to its own top even where the
        // land has eroded it: a lens keeps its place in its bed and the surface cuts it, where
        // measuring the top bed only to the ground would lay its lens along the ground.
        int ceiling = surface + BED_REACH;
        int[] planes = new int[GeologyMap.MAXIMUM_BOUNDARIES + 2];
        int n = g.boundaries(minimumY, ceiling, planes, 0);
        Arrays.sort(planes, 0, n);
        int[] runLo = new int[n + 1], runHi = new int[n + 1];
        GeologyMap.Unit[] runUnit = new GeologyMap.Unit[n + 1];
        int runs = 0, start = minimumY + 1;
        GeologyMap.Unit current = g.unit(start);
        for (int i = 0; i <= n; i++) {
            int edge = i < n ? planes[i] : ceiling + 1;
            if (edge <= start) continue;
            GeologyMap.Unit next = i < n ? g.unit(edge) : null;
            if (next == current && i < n) continue;
            runLo[runs] = start;
            runHi[runs] = edge;
            runUnit[runs++] = current;
            start = edge;
            current = next;
        }
        for (int d = 0; d < DEPOSITS.length && out.count < CAP; d++) {
            Deposit dep = DEPOSITS[d];
            int lo = Math.max(Math.max(dep.yLo, minimumY + 1), surface - dep.maxDepth),
                    hi = Math.min(dep.yHi + 1, surface + 1);
            if (lo >= hi) continue;
            if (dep.origin == PIPE) {
                for (int r = 0; r < runs; r++)
                    if (dep.hosts.contains(runUnit[r]))
                        out.add(Math.max(lo, runLo[r]), Math.min(hi, runHi[r]), d);
                continue;
            }
            int cx0 = (int) Math.floor(x / dep.cell), cz0 = (int) Math.floor(z / dep.cell);
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    long key = SeededNoise.cell(seed ^ dep.salt, cx0 + dx, cz0 + dz);
                    if (unit(key, 1) >= dep.chance) continue;
                    double ox = x - (cx0 + dx + 0.15 + 0.7 * unit(key, 2)) * dep.cell,
                            oz = z - (cz0 + dz + 0.15 + 0.7 * unit(key, 3)) * dep.cell;
                    if (dep.origin == SHEET)
                        sheet(dep, d, key, ox, oz, lo, hi, runLo, runHi, runUnit, runs, out);
                    else patch(dep, d, key, ox, oz, lo, hi, runLo, runHi, runUnit, runs, out);
                }
            // Skarns also line pluton walls: host rock beside a pluton, below its rim roof.
            if (dep.aureole != null && g.aureole < lerp(dep.aureole, 0.5 + 0.5 * wall(x, z, d))) {
                int top = Math.min(hi, (int) Math.floor(g.aureoleRoof) + 1);
                for (int r = 0; r < runs; r++)
                    if (dep.hosts.contains(runUnit[r]))
                        out.add(Math.max(lo, runLo[r]), Math.min(top, runHi[r]), d);
            }
        }
    }

    /** A slow [-1, 1] wobble of a deposit's aureole width along the pluton wall. */
    private double wall(int x, int z, int d) {
        double a = (x + z * 0.618) / 23.0 + d * 7.1, b = (z - x * 0.618) / 31.0;
        return 0.5 * StrictMath.sin(a) + 0.5 * StrictMath.sin(b + 1.3 * StrictMath.sin(a * 0.7));
    }

    /** A lens or contact patch: a lobed disc around the cell centre. */
    private static void patch(
            Deposit dep,
            int d,
            long key,
            double ox,
            double oz,
            int lo,
            int hi,
            int[] runLo,
            int[] runHi,
            GeologyMap.Unit[] runUnit,
            int runs,
            Bodies out) {
        double theta = StrictMath.atan2(oz, ox);
        double radius =
                lerp(dep.radius, unit(key, 4))
                        * (1 + 0.2 * StrictMath.sin(3 * theta + 6.283 * unit(key, 5)));
        double r2 = (ox * ox + oz * oz) / (radius * radius);
        if (r2 >= 1) return;
        double taper = Math.sqrt(1 - r2);
        if (dep.origin == LENS) {
            double half = lerp(dep.thickness, unit(key, 6)) / 2 * taper,
                    f = 0.15 + 0.7 * unit(key, 7);
            for (int r = 0; r < runs; r++) {
                if (!dep.hosts.contains(runUnit[r])) continue;
                double centre = runLo[r] + f * (runHi[r] - runLo[r]);
                int a = (int) Math.ceil(centre - half), b = (int) Math.floor(centre + half) + 1;
                out.add(
                        Math.max(Math.max(a, runLo[r]), lo),
                        Math.min(Math.min(b, runHi[r]), hi),
                        d);
            }
            return;
        }
        int band = Math.max(1, (int) Math.round(lerp(dep.band, unit(key, 6)) * taper));
        for (int r = 0; r + 1 < runs; r++) {
            GeologyMap.Unit below = runUnit[r], above = runUnit[r + 1];
            if (dep.hosts.contains(below) && dep.partners.contains(above))
                out.add(
                        Math.max(Math.max(runLo[r], runHi[r] - band), lo),
                        Math.min(runHi[r], hi),
                        d);
            if (dep.hosts.contains(above) && dep.partners.contains(below))
                out.add(
                        Math.max(runLo[r + 1], lo),
                        Math.min(Math.min(runHi[r + 1], runLo[r + 1] + band), hi),
                        d);
        }
    }

    /** A planar vein through the cell centre, clipped to host rock: exact Y interval per column. */
    private static void sheet(
            Deposit dep,
            int d,
            long key,
            double ox,
            double oz,
            int lo,
            int hi,
            int[] runLo,
            int[] runHi,
            GeologyMap.Unit[] runUnit,
            int runs,
            Bodies out) {
        double strike = 2 * Math.PI * unit(key, 4),
                dip = Math.toRadians(lerp(dep.dip, unit(key, 5)));
        double along = ox * StrictMath.cos(strike) + oz * StrictMath.sin(strike),
                across = -ox * StrictMath.sin(strike) + oz * StrictMath.cos(strike);
        double length = lerp(dep.length, unit(key, 6));
        if (Math.abs(along) >= length) return;
        double cy = dep.yLo + (dep.yHi - dep.yLo) * unit(key, 7);
        double umax =
                lerp(dep.height, unit(key, 8)) * Math.sqrt(1 - (along / length) * (along / length));
        double half = lerp(dep.thickness, unit(key, 9)) / 2;
        double sin = StrictMath.sin(dip), cos = StrictMath.cos(dip);
        // Across the plane: |across sin + dy cos| < half; within it: |dy sin - across cos| < umax.
        double a = (across * cos - umax) / sin, b = (across * cos + umax) / sin;
        if (cos > 1e-6) {
            double t0 = (-half - across * sin) / cos, t1 = (half - across * sin) / cos;
            a = Math.max(a, Math.min(t0, t1));
            b = Math.min(b, Math.max(t0, t1));
        } else if (Math.abs(across) >= half) return;
        int y0 = (int) Math.ceil(cy + a), y1 = (int) Math.floor(cy + b) + 1;
        y0 = Math.max(y0, lo);
        y1 = Math.min(y1, hi);
        for (int r = 0; r < runs && y0 < y1; r++)
            if (dep.hosts.contains(runUnit[r]))
                out.add(Math.max(y0, runLo[r]), Math.min(y1, runHi[r]), d);
    }

    /** The mineral (index into {@link #MINERALS}) at the block, or -1 where the rock stays. */
    public int mineral(int x, int y, int z, Bodies b) {
        return mineral(x, y, z, b.lo, b.hi, b.deposit, 0, b.count);
    }

    /**
     * As {@link #mineral(int, int, int, Bodies)}, over stored intervals [from, to). Ore comes in
     * nodes: a jittered 3D grid of cells (cellBlocks) where each cell may hold one node of
     * orePerNode blocks (megaOrePerNode at megaChance), a lumpy ellipsoid of one mineral. A node
     * forms in a body when its centre lies inside the body (padded by padBlocks above and below),
     * with the deposit's nodeChance, and keeps all its blocks, so thin bodies hold whole nodes.
     */
    public int mineral(int x, int y, int z, int[] lo, int[] hi, short[] deposit, int from, int to) {
        for (int i = from; i < to; i++) {
            if (y < lo[i] - NODE_PAD - NODE_REACH || y >= hi[i] + NODE_PAD + NODE_REACH) continue;
            Deposit dep = DEPOSITS[deposit[i]];
            if (dep.solid) {
                // A seam or rock body fills its whole interval; sporadic blocks scatter through it.
                if (y < lo[i] || y >= hi[i]) continue;
                long h =
                        SeededNoise.mix(
                                seed
                                        ^ 0x534f4c4944313131L
                                        ^ SeededNoise.mix(x * 0x9e3779b97f4a7c15L)
                                        ^ SeededNoise.mix(y * 0xc2b2ae3d27d4eb4fL)
                                        ^ SeededNoise.mix(z * 0xd1b54a32d192ed03L));
                if (SeededNoise.unit(h) < SPORADIC_SHARE) return dep.layers[SPORADIC];
                double t = (y - lo[i] + 0.5) / (hi[i] - lo[i]);
                return dep.layers[
                        t >= PRIMARY_ABOVE ? PRIMARY : t < SECONDARY_BELOW ? SECONDARY : BETWEEN];
            }
            double gain =
                    GAIN_TO == GAIN_FROM
                            ? 0
                            : GAIN
                                    * Math.max(
                                            0,
                                            Math.min(1, (y - GAIN_FROM) / (GAIN_TO - GAIN_FROM)));
            double chance = Math.min(1, dep.nodeChance * DENSITY_SCALE * (1 + gain));
            int cx = (int) Math.floor(x / NODE_CELL),
                    cy = (int) Math.floor(y / NODE_CELL),
                    cz = (int) Math.floor(z / NODE_CELL);
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        long h =
                                SeededNoise.mix(
                                        seed
                                                ^ SeededNoise.mix((cx + dx) * 0x9e3779b97f4a7c15L)
                                                ^ SeededNoise.mix((cy + dy) * 0xc2b2ae3d27d4eb4fL)
                                                ^ SeededNoise.mix((cz + dz) * 0xd1b54a32d192ed03L));
                        if (unit(h, 1) >= chance) continue;
                        double nx = (cx + dx + unit(h, 2)) * NODE_CELL,
                                ny = (cy + dy + unit(h, 3)) * NODE_CELL,
                                nz = (cz + dz + unit(h, 4)) * NODE_CELL;
                        if (ny < lo[i] - NODE_PAD || ny >= hi[i] + NODE_PAD) continue;
                        double ore =
                                lerp(unit(h, 5) < MEGA_CHANCE ? MEGA_ORE : NODE_ORE, unit(h, 6));
                        // Unit-volume axis scales keep the node's block count while varying its
                        // shape.
                        double sx = 1 + NODE_SHAPE * (2 * unit(h, 7) - 1),
                                sy = 1 + NODE_SHAPE * (2 * unit(h, 8) - 1),
                                sz = 1 + NODE_SHAPE * (2 * unit(h, 9) - 1);
                        double norm = Math.cbrt(sx * sy * sz), r = radius(ore);
                        double ex = (x + 0.5 - nx) * norm / sx,
                                ey = (y + 0.5 - ny) * norm / sy,
                                ez = (z + 0.5 - nz) * norm / sz;
                        if (ex * ex + ey * ey + ez * ez >= r * r) continue;
                        if (unit(h, 10) < SPORADIC_SHARE) return dep.layers[SPORADIC];
                        double t = (ny - lo[i]) / Math.max(1, hi[i] - lo[i]);
                        return dep.layers[
                                t >= PRIMARY_ABOVE
                                        ? PRIMARY
                                        : t < SECONDARY_BELOW ? SECONDARY : BETWEEN];
                    }
        }
        return -1;
    }

    /** The radius of a ball holding about {@code blocks} blocks. */
    private static double radius(double blocks) {
        return Math.cbrt(3 * blocks / (4 * Math.PI));
    }

    private static double unit(long key, int parameter) {
        return SeededNoise.unit(SeededNoise.mix(key + parameter * 0x9e3779b97f4a7c15L));
    }

    private static double lerp(double[] range, double t) {
        return range[0] + (range[1] - range[0]) * t;
    }
}
