package cc.orthrus.riven.worldgen;

/** Direct terrain composition. All samples are independent of loading order and neighboring worlds. */
public final class TerrainMap {
    public static final int VERSION = 85;
    private final TerrainHeightReference heights;
    private final BaseTerrainMap baseTerrain;
    private final ErosionFilter erosion;
    private final ClimateMap climate;
    private final RegionalTerrainPasses passes;
    private final LowlandTerrain lowlands;
    private final HillTerrain hills;
    private final MountainTerrain mountains;
    private final PlateauTerrain plateaus;
    private final DrylandTerrain drylands;
    private final ColdTerrain cold;
    private final VolcanicTerrain volcanic;
    private final GeneralSurfaceCuts cuts;
    public final SedimentTerrain sediment;
    public final RockyTerrain rocky;
    private final RegionalLandmarks landmarks;
    private final SurfaceCarvers carvers;
    private final WonderTerrain wonders;
    private final GeologyMap geology;
    private final FloodMap floods;
    public final CaveMap caves;
    public final OreMap ores;
    public final Terrain types;
    public final Shore shores;
    public final OceanBed oceanBed;
    public final Hydro hydro;
    public final Head groundwater;
    private final ContinentalMap continents;
    private final IntentSampler intentSampler;
    private final MajorFeatureParent majorFeatureParent;

    public final int minimumSurfaceY, maximumSurfaceY;
    private final double cutWaterSkirt = Terrain.cutWaterSkirt();

    /** Built only by {@link GenerationMaps}, which owns every earlier service once per seed. */
    TerrainMap(
            long seed,
            PlateMap plates,
            TerrainHeightReference heights,
            ClimateMap climate,
            ContinentalMap continents,
            SubstrateMap substrate,
            BaseTerrainMap baseTerrain,
            RegionalFabricMap fabric) {
        if (climate == null || continents == null || baseTerrain == null || fabric == null)
            throw new NullPointerException();
        this.baseTerrain = baseTerrain;
        this.heights = heights;
        this.continents = continents;
        this.climate = climate;
        types = new Terrain(seed, heights);
        shores = new Shore(seed, heights);
        passes = new RegionalTerrainPasses(seed, heights);
        lowlands = new LowlandTerrain(seed, heights);
        drylands = new DrylandTerrain(seed, heights, substrate);
        landmarks = new RegionalLandmarks(seed, heights, types.sites);
        carvers = new SurfaceCarvers(seed, heights, types.sites, plates);
        oceanBed = new OceanBed(seed, heights, continents);
        minimumSurfaceY =
                Math.min(
                        heights.lowestSurface,
                        Math.min(
                                oceanBed.minimumSurfaceY,
                                Math.min(continents.minimumSurfaceY, baseTerrain.minimumSurfaceY)));
        // Relative plateau/mesa envelopes can exceed the broad mountain band's top.
        maximumSurfaceY = heights.highestSurface;
        erosion = new ErosionFilter(seed, heights);
        groundwater = new Head(seed, heights);
        intentSampler = this::intent;
        hydro = new Hydro(seed, heights, intentSampler, groundwater, continents, oceanBed);
        majorFeatureParent = this::sampleBeforeMajorFeatures;
        hills =
                new HillTerrain(
                        seed,
                        heights,
                        types.sites,
                        intentSampler,
                        majorFeatureParent,
                        hydro,
                        fabric);
        mountains =
                new MountainTerrain(
                        seed, heights, continents.outline, intentSampler, hydro, hydro.cellBlocks);
        plateaus =
                new PlateauTerrain(
                        seed, heights, types.sites, majorFeatureParent, hydro, hydro.cellBlocks);
        cold =
                new ColdTerrain(
                        seed, heights, types.sites, majorFeatureParent, hydro, hydro.cellBlocks);
        volcanic =
                new VolcanicTerrain(
                        seed,
                        heights,
                        types.sites,
                        majorFeatureParent,
                        hydro,
                        hydro.cellBlocks,
                        plates.cellBlocks());
        cuts =
                new GeneralSurfaceCuts(
                        seed,
                        heights,
                        types.sites,
                        intentSampler,
                        majorFeatureParent,
                        hydro,
                        hydro.cellBlocks);
        wonders = new WonderTerrain(seed, heights, types.sites, majorFeatureParent, hydro);
        geology = new GeologyMap(seed, plates);
        floods = new FloodMap(seed, this::sampleGeology);
        caves = new CaveMap(seed, heights, this::sample);
        ores = new OreMap(seed, heights);
        rocky =
                new RockyTerrain(
                        seed,
                        heights,
                        types.sites,
                        intentSampler,
                        this::sampleBeforeRocky,
                        hydro,
                        hydro.cellBlocks);
        sediment =
                new SedimentTerrain(
                        seed,
                        heights,
                        types.sites,
                        intentSampler,
                        this::sampleBeforeSediment,
                        hydro,
                        hydro.cellBlocks,
                        hydro.bankScale);
    }

