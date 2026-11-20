package net.handsonovergeared.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.pockyl.diegesis.api.client.DiegesisParticles;
import com.pockyl.diegesis.api.client.NodeView;
import com.pockyl.diegesis.api.client.StationBehavior;
import com.pockyl.diegesis.api.client.StationView;
import com.pockyl.diegesis.api.event.SceneEvent;
import com.pockyl.diegesis.api.scene.Visual;
import com.pockyl.diegesis.api.state.StationState;

import net.handsonovergeared.station.AnvilStation;
import net.handsonovergeared.station.ForgeRhythm;
import net.stirdrem.overgeared.block.custom.AbstractSmithingAnvil;
import net.stirdrem.overgeared.block.entity.AbstractSmithingAnvilBlockEntity;
import net.stirdrem.overgeared.config.ServerConfig;
import net.stirdrem.overgeared.heateditem.HeatedItem;
import net.stirdrem.overgeared.recipe.ForgingRecipe;
import net.stirdrem.overgeared.sound.ModSounds;
import net.stirdrem.overgeared.util.ModTags;

/**
 * The anvil on the client. The hammer taken on the cursor is drawn by the scene over the work, raised, and comes down when the work is
 * clicked; the rhythm bar stands over the anvil while a piece is forged, its marker running to and fro, and every strike shows how it
 * landed. Everybody around sees the bar, the strikes and the sparks.
 */
public final class AnvilBehavior implements StationBehavior {
    /** The anvil's top, where the work lies and the hammer strikes: scene pixels. */
    private static final float TOP = 16.0F;
    private static final float WORK_MIN_X = 3.0F;
    private static final float WORK_MAX_X = 13.0F;
    private static final float WORK_MIN_Z = 2.6F;
    private static final float WORK_MAX_Z = 13.2F;
    private static final float WORK_X = 8.0F;
    private static final float WORK_Z = 8.0F;

    /** The hammer: how high its grip stands over the strike point, how far to the screen's right, and its angles (degrees). */
    static float GRIP_UP = 2.3F;
    static float GRIP_RIGHT = 5.3F;
    static float BASE_ANGLE = 135.0F;
    static float RAISED = -62.0F;
    static float STRUCK = 6.0F;
    private static final float DOWN = 0.075F;
    private static final float UP = 0.2F;
    private static final float TOTAL = DOWN + UP;
    /** The hammer offered from the anvil's heel or the inventory: see-through until taken. */
    private static final int SOLID = 0xFFFFFFFF;

    private static final int SPARK_COLOUR = 0xFFFFA43A;
    private static final int[] GRADE_COLOURS = {0xFFFF5555, 0xFF55FF55, 0xFFFFD700};
    private static final String[] GRADE_KEYS = {"overgeared.forging.miss", "overgeared.forging.good", "overgeared.forging.perfect"};

    private static final float POPUP_TIME = 1.1F;
    private static final float STRIKE_GAP = 0.11F;

    private final List<Popup> popups = new ArrayList<>();

    // The hammer as drawn: where its strike point is, and the swing under way (NaN: none).
    private float hammerX = WORK_X;
    private float hammerZ = WORK_Z;
    private float swingAge = Float.NaN;
    private int swingGrade = -1;
    private boolean swingMine;
    private Item shownHammer;
    private Item swingHammer;
    private float lastStrike = -10.0F;

    // The rhythm as this client sees it. The smith's own client runs ahead of the server: its strikes count at once.
    private long seed;
    private int zonesFor = -1;
    private ForgeRhythm.Zones zones;
    private int localDone = -1;
    private ForgeRhythm.Marker localAnchor;
    private float localAnchorTime;
    private float flash;

    private boolean barShown;
    private int barDone = -1;
    private int barMax = -1;
    private boolean barFlash;
    private ForgeRhythm.Zones barZones;

    private List<Ingredient> usable;
    private int recipeCheck;
    private ItemStack preview = ItemStack.EMPTY;
    private int previewHits;

    public AnvilBehavior(StationView view) {
    }

