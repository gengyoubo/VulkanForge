package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL46C.*;

/**
 * OpenGL 4.6 entity and block-entity pass (issue #4, P0). Inside the ENTITIES window (after this frame's GL 4.6 terrain until
 * AFTER_BLOCK_ENTITIES) each supported RenderType batch is copied into persistent-mapped vertex/index rings and a 256-byte
 * per-draw record, instead of vanilla's per-draw buffer orphaning and uniform uploads. At flush, opaque draws are grouped by
 * GL state and textures (blended ones keep their order) and each group becomes one glMultiDrawElementsIndirect; every command's
 * baseInstance indexes its record. Anything else flushes the pending draws first and is then drawn by vanilla.
 */
final class Gl46Entities implements AutoCloseable {
    private static final int STRIDE = 36, RECORD = 256, COMMAND = 20;
    /** Fragment variant per vanilla shader (entity.frag); all use DefaultVertexFormat.NEW_ENTITY. */
    private static final Map<String, Integer> VARIANTS = Map.of(
            "rendertype_entity_cutout", 0, "rendertype_entity_cutout_no_cull", 0, "rendertype_entity_cutout_no_cull_z_offset", 0,
            "rendertype_entity_translucent", 0, "rendertype_entity_smooth_cutout", 0, "rendertype_entity_solid", 1,
            "rendertype_entity_translucent_cull", 2, "rendertype_armor_cutout_no_cull", 2, "rendertype_entity_no_outline", 3,
            "rendertype_entity_shadow", 4);

    /** Everything a run of draws must share: raw GL state plus the three sampler textures. */
    private record Key(boolean blend, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha, boolean depthTest, int depthFunc,
                       boolean depthMask, boolean cull, int texture, int overlay, int lightmap) { }
    /** quads: sequential QUADS drawn through the static quad index buffer; otherwise indices live in the index ring. */
    private record Draw(Key key, boolean quads, int record, int indices, int firstIndex, int baseVertex) { }
    /** Sequential batches up to this many quads use the static 0 1 2, 2 3 0 pattern instead of CPU-written indices. */
    private static final int MAX_QUADS = 1 << 16;

    private static Gl46Entities current;
    private Gl46Programs.Program program;
    private Gl46Buffers.Ring vertices, indices, records, commands;
    private int listVao, quadVao, quadIndices;
    private final List<Draw> pending = new ArrayList<>();
    private final Map<String, Long> fallbacks = new TreeMap<>();
    private final Matrix3f rotation = new Matrix3f();
    private final float[] values = new float[64];
    private boolean open;
    private long captured, flushes, multiDraws, splits, rejected, captureNanos, flushNanos;

    Gl46Entities() {
        try {
            program = Gl46Programs.load("entity.vert", "entity.frag");
            vertices = new Gl46Buffers.Ring(STRIDE << 18, 4); // segments stay multiples of the vertex stride (baseVertex)
            indices = new Gl46Buffers.Ring(4 << 20, 4);
            records = new Gl46Buffers.Ring(RECORD << 12, 4);
            commands = new Gl46Buffers.Ring(COMMAND << 12, 4);
            listVao = vertexArray(); quadVao = vertexArray();
            quadIndices = quadIndexBuffer();
            glVertexArrayElementBuffer(listVao, indices.buffer);
            glVertexArrayElementBuffer(quadVao, quadIndices);
            Gl46Context.report("ENTITY_INIT shaders=" + (program.spirv() ? "SPIRV" : "GLSL"));
        } catch (Throwable error) { close(); throw error; }
        current = this;
    }

