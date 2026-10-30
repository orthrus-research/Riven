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

    /**
     * Where in-build parallelism runs. The game's maps start on the calling thread until the host
     * supplies its pool; the tools' constructors below use the tools' shared pool.
     */
    public final BuildPool pool = new BuildPool();

    public GenerationMaps(long seed) {
        this(seed, TerrainHeightReference.DEFAULT);
    }

    /** A world at a layout scale (1 = 8 km tuning, 4 = 32 km worlds). */
    public GenerationMaps(long seed, WorldScale world) {
        this(seed, TerrainHeightReference.DEFAULT, BaseTerrainMap.configuredPolicy(), world, true);
    }

    GenerationMaps(long seed, TerrainHeightReference heights) {
        this(seed, heights, BaseTerrainMap.configuredPolicy());
    }

    GenerationMaps(long seed, TerrainHeightReference heights, BaseTerrainMap.Policy policy) {
        this(seed, heights, policy, WorldScale.DEFAULT, true);
    }

    /** The game's maps ({@link WorldGenerationContext}): no pool until the host supplies one. */
    GenerationMaps(
            long seed,
            TerrainHeightReference heights,
            BaseTerrainMap.Policy policy,
            WorldScale world) {
        this(seed, heights, policy, world, false);
    }

    private GenerationMaps(
            long seed,
            TerrainHeightReference heights,
            BaseTerrainMap.Policy policy,
            WorldScale world,
            boolean tools) {
        if (tools) pool.use(BuildPool.tools());
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
                        world,
                        pool);
        surfaces = new SurfaceRules(heights);
        columns = new NativeColumns(terrain, surfaces, heights);
        boulders = new Boulders(seed, terrain);
    }
}
