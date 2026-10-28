package cc.orthrus.riven.worldgen;

/**
 * An underground lake (riven-hydrology.json layers.lakes): an elliptical chamber whose lower half
 * holds still water up to one flat waterline and whose upper half is an air dome. Caves may open
 * into the dome; carving leaves a shell of rock around the lake below its waterline, so the water
 * meets only rock, its own surface and the dome.
 */
public final class Lens {
    public final long id;
    public final double x, z, y, radius, vertical, dome, shell;
    public final int head, roof, level;

    Lens(
            long id,
            double x,
            double z,
            double y,
            double radius,
            double vertical,
            double dome,
            double shell,
            int head,
            int roof) {
        this.id = id;
        this.x = x;
        this.z = z;
        this.y = y;
        this.radius = radius;
        this.vertical = vertical;
        this.dome = dome;
        this.shell = shell;
        this.head = head;
        this.roof = roof;
        level = (int) Math.floor(y);
    }

    public boolean intersects(int cubeX, int cubeY, int cubeZ) {
        return cubeX * 16. + 15 >= x - radius - shell
                && cubeX * 16. <= x + radius + shell
                && cubeZ * 16. + 15 >= z - radius - shell
                && cubeZ * 16. <= z + radius + shell
                && cubeY * 16. + 15 >= y - vertical - shell
                && cubeY * 16. <= Math.min(head, y + dome);
    }

    public void sample(double px, double pz, Hydro.Sample out) {
        double sx = (px - x) / (radius + shell), sz = (pz - z) / (radius * .75 + shell);
        double qs = 1 - sx * sx - sz * sz;
        if (qs <= 0) return;
        // The shell: rock below the waterline within shell blocks of the lake.
        out.aquiferShellLo = (int) Math.floor(y - (vertical + shell) * Math.sqrt(qs));
        out.aquiferShellHi = level;
        double dx = (px - x) / radius, dz = (pz - z) / (radius * .75), q = 1 - dx * dx - dz * dz;
        if (q <= 0) return;
        double root = Math.sqrt(q);
        int bottom = (int) Math.ceil(y - vertical * root),
                top = Math.min(head, (int) Math.floor(y + dome * root));
        if (top < bottom) return;
        out.aquiferMin = bottom;
        out.aquiferMax = top;
        out.aquiferLevel = level;
        out.aquiferId = id;
        out.aquiferRoof = roof;
    }
}
