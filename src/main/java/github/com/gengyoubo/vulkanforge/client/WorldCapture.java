package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Render-thread capture of vanilla solid/cutout/translucent block and fluid geometry around the camera. */
final class WorldCapture {
    private static final AtomicLong EPOCHS = new AtomicLong();
    /** Last published {opaque, translucent} vertices per section. */
    private final Map<Long, byte[][]> sent = new HashMap<>();
    private final Set<Long> valid = new HashSet<>(), invalidated = new HashSet<>();
    private final Set<Long> dirty = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile int centerX, centerY, centerZ, radius = 1;
    private long completedBuilds;
    private final BufferBuilder builder = new BufferBuilder(262144), translucentBuilder = new BufferBuilder(65536);
    private ClientLevel level;
    private long epoch;
    private int textureCursor;
    private static final long FRAME_BUILD_BUDGET = 1_500_000L;
    private List<SectionPos> wanted = List.of();
    private final Set<Long> wantedKeys = new HashSet<>();
    private final Map<Long, Long> queuedAt = new HashMap<>();
    private int wantedX = Integer.MIN_VALUE, wantedY, wantedZ, wantedRadius;
    private long cancelledBuilds, deferredPublishes, completedThisFrame, maxFrameBuildNanos, maxCompletedPerFrame;
    private final long[] frameSamples = new long[1024];
    private int frameSampleCount, frameSampleCursor;
    private SectionPos pendingSection;
    private byte[][] pendingVertices;
    private boolean atlasSent;
    private TextureCapture.Snapshot atlasSnapshot;
    private byte[] lightSnapshot;
    private long nextTexturePoll;
    private long atlasPatches, lightUpdates;
    private int cutoutBlocks;
    private long translucentQuads, fluidBlocks;
    private final TextureCapture.Readback atlasReadback = new TextureCapture.Readback();
    private final TextureCapture.Readback lightReadback = new TextureCapture.Readback();
    private SectionBuild build;
    private long buildSlices, buildNanos, maxBuildNanos;

