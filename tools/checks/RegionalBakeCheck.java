package cc.orthrus.riven.worldgen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;

/**
 * Region preparation with a regional store bakes networks to disk: it writes each candidate
 * network, and the cell's own plan and coast forms that follow read networks from the store, so
 * no network is built through the memory memo and every held one was a store hit; repeating the
 * preparation writes nothing; and a baked network reads back with the same encoded bytes as a
 * fresh build. Usage: {@code
 * RegionalBakeCheck <output parent> <seed> <scale> <region x> <region z>}.
 */
public final class RegionalBakeCheck {
    private RegionalBakeCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    private static byte[] encoded(Object network) throws Exception {
        Field field = Drainage.class.getDeclaredField("NETWORK_CODEC");
        field.setAccessible(true);
        RegionalStore.Codec<Object> codec = (RegionalStore.Codec<Object>) field.get(null);
        RegionalData.Writer out = new RegionalData.Writer();
        codec.write(out, network);
        return MessageDigest.getInstance("SHA-256").digest(out.finish());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5)
            throw new IllegalArgumentException(
                    "Usage: RegionalBakeCheck <output parent> <seed> <scale> <region x> <region z>");
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path cache = Files.createTempDirectory(parent, "regional-bake-");
        long seed = Long.parseLong(args[1]);
        WorldScale world = new WorldScale(Double.parseDouble(args[2]));
        int rx = Integer.parseInt(args[3]), rz = Integer.parseInt(args[4]);
        ForkJoinPool pool = new ForkJoinPool(4);
        RegionalStore store = new RegionalStore(cache, RegionalIdentity.capture(seed, world));
        GenerationMaps baked =
                new GenerationMaps(seed, TerrainHeightReference.DEFAULT, world, pool, store);
        Drainage d = baked.terrain.hydro.drainage;
        require(d.bakes(), "A bound store must make preparation bake");
        RegionalStore.Statistics before = store.statistics();
        require(baked.terrain.prepareRegion(rx, rz), "Baking preparation declined");
        RegionalStore.Statistics after = store.statistics();
        long written = after.writes() - before.writes();
        require(written > 0 && after.failedWrites() == before.failedWrites(), "Nothing baked");
        Field memoField = Drainage.class.getDeclaredField("memo");
        memoField.setAccessible(true);
        CellMemo<?> memo = (CellMemo<?>) memoField.get(d);
        long[] cells =
                d.continents(
                        (rx + .5) * baked.terrain.hydro.cellBlocks,
                        (rz + .5) * baked.terrain.hydro.cellBlocks);
        int held = 0;
        for (int k = 0; k < cells.length; k += 2)
            if (memo.holds((int) cells[k], (int) cells[k + 1])) held++;
        long misses = after.misses() - before.misses(), hits = after.hits() - before.hits();
        require(misses == 0, "Preparation built " + misses + " networks through the memo");
        require(held <= hits, held + " held networks but only " + hits + " store reads");
        require(baked.terrain.prepareRegion(rx, rz), "Repeated preparation declined");
        require(
                store.statistics().writes() == after.writes(),
                "Repeated preparation rewrote networks");

        // Demand reads the baked networks back; a store-free map builds them fresh.
        GenerationMaps fresh =
                new GenerationMaps(seed, TerrainHeightReference.DEFAULT, world, pool, null);
        Method network = Drainage.class.getDeclaredMethod("network", long.class, long.class);
        network.setAccessible(true);
        int compared = 0, inMemory = 0;
        long hitsBefore = store.statistics().hits();
        for (int k = 0; k < cells.length; k += 2) {
            if (!store.contains("drainage", cells[k], cells[k + 1], codec())) continue;
            if (memo.holds((int) cells[k], (int) cells[k + 1])) inMemory++;
            byte[] loaded = encoded(network.invoke(d, cells[k], cells[k + 1]));
            byte[] built =
                    encoded(network.invoke(fresh.terrain.hydro.drainage, cells[k], cells[k + 1]));
            require(
                    Arrays.equals(loaded, built),
                    "Baked network differs at " + cells[k] + "," + cells[k + 1]);
            compared++;
        }
        require(compared > 0, "No baked network to compare");
        require(
                store.statistics().hits() - hitsBefore == compared - inMemory,
                "Demand did not read the bake");
        System.out.printf(
                "Regional bake: seed %d scale %s region %d,%d: %d networks written; nothing built through"
                        + " the memo, %d read back from the bake, none rewritten; %d identical to a fresh build%n",
                seed, args[2], rx, rz, written, held, compared);
        store.close();
        System.exit(0);
    }

    @SuppressWarnings("unchecked")
    private static RegionalStore.Codec<Object> codec() throws Exception {
        Field field = Drainage.class.getDeclaredField("NETWORK_CODEC");
        field.setAccessible(true);
        return (RegionalStore.Codec<Object>) field.get(null);
    }
}
