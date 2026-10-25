package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vulkanforge.bridge.FrameProtocol;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL21.*;

/** Uploads raw RGBA bytes, with no color swizzle or per-pixel Java conversion. */
final class FrameTexture extends AbstractTexture {
    private ByteBuffer upload = MemoryUtil.memAlloc(FrameProtocol.BYTES);

    FrameTexture() {
        RenderSystem.assertOnRenderThread();
        int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            TextureUtil.prepareImage(getId(), FrameProtocol.WIDTH, FrameProtocol.HEIGHT);
            setFilter(true, false);
        } finally { GlStateManager._bindTexture(bound); }
    }

    void upload(byte[] rgba) {
        RenderSystem.assertOnRenderThread();
        if (upload == null || rgba.length != FrameProtocol.BYTES) throw new IllegalStateException("Invalid Vulkan frame upload");
        upload.clear().put(rgba).flip();
        int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
        int pbo = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int alignment = glGetInteger(GL_UNPACK_ALIGNMENT), rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        int skipRows = glGetInteger(GL_UNPACK_SKIP_ROWS), skipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        try {
            bind();
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, FrameProtocol.WIDTH, FrameProtocol.HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, upload);
        } finally {
            glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, skipRows);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipPixels);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
            GlStateManager._bindTexture(bound);
        }
    }

    @Override public void load(ResourceManager resources) { }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (upload != null) { MemoryUtil.memFree(upload); upload = null; }
        releaseId();
    }
}
