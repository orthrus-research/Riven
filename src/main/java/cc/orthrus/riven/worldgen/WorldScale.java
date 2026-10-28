package cc.orthrus.riven.worldgen;

/**
 * Per-world layout scale, 0.5-8 (4 km to 64 km worlds). The definitions are tuned for 8 km worlds;
 * this factor multiplies the continental layout so a larger or smaller world keeps the same land
 * share, continents per world and climate blend: the continent lattice, warp, bays, bridges,
 * shelter and strait reach (continent crests follow in continent radii); temperature and moisture
 * wavelengths; plate cells and volcanic provinces; climate forcing's upwind reach and lattice.
 * Coast detail is self-similar: above 1 larger octaves fade in, below 1 the base octaves shrink.
 * Trunk river step caps and path capacity follow the outline's largest continent. Landforms,
 * rivers, mountains and sites keep their physical size ({@link TerrainScale}).
 *
 * <p>Read from the world's generator options ({@code scale=4}), which level.dat saves with the
 * world; servers set it with {@code generator-settings} in server.properties. Content is a pure
 * function of seed, definitions and this factor.
 */
public final class WorldScale {
    public static final double MINIMUM = .5, MAXIMUM = 8;
    public static final WorldScale DEFAULT = new WorldScale(1);

    public final double layout;

    public WorldScale(double layout) {
        if (!(layout >= MINIMUM && layout <= MAXIMUM))
            throw new IllegalArgumentException(
                    "Riven layout scale " + layout + " outside " + MINIMUM + "-" + MAXIMUM);
        this.layout = layout;
    }

    /** Blank options keep the 8 km tuning. Unknown keys fail rather than silently using 1. */
    public static WorldScale parse(String options) {
        if (options == null || options.trim().isEmpty()) return DEFAULT;
        double layout = 1;
        for (String entry : options.split("[;,]")) {
            String item = entry.trim();
            if (item.isEmpty()) continue;
            int split = item.indexOf('=');
            String key = split < 0 ? item : item.substring(0, split).trim();
            if (split < 0 || !key.equals("scale"))
                throw new IllegalArgumentException(
                        "Riven generator options: expected scale=<1-8>, got '" + item + "'");
            try {
                layout = Double.parseDouble(item.substring(split + 1).trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Riven generator options: " + item, error);
            }
        }
        return layout == 1 ? DEFAULT : new WorldScale(layout);
    }

    @Override
    public String toString() {
        return "scale=" + layout;
    }
}
