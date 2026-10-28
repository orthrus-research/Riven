package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.carvers.SurfaceCuts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Injected analytic earlier-parent protocol; no actual-world occurrence or native proof. */
public final class GeneralCutsPortCheck {
    private static final long SEED = 8675309;
    private static final List<String> ROWS = new ArrayList<>();

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static final class Probe
            implements IntentSampler, MajorFeatureParent, BoundedWaterClearance {
        final TerrainHeightReference heights;
        final int width;
        int originX, originZ, intents, parents, waters;
        boolean blockedWater, marineIntent, wetParent;

        Probe(TerrainHeightReference heights, int width) {
            this.heights = heights;
            this.width = width;
        }

        double height(double x) {
            return 200 + 70 * Math.sin((x - originX) / 64.0);
        }

        void facts(TerrainMap.BaseSample out) {
            out.ocean.marine = false;
            out.shore.kind = Shore.Kind.INLAND;
            out.shore.landWeight = 1;
            out.shore.distance = Double.POSITIVE_INFINITY;
            out.water.clear();
            out.minimumY = heights.lowestSurface;
            out.maximumY = heights.highestSurface;
            out.climate.moisture = 0;
        }

        @Override
        public double surfaceClearance(double x, double z, double limit) {
            require(
                    waters == 0 && intents == 0 && parents == 0,
                    "Single bounded water preflight must precede all earlier reads");
            require(
                    Double.isFinite(limit) && limit > 0 && limit <= width,
                    "Water query exceeded the existing finite reach");
            waters++;
            return blockedWater ? 0 : limit;
        }

        @Override
        public void sample(double x, double z, TerrainMap.BaseSample out) {
            require(waters == 1 && parents >= 5, "Protection-only intent preceded water/gradient");
            require(x == Math.floor(x) && z == Math.floor(z), "Intent protection probe not native");
            intents++;
            facts(out);
            out.ocean.marine = marineIntent;
        }

        @Override
        public void sample(double x, double z, TerrainMap.Sample out) {
            require(waters == 1, "Earlier terrain preceded water preflight");
            require(x == Math.floor(x) && z == Math.floor(z), "Earlier terrain probe not native");
            if (parents < 5) {
                int[] dx = {0, 1, -1, 0, 0}, dz = {0, 0, 0, 1, -1};
                require(
                        x == originX + dx[parents] && z == originZ + dz[parents],
                        "Center and finite-difference query order changed");
            }
            parents++;
            facts(out);
            out.height = height(x);
            out.water.wet = wetParent;
        }

        void owner(Sites sites, int cx, int cz) {
            long key = sites.key(cx, cz);
            originX = (int) Math.round(sites.x(cx, key));
            originZ = (int) Math.round(sites.z(cz, key));
            intents = parents = waters = 0;
        }
    }

    private static GeneralSurfaceCuts create(Sites sites, Probe probe) {
        int intents = probe.intents, parents = probe.parents, waters = probe.waters;
        GeneralSurfaceCuts cuts =
                new GeneralSurfaceCuts(
                        SEED, probe.heights, sites, probe, probe, probe, probe.width);
        require(
                probe.intents == intents && probe.parents == parents && probe.waters == waters,
                "Constructor queried a dependency");
        return cuts;
    }

    private static String decisionFacts(GeneralSurfaceCuts.Decision d) {
        return bits(d.minParent)
                + "|"
                + bits(d.maxParent)
                + "|"
                + bits(d.shoreDistance)
                + "|"
                + bits(d.waterDistance)
                + "|"
                + bits(d.relief)
                + "|"
                + bits(d.saddleFit)
                + "|"
                + bits(d.saddleAxis)
                + "|"
                + d.supportColumns
                + "|"
                + d.protectedColumns
                + "|"
                + d.corridorColumns
                + "|"
                + d.probeColumns;
    }

