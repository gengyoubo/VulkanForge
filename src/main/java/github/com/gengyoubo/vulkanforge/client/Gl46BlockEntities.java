package github.com.gengyoubo.vulkanforge.client;

import static github.com.gengyoubo.vulkanforge.client.GlCompat.glCreateVertexArrays;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glDeleteVertexArrays;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glEnableVertexArrayAttrib;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glMultiDrawElementsIndirect;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayAttribBinding;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayAttribFormat;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayElementBuffer;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayVertexBuffer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BrightnessCombiner;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL46C.*;

/**
 * Static block-entity meshes for the GL 4.6 entity pass (P0). Instead of letting the renderer rebuild the same cube vertices
 * every frame, a renderer is run once with an identity pose into a private buffer source; the block-local mesh of each
 * RenderType is kept in a device-local arena together with the GL state that RenderType sets up. Every frame each block
 * entity then only contributes an instance (PoseStack pose and normal matrices, light, overlay), and all instances of a mesh
 * part are one instanced indirect draw.
 * <p>
 * Only renderers whose output is fully determined by a cache key are taken, and only in states that do not animate:
 * vanilla's {@link ChestRenderer} itself (not subclasses), keyed by BlockState and block-entity class, while the lid is closed.
 * Light is computed exactly as ChestRenderer computes it (BrightnessCombiner over a double chest). Everything else, and any
 * chest that is opening or closing, is rendered by vanilla through the normal capture path.
 */
final class Gl46BlockEntities implements AutoCloseable {
    private static final int STRIDE = 36, INSTANCE = 128, COMMAND = 20, MAX_MESHES = 1024, MAX_QUADS = 1 << 16;

    private record MeshKey(BlockState state, Class<?> type) { }
    /** One RenderType of a cached mesh; instances accumulate per batch. */
    private static final class Part {
        final Gl46Entities.Key key;
        final ShaderInstance shader;
        final int variant, firstVertex, quads;
        float[] data = new float[INSTANCE / 4 * 4];
        int count, record = -1;
        Part(Gl46Entities.Key key, ShaderInstance shader, int variant, int firstVertex, int quads) {
            this.key = key; this.shader = shader; this.variant = variant; this.firstVertex = firstVertex; this.quads = quads;
        }
    }

    private final Gl46Entities owner;
    /** Empty list: this key could not be cached (unsupported RenderType or state); vanilla renders it. */
    private final Map<MeshKey, List<Part>> meshes = new HashMap<>();
    private final List<Part> active = new ArrayList<>();
    private final List<BufferBuilder> builders = new ArrayList<>();
    private final float[] pose = new float[16], normal = new float[9];
    private Gl46Programs.Program program;
    private Gl46Buffers.Arena arena;
    private Gl46Buffers.Uploader uploads;
    private Gl46Buffers.Ring instances;
    private int vao, vaoBuffer;
    private long instancesDrawn, instancedDraws, animated, uncacheable, recordings, cachedNanos, cachedCalls;

    Gl46BlockEntities(Gl46Entities owner, int quadIndices) {
        this.owner = owner;
        try {
            program = Gl46Programs.load("entity_instanced.vert", "entity.frag");
            arena = new Gl46Buffers.Arena("block entity mesh", STRIDE, 1 << 14, 32L << 20);
            uploads = new Gl46Buffers.Uploader(1 << 20, 2);
            instances = new Gl46Buffers.Ring(INSTANCE << 12, 4);
            vao = glCreateVertexArrays();
            glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
            glVertexArrayAttribFormat(vao, 1, 4, GL_UNSIGNED_BYTE, true, 12);
            glVertexArrayAttribFormat(vao, 2, 2, GL_FLOAT, false, 16);
            glVertexArrayAttribFormat(vao, 5, 3, GL_BYTE, true, 32);
            for (int attribute : new int[]{0, 1, 2, 5}) { glVertexArrayAttribBinding(vao, attribute, 0); glEnableVertexArrayAttrib(vao, attribute); }
            glVertexArrayElementBuffer(vao, quadIndices);
            Gl46Context.report("BLOCK_ENTITY_INIT shaders=" + (program.spirv() ? "SPIRV" : "GLSL"));
        } catch (Throwable error) { close(); throw error; }
    }

