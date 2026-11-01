package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.rocky.RockyCoasts.Edit;
import cc.orthrus.riven.worldgen.engine.Assembler;
import cc.orthrus.riven.worldgen.engine.Assembly;
import cc.orthrus.riven.worldgen.engine.Flags;
import cc.orthrus.riven.worldgen.engine.Horizons;
import cc.orthrus.riven.worldgen.engine.Shape;

/**
 * The crust's shape operator: maps a terrain sample onto the engine's shape operations. The
 * ground is raised to the sky's floor; sediment and a rocky shore's build-up are laid over it; a
 * rocky volume's solid is built and its tunnel carved (the volume is an overhang over the ground
 * as it was before it, which is the sky's floor); a wonder's void is carved; an aquifer is a
 * pocket; and the column's caves are carved, entrances apart, under the sky's roof band, their
 * fill left to the engine's precedence. Until B2's switch the native world still takes its caves
 * through the temporary carve (voids with their own fill), and checks compare the two.
 */
final class CrustShape implements Assembler.Shaper<TerrainMap.Sample> {
    /** Where the crust's horizons go: a shape, or an array for the cave walk. */
    interface Sink {
        void publish(int horizon, int y);
    }

    private final boolean engineCaves;

    private CrustShape(boolean engineCaves) {
        this.engineCaves = engineCaves;
    }

    /** The crust's assembler over packaged definitions, with these surface rules and heights. */
    static Assembler<TerrainMap.Sample> assembler(
            SurfaceRules rules, TerrainHeightReference heights) {
        return assembler(rules, heights, false);
    }

    /** As {@link #assembler(SurfaceRules, TerrainHeightReference)}, carving caves as chosen. */
    static Assembler<TerrainMap.Sample> assembler(
            SurfaceRules rules, TerrainHeightReference heights, boolean engineCaves) {
        Assembly assembly = Assembly.packaged();
        return new Assembler<>(
                assembly,
                "crust",
                heights.maximumYExclusive,
                heights.seaLevel,
                heights.minimumY,
                heights.worldMinimumY,
                new CrustShape(engineCaves),
                new CrustSettings(assembly.thresholds("crust"), rules, heights),
                new CrustStrata(heights));
    }

    /**
     * The crust's horizons of a sample, the one description the shape and the cave walk read: the
     * sky's floor and the ground under deposits and rocky shaping, the water, an aquifer's
     * pocket, geology's deep surface, lava level and unconformity, and a flood cell's band.
     */
    static void horizons(TerrainMap.Sample s, Sink out) {
        int surface = s.blockY();
        boolean volume = s.rocky.hasVolume();
        out.publish(Horizons.GROUND, volume ? (int) Math.floor(s.rocky.parentHeight) : surface);
        if (s.sediment.weight > 0)
            out.publish(Horizons.GROUND_BEFORE_DEPOSIT, floor(s.sediment.parentHeight));
        if (s.rocky.weight > 0)
            out.publish(Horizons.GROUND_BEFORE_ROCKY, floor(s.rocky.parentHeight));
        out.publish(Horizons.WATER, s.waterY());
        Hydro.Sample w = s.water;
        if (w.aquiferMin <= w.aquiferMax) {
            out.publish(Horizons.POCKET_FLOOR, w.aquiferMin);
            out.publish(Horizons.POCKET_ROOF, w.aquiferMax + 1);
            out.publish(Horizons.POCKET_LEVEL, w.aquiferLevel);
        }
        GeologyMap.Column g = s.geology;
        // The root lies at and below the deep surface; lava fills to its level.
        if (!Double.isInfinite(g.deepY)) out.publish(Horizons.DEEP, floor(g.deepY) + 1);
        if (!Double.isInfinite(g.lavaY)) out.publish(Horizons.LAVA, floor(g.lavaY));
        out.publish(Horizons.UNCONFORMITY, floor(g.unconformity) + 1);
        if (s.flood.flooded) {
            out.publish(Horizons.FLOOD_FLOOR, s.flood.floor);
            out.publish(Horizons.FLOOD_LEVEL, s.flood.level);
        }
    }

    @Override
    public void shape(TerrainMap.Sample s, Shape shape) {
        horizons(s, shape::publish);
        if (CaveMap.wet(s)) shape.flag(Flags.WET);
        int surface = s.blockY();
        boolean volume = s.rocky.hasVolume(),
                sediment = s.sediment.weight > 0,
                rock = s.rocky.weight > 0;
        int ground = shape.horizon(Horizons.GROUND),
                beforeDeposit = shape.horizon(Horizons.GROUND_BEFORE_DEPOSIT),
                beforeRocky = shape.horizon(Horizons.GROUND_BEFORE_ROCKY);
        Hydro.Sample w = s.water;
        boolean aquifer = w.aquiferMin <= w.aquiferMax;
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
        if (engineCaves) engineCaves(s, shape);
        else caves(s, shape);
    }

    /**
     * The column's caves through the engine: linings, then each void under the roof band and each
     * entrance whole, their fill left to the engine (a void's water freezes where its lowest
     * block's depth biome is frozen, a pool's where its own is), then decorations over both, an
     * earlier decoration over a later one.
     */
    private static void engineCaves(TerrainMap.Sample s, Shape shape) {
        CaveMap.Voids v = s.caves;
        for (int i = 0; i < v.linings; i++)
            shape.caveLining(v.liningLo[i], v.liningHi[i], v.liningBlock[i]);
        FloodMap.Column f = s.flood;
        for (int i = 0; i < v.count; i++) {
            // Only flood water reads a void's frost, so only a void reaching the band asks.
            boolean frozen =
                    f.flooded
                            && v.lo[i] <= f.level
                            && v.hi[i] > f.floor + 1
                            && RealmMap.cell(v.lo[i], s).frozen;
            shape.cave(v.lo[i], v.hi[i], frozen);
            for (int e = 0; e < v.entrances; e++)
                if (v.entranceLo[e] >= v.lo[i] && v.entranceLo[e] < v.hi[i])
                    shape.entrance(v.entranceLo[e], v.entranceHi[e], frozen);
        }
        for (int i = 0; i < v.pools; i++)
            shape.pool(v.poolLo[i], v.poolHi[i], RealmMap.cell(v.poolLo[i], s).frozen);
        for (int i = v.decorations - 1; i >= 0; i--)
            shape.caveDecor(v.decorLo[i], v.decorHi[i], v.decorBlock[i]);
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
