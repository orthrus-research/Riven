package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Definitions;
import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.WorldScale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeClassification;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeClassification.CubeClass;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeClassification.SurfaceBand;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

/**
 * Checks cached cube classifications against actual generated primers. Usage:
 * CubeClassificationCheck <new receipt.json> [seed ...]. With no seed arguments, all six seeds run.
 */
public final class CubeClassificationCheck {
    private static final long[] SEEDS = {8675309L, 101L, -991L, 7L, 42L, 1L};
    private static final TerrainHeightReference HEIGHTS = TerrainHeightReference.DEFAULT;
    private static final int RADIUS = 12;

    private CubeClassificationCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @SuppressWarnings("deprecation") // CubePrimer stores the Minecraft state registry's IDs.
    private static int registered(IBlockState state) {
        int id = Block.BLOCK_STATE_IDS.get(state);
        IBlockState decoded = id < 0 ? null : Block.BLOCK_STATE_IDS.getByValue(id);
        require(
                id >= 0 && decoded != null && decoded.getBlock() == state.getBlock(),
                "Unregistered or aliased primer state: " + state + " id=" + id);
        return id;
    }

    private static void registered(Biome biome) {
        require(
                biome != null
                        && biome.getRegistryName() != null
                        && ForgeRegistries.BIOMES.getValue(biome.getRegistryName()) == biome,
                "Unregistered primer biome: " + biome);
        int id = Biome.getIdForBiome(biome);
        require(id >= 0 && Biome.getBiome(id) == biome, "Aliased primer biome: " + biome);
    }

    private static final class CheckedPrimer extends CubePrimer {
        int stateWrites, biomeWrites;

        @Override
        public void setBlockState(int x, int y, int z, IBlockState state) {
            registered(state);
            super.setBlockState(x, y, z, state);
            require(
                    getBlockState(x, y, z).getBlock() == state.getBlock(),
                    "Primer changed a block on write: " + state);
            stateWrites++;
        }

        @Override
        public void setBiome(int x, int y, int z, Biome biome) {
            registered(biome);
            super.setBiome(x, y, z, biome);
            biomeWrites++;
        }
    }

    private static void validatorControls() {
        CheckedPrimer primer = new CheckedPrimer();
        primer.setBlockState(3, 5, 7, Blocks.STONE.getDefaultState());
        primer.setBiome(1, 2, 3, Biomes.PLAINS);
        require(
                primer.getBlockState(3, 5, 7).getBlock() == Blocks.STONE
                        && primer.getBiome(1, 2, 3) == Biomes.PLAINS
                        && primer.stateWrites == 1
                        && primer.biomeWrites == 1,
                "Emission validator positive controls failed");
        IBlockState unregistered =
                new Block(Material.ROCK)
                        .setRegistryName("riven", "unregistered_classification_fixture")
                        .getDefaultState();
        boolean rejected = false;
        try {
            primer.setBlockState(0, 0, 0, unregistered);
        } catch (AssertionError expected) {
            require(
                    expected.getMessage().startsWith("Unregistered or aliased primer state:"),
                    "Wrong unregistered-state rejection: " + expected);
            rejected = true;
        }
        require(rejected, "Unregistered state bypassed this check's emission validator");
    }

    private record ColumnPosition(int x, int z) {}

    private record CubePosition(long seed, int x, int y, int z) {}

    private static final class Counts {
        long empty, detailed, unknown;

        void add(CubeClass value) {
            switch (value) {
                case EMPTY -> empty++;
                case DETAILED -> detailed++;
                case UNKNOWN -> unknown++;
            }
        }

        JsonObject json() {
            JsonObject result = new JsonObject();
            long total = empty + detailed + unknown;
            result.addProperty("total", total);
            result.addProperty("EMPTY", empty);
            result.addProperty("DETAILED", detailed);
            result.addProperty("UNKNOWN", unknown);
            result.addProperty("emptyShareOfAllCubes", total == 0 ? 0 : (double) empty / total);
            return result;
        }
    }

