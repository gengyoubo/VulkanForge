package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.function.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;

/** Reflective bootstrap API. No Minecraft or parent-LWJGL classes cross this class-loader boundary. */
public final class MainWindowBackend implements AutoCloseable {
    private final VulkanContext context = new VulkanContext();
    private long swapchain, pool, fence, acquired;
    private VkCommandBuffer command;
    private long[] images = new long[0], finished = new long[0];
    private SharedPresentImage shared;
    private int width, height, format, generations;
    private long frames;
    private boolean dirty = true, vsync = true;
    private String capture;
    private PrintWriter report;
    private final Function<long[], Consumer<int[]>> importer;
    private final Runnable finishGL;

    public MainWindowBackend(String logPath, byte[] uuid, Function<long[], Consumer<int[]>> importer, Runnable finishGL) throws Exception {
        this.importer = importer; this.finishGL = finishGL;
        Configuration.STACK_SIZE.set(1024);
        report = new PrintWriter(Files.newBufferedWriter(Path.of(logPath)));
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                context.initializeInterop(uuid, Boolean.getBoolean("vulkanforge.requireValidation"));
                log("VALIDATION=" + (context.validationEnabled() ? "ENABLED" : "UNAVAILABLE"));
                verifyInterop(stack);
                var handle = stack.mallocLong(1);
                check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily)
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "main-window command pool"); pool = handle.get(0);
                var pointer = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "main-window command buffer");
                command = new VkCommandBuffer(pointer.get(0), context.device);
                check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "main-window frame fence"); fence = handle.get(0);
                check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "main-window acquire semaphore"); acquired = handle.get(0);
            }
            // Check actual handle import and FBO creation before the caller replaces the visible window.
            try (var probe = new SharedPresentImage(context, 16, 16, importer)) { log("SHARED_IMAGE_PROBE=OK"); }
            log("BACKEND=WIN32_VULKAN_SWAPCHAIN_GPU_INTEROP");
            log("GL_BINDINGS=HOST_CALLBACKS");
        } catch (Throwable error) { close(); throw error; }
    }

    private void verifyInterop(MemoryStack stack) {
        var external = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack).sType$Default().handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        var info = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default().pNext(external.address()).format(VK_FORMAT_R8G8B8A8_UNORM)
                .type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        var properties = VkExternalImageFormatProperties.calloc(stack).sType$Default();
        var formats = VkImageFormatProperties2.calloc(stack).sType$Default().pNext(properties.address());
        check(vkGetPhysicalDeviceImageFormatProperties2(context.physical, info, formats), "query exportable presentation format");
        if ((properties.externalMemoryProperties().externalMemoryFeatures() & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) == 0) throw new IllegalStateException("RGBA8 image export unavailable");
        var semaphores = VkExternalSemaphoreProperties.calloc(stack).sType$Default();
        vkGetPhysicalDeviceExternalSemaphoreProperties(context.physical, VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack).sType$Default()
                .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT), semaphores);
        if ((semaphores.externalSemaphoreFeatures() & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) == 0) throw new IllegalStateException("Semaphore export unavailable");
        var features = VkFormatProperties.calloc(stack); vkGetPhysicalDeviceFormatProperties(context.physical, VK_FORMAT_R8G8B8A8_UNORM, features);
        if ((features.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0) throw new IllegalStateException("Shared image cannot be a blit source");
    }

    public void attach(long hwnd, long hinstance) { context.attachWin32(hwnd, hinstance); log("MAIN_HWND=" + Long.toUnsignedString(hwnd)); log("SURFACE_CREATED=true"); }
    public long frames() { return frames; }
    public int generations() { return generations; }
    public void captureNext(String path) { capture = path; }
    public void setVsync(boolean enabled) { if (vsync != enabled) { vsync = enabled; dirty = true; } }

    public void present(int sourceFramebuffer, int sourceWidth, int sourceHeight, int framebufferWidth, int framebufferHeight) throws IOException {
        if (framebufferWidth <= 0 || framebufferHeight <= 0) return;
        if (dirty || framebufferWidth != width || framebufferHeight != height) recreate(framebufferWidth, framebufferHeight);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "wait presentation frame");
            var index = stack.ints(0);
            int result = vkAcquireNextImageKHR(context.device, swapchain, 5_000_000_000L, acquired, 0, index);
            if (result == VK_ERROR_OUT_OF_DATE_KHR) { dirty = true; return; }
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) check(result, "acquire main-window swapchain");
            int acquiredResult = result, image = index.get(0);
            shared.copyFromGL(sourceFramebuffer, sourceWidth, sourceHeight);
            check(vkResetCommandBuffer(command, 0), "reset presentation command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin presentation command");
            var barriers = VkImageMemoryBarrier2.calloc(2, stack);
            barriers.get(0).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_NONE).dstStageMask(VK_PIPELINE_STAGE_2_BLIT_BIT)
                    .dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT).oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL).dstQueueFamilyIndex(context.queueFamily).image(shared.image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            barriers.get(1).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_NONE).dstStageMask(VK_PIPELINE_STAGE_2_BLIT_BIT)
                    .dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(images[image])
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            var blit = VkImageBlit.calloc(1, stack).srcSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                    .dstSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1));
            blit.srcOffsets(0).set(0, height, 0); blit.srcOffsets(1).set(width, 0, 1);
            blit.dstOffsets(0).set(0, 0, 0); blit.dstOffsets(1).set(width, height, 1);
            vkCmdBlitImage(command, shared.image, VK_IMAGE_LAYOUT_GENERAL, images[image], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
            // Optional diagnostic readback, never used by the normal presentation path.
            CaptureBuffer proof = capture == null ? null : new CaptureBuffer(context, width * height * 4);
            if (proof != null) proof.record(command, images[image], width, height, stack);
            barriers.get(0).srcStageMask(VK_PIPELINE_STAGE_2_BLIT_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_NONE).dstAccessMask(0).srcQueueFamilyIndex(context.queueFamily).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL);
            barriers.get(1).srcStageMask(proof == null ? VK_PIPELINE_STAGE_2_BLIT_BIT : VK_PIPELINE_STAGE_2_COPY_BIT)
                    .srcAccessMask(proof == null ? VK_ACCESS_2_TRANSFER_WRITE_BIT : VK_ACCESS_2_TRANSFER_READ_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_NONE).dstAccessMask(0).oldLayout(proof == null ? VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            check(vkEndCommandBuffer(command), "end presentation command");
            check(vkResetFences(context.device, fence), "reset presentation fence");
            var submit = VkSubmitInfo.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(acquired, shared.complete))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pCommandBuffers(stack.pointers(command.address())).pSignalSemaphores(stack.longs(finished[image], shared.ready));
            check(vkQueueSubmit(context.queue, submit, fence), "submit main-window presentation");
            result = vkQueuePresentKHR(context.queue, VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(finished[image]))
                    .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(index));
            if (result != VK_SUCCESS && result != VK_ERROR_OUT_OF_DATE_KHR && result != VK_SUBOPTIMAL_KHR) check(result, "vkQueuePresentKHR(main window)");
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR || acquiredResult == VK_SUBOPTIMAL_KHR) dirty = true;
            frames++;
            if (proof != null) {
                try { check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "wait swapchain capture"); proof.save(capture, width, height, format); }
                finally { proof.close(); capture = null; }
            }
            if (frames == 1 || frames % 300 == 0) log("PRESENTED_FRAMES=" + frames);
        }
    }

    private void recreate(int requestedWidth, int requestedHeight) {
        finishGL.run(); check(vkDeviceWaitIdle(context.device), "wait resize"); destroySwapchain();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physical, context.surface, caps), "main-window surface capabilities");
            int required = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            if ((caps.supportedUsageFlags() & required) != required) throw new IllegalStateException("Swapchain transfer usage unavailable");
            width = caps.currentExtent().width() == -1 ? Math.max(caps.minImageExtent().width(), Math.min(requestedWidth, caps.maxImageExtent().width())) : caps.currentExtent().width();
            height = caps.currentExtent().height() == -1 ? Math.max(caps.minImageExtent().height(), Math.min(requestedHeight, caps.maxImageExtent().height())) : caps.currentExtent().height();
            var count = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, null), "surface formats");
            var formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, formats), "surface formats");
            format = 0; int colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
            for (var candidate : formats) if ((candidate.format() == VK_FORMAT_B8G8R8A8_UNORM || candidate.format() == VK_FORMAT_R8G8B8A8_UNORM) && candidate.colorSpace() == colorSpace) { format = candidate.format(); break; }
            if (format == 0) throw new IllegalStateException("UNORM swapchain format required to preserve Minecraft colors");
            var features = VkFormatProperties.calloc(stack); vkGetPhysicalDeviceFormatProperties(context.physical, format, features);
            if ((features.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) throw new IllegalStateException("Swapchain format cannot be a blit destination");
            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) imageCount = Math.min(imageCount, caps.maxImageCount());
            int mode = VK_PRESENT_MODE_FIFO_KHR;
            if (!vsync) {
                check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physical, context.surface, count, null), "present modes");
                var modes = stack.mallocInt(count.get(0));
                check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physical, context.surface, count, modes), "present modes");
                for (int i = 0; i < modes.remaining(); i++) { int candidate = modes.get(i); if (candidate == VK_PRESENT_MODE_MAILBOX_KHR) { mode = candidate; break; } if (candidate == VK_PRESENT_MODE_IMMEDIATE_KHR) mode = candidate; }
            }
            var info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(context.surface).minImageCount(imageCount).imageFormat(format).imageColorSpace(colorSpace)
                    .imageExtent(e -> e.set(width, height)).imageArrayLayers(1).imageUsage(required).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform()).compositeAlpha(Integer.lowestOneBit(caps.supportedCompositeAlpha())).presentMode(mode).clipped(true);
            var handle = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(context.device, info, null, handle), "create Minecraft swapchain"); swapchain = handle.get(0);
            check(vkGetSwapchainImagesKHR(context.device, swapchain, count, null), "swapchain images");
            var handles = stack.mallocLong(count.get(0)); check(vkGetSwapchainImagesKHR(context.device, swapchain, count, handles), "swapchain images");
            images = new long[count.get(0)]; finished = new long[images.length];
            for (int i = 0; i < images.length; i++) { images[i] = handles.get(i); check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "present image semaphore"); finished[i] = handle.get(0); }
            shared = new SharedPresentImage(context, width, height, importer);
            check(vkResetCommandBuffer(command, 0), "reset interop initializer");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin interop initializer");
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_NONE).dstStageMask(VK_PIPELINE_STAGE_2_NONE)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(context.queueFamily).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL)
                    .image(shared.image).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
            check(vkEndCommandBuffer(command), "end interop initializer");
            check(vkQueueSubmit(context.queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command.address())).pSignalSemaphores(stack.longs(shared.ready)), 0), "initialize GL ownership");
            check(vkQueueWaitIdle(context.queue), "wait GL ownership initializer");
            generations++; dirty = false;
            log("SWAPCHAIN[" + generations + "]=" + width + "x" + height + " format=" + format + " mode=" + mode);
        }
    }

    private void log(String message) { if (report != null) { report.println(message); report.flush(); } }
    private void destroySwapchain() {
        if (shared != null) { shared.close(); shared = null; }
        for (long semaphore : finished) if (semaphore != 0) vkDestroySemaphore(context.device, semaphore, null);
        finished = new long[0]; images = new long[0];
        if (swapchain != 0) { vkDestroySwapchainKHR(context.device, swapchain, null); swapchain = 0; }
    }
    @Override public void close() {
        if (context.device != null) {
            finishGL.run(); vkDeviceWaitIdle(context.device); destroySwapchain();
            if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
            if (fence != 0) { vkDestroyFence(context.device, fence, null); fence = 0; }
            if (acquired != 0) { vkDestroySemaphore(context.device, acquired, null); acquired = 0; }
        }
        context.close(); log("FINAL_PRESENTED_FRAMES=" + frames); log("VALIDATION_ERRORS=" + context.validationErrors());
        if (report != null) { report.close(); report = null; }
    }
}
