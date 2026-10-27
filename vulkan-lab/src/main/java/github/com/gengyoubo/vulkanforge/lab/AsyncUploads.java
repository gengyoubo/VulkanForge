package github.com.gengyoubo.vulkanforge.lab;

import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Batched staging uploads on the graphics queue. Uploads append to one open command buffer and one staging buffer;
 * {@link #flush()} submits the batch (called before every render submit that may read uploaded data, and once per frame).
 * Submission order and the recorders' barriers publish uploads; the CPU waits only under backpressure or teardown.
 */
final class AsyncUploads implements AutoCloseable {
    /** offset: where this upload's bytes start in the batch staging buffer. */
    @FunctionalInterface interface Recorder { void record(VkCommandBuffer command, GpuBuffer staging, long offset, MemoryStack stack); }
    private static final int SLOTS = 8, ALIGNMENT = 256;
    /** Bytes in flight before admission refuses / submit waits; a batch closes early once it holds BATCH_BYTES. */
    static final int BATCH_BYTES = 16 << 20;
    private static final long BYTE_BUDGET = 64L << 20;
    /** used: staging write offset; pending: payload bytes counted against BYTE_BUDGET until the batch completes. */
    private static final class Slot { VkCommandBuffer command; GpuBuffer staging; long fence, ticket, pending; int used, uploads; boolean open; }
    private final VulkanContext context;
    private final Slot[] slots = new Slot[SLOTS];
    private final ArrayDeque<Map.Entry<Long, Runnable>> retired = new ArrayDeque<>();
    private long pool, timeline, submitted, completed, bytes, pendingBytes, backpressure, uploads, batches;
    private int cursor;
    AsyncUploads(VulkanContext context, boolean timelineSupported) {
        this.context = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "upload ring pool"); pool = handle.get(0);
            var pointers = stack.mallocPointer(SLOTS);
            check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOTS), pointers), "upload ring commands");
            if (timelineSupported) {
                check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default()
                        .pNext(VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).address()), null, handle), "upload timeline"); timeline = handle.get(0);
            }
            for (int i = 0; i < SLOTS; i++) {
                Slot slot = slots[i] = new Slot(); slot.command = new VkCommandBuffer(pointers.get(i), context.device);
                if (timeline == 0) { check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "upload fallback fence"); slot.fence = handle.get(0); }
            }
        } catch (Throwable error) { close(); throw error; }
    }

    /** Nonblocking admission for interactive updates; callers keep their dirty state and retry on the next frame. */
    boolean available(int count, long bytes) {
        poll();
        if (pendingBytes != 0 && pendingBytes + bytes > BYTE_BUDGET) return false;
        Slot slot = slots[cursor];
        // Room in the open batch, or a free slot to open the next one.
        return slot.open ? slot.used + bytes <= BATCH_BYTES || slots[(cursor + 1) % SLOTS].ticket <= completed : slot.ticket <= completed;
    }

    long submit(byte[] data, Recorder recorder) {
        return submit(data, 0, data.length, recorder);
    }

    /** Upload a slice without allocating a second heap array for large atlas rows. */
    long submit(byte[] data, int sourceOffset, int length, Recorder recorder) {
        Objects.checkFromIndexSize(sourceOffset, length, data.length);
        poll();
        Slot slot = slots[cursor];
        if (slot.open && slot.uploads > 0 && slot.used + length > Math.max(BATCH_BYTES, slot.staging.mapped.capacity())) { flush(); slot = slots[cursor]; }
        if (!slot.open) {
            if (slot.ticket > completed) { backpressure++; waitFor(slot.ticket); }
            if (pendingBytes != 0 && pendingBytes + length > BYTE_BUDGET) { backpressure++; drain(); }
            open(slot, length);
        } else if (slot.used + length > slot.staging.mapped.capacity()) grow(slot, length);
        int offset = slot.used;
        slot.staging.mapped.position(offset); slot.staging.mapped.put(data, sourceOffset, length).clear();
        try (MemoryStack stack = MemoryStack.stackPush()) { recorder.record(slot.command, slot.staging, offset, stack); }
        slot.used = align(offset + length); slot.uploads++; slot.pending += length; pendingBytes += length; bytes += length; uploads++;
        return slot.ticket;
    }

    private void open(Slot slot, int size) {
        if (slot.staging == null || slot.staging.mapped.capacity() < size) {
            if (slot.staging != null) slot.staging.close();
            slot.staging = new GpuBuffer(context, capacity(size), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(slot.command, 0), "reset async upload");
            check(vkBeginCommandBuffer(slot.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin async upload");
        }
        slot.open = true; slot.used = slot.uploads = 0; slot.pending = 0; slot.ticket = submitted + 1;
    }
    /** Earlier uploads in this batch already reference the staging buffer, so it is retired with the batch rather than replaced. */
    private void grow(Slot slot, int size) {
        GpuBuffer old = slot.staging, next = new GpuBuffer(context, capacity(size), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        // Recorded copies read the old buffer; keep it alive until this batch completes. Only the new tail goes to the new buffer.
        retired.add(Map.entry(slot.ticket, old::close));
        slot.staging = next; slot.used = 0;
    }
    private static int capacity(int size) { return Math.max(1 << 20, Integer.highestOneBit(Math.max(1, size - 1)) << 1); }
    private static int align(int value) { return (value + ALIGNMENT - 1) & -ALIGNMENT; }

    /** Submits the open batch, if any. Must precede any queue submission that reads data uploaded since the last flush. */
    void flush() {
        Slot slot = slots[cursor];
        if (!slot.open) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkEndCommandBuffer(slot.command), "end async upload");
            var submit = VkSubmitInfo2.calloc(1, stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(slot.command));
            if (timeline != 0) submit.pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(timeline).value(slot.ticket).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));
            else check(vkResetFences(context.device, slot.fence), "reset fallback upload fence");
            check(vkQueueSubmit2(context.queue, submit, slot.fence), "submit async upload batch");
        }
        slot.open = false; submitted = slot.ticket; batches++;
        cursor = (cursor + 1) % SLOTS;
    }

    void retire(long ticket, Runnable action) {
        if (ticket <= completed) action.run(); else retired.add(Map.entry(ticket, action));
    }
    void poll() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (timeline != 0) { var value = stack.mallocLong(1); check(vkGetSemaphoreCounterValue(context.device, timeline, value), "poll upload timeline"); completed = value.get(0); }
            else for (Slot slot : slots) if (slot != null && !slot.open && slot.ticket > completed && vkGetFenceStatus(context.device, slot.fence) == VK_SUCCESS) completed = Math.max(completed, slot.ticket);
        }
        for (Slot slot : slots) if (slot != null && !slot.open && slot.ticket != 0 && slot.ticket <= completed && slot.pending != 0) { pendingBytes -= slot.pending; slot.pending = 0; }
        for (var iterator = retired.iterator(); iterator.hasNext();) {
            var item = iterator.next();
            if (item.getKey() <= completed) { iterator.remove(); item.getValue().run(); }
        }
    }
    private void waitFor(long ticket) {
        if (ticket > submitted) flush(); // Waiting on the open batch submits it first.
        if (ticket <= completed) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (timeline != 0) check(vkWaitSemaphores(context.device, VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1)
                    .pSemaphores(stack.longs(timeline)).pValues(stack.longs(ticket)), 5_000_000_000L), "wait upload backpressure");
            else {
                Slot target = Arrays.stream(slots).filter(s -> s != null && s.ticket == ticket).findFirst().orElseThrow();
                check(vkWaitForFences(context.device, target.fence, true, 5_000_000_000L), "wait fallback upload");
            }
        }
        poll();
    }
    void drain() { flush(); waitFor(submitted); }
    /** {submittedBatches, completedBatches, bytes, backpressureWaits, timeline, uploads}. */
    long[] stats() { return new long[]{submitted, completed, bytes, backpressure, timeline == 0 ? 0 : 1, uploads}; }
    @Override public void close() {
        if (pool != 0) drain();
        for (Slot slot : slots) if (slot != null) {
            if (slot.staging != null) slot.staging.close();
            if (slot.fence != 0) vkDestroyFence(context.device, slot.fence, null);
        }
        for (var item : retired) item.getValue().run(); retired.clear();
        if (timeline != 0) { vkDestroySemaphore(context.device, timeline, null); timeline = 0; }
        if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
    }
}
