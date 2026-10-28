package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.plateau.PlateauKernels;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Controlled adapter certificate and query protocol; no production occurrence or topology proof. */
public final class PlateauPortCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static void flat(TerrainMap.Sample out) {
        out.height = 128;
        out.ocean.marine = false;
        out.shore.kind = Shore.Kind.INLAND;
        out.shore.landWeight = 1;
        out.shore.distance = Double.POSITIVE_INFINITY;
        out.water.clear();
        out.land.middle = 1;
        out.land.low = out.land.high = out.land.rugged = 0;
        out.climate.moisture = 0;
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Expected a new output CSV path");
        long seed = 8675309;
        GenerationMaps maps = new GenerationMaps(seed);
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        Sites sites = maps.terrain.types.sites;
        int width = maps.terrain.hydro.cellBlocks;
        int[] calls = new int[2];
        double[] anchor = new double[2];
        MajorFeatureParent earlier =
                (x, z, out) -> {
                    if (calls[0] == 0) {
                        require(calls[1] == 0, "Anchor must precede clearance");
                        anchor[0] = x;
                        anchor[1] = z;
                    } else {
                        require(
                                calls[1] == 1 && x == Math.floor(x) && z == Math.floor(z),
                                "Support queries must be integer columns after anchor clearance");
                    }
                    calls[0]++;
                    flat(out);
                };
        BoundedWaterClearance water =
                (x, z, limit) -> {
                    require(
                            Double.isFinite(limit) && limit > 0 && limit <= width,
                            "Invalid bounded skirt");
                    calls[1]++;
                    return limit;
                };
        PlateauTerrain terrain = new PlateauTerrain(seed, heights, sites, earlier, water, width);
        require(calls[0] == 0 && calls[1] == 0, "Constructor queried dependencies");
        boolean rejectedWidth = false;
        try {
            new PlateauTerrain(seed, heights, sites, earlier, water, 0);
        } catch (IllegalStateException expected) {
            rejectedWidth =
                    expected.getMessage()
                            .equals("Plateau preservation skirt must fit a hydrology owner width");
        }
        require(rejectedWidth && calls[0] == 0 && calls[1] == 0, "Width preflight changed");
        List<String> rows = new ArrayList<>();
        rows.add("role,cellX,cellZ,reason,columns,parentCalls,waterCalls,detail");
        int requests = 0;
        boolean complete = false;
        search:
        for (int cz = 0; cz <= 128 && requests < 8; cz++)
            for (int cx = 0; cx <= 128 && requests < 8; cx++) {
                long key = sites.key(cx, cz);
                if (!sites.admits(cx, cz, key, Sites.Lane.PLATEAUS)) continue;
                requests++;
                calls[0] = calls[1] = 0;
                PlateauTerrain.Decision decision = terrain.plan(cx, cz);
                require(calls[0] == decision.certificateColumns, "Parent-query count mismatch");
                rows.add(
                        "PLAN,"
                                + cx
                                + ","
                                + cz
                                + ","
                                + decision.reason
                                + ","
                                + decision.certificateColumns
                                + ","
                                + calls[0]
                                + ","
                                + calls[1]
                                + ","
                                + bits(decision.parentMin)
                                + "|"
                                + bits(decision.parentMax));
                if (decision.plan == null || decision.plan.reason != PlateauKernels.Reason.ACCEPT)
                    continue;
                require(
                        calls[1] == 1 && decision.parentMin == 128 && decision.parentMax == 128,
                        "Controlled certificate changed");
                require(
                        bits(anchor[0]).equals(bits(sites.x(cx, key)))
                                && bits(anchor[1]).equals(bits(sites.z(cz, key)))
                                && (anchor[0] != Math.floor(anchor[0])
                                        || anchor[1] != Math.floor(anchor[1])),
                        "Fractional earlier anchor changed");
                int parentReads = calls[0], waterReads = calls[1];
                require(
                        terrain.plan(cx, cz) == decision
                                && calls[0] == parentReads
                                && calls[1] == waterReads,
                        "Immediate cached lookup queried dependencies");
                TerrainMap.Sample base = new TerrainMap.Sample();
                flat(base);
                PlateauTerrain.Result result = new PlateauTerrain.Result();
                int x = (int) Math.floor(anchor[0]), z = (int) Math.floor(anchor[1]);
                terrain.apply(x, z, base, 128, result);
                require(
                        calls[0] == parentReads && calls[1] == waterReads + 1,
                        "Local reservation query/cache order changed");
                require(
                        Double.isFinite(result.height) && result.height == 128 + result.delta,
                        "Controlled application composition changed");
                rows.add(
                        "INTEGER,"
                                + cx
                                + ","
                                + cz
                                + ","
                                + result.reason
                                + ","
                                + result.certificateColumns
                                + ","
                                + (calls[0] - parentReads)
                                + ","
                                + (calls[1] - waterReads)
                                + ","
                                + bits(result.delta));
                terrain.apply(anchor[0] + .25, anchor[1] + .5, base, 128, result);
                require(
                        Double.isFinite(result.height) && !result.reason.equals("COLUMN_DOMAIN"),
                        "Existing fractional application policy changed");
                rows.add(
                        "FRACTIONAL,"
                                + cx
                                + ","
                                + cz
                                + ","
                                + result.reason
                                + ","
                                + result.certificateColumns
                                + ",NA,NA,"
                                + bits(result.delta));
                terrain.apply(x, z, base, 129, result);
                require(
                        result.reason.equals("UNCERTIFIED_PARENT")
                                && result.height == 129
                                && result.delta == 0
                                && result.id == 0,
                        "Uncertified parent was modified");
                int beforeWater = calls[1];
                base.water.wet = true;
                terrain.apply(x, z, base, 128, result);
                require(
                        result.reason.equals("COLUMN_PROTECTED")
                                && result.delta == 0
                                && result.contributors == 0
                                && result.id == 0
                                && calls[1] == beforeWater,
                        "Wet local column must short-circuit clearance and reset results");
                terrain.apply(x, z, base, Double.NaN, result);
                require(
                        result.reason.equals("INVALID_PARENT") && calls[1] == beforeWater,
                        "Invalid parent must precede reservations");
                for (boolean wet : new boolean[] {false, true}) {
                    int[] earlyCalls = new int[2];
                    PlateauTerrain blocked =
                            new PlateauTerrain(
                                    seed,
                                    heights,
                                    sites,
                                    (px, pz, out) -> {
                                        earlyCalls[0]++;
                                        flat(out);
                                        out.water.wet = wet;
                                    },
                                    (px, pz, limit) -> {
                                        earlyCalls[1]++;
                                        return 0;
                                    },
                                    width);
                    PlateauTerrain.Decision denied = blocked.plan(cx, cz);
                    require(
                            denied.reason.equals("WATER_RESERVED")
                                    && denied.certificateColumns == 1
                                    && earlyCalls[0] == 1
                                    && earlyCalls[1] == (wet ? 0 : 1),
                            "Anchor reservation short-circuit/order changed");
                    rows.add(
                            "WATER_"
                                    + wet
                                    + ","
                                    + cx
                                    + ","
                                    + cz
                                    + ","
                                    + denied.reason
                                    + ",1,"
                                    + earlyCalls[0]
                                    + ","
                                    + earlyCalls[1]
                                    + ",synthetic");
                }
                complete = true;
                break search;
            }
        require(complete, "No controlled certificate within eight explicit plan requests");
        Files.write(
                Paths.get(args[0]),
                rows,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println(
                "PASS synthetic Plateau port/certificate protocol; search requests="
                        + requests
                        + "; two separate water controls; no production occurrence qualification.");
    }
}
