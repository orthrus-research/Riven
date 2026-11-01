package cc.orthrus.riven.worldgen.engine;

import cc.orthrus.riven.worldgen.TerrainBlock;

/**
 * The interval builder a zone's shape operator writes one column with. Operations run in a fixed
 * order (raise, lay, build, carve, pocket, then the temporary cave carve), each writing half-open
 * [bottom, top) intervals by splitting runs, so cost follows the column's transitions. What each
 * pass may write over is the engine's rule ({@link Pass}), not the operator's: a write simply
 * leaves alone what its pass may not touch.
 */
public final class Shape {
    /** The passes that write a column, and what each may write over. */
    public enum Pass {
        /** The zone's ground up to its cavity floor, open space above. */
        RAISE(passes(0), Role.BED, Role.AIR, Role.FLUID),
        /** A deposit over the ground, in open space or over an earlier deposit. */
        LAY(passes(0, 1), Role.DEPOSIT),
        /** Solid inside a cavity: over everything. */
        BUILD(passes(0, 1, 2), Role.BED),
        /** Open space cut into anything but built solid. */
        CARVE(passes(0, 1, 3), Role.AIR, Role.FLUID),
        /** A sealed pocket with its own level, inside raised and laid ground. */
        POCKET(passes(0, 1), Role.AIR, Role.FLUID),
        /**
         * Today's caves (CaveMap voids, their fill, linings and decorations), cut into raised,
         * laid and pocketed space; B2 replaces this with carve families and dressing.
         */
        CAVES(passes(0, 1, 4, 5), Role.AIR, Role.FLUID, Role.LINING, Role.DECOR),
        /** A cavity floor's setting layers, over raised rock only. */
        MATERIALS(passes(0), Role.TOP, Role.SOIL, Role.SUBSOIL);

        /** The passes whose runs this pass may write over. */
        final int over;

        private final int roles;

        Pass(int over, Role... roles) {
            this.over = over;
            int r = 0;
            for (Role role : roles) r |= role.bit();
            this.roles = r;
        }

        private static int passes(int... ordinals) {
            int mask = 0;
            for (int o : ordinals) mask |= 1 << o;
            return mask;
        }

        /** Whether this pass may write a run with this role. */
        public boolean writes(Role role) {
            return (roles & role.bit()) != 0;
        }

        private static final Pass[] ALL = values();

        public static Pass of(int code) {
            return ALL[code];
        }
    }

    /** Lay may cover only open space and earlier deposits; every other pass, any role. */
    private static final int ANY = -1,
            OPEN = Role.AIR.bit() | Role.FLUID.bit() | Role.DEPOSIT.bit(),
            RAISED_BED = Role.BED.bit();

    private final AssembledColumn column;

    Shape(AssembledColumn column) {
        this.column = column;
    }

    /** Publishes a horizon of this column. */
    public void publish(int horizon, int y) {
        column.horizons[horizon] = y;
    }

    /** A horizon published so far, or {@link Horizons#ABSENT}. */
    public int horizon(int horizon) {
        return column.horizons[horizon];
    }

    /** The zone's lowest Y. */
    public int bottom() {
        return column.zoneBottom;
    }

    /** One above the zone's highest Y. */
    public int top() {
        return column.zoneTop;
    }

    /**
     * Raises the ground below top: bed rock whose depth counts from the cavity floor, with the
     * cavity's open space above it.
     */
    public void raise(int top) {
        int floor = column.horizons[column.cavity.floor];
        column.write(
                column.zoneBottom,
                top,
                AssembledColumn.BED_ENTRY,
                Role.BED,
                Pass.RAISE,
                floor,
                ANY);
    }

    /** Lays a deposit of an entry over [bottom, top). */
    public void lay(int bottom, int top, TerrainBlock entry) {
        column.write(bottom, top, (short) entry.index(), Role.DEPOSIT, Pass.LAY, 0, OPEN);
    }

    /** Lays a deposit of the bed's own rock, its depth counted from depthTop. */
    public void layBed(int bottom, int top, int depthTop) {
        column.write(
                bottom, top, AssembledColumn.BED_ENTRY, Role.DEPOSIT, Pass.LAY, depthTop, OPEN);
    }

    /** Builds solid bed rock over [bottom, top), its depth counted from depthTop. */
    public void build(int bottom, int top, int depthTop) {
        column.write(bottom, top, AssembledColumn.BED_ENTRY, Role.BED, Pass.BUILD, depthTop, ANY);
    }

    /** Carves [bottom, top) open; the cavity's fluid fills it to its level. */
    public void carve(int bottom, int top) {
        column.write(
                bottom,
                top,
                AssembledColumn.CAVITY_FILL,
                Role.AIR,
                Pass.CARVE,
                column.cavity.level,
                ANY);
    }

    /** Opens a sealed pocket over [bottom, top); water fills it to its own level. */
    public void pocket(int bottom, int top) {
        column.write(
                bottom,
                top,
                AssembledColumn.POCKET_FILL,
                Role.AIR,
                Pass.POCKET,
                Horizons.POCKET_LEVEL,
                ANY);
    }

    /** The temporary cave carve: open space over [bottom, top) holding its fill (air or fluid). */
    public void caveVoid(int bottom, int top, TerrainBlock fill) {
        column.write(
                bottom,
                top,
                (short) fill.index(),
                fill == TerrainBlock.AIR ? Role.AIR : Role.FLUID,
                Pass.CAVES,
                0,
                ANY);
    }

    /** The temporary cave carve: a lining beside a void. */
    public void caveLining(int bottom, int top, TerrainBlock block) {
        column.write(bottom, top, (short) block.index(), Role.LINING, Pass.CAVES, 0, ANY);
    }

    /** The temporary cave carve: a decoration, over voids and linings too. */
    public void caveDecor(int bottom, int top, TerrainBlock block) {
        column.write(bottom, top, (short) block.index(), Role.DECOR, Pass.CAVES, 0, ANY);
    }

    /** A setting layer of the cavity floor: over raised bed rock only. */
    void layer(int bottom, int top, short entry, Role role) {
        column.write(bottom, top, entry, role, Pass.MATERIALS, 0, RAISED_BED);
    }
}
