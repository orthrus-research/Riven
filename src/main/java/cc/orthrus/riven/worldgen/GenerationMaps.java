package cc.orthrus.riven.worldgen;

/** One seed-owned set of pure maps. Callers sample only the layer they need. */
public final class GenerationMaps {
    public final TemperatureMap temperature;
    public final ClimateMap climate;
    public final ClimateStructureMap climateStructure;
    public final ClimateForcingMap climateForcing;
    public final PlateMap plates;
    public final ContinentalOutlineMap outline;
    public final ContinentalMap continents;
    public final SubstrateMap substrate;
    public final RegionalProfileMap regions;
    public final RegionalFabricMap fabric;
    public final BaseElevationMap baseElevation;
    public final BaseTerrainMap baseTerrain;
    public final TerrainMap terrain;
    public final SurfaceRules surfaces;
    public final NativeColumns columns;
    public final Boulders boulders;

    public GenerationMaps(long seed) {
        this(seed, TerrainHeightReference.DEFAULT);
    }

    /** A world at a layout scale (1 = 8 km tuning, 4 = 32 km worlds). */
    public GenerationMaps(long seed, WorldScale world) {
        this(seed, TerrainHeightReference.DEFAULT, BaseTerrainMap.configuredPolicy(), world);
    }

    GenerationMaps(long seed, TerrainHeightReference heights) {
        this(seed, heights, BaseTerrainMap.configuredPolicy());
    }

    GenerationMaps(long seed, TerrainHeightReference heights, BaseTerrainMap.Policy policy) {
        this(seed, heights, policy, WorldScale.DEFAULT);
    }

    GenerationMaps(
            long seed,
            TerrainHeightReference heights,
            BaseTerrainMap.Policy policy,
            WorldScale world) {
        temperature = new TemperatureMap(seed, world);
        plates = new PlateMap(seed, world);
        outline = new ContinentalOutlineMap(seed, heights, world);
        baseElevation = new BaseElevationMap(seed, plates, heights, outline);
        climateStructure = new ClimateStructureMap(outline, baseElevation, heights);
        climateForcing = new ClimateForcingMap(climateStructure, heights, world);
        climate = new ClimateMap(seed, temperature, climateForcing);
        continents = new ContinentalMap(seed, heights, outline);
        substrate = new SubstrateMap();
        regions = new RegionalProfileMap(seed, heights);
        fabric = new RegionalFabricMap(seed, heights);
        baseTerrain = new BaseTerrainMap(seed, baseElevation, heights, regions, policy);
        terrain =
                new TerrainMap(
                        seed,
                        plates,
                        heights,
                        climate,
                        continents,
                        substrate,
                        baseTerrain,
                        fabric,
                        world);
        surfaces = new SurfaceRules(heights);
        columns = new NativeColumns(terrain, surfaces, heights);
        boulders = new Boulders(seed, terrain);
    }
}
