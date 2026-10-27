package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import java.util.IdentityHashMap;
import java.util.Map;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** RenderTarget keeps its framebuffer and public texture IDs; Vulkan owns their shared storage. */
public final class NativeRenderTargets {
    private static final Map<RenderTarget, Integer> targets = new IdentityHashMap<>();
    private NativeRenderTargets() { }

    /** Called before RenderTarget.checkStatus, after MainTarget and TextureTarget have allocated their attachments. */
    public static void prepare(RenderTarget target) {
        if (!NativeMainWindow.active() || NativeMainWindow.glTailCompatibility() || Boolean.getBoolean("vulkanforge.legacyTargets") || targets.containsKey(target)
                || !target.useDepth || target.isStencilEnabled() || target.width <= 0 || target.height <= 0) return;
        // Startup may leave an unrelated GL error set. Attribute it here instead of failing the subsequent interop import.
        for (int error; (error = glGetError()) != GL_NO_ERROR; )
            github.com.gengyoubo.vulkanforge.VulkanForge.LOGGER.warn("GL error 0x{} before shared RenderTarget allocation", Integer.toHexString(error));
        int oldColor = target.getColorTextureId(), oldDepth = target.getDepthTextureId();
        int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
        int filter;
        try {
            GlStateManager._bindTexture(oldColor);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT) != GL_RGBA8
                    || glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH) != target.width
                    || glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT) != target.height) return;
            // MainTarget initializes texture parameters directly; its filterMode field can still be zero here.
            filter = glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER);
            GlStateManager._bindTexture(oldDepth);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT) != GL_DEPTH_COMPONENT32F) return;
        } finally { GlStateManager._bindTexture(bound); }
        int[] textures = NativeMainWindow.createRenderTarget(target.frameBufferId, target.width, target.height);
        if (textures == null) return;
        int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[0], 0);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, textures[1], 0);
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Shared RenderTarget framebuffer is incomplete");
            ((RenderTargetTextures) target).vulkanforge$setTextures(textures[0], textures[1]);
            targets.put(target, target.frameBufferId);
            target.setFilterMode(filter);
            GlStateManager._bindTexture(textures[1]);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GlStateManager._bindTexture(0);
            TextureUtil.releaseTextureId(oldColor);
            TextureUtil.releaseTextureId(oldDepth);
        } catch (Throwable error) {
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, oldColor, 0);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, oldDepth, 0);
            ((RenderTargetTextures) target).vulkanforge$setTextures(oldColor, oldDepth);
            targets.remove(target);
            NativeMainWindow.destroyRenderTarget(target.frameBufferId);
            throw error;
        } finally { GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw); }
    }

    /** The importer deletes shared GL names. Prevent vanilla from deleting those names a second time. */
    public static void destroy(RenderTarget target) {
        Integer framebuffer = targets.remove(target);
        if (framebuffer == null) return;
        GlStateManager._bindTexture(0);
        NativeMainWindow.destroyRenderTarget(framebuffer);
        ((RenderTargetTextures) target).vulkanforge$setTextures(-1, -1);
    }

    static void shutdown() {
        for (RenderTarget target : targets.keySet().toArray(RenderTarget[]::new)) destroy(target);
    }
    static boolean shared(RenderTarget target) { return target != null && targets.containsKey(target); }
}
