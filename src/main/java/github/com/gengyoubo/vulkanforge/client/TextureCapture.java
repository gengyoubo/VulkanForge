package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

/** Reads a texture while preserving GL texture and pixel-pack state. */
final class TextureCapture {
    record Snapshot(int width, int height, byte[] rgba) { }
    private TextureCapture() { }

    /** One reusable pixel-pack buffer. Polls GPU completion without waiting on the render thread. */
    static final class Readback implements AutoCloseable {
        private int buffer, capacity, width, height;
        private long fence;
        private Snapshot cpu;
        void request(int texture) {
            if (fence != 0||cpu!=null) return;
            cpu=CpuTextures.snapshot(texture);if(cpu!=null)return;
            if (texture <= 0) throw new IllegalStateException("Required Minecraft texture has not been initialized");
            int bound = glGetInteger(GL_TEXTURE_BINDING_2D), previous = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            int alignment = glGetInteger(GL_PACK_ALIGNMENT), rowLength = glGetInteger(GL_PACK_ROW_LENGTH);
            int skipRows = glGetInteger(GL_PACK_SKIP_ROWS), skipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
            try {
                GlStateManager._bindTexture(texture);
                width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH); height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
                if (width < 1 || height < 1 || width > 4096 || height > 4096) throw new IllegalStateException("Unsupported texture dimensions: " + width + "x" + height);
                if (buffer == 0) buffer = glGenBuffers(); glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
                int size = width * height * 4;
                if (capacity != size) { glBufferData(GL_PIXEL_PACK_BUFFER, size, GL_STREAM_READ); capacity = size; }
                glPixelStorei(GL_PACK_ALIGNMENT, 1); glPixelStorei(GL_PACK_ROW_LENGTH, 0);
                glPixelStorei(GL_PACK_SKIP_ROWS, 0); glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
                fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (fence == 0) throw new IllegalStateException("Texture readback fence creation failed");
            } finally {
                glPixelStorei(GL_PACK_ALIGNMENT, alignment); glPixelStorei(GL_PACK_ROW_LENGTH, rowLength);
                glPixelStorei(GL_PACK_SKIP_ROWS, skipRows); glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
                glBindBuffer(GL_PIXEL_PACK_BUFFER, previous); GlStateManager._bindTexture(bound);
            }
        }
        Snapshot poll() {
            if(cpu!=null){Snapshot result=cpu;cpu=null;return result;}
            if (fence == 0) return null;
            int result = glClientWaitSync(fence, 0, 0L);
            if (result == GL_TIMEOUT_EXPIRED) return null;
            if (result == GL_WAIT_FAILED) throw new IllegalStateException("Texture readback fence failed");
            int previous = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
                var mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, capacity, GL_MAP_READ_BIT);
                if (mapped == null) throw new IllegalStateException("Texture readback map failed");
                byte[] bytes = new byte[capacity];
                try { mapped.get(bytes); } finally { if (!glUnmapBuffer(GL_PIXEL_PACK_BUFFER)) throw new IllegalStateException("Texture readback corrupted"); }
                return new Snapshot(width, height, bytes);
            } finally {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, previous); glDeleteSync(fence); fence = 0;
            }
        }
        @Override public void close() {
            cpu=null;
            if (fence != 0) { glDeleteSync(fence); fence = 0; }
            if (buffer != 0) { glDeleteBuffers(buffer); buffer = 0; capacity = 0; }
        }
    }

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
