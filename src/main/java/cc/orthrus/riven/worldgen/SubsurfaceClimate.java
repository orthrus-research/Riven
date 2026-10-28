package cc.orthrus.riven.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * The subsurface climate (riven-subsurface-climate.json): temperature, humidity and air at a block
 * below the ground, from the land's climate, the depth below the ground and the geology's water
 * table, lava level, rift strength, magma chambers, faults and rock. A pure function of seed,
 * definitions and coordinates. Biome rules and player effects read it; no generated block does
 * yet.
 */
public final class SubsurfaceClimate {
    private static final Rules RULES = Rules.load("riven-subsurface-climate.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final double GRADIENT,
            RIFT_BOOST,
            LAVA_CELSIUS,
            LAVA_REACH,
            CHAMBER_CELSIUS,
            CHAMBER_REACH,
            RIFT_FAULT_CELSIUS,
            RIFT_FAULT_REACH;
    private static final double[] REALM_TOP_CELSIUS, REALM_GRADIENT;
    private static final double FRINGE,
            SATURATION_FRINGE,
            AQUIFER_REACH,
            RAIN_REACH,
            ROCK_BASE,
            ROCK_WEIGHT,
            RECHARGE_BASE,
            RECHARGE_WEIGHT,
            FAULT_HUMIDITY,
            FAULT_WET_REACH,
            EVAPORITE_FACTOR,
            DRYING_CELSIUS,
            DRYING_SCALE;
    private static final double AIR_DEPTH,
            AIR_FLOOR,
            FAULT_BOOST,
            FAULT_REACH,
            GAS_CELSIUS,
            GAS_SCALE;
    private static final double FROZEN_CELSIUS, STEAM_CELSIUS, STEAM_HUMIDITY;
    private static final GeologyMap.Unit EVAPORITE = GeologyMap.named("evaporite");

    static {
        JsonObject t = RULES.json.getAsJsonObject("temperature");
        GRADIENT = Rules.number(t, "gradientPerBlock", 0, 1);
        RIFT_BOOST = Rules.number(t, "riftGradientBoost", 0, 10);
        JsonObject lava = t.getAsJsonObject("lava"), chambers = t.getAsJsonObject("chambers");
        LAVA_CELSIUS = Rules.number(lava, "celsius", 0, 1000);
        LAVA_REACH = Rules.number(lava, "reachBlocks", 1, 512);
        CHAMBER_CELSIUS = Rules.number(chambers, "celsius", 0, 1000);
        CHAMBER_REACH = Rules.number(chambers, "reachBlocks", 1, 512);
        JsonObject riftFaults = t.getAsJsonObject("riftFaults");
        RIFT_FAULT_CELSIUS = Rules.number(riftFaults, "celsius", 0, 1000);
        RIFT_FAULT_REACH = Rules.number(riftFaults, "reachBlocks", 1, 512);
        JsonObject realms = t.getAsJsonObject("realms");
        REALM_TOP_CELSIUS = new double[RealmMap.realms().size()];
        REALM_GRADIENT = new double[REALM_TOP_CELSIUS.length];
        for (Map.Entry<String, JsonElement> e : realms.entrySet()) {
            int k = realmIndex(e.getKey());
            if (k < 0) throw new IllegalStateException("Unknown realm: " + e.getKey());
            JsonObject r = e.getValue().getAsJsonObject();
            REALM_TOP_CELSIUS[k] = Rules.number(r, "celsiusAtTop", -100, 1000);
            REALM_GRADIENT[k] = Rules.number(r, "gradientPerBlock", 0, 1);
        }
        for (int k = 0; k < REALM_TOP_CELSIUS.length; k++)
            if (!realms.has(RealmMap.realms().get(k).id))
                throw new IllegalStateException(
                        "No climate for realm " + RealmMap.realms().get(k).id);
        JsonObject h = RULES.json.getAsJsonObject("humidity");
        FRINGE = Rules.number(h, "fringeBlocks", 1, 512);
        JsonObject saturation = h.getAsJsonObject("saturation");
        SATURATION_FRINGE = Rules.number(saturation, "fringeBlocks", 1, 512);
        AQUIFER_REACH = Rules.number(saturation, "aquiferReachBlocks", 1, 4096);
        RAIN_REACH = Rules.number(h, "rainReachBlocks", 1, 512);
        ROCK_BASE = Rules.number(h, "rockBase", 0, 1);
        ROCK_WEIGHT = Rules.number(h, "rockPermeabilityWeight", 0, 1);
        JsonObject recharge = h.getAsJsonObject("recharge"), wet = h.getAsJsonObject("faults");
        RECHARGE_BASE = Rules.number(recharge, "base", 0, 2);
        RECHARGE_WEIGHT = Rules.number(recharge, "moistureWeight", 0, 2);
        FAULT_HUMIDITY = Rules.number(wet, "humidity", 0, 1);
        FAULT_WET_REACH = Rules.number(wet, "reachBlocks", 1, 512);
        EVAPORITE_FACTOR = Rules.number(h, "evaporiteFactor", 0, 1);
        DRYING_CELSIUS = Rules.number(h, "dryingCelsius", -100, 1000);
        DRYING_SCALE = Rules.number(h, "dryingScaleCelsius", 1, 1000);
        JsonObject a = RULES.json.getAsJsonObject("air");
        AIR_DEPTH = Rules.number(a, "depthScaleBlocks", 1, 4096);
        AIR_FLOOR = Rules.number(a, "floor", 0, 1);
        FAULT_BOOST = Rules.number(a, "faultBoost", 0, 1);
        FAULT_REACH = Rules.number(a, "faultReachBlocks", 1, 512);
        GAS_CELSIUS = Rules.number(a, "gasCelsius", -100, 1000);
        GAS_SCALE = Rules.number(a, "gasScaleCelsius", 1, 1000);
        JsonObject s = RULES.json.getAsJsonObject("states");
        FROZEN_CELSIUS = Rules.number(s, "frozenCelsius", -100, 100);
        STEAM_CELSIUS = Rules.number(s, "steamCelsius", 0, 1000);
        STEAM_HUMIDITY = Rules.number(s, "steamHumidity", 0, 1);
    }

    private SubsurfaceClimate() {}

    private static int realmIndex(String id) {
        for (int k = 0; k < RealmMap.realms().size(); k++)
            if (RealmMap.realms().get(k).id.equals(id)) return k;
        return -1;
    }

    /** One block's climate. */
    public static final class Reading {
        /** Temperature in Celsius. */
        public double celsius;

        /** Humidity and air freshness, 0 to 1. */
        public double humidity, air;

        /** Blocks below the column's ground. */
        public double depth;

        public boolean frozen, steam;
    }

    /**
     * The climate at block Y of a sampled column (geology set). realm is the {@link
     * RealmMap#realms} index below the crust floor, or -1 in the crust.
     */
    public static Reading at(long y, TerrainMap.Sample s, int realm, Reading out) {
        GeologyMap.Column g = s.geology;
        GeologyMap.Unit unit = g.formation(y);
        double depth = Math.max(0, s.blockY() - y);
        // A fault counts where it has thrown; its nearness falls off with distance.
        boolean fault = g.faultThrow != 0;
        double celsius;
        if (realm < 0) {
            celsius = s.climate.celsius + GRADIENT * (1 + RIFT_BOOST * g.rift) * depth;
            // Full heat at and below the lava level, falling off above it.
            celsius += LAVA_CELSIUS * Math.exp(-Math.max(0, y - g.lavaY) / LAVA_REACH);
            if (fault)
                celsius +=
                        RIFT_FAULT_CELSIUS * g.rift * Math.exp(-g.faultDistance / RIFT_FAULT_REACH);
        } else
            celsius =
                    REALM_TOP_CELSIUS[realm]
                            + REALM_GRADIENT[realm]
                                    * Math.max(0, RealmMap.realms().get(realm).top - y);
        if (g.chamberGap < Double.POSITIVE_INFINITY) {
            // Over a chamber, the distance beyond its lens here; beside it, from its rim.
            double vertical =
                    g.chamberTop > Double.NEGATIVE_INFINITY
                            ? Math.max(0, Math.max(y - g.chamberTop, g.chamberBottom - y))
                            : Math.abs(y - g.chamberCentreY);
            celsius +=
                    CHAMBER_CELSIUS * Math.exp(-Math.hypot(g.chamberGap, vertical) / CHAMBER_REACH);
        }

        // Water peaks at the water table. Below it, rock is saturated just under the table and
        // permeable formations stay wet much deeper (confined aquifers), shales and crystalline
        // rock dry.
        double moisture = clamp(s.climate.moisture);
        double water =
                y >= g.waterTable
                        ? Math.exp(-(y - g.waterTable) / FRINGE)
                        : Math.max(
                                Math.exp(-(g.waterTable - y) / SATURATION_FRINGE),
                                unit.permeability * Math.exp(-(g.waterTable - y) / AQUIFER_REACH));
        double rain = moisture * Math.exp(-depth / RAIN_REACH);
        double rock =
                (ROCK_BASE + ROCK_WEIGHT * unit.permeability)
                        * (RECHARGE_BASE + RECHARGE_WEIGHT * moisture);
        // Water running along a fault is groundwater: like the water table's, heat does not dry
        // it, so hot faults steam.
        if (fault)
            water = Math.max(water, FAULT_HUMIDITY * Math.exp(-g.faultDistance / FAULT_WET_REACH));
        double dry = Math.max(rain, rock);
        if (celsius > DRYING_CELSIUS) dry *= Math.exp(-(celsius - DRYING_CELSIUS) / DRYING_SCALE);
        double humidity = Math.max(water, dry);
        if (unit == EVAPORITE) humidity *= EVAPORITE_FACTOR;

        double air = AIR_FLOOR + (1 - AIR_FLOOR) * Math.exp(-depth / AIR_DEPTH);
        if (fault) air += FAULT_BOOST * Math.exp(-g.faultDistance / FAULT_REACH);
        if (celsius > GAS_CELSIUS) air *= Math.exp(-(celsius - GAS_CELSIUS) / GAS_SCALE);

        out.celsius = celsius;
        out.humidity = clamp(humidity);
        out.air = clamp(air);
        out.depth = depth;
        out.frozen = celsius <= FROZEN_CELSIUS;
        out.steam = celsius >= STEAM_CELSIUS && out.humidity >= STEAM_HUMIDITY;
        return out;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
