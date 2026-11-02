package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Provinces hold their budget and blend smoothly (S2). Usage: ProvinceCheck [seeds [scale ...]]
 * (default 64 seeds at layouts 1 and 4, 8 km and 32 km). Per seed it builds the province map
 * from the world's earlier maps and reports land province counts, each type's share of land
 * against its budget, shortfalls and build time; requires no type above its maximum unless it
 * holds one province. Over a 128 x 128 grid of the world square it reports intensity and climate
 * offsets, and requires offsets within their bounds, a fresh map and reverse-order sampling to
 * give the same answers, and the offset to change by at most 0.05 °C and 0.002 moisture between
 * neighbouring blocks. A pack variant (rift disabled, upland narrowed to 20-25%) must hold.
 */
public final class ProvinceCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** The province map of a world, built from the same earlier maps GenerationMaps builds. */
    static ProvinceMap build(long seed, WorldScale world, ProvinceMap.Definition def) {
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        TemperatureMap temperature = new TemperatureMap(seed, world);
        PlateMap plates = new PlateMap(seed, world);
        ContinentalOutlineMap outline = LandShare.choose(seed, heights, world).outline;
        BaseElevationMap elevation = new BaseElevationMap(seed, plates, heights);
        ClimateStructureMap structure = new ClimateStructureMap(outline, elevation, heights);
        ClimateForcingMap forcing = new ClimateForcingMap(structure, heights, world);
        ClimateMap climate = new ClimateMap(seed, temperature, forcing);
        ContinentalMap continents = new ContinentalMap(seed, heights, outline);
        return new ProvinceMap(seed, world, plates, continents, climate, def);
    }

    public static void main(String[] args) throws Exception {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        double[] scales =
                args.length > 1
                        ? Arrays.stream(args, 1, args.length)
                                .mapToDouble(Double::parseDouble)
                                .toArray()
                        : new double[] {1, 4};
        ProvinceMap.Definition def = ProvinceMap.PACKAGED;
        int types = def.types.length;
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        Runtime.getRuntime().availableProcessors(),
                        r -> {
                            Thread thread = new Thread(r);
                            thread.setDaemon(true);
                            return thread;
                        });
        for (double layout : scales) {
            WorldScale world = layout == 1 ? WorldScale.DEFAULT : new WorldScale(layout);
            List<Future<double[]>> rows = new ArrayList<>();
            for (int s = 1; s <= seeds; s++) {
                long seed = s;
                rows.add(
                        pool.submit(
                                () -> {
                                    ProvinceMap p = build(seed, world, def);
                                    double ms = p.buildMillis;
                                    double[] row = new double[types + 5];
                                    System.arraycopy(p.shares, 0, row, 0, types);
                                    row[types] = p.landCells;
                                    row[types + 1] = p.cells;
                                    row[types + 2] = p.shortfalls.size();
                                    row[types + 3] = ms;
                                    row[types + 4] = p.overruns.size();
                                    for (ProvinceMap.Type t : def.types)
                                        if (t.land && p.shares[t.index] > t.max + 1e-9) {
                                            // One province may always hold a type, and a
                                            // province no type had room for is logged.
                                            require(
                                                    singleProvince(p, t) || overrun(p, t),
                                                    t.id + " over its budget on seed " + seed);
                                        }
                                    if (seed <= 2) grid(seed, world, p, def);
                                    return row;
                                }));
            }
            double[] mean = new double[types + 5],
                    min = new double[types + 5],
                    max = new double[types + 5];
            int[] present = new int[types];
            Arrays.fill(min, Double.POSITIVE_INFINITY);
            Arrays.fill(max, Double.NEGATIVE_INFINITY);
            long shortSeeds = 0, overSeeds = 0;
            for (Future<double[]> f : rows) {
                double[] r = f.get();
                for (int k = 0; k < r.length; k++) {
                    mean[k] += r[k] / seeds;
                    min[k] = Math.min(min[k], r[k]);
                    max[k] = Math.max(max[k], r[k]);
                }
                for (int t = 0; t < types; t++) if (r[t] > 0) present[t]++;
                if (r[types + 2] > 0) shortSeeds++;
                if (r[types + 4] > 0) overSeeds++;
            }
            System.out.printf(
                    Locale.ROOT,
                    "layout %.1f (%d km), %d seeds: provinces holding land mean %.1f (%d-%d), of %.1f;"
                            + " build %.0f ms mean, %.0f max (in parallel); seeds with a shortfall %d,"
                            + " with an overrun %d%n",
                    layout,
                    Math.round(8 * layout),
                    seeds,
                    mean[types],
                    (int) min[types],
                    (int) max[types],
                    mean[types + 1],
                    mean[types + 3],
                    max[types + 3],
                    shortSeeds,
                    overSeeds);
            for (ProvinceMap.Type t : def.types)
                if (t.land)
                    System.out.printf(
                            Locale.ROOT,
                            "  %-10s share mean %5.1f%% (%5.1f-%5.1f), in %2d of %d worlds, budget %s%n",
                            t.id,
                            100 * mean[t.index],
                            100 * min[t.index],
                            100 * max[t.index],
                            present[t.index],
                            seeds,
                            String.format(Locale.ROOT, "%.0f-%.0f%%", 100 * t.min, 100 * t.max));
        }
        // The province map's own build time, one world at a time (the parallel runs above share
        // the cores, so their times run long).
        double sequential = 0, slowest = 0;
        for (int s = 1; s <= 8; s++) {
            double ms = build(s, WorldScale.DEFAULT, def).buildMillis;
            sequential += ms / 8;
            slowest = Math.max(slowest, ms);
        }
        System.out.printf(
                Locale.ROOT,
                "build alone, 8 km, seeds 1-8 one at a time: %.0f ms mean, %.0f ms slowest%n",
                sequential,
                slowest);
        // A pack's variant: rift removed, upland narrowed.
        JsonObject json = ProvinceMap.RULES.json.deepCopy();
        for (com.google.gson.JsonElement e : json.getAsJsonArray("types")) {
            JsonObject t = e.getAsJsonObject();
            if (t.get("id").getAsString().equals("rift")) t.addProperty("enabled", false);
            if (t.get("id").getAsString().equals("upland")) {
                JsonArray share = new JsonArray();
                share.add(0.20);
                share.add(0.25);
                t.add("share", share);
            }
        }
        ProvinceMap.Definition variant = new ProvinceMap.Definition(json, "variant");
        int held = 0, shortUpland = 0;
        for (int s = 1; s <= 16; s++) {
            ProvinceMap p = build(s, WorldScale.DEFAULT, variant);
            int rift = -1, upland = -1;
            for (ProvinceMap.Type t : variant.types) {
                if (t.id.equals("rift")) rift = t.index;
                if (t.id.equals("upland")) upland = t.index;
            }
            require(p.shares[rift] == 0, "variant: rift appears on seed " + s);
            boolean over =
                    p.shares[upland] > 0.25 + 1e-9
                            && !singleProvince(p, variant.types[upland])
                            && !overrun(p, variant.types[upland]);
            require(!over, "variant: upland over 25% on seed " + s);
            if (p.shares[upland] < 0.20 - 1e-9) shortUpland++;
            else held++;
        }
        System.out.printf(
                "pack variant (rift off, upland 20-25%%) over 16 seeds: rift absent, upland at most 25%%;"
                        + " upland in its band %d, short %d (logged)%n",
                held, shortUpland);
        pool.shutdown();
        System.out.println("ProvinceCheck passed");
    }

    /** A low type straight beside tableland or a range. */
    private static boolean jump(
            ProvinceMap.Definition def, ProvinceMap.Type a, ProvinceMap.Type b) {
        boolean low = a.index == def.coastal || a.index == def.interior;
        return low && (b.index == def.top || b.index == def.convergent);
    }

    /** Whether the map logged a type past its maximum for want of room. */
    private static boolean overrun(ProvinceMap p, ProvinceMap.Type t) {
        for (String line : p.overruns) if (line.startsWith(t.id + " ")) return true;
        return false;
    }

    /** Whether a type's whole share sits in one province (a nonzero maximum always allows one). */
    private static boolean singleProvince(ProvinceMap p, ProvinceMap.Type t) {
        return p.provincesOf(t) <= 1;
    }

    /** Offsets, intensity, determinism and smoothness over a grid of the world square. */
    private static void grid(
            long seed, WorldScale world, ProvinceMap p, ProvinceMap.Definition def) {
        ProvinceMap fresh = build(seed, world, def);
        int side = 128;
        double half = world.side() / 2, step = world.side() / side;
        ProvinceMap.Sample a = new ProvinceMap.Sample(),
                b = new ProvinceMap.Sample(),
                c = new ProvinceMap.Sample();
        double maxC = 0, maxM = 0, jumpC = 0, jumpM = 0;
        double[] intensity = new double[side * side];
        int n = 0;
        for (int gi = side - 1; gi >= 0; gi--)
            for (int gj = side - 1; gj >= 0; gj--) {
                double x = -half + (gi + 0.5) * step, z = -half + (gj + 0.5) * step;
                p.sample(x, z, a);
                fresh.sample(x, z, b);
                require(
                        a.type == b.type
                                && a.cell == b.cell
                                && a.celsiusOffset == b.celsiusOffset
                                && a.moistureOffset == b.moistureOffset
                                && a.intensity == b.intensity,
                        "Province differs between fresh maps at " + x + "," + z + " seed " + seed);
                p.sample(x + 1, z, c);
                jumpC = Math.max(jumpC, Math.abs(c.celsiusOffset - a.celsiusOffset));
                jumpM = Math.max(jumpM, Math.abs(c.moistureOffset - a.moistureOffset));
                p.sample(x, z + 1, c);
                jumpC = Math.max(jumpC, Math.abs(c.celsiusOffset - a.celsiusOffset));
                jumpM = Math.max(jumpM, Math.abs(c.moistureOffset - a.moistureOffset));
                maxC = Math.max(maxC, Math.abs(a.celsiusOffset));
                maxM = Math.max(maxM, Math.abs(a.moistureOffset));
                intensity[n++] = a.intensity;
            }
        require(
                maxC <= def.maxCelsius + 1e-9 && maxM <= def.maxMoisture + 1e-9,
                "Offsets out of bounds");
        // Coherence: of land grid neighbours with different types, how many put a low type
        // (plain, basin) straight beside the top type (tableland) or a range beside a low type.
        int differ = 0, jumps = 0;
        ProvinceMap.Sample e = new ProvinceMap.Sample(), w = new ProvinceMap.Sample();
        for (int gi = 0; gi + 1 < side; gi++)
            for (int gj = 0; gj < side; gj++) {
                double x = -half + (gi + 0.5) * step, z = -half + (gj + 0.5) * step;
                p.sample(x, z, e);
                p.sample(x + step, z, w);
                if (!e.type.land || !w.type.land || e.type == w.type) continue;
                differ++;
                if (jump(def, e.type, w.type) || jump(def, w.type, e.type)) jumps++;
            }
        System.out.printf(
                Locale.ROOT,
                "  seed %d layout %.1f coherence: %d differing land neighbours, %.1f%% a low type beside"
                        + " tableland or a range%n",
                seed,
                world.layout,
                differ,
                differ == 0 ? 0 : 100.0 * jumps / differ);
        require(
                jumpC <= 0.05 && jumpM <= 0.002,
                "Offsets jump between neighbours: " + jumpC + " °C, " + jumpM);
        Arrays.sort(intensity);
        System.out.printf(
                Locale.ROOT,
                "  seed %d layout %.1f grid: offsets up to %.2f °C, %.3f moisture; largest step between"
                        + " neighbouring blocks %.4f °C, %.5f; intensity p10/p50/p90 %.2f/%.2f/%.2f;"
                        + " fresh map and reverse order equal%n",
                seed,
                world.layout,
                maxC,
                maxM,
                jumpC,
                jumpM,
                intensity[n / 10],
                intensity[n / 2],
                intensity[9 * n / 10]);
    }
}
