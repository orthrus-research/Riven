package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.coast.SedimentaryCoasts;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The coast plan's sediment (riven-coasts.json sediment): each beach segment's bench along its
 * shore, a spit off a beach's end beside rock, and a tombolo tying a beach to a stack or islet,
 * from the MIT sedimentary kernels; and a marsh's tidal creeks and the mud over its flat, Riven's
 * own. A segment places its sediment once, against the ground the coast's earlier stages made, so
 * none needs a certificate: a bench follows the segment's own snapped shore, a spit or tombolo
 * stands only where every node of its line lies on shallow, unreserved ground no higher than a
 * beach, and a creek grows only through the marsh's flat, between banks that hold the sea's water.
 */
public final class CoastSediment {
    /** A sediment form: the kernel family of its name, or one of Riven's own with its salt. */
    public enum Form {
        BEACH_BENCH(SedimentaryCoasts.Family.BEACH_BENCH),
        SPIT(SedimentaryCoasts.Family.SPIT),
        TOMBOLO(SedimentaryCoasts.Family.TOMBOLO),
        /** A marsh's mud over its flat: no placement, a cover each column decides. */
        MUD_FLAT(0x4d5544464c415431L),
        TIDAL_CREEK(0x544944414c435231L);

        /** The kernel family, or null for Riven's own forms. */
        final SedimentaryCoasts.Family family;

        final long salt;

        Form(SedimentaryCoasts.Family family) {
            this.family = family;
            salt = family.salt;
        }

        Form(long salt) {
            family = null;
            this.salt = salt;
        }
    }

    /** Complete terrain before the sediment, coast forms included; it never asks for sediment. */
    @FunctionalInterface
    interface Parent {
        void sample(double x, double z, TerrainMap.Sample out);
    }

    private static final Rules RULES = Rules.load("riven-coasts.json", 1);
    private static final Placed[] NONE = new Placed[0];

    /** How many coast cells from its segment's cell a sediment form may reach. */
    private static final int SEARCH = 2;

    /** The most guide points a stretch walks each way from its site (a plan holds 64). */
    private static final int MAXIMUM_STEPS = 31;

    /** How far inland of the shore line a spit or tombolo joins the land, in blocks. */
    private static final double ATTACHMENT = 2;

    /** The directions an islet's surrounding sea is probed in. */
    private static final int ISLET_PROBES = 12;

    /** The steps a creek's branch takes leaving its channel before it must keep a bank clear. */
    private static final int JUNCTION_STEPS = 3;

    /** One placed sediment form: its kernel plan or its creek, and the box it edits. */
    private static final class Placed {
        final Form form;
        final long id;
        final SedimentaryCoasts.Plan plan;
        final TidalCreek creek;
        final double minX, minZ, maxX, maxZ;

        Placed(Form form, long id, SedimentaryCoasts.Plan plan) {
            this.form = form;
            this.id = id;
            this.plan = plan;
            creek = null;
            minX = plan.minX;
            minZ = plan.minZ;
            maxX = plan.maxX;
            maxZ = plan.maxZ;
        }

        Placed(TidalCreek creek) {
            form = Form.TIDAL_CREEK;
            id = creek.id;
            plan = null;
            this.creek = creek;
            minX = creek.minX;
            minZ = creek.minZ;
            maxX = creek.maxX;
            maxZ = creek.maxZ;
        }

        boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /**
     * A segment's own stretch of shore: snapped points with land on the left of their order, the
     * index range the segment owns, and whether the point past each end belongs to a rocky type.
     */
    private static final class Stretch {
        final double[] x, z, nx, nz;
        final int first, last;
        final boolean rockBefore, rockAfter;

        Stretch(List<double[]> points, int first, int last, boolean rockBefore, boolean rockAfter) {
            int n = points.size();
            x = new double[n];
            z = new double[n];
            nx = new double[n];
            nz = new double[n];
            for (int i = 0; i < n; i++) {
                double[] p = points.get(i);
                x[i] = p[0];
                z[i] = p[1];
                nx[i] = p[2];
                nz[i] = p[3];
            }
            this.first = first;
            this.last = last;
            this.rockBefore = rockBefore;
            this.rockAfter = rockAfter;
        }
    }

    /**
     * A column's sediment: its height, the edit that made it (a deposit's weight; a creek's cut
     * lays nothing, so its weight is 0), whether sand rose from the sea and whether mud covers it.
     */
    public static final class Result implements StageResult {
        public double height, parentHeight, weight;
        public long id;
        public Form family;
        public boolean emergent, mud;
        public String reason;
        private final SedimentaryCoasts.Sample geometry = new SedimentaryCoasts.Sample();
        private final double[] creek = new double[2];

