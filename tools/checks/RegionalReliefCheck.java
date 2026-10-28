package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Regional partition, gradient, envelope and caller-scratch checks; no native world is generated. */
public final class RegionalReliefCheck {
    private RegionalReliefCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void close(double observed, double expected, String name) {
        require(
                Double.isFinite(observed)
                        && Double.isFinite(expected)
                        && Math.abs(observed - expected) <= 2e-6 + Math.abs(expected) * 2e-4,
                name + ": " + observed + " != " + expected);
    }

    static JsonObject definition(RegionalProfileMap.Profile only) {
        JsonObject json = Rules.load("riven-terrain-regions.json", 1).json;
        if (only != null)
            for (RegionalProfileMap.Profile p : RegionalProfileMap.Profile.values())
                json.getAsJsonObject("profiles")
                        .getAsJsonObject(p.name())
                        .addProperty("frequencyWeight", p == only ? 1 : 0);
        return json;
    }

    private static String snapshot(BaseTerrainMap.Sample b) {
        StringBuilder out = new StringBuilder();
        for (double v :
                new double[] {
                    b.height(),
                    b.dx(),
                    b.dz(),
                    b.broadDx(),
                    b.broadDz(),
                    b.meanHeight(),
                    b.coarseHeight(),
                    b.minimumY(),
                    b.maximumY(),
                    b.amplitude(),
                    b.noise(),
                    b.fineContribution(),
                    b.legacyPosition()
                }) out.append(Long.toHexString(Double.doubleToLongBits(v))).append('|');
        for (RegionalProfileMap.Profile p : RegionalProfileMap.Profile.values())
            out.append(Long.toHexString(Double.doubleToLongBits(b.region().weight(p)))).append('|');
        return out.toString();
    }

    private static void partition(RegionalProfileMap.Sample r) {
        double total = 0;
        for (RegionalProfileMap.Profile p : RegionalProfileMap.Profile.values()) {
            double weight = r.weight(p);
            require(weight >= 0 && weight <= 1 + 1e-14, "Regional weight range");
            total += weight;
        }
        require(Math.abs(total - 1) < 1e-14, "Regional partition sum");
        require(
                r.amplitudeFraction() >= .18 - 1e-14 && r.amplitudeFraction() <= .95 + 1e-14,
                "Amplitude fraction envelope");
        require(
                r.wideShare() >= 0 && r.fineShare() >= 0 && r.wideShare() + r.fineShare() <= 1,
                "Spectral simplex");
    }

    private static void gradients(BaseTerrainMap map, double x, double z) {
        BaseTerrainMap.Sample center = new BaseTerrainMap.Sample(),
                minus = new BaseTerrainMap.Sample(),
                plus = new BaseTerrainMap.Sample();
        map.sample(x, z, center);
        double h = .015625;
        for (int axis = 0; axis < 2; axis++) {
            map.sample(x - (axis == 0 ? h : 0), z - (axis == 1 ? h : 0), minus);
            map.sample(x + (axis == 0 ? h : 0), z + (axis == 1 ? h : 0), plus);
            close(
                    axis == 0 ? center.dx() : center.dz(),
                    (plus.height() - minus.height()) / (2 * h),
                    "Full gradient");
            close(
                    axis == 0 ? center.broadDx() : center.broadDz(),
                    (plus.coarseHeight() - minus.coarseHeight()) / (2 * h),
                    "Coarse gradient");
            close(
                    axis == 0 ? center.region().amplitudeDx() : center.region().amplitudeDz(),
                    (plus.region().amplitudeFraction() - minus.region().amplitudeFraction())
                            / (2 * h),
                    "Fraction gradient");
            close(
                    axis == 0 ? center.region().wideDx() : center.region().wideDz(),
                    (plus.region().wideShare() - minus.region().wideShare()) / (2 * h),
                    "Wide gradient");
            close(
                    axis == 0 ? center.region().fineDx() : center.region().fineDz(),
                    (plus.region().fineShare() - minus.region().fineShare()) / (2 * h),
                    "Fine gradient");
        }
    }

