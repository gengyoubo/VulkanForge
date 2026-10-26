package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.vulkanforge.bridge.WorldProtocol;
import java.nio.ByteBuffer;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL46C.*;

/**
 * OpenGL 4.6 terrain in the game's own context (issue #4). Vanilla's BLOCK quads are uploaded unchanged into one DSA vertex
 * arena (no triangle expansion); a compute shader frustum-tests the section SSBO and writes, per visible section, one
 * DrawElementsIndirectCommand into each of the solid / cutoutMipped / cutout command lists, with GPU draw counts. Each layer
 * is then one glMultiDrawElementsIndirectCount over a shared quad index buffer, with vanilla's mip filtering and alpha cutoff.
 * Translucent sections are sorted on the CPU and drawn with one glMultiDrawElementsIndirect. Uploads, per-draw uniforms and
 * translucent commands use persistent-mapped rings. Draws go straight into Minecraft's bound framebuffer and depth.
 */
final class Gl46Terrain implements WorldSink, AutoCloseable {
    static final int MAX_SECTIONS = 65535;
    /** DefaultVertexFormat.BLOCK: position, color, uv0, uv2 (light), normal. */
    private static final int STRIDE = DefaultVertexFormat.BLOCK.getVertexSize();
    /** WorldCapture admits at most MAX_MESH_BYTES of triangle-expanded geometry per layer group, i.e. fewer quads than this. */
    private static final int MAX_QUADS = 1 << 16;
    private static final int SECTION_BYTES = 64, FRAME_BYTES = 144, COMMAND_BYTES = 20, RESORT_BUDGET = 16, OPAQUE_LAYERS = 3;
    /** Vanilla rendertype_solid / cutout_mipped / cutout discard thresholds; 0 never discards. */
    private static final float[] CUTOFF = {0, .5f, .1f};

    private record Section(int slot, int x, int y, int z, int firstVertex, int opaqueQuads, Translucent translucent) { }