        @Override
        public void clear(double surface) {
            height = parentHeight = surface;
            weight = 0;
            id = 0;
            family = null;
            emergent = mud = false;
            reason = "NO_FORM";
        }
    }

    private final long seed;
    private final Shore shore;
    private final CoastForms rocks;
    private final Parent parent;
    private final SedimentaryCoasts.Reference reference;
    private final SedimentaryCoasts.Tuning tuning;
    private final double guide, spitChance, tomboloSpacing, isletRadius, flatRise, marshShare;
    private final double creekSlot, creekChance, creekStep, wander, maximumTurn, spread;
    private final double branchChance;
    private final double tip, bank;
    private final double[] mouthHalfWidth, mouthDepth, creekLength, branchAngle, branchScale;
    private final int orders, maximumChannels;
    private final double mudWavelength, mudThreshold, mudShore, mudShoreBonus, mudCreek;
    private final double mudCreekBonus;
    private final SeededNoise mud;
    private final int sea, waterTop;
    private final Form[][] sedimentOf;
    private final boolean[] mudFlat;
    private final CellMemo<Placed[]> plans = new CellMemo<>(4096);
    private final ThreadLocal<TerrainMap.Sample> scratch =
            ThreadLocal.withInitial(TerrainMap.Sample::new);
    private final ThreadLocal<Shore.Nearest> nearest = ThreadLocal.withInitial(Shore.Nearest::new);

    CoastSediment(
            long worldSeed,
            TerrainHeightReference heights,
            Shore shore,
            CoastForms rocks,
            Parent parent) {
        this.shore = shore;
        this.rocks = rocks;
        this.parent = parent;
        JsonObject j = RULES.json.getAsJsonObject("sediment");
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        waterTop = heights.waterTopY;
        sea = waterTop + 1;
        TerrainHeightReference.Feature beach = heights.feature("beach"),
                plain = heights.feature("coastal_plain"),
                shoal = heights.feature("shoal");
        double scale = TerrainScale.HORIZONTAL;
        reference =
                new SedimentaryCoasts.Reference(
                        heights.seaLevel,
                        beach.elevationOffset.min,
                        beach.elevationOffset.targetAverage,
                        beach.elevationOffset.max,
                        beach.width.min * scale,
                        beach.width.targetAverage * scale,
                        beach.width.max * scale,
                        beach.relief.targetAverage,
                        plain.width.targetAverage * scale,
                        -shoal.elevationOffset.min);
        JsonObject t = j.getAsJsonObject("tuning");
        tuning =
                new SedimentaryCoasts.Tuning(
                        Rules.number(t, "widthScale", .35, 2),
                        Rules.number(t, "lengthScale", .2, 1.5),
                        Rules.number(t, "offsetScale", .08, .45),
                        Rules.number(t, "hook", .1, .8),
                        Rules.number(t, "roughness", 0, 1),
                        Rules.number(t, "bench", 0, 1));
        guide = Rules.number(j, "guideBlocks", 8, 64);
        spitChance = Rules.number(j, "spitChance", 0, 1);
        tomboloSpacing = Rules.number(j, "tomboloSlotBlocks", 32, 1024);
        isletRadius = Rules.number(j, "isletRadiusBlocks", 8, 256);
        flatRise = Rules.number(j, "marshFlatRiseBlocks", 1, 8);
        marshShare = Rules.number(j, "marshShare", .1, 1);
        JsonObject c = j.getAsJsonObject("creek");
        creekSlot = Rules.number(c, "slotBlocks", 16, 1024);
        creekChance = Rules.number(c, "chance", 0, 1);
        mouthHalfWidth = range(c, "mouthHalfWidthBlocks", 1, 8);
        mouthDepth = range(c, "mouthDepthBlocks", 1, 6);
        creekLength = range(c, "lengthBlocks", 8, 512);
        creekStep = Rules.number(c, "stepBlocks", 1, 8);
        wander = Math.toRadians(Rules.number(c, "wanderDegrees", 0, 90));
        maximumTurn = Math.toRadians(Rules.number(c, "maximumTurnDegrees", 0, 90));
        spread = Math.toRadians(Rules.number(c, "spreadDegrees", 0, 90));
        branchChance = Rules.number(c, "branchChance", 0, 1);
        branchAngle = range(c, "branchAngleDegrees", 10, 90);
        branchScale = range(c, "branchScale", .2, 1);
        orders = (int) Rules.number(c, "orders", 1, 4);
        maximumChannels = (int) Rules.number(c, "maximumChannels", 1, 64);
        tip = Rules.number(c, "tipHalfWidthBlocks", .5, 4);
        bank = Rules.number(c, "bankBlocks", 1, 8);
        if (!(tip <= mouthHalfWidth[0]))
            throw new IllegalStateException("riven-coasts.json sediment creek tipHalfWidthBlocks");
        JsonObject m = j.getAsJsonObject("mud");
        mudWavelength = Rules.number(m, "wavelengthBlocks", 4, 1024);
        mudThreshold = Rules.number(m, "threshold", -1, 3);
        mudShore = Rules.number(m, "shoreBlocks", 1, 256);
        mudShoreBonus = Rules.number(m, "shoreBonus", 0, 2);
        mudCreek = Rules.number(m, "creekBlocks", 1, 64);
        mudCreekBonus = Rules.number(m, "creekBonus", 0, 2);
        mud = new SeededNoise(SeededNoise.mix(seed ^ Form.MUD_FLAT.salt));
        // Names resolve once here; generation reads only the forms they name.
        sedimentOf = new Form[Shore.TYPES.length][];
        mudFlat = new boolean[Shore.TYPES.length];
        for (Shore.Type type : Shore.TYPES) {
            sedimentOf[type.index] = new Form[type.sediment.length];
            for (int f = 0; f < type.sediment.length; f++) {
                Form form = Form.valueOf(type.sediment[f]);
                sedimentOf[type.index][f] = form;
                if (form == Form.MUD_FLAT) mudFlat[type.index] = true;
            }
        }
    }

