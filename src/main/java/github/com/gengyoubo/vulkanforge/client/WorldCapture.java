package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
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
import static org.lwjgl.opengl.GL11.*;

/** Render-thread capture of vanilla baked solid geometry, bounded to 3x3x5 sections. */
final class WorldCapture {
    private static final AtomicLong EPOCHS = new AtomicLong();
    private final Map<Long, byte[]> sent = new HashMap<>();
    private final BufferBuilder builder = new BufferBuilder(262144);
    private ClientLevel level;
    private long epoch, nextBuild;
    private int cursor;
    private boolean atlasSent;

    long epoch() { return epoch; }
    int sections() { return sent.size(); }
    void invalidate() { level = null; }

    void update(RenderLevelStageEvent event, OffscreenBridge bridge) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (level != mc.level) {
            level = mc.level;
            epoch = EPOCHS.incrementAndGet();
            sent.clear(); cursor = 0; atlasSent = false;
            bridge.reset(epoch);
            VulkanForge.LOGGER.info("Vulkan world capture reset: epoch={}, dimension={}", epoch, level.dimension().location());
        }
        if (!atlasSent) { atlasSent = bridge.send(captureAtlas(mc)); if (!atlasSent) return; }
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
        byte[] vertices = captureSection(mc, selected);
        if (!Arrays.equals(sent.get(selected.asLong()), vertices) && bridge.send(new WorldProtocol.Mesh(epoch, selected.asLong(), selected.minBlockX(), selected.minBlockY(), selected.minBlockZ(), vertices))) {
            sent.put(selected.asLong(), vertices);
            if (sent.size() == 1 || sent.size() % 15 == 0) VulkanForge.LOGGER.info("Vulkan world mesh: sections={}, lastVertices={}", sent.size(), vertices.length / WorldProtocol.STRIDE);
        }
    }

    private WorldProtocol.Atlas captureAtlas(Minecraft mc) {
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).bind();
            int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (width < 1 || height < 1 || (long) width * height * 4 > WorldProtocol.MAX_ATLAS_BYTES) throw new IllegalStateException("Block atlas exceeds bridge limit");
            try (NativeImage image = new NativeImage(width, height, false)) {
                image.downloadTexture(0, false);
                int[] pixels = image.getPixelsRGBA();
                byte[] rgba = new byte[pixels.length * 4];
                for (int i = 0; i < pixels.length; i++) {
                    int color = pixels[i];
                    for (int component = 0; component < 4; component++) rgba[i * 4 + component] = (byte) (color >>> (component * 8));
                }
                VulkanForge.LOGGER.info("Captured Vulkan block atlas: {}x{}", width, height);
                return new WorldProtocol.Atlas(epoch, width, height, rgba);
            }
        } finally { GlStateManager._bindTexture(previous); }
    }

    private byte[] captureSection(Minecraft mc, SectionPos section) {
        LevelChunk chunk = level.getChunk(section.x(), section.z());
        LevelChunkSection states = chunk.getSection(level.getSectionIndexFromSectionY(section.y()));
        if (states.hasOnlyAir()) return new byte[0];
        var modelData = level.getModelDataManager().getAt(new ChunkPos(section.x(), section.z()));
        PoseStack poses = new PoseStack();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        ModelBlockRenderer.enableCaching();
        BufferBuilder.RenderedBuffer rendered = null;
        try {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var state = states.getBlockState(x, y, z);
                if (state.getRenderShape() != RenderShape.MODEL) continue;
                pos.set(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z);
                var model = mc.getBlockRenderer().getBlockModel(state);
                ModelData data = model.getModelData(level, pos, state, modelData.getOrDefault(pos, ModelData.EMPTY));
                random.setSeed(state.getSeed(pos));
                if (!model.getRenderTypes(state, random, data).contains(RenderType.solid())) continue;
                poses.pushPose(); poses.translate(x, y, z);
                mc.getBlockRenderer().renderBatched(state, pos, level, poses, builder, true, random, data, RenderType.solid());
                poses.popPose();
            }
            rendered = builder.end();
            int vertices = rendered.drawState().vertexCount();
            int stride = DefaultVertexFormat.BLOCK.getVertexSize();
            if (vertices % 4 != 0 || (long) vertices / 4 * 6 * WorldProtocol.STRIDE > WorldProtocol.MAX_MESH_BYTES) throw new IllegalStateException("Invalid or oversized solid section mesh");
            ByteBuffer input = rendered.vertexBuffer().order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer output = ByteBuffer.allocate(vertices / 4 * 6 * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
            int[] triangles = {0, 1, 2, 2, 3, 0};
            for (int quad = 0; quad < vertices; quad += 4) for (int corner : triangles) {
                int offset = (quad + corner) * stride;
                output.putFloat(input.getFloat(offset)).putFloat(input.getFloat(offset + 4)).putFloat(input.getFloat(offset + 8));
                float light = 0.25f + 0.75f * Math.min(1f, Math.max(Short.toUnsignedInt(input.getShort(offset + 24)), Short.toUnsignedInt(input.getShort(offset + 26))) / 240f);
                for (int c = 0; c < 3; c++) output.put((byte) (Byte.toUnsignedInt(input.get(offset + 12 + c)) * light));
                output.put(input.get(offset + 15));
                output.putFloat(input.getFloat(offset + 16)).putFloat(input.getFloat(offset + 20));
            }
            return output.array();
        } finally {
            if (rendered != null) rendered.release();
            else builder.discard();
            ModelBlockRenderer.clearCache();
        }
    }
}
