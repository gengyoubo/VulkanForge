package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * GL 4.6 fast-movement check (-Pgl46Move): a generated world at render distance 10, GL 4.6 terrain on, then a 20 s straight
 * flight at 1.5 blocks per tick (elytra speed). Reports whole-frame times, the CPU of WorldCapture.update (selection,
 * publishing and GL uploads) and upload counters, so section streaming hitches can be compared between builds.
 */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.CLIENT)
public final class Gl46MoveSmoke {
    private static final int FLIGHT_TICKS = 400;
    private static final double SPEED = 1.5;
    private static int stage, ticks;
    private static long[] schedulerBefore, terrainBefore;
    private static final long START = System.nanoTime();
    private static Path report;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("vulkanforge.gl46Move") || event.phase != TickEvent.Phase.END || stage == 99) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - START) > 420) throw new IllegalStateException("GL 4.6 move smoke timed out, stage=" + stage);
            if (stage == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                report = mc.gameDirectory.toPath().resolve("logs/vulkanforge/gl46-move");
                Files.createDirectories(report);
                Files.writeString(report.resolve("report.txt"), "");
                if (!VulkanForgeConfig.startup().gl46Terrain()) throw new AssertionError("Startup is " + VulkanForgeConfig.startup());
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(10);
                mc.options.hideGui = true;
                String name = "VulkanForge GL46 Move " + System.currentTimeMillis();
                mc.createWorldOpenFlows().createFreshLevel(name,
                        new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234L, false, false),
                        registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions());
                log("Created generated test world: " + name + ", render distance 10");
                stage = 1;
            } else if (stage == 1 && mc.player != null && mc.level != null && mc.screen == null) {
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                server.execute(() -> {
                    var level = server.overworld();
                    level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                    level.setDayTime(6000); level.setWeatherParameters(6000, 0, false, false);
                    var player = server.getPlayerList().getPlayer(uuid);
                    player.getAbilities().flying = true; player.onUpdateAbilities();
                    player.teleportTo(level, 0.5, 140, 0.5, -90, 25);
                });
                stage = 2; ticks = 0;
            } else if (stage == 2 && ++ticks >= 100) {
                VulkanPreview.toggle();
                if (!VulkanPreview.gl46World()) throw new AssertionError("GL 4.6 terrain did not start");
                stage = 3; ticks = 0;
            } else if (stage == 3 && ++ticks >= 40 && (VulkanPreview.capturedSections() > 400 || ticks > 600)) {
                log("Settled: sections=" + VulkanPreview.capturedSections() + " after " + ticks + " ticks; scheduler=" + Arrays.toString(VulkanPreview.schedulerStats()));
                VulkanPreview.FRAME_TIMER.reset(); VulkanPreview.UPDATE_TIMER.reset();
                schedulerBefore = VulkanPreview.schedulerStats(); terrainBefore = VulkanPreview.gl46Stats();
                stage = 4; ticks = 0;
            } else if (stage == 4 && ++ticks <= FLIGHT_TICKS) {
                double x = 0.5 + ticks * SPEED;
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                server.execute(() -> server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(), x, 140, 0.5, -90, 25));
            } else if (stage == 4) {
                long[] scheduler = VulkanPreview.schedulerStats(), terrain = VulkanPreview.gl46Stats();
                log("FLIGHT " + FLIGHT_TICKS + " ticks at " + SPEED + " blocks/tick: frame{" + VulkanPreview.FRAME_TIMER + "} frames=" + VulkanPreview.FRAME_TIMER.frames()
                        + " over33ms=" + VulkanPreview.FRAME_TIMER.over(33_000) + " over50ms=" + VulkanPreview.FRAME_TIMER.over(50_000));
                log("WorldCapture.update{" + VulkanPreview.UPDATE_TIMER + "} over2ms=" + VulkanPreview.UPDATE_TIMER.over(2_000) + " over5ms=" + VulkanPreview.UPDATE_TIMER.over(5_000));
                log("published=" + (scheduler[0] - schedulerBefore[0]) + " cancelled=" + (scheduler[1] - schedulerBefore[1]) + " deferred=" + (scheduler[2] - schedulerBefore[2])
                        + " queued=" + scheduler[3] + " maxPerFrame=" + scheduler[5] + " sections=" + terrain[1]
                        + " uploadedMiB=" + String.format("%.1f", (terrain[5] - terrainBefore[5]) / 1048576.0) + " vertexCapacity=" + terrain[3] + " vertexGrows=" + terrain[4]
                        + " stagingWaits=" + terrain[6]);
                long[] grow = VulkanPreview.gl46GrowStats();
                log(String.format("vertex arena grows=%d total=%.1fms worst=%.1fms; scheduler{budgetDeferrals,rescans,validations}=%s; sorts=%s",
                        grow[0], grow[1] / 1e6, grow[2] / 1e6, Arrays.toString(Arrays.copyOfRange(scheduler, Math.min(7, scheduler.length), scheduler.length)),
                        Arrays.toString(VulkanPreview.gl46SortStats())));
                log("CPU per frame " + VulkanPreview.gl46Timers());
                log("STATUS=OK"); stage = 99;
                Thread watchdog = new Thread(() -> {
                    try { Thread.sleep(30_000); Files.writeString(report.resolve("report.txt"), "EXIT_TIMEOUT" + System.lineSeparator(), StandardOpenOption.APPEND); }
                    catch (Exception ignored) { }
                    Runtime.getRuntime().halt(3);
                }, "VulkanForge-gl46-move-exit-watchdog");
                watchdog.setDaemon(true); watchdog.start();
                VulkanPreview.close();
                mc.stop();
            }
        } catch (Throwable error) {
            VulkanForge.LOGGER.error("GL 4.6 move smoke failed at stage " + stage, error);
            try { log("STATUS=FAILED " + error); } catch (Exception ignored) { }
            stage = 99; mc.stop();
        }
    }

    private static void log(String message) throws java.io.IOException {
        VulkanForge.LOGGER.info("GL 4.6 move smoke: {}", message);
        Files.writeString(report.resolve("report.txt"), message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
