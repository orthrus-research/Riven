package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.rocky.RockyCoasts;
import cc.orthrus.riven.contrib.rocky.RockyCoasts.Column;
import cc.orthrus.riven.contrib.rocky.RockyCoasts.ColumnProposal;
import cc.orthrus.riven.contrib.rocky.RockyCoasts.Edit;
import cc.orthrus.riven.contrib.rocky.RockyCoasts.Family;

import com.google.gson.JsonObject;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Exclusive rocky shoreline targets and actual volume edits, on a frozen earlier surface. */
public final class RockyTerrain {
    private static final Rules RULES = Rules.load("riven-rocky-coasts.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final Family[] FAMILIES = {
        Family.CLIFF_PLATFORM, Family.STEPPED_HEADLAND, Family.JOINT_COVES, Family.STACK_ARCH
    };
    private final long seed;
    private final IntentSampler earlierIntent;
    private final RockyCoastParent earlierTerrain;
    private final BoundedWaterClearance waterClearance;
    private final Sites sites;
    private final RockyCoasts.Reference reference;
    private final RockyCoasts.Tuning[] tuning = new RockyCoasts.Tuning[FAMILIES.length];
    private final double[] weights = new double[FAMILIES.length];
    private final double radius;
    private final int searchRadius, sets, ways, maximumColumns, maximumBodyColumns, maximumVoxels;
    private final AtomicReferenceArray<Entry> memo;
    private final AtomicIntegerArray next;
    private final SharedBuild<Decision> builds = new SharedBuild<>();

    RockyTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            IntentSampler earlierIntent,
            RockyCoastParent earlierTerrain,
            BoundedWaterClearance waterClearance,
            int waterCellBlocks) {
        this.earlierIntent = earlierIntent;
        this.earlierTerrain = earlierTerrain;
        this.waterClearance = waterClearance;
        this.sites = sites;
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        TerrainHeightReference.Feature scarp = heights.feature("scarp");
        double scale = TerrainScale.HORIZONTAL;
        Properties r = new Properties();
        put(r, "sea", heights.seaLevel);
        put(r, "waterTop", heights.waterTopY);
        put(r, "low", heights.lowestSurface);
        put(r, "high", heights.highestSurface);
        put(r, "scarp.magnitude.min", scarp.magnitude.min);
        put(r, "scarp.magnitude.mean", scarp.magnitude.targetAverage);
        put(r, "scarp.magnitude.max", scarp.magnitude.max);
        put(r, "scarp.width.min", scarp.width.min * scale);
        put(r, "scarp.width.mean", scarp.width.targetAverage * scale);
        put(r, "scarp.width.max", scarp.width.max * scale);
        put(r, "beach.width.max", heights.feature("beach").width.max * scale);
        reference = new RockyCoasts.Reference(r);
        Properties t = new Properties();
        for (String name :
                new String[] {"coveDepth", "minimumRock", "minimumExposure", "archFraction"})
            put(t, name, j.get(name).getAsDouble());
        for (int i = 0; i < FAMILIES.length; i++) {
            JsonObject p = j.getAsJsonObject("profiles").getAsJsonObject(FAMILIES[i].name());
            for (String name : new String[] {"occurrence", "widthScale", "reliefScale", "detail"})
                put(t, FAMILIES[i].name() + "." + name, p.get(name).getAsDouble());
            tuning[i] = new RockyCoasts.Tuning(t, FAMILIES[i]);
            weights[i] =
                    Rules.number(j.getAsJsonObject("familyWeights"), FAMILIES[i].name(), 0, 10);
        }
        radius = sites.maximumRadius(Sites.Lane.ROCKY);
        anchorReach = reference.widthMax * Rules.number(j, "anchorReachInWidths", 1, 8);
        searchRadius = sites.searchRadius(Sites.Lane.ROCKY);
        maximumColumns = integer(j, "maximumCertificateColumns", 65536, 1048576);
        maximumBodyColumns = integer(j, "maximumBodyColumns", 1024, 65536);
        maximumVoxels = integer(j, "maximumCertificateVoxels", 65536, 2097152);
        sets = integer(j, "cacheSets", 16, 4096);
        ways = integer(j, "cacheWays", 1, 4);
        if ((sets & (sets - 1)) != 0 || radius + reference.widthMin + 4 > waterCellBlocks)
            throw new IllegalStateException("Rocky cache or hydrology reach");
        memo = new AtomicReferenceArray<>(sets * ways);
        next = new AtomicIntegerArray(sets);
    }

    private static void put(Properties p, String key, double value) {
        p.setProperty(key, Double.toString(value));
    }

    private static int integer(JsonObject j, String key, int min, int max) {
        double n = Rules.number(j, key, min, max);
        if (n != (int) n) throw new IllegalStateException(key);
        return (int) n;
    }

    private static final class Entry {
        final long key;
        final Decision decision;

        Entry(long key, Decision decision) {
            this.key = key;
            this.decision = decision;
        }
    }

    private static final class Evidence {
        boolean core, foundationSupported, attached, solidConnected, portals;
        int boxColumns, landColumns, witnessColumns, bodyColumns, bodyProbes, voxels, voidVoxels;
        double minimumLand = Double.POSITIVE_INFINITY,
                maximumParent = Double.NEGATIVE_INFINITY,
                nativeMin = Double.POSITIVE_INFINITY,
                nativeMax = Double.NEGATIVE_INFINITY,
                rock = 1,
                exposure = 1,
                foundation,
                waterClearance;

        Evidence copy() {
            Evidence c = new Evidence();
            c.core = core;
            c.foundationSupported = foundationSupported;
            c.attached = attached;
            c.solidConnected = solidConnected;
            c.portals = portals;
            c.boxColumns = boxColumns;
            c.landColumns = landColumns;
            c.witnessColumns = witnessColumns;
            c.bodyColumns = bodyColumns;
            c.bodyProbes = bodyProbes;
            c.voxels = voxels;
            c.voidVoxels = voidVoxels;
            c.minimumLand = minimumLand;
            c.maximumParent = maximumParent;
            c.nativeMin = nativeMin;
            c.nativeMax = nativeMax;
            c.rock = rock;
            c.exposure = exposure;
            c.foundation = foundation;
            c.waterClearance = waterClearance;
            return c;
        }
    }

    public static final class Decision {
        public final RockyCoasts.ColumnPlan plan;
        public final Family family;
        public final boolean arch;
        public final String reason;
        public final int boxColumns,
                landColumns,
                witnessColumns,
                bodyColumns,
                bodyProbes,
                voxels,
                voidVoxels;
        public final double minimumLand, maximumParent, rock, exposure, foundation, waterClearance;

        private Decision(
                RockyCoasts.ColumnPlan plan,
                Family family,
                boolean arch,
                String reason,
                Evidence e) {
            this.plan = plan;
            this.family = family;
            this.arch = family == Family.STACK_ARCH && arch;
            this.reason = reason;
            boxColumns = e.boxColumns;
            landColumns = e.landColumns;
            witnessColumns = e.witnessColumns;
            bodyColumns = e.bodyColumns;
            bodyProbes = e.bodyProbes;
            voxels = e.voxels;
            voidVoxels = e.voidVoxels;
            minimumLand = Double.isFinite(e.minimumLand) ? e.minimumLand : 0;
            maximumParent = Double.isFinite(e.maximumParent) ? e.maximumParent : 0;
            rock = e.rock;
            exposure = e.exposure;
            foundation = e.foundation;
            waterClearance = e.waterClearance;
        }
    }

    public Decision plan(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        int set = (int) SeededNoise.mix(key) & (sets - 1), first = set * ways;
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.decision;
        }
        Decision built = builds.get(key, () -> build(cx, cz));
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.decision;
        }
        memo.set(first + Math.floorMod(next.getAndIncrement(set), ways), new Entry(key, built));
        return built;
    }

    private static Decision reject(Family f, boolean arch, String reason, Evidence e) {
        return new Decision(null, f, arch, reason, e);
    }

    /** An anchor's checks that its own earlier facts decide, before any competition. */
    private static final class Prepared {
        final String reason;
        final Family family;
        final int selected;
        final ColumnProposal proposal;
        final Evidence evidence;

        Prepared(
                String reason,
                Family family,
                int selected,
                ColumnProposal proposal,
                Evidence evidence) {
            this.reason = reason;
            this.family = family;
            this.selected = selected;
            this.proposal = proposal;
            this.evidence = evidence;
        }
    }

    private final CellMemo<Prepared> prepared = new CellMemo<>(4096);

    /** Anchors farther from the shore than this cannot reach it inside the lane radius. */
    private final double anchorReach;

    /**
     * Everything an anchor's own earlier facts decide: family, shoreline root, proposal,
     * occurrence, support and the backing-land witness strip. Only anchors that pass compete for
     * the rocky lane, so one that could never build cannot veto one that can. Reads earlier intent
     * and bounded water clearance only, never another site.
     */
    private Prepared prepare(int cx, int cz, long owner) {
        return prepared.get(cx, cz, () -> prepareUncached(cx, cz, owner));
    }

    private Prepared prepareUncached(int cx, int cz, long owner) {
        Evidence e = new Evidence();
        int selected = Rules.choose(weights, Sites.random(owner, 0x524f434b));
        if (selected < 0) return new Prepared("NOT_SELECTED", null, -1, null, e);
        Family f = FAMILIES[selected];
        double ax = sites.x(cx, owner), az = sites.z(cz, owner);
        if (Math.abs(ax) + radius > 29999984 || Math.abs(az) + radius > 29999984)
            return new Prepared("WORLD_BOUND", f, selected, null, e);
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        earlierIntent.sample(ax, az, scratch);
        double nx = scratch.ocean.normalX, nz = scratch.ocean.normalZ, n = Math.hypot(nx, nz);
        if (!Double.isFinite(n)
                || !Double.isFinite(scratch.ocean.distance)
                || n < .5
                || Math.abs(scratch.ocean.distance) > anchorReach)
            return new Prepared("NO_NEAR_SHORE", f, selected, null, e);
        nx /= n;
        nz /= n;
        // The coast distance is the outline score, which can understate true distance (rough or
        // elongated continents), so the search reaches half again past the admission band.
        double[] root = shore(ax, az, nx, nz, 1.5 * anchorReach, scratch);
        if (root == null) return new Prepared("SHORE_BRACKET", f, selected, null, e);
        ColumnProposal p =
                RockyCoasts.proposeColumns(
                        f, seed, owner, root[0], root[1], nx, nz, reference, tuning[selected]);
        if (!RockyCoasts.occurs(p)) return new Prepared("OCCURRENCE", f, selected, p, e);
        if (Math.hypot(p.x - ax, p.z - az) + p.supportRadius + 5 > radius)
            return new Prepared("SUPPORT_BUDGET", f, selected, p, e);
        if (p.boxColumns > maximumColumns) return new Prepared("WORK_BOUND", f, selected, p, e);
        double waterReach = p.supportRadius + reference.widthMin + 4;
        e.waterClearance = waterClearance.surfaceClearance(p.x, p.z, waterReach);
        if (e.waterClearance < waterReach) return new Prepared("WATER_SUPPORT", f, selected, p, e);
        // A material-intent patch has actual backing witnesses along its full tangent span.
        // Their conservative lower bound extends the earlier rocky shore intent across its
        // submerged toe.
        for (int u = (int) Math.floor(-p.halfAlong); u <= Math.ceil(p.halfAlong); u++) {
            earlierIntent.sample(
                    p.x - p.nz * u + p.nx * p.inland * .75,
                    p.z + p.nx * u + p.nz * p.inland * .75,
                    scratch);
            e.witnessColumns++;
            if (scratch.ocean.marine) return new Prepared("NO_BACKING_LAND", f, selected, p, e);
            e.rock = Math.min(e.rock, rock(scratch));
            e.exposure = Math.min(e.exposure, exposure(scratch));
        }
        if (e.rock < tuning[selected].minimumRock)
            return new Prepared("NO_ROCK_INTENT", f, selected, p, e);
        if (e.exposure < tuning[selected].minimumExposure)
            return new Prepared("LOW_EXPOSURE", f, selected, p, e);
        return new Prepared(null, f, selected, p, e);
    }

    /**
     * Rock intent: rocky or cliff shore class (which includes raised capes) or rugged raised
     * ground. Each needs raised land, which also keeps the coastal plain from flattening the
     * land behind the cliff.
     */
    private static double rock(TerrainMap.BaseSample s) {
        return Math.max(
                Math.max(s.shore.rock, s.shore.cliff),
                s.land.rugged * (s.land.middle + s.land.high));
    }

    private Decision build(int cx, int cz) {
        long owner = sites.key(Sites.Lane.ROCKY, cx, cz);
        if (!sites.inLane(owner, Sites.Lane.ROCKY))
            return reject(null, false, "SHARED_BUDGET", new Evidence());
        Prepared c = prepare(cx, cz, owner);
        Evidence e = c.evidence.copy();
        Family f = c.family;
        ColumnProposal p = c.proposal;
        boolean arch = p != null && p.arch;
        if (c.reason != null) return reject(f, arch, c.reason, e);
        if (!sites.admits(
                cx, cz, owner, Sites.Lane.ROCKY, (x, z, o) -> prepare(x, z, o).reason == null))
            return reject(f, arch, "SHARED_BUDGET", e);
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        int minX = (int) Math.floor(p.minX),
                maxX = (int) Math.ceil(p.maxX),
                minZ = (int) Math.floor(p.minZ),
                maxZ = (int) Math.ceil(p.maxZ);
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                earlierTerrain.sample(x, z, scratch);
                e.boxColumns++;
                if (!Double.isFinite(scratch.height) || !Double.isFinite(scratch.rockyParentHeight))
                    return reject(f, p.arch, "INVALID_PARENT", e);
                e.nativeMin = Math.min(e.nativeMin, scratch.height);
                e.nativeMax = Math.max(e.nativeMax, scratch.height);
                e.maximumParent =
                        Math.max(
                                e.maximumParent,
                                Math.max(scratch.height, scratch.rockyParentHeight));
                if (!RockyCoasts.inSupport(p, x, z, scratch.ocean.distance)) continue;
                e.exposure = Math.min(e.exposure, exposure(scratch));
                if (reserved(scratch)) return reject(f, p.arch, "WATER_COLUMN", e);
                if (!scratch.ocean.marine) {
                    e.landColumns++;
                    e.minimumLand = Math.min(e.minimumLand, scratch.rockyParentHeight);
                }
            }
        if (e.landColumns == 0) return reject(f, p.arch, "NO_LAND_ENVELOPE", e);
        if (e.minimumLand - reference.sea < reference.dropMin)
            return reject(f, p.arch, "INSUFFICIENT_RELIEF", e);
        e.foundation = Math.min(reference.waterTop - reference.dropMin, Math.floor(e.nativeMin));
        boolean volume = f == Family.STACK_ARCH;
        if (volume) {
            String reason = body(p, scratch, e);
            if (reason != null) return reject(f, p.arch, reason, e);
        }
        RockyCoasts.ColumnFacts facts =
                new RockyCoasts.ColumnFacts(
                        p,
                        e.boxColumns,
                        e.landColumns,
                        e.bodyColumns,
                        e.minimumLand,
                        e.maximumParent,
                        e.foundation,
                        e.rock,
                        e.exposure,
                        e.nativeMin,
                        e.nativeMax,
                        e.foundationSupported,
                        e.core && e.solidConnected,
                        e.attached,
                        e.portals);
        RockyCoasts.ColumnDecision admitted = RockyCoasts.admitColumns(p, facts);
        return new Decision(admitted.plan, f, p.arch, admitted.reason.name(), e);
    }

    /**
     * The shoreline nearest the point along the coast normal, within reach either way: walks out in
     * 8-block steps on both sides until land and sea flip, bisects that step, and requires sea
     * seaward and land inland two blocks either side of the root. The former end-point bracket
     * failed beside narrow peninsulas, inlets and islets, where both ends fall on one side.
     */
    private double[] shore(
            double x, double z, double nx, double nz, double reach, TerrainMap.BaseSample scratch) {
        earlierIntent.sample(x, z, scratch);
        boolean here = scratch.ocean.marine;
        double low = Double.NaN, high = Double.NaN;
        for (double t = 8; t <= reach + 7.999 && Double.isNaN(low); t += 8) {
            double step = Math.min(t, reach);
            for (int side = 1; side >= -1 && Double.isNaN(low); side -= 2) {
                earlierIntent.sample(x + nx * side * step, z + nz * side * step, scratch);
                if (scratch.ocean.marine != here) {
                    double inner = side * Math.max(0, step - 8), outer = side * step;
                    // Keep "low" on the sea side and "high" on the land side of the bracket.
                    if (here) {
                        low = inner;
                        high = outer;
                    } else {
                        low = outer;
                        high = inner;
                    }
                }
            }
        }
        if (Double.isNaN(low)) return null;
        for (int i = 0; i < 16; i++) {
            double m = (low + high) * .5;
            earlierIntent.sample(x + nx * m, z + nz * m, scratch);
            if (scratch.ocean.marine) low = m;
            else high = m;
        }
        double q = (low + high) * .5, px = x + nx * q, pz = z + nz * q;
        earlierIntent.sample(px + nx * 2, pz + nz * 2, scratch);
        if (scratch.ocean.marine) return null;
        earlierIntent.sample(px - nx * 2, pz - nz * 2, scratch);
        if (!scratch.ocean.marine) return null;
        return new double[] {px, pz};
    }

    private static boolean reserved(TerrainMap.BaseSample s) {
        return s.ocean.protectedWater || s.water.wet || s.water.weight > 0;
    }

    private static double protection(TerrainMap.BaseSample s) {
        return Math.max(s.shore.marsh, Math.max(s.shore.beach, s.shore.plain));
    }

    private static double exposure(TerrainMap.BaseSample s) {
        return 1 - Rules.smooth(Math.min(1, s.ocean.shelter * 3));
    }

    /** Temporary earlier columns/geometry, discarded after admission; never attached to a cached decision. */
    private static final class Body {
        final int minX, minZ, width, height;
        final double[] parent;
        final int[] water, aquiferMin, aquiferMax;
        final Column[] columns;

        Body(int minX, int minZ, int width, int height) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            int size = width * height;
            parent = new double[size];
            water = new int[size];
            aquiferMin = new int[size];
            aquiferMax = new int[size];
            columns = new Column[size];
        }
    }

    private String body(ColumnProposal p, TerrainMap.Sample scratch, Evidence e) {
        int minX = (int) Math.floor(p.bodyMinX) - 3,
                maxX = (int) Math.ceil(p.bodyMaxX) + 3,
                minZ = (int) Math.floor(p.bodyMinZ) - 3,
                maxZ = (int) Math.ceil(p.bodyMaxZ) + 3;
        int width = maxX - minX + 1, height = maxZ - minZ + 1;
        if ((long) width * height > maximumBodyColumns) return "BODY_WORK_BOUND";
        Body b = new Body(minX, minZ, width, height);
        double floor = Double.POSITIVE_INFINITY;
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                int at = (z - minZ) * width + x - minX;
                earlierTerrain.sample(x, z, scratch);
                e.bodyProbes++;
                if (!Double.isFinite(scratch.height)) return "INVALID_BODY_PARENT";
                b.parent[at] = scratch.height;
                b.water[at] = scratch.waterY();
                b.aquiferMin[at] = scratch.water.aquiferMin;
                b.aquiferMax[at] = scratch.water.aquiferMax;
                Column c = new Column();
                b.columns[at] = c;
                RockyCoasts.probeBodyColumns(p, x, z, scratch.height, c);
                if (!p.arch
                        && (!scratch.ocean.marine
                                || scratch.waterY() != reference.waterTop
                                || scratch.blockY() >= scratch.waterY())) return "STACK_WATER_MOAT";
                if (c.reason != RockyCoasts.Reason.ACCEPTED) continue;
                e.bodyColumns++;
                floor = Math.min(floor, Math.floor(scratch.height) - 1);
                if (reserved(scratch)
                        || RockyCoasts.coreWeight(
                                        p, x, z, scratch.ocean.distance, protection(scratch))
                                < .999999) return "BODY_CORE_PERMISSION";
            }
        if (e.bodyColumns == 0) return "EMPTY_BODY";
        e.core = true;
        e.foundation = Math.min(floor, reference.waterTop - reference.dropMin);
        if (e.foundation < reference.low || p.maximumBodyTop > reference.high)
            return "BODY_HEADROOM";
        boolean contact = !p.arch;
        for (int at = 0; at < b.columns.length; at++) {
            Column c = b.columns[at];
            if (c.reason != RockyCoasts.Reason.ACCEPTED) continue;
            if (e.foundation > Math.floor(b.parent[at])
                    || b.aquiferMin[at] <= Math.floor(c.bodyTop)
                            && b.aquiferMax[at] >= e.foundation) return "FOUNDATION_WATER";
            if (!p.arch
                    || c.localD <= 0
                    || RockyCoasts.probeOccupancy(p, c, (int) reference.sea, e.foundation)
                            != Edit.SOLID) continue;
            int u = at % width, v = at / width;
            for (int d = 0; d < 4; d++) {
                int x = u + (d == 0 ? 1 : d == 1 ? -1 : 0), z = v + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (x < 0 || z < 0 || x >= width || z >= height) continue;
                int q = z * width + x;
                if (b.columns[q].reason != RockyCoasts.Reason.ACCEPTED
                        && b.parent[q] >= reference.sea
                        && b.water[q] <= reference.waterTop
                        && (reference.sea < b.aquiferMin[q] || reference.sea > b.aquiferMax[q]))
                    contact = true;
            }
        }
        if (!contact) return "NO_MAINLAND_CONTACT";
        e.foundationSupported = true;
        e.attached = true;
        return voxels(p, b, e);
    }

    private String voxels(ColumnProposal p, Body b, Evidence e) {
        int bottom = (int) Math.ceil(e.foundation),
                top = (int) Math.floor(p.maximumBodyTop),
                layers = top - bottom + 1,
                plane = b.width * b.height;
        long size = (long) plane * layers;
        if (layers < 1 || size > maximumVoxels) return "VOXEL_WORK_BOUND";
        byte[] cells = new byte[(int) size];
        int[] queue = new int[cells.length];
        int first = 0, last = 0, solid = 0;
        boolean frontPier = false, backPier = false;
        for (int at = 0; at < plane; at++) {
            Column c = b.columns[at];
            if (c.reason != RockyCoasts.Reason.ACCEPTED) continue;
            int lastVoid = Integer.MIN_VALUE;
            boolean cut = false;
            for (int y = bottom; y <= top; y++) {
                Edit edit = RockyCoasts.probeOccupancy(p, c, y, e.foundation);
                e.voxels++;
                if (edit == Edit.VOID) {
                    e.voidVoxels++;
                    lastVoid = y;
                    cut = true;
                }
                if (edit != Edit.SOLID) continue;
                int q = (y - bottom) * plane + at;
                cells[q] = 1;
                solid++;
                if (y == bottom) {
                    cells[q] = 2;
                    queue[last++] = q;
                }
            }
            if (cut && Math.floor(c.bodyTop) - lastVoid < Math.ceil(reference.dropMin))
                return "NATIVE_ROOF_THICKNESS";
            if (!cut && c.bodyTop >= p.tunnelCenterY + p.tunnelRadiusY + reference.dropMin) {
                double d = (c.localD + p.width * .45) / (p.width * .60);
                if (d < -1) frontPier = true;
                if (d > 1) backPier = true;
            }
        }
        if (solid == 0) return "NO_SOLID_BODY";
        while (first < last) {
            int at = queue[first++];
            for (int direction = 0; direction < 6; direction++) {
                int q = neighbor(at, direction, b.width, b.height, layers);
                if (q < 0 || cells[q] != 1) continue;
                cells[q] = 2;
                queue[last++] = q;
            }
        }
        if (last != solid) return "UNSUPPORTED_NATIVE_ROOF";
        e.solidConnected = true;
        if (!p.arch) return null;
        if (e.voidVoxels == 0 || !frontPier || !backPier) return "NO_NATIVE_ARCH";
        // Reuse the bounded voxel workspace. Every free portal cell lies inside the authored tunnel
        // cylinder.
        for (int q = 0; q < cells.length; q++) {
            int at = q % plane, y = bottom + q / plane;
            Column c = b.columns[at];
            cells[q] = 0;
            if (!RockyCoasts.portalCylinder(p, c, y)) continue;
            Edit edit = RockyCoasts.probeOccupancy(p, c, y, e.foundation);
            if (edit == Edit.VOID) cells[q] = 2;
            else if (edit == Edit.KEEP && y > Math.floor(b.parent[at]) && y > b.water[at])
                cells[q] = 1;
        }
        for (int start = 0; start < cells.length; start++) {
            if (cells[start] == 0
                    || (cells[start] & 4) != 0
                    || b.columns[start % plane].localU >= -p.radiusU - 1) continue;
            first = 0;
            last = 1;
            queue[0] = start;
            cells[start] |= 4;
            boolean opening = false, right = false;
            while (first < last) {
                int at = queue[first++];
                if ((cells[at] & 3) == 2) opening = true;
                if (b.columns[at % plane].localU > p.radiusU + 1) right = true;
                for (int direction = 0; direction < 6; direction++) {
                    int q = neighbor(at, direction, b.width, b.height, layers);
                    if (q < 0 || cells[q] == 0 || (cells[q] & 4) != 0) continue;
                    cells[q] |= 4;
                    queue[last++] = q;
                }
            }
            if (opening && right) {
                e.portals = true;
                return null;
            }
        }
        return "BLOCKED_NATIVE_PORTAL";
    }

    private static int neighbor(int at, int direction, int width, int height, int layers) {
        int plane = width * height, x = at % width, z = at / width % height, y = at / plane;
        if (direction == 0) return x + 1 < width ? at + 1 : -1;
        if (direction == 1) return x > 0 ? at - 1 : -1;
        if (direction == 2) return z + 1 < height ? at + width : -1;
        if (direction == 3) return z > 0 ? at - width : -1;
        if (direction == 4) return y + 1 < layers ? at + plane : -1;
        return y > 0 ? at - plane : -1;
    }

    public boolean hasGeometry(double x, double z) {
        if (x != Math.floor(x)
                || z != Math.floor(z)
                || Math.abs(x) > 29999984
                || Math.abs(z) > 29999984) return false;
        int cx = (int) Math.floor(x / sites.spacing), cz = (int) Math.floor(z / sites.spacing);
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                long id = sites.key(Sites.Lane.ROCKY, cx + dx, cz + dz);
                if (Math.hypot(x - sites.x(cx + dx, id), z - sites.z(cz + dz, id)) > radius)
                    continue;
                Decision d = plan(cx + dx, cz + dz);
                if (d.plan != null && d.plan.contains(x, z)) return true;
            }
        return false;
    }

    /** Volume transition boundaries one column can carry (see RockyCoasts.columnCuts). */
    public static final int MAXIMUM_CUTS = 4;

    public static final class Result {
        public double height, parentHeight, weight;
        public long id;
        public Family family;
        public boolean arch, emergent;
        public String reason;
        public int candidates, boxColumns, bodyColumns, voxels, cutCount;
        private RockyCoasts.ColumnPlan plan;
        private final Column column = new Column();
        private final int[] cuts = new int[MAXIMUM_CUTS];

        public void clear(double parent) {
            height = parentHeight = parent;
            weight = 0;
            id = 0;
            family = null;
            arch = emergent = false;
            reason = "NO_SITE";
            candidates = boxColumns = bodyColumns = voxels = cutCount = 0;
            plan = null;
        }

        public int boundary(int index, int fallback) {
            return index < cutCount ? cuts[index] : fallback;
        }

        public boolean hasVolume() {
            return plan != null;
        }
    }

    public static Edit edit(Result r, int y) {
        return r.plan == null ? Edit.KEEP : RockyCoasts.occupancyColumns(r.plan, r.column, y);
    }

    public void apply(double x, double z, TerrainMap.Sample sample) {
        Result out = sample.rocky;
        out.clear(sample.height);
        if (x != Math.floor(x)
                || z != Math.floor(z)
                || Math.abs(x) > 29999984
                || Math.abs(z) > 29999984) {
            out.reason = "COLUMN_DOMAIN";
            return;
        }
        int cx = (int) Math.floor(x / sites.spacing), cz = (int) Math.floor(z / sites.spacing);
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                long id = sites.key(Sites.Lane.ROCKY, cx + dx, cz + dz);
                if (Math.hypot(x - sites.x(cx + dx, id), z - sites.z(cz + dz, id)) > radius)
                    continue;
                Decision d = plan(cx + dx, cz + dz);
                out.candidates++;
                if (d.family != null) {
                    out.id = id;
                    out.family = d.family;
                    out.arch = d.arch;
                    out.reason = d.reason;
                    out.boxColumns = d.boxColumns;
                    out.bodyColumns = d.bodyColumns;
                    out.voxels = d.voxels;
                }
                if (d.plan == null || !d.plan.contains(x, z)) continue;
                RockyCoasts.sampleColumns(
                        d.plan,
                        x,
                        z,
                        sample.ocean.distance,
                        sample.rockyParentHeight,
                        sample.height,
                        protection(sample),
                        sample.ocean.marine,
                        reserved(sample),
                        out.column);
                out.reason = out.column.reason.name();
                out.height = out.column.height;
                out.weight = out.column.weight;
                if (out.column.reason == RockyCoasts.Reason.ACCEPTED
                        && out.weight > 0
                        && d.family == Family.STACK_ARCH) {
                    out.plan = d.plan;
                    out.cutCount = RockyCoasts.columnCuts(d.plan, out.column, out.cuts);
                    out.emergent = sample.ocean.marine && out.height >= reference.sea;
                }
                sample.height = out.height;
                sample.emergentRock = out.emergent;
                return;
            }
    }
}
