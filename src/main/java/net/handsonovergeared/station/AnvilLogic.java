package net.handsonovergeared.station;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.SlotStorage;
import com.pockyl.diegesis.api.state.StationState;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;

import net.stirdrem.overgeared.ForgingQuality;
import net.stirdrem.overgeared.block.custom.AbstractSmithingAnvil;
import net.stirdrem.overgeared.block.entity.AbstractSmithingAnvilBlockEntity;
import net.stirdrem.overgeared.config.ServerConfig;
import net.stirdrem.overgeared.recipe.ForgingRecipe;
import net.stirdrem.overgeared.sound.ModSounds;
import net.stirdrem.overgeared.util.ParticleUtils;

/**
 * The anvil's rules on the server. The items stay in the anvil's own inventory; forging goes through the anvil's own progress, so the
 * piece comes out exactly as Overgeared makes it: its quality from the strikes, capped by the blueprint, the creator's name, the
 * blueprint's progress and the advancements.
 *
 * <p>The client judges where each strike lands, as Overgeared's own minigame does; the server counts them, keeps the rhythm in the
 * station's data for everybody around, and decides the quality at the last strike.
 */
public final class AnvilLogic implements StationLogic {
    /** The fastest a hammer may come down again, in ticks. */
    private static final long STRIKE_GAP = 2;

    private final AbstractSmithingAnvilBlockEntity anvil;
    private long lastStrike = Long.MIN_VALUE / 2;
    private int syncedProgress = -1;
    private int syncedRequired = -1;

    public AnvilLogic(StationContext station) {
        anvil = station.blockEntity() instanceof AbstractSmithingAnvilBlockEntity found ? found : null;
        if (anvil == null) {
            return;
        }
        StationState state = station.state();
        state.bindSlots(AnvilStation.GRID, SlotStorage.of(anvil, 0, AnvilStation.GRID_SIZE));
        state.bindSlots(AnvilStation.HAMMER, SlotStorage.of(anvil, AnvilStation.HAMMER_INDEX, 1));
        state.bindSlots(AnvilStation.RESULT, SlotStorage.of(anvil, AnvilStation.RESULT_INDEX, 1));
        state.bindSlots(AnvilStation.BLUEPRINT, SlotStorage.of(anvil, AnvilStation.BLUEPRINT_INDEX, 1));
    }

    public static boolean forging(StationState state) {
        return state.data().getBoolean(AnvilStation.ON);
    }

    @Override
    public boolean mayPlace(StationContext station, Player player, SlotRef slot, net.minecraft.world.item.ItemStack stack) {
        if (anvil == null) {
            return false;
        }
        if (AnvilStation.BLUEPRINT.equals(slot.group()) && !hasBlueprintSlot()) {
            return false;
        }
        // The work cannot change under the hammer: Overgeared would start the piece over.
        return !(forging(station.state()) && changesWork(slot));
    }

    @Override
    public boolean mayTake(StationContext station, Player player, SlotRef slot) {
        return anvil != null && !(forging(station.state()) && changesWork(slot));
    }

    private static boolean changesWork(SlotRef slot) {
        return AnvilStation.GRID.equals(slot.group()) || AnvilStation.BLUEPRINT.equals(slot.group());
    }

    private boolean hasBlueprintSlot() {
        return anvil.getAnvilTier().hasBlueprint() && ServerConfig.ENABLE_BLUEPRINT_FORGING.get();
    }

    @Override
    public void onTaken(StationContext station, Player player, SlotRef slot, net.minecraft.world.item.ItemStack stack) {
        if (AnvilStation.RESULT.equals(slot.group()) && !stack.isEmpty()) {
            stack.onCraftedBy(station.level(), player, stack.getCount());
        }
    }

    @Override
    public void onClose(StationContext station, Player player) {
        if (anvil == null || station.isClientSide()) {
            return;
        }
        CompoundTag data = station.state().data();
        if (data.getBoolean(AnvilStation.ON) && data.hasUUID(AnvilStation.FORGER)
                && data.getUUID(AnvilStation.FORGER).equals(player.getUUID())) {
            // As Overgeared does when the smith walks away: the piece starts over.
            anvil.setProgress(0);
            endForging(station);
        }
    }