    /** Scratch belongs to one call sequence, never to the map or a shared cache. */
    public static class BaseSample {
        final BaseTerrainMap.Sample base = new BaseTerrainMap.Sample();

        /** The column this sample describes (set by full samples, for block variety noise). */
        public double x, z;

        public BaseTerrainMap.Sample baseTerrain() {
            return base;
        }

        public double baseHeight,
                baseDx,
                baseDz,
                broadDx,
                broadDz,
                minimumY,
                maximumY,
                uplift,
                blend,
                intentDx,
                intentDz;
        public final Terrain.Sample land = new Terrain.Sample();
        public final ContinentalMap.Sample ocean = new ContinentalMap.Sample();
        public final Shore.Sample shore = new Shore.Sample();
        public final ClimateMap.Sample climate = new ClimateMap.Sample();
        public final Hydro.Sample water = new Hydro.Sample();

        /** Logical biome locked at the end of intent; later stages read it, never recompute. */
        public final Biomes.Lock biome = new Biomes.Lock();

        public String firstLandform, secondLandform;

        /** Final authorized emergence; earlier intent/hydrology never infer land from this fact. */
        public boolean emergentSediment;

        public boolean emergentRock;
    }

    public static final class Sample extends BaseSample {
        public final ErosionFilter.Result erosion = new ErosionFilter.Result();
        public final RegionalTerrainPasses.Result passes = new RegionalTerrainPasses.Result();
        public final RegionalLandmarks.Result landmarks = new RegionalLandmarks.Result();
        public double height, originalHeight, groundwaterHead, rockyParentHeight;

        public int blockY() {
            return (int) Math.floor(height);
        }

        public int waterY() {
            return water.waterY;
        }

        public final SurfaceCarvers.Result carvers = new SurfaceCarvers.Result();
        public final OceanBed.Result oceanFloor = new OceanBed.Result();
        public final LowlandTerrain.Result lowland = new LowlandTerrain.Result();
        public final HillTerrain.Result hills = new HillTerrain.Result();
        public final MountainTerrain.Result mountains = new MountainTerrain.Result();
        public final PlateauTerrain.Result plateaus = new PlateauTerrain.Result();
        public final DrylandTerrain.Result drylands = new DrylandTerrain.Result();
        public final ColdTerrain.Result cold = new ColdTerrain.Result();
        public final VolcanicTerrain.Result volcanic = new VolcanicTerrain.Result();
        public final GeneralSurfaceCuts.Result cuts = new GeneralSurfaceCuts.Result();
        public final SedimentTerrain.Result sediment = new SedimentTerrain.Result();
        public final RockyTerrain.Result rocky = new RockyTerrain.Result();
        public final WonderTerrain.Result wonders = new WonderTerrain.Result();
        public final GeologyMap.Column geology = new GeologyMap.Column();

        /** The column's flood cell: its water level, band floor and the seals holding it. */
        final FloodMap.Column flood = new FloodMap.Column();

