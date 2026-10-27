package github.com.gengyoubo.vulkanforge.client;

import static github.com.gengyoubo.vulkanforge.client.Gl46State.glUseProgram;
import static github.com.gengyoubo.vulkanforge.client.Gl46State.glBindVertexArray;
import static github.com.gengyoubo.vulkanforge.client.Gl46State.glBindSampler;
import static github.com.gengyoubo.vulkanforge.client.Gl46State.glEnable;
import static github.com.gengyoubo.vulkanforge.client.Gl46State.glDisable;
import static github.com.gengyoubo.vulkanforge.client.Gl46State.glDepthMask;
import static github.com.gengyoubo.vulkanforge.client.Gl46State.glDepthFunc;
import static github.com.gengyoubo.vulkanforge.client.Gl46State.glBlendFuncSeparate;

import static github.com.gengyoubo.vulkanforge.client.GlCompat.glBindBuffer;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glBindBufferRange;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glBindTextureUnit;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glClearNamedBufferData;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glClearNamedBufferSubData;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glCopyNamedBufferSubData;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glCreateBuffers;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glCreateSamplers;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glCreateTextures;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glCreateVertexArrays;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glDeleteVertexArrays;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glDispatchCompute;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glEnableVertexArrayAttrib;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glMapNamedBufferRange;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glMultiDrawElementsIndirect;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glMultiDrawElementsIndirectCount;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glNamedBufferStorage;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glNamedBufferSubData;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glTextureStorage2D;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glUnmapNamedBuffer;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayAttribBinding;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayAttribFormat;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayAttribIFormat;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayElementBuffer;
import static github.com.gengyoubo.vulkanforge.client.GlCompat.glVertexArrayVertexBuffer;

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
    private static final int SECTION_BYTES = 64, FRAME_BYTES = 144, COMMAND_BYTES = 20, OPAQUE_LAYERS = 3;
    /** Counts { drawCounts[3]; tested; frustumVisible; occluded }; READBACK_SLOTS frames of copies in flight. */
    private static final int COUNT_BYTES = 24, OCCLUSION_BYTES = 112, READBACK_SLOTS = 4;
    /** Re-sorted quads per frame, nearest stale sections first (at least one section per frame). */
    private static final int QUAD_BUDGET = 32768;
    /** CPU time for re-sorting per frame; the nearest stale section is always sorted. */
    private static final long SORT_NANOS = 400_000;
    /** Camera moves beyond this (blocks) since the last sort use a full sort instead of insertion sort on the previous order. */
    private static final double FULL_SORT_DISTANCE = 8;
    /** Vanilla rendertype_solid / cutout_mipped / cutout discard thresholds; 0 never discards. */
    private static final float[] CUTOFF = {0, .5f, .1f};

    private record Section(int slot, int x, int y, int z, int firstVertex, int opaqueQuads, Translucent translucent) { }

    /**
     * Sorting rewrites only this section's quad index range. {@code order} is the far-to-near quad order of the last sort; after
     * a small camera move it is nearly sorted, so re-sorting it with insertion sort is close to linear.
     */
    private static final class Translucent {
        final int firstVertex, firstIndex, quads;
        final float[] centers;
        final int[] order;
        double sortedX, sortedY, sortedZ;
        boolean sorted;
        Translucent(int firstVertex, int firstIndex, byte[] bytes) {
            this.firstVertex = firstVertex; this.firstIndex = firstIndex;
            quads = bytes.length / (4 * STRIDE);
            centers = new float[quads * 3];
            order = new int[quads];
            var data = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int quad = 0; quad < quads; quad++) {
                order[quad] = quad;
                for (int corner = 0; corner < 4; corner++) for (int axis = 0; axis < 3; axis++)
                    centers[quad * 3 + axis] += data.getFloat((quad * 4 + corner) * STRIDE + axis * 4) * .25f;
            }
        }
        int vertices() { return quads * 4; }
        int indices() { return quads * 6; }
        double moved(double x, double y, double z) {
            double dx = x - sortedX, dy = y - sortedY, dz = z - sortedZ;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        /** Far sections change order more slowly: re-sort after max(1, distance / 8) blocks of camera movement. */
        boolean stale(double x, double y, double z) {
            return !sorted || moved(x, y, z) > Math.max(1, Math.sqrt(x * x + y * y + z * z) / 8);
        }
    }
    /** Sections that have a translucent layer; drawTranslucent never walks the opaque-only ones. */
    private final List<Section> layered = new ArrayList<>();
    /** Reused per frame / per sort: no allocation in the translucent pass once warmed up. */
    private long[] sectionKeys = new long[64], quadKeys = new long[1024];
    private float[] distances = new float[1024];
    private final List<Section> drawOrder = new ArrayList<>();
    private long insertionSorts, fullSorts, sortedQuads, deferredSorts;

    private Gl46Programs.Program cull, opaque, translucent, hiz;
    /** Vanilla's visible sections for this frame, one bit per slot (all ones when unavailable). */
    private final int[] visibility = new int[(MAX_SECTIONS + 31) / 32];
    private boolean visibilityKnown;
    private long vanillaMarked;
    /** Previous frame's depth pyramid (R32F, max of 2x2) and the camera it was built with. */
    private static final boolean HIZ = !Boolean.getBoolean("vulkanforge.gl46.noHiZ");
    private static final float MAX_TURN_DEGREES = 10, MAX_MOVE_BLOCKS = 2, MAX_PROJECTION_CHANGE = .02f;
    private int pyramid, pyramidWidth, pyramidHeight, pyramidLevels, pyramidParameters, readback, readbackFrame;
    private int depthWidth, depthHeight;
    private long hizDispatches, hizBarriers;
    private ByteBuffer readbackMapped;
    private final int counterInterval = Math.max(0, Integer.getInteger("vulkanforge.gl46.counterInterval", 60));
    private final long[] counterFences = new long[READBACK_SLOTS];
    private int counterSlot = -1;
    private final Gl46Timer counterCpu = new Gl46Timer(), hizCpu = new Gl46Timer();
    private final Gl46GpuTimer cullGpu = new Gl46GpuTimer(), drawsGpu = new Gl46GpuTimer(), hizGpu = new Gl46GpuTimer();
    private long counterFrames, counterCopies, counterBusyDrops, opaqueLayerCalls, emptyLayerSkips;
    private final boolean skipEmptyLayers = Boolean.parseBoolean(System.getProperty("vulkanforge.gl46.skipEmptyLayers", "true"));
    /** CPU residency only; never the GPU-visible count. Updated on slot replace/remove/reset. */
    private final int[] layerSectionCounts = new int[OPAQUE_LAYERS], slotLayerMasks = new int[MAX_SECTIONS];
    private WorldProtocol.Camera previousCamera;
    private long previousEpoch;
    private float yaw, pitch, previousYaw, previousPitch;
    private final float[] projection = new float[16], previousProjection = new float[16];
    /** {sampled frames, tested, frustumVisible, occluded, layerDraws}; averages divide by samples, never by total game frames. */
    private final long[] culling = new long[5];
    private long hizBuilds, hizFrames, hizSkipped, hizNanos;
    private Gl46Buffers.Arena vertices, indices;
    private Gl46Buffers.Ring frames, commands;
    private Gl46Buffers.Uploader uploads;
    private int sectionBuffer, drawBuffer, countBuffer, quadIndices, opaqueVao, translucentVao;
    private int mipSampler, flatSampler, lightSampler, uboAlignment, ssboAlignment, vaoVertexBuffer, vaoIndexBuffer;
    private final Map<Long, Section> sections = new HashMap<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int slotCount, peakSections;
    private long epoch, frameCount, translucentFrames, translucentDraws, sorts, meshes, opaqueNanos, translucentNanos;
    private WorldProtocol.Camera camera;
    private boolean receivedMesh;
    // Opt-in A/B: CPU generates the same compact commands; MDI, Indirect Count and shader format stay independent.
    private final boolean cpuDriven = Boolean.getBoolean("vulkanforge.gl46.noGpuDriven");
    private static final boolean MATCH_VULKAN_RASTER = Boolean.getBoolean("bench.matchVulkanTargets") || Boolean.getBoolean("bench.matchVulkanRaster");
    private record CpuSection(int x, int y, int z, float[] bounds, int first, int[] quads) { }
    private final Map<Integer, CpuSection> cpuSections = new HashMap<>();
    private ByteBuffer cpuCommands;
    private int lastTranslucentOffset, lastTranslucentCount;
    private final Set<String> checkedPasses = new HashSet<>();

    Gl46Terrain() {
        try {
            cull = Gl46Programs.load("cull.comp");
            if (HIZ) hiz = Gl46Programs.load("hiz.comp");
            opaque = Gl46Programs.load("terrain.vert", "terrain.frag");
            translucent = Gl46Programs.load("terrain.vert", "terrain_translucent.frag");
            vertices = new Gl46Buffers.Arena("vertex", STRIDE, 1 << 18, 256L << 20);
            indices = new Gl46Buffers.Arena("index", 4, 1 << 18, 64L << 20);
            uploads = new Gl46Buffers.Uploader(WorldProtocol.MAX_MESH_BYTES, 4);
            frames = new Gl46Buffers.Ring(64 << 10, 4);
            commands = new Gl46Buffers.Ring(2 << 20, 3);
            sectionBuffer = Gl46Buffers.immutable((long) MAX_SECTIONS * SECTION_BYTES, GL_DYNAMIC_STORAGE_BIT);
            glClearNamedBufferData(sectionBuffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
            drawBuffer = Gl46Buffers.immutable((long) OPAQUE_LAYERS * MAX_SECTIONS * COMMAND_BYTES, cpuDriven ? GL_DYNAMIC_STORAGE_BIT : 0);
            if (cpuDriven) cpuCommands = MemoryUtil.memAlloc(OPAQUE_LAYERS * MAX_SECTIONS * COMMAND_BYTES);
            countBuffer = Gl46Buffers.immutable(COUNT_BYTES, GL_DYNAMIC_STORAGE_BIT);
            int read = GL_MAP_READ_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            readback = Gl46Buffers.immutable(READBACK_SLOTS * 32L, read);
            readbackMapped = glMapNamedBufferRange(readback, 0, READBACK_SLOTS * 32L, read);
            Arrays.fill(visibility, -1);
            quadIndices = quadIndexBuffer();
            uboAlignment = Math.max(16, glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT));
            ssboAlignment = Math.max(16, glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
            opaqueVao = vertexArray(); translucentVao = vertexArray();
            glVertexArrayElementBuffer(opaqueVao, quadIndices);
            // Vanilla's block sheet: NEAREST_MIPMAP_LINEAR for solid, cutoutMipped and translucent; cutout skips mipmaps.
            mipSampler = sampler(GL_NEAREST_MIPMAP_LINEAR, GL_NEAREST);
            flatSampler = sampler(GL_NEAREST, GL_NEAREST);
            lightSampler = sampler(GL_LINEAR, GL_LINEAR);
            Gl46Context.report("TERRAIN_INIT shaders=" + (cull.spirv() && opaque.spirv() && translucent.spirv() ? "SPIRV" : "GLSL")
                    + " cull=" + path(cull) + " opaque=" + path(opaque) + " translucent=" + path(translucent) + " uboAlignment=" + uboAlignment
                    + " vertexStride=" + STRIDE);
            Gl46Context.report("TERRAIN_TUNING counterInterval=" + counterInterval + " skipEmptyLayers=" + skipEmptyLayers + " diffState=" + Gl46State.differential());
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
                // solid | cutoutMipped | cutout are contiguous in the arena: one staging write, one queued copy.
                vertices.upload(first, uploads, layers[0], layers[1], layers[2]);
            }
            Translucent sorted = null;
            if (layers[3].length > 0) {
                translucentFirst = vertices.allocate(layers[3].length / STRIDE);
                vertices.upload(translucentFirst, uploads, layers[3]);
                indexFirst = indices.allocate(layers[3].length / STRIDE / 4 * 6);
                sorted = new Translucent(translucentFirst, indexFirst, layers[3]);
                // Drawable at once in capture order; the budgeted sorter then orders it far-to-near.
                writeOrder(sorted);
            }
            int slot = freeSlots.isEmpty() ? slotCount++ : freeSlots.poll();
            Section section = new Section(slot, x, y, z, Math.max(first, 0), opaqueVertices / 4, sorted);
            sections.put(key, section);
            if (sorted != null) layered.add(section);
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
        if (old.translucent() != null) layered.remove(old);
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
        if (cpuDriven) {
            if (quads[0] + quads[1] + quads[2] == 0) cpuSections.remove(slot);
            else cpuSections.put(slot, new CpuSection(x, y, z, bounds.clone(), first, quads.clone()));
        }
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
        int mask = 0;
        for (int layer = 0; layer < OPAQUE_LAYERS; layer++) if (quads[layer] > 0) mask |= 1 << layer;
        int oldMask = slotLayerMasks[slot];
        for (int layer = 0; layer < OPAQUE_LAYERS; layer++)
            layerSectionCounts[layer] += ((mask >> layer) & 1) - ((oldMask >> layer) & 1);
        slotLayerMasks[slot] = mask;
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
        uploads.flush(); // this frame's section uploads, before anything reads them
        if (!ready()) return false;
        if (slotCount == 0) { frameCount++; return true; }
        long start = System.nanoTime();
        Gl46State saved = new Gl46State();
        long t1 = System.nanoTime(), t2 = t1, t3 = t1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            prepareCounts();
            cullGpu.begin();
            bindFrame(atlas, light, CUTOFF[0], mipSampler);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, drawBuffer);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, countBuffer);
            glClearNamedBufferSubData(countBuffer, GL_R32UI, 0, COUNT_BYTES, GL_RED_INTEGER, GL_UNSIGNED_INT, stack.callocInt(1));
            if (cpuDriven) cpuCull(stack);
            else {
                bindCulling();
                glUseProgram(cull.id());
                glDispatchCompute((slotCount + 63) / 64, 1, 1);
            }
            // Indirect commands and counts, plus the statistics copy in collectCounts().
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
            cullGpu.end();
            glBindTextureUnit(2, light); glBindSampler(2, lightSampler); // the pyramid sat on unit 2 for the cull
            t2 = System.nanoTime();
            drawsGpu.begin();
            glUseProgram(opaque.id());
            bindVertexArray(opaqueVao);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, drawBuffer);
            glBindBuffer(GL_PARAMETER_BUFFER, countBuffer);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
            if (MATCH_VULKAN_RASTER) glDisable(GL_CULL_FACE); else glEnable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            for (int layer = 0; layer < OPAQUE_LAYERS; layer++) {
                if (skipEmptyLayers && layerSectionCounts[layer] == 0) { emptyLayerSkips++; continue; }
                if (layer > 0) bindFrame(atlas, light, CUTOFF[layer], layer == 2 ? flatSampler : mipSampler);
                glMultiDrawElementsIndirectCount(GL_TRIANGLES, GL_UNSIGNED_INT, (long) layer * MAX_SECTIONS * COMMAND_BYTES, layer * 4L, slotCount, COMMAND_BYTES);
                opaqueLayerCalls++;
            }
            t3 = System.nanoTime();
            drawsGpu.end();
            collectCounts();
            frames.submit();
        } finally {
            cullGpu.end(); drawsGpu.end();
            long t4 = System.nanoTime();
            saved.close();
            long t5 = System.nanoTime();
            phases[0] += t1 - start; phases[1] += t2 - t1; phases[2] += t3 - t2; phases[3] += t4 - t3; phases[4] += t5 - t4;
        }
        long spent = System.nanoTime() - start;
        opaqueNanos += spent; opaqueTimer.add(spent); opaqueTimer.frame();
        frameCount++;
        checkErrors("opaque");
        return true;
    }

    private void cpuCull(MemoryStack stack) {
        int[] counts = new int[3]; int tested = 0, visibleCount = 0;
        float[] m = camera.matrix();
        for (var entry : cpuSections.entrySet()) {
            int index = entry.getKey(); CpuSection section = entry.getValue();
            if ((visibility[index >> 5] & (1 << (index & 31))) == 0) continue;
            tested++;
            int outside = 63;
            for (int c = 0; c < 8; c++) {
                float x = (float)(section.x - camera.x()) + section.bounds[(c & 1) == 0 ? 0 : 3] + ((c & 1) == 0 ? -.001f : .001f);
                float y = (float)(section.y - camera.y()) + section.bounds[(c & 2) == 0 ? 1 : 4] + ((c & 2) == 0 ? -.001f : .001f);
                float z = (float)(section.z - camera.z()) + section.bounds[(c & 4) == 0 ? 2 : 5] + ((c & 4) == 0 ? -.001f : .001f);
                float cx=m[0]*x+m[4]*y+m[8]*z+m[12], cy=m[1]*x+m[5]*y+m[9]*z+m[13];
                float cz=m[2]*x+m[6]*y+m[10]*z+m[14], w=m[3]*x+m[7]*y+m[11]*z+m[15];
                outside &= (cx < -w ? 1 : 0) | (cx > w ? 2 : 0) | (cy < -w ? 4 : 0) | (cy > w ? 8 : 0) | (cz < -w ? 16 : 0) | (cz > w ? 32 : 0);
            }
            if (outside != 0) continue;
            visibleCount++; int first = section.first;
            for (int layer = 0; layer < 3; layer++) {
                int quads = section.quads[layer];
                if (quads > 0) {
                    int at = (layer * MAX_SECTIONS + counts[layer]++) * COMMAND_BYTES;
                    cpuCommands.putInt(at, quads * 6).putInt(at+4, 1).putInt(at+8, 0).putInt(at+12, first).putInt(at+16, index);
                }
                first += quads * 4;
            }
        }
        for (int layer=0; layer<3; layer++) if (counts[layer] > 0) {
            ByteBuffer bytes=cpuCommands.duplicate(); int at=layer*MAX_SECTIONS*COMMAND_BYTES;
            bytes.position(at).limit(at+counts[layer]*COMMAND_BYTES); glNamedBufferSubData(drawBuffer, at, bytes);
        }
        ByteBuffer counters=stack.calloc(COUNT_BYTES);
        for (int i=0;i<3;i++) counters.putInt(i*4, counts[i]);
        counters.putInt(12, tested).putInt(16, visibleCount); glNamedBufferSubData(countBuffer, 0, counters);
    }

    /** Explicit diagnostics only, after glFinish; never called in a measured frame. Section key -> submitted quads. */
    Map<Long, Long> auditOpaqueDraws() {
        Map<Integer, Long> keys = new HashMap<>(); sections.forEach((key, section) -> keys.put(section.slot(), key));
        Map<Long, Long> draws = new TreeMap<>();
        int old = glGetInteger(GL_COPY_READ_BUFFER_BINDING);
        ByteBuffer counts = MemoryUtil.memAlloc(COUNT_BYTES), commands = MemoryUtil.memAlloc(OPAQUE_LAYERS * MAX_SECTIONS * COMMAND_BYTES);
        try {
            glBindBuffer(GL_COPY_READ_BUFFER, countBuffer); glGetBufferSubData(GL_COPY_READ_BUFFER, 0, counts);
            glBindBuffer(GL_COPY_READ_BUFFER, drawBuffer); glGetBufferSubData(GL_COPY_READ_BUFFER, 0, commands);
            for (int layer=0;layer<3;layer++) for (int i=0;i<counts.getInt(layer*4);i++) {
                int at=(layer*MAX_SECTIONS+i)*COMMAND_BYTES;
                draws.merge(keys.get(commands.getInt(at+16)), commands.getInt(at)/6L, Long::sum);
            }
        } finally { glBindBuffer(GL_COPY_READ_BUFFER, old); MemoryUtil.memFree(counts); MemoryUtil.memFree(commands); }
        return draws;
    }
    Map<Long, Long> auditTranslucentDraws() {
        Map<Integer, Long> keys = new HashMap<>(); sections.forEach((key, section) -> keys.put(section.slot(), key));
        Map<Long, Long> draws = new TreeMap<>();
        if (lastTranslucentCount == 0) return draws;
        int old = glGetInteger(GL_COPY_READ_BUFFER_BINDING);
        ByteBuffer bytes = MemoryUtil.memAlloc(lastTranslucentCount * COMMAND_BYTES);
        try {
            glBindBuffer(GL_COPY_READ_BUFFER, commands.buffer); glGetBufferSubData(GL_COPY_READ_BUFFER, lastTranslucentOffset, bytes);
            for (int i=0;i<lastTranslucentCount;i++) draws.put(keys.get(bytes.getInt(i*COMMAND_BYTES+16)),bytes.getInt(i*COMMAND_BYTES)/6L);
        } finally { glBindBuffer(GL_COPY_READ_BUFFER,old); MemoryUtil.memFree(bytes); }
        return draws;
    }

    /** Before drawOpaque: start this frame's vanilla visibility set; {@code known} false keeps every slot eligible. */
    void beginVisibility(boolean known) {
        visibilityKnown = known;
        Arrays.fill(visibility, 0, (slotCount + 31) / 32, known ? 0 : -1);
    }
    void markVisible(long key) {
        Section section = sections.get(key);
        if (section == null) return;
        visibility[section.slot() >> 5] |= 1 << (section.slot() & 31);
        vanillaMarked++;
    }
    /** This frame's camera orientation and projection, for the conservative Hi-Z checks. */
    void view(float yaw, float pitch, org.joml.Matrix4f projection) { this.yaw = yaw; this.pitch = pitch; projection.get(this.projection); }

    /**
     * Uploads the visibility bits and the Occlusion block, and binds last frame's pyramid on unit 2. Hi-Z is used only when
     * this frame looks almost like the one the pyramid came from; fast turns, moves, FOV or size changes skip it for a frame.
     */
    private void bindCulling() {
        int alignment = Math.max(uboAlignment, ssboAlignment), words = Math.max(1, (slotCount + 31) / 32);
        int offset = frames.allocate(words * 4, alignment);
        if (offset < 0) { frames.submit(); offset = frames.allocate(words * 4, alignment); }
        for (int i = 0; i < words; i++) frames.mapped.putInt(offset + i * 4, visibility[i]);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 3, frames.buffer, offset, words * 4L);
        boolean enabled = occlusionUsable();
        if (enabled) hizFrames++; else if (pyramid != 0) hizSkipped++;
        offset = frames.allocate(OCCLUSION_BYTES, uboAlignment);
        if (offset < 0) { frames.submit(); offset = frames.allocate(OCCLUSION_BYTES, uboAlignment); }
        ByteBuffer block = frames.mapped;
        if (enabled) {
            float[] matrix = previousCamera.matrix();
            for (int i = 0; i < 16; i++) block.putFloat(offset + i * 4, matrix[i]);
            double[] position = {previousCamera.x(), previousCamera.y(), previousCamera.z()};
            for (int axis = 0; axis < 3; axis++) {
                double floor = Math.floor(position[axis]);
                block.putInt(offset + 64 + axis * 4, (int) floor).putFloat(offset + 80 + axis * 4, (float) (position[axis] - floor));
            }
        }
        block.putInt(offset + 96, pyramidWidth).putInt(offset + 100, pyramidHeight).putInt(offset + 104, pyramidLevels).putInt(offset + 108, enabled ? 1 : 0);
        glBindBufferRange(GL_UNIFORM_BUFFER, 1, frames.buffer, offset, OCCLUSION_BYTES);
        glBindTextureUnit(2, pyramid); glBindSampler(2, 0);
    }
    private boolean occlusionUsable() {
        if (pyramid == 0 || previousCamera == null || previousEpoch != epoch) return false;
        double dx = camera.x() - previousCamera.x(), dy = camera.y() - previousCamera.y(), dz = camera.z() - previousCamera.z();
        if (dx * dx + dy * dy + dz * dz > MAX_MOVE_BLOCKS * MAX_MOVE_BLOCKS) return false;
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - previousYaw)) > MAX_TURN_DEGREES || Math.abs(pitch - previousPitch) > MAX_TURN_DEGREES) return false;
        for (int i = 0; i < 16; i++) if (Math.abs(projection[i] - previousProjection[i]) > MAX_PROJECTION_CHANGE * Math.max(1, Math.abs(previousProjection[i]))) return false;
        return true;
    }
    /** Sampled telemetry only. Never waits and never controls any draw's visible count. 0 disables sampling. */
    private void prepareCounts() {
        long start = System.nanoTime();
        pollCounts();
        long frame = counterFrames++;
        counterSlot = -1;
        if (counterInterval != 0 && frame % counterInterval == 0) {
            for (int n = 0; n < READBACK_SLOTS; n++) {
                int slot = (readbackFrame + n) % READBACK_SLOTS;
                if (counterFences[slot] == 0) { counterSlot = slot; break; }
            }
            if (counterSlot < 0) counterBusyDrops++;
        }
        counterCpu.add(System.nanoTime() - start);
    }
    private void collectCounts() {
        long start = System.nanoTime();
        if (counterSlot >= 0) {
            GlCompat.copyCounterReadback(countBuffer, readback, counterSlot * 32L, COUNT_BYTES);
            counterFences[counterSlot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            readbackFrame = (counterSlot + 1) % READBACK_SLOTS; counterCopies++;
        }
        counterCpu.add(System.nanoTime() - start); counterCpu.frame();
    }
    private void pollCounts() {
        for (int slot = 0; slot < READBACK_SLOTS; slot++) {
            if (counterFences[slot] == 0) continue;
            int state = glClientWaitSync(counterFences[slot], 0, 0);
            if (state == GL_WAIT_FAILED) throw new IllegalStateException("Counter fence poll failed");
            if (state != GL_ALREADY_SIGNALED && state != GL_CONDITION_SATISFIED) continue;
            GlCompat.refreshCounterReadback(readback, slot * 32L, COUNT_BYTES);
            glDeleteSync(counterFences[slot]); counterFences[slot] = 0;
            int at = slot * 32;
            culling[0]++;
            culling[1] += Integer.toUnsignedLong(readbackMapped.getInt(at + 12));
            culling[2] += Integer.toUnsignedLong(readbackMapped.getInt(at + 16));
            culling[3] += Integer.toUnsignedLong(readbackMapped.getInt(at + 20));
            for (int layer = 0; layer < OPAQUE_LAYERS; layer++) culling[4] += Integer.toUnsignedLong(readbackMapped.getInt(at + layer * 4));
        }
    }
    private void discardCounts() {
        for (int slot = 0; slot < READBACK_SLOTS; slot++) if (counterFences[slot] != 0) { glDeleteSync(counterFences[slot]); counterFences[slot] = 0; }
        counterSlot = -1; readbackFrame = 0; counterFrames = 0;
    }
    /** {render attempts, copies, collected samples, busy drops, indirect calls, empty-layer skips}. */
    long[] submissionStats() { return new long[]{frameCount, counterCopies, culling[0], counterBusyDrops, opaqueLayerCalls, emptyLayerSkips}; }

    /**
     * AFTER_CUTOUT_BLOCKS: every opaque terrain layer (GL 4.6 and vanilla's remaining sections) is in the main depth buffer and
     * no entity has drawn yet. Builds next frame's occluder pyramid from it.
     */
    void buildHiZ(int depthTexture, int width, int height) {
        if (hiz == null || !ready() || width <= 0 || height <= 0) return;
        long start = System.nanoTime();
        int levelWidth = (width + 1) / 2, levelHeight = (height + 1) / 2;
        if (pyramid == 0 || depthWidth != width || depthHeight != height) {
            depthWidth = width; depthHeight = height;
            if (pyramid != 0) glDeleteTextures(pyramid);
            pyramidWidth = levelWidth; pyramidHeight = levelHeight;
            pyramidLevels = 32 - Integer.numberOfLeadingZeros(Math.max(levelWidth, levelHeight));
            pyramid = glCreateTextures(GL_TEXTURE_2D);
            glTextureStorage2D(pyramid, pyramidLevels, GL_R32F, levelWidth, levelHeight);
            if (pyramidParameters != 0) glDeleteBuffers(pyramidParameters);
            // These parameters depend only on the framebuffer extent, not on the camera or frame.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer levels = stack.calloc(pyramidLevels * uboAlignment);
                int sourceWidth = width, sourceHeight = height;
                for (int level = 0; level < pyramidLevels; level++) {
                    int at = level * uboAlignment;
                    levels.putInt(at, level == 0 ? 0 : level - 1).putInt(at + 4, sourceWidth).putInt(at + 8, sourceHeight);
                    sourceWidth = Math.max(1, pyramidWidth >> level); sourceHeight = Math.max(1, pyramidHeight >> level);
                }
                pyramidParameters = glCreateBuffers(); glNamedBufferStorage(pyramidParameters, levels, 0);
            }
            previousCamera = null; // a pyramid of another size never describes the next frame
        }
        try (Gl46State ignored = new Gl46State()) {
            hizGpu.begin();
            glUseProgram(hiz.id());
            glBindSampler(0, 0);
            glBindTextureUnit(0, depthTexture);
            // Finish earlier shader reads before overwriting this pyramid. Each following dependency is imageStore -> texture fetch.
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT); hizBarriers++;
            for (int level = 0; level < pyramidLevels; level++) {
                glBindBufferRange(GL_UNIFORM_BUFFER, 1, pyramidParameters, (long) level * uboAlignment, 16);
                if (level == 1) glBindTextureUnit(0, pyramid);
                glBindImageTexture(0, pyramid, level, false, 0, GL_WRITE_ONLY, GL_R32F);
                int w = Math.max(1, pyramidWidth >> level), h = Math.max(1, pyramidHeight >> level);
                glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
                glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT);
                hizDispatches++; hizBarriers++;
            }
        } finally { hizGpu.end(); }
        previousCamera = camera; previousEpoch = epoch; previousYaw = yaw; previousPitch = pitch;
        System.arraycopy(projection, 0, previousProjection, 0, 16);
        hizBuilds++; long spent = System.nanoTime() - start; hizNanos += spent; hizCpu.sample(spent);
        checkErrors("hiz");
    }
    /** {cull frames, tested (vanilla-visible), frustumVisible, occluded, layerDraws, vanillaMarked, hizBuilds, hizFrames, hizSkipped}. */
    long[] cullStats() {
        return new long[]{culling[0], culling[1], culling[2], culling[3], culling[4], vanillaMarked, hizBuilds, hizFrames, hizSkipped};
    }

    long[] growStats() { return vertices != null ? vertices.growStats() : new long[3]; }
    /** {sortedQuads, insertionSorts, fullSorts, deferredSorts}. */
    long[] sortStats() { return new long[]{sortedQuads, insertionSorts, fullSorts, deferredSorts}; }
    final Gl46Timer opaqueTimer = new Gl46Timer(), translucentTimer = new Gl46Timer();
    /** Opaque pass breakdown in ns: {state snapshot, cull dispatch, layer draws, fence, state restore}. */
    private final long[] phases = new long[5];
    long[] phases() { return phases.clone(); }

    /** AFTER_TRANSLUCENT_BLOCKS: far-to-near sections, each far-to-near quads, with vanilla's translucent blend and depth write. */
    void drawTranslucent(int atlas, int light) {
        lastTranslucentCount = 0;
        if (!ready() || layered.isEmpty()) return;
        long start = System.nanoTime();
        List<Section> ordered = orderSections();
        // Nearest stale sections spend the quad budget first; a section larger than the rest of the budget waits a frame.
        int budget = QUAD_BUDGET;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Section section = ordered.get(i); Translucent layer = section.translucent();
            double x = camera.x() - section.x(), y = camera.y() - section.y(), z = camera.z() - section.z();
            if (!layer.stale(x, y, z)) continue;
            if (budget <= 0 || (layer.quads > budget && budget < QUAD_BUDGET) || (budget < QUAD_BUDGET && System.nanoTime() - start > SORT_NANOS)) { deferredSorts++; continue; }
            sort(layer, x, y, z); budget -= layer.quads;
        }
        uploads.flush();
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
            lastTranslucentOffset = offset; lastTranslucentCount = ordered.size();
            frames.submit(); commands.submit();
        }
        long spent = System.nanoTime() - start;
        translucentNanos += spent; translucentTimer.add(spent); translucentTimer.frame();
        translucentFrames++; translucentDraws += ordered.size();
        checkErrors("translucent");
    }

    /** Far-to-near translucent sections, sorted through packed (distance bits, index) keys in a reused array. */
    private List<Section> orderSections() {
        int count = layered.size();
        if (sectionKeys.length < count) sectionKeys = new long[Integer.highestOneBit(count) * 2];
        for (int i = 0; i < count; i++)
            // Non-negative float bits order like the floats themselves.
            sectionKeys[i] = (long) Float.floatToIntBits((float) distanceSquared(layered.get(i))) << 32 | i;
        Arrays.sort(sectionKeys, 0, count);
        drawOrder.clear();
        for (int i = count - 1; i >= 0; i--) drawOrder.add(layered.get((int) sectionKeys[i]));
        return drawOrder;
    }

    /** x/y/z: camera relative to the section origin. Farthest quad first; indices are relative to the layer's first vertex. */
    private void sort(Translucent layer, double x, double y, double z) {
        int quads = layer.quads;
        if (distances.length < quads) distances = new float[Integer.highestOneBit(quads) * 2];
        for (int quad = 0; quad < quads; quad++) {
            float dx = (float) (layer.centers[quad * 3] - x), dy = (float) (layer.centers[quad * 3 + 1] - y), dz = (float) (layer.centers[quad * 3 + 2] - z);
            distances[quad] = dx * dx + dy * dy + dz * dz;
        }
        int[] order = layer.order;
        if (layer.sorted && layer.moved(x, y, z) <= FULL_SORT_DISTANCE) {
            // The previous order is nearly right: descending insertion sort.
            for (int i = 1; i < quads; i++) {
                int quad = order[i]; float distance = distances[quad]; int j = i - 1;
                while (j >= 0 && distances[order[j]] < distance) { order[j + 1] = order[j]; j--; }
                order[j + 1] = quad;
            }
            insertionSorts++;
        } else {
            if (quadKeys.length < quads) quadKeys = new long[Integer.highestOneBit(quads) * 2];
            for (int quad = 0; quad < quads; quad++) quadKeys[quad] = (long) Float.floatToIntBits(distances[quad]) << 32 | quad;
            Arrays.sort(quadKeys, 0, quads);
            for (int i = 0; i < quads; i++) order[i] = (int) quadKeys[quads - 1 - i];
            fullSorts++;
        }
        writeOrder(layer);
        layer.sortedX = x; layer.sortedY = y; layer.sortedZ = z; layer.sorted = true;
        sorts++; sortedQuads += quads;
    }

    /** layer.order as quad indices (0 1 2, 2 3 0 per quad), written straight into the staging ring and copied once. */
    private void writeOrder(Translucent layer) {
        int bytes = layer.indices() * 4;
        int offset = uploads.reserve(bytes);
        if (offset < 0) {
            ByteBuffer direct = MemoryUtil.memAlloc(bytes);
            try { putOrder(layer, direct, 0); glNamedBufferSubData(indices.buffer(), (long) layer.firstIndex * 4, direct); }
            finally { MemoryUtil.memFree(direct); }
            return;
        }
        putOrder(layer, uploads.staging.mapped, offset);
        indices.copy(layer.firstIndex, uploads, offset, bytes);
    }
    private static void putOrder(Translucent layer, ByteBuffer out, int offset) {
        for (int i = 0, at = offset; i < layer.quads; i++, at += 24) {
            int base = layer.order[i] * 4;
            out.putInt(at, base).putInt(at + 4, base + 1).putInt(at + 8, base + 2).putInt(at + 12, base + 2).putInt(at + 16, base + 3).putInt(at + 20, base);
        }
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
        frame.putInt(offset + 128, slotCount).putInt(offset + 132, MAX_SECTIONS).putInt(offset + 136, counterSlot >= 0 ? 1 : 0);
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

    /** {frames, sections, slots, vertexCapacity, vertexGrows, uploadedBytes, stagingWaits, translucentDraws, sorts, opaqueNanos, translucentNanos}. */
    long[] stats() {
        return new long[]{frameCount, sections.size(), slotCount, vertices == null ? 0 : vertices.capacity(), vertices == null ? 0 : vertices.grows(),
                vertices == null ? 0 : vertices.uploadedBytes() + indices.uploadedBytes(), uploads == null ? 0 : uploads.staging.waits(), translucentDraws, sorts,
                opaqueNanos, translucentNanos};
    }

    private void clear() {
        discardCounts(); Arrays.fill(slotLayerMasks, 0); Arrays.fill(layerSectionCounts, 0);
        cpuSections.clear();
        for (Section section : sections.values()) release(section);
        if (uploads != null) uploads.flush();
        sections.clear(); layered.clear(); freeSlots.clear(); slotCount = 0;
        if (sectionBuffer != 0) glClearNamedBufferData(sectionBuffer, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, (ByteBuffer) null);
        camera = null; receivedMesh = false; previousCamera = null;
    }

    @Override public void close() {
        if (readbackMapped != null) pollCounts();
        discardCounts();
        Gl46Context.report("TERRAIN_SUBMISSIONS=" + Arrays.toString(submissionStats()));
        Gl46Context.report("TERRAIN_COST hizDispatches=" + hizDispatches + " hizBarriers=" + hizBarriers + " counterCpu{" + counterCpu + "} hizCpu{" + hizCpu + "} cullGpu{" + cullGpu
                + "} indirectGpu{" + drawsGpu + "} hizGpu{" + hizGpu + "}");
        Gl46Context.report("GL_STATE_COST scopes{full,diff,restores,avoidedRestores,sets,avoidedSets}=" + Arrays.toString(Gl46State.stats())
                + " cache{reads,queries,verified,invalidated}=" + Arrays.toString(GlStateCache.stats()));
        cullGpu.close(); drawsGpu.close(); hizGpu.close();
        if (cpuCommands != null) { MemoryUtil.memFree(cpuCommands); cpuCommands = null; }
        cpuSections.clear();
        if (vertices != null) Gl46Context.report("TERRAIN_CLOSE frames=" + frameCount + " translucentFrames=" + translucentFrames
                + " translucentDraws=" + translucentDraws + " sorts=" + sorts + " meshes=" + meshes + " peakSections=" + peakSections
                + " vertexCapacity=" + vertices.capacity() + " vertexGrows=" + vertices.grows() + " indexGrows=" + (indices == null ? 0 : indices.grows())
                + " uploadedBytes=" + (vertices.uploadedBytes() + (indices == null ? 0 : indices.uploadedBytes()))
                + " stagingWaits=" + uploads.staging.waits() + " uploads{issued,merged,direct}=" + Arrays.toString(uploads.stats()) + " freeRanges=" + vertices.freeRanges()
                + " sortedQuads=" + sortedQuads + " insertionSorts=" + insertionSorts + " fullSorts=" + fullSorts + " deferredSorts=" + deferredSorts
                + " culling{frames,tested,frustumVisible,occluded,layerDraws,vanillaMarked,hizBuilds,hizFrames,hizSkipped}=" + Arrays.toString(cullStats())
                + " hizNanos=" + hizNanos + " vanillaVisibility=" + visibilityKnown
                + " opaqueNanos=" + opaqueNanos + " translucentNanos=" + translucentNanos + " opaqueCpu{" + opaqueTimer + "} translucentCpu{" + translucentTimer + "}"
                + " vertexGrowCpu{grows,ns,maxNs}=" + Arrays.toString(vertices.growStats())
                + " rings{fences,waits,waitNanos,oversized} staging=" + Arrays.toString(uploads.staging.stats()) + " frames=" + Arrays.toString(frames.stats())
                + " commands=" + Arrays.toString(commands.stats()));
        sections.clear(); freeSlots.clear();
        for (Gl46Programs.Program program : new Gl46Programs.Program[]{cull, opaque, translucent, hiz}) if (program != null) glDeleteProgram(program.id());
        cull = opaque = translucent = hiz = null;
        if (pyramid != 0) glDeleteTextures(pyramid);
        if (pyramidParameters != 0) glDeleteBuffers(pyramidParameters);
        if (readback != 0) { glUnmapNamedBuffer(readback); glDeleteBuffers(readback); }
        pyramid = pyramidParameters = readback = 0; readbackMapped = null;
        for (AutoCloseable resource : new AutoCloseable[]{vertices, indices, uploads, frames, commands}) {
            try { if (resource != null) resource.close(); } catch (Exception ignored) { }
        }
        vertices = indices = null; uploads = null; frames = commands = null;
        for (int buffer : new int[]{sectionBuffer, drawBuffer, countBuffer, quadIndices}) if (buffer != 0) glDeleteBuffers(buffer);
        sectionBuffer = drawBuffer = countBuffer = quadIndices = 0;
        for (int vao : new int[]{opaqueVao, translucentVao}) if (vao != 0) glDeleteVertexArrays(vao);
        for (int sampler : new int[]{mipSampler, flatSampler, lightSampler}) if (sampler != 0) glDeleteSamplers(sampler);
        opaqueVao = translucentVao = mipSampler = flatSampler = lightSampler = 0;
    }
}
