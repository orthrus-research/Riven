package cc.orthrus.riven.worldgen;

/** Caller-owned scratch sample. Derivatives use height blocks per horizontal block. */
public final class HeightSample {
    public double height, dx, dz;

    public void set(double height, double dx, double dz) {
        this.height = height;
        this.dx = dx;
        this.dz = dz;
    }
}
