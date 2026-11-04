package cc.orthrus.riven.worldgen;

/** Stateless smooth value noise; absolute coordinates make sampling order irrelevant. */
public final class SeededNoise {
    private final long seed;

    SeededNoise(long seed) {
        this.seed = seed;
    }

    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    public static long cell(long seed, int x, int z) {
        return mix(seed ^ mix(x * 0x9e3779b97f4a7c15L) ^ mix(z * 0xd1b54a32d192ed03L));
    }

    public static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    double sample(double x, double z) {
        return sample(x, z, null);
    }

    double sample(double x, double z, HeightSample out) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double u = fade(fx), v = fade(fz);
        double a = unit(cell(seed, ix, iz)) * 2 - 1;
        double b = unit(cell(seed, ix + 1, iz)) * 2 - 1;
        double c = unit(cell(seed, ix, iz + 1)) * 2 - 1;
        double d = unit(cell(seed, ix + 1, iz + 1)) * 2 - 1;
        double value = lerp(lerp(a, b, u), lerp(c, d, u), v);
        if (out != null)
            out.set(
                    value,
                    lerp(b - a, d - c, v) * derivative(fx),
                    (lerp(c, d, u) - lerp(a, b, u)) * derivative(fz));
        return value;
    }

    /** Unit gradient directions for {@link #gradient}, evenly spaced around the circle. */
    private static final double[] GX = new double[64], GZ = new double[64];

    static {
        for (int i = 0; i < 64; i++) {
            double a = (i + .5) * Math.PI / 32;
            GX[i] = Math.cos(a);
            GZ[i] = Math.sin(a);
        }
    }

    /**
     * Gradient (Perlin) noise in [-1, 1] with its exact derivatives: zero at every lattice node,
     * shaped by a random unit slope there, so its zero lines curve through the nodes in every
     * direction instead of following the cell edges as value noise's do. Scaled by sqrt(2), the
     * bound of unit-gradient noise in two dimensions.
     */
    void gradient(double x, double z, HeightSample out) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double u = fade(fx), v = fade(fz);
        int ga = (int) (cell(seed, ix, iz) >>> 58), gb = (int) (cell(seed, ix + 1, iz) >>> 58);
        int gc = (int) (cell(seed, ix, iz + 1) >>> 58);
        int gd = (int) (cell(seed, ix + 1, iz + 1) >>> 58);
        double a = GX[ga] * fx + GZ[ga] * fz;
        double b = GX[gb] * (fx - 1) + GZ[gb] * fz;
        double c = GX[gc] * fx + GZ[gc] * (fz - 1);
        double d = GX[gd] * (fx - 1) + GZ[gd] * (fz - 1);
        double k = a - b - c + d;
        double value = a + u * (b - a) + v * (c - a) + u * v * k;
        double kx = GX[ga] - GX[gb] - GX[gc] + GX[gd], kz = GZ[ga] - GZ[gb] - GZ[gc] + GZ[gd];
        double dx =
                GX[ga]
                        + u * (GX[gb] - GX[ga])
                        + v * (GX[gc] - GX[ga])
                        + u * v * kx
                        + derivative(fx) * (b - a + v * k);
        double dz =
                GZ[ga]
                        + u * (GZ[gb] - GZ[ga])
                        + v * (GZ[gc] - GZ[ga])
                        + u * v * kz
                        + derivative(fz) * (c - a + u * k);
        double s = Math.sqrt(2);
        out.set(Math.max(-1, Math.min(1, value * s)), dx * s, dz * s);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double derivative(double t) {
        return 30 * t * t * (t - 1) * (t - 1);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