    private static String resultFacts(GeneralSurfaceCuts.Result r) {
        return bits(r.height)
                + "|"
                + bits(r.cut)
                + "|"
                + bits(r.weight)
                + "|"
                + bits(r.anchorX)
                + "|"
                + bits(r.anchorZ)
                + "|"
                + bits(r.relief)
                + "|"
                + bits(r.saddleFit)
                + "|"
                + r.candidates
                + "|"
                + r.contributors
                + "|"
                + r.supportColumns
                + "|"
                + r.protectedColumns
                + "|"
                + r.corridorColumns
                + "|"
                + r.probeColumns;
    }

    private static void row(
            String role,
            int cx,
            int cz,
            String reason,
            long id,
            Object family,
            Probe p,
            String detail) {
        ROWS.add(
                role
                        + ","
                        + cx
                        + ","
                        + cz
                        + ","
                        + reason
                        + ","
                        + Long.toUnsignedString(id, 16)
                        + ","
                        + family
                        + ","
                        + p.intents
                        + ","
                        + p.parents
                        + ","
                        + p.waters
                        + ","
                        + detail);
    }

    private static void stale(GeneralSurfaceCuts.Result out) {
        out.height =
                out.cut = out.weight = out.anchorX = out.anchorZ = out.relief = out.saddleFit = 99;
        out.candidates = out.contributors = out.supportColumns = out.protectedColumns = 99;
        out.corridorColumns = out.probeColumns = 99;
        out.id = 99;
        out.dominant = SurfaceCuts.Family.RIBBON_RAVINE;
        out.reason = "STALE";
    }

