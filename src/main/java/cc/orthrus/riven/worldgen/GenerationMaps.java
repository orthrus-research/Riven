package cc.orthrus.riven.worldgen;

import java.util.concurrent.ForkJoinPool;

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
    public final Variants variants;
    public final Boulders boulders;

    /** The kept climate draw, how many were drawn and its rarest climate's patches. */
    public final int climateAttempt, climateDraws, climateFewest;

    /** The maps that read the climate, built for one draw. */
    private final class Draw {
        final int attempt;
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
        }
    }

    /**
     * Where in-build parallelism runs. The host supplies its pool before these maps are built;
     * the tools' constructors below use the tools' shared pool. Null keeps work on the caller.
     */
    public final BuildPool pool = new BuildPool();

    public GenerationMaps(long seed) {
        this(seed, TerrainHeightReference.DEFAULT);
    }

    /** Cache-free tools maps at a layout scale (1 = 8 km tuning, 4 = 32 km worlds). */
    public GenerationMaps(long seed, WorldScale world) {
        this(seed, TerrainHeightReference.DEFAULT, world, BuildPool.tools(), null);
    }

    GenerationMaps(long seed, TerrainHeightReference heights) {
        this(seed, heights, WorldScale.DEFAULT, BuildPool.tools(), null);
    }

    /** The game's maps, built on the host's pool or caller; only the kept draw uses the store. */
    GenerationMaps(
            long seed,
            TerrainHeightReference heights,
            WorldScale world,
            ForkJoinPool executor,
            RegionalStore store) {
        if (store != null && heights != TerrainHeightReference.DEFAULT)
            throw new IllegalArgumentException("Stored maps require the standard terrain heights");
        pool.use(executor);
        plates = new PlateMap(seed, world);
        WorldSelection stored =
                store == null ? null : store.get(WorldSelection.KIND, 0, 0, WorldSelection.CODEC);
        land =
                stored == null
                        ? LandShare.choose(seed, heights, world)
                        : LandShare.restore(seed, heights, world, stored);
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
        int draws = 0, keptFewest = -1;
        if (stored != null) {
            kept = new Draw(seed, heights, world, stored.climateAttempt());
            draws = stored.climateDraws();
            keptFewest = stored.climateFewest();
        } else {
            for (int attempt = 0; attempt < ClimateCoverage.ATTEMPTS; attempt++) {
                Draw draw = new Draw(seed, heights, world, attempt);
                int fewest = ClimateCoverage.fewest(draw.terrain, world, pool);
                draws++;
                if (kept == null || fewest > keptFewest) {
                    kept = draw;
                    keptFewest = fewest;
                }
                if (fewest >= ClimateCoverage.MIN_PATCHES) break;
            }
        }
        climateAttempt = kept.attempt;
        climateDraws = draws;
        climateFewest = keptFewest;
        temperature = kept.temperature;
        climate = kept.climate;
        provinces = kept.provinces;
        baseTerrain = kept.baseTerrain;
        terrain = kept.terrain;
        if (store != null) terrain.hydro.drainage.useStore(store);
        surfaces = new SurfaceRules(heights);
        columns = new NativeColumns(terrain, surfaces, heights);
        variants = new Variants(seed, terrain);
        boulders = new Boulders(seed, terrain, variants);
        if (store != null && stored == null)
            store.put(
                    WorldSelection.KIND,
                    0,
                    0,
                    new WorldSelection(
                            land.outline.attempt,
                            land.share,
                            climateAttempt,
                            climateDraws,
                            climateFewest),
                    WorldSelection.CODEC);
    }
}
