package net.handsonovergeared.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.handsonovergeared.furnace.FurnaceTimes;
import net.stirdrem.overgeared.block.entity.AlloySmelterBlockEntity;

/** The fire and the progress Overgeared keeps private in the AlloySmelter block entity, for the furnace station. */
@Mixin(value = AlloySmelterBlockEntity.class, remap = false)
public interface AlloySmelterAccess extends FurnaceTimes {
    @Override
    @Accessor("burnTime")
    int handson$burnTime();

    @Override
    @Accessor("maxBurnTime")
    int handson$maxBurnTime();

    @Override
    @Accessor("cookTime")
    int handson$cookTime();

    @Override
    @Accessor("cookTimeTotal")
    int handson$cookTimeTotal();
}
