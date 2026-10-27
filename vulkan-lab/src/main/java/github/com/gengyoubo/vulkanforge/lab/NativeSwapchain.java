package github.com.gengyoubo.vulkanforge.lab;

import java.util.function.Consumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;

/** Vulkan-private terrain -> sampled fullscreen pass -> WSI. Never exports images or calls GL. */
final class NativeSwapchain implements AutoCloseable {
    private static final class Slot {
        VkCommandBuffer command;
        long acquired, fence;
        boolean pending;
    }
    private final VulkanContext context;
    private final Consumer<String> log;
    private final boolean bottomOrigin;
    private final Slot[] slots = {new Slot(), new Slot()};
    private long pool, swapchain;
    private long[] images = {}, views = {}, finished = {};
    private long[] presentFences = {};
    private boolean[] presentPending = {};
    private PresentPass pass;
    private int width, height, format, requestedWidth, requestedHeight, image = -1, currentSlot;
    private int generations, presentMode;
    private boolean dirty = true, vsync = true, transferSource, closed;
    private long frames, cancellations, skippedExtents;
    private String capture;

    NativeSwapchain(VulkanContext context, Consumer<String> log) {
        this(context,log,false);
    }
    NativeSwapchain(VulkanContext context, Consumer<String> log, boolean bottomOrigin) {
        if (context.interopEnabled() || context.surface == 0 || !context.nativePresentFences)
            throw new IllegalArgumentException("Native presentation needs a private device with a surface and presentation fences");
        this.context = context; this.log = log; this.bottomOrigin = bottomOrigin;
        try (var stack = MemoryStack.stackPush()) {
            var h = stack.mallocLong(1);
            check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, h), "native present command pool");
            pool = h.get(0);
            var commands = stack.mallocPointer(slots.length);
            check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(slots.length), commands), "native present commands");
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i]; slot.command = new VkCommandBuffer(commands.get(i), context.device);
                check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, h), "native image acquired semaphore");
                slot.acquired = h.get(0);
                check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, h), "native present fence");
                slot.fence = h.get(0);
            }
        } catch (RuntimeException | Error error) { close(); throw error; }
    }

    boolean begin(int slotIndex, int w, int h) {
        requireOpen();
        if (slotIndex < 0 || slotIndex >= slots.length) throw new IllegalArgumentException("Invalid native frame slot");
        if (w <= 0 || h <= 0) {
            cancel(); dirty = true; skippedExtents++; return false;
        }
        if (image >= 0) {
            // SUBOPTIMAL and a VSync change invalidate the next acquire, not this already acquired image.
            if (currentSlot == slotIndex && requestedWidth == w && requestedHeight == h) return true;
            cancel();
        }
        if (requestedWidth != w || requestedHeight != h) dirty = true;
        if (dirty && !recreate(w, h)) { skippedExtents++; return false; }
        currentSlot = slotIndex;
        waitSlot(slotIndex);
        try (var stack = MemoryStack.stackPush()) {
            var index = stack.ints(0);
            int result = vkAcquireNextImageKHR(context.device, swapchain, 1_000_000_000L, slots[slotIndex].acquired, 0, index);
            if (result == VK_ERROR_OUT_OF_DATE_KHR) { dirty = true; return false; }
            if (result == VK_TIMEOUT || result == VK_NOT_READY) return false;
            if (result != VK_SUBOPTIMAL_KHR) check(result, "native acquire image");
            image = index.get(0);
            waitPresentImage(image);
            if (result == VK_SUBOPTIMAL_KHR) dirty = true;
            return true;
        }
    }

    private boolean recreate(int w, int h) {
        check(vkDeviceWaitIdle(context.device), "native swapchain resize wait");
        waitPresentations();
        try (var stack = MemoryStack.stackPush()) {
            var caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physical, context.surface, caps), "native surface capabilities");
            int nextWidth = caps.currentExtent().width() == -1 ? clamp(w, caps.minImageExtent().width(), caps.maxImageExtent().width()) : caps.currentExtent().width();
            int nextHeight = caps.currentExtent().height() == -1 ? clamp(h, caps.minImageExtent().height(), caps.maxImageExtent().height()) : caps.currentExtent().height();
            if (nextWidth <= 0 || nextHeight <= 0) return false;
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0)
                throw new IllegalStateException("Native swapchain does not support color attachments");
            var n = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, n, null), "native surface formats count");
            if (n.get(0) == 0) throw new IllegalStateException("Native surface has no formats");
            var formats = VkSurfaceFormatKHR.calloc(n.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, n, formats), "native surface formats");
            VkSurfaceFormatKHR chosen = null;
            int[] preferences = {VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_R8G8B8A8_SRGB};
            for (int preferred : preferences) {
                for (var candidate : formats) if ((candidate.format() == preferred || candidate.format() == VK_FORMAT_UNDEFINED)
                        && candidate.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { chosen = candidate; break; }
                if (chosen != null) break;
            }
            if (chosen == null) throw new IllegalStateException("Native surface has no supported RGBA8/BGRA8 SDR format");
            int nextFormat = chosen.format() == VK_FORMAT_UNDEFINED ? preferences[0] : chosen.format();
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physical, context.surface, n, null), "native present modes count");
            var modes = stack.mallocInt(n.get(0));
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physical, context.surface, n, modes), "native present modes");
            int mode = VK_PRESENT_MODE_FIFO_KHR;
            if (!vsync) for (int candidate : new int[]{VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR}) {
                boolean found = false;
                for (int i = 0; i < modes.remaining(); i++) if (modes.get(i) == candidate) found = true;
                if (found) { mode = candidate; break; }
            }
            int count = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) count = Math.min(count, caps.maxImageCount());
            boolean canCapture = (caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0;
            // Nothing from the old generation is referenced after the device-idle wait above.
            destroySwapchain();
            width = nextWidth; height = nextHeight; format = nextFormat; presentMode = mode; transferSource = canCapture;
            var handle = stack.mallocLong(1);
            var info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(context.surface).minImageCount(count)
                    .imageFormat(format).imageColorSpace(chosen.colorSpace()).imageExtent(e -> e.set(width, height))
                    .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | (canCapture ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0))
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                    .compositeAlpha(Integer.lowestOneBit(caps.supportedCompositeAlpha())).presentMode(mode).clipped(true);
            check(vkCreateSwapchainKHR(context.device, info, null, handle), "native create swapchain"); swapchain = handle.get(0);
            check(vkGetSwapchainImagesKHR(context.device, swapchain, n, null), "native swapchain image count");
            var handles = stack.mallocLong(n.get(0));
            check(vkGetSwapchainImagesKHR(context.device, swapchain, n, handles), "native swapchain images");
            images = new long[n.get(0)]; views = new long[images.length]; finished = new long[images.length];
            presentFences = new long[images.length]; presentPending = new boolean[images.length]; handles.get(images);
            for (int i = 0; i < images.length; i++) {
                check(vkCreateImageView(context.device, VkImageViewCreateInfo.calloc(stack).sType$Default().image(images[i])
                        .format(format).viewType(VK_IMAGE_VIEW_TYPE_2D).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)), null, handle), "native swapchain view");
                views[i] = handle.get(0);
                // A frame fence does not cover presentation. Reuse this semaphore only after reacquiring its image.
                check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "native render finished semaphore");
                finished[i] = handle.get(0);
                check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "native presentation fence");
                presentFences[i] = handle.get(0);
            }
            boolean srgb = format == VK_FORMAT_B8G8R8A8_SRGB || format == VK_FORMAT_R8G8B8A8_SRGB;
            pass = new PresentPass(context, format, bottomOrigin ? "/shaders/composite.vert" : "/shaders/native_present.vert", srgb ? "/shaders/native_present_srgb.frag" : "/shaders/present.frag");
            requestedWidth = w; requestedHeight = h; generations++; dirty = false;
            log.accept("NATIVE_SWAPCHAIN[" + generations + "]=" + width + "x" + height + " images=" + images.length + " format=" + format + " presentMode=" + presentMode);
            return true;
        }
    }

    /** Both submissions use the same queue; the image barrier orders terrain writes before sampling. */
    void present(VulkanRenderImage source) {
        requireOpen();
        if (image < 0) throw new IllegalStateException("Native presentation has no acquired image");
        submit(source, false);
    }

    /** Consume an acquired semaphore and return its image even if no terrain frame was produced. */
    void cancel() {
        if (image >= 0) submit(null, true);
    }

    private void submit(VulkanRenderImage source, boolean cancelled) {
        Slot slot = slots[currentSlot];
        CaptureBuffer proof = null;
        boolean submitted = false;
        try (var stack = MemoryStack.stackPush()) {
            if (!cancelled && capture != null) {
                if (!transferSource) throw new UnsupportedOperationException("Native surface cannot capture swapchain images");
                proof = new CaptureBuffer(context, Math.multiplyExact(Math.multiplyExact(width, height), 4));
            }
            check(vkResetCommandBuffer(slot.command, 0), "reset native present command");
            check(vkBeginCommandBuffer(slot.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin native present command");
            transition(slot.command, stack, images[image], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    // Chain the layout transition after the acquire semaphore's COLOR_ATTACHMENT_OUTPUT wait.
                    // NONE here would let the transition execute before the presentation engine releases the image.
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, 0, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            if (source != null) {
                transition(slot.command, stack, source.image(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                pass.record(slot.command, stack, currentSlot, source.view(), views[image], width, height);
                transition(slot.command, stack, source.image(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            } else {
                var color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(views[image])
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                color.clearValue().color().float32(3, 1);
                vkCmdBeginRendering(slot.command, VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).renderArea(r -> r.extent(e -> e.set(width, height))));
                vkCmdEndRendering(slot.command);
            }
            if (proof != null) proof.record(slot.command, images[image], width, height, stack, true);
            transition(slot.command, stack, images[image], proof != null ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    proof != null ? VK_PIPELINE_STAGE_2_COPY_BIT : VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    proof != null ? VK_ACCESS_2_TRANSFER_READ_BIT : VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_NONE, 0);
            check(vkEndCommandBuffer(slot.command), "end native present command");
            check(vkResetFences(context.device, slot.fence), "reset native present fence");
            var info = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pWaitSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(slot.acquired).stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(slot.command))
                    .pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(finished[image]).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));
            check(vkQueueSubmit2(context.queue, info, slot.fence), "native present submit"); slot.pending = submitted = true;
            check(vkResetFences(context.device, presentFences[image]), "reset native presentation fence");
            int result = vkQueuePresentKHR(context.queue, VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pNext(VkSwapchainPresentFenceInfoEXT.calloc(stack).sType$Default().pFences(stack.longs(presentFences[image])))
                    .pWaitSemaphores(stack.longs(finished[image])).swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(image)));
            // OUT_OF_DATE/SURFACE_LOST still enqueue the semaphore wait and present fence.
            if (result != VK_ERROR_OUT_OF_HOST_MEMORY && result != VK_ERROR_OUT_OF_DEVICE_MEMORY && result != VK_ERROR_DEVICE_LOST)
                presentPending[image] = true;
            image = -1;
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) dirty = true;
            else check(result, "native queue present");
            if (cancelled) cancellations++;
            else if (result != VK_ERROR_OUT_OF_DATE_KHR) frames++;
            if (proof != null) {
                waitSlot(currentSlot);
                try { proof.save(capture, width, height, format); }
                catch (java.io.IOException error) { throw new IllegalStateException("Native swapchain capture failed", error); }
                capture = null;
            }
        } finally {
            if (proof != null) {
                if (submitted && slot.pending) waitSlot(currentSlot);
                proof.close();
            }
        }
    }

    void waitSlot(int index) {
        Slot slot = slots[index];
        if (slot.pending) { check(vkWaitForFences(context.device, slot.fence, true, 5_000_000_000L), "wait native present slot"); slot.pending = false; }
    }
    private void waitPresentImage(int index) {
        if (presentPending[index]) {
            check(vkWaitForFences(context.device, presentFences[index], true, 5_000_000_000L), "wait native presentation resources");
            presentPending[index] = false;
        }
    }
    private void waitPresentations() { for (int i = 0; i < presentPending.length; i++) waitPresentImage(i); }
    void setVsync(boolean value) { requireOpen(); if (vsync != value) { vsync = value; dirty = true; } }
    void captureNext(String path) { requireOpen(); capture = java.util.Objects.requireNonNull(path); }
    int width() { return width; }
    int height() { return height; }
    long frames() { return frames; }
    int generations() { return generations; }
    long cancellations() { return cancellations; }
    long skippedExtents() { return skippedExtents; }
    private void requireOpen() { if (closed) throw new IllegalStateException("Native swapchain is closed"); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static void transition(VkCommandBuffer command, MemoryStack stack, long image, int from, int to, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().image(image).oldLayout(from).newLayout(to)
                .srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
        vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
    }
    private void destroySwapchain() {
        if (pass != null) { pass.close(); pass = null; }
        for (long view : views) if (view != 0) vkDestroyImageView(context.device, view, null);
        for (long semaphore : finished) if (semaphore != 0) vkDestroySemaphore(context.device, semaphore, null);
        for (long fence : presentFences) if (fence != 0) vkDestroyFence(context.device, fence, null);
        images = views = finished = new long[0];
        presentFences = new long[0]; presentPending = new boolean[0];
        if (swapchain != 0) { vkDestroySwapchainKHR(context.device, swapchain, null); swapchain = 0; }
    }
    @Override public void close() {
        if (closed) return;
        try {
            cancel();
            check(vkDeviceWaitIdle(context.device), "native presentation shutdown");
            waitPresentations();
        } finally {
            closed = true;
            destroySwapchain();
            for (Slot slot : slots) {
                if (slot.fence != 0) vkDestroyFence(context.device, slot.fence, null);
                if (slot.acquired != 0) vkDestroySemaphore(context.device, slot.acquired, null);
            }
            if (pool != 0) vkDestroyCommandPool(context.device, pool, null);
        }
    }
}