    @Override
    public void tick(StationContext station) {
        if (anvil == null || station.isClientSide()) {
            return;
        }
        CompoundTag data = station.state().data();
        if (data.getBoolean(AnvilStation.ON)) {
            boolean forgerHere = data.hasUUID(AnvilStation.FORGER) && station.users().stream()
                    .anyMatch(user -> user.getUUID().equals(data.getUUID(AnvilStation.FORGER)));
            // The recipe went (the grid changed behind the station's back) or the anvil started the piece over by itself.
            boolean lost = !anvil.hasRecipe() || data.getInt(AnvilStation.DONE) > 0 && anvil.getProgress() == 0;
            if (!forgerHere || lost) {
                anvil.setProgress(0);
                endForging(station);
            }
            return;
        }
        if (station.level().getGameTime() % 5 != 0) {
            return;
        }
        int required = anvil.hasRecipe() ? anvil.getRequiredProgress() : 0;
        int progress = required > 0 ? anvil.getProgress() : 0;
        if (required != syncedRequired || progress != syncedProgress) {
            syncedRequired = required;
            syncedProgress = progress;
            CompoundTag next = station.state().data();
            next.putInt(AnvilStation.REQUIRED, required);
            next.putInt(AnvilStation.PROGRESS, progress);
            station.state().setData(next);
        }
    }

    @Override
    public void onCustomAction(StationContext station, Player player, String action, CompoundTag data) {
        if (anvil == null || station.isClientSide() || !AnvilStation.ACTION_STRIKE.equals(action)
                || !(player instanceof ServerPlayer smith)) {
            return;
        }
        Level level = station.level();
        long now = level.getGameTime();
        if (now - lastStrike < STRIKE_GAP) {
            return;
        }
        lastStrike = now;
        BlockState blockState = station.blockState();
        if (!(blockState.getBlock() instanceof AbstractSmithingAnvil block)) {
            return;
        }
        ItemStack hammer = smith.inventoryMenu.getCarried();
        if (hammer.isEmpty() || !hammer.is(block.hammerTag())) {
            refuse(station, smith, "message.overgeared.wrong_hammer");
            return;
        }
        CompoundTag state = station.state().data();
        if (state.getBoolean(AnvilStation.ON)) {
            if (!state.getUUID(AnvilStation.FORGER).equals(smith.getUUID())) {
                refuse(station, smith, "message.overgeared.anvil_in_use_by_another");
                return;
            }
            strikeInRhythm(station, smith, block, hammer, data);
            return;
        }
        if (!anvil.hasRecipe()) {
            refuse(station, smith, "message.overgeared.no_recipe");
            return;
        }
        UUID owner = anvil.getOwnerUUID();
        if (owner != null && !owner.equals(smith.getUUID())) {
            if (level.getPlayerByUUID(owner) != null) {
                refuse(station, smith, "message.overgeared.anvil_in_use_by_another");
                return;
            }
            anvil.clearOwner();
        }
        Optional<RecipeHolder<ForgingRecipe>> recipe = anvil.getCurrentRecipeHolder();
        if (recipe.isPresent() && level.getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                && !smith.getRecipeBook().contains(recipe.get())) {
            refuse(station, smith, "message.overgeared.no_recipe");
            return;
        }
        boolean rhythm = ServerConfig.ENABLE_MINIGAME.get() && (anvil.hasQuality() || anvil.needsMinigame());
        if (rhythm) {
            startForging(station, smith);
        } else {
            plainStrike(station, smith, hammer);
        }
    }

