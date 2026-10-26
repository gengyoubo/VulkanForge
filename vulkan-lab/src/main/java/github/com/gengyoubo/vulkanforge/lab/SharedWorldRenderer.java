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
        boolean nativeOwned, nativeTargetLeased;
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
    static final int MAX_OVERLAYS = 64;
    private int width, height, generations, reading = -1, overlayReading = -1;
    private EntityPass entities;
    private DepthComposite composite;
    private NativePost post;
    private final java.util.Set<String> unsupportedPostSources = new java.util.HashSet<>();
    private final SharedRenderTargets renderTargets;
    private int nativeReading = -1;
    private long nativeComposites, nativeEntityPasses;
    private long overlayPasses, skyDraws, handoffGroups, handoffImages;
    /** Sync-chain cost per frame: queue submits, CPU blocked in fence waits, GL semaphore calls (see SharedPresentImage.groupNanos). */
    private long submits, fenceWaitNanos, maxFenceWaitNanos, reportedFrames, reportedSubmits, reportedFenceNanos, reportedGlCalls, reportedGlNanos;
    private int depthTexture, translucentTexture, translucentDepthTexture;
    private long translucentSectionDraws;
    private static final long DEPTH_STAGES = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
    private static final long DEPTH_ACCESS = VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;

    SharedWorldRenderer(VulkanContext context, Function<long[], Consumer<int[]>> importer, Runnable finishGL, Consumer<String> log, SharedRenderTargets renderTargets) {
        this.context = context; this.importer = importer; this.finishGL = finishGL; this.log = log;
        this.renderTargets = renderTargets;
        world = new WorldScene(context, log);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            world.initialize();
            Consumer<Runnable> retire = resource -> {
                if (completedFrames >= frames) resource.run(); else retired.addLast(new Retired(frames, resource));
            };
            world.retireUsing(retire);
            // Per slot: one ring set per overlay pass plus one for the sky drawn inside the terrain submit.
            entities = new EntityPass(context, SLOT_COUNT * (MAX_OVERLAYS + 1), retire);
            composite = new DepthComposite(context);
            post = new NativePost(context, SLOT_COUNT * MAX_OVERLAYS);
            timers = new GpuTimers(context, SLOT_COUNT, log, "TERRAIN", "TERRAIN_RELEASE", "TRIANGLE", "CULLING", "TRANSLUCENT");
            var familyCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(context.physical, familyCount, null);
            var families = VkQueueFamilyProperties.calloc(familyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(context.physical, familyCount, families);
            if ((families.get(context.queueFamily).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0 && !Boolean.getBoolean("vulkanforge.disableGpuCulling"))
                culler = new GpuCuller(context, SLOT_COUNT);
            if (culler != null) world.enableGpuDriven(culler);
            log.accept("GPU_DRIVEN=" + (world.meshShaderActive() ? "NV_TASK_MESH" : culler != null && culler.compact() ? "COMPACT_INDIRECT_COUNT" : "FALLBACK"));
            log.accept("GPU_CULLING=" + (culler == null ? "DISABLED" : world.meshShaderActive() ? "TASK_SHADER" : "COMPUTE_INDIRECT"));
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
        if (command instanceof WorldProtocol.Reset || command instanceof WorldProtocol.Atlas) { endRead(); waitAll(); }
        if (command instanceof WorldProtocol.Reset || command instanceof WorldProtocol.Atlas) entities.invalidateDescriptors();
        world.apply(command);
    }
    void prepareFrame() { waitSlot((int)(frames % SLOT_COUNT)); }
    long epoch() { return world.epoch(); }

    int render(int requestedWidth, int requestedHeight) { return render(requestedWidth, requestedHeight, java.util.List.of()); }
    /** P10: {@code sky} packets (captured renderSky draws) are drawn first in the opaque pass, under terrain, with its depth. */
    int render(int requestedWidth, int requestedHeight, java.util.List<Object[]> sky) {
        return render(requestedWidth, requestedHeight, sky, false);
    }
    int render(int requestedWidth, int requestedHeight, java.util.List<Object[]> sky, boolean nativeMode) {
        endRead();
        if (!nativeMode) finishNativeLeases();
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
            if (slot.nativeOwned) {
                for (int k=0;k<4;k++) acquire.get(k).srcStageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT).srcAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            }
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
                    entities.record(command, stack, index * (MAX_OVERLAYS + 1) + MAX_OVERLAYS, sky, world.lightMapView(), world.lightSampler(), world.lightMapIdentity());
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
            if (nativeMode) for (int k=0;k<4;k++) release.get(k).dstStageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT).dstAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(release));
            boolean prepareNativeTarget = nativeMode && !slot.nativeTargetLeased;
            if (prepareNativeTarget) {
                var target = VkImageMemoryBarrier2.calloc(2, stack);
                acquireBarrier(target.get(0), slot.overlayUsed, slot.overlayColor, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0, VK_IMAGE_LAYOUT_GENERAL, false);
                acquireBarrier(target.get(1), slot.overlayUsed, slot.overlayDepth, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0, VK_IMAGE_LAYOUT_GENERAL, true);
                vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(target));
                releaseBarrier(target.get(0),slot.overlayColor,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,0,VK_IMAGE_LAYOUT_GENERAL,false);
                releaseBarrier(target.get(1),slot.overlayDepth,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,0,VK_IMAGE_LAYOUT_GENERAL,true);
                vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(target));
            }
            timers.end(command, index, 1, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkEndCommandBuffer(command), "end shared world command");
            SharedPresentImage[] images = slot.images();
            var signals = VkSemaphoreSubmitInfo.calloc(nativeMode ? prepareNativeTarget ? 1 : 0 : 1, stack);
            if (signals.hasRemaining()) signals.get(0).sType$Default().semaphore(nativeMode ? slot.overlayColor.ready : slot.color.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var submit = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command)).pSignalSemaphoreInfos(signals);
            if ((slot.used && !slot.nativeOwned) || (prepareNativeTarget && slot.overlayUsed)) {
                int waitCount=(slot.used && !slot.nativeOwned?1:0)+(prepareNativeTarget && slot.overlayUsed?1:0);
                var waits = VkSemaphoreSubmitInfo.calloc(waitCount, stack); int w=0;
                if(slot.used && !slot.nativeOwned) waits.get(w++).sType$Default().semaphore(slot.color.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                if(prepareNativeTarget && slot.overlayUsed) waits.get(w).sType$Default().semaphore(slot.overlayColor.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                submit.pWaitSemaphoreInfos(waits);
            }
            check(vkResetFences(context.device, slot.fence), "reset shared world fence");
            context.uploads.flush(); check(vkQueueSubmit2(context.queue, submit, slot.fence), "submit shared world"); submits++;
            slot.used = slot.pending = true; slot.nativeOwned = nativeMode; timers.submitted(index); frames++;
            if (prepareNativeTarget) {
                slot.overlayUsed = true;
                // Even frames that only use persistent shared targets must consume this ready signal. Keep the
                // compatibility pair leased until actually used or released; otherwise slot reuse waits on an unsignaled complete.
                SharedPresentImage.beginGroupGL(slot.overlayColor, slot.overlayDepth); slot.nativeTargetLeased = true;
            }
            slot.serial = frames;
            if (terrain) { terrainFrames++; sectionDraws += world.drawableSections(); translucentSectionDraws += world.translucentSections(); }
            // Keep the GL read lease open through terrain + HUD/inset sampling, and close it before final presentation.
            if(nativeMode) nativeReading=index; else reading=index;
            int[] textures = nativeMode ? new int[]{slot.color.textureGL(),slot.depth.textureGL(),slot.translucentColor.textureGL(),slot.translucentDepth.textureGL()}
                    : SharedPresentImage.beginGroupGL(images);
            if(!nativeMode){handoffGroups++;handoffImages+=images.length;}
            int texture = textures[0]; depthTexture = textures[1]; translucentTexture = textures[2]; translucentDepthTexture = textures[3];
            if (frames % 300 == 0) { log.accept("WORLD_RENDERED_FRAMES=" + frames + " sections=" + world.trackedSections()); timers.report(log); reportSync(); }
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
            entities.record(command, stack, index * (MAX_OVERLAYS + 1) + pass, packets, world.lightMapView(), world.lightSampler(), world.lightMapIdentity());
            vkCmdEndRendering(command);
            var release = VkImageMemoryBarrier2.calloc(2, stack);
            releaseBarrier(release.get(0), slot.overlayColor, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, false);
            releaseBarrier(release.get(1), slot.overlayDepth, DEPTH_STAGES, DEPTH_ACCESS, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, true);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(release));
            check(vkEndCommandBuffer(command), "end overlay command");
            var signals = VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(slot.overlayColor.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var submit = VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command)).pSignalSemaphoreInfos(signals);
            if (slot.overlayUsed) {
                var waits = VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(slot.overlayColor.complete)
                        .stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_COPY_BIT | DEPTH_STAGES);
                submit.pWaitSemaphoreInfos(waits);
            }
            check(vkResetFences(context.device, slot.overlayFences[pass]), "reset overlay fence");
            context.uploads.flush(); check(vkQueueSubmit2(context.queue, submit, slot.overlayFences[pass]), "submit overlay"); submits++;
            slot.overlayUsed = slot.overlayPending[pass] = true; overlayPasses++;
            overlayReading = index;
            handoffGroups++; handoffImages += 2;
            return SharedPresentImage.beginGroupGL(slot.overlayColor, slot.overlayDepth);
        }
    }
    /** GL has issued its composite of the current overlay: release it so the next pass of this frame can reuse the images. */
    void finishOverlay() {
        if (overlayReading >= 0) { int index = overlayReading; overlayReading = -1; SharedPresentImage.endGroupGL(slots[index].overlayColor, slots[index].overlayDepth); }
    }
    void entityTexture(int id, int width, int height, int flags, byte[] rgba) { entities.texture(id, width, height, flags, rgba); }
    void entityTexturePatch(int id, int x, int y, int width, int height, byte[] rgba) { entities.texturePatch(id, x, y, width, height, rgba); }
    boolean hasEntityTexture(int id) { return entities.hasTexture(id); }

    boolean nativeReady() { return nativeReading >= 0; }
    boolean composeNative(int framebuffer, int targetWidth, int targetHeight, int kind, java.util.List<Object[]> packets) {
        if (nativeReading < 0) return false;
        var target = renderTargets.get(framebuffer, targetWidth, targetHeight);
        if ((target == null || kind < 2) && (targetWidth != width || targetHeight != height)) return false;
        int index=nativeReading; Slot slot=slots[index];
        if(slot.overlays>=MAX_OVERLAYS) throw new IllegalStateException("Native render pass budget exceeded");
        int pass=slot.overlays++;
        SharedPresentImage targetColor = target == null ? slot.overlayColor : target.color;
        SharedPresentImage targetDepth = target == null ? slot.overlayDepth : target.depth;
        boolean hasDepth = true;
        if (target != null) target.releaseGL();
        else {
            if(!slot.nativeTargetLeased){SharedPresentImage.beginGroupGL(targetColor,targetDepth);slot.nativeTargetLeased=true;}
            hasDepth=targetColor.copyTargetGL(true,framebuffer,targetWidth,targetHeight,targetDepth);
            SharedPresentImage.endGroupGL(targetColor,targetDepth);slot.nativeTargetLeased=false;
        }
        try(var stack=MemoryStack.stackPush()) {
            var command=slot.overlayCommands[pass];
            check(vkResetCommandBuffer(command,0),"reset native composite");
            check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin native composite");
            var barriers=VkImageMemoryBarrier2.calloc(2,stack);
            acquireBarrier(barriers.get(0),true,targetColor,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,false);
            acquireBarrier(barriers.get(1),true,targetDepth,DEPTH_STAGES,DEPTH_ACCESS,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,true);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            var color=VkRenderingAttachmentInfo.calloc(1,stack).sType$Default().imageView(targetColor.view()).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var depth=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(targetDepth.view()).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(hasDepth?VK_ATTACHMENT_LOAD_OP_LOAD:VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            depth.clearValue().depthStencil().depth(1);
            vkCmdBeginRendering(command,VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth).renderArea(r->r.extent(e->e.set(targetWidth,targetHeight))));
            vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).width(targetWidth).height(targetHeight).minDepth(0).maxDepth(1));
            vkCmdSetScissor(command,0,VkRect2D.calloc(1,stack).extent(e->e.set(targetWidth,targetHeight)));
            if(kind<2){composite.record(command,stack,index,kind==1);nativeComposites++;}
            else {entities.record(command,stack,index*(MAX_OVERLAYS+1)+pass,packets,world.lightMapView(),world.lightSampler(),world.lightMapIdentity(),true);nativeEntityPasses++;overlayPasses++;}
            vkCmdEndRendering(command);
            releaseBarrier(barriers.get(0),targetColor,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,false);
            releaseBarrier(barriers.get(1),targetDepth,DEPTH_STAGES,DEPTH_ACCESS,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,true);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            check(vkEndCommandBuffer(command),"end native composite");
            var wait=VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(targetColor.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var signal=VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(targetColor.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkResetFences(context.device,slot.overlayFences[pass]),"reset native composite fence");
            context.uploads.flush(); check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,stack).sType$Default().pWaitSemaphoreInfos(wait).pSignalSemaphoreInfos(signal)
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(command)),slot.overlayFences[pass]),"submit native composite");
            slot.overlayPending[pass]=true; submits++;
        }
        if (target != null) target.acquireGL();
        else {
            SharedPresentImage.beginGroupGL(targetColor,targetDepth);slot.nativeTargetLeased=true;
            targetColor.copyTargetGL(false,framebuffer,targetWidth,targetHeight,targetDepth);
        }
        renderTargets.composed(target != null);
        handoffGroups++;handoffImages+=2;
        return true;
    }

    /** Post inputs remain GPU images; all preflight checks run before changing ownership or recording output. */
    boolean postNative(Object[] packet) {
        if (nativeReading < 0) return false;
        int[] output = (int[]) packet[5], metadata = (int[]) packet[3];
        var target = renderTargets.get(output[0], output[1], output[2]);
        if (target == null || metadata.length == 0 || metadata.length % 5 != 0) return false;
        var owners = new java.util.LinkedHashSet<SharedRenderTargets.Target>();
        var inputs = new SharedPresentImage[metadata.length / 5];
        for (int i = 0; i < inputs.length; i++) {
            int offset = i * 5;
            var owner = renderTargets.textureOwner(metadata[offset]);
            if (owner == null || owner == target || owner.width != metadata[offset+1] || owner.height != metadata[offset+2]) return false;
            boolean depth = metadata[offset] == owner.depth.textureGL();
            if (depth != (metadata[offset+3] != 0) || metadata[offset+4] < 0 || metadata[offset+4] > 1) return false;
            inputs[i] = depth ? owner.depth : owner.color; owners.add(owner);
        }
        Slot slot = slots[nativeReading];
        if (slot.overlays >= MAX_OVERLAYS) return false;
        // Shader compilation can reject resource-pack programs. Nothing has been submitted or changed yet.
        String sourceKey = (String) packet[1] + '\0' + packet[2];
        if (unsupportedPostSources.contains(sourceKey)) return false;
        try { post.prepare(packet); }
        catch (IllegalArgumentException | IllegalStateException error) {
            unsupportedPostSources.add(sourceKey);
            log.accept("POST_GL_FALLBACK=" + packet[0] + " reason=" + error.getMessage());
            return false;
        }
        int pass = slot.overlays++;
        var all = new java.util.ArrayList<>(owners); all.add(target);
        for (var owner : all) owner.releaseGL();
        try (var stack = MemoryStack.stackPush()) {
            var command = slot.overlayCommands[pass];
            check(vkResetCommandBuffer(command, 0), "reset native post");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin native post");
            var barriers = VkImageMemoryBarrier2.calloc(all.size()*2, stack);
            for (int i = 0; i < all.size(); i++) {
                var owner = all.get(i); boolean out = owner == target;
                SharedRenderTargets.acquire(barriers.get(i*2), owner.color, false, out ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        out ? VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        out ? VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                SharedRenderTargets.acquire(barriers.get(i*2+1), owner.depth, true, out ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        out ? DEPTH_STAGES : VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        out ? DEPTH_ACCESS : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            }
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            var color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(target.color.view()).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            float[] clear=packet.length>7?(float[])packet[7]:new float[]{1,1,1,0};
            for(int i=0;i<4;i++)color.clearValue().color().float32(i,clear[i]);
            var depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(target.depth.view()).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            depth.clearValue().depthStencil().depth(1);
            vkCmdBeginRendering(command, VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth)
                    .renderArea(r -> r.extent(e -> e.set(target.width, target.height))));
            vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).width(target.width).height(target.height).minDepth(0).maxDepth(1));
            vkCmdSetScissor(command, 0, VkRect2D.calloc(1, stack).extent(e -> e.set(target.width, target.height)));
            post.record(command, stack, nativeReading * MAX_OVERLAYS + pass, packet, inputs);
            vkCmdEndRendering(command);
            for (int i = 0; i < all.size(); i++) {
                var owner = all.get(i); boolean out = owner == target;
                SharedRenderTargets.release(barriers.get(i*2), owner.color, false, out ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        out ? VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        out ? VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                SharedRenderTargets.release(barriers.get(i*2+1), owner.depth, true, out ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        out ? DEPTH_STAGES : VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        out ? DEPTH_ACCESS : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            }
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            check(vkEndCommandBuffer(command), "end native post");
            var waits = VkSemaphoreSubmitInfo.calloc(all.size(), stack);
            var signals = VkSemaphoreSubmitInfo.calloc(all.size(), stack);
            for (int i = 0; i < all.size(); i++) {
                waits.get(i).sType$Default().semaphore(all.get(i).color.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                signals.get(i).sType$Default().semaphore(all.get(i).color.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            }
            check(vkResetFences(context.device, slot.overlayFences[pass]), "reset native post fence");
            context.uploads.flush(); check(vkQueueSubmit2(context.queue, VkSubmitInfo2.calloc(1, stack).sType$Default().pWaitSemaphoreInfos(waits).pSignalSemaphoreInfos(signals)
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command)), slot.overlayFences[pass]), "submit native post");
            slot.overlayPending[pass] = true; submits++;
        }
        for (var owner : all) owner.acquireGL();
        renderTargets.post();
        return true;
    }
    /** Per-frame averages since the previous report. */
    private void reportSync() {
        long frameCount = frames - reportedFrames;
        if (frameCount <= 0) return;
        log.accept(String.format(java.util.Locale.ROOT, "WORLD_SYNC frames=%d submitsPerFrame=%.2f glHandoffsPerFrame=%.2f cpuGlHandoffMs=%.3f cpuFenceWaitMs=%.3f maxFenceWaitMs=%.3f",
                frameCount, (double) (submits - reportedSubmits) / frameCount, (double) (SharedPresentImage.groupCalls - reportedGlCalls) / frameCount,
                (SharedPresentImage.groupNanos - reportedGlNanos) / 1e6 / frameCount, (fenceWaitNanos - reportedFenceNanos) / 1e6 / frameCount, maxFenceWaitNanos / 1e6));
        reportedFrames = frames; reportedSubmits = submits; reportedFenceNanos = fenceWaitNanos;
        reportedGlCalls = SharedPresentImage.groupCalls; reportedGlNanos = SharedPresentImage.groupNanos; maxFenceWaitNanos = 0;
    }
    private void finishNativeLeases(){for(Slot slot:slots)if(slot.nativeTargetLeased){SharedPresentImage.endGroupGL(slot.overlayColor,slot.overlayDepth);slot.nativeTargetLeased=false;}}

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
        nativeReading = -1;
        if (reading >= 0) { int index = reading; reading = -1; slots[index].sceneFresh = false; SharedPresentImage.endGroupGL(slots[index].images()); }
        finishOverlay();
    }
    private void waitSlot(int index) {
        Slot slot = slots[index];
        // One call for the terrain fence and every overlay fence of this slot, instead of one blocking call per fence.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var fences = stack.mallocLong(MAX_OVERLAYS + 1);
            for (int k = 0; k < MAX_OVERLAYS; k++) if (slot.overlayPending[k]) { fences.put(slot.overlayFences[k]); slot.overlayPending[k] = false; }
            if (slot.pending) fences.put(slot.fence);
            fences.flip();
            if (fences.hasRemaining()) {
                long start = System.nanoTime();
                check(vkWaitForFences(context.device, fences, true, 5_000_000_000L), "wait shared world slot");
                long elapsed = System.nanoTime() - start; fenceWaitNanos += elapsed; maxFenceWaitNanos = Math.max(maxFenceWaitNanos, elapsed);
            }
        }
        if (!slot.pending) return;
        slot.pending = false; timers.collect(index);
        if (culler != null) culler.collect(index);
        completedFrames = Math.max(completedFrames, slot.serial);
        while (!retired.isEmpty() && retired.peekFirst().serial() <= completedFrames) retired.removeFirst().close().run();
    }
    private void waitAll() { for (int i = 0; i < SLOT_COUNT; i++) waitSlot(i); }
    private void resize(int width, int height) {
        endRead(); finishNativeLeases(); finishGL.run(); waitAll(); destroyTargets();
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
        for(int i=0;i<slots.length;i++) composite.sources(i,slots[i].color.view(),slots[i].depth.view(),slots[i].translucentColor.view(),slots[i].translucentDepth.view());
        log.accept("WORLD_TARGET[" + ++generations + "]=" + width + "x" + height + " slots=" + SLOT_COUNT);
    }
    long[] stats() {
        long[] culling = culler == null ? new long[3] : culler.stats();
        long[] entity = entities == null ? new long[5] : entities.stats();
        return new long[]{world.renderedEpoch(), frames, world.trackedSections(), world.peakSections(), width, height, terrainFrames, sectionDraws, ProcessHandle.current().pid(), depthTexture,
                culling[0], culling[1], culling[2], translucentTexture, translucentDepthTexture, world.translucentSections(), translucentSectionDraws, world.translucentSorts(),
                overlayPasses, entity[0], entity[1], entity[2], entity[3], entity[4], skyDraws};
    }
    long[] optimizationStats() { long[] a = world.arenaStats(); return new long[]{world.indirectBatches(), a[0], a[1], a[2]}; }
    boolean gpuDrivenEnabled() { return culler != null && culler.compact(); }
    private void destroyTargets() {
        for (Slot slot : slots) {
            if (slot.color != null) { slot.color.close(); slot.color = null; }
            if (slot.depth != null) { slot.depth.close(); slot.depth = null; }
            if (slot.translucentColor != null) { slot.translucentColor.close(); slot.translucentColor = null; }
            if (slot.translucentDepth != null) { slot.translucentDepth.close(); slot.translucentDepth = null; }
            if (slot.overlayColor != null) { slot.overlayColor.close(); slot.overlayColor = null; }
            if (slot.overlayDepth != null) { slot.overlayDepth.close(); slot.overlayDepth = null; }
            if (slot.sceneDepth != null) { slot.sceneDepth.close(); slot.sceneDepth = null; }
            slot.used = slot.overlayUsed = slot.sceneFresh = slot.sceneReadable = slot.nativeOwned = slot.nativeTargetLeased = false;
        }
    }
    @Override public void close() {
        endRead(); finishNativeLeases(); finishGL.run(); waitAll();
        log.accept("NATIVE_COMPOSITES="+nativeComposites+" entityPasses="+nativeEntityPasses);
        log.accept("WORLD_FINAL_FRAMES=" + frames + " terrain=" + terrainFrames + " sectionDraws=" + sectionDraws + " peakSections=" + world.peakSections());
        log.accept("WORLD_TRANSLUCENT sectionDraws=" + translucentSectionDraws + " sorts=" + world.translucentSorts());
        log.accept("WORLD_RGBA_READBACK_BYTES=0");
        log.accept("GL_VULKAN_READ_LEASES groups=" + handoffGroups + " images=" + handoffImages);
        reportSync();
        if (entities != null) {
            long[] entity = entities.stats();
            log.accept("WORLD_BINDINGS=" + java.util.Arrays.toString(entities.bindingStats()));
            log.accept("WORLD_OVERLAYS passes=" + overlayPasses + " skyDraws=" + skyDraws + " draws=" + entity[0] + " vertices=" + entity[1] + " textures=" + entity[2] + " uploads=" + entity[3] + " missingTextures=" + entity[4]);
            entities.close(); entities = null;
        }
        if (timers != null) { timers.report(log); timers.close(); timers = null; }
        if (culler != null) { culler.report(log); culler.close(); culler = null; }
        world.close(); destroyTargets();
        if(composite!=null){composite.close();composite=null;}
        if(post!=null){post.close();post=null;}
        if (trianglePipeline != 0) { vkDestroyPipeline(context.device, trianglePipeline, null); trianglePipeline = 0; }
        if (triangleLayout != 0) { vkDestroyPipelineLayout(context.device, triangleLayout, null); triangleLayout = 0; }
        if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
        for (Slot slot : slots) {
            if (slot.fence != 0) { vkDestroyFence(context.device, slot.fence, null); slot.fence = 0; }
            for (int k = 0; k < MAX_OVERLAYS; k++) if (slot.overlayFences[k] != 0) { vkDestroyFence(context.device, slot.overlayFences[k], null); slot.overlayFences[k] = 0; }
        }
    }
}
