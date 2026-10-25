package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import java.util.function.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;
import static org.lwjgl.vulkan.VK13.*;

/** Same-device terrain -> exported color attachments -> host GL sampling. No staging/readback or frame protocol. */
final class SharedWorldRenderer implements AutoCloseable {
    private static final int SLOT_COUNT = 2;
    private static final class Slot {
        SharedPresentImage color;
        SharedPresentImage depth;
        VkCommandBuffer command;
        long fence;
        boolean used, pending;
        long serial;
    }
    private final VulkanContext context;
    private final Function<long[], Consumer<int[]>> importer;
    private final Runnable finishGL;
    private final Consumer<String> log;
    private final Slot[] slots = {new Slot(), new Slot()};
    private final WorldScene world;
    private GpuTimers timers;
    private GpuCuller culler;
    private record Retired(long serial, Runnable close) { }
    private final java.util.ArrayDeque<Retired> retired = new java.util.ArrayDeque<>();
    private long completedFrames;
    private long pool, triangleLayout, trianglePipeline, frames, terrainFrames, sectionDraws;
    private final long started = System.nanoTime();
    private int width, height, generations, reading = -1;
    private int depthTexture;

    SharedWorldRenderer(VulkanContext context, Function<long[], Consumer<int[]>> importer, Runnable finishGL, Consumer<String> log) {
        this.context = context; this.importer = importer; this.finishGL = finishGL; this.log = log;
        world = new WorldScene(context, log);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            world.initialize();
            world.retireUsing(resource -> {
                if (completedFrames >= frames) resource.run(); else retired.addLast(new Retired(frames, resource));
            });
            timers = new GpuTimers(context, SLOT_COUNT, log, "TERRAIN", "TERRAIN_RELEASE", "TRIANGLE", "CULLING");
            var familyCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(context.physical, familyCount, null);
            var families = VkQueueFamilyProperties.calloc(familyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(context.physical, familyCount, families);
            if ((families.get(context.queueFamily).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0 && !Boolean.getBoolean("vulkanforge.disableGpuCulling"))
                culler = new GpuCuller(context, SLOT_COUNT);
            log.accept("GPU_CULLING=" + (culler == null ? "DISABLED" : "COMPUTE_INDIRECT"));
            var handle = stack.mallocLong(1);
            check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "shared world pool"); pool = handle.get(0);
            var pointers = stack.mallocPointer(SLOT_COUNT);
            check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOT_COUNT), pointers), "shared world commands");
            for (int i = 0; i < SLOT_COUNT; i++) {
                slots[i].command = new VkCommandBuffer(pointers.get(i), context.device);
                check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "shared world fence");
                slots[i].fence = handle.get(0);
            }
            var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).size(8);
            check(vkCreatePipelineLayout(context.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(push), null, handle), "shared triangle layout");
            triangleLayout = handle.get(0);
            trianglePipeline = GraphicsPipeline.create(context, triangleLayout, VK_FORMAT_R8G8B8A8_UNORM, "/shaders/offscreen.vert", stack);
            log.accept("WORLD_BACKEND=MAIN_WINDOW_SHARED_GPU");
            log.accept("WORLD_PROCESS_ID=" + ProcessHandle.current().pid());
        } catch (Throwable error) { close(); throw error; }
    }

    void apply(WorldProtocol.Command command) {
        // Camera pushes are copied while recording; they do not mutate resources used by older submissions.
        if (!(command instanceof WorldProtocol.Camera || command instanceof WorldProtocol.Mesh || command instanceof WorldProtocol.Remove)) { endRead(); waitAll(); }
        world.apply(command);
    }
    void prepareFrame() { waitSlot((int)(frames % SLOT_COUNT)); }

    int render(int requestedWidth, int requestedHeight) {
        endRead();
        boolean terrain = world.ready();
        if (requestedWidth <= 0 || requestedHeight <= 0 || (!terrain && world.epoch() != 0)) return 0;
        if (width != requestedWidth || height != requestedHeight) resize(requestedWidth, requestedHeight);
        int index = (int)(frames % SLOT_COUNT);
        Slot slot = slots[index]; waitSlot(index);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer command = slot.command;
            check(vkResetCommandBuffer(command, 0), "reset shared world command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin shared world command");
            timers.begin(command, index);
            var barriers = VkImageMemoryBarrier2.calloc(2, stack);
            barriers.get(0).sType$Default().srcStageMask(slot.used ? VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_2_NONE)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                    .oldLayout(slot.used ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .srcQueueFamilyIndex(slot.used ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(slot.used ? context.queueFamily : VK_QUEUE_FAMILY_IGNORED).image(slot.color.image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            barriers.get(1).sType$Default()
                    .srcStageMask(slot.used ? VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT : VK_PIPELINE_STAGE_2_NONE)
                    .dstStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                    .dstAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .oldLayout(slot.used ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .srcQueueFamilyIndex(slot.used ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(slot.used ? context.queueFamily : VK_QUEUE_FAMILY_IGNORED).image(slot.depth.image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1));
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            var color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(slot.color.view())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.clearValue().color().float32(0, terrain ? world.red() : .025f).float32(1, terrain ? world.green() : .04f)
                    .float32(2, terrain ? world.blue() : .075f).float32(3, terrain ? 0 : 1);
            var rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color)
                    .renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
            if (terrain) {
                var depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(slot.depth.view())
                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                depth.clearValue().depthStencil().depth(1);
                rendering.pDepthAttachment(depth);
            }
            int metric = terrain ? 0 : 2;
            if (terrain && culler != null) {
                timers.start(command, index, 3, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
                world.prepareCulling(command, stack, culler, index);
                timers.end(command, index, 3, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
            }
            timers.start(command, index, metric, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            vkCmdBeginRendering(command, rendering);
            vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1));
            vkCmdSetScissor(command, 0, VkRect2D.calloc(1, stack).offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
            if (terrain) world.draw(command, stack, culler, index);
            else {
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, trianglePipeline);
                vkCmdPushConstants(command, triangleLayout, VK_SHADER_STAGE_VERTEX_BIT, 0,
                        stack.floats((System.nanoTime() - started) / 1_000_000_000f * .7f, (float)width / height));
                vkCmdDraw(command, 3, 1, 0, 0);
            }
            vkCmdEndRendering(command);
            timers.end(command, index, metric, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            timers.start(command, index, 1, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var release = VkImageMemoryBarrier2.calloc(2, stack);
            release.get(0).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_NONE)
                    .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(context.queueFamily).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL).image(slot.color.image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            release.get(1).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(context.queueFamily).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL).image(slot.depth.image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1));
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(release));
            timers.end(command, index, 1, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkEndCommandBuffer(command), "end shared world command");
            var signals = VkSemaphoreSubmitInfo.calloc(2, stack);
            signals.get(0).sType$Default().semaphore(slot.color.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            signals.get(1).sType$Default().semaphore(slot.depth.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var submit = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command)).pSignalSemaphoreInfos(signals);
            if (slot.used) {
                var waits = VkSemaphoreSubmitInfo.calloc(2, stack);
                waits.get(0).sType$Default().semaphore(slot.color.complete).stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
                waits.get(1).sType$Default().semaphore(slot.depth.complete)
                        .stageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT);
                submit.pWaitSemaphoreInfos(waits);
            }
            check(vkResetFences(context.device, slot.fence), "reset shared world fence");
            check(vkQueueSubmit2(context.queue, submit, slot.fence), "submit shared world");
            slot.used = slot.pending = true; timers.submitted(index); frames++;
            slot.serial = frames;
            if (terrain) { terrainFrames++; sectionDraws += world.drawableSections(); }
            // Keep the GL read lease open through terrain + HUD/inset sampling, and close it before final presentation.
            reading = index;
            int texture = slot.color.beginReadGL();
            depthTexture = slot.depth.beginReadGL();
            if (frames % 300 == 0) { log.accept("WORLD_RENDERED_FRAMES=" + frames + " sections=" + world.trackedSections()); timers.report(log); }
            return texture;
        }
    }

    void endRead() {
        if (reading >= 0) { int index = reading; reading = -1; slots[index].color.endReadGL(); slots[index].depth.endReadGL(); }
    }
    private void waitSlot(int index) {
        Slot slot = slots[index];
        if (!slot.pending) return;
        check(vkWaitForFences(context.device, slot.fence, true, 5_000_000_000L), "wait shared world slot");
        slot.pending = false; timers.collect(index);
        if (culler != null) culler.collect(index);
        completedFrames = Math.max(completedFrames, slot.serial);
        while (!retired.isEmpty() && retired.peekFirst().serial() <= completedFrames) retired.removeFirst().close().run();
    }
    private void waitAll() { for (int i = 0; i < SLOT_COUNT; i++) waitSlot(i); }
    private void resize(int width, int height) {
        endRead(); finishGL.run(); waitAll(); destroyTargets();
        this.width = width; this.height = height;
        for (Slot slot : slots) {
            slot.color = new SharedPresentImage(context, width, height, importer);
            slot.depth = new SharedPresentImage(context, width, height, importer, true);
        }
        log.accept("WORLD_TARGET[" + ++generations + "]=" + width + "x" + height + " slots=" + SLOT_COUNT);
    }
    long[] stats() {
        long[] culling = culler == null ? new long[3] : culler.stats();
        return new long[]{world.renderedEpoch(), frames, world.trackedSections(), world.peakSections(), width, height, terrainFrames, sectionDraws, ProcessHandle.current().pid(), depthTexture,
                culling[0], culling[1], culling[2]};
    }
    private void destroyTargets() {
        for (Slot slot : slots) {
            if (slot.color != null) { slot.color.close(); slot.color = null; }
            if (slot.depth != null) { slot.depth.close(); slot.depth = null; }
            slot.used = false;
        }
    }
    @Override public void close() {
        endRead(); finishGL.run(); waitAll();
        log.accept("WORLD_FINAL_FRAMES=" + frames + " terrain=" + terrainFrames + " sectionDraws=" + sectionDraws + " peakSections=" + world.peakSections());
        log.accept("WORLD_RGBA_READBACK_BYTES=0");
        if (timers != null) { timers.report(log); timers.close(); timers = null; }
        if (culler != null) { culler.report(log); culler.close(); culler = null; }
        world.close(); destroyTargets();
        if (trianglePipeline != 0) { vkDestroyPipeline(context.device, trianglePipeline, null); trianglePipeline = 0; }
        if (triangleLayout != 0) { vkDestroyPipelineLayout(context.device, triangleLayout, null); triangleLayout = 0; }
        if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
        for (Slot slot : slots) if (slot.fence != 0) { vkDestroyFence(context.device, slot.fence, null); slot.fence = 0; }
    }
}
