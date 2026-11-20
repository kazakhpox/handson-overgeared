package net.handsonovergeared.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;

import com.pockyl.diegesis.api.client.DiegesisClient;

import net.stirdrem.overgeared.block.entity.AbstractSmithingAnvilBlockEntity;
import net.stirdrem.overgeared.block.entity.renderer.SmithingAnvilBlockEntityRenderer;

/**
 * Overgeared draws what lies on the anvil itself. While the station's scene stands in for it (laid out item by item, near enough to
 * be drawn), that drawing would lie twice on the anvil: it steps aside exactly then, and comes back for anvils farther away.
 */
@Mixin(value = SmithingAnvilBlockEntityRenderer.class, remap = false)
public abstract class AnvilRendererMixin {
    @Inject(method = "render(Lnet/stirdrem/overgeared/block/entity/AbstractSmithingAnvilBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), cancellable = true)
    private void handson$sceneDrawsIt(AbstractSmithingAnvilBlockEntity anvil, float partialTick, PoseStack pose, MultiBufferSource buffers,
            int light, int overlay, CallbackInfo info) {
        if (DiegesisClient.isSceneDrawn(anvil.getBlockPos())) {
            info.cancel();
        }
    }
}
