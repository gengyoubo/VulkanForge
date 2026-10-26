package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Publishes vanilla's completed chunk meshes, with bounded conversion/upload work and GL fallback until admission succeeds. */
final class WorldCapture {
    private static final int BLOCK_STRIDE = com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.getVertexSize();
    private static int triangleBytes(int rawBytes) { return rawBytes / BLOCK_STRIDE / 4 * 6 * WorldProtocol.STRIDE; }
    private static final AtomicLong EPOCHS = new AtomicLong();
    private static final long FRAME_BUILD_BUDGET = 1_500_000L, GEOMETRY_LIMIT = 192L * 1024 * 1024;
    private static final int MAX_SECTIONS = 65534;
    private final Map<Long, TerrainMeshes.Snapshot> sent = new HashMap<>();
    private final Map<Long, Long> queuedAt = new HashMap<>();
    private final Set<Long> dirty = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private ClientLevel level;
    private long epoch, completedBuilds, cancelledBuilds, deferredPublishes, maxFrameBuildNanos, maxCompletedPerFrame, geometryBytes;
    private final long[] frameSamples = new long[1024];
    private int frameSampleCount, frameSampleCursor, textureCursor;
    private Build build;
    private boolean atlasSent, bootstrap;
    private TextureCapture.Snapshot atlasSnapshot;
    private byte[] lightSnapshot;
    private long nextTexturePoll, atlasPatches, lightUpdates, cutoutQuads, translucentQuads, translucentMeshes;
    private final TextureCapture.Readback atlasReadback = new TextureCapture.Readback(), lightReadback = new TextureCapture.Readback();

    long[] schedulerStats() {
        long[] sample = Arrays.copyOf(frameSamples, frameSampleCount); Arrays.sort(sample);
        return new long[]{completedBuilds, cancelledBuilds, deferredPublishes, queuedAt.size(), maxFrameBuildNanos, maxCompletedPerFrame,
                sample.length == 0 ? 0 : sample[(sample.length - 1) * 95 / 100]};
    }
    long epoch() { return epoch; }
    int sections() { return sent.size(); }
    boolean covers(BlockPos origin) {
        var snapshot = sent.get(SectionPos.of(origin).asLong());
        return level == Minecraft.getInstance().level && snapshot != null && snapshot.current();
    }
    void markDirty(int x, int y, int z) { dirty.add(SectionPos.asLong(x, y, z)); }
    long builds() { return completedBuilds; }
    long atlasPatches() { return atlasPatches; }
    long lightUpdates() { return lightUpdates; }
    int cutoutQuads() { return (int) Math.min(Integer.MAX_VALUE, cutoutQuads); }
    long translucentQuads() { return translucentQuads; }
    long translucentMeshes() { return translucentMeshes; }
    void invalidate() { level = null; }
    void close() { build = null; queuedAt.clear(); sent.clear(); dirty.clear(); geometryBytes = 0; atlasReadback.close(); lightReadback.close(); invalidate(); }
    void leave(WorldSink bridge) { if (epoch != 0) { close(); epoch = 0; atlasSent = false; bridge.reset(0); } }

    private static long size(TerrainMeshes.Snapshot snapshot) {
        long bytes = 0; for (byte[] layer : snapshot.layers()) bytes += triangleBytes(layer.length);
        return bytes;
    }

