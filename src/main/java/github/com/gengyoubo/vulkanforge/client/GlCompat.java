package github.com.gengyoubo.vulkanforge.client;

import java.nio.*;
import java.util.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL43C.*;

/** Version-bounded operations shared by the GL terrain and entity passes. */
final class GlCompat {
    private static int minor = 6, parameterBuffer, instanceIndices;
    private static final Set<Integer> vaos = new HashSet<>();
    private record Mirror(ByteBuffer bytes, boolean read) { }
    private static final Map<Integer, Mirror> mirrors = new HashMap<>();
    private static final Map<Integer, int[]> dirty = new HashMap<>();
    private GlCompat() { }
    static void configure(int versionMinor) { minor = versionMinor; }
    static boolean core46() { return minor >= 6 && GL.getCapabilities().OpenGL46; }
    static boolean storage() { return minor >= 4 && GL.getCapabilities().OpenGL44; }
    private static boolean dsa() { return minor >= 5 && GL.getCapabilities().OpenGL45; }
    static String paths() { return "DSA=" + dsa() + " PERSISTENT=" + storage() + " INDIRECT_COUNT=" + core46() + " SPIRV=" + core46(); }
    static void written(int buffer, int offset, int bytes) {
        if (!mirrors.containsKey(buffer)) return;
        dirty.compute(buffer, (key, old) -> old == null ? new int[]{offset, offset + bytes}
                : new int[]{Math.min(old[0], offset), Math.max(old[1], offset + bytes)});
    }
    private static void flush(int buffer) {
        int[] range = dirty.remove(buffer);
        if (range == null) return;
        ByteBuffer bytes = mirrors.get(buffer).bytes().duplicate(); bytes.position(range[0]).limit(range[1]);
        glNamedBufferSubData(buffer, range[0], bytes);
    }
    private static void flushAll() { for (int buffer : new ArrayList<>(dirty.keySet())) flush(buffer); }
    private static int bind(int buffer) { int previous = glGetInteger(GL_COPY_WRITE_BUFFER_BINDING); glBindBuffer(GL_COPY_WRITE_BUFFER, buffer); return previous; }
    private static void restore(int previous) { glBindBuffer(GL_COPY_WRITE_BUFFER, previous); }
    static int glCreateBuffers() { return dsa() ? org.lwjgl.opengl.GL45C.glCreateBuffers() : glGenBuffers(); }
    static void glNamedBufferStorage(int buffer, long size, int flags) {
        if (dsa()) { org.lwjgl.opengl.GL45C.glNamedBufferStorage(buffer, size, flags); return; }
        int old = bind(buffer);
        try { if (storage()) org.lwjgl.opengl.GL44C.glBufferStorage(GL_COPY_WRITE_BUFFER, size, flags); else glBufferData(GL_COPY_WRITE_BUFFER, size, GL_DYNAMIC_DRAW); }
        finally { restore(old); }
    }
    static void glNamedBufferStorage(int buffer, ByteBuffer bytes, int flags) {
        if (dsa()) { org.lwjgl.opengl.GL45C.glNamedBufferStorage(buffer, bytes, flags); return; }
        int old = bind(buffer);
        try { if (storage()) org.lwjgl.opengl.GL44C.glBufferStorage(GL_COPY_WRITE_BUFFER, bytes, flags); else glBufferData(GL_COPY_WRITE_BUFFER, bytes, GL_DYNAMIC_DRAW); }
        finally { restore(old); }
    }
    static void glNamedBufferSubData(int buffer, long offset, ByteBuffer bytes) {
        if (dsa()) { org.lwjgl.opengl.GL45C.glNamedBufferSubData(buffer, offset, bytes); return; }
        int old = bind(buffer); try { glBufferSubData(GL_COPY_WRITE_BUFFER, offset, bytes); } finally { restore(old); }
    }
    static ByteBuffer glMapNamedBufferRange(int buffer, long offset, long size, int flags) {
        if (!storage()) {
            if (offset != 0) throw new IllegalArgumentException("Mirror mapping must start at zero");
            ByteBuffer bytes = MemoryUtil.memCalloc(Math.toIntExact(size));
            mirrors.put(buffer, new Mirror(bytes, (flags & GL_MAP_READ_BIT) != 0)); return bytes;
        }
        if (dsa()) return org.lwjgl.opengl.GL45C.glMapNamedBufferRange(buffer, offset, size, flags);
        int old = bind(buffer); try { return glMapBufferRange(GL_COPY_WRITE_BUFFER, offset, size, flags); } finally { restore(old); }
    }
    static boolean glUnmapNamedBuffer(int buffer) {
        Mirror mirror = mirrors.remove(buffer); dirty.remove(buffer);
        if (mirror != null) { MemoryUtil.memFree(mirror.bytes()); return true; }
        if (dsa()) return org.lwjgl.opengl.GL45C.glUnmapNamedBuffer(buffer);
        int old = bind(buffer); try { return glUnmapBuffer(GL_COPY_WRITE_BUFFER); } finally { restore(old); }
    }
    static void glCopyNamedBufferSubData(int source, int destination, long from, long to, long size) {
        flush(source);
        if (dsa()) org.lwjgl.opengl.GL45C.glCopyNamedBufferSubData(source, destination, from, to, size);
        else {
            int read = glGetInteger(GL_COPY_READ_BUFFER_BINDING), write = bind(destination);
            try { glBindBuffer(GL_COPY_READ_BUFFER, source); glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, from, to, size); }
            finally { glBindBuffer(GL_COPY_READ_BUFFER, read); restore(write); }
        }
        Mirror mirror = mirrors.get(destination);
        if (mirror != null && mirror.read()) {
            ByteBuffer bytes = mirror.bytes().duplicate(); bytes.position(Math.toIntExact(to)).limit(Math.toIntExact(to + size));
            int old = bind(destination); try { glGetBufferSubData(GL_COPY_WRITE_BUFFER, to, bytes); } finally { restore(old); }
        }
    }
    static void glClearNamedBufferData(int buffer, int internal, int format, int type, ByteBuffer value) {
        if (dsa()) org.lwjgl.opengl.GL45C.glClearNamedBufferData(buffer, internal, format, type, value);
        else { int old = bind(buffer); try { glClearBufferData(GL_COPY_WRITE_BUFFER, internal, format, type, value); } finally { restore(old); } }
    }
    static void glClearNamedBufferSubData(int buffer, int internal, long offset, long size, int format, int type, IntBuffer value) {
        if (dsa()) org.lwjgl.opengl.GL45C.glClearNamedBufferSubData(buffer, internal, offset, size, format, type, value);
        else { int old = bind(buffer); try { glClearBufferSubData(GL_COPY_WRITE_BUFFER, internal, offset, size, format, type, value); } finally { restore(old); } }
    }
    private static void vao(int vao, Runnable action) {
        int old = glGetInteger(GL_VERTEX_ARRAY_BINDING); glBindVertexArray(vao);
        try { action.run(); } finally { glBindVertexArray(old); }
    }
    static int glCreateVertexArrays() {
        int vao = dsa() ? org.lwjgl.opengl.GL45C.glCreateVertexArrays() : glGenVertexArrays();
        if (!core46()) {
            if (instanceIndices == 0) {
                instanceIndices = glCreateBuffers();
                ByteBuffer bytes = MemoryUtil.memAlloc(4 << 20);
                try { for (int i = 0; i < (1 << 20); i++) bytes.putInt(i); glNamedBufferStorage(instanceIndices, bytes.flip(), 0); }
                finally { MemoryUtil.memFree(bytes); }
            }
            vao(vao, () -> { glBindVertexBuffer(15, instanceIndices, 0, 4); glVertexAttribIFormat(15, 1, GL_INT, 0);
                glVertexAttribBinding(15, 15); glVertexBindingDivisor(15, 1); glEnableVertexAttribArray(15); });
            vaos.add(vao);
        }
        return vao;
    }
    static void glDeleteVertexArrays(int vao) {
        org.lwjgl.opengl.GL30C.glDeleteVertexArrays(vao);
        if (vaos.remove(vao) && vaos.isEmpty()) { glDeleteBuffers(instanceIndices); instanceIndices = 0; }
    }
    static void glVertexArrayAttribFormat(int vao, int attribute, int size, int type, boolean normalized, int offset) {
        if (dsa()) org.lwjgl.opengl.GL45C.glVertexArrayAttribFormat(vao, attribute, size, type, normalized, offset);
        else vao(vao, () -> glVertexAttribFormat(attribute, size, type, normalized, offset));
    }
    static void glVertexArrayAttribIFormat(int vao, int attribute, int size, int type, int offset) {
        if (dsa()) org.lwjgl.opengl.GL45C.glVertexArrayAttribIFormat(vao, attribute, size, type, offset);
        else vao(vao, () -> glVertexAttribIFormat(attribute, size, type, offset));
    }
    static void glVertexArrayAttribBinding(int vao, int attribute, int binding) {
        if (dsa()) org.lwjgl.opengl.GL45C.glVertexArrayAttribBinding(vao, attribute, binding);
        else vao(vao, () -> glVertexAttribBinding(attribute, binding));
    }
    static void glEnableVertexArrayAttrib(int vao, int attribute) {
        if (dsa()) org.lwjgl.opengl.GL45C.glEnableVertexArrayAttrib(vao, attribute);
        else vao(vao, () -> glEnableVertexAttribArray(attribute));
    }
    static void glVertexArrayVertexBuffer(int vao, int binding, int buffer, long offset, int stride) {
        if (dsa()) org.lwjgl.opengl.GL45C.glVertexArrayVertexBuffer(vao, binding, buffer, offset, stride);
        else vao(vao, () -> glBindVertexBuffer(binding, buffer, offset, stride));
    }
    static void glVertexArrayElementBuffer(int vao, int buffer) {
        if (dsa()) org.lwjgl.opengl.GL45C.glVertexArrayElementBuffer(vao, buffer);
        else vao(vao, () -> glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer));
    }
    static int glCreateSamplers() { return dsa() ? org.lwjgl.opengl.GL45C.glCreateSamplers() : glGenSamplers(); }
    static int glCreateTextures(int target) {
        if (dsa()) return org.lwjgl.opengl.GL45C.glCreateTextures(target);
        if (target != GL_TEXTURE_2D) throw new IllegalArgumentException("Expected 2D texture");
        return glGenTextures();
    }
    static void glTextureStorage2D(int texture, int levels, int format, int width, int height) {
        if (dsa()) { org.lwjgl.opengl.GL45C.glTextureStorage2D(texture, levels, format, width, height); return; }
        int old = glGetInteger(GL_TEXTURE_BINDING_2D); glBindTexture(GL_TEXTURE_2D, texture);
        try { glTexStorage2D(GL_TEXTURE_2D, levels, format, width, height); } finally { glBindTexture(GL_TEXTURE_2D, old); }
    }
    static void glBindTextureUnit(int unit, int texture) {
        if (dsa()) { org.lwjgl.opengl.GL45C.glBindTextureUnit(unit, texture); return; }
        int active = glGetInteger(GL_ACTIVE_TEXTURE); glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture); glActiveTexture(active);
    }
    static void glBindBuffer(int target, int buffer) {
        if (target == org.lwjgl.opengl.GL46C.GL_PARAMETER_BUFFER) { parameterBuffer = buffer; if (!core46()) return; }
        org.lwjgl.opengl.GL15C.glBindBuffer(target, buffer);
    }
    static void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        flush(buffer); org.lwjgl.opengl.GL30C.glBindBufferRange(target, index, buffer, offset, size);
    }
    static void glDispatchCompute(int x, int y, int z) { flushAll(); org.lwjgl.opengl.GL43C.glDispatchCompute(x, y, z); }
    static void glMultiDrawElementsIndirect(int mode, int type, long offset, int count, int stride) {
        flushAll(); org.lwjgl.opengl.GL43C.glMultiDrawElementsIndirect(mode, type, offset, count, stride);
    }
    static void glMultiDrawElementsIndirectCount(int mode, int type, long offset, long countOffset, int maximum, int stride) {
        flushAll();
        if (core46()) org.lwjgl.opengl.GL46C.glMultiDrawElementsIndirectCount(mode, type, offset, countOffset, maximum, stride);
        else {
            int old = bind(parameterBuffer);
            int[] count = new int[1]; try { glGetBufferSubData(GL_COPY_WRITE_BUFFER, countOffset, count); } finally { restore(old); }
            glMultiDrawElementsIndirect(mode, type, offset, Math.min(maximum, Math.max(0, count[0])), stride);
        }
    }
}
