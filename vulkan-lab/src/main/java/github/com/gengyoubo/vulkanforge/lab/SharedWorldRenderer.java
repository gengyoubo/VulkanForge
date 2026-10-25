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
        SharedPresentImage translucentColor, translucentDepth;
        /** Order fixes semaphore wait stages in render(). */
        SharedPresentImage[] images() { return new SharedPresentImage[]{color, depth, translucentColor, translucentDepth}; }
        VkCommandBuffer command;
        long fence;
        boolean used, pending;
        long serial;
        // P7-P9 overlay passes (entities, particles, weather): one color/depth pair reused by every pass of a frame. GL releases
        // it right after each composite, so each pass is its own Vulkan wait -> render -> signal -> GL lease cycle.
        // sceneDepth is a Vulkan-only copy of this frame's opaque terrain depth, filled by the terrain submit.
        SharedPresentImage overlayColor, overlayDepth;
        GpuImage sceneDepth;
        final VkCommandBuffer[] overlayCommands = new VkCommandBuffer[MAX_OVERLAYS];
        final long[] overlayFences = new long[MAX_OVERLAYS];
        final boolean[] overlayPending = new boolean[MAX_OVERLAYS];
        boolean overlayUsed, sceneFresh, sceneReadable;
        int overlays;
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
    /** Overlay passes allowed per frame; each owns a command buffer, fence and EntityPass ring set per slot. */
    static final int MAX_OVERLAYS = 16; // world windows plus order-preserving hand/HUD/screen splits (P11)
    private int width, height, generations, reading = -1, overlayReading = -1;
    private EntityPass entities;
    private long overlayPasses, skyDraws;
    private int depthTexture, translucentTexture, translucentDepthTexture;
    private long translucentSectionDraws;
    private static final long DEPTH_STAGES = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
    private static final long DEPTH_ACCESS = VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;

    SharedWorldRenderer(VulkanContext context, Function<long[], Consumer<int[]>> importer, Runnable finishGL, Consumer<String> log) {
        this.context = context; this.importer = importer; this.finishGL = finishGL; this.log = log;
        world = new WorldScene(context, log);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            world.initialize();
            Consumer<Runnable> retire = resource -> {
                if (completedFrames >= frames) resource.run(); else retired.addLast(new Retired(frames, resource));
            };
            world.retireUsing(retire);
            // Per slot: one ring set per overlay pass plus one for the sky drawn inside the terrain submit.
            entities = new EntityPass(context, SLOT_COUNT * (MAX_OVERLAYS + 1), retire);
            timers = new GpuTimers(context, SLOT_COUNT, log, "TERRAIN", "TERRAIN_RELEASE", "TRIANGLE", "CULLING", "TRANSLUCENT");
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
            var pointers = stack.mallocPointer(SLOT_COUNT * (1 + MAX_OVERLAYS));
            check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOT_COUNT * (1 + MAX_OVERLAYS)), pointers), "shared world commands");
            for (int i = 0; i < SLOT_COUNT; i++) {
                slots[i].command = new VkCommandBuffer(pointers.get(i), context.device);
                check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "shared world fence");
                slots[i].fence = handle.get(0);
                for (int k = 0; k < MAX_OVERLAYS; k++) {
                    slots[i].overlayCommands[k] = new VkCommandBuffer(pointers.get(SLOT_COUNT + i * MAX_OVERLAYS + k), context.device);
                    check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "shared overlay fence");
                    slots[i].overlayFences[k] = handle.get(0);
                }
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

    int render(int requestedWidth, int requestedHeight) { return render(requestedWidth, requestedHeight, java.util.List.of()); }
    /** P10: {@code sky} packets (captured renderSky draws) are drawn first in the opaque pass, under terrain, with its depth. */
    int render(int requestedWidth, int requestedHeight, java.util.List<Object[]> sky) {
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
            // Opaque color/depth feed the AFTER_CUTOUT composite; translucent color plus its own depth (a copy of opaque depth,
            // then nearest translucent surface) feed the AFTER_TRANSLUCENT composite. All four are leased to GL every frame.
            var acquire = VkImageMemoryBarrier2.calloc(4, stack);
            acquireBarrier(acquire.get(0), slot.used, slot.color, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, false);
            acquireBarrier(acquire.get(1), slot.used, slot.depth, DEPTH_STAGES, DEPTH_ACCESS, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, true);
            acquireBarrier(acquire.get(2), slot.used, slot.translucentColor, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, false);
            acquireBarrier(acquire.get(3), slot.used, slot.translucentDepth, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, true);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(acquire));
            var color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(slot.color.view())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.clearValue().color().float32(0, terrain ? world.red() : .025f).float32(1, terrain ? world.green() : .04f)
                    .float32(2, terrain ? world.blue() : .075f).float32(3, terrain ? 0 : 1);
            var rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color)
                    .renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
            var depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(slot.depth.view())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            depth.clearValue().depthStencil().depth(1);
            // The menu triangle pipeline has no depth format; its depth contents are unused (translucent stays empty).
            if (terrain) rendering.pDepthAttachment(depth);
            int metric = terrain ? 0 : 2;
            if (terrain && culler != null) {
                timers.start(command, index, 3, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
                world.prepareCulling(command, stack, culler, index);
                timers.end(command, index, 3, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
            }
            timers.start(command, index, metric, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            vkCmdBeginRendering(command, rendering);
            var viewport = VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1);
            var scissor = VkRect2D.calloc(1, stack).offset(o -> o.set(0, 0)).extent(e -> e.set(width, height));
            vkCmdSetViewport(command, 0, viewport);
            vkCmdSetScissor(command, 0, scissor);
            if (terrain) {
                if (!sky.isEmpty() && world.lightMapView() != 0) {
                    entities.record(command, stack, index * (MAX_OVERLAYS + 1) + MAX_OVERLAYS, sky, world.lightMapView(), world.lightSampler());
                    skyDraws += sky.size();
                }
                world.draw(command, stack, culler, index);
            }
            else {
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, trianglePipeline);
                vkCmdPushConstants(command, triangleLayout, VK_SHADER_STAGE_VERTEX_BIT, 0,
                        stack.floats((System.nanoTime() - started) / 1_000_000_000f * .7f, (float)width / height));
                vkCmdDraw(command, 3, 1, 0, 0);
            }
            vkCmdEndRendering(command);
            timers.end(command, index, metric, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            timers.start(command, index, 4, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var toCopy = VkImageMemoryBarrier2.calloc(1, stack);
            layoutBarrier(toCopy.get(0), slot.depth, DEPTH_STAGES, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, true);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toCopy));
            var copy = VkImageCopy.calloc(1, stack).srcSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1))
                    .dstSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).extent(e -> e.set(width, height, 1));
            vkCmdCopyImage(command, slot.depth.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, slot.translucentDepth.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            if (terrain) {
                // Entities test against Vulkan terrain depth; the copy stays Vulkan-owned in TRANSFER_DST until the entity submit.
                var scene = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                        .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(slot.sceneDepth.image)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1));
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(scene));
                vkCmdCopyImage(command, slot.depth.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, slot.sceneDepth.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            }
            slot.sceneFresh = terrain; slot.sceneReadable = false; slot.overlays = 0;
            var toTest = VkImageMemoryBarrier2.calloc(1, stack);
            layoutBarrier(toTest.get(0), slot.translucentDepth, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, DEPTH_STAGES, DEPTH_ACCESS,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, true);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toTest));
            var translucentColor = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(slot.translucentColor.view())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            translucentColor.clearValue().color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            var translucentDepth = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(slot.translucentDepth.view())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            vkCmdBeginRendering(command, VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(translucentColor).pDepthAttachment(translucentDepth)
                    .renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height))));
            vkCmdSetViewport(command, 0, viewport);
            vkCmdSetScissor(command, 0, scissor);
            if (terrain) world.drawTranslucent(command, stack, index);
            vkCmdEndRendering(command);
            timers.end(command, index, 4, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            timers.start(command, index, 1, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var release = VkImageMemoryBarrier2.calloc(4, stack);
            releaseBarrier(release.get(0), slot.color, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, false);
            releaseBarrier(release.get(1), slot.depth, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, true);
            releaseBarrier(release.get(2), slot.translucentColor, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, false);
            releaseBarrier(release.get(3), slot.translucentDepth, DEPTH_STAGES, DEPTH_ACCESS, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, true);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(release));
            timers.end(command, index, 1, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkEndCommandBuffer(command), "end shared world command");
            SharedPresentImage[] images = slot.images();
            var signals = VkSemaphoreSubmitInfo.calloc(images.length, stack);
            for (int i = 0; i < images.length; i++) signals.get(i).sType$Default().semaphore(images[i].ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var submit = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command)).pSignalSemaphoreInfos(signals);
            if (slot.used) {
                // Each wait stage matches the first use in this command buffer, which the acquire barriers chain from.
                long[] stages = {VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, DEPTH_STAGES, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_2_COPY_BIT};
                var waits = VkSemaphoreSubmitInfo.calloc(images.length, stack);
                for (int i = 0; i < images.length; i++) waits.get(i).sType$Default().semaphore(images[i].complete).stageMask(stages[i]);
                submit.pWaitSemaphoreInfos(waits);
            }
            check(vkResetFences(context.device, slot.fence), "reset shared world fence");
            check(vkQueueSubmit2(context.queue, submit, slot.fence), "submit shared world");
            slot.used = slot.pending = true; timers.submitted(index); frames++;
            slot.serial = frames;
            if (terrain) { terrainFrames++; sectionDraws += world.drawableSections(); translucentSectionDraws += world.translucentSections(); }
            // Keep the GL read lease open through terrain + HUD/inset sampling, and close it before final presentation.
            reading = index;
            int texture = slot.color.beginReadGL();
            depthTexture = slot.depth.beginReadGL();
            translucentTexture = slot.translucentColor.beginReadGL();
            translucentDepthTexture = slot.translucentDepth.beginReadGL();
            if (frames % 300 == 0) { log.accept("WORLD_RENDERED_FRAMES=" + frames + " sections=" + world.trackedSections()); timers.report(log); }
            return texture;
        }
    }

    /**
     * P7-P9: replays one capture window's draws over a copy of this frame's terrain depth. Must follow render() in the same frame;
     * returns {color, depth} GL textures leased until finishOverlay()/endRead(), or null when no terrain frame is open.
     */
    int[] renderOverlay(java.util.List<Object[]> packets) { return renderOverlay(packets, false); }
    /**
     * {@code clearDepth}: start from depth 1.0 instead of this frame's terrain depth - the hand and the GUI, which vanilla draws
     * after RenderSystem.clear(GL_DEPTH_BUFFER_BIT).
     */
    int[] renderOverlay(java.util.List<Object[]> packets, boolean clearDepth) {
        if (reading < 0) return null;
        finishOverlay();
        int index = reading;
        Slot slot = slots[index];
        if ((!clearDepth && !slot.sceneFresh) || slot.overlays >= MAX_OVERLAYS || world.lightMapView() == 0) return null;
        int pass = slot.overlays++;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer command = slot.overlayCommands[pass];
            check(vkResetCommandBuffer(command, 0), "reset overlay command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin overlay command");
            if (clearDepth) {
                // Hand/GUI: vanilla cleared depth before drawing them; the attachment is cleared below instead of copied.
                var acquire = VkImageMemoryBarrier2.calloc(2, stack);
                acquireBarrier(acquire.get(0), slot.overlayUsed, slot.overlayDepth, DEPTH_STAGES, DEPTH_ACCESS, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, true);
                acquireBarrier(acquire.get(1), slot.overlayUsed, slot.overlayColor, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, false);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(acquire));
            } else {
                var acquire = VkImageMemoryBarrier2.calloc(3, stack);
                // The terrain submit's copy into sceneDepth (and any earlier overlay's read of it) precede this submission on the queue.
                if (slot.sceneReadable) layoutBarrier(acquire.get(0), slot.sceneDepth.image, VK_PIPELINE_STAGE_2_COPY_BIT, 0, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, true);
                else layoutBarrier(acquire.get(0), slot.sceneDepth.image, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, true);
                slot.sceneReadable = true;
                acquireBarrier(acquire.get(1), slot.overlayUsed, slot.overlayDepth, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, true);
                acquireBarrier(acquire.get(2), slot.overlayUsed, slot.overlayColor, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, false);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(acquire));
                var copy = VkImageCopy.calloc(1, stack).srcSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1))
                        .dstSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).extent(e -> e.set(width, height, 1));
                vkCmdCopyImage(command, slot.sceneDepth.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, slot.overlayDepth.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                var toTest = VkImageMemoryBarrier2.calloc(1, stack);
                layoutBarrier(toTest.get(0), slot.overlayDepth.image, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, DEPTH_STAGES, DEPTH_ACCESS,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, true);
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toTest));
            }
            var color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(slot.overlayColor.view())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.clearValue().color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            var depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(slot.overlayDepth.view())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(clearDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            depth.clearValue().depthStencil().depth(1);
            vkCmdBeginRendering(command, VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth)
                    .renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height))));
            vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1));
            vkCmdSetScissor(command, 0, VkRect2D.calloc(1, stack).offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
            entities.record(command, stack, index * (MAX_OVERLAYS + 1) + pass, packets, world.lightMapView(), world.lightSampler());
            vkCmdEndRendering(command);
            var release = VkImageMemoryBarrier2.calloc(2, stack);
            releaseBarrier(release.get(0), slot.overlayColor, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, false);
            releaseBarrier(release.get(1), slot.overlayDepth, DEPTH_STAGES, DEPTH_ACCESS, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, true);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(release));
            check(vkEndCommandBuffer(command), "end overlay command");
            var signals = VkSemaphoreSubmitInfo.calloc(2, stack);
            signals.get(0).sType$Default().semaphore(slot.overlayColor.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            signals.get(1).sType$Default().semaphore(slot.overlayDepth.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var submit = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command)).pSignalSemaphoreInfos(signals);
            if (slot.overlayUsed) {
                var waits = VkSemaphoreSubmitInfo.calloc(2, stack);
                waits.get(0).sType$Default().semaphore(slot.overlayColor.complete).stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
                // First depth use is the copy, or the clear/depth tests for a clearDepth pass.
                waits.get(1).sType$Default().semaphore(slot.overlayDepth.complete).stageMask(VK_PIPELINE_STAGE_2_COPY_BIT | DEPTH_STAGES);
                submit.pWaitSemaphoreInfos(waits);
            }
            check(vkResetFences(context.device, slot.overlayFences[pass]), "reset overlay fence");
            check(vkQueueSubmit2(context.queue, submit, slot.overlayFences[pass]), "submit overlay");
            slot.overlayUsed = slot.overlayPending[pass] = true; overlayPasses++;
            overlayReading = index;
            return new int[]{slot.overlayColor.beginReadGL(), slot.overlayDepth.beginReadGL()};
        }
    }
    /** GL has issued its composite of the current overlay: release it so the next pass of this frame can reuse the images. */
    void finishOverlay() {
        if (overlayReading >= 0) { int index = overlayReading; overlayReading = -1; slots[index].overlayColor.endReadGL(); slots[index].overlayDepth.endReadGL(); }
    }
    void entityTexture(int id, int width, int height, int flags, byte[] rgba) { entities.texture(id, width, height, flags, rgba); }
    void entityTexturePatch(int id, int x, int y, int width, int height, byte[] rgba) { entities.texturePatch(id, x, y, width, height, rgba); }
    boolean hasEntityTexture(int id) { return entities.hasTexture(id); }

    /** First use per frame: from the GL owner (GENERAL) or, on a fresh image, from UNDEFINED. */
    private void acquireBarrier(VkImageMemoryBarrier2 barrier, boolean used, SharedPresentImage image, long stage, long access, int layout, boolean depth) {
        barrier.sType$Default().srcStageMask(used ? stage : VK_PIPELINE_STAGE_2_NONE).dstStageMask(stage).dstAccessMask(access)
                .oldLayout(used ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED).newLayout(layout)
                .srcQueueFamilyIndex(used ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(used ? context.queueFamily : VK_QUEUE_FAMILY_IGNORED).image(image.image)
                .subresourceRange(r -> r.aspectMask(depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
    }
    private void releaseBarrier(VkImageMemoryBarrier2 barrier, SharedPresentImage image, long stage, long access, int layout, boolean depth) {
        barrier.sType$Default().srcStageMask(stage).srcAccessMask(access).dstStageMask(VK_PIPELINE_STAGE_2_NONE)
                .oldLayout(layout).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(context.queueFamily).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL).image(image.image)
                .subresourceRange(r -> r.aspectMask(depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
    }
    private static void layoutBarrier(VkImageMemoryBarrier2 barrier, SharedPresentImage image, long srcStage, long srcAccess, long dstStage, long dstAccess,
                                      int oldLayout, int newLayout, boolean depth) {
        layoutBarrier(barrier, image.image, srcStage, srcAccess, dstStage, dstAccess, oldLayout, newLayout, depth);
    }
    private static void layoutBarrier(VkImageMemoryBarrier2 barrier, long image, long srcStage, long srcAccess, long dstStage, long dstAccess,
                                      int oldLayout, int newLayout, boolean depth) {
        barrier.sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image)
                .subresourceRange(r -> r.aspectMask(depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
    }

    void endRead() {
        if (reading >= 0) { int index = reading; reading = -1; slots[index].sceneFresh = false; for (SharedPresentImage image : slots[index].images()) image.endReadGL(); }
        finishOverlay();
    }
    private void waitSlot(int index) {
        Slot slot = slots[index];
        for (int k = 0; k < MAX_OVERLAYS; k++) if (slot.overlayPending[k]) {
            check(vkWaitForFences(context.device, slot.overlayFences[k], true, 5_000_000_000L), "wait shared overlay slot"); slot.overlayPending[k] = false;
        }
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
            slot.translucentColor = new SharedPresentImage(context, width, height, importer);
            slot.translucentDepth = new SharedPresentImage(context, width, height, importer, true);
            slot.overlayColor = new SharedPresentImage(context, width, height, importer);
            slot.overlayDepth = new SharedPresentImage(context, width, height, importer, true);
            slot.sceneDepth = new GpuImage(context, width, height, VK_FORMAT_D32_SFLOAT,
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
        }
        log.accept("WORLD_TARGET[" + ++generations + "]=" + width + "x" + height + " slots=" + SLOT_COUNT);
    }
    long[] stats() {
        long[] culling = culler == null ? new long[3] : culler.stats();
        long[] entity = entities == null ? new long[5] : entities.stats();
        return new long[]{world.renderedEpoch(), frames, world.trackedSections(), world.peakSections(), width, height, terrainFrames, sectionDraws, ProcessHandle.current().pid(), depthTexture,
                culling[0], culling[1], culling[2], translucentTexture, translucentDepthTexture, world.translucentSections(), translucentSectionDraws, world.translucentSorts(),
                overlayPasses, entity[0], entity[1], entity[2], entity[3], entity[4], skyDraws};
    }
    private void destroyTargets() {
        for (Slot slot : slots) {
            if (slot.color != null) { slot.color.close(); slot.color = null; }
            if (slot.depth != null) { slot.depth.close(); slot.depth = null; }
            if (slot.translucentColor != null) { slot.translucentColor.close(); slot.translucentColor = null; }
            if (slot.translucentDepth != null) { slot.translucentDepth.close(); slot.translucentDepth = null; }
            if (slot.overlayColor != null) { slot.overlayColor.close(); slot.overlayColor = null; }
            if (slot.overlayDepth != null) { slot.overlayDepth.close(); slot.overlayDepth = null; }
            if (slot.sceneDepth != null) { slot.sceneDepth.close(); slot.sceneDepth = null; }
            slot.used = slot.overlayUsed = slot.sceneFresh = slot.sceneReadable = false;
        }
    }
    @Override public void close() {
        endRead(); finishGL.run(); waitAll();
        log.accept("WORLD_FINAL_FRAMES=" + frames + " terrain=" + terrainFrames + " sectionDraws=" + sectionDraws + " peakSections=" + world.peakSections());
        log.accept("WORLD_TRANSLUCENT sectionDraws=" + translucentSectionDraws + " sorts=" + world.translucentSorts());
        log.accept("WORLD_RGBA_READBACK_BYTES=0");
        if (entities != null) {
            long[] entity = entities.stats();
            log.accept("WORLD_OVERLAYS passes=" + overlayPasses + " skyDraws=" + skyDraws + " draws=" + entity[0] + " vertices=" + entity[1] + " textures=" + entity[2] + " uploads=" + entity[3] + " missingTextures=" + entity[4]);
            entities.close(); entities = null;
        }
        if (timers != null) { timers.report(log); timers.close(); timers = null; }
        if (culler != null) { culler.report(log); culler.close(); culler = null; }
        world.close(); destroyTargets();
        if (trianglePipeline != 0) { vkDestroyPipeline(context.device, trianglePipeline, null); trianglePipeline = 0; }
        if (triangleLayout != 0) { vkDestroyPipelineLayout(context.device, triangleLayout, null); triangleLayout = 0; }
        if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
        for (Slot slot : slots) {
            if (slot.fence != 0) { vkDestroyFence(context.device, slot.fence, null); slot.fence = 0; }
            for (int k = 0; k < MAX_OVERLAYS; k++) if (slot.overlayFences[k] != 0) { vkDestroyFence(context.device, slot.overlayFences[k], null); slot.overlayFences[k] = 0; }
        }
    }
}
