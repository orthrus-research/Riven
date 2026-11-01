package cc.orthrus.riven.worldgen.engine;

/**
 * What a block of an assembled column is doing. The vocabulary is the engine's: rules read a
 * block's role and zone together (an ore modifier masks bed, a setting table writes top, soil and
 * subsoil), so data never re-derives why a block is there.
 */
public enum Role {
    /** The material at a cavity's floor. */
    TOP,
    /** Under the top, counting down from the floor. */
    SOIL,
    /** Under the soil (the sandstone under sand). */
    SUBSOIL,
    /** Rock from the zone's bed source. */
    BED,
    /** A laid body's material (beach sand, a built-up rocky shore). */
    DEPOSIT,
    /** The solid face over a cavity. */
    ROOF,
    /** Rock dressed beside carved space (a geode's shell). */
    LINING,
    /** Rock dressed under carved space. */
    FLOOR,
    AIR,
    /** Water, lava or a frozen pool. */
    FLUID,
    /** A decoration against an open face. */
    DECOR;

    private static final Role[] ALL = values();

    /** The role a run's stored code names. */
    public static Role of(int code) {
        return ALL[code];
    }

    public byte code() {
        return (byte) ordinal();
    }

    /** Open space: air or fluid. Decorations stand in open space but are blocks. */
    public boolean open() {
        return this == AIR || this == FLUID;
    }

    /** The roles a cavity floor's setting writes. */
    public boolean soil() {
        return this == TOP || this == SOIL || this == SUBSOIL;
    }

    /** This role's bit, for role masks. */
    public int bit() {
        return 1 << ordinal();
    }
}
