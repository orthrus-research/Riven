package cc.orthrus.riven.content;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;

/** A Riven species' planks (riven-content.json woods): what its logs craft into. */
final class BlockRivenPlanks extends Block {
    final String species;

    BlockRivenPlanks(String species) {
        super(Material.WOOD);
        this.species = species;
        setSoundType(SoundType.WOOD);
    }
}
