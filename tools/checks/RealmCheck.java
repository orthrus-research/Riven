package cc.orthrus.riven.worldgen;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The realms below the crust floor (riven-realms.json): every block from the world floor to the
 * crust floor is solid, bedrock lies only on the world floor and blocks do not depend on sampling
 * order. Prints the share of each block by depth around both boundaries, where the rock above
 * fades out, and each realm's shares below its band. args: [seed] [section.png]
 */
public final class RealmCheck {
    // Columns off every noise lattice: one on a lattice line samples the noise along that line.
    private static final int LO = -3963, HI = 4037, STEP = 160;

    public static void main(String[] args) throws java.io.IOException {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 8675309L;
        if (args.length > 1) {
            section(seed, args[1]);
            return;
        }
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        RealmMap map = new RealmMap(seed, h);
        List<RealmMap.Realm> realms = RealmMap.realms();
        double[] tops = new double[realms.size()];
        TerrainBlock crust = TerrainBlock.GABBRO;
        Map<Integer, EnumMap<TerrainBlock, Integer>> profile = new TreeMap<>();
        // Each realm's blocks below its transition band, whose shares its geology sets.
        List<EnumMap<TerrainBlock, Integer>> interior = new java.util.ArrayList<>();
        for (int k = 0; k < realms.size(); k++) interior.add(new EnumMap<>(TerrainBlock.class));
        long checksum = 0;
        int columns = 0;
        for (int z = LO; z <= HI; z += STEP)
            for (int x = LO; x <= HI; x += STEP) {
                columns++;
                map.tops(x, z, tops);
                for (int y = h.worldMinimumY; y < h.minimumY; y++) {
                    TerrainBlock b = map.block(x, y, z, tops, crust);
                    if (b == TerrainBlock.AIR)
                        throw new IllegalStateException("Air at " + x + "," + y + "," + z);
                    if ((b == TerrainBlock.BEDROCK) != (y == h.worldMinimumY))
                        throw new IllegalStateException("Bedrock off the world floor at Y " + y);
                    checksum = checksum * 31 + b.ordinal();
                    int realm = RealmMap.realm(y, tops);
                    if (y > h.worldMinimumY && tops[realm] - y > map.band())
                        interior.get(realm).merge(b, 1, Integer::sum);
                    // Depth relative to each boundary, in 8-block buckets, near the boundaries
                    // only.
                    for (int k = 0; k < realms.size(); k++) {
                        int d = (int) Math.floor((tops[k] - y) / 8);
                        if (d >= -2 && d < 8)
                            profile.computeIfAbsent(
                                            k * 100 + d, key -> new EnumMap<>(TerrainBlock.class))
                                    .merge(b, 1, Integer::sum);
                    }
                }
            }
        // The same blocks when sampled in the opposite order.
        long reverse = 0;
        for (int z = LO; z <= HI; z += STEP)
            for (int x = LO; x <= HI; x += STEP) {
                map.tops(x, z, tops);
                for (int y = h.worldMinimumY; y < h.minimumY; y++)
                    reverse = reverse * 31 + map.block(x, y, z, tops, crust).ordinal();
            }
        RealmMap other = new RealmMap(seed, h);
        long rebuilt = 0;
        for (int z = HI; z >= LO; z -= STEP)
            for (int x = HI; x >= LO; x -= STEP) {
                other.tops(x, z, tops);
                for (int y = h.minimumY - 1; y >= h.worldMinimumY; y--)
                    rebuilt +=
                            (long) other.block(x, y, z, tops, crust).ordinal()
                                    * (x * 31L + y * 17L + z);
            }
        long forward = 0;
        for (int z = LO; z <= HI; z += STEP)
            for (int x = LO; x <= HI; x += STEP) {
                map.tops(x, z, tops);
                for (int y = h.worldMinimumY; y < h.minimumY; y++)
                    forward +=
                            (long) map.block(x, y, z, tops, crust).ordinal()
                                    * (x * 31L + y * 17L + z);
            }
        if (checksum != reverse || forward != rebuilt)
            throw new IllegalStateException("Blocks depend on order");
        System.out.printf(
                "%d columns from Y%d to Y%d, order-independent%n",
                columns, h.worldMinimumY, h.minimumY - 1);
        for (Map.Entry<Integer, EnumMap<TerrainBlock, Integer>> e : profile.entrySet()) {
            int k = e.getKey() < 50 ? 0 : (e.getKey() + 50) / 100, d = e.getKey() - k * 100;
            int total = 0;
            for (int n : e.getValue().values()) total += n;
            StringBuilder line = new StringBuilder();
            for (Map.Entry<TerrainBlock, Integer> b : e.getValue().entrySet())
                line.append(String.format(" %s %.0f%%", b.getKey(), 100.0 * b.getValue() / total));
            System.out.printf(
                    "  %-8s top %+4d..%+4d below:%s%n", realms.get(k).id, d * 8, d * 8 + 7, line);
        }
        for (int k = 0; k < realms.size(); k++) {
            int total = 0;
            for (int n : interior.get(k).values()) total += n;
            List<Map.Entry<TerrainBlock, Integer>> rows =
                    new java.util.ArrayList<>(interior.get(k).entrySet());
            rows.sort((p, q) -> q.getValue() - p.getValue());
            StringBuilder line = new StringBuilder();
            for (Map.Entry<TerrainBlock, Integer> b : rows)
                line.append(String.format(" %s %.1f%%", b.getKey(), 100.0 * b.getValue() / total));
            System.out.printf("  %-8s below its band:%s%n", realms.get(k).id, line);
        }
    }

