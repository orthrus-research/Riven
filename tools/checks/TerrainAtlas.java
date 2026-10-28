package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.carvers.SurfaceCuts;
import cc.orthrus.riven.contrib.coast.SedimentaryCoasts;
import cc.orthrus.riven.contrib.cold.ColdRegions;
import cc.orthrus.riven.contrib.dryland.Drylands;
import cc.orthrus.riven.contrib.hills.Hills;
import cc.orthrus.riven.contrib.mountains.MountainKernels;
import cc.orthrus.riven.contrib.plateau.PlateauKernels;
import cc.orthrus.riven.contrib.rocky.RockyCoasts;
import cc.orthrus.riven.contrib.volcanic.VolcanicKernels;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Seed-to-raster sampler for prototyping. Every value comes from the production generator: one
 * complete native-path {@link TerrainMap#sample} per pixel (the call native cubes and the DH
 * preview make), the real {@link Sites} decisions and each stage's own {@code reason} field.
 * Nothing here reimplements selection.
 *
 * <p>Arguments are {@code key=value}: {@code out seed cx cz width height step} for a raster, plus
 * optional {@code threads}, {@code order=forward|reverse}, {@code footprint=n} (n x n samples per
 * pixel for maxima), {@code sites=true}. {@code section=x1,z1,x2,z2} with {@code spacing} writes a
 * cross-section instead. Raw layers are little-endian; {@code atlas.json} carries legends and a
 * SHA-256 digest of every deterministic layer, independent of threads and sampling order.
 */
public final class TerrainAtlas {
    private static final String[] STAGE_NAMES = {
        "lowland",
        "passes",
        "volcanic",
        "cold",
        "plateau",
        "mountain",
        "hill",
        "landmark",
        "carver",
        "cut",
        "dryland",
        "wonder",
        "shore",
        "rocky",
        "sediment"
    };
    private static final Enum<?>[][] STAGE_FAMILIES = {
        {},
        RegionalTerrainPasses.Formation.values(),
        VolcanicKernels.Family.values(),
        ColdRegions.Family.values(),
        PlateauKernels.Family.values(),
        MountainKernels.Family.values(),
        Hills.Family.values(),
        RegionalLandmarks.Family.values(),
        SurfaceCarvers.Kind.values(),
        SurfaceCuts.Family.values(),
        Drylands.Family.values(),
        WonderTerrain.Family.values(),
        Shore.Kind.values(),
        RockyCoasts.Family.values(),
        SedimentaryCoasts.Family.values()
    };

    /** Height after each stage, in pipeline order; index 0 is the eroded base. */
    private static double after(TerrainMap.Sample s, int stage) {
        switch (stage) {
            case -1:
                return s.originalHeight;
            case 0:
                return s.lowland.height;
            case 1:
                return s.passes.height;
            case 2:
                return s.volcanic.height;
            case 3:
                return s.cold.height;
            case 4:
                return s.plateaus.height;
            case 5:
                return s.mountains.height;
            case 6:
                return s.hills.height;
            case 7:
                return s.landmarks.height;
            case 8:
                return s.carvers.height;
            case 9:
                return s.cuts.height;
            case 10:
                return s.drylands.height;
            case 11:
                return s.wonders.height;
            case 12:
                return s.rocky.parentHeight;
            case 13:
                return s.rocky.height;
            case 14:
                return s.sediment.height;
            default:
                throw new IllegalArgumentException("stage " + stage);
        }
    }

    private static double delta(TerrainMap.Sample s, int stage) {
        // Rocky and sediment record their own parent; earlier stages chain consecutively.
        if (stage == 14) return s.sediment.height - s.sediment.parentHeight;
        return after(s, stage) - after(s, stage - 1);
    }

    private static Enum<?> family(TerrainMap.Sample s, int stage) {
        switch (stage) {
            case 1:
                return s.passes.dominant;
            case 2:
                return s.volcanic.dominant;
            case 3:
                return s.cold.dominant;
            case 4:
                return s.plateaus.dominant;
            case 5:
                return s.mountains.dominant;
            case 6:
                return s.hills.dominant;
            case 7:
                return s.landmarks.dominant;
            case 8:
                return s.carvers.dominant;
            case 9:
                return s.cuts.dominant;
            case 10:
                return s.drylands.dominant;
            case 11:
                return s.wonders.family;
            case 12:
                return s.shore.kind;
            case 13:
                return s.rocky.family;
            case 14:
                return s.sediment.family;
            default:
                return null;
        }
    }