        /** Entrances from every sample; native chunk columns add the 3D caves. */
        public final CaveMap.Voids caves = new CaveMap.Voids();
    }

    public void sample(double x, double z, Sample out) {
        sample(x, z, ErosionFilter.OCTAVES, out);
    }

    public void sample(double x, double z, int octaves, Sample out) {
        out.x = x;
        out.z = z;
        // Native coast certificates use the canonical parent, even when an alternate preview asks
        // for fewer octaves.
        int parentOctaves =
                octaves != ErosionFilter.OCTAVES
                                && (sediment.hasGeometry(x, z) || rocky.hasGeometry(x, z))
                        ? ErosionFilter.OCTAVES
                        : octaves;
        sampleBeforeSediment(x, z, parentOctaves, out);
        sediment.apply(x, z, out);
        seal(out);
        // The rock beneath reads only smooth earlier facts, never the shaped surface.
        geology.column(x, z, out, out.geology);
        floods.column(x, z, out.flood);
        caves.entrances(x, z, out);
    }

    /** A flood cell centre's sample: up to its geology column, without flood cells or caves. */
    private void sampleGeology(double x, double z, Sample out) {
        out.x = x;
        out.z = z;
        sampleBeforeSediment(x, z, ErosionFilter.OCTAVES, out);
        sediment.apply(x, z, out);
        seal(out);
        geology.column(x, z, out, out.geology);
    }

    /** Frozen complete parent. No sediment plan or final biome query is reachable from this stage. */
    public void sampleBeforeSediment(double x, double z, Sample out) {
        sampleBeforeSediment(x, z, ErosionFilter.OCTAVES, out);
    }

    private void sampleBeforeSediment(double x, double z, int octaves, Sample out) {
        sampleBeforeRocky(x, z, octaves, out);
        rocky.apply(x, z, out);
        seal(out);
    }

    /** Frozen complete shore parent. Rocky planning cannot query its own layer or later sediment. */
    public void sampleBeforeRocky(double x, double z, Sample out) {
        sampleBeforeRocky(x, z, ErosionFilter.OCTAVES, out);
    }

    private void sampleBeforeRocky(double x, double z, int octaves, Sample out) {
        sampleBeforeMajorFeatures(x, z, octaves, out);
        if (out.ocean.marine) return;
        // Major features keep the coast by composition: each raise is softly capped by the major
        // envelope instead of excluding coastal columns.
        double coast = out.ocean.coastCoordinate, parent = out.height;
        volcanic.apply(x, z, out, parent, out.volcanic);
        out.volcanic.height = shores.majorEnvelope(parent, out.volcanic.height, coast);
        out.volcanic.delta = out.volcanic.height - parent;
        parent = out.volcanic.height;
        cold.apply(x, z, out, parent, out.cold);
        out.cold.height = shores.majorEnvelope(parent, out.cold.height, coast);
        out.cold.delta = out.cold.height - parent;
        parent = out.cold.height;
        plateaus.apply(x, z, out, parent, out.plateaus);
        out.plateaus.height = shores.majorEnvelope(parent, out.plateaus.height, coast);
        out.plateaus.delta = out.plateaus.height - parent;
        parent = out.plateaus.height;
        mountains.apply(x, z, out, parent, out.mountains);
        out.mountains.height = shores.majorEnvelope(parent, out.mountains.height, coast);
        out.mountains.delta = out.mountains.height - parent;
        hills.apply(x, z, out.mountains.height, out.hills);
        // Hills keep the coast by composition: their raise is softly capped by the envelope.
        out.hills.height = shores.envelope(out.mountains.height, out.hills.height, out.shore);
        out.hills.delta = out.hills.height - out.mountains.height;
        landmarks.apply(
                x,
                z,
                intentSampler,
                out,
                out.hills.height,
                out.minimumY,
                out.maximumY,
                out.landmarks);
        out.height = out.landmarks.height;
        carvers.apply(x, z, intentSampler, out, out.carvers);
        cuts.apply(x, z, out, out.carvers.height, out.cuts);
        // Carvers and cuts know nothing of water: ease their lowering to nothing toward it, or a
        // river's bank (held up so it cannot spill) stands as a fin beside a cut.
        double cut = out.cuts.height;
        if (cut < out.landmarks.height)
            cut =
                    out.landmarks.height
                            + (cut - out.landmarks.height)
                                    * Rules.smooth(
                                            hydro.surfaceClearance(x, z, cutWaterSkirt)
                                                    / cutWaterSkirt);
        drylands.apply(x, z, out, hydro, cut, out.drylands);
        // Wonders come last on land, so they rise above whatever earlier stages shaped.
        double dry = out.drylands.height;
        wonders.apply(x, z, out, dry, out.wonders);
        out.wonders.height = shores.majorEnvelope(dry, out.wonders.height, coast);
        out.wonders.delta = out.wonders.height - dry;
        out.rockyParentHeight = out.wonders.height;
        out.height = out.shore.shape(out.wonders.height);
        out.height = hydro.constrain(out.height, out.water);
        // A wonder's void stays under at least two solid blocks of the final surface.
        if (out.wonders.hasVoid()) {
            out.wonders.voidTop = Math.min(out.wonders.voidTop, out.blockY() - 1);
            if (out.wonders.voidTop <= out.wonders.voidBottom)
                out.wonders.voidBottom = out.wonders.voidTop = 0;
        }
        seal(out);
    }

