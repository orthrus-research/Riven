package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.AssembledColumn;
import cc.orthrus.riven.worldgen.engine.Assembler;
import cc.orthrus.riven.worldgen.engine.Flags;
import cc.orthrus.riven.worldgen.engine.Horizons;
import cc.orthrus.riven.worldgen.engine.Role;
import cc.orthrus.riven.worldgen.engine.SettingTable;
import cc.orthrus.riven.worldgen.engine.Settings;
import cc.orthrus.riven.worldgen.engine.Shape;
import cc.orthrus.riven.worldgen.engine.Zone;

import java.util.Arrays;
import java.util.TreeMap;

/**
 * Assembled crust columns are well formed and pure. Usage: AssembledColumnCheck [seed ...].
 * Samples a coarse grid plus dense coastal and wonder strips per seed, every column around the
 * first 12 stacks, arches and sea caves (the coast forms that hold volumes), and every column of
 * chunk columns along a diagonal crossing land and coast (carved as native cubes carve them), and
 * requires: runs cover the crust without gaps or overlaps, and neighbours differ; at(y) and
 * role(y) equal the runs at every boundary; a column assembled cold, from the reused buffer and
 * in reverse order is the same column; soil roles lie only in their layer under the sky's floor;
 * every run's role is one its pass writes; nothing above the highest surface but air, and no soil
 * or deposit below the lowest soil. In carved chunk columns: every carved block's family comes from
 * a carve form its sub-zone allows (riven-zones.json); nothing but an entrance cuts the sky's roof
 * band; carved space holds lava only at or below the lava horizon and water or ice only in an
 * aquifer's band under its level, a cave pool or a flood cell's band. Native chunk columns store
 * exactly the assembled columns (every cube, block and role), and a cache filled in reverse order
 * returns identical columns, ores and unstable floors included.
 */
