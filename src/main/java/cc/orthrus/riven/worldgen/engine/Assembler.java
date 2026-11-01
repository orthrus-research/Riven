package cc.orthrus.riven.worldgen.engine;

/**
 * Assembles one zone of a column: its shape operator writes the shape, the cavity floor's setting
 * lays its layers, the bed source fills the rock and the fills the open space. An assembler is
 * immutable and shared; all mutable state is the caller's {@link AssembledColumn}, so columns are
 * pure functions of their sample, whatever buffer or order they are assembled in.
 */
public final class Assembler<S> {
    /** A zone's shape operator: publishes the column's horizons and writes its shape. */
    public interface Shaper<S> {
        void shape(S sample, Shape shape);
    }

    public final Assembly assembly;
    public final Zone zone;
    private final Zone.Cavity cavity;
    private final int[] frame;
    private final int bottom, top;
    private final Shaper<S> shaper;
    private final Settings.Classifier<S> settings;
    private final BedSource<S> bed;

    /**
     * An assembler for a zone, with the frame's horizons (top, sea, crust floor and world floor)
     * and the zone's operators. The bed source must be the one the zone names.
     */
    public Assembler(
            Assembly assembly,
            String zone,
            int top,
            int sea,
            int crustFloor,
            int worldFloor,
            Shaper<S> shaper,
            Settings.Classifier<S> settings,
            BedSource<S> bed) {
        this.assembly = assembly;
        this.zone = assembly.zones.zone(zone);
        if (!this.zone.bed.equals(bed.id()))
            throw new IllegalStateException(
                    "Zone " + zone + " names bed " + this.zone.bed + ", not " + bed.id());
        cavity = this.zone.cavity(0);
        frame = new int[Horizons.WORLD_FLOOR + 1];
        frame[Horizons.TOP] = top;
        frame[Horizons.SEA] = sea;
        frame[Horizons.CRUST_FLOOR] = crustFloor;
        frame[Horizons.WORLD_FLOOR] = worldFloor;
        // A zone's extent comes from the frame, so every column of it covers the same Y.
        int[] horizons = new int[Horizons.count()];
        java.util.Arrays.fill(horizons, Horizons.ABSENT);
        System.arraycopy(frame, 0, horizons, 0, frame.length);
        this.bottom = this.zone.bottom.resolve(horizons);
        this.top = this.zone.top.resolve(horizons);
        if (this.bottom == Horizons.ABSENT
                || this.top == Horizons.ABSENT
                || this.top <= this.bottom)
            throw new IllegalStateException("Zone " + zone + " needs a fixed extent");
        this.shaper = shaper;
        this.settings = settings;
        this.bed = bed;
    }

    /** Assembles the sample's column of this zone into out, which it returns. */
    public AssembledColumn assemble(S sample, AssembledColumn out) {
        out.begin(zone, cavity, frame, bottom, top);
        shaper.shape(sample, out.shape);
        int floor = out.horizons[cavity.floor];
        if (floor != Horizons.ABSENT) {
            SettingTable table = cavity.settings;
            long holds = settings.holds(sample, floor);
            int row = table.row(holds), under = table.subsoilRow(holds, row);
            out.settings(table.setting(row), table.setting(under));
            // Layers count down from the floor: top, then soil, then the subsoil's row's subsoil.
            int y = floor + 1;
            for (int layer = 0; layer < SettingTable.LAYERS; layer++) {
                int source = layer == SettingTable.SUBSOIL ? under : row;
                int depth = table.depth(source, layer);
                short entry = table.entry(source, layer);
                if (entry != SettingTable.BED)
                    out.shape.layer(y - depth, y, entry, SettingTable.role(layer));
                y -= depth;
            }
        }
        out.resolve(sample, bed, zone.fluid);
        return out;
    }

    /** The zone's lowest Y. */
    public int bottom() {
        return bottom;
    }

    /** One above the zone's highest Y. */
    public int top() {
        return top;
    }

    /**
     * How far below a cavity floor anything but the bed's geology can lie: the deepest setting
     * layers or the bed's surface variety. Below it, only rock varies.
     */
    public int materialDepth() {
        return Math.max(cavity.settings.deepest(), bed.surfaceDepth());
    }

    /** The setting table of the zone's cavity. */
    public SettingTable settings() {
        return cavity.settings;
    }
}
