package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.vulkan.*;

/** Dispatch promoted commands through their KHR entry points on a Vulkan 1.2 device. */
final class VulkanCommands {
    private VulkanCommands() { }
    static void vkCmdBeginRendering(VkCommandBuffer command, VkRenderingInfo info) {
        if (command.getCapabilities().Vulkan13) VK13.vkCmdBeginRendering(command, info);
        else KHRDynamicRendering.vkCmdBeginRenderingKHR(command, info);
    }
    static void vkCmdEndRendering(VkCommandBuffer command) {
        if (command.getCapabilities().Vulkan13) VK13.vkCmdEndRendering(command);
        else KHRDynamicRendering.vkCmdEndRenderingKHR(command);
    }
    static void vkCmdPipelineBarrier2(VkCommandBuffer command, VkDependencyInfo info) {
        if (command.getCapabilities().Vulkan13) VK13.vkCmdPipelineBarrier2(command, info);
        else KHRSynchronization2.vkCmdPipelineBarrier2KHR(command, info);
    }
    static void vkCmdWriteTimestamp2(VkCommandBuffer command, long stage, long pool, int query) {
        if (command.getCapabilities().Vulkan13) VK13.vkCmdWriteTimestamp2(command, stage, pool, query);
        else KHRSynchronization2.vkCmdWriteTimestamp2KHR(command, stage, pool, query);
    }
    static int vkQueueSubmit2(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence) {
        return queue.getCapabilities().Vulkan13 ? VK13.vkQueueSubmit2(queue, submits, fence)
                : KHRSynchronization2.vkQueueSubmit2KHR(queue, submits, fence);
    }
}
