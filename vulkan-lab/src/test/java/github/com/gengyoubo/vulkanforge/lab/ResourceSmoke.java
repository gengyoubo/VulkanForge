package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.VK13.*;

/** Real device checks with a deliberately blocked queue: nonblocking admission and mapped suballocation/reuse. */
public final class ResourceSmoke {
    public static void main(String[] args) {
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
        VulkanContext context = new VulkanContext(); long gate = 0; boolean released = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            context.initialize(0, VK.getInstanceVersionSupported(), Boolean.getBoolean("vulkanforge.requireValidation"));
            if (context.capabilities.timeline()) {
                var handle = stack.mallocLong(1);
                check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default()
                        .pNext(VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).address()), null, handle), "resource test gate"); gate = handle.get(0);
                var waits = VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(gate).value(1).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                check(vkQueueSubmit2(context.queue, VkSubmitInfo2.calloc(1, stack).sType$Default().pWaitSemaphoreInfos(waits), 0), "block upload queue");
                long ticket = 0;
                for (int i = 0; i < 8; i++) ticket = context.uploads.submit(new byte[]{1}, (command, staging, memory) -> {});
                if (context.uploads.available(1, 1) || context.uploads.stats()[3] != 0 || context.uploads.stats()[1] != 0)
                    throw new AssertionError("Busy upload admission waited or reused in-flight staging");
                int[] retired = {0}; context.uploads.retire(ticket, () -> retired[0]++);
                if (retired[0] != 0) throw new AssertionError("Upload resource retired early");
                check(vkSignalSemaphore(context.device, VkSemaphoreSignalInfo.calloc(stack).sType$Default().semaphore(gate).value(1)), "release test queue"); released = true;
                context.uploads.drain();
                if (!context.uploads.available(1, 1) || retired[0] != 1) throw new AssertionError("Upload slots did not recover");
                System.out.println("UPLOAD_ADMISSION_SMOKE=OK (busy queue defers without waiting, no early staging reuse, delayed retirement)");
            }
            GpuBuffer[] buffers = new GpuBuffer[96];
            try {
                for (int i = 0; i < buffers.length; i++) { buffers[i] = new GpuBuffer(context, 64 << 10, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT); buffers[i].mapped.putInt(0, i); }
                if (buffers[0].memory != buffers[2].memory) throw new AssertionError("Small GPU buffers are not suballocated");
                for (int i = 1; i < buffers.length; i += 2) { buffers[i].close(); buffers[i] = new GpuBuffer(context, 64 << 10, VK_BUFFER_USAGE_INDEX_BUFFER_BIT); buffers[i].mapped.putInt(0, -1); }
                for (int i = 0; i < buffers.length; i += 2) if (buffers[i].mapped.getInt(0) != i) throw new AssertionError("Free range reuse overlapped a live allocation");
                if (context.memory.stats()[4] > 2) throw new AssertionError("Small buffers fragmented the device memory pool");
                System.out.println("GPU_MEMORY_SMOKE=OK allocations=" + context.memory.stats()[3] + " blocks=" + context.memory.stats()[4]);
            } finally { for (GpuBuffer buffer : buffers) if (buffer != null) buffer.close(); }
        } finally {
            if (gate != 0) try (MemoryStack stack = MemoryStack.stackPush()) {
                if (!released) vkSignalSemaphore(context.device, VkSemaphoreSignalInfo.calloc(stack).sType$Default().semaphore(gate).value(1));
                vkDeviceWaitIdle(context.device); vkDestroySemaphore(context.device, gate, null);
            }
            context.close();
        }
        if (context.validationErrors() != 0) throw new AssertionError("Resource validation errors=" + context.validationErrors());
        System.out.println("RESOURCE_SMOKE=OK VALIDATION_ERRORS=0");
    }
}
