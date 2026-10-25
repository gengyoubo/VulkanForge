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

    private VulkanPreview() { }

    static void toggle() {
        RenderSystem.assertOnRenderThread();
        if (enabled) close();
        else {
            enabled = true;
            uploaded = lastLogged = 0;
            bridge = new OffscreenBridge();
        }
    }

    static void render(GuiGraphics graphics) {
        if (!enabled || bridge == null) return;
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        OffscreenBridge.Status status = bridge.status();
        OffscreenBridge.Frame frame = bridge.take();
        if (frame != null) {
            try {
                if (texture == null) {
                    texture = new FrameTexture();
                    minecraft.getTextureManager().register(TEXTURE, texture);
                }
                texture.upload(frame.rgba());
                uploaded = frame.sequence();
                if (lastLogged == 0 || uploaded - lastLogged >= 300) {
                    VulkanForge.LOGGER.info("Vulkan preview frame {} uploaded to main window", uploaded);
                    lastLogged = uploaded;
                }
            } catch (RuntimeException e) {
                VulkanForge.LOGGER.error("Could not upload Vulkan preview texture", e);
                close();
                return;
            } finally { if (bridge != null) bridge.recycle(frame); }
        }
        int width = Math.min(Math.max(1, graphics.guiWidth() - 16), Math.min(220, Math.max(120, graphics.guiWidth() / 3)));
        int height = Math.max(1, width * FrameProtocol.HEIGHT / FrameProtocol.WIDTH);
        int x = graphics.guiWidth() - width - 8, y = 8;
        graphics.fill(x - 2, y - 2, x + width + 2, y + height + 26, 0xe6111828);
        if (uploaded > 0 && texture != null) {
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
        uploaded = 0;
    }
}
