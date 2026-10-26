package github.com.gengyoubo.vulkanforge.lab;

import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** One frame in flight, dynamic rendering and synchronization2; no render pass or vertex buffer. */
final class TriangleRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final long window;
    private long swapchain, pipelineLayout, pipeline, commandPool, acquiredSemaphore, frameFence;
    private VkCommandBuffer command;
    private long[] images = new long[0], views = new long[0], finishedSemaphores = new long[0];
    private int format, width, height, generations;
    private boolean resized;

    TriangleRenderer(VulkanContext context, long window) { this.context = context; this.window = window; }

    void initialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            VkCommandPoolCreateInfo pool = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            check(vkCreateCommandPool(context.device, pool, null, handle), "vkCreateCommandPool");
            commandPool = handle.get(0);
            VkCommandBufferAllocateInfo allocation = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(context.device, allocation, pointer), "vkAllocateCommandBuffers");
            command = new VkCommandBuffer(pointer.get(0), context.device);
            VkSemaphoreCreateInfo semaphore = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            check(vkCreateSemaphore(context.device, semaphore, null, handle), "vkCreateSemaphore(acquired)");
            acquiredSemaphore = handle.get(0);
            VkFenceCreateInfo fence = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            check(vkCreateFence(context.device, fence, null, handle), "vkCreateFence");
            frameFence = handle.get(0);
            VkPipelineLayoutCreateInfo layout = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            check(vkCreatePipelineLayout(context.device, layout, null, handle), "vkCreatePipelineLayout");
            pipelineLayout = handle.get(0);
        }
        recreateSwapchain();
    }

    void requestResize() { resized = true; }
    int swapchainGenerations() { return generations; }

    private void recreateSwapchain() {
        check(vkDeviceWaitIdle(context.device), "vkDeviceWaitIdle(resize)");
        destroySwapchain();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physical, context.surface, caps), "surface capabilities");
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0) {
                throw new IllegalStateException("Surface does not support color attachments");
            }
            IntBuffer count = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, null), "surface formats(count)");
            if (count.get(0) == 0) throw new IllegalStateException("Surface has no formats");
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, formats), "surface formats");
            VkSurfaceFormatKHR chosen = formats.get(0);
            for (VkSurfaceFormatKHR candidate : formats) {
                if (candidate.format() == VK_FORMAT_B8G8R8A8_SRGB && candidate.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    chosen = candidate;
                    break;
                }
            }
            format = chosen.format() == VK_FORMAT_UNDEFINED ? VK_FORMAT_B8G8R8A8_SRGB : chosen.format();
            if (caps.currentExtent().width() != -1) {
                width = caps.currentExtent().width();
                height = caps.currentExtent().height();
            } else {
                IntBuffer w = stack.ints(0), h = stack.ints(0);
                glfwGetFramebufferSize(window, w, h);
                width = clamp(w.get(0), caps.minImageExtent().width(), caps.maxImageExtent().width());
                height = clamp(h.get(0), caps.minImageExtent().height(), caps.maxImageExtent().height());
            }
            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) imageCount = Math.min(imageCount, caps.maxImageCount());
            int alpha = Integer.lowestOneBit(caps.supportedCompositeAlpha());
            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                    .surface(context.surface).minImageCount(imageCount).imageFormat(format).imageColorSpace(chosen.colorSpace())
                    .imageExtent(e -> e.set(width, height)).imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform()).compositeAlpha(alpha)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true).oldSwapchain(NULL);
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(context.device, info, null, handle), "vkCreateSwapchainKHR");
            swapchain = handle.get(0);
            check(vkGetSwapchainImagesKHR(context.device, swapchain, count, null), "swapchain images(count)");
            LongBuffer imageHandles = stack.mallocLong(count.get(0));
            check(vkGetSwapchainImagesKHR(context.device, swapchain, count, imageHandles), "swapchain images");
            images = new long[count.get(0)];
            views = new long[images.length];
            // Presentation completion is associated with the acquired image, not the frame fence.
            finishedSemaphores = new long[images.length];
            for (int i = 0; i < images.length; i++) {
                images[i] = imageHandles.get(i);
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(images[i])
                        .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                check(vkCreateImageView(context.device, view, null, handle), "vkCreateImageView");
                views[i] = handle.get(0);
                check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateSemaphore(present)");
                finishedSemaphores[i] = handle.get(0);
            }
            createPipeline(stack);
            generations++;
            resized = false;
            System.out.printf("SWAPCHAIN[%d]=%dx%d images=%d format=%d present=FIFO%n", generations, width, height, images.length, format);
        }
    }

    private void createPipeline(MemoryStack stack) {
        pipeline = GraphicsPipeline.create(context, pipelineLayout, format, "/shaders/triangle.vert", stack);
    }

    boolean draw() {
        if (resized) recreateSwapchain();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkWaitForFences(context.device, frameFence, true, -1L), "vkWaitForFences");
            IntBuffer index = stack.ints(0);
            int acquire = vkAcquireNextImageKHR(context.device, swapchain, -1L, acquiredSemaphore, NULL, index);
            if (acquire == VK_ERROR_OUT_OF_DATE_KHR) { resized = true; return false; }
            if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) check(acquire, "vkAcquireNextImageKHR");
            int image = index.get(0);
            check(vkResetCommandBuffer(command, 0), "vkResetCommandBuffer");
            record(stack, image);
            // Only reset after successful acquisition/recording, so an out-of-date swapchain cannot strand the fence.
            check(vkResetFences(context.device, frameFence), "vkResetFences");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(acquiredSemaphore)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(command.address())).pSignalSemaphores(stack.longs(finishedSemaphores[image]));
            check(vkQueueSubmit(context.queue, submit, frameFence), "vkQueueSubmit");
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(finishedSemaphores[image])).swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(index);
            int result = vkQueuePresentKHR(context.queue, present);
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR || acquire == VK_SUBOPTIMAL_KHR) resized = true;
            else check(result, "vkQueuePresentKHR");
            return true;
        }
    }

    private void record(MemoryStack stack, int image) {
        check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
        VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK_ACCESS_2_NONE)
                .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(images[image])
                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
        VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier);
        vkCmdPipelineBarrier2(command, dependency);

        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(views[image]).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        color.clearValue().color().float32(0, 0.018f).float32(1, 0.028f).float32(2, 0.050f).float32(3, 1.0f);
        VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)))
                .layerCount(1).pColorAttachments(color);
        vkCmdBeginRendering(command, rendering);
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack).x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
        vkCmdSetViewport(command, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack).offset(o -> o.set(0, 0)).extent(e -> e.set(width, height));
        vkCmdSetScissor(command, 0, scissor);
        vkCmdDraw(command, 3, 1, 0, 0);
        vkCmdEndRendering(command);

        barrier.srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT).srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_NONE).dstAccessMask(VK_ACCESS_2_NONE)
                .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        vkCmdPipelineBarrier2(command, dependency);
        check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private void destroySwapchain() {
        if (pipeline != NULL) { vkDestroyPipeline(context.device, pipeline, null); pipeline = NULL; }
        for (long view : views) if (view != NULL) vkDestroyImageView(context.device, view, null);
        for (long semaphore : finishedSemaphores) if (semaphore != NULL) vkDestroySemaphore(context.device, semaphore, null);
        views = new long[0];
        images = new long[0];
        finishedSemaphores = new long[0];
        if (swapchain != NULL) { vkDestroySwapchainKHR(context.device, swapchain, null); swapchain = NULL; }
    }

    @Override public void close() {
        if (context.device == null) return;
        vkDeviceWaitIdle(context.device);
        destroySwapchain();
        if (pipelineLayout != NULL) { vkDestroyPipelineLayout(context.device, pipelineLayout, null); pipelineLayout = NULL; }
        if (commandPool != NULL) { vkDestroyCommandPool(context.device, commandPool, null); commandPool = NULL; }
        if (acquiredSemaphore != NULL) { vkDestroySemaphore(context.device, acquiredSemaphore, null); acquiredSemaphore = NULL; }
        if (frameFence != NULL) { vkDestroyFence(context.device, frameFence, null); frameFence = NULL; }
    }
}
