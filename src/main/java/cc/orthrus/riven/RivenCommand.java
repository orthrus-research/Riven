package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Basin;
import cc.orthrus.riven.worldgen.ClimateMap;
import cc.orthrus.riven.worldgen.CoastShape;
import cc.orthrus.riven.worldgen.Hydro;
import cc.orthrus.riven.worldgen.LakeShape;
import cc.orthrus.riven.worldgen.PlateMap;
import cc.orthrus.riven.worldgen.RealmMap;
import cc.orthrus.riven.worldgen.RiverShape;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;

import io.github.opencubicchunks.cubicchunks.core.server.ICubicPlayerList;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumSkyBlock;
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
        ClimateBiomeProvider provider = (ClimateBiomeProvider) world.getBiomeProvider();
        sender.sendMessage(
                new TextComponentString(
                        "View distances: server horizontal="
                                + server.getPlayerList().getViewDistance()
                                + " vertical="
                                + ((ICubicPlayerList) server.getPlayerList())
                                        .getVerticalViewDistance()));
        ClimateMap.Sample climate = new ClimateMap.Sample();
        provider.maps.climate.sample(x, z, climate);
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Climate forcing %s/%s: background=%.3f correction=%+.3f factor=%+.3f landWeight=%.3f obstruction=%.2f contrast=%.2f blocks datumY=%d reads=%d/%d (structure/elevation)",
                                provider.maps.climateForcing.policy,
                                climate.forcing.status(),
                                climate.backgroundMoisture,
                                climate.moistureCorrection,
                                climate.forcing.moistureFactor(),
                                climate.forcing.landWeight(),
                                climate.forcing.obstructionAboveDatum(),
                                climate.forcing.meanContrastBlocks(),
                                provider.maps.climateStructure.datumY,
                                climate.forcing.structureQueries(),
                                climate.forcing.elevationQueries())));
        PlateMap.Sample plate = provider.maps.plates.sample(x, z);
        TerrainMap.Sample terrain = new TerrainMap.Sample();
        provider.maps.terrain.sample(x, z, terrain);
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Hydro body=%s id=%s waterY=%d groundwaterPotential=%.2f bankWeight=%.3f",
                                terrain.water.kind,
                                Long.toUnsignedString(terrain.water.id, 16),
                                terrain.waterY(),
                                terrain.groundwaterHead,
                                terrain.water.weight)));
        Hydro hydro = provider.maps.terrain.hydro;
        Hydro.Plan owner =
                hydro.plan(Math.floorDiv(x, hydro.cellBlocks), Math.floorDiv(z, hydro.cellBlocks));
        describeRivers(sender, owner.rivers, terrain.water.riverShape);
        if (terrain.water.coastShape != null) describeCoast(sender, terrain.water.coastShape);
        if (owner.present() && terrain.water.id == owner.id) describeLake(sender, owner.lakeShape);
        for (int i = 0; i < owner.basins(); i++) {
            Basin basin = owner.basin(i);
            if (terrain.water.id == basin.id) {
                describeLake(sender, basin.lakeShape);
                if (terrain.water.coastShape == null) describeCoast(sender, basin.coastShape);
            }
        }
        sender.sendMessage(
                new TextComponentString(
                        "Aquifer interval="
                                + terrain.water.aquiferMin
                                + ".."
                                + terrain.water.aquiferMax
                                + " id="
                                + Long.toUnsignedString(terrain.water.aquiferId, 16)));
        int waterY = provider.context.terrainHeights.waterTopY;
        BlockPos surface = new BlockPos(x, terrain.blockY(), z);
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Riven (%d, %d): %.1f C %s | moisture=%.3f %s | region=%s | biome=%s expected=%s | surface=%s | plate=%s | boundary=%.0f blocks %s",
                                x,
                                z,
                                climate.celsius,
                                climate.temperatureBand,
                                climate.moisture,
                                climate.moistureBand,
                                climate.region,
                                world.getBiome(surface).getRegistryName(),
                                provider.biome(terrain, x, z).getRegistryName()
                                        + " look="
                                        + BiomeProvision.lookName(provider.look(terrain, x, z)),
                                world.getBlockState(surface).getBlock().getRegistryName(),
                                Long.toUnsignedString(plate.plateId, 16),
                                plate.boundaryDistance,
                                plate.boundary)));
        // Realms report their biome by height: the biome where the sender stands, not at the
        // surface.
        BlockPos here = sender.getPosition();
        sender.sendMessage(
                new TextComponentString(
                        "Here (Y "
                                + here.getY()
                                + "): biome="
                                + world.getBiome(here).getRegistryName()));
        RivenClimate.Here below = RivenClimate.at(world, here);
        if (below != null)
            sender.sendMessage(
                    new TextComponentString(
                            String.format(
                                    Locale.ROOT,
                                    "Climate here: %.1f C humidity=%.2f air=%.2f depth=%.0f%s%s | realm=%s%s | effects=%s",
                                    below.climate.celsius,
                                    below.climate.humidity,
                                    below.climate.air,
                                    below.climate.depth,
                                    below.climate.frozen ? " frozen" : "",
                                    below.climate.steam ? " steam" : "",
                                    below.realm,
                                    below.depthBiome == null
                                            ? ""
                                            : " depthBiome=" + below.depthBiome,
                                    PlayerEffects.matching(below))));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Ocean marine=%s coast=%s coordinate=%.2f landWeight=%.3f | bed=%d seaLevel=%d highestWater=%d | aboveBed=%s waterTop=%s aboveWater=%s skyAtWater=%d/%d/%d",
                                terrain.ocean.marine,
                                terrain.ocean.form,
                                terrain.ocean.coastCoordinate,
                                terrain.shore.landWeight,
                                terrain.blockY(),
                                world.getSeaLevel(),
                                provider.context.terrainHeights.waterTopY,
                                world.getBlockState(surface.up()),
                                world.getBlockState(new BlockPos(x, waterY, z)),
                                world.getBlockState(new BlockPos(x, waterY + 1, z)),
                                world.getLightFor(EnumSkyBlock.SKY, new BlockPos(x, waterY, z)),
                                world.getLightFor(EnumSkyBlock.SKY, new BlockPos(x, waterY - 1, z)),
                                world.getLightFor(
                                        EnumSkyBlock.SKY, new BlockPos(x, waterY - 2, z)))));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Continents=%s owner=%s islands=%s ownership=%s supplied=%s protectedScaffold=%s | size=%.2f axis=%.2f rough=%.2f interior=%.2f shelter=%.2f cape=%.2f",
                                terrain.ocean.family,
                                Long.toUnsignedString(terrain.ocean.owner, 16),
                                terrain.ocean.archipelago,
                                terrain.ocean.reason,
                                terrain.ocean.supplied,
                                terrain.ocean.protectedWater,
                                terrain.ocean.continentSize,
                                terrain.ocean.elongation,
                                terrain.ocean.roughness,
                                terrain.ocean.interior,
                                terrain.ocean.shelter,
                                terrain.ocean.cape)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Terrain family=%s character=%s weights=%.3f/%.3f/%.3f rugged=%.3f | edgeOpportunity=%.3f cliff=%.3f waterfallSite=%.3f (no water supply implied)",
                                terrain.land.level,
                                terrain.land.character,
                                terrain.land.low,
                                terrain.land.middle,
                                terrain.land.high,
                                terrain.land.rugged,
                                terrain.land.edge,
                                terrain.shore.cliff,
                                terrain.land.waterfallSite)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Shore=%s distanceEstimate=%.2f normal=%.3f/%.3f shelter=%.3f grade=%.3f | beach=%.3f plain=%.3f rock=%.3f marshOpportunity=%.3f | broadHeight=%.2f",
                                terrain.shore.kind,
                                terrain.shore.distance,
                                terrain.shore.normalX,
                                terrain.shore.normalZ,
                                terrain.shore.shelter,
                                terrain.shore.grade,
                                terrain.shore.beach,
                                terrain.shore.plain,
                                terrain.shore.rock,
                                terrain.shore.marsh,
                                terrain.shore.height)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Sediment=%s reason=%s id=%s | parent=%.2f top=%.2f weight=%.3f emergent=%s | candidates=%d boxColumns=%d active=%d masked=%d earlierProbes=%d islandColumns=%d",
                                terrain.sediment.family,
                                terrain.sediment.reason,
                                Long.toUnsignedString(terrain.sediment.id, 16),
                                terrain.sediment.parentHeight,
                                terrain.sediment.height,
                                terrain.sediment.weight,
                                terrain.emergentSediment,
                                terrain.sediment.candidates,
                                terrain.sediment.boxColumns,
                                terrain.sediment.activeColumns,
                                terrain.sediment.maskedColumns,
                                terrain.sediment.probeColumns,
                                terrain.sediment.islandColumns)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Rocky=%s reason=%s id=%s arch=%s | parent=%.2f top=%.2f weight=%.3f emergent=%s | candidates=%d boxColumns=%d bodyColumns=%d certificateVoxels=%d volumeCuts=%d",
                                terrain.rocky.family,
                                terrain.rocky.reason,
                                Long.toUnsignedString(terrain.rocky.id, 16),
                                terrain.rocky.arch,
                                terrain.rocky.parentHeight,
                                terrain.rocky.height,
                                terrain.rocky.weight,
                                terrain.emergentRock,
                                terrain.rocky.candidates,
                                terrain.rocky.boxColumns,
                                terrain.rocky.bodyColumns,
                                terrain.rocky.voxels,
                                terrain.rocky.cutCount)));
        if (terrain.ocean.marine) return;
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Lowlands=%s weights=%.3f/%.3f/%.3f/%.3f | applied=%+.3f weight=%.3f candidates=%d shoreClearance=%.2f waterClearance=%.2f",
                                terrain.lowland.reason,
                                terrain.lowland.weights[0],
                                terrain.lowland.weights[1],
                                terrain.lowland.weights[2],
                                terrain.lowland.weights[3],
                                terrain.lowland.delta,
                                terrain.lowland.weight,
                                terrain.lowland.candidateVisits,
                                terrain.lowland.shoreClearance,
                                terrain.lowland.waterClearance)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Terrain top=%d base=%.2f erosion=%+.2f | %s/%s blend=%.3f uplift=%.3f | above=%s below=%s",
                                terrain.blockY(),
                                terrain.baseHeight,
                                terrain.erosion.displacement,
                                terrain.firstLandform,
                                terrain.secondLandform,
                                terrain.blend,
                                terrain.uplift,
                                world.getBlockState(surface.up()).getBlock().getRegistryName(),
                                world.getBlockState(surface.down()).getBlock().getRegistryName())));
        if (!terrain.ocean.marine)
            sender.sendMessage(
                    new TextComponentString(
                            String.format(
                                    Locale.ROOT,
                                    "Region=%s | inland mean=%.2f amplitude=%.2f wide/fine=%.3f/%.3f",
                                    terrain.baseTerrain().region().dominant(),
                                    terrain.baseTerrain().meanHeight(),
                                    terrain.baseTerrain().amplitude(),
                                    terrain.baseTerrain().region().wideShare(),
                                    terrain.baseTerrain().region().fineShare())));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Formation=%s permutation=%s | shape=%+.2f roughening=%+.2f smoothing=%+.2f strength=%.3f | bounded change=%+.2f",
                                terrain.passes.dominant,
                                Long.toUnsignedString(terrain.passes.permutation, 16),
                                terrain.passes.formation + terrain.passes.formationFine,
                                terrain.passes.roughening,
                                terrain.passes.smoothing,
                                terrain.passes.smoothingStrength,
                                terrain.passes.appliedDisplacement)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Volcanic=%s reason=%s id=%s anchor=(%.1f,%.1f) | delta=%+.3f weight=%.3f candidates=%d contributors=%d retainedCertificateColumns=%d stencilColumns=%d | minPotential=%.3f maxColumnSlope=%.3f",
                                terrain.volcanic.dominant,
                                terrain.volcanic.reason,
                                Long.toUnsignedString(terrain.volcanic.id, 16),
                                terrain.volcanic.anchorX,
                                terrain.volcanic.anchorZ,
                                terrain.volcanic.delta,
                                terrain.volcanic.weight,
                                terrain.volcanic.candidates,
                                terrain.volcanic.contributors,
                                terrain.volcanic.certificateColumns,
                                terrain.volcanic.stencilColumns,
                                terrain.volcanic.minPotential,
                                terrain.volcanic.maxColumnSlope)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "ColdRegions=%s reason=%s bits=%d id=%s anchor=(%.1f,%.1f) | delta=%+.3f weight=%.3f candidates=%d contributors=%d retainedCertificateColumns=%d diagnosticColumns=%d | cold=%.3f earlierRelief256=%.3f maxBroadSlope=%.3f",
                                terrain.cold.dominant,
                                terrain.cold.reason,
                                terrain.cold.rejectionBits,
                                Long.toUnsignedString(terrain.cold.id, 16),
                                terrain.cold.anchorX,
                                terrain.cold.anchorZ,
                                terrain.cold.delta,
                                terrain.cold.weight,
                                terrain.cold.candidates,
                                terrain.cold.contributors,
                                terrain.cold.certificateColumns,
                                terrain.cold.diagnosticColumns,
                                terrain.cold.cold,
                                terrain.cold.relief,
                                terrain.cold.slope)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Plateaus=%s reason=%s id=%s anchor=(%.1f,%.1f) | candidates=%d contributors=%d delta=%+.3f retainedCertificateColumns=%d",
                                terrain.plateaus.dominant,
                                terrain.plateaus.reason,
                                Long.toUnsignedString(terrain.plateaus.id, 16),
                                terrain.plateaus.anchorX,
                                terrain.plateaus.anchorZ,
                                terrain.plateaus.candidates,
                                terrain.plateaus.contributors,
                                terrain.plateaus.delta,
                                terrain.plateaus.certificateColumns)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Mountains=%s reason=%s id=%s anchor=(%.1f,%.1f) | candidates=%d contributors=%d delta=%+.3f ridge=%.3f pass=%.3f",
                                terrain.mountains.dominant,
                                terrain.mountains.reason,
                                Long.toUnsignedString(terrain.mountains.id, 16),
                                terrain.mountains.anchorX,
                                terrain.mountains.anchorZ,
                                terrain.mountains.candidates,
                                terrain.mountains.contributors,
                                terrain.mountains.delta,
                                terrain.mountains.ridge,
                                terrain.mountains.pass)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Hills=%s reason=%s id=%s anchor=(%.1f,%.1f) | candidates=%d contributors=%d delta=%+.3f retainedCertificateColumns=%d",
                                terrain.hills.dominant,
                                terrain.hills.reason,
                                Long.toUnsignedString(terrain.hills.id, 16),
                                terrain.hills.anchorX,
                                terrain.hills.anchorZ,
                                terrain.hills.candidates,
                                terrain.hills.contributors,
                                terrain.hills.delta,
                                terrain.hills.certificateColumns)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Landmark=%s variant=%d id=%s anchor=(%.1f,%.1f) climate=%s | overlaps=%d raw=%+.2f applied=%+.2f",
                                terrain.landmarks.dominant,
                                terrain.landmarks.variant,
                                Long.toUnsignedString(terrain.landmarks.featureId, 16),
                                terrain.landmarks.anchorX,
                                terrain.landmarks.anchorZ,
                                terrain.landmarks.anchorRegion,
                                terrain.landmarks.overlaps,
                                terrain.landmarks.rawDisplacement,
                                terrain.landmarks.appliedDisplacement)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Carver=%s id=%s anchor=(%.1f,%.1f) climate=%s | overlaps=%d rawCut=%.2f appliedCut=%.2f",
                                terrain.carvers.dominant,
                                Long.toUnsignedString(terrain.carvers.featureId, 16),
                                terrain.carvers.anchorX,
                                terrain.carvers.anchorZ,
                                terrain.carvers.anchorRegion,
                                terrain.carvers.overlaps,
                                terrain.carvers.rawCut,
                                terrain.carvers.appliedCut)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "GeneralCuts=%s reason=%s id=%s anchor=(%.1f,%.1f) | cut=%.3f weight=%.3f candidates=%d contributors=%d relief256=%.3f saddleFit=%.3f | retainedSupport=%d protected=%d corridor=%d probes=%d",
                                terrain.cuts.dominant,
                                terrain.cuts.reason,
                                Long.toUnsignedString(terrain.cuts.id, 16),
                                terrain.cuts.anchorX,
                                terrain.cuts.anchorZ,
                                terrain.cuts.cut,
                                terrain.cuts.weight,
                                terrain.cuts.candidates,
                                terrain.cuts.contributors,
                                terrain.cuts.relief,
                                terrain.cuts.saddleFit,
                                terrain.cuts.supportColumns,
                                terrain.cuts.protectedColumns,
                                terrain.cuts.corridorColumns,
                                terrain.cuts.probeColumns)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Drylands=%s reason=%s shares=%.3f/%.3f/%.3f/%.3f | delta=%+.3f sand=%.3f pavement=%.3f yardangCut=%.3f candidates=%d contributors=%d | fit=%.3f aridity=%.3f shoreClearance=%.2f waterClearance=%.2f",
                                terrain.drylands.dominant,
                                terrain.drylands.reason,
                                terrain.drylands.weights[0],
                                terrain.drylands.weights[1],
                                terrain.drylands.weights[2],
                                terrain.drylands.weights[3],
                                terrain.drylands.delta,
                                terrain.drylands.sandCover,
                                terrain.drylands.pavement,
                                terrain.drylands.yardangCut,
                                terrain.drylands.candidates,
                                terrain.drylands.contributors,
                                terrain.drylands.fit,
                                terrain.drylands.aridity,
                                terrain.drylands.shoreClearance,
                                terrain.drylands.waterClearance)));
        sender.sendMessage(
                new TextComponentString(
                        "Dryland family admission="
                                + java.util.Arrays.toString(terrain.drylands.reasons)));
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "Cover=%s stoneFromY=%d soilDepth=%d | expectedSurface=%s substrate=%s",
                                provider.maps.surfaces.reason(terrain),
                                provider.maps.surfaces.stoneAboveY,
                                provider.maps.surfaces.soilDepth,
                                TerrainColumn.blockAt(
                                                terrain.blockY(),
                                                terrain,
                                                provider.maps.surfaces.bareStone(terrain),
                                                provider.maps.surfaces,
                                                provider.context.terrainHeights)
                                        .getBlock()
                                        .getRegistryName(),
                                world.getBlockState(surface.down(provider.maps.surfaces.soilDepth))
                                        .getBlock()
                                        .getRegistryName())));
    }

    private static void describeCoast(ICommandSender sender, CoastShape.Decision decision) {
        if (decision == null) return;
        CoastShape shape = decision.shape;
        if (shape == null) {
            sender.sendMessage(
                    new TextComponentString(
                            "CoastShape="
                                    + decision.family
                                    + "/"
                                    + decision.reason
                                    + " certificateColumns="
                                    + decision.probes
                                    + " receiverColumns="
                                    + decision.receiverColumns));
            return;
        }
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "CoastShape=%s id=%s length=%.2f support=%.2f foundation=%.2f | shelter=%.3f sedimentOpportunity=%.3f receiverHalfWidth=%.2f certificateColumns=%d receiverColumns=%d",
                                decision.family,
                                Long.toUnsignedString(shape.geometry.id, 16),
                                shape.length,
                                shape.geometry.supportRadius,
                                shape.foundation,
                                shape.shelter,
                                shape.sediment,
                                shape.receiverHalfWidth,
                                decision.probes,
                                decision.receiverColumns)));
    }

    private static void describeRivers(
            ICommandSender sender, RiverShape rivers, RiverShape.Decision local) {
        if (rivers == null) return;
        sender.sendMessage(
                new TextComponentString(
                        "RiverShapes accepted="
                                + rivers.accepted
                                + "/"
                                + rivers.candidates()
                                + " ownerCertificateColumns="
                                + rivers.certificateColumns));
        if (local != null) {
            RiverShape.Reach shape = local.shape;
            sender.sendMessage(
                    new TextComponentString(
                            String.format(
                                    Locale.ROOT,
                                    "RiverShape=%s edge=%d route=%s width=%.3f depth=%.1f length=%.2f heads=%.3f..%.3f branchLength=%.2f | segments=%d certificateColumns=%d",
                                    local.family,
                                    local.edge,
                                    Long.toUnsignedString(shape.geometry.routeId, 16),
                                    shape.width,
                                    shape.depth,
                                    shape.length,
                                    shape.startWater,
                                    shape.endWater,
                                    shape.branchLength,
                                    shape.geometry.segments,
                                    local.probes)));
        } else {
            StringBuilder reasons = new StringBuilder("River selections:");
            for (int i = 0; i < rivers.candidates(); i++) {
                RiverShape.Decision d = rivers.decision(i);
                reasons.append(' ')
                        .append(d.edge)
                        .append(':')
                        .append(d.family)
                        .append('/')
                        .append(d.reason);
            }
            sender.sendMessage(new TextComponentString(reasons.toString()));
        }
    }

    private static void describeLake(ICommandSender sender, LakeShape.Decision decision) {
        if (decision == null) return;
        LakeShape shape = decision.shape;
        if (shape == null) {
            sender.sendMessage(
                    new TextComponentString(
                            "LakeShape="
                                    + decision.reason
                                    + " certificateColumns="
                                    + decision.probes));
            return;
        }
        sender.sendMessage(
                new TextComponentString(
                        String.format(
                                Locale.ROOT,
                                "LakeShape=%s profile=%s width=%.2f depth=%.0f islandRadius=%.3f outletWidth=%.2f | support=%.2f certificateColumns=%d",
                                shape.geometry.family,
                                shape.profile,
                                shape.geometry.t.width(),
                                shape.geometry.t.depth(),
                                shape.geometry.t.islandRadius(),
                                shape.geometry.t.outletWidth(),
                                shape.geometry.supportRadius,
                                decision.probes)));
    }
}
