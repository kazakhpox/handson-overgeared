package net.handsonovergeared.devtools;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.client.DiegesisClient;
import com.pockyl.diegesis.api.client.StationView;

import net.handsonovergeared.station.AnvilStation;
import net.handsonovergeared.station.ForgeRhythm;
import net.stirdrem.overgeared.block.ModBlocks;
import net.stirdrem.overgeared.block.custom.AbstractSmithingAnvil;
import net.stirdrem.overgeared.block.entity.AbstractSmithingAnvilBlockEntity;
import net.stirdrem.overgeared.item.ModItems;

/**
 * Builds a flat test world, puts an iron smithing anvil before the player, opens the station and works it with the mouse as a player
 * would, saving screenshots along the way to the folder in the system property {@code handson.shots}. Then quits.
 */
@Mod("handson_overgeared_devtools")
public final class ShotDriver {
    private static final String WORLD = "handson_shots";
    private static final BlockPos ANVIL = new BlockPos(0, -60, 0);

    private final Deque<Step> steps = new ArrayDeque<>();
    private int wait;
    private int strikes;

    private record Step(String name, BooleanSupplier until, Runnable action) {
    }

    public ShotDriver(IEventBus modBus) {
        if (System.getProperty("handson.shots") == null) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        plan();
    }

    private void then(String name, int ticks, Runnable action) {
        steps.add(new Step(name, () -> true, action));
        steps.add(new Step(name + " wait", null, () -> wait = ticks));
    }

    private void when(String name, BooleanSupplier until, Runnable action) {
        steps.add(new Step(name, until, action));
    }

