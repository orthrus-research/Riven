package cc.orthrus.riven.worldgen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Drainage network footprint (tools only): builds the network of every continent whose support
 * reaches the world square (side 8000 x scale, centred on the origin) and reports where its heap
 * goes, channel arrays against the bin index, as each structure holds it. Arrays count 16 header
 * bytes plus their elements, references 4 bytes (compressed oops).
 *
 * <pre>
 * .local/atlas-venv/Scripts/python tools/run_check.py DrainageFootprint 8675309 4
 * </pre>
 */
public final class DrainageFootprint {
    private DrainageFootprint() {}

    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        double scale = Double.parseDouble(args[1]);
        GenerationMaps maps = new GenerationMaps(seed, new WorldScale(scale));
        Drainage drainage = maps.terrain.hydro.drainage;
        double half = 4000 * scale;
        Set<Long> cells = new LinkedHashSet<>();
        for (double z = -half; z <= half; z += 500)
            for (double x = -half; x <= half; x += 500) {
                long[] c = drainage.continents(x, z);
                for (int k = 0; k < c.length; k += 2)
                    cells.add((c[k] << 32) ^ (c[k + 1] & 0xffffffffL));
            }
        Method network = Drainage.class.getDeclaredMethod("network", long.class, long.class);
        network.setAccessible(true);
        long channelBytes = 0, indexBytes = 0, otherBytes = 0, points = 0, channels = 0;
        long dropped = 0;
        int networks = 0;
        System.out.printf(
                "%-9s %8s %9s %10s %10s%n",
                "continent", "channels", "points", "channelMB", "indexMB");
        for (long key : cells) {
            long gx = key >> 32, gz = (int) key;
            Object n = network.invoke(drainage, gx, gz);
            Object[] chs = (Object[]) get(n, "channels");
            if (chs.length == 0) continue;
            long cBytes = 0, cPoints = 0;
            for (Object ch : chs) {
                int length = ((double[]) get(ch, "x")).length;
                cPoints += length;
                // The channel object: id, 6 doubles, the flag and 11 array references.
                cBytes += 16 + 8 + 6 * 8 + 8 + 11 * 4;
                for (Field f : ch.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(ch);
                    if (v instanceof double[] d) cBytes += 16 + 8L * d.length;
                    else if (v instanceof byte[] b) cBytes += 16 + b.length;
                    else if (v == null && !f.getType().isPrimitive()) dropped += length;
                }
            }
            long iBytes = 0;
            for (String name : new String[] {"refs", "rivers"}) {
                Object bins = get(n, name);
                for (Field f : bins.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(bins);
                    if (v instanceof long[] l) iBytes += 16 + 8L * l.length;
                    else if (v instanceof int[] i) iBytes += 16 + 4L * i.length;
                }
            }
            double[] mouths = (double[]) get(n, "mouths");
            otherBytes += 16 + 8L * mouths.length + 16 + 4L * chs.length;
            networks++;
            channels += chs.length;
            points += cPoints;
            channelBytes += cBytes;
            indexBytes += iBytes;
            System.out.printf(
                    "%-9s %8d %9d %10.2f %10.2f%n",
                    gx + "," + gz, chs.length, cPoints, cBytes / 1048576.0, iBytes / 1048576.0);
        }
        System.out.printf(
                "%d networks, %d channels, %d points; channel arrays %.1f MB (%.1f arrays of"
                        + " points left out a point), bin index %.1f MB, other %.1f MB; %.2f MB per"
                        + " network%n",
                networks,
                channels,
                points,
                channelBytes / 1048576.0,
                dropped / (double) Math.max(1, points),
                indexBytes / 1048576.0,
                otherBytes / 1048576.0,
                (channelBytes + indexBytes + otherBytes) / 1048576.0 / Math.max(1, networks));
    }

    private static Object get(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
