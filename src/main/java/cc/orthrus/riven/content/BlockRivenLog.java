package cc.orthrus.riven.content;

import net.minecraft.block.BlockLog;
import net.minecraft.block.SoundType;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;

/** A Riven species' log (riven-content.json woods): bark sides and end-grain caps, any axis. */
final class BlockRivenLog extends BlockLog {
    final String species;

    BlockRivenLog(String species) {
        this.species = species;
        setSoundType(SoundType.WOOD);
        setDefaultState(blockState.getBaseState().withProperty(LOG_AXIS, EnumAxis.Y));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, LOG_AXIS);
    }

    @SuppressWarnings("deprecation") // Block's 1.12 API: deprecated to call, not to override.
    @Override
    public IBlockState getStateFromMeta(int meta) {
        EnumAxis axis;
        switch (meta & 12) {
            case 4:
                axis = EnumAxis.X;
                break;
            case 8:
                axis = EnumAxis.Z;
                break;
            case 12:
                axis = EnumAxis.NONE;
                break;
            default:
                axis = EnumAxis.Y;
        }
        return getDefaultState().withProperty(LOG_AXIS, axis);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        switch (state.getValue(LOG_AXIS)) {
            case X:
                return 4;
            case Z:
                return 8;
            case NONE:
                return 12;
            default:
                return 0;
        }
    }
}
