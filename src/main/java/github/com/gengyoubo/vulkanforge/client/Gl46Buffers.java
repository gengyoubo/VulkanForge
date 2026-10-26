package github.com.gengyoubo.vulkanforge.client;

import java.nio.ByteBuffer;
import java.util.*;
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
     * Persistent, coherent write-only mapping split into segments that are reused round-robin. A segment's data can only be
     * overwritten after the cursor has wrapped all the way around, so it needs one fence, placed by the first {@link #submit()}
     * after the cursor left it (by then every GL command reading it has been issued; readers may be deferred, e.g. a whole
     * entity window). glFenceSync costs tens of microseconds on some drivers, and with multi-MB segments this is one fence
     * per few hundred frames instead of one per draw pass.
     */
    static final class Ring implements AutoCloseable {
        final int buffer;
        final ByteBuffer mapped;
        private final int segmentSize;
        private final long[] fences;
        /** Left by the cursor but not yet fenced. */
        private final boolean[] closed;
        private int segment, cursor;
        private long waits, waitNanos, fenceCount, oversized;
        Ring(int segmentSize, int segments) {
            this.segmentSize = segmentSize; fences = new long[segments]; closed = new boolean[segments];
            int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            buffer = immutable((long) segmentSize * segments, flags);
            mapped = glMapNamedBufferRange(buffer, 0, (long) segmentSize * segments, flags);
            if (mapped == null) { glDeleteBuffers(buffer); throw new IllegalStateException("Persistent buffer mapping failed"); }
        }
        int segmentSize() { return segmentSize; }
        /**
         * Byte offset into {@link #buffer}; -1 when the request is larger than one segment (counted; the caller grows the ring
         * or takes another path), or when the cursor would wrap onto a segment whose readers were not submitted yet (the caller
         * issues its pending readers, submits and retries).
         */
        int allocate(int bytes, int alignment) {
            if (bytes > segmentSize) { oversized++; return -1; }
            int offset = (cursor + alignment - 1) / alignment * alignment;
            if (offset + bytes > (segment + 1) * segmentSize) {
                int next = (segment + 1) % fences.length;
                if (closed[next]) return -1;
                closed[segment] = true;
                segment = next;
                if (fences[segment] != 0) {
                    long start = System.nanoTime(), fence = fences[segment];
                    fences[segment] = 0;
                    if (await(fence)) { waits++; waitNanos += System.nanoTime() - start; }
                    release(fence);
                }
                offset = segment * segmentSize;
            }
            cursor = offset + bytes;
            return offset;
        }
        /** After the GL commands reading this ring's allocations so far have been issued; a no-op unless a segment was left. */
        void submit() {
            boolean any = false;
            for (boolean left : closed) any |= left;
            if (!any) return;
            long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            fenceCount++;
            for (int i = 0; i < closed.length; i++) if (closed[i]) { long old = fences[i]; fences[i] = fence; closed[i] = false; release(old); }
        }
        /** {fences, CPU waits, waitNanos, oversized requests}. */
        long[] stats() { return new long[]{fenceCount, waits, waitNanos, oversized}; }
        long waits() { return waits; }
        @Override public void close() {
            for (int i = 0; i < fences.length; i++) { long fence = fences[i]; fences[i] = 0; release(fence); }
            glUnmapNamedBuffer(buffer); glDeleteBuffers(buffer); // GL keeps the store alive for queued readers
        }
        /** One fence may guard several segments; it is deleted once no segment refers to it. */
        private void release(long fence) {
            if (fence == 0) return;
            for (long other : fences) if (other == fence) return;
            glDeleteSync(fence);
        }
        /** True when the CPU actually had to block. */
        private static boolean await(long fence) {
            int status = glClientWaitSync(fence, 0, 0);
            if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) return false;
            do {
                status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
                if (status == GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed");
            } while (status == GL_TIMEOUT_EXPIRED);
            return true;
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
        /** Fragmentation indicator for reports: number of free ranges and the largest one, in elements. */
        String freeRanges() { return free.size() + "/" + free.values().stream().mapToInt(Integer::intValue).max().orElse(0); }

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
        /**
         * Contiguous parts starting at element {@code start}, written straight from the heap arrays into staging and queued as
         * one GPU copy (GL orders it after earlier draws that read the range, so in-flight frames still see the old contents).
         * Larger than a staging segment: written directly with glNamedBufferSubData.
         */
        void upload(int start, Uploader uploader, byte[]... parts) {
            int bytes = 0;
            for (byte[] part : parts) bytes += part.length;
            if (bytes == 0) return;
            int offset = uploader.reserve(bytes);
            long destination = (long) start * unit;
            if (offset < 0) {
                ByteBuffer direct = MemoryUtil.memAlloc(bytes);
                try { for (byte[] part : parts) direct.put(part); glNamedBufferSubData(buffer, destination, direct.flip()); }
                finally { MemoryUtil.memFree(direct); }
                uploader.direct++;
            } else {
                int at = offset;
                for (byte[] part : parts) { uploader.staging.mapped.put(at, part); at += part.length; }
                uploader.enqueue(this, offset, destination, bytes);
            }
            uploadedBytes += bytes;
        }
        /** {@code bytes} the caller already wrote at {@code offset}, a range it got from {@link Uploader#reserve}. */
        void copy(int start, Uploader uploader, int offset, int bytes) {
            uploader.enqueue(this, offset, (long) start * unit, bytes);
            uploadedBytes += bytes;
        }
        private java.util.Map.Entry<Integer, Integer> first(int count) {
            for (var entry : free.entrySet()) if (entry.getValue() >= count) return entry;
            return null;
        }
        private void grow(int needed) {
            long start = System.nanoTime();
            try { resize(needed); } finally { long spent = System.nanoTime() - start; growNanos += spent; maxGrowNanos = Math.max(maxGrowNanos, spent); }
        }
        private long growNanos, maxGrowNanos;
        /** {grows, total ns, worst ns}: CPU time of creating the larger buffer and queuing the GPU copy. */
        long[] growStats() { return new long[]{grows, growNanos, maxGrowNanos}; }
        private void resize(int needed) {
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

    /**
     * Batches staging-to-arena copies. Writes land in the persistent staging ring at once; the GPU copies are queued, merged
     * when both source and destination continue the previous copy (a section's layers, consecutive fresh sections), and issued
     * by {@link #flush()} before the draws that need them, followed by a single staging submit. The destination buffer is
     * resolved at flush time, so an arena that grew in between receives the copy in its new buffer.
     */
    static final class Uploader implements AutoCloseable {
        final Ring staging;
        private final List<Arena> targets = new ArrayList<>();
        private long[] copies = new long[3 * 64];
        private int count;
        private long issued, merged, direct;
        Uploader(int segmentSize, int segments) { staging = new Ring(segmentSize, segments); }

        /** Staging offset for {@code bytes}; issues the queue first when the ring must wrap. -1: larger than a segment. */
        int reserve(int bytes) {
            int offset = staging.allocate(bytes, 16);
            if (offset < 0 && bytes <= staging.segmentSize()) { flush(); offset = staging.allocate(bytes, 16); }
            return offset;
        }
        void enqueue(Arena target, int source, long destination, int bytes) {
            int last = count - 1;
            if (last >= 0 && targets.get(last) == target && copies[last * 3] + copies[last * 3 + 2] == source
                    && copies[last * 3 + 1] + copies[last * 3 + 2] == destination) { copies[last * 3 + 2] += bytes; merged++; return; }
            if (copies.length < (count + 1) * 3) copies = Arrays.copyOf(copies, copies.length * 2);
            copies[count * 3] = source; copies[count * 3 + 1] = destination; copies[count * 3 + 2] = bytes;
            targets.add(target); count++;
        }
        void flush() {
            for (int i = 0; i < count; i++) glCopyNamedBufferSubData(staging.buffer, targets.get(i).buffer(), copies[i * 3], copies[i * 3 + 1], copies[i * 3 + 2]);
            issued += count; count = 0; targets.clear();
            staging.submit();
        }
        /** {issued copies, merged writes, direct (oversized) uploads}. */
        long[] stats() { return new long[]{issued, merged, direct}; }
        @Override public void close() { count = 0; targets.clear(); staging.close(); }
    }
}
