package cc.orthrus.riven.worldgen.engine;

import cc.orthrus.riven.worldgen.TerrainBlock;

import java.util.Arrays;

/**
 * One zone of one column, assembled: bottom-up runs that cover the zone without gaps, each with
 * its catalogue entry, role, zone and the pass that wrote it. Run i covers [bottom(i), top(i)),
 * and adjacent runs differ in entry, role or zone. An instance is a reusable buffer for one
 * thread: {@link Assembler#assemble} overwrites it.
 */
public final class AssembledColumn {
    private static final TerrainBlock[] ENTRIES = TerrainBlock.values();

    /** Placeholder entries while shaping: the bed's rock, a cavity's fill, a pocket's fill. */
    static final short BED_ENTRY = -1, CAVITY_FILL = -2, POCKET_FILL = -3;

    private int count;
    private int[] bottoms = new int[65];
    private short[] entries = new short[64];
    private byte[] roles = new byte[64], zones = new byte[64], passes = new byte[64];

    // The shaping runs, resolved into the runs above. A bed run's ref is its depth top; a fill
    // run's is the horizon its level reads.
    private int shaped;
    private int[] shapedBottoms = new int[65], refs = new int[64];
    private short[] shapedEntries = new short[64];
    private byte[] shapedRoles = new byte[64], shapedPasses = new byte[64];

    final int[] horizons = new int[Horizons.count()];
    final Shape shape = new Shape(this);
    Zone.Cavity cavity;
    int zoneBottom, zoneTop;
    private byte zone;
    private int setting = -1, subsoilSetting = -1;

    // The bed's boundaries for up to two depth tops, sorted: [offset, offset + count).
    private int[] bounds = new int[0];
    private final int[] boundDepthTop = new int[2], boundCount = new int[2], boundOffset = {0, 0};
    private int boundSlots;

    /** The number of runs. */
    public int count() {
        return count;
    }

    public int bottom(int run) {
        return bottoms[run];
    }

    public int top(int run) {
        return bottoms[run + 1];
    }

    /** The zone's lowest Y. */
    public int bottomY() {
        return bottoms[0];
    }

    /** One above the zone's highest Y. */
    public int topY() {
        return bottoms[count];
    }

    /** A run's catalogue entry index ({@link TerrainBlock#index}). */
    public short runEntry(int run) {
        return entries[run];
    }

    public TerrainBlock runBlock(int run) {
        return ENTRIES[entries[run]];
    }

    public Role runRole(int run) {
        return Role.of(roles[run]);
    }

    /** A run's zone index ({@link Zones#zone(int)}). */
    public int runZone(int run) {
        return zones[run];
    }

    /** The pass that wrote a run (for checks: every run's role is one its pass writes). */
    public Shape.Pass runPass(int run) {
        return Shape.Pass.of(passes[run]);
    }

