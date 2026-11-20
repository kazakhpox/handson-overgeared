package net.handsonovergeared.client;

import net.minecraft.resources.ResourceLocation;

import com.pockyl.diegesis.api.client.MeshShape;

import net.handsonovergeared.HandsOnOvergeared;
import net.handsonovergeared.station.ForgeRhythm;

/**
 * The forging rhythm drawn as a plate standing over the anvil, made by code: the track, the good and perfect zones, how many strikes
 * are done, and the marker. It stands in the node's upright plane x = 0, read from +X: the bar's start on the screen's left (+Z).
 * Every colour is a swatch of one small texture.
 */
final class RhythmBar {
    static final ResourceLocation TEXTURE = HandsOnOvergeared.id("block/rhythm");

    /** Width and height of the track, pixels. */
    static final float WIDTH = 15.0F;
    static final float HEIGHT = 1.6F;
    private static final float FRAME = 0.3F;
    private static final float PROGRESS_TOP = -0.55F;
    private static final float PROGRESS_BOTTOM = -1.05F;

    /** Swatches of the texture, a 4 by 4 grid of solid colours. */
    static final int FRAME_COLOUR = 0;
    static final int TRACK = 1;
    static final int GOOD = 2;
    static final int PERFECT = 3;
    static final int MARKER = 4;
    static final int PROGRESS_TRACK = 5;
    static final int PROGRESS_FILL = 6;
    static final int MISS = 7;
    static final int PERFECT_FLASH = 8;

    private RhythmBar() {
    }

    /** The scene z of a value of the bar. */
    static float z(float value) {
        float t = (value - ForgeRhythm.BAR_MIN) / (ForgeRhythm.BAR_MAX - ForgeRhythm.BAR_MIN);
        return WIDTH / 2.0F - t * WIDTH;
    }

    /** The plate: frame, track and zones, and the strikes done as a strip under it ({@code done} of {@code max}). */
    static MeshShape plate(ForgeRhythm.Zones zones, boolean showZones, int done, int max, boolean flash) {
        MeshShape.Builder mesh = MeshShape.builder();
        float half = WIDTH / 2.0F;
        quad(mesh, 0.0F, -FRAME + PROGRESS_BOTTOM, HEIGHT + FRAME, half + FRAME, -half - FRAME, FRAME_COLOUR);
        quad(mesh, 0.04F, 0.0F, HEIGHT, half, -half, TRACK);
        if (showZones) {
            quad(mesh, 0.08F, 0.0F, HEIGHT, z(zones.goodStart()), z(zones.goodEnd()), GOOD);
            quad(mesh, 0.12F, 0.0F, HEIGHT, z(zones.perfectStart()), z(zones.perfectEnd()), flash ? PERFECT_FLASH : PERFECT);
        }
        if (max > 0) {
            quad(mesh, 0.04F, PROGRESS_BOTTOM, PROGRESS_TOP, half, -half, PROGRESS_TRACK);
            float fraction = Math.min(1.0F, done / (float) max);
            if (fraction > 0.0F) {
                quad(mesh, 0.08F, PROGRESS_BOTTOM, PROGRESS_TOP, half, half - WIDTH * fraction, PROGRESS_FILL);
            }
        }
        return mesh.build();
    }

    /** The marker: a needle standing across the track, its own node moved along z. */
    static MeshShape marker() {
        MeshShape.Builder mesh = MeshShape.builder();
        quad(mesh, 0.2F, -0.45F, HEIGHT + 0.45F, 0.22F, -0.22F, MARKER);
        quad(mesh, 0.18F, -0.6F, HEIGHT + 0.6F, 0.36F, -0.36F, FRAME_COLOUR);
        return mesh.build();
    }

    /**
     * A rectangle in the plane x = {@code x}, from {@code zLeft} (the screen's left) to {@code zRight}, both faces, so that it reads
     * from either side of the anvil.
     */
    private static void quad(MeshShape.Builder mesh, float x, float y0, float y1, float zLeft, float zRight, int swatch) {
        float u = ((swatch % 4) * 4 + 2) / 16.0F;
        float v = ((swatch / 4) * 4 + 2) / 16.0F;
        // Seen from +X: the screen's right is -Z, so counter-clockwise runs bottom left, bottom right, top right, top left.
        mesh.corner(x, y0, zLeft, u, v, 1, 0, 0)
                .corner(x, y0, zRight, u, v, 1, 0, 0)
                .corner(x, y1, zRight, u, v, 1, 0, 0)
                .corner(x, y1, zLeft, u, v, 1, 0, 0);
        mesh.corner(-x, y0, zRight, u, v, -1, 0, 0)
                .corner(-x, y0, zLeft, u, v, -1, 0, 0)
                .corner(-x, y1, zLeft, u, v, -1, 0, 0)
                .corner(-x, y1, zRight, u, v, -1, 0, 0);
    }
}