    /** True when the block entity became an instance; false: vanilla must render it. */
    boolean render(BlockEntityRenderer<?> renderer, BlockEntity entity, float partial, PoseStack stack, int light, int overlay) {
        if (renderer.getClass() != ChestRenderer.class || entity.getLevel() == null || !(entity instanceof LidBlockEntity lid)) return false;
        long start = System.nanoTime();
        BlockState state = entity.getBlockState();
        if (!(state.getBlock() instanceof AbstractChestBlock<?> block)) return false;
        // ChestRenderer's own inputs: openness and light through the double-chest combiner.
        DoubleBlockCombiner.NeighborCombineResult<? extends ChestBlockEntity> combined = block.combine(state, entity.getLevel(), entity.getBlockPos(), true);
        if (combined.apply(ChestBlock.opennessCombiner(lid)).get(partial) > 0) { animated++; return false; }
        int combinedLight = combined.apply(new BrightnessCombiner<>()).applyAsInt(light);
        MeshKey key = new MeshKey(state, entity.getClass());
        List<Part> parts = meshes.get(key);
        if (parts == null) {
            if (meshes.size() >= MAX_MESHES) return false;
            parts = record(renderer, entity, partial, overlay);
            meshes.put(key, parts);
        }
        if (parts.isEmpty()) { uncacheable++; return false; }
        stack.last().pose().get(pose);
        stack.last().normal().get(normal);
        for (Part part : parts) add(part, combinedLight, overlay);
        cachedNanos += System.nanoTime() - start; cachedCalls++;
        return true;
    }

    private void add(Part part, int light, int overlay) {
        if (part.record < 0) {
            // Same values vanilla would upload for this RenderType's shader at this point of the frame.
            part.record = owner.record(part.shader, part.variant);
            if (part.record < 0) throw new IllegalStateException("Entity record ring exhausted");
            active.add(part);
        }
        int floats = INSTANCE / 4;
        if (part.data.length < (part.count + 1) * floats) part.data = Arrays.copyOf(part.data, part.data.length * 2);
        int at = part.count * floats;
        System.arraycopy(pose, 0, part.data, at, 16);
        for (int column = 0; column < 3; column++) System.arraycopy(normal, column * 3, part.data, at + 16 + column * 4, 3);
        part.data[at + 28] = Float.intBitsToFloat(light); part.data[at + 29] = Float.intBitsToFloat(overlay);
        part.data[at + 30] = Float.intBitsToFloat(part.record / Gl46Entities.RECORD);
        part.count++;
    }

    /** Runs the renderer once in block space into private builders; each RenderType becomes a part with its captured GL state. */
    private List<Part> record(BlockEntityRenderer<?> renderer, BlockEntity entity, float partial, int overlay) {
        recordings++;
        Map<RenderType, BufferBuilder> used = new LinkedHashMap<>();
        MultiBufferSource source = type -> used.computeIfAbsent(type, ignored -> {
            if (builders.size() <= used.size()) builders.add(new BufferBuilder(1 << 12));
            BufferBuilder builder = builders.get(used.size());
            builder.begin(type.mode(), type.format());
            return builder;
        });
        @SuppressWarnings({"unchecked", "rawtypes"}) BlockEntityRenderer<BlockEntity> raw = (BlockEntityRenderer) renderer;
        raw.render(entity, partial, new PoseStack(), source, 0, overlay);
        List<Part> parts = new ArrayList<>();
        boolean supported = true;
        for (var entry : used.entrySet()) {
            BufferBuilder.RenderedBuffer rendered = entry.getValue().endOrDiscardIfEmpty();
            if (rendered == null) continue;
            try {
                BufferBuilder.DrawState drawState = rendered.drawState();
                if (!supported || drawState.format() != DefaultVertexFormat.NEW_ENTITY || drawState.mode() != VertexFormat.Mode.QUADS
                        || !drawState.sequentialIndex() || drawState.vertexCount() / 4 > MAX_QUADS) { supported = false; continue; }
                RenderType type = entry.getKey();
                type.setupRenderState();
                try {
                    ShaderInstance shader = RenderSystem.getShader();
                    Integer variant = shader == null ? null : Gl46Entities.variant(shader);
                    if (variant == null) { supported = false; continue; }
                    ShaderBlendState.apply(shader);
                    Gl46Entities.Key key = Gl46Entities.key();
                    // Instances of one part are drawn as a single unordered group: only opaque state qualifies.
                    if (key == null || key.blend()) { supported = false; continue; }
                    ByteBuffer vertices = rendered.vertexBuffer();
                    byte[] bytes = new byte[drawState.vertexCount() * STRIDE];
                    vertices.duplicate().get(bytes);
                    int first = arena.allocate(drawState.vertexCount());
                    arena.upload(first, uploads, bytes);
                    parts.add(new Part(key, shader, variant, first, drawState.vertexCount() / 4));
                } finally { type.clearRenderState(); }
            } finally { rendered.release(); }
        }
        uploads.flush();
        if (!supported) {
            for (Part part : parts) arena.free(part.firstVertex, part.quads * 4);
            Gl46Context.report("BLOCK_ENTITY_UNCACHEABLE " + entity.getType() + " " + entity.getBlockState());
            return List.of();
        }
        return parts;
    }

