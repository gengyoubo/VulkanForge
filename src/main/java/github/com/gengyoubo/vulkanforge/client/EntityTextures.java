package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import java.util.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Mirrors GL textures used by captured entity draws into the Vulkan backend, keyed by GL texture id. Readback is asynchronous
 * (PBO + fence, polled once per frame); a draw whose texture is not mirrored yet stays on GL. Textures used within the last
 * second are re-read every 2 s and re-sent only when their pixels changed (late skin downloads, map items, animated atlases).
 */
final class EntityTextures {
    private static final int TEXTURE_LINEAR = 1, TEXTURE_CLAMP = 2, MAX_READBACKS = 4;
    private static final long REVALIDATE_NANOS = 2_000_000_000L, RECENT_NANOS = 1_000_000_000L;
    private static final class Entry {
        final TextureCapture.Readback readback = new TextureCapture.Readback();
        boolean sent, pending, failed;
        int flags, version, requestedVersion, sentVersion, width, height;
        byte[] rgba;
        long lastUsed, nextCheck;
    }
    private static final Map<Integer, Entry> entries = new HashMap<>();
    private static long uploads, revalidations, glyphUploads, glyphPatches;
    private EntityTextures() { }

    /** True when the backend already holds this texture; otherwise schedules a readback. */
    static boolean ready(int id) {
        if (id <= 0) return false;
        Entry entry = entries.computeIfAbsent(id, key -> new Entry());
        entry.lastUsed = System.nanoTime();
        return entry.sent && entry.version == entry.sentVersion;
    }

    /** A glyph page changed during this frame. Pending snapshots of an older version must not make it ready. */
    static void dirty(int id) {
        Entry entry = entries.computeIfAbsent(id, key -> new Entry());
        glyphUploads++;
        entry.version++; entry.nextCheck = 0; entry.failed = false;
    }

    /** Once per frame on the render thread, before capture opens. */
    static void poll() {
        long now = System.nanoTime();
        int inFlight = 0;
        for (var item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (!entry.pending) continue;
            var snapshot = entry.readback.poll();
            if (snapshot == null) { inFlight++; continue; }
            entry.pending = false;
            if (entry.requestedVersion != entry.version) { entry.nextCheck = 0; continue; }
            boolean changed = !entry.sent || entry.width != snapshot.width() || entry.height != snapshot.height() || !Arrays.equals(entry.rgba, snapshot.rgba());
            if (!changed) { entry.sentVersion = entry.version; continue; }
            boolean sent;
            if (entry.sent && entry.width == snapshot.width() && entry.height == snapshot.height()) {
                int left = entry.width, top = entry.height, right = 0, bottom = 0;
                for (int y = 0; y < entry.height; y++) for (int x = 0; x < entry.width; x++) {
                    int at = (y * entry.width + x) * 4;
                    if (!Arrays.equals(entry.rgba, at, at + 4, snapshot.rgba(), at, at + 4)) {
                        left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x + 1); bottom = Math.max(bottom, y + 1);
                    }
                }
                int width = right - left, height = bottom - top;
                byte[] patch = new byte[width * height * 4];
                for (int y = 0; y < height; y++) System.arraycopy(snapshot.rgba(), ((top + y) * entry.width + left) * 4, patch, y * width * 4, width * 4);
                sent = NativeMainWindow.entityTexturePatch(new Object[]{item.getKey(), left, top, width, height, patch});
            } else sent = NativeMainWindow.entityTexture(new Object[]{item.getKey(), snapshot.width(), snapshot.height(), entry.flags, snapshot.rgba()});
            if (sent) {
                if (entry.sent) { revalidations++; if (entry.version > 0) glyphPatches++; }
                entry.sent = true; entry.sentVersion = entry.version; entry.width = snapshot.width(); entry.height = snapshot.height(); entry.rgba = snapshot.rgba(); uploads++;
            } else entry.nextCheck = 0;
        }
        for (var item : entries.entrySet()) {
            if (inFlight >= MAX_READBACKS) break;
            Entry entry = item.getValue();
            if (entry.pending || entry.failed || now < entry.nextCheck || (entry.sent && now - entry.lastUsed > RECENT_NANOS)) continue;
            try {
                entry.flags = flags(item.getKey());
                entry.readback.request(item.getKey());
                entry.requestedVersion = entry.version;
                entry.pending = true; entry.nextCheck = now + REVALIDATE_NANOS; inFlight++;
            } catch (RuntimeException error) {
                entry.failed = true;
                VulkanForge.LOGGER.warn("Entity texture {} cannot be mirrored to Vulkan; its draws stay on OpenGL: {}", item.getKey(), error.getMessage());
            }
        }
    }

    private static int flags(int texture) {
        int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._bindTexture(texture);
            int filter = glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER), wrap = glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S);
            return (filter == GL_LINEAR ? TEXTURE_LINEAR : 0) | (wrap == GL_CLAMP_TO_EDGE ? TEXTURE_CLAMP : 0);
        } finally { GlStateManager._bindTexture(bound); }
    }

    static long[] stats() { int sent = 0; for (Entry entry : entries.values()) if (entry.sent) sent++; return new long[]{entries.size(), sent, uploads, revalidations, glyphUploads, glyphPatches}; }

    /** The backend's mirrors die with its world renderer; forget ours with them. */
    static void reset() { entries.values().forEach(entry -> entry.readback.close()); entries.clear(); }
}
