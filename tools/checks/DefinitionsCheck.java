package cc.orthrus.riven.worldgen;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Definitions lists exactly the packaged definitions, each loads, and the terrain version agrees
 * everywhere it is written. Usage: DefinitionsCheck [resources directory] [repository root]
 * (defaults src/main/resources and .). Every riven-*.json there must be catalogued and every
 * catalogued name must exist; Rules refuses any other name, so a definition added without its
 * catalogue entry fails here and wherever it is first loaded. TerrainMap.VERSION must equal the
 * newest row of CHANGELOG.md's terrain table and the version README.md names.
 */
public final class DefinitionsCheck {
    /** The last number matched by pattern in a file, or -1. */
    private static int last(File file, Pattern pattern) throws IOException {
        Matcher m =
                pattern.matcher(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        int found = -1;
        while (m.find()) found = Integer.parseInt(m.group(1));
        return found;
    }

    public static void main(String[] args) throws IOException {
        File directory = new File(args.length == 0 ? "src/main/resources" : args[0]);
        File root = new File(args.length < 2 ? "." : args[1]);
        String[] names = directory.list((dir, name) -> name.matches("riven-.+\\.json"));
        if (names == null) throw new AssertionError("No resources directory " + directory);
        TreeSet<String> packaged = new TreeSet<>(), catalogued = new TreeSet<>();
        for (String name : names) packaged.add(name);
        catalogued.addAll(Definitions.resources());
        List<String> problems = new ArrayList<>();
        for (String name : packaged)
            if (!catalogued.contains(name)) problems.add("not catalogued: " + name);
        for (String name : catalogued)
            if (!packaged.contains(name)) problems.add("catalogued but not packaged: " + name);
        if (!new ArrayList<>(catalogued).equals(Definitions.resources()))
            problems.add("catalogue is not sorted");
        int changelog =
                last(new File(root, "CHANGELOG.md"), Pattern.compile("(?m)^\\| v(\\d+) \\|"));
        int readme =
                last(
                        new File(root, "README.md"),
                        Pattern.compile("current\\s+version is terrain-v(\\d+)"));
        if (changelog != TerrainMap.VERSION || readme != TerrainMap.VERSION)
            problems.add(
                    "terrain version: TerrainMap.VERSION "
                            + TerrainMap.VERSION
                            + ", CHANGELOG.md newest v"
                            + changelog
                            + ", README.md v"
                            + readme);
        if (!problems.isEmpty()) throw new AssertionError("Definitions: " + problems);
        int revised = 0;
        for (Rules rules : Definitions.loadAll()) if (rules.revision() != null) revised++;
        System.out.println(
                "definitions catalogued and loaded: "
                        + catalogued.size()
                        + " ("
                        + revised
                        + " with a revision); terrain-v"
                        + TerrainMap.VERSION
                        + " in TerrainMap, CHANGELOG.md and README.md");
    }
}