    private static double unit(long h, int k) {
        return SeededNoise.unit(SeededNoise.mix(h + k * 0x9e3779b97f4a7c15L));
    }

    private static double[] range(JsonObject j, String key, double min, double max) {
        com.google.gson.JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(min <= lo && lo <= hi && hi <= max))
            throw new IllegalStateException("riven-coasts.json sediment " + key);
        return new double[] {lo, hi};
    }

    private static double lerp(double[] range, double t) {
        return range[0] + (range[1] - range[0]) * t;
    }

    /** A segment's sediment, placed once. */
    private Placed[] forms(int i, int j) {
        return plans.get(i, j, () -> place(shore.segmentAt(i, j)));
    }

    private Placed[] place(Shore.Segment s) {
        if (s.type == null || sedimentOf[s.type.index].length == 0) return NONE;
        Stretch stretch = stretch(s);
        if (stretch == null) return NONE;
        List<Placed> out = new ArrayList<>();
        for (Form form : sedimentOf[s.type.index]) {
            long h = SeededNoise.mix(s.key ^ seed ^ form.salt);
            switch (form) {
                case BEACH_BENCH -> bench(stretch, h, out);
                case SPIT -> spits(stretch, h, out);
                case TOMBOLO -> tombolo(stretch, h, out);
                case TIDAL_CREEK -> creeks(s, stretch, h, out);
                case MUD_FLAT -> {}
            }
        }
        return out.toArray(NONE);
    }

    /**
     * Walks a segment's shore from its site both ways, a guide's step at a time along each point's
     * own shore, snapping every step onto the shore line, until the next point belongs to another
     * segment (that point is kept, so neighbouring benches overlap) or the shore is lost.
     */
    private Stretch stretch(Shore.Segment s) {
        // A site lies near its shore, not on it (S3a moves it toward the line).
        double[] start = shore.onShore(s.x, s.z, shore.cellBlocks() / 2);
        if (start == null) return null;
        Shore.Nearest owner = new Shore.Nearest();
        List<double[]> before = new ArrayList<>(), after = new ArrayList<>();
        boolean[] rock = new boolean[2];
        for (int side = 0; side < 2; side++) {
            List<double[]> walk = side == 0 ? before : after;
            double sign = side == 0 ? -1 : 1;
            double[] at = start;
            for (int step = 0; step < MAXIMUM_STEPS; step++) {
                // Land lies on the left of the tangent (nz, -nx).
                double[] next =
                        shore.onShore(
                                at[0] + sign * at[3] * guide, at[1] - sign * at[2] * guide, guide);
                if (next == null || StrictMath.hypot(next[0] - at[0], next[1] - at[1]) > 2 * guide)
                    break;
                walk.add(next);
                shore.nearest(next[0], next[1], owner);
                if (!s.is(owner.a)) {
                    rock[side] =
                            owner.a != null
                                    && owner.a.type != null
                                    && (owner.a.type.kind == Shore.Kind.CLIFF
                                            || owner.a.type.kind == Shore.Kind.ROCK);
                    break;
                }
                at = next;
            }
        }
        List<double[]> points = new ArrayList<>();
        for (int k = before.size() - 1; k >= 0; k--) points.add(before.get(k));
        points.add(start);
        points.addAll(after);
        // The owned range leaves out a walk's last point where it crossed into another segment.
        int first = ownedFrom(points, s, owner, 0), last = ownedFrom(points, s, owner, 1);
        if (last - first < 1) return null;
        return new Stretch(points, first, last, rock[0], rock[1]);
    }

