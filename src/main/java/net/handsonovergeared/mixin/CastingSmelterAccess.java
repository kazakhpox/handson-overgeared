package net.handsonovergeared.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.handsonovergeared.furnace.FurnaceTimes;
import net.stirdrem.overgeared.block.entity.CastingSmelterBlockEntity;

/** The fire and the progress Overgeared keeps private in the CastingSmelter block entity, for the furnace station. */
@Mixin(value = CastingSmelterBlockEntity.class, remap = false)
public interface CastingSmelterAccess extends FurnaceTimes {
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
