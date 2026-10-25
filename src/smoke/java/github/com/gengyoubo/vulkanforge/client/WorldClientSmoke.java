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
    private static double dayBrightness, nightBrightness;
    private static long lightBaseline;
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
                Files.writeString(report.resolve("report.txt"), "");
                stage = 1;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(3);
                mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
                mc.options.gamma().set(0.0);
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
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.setDayTime(6000);
                    for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) level.setBlock(new BlockPos(x, 64, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    for (int x = -2; x <= 2; x++) for (int y = 65; y <= 69; y++) level.setBlock(new BlockPos(x, y, 0), Blocks.RED_CONCRETE.defaultBlockState(), 3);
                    for (int x = -2; x <= 2; x++) for (int y = 65; y <= 69; y++) level.setBlock(new BlockPos(x, y, 3), Blocks.OAK_LEAVES.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true), 3);
                    for (int x = -4; x <= -3; x++) for (int y = 65; y <= 68; y++) level.setBlock(new BlockPos(x, y, 2), Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(3, 65, 3), Blocks.OAK_FENCE.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(3, 65, 5), Blocks.DANDELION.defaultBlockState(), 3);
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
                require(VulkanPreview.capturedCutoutBlocks() > 0, "No cutout blocks were captured");
                require(VulkanPreview.atlasPatches() > 0 && VulkanPreview.lightUpdates() > 0, "Texture synchronization did not start");
                log("PASS cutout model capture, atlas patches=" + VulkanPreview.atlasPatches() + ", lightmap updates=" + VulkanPreview.lightUpdates());
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
                dayBrightness = brightness(report.resolve("reloaded.png"));
                lightBaseline = VulkanPreview.lightUpdates();
                mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setDayTime(18000));
                stage = 7; ticks = 0;
            } else if (stage == 7 && ++ticks > 100) {
                VulkanPreview.saveWorldFrame(report.resolve("night.png"));
                nightBrightness = brightness(report.resolve("night.png"));
                require(VulkanPreview.lightUpdates() > lightBaseline && nightBrightness < dayBrightness * .85,
                        "Night lightmap did not darken Vulkan terrain: day=" + dayBrightness + ", night=" + nightBrightness);
                log("PASS night lighting: mean brightness " + dayBrightness + " -> " + nightBrightness);
                mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setDayTime(6000));
                stage = 8; ticks = 0;
            } else if (stage == 8 && ++ticks > 100) {
                VulkanPreview.saveWorldFrame(report.resolve("day-restored.png"));
                require(brightness(report.resolve("day-restored.png")) > nightBrightness * 1.15, "Day lightmap did not restore brightness");
                log("PASS daylight restoration; animated atlas patches=" + VulkanPreview.atlasPatches());
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
    private static double brightness(Path path) throws java.io.IOException {
        var image = javax.imageio.ImageIO.read(path.toFile());
        double sum = 0; int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int color = image.getRGB(x, y);
            if ((color >>> 24) < 200) continue;
            sum += .2126 * ((color >>> 16) & 255) + .7152 * ((color >>> 8) & 255) + .0722 * (color & 255); count++;
        }
        require(count > 1000, "Not enough terrain pixels for lighting check");
        return sum / count;
    }
}
