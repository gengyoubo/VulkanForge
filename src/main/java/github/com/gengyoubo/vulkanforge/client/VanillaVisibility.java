package github.com.gengyoubo.vulkanforge.client;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.LongConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.SectionPos;

/**
 * Vanilla's per-frame visible section list (LevelRenderer.renderChunksInFrustum: frustum plus the occlusion-graph flood fill),
 * the exact set vanilla draws this frame. Fields are found by type (names are SRG in production); {@link #available()} is false
 * when the layout is unexpected, and the GL 4.6 cull then falls back to frustum-only.
 */
final class VanillaVisibility {
    private static Field list, chunk;
    private static boolean resolved, available;
    private VanillaVisibility() { }

    static synchronized boolean available() {
        if (!resolved) {
            resolved = true;
            try {
                List<Field> lists = new ArrayList<>();
                for (Field field : LevelRenderer.class.getDeclaredFields())
                    if (!Modifier.isStatic(field.getModifiers()) && field.getType() == it.unimi.dsi.fastutil.objects.ObjectArrayList.class) lists.add(field);
                if (lists.size() != 1) throw new IllegalStateException(lists.size() + " ObjectArrayList fields");
                list = lists.get(0); list.setAccessible(true);
                Class<?> info = Arrays.stream(LevelRenderer.class.getDeclaredClasses()).filter(type -> type.getSimpleName().equals("RenderChunkInfo"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("no RenderChunkInfo"));
                List<Field> chunks = new ArrayList<>();
                for (Field field : info.getDeclaredFields()) if (field.getType() == ChunkRenderDispatcher.RenderChunk.class) chunks.add(field);
                if (chunks.size() != 1) throw new IllegalStateException(chunks.size() + " RenderChunk fields");
                chunk = chunks.get(0); chunk.setAccessible(true);
                available = true;
            } catch (RuntimeException error) {
                Gl46Context.report("VANILLA_VISIBILITY=UNAVAILABLE " + error);
            }
            if (available) Gl46Context.report("VANILLA_VISIBILITY=renderChunksInFrustum");
        }
        return available;
    }

    /** SectionPos keys of this frame's visible sections; false when unavailable. */
    static boolean forEach(LevelRenderer renderer, LongConsumer section) {
        if (!available()) return false;
        try {
            // Same key as TerrainMeshes.Snapshot.key().
            for (Object info : (List<?>) list.get(renderer))
                section.accept(SectionPos.of(((ChunkRenderDispatcher.RenderChunk) chunk.get(info)).getOrigin()).asLong());
            return true;
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }
}
