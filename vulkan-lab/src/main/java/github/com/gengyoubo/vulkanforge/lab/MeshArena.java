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
    }
    private final VulkanContext context;
    private final Consumer<Runnable> retire;
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private GpuBuffer buffer;
    private int capacity;
    private long copies, grows;
    MeshArena(VulkanContext context, Consumer<Runnable> retire) { this.context = context; this.retire = retire; }
    long buffer() { return buffer == null ? 0 : buffer.buffer; }
    long[] stats() { return new long[]{capacity, copies, grows}; }

    Allocation upload(byte[] bytes) {
        if (bytes.length == 0 || bytes.length % STRIDE != 0) throw new IllegalArgumentException("Invalid arena mesh");
        int count = bytes.length / STRIDE;
        var region = free.entrySet().stream().filter(e -> e.getValue() >= count).findFirst().orElse(null);
        if (region == null) { grow(count); region = free.entrySet().stream().filter(e -> e.getValue() >= count).findFirst().orElseThrow(); }
        int start = region.getKey(), available = region.getValue(); free.remove(start);
        if (available > count) free.put(start + count, available - count);
        GpuBuffer destination = buffer;
        try {
            context.uploads.submit(bytes, (command, staging, stack) -> {
                var barrier = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(destination.buffer).offset((long) start * STRIDE).size(bytes.length);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
                vkCmdCopyBuffer(command, staging.buffer, destination.buffer, VkBufferCopy.calloc(1, stack).dstOffset((long) start * STRIDE).size(bytes.length));
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(readStages()).dstAccessMask(readAccess());
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
            }); copies++;
            return new Allocation(start, count);
        } catch (Throwable error) { free(start, count); throw error; }
    }

    private void grow(int needed) {
        int nextCapacity = Math.max(1 << 16, capacity);
        do { if ((long) nextCapacity * STRIDE > 256L << 20) throw new IllegalStateException("Opaque mesh arena exceeds 256 MiB");
            if (nextCapacity - capacity >= needed) break; nextCapacity *= 2;
        } while (true);
        GpuBuffer next = new GpuBuffer(context, nextCapacity * STRIDE,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false);
        GpuBuffer old = buffer; int oldCapacity = capacity;
        if (old != null) {
            long ticket = context.uploads.submit(new byte[0], (command, staging, stack) -> {
                var barrier = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(old.buffer).size((long) oldCapacity * STRIDE);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
                vkCmdCopyBuffer(command, old.buffer, next.buffer, VkBufferCopy.calloc(1, stack).size((long) oldCapacity * STRIDE));
                barrier.buffer(next.buffer).srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(readStages()).dstAccessMask(readAccess());
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
            });
            context.uploads.retire(ticket, () -> retire.accept(old::close));
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
