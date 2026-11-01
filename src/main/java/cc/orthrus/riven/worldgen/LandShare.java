package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Holds every world's land share in a band ({@code riven-continents.json} {@code land}). The share
 * is the part of the world square whose outline land weight is above zero, before any shaping,
 * measured on a grid of {@code measureSide} samples per side whose step follows the world's size.
 * Attempts are seeded redraws of the outline alone; the first inside the band is kept, and after
 * the last the one closest to it, so creation is bounded. The mean comes from the outline's own
 * draw, not from here.
 */
public final class LandShare {
    static final double LOW, HIGH;
    static final int ATTEMPTS, SIDE;

    static {
        JsonObject land = ContinentalOutlineMap.RULES.json.getAsJsonObject("land");
        if (land == null) throw new IllegalStateException("Missing definition value: land");
        JsonArray band = land.getAsJsonArray("band");
        if (band == null || band.size() != 2)
            throw new IllegalStateException("land.band needs two shares");
        LOW = band.get(0).getAsDouble();
        HIGH = band.get(1).getAsDouble();
        if (!(LOW >= 0 && LOW <= HIGH && HIGH <= 1))
            throw new IllegalStateException("Range: land.band = " + band + " outside 0..1");
        ATTEMPTS = (int) Rules.number(land, "attempts", 1, 64);
        SIDE = (int) Rules.number(land, "measureSide", 8, 512);
    }

    /** The outline of the kept attempt. */
    public final ContinentalOutlineMap outline;

    /** The kept attempt's share of the world square, 0..1. */
    public final double share;

    private LandShare(ContinentalOutlineMap outline, double share) {
        this.outline = outline;
        this.share = share;
    }

    /** Whether the kept attempt is inside the band (false only once every attempt missed it). */
    public boolean inBand() {
        return share >= LOW && share <= HIGH;
    }

    /** For example {@code 48.2% (attempt 2 of 8)}; attempts count from 1 here. */
    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "%.1f%% (attempt %d of %d)",
                100 * share,
                outline.attempt + 1,
                ATTEMPTS);
    }

    /** The world's outline: the first attempt inside the band, else the closest. */
    public static LandShare choose(long seed, TerrainHeightReference heights, WorldScale world) {
        String key =
                seed
                        + ":"
                        + world.layout
                        + ":"
                        + ContinentalOutlineMap.FINGERPRINT
                        + ":"
                        + heights.fingerprint;
        double[] kept;
        synchronized (MEMO) {
            kept = MEMO.get(key);
        }
        if (kept != null)
            return new LandShare(
                    new ContinentalOutlineMap(seed, heights, world, (int) kept[0]), kept[1]);
        ContinentalOutlineMap best = null;
        double bestShare = 0, bestMiss = Double.POSITIVE_INFINITY;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            ContinentalOutlineMap outline =
                    new ContinentalOutlineMap(seed, heights, world, attempt);
            double share = measure(outline, world);
            double miss = Math.max(LOW - share, share - HIGH);
            if (miss < bestMiss) {
                best = outline;
                bestShare = share;
                bestMiss = miss;
            }
            if (miss <= 0) break;
        }
        synchronized (MEMO) {
            MEMO.put(key, new double[] {best.attempt, bestShare});
        }
        return new LandShare(best, bestShare);
    }

    /** The share of the world square whose land weight is above zero, at cell centres. */
    public static double measure(ContinentalOutlineMap outline, WorldScale world) {
        double step = world.side() / SIDE, origin = -world.side() / 2 + step / 2;
        ContinentalOutlineMap.Sample sample = new ContinentalOutlineMap.Sample();
        int land = 0;
        for (int i = 0; i < SIDE; i++)
            for (int k = 0; k < SIDE; k++) {
                outline.sample(origin + i * step, origin + k * step, sample);
                if (sample.landWeight() > 0) land++;
            }
        return land / (double) (SIDE * SIDE);
    }

    /**
     * Kept attempts and shares by seed, layout scale and definitions, so tools that build many
     * maps for one world measure it once. Eviction only repeats a pure measurement.
     */
    private static final Map<String, double[]> MEMO =
            new LinkedHashMap<String, double[]>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                    return size() > 32;
                }
            };
}