    private final class SectionBuild {
        final SectionPos section;
        final LevelChunkSection states;
        final Map<BlockPos, ModelData> modelData;
        final PoseStack poses = new PoseStack();
        final RandomSource random = RandomSource.create();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final boolean empty;
        int next;
        boolean bufferOpen;
        TriangleCopy opaqueCopy, translucentCopy;
        SectionBuild(SectionPos section) {
            this.section = section;
            states = level.getChunk(section.x(), section.z()).getSection(level.getSectionIndexFromSectionY(section.y()));
            empty = states.hasOnlyAir();
            modelData = empty ? Map.of() : level.getModelDataManager().getAt(new ChunkPos(section.x(), section.z()));
            if (!empty) {
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                translucentBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK); bufferOpen = true;
            }
        }
    }
    private void cancelBuild() {
        if (build != null && build.bufferOpen) {
            builder.end().release(); builder.discard();
            translucentBuilder.end().release(); translucentBuilder.discard();
        }
        if (build != null) {
            if (build.opaqueCopy != null) build.opaqueCopy.close();
            if (build.translucentCopy != null) build.translucentCopy.close();
            cancelledBuilds++;
        }
        build = null;
    }

    long[] schedulerStats() {
        long[] sample = Arrays.copyOf(frameSamples, Math.min(frameSampleCount, frameSamples.length)); Arrays.sort(sample);
        long p95 = sample.length == 0 ? 0 : sample[(sample.length - 1) * 95 / 100];
        return new long[]{completedBuilds, cancelledBuilds, deferredPublishes, queuedAt.size(), maxFrameBuildNanos, maxCompletedPerFrame, p95};
    }
    long epoch() { return epoch; }
    int sections() { return sent.size(); }
    boolean covers(BlockPos origin) {
        long key = SectionPos.of(origin).asLong();
        return level == Minecraft.getInstance().level && valid.contains(key) && !dirty.contains(key);
    }
    void markDirty(int x, int y, int z) {
        if (level != null && Math.abs(x - centerX) <= radius && Math.abs(y - centerY) <= 2 && Math.abs(z - centerZ) <= radius)
            dirty.add(SectionPos.asLong(x, y, z));
    }
    long builds() { return completedBuilds; }
    long atlasPatches() { return atlasPatches; }
    long lightUpdates() { return lightUpdates; }
    int cutoutBlocks() { return cutoutBlocks; }
    long translucentQuads() { return translucentQuads; }
    long fluidBlocks() { return fluidBlocks; }
    void invalidate() { level = null; }
    void close() {
        cancelBuild(); pendingSection = null; pendingVertices = null; queuedAt.clear(); wanted = List.of(); wantedKeys.clear(); wantedX = Integer.MIN_VALUE;
        atlasReadback.close(); lightReadback.close(); invalidate();
        if (buildSlices > 0) VulkanForge.LOGGER.info("Vulkan mesh capture slices={}, meanMs={}, maxMs={}", buildSlices,
                buildNanos / (buildSlices * 1_000_000.0), maxBuildNanos / 1_000_000.0);
        buildSlices = buildNanos = maxBuildNanos = 0;
    }
    void leave(WorldSink bridge) {
        if (epoch != 0) { close(); epoch = 0; sent.clear(); atlasSent = false; bridge.reset(0); }
    }

    void update(RenderLevelStageEvent event, WorldSink bridge) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (level != mc.level) {
            cancelBuild();
            atlasReadback.close(); lightReadback.close();
            level = mc.level;
            epoch = EPOCHS.incrementAndGet();
            sent.clear(); valid.clear(); invalidated.clear(); dirty.clear(); queuedAt.clear(); wantedX = Integer.MIN_VALUE;
            pendingSection = null; pendingVertices = null; atlasSent = false;
            atlasSnapshot = null; lightSnapshot = null; nextTexturePoll = 0; cutoutBlocks = 0; translucentQuads = fluidBlocks = 0;
            textureCursor = 0;
            bridge.reset(epoch);
            VulkanForge.LOGGER.info("Vulkan world capture reset: epoch={}, dimension={}", epoch, level.dimension().location());
        }
        if (!atlasSent) {
            atlasReadback.request(mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
            var snapshot = atlasReadback.poll();
            if (snapshot == null) return;
            atlasSent = bridge.send(new WorldProtocol.Atlas(epoch, snapshot.width(), snapshot.height(), snapshot.rgba()));
            if (!atlasSent) return;
            // Published packet bytes are immutable; keep a separate baseline for incremental patches.
            atlasSnapshot = new TextureCapture.Snapshot(snapshot.width(), snapshot.height(), snapshot.rgba().clone());
            VulkanForge.LOGGER.info("Captured Vulkan block atlas: {}x{}", snapshot.width(), snapshot.height());
        }
        if (!syncTextures(mc, bridge)) return;
        var position = event.getCamera().getPosition();
        float[] matrix = new Matrix4f(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose()).get(new float[16]);
        float[] fog = RenderSystem.getShaderFogColor();
        bridge.camera(new WorldProtocol.Camera(epoch, position.x, position.y, position.z, matrix, fog[0], fog[1], fog[2]));
        int sx = SectionPos.blockToSectionCoord((int) Math.floor(position.x));
        int sy = SectionPos.blockToSectionCoord((int) Math.floor(position.y));
        int sz = SectionPos.blockToSectionCoord((int) Math.floor(position.z));
        centerX = sx; centerY = sy; centerZ = sz;
        radius = Math.max(1, Math.min(4, (mc.options.renderDistance().get() + 3) / 8));
        if (sx != wantedX || sy != wantedY || sz != wantedZ || radius != wantedRadius) {
            List<SectionPos> nextWanted = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1) * 5);
            for (int x = sx - radius; x <= sx + radius; x++) for (int z = sz - radius; z <= sz + radius; z++)
                for (int y = Math.max(sy - 2, level.getMinSection()); y <= Math.min(sy + 2, level.getMaxSection() - 1); y++) nextWanted.add(SectionPos.of(x, y, z));
            wanted = nextWanted; wantedKeys.clear(); for (SectionPos section : wanted) wantedKeys.add(section.asLong());
            queuedAt.keySet().retainAll(wantedKeys);
            wantedX = sx; wantedY = sy; wantedZ = sz; wantedRadius = radius;
        }
        Set<Long> keys = wantedKeys;
        Iterator<Long> old = sent.keySet().iterator();
        while (old.hasNext()) {
            long key = old.next();
            if (!keys.contains(key) && bridge.send(new WorldProtocol.Remove(epoch, key))) { old.remove(); valid.remove(key); invalidated.remove(key); }
        }
        dirty.retainAll(keys);
        for (long key : dirty) if (sent.containsKey(key) && !invalidated.contains(key) && bridge.send(new WorldProtocol.Remove(epoch, key))) {
            valid.remove(key); invalidated.add(key);
        }
        if (build != null && (dirty.contains(build.section.asLong()) || !keys.contains(build.section.asLong()) || !level.hasChunk(build.section.x(), build.section.z()))) cancelBuild();
        if (pendingSection != null && (!keys.contains(pendingSection.asLong()) || dirty.contains(pendingSection.asLong())
                || !level.hasChunk(pendingSection.x(), pendingSection.z()))) { pendingSection = null; pendingVertices = null; }
        long frameStart = System.nanoTime(), deadline = frameStart + FRAME_BUILD_BUDGET;
        completedThisFrame = 0;
        while (completedThisFrame < 16 && System.nanoTime() < deadline) {
            if (pendingSection != null) {
                if (!publish(bridge, pendingSection, pendingVertices)) { deferredPublishes++; break; }
                pendingSection = null; pendingVertices = null;
            }
            if (build == null) {
                SectionPos selected = select(event, frameStart);
                if (selected == null) break;
                dirty.remove(selected.asLong()); queuedAt.remove(selected.asLong()); build = new SectionBuild(selected);
            }
            SectionPos selected = build.section;
            long start = System.nanoTime();
            byte[][] vertices = captureSection(mc, build, deadline);
            long elapsed = System.nanoTime() - start; buildSlices++; buildNanos += elapsed; maxBuildNanos = Math.max(maxBuildNanos, elapsed);
            if (vertices == null) break;
            build = null; completedBuilds++; completedThisFrame++;
            if (dirty.contains(selected.asLong())) continue;
            if (!publish(bridge, selected, vertices)) { pendingSection = selected; pendingVertices = vertices; deferredPublishes++; break; }
        }
        long frameElapsed = System.nanoTime() - frameStart;
        maxFrameBuildNanos = Math.max(maxFrameBuildNanos, frameElapsed); maxCompletedPerFrame = Math.max(maxCompletedPerFrame, completedThisFrame);
        frameSamples[frameSampleCursor++ & (frameSamples.length - 1)] = frameElapsed;
        frameSampleCount = Math.min(frameSampleCount + 1, frameSamples.length);
    }

    /** Edits first, then visible nearby sections; age prevents offscreen work from starving. Missing chunks are never force-loaded. */
    private SectionPos select(RenderLevelStageEvent event, long now) {
        SectionPos best = null; double bestScore = Double.POSITIVE_INFINITY;
        var position = event.getCamera().getPosition();
        for (SectionPos section : wanted) {
            long key = section.asLong();
            if (valid.contains(key) && !dirty.contains(key)) { queuedAt.remove(key); continue; }
            if (!level.hasChunk(section.x(), section.z())) continue;
            long since = queuedAt.computeIfAbsent(key, ignored -> now);
            boolean visible = event.getFrustum() == null || event.getFrustum().isVisible(new net.minecraft.world.phys.AABB(
                    section.minBlockX(), section.minBlockY(), section.minBlockZ(), section.minBlockX() + 16, section.minBlockY() + 16, section.minBlockZ() + 16));
            double score = section.center().distToCenterSqr(position) - Math.min(2_000_000, (now - since) / 10_000.0)
                    - (dirty.contains(key) && sent.containsKey(key) ? 4_000_000 : visible ? 1_000_000 : 0);
            if (score < bestScore) { best = section; bestScore = score; }
        }
        return best;
    }

    private boolean publish(WorldSink bridge, SectionPos selected, byte[][] vertices) {
        long key = selected.asLong();
        if (valid.contains(key) && Arrays.deepEquals(sent.get(key), vertices)) return true;
        if (!bridge.send(new WorldProtocol.Mesh(epoch, key, selected.minBlockX(), selected.minBlockY(), selected.minBlockZ(), vertices[0], vertices[1]))) return false;
        sent.put(key, vertices); valid.add(key); invalidated.remove(key); queuedAt.remove(key);
        translucentQuads += vertices[1].length / (6 * WorldProtocol.STRIDE);
        if (sent.size() == 1 || sent.size() % 15 == 0) VulkanForge.LOGGER.info("Vulkan world mesh: sections={}, lastVertices={}, translucentVertices={}", sent.size(),
                vertices[0].length / WorldProtocol.STRIDE, vertices[1].length / WorldProtocol.STRIDE);
        return true;
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

    /** Returns {opaque, translucent} triangle vertices, or null when the 1 ms slice ran out. */
    private byte[][] captureSection(Minecraft mc, SectionBuild job, long deadline) {
        if (job.empty) return new byte[][]{new byte[0], new byte[0]};
        SectionPos section = job.section;
        var states = job.states; var modelData = job.modelData;
        var poses = job.poses; var random = job.random; var pos = job.pos;
        if (job.opaqueCopy != null) return finishCopies(job, deadline);
        ModelBlockRenderer.enableCaching();
        BufferBuilder.RenderedBuffer rendered = null, translucent = null;
        try {
            while (job.next < 4096) {
                if (System.nanoTime() >= deadline) return null;
                int index = job.next++, x = index & 15, z = (index >>> 4) & 15, y = index >>> 8;
                var state = states.getBlockState(x, y, z);
                var fluid = state.getFluidState();
                pos.set(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z);
                if (!fluid.isEmpty()) {
                    // Water blocks have no MODEL shape; LiquidBlockRenderer already emits section-relative (pos & 15) positions.
                    RenderType layer = ItemBlockRenderTypes.getRenderLayer(fluid);
                    BufferBuilder target = layer == RenderType.translucent() ? translucentBuilder : opaque(layer) ? builder : null;
                    if (target != null) { mc.getBlockRenderer().renderLiquid(pos, level, target, state, fluid); fluidBlocks++; }
                }
                if (state.getRenderShape() != RenderShape.MODEL) continue;
                var model = mc.getBlockRenderer().getBlockModel(state);
                ModelData data = model.getModelData(level, pos, state, modelData.getOrDefault(pos, ModelData.EMPTY));
                random.setSeed(state.getSeed(pos));
                for (RenderType type : model.getRenderTypes(state, random, data)) {
                    BufferBuilder target = type == RenderType.translucent() ? translucentBuilder : opaque(type) ? builder : null;
                    if (target == null) continue;
                    if (type == RenderType.cutout() || type == RenderType.cutoutMipped()) cutoutBlocks++;
                    poses.pushPose(); poses.translate(x, y, z);
                    mc.getBlockRenderer().renderBatched(state, pos, level, poses, target, true, random, data, type);
                    poses.popPose();
                }
            }
            rendered = builder.end(); translucent = translucentBuilder.end();
            job.bufferOpen = false;
            job.opaqueCopy = new TriangleCopy(rendered); rendered = null;
            job.translucentCopy = new TriangleCopy(translucent); translucent = null;
            return finishCopies(job, deadline);
        } finally {
            if (rendered != null) rendered.release();
            if (translucent != null) translucent.release();
            ModelBlockRenderer.clearCache();
        }
    }

    private static boolean opaque(RenderType type) { return type == RenderType.solid() || type == RenderType.cutout() || type == RenderType.cutoutMipped(); }

    private byte[][] finishCopies(SectionBuild job, long deadline) {
        if (!job.opaqueCopy.step(deadline) || !job.translucentCopy.step(deadline)) return null;
        byte[][] result = {job.opaqueCopy.output.array(), job.translucentCopy.output.array()};
        job.opaqueCopy.close(); job.translucentCopy.close();
        return result;
    }

    /** Incremental conversion also stays inside the frame budget; each original buffer belongs exclusively to this build. */
    private static final class TriangleCopy implements AutoCloseable {
        final BufferBuilder.RenderedBuffer rendered;
        final ByteBuffer input, output;
        final int vertices, stride = DefaultVertexFormat.BLOCK.getVertexSize();
        int quad;
        boolean released;
        TriangleCopy(BufferBuilder.RenderedBuffer rendered) {
            this.rendered = rendered; vertices = rendered.drawState().vertexCount(); input = rendered.vertexBuffer();
            if (vertices % 4 != 0 || (long) vertices / 4 * 6 * WorldProtocol.STRIDE > WorldProtocol.MAX_MESH_BYTES) throw new IllegalStateException("Invalid or oversized section mesh");
            output = ByteBuffer.allocate(vertices / 4 * 6 * WorldProtocol.STRIDE);
        }
        boolean step(long deadline) {
            int[] corners = {0, 1, 2, 2, 3, 0};
            while (quad < vertices) {
                if ((quad & 255) == 0 && System.nanoTime() >= deadline) return false;
                for (int corner : corners) {
                    output.put(output.position(), input, (quad + corner) * stride, WorldProtocol.STRIDE);
                    output.position(output.position() + WorldProtocol.STRIDE);
                }
                quad += 4;
            }
            return true;
        }
        @Override public void close() { if (!released) { rendered.release(); released = true; } }
    }
}
