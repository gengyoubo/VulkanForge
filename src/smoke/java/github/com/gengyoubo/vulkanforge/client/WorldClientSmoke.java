package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Opt-in development source set; never packaged in the mod jar. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.CLIENT)
public final class WorldClientSmoke {
    private static int stage, ticks, firstHash;
    private static long firstEpoch;
    private static final long START = System.nanoTime();
    private static Path report;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("vulkanforge.worldSmoke") || event.phase != TickEvent.Phase.END || stage == 99) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - START) > 180) throw new IllegalStateException("World client smoke timed out, stage=" + stage);
            if (stage == 0 && mc.screen instanceof TitleScreen) {
                report = mc.gameDirectory.toPath().resolve("logs/vulkanforge/world-client-smoke");
                Files.createDirectories(report);
                stage = 1;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(3);
                String name = "VulkanForge Smoke " + System.currentTimeMillis();
                mc.createWorldOpenFlows().createFreshLevel(name,
                        new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234L, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
                log("Created isolated flat test world");
            } else if (stage == 1 && mc.player != null && mc.level != null && mc.screen == null) {
                stage = 2; ticks = 0;
                var server = mc.getSingleplayerServer();
                var uuid = mc.player.getUUID();
                server.execute(() -> {
                    var level = server.overworld();
                    for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) level.setBlock(new BlockPos(x, 64, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    for (int x = -2; x <= 2; x++) for (int y = 65; y <= 69; y++) level.setBlock(new BlockPos(x, y, 0), Blocks.RED_CONCRETE.defaultBlockState(), 3);
                    var player = server.getPlayerList().getPlayer(uuid);
                    player.teleportTo(level, 0.5, 68.0, 9.0, 180, 18);
                    player.getAbilities().flying = true; player.onUpdateAbilities();
                });
            } else if (stage == 2 && ++ticks >= 50) {
                VulkanPreview.toggle(); stage = 3; ticks = 0;
            } else if (stage == 3 && ++ticks > 100 && VulkanPreview.worldEpoch() > 0 && VulkanPreview.capturedSections() >= 10) {
                firstHash = VulkanPreview.saveWorldFrame(report.resolve("before.png"));
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, "world-smoke-main.png", mc.getMainRenderTarget(), message -> { });
                firstEpoch = VulkanPreview.worldEpoch();
                log("PASS real block atlas and section capture, epoch=" + firstEpoch + ", sections=" + VulkanPreview.capturedSections());
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                server.execute(() -> { var player = server.getPlayerList().getPlayer(uuid); player.teleportTo(server.overworld(), 0.5, 68, 9, 130, 18); });
                stage = 4; ticks = 0;
            } else if (stage == 4 && ++ticks > 60) {
                require(VulkanPreview.saveWorldFrame(report.resolve("camera.png")) != firstHash, "Camera rotation did not change Vulkan frame");
                log("PASS camera rotation");
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                server.execute(() -> {
                    server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(), 0.5, 68, 9, 180, 18);
                    for (int x = -3; x <= 3; x++) for (int y = 65; y <= 70; y++) server.overworld().setBlock(new BlockPos(x, y, 5), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                });
                stage = 5; ticks = 0;
            } else if (stage == 5 && ++ticks > 150) {
                require(VulkanPreview.saveWorldFrame(report.resolve("updated.png")) != firstHash, "Block placement did not update Vulkan frame");
                log("PASS block placement and section replacement");
                mc.reloadResourcePacks(); stage = 6; ticks = 0;
            } else if (stage == 6 && ++ticks > 100 && VulkanPreview.worldEpoch() > firstEpoch && VulkanPreview.capturedSections() >= 10) {
                VulkanPreview.saveWorldFrame(report.resolve("reloaded.png"));
                log("PASS resource reload, new epoch=" + VulkanPreview.worldEpoch());
                log("STATUS=OK");
                stage = 99; mc.stop();
            }
        } catch (Throwable error) {
            VulkanForge.LOGGER.error("World client smoke failed at stage " + stage, error);
            try { log("STATUS=FAILED " + error); } catch (Exception ignored) { }
            stage = 99; mc.stop();
        }
    }

    private static void log(String message) throws java.io.IOException {
        VulkanForge.LOGGER.info("World client smoke: {}", message);
        Files.writeString(report.resolve("report.txt"), message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
