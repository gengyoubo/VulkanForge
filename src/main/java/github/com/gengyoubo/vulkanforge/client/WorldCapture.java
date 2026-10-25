package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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

/** Render-thread capture of vanilla solid/cutout geometry, bounded to 3x3x5 sections. */
final class WorldCapture {
    private static final AtomicLong EPOCHS = new AtomicLong();
    private final Map<Long, byte[]> sent = new HashMap<>();
    private final BufferBuilder builder = new BufferBuilder(262144);
    private ClientLevel level;
    private long epoch, nextBuild;
    private int cursor, textureCursor;
    private boolean atlasSent;
    private TextureCapture.Snapshot atlasSnapshot;
    private byte[] lightSnapshot;
    private long nextTexturePoll;
    private long atlasPatches, lightUpdates;
    private int cutoutBlocks;
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
        SectionBuild(SectionPos section) {
            this.section = section;
            states = level.getChunk(section.x(), section.z()).getSection(level.getSectionIndexFromSectionY(section.y()));
            empty = states.hasOnlyAir();
            modelData = empty ? Map.of() : level.getModelDataManager().getAt(new ChunkPos(section.x(), section.z()));
            if (!empty) { builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK); bufferOpen = true; }
        }
    }
    private void cancelBuild() {
        if (build != null && build.bufferOpen) { builder.end().release(); builder.discard(); }
        build = null;
    }

    long epoch() { return epoch; }
    int sections() { return sent.size(); }
    long atlasPatches() { return atlasPatches; }
    long lightUpdates() { return lightUpdates; }
    int cutoutBlocks() { return cutoutBlocks; }
    void invalidate() { level = null; }
    void close() {
        cancelBuild(); atlasReadback.close(); lightReadback.close(); invalidate();
        if (buildSlices > 0) VulkanForge.LOGGER.info("Vulkan mesh capture slices={}, meanMs={}, maxMs={}", buildSlices,
                buildNanos / (buildSlices * 1_000_000.0), maxBuildNanos / 1_000_000.0);
        buildSlices = buildNanos = maxBuildNanos = 0;
    }
    void leave(OffscreenBridge bridge) {
        if (epoch != 0) { close(); epoch = 0; sent.clear(); atlasSent = false; bridge.reset(0); }
    }

    void update(RenderLevelStageEvent event, OffscreenBridge bridge) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (level != mc.level) {
            cancelBuild();
            atlasReadback.close(); lightReadback.close();
            level = mc.level;
            epoch = EPOCHS.incrementAndGet();
            sent.clear(); cursor = 0; atlasSent = false;
            atlasSnapshot = null; lightSnapshot = null; nextTexturePoll = 0; cutoutBlocks = 0;
            textureCursor = 0;
            bridge.reset(epoch);
            VulkanForge.LOGGER.info("Vulkan world capture reset: epoch={}, dimension={}", epoch, level.dimension().location());
        }
        if (!atlasSent) {
            var snapshot = TextureCapture.read(mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId());
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
        List<SectionPos> wanted = new ArrayList<>(45);
        for (int x = sx - 1; x <= sx + 1; x++) for (int z = sz - 1; z <= sz + 1; z++) {
            for (int y = Math.max(sy - 2, level.getMinSection()); y <= Math.min(sy + 2, level.getMaxSection() - 1); y++) wanted.add(SectionPos.of(x, y, z));
        }
        wanted.sort(Comparator.comparingDouble(p -> p.center().distToCenterSqr(position)));
        Set<Long> keys = new HashSet<>();
        for (SectionPos section : wanted) keys.add(section.asLong());
        Iterator<Long> old = sent.keySet().iterator();
        while (old.hasNext()) {
            long key = old.next();
            if (!keys.contains(key) && bridge.send(new WorldProtocol.Remove(epoch, key))) old.remove();
        }
        if (build != null && (!keys.contains(build.section.asLong()) || !level.hasChunk(build.section.x(), build.section.z()))) cancelBuild();
        if (build == null) {
            if (wanted.isEmpty() || System.nanoTime() < nextBuild) return;
            nextBuild = System.nanoTime() + 50_000_000L;
            SectionPos selected = null;
            for (SectionPos section : wanted) {
                if (!sent.containsKey(section.asLong()) && level.hasChunk(section.x(), section.z())) { selected = section; break; }
            }
            if (selected == null) selected = wanted.get(cursor++ % wanted.size());
            if (!level.hasChunk(selected.x(), selected.z())) {
                if (bridge.send(new WorldProtocol.Remove(epoch, selected.asLong()))) sent.remove(selected.asLong());
                return;
            }
            build = new SectionBuild(selected);
        }
        SectionPos selected = build.section;
        long start = System.nanoTime();
        byte[] vertices = captureSection(mc, build);
        long elapsed = System.nanoTime() - start; buildSlices++; buildNanos += elapsed; maxBuildNanos = Math.max(maxBuildNanos, elapsed);
        if (vertices == null) return;
        build = null;
        if (!Arrays.equals(sent.get(selected.asLong()), vertices) && bridge.send(new WorldProtocol.Mesh(epoch, selected.asLong(), selected.minBlockX(), selected.minBlockY(), selected.minBlockZ(), vertices))) {
            sent.put(selected.asLong(), vertices);
            if (sent.size() == 1 || sent.size() % 15 == 0) VulkanForge.LOGGER.info("Vulkan world mesh: sections={}, lastVertices={}", sent.size(), vertices.length / WorldProtocol.STRIDE);
        }
    }

    private boolean syncTextures(Minecraft mc, OffscreenBridge bridge) {
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

    private byte[] captureSection(Minecraft mc, SectionBuild job) {
        if (job.empty) return new byte[0];
        SectionPos section = job.section;
        var states = job.states; var modelData = job.modelData;
        var poses = job.poses; var random = job.random; var pos = job.pos;
        long deadline = System.nanoTime() + 1_000_000L;
        ModelBlockRenderer.enableCaching();
        BufferBuilder.RenderedBuffer rendered = null;
        try {
            while (job.next < 4096) {
                if (System.nanoTime() >= deadline) return null;
                int index = job.next++, x = index & 15, z = (index >>> 4) & 15, y = index >>> 8;
                var state = states.getBlockState(x, y, z);
                if (state.getRenderShape() != RenderShape.MODEL) continue;
                pos.set(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z);
                var model = mc.getBlockRenderer().getBlockModel(state);
                ModelData data = model.getModelData(level, pos, state, modelData.getOrDefault(pos, ModelData.EMPTY));
                random.setSeed(state.getSeed(pos));
                for (RenderType type : model.getRenderTypes(state, random, data)) {
                    if (type != RenderType.solid() && type != RenderType.cutout() && type != RenderType.cutoutMipped()) continue;
                    if (type != RenderType.solid()) cutoutBlocks++;
                    poses.pushPose(); poses.translate(x, y, z);
                    mc.getBlockRenderer().renderBatched(state, pos, level, poses, builder, true, random, data, type);
                    poses.popPose();
                }
            }
            rendered = builder.end();
            job.bufferOpen = false;
            int vertices = rendered.drawState().vertexCount();
            int stride = DefaultVertexFormat.BLOCK.getVertexSize();
            if (vertices % 4 != 0 || (long) vertices / 4 * 6 * WorldProtocol.STRIDE > WorldProtocol.MAX_MESH_BYTES) throw new IllegalStateException("Invalid or oversized solid section mesh");
            ByteBuffer input = rendered.vertexBuffer().order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer output = ByteBuffer.allocate(vertices / 4 * 6 * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
            int[] triangles = {0, 1, 2, 2, 3, 0};
            for (int quad = 0; quad < vertices; quad += 4) for (int corner : triangles) {
                int offset = (quad + corner) * stride;
                output.putFloat(input.getFloat(offset)).putFloat(input.getFloat(offset + 4)).putFloat(input.getFloat(offset + 8));
                output.putInt(input.getInt(offset + 12));
                output.putFloat(input.getFloat(offset + 16)).putFloat(input.getFloat(offset + 20));
                output.putShort(input.getShort(offset + 24)).putShort(input.getShort(offset + 26));
            }
            return output.array();
        } finally {
            if (rendered != null) rendered.release();
            ModelBlockRenderer.clearCache();
        }
    }
}
