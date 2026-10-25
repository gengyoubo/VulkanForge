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
    private static int originalWidth, originalHeight, swapchainBaseline;
    private static long minimizedFrames;
    private static long bridgeLaunches;
    private static final long START = System.nanoTime();
    private static Path report;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("vulkanforge.worldSmoke") || event.phase != TickEvent.Phase.END || stage == 99) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - START) > 300) throw new IllegalStateException("World client smoke timed out, stage=" + stage);
            if (stage == 0 && mc.screen instanceof TitleScreen) {
                report = mc.gameDirectory.toPath().resolve("logs/vulkanforge/world-client-smoke");
                Files.createDirectories(report);
                Files.writeString(report.resolve("report.txt"), "");
                Files.deleteIfExists(report.resolve("main-swapchain.png"));
                Files.deleteIfExists(report.resolve("resized-swapchain.png"));
                for (String png : new String[]{"restored", "fullscreen", "windowed"}) Files.deleteIfExists(report.resolve(png + "-swapchain.png"));
                if (System.getProperty("vulkanforge.directPresent", "true").equalsIgnoreCase("true") && System.getProperty("os.name").startsWith("Windows")) {
                    require(NativeMainWindow.active(), "Vulkan main-window initialization fell back to OpenGL");
                    require(org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_CLIENT_API) == org.lwjgl.glfw.GLFW.GLFW_NO_API,
                            "Main window still has an OpenGL context");
                    require(org.lwjgl.glfw.GLFW.glfwGetCurrentContext() != mc.getWindow().getWindow(), "Legacy GL context must belong to the hidden window");
                    require(NativeMainWindow.presentedFrames() > 0, "No frames presented to main swapchain");
                    require(NativeMainWindow.acquiresBeforeInput() > 0, "No image acquisition before input sampling");
                    log("PASS ASM hooks, GLFW_NO_API main window, hidden GL context and Vulkan presentation");
                }
                stage = 1;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(3);
                mc.options.graphicsMode().set(net.minecraft.client.GraphicsStatus.FANCY);
                mc.options.gamma().set(0.0);
                createWorld(mc, "VulkanForge Smoke ");
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
                if (NativeMainWindow.active() && mc.mouseHandler.isMouseGrabbed()) {
                    long window = mc.getWindow().getWindow();
                    require(org.lwjgl.glfw.GLFW.glfwGetInputMode(window, org.lwjgl.glfw.GLFW.GLFW_CURSOR) == org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED,
                            "Cursor capture targets a different window");
                    if (mc.options.rawMouseInput().get() && org.lwjgl.glfw.GLFW.glfwRawMouseMotionSupported()) require(
                            org.lwjgl.glfw.GLFW.glfwGetInputMode(window, org.lwjgl.glfw.GLFW.GLFW_RAW_MOUSE_MOTION) == org.lwjgl.glfw.GLFW.GLFW_TRUE,
                            "Raw mouse input is not bound to the visible main window");
                    log("PASS vanilla cursor/raw mouse capture belongs to the visible main window");
                }
                bridgeLaunches = OffscreenBridge.launches();
                VulkanPreview.toggle(); stage = 3; ticks = 0;
            } else if (stage == 3 && ++ticks > 100 && VulkanPreview.worldEpoch() > 0 && VulkanPreview.capturedSections() >= (NativeMainWindow.active() ? 45 : 10)) {
                if (NativeMainWindow.active()) {
                    long[] stats = VulkanPreview.directWorldStats();
                    require(VulkanPreview.directWorld() && stats[2] == 45 && stats[7] > 0, "WorldScene is not drawing 45 sections through the main backend");
                    require(stats[8] == ProcessHandle.current().pid() && OffscreenBridge.launches() == bridgeLaunches, "Terrain still runs in a subprocess");
                    require(stats[4] == mc.getMainRenderTarget().viewWidth && stats[5] == mc.getMainRenderTarget().viewHeight, "Shared terrain target does not match the main target");
                    log("PASS same-process WorldScene, sections=" + stats[2] + ", pid=" + stats[8] + ", no terrain subprocess / FrameProtocol RGBA");
                    long[] passes = TerrainPasses.stats();
                    require(passes[0] > 0 && passes[1] > 0, "Vanilla Solid/Cutout sections were not replaced");
                    log("PASS P4 vanilla terrain skipped: solid=" + passes[0] + ", cutout=" + passes[1] + ", fallback=" + passes[2]);
                }
                firstHash = VulkanPreview.saveWorldFrame(report.resolve("before.png"));
                if (NativeMainWindow.active()) NativeMainWindow.captureNext(report.resolve("main-swapchain.png"));
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
                if (NativeMainWindow.active()) {
                    originalWidth = mc.getWindow().getScreenWidth(); originalHeight = mc.getWindow().getScreenHeight();
                    swapchainBaseline = NativeMainWindow.swapchainGenerations();
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), originalWidth == 1000 ? 900 : 1000, 650);
                    stage = 9; ticks = 0;
                } else leaveWorld(mc);
            } else if (stage == 9 && ++ticks > 40) {
                require(NativeMainWindow.swapchainGenerations() > swapchainBaseline, "Main swapchain did not rebuild after resize");
                verifyCapture(report.resolve("main-swapchain.png"), originalWidth, originalHeight);
                log("PASS main-window resize and swapchain capture, frames=" + NativeMainWindow.presentedFrames());
                NativeMainWindow.captureNext(report.resolve("resized-swapchain.png"));
                stage = 12; ticks = 0;
            } else if (stage == 12 && ++ticks > 10) {
                verifyCapture(report.resolve("resized-swapchain.png"), originalWidth == 1000 ? 900 : 1000, 650);
                org.lwjgl.glfw.GLFW.glfwIconifyWindow(mc.getWindow().getWindow());
                stage = 10; ticks = 0; minimizedFrames = NativeMainWindow.presentedFrames();
            } else if (stage == 10 && ++ticks > 20) {
                require(NativeMainWindow.presentedFrames() == minimizedFrames, "Vulkan should skip minimized-window presentation");
                org.lwjgl.glfw.GLFW.glfwRestoreWindow(mc.getWindow().getWindow());
                org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), originalWidth, originalHeight);
                stage = 11; ticks = 0;
            } else if (stage == 11 && ++ticks > 40) {
                require(NativeMainWindow.presentedFrames() > minimizedFrames, "Presentation did not resume after restoring window");
                log("PASS minimize/restore; Vulkan presented frames=" + NativeMainWindow.presentedFrames());
                NativeMainWindow.captureNext(report.resolve("restored-swapchain.png"));
                stage = 13; ticks = 0;
            } else if (stage == 13 && ++ticks > 10) {
                verifyCapture(report.resolve("restored-swapchain.png"), originalWidth, originalHeight);
                log("PASS non-black swapchain pixels before resize, after resize and after restore");
                // Same path as F11; Window.updateDisplay applies it on the next frame.
                swapchainBaseline = NativeMainWindow.swapchainGenerations();
                mc.getWindow().toggleFullScreen();
                stage = 14; ticks = 0;
            } else if (stage == 14 && ++ticks > 40) {
                require(mc.getWindow().isFullscreen() && NativeMainWindow.swapchainGenerations() > swapchainBaseline, "Fullscreen did not rebuild the main swapchain");
                NativeMainWindow.captureNext(report.resolve("fullscreen-swapchain.png"));
                stage = 15; ticks = 0;
            } else if (stage == 15 && ++ticks > 10) {
                verifyCapture(report.resolve("fullscreen-swapchain.png"), mc.getWindow().getWidth(), mc.getWindow().getHeight());
                log("PASS fullscreen " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
                swapchainBaseline = NativeMainWindow.swapchainGenerations();
                mc.getWindow().toggleFullScreen();
                stage = 16; ticks = 0;
            } else if (stage == 16 && ++ticks > 40) {
                require(!mc.getWindow().isFullscreen() && NativeMainWindow.swapchainGenerations() > swapchainBaseline, "Leaving fullscreen did not rebuild the main swapchain");
                NativeMainWindow.captureNext(report.resolve("windowed-swapchain.png"));
                stage = 17; ticks = 0;
            } else if (stage == 17 && ++ticks > 10) {
                verifyCapture(report.resolve("windowed-swapchain.png"), mc.getWindow().getWidth(), mc.getWindow().getHeight());
                log("PASS fullscreen round trip, swapchain generations=" + NativeMainWindow.swapchainGenerations());
                VulkanPreview.toggleInset();
                NativeMainWindow.captureNext(report.resolve("inset-swapchain.png"));
                stage = 20; ticks = 0;
            } else if (stage == 20 && ++ticks > 10) {
                verifyCapture(report.resolve("inset-swapchain.png"), mc.getWindow().getWidth(), mc.getWindow().getHeight());
                log("PASS shared terrain sampling through GUI inset");
                VulkanPreview.toggleInset();
                VulkanPreview.toggle();
                require(!VulkanPreview.directWorld() && NativeMainWindow.worldStats()[1] == 0, "F8 did not release shared world resources");
                stage = 21; ticks = 0;
            } else if (stage == 21 && ++ticks > 10) {
                VulkanPreview.toggle();
                stage = 22; ticks = 0;
            } else if (stage == 22 && ++ticks > 60 && VulkanPreview.worldEpoch() > 0 && VulkanPreview.capturedSections() == 45) {
                require(VulkanPreview.directWorldStats()[2] == 45 && OffscreenBridge.launches() == bridgeLaunches, "F8 reopen did not restore same-process sections");
                log("PASS F8 shared world release/reopen, sections=45");
                leaveWorld(mc);
            } else if (stage == 18 && mc.level == null && mc.screen instanceof TitleScreen && ++ticks > 20) {
                require(VulkanPreview.worldEpoch() == 0, "Vulkan world state survived leaving the world");
                long frames = NativeMainWindow.presentedFrames();
                createWorld(mc, "VulkanForge Smoke Switch ");
                minimizedFrames = frames;
                stage = 19; ticks = 0;
            } else if (stage == 19 && mc.player != null && mc.level != null && mc.screen == null && ++ticks > 100
                    && VulkanPreview.worldEpoch() > firstEpoch && VulkanPreview.capturedSections() >= 10) {
                VulkanPreview.saveWorldFrame(report.resolve("switched-world.png"));
                require(!NativeMainWindow.active() || NativeMainWindow.presentedFrames() > minimizedFrames, "Main window stopped presenting across the world switch");
                log("PASS world switch, epoch " + firstEpoch + " -> " + VulkanPreview.worldEpoch() + ", sections=" + VulkanPreview.capturedSections());
                finish(mc);
            }
        } catch (Throwable error) {
            VulkanForge.LOGGER.error("World client smoke failed at stage " + stage, error);
            try { log("STATUS=FAILED " + error); } catch (Exception ignored) { }
            stage = 99; mc.stop();
        }
    }

    private static void createWorld(Minecraft mc, String prefix) throws java.io.IOException {
        String name = prefix + System.currentTimeMillis();
        mc.createWorldOpenFlows().createFreshLevel(name,
                new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(1234L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
        log("Created isolated flat test world: " + name);
    }
    private static void leaveWorld(Minecraft mc) {
        firstEpoch = VulkanPreview.worldEpoch();
        // Same sequence as the pause screen's "Save and Quit to Title".
        mc.level.disconnect();
        mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
        stage = 18; ticks = 0;
    }
    private static void finish(Minecraft mc) throws java.io.IOException {
        if (NativeMainWindow.active()) {
            require(VulkanPreview.directWorld() && OffscreenBridge.launches() == bridgeLaunches, "Terrain switched back to the RGBA subprocess");
            require(NativeMainWindow.acquiresBeforeInput() > NativeMainWindow.presentedFrames() * .9,
                    "Most acquisitions still happen after camera rendering");
            log("PASS acquire before input=" + NativeMainWindow.acquiresBeforeInput() + ", fallback acquire after render=" + NativeMainWindow.acquiresAfterRender());
        }
        log("STATUS=OK"); stage = 99;
        // Teardown of the swapchain, shared GL images and bridge must not hang; build.gradle fails on this marker.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(30_000); Files.writeString(report.resolve("report.txt"), "EXIT_TIMEOUT" + System.lineSeparator(), StandardOpenOption.APPEND); }
            catch (Exception ignored) { }
            Runtime.getRuntime().halt(3);
        }, "VulkanForge-smoke-exit-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        mc.stop();
    }
    private static void log(String message) throws java.io.IOException {
        VulkanForge.LOGGER.info("World client smoke: {}", message);
        Files.writeString(report.resolve("report.txt"), message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void verifyCapture(Path path, int width, int height) throws java.io.IOException {
        var image = javax.imageio.ImageIO.read(path.toFile());
        require(image != null && image.getWidth() == width && image.getHeight() == height, "Wrong swapchain screenshot extent: " + path);
        int visible = 0; var colors = new java.util.HashSet<Integer>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int color = image.getRGB(x, y) & 0xffffff; colors.add(color);
            if (((color >> 16) & 255) + ((color >> 8) & 255) + (color & 255) > 24) visible++;
        }
        require(visible > width * height / 10 && colors.size() > 64, "Swapchain screenshot is blank or lacks game content: " + path);
    }
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
