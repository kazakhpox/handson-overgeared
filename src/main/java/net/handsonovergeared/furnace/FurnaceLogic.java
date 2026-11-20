package net.handsonovergeared.furnace;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.api.state.SlotStorage;
import com.pockyl.diegesis.api.station.StationContext;
import com.pockyl.diegesis.api.station.StationLogic;

import net.stirdrem.overgeared.block.entity.AlloySmelterBlockEntity;
import net.stirdrem.overgeared.block.entity.CastingSmelterBlockEntity;
import net.stirdrem.overgeared.block.entity.NetherAlloySmelterBlockEntity;

/**
 * An Overgeared furnace at work. It smelts by itself; the station binds its slots, keeps to its own rules for what goes where, gives
 * the experience it stored when its result is taken, and tells the clients how the fire and the smelting go.
 */
public final class FurnaceLogic implements StationLogic {
    private final FurnaceStations.Layout layout;
    private final BlockEntity furnace;
    private final Container container;
    private int shownBurn = -1;
    private int shownCook = -1;
    private int shownCookMax = -1;

    public FurnaceLogic(StationContext station, FurnaceStations.Layout layout) {
        this.layout = layout;
        this.furnace = station.blockEntity();
        this.container = furnace instanceof Container found ? found : null;
        if (container == null) {
            return;
        }
        station.state().bindSlots(FurnaceStations.INPUTS, SlotStorage.of(container, 0, layout.inputs()));
        if (layout.cast() >= 0) {
            station.state().bindSlots(FurnaceStations.CAST, SlotStorage.of(container, layout.cast(), 1));
        }
        station.state().bindSlots(FurnaceStations.FUEL, SlotStorage.of(container, layout.fuel(), 1));
        station.state().bindSlots(FurnaceStations.OUTPUT, SlotStorage.of(container, layout.output(), 1));
    }

    private int index(SlotRef slot) {
        return switch (slot.group()) {
            case FurnaceStations.INPUTS -> slot.index();
            case FurnaceStations.CAST -> layout.cast();
            case FurnaceStations.FUEL -> layout.fuel();
            default -> layout.output();
        };
    }

    @Override
    public boolean mayPlace(StationContext station, Player player, SlotRef slot, ItemStack stack) {
        // The furnace's own rules: fuel only in the fuel slot, a tool cast only in the cast slot, nothing into the output.
        return container != null && container.canPlaceItem(index(slot), stack);
    }

    @Override
    public void onTaken(StationContext station, Player player, SlotRef slot, ItemStack stack) {
        if (station.isClientSide() || !FurnaceStations.OUTPUT.equals(slot.group()) || stack.isEmpty()) {
            return;
        }
        stack.onCraftedBy(station.level(), player, stack.getCount());
        switch (furnace) {
            case AlloySmelterBlockEntity alloy -> alloy.awardStoredExperience(player);
            case NetherAlloySmelterBlockEntity nether -> nether.awardStoredExperience(player);
            case CastingSmelterBlockEntity casting -> casting.awardStoredExperience(player);
            default -> {
            }
        }
    }

    @Override
    public void tick(StationContext station) {
        if (station.isClientSide() || !(furnace instanceof FurnaceTimes times)) {
            return;
        }
        int burn = times.handson$burnTime();
        int cook = times.handson$cookTime();
        int cookMax = times.handson$cookTimeTotal();
        boolean litChanged = (burn > 0) != (shownBurn > 0);
        // Every few ticks is enough: the clients run the fire and the bar on between reports.
        if (litChanged || cookMax != shownCookMax || cook < shownCook || station.level().getGameTime() % 5 == 0
                && (burn != shownBurn || cook != shownCook)) {
            shownBurn = burn;
            shownCook = cook;
            shownCookMax = cookMax;
            CompoundTag data = station.state().data();
            data.putInt(FurnaceStations.BURN, burn);
            data.putInt(FurnaceStations.BURN_MAX, times.handson$maxBurnTime());
            data.putInt(FurnaceStations.COOK, cook);
            data.putInt(FurnaceStations.COOK_MAX, cookMax);
            station.state().setData(data);
        }
    }
}