    private int vertexArray() {
        int vao = glCreateVertexArrays();
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribFormat(vao, 1, 4, GL_UNSIGNED_BYTE, true, 12);
        glVertexArrayAttribFormat(vao, 2, 2, GL_FLOAT, false, 16);
        glVertexArrayAttribIFormat(vao, 3, 2, GL_SHORT, 24);
        glVertexArrayAttribIFormat(vao, 4, 2, GL_SHORT, 28);
        glVertexArrayAttribFormat(vao, 5, 3, GL_BYTE, true, 32);
        for (int attribute = 0; attribute < 6; attribute++) { glVertexArrayAttribBinding(vao, attribute, 0); glEnableVertexArrayAttrib(vao, attribute); }
        glVertexArrayVertexBuffer(vao, 0, vertices.buffer, 0, STRIDE);
        return vao;
    }
    private static int quadIndexBuffer() {
        ByteBuffer pattern = MemoryUtil.memAlloc(MAX_QUADS * 6 * 4);
        try {
            var ints = pattern.asIntBuffer();
            for (int quad = 0; quad < MAX_QUADS; quad++) { int base = quad * 4; ints.put(base).put(base + 1).put(base + 2).put(base + 2).put(base + 3).put(base); }
            int buffer = glCreateBuffers();
            glNamedBufferStorage(buffer, pattern, 0);
            return buffer;
        } finally { MemoryUtil.memFree(pattern); }
    }

    /** EntityDraws hook for every RenderType.end / immediate draw: true when this pass took the draw. */
    static boolean take(BufferBuilder.RenderedBuffer rendered) {
        Gl46Entities pass = current;
        if (pass == null || !pass.open || !RenderSystem.isOnRenderThread()) return false;
        long start = System.nanoTime();
        try {
            if (pass.capture(rendered)) return true;
            pass.split();
            return false;
        } finally { long spent = System.nanoTime() - start; pass.captureNanos += spent; pass.captureTimer.add(spent); }
    }

    /** After this frame's GL 4.6 opaque terrain: entities and block entities come next. */
    void begin() { pending.clear(); open = !Minecraft.useShaderTransparency(); }
    /** AFTER_BLOCK_ENTITIES. */
    void end() { flush(); open = false; captureTimer.frame(); flushTimer.frame(); }
    final Gl46Timer captureTimer = new Gl46Timer(), flushTimer = new Gl46Timer();
    /** {captured, flushes, multiDraws, splits, rejected, captureNanos (incl. split flushes), flushNanos}. */
    long[] stats() { return new long[]{captured, flushes, multiDraws, splits, rejected, captureNanos, flushNanos}; }
    Map<String, Long> fallbacks() { return Collections.unmodifiableMap(fallbacks); }

    private boolean capture(BufferBuilder.RenderedBuffer rendered) {
        ShaderInstance shader = RenderSystem.getShader();
        BufferBuilder.DrawState state = rendered.drawState();
        Integer variant = shader == null ? null : VARIANTS.get(shader.getName());
        if (variant == null || state.format() != DefaultVertexFormat.NEW_ENTITY || state.indexOnly() || state.vertexCount() == 0
                || (state.mode() != VertexFormat.Mode.QUADS && state.mode() != VertexFormat.Mode.TRIANGLES)) return reject(shader, "shader/format");
        // Outside Fabulous mode every whitelisted RenderType writes the main target (begin() skips Fabulous), so the binding is
        // only queried when the state cache is not trusted or is being verified.
        if ((!GlStateCache.trusted() || GlStateCache.verifying())
                && glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != Minecraft.getInstance().getMainRenderTarget().frameBufferId) return reject(shader, "target");
        ShaderBlendState.apply(shader); // the blend change ShaderInstance.apply would make inside drawWithShader
        Key key = key();
        if (key == null) return reject(shader, "state");
        int vertexBytes = state.vertexCount() * STRIDE;
        boolean quads = state.mode() == VertexFormat.Mode.QUADS && state.sequentialIndex() && state.vertexCount() / 4 <= MAX_QUADS;
        int indexCount = state.mode() == VertexFormat.Mode.QUADS && state.sequentialIndex() ? state.vertexCount() / 4 * 6
                : state.sequentialIndex() ? state.vertexCount() : state.indexCount();
        int vertexOffset = allocate(vertices, vertexBytes, STRIDE), indexOffset = quads ? 0 : allocate(indices, indexCount * 4, 4), record = allocate(records, RECORD, RECORD);
        if (vertexOffset < 0 || indexOffset < 0 || record < 0) return reject(shader, "ring");
        MemoryUtil.memCopy(MemoryUtil.memAddress(rendered.vertexBuffer()), MemoryUtil.memAddress(vertices.mapped) + vertexOffset, vertexBytes);
        if (!quads) writeIndices(rendered, state, indexOffset, indexCount);
        writeRecord(shader, record, variant);
        pending.add(new Draw(key, quads, record, indexCount, indexOffset / 4, vertexOffset / STRIDE));
        rendered.release();
        captured++;
        return true;
    }

