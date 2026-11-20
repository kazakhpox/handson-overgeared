package net.handsonovergeared.furnace;

import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.state.SlotRules;
import com.pockyl.diegesis.api.state.StationSchema;
import com.pockyl.diegesis.api.station.StationType;

import net.handsonovergeared.HandsOnOvergeared;
import net.stirdrem.overgeared.block.ModBlocks;

/**
 * Overgeared's furnaces as stations, worked as the furnaces of Hands-On Vanilla: the door swings open, what smelts lies on the
 * grate in the mouth, the fuel on the coals under it, and the result comes out beside the door. The furnaces go on smelting by
 * themselves; every slot group is a window on the furnace's own slots.
 */
public final class FurnaceStations {
    public static final String INPUTS = "inputs";
    public static final String CAST = "cast";
    public static final String FUEL = "fuel";
    public static final String OUTPUT = "output";
    /** Data: the fire left and its whole, the smelting done and its whole, in ticks. */
    public static final String BURN = "burn";
    public static final String BURN_MAX = "burn_max";
    public static final String COOK = "cook";
    public static final String COOK_MAX = "cook_max";

    /** Where a furnace keeps what: the first input and how many, the fuel, the output, the cast (-1: none). */
    public record Layout(int inputs, int fuel, int output, int cast) {
        StationSchema schema() {
            StationSchema.Builder builder = StationSchema.builder()
                    .slots(INPUTS, inputs, SlotRules.DEFAULT.kept());
            if (cast >= 0) {
                builder.slots(CAST, 1, SlotRules.DEFAULT.kept());
            }
            return builder.slots(FUEL, 1, SlotRules.DEFAULT.kept())
                    .slots(OUTPUT, 1, SlotRules.DEFAULT.kept().accepts(stack -> false))
                    .build();
        }
    }

    public static final Layout ALLOY = new Layout(4, 4, 5, -1);
    public static final Layout NETHER_ALLOY = new Layout(9, 9, 10, -1);
    public static final Layout CASTING = new Layout(1, 1, 2, 3);

    public static final ResourceLocation ALLOY_ID = HandsOnOvergeared.id("alloy_furnace");
    public static final ResourceLocation NETHER_ALLOY_ID = HandsOnOvergeared.id("nether_alloy_furnace");
    public static final ResourceLocation CASTING_ID = HandsOnOvergeared.id("casting_furnace");

    public static final StationType ALLOY_TYPE = type(ALLOY_ID, () -> ModBlocks.ALLOY_FURNACE.get(), ALLOY);
    public static final StationType NETHER_ALLOY_TYPE = type(NETHER_ALLOY_ID, () -> ModBlocks.NETHER_ALLOY_FURNACE.get(), NETHER_ALLOY);
    public static final StationType CASTING_TYPE = type(CASTING_ID, () -> ModBlocks.CAST_FURNACE.get(), CASTING);

    private FurnaceStations() {
    }

    private static StationType type(ResourceLocation id, Supplier<? extends Block> block, Layout layout) {
        return Diegesis.station(id)
                .block(block)
                .schema(layout.schema())
                .logic(station -> new FurnaceLogic(station, layout))
                // The furnace's facing names its back: its front looks the other way.
                .orientation(StationType.Orientation.BLOCK_OPPOSITE)
                .hidesBlock()
                .openWhen((player, pos, state) -> !player.isSecondaryUseActive())
                .build();
    }

    public static void register() {
        Diegesis.register(ALLOY_TYPE);
        Diegesis.register(NETHER_ALLOY_TYPE);
        Diegesis.register(CASTING_TYPE);
    }

}
