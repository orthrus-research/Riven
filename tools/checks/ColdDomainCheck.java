package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.cold.ColdRegions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Synthetic API/domain checks; no production occurrence or completed support certificate proof. */
public final class ColdDomainCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static void warm(TerrainMap.BaseSample out) {
        out.ocean.marine = false;
        out.shore.kind = Shore.Kind.INLAND;
        out.shore.landWeight = 1;
        out.shore.distance = Double.POSITIVE_INFINITY;
        out.water.clear();
        out.climate.celsius = 100;
    }

    private static void check(
            ColdTerrain terrain,
            TerrainMap.BaseSample base,
            ColdTerrain.Result result,
            List<String> rows,
            String role,
            double x,
            double z,
            double parent,
            String expected) {
        result.height = result.delta = result.weight = result.anchorX = result.anchorZ = 99;
        result.cold = result.relief = result.slope = 99;
        result.candidates = result.contributors = result.certificateColumns = 99;
        result.diagnosticColumns = result.rejectionBits = 99;
        result.id = 99;
        result.dominant = ColdRegions.Family.CIRQUE;
        result.reason = "STALE";
        terrain.apply(x, z, base, parent, result);
        require(result.reason.equals(expected), role + ": " + result.reason);
        require(bits(result.height).equals(bits(parent)), role + ": parent changed");
        require(
                result.delta == 0
                        && result.weight == 0
                        && result.anchorX == 0
                        && result.anchorZ == 0
                        && result.cold == 0
                        && result.relief == 0
                        && result.slope == 0
                        && result.candidates == 0
                        && result.contributors == 0
                        && result.certificateColumns == 0
                        && result.diagnosticColumns == 0
                        && result.rejectionBits == 0
                        && result.id == 0
                        && result.dominant == null,
                role + ": stale result survived guard");
        rows.add(
                role
                        + ","
                        + bits(x)
                        + ","
                        + bits(z)
                        + ","
                        + bits(parent)
                        + ","
                        + result.reason
                        + ","
                        + bits(result.height)
                        + ","
                        + bits(result.delta));
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Expected boundary/candidate and new CSV path");
        boolean guarded = args[0].equals("candidate");
        require(guarded || args[0].equals("boundary"), "Unsupported phase");
        long seed = 8675309;
        GenerationMaps maps = new GenerationMaps(seed);
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        Sites sites = maps.terrain.types.sites;
        int[] calls = new int[2];
        double[] anchor = new double[2];
        MajorFeatureParent earlier =
                (x, z, out) -> {
                    require(calls[1] == 1, "Anchor must follow water clearance");
                    calls[0]++;
                    anchor[0] = x;
                    anchor[1] = z;
                    out.height = 128;
                    warm(out);
                };
        BoundedWaterClearance water =
                (x, z, limit) -> {
                    calls[1]++;
                    return limit;
                };
        int width = maps.terrain.hydro.cellBlocks;
        ColdTerrain terrain = new ColdTerrain(seed, heights, sites, earlier, water, width);
        require(calls[0] == 0 && calls[1] == 0, "Constructor queried dependencies");
        boolean invalidWidth = false;
        try {
            new ColdTerrain(seed, heights, sites, earlier, water, 0);
        } catch (IllegalStateException expected) {
            invalidWidth =
                    expected.getMessage()
                            .equals(
                                    "Cold support/skirts must fit the finite hydrology clearance query");
        }
        require(invalidWidth && calls[0] == 0 && calls[1] == 0, "Width preflight changed");
        List<String> rows = new ArrayList<>();
        rows.add("role,xBits,zBits,parentBits,reason,heightBits,deltaBits");
        TerrainMap.BaseSample base = new TerrainMap.BaseSample();
        warm(base);
        ColdTerrain.Result result = new ColdTerrain.Result();
        check(terrain, base, result, rows, "INTEGER_WARM", 0, 0, 128, "WARM_COLUMN");
        check(terrain, base, result, rows, "NEGATIVE_INTEGER", -17, -16, 128, "WARM_COLUMN");
        check(terrain, base, result, rows, "SIGNED_ZERO", -0.0, 0, 128, "WARM_COLUMN");
        check(terrain, base, result, rows, "BOUND_POLICY", 30000000, 0, 128, "WARM_COLUMN");
        for (double[] point :
                new double[][] {
                    {.25, 0},
                    {0, -.5},
                    {Double.MIN_VALUE, 0},
                    {Double.NaN, 0},
                    {0, Double.POSITIVE_INFINITY},
                    {Double.NEGATIVE_INFINITY, 0}
                }) {
            check(
                    terrain,
                    base,
                    result,
                    rows,
                    "DOMAIN",
                    point[0],
                    point[1],
                    128,
                    guarded ? "COLUMN_DOMAIN" : "WARM_COLUMN");
        }
        base.ocean.marine = true;
        check(terrain, base, result, rows, "INTEGER_PROTECTED", 0, 0, 128, "COLUMN_PROTECTED");
        check(
                terrain,
                base,
                result,
                rows,
                "FRACTIONAL_PROTECTED",
                .25,
                0,
                128,
                guarded ? "COLUMN_DOMAIN" : "COLUMN_PROTECTED");
        check(
                terrain,
                base,
                result,
                rows,
                "INVALID_PARENT",
                .25,
                Double.NaN,
                Double.NaN,
                "INVALID_PARENT");
        require(calls[0] == 0 && calls[1] == 0, "Column guards queried a planner");
        int requests = 0;
        boolean reachedAnchor = false;
        search:
        for (int cz = 0; cz <= 512 && requests < 8; cz++)
            for (int cx = 0; cx <= 512 && requests < 8; cx++) {
                long key = sites.key(cx, cz);
                if (!sites.admits(cx, cz, key, Sites.Lane.COLD)) continue;
                requests++;
                calls[0] = calls[1] = 0;
                ColdTerrain.Decision decision = terrain.plan(cx, cz);
                if (calls[0] == 0) continue;
                require(
                        calls[0] == 1
                                && calls[1] == 1
                                && decision.certificateColumns == 1
                                && decision.diagnosticColumns == 0
                                && decision.reason.equals("COLD_TERRAIN_SUPPORT"),
                        "Warm anchor rejection changed");
                require(
                        bits(anchor[0]).equals(bits(sites.x(cx, key)))
                                && bits(anchor[1]).equals(bits(sites.z(cz, key)))
                                && (anchor[0] != Math.floor(anchor[0])
                                        || anchor[1] != Math.floor(anchor[1])),
                        "Earlier anchor lost its fractional coordinates");
                rows.add(
                        "EARLIER_ANCHOR,"
                                + bits(anchor[0])
                                + ","
                                + bits(anchor[1])
                                + ","
                                + bits(128)
                                + ","
                                + decision.reason
                                + ","
                                + bits(128)
                                + ",0");
                int[] blockedCalls = new int[2];
                ColdTerrain blocked =
                        new ColdTerrain(
                                seed,
                                heights,
                                sites,
                                (x, z, out) -> blockedCalls[0]++,
                                (x, z, limit) -> {
                                    blockedCalls[1]++;
                                    return 0;
                                },
                                width);
                ColdTerrain.Decision denied = blocked.plan(cx, cz);
                require(
                        denied.reason.equals("WATER_SUPPORT")
                                && blockedCalls[0] == 0
                                && blockedCalls[1] == 1
                                && denied.certificateColumns == 0,
                        "Water support must reject before the earlier anchor query");
                reachedAnchor = true;
                break search;
            }
        require(reachedAnchor, "No eligible synthetic anchor within eight plan requests");
        Files.write(
                Paths.get(args[1]),
                rows,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(
                "PASS Cold domain/port protocol phase="
                        + args[0]
                        + " requests="
                        + requests
                        + "; fractional earlier anchor preserved; synthetic facts only.");
    }
}
