package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import java.nio.ByteBuffer;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

/** Device-wide memory blocks. Buffers and optimal images use separate pools to honor bufferImageGranularity. */
final class GpuMemory implements AutoCloseable {
    private final VulkanContext context;
    private final Map<Integer, List<Block>> pools = new HashMap<>();
    private long reserved, used, peak, allocations;
    private final class Block implements AutoCloseable {
        final long size;
        final int key;
        final boolean dedicated;
        final TreeMap<Long, Long> free = new TreeMap<>();
        long memory, address;
        Block(int key, int type, long size, boolean host, long image, long buffer) {
            this.key = key; this.size = size; dedicated = image != 0 || buffer != 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var handle = stack.mallocLong(1);
                var info = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(size).memoryTypeIndex(type);
                if (dedicated) info.pNext(VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image).buffer(buffer).address());
                check(vkAllocateMemory(context.device, info, null, handle), "allocate GPU memory block"); memory = handle.get(0);
                if (host) {
                    var pointer = stack.mallocPointer(1); check(vkMapMemory(context.device, memory, 0, size, 0, pointer), "map GPU memory block"); address = pointer.get(0);
                }
                free.put(0L, size); reserved += size;
            } catch (Throwable error) { if (memory != 0) vkFreeMemory(context.device, memory, null); memory = 0; throw error; }
        }
        Allocation take(long bytes, long alignment) {
            for (var entry : free.entrySet()) {
                long start = (entry.getKey() + alignment - 1) & -alignment, end = entry.getKey() + entry.getValue();
                if (start + bytes > end) continue;
                long before = entry.getKey(); free.remove(before);
                if (start > before) free.put(before, start - before);
                if (start + bytes < end) free.put(start + bytes, end - start - bytes);
                used += bytes; peak = Math.max(peak, used); allocations++;
                return new Allocation(this, start, bytes);
            }
            return null;
        }
        void release(long offset, long bytes) {
            used -= bytes;
            var before = free.lowerEntry(offset);
            if (before != null && before.getKey() + before.getValue() == offset) { offset = before.getKey(); bytes += before.getValue(); free.remove(before.getKey()); }
            var after = free.ceilingEntry(offset);
            if (after != null && offset + bytes == after.getKey()) { bytes += after.getValue(); free.remove(after.getKey()); }
            free.put(offset, bytes);
            if (dedicated) { pools.get(key).remove(this); close(); }
        }
        @Override public void close() {
            if (memory != 0) {
                if (address != 0) vkUnmapMemory(context.device, memory);
                vkFreeMemory(context.device, memory, null); memory = address = 0; reserved -= size;
            }
        }
    }
    final class Allocation implements AutoCloseable {
        private final Block block;
        final long offset, size;
        private boolean released;
        Allocation(Block block, long offset, long size) { this.block = block; this.offset = offset; this.size = size; }
        long memory() { return block.memory; }
        ByteBuffer mapped(int size) {
            if (block.address == 0 || size > this.size) throw new IllegalStateException("Unmapped GPU allocation");
            return MemoryUtil.memByteBuffer(block.address + offset, size).order(java.nio.ByteOrder.nativeOrder());
        }
        @Override public void close() { if (!released) { released = true; block.release(offset, size); } }
    }
    GpuMemory(VulkanContext context) { this.context = context; }
    Allocation allocate(VkMemoryRequirements requirements, int flags, boolean imagePool, long dedicatedImage, long dedicatedBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int type = GpuBuffer.memoryType(context, stack, requirements.memoryTypeBits(), flags);
            boolean host = (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0, dedicated = dedicatedImage != 0 || dedicatedBuffer != 0;
            int key = type * 4 + (imagePool ? 2 : 0) + (host ? 1 : 0);
            var blocks = pools.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!dedicated) for (Block block : blocks) if (!block.dedicated) {
                Allocation result = block.take(requirements.size(), requirements.alignment()); if (result != null) return result;
            }
            long size = dedicated ? requirements.size() : Math.max(host ? 16L << 20 : 64L << 20, requirements.size());
            Block block = new Block(key, type, size, host, dedicatedImage, dedicatedBuffer); blocks.add(block);
            return Objects.requireNonNull(block.take(requirements.size(), requirements.alignment()));
        }
    }
    long[] stats() { return new long[]{reserved, used, peak, allocations, pools.values().stream().mapToLong(List::size).sum()}; }
    @Override public void close() {
        if (used != 0) throw new IllegalStateException("Live GPU memory at shutdown: " + used);
        pools.values().forEach(blocks -> blocks.forEach(Block::close)); pools.clear();
    }
}
