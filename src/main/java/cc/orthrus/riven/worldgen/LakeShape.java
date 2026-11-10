package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.lakes.Lakes;

import com.google.gson.JsonObject;

/** Four basin families inside a supplied foundation, with a matching earlier river handoff. */
public final class LakeShape {
    /** Spacing of the coarse pre-scan lattice, in blocks. */
    private static final int LATTICE = 8;

    private static final Rules RULES = Rules.load("riven-lakes.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final long VARIATION = Rules.seed(RULES.json, "variationSeed");
    // Explicit selection order and independent salts keep later draws out of family choice.
    private static final Lakes.Family[] FAMILIES = {
        Lakes.Family.HARMONIC, Lakes.Family.LOBES, Lakes.Family.SWEPT, Lakes.Family.CELLULAR
    };
    private static final double[] WEIGHTS = weights();
    private static final double ASPECT = Rules.number(RULES.json, "aspect", 1, 2.5);
    private static final double CORE = Rules.number(RULES.json, "coreRadiusFraction", .5, .85);
    private static final double DETAIL = Rules.number(RULES.json, "detail", 0, .20);
    private static final double SHELF = Rules.number(RULES.json, "shelfFraction", .08, .55);
    private static final double SKIRT = Rules.number(RULES.json, "skirtFraction", .04, .15);
    private static final double ISLAND_CHANCE = Rules.number(RULES.json, "islandChance", 0, 1);
    private static final double ISLAND_RADIUS = Rules.number(RULES.json, "islandRadius", 0, .15);
    private static final double OUTLET_FRACTION =
            Rules.number(RULES.json, "outletWidthFraction", .01, .15);
    private static final double DEEP_CHANCE = Rules.number(RULES.json, "deepLakeChance", 0, 1);
    private static final double DEEP_SUPPLY =
            Rules.number(RULES.json, "deepLakeMinimumSupply", 0, 1);
    private static final int MAX_COLUMNS =
            (int) Rules.number(RULES.json, "maximumCertificateColumns", 1024, 65536);

    static {
        if (RULES.json.get("revision").getAsDouble() != 2
                || RULES.json.get("maximumCertificateColumns").getAsDouble() != MAX_COLUMNS)
            throw new IllegalArgumentException("Lake shape revision/certificate bound");
        // The adapter's second shore feather must end before the source's closed support box.
        // Other families fit 1.10 long-axis radii at their full allowed detail/skirt ranges.
        double sweptFeather = Math.hypot(.64, (.19 + .625 * DETAIL) / ASPECT) + .215 + 2 * SKIRT;
        if (sweptFeather > 1.10)
            throw new IllegalArgumentException("Swept lake bank feather exceeds kernel support");
    }

    public final Lakes.Plan geometry;
    public final String profile;
    public final double radius, outerRadius, foundation;
    public final int certificateColumns;
    private final double cos, sin, outletEnd, outletHalf, outletBank;

    public static final class Decision {
        public final LakeShape shape;
        public final String reason;
        public final int probes;

        private Decision(LakeShape shape, String reason, int probes) {
            this.shape = shape;
            this.reason = reason;
            this.probes = probes;
        }
    }

    /** Actual immutable first-segment geometry and heads from the earlier Hydro route.
     * Half-width is a lower bound on its wet capsule, not its wider bank support.
     */
    public static final class Receiver {
        public final double x,
                z,
                endX,
                endZ,
                startHead,
                endHead,
                halfWidth,
                minimumDepth,
                length,
                angle;

        public Receiver(
                double x,
                double z,
                double endX,
                double endZ,
                double startHead,
                double endHead,
                double startWidth,
                double endWidth,
                double minimumDepth) {
            this.x = x;
            this.z = z;
            this.endX = endX;
            this.endZ = endZ;
            this.startHead = startHead;
            this.endHead = endHead;
            this.minimumDepth = minimumDepth;
            halfWidth = Math.min(startWidth, endWidth);
            length = Math.hypot(endX - x, endZ - z);
            angle = Math.atan2(endZ - z, endX - x);
        }

        private boolean supplied(double anchorX, double anchorZ, int waterY) {
            return x == anchorX
                    && z == anchorZ
                    && Double.isFinite(length)
                    && length > 1
                    && Double.isFinite(halfWidth)
                    && halfWidth > 1.5
                    && Double.isFinite(minimumDepth)
                    && minimumDepth >= 1
                    && startHead == waterY
                    && endHead == waterY;
        }

        private boolean contains(Lakes.Support layout) {
            double vx = endX - x, vz = endZ - z;
            double along =
                    ((layout.outletX - x) * vx + (layout.outletZ - z) * vz) / (length * length);
            if (along < 0 || along > 1) return false;
            double distance =
                    Math.hypot(layout.outletX - x - along * vx, layout.outletZ - z - along * vz);
            // The complete terminal disk fits in a supplied, same-head wet capsule.
            return distance + layout.terminalRadius <= halfWidth;
        }
    }

    private LakeShape(
            Lakes.Plan geometry,
            String profile,
            double radius,
            double outerRadius,
            double foundation,
            int columns,
            double angle) {
        this.geometry = geometry;
        this.profile = profile;
        this.radius = radius;
        this.outerRadius = outerRadius;
        this.foundation = foundation;
        certificateColumns = columns;
        cos = StrictMath.cos(angle);
        sin = StrictMath.sin(angle);
        outletEnd =
                geometry.t.outletWidth() > 0
                        ? 1.30 * geometry.t.width() * geometry.t.aspect() * .5
                        : 0;
        outletHalf = geometry.t.outletWidth() * .5;
        outletBank = geometry.t.width() * .5 * geometry.t.skirtFraction();
    }

    private static double[] weights() {
        JsonObject values = RULES.json.getAsJsonObject("familyWeights");
        double[] result = new double[FAMILIES.length];
        double total = 0;
        for (int i = 0; i < result.length; i++) {
            result[i] = Rules.number(values, FAMILIES[i].name(), 0, 100);
            total += result[i];
        }
        if (total <= 0)
            throw new IllegalStateException("Lake families require a positive total weight");
        return result;
    }

    /** Rows per item of the parallel full scan. */
    private static final int SCAN_ROWS = 4;

    /**
     * The full scan's world-aligned spacing inside the box; the box's border is read at every
     * column (terrain-v133). Lakes that pass the 8-block lattice almost always pass the scan:
     * over four seeds at layout scales 1 and 4, the per-block scan and this spacing decided all
     * 1,010 scanned lakes alike, for a third to two thirds less regional preparation.
     */
    static final int SCAN_STEP = 4;

    /** Whether the full scan reads the box column at row r, column c. */
    private static boolean scanned(int minX, int minZ, int width, int rows, int r, int c) {
        return r == 0
                || c == 0
                || r == rows - 1
                || c == width - 1
                || (Math.floorMod(minX + c, SCAN_STEP) == 0
                        && Math.floorMod(minZ + r, SCAN_STEP) == 0);
    }

    /** Columns the full scan reads before row-order index {@code end} (exclusive). */
    private static int scannedBefore(int minX, int minZ, int width, int rows, int end) {
        int count = 0;
        for (int k = 0; k < end; k++)
            if (scanned(minX, minZ, width, rows, k / width, k % width)) count++;
        return count;
    }

    /**
     * The first scanned column of the box, in row order, that fails the lake's column rules, as
     * (index in row order) * 2 + (0 for a protected shore, 1 for earlier earthwork), or -1 when
     * every scanned column passes. Rows go out in items across the pool; an item stops at its own
     * first failure, and items after a known earlier failure stop at once, since only the
     * earliest failure can decide. Every earlier item still finishes, so the answer is the serial
     * scan's.
     */
    private static int firstFailure(
            BuildPool pool,
            IntentSampler intent,
            int minX,
            int minZ,
            int width,
            int rows,
            double foundation,
            double maxFill,
            double maxCut,
            double floor) {
        int items = (rows + SCAN_ROWS - 1) / SCAN_ROWS;
        java.util.concurrent.atomic.AtomicIntegerArray found =
                new java.util.concurrent.atomic.AtomicIntegerArray(items);
        java.util.concurrent.atomic.AtomicInteger earliest =
                new java.util.concurrent.atomic.AtomicInteger(items);
        for (int i = 0; i < items; i++) found.set(i, -1);
        pool.forEach(
                items,
                item -> {
                    TerrainMap.BaseSample scratch = new TerrainMap.BaseSample();
                    int last = Math.min(rows, (item + 1) * SCAN_ROWS);
                    for (int r = item * SCAN_ROWS; r < last; r++)
                        for (int c = 0; c < width; c++) {
                            if (!scanned(minX, minZ, width, rows, r, c)) continue;
                            if (earliest.get() < item) return;
                            intent.sample(minX + c, minZ + r, scratch);
                            int code = -1;
                            if (scratch.ocean.marine
                                    || scratch.shore.sandy
                                    || scratch.shore.rocky()) code = 0;
                            else if (foundation - scratch.shore.height > maxFill
                                    || scratch.shore.height - floor > maxCut) code = 1;
                            if (code >= 0) {
                                found.set(item, (r * width + c) * 2 + code);
                                earliest.accumulateAndGet(item, Math::min);
                                return;
                            }
                        }
                });
        for (int i = 0; i < items; i++) if (found.get(i) >= 0) return found.get(i);
        return -1;
    }

    private static double unit(long key, long salt) {
        return SeededNoise.unit(SeededNoise.mix(key ^ salt));
    }

    /** Closed groundwater pond. Connected lakes must supply their actual earlier receiver. */
    public static Decision prepare(
            long seed,
            long id,
            IntentSampler intent,
            TerrainHeightReference heights,
            Hydro.Kind kind,
            double x,
            double z,
            double radius,
            double depth,
            int waterY,
            double skirt,
            double freeboard,
            double maxCut,
            double maxFill,
            int cellX,
            int cellZ,
            int cellBlocks,
            BuildPool pool) {
        return prepare(
                seed,
                id,
                intent,
                heights,
                kind,
                x,
                z,
                radius,
                depth,
                waterY,
                skirt,
                freeboard,
                maxCut,
                maxFill,
                cellX,
                cellZ,
                cellBlocks,
                0,
                null,
                pool);
    }

    /** Certify all native columns in the bounded owner box against earlier intent.
     * The kernel's parent is an explicitly reserved flat foundation, not raw terrain.
     * Family, islands and depth profile are chosen once; rejection retains the earlier plain body.
     */
    public static Decision prepare(
            long seed,
            long id,
            IntentSampler intent,
            TerrainHeightReference heights,
            Hydro.Kind kind,
            double x,
            double z,
            double radius,
            double depth,
            int waterY,
            double skirt,
            double freeboard,
            double maxCut,
            double maxFill,
            int cellX,
            int cellZ,
            int cellBlocks,
            double supply,
            Receiver receiver,
            BuildPool pool) {
        if (kind != Hydro.Kind.LAKE && kind != Hydro.Kind.POND) return reject("KIND", 0);
        boolean open = kind == Hydro.Kind.LAKE;
        if (open && (receiver == null || !receiver.supplied(x, z, waterY)))
            return reject("RECEIVER", 0);
        long key = SeededNoise.mix(seed ^ VARIATION ^ id);
        int selected = Rules.choose(WEIGHTS, unit(key, 0x46414d494c593031L));
        if (selected < 0) return reject("FAMILY", 0);
        Lakes.Family family = FAMILIES[selected];
        boolean deep =
                open && supply >= DEEP_SUPPLY && unit(key, 0x444545504c414b45L) < DEEP_CHANCE;
        TerrainHeightReference.Feature feature =
                heights.feature(
                        kind == Hydro.Kind.POND ? "pond_bed" : deep ? "deep_lake_bed" : "lake_bed");
        double wholeDepth = Math.floor(deep ? feature.magnitude.targetAverage : depth);
        double width = 2 * radius * CORE / (1.10 * ASPECT), outletWidth = 0, angle = 0;
        if (open) {
            // Fit the channel and its extra dry-bank feather inside the earlier owner.
            // The original terminal bank disk must also fit inside the existing wet receiver.
            double budget = Math.min(radius * CORE, receiver.length - 1);
            width =
                    Math.min(
                            budget / (.65 * ASPECT + OUTLET_FRACTION * .5 + SKIRT),
                            (receiver.halfWidth - .5) / (OUTLET_FRACTION * .5 + SKIRT * .5));
            outletWidth = width * OUTLET_FRACTION;
            if (outletWidth < 2) {
                width =
                        Math.min(
                                (budget - 1) / (.65 * ASPECT + SKIRT),
                                (receiver.halfWidth - 1.5) / (SKIRT * .5));
                outletWidth = 2;
            }
            angle = receiver.angle;
        }
        double minWidth = feature.width.min * TerrainScale.HORIZONTAL,
                maxWidth = feature.width.max * TerrainScale.HORIZONTAL;
        double outer = radius + Math.min(skirt, heights.feature("river_bed").width.targetAverage);
        double foundation = waterY + Math.max(2, freeboard);
        double island = unit(key, 0x49534c414e443031L) < ISLAND_CHANCE ? ISLAND_RADIUS : 0;
        if (width < minWidth
                || width > maxWidth
                || wholeDepth < feature.magnitude.min
                || wholeDepth > feature.magnitude.max
                || outletWidth > width * .15
                || radius - radius * CORE < 2) return reject("REFERENCE", 0);
        if (foundation - (waterY - wholeDepth) > maxCut) return reject("CUT_BUDGET", 0);
        if (Math.abs(x) + outer > 29999984
                || Math.abs(z) + outer > 29999984
                || x - outer <= cellX * (double) cellBlocks
                || z - outer <= cellZ * (double) cellBlocks
                || x + outer >= (cellX + 1.) * cellBlocks
                || z + outer >= (cellZ + 1.) * cellBlocks) return reject("OWNER", 0);
        int minX = (int) Math.floor(x - outer), maxX = (int) Math.ceil(x + outer);
        int minZ = (int) Math.floor(z - outer), maxZ = (int) Math.ceil(z + outer);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > MAX_COLUMNS)
            return reject("WORK_BOUND", 0);
        Lakes.Reference reference =
                new Lakes.Reference(
                        feature.id,
                        minWidth,
                        maxWidth,
                        feature.magnitude.min,
                        feature.magnitude.max,
                        heights.lowestSurface,
                        heights.highestSurface);
        Lakes.Tuning tuning =
                new Lakes.Tuning(
                        width,
                        wholeDepth,
                        ASPECT,
                        DETAIL,
                        SHELF,
                        island,
                        SKIRT,
                        Math.max(2, freeboard),
                        maxCut,
                        outletWidth,
                        Math.min(2, wholeDepth));
        long shapeSeed = seed ^ VARIATION;
        if (open) {
            Lakes.Support layout = Lakes.supportAligned(shapeSeed, x, z, id, family, tuning, angle);
            if (!receiver.contains(layout)) return reject("RECEIVER_DISK", 0);
        }
        TerrainMap.BaseSample scratch = new TerrainMap.BaseSample();
        int probes = 0;
        // The same column rules on a coarse world-aligned lattice first: each lattice column is a
        // column of the box, so a failure there is a failure of the full scan, at a fraction of
        // its cost (most candidate lakes fail on a slope or a protected shore).
        for (int pz = Math.floorDiv(minZ + LATTICE - 1, LATTICE) * LATTICE;
                pz <= maxZ;
                pz += LATTICE)
            for (int px = Math.floorDiv(minX + LATTICE - 1, LATTICE) * LATTICE;
                    px <= maxX;
                    px += LATTICE) {
                intent.sample(px, pz, scratch);
                probes++;
                if (scratch.ocean.marine || scratch.shore.sandy || scratch.shore.rocky())
                    return reject("PROTECTED_SHORE", probes);
                if (foundation - scratch.shore.height > maxFill
                        || scratch.shore.height - (waterY - wholeDepth) > maxCut)
                    return reject("EARLIER_EARTHWORK", probes);
            }
        // The full scan, across the build pool: the border and the SCAN_STEP lattice inside it;
        // the first failing scanned column in row order decides, as the serial scan would.
        int boxWidth = maxX - minX + 1, boxRows = maxZ - minZ + 1;
        int failure =
                firstFailure(
                        pool,
                        intent,
                        minX,
                        minZ,
                        boxWidth,
                        boxRows,
                        foundation,
                        maxFill,
                        maxCut,
                        waterY - wholeDepth);
        if (failure >= 0)
            return reject(
                    (failure & 1) == 0 ? "PROTECTED_SHORE" : "EARLIER_EARTHWORK",
                    probes + scannedBefore(minX, minZ, boxWidth, boxRows, failure >>> 1) + 1);
        probes += scannedBefore(minX, minZ, boxWidth, boxRows, boxWidth * boxRows);
        // Ownership excludes unrelated bodies; only this matching river may share the outlet.
        // The native solid fill and final aquifer roof seal supply the floor seal.
        Lakes.Earlier earlier =
                new Lakes.Earlier(
                        waterY,
                        true,
                        false,
                        false,
                        true,
                        radius,
                        foundation,
                        foundation,
                        true,
                        open,
                        waterY);
        Lakes.Decision result =
                open
                        ? Lakes.planAligned(
                                shapeSeed, x, z, id, family, tuning, reference, earlier, angle)
                        : Lakes.plan(shapeSeed, x, z, id, family, tuning, reference, earlier);
        if (result.plan() == null) return reject("KERNEL_" + result.reason(), probes);
        return new Decision(
                new LakeShape(result.plan(), feature.id, radius, outer, foundation, probes, angle),
                "ACCEPT",
                probes);
    }

