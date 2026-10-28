package cc.orthrus.riven.content;

import net.minecraft.block.state.IBlockState;

/** A Riven ground decoration (riven-content.json decorations): what it stands on. */
public interface GroundPlant {
    boolean growsOn(IBlockState ground);

    /** A two-block plant: its forms, and the state of either half in a form. */
    interface Tall extends GroundPlant {
        int forms();

        IBlockState part(boolean upper, int form);
    }
}
