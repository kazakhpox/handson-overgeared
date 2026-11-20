package net.handsonovergeared.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.api.Diegesis;

import net.stirdrem.overgeared.block.entity.AbstractSmithingAnvilBlockEntity;

/**
 * Overgeared keeps hot metal on the anvil from cooling while somebody has the anvil's screen open. Working at the station is the
 * same as having it open: the metal stays hot while the smith is at it.
 */
@Mixin(value = AbstractSmithingAnvilBlockEntity.class, remap = false)
public abstract class AnvilCoolingMixin {
    @Inject(method = "tickHeatedIngredients", at = @At("HEAD"), cancellable = true)
    private void handson$hotWhileWorked(Level level, BlockPos pos, BlockState state, CallbackInfo info) {
        if (level instanceof ServerLevel server && Diegesis.activeStation(server, pos).isPresent()) {
            info.cancel();
        }
    }
}