    private void plan() {
        when("title", () -> Minecraft.getInstance().screen instanceof TitleScreen, this::createWorld);
        when("in world", () -> Minecraft.getInstance().player != null && Minecraft.getInstance().getSingleplayerServer() != null
                && Minecraft.getInstance().screen == null, () -> wait = 60);
        then("build", 40, this::build);
        then("world view", 10, () -> shot("00_world"));
        then("open", 60, ShotDriver::use);
        then("open shot", 5, () -> shot("01_open"));
        then("fill grid", 30, this::fillGrid);
        then("grid shot", 5, () -> shot("02_grid"));
        then("take hammer", 20, () -> station().ifPresent(view -> view.takeFromInventory(0)));
        then("aim", 20, () -> aimAt(8.0F, 16.0F, 8.0F));
        then("hammer shot", 5, () -> shot("03_hammer"));
        then("start", 30, ShotDriver::click);
        then("bar shot", 3, () -> shot("04_bar"));
        for (int i = 0; i < 3; i++) {
            int strike = i;
            when("timing " + i, this::markerInPerfect, () -> {
                click();
                strikes++;
            });
            steps.add(new Step("swing wait " + i, null, () -> wait = 1));
            then("swing shot " + i, 25, () -> shot("05_strike_" + strike));
        }
        then("done shot", 40, () -> {
            shot("06_done");
            station().ifPresent(view -> System.out.println("[shots] result " + view.state().getItem(AnvilStation.RESULT_SLOT)
                    + " " + view.state().getItem(AnvilStation.RESULT_SLOT).getComponentsPatch()));
        });
        then("leave", 50, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
            }
        });
        then("refill", 30, this::fillGrid);
        then("idle shot", 5, () -> shot("07_idle"));
        then("leave anvil", 40, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) {
                mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
            }
        });
        then("build furnaces", 30, this::buildFurnaces);
        String[] furnaces = {"alloy", "nether_alloy", "casting"};
        for (int i = 0; i < 3; i++) {
            BlockPos pos = FURNACES[i];
            String name = furnaces[i];
            then("stand " + name, 15, () -> server(player -> player.teleportTo(player.serverLevel(), pos.getX() + 0.5, pos.getY(),
                    pos.getZ() + 1.7, 180.0F, 40.0F)));
            then("aim " + name, 5, () -> Minecraft.getInstance().player.lookAt(
                    net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, new Vec3(pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5)));
            then("open " + name, 80, ShotDriver::use);
            then("shot " + name, 5, () -> shot("10_" + name));
            then("later " + name, 60, () -> {
            });
            then("shot later " + name, 5, () -> shot("11_" + name + "_later"));
            then("leave " + name, 30, () -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen != null) {
                    mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                }
            });
        }
        then("closed shot", 5, () -> shot("12_closed"));
        then("quit", 5, () -> Minecraft.getInstance().stop());
    }

    private void tick() {
        if (wait > 0) {
            wait--;
            return;
        }
        Step step = steps.peek();
        if (step == null) {
            return;
        }
        if (step.until() == null) {
            steps.poll();
            step.action().run();
            return;
        }
        if (step.until().getAsBoolean()) {
            steps.poll();
            System.out.println("[shots] " + step.name());
            step.action().run();
        }
    }

    private void createWorld() {
        Minecraft mc = Minecraft.getInstance();
        Path saves = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        try {
            if (Files.exists(saves)) {
                try (var walk = Files.walk(saves)) {
                    walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules,
                WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value()
                        .createWorldDimensions(), mc.screen);
    }

    /** A right click at what the crosshair is on, with an empty hand: the player uses the anvil as anybody would. */
    private static void use() {
        Minecraft mc = Minecraft.getInstance();
        mc.player.getInventory().selected = 4;
        System.out.println("[shots] looking at " + mc.hitResult);
        try {
            Method method = Minecraft.class.getDeclaredMethod("startUseItem");
            method.setAccessible(true);
            method.invoke(mc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void server(java.util.function.Consumer<ServerPlayer> action) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().get(0)));
    }

    private void build() {
        server(player -> {
            ServerLevel level = player.serverLevel();
            level.setDayTime(6000);
            // Overgeared turns the anvil a quarter clockwise from the player who builds it: one looking north builds it facing east.
            BlockState anvil = ModBlocks.IRON_SMITHING_ANVIL.get().defaultBlockState().setValue(AbstractSmithingAnvil.FACING, Direction.EAST);
            level.setBlockAndUpdate(ANVIL, anvil);
            level.setBlockAndUpdate(ANVIL.west(), net.minecraft.world.level.block.Blocks.BLAST_FURNACE.defaultBlockState());
            level.setBlockAndUpdate(ANVIL.east().north(), net.minecraft.world.level.block.Blocks.LANTERN.defaultBlockState());
            player.teleportTo(level, ANVIL.getX() + 0.5, ANVIL.getY(), ANVIL.getZ() + 2.6, 180.0F, 35.0F);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(ModItems.SMITHING_HAMMER.get()));
            player.getInventory().setItem(1, new ItemStack(ModItems.HEATED_IRON_INGOT.get(), 12));
            player.getInventory().setItem(2, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 20));
            player.getInventory().setItem(3, new ItemStack(ModItems.COPPER_SMITHING_HAMMER.get()));
            player.getInventory().selected = 4;
        });
    }

    private static final BlockPos[] FURNACES = {new BlockPos(4, -60, 0), new BlockPos(6, -60, 0), new BlockPos(8, -60, 0)};

    /** Three furnaces in a row, their fronts to the south (Overgeared's facing names a furnace's back), stocked to smelt. */
    private void buildFurnaces() {
        server(player -> {
            ServerLevel level = player.serverLevel();
            net.minecraft.world.level.block.Block[] blocks = {ModBlocks.ALLOY_FURNACE.get(), ModBlocks.NETHER_ALLOY_FURNACE.get(),
                    ModBlocks.CAST_FURNACE.get()};
            for (int i = 0; i < 3; i++) {
                BlockState state = blocks[i].defaultBlockState();
                if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
                    state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
                }
                level.setBlockAndUpdate(FURNACES[i], state);
            }
            if (level.getBlockEntity(FURNACES[0]) instanceof net.minecraft.world.Container alloy) {
                alloy.setItem(0, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 6));
                alloy.setItem(1, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 6));
                alloy.setItem(2, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 6));
                alloy.setItem(3, new ItemStack(net.minecraft.world.item.Items.COAL, 2));
                alloy.setItem(4, new ItemStack(net.minecraft.world.item.Items.COAL, 8));
            }
            if (level.getBlockEntity(FURNACES[1]) instanceof net.minecraft.world.Container nether) {
                for (int i = 0; i < 4; i++) {
                    nether.setItem(i, new ItemStack(net.minecraft.world.item.Items.NETHERITE_SCRAP, 1));
                    nether.setItem(4 + i, new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 1));
                }
                nether.setItem(9, new ItemStack(net.minecraft.world.item.Items.BLAZE_ROD, 4));
            }
            if (level.getBlockEntity(FURNACES[2]) instanceof net.minecraft.world.Container casting) {
                casting.setItem(1, new ItemStack(net.minecraft.world.item.Items.COAL, 5));
                casting.setItem(0, new ItemStack(net.minecraft.world.item.Items.RAW_GOLD, 4));
            }
        });
    }

    private void fillGrid() {
        server(player -> {
            if (player.level().getBlockEntity(ANVIL) instanceof AbstractSmithingAnvilBlockEntity anvil) {
                for (int i = 0; i < 3; i++) {
                    anvil.setItem(i, new ItemStack(ModItems.HEATED_IRON_INGOT.get()));
                }
                player.getInventory().getItem(1).shrink(3);
            }
        });
    }

    private static Optional<StationView> station() {
        return DiegesisClient.focusedStation();
    }

    /** Puts the mouse over a point of the scene. */
    private static void aimAt(float x, float y, float z) {
        station().ifPresent(view -> {
            Vec3 world = view.toWorld(x, y, z);
            Optional<Vector2f> screen = DiegesisClient.toScreen(world);
            if (screen.isEmpty()) {
                System.out.println("[shots] point off screen");
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            double scale = mc.getWindow().getGuiScale();
            double px = screen.get().x * scale;
            double py = screen.get().y * scale;
            long window = mc.getWindow().getWindow();
            GLFW.glfwSetCursorPos(window, px, py);
            invoke("onMove", new Class<?>[]{long.class, double.class, double.class}, window, px, py);
        });
    }

    private static void click() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        invoke("onPress", new Class<?>[]{long.class, int.class, int.class, int.class}, window, 0, GLFW.GLFW_PRESS, 0);
        invoke("onPress", new Class<?>[]{long.class, int.class, int.class, int.class}, window, 0, GLFW.GLFW_RELEASE, 0);
    }

    private static void invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = Minecraft.getInstance().mouseHandler.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(Minecraft.getInstance().mouseHandler, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Whether the marker is in the perfect zone now, as the server's data has it; gives up waiting after a while. */
    private int waited;

    private boolean markerInPerfect() {
        if (++waited > 80) {
            waited = 0;
            return true;
        }
        Optional<StationView> view = station();
        if (view.isEmpty()) {
            return false;
        }
        CompoundTag data = view.get().state().data();
        if (!data.getBoolean(AnvilStation.ON)) {
            return waited > 10;
        }
        ForgeRhythm.Settings settings = ForgeRhythm.Settings.read(data);
        int done = data.getInt(AnvilStation.DONE);
        if (done < strikes) {
            return false;
        }
        ForgeRhythm.Zones zones = ForgeRhythm.zones(settings, data.getLong(AnvilStation.SEED), done);
        float time = view.get().level().getGameTime() + 1.0F;
        ForgeRhythm.Marker marker = ForgeRhythm.marker(new ForgeRhythm.Marker(data.getFloat(AnvilStation.ANCHOR_POS),
                data.getBoolean(AnvilStation.ANCHOR_UP)), settings.speedAfter(done), time - data.getLong(AnvilStation.ANCHOR_TICK));
        boolean in = zones.grade(marker.position()) == ForgeRhythm.Grade.PERFECT;
        if (in) {
            waited = 0;
        }
        return in;
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        File dir = new File(System.getProperty("handson.shots"));
        dir.mkdirs();
        Screenshot.grab(dir, name + ".png", mc.getMainRenderTarget(), message -> System.out.println("[shots] " + message.getString()));
    }
}