    /** Acyclic parent stage for admission. No major features, carvers or dryland fields. */
    public void sampleBeforeMajorFeatures(double x, double z, Sample out) {
        sampleBeforeMajorFeatures(x, z, ErosionFilter.OCTAVES, out);
    }

    private void sampleBeforeMajorFeatures(double x, double z, int octaves, Sample out) {
        habitat(x, z, out);
        out.groundwaterHead = groundwater.at(x, z, out);
        if (out.ocean.marine) {
            oceanBed.apply(x, z, out.ocean, out.water.wet, out.oceanFloor);
            out.originalHeight = out.oceanFloor.height;
            // At sea only a river's wet channel applies: a bank keeps land above the river's
            // water, and raising the sea floor to it left sand levees running out to sea.
            out.height =
                    out.water.wet
                            ? hydro.constrain(out.originalHeight, out.water)
                            : out.originalHeight;
            out.rockyParentHeight = out.height;
            out.sediment.clear(out.height);
            out.rocky.clear(out.height);
            out.carvers.clear(out.height);
            out.landmarks.clear(out.height);
            out.passes.clear(out.height);
            out.lowland.clear(out.height);
            out.hills.clear(out.height);
            out.mountains.clear(out.height);
            out.plateaus.clear(out.height);
            out.drylands.clear(out.height);
            out.cold.clear(out.height);
            out.volcanic.clear(out.height);
            out.cuts.clear(out.height);
            out.wonders.clear(out.height);
            out.erosion.displacement =
                    out.erosion.coarseDisplacement = out.erosion.ridgeTendency = 0;
            out.erosion.octavesEvaluated = out.erosion.kernelVisits = 0;
            seal(out);
            return;
        }
        // Filter in nominal coordinates as the horizontal footprint shrinks.
        double scale = TerrainScale.HORIZONTAL;
        erosion.apply(
                x / scale,
                z / scale,
                out.baseDx * scale,
                out.baseDz * scale,
                out.base.noise(),
                Math.min(out.baseHeight - out.minimumY, out.maximumY - out.baseHeight),
                octaves,
                out.erosion);
        out.originalHeight = out.baseHeight + out.erosion.displacement;
        lowlands.apply(x, z, out, hydro, out.originalHeight, out.lowland);
        double fine =
                out.base.fineContribution()
                        + out.erosion.displacement
                        - out.erosion.coarseDisplacement;
        passes.apply(
                x,
                z,
                out.climate,
                out.land,
                out.lowland.height,
                fine,
                out.minimumY,
                out.maximumY,
                out.passes);
        out.height = out.passes.height;
        // A reused prefix sample must not expose observations from a prior coordinate or stage.
        out.oceanFloor.clear(out.height);
        out.rockyParentHeight = out.height;
        out.sediment.clear(out.height);
        out.rocky.clear(out.height);
        out.plateaus.clear(out.height);
        out.mountains.clear(out.height);
        out.hills.clear(out.height);
        out.landmarks.clear(out.height);
        out.carvers.clear(out.height);
        out.drylands.clear(out.height);
        out.cold.clear(out.height);
        out.volcanic.clear(out.height);
        out.cuts.clear(out.height);
        out.wonders.clear(out.height);
    }

