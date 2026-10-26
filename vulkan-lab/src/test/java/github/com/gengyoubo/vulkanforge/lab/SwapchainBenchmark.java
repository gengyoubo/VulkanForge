package github.com.gengyoubo.vulkanforge.lab;

import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;

import java.util.Arrays;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * PresentBenchmark mode B (-Dbench.mode=swapchain): bare Vulkan swapchain on a GLFW_NO_API window, no GL context, no shared
 * images and no world. Each frame: wait slot fence, acquire, vkCmdClearColorImage, submit, present. Measures whether the
 * ~4 ms per-frame presentation cost exists without anything VulkanForge adds on top.
 * Per frame: fence wait, acquire, record+submit and vkQueuePresentKHR times (mean/p50/p99/max); present-to-present interval;
 * per image, the time from its present returning to the acquire that hands it back (image availability).
 * -Dbench.timerResolutionUs=N (see TimerResolution), -Dbench.frames, -Dbench.width/height, -Dbench.fullscreen, -Dbench.cpuMs (spin before acquire), -Dbench.inFlight=N (2),
 * -Dvulkanforge.presentMode=immediate|mailbox|fifo (immediate), -Dvulkanforge.swapchainImages=N (min+1).
 * FPS counts completed frames only: the queue is idle at both ends of the timed window.
 * -Dbench.timeline=file.csv also writes each timed frame's fence-wait/acquire/submit/present begin and end as Windows system time
 * (GetSystemTimePreciseAsFileTime, 100 ns, the clock tracerpt reports), plus the render thread id, for
 * trace/analyze-present-trace.py --timeline to place on the ETW GPU timeline.
 */
final class SwapchainBenchmark {
    private static final int WARMUP = 120;