    private static double field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(object);
    }

    private static void seams(RegionalProfileMap regions, BaseTerrainMap map) throws Exception {
        for (String name : new String[] {"coarse", "local"}) {
            Field f = RegionalProfileMap.class.getDeclaredField(name);
            f.setAccessible(true);
            Object frame = f.get(regions);
            double c = field(frame, "c"), s = field(frame, "s"), span = field(frame, "span");
            double ox = field(frame, "offsetX"), oz = field(frame, "offsetZ");
            for (double u : new double[] {-2, -1.75, -.25, 0, .25, .75, 1})
                for (double v : new double[] {-.75, 0, .25}) {
                    double a = (u - ox) * span, b = (v - oz) * span;
                    gradients(map, a * c - b * s, a * s + b * c);
                }
        }
    }

    private static void invalidDefinitions() {
        for (String property :
                new String[] {
                    "coarseInPlainWidths", "localInPlainWidths", "transitionFraction", "localMix"
                }) {
            JsonObject j = definition(null);
            j.addProperty(property, Double.NaN);
            try {
                new RegionalProfileMap(0, TerrainHeightReference.DEFAULT, j);
                throw new AssertionError("Invalid regional definition");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains(property), "Definition diagnostic");
            }
        }
        for (String property :
                new String[] {"amplitudeFraction", "wideShare", "fineShare", "frequencyWeight"}) {
            JsonObject j = definition(null);
            j.getAsJsonObject("profiles").getAsJsonObject("QUIET").addProperty(property, 1.01);
            try {
                new RegionalProfileMap(0, TerrainHeightReference.DEFAULT, j);
                throw new AssertionError("Invalid profile definition");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains(property), "Profile diagnostic");
            }
        }
        JsonObject zero = definition(null);
        for (RegionalProfileMap.Profile p : RegionalProfileMap.Profile.values())
            zero.getAsJsonObject("profiles")
                    .getAsJsonObject(p.name())
                    .addProperty("frequencyWeight", 0);
        try {
            new RegionalProfileMap(0, TerrainHeightReference.DEFAULT, zero);
            throw new AssertionError("Empty frequency total");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("frequency"), "Empty frequency diagnostic");
        }
    }

    public static void main(String[] args) throws Exception {
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        int observed = 0;
        for (long seed : new long[] {8675309, 7, -918273645}) {
            PlateMap plates = new PlateMap(seed);
            RegionalProfileMap regions = new RegionalProfileMap(seed, heights);
            BaseTerrainMap map =
                    new BaseTerrainMap(
                            seed, plates, heights, regions, BaseTerrainMap.Policy.REGIONAL);
            BaseTerrainMap legacy =
                    new BaseTerrainMap(
                            seed, plates, heights, regions, BaseTerrainMap.Policy.LEGACY);
            BaseTerrainMap.Sample sample = new BaseTerrainMap.Sample(),
                    old = new BaseTerrainMap.Sample();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 1024; i++) {
                double x = (i % 32 - 16) * 941.25 + .25, z = (i / 32 - 16) * 1021.75 - .5;
                map.sample(x, z, sample);
                legacy.sample(x, z, old);
                partition(sample.region());
                require(
                        Double.isFinite(sample.height())
                                && sample.height() >= sample.minimumY() - 1e-10
                                && sample.height() <= sample.maximumY() + 1e-10,
                        "Legal height envelope");
                require(
                        Double.doubleToLongBits(sample.meanHeight())
                                        == Double.doubleToLongBits(old.meanHeight())
                                && sample.minimumY() == old.minimumY()
                                && sample.maximumY() == old.maximumY()
                                && sample.legacyPosition() == old.legacyPosition(),
                        "Elevation policy changed");
                require(
                        sample.amplitude()
                                <= Math.min(
                                                sample.meanHeight() - sample.minimumY(),
                                                sample.maximumY() - sample.meanHeight())
                                        + 1e-10,
                        "Amplitude exceeds signed envelope");
                expected.add(snapshot(sample));
                if ((i & 15) == 0) gradients(map, x, z);
                observed++;
            }
            for (int i = 1023; i >= 0; i--) {
                map.sample((i % 32 - 16) * 941.25 + .25, (i / 32 - 16) * 1021.75 - .5, sample);
                require(expected.get(i).equals(snapshot(sample)), "Reverse scratch replay");
            }
            seams(regions, map);
            for (double x : new double[] {-30000000, -1, 0, 30000000}) {
                map.sample(x, -x, sample);
                partition(sample.region());
                require(Double.isFinite(sample.height()), "Far coordinate");
            }
            String retained = snapshot(sample);
            for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -30000001}) {
                try {
                    map.sample(bad, 0, sample);
                    throw new AssertionError("Invalid coordinate");
                } catch (IllegalArgumentException wanted) {
                    require(retained.equals(snapshot(sample)), "Invalid call mutated scratch");
                }
            }
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Void>> tasks = new ArrayList<>();
                for (int lane = 0; lane < 4; lane++) {
                    final int id = lane;
                    tasks.add(
                            () -> {
                                BaseTerrainMap.Sample local = new BaseTerrainMap.Sample();
                                for (int n = 0; n < 256; n++) {
                                    int i = (n * 73 + id * 17) & 1023;
                                    map.sample(
                                            (i % 32 - 16) * 941.25 + .25,
                                            (i / 32 - 16) * 1021.75 - .5,
                                            local);
                                    require(
                                            expected.get(i).equals(snapshot(local)),
                                            "Concurrent replay");
                                }
                                return null;
                            });
                }
                for (Future<Void> task : pool.invokeAll(tasks)) task.get();
            } finally {
                pool.shutdownNow();
            }
            for (RegionalProfileMap.Profile p : RegionalProfileMap.Profile.values()) {
                RegionalProfileMap forced = new RegionalProfileMap(seed, heights, definition(p));
                RegionalProfileMap.Sample r = new RegionalProfileMap.Sample();
                forced.sample(-1234.25, 789.5, r);
                require(Math.abs(r.weight(p) - 1) < 1e-14, "Single-profile control");
            }
        }
        invalidDefinitions();
        System.out.println(
                "Regional partition/elevation envelope/gradients/seams/scratch/coordinates/concurrency=PASS; points="
                        + observed);
    }
}
