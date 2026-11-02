package cc.orthrus.riven.worldgen.engine;

/**
 * The fixed precedence that fills carved cave space (D15), the one description assembly and the
 * crust's cave dressing both read: lava up to the lava horizon, then a pocket's band (water to its
 * level, air above), then a cave pool, then a flood cell's band, then air. Callers give the
 * column's horizons; whether water freezes is theirs to say.
 */
public final class CaveFills {
    /** What fills a Y: air, lava, a pocket's water, a flood cell's water, or pool k's water. */
    public static final int AIR = 0, LAVA = 1, POCKET = 2, FLOOD = 3, POOL = 4;

    private CaveFills() {}

    /**
     * The fill of carved space at Y: {@link #POOL} + k for pool k. Absent horizons
     * ({@link Horizons#ABSENT}) mean the column has no lava, pocket or flood band.
     */
    public static int fill(
            int y,
            int lava,
            int pocketFloor,
            int pocketRoof,
            int pocketLevel,
            int[] poolLo,
            int[] poolHi,
            int pools,
            int floodFloor,
            int floodLevel) {
        if (lava != Horizons.ABSENT && y <= lava) return LAVA;
        if (pocketFloor != Horizons.ABSENT && y >= pocketFloor && y < pocketRoof)
            return y <= pocketLevel ? POCKET : AIR;
        for (int k = 0; k < pools; k++) if (y >= poolLo[k] && y < poolHi[k]) return POOL + k;
        if (floodFloor != Horizons.ABSENT && y > floodFloor && y <= floodLevel) return FLOOD;
        return AIR;
    }
}
