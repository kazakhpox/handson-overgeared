package net.handsonovergeared.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import com.pockyl.diegesis.api.client.MeshShape;
import com.pockyl.diegesis.api.client.NodeView;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;

import net.handsonovergeared.furnace.FurnaceStations;

/**
 * An Overgeared furnace worked by hand, as the furnaces of Hands-On Vanilla: the block steps aside for the scene's copy, whose door
 * swings open; inside, the coals glow and flames lick the grate while it burns, and a bar over the mouth fills as it smelts, the fuel
 * left under it.
 */
public final class FurnaceBehavior implements StationBehavior {
    private static final float OPEN_ANGLE = 112.0F;
    private static final float OPEN_TIME = 0.35F;

    private float open;
    private boolean wasInUse;
    private float shownCook;
    private float shownBurn;
    private float crackle;

    public FurnaceBehavior(StationView view) {
    }

    @Override
    public boolean holdsBlock(StationView view) {
        // The door is still swinging shut: the copy stands in for the block until it has closed.
        return open > 0.0F;
    }

    @Override
    public void frame(StationView view, float dt) {
        boolean inUse = view.isInUse();
        if (inUse != wasInUse) {
            wasInUse = inUse;
            view.playSound(inUse ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE, "door", 0.5F, 0.8F);
        }
        open = Mth.clamp(open + (inUse ? dt : -dt) / OPEN_TIME, 0.0F, 1.0F);
        float eased = open * open * (3.0F - 2.0F * open);
        boolean shown = view.isBlockHidden();
        for (String id : new String[]{"body", "door", "grate", "coals"}) {
            view.node(id).ifPresent(node -> node.setVisible(shown));
        }
        view.node("door").ifPresent(node -> node.rotateTo(0.0F, -OPEN_ANGLE * eased, 0.0F, 0.0F));

        CompoundTag data = view.state().data();
        int burn = data.getInt(FurnaceStations.BURN);
        int burnMax = Math.max(1, data.getInt(FurnaceStations.BURN_MAX));
        int cookMax = data.getInt(FurnaceStations.COOK_MAX);
        boolean lit = burn > 0;
        float burnFraction = Mth.clamp(burn / (float) burnMax, 0.0F, 1.0F);
        view.node("embers").ifPresent(node -> {
            node.setVisible(shown && lit);
            node.setTint(0xFF000000 | (int) (160 + 95 * burnFraction) << 16 | (int) (110 + 60 * burnFraction) << 8 | 0x50);
        });
        view.node("fire").ifPresent(node -> {
            node.setEmitting(shown && lit);
            node.setEmitRate(4.0F + 10.0F * burnFraction);
            node.setLight(shown && lit ? 11 + Math.round(3 * burnFraction) : 0);
        });
        if (shown && lit) {
            crackle -= dt;
            if (crackle <= 0.0F) {
                crackle = 1.2F + (float) Math.random() * 2.0F;
                view.playSound(SoundEvents.FURNACE_FIRE_CRACKLE, "fire", 0.5F, 1.0F);
            }
        }

        // The server reports every few ticks: the bars run on smoothly between its reports.
        float cookTarget = cookMax > 0 ? data.getInt(FurnaceStations.COOK) / (float) cookMax : 0.0F;
        shownCook = cookTarget < shownCook - 0.2F || cookMax <= 0 ? cookTarget
                : Math.min(cookTarget + 5.0F / Math.max(cookMax, 1), shownCook + (lit ? dt * 20.0F / Math.max(cookMax, 1) : 0.0F));
        shownCook = Math.max(shownCook, Math.min(cookTarget, 1.0F));
        shownBurn += (burnFraction - shownBurn) * Math.min(1.0F, dt * 6.0F);
        NodeView bar = view.node("bar").orElse(null);
        if (bar != null) {
            boolean visible = shown && (cookMax > 0 || lit);
            bar.setVisible(visible);
            if (visible) {
                bar.setShape(bars(Mth.clamp(shownCook, 0.0F, 1.0F), shownBurn), BAR_TEXTURE, true);
            }
        }
    }

    private static final net.minecraft.resources.ResourceLocation BAR_TEXTURE =
            net.handsonovergeared.HandsOnOvergeared.id("block/rhythm");

    /** The smelting bar over the mouth and the fuel bar under it, facing the front (-Z); x runs to the screen's left. */
    private static MeshShape bars(float cook, float fuel) {
        MeshShape.Builder mesh = MeshShape.builder();
        rect(mesh, 5.6F, -5.6F, -0.9F, 1.3F, 0.0F, 0);
        rect(mesh, 5.3F, -5.3F, 0.15F, 1.0F, 0.03F, 1);
        if (cook > 0.0F) {
            rect(mesh, 5.3F, 5.3F - 10.6F * cook, 0.15F, 1.0F, 0.06F, 6);
        }
        rect(mesh, 5.3F, -5.3F, -0.6F, -0.15F, 0.03F, 5);
        if (fuel > 0.0F) {
            rect(mesh, 5.3F, 5.3F - 10.6F * fuel, -0.6F, -0.15F, 0.06F, 7);
        }
        return mesh.build();
    }

    /** A rectangle facing -Z, {@code depth} in front of the node, from {@code left} (screen left, +X) to {@code right}. */
    private static void rect(MeshShape.Builder mesh, float left, float right, float y0, float y1, float depth, int swatch) {
        float u = ((swatch % 4) * 4 + 2) / 16.0F;
        float v = ((swatch / 4) * 4 + 2) / 16.0F;
        float z = -depth;
        mesh.corner(left, y0, z, u, v, 0, 0, -1)
                .corner(right, y0, z, u, v, 0, 0, -1)
                .corner(right, y1, z, u, v, 0, 0, -1)
                .corner(left, y1, z, u, v, 0, 0, -1);
    }
}