    private static final Map<TerrainBlock, Integer> COLOURS = new EnumMap<>(TerrainBlock.class);

    /** A block's section colour: a realm rock's main face averaged, else a fixed colour. */
    private static int colour(TerrainBlock b) {
        return COLOURS.computeIfAbsent(
                b,
                block -> {
                    switch (block) {
                        case GABBRO:
                            return 0x3b3f45;
                        case LEUCOBASALT:
                            return 0x9aa3ad;
                        case NETHERRACK:
                            return 0x8a2f2a;
                        case BEDROCK:
                            return 0x111111;
                        case AIR:
                            return 0x000000; // below the world floor
                        default:
                    }
                    java.io.File face =
                            new java.io.File(
                                    "src/main/resources/assets/riven/textures/blocks/rock/"
                                            + block.rockId()
                                            + "_0.png");
                    if (block.rockId() == null || !face.isFile()) return 0xff00ff;
                    try {
                        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(face);
                        // Animated faces stack frames vertically; the first frame is square.
                        int w = image.getWidth();
                        long r = 0, g = 0, bl = 0;
                        for (int y = 0; y < w; y++)
                            for (int x = 0; x < w; x++) {
                                int p = image.getRGB(x, y);
                                r += p >> 16 & 0xff;
                                g += p >> 8 & 0xff;
                                bl += p & 0xff;
                            }
                        long n = (long) w * w;
                        return (int) (r / n << 16 | g / n << 8 | bl / n);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    }

    /**
     * A vertical slice along X at Z 0, from Y-1680 to Y-480, one pixel per block, written as a PNG:
     * the crust's floor rock (gabbro here) and each realm's rock, so the fades can be inspected.
     */
    private static void section(long seed, String out) throws java.io.IOException {
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        RealmMap map = new RealmMap(seed, h);
        double[] tops = new double[RealmMap.realms().size()];
        int width = 1024, top = -480, bottom = -1680;
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(
                        width, top - bottom, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            map.tops(x, 0, tops);
            for (int y = bottom; y < top; y++) {
                int rgb =
                        y >= h.minimumY
                                ? 0x6f6a5f // the crust above its floor
                                : colour(map.block(x, y, 0, tops, TerrainBlock.GABBRO));
                image.setRGB(x, top - 1 - y, rgb);
            }
        }
        javax.imageio.ImageIO.write(image, "png", new java.io.File(out));
        System.out.println("section " + out);
    }
}
