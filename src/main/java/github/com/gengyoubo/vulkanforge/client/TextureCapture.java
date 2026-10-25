package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL21.*;

/** Reads a texture while preserving GL texture and pixel-pack state. */
final class TextureCapture {
    record Snapshot(int width, int height, byte[] rgba) { }
    private TextureCapture() { }

    static Snapshot read(int texture) {
        if (texture <= 0) throw new IllegalStateException("Required Minecraft texture has not been initialized");
        int bound = glGetInteger(GL_TEXTURE_BINDING_2D), pbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = glGetInteger(GL_PACK_ALIGNMENT), rowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int skipRows = glGetInteger(GL_PACK_SKIP_ROWS), skipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        try {
            GlStateManager._bindTexture(texture);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0); glPixelStorei(GL_PACK_SKIP_ROWS, 0); glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH), height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (width < 1 || height < 1 || width > 4096 || height > 4096) throw new IllegalStateException("Unsupported Minecraft texture dimensions: " + width + "x" + height);
            try (NativeImage image = new NativeImage(width, height, false)) {
                image.downloadTexture(0, false);
                int[] pixels = image.getPixelsRGBA();
                byte[] bytes = new byte[pixels.length * 4];
                for (int i = 0; i < pixels.length; i++) for (int c = 0; c < 4; c++) bytes[i * 4 + c] = (byte) (pixels[i] >>> (c * 8));
                return new Snapshot(width, height, bytes);
            }
        } finally {
            glPixelStorei(GL_PACK_ALIGNMENT, alignment); glPixelStorei(GL_PACK_ROW_LENGTH, rowLength);
            glPixelStorei(GL_PACK_SKIP_ROWS, skipRows); glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo); GlStateManager._bindTexture(bound);
        }
    }
}
