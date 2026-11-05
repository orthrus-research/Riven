package cc.orthrus.riven.worldgen;

/** One seed-owned set of pure maps. Callers sample only the layer they need. */
public final class GenerationMaps {
    public final TemperatureMap temperature;
    public final ClimateMap climate;
    public final ClimateStructureMap climateStructure;
    public final ClimateForcingMap climateForcing;
    public final PlateMap plates;
    public final LandShare land;
    public final ContinentalOutlineMap outline;
    public final ContinentalMap continents;
    public final SpineMap spines;
    public final ProvinceMap provinces;
    public final SubstrateMap substrate;
    public final RegionalProfileMap regions;
    public final BaseElevationMap baseElevation;
    public final BaseTerrainMap baseTerrain;
    public final TerrainMap terrain;
    public final SurfaceRules surfaces;
    public final NativeColumns columns;
    public final Boulders boulders;

    /** The kept climate draw, how many were drawn and its rarest climate's patches. */
    public final int climateAttempt, climateDraws, climateFewest;

    /** The maps that read the climate, built for one draw. */
    private final class Draw {
        final int attempt, fewest;
        final TemperatureMap temperature;
        final ClimateMap climate;
        final ProvinceMap provinces;
        final BaseTerrainMap baseTerrain;
        final TerrainMap terrain;

        Draw(long seed, TerrainHeightReference heights, WorldScale world, int attempt) {
            this.attempt = attempt;
            temperature = new TemperatureMap(seed, world, attempt);
            climate = new ClimateMap(seed, temperature, climateForcing);
            provinces = new ProvinceMap(seed, world, plates, continents, spines, climate);
            baseTerrain = new BaseTerrainMap(seed, baseElevation, provinces, heights, regions);
            terrain =
                    new TerrainMap(
                            seed,
                            plates,
                            heights,
                            climate,
                            provinces,
                            continents,
                            substrate,
                            baseTerrain,
                            world,
                            pool);
            fewest = ClimateCoverage.fewest(terrain, world, pool);
        }
    }

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
        this(seed, TerrainHeightReference.DEFAULT, world, true);
    }

    GenerationMaps(long seed, TerrainHeightReference heights) {
        this(seed, heights, WorldScale.DEFAULT, true);
    }

    /** The game's maps ({@link WorldGenerationContext}): no pool until the host supplies one. */
    GenerationMaps(long seed, TerrainHeightReference heights, WorldScale world) {
        this(seed, heights, world, false);
    }

    private GenerationMaps(
            long seed, TerrainHeightReference heights, WorldScale world, boolean tools) {
        if (tools) pool.use(BuildPool.tools());
        plates = new PlateMap(seed, world);
        land = LandShare.choose(seed, heights, world);
        outline = land.outline;
        baseElevation = new BaseElevationMap(seed, plates, heights);
        climateStructure = new ClimateStructureMap(outline, baseElevation, heights);
        climateForcing = new ClimateForcingMap(climateStructure, heights, world);
        continents = new ContinentalMap(seed, heights, outline);
        spines = new SpineMap(seed, continents, world, pool);
        substrate = new SubstrateMap();
        regions = new RegionalProfileMap(seed, heights);
        // The climate draw: the first phase in which every climate forms enough patches, else
        // the draw with the most for its rarest climate (ClimateCoverage).
        Draw kept = null;
        int draws = 0;
        for (int attempt = 0; attempt < ClimateCoverage.ATTEMPTS; attempt++) {
            Draw draw = new Draw(seed, heights, world, attempt);
            draws++;
            if (kept == null || draw.fewest > kept.fewest) kept = draw;
            if (draw.fewest >= ClimateCoverage.MIN_PATCHES) break;
        }
        climateAttempt = kept.attempt;
        climateDraws = draws;
        climateFewest = kept.fewest;
        temperature = kept.temperature;
        climate = kept.climate;
        provinces = kept.provinces;
        baseTerrain = kept.baseTerrain;
        terrain = kept.terrain;
        surfaces = new SurfaceRules(heights);
        columns = new NativeColumns(terrain, surfaces, heights);
        boulders = new Boulders(seed, terrain);
    }
}
