package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/**
 * Earlier material permissions, independent of terrain, climate and water queries. Revision 1
 * preserves the authored constant prototype values; these are not geology or sediment supply.
 */
public final class SubstrateMap {
    private static final Rules RULES = Rules.load("riven-substrate.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private final double sandPotential, looseMaterialPotential, windErodibility;

    public SubstrateMap() {
        this(RULES.json);
    }

    SubstrateMap(JsonObject definition) {
        sandPotential = Rules.number(definition, "sandPotential", 0, 1);
        looseMaterialPotential = Rules.number(definition, "looseMaterialPotential", 0, 1);
        windErodibility = Rules.number(definition, "windErodibility", 0, 1);
    }

    /** Caller-owned reusable scratch. Only this provider can write its three permissions. */
    public static final class Sample {
        private double sandPotential, looseMaterialPotential, windErodibility;

        public double sandPotential() {
            return sandPotential;
        }

        public double looseMaterialPotential() {
            return looseMaterialPotential;
        }

        public double windErodibility() {
            return windErodibility;
        }
    }

    /**
     * Accepts finite absolute coordinates, including negative and fractional positions. Writes
     * only caller scratch, retains nothing, and performs no queries or per-sample allocation.
     */
    public void sample(double x, double z, Sample out) {
        if (out == null) throw new NullPointerException("out");
        if (!Double.isFinite(x) || !Double.isFinite(z))
            throw new IllegalArgumentException("Finite absolute coordinates required");
        out.sandPotential = sandPotential;
        out.looseMaterialPotential = looseMaterialPotential;
        out.windErodibility = windErodibility;
    }
}
