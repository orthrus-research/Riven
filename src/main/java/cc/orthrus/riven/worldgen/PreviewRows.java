package cc.orthrus.riven.worldgen;

/**
 * One preview thread's samples of the cells it resolves next. A preview tile is 64 x 64 cells
 * walked row by row; a cell's materials read its 8 neighbouring cells, and those ahead of it (the
 * next cell, and three in the next row) are not sampled yet. Sampled here in full and kept, each
 * serves as its neighbours' surface now and as its own column when the walk reaches it, so no
 * cell is sampled twice. The 66 slots hold a row and two cells: a cell is kept from the first
 * time a neighbour asks for it until its own turn, and no slot is reused in between.
 *
 * <p>A kept sample equals a fresh one, so what is kept, missed or evicted changes speed, never a
 * result: a neighbour behind the walk, outside the cell's 64-cell tile or not one cell away (the
 * 16-block hollow lattice) is read by height alone, as before.
 */
public final class PreviewRows implements GroundForm.Neighbours {
    private static final int TILE = 64, SLOTS = TILE + 2;

    private final TerrainMap terrain;
    private final GroundForm form;
    private final TerrainMap.Sample[] samples = new TerrainMap.Sample[SLOTS];
    private final long[] keys = new long[SLOTS];
    private final boolean[] held = new boolean[SLOTS];

    // The cell being resolved: its width, its cell coordinates and slot; -1 slot when none.
    private int step, centreX, centreZ, cellX, cellZ, slot = -1;
    private int spacingWas;
    private GroundForm.Neighbours neighboursWas;

    // Cells served from a kept sample (cost diagnostics).
    private long kept;

    public PreviewRows(TerrainMap terrain, GroundForm form) {
        this.terrain = terrain;
        this.form = form;
    }

    /**
     * The sample of a cell of width {@code step} centred at (x, z), kept or sampled now, with this
     * thread's ground form reading the cell's neighbours at its width until {@link #end}. The
     * sample is this object's until then.
     */
    public TerrainMap.Sample begin(int x, int z, int step) {
        if (slot >= 0)
            throw new IllegalStateException("cell " + cellX + "," + cellZ + " not ended");
        int at = slot(Math.floorDiv(x, step), Math.floorDiv(z, step));
        long key = key(x, z);
        TerrainMap.Sample s = samples[at];
        if (s == null) s = samples[at] = new TerrainMap.Sample();
        if (held[at] && keys[at] == key) kept++;
        else {
            held[at] = false;
            terrain.sample(x, z, s);
            keys[at] = key;
            held[at] = true;
        }
        this.step = step;
        centreX = x;
        centreZ = z;
        cellX = Math.floorDiv(x, step);
        cellZ = Math.floorDiv(z, step);
        slot = at;
        spacingWas = form.spacing(step);
        neighboursWas = form.neighbours(this);
        return s;
    }

    /** Ends the cell {@link #begin} returned, restoring the ground form's spacing and neighbours. */
    public void end() {
        if (slot < 0) return;
        form.neighbours(neighboursWas);
        form.spacing(spacingWas);
        neighboursWas = null;
        slot = -1;
    }

    /** Cells served from a kept sample on this object so far (cost diagnostics). */
    public long kept() {
        return kept;
    }

    @Override
    public TerrainMap.Sample neighbour(int x, int z) {
        if (slot < 0) return null;
        int dx = x - centreX, dz = z - centreZ;
        boolean ahead = dz == step ? Math.abs(dx) <= step && dx % step == 0 : dz == 0 && dx == step;
        if (!ahead) return null;
        int cx = cellX + dx / step, cz = cellZ + dz / step;
        // Past the tile's edge the walk never reaches the cell; its height alone serves.
        if (Math.floorDiv(cx, TILE) != Math.floorDiv(cellX, TILE)
                || Math.floorDiv(cz, TILE) != Math.floorDiv(cellZ, TILE)) return null;
        int at = slot(cx, cz);
        TerrainMap.Sample s = samples[at];
        if (s == null) s = samples[at] = new TerrainMap.Sample();
        long key = key(x, z);
        if (held[at] && keys[at] == key) return s;
        held[at] = false;
        terrain.sample(x, z, s);
        keys[at] = key;
        held[at] = true;
        return s;
    }

    private static int slot(int cx, int cz) {
        return (Math.floorMod(cz, TILE) * TILE + Math.floorMod(cx, TILE)) % SLOTS;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
