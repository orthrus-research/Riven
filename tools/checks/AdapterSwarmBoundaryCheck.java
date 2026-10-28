import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.Hydro;
import cc.orthrus.riven.worldgen.RockyTerrain;
import cc.orthrus.riven.worldgen.Sites;
import cc.orthrus.riven.worldgen.TerrainMap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Bounded public-API differential; scalar observations do not certify actual voxel topology. */
public final class AdapterSwarmBoundaryCheck {
    private static final long SEED = 8675309;
    private static final int OWNER_CAP = 4, LAST_CELL = 128;
    private static final String MANIFEST = "seed,role,cellX,cellZ,x,z";
    private static final String HEADER =
            MANIFEST
                    + ",stage,heightBits,rockyParentBits,landFacts,waterFacts,hills,cuts,rocky,sediment,volume,cutBoundaries,editObservations,cover,bareStone";

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double n) {
        return Long.toHexString(Double.doubleToRawLongBits(n));
    }

    private static final class Point {
        final String role;
        final int cx, cz, x, z;

        Point(String role, int cx, int cz, int x, int z) {
            this.role = role;
            this.cx = cx;
            this.cz = cz;
            this.x = x;
            this.z = z;
        }

        String row() {
            return SEED + "," + role + "," + cx + "," + cz + "," + x + "," + z;
        }
    }

    private static String scalarFields(Object value) throws Exception {
        Field[] fields = value.getClass().getFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        StringBuilder out = new StringBuilder();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (value instanceof Hydro.Sample
                    && (field.getName().equals("riverShape")
                            || field.getName().equals("coastShape"))) continue;
            require(
                    type.isPrimitive() || type.isEnum() || type == String.class,
                    "Unexpected public observation type: " + field);
            if (out.length() > 0) out.append('|');
            Object item = field.get(value);
            out.append(field.getName()).append('=');
            if (type == double.class) out.append(bits((Double) item));
            else if (type == long.class) out.append(Long.toHexString((Long) item));
            else out.append(item);
        }
        return out.toString();
    }

    private static void write(Path output, String header, List<String> rows) throws Exception {
        List<String> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        Files.write(output, all, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void discover(Path output) throws Exception {
        GenerationMaps maps = new GenerationMaps(SEED);
        List<Point> points = new ArrayList<>();
        for (int[] xy :
                new int[][] {
                    {7124, 3179}, {8102, 4552}, {75891, 3626}, {-1, -1}, {-17, -16}, {0, 0}
                }) points.add(new Point("CONTROL", 0, 0, xy[0], xy[1]));
        Sites sites = maps.terrain.types.sites;
        for (Sites.Lane lane :
                new Sites.Lane[] {
                    Sites.Lane.HILLS, Sites.Lane.CUTS, Sites.Lane.ROCKY, Sites.Lane.SEDIMENT
                }) {
            int owners = 0;
            search:
            for (int z = 0; z <= LAST_CELL; z++)
                for (int x = 0; x <= LAST_CELL; x++) {
                    long key = sites.key(x, z);
                    if (!sites.admits(x, z, key, lane)) continue;
                    points.add(
                            new Point(
                                    lane.name(),
                                    x,
                                    z,
                                    (int) Math.floor(sites.x(x, key)),
                                    (int) Math.floor(sites.z(z, key))));
                    if (++owners == OWNER_CAP) break search;
                }
            System.out.println(
                    "Bounded discovery lane="
                            + lane
                            + " observedOwners="
                            + owners
                            + " cap="
                            + OWNER_CAP);
        }
        List<String> rows = new ArrayList<>();
        for (Point point : points) {
            TerrainMap.Sample sample = new TerrainMap.Sample();
            maps.terrain.sample(point.x, point.z, sample);
            rows.add(point.row());
            System.out.println(
                    point.row()
                            + " marine="
                            + sample.ocean.marine
                            + " hills="
                            + sample.hills.reason
                            + " cuts="
                            + sample.cuts.reason
                            + " rocky="
                            + sample.rocky.reason
                            + " sediment="
                            + sample.sediment.reason);
        }
        require(points.size() <= 6 + 4 * OWNER_CAP, "Expanded discovery");
        write(output, MANIFEST, rows);
    }

    private static String snapshot(GenerationMaps maps, Point p, int stage, TerrainMap.Sample s)
            throws Exception {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(p.x, p.z, s);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(p.x, p.z, s);
        else if (stage == 2) maps.terrain.sampleBeforeSediment(p.x, p.z, s);
        else maps.terrain.sample(p.x, p.z, s);
        require(Double.isFinite(s.height), "Nonfinite height");
        StringBuilder boundaries = new StringBuilder(), edits = new StringBuilder();
        for (int i = 0; i < s.rocky.cutCount; i++)
            boundaries.append(s.rocky.boundary(i, Integer.MIN_VALUE)).append('|');
        for (int y :
                new int[] {
                    s.blockY() - 1,
                    s.blockY(),
                    s.blockY() + 1,
                    s.waterY(),
                    (int) Math.floor(s.rocky.parentHeight)
                }) edits.append(y).append(':').append(RockyTerrain.edit(s.rocky, y)).append('|');
        String land =
                bits(s.baseHeight)
                        + '|'
                        + bits(s.minimumY)
                        + '|'
                        + bits(s.maximumY)
                        + '|'
                        + bits(s.plateaus.height)
                        + '|'
                        + bits(s.mountains.height)
                        + '|'
                        + bits(s.carvers.height)
                        + '|'
                        + bits(s.drylands.height)
                        + '|'
                        + bits(s.drylands.weight)
                        + '|'
                        + s.ocean.marine
                        + '|'
                        + s.ocean.supplied
                        + '|'
                        + s.emergentRock
                        + '|'
                        + s.emergentSediment
                        + '|'
                        + s.shore.kind;
        return p.row()
                + ','
                + stage
                + ','
                + bits(s.height)
                + ','
                + bits(s.rockyParentHeight)
                + ','
                + land
                + ','
                + scalarFields(s.water)
                + ','
                + scalarFields(s.hills)
                + ','
                + scalarFields(s.cuts)
                + ','
                + scalarFields(s.rocky)
                + ','
                + scalarFields(s.sediment)
                + ','
                + s.rocky.hasVolume()
                + ','
                + boundaries
                + ','
                + edits
                + ','
                + maps.surfaces.reason(s)
                + ','
                + maps.surfaces.bareStone(s);
    }

    private static void capture(Path manifest, Path output) throws Exception {
        List<Point> points = new ArrayList<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.equals(MANIFEST)) continue;
            String[] v = line.split(",", -1);
            require(v.length == 6 && Long.parseLong(v[0]) == SEED, "Invalid witness");
            points.add(
                    new Point(
                            v[1],
                            Integer.parseInt(v[2]),
                            Integer.parseInt(v[3]),
                            Integer.parseInt(v[4]),
                            Integer.parseInt(v[5])));
        }
        require(!points.isEmpty() && points.size() <= 22, "Invalid bounded manifest");
        GenerationMaps maps = new GenerationMaps(SEED);
        List<String> rows = new ArrayList<>();
        for (Point point : points)
            for (int stage = 0; stage < 4; stage++)
                rows.add(snapshot(maps, point, stage, new TerrainMap.Sample()));
        TerrainMap.Sample reused = new TerrainMap.Sample();
        for (int pass = 0; pass < 2; pass++)
            for (int offset = 0; offset < rows.size(); offset++) {
                int index = pass == 0 ? offset : rows.size() - 1 - offset;
                require(
                        rows.get(index)
                                .equals(snapshot(maps, points.get(index / 4), index % 4, reused)),
                        "Scratch/order mismatch " + index);
            }
        write(output, HEADER, rows);
        System.out.println(
                "PASS bounded swarm rows="
                        + rows.size()
                        + "; four prefixes; all public scalar fields; fresh/reused forward/reverse; no cold-cache/native/voxel topology/performance qualification.");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("discover")) discover(Paths.get(args[1]));
        else if (args.length == 3 && args[0].equals("capture"))
            capture(Paths.get(args[1]), Paths.get(args[2]));
        else
            throw new IllegalArgumentException(
                    "discover <new-manifest> | capture <manifest> <new-output>");
    }
}