    void update(RenderLevelStageEvent event, WorldSink bridge) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (level != mc.level) {
            close(); level = mc.level; epoch = EPOCHS.incrementAndGet(); atlasSent = bootstrap = false;
            atlasSnapshot = null; lightSnapshot = null; nextTexturePoll = 0; textureCursor = 0;
            cutoutQuads = translucentQuads = translucentMeshes = 0;
            bridge.reset(epoch);
            VulkanForge.LOGGER.info("Vulkan vanilla mesh capture reset: epoch={}, dimension={}", epoch, level.dimension().location());
        }
        if (!atlasSent) {
            atlasReadback.request(mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
            var snapshot = atlasReadback.poll();
            if (snapshot == null || !bridge.send(new WorldProtocol.Atlas(epoch, snapshot.width(), snapshot.height(), snapshot.rgba()))) return;
            atlasSent = true;
            atlasSnapshot = new TextureCapture.Snapshot(snapshot.width(), snapshot.height(), snapshot.rgba().clone());
            // A void world still has a valid sky/camera even before any nonempty chunk finishes compiling.
            bootstrap = bridge.send(new WorldProtocol.Mesh(epoch, 0, 0, 0, 0, new byte[0]));
        }
        if (!syncTextures(mc, bridge)) return;
        var position = event.getCamera().getPosition();
        float[] matrix = new Matrix4f(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose()).get(new float[16]);
        float[] fog = RenderSystem.getShaderFogColor();
        bridge.camera(new WorldProtocol.Camera(epoch, position.x, position.y, position.z, matrix, fog[0], fog[1], fog[2]));

        long frameStart = System.nanoTime(), deadline = frameStart + FRAME_BUILD_BUDGET;
        Map<Long, TerrainMeshes.Snapshot> candidates = new HashMap<>();
        for (var snapshot : TerrainMeshes.snapshots(level)) if (snapshot.current() && !snapshot.empty()
                && level.hasChunk(SectionPos.blockToSectionCoord(snapshot.origin().getX()), SectionPos.blockToSectionCoord(snapshot.origin().getZ())))
            candidates.put(snapshot.key(), snapshot);
        Iterator<Map.Entry<Long, TerrainMeshes.Snapshot>> old = sent.entrySet().iterator();
        while (old.hasNext()) {
            var entry = old.next();
            if (candidates.get(entry.getKey()) != entry.getValue() && bridge.send(new WorldProtocol.Remove(epoch, entry.getKey()))) {
                geometryBytes -= size(entry.getValue()); old.remove();
            }
        }
        queuedAt.keySet().retainAll(candidates.keySet()); dirty.retainAll(candidates.keySet());
        if (build != null && candidates.get(build.snapshot.key()) != build.snapshot) { build = null; cancelledBuilds++; }
        int completed = 0;
        while (completed < 16 && System.nanoTime() < deadline) {
            if (build == null) {
                TerrainMeshes.Snapshot selected = null; double best = Double.POSITIVE_INFINITY;
                for (var candidate : candidates.values()) {
                    long key = candidate.key();
                    if (sent.get(key) == candidate) { queuedAt.remove(key); continue; }
                    long since = queuedAt.computeIfAbsent(key, ignored -> frameStart);
                    long opaqueBytes = triangleBytes(candidate.layers()[0].length + candidate.layers()[1].length + candidate.layers()[2].length);
                    if (sent.size() >= MAX_SECTIONS || geometryBytes + size(candidate) > GEOMETRY_LIMIT
                            || opaqueBytes > WorldProtocol.MAX_MESH_BYTES) continue;
                    BlockPos origin = candidate.origin();
                    boolean visible = event.getFrustum() == null || event.getFrustum().isVisible(candidate.chunk().getBoundingBox());
                    double score = origin.distToCenterSqr(position) - Math.min(2_000_000, (frameStart - since) / 10_000.0)
                            - (dirty.contains(key) ? 4_000_000 : visible ? 1_000_000 : 0);
                    if (score < best) { best = score; selected = candidate; }
                }
                if (selected == null) break;
                build = new Build(selected);
            }
            if (!build.step(deadline)) break;
            var source = build.snapshot; BlockPos origin = source.origin();
            if (!bridge.send(new WorldProtocol.Mesh(epoch, source.key(), origin.getX(), origin.getY(), origin.getZ(), build.opaque, build.translucent))) {
                deferredPublishes++; break;
            }
            sent.put(source.key(), source); geometryBytes += size(source); queuedAt.remove(source.key()); dirty.remove(source.key());
            if (bootstrap) { if (source.key() != 0) bridge.send(new WorldProtocol.Remove(epoch, 0)); bootstrap = false; }
            cutoutQuads += (source.layers()[1].length + source.layers()[2].length) / (4 * BLOCK_STRIDE);
            translucentQuads += build.translucent.length / (6 * WorldProtocol.STRIDE);
            if (build.translucent.length > 0) translucentMeshes++;
            build = null; completed++; completedBuilds++;
        }
        long elapsed = System.nanoTime() - frameStart;
        maxFrameBuildNanos = Math.max(maxFrameBuildNanos, elapsed); maxCompletedPerFrame = Math.max(maxCompletedPerFrame, completed);
        frameSamples[frameSampleCursor++ & (frameSamples.length - 1)] = elapsed;
        frameSampleCount = Math.min(frameSampleCount + 1, frameSamples.length);
    }