    /** The first blow with the hammer: the rhythm starts, at the difficulty Overgeared gives the recipe and blueprint. */
    private void startForging(StationContext station, ServerPlayer smith) {
        String difficulty = ForgeRhythm.normalized(anvil.minigameQuality().getDisplayName());
        ForgeRhythm.Settings settings = settings(difficulty);
        anvil.setProgress(0);
        anvil.setOwner(smith.getUUID());
        anvil.setPlayer(smith);
        anvil.setMinigameOn(true);

        CompoundTag data = station.state().data();
        data.putBoolean(AnvilStation.ON, true);
        data.putUUID(AnvilStation.FORGER, smith.getUUID());
        data.putString(AnvilStation.DIFFICULTY, difficulty);
        data.putLong(AnvilStation.SEED, station.level().random.nextLong());
        data.putInt(AnvilStation.MAX, Math.max(1, anvil.getRequiredProgress()));
        data.putInt(AnvilStation.DONE, 0);
        data.putInt(AnvilStation.PERFECT, 0);
        data.putInt(AnvilStation.GOOD, 0);
        data.putInt(AnvilStation.MISS, 0);
        // A little after now: the marker starts once the bar has reached the clients.
        data.putLong(AnvilStation.ANCHOR_TICK, station.level().getGameTime() + 4);
        data.putFloat(AnvilStation.ANCHOR_POS, 50.0F);
        data.putBoolean(AnvilStation.ANCHOR_UP, true);
        settings.write(data);
        station.state().setData(data);

        CompoundTag effect = new CompoundTag();
        effect.putUUID(AnvilStation.PLAYER, smith.getUUID());
        station.effect(AnvilStation.EFFECT_STARTED, "work", effect);
    }

    private void strikeInRhythm(StationContext station, ServerPlayer smith, AbstractSmithingAnvil block, ItemStack hammer,
            CompoundTag strike) {
        Level level = station.level();
        BlockPos pos = station.pos();
        if (!anvil.hasRecipe()) {
            anvil.setProgress(0);
            endForging(station);
            refuse(station, smith, "message.overgeared.no_recipe");
            return;
        }
        ForgeRhythm.Grade grade = ForgeRhythm.Grade.of(strike.getInt(AnvilStation.GRADE));
        CompoundTag data = station.state().data();
        String key = switch (grade) {
            case PERFECT -> AnvilStation.PERFECT;
            case GOOD -> AnvilStation.GOOD;
            case MISS -> AnvilStation.MISS;
        };
        data.putInt(key, data.getInt(key) + 1);
        int done = data.getInt(AnvilStation.DONE) + 1;
        data.putInt(AnvilStation.DONE, done);
        data.putLong(AnvilStation.ANCHOR_TICK, level.getGameTime());
        data.putFloat(AnvilStation.ANCHOR_POS, Mth.clamp(strike.getFloat(AnvilStation.POSITION), ForgeRhythm.BAR_MIN, ForgeRhythm.BAR_MAX));
        data.putBoolean(AnvilStation.ANCHOR_UP, strike.getBoolean(AnvilStation.UP));

        boolean last = anvil.getProgress() + 1 >= anvil.getRequiredProgress();
        String quality = "poor";
        if (last) {
            quality = ForgeRhythm.quality(data.getInt(AnvilStation.PERFECT), data.getInt(AnvilStation.GOOD), data.getInt(AnvilStation.MISS),
                    ServerConfig.PERFECT_QUALITY_SCORE.get(), ServerConfig.EXPERT_QUALITY_SCORE.get(), ServerConfig.WELL_QUALITY_SCORE.get());
        }
        // Overgeared reads the quality of the last strike from the anvil block when the piece is made.
        block.setQuality(ForgingQuality.fromString(quality));
        anvil.setPlayer(smith);
        Optional<RecipeHolder<ForgingRecipe>> recipe = last ? anvil.getCurrentRecipeHolder() : Optional.empty();
        wearHammer(smith, hammer);
        anvil.increaseForgingProgress(level, pos, station.blockState());
        ParticleUtils.spawnAnvilParticles(level, pos);

        CompoundTag effect = new CompoundTag();
        effect.putUUID(AnvilStation.PLAYER, smith.getUUID());
        effect.putInt(AnvilStation.GRADE, grade.ordinal());
        effect.putString(AnvilStation.TOOL, BuiltInRegistries.ITEM.getKey(hammer.getItem()).toString());
        effect.putBoolean(AnvilStation.LAST, last);
        if (last) {
            recipe.ifPresent(holder -> smith.awardRecipes(List.of(holder)));
            finish(station, smith, quality);
        } else {
            station.state().setData(data);
            playSound(level, pos, smith, ModSounds.ANVIL_HIT.get());
        }
        station.effect(AnvilStation.EFFECT_STRUCK, "work", effect);
    }

