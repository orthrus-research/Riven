package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.AssembledColumn;
import cc.orthrus.riven.worldgen.engine.Assembler;

import java.util.Arrays;

/**
 * The crust's carve forms reproduce today's caves (B2, before the switch). Usage: CaveEngineCheck
 * [seed ...]. For every column of chunk columns along a diagonal crossing land and coast, and a
 * coarse grid, requires: the forms walk (sub-zones from riven-zones.json) carves the same voids,
 * linings and family at every Y as the cascade; and the column assembled with the engine's cave
 * fills (roof band, entrances, pools, D15's precedence) equals the column assembled with the
 * temporary carve, run for run. A column whose sky's floor is not its surface (a rocky volume's
 * overhang) may differ in the skin, and is counted apart.
 */
public final class CaveEngineCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static int[] voids(CaveMap.Voids v) {
        int[] out = new int[2 + 2 * v.count + 2 * v.linings];
        out[0] = v.count;
        out[1] = v.linings;
        int k = 2;
        for (int i = 0; i < v.count; i++) {
            out[k++] = v.lo[i];
            out[k++] = v.hi[i];
        }
        for (int i = 0; i < v.linings; i++) {
            out[k++] = v.liningLo[i];
            out[k++] = v.liningHi[i] * 1000 + v.liningBlock[i].index();
        }
        return out;
    }

    private static int[] runs(AssembledColumn c) {
        int[] out = new int[3 * c.count() + 1];
        for (int i = 0; i < c.count(); i++) {
            out[3 * i] = c.bottom(i);
            out[3 * i + 1] = c.runEntry(i);
            out[3 * i + 2] = (c.runRole(i).ordinal() << 8) | c.runZone(i);
        }
        out[out.length - 1] = c.topY();
        return out;
    }

    public static void main(String[] args) {
        long[] seeds =
                args.length == 0
                        ? new long[] {8675309L, 101L, 1L, 5L}
                        : Arrays.stream(args).mapToLong(Long::parseLong).toArray();
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        int span = h.maximumYExclusive - h.minimumY;
        long columns = 0, carvedBlocks = 0, voidRuns = 0, volumeColumns = 0, wetSkin = 0;
        long[] byFamily = new long[CaveMap.FAMILIES.length];
        AssembledColumn a = new AssembledColumn(), b = new AssembledColumn();
        for (long seed : seeds) {
            GenerationMaps maps = new GenerationMaps(seed);
            TerrainMap terrain = maps.terrain;
            CaveMap caves = terrain.caves;
            Assembler<TerrainMap.Sample> temporary = CrustShape.assembler(maps.surfaces, h, false),
                    engine = CrustShape.assembler(maps.surfaces, h, true);
            TerrainMap.Sample s = new TerrainMap.Sample();
            byte[] cascade = new byte[span], forms = new byte[span];
            // Chunk columns along a diagonal (as the native check), then a coarse grid.
            int[][] at = new int[160 * 256 + 64 * 64][];
            int n = 0;
            for (int i = 0; i < 160; i++)
                for (int index = 0; index < 256; index++)
                    at[n++] =
                            new int[] {
                                (i * 3 - 240) * 16 + (index & 15), (i * 2 - 160) * 16 + (index >> 4)
                            };
            for (int gz = 0; gz < 64; gz++)
                for (int gx = 0; gx < 64; gx++)
                    at[n++] = new int[] {-8192 + gx * 256 + 101, -8192 + gz * 256 + 67};
            for (int[] p : at) {
                int x = p[0], z = p[1];
                String where = x + "," + z + " seed " + seed;
                columns++;
                terrain.sample(x, z, s);
                boolean volume = s.rocky.hasVolume();
                if (volume) volumeColumns++;
                Arrays.fill(cascade, (byte) 0);
                boolean roomA = caves.carveCascade(x, z, s, cascade);
                int[] voidsA = voids(s.caves);
                terrain.sample(x, z, s);
                Arrays.fill(forms, (byte) 0);
                boolean roomB =
                        caves.carveForms(x, z, s, forms, Integer.MIN_VALUE, Integer.MAX_VALUE);
                int[] voidsB = voids(s.caves);
                if (volume && !Arrays.equals(voidsA, voidsB)) continue;
                require(roomA == roomB, "Room for caves differs at " + where);
                for (int y = 0; y < span; y++)
                    require(
                            cascade[y] == forms[y],
                            "Family differs at "
                                    + where
                                    + " y "
                                    + (y + h.minimumY)
                                    + ": cascade "
                                    + CaveMap.FAMILIES[cascade[y]]
                                    + ", forms "
                                    + CaveMap.FAMILIES[forms[y]]);
                require(Arrays.equals(voidsA, voidsB), "Voids or linings differ at " + where);
                for (byte f : forms) {
                    byFamily[f]++;
                    if (f != 0) carvedBlocks++;
                }
                if (CaveMap.wet(s) && s.blockY() - h.minimumY > 0)
                    for (int y = Math.max(0, s.blockY() - 39 - h.minimumY);
                            y <= s.blockY() - h.minimumY && y < span;
                            y++) if (forms[y] != 0) wetSkin++;
                // The whole carve (pools, seeps, decor) through both paths.
                terrain.sample(x, z, s);
                caves.carve(x, z, s);
                voidRuns += s.caves.count;
                int[] runsA = runs(temporary.assemble(s, a)), runsB = runs(engine.assemble(s, b));
                if (!Arrays.equals(runsA, runsB)) {
                    StringBuilder d = new StringBuilder();
                    for (int i = 0; i < Math.min(a.count(), b.count()); i++)
                        if (a.bottom(i) != b.bottom(i)
                                || a.runEntry(i) != b.runEntry(i)
                                || a.runRole(i) != b.runRole(i)
                                || a.runZone(i) != b.runZone(i)) {
                            d.append(" first differing run ")
                                    .append(i)
                                    .append(": temporary ")
                                    .append(a.bottom(i))
                                    .append(' ')
                                    .append(a.runBlock(i))
                                    .append(' ')
                                    .append(a.runRole(i))
                                    .append(", engine ")
                                    .append(b.bottom(i))
                                    .append(' ')
                                    .append(b.runBlock(i))
                                    .append(' ')
                                    .append(b.runRole(i));
                            break;
                        }
                    throw new AssertionError("Assembled columns differ at " + where + d);
                }
            }
            System.out.println("seed " + seed + ": ok");
        }
        System.out.println(
                "PASS "
                        + columns
                        + " columns ("
                        + volumeColumns
                        + " under a rocky volume), "
                        + voidRuns
                        + " voids, "
                        + carvedBlocks
                        + " carved blocks, "
                        + wetSkin
                        + " of them in a wet column's skin");
        for (int f = 1; f < byFamily.length; f++)
            if (byFamily[f] > 0) System.out.println("  " + CaveMap.FAMILIES[f] + " " + byFamily[f]);
    }
}
