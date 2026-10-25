package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import github.com.gengyoubo.vulkanforge.VulkanForge;
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
    private static long uploaded;
    private static long lastLogged;
    private static long uploadedEpoch;
    private static boolean inset;
    private static final WorldCapture WORLD = new WorldCapture();
    private static final TerrainPresenter PRESENTER = new TerrainPresenter();

    private VulkanPreview() { }

    static void toggle() {
        RenderSystem.assertOnRenderThread();
        if (enabled) close();
        else {
            enabled = true;
            uploaded = lastLogged = 0;
            bridge = new OffscreenBridge();
            WORLD.invalidate();
        }
    }

    static void terrain(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (!enabled || bridge == null) return;
        try {
            WORLD.update(event, bridge);
            upload();
            if (!inset && bridge.status() == OffscreenBridge.Status.RUNNING && uploadedEpoch != 0 && uploadedEpoch == WORLD.epoch() && texture != null) PRESENTER.draw(texture);
        }
        catch (Exception error) {
            VulkanForge.LOGGER.error("Could not capture solid terrain for Vulkan", error);
            close();
        }
    }

    static void invalidateWorld() { WORLD.invalidate(); }
    static void toggleInset() { inset = !inset; }
    static long worldEpoch() { return uploadedEpoch == WORLD.epoch() ? uploadedEpoch : 0; }
    static int capturedSections() { return WORLD.sections(); }
    static long atlasPatches() { return WORLD.atlasPatches(); }
    static long lightUpdates() { return WORLD.lightUpdates(); }
    static int capturedCutoutBlocks() { return WORLD.cutoutBlocks(); }
    static int saveWorldFrame(java.nio.file.Path path) throws java.io.IOException {
        RenderSystem.assertOnRenderThread();
        if (texture == null || worldEpoch() == 0) throw new IllegalStateException("No Vulkan world frame available");
        int previous = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        try (var image = new com.mojang.blaze3d.platform.NativeImage(FrameProtocol.WIDTH, FrameProtocol.HEIGHT, false)) {
            texture.bind(); image.downloadTexture(0, false); image.writeToFile(path);
            return java.util.Arrays.hashCode(image.getPixelsRGBA());
        } finally { com.mojang.blaze3d.platform.GlStateManager._bindTexture(previous); }
    }

    private static void upload() {
        if (Minecraft.getInstance().level == null) WORLD.leave(bridge);
        OffscreenBridge.Frame frame = bridge.take();
        if (frame == null) return;
        try {
            if (frame.epoch() != WORLD.epoch()) return;
            if (texture == null) {
                texture = new FrameTexture();
                Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
            }
            texture.upload(frame.rgba());
            uploaded = frame.sequence();
            uploadedEpoch = frame.epoch();
            if (lastLogged == 0 || uploaded - lastLogged >= 300) {
                VulkanForge.LOGGER.info("Vulkan main window: frame={}, worldEpoch={}, capturedSections={}", uploaded, uploadedEpoch, WORLD.sections());
                lastLogged = uploaded;
            }
        } finally { bridge.recycle(frame); }
    }

    static void render(GuiGraphics graphics) {
        if (!enabled || bridge == null) return;
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        OffscreenBridge.Status status = bridge.status();
        try { upload(); }
        catch (RuntimeException error) { VulkanForge.LOGGER.error("Vulkan texture upload failed", error); close(); return; }
        if (status == OffscreenBridge.Status.RUNNING && minecraft.level != null && !inset && uploadedEpoch != 0 && uploadedEpoch == WORLD.epoch()) {
            graphics.fill(4, 4, 222, 27, 0xc0111828);
            graphics.drawString(minecraft.font, Component.translatable("vulkanforge.world.status", WORLD.sections(), uploaded), 7, 7, 0xffaaddff);
            graphics.drawString(minecraft.font, Component.translatable("vulkanforge.world.controls"), 7, 17, 0xffa0aabb);
            return;
        }
        int width = Math.min(Math.max(1, graphics.guiWidth() - 16), Math.min(220, Math.max(120, graphics.guiWidth() / 3)));
        int height = Math.max(1, width * FrameProtocol.HEIGHT / FrameProtocol.WIDTH);
        int x = graphics.guiWidth() - width - 8, y = 8;
        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 26, 0xe6111828);
        if (uploaded > 0 && texture != null && uploadedEpoch == WORLD.epoch()) {
            graphics.blit(TEXTURE, x, y, width, height, 0, 0, FrameProtocol.WIDTH, FrameProtocol.HEIGHT, FrameProtocol.WIDTH, FrameProtocol.HEIGHT);
        }
        Component label = switch (status) {
            case STARTING -> Component.translatable("vulkanforge.preview.starting");
            case RUNNING -> Component.translatable("vulkanforge.preview.running", uploaded);
            case FAILED -> Component.translatable("vulkanforge.preview.failed");
            case STOPPED -> Component.translatable("vulkanforge.preview.stopped");
        };
        graphics.drawString(minecraft.font, label, x + 3, y + height + 3, status == OffscreenBridge.Status.FAILED ? 0xffff8888 : 0xffaaddff);
        graphics.drawString(minecraft.font, Component.translatable("vulkanforge.preview.transport"), x + 3, y + height + 14, 0xffa0aabb);
    }

    static void close() {
        RenderSystem.assertOnRenderThread();
        enabled = false;
        if (bridge != null) { bridge.close(); bridge = null; }
        if (texture != null) { Minecraft.getInstance().getTextureManager().release(TEXTURE); texture.close(); texture = null; }
        PRESENTER.close();
        uploaded = 0;
        uploadedEpoch = 0;
        WORLD.invalidate();
    }
}
