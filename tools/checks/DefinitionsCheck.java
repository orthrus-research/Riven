package cc.orthrus.riven.worldgen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Definitions lists exactly the packaged definitions, and each loads. Usage: DefinitionsCheck
 * [resources directory] (default src/main/resources). Every riven-*.json there must be catalogued
 * and every catalogued name must exist; Rules refuses any other name, so a definition added
 * without its catalogue entry fails here and wherever it is first loaded.
 */
public final class DefinitionsCheck {
    public static void main(String[] args) {
        File directory = new File(args.length == 0 ? "src/main/resources" : args[0]);
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
        if (!problems.isEmpty()) throw new AssertionError("Definitions: " + problems);
        int revised = 0;
        for (Rules rules : Definitions.loadAll()) if (rules.revision() != null) revised++;
        System.out.println(
                "definitions catalogued and loaded: "
                        + catalogued.size()
                        + " ("
                        + revised
                        + " with a revision)");
    }
}
