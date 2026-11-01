package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.rocky.RockyCoasts.Edit;
import cc.orthrus.riven.worldgen.engine.Assembler;
import cc.orthrus.riven.worldgen.engine.Assembly;
import cc.orthrus.riven.worldgen.engine.Horizons;
import cc.orthrus.riven.worldgen.engine.Shape;

/**
 * The crust's shape operator: maps a terrain sample onto the engine's shape operations. The
 * ground is raised to the sky's floor; sediment and a rocky shore's build-up are laid over it; a
 * rocky volume's solid is built and its tunnel carved (the volume is an overhang over the ground
 * as it was before it, which is the sky's floor); a wonder's void is carved; an aquifer is a
 * pocket; and today's caves (voids with their fill, linings and decorations) arrive through the
 * temporary carve until carve families replace them (B2).
 */
final class CrustShape implements Assembler.Shaper<TerrainMap.Sample> {
    /** The crust's assembler over packaged definitions, with these surface rules and heights. */
    static Assembler<TerrainMap.Sample> assembler(
            SurfaceRules rules, TerrainHeightReference heights) {
        Assembly assembly = Assembly.packaged();
        return new Assembler<>(
                assembly,
                "crust",
                heights.maximumYExclusive,
                heights.seaLevel,
                heights.minimumY,
                heights.worldMinimumY,
                new CrustShape(),
                new CrustSettings(assembly.thresholds("crust"), rules, heights),
                new CrustStrata(heights));
    }

    @Override
    public void shape(TerrainMap.Sample s, Shape shape) {
        int surface = s.blockY();
        boolean volume = s.rocky.hasVolume(),
                sediment = s.sediment.weight > 0,
                rock = s.rocky.weight > 0;
        int ground = volume ? (int) Math.floor(s.rocky.parentHeight) : surface;
        shape.publish(Horizons.GROUND, ground);
        int beforeDeposit = Horizons.ABSENT, beforeRocky = Horizons.ABSENT;
        if (sediment)
            shape.publish(
                    Horizons.GROUND_BEFORE_DEPOSIT, beforeDeposit = floor(s.sediment.parentHeight));
        if (rock)
            shape.publish(Horizons.GROUND_BEFORE_ROCKY, beforeRocky = floor(s.rocky.parentHeight));
        shape.publish(Horizons.WATER, s.waterY());
        Hydro.Sample w = s.water;
        boolean aquifer = w.aquiferMin <= w.aquiferMax;
        if (aquifer) {
            shape.publish(Horizons.POCKET_FLOOR, w.aquiferMin);
            shape.publish(Horizons.POCKET_ROOF, w.aquiferMax + 1);
            shape.publish(Horizons.POCKET_LEVEL, w.aquiferLevel);
        }
        // Raise to the ground buried under what is laid over it.
        int raised = ground;
        if (sediment) raised = Math.min(raised, beforeDeposit);
        if (rock && !volume) raised = Math.min(raised, beforeRocky);
        shape.raise(raised + 1);
        // A rocky shore without a volume builds its land up in its own rock; sediment lies on top.
        if (rock && !volume) shape.layBed(beforeRocky + 1, ground + 1, surface);
        if (sediment) shape.lay(beforeDeposit + 1, ground + 1, TerrainBlock.SAND);
        if (volume) volume(s, shape, surface);
        if (s.wonders.hasVoid()) shape.carve(s.wonders.voidBottom, s.wonders.voidTop);
        if (aquifer) shape.pocket(w.aquiferMin, w.aquiferMax + 1);
        caves(s, shape);
    }

    private static int floor(double y) {
        return (int) Math.floor(y);
    }

    /**
     * A rocky volume's edits, constant between its cut boundaries: solid is built (its depth from
     * the column's own top) and the arch's tunnel carved.
     */
    private static void volume(TerrainMap.Sample s, Shape shape, int surface) {
        RockyTerrain.Result r = s.rocky;
        int cuts = r.cutCount;
        if (cuts == 0) return;
        int[] at = new int[cuts];
        for (int i = 0; i < cuts; i++) at[i] = r.boundary(i, surface + 1);
        java.util.Arrays.sort(at);
        for (int i = 0; i + 1 < cuts; i++) {
            if (at[i + 1] <= at[i]) continue;
            Edit edit = RockyTerrain.edit(r, at[i]);
            if (edit == Edit.SOLID) shape.build(at[i], at[i + 1], surface);
            else if (edit == Edit.VOID) shape.carve(at[i], at[i + 1]);
        }
    }

    /**
     * The temporary carve: today's cave voids hold their own fill, split where it changes;
     * linings line them and decorations stand over both, an earlier decoration over a later one.
     */
    private static void caves(TerrainMap.Sample s, Shape shape) {
        CaveMap.Voids v = s.caves;
        for (int i = 0; i < v.linings; i++)
            shape.caveLining(v.liningLo[i], v.liningHi[i], v.liningBlock[i]);
        for (int i = 0; i < v.count; i++) {
            int y = v.lo[i], hi = v.hi[i];
            while (y < hi) {
                int next = fillChange(s, v, y, hi);
                shape.caveVoid(y, next, v.fill(y, s));
                y = next;
            }
        }
        for (int i = v.decorations - 1; i >= 0; i--)
            shape.caveDecor(v.decorLo[i], v.decorHi[i], v.decorBlock[i]);
    }

    /** The lowest Y in (y, hi) where a void's fill can change, or hi. */
    private static int fillChange(TerrainMap.Sample s, CaveMap.Voids v, int y, int hi) {
        int next = hi;
        next = below(next, y, (int) Math.floor(s.geology.lavaY) + 1);
        if (s.flood.flooded) {
            next = below(next, y, s.flood.level + 1);
            next = below(next, y, s.flood.floor + 1);
        }
        Hydro.Sample w = s.water;
        if (w.aquiferMin <= w.aquiferMax) {
            next = below(next, y, w.aquiferMin);
            next = below(next, y, w.aquiferMax + 1);
            next = below(next, y, w.aquiferLevel + 1);
        }
        for (int i = 0; i < v.pools; i++) {
            next = below(next, y, v.poolLo[i]);
            next = below(next, y, v.poolHi[i]);
        }
        return next;
    }

    private static int below(int next, int y, int candidate) {
        return candidate > y && candidate < next ? candidate : next;
    }
}
