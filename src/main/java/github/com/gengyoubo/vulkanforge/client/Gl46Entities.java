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
 * per-draw record, instead of vanilla's per-draw buffer orphaning and uniform uploads. At flush, consecutive draws sharing GL
 * state and textures become one glMultiDrawElementsIndirect whose gl_DrawID indexes the records. Anything else flushes the
 * pending draws first and is then drawn by vanilla, so painter's order is unchanged.
 */
final class Gl46Entities implements AutoCloseable {
    private static final int STRIDE = 36, RECORD = 256, COMMAND = 20;
    /** Fragment variant per vanilla shader (entity.frag); all use DefaultVertexFormat.NEW_ENTITY. */
    private static final Map<String, Integer> VARIANTS = Map.of(
            "rendertype_entity_cutout", 0, "rendertype_entity_cutout_no_cull", 0, "rendertype_entity_cutout_no_cull_z_offset", 0,
            "rendertype_entity_translucent", 0, "rendertype_entity_smooth_cutout", 0, "rendertype_entity_solid", 1,
            "rendertype_entity_translucent_cull", 2, "rendertype_armor_cutout_no_cull", 2, "rendertype_entity_no_outline", 3);

    /** Everything a run of draws must share: raw GL state plus the three sampler textures. */
    private record Key(boolean blend, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha, boolean depthTest, int depthFunc,
                       boolean depthMask, boolean cull, int texture, int overlay, int lightmap) { }
    private record Draw(Key key, int record, int indices, int firstIndex, int baseVertex) { }

    private static Gl46Entities current;
    private Gl46Programs.Program program;
    private Gl46Buffers.Ring vertices, indices, records, commands;
    private int vao;
    private final List<Draw> pending = new ArrayList<>();
    private final Map<String, Long> fallbacks = new TreeMap<>();
    private final Matrix3f rotation = new Matrix3f();
    private final float[] values = new float[64];
    private boolean open;
    private long captured, flushes, multiDraws, splits, rejected;

    Gl46Entities() {
        try {
            program = Gl46Programs.load("entity.vert", "entity.frag");
            vertices = new Gl46Buffers.Ring(STRIDE << 18, 4); // segments stay multiples of the vertex stride (baseVertex)
            indices = new Gl46Buffers.Ring(4 << 20, 4);
            records = new Gl46Buffers.Ring(RECORD << 12, 4);
            commands = new Gl46Buffers.Ring(COMMAND << 12, 4);
            vao = glCreateVertexArrays();
            glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
            glVertexArrayAttribFormat(vao, 1, 4, GL_UNSIGNED_BYTE, true, 12);
            glVertexArrayAttribFormat(vao, 2, 2, GL_FLOAT, false, 16);
            glVertexArrayAttribIFormat(vao, 3, 2, GL_SHORT, 24);
            glVertexArrayAttribIFormat(vao, 4, 2, GL_SHORT, 28);
            glVertexArrayAttribFormat(vao, 5, 3, GL_BYTE, true, 32);
            for (int attribute = 0; attribute < 6; attribute++) { glVertexArrayAttribBinding(vao, attribute, 0); glEnableVertexArrayAttrib(vao, attribute); }
            glVertexArrayVertexBuffer(vao, 0, vertices.buffer, 0, STRIDE);
            glVertexArrayElementBuffer(vao, indices.buffer);
            Gl46Context.report("ENTITY_INIT shaders=" + (program.spirv() ? "SPIRV" : "GLSL"));
        } catch (Throwable error) { close(); throw error; }
        current = this;
    }

    /** EntityDraws hook for every RenderType.end / immediate draw: true when this pass took the draw. */
    static boolean take(BufferBuilder.RenderedBuffer rendered) {
        Gl46Entities pass = current;
        if (pass == null || !pass.open || !RenderSystem.isOnRenderThread()) return false;
        if (pass.capture(rendered)) return true;
        pass.split();
        return false;
    }

    /** After this frame's GL 4.6 opaque terrain: entities and block entities come next. */
    void begin() { pending.clear(); open = true; }
    /** AFTER_BLOCK_ENTITIES. */
    void end() { flush(); open = false; }
    long[] stats() { return new long[]{captured, flushes, multiDraws, splits, rejected}; }
    Map<String, Long> fallbacks() { return Collections.unmodifiableMap(fallbacks); }

    private boolean capture(BufferBuilder.RenderedBuffer rendered) {
        ShaderInstance shader = RenderSystem.getShader();
        BufferBuilder.DrawState state = rendered.drawState();
        Integer variant = shader == null ? null : VARIANTS.get(shader.getName());
        if (variant == null || state.format() != DefaultVertexFormat.NEW_ENTITY || state.indexOnly() || state.vertexCount() == 0
                || (state.mode() != VertexFormat.Mode.QUADS && state.mode() != VertexFormat.Mode.TRIANGLES)) return reject(shader, "shader/format");
        if (glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != Minecraft.getInstance().getMainRenderTarget().frameBufferId) return reject(shader, "target");
        ShaderBlendState.apply(shader); // the blend change ShaderInstance.apply would make inside drawWithShader
        Key key = key();
        if (key == null) return reject(shader, "state");
        int vertexBytes = state.vertexCount() * STRIDE;
        int indexCount = state.mode() == VertexFormat.Mode.QUADS && state.sequentialIndex() ? state.vertexCount() / 4 * 6
                : state.sequentialIndex() ? state.vertexCount() : state.indexCount();
        int vertexOffset = allocate(vertices, vertexBytes, STRIDE), indexOffset = allocate(indices, indexCount * 4, 4), record = allocate(records, RECORD, RECORD);
        if (vertexOffset < 0 || indexOffset < 0 || record < 0) return reject(shader, "ring");
        MemoryUtil.memCopy(MemoryUtil.memAddress(rendered.vertexBuffer()), MemoryUtil.memAddress(vertices.mapped) + vertexOffset, vertexBytes);
        writeIndices(rendered, state, indexOffset, indexCount);
        writeRecord(shader, record, variant);
        pending.add(new Draw(key, record, indexCount, indexOffset / 4, vertexOffset / STRIDE));
        rendered.release();
        captured++;
        return true;
    }