    private boolean syncTextures(Minecraft mc, WorldSink bridge) {
        if (System.nanoTime() >= nextTexturePoll) {
            nextTexturePoll = System.nanoTime() + 100_000_000L;
            lightReadback.request(RenderSystem.getShaderTexture(2));
            atlasReadback.request(mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
        }
        var light = lightReadback.poll();
        if (light != null && (light.width() != 16 || light.height() != 16)) throw new IllegalStateException("Expected Minecraft 16x16 lightmap");
        if (light != null && !Arrays.equals(lightSnapshot, light.rgba()) && bridge.send(new WorldProtocol.TexturePatch(epoch, WorldProtocol.LIGHT_MAP, 0, 0, 16, 16, light.rgba()))) {
            lightSnapshot = light.rgba(); lightUpdates++;
            if (lightUpdates == 1 || lightUpdates % 100 == 0) VulkanForge.LOGGER.info("Vulkan lightmap updates={}", lightUpdates);
        }
        var current = atlasReadback.poll();
        if (current == null) return true;
        if (current.width() != atlasSnapshot.width() || current.height() != atlasSnapshot.height()) { invalidate(); return false; }
        int budget = 8;
        int columns = (current.width() + 31) / 32, tiles = columns * ((current.height() + 31) / 32);
        // Round-robin prevents frequently changing low-index sprites from starving later atlas regions.
        for (int checked = 0; checked < tiles && budget > 0; checked++) {
            int tile = textureCursor;
            textureCursor = (textureCursor + 1) % tiles;
            int x = (tile % columns) * 32, y = (tile / columns) * 32;
            int width = Math.min(32, current.width() - x), height = Math.min(32, current.height() - y);
            boolean changed = false;
            for (int row = 0; row < height && !changed; row++) {
                int start = ((y + row) * current.width() + x) * 4;
                changed = !Arrays.equals(current.rgba(), start, start + width * 4, atlasSnapshot.rgba(), start, start + width * 4);
            }
            if (!changed) continue;
            byte[] pixels = new byte[width * height * 4];
            for (int row = 0; row < height; row++) System.arraycopy(current.rgba(), ((y + row) * current.width() + x) * 4, pixels, row * width * 4, width * 4);
            if (!bridge.send(new WorldProtocol.TexturePatch(epoch, WorldProtocol.BLOCK_ATLAS, x, y, width, height, pixels))) return true;
            for (int row = 0; row < height; row++) System.arraycopy(pixels, row * width * 4, atlasSnapshot.rgba(), ((y + row) * current.width() + x) * 4, width * 4);
            atlasPatches++; budget--;
            if (atlasPatches == 1 || atlasPatches % 100 == 0) VulkanForge.LOGGER.info("Vulkan animated atlas patches={}", atlasPatches);
        }
        return true;
    }

    /** Convert immutable BLOCK quads incrementally; original builder buffers have already been returned to vanilla. */
    private static final class Build {
        final TerrainMeshes.Snapshot snapshot;
        final byte[] opaque, translucent;
        int layer, quad, output;
        private static final int[] CORNERS = {0, 1, 2, 2, 3, 0};
        Build(TerrainMeshes.Snapshot snapshot) {
            this.snapshot = snapshot;
            byte[][] layers = snapshot.layers();
            opaque = new byte[triangleBytes(layers[0].length + layers[1].length + layers[2].length)];
            translucent = new byte[triangleBytes(layers[3].length)];
            if (opaque.length > WorldProtocol.MAX_MESH_BYTES || translucent.length > WorldProtocol.MAX_MESH_BYTES)
                throw new IllegalStateException("Section exceeds world protocol mesh budget");
        }
        boolean step(long deadline) {
            while (layer < 4) {
                byte[] source = snapshot.layers()[layer], target = layer == 3 ? translucent : opaque;
                while (quad < source.length) {
                    if ((quad / (4 * BLOCK_STRIDE) & 63) == 0 && System.nanoTime() >= deadline) return false;
                    for (int corner : CORNERS) { System.arraycopy(source, quad + corner * BLOCK_STRIDE, target, output, WorldProtocol.STRIDE); output += WorldProtocol.STRIDE; }
                    quad += 4 * BLOCK_STRIDE;
                }
                layer++; quad = 0; if (layer == 3) output = 0;
            }
            return true;
        }
    }
}
