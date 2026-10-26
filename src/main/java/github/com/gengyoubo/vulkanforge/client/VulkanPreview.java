package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig;
import java.util.Map;
import dev.vulkanforge.bridge.FrameProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class VulkanPreview {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(VulkanForge.MOD_ID, "preview");
    private static boolean enabled;
    private static OffscreenBridge bridge;
    private static FrameTexture texture;
    private static SharedFrameTexture sharedTexture;
    private static WorldSink sink;
    private static boolean direct;
    private static int sharedDepth, sharedTranslucent, sharedTranslucentDepth;
    /** True between this frame's Vulkan render and its AFTER_TRANSLUCENT composite; the GL lease stays open until swap. */
    private static boolean translucentPending;
    private static long translucentComposites;
    private static int frameWidth = FrameProtocol.WIDTH, frameHeight = FrameProtocol.HEIGHT;
    private static long uploaded;
    private static long lastLogged;
    private static long uploadedEpoch;
    private static boolean inset;
    private static net.minecraftforge.client.event.RenderLevelStageEvent captureEvent;
    private static boolean prepared,preparingAllowed;
    private static final WorldCapture WORLD = new WorldCapture();
    private static final TerrainPresenter PRESENTER = new TerrainPresenter();
    /** OpenGL backend (issue #4): in-context GL 4.6 terrain instead of any Vulkan transport. */
    private static Gl46Terrain gl;
    /** True from this frame's GL opaque draw until AFTER_TRANSLUCENT_BLOCKS; vanilla skips covered sections only then. */
    private static boolean glDrawn;
    /** GL 4.6 entity/block-entity pass; null when it could not be created (terrain still runs). */
    private static Gl46Entities glEntities;

    private VulkanPreview() { }

    static void toggle() {
        RenderSystem.assertOnRenderThread();
        if (enabled) close();
        else if (VulkanForgeConfig.startup().backend() == VulkanForgeConfig.Backend.OPENGL) enableGl();
        else {
            enabled = true;
            uploaded = lastLogged = 0;
            direct = NativeMainWindow.active();
            if (direct) sink = NativeMainWindow::sendWorld;
            else { bridge = new OffscreenBridge(); sink = bridge; }
            VulkanForge.LOGGER.info("Vulkan terrain transport: {}", direct ? "same-process shared GPU image" : "legacy subprocess RGBA fallback");
            WORLD.invalidate();
        }
    }

    private static void enableGl() {
        String reason = Gl46Context.terrainUnsupported();
        if (reason == null) {
            GlStateCache.initialise();
            try { gl = new Gl46Terrain(); }
            catch (RuntimeException error) { VulkanForge.LOGGER.error("Could not create the OpenGL terrain renderer", error); reason = error.getMessage(); }
        }
        if (gl == null) {
            VulkanForge.LOGGER.warn("OpenGL terrain unavailable: {}", reason);
            Gl46Context.report("TERRAIN_UNAVAILABLE=" + reason);
            notifyPlayer(Component.translatable("vulkanforge.gl46.unavailable", VulkanForgeConfig.startup().openGLVersion().label, String.valueOf(reason)));
            return;
        }
        enabled = true; direct = false; sink = gl;
        uploaded = lastLogged = 0;
        if (!Boolean.getBoolean("vulkanforge.gl46.noEntities")) {
            try { glEntities = new Gl46Entities(); }
            catch (RuntimeException error) { VulkanForge.LOGGER.error("OpenGL entity pass unavailable; entities stay on vanilla", error); Gl46Context.report("ENTITY_UNAVAILABLE=" + error); }
        }
        VulkanForge.LOGGER.info("Terrain transport: in-context OpenGL {} ({})", VulkanForgeConfig.startup().openGLVersion().label, GlCompat.paths());
        WORLD.invalidate();
    }
    private static void notifyPlayer(Component message) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(message, false);
    }
    /** First chunk-layer draw of the frame (after compileChunks and terrain fog setup), or AFTER_CUTOUT_BLOCKS as a fallback. */
    private static void glOpaque() {
        glDrawn = false;
        if (captureEvent == null || Minecraft.getInstance().level == null) return;
        try {
            long updateStart = System.nanoTime();
            WORLD.update(captureEvent, sink);
            UPDATE_TIMER.sample(Math.max(1, System.nanoTime() - updateStart));
            // Draw exactly what vanilla's occlusion graph keeps this frame (plus frustum and Hi-Z on top).
            boolean known = !Boolean.getBoolean("vulkanforge.gl46.noVanillaVisibility") && VanillaVisibility.available();
            gl.beginVisibility(known);
            if (known) VanillaVisibility.forEach(Minecraft.getInstance().levelRenderer, gl::markVisible);
            gl.view(captureEvent.getCamera().getYRot(), captureEvent.getCamera().getXRot(), captureEvent.getProjectionMatrix());
            int[] textures = glTextures();
            glDrawn = gl.drawOpaque(textures[0], textures[1]) && gl.epoch() == WORLD.epoch();
            if (glDrawn) { uploadedEpoch = WORLD.epoch(); uploaded = gl.frames(); }
        } catch (RuntimeException error) { glFailed(error); }
    }
    /** Vanilla's own block atlas and lightmap: the GL path never reads textures back. */
    private static int[] glTextures() {
        Minecraft mc = Minecraft.getInstance();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        return new int[]{mc.getTextureManager().getTexture(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).getId(), RenderSystem.getShaderTexture(2)};
    }
    /** BlockEntityMeshes hook; failures disable the entity pass like capture failures. */
    static boolean gl46BlockEntity(net.minecraft.client.renderer.blockentity.BlockEntityRenderer<?> renderer, net.minecraft.world.level.block.entity.BlockEntity entity,
                                   float partial, com.mojang.blaze3d.vertex.PoseStack pose, int light, int overlay) {
        if (glEntities == null) return false;
        try { return glEntities.blockEntity(renderer, entity, partial, pose, light, overlay); }
        catch (RuntimeException error) { glEntitiesFailed(error); return false; }
    }
    static long[] gl46BlockEntityStats() { return glEntities != null ? glEntities.blockEntityStats() : new long[7]; }
    /** EntityDraws hook; an entity-pass failure only disables that pass. */
    static boolean gl46Entity(com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer rendered) {
        if (glEntities == null) return false;
        try { return Gl46Entities.take(rendered); }
        catch (RuntimeException error) { glEntitiesFailed(error); return false; }
    }
    private static void glEntitiesEnd() {
        if (glEntities == null) return;
        try { glEntities.end(); } catch (RuntimeException error) { glEntitiesFailed(error); }
    }
    private static void glEntitiesFailed(RuntimeException error) {
        VulkanForge.LOGGER.error("OpenGL 4.6 entity pass failed; entities return to vanilla until F8 restarts it", error);
        Gl46Context.report("ENTITY_FAILED=" + error);
        Gl46Entities failed = glEntities; glEntities = null;
        failed.close();
    }
    static long[] gl46EntityStats() { return glEntities != null ? glEntities.stats() : new long[7]; }
    static Map<String, Long> gl46EntityFallbacks() { return glEntities != null ? glEntities.fallbacks() : Map.of(); }
    private static void glFailed(RuntimeException error) {
        VulkanForge.LOGGER.error("OpenGL 4.6 terrain failed; vanilla terrain rendering resumes", error);
        Gl46Context.report("TERRAIN_FAILED=" + error);
        notifyPlayer(Component.translatable("vulkanforge.gl46.failed", VulkanForgeConfig.startup().openGLVersion().label));
        close();
    }

    static void terrain(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (!enabled || sink == null) return;
        if (gl != null) {
            if (preparingAllowed && !prepared) glOpaque();
            prepared = false; preparingAllowed = false;
            if (glDrawn) {
                var target = Minecraft.getInstance().getMainRenderTarget();
                try { gl.buildHiZ(target.getDepthTextureId(), target.width, target.height); }
                catch (RuntimeException error) { glFailed(error); return; }
            }
            // Entities and block entities follow; only frames whose terrain ran on GL 4.6 replay them there.
            if (glDrawn && glEntities != null) glEntities.begin();
            return;
        }
        translucentPending = false;
        EntityDraws.cancel();
        // P10: the next frame's sky may be captured only while this frame's terrain composite covers the screen.
        boolean composited = false;
        if (direct && !NativeMainWindow.worldVisible()) { uploadedEpoch = 0; EntityDraws.armSky(false); return; }
        try {
            if(!prepared){WORLD.update(event, sink);if (direct) renderShared(); else upload();}
            prepared=false;preparingAllowed=false;
            if (!inset && uploadedEpoch != 0 && uploadedEpoch == WORLD.epoch()) {
                if (direct && sharedTexture != null) {
                    PRESENTER.draw(sharedTexture.getId(), sharedDepth);
                    composited = true;
                    translucentPending = sharedTranslucent > 0 && sharedTranslucentDepth > 0 && TerrainPresenter.translucentReady()
                            && (!Minecraft.useShaderTransparency() || nativeTerrain()
                            && NativeRenderTargets.shared(Minecraft.getInstance().levelRenderer.getTranslucentTarget()));
                    // P7/P8: entity and block-entity draws until AFTER_BLOCK_ENTITIES go to the Vulkan entity pass when supported.
                    if (translucentPending) { EntityTextures.poll(); EntityDraws.beginFrame(RenderSystem.getShaderTexture(2), PRESENTER); }
                }
                else if (texture != null && bridge.status() == OffscreenBridge.Status.RUNNING) PRESENTER.draw(texture);
            }
        }
        catch (Exception error) {
            VulkanForge.LOGGER.error("Could not capture solid terrain for Vulkan", error);
            close();
        }
        EntityDraws.armSky(composited && enabled);
    }
    /** P11, AFTER_LEVEL: GameRenderer next clears depth and draws the held items, arms and screen effects. */
    static boolean guiEnabled() { return enabled && direct && !inset; }
    static boolean nativeTerrain(){return enabled&&direct&&!inset&&!Boolean.getBoolean("vulkanforge.legacyComposite");}
    static void handBegin() { ordered(GuiDraws::beginHand); }
    /** Before GUI clear or present: composite a still-open hand window into the main target. */
    static void beforeSwap() {
        GuiDraws.finishHand();
        EntityDraws.Window window = EntityDraws.openWindow();
        if (window == null || !window.ordered()) return;
        var target = Minecraft.getInstance().getMainRenderTarget();
        int draw = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        ordered(() -> {
            target.bindWrite(true);
            try { EntityDraws.closeOrdered(window); }
            finally {
                com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, draw);
                com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
            }
        });
    }
    private static void ordered(Runnable action) {
        if (!enabled || !direct) return;
        try { action.run(); }
        catch (Exception error) {
            VulkanForge.LOGGER.error("Vulkan hand/GUI overlay failed; captured draws return to OpenGL until the preview restarts", error);
            EntityDraws.disable();
        }
    }
    /** ViewportEvent.ComputeFogColor: LevelRenderer is about to clear and draw the sky. */
    static void skyBegin() { if (enabled && direct) EntityDraws.openSky(); }
    /** AFTER_SKY: captured sky waits for this frame's terrain submit. */
    static void skyEnd(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        EntityDraws.closeSky();captureEvent=event;prepared=false;preparingAllowed=true;
        long now = System.nanoTime();
        if (lastSky != 0 && enabled && gl != null) FRAME_TIMER.sample(now - lastSky);
        lastSky = now;
    }
    /** GL 4.6 diagnostics: interval between consecutive frames' AFTER_SKY (whole frame), and WorldCapture.update CPU. */
    static final Gl46Timer FRAME_TIMER = new Gl46Timer(), UPDATE_TIMER = new Gl46Timer();
    private static long lastSky;
    static void prepareTerrain(){
        if (gl != null) { if (enabled && preparingAllowed && !prepared) { prepared = true; glOpaque(); } return; }
        if(!preparingAllowed||prepared||!nativeTerrain()||sink==null||captureEvent==null||!NativeMainWindow.worldVisible())return;
        WORLD.update(captureEvent,sink);renderShared();prepared=true;
    }

    /** AFTER_BLOCK_ENTITIES: replay captured entity/block-entity draws in Vulkan and composite them with depth. */
    static void entities() {
        if (gl != null) { glEntitiesEnd(); return; }
        overlay(EntityDraws.Window.ENTITIES, EntityDraws.Window.EFFECTS);
    }
    /** AFTER_TRIPWIRE_BLOCKS: the next thing vanilla draws is ParticleEngine.render (P9). */
    static void particlesBegin() { overlay(EntityDraws.Window.EFFECTS,EntityDraws.Window.PARTICLES); }
    /** AFTER_PARTICLES: composite particles, then capture rain/snow until AFTER_WEATHER. */
    static void particlesEnd() { overlay(EntityDraws.Window.PARTICLES, EntityDraws.Window.WEATHER); }
    static void weather() { overlay(EntityDraws.Window.WEATHER, null); }
    private static void overlay(EntityDraws.Window window, EntityDraws.Window next) {
        try { EntityDraws.flush(window, PRESENTER); if (next != null) EntityDraws.open(next); }
        catch (Exception error) {
            // Captured draws fall back to OpenGL; terrain stays on Vulkan.
            VulkanForge.LOGGER.error("Vulkan overlay pass failed; entities, particles and weather return to OpenGL until the preview restarts", error);
            EntityDraws.disable();
        }
    }

    /** AFTER_TRANSLUCENT_BLOCKS: after GL entities and the (partially skipped) vanilla translucent layer. */
    static void translucent() {
        if (gl != null) {
            glEntitiesEnd(); // normally already closed at AFTER_BLOCK_ENTITIES
            try {
                if (enabled && glDrawn && !Minecraft.useShaderTransparency()) {
                    int[] textures = glTextures();
                    gl.drawTranslucent(textures[0], textures[1]); translucentComposites++;
                }
            } catch (RuntimeException error) { glFailed(error); }
            glDrawn = false;
            return;
        }
        EntityDraws.flushCurrent();
        if (!translucentPending) return;
        translucentPending = false;
        try { PRESENTER.drawTranslucent(sharedTranslucent, sharedTranslucentDepth); translucentComposites++; }
        catch (Exception error) { VulkanForge.LOGGER.error("Could not composite Vulkan translucent terrain", error); close(); }
    }
    /** Fabulous mode renders translucency into its own target for the transparency post chain; leave that to vanilla. */
    static boolean replacesTranslucent(net.minecraft.core.BlockPos origin) {
        if (gl != null) return !Minecraft.useShaderTransparency() && replacesSection(origin);
        return translucentPending && replacesSection(origin);
    }

    static long translucentComposites() { return translucentComposites; }
    static long translucentQuads() { return WORLD.translucentQuads(); }
    static long translucentMeshes() { return WORLD.translucentMeshes(); }
    static void invalidateWorld() { WORLD.invalidate(); if (glEntities != null) glEntities.invalidateBlockEntities(); }
    static void markDirty(int x, int y, int z) { if (enabled) WORLD.markDirty(x, y, z); }
    static void toggleInset() { inset = !inset; }
    static long worldEpoch() { return uploadedEpoch == WORLD.epoch() ? uploadedEpoch : 0; }
    static int capturedSections() { return WORLD.sections(); }
    static long[] schedulerStats() { return WORLD.schedulerStats(); }
    static long[] nativeMeshStats() { return TerrainMeshes.stats(); }
    static long rebuiltSections() { return WORLD.builds(); }
    static long atlasPatches() { return WORLD.atlasPatches(); }
    static long lightUpdates() { return WORLD.lightUpdates(); }
    static int capturedCutoutQuads() { return WORLD.cutoutQuads(); }
    static boolean replacesSection(net.minecraft.core.BlockPos origin) {
        if (gl != null) return enabled && glDrawn && WORLD.covers(origin);
        return enabled && direct && !inset && sharedDepth > 0 && TerrainPresenter.depthReady()
                && uploadedEpoch != 0 && uploadedEpoch == WORLD.epoch() && WORLD.covers(origin);
    }
    static boolean directWorld() { return enabled && direct; }
    static boolean gl46World() { return enabled && gl != null; }
    /** Outside the frame (e.g. from a tick): would the next GL 4.6 frame draw this section instead of vanilla? */
    static boolean gl46Covers(net.minecraft.core.BlockPos origin) {
        return gl46World() && gl.frames() > 0 && uploadedEpoch == WORLD.epoch() && WORLD.covers(origin);
    }
    /** See Gl46Terrain.stats(). */
    /** Per-frame CPU percentiles of each GL 4.6 pass. */
    static String gl46Timers() {
        return "terrainOpaque{" + (gl != null ? gl.opaqueTimer : "-") + "} terrainTranslucent{" + (gl != null ? gl.translucentTimer : "-")
                + "} entityCapture{" + (glEntities != null ? glEntities.captureTimer : "-") + "} entityFlush{" + (glEntities != null ? glEntities.flushTimer : "-") + "}";
    }
    static long[] gl46CullStats() { return gl != null ? gl.cullStats() : new long[9]; }
    static long[] gl46GrowStats() { return gl != null ? gl.growStats() : new long[3]; }
    static long[] gl46SortStats() { return gl != null ? gl.sortStats() : new long[4]; }
    static long[] gl46Phases() { return gl != null ? gl.phases() : new long[5]; }
    static long[] gl46EntityPhases() { return glEntities != null ? glEntities.phases() : new long[4]; }
    static long[] gl46Stats() { return gl != null ? gl.stats() : new long[11]; }
    static long[] directWorldStats() { return directWorld() ? NativeMainWindow.worldStats() : new long[25]; }
    static int saveWorldFrame(java.nio.file.Path path) throws java.io.IOException {
        RenderSystem.assertOnRenderThread();
        // A diagnostic read acquires a fresh GL lease; normal terrain rendering never downloads pixels.
        if (direct) renderShared(false);
        if ((direct ? sharedTexture == null : texture == null) || worldEpoch() == 0) throw new IllegalStateException("No Vulkan world frame available");
        int previous = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        try (var image = new com.mojang.blaze3d.platform.NativeImage(frameWidth, frameHeight, false)) {
            if (direct) sharedTexture.bind(); else texture.bind();
            image.downloadTexture(0, false); image.writeToFile(path);
            return java.util.Arrays.hashCode(image.getPixelsRGBA());
        } finally { com.mojang.blaze3d.platform.GlStateManager._bindTexture(previous); }
    }

    private static void renderShared() {
        renderShared(!inset && !Boolean.getBoolean("vulkanforge.legacyComposite"));
    }
    private static void renderShared(boolean nativeMode) {
        if (!NativeMainWindow.worldVisible()) { uploadedEpoch = 0; return; }
        var target = Minecraft.getInstance().getMainRenderTarget();
        int id = NativeMainWindow.renderWorld(target.viewWidth, target.viewHeight, EntityDraws.takeSky(),nativeMode);
        long[] stats = NativeMainWindow.worldStats();
        if (id == 0) { uploadedEpoch = 0; return; }
        uploadedEpoch = stats[0];
        sharedDepth = (int)stats[9];
        sharedTranslucent = stats.length > 14 ? (int)stats[13] : 0; sharedTranslucentDepth = stats.length > 14 ? (int)stats[14] : 0;
        if (sharedTexture == null) {
            sharedTexture = new SharedFrameTexture();
            Minecraft.getInstance().getTextureManager().register(TEXTURE, sharedTexture);
        }
        sharedTexture.use(id); uploaded = stats[1]; frameWidth = (int)stats[4]; frameHeight = (int)stats[5];
        if (lastLogged == 0 || uploaded - lastLogged >= 300) {
            VulkanForge.LOGGER.info("Vulkan shared world: frame={}, epoch={}, sections={}, target={}x{}", uploaded, uploadedEpoch, stats[2], frameWidth, frameHeight);
            lastLogged = uploaded;
        }
    }

    private static void upload() {
        if (Minecraft.getInstance().level == null) WORLD.leave(sink);
        OffscreenBridge.Frame frame = bridge.take();
        if (frame == null) return;
        try {
            if (frame.epoch() != WORLD.epoch()) return;
            if (texture == null) {
                texture = new FrameTexture();
                Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
            }
            texture.upload(frame.rgba());
            frameWidth = FrameProtocol.WIDTH; frameHeight = FrameProtocol.HEIGHT;
            uploaded = frame.sequence();
            uploadedEpoch = frame.epoch();
            if (lastLogged == 0 || uploaded - lastLogged >= 300) {
                VulkanForge.LOGGER.info("Vulkan main window: frame={}, worldEpoch={}, capturedSections={}", uploaded, uploadedEpoch, WORLD.sections());
                lastLogged = uploaded;
            }
        } finally { bridge.recycle(frame); }
    }

    static void render(GuiGraphics graphics) {
        if (!enabled || sink == null) return;
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        if (gl != null) {
            if (minecraft.level == null) { WORLD.leave(sink); return; }
            graphics.fill(4, 4, 222, 27, 0xc0111828);
            graphics.drawString(minecraft.font, gl.frames() > 0 && uploadedEpoch == WORLD.epoch()
                    ? Component.translatable("vulkanforge.gl46.status", VulkanForgeConfig.startup().openGLVersion().label, gl.sections(), gl.frames())
                    : Component.translatable("vulkanforge.gl46.waiting", VulkanForgeConfig.startup().openGLVersion().label), 7, 7, 0xffaaddff);
            graphics.drawString(minecraft.font, Component.translatable("vulkanforge.gl46.controls"), 7, 17, 0xffa0aabb);
            return;
        }
        try {
            if (direct) {
                if (minecraft.level == null) { WORLD.leave(sink); renderShared(); }
            } else upload();
        }
        catch (RuntimeException error) { VulkanForge.LOGGER.error("Vulkan texture upload failed", error); close(); return; }
        OffscreenBridge.Status status = direct ? (uploaded > 0 ? OffscreenBridge.Status.RUNNING : OffscreenBridge.Status.STARTING) : bridge.status();
        if (status == OffscreenBridge.Status.RUNNING && minecraft.level != null && !inset && uploadedEpoch != 0 && uploadedEpoch == WORLD.epoch()) {
            graphics.fill(4, 4, 222, 27, 0xc0111828);
            graphics.drawString(minecraft.font, Component.translatable("vulkanforge.world.status", WORLD.sections(), uploaded), 7, 7, 0xffaaddff);
            graphics.drawString(minecraft.font, Component.translatable("vulkanforge.world.controls"), 7, 17, 0xffa0aabb);
            return;
        }
        int width = Math.min(Math.max(1, graphics.guiWidth() - 16), Math.min(220, Math.max(120, graphics.guiWidth() / 3)));
        int height = Math.max(1, width * frameHeight / frameWidth);
        int x = graphics.guiWidth() - width - 8, y = 8;
        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 26, 0xe6111828);
        if (uploaded > 0 && (direct ? sharedTexture != null : texture != null) && uploadedEpoch == WORLD.epoch()) {
            graphics.blit(TEXTURE, x, y, width, height, 0, 0, frameWidth, frameHeight, frameWidth, frameHeight);
        }
        Component label = switch (status) {
            case STARTING -> Component.translatable("vulkanforge.preview.starting", VulkanForgeConfig.startup().vulkanVersion().label);
            case RUNNING -> Component.translatable("vulkanforge.preview.running", VulkanForgeConfig.startup().vulkanVersion().label, uploaded);
            case FAILED -> Component.translatable("vulkanforge.preview.failed");
            case STOPPED -> Component.translatable("vulkanforge.preview.stopped");
        };
        graphics.drawString(minecraft.font, label, x + 3, y + height + 3, status == OffscreenBridge.Status.FAILED ? 0xffff8888 : 0xffaaddff);
        graphics.drawString(minecraft.font, Component.translatable(direct ? "vulkanforge.world.controls" : "vulkanforge.preview.transport"), x + 3, y + height + 14, 0xffa0aabb);
    }

    static void close() {
        RenderSystem.assertOnRenderThread();
        enabled = false;
        if (bridge != null) { bridge.close(); bridge = null; }
        if (texture != null) { Minecraft.getInstance().getTextureManager().release(TEXTURE); texture.close(); texture = null; }
        if (sharedTexture != null) { Minecraft.getInstance().getTextureManager().release(TEXTURE); sharedTexture.close(); sharedTexture = null; }
        GuiDraws.reset();
        EntityDraws.reset();
        if (direct) NativeMainWindow.closeWorld();
        if (glEntities != null) { glEntities.close(); glEntities = null; }
        if (gl != null) { gl.close(); gl = null; }
        glDrawn = false;
        EntityTextures.reset();
        PRESENTER.close();
        uploaded = 0;
        uploadedEpoch = 0;
        WORLD.close();
        sink = null; direct = false;
        sharedDepth = sharedTranslucent = sharedTranslucentDepth = 0;
        translucentPending = false;
    }
}