    static Decision reject(String reason, int probes) {
        return new Decision(null, reason, probes);
    }

    /** Upstream of the terminal plane the kernel alone owns the body, islands and channel. */
    public boolean allowsRiver(double x, double z) {
        double dx = x - geometry.anchorX, dz = z - geometry.anchorZ;
        return outletEnd == 0
                || Math.hypot(dx, dz) >= outerRadius
                || dx * cos + dz * sin >= outletEnd;
    }

    public void apply(double x, double z, Hydro.Kind kind, long id, Hydro.Sample out) {
        if (out.wet) return;
        double dx = x - geometry.anchorX, dz = z - geometry.anchorZ;
        if (Math.hypot(dx, dz) >= outerRadius) return;
        geometry.sample(x, z, foundation, out.lakeGeometry);
        Lakes.Sample sample = out.lakeGeometry;
        if (sample.wet) {
            out.wet = true;
            out.floor = sample.bed;
            out.waterY = sample.waterY;
            out.head = sample.waterY;
            out.weight = 1;
            out.kind = kind;
            out.id = id;
            out.bed = Hydro.Bed.GRAVEL;
        } else {
            // Negative original shore distance pins dry islands as well as the basin's bank.
            double skirt = geometry.t.skirtFraction();
            double weight = 1 - Rules.smooth((sample.shoreDistance - skirt) / skirt);
            if (outletEnd > 0) {
                double along = dx * cos + dz * sin, at = Math.max(0, Math.min(outletEnd, along));
                double distance = Math.hypot(along - at, -dx * sin + dz * cos);
                // Pin the source channel bank, then feather its foundation outside that skirt.
                weight =
                        Math.max(
                                weight,
                                1
                                        - Rules.smooth(
                                                (distance - outletHalf - outletBank) / outletBank));
            }
            if (weight > out.weight) {
                out.bank = sample.bed;
                out.weight = weight;
                out.head = geometry.earlier.waterY();
                out.kind = kind;
                out.id = id;
            }
        }
    }
}