    private static List<ColumnPosition> nativeWindows() {
        List<ColumnPosition> columns = new ArrayList<>();
        for (int i = 0; i < 120; i++) columns.add(new ColumnPosition(i * 3 - 180, i * 2 - 120));
        for (int z = -1572; z < -1556; z++)
            for (int x = 252; x < 268; x++) columns.add(new ColumnPosition(x, z));
        require(
                columns.size() == 376 && new HashSet<>(columns).size() == 376,
                "Native digest windows changed");
        return columns;
    }

    private static List<ColumnPosition> viewColumns() {
        List<ColumnPosition> columns = new ArrayList<>();
        for (int z = -RADIUS; z <= RADIUS; z++)
            for (int x = -RADIUS; x <= RADIUS; x++) columns.add(new ColumnPosition(x, z));
        return columns;
    }

    private static void snapshot(Path fixture) throws Exception {
        JsonObject defaults = Rules.load("riven-mappings.json", 1).json;
        require(
                defaults.getAsJsonArray("integrations").size() == 0,
                "Packaged mappings require additional real registry inputs");
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schema", 1);
        JsonArray layers = new JsonArray();
        layers.add(defaults.get("layer"));
        snapshot.add("layers", layers);
        snapshot.add("roles", defaults.get("roles"));
        snapshot.add("biomes", defaults.has("biomes") ? defaults.get("biomes") : new JsonObject());
        Path file = fixture.resolve("data/riven/definitions.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, snapshot.toString(), StandardCharsets.UTF_8);
    }

    private static final class SeedCheck {
        final long seed;
        final ClimateBiomeProvider biomes;
        final ICubeGenerator generator;
        final ICubeClassification classifier;
        final JsonObject result;
        final JsonArray phases = new JsonArray(),
                controls = new JsonArray(),
                oracle = new JsonArray();
        final Set<CubePosition> pending = new LinkedHashSet<>(), proved = new HashSet<>();

        /** EMPTY cubes the classifier calls regenerable, and those it keeps saved. */
        final Set<CubePosition> regenerable = new LinkedHashSet<>();

        long regenerableProved, emptyKept;
        final FirstAnimals animals;
        final Set<ColumnPosition> summaries = new HashSet<>();
        long retainCalls, summaryAssertions, classificationQueries, unchangedCacheAssertions;
        int held, maximumHeld;

        SeedCheck(
                long seed,
                ClimateBiomeProvider biomes,
                ICubeGenerator generator,
                JsonObject result) {
            this.seed = seed;
            this.biomes = biomes;
            this.generator = generator;
            this.result = result;
            require(
                    generator instanceof ICubeClassification,
                    "Actual generator lacks classification");
            classifier = (ICubeClassification) generator;
            animals = new FirstAnimals(seed, biomes);
            result.addProperty("seed", seed);
            result.addProperty("layoutScale", 1);
            result.addProperty("generatorClass", generator.getClass().getName());
            result.add("phases", phases);
            result.add("controls", controls);
            result.add("oracleCases", oracle);
        }

        private final class HeldColumns implements AutoCloseable {
            final List<NativeColumns.Reservation> leases = new ArrayList<>(729);
            final Set<ColumnPosition> positions = new HashSet<>();

            NativeColumns.Column add(int x, int z) {
                require(positions.add(new ColumnPosition(x, z)), "Duplicate retained column");
                NativeColumns.Reservation lease = biomes.maps.columns.retain(x, z);
                try {
                    leases.add(lease);
                } catch (RuntimeException | Error failure) {
                    try {
                        lease.close();
                    } catch (RuntimeException | Error cleanup) {
                        if (failure != cleanup) failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
                retainCalls++;
                maximumHeld = Math.max(maximumHeld, ++held);
                require(held <= 729, "Unbounded checker retention");
                NativeColumns.Column column = lease.column();
                verifySummary(x, z, column);
                return column;
            }

            @Override
            public void close() {
                Throwable primary = null;
                for (int i = leases.size() - 1; i >= 0; i--) {
                    try {
                        leases.get(i).close();
                    } catch (RuntimeException | Error failure) {
                        if (primary == null) primary = failure;
                        else if (primary != failure) primary.addSuppressed(failure);
                    } finally {
                        held--;
                    }
                }
                leases.clear();
                if (primary instanceof RuntimeException failure) throw failure;
                if (primary instanceof Error failure) throw failure;
            }
        }

        void verifySummary(int x, int z, NativeColumns.Column column) {
            int maximum = Integer.MIN_VALUE;
            for (int index = 0; index < 256; index++)
                maximum = Math.max(maximum, column.surface(index));
            require(
                    column.maximumTop() == maximum,
                    "Wrong maximumTop at " + seed + ":" + x + "," + z);
            summaryAssertions++;
            summaries.add(new ColumnPosition(x, z));
        }

        void missingNeighbours(NativeColumns.Column owner) {
            for (int z = -1; z <= 1; z++)
                for (int x = -1; x <= 1; x++)
                    require(
                            biomes.maps.columns.cachedColumn(x, z)
                                    == (x == 0 && z == 0 ? owner : null),
                            "A non-loading query changed cached columns at " + x + "," + z);
        }

        NativeColumns.Column[] cached(int x, int z) {
            NativeColumns.Column[] columns = new NativeColumns.Column[9];
            int i = 0;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++)
                    columns[i++] = biomes.maps.columns.cachedColumn(x + dx, z + dz);
            return columns;
        }

        void unchanged(int x, int z, NativeColumns.Column[] before) {
            NativeColumns.Column[] after = cached(x, z);
            for (int i = 0; i < before.length; i++)
                require(
                        before[i] == after[i],
                        "A summary query changed cached columns at " + seed + ":" + x + "," + z);
            unchangedCacheAssertions++;
        }

        SurfaceBand band(int x, int z) {
            NativeColumns.Column[] before = cached(x, z);
            SurfaceBand result = classifier.surfaceBand(x, z);
            unchanged(x, z, before);
            return result;
        }

        CubeClass query(int x, int y, int z) {
            classificationQueries++;
            NativeColumns.Column[] before = cached(x, z);
            CubeClass value = classifier.cubeClass(x, y, z);
            boolean again = classifier.regenerable(x, y, z);
            unchanged(x, z, before);
            require(value != null, "Null cube classification");
            require(!again || value == CubeClass.EMPTY, "A non-EMPTY cube was called regenerable");
            if (value == CubeClass.EMPTY) {
                pending.add(new CubePosition(seed, x, y, z));
                if (again) regenerable.add(new CubePosition(seed, x, y, z));
                else emptyKept++;
            }
            return value;
        }

        void control(String name, int x, int y, int z, CubeClass expected) {
            CubeClass actual = query(x, y, z);
            require(actual == expected, name + ": expected " + expected + ", got " + actual);
            JsonObject control = new JsonObject();
            control.addProperty("name", name);
            control.addProperty("cubeX", x);
            control.addProperty("cubeY", y);
            control.addProperty("cubeZ", z);
            control.addProperty("classification", actual.name());
            controls.add(control);
        }

        void phase(String name, List<ColumnPosition> columns, int minimumY, int maximumY) {
            Counts counts = new Counts();
            JsonObject phase = new JsonObject();
            phase.addProperty("name", name);
            phase.addProperty("retainedColumns", held);
            phase.addProperty("minimumCubeY", minimumY);
            phase.addProperty("maximumCubeY", maximumY);
            JsonArray positions = new JsonArray();
            for (ColumnPosition column : columns) {
                StringBuilder classes = new StringBuilder();
                for (int y = minimumY; y <= maximumY; y++) {
                    CubeClass value = query(column.x, y, column.z);
                    counts.add(value);
                    classes.append(
                            switch (value) {
                                case EMPTY -> 'E';
                                case DETAILED -> 'D';
                                case UNKNOWN -> 'U';
                            });
                }
                JsonObject position = new JsonObject();
                position.addProperty("columnX", column.x);
                position.addProperty("columnZ", column.z);
                position.addProperty("classesByIncreasingCubeY", classes.toString());
                positions.add(position);
            }
            phase.add("counts", counts.json());
            phase.add("columns", positions);
            phases.add(phase);
        }

        void box(String name, int centreY) {
            phase(name, viewColumns(), centreY - RADIUS, centreY + RADIUS);
        }

        void provePending() {
            ByteBuffer cells = ByteBuffer.allocate(4096 * Integer.BYTES + 1 + 64 * Integer.BYTES);
            for (CubePosition position : pending) {
                if (proved.contains(position)) continue;
                JsonObject proof = new JsonObject();
                proof.addProperty("cubeX", position.x);
                proof.addProperty("cubeY", position.y);
                proof.addProperty("cubeZ", position.z);
                proof.addProperty("completed", false);
                oracle.add(proof);
                CheckedPrimer primer = new CheckedPrimer();
                CubePrimer returned =
                        generator.generateCube(position.x, position.y, position.z, primer);
                require(returned == primer, "Actual generator bypassed the checked primer");
                cells.clear();
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            IBlockState state = returned.getBlockState(x, y, z);
                            cells.putInt(registered(state));
                            require(
                                    state.getBlock() == Blocks.AIR,
                                    "EMPTY generated non-air at "
                                            + position
                                            + " local "
                                            + x
                                            + ","
                                            + y
                                            + ","
                                            + z);
                        }
                require(
                        !returned.hasBiomes() && primer.biomeWrites == 0,
                        "EMPTY generated biome entries at " + position);
                cells.put((byte) 0);
                for (int y = 0; y < 4; y++)
                    for (int z = 0; z < 4; z++)
                        for (int x = 0; x < 4; x++) {
                            require(
                                    returned.getBiome(x, y, z) == null,
                                    "EMPTY retained a biome cell at " + position);
                            cells.putInt(-1);
                        }
                require(!cells.hasRemaining(), "Incomplete actual primer scan");
                proof.addProperty("blockCells", 4096);
                proof.addProperty("biomeCells", 64);
                proof.addProperty("stateWrites", primer.stateWrites);
                proof.addProperty("biomeWrites", primer.biomeWrites);
                proof.addProperty("sha256", Rules.sha256(cells.array()));
                proof.addProperty("completed", true);
                proved.add(position);
            }
            pending.clear();
            // Population of a regenerable cube must create nothing: no animal stands in it.
            for (CubePosition position : regenerable) {
                require(
                        animals.plan(position.x, position.z, position.y * 16).isEmpty(),
                        "A regenerable cube plans animals at " + position);
                regenerableProved++;
            }
            regenerable.clear();
        }

        void surfaceBand(NativeColumns.Column column) {
            SurfaceBand band = band(0, 0);
            JsonObject control = new JsonObject();
            control.addProperty("name", "warm-surface-band");
            control.addProperty("available", band != null);
            if (band == null) {
                controls.add(control);
                return;
            }
            int checked = 0, clipped = 0;
            for (int index = 0; index < 256; index++) {
                int surface = column.surface(index);
                if (surface == Integer.MIN_VALUE) continue;
                int y = Math.floorDiv(surface - 1, 16);
                long bottom = (long) y * 16;
                if (bottom < HEIGHTS.minimumY
                        || bottom >= HEIGHTS.maximumYExclusive
                        || bottom > biomes.maps.terrain.maximumSurfaceY) {
                    clipped++;
                    continue;
                }
                require(
                        y >= band.minimumCubeY() && y <= band.maximumCubeY(),
                        "Warm band excludes a native surface at index " + index);
                checked++;
            }
            control.addProperty("minimumCubeY", band.minimumCubeY());
            control.addProperty("maximumCubeY", band.maximumCubeY());
            control.addProperty("nativeSurfacesChecked", checked);
            control.addProperty("nativeSurfacesClippedByOriginalGenerator", clipped);
            control.addProperty("decorationCoverageProved", false);
            controls.add(control);
        }

        void run() {
            int minimumY = Math.floorDiv(HEIGHTS.minimumY, 16);
            int maximumY = Math.floorDiv(HEIGHTS.maximumYExclusive - 1, 16);
            missingNeighbours(null);
            control("cold-owner-missing", 0, minimumY, 0, CubeClass.UNKNOWN);
            require(band(0, 0) == null, "Cold surface band must be absent");
            missingNeighbours(null);
            // This explicit build does not publish a native column. Only the regional plans may
            // warm.
            NativeColumns.Column anchor = biomes.maps.columns.build(0, 0);
            verifySummary(0, 0, anchor);
            missingNeighbours(null);
            require(anchor.surface(0) != Integer.MIN_VALUE, "Ground view anchor has no surface");
            int groundY = Math.floorDiv(anchor.surface(0) - 1, 16);
            result.addProperty("groundCentreCubeY", groundY);
            result.addProperty(
                    "groundAnchor", "detached build(0,0); surface(0)-1; not memo-published");
            result.addProperty("detachedAnchorBuildCalls", 1);
            result.addProperty(
                    "coldMeaning", "native column memo absent; regional plans may be prepared");
            phase("column-memo-cold-native-windows", nativeWindows(), minimumY, maximumY);
            box("column-memo-cold-ground-view", groundY);
            box("column-memo-cold-y320-view", 20);
            missingNeighbours(null);
            try (HeldColumns owner = new HeldColumns()) {
                NativeColumns.Column column = owner.add(0, 0);
                missingNeighbours(column);
                int probe = Math.floorDiv(Math.max(HEIGHTS.minimumY, column.minimumGround()), 16);
                long bottom = (long) probe * 16;
                require(
                        bottom + 15 >= column.minimumGround()
                                && bottom + 15 >= HEIGHTS.minimumY
                                && bottom < HEIGHTS.maximumYExclusive
                                && bottom <= biomes.maps.terrain.maximumSurfaceY,
                        "Missing-neighbour control would take an unrelated early branch");
                control("owner-held-neighbours-missing", 0, probe, 0, CubeClass.UNKNOWN);
                require(band(0, 0) == null, "Missing neighbours produced a band");
                box("owner-only-held-ground-view", groundY);
                box("owner-only-held-y320-view", 20);
                missingNeighbours(column);
                for (int z = -1; z <= 1; z++)
                    for (int x = -1; x <= 1; x++) if (x != 0 || z != 0) owner.add(x, z);
                require(
                        query(0, probe, 0) != CubeClass.UNKNOWN,
                        "Loaded definitions cannot provide the warm classification positive control");
                surfaceBand(column);
            }
            control("realm-not-inferred-empty", 0, minimumY - 1, 0, CubeClass.DETAILED);
            control(
                    "extreme-negative-y-not-inferred-empty",
                    0,
                    Integer.MIN_VALUE,
                    0,
                    CubeClass.DETAILED);
            control("above-allocation", 0, maximumY + 1, 0, CubeClass.EMPTY);
            control("east-outside-world", 1875000, 0, 0, CubeClass.EMPTY);
            control("west-outside-world", -1875001, 0, 0, CubeClass.EMPTY);
            control(
                    "extreme-coordinates",
                    Integer.MAX_VALUE,
                    Integer.MIN_VALUE,
                    Integer.MIN_VALUE,
                    CubeClass.EMPTY);
            provePending();
            for (ColumnPosition position : nativeWindows()) {
                try (HeldColumns columns = new HeldColumns()) {
                    for (int z = -1; z <= 1; z++)
                        for (int x = -1; x <= 1; x++) columns.add(position.x + x, position.z + z);
                    phase(
                            "neighbourhood-held-native-window",
                            List.of(position),
                            minimumY,
                            maximumY);
                    provePending();
                }
            }
            try (HeldColumns columns = new HeldColumns()) {
                for (int z = -RADIUS - 1; z <= RADIUS + 1; z++)
                    for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) columns.add(x, z);
                box("halo-held-ground-view", groundY);
                box("halo-held-y320-view", 20);
                provePending();
            }
            require(
                    held == 0 && pending.isEmpty(),
                    "Checker retained columns or unproved EMPTY cases");
            long expectedQueries = 2L * 376 * (maximumY - minimumY + 1) + 6L * 15625 + 9;
            require(classificationQueries == expectedQueries, "Incomplete classification workload");
            require(
                    unchangedCacheAssertions == classificationQueries + 3,
                    "Incomplete non-loading summary controls");
            require(
                    retainCalls == 9L + 376L * 9 + 729 && maximumHeld == 729,
                    "Incomplete or unbounded retention workload");
            result.addProperty("classificationQueries", classificationQueries);
            result.addProperty("unchangedNeighbourhoodCacheAssertions", unchangedCacheAssertions);
            result.addProperty("columnRetainCalls", retainCalls);
            result.addProperty("maximumSimultaneousRetainedColumns", maximumHeld);
            result.addProperty("maximumTopAssertions", summaryAssertions);
            result.addProperty("uniqueColumnsWithMaximumTopChecked", summaries.size());
            result.addProperty("actualGenerateCubeCalls", proved.size());
            require(
                    regenerableProved > 0 && emptyKept > 0,
                    "Regenerable classification was not exercised both ways");
            result.addProperty("regenerableCubesProved", regenerableProved);
            result.addProperty("emptyCubesKeptSaved", emptyKept);
            result.addProperty("emptyViolations", 0);
        }
    }

