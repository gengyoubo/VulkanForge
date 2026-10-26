package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vulkanforge.bridge.WorldProtocol;
import java.nio.ByteBuffer;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static dev.vulkanforge.bridge.WorldProtocol.STRIDE;
import static org.lwjgl.opengl.GL46C.*;

/**
 * OpenGL 4.6 terrain in the game's own context (issue #4). Section meshes live in one DSA vertex arena; a compute shader
 * frustum-tests the section SSBO and compacts DrawArraysIndirectCommands plus a GPU draw count, so all opaque sections
 * are one glMultiDrawArraysIndirectCount. Translucent sections are sorted on the CPU and drawn with one
 * glMultiDrawElementsIndirect. Uploads, per-frame uniforms and translucent commands use persistent-mapped rings.
 * Draws go straight into Minecraft's bound framebuffer and depth, so nothing has to be composited afterwards.
 */
final class Gl46Terrain implements WorldSink, AutoCloseable {
    static final int MAX_SECTIONS = 65535;
    private static final int SECTION_BYTES = 64, FRAME_BYTES = 144, COMMAND_BYTES = 20, RESORT_BUDGET = 16;

    private record Section(int slot, int x, int y, int z, int firstVertex, int vertices, Translucent translucent) { }

    /** Triangles stay in quad order (6 vertices per quad); sorting rewrites only this section's index range. */
    private static final class Translucent {
        final int firstVertex, firstIndex, quads;
        final float[] centers;
        double sortedX, sortedY, sortedZ;
        boolean sorted;
        Translucent(int firstVertex, int firstIndex, byte[] bytes) {
            this.firstVertex = firstVertex; this.firstIndex = firstIndex;
            quads = bytes.length / (6 * STRIDE);
            centers = new float[quads * 3];
            var data = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int[] corners = {0, 1, 2, 4}; // the four distinct quad corners among 0,1,2 / 2,3,0
            for (int quad = 0; quad < quads; quad++) for (int corner : corners) for (int axis = 0; axis < 3; axis++)
                centers[quad * 3 + axis] += data.getFloat((quad * 6 + corner) * STRIDE + axis * 4) * .25f;
        }
        int indices() { return quads * 6; }
        boolean stale(double x, double y, double z) {
            double dx = x - sortedX, dy = y - sortedY, dz = z - sortedZ;
            return !sorted || dx * dx + dy * dy + dz * dz > 1;
        }
    }

    private Gl46Programs.Program cull, opaque, translucent;
    private Gl46Buffers.Arena vertices, indices;
    private Gl46Buffers.Ring staging, frames, commands;
    private int sectionBuffer, drawBuffer, countBuffer, vao, atlasSampler, lightSampler, uboAlignment;
    private int vaoVertexBuffer, vaoIndexBuffer;
    private final Map<Long, Section> sections = new HashMap<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int slotCount, peakSections;
    private long epoch, frameCount, translucentFrames, translucentDraws, sorts, meshes;
    private WorldProtocol.Camera camera;
    private boolean receivedMesh, errorsChecked;

