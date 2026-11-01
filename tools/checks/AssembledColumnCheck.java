package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.AssembledColumn;
import cc.orthrus.riven.worldgen.engine.Assembler;
import cc.orthrus.riven.worldgen.engine.Role;
import cc.orthrus.riven.worldgen.engine.SettingTable;
import cc.orthrus.riven.worldgen.engine.Settings;

import java.util.Arrays;
import java.util.TreeMap;

/**
 * Assembled crust columns are well formed and pure. Usage: AssembledColumnCheck [seed ...].
 * Samples a coarse grid plus dense coastal and wonder strips per seed, and every column of chunk
 * columns along a diagonal crossing land and coast (carved as native cubes carve them), and
 * requires: runs cover the crust without gaps or overlaps, and neighbours differ; at(y) and
 * role(y) equal the runs at every boundary; a column assembled cold, from the reused buffer and
 * in reverse order is the same column; soil roles lie only in their layer under the sky's floor;
 * every run's role is one its pass writes; nothing above the highest surface but air, and no soil
 * or deposit below the lowest soil. Until the engine is wired, every block also equals
 * ColumnBlocks (the first setting table reproduces today's blocks).
 */
public final class AssembledColumnCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** A column's runs as one comparable array. */
    private static int[] snapshot(AssembledColumn c) {
        int[] out = new int[3 * c.count() + 2];
        out[0] = c.count();
        for (int i = 0; i < c.count(); i++) {
            out[3 * i + 1] = c.bottom(i);
            out[3 * i + 2] = c.runEntry(i);
            out[3 * i + 3] =
                    (c.runRole(i).ordinal() << 16) | (c.runZone(i) << 8) | c.runPass(i).ordinal();
        }
        out[out.length - 1] = c.topY();
        return out;
    }

    /** Structure, boundaries, roles by pass and soil layers of one assembled column. */
    private static void wellFormed(
            AssembledColumn c, TerrainHeightReference h, SettingTable table, String where) {
        require(
                c.bottomY() == h.minimumY && c.topY() == h.maximumYExclusive,
                "Runs must cover the crust at " + where);
        for (int i = 0; i < c.count(); i++) {
            require(c.top(i) > c.bottom(i), "Empty run at " + where);
            if (i > 0)
                require(
                        c.runEntry(i) != c.runEntry(i - 1)
                                || c.runRole(i) != c.runRole(i - 1)
                                || c.runZone(i) != c.runZone(i - 1),
                        "Equal neighbours unmerged at " + where + " y " + c.bottom(i));
            for (int y : new int[] {c.bottom(i), c.top(i) - 1})
                require(
                        c.at(y) == c.runBlock(i) && c.role(y) == c.runRole(i),
                        "at/role differ from the runs at " + where + " y " + y);
            require(
                    c.runPass(i).writes(c.runRole(i)),
                    c.runPass(i) + " wrote " + c.runRole(i) + " at " + where + " y " + c.bottom(i));
        }
        require(
                c.at(h.minimumY - 1) == TerrainBlock.AIR
                        && c.at(h.maximumYExclusive) == TerrainBlock.AIR,
                "Outside the crust is air at " + where);
        // Soil roles lie in their own layer under the sky's floor, and nowhere else.
        int floor = c.horizon(cc.orthrus.riven.worldgen.engine.Horizons.GROUND);
        int row = c.setting() < 0 ? -1 : table.rowOf(c.setting());
        int under = c.subsoilSetting() < 0 ? -1 : table.rowOf(c.subsoilSetting());
        for (int i = 0; i < c.count(); i++) {
            Role role = c.runRole(i);
            if (!role.soil()) continue;
            require(row >= 0, "Soil without a floor at " + where);
            int top = table.depth(row, SettingTable.TOP),
                    soil = top + table.depth(row, SettingTable.SOIL),
                    subsoil = soil + table.depth(under, SettingTable.SUBSOIL);
            int lo = role == Role.TOP ? 0 : role == Role.SOIL ? top : soil,
                    hi = role == Role.TOP ? top : role == Role.SOIL ? soil : subsoil;
            require(
                    floor - (c.top(i) - 1) >= lo && floor - c.bottom(i) < hi,
                    role + " outside its layer at " + where + " y " + c.bottom(i));
        }
    }

    public static void main(String[] args) {
        long[] seeds =
                args.length == 0 ? new long[] {8675309L, 101L, 1L, 5L} : new long[args.length];
        for (int i = 0; i < args.length; i++) seeds[i] = Long.parseLong(args[i]);
        TerrainHeightReference h = TerrainHeightReference.DEFAULT;
        int[] bottoms = new int[ColumnBlocks.maximumRuns() + 1];
        TerrainBlock[] blocks = new TerrainBlock[ColumnBlocks.maximumRuns()];
        TreeMap<String, Integer> coverage = new TreeMap<>(), settings = new TreeMap<>();
        long columns = 0, chunkColumns = 0, runs = 0, perBlock = 0;
        int maximumRuns = 0;
        AssembledColumn reused = new AssembledColumn();
        for (long seed : seeds) {
            GenerationMaps maps = new GenerationMaps(seed);
            TerrainMap terrain = maps.terrain;
            SurfaceRules rules = maps.surfaces;
            Assembler<TerrainMap.Sample> crust = CrustShape.assembler(rules, h);
            SettingTable table = crust.settings();
            int minimumSoilY = terrain.minimumSurfaceY - crust.materialDepth() + 1;
            require(
                    crust.materialDepth() == ColumnBlocks.materialDepth(rules),
                    "Material depth differs from ColumnBlocks");
            TerrainMap.Sample s = new TerrainMap.Sample();
            ContinentalMap.Sample probe = new ContinentalMap.Sample();
            int patches = 0;
            for (int gz = 0, z = -8192; z < 8192; gz++, z += 37)
                for (int gx = 0, x = -8192; x < 8192; gx++, x += 37) {
                    // Coarse grid everywhere; every column of a short strip where the coast is
                    // near on every 37th grid line, and through wonders, which carry voids under
                    // their surfaces.
                    boolean dense = false;
                    if (gx % 37 == 0 || gz % 37 == 0) {
                        maps.continents.sample(x, z, probe);
                        dense = Math.abs(probe.coastCoordinate) < 96;
                    }
                    if (!dense && gx % 5 == 0) {
                        terrain.sample(x, z, s);
                        dense = s.wonders.family != null;
                    }
                    int strip = dense ? 37 : 1;
                    for (int dx = 0; dx < strip; dx++) {
                        terrain.sample(x + dx, z, s);
                        boolean rocky = dense && s.rocky.weight > 0;
                        // A rocky shore's sites can hold volumes: a whole patch around the first
                        // few.
                        int side = rocky && patches < 12 ? 48 : 1;
                        if (side > 1) patches++;
                        for (int pz = 0; pz < side; pz++)
                            for (int px = 0; px < side; px++) {
                                int wx = x + dx + px - side / 2, wz = z + pz - side / 2;
                                if (side > 1) terrain.sample(wx, wz, s);
                                columns++;
                                column(
                                        maps,
                                        crust,
                                        table,
                                        s,
                                        reused,
                                        wx + "," + wz + " seed " + seed,
                                        minimumSoilY,
                                        h,
                                        bottoms,
                                        blocks,
                                        columns,
                                        coverage,
                                        settings);
                                AssembledColumn c = reused;
                                runs += c.count();
                                maximumRuns = Math.max(maximumRuns, c.count());
                                if (columns % 16 == 0) perBlock++;
                            }
                    }
                }
            chunkColumns += chunks(maps, h, seed, table, bottoms, blocks);
        }
        System.out.println(
                "PASS carved chunk columns: well formed, equal ColumnBlocks, cold = reused ="
                        + " reverse order; chunkColumns="
                        + chunkColumns);
        System.out.println(
                "PASS assembled columns: cover the crust, at = runs, roles legal per pass, soil"
                        + " only in its layer, equal ColumnBlocks; columns="
                        + columns
                        + " runs="
                        + runs
                        + " maxRuns="
                        + maximumRuns
                        + " perBlockColumns="
                        + perBlock
                        + " coverage="
                        + coverage
                        + " settings="
                        + settings
                        + (coverage.containsKey("rockyVolume")
                                ? ""
                                : "; coverage gap: no rocky volume column sampled"));
    }

    /** Every check of one sampled column, assembled into the reused buffer. */
    private static void column(
            GenerationMaps maps,
            Assembler<TerrainMap.Sample> crust,
            SettingTable table,
            TerrainMap.Sample s,
            AssembledColumn reused,
            String where,
            int minimumSoilY,
            TerrainHeightReference h,
            int[] bottoms,
            TerrainBlock[] blocks,
            long columns,
            TreeMap<String, Integer> coverage,
            TreeMap<String, Integer> settings) {
        AssembledColumn c = crust.assemble(s, reused);
        wellFormed(c, h, table, where);
        if (columns % 61 == 0)
            require(
                    Arrays.equals(snapshot(c), snapshot(crust.assemble(s, new AssembledColumn()))),
                    "Reused buffer differs from a cold one at " + where);
        for (int i = c.count() - 1; i >= 0 && c.top(i) > maps.terrain.maximumSurfaceY + 1; i--)
            require(c.runBlock(i) == TerrainBlock.AIR, "Above the highest surface at " + where);
        for (int i = 0; i < c.count() && c.bottom(i) < minimumSoilY; i++)
            require(
                    !c.runRole(i).soil() && c.runRole(i) != Role.DEPOSIT,
                    "Soil or deposit below the lowest soil at " + where);
        parity(c, s, maps.surfaces, h, bottoms, blocks, columns % 16 == 0, where);
        settings.merge(Settings.name(c.setting()), 1, Integer::sum);
        if (s.ocean.marine) coverage.merge("marine", 1, Integer::sum);
        if (s.water.wet) coverage.merge("wet", 1, Integer::sum);
        if (s.water.aquiferMin <= s.water.aquiferMax) coverage.merge("aquifer", 1, Integer::sum);
        if (s.sediment.weight > 0) coverage.merge("sediment", 1, Integer::sum);
        if (s.rocky.weight > 0) coverage.merge("rocky", 1, Integer::sum);
        if (s.rocky.hasVolume()) coverage.merge("rockyVolume", 1, Integer::sum);
        if (s.rocky.cutCount > 0) coverage.merge("rockyCuts", 1, Integer::sum);
        if (s.wonders.family != null) coverage.merge("wonder", 1, Integer::sum);
        if (s.wonders.palette != null) coverage.merge("wonderStrata", 1, Integer::sum);
        if (s.wonders.hasVoid()) coverage.merge("wonderVoid", 1, Integer::sum);
        if (s.caves.count() > 0) coverage.merge("caveEntrance", 1, Integer::sum);
        if (c.subsoilSetting() != c.setting()) coverage.merge("inheritedSubsoil", 1, Integer::sum);
    }

    /**
     * Carved chunk columns along a diagonal: assembled forward with the reused buffer, then in
     * reverse order by a fresh assembler over fresh maps, each well formed and equal to
     * ColumnBlocks.
     */
    private static int chunks(
            GenerationMaps maps,
            TerrainHeightReference h,
            long seed,
            SettingTable table,
            int[] bottoms,
            TerrainBlock[] blocks) {
        int[][] chunks = new int[160][];
        for (int i = 0; i < chunks.length; i++) chunks[i] = new int[] {i * 3 - 240, i * 2 - 160};
        Assembler<TerrainMap.Sample> crust = CrustShape.assembler(maps.surfaces, h);
        AssembledColumn reused = new AssembledColumn();
        TerrainMap.Sample s = new TerrainMap.Sample();
        int[][][] forward = new int[chunks.length][256][];
        for (int i = 0; i < chunks.length; i++)
            for (int index = 0; index < 256; index++) {
                int x = chunks[i][0] * 16 + (index & 15), z = chunks[i][1] * 16 + (index >> 4);
                maps.terrain.sample(x, z, s);
                maps.terrain.caves.carve(x, z, s);
                AssembledColumn c = crust.assemble(s, reused);
                String where = x + "," + z + " seed " + seed + " carved";
                wellFormed(c, h, table, where);
                parity(c, s, maps.surfaces, h, bottoms, blocks, index % 16 == 0, where);
                forward[i][index] = snapshot(c);
            }
        GenerationMaps fresh = new GenerationMaps(seed);
        Assembler<TerrainMap.Sample> again = CrustShape.assembler(fresh.surfaces, h);
        for (int i = chunks.length - 1; i >= 0; i--)
            for (int index = 255; index >= 0; index--) {
                int x = chunks[i][0] * 16 + (index & 15), z = chunks[i][1] * 16 + (index >> 4);
                fresh.terrain.sample(x, z, s);
                fresh.terrain.caves.carve(x, z, s);
                require(
                        Arrays.equals(
                                forward[i][index],
                                snapshot(again.assemble(s, new AssembledColumn()))),
                        "Reverse-order cold column differs at " + x + "," + z + " seed " + seed);
            }
        return chunks.length;
    }

    /**
     * The assembled column equals ColumnBlocks at every boundary of either (both are constant
     * between their boundaries), and at every Y when full is set; returns 1 for a full check.
     */
    private static int parity(
            AssembledColumn c,
            TerrainMap.Sample s,
            SurfaceRules rules,
            TerrainHeightReference h,
            int[] bottoms,
            TerrainBlock[] blocks,
            boolean full,
            String where) {
        boolean bare = ColumnBlocks.bareStone(s, rules);
        int count = ColumnBlocks.runs(s, bare, rules, h, bottoms, blocks);
        for (int i = 0; i < count; i++)
            for (int y : new int[] {bottoms[i], bottoms[i + 1] - 1})
                require(
                        c.at(y) == blocks[i],
                        "Assembled "
                                + c.at(y)
                                + " != ColumnBlocks "
                                + blocks[i]
                                + " at "
                                + where
                                + " y "
                                + y);
        for (int i = 0; i < c.count(); i++)
            for (int y : new int[] {c.bottom(i), c.top(i) - 1}) {
                TerrainBlock expected = ColumnBlocks.at(y, s, bare, rules, h);
                require(
                        c.runBlock(i) == expected,
                        "Assembled "
                                + c.runBlock(i)
                                + " != ColumnBlocks "
                                + expected
                                + " at "
                                + where
                                + " y "
                                + y);
            }
        if (!full) return 0;
        for (int y = h.minimumY; y < h.maximumYExclusive; y++)
            require(
                    c.at(y) == ColumnBlocks.at(y, s, bare, rules, h),
                    "Assembled differs from ColumnBlocks at " + where + " y " + y);
        return 1;
    }
}