    /** The stage's own recorded selection/rejection reason, where it keeps one. */
    private static String reason(TerrainMap.Sample s, int stage) {
        switch (stage) {
            case 0:
                return s.lowland.reason == null ? null : s.lowland.reason.name();
            case 2:
                return s.volcanic.reason;
            case 3:
                return s.cold.reason;
            case 4:
                return s.plateaus.reason;
            case 5:
                return s.mountains.reason;
            case 6:
                return s.hills.reason;
            case 9:
                return s.cuts.reason;
            case 10:
                return s.drylands.reason;
            case 11:
                return s.wonders.reason;
            case 13:
                return s.rocky.reason;
            case 14:
                return s.sediment.reason;
            default:
                return null;
        }
    }

    /** Thread-safe string codes, renumbered in sorted order before anything is written. */
    private static final class Codes {
        final ConcurrentHashMap<String, Integer> codes = new ConcurrentHashMap<>();
        final AtomicInteger next = new AtomicInteger(1);

        int code(String value) {
            if (value == null) return 0;
            Integer known = codes.get(value);
            if (known != null) return known;
            return codes.computeIfAbsent(
                    value,
                    v -> {
                        int c = next.getAndIncrement();
                        if (c > 255) throw new IllegalStateException("over 255 reasons");
                        return c;
                    });
        }

        /** Sorted names; index 0 means "none". Returns old code -> new code. */
        int[] renumber(List<String> names) {
            names.add("");
            names.addAll(new TreeSet<>(codes.keySet()));
            int[] map = new int[256];
            for (Map.Entry<String, Integer> e : codes.entrySet())
                map[e.getValue()] = names.indexOf(e.getKey());
            return map;
        }
    }

    /** Planar layers; index = row * width + column, row 0 at the minimum z. */
    private static final class Raster {
        final int width, height, footprint;
        final short[] surface, water;
        final byte[] habitat, region, level, character, waterKind, flags;
        final float[] celsius, moisture, micros;
        final float[][] delta, footprintDelta;
        final byte[][] family, reason, footprintFamily;
        final Codes[] reasons = new Codes[STAGE_NAMES.length];

        Raster(int width, int height, int footprint) {
            this.width = width;
            this.height = height;
            this.footprint = footprint;
            int n = width * height, k = STAGE_NAMES.length;
            surface = new short[n];
            water = new short[n];
            habitat = new byte[n];
            region = new byte[n];
            level = new byte[n];
            character = new byte[n];
            waterKind = new byte[n];
            flags = new byte[n];
            celsius = new float[n];
            moisture = new float[n];
            micros = new float[n];
            delta = new float[k][n];
            family = new byte[k][n];
            reason = new byte[k][n];
            footprintDelta = footprint > 1 ? new float[k][n] : null;
            footprintFamily = footprint > 1 ? new byte[k][n] : null;
            for (int i = 0; i < k; i++) reasons[i] = new Codes();
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq < 1) throw new IllegalArgumentException("Expected key=value: " + arg);
            a.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        Path out = Paths.get(required(a, "out"));
        long seed = Long.parseLong(required(a, "seed"));
        Files.createDirectories(out);
        long started = System.nanoTime();
        GenerationMaps maps =
                new GenerationMaps(
                        seed,
                        new WorldScale(
                                a.containsKey("scale") ? Double.parseDouble(a.get("scale")) : 1));
        double mapSeconds = (System.nanoTime() - started) / 1e9;
        if (a.containsKey("section")) section(maps, seed, out, a);
        else raster(maps, seed, out, a, mapSeconds);
    }

