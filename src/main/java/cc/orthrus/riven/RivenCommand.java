package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RealmMap;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Read-only inspection of authoritative climate and the actual generated column. */
final class RivenCommand extends CommandBase {
    @Override
    public String getName() {
        return "riven";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/riven [x z] | /riven find <biome> [radius] | /riven trees [radius] | /riven cover [radius] | /riven boulders [radius] | /riven digest [radius [x z]] | /riven animals [radius] | /riven biomes | /riven mappings | /riven shaderprops [player] | /riven effects [reload]";
    }

    /**
     * Riven's biomes with their numeric IDs, parent classes and intended shader category, and the
     * biome ID budget. /riven shaderprops reports the category Demonica actually gives each.
     */
    private static void biomes(ICommandSender sender) {
        int used = 0;
        for (int id = 0; id < 256; id++)
            if (net.minecraft.world.biome.Biome.getBiome(id) != null) used++;
        StringBuilder line = new StringBuilder();
        int n = 0;
        for (net.minecraft.world.biome.Biome biome : RivenBiomes.list()) {
            cc.orthrus.riven.worldgen.RivenBiomeTable.Definition d =
                    ((RivenBiomeTypes.RivenBiome) biome).definition();
            line.append(
                    String.format(
                            Locale.ROOT,
                            "%s#%d %s/%s; ",
                            d.id,
                            net.minecraft.world.biome.Biome.getIdForBiome(biome),
                            biome.getClass().getSuperclass().getSimpleName(),
                            d.category));
            if (++n % 4 == 0) {
                sender.sendMessage(new TextComponentString("Riven biomes: " + line));
                line.setLength(0);
            }
        }
        if (line.length() > 0) sender.sendMessage(new TextComponentString("Riven biomes: " + line));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven biomes: %d riven:, %d of 256 biome IDs used, %d free",
                                RivenBiomes.list().size(),
                                used,
                                256 - used)));
    }

    /** The world's recorded definitions against what the current layers would resolve. */
    private static void mappings(ICommandSender sender, World world) throws CommandException {
        Mappings.Snapshot recorded = RivenWorldType.snapshot(world);
        if (recorded == null) throw new CommandException("Not a Riven cubic world");
        Mappings.Snapshot current = Mappings.resolve();
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven mappings: world %s from %s, %d roles, %d biome colour overrides, fallbacks %s",
                                recorded.fingerprint,
                                recorded.layers,
                                recorded.roles.size(),
                                recorded.colours.size(),
                                recorded.fallbacks)));
        sender.sendMessage(
                new TextComponentString(
                        current.fingerprint.equals(recorded.fingerprint)
                                ? "Riven mappings: the current layers resolve to the same definitions"
                                : "Riven mappings: the current layers resolve to "
                                        + current.fingerprint
                                        + " from "
                                        + current.layers
                                        + "; they apply to new worlds only"));
    }

    /** Nearest column of a biome, on rings 32 blocks apart around the sender. */
    private static void find(ICommandSender sender, ClimateBiomeProvider provider, String[] args)
            throws CommandException {
        String name = args[1];
        int radius = args.length > 2 ? parseInt(args[2], 32, 30000) : 4096;
        BlockPos at = sender.getPosition();
        TerrainMap.Sample sample = new TerrainMap.Sample();
        // A depth biome is found at the highest cell centre of a column that reports it.
        RealmMap.Depth wanted = null;
        for (RealmMap.Depth depth : RealmMap.depths())
            if (("riven:" + depth.biome).equals(name)) wanted = depth;
        for (int r = 0; r <= radius; r += 32)
            for (int dz = -r; dz <= r; dz += 32)
                for (int dx = -r; dx <= r; dx += 32) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = at.getX() + dx, z = at.getZ() + dz;
                    provider.maps.terrain.sample(x, z, sample);
                    if (wanted != null) {
                        int top = sample.blockY() - RealmMap.surfaceBand();
                        for (int y = Math.floorDiv(top - 2, 4) * 4 + 2;
                                y > TerrainHeightReference.DEFAULT.minimumY;
                                y -= 4)
                            if (RealmMap.depth(y, sample) == wanted) {
                                sender.sendMessage(
                                        new TextComponentString(
                                                String.format(
                                                        Locale.ROOT,
                                                        "Riven find %s: x=%d y=%d z=%d ground=%d"
                                                                + " distance=%d",
                                                        name,
                                                        x,
                                                        y,
                                                        z,
                                                        sample.blockY(),
                                                        r)));
                                return;
                            }
                        continue;
                    }
                    net.minecraft.world.biome.Biome biome = provider.biome(sample, x, z);
                    if (biome.getRegistryName() != null
                            && biome.getRegistryName().toString().equals(name)) {
                        sender.sendMessage(
                                new TextComponentString(
                                        String.format(
                                                Locale.ROOT,
                                                "Riven find %s: x=%d z=%d ground=%d distance=%d",
                                                name,
                                                x,
                                                z,
                                                sample.blockY(),
                                                r)));
                        return;
                    }
                }
        sender.sendMessage(
                new TextComponentString("Riven find " + name + ": none within " + radius));
    }

    /**
     * Tree parity around the sender: the preview's trunks and crowns against the logs and leaves
     * native cubes hold, per block, over columns whose cubes are loaded; then each site,
     * grown when every log of its tree stands in the world.
     */
    private static void trees(ICommandSender sender, World world, String[] args)
            throws CommandException {
        int radius = args.length > 1 ? parseInt(args[1], 1, 128) : 48;
        TreeSites sites = RivenWorldType.trees(world);
        if (sites == null) throw new CommandException("Riven tree sites need a Riven cubic world");
        BlockPos at = sender.getPosition();
        long logs = 0, leaves = 0, previewOnly = 0, worldOnly = 0, columns = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = at.getZ() - radius; z <= at.getZ() + radius; z++)
            for (int x = at.getX() - radius; x <= at.getX() + radius; x++) {
                TreeSites.Recorder column = sites.previewColumn(x, z);
                int ground = column.ground();
                if (ground == Integer.MIN_VALUE) continue;
                pos.setPos(x, ground, z);
                if (!world.isBlockLoaded(pos)
                        || !world.isBlockLoaded(pos.setPos(x, ground + TREE_SCAN, z))) continue;
                columns++;
                // Hanging strands may reach a few blocks below a slope's ground.
                for (int y = ground - 4; y < ground + TREE_SCAN; y++) {
                    int kind = kind(column.at(y));
                    int found = kind(world.getBlockState(pos.setPos(x, y, z)));
                    if (kind != 0 && kind == found) {
                        if (kind == 1) logs++;
                        else leaves++;
                    } else {
                        if (kind != 0) previewOnly++;
                        if (found != 0) worldOnly++;
                    }
                }
            }
        long[] tally = new long[2]; // sites, grown
        sites.sites(
                at.getX() - radius,
                at.getZ() - radius,
                at.getX() + radius + 1,
                at.getZ() + radius + 1,
                site -> {
                    tally[0]++;
                    boolean[] grown = {true};
                    sites.blocks(
                            site,
                            (bx, by, bz, state, kind) -> {
                                if (kind != TreeSites.LOG) return;
                                BlockPos p = new BlockPos(bx, by, bz);
                                if (world.isBlockLoaded(p) && kind(world.getBlockState(p)) != 1)
                                    grown[0] = false;
                            });
                    if (grown[0]) tally[1]++;
                });
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven trees r=%d: columns=%d matched logs=%d leaves=%d previewOnly=%d worldOnly=%d sites=%d grown=%d",
                                radius,
                                columns,
                                logs,
                                leaves,
                                previewOnly,
                                worldOnly,
                                tally[0],
                                tally[1])));
        sender.sendMessage(
                new TextComponentString(
                        "Riven tree cells: "
                                + sites.reasons(
                                        at.getX() - radius,
                                        at.getZ() - radius,
                                        at.getX() + radius + 1,
                                        at.getZ() + radius + 1)));
    }

    /**
     * Ground cover parity around the sender: every ground cover block the preview draws against
     * the world, near each loaded column's ground, and how many columns carry cover. Submerged
     * plants, which the preview draws as their water, are counted apart.
     */
    private static void cover(ICommandSender sender, World world, String[] args)
            throws CommandException {
        int radius = args.length > 1 ? parseInt(args[1], 1, 128) : 32;
        TreeSites sites = RivenWorldType.trees(world);
        GroundCover cover = RivenWorldType.cover(world);
        if (sites == null || cover == null)
            throw new CommandException("Riven ground cover needs a Riven cubic world");
        BlockPos at = sender.getPosition();
        long matched = 0, previewOnly = 0, worldOnly = 0, columns = 0, covered = 0, grounds = 0;
        long submerged = 0;
        String groundExample = null;
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        // Each mismatched block kind with the first position it was seen at.
        java.util.Map<String, String> mismatches = new java.util.TreeMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = at.getZ() - radius; z <= at.getZ() + radius; z++)
            for (int x = at.getX() - radius; x <= at.getX() + radius; x++) {
                TreeSites.Recorder column = sites.previewColumn(x, z, cover);
                int ground = column.ground();
                if (ground == Integer.MIN_VALUE) continue;
                if (!world.isBlockLoaded(pos.setPos(x, ground - 8, z))
                        || !world.isBlockLoaded(pos.setPos(x, ground + 8, z))) continue;
                columns++;
                // The ground block itself: the terrain both paths draw under any cover.
                net.minecraft.block.state.IBlockState top = column.at(ground - 1),
                        below = world.getBlockState(pos.setPos(x, ground - 1, z));
                if (top != null && !top.equals(below)) {
                    grounds++;
                    if (groundExample == null)
                        groundExample =
                                x + " " + (ground - 1) + " " + z + ": " + top + " / " + below;
                }
                boolean any = false;
                for (int y = ground - 8; y < ground + 8; y++) {
                    net.minecraft.block.state.IBlockState preview = column.at(y);
                    net.minecraft.block.state.IBlockState found =
                            world.getBlockState(pos.setPos(x, y, z));
                    boolean p = preview != null && cover.places(preview.getBlock());
                    if (found.getBlock() instanceof cc.orthrus.riven.content.SubmergedPlant) {
                        submerged++;
                        continue;
                    }
                    boolean w = cover.places(found.getBlock());
                    if (p && preview.equals(found)) {
                        matched++;
                        any = true;
                        kinds.merge(
                                preview.getBlock().getRegistryName().getPath(), 1, Integer::sum);
                    } else {
                        if (p) previewOnly++;
                        if (w) worldOnly++;
                        if ((p || w) && !mismatches.containsKey(found + "|" + preview)) {
                            // What each path decided from: the biome and the block below.
                            ClimateBiomeProvider provider =
                                    (ClimateBiomeProvider) world.getBiomeProvider();
                            cc.orthrus.riven.worldgen.TerrainMap.Sample sample =
                                    new cc.orthrus.riven.worldgen.TerrainMap.Sample();
                            provider.maps.terrain.sample(x, z, sample);
                            int cx = x >> 4, cz = z >> 4, index = ((z & 15) << 4) | (x & 15);
                            cc.orthrus.riven.worldgen.NativeColumns.Column native_ =
                                    provider.maps.columns.column(cx, cz);
                            mismatches.put(
                                    found + "|" + preview,
                                    String.format(
                                            Locale.ROOT,
                                            "at %d %d %d: below preview=%s world=%s; biome sample=%s column=%s; surface=%d",
                                            x,
                                            y,
                                            z,
                                            column.at(y - 1),
                                            world.getBlockState(new BlockPos(x, y - 1, z)),
                                            provider.biome(sample, x, z).getRegistryName(),
                                            provider.biome(native_, index, x, z).getRegistryName(),
                                            native_.surface(index)));
                        }
                    }
                }
                if (any) covered++;
            }
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven cover r=%d: columns=%d covered=%d matched=%d previewOnly=%d worldOnly=%d groundDiffers=%d submerged=%d (%s)",
                                radius,
                                columns,
                                covered,
                                matched,
                                previewOnly,
                                worldOnly,
                                grounds,
                                submerged,
                                groundExample)));
        java.util.List<java.util.Map.Entry<String, Integer>> top =
                new java.util.ArrayList<>(kinds.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        sender.sendMessage(
                new TextComponentString(
                        "Riven cover kinds: " + top.subList(0, Math.min(12, top.size()))));
        if (!mismatches.isEmpty())
            sender.sendMessage(new TextComponentString("Riven cover mismatches: " + mismatches));
    }

    /**
     * One column through both generation paths: the native column's runs, habitat key and biome
     * against the preview sample's, and the preview against the world near the ground.
     */
    private static void column(ICommandSender sender, World world, int x, int z) {
        ClimateBiomeProvider provider = (ClimateBiomeProvider) world.getBiomeProvider();
        cc.orthrus.riven.worldgen.TerrainMap.Sample sample =
                new cc.orthrus.riven.worldgen.TerrainMap.Sample();
        provider.maps.terrain.sample(x, z, sample);
        int index = ((z & 15) << 4) | (x & 15);
        cc.orthrus.riven.worldgen.NativeColumns.Column native_ =
                provider.maps.columns.column(x >> 4, z >> 4);
        int surface = native_.surface(index);
        StringBuilder out = new StringBuilder();
        out.append(
                String.format(
                        Locale.ROOT,
                        "Riven column %d %d: surface=%d key native=%s sample=%s; biome native=%s sample=%s",
                        x,
                        z,
                        surface,
                        native_.key(index),
                        cc.orthrus.riven.worldgen.Habitats.key(sample),
                        provider.biome(native_, index, x, z).getRegistryName(),
                        provider.biome(sample, x, z).getRegistryName()));
        sender.sendMessage(new TextComponentString(out.toString()));
        cc.orthrus.riven.worldgen.TerrainBlock[] line =
                new cc.orthrus.riven.worldgen.TerrainBlock[16];
        // The preview's column (uncarved), as the engine assembles it.
        cc.orthrus.riven.worldgen.engine.AssembledColumn assembled =
                provider.maps
                        .columns
                        .assembler()
                        .assemble(sample, new cc.orthrus.riven.worldgen.engine.AssembledColumn());
        int count = assembled.count();
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "  assembled %d runs in zone %s: setting %s, subsoil from %s",
                                count,
                                provider.maps.columns.assembler().zone.id,
                                assembled.setting() < 0
                                        ? "none"
                                        : cc.orthrus.riven.worldgen.engine.Settings.name(
                                                assembled.setting()),
                                assembled.subsoilSetting() < 0
                                        ? "none"
                                        : cc.orthrus.riven.worldgen.engine.Settings.name(
                                                assembled.subsoilSetting()))));
        TreeSites sites = RivenWorldType.trees(world);
        TreeSites.Recorder preview = sites == null ? null : sites.previewColumn(x, z);
        for (int y = surface - 5; y <= surface + 1; y++) {
            native_.cube(index, Math.floorDiv(y, 16) * 16, line);
            int run = assembled.run(y);
            sender.sendMessage(
                    new TextComponentString(
                            String.format(
                                    Locale.ROOT,
                                    "  y=%d terrain native=%s (%s) sample=%s (%s, zone %s) | preview=%s world=%s",
                                    y,
                                    line[Math.floorMod(y, 16)],
                                    native_.role(index, y),
                                    assembled.at(y),
                                    assembled.role(y),
                                    run < 0
                                            ? "none"
                                            : provider.maps
                                                    .columns
                                                    .assembler()
                                                    .assembly
                                                    .zones
                                                    .name(assembled.runZone(run)),
                                    preview == null ? null : preview.at(y),
                                    world.getBlockState(new BlockPos(x, y, z)))));
        }
    }

    /** Blocks above a column's ground the tree parity check compares (the tallest trees fit). */
    private static final int TREE_SCAN = 48;

    /**
     * Compares every boulder site near the sender with the world: each rock block the site holds,
     * and the block above each column's rock, which must not be that rock.
     */
    private static void boulders(
            ICommandSender sender, World world, ClimateBiomeProvider biomes, String[] args)
            throws CommandException {
        int radius = args.length > 1 ? parseInt(args[1], 1, 256) : 64;
        BlockPos at = sender.getPosition();
        long[] tally = new long[5]; // sites, loaded sites, matched, missing, overgrown
        Map<String, Integer> kinds = new TreeMap<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        biomes.maps.boulders.sites(
                at.getX() - radius,
                at.getZ() - radius,
                at.getX() + radius + 1,
                at.getZ() + radius + 1,
                site -> {
                    tally[0]++;
                    kinds.merge(site.kind, 1, Integer::sum);
                    if (!world.isBlockLoaded(pos.setPos(site.x, at.getY(), site.z))) return;
                    tally[1]++;
                    net.minecraft.block.state.IBlockState rock = TerrainColumn.state(site.rock);
                    for (int z = site.z - site.radius; z <= site.z + site.radius; z++)
                        for (int x = site.x - site.radius; x <= site.x + site.radius; x++) {
                            int bottom = site.bottom(x, z);
                            if (bottom == Integer.MIN_VALUE) continue;
                            int top = site.top(x, z);
                            for (int y = bottom; y < top; y++) {
                                net.minecraft.block.state.IBlockState found =
                                        world.getBlockState(pos.setPos(x, y, z));
                                if (found == rock) tally[2]++;
                                else {
                                    tally[3]++;
                                    if (missing.size() < 4)
                                        missing.add(x + " " + y + " " + z + " " + found);
                                }
                            }
                            if (world.getBlockState(pos.setPos(x, top, z)) == rock) tally[4]++;
                        }
                });
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven boulders r=%d: sites=%d loaded=%d %s; rock matched=%d missing=%d; rock above a column's top=%d",
                                radius,
                                tally[0],
                                tally[1],
                                kinds,
                                tally[2],
                                tally[3],
                                tally[4])));
        if (!missing.isEmpty())
            sender.sendMessage(new TextComponentString("Riven boulders missing: " + missing));
    }

    /**
     * The first animals of the chunk columns near the sender against the world: those planned, those
     * whose feet cube is loaded, those the world carries there, and of those the ones found by their
     * origin among the loaded entities; also the loaded creatures without an origin, and the planned
     * species.
     */
    private static void animals(ICommandSender sender, World world, String[] args)
            throws CommandException {
        int radius = args.length > 1 ? parseInt(args[1], 1, 256) : 48;
        FirstAnimals animals = RivenWorldType.animals(world);
        if (animals == null) throw new CommandException("Riven animals need a Riven cubic world");
        java.util.Set<String> origins = new java.util.HashSet<>();
        int untagged = 0;
        for (net.minecraft.entity.Entity e : world.loadedEntityList) {
            String origin = e.getEntityData().getString(FirstAnimals.ORIGIN);
            if (!origin.isEmpty()) origins.add(origin);
            else if (e instanceof net.minecraft.entity.passive.EntityAnimal) untagged++;
        }
        BlockPos at = sender.getPosition();
        int planned = 0, loaded = 0, fitting = 0, found = 0, foundUnfit = 0;
        Map<String, Integer> species = new TreeMap<>();
        for (int cz = (at.getZ() - radius) >> 4; cz <= (at.getZ() + radius) >> 4; cz++)
            for (int cx = (at.getX() - radius) >> 4; cx <= (at.getX() + radius) >> 4; cx++)
                for (FirstAnimals.Animal a : animals.plan(cx, cz, Integer.MIN_VALUE)) {
                    planned++;
                    species.merge(a.kind.mob, 1, Integer::sum);
                    BlockPos feet = new BlockPos(a.x, a.y, a.z);
                    if (!world.isBlockLoaded(feet.down()) || !world.isBlockLoaded(feet.up()))
                        continue;
                    loaded++;
                    boolean fits = FirstAnimals.fits(world, feet),
                            there = origins.contains(a.origin);
                    if (fits) fitting++;
                    if (there && fits) found++;
                    if (there && !fits) foundUnfit++;
                }
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven animals r=%d: planned=%d loaded=%d fitting=%d found=%d missing=%d foundUnfit=%d untagged=%d species=%s; population cubes=%d members=%d fitting=%d spawned=%d refused=%d",
                                radius,
                                planned,
                                loaded,
                                fitting,
                                found,
                                fitting - found,
                                foundUnfit,
                                untagged,
                                species,
                                FirstAnimals.COUNTS[0],
                                FirstAnimals.COUNTS[1],
                                FirstAnimals.COUNTS[2],
                                FirstAnimals.COUNTS[3],
                                FirstAnimals.COUNTS[4])));
    }

    /** Blocks below a chunk column's lowest surface and above its highest that a digest hashes. */
    private static final int DIGEST_BELOW = 32, DIGEST_ABOVE = 48;

    /**
     * A digest of the world's blocks around a point, to compare two worlds of one seed block for
     * block: each chunk column within the radius whose band (its lowest surface less DIGEST_BELOW
     * up to its highest plus DIGEST_ABOVE, from the generator's own columns, so both worlds hash
     * the same blocks) is wholly loaded is hashed by block state names, one line per chunk column
     * in {@code riven/digests/} of the world's save, and the lines are hashed into one total.
     */
    private static void digest(ICommandSender sender, World world, String[] args)
            throws CommandException {
        int radius = args.length > 1 ? parseInt(args[1], 1, 256) : 48;
        int x =
                args.length > 3
                        ? parseInt(args[2], -29999984, 29999984)
                        : sender.getPosition().getX();
        int z =
                args.length > 3
                        ? parseInt(args[3], -29999984, 29999984)
                        : sender.getPosition().getZ();
        ClimateBiomeProvider provider = (ClimateBiomeProvider) world.getBiomeProvider();
        Map<net.minecraft.block.state.IBlockState, byte[]> names = new java.util.HashMap<>();
        java.security.MessageDigest chunk = sha256(), total = sha256();
        java.util.Set<String> chunks = new java.util.HashSet<>();
        StringBuilder lines = new StringBuilder();
        int hashed = 0, skipped = 0;
        long blocks = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++)
            for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
                if (!ClimateBiomeProvider.inWorld(cx * 16L, cz * 16L)) continue;
                cc.orthrus.riven.worldgen.NativeColumns.Column column =
                        provider.maps.columns.column(cx, cz);
                int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                for (int i = 0; i < 256; i++) {
                    low = Math.min(low, column.surface(i));
                    high = Math.max(high, column.surface(i));
                }
                int bottom = low - DIGEST_BELOW, top = high + DIGEST_ABOVE;
                boolean loaded = true;
                for (int y = Math.floorDiv(bottom, 16) * 16; y <= top && loaded; y += 16)
                    loaded = world.isBlockLoaded(pos.setPos(cx * 16, y, cz * 16));
                if (!loaded) {
                    skipped++;
                    continue;
                }
                chunk.reset();
                for (int y = bottom; y <= top; y++)
                    for (int lz = 0; lz < 16; lz++)
                        for (int lx = 0; lx < 16; lx++) {
                            net.minecraft.block.state.IBlockState state =
                                    world.getBlockState(pos.setPos(cx * 16 + lx, y, cz * 16 + lz));
                            chunk.update(
                                    names.computeIfAbsent(
                                            state,
                                            s ->
                                                    (s.toString() + "\n")
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8)));
                        }
                blocks += (top - bottom + 1) * 256L;
                String line =
                        String.format(
                                Locale.ROOT,
                                "%d %d %d %d %s",
                                cx,
                                cz,
                                bottom,
                                top,
                                hex(chunk.digest()));
                total.update((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                lines.append(line).append('\n');
                chunks.add(cx + "," + cz);
                hashed++;
            }
        String digest = hex(total.digest());
        // The first animals of the hashed chunk columns, by origin, without what changes as they
        // live (position, motion, health, timers, ids).
        java.util.TreeMap<String, String> animals = new java.util.TreeMap<>();
        for (net.minecraft.entity.Entity e : world.loadedEntityList) {
            String origin = e.getEntityData().getString(FirstAnimals.ORIGIN);
            int comma = origin.indexOf(',', origin.indexOf(',') + 1);
            if (comma < 0 || !chunks.contains(origin.substring(0, comma))) continue;
            animals.put(
                    origin,
                    origin
                            + " "
                            + net.minecraft.entity.EntityList.getKey(e)
                            + " "
                            + lasting(e.writeToNBT(new net.minecraft.nbt.NBTTagCompound())));
        }
        StringBuilder animalLines = new StringBuilder();
        for (String line : animals.values()) animalLines.append(line).append('\n');
        String animalDigest =
                hex(
                        sha256().digest(
                                        animalLines
                                                .toString()
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        java.io.File out =
                new java.io.File(
                        world.getSaveHandler().getWorldDirectory(),
                        String.format(Locale.ROOT, "riven/digests/%d_%d_r%d.txt", x, z, radius));
        try {
            java.nio.file.Files.createDirectories(out.getParentFile().toPath());
            java.nio.file.Files.write(
                    out.toPath(),
                    lines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(
                    new java.io.File(
                                    out.getParentFile(),
                                    out.getName().replace(".txt", "_animals.txt"))
                            .toPath(),
                    animalLines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new CommandException("Cannot write " + out + ": " + e);
        }
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven digest %d %d r=%d: chunks=%d skipped=%d blocks=%d sha256=%s animals=%d sha256=%s (%s)",
                                x,
                                z,
                                radius,
                                hashed,
                                skipped,
                                blocks,
                                digest,
                                animals.size(),
                                animalDigest,
                                out.getName())));
    }

    /** NBT keys of an animal that it keeps for life: its looks, strength and attributes. */
    private static final java.util.Set<String> LASTING =
            new java.util.TreeSet<>(
                    java.util.Arrays.asList(
                            "Attributes",
                            "CollarColor",
                            "Color",
                            "LeftHanded",
                            "RabbitType",
                            "Strength",
                            "Variant"));

    /**
     * An animal's lasting NBT in a fixed order: the {@link #LASTING} keys, each attribute's base
     * and its modifiers by name (without their random ids).
     */
    private static String lasting(net.minecraft.nbt.NBTTagCompound nbt) {
        StringBuilder s = new StringBuilder();
        for (String key : LASTING) {
            if (!nbt.hasKey(key)) continue;
            if (!key.equals("Attributes")) {
                s.append(key).append('=').append(nbt.getTag(key)).append(';');
                continue;
            }
            java.util.TreeMap<String, String> attributes = new java.util.TreeMap<>();
            net.minecraft.nbt.NBTTagList list = nbt.getTagList(key, 10);
            for (int i = 0; i < list.tagCount(); i++) {
                net.minecraft.nbt.NBTTagCompound a = list.getCompoundTagAt(i);
                java.util.TreeSet<String> modifiers = new java.util.TreeSet<>();
                net.minecraft.nbt.NBTTagList m = a.getTagList("Modifiers", 10);
                for (int k = 0; k < m.tagCount(); k++)
                    modifiers.add(
                            m.getCompoundTagAt(k).getString("Name")
                                    + ":"
                                    + m.getCompoundTagAt(k).getDouble("Amount")
                                    + ":"
                                    + m.getCompoundTagAt(k).getInteger("Operation"));
                attributes.put(a.getString("Name"), a.getDouble("Base") + modifiers.toString());
            }
            s.append(key).append('=').append(attributes).append(';');
        }
        return s.toString();
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b));
        return s.toString();
    }

    /** 1 for a log, 2 for leaves, 0 otherwise. */
    private static int kind(net.minecraft.block.state.IBlockState state) {
        if (state == null) return 0;
        if (state.getBlock() instanceof net.minecraft.block.BlockLog) return 1;
        if (state.getBlock() instanceof net.minecraft.block.BlockLeaves) return 2;
        return 0;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length >= 1 && args[0].equals("effects")) {
            if (args.length >= 2 && args[1].equals("reload")) {
                // Rereading server configuration is an operator's call.
                if (!sender.canUseCommand(2, getName()))
                    throw new CommandException("commands.generic.permission");
                sender.sendMessage(new TextComponentString(PlayerEffects.reload()));
            } else if (args.length == 1) {
                sender.sendMessage(
                        new TextComponentString(
                                "Riven player effects: " + PlayerEffects.summary()));
            } else throw new CommandException(getUsage(sender));
            return;
        }
        World world = sender.getEntityWorld();
        if (!(world.getBiomeProvider() instanceof ClimateBiomeProvider))
            throw new CommandException("Riven maps are available in the overworld");
        if (args.length >= 2 && args[0].equals("find")) {
            find(sender, (ClimateBiomeProvider) world.getBiomeProvider(), args);
            return;
        }
        if (args.length >= 1 && args[0].equals("trees")) {
            trees(sender, world, args);
            return;
        }
        if (args.length >= 1 && args[0].equals("cover")) {
            cover(sender, world, args);
            return;
        }
        if (args.length >= 3 && args[0].equals("column")) {
            column(sender, world, parseInt(args[1]), parseInt(args[2]));
            return;
        }
        if (args.length >= 1 && args[0].equals("animals")) {
            animals(sender, world, args);
            return;
        }
        if (args.length >= 1 && args[0].equals("digest")) {
            digest(sender, world, args);
            return;
        }
        if (args.length >= 1 && args[0].equals("boulders")) {
            boulders(sender, world, (ClimateBiomeProvider) world.getBiomeProvider(), args);
            return;
        }
        if (args.length >= 1 && args[0].equals("biomes")) {
            biomes(sender);
            return;
        }
        if (args.length >= 1 && args[0].equals("mappings")) {
            mappings(sender, world);
            return;
        }
        if (args.length >= 1 && args[0].equals("shaderprops")) {
            net.minecraft.entity.player.EntityPlayerMP player =
                    args.length >= 2
                            ? getPlayer(server, sender, args[1])
                            : getCommandSenderAsPlayer(sender);
            RivenNetwork.shaderProps(player);
            sender.sendMessage(
                    new TextComponentString(
                            "Asked "
                                    + player.getName()
                                    + "'s client for a Riven copy of its shader pack"));
            return;
        }
        if (args.length >= 1 && args[0].equals("entities")) {
            Map<String, Integer> counts = new TreeMap<>();
            for (net.minecraft.entity.Entity e : world.loadedEntityList) {
                net.minecraft.util.ResourceLocation key = net.minecraft.entity.EntityList.getKey(e);
                if (key != null) counts.merge(key.toString(), 1, Integer::sum);
            }
            sender.sendMessage(new TextComponentString("Riven entities: " + counts));
            return;
        }
        if (args.length != 0 && args.length != 2) throw new CommandException(getUsage(sender));
        int x =
                args.length == 2
                        ? parseInt(args[0], -29999984, 29999984)
                        : sender.getPosition().getX();
        int z =
                args.length == 2
                        ? parseInt(args[1], -29999984, 29999984)
                        : sender.getPosition().getZ();
        ColumnReport.send(server, sender, world, x, z);
    }
}
