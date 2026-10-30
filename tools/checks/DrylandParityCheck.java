package cc.orthrus.riven.worldgen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

/** Frozen-artifact parity for the dryland adapter, including positive controls for every family. */
public final class DrylandParityCheck {
    private static final long[] SEEDS = {0, -1, 8675309};
    private static final double PARENT = 160;

    private DrylandParityCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void dryIntent(double x, double z, TerrainMap.BaseSample out) {
        out.ocean.marine = false;
        out.shore.kind = Shore.Kind.INLAND;
        out.shore.landWeight = 1;
        out.shore.distance = 1000000;
        out.shore.height = PARENT;
        out.land.low = 1;
        out.climate.moisture = 0;
        out.climate.celsius = 0;
        out.minimumY = TerrainHeightReference.DEFAULT.lowestSurface;
        out.maximumY = TerrainHeightReference.DEFAULT.highestSurface;
    }

    private static String scalar(Object value) {
        return value instanceof Double
                ? Long.toHexString(Double.doubleToLongBits((Double) value))
                : String.valueOf(value);
    }

    static String snapshot(DrylandTerrain.Result result) throws Exception {
        Field[] fields = DrylandTerrain.Result.class.getFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        StringBuilder row = new StringBuilder();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (row.length() > 0) row.append('|');
            row.append(field.getName()).append('=');
            Object value = field.get(result);
            if (value instanceof double[]) {
                for (double item : (double[]) value) row.append(scalar(item)).append(';');
            } else if (value instanceof String[]) {
                for (String item : (String[]) value) row.append(item).append(';');
            } else row.append(scalar(value));
        }
        return row.toString();
    }

    private static Method applyMethod() {
        // Both frozen Hydro and current BoundedWaterClearance signatures run the same fixture.
        for (Method method : DrylandTerrain.class.getDeclaredMethods())
            if (method.getName().equals("apply") && method.getParameterTypes().length == 6)
                return method;
        throw new AssertionError("Missing dryland adapter");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Usage: DrylandParityCheck <new-output.csv>");
        Path output = Paths.get(args[0]);
        Method apply = applyMethod();
        Field adapterField = TerrainMap.class.getDeclaredField("drylands");
        adapterField.setAccessible(true);
        List<String> rows = new ArrayList<>();
        rows.add("seed,x,z,result");
        int[] accepted = new int[4];
        int changed = 0;
        for (long seed : SEEDS) {
            GenerationMaps maps = new GenerationMaps(seed);
            DrylandTerrain adapter = (DrylandTerrain) adapterField.get(maps.terrain);
            Hydro clearance =
                    new Hydro(
                            seed,
                            TerrainHeightReference.DEFAULT,
                            DrylandParityCheck::dryIntent,
                            (x, z, earlier) -> -1000,
                            maps.continents,
                            maps.terrain.oceanBed,
                            WorldScale.DEFAULT,
                            maps.pool);
            TerrainMap.Sample base = new TerrainMap.Sample();
            dryIntent(0, 0, base);
            DrylandTerrain.Result reused = new DrylandTerrain.Result();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 1024; i++) {
                double x = (i % 32 - 16) * 127.75 + .25;
                double z = (i / 32 - 16) * 131.25 - .5;
                DrylandTerrain.Result fresh = new DrylandTerrain.Result();
                apply.invoke(adapter, x, z, base, clearance, PARENT, fresh);
                String observation = snapshot(fresh);
                expected.add(observation);
                apply.invoke(adapter, x, z, base, clearance, PARENT, reused);
                require(observation.equals(snapshot(reused)), "Fresh/reused scratch: " + i);
                for (int family = 0; family < accepted.length; family++)
                    if (fresh.weights[family] > 0 && fresh.reasons[family].equals("ACCEPTED"))
                        accepted[family]++;
                if (fresh.delta != 0 || fresh.pavement > 0 || fresh.sandCover > 0) changed++;
                rows.add(seed + "," + x + "," + z + "," + observation);
            }
            for (int i = 1023; i >= 0; i--) {
                double x = (i % 32 - 16) * 127.75 + .25;
                double z = (i / 32 - 16) * 131.25 - .5;
                apply.invoke(adapter, x, z, base, clearance, PARENT, reused);
                require(expected.get(i).equals(snapshot(reused)), "Reverse replay: " + i);
            }
            // Reusing an accepted scratch must clear every observation on an early return.
            base.ocean.marine = true;
            apply.invoke(adapter, 0.25, -0.5, base, clearance, PARENT, reused);
            DrylandTerrain.Result fresh = new DrylandTerrain.Result();
            apply.invoke(adapter, 0.25, -0.5, base, clearance, PARENT, fresh);
            require(snapshot(fresh).equals(snapshot(reused)), "Early-return scratch reset");
        }
        for (int family = 0; family < accepted.length; family++)
            require(accepted[family] > 0, "Missing positive family: " + family);
        require(changed > 0, "No actual relief or cover contribution");
        Files.write(output, rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(
                "rows="
                        + (rows.size() - 1)
                        + " acceptedByFamily="
                        + Arrays.toString(accepted)
                        + " reliefOrCover="
                        + changed
                        + " scratch/reverse/reset=PASS");
    }
}
