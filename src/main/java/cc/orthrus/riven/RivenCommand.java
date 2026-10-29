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
        return "/riven [x z] | /riven find <biome> [radius] | /riven trees [radius] | /riven cover [radius] | /riven boulders [radius] | /riven biomes | /riven mappings | /riven shaderprops [player] | /riven effects [reload]";
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
                                "Riven biomes: %d riven:, %d of 256 biome IDs used, %d free; provider overworld biomes withheld: %s",
                                RivenBiomes.list().size(),
                                used,
                                256 - used,
                                Riven.withholdProviderBiomes && BiomeProvision.active())));
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
                    // A riven: biome, or a legacy look such as biomesoplenty:woodland.
                    net.minecraft.world.biome.Biome biome = provider.biome(sample, x, z);
                    boolean reported =
                            biome.getRegistryName() != null
                                    && biome.getRegistryName().toString().equals(name);
                    if (reported
                            || BiomeProvision.lookName(provider.look(sample, x, z)).equals(name)) {
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
     * native population placed, per block, over columns whose cubes are loaded; then each site,
     * grown when every log of its tree stands in the world.
     */
    private static void trees(ICommandSender sender, World world, String[] args)
            throws CommandException {
        int radius = args.length > 1 ? parseInt(args[1], 1, 128) : 48;
        TreeSites sites = RivenWorldType.trees(world);
        if (sites == null)
            throw new CommandException("Riven tree sites need the decoration provider");
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
     * the world, near each loaded column's ground, and how many columns carry cover.
     */
    private static void cover(ICommandSender sender, World world, String[] args)
            throws CommandException {
        int radius = args.length > 1 ? parseInt(args[1], 1, 128) : 32;
        TreeSites sites = RivenWorldType.trees(world);
        GroundCover cover = RivenWorldType.cover(world);
        if (sites == null || cover == null)
            throw new CommandException("Riven ground cover needs the decoration provider");
        BlockPos at = sender.getPosition();
        long matched = 0, previewOnly = 0, worldOnly = 0, columns = 0, covered = 0, grounds = 0;
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
                                "Riven cover r=%d: columns=%d covered=%d matched=%d previewOnly=%d worldOnly=%d groundDiffers=%d (%s)",
                                radius,
                                columns,
                                covered,
                                matched,
                                previewOnly,
                                worldOnly,
                                grounds,
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
     * One column through both generation paths: the native column's runs, habitat key and look
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
                        "Riven column %d %d: surface=%d key native=%s sample=%s; climate native=%d sample=%d; look native=%s sample=%s",
                        x,
                        z,
                        surface,
                        native_.key(index),
                        cc.orthrus.riven.worldgen.Habitats.key(sample),
                        native_.climate(index),
                        cc.orthrus.riven.worldgen.Habitats.climate(sample),
                        provider.look(native_, index, x, z).getRegistryName(),
                        provider.look(sample, x, z).getRegistryName()));
        sender.sendMessage(new TextComponentString(out.toString()));
        cc.orthrus.riven.worldgen.TerrainBlock[] line =
                new cc.orthrus.riven.worldgen.TerrainBlock[16];
        int[] bottoms = new int[cc.orthrus.riven.worldgen.ColumnBlocks.maximumRuns() + 1];
        cc.orthrus.riven.worldgen.TerrainBlock[] blocks =
                new cc.orthrus.riven.worldgen.TerrainBlock
                        [cc.orthrus.riven.worldgen.ColumnBlocks.maximumRuns()];
        int count =
                cc.orthrus.riven.worldgen.ColumnBlocks.runs(
                        sample,
                        cc.orthrus.riven.worldgen.ColumnBlocks.bareStone(
                                sample, provider.maps.surfaces),
                        provider.maps.surfaces,
                        cc.orthrus.riven.worldgen.TerrainHeightReference.DEFAULT,
                        bottoms,
                        blocks);
        TreeSites sites = RivenWorldType.trees(world);
        TreeSites.Recorder preview = sites == null ? null : sites.previewColumn(x, z);
        net.minecraft.world.biome.Biome nativeLook = provider.look(native_, index, x, z),
                sampleLook = provider.look(sample, x, z);
        sender.sendMessage(
                new TextComponentString(
                        "  looks native="
                                + BiomeProvision.lookName(nativeLook)
                                + " (top "
                                + nativeLook.topBlock
                                + ", filler "
                                + nativeLook.fillerBlock
                                + ") sample="
                                + BiomeProvision.lookName(sampleLook)
                                + (nativeLook == sampleLook ? " (same)" : " (DIFFERENT)")));
        SurfaceTranscription surfaces = RivenWorldType.surfaces(world);
        SurfaceTranscription.Surface chunk =
                surfaces == null ? null : surfaces.surface(x >> 4, z >> 4);
        cc.orthrus.riven.worldgen.TerrainBlock[] riven =
                new cc.orthrus.riven.worldgen.TerrainBlock[256];
        int ground = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            for (int y = Math.max(0, bottoms[i]); y < Math.min(256, bottoms[i + 1]); y++)
                riven[y] = blocks[i];
            if (blocks[i] != cc.orthrus.riven.worldgen.TerrainBlock.AIR
                    && blocks[i] != cc.orthrus.riven.worldgen.TerrainBlock.WATER)
                ground = bottoms[i + 1];
        }
        for (int y = 0; y < 256; y++)
            if (riven[y] == null) riven[y] = cc.orthrus.riven.worldgen.TerrainBlock.AIR;
        SurfaceTranscription.Surface single =
                surfaces == null ? null : surfaces.column(riven, ground, sampleLook, x, z);
        StringBuilder kept = new StringBuilder("  kept chunk/column:");
        for (int y = surface - 5; y <= surface; y++)
            kept.append(' ')
                    .append(y)
                    .append('=')
                    .append(chunk == null ? null : chunk.at(index, y))
                    .append('/')
                    .append(single == null ? null : single.at(0, y));
        sender.sendMessage(
                new TextComponentString(
                        kept
                                + " tops "
                                + (chunk == null ? null : chunk.bandTop(index))
                                + "/"
                                + (single == null ? null : single.bandTop(0))));
        for (int y = surface - 5; y <= surface + 1; y++) {
            native_.cube(index, Math.floorDiv(y, 16) * 16, line);
            cc.orthrus.riven.worldgen.TerrainBlock sampled = null;
            for (int i = 0; i < count; i++)
                if (y >= bottoms[i] && y < bottoms[i + 1]) sampled = blocks[i];
            sender.sendMessage(
                    new TextComponentString(
                            String.format(
                                    Locale.ROOT,
                                    "  y=%d terrain native=%s sample=%s | preview=%s world=%s",
                                    y,
                                    line[Math.floorMod(y, 16)],
                                    sampled,
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
