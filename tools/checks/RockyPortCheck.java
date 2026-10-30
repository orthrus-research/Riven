package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.rocky.RockyCoasts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Controlled restricted-reader checks; synthetic columns are not actual-world occurrence evidence. */
public final class RockyPortCheck {
    private static final long SEED = 8675309L;
    private static final int OWNER_CAP = 8;
    private static final double BROAD = 100, LAND = 92, BED = 40;
    private static final List<String> ROWS = new ArrayList<>();

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static String join(Object... values) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) {
            if (out.length() > 0) out.append('|');
            out.append(value);
        }
        return out.toString();
    }

    private static final class Probe
            implements IntentSampler, RockyCoastParent, BoundedWaterClearance {
        final TerrainHeightReference heights;
        final int width;
        double anchorX, anchorZ;
        String mode = "ELIGIBLE";
        int intents, parents, waters, phase;

        Probe(TerrainHeightReference heights, int width, double x, double z) {
            this.heights = heights;
            this.width = width;
            anchorX = x;
            anchorZ = z;
        }

        private void facts(double x, double z, TerrainMap.BaseSample out) {
            out.baseHeight = BROAD;
            out.minimumY = heights.lowestSurface;
            out.maximumY = heights.highestSurface;
            out.ocean.distance = x - anchorX;
            out.ocean.normalX = mode.equals("NO_NEAR_SHORE") ? 0 : 1;
            out.ocean.normalZ = 0;
            out.ocean.marine = x < anchorX && !mode.equals("SHORE_BRACKET");
            out.ocean.protectedWater = false;
            out.ocean.shelter = 0;
            out.shore.kind = out.ocean.marine ? Shore.Kind.OCEAN : Shore.Kind.ROCK;
            out.shore.landWeight = out.ocean.marine ? 0 : 1;
            out.shore.marsh = out.shore.beach = out.shore.plain = 0;
            out.shore.rock = out.shore.cliff = mode.equals("NO_ROCK_INTENT") ? 0 : 1;
            out.land.low = out.land.middle = 0;
            out.land.high = 1;
            out.land.rugged = mode.equals("NO_ROCK_INTENT") ? 0 : 1;
            out.water.clear();
            if (out.ocean.marine) out.water.waterY = heights.waterTopY;
        }

        @Override
        public void sample(double x, double z, TerrainMap.BaseSample out) {
            require(phase < 3, "Earlier intent queried after the canonical box began");
            require(Double.isFinite(x) && Double.isFinite(z), "Nonfinite intent coordinates");
            if (intents == 0)
                require(
                        bits(x).equals(bits(anchorX)) && bits(z).equals(bits(anchorZ)),
                        "Initial intent query must use the exact fractional owner anchor");
            intents++;
            facts(x, z, out);
        }

        @Override
        public void sample(double x, double z, TerrainMap.Sample out) {
            require(
                    waters == 1 && intents > 35 && x == Math.floor(x) && z == Math.floor(z),
                    "Canonical prefix must follow clearance/backing witnesses at integer columns");
            phase = 3;
            parents++;
            local(x, z, out);
            if (mode.equals("INVALID_PARENT")) out.height = Double.NaN;
        }

        private void local(double x, double z, TerrainMap.Sample out) {
            facts(x, z, out);
            out.rockyParentHeight = BROAD;
            out.height = out.ocean.marine ? BED : LAND;
            out.shore.height = out.height;
            out.emergentRock = false;
        }

        @Override
        public double surfaceClearance(double x, double z, double limit) {
            require(
                    intents == 35
                            && parents == 0
                            && waters == 0
                            && Double.isFinite(limit)
                            && limit > 0
                            && limit <= width
                            && Math.abs(x - anchorX) < 1e-5
                            && bits(z).equals(bits(anchorZ)),
                    "Clearance must follow anchor/bracket refinement at its relocated root");
            waters++;
            phase = 2;
            return mode.equals("WATER_SUPPORT") ? limit - 1 : limit;
        }
    }

    private static RockyTerrain create(Sites sites, Probe probe) {
        int before = probe.intents + probe.parents + probe.waters;
        RockyTerrain terrain =
                new RockyTerrain(
                        SEED,
                        probe.heights,
                        sites,
                        probe,
                        probe,
                        probe,
                        probe.width,
                        WorldScale.DEFAULT);
        require(
                probe.intents + probe.parents + probe.waters == before,
                "Constructor queried an earlier dependency");
        return terrain;
    }

    private static void row(
            String role, int cx, int cz, String reason, Object family, Probe p, String detail) {
        ROWS.add(
                role + "," + cx + "," + cz + "," + reason + "," + family + "," + p.intents + ","
                        + p.parents + "," + p.waters + "," + detail);
    }

    private static String decisionFacts(RockyTerrain.Decision d) {
        return join(
                d.arch,
                d.boxColumns,
                d.landColumns,
                d.witnessColumns,
                d.bodyColumns,
                d.bodyProbes,
                d.voxels,
                d.voidVoxels,
                bits(d.minimumLand),
                bits(d.maximumParent),
                bits(d.rock),
                bits(d.exposure),
                bits(d.foundation),
                bits(d.waterClearance),
                d.plan == null
                        ? "no-plan"
                        : join(
                                bits(d.plan.minX),
                                bits(d.plan.minZ),
                                bits(d.plan.maxX),
                                bits(d.plan.maxZ)));
    }

    private static String resultFacts(RockyTerrain.Result r) {
        return join(
                bits(r.height),
                bits(r.parentHeight),
                bits(r.weight),
                Long.toUnsignedString(r.id, 16),
                r.family,
                r.arch,
                r.emergent,
                r.reason,
                r.candidates,
                r.boxColumns,
                r.bodyColumns,
                r.voxels,
                r.cutCount,
                r.hasVolume(),
                r.boundary(0, -777),
                RockyTerrain.edit(r, 63));
    }

    private static void stale(RockyTerrain.Result r) {
        r.height = r.parentHeight = r.weight = 99;
        r.id = 99;
        r.family = RockyCoasts.Family.STACK_ARCH;
        r.arch = r.emergent = true;
        r.reason = "STALE";
        r.candidates = r.boxColumns = r.bodyColumns = r.voxels = r.cutCount = 3;
    }

    private static void cleared(RockyTerrain.Result r, double parent) {
        require(
                bits(r.height).equals(bits(parent))
                        && bits(r.parentHeight).equals(bits(parent))
                        && r.weight == 0
                        && r.id == 0
                        && r.family == null
                        && !r.arch
                        && !r.emergent
                        && r.reason.equals("COLUMN_DOMAIN")
                        && r.candidates == 0
                        && r.boxColumns == 0
                        && r.bodyColumns == 0
                        && r.voxels == 0
                        && r.cutCount == 0
                        && !r.hasVolume()
                        && r.boundary(0, -777) == -777
                        && RockyTerrain.edit(r, 63) == RockyCoasts.Edit.KEEP,
                "Domain guard must reset every public result and delegate occupancy");
    }

    private static void control(
            Sites sites, Probe source, int cx, int cz, String mode, String reason) {
        Probe p = new Probe(source.heights, source.width, source.anchorX, source.anchorZ);
        p.mode = mode;
        RockyTerrain.Decision d = create(sites, p).plan(cx, cz);
        require(d.plan == null && d.reason.equals(reason), "Controlled rejection changed: " + mode);
        require(p.parents == d.boxColumns + d.bodyProbes, "Canonical query accounting changed");
        if (mode.equals("NO_NEAR_SHORE"))
            require(p.intents == 1 && p.waters == 0, "Near-shore preflight read later inputs");
        else if (mode.equals("SHORE_BRACKET"))
            require(p.intents == 2 && p.waters == 0, "Bracket preflight query count changed");
        else require(p.waters == 1, "Controlled internal rejection omitted clearance");
        row(mode, cx, cz, d.reason, d.family, p, decisionFacts(d));
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Expected new output CSV path");
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        Sites sites = new Terrain(SEED, heights).sites;
        int width =
                (int)
                        Rules.number(
                                Rules.load("riven-hydrology.json", 1).json,
                                "cellBlocks",
                                2048,
                                8192);
        ROWS.add("role,cellX,cellZ,reason,family,intentCalls,parentCalls,waterCalls,detail");
        Probe preflight = new Probe(heights, 0, 0, 0);
        boolean rejectedWidth = false;
        try {
            create(sites, preflight);
        } catch (IllegalStateException expected) {
            rejectedWidth = expected.getMessage().equals("Rocky cache or hydrology reach");
        }
        require(
                rejectedWidth && preflight.intents + preflight.parents + preflight.waters == 0,
                "Width preflight changed or queried dependencies");
        row("WIDTH_PREFLIGHT", 0, 0, "REJECTED", null, preflight, "zero-callbacks");
        int requests = 0, selectedX = 0, selectedZ = 0;
        Probe selectedProbe = null, firstProbe = null;
        RockyTerrain selectedTerrain = null;
        RockyTerrain.Decision selected = null;
        int firstCx = 0, firstCz = 0;
        search:
        for (int cz = 0; cz <= 128; cz++)
            for (int cx = 0; cx <= 128; cx++) {
                long owner = sites.key(cx, cz);
                if (!sites.admits(cx, cz, owner, Sites.Lane.ROCKY)) continue;
                if (requests == OWNER_CAP) break search;
                requests++;
                Probe p = new Probe(heights, width, sites.x(cx, owner), sites.z(cz, owner));
                RockyTerrain terrain = create(sites, p);
                if (firstProbe == null) {
                    firstProbe = p;
                    firstCx = cx;
                    firstCz = cz;
                }
                RockyTerrain.Decision d = terrain.plan(cx, cz);
                require(p.parents == d.boxColumns + d.bodyProbes, "Parent accounting changed");
                row("PLAN", cx, cz, d.reason, d.family, p, decisionFacts(d));
                System.out.println(
                        "Controlled rocky owner="
                                + cx
                                + ","
                                + cz
                                + " reason="
                                + d.reason
                                + " family="
                                + d.family
                                + " parentCalls="
                                + p.parents);
                if (d.plan != null && d.family != RockyCoasts.Family.STACK_ARCH) {
                    selected = d;
                    selectedTerrain = terrain;
                    selectedProbe = p;
                    selectedX = cx;
                    selectedZ = cz;
                    break search;
                }
            }
        require(firstProbe != null, "No admitted ROCKY owner in the finite source domain");
        control(sites, firstProbe, firstCx, firstCz, "NO_NEAR_SHORE", "NO_NEAR_SHORE");
        control(sites, firstProbe, firstCx, firstCz, "SHORE_BRACKET", "SHORE_BRACKET");
        Probe domainProbe = new Probe(heights, width, firstProbe.anchorX, firstProbe.anchorZ);
        RockyTerrain domain = create(sites, domainProbe);
        for (double[] p :
                new double[][] {
                    {1.25, 2},
                    {1, 2.5},
                    {Double.NaN, 2},
                    {Double.POSITIVE_INFINITY, 2},
                    {30000000, 2}
                }) {
            TerrainMap.Sample s = new TerrainMap.Sample();
            s.height = LAND;
            stale(s.rocky);
            domain.apply(p[0], p[1], s);
            cleared(s.rocky, LAND);
            require(!domain.hasGeometry(p[0], p[1]), "Out-of-domain geometry became visible");
            require(
                    domainProbe.intents + domainProbe.parents + domainProbe.waters == 0,
                    "Domain guard queried earlier inputs");
            row(
                    "COLUMN_DOMAIN",
                    0,
                    0,
                    s.rocky.reason,
                    s.rocky.family,
                    domainProbe,
                    join(bits(p[0]), bits(p[1]), resultFacts(s.rocky)));
        }
        boolean applied = false;
        if (selected != null) {
            Probe p = selectedProbe;
            int before = p.intents + p.parents + p.waters;
            require(
                    selectedTerrain.plan(selectedX, selectedZ) == selected
                            && p.intents + p.parents + p.waters == before,
                    "Selected memo lookup queried an earlier dependency");
            row(
                    "MEMO",
                    selectedX,
                    selectedZ,
                    selected.reason,
                    selected.family,
                    p,
                    "selected-identity-and-zero-additional-callbacks");
            control(sites, p, selectedX, selectedZ, "WATER_SUPPORT", "WATER_SUPPORT");
            control(sites, p, selectedX, selectedZ, "NO_ROCK_INTENT", "NO_ROCK_INTENT");
            control(sites, p, selectedX, selectedZ, "INVALID_PARENT", "INVALID_PARENT");
            interior:
            for (int dx : new int[] {1, 8, -8})
                for (int dz : new int[] {0, 8, -8}) {
                    int x = (int) Math.floor(p.anchorX) + dx;
                    int z = (int) Math.floor(p.anchorZ) + dz;
                    if (!selected.plan.contains(x, z)) continue;
                    TerrainMap.Sample s = new TerrainMap.Sample();
                    p.local(x, z, s);
                    RockyCoasts.Column expected = new RockyCoasts.Column();
                    RockyCoasts.sampleColumns(
                            selected.plan,
                            x,
                            z,
                            s.ocean.distance,
                            s.rockyParentHeight,
                            s.height,
                            0,
                            s.ocean.marine,
                            false,
                            expected);
                    double parent = s.height;
                    selectedTerrain.apply(x, z, s);
                    require(
                            p.intents + p.parents + p.waters == before,
                            "Application at the selected cached support queried dependencies");
                    require(
                            bits(s.height).equals(bits(expected.height))
                                    && bits(s.rocky.height).equals(bits(expected.height))
                                    && bits(s.rocky.parentHeight).equals(bits(parent))
                                    && bits(s.rocky.weight).equals(bits(expected.weight))
                                    && s.rocky.reason.equals(expected.reason.name()),
                            "Application differs from the unchanged kernel on actual canonical parent");
                    if (s.rocky.weight <= 0 || bits(s.height).equals(bits(parent))) continue;
                    require(
                            !s.rocky.hasVolume()
                                    && s.rocky.id == sites.key(selectedX, selectedZ)
                                    && s.rocky.family == selected.family
                                    && s.rocky.arch == selected.arch
                                    && !s.rocky.emergent
                                    && s.rocky.candidates > 0
                                    && s.rocky.boxColumns == selected.boxColumns
                                    && s.rocky.bodyColumns == selected.bodyColumns
                                    && s.rocky.voxels == selected.voxels
                                    && s.rocky.cutCount == 0
                                    && RockyTerrain.edit(s.rocky, 63) == RockyCoasts.Edit.KEEP,
                            "Heightfield must retain earlier occupancy delegation");
                    row(
                            "APPLY_HEIGHTFIELD",
                            selectedX,
                            selectedZ,
                            s.rocky.reason,
                            s.rocky.family,
                            p,
                            join(x, z, bits(parent), bits(BROAD), resultFacts(s.rocky)));
                    applied = true;
                    break interior;
                }
        }
        row(
                "SUMMARY",
                selectedX,
                selectedZ,
                "PASS",
                selected == null ? null : selected.family,
                selectedProbe == null ? domainProbe : selectedProbe,
                "ownerRequests="
                        + requests
                        + "|ownerCap="
                        + OWNER_CAP
                        + "|acceptedHeightfield="
                        + (selected != null)
                        + "|appliedHeightfield="
                        + applied
                        + "|synthetic-only-no-volume-topology-or-world-occurrence-qualification");
        Files.write(
                Paths.get(args[0]),
                ROWS,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(
                "PASS bounded Rocky restricted-reader protocol; ownerRequests="
                        + requests
                        + "; acceptedHeightfield="
                        + (selected != null)
                        + "; appliedHeightfield="
                        + applied
                        + "; no search expansion or native/volume-topology/performance qualification.");
    }
}
