package cc.orthrus.riven.worldgen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Frozen-artifact comparison of the inland base expression, without marine or water planning. */
public final class BaseTerrainSnapshotCheck {
    private static final String[] FIELDS = {
        "baseHeight",
        "baseDx",
        "baseDz",
        "broadDx",
        "broadDz",
        "minimumY",
        "maximumY",
        "uplift",
        "blend",
        "intentDx",
        "intentDz",
        "firstLandform",
        "secondLandform"
    };

    private BaseTerrainSnapshotCheck() {}

    private static String value(Object item) {
        return item instanceof Double
                ? Long.toHexString(Double.doubleToLongBits((Double) item))
                : String.valueOf(item);
    }

    private static String snapshot(TerrainMap.BaseSample out) throws Exception {
        StringBuilder row = new StringBuilder();
        for (String name : FIELDS)
            row.append(name)
                    .append('=')
                    .append(value(TerrainMap.BaseSample.class.getField(name).get(out)))
                    .append('|');
        try {
            for (String name : new String[] {"amplitude", "noise", "position"}) {
                Field field = TerrainMap.BaseSample.class.getDeclaredField(name);
                field.setAccessible(true);
                row.append(value(field.get(out))).append('|');
            }
            Field field = TerrainMap.BaseSample.class.getDeclaredField("detail");
            field.setAccessible(true);
            Field amplitude = TerrainMap.BaseSample.class.getDeclaredField("amplitude");
            amplitude.setAccessible(true);
            row.append(
                    value(amplitude.getDouble(out) * ((HeightSample) field.get(out)).height * .2));
        } catch (NoSuchFieldException current) {
            Field field = TerrainMap.BaseSample.class.getDeclaredField("base");
            field.setAccessible(true);
            Object base = field.get(out);
            for (String name :
                    new String[] {"amplitude", "noise", "legacyPosition", "fineContribution"})
                row.append(value(base.getClass().getMethod(name).invoke(base))).append('|');
            row.setLength(row.length() - 1);
        }
        return row.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("<new-output.csv>");
        Method inland;
        try {
            inland =
                    TerrainMap.class.getDeclaredMethod(
                            "sampleBase", double.class, double.class, TerrainMap.BaseSample.class);
        } catch (NoSuchMethodException current) {
            inland =
                    TerrainMap.class.getDeclaredMethod(
                            "sampleInland",
                            double.class,
                            double.class,
                            TerrainMap.BaseSample.class);
        }
        inland.setAccessible(true);
        List<String> rows = new ArrayList<>();
        rows.add("seed,x,z,base");
        for (long seed : new long[] {0, -1, 8675309}) {
            TerrainMap map = new GenerationMaps(seed).terrain;
            TerrainMap.BaseSample reused = new TerrainMap.BaseSample();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 1024; i++) {
                double x = (i % 32 - 16) * 4093.75 + .25;
                double z = (i / 32 - 16) * 4021.25 - .5;
                TerrainMap.BaseSample fresh = new TerrainMap.BaseSample();
                inland.invoke(map, x, z, fresh);
                String observation = snapshot(fresh);
                expected.add(observation);
                inland.invoke(map, x, z, reused);
                if (!observation.equals(snapshot(reused)))
                    throw new AssertionError("Scratch replay");
                rows.add(seed + "," + x + "," + z + "," + observation);
            }
            for (int i = 1023; i >= 0; i--) {
                inland.invoke(
                        map, (i % 32 - 16) * 4093.75 + .25, (i / 32 - 16) * 4021.25 - .5, reused);
                if (!expected.get(i).equals(snapshot(reused)))
                    throw new AssertionError("Reverse replay");
            }
        }
        Files.write(
                Paths.get(args[0]), rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(
                "Inland base snapshots=" + (rows.size() - 1) + " fresh/reused/reverse=PASS");
    }
}
