package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.AssembledColumn;
import cc.orthrus.riven.worldgen.engine.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws drainage river mouths as text maps of assembled columns, to see how a river meets the sea:
 * '~' sea water, '=' surface water inland (river, lake), digits the dry ground's top above the
 * sea (0-9 blocks, '+' higher), '#' water above the sea level over a dry neighbour. Each map is
 * 96 x 96 blocks around a mouth, one character per two blocks, the river arriving from wherever
 * its course runs. Also prints the water and ground profile along the last 160 blocks of the
 * channel. args: [seed] [scale] [mouths]
 */
public final class RiverMouthProbe {
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 2485331L;
        double scale = args.length > 1 ? Double.parseDouble(args[1]) : 4;
        int wanted = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        GenerationMaps maps = new GenerationMaps(seed, new WorldScale(scale));
        Drainage drainage = maps.terrain.hydro.drainage;
        int sea = TerrainHeightReference.DEFAULT.waterTopY;
        double[] cells = toDoubles(drainage.continents(0, 0));
        List<double[][]> mouths = new ArrayList<>();
        for (int k = 0; k < cells.length; k += 2)
            for (double[][] c : drainage.channels((long) cells[k], (long) cells[k + 1])) {
                int last = c[0].length - 1;
                if (c[3][0] <= 0 || c[2][last] > sea + .5) continue;
                if (Math.abs(c[0][last]) > 6000 || Math.abs(c[1][last]) > 6000) continue;
                mouths.add(c);
            }
        // The widest mouths first: those are the ones a player sees.
        mouths.sort((a, b) -> Double.compare(b[3][b[3].length - 1], a[3][a[3].length - 1]));
        System.out.printf(
                "seed %d scale %s: %d river mouths near the origin%n", seed, scale, mouths.size());
        TerrainMap.Sample s = new TerrainMap.Sample();
        AssembledColumn col = new AssembledColumn();
        // Bed census: river and stream columns every 4 blocks over 4 km around the origin.
        java.util.EnumMap<Hydro.Bed, Integer> beds = new java.util.EnumMap<>(Hydro.Bed.class);
        for (int z = -2048; z < 2048; z += 4)
            for (int x = -2048; x < 2048; x += 4) {
                maps.terrain.sample(x, z, s);
                if (s.water.wet && s.water.kind == Hydro.Kind.RIVER && !s.ocean.marine)
                    beds.merge(s.water.bed, 1, Integer::sum);
            }
        System.out.println("river beds: " + beds);
        for (int m = 0; m < Math.min(wanted, mouths.size()); m++) {
            double[][] c = mouths.get(m);
            int last = c[0].length - 1;
            int mx = (int) Math.round(c[0][last]), mz = (int) Math.round(c[1][last]);
            System.out.printf(
                    "%nmouth %d at %d %d, half width %.1f, level %.2f%n",
                    m, mx, mz, c[3][last], c[2][last]);
            for (int dz = -48; dz < 48; dz += 2) {
                StringBuilder row = new StringBuilder();
                for (int dx = -48; dx < 48; dx += 2)
                    row.append(glyph(maps, mx + dx, mz + dz, sea, s, col));
                System.out.println(row);
            }
            System.out.println(
                    "along the channel from 160 blocks upstream: distance water ground kind");
            double total = 0;
            for (int i = 1; i <= last; i++)
                total += Rules.hypot(c[0][i] - c[0][i - 1], c[1][i] - c[1][i - 1]);
            double walked = 0;
            for (int i = 1; i <= last; i++) {
                walked += Rules.hypot(c[0][i] - c[0][i - 1], c[1][i] - c[1][i - 1]);
                if (walked < total - 160) continue;
                if (i % 4 != 0 && i != last) continue;
                int x = (int) Math.round(c[0][i]), z = (int) Math.round(c[1][i]);
                maps.terrain.sample(x, z, s);
                maps.columns.assembler().assemble(s, col);
                System.out.printf(
                        "  %6.0f  water %4d  top %4d  %s%s%n",
                        walked - total,
                        water(col),
                        top(col),
                        s.ocean.marine ? "marine " : "",
                        s.water.kind);
            }
        }
    }

    private static char glyph(
            GenerationMaps maps, int x, int z, int sea, TerrainMap.Sample s, AssembledColumn col) {
        maps.terrain.sample(x, z, s);
        maps.columns.assembler().assemble(s, col);
        int w = water(col), t = top(col);
        if (w > t) return s.ocean.marine ? '~' : w > sea ? '=' : '-';
        int rise = t - sea;
        return rise < 0 ? '_' : rise > 9 ? '+' : (char) ('0' + rise);
    }

    private static int water(AssembledColumn c) {
        for (int r = c.count() - 1; r >= 0; r--)
            if (c.runRole(r) == Role.FLUID) return c.top(r) - 1;
            else if (!c.runRole(r).open()) return Integer.MIN_VALUE;
        return Integer.MIN_VALUE;
    }

    private static int top(AssembledColumn c) {
        for (int r = c.count() - 1; r >= 0; r--)
            if (!c.runRole(r).open() && c.runRole(r) != Role.DECOR) return c.top(r) - 1;
        return c.bottomY() - 1;
    }

    private static double[] toDoubles(long[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = a[i];
        return d;
    }
}
