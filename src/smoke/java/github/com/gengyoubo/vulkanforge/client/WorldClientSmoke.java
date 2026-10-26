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
    private static long lightBaseline, glyphBaseline, movingBuilds;
    private static int originalWidth, originalHeight, swapchainBaseline, initialCoverage;
    private static long minimizedFrames, screenBaseline, fallbackBaseline;
    private static long bridgeLaunches;
    private static final long START = System.nanoTime();
    private static Path report;

    @SubscribeEvent public static void glyphs(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        if (Boolean.getBoolean("vulkanforge.worldSmoke") && (stage == 40 || stage == 41) && ticks > 10) {
            event.getGuiGraphics().drawString(Minecraft.getInstance().font, "P11 动态字形 Ω Ж あ 漢", 8, 8, -1);
            if (stage == 40 && ticks == 20 && GuiDraws.active()) {
                event.getGuiGraphics().flush();
                var previous = com.mojang.blaze3d.systems.RenderSystem.getShader();
                com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
                var buffer = new com.mojang.blaze3d.vertex.BufferBuilder(256);
                buffer.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLE_STRIP, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
                buffer.vertex(8, 40, 0).color(255,0,255,255).endVertex(); buffer.vertex(8, 48, 0).color(255,0,255,255).endVertex();
                buffer.vertex(32, 40, 0).color(255,0,255,255).endVertex(); buffer.vertex(32, 48, 0).color(255,0,255,255).endVertex();
                EntityDraws.drawImmediate(buffer.end()); // Unsupported topology: replay hand + pending GUI on GL, then continue in order.
                com.mojang.blaze3d.systems.RenderSystem.setShader(() -> previous);
                NativeMainWindow.captureNext(report.resolve("gui-fallback-swapchain.png"));
            }
        }
    }

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
                mc.options.renderDistance().set(4); // clouds are off below 4 (Options.getCloudsType)
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY); // P10: two-pass clouds (depth prepass + color)
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
                    // P6: a walled water pool (fluid, translucent layer) and a stained-glass panel in front of the camera.
                    for (int x = -8; x <= -4; x++) for (int z = 4; z <= 8; z++) level.setBlock(new BlockPos(x, 65, z),
                            x == -8 || x == -4 || z == 4 || z == 8 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState(), 3);
                    for (int x = -1; x <= 1; x++) for (int y = 65; y <= 66; y++) level.setBlock(new BlockPos(x, y, 7), Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), 3);
                    // Beyond the former radius=1 capture cube, but inside the actual vanilla render distance.
                    for (int y = 64; y <= 73; y++) level.setBlock(new BlockPos(24, y, -40), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                    for (int y = 64; y <= 73; y++) level.setBlock(new BlockPos(-48, y, -72), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                    // P7/P8: block entities (chest, sign) and a mob without AI in front of the camera.
                    level.setBlock(new BlockPos(3, 65, 6), Blocks.CHEST.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(2, 65, 7), Blocks.OAK_SIGN.defaultBlockState(), 3);
                    var cow = net.minecraft.world.entity.EntityType.COW.spawn(level, new BlockPos(-3, 65, 6), net.minecraft.world.entity.MobSpawnType.COMMAND);
                    if (cow != null) { cow.setNoAi(true); cow.setYRot(30); }
                    // P9: a lit campfire and a torch keep emitting particles; rain is switched off again after the P9 check.
                    level.setBlock(new BlockPos(1, 65, 5), Blocks.CAMPFIRE.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(4, 65, 4), Blocks.TORCH.defaultBlockState(), 3);
                    level.setWeatherParameters(0, 6000, true, false);
                    var player = server.getPlayerList().getPlayer(uuid);
                    // P11: a held item and a hotbar icon.
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
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
            } else if (stage == 3 && ++ticks > 100 && VulkanPreview.worldEpoch() > 0 && VulkanPreview.capturedSections() >= 20
                    && (!NativeMainWindow.active() || VulkanPreview.replacesSection(new BlockPos(16, 64, -48)))) {
                if (NativeMainWindow.active()) {
                    long[] stats = VulkanPreview.directWorldStats();
                    require(VulkanPreview.directWorld() && stats[2] >= 20 && stats[7] > 0, "WorldScene is not drawing native section meshes through the main backend");
                    initialCoverage = VulkanPreview.capturedSections();
                    require(VulkanPreview.replacesSection(new BlockPos(0, -64, 0)), "Full vertical world coverage is missing");
                    long[] nativeMeshes = VulkanPreview.nativeMeshStats();
                    require(nativeMeshes[0] > 0 && nativeMeshes[1] > 0, "Vanilla section meshes were not reused");
                    require(nativeMeshes[5] == 9L * 9 * mc.level.getSectionsCount(), "Coverage does not match vanilla ViewArea height/distance");
                    log("PASS native mesh reuse and distant section coverage: " + java.util.Arrays.toString(nativeMeshes));
                    require(stats[8] == ProcessHandle.current().pid() && OffscreenBridge.launches() == bridgeLaunches, "Terrain still runs in a subprocess");
                    require(stats[4] == mc.getMainRenderTarget().viewWidth && stats[5] == mc.getMainRenderTarget().viewHeight, "Shared terrain target does not match the main target");
                    log("PASS same-process WorldScene, sections=" + stats[2] + ", pid=" + stats[8] + ", no terrain subprocess / FrameProtocol RGBA");
                    long[] passes = TerrainPasses.stats();
                    require(passes[0] > 0 && passes[1] > 0, "Vanilla Solid/Cutout sections were not replaced");
                    log("PASS P4 vanilla terrain skipped: solid=" + passes[0] + ", cutout=" + passes[1] + ", fallback=" + passes[2]);
                    require(VulkanPreview.translucentMeshes() > 0 && VulkanPreview.translucentQuads() > 0 && stats[15] > 0, "Water/stained glass were not captured as Vulkan translucent geometry");
                    require(stats[13] > 0 && stats[14] > 0 && stats[16] > 0 && stats[17] > 0, "Vulkan translucent pass did not draw sorted sections");
                    require(passes[3] > 0 && VulkanPreview.translucentComposites() > 0, "Vanilla translucent sections were not replaced by the Vulkan composite");
                    log("PASS P6 translucent terrain: native translucent meshes=" + VulkanPreview.translucentMeshes() + ", quads=" + VulkanPreview.translucentQuads() + ", sections=" + stats[15]
                            + ", sorts=" + stats[17] + ", vanilla translucent skipped=" + passes[3] + ", composites=" + VulkanPreview.translucentComposites());
                    long[] entities = EntityDraws.stats(), mirrors = EntityTextures.stats();
                    require(entities[0] > 0 && entities[4] > 0 && mirrors[1] > 0, "No entity/block-entity draws were replayed by Vulkan: " + java.util.Arrays.toString(entities));
                    require(stats[18] > 0 && stats[19] > 0 && stats[23] == 0, "Vulkan entity pass did not draw, or drew with missing textures: " + java.util.Arrays.toString(stats));
                    log("PASS P7/P8 entity pass: captured=" + entities[0] + ", GL fallback unsupported=" + entities[1] + " state=" + entities[2] + " texture=" + entities[3]
                            + ", composites=" + entities[4] + ", Vulkan draws=" + stats[19] + ", mirrored textures=" + mirrors[1] + "/" + mirrors[0]);
                    log("INFO GL fallback states: " + EntityDraws.fallbackStates());
                    long[] particles = EntityDraws.stats(EntityDraws.Window.PARTICLES), weather = EntityDraws.stats(EntityDraws.Window.WEATHER);
                    require(particles[0] > 0 && particles[1] > 0, "Particles were not replayed by Vulkan: " + java.util.Arrays.toString(particles));
                    require(weather[0] > 0 && weather[1] > 0, "Rain was not replayed by Vulkan: " + java.util.Arrays.toString(weather));
                    log("PASS P9 particles and weather: particle draws=" + particles[0] + " composites=" + particles[1] + ", rain/snow draws=" + weather[0]
                            + " composites=" + weather[1] + ", overlay passes=" + stats[18]);
                    long[] skyWindow = EntityDraws.stats(EntityDraws.Window.SKY);
                    var shaders = EntityDraws.capturedShaders();
                    require(skyWindow[0] > 0 && skyWindow[1] > 0 && stats[24] > 0, "Sky was not drawn by the terrain submit: " + java.util.Arrays.toString(skyWindow) + " skyDraws=" + stats[24]);
                    require(shaders.getOrDefault("position", 0L) > 0 && shaders.getOrDefault("position_tex", 0L) > 0, "Sky disc or sun/moon not captured: " + shaders);
                    require(shaders.getOrDefault("position_tex_color_normal", 0L) > 0, "Clouds not captured: " + shaders);
                    log("PASS P10 sky and clouds: sky draws=" + skyWindow[0] + " in " + skyWindow[1] + " terrain submits (Vulkan sky draws=" + stats[24]
                            + "), clouds=" + shaders.get("position_tex_color_normal"));
                    long[] optimized = NativeMainWindow.optimizationStats(), uploads = NativeMainWindow.uploadStats();
                    require(optimized[0] > 0 && optimized[2] > 0, "GPU indirect count / device-local arena was not exercised");
                    require(uploads[0] > 0 && uploads[4] == 1, "Timeline upload queue was not exercised");
                    log("PASS GPU driven: indirect batches=" + optimized[0] + ", arena uploads=" + optimized[2] + ", grows=" + optimized[3]);
                    log("PASS async uploads: submitted=" + uploads[0] + ", bytes=" + uploads[2] + ", backpressure waits=" + uploads[3]);
                    long[] hand = EntityDraws.stats(EntityDraws.Window.HAND), hud = GuiDraws.stats();
                    require(hand[0] > 0 && hand[1] > 0, "Held item / arm was not replayed by Vulkan: " + java.util.Arrays.toString(hand));
                    require(NativeMainWindow.handPasses() > 0, "Hand is still composited through GL");
                    require(hud[0] > 0 && hud[1] > 0 && NativeMainWindow.guiPasses() > 0, "HUD was not replayed by Vulkan: " + java.util.Arrays.toString(hud) + " reasons=" + GuiDraws.fallbackReasons());
                    log("PASS P11 hand and HUD: hand draws=" + hand[0] + " composites=" + hand[1] + ", GUI draws=" + hud[0] + " swapchain passes=" + NativeMainWindow.guiPasses()
                            + ", order-preserving splits=" + EntityDraws.stats()[5]);
                    log("INFO captured shaders: " + shaders);
                    log("INFO GL fallback shaders: " + EntityDraws.fallbackShaders());
                    var clear = mc.getSingleplayerServer();
                    clear.execute(() -> clear.overworld().setWeatherParameters(6000, 0, false, false));
                }
                firstHash = VulkanPreview.saveWorldFrame(report.resolve("before.png"));
                if (NativeMainWindow.active()) NativeMainWindow.captureNext(report.resolve("main-swapchain.png"));
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, "world-smoke-main.png", mc.getMainRenderTarget(), message -> { });
                firstEpoch = VulkanPreview.worldEpoch();
                log("PASS real block atlas and section capture, epoch=" + firstEpoch + ", sections=" + VulkanPreview.capturedSections());
                require(VulkanPreview.capturedCutoutQuads() > 0, "No native cutout quads were reused");
                require(VulkanPreview.atlasPatches() > 0 && VulkanPreview.lightUpdates() > 0, "Texture synchronization did not start");
                log("PASS cutout model capture, atlas patches=" + VulkanPreview.atlasPatches() + ", lightmap updates=" + VulkanPreview.lightUpdates());
                var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                server.execute(() -> { var player = server.getPlayerList().getPlayer(uuid); player.teleportTo(server.overworld(), 0.5, 68, 9, 130, 18); });
                if (NativeMainWindow.active()) {
                    // P11: a screen over the world.
                    screenBaseline = GuiDraws.stats()[0]; glyphBaseline = EntityTextures.stats()[5]; fallbackBaseline = GuiDraws.stats()[2];
                    mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                    stage = 40;
                } else stage = 4;
                ticks = 0;
            } else if (stage == 40 && ++ticks > 40) {
                long[] screen = GuiDraws.stats();
                require(screen[0] > screenBaseline && screen[1] > 0, "Inventory screen was not replayed by Vulkan: " + java.util.Arrays.toString(screen));
                require(EntityTextures.stats()[5] > glyphBaseline, "New font glyphs did not produce an incremental texture patch");
                log("PASS P11 incremental glyph mirror, patches=" + EntityTextures.stats()[5]);
                log("PASS P11 inventory screen: draws=" + screen[0] + " direct frames=" + screen[1] + ", splits=" + EntityDraws.stats()[5]
                        + ", GL fallback shaders=" + EntityDraws.fallbackShaders() + ", states=" + EntityDraws.fallbackStates());
                Files.deleteIfExists(report.resolve("inventory-swapchain.png"));
                NativeMainWindow.captureNext(report.resolve("inventory-swapchain.png"));
                stage = 41; ticks = 0;
            } else if (stage == 41 && ++ticks > 5) {
                verifyCapture(report.resolve("inventory-swapchain.png"), mc.getWindow().getWidth(), mc.getWindow().getHeight());
                require(GuiDraws.stats()[2] > fallbackBaseline, "GUI fallback did not replay the deferred hand");
                verifyCapture(report.resolve("gui-fallback-swapchain.png"), mc.getWindow().getWidth(), mc.getWindow().getHeight());
                log("PASS deferred hand + GUI GL fallback order");
                mc.setScreen(null); movingBuilds = VulkanPreview.rebuiltSections(); mc.options.renderDistance().set(6);
                stage = 42; ticks = 0;
            } else if (stage == 42) {
                ticks++;
                if (ticks % 12 == 0 && ticks <= 48) {
                    double x = ticks == 48 ? .5 : ticks * 4 + .5;
                    var server = mc.getSingleplayerServer(); var uuid = mc.player.getUUID();
                    server.execute(() -> server.getPlayerList().getPlayer(uuid).teleportTo(server.overworld(), x, 68, 9, 130, 18));
                }
                if (ticks > 110 && VulkanPreview.replacesSection(new BlockPos(-48, 64, -80))) {
                    require(VulkanPreview.rebuiltSections() > movingBuilds, "Movement did not schedule new section captures");
                    long[] schedule = VulkanPreview.schedulerStats();
                    require(schedule[5] > 1, "Native mesh publication is still throttled to one per frame");
                    require(VulkanPreview.nativeMeshStats()[5] == 13L * 13 * mc.level.getSectionsCount(), "Render distance increase did not expand native coverage");
                    log("PASS fast movement rebuild scheduling: " + java.util.Arrays.toString(schedule));
                    log("PASS render distance increase: sections=" + VulkanPreview.capturedSections());
                    mc.options.renderDistance().set(4);
                    stage = 4; ticks = 0;
                }
            } else if (stage == 4 && ++ticks > 60) {
                require(!VulkanPreview.replacesSection(new BlockPos(-48, 64, -80)), "Render distance decrease retained an out-of-range section");
                require(VulkanPreview.nativeMeshStats()[5] == 9L * 9 * mc.level.getSectionsCount(), "Render distance decrease did not shrink native coverage");
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
            } else if (stage == 22 && ++ticks > 60 && VulkanPreview.worldEpoch() > 0 && VulkanPreview.capturedSections() > 0) {
                require(VulkanPreview.directWorldStats()[2] == VulkanPreview.capturedSections() && OffscreenBridge.launches() == bridgeLaunches, "F8 reopen did not restore same-process sections");
                log("PASS F8 shared world release/reopen, sections=" + VulkanPreview.capturedSections());
                leaveWorld(mc);
            } else if (stage == 18 && mc.level == null && mc.screen instanceof TitleScreen && ++ticks > 20) {
                require(VulkanPreview.worldEpoch() == 0, "Vulkan world state survived leaving the world");
                require(VulkanPreview.nativeMeshStats()[4] == 0 && VulkanPreview.nativeMeshStats()[5] == 0, "Native mesh cache retained the departed world");
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
            log("PASS memory pool: " + java.util.Arrays.toString(NativeMainWindow.memoryStats()));
            require(NativeMainWindow.guiBindingStats()[2] < 128, "Lightmap updates repeatedly invalidated GUI uniform descriptors");
            log("PASS GUI descriptor/batch counters: " + java.util.Arrays.toString(NativeMainWindow.guiBindingStats()));
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
