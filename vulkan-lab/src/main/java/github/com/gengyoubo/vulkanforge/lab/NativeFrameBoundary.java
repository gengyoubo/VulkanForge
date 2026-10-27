package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Optional experimental GL-VK output adapter. The private executor never loads its GL implementation. */
interface NativeFrameBoundary extends AutoCloseable {
    VulkanRenderImage color(int slot, int index);
    void validatePlan(NativeFramePlan plan);
    default void beforeRecord(int slot,int result) { }
    void acquire(VkCommandBuffer command, MemoryStack stack, int slot, int result);
    void release(VkCommandBuffer command, MemoryStack stack, int slot, int result);
    default void releaseIdle(VkCommandBuffer command,MemoryStack stack,int slot) { throw new UnsupportedOperationException("No minimal handoff boundary"); }
    void beforeSubmit(int slot, int result);
    VkSemaphoreSubmitInfo.Buffer waits(MemoryStack stack, int slot, int result);
    VkSemaphoreSubmitInfo.Buffer signals(MemoryStack stack, int slot, int result);
    void submitted(int slot, int result, long fence);
    int sharedImages();
    @Override void close();
}
