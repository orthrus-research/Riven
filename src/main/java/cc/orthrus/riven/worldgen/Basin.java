package cc.orthrus.riven.worldgen;

/** Shared finite surface-water ownership, with optional supplied shape adapters. */
public final class Basin {
    public enum Supply {
        STORAGE,
        GROUNDWATER,
        OCEAN
    }

    public final Hydro.Kind kind;
    public final Supply supply;
    public final long id;
    public final double x, z, radius, depth, skirt, aspect, cos, sin;
    public final int waterY;
    public final LakeShape.Decision lakeShape;
    public final CoastShape.Decision coastShape;

    private Basin(
            Hydro.Kind kind,
            Supply supply,
            long id,
            double x,
            double z,
            double radius,
            double depth,
            double skirt,
            double angle,
            int waterY) {
        this.kind = kind;
        this.supply = supply;
        this.id = id;
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.depth = depth;
        this.skirt = skirt;
        this.waterY = waterY;
        aspect = .8;
        cos = Math.cos(angle);
        sin = Math.sin(angle);
        lakeShape = null;
        coastShape = null;
    }

    private Basin(Basin parent, LakeShape.Decision shape) {
        kind = parent.kind;
        supply = parent.supply;
        id = parent.id;
        x = parent.x;
        z = parent.z;
        radius = parent.radius;
        depth = parent.depth;
        skirt = parent.skirt;
        aspect = parent.aspect;
        cos = parent.cos;
        sin = parent.sin;
        waterY = parent.waterY;
        lakeShape = shape;
        coastShape = parent.coastShape;
    }

    public Basin withShape(LakeShape.Decision shape) {
        return new Basin(this, shape);
    }

    private Basin(Basin parent, CoastShape.Decision shape) {
        kind = parent.kind;
        supply = parent.supply;
        id = parent.id;
        x = parent.x;
        z = parent.z;
        radius = parent.radius;
        depth = parent.depth;
        skirt = parent.skirt;
        aspect = parent.aspect;
        cos = parent.cos;
        sin = parent.sin;
        waterY = parent.waterY;
        lakeShape = parent.lakeShape;
        coastShape = shape;
    }

    public Basin withCoastShape(CoastShape.Decision shape) {
        return new Basin(this, shape);
    }

    public static Basin create(
            Hydro.Kind kind,
            Supply supply,
            long id,
            double x,
            double z,
            int head,
            double radius,
            double depth,
            double skirt,
            double angle) {
        if (radius <= 0
                || depth < 1
                || skirt < 1
                || !Double.isFinite(radius + depth + skirt + x + z + angle))
            throw new IllegalArgumentException("Basin dimensions");
        return new Basin(kind, supply, id, x, z, radius, depth, skirt, angle, head);
    }

    public double support() {
        double plain = plainSupport();
        return coastShape != null && coastShape.shape != null
                ? Math.max(
                        plain,
                        Math.hypot(x - coastShape.shape.geometry.x, z - coastShape.shape.geometry.z)
                                + coastShape.shape.geometry.supportRadius)
                : plain;
    }

    private double plainSupport() {
        return radius + skirt * (kind == Hydro.Kind.LAGOON ? 2 : 1);
    }

    public void apply(double px, double pz, Hydro.Sample out, double freeboard) {
        if (coastShape != null
                && coastShape.shape != null
                && px == Math.floor(px)
                && pz == Math.floor(pz)) {
            coastShape.shape.apply(px, pz, out, coastShape);
            return;
        }
        if (lakeShape != null && lakeShape.shape != null) {
            lakeShape.shape.apply(px, pz, kind, id, out);
            return;
        }
        double dx = px - x, dz = pz - z;
        double support = plainSupport();
        if (Math.abs(dx) > support || Math.abs(dz) > support) return;
        double u = (dx * cos + dz * sin) / radius,
                v = (-dx * sin + dz * cos) / (radius * aspect),
                r = Math.sqrt(u * u + v * v);
        boolean mouth =
                kind == Hydro.Kind.LAGOON
                        && u > 0
                        && u * radius <= support
                        && Math.abs(v) * radius * aspect < skirt * .25;
        if (r > 1 + skirt / radius && !mouth) return;
        boolean pool = r <= 1;
        // Lagoon axes point seaward. This reserved mouth passes through the barrier
        // at the ocean head; admission separately verifies its marine receiver.
        if (mouth) pool = true;
        if (kind == Hydro.Kind.WETLAND) {
            // Saturated shelf with several shallow pools; all share one storage head.
            pool =
                    u * u + v * v < .12
                            || square(u - .48) + square(v + .1) < .07
                            || square(u + .37) + square(v - .25) < .075;
        }
        if (pool) {
            if (out.wet && kind == Hydro.Kind.LAGOON && out.waterY == waterY) {
                out.kind = kind;
                out.bed = Hydro.Bed.SAND;
            }
            // The existing network owns overlaps. Connected additions share its head.
            if (!out.wet) {
                out.wet = true;
                out.waterY = waterY;
                out.floor = waterY - Math.max(1, depth * (.4 + .6 * (1 - Math.min(1, r * r))));
                out.weight = 1;
                out.kind = kind;
                out.id = id;
                out.head = waterY;
                out.bed =
                        kind == Hydro.Kind.LAGOON || kind == Hydro.Kind.OASIS
                                ? Hydro.Bed.SAND
                                : kind == Hydro.Kind.WETLAND ? Hydro.Bed.DIRT : Hydro.Bed.GRAVEL;
            }
        } else if (!out.wet) {
            double rim = kind == Hydro.Kind.LAGOON ? Math.min(.2, skirt / radius * .35) : 0;
            double weight = 1 - Rules.smooth((r - 1 - rim) / (skirt / radius - rim));
            if (weight > out.weight) {
                out.weight = weight;
                out.bank = waterY + (kind == Hydro.Kind.WETLAND ? 1 : freeboard);
                out.kind = kind;
                out.id = id;
                out.head = waterY;
            }
        }
    }

    private static double square(double v) {
        return v * v;
    }
}