    /** Sorting rewrites only this section's quad index range. */
    private static final class Translucent {
        final int firstVertex, firstIndex, quads;
        final float[] centers;
        double sortedX, sortedY, sortedZ;
        boolean sorted;
        Translucent(int firstVertex, int firstIndex, byte[] bytes) {
            this.firstVertex = firstVertex; this.firstIndex = firstIndex;
            quads = bytes.length / (4 * STRIDE);
            centers = new float[quads * 3];
            var data = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int quad = 0; quad < quads; quad++) for (int corner = 0; corner < 4; corner++) for (int axis = 0; axis < 3; axis++)
                centers[quad * 3 + axis] += data.getFloat((quad * 4 + corner) * STRIDE + axis * 4) * .25f;
        }
        int vertices() { return quads * 4; }
        int indices() { return quads * 6; }
        boolean stale(double x, double y, double z) {
            double dx = x - sortedX, dy = y - sortedY, dz = z - sortedZ;
            return !sorted || dx * dx + dy * dy + dz * dz > 1;
        }
    }

    private Gl46Programs.Program cull, opaque, translucent;
    private Gl46Buffers.Arena vertices, indices;
    private Gl46Buffers.Ring staging, frames, commands;
    private int sectionBuffer, drawBuffer, countBuffer, quadIndices, opaqueVao, translucentVao;
    private int mipSampler, flatSampler, lightSampler, uboAlignment, vaoVertexBuffer, vaoIndexBuffer;
    private final Map<Long, Section> sections = new HashMap<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int slotCount, peakSections;
    private long epoch, frameCount, translucentFrames, translucentDraws, sorts, meshes;
    private WorldProtocol.Camera camera;
    private boolean receivedMesh;
    private final Set<String> checkedPasses = new HashSet<>();

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
            drawBuffer = Gl46Buffers.immutable((long) OPAQUE_LAYERS * MAX_SECTIONS * COMMAND_BYTES, 0);
            countBuffer = Gl46Buffers.immutable(4 * OPAQUE_LAYERS, GL_DYNAMIC_STORAGE_BIT);
            quadIndices = quadIndexBuffer();
            uboAlignment = Math.max(16, glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT));
            opaqueVao = vertexArray(); translucentVao = vertexArray();
            glVertexArrayElementBuffer(opaqueVao, quadIndices);
            // Vanilla's block sheet: NEAREST_MIPMAP_LINEAR for solid, cutoutMipped and translucent; cutout skips mipmaps.
            mipSampler = sampler(GL_NEAREST_MIPMAP_LINEAR, GL_NEAREST);
            flatSampler = sampler(GL_NEAREST, GL_NEAREST);
            lightSampler = sampler(GL_LINEAR, GL_LINEAR);
            Gl46Context.report("TERRAIN_INIT shaders=" + (cull.spirv() && opaque.spirv() && translucent.spirv() ? "SPIRV" : "GLSL")
                    + " cull=" + path(cull) + " opaque=" + path(opaque) + " translucent=" + path(translucent) + " uboAlignment=" + uboAlignment
                    + " vertexStride=" + STRIDE);
        } catch (Throwable error) { close(); throw error; }
    }

    private static String path(Gl46Programs.Program program) { return program.spirv() ? "SPIRV" : "GLSL"; }
    private static int sampler(int minFilter, int magFilter) {
        int sampler = glCreateSamplers();
        glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, minFilter); glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, magFilter);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return sampler;
    }
    /** Immutable 0 1 2, 2 3 0 pattern (vanilla's quad order) shared by every opaque layer draw via baseVertex. */
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
    private static int vertexArray() {
        int vao = glCreateVertexArrays();
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribFormat(vao, 1, 4, GL_UNSIGNED_BYTE, true, 12);
        glVertexArrayAttribFormat(vao, 2, 2, GL_FLOAT, false, 16);
        glVertexArrayAttribIFormat(vao, 3, 2, GL_SHORT, 24);
        for (int attribute = 0; attribute < 4; attribute++) { glVertexArrayAttribBinding(vao, attribute, 0); glEnableVertexArrayAttrib(vao, attribute); }
        return vao;
    }

    /** Same-thread sink: commands are applied immediately on the render thread. */
    @Override public boolean send(WorldProtocol.Command command) {
        RenderSystem.assertOnRenderThread();
        if (command instanceof WorldProtocol.Reset reset) { clear(); epoch = reset.epoch(); return true; }
        if (command.epoch() != epoch || epoch == 0) return true;
        if (command instanceof WorldProtocol.Camera view) camera = view;
        else if (command instanceof WorldProtocol.Remove remove) remove(remove.key());
        else if (command instanceof WorldProtocol.Mesh mesh) {
            // Only WorldCapture's empty bootstrap mesh arrives this way; sections use quads().
            if (mesh.vertices().length > 0 || mesh.translucent().length > 0) throw new IllegalStateException("GL 4.6 terrain takes raw quads only");
            receivedMesh = true; remove(mesh.key());
        }
        // Atlas / TexturePatch never arrive: mirrorsTextures() is false and draws sample vanilla's own textures.
        return true;
    }
    @Override public boolean mirrorsTextures() { return false; }
    @Override public boolean rawQuads() { return true; }

    @Override public boolean quads(long epoch, long key, int x, int y, int z, byte[][] layers) {
        RenderSystem.assertOnRenderThread();
        if (epoch != this.epoch || epoch == 0) return true;
        receivedMesh = true;
        remove(key);
        int opaqueBytes = layers[0].length + layers[1].length + layers[2].length;
        if (opaqueBytes == 0 && layers[3].length == 0) return true;
        for (byte[] layer : layers) if (layer.length / (4 * STRIDE) > MAX_QUADS) throw new IllegalStateException("Section layer exceeds " + MAX_QUADS + " quads");
        if (freeSlots.isEmpty() && slotCount >= MAX_SECTIONS) throw new IllegalStateException("GL 4.6 section slots exhausted");
        int opaqueVertices = opaqueBytes / STRIDE, first = -1, translucentFirst = -1, indexFirst = -1;
        try {
            if (opaqueVertices > 0) {
                first = vertices.allocate(opaqueVertices);
                int at = first;
                for (int layer = 0; layer < OPAQUE_LAYERS; layer++) if (layers[layer].length > 0) {
                    vertices.upload(at, layers[layer], staging); at += layers[layer].length / STRIDE;
                }
            }
            Translucent sorted = null;
            if (layers[3].length > 0) {
                translucentFirst = vertices.allocate(layers[3].length / STRIDE);
                vertices.upload(translucentFirst, layers[3], staging);
                indexFirst = indices.allocate(layers[3].length / STRIDE / 4 * 6);
                sorted = new Translucent(translucentFirst, indexFirst, layers[3]);
            }
            int slot = freeSlots.isEmpty() ? slotCount++ : freeSlots.poll();
            sections.put(key, new Section(slot, x, y, z, Math.max(first, 0), opaqueVertices / 4, sorted));
            int[] quads = new int[OPAQUE_LAYERS];
            for (int layer = 0; layer < OPAQUE_LAYERS; layer++) quads[layer] = layers[layer].length / (4 * STRIDE);
            writeSlot(slot, x, y, z, opaqueVertices > 0 ? bounds(layers[0], layers[1], layers[2]) : bounds(layers[3]), Math.max(first, 0), quads);
            peakSections = Math.max(peakSections, sections.size()); meshes++;
            return true;
        } catch (RuntimeException error) {
            if (first >= 0) vertices.free(first, opaqueVertices);
            if (translucentFirst >= 0) vertices.free(translucentFirst, layers[3].length / STRIDE);
            if (indexFirst >= 0) indices.free(indexFirst, layers[3].length / STRIDE / 4 * 6);
            throw error;
        }
    }

    boolean ready() { return camera != null && receivedMesh; }
    long epoch() { return epoch; }
    int sections() { return sections.size(); }
    long frames() { return frameCount; }

    /** GL executes the slot clear and range reuse after earlier frames' draws, so no fence is needed here. */
    private void remove(long key) {
        Section old = sections.remove(key);
        if (old == null) return;
        release(old);
        writeSlot(old.slot(), 0, 0, 0, new float[6], 0, new int[OPAQUE_LAYERS]);
        freeSlots.add(old.slot());
    }
    private void release(Section old) {
        if (old.opaqueQuads() > 0) vertices.free(old.firstVertex(), old.opaqueQuads() * 4);
        if (old.translucent() != null) {
            vertices.free(old.translucent().firstVertex, old.translucent().vertices());
            indices.free(old.translucent().firstIndex, old.translucent().indices());
        }
    }

    /** std430 Section { ivec4 origin; vec4 minimum; vec4 maximum; uvec4 draw(first, solid, cutoutMipped, cutout quads) }. */
    private void writeSlot(int slot, int x, int y, int z, float[] bounds, int first, int[] quads) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer entry = stack.calloc(SECTION_BYTES);
            entry.putInt(0, x).putInt(4, y).putInt(8, z);
            for (int axis = 0; axis < 3; axis++) {
                entry.putFloat(16 + axis * 4, bounds[axis] - .001f);
                entry.putFloat(32 + axis * 4, bounds[axis + 3] + .001f);
            }
            entry.putInt(48, first);
            for (int layer = 0; layer < OPAQUE_LAYERS; layer++) entry.putInt(52 + layer * 4, quads[layer]);
            glNamedBufferSubData(sectionBuffer, (long) slot * SECTION_BYTES, entry);
        }
    }

    private static float[] bounds(byte[]... layers) {
        float[] result = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (byte[] layer : layers) {
            var bytes = ByteBuffer.wrap(layer).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset < layer.length; offset += STRIDE) for (int axis = 0; axis < 3; axis++) {
                float value = bytes.getFloat(offset + axis * 4);
                result[axis] = Math.min(result[axis], value); result[axis + 3] = Math.max(result[axis + 3], value);
            }
        }
        return result;
    }

    /** Called with Minecraft's main target bound, before vanilla's solid layer; GL state is restored exactly. */
    boolean drawOpaque(int atlas, int light) {
        if (!ready()) return false;
        if (slotCount == 0) { frameCount++; return true; }
        try (Gl46State ignored = new Gl46State(); MemoryStack stack = MemoryStack.stackPush()) {
            bindFrame(atlas, light, CUTOFF[0], mipSampler);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, drawBuffer);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, countBuffer);
            glClearNamedBufferSubData(countBuffer, GL_R32UI, 0, 4 * OPAQUE_LAYERS, GL_RED_INTEGER, GL_UNSIGNED_INT, stack.callocInt(1));
            glUseProgram(cull.id());
            glDispatchCompute((slotCount + 63) / 64, 1, 1);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            glUseProgram(opaque.id());
            bindVertexArray(opaqueVao);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, drawBuffer);
            glBindBuffer(GL_PARAMETER_BUFFER, countBuffer);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
            glEnable(GL_CULL_FACE); glDisable(GL_BLEND);
            for (int layer = 0; layer < OPAQUE_LAYERS; layer++) {
                if (layer > 0) bindFrame(atlas, light, CUTOFF[layer], layer == 2 ? flatSampler : mipSampler);
                glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_INT, (long) layer * MAX_SECTIONS * COMMAND_BYTES, layer * 4L, slotCount, COMMAND_BYTES);
            }
            frames.submit();
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
        try (Gl46State ignored = new Gl46State()) {
            bindFrame(atlas, light, 0, mipSampler);
            int offset = commands.allocate(ordered.size() * COMMAND_BYTES, 4);
            if (offset < 0) { commands.submit(); offset = commands.allocate(ordered.size() * COMMAND_BYTES, 4); }
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
            bindVertexArray(translucentVao);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commands.buffer);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true); glEnable(GL_CULL_FACE);
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, offset, ordered.size(), COMMAND_BYTES);
            frames.submit(); commands.submit();
        }
        translucentFrames++; translucentDraws += ordered.size();
        checkErrors("translucent");
    }

    /** x/y/z: camera relative to the section origin. Farthest quad first; indices are relative to the layer's first vertex. */
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
            for (int i = layer.quads - 1; i >= 0; i--) {
                int base = (int) keys[i] * 4;
                ints.put(base).put(base + 1).put(base + 2).put(base + 2).put(base + 3).put(base);
            }
            indices.upload(layer.firstIndex, order, staging);
        } finally { MemoryUtil.memFree(order); }
        layer.sortedX = x; layer.sortedY = y; layer.sortedZ = z; layer.sorted = true;
        sorts++;
    }

    private double distanceSquared(Section section) {
        double x = section.x() + 8 - camera.x(), y = section.y() + 8 - camera.y(), z = section.z() + 8 - camera.z();
        return x * x + y * y + z * z;
    }

    /** std140 Frame block in a fresh persistent-ring slice, plus the section SSBO, the atlas with this layer's sampler, and the lightmap. */
    private void bindFrame(int atlas, int light, float cutoff, int atlasSampler) {
        int offset = frames.allocate(FRAME_BYTES, uboAlignment);
        if (offset < 0) { frames.submit(); offset = frames.allocate(FRAME_BYTES, uboAlignment); }
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
                .putFloat(offset + 120, RenderSystem.getShaderFogShape().getIndex()).putFloat(offset + 124, cutoff);
        frame.putInt(offset + 128, slotCount).putInt(offset + 132, MAX_SECTIONS);
        glBindBufferRange(GL_UNIFORM_BUFFER, 0, frames.buffer, offset, FRAME_BYTES);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, sectionBuffer);
        glBindTextureUnit(0, atlas); glBindSampler(0, atlasSampler);
        glBindTextureUnit(2, light); glBindSampler(2, lightSampler);
    }

    /** Arena growth replaces buffers; both VAOs pick up the current ones before each draw. */
    private void bindVertexArray(int vao) {
        if (vaoVertexBuffer != vertices.buffer()) {
            vaoVertexBuffer = vertices.buffer();
            glVertexArrayVertexBuffer(opaqueVao, 0, vaoVertexBuffer, 0, STRIDE);
            glVertexArrayVertexBuffer(translucentVao, 0, vaoVertexBuffer, 0, STRIDE);
        }
        if (vaoIndexBuffer != indices.buffer()) { vaoIndexBuffer = indices.buffer(); glVertexArrayElementBuffer(translucentVao, vaoIndexBuffer); }
        glBindVertexArray(vao);
    }

    /** glGetError stalls nothing on its own but is still kept to each pass's first frame. */
    private void checkErrors(String pass) {
        if (!checkedPasses.add(pass)) return;
        int error = glGetError();
        Gl46Context.report("FIRST_" + pass.toUpperCase(Locale.ROOT) + "_GL_ERROR=" + (error == GL_NO_ERROR ? "NONE" : "0x" + Integer.toHexString(error)));
        if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(error) + " in GL 4.6 " + pass + " terrain pass");
    }

    /** {frames, sections, slots, vertexCapacity, vertexGrows, uploadedBytes, stagingWaits, translucentDraws, sorts}. */
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
                + " stagingWaits=" + (staging == null ? 0 : staging.waits()) + " freeRanges=" + vertices.freeRanges());
        sections.clear(); freeSlots.clear();
        for (Gl46Programs.Program program : new Gl46Programs.Program[]{cull, opaque, translucent}) if (program != null) glDeleteProgram(program.id());
        cull = opaque = translucent = null;
        for (AutoCloseable resource : new AutoCloseable[]{vertices, indices, staging, frames, commands}) {
            try { if (resource != null) resource.close(); } catch (Exception ignored) { }
        }
        vertices = indices = null; staging = frames = commands = null;
        for (int buffer : new int[]{sectionBuffer, drawBuffer, countBuffer, quadIndices}) if (buffer != 0) glDeleteBuffers(buffer);
        sectionBuffer = drawBuffer = countBuffer = quadIndices = 0;
        for (int vao : new int[]{opaqueVao, translucentVao}) if (vao != 0) glDeleteVertexArrays(vao);
        for (int sampler : new int[]{mipSampler, flatSampler, lightSampler}) if (sampler != 0) glDeleteSamplers(sampler);
        opaqueVao = translucentVao = mipSampler = flatSampler = lightSampler = 0;
    }
}
