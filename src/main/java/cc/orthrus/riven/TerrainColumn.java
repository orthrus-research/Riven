package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Boulders;
import cc.orthrus.riven.worldgen.ColumnBlocks;
import cc.orthrus.riven.worldgen.RealmMap;
import cc.orthrus.riven.worldgen.SurfaceRules;
import cc.orthrus.riven.worldgen.TerrainBlock;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;

import net.minecraft.block.Block;
import net.minecraft.block.BlockColored;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockStone;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;

import java.util.Map;

/** Minecraft states for Riven's shared block rules ({@link ColumnBlocks}). */
final class TerrainColumn {
    private static final IBlockState[] STATES = states();

    private static IBlockState[] states() {
        IBlockState[] result = new IBlockState[TerrainBlock.values().length];
        result[TerrainBlock.AIR.ordinal()] = Blocks.AIR.getDefaultState();
        result[TerrainBlock.BEDROCK.ordinal()] = Blocks.BEDROCK.getDefaultState();
        result[TerrainBlock.STONE.ordinal()] = Blocks.STONE.getDefaultState();
        result[TerrainBlock.WATER.ordinal()] = Blocks.WATER.getDefaultState();
        result[TerrainBlock.SAND.ordinal()] = Blocks.SAND.getDefaultState();
        result[TerrainBlock.GRAVEL.ordinal()] = Blocks.GRAVEL.getDefaultState();
        result[TerrainBlock.DIRT.ordinal()] = Blocks.DIRT.getDefaultState();
        result[TerrainBlock.GRASS.ordinal()] = Blocks.GRASS.getDefaultState();
        result[TerrainBlock.SNOW.ordinal()] = Blocks.SNOW.getDefaultState();
        result[TerrainBlock.GRANITE.ordinal()] =
                Blocks.STONE
                        .getDefaultState()
                        .withProperty(BlockStone.VARIANT, BlockStone.EnumType.GRANITE);
        result[TerrainBlock.DIORITE.ordinal()] =
                Blocks.STONE
                        .getDefaultState()
                        .withProperty(BlockStone.VARIANT, BlockStone.EnumType.DIORITE);
        result[TerrainBlock.ANDESITE.ordinal()] =
                Blocks.STONE
                        .getDefaultState()
                        .withProperty(BlockStone.VARIANT, BlockStone.EnumType.ANDESITE);
        result[TerrainBlock.SANDSTONE.ordinal()] = Blocks.SANDSTONE.getDefaultState();
        result[TerrainBlock.RED_SANDSTONE.ordinal()] = Blocks.RED_SANDSTONE.getDefaultState();
        result[TerrainBlock.COARSE_DIRT.ordinal()] =
                Blocks.DIRT
                        .getDefaultState()
                        .withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.COARSE_DIRT);
        result[TerrainBlock.CLAY.ordinal()] = Blocks.CLAY.getDefaultState();
        result[TerrainBlock.TERRACOTTA.ordinal()] = Blocks.HARDENED_CLAY.getDefaultState();
        result[TerrainBlock.TERRACOTTA_ORANGE.ordinal()] = terracotta(EnumDyeColor.ORANGE);
        result[TerrainBlock.TERRACOTTA_YELLOW.ordinal()] = terracotta(EnumDyeColor.YELLOW);
        result[TerrainBlock.TERRACOTTA_BROWN.ordinal()] = terracotta(EnumDyeColor.BROWN);
        result[TerrainBlock.TERRACOTTA_RED.ordinal()] = terracotta(EnumDyeColor.RED);
        result[TerrainBlock.TERRACOTTA_WHITE.ordinal()] = terracotta(EnumDyeColor.WHITE);
        result[TerrainBlock.TERRACOTTA_GRAY.ordinal()] = terracotta(EnumDyeColor.GRAY);
        result[TerrainBlock.TERRACOTTA_BLACK.ordinal()] = terracotta(EnumDyeColor.BLACK);
        result[TerrainBlock.LAVA.ordinal()] = Blocks.LAVA.getDefaultState();
        // Cave decoration: Riven's own blocks, Biomes O' Plenty's where it is loaded, vanilla
        // stand-ins otherwise (riven-cave-decor.json lists them).
        IBlockState clay = Blocks.CLAY.getDefaultState(), air = Blocks.AIR.getDefaultState();
        IBlockState brown = Blocks.BROWN_MUSHROOM.getDefaultState();
        result[TerrainBlock.MUD.ordinal()] = named("biomesoplenty:mud", "variant", "mud", clay);
        result[TerrainBlock.WHITE_SAND.ordinal()] =
                named("biomesoplenty:white_sand", null, null, Blocks.SAND.getDefaultState());
        result[TerrainBlock.ASH.ordinal()] =
                named("biomesoplenty:ash_block", null, null, Blocks.GRAVEL.getDefaultState());
        result[TerrainBlock.MAGMA.ordinal()] = Blocks.MAGMA.getDefaultState();
        result[TerrainBlock.NETHERRACK.ordinal()] = Blocks.NETHERRACK.getDefaultState();
        result[TerrainBlock.ICE.ordinal()] = Blocks.ICE.getDefaultState();
        result[TerrainBlock.PACKED_ICE.ordinal()] = Blocks.PACKED_ICE.getDefaultState();
        result[TerrainBlock.COBWEB.ordinal()] = Blocks.WEB.getDefaultState();
        result[TerrainBlock.DRIPSTONE_BLOCK.ordinal()] =
                named("riven:dripstone_block", null, null, Blocks.STONE.getDefaultState());
        result[TerrainBlock.DRIPSTONE_UP.ordinal()] =
                named("riven:pointed_dripstone", "direction", "up", air);
        result[TerrainBlock.DRIPSTONE_DOWN.ordinal()] =
                named("riven:pointed_dripstone", "direction", "down", air);
        result[TerrainBlock.ICE_UP.ordinal()] = named("riven:pointed_ice", "direction", "up", air);
        result[TerrainBlock.ICE_DOWN.ordinal()] =
                named("riven:pointed_ice", "direction", "down", air);
        result[TerrainBlock.SALT_UP.ordinal()] =
                named("riven:pointed_salt", "direction", "up", air);
        result[TerrainBlock.SALT_DOWN.ordinal()] =
                named("riven:pointed_salt", "direction", "down", air);
        result[TerrainBlock.BASALT_UP.ordinal()] =
                named("riven:pointed_basalt", "direction", "up", air);
        result[TerrainBlock.BASALT_DOWN.ordinal()] =
                named("riven:pointed_basalt", "direction", "down", air);
        result[TerrainBlock.ROOT.ordinal()] =
                named("biomesoplenty:plant_1", "variant", "root", air);
        result[TerrainBlock.TOADSTOOL.ordinal()] =
                named(
                        "biomesoplenty:mushroom",
                        "variant",
                        "toadstool",
                        Blocks.RED_MUSHROOM.getDefaultState());
        result[TerrainBlock.PORTOBELLO.ordinal()] =
                named("biomesoplenty:mushroom", "variant", "portobello", brown);
        result[TerrainBlock.BROWN_MUSHROOM.ordinal()] = brown;
        result[TerrainBlock.RED_MUSHROOM.ordinal()] = Blocks.RED_MUSHROOM.getDefaultState();
        result[TerrainBlock.MINERS_DELIGHT.ordinal()] =
                named("biomesoplenty:flower_1", "variant", "miners_delight", air);
        result[TerrainBlock.GLOWSHROOM.ordinal()] =
                named("biomesoplenty:mushroom", "variant", "glowshroom", brown);
        result[TerrainBlock.ALGAE.ordinal()] =
                named("biomesoplenty:coral", "variant", "algae", Blocks.WATER.getDefaultState());
        // Riven host stones, with vanilla stand-ins should a block be missing.
        IBlockState stone = Blocks.STONE.getDefaultState();
        for (TerrainBlock b : TerrainBlock.values())
            if (b.stoneId() != null) {
                IBlockState fallback =
                        b == TerrainBlock.LIMESTONE || b == TerrainBlock.ANORTHOSITE
                                ? result[TerrainBlock.DIORITE.ordinal()]
                                : b == TerrainBlock.GNEISS
                                                || b == TerrainBlock.QUARTZITE
                                                || b == TerrainBlock.SOAPSTONE
                                                || b == TerrainBlock.KIMBERLITE
                                        ? stone
                                        : result[TerrainBlock.ANDESITE.ordinal()];
                result[b.ordinal()] = named("riven:" + b.stoneId(), null, null, fallback);
            }
        // Deep-realm and boundary rocks.
        for (TerrainBlock b : TerrainBlock.values())
            if (b.rockId() != null)
                result[b.ordinal()] = named("riven:" + b.rockId(), null, null, stone);
        for (IBlockState state : result)
            if (state == null) throw new IllegalStateException("Unmapped terrain block");
        return result;
    }

    /**
     * A registered block's state with one property set by name, or the fallback when the block
     * (an optional provider's) or the value is missing.
     */
    private static IBlockState named(
            String id, String property, String value, IBlockState fallback) {
        Block block = Block.REGISTRY.getObject(new ResourceLocation(id));
        if (block == Blocks.AIR || !id.equals(String.valueOf(block.getRegistryName())))
            return fallback;
        if (property == null) return block.getDefaultState();
        for (IBlockState state : block.getBlockState().getValidStates())
            for (Map.Entry<IProperty<?>, Comparable<?>> e : state.getProperties().entrySet())
                if (e.getKey().getName().equals(property)
                        && value.equals(valueName(e.getKey(), e.getValue()))) return state;
        return fallback;
    }

    private static <T extends Comparable<T>> String valueName(
            IProperty<T> property, Comparable<?> value) {
        return property.getName(property.getValueClass().cast(value));
    }

    private static IBlockState terracotta(EnumDyeColor color) {
        return Blocks.STAINED_HARDENED_CLAY
                .getDefaultState()
                .withProperty(BlockColored.COLOR, color);
    }

    private TerrainColumn() {}

    static IBlockState state(TerrainBlock block) {
        return STATES[block.ordinal()];
    }

    /**
     * The state of a Riven block in a column of the given biome. A provider biome (riven-bop.json)
     * supplies its own surface: the soil Riven places as grass takes the biome's top block and the
     * soil below takes its filler (as the look was built: routines rewrite the live fields per
     * column), each only when it is a full solid block. Riven still decides where soil lies;
     * nothing else changes.
     */
    static IBlockState state(TerrainBlock block, Biome biome) {
        IBlockState[] soil;
        if ((block == TerrainBlock.GRASS || block == TerrainBlock.DIRT)
                && BiomeProvision.isProviderLook(biome)
                && (soil = BopLooks.soil(biome)) != null) {
            IBlockState own = block == TerrainBlock.GRASS ? soil[0] : soil[1];
            if (own != null && own.isFullCube() && own.getMaterial().isSolid()) return own;
        }
        return STATES[block.ordinal()];
    }

    static IBlockState blockAt(
            long y,
            TerrainMap.Sample sample,
            boolean bareStone,
            SurfaceRules rules,
            TerrainHeightReference heights) {
        return state(ColumnBlocks.at(y, sample, bareStone, rules, heights));
    }

    /**
     * A kept surface block as the preview's lighting accepts it: translucent or emitting blocks
     * (a frozen biome's ice) cannot be lit there, so ice previews as packed ice and others as
     * Riven's own block.
     */
    @SuppressWarnings("deprecation") // The preview lighting guard reads the same properties.
    private static IBlockState previewable(IBlockState kept) {
        if (kept == null) return null;
        int opacity = kept.getLightOpacity();
        if ((opacity <= 0 || opacity >= 15) && kept.getLightValue() == 0) return kept;
        return kept.getBlock() == Blocks.ICE ? Blocks.PACKED_ICE.getDefaultState() : null;
    }

    /**
     * One caller's reusable buffers for {@link #preview}: a preview tile's sampler, or a thread's
     * tree sites (whose trunk columns are previewed inside a preview column's trees, so the two
     * never share one). Nothing in them outlives a call.
     */
    static final class Scratch {
        final int[] bottoms = new int[ColumnBlocks.maximumRuns() + 1];
        final TerrainBlock[] blocks = new TerrainBlock[ColumnBlocks.maximumRuns()];
        final TerrainBlock[] riven = new TerrainBlock[256];
        final TreeSites.Recorder recorder = new TreeSites.Recorder();
        final TreeSites.Canopy canopy = new TreeSites.Canopy();
    }

    static void preview(
            TerrainMap.Sample sample,
            Biome biome,
            Biome reported,
            boolean bareStone,
            SurfaceRules rules,
            TerrainHeightReference heights,
            SurfaceTranscription surfaces,
            Boulders boulders,
            TreeSites trees,
            GroundCover cover,
            int footprint,
            int x,
            int z,
            Scratch scratch,
            ICubicTerrainPreview.ColumnConsumer target) {
        int[] bottoms = scratch.bottoms;
        TerrainBlock[] blocks = scratch.blocks;
        int count = ColumnBlocks.runs(sample, bareStone, rules, heights, bottoms, blocks);
        target.begin(reported);
        // Boulders show in fine cells only: coarser cells are wider than most boulders.
        Boulders.Site boulder =
                boulders == null || footprint >= BOULDER_FOOTPRINT ? null : boulders.at(x, z);
        // With tree sites or a boulder the column is recorded first, then replayed with the
        // boulder's rock on its ground and its trees in the sky.
        TreeSites.Recorder recorded = trees == null && boulder == null ? null : scratch.recorder;
        if (recorded != null) recorded.reset();
        ICubicTerrainPreview.ColumnConsumer consumer = recorded == null ? target : recorded;
        // The preview shows surfaces only: the realms below the crust floor are one run of rock.
        if (heights.worldMinimumY < heights.minimumY)
            consumer.addRun(
                    heights.worldMinimumY, heights.minimumY, state(RealmMap.realms().get(0).rock));
        SurfaceTranscription.Surface surface = null;
        int index = 0;
        if (surfaces != null) {
            // The same kept band as native cubes, from this column's own runs.
            TerrainBlock[] riven = scratch.riven;
            java.util.Arrays.fill(riven, TerrainBlock.AIR);
            int ground = Integer.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                for (int y = Math.max(0, bottoms[i]); y < Math.min(256, bottoms[i + 1]); y++)
                    riven[y] = blocks[i];
                if (blocks[i] != TerrainBlock.AIR && blocks[i] != TerrainBlock.WATER)
                    ground = bottoms[i + 1];
            }
            surface = surfaces.column(riven, ground, biome, x, z);
        }
        int lo = surface == null ? Integer.MAX_VALUE : surface.bandBottom(index);
        int hi = lo == Integer.MAX_VALUE ? lo : surface.bandTop(index);
        // The preview cannot light air under water: air below a column's highest water (an
        // underground lake's dome under a pond) never shows from the sky and previews as stone.
        int highestWater = -1;
        for (int i = 0; i < count; i++) if (blocks[i] == TerrainBlock.WATER) highestWater = i;
        for (int i = 0; i < count; i++) {
            // Buried magma never shows from the sky, and the preview cannot light emitting or
            // translucent blocks: a frozen pool in an entrance previews as packed ice.
            IBlockState own =
                    blocks[i] == TerrainBlock.LAVA
                                    || (blocks[i] == TerrainBlock.AIR && i < highestWater)
                            ? Blocks.STONE.getDefaultState()
                            : blocks[i] == TerrainBlock.ICE
                                    ? Blocks.PACKED_ICE.getDefaultState()
                                    : state(blocks[i], biome);
            int bottom = bottoms[i], top = bottoms[i + 1];
            // Below and above a column's kept band the run is Riven's own; inside it, per block.
            if (bottom < Math.min(top, lo)) consumer.addRun(bottom, Math.min(top, lo), own);
            for (int y = Math.max(bottom, lo); y < Math.min(top, hi); y++) {
                IBlockState kept = previewable(surface.at(index, y));
                consumer.addRun(y, y + 1, kept != null ? kept : own);
            }
            if (Math.max(bottom, hi) < top) consumer.addRun(Math.max(bottom, hi), top, own);
        }
        if (recorded == null) return;
        // The boulder's rock replaces the column's ground block and the air above it, as the
        // cube writes it, so trees see its top as ground.
        if (boulder != null)
            recorded.overwrite(boulder.bottom(x, z), boulder.top(x, z), state(boulder.rock));
        // Ground cover stands on the column's own surface: the top of its highest non-air run.
        int surfaceY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++)
            if (blocks[i] != TerrainBlock.AIR) surfaceY = bottoms[i + 1];
        if (trees != null)
            replayWithTrees(
                    recorded,
                    trees,
                    cover,
                    surfaceY,
                    footprint,
                    x,
                    z,
                    reported,
                    sample,
                    scratch.canopy,
                    target);
        else
            for (int i = 0; i < recorded.count; i++)
                target.addRun(recorded.bottoms[i], recorded.tops[i], recorded.states[i]);
    }

    /** Footprints from which the preview leaves boulders out (and ground cover stops avoiding them). */
    static final int BOULDER_FOOTPRINT = 4;

    /** Writes the boulder rock inside one cube, from each column's ground block to its top. */
    static void placeBoulders(
            Boulders boulders,
            io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer primer,
            int cubeX,
            int cubeY,
            int cubeZ) {
        int x0 = cubeX << 4, y0 = cubeY << 4, z0 = cubeZ << 4, reach = boulders.reach();
        boulders.sites(
                x0 - reach,
                z0 - reach,
                x0 + 16 + reach,
                z0 + 16 + reach,
                site -> {
                    IBlockState rock = state(site.rock);
                    for (int bz = Math.max(z0, site.z - site.radius);
                            bz <= Math.min(z0 + 15, site.z + site.radius);
                            bz++)
                        for (int bx = Math.max(x0, site.x - site.radius);
                                bx <= Math.min(x0 + 15, site.x + site.radius);
                                bx++) {
                            int bottom = site.bottom(bx, bz);
                            if (bottom == Integer.MIN_VALUE) continue;
                            int top = Math.min(site.top(bx, bz), y0 + 16);
                            for (int y = Math.max(bottom, y0); y < top; y++)
                                primer.setBlockState(bx - x0, y - y0, bz - z0, rock);
                        }
                });
    }

    private static void replayWithTrees(
            TreeSites.Recorder r,
            TreeSites trees,
            GroundCover cover,
            int surfaceY,
            int footprint,
            int x,
            int z,
            Biome reported,
            TerrainMap.Sample sample,
            TreeSites.Canopy canopy,
            ICubicTerrainPreview.ColumnConsumer target) {
        int top = r.count - 1;
        while (top >= 0 && r.states[top].getMaterial() == Material.AIR) top--;
        canopy.clear();
        if (top >= 0)
            trees.canopy(x, z, footprint, r.tops[top], reported, sample, r.states[top], canopy);
        // The cover the cube writes on the surface where terrain, boulders and trees leave air.
        IBlockState[] plants = null;
        IBlockState ground = surfaceY == Integer.MIN_VALUE ? null : r.at(surfaceY - 1);
        if (cover != null && top >= 0 && ground != null)
            plants =
                    cover.preview(
                            reported, ground, x, surfaceY, z, footprint, y -> taken(r, canopy, y));
        if (canopy.count == 0 && plants == null) {
            for (int i = 0; i < r.count; i++) target.addRun(r.bottoms[i], r.tops[i], r.states[i]);
            return;
        }
        // Everything above the highest solid or water run is open sky; trees replace only that.
        for (int i = 0; i < top; i++) target.addRun(r.bottoms[i], r.tops[i], r.states[i]);
        // Under a trunk the top block is dirt, as the cube writes it.
        if (canopy.soil != null && r.tops[top] - r.bottoms[top] > 1) {
            target.addRun(r.bottoms[top], r.tops[top] - 1, r.states[top]);
            target.addRun(r.tops[top] - 1, r.tops[top], canopy.soil);
        } else if (canopy.soil != null) target.addRun(r.bottoms[top], r.tops[top], canopy.soil);
        else target.addRun(r.bottoms[top], r.tops[top], r.states[top]);
        IBlockState air = Blocks.AIR.getDefaultState();
        int cursor = r.tops[top], end = r.tops[r.count - 1];
        if (plants != null)
            for (int i = 0; i < plants.length; i++) {
                int y = surfaceY + i;
                if (y > cursor) target.addRun(cursor, y, air);
                target.addRun(y, y + 1, plants[i]);
                cursor = y + 1;
            }
        for (int i = 0; i < canopy.count; i++) {
            if (canopy.bottoms[i] > cursor) target.addRun(cursor, canopy.bottoms[i], air);
            target.addRun(canopy.bottoms[i], canopy.tops[i], canopy.states[i]);
            cursor = canopy.tops[i];
        }
        if (cursor < end) target.addRun(cursor, end, air);
    }

    /** Whether terrain the column records or a tree block holds Y. */
    private static boolean taken(TreeSites.Recorder r, TreeSites.Canopy canopy, int y) {
        IBlockState state = r.at(y);
        if (state != null && state.getMaterial() != Material.AIR) return true;
        for (int i = 0; i < canopy.count; i++)
            if (y >= canopy.bottoms[i] && y < canopy.tops[i]) return true;
        return false;
    }
}