    Gl46Terrain() {
        try {
            cull = Gl46Programs.load("cull.comp");
            opaque = Gl46Programs.load("terrain.vert", "terrain.frag");
            translucent = Gl46Programs.load("terrain.vert", "terrain_translucent.frag");
            vertices = new Gl46Buffers.Arena("vertex", STRIDE, 1 << 18, 256L << 20);
            indices = new Gl46Buffers.Arena("index", 4, 1 << 18, 64L << 20);
            staging = new Gl46Buffers.Ring(WorldProtocol.MAX_MESH_BYTES, 4);
            frames = new Gl46Buffers.Ring(64 << 10, 4);
            commands = new Gl46Buffers.Ring(2 << 20, 3);
            sectionBuffer = Gl46Buffers.immutable((long) MAX_SECTIONS * SECTION_BYTES, GL_DYNAMIC_STORAGE_BIT);
            glClearNamedBufferData(sectionBuffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
            drawBuffer = Gl46Buffers.immutable((long) MAX_SECTIONS * 16, 0);
            countBuffer = Gl46Buffers.immutable(4, GL_DYNAMIC_STORAGE_BIT);
            uboAlignment = Math.max(16, glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT));
            vao = glCreateVertexArrays();
            glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
            glVertexArrayAttribFormat(vao, 1, 4, GL_UNSIGNED_BYTE, true, 12);
            glVertexArrayAttribFormat(vao, 2, 2, GL_FLOAT, false, 16);
            glVertexArrayAttribIFormat(vao, 3, 2, GL_SHORT, 24);
            for (int attribute = 0; attribute < 4; attribute++) { glVertexArrayAttribBinding(vao, attribute, 0); glEnableVertexArrayAttrib(vao, attribute); }
            // Mip level 0 only, like the Vulkan world pass: solid, cutoutMipped and cutout share one mesh.
            atlasSampler = sampler(GL_NEAREST);
            lightSampler = sampler(GL_LINEAR);
            Gl46Context.report("TERRAIN_INIT shaders=" + (cull.spirv() && opaque.spirv() && translucent.spirv() ? "SPIRV" : "GLSL")
                    + " cull=" + path(cull) + " opaque=" + path(opaque) + " translucent=" + path(translucent) + " uboAlignment=" + uboAlignment);
        } catch (Throwable error) { close(); throw error; }
    }

