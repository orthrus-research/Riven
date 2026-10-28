package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.hills.Hills;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded production-plan search near a declared inland control; not native block acceptance. */
public final class HillFabricNaturalCheck {
    private HillFabricNaturalCheck() {}

    private static final class Point {
        final int x, z;
        final double score;

        Point(int x, int z, double score) {
            this.x = x;
            this.z = z;
            this.score = score;
        }
    }

    public static void main(String[] args) throws Exception {
        if ((args.length != 3 && args.length != 4 && args.length != 5)
                || !(args[0].equals("discover")
                        || args[0].equals("discover-broad")
                        || args[0].equals("capture")))
            throw new IllegalArgumentException(
                    "<discover|discover-broad|capture> <points.csv> <new-plans.csv> [new-final.csv] [point]");
        boolean point = args.length == 5;
        if (point && !args[4].equals("point"))
            throw new IllegalArgumentException("Final sampling mode");
        boolean broad = args[0].equals("discover-broad"), discover = !args[0].equals("capture");
        List<String> manifest = new ArrayList<>(), rows = new ArrayList<>();
        manifest.add("seed,cx,cz");
        rows.add(
                "seed,cx,cz,family,reason,columns,key,xBits,zBits,aBits,bBits,amplitudeBits,geometry");
        List<String> finals = new ArrayList<>();
        finals.add("seed,cx,cz,x,z,height,blockY,hillDelta,hillWeight,hillID,family,cover");
        List<String> fixed =
                discover ? null : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8);
        int positives = 0;
        for (long seed : new long[] {8675309, 7, -918273645}) {
            GenerationMaps maps = new GenerationMaps(seed);
            Sites sites = maps.terrain.types.sites;
            Field f = TerrainMap.class.getDeclaredField("hills");
            f.setAccessible(true);
            HillTerrain hills = (HillTerrain) f.get(maps.terrain);
            try {
                Field fabricField = HillTerrain.class.getDeclaredField("fabric");
                fabricField.setAccessible(true);
                if (fabricField.get(hills) != GenerationMaps.class.getField("fabric").get(maps))
                    throw new AssertionError(
                            "Hill adapter does not share its seed-owned fabric provider");
            } catch (NoSuchFieldException baseline) {
                // The frozen artifact predates the fabric provider.
            }
            List<Point> points = new ArrayList<>();
            if (discover) {
                BaseTerrainMap.Sample potential = new BaseTerrainMap.Sample();
                TerrainHeightReference.Feature form =
                        TerrainHeightReference.DEFAULT.feature("rolling_hills");
                double low = TerrainHeightReference.DEFAULT.seaLevel + form.elevationOffset.min;
                double high = TerrainHeightReference.DEFAULT.seaLevel + form.elevationOffset.max;
                int cx = (int) Math.floor(75891 / sites.spacing),
                        cz = (int) Math.floor(3626 / sites.spacing);
                ContinentalOutlineMap.Sample outline = new ContinentalOutlineMap.Sample();
                int minX = broad ? -512 : cx - 32, maxX = broad ? 512 : cx + 32;
                int minZ = broad ? -512 : cz - 32,
                        maxZ = broad ? 512 : cz + 32,
                        stride = broad ? 7 : 1;
                for (int z = minZ; z <= maxZ; z += stride)
                    for (int x = minX; x <= maxX; x += stride) {
                        long owner = sites.key(x, z);
                        if (!sites.admits(x, z, owner, Sites.Lane.HILLS)) continue;
                        maps.baseTerrain.sample(sites.x(x, owner), sites.z(z, owner), potential);
                        // A conditional search filter, not a proof of admission or marine
                        // ownership.
                        if (potential.height() < low
                                || potential.height() + .8 * form.relief.targetAverage > high)
                            continue;
                        double score = Math.abs(potential.height() - 128);
                        if (broad) {
                            maps.outline.sample(sites.x(x, owner), sites.z(z, owner), outline);
                            if (outline.marine() || outline.landWeight() < 1) continue;
                            // Score is geographic opportunity, not a distance or clearance
                            // certificate.
                            score = -outline.coastCoordinate() + 4 * score;
                        }
                        points.add(new Point(x, z, score));
                    }
                points.sort(
                        Comparator.comparingDouble((Point p) -> p.score)
                                .thenComparingInt(p -> p.z)
                                .thenComparingInt(p -> p.x));
            } else {
                for (String row : fixed) {
                    if (row.startsWith("seed,")) continue;
                    String[] fields = row.split(",");
                    if (Long.parseLong(fields[0]) == seed)
                        points.add(
                                new Point(
                                        Integer.parseInt(fields[1]),
                                        Integer.parseInt(fields[2]),
                                        0));
                }
            }
            int requests = 0, found = 0;
            for (Point p : points) {
                if (discover && (requests == 64 || found == 2)) break;
                HillTerrain.Decision d = hills.plan(p.x, p.z);
                manifest.add(seed + "," + p.x + "," + p.z);
                rows.add(
                        seed
                                + ","
                                + p.x
                                + ","
                                + p.z
                                + ","
                                + HillFabricSnapshotCheck.identity(d)
                                + ","
                                + HillFabricSnapshotCheck.geometry(d));
                requests++;
                if (d.site != null && d.family == Hills.Family.SKEW_HILLS) {
                    found++;
                    positives++;
                    if (args.length >= 4) {
                        TerrainMap.Sample sample = new TerrainMap.Sample();
                        for (int dz = -1; dz <= 1; dz++)
                            for (int dx = -1; dx <= 1; dx++) {
                                if (point && (dx != 1 || dz != 0)) continue;
                                int x = (int) Math.floor(d.site.x + dx * d.site.minorRadius / 2);
                                int z = (int) Math.floor(d.site.z + dz * d.site.minorRadius / 2);
                                maps.terrain.sample(x, z, sample);
                                finals.add(
                                        seed
                                                + ","
                                                + p.x
                                                + ","
                                                + p.z
                                                + ","
                                                + x
                                                + ","
                                                + z
                                                + ","
                                                + sample.height
                                                + ","
                                                + sample.blockY()
                                                + ","
                                                + sample.hills.delta
                                                + ","
                                                + sample.hills.weight
                                                + ","
                                                + Long.toHexString(sample.hills.id)
                                                + ","
                                                + sample.hills.dominant
                                                + ","
                                                + maps.surfaces.reason(sample));
                            }
                    }
                }
            }
            System.out.println(
                    "seed="
                            + seed
                            + " conditionalCandidates="
                            + points.size()
                            + " productionPlanRequests="
                            + requests
                            + " acceptedSkew="
                            + found);
        }
        if (discover)
            Files.write(
                    Paths.get(args[1]),
                    manifest,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        Files.write(
                Paths.get(args[2]), rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        if (args.length >= 4)
            Files.write(
                    Paths.get(args[3]),
                    finals,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        System.out.println(
                "Bounded natural-plan observations="
                        + (rows.size() - 1)
                        + "; acceptedSkew="
                        + positives
                        + "; conditional observations; optional final columns are model samples, not native/global qualification.");
    }
}
