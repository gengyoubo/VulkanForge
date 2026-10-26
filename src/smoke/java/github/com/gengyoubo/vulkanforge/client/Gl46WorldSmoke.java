package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
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

/**
 * OpenGL 4.6 backend check (-Pgl46Smoke): flat world with opaque, cutout, water, glass, block entities and a mob; the
 * same view is captured with GL 4.6 terrain on, off (vanilla) and on again, and the two renderers must agree.
 */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.CLIENT)
public final class Gl46WorldSmoke {
    private static int stage, ticks;
    private static long[] sortsBefore;
    private static final long START = System.nanoTime();
    private static Path report;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("vulkanforge.gl46Smoke") || event.phase != TickEvent.Phase.END || stage == 99) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - START) > 300) throw new IllegalStateException("GL 4.6 smoke timed out, stage=" + stage);
            if (stage == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                report = mc.gameDirectory.toPath().resolve("logs/vulkanforge/gl46-smoke");
                Files.createDirectories(report);
                Files.writeString(report.resolve("report.txt"), "");
                require(VulkanForgeConfig.startup().gl46Terrain(), "Startup is " + VulkanForgeConfig.startup() + ", expected OPENGL 4.6");
                require(!NativeMainWindow.active(), "OpenGL backend must not create the Vulkan main window");
                require(org.lwjgl.opengl.GL.getCapabilities().OpenGL46, "Context is " + Gl46Context.describe());
                log("PASS startup OPENGL 4.6, context " + Gl46Context.describe());
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(4);
                mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
                mc.options.hideGui = true;
                createWorld(mc);
                stage = 1;
            } else if (stage == 1 && mc.player != null && mc.level != null && mc.screen == null) {
                buildScene(mc);
                stage = 2; ticks = 0;
            } else if (stage == 2 && ++ticks >= 60) {
                VulkanPreview.toggle();
                require(VulkanPreview.gl46World(), "GL 4.6 terrain did not start; see logs/vulkanforge/gl46-report.txt");
                stage = 3; ticks = 0;
            } else if (stage == 3 && ++ticks > 100 && VulkanPreview.gl46Stats()[0] > 60 && VulkanPreview.capturedSections() >= 20
                    && VulkanPreview.gl46Covers(new BlockPos(0, 64, 0))) {
                long[] stats = VulkanPreview.gl46Stats();
                require(VulkanPreview.gl46Covers(new BlockPos(0, -64, 0)), "Vanilla still draws covered bottom sections");
                require(stats[7] > 0 && stats[8] > 0, "Water/glass were not drawn by the translucent pass: " + java.util.Arrays.toString(stats));
                String gl = Files.readString(mc.gameDirectory.toPath().resolve("logs/vulkanforge/gl46-report.txt"));
                require(gl.contains("TERRAIN_INIT") && gl.contains("FIRST_OPAQUE_GL_ERROR=NONE") && gl.contains("FIRST_TRANSLUCENT_GL_ERROR=NONE"),
                        "GL 4.6 terrain reported an error:\n" + gl);
                log("PASS GL 4.6 terrain: sections=" + VulkanPreview.capturedSections() + " stats=" + java.util.Arrays.toString(stats)
                        + " shaders=" + (gl.contains("shaders=SPIRV") ? "SPIRV" : "GLSL"));
                long[] entities = VulkanPreview.gl46EntityStats();
                // {captured, flushes, multiDraws, splits, rejected}: the cow and the chest must go through the GL 4.6 pass.
                require(entities[0] > 0 && entities[2] > 0, "GL 4.6 entity pass captured nothing: " + java.util.Arrays.toString(entities)
                        + " fallbacks=" + VulkanPreview.gl46EntityFallbacks());
                log("PASS GL 4.6 entities: stats=" + java.util.Arrays.toString(entities) + " fallbacks=" + VulkanPreview.gl46EntityFallbacks());
                require(GlStateCache.trusted(), "GL state cache rejected; see gl46-report.txt STATE_CACHE");
                if (GlStateCache.verifying()) {
                    require(GlStateCache.verified() > 0, "State verification never ran");
                    log("PASS GL state cache matched glGet on " + GlStateCache.verified() + " snapshots");
                }
                long frames = Math.max(1, stats[0]); // terrain frames: flushes per frame vary with splits
                log(String.format("CPU entity capture %.1f us/frame (%.2f us/draw), flush %.1f us/frame (%.2f flushes/frame); terrain opaque %.1f us, translucent %.1f us per frame",
                        entities[5] / 1e3 / frames, entities[5] / 1e3 / Math.max(1, entities[0]), entities[6] / 1e3 / frames, entities[1] / (double) frames,
                        VulkanPreview.gl46Stats()[9] / 1e3 / Math.max(1, stats[0]), VulkanPreview.gl46Stats()[10] / 1e3 / Math.max(1, stats[0])));
                log("CPU per frame " + VulkanPreview.gl46Timers());
                log("CPU us/frame breakdown: opaque {snapshot, cull, draws, fence, restore}=" + perFrame(VulkanPreview.gl46Phases(), stats[0])
                        + " entity flush {snapshot, draws, restore, fences}=" + perFrame(VulkanPreview.gl46EntityPhases(), frames));
                screenshot(mc, "gl46.png");
                VulkanPreview.toggle();
                require(!VulkanPreview.gl46World(), "F8 did not turn GL 4.6 terrain off");
                stage = 4; ticks = 0;
            } else if (stage == 4 && ++ticks > 40) {
                screenshot(mc, "vanilla.png");
                double[] diff = compare(report.resolve("gl46.png"), report.resolve("vanilla.png"));
                log(String.format("COMPARE gl46 vs vanilla: meanAbs=%.2f differing=%.4f", diff[0], diff[1]));
                require(diff[0] < 12 && diff[1] < 0.08, "GL 4.6 terrain differs from vanilla too much");
                log("PASS GL 4.6 terrain matches vanilla");
                VulkanPreview.toggle();
                stage = 5; ticks = 0;
            } else if (stage == 5 && ++ticks > 60 && VulkanPreview.gl46Stats()[0] > 30 && VulkanPreview.gl46Covers(new BlockPos(0, 64, 0))) {
                screenshot(mc, "gl46-restarted.png");
                log("PASS restart after F8 off/on: stats=" + java.util.Arrays.toString(VulkanPreview.gl46Stats()));
                sortsBefore = VulkanPreview.gl46SortStats();
                stage = 6; ticks = 0;
            } else if (stage == 6 && ++ticks <= 100) {
                // Fly along the glass wall and the pool: translucent sections must keep re-sorting within budget.
                double x = -20 + ticks * 0.4, z = 11.0 - ticks * 0.15;
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                server.execute(() -> { var player = server.getPlayerList().getPlayer(uuid); player.teleportTo(server.overworld(), x, 68.0, z, 180, 18); });
            } else if (stage == 6) {
                long[] sorts = VulkanPreview.gl46SortStats();
                require(sorts[0] > sortsBefore[0], "Camera flight did not re-sort any translucent quads");
                log("PASS translucent sorting during flight: {sortedQuads, insertionSorts, fullSorts, deferredSorts} "
                        + java.util.Arrays.toString(sortsBefore) + " -> " + java.util.Arrays.toString(sorts));
                log("CPU per frame after flight " + VulkanPreview.gl46Timers());
                screenshot(mc, "gl46-flight.png");
                VulkanPreview.close();
                require(Files.readString(mc.gameDirectory.toPath().resolve("logs/vulkanforge/gl46-report.txt")).contains("TERRAIN_CLOSE"), "Renderer did not close");
                log("STATUS=OK"); stage = 99;
                Thread watchdog = new Thread(() -> {
                    try { Thread.sleep(30_000); Files.writeString(report.resolve("report.txt"), "EXIT_TIMEOUT" + System.lineSeparator(), StandardOpenOption.APPEND); }
                    catch (Exception ignored) { }
                    Runtime.getRuntime().halt(3);
                }, "VulkanForge-gl46-smoke-exit-watchdog");
                watchdog.setDaemon(true); watchdog.start();
                mc.stop();
            }
        } catch (Throwable error) {
            VulkanForge.LOGGER.error("GL 4.6 smoke failed at stage " + stage, error);
            try { log("STATUS=FAILED " + error); } catch (Exception ignored) { }
            stage = 99; mc.stop();
        }
    }

    private static void buildScene(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var level = server.overworld();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.setDayTime(6000);
            level.setWeatherParameters(6000, 0, false, false);
            for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) level.setBlock(new BlockPos(x, 64, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            for (int x = -2; x <= 2; x++) for (int y = 65; y <= 69; y++) level.setBlock(new BlockPos(x, y, 0), Blocks.RED_CONCRETE.defaultBlockState(), 3);
            for (int x = -2; x <= 2; x++) for (int y = 65; y <= 69; y++) level.setBlock(new BlockPos(x, y, 3), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT, true), 3);
            level.setBlock(new BlockPos(3, 65, 5), Blocks.DANDELION.defaultBlockState(), 3);
            for (int x = -8; x <= -4; x++) for (int z = 4; z <= 8; z++) level.setBlock(new BlockPos(x, 65, z),
                    x == -8 || x == -4 || z == 4 || z == 8 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState(), 3);
            for (int x = -1; x <= 1; x++) for (int y = 65; y <= 66; y++) level.setBlock(new BlockPos(x, y, 7), Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), 3);
            level.setBlock(new BlockPos(3, 65, 6), Blocks.CHEST.defaultBlockState(), 3);
            // Entity density: a 10x6 herd and a row of chests behind the wall, plus a glass pane wall across several sections.
            for (int x = -9; x <= 9; x += 2) for (int z = -3; z >= -13; z -= 2) {
                var herd = net.minecraft.world.entity.EntityType.COW.spawn(level, new BlockPos(x, 65, z), net.minecraft.world.entity.MobSpawnType.COMMAND);
                if (herd != null) { herd.setNoAi(true); herd.setYRot(x * 9); }
            }
            for (int x = -12; x <= 12; x++) level.setBlock(new BlockPos(x, 65, -15), Blocks.CHEST.defaultBlockState(), 3);
            for (int x = -24; x <= 24; x++) for (int y = 65; y <= 67; y++) level.setBlock(new BlockPos(x, y, -18), Blocks.WHITE_STAINED_GLASS.defaultBlockState(), 3);
            var cow =net.minecraft.world.entity.EntityType.COW.spawn(level, new BlockPos(-3, 65, 6), net.minecraft.world.entity.MobSpawnType.COMMAND);
            if (cow != null) { cow.setNoAi(true); cow.setYRot(30); }
            var player = server.getPlayerList().getPlayer(uuid);
            player.teleportTo(level, 0.5, 68.0, 11.0, 180, 18);
            player.getAbilities().flying = true; player.onUpdateAbilities();
        });
    }

    private static void createWorld(Minecraft mc) throws java.io.IOException {
        String name = "VulkanForge GL46 Smoke " + System.currentTimeMillis();
        mc.createWorldOpenFlows().createFreshLevel(name,
                new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(1234L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
        log("Created isolated flat test world: " + name);
    }

    /** The main target holds the last completed frame at the end of a client tick. */
    private static void screenshot(Minecraft mc, String name) throws java.io.IOException {
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(report.resolve(name)); }
    }

    /** {mean absolute RGB difference, fraction of pixels differing by more than 48 in any channel}. */
    private static double[] compare(Path first, Path second) throws java.io.IOException {
        var a = javax.imageio.ImageIO.read(first.toFile()); var b = javax.imageio.ImageIO.read(second.toFile());
        require(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "Screenshot sizes differ");
        double sum = 0; long differing = 0, pixels = (long) a.getWidth() * a.getHeight();
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y), worst = 0;
            for (int shift = 0; shift <= 16; shift += 8) {
                int d = Math.abs((p >> shift & 255) - (q >> shift & 255)); sum += d; worst = Math.max(worst, d);
            }
            if (worst > 48) differing++;
        }
        return new double[]{sum / (pixels * 3.0), differing / (double) pixels};
    }

    private static String perFrame(long[] nanos, long frames) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < nanos.length; i++) out.append(i == 0 ? "" : ", ").append(String.format("%.1f", nanos[i] / 1e3 / Math.max(1, frames)));
        return out.append(']').toString();
    }
    private static void log(String message) throws java.io.IOException {
        VulkanForge.LOGGER.info("GL 4.6 smoke: {}", message);
        Files.writeString(report.resolve("report.txt"), message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