    /**
     * A ring that would wrap onto its own unsubmitted data first flushes the pending draws, which submits every ring. A request
     * larger than a segment grows the vertex or index ring (records are fixed-size).
     */
    private int allocate(Gl46Buffers.Ring ring, int bytes, int alignment) {
        if (bytes > ring.segmentSize() && ring != records) ring = grow(ring, bytes, alignment);
        if (ring == null) return -1;
        int offset = ring.allocate(bytes, alignment);
        if (offset < 0) { flush(); ring.submit(); offset = ring.allocate(bytes, alignment); }
        return offset;
    }

    private static final int MAX_SEGMENT = 64 << 20;
    /** Doubling segments (kept a multiple of {@code unit} so offsets stay aligned); null beyond MAX_SEGMENT. */
    private Gl46Buffers.Ring grow(Gl46Buffers.Ring ring, int bytes, int unit) {
        long size = ring.segmentSize();
        while (size < bytes) size *= 2;
        size = (size + unit - 1) / unit * unit;
        if (size > MAX_SEGMENT) return null;
        flush();
        Gl46Buffers.Ring replacement = new Gl46Buffers.Ring((int) size, 4);
        retired.add(ring.stats()); ring.close();
        if (ring == vertices) {
            vertices = replacement;
            for (int vao : new int[]{listVao, quadVao}) glVertexArrayVertexBuffer(vao, 0, vertices.buffer, 0, STRIDE);
        } else { indices = replacement; glVertexArrayElementBuffer(listVao, indices.buffer); }
        grows++;
        Gl46Context.report("ENTITY_RING_GROW " + (replacement == vertices ? "vertices" : "indices") + " segment=" + size);
        return replacement;
    }
    private final List<long[]> retired = new ArrayList<>();
    private long grows;

    private void writeIndices(BufferBuilder.RenderedBuffer rendered, BufferBuilder.DrawState state, int offset, int count) {
        ByteBuffer out = indices.mapped;
        if (state.sequentialIndex()) {
            if (state.mode() == VertexFormat.Mode.QUADS) {
                for (int quad = 0, at = offset; quad < count / 6; quad++) {
                    int base = quad * 4; // vanilla's quad order: 0 1 2, 2 3 0
                    out.putInt(at, base).putInt(at + 4, base + 1).putInt(at + 8, base + 2)
                            .putInt(at + 12, base + 2).putInt(at + 16, base + 3).putInt(at + 20, base); at += 24;
                }
            } else for (int i = 0; i < count; i++) out.putInt(offset + i * 4, i);
            return;
        }
        ByteBuffer source = rendered.indexBuffer();
        boolean shorts = state.indexType().bytes == 2;
        for (int i = 0; i < count; i++)
            out.putInt(offset + i * 4, shorts ? Short.toUnsignedInt(source.getShort(source.position() + i * 2)) : source.getInt(source.position() + i * 4));
    }

