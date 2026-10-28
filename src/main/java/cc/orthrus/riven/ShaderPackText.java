package cc.orthrus.riven;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft-free edits of a shader pack's properties files for /riven shaderprops. Every original
 * line stays as written; additions go at the end of an existing block entry or biome list, so the
 * pack keeps its own IDs, comments and version branches.
 */
public final class ShaderPackText {
    private static final Pattern ENTRY = Pattern.compile("^\\s*block\\.(\\d+)\\s*=(.*)$");
    private static final Pattern CONDITION =
            Pattern.compile("^MC_VERSION\\s*(>=|<=|==|!=|>|<)\\s*(\\d+)$");
    private static final Pattern BIOME_LIST = Pattern.compile("in\\(\\s*biome\\s*,([^()]*)\\)");

    private ShaderPackText() {}

    /** One block.N= entry, the lines it spans, its names and whether its version branch is live. */
    static final class Entry {
        final String id;
        final int first, last;
        final List<String> names;
        final boolean active;

        Entry(String id, int first, int last, List<String> names, boolean active) {
            this.id = id;
            this.first = first;
            this.last = last;
            this.names = names;
            this.active = active;
        }
    }

    /** What an edit changed. */
    public static final class Result {
        public final String text;
        public final int lists, additions, removals, unknownConditions;

        Result(String text, int lists, int additions, int removals, int unknownConditions) {
            this.text = text;
            this.lists = lists;
            this.additions = additions;
            this.removals = removals;
            this.unknownConditions = unknownConditions;
        }
    }

    /** Block name to shader ID for the entries live at this Minecraft version; the first wins. */
    public static Map<String, String> anchors(String text, int mcVersion) {
        Map<String, String> anchors = new LinkedHashMap<>();
        for (Entry e : entries(lines(text), mcVersion, new int[1]))
            if (e.active) for (String name : e.names) anchors.putIfAbsent(name, e.id);
        return anchors;
    }

    /**
     * Appends names to the first live entry of each ID, skipping names any live entry already
     * holds. IDs without a live entry are left out; callers get IDs from {@link #anchors}.
     */
    public static Result appendBlocks(
            String text, int mcVersion, Map<String, ? extends Collection<String>> namesById) {
        List<String> lines = lines(text);
        int[] unknown = new int[1];
        List<Entry> entries = entries(lines, mcVersion, unknown);
        Set<String> present = new LinkedHashSet<>();
        Map<String, Entry> target = new LinkedHashMap<>();
        for (Entry e : entries)
            if (e.active) {
                present.addAll(e.names);
                target.putIfAbsent(e.id, e);
            }
        // Edit from the bottom up so earlier line numbers stay valid.
        List<Entry> order = new ArrayList<>(target.values());
        order.sort((a, b) -> Integer.compare(b.last, a.last));
        int lists = 0, additions = 0;
        for (Entry e : order) {
            Collection<String> wanted = namesById.get(e.id);
            if (wanted == null) continue;
            List<String> added = new ArrayList<>();
            for (String name : wanted) if (present.add(name)) added.add(name);
            if (added.isEmpty()) continue;
            lines.set(e.last, lines.get(e.last).replaceAll("\\s+$", "") + " \\");
            lines.add(e.last + 1, String.join(" ", added));
            lists++;
            additions += added.size();
        }
        return new Result(join(lines, text), lists, additions, 0, unknown[0]);
    }

