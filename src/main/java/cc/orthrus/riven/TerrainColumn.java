package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Boulders;
import cc.orthrus.riven.worldgen.RealmMap;
import cc.orthrus.riven.worldgen.TerrainBlock;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;
import cc.orthrus.riven.worldgen.engine.AssembledColumn;
import cc.orthrus.riven.worldgen.engine.Assembler;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Minecraft states for the entries Riven's column engine assembles ({@link Assembler}). */
final class TerrainColumn {
    /** Entries that resolved to a later candidate than their first (a stand-in). */
    private static final List<String> STAND_INS = new ArrayList<>();

    private static final IBlockState[] STATES = catalogued();

    /**
     * Each catalogue entry's state (riven-catalogue.json), its first candidate that resolves.
     * Until the remaining Java block rules move into the catalogue, the legacy table is built
     * beside it and any difference fails startup.
     */
    private static IBlockState[] catalogued() {
        IBlockState[] legacy = states(), result = new IBlockState[legacy.length];
        List<String> differ = new ArrayList<>();
        for (TerrainBlock b : TerrainBlock.values()) {
            IBlockState state = null;
            for (int i = 0; i < b.candidates.size() && state == null; i++) {
                state = Mappings.parse(b.candidates.get(i));
                if (state != null && i > 0) STAND_INS.add(b.id);
            }
            if (state == null)
                throw new IllegalStateException(b.id + ": no candidate block is registered");
            result[b.index()] = state;
            if (!state.equals(legacy[b.index()]))
                differ.add(b.id + " " + state + " (legacy " + legacy[b.index()] + ")");
        }
        if (!differ.isEmpty())
            throw new IllegalStateException(
                    "Riven catalogue differs from the legacy table: " + differ);
        return result;
    }

    /** The startup log line: how the catalogue resolved. */
    static String catalogueSummary() {
        return "Riven catalogue: "
                + STATES.length
                + " entries resolve as the legacy table; stand-ins "
                + STAND_INS;
    }

