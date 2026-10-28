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
