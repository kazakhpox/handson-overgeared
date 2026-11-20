package net.handsonovergeared.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

/** Draws text standing in a scene, read by a camera on the scene's +X side (the side the smith works from). */
final class TextPainter {
    private TextPainter() {
    }

    /**
     * Draws {@code text} centred on the node's origin, {@code height} pixels high (a line of the font), raised by {@code up} pixels.
     *
     * @param argb colour; its alpha fades the text
     */
    static void draw(PoseStack pose, MultiBufferSource buffers, Component text, float height, float up, int argb, boolean mirrored) {
        if ((argb >>> 24) < 8) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        float scale = height / font.lineHeight;
        pose.pushPose();
        pose.translate(0.0F, up, 0.0F);
        // Text x runs to the screen's right (scene -Z from +X), text y downwards; the glyphs face the camera at +X.
        float side = mirrored ? -1.0F : 1.0F;
        Matrix4f face = new Matrix4f(
                0.0F, 0.0F, -scale * side, 0.0F,
                0.0F, -scale, 0.0F, 0.0F,
                scale * side, 0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        pose.last().pose().mul(face);
        float width = font.width(text);
        font.drawInBatch(text, -width / 2.0F, -font.lineHeight / 2.0F, argb, false, pose.last().pose(), buffers,
                Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
