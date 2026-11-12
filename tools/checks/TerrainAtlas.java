package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.carvers.SurfaceCuts;
import cc.orthrus.riven.contrib.cold.ColdRegions;
import cc.orthrus.riven.contrib.dryland.Drylands;
import cc.orthrus.riven.contrib.mountains.MountainKernels;
import cc.orthrus.riven.contrib.volcanic.VolcanicKernels;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
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
        "passes",
        "volcanic",
        "cold",
        "mountain",
        "landmark",
        "carver",
        "cut",
        "dryland",
        "shore",
        "rocky",
        "sediment"
    };
    private static final Enum<?>[][] STAGE_FAMILIES = {
        RegionalTerrainPasses.Formation.values(),
        VolcanicKernels.Family.values(),
        ColdRegions.Family.values(),
        MountainKernels.Family.values(),
        RegionalLandmarks.Family.values(),
        SurfaceCarvers.Kind.values(),
        SurfaceCuts.Family.values(),
        Drylands.Family.values(),
        Shore.Kind.values(),
        CoastForms.Form.values(),
        CoastSediment.Form.values()
    };

    /** Height after each stage, in pipeline order; index -1 is the eroded base. */
    private static double after(TerrainMap.Sample s, int stage) {
        switch (stage) {
            case -1:
                return s.originalHeight;
            case 0:
                return s.passes.height;
            case 1:
                return s.volcanic.height;
            case 2:
                return s.cold.height;
            case 3:
                return s.mountains.height;
            case 4:
                return s.landmarks.height;
            case 5:
                return s.carvers.height;
            case 6:
                return s.cuts.height;
            case 7:
                return s.drylands.height;
            case 8:
                return s.rocky.parentHeight;
            case 9:
                return s.rocky.height;
            case 10:
                return s.sediment.height;
            default:
                throw new IllegalArgumentException("stage " + stage);
        }
    }

    private static double delta(TerrainMap.Sample s, int stage) {
        // Rocky and sediment record their own parent; earlier stages chain consecutively.
        if (stage == 10) return s.sediment.height - s.sediment.parentHeight;
        return after(s, stage) - after(s, stage - 1);
    }

    private static Enum<?> family(TerrainMap.Sample s, int stage) {
        switch (stage) {
            case 0:
                return s.passes.dominant;
            case 1:
                return s.volcanic.dominant;
            case 2:
                return s.cold.dominant;
            case 3:
                return s.mountains.dominant;
            case 4:
                return s.landmarks.dominant;
            case 5:
                return s.carvers.dominant;
            case 6:
                return s.cuts.dominant;
            case 7:
                return s.drylands.dominant;
            case 8:
                return s.shore.kind;
            case 9:
                return s.rocky.family;
            case 10:
                return s.sediment.family;
            default:
                return null;
        }
    }

    /** The stage's own recorded selection/rejection reason, where it keeps one. */
    private static String reason(TerrainMap.Sample s, int stage) {
        switch (stage) {
            case 1:
                return s.volcanic.reason;
            case 2:
                return s.cold.reason;
            case 3:
                return s.mountains.reason;
            case 6:
                return s.cuts.reason;
            case 7:
                return s.drylands.reason;
            case 9:
                return s.rocky.reason;
            case 10:
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

    /** Thread-safe codes for 64-bit identities (plates, province cells), renumbered ascending. */
    private static final class LongCodes {
        final ConcurrentHashMap<Long, Integer> codes = new ConcurrentHashMap<>();
        final AtomicInteger next = new AtomicInteger(1);

        int code(long value) {
            Integer known = codes.get(value);
            if (known != null) return known;
            return codes.computeIfAbsent(
                    value,
                    v -> {
                        int c = next.getAndIncrement();
                        if (c > 65535) throw new IllegalStateException("over 65535 identities");
                        return c;
                    });
        }

        /** Ascending identities; code k + 1 names ids[k]. Returns old code -> new code. */
        int[] renumber(List<Long> ids) {
            ids.addAll(new TreeSet<>(codes.keySet()));
            int[] map = new int[next.get()];
            for (Map.Entry<Long, Integer> e : codes.entrySet())
                map[e.getValue()] = Collections.binarySearch(ids, e.getKey()) + 1;
            return map;
        }

        static void apply(short[] codes, int[] map) {
            for (int i = 0; i < codes.length; i++) codes[i] = (short) map[codes[i] & 0xffff];
        }
    }

    /** Planar layers; index = row * width + column, row 0 at the minimum z. */
    private static final class Raster {
        final int width, height, footprint;
        final short[] surface, water, plate, province, waterBody;
        final byte[] habitat,
                variant,
                region,
                level,
                character,
                landform,
                waterKind,
                flags,
                provinceType,
                coast;
        final byte[] river, mouth, channelBeyond;
        final float[] celsius, moisture, micros, craton, volcanic, spine, provinceShare;
        final float[][] delta, footprintDelta;
        final byte[][] family, reason, footprintFamily;
        final Codes[] reasons = new Codes[STAGE_NAMES.length];
        final Codes provinceTypes = new Codes(), coastTypes = new Codes();
        final LongCodes plates = new LongCodes(), provinces = new LongCodes();
        final LongCodes waterBodies = new LongCodes();

        Raster(int width, int height, int footprint) {
            this.width = width;
            this.height = height;
            this.footprint = footprint;
            int n = width * height, k = STAGE_NAMES.length;
            surface = new short[n];
            water = new short[n];
            plate = new short[n];
            province = new short[n];
            provinceType = new byte[n];
            coast = new byte[n];
            waterBody = new short[n];
            river = new byte[n];
            mouth = new byte[n];
            channelBeyond = new byte[n];
            habitat = new byte[n];
            variant = new byte[n];
            region = new byte[n];
            level = new byte[n];
            character = new byte[n];
            landform = new byte[n];
            waterKind = new byte[n];
            flags = new byte[n];
            celsius = new float[n];
            moisture = new float[n];
            micros = new float[n];
            craton = new float[n];
            volcanic = new float[n];
            spine = new float[n];
            provinceShare = new float[n];
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
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpuStarted = os.getProcessCpuTime();
        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        // Work goes out in tiles about one hydrology cell wide, spread across the window, so
        // concurrent threads build different regional plans instead of queueing on one (rows put
        // every thread on the same cell at once). Pixels are independent: order changes timing,
        // never a sample.
        int tile = Math.max(1, (int) Math.ceil(maps.terrain.hydro.cellBlocks / (double) step));
        int tilesX = (width + tile - 1) / tile, tilesZ = (height + tile - 1) / tile;
        int[] order = spread(tilesX * tilesZ);
        AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        List<Future<?>> work = new ArrayList<>();
        for (int t = 0; t < threads; t++)
            work.add(
                    pool.submit(
                            () -> {
                                TerrainMap.Sample s = new TerrainMap.Sample();
                                TerrainMap.Sample sub = new TerrainMap.Sample();
                                for (int n; (n = next.getAndIncrement()) < order.length; ) {
                                    int k = order[reverse ? order.length - 1 - n : n];
                                    int row0 = k / tilesX * tile, col0 = k % tilesX * tile;
                                    int rows = Math.min(tile, height - row0);
                                    int cols = Math.min(tile, width - col0);
                                    for (int i = 0; i < rows * cols; i++) {
                                        int j = reverse ? rows * cols - 1 - i : i;
                                        fill(
                                                maps,
                                                habitats,
                                                r,
                                                row0 + j / cols,
                                                col0 + j % cols,
                                                minX,
                                                minZ,
                                                step,
                                                s,
                                                sub);
                                    }
                                    int d = done.incrementAndGet();
                                    if (d % Math.max(1, order.length / 10) == 0)
                                        System.out.printf("tiles %d/%d%n", d, order.length);
                                }
                                return null;
                            }));
        for (Future<?> f : work) f.get();
        pool.shutdown();
        double sampleSeconds = (System.nanoTime() - started) / 1e9;
        // Cores busy while sampling: process CPU over wall time (the log only, never the digest).
        double busyCores = (os.getProcessCpuTime() - cpuStarted) / 1e9 / sampleSeconds;

        List<List<String>> reasonNames = new ArrayList<>();
        for (int k = 0; k < STAGE_NAMES.length; k++) {
            List<String> names = new ArrayList<>();
            int[] map = r.reasons[k].renumber(names);
            byte[] codes = r.reason[k];
            for (int i = 0; i < codes.length; i++) codes[i] = (byte) map[codes[i] & 0xff];
            reasonNames.add(names);
        }
        List<Long> plateIds = new ArrayList<>(), provinceCells = new ArrayList<>();
        LongCodes.apply(r.plate, r.plates.renumber(plateIds));
        LongCodes.apply(r.province, r.provinces.renumber(provinceCells));
        // Not-wet columns hold 0, which LongCodes never assigns, so it maps to 0.
        LongCodes.apply(r.waterBody, r.waterBodies.renumber(new ArrayList<>()));
        List<String> provinceTypes = new ArrayList<>(), coastTypes = new ArrayList<>();
        int[] typeMap = r.provinceTypes.renumber(provinceTypes);
        int[] coastMap = r.coastTypes.renumber(coastTypes);
        for (int i = 0; i < r.coast.length; i++) {
            r.provinceType[i] = (byte) typeMap[r.provinceType[i] & 0xff];
            r.coast[i] = (byte) coastMap[r.coast[i] & 0xff];
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
        JsonArray variants = new JsonArray();
        variants.add("");
        for (int v = 0; v < Variants.count(); v++) variants.add(Variants.id(v));
        j.add("variants", variants);
        j.add("regions", array(ClimateRegion.values()));
        j.add("levels", array(Terrain.Level.values()));
        j.add("characters", array(Terrain.Character.values()));
        j.add("landforms", array(Terrain.Landform.values()));
        j.add("waterKinds", array(Hydro.Kind.values()));
        // Index 0 is "none" in each list; plate ids and packed province cells are 64-bit, so text.
        j.add("provinceTypes", array(provinceTypes));
        j.add("coastTypes", array(coastTypes));
        JsonArray plates = new JsonArray(), cells = new JsonArray();
        plates.add("");
        for (long id : plateIds) plates.add(Long.toString(id));
        cells.add("");
        for (long cell : provinceCells) cells.add(Long.toString(cell));
        j.add("plates", plates);
        j.add("provinceCells", cells);
        JsonObject flags = new JsonObject();
        flags.addProperty("marine", 1);
        flags.addProperty("supplied", 2);
        flags.addProperty("deepOcean", 4);
        flags.addProperty("wet", 8);
        j.add("flags", flags);
        JsonObject river = new JsonObject();
        river.addProperty("river", 1);
        river.addProperty("stream", 2);
        river.addProperty("channel", 4);
        river.addProperty("bar", 8);
        j.add("riverFlags", river);
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
                        + " threads (%s, %.1f cores busy); digest %s%n",
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
                busyCores,
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
        // 0 is none; variant k is k + 1, as the metadata's variants list names them.
        r.variant[i] =
                (byte) (maps.variants.at(x, z, RivenBiomeTable.select(Habitats.key(s)).index) + 1);
        r.region[i] = (byte) (s.climate.region == null ? -1 : s.climate.region.ordinal());
        r.level[i] = (byte) s.land.level.ordinal();
        r.character[i] = (byte) s.land.character.ordinal();
        r.landform[i] = (byte) (s.ocean.marine ? 255 : s.land.landform.ordinal());
        r.waterKind[i] = (byte) s.water.kind.ordinal();
        r.flags[i] =
                (byte)
                        ((s.ocean.marine ? 1 : 0)
                                | (s.ocean.supplied ? 2 : 0)
                                | (s.ocean.deepOcean ? 4 : 0)
                                | (s.water.wet ? 8 : 0));
        r.craton[i] = (float) s.geology.craton;
        r.volcanic[i] = (float) s.geology.volcanic;
        r.spine[i] = (float) s.geology.spine;
        double share = 0;
        for (double v : s.geology.province) share = Math.max(share, v);
        r.provinceShare[i] = (float) share;
        r.celsius[i] = (float) s.climate.celsius;
        r.moisture[i] = (float) s.climate.moisture;
        // Codes are provisional while threads sample; raster() renumbers them in sorted order.
        r.plate[i] = (short) r.plates.code(maps.plates.sample(x, z).plateId);
        r.province[i] = (short) r.provinces.code(s.province.cell);
        r.provinceType[i] =
                (byte) r.provinceTypes.code(s.province.type == null ? null : s.province.type.id);
        r.coast[i] = (byte) r.coastTypes.code(s.shore.type == null ? null : s.shore.type.id);
        Hydro.Sample w = s.water;
        r.waterBody[i] = (short) (w.wet ? r.waterBodies.code(w.id) : 0);
        r.river[i] =
                (byte)
                        ((w.wet && w.kind == Hydro.Kind.RIVER ? 1 : 0)
                                | (w.stream ? 2 : 0)
                                | (w.channel ? 4 : 0)
                                | (w.bar != null ? 8 : 0));
        r.mouth[i] = (byte) Math.round(Math.max(0, Math.min(1, w.mouth)) * 255);
        // Blocks beyond the nearest channel's edge for dry columns it reaches (255: none, or far).
        r.channelBeyond[i] = (byte) (w.channel ? Math.min(254, Math.round(w.channelBeyond)) : 255);
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
        double margin = 0;
        for (Sites.Lane lane : Sites.Lane.values())
            margin = Math.max(margin, sites.maximumRadius(lane));
        int c0 = (int) Math.floor((minX - margin) / sites.spacing) - 1;
        int c1 = (int) Math.floor((minX + width * step + margin) / sites.spacing) + 1;
        int r0 = (int) Math.floor((minZ - margin) / sites.spacing) - 1;
        int r1 = (int) Math.floor((minZ + height * step + margin) / sites.spacing) + 1;
        JsonArray list = new JsonArray();
        TerrainMap.Sample s = new TerrainMap.Sample();
        for (Sites.Lane lane : Sites.Lane.values())
            for (int cz = r0; cz <= r1; cz++)
                for (int cx = c0; cx <= c1; cx++) {
                    long key = sites.key(lane, cx, cz);
                    if (!sites.inLane(key, lane)) continue;
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

    /**
     * The tiles 0..n-1 in an order that spreads consecutive ones apart: a stride coprime with n
     * near n times the golden ratio's fractional part.
     */
    private static int[] spread(int n) {
        int stride = Math.max(1, (int) Math.round(n * 0.6180339887));
        while (gcd(stride, n) != 1) stride++;
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = (int) ((long) i * stride % n);
        return order;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static int stageOf(Sites.Lane lane) {
        switch (lane) {
            case LANDMARK:
                return 4;
            case CARVER:
                return 5;
            case COLD:
                return 2;
            case VOLCANIC:
                return 1;
            case CUTS:
                return 6;
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

    /** Fingerprints of every packaged definition, from the Definitions catalogue. */
    private static JsonObject fingerprints() {
        JsonObject f = new JsonObject();
        for (Rules rules : Definitions.loadAll()) f.addProperty(rules.resource, rules.fingerprint);
        return f;
    }

    private static void write(Path out, Raster r, MessageDigest digest) throws IOException {
        shorts(out, "surface.i16", r.surface, digest);
        shorts(out, "water.i16", r.water, digest);
        bytes(out, "habitat.u8", r.habitat, digest);
        bytes(out, "variant.u8", r.variant, digest);
        bytes(out, "region.u8", r.region, digest);
        bytes(out, "level.u8", r.level, digest);
        bytes(out, "character.u8", r.character, digest);
        bytes(out, "landform.u8", r.landform, digest);
        bytes(out, "water_kind.u8", r.waterKind, digest);
        bytes(out, "flags.u8", r.flags, digest);
        floats(out, "celsius.f32", r.celsius, digest);
        floats(out, "moisture.f32", r.moisture, digest);
        floats(out, "geo_craton.f32", r.craton, digest);
        floats(out, "geo_volcanic.f32", r.volcanic, digest);
        floats(out, "geo_spine.f32", r.spine, digest);
        floats(out, "geo_province_share.f32", r.provinceShare, digest);
        shorts(out, "plate.u16", r.plate, digest);
        shorts(out, "province.u16", r.province, digest);
        bytes(out, "province_type.u8", r.provinceType, digest);
        bytes(out, "coast.u8", r.coast, digest);
        shorts(out, "water_body.u16", r.waterBody, digest);
        bytes(out, "river.u8", r.river, digest);
        bytes(out, "mouth.u8", r.mouth, digest);
        bytes(out, "channel_beyond.u8", r.channelBeyond, digest);
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
