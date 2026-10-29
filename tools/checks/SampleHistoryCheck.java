package cc.orthrus.riven.worldgen;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * A reused TerrainMap.Sample reports exactly what a fresh one does, whatever it sampled before.
 * Usage: SampleHistoryCheck [seed ...] (default 8675309). Every stage result in the sample must be
 * registered with exactly one phase. Columns along a diagonal crossing land and coast are then
 * sampled the native way (sample, then carve) into fresh samples, visited again in reverse and in
 * a seeded shuffle through one reused sample, and compared field by field, reporting every field
 * that differs.
 */
public final class SampleHistoryCheck {
    private static final int COLUMNS = 320;

    /** Arrays filled from index 0 up to a count field; slots past the count are never read. */
    private static final Map<String, String> BOUNDED = new HashMap<>();

    /** Working memory, written before every read; each entry says whose. */
    private static final Set<String> SCRATCH = new HashSet<>();

    static {
        bounded("worldgen.CaveMap$Voids", "pools", "poolLo", "poolHi");
        bounded("worldgen.CaveMap$Voids", "unstables", "unstableLo", "unstableHi");
        bounded("worldgen.CaveMap$Voids", "count", "lo", "hi");
        bounded("worldgen.CaveMap$Voids", "linings", "liningLo", "liningHi", "liningBlock");
        bounded("worldgen.CaveMap$Voids", "decorations", "decorLo", "decorHi", "decorBlock");
        bounded("worldgen.FloodMap$Column", "dams", "damDistance", "damLo", "damHi");
        bounded("worldgen.RockyTerrain$Result", "cutCount", "cuts");
        // Erosion's per-octave orientation (phacelle) and the passes' per-kind scores and shapes.
        scratch("worldgen.ErosionFilter$Result", "cosine", "sine");
        scratch("worldgen.RegionalTerrainPasses$Result", "admission", "coarse", "fine");
        // Anchor intent samples, lottery weights and kernel outputs each apply fills before use.
        scratch("worldgen.SurfaceCarvers$Result", "base", "weights");
        // A carver's segment tree: each node is written by its parent before it is read.
        scratch("worldgen.SurfaceCarvers$Result", "ax", "az", "bx", "bz", "width");
        scratch("worldgen.RegionalLandmarks$Result", "anchorBase", "weights");
        scratch("worldgen.DrylandTerrain$Result", "sample", "substrate");
        scratch("worldgen.PlateauTerrain$Result", "sample");
        scratch("worldgen.MountainTerrain$Result", "sample", "crest");
        scratch("worldgen.LowlandTerrain$Result", "sample");
        scratch("worldgen.SedimentTerrain$Result", "geometry");
        // Read only through plan, which is set after the kernel fills it and cleared with it.
        scratch("worldgen.RockyTerrain$Result", "column");
        // Kernel work buffers and noise samples behind the published base facts; marine columns
        // clear the facts and never read these.
        scratch("worldgen.ContinentalMap$Sample", "work", "probe");
        scratch("worldgen.BaseTerrainMap$Sample", "body", "detail", "wide");
        scratch("worldgen.BaseElevationMap$Sample", "outline", "regional", "upliftSample");
        // Lake, river and coast kernel outputs, filled where each body is evaluated.
        scratch("worldgen.Hydro$Sample", "lakeGeometry", "riverGeometry", "coastGeometry");
        // Climate forcing's structure read and upwind samples, filled before each use.
        scratch("worldgen.ClimateForcingMap$Sample", "structure", "upwindObstruction");
    }

    private static void bounded(String type, String count, String... arrays) {
        for (String array : arrays) BOUNDED.put(type + "#" + array, type + "#" + count);
    }

