package cc.orthrus.riven.worldgen;

/**
 * A set of rock units as one bitmask over their definition indices: membership without hashing,
 * on the cave and ore paths every column runs. Filled while its owner is built, then only read.
 */
public final class UnitSet {
    private long bits;

    public void add(GeologyMap.Unit unit) {
        bits |= 1L << unit.index;
    }

    public boolean contains(GeologyMap.Unit unit) {
        return unit != null && (bits >>> unit.index & 1) != 0;
    }
}