    private static String required(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null) throw new IllegalArgumentException("Missing " + key + "=");
        return v;
    }

    private static int integer(Map<String, String> a, String key, int fallback) {
        return a.containsKey(key) ? Integer.parseInt(a.get(key)) : fallback;
    }

    private static void raster(
            GenerationMaps maps, long seed, Path out, Map<String, String> a, double mapSeconds)
            throws Exception {
        int width = integer(a, "width", 512), height = integer(a, "height", width);
        int step = integer(a, "step", 16), footprint = integer(a, "footprint", 1);
        int threads =
                integer(a, "threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        boolean reverse = "reverse".equals(a.getOrDefault("order", "forward"));
        if (width < 1 || height < 1 || step < 1 || footprint < 1 || threads < 1)
            throw new IllegalArgumentException("size/step/footprint/threads");
        if (footprint > step) throw new IllegalArgumentException("footprint exceeds step");
        int minX = integer(a, "cx", 0) - width * step / 2;
        int minZ = integer(a, "cz", 0) - height * step / 2;

        List<String> habitats = new ArrayList<>(Habitats.paths());
        Raster r = new Raster(width, height, footprint);
        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        List<Future<?>> work = new ArrayList<>();
        for (int t = 0; t < threads; t++)
            work.add(
                    pool.submit(
                            () -> {
                                TerrainMap.Sample s = new TerrainMap.Sample();
                                TerrainMap.Sample sub = new TerrainMap.Sample();
                                for (int n; (n = next.getAndIncrement()) < height; ) {
                                    int row = reverse ? height - 1 - n : n;
                                    for (int c = 0; c < width; c++) {
                                        int col = reverse ? width - 1 - c : c;
                                        fill(maps, habitats, r, row, col, minX, minZ, step, s, sub);
                                    }
                                    int d = done.incrementAndGet();
                                    if (d % Math.max(1, height / 10) == 0)
                                        System.out.printf("rows %d/%d%n", d, height);
                                }
                                return null;
                            }));
        for (Future<?> f : work) f.get();
        pool.shutdown();
        double sampleSeconds = (System.nanoTime() - started) / 1e9;

        List<List<String>> reasonNames = new ArrayList<>();
        for (int k = 0; k < STAGE_NAMES.length; k++) {
            List<String> names = new ArrayList<>();
            int[] map = r.reasons[k].renumber(names);
            byte[] codes = r.reason[k];
            for (int i = 0; i < codes.length; i++) codes[i] = (byte) map[codes[i] & 0xff];
            reasonNames.add(names);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        write(out, r, digest);
        if (a.containsKey("sites")) {
            // Site decisions are sequential and deterministic; they join the digest.
            String sites =
                    new GsonBuilder()
                            .setPrettyPrinting()
                            .create()
                            .toJson(sites(maps, minX, minZ, width, height, step));
            byte[] bytes = sites.getBytes(StandardCharsets.UTF_8);
            bytes(out, "sites.json", bytes, digest);
        }

        JsonObject j = new JsonObject();
        j.addProperty("seed", seed);
        j.addProperty("terrainVersion", TerrainMap.VERSION);
        j.addProperty("minX", minX);
        j.addProperty("minZ", minZ);
        j.addProperty("step", step);
        j.addProperty("width", width);
        j.addProperty("height", height);
        j.addProperty("footprint", footprint);
        j.addProperty("threads", threads);
        j.addProperty("order", reverse ? "reverse" : "forward");
        j.addProperty("digest", hex(digest.digest()));
        JsonObject seconds = new JsonObject();
        seconds.addProperty("maps", round(mapSeconds));
        seconds.addProperty("sampling", round(sampleSeconds));
        j.add("seconds", seconds);
        j.addProperty("waterTopY", TerrainHeightReference.DEFAULT.waterTopY);
        j.addProperty("seaLevel", TerrainHeightReference.DEFAULT.seaLevel);
        j.add("habitats", array(habitats));
        j.add("regions", array(ClimateRegion.values()));
        j.add("levels", array(Terrain.Level.values()));
        j.add("characters", array(Terrain.Character.values()));
        j.add("waterKinds", array(Hydro.Kind.values()));
        JsonObject flags = new JsonObject();
        flags.addProperty("marine", 1);
        flags.addProperty("supplied", 2);
        flags.addProperty("deepOcean", 4);
        flags.addProperty("wet", 8);
        j.add("flags", flags);
        j.add("fingerprints", fingerprints());
        JsonArray stages = new JsonArray();
        for (int k = 0; k < STAGE_NAMES.length; k++) {
            JsonObject stage = new JsonObject();
            stage.addProperty("name", STAGE_NAMES[k]);
            stage.add("families", array(STAGE_FAMILIES[k]));
            stage.add("reasons", array(reasonNames.get(k)));
            JsonObject land = new JsonObject();
            int[] counts = new int[reasonNames.get(k).size()];
            for (int i = 0; i < r.reason[k].length; i++)
                if ((r.flags[i] & 1) == 0) counts[r.reason[k][i] & 0xff]++;
            for (int c = 1; c < counts.length; c++)
                if (counts[c] > 0) land.addProperty(reasonNames.get(k).get(c), counts[c]);
            stage.add("landReasons", land);
            stages.add(stage);
        }
        j.add("stages", stages);
        writeJson(out.resolve("atlas.json"), j);
        System.out.printf(
                Locale.ROOT,
                "atlas %dx%d step %d footprint %d at [%d,%d) x [%d,%d): sampling %.1fs on %d"
                        + " threads (%s); digest %s%n",
                width,
                height,
                step,
                footprint,
                minX,
                minX + width * step,
                minZ,
                minZ + height * step,
                sampleSeconds,
                threads,
                reverse ? "reverse" : "forward",
                j.get("digest").getAsString());
    }

    private static void fill(
            GenerationMaps maps,
            List<String> habitats,
            Raster r,
            int row,
            int col,
            int minX,
            int minZ,
            int step,
            TerrainMap.Sample s,
            TerrainMap.Sample sub) {
        int i = row * r.width + col, x = minX + col * step, z = minZ + row * step;
        long t0 = System.nanoTime();
        // A full sample runs habitat first, so Habitats.at matches the final surfaceHabitat biome.
        maps.terrain.sample(x, z, s);
        r.micros[i] = (System.nanoTime() - t0) / 1000f;
        r.surface[i] = (short) s.blockY();
        r.water[i] = s.waterY() == Integer.MIN_VALUE ? Short.MIN_VALUE : (short) s.waterY();
        r.habitat[i] = (byte) habitats.indexOf(Habitats.at(s));
        r.region[i] = (byte) (s.climate.region == null ? -1 : s.climate.region.ordinal());
        r.level[i] = (byte) s.land.level.ordinal();
        r.character[i] = (byte) s.land.character.ordinal();
        r.waterKind[i] = (byte) s.water.kind.ordinal();
        r.flags[i] =
                (byte)
                        ((s.ocean.marine ? 1 : 0)
                                | (s.ocean.supplied ? 2 : 0)
                                | (s.ocean.deepOcean ? 4 : 0)
                                | (s.water.wet ? 8 : 0));
        r.celsius[i] = (float) s.climate.celsius;
        r.moisture[i] = (float) s.climate.moisture;
        for (int k = 0; k < STAGE_NAMES.length; k++) {
            r.delta[k][i] = (float) delta(s, k);
            Enum<?> f = family(s, k);
            r.family[k][i] = (byte) (f == null ? 0 : f.ordinal() + 1);
            r.reason[k][i] = (byte) r.reasons[k].code(reason(s, k));
        }
        if (r.footprint <= 1) return;
        // Integer block offsets across the pixel's footprint, including the representative.
        for (int k = 0; k < STAGE_NAMES.length; k++) {
            r.footprintDelta[k][i] = r.delta[k][i];
            r.footprintFamily[k][i] = r.family[k][i];
        }
        for (int fz = 0; fz < r.footprint; fz++)
            for (int fx = 0; fx < r.footprint; fx++) {
                if (fx == 0 && fz == 0) continue;
                maps.terrain.sample(x + fx * step / r.footprint, z + fz * step / r.footprint, sub);
                for (int k = 0; k < STAGE_NAMES.length; k++) {
                    double d = delta(sub, k);
                    if (Math.abs(d) > Math.abs(r.footprintDelta[k][i])) {
                        r.footprintDelta[k][i] = (float) d;
                        Enum<?> f = family(sub, k);
                        r.footprintFamily[k][i] = (byte) (f == null ? 0 : f.ordinal() + 1);
                    }
                }
            }
    }

    /** Every raw site anchor near the window with its lane, admission and outcome at the anchor. */
    private static JsonArray sites(
            GenerationMaps maps, int minX, int minZ, int width, int height, int step)
            throws Exception {
        Sites sites = maps.terrain.types.sites;
        Method laneOf = Sites.class.getDeclaredMethod("laneOf", long.class);
        laneOf.setAccessible(true);
        Field probabilityField = Sites.class.getDeclaredField("probability");
        probabilityField.setAccessible(true);
        double probability = probabilityField.getDouble(sites);
        double margin = 0;
        for (Sites.Lane lane : Sites.Lane.values())
            margin = Math.max(margin, sites.maximumRadius(lane));
        int c0 = (int) Math.floor((minX - margin) / sites.spacing) - 1;
        int c1 = (int) Math.floor((minX + width * step + margin) / sites.spacing) + 1;
        int r0 = (int) Math.floor((minZ - margin) / sites.spacing) - 1;
        int r1 = (int) Math.floor((minZ + height * step + margin) / sites.spacing) + 1;
        JsonArray list = new JsonArray();
        TerrainMap.Sample s = new TerrainMap.Sample();
        for (int cz = r0; cz <= r1; cz++)
            for (int cx = c0; cx <= c1; cx++) {
                long key = sites.key(cx, cz);
                if (Sites.random(key, 1) >= probability) continue;
                Sites.Lane lane = (Sites.Lane) laneOf.invoke(sites, key);
                double ax = sites.x(cx, key), az = sites.z(cz, key);
                double radius = sites.maximumRadius(lane);
                if (ax + radius < minX
                        || ax - radius >= minX + width * step
                        || az + radius < minZ
                        || az - radius >= minZ + height * step) continue;
                JsonObject site = new JsonObject();
                site.addProperty("cellX", cx);
                site.addProperty("cellZ", cz);
                site.addProperty("lane", lane.name());
                site.addProperty("x", round(ax));
                site.addProperty("z", round(az));
                site.addProperty("radius", round(radius));
                boolean admitted = sites.admits(cx, cz, key, lane);
                site.addProperty("admitted", admitted);
                if (admitted) {
                    maps.terrain.sample(Math.floor(ax), Math.floor(az), s);
                    int stage = stageOf(lane);
                    site.addProperty("marine", s.ocean.marine);
                    site.addProperty("stage", STAGE_NAMES[stage]);
                    String why = reason(s, stage);
                    if (why != null) site.addProperty("reasonAtAnchor", why);
                    Enum<?> f = family(s, stage);
                    if (f != null) site.addProperty("familyAtAnchor", f.name());
                    site.addProperty("deltaAtAnchor", round(delta(s, stage)));
                }
                list.add(site);
            }
        return list;
    }

    private static int stageOf(Sites.Lane lane) {
        switch (lane) {
            case LANDMARK:
                return 7;
            case CARVER:
                return 8;
            case HILLS:
                return 6;
            case MOUNTAINS:
                return 5;
            case PLATEAUS:
                return 4;
            case COLD:
                return 3;
            case VOLCANIC:
                return 2;
            case CUTS:
                return 9;
            case SEDIMENT:
                return 13;
            case ROCKY:
                return 12;
            default:
                throw new IllegalArgumentException(lane.name());
        }
    }

    /** One column every {@code spacing} blocks along a line: height after every stage. */
    private static void section(GenerationMaps maps, long seed, Path out, Map<String, String> a)
            throws IOException {
        String[] p = required(a, "section").split(",");
        double x1 = Double.parseDouble(p[0]), z1 = Double.parseDouble(p[1]);
        double x2 = Double.parseDouble(p[2]), z2 = Double.parseDouble(p[3]);
        int spacing = integer(a, "spacing", 1);
        double length = Math.hypot(x2 - x1, z2 - z1);
        int count = (int) Math.floor(length / spacing) + 1;
        if (count > 65536) throw new IllegalArgumentException("section over 65536 columns");
        List<String> habitats = new ArrayList<>(Habitats.paths());
        StringBuilder csv = new StringBuilder("distance,x,z,base");
        for (String name : STAGE_NAMES) csv.append(',').append(name);
        csv.append(",blockY,waterY,marine,habitat");
        for (String name : STAGE_NAMES) csv.append(',').append(name).append("_family");
        for (String name : STAGE_NAMES) csv.append(',').append(name).append("_reason");
        csv.append('\n');
        TerrainMap.Sample s = new TerrainMap.Sample();
        for (int i = 0; i < count; i++) {
            double t = count == 1 ? 0 : (double) i / (count - 1);
            int x = (int) Math.floor(x1 + (x2 - x1) * t), z = (int) Math.floor(z1 + (z2 - z1) * t);
            maps.terrain.sample(x, z, s);
            csv.append(round(length * t)).append(',').append(x).append(',').append(z);
            csv.append(',').append(round(after(s, -1)));
            for (int k = 0; k < STAGE_NAMES.length; k++) csv.append(',').append(round(after(s, k)));
            csv.append(',').append(s.blockY()).append(',');
            if (s.waterY() != Integer.MIN_VALUE) csv.append(s.waterY());
            csv.append(',').append(s.ocean.marine).append(',').append(Habitats.at(s));
            for (int k = 0; k < STAGE_NAMES.length; k++) {
                Enum<?> f = family(s, k);
                csv.append(',').append(f == null ? "" : f.name());
            }
            for (int k = 0; k < STAGE_NAMES.length; k++) {
                String why = reason(s, k);
                csv.append(',').append(why == null ? "" : why);
            }
            csv.append('\n');
        }
        Files.write(out.resolve("section.csv"), csv.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject j = new JsonObject();
        j.addProperty("seed", seed);
        j.addProperty("terrainVersion", TerrainMap.VERSION);
        j.addProperty("x1", x1);
        j.addProperty("z1", z1);
        j.addProperty("x2", x2);
        j.addProperty("z2", z2);
        j.addProperty("spacing", spacing);
        j.addProperty("columns", count);
        j.addProperty("waterTopY", TerrainHeightReference.DEFAULT.waterTopY);
        j.add("stages", array(java.util.Arrays.asList(STAGE_NAMES)));
        writeJson(out.resolve("section.json"), j);
        System.out.printf("section %d columns every %d blocks%n", count, spacing);
    }

    /** Fingerprints of every packaged definition this source tree has, read reflectively. */
    private static JsonObject fingerprints() {
        JsonObject f = new JsonObject();
        f.addProperty("reference", TerrainHeightReference.DEFAULT.fingerprint);
        String[] owners = {
            "ContinentalMap",
            "Terrain",
            "Shore",
            "Habitats",
            "Hydro",
            "OceanBed",
            "LakeShape",
            "RiverShape",
            "CoastShape",
            "SedimentTerrain",
            "RockyTerrain",
            "LowlandTerrain",
            "HillTerrain",
            "RegionalFabricMap",
            "MountainTerrain",
            "PlateauTerrain",
            "DrylandTerrain",
            "SubstrateMap",
            "BaseTerrainMap",
            "BaseElevationMap",
            "ClimateForcingMap",
            "RegionalProfileMap",
            "ColdTerrain",
            "VolcanicTerrain",
            "GeneralSurfaceCuts",
            "SurfaceRules",
            "RegionalTerrainPasses",
            "RegionalLandmarks",
            "SurfaceCarvers"
        };
        for (String owner : owners) {
            try {
                Class<?> c = Class.forName(TerrainAtlas.class.getPackage().getName() + "." + owner);
                f.addProperty(owner, String.valueOf(c.getField("FINGERPRINT").get(null)));
            } catch (ReflectiveOperationException absent) {
                // Older source trees predate some definitions.
            }
        }
        return f;
    }

    private static void write(Path out, Raster r, MessageDigest digest) throws IOException {
        shorts(out, "surface.i16", r.surface, digest);
        shorts(out, "water.i16", r.water, digest);
        bytes(out, "habitat.u8", r.habitat, digest);
        bytes(out, "region.u8", r.region, digest);
        bytes(out, "level.u8", r.level, digest);
        bytes(out, "character.u8", r.character, digest);
        bytes(out, "water_kind.u8", r.waterKind, digest);
        bytes(out, "flags.u8", r.flags, digest);
        floats(out, "celsius.f32", r.celsius, digest);
        floats(out, "moisture.f32", r.moisture, digest);
        for (int k = 0; k < STAGE_NAMES.length; k++) {
            String n = STAGE_NAMES[k];
            floats(out, "delta_" + n + ".f32", r.delta[k], digest);
            bytes(out, "family_" + n + ".u8", r.family[k], digest);
            bytes(out, "reason_" + n + ".u8", r.reason[k], digest);
            if (r.footprint > 1) {
                floats(out, "fp_delta_" + n + ".f32", r.footprintDelta[k], digest);
                bytes(out, "fp_family_" + n + ".u8", r.footprintFamily[k], digest);
            }
        }
        // Timing depends on threads, cache state and the host; it is excluded from the digest.
        floats(out, "micros.f32", r.micros, null);
    }

    private static void bytes(Path out, String name, byte[] values, MessageDigest digest)
            throws IOException {
        if (digest != null) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update(values);
        }
        Files.write(out.resolve(name), values);
    }

    private static void shorts(Path out, String name, short[] values, MessageDigest digest)
            throws IOException {
        ByteBuffer b = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.asShortBuffer().put(values);
        bytes(out, name, b.array(), digest);
    }

    private static void floats(Path out, String name, float[] values, MessageDigest digest)
            throws IOException {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(values);
        bytes(out, name, b.array(), digest);
    }

    private static void writeJson(Path path, Object json) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(json, w);
        }
    }

    private static JsonArray array(Enum<?>[] values) {
        JsonArray a = new JsonArray();
        for (Enum<?> e : values) a.add(e.name());
        return a;
    }

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte v : bytes) b.append(String.format("%02x", v));
        return b.toString();
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