    @Override
    public void onSceneReady(StationView view) {
        shownHammer = null;
        barShown = false;
        barDone = -1;
        view.node("marker").ifPresent(node -> node.setShape(RhythmBar.marker(), RhythmBar.TEXTURE, true));
        view.node("popup").ifPresent(node -> node.setPainter((pose, buffers, light) -> paintPopups(pose, buffers)));
        view.node("label").ifPresent(node -> node.setPainter((pose, buffers, light) -> paintLabel(view, pose, buffers)));
        view.node("hammer").ifPresent(node -> node.setVisible(false));
        boolean blueprint = hasBlueprintSlot(view);
        view.node("blueprint").ifPresent(node -> {
            node.setVisible(blueprint);
            node.setEnabled(blueprint);
        });
        updateBar(view);
        updatePreview(view);
    }

    @Override
    public void onOpen(StationView view) {
        usable = null;
        view.setInventoryFilter(stack -> usable(view, stack));
        // Came with the hammer in hand: it is in hand here too.
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && view.openedByUse() && isHammer(player.getMainHandItem())) {
            view.takeFromInventory(player.getInventory().selected);
        }
        caption(view);
    }

    @Override
    public void onClose(StationView view) {
        localDone = -1;
        localAnchor = null;
    }

    @Override
    public void onStateChanged(StationView view, StationState.Changes changes) {
        if (changes.data()) {
            CompoundTag data = view.state().data();
            if (!data.getBoolean(AnvilStation.ON) || data.getLong(AnvilStation.SEED) != seed) {
                localDone = -1;
                localAnchor = null;
            }
            seed = data.getLong(AnvilStation.SEED);
            zonesFor = -1;
        }
        if (!changes.slots().isEmpty()) {
            recipeCheck = 0;
        }
    }

    @Override
    public void tick(StationView view) {
        if (--recipeCheck <= 0) {
            recipeCheck = 10;
            updatePreview(view);
        }
        boolean heated = false;
        for (ItemStack stack : view.state().getItems(AnvilStation.GRID)) {
            heated |= !stack.isEmpty() && HeatedItem.isHeated(stack);
        }
        boolean glowing = heated;
        view.node("embers").ifPresent(node -> node.setEmitting(glowing));
    }

    @Override
    public void frame(StationView view, float dt) {
        flash = Math.max(0.0F, flash - dt);
        Iterator<Popup> it = popups.iterator();
        while (it.hasNext()) {
            Popup popup = it.next();
            popup.age += dt;
            if (popup.age > POPUP_TIME) {
                it.remove();
            }
        }
        updateBar(view);
        moveHammer(view, dt);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The rhythm
    // ------------------------------------------------------------------------------------------------------------------

    private static float now(StationView view) {
        return view.level().getGameTime() + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }

    private static boolean forging(StationView view) {
        return view.state().data().getBoolean(AnvilStation.ON);
    }

    private static boolean mine(StationView view) {
        CompoundTag data = view.state().data();
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && data.hasUUID(AnvilStation.FORGER) && data.getUUID(AnvilStation.FORGER).equals(player.getUUID());
    }

    /** Strikes made as this client counts them. */
    private int strikes(StationView view) {
        int done = view.state().data().getInt(AnvilStation.DONE);
        return localAnchor != null && localDone >= done ? localDone : done;
    }

    private ForgeRhythm.Zones zones(StationView view) {
        int strikes = strikes(view);
        if (zones == null || zonesFor != strikes) {
            CompoundTag data = view.state().data();
            zones = ForgeRhythm.zones(ForgeRhythm.Settings.read(data), data.getLong(AnvilStation.SEED), strikes);
            zonesFor = strikes;
        }
        return zones;
    }

    private ForgeRhythm.Marker marker(StationView view, float time) {
        CompoundTag data = view.state().data();
        ForgeRhythm.Settings settings = ForgeRhythm.Settings.read(data);
        int done = data.getInt(AnvilStation.DONE);
        if (localAnchor != null && localDone >= done) {
            return ForgeRhythm.marker(localAnchor, settings.speedAfter(localDone), time - localAnchorTime);
        }
        ForgeRhythm.Marker anchor = new ForgeRhythm.Marker(data.getFloat(AnvilStation.ANCHOR_POS), data.getBoolean(AnvilStation.ANCHOR_UP));
        return ForgeRhythm.marker(anchor, settings.speedAfter(done), time - data.getLong(AnvilStation.ANCHOR_TICK));
    }

    private void updateBar(StationView view) {
        CompoundTag data = view.state().data();
        boolean on = data.getBoolean(AnvilStation.ON);
        int max;
        int done;
        if (on) {
            max = data.getInt(AnvilStation.MAX);
            done = strikes(view);
        } else {
            max = data.getInt(AnvilStation.REQUIRED);
            done = data.getInt(AnvilStation.PROGRESS);
        }
        // Plain hammering shows only how many blows are done, once the first has been struck.
        boolean shown = on || max > 0 && done > 0;
        Optional<NodeView> bar = view.node("bar");
        if (bar.isEmpty()) {
            return;
        }
        if (shown != barShown) {
            barShown = shown;
            bar.get().setVisible(shown);
            if (shown) {
                // The plate springs up as the forging starts.
                bar.get().scaleTo(0.6F, 0.0F);
                bar.get().scaleTo(1.0F, 0.18F);
            }
        }
        view.node("marker").ifPresent(node -> node.setVisible(on));
        if (!shown) {
            return;
        }
        ForgeRhythm.Zones z = on ? zones(view) : null;
        boolean flashing = flash > 0.0F;
        if (done != barDone || max != barMax || z != barZones || flashing != barFlash) {
            barDone = done;
            barMax = max;
            barZones = z;
            barFlash = flashing;
            bar.get().setShape(RhythmBar.plate(z == null ? new ForgeRhythm.Zones(0, 0, 0, 0) : z, on, done, max, flashing),
                    RhythmBar.TEXTURE, true);
        }
        if (on) {
            ForgeRhythm.Marker m = marker(view, now(view));
            view.node("marker").ifPresent(node -> node.moveTo(0.0F, 0.0F, RhythmBar.z(m.position()), 0.0F));
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Striking
    // ------------------------------------------------------------------------------------------------------------------

    private static boolean isHammer(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModTags.Items.SMITHING_HAMMERS);
    }

    private static ItemStack carried() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.inventoryMenu.getCarried();
    }

    private static boolean overWork(Vector3f point) {
        return point.x >= WORK_MIN_X - 0.5F && point.x <= WORK_MAX_X + 0.5F && point.z >= WORK_MIN_Z && point.z <= WORK_MAX_Z;
    }

    @Override
    public boolean onItemClick(StationView view, String node, ItemStack stack, int button, boolean shift) {
        if (!isHammer(stack) || "hammer_rest".equals(node) || "result".equals(node) || "blueprint".equals(node)) {
            return false;
        }
        if (button == 1) {
            // The right button lays the hammer down on the anvil's heel, or back into the inventory.
            view.putAwayCarried();
            return true;
        }
        strike(view, stack);
        return true;
    }

    @Override
    public void onEvent(StationView view, SceneEvent event) {
        // An empty hand on the anvil's face takes up a hammer: the one resting on the heel is a slot of its own, else one from the inventory.
        if (event instanceof SceneEvent.Click click && "work".equals(click.node()) && carried().isEmpty()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }
            for (int slot = 0; slot < 36; slot++) {
                if (isHammer(player.getInventory().getItem(slot))) {
                    view.takeFromInventory(slot);
                    return;
                }
            }
            view.showCaption(Component.translatable("handson_overgeared.hint.no_hammer"), 3.0F);
        }
    }

    private void strike(StationView view, ItemStack hammer) {
        float time = view.age();
        if (time - lastStrike < STRIKE_GAP || !Float.isNaN(swingAge) && swingAge < DOWN) {
            return;
        }
        if (view.state().getItems(AnvilStation.GRID).length > 0 && gridEmpty(view)) {
            view.playSound(SoundEvents.ANVIL_LAND, "work", 0.15F, 1.8F);
            view.showCaption(Component.translatable("handson_overgeared.hint.empty"), 3.0F);
            return;
        }
        if (view.level().getBlockState(view.pos()).getBlock() instanceof AbstractSmithingAnvil anvil && !hammer.is(anvil.hammerTag())) {
            view.showCaption(Component.translatable("message.overgeared.wrong_hammer").withStyle(ChatFormatting.RED), 3.0F);
            return;
        }
        lastStrike = time;
        CompoundTag data = new CompoundTag();
        int grade = -1;
        if (forging(view)) {
            if (!mine(view)) {
                view.showCaption(Component.translatable("message.overgeared.anvil_in_use_by_another").withStyle(ChatFormatting.RED), 3.0F);
                return;
            }
            float now = now(view);
            ForgeRhythm.Marker m = marker(view, now);
            ForgeRhythm.Grade landed = zones(view).grade(m.position());
            grade = landed.ordinal();
            localDone = strikes(view) + 1;
            localAnchor = m;
            localAnchorTime = now;
            data.putInt(AnvilStation.GRADE, grade);
            data.putFloat(AnvilStation.POSITION, m.position());
            data.putBoolean(AnvilStation.UP, m.up());
            popup(Component.translatable(GRADE_KEYS[grade]), GRADE_COLOURS[grade], grade == 2 ? 1.25F : 1.0F);
            if (landed == ForgeRhythm.Grade.PERFECT) {
                flash = 0.18F;
            }
        }
        view.sendAction(AnvilStation.ACTION_STRIKE, data);
        swing(hammer.getItem(), grade, true);
    }

    private static boolean gridEmpty(StationView view) {
        for (ItemStack stack : view.state().getItems(AnvilStation.GRID)) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void swing(Item hammer, int grade, boolean mine) {
        swingAge = 0.0F;
        swingGrade = grade;
        swingMine = mine;
        swingHammer = hammer;
        if (!mine) {
            hammerX = WORK_X;
            hammerZ = WORK_Z;
        }
    }

    /** The moment the hammer meets the work: sparks, a shake of the work, and the smith's own client hears it at once. */
    private void impact(StationView view) {
        int count = switch (swingGrade) {
            case 2 -> 16;
            case 1 -> 9;
            case 0 -> 3;
            default -> 7;
        };
        Vector3f at = new Vector3f(hammerX, TOP + 0.6F, hammerZ);
        view.spawnParticles(DiegesisParticles.SPARK, at, new Vector3f(0.0F, 14.0F, 0.0F), 1.6F, count, SPARK_COLOUR);
        if (swingGrade == 2) {
            view.spawnParticles(DiegesisParticles.GLINT, at, new Vector3f(0.0F, 4.0F, 0.0F), 2.0F, 4, 0xFFFFE680);
        }
        for (int i = 0; i < AnvilStation.GRID_SIZE; i++) {
            view.node("grid_" + i).ifPresent(node -> node.shake(0.25F, 0.22F));
        }
        if (swingMine) {
            view.playSound(ModSounds.ANVIL_HIT.get(), "work", 1.0F, 0.95F + (swingGrade == 2 ? 0.1F : 0.0F));
        }
    }

    private void moveHammer(StationView view, float dt) {
        NodeView pivot = view.node("hammer_pivot").orElse(null);
        NodeView node = view.node("hammer").orElse(null);
        if (pivot == null || node == null) {
            return;
        }
        boolean swinging = !Float.isNaN(swingAge);
        ItemStack hand = view.isFocused() ? carried() : ItemStack.EMPTY;
        Optional<Vector3f> cursor = view.isFocused() && isHammer(hand) ? view.cursorOn(TOP) : Optional.empty();
        boolean over = cursor.isPresent() && overWork(cursor.get());
        boolean drawn = over || swinging;
        node.setVisible(drawn);
        if (view.isFocused()) {
            view.setCarriedHidden(over || swinging && swingMine && isHammer(hand));
        }
        if (!drawn) {
            return;
        }
        Item item = swinging && swingHammer != null ? swingHammer : hand.getItem();
        if (item != shownHammer) {
            shownHammer = item;
            // Held by the end of its handle: the bottom left of the icon.
            node.setVisual(Visual.ofItem(item).withAnchor(new Vector3f(1.5F, 1.5F, 8.0F)));
        }
        node.setTint(SOLID);
        if (!swinging || !swingMine) {
            if (over && !swinging) {
                float follow = 1.0F - (float) Math.exp(-dt * 24.0F);
                hammerX += (Mth.clamp(cursor.get().x, WORK_MIN_X, WORK_MAX_X) - hammerX) * follow;
                hammerZ += (Mth.clamp(cursor.get().z, WORK_MIN_Z, WORK_MAX_Z) - hammerZ) * follow;
            }
        }
        float angle = RAISED;
        float drop = 0.0F;
        if (swinging) {
            float before = swingAge;
            swingAge += dt;
            if (before < DOWN && swingAge >= DOWN) {
                impact(view);
            }
            if (swingAge < DOWN) {
                float t = swingAge / DOWN;
                angle = Mth.lerp(t * t, RAISED, STRUCK);
            } else if (swingAge < TOTAL) {
                float t = (swingAge - DOWN) / UP;
                float eased = 1.0F - (1.0F - t) * (1.0F - t);
                angle = Mth.lerp(eased, STRUCK, RAISED);
                drop = (1.0F - eased) * 0.4F;
            } else {
                swingAge = Float.NaN;
            }
        } else {
            // A breath while it waits over the work.
            angle += Mth.sin(view.age() * 3.0F) * 2.0F;
        }
        pivot.moveTo(hammerX, TOP + GRIP_UP - drop, hammerZ - GRIP_RIGHT, 0.0F);
        pivot.rotateTo(BASE_ANGLE + angle, 0.0F, 0.0F, 0.0F);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // What others did, and what the server says
    // ------------------------------------------------------------------------------------------------------------------

    @Override
    public void onEffect(StationView view, ResourceLocation effect, String node, CompoundTag data) {
        boolean byMe = byLocalPlayer(data);
        if (AnvilStation.EFFECT_STRUCK.equals(effect)) {
            if (!byMe) {
                Item tool = BuiltInRegistries.ITEM.get(ResourceLocation.parse(data.getString(AnvilStation.TOOL)));
                int grade = data.getInt(AnvilStation.GRADE);
                swing(tool, grade, false);
                if (grade >= 0 && grade < GRADE_KEYS.length) {
                    popup(Component.translatable(GRADE_KEYS[grade]), GRADE_COLOURS[grade], 1.0F);
                }
            }
        } else if (AnvilStation.EFFECT_FORGED.equals(effect)) {
            forged(view, data, byMe);
        } else if (AnvilStation.EFFECT_REFUSED.equals(effect)) {
            if (byMe) {
                localDone = -1;
                localAnchor = null;
                view.showCaption(Component.translatable(data.getString(AnvilStation.REASON)).withStyle(ChatFormatting.RED), 3.0F);
            }
        } else if (AnvilStation.EFFECT_STARTED.equals(effect)) {
            if (byMe) {
                view.showCaption(Component.translatable("handson_overgeared.hint.rhythm"), 4.0F);
            }
            view.playSound(SoundEvents.BLASTFURNACE_FIRE_CRACKLE, "work", 0.6F, 1.3F);
        }
    }

    private void forged(StationView view, CompoundTag data, boolean byMe) {
        localDone = -1;
        localAnchor = null;
        String quality = data.getString(AnvilStation.QUALITY);
        boolean failed = data.getBoolean(AnvilStation.FAILED);
        Component text;
        int colour;
        if (failed) {
            text = Component.translatable("handson_overgeared.forged.failed");
            colour = 0xFFFF5555;
        } else if (!quality.isEmpty()) {
            ChatFormatting format = net.stirdrem.overgeared.ForgingQuality.getColor(quality);
            text = Component.translatable("tooltip.overgeared." + quality);
            colour = 0xFF000000 | (format != null && format.getColor() != null ? format.getColor() : 0xFFFFFF);
        } else {
            text = Component.translatable("handson_overgeared.forged.done");
            colour = 0xFFFFFFFF;
        }
        popup(text, colour, 1.6F);
        Vector3f at = new Vector3f(WORK_X, TOP + 1.0F, WORK_Z);
        view.spawnParticles(DiegesisParticles.SPARK, at, new Vector3f(0.0F, 20.0F, 0.0F), 3.0F, failed ? 6 : 26, SPARK_COLOUR);
        view.spawnParticles(DiegesisParticles.SMOKE, at, new Vector3f(0.0F, 6.0F, 0.0F), 2.0F, failed ? 8 : 3, 0);
        view.node("result").ifPresent(result -> result.shake(0.4F, 0.4F));
        if (byMe) {
            view.showCaption(Component.translatable("handson_overgeared.hint.take_result"), 4.0F);
        }
    }

    private static boolean byLocalPlayer(CompoundTag data) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !data.hasUUID(AnvilStation.PLAYER)) {
            return false;
        }
        UUID uuid = data.getUUID(AnvilStation.PLAYER);
        return uuid.equals(player.getUUID());
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Words over the anvil
    // ------------------------------------------------------------------------------------------------------------------

    private void popup(Component text, int colour, float size) {
        popups.add(new Popup(text, colour, size));
        if (popups.size() > 4) {
            popups.remove(0);
        }
    }

    private void paintPopups(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers) {
        for (Popup popup : popups) {
            float t = popup.age / POPUP_TIME;
            float grow = popup.age < 0.08F ? 0.6F + popup.age / 0.08F * 0.4F : 1.0F;
            int alpha = (int) (255 * Mth.clamp(1.0F - (t - 0.6F) / 0.4F, 0.0F, 1.0F));
            int argb = (alpha << 24) | (popup.colour & 0xFFFFFF);
            TextPainter.draw(pose, buffers, popup.text, 1.6F * popup.size * grow, popup.age * 3.5F, argb, false);
        }
    }

    private void paintLabel(StationView view, com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.MultiBufferSource buffers) {
        CompoundTag data = view.state().data();
        if (data.getBoolean(AnvilStation.ON)) {
            Component line = Component.translatable("handson_overgeared.label.forging", strikes(view), data.getInt(AnvilStation.MAX));
            TextPainter.draw(pose, buffers, line, 1.1F, 0.0F, 0xFFFFFFFF, false);
        } else if (!preview.isEmpty() && view.isFocused()) {
            Component line = Component.translatable("handson_overgeared.label.recipe", preview.getHoverName(), previewHits);
            TextPainter.draw(pose, buffers, line, 1.0F, 0.0F, 0xE0FFFFFF, false);
        }
    }

    private void caption(StationView view) {
        if (!preview.isEmpty()) {
            view.showCaption(Component.translatable("handson_overgeared.hint.strike"), 4.0F);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // What the work will become
    // ------------------------------------------------------------------------------------------------------------------

    private void updatePreview(StationView view) {
        BlockEntity entity = view.level().getBlockEntity(view.pos());
        ItemStack next = ItemStack.EMPTY;
        int hits = 0;
        if (entity instanceof AbstractSmithingAnvilBlockEntity anvil && !gridEmpty(view)) {
            Optional<ForgingRecipe> recipe = anvil.getCurrentRecipe();
            if (recipe.isPresent()) {
                next = recipe.get().getResultItem(view.level().registryAccess()).copy();
                hits = recipe.get().getHammeringRequired();
            }
        }
        preview = next;
        previewHits = hits;
        ItemStack ghost = next;
        view.node("result").ifPresent(node -> node.setGhostItems(ghost.isEmpty() ? List.of() : List.of(ghost)));
    }

    private static boolean hasBlueprintSlot(StationView view) {
        return view.level().getBlockEntity(view.pos()) instanceof AbstractSmithingAnvilBlockEntity anvil
                && anvil.getAnvilTier().hasBlueprint() && ServerConfig.ENABLE_BLUEPRINT_FORGING.get();
    }

    /** What the drawer shows: hammers, blueprints and whatever some forging recipe takes. */
    private boolean usable(StationView view, ItemStack stack) {
        if (isHammer(stack) || AnvilStation.isBlueprint(stack)) {
            return true;
        }
        if (usable == null) {
            usable = new ArrayList<>();
            for (RecipeHolder<?> holder : view.level().getRecipeManager().getRecipes()) {
                if (holder.value() instanceof ForgingRecipe recipe) {
                    usable.addAll(recipe.getIngredients());
                }
            }
        }
        for (Ingredient ingredient : usable) {
            if (!ingredient.isEmpty() && ingredient.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private static final class Popup {
        final Component text;
        final int colour;
        final float size;
        float age;

        Popup(Component text, int colour, float size) {
            this.text = text;
            this.colour = colour;
            this.size = size;
        }
    }
}
