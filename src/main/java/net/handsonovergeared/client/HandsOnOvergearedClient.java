package net.handsonovergeared.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import com.pockyl.diegesis.api.client.DiegesisClient;

import net.handsonovergeared.HandsOnOvergeared;
import net.handsonovergeared.station.AnvilStation;

/** Client setup: the anvil's behaviour, and its effects (the behaviour plays them, see {@link AnvilBehavior#onEffect}). */
@Mod(value = HandsOnOvergeared.MOD_ID, dist = Dist.CLIENT)
public final class HandsOnOvergearedClient {
    public HandsOnOvergearedClient(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(HandsOnOvergearedClient::setup));
    }

    private static void setup() {
        DiegesisClient.registerBehavior(AnvilStation.ID, AnvilBehavior::new);
        for (var furnace : new net.minecraft.resources.ResourceLocation[]{net.handsonovergeared.furnace.FurnaceStations.ALLOY_ID,
                net.handsonovergeared.furnace.FurnaceStations.NETHER_ALLOY_ID, net.handsonovergeared.furnace.FurnaceStations.CASTING_ID}) {
            DiegesisClient.registerBehavior(furnace, FurnaceBehavior::new);
        }
        for (var effect : new net.minecraft.resources.ResourceLocation[]{AnvilStation.EFFECT_STRUCK, AnvilStation.EFFECT_FORGED,
                AnvilStation.EFFECT_REFUSED, AnvilStation.EFFECT_STARTED}) {
            DiegesisClient.registerEffect(effect, (view, node, data) -> {
            });
        }
    }
}