    /** The first (end 0) or last (end 1) point of a walk that its segment owns. */
    private int ownedFrom(List<double[]> points, Shore.Segment s, Shore.Nearest owner, int end) {
        int n = points.size();
        for (int k = 0; k < n; k++) {
            int i = end == 0 ? k : n - 1 - k;
            double[] p = points.get(i);
            shore.nearest(p[0], p[1], owner);
            if (s.is(owner.a)) return i;
        }
        return end == 0 ? n : -1;
    }

    /** A bench along the segment's whole stretch, overlapping its neighbours by a point. */
    private void bench(Stretch s, long h, List<Placed> out) {
        out.add(
                new Placed(
                        Form.BEACH_BENCH,
                        h,
                        SedimentaryCoasts.beach(seed, h, reference, tuning, s.x, s.z)));
    }

    /**
     * A spit off each end of the stretch that meets a rocky segment, by spitChance, rooted at the
     * last point the beach owns and drifting away from the rock.
     */
    private void spits(Stretch s, long h, List<Placed> out) {
        TerrainMap.Sample probe = scratch.get();
        for (int end = 0; end < 2; end++) {
            if (!(end == 0 ? s.rockBefore : s.rockAfter) || unit(h, end) >= spitChance) continue;
            SedimentaryCoasts.Anchor root = attachment(s, end == 0 ? s.first : s.last, probe);
            if (root == null) continue;
            long id = SeededNoise.mix(h + end + 1);
            SedimentaryCoasts.Plan plan =
                    SedimentaryCoasts.spit(seed, id, reference, tuning, root, end == 0 ? 1 : -1);
            if (plan.admission == SedimentaryCoasts.Reason.ACCEPTED && shallow(plan, 1, probe))
                out.add(new Placed(Form.SPIT, id, plan));
        }
    }

    /**
     * At most one tombolo, from the first slot along the stretch that finds a stack (from the
     * coast's forms) or an islet out to sea within the kernel's tie bounds, along a line of
     * shallow, unreserved ground.
     */
    private void tombolo(Stretch s, long h, List<Placed> out) {
        TerrainMap.Sample probe = scratch.get();
        int step = Math.max(1, (int) Math.round(tomboloSpacing / guide));
        for (int i = s.first + step / 2; i <= s.last; i += step) {
            SedimentaryCoasts.Anchor mainland = attachment(s, i, probe);
            if (mainland == null) continue;
            SedimentaryCoasts.Anchor island = island(s, i, probe);
            if (island == null) continue;
            // The kernel's tie bounds, between the two attachments themselves.
            double tie = StrictMath.hypot(island.x - mainland.x, island.z - mainland.z);
            if (tie < reference.widthMean * 2 || tie > reference.plainWidth * 1.5) continue;
            long id = SeededNoise.mix(h + i);
            SedimentaryCoasts.Plan plan =
                    SedimentaryCoasts.tombolo(seed, id, reference, tuning, mainland, island);
            if (plan.admission != SedimentaryCoasts.Reason.ACCEPTED || !shallow(plan, 1, probe))
                continue;
            out.add(new Placed(Form.TOMBOLO, id, plan));
            return;
        }
    }

    /**
     * Where a spit or tombolo joins the land at stretch point i: ATTACHMENT blocks inland of the
     * shore line, so on land, or null where that is sea.
     */
    private SedimentaryCoasts.Anchor attachment(Stretch s, int i, TerrainMap.Sample probe) {
        double x = s.x[i] + s.nx[i] * ATTACHMENT, z = s.z[i] + s.nz[i] * ATTACHMENT;
        parent.sample(x, z, probe);
        if (probe.ocean.marine) return null;
        return new SedimentaryCoasts.Anchor(x, z, probe.height, s.nz[i], -s.nx[i], true);
    }