    /** Instanced commands this flush will draw. */
    int pendingCommands() { return active.size(); }

    /**
     * Writes instance data and one DrawElementsIndirectCommand per active part at {@code commands}+{@code offset}, then draws them
     * grouped by GL state. Called by the owner's flush with GL state already saved; leaves its own program bound.
     */
    void draw(Gl46Buffers.Ring commands, int offset) {
        if (active.isEmpty()) return;
        active.sort(Comparator.comparingInt(part -> part.key.hashCode()));
        ByteBuffer out = commands.mapped;
        for (int i = 0; i < active.size(); i++) {
            Part part = active.get(i);
            int bytes = part.count * INSTANCE;
            int base = instances.allocate(bytes, INSTANCE);
            if (base < 0) { instances.submit(); base = instances.allocate(bytes, INSTANCE); }
            if (base < 0) throw new IllegalStateException("Block entity instance ring too small for " + part.count + " instances");
            long address = MemoryUtil.memAddress(instances.mapped) + base;
            for (int f = 0; f < part.count * INSTANCE / 4; f++) MemoryUtil.memPutFloat(address + f * 4L, part.data[f]);
            int at = offset + i * COMMAND;
            out.putInt(at, part.quads * 6).putInt(at + 4, part.count).putInt(at + 8, 0).putInt(at + 12, part.firstVertex).putInt(at + 16, base / INSTANCE);
            instancesDrawn += part.count;
        }
        if (vaoBuffer != arena.buffer()) { vaoBuffer = arena.buffer(); glVertexArrayVertexBuffer(vao, 0, vaoBuffer, 0, STRIDE); }
        glUseProgram(program.id());
        glBindVertexArray(vao);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, instances.buffer);
        Gl46Entities.Key applied = null;
        for (int start = 0; start < active.size(); ) {
            int end = start + 1;
            while (end < active.size() && active.get(end).key.equals(active.get(start).key)) end++;
            Gl46Entities.apply(applied, active.get(start).key); applied = active.get(start).key;
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset + (long) start * COMMAND, end - start, COMMAND);
            instancedDraws++;
            start = end;
        }
        for (Part part : active) { part.count = 0; part.record = -1; }
        active.clear();
        instances.submit();
    }

    /** Resource reload: atlases and shaders may have changed under every cached mesh. */
    void invalidate() {
        for (Part part : active) { part.count = 0; part.record = -1; }
        active.clear();
        for (List<Part> parts : meshes.values()) for (Part part : parts) arena.free(part.firstVertex, part.quads * 4);
        meshes.clear();
    }

    /** {cached meshes, recordings, instances drawn, instanced draws, animated (vanilla), uncacheable (vanilla), cached-path ns per call}. */
    long[] stats() {
        return new long[]{meshes.size(), recordings, instancesDrawn, instancedDraws, animated, uncacheable, cachedCalls == 0 ? 0 : cachedNanos / cachedCalls};
    }

    @Override public void close() {
        if (arena != null) Gl46Context.report("BLOCK_ENTITY_CLOSE {meshes,recordings,instances,instancedDraws,animated,uncacheable,nsPerCall}=" + Arrays.toString(stats()));
        active.clear(); meshes.clear();
        if (program != null) glDeleteProgram(program.id());
        program = null;
        for (AutoCloseable resource : new AutoCloseable[]{arena, uploads, instances}) {
            try { if (resource != null) resource.close(); } catch (Exception ignored) { }
        }
        arena = null; uploads = null; instances = null;
        if (vao != 0) glDeleteVertexArrays(vao);
        vao = 0;
    }
}
