package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Material-parent endpoints, rejection controls and shared-map/caller-scratch boundaries. */
public final class SubstrateBoundaryCheck {
    private static final double PARENT = 160;

    private SubstrateBoundaryCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JsonObject definition(double sand, double loose, double erodible) {
        JsonObject json = new JsonObject();
        json.addProperty("sandPotential", sand);
        json.addProperty("looseMaterialPotential", loose);
        json.addProperty("windErodibility", erodible);
        return json;
    }

    private static void values(
            SubstrateMap.Sample out, double sand, double loose, double erodible) {
        require(out.sandPotential() == sand, "Sand potential");
        require(out.looseMaterialPotential() == loose, "Loose-material potential");
        require(out.windErodibility() == erodible, "Wind erodibility");
    }

    private static void mapContracts() {
        SubstrateMap packaged = new SubstrateMap();
        SubstrateMap empty = new SubstrateMap(definition(0, 0, 0));
        SubstrateMap full = new SubstrateMap(definition(1, 1, 1));
        JsonObject authored = definition(1, .25, .75);
        SubstrateMap mixed = new SubstrateMap(authored);
        authored.addProperty("sandPotential", 0);
        SubstrateMap.Sample reused = new SubstrateMap.Sample();
        for (double x : new double[] {-30000000, -16.25, -.5, -0.0, 0, .25, 16, 30000000}) {
            packaged.sample(x, -x, reused);
            values(reused, .9, .9, .9);
            empty.sample(x, -x, reused);
            values(reused, 0, 0, 0);
            full.sample(x, -x, reused);
            values(reused, 1, 1, 1);
            mixed.sample(x, -x, reused);
            values(reused, 1, .25, .75);
        }
        for (double invalid :
                new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            for (int axis = 0; axis < 2; axis++) {
                try {
                    packaged.sample(axis == 0 ? invalid : 0, axis == 1 ? invalid : 0, reused);
                    throw new AssertionError("Accepted invalid coordinate");
                } catch (IllegalArgumentException expected) {
                    values(reused, 1, .25, .75);
                }
            }
        }
        try {
            packaged.sample(0, 0, null);
            throw new AssertionError("Accepted missing scratch");
        } catch (NullPointerException expected) {
            // Caller must supply its own scratch.
        }
        for (String field :
                new String[] {"sandPotential", "looseMaterialPotential", "windErodibility"})
            for (double invalid :
                    new double[] {
                        -0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY
                    }) {
                JsonObject json = definition(.9, .9, .9);
                json.addProperty(field, invalid);
                try {
                    new SubstrateMap(json);
                    throw new AssertionError("Accepted invalid scalar: " + field);
                } catch (IllegalStateException expected) {
                    require(
                            expected.getMessage().equals("Range: " + field),
                            "Invalid scalar diagnostic");
                }
            }
    }

    private static void adapterControls() {
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        int[] positive = new int[4];
        for (int mode = 0; mode < 4; mode++) {
            // Each permission has an independent downstream consumer. Unavailable mass stays empty.
            SubstrateMap substrate =
                    new SubstrateMap(
                            definition(mode == 1 ? 1 : 0, mode == 2 ? 1 : 0, mode == 3 ? 1 : 0));
            DrylandTerrain adapter = new DrylandTerrain(8675309, heights, substrate);
            TerrainMap.Sample base = new TerrainMap.Sample();
            DrylandParityCheck.dryIntent(0, 0, base);
            DrylandTerrain.Result out = new DrylandTerrain.Result();
            int[] calls = {0};
            BoundedWaterClearance clearance =
                    (x, z, limit) -> {
                        calls[0]++;
                        require(limit > 0 && Double.isFinite(limit), "Bounded positive clearance");
                        return limit;
                    };
            for (int i = 0; i < 512; i++) {
                double x = (i % 32 - 16) * 127.75 + .25;
                double z = (i / 32 - 8) * 131.25 - .5;
                adapter.apply(x, z, base, clearance, PARENT, out);
                for (int family = 0; family < 4; family++) {
                    if (out.weights[family] <= 0) continue;
                    boolean permitted = mode == (family < 2 ? 1 : family == 2 ? 2 : 3);
                    if (!permitted)
                        require(
                                out.reasons[family].equals("SUBSTRATE"),
                                "Permission reached wrong family");
                    else if (out.reasons[family].equals("ACCEPTED")) positive[family]++;
                }
                if (mode == 0)
                    require(
                            out.height == PARENT && out.weight == 0 && out.contributors == 0,
                            "Empty substrate changed terrain");
            }
            require(calls[0] == 512, "Clearance must be queried exactly once per eligible column");
            base.ocean.marine = true;
            adapter.apply(0.25, -0.5, base, clearance, PARENT, out);
            require(calls[0] == 512 && out.reason.equals("MARINE"), "Marine early guard");
            base.ocean.marine = false;
            base.water.wet = true;
            adapter.apply(0.25, -0.5, base, clearance, PARENT, out);
            require(calls[0] == 512 && out.reason.equals("WATER_RESERVED"), "Wet early guard");
            base.water.wet = false;
            adapter.apply(0.25, -0.5, base, (x, z, limit) -> 0, PARENT, out);
            require(
                    out.reason.equals("WATER_RESERVED") && out.contributors == 0,
                    "Water clearance must precede material admission");
        }
        for (int count : positive) require(count > 0, "Missing independently permitted family");
        System.out.println(
                "independent permission positives=" + java.util.Arrays.toString(positive));
    }

    private static void concurrentReplay() throws Exception {
        SubstrateMap shared = new SubstrateMap(definition(.15, .65, 1));
        DrylandTerrain adapter =
                new DrylandTerrain(8675309, TerrainHeightReference.DEFAULT, new SubstrateMap());
        BoundedWaterClearance clearance = (x, z, limit) -> limit;
        List<String> expected = new ArrayList<>();
        TerrainMap.Sample base = new TerrainMap.Sample();
        DrylandParityCheck.dryIntent(0, 0, base);
        DrylandTerrain.Result result = new DrylandTerrain.Result();
        for (int i = 0; i < 256; i++) {
            adapter.apply(-2048 + i * 16.25, -i * 31.5, base, clearance, PARENT, result);
            expected.add(DrylandParityCheck.snapshot(result));
        }
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                final int lane = thread;
                tasks.add(
                        () -> {
                            SubstrateMap.Sample local = new SubstrateMap.Sample();
                            TerrainMap.Sample earlier = new TerrainMap.Sample();
                            DrylandParityCheck.dryIntent(0, 0, earlier);
                            DrylandTerrain.Result out = new DrylandTerrain.Result();
                            for (int n = 0; n < 256; n++) {
                                int i = (n * 73 + lane * 17) & 255;
                                shared.sample(-2048 + i * 16.25, -i * 31.5, local);
                                values(local, .15, .65, 1);
                                adapter.apply(
                                        -2048 + i * 16.25,
                                        -i * 31.5,
                                        earlier,
                                        clearance,
                                        PARENT,
                                        out);
                                require(
                                        expected.get(i).equals(DrylandParityCheck.snapshot(out)),
                                        "Concurrent replay");
                            }
                            return null;
                        });
            }
            for (Future<Void> task : workers.invokeAll(tasks)) task.get();
        } finally {
            workers.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        mapContracts();
        adapterControls();
        concurrentReplay();
        System.out.println("Substrate endpoints/invalid inputs/scratch/guards/concurrency=PASS");
    }
}
