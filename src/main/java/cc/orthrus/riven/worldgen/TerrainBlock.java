package cc.orthrus.riven.worldgen;

/** The complete block palette Riven emits; mod glue maps each entry to a Minecraft state. */
public enum TerrainBlock {
    AIR,
    BEDROCK,
    STONE,
    WATER,
    SAND,
    GRAVEL,
    DIRT,
    GRASS,
    SNOW,
    GRANITE,
    DIORITE,
    ANDESITE,
    SANDSTONE,
    RED_SANDSTONE,
    COARSE_DIRT,
    CLAY,
    TERRACOTTA,
    TERRACOTTA_ORANGE,
    TERRACOTTA_YELLOW,
    TERRACOTTA_BROWN,
    TERRACOTTA_RED,
    TERRACOTTA_WHITE,
    TERRACOTTA_GRAY,
    TERRACOTTA_BLACK,
    LAVA,
    // Cave decoration (riven-cave-decor.json); mod glue maps provider blocks with fallbacks.
    MUD,
    WHITE_SAND,
    ASH,
    MAGMA,
    COBWEB,
    DRIPSTONE_BLOCK,
    DRIPSTONE_UP,
    DRIPSTONE_DOWN,
    ROOT,
    TOADSTOOL,
    PORTOBELLO,
    BROWN_MUSHROOM,
    RED_MUSHROOM,
    MINERS_DELIGHT,
    GLOWSHROOM,
    ALGAE,
    // Riven host stones (riven-content.json; borrowed Supersymmetry art in test builds).
    GABBRO,
    GNEISS,
    GRAPHITE_SCHIST,
    LIMESTONE,
    MICA_SCHIST,
    PHYLLITE,
    QUARTZITE,
    SHALE,
    SLATE,
    SOAPSTONE,
    KIMBERLITE,
    ANORTHOSITE,
    LEUCOBASALT,
    NETHERRACK,
    // Frost Hollows (riven-realms.json): frozen pools and packed ice floors.
    ICE,
    PACKED_ICE,
    // Formations by setting (riven-cave-decor.json dripstone): pointed ice, salt and basalt.
    ICE_UP,
    ICE_DOWN,
    SALT_UP,
    SALT_DOWN,
    BASALT_UP,
    BASALT_DOWN,
    // Deep-realm and boundary rocks (riven-content.json rocks; riven-realms.json places them).
    GRANULITE,
    MIGMATITE,
    ECLOGITE,
    PERIDOTITE,
    SERPENTINITE,
    BLUESCHIST,
    GARNET_ROCK,
    CHROMITE_PERIDOTITE,
    JADEITE_SERPENTINITE,
    MOONSTONE_FAINT,
    MOONSTONE_BRIGHT,
    INFERNAL_ROCK,
    SLAG_STONE,
    BRIMSTONE,
    CINDER_ROCK,
    ASH_SHALE,
    JASPER_BANDS,
    MOLTEN_SEAMS,
    SOUL_ASH,
    CRUST_FLOOR_TRANSITION,
    BENEATH_INFERNAL_TRANSITION;

    /** The riven-content.json rocks id of a deep-realm or boundary rock, or null. */
    public String rockId() {
        return ordinal() >= GRANULITE.ordinal() ? name().toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** The riven-content.json id of a Riven host stone, or null. */
    public String stoneId() {
        switch (this) {
            case GABBRO:
                return "gabbro";
            case GNEISS:
                return "gneiss";
            case GRAPHITE_SCHIST:
                return "graphite";
            case LIMESTONE:
                return "limestone";
            case MICA_SCHIST:
                return "mica";
            case PHYLLITE:
                return "phyllite";
            case QUARTZITE:
                return "quartzite";
            case SHALE:
                return "shale";
            case SLATE:
                return "slate";
            case SOAPSTONE:
                return "soapstone";
            case KIMBERLITE:
                return "kimberlite";
            case ANORTHOSITE:
                return "anorthosite";
            case LEUCOBASALT:
                return "leucobasalt";
            default:
                return null;
        }
    }
}