    /** The run holding Y, or -1 outside the zone. */
    public int run(int y) {
        if (y < bottoms[0] || y >= bottoms[count]) return -1;
        int lo = 0, hi = count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (bottoms[mid] <= y) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** The block at Y: air outside the zone. */
    public TerrainBlock at(int y) {
        int run = run(y);
        return run < 0 ? TerrainBlock.AIR : ENTRIES[entries[run]];
    }

    /** The role at Y: air outside the zone. */
    public Role role(int y) {
        int run = run(y);
        return run < 0 ? Role.AIR : Role.of(roles[run]);
    }

    /** A horizon of this column, or {@link Horizons#ABSENT}. */
    public int horizon(int horizon) {
        return horizons[horizon];
    }

    /** The setting the cavity floor took ({@link Settings}), or -1 without a floor. */
    public int setting() {
        return setting;
    }

    /** The setting whose subsoil the floor took, or -1 without a floor. */
    public int subsoilSetting() {
        return subsoilSetting;
    }

    /** Starts a column of a zone: horizons absent but the frame's, one open run. */
    void begin(Zone zone, Zone.Cavity cavity, int[] frame, int bottom, int top) {
        Arrays.fill(horizons, Horizons.ABSENT);
        System.arraycopy(frame, 0, horizons, 0, frame.length);
        this.zone = (byte) zone.index;
        this.cavity = cavity;
        zoneBottom = bottom;
        zoneTop = top;
        setting = subsoilSetting = -1;
        shaped = 1;
        shapedBottoms[0] = bottom;
        shapedBottoms[1] = top;
        shapedEntries[0] = CAVITY_FILL;
        refs[0] = cavity.level;
        shapedRoles[0] = Role.AIR.code();
        shapedPasses[0] = (byte) Shape.Pass.RAISE.ordinal();
    }

    void settings(int setting, int subsoilSetting) {
        this.setting = setting;
        this.subsoilSetting = subsoilSetting;
    }

    /**
     * Writes [bottom, top) of the shaping runs, clipped to the zone: every part whose pass and
     * role the writing pass may write over takes the entry, role and ref.
     */
    void write(int bottom, int top, short entry, Role role, Shape.Pass pass, int ref, int over) {
        bottom = Math.max(bottom, zoneBottom);
        top = Math.min(top, zoneTop);
        if (top <= bottom) return;
        int first = split(bottom), end = split(top);
        byte r = role.code(), p = (byte) pass.ordinal();
        for (int i = first; i < end; i++) {
            if ((pass.over & (1 << shapedPasses[i])) == 0) continue;
            if ((over & (1 << shapedRoles[i])) == 0) continue;
            shapedEntries[i] = entry;
            shapedRoles[i] = r;
            shapedPasses[i] = p;
            refs[i] = ref;
        }
    }

    /** The shaping run that starts at y, splitting the one holding it; the count at the top. */
    private int split(int y) {
        if (y >= zoneTop) return shaped;
        int i = 0;
        while (shapedBottoms[i + 1] <= y) i++;
        if (shapedBottoms[i] == y) return i;
        if (shaped + 1 >= shapedEntries.length) growShaped();
        int move = shaped - i;
        System.arraycopy(shapedBottoms, i + 1, shapedBottoms, i + 2, move);
        System.arraycopy(shapedEntries, i, shapedEntries, i + 1, move);
        System.arraycopy(shapedRoles, i, shapedRoles, i + 1, move);
        System.arraycopy(shapedPasses, i, shapedPasses, i + 1, move);
        System.arraycopy(refs, i, refs, i + 1, move);
        shapedBottoms[i + 1] = y;
        shaped++;
        return i + 1;
    }

    private void growShaped() {
        int n = shapedEntries.length * 2;
        shapedBottoms = Arrays.copyOf(shapedBottoms, n + 1);
        shapedEntries = Arrays.copyOf(shapedEntries, n);
        shapedRoles = Arrays.copyOf(shapedRoles, n);
        shapedPasses = Arrays.copyOf(shapedPasses, n);
        refs = Arrays.copyOf(refs, n);
    }

    /**
     * Resolves the shaping runs into the column's runs: bed runs take their rock from the bed
     * source, fill runs their fluid up to their level and air above, and equal neighbours merge.
     */
    <S> void resolve(S sample, BedSource<S> bed, short fluid) {
        count = 0;
        boundSlots = 0;
        for (int i = 0; i < shaped; i++) {
            int bottom = shapedBottoms[i], top = shapedBottoms[i + 1];
            byte role = shapedRoles[i], pass = shapedPasses[i];
            short entry = shapedEntries[i];
            if (entry == BED_ENTRY) {
                int slot = bounds(sample, bed, refs[i], i);
                int depthTop = refs[i], from = boundOffset[slot];
                int to = from + boundCount[slot];
                // The first boundary above this run's bottom.
                int lo = from, hi = to;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (bounds[mid] <= bottom) lo = mid + 1;
                    else hi = mid;
                }
                int start = bottom;
                short rock = (short) bed.block(sample, bottom, depthTop).index();
                for (int b = lo; b < to && bounds[b] < top; b++) {
                    int y = bounds[b];
                    if (y <= start) continue;
                    short next = (short) bed.block(sample, y, depthTop).index();
                    if (next == rock) continue;
                    emit(start, rock, role, pass);
                    start = y;
                    rock = next;
                }
                emit(start, rock, role, pass);
            } else if (entry == CAVITY_FILL || entry == POCKET_FILL) {
                int level = horizons[refs[i]];
                byte open = Role.AIR.code(), wet = Role.FLUID.code();
                short air = (short) TerrainBlock.AIR.index();
                if (level == Horizons.ABSENT || level < bottom) emit(bottom, air, open, pass);
                else if (level >= top - 1) emit(bottom, fluid, wet, pass);
                else {
                    emit(bottom, fluid, wet, pass);
                    emit(level + 1, air, open, pass);
                }
            } else emit(bottom, entry, role, pass);
        }
        bottoms[count] = zoneTop;
    }

    /** Appends a run from bottom upward, merged into the last one where it is the same. */
    private void emit(int bottom, short entry, byte role, byte pass) {
        if (count > 0 && entries[count - 1] == entry && roles[count - 1] == role) return;
        if (count + 1 >= entries.length) {
            int n = entries.length * 2;
            bottoms = Arrays.copyOf(bottoms, n + 1);
            entries = Arrays.copyOf(entries, n);
            roles = Arrays.copyOf(roles, n);
            zones = Arrays.copyOf(zones, n);
            passes = Arrays.copyOf(passes, n);
        }
        bottoms[count] = bottom;
        entries[count] = entry;
        roles[count] = role;
        zones[count] = zone;
        passes[count++] = pass;
    }

    /**
     * The slot holding the bed's sorted boundaries for a depth top, computed once per column up
     * to the highest bed run that counts from it.
     */
    private <S> int bounds(S sample, BedSource<S> bed, int depthTop, int from) {
        for (int s = 0; s < boundSlots; s++) if (boundDepthTop[s] == depthTop) return s;
        int top = zoneBottom;
        for (int i = from; i < shaped; i++)
            if (shapedEntries[i] == BED_ENTRY && refs[i] == depthTop)
                top = Math.max(top, shapedBottoms[i + 1]);
        int slot = boundSlots < 2 ? boundSlots++ : 1;
        int offset = slot == 0 ? 0 : boundOffset[0] + boundCount[0];
        int need = offset + bed.maximumBoundaries();
        if (bounds.length < need) bounds = Arrays.copyOf(bounds, need);
        int n = bed.boundaries(sample, zoneBottom, top, depthTop, bounds, offset);
        Arrays.sort(bounds, offset, n);
        boundDepthTop[slot] = depthTop;
        boundOffset[slot] = offset;
        boundCount[slot] = n - offset;
        return slot;
    }
}