    /** One blow of a recipe that needs no rhythm (or with Overgeared's minigame turned off): the anvil counts it. */
    private void plainStrike(StationContext station, ServerPlayer smith, ItemStack hammer) {
        Level level = station.level();
        BlockPos pos = station.pos();
        boolean last = anvil.getProgress() + 1 >= anvil.getRequiredProgress();
        Optional<RecipeHolder<ForgingRecipe>> recipe = last ? anvil.getCurrentRecipeHolder() : Optional.empty();
        anvil.setPlayer(smith);
        wearHammer(smith, hammer);
        anvil.increaseForgingProgress(level, pos, station.blockState());
        ParticleUtils.spawnAnvilParticles(level, pos);
        CompoundTag effect = new CompoundTag();
        effect.putUUID(AnvilStation.PLAYER, smith.getUUID());
        effect.putInt(AnvilStation.GRADE, -1);
        effect.putString(AnvilStation.TOOL, BuiltInRegistries.ITEM.getKey(hammer.getItem()).toString());
        effect.putBoolean(AnvilStation.LAST, last);
        if (last) {
            recipe.ifPresent(holder -> smith.awardRecipes(List.of(holder)));
            finish(station, smith, "");
        } else {
            playSound(level, pos, smith, ModSounds.ANVIL_HIT.get());
            syncedProgress = -1;
        }
        station.effect(AnvilStation.EFFECT_STRUCK, "work", effect);
    }

    /** The piece is made and lies over the horn: everybody hears how it went. */
    private void finish(StationContext station, ServerPlayer smith, String quality) {
        Level level = station.level();
        ItemStack made = anvil.getItem(AnvilStation.RESULT_INDEX);
        boolean failed = !made.isEmpty() && anvil.isFailedResult();
        level.playSound(null, station.pos(), failed ? ModSounds.FORGING_FAILED.get() : ModSounds.FORGING_COMPLETE.get(),
                SoundSource.BLOCKS, 1.0F, 1.0F);
        endForging(station);
        CompoundTag effect = new CompoundTag();
        effect.putUUID(AnvilStation.PLAYER, smith.getUUID());
        effect.putString(AnvilStation.QUALITY, quality);
        effect.putBoolean(AnvilStation.FAILED, failed);
        if (!made.isEmpty()) {
            effect.putString(AnvilStation.ITEM, BuiltInRegistries.ITEM.getKey(made.getItem()).toString());
        }
        station.effect(AnvilStation.EFFECT_FORGED, "result", effect);
    }

    private void endForging(StationContext station) {
        anvil.setMinigameOn(false);
        anvil.clearOwner();
        if (station.blockState().getBlock() instanceof AbstractSmithingAnvil block) {
            block.setQuality(null);
        }
        CompoundTag data = station.state().data();
        for (String key : new String[]{AnvilStation.ON, AnvilStation.FORGER, AnvilStation.DIFFICULTY, AnvilStation.SEED, AnvilStation.MAX,
                AnvilStation.DONE, AnvilStation.PERFECT, AnvilStation.GOOD, AnvilStation.MISS, AnvilStation.ANCHOR_TICK,
                AnvilStation.ANCHOR_POS, AnvilStation.ANCHOR_UP}) {
            data.remove(key);
        }
        station.state().setData(data);
        syncedProgress = -1;
    }

    private static void wearHammer(ServerPlayer smith, ItemStack hammer) {
        if (smith.level() instanceof ServerLevel level && !smith.getAbilities().instabuild) {
            hammer.hurtAndBreak(1, level, smith, item -> {
            });
        }
        smith.inventoryMenu.setCarried(hammer.isEmpty() ? ItemStack.EMPTY : hammer);
    }