    private static void early(
            GeneralSurfaceCuts cuts,
            Probe p,
            TerrainMap.Sample base,
            GeneralSurfaceCuts.Result out,
            int cx,
            int cz,
            double x,
            double z,
            double parent,
            String reason) {
        int intents = p.intents, parents = p.parents, waters = p.waters;
        stale(out);
        cuts.apply(x, z, base, parent, out);
        require(
                out.reason.equals(reason)
                        && bits(out.height).equals(bits(parent))
                        && out.cut == 0
                        && out.weight == 0
                        && out.anchorX == 0
                        && out.anchorZ == 0
                        && out.relief == 0
                        && out.saddleFit == 0
                        && out.candidates == 0
                        && out.contributors == 0
                        && out.supportColumns == 0
                        && out.protectedColumns == 0
                        && out.corridorColumns == 0
                        && out.probeColumns == 0
                        && out.id == 0
                        && out.dominant == null,
                "Early guard failed complete reset: " + reason);
        require(
                p.intents == intents && p.parents == parents && p.waters == waters,
                "Early application guard queried a dependency");
        row(
                "GUARD",
                cx,
                cz,
                out.reason,
                out.id,
                out.dominant,
                p,
                bits(x) + "|" + bits(z) + "|" + resultFacts(out));
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Expected new output CSV");
        GenerationMaps maps = new GenerationMaps(SEED);
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        Sites sites = maps.terrain.types.sites;
        int width = maps.terrain.hydro.cellBlocks;
        Probe p = new Probe(heights, width);
        GeneralSurfaceCuts cuts = create(sites, p);
        boolean rejectedWidth = false;
        try {
            new GeneralSurfaceCuts(SEED, heights, sites, p, p, p, 0);
        } catch (IllegalStateException expected) {
            rejectedWidth =
                    expected.getMessage()
                            .equals("General-cut water certificate exceeds finite clearance reach");
        }
        require(
                rejectedWidth && p.intents == 0 && p.parents == 0 && p.waters == 0,
                "Width preflight changed or queried dependencies");
        ROWS.add("role,cellX,cellZ,reason,id,family,intentCalls,parentCalls,waterCalls,detail");
        GeneralSurfaceCuts.Decision accepted = null;
        int cx = 0, cz = 0, requests = 0, completed = 0;
        search:
        for (int z = 0; z <= 128 && requests < 32; z++)
            for (int x = 0; x <= 128 && requests < 32; x++) {
                long key = sites.key(x, z);
                if (!sites.admits(x, z, key, Sites.Lane.CUTS)) continue;
                requests++;
                p.owner(sites, x, z);
                GeneralSurfaceCuts.Decision d = cuts.plan(x, z);
                if (d.supportColumns > 0) completed++;
                row(
                        "PLAN",
                        x,
                        z,
                        d.reason,
                        d.plan == null ? 0 : d.plan.proposal.id,
                        d.plan == null ? null : d.plan.proposal.family,
                        p,
                        decisionFacts(d));
                if (d.plan == null) continue;
                require(
                        d.reason.equals("ACCEPTED")
                                && p.waters == 1
                                && p.intents > 0
                                && d.supportColumns > 0
                                && d.protectedColumns > 0
                                && d.corridorColumns > 0
                                && d.probeColumns >= 5
                                && p.parents > d.probeColumns
                                && p.parents
                                        >= d.probeColumns
                                                + Math.max(d.supportColumns, d.corridorColumns)
                                && p.parents
                                        <= d.probeColumns + d.supportColumns + d.corridorColumns
                                && d.minParent >= 130
                                && d.maxParent <= 270
                                && d.minParent < d.maxParent
                                && d.relief > 0
                                && d.relief <= 140
                                && d.plan.depth > 0,
                        "Injected completed certificate does not contain real bounded earlier evidence");
                accepted = d;
                cx = x;
                cz = z;
                break search;
            }
        if (accepted == null) {
            row(
                    "SUMMARY",
                    0,
                    0,
                    "COVERAGE_GAP",
                    0,
                    null,
                    p,
                    "ownerRequests="
                            + requests
                            + "|completedCandidates="
                            + completed
                            + "|no-search-expansion|no-actual-world-sampling");
        } else {
            int intents = p.intents, parents = p.parents, waters = p.waters;
            require(
                    cuts.plan(cx, cz) == accepted
                            && p.intents == intents
                            && p.parents == parents
                            && p.waters == waters,
                    "Cached lookup queried dependencies");
            TerrainMap.Sample base = new TerrainMap.Sample();
            p.facts(base);
            GeneralSurfaceCuts.Result out = new GeneralSurfaceCuts.Result();
            SurfaceCuts.ColumnProposal proposal = accepted.plan.proposal;
            int selectedX = 0, selectedZ = 0, stencil = 0;
            double delivered = 0;
            boolean positive = false;
            interior:
            for (double fx : new double[] {0, -.125, .125, -.25, .25})
                for (double fz : new double[] {0, -.125, .125, -.25, .25}) {
                    stencil++;
                    int x = (int) Math.round(proposal.x + fx * proposal.width);
                    int z = (int) Math.round(proposal.z + fz * proposal.width);
                    double parent = p.height(x) + .25;
                    cuts.apply(x, z, base, parent, out);
                    require(
                            p.intents == intents && p.parents == parents && p.waters == waters,
                            "Application unexpectedly queried earlier providers");
                    if (out.cut <= 0) continue;
                    double distance = Math.hypot(x - proposal.x, z - proposal.z);
                    double expected =
                            accepted.plan.cut(
                                    x,
                                    z,
                                    parent,
                                    heights.lowestSurface,
                                    false,
                                    Math.max(0, accepted.shoreDistance - distance),
                                    Math.max(0, accepted.waterDistance - distance));
                    require(
                            out.reason.equals("ACCEPTED")
                                    && out.candidates == 1
                                    && out.contributors == 1
                                    && out.id == proposal.id
                                    && out.dominant == proposal.family
                                    && bits(out.cut).equals(bits(expected))
                                    && bits(out.height).equals(bits(parent - expected))
                                    && bits(out.weight)
                                            .equals(bits(expected / accepted.plan.depth)),
                            "Actual delivered parent or one-cut composition changed");
                    row(
                            "APPLY",
                            cx,
                            cz,
                            out.reason,
                            out.id,
                            out.dominant,
                            p,
                            bits(x) + "|" + bits(z) + "|" + bits(parent) + "|" + resultFacts(out));
                    selectedX = x;
                    selectedZ = z;
                    delivered = parent;
                    positive = true;
                    break interior;
                }
            require(
                    positive,
                    "Accepted controlled plan had no applied cut in 25 fixed interior probes");
            early(cuts, p, base, out, cx, cz, selectedX, selectedZ, Double.NaN, "INVALID_PARENT");
            early(
                    cuts,
                    p,
                    base,
                    out,
                    cx,
                    cz,
                    selectedX + .25,
                    selectedZ,
                    delivered,
                    "COLUMN_DOMAIN");
            early(cuts, p, base, out, cx, cz, 30000000, selectedZ, delivered, "COLUMN_DOMAIN");
            base.water.wet = true;
            early(cuts, p, base, out, cx, cz, selectedX, selectedZ, delivered, "COLUMN_PROTECTED");
            p.facts(base);
            double outsideParent = accepted.maxParent + 1;
            stale(out);
            cuts.apply(selectedX, selectedZ, base, outsideParent, out);
            require(
                    out.reason.equals("UNCERTIFIED_PARENT")
                            && out.height == outsideParent
                            && out.cut == 0
                            && out.weight == 0
                            && out.contributors == 0
                            && out.id == 0
                            && out.dominant == null
                            && out.supportColumns == accepted.supportColumns,
                    "Uncertified actual parent did not preserve its value and certificate metadata");
            row("UNCERTIFIED", cx, cz, out.reason, out.id, out.dominant, p, resultFacts(out));
            for (String control : new String[] {"WATER", "INTENT_SHORE", "PARENT_WATER"}) {
                Probe blocked = new Probe(heights, width);
                blocked.owner(sites, cx, cz);
                blocked.blockedWater = control.equals("WATER");
                blocked.marineIntent = control.equals("INTENT_SHORE");
                blocked.wetParent = control.equals("PARENT_WATER");
                GeneralSurfaceCuts.Decision d = create(sites, blocked).plan(cx, cz);
                String expected =
                        control.equals("WATER")
                                ? "WATER_SUPPORT"
                                : control.equals("INTENT_SHORE")
                                        ? "SHORE_SUPPORT"
                                        : "INVALID_PARENT";
                require(
                        d.plan == null && d.reason.equals(expected) && blocked.waters == 1,
                        "Controlled reservation query/rejection changed: " + control);
                if (control.equals("WATER"))
                    require(
                            blocked.parents == 0 && blocked.intents == 0 && d.probeColumns == 0,
                            "Blocked water did not stop before all earlier queries");
                else if (control.equals("INTENT_SHORE"))
                    require(
                            blocked.intents > 0 && blocked.parents >= 5,
                            "Protection-only ring did not read the intent port");
                else
                    require(
                            blocked.parents == 5 && blocked.intents == 0 && d.probeColumns == 5,
                            "Parent-water rejection did not preserve all five gradient reads");
                row(control, cx, cz, d.reason, 0, null, blocked, decisionFacts(d));
            }
            row(
                    "SUMMARY",
                    cx,
                    cz,
                    "PASS",
                    proposal.id,
                    proposal.family,
                    p,
                    "ownerRequests="
                            + requests
                            + "|completedCandidates="
                            + completed
                            + "|interiorProbes="
                            + stencil
                            + "|separatePlanControls=3"
                            + "|no-saddle-traversal-tie-or-actual-world-qualification");
        }
        Files.write(
                Paths.get(args[0]),
                ROWS,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(
                "Controlled GeneralCuts protocol: "
                        + (accepted == null ? "COVERAGE_GAP" : "PASS")
                        + "; requests="
                        + requests
                        + "; no actual-world sampling or search expansion.");
    }
}