    /**
     * What a tombolo from stretch point i would tie to: the nearest stack seaward of it, else the
     * first land met seaward across open water if it is an islet, inside the kernel's tie bounds.
     */
    private SedimentaryCoasts.Anchor island(Stretch s, int i, TerrainMap.Sample probe) {
        double x = s.x[i], z = s.z[i], nx = s.nx[i], nz = s.nz[i];
        double nearest = reference.widthMean * 2, farthest = reference.plainWidth * 1.5;
        CoastForms.View stack = null;
        double best = Double.POSITIVE_INFINITY;
        for (CoastForms.View v :
                rocks.placed(x - farthest, z - farthest, x + farthest, z + farthest)) {
            if (v.form != CoastForms.Form.STACK_ARCH || v.arch) continue;
            double dx = v.cx - x, dz = v.cz - z, d = StrictMath.hypot(dx, dz) - v.radius - 1;
            // Out to sea: within 40 degrees of the seaward normal.
            if (d < nearest
                    || d > farthest
                    || -(dx * nx + dz * nz)
                            < Math.cos(Math.toRadians(40)) * StrictMath.hypot(dx, dz)) continue;
            if (d < best) {
                best = d;
                stack = v;
            }
        }
        if (stack != null) {
            double dx = x - stack.cx, dz = z - stack.cz, d = StrictMath.hypot(dx, dz);
            double r = stack.radius + 1;
            return new SedimentaryCoasts.Anchor(
                    stack.cx + dx / d * r,
                    stack.cz + dz / d * r,
                    reference.sea + reference.beachMean,
                    nz,
                    -nx,
                    true);
        }
        for (double d = guide; d <= farthest; d += 4) {
            double ix = x - nx * d, iz = z - nz * d;
            parent.sample(ix, iz, probe);
            if (probe.ocean.marine) continue;
            if (d < nearest) return null;
            double top = probe.height;
            // An islet, not a far shore: open sea all round it within isletRadius.
            for (int k = 0; k < ISLET_PROBES; k++) {
                double a = 2 * Math.PI * k / ISLET_PROBES;
                parent.sample(
                        ix + Math.cos(a) * isletRadius, iz + Math.sin(a) * isletRadius, probe);
                if (!probe.ocean.marine) return null;
            }
            return new SedimentaryCoasts.Anchor(ix, iz, top, nz, -nx, true);
        }
        return null;
    }

    /**
     * Whether every node of a plan from index from on stands on ground a deposit may build on (no
     * deeper than the shoal, no higher than a beach) and its line between them crosses no
     * reserved water, sampled every block.
     */
    private boolean shallow(SedimentaryCoasts.Plan plan, int from, TerrainMap.Sample probe) {
        for (int k = from; k < plan.nodeCount() - 1; k++) {
            double ax = plan.nodeX(k), az = plan.nodeZ(k);
            parent.sample(ax, az, probe);
            if (reserved(probe)
                    || probe.height < reference.sea - reference.shoalDepth
                    || probe.height > reference.sea + reference.beachMax) return false;
            // Reserved water between nodes, at one-block steps: a stream narrower than the node
            // spacing would otherwise cross the form and cut its sand off from land.
            double bx = plan.nodeX(k + 1), bz = plan.nodeZ(k + 1);
            int steps = (int) Math.ceil(StrictMath.hypot(bx - ax, bz - az));
            for (int i = 1; i < steps; i++) {
                double t = i / (double) steps;
                parent.sample(ax + t * (bx - ax), az + t * (bz - az), probe);
                if (reserved(probe)) return false;
            }
        }
        return true;
    }

    /** Water sediment leaves alone: protected or reserved shore, wet or banked columns. */
    private static boolean reserved(TerrainMap.BaseSample s) {
        return s.ocean.protectedWater || s.water.wet || s.water.weight > 0;
    }

    /**
     * A creek from a mouth every creek slot along the stretch, by the creek chance, each keeping a
     * bank clear of the segment's earlier creeks. Mouths keep a point from each end of the owned
     * stretch, where a neighbouring beach's bench reaches.
     */
    private void creeks(Shore.Segment segment, Stretch s, long h, List<Placed> out) {
        TerrainMap.Sample probe = scratch.get();
        List<List<double[]>> taken = new ArrayList<>();
        int step = Math.max(1, (int) Math.round(creekSlot / guide));
        for (int i = s.first + 1 + step / 2; i < s.last; i += step) {
            long id = SeededNoise.mix(h + i);
            if (unit(id, 0) >= creekChance) continue;
            TidalCreek creek = creek(segment, s, i, id, taken, probe);
            if (creek != null) out.add(new Placed(creek));
        }
    }

