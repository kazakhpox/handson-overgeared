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
 * grille doors swing open. Its front is cut where Overgeared's texture draws its openings; what smelts lies in the mouth or in a
 * crucible in its top, the fuel among the coals of the firebox arch; while it burns
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
                bars.setShape(strips(bars.extra(), Mth.clamp(shownCook, 0.0F, 1.0F), cookMax > 0, lit ? shownBurn : 0.0F), BAR_TEXTURE, true);
            }
        }
    }

    /**
     * The two strips, in scene pixels, where the scene's {@code bars} node says ({@code extra}): the smelting standing on the lip of the
     * mouth, or lying on the top behind a crucible ({@code flat}); the fuel standing on the floor of the firebox arch. Each fills from
     * the screen's left (+X).
     */
    private static MeshShape strips(com.google.gson.JsonObject config, float cook, boolean smelting, float fuel) {
        MeshShape.Builder mesh = MeshShape.builder();
        if (config.has("smelt")) {
            com.google.gson.JsonObject smelt = config.getAsJsonObject("smelt");
            float fill = smelting ? cook : 0.0F;
            if (smelt.has("flat") && smelt.get("flat").getAsBoolean()) {
                lying(mesh, smelt.get("y").getAsFloat(), smelt.get("from").getAsFloat(), smelt.get("to").getAsFloat(), fill, 6);
            } else {
                standing(mesh, smelt.get("y0").getAsFloat(), smelt.get("y1").getAsFloat(), smelt.get("z").getAsFloat(), fill, 6);
            }
        }
        if (config.has("fuel")) {
            com.google.gson.JsonObject strip = config.getAsJsonObject("fuel");
            standing(mesh, strip.get("y0").getAsFloat(), strip.get("y1").getAsFloat(), strip.get("z").getAsFloat(), fuel, 7);
        }
        return mesh.build();
    }

    private static final float LEFT = 12.4F;
    private static final float RIGHT = 3.6F;

    /** A strip standing up at {@code z}, facing the front. */
    private static void standing(MeshShape.Builder mesh, float y0, float y1, float z, float fill, int swatch) {
        front(mesh, LEFT, RIGHT, y0, y1, z, 0);
        front(mesh, LEFT - 0.15F, RIGHT + 0.15F, y0 + 0.1F, y1 - 0.1F, z - 0.04F, 1);
        if (fill > 0.0F) {
            front(mesh, LEFT - 0.15F, LEFT - 0.15F - (LEFT - RIGHT - 0.3F) * fill, y0 + 0.1F, y1 - 0.1F, z - 0.08F, swatch);
        }
    }

    /** A strip lying at height {@code y}, from {@code z0} (front) to {@code z1}, facing up. */
    private static void lying(MeshShape.Builder mesh, float y, float z0, float z1, float fill, int swatch) {
        up(mesh, LEFT, RIGHT, z0, z1, y, 0);
        up(mesh, LEFT - 0.15F, RIGHT + 0.15F, z0 + 0.12F, z1 - 0.12F, y + 0.03F, 1);
        if (fill > 0.0F) {
            up(mesh, LEFT - 0.15F, LEFT - 0.15F - (LEFT - RIGHT - 0.3F) * fill, z0 + 0.12F, z1 - 0.12F, y + 0.06F, swatch);
        }
    }

    private static float u(int swatch) {
        return ((swatch % 4) * 4 + 2) / 16.0F;
    }

    private static float v(int swatch) {
        return ((swatch / 4) * 4 + 2) / 16.0F;
    }

    /** A rectangle facing -Z at {@code z}, from {@code left} (screen left, +X) to {@code right}. */
    private static void front(MeshShape.Builder mesh, float left, float right, float y0, float y1, float z, int swatch) {
        float u = u(swatch);
        float v = v(swatch);
        mesh.corner(left, y0, z, u, v, 0, 0, -1)
                .corner(right, y0, z, u, v, 0, 0, -1)
                .corner(right, y1, z, u, v, 0, 0, -1)
                .corner(left, y1, z, u, v, 0, 0, -1);
    }

    /** A rectangle facing up at {@code y}, from {@code left} (screen left, +X) to {@code right}, {@code z0} (front) to {@code z1}. */
    private static void up(MeshShape.Builder mesh, float left, float right, float z0, float z1, float y, int swatch) {
        float u = u(swatch);
        float v = v(swatch);
        // Wound so that the face points up (left lies at +X): front left, front right, back right, back left.
        mesh.corner(left, y, z0, u, v, 0, 1, 0)
                .corner(right, y, z0, u, v, 0, 1, 0)
                .corner(right, y, z1, u, v, 0, 1, 0)
                .corner(left, y, z1, u, v, 0, 1, 0);
    }
}
