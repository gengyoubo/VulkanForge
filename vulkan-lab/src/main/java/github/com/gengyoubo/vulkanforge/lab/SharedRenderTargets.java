package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import java.util.function.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;
import static org.lwjgl.vulkan.VK13.*;

/** Long-lived RenderTarget storage, shared in place between vanilla GL draws and native Vulkan passes. */
final class SharedRenderTargets implements AutoCloseable {
    static final class Target {
        final int framebuffer, width, height;
        final SharedPresentImage color, depth;
        boolean leased;
        Target(int framebuffer, int width, int height, SharedPresentImage color, SharedPresentImage depth) {
            this.framebuffer = framebuffer; this.width = width; this.height = height; this.color = color; this.depth = depth;
        }
        void releaseGL() {
            if (!leased) throw new IllegalStateException("RenderTarget is not owned by GL");
            SharedPresentImage.endGroupGL(color, depth); leased = false;
        }
        void acquireGL() {
            if (leased) throw new IllegalStateException("RenderTarget already owned by GL");
            SharedPresentImage.beginGroupGL(color, depth); leased = true;
        }
    }
    private final VulkanContext context;
    private final Function<long[], Consumer<int[]>> importer;
    private final Runnable finishGL;
    private final Consumer<String> log;
    private final Map<Integer, Target> targets = new HashMap<>();
    private long pool, fence, created, destroyed, directPasses, copiedPasses, postPasses;
    private VkCommandBuffer command;

    SharedRenderTargets(VulkanContext context, Function<long[], Consumer<int[]>> importer, Runnable finishGL, Consumer<String> log) {
        this.context = context; this.importer = importer; this.finishGL = finishGL; this.log = log;
        try (var stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "shared target pool"); pool = handle.get(0);
            var pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "shared target command");
            command = new VkCommandBuffer(pointer.get(0), context.device);
            check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "shared target fence"); fence = handle.get(0);
        } catch (Throwable error) { close(); throw error; }
    }

    int[] create(int framebuffer, int width, int height) {
        if (targets.containsKey(framebuffer)) throw new IllegalStateException("RenderTarget already registered: " + framebuffer);
        SharedPresentImage color = null, depth = null;
        try {
            color = new SharedPresentImage(context, width, height, importer);
            depth = new SharedPresentImage(context, width, height, importer, true);
            Target target = new Target(framebuffer, width, height, color, depth);
            try (var stack = MemoryStack.stackPush()) {
                check(vkResetCommandBuffer(command, 0), "reset target initialization");
                check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin target initialization");
                var barriers = VkImageMemoryBarrier2.calloc(2, stack);
                barrier(barriers.get(0), color, false, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0, false);
                barrier(barriers.get(1), depth, true, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_2_NONE, 0, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0, false);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
                release(barriers.get(0), color, false, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0);
                release(barriers.get(1), depth, true, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
                check(vkEndCommandBuffer(command), "end target initialization");
                check(vkResetFences(context.device, fence), "reset target initialization fence");
                check(vkQueueSubmit2(context.queue, VkSubmitInfo2.calloc(1, stack).sType$Default()
                        .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command))
                        .pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(color.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)), fence), "initialize shared target");
                target.acquireGL();
                check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "wait target initialization");
            }
            targets.put(framebuffer, target); created++;
            return new int[]{color.textureGL(), depth.textureGL()};
        } catch (Throwable error) {
            finishGL.run(); vkQueueWaitIdle(context.queue);
            if (depth != null) depth.close(); if (color != null) color.close();
            throw error;
        }
    }

    Target get(int framebuffer, int width, int height) {
        Target target = targets.get(framebuffer);
        return target != null && target.width == width && target.height == height ? target : null;
    }
    Target textureOwner(int texture) {
        for (Target target : targets.values()) if (target.color.textureGL() == texture || target.depth.textureGL() == texture) return target;
        return null;
    }
    void composed(boolean direct) { if (direct) directPasses++; else copiedPasses++; }
    void post() { postPasses++; }
    long[] stats() { return new long[]{created, destroyed, targets.size(), directPasses, copiedPasses, postPasses}; }

    static void acquire(VkImageMemoryBarrier2 barrier, SharedPresentImage image, boolean depth, int layout, long stage, long access) {
        barrier(barrier, image, depth, VK_IMAGE_LAYOUT_GENERAL, layout, VK_PIPELINE_STAGE_2_NONE, 0, stage, access, true);
    }
    static void release(VkImageMemoryBarrier2 barrier, SharedPresentImage image, boolean depth, int layout, long stage, long access) {
        barrier(barrier, image, depth, layout, VK_IMAGE_LAYOUT_GENERAL, stage, access, VK_PIPELINE_STAGE_2_NONE, 0, true);
    }
    private static void barrier(VkImageMemoryBarrier2 barrier, SharedPresentImage image, boolean depth, int oldLayout, int newLayout,
                                long srcStage, long srcAccess, long dstStage, long dstAccess, boolean external) {
        boolean acquire = oldLayout == VK_IMAGE_LAYOUT_GENERAL && dstStage != VK_PIPELINE_STAGE_2_NONE;
        barrier.sType$Default().image(image.image).oldLayout(oldLayout).newLayout(newLayout)
                .srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(external ? acquire ? VK_QUEUE_FAMILY_EXTERNAL : image.context.queueFamily : VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(external ? acquire ? image.context.queueFamily : VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED)
                .subresourceRange(r -> r.aspectMask(depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
    }
    void destroy(int framebuffer) {
        Target target = targets.remove(framebuffer);
        if (target == null) return;
        // Descriptors in any world/post slot may still refer to these views. Deletion is an infrequent lifecycle operation.
        finishGL.run(); check(vkQueueWaitIdle(context.queue), "wait shared target destruction");
        target.depth.close(); target.color.close(); destroyed++;
    }
    @Override public void close() {
        for (int framebuffer : targets.keySet().stream().mapToInt(Integer::intValue).toArray()) destroy(framebuffer);
        log.accept("SHARED_RENDER_TARGETS=" + Arrays.toString(stats()));
        if (fence != 0) { vkDestroyFence(context.device, fence, null); fence = 0; }
        if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
    }
}
