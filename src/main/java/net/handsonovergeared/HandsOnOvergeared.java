package net.handsonovergeared;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.common.Mod;

import net.handsonovergeared.station.AnvilStation;

/**
 * Hands-On Overgeared: Overgeared's smithing anvil opens in the world as a Diegesis station. Adds nothing to the registries, so a
 * player or a server without it keeps Overgeared's own screen.
 */
@Mod(HandsOnOvergeared.MOD_ID)
public final class HandsOnOvergeared {
    public static final String MOD_ID = "handson_overgeared";

    public HandsOnOvergeared() {
        AnvilStation.register();
        net.handsonovergeared.furnace.FurnaceStations.register();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
