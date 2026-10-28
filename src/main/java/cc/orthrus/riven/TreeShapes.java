package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.SeededNoise;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.BlockLog;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tree species and their shapes (riven-trees.json species). A species names its wood (the log and
 * leaves roles of riven-mappings.json), the ground it grows on and one shape family with that
 * family's parameters; a tree's blocks are a pure function of its species and its id. Crowns are
 * leaf clusters around logs (trunk tops, branch and limb ends), sized so every leaf lies within four
 * steps of a log: once a player cuts a tree its leaves decay as vanilla leaves do.
 */
final class TreeShapes {
    /** What a shape block is: leaves, a log, or a root log kept only where it stands on ground. */
    static final int LEAVES = 0, LOG = 1, ROOT = 2;

    private static final int AXIS_Y = 0, AXIS_X = 1, AXIS_Z = 2, AXIS_NONE = 3;

    /** Sixteen compass directions, counter-clockwise from east. */
    private static final double[] DIRECTION_X = new double[16], DIRECTION_Z = new double[16];

    /** The eight neighbour steps, cardinal and diagonal alternating, from east. */
    private static final int[][] STEPS = {
        {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    static {
        for (int i = 0; i < 16; i++) {
            DIRECTION_X[i] = StrictMath.cos(i * Math.PI / 8);
            DIRECTION_Z[i] = StrictMath.sin(i * Math.PI / 8);
        }
    }

    enum Family {
        BROADLEAF,
        CONIFER,
        PALM,
        UMBRELLA
    }

    private TreeShapes() {}

    /** Every species of riven-trees.json, in definition order, with its roles resolved. */
    static Map<String, Species> species(JsonObject definitions, Mappings.Snapshot snapshot) {
        Map<String, Species> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : definitions.getAsJsonObject("species").entrySet())
            out.put(e.getKey(), new Species(e.getKey(), e.getValue().getAsJsonObject(), snapshot));
        return out;
    }

    static final class Species {
        final String id, wood;
        final Family family;
        final IBlockState leaves;
        private final IBlockState[] logs = new IBlockState[4];
        final boolean onGrass, onDirt, onSand, onSnow;

        /** Trunk width (1, or 2 for a 2 x 2 trunk from the site's corner). */
        final int width;

        final int[] height;
        final double density;
        // Broadleaf: crown cluster at the trunk top, branches with a cluster each, hanging strands.
        private final double[] crownRadius, branchLength, branchCluster;
        private final double crownDepth, branchStart, branchRise;
        private final int[] branches, droop;
        // Roots: buttresses up the trunk's sides, or prop roots arching down from it.
        private final int rootCount, rootReach, rootHeight;
        private final boolean propRoots;
        // Conifer: rows of discs from the crown start to above the trunk top.
        private final double crownStart;
        private final double[] radius;
        private final String profile;
        private final int whorl, topRows;
        // Palm: a leaning trunk and fronds from its top.
        private final double[] lean;
        private final int fronds, frondDroop;
        private final int[] frondLength;
        // Umbrella: limbs from the trunk top, each under a flat cluster.
        private final int[] limbs;
        private final double[] limbLength, disc;
        private final double limbRise, discDepth;

        /** Bound on any block's horizontal distance from the trunk corner. */
        final int reach;

        /** Sample trees' mean leaf footprint (columns) and mean crown bottom and top over base. */
        final double footprint;

        final int crownBottom, crownTop;

        Species(String id, JsonObject j, Mappings.Snapshot snapshot) {
            this.id = id;
            wood = j.get("wood").getAsString();
            family =
                    Family.valueOf(
                            j.get("family").getAsString().toUpperCase(java.util.Locale.ROOT));
            // Materials are roles, resolved through the world's mappings.
            IBlockState log = snapshot.role("wood." + wood + ".log");
            leaves = snapshot.role("wood." + wood + ".leaves");
            logs[AXIS_Y] = axis(log, BlockLog.EnumAxis.Y);
            logs[AXIS_X] = axis(log, BlockLog.EnumAxis.X);
            logs[AXIS_Z] = axis(log, BlockLog.EnumAxis.Z);
            logs[AXIS_NONE] = axis(log, BlockLog.EnumAxis.NONE);
            boolean grass = false, dirt = false, sand = false, snow = false;
            JsonArray ground =
                    j.has("ground") ? j.getAsJsonArray("ground") : array("grass", "dirt");
            for (JsonElement g : ground)
                switch (g.getAsString()) {
                    case "grass":
                        grass = true;
                        break;
                    case "dirt":
                        dirt = true;
                        break;
                    case "sand":
                        sand = true;
                        break;
                    case "snow":
                        snow = true;
                        break;
                    default:
                        throw new IllegalStateException("Unknown tree ground " + g + ": " + id);
                }
            onGrass = grass;
            onDirt = dirt;
            onSand = sand;
            onSnow = snow;
            width = j.has("width") ? (int) Rules.number(j, "width", 1, 2) : 1;
            height = integers(j, "heightBlocks", 1, 40);
            density = j.has("density") ? Rules.number(j, "density", 0, 1) : 0.7;
            JsonObject none = new JsonObject();
            JsonObject crown = j.has("crown") ? j.getAsJsonObject("crown") : none;
            JsonObject branch = j.has("branches") ? j.getAsJsonObject("branches") : none;
            JsonObject root = j.has("roots") ? j.getAsJsonObject("roots") : none;
            JsonObject frond = j.has("fronds") ? j.getAsJsonObject("fronds") : none;
            JsonObject limb = j.has("limbs") ? j.getAsJsonObject("limbs") : none;
            boolean broadleaf = family == Family.BROADLEAF, conifer = family == Family.CONIFER;
            boolean palm = family == Family.PALM, umbrella = family == Family.UMBRELLA;
            crownRadius = broadleaf ? range(crown, "radius", 0.5, 3.4) : null;
            crownDepth = broadleaf ? Rules.number(crown, "depth", 0.5, 3) : 0;
            branches = broadleaf && branch.has("count") ? integers(branch, "count", 0, 8) : null;
            branchStart = branches != null ? Rules.number(branch, "start", 0, 1) : 0;
            branchLength = branches != null ? range(branch, "length", 1, 8) : null;
            branchRise = branches != null ? Rules.number(branch, "rise", -1, 3) : 0;
            branchCluster = branches != null ? range(branch, "cluster", 0.5, 3.4) : null;
            droop = broadleaf && j.has("droop") ? integers(j, "droop", 0, 6) : null;
            rootCount = j.has("roots") ? (int) Rules.number(root, "count", 1, 4) : 0;
            rootReach = rootCount > 0 ? (int) Rules.number(root, "reach", 1, 3) : 0;
            rootHeight = rootCount > 0 ? (int) Rules.number(root, "height", 1, 6) : 0;
            propRoots = rootCount > 0 && root.get("style").getAsString().equals("prop");
            crownStart = conifer ? Rules.number(crown, "start", 0, 1) : 0;
            radius = conifer ? range(crown, "radius", 0.5, 3.4) : null;
            profile = conifer ? crown.get("profile").getAsString() : null;
            if (conifer && !Arrays.asList("cone", "round", "column").contains(profile))
                throw new IllegalStateException("Unknown conifer profile " + profile + ": " + id);
            whorl = conifer && crown.has("whorl") ? (int) Rules.number(crown, "whorl", 0, 4) : 0;
            topRows = conifer ? (int) Rules.number(crown, "top", 0, 3) : 0;
            lean = palm ? range(j, "lean", 0, 6) : null;
            fronds = palm ? (int) Rules.number(frond, "count", 4, 8) : 0;
            if (palm && fronds != 4 && fronds != 8)
                throw new IllegalStateException("Range: fronds count of " + id);
            frondLength = palm ? integers(frond, "length", 1, 4) : null;
            frondDroop = palm ? (int) Rules.number(frond, "droop", 0, 3) : 0;
            limbs = umbrella ? integers(limb, "count", 1, 6) : null;
            limbLength = umbrella ? range(limb, "length", 1, 6) : null;
            limbRise = umbrella ? Rules.number(limb, "rise", 0, 3) : 0;
            disc = umbrella ? range(j.getAsJsonObject("disc"), "radius", 0.5, 3.4) : null;
            discDepth = umbrella ? Rules.number(j.getAsJsonObject("disc"), "depth", 0.5, 2) : 0;
            // Leaves within four steps of a log: a cluster's farthest block lies at most
            // sqrt(2 rh^2 + rv^2) steps from its centre log.
            if (broadleaf) {
                decayReach(crownRadius[1], crownDepth, id);
                if (branches != null) decayReach(branchCluster[1], crownDepth, id);
            }
            if (umbrella) decayReach(disc[1], discDepth, id);
            int bound;
            switch (family) {
                case BROADLEAF:
                    bound =
                            (int)
                                    Math.ceil(
                                            Math.max(
                                                    crownRadius[1],
                                                    branches == null
                                                            ? 0
                                                            : branchLength[1]
                                                                    + branchCluster[1]
                                                                    + 1));
                    break;
                case CONIFER:
                    bound = (int) Math.ceil(radius[1] + 0.5);
                    break;
                case PALM:
                    bound = (int) Math.ceil(lean[1]) + frondLength[1] + 1;
                    break;
                default:
                    bound = (int) Math.ceil(limbLength[1] + disc[1] + 1);
            }
            reach = Math.max(bound, rootReach) + width;
            // Sample trees: check the reach bound and measure the crown for the distant canopy.
            Shape s = new Shape();
            double area = 0, bottom = 0, top = 0;
            int samples = 32;
            for (int i = 0; i < samples; i++) {
                shape(this, SeededNoise.mix(0x7a3f1d5bL + i), s);
                if (-s.minX > reach || s.maxX > reach || -s.minZ > reach || s.maxZ > reach)
                    throw new IllegalStateException("Tree shape beyond its reach: " + id);
                int[] lowest = new int[(s.maxX - s.minX + 1) * (s.maxZ - s.minZ + 1)];
                Arrays.fill(lowest, Integer.MAX_VALUE);
                int highest = 0, columns = 0;
                long sum = 0;
                for (int b = 0; b < s.count; b++) {
                    int p = s.blocks[b];
                    if (kind(p) != LEAVES) continue;
                    int c = (dz(p) - s.minZ) * (s.maxX - s.minX + 1) + dx(p) - s.minX;
                    lowest[c] = Math.min(lowest[c], dy(p));
                    highest = Math.max(highest, dy(p) + 1);
                }
                for (int low : lowest)
                    if (low != Integer.MAX_VALUE) {
                        columns++;
                        sum += low;
                    }
                area += columns;
                top += highest;
                bottom += columns == 0 ? highest : (double) sum / columns;
            }
            footprint = area / samples;
            crownTop = (int) Math.round(top / samples);
            crownBottom = Math.min(crownTop - 1, (int) Math.round(bottom / samples));
        }

        /** The log for an axis. */
        IBlockState log(int axis) {
            return logs[axis];
        }

        int height(long id) {
            return height[0] + (int) Math.floor(unit(id, 1) * (height[1] - height[0] + 1));
        }
    }

    private static void decayReach(double horizontal, double vertical, String id) {
        if (2 * horizontal * horizontal + vertical * vertical > 25)
            throw new IllegalStateException("Leaves beyond decay reach of a log: " + id);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IBlockState axis(IBlockState log, BlockLog.EnumAxis axis) {
        for (IProperty<?> p : log.getPropertyKeys())
            if (p.getName().equals("axis") && p.getAllowedValues().contains(axis))
                return log.withProperty((IProperty) p, axis);
        return log;
    }

    private static JsonArray array(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    /** A [min, max] pair, or one number for both. */
    private static double[] range(JsonObject j, String key, double min, double max) {
        JsonElement e = j.get(key);
        if (e == null) throw new IllegalStateException("Missing: " + key);
        double a, b;
        if (e.isJsonArray()) {
            a = e.getAsJsonArray().get(0).getAsDouble();
            b = e.getAsJsonArray().get(1).getAsDouble();
        } else a = b = e.getAsDouble();
        if (!(a >= min && b >= a && b <= max)) throw new IllegalStateException("Range: " + key);
        return new double[] {a, b};
    }

    private static int[] integers(JsonObject j, String key, int min, int max) {
        double[] r = range(j, key, min, max);
        if (r[0] != Math.floor(r[0]) || r[1] != Math.floor(r[1]))
            throw new IllegalStateException("Range: " + key);
        return new int[] {(int) r[0], (int) r[1]};
    }

    // Shape blocks pack an offset from the trunk corner, a kind, a log axis and a root chain.
    static int dx(int p) {
        return (p & 0xff) - 128;
    }

    static int dy(int p) {
        return ((p >>> 8) & 0xff) - 64;
    }

    static int dz(int p) {
        return ((p >>> 16) & 0xff) - 128;
    }

    static int kind(int p) {
        return (p >>> 24) & 3;
    }

    static int axis(int p) {
        return (p >>> 26) & 3;
    }

    /** The root chain of a ROOT block (0 for the first). */
    static int chain(int p) {
        return (p >>> 28) - 1;
    }

    /**
     * One tree's blocks relative to its trunk corner: leaves, then logs, then roots, each in the
     * order the shape drew them, with an index by column that keeps that order.
     */
    static final class Shape {
        int count, chains, minX, maxX, minZ, maxZ, minY, maxY;
        int[] blocks = new int[256];
        final int[] footX = new int[4], footZ = new int[4];
        private int[] logs = new int[64], start = new int[1], order = new int[256];
        private int logCount, rootCount;
        private int[] roots = new int[16];

        private void reset() {
            count = logCount = rootCount = chains = 0;
        }

        private void leaf(int x, int y, int z) {
            if (count == blocks.length) blocks = Arrays.copyOf(blocks, count * 2);
            blocks[count++] = pack(x, y, z, LEAVES, AXIS_Y, -1);
        }

        private void log(int x, int y, int z, int axis) {
            if (logCount == logs.length) logs = Arrays.copyOf(logs, logCount * 2);
            logs[logCount++] = pack(x, y, z, LOG, axis, -1);
        }

        private void root(int x, int y, int z, int axis, int chain) {
            if (rootCount == roots.length) roots = Arrays.copyOf(roots, rootCount * 2);
            roots[rootCount++] = pack(x, y, z, ROOT, axis, chain);
        }

        private static int pack(int x, int y, int z, int kind, int axis, int chain) {
            if (x < -128 || x > 127 || y < -64 || y > 191 || z < -128 || z > 127)
                throw new IllegalStateException("Tree block out of range");
            return (x + 128)
                    | (y + 64) << 8
                    | (z + 128) << 16
                    | kind << 24
                    | axis << 26
                    | (chain + 1) << 28;
        }

        /** Appends logs and roots after the leaves and indexes the blocks by column. */
        private void finish() {
            int need = count + logCount + rootCount;
            if (blocks.length < need) blocks = Arrays.copyOf(blocks, need);
            System.arraycopy(logs, 0, blocks, count, logCount);
            System.arraycopy(roots, 0, blocks, count + logCount, rootCount);
            count = need;
            minX = minZ = minY = Integer.MAX_VALUE;
            maxX = maxZ = maxY = Integer.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                int p = blocks[i];
                minX = Math.min(minX, dx(p));
                maxX = Math.max(maxX, dx(p));
                minZ = Math.min(minZ, dz(p));
                maxZ = Math.max(maxZ, dz(p));
                minY = Math.min(minY, dy(p));
                maxY = Math.max(maxY, dy(p));
            }
            int width = maxX - minX + 1, columns = width * (maxZ - minZ + 1);
            if (start.length < columns + 1) start = new int[columns + 1];
            Arrays.fill(start, 0, columns + 1, 0);
            for (int i = 0; i < count; i++) start[column(blocks[i], width) + 1]++;
            for (int c = 0; c < columns; c++) start[c + 1] += start[c];
            if (order.length < count) order = new int[count];
            int[] next = Arrays.copyOf(start, columns);
            for (int i = 0; i < count; i++) order[next[column(blocks[i], width)]++] = blocks[i];
        }

        private int column(int p, int width) {
            return (dz(p) - minZ) * width + dx(p) - minX;
        }

        /** The blocks of column (dx, dz) in drawing order: order[from .. to). */
        int from(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return 0;
            return start[(z - minZ) * (maxX - minX + 1) + x - minX];
        }

        int to(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return 0;
            return start[(z - minZ) * (maxX - minX + 1) + x - minX + 1];
        }

        int ordered(int i) {
            return order[i];
        }
    }

    /** Draws a species' tree for an id into out (its previous contents are discarded). */
    static void shape(Species s, long id, Shape out) {
        out.reset();
        switch (s.family) {
            case BROADLEAF:
                broadleaf(s, id, out);
                break;
            case CONIFER:
                conifer(s, id, out);
                break;
            case PALM:
                palm(s, id, out);
                break;
            default:
                umbrella(s, id, out);
        }
        if (s.rootCount > 0) roots(s, id, out);
        out.finish();
    }

    private static void broadleaf(Species s, long id, Shape out) {
        int h = s.height(id), w = s.width;
        double c = (w - 1) / 2.0;
        trunk(out, w, h);
        cluster(out, c, h - 1, c, draw(s.crownRadius, id, 2), s.crownDepth, s, id, 3);
        if (s.branches != null) {
            int n = draw(s.branches, id, 4), turn = (int) (unit(id, 5) * 16);
            for (int k = 0; k < n; k++) {
                long b = SeededNoise.mix(id + 0x632be59bd9b4e019L * (k + 1));
                int dir = (turn + k * 16 / n + (int) (unit(b, 1) * 3) - 1) & 15;
                int ys =
                        Math.max(
                                1,
                                Math.min(
                                        h - 1,
                                        (int)
                                                Math.floor(
                                                        h
                                                                * (s.branchStart
                                                                        + (1 - s.branchStart)
                                                                                * unit(b, 2)))));
                double length = draw(s.branchLength, b, 3);
                int sx = side(c, DIRECTION_X[dir]), sz = side(c, DIRECTION_Z[dir]);
                int ex = (int) Math.round(c + DIRECTION_X[dir] * length);
                int ez = (int) Math.round(c + DIRECTION_Z[dir] * length);
                int ey = (int) Math.round(ys + s.branchRise * length);
                line(out, sx, ys, sz, ex, ey, ez);
                double rh =
                        s.branchCluster[0] + unit(b, 4) * (s.branchCluster[1] - s.branchCluster[0]);
                cluster(out, ex, ey, ez, rh, s.crownDepth, s, b, 5);
            }
        }
    }

    private static void conifer(Species s, long id, Shape out) {
        int h = s.height(id), w = s.width;
        double c = (w - 1) / 2.0, r0 = draw(s.radius, id, 2);
        int bottom = Math.max(1, Math.min(h - 1, (int) Math.round(h * s.crownStart)));
        int top = h - 1 + s.topRows, span = Math.max(1, top - bottom);
        for (int y = bottom; y <= top; y++) {
            double f = (y - bottom) / (double) span, r;
            switch (s.profile) {
                case "cone":
                    r = r0 * (1 - f) + 0.5;
                    break;
                case "round":
                    r = r0 * Math.sqrt(Math.max(0, 1 - (2 * f - 1) * (2 * f - 1))) + 0.5;
                    break;
                default:
                    r = r0 * Math.min(1, 3 * (1 - f)) + 0.5;
            }
            if (s.whorl > 0 && (top - y) % s.whorl != 0) r -= 1;
            // Every row beside the trunk rings it, so no bare trunk shows through the crown; above
            // it the leading shoot narrows to a cross (a ring over a 2 x 2 trunk), then a tip.
            double ring = w == 2 ? 1.6 : 1.0, tip = w == 2 ? 0.75 : 0.5;
            if (y < h) r = Math.max(r, ring);
            else r = Math.max(tip, Math.min(r, y == h ? ring : tip));
            disc(out, c, y, c, r, s.density, id, y);
        }
        trunk(out, w, h);
    }

    private static void palm(Species s, long id, Shape out) {
        int h = s.height(id), dir = (int) (unit(id, 3) * 16);
        double lean = draw(s.lean, id, 2);
        int px = 0, pz = 0;
        for (int y = 0; y < h; y++) {
            double f = h > 1 ? y / (double) (h - 1) : 0, offset = lean * f * f;
            int ox = (int) Math.round(DIRECTION_X[dir] * offset);
            int oz = (int) Math.round(DIRECTION_Z[dir] * offset);
            // A step aside keeps the trunk joined face to face.
            if (y > 0 && (ox != px || oz != pz)) out.log(ox, y - 1, oz, AXIS_Y);
            out.log(ox, y, oz, AXIS_Y);
            px = ox;
            pz = oz;
        }
        out.leaf(px, h, pz);
        int length = draw(s.frondLength, id, 4);
        for (int k = 0; k < 8; k++) {
            if (s.fronds == 4 && (k & 1) == 1) continue;
            long f = SeededNoise.mix(id + 0x5851f42d4c957f2dL * (k + 1));
            int l = Math.max(1, length - (unit(f, 1) < 0.3 ? 1 : 0));
            int[] step = STEPS[k];
            boolean diagonal = (k & 1) == 1;
            for (int t = 1; t <= l; t++) {
                // A diagonal frond climbs as a staircase, so every leaf touches the last.
                int fx = diagonal ? step[0] * ((t + 1) / 2) : step[0] * t;
                int fz = diagonal ? step[1] * (t / 2) : step[1] * t;
                int drop = Math.max(0, t - (l - s.frondDroop));
                out.leaf(px + fx, h - drop, pz + fz);
            }
        }
    }

    private static void umbrella(Species s, long id, Shape out) {
        int h = s.height(id), w = s.width;
        double c = (w - 1) / 2.0;
        trunk(out, w, h);
        int n = draw(s.limbs, id, 2), turn = (int) (unit(id, 3) * 16);
        for (int k = 0; k < n; k++) {
            long b = SeededNoise.mix(id + 0x9e3779b97f4a7c15L * (k + 1));
            int dir = (turn + k * 16 / n + (int) (unit(b, 1) * 3) - 1) & 15;
            double length = draw(s.limbLength, b, 2);
            int sx = side(c, DIRECTION_X[dir]), sz = side(c, DIRECTION_Z[dir]);
            int ex = (int) Math.round(c + DIRECTION_X[dir] * length);
            int ez = (int) Math.round(c + DIRECTION_Z[dir] * length);
            int ey = (int) Math.round(h - 1 + s.limbRise * length);
            line(out, sx, h - 1, sz, ex, ey, ez);
            cluster(out, ex, ey, ez, draw(s.disc, b, 3), s.discDepth, s, b, 4);
        }
    }

    /** Buttresses up the trunk's sides, or prop roots arching from it to the ground. */
    private static void roots(Species s, long id, Shape out) {
        int w = s.width, first = (int) (unit(id, 40) * 4);
        double c = (w - 1) / 2.0;
        for (int k = 0; k < s.rootCount; k++) {
            // Buttresses stand on the four sides; prop roots reach out to the corners.
            int[] step = STEPS[(2 * ((first + k) & 3) + (s.propRoots ? 1 : 0)) & 7];
            int sx = side(c, step[0]), sz = side(c, step[1]);
            int chain = out.chains++;
            int fx = sx + step[0] * s.rootReach, fz = sz + step[1] * s.rootReach;
            if (s.propRoots) {
                int from = s.rootHeight, steps = Math.max(s.rootReach, from);
                for (int i = 1; i <= steps; i++) {
                    int x = sx + (int) Math.round(step[0] * s.rootReach * i / (double) steps);
                    int z = sz + (int) Math.round(step[1] * s.rootReach * i / (double) steps);
                    out.root(
                            x,
                            (int) Math.round(from - from * i / (double) steps),
                            z,
                            AXIS_NONE,
                            chain);
                }
            } else
                for (int t = 1; t <= s.rootReach; t++) {
                    int tall =
                            Math.max(
                                    1,
                                    (int)
                                            Math.round(
                                                    s.rootHeight
                                                            * (s.rootReach - t + 1)
                                                            / (double) s.rootReach));
                    for (int y = 0; y < tall; y++)
                        out.root(sx + step[0] * t, y, sz + step[1] * t, AXIS_Y, chain);
                }
            out.footX[chain] = fx;
            out.footZ[chain] = fz;
        }
    }

    private static void trunk(Shape out, int w, int h) {
        for (int y = 0; y < h; y++)
            for (int j = 0; j < w; j++) for (int i = 0; i < w; i++) out.log(i, y, j, AXIS_Y);
    }

    /** The trunk column on the side a direction leaves from. */
    private static int side(double centre, double direction) {
        return (int) Math.round(centre + 0.49 * Math.signum(direction));
    }

    /** Logs from a trunk block (excluded) to an end block, along the line's dominant axis. */
    private static void line(Shape out, int x0, int y0, int z0, int x1, int y1, int z1) {
        int ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
        int steps = Math.max(Math.abs(ax), Math.max(Math.abs(ay), Math.abs(az)));
        int axis =
                Math.abs(ay) >= Math.max(Math.abs(ax), Math.abs(az))
                        ? AXIS_Y
                        : Math.abs(ax) >= Math.abs(az) ? AXIS_X : AXIS_Z;
        for (int i = 1; i <= steps; i++)
            out.log(
                    x0 + (int) Math.round(ax * i / (double) steps),
                    y0 + (int) Math.round(ay * i / (double) steps),
                    z0 + (int) Math.round(az * i / (double) steps),
                    axis);
    }

    /**
     * An ellipsoid of leaves around a centre; edge blocks stand where the tree's hash says (with
     * the species' density), and a weeping species hangs strands from the cluster's rim.
     */
    private static void cluster(
            Shape out,
            double cx,
            int cy,
            double cz,
            double rh,
            double rv,
            Species s,
            long id,
            int salt) {
        int x0 = (int) Math.floor(cx - rh), x1 = (int) Math.ceil(cx + rh);
        int z0 = (int) Math.floor(cz - rh), z1 = (int) Math.ceil(cz + rh), v = (int) Math.ceil(rv);
        for (int dy = -v; dy <= v; dy++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    double q = ((x - cx) * (x - cx) + (z - cz) * (z - cz)) / (rh * rh);
                    double d = q + dy * dy / (rv * rv);
                    if (d > 1) continue;
                    if (d > 0.55 && edge(id, salt, x, cy + dy, z) >= s.density) continue;
                    out.leaf(x, cy + dy, z);
                }
        if (s.droop == null) return;
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                double q = ((x - cx) * (x - cx) + (z - cz) * (z - cz)) / (rh * rh);
                if (q < 0.45 || q > 1) continue;
                int lowest = cy - (int) Math.floor(rv * Math.sqrt(1 - q));
                int length = (int) Math.floor(edge(id, salt + 1, x, 0, z) * (s.droop[1] + 1));
                length = Math.max(length, s.droop[0]);
                for (int y = lowest - 1; y >= lowest - length; y--) out.leaf(x, y, z);
            }
    }

    /** A horizontal disc of leaves; edge blocks stand where the tree's hash says. */
    private static void disc(
            Shape out, double cx, int y, double cz, double r, double density, long id, int salt) {
        if (r < 0.5) return;
        int x0 = (int) Math.floor(cx - r), x1 = (int) Math.ceil(cx + r);
        int z0 = (int) Math.floor(cz - r), z1 = (int) Math.ceil(cz + r);
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                double d = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (d > r * r) continue;
                if (d > (r - 1) * (r - 1) && r > 1 && edge(id, salt, x, y, z) >= density) continue;
                out.leaf(x, y, z);
            }
    }

    private static double edge(long id, int salt, int x, int y, int z) {
        return unit(
                id
                        ^ (x * 0x9e3779b97f4a7c15L)
                        ^ (y * 0x165667b19e3779f9L)
                        ^ (z * 0xc2b2ae3d27d4eb4fL),
                salt + 100);
    }

    private static double draw(double[] range, long id, int salt) {
        return range[0] + unit(id, salt) * (range[1] - range[0]);
    }

    private static int draw(int[] range, long id, int salt) {
        return range[0] + (int) Math.floor(unit(id, salt) * (range[1] - range[0] + 1));
    }

    static double unit(long id, long salt) {
        return SeededNoise.unit(SeededNoise.mix(id ^ salt * 0x9e3779b97f4a7c15L));
    }
}
