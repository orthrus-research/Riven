package cc.orthrus.riven.worldgen;

import java.util.List;

/**
 * A marsh's tidal creek: a network of channels grown once from one mouth on its segment's shore,
 * the first a polyline from the sea inland and each branch a polyline from a node of the channel
 * it leaves. Every node carries the channel's half width and depth there, both narrowing toward
 * its tip. A creek's bed lies below the water's top, so the sea's water stands in it; its banks
 * are the flat it was grown through, which stands above that water.
 */
final class TidalCreek {
    final long id;

    /** Every channel's nodes in order; channel c holds nodes [start[c], start[c + 1]). */
    final double[] x, z, halfWidth, depth;

    final int[] start;
    final double minX, minZ, maxX, maxZ;

    /** A creek's channels from their node lists, each {x, z, half width, depth} per node. */
    TidalCreek(long id, List<List<double[]>> channels) {
        this.id = id;
        int n = 0;
        for (List<double[]> c : channels) n += c.size();
        x = new double[n];
        z = new double[n];
        halfWidth = new double[n];
        depth = new double[n];
        start = new int[channels.size() + 1];
        double x0 = Double.POSITIVE_INFINITY, z0 = x0, x1 = -x0, z1 = -x0;
        int k = 0;
        for (int c = 0; c < channels.size(); c++) {
            start[c] = k;
            for (double[] p : channels.get(c)) {
                x[k] = p[0];
                z[k] = p[1];
                halfWidth[k] = p[2];
                depth[k] = p[3];
                x0 = Math.min(x0, p[0] - p[2]);
                z0 = Math.min(z0, p[1] - p[2]);
                x1 = Math.max(x1, p[0] + p[2]);
                z1 = Math.max(z1, p[1] + p[2]);
                k++;
            }
        }
        start[channels.size()] = k;
        minX = x0;
        minZ = z0;
        maxX = x1;
        maxZ = z1;
    }

    int channels() {
        return start.length - 1;
    }

    /** Whether the creek's box, widened by margin, holds the column. */
    boolean near(double px, double pz, double margin) {
        return px >= minX - margin
                && px <= maxX + margin
                && pz >= minZ - margin
                && pz <= maxZ + margin;
    }

    /**
     * The creek at a column: into out[0] its bed's height below the water's top, the deepest of
     * every channel reaching it (NaN outside the creek), and into out[1] the column's distance
     * beyond the nearest channel's edge (negative inside). A channel's bed is a block under the
     * water at its edge and its depth at its line, between them a parabola.
     */
    void column(double px, double pz, int waterTop, double[] out) {
        double bed = Double.NaN, beyond = Double.POSITIVE_INFINITY;
        for (int c = 0; c < channels(); c++)
            for (int k = start[c]; k + 1 < start[c + 1]; k++) {
                double ax = x[k], az = z[k], dx = x[k + 1] - ax, dz = z[k + 1] - az;
                double length2 = dx * dx + dz * dz;
                double t =
                        length2 == 0
                                ? 0
                                : Math.max(
                                        0,
                                        Math.min(1, ((px - ax) * dx + (pz - az) * dz) / length2));
                double d = StrictMath.hypot(px - ax - dx * t, pz - az - dz * t);
                double r = halfWidth[k] + (halfWidth[k + 1] - halfWidth[k]) * t;
                beyond = Math.min(beyond, d - r);
                if (d >= r) continue;
                double q = d / r, deep = depth[k] + (depth[k + 1] - depth[k]) * t;
                // Mid-block, so the bed's top block is depth blocks under the water's top.
                double h = waterTop - .5 - (deep - 1) * (1 - q * q);
                if (Double.isNaN(bed) || h < bed) bed = h;
            }
        out[0] = bed;
        out[1] = beyond;
    }
}
