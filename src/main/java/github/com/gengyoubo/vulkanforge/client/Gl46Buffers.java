package github.com.gengyoubo.vulkanforge.client;

import java.nio.ByteBuffer;
import java.util.TreeMap;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL44C.*;
import static org.lwjgl.opengl.GL45C.*;

/** GL 4.4 immutable storage / persistent mapping and GL 4.5 DSA buffers for the OpenGL 4.6 terrain renderer. */
final class Gl46Buffers {
    private Gl46Buffers() { }

    static int immutable(long bytes, int flags) {
        int buffer = glCreateBuffers();
        glNamedBufferStorage(buffer, bytes, flags);
        return buffer;
    }

    /**
     * Persistent, coherent write-only mapping split into fenced segments. A segment gets a fence when the cursor leaves it
     * and is waited on before it is reused, so every GPU reader of an allocation must be issued before the next allocate().
     */
    static final class Ring implements AutoCloseable {
        final int buffer;
        final ByteBuffer mapped;
        private final int segmentSize;
        private final long[] fences;
        private int segment, cursor;
        private long waits;
        Ring(int segmentSize, int segments) {
            this.segmentSize = segmentSize; fences = new long[segments];
            int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            buffer = immutable((long) segmentSize * segments, flags);
            mapped = glMapNamedBufferRange(buffer, 0, (long) segmentSize * segments, flags);
            if (mapped == null) { glDeleteBuffers(buffer); throw new IllegalStateException("Persistent buffer mapping failed"); }
        }
        /** Byte offset into {@link #buffer}, or -1 when the request is larger than one segment. */
        int allocate(int bytes, int alignment) {
            if (bytes > segmentSize) return -1;
            int offset = (cursor + alignment - 1) / alignment * alignment;
            if (offset + bytes > (segment + 1) * segmentSize) {
                fences[segment] = fence(fences[segment]);
                segment = (segment + 1) % fences.length;
                if (fences[segment] != 0) { waits += await(fences[segment]); fences[segment] = 0; }
                offset = segment * segmentSize;
            }
            cursor = offset + bytes;
            return offset;
        }
        long waits() { return waits; }
        @Override public void close() {
            for (long fence : fences) if (fence != 0) glDeleteSync(fence);
            glUnmapNamedBuffer(buffer); glDeleteBuffers(buffer);
        }
        private static long fence(long previous) {
            if (previous != 0) glDeleteSync(previous);
            return glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        /** 1 when the CPU actually had to block. */
        private static int await(long fence) {
            try {
                int status = glClientWaitSync(fence, 0, 0);
                if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) return 0;
                do {
                    status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
                    if (status == GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed");
                } while (status == GL_TIMEOUT_EXPIRED);
                return 1;
            } finally { glDeleteSync(fence); }
        }
    }

    /**
     * Device-local arena of fixed-size elements with first-fit allocation and coalescing; grows by GPU copy, so offsets stay
     * stable and one indirect draw can address every section. Uploads go through the persistent staging ring.
     */
    static final class Arena implements AutoCloseable {
        private final int unit;
        private final long limitBytes;
        private final String name;
        private final TreeMap<Integer, Integer> free = new TreeMap<>();
        private int buffer, capacity;
        private long grows, uploadedBytes;
        Arena(String name, int unit, int initial, long limitBytes) { this.name = name; this.unit = unit; this.limitBytes = limitBytes; grow(initial); }
        int buffer() { return buffer; }
        int capacity() { return capacity; }
        long grows() { return grows; }
        long uploadedBytes() { return uploadedBytes; }

        int allocate(int count) {
            if (count <= 0) throw new IllegalArgumentException("Empty " + name + " allocation");
            var region = first(count);
            if (region == null) { grow(count); region = first(count); }
            int start = region.getKey(), available = region.getValue(); free.remove(start);
            if (available > count) free.put(start + count, available - count);
            return start;
        }
        void free(int start, int count) {
            var before = free.lowerEntry(start);
            if (before != null && before.getKey() + before.getValue() == start) { start = before.getKey(); count += before.getValue(); free.remove(before.getKey()); }
            var after = free.ceilingEntry(start);
            if (after != null && start + count == after.getKey()) { count += after.getValue(); free.remove(after.getKey()); }
            free.put(start, count);
        }
        /** GL orders the copy after earlier draws that read the range, so in-flight frames still see the old contents. */
        void upload(int start, ByteBuffer data, Ring staging) {
            int bytes = data.remaining();
            int offset = staging.allocate(bytes, 16);
            if (offset >= 0) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(staging.mapped) + offset, bytes);
                glCopyNamedBufferSubData(staging.buffer, buffer, offset, (long) start * unit, bytes);
            } else glNamedBufferSubData(buffer, (long) start * unit, data);
            uploadedBytes += bytes;
        }
        void upload(int start, byte[] data, Ring staging) {
            ByteBuffer direct = MemoryUtil.memAlloc(data.length);
            try { direct.put(data).flip(); upload(start, direct, staging); }
            finally { MemoryUtil.memFree(direct); }
        }
        private java.util.Map.Entry<Integer, Integer> first(int count) {
            for (var entry : free.entrySet()) if (entry.getValue() >= count) return entry;
            return null;
        }
        private void grow(int needed) {
            long next = Math.max(capacity, 1);
            while (next - capacity < needed) next *= 2;
            if (next * unit > limitBytes) throw new IllegalStateException(name + " arena exceeds " + (limitBytes >> 20) + " MiB");
            int replacement = immutable(next * unit, GL_DYNAMIC_STORAGE_BIT);
            if (buffer != 0) {
                glCopyNamedBufferSubData(buffer, replacement, 0, 0, (long) capacity * unit);
                glDeleteBuffers(buffer); // deletion is deferred by GL until queued draws are done with it
                grows++;
            }
            int old = capacity;
            buffer = replacement; capacity = (int) next;
            free(old, capacity - old);
        }
        @Override public void close() { if (buffer != 0) glDeleteBuffers(buffer); buffer = 0; free.clear(); }
    }
}
