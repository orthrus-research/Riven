package cc.orthrus.riven.worldgen;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * The subsurface climate (riven-subsurface-climate.json) as the generator computes it, to find
 * the niches underground biomes could occupy.
 *
 * <pre>
 * ClimateSurvey stats SEED HALF STEP
 *     per realm and depth biome: percentiles of temperature, humidity and air over all
 *     underground volume and over open cave space, and the share of cave space in each climate
 *     class (cold, temperate, warm, hot, scorching by dry, damp, wet), with invariants checked
 * ClimateSurvey space SEED HALF STEP OUT.png
 *     a temperature by humidity heatmap per realm (cave space in the crust, all volume below it)
 * ClimateSurvey section SEED X1 Z1 X2 Z2 SPACING YMIN YMAX OUT.png
 *     a vertical slice in three panels: temperature, humidity and air (caves darkened)
 * </pre>
 */
public final class ClimateSurvey {
    private static final TerrainHeightReference H = TerrainHeightReference.DEFAULT;

    /** Realm names in survey order: the four crust realms, then the realms below. */
    private static List<String> realmNames() {
        List<String> names = new ArrayList<>();
        for (RealmMap.Crust c : RealmMap.Crust.values())
            names.add(c.name().toLowerCase(Locale.ROOT));
        for (RealmMap.Realm r : RealmMap.realms()) names.add(r.id);
        return names;
    }

    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[1]);
        GenerationMaps maps = new GenerationMaps(seed);
        RealmMap realms = new RealmMap(seed, H);
        if (a[0].equals("stats"))
            stats(maps, realms, Integer.parseInt(a[2]), Integer.parseInt(a[3]));
        else if (a[0].equals("space"))
            space(maps, realms, Integer.parseInt(a[2]), Integer.parseInt(a[3]), a[4]);
        else if (a[0].equals("section")) section(maps, realms, a);
        else throw new IllegalArgumentException(a[0]);
    }

    /** Visits every underground block of the sampled columns: realm index, open, reading. */
    private interface Visitor {
        void visit(int realm, int depthBiome, boolean open, SubsurfaceClimate.Reading r);
    }

    private static void walk(GenerationMaps maps, RealmMap realms, int half, int step, Visitor v) {
        int crustRealms = RealmMap.Crust.values().length;
        List<int[]> columns = new ArrayList<>();
        for (int z = -half; z <= half; z += step)
            for (int x = -half; x <= half; x += step) columns.add(new int[] {x, z});
        AtomicInteger next = new AtomicInteger();
        Thread[] threads = new Thread[Math.max(1, Runtime.getRuntime().availableProcessors() - 2)];
        for (int t = 0; t < threads.length; t++) {
            threads[t] =
                    new Thread(
                            () -> {
                                TerrainMap.Sample s = new TerrainMap.Sample();
                                SubsurfaceClimate.Reading r = new SubsurfaceClimate.Reading();
                                double[] tops = new double[RealmMap.realms().size()];
                                for (int i; (i = next.getAndIncrement()) < columns.size(); ) {
                                    int x = columns.get(i)[0], z = columns.get(i)[1];
                                    maps.terrain.sample(x, z, s);
                                    maps.terrain.caves.carve(x, z, s);
                                    realms.tops(x, z, tops);
                                    int top = s.blockY() - RealmMap.surfaceBand();
                                    for (int y = H.worldMinimumY + 1; y < top; y += 2) {
                                        if (y < H.minimumY) {
                                            int k = RealmMap.realm(y, tops);
                                            SubsurfaceClimate.at(y, s, k, r);
                                            v.visit(crustRealms + k, -1, false, r);
                                        } else {
                                            SubsurfaceClimate.at(y, s, -1, r);
                                            GeologyMap.Unit unit = s.geology.formation(y);
                                            v.visit(
                                                    RealmMap.crust(y, unit, s).ordinal(),
                                                    RealmMap.cell(y, s).index,
                                                    s.caves.voids(y),
                                                    r);
                                        }
                                    }
                                }
                            });
            threads[t].start();
        }
        for (Thread t : threads)
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
    }

    /** Fixed-width histograms of the three parameters, mergeable across threads. */
    private static final class Histogram {
        static final double C0 = -40, C1 = 160, CW = 1;
        final long[] celsius = new long[(int) ((C1 - C0) / CW) + 1];
        final long[] humidity = new long[101], air = new long[101];
        long count;

        synchronized void add(SubsurfaceClimate.Reading r) {
            int c = (int) Math.floor((Math.max(C0, Math.min(C1, r.celsius)) - C0) / CW);
            celsius[c]++;
            humidity[(int) Math.round(r.humidity * 100)]++;
            air[(int) Math.round(r.air * 100)]++;
            count++;
        }

        static double percentile(long[] h, long count, double q, double lo, double width) {
            long target = (long) Math.ceil(q * count), seen = 0;
            for (int i = 0; i < h.length; i++) {
                seen += h[i];
                if (seen >= target && count > 0) return lo + i * width;
            }
            return Double.NaN;
        }

        String describe() {
            if (count == 0) return "(none)";
            return String.format(
                    Locale.ROOT,
                    "%6.0f %6.0f %6.0f C | %4.2f %4.2f %4.2f hum | %4.2f %4.2f %4.2f air",
                    percentile(celsius, count, .1, C0, CW),
                    percentile(celsius, count, .5, C0, CW),
                    percentile(celsius, count, .9, C0, CW),
                    percentile(humidity, count, .1, 0, .01),
                    percentile(humidity, count, .5, 0, .01),
                    percentile(humidity, count, .9, 0, .01),
                    percentile(air, count, .1, 0, .01),
                    percentile(air, count, .5, 0, .01),
                    percentile(air, count, .9, 0, .01));
        }
    }

    private static final String[] HEAT = {"cold", "temperate", "warm", "hot", "scorching"};
    private static final double[] HEAT_EDGES = {0, 20, 40, 70};
    private static final String[] DAMP = {"dry", "damp", "wet"};
    private static final double[] DAMP_EDGES = {0.35, 0.7};

    private static int band(double v, double[] edges) {
        int k = 0;
        while (k < edges.length && v >= edges[k]) k++;
        return k;
    }

    private static void stats(GenerationMaps maps, RealmMap realms, int half, int step) {
        List<String> names = realmNames();
        int n = names.size(), depths = RealmMap.depths().size();
        Histogram[] all = new Histogram[n], open = new Histogram[n], biome = new Histogram[depths];
        for (int i = 0; i < n; i++) {
            all[i] = new Histogram();
            open[i] = new Histogram();
        }
        for (int i = 0; i < depths; i++) biome[i] = new Histogram();
        long[][] classes = new long[n][HEAT.length * DAMP.length];
        long[] frozen = new long[n], steam = new long[n];
        long started = System.nanoTime();
        walk(
                maps,
                realms,
                half,
                step,
                (realm, depthBiome, isOpen, r) -> {
                    if (!Double.isFinite(r.celsius)
                            || r.humidity < 0
                            || r.humidity > 1
                            || r.air < 0
                            || r.air > 1) throw new AssertionError("Climate out of range");
                    all[realm].add(r);
                    if (depthBiome >= 0) biome[depthBiome].add(r);
                    // Below the crust there are no caves yet: every block counts as space.
                    if (isOpen || depthBiome < 0) {
                        open[realm].add(r);
                        synchronized (classes) {
                            classes[realm][
                                    band(r.celsius, HEAT_EDGES) * DAMP.length
                                            + band(r.humidity, DAMP_EDGES)]++;
                            if (r.frozen) frozen[realm]++;
                            if (r.steam) steam[realm]++;
                        }
                    }
                });
        System.out.println("percentiles p10 p50 p90 of temperature, humidity and air");
        for (int i = 0; i < n; i++) {
            System.out.printf(Locale.ROOT, "%-9s all   %s%n", names.get(i), all[i].describe());
            System.out.printf(Locale.ROOT, "%-9s caves %s%n", "", open[i].describe());
        }
        System.out.println("by depth biome (all volume)");
        for (RealmMap.Depth d : RealmMap.depths())
            System.out.printf(Locale.ROOT, "%-18s %s%n", d.biome, biome[d.index].describe());
        System.out.println("climate classes: share of cave space (all volume below the crust)");
        StringBuilder head = new StringBuilder(String.format("%-9s", ""));
        for (String heat : HEAT)
            for (String damp : DAMP)
                head.append(
                        String.format(
                                " %9s",
                                heat.substring(0, Math.min(4, heat.length())) + "/" + damp));
        System.out.println(head + "    frozen   steam");
        for (int i = 0; i < n; i++) {
            long total = Arrays.stream(classes[i]).sum();
            StringBuilder row = new StringBuilder(String.format("%-9s", names.get(i)));
            for (long c : classes[i])
                row.append(
                        String.format(Locale.ROOT, " %8.1f%%", total == 0 ? 0 : 100.0 * c / total));
            row.append(
                    String.format(
                            Locale.ROOT,
                            " %7.1f%% %6.1f%%",
                            total == 0 ? 0 : 100.0 * frozen[i] / total,
                            total == 0 ? 0 : 100.0 * steam[i] / total));
            System.out.println(row);
        }
        System.out.printf(Locale.ROOT, "surveyed in %.1f s%n", (System.nanoTime() - started) / 1e9);
    }

    private static void space(GenerationMaps maps, RealmMap realms, int half, int step, String out)
            throws Exception {
        List<String> names = realmNames();
        int n = names.size(), w = 200, hgt = 100; // temperature -40..160 C by humidity 0..1
        long[][] grid = new long[n][w * hgt];
        walk(
                maps,
                realms,
                half,
                step,
                (realm, depthBiome, isOpen, r) -> {
                    if (!isOpen && depthBiome >= 0) return;
                    int cx = (int) Math.max(0, Math.min(w - 1, r.celsius + 40)),
                            cy = (int) Math.max(0, Math.min(hgt - 1, r.humidity * (hgt - 1)));
                    synchronized (grid) {
                        grid[realm][(hgt - 1 - cy) * w + cx]++;
                    }
                });
        int scale = 3, pad = 40, panelW = w * scale, panelH = hgt * scale, cols = 2;
        int rows = (n + cols - 1) / cols;
        BufferedImage image =
                new BufferedImage(
                        cols * (panelW + pad) + pad,
                        rows * (panelH + pad + 20) + pad,
                        BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xf2f3f0));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        for (int i = 0; i < n; i++) {
            int ox = pad + (i % cols) * (panelW + pad),
                    oy = pad + 20 + (i / cols) * (panelH + pad + 20);
            long max = 1;
            for (long c : grid[i]) max = Math.max(max, c);
            for (int py = 0; py < hgt; py++)
                for (int px = 0; px < w; px++) {
                    long c = grid[i][py * w + px];
                    int rgb = c == 0 ? 0xffffff : heat(Math.log1p(c) / Math.log1p(max));
                    g.setColor(new Color(rgb));
                    g.fillRect(ox + px * scale, oy + py * scale, scale, scale);
                }
            g.setColor(new Color(0x1d2421));
            g.drawRect(ox, oy, panelW, panelH);
            g.drawString(
                    names.get(i)
                            + (i < RealmMap.Crust.values().length
                                    ? " (cave space)"
                                    : " (all rock)"),
                    ox,
                    oy - 6);
            for (int c = -40; c <= 160; c += 40) {
                int px = ox + (c + 40) * scale;
                g.drawLine(px, oy + panelH, px, oy + panelH + 4);
                g.drawString(c + " C", px - 10, oy + panelH + 17);
            }
            g.drawString("wet", ox - 30, oy + 10);
            g.drawString("dry", ox - 30, oy + panelH);
        }
        g.dispose();
        ImageIO.write(image, "png", new File(out));
        System.out.println("space -> " + out);
    }

    /** White to amber to dark red, for counts. */
    private static int heat(double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (255 - 70 * t), gr = (int) (235 - 205 * t), b = (int) (200 - 190 * t);
        return (r << 16) | (gr << 8) | b;
    }

    private static int ramp(double t, int[] stops) {
        t = Math.max(0, Math.min(1, t)) * (stops.length - 1);
        int k = Math.min(stops.length - 2, (int) Math.floor(t));
        double f = t - k;
        int a = stops[k], b = stops[k + 1];
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f),
                gr = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f),
                bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return (r << 16) | (gr << 8) | bl;
    }

    private static final int[] T_STOPS = {
        0x2f5f9e, 0x9cc3e4, 0xf1efe6, 0xf1c35b, 0xd9541e, 0x6e1208
    };
    private static final int[] H_STOPS = {0xc9a46a, 0xe8e2c8, 0x7fb38b, 0x2f6f8f};
    private static final int[] A_STOPS = {0x3b1f4a, 0x8d7f95, 0xe9ecef};

    private static void section(GenerationMaps maps, RealmMap realms, String[] a) throws Exception {
        double x1 = Double.parseDouble(a[2]), z1 = Double.parseDouble(a[3]);
        double x2 = Double.parseDouble(a[4]), z2 = Double.parseDouble(a[5]);
        int spacing = Integer.parseInt(a[6]),
                y0 = Integer.parseInt(a[7]),
                y1 = Integer.parseInt(a[8]);
        int width = (int) Math.max(1, Math.hypot(x2 - x1, z2 - z1) / spacing), height = y1 - y0 + 1;
        int gap = 30;
        BufferedImage image =
                new BufferedImage(width, 3 * height + 2 * gap, BufferedImage.TYPE_INT_RGB);
        AtomicInteger next = new AtomicInteger();
        Thread[] threads = new Thread[Math.max(1, Runtime.getRuntime().availableProcessors() - 2)];
        for (int t = 0; t < threads.length; t++) {
            threads[t] =
                    new Thread(
                            () -> {
                                TerrainMap.Sample s = new TerrainMap.Sample();
                                SubsurfaceClimate.Reading r = new SubsurfaceClimate.Reading();
                                double[] tops = new double[RealmMap.realms().size()];
                                for (int i; (i = next.getAndIncrement()) < width; ) {
                                    double f = width == 1 ? 0 : i / (double) (width - 1);
                                    int x = (int) Math.floor(x1 + (x2 - x1) * f),
                                            z = (int) Math.floor(z1 + (z2 - z1) * f);
                                    maps.terrain.sample(x, z, s);
                                    maps.terrain.caves.carve(x, z, s);
                                    realms.tops(x, z, tops);
                                    int surface = s.blockY();
                                    for (int y = y0; y <= y1; y++) {
                                        int[] rgb = {0xffffff, 0xffffff, 0xffffff};
                                        if (y <= surface && y > H.worldMinimumY) {
                                            int k = y < H.minimumY ? RealmMap.realm(y, tops) : -1;
                                            SubsurfaceClimate.at(y, s, k, r);
                                            rgb[0] = ramp((r.celsius + 20) / 140, T_STOPS);
                                            rgb[1] = ramp(r.humidity, H_STOPS);
                                            rgb[2] = ramp(r.air, A_STOPS);
                                            if (y >= H.minimumY && s.caves.voids(y))
                                                for (int p = 0; p < 3; p++) rgb[p] = darker(rgb[p]);
                                        }
                                        for (int p = 0; p < 3; p++)
                                            image.setRGB(i, p * (height + gap) + (y1 - y), rgb[p]);
                                    }
                                }
                            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x1d2421));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        String[] labels = {"temperature -20 to 120 C", "humidity dry to wet", "air foul to fresh"};
        for (int p = 0; p < 3; p++) {
            g.setColor(Color.WHITE);
            g.fillRect(0, p * (height + gap) + height, width, gap);
            g.setColor(new Color(0x1d2421));
            g.drawString(labels[p], 8, p * (height + gap) + height + 20);
        }
        g.dispose();
        ImageIO.write(image, "png", new File(a[9]));
        System.out.println("section " + width + "x" + image.getHeight() + " -> " + a[9]);
    }

    private static int darker(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return ((r * 45 / 100) << 16) | ((g * 45 / 100) << 8) | (b * 45 / 100);
    }
}
