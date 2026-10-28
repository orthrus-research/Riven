package cc.orthrus.riven.worldgen;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Bounded standalone full-terrain sampling costs, not native generation or throughput evidence. */
public final class SubstrateColumnCostCheck {
    private static volatile long sink;
    private static volatile TerrainMap.Sample retainedScratch;

    private SubstrateColumnCostCheck() {}

    @SuppressWarnings("deprecation") // Thread.getId is shared with the Java-8 Forge fallback.
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException(
                    "<fixed-witnesses.csv> <new-costs.csv> [warmup-passes]");
        int warmupPasses = args.length == 3 ? Integer.parseInt(args[2]) : 3;
        if (warmupPasses < 3 || warmupPasses > 4096)
            throw new IllegalArgumentException("Warmup must be 3..4096 bounded cohort traversals");
        List<int[]> points = new ArrayList<>();
        for (String row : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if (row.startsWith("seed,")) continue;
            String[] fields = row.split(",");
            if (fields.length != 6 || Long.parseLong(fields[0]) != 8675309)
                throw new IllegalArgumentException("Invalid witness");
            points.add(new int[] {Integer.parseInt(fields[4]), Integer.parseInt(fields[5])});
        }
        if (points.isEmpty() || points.size() > 22)
            throw new IllegalArgumentException("Bounded cohort required");
        ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocation.isThreadAllocatedMemorySupported())
            throw new IllegalStateException("Allocation counter unavailable");
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        long allocated = allocation.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        GenerationMaps maps = new GenerationMaps(8675309);
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        long constructionNanos = System.nanoTime() - started;
        long constructionBytes = allocation.getThreadAllocatedBytes(thread) - allocated;
        List<String> rows = new ArrayList<>();
        rows.add("phase,pass,index,x,z,nanos,allocatedBytes,heightBits,drylandWeightBits");
        int firstPass = -warmupPasses - 1;
        for (int pass = firstPass; pass < 5; pass++)
            for (int i = 0; i < points.size(); i++) {
                int[] point = points.get(i);
                allocated = allocation.getThreadAllocatedBytes(thread);
                started = System.nanoTime();
                maps.terrain.sample(point[0], point[1], scratch);
                boolean bare = maps.surfaces.bareStone(scratch);
                SurfaceRules.CoverReason reason = maps.surfaces.reason(scratch);
                long nanos = System.nanoTime() - started;
                long bytes = allocation.getThreadAllocatedBytes(thread) - allocated;
                long height = Double.doubleToLongBits(scratch.height);
                sink ^= height ^ (bare ? 1 : 0) ^ reason.ordinal();
                if (pass == firstPass || pass >= 0)
                    rows.add(
                            (pass == firstPass ? "cold" : "warm")
                                    + ","
                                    + pass
                                    + ","
                                    + i
                                    + ","
                                    + point[0]
                                    + ","
                                    + point[1]
                                    + ","
                                    + nanos
                                    + ","
                                    + bytes
                                    + ","
                                    + Long.toHexString(height)
                                    + ","
                                    + Long.toHexString(
                                            Double.doubleToLongBits(scratch.drylands.weight)));
            }
        Files.write(
                Paths.get(args[1]), rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        allocated = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 128; i++) retainedScratch = new TerrainMap.Sample();
        long scratchBytes = (allocation.getThreadAllocatedBytes(thread) - allocated) / 128;
        System.out.println(
                "constructionNanos="
                        + constructionNanos
                        + " constructionBytes="
                        + constructionBytes
                        + " scratchBytes="
                        + scratchBytes
                        + " warmupPasses="
                        + warmupPasses
                        + " rows="
                        + (rows.size() - 1)
                        + " sink="
                        + sink);
        System.out.println(
                "Cold means first traversal after construction; warm follows the configured warmup traversals. Standalone terrain plus cover only; no cube/lighting/DH/frame/TPS qualification.");
    }
}
