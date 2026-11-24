package cc.orthrus.riven.worldgen;

import java.util.Locale;

/**
 * Tour views facing rock faces. Usage: StrataViews SEED LAYOUT DISTANCE LABEL:X:Z ...
 *
 * <p>For each face column, finds the direction of steepest fall over 4 blocks among 16, prints the
 * ground profile along it, and places a camera DISTANCE blocks out on the low side, above the
 * ground there and at the face's middle height, looking back at the face. Prints one tour entry
 * per face (wb play --tour).
 */
public final class StrataViews {
    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        double layout = Double.parseDouble(args[1]);
        int distance = Integer.parseInt(args[2]);
        GenerationMaps maps =
                new GenerationMaps(seed, layout == 1 ? WorldScale.DEFAULT : new WorldScale(layout));
        TerrainMap.Sample s = new TerrainMap.Sample();
        StringBuilder tour = new StringBuilder("[\n");
        for (int i = 3; i < args.length; i++) {
            String[] p = args[i].split(":");
            String label = p[0];
            int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            int top = ground(maps, s, x, z);
            double best = 0, bx = 1, bz = 0;
            for (int k = 0; k < 16; k++) {
                double a = k * Math.PI / 8, dx = Math.cos(a), dz = Math.sin(a);
                int drop = top - ground(maps, s, x + dx * 4, z + dz * 4);
                if (drop > best) {
                    best = drop;
                    bx = dx;
                    bz = dz;
                }
            }
            StringBuilder profile = new StringBuilder();
            int lowest = top;
            for (int d = -8; d <= distance; d += 4) {
                int g = ground(maps, s, x + bx * d, z + bz * d);
                lowest = Math.min(lowest, g);
                profile.append(d).append(':').append(g).append(' ');
            }
            double cx = x + bx * distance, cz = z + bz * distance;
            int camGround = ground(maps, s, cx, cz);
            maps.terrain.sample(Math.floor(cx), Math.floor(cz), s);
            int water = s.waterY();
            int y = Math.max(Math.max(camGround, water) + 3, (top + lowest) / 2);
            // Look back at the face: direction (-bx, -bz); Minecraft yaw 0 faces +Z, 90 faces -X.
            double yaw = Math.toDegrees(Math.atan2(bx, -bz));
            double pitch = Math.toDegrees(Math.atan2(y - (top + lowest) / 2.0, distance));
            maps.terrain.sample(x, z, s);
            System.out.printf(
                    Locale.ROOT,
                    "%s at %d,%d top %d, fall %.0f over 4 toward (%.2f,%.2f), %s %s%n  profile %s%n",
                    label,
                    x,
                    z,
                    top,
                    best,
                    bx,
                    bz,
                    s.climate.region,
                    s.geology.unit(top - 4).name,
                    profile);
            tour.append(
                    String.format(
                            Locale.ROOT,
                            "  {\"label\": \"%s\", \"x\": %.1f, \"y\": %d, \"z\": %.1f, \"yaw\":"
                                    + " %.0f, \"pitch\": %.0f}%s%n",
                            label,
                            Math.floor(cx) + 0.5,
                            y,
                            Math.floor(cz) + 0.5,
                            yaw,
                            pitch,
                            i + 1 < args.length ? "," : ""));
        }
        System.out.print(tour.append("]\n"));
    }

    private static int ground(GenerationMaps maps, TerrainMap.Sample s, double x, double z) {
        maps.terrain.sample(Math.floor(x), Math.floor(z), s);
        return s.blockY();
    }
}
