package cc.orthrus.riven;

import net.minecraft.block.Block;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sapling growth: a Riven sapling grows into one of its wood's species from riven-trees.json, drawn
 * by the same shapes as generated trees. Four saplings of a wood in a square grow its 2 x 2 species
 * where it has one; otherwise a single-trunk species. Logs need free space (air, leaves, plants that
 * give way, or the saplings); leaves fill what is free; root chains stand where their foot rests on
 * solid ground. In a Riven world the species use the world's recorded mappings.
 */
final class TreeGrowth {
    private TreeGrowth() {}

    static boolean grow(World world, BlockPos pos, String wood, Random random) {
        TreeSites sites = RivenWorldType.trees(world);
        Map<String, TreeShapes.Species> all =
                sites != null ? sites.species() : TreeSites.defaultSpecies();
        List<TreeShapes.Species> single = new ArrayList<>(), square = new ArrayList<>();
        for (TreeShapes.Species s : all.values())
            if (s.wood.equals(wood)) (s.width == 2 ? square : single).add(s);
        Block sapling = world.getBlockState(pos).getBlock();
        if (!square.isEmpty())
            for (int oz = 0; oz >= -1; oz--)
                for (int ox = 0; ox >= -1; ox--) {
                    BlockPos corner = pos.add(ox, 0, oz);
                    if (world.getBlockState(corner).getBlock() == sapling
                            && world.getBlockState(corner.east()).getBlock() == sapling
                            && world.getBlockState(corner.south()).getBlock() == sapling
                            && world.getBlockState(corner.south().east()).getBlock() == sapling)
                        return place(world, corner, pick(square, random), sapling, random);
                }
        return !single.isEmpty() && place(world, pos, pick(single, random), sapling, random);
    }

    private static TreeShapes.Species pick(List<TreeShapes.Species> list, Random random) {
        return list.get(random.nextInt(list.size()));
    }

    private static boolean place(
            World world,
            BlockPos corner,
            TreeShapes.Species species,
            Block sapling,
            Random random) {
        TreeShapes.Shape shape = new TreeShapes.Shape();
        TreeShapes.shape(species, random.nextLong(), shape);
        int roots = (1 << shape.chains) - 1;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < shape.count; i++) {
            int p = shape.blocks[i], kind = TreeShapes.kind(p);
            at.setPos(
                    corner.getX() + TreeShapes.dx(p),
                    corner.getY() + TreeShapes.dy(p),
                    corner.getZ() + TreeShapes.dz(p));
            if (!world.isBlockLoaded(at)) return false;
            boolean free = free(world, at, sapling);
            if (kind == TreeShapes.ROOT && !free) roots &= ~(1 << TreeShapes.chain(p));
            else if (kind == TreeShapes.LOG && !free) return false;
        }
        for (int c = 0; c < shape.chains; c++) {
            at.setPos(
                    corner.getX() + shape.footX[c],
                    corner.getY() - 1,
                    corner.getZ() + shape.footZ[c]);
            if (!world.getBlockState(at).isSideSolid(world, at, EnumFacing.UP)) roots &= ~(1 << c);
        }
        // The saplings give way first, so the trunk and any leaves around it replace them.
        for (int j = 0; j < species.width; j++)
            for (int i = 0; i < species.width; i++)
                world.setBlockState(corner.add(i, 0, j), Blocks.AIR.getDefaultState(), 4);
        for (int i = 0; i < shape.count; i++) {
            int p = shape.blocks[i], kind = TreeShapes.kind(p);
            if (kind == TreeShapes.ROOT && (roots & 1 << TreeShapes.chain(p)) == 0) continue;
            at.setPos(
                    corner.getX() + TreeShapes.dx(p),
                    corner.getY() + TreeShapes.dy(p),
                    corner.getZ() + TreeShapes.dz(p));
            IBlockState existing = world.getBlockState(at);
            if (kind == TreeShapes.LEAVES) {
                if (free(world, at, sapling) && existing.getMaterial() != Material.LEAVES)
                    world.setBlockState(at, species.leaves, 3);
            } else if (free(world, at, sapling))
                world.setBlockState(at, species.log(TreeShapes.axis(p)), 3);
        }
        for (int j = 0; j < species.width; j++)
            for (int i = 0; i < species.width; i++) {
                BlockPos below = corner.add(i, -1, j);
                if (world.getBlockState(below).getBlock() instanceof BlockGrass)
                    world.setBlockState(below, Blocks.DIRT.getDefaultState(), 3);
            }
        return true;
    }

    /** Air, leaves, a plant that gives way, or one of the growing saplings. */
    private static boolean free(World world, BlockPos pos, Block sapling) {
        IBlockState state = world.getBlockState(pos);
        return state.getBlock().isAir(state, world, pos)
                || state.getMaterial() == Material.LEAVES
                || state.getBlock() == sapling
                || state.getBlock().isReplaceable(world, pos);
    }
}