    /** Same uniform values vanilla's ShaderInstance would upload for this draw. */
    private void writeRecord(ShaderInstance shader, int offset, int variant) {
        Arrays.fill(values, 0);
        RenderSystem.getProjectionMatrix().get(values, 0);
        RenderSystem.getModelViewMatrix().get(values, 16);
        float[] columns = new float[9];
        rotation.set(RenderSystem.getInverseViewRotationMatrix()).get(columns);
        for (int column = 0; column < 3; column++) System.arraycopy(columns, column * 3, values, 32 + column * 4, 3);
        System.arraycopy(RenderSystem.getShaderColor(), 0, values, 44, 4);
        RenderSystem.setupShaderLights(shader);
        if (shader.LIGHT0_DIRECTION != null) shader.LIGHT0_DIRECTION.getFloatBuffer().get(0, values, 48, 3);
        if (shader.LIGHT1_DIRECTION != null) shader.LIGHT1_DIRECTION.getFloatBuffer().get(0, values, 52, 3);
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, values, 56, 4);
        values[60] = RenderSystem.getShaderFogStart(); values[61] = RenderSystem.getShaderFogEnd();
        values[62] = RenderSystem.getShaderFogShape().getIndex(); values[63] = variant;
        ByteBuffer out = records.mapped;
        for (int i = 0; i < values.length; i++) out.putFloat(offset + i * 4, values[i]);
    }

    /** Null when GL 4.6 cannot reproduce the state exactly (offset, scissor, logic op, partial color mask, odd blend/cull). */
    private static Key key() {
        GlStateCache.Snapshot state = GlStateCache.snapshot();
        if (state.polygonOffset() || state.scissor() || state.colorLogic() || !state.colorMaskAll()) return null;
        // Neither is cached by vanilla, and vanilla never changes them for these shaders (blend "add", back faces, CCW).
        if (!GlStateCache.trusted() || GlStateCache.verifying()) {
            if (state.blend() && (glGetInteger(GL_BLEND_EQUATION_RGB) != GL_FUNC_ADD || glGetInteger(GL_BLEND_EQUATION_ALPHA) != GL_FUNC_ADD)) return null;
            if (state.cull() && (glGetInteger(GL_CULL_FACE_MODE) != GL_BACK || glGetInteger(GL_FRONT_FACE) != GL_CCW)) return null;
        }
        return new Key(state.blend(), state.srcRGB(), state.dstRGB(), state.srcAlpha(), state.dstAlpha(), state.depthTest(), state.depthFunc(),
                state.depthMask(), state.cull(), RenderSystem.getShaderTexture(0), RenderSystem.getShaderTexture(1), RenderSystem.getShaderTexture(2));
    }

    private boolean reject(ShaderInstance shader, String reason) {
        rejected++;
        fallbacks.merge((shader == null ? "null" : shader.getName()) + ":" + reason, 1L, Long::sum);
        return false;
    }

    /** Vanilla draws something itself next: everything captured before it is drawn first. */
    private void split() { if (!pending.isEmpty()) { splits++; flush(); } }

    private void flush() {
        if (pending.isEmpty()) return;
        long start = System.nanoTime();
        try { submit(); } finally { long spent = System.nanoTime() - start; flushNanos += spent; flushTimer.add(spent); }
    }

    private void submit() {
        List<Draw> ordered = order();
        int offset = commands.allocate(ordered.size() * COMMAND, 4);
        if (offset < 0) { commands.submit(); offset = commands.allocate(ordered.size() * COMMAND, 4); }
        if (offset < 0) throw new IllegalStateException("Entity command ring too small for " + ordered.size() + " draws");
        ByteBuffer out = commands.mapped;
        for (int i = 0; i < ordered.size(); i++) {
            // baseInstance carries the record index, so records need not be contiguous and the SSBO is bound once.
            Draw draw = ordered.get(i); int at = offset + i * COMMAND;
            out.putInt(at, draw.indices()).putInt(at + 4, 1).putInt(at + 8, draw.firstIndex()).putInt(at + 12, draw.baseVertex()).putInt(at + 16, draw.record() / RECORD);
        }
        long t0 = System.nanoTime();
        Gl46State saved = new Gl46State();
        long t1 = System.nanoTime();
        try {
            glUseProgram(program.id());
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands.buffer);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, records.buffer);
            int boundVao = 0;
            Key applied = null;
            for (int start = 0; start < ordered.size(); ) {
                Draw first = ordered.get(start);
                int end = start + 1;
                while (end < ordered.size() && ordered.get(end).key().equals(first.key()) && ordered.get(end).quads() == first.quads()) end++;
                int vao = first.quads() ? quadVao : listVao;
                if (vao != boundVao) { glBindVertexArray(vao); boundVao = vao; }
                apply(applied, first.key()); applied = first.key();
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset + (long) start * COMMAND, end - start, COMMAND);
                multiDraws++;
                start = end;
            }
        } finally {
            long t2 = System.nanoTime();
            saved.close();
            long t3 = System.nanoTime();
            phases[0] += t1 - t0; phases[1] += t2 - t1; phases[2] += t3 - t2;
        }
        long t4 = System.nanoTime();
        vertices.submit(); indices.submit(); records.submit(); commands.submit();
        phases[3] += System.nanoTime() - t4;
        pending.clear();
        flushes++;
    }

    /**
     * Non-blended draws first, grouped by key in first-appearance order (depth testing makes their order irrelevant), then the
     * blended draws in capture order. Vanilla's shared BufferSource ends a batch at every RenderType switch (cow, shadow, cow,
     * ...); this turns those alternations into one run per key. Blended draws now see all opaque depth, the usual order.
     */
    private List<Draw> order() {
        Map<Key, List<Draw>> opaque = new LinkedHashMap<>();
        List<Draw> blended = new ArrayList<>(), result = new ArrayList<>(pending.size());
        for (Draw draw : pending) {
            if (draw.key().blend()) blended.add(draw);
            else opaque.computeIfAbsent(draw.key(), key -> new ArrayList<>()).add(draw);
        }
        for (List<Draw> group : opaque.values()) {
            // Within a key, sequential-quad draws and index-list draws form at most two runs.
            for (Draw draw : group) if (draw.quads()) result.add(draw);
            for (Draw draw : group) if (!draw.quads()) result.add(draw);
        }
        result.addAll(blended);
        reordered += pending.size() - blended.size();
        return result;
    }
    private long reordered;
    /** Flush breakdown in ns: {state snapshot, draws, state restore, ring fences}. */
    private final long[] phases = new long[4];
    long[] phases() { return phases.clone(); }

    /** Only what differs from the previous run ({@code last} null: everything). */
    private static void apply(Key last, Key key) {
        if (last == null || last.blend() != key.blend()) Gl46State.toggle(GL_BLEND, key.blend());
        if (key.blend() && (last == null || last.srcRGB() != key.srcRGB() || last.dstRGB() != key.dstRGB() || last.srcAlpha() != key.srcAlpha() || last.dstAlpha() != key.dstAlpha()))
            glBlendFuncSeparate(key.srcRGB(), key.dstRGB(), key.srcAlpha(), key.dstAlpha());
        if (last == null || last.depthTest() != key.depthTest()) Gl46State.toggle(GL_DEPTH_TEST, key.depthTest());
        if (last == null || last.depthFunc() != key.depthFunc()) glDepthFunc(key.depthFunc());
        if (last == null || last.depthMask() != key.depthMask()) glDepthMask(key.depthMask());
        if (last == null || last.cull() != key.cull()) Gl46State.toggle(GL_CULL_FACE, key.cull());
        if (last == null || last.texture() != key.texture()) glBindTextureUnit(0, key.texture());
        if (last == null || last.overlay() != key.overlay()) glBindTextureUnit(1, key.overlay());
        if (last == null || last.lightmap() != key.lightmap()) glBindTextureUnit(2, key.lightmap());
    }

    @Override public void close() {
        if (current == this) current = null;
        open = false; pending.clear();
        if (vertices != null) Gl46Context.report("ENTITY_CLOSE captured=" + captured + " flushes=" + flushes + " multiDraws=" + multiDraws + " reordered=" + reordered
                + " splits=" + splits + " rejected=" + rejected + " captureNanos=" + captureNanos + " flushNanos=" + flushNanos + " fallbacks=" + fallbacks
                + " rings{fences,waits,waitNanos,oversized} vertices=" + Arrays.toString(vertices.stats()) + " indices=" + Arrays.toString(indices.stats())
                + " records=" + Arrays.toString(records.stats()) + " commands=" + Arrays.toString(commands.stats()) + " grows=" + grows
                + " retired=" + retired.stream().map(Arrays::toString).toList() + " captureCpu{" + captureTimer + "} flushCpu{" + flushTimer + "}");
        if (program != null) glDeleteProgram(program.id());
        program = null;
        for (AutoCloseable ring : new AutoCloseable[]{vertices, indices, records, commands}) {
            try { if (ring != null) ring.close(); } catch (Exception ignored) { }
        }
        vertices = indices = records = commands = null;
        for (int vao : new int[]{listVao, quadVao}) if (vao != 0) glDeleteVertexArrays(vao);
        if (quadIndices != 0) glDeleteBuffers(quadIndices);
        listVao = quadVao = quadIndices = 0;
    }
}
