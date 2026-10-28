package cc.orthrus.riven;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * /riven shaderprops edits on a real pack's files (args: block.properties shaders.properties):
 * 1.12 anchors resolve, appended blocks parse back to their anchor's ID, every original line
 * survives in order, and only numeric biome lists grow.
 */
public final class ShaderPropsCheck {
    public static void main(String[] args) throws Exception {
        String blocks = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        String shaders = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
        Map<String, String> anchors = ShaderPackText.anchors(blocks, 11202);
        for (String anchor :
                new String[] {
                    "minecraft:leaves",
                    "minecraft:tallgrass",
                    "minecraft:red_flower",
                    "minecraft:double_plant:half=lower",
                    "minecraft:double_plant:half=upper",
                    "minecraft:sapling",
                    "minecraft:iron_ore",
                    "minecraft:glowstone"
                }) {
            if (anchors.get(anchor) == null)
                throw new IllegalStateException("The pack lacks anchor " + anchor);
            System.out.printf("%-36s %s%n", anchor, anchors.get(anchor));
        }
        if (anchors.containsKey("minecraft:short_grass"))
            throw new IllegalStateException("1.13+ branch leaked into 1.12 anchors");

        Map<String, List<String>> added = new LinkedHashMap<>();
        added.put(anchors.get("minecraft:leaves"), Arrays.asList("test:leaves_a", "test:leaves_b"));
        added.put(
                anchors.get("minecraft:double_plant:half=lower"),
                Collections.singletonList("test:tall:half=lower"));
        added.put(
                anchors.get("minecraft:tallgrass"),
                Arrays.asList("minecraft:tallgrass", "test:grass"));
        ShaderPackText.Result b = ShaderPackText.appendBlocks(blocks, 11202, added);
        Map<String, String> after = ShaderPackText.anchors(b.text, 11202);
        expect(after, "test:leaves_a", anchors.get("minecraft:leaves"));
        expect(after, "test:leaves_b", anchors.get("minecraft:leaves"));
        expect(after, "test:tall:half=lower", anchors.get("minecraft:double_plant:half=lower"));
        expect(after, "test:grass", anchors.get("minecraft:tallgrass"));
        if (b.additions != 4)
            throw new IllegalStateException("Expected 4 additions, got " + b.additions);
        if (!ShaderPackText.anchors(b.text, 11300).equals(ShaderPackText.anchors(blocks, 11300)))
            throw new IllegalStateException("The 1.13+ branch changed");
        keepsLines(blocks, b.text);

        Map<Integer, List<Integer>> twins = new LinkedHashMap<>();
        twins.put(2, Arrays.asList(201, 202)); // desert
        twins.put(12, Collections.singletonList(203)); // ice plains
        Set<Integer> riven = new TreeSet<>(Arrays.asList(201, 202, 203));
        ShaderPackText.Result s = ShaderPackText.appendBiomes(shaders, twins, riven);
        // Taking the added IDs back out restores the file exactly.
        if (!s.text.replace(", 201", "").replace(", 202", "").replace(", 203", "").equals(shaders))
            throw new IllegalStateException("Biome edit changed more than its lists");

        // A riven: biome holding an ID the pack lists for something else leaves that list.
        twins.put(5, Collections.singletonList(49)); // taiga twin; BSL lists 49 under isJungle
        riven.add(49);
        ShaderPackText.Result c = ShaderPackText.appendBiomes(shaders, twins, riven);
        boolean jungleClean = false, coldHas = false;
        for (String line : c.text.split("\r?\n")) {
            if (line.contains("isJungle") && line.contains("in(biome, 21, 22, 23, 48, 149, 151)"))
                jungleClean = true;
            if (line.contains("in(biome, 5, 19, 32, 33, 133, 49)")) coldHas = true;
        }
        if (!jungleClean || !coldHas || c.removals != 1)
            throw new IllegalStateException(
                    "Collision handling: jungle " + jungleClean + ", taiga " + coldHas);
        System.out.printf(
                "blocks: %d entries grew by %d names (%d unjudged conditions); biomes: %d lists grew by %d IDs; collision case removed %d%n",
                b.lists, b.additions, b.unknownConditions, s.lists, s.additions, c.removals);
        for (String line : s.text.split("\r?\n"))
            if (line.contains("201") || line.contains("203"))
                System.out.println("  " + line.trim());
    }

    private static void expect(Map<String, String> anchors, String name, String id) {
        if (!id.equals(anchors.get(name)))
            throw new IllegalStateException(name + " -> " + anchors.get(name) + ", expected " + id);
    }

    /** Each original line appears in the edit, in order, unchanged apart from an added continuation. */
    private static void keepsLines(String original, String edited) {
        String[] before = original.split("\r?\n", -1), after = edited.split("\r?\n", -1);
        int j = 0;
        for (String line : before) {
            String bare = line.replaceAll("\\s+$", "");
            while (j < after.length && !after[j].equals(line) && !after[j].equals(bare + " \\"))
                j++;
            if (j++ >= after.length) throw new IllegalStateException("Lost line: " + line);
        }
    }
}
