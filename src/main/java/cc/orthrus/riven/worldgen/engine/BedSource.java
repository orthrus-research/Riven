package cc.orthrus.riven.worldgen.engine;

import cc.orthrus.riven.worldgen.TerrainBlock;

/**
 * Where a zone's rock comes from: fills every {@code bed} run, and laid bodies whose material is
 * the bed's. Rock can vary with depth below a depth top (the column's ground, or for built rock
 * the column's own top), so every call names it.
 */
public interface BedSource<S> {
    /** The id a zone's {@code bed} names. */
    String id();

    /**
     * Writes every Y in (bottom, top) where {@link #block} can change from Y - 1 (a superset is
     * fine) into out from index n, and returns the new count; out holds {@link
     * #maximumBoundaries} free entries.
     */
    int boundaries(S sample, int bottom, int top, int depthTop, int[] out, int n);

    /** The rock at Y. */
    TerrainBlock block(S sample, int y, int depthTop);

    /** Upper bound on the boundaries one call writes. */
    int maximumBoundaries();

    /**
     * How far below its depth top the rock's surface variety reaches; below it, and below every
     * setting's layers, a column's rock varies only with its geology.
     */
    int surfaceDepth();
}