    /** The smith's own client already played the blow as the hammer came down: everybody else hears it from here. */
    private static void playSound(Level level, BlockPos pos, Player smith, SoundEvent sound) {
        level.playSound(smith, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void refuse(StationContext station, ServerPlayer smith, String reason) {
        CompoundTag effect = new CompoundTag();
        effect.putUUID(AnvilStation.PLAYER, smith.getUUID());
        effect.putString(AnvilStation.REASON, reason);
        station.effect(AnvilStation.EFFECT_REFUSED, "work", effect);
    }

    /** Overgeared's minigame settings for a difficulty (the blueprint's quality, or the recipe's own). */
    static ForgeRhythm.Settings settings(String difficulty) {
        return switch (difficulty) {
            case "none" -> new ForgeRhythm.Settings(f(ServerConfig.DEFAULT_ARROW_SPEED.get()), f(ServerConfig.DEFAULT_ARROW_SPEED_INCREASE.get()),
                    f(ServerConfig.DEFAULT_MAX_ARROW_SPEED.get()), ServerConfig.DEFAULT_ZONE_STARTING_SIZE.get(),
                    f(ServerConfig.DEFAULT_ZONE_SHRINK_FACTOR.get()), ServerConfig.DEFAULT_MIN_PERFECT_ZONE.get());
            case "master" -> new ForgeRhythm.Settings(f(ServerConfig.MASTER_ARROW_SPEED.get()), f(ServerConfig.MASTER_ARROW_SPEED_INCREASE.get()),
                    f(ServerConfig.MASTER_MAX_ARROW_SPEED.get()), ServerConfig.MASTER_ZONE_STARTING_SIZE.get(),
                    f(ServerConfig.MASTER_ZONE_SHRINK_FACTOR.get()), ServerConfig.MASTER_MIN_PERFECT_ZONE.get());
            case "perfect" -> new ForgeRhythm.Settings(f(ServerConfig.PERFECT_ARROW_SPEED.get()), f(ServerConfig.PERFECT_ARROW_SPEED_INCREASE.get()),
                    f(ServerConfig.PERFECT_MAX_ARROW_SPEED.get()), ServerConfig.PERFECT_ZONE_STARTING_SIZE.get(),
                    f(ServerConfig.PERFECT_ZONE_SHRINK_FACTOR.get()), ServerConfig.PERFECT_MIN_PERFECT_ZONE.get());
            case "expert" -> new ForgeRhythm.Settings(f(ServerConfig.EXPERT_ARROW_SPEED.get()), f(ServerConfig.EXPERT_ARROW_SPEED_INCREASE.get()),
                    f(ServerConfig.EXPERT_MAX_ARROW_SPEED.get()), ServerConfig.EXPERT_ZONE_STARTING_SIZE.get(),
                    f(ServerConfig.EXPERT_ZONE_SHRINK_FACTOR.get()), ServerConfig.EXPERT_MIN_PERFECT_ZONE.get());
            case "well" -> new ForgeRhythm.Settings(f(ServerConfig.WELL_ARROW_SPEED.get()), f(ServerConfig.WELL_ARROW_SPEED_INCREASE.get()),
                    f(ServerConfig.WELL_MAX_ARROW_SPEED.get()), ServerConfig.WELL_ZONE_STARTING_SIZE.get(),
                    f(ServerConfig.WELL_ZONE_SHRINK_FACTOR.get()), ServerConfig.WELL_MIN_PERFECT_ZONE.get());
            default -> new ForgeRhythm.Settings(f(ServerConfig.POOR_ARROW_SPEED.get()), f(ServerConfig.POOR_ARROW_SPEED_INCREASE.get()),
                    f(ServerConfig.POOR_MAX_ARROW_SPEED.get()), ServerConfig.POOR_ZONE_STARTING_SIZE.get(),
                    f(ServerConfig.POOR_ZONE_SHRINK_FACTOR.get()), ServerConfig.POOR_MIN_PERFECT_ZONE.get());
        };
    }

    private static float f(Number value) {
        return value.floatValue();
    }
}