    private static IBlockState[] states() {
        IBlockState[] result = new IBlockState[TerrainBlock.values().length];
        result[at("air")] = Blocks.AIR.getDefaultState();
        result[at("rock.bedrock")] = Blocks.BEDROCK.getDefaultState();
        result[at("rock.stone")] = Blocks.STONE.getDefaultState();
        result[at("fluid.water")] = Blocks.WATER.getDefaultState();
        result[at("sediment.sand")] = Blocks.SAND.getDefaultState();
        result[at("sediment.gravel")] = Blocks.GRAVEL.getDefaultState();
        result[at("soil.dirt")] = Blocks.DIRT.getDefaultState();
        result[at("soil.grass")] = Blocks.GRASS.getDefaultState();
        result[at("ice.snow")] = Blocks.SNOW.getDefaultState();
        result[at("rock.granite")] =
                Blocks.STONE
                        .getDefaultState()
                        .withProperty(BlockStone.VARIANT, BlockStone.EnumType.GRANITE);
        result[at("rock.diorite")] =
                Blocks.STONE
                        .getDefaultState()
                        .withProperty(BlockStone.VARIANT, BlockStone.EnumType.DIORITE);
        result[at("rock.andesite")] =
                Blocks.STONE
                        .getDefaultState()
                        .withProperty(BlockStone.VARIANT, BlockStone.EnumType.ANDESITE);
        result[at("rock.sandstone")] = Blocks.SANDSTONE.getDefaultState();
        result[at("rock.red_sandstone")] = Blocks.RED_SANDSTONE.getDefaultState();
        result[at("soil.coarse_dirt")] =
                Blocks.DIRT
                        .getDefaultState()
                        .withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.COARSE_DIRT);
        result[at("sediment.clay")] = Blocks.CLAY.getDefaultState();
        result[at("rock.terracotta")] = Blocks.HARDENED_CLAY.getDefaultState();
        result[at("rock.terracotta_orange")] = terracotta(EnumDyeColor.ORANGE);
        result[at("rock.terracotta_yellow")] = terracotta(EnumDyeColor.YELLOW);
        result[at("rock.terracotta_brown")] = terracotta(EnumDyeColor.BROWN);
        result[at("rock.terracotta_red")] = terracotta(EnumDyeColor.RED);
        result[at("rock.terracotta_white")] = terracotta(EnumDyeColor.WHITE);
        result[at("rock.terracotta_gray")] = terracotta(EnumDyeColor.GRAY);
        result[at("rock.terracotta_black")] = terracotta(EnumDyeColor.BLACK);
        // Soils by climate (riven-surface.json), Riven's own but for vanilla's podzol and red sand.
        IBlockState dirt = Blocks.DIRT.getDefaultState();
        result[at("soil.loam")] = named("riven:loam", null, null, dirt);
        result[at("soil.silt")] = named("riven:silt", null, null, dirt);
        result[at("soil.sandy_loam")] = named("riven:sandy_loam", null, null, dirt);
        result[at("soil.peat")] = named("riven:peat", null, null, dirt);
        result[at("soil.podzol")] =
                Blocks.DIRT
                        .getDefaultState()
                        .withProperty(BlockDirt.VARIANT, BlockDirt.DirtType.PODZOL);
        result[at("sediment.red_sand")] =
                Blocks.SAND
                        .getDefaultState()
                        .withProperty(
                                net.minecraft.block.BlockSand.VARIANT,
                                net.minecraft.block.BlockSand.EnumType.RED_SAND);
        result[at("fluid.lava")] = Blocks.LAVA.getDefaultState();
        // Cave decoration: Riven's own blocks, vanilla stand-ins should one be missing.
        IBlockState clay = Blocks.CLAY.getDefaultState(), air = Blocks.AIR.getDefaultState();
        IBlockState brown = Blocks.BROWN_MUSHROOM.getDefaultState();
        result[at("soil.mud")] = named("riven:mud", null, null, clay);
        result[at("sediment.salt_sand")] =
                named("riven:salt_sand", null, null, Blocks.SAND.getDefaultState());
        result[at("sediment.volcanic_ash")] =
                named("riven:volcanic_ash", null, null, Blocks.GRAVEL.getDefaultState());
        result[at("rock.magma")] = Blocks.MAGMA.getDefaultState();
        result[at("rock.netherrack")] = Blocks.NETHERRACK.getDefaultState();
        result[at("ice.ice")] = Blocks.ICE.getDefaultState();
        result[at("ice.packed_ice")] = Blocks.PACKED_ICE.getDefaultState();
        result[at("decor.cobweb")] = Blocks.WEB.getDefaultState();
        result[at("rock.dripstone_block")] =
                named("riven:dripstone_block", null, null, Blocks.STONE.getDefaultState());
        result[at("formation.dripstone_up")] =
                named("riven:pointed_dripstone", "direction", "up", air);
        result[at("formation.dripstone_down")] =
                named("riven:pointed_dripstone", "direction", "down", air);
        result[at("formation.ice_up")] = named("riven:pointed_ice", "direction", "up", air);
        result[at("formation.ice_down")] = named("riven:pointed_ice", "direction", "down", air);
        result[at("formation.salt_up")] = named("riven:pointed_salt", "direction", "up", air);
        result[at("formation.salt_down")] = named("riven:pointed_salt", "direction", "down", air);
        result[at("formation.basalt_up")] = named("riven:pointed_basalt", "direction", "up", air);
        result[at("formation.basalt_down")] =
                named("riven:pointed_basalt", "direction", "down", air);
        result[at("plant.hanging_roots")] = named("riven:hanging_roots", null, null, air);
        result[at("plant.ink_cap")] =
                named("riven:plant_ink_cap", null, null, Blocks.RED_MUSHROOM.getDefaultState());
        result[at("plant.pale_cave_mushroom")] =
                named("riven:plant_pale_cave_mushroom", null, null, brown);
        result[at("plant.brown_mushroom")] = brown;
        result[at("plant.red_mushroom")] = Blocks.RED_MUSHROOM.getDefaultState();
        result[at("plant.stonebell")] = named("riven:plant_stonebell", null, null, air);
        result[at("plant.glowing_mushroom")] =
                named("riven:plant_glowing_mushroom", null, null, brown);
        result[at("plant.green_biofilm")] =
                named("riven:submerged_green_biofilm", null, null, Blocks.WATER.getDefaultState());
        // Riven host stones, with vanilla stand-ins should a block be missing.
        IBlockState stone = Blocks.STONE.getDefaultState();
        for (TerrainBlock b : TerrainBlock.values())
            if (b.stoneId() != null) {
                IBlockState fallback =
                        b.id.equals("rock.limestone") || b.id.equals("rock.anorthosite")
                                ? result[at("rock.diorite")]
                                : b.id.equals("rock.gneiss")
                                                || b.id.equals("rock.quartzite")
                                                || b.id.equals("rock.soapstone")
                                                || b.id.equals("rock.kimberlite")
                                        ? stone
                                        : result[at("rock.andesite")];
                result[b.index()] = named("riven:" + b.stoneId(), null, null, fallback);
            }
        // Deep-realm and boundary rocks.
        for (TerrainBlock b : TerrainBlock.values())
            if (b.rockId() != null)
                result[b.index()] = named("riven:" + b.rockId(), null, null, stone);
        for (IBlockState state : result)
            if (state == null) throw new IllegalStateException("Unmapped terrain block");
        return result;
    }

    /** A legacy table slot by catalogue id. */
    private static int at(String id) {
        return TerrainBlock.of(id).index();
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
        return STATES[block.index()];
    }

    /** The block at Y of a sample's crust column, assembled afresh (reports). */
    static IBlockState blockAt(
            int y, TerrainMap.Sample sample, Assembler<TerrainMap.Sample> crust) {
        return state(crust.assemble(sample, new AssembledColumn()).at(y));
    }

    /**
     * One caller's reusable buffers for {@link #preview}: a preview tile's sampler, or a thread's
     * tree sites (whose trunk columns are previewed inside a preview column's trees, so the two
     * never share one). Nothing in them outlives a call.
     */
    static final class Scratch {
        final AssembledColumn column = new AssembledColumn();
        final TreeSites.Recorder recorder = new TreeSites.Recorder();
        final TreeSites.Canopy canopy = new TreeSites.Canopy();
    }

    static void preview(
            TerrainMap.Sample sample,
            Biome reported,
            Assembler<TerrainMap.Sample> crust,
            TerrainHeightReference heights,
            Boulders boulders,
            TreeSites trees,
            GroundCover cover,
            int footprint,
            int x,
            int z,
            Scratch scratch,
            ICubicTerrainPreview.ColumnConsumer target) {
        AssembledColumn column = crust.assemble(sample, scratch.column);
        int count = column.count();
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
        // The preview cannot light air under water: air below a column's highest water (an
        // underground lake's dome under a pond) never shows from the sky and previews as stone.
        int highestWater = -1;
        for (int i = 0; i < count; i++)
            if (column.runBlock(i) == TerrainBlock.WATER) highestWater = i;
        for (int i = 0; i < count; i++) {
            TerrainBlock block = column.runBlock(i);
            // Buried magma never shows from the sky, and the preview cannot light emitting or
            // translucent blocks: a frozen pool in an entrance previews as packed ice.
            IBlockState own =
                    block == TerrainBlock.LAVA || (block == TerrainBlock.AIR && i < highestWater)
                            ? Blocks.STONE.getDefaultState()
                            : block == TerrainBlock.ICE
                                    ? Blocks.PACKED_ICE.getDefaultState()
                                    : state(block);
            consumer.addRun(column.bottom(i), column.top(i), own);
        }
        if (recorded == null) return;
        // The boulder's rock replaces the column's ground block and the air above it, as the
        // cube writes it, so trees see its top as ground.
        if (boulder != null)
            recorded.overwrite(boulder.bottom(x, z), boulder.top(x, z), state(boulder.rock));
        // Ground cover stands on the column's own surface: the top of its highest non-air run.
        int surfaceY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++)
            if (column.runBlock(i) != TerrainBlock.AIR) surfaceY = column.top(i);
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
