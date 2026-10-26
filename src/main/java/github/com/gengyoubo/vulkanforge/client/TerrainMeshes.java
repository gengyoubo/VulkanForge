package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.vulkanforge.bridge.WorldProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable copies of vanilla's completed section builds. Never owns a RenderedBuffer or builds blocks a second time. */
public final class TerrainMeshes {
    static final RenderType[] LAYERS = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
    private static final byte[] EMPTY = new byte[0];
    private static final long CACHE_LIMIT = 128L * 1024 * 1024;
    private static final Map<VertexBuffer, byte[]> uploads = new IdentityHashMap<>();
    private static final Map<ChunkRenderDispatcher.RenderChunk, Snapshot> ready = new ConcurrentHashMap<>();
    private static volatile Set<ChunkRenderDispatcher.RenderChunk> members = Set.of();
    private static ClientLevel level;
    private static ViewArea currentArea;
    private static long bytes, copies, commits, missed, indexUpdates;
    private TerrainMeshes() { }

    record Snapshot(ChunkRenderDispatcher.RenderChunk chunk, ChunkRenderDispatcher.CompiledChunk compiled, BlockPos origin, byte[][] layers) {
        long key() { return SectionPos.of(origin).asLong(); }
        boolean current() { return members.contains(chunk) && chunk.getCompiledChunk() == compiled && chunk.getOrigin().equals(origin); }
        boolean empty() { for (byte[] layer : layers) if (layer.length > 0) return false; return true; }
    }

    /** ViewArea constructor: the cache follows the actual render distance and the dimension's complete vertical range. */
    public static synchronized void area(ViewArea area) {
        uploads.clear(); ready.clear(); bytes = 0;
        level = Minecraft.getInstance().level;
        currentArea = area;
        members = Set.copyOf(Arrays.asList(area.chunks));
    }
    public static synchronized void released(ViewArea area) {
        if (currentArea != area) return;
        uploads.clear(); ready.clear(); bytes = 0; members = Set.of(); level = null; currentArea = null;
    }

    /** RenderChunk.setOrigin/releaseBuffers: pooled chunk objects must never carry geometry to a different section. */
    public static void moved(ChunkRenderDispatcher.RenderChunk chunk) {
        ready.remove(chunk);
        for (RenderType type : LAYERS) {
            byte[] old = uploads.remove(chunk.getBuffer(type));
            if (old != null) bytes -= old.length;
        }
    }

    /** Only ChunkRenderDispatcher's upload call is redirected here, before vanilla releases/reuses the builder. */
    public static void upload(VertexBuffer buffer, BufferBuilder.RenderedBuffer rendered) {
        RenderSystem.assertOnRenderThread();
        var state = rendered.drawState();
        if (state.indexOnly()) indexUpdates++; // Vanilla transparency resort changes no positions, colors, UVs or lighting.
        else {
            byte[] old = uploads.remove(buffer); if (old != null) bytes -= old.length;
            long expanded = (long) state.vertexCount() / 4 * 6 * WorldProtocol.STRIDE;
            if (!buffer.isInvalid() && state.format() == DefaultVertexFormat.BLOCK && state.mode() == VertexFormat.Mode.QUADS
                    && state.vertexCount() % 4 == 0 && expanded <= WorldProtocol.MAX_MESH_BYTES
                    && bytes + rendered.vertexBuffer().remaining() <= CACHE_LIMIT) {
                ByteBuffer source = rendered.vertexBuffer().duplicate(); byte[] copy = new byte[source.remaining()]; source.get(copy);
                uploads.put(buffer, copy); bytes += copy.length; copies++;
            }
        }
        buffer.upload(rendered);
    }

    /** RebuildTask's successful compiled.set only: failed/cancelled/partially uploaded builds cannot become Vulkan geometry. */
    public static synchronized void compiled(AtomicReference<ChunkRenderDispatcher.CompiledChunk> reference,
                                ChunkRenderDispatcher.CompiledChunk compiled, ChunkRenderDispatcher.RenderChunk chunk) {
        reference.set(compiled);
        if (!members.contains(chunk)) return;
        byte[][] layers = new byte[LAYERS.length][];
        for (int i = 0; i < LAYERS.length; i++) {
            if (compiled.isEmpty(LAYERS[i])) layers[i] = EMPTY;
            else {
                // Nonempty rebuilds finish on the render thread after their last upload. Empty builds may finish on a worker.
                layers[i] = RenderSystem.isOnRenderThread() ? uploads.get(chunk.getBuffer(LAYERS[i])) : null;
                if (layers[i] == null) { ready.remove(chunk); missed++; return; }
            }
        }
        ready.put(chunk, new Snapshot(chunk, compiled, chunk.getOrigin().immutable(), layers)); commits++;
    }

    static Collection<Snapshot> snapshots(ClientLevel expected) { return level == expected ? ready.values() : List.of(); }
    static boolean available(ClientLevel expected) { return level == expected && !members.isEmpty(); }
    static long[] stats() { return new long[]{copies, commits, missed, indexUpdates, bytes, members.size(), ready.size()}; }
}