    static void run() {
        int frames = Integer.getInteger("bench.frames", 3000), width = Integer.getInteger("bench.width", 1600), height = Integer.getInteger("bench.height", 900);
        int inFlight = Integer.getInteger("bench.inFlight", 2);
        boolean fullscreen = Boolean.getBoolean("bench.fullscreen");
        double cpuMs = Double.parseDouble(System.getProperty("bench.cpuMs", "0"));
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
        TimerResolution.configure();
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        glfwDefaultWindowHints(); glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); glfwWindowHint(GLFW_FLOATING, GLFW_TRUE); glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        long monitor = glfwGetPrimaryMonitor(); var video = glfwGetVideoMode(monitor);
        long window = fullscreen ? glfwCreateWindow(video.width(), video.height(), "VulkanForge swapchain benchmark", monitor, 0)
                : glfwCreateWindow(width, height, "VulkanForge swapchain benchmark", 0, 0);
        glfwShowWindow(window); glfwFocusWindow(window);
        System.out.println("SWAPCHAIN_BENCH_MONITOR_HZ=" + video.refreshRate());
        VulkanContext context = new VulkanContext();
        context.initialize(window, VK.getInstanceVersionSupported(), false);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physical, context.surface, caps), "surface capabilities");
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) throw new IllegalStateException("Swapchain images cannot be cleared by transfer");
            var count = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, null), "surface formats");
            var formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, formats), "surface formats");
            var chosen = formats.get(0);
            for (var candidate : formats) if (candidate.format() == VK_FORMAT_B8G8R8A8_UNORM && candidate.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { chosen = candidate; break; }
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physical, context.surface, count, null), "present modes");
            var modes = stack.mallocInt(count.get(0));
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physical, context.surface, count, modes), "present modes");
            StringBuilder available = new StringBuilder();
            int wanted = switch (System.getProperty("vulkanforge.presentMode", "immediate").toLowerCase()) {
                case "mailbox" -> VK_PRESENT_MODE_MAILBOX_KHR; case "fifo" -> VK_PRESENT_MODE_FIFO_KHR; default -> VK_PRESENT_MODE_IMMEDIATE_KHR;
            }, mode = VK_PRESENT_MODE_FIFO_KHR;
            for (int i = 0; i < modes.remaining(); i++) { available.append(' ').append(modeName(modes.get(i))); if (modes.get(i) == wanted) mode = wanted; }
            int images = Math.max(caps.minImageCount(), Integer.getInteger("vulkanforge.swapchainImages", caps.minImageCount() + 1));
            if (caps.maxImageCount() > 0) images = Math.min(images, caps.maxImageCount());
            int extentWidth = caps.currentExtent().width() != -1 ? caps.currentExtent().width() : width;
            int extentHeight = caps.currentExtent().width() != -1 ? caps.currentExtent().height() : height;
            var info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(context.surface).minImageCount(images).imageFormat(chosen.format())
                    .imageColorSpace(chosen.colorSpace()).imageExtent(e -> e.set(extentWidth, extentHeight)).imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                    .compositeAlpha(Integer.lowestOneBit(caps.supportedCompositeAlpha())).presentMode(mode).clipped(true);
            var handle = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(context.device, info, null, handle), "create swapchain"); long swapchain = handle.get(0);
            check(vkGetSwapchainImagesKHR(context.device, swapchain, count, null), "swapchain images");
            var imageHandles = stack.mallocLong(count.get(0)); check(vkGetSwapchainImagesKHR(context.device, swapchain, count, imageHandles), "swapchain images");
            long[] swapImages = new long[count.get(0)], finished = new long[swapImages.length];
            for (int i = 0; i < swapImages.length; i++) {
                swapImages[i] = imageHandles.get(i);
                check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "present semaphore"); finished[i] = handle.get(0);
            }
            System.out.printf("SWAPCHAIN_BENCH_CONFIG %dx%d mode=%s images=%d (requested %s/%d) inFlight=%d available=[%s ]%n", extentWidth, extentHeight,
                    modeName(mode), swapImages.length, modeName(wanted), images, inFlight, available);

            check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "command pool"); long pool = handle.get(0);
            var pointers = stack.mallocPointer(inFlight);
            check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(inFlight), pointers), "command buffers");
            VkCommandBuffer[] commands = new VkCommandBuffer[inFlight]; long[] fences = new long[inFlight], acquired = new long[inFlight];
            for (int i = 0; i < inFlight; i++) {
                commands[i] = new VkCommandBuffer(pointers.get(i), context.device);
                check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "fence"); fences[i] = handle.get(0);
                check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "acquire semaphore"); acquired[i] = handle.get(0);
            }
            GpuTimers timers = new GpuTimers(context, inFlight, System.out::println, "CLEAR");

            int total = WARMUP + frames;
            long[] fenceWait = new long[frames], acquire = new long[frames], submit = new long[frames], present = new long[frames], interval = new long[frames];
            long[] availability = new long[frames]; int availableSamples = 0;
            long[] presentedAt = new long[swapImages.length]; int[] uses = new int[swapImages.length];
            String timelinePath = System.getProperty("bench.timeline");
            FileTimeClock clock = timelinePath == null ? null : new FileTimeClock();
            long[] timeline = clock == null ? null : new long[frames * TIMELINE_COLUMNS.length];
            long start = 0, lastPresent = 0; int outOfDate = 0, suboptimal = 0;
            var clear = VkClearColorValue.calloc(stack);
            var range = VkImageSubresourceRange.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .subresourceRange(range.get(0));
            var dependency = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier);
            var index = stack.ints(0);
            for (int frame = 0; frame < total; frame++) {
                try (MemoryStack f = stack.push()) { // Per-frame structs; popped every iteration.
                    if (frame == WARMUP) { check(vkQueueWaitIdle(context.queue), "idle before timing"); start = System.nanoTime(); }
                    int slot = frame % inFlight, at = frame - WARMUP;
                    if (glfwGetWindowAttrib(window, GLFW_ICONIFIED) != 0) { glfwRestoreWindow(window); glfwFocusWindow(window); }
                    glfwPollEvents();
                    // Stand-in for the game's CPU frame before it needs the next image.
                    for (long until = System.nanoTime() + (long) (cpuMs * 1e6); System.nanoTime() < until; ) Thread.onSpinWait();
                    long t0 = System.nanoTime();
                    if (clock != null && at >= 0) timeline[at * TIMELINE_COLUMNS.length] = clock.now();
                    check(vkWaitForFences(context.device, fences[slot], true, 5_000_000_000L), "wait slot");
                    timers.collect(slot);
                    long t1 = System.nanoTime();
                    if (clock != null && at >= 0) timeline[at * TIMELINE_COLUMNS.length + 1] = clock.now();
                    int result = vkAcquireNextImageKHR(context.device, swapchain, 5_000_000_000L, acquired[slot], 0, index);
                    long t2 = System.nanoTime();
                    if (clock != null && at >= 0) timeline[at * TIMELINE_COLUMNS.length + 2] = clock.now();
                    if (result == VK_ERROR_OUT_OF_DATE_KHR) throw new AssertionError("Swapchain out of date (window resized/minimized during the run)");
                    if (result == VK_SUBOPTIMAL_KHR) suboptimal++; else check(result, "acquire");
                    int image = index.get(0);
                    if (at >= 0 && uses[image] > 0) availability[availableSamples++] = t2 - presentedAt[image];
                    VkCommandBuffer command = commands[slot];
                    check(vkResetCommandBuffer(command, 0), "reset");
                    check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(f).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
                    timers.begin(command, slot);
                    timers.start(command, slot, 0, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                    barrier.image(swapImages[image]).srcStageMask(VK_PIPELINE_STAGE_2_CLEAR_BIT).srcAccessMask(0).dstStageMask(VK_PIPELINE_STAGE_2_CLEAR_BIT)
                            .dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                    vkCmdPipelineBarrier2(command, dependency);
                    clear.float32(0, (frame & 255) / 255f).float32(1, .3f).float32(2, .5f).float32(3, 1);
                    vkCmdClearColorImage(command, swapImages[image], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
                    barrier.srcStageMask(VK_PIPELINE_STAGE_2_CLEAR_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_NONE)
                            .dstAccessMask(0).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
                    vkCmdPipelineBarrier2(command, dependency);
                    timers.end(command, slot, 0, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                    check(vkEndCommandBuffer(command), "end");
                    var submitInfo = VkSubmitInfo2.calloc(1, f).sType$Default()
                            .pWaitSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, f).sType$Default().semaphore(acquired[slot]).stageMask(VK_PIPELINE_STAGE_2_CLEAR_BIT))
                            .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, f).sType$Default().commandBuffer(command))
                            .pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, f).sType$Default().semaphore(finished[image]).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));
                    check(vkResetFences(context.device, fences[slot]), "reset fence");
                    if (clock != null && at >= 0) timeline[at * TIMELINE_COLUMNS.length + 3] = clock.now();
                    check(vkQueueSubmit2(context.queue, submitInfo, fences[slot]), "submit");
                    if (clock != null && at >= 0) timeline[at * TIMELINE_COLUMNS.length + 4] = clock.now();
                    timers.submitted(slot);
                    long t3 = System.nanoTime();
                    result = vkQueuePresentKHR(context.queue, VkPresentInfoKHR.calloc(f).sType$Default().pWaitSemaphores(f.longs(finished[image]))
                            .swapchainCount(1).pSwapchains(f.longs(swapchain)).pImageIndices(f.ints(image)));
                    long t4 = System.nanoTime();
                    if (clock != null && at >= 0) { timeline[at * TIMELINE_COLUMNS.length + 5] = clock.now(); timeline[at * TIMELINE_COLUMNS.length + 6] = image; }
                    if (result == VK_ERROR_OUT_OF_DATE_KHR) outOfDate++; else if (result == VK_SUBOPTIMAL_KHR) suboptimal++; else check(result, "present");
                    presentedAt[image] = t4; uses[image]++;
                    if (at >= 0) { fenceWait[at] = t1 - t0; acquire[at] = t2 - t1; submit[at] = t3 - t2; present[at] = t4 - t3; if (at > 0) interval[at] = t4 - lastPresent; }
                    lastPresent = t4;
                }
            }
            check(vkQueueWaitIdle(context.queue), "idle after timing");
            double seconds = (System.nanoTime() - start) / 1e9;
            timers.collectAll();
            System.out.printf("SWAPCHAIN_BENCH %s %s images=%d inFlight=%d cpuMs=%s frames=%d fps=%.1f frameMs=%.3f%n", fullscreen ? "fullscreen" : "windowed",
                    modeName(mode), swapImages.length, inFlight, cpuMs, frames, frames / seconds, seconds * 1000 / frames);
            System.out.println("SWAPCHAIN_BENCH_MS " + stats("fenceWait", fenceWait, frames) + " " + stats("acquire", acquire, frames) + " "
                    + stats("recordSubmit", submit, frames) + " " + stats("queuePresent", present, frames));
            System.out.println("SWAPCHAIN_BENCH_PACING " + stats("presentInterval", Arrays.copyOfRange(interval, 1, frames), frames - 1) + " "
                    + stats("imagePresentToReacquire", availability, availableSamples) + " imageUses=" + Arrays.toString(uses) + " suboptimal=" + suboptimal + " outOfDate=" + outOfDate);
            timers.report(System.out::println);
            timers.close();
            if (clock != null) {
                writeTimeline(timelinePath, timeline, clock.threadId);
                clock.close();
            }
            for (int i = 0; i < inFlight; i++) { vkDestroyFence(context.device, fences[i], null); vkDestroySemaphore(context.device, acquired[i], null); }
            for (long semaphore : finished) vkDestroySemaphore(context.device, semaphore, null);
            vkDestroyCommandPool(context.device, pool, null);
            vkDestroySwapchainKHR(context.device, swapchain, null);
        } finally {
            context.close();
            glfwDestroyWindow(window); glfwTerminate();
        }
    }

    private static final String[] TIMELINE_COLUMNS = {"fenceWaitBegin", "acquireBegin", "acquireEnd", "submitBegin", "submitEnd", "presentEnd", "image"};

    /** Windows system time with 100 ns resolution from the render thread, on the same clock as tracerpt's Clock-Time. */
    private static final class FileTimeClock implements AutoCloseable {
        private final long function = org.lwjgl.system.windows.Kernel32.getLibrary().getFunctionAddress("GetSystemTimePreciseAsFileTime");
        private final long cell = org.lwjgl.system.MemoryUtil.nmemAlloc(8);
        final int threadId = org.lwjgl.system.JNI.callI(org.lwjgl.system.windows.Kernel32.getLibrary().getFunctionAddress("GetCurrentThreadId"));
        long now() { org.lwjgl.system.JNI.callPV(cell, function); return org.lwjgl.system.MemoryUtil.memGetLong(cell); }
        @Override public void close() { org.lwjgl.system.MemoryUtil.nmemFree(cell); }
    }

    private static void writeTimeline(String path, long[] timeline, int threadId) {
        StringBuilder out = new StringBuilder("# pid=").append(ProcessHandle.current().pid()).append(" renderThread=").append(threadId).append('\n')
                .append("frame,").append(String.join(",", TIMELINE_COLUMNS)).append('\n');
        for (int frame = 0; frame < timeline.length / TIMELINE_COLUMNS.length; frame++) {
            out.append(frame);
            for (int column = 0; column < TIMELINE_COLUMNS.length; column++) out.append(',').append(timeline[frame * TIMELINE_COLUMNS.length + column]);
            out.append('\n');
        }
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(path), out); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        System.out.println("SWAPCHAIN_BENCH_TIMELINE " + path + " renderThread=" + threadId);
    }

    /** name=mean/p50/p99/max in milliseconds. */
    private static String stats(String name, long[] nanos, int count) {
        if (count <= 0) return name + "=n/a";
        long[] sorted = Arrays.copyOf(nanos, count); Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
        return String.format("%s=%.3f/%.3f/%.3f/%.3f", name, mean, sorted[count / 2] / 1e6, sorted[Math.min(count - 1, (int) (count * .99))] / 1e6, sorted[count - 1] / 1e6);
    }
    private static String modeName(int mode) {
        return switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE"; case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case VK_PRESENT_MODE_FIFO_KHR -> "FIFO"; case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED"; default -> "MODE_" + mode;
        };
    }
}