public final class AssembledColumnCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    /** The carve forms that open each family (by CaveMap.FAMILIES index). */
    private static final String[][] FORMS_OF = {
        {},
        {"basement-cavern", "cavern"},
        {"pillar-hall"},
        {"root-tunnel", "basement-tunnel", "tunnel"},
        {"karst"},
        {"karst"},
        {"fissure"},
        {"dome"},
        {"salt-hall"},
        {"lava-tube"},
        {"chamber-room"},
        {"bedding-plane"},
        {"joint-maze"},
        {"vein-gallery"},
        {"lava-river"},
        {"geode"}
    };

    /** Carved blocks per sub-zone over every carved chunk column. */
    private static final TreeMap<String, Long> CARVED = new TreeMap<>();

    /**
     * B2's cave rules in a carved column: families from the forms their sub-zone allows, the roof
     * band held but at an entrance, and fills by the engine's precedence.
     */
    private static void caves(
            AssembledColumn c,
            TerrainMap.Sample s,
            byte[] families,
            Assembler<TerrainMap.Sample> crust,
            TerrainHeightReference h,
            String where) {
        Zone zone = crust.zone;
        CaveMap.Voids v = s.caves;
        for (int y = h.minimumY; y < h.maximumYExclusive; y++) {
            int f = families[y - h.minimumY];
            if (f == 0) continue;
            String sub = crust.assembly.zones.name(c.runZone(c.run(y)));
            CARVED.merge(sub, 1L, Long::sum);
            boolean allowed = false;
            for (int k = 0; k < zone.subs(); k++)
                if (zone.sub(k).id.equals(sub))
                    for (String form : FORMS_OF[f]) allowed |= zone.sub(k).allows(form);
            require(
                    allowed,
                    CaveMap.FAMILIES[f] + " in sub-zone " + sub + " at " + where + " y " + y);
        }
        int ground = c.horizon(Horizons.GROUND),
                band = zone.cavity(0).roof(c.flag(Flags.WET) ? 1 << Flags.WET : 0),
                lava = c.horizon(Horizons.LAVA),
                pocketFloor = c.horizon(Horizons.POCKET_FLOOR),
                pocketRoof = c.horizon(Horizons.POCKET_ROOF),
                pocketLevel = c.horizon(Horizons.POCKET_LEVEL),
                floodFloor = c.horizon(Horizons.FLOOD_FLOOR),
                floodLevel = c.horizon(Horizons.FLOOD_LEVEL);
        for (int i = 0; i < c.count(); i++) {
            if (c.runPass(i) != Shape.Pass.CAVES || !c.runRole(i).open()) continue;
            TerrainBlock block = c.runBlock(i);
            // A run reaching the sky's floor merges with the sky's air above, which is not a cave.
            for (int y = c.bottom(i); y < Math.min(c.top(i), ground + 1); y++) {
                if (y > ground - band)
                    require(
                            v.entrances > 0 && y >= v.entranceLo[0] && y < v.entranceHi[0],
                            "Cave cuts the roof band at " + where + " y " + y);
                boolean underLava = lava != Horizons.ABSENT && y <= lava;
                boolean pocket =
                        pocketFloor != Horizons.ABSENT
                                && y >= pocketFloor
                                && y < pocketRoof
                                && y <= pocketLevel;
                boolean pool = false;
                for (int k = 0; k < v.pools; k++) pool |= y >= v.poolLo[k] && y < v.poolHi[k];
                boolean flood = floodFloor != Horizons.ABSENT && y > floodFloor && y <= floodLevel;
                if (block == TerrainBlock.LAVA)
                    require(underLava, "Lava above the lava horizon at " + where + " y " + y);
                else if (block == TerrainBlock.AIR)
                    require(!underLava, "Air below the lava horizon at " + where + " y " + y);
                else
                    require(
                            !underLava && (pocket || pool || flood),
                            block
                                    + " outside a pocket, pool or flood band at "
                                    + where
                                    + " y "
                                    + y);
            }
        }
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
        TreeMap<String, Integer> coverage = new TreeMap<>(), settings = new TreeMap<>();
        long columns = 0, chunkColumns = 0, runs = 0;
        int maximumRuns = 0, materialDepth = 0;
        AssembledColumn reused = new AssembledColumn();
        for (long seed : seeds) {
            GenerationMaps maps = new GenerationMaps(seed);
            TerrainMap terrain = maps.terrain;
            Assembler<TerrainMap.Sample> crust = maps.columns.assembler();
            SettingTable table = crust.settings();
            materialDepth = crust.materialDepth();
            int minimumSoilY = terrain.minimumSurfaceY - materialDepth + 1;
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
                        columns++;
                        column(
                                maps,
                                crust,
                                table,
                                s,
                                reused,
                                (x + dx) + "," + z + " seed " + seed,
                                minimumSoilY,
                                h,
                                columns,
                                coverage,
                                settings);
                        runs += reused.count();
                        maximumRuns = Math.max(maximumRuns, reused.count());
                    }
                }
            // Every column of a patch around the first few forms that hold volumes.
            for (CoastForms.View v : terrain.rocky.placed(-4096, -4096, 4096, 4096)) {
                if (v.form != CoastForms.Form.STACK_ARCH && v.form != CoastForms.Form.SEA_CAVE)
                    continue;
                if (patches++ == 12) break;
                int cx = (int) Math.floor(v.x + v.nx * v.centerD),
                        cz = (int) Math.floor(v.z + v.nz * v.centerD);
                for (int wz = cz - 24; wz < cz + 24; wz++)
                    for (int wx = cx - 24; wx < cx + 24; wx++) {
                        terrain.sample(wx, wz, s);
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
                                columns,
                                coverage,
                                settings);
                        runs += reused.count();
                        maximumRuns = Math.max(maximumRuns, reused.count());
                    }
            }
            chunkColumns += chunks(maps, h, seed, table);
        }
        System.out.println(
                "PASS native chunk columns: carved columns well formed, families from their"
                        + " sub-zone's forms, roof band held but at entrances, fills by"
                        + " precedence; stored cubes, blocks and roles = assembled, cold = reused ="
                        + " reverse order (ores and unstable floors too); chunkColumns="
                        + chunkColumns
                        + " carved by sub-zone="
                        + CARVED);
        System.out.println(
                "PASS assembled columns: cover the crust, at = runs, roles legal per pass, soil"
                        + " only in its layer; materialDepth="
                        + materialDepth
                        + " columns="
                        + columns
                        + " runs="
                        + runs
                        + " maxRuns="
                        + maximumRuns
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
        settings.merge(Settings.name(c.setting()), 1, Integer::sum);
        if (s.ocean.marine) coverage.merge("marine", 1, Integer::sum);
        if (s.water.wet) coverage.merge("wet", 1, Integer::sum);
        if (s.water.aquiferMin <= s.water.aquiferMax) coverage.merge("aquifer", 1, Integer::sum);
        if (s.sediment.weight > 0) coverage.merge("sediment", 1, Integer::sum);
        if (s.rocky.weight > 0) coverage.merge("rocky", 1, Integer::sum);
        if (s.rocky.hasVolume()) coverage.merge("rockyVolume", 1, Integer::sum);
        if (s.wonders.family != null) coverage.merge("wonder", 1, Integer::sum);
        if (s.wonders.palette != null) coverage.merge("wonderStrata", 1, Integer::sum);
        if (s.wonders.hasVoid()) coverage.merge("wonderVoid", 1, Integer::sum);
        if (s.caves.count() > 0) coverage.merge("caveEntrance", 1, Integer::sum);
        if (c.subsoilSetting() != c.setting()) coverage.merge("inheritedSubsoil", 1, Integer::sum);
    }

    /**
     * Carved chunk columns along a diagonal: each column assembled with the reused buffer is well
     * formed and is what the cached native chunk column stores; a fresh cache over fresh maps,
     * filled in reverse order, assembles and stores the same columns.
     */
    private static int chunks(
            GenerationMaps maps, TerrainHeightReference h, long seed, SettingTable table) {
        int[][] chunks = new int[160][];
        for (int i = 0; i < chunks.length; i++) chunks[i] = new int[] {i * 3 - 240, i * 2 - 160};
        Assembler<TerrainMap.Sample> crust = maps.columns.assembler();
        AssembledColumn reused = new AssembledColumn();
        TerrainMap.Sample s = new TerrainMap.Sample();
        NativeColumns.Column[] forward = new NativeColumns.Column[chunks.length];
        int[][][] assembled = new int[chunks.length][256][];
        byte[] families = new byte[h.maximumYExclusive - h.minimumY];
        for (int i = 0; i < chunks.length; i++) {
            forward[i] = maps.columns.column(chunks[i][0], chunks[i][1]);
            for (int index = 0; index < 256; index++) {
                int x = chunks[i][0] * 16 + (index & 15), z = chunks[i][1] * 16 + (index >> 4);
                maps.terrain.sample(x, z, s);
                Arrays.fill(families, (byte) 0);
                maps.terrain.caves.carve(x, z, s, families);
                AssembledColumn c = crust.assemble(s, reused);
                String where = x + "," + z + " seed " + seed + " carved";
                wellFormed(c, h, table, where);
                caves(c, s, families, crust, h, where);
                stored(forward[i], index, c, h, index % 16 == 0, where);
                assembled[i][index] = snapshot(c);
            }
        }
        GenerationMaps fresh = new GenerationMaps(seed);
        Assembler<TerrainMap.Sample> again = fresh.columns.assembler();
        for (int i = chunks.length - 1; i >= 0; i--) {
            NativeColumns.Column back = fresh.columns.column(chunks[i][0], chunks[i][1]);
            for (int index = 255; index >= 0; index--) {
                int x = chunks[i][0] * 16 + (index & 15), z = chunks[i][1] * 16 + (index >> 4);
                fresh.terrain.sample(x, z, s);
                fresh.terrain.caves.carve(x, z, s);
                AssembledColumn c = again.assemble(s, new AssembledColumn());
                String where = x + "," + z + " seed " + seed;
                require(
                        Arrays.equals(assembled[i][index], snapshot(c)),
                        "Reverse-order cold column differs at " + where);
                same(forward[i], back, index, c, x, z, h, index % 16 == 0, where);
            }
        }
        return chunks.length;
    }

    /**
     * A native chunk column stores the assembled column: its block and role at every run boundary,
     * and every cube's blocks when full is set.
     */
    private static void stored(
            NativeColumns.Column column,
            int index,
            AssembledColumn c,
            TerrainHeightReference h,
            boolean full,
            String where) {
        for (int i = 0; i < c.count(); i++)
            for (int y : new int[] {c.bottom(i), c.top(i) - 1})
                require(
                        column.block(index, y) == c.runBlock(i)
                                && column.role(index, y) == c.runRole(i),
                        "Stored column differs from the assembled one at " + where + " y " + y);
        if (!full) return;
        TerrainBlock[] line = new TerrainBlock[16];
        for (int bottom = h.minimumY - 16; bottom < h.maximumYExclusive + 16; bottom += 16) {
            column.cube(index, bottom, line);
            for (int y = 0; y < 16; y++)
                require(
                        line[y] == c.at(bottom + y),
                        "Stored cube differs at " + where + " y " + (bottom + y));
        }
    }

    /**
     * Two native chunk columns, one from each cache, are the same column: blocks, roles, ores and
     * unstable floors at every run boundary, and at every Y when full is set.
     */
    private static void same(
            NativeColumns.Column a,
            NativeColumns.Column b,
            int index,
            AssembledColumn c,
            int x,
            int z,
            TerrainHeightReference h,
            boolean full,
            String where) {
        for (int i = 0; i < c.count(); i++)
            for (int y : new int[] {c.bottom(i), c.top(i) - 1}) same(a, b, index, x, y, z, where);
        if (full)
            for (int y = h.minimumY; y < h.maximumYExclusive; y++)
                same(a, b, index, x, y, z, where);
    }

    private static void same(
            NativeColumns.Column a,
            NativeColumns.Column b,
            int index,
            int x,
            int y,
            int z,
            String where) {
        require(
                a.block(index, y) == b.block(index, y)
                        && a.role(index, y) == b.role(index, y)
                        && a.ore(index, x, y, z) == b.ore(index, x, y, z)
                        && a.unstable(index, y) == b.unstable(index, y),
                "Reverse-order cache differs at " + where + " y " + y);
    }
}
