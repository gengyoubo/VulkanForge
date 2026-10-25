package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

/** ASM redirects restricted to LevelRenderer's chunk layer loop. */
public final class TerrainPasses {
    private static boolean replace;
    private static RenderType layer;
    private static long solidSkipped, cutoutSkipped, fallbackDraws;
    private TerrainPasses() { }
    public static void markDirty(net.minecraft.client.renderer.ViewArea area, int x, int y, int z, boolean important) {
        area.setDirty(x, y, z, important);
        VulkanPreview.markDirty(x, y, z);
    }
    public static VertexBuffer buffer(ChunkRenderDispatcher.RenderChunk chunk, RenderType type) {
        layer = type;
        replace = (type == RenderType.solid() || type == RenderType.cutout() || type == RenderType.cutoutMipped())
                && VulkanPreview.replacesSection(chunk.getOrigin());
        return chunk.getBuffer(type);
    }
    public static void draw(VertexBuffer buffer) {
        if (replace) {
            if (layer == RenderType.solid()) solidSkipped++; else cutoutSkipped++;
        } else { fallbackDraws++; buffer.draw(); }
        replace = false;
    }
    static long[] stats() { return new long[]{solidSkipped, cutoutSkipped, fallbackDraws}; }
}
