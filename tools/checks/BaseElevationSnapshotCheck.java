package cc.orthrus.riven.worldgen;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Frozen-artifact differential of elevation observations and the complete inland base. */
public final class BaseElevationSnapshotCheck {
    private BaseElevationSnapshotCheck() {}

    private static String observations(Object sample, String[] fields) throws Exception {
        StringBuilder result = new StringBuilder();
        for (String field : fields) {
            Object value = sample.getClass().getMethod(field).invoke(sample);
            result.append(field).append('=');
            if (value instanceof Double)
                result.append(Long.toHexString(Double.doubleToRawLongBits((Double) value)));
            else result.append(value);
            result.append('|');
        }
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("<elevation|base> <new-output.csv>");
        boolean elevation = args[0].equals("elevation");
        if (!elevation && !args[0].equals("base")) throw new IllegalArgumentException("Mode");
        List<String> rows = new ArrayList<>();
        rows.add("policy,seed,x,z,observations");
        for (BaseTerrainMap.Policy policy : BaseTerrainMap.Policy.values())
            for (long seed : new long[] {0, -1, 8675309, 7, -918273645}) {
                GenerationMaps maps =
                        new GenerationMaps(seed, TerrainHeightReference.DEFAULT, policy);
                Object provider = maps.baseTerrain;
                Class<?> scratch = BaseTerrainMap.Sample.class;
                if (elevation)
                    try {
                        provider = GenerationMaps.class.getField("baseElevation").get(maps);
                        scratch =
                                Class.forName("cc.orthrus.riven.worldgen.BaseElevationMap$Sample");
                    } catch (NoSuchFieldException baseline) {
                        // The frozen artifact exposes these same earlier facts through its base
                        // sample.
                    }
                Method sample =
                        provider.getClass()
                                .getMethod("sample", double.class, double.class, scratch);
                Object out = scratch.getConstructor().newInstance();
                String[] fields =
                        elevation
                                ? new String[] {
                                    "meanHeight",
                                    "minimumY",
                                    "maximumY",
                                    "uplift",
                                    "blend",
                                    "intentDx",
                                    "intentDz",
                                    "legacyPosition",
                                    "firstLandform",
                                    "secondLandform"
                                }
                                : new String[] {
                                    "height",
                                    "dx",
                                    "dz",
                                    "broadDx",
                                    "broadDz",
                                    "meanHeight",
                                    "coarseHeight",
                                    "minimumY",
                                    "maximumY",
                                    "amplitude",
                                    "noise",
                                    "fineContribution",
                                    "uplift",
                                    "blend",
                                    "intentDx",
                                    "intentDz",
                                    "legacyPosition",
                                    "firstLandform",
                                    "secondLandform"
                                };
                for (int i = 0; i < 1028; i++) {
                    double x = (i % 32 - 16) * 4093.75 + .25;
                    double z = (i / 32 - 16) * 4021.25 - .5;
                    if (i >= 1024) {
                        x = i % 2 == 0 ? -30000000 : 30000000;
                        z = i % 4 < 2 ? -30000000 : 30000000;
                    }
                    sample.invoke(provider, x, z, out);
                    String result = observations(out, fields);
                    if (!elevation) {
                        Object region = out.getClass().getMethod("region").invoke(out);
                        result +=
                                observations(
                                        region,
                                        new String[] {
                                            "amplitudeFraction",
                                            "amplitudeDx",
                                            "amplitudeDz",
                                            "wideShare",
                                            "wideDx",
                                            "wideDz",
                                            "fineShare",
                                            "fineDx",
                                            "fineDz"
                                        });
                    }
                    rows.add(policy + "," + seed + "," + x + "," + z + "," + result);
                }
            }
        Files.write(
                Paths.get(args[1]), rows, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(args[0] + " frozen observations=" + (rows.size() - 1));
    }
}