    private static String path(Gl46Programs.Program program) { return program.spirv() ? "SPIRV" : "GLSL"; }
    private static int sampler(int filter) {
        int sampler = glCreateSamplers();
        glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, filter); glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, filter);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return sampler;
    }

    /** Same-thread sink: commands are applied immediately on the render thread. */
    @Override public boolean send(WorldProtocol.Command command) {
        RenderSystem.assertOnRenderThread();
        if (command instanceof WorldProtocol.Reset reset) { clear(); epoch = reset.epoch(); return true; }
        if (command.epoch() != epoch || epoch == 0) return true;
        if (command instanceof WorldProtocol.Camera view) camera = view;
        else if (command instanceof WorldProtocol.Remove remove) remove(remove.key());
        else if (command instanceof WorldProtocol.Mesh mesh) { receivedMesh = true; remove(mesh.key()); add(mesh); }
        // Atlas / TexturePatch never arrive: mirrorsTextures() is false and draws sample vanilla's own textures.
        return true;
    }
    @Override public boolean mirrorsTextures() { return false; }

    boolean ready() { return camera != null && receivedMesh; }
    long epoch() { return epoch; }
    int sections() { return sections.size(); }
    long frames() { return frameCount; }

    private void add(WorldProtocol.Mesh mesh) {
        byte[] solid = mesh.vertices(), sorted = mesh.translucent();
        if (solid.length == 0 && sorted.length == 0) return;
        if (freeSlots.isEmpty() && slotCount >= MAX_SECTIONS) throw new IllegalStateException("GL 4.6 section slots exhausted");
        int count = solid.length / STRIDE, first = 0, translucentFirst = -1, indexFirst = -1;
        try {
            if (count > 0) { first = vertices.allocate(count); vertices.upload(first, solid, staging); }
            Translucent layer = null;
            if (sorted.length > 0) {
                int translucentCount = sorted.length / STRIDE;
                translucentFirst = vertices.allocate(translucentCount); vertices.upload(translucentFirst, sorted, staging);
                indexFirst = indices.allocate(translucentCount);
                layer = new Translucent(translucentFirst, indexFirst, sorted);
            }
            int slot = freeSlots.isEmpty() ? slotCount++ : freeSlots.poll();
            sections.put(mesh.key(), new Section(slot, mesh.x(), mesh.y(), mesh.z(), first, count, layer));
            writeSlot(slot, mesh.x(), mesh.y(), mesh.z(), bounds(count > 0 ? solid : sorted), count, first);
            peakSections = Math.max(peakSections, sections.size()); meshes++;
        } catch (RuntimeException error) {
            if (count > 0) vertices.free(first, count);
            if (translucentFirst >= 0) vertices.free(translucentFirst, sorted.length / STRIDE);
            if (indexFirst >= 0) indices.free(indexFirst, sorted.length / STRIDE);
            throw error;
        }
    }

    /** GL executes the slot clear and range reuse after earlier frames' draws, so no fence is needed here. */
    private void remove(long key) {
        Section old = sections.remove(key);
        if (old == null) return;
        release(old);
        writeSlot(old.slot(), 0, 0, 0, new float[6], 0, 0);
        freeSlots.add(old.slot());
    }
    private void release(Section old) {
        if (old.vertices() > 0) vertices.free(old.firstVertex(), old.vertices());
        if (old.translucent() != null) {
            vertices.free(old.translucent().firstVertex, old.translucent().indices());
            indices.free(old.translucent().firstIndex, old.translucent().indices());
        }
    }

    /** std430 Section { ivec4 origin; vec4 minimum; vec4 maximum; uvec4 draw(count, first) }. */
    private void writeSlot(int slot, int x, int y, int z, float[] bounds, int count, int first) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer entry = stack.calloc(SECTION_BYTES);
            entry.putInt(0, x).putInt(4, y).putInt(8, z);
            for (int axis = 0; axis < 3; axis++) {
                entry.putFloat(16 + axis * 4, bounds[axis] - .001f);
                entry.putFloat(32 + axis * 4, bounds[axis + 3] + .001f);
            }
            entry.putInt(48, count).putInt(52, first);
            glNamedBufferSubData(sectionBuffer, (long) slot * SECTION_BYTES, entry);
        }
    }

    private static float[] bounds(byte[] vertices) {
        float[] result = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        var bytes = ByteBuffer.wrap(vertices).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < vertices.length; offset += STRIDE) for (int axis = 0; axis < 3; axis++) {
            float value = bytes.getFloat(offset + axis * 4);
            result[axis] = Math.min(result[axis], value); result[axis + 3] = Math.max(result[axis + 3], value);
        }
        return result;
    }

    /** Called with Minecraft's main target bound, before vanilla's solid layer; GL state is restored exactly. */
    boolean drawOpaque(int atlas, int light) {
        if (!ready()) return false;
        if (slotCount == 0) { frameCount++; return true; }
        try (State ignored = new State(); MemoryStack stack = MemoryStack.stackPush()) {
            bindFrame(atlas, light);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, drawBuffer);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, countBuffer);
            glClearNamedBufferSubData(countBuffer, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, stack.callocInt(1));
            glUseProgram(cull.id());
            glDispatchCompute((slotCount + 63) / 64, 1, 1);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            glUseProgram(opaque.id());
            bindVertexArray();
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, drawBuffer);
            glBindBuffer(GL_PARAMETER_BUFFER, countBuffer);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
            glEnable(GL_CULL_FACE); glDisable(GL_BLEND);
            glMultiDrawArraysIndirectCount(GL_TRIANGLES, 0, 0, slotCount, 16);
        }
        frameCount++;
        checkErrors("opaque");
        return true;
    }

    /** AFTER_TRANSLUCENT_BLOCKS: far-to-near sections, each far-to-near quads, with vanilla's translucent blend and depth write. */
    void drawTranslucent(int atlas, int light) {
        if (!ready()) return;
        List<Section> ordered = new ArrayList<>();
        for (Section section : sections.values()) if (section.translucent() != null) ordered.add(section);
        if (ordered.isEmpty()) return;
        ordered.sort(Comparator.comparingDouble(section -> -distanceSquared(section)));
        int resorts = 0;
        // Nearest stale sections spend the re-sort budget first; never-sorted sections are always sorted before use.
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Section section = ordered.get(i); Translucent layer = section.translucent();
            double x = camera.x() - section.x(), y = camera.y() - section.y(), z = camera.z() - section.z();
            if (!layer.sorted) sort(layer, x, y, z);
            else if (resorts < RESORT_BUDGET && layer.stale(x, y, z)) { sort(layer, x, y, z); resorts++; }
        }
        try (State ignored = new State()) {
            bindFrame(atlas, light);
            int offset = commands.allocate(ordered.size() * COMMAND_BYTES, 4);
            if (offset < 0) throw new IllegalStateException("Translucent command ring too small");
            ByteBuffer out = commands.mapped;
            int cursor = offset;
            for (Section section : ordered) {
                Translucent layer = section.translucent();
                out.putInt(cursor, layer.indices()).putInt(cursor + 4, 1).putInt(cursor + 8, layer.firstIndex)
                        .putInt(cursor + 12, layer.firstVertex).putInt(cursor + 16, section.slot());
                cursor += COMMAND_BYTES;
            }
            glUseProgram(translucent.id());
            bindVertexArray();
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands.buffer);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true); glEnable(GL_CULL_FACE);
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, ordered.size(), COMMAND_BYTES);
        }
        translucentFrames++; translucentDraws += ordered.size();
        checkErrors("translucent");
    }

    /** x/y/z: camera relative to the section origin. Farthest quad first. */
    private void sort(Translucent layer, double x, double y, double z) {
        long[] keys = new long[layer.quads];
        for (int quad = 0; quad < layer.quads; quad++) {
            float dx = (float) (layer.centers[quad * 3] - x), dy = (float) (layer.centers[quad * 3 + 1] - y), dz = (float) (layer.centers[quad * 3 + 2] - z);
            // Non-negative float bits order like the floats themselves.
            keys[quad] = (long) Float.floatToIntBits(dx * dx + dy * dy + dz * dz) << 32 | quad;
        }
        Arrays.sort(keys);
        ByteBuffer order = MemoryUtil.memAlloc(layer.indices() * 4);
        try {
            var ints = order.asIntBuffer();
            for (int i = layer.quads - 1; i >= 0; i--) { int base = (int) keys[i] * 6; for (int k = 0; k < 6; k++) ints.put(base + k); }
            indices.upload(layer.firstIndex, order, staging);
        } finally { MemoryUtil.memFree(order); }
        layer.sortedX = x; layer.sortedY = y; layer.sortedZ = z; layer.sorted = true;
        sorts++;
    }

    private double distanceSquared(Section section) {
        double x = section.x() + 8 - camera.x(), y = section.y() + 8 - camera.y(), z = section.z() + 8 - camera.z();
        return x * x + y * y + z * z;
    }

    /** std140 Frame block, a fresh persistent-ring slice per draw, plus the section SSBO and both samplers. */
    private void bindFrame(int atlas, int light) {
        int offset = frames.allocate(FRAME_BYTES, uboAlignment);
        ByteBuffer frame = frames.mapped;
        float[] matrix = camera.matrix();
        for (int i = 0; i < 16; i++) frame.putFloat(offset + i * 4, matrix[i]);
        double[] position = {camera.x(), camera.y(), camera.z()};
        for (int axis = 0; axis < 3; axis++) {
            double block = Math.floor(position[axis]);
            frame.putInt(offset + 64 + axis * 4, (int) block);
            frame.putFloat(offset + 80 + axis * 4, (float) (position[axis] - block));
        }
        float[] fogColor = RenderSystem.getShaderFogColor();
        for (int i = 0; i < 4; i++) frame.putFloat(offset + 96 + i * 4, fogColor[i]);
        frame.putFloat(offset + 112, RenderSystem.getShaderFogStart()).putFloat(offset + 116, RenderSystem.getShaderFogEnd())
                .putFloat(offset + 120, RenderSystem.getShaderFogShape().getIndex());
        frame.putInt(offset + 128, slotCount);
        glBindBufferRange(GL_UNIFORM_BUFFER, 0, frames.buffer, offset, FRAME_BYTES);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, sectionBuffer);
        glBindTextureUnit(0, atlas); glBindSampler(0, atlasSampler);
        glBindTextureUnit(2, light); glBindSampler(2, lightSampler);
    }

    /** Arena growth replaces buffers; the VAO picks up the current ones before each draw. */
    private void bindVertexArray() {
        if (vaoVertexBuffer != vertices.buffer()) { vaoVertexBuffer = vertices.buffer(); glVertexArrayVertexBuffer(vao, 0, vaoVertexBuffer, 0, STRIDE); }
        if (vaoIndexBuffer != indices.buffer()) { vaoIndexBuffer = indices.buffer(); glVertexArrayElementBuffer(vao, vaoIndexBuffer); }
        glBindVertexArray(vao);
    }

    private void checkErrors(String pass) {
        if (errorsChecked) return;
        errorsChecked = true;
        int error = glGetError();
        Gl46Context.report("FIRST_" + pass.toUpperCase(Locale.ROOT) + "_GL_ERROR=" + (error == GL_NO_ERROR ? "NONE" : "0x" + Integer.toHexString(error)));
        if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(error) + " in GL 4.6 " + pass + " terrain pass");
    }

    long[] stats() {
        return new long[]{frameCount, sections.size(), slotCount, vertices == null ? 0 : vertices.capacity(), vertices == null ? 0 : vertices.grows(),
                vertices == null ? 0 : vertices.uploadedBytes() + indices.uploadedBytes(), staging == null ? 0 : staging.waits(), translucentDraws, sorts};
    }

    private void clear() {
        for (Section section : sections.values()) release(section);
        sections.clear(); freeSlots.clear(); slotCount = 0;
        if (sectionBuffer != 0) glClearNamedBufferData(sectionBuffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        camera = null; receivedMesh = false;
    }

    @Override public void close() {
        if (vertices != null) Gl46Context.report("TERRAIN_CLOSE frames=" + frameCount + " translucentFrames=" + translucentFrames
                + " translucentDraws=" + translucentDraws + " sorts=" + sorts + " meshes=" + meshes + " peakSections=" + peakSections
                + " vertexCapacity=" + vertices.capacity() + " vertexGrows=" + vertices.grows() + " indexGrows=" + (indices == null ? 0 : indices.grows())
                + " uploadedBytes=" + (vertices.uploadedBytes() + (indices == null ? 0 : indices.uploadedBytes()))
                + " stagingWaits=" + (staging == null ? 0 : staging.waits()));
        sections.clear(); freeSlots.clear();
        if (cull != null) glDeleteProgram(cull.id());
        if (opaque != null) glDeleteProgram(opaque.id());
        if (translucent != null) glDeleteProgram(translucent.id());
        cull = opaque = translucent = null;
        for (AutoCloseable resource : new AutoCloseable[]{vertices, indices, staging, frames, commands}) {
            try { if (resource != null) resource.close(); } catch (Exception ignored) { }
        }
        vertices = indices = null; staging = frames = commands = null;
        for (int buffer : new int[]{sectionBuffer, drawBuffer, countBuffer}) if (buffer != 0) glDeleteBuffers(buffer);
        sectionBuffer = drawBuffer = countBuffer = 0;
        if (vao != 0) glDeleteVertexArrays(vao);
        if (atlasSampler != 0) glDeleteSamplers(atlasSampler);
        if (lightSampler != 0) glDeleteSamplers(lightSampler);
        vao = atlasSampler = lightSampler = 0;
    }

    /**
     * Raw GL state this renderer touches. Restoring the exact previous values keeps GlStateManager's, ShaderInstance's and
     * BufferUploader's caches truthful without going through them.
     */
    private static final class State implements AutoCloseable {
        final int program = glGetInteger(GL_CURRENT_PROGRAM), vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int indirect = glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING), parameter = glGetInteger(GL_PARAMETER_BUFFER_BINDING);
        final int active = glGetInteger(GL_ACTIVE_TEXTURE), texture0, texture2;
        final boolean depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE), blend = glIsEnabled(GL_BLEND);
        final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        final int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        final int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
        final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        State() { texture0 = bound(0); texture2 = bound(2); glActiveTexture(active); }
        private static int bound(int unit) { glActiveTexture(GL_TEXTURE0 + unit); return glGetInteger(GL_TEXTURE_BINDING_2D); }
        @Override public void close() {
            glUseProgram(program); glBindVertexArray(vertexArray);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect); glBindBuffer(GL_PARAMETER_BUFFER, parameter);
            glBindSampler(0, 0); glBindSampler(2, 0);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture0);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, texture2);
            glActiveTexture(active);
            toggle(GL_DEPTH_TEST, depth); toggle(GL_CULL_FACE, cull); toggle(GL_BLEND, blend);
            glDepthMask(depthMask); glDepthFunc(depthFunc);
            glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
        }
        private static void toggle(int capability, boolean enabled) { if (enabled) glEnable(capability); else glDisable(capability); }
    }
}
