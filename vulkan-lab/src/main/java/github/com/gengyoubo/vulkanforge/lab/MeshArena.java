package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import java.util.function.Consumer;
import org.lwjgl.vulkan.*;
import static dev.vulkanforge.bridge.WorldProtocol.STRIDE;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Device-local opaque vertex arena. Stable offsets let one indirect draw batch cover every section. */
final class MeshArena implements AutoCloseable {
    final class Allocation implements AutoCloseable {
        final int firstVertex, count;
        private boolean freed;
        Allocation(int firstVertex, int count) { this.firstVertex = firstVertex; this.count = count; }
        @Override public void close() { if (!freed) { freed = true; free(firstVertex, count); } }
        /**
         * Section rebuild: overwrite this range in place when the new mesh fits, freeing only the unused tail; null otherwise.
         * In-flight draws of the old vertices were submitted earlier, and the upload barrier waits for all prior commands.
         */
        Allocation replace(byte[] bytes) {
            int next = bytes.length / STRIDE;
            if (freed || next == 0 || next > count || bytes.length % STRIDE != 0) return null;
            write(firstVertex, bytes);
            freed = true; reuses++;
            if (next < count) free(firstVertex + next, count - next);
            return new Allocation(firstVertex, next);
        }
    }
    private final VulkanContext context;
    private final Consumer<Runnable> retire;
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private GpuBuffer buffer;
    private int capacity;
    private long copies, grows, copiedVertices, reuses;
    MeshArena(VulkanContext context, Consumer<Runnable> retire) { this.context = context; this.retire = retire; }
    long buffer() { return buffer == null ? 0 : buffer.buffer; }
    /** {capacity, uploads, grows, verticesCopiedByGrowth, inPlaceReplacements}. */
    long[] stats() { return new long[]{capacity, copies, grows, copiedVertices, reuses}; }

    Allocation upload(byte[] bytes) {
        if (bytes.length == 0 || bytes.length % STRIDE != 0) throw new IllegalArgumentException("Invalid arena mesh");
        int count = bytes.length / STRIDE;
        var region = firstFit(count);
        if (region == null) { grow(count); region = firstFit(count); }
        int start = region.getKey(), available = region.getValue(); free.remove(start);
        if (available > count) free.put(start + count, available - count);
        try { write(start, bytes); return new Allocation(start, count); }
        catch (Throwable error) { free(start, count); throw error; }
    }
    private Map.Entry<Integer, Integer> firstFit(int count) {
        for (var entry : free.entrySet()) if (entry.getValue() >= count) return entry;
        return null;
    }
    private void write(int start, byte[] bytes) {
        GpuBuffer destination = buffer;
        context.uploads.submit(bytes, (command, staging, offset, stack) -> {
            var barrier = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(destination.buffer).offset((long) start * STRIDE).size(bytes.length);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
            vkCmdCopyBuffer(command, staging.buffer, destination.buffer, VkBufferCopy.calloc(1, stack).srcOffset(offset).dstOffset((long) start * STRIDE).size(bytes.length));
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(readStages()).dstAccessMask(readAccess());
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
        }); copies++;
    }

    private void grow(int needed) {
        // A free tail merges with the new space, so only the remainder has to be added; grow ~1.5x in 64Ki-vertex steps.
        var tail = free.lowerEntry(capacity);
        int missing = tail != null && tail.getKey() + tail.getValue() == capacity ? needed - tail.getValue() : needed;
        int nextCapacity = Math.max(1 << 16, capacity + Math.max(missing, capacity / 2) + 0xffff & ~0xffff);
        if ((long) nextCapacity * STRIDE > 256L << 20) throw new IllegalStateException("Opaque mesh arena exceeds 256 MiB");
        GpuBuffer next = new GpuBuffer(context, nextCapacity * STRIDE,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false);
        GpuBuffer old = buffer; int oldCapacity = capacity;
        if (old != null) {
            // Only live allocations (the gaps between free ranges) are copied; freed space is left undefined in the new buffer.
            List<int[]> live = new ArrayList<>();
            int cursor = 0;
            for (var range : free.entrySet()) {
                if (range.getKey() > cursor) live.add(new int[]{cursor, range.getKey() - cursor});
                cursor = Math.max(cursor, range.getKey() + range.getValue());
            }
            if (cursor < oldCapacity) live.add(new int[]{cursor, oldCapacity - cursor});
            if (!live.isEmpty()) {
                long ticket = context.uploads.submit(new byte[0], (command, staging, offset, stack) -> {
                    var barrier = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default()
                            .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT)
                            .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(old.buffer).size((long) oldCapacity * STRIDE);
                    vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
                    var regions = VkBufferCopy.malloc(live.size());
                    try {
                        for (int i = 0; i < live.size(); i++) {
                            long start = (long) live.get(i)[0] * STRIDE;
                            regions.get(i).srcOffset(start).dstOffset(start).size((long) live.get(i)[1] * STRIDE);
                        }
                        vkCmdCopyBuffer(command, old.buffer, next.buffer, regions);
                    } finally { regions.free(); }
                    barrier.buffer(next.buffer).srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                            .dstStageMask(readStages()).dstAccessMask(readAccess()).size((long) nextCapacity * STRIDE);
                    vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
                });
                for (int[] range : live) copiedVertices += range[1];
                context.uploads.retire(ticket, () -> retire.accept(old::close));
            } else retire.accept(old::close);
        }
        buffer = next; capacity = nextCapacity; free(oldCapacity, nextCapacity - oldCapacity); grows++;
    }
    private long readStages() { return VK_PIPELINE_STAGE_2_VERTEX_ATTRIBUTE_INPUT_BIT | (context.meshShaderNV ? KHRSynchronization2.VK_PIPELINE_STAGE_2_MESH_SHADER_BIT_NV : 0); }
    private long readAccess() { return VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT | (context.meshShaderNV ? VK_ACCESS_2_SHADER_STORAGE_READ_BIT : 0); }
    private void free(int start, int length) {
        var before = free.lowerEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) { start = before.getKey(); length += before.getValue(); free.remove(before.getKey()); }
        var after = free.ceilingEntry(start);
        if (after != null && start + length == after.getKey()) { length += after.getValue(); free.remove(after.getKey()); }
        free.put(start, length);
    }
    @Override public void close() { context.uploads.drain(); if (buffer != null) { buffer.close(); buffer = null; } free.clear(); }
}