    /**
     * A creek grown from stretch point i: its first channel from two blocks out to sea inland
     * along the shore's normal, and each branch from a node of the channel it leaves, breadth
     * first; or null where the sea at the mouth is not open or the first channel cannot reach
     * inland past its mouth. Its channels join taken, the channels later ones keep clear of.
     */
    private TidalCreek creek(
            Shore.Segment segment,
            Stretch s,
            int i,
            long id,
            List<List<double[]>> taken,
            TerrainMap.Sample probe) {
        double nx = s.nx[i], nz = s.nz[i];
        parent.sample(s.x[i] - nx * 4, s.z[i] - nz * 4, probe);
        if (!openSea(probe)) return null;
        List<List<double[]>> channels = new ArrayList<>(), avoid = new ArrayList<>(taken);
        // A channel to grow: {x, z, heading, half width, depth, length, order, the index in avoid
        // of the channel it leaves (-1 for the first)}.
        Deque<double[]> queue = new ArrayDeque<>();
        queue.add(
                new double[] {
                    s.x[i] - nx * 2,
                    s.z[i] - nz * 2,
                    Math.atan2(nz, nx),
                    lerp(mouthHalfWidth, unit(id, 1)),
                    lerp(mouthDepth, unit(id, 2)),
                    lerp(creekLength, unit(id, 3)),
                    0,
                    -1
                });
        double inland = Math.atan2(nz, nx);
        while (!queue.isEmpty() && channels.size() < maximumChannels) {
            double[] from = queue.poll();
            List<double[]> nodes =
                    channel(
                            from,
                            segment,
                            inland,
                            avoid,
                            SeededNoise.mix(id + channels.size() + 1),
                            queue,
                            probe);
            // The first channel reaches past the shore's blend, or there is no creek.
            if (channels.isEmpty() && nodes.size() < 4) return null;
            if (nodes.size() >= 2) {
                channels.add(nodes);
                avoid.add(nodes);
            }
        }
        taken.addAll(channels);
        return new TidalCreek(id, channels);
    }

    /**
     * One channel's nodes, a step apart, wandering within the maximum turn of its heading and the
     * spread of the creek's inland direction, and narrowing to the tip half width and a block's
     * depth over its length. It stops before the first node that would leave the marsh's flat,
     * lack a bank on either side or come within a bank of an earlier channel (in its first steps,
     * of any but the channel it leaves); a node may queue a branch, which leaves
     * at the branch angle to either side, scaled down.
     */
    private List<double[]> channel(
            double[] from,
            Shore.Segment segment,
            double inland,
            List<List<double[]>> earlier,
            long h,
            Deque<double[]> queue,
            TerrainMap.Sample probe) {
        double x = from[0], z = from[1], width = from[3], depth = from[4];
        double heading = inland + clamp(from[2] - inland, spread);
        int order = (int) from[6], steps = Math.max(1, (int) Math.round(from[5] / creekStep));
        List<double[]> nodes = new ArrayList<>();
        nodes.add(new double[] {x, z, width, depth});
        double angle = heading;
        for (int k = 1; k <= steps; k++) {
            angle += wander * (2 * unit(h, 5 * k) - 1);
            angle = heading + clamp(angle - heading, maximumTurn);
            angle = inland + clamp(angle - inland, spread);
            double t = (double) k / steps, r = width + (tip - width) * t;
            double d = depth + (1 - depth) * t;
            double px = x + Math.cos(angle) * creekStep, pz = z + Math.sin(angle) * creekStep;
            if (crosses(px, pz, r, earlier, k > JUNCTION_STEPS ? -1 : (int) from[7])) break;
            if (!fits(segment, px, pz, angle, r, probe)) break;
            x = px;
            z = pz;
            nodes.add(new double[] {x, z, r, d});
            if (order + 1 < orders
                    && k >= 2
                    && k < steps - 1
                    && unit(h, 5 * k + 1) < branchChance) {
                double side = unit(h, 5 * k + 2) < .5 ? -1 : 1,
                        scale = lerp(branchScale, unit(h, 5 * k + 3));
                queue.add(
                        new double[] {
                            x,
                            z,
                            angle + side * Math.toRadians(lerp(branchAngle, unit(h, 5 * k + 4))),
                            Math.max(tip, r * scale),
                            Math.max(1, d * scale),
                            (steps - k) * creekStep * scale,
                            order + 1,
                            earlier.size()
                        });
            }
        }
        return nodes;
    }