    /**
     * Adds each riven: biome ID to every numeric in(biome, ...) list that holds its vanilla twin's
     * ID. A listed ID that is a riven: biome in this world (packs list IDs vanilla 1.12 never used)
     * stays only where its twin is listed. Lists written with names (modern BIOME_ constants) are
     * left alone.
     */
    public static Result appendBiomes(
            String text,
            Map<Integer, ? extends Collection<Integer>> rivenByTwin,
            Set<Integer> rivenIds) {
        Matcher m = BIOME_LIST.matcher(text);
        StringBuffer out = new StringBuffer();
        int lists = 0, additions = 0, removals = 0;
        while (m.find()) {
            List<Integer> ids = new ArrayList<>();
            boolean numeric = true;
            for (String token : m.group(1).split(",")) {
                try {
                    ids.add(Integer.parseInt(token.trim()));
                } catch (NumberFormatException notNumeric) {
                    numeric = false;
                    break;
                }
            }
            String replacement = m.group();
            if (numeric) {
                Set<Integer> justified = new TreeSet<>();
                for (int id : ids) {
                    Collection<Integer> riven = rivenIds.contains(id) ? null : rivenByTwin.get(id);
                    if (riven != null) justified.addAll(riven);
                }
                List<Integer> kept = new ArrayList<>();
                for (int id : ids)
                    if (!rivenIds.contains(id) || justified.contains(id)) kept.add(id);
                TreeSet<Integer> added = new TreeSet<>(justified);
                added.removeAll(ids);
                int removed = ids.size() - kept.size();
                if (removed > 0) {
                    kept.addAll(added);
                    StringBuilder list = new StringBuilder("in(biome");
                    for (int id : kept) list.append(", ").append(id);
                    replacement = list.append(")").toString();
                } else if (!added.isEmpty()) {
                    StringBuilder tail = new StringBuilder();
                    for (int r : added) tail.append(", ").append(r);
                    replacement = replacement.substring(0, replacement.length() - 1) + tail + ")";
                }
                if (removed > 0 || !added.isEmpty()) lists++;
                additions += added.size();
                removals += removed;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return new Result(out.toString(), lists, additions, removals, 0);
    }

    /**
     * Block entries with the live state of their version branch. Directives other than
     * MC_VERSION comparisons cannot be judged here; their branches count as not live.
     */
    static List<Entry> entries(List<String> lines, int mcVersion, int[] unknownConditions) {
        List<Entry> entries = new ArrayList<>();
        // Each frame: {parent live, a branch already taken, this branch live}.
        List<boolean[]> stack = new ArrayList<>();
        boolean live = true;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#")) {
                String directive = line.substring(1).trim();
                if (directive.startsWith("if")) {
                    Boolean test = test(directive, mcVersion, unknownConditions);
                    boolean branch = live && Boolean.TRUE.equals(test);
                    stack.add(new boolean[] {live, branch, branch});
                    live = branch;
                } else if (directive.startsWith("elif") && !stack.isEmpty()) {
                    boolean[] frame = stack.get(stack.size() - 1);
                    Boolean test =
                            test("if" + directive.substring(4), mcVersion, unknownConditions);
                    frame[2] = frame[0] && !frame[1] && Boolean.TRUE.equals(test);
                    frame[1] |= frame[2];
                    live = frame[2];
                } else if (directive.startsWith("else") && !stack.isEmpty()) {
                    boolean[] frame = stack.get(stack.size() - 1);
                    frame[2] = frame[0] && !frame[1];
                    frame[1] = true;
                    live = frame[2];
                } else if (directive.startsWith("endif") && !stack.isEmpty()) {
                    live = stack.remove(stack.size() - 1)[0];
                }
                continue;
            }
            Matcher m = ENTRY.matcher(lines.get(i));
            if (!m.matches()) continue;
            int first = i;
            List<String> names = new ArrayList<>();
            String value = m.group(2);
            while (true) {
                String trimmed = value.replaceAll("\\s+$", "");
                boolean more = trimmed.endsWith("\\");
                if (more) trimmed = trimmed.substring(0, trimmed.length() - 1);
                for (String name : trimmed.trim().split("\\s+"))
                    if (!name.isEmpty()) names.add(name);
                if (!more || i + 1 >= lines.size()) break;
                value = lines.get(++i);
            }
            entries.add(new Entry(m.group(1), first, i, names, live));
        }
        return entries;
    }

    /** The truth of "if MC_VERSION op N", or null for any other condition. */
    private static Boolean test(String directive, int mcVersion, int[] unknownConditions) {
        String condition =
                directive.startsWith("ifdef") || directive.startsWith("ifndef")
                        ? null
                        : directive.substring(2).trim();
        Matcher m = condition == null ? null : CONDITION.matcher(condition);
        if (m == null || !m.matches()) {
            unknownConditions[0]++;
            return null;
        }
        int n = Integer.parseInt(m.group(2));
        switch (m.group(1)) {
            case ">=":
                return mcVersion >= n;
            case "<=":
                return mcVersion <= n;
            case ">":
                return mcVersion > n;
            case "<":
                return mcVersion < n;
            case "==":
                return mcVersion == n;
            default:
                return mcVersion != n;
        }
    }

    private static List<String> lines(String text) {
        return new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
    }

    private static String join(List<String> lines, String original) {
        return String.join(original.contains("\r\n") ? "\r\n" : "\n", lines);
    }
}