    private void seal(Sample out) {
        if (out.water.aquiferMin <= out.water.aquiferMax) {
            out.water.aquiferMax =
                    Math.min(out.water.aquiferMax, out.blockY() - out.water.aquiferRoof);
            if (out.water.aquiferMax < out.water.aquiferMin) {
                out.water.aquiferMin = Integer.MAX_VALUE;
                out.water.aquiferMax = Integer.MIN_VALUE;
                out.water.aquiferLevel = Integer.MIN_VALUE;
                out.water.aquiferId = 0;
            }
        }
    }

    /** Water habitat reads a finite plan after intent; planners themselves use intent only. */
    public void habitat(double x, double z, BaseSample out) {
        intent(x, z, out);
        hydro.sample(x, z, out.water);
        if (out.ocean.marine && out.ocean.supplied && !out.water.wet)
            out.water.waterY = heights.waterTopY;
    }

    /** Final biome callers include authorized coastal emergence; planners continue to use habitat/intent. */
    public void surfaceHabitat(double x, double z, Sample out) {
        habitat(x, z, out);
        if (sediment.hasGeometry(x, z) || rocky.hasGeometry(x, z)) sample(x, z, out);
    }

    /** Classification entry point for selectors and biome queries. Never executes shaping. */
    public void intent(double x, double z, BaseSample out) {
        out.emergentSediment = out.emergentRock = false;
        out.water.clear();
        continents.sample(x, z, out.ocean);
        climate.sample(x, z, out.climate);
        if (out.ocean.marine) {
            if (out.ocean.supplied) out.water.waterY = heights.waterTopY;
            out.baseHeight = out.ocean.bedHeight;
            out.minimumY = minimumSurfaceY;
            out.maximumY = heights.waterTopY;
            out.baseDx =
                    out.baseDz =
                            out.broadDx =
                                    out.broadDz =
                                            out.intentDx =
                                                    out.intentDz = out.uplift = out.blend = 0;
            baseTerrain.clear(out.base);
            out.firstLandform = out.ocean.deepOcean ? "deep_ocean" : "continental_shelf";
            out.secondLandform = out.firstLandform;
        } else sampleInland(x, z, out);
        types.classify(x, z, out, out.land);
        shores.classify(x, z, out, out.shore);
        Biomes.lock(out);
    }

    /** Copies observations from the earlier inland provider; this stage owns marine composition. */
    private void sampleInland(double x, double z, BaseSample out) {
        baseTerrain.sample(x, z, out.base);
        BaseTerrainMap.Sample value = out.base;
        out.baseHeight = value.height();
        out.baseDx = value.dx();
        out.baseDz = value.dz();
        out.broadDx = value.broadDx();
        out.broadDz = value.broadDz();
        out.minimumY = value.minimumY();
        out.maximumY = value.maximumY();
        out.firstLandform = value.firstLandform();
        out.secondLandform = value.secondLandform();
        out.blend = value.blend();
        out.uplift = value.uplift();
        out.intentDx = value.intentDx();
        out.intentDz = value.intentDz();
    }

    public int surfaceY(int x, int z) {
        Sample out = new Sample();
        sample(x, z, out);
        return out.blockY();
    }
}