    private static void runSeed(Path parent, long seed, RivenWorldType worldType, JsonObject result)
            throws Exception {
        Path fixture = Files.createTempDirectory(parent, "classification-");
        require(fixture.toRealPath().startsWith(parent.toRealPath()), "Fixture escaped its parent");
        Throwable primary = null;
        try {
            snapshot(fixture);
            try (ForkJoinPool workers = new ForkJoinPool(4)) {
                ClimateBiomeProvider biomes =
                        new ClimateBiomeProvider(seed, new WorldScale(1), workers, null);
                World world = CubePrimerFixture.create(fixture, seed, biomes);
                SeedCheck check =
                        new SeedCheck(seed, biomes, worldType.createCubeGenerator(world), result);
                check.result.addProperty(
                        "snapshotSha256",
                        Rules.sha256(
                                Files.readAllBytes(
                                        fixture.resolve("data/riven/definitions.json"))));
                check.run();
            }
        } catch (Exception | Error failure) {
            primary = failure;
            result.addProperty("failure", failure.toString());
            throw failure;
        } finally {
            try {
                try (var files = Files.walk(fixture)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                        Files.delete(file);
                }
            } catch (Exception | Error cleanup) {
                if (primary == null) throw cleanup;
                if (cleanup != primary) primary.addSuppressed(cleanup);
            }
        }
    }

