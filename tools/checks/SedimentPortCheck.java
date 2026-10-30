package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.coast.SedimentaryCoasts;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Bounded injected coastline and empty reservation geometry; no actual-world occurrence proof. */
public final class SedimentPortCheck {
    private static final long SEED = 8675309;
    private static final List<String> ROWS = new ArrayList<>();

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double n) {
        return Long.toHexString(Double.doubleToRawLongBits(n));
    }

    private static final class Probe
            implements IntentSampler, SedimentCoastParent, SurfaceWaterPlanSampler {
        final TerrainHeightReference heights;
        final Hydro.Plan empty;
        final int width;
        double shoreX;
        int intents, parents, plans;
        boolean noShore;

        Probe(TerrainHeightReference heights, Hydro.Plan empty, int width, double shoreX) {
            this.heights = heights;
            this.empty = empty;
            this.width = width;
            this.shoreX = shoreX;
        }

        private void facts(double x, TerrainMap.BaseSample out) {
            double d = x - shoreX;
            boolean marine = d <= 0;
            out.ocean.distance = d;
            out.ocean.normalX = noShore ? 0 : 1;
            out.ocean.normalZ = 0;
            out.ocean.marine = marine;
            out.ocean.supplied = marine;
            out.ocean.protectedWater = false;
            out.ocean.landWeight = marine ? 0 : 1;
            out.ocean.shelter = 1;
            out.minimumY = heights.lowestSurface;
            out.maximumY = heights.highestSurface;
            out.baseHeight = marine ? heights.waterTopY - 6 : heights.seaLevel + 1;
            out.broadDx = out.broadDz = 0;
            out.land.low = 1;
            out.land.middle = out.land.high = out.land.rugged = 0;
            out.shore.kind = marine ? Shore.Kind.OCEAN : Shore.Kind.BEACH;
            out.shore.landWeight = marine ? 0 : 1;
            out.shore.beach = 1;
            out.water.clear();
            out.water.waterY = marine ? heights.waterTopY : Integer.MIN_VALUE;
        }

        @Override
        public void sample(double x, double z, TerrainMap.BaseSample out) {
            require(Double.isFinite(x) && Double.isFinite(z), "Nonfinite intent query");
            intents++;
            facts(x, out);
        }

        @Override
        public void sample(double x, double z, TerrainMap.Sample out) {
            require(
                    x == Math.floor(x) && z == Math.floor(z),
                    "Sediment prefix requires integer columns");
            require(intents > 0, "Prefix requested before anchor intent");
            parents++;
            facts(x, out);
            out.height = out.rockyParentHeight = out.baseHeight;
            out.rocky.clear(out.height);
            out.sediment.clear(out.height);
        }

        @Override
        public Hydro.Plan plan(int x, int z) {
            require(parents > 0, "Reservation geometry requested before earlier attachment");
            plans++;
            return empty;
        }
    }

    private static SedimentTerrain create(Sites sites, Probe p, double bank) {
        int before = p.intents + p.parents + p.plans;
        SedimentTerrain terrain =
                new SedimentTerrain(
                        SEED, p.heights, sites, p, p, p, p.width, bank, WorldScale.DEFAULT);
        require(before == p.intents + p.parents + p.plans, "Constructor queried a dependency");
        return terrain;
    }

    private static void row(
            String role, int cx, int cz, String reason, Object family, Probe p, String detail) {
        ROWS.add(
                role + "," + cx + "," + cz + "," + reason + "," + family + "," + p.intents + ","
                        + p.parents + "," + p.plans + "," + detail);
    }

    private static void stale(TerrainMap.Sample s) {
        s.height = 71;
        s.sediment.height = s.sediment.parentHeight = s.sediment.weight = 99;
        s.sediment.emergentSediment = true;
        s.sediment.id = 99;
        s.sediment.family = SedimentaryCoasts.Family.SPIT;
        s.sediment.reason = "STALE";
        s.sediment.candidates =
                s.sediment.boxColumns =
                        s.sediment.activeColumns =
                                s.sediment.maskedColumns =
                                        s.sediment.probeColumns = s.sediment.islandColumns = 99;
    }

    private static void guard(SedimentTerrain terrain, Probe p, double x, double z) {
        int before = p.intents + p.parents + p.plans;
        TerrainMap.Sample s = new TerrainMap.Sample();
        stale(s);
        terrain.apply(x, z, s);
        SedimentTerrain.Result r = s.sediment;
        require(
                s.height == 71
                        && r.height == 71
                        && r.parentHeight == 71
                        && r.weight == 0
                        && r.id == 0
                        && r.family == null
                        && !r.emergentSediment
                        && r.reason.equals("COLUMN_DOMAIN")
                        && r.candidates == 0
                        && r.boxColumns == 0
                        && r.activeColumns == 0
                        && r.maskedColumns == 0
                        && r.probeColumns == 0
                        && r.islandColumns == 0,
                "Domain/reset semantics changed");
        require(
                !terrain.hasGeometry(x, z) && before == p.intents + p.parents + p.plans,
                "Domain guard queried dependencies");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Expected new output path");
        GenerationMaps maps = new GenerationMaps(SEED);
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        JsonObject ownership =
                new Gson()
                        .fromJson(
                                Rules.load("riven-terrain.json", 1)
                                        .json
                                        .getAsJsonObject("sites")
                                        .toString(),
                                JsonObject.class);
        // Explicit injected ownership fixture; the active definition is never mutated.
        for (String share :
                new String[] {
                    "carverShare",
                    "cutsShare",
                    "hillsShare",
                    "mountainShare",
                    "plateauShare",
                    "coldShare",
                    "volcanicShare",
                    "rockyShare"
                }) ownership.addProperty(share, 0);
        ownership.addProperty("sedimentShare", 1);
        ownership.addProperty("anchorProbability", .02);
        Sites sites = Sites.create(SEED, heights, ownership);
        int width = maps.terrain.hydro.cellBlocks;
        double bank = maps.terrain.hydro.bankScale;
        // The unchanged Hydro world-bound guard supplies an immutable empty geometry fixture.
        Hydro.Plan empty = maps.terrain.hydro.plan(Integer.MAX_VALUE, Integer.MAX_VALUE);
        require(
                !empty.present() && empty.basins() == 0,
                "Expected empty out-of-world plan fixture");
        ROWS.add("role,cellX,cellZ,reason,family,intentCalls,parentCalls,planCalls,detail");
        Probe guardProbe = new Probe(heights, empty, width, 0);
        SedimentTerrain guarded = create(sites, guardProbe, bank);
        boolean rejectedWidth = false;
        try {
            new SedimentTerrain(
                    SEED,
                    heights,
                    sites,
                    guardProbe,
                    guardProbe,
                    guardProbe,
                    0,
                    bank,
                    WorldScale.DEFAULT);
        } catch (IllegalStateException expected) {
            rejectedWidth =
                    expected.getMessage().equals("Sediment cache or finite hydrology reach");
        }
        require(
                rejectedWidth && guardProbe.intents + guardProbe.parents + guardProbe.plans == 0,
                "Width preflight changed");
        guard(guarded, guardProbe, .25, 0);
        guard(guarded, guardProbe, 30000000, 0);
        row(
                "GUARDS",
                0,
                0,
                "COLUMN_DOMAIN",
                null,
                guardProbe,
                "fractional-world-bound-reset;empty-fixture-no-production-water-coverage");
        int requests = 0, accepted = 0, geometryReads = 0, positive = 0;
        search:
        for (int z = 0; z <= 128; z++)
            for (int x = 0; x <= 128; x++) {
                long owner = sites.key(x, z);
                if (!sites.admits(x, z, owner, Sites.Lane.SEDIMENT)) continue;
                if (requests == 8) break search;
                requests++;
                Probe p = new Probe(heights, empty, width, sites.x(x, owner));
                SedimentTerrain terrain = create(sites, p, bank);
                SedimentTerrain.Decision d = terrain.plan(x, z);
                require(p.intents > 0, "Admitted owner skipped anchor intent");
                int before = p.intents + p.parents + p.plans;
                require(
                        terrain.plan(x, z) == d && before == p.intents + p.parents + p.plans,
                        "Selected memo hit queried dependencies");
                geometryReads += p.plans;
                if (d.plan != null) accepted++;
                row(
                        "PLAN",
                        x,
                        z,
                        d.reason,
                        d.family,
                        p,
                        "box="
                                + d.boxColumns
                                + "|active="
                                + d.activeColumns
                                + "|probes="
                                + d.probeColumns
                                + "|island="
                                + d.islandColumns);
                if (d.plan != null) {
                    TerrainMap.Sample s = new TerrainMap.Sample();
                    double px = Math.ceil(p.shoreX), pz = Math.floor(sites.z(z, owner));
                    p.sample(px, pz, s);
                    int intents = p.intents, parents = p.parents, plans = p.plans;
                    terrain.apply(px, pz, s);
                    require(
                            Double.isFinite(s.height)
                                    && p.intents == intents
                                    && p.parents == parents
                                    && p.plans == plans,
                            "Warm application queried earlier dependencies");
                    if (s.sediment.weight > 0) positive++;
                    row(
                            "APPLICATION",
                            x,
                            z,
                            s.sediment.reason,
                            s.sediment.family,
                            p,
                            bits(s.height) + "|weight=" + bits(s.sediment.weight));
                }
                Probe noShore = new Probe(heights, empty, width, sites.x(x, owner));
                noShore.noShore = true;
                SedimentTerrain.Decision denied = create(sites, noShore, bank).plan(x, z);
                require(
                        denied.plan == null
                                && denied.reason.equals("NO_NEAR_SHORE")
                                && noShore.intents == 1
                                && noShore.parents == 0
                                && noShore.plans == 0,
                        "Anchor rejection queried later dependencies");
                row(
                        "ANCHOR_REJECT",
                        x,
                        z,
                        denied.reason,
                        denied.family,
                        noShore,
                        "no-prefix-or-owner-plan-lookup");
            }
        require(requests > 0, "No admitted sediment owners within bounded cells");
        ROWS.add(
                "SUMMARY,0,0,PASS,null,0,0,"
                        + geometryReads
                        + ",requests="
                        + requests
                        + "|accepted="
                        + accepted
                        + "|positive="
                        + positive
                        + "|empty-reservations-only-no-topology-or-occurrence-proof");
        Files.write(
                Paths.get(args[0]), ROWS, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(
                "PASS controlled Sediment protocol: requests="
                        + requests
                        + " accepted="
                        + accepted
                        + " positive="
                        + positive
                        + " geometryReads="
                        + geometryReads
                        + "; empty reservation fixture only; no actual-world/native/continuous/topology qualification.");
    }
}
