package cc.orthrus.riven.worldgen;

import java.awt.image.BufferedImage;
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
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * How a player on foot meets the surface, from {@link TerrainMap#sample} per column: step sizes,
 * walls and who made them, land cut off by walls, best-path detours, jumps and swims.
 *
 * <p>The player model is Minecraft 1.12's: a walker steps up at most one block (a jump) and drops
 * at most three without damage; swimming costs twice walking and climbing out of water allows one
 * block. A step of two or more blocks between dry neighbours is a <b>wall</b>. Each wall is
 * credited to the stage that made most of its height difference, or to rounding when no stage
 * made 0.75 blocks of it; walls credited to base ground are credited again to the base term
 * ({@link BaseTerrainMap#TERM_NAMES}, plus erosion) that made most of it. Separately, per stage
 * and base term, the report counts the walls that would fall under two blocks without that one
 * part of their step (the rest unchanged).
 *
 * <p>A survey every {@code survey} blocks over the world square picks, by seeded hash, up to
 * {@code perClass} windows per landform where that landform holds 60% of the window (15% for
 * scarps, which are thin); {@code centers} names windows instead, as {@code CLASS,x,z[,size]}
 * separated by semicolons, where class {@code CROSS} measures the best walk across the window
 * both ways. Every window is sampled at every block.
 *
 * <p>Writes {@code report.json} (every window and a summary per landform) and, per window, {@code
 * walls<i>.png}: hillshade, water blue, each wall's two columns coloured by the stage that made
 * it. Fails when a column throws; the report counts those columns.
 *
 * <p>Arguments: {@code out=<new dir> [seed=2485331] [scale=4] [threads=cores/2] [window=512]
 * [perClass=5] [survey=64] [centers=...]}.
 */
public final class TraversalCheck {
    private TraversalCheck() {}

    static final String[] LANDFORMS = {
        "BASIN", "PLAIN", "HILLS", "FOOTHILLS", "RIFT", "PLATEAU", "SCARP", "MOUNTAIN"
    };
    static final int SCARP = 6;
    static final String[] STAGES = {
        "base",
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
        "rounding"
    };
    static final int STAGE_COUNT = STAGES.length - 1, ROUNDING = STAGES.length - 1;
    static final int BASE_TERMS = BaseTerrainMap.TERM_NAMES.length + 1;
    static final double CREDIT = 0.75;
    static final int[][] STAGE_COLORS = {
        {220, 40, 40}, {245, 150, 30}, {150, 60, 20}, {120, 200, 255}, {140, 60, 200},
        {240, 90, 200}, {110, 70, 40}, {30, 180, 170}, {230, 220, 60}, {20, 90, 220},
        {90, 90, 90}, {200, 170, 120}, {255, 255, 255}
    };

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
        int size = Integer.parseInt(a.getOrDefault("window", "512"));
        int perClass = Integer.parseInt(a.getOrDefault("perClass", "5"));
        int surveyStep = Integer.parseInt(a.getOrDefault("survey", "64"));
        long started = System.nanoTime();
        GenerationMaps maps = new GenerationMaps(seed, new WorldScale(scale));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger failures = new AtomicInteger();
        try {
            List<Spec> specs =
                    a.containsKey("centers")
                            ? parse(a.get("centers"), size)
                            : pick(
                                    maps,
                                    pool,
                                    threads,
                                    seed,
                                    scale,
                                    surveyStep,
                                    size,
                                    perClass,
                                    failures);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                Spec w = specs.get(i);
                long t = System.nanoTime();
                Window win =
                        Window.sample(
                                maps,
                                pool,
                                threads,
                                w.cx - w.size / 2,
                                w.cz - w.size / 2,
                                w.size,
                                failures);
                Map<String, Object> m =
                        w.cls.equals("CROSS")
                                ? win.crossing()
                                : win.metrics(new Random(seed * 31 + i));
                m.put("class", w.cls);
                m.put("cx", w.cx);
                m.put("cz", w.cz);
                rows.add(m);
                win.render(out.resolve("walls" + i + ".png"));
                System.out.printf(
                        Locale.ROOT,
                        "window %d %s (%d, %d) %.1fs%n",
                        i,
                        w.cls,
                        w.cx,
                        w.cz,
                        (System.nanoTime() - t) / 1e9);
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("seed", seed);
            report.put("scale", scale);
            report.put("terrain", TerrainMap.VERSION);
            report.put("failedColumns", failures.get());
            report.put("summary", summary(rows));
            report.put("windows", rows);
            Files.writeString(out.resolve("report.json"), Json.write(report) + "\n");
            print(report);
            System.out.printf(
                    Locale.ROOT,
                    "%d windows in %.0fs, %d failed columns%n",
                    rows.size(),
                    (System.nanoTime() - started) / 1e9,
                    failures.get());
        } finally {
            pool.shutdown();
        }
        if (failures.get() > 0) System.exit(1);
    }

    record Spec(String cls, int cx, int cz, int size) {}

    static List<Spec> parse(String centers, int size) {
        List<Spec> specs = new ArrayList<>();
        for (String c : centers.split(";")) {
            String[] q = c.trim().split(",");
            specs.add(
                    new Spec(
                            q[0],
                            Integer.parseInt(q[1].trim()),
                            Integer.parseInt(q[2].trim()),
                            q.length > 3 ? Integer.parseInt(q[3].trim()) : size));
        }
        return specs;
    }

    /** Windows per landform from a survey of the world square, in landform order. */
    static List<Spec> pick(
            GenerationMaps maps,
            ExecutorService pool,
            int threads,
            long seed,
            double scale,
            int step,
            int size,
            int perClass,
            AtomicInteger failures)
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
                        int i = r * n + c;
                        try {
                            maps.terrain.sample(-half + c * step, -half + r * step, s);
                            landform[i] = (byte) (s.ocean.marine ? -1 : s.land.landform.ordinal());
                        } catch (RuntimeException e) {
                            failures.incrementAndGet();
                            landform[i] = -1;
                            s = new TerrainMap.Sample();
                        }
                    }
                });
        Random rnd = new Random(seed ^ 0x5eedL);
        int rad = Math.max(1, size / step / 2);
        List<Spec> specs = new ArrayList<>();
        for (int lf = 0; lf < LANDFORMS.length; lf++) {
            List<int[]> candidates = new ArrayList<>();
            for (int r = rad; r < n - rad; r += 2) {
                for (int c = rad; c < n - rad; c += 2) {
                    int same = 0, land = 0, total = 0;
                    for (int dr = -rad; dr < rad; dr++) {
                        for (int dc = -rad; dc < rad; dc++) {
                            int j = (r + dr) * n + c + dc;
                            total++;
                            if (landform[j] >= 0) land++;
                            if (landform[j] == lf) same++;
                        }
                    }
                    double need = lf == SCARP ? 0.15 : 0.6;
                    if (land >= 0.9 * total && same >= need * total) {
                        candidates.add(new int[] {-half + c * step, -half + r * step});
                    }
                }
            }
            Collections.shuffle(candidates, rnd);
            int took = 0;
            for (int[] cand : candidates) {
                if (took >= perClass) break;
                boolean far = true;
                for (Spec w : specs) {
                    if (Math.abs(w.cx - cand[0]) < 3000 && Math.abs(w.cz - cand[1]) < 3000) {
                        far = false;
                    }
                }
                if (!far) continue;
                specs.add(new Spec(LANDFORMS[lf], cand[0], cand[1], size));
                took++;
            }
        }
        return specs;
    }

    interface Row {
        void run(int r);
    }

    /** Rows r = t, t + threads, ... on each of threads workers; any failure propagates. */
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

    /** One window sampled at every block: surface, water, landform and every stage's height. */
    static final class Window {
        final int x0, z0, n;
        final int[] h, wy;
        final boolean[] dry;
        final byte[] landform;
        final float[][] stage = new float[STAGE_COUNT][], term = new float[BASE_TERMS][];

        Window(int x0, int z0, int n) {
            this.x0 = x0;
            this.z0 = z0;
            this.n = n;
            h = new int[n * n];
            wy = new int[n * n];
            dry = new boolean[n * n];
            landform = new byte[n * n];
            for (int k = 0; k < STAGE_COUNT; k++) stage[k] = new float[n * n];
            for (int k = 0; k < BASE_TERMS; k++) term[k] = new float[n * n];
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
                            try {
                                maps.terrain.sample(x0 + c, z0 + r, s);
                            } catch (RuntimeException e) {
                                if (failures.incrementAndGet() <= 5) {
                                    System.out.printf(
                                            "column (%d, %d) failed: %s%n", x0 + c, z0 + r, e);
                                }
                                scratch.remove();
                                w.dry[r * n + c] = false;
                                w.wy[r * n + c] = Integer.MIN_VALUE;
                                continue;
                            }
                            w.store(r * n + c, s);
                        }
                    });
            return w;
        }

        void store(int i, TerrainMap.Sample s) {
            h[i] = s.blockY();
            wy[i] = s.waterY();
            // Water with no surface level (a dry bed) is walked as ground.
            dry[i] = s.ocean.marine ? false : !s.waterOverSurface() || wy[i] == Integer.MIN_VALUE;
            landform[i] = (byte) (s.ocean.marine ? -1 : s.land.landform.ordinal());
            double[] v = {
                s.originalHeight, s.passes.height, s.volcanic.height, s.cold.height,
                s.mountains.height, s.landmarks.height, s.carvers.height, s.cuts.height,
                s.drylands.height, s.rocky.parentHeight, s.rocky.height, s.sediment.height
            };
            for (int k = 0; k < STAGE_COUNT; k++) stage[k][i] = (float) v[k];
            BaseTerrainMap.Sample base = s.baseTerrain();
            for (int k = 0; k < BASE_TERMS - 1; k++) term[k][i] = (float) base.term(k);
            term[BASE_TERMS - 1][i] = (float) (s.originalHeight - base.height());
        }

        /** Standing level: dry ground's top, else the water's surface. */
        int level(int i) {
            return dry[i] ? h[i] : wy[i];
        }

        boolean failed(int i) {
            return !dry[i] && wy[i] == Integer.MIN_VALUE;
        }

        static final double JUMP = 0.6, SWIM = 2.0;

        /** Cost of one move a to b of length len, or NaN when a walker cannot make it. */
        double cost(int a, int b, double len) {
            if (failed(a) || failed(b)) return Double.NaN;
            if (dry[a] && dry[b]) {
                int d = h[b] - h[a];
                if (d >= 2 || d < -3) return Double.NaN;
                return d == 1 ? len + JUMP : len;
            }
            if (dry[a]) return len * SWIM;
            int d = (dry[b] ? h[b] : wy[b]) - wy[a];
            if (d >= 2) return Double.NaN;
            if (dry[b]) return len + (d == 1 ? JUMP : 0);
            return len * SWIM;
        }

        /** The stage that made most of the step from i to j (d = h[j] - h[i]), or rounding. */
        int creditStage(int i, int j, int d) {
            int best = ROUNDING;
            double bv = CREDIT;
            for (int k = 0; k < STAGE_COUNT; k++) {
                double ck = stage[k][j] - stage[k][i];
                if (k > 0) ck -= stage[k - 1][j] - stage[k - 1][i];
                if (Math.signum(ck) == Math.signum(d) && Math.abs(ck) > bv) {
                    bv = Math.abs(ck);
                    best = k;
                }
            }
            return best;
        }

        int creditTerm(int i, int j, int d) {
            int best = 0;
            double bv = -1;
            for (int k = 0; k < BASE_TERMS; k++) {
                double ck = (term[k][j] - term[k][i]) * Math.signum(d);
                if (ck > bv) {
                    bv = ck;
                    best = k;
                }
            }
            return best;
        }

        byte[] wallStage;

        Map<String, Object> metrics(Random rnd) {
            int cells = n * n;
            long dryCount = 0, pairs = 0, gentle = 0, walls = 0, wallsNearWater = 0;
            long shoreEdges = 0, landable = 0;
            long[] steps = new long[6];
            long[] byStage = new long[STAGES.length], byTerm = new long[BASE_TERMS];
            // Walls that would be under 2 blocks without one stage's (or base term's) part of the
            // step, other parts unchanged: what removing that one source alone would clear.
            long[] needStage = new long[STAGE_COUNT], needTerm = new long[BASE_TERMS];
            long[] byLandform = new long[LANDFORMS.length];
            wallStage = new byte[cells];
            Arrays.fill(wallStage, (byte) -1);
            for (int i = 0; i < cells; i++) if (dry[i]) dryCount++;
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    int i = r * n + c;
                    if (dry[i] && r >= 4 && r + 4 < n && c >= 4 && c + 4 < n) {
                        double gx = (h[i + 4] - h[i - 4]) / 8.0,
                                gz = (h[i + 4 * n] - h[i - 4 * n]) / 8.0;
                        if (Math.hypot(gx, gz) <= 0.25) gentle++;
                    }
                    for (int j : new int[] {c + 1 < n ? i + 1 : -1, r + 1 < n ? i + n : -1}) {
                        if (j < 0 || failed(i) || failed(j)) continue;
                        if (dry[i] != dry[j]) {
                            int water = dry[i] ? j : i, land = dry[i] ? i : j;
                            shoreEdges++;
                            if (h[land] <= wy[water] + 1) landable++;
                            continue;
                        }
                        if (!dry[i]) continue;
                        int d = h[j] - h[i];
                        steps[bucket(Math.abs(d))]++;
                        pairs++;
                        if (Math.abs(d) < 2) continue;
                        walls++;
                        int low = d > 0 ? i : j;
                        if (landform[low] >= 0) byLandform[landform[low]]++;
                        if (nearWater(i) || nearWater(j)) wallsNearWater++;
                        for (int q = 0; q < STAGE_COUNT; q++) {
                            double part = stage[q][j] - stage[q][i];
                            if (q > 0) part -= stage[q - 1][j] - stage[q - 1][i];
                            if (Math.abs(d - part) < 2) needStage[q]++;
                        }
                        for (int q = 0; q < BASE_TERMS; q++) {
                            if (Math.abs(d - (term[q][j] - term[q][i])) < 2) needTerm[q]++;
                        }
                        int k = creditStage(i, j, d);
                        byStage[k]++;
                        if (k == 0) byTerm[creditTerm(i, j, d)]++;
                        wallStage[i] = (byte) k;
                        wallStage[j] = (byte) k;
                    }
                }
            }
            // Components of the two-way walkable graph (four neighbours): land outside the
            // largest is cut off.
            int[] comp = new int[cells];
            Arrays.fill(comp, -1);
            List<Integer> sizes = new ArrayList<>();
            int[] stack = new int[cells];
            for (int i = 0; i < cells; i++) {
                if (comp[i] >= 0) continue;
                int id = sizes.size(), sp = 0, size = 0;
                stack[sp++] = i;
                comp[i] = id;
                while (sp > 0) {
                    int u = stack[--sp];
                    if (dry[u]) size++;
                    int ur = u / n, uc = u % n;
                    for (int v :
                            new int[] {
                                uc > 0 ? u - 1 : -1,
                                uc < n - 1 ? u + 1 : -1,
                                ur > 0 ? u - n : -1,
                                ur < n - 1 ? u + n : -1
                            }) {
                        if (v < 0 || comp[v] >= 0) continue;
                        if (Double.isNaN(cost(u, v, 1)) || Double.isNaN(cost(v, u, 1))) continue;
                        comp[v] = id;
                        stack[sp++] = v;
                    }
                }
                sizes.add(size);
            }
            int largest = 0;
            for (int s : sizes) largest = Math.max(largest, s);
            long pocket = 0;
            for (int s : sizes) if (s < largest) pocket += s;
            // Best paths from up to 10 dry sources near the centre to 40 targets 128 to 384
            // blocks away each.
            List<Double> detours = new ArrayList<>(),
                    jumps100 = new ArrayList<>(),
                    swimShare = new ArrayList<>();
            int targets = 0, unreachable = 0;
            double[] dist = new double[cells];
            float[] jumps = new float[cells], swim = new float[cells];
            for (int src = 0, tries = 0; src < 10 && tries < 200; tries++) {
                int sr = n / 4 + rnd.nextInt(n / 2),
                        sc = n / 4 + rnd.nextInt(n / 2),
                        s0 = sr * n + sc;
                if (!dry[s0]) continue;
                src++;
                paths(List.of(s0), dist, jumps, swim);
                for (int k = 0, misses = 0; k < 40 && misses < 400; ) {
                    double ang = rnd.nextDouble() * 2 * Math.PI, d = 128 + rnd.nextDouble() * 256;
                    int tr = (int) Math.round(sr + d * Math.sin(ang));
                    int tc = (int) Math.round(sc + d * Math.cos(ang));
                    if (tr < 0 || tr >= n || tc < 0 || tc >= n || !dry[tr * n + tc]) {
                        misses++;
                        continue;
                    }
                    k++;
                    int t = tr * n + tc;
                    targets++;
                    double e = Math.hypot(tr - sr, tc - sc);
                    if (Double.isInfinite(dist[t])) {
                        unreachable++;
                        continue;
                    }
                    detours.add(dist[t] / e);
                    jumps100.add(jumps[t] * 100.0 / e);
                    swimShare.add(swim[t] / Math.max(1, dist[t]));
                }
            }
            double pr = Math.max(1, pairs), dc = Math.max(1, dryCount), wc = Math.max(1, walls);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("size", n);
            m.put("dry", dryCount / (double) cells);
            Map<String, Object> st = new LinkedHashMap<>();
            String[] names = {"0", "1", "2", "3", "4-7", "8+"};
            for (int k = 0; k < 6; k++) st.put(names[k], steps[k] / pr);
            m.put("steps", st);
            m.put("walls", (steps[2] + steps[3] + steps[4] + steps[5]) / pr);
            m.put("gentle", gentle / dc);
            m.put("pocketLand", pocket / dc);
            m.put("targets", targets);
            m.put("unreachable", unreachable / (double) Math.max(1, targets));
            m.put("detourP50", pct(detours, .5));
            m.put("detourP90", pct(detours, .9));
            m.put("jumpsPer100P50", pct(jumps100, .5));
            m.put("jumpsPer100P90", pct(jumps100, .9));
            m.put("swimShareP90", pct(swimShare, .9));
            m.put("relief", relief());
            m.put("wallsNearWater", walls == 0 ? 0.0 : wallsNearWater / wc);
            m.put("landableShore", shoreEdges == 0 ? 1.0 : landable / (double) shoreEdges);
            m.put("wallCount", walls);
            m.put("wallStage", shares(STAGES, byStage, walls));
            String[] termNames = Arrays.copyOf(BaseTerrainMap.TERM_NAMES, BASE_TERMS);
            termNames[BASE_TERMS - 1] = "erosion";
            m.put("baseWallTerm", shares(termNames, byTerm, byStage[0]));
            m.put(
                    "clearedWithoutStage",
                    shares(Arrays.copyOf(STAGES, STAGE_COUNT), needStage, walls));
            m.put("clearedWithoutTerm", shares(termNames, needTerm, walls));
            Map<String, Object> lf = new LinkedHashMap<>();
            for (int k = 0; k < LANDFORMS.length; k++)
                if (byLandform[k] > 0) lf.put(LANDFORMS[k], byLandform[k]);
            m.put("wallsByLandform", lf);
            return m;
        }

        boolean nearWater(int q) {
            int qr = q / n, qc = q % n;
            for (int dr = -2; dr <= 2; dr++) {
                for (int dc = -2; dc <= 2; dc++) {
                    int rr = qr + dr, cc = qc + dc;
                    if (rr >= 0 && rr < n && cc >= 0 && cc < n && !dry[rr * n + cc]) return true;
                }
            }
            return false;
        }

        /** The best walk from the west edge to the east, and from the north edge to the south. */
        Map<String, Object> crossing() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("size", n);
            int cells = n * n;
            double[] dist = new double[cells];
            float[] jumps = new float[cells], swim = new float[cells];
            for (int dir = 0; dir < 2; dir++) {
                List<Integer> sources = new ArrayList<>();
                for (int k = 0; k < n; k++) {
                    int i = dir == 0 ? k * n : k;
                    if (dry[i]) sources.add(i);
                }
                int[] peak = paths(sources, dist, jumps, swim);
                int best = -1;
                for (int k = 0; k < n; k++) {
                    int i = dir == 0 ? k * n + n - 1 : (n - 1) * n + k;
                    if (!Double.isInfinite(dist[i]) && (best < 0 || dist[i] < dist[best])) best = i;
                }
                List<Double> lineMax = new ArrayList<>();
                for (int k = 0; k < n; k += 4) {
                    int top = Integer.MIN_VALUE;
                    for (int t = 0; t < n; t++) {
                        int i = dir == 0 ? k * n + t : t * n + k;
                        if (!failed(i)) top = Math.max(top, level(i));
                    }
                    lineMax.add((double) top);
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("reachable", best >= 0);
                if (best >= 0) {
                    d.put("costRatio", dist[best] / (n - 1));
                    d.put("pathMaxY", peak[best]);
                    d.put("pathJumps", (double) jumps[best]);
                    d.put("pathSwim", (double) swim[best]);
                }
                d.put("lineMaxYp10", pct(lineMax, .1));
                d.put("lineMaxYp50", pct(lineMax, .5));
                m.put(dir == 0 ? "westEast" : "northSouth", d);
            }
            m.put("relief", relief());
            return m;
        }

        int relief() {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int i = 0; i < n * n; i++) {
                if (!dry[i]) continue;
                lo = Math.min(lo, h[i]);
                hi = Math.max(hi, h[i]);
            }
            return hi == Integer.MIN_VALUE ? 0 : hi - lo;
        }

        /**
         * Dijkstra over eight neighbours from the sources; a diagonal must clear both orthogonal
         * corners. Fills dist, the jumps and swum length along each best path, and returns the
         * highest standing level along it.
         */
        int[] paths(List<Integer> sources, double[] dist, float[] jumps, float[] swim) {
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            int[] peak = new int[n * n];
            PriorityQueue<double[]> q =
                    new PriorityQueue<>(
                            (p, o) ->
                                    p[0] != o[0]
                                            ? Double.compare(p[0], o[0])
                                            : Double.compare(p[1], o[1]));
            for (int s : sources) {
                dist[s] = 0;
                jumps[s] = 0;
                swim[s] = 0;
                peak[s] = level(s);
                q.add(new double[] {0, s});
            }
            int[] dr = {-1, -1, -1, 0, 0, 1, 1, 1}, dc = {-1, 0, 1, -1, 1, -1, 0, 1};
            while (!q.isEmpty()) {
                double[] top = q.poll();
                int u = (int) top[1];
                if (top[0] > dist[u]) continue;
                int ur = u / n, uc = u % n;
                for (int k = 0; k < 8; k++) {
                    int vr = ur + dr[k], vc = uc + dc[k];
                    if (vr < 0 || vr >= n || vc < 0 || vc >= n) continue;
                    int v = vr * n + vc;
                    double len = dr[k] != 0 && dc[k] != 0 ? Math.sqrt(2) : 1;
                    if (len > 1) {
                        int a1 = ur * n + vc, a2 = vr * n + uc;
                        if (Double.isNaN(cost(u, a1, 1))
                                || Double.isNaN(cost(a1, v, 1))
                                || Double.isNaN(cost(u, a2, 1))
                                || Double.isNaN(cost(a2, v, 1))) {
                            continue;
                        }
                    }
                    double c = cost(u, v, len);
                    if (Double.isNaN(c) || dist[u] + c >= dist[v]) continue;
                    dist[v] = dist[u] + c;
                    int lu = level(u), lv = level(v);
                    jumps[v] = jumps[u] + (dry[v] && lv - lu == 1 ? 1 : 0);
                    swim[v] = swim[u] + (!dry[u] || !dry[v] ? (float) len : 0);
                    peak[v] = Math.max(peak[u], lv);
                    q.add(new double[] {dist[v], v});
                }
            }
            return peak;
        }

        /** Hillshade, water blue, each wall's columns in its stage's colour. */
        void render(Path path) throws IOException {
            BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int i = 0; i < n * n; i++) {
                if (!dry[i]) continue;
                lo = Math.min(lo, h[i]);
                hi = Math.max(hi, h[i]);
            }
            double span = Math.max(1, hi - lo);
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    int i = r * n + c;
                    int[] rgb;
                    if (wallStage != null && wallStage[i] >= 0) {
                        rgb = STAGE_COLORS[wallStage[i]];
                    } else if (failed(i)) {
                        rgb = new int[] {255, 0, 255};
                    } else if (!dry[i]) {
                        rgb = new int[] {64, 115, 205};
                    } else {
                        double gx =
                                (h[r * n + Math.min(n - 1, c + 1)] - h[r * n + Math.max(0, c - 1)])
                                        / 2.0;
                        double gz =
                                (h[Math.min(n - 1, r + 1) * n + c] - h[Math.max(0, r - 1) * n + c])
                                        / 2.0;
                        double shade =
                                Math.max(0, (gx + gz) * -0.471 + 0.707)
                                        / Math.sqrt(1 + gx * gx + gz * gz);
                        double t = (h[i] - lo) / span, f = 0.35 + 0.65 * Math.min(1, shade);
                        rgb =
                                new int[] {
                                    (int) (255 * (0.55 + 0.35 * t) * f),
                                    (int) (255 * (0.62 + 0.2 * t) * f),
                                    (int) (255 * (0.42 + 0.3 * t) * f)
                                };
                    }
                    img.setRGB(c, r, (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
                }
            }
            ImageIO.write(img, "png", path.toFile());
        }
    }

    static int bucket(int d) {
        return d <= 3 ? d : d <= 7 ? 4 : 5;
    }

    static double pct(List<Double> v, double q) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get((int) Math.min(s.size() - 1, Math.floor(q * s.size())));
    }

    static Map<String, Object> shares(String[] names, long[] counts, long total) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < names.length; k++) {
            if (counts[k] > 0) m.put(names[k], counts[k] / (double) Math.max(1, total));
        }
        return m;
    }

    /** Per landform class: window means of the headline figures and the worst window's walls. */
    static Map<String, Object> summary(List<Map<String, Object>> rows) {
        String[] keys = {
            "walls",
            "pocketLand",
            "unreachable",
            "detourP50",
            "detourP90",
            "jumpsPer100P50",
            "jumpsPer100P90",
            "gentle",
            "wallsNearWater",
            "landableShore"
        };
        Map<String, Object> out = new LinkedHashMap<>();
        for (String cls : LANDFORMS) {
            List<Map<String, Object>> of = new ArrayList<>();
            for (Map<String, Object> r : rows) if (cls.equals(r.get("class"))) of.add(r);
            if (of.isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("windows", of.size());
            for (String k : keys) {
                double sum = 0;
                int count = 0;
                for (Map<String, Object> r : of) {
                    double v = (Double) r.get(k);
                    if (Double.isNaN(v)) continue;
                    sum += v;
                    count++;
                }
                m.put(k, count == 0 ? Double.NaN : sum / count);
            }
            double worst = 0;
            for (Map<String, Object> r : of) worst = Math.max(worst, (Double) r.get("walls"));
            m.put("wallsMax", worst);
            out.put(cls, m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void print(Map<String, Object> report) {
        System.out.println(
                "class      n  walls%  max%  pocket%  detour p50/p90  jumps/100 p50/p90  gentle%");
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        for (Map.Entry<String, Object> e : summary.entrySet()) {
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            System.out.printf(
                    Locale.ROOT,
                    "%-9s %2d  %5.2f  %5.1f  %6.2f   %.2f / %.2f      %5.1f / %5.1f      %4.0f%n",
                    e.getKey(),
                    (Integer) m.get("windows"),
                    100 * (Double) m.get("walls"),
                    100 * (Double) m.get("wallsMax"),
                    100 * (Double) m.get("pocketLand"),
                    (Double) m.get("detourP50"),
                    (Double) m.get("detourP90"),
                    (Double) m.get("jumpsPer100P50"),
                    (Double) m.get("jumpsPer100P90"),
                    100 * (Double) m.get("gentle"));
        }
    }

    /** Just enough JSON for the report: maps, lists, strings, numbers and booleans. */
    static final class Json {
        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(v, sb, "");
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        static void write(Object v, StringBuilder sb, String indent) {
            if (v instanceof Map<?, ?> m) {
                String in = indent + "  ";
                sb.append("{");
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                    sb.append(first ? "\n" : ",\n")
                            .append(in)
                            .append('"')
                            .append(e.getKey())
                            .append("\": ");
                    write(e.getValue(), sb, in);
                    first = false;
                }
                sb.append(first ? "}" : "\n" + indent + "}");
            } else if (v instanceof List<?> l) {
                String in = indent + "  ";
                sb.append("[");
                for (int i = 0; i < l.size(); i++) {
                    sb.append(i == 0 ? "\n" : ",\n").append(in);
                    write(l.get(i), sb, in);
                }
                sb.append(l.isEmpty() ? "]" : "\n" + indent + "]");
            } else if (v instanceof Double d) {
                sb.append(
                        d.isNaN() || d.isInfinite()
                                ? "null"
                                : String.format(Locale.ROOT, "%.4f", d));
            } else if (v instanceof String s) {
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else {
                sb.append(v);
            }
        }
    }
}