    private static JsonArray seeds(long[] seeds) {
        JsonArray result = new JsonArray();
        for (long seed : seeds) result.add(seed);
        return result;
    }

    public static void main(String[] args) throws Exception {
        require(Runtime.version().feature() == 26, "Diagnostics must run on Java 26");
        if (args.length == 0)
            throw new IllegalArgumentException("Give a new receipt path and optional seeds");
        long[] selected =
                args.length == 1
                        ? SEEDS.clone()
                        : Arrays.stream(args).skip(1).mapToLong(Long::parseLong).toArray();
        Set<Long> unique = new HashSet<>();
        for (long seed : selected)
            require(
                    Arrays.stream(SEEDS).anyMatch(value -> value == seed) && unique.add(seed),
                    "Select each intended seed at most once: " + seed);
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Path parent = output.getParent();
        Files.createDirectories(parent);
        require(!Files.exists(output), "Receipt already exists: " + output);
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schema", 1);
        receipt.addProperty("javaRuntime", Runtime.version().toString());
        receipt.addProperty("javaHome", System.getProperty("java.home"));
        receipt.add("intendedSeeds", seeds(SEEDS));
        receipt.add("selectedSeeds", seeds(selected));
        JsonArray completed = new JsonArray(), results = new JsonArray();
        receipt.add("completedSeeds", completed);
        receipt.add("results", results);
        receipt.addProperty(
                "classificationEncoding", "E=EMPTY,D=DETAILED,U=UNKNOWN; increasing cube Y");
        receipt.addProperty(
                "digestEncoding",
                "y,z,x; big-endian int state IDs; hasBiomes byte; 64 int biome IDs or -1");
        receipt.addProperty("nativeWindowColumnsPerSeed", 376);
        receipt.addProperty("nativeWindowCubeYMinimum", Math.floorDiv(HEIGHTS.minimumY, 16));
        receipt.addProperty(
                "nativeWindowCubeYMaximum", Math.floorDiv(HEIGHTS.maximumYExclusive - 1, 16));
        receipt.addProperty("viewRadiusCubes", RADIUS);
        receipt.addProperty("cubesPerViewBox", 15625);
        receipt.addProperty("maximumViewHaloRetentions", 729);
        receipt.addProperty("workerParallelism", 4);
        receipt.addProperty("nativeOrNewPinQualificationClaim", false);
        Throwable primary = null;
        boolean success = false;
        try {
            CubePrimerBootstrapCheck.main(new String[] {parent.toString(), "registries"});
            validatorControls();
            receipt.addProperty("ownValidatorControlsPassed", true);
            JsonObject definitions = new JsonObject();
            for (Rules rules : Definitions.loadAll())
                definitions.addProperty(rules.resource, rules.fingerprint);
            receipt.add("definitions", definitions);
            Path mapping = Path.of("build/diagnostics/cubature-mapping.json");
            byte[] mappingBytes = Files.readAllBytes(mapping);
            JsonObject provenance =
                    JsonParser.parseString(new String(mappingBytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            Path actualApi =
                    Path.of(
                            CubePrimer.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
            for (Class<?> api : List.of(ICubeGenerator.class, ICubeClassification.class))
                require(
                        Files.isSameFile(
                                actualApi,
                                Path.of(
                                        api.getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .toURI())),
                        "Generator API classes came from different artifacts");
            String actualHash = Rules.sha256(Files.readAllBytes(actualApi));
            require(
                    actualHash.equals(
                            provenance.getAsJsonObject("mapped").get("sha256").getAsString()),
                    "Mapping receipt does not describe the actual CubePrimer artifact");
            receipt.addProperty("mappingReceiptSha256", Rules.sha256(mappingBytes));
            receipt.addProperty("actualApiSha256", actualHash);
            receipt.add("runtimeMapping", provenance);
            RivenWorldType worldType = new RivenWorldType();
            for (long seed : selected) {
                receipt.addProperty("activeSeed", seed);
                JsonObject result = new JsonObject();
                result.addProperty("seed", seed);
                result.addProperty("completed", false);
                results.add(result);
                runSeed(parent, seed, worldType, result);
                result.addProperty("completed", true);
                completed.add(seed);
                System.out.println("Completed cube classification seed " + seed);
            }
            receipt.remove("activeSeed");
            success = true;
        } catch (Exception | Error failure) {
            primary = failure;
            receipt.addProperty("failure", failure.toString());
            throw failure;
        } finally {
            receipt.addProperty("selectedSeedsPassed", success);
            receipt.addProperty(
                    "fullIntendedProofPassed", success && selected.length == SEEDS.length);
            try {
                Files.writeString(
                        output,
                        receipt.toString(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
            } catch (Exception | Error failure) {
                if (primary == null) throw failure;
                if (failure != primary) primary.addSuppressed(failure);
            }
        }
        System.out.println("Cube classification receipt: " + output);
    }
}
