package github.com.gengyoubo.vulkanforge.lab;

import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;

import java.util.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Alternating A/B CPU command-recording benchmark. Same packets/device; pipeline compilation and GPU waits are outside samples. */
public final class BindingBenchmark {
    public static void main(String[] args) {
        Configuration.STACK_SIZE.set(1024);
        VulkanContext context = new VulkanContext(); EntityPass[] passes = new EntityPass[2];
        GpuImage color = null, depth = null; long pool = 0, fence = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            context.initialize(0, VK.getInstanceVersionSupported(), false);
            if (context.validationEnabled()) throw new IllegalStateException("Disable validation for binding benchmark");
            if (!context.pushDescriptors) throw new IllegalStateException("Push descriptors unavailable");
            color = new GpuImage(context, 64, 64, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            depth = new GpuImage(context, 64, 64, VK_FORMAT_D32_SFLOAT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
            for (int mode = 0; mode < 2; mode++) {
                passes[mode] = new EntityPass(context, 1, Runnable::run, VK_FORMAT_R8G8B8A8_UNORM, true, mode == 1);
                for (int texture = 1; texture <= 32; texture++) passes[mode].texture(texture, 1, 1, 0, new byte[]{-1,-1,-1,-1});
            }
            context.uploads.drain();
            var handle = stack.mallocLong(1); var pointer = stack.mallocPointer(1);
            check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "benchmark pool"); pool = handle.get(0);
            check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "benchmark command");
            VkCommandBuffer command = new VkCommandBuffer(pointer.get(0), context.device);
            check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "benchmark fence"); fence = handle.get(0);
            List<Object[]> packets = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                Object[] packet = GuiPassSmoke.quad(EntityPass.FORMAT_POSITION_TEX, 4, EntityPass.BLEND, i % 32 + 1, -1, 0);
                ((float[]) packet[1])[44] = i % 2 == 0 ? .7f : .8f; // Keep adjacent draws distinct, as with differently tinted GUI elements.
                packets.add(packet);
            }
            long[][] samples = new long[2][120];
            for (int frame = 0; frame < 300; frame++) {
                int mode = frame % 2;
                check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "benchmark frame wait");
                try (MemoryStack memory = MemoryStack.stackPush()) {
                    check(vkResetCommandBuffer(command, 0), "benchmark reset");
                    check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(memory).sType$Default()), "benchmark begin");
                    var barriers = VkImageMemoryBarrier2.calloc(2, memory);
                    barriers.get(0).sType$Default().dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                            .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .image(color.image).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
                    barriers.get(1).sType$Default().dstStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT).dstAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                            .newLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .image(depth.image).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1));
                    vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(memory).sType$Default().pImageMemoryBarriers(barriers));
                    var attachment = VkRenderingAttachmentInfo.calloc(1, memory).sType$Default().imageView(color.view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
                    var depthInfo = VkRenderingAttachmentInfo.calloc(memory).sType$Default().imageView(depth.view).imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE); depthInfo.clearValue().depthStencil().depth(1);
                    var rendering = VkRenderingInfo.calloc(memory).sType$Default().layerCount(1).pColorAttachments(attachment).pDepthAttachment(depthInfo);
                    rendering.renderArea().extent().set(64,64); vkCmdBeginRendering(command, rendering);
                    vkCmdSetViewport(command, 0, VkViewport.calloc(1,memory).width(64).height(64).maxDepth(1));
                    var scissor = VkRect2D.calloc(1,memory); scissor.extent().set(64,64); vkCmdSetScissor(command, 0, scissor);
                    long start = System.nanoTime(); passes[mode].record(command, memory, 0, packets, 0, 0); long elapsed = System.nanoTime() - start;
                    if (frame >= 60) samples[mode][(frame - 60) / 2] = elapsed;
                    vkCmdEndRendering(command); check(vkEndCommandBuffer(command), "benchmark end");
                    check(vkResetFences(context.device,fence), "benchmark reset fence");
                    check(vkQueueSubmit2(context.queue, VkSubmitInfo2.calloc(1,memory).sType$Default()
                            .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,memory).sType$Default().commandBuffer(command)), fence), "benchmark submit");
                }
            }
            check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "benchmark finish");
            for (int mode = 0; mode < 2; mode++) {
                Arrays.sort(samples[mode]);
                System.out.println((mode == 0 ? "CACHED" : "PUSH") + "_BINDING_CPU_MS median=" + samples[mode][60] / 1e6 + " p95=" + samples[mode][114] / 1e6
                        + " descriptorCounters=" + Arrays.toString(passes[mode].bindingStats()));
            }
        } finally {
            if (context.device != null) {
                vkDeviceWaitIdle(context.device); for (EntityPass pass : passes) if (pass != null) pass.close();
                if (color != null) color.close(); if (depth != null) depth.close();
                if (fence != 0) vkDestroyFence(context.device,fence,null); if (pool != 0) vkDestroyCommandPool(context.device,pool,null);
            }
            context.close();
        }
    }
}
