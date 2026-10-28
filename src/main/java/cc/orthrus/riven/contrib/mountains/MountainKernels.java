package cc.orthrus.riven.contrib.mountains;

import java.util.Objects;

/** Independently authored finite mountain kernels. MIT; see contribution LICENSE.
 * No Minecraft APIs, terrain callbacks, mutable shared scratch, or hidden caches.
 * All heights denote highest solid block Y, not the top of that block.
 */
@SuppressWarnings("strictfp") // Retain strict evaluation for the Java 8 fallback.
public final strictfp class MountainKernels {
    private MountainKernels() {}

    public enum Family {
        RIDGE_TREE(0x4D37545245453031L),
        FOLDED_BELTS(0x4D37464F4C443031L),
        SUMMIT_GRAPH(0x4D37475241503031L),
        WARPED_RIDGES(0x4D37574152503031L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public enum Reason {
        ACCEPTED,
        MARINE,
        WATER_RESERVED,
        PROTECTED_SHORE,
        LOW_MOUNTAIN_INTENT,
        LOW_UPLIFT,
        ELEVATION,
        HEADROOM,
        OCCURRENCE,
        OUTSIDE_SUPPORT
    }

    /** Adapter values MUST come from context.terrainHeights and TerrainScale.HORIZONTAL.
     * Width values remain nominal here; multiply by horizontalScale exactly once.
     */
    public static final class Reference {
        public final String fingerprint;
        public final double minY, targetY, maxY, featherY, reliefMin, reliefTarget, reliefMax;
        public final double widthMin, widthTarget, widthMax, spurWidth, spurRelief;
        public final double lowestY, highestY, horizontalScale;

        public Reference(
                String fingerprint,
                double minY,
                double targetY,
                double maxY,
                double featherY,
                double reliefMin,
                double reliefTarget,
                double reliefMax,
                double widthMin,
                double widthTarget,
                double widthMax,
                double spurWidth,
                double spurRelief,
                double lowestY,
                double highestY,
                double horizontalScale) {
            this.fingerprint = Objects.requireNonNull(fingerprint);
            for (double v :
                    new double[] {
                        minY,
                        targetY,
                        maxY,
                        featherY,
                        reliefMin,
                        reliefTarget,
                        reliefMax,
                        widthMin,
                        widthTarget,
                        widthMax,
                        spurWidth,
                        spurRelief,
                        lowestY,
                        highestY,
                        horizontalScale
                    }) finite(v);
            require(lowestY < minY && minY < targetY && targetY < maxY && maxY <= highestY);
            require(featherY > 0 && 2 * featherY < maxY - minY);
            require(0 < reliefMin && reliefMin <= reliefTarget && reliefTarget <= reliefMax);
            require(0 < widthMin && widthMin <= widthTarget && widthTarget <= widthMax);
            require(spurWidth > 0 && spurRelief > 0 && horizontalScale > 0);
            require(
                    widthMin * horizontalScale > 0
                            && Double.isFinite(widthMax * horizontalScale * 3));
            require(
                    spurWidth * horizontalScale > 0
                            && Double.isFinite(spurWidth * horizontalScale));
            this.minY = minY;
            this.targetY = targetY;
            this.maxY = maxY;
            this.featherY = featherY;
            this.reliefMin = reliefMin;
            this.reliefTarget = reliefTarget;
            this.reliefMax = reliefMax;
            this.widthMin = widthMin;
            this.widthTarget = widthTarget;
            this.widthMax = widthMax;
            this.spurWidth = spurWidth;
            this.spurRelief = spurRelief;
            this.lowestY = lowestY;
            this.highestY = highestY;
            this.horizontalScale = horizontalScale;
        }
    }

    /** Scalar controls, validated before planning. No variable runtime octave counts. */
    public static final class Tuning {
        private final Reference reference;
        public final double widthMultiplier, aspect, relief, sharpness, spurs, warp;
        public final double passChance, occurrence, minMountainIntent, minUplift;

        public Tuning(
                Reference r,
                double widthMultiplier,
                double aspect,
                double relief,
                double sharpness,
                double spurs,
                double warp,
                double passChance,
                double occurrence,
                double minMountainIntent,
                double minUplift) {
            reference = Objects.requireNonNull(r);
            for (double v :
                    new double[] {
                        widthMultiplier,
                        aspect,
                        relief,
                        sharpness,
                        spurs,
                        warp,
                        passChance,
                        occurrence,
                        minMountainIntent,
                        minUplift
                    }) finite(v);
            double width = r.widthTarget * widthMultiplier;
            require(width >= r.widthMin && width <= r.widthMax && aspect >= 1.3 && aspect <= 3);
            require(
                    relief >= r.reliefMin
                            && relief <= r.reliefMax
                            && sharpness >= 0.7
                            && sharpness <= 2);
            require(spurs >= 0 && spurs <= 1 && warp >= 0 && warp <= 0.25);
            require(passChance >= 0 && passChance <= 1 && occurrence >= 0 && occurrence <= 1);
            require(
                    minMountainIntent > 0
                            && minMountainIntent < 1
                            && minUplift > 0
                            && minUplift < 1);
            this.widthMultiplier = widthMultiplier;
            this.aspect = aspect;
            this.relief = relief;
            this.sharpness = sharpness;
            this.spurs = spurs;
            this.warp = warp;
            this.passChance = passChance;
            this.occurrence = occurrence;
            this.minMountainIntent = minMountainIntent;
            this.minUplift = minUplift;
        }

        public static Tuning defaults(Reference r) {
            return new Tuning(r, 1, 2.2, r.reliefMax, 1.15, 0.72, 0.12, 0.45, 0.68, 0.55, 0.25);
        }
    }

    /** Earlier immutable admission facts at the anchor, never computed from shaped terrain. */
    public static final class Intent {
        public final boolean marine, waterReserved, protectedShore;
        public final double mountainWeight, uplift, parentY, floorY, ceilingY, direction;

        public Intent(
                boolean marine,
                boolean waterReserved,
                boolean protectedShore,
                double mountainWeight,
                double uplift,
                double parentY,
                double floorY,
                double ceilingY,
                double direction) {
            for (double v :
                    new double[] {mountainWeight, uplift, parentY, floorY, ceilingY, direction})
                finite(v);
            require(mountainWeight >= 0 && mountainWeight <= 1 && uplift >= 0 && uplift <= 1);
            require(floorY <= parentY && parentY <= ceilingY);
            this.marine = marine;
            this.waterReserved = waterReserved;
            this.protectedShore = protectedShore;
            this.mountainWeight = mountainWeight;
            this.uplift = uplift;
            this.parentY = parentY;
            this.floorY = floorY;
            this.ceilingY = ceilingY;
            this.direction = direction;
        }
    }

    /** Immutable geometry. Arrays are private, exclusively owned, never exposed. */
    public static final class Plan {
        public final Family family;
        public final Reason reason;
        public final long id;
        public final double anchorX, anchorZ, halfLength, halfWidth, amplitude, cos, sin;
        public final boolean hasPass;
        public final double passU, passBend, passWidth;
        private final Reference ref;
        private final Tuning tune;
        private final double[] x, z, h;
        private final int[] edgeA, edgeB;

        private Plan(
                Family f,
                Reason why,
                long id,
                double ax,
                double az,
                Reference ref,
                Tuning tune,
                Intent intent) {
            this.family = f;
            this.reason = why;
            this.id = id;
            this.anchorX = ax;
            this.anchorZ = az;
            this.ref = ref;
            this.tune = tune;
            halfWidth = ref.widthTarget * tune.widthMultiplier * ref.horizontalScale / 2;
            halfLength = halfWidth * tune.aspect;
            // Supplied directional field steers a range, seed gives bounded local deviation.
            double angle = intent.direction + (unit(id, 1) - 0.5) * 0.8;
            cos = StrictMath.cos(angle);
            sin = StrictMath.sin(angle);
            amplitude =
                    Math.max(
                            0,
                            Math.min(
                                    tune.relief,
                                    Math.min(ref.maxY, intent.ceilingY) - intent.parentY));
            hasPass = why == Reason.ACCEPTED && unit(id, 2) < tune.passChance;
            passU = (unit(id, 3) - 0.5) * 0.52;
            passBend = (unit(id, 4) - 0.5) * 0.22;
            passWidth = ref.spurWidth * ref.horizontalScale * 0.45;
            int count =
                    why != Reason.ACCEPTED
                            ? 0
                            : f == Family.RIDGE_TREE ? 17 : f == Family.SUMMIT_GRAPH ? 9 : 0;
            x = new double[count];
            z = new double[count];
            h = new double[count];
            int edges =
                    why != Reason.ACCEPTED
                            ? 0
                            : f == Family.RIDGE_TREE ? 16 : f == Family.SUMMIT_GRAPH ? 10 : 0;
            edgeA = new int[edges];
            edgeB = new int[edges];
            if (why != Reason.ACCEPTED) return;
            if (f == Family.RIDGE_TREE) buildTree();
            else if (f == Family.SUMMIT_GRAPH) buildGraph();
        }

        public int nodeCount() {
            return x.length;
        }

        public int edgeCount() {
            return edgeA.length;
        }

        public double nodeX(int n) {
            return anchorX + x[n] * cos - z[n] * sin;
        }

        public double nodeZ(int n) {
            return anchorZ + x[n] * sin + z[n] * cos;
        }

        public int edgeStart(int e) {
            return edgeA[e];
        }

        public int edgeEnd(int e) {
            return edgeB[e];
        }

        public double passLocalU(double v) {
            return passU + passBend * (1 - v * v);
        }

        private void buildTree() {
            for (int i = 0; i < 7; i++) {
                x[i] = (-0.79 + i * 0.2633 + (unit(id, 10 + i) - 0.5) * 0.11) * halfLength;
                z[i] = (unit(id, 20 + i) - 0.5) * halfWidth * 0.31;
                h[i] = 0.66 + 0.34 * unit(id, 40 + i);
                if (i > 0) {
                    edgeA[i - 1] = i - 1;
                    edgeB[i - 1] = i;
                }
            }
            for (int i = 0; i < 10; i++) {
                int root = 1 + (int) (unit(id, 130 + i) * 5), n = 7 + i;
                x[n] = x[root] + (unit(id, 70 + i) - 0.5) * halfLength * 0.35;
                z[n] =
                        (unit(id, 140 + i) < 0.5 ? -1 : 1)
                                * halfWidth
                                * (0.48 + unit(id, 90 + i) * 0.24);
                h[n] = (0.26 + 0.19 * unit(id, 110 + i)) * tune.spurs;
                edgeA[6 + i] = root;
                edgeB[6 + i] = n;
            }
        }

        private void buildGraph() {
            for (int i = 0; i < 9; i++) {
                x[i] = ((i % 3 - 1) * 0.59 + (unit(id, 20 + i) - 0.5) * 0.20) * halfLength;
                z[i] = ((i / 3 - 1) * 0.54 + (unit(id, 40 + i) - 0.5) * 0.20) * halfWidth;
                h[i] = 0.60 + 0.40 * unit(id, 60 + i);
            }
            // Prim on nine authored sites: <= 8*81 pair comparisons; no recursive search.
            boolean[] used = new boolean[9];
            used[4] = true;
            for (int e = 0; e < 8; e++) {
                double best = Double.POSITIVE_INFINITY;
                int a = 0, b = 0;
                for (int i = 0; i < 9; i++)
                    if (used[i])
                        for (int j = 0; j < 9; j++)
                            if (!used[j]) {
                                double cost = sq(x[i] - x[j]) + sq(z[i] - z[j]);
                                if (cost < best) {
                                    best = cost;
                                    a = i;
                                    b = j;
                                }
                            }
                edgeA[e] = a;
                edgeB[e] = b;
                used[b] = true;
            }
            for (int e = 8; e < 10; e++) {
                double best = Double.POSITIVE_INFINITY;
                int a = 0, b = 1;
                for (int i = 0; i < 9; i++)
                    for (int j = i + 1; j < 9; j++) {
                        boolean existing = false;
                        for (int k = 0; k < e; k++)
                            if ((edgeA[k] == i && edgeB[k] == j)
                                    || (edgeA[k] == j && edgeB[k] == i)) existing = true;
                        double cost =
                                (sq(x[i] - x[j]) + sq(z[i] - z[j]))
                                        * (0.8 + 0.4 * unit(id, 150 + i * 9 + j));
                        if (!existing && cost < best) {
                            best = cost;
                            a = i;
                            b = j;
                        }
                    }
                edgeA[e] = a;
                edgeB[e] = b;
            }
        }
    }

    /** Caller owns anchor enumeration/overlap policy; no occurrence retry or terrain search.
     * Stable owner coordinates identify a site independently of chunk and iteration order.
     */
    public static Plan plan(
            Family family,
            long worldSeed,
            long ownerX,
            long ownerZ,
            double anchorX,
            double anchorZ,
            Reference ref,
            Tuning tuning,
            Intent earlier) {
        Objects.requireNonNull(family);
        Objects.requireNonNull(ref);
        Objects.requireNonNull(tuning);
        Objects.requireNonNull(earlier);
        coordinate(anchorX);
        coordinate(anchorZ);
        long id =
                mix(
                        worldSeed
                                ^ family.salt
                                ^ mix(ownerX + 0x71B431D562A9C857L)
                                ^ Long.rotateLeft(mix(ownerZ), 29));
        Reason reason = admit(ref, tuning, earlier);
        if (reason == Reason.ACCEPTED && unit(id, 0) >= tuning.occurrence)
            reason = Reason.OCCURRENCE;
        return new Plan(family, reason, id, anchorX, anchorZ, ref, tuning, earlier);
    }

    public static Reason admit(Reference r, Tuning t, Intent i) {
        require(t.reference == r);
        if (i.marine) return Reason.MARINE;
        if (i.waterReserved) return Reason.WATER_RESERVED;
        if (i.protectedShore) return Reason.PROTECTED_SHORE;
        if (i.mountainWeight < t.minMountainIntent) return Reason.LOW_MOUNTAIN_INTENT;
        if (i.uplift < t.minUplift) return Reason.LOW_UPLIFT;
        if (i.parentY < r.minY - r.featherY || i.parentY >= r.maxY) return Reason.ELEVATION;
        if (Math.min(r.maxY, i.ceilingY) - i.parentY < r.reliefMin) return Reason.HEADROOM;
        return Reason.ACCEPTED;
    }

    /** Caller-owned output. The hot sampler allocates nothing. */
    public static final class Sample {
        public double height, delta, weight, shape, ridge, pass;
        public Reason reason;

        private void clear(double parent, Reason why) {
            height = parent;
            delta = weight = shape = ridge = pass = 0;
            reason = why;
        }
    }

    /** Local facts also come from an earlier immutable stage, not this kernel's output.
     * preservationWeight is caller's continuous water/shore transition mask: 1 preserves.
     * marine/reserved flags are hard ownership vetoes, in addition to the smooth mask.
     */
    public static void sample(
            Plan p,
            double worldX,
            double worldZ,
            Intent local,
            double preservationWeight,
            Sample out) {
        coordinate(worldX);
        coordinate(worldZ);
        finite(preservationWeight);
        require(preservationWeight >= 0 && preservationWeight <= 1);
        out.clear(local.parentY, p.reason);
        if (p.reason != Reason.ACCEPTED) return;
        double dx = worldX - p.anchorX, dz = worldZ - p.anchorZ;
        double a = dx * p.cos + dz * p.sin, b = -dx * p.sin + dz * p.cos;
        double u = a / p.halfLength, v = b / p.halfWidth, r = StrictMath.sqrt(u * u + v * v);
        if (r >= 1) {
            out.reason = Reason.OUTSIDE_SUPPORT;
            return;
        }
        Reason why = admit(p.ref, p.tune, local);
        if (why != Reason.ACCEPTED) {
            out.reason = why;
            return;
        }
        double skirt = smooth((1 - r) / 0.24);
        double q;
        switch (p.family) {
            case RIDGE_TREE:
                q = tree(p, a, b, out);
                break;
            case FOLDED_BELTS:
                q = folds(p, u, v, out);
                break;
            case SUMMIT_GRAPH:
                q = graph(p, a, b, out);
                break;
            default:
                q = warped(p, u, v, out);
                break;
        }
        // Low amplitude texture scales with the formation, so it cannot survive at support edges.
        q =
                q
                        * (0.97
                                + 0.03
                                        * noise(
                                                p.id ^ 0x726F756768L,
                                                a / (p.halfWidth * 0.18),
                                                b / (p.halfWidth * 0.18)));
        q = StrictMath.pow(clamp(q), p.tune.sharpness);
        if (p.hasPass) {
            // A bounded dry route through a saddle, not a drainage or water-placement plan.
            double distance = (u - p.passLocalU(v)) * p.halfLength;
            out.pass = bell(distance / p.passWidth);
            double route = 0.23 * sq(Math.max(0, 1 - v * v));
            // Blend to a continuous saddle profile: a cut-only minimum would preserve
            // narrow pre-existing dips and could defeat the supplied traversable route.
            q = q * (1 - out.pass) + route * out.pass;
        }
        double fit =
                smooth((local.mountainWeight - p.tune.minMountainIntent) / 0.22)
                        * smooth((local.uplift - p.tune.minUplift) / 0.25)
                        * smooth((local.parentY - (p.ref.minY - p.ref.featherY)) / p.ref.featherY);
        // Headroom transition prevents a seam at rejection; no final height clipping.
        double room = Math.min(local.ceilingY, p.ref.maxY) - local.parentY;
        fit *= smooth((room - p.ref.reliefMin) / p.ref.reliefMin);
        double amplitude = Math.min(p.amplitude, room);
        out.shape = q;
        out.weight = skirt * fit * (1 - smooth(preservationWeight));
        out.delta = amplitude * q * out.weight;
        out.height = local.parentY + out.delta;
        out.reason = Reason.ACCEPTED;
    }

    private static double tree(Plan p, double a, double b, Sample s) {
        double crest = 0, buttress = 0;
        for (int e = 0; e < 16; e++) {
            int i = p.edgeA[e], j = p.edgeB[e];
            double width = p.ref.spurWidth * p.ref.horizontalScale * (e < 6 ? 0.9 : 0.56);
            double value = edge(p, a, b, i, j, width, e < 6 ? 0.18 : 0.1);
            crest = Math.max(crest, value);
            buttress = Math.max(buttress, edge(p, a, b, i, j, width * 2.6, 0.1));
        }
        s.ridge = crest;
        return 0.66 * crest + 0.34 * buttress;
    }

    private static double edge(Plan p, double a, double b, int i, int j, double width, double sag) {
        double vx = p.x[j] - p.x[i], vz = p.z[j] - p.z[i];
        double t = clamp(((a - p.x[i]) * vx + (b - p.z[i]) * vz) / (vx * vx + vz * vz));
        double rx = a - (p.x[i] + t * vx), rz = b - (p.z[i] + t * vz);
        // Asymmetric shoulders, with a smooth width change across the crest line.
        double side = (rx * vz - rz * vx) / (StrictMath.sqrt(vx * vx + vz * vz) * width);
        double asymmetric = 1 + 0.24 * side / StrictMath.sqrt(1 + side * side);
        double dist = StrictMath.sqrt(rx * rx + rz * rz) / (width * asymmetric);
        double height = p.h[i] + t * (p.h[j] - p.h[i]);
        return bell(dist) * height * (1 - sag * 4 * t * (1 - t));
    }

    private static double folds(Plan p, double u, double v, Sample s) {
        double ridge = 0, foot = 0;
        for (int i = 0; i < 3; i++) {
            double wave = noise(p.id + 701 + i, u * 2.2, 0.37) * p.tune.warp;
            double center = (i - 1) * (0.44 + 0.08 * unit(p.id, 710 + i)) + wave;
            double offset = v - center;
            double width =
                    (0.19 + 0.055 * unit(p.id, 720 + i))
                            * (offset < 0 ? 0.78 : 1.25)
                            * (0.8 + 0.35 * (0.5 + 0.5 * noise(p.id + 760 + i, u * 3.7, 0.5)));
            double along = 0.63 + 0.37 * (0.5 + 0.5 * noise(p.id + 730 + i, u * 4.7, 0.13));
            // Narrow oblique secondary spurs are subordinate to the connected belt.
            double spur = 0.5 + 0.5 * noise(p.id + 750 + i, u * 7.5 + v * 2, v * 3);
            ridge = Math.max(ridge, bell(offset / width) * along);
            foot =
                    Math.max(
                            foot,
                            bell(offset / (width * 2.3))
                                    * along
                                    * (0.7 + 0.3 * spur * p.tune.spurs));
        }
        // Two subordinate oblique saddle ribs link the otherwise parallel belts.
        for (int i = 0; i < 2; i++) {
            double at = (i == 0 ? -0.40 : 0.36) + (unit(p.id, 780 + i) - 0.5) * 0.22;
            double rib =
                    bell((u - at - 0.15 * v) / 0.13)
                            * bell(v / 0.87)
                            * (0.43 + 0.10 * unit(p.id, 790 + i));
            ridge = Math.max(ridge, rib);
            foot = Math.max(foot, rib);
        }
        s.ridge = ridge;
        return 0.58 * ridge + 0.42 * foot;
    }

    private static double graph(Plan p, double a, double b, Sample s) {
        double ridge = 0, shoulder = 0;
        double w = p.ref.spurWidth * p.ref.horizontalScale * 0.95;
        for (int e = 0; e < 10; e++) {
            ridge = Math.max(ridge, edge(p, a, b, p.edgeA[e], p.edgeB[e], w, 0.36));
            shoulder = Math.max(shoulder, edge(p, a, b, p.edgeA[e], p.edgeB[e], w * 2.4, 0.22));
        }
        double summit = 0;
        for (int i = 0; i < 9; i++) {
            double dx = (a - p.x[i]) / (w * (0.9 + 0.4 * unit(p.id, 810 + i)));
            double dz = (b - p.z[i]) / (w * (0.9 + 0.4 * unit(p.id, 830 + i)));
            double d = StrictMath.sqrt(dx * dx + dz * dz);
            summit = Math.max(summit, bell(d) * p.h[i]);
            shoulder = Math.max(shoulder, bell(d / 2.7) * p.h[i]);
        }
        s.ridge = ridge;
        return 0.70 * Math.max(ridge, summit) + 0.30 * shoulder;
    }

    private static double warped(Plan p, double u, double v, Sample s) {
        double x = u * 2.1 + p.tune.warp * 3 * noise(p.id + 901, u * 1.4, v * 1.4);
        double z = v * 2.8 + p.tune.warp * 3 * noise(p.id + 902, u * 1.4, v * 1.4);
        double r0 = 1 - StrictMath.abs(noise(p.id + 903, x, z));
        // A fixed rotation prevents the finer field sharing the coarse lattice axes.
        double r1 = 1 - StrictMath.abs(noise(p.id + 904, 1.7 * x + 1.1 * z, -1.1 * x + 1.7 * z));
        double r2 = 1 - StrictMath.abs(noise(p.id + 905, 3.8 * x - 2.2 * z, 2.2 * x + 3.8 * z));
        s.ridge = r0 * r0 * r0;
        double field = 0.67 * s.ridge + 0.25 * r1 * r1 * r1 * r0 + 0.08 * r2 * r2 * r2 * r1 * r0;
        // A positive macro crest supplies a connected primary ridge even when a
        // particular seed's implicit contour network fragments into isolated summits.
        double axis = p.tune.warp * noise(p.id + 906, u * 2.1, 0.3);
        double spine =
                bell((v - axis) / 0.24)
                        * (0.66 + 0.34 * (0.5 + 0.5 * noise(p.id + 907, u * 3.9, 0.7)));
        s.ridge = Math.max(s.ridge, spine);
        return Math.max(field, 0.84 * spine);
    }

    /** Independently authored quintic-interpolated lattice VALUE noise, not Perlin source. */
    static double noise(long seed, double x, double z) {
        long ix = (long) StrictMath.floor(x), iz = (long) StrictMath.floor(z);
        double fx = smooth(x - ix), fz = smooth(z - iz);
        double a = lattice(seed, ix, iz), b = lattice(seed, ix + 1, iz);
        double c = lattice(seed, ix, iz + 1), d = lattice(seed, ix + 1, iz + 1);
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    private static double lattice(long s, long x, long z) {
        return 2 * unit(mix(s ^ mix(x) ^ Long.rotateLeft(mix(z), 31)), 0) - 1;
    }

    public static long mix(long n) {
        // Authored reversible xor/multiply avalanche, fixed constants are part of seed identity.
        n ^= n >>> 29;
        n *= 0xD6E8FEB86659FD93L;
        n ^= n >>> 32;
        n *= 0xA5A3564E27F8862BL;
        return n ^ (n >>> 29);
    }

    static double unit(long id, long lane) {
        return (mix(id + lane * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
    }

    static double sq(double x) {
        return x * x;
    }

    static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    static double smooth(double x) {
        double t = clamp(x);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    static double bell(double x) {
        double q = Math.max(0, 1 - x * x);
        return q * q;
    }

    private static void require(boolean ok) {
        if (!ok) throw new IllegalArgumentException("Invalid mountain input or tuning");
    }

    private static void finite(double x) {
        require(Double.isFinite(x));
    }

    private static void coordinate(double x) {
        finite(x);
        require(StrictMath.abs(x) <= 30_000_000);
    }
}
