package cc.orthrus.riven.worldgen;

import java.security.MessageDigest;

/**
 * A SHA-256 of native chunk columns, every block, ore and unstable flag from the crust floor to
 * the allocation top, over chunks along land and coast diagonals and a block on the land route.
 * Caves, flood water, ores and terrain all take part, so equal digests before and after a change
 * prove it left native blocks unchanged where the atlas digests do not look (they sample no
 * caves). Usage: NativeDigestCheck [seed ...].
 */
public final class NativeDigestCheck {
    public static void main(String[] args) throws Exception {
        long[] seeds =
                args.length == 0
                        ? new long[] {8675309L, 101L, -991L}
                        : java.util.Arrays.stream(args).mapToLong(Long::parseLong).toArray();
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        for (long seed : seeds) {
            GenerationMaps maps = new GenerationMaps(seed);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            TerrainBlock[] line = new TerrainBlock[16];
            int chunks = 0;
            java.util.List<int[]> list = new java.util.ArrayList<>();
            for (int i = 0; i < 120; i++) list.add(new int[] {i * 3 - 180, i * 2 - 120});
            for (int cz = -1572; cz < -1556; cz++)
                for (int cx = 252; cx < 268; cx++) list.add(new int[] {cx, cz});
            long started = System.nanoTime();
            for (int[] c : list) {
                NativeColumns.Column column = maps.columns.build(c[0], c[1]);
                for (int index = 0; index < 256; index++) {
                    int x = c[0] * 16 + (index & 15), z = c[1] * 16 + (index >> 4);
                    for (int bottom = h.minimumY; bottom < h.maximumYExclusive; bottom += 16) {
                        column.cube(index, bottom, line);
                        for (int y = 0; y < 16; y++) {
                            int wy = bottom + y;
                            digest.update((byte) line[y].ordinal());
                            digest.update((byte) column.ore(index, x, wy, z));
                            digest.update((byte) (column.unstable(index, wy) ? 1 : 0));
                        }
                    }
                }
                chunks++;
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            System.out.printf(
                    "seed %d: %d chunk columns, native digest %s (%.1f s)%n",
                    seed, chunks, hex, (System.nanoTime() - started) / 1e9);
        }
    }
}
