package net.handsonovergeared.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import com.pockyl.diegesis.api.client.MeshShape;
import com.pockyl.diegesis.api.client.NodeView;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.scene.Visual;

import net.handsonovergeared.HandsOnOvergeared;
import net.handsonovergeared.furnace.FurnaceStations;

/**
 * An Overgeared furnace worked by hand, as the furnace of Hands-On Vanilla: the block steps aside for the scene's copy, whose double
 * doors swing open on the mouth and the firebox. Inside, what smelts stands on the grate and the fuel in the firebox; while it burns
 * the lining and the grate glow, the coals turn to embers and flames lick up. A strip along the grate's front lip fills as it smelts,
 * and one along the firebox's lip shows the fuel left.
 */
public final class FurnaceBehavior implements StationBehavior {
    private static final float OPEN_ANGLE = 105.0F;
    private static final float OPEN_TIME = 0.35F;
    private static final ResourceLocation BAR_TEXTURE = HandsOnOvergeared.id("block/rhythm");

    private float open;
    private boolean wasInUse;
    private float shownCook;
    private float shownBurn;
    private float crackle;
    private Boolean shownLit;

    public FurnaceBehavior(StationView view) {
    }

    @Override
    public void onSceneReady(StationView view) {
        shownLit = null;
    }

    @Override
    public boolean holdsBlock(StationView view) {
        // The doors are still swinging shut: the copy stands in for the block until they have closed.
        return open > 0.0F;
    }

    @Override
    public void frame(StationView view, float dt) {
        boolean inUse = view.isInUse();
        if (inUse != wasInUse) {
            wasInUse = inUse;
            view.playSound(inUse ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, "body", 0.5F, 0.9F);
        }
        open = Mth.clamp(open + (inUse ? dt : -dt) / OPEN_TIME, 0.0F, 1.0F);
        float eased = open * open * (3.0F - 2.0F * open);
        boolean shown = view.isBlockHidden();
        // The right leaves open to the right, the left ones to the left: both swing out towards the front.
        for (String leaf : new String[]{"mouth_right", "firebox_right"}) {
            view.node(leaf).ifPresent(node -> {
                node.setVisible(shown);
                node.rotateTo(0.0F, OPEN_ANGLE * eased, 0.0F, 0.0F);
            });
        }
        for (String leaf : new String[]{"mouth_left", "firebox_left"}) {
            view.node(leaf).ifPresent(node -> {
                node.setVisible(shown);
                node.rotateTo(0.0F, -OPEN_ANGLE * eased, 0.0F, 0.0F);
            });
        }

        CompoundTag data = view.state().data();
        int burn = data.getInt(FurnaceStations.BURN);
        int burnMax = Math.max(1, data.getInt(FurnaceStations.BURN_MAX));
        int cookMax = data.getInt(FurnaceStations.COOK_MAX);
        boolean lit = burn > 0;
        float burnFraction = Mth.clamp(burn / (float) burnMax, 0.0F, 1.0F);
        view.node("body").ifPresent(node -> {
            node.setVisible(shown);
            if (shownLit == null || shownLit != lit) {
                shownLit = lit;
                String model = node.extra().has(lit ? "lit" : "unlit") ? node.extra().get(lit ? "lit" : "unlit").getAsString() : null;
                if (model != null) {
                    node.setVisual(Visual.ofModel(ResourceLocation.parse(model)).asLitAsBlock());
                }
            }
        });
        view.node("fire").ifPresent(node -> {
            node.setEmitting(shown && lit);
            node.setEmitRate(3.0F + 8.0F * burnFraction);
            node.setLight(shown && lit ? 10 + Math.round(4 * burnFraction) : 0);
        });
        if (shown && lit) {
            crackle -= dt;
            if (crackle <= 0.0F) {
                crackle = 1.2F + (float) Math.random() * 2.0F;
                view.playSound(SoundEvents.FURNACE_FIRE_CRACKLE, "fire", 0.5F, 1.0F);
            }
        }

        // The server reports every few ticks: the strips run on smoothly between its reports.
        float cookTarget = cookMax > 0 ? data.getInt(FurnaceStations.COOK) / (float) cookMax : 0.0F;
        if (cookMax <= 0 || cookTarget < shownCook - 0.2F) {
            shownCook = cookTarget;
        } else {
            float ahead = lit ? dt * 20.0F / cookMax : 0.0F;
            shownCook = Math.max(cookTarget, Math.min(shownCook + ahead, cookTarget + 5.0F / cookMax));
        }
        shownBurn += (burnFraction - shownBurn) * Math.min(1.0F, dt * 6.0F);
        NodeView bars = view.node("bars").orElse(null);
        if (bars != null) {
            boolean visible = shown && open > 0.6F;
            bars.setVisible(visible);
            if (visible) {
                bars.setShape(strips(Mth.clamp(shownCook, 0.0F, 1.0F), cookMax > 0, lit ? shownBurn : 0.0F), BAR_TEXTURE, true);
            }
        }
    }

    /**
     * The two strips, in scene pixels facing the front (-Z): the smelting along the grate's front lip in the mouth (y 8), the fuel along
     * the firebox's lip (y 1); each fills from the screen's left (+X).
     */
    private static MeshShape strips(float cook, boolean smelting, float fuel) {
        MeshShape.Builder mesh = MeshShape.builder();
        strip(mesh, 8.05F, 8.75F, smelting ? cook : 0.0F, 6);
        strip(mesh, 1.05F, 1.55F, fuel, 7);
        return mesh.build();
    }

    private static void strip(MeshShape.Builder mesh, float y0, float y1, float fill, int swatch) {
        float left = 12.6F;
        float right = 3.4F;
        rect(mesh, left, right, y0, y1, 0.3F, 0);
        rect(mesh, left - 0.15F, right + 0.15F, y0 + 0.12F, y1 - 0.12F, 0.34F, 1);
        if (fill > 0.0F) {
            float end = left - 0.15F - (left - right - 0.3F) * fill;
            rect(mesh, left - 0.15F, end, y0 + 0.12F, y1 - 0.12F, 0.38F, swatch);
        }
    }

    /** A rectangle facing -Z, {@code depth} in front of its plane, from {@code left} (screen left, +X) to {@code right}. */
    private static void rect(MeshShape.Builder mesh, float left, float right, float y0, float y1, float depth, int swatch) {
        float u = ((swatch % 4) * 4 + 2) / 16.0F;
        float v = ((swatch / 4) * 4 + 2) / 16.0F;
        // In the mouth, a little behind its front face: the strip stands on the lip, inside the opening.
        float z = 0.9F - depth;
        mesh.corner(left, y0, z, u, v, 0, 0, -1)
                .corner(right, y0, z, u, v, 0, 0, -1)
                .corner(right, y1, z, u, v, 0, 0, -1)
                .corner(left, y1, z, u, v, 0, 0, -1);
    }
}