    private static void scratch(String type, String... fields) {
        for (String field : fields) SCRATCH.add(type + "#" + field);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final String ROOT = "cc.orthrus.riven.";

    /** A field's table key: its class below cc.orthrus.riven, then the field name. */
    private static String key(Class<?> type, String field) {
        return type.getName().substring(ROOT.length()) + "#" + field;
    }

    /** Every exemption still names a field, so a rename cannot silently widen the comparison. */
    private static void exemptions() throws ClassNotFoundException {
        Set<String> keys = new HashSet<>(SCRATCH);
        keys.addAll(BOUNDED.keySet());
        for (String value : BOUNDED.values()) keys.add(value);
        for (String key : keys) {
            int split = key.indexOf('#');
            Class<?> type = Class.forName(ROOT + key.substring(0, split));
            try {
                type.getDeclaredField(key.substring(split + 1));
            } catch (NoSuchFieldException missing) {
                throw new AssertionError("Exemption names no field: " + key);
            }
        }
    }

    /** Every StageResult field of the sample is registered with exactly one phase. */
    private static int registrations() throws ReflectiveOperationException {
        TerrainMap.Sample sample = new TerrainMap.Sample();
        int results = 0;
        for (Class<?> type = sample.getClass(); type != Object.class; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !StageResult.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                int count = sample.registrations((StageResult) field.get(sample));
                require(
                        count == 1,
                        "Stage result " + field.getName() + " is registered " + count + " times");
                results++;
            }
        require(results > 0, "No stage results found");
        return results;
    }

    private static void sample(GenerationMaps maps, int[] column, TerrainMap.Sample out) {
        maps.terrain.sample(column[0], column[1], out);
        maps.terrain.caves.carve(column[0], column[1], out);
    }

    /** Records in found the path of every field where a and b differ (array indices elided). */
    private static void compare(
            Object a, Object b, String path, Map<Object, Object> seen, Set<String> found)
            throws ReflectiveOperationException {
        if (a == b) return;
        if (a == null || b == null || a.getClass() != b.getClass()) {
            found.add(path);
            return;
        }
        Class<?> type = a.getClass();
        if (type.isArray()) {
            compareArrays(a, b, Array.getLength(a), Array.getLength(b), path, seen, found);
            return;
        }
        // Boxed primitives, strings, enums and other library values compare by value; NaN equals
        // NaN through Double.equals.
        if (type.isEnum() || !type.getName().startsWith(ROOT)) {
            if (!a.equals(b)) found.add(path);
            return;
        }
        if (seen.put(a, b) != null) return;
        for (Class<?> k = type; k != Object.class; k = k.getSuperclass())
            for (Field field : k.getDeclaredFields()) {
                String key = key(k, field.getName());
                // The registries alias the named stage results.
                if (Modifier.isStatic(field.getModifiers())
                        || SCRATCH.contains(key)
                        || (k == TerrainMap.Sample.class && field.getType() == List.class))
                    continue;
                field.setAccessible(true);
                String inner = path + "." + field.getName();
                String bound = BOUNDED.get(key);
                if (bound == null) {
                    compare(field.get(a), field.get(b), inner, seen, found);
                    continue;
                }
                Field count = k.getDeclaredField(bound.substring(bound.indexOf('#') + 1));
                count.setAccessible(true);
                compareArrays(
                        field.get(a),
                        field.get(b),
                        count.getInt(a),
                        count.getInt(b),
                        inner,
                        seen,
                        found);
            }
    }

    private static void compareArrays(
            Object a,
            Object b,
            int lengthA,
            int lengthB,
            String path,
            Map<Object, Object> seen,
            Set<String> found)
            throws ReflectiveOperationException {
        if (lengthA != lengthB) {
            found.add(path + ".length");
            return;
        }
        for (int i = 0; i < lengthA; i++)
            compare(Array.get(a, i), Array.get(b, i), path + "[]", seen, found);
    }

    private static int[] history(long seed) throws ReflectiveOperationException {
        GenerationMaps maps = new GenerationMaps(seed);
        int[][] columns = new int[COLUMNS][];
        TerrainMap.Sample[] fresh = new TerrainMap.Sample[COLUMNS];
        int marine = 0, shaped = 0;
        for (int i = 0; i < COLUMNS; i++) {
            // Odd offsets move each column to a different place in its cells and lattices.
            columns[i] = new int[] {-3840 + i * 24 + i % 7, -2560 + i * 16 + i % 5};
            fresh[i] = new TerrainMap.Sample();
            sample(maps, columns[i], fresh[i]);
            if (fresh[i].ocean.marine) marine++;
            else if (fresh[i].height != fresh[i].passes.height) shaped++;
        }
        require(
                marine > 0 && marine < COLUMNS && shaped > 0,
                "Seed "
                        + seed
                        + " needs marine, land and shaped columns: "
                        + marine
                        + "/"
                        + shaped);
        List<Integer> order = new ArrayList<>();
        for (int i = COLUMNS - 1; i >= 0; i--) order.add(i);
        List<Integer> shuffled = new ArrayList<>(order);
        Collections.shuffle(shuffled, new Random(seed));
        order.addAll(shuffled);
        TerrainMap.Sample reused = new TerrainMap.Sample();
        Map<String, String> differences = new TreeMap<>();
        int transitions = 0;
        boolean wasMarine = false;
        for (int step = 0; step < order.size(); step++) {
            int i = order.get(step);
            sample(maps, columns[i], reused);
            Set<String> found = new HashSet<>();
            compare(fresh[i], reused, "sample", new IdentityHashMap<Object, Object>(), found);
            for (String path : found)
                if (!differences.containsKey(path))
                    differences.put(path, columns[i][0] + "," + columns[i][1] + " visit " + step);
            if (step > 0 && reused.ocean.marine != wasMarine) transitions++;
            wasMarine = reused.ocean.marine;
        }
        if (!differences.isEmpty()) {
            StringBuilder report = new StringBuilder("Seed " + seed + ": a reused sample differs");
            for (Map.Entry<String, String> e : differences.entrySet())
                report.append("\n  ")
                        .append(e.getKey())
                        .append(" (first at ")
                        .append(e.getValue())
                        .append(')');
            throw new AssertionError(report.toString());
        }
        require(transitions > 0, "Seed " + seed + " never crossed between land and sea");
        return new int[] {marine, shaped, transitions, order.size()};
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        exemptions();
        int results = registrations();
        System.out.println("stage results registered once: " + results);
        String[] seeds = args.length == 0 ? new String[] {"8675309"} : args;
        for (String seed : seeds) {
            int[] counts = history(Long.parseLong(seed));
            System.out.println(
                    "seed "
                            + seed
                            + ": "
                            + counts[3]
                            + " reused visits match fresh samples ("
                            + COLUMNS
                            + " columns, "
                            + counts[0]
                            + " marine, "
                            + counts[1]
                            + " shaped after the passes, "
                            + counts[2]
                            + " land/sea crossings)");
        }
    }
}
