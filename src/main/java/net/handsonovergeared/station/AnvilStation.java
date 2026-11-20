package net.handsonovergeared.station;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SmithingTemplateItem;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.SlotRules;
import com.pockyl.diegesis.api.state.StationSchema;
import com.pockyl.diegesis.api.station.StationType;

import net.handsonovergeared.HandsOnOvergeared;
import net.stirdrem.overgeared.block.ModBlocks;
import net.stirdrem.overgeared.block.entity.AbstractSmithingAnvilBlockEntity;
import net.stirdrem.overgeared.item.ModItems;
import net.stirdrem.overgeared.util.ModTags;

/**
 * Overgeared's smithing anvils as a station: the 3x3 grid lies on the anvil's face, the hammer rests on its heel, the finished piece
 * waits over the horn, and the hammer taken in hand strikes the work in time with the forging rhythm ({@link ForgeRhythm}).
 *
 * <p>Every slot group is a window on the anvil's own inventory, so hoppers, Overgeared's own screen (sneaking) and players without
 * this mod work with the same items.
 */
public final class AnvilStation {
    public static final ResourceLocation ID = HandsOnOvergeared.id("smithing_anvil");

    /** The anvil's slots: 0-8 the grid, 9 the hammer, 10 the result, 11 the blueprint. */
    public static final String GRID = "grid";
    public static final String HAMMER = "hammer";
    public static final String RESULT = "result";
    public static final String BLUEPRINT = "blueprint";
    public static final int GRID_SIZE = 9;
    public static final int HAMMER_INDEX = 9;
    public static final int RESULT_INDEX = 10;
    public static final int BLUEPRINT_INDEX = 11;
    public static final SlotRef HAMMER_SLOT = SlotRef.of(HAMMER, 0);
    public static final SlotRef RESULT_SLOT = SlotRef.of(RESULT, 0);
    public static final SlotRef BLUEPRINT_SLOT = SlotRef.of(BLUEPRINT, 0);

    /**
     * {@code strike}: the hammer on the cursor came down on the work. During a forging, {@link #GRADE} is how the client saw it land
     * (a {@link ForgeRhythm.Grade}) and {@link #POSITION}, {@link #UP} where the marker was then; otherwise it starts the forging, or
     * is one plain blow for recipes that need no rhythm.
     */
    public static final String ACTION_STRIKE = "strike";
    public static final String GRADE = "grade";
    public static final String POSITION = "pos";
    public static final String UP = "up";

    /** The station's data, which every client near the anvil gets. */
    public static final String ON = "on";
    public static final String FORGER = "forger";
    public static final String DIFFICULTY = "difficulty";
    public static final String SEED = "seed";
    public static final String MAX = "max";
    public static final String DONE = "done";
    public static final String PERFECT = "perfect";
    public static final String GOOD = "good";
    public static final String MISS = "miss";
    /** Where the marker was at the last strike, and the game tick of it. */
    public static final String ANCHOR_TICK = "a_tick";
    public static final String ANCHOR_POS = "a_pos";
    public static final String ANCHOR_UP = "a_up";
    /** Hits made and needed of the recipe on the anvil while nobody forges with the rhythm (plain hammering). */
    public static final String PROGRESS = "progress";
    public static final String REQUIRED = "required";

    /** Effects for everybody who sees the anvil. */
    public static final ResourceLocation EFFECT_STRUCK = HandsOnOvergeared.id("struck");
    public static final ResourceLocation EFFECT_FORGED = HandsOnOvergeared.id("forged");
    public static final ResourceLocation EFFECT_REFUSED = HandsOnOvergeared.id("refused");
    public static final ResourceLocation EFFECT_STARTED = HandsOnOvergeared.id("started");
    /** Effect data: who did it, with what, how it landed, whether the piece is done, and the message key of a refusal. */
    public static final String PLAYER = "player";
    public static final String TOOL = "tool";
    public static final String LAST = "last";
    public static final String QUALITY = "quality";
    public static final String FAILED = "failed";
    public static final String REASON = "reason";
    public static final String ITEM = "item";

    public static final StationSchema SCHEMA = StationSchema.builder()
            .slots(HAMMER, 1, SlotRules.DEFAULT.kept().maxStack(1).accepts(stack -> stack.is(ModTags.Items.SMITHING_HAMMERS)))
            .slots(BLUEPRINT, 1, SlotRules.DEFAULT.kept().maxStack(1).accepts(AnvilStation::isBlueprint))
            .slots(GRID, GRID_SIZE, SlotRules.DEFAULT.kept())
            .slots(RESULT, 1, SlotRules.DEFAULT.kept().accepts(stack -> false))
            .build();

    private AnvilStation() {
    }

    public static final StationType TYPE = build();

    private static StationType build() {
        StationType.Builder builder = Diegesis.station(ID)
                .block(() -> ModBlocks.STONE_SMITHING_ANVIL.get())
                .schema(SCHEMA)
                .logic(AnvilLogic::new)
                .openWhen(AnvilStation::opens)
                .idleLogic();
        // One anvil per configured tier (config or KubeJS), registered by Overgeared before this mod's setup.
        for (var anvil : ModBlocks.TIERED_ANVILS.values()) {
            builder.block(() -> anvil.get());
        }
        return builder.build();
    }

    public static void register() {
        Diegesis.register(TYPE);
    }

    public static boolean isBlueprint(ItemStack stack) {
        return stack.is(ModItems.BLUEPRINT.get()) || stack.getItem() instanceof SmithingTemplateItem;
    }

    /**
     * Sneaking keeps Overgeared's own use of the anvil: its screen, and its own minigame started with the hammer. Every other use opens
     * the station, with whatever is in hand.
     */
    public static boolean opens(Player player, BlockPos pos, BlockState state) {
        return !player.isSecondaryUseActive() && player.level().getBlockEntity(pos) instanceof AbstractSmithingAnvilBlockEntity;
    }
}
