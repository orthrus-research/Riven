package cc.orthrus.riven.worldgen;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * Unbroken one-block contour lines on soil, from {@link TerrainMap#sample} per column.
 *
 * <p>On soil-mantled ground (cover reason CLIMATE, dry, no step over one block) each one-block
 * contour, the boundary between level k and k + 1, is traced as a polyline through the midpoints
 * of the column edges it crosses (marching squares). A <b>run</b> is a stretch of a contour along
 * which the direction, smoothed over six points, stays within {@code turn} degrees of where the
 * run began; it ends where the contour bends further, leaves soil, or meets a larger step. Long
 * runs are what reads as a smooth, evenly spaced contour line.
 *
 * <p>Reports, per window and per landform: contour length per soil column, the length-weighted
 * mean run and the run lengths holding half and a tenth of the contour length (median and p90 by
 * length), and the shares of contour length in runs of 32 and 64 blocks or more. For the contour
 * length in runs of {@code long} blocks or more, and for all of it, the height difference across
 * each contour edge is split between the base terms ({@link BaseTerrainMap#TERM_NAMES}), erosion
 * and the later stages: which part of the terrain makes the slope the long contours lie on.
 *
 * <p>Writes {@code report.json}, per window {@code runs<i>.png} (hillshade, soil contours coloured
 * by run length, grey to red at 64 blocks) and {@code heights<i>.bin}. With {@code
 * before=<dir>} of an earlier run on the same windows it also reports how far each soil column's
 * surface moved (|dh| histogram and percentiles).
 *
 * <p>Arguments: {@code out=<new dir> [seed=2485331] [scale=4] [threads=cores/2] [window=384]
 * [perClass=4] [survey=128] [turn=30] [long=48] [classes=PLAIN,HILLS,...] [centers=CLASS,x,z;...]
 * [before=<dir>]}.
 */
public final class ContourRunCheck {
    private ContourRunCheck() {}

    static final String[] LANDFORMS = {
        "BASIN", "PLAIN", "HILLS", "FOOTHILLS", "RIFT", "PLATEAU", "SCARP", "MOUNTAIN"
    };
    static final int SCARP = 6;

    /** Parts a contour edge's height difference is split between. */
    static final String[] PARTS;

    static final int TERMS = BaseTerrainMap.TERM_NAMES.length;

    static {
        List<String> p = new ArrayList<>(Arrays.asList(BaseTerrainMap.TERM_NAMES));
        p.addAll(
                List.of(
                        "erosion",
                        "passes",
                        "volcanic",
                        "cold",
                        "mountains",
                        "landmarks",
                        "carvers",
                        "cuts",
                        "drylands",
                        "shore+hydro",
                        "rocky",
                        "sediment",
                        "rounding"));
        PARTS = p.toArray(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (String s : args) {
            int e = s.indexOf('=');
            if (e <= 0) throw new IllegalArgumentException("expected key=value: " + s);
            a.put(s.substring(0, e), s.substring(e + 1));
        }
        if (!a.containsKey("out")) throw new IllegalArgumentException("out=<new dir> is required");
        Path out = Path.of(a.get("out"));
        Files.createDirectories(out);
        long seed = Long.parseLong(a.getOrDefault("seed", "2485331"));
        double scale = Double.parseDouble(a.getOrDefault("scale", "4"));
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Integer.parseInt(a.getOrDefault("threads", "" + Math.max(1, cores / 2)));
        int size = Integer.parseInt(a.getOrDefault("window", "384"));
        int perClass = Integer.parseInt(a.getOrDefault("perClass", "4"));
        int surveyStep = Integer.parseInt(a.getOrDefault("survey", "128"));
        double turn = Math.toRadians(Double.parseDouble(a.getOrDefault("turn", "30")));
        double longRun = Double.parseDouble(a.getOrDefault("long", "48"));
        List<String> classes =
                Arrays.asList(
                        a.getOrDefault("classes", "BASIN,PLAIN,HILLS,FOOTHILLS,PLATEAU,SCARP")
                                .split(","));
        Path before = a.containsKey("before") ? Path.of(a.get("before")) : null;
        long started = System.nanoTime();
        GenerationMaps maps = new GenerationMaps(seed, new WorldScale(scale));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger failures = new AtomicInteger();
        try {
            List<String[]> specs = new ArrayList<>();
            if (a.containsKey("centers")) {
                for (String c : a.get("centers").split(";")) specs.add(c.trim().split(","));
            } else {
                specs = pick(maps, pool, threads, seed, scale, surveyStep, size, perClass, classes);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            long sampleNanos = 0, columns = 0;
            for (int i = 0; i < specs.size(); i++) {
                String[] w = specs.get(i);
                int cx = Integer.parseInt(w[1].trim()), cz = Integer.parseInt(w[2].trim());
                int n = w.length > 3 ? Integer.parseInt(w[3].trim()) : size;
                long t = System.nanoTime();
                Window win =
                        Window.sample(maps, pool, threads, cx - n / 2, cz - n / 2, n, failures);
                sampleNanos += System.nanoTime() - t;
                columns += (long) n * n;
                Map<String, Object> m = win.metrics(turn, longRun);
                m.put("class", w[0]);
                m.put("cx", cx);
                m.put("cz", cz);
                m.put("size", n);
                if (before != null)
                    m.put("moved", win.moved(before.resolve("heights" + i + ".bin")));
                win.save(out.resolve("heights" + i + ".bin"));
                win.render(out.resolve("runs" + i + ".png"));
                rows.add(m);
                System.out.printf(
                        Locale.ROOT,
                        "window %d %s (%d, %d) soil %d, contour %.0f, weighted run %.1f,"
                                + " >=64 %.3f, %.1fs%n",
                        i,
                        w[0],
                        cx,
                        cz,
                        ((Number) m.get("soilColumns")).longValue(),
                        ((Number) m.get("contourBlocks")).doubleValue(),
                        ((Number) m.get("weightedRun")).doubleValue(),
                        ((Number) m.get("share64")).doubleValue(),
                        (System.nanoTime() - t) / 1e9);
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("seed", seed);
            report.put("scale", scale);
            report.put("terrain", TerrainMap.VERSION);
            report.put("turnDegrees", Math.toDegrees(turn));
            report.put("longRun", longRun);
            report.put("failedColumns", failures.get());
            report.put("microsPerColumn", sampleNanos / 1e3 / Math.max(1, columns));
            report.put("summary", summary(rows));
            report.put("windows", rows);
            Files.writeString(out.resolve("report.json"), Json.write(report) + "\n");
            System.out.println(Json.write(report.get("summary")));
            System.out.printf(
                    Locale.ROOT,
                    "%d windows in %.0fs, %.1f us/column, %d failed columns%n",
                    rows.size(),
                    (System.nanoTime() - started) / 1e9,
                    sampleNanos / 1e3 / Math.max(1, columns),
                    failures.get());
        } finally {
            pool.shutdown();
        }
        if (failures.get() > 0) System.exit(1);
    }

    /** Windows per landform from a survey of the world square, in landform order. */
    static List<String[]> pick(
            GenerationMaps maps,
            ExecutorService pool,
            int threads,
            long seed,
            double scale,
            int step,
            int size,
            int perClass,
            List<String> classes)
            throws Exception {
        int half = (int) (4000 * scale), n = 2 * half / step;
        byte[] landform = new byte[n * n];
        parallel(
                pool,
                threads,
                n,
                r -> {
                    TerrainMap.Sample s = new TerrainMap.Sample();
                    for (int c = 0; c < n; c++) {
                        maps.terrain.sample(-half + c * step, -half + r * step, s);
                        landform[r * n + c] =
                                (byte) (s.ocean.marine ? -1 : s.land.landform.ordinal());
                    }
                });
        Random rnd = new Random(seed ^ 0xc0e7L);
        int rad = Math.max(1, size / step / 2);
        List<String[]> specs = new ArrayList<>();
        for (int lf = 0; lf < LANDFORMS.length; lf++) {
            if (!classes.contains(LANDFORMS[lf])) continue;
            List<int[]> candidates = new ArrayList<>();
            for (int r = rad; r < n - rad; r++) {
                for (int c = rad; c < n - rad; c++) {
                    int same = 0, land = 0, total = 0;
                    for (int dr = -rad; dr <= rad; dr++) {
                        for (int dc = -rad; dc <= rad; dc++) {
                            int j = (r + dr) * n + c + dc;
                            total++;
                            if (landform[j] >= 0) land++;
                            if (landform[j] == lf) same++;
                        }
                    }
                    double need = lf == SCARP ? 0.2 : 0.6;
                    if (land >= 0.95 * total && same >= need * total)
                        candidates.add(new int[] {-half + c * step, -half + r * step});
                }
            }
            Collections.shuffle(candidates, rnd);
            int took = 0;
            for (int[] cand : candidates) {
                if (took >= perClass) break;
                boolean far = true;
                for (String[] w : specs)
                    if (Math.abs(Integer.parseInt(w[1]) - cand[0]) < 3000
                            && Math.abs(Integer.parseInt(w[2]) - cand[1]) < 3000) far = false;
                if (!far) continue;
                specs.add(new String[] {LANDFORMS[lf], "" + cand[0], "" + cand[1], "" + size});
                took++;
            }
        }
        return specs;
    }

    interface Row {
        void run(int r);
    }

    static void parallel(ExecutorService pool, int threads, int rows, Row work) throws Exception {
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int first = t;
            fs.add(
                    pool.submit(
                            () -> {
                                for (int r = first; r < rows; r += threads) work.run(r);
                                return null;
                            }));
        }
        for (Future<?> f : fs) f.get();
    }

    static final class Window {
        final int x0, z0, n;
        final int[] h;
        final boolean[] soil, dry;

        /** Per column, each part's cumulative height (see {@link #PARTS}), for crediting. */
        final float[][] part = new float[PARTS.length][];

        float[] runLength;

        /** Hydrology's hold on a column: 0 none, 1 a drainage valley side, 2 a bank blend. */
        final byte[] hydroKind;

        /** shore+hydro credit split by the higher column's {@link #hydroKind}. */
        final double[] hydroLong = new double[3], hydroAll = new double[3];

        Window(int x0, int z0, int n) {
            this.x0 = x0;
            this.z0 = z0;
            this.n = n;
            h = new int[n * n];
            soil = new boolean[n * n];
            dry = new boolean[n * n];
            hydroKind = new byte[n * n];
            for (int k = 0; k < PARTS.length; k++) part[k] = new float[n * n];
        }

        static Window sample(
                GenerationMaps maps,
                ExecutorService pool,
                int threads,
                int x0,
                int z0,
                int n,
                AtomicInteger failures)
                throws Exception {
            Window w = new Window(x0, z0, n);
            ThreadLocal<TerrainMap.Sample> scratch =
                    ThreadLocal.withInitial(TerrainMap.Sample::new);
            parallel(
                    pool,
                    threads,
                    n,
                    r -> {
                        for (int c = 0; c < n; c++) {
                            TerrainMap.Sample s = scratch.get();
                            int i = r * n + c;
                            try {
                                maps.terrain.sample(x0 + c, z0 + r, s);
                            } catch (RuntimeException e) {
                                if (failures.incrementAndGet() <= 5)
                                    System.out.printf(
                                            "column (%d, %d) failed: %s%n", x0 + c, z0 + r, e);
                                scratch.remove();
                                continue;
                            }
                            w.store(i, s, maps.surfaces);
                        }
                    });
            return w;
        }

        void store(int i, TerrainMap.Sample s, SurfaceRules rules) {
            h[i] = s.blockY();
            int wy = s.waterY();
            dry[i] = !s.ocean.marine && (!s.waterOverSurface() || wy == Integer.MIN_VALUE);
            soil[i] = dry[i] && rules.reason(s) == SurfaceRules.CoverReason.CLIMATE;
            hydroKind[i] =
                    (byte)
                            (s.water.weight > 0
                                    ? 2
                                    : s.water.channel && s.water.channelBeyond > 0 ? 1 : 0);
            BaseTerrainMap.Sample base = s.baseTerrain();
            // Cumulative: part k holds the height after part k, so differences credit each part.
            double acc = 0;
            for (int k = 0; k < TERMS; k++) {
                acc += base.term(k);
                part[k][i] = (float) acc;
            }
            double[] v = {
                s.originalHeight,
                s.passes.height,
                s.volcanic.height,
                s.cold.height,
                s.mountains.height,
                s.landmarks.height,
                s.carvers.height,
                s.cuts.height,
                s.drylands.height,
                s.rocky.parentHeight,
                s.rocky.height,
                s.sediment.height,
                h[i]
            };
            for (int k = 0; k < v.length; k++) part[TERMS + k][i] = (float) v[k];
        }

        void save(Path file) throws IOException {
            try (DataOutputStream o = new DataOutputStream(Files.newOutputStream(file))) {
                o.writeInt(n);
                for (int i = 0; i < n * n; i++) {
                    o.writeInt(h[i]);
                    o.writeBoolean(soil[i]);
                }
            }
        }

        Map<String, Object> moved(Path file) throws IOException {
            long[] hist = new long[6];
            long count = 0, changed = 0;
            double sum = 0;
            int max = 0;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != n) throw new IllegalStateException("window size differs");
                for (int i = 0; i < n * n; i++) {
                    int old = in.readInt();
                    boolean wasSoil = in.readBoolean();
                    if (!wasSoil || !soil[i]) continue;
                    int d = Math.abs(h[i] - old);
                    hist[Math.min(5, d)]++;
                    count++;
                    if (d > 0) changed++;
                    sum += d;
                    max = Math.max(max, d);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("soilColumns", count);
            m.put("changedShare", (double) changed / Math.max(1, count));
            m.put("meanAbs", sum / Math.max(1, count));
            m.put("max", max);
            List<Double> shares = new ArrayList<>();
            for (long v : hist) shares.add((double) v / Math.max(1, count));
            m.put("absHistogram0to5plus", shares);
            return m;
        }

        boolean qualifies(int i) {
            return soil[i];
        }

        /** Edge id: horizontal (r, c)-(r, c+1) is 2 i, vertical (r, c)-(r+1, c) is 2 i + 1. */
        double edgeX(int e) {
            int i = e >> 1;
            return (i % n) + ((e & 1) == 0 ? .5 : 0);
        }

        double edgeZ(int e) {
            int i = e >> 1;
            return (i / n) + ((e & 1) == 0 ? 0 : .5);
        }

        Map<String, Object> metrics(double turn, double longRun) {
            int cells = n * n;
            long soilCount = 0;
            for (int i = 0; i < cells; i++) if (soil[i]) soilCount++;
            // Links between crossing edges, at most two per edge.
            int[] link = new int[4 * cells];
            Arrays.fill(link, -1);
            for (int r = 0; r + 1 < n; r++) {
                for (int c = 0; c + 1 < n; c++) {
                    int i00 = r * n + c, i01 = i00 + 1, i10 = i00 + n, i11 = i10 + 1;
                    if (!qualifies(i00) || !qualifies(i01) || !qualifies(i10) || !qualifies(i11))
                        continue;
                    int lo = Math.min(Math.min(h[i00], h[i01]), Math.min(h[i10], h[i11]));
                    int hi = Math.max(Math.max(h[i00], h[i01]), Math.max(h[i10], h[i11]));
                    if (hi - lo != 1) continue;
                    // Square edges: top (i00-i01), bottom (i10-i11), left (i00-i10), right.
                    int top = 2 * i00, bottom = 2 * i10, left = 2 * i00 + 1, right = 2 * i01 + 1;
                    boolean a = h[i00] > lo, b = h[i01] > lo, cc = h[i10] > lo, d = h[i11] > lo;
                    List<Integer> crossing = new ArrayList<>(4);
                    if (a != b) crossing.add(top);
                    if (b != d) crossing.add(right);
                    if (d != cc) crossing.add(bottom);
                    if (cc != a) crossing.add(left);
                    if (crossing.size() == 2) {
                        connect(link, crossing.get(0), crossing.get(1));
                    } else if (crossing.size() == 4) {
                        // Saddle: keep the high corners apart.
                        if (a) {
                            connect(link, top, left);
                            connect(link, right, bottom);
                        } else {
                            connect(link, top, right);
                            connect(link, left, bottom);
                        }
                    }
                }
            }
            runLength = new float[cells];
            boolean[] seen = new boolean[2 * cells];
            List<Double> runs = new ArrayList<>();
            double[] creditLong = new double[PARTS.length], creditAll = new double[PARTS.length];
            double totalLength = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int e = 0; e < 2 * cells; e++) {
                    if (seen[e] || link[2 * e] < 0) continue;
                    // Paths start at an end (pass 0); what remains are loops (pass 1).
                    if (pass == 0 && link[2 * e + 1] >= 0) continue;
                    List<Integer> path = new ArrayList<>();
                    int prev = -1, cur = e;
                    while (cur >= 0 && !seen[cur]) {
                        seen[cur] = true;
                        path.add(cur);
                        int next = link[2 * cur] != prev ? link[2 * cur] : link[2 * cur + 1];
                        if (next == prev) next = -1;
                        prev = cur;
                        cur = next;
                    }
                    totalLength += trace(path, turn, longRun, runs, creditLong, creditAll);
                }
            }
            Collections.sort(runs);
            double sum = 0, sq = 0, over32 = 0, over64 = 0;
            for (double v : runs) {
                sum += v;
                sq += v * v;
                if (v >= 32) over32 += v;
                if (v >= 64) over64 += v;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("soilColumns", soilCount);
            m.put("contourBlocks", totalLength);
            m.put("contourPerThousandSoil", 1000 * totalLength / Math.max(1, soilCount));
            m.put("runs", runs.size());
            m.put("weightedRun", sq / Math.max(1e-9, sum));
            m.put("medianByLength", byLength(runs, sum, .5));
            m.put("p90ByLength", byLength(runs, sum, .9));
            m.put("share32", over32 / Math.max(1e-9, sum));
            m.put("share64", over64 / Math.max(1e-9, sum));
            m.put("longCredit", shares(creditLong));
            m.put("allCredit", shares(creditAll));
            m.put("longCreditRaw", creditLong);
            m.put("allCreditRaw", creditAll);
            m.put("longBlocks", over(runs, longRun));
            m.put("hydroLongOtherValleyBank", hydroLong.clone());
            m.put("hydroAllOtherValleyBank", hydroAll.clone());
            return m;
        }

        static double over(List<Double> runs, double min) {
            double s = 0;
            for (double v : runs) if (v >= min) s += v;
            return s;
        }

        static void connect(int[] link, int p, int q) {
            put(link, p, q);
            put(link, q, p);
        }

        static void put(int[] link, int p, int q) {
            if (link[2 * p] < 0) link[2 * p] = q;
            else if (link[2 * p + 1] < 0) link[2 * p + 1] = q;
            else throw new IllegalStateException("contour edge with three links");
        }

        /** Splits one contour into runs; returns its length. */
        double trace(
                List<Integer> path,
                double turn,
                double longRun,
                List<Double> runs,
                double[] creditLong,
                double[] creditAll) {
            int m = path.size();
            if (m < 2) return 0;
            double[] px = new double[m], pz = new double[m], seg = new double[m];
            for (int k = 0; k < m; k++) {
                px[k] = edgeX(path.get(k));
                pz[k] = edgeZ(path.get(k));
                if (k > 0) seg[k] = Math.hypot(px[k] - px[k - 1], pz[k] - pz[k - 1]);
            }
            double total = 0;
            int start = 0;
            double startAngle = tangent(px, pz, 0);
            double length = 0;
            for (int k = 1; k < m; k++) {
                length += seg[k];
                double angle = tangent(px, pz, k);
                double diff = Math.abs(Math.IEEEremainder(angle - startAngle, 2 * Math.PI));
                if (diff > turn || k == m - 1) {
                    finish(path, start, k, length, longRun, runs, creditLong, creditAll);
                    total += length;
                    start = k;
                    startAngle = angle;
                    length = 0;
                }
            }
            return total;
        }

        double tangent(double[] px, double[] pz, int k) {
            int m = px.length, a = Math.max(0, k - 3), b = Math.min(m - 1, k + 3);
            return Math.atan2(pz[b] - pz[a], px[b] - px[a]);
        }

        void finish(
                List<Integer> path,
                int from,
                int to,
                double length,
                double longRun,
                List<Double> runs,
                double[] creditLong,
                double[] creditAll) {
            runs.add(length);
            for (int k = from; k <= to; k++) {
                int e = path.get(k), i = e >> 1, j = (e & 1) == 0 ? i + 1 : i + n;
                runLength[i] = Math.max(runLength[i], (float) length);
                int lo = h[i] < h[j] ? i : j, hi = lo == i ? j : i;
                double prev = 0;
                for (int p = 0; p < PARTS.length; p++) {
                    double d = part[p][hi] - part[p][lo];
                    double c = d - prev;
                    prev = d;
                    creditAll[p] += c;
                    if (length >= longRun) creditLong[p] += c;
                    if (PARTS[p].equals("shore+hydro")) {
                        hydroAll[hydroKind[hi]] += c;
                        if (length >= longRun) hydroLong[hydroKind[hi]] += c;
                    }
                }
            }
        }

        static double byLength(List<Double> sorted, double sum, double q) {
            double acc = 0;
            for (int k = sorted.size() - 1; k >= 0; k--) {
                acc += sorted.get(k);
                if (acc >= q * sum) return sorted.get(k);
            }
            return 0;
        }

        void render(Path file) throws IOException {
            BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    int i = r * n + c;
                    int e = c + 1 < n ? h[i + 1] : h[i], s = r + 1 < n ? h[i + n] : h[i];
                    double shade = 0.6 + 0.12 * ((h[i] - e) + (h[i] - s));
                    shade = Math.max(0.2, Math.min(1, shade));
                    int base = soil[i] ? 150 : dry[i] ? 110 : 60;
                    int rr = (int) (base * shade), gg = (int) ((soil[i] ? 170 : base) * shade);
                    int bb = (int) ((dry[i] ? base : 200) * shade);
                    if (runLength[i] > 0) {
                        double t = Math.min(1, runLength[i] / 64);
                        rr = (int) (40 + 215 * t);
                        gg = (int) (40 * (1 - t));
                        bb = (int) (40 * (1 - t));
                    }
                    img.setRGB(c, r, (rr << 16) | (gg << 8) | bb);
                }
            }
            ImageIO.write(img, "png", file.toFile());
        }
    }

    /** Each continuous part's share of the height difference; rounding is left out. */
    static Map<String, Object> shares(double[] credit) {
        double total = 0;
        for (int k = 0; k < PARTS.length - 1; k++) total += credit[k];
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < PARTS.length - 1; k++)
            if (Math.abs(credit[k]) > 0.005 * Math.abs(total))
                m.put(PARTS[k], Math.round(1000 * credit[k] / total) / 1000.0);
        return m;
    }

    static Map<String, Object> summary(List<Map<String, Object>> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> by = new LinkedHashMap<>();
        for (Map<String, Object> r : rows)
            by.computeIfAbsent((String) r.get("class"), k -> new ArrayList<>()).add(r);
        by.put("ALL", rows);
        for (Map.Entry<String, List<Map<String, Object>>> e : by.entrySet()) {
            double soil = 0, contour = 0, weighted = 0, s32 = 0, s64 = 0;
            double[] cl = new double[PARTS.length], ca = new double[PARTS.length];
            for (Map<String, Object> r : e.getValue()) {
                double len = ((Number) r.get("contourBlocks")).doubleValue();
                soil += ((Number) r.get("soilColumns")).doubleValue();
                contour += len;
                weighted += len * ((Number) r.get("weightedRun")).doubleValue();
                s32 += len * ((Number) r.get("share32")).doubleValue();
                s64 += len * ((Number) r.get("share64")).doubleValue();
                double[] l = (double[]) r.get("longCreditRaw"),
                        al = (double[]) r.get("allCreditRaw");
                for (int k = 0; k < PARTS.length; k++) {
                    cl[k] += l[k];
                    ca[k] += al[k];
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("windows", e.getValue().size());
            m.put("soilColumns", (long) soil);
            m.put("contourPerThousandSoil", 1000 * contour / Math.max(1, soil));
            m.put("weightedRun", weighted / Math.max(1e-9, contour));
            m.put("share32", s32 / Math.max(1e-9, contour));
            m.put("share64", s64 / Math.max(1e-9, contour));
            m.put("longCredit", shares(cl));
            m.put("allCredit", shares(ca));
            out.put(e.getKey(), m);
        }
        for (Map<String, Object> r : rows) {
            r.remove("longCreditRaw");
            r.remove("allCreditRaw");
        }
        return out;
    }

    static final class Json {
        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(v, sb, "");
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        static void write(Object v, StringBuilder sb, String indent) {
            if (v instanceof Map<?, ?> m) {
                sb.append("{");
                String in = indent + "  ";
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    sb.append(first ? "\n" : ",\n").append(in);
                    sb.append('"').append(e.getKey()).append("\": ");
                    write(e.getValue(), sb, in);
                    first = false;
                }
                sb.append(first ? "}" : "\n" + indent + "}");
            } else if (v instanceof List<?> l) {
                sb.append("[");
                for (int k = 0; k < l.size(); k++) {
                    if (k > 0) sb.append(", ");
                    write(l.get(k), sb, indent);
                }
                sb.append("]");
            } else if (v instanceof double[] d) {
                List<Double> l = new ArrayList<>();
                for (double x : d) l.add(x);
                write(l, sb, indent);
            } else if (v instanceof Double d) {
                sb.append(Double.isFinite(d) ? String.format(Locale.ROOT, "%.4f", d) : "null");
            } else if (v instanceof String s) {
                sb.append('"').append(s).append('"');
            } else {
                sb.append(v);
            }
        }
    }
}
