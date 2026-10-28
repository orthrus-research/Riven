package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.volcanic.VolcanicKernels;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Synthetic port protocol only; flat inputs do not qualify production occurrence or biome policy. */
public final class VolcanicPortCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void flat(TerrainMap.Sample out) {
        out.height = 128;
        out.ocean.marine = false;
        out.shore.kind = Shore.Kind.INLAND;
        out.shore.landWeight = 1;
        out.shore.distance = Double.POSITIVE_INFINITY;
        out.water.clear();
        out.uplift = 1;
        out.climate.temperatureBand = TemperatureBand.HOT;
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Expected a new output CSV path");
        long seed = 8675309;
        GenerationMaps maps = new GenerationMaps(seed);
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        Sites sites = maps.terrain.types.sites;
        int[] calls = new int[2];
        MajorFeatureParent parent =
                (x, z, out) -> {
                    require(calls[1] == 1, "Parent must follow one water-support query");
                    calls[0]++;
                    flat(out);
                };
        BoundedWaterClearance water =
                (x, z, limit) -> {
                    require(Double.isFinite(limit) && limit > 0, "Invalid water skirt");
                    calls[1]++;
                    return limit;
                };
        VolcanicTerrain terrain =
                new VolcanicTerrain(
                        seed, heights, sites, parent, water, maps.terrain.hydro.cellBlocks);
        require(calls[0] == 0 && calls[1] == 0, "Constructor must not query dependencies");
        boolean rejectedWidth = false;
        try {
            new VolcanicTerrain(seed, heights, sites, parent, water, 0);
        } catch (IllegalStateException expected) {
            rejectedWidth =
                    expected.getMessage()
                            .equals("Volcanic support must fit the finite water-clearance query");
        }
        require(rejectedWidth && calls[0] == 0 && calls[1] == 0, "Width guard changed");
        List<String> rows = new ArrayList<>();
        rows.add("role,cellX,cellZ,reason,columns,stencils,parentCalls,waterCalls,detail");
        int requests = 0;
        boolean applied = false;
        search:
        for (int cz = 0; cz <= 512 && requests < 64; cz++)
            for (int cx = 0; cx <= 512 && requests < 64; cx++) {
                if (!sites.admits(cx, cz, sites.key(cx, cz), Sites.Lane.VOLCANIC)) continue;
                requests++;
                calls[0] = calls[1] = 0;
                VolcanicTerrain.Decision decision = terrain.plan(cx, cz);
                require(calls[0] == decision.columns, "Certificate must count every parent query");
                require(calls[1] <= 1, "Repeated water query in one plan");
                rows.add(
                        "PLAN,"
                                + cx
                                + ","
                                + cz
                                + ","
                                + decision.reason
                                + ","
                                + decision.columns
                                + ","
                                + decision.stencils
                                + ","
                                + calls[0]
                                + ","
                                + calls[1]
                                + ","
                                + Long.toHexString(
                                        Double.doubleToRawLongBits(decision.maxColumnSlope)));
                VolcanicKernels.ColumnPlan plan = decision.plan;
                if (plan == null) continue;
                require(
                        decision.reason.equals("ACCEPTED") && decision.stencils > 0,
                        "Accepted geometry needs a completed stencil certificate");
                TerrainMap.Sample base = new TerrainMap.Sample();
                flat(base);
                VolcanicTerrain.Result result = new VolcanicTerrain.Result();
                for (double fraction : new double[] {0, .25, -.25, .5, -.5}) {
                    int x = (int) Math.floor(plan.proposal.x + plan.proposal.radius * fraction);
                    int z = (int) Math.floor(plan.proposal.z);
                    terrain.apply(x, z, base, 128, result);
                    if (result.delta == 0) continue;
                    require(
                            result.contributors > 0 && result.id == plan.proposal.seed,
                            "Controlled application lost its accepted identity");
                    require(
                            result.height == 128 + result.delta,
                            "Contribution applied incorrectly");
                    rows.add(
                            "APPLY,"
                                    + cx
                                    + ","
                                    + cz
                                    + ","
                                    + result.reason
                                    + ","
                                    + result.certificateColumns
                                    + ","
                                    + result.stencilColumns
                                    + ",NA,NA,"
                                    + Long.toHexString(Double.doubleToRawLongBits(result.delta)));
                    terrain.apply(x + .25, z, base, 128, result);
                    require(
                            result.reason.equals("COLUMN_DOMAIN")
                                    && result.id == 0
                                    && result.contributors == 0
                                    && result.delta == 0,
                            "Fractional call must reset the prior contribution");
                    terrain.apply(x, z, base, Double.NaN, result);
                    require(
                            result.reason.equals("INVALID_PARENT") && result.id == 0,
                            "Invalid parent guard changed");
                    base.ocean.marine = true;
                    terrain.apply(x, z, base, 128, result);
                    require(
                            result.reason.equals("COLUMN_PROTECTED") && result.id == 0,
                            "Protected column guard changed");
                    VolcanicTerrain blocked =
                            new VolcanicTerrain(
                                    seed,
                                    heights,
                                    sites,
                                    parent,
                                    (px, pz, limit) -> 0,
                                    maps.terrain.hydro.cellBlocks);
                    calls[0] = calls[1] = 0;
                    VolcanicTerrain.Decision denied = blocked.plan(cx, cz);
                    require(
                            denied.reason.equals("WATER_SUPPORT")
                                    && denied.columns == 0
                                    && calls[0] == 0,
                            "Water rejection must precede every parent query");
                    VolcanicTerrain warm =
                            new VolcanicTerrain(
                                    seed,
                                    heights,
                                    sites,
                                    (px, pz, out) -> {
                                        flat(out);
                                        out.climate.temperatureBand = TemperatureBand.WARM;
                                    },
                                    (px, pz, limit) -> limit,
                                    maps.terrain.hydro.cellBlocks);
                    require(
                            warm.plan(cx, cz).reason.equals("CLIMATE_SUPPORT"),
                            "Volcanic support outside the HOT band must be rejected");
                    rows.add("GUARDS," + cx + "," + cz + ",PASS,0,0,NA,NA,synthetic");
                    applied = true;
                    break search;
                }
            }
        require(applied, "No nonzero controlled relief within 64 explicit plan requests");
        Files.write(
                Paths.get(args[0]),
                rows,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(
                "PASS synthetic volcanic port protocol; requests="
                        + requests
                        + "; flat HOT parent and WARM rejection guard; no production occurrence.");
    }
}
