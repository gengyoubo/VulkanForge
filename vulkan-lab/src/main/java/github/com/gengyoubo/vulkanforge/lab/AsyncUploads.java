package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;

/** Bounded staging slots on the graphics queue. Submission order and barriers publish uploads; CPU waits only under backpressure/teardown. */
final class AsyncUploads implements AutoCloseable {
    @FunctionalInterface interface Recorder { void record(VkCommandBuffer command, GpuBuffer staging, MemoryStack stack); }
    private static final int SLOTS = 8;
    private static final long BYTE_BUDGET = 32L << 20;
    private static final class Slot { VkCommandBuffer command; GpuBuffer staging; long fence, ticket; int bytes; }
    private final VulkanContext context;
    private final Slot[] slots = new Slot[SLOTS];
    private final ArrayDeque<Map.Entry<Long, Runnable>> retired = new ArrayDeque<>();
    private long pool, timeline, submitted, completed, bytes, pendingBytes, backpressure;
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

    long submit(byte[] data, Recorder recorder) {
        poll();
        Slot slot = slots[cursor];
        if (slot.ticket > completed) { backpressure++; waitFor(slot.ticket); }
        if (pendingBytes + data.length > BYTE_BUDGET) { backpressure++; drain(); }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (slot.staging == null || slot.staging.mapped.capacity() < data.length) {
                if (slot.staging != null) slot.staging.close();
                slot.staging = new GpuBuffer(context, Math.max(4096, Integer.highestOneBit(Math.max(1, data.length - 1)) << 1), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            }
            slot.staging.mapped.clear().put(data);
            check(vkResetCommandBuffer(slot.command, 0), "reset async upload");
            check(vkBeginCommandBuffer(slot.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin async upload");
            recorder.record(slot.command, slot.staging, stack);
            check(vkEndCommandBuffer(slot.command), "end async upload");
            long ticket = submitted + 1;
            var submit = VkSubmitInfo2.calloc(1, stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(slot.command));
            if (timeline != 0) submit.pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(timeline).value(ticket).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));
            else check(vkResetFences(context.device, slot.fence), "reset fallback upload fence");
            check(vkQueueSubmit2(context.queue, submit, slot.fence), "submit async upload");
            submitted = slot.ticket = ticket; slot.bytes = data.length; pendingBytes += data.length; bytes += data.length;
            cursor = (cursor + 1) % SLOTS;
            return ticket;
        }
    }

    void retire(long ticket, Runnable action) {
        if (ticket <= completed) action.run(); else retired.add(Map.entry(ticket, action));
    }
    void poll() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (timeline != 0) { var value = stack.mallocLong(1); check(vkGetSemaphoreCounterValue(context.device, timeline, value), "poll upload timeline"); completed = value.get(0); }
            else for (Slot slot : slots) if (slot != null && slot.ticket > completed && vkGetFenceStatus(context.device, slot.fence) == VK_SUCCESS) completed = Math.max(completed, slot.ticket);
        }
        for (Slot slot : slots) if (slot != null && slot.ticket != 0 && slot.ticket <= completed) { pendingBytes -= slot.bytes; slot.bytes = 0; }
        for (var iterator = retired.iterator(); iterator.hasNext();) {
            var item = iterator.next();
            if (item.getKey() <= completed) { iterator.remove(); item.getValue().run(); }
        }
    }
    private void waitFor(long ticket) {
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
    void drain() { waitFor(submitted); }
    long[] stats() { return new long[]{submitted, completed, bytes, backpressure, timeline == 0 ? 0 : 1}; }
    @Override public void close() {
        drain();
        for (Slot slot : slots) if (slot != null) {
            if (slot.staging != null) slot.staging.close();
            if (slot.fence != 0) vkDestroyFence(context.device, slot.fence, null);
        }
        if (timeline != 0) { vkDestroySemaphore(context.device, timeline, null); timeline = 0; }
        if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
    }
}