    /** An angle difference brought into (-pi, pi], then within limit of 0. */
    private static double clamp(double difference, double limit) {
        double d = StrictMath.IEEEremainder(difference, 2 * Math.PI);
        return Math.max(-limit, Math.min(limit, d));
    }

    /**
     * Whether a node of half width r comes within a bank of any earlier channel's node but the
     * one at index leaving (-1 for none), which a branch's first steps leave from.
     */
    private boolean crosses(
            double x, double z, double r, List<List<double[]>> earlier, int leaving) {
        for (int c = 0; c < earlier.size(); c++) {
            if (c == leaving) continue;
            for (double[] n : earlier.get(c))
                if (StrictMath.hypot(x - n[0], z - n[1]) < r + n[2] + bank) return true;
        }
        return false;
    }

    /**
     * Whether a channel node of half width r fits: its column is nearest its own segment (so
     * neighbouring segments' creeks never cross, and no segment's plan asks another's) and lies on
     * the marsh's flat, and the columns a bank's width past either edge are open sea or ground
     * that holds the sea's water.
     */
    private boolean fits(
            Shore.Segment segment,
            double x,
            double z,
            double angle,
            double r,
            TerrainMap.Sample probe) {
        Shore.Nearest owner = nearest.get();
        shore.nearest(x, z, owner);
        if (!segment.is(owner.a)) return false;
        parent.sample(x, z, probe);
        if (!holds(probe) || probe.shore.marsh < marshShare || probe.height > waterTop + flatRise)
            return false;
        double px = -Math.sin(angle) * (r + bank), pz = Math.cos(angle) * (r + bank);
        for (int side = -1; side <= 1; side += 2) {
            parent.sample(x + px * side, z + pz * side, probe);
            if (!openSea(probe) && !holds(probe)) return false;
        }
        return true;
    }

    /** Land a creek may cut or bank on: unreserved, no coast form's, holding the sea's water. */
    private boolean holds(TerrainMap.Sample s) {
        return !s.ocean.marine && !reserved(s) && s.rocky.family == null && s.height >= waterTop;
    }

    /** Open, supplied sea at the water's top that no shore or water reserves. */
    private boolean openSea(TerrainMap.Sample s) {
        return s.ocean.marine && s.ocean.supplied && !reserved(s) && s.waterY() == waterTop;
    }

    /**
     * Places the sediment of every segment near a region ahead of demand, on Cubature's pool, or
     * declines once a quarter of the memo is held. Plans are pure and their memo bounded, so
     * preparing them changes timing only.
     */
    boolean prepareRegion(double minX, double minZ, double maxX, double maxZ, BuildPool pool) {
        int[] held = {0};
        plans.forEachHeld(p -> held[0]++);
        if (held[0] > plans.capacity() / 4) return false;
        double cell = shore.cellBlocks();
        int x0 = (int) Math.floor(minX / cell) - SEARCH,
                x1 = (int) Math.floor(maxX / cell) + SEARCH;
        int z0 = (int) Math.floor(minZ / cell) - SEARCH,
                z1 = (int) Math.floor(maxZ / cell) + SEARCH;
        int nx = x1 - x0 + 1;
        pool.forEach(nx * (z1 - z0 + 1), k -> forms(x0 + k % nx, z0 + k / nx));
        return true;
    }

    /** Whether any sediment form may edit the column: the cheap test before sampling it. */
    public boolean hasGeometry(double x, double z) {
        double cell = shore.cellBlocks();
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        for (int di = -SEARCH; di <= SEARCH; di++)
            for (int dj = -SEARCH; dj <= SEARCH; dj++)
                for (Placed p : forms(ci + di, cj + dj)) if (p.contains(x, z)) return true;
        return false;
    }

    /** A placed sediment form as checks see it: its kind, id and plan or creek nodes. */
    static final class View {
        final Form form;
        final long id;
        final double[] x, z;

        /** A creek's channel count; 0 for the kernel's forms. */
        final int channels;

        View(Placed p) {
            form = p.form;
            id = p.id;
            if (p.creek != null) {
                x = p.creek.x.clone();
                z = p.creek.z.clone();
                channels = p.creek.channels();
                return;
            }
            channels = 0;
            x = new double[p.plan.nodeCount()];
            z = new double[x.length];
            for (int k = 0; k < x.length; k++) {
                x[k] = p.plan.nodeX(k);
                z[k] = p.plan.nodeZ(k);
            }
        }
    }