    /** A ring that would wrap onto its own unsubmitted data first flushes the pending draws, which submits every ring. */
    private int allocate(Gl46Buffers.Ring ring, int bytes, int alignment) {
        int offset = ring.allocate(bytes, alignment);
        if (offset < 0 && !pending.isEmpty()) { flush(); offset = ring.allocate(bytes, alignment); }
        return offset;
    }

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
        if (glIsEnabled(GL_POLYGON_OFFSET_FILL) || glIsEnabled(GL_SCISSOR_TEST) || glIsEnabled(GL_COLOR_LOGIC_OP)) return null;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) if (mask.get(i) == 0) return null;
        }
        boolean blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE), depth = glIsEnabled(GL_DEPTH_TEST);
        if (blend && (glGetInteger(GL_BLEND_EQUATION_RGB) != GL_FUNC_ADD || glGetInteger(GL_BLEND_EQUATION_ALPHA) != GL_FUNC_ADD)) return null;
        if (cull && (glGetInteger(GL_CULL_FACE_MODE) != GL_BACK || glGetInteger(GL_FRONT_FACE) != GL_CCW)) return null;
        return new Key(blend, glGetInteger(GL_BLEND_SRC_RGB), glGetInteger(GL_BLEND_DST_RGB), glGetInteger(GL_BLEND_SRC_ALPHA), glGetInteger(GL_BLEND_DST_ALPHA),
                depth, glGetInteger(GL_DEPTH_FUNC), glGetBoolean(GL_DEPTH_WRITEMASK), cull,
                RenderSystem.getShaderTexture(0), RenderSystem.getShaderTexture(1), RenderSystem.getShaderTexture(2));
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
        int offset = commands.allocate(pending.size() * COMMAND, 4);
        if (offset < 0) { commands.submit(); offset = commands.allocate(pending.size() * COMMAND, 4); }
        if (offset < 0) throw new IllegalStateException("Entity command ring too small for " + pending.size() + " draws");
        ByteBuffer out = commands.mapped;
        for (int i = 0; i < pending.size(); i++) {
            Draw draw = pending.get(i); int at = offset + i * COMMAND;
            out.putInt(at, draw.indices()).putInt(at + 4, 1).putInt(at + 8, draw.firstIndex()).putInt(at + 12, draw.baseVertex()).putInt(at + 16, 0);
        }
        try (Gl46State ignored = new Gl46State()) {
            glUseProgram(program.id());
            glBindVertexArray(vao);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands.buffer);
            for (int start = 0; start < pending.size(); ) {
                Draw first = pending.get(start);
                int end = start + 1;
                // A run shares the key and has contiguous records, so one SSBO range serves gl_DrawID.
                while (end < pending.size() && pending.get(end).key().equals(first.key())
                        && pending.get(end).record() == first.record() + (end - start) * RECORD) end++;
                apply(first.key());
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, records.buffer, first.record(), (long) (end - start) * RECORD);
                glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset + (long) start * COMMAND, end - start, COMMAND);
                multiDraws++;
                start = end;
            }
        }
        vertices.submit(); indices.submit(); records.submit(); commands.submit();
        pending.clear();
        flushes++;
    }

    private static void apply(Key key) {
        Gl46State.toggle(GL_BLEND, key.blend());
        glBlendFuncSeparate(key.srcRGB(), key.dstRGB(), key.srcAlpha(), key.dstAlpha());
        Gl46State.toggle(GL_DEPTH_TEST, key.depthTest());
        glDepthFunc(key.depthFunc()); glDepthMask(key.depthMask());
        Gl46State.toggle(GL_CULL_FACE, key.cull());
        glBindTextureUnit(0, key.texture()); glBindTextureUnit(1, key.overlay()); glBindTextureUnit(2, key.lightmap());
    }

    @Override public void close() {
        if (current == this) current = null;
        open = false; pending.clear();
        if (vertices != null) Gl46Context.report("ENTITY_CLOSE captured=" + captured + " flushes=" + flushes + " multiDraws=" + multiDraws
                + " splits=" + splits + " rejected=" + rejected + " fallbacks=" + fallbacks);
        if (program != null) glDeleteProgram(program.id());
        program = null;
        for (AutoCloseable ring : new AutoCloseable[]{vertices, indices, records, commands}) {
            try { if (ring != null) ring.close(); } catch (Exception ignored) { }
        }
        vertices = indices = records = commands = null;
        if (vao != 0) glDeleteVertexArrays(vao);
        vao = 0;
    }
}