    /** Every sediment form of the segments of the coast cells overlapping a box, for checks. */
    List<View> placed(double minX, double minZ, double maxX, double maxZ) {
        double cell = shore.cellBlocks();
        List<View> out = new ArrayList<>();
        for (int i = (int) Math.floor(minX / cell); i <= (int) Math.floor(maxX / cell); i++)
            for (int j = (int) Math.floor(minZ / cell); j <= (int) Math.floor(maxZ / cell); j++)
                for (Placed p : forms(i, j)) out.add(new View(p));
        return out;
    }

    /**
     * Applies the sediment to a column after the coast's forms, where no form claimed it: the
     * highest edit of every deposit whose plan reaches it (deposition forms only build; a bench may
     * also cut the low shore, but no channel's levee below its water), then the deepest bed of
     * every creek reaching an unreserved land column, which takes the sea's water as its own. Sand
     * rising from marine columns is emergent. Mud covers a marsh's flat in patches of noise,
     * densest toward the water and along the creeks, and every creek's bed.
     */
    public void apply(double x, double z, TerrainMap.Sample sample) {
        Result out = sample.sediment;
        out.clear(sample.height);
        // Sand never covers a coast form's edit: its notch, cove, stack or arch stays as built.
        if (!Double.isFinite(sample.height) || sample.rocky.family != null) return;
        double cell = shore.cellBlocks(), ground = sample.height, height = Double.NaN;
        double bed = Double.NaN, beyond = Double.POSITIVE_INFINITY;
        long creek = 0;
        boolean marine = sample.ocean.marine, reserved = reserved(sample);
        int ci = (int) Math.floor(x / cell), cj = (int) Math.floor(z / cell);
        SedimentaryCoasts.Sample g = out.geometry;
        double[] c = out.creek;
        for (int di = -SEARCH; di <= SEARCH; di++)
            for (int dj = -SEARCH; dj <= SEARCH; dj++)
                for (Placed p : forms(ci + di, cj + dj)) {
                    if (p.creek != null) {
                        if (marine || reserved || !p.creek.near(x, z, mudCreek)) continue;
                        p.creek.column(x, z, waterTop, c);
                        beyond = Math.min(beyond, c[1]);
                        if (!Double.isNaN(c[0]) && (Double.isNaN(bed) || c[0] < bed)) {
                            bed = c[0];
                            creek = p.id;
                        }
                        continue;
                    }
                    if (!p.contains(x, z)) continue;
                    SedimentaryCoasts.sample(
                            p.plan, x, z, ground, marine, p.form != Form.BEACH_BENCH, reserved, g);
                    if (g.reason != SedimentaryCoasts.Reason.ACCEPTED || g.weight <= 0) continue;
                    double edited = Hydro.holding(g.heightY, sample.water);
                    if (Double.isNaN(height) || edited > height) {
                        height = edited;
                        out.weight = g.weight;
                        out.family = p.form;
                        out.id = p.id;
                    }
                }
        if (out.family != null) {
            out.reason = "ACCEPTED";
            out.height = height;
            out.emergent = marine && height >= sea;
            sample.height = height;
            sample.emergentSediment = out.emergent;
        }
        if (!Double.isNaN(bed) && bed < sample.height) {
            // A creek's cut lays nothing: the sea's water stands in it, as a stream's in its bed,
            // and the marsh keeps its biome.
            out.reason = "ACCEPTED";
            out.height = sample.height = bed;
            out.weight = 0;
            out.family = Form.TIDAL_CREEK;
            out.id = creek;
            out.emergent = sample.emergentSediment = false;
            out.mud = true;
            Hydro.Sample w = sample.water;
            w.wet = w.stream = true;
            w.waterY = waterTop;
            w.floor = bed;
            return;
        }
        if (marine
                || reserved
                || out.weight > 0
                || sample.shore.marsh < marshShare
                || sample.height > waterTop + flatRise
                || sample.shore.type == null
                || !mudFlat[sample.shore.type.index]) return;
        // Two octaves, the finer offset from the coarser's lattice, so patches have ragged edges.
        double fine = mudWavelength * .4;
        double m =
                .65 * mud.sample(x / mudWavelength, z / mudWavelength)
                        + .35 * mud.sample(x / fine + 31.7, z / fine - 17.3)
                        + mudShoreBonus * (1 - Rules.smooth(sample.shore.distance / mudShore))
                        + mudCreekBonus * (1 - Rules.smooth(Math.max(0, beyond) / mudCreek));
        out.mud = m > mudThreshold;
    }
}
