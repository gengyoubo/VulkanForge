package github.com.gengyoubo.vulkanforge.lab;

import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;

import dev.vulkanforge.bridge.WorldProtocol;
import java.util.function.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;
import static org.lwjgl.vulkan.VK13.*;

/** GL_VK boundary adapter. Its legacy nativeMode flag selects Vulkan composites, not GL-free VK mode. */
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
    private final VulkanSceneCore core;
    private GpuTimers timers;
    private GpuTimers entityTimers;
    private GpuTimers nativeTimers;
    private double queueGapMs,envelopeMs;
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
    private final boolean optimizeInterop = !Boolean.getBoolean("vulkanforge.noInteropOptimizations");
    private final boolean glTail;
    private final boolean tailProfile = Boolean.getBoolean("vulkanforge.tailProfile");
    private final boolean tailDiagnostics = Boolean.getBoolean("vulkanforge.glTailDiagnostics");
    private long renderCpuCalls, renderCpuNanos, omittedTerrainFrames;
    private int postChainDepth, nativeIntervalDepth;
    private sealed interface PendingNative permits PendingPost, PendingComposite {
        SharedRenderTargets.Target target();
        java.util.List<SharedRenderTargets.Target> owners();
        int pass();
    }
    private record PendingPost(Object[] packet, SharedRenderTargets.Target target, SharedPresentImage[] inputs,
                               java.util.List<SharedRenderTargets.Target> owners, int pass) implements PendingNative { }
    private record PendingComposite(SharedRenderTargets.Target target, int kind, int pass) implements PendingNative {
        public java.util.List<SharedRenderTargets.Target> owners() { return java.util.List.of(); }
    }
    private final java.util.ArrayList<PendingNative> pendingPosts = new java.util.ArrayList<>();
    private long depthCopies, avoidedDepthCopies, postBatches, batchedPostPasses, mixedBatches, batchedComposites;
    /** Sync-chain cost per frame: queue submits, CPU blocked in fence waits, GL semaphore calls (see SharedPresentImage.groupNanos). */
    private long submits, queueSubmitNanos, recordNanos, prepareNanos, uploadFlushNanos, fenceWaitNanos, maxFenceWaitNanos, reportedFrames, reportedSubmits, reportedFenceNanos, reportedGlCalls, reportedGlNanos;
    private int depthTexture, translucentTexture, translucentDepthTexture;
    private long translucentSectionDraws;
    private static final long DEPTH_STAGES = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
    private static final long DEPTH_ACCESS = VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;

    SharedWorldRenderer(VulkanContext context, Function<long[], Consumer<int[]>> importer, Runnable finishGL, Consumer<String> log, SharedRenderTargets renderTargets, boolean glTail) {
        this.glTail=glTail;
        this.context = context; this.importer = importer; this.finishGL = finishGL; this.log = log;
        this.renderTargets = renderTargets;
        core = new VulkanSceneCore(context,SLOT_COUNT,log);world=core.world;culler=core.culler;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Consumer<Runnable> retire = resource -> {
                if (completedFrames >= frames) resource.run(); else retired.addLast(new Retired(frames, resource));
            };
            world.retireUsing(retire);
            // Per slot: one ring set per overlay pass plus one for the sky drawn inside the terrain submit.
            entities = new EntityPass(context, SLOT_COUNT * (MAX_OVERLAYS + 1), retire);
            composite = new DepthComposite(context);
            post = new NativePost(context, SLOT_COUNT * MAX_OVERLAYS);
            timers = new GpuTimers(context, SLOT_COUNT, log, "TERRAIN", "TERRAIN_RELEASE", "TRIANGLE", "CULLING", "TRANSLUCENT", "DEPTH_COPY", "TERRAIN_SUBMIT_BODY");
            if(Boolean.getBoolean("vulkanforge.interopProfile"))nativeTimers=new GpuTimers(context,SLOT_COUNT*MAX_OVERLAYS,log,"COMPOSITE","POST","NATIVE_SUBMIT_BODY");
            if(Boolean.getBoolean("vulkanforge.entityProfile"))entityTimers=new GpuTimers(context,SLOT_COUNT*(MAX_OVERLAYS+1),log,"ENTITY_OVERLAY");
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
        long start=tailProfile?System.nanoTime():0;
        try { return renderImpl(requestedWidth,requestedHeight,sky,nativeMode); }
        finally { if(tailProfile){renderCpuCalls++;renderCpuNanos+=System.nanoTime()-start;} }
    }
    long[] renderCpuStats(){return new long[]{renderCpuCalls,renderCpuNanos,omittedTerrainFrames};}
    private int renderImpl(int requestedWidth, int requestedHeight, java.util.List<Object[]> sky, boolean nativeMode) {
        if (glTail && nativeMode) throw new IllegalStateException("GL tail plan requires GL-owned terrain outputs");
        boolean omitTerrain=glTail&&tailDiagnostics&&Boolean.getBoolean("vulkanforge.glTailOmitTerrain");
        endRead();
        if (!nativeMode) finishNativeLeases();
        boolean terrain = world.ready();
        if (requestedWidth <= 0 || requestedHeight <= 0 || (!terrain && world.epoch() != 0)) return 0;
        if (width != requestedWidth || height != requestedHeight) resize(requestedWidth, requestedHeight);
        int index = (int)(frames % SLOT_COUNT);
        Slot slot = slots[index]; waitSlot(index);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer command = slot.command;
            long recordStart=System.nanoTime();
            check(vkResetCommandBuffer(command, 0), "reset shared world command");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin shared world command");
            timers.begin(command, index);
            if(nativeTimers!=null)timers.start(command,index,6,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
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
                core.prepare(command, stack, index);
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
                if(!omitTerrain)core.opaque(command, stack, index);
            }
            else {
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, trianglePipeline);
                vkCmdPushConstants(command, triangleLayout, VK_SHADER_STAGE_VERTEX_BIT, 0,
                        stack.floats((System.nanoTime() - started) / 1_000_000_000f * .7f, (float)width / height));
                vkCmdDraw(command, 3, 1, 0, 0);
            }
            vkCmdEndRendering(command);
            timers.end(command, index, metric, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            timers.start(command, index, 5, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var toCopy = VkImageMemoryBarrier2.calloc(1, stack);
            layoutBarrier(toCopy.get(0), slot.depth, DEPTH_STAGES, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, true);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toCopy));
            var copy = VkImageCopy.calloc(1, stack).srcSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1))
                    .dstSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).extent(e -> e.set(width, height, 1));
            vkCmdCopyImage(command, slot.depth.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, slot.translucentDepth.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            boolean copySceneDepth = terrain && !glTail && (!nativeMode || !optimizeInterop);
            if (copySceneDepth) {
                // Entities test against Vulkan terrain depth; the copy stays Vulkan-owned in TRANSFER_DST until the entity submit.
                var scene = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                        .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(slot.sceneDepth.image)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1));
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(scene));
                vkCmdCopyImage(command, slot.depth.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, slot.sceneDepth.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
            }
            depthCopies++; // Opaque -> independent translucent depth is needed for the later GL/entity ordering.
            if (copySceneDepth) depthCopies++; else if (terrain) avoidedDepthCopies++;
            slot.sceneFresh = copySceneDepth; slot.sceneReadable = false; slot.overlays = 0;
            var toTest = VkImageMemoryBarrier2.calloc(1, stack);
            layoutBarrier(toTest.get(0), slot.translucentDepth, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, DEPTH_STAGES, DEPTH_ACCESS,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, true);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toTest));
            timers.end(command,index,5,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            timers.start(command,index,4,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var translucentColor = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(slot.translucentColor.view())
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            translucentColor.clearValue().color().float32(0, 0).float32(1, 0).float32(2, 0).float32(3, 0);
            var translucentDepth = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(slot.translucentDepth.view())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            vkCmdBeginRendering(command, VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(translucentColor).pDepthAttachment(translucentDepth)
                    .renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height))));
            vkCmdSetViewport(command, 0, viewport);
            vkCmdSetScissor(command, 0, scissor);
            if (terrain && !omitTerrain) core.translucent(command, stack, index);
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
            if(nativeTimers!=null)timers.end(command,index,6,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkEndCommandBuffer(command), "end shared world command");
            recordNanos+=System.nanoTime()-recordStart;
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
            flushUploads(); submit(submit, slot.fence, "submit shared world"); submits++;
            slot.used = slot.pending = true; slot.nativeOwned = nativeMode; timers.submitted(index); frames++;
            if (prepareNativeTarget) {
                slot.overlayUsed = true;
                // Even frames that only use persistent shared targets must consume this ready signal. Keep the
                // compatibility pair leased until actually used or released; otherwise slot reuse waits on an unsignaled complete.
                SharedPresentImage.beginGroupGL(slot.overlayColor, slot.overlayDepth); slot.nativeTargetLeased = true;
            }
            slot.serial = frames;
            if (terrain) { terrainFrames++; if(omitTerrain)omittedTerrainFrames++;else{sectionDraws += world.drawableSections(); translucentSectionDraws += world.translucentSections();} }
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
            int entitySlot=index*(MAX_OVERLAYS+1)+pass;
            if(entityTimers!=null)entityTimers.begin(command,entitySlot);
            vkCmdBeginRendering(command, VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth)
                    .renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height))));
            vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1));
            vkCmdSetScissor(command, 0, VkRect2D.calloc(1, stack).offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
            if(entityTimers!=null)entityTimers.start(command,entitySlot,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            entities.record(command, stack, entitySlot, packets, world.lightMapView(), world.lightSampler(), world.lightMapIdentity());
            if(entityTimers!=null)entityTimers.end(command,entitySlot,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
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
            flushUploads(); submit(submit, slot.overlayFences[pass], "submit overlay"); submits++;
            if(entityTimers!=null)entityTimers.submitted(entitySlot);
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
        long prepareStart=System.nanoTime();
        if (nativeReading < 0) return false;
        var target = renderTargets.get(framebuffer, targetWidth, targetHeight);
        if ((target == null || kind < 2) && (targetWidth != width || targetHeight != height)) return false;
        int index=nativeReading; Slot slot=slots[index];
        if(slot.overlays>=MAX_OVERLAYS) throw new IllegalStateException("Native render pass budget exceeded");
        if (optimizeInterop && nativeIntervalDepth > 0 && target != null && kind >= 0 && kind < 2) {
            pendingPosts.add(new PendingComposite(target, kind, slot.overlays++));
            prepareNanos+=System.nanoTime()-prepareStart;
            return true;
        }
        prepareNanos+=System.nanoTime()-prepareStart;
        // Copy-backed/unregistered targets and entity passes remain explicit boundaries.
        flushPosts();
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
            long recordStart=System.nanoTime();
            var command=slot.overlayCommands[pass];
            check(vkResetCommandBuffer(command,0),"reset native composite");
            check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin native composite");
            int nativeTimerSlot=index*MAX_OVERLAYS+pass;
            if(kind<2&&nativeTimers!=null){nativeTimers.begin(command,nativeTimerSlot);nativeTimers.start(command,nativeTimerSlot,2,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);}
            var barriers=VkImageMemoryBarrier2.calloc(2,stack);
            acquireBarrier(barriers.get(0),true,targetColor,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,false);
            acquireBarrier(barriers.get(1),true,targetDepth,DEPTH_STAGES,DEPTH_ACCESS,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,true);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            var color=VkRenderingAttachmentInfo.calloc(1,stack).sType$Default().imageView(targetColor.view()).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var depth=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(targetDepth.view()).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(hasDepth?VK_ATTACHMENT_LOAD_OP_LOAD:VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            depth.clearValue().depthStencil().depth(1);
            int entitySlot=index*(MAX_OVERLAYS+1)+pass;
            if(kind<2&&nativeTimers!=null)nativeTimers.start(command,nativeTimerSlot,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            if(kind>=2&&entityTimers!=null)entityTimers.begin(command,entitySlot);
            vkCmdBeginRendering(command,VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth).renderArea(r->r.extent(e->e.set(targetWidth,targetHeight))));
            vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).width(targetWidth).height(targetHeight).minDepth(0).maxDepth(1));
            vkCmdSetScissor(command,0,VkRect2D.calloc(1,stack).extent(e->e.set(targetWidth,targetHeight)));
            if(kind<2){composite.record(command,stack,index,kind==1);nativeComposites++;}
            else {
                if(entityTimers!=null)entityTimers.start(command,entitySlot,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                entities.record(command,stack,entitySlot,packets,world.lightMapView(),world.lightSampler(),world.lightMapIdentity(),true);
                if(entityTimers!=null)entityTimers.end(command,entitySlot,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                nativeEntityPasses++;overlayPasses++;
            }
            vkCmdEndRendering(command);
            if(kind<2&&nativeTimers!=null)nativeTimers.end(command,nativeTimerSlot,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            releaseBarrier(barriers.get(0),targetColor,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,false);
            releaseBarrier(barriers.get(1),targetDepth,DEPTH_STAGES,DEPTH_ACCESS,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,true);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            if(kind<2&&nativeTimers!=null)nativeTimers.end(command,nativeTimerSlot,2,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkEndCommandBuffer(command),"end native composite");
            recordNanos+=System.nanoTime()-recordStart;
            var wait=VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(targetColor.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            var signal=VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(targetColor.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkResetFences(context.device,slot.overlayFences[pass]),"reset native composite fence");
            flushUploads(); submit(VkSubmitInfo2.calloc(1,stack).sType$Default().pWaitSemaphoreInfos(wait).pSignalSemaphoreInfos(signal)
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(command)),slot.overlayFences[pass],"submit native composite");
            if(kind>=2&&entityTimers!=null)entityTimers.submitted(index*(MAX_OVERLAYS+1)+pass);
            if(kind<2&&nativeTimers!=null)nativeTimers.submitted(nativeTimerSlot);
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
        long prepareStart=System.nanoTime();
        if (nativeReading < 0) return false;
        if(packet.length<6||!(packet[3] instanceof int[] meta)||meta.length==0||meta.length%5!=0||!(packet[4] instanceof float[] values)||values.length>256
                ||!(packet[5] instanceof int[] dimensions)||dimensions.length!=3||(packet.length>7&&(!(packet[7] instanceof float[] clear)||clear.length!=4)))return false;
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
        prepareNanos+=System.nanoTime()-prepareStart;
        int pass = slot.overlays++;
        if (optimizeInterop && (postChainDepth > 0 || nativeIntervalDepth > 0)) {
            pendingPosts.add(new PendingPost(packet, target, inputs, java.util.List.copyOf(owners), pass));
            return true;
        }
        var all = new java.util.ArrayList<>(owners); all.add(target);
        for (var owner : all) owner.releaseGL();
        try (var stack = MemoryStack.stackPush()) {
            long recordStart=System.nanoTime();
            var command = slot.overlayCommands[pass];
            check(vkResetCommandBuffer(command, 0), "reset native post");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin native post");
            int timerSlot=nativeReading*MAX_OVERLAYS+pass;
            if(nativeTimers!=null){nativeTimers.begin(command,timerSlot);nativeTimers.start(command,timerSlot,2,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);}
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
            if(nativeTimers!=null)nativeTimers.start(command,timerSlot,1,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            post.record(command, stack, nativeReading * MAX_OVERLAYS + pass, packet, inputs);
            vkCmdEndRendering(command);
            if(nativeTimers!=null)nativeTimers.end(command,timerSlot,1,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
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
            if(nativeTimers!=null)nativeTimers.end(command,timerSlot,2,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            check(vkEndCommandBuffer(command), "end native post");
            recordNanos+=System.nanoTime()-recordStart;
            var waits = VkSemaphoreSubmitInfo.calloc(all.size(), stack);
            var signals = VkSemaphoreSubmitInfo.calloc(all.size(), stack);
            for (int i = 0; i < all.size(); i++) {
                waits.get(i).sType$Default().semaphore(all.get(i).color.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                signals.get(i).sType$Default().semaphore(all.get(i).color.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            }
            check(vkResetFences(context.device, slot.overlayFences[pass]), "reset native post fence");
            flushUploads(); submit(VkSubmitInfo2.calloc(1, stack).sType$Default().pWaitSemaphoreInfos(waits).pSignalSemaphoreInfos(signals)
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command)), slot.overlayFences[pass], "submit native post");
            slot.overlayPending[pass] = true; submits++;
            if(nativeTimers!=null)nativeTimers.submitted(timerSlot);
        }
        for (var owner : all) owner.acquireGL();
        renderTargets.post();
        return true;
    }

    void beginPostChain() { postChainDepth++; }
    void endPostChain() {
        if (postChainDepth <= 0) throw new IllegalStateException("Unbalanced native post chain");
        if (--postChainDepth == 0 && nativeIntervalDepth == 0) flushPosts();
    }
    /** Opt-in contract: no GL image access inside this interval without flushPosts() first. */
    void beginNativeInterval() {
        if (!optimizeInterop || nativeReading < 0) throw new IllegalStateException("Native interval requires an active optimized GL-VK terrain frame");
        nativeIntervalDepth++;
    }
    void endNativeInterval() {
        if (nativeIntervalDepth <= 0) throw new IllegalStateException("Unbalanced native interval");
        if (--nativeIntervalDepth == 0 && postChainDepth == 0) flushPosts();
    }
    /** Explicit native scopes may defer work; any GL access/fallback first drains them. */
    void flushPosts() {
        if (pendingPosts.isEmpty()) return;
        if (nativeReading < 0) throw new IllegalStateException("Post batch outlived its terrain frame");
        var all = new java.util.LinkedHashSet<SharedRenderTargets.Target>();
        for (var item : pendingPosts) { all.addAll(item.owners()); all.add(item.target()); }
        Slot slot = slots[nativeReading]; int first = pendingPosts.get(0).pass();
        var command = slot.overlayCommands[first];
        // Build the whole command buffer while images remain GL-owned. A recording failure leaves every GL lease usable.
        try (var stack = MemoryStack.stackPush()) {
            long recordStart=System.nanoTime();
            check(vkResetCommandBuffer(command,0),"reset post batch");
            check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin post batch");
            if(nativeTimers!=null){for(var item:pendingPosts)nativeTimers.begin(command,nativeReading*MAX_OVERLAYS+item.pass());nativeTimers.start(command,nativeReading*MAX_OVERLAYS+first,2,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);}
            var layouts = new java.util.LinkedHashMap<SharedRenderTargets.Target,Boolean>();
            for (var item : pendingPosts) {
                try (var passStack = MemoryStack.stackPush()) {
                // Per-pass native scratch is reclaimed even for long chains.
                var touched = new java.util.ArrayList<>(item.owners()); touched.add(item.target());
                var barriers = VkImageMemoryBarrier2.calloc(touched.size()*2,passStack);
                for(int i=0;i<touched.size();i++) {
                    var owner=touched.get(i); boolean output=owner==item.target(); Boolean previous=layouts.get(owner);
                    for(int d=0;d<2;d++) {
                        boolean depth=d==1; var image=depth?owner.depth:owner.color;
                        int layout=output?(depth?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL):VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                        long stage=output?(depth?DEPTH_STAGES:VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT):VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
                        // Composite LOAD and blending read the destination as well as writing it.
                        long access=output?(depth?DEPTH_ACCESS:VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT):VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
                        if(previous==null)SharedRenderTargets.acquire(barriers.get(i*2+d),image,depth,layout,stage,access);
                        else layoutBarrier(barriers.get(i*2+d),image,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT,stage,access,
                                previous?(depth?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL):VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,layout,depth);
                    }
                    layouts.put(owner,output);
                }
                vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
                var target=item.target();
                boolean isPost = item instanceof PendingPost;
                int timerSlot=nativeReading*MAX_OVERLAYS+item.pass();
                if(nativeTimers!=null)nativeTimers.start(command,timerSlot,isPost?1:0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                int load = isPost ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
                var color=VkRenderingAttachmentInfo.calloc(1,stack).sType$Default().imageView(target.color.view()).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(load).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                if (item instanceof PendingPost p) {
                    float[] clear=p.packet().length>7?(float[])p.packet()[7]:new float[]{1,1,1,0};
                    for(int i=0;i<4;i++)color.clearValue().color().float32(i,clear[i]);
                }
                var depth=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(target.depth.view()).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(load).storeOp(VK_ATTACHMENT_STORE_OP_STORE);depth.clearValue().depthStencil().depth(1);
                vkCmdBeginRendering(command,VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depth).renderArea(r->r.extent(e->e.set(target.width,target.height))));
                vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).width(target.width).height(target.height).maxDepth(1));vkCmdSetScissor(command,0,VkRect2D.calloc(1,stack).extent(e->e.set(target.width,target.height)));
                if (item instanceof PendingPost p) post.record(command,stack,nativeReading*MAX_OVERLAYS+p.pass(),p.packet(),p.inputs());
                else if (item instanceof PendingComposite c) composite.record(command,stack,nativeReading,c.kind()==1);
                vkCmdEndRendering(command);
                if(nativeTimers!=null)nativeTimers.end(command,timerSlot,isPost?1:0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                }
            }
            var release=VkImageMemoryBarrier2.calloc(all.size()*2,stack);int i=0;
            for(var owner:all) {
                boolean output=layouts.get(owner);
                SharedRenderTargets.release(release.get(i*2),owner.color,false,output?VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT);
                SharedRenderTargets.release(release.get(i*2+1),owner.depth,true,output?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT);i++;
            }
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(release));if(nativeTimers!=null)nativeTimers.end(command,nativeReading*MAX_OVERLAYS+first,2,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);check(vkEndCommandBuffer(command),"end post batch");
            recordNanos+=System.nanoTime()-recordStart;
            var waits=VkSemaphoreSubmitInfo.calloc(all.size(),stack);var signals=VkSemaphoreSubmitInfo.calloc(all.size(),stack);i=0;
            for(var owner:all){waits.get(i).sType$Default().semaphore(owner.color.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);signals.get(i).sType$Default().semaphore(owner.color.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);i++;}
            // No allocation/compilation/recording can fail after the ownership changes.
            check(vkResetFences(context.device,slot.overlayFences[first]),"reset post batch fence");flushUploads();
            for(var owner:all)owner.releaseGL();
            submit(VkSubmitInfo2.calloc(1,stack).sType$Default().pWaitSemaphoreInfos(waits).pSignalSemaphoreInfos(signals).pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(command)),slot.overlayFences[first],"submit post batch");
            slot.overlayPending[first]=true;submits++;
            int posts=0, composites=0;
            for(var item:pendingPosts) {
                if(nativeTimers!=null)nativeTimers.submitted(nativeReading*MAX_OVERLAYS+item.pass());
                if(item instanceof PendingPost){renderTargets.post();posts++;}
                else {renderTargets.composed(true);nativeComposites++;composites++;}
            }
            if(posts>0){postBatches++;batchedPostPasses+=posts;}
            if(composites>0){mixedBatches++;batchedComposites+=composites;}
            for(var owner:all)owner.acquireGL();
        } finally { pendingPosts.clear(); }
    }
    long[] interopStats(){return new long[]{depthCopies,avoidedDepthCopies,postBatches,batchedPostPasses,submits,SharedPresentImage.groupCalls,mixedBatches,batchedComposites,SharedPresentImage.groupImageReferences,queueSubmitNanos,fenceWaitNanos,SharedPresentImage.groupNanos};}
    private void submit(VkSubmitInfo2.Buffer info, long fence, String stage) {
        long start=System.nanoTime();
        int result=vkQueueSubmit2(context.queue,info,fence);
        queueSubmitNanos+=System.nanoTime()-start;
        check(result,stage);
    }
    private void flushUploads(){long start=System.nanoTime();context.uploads.flush();uploadFlushNanos+=System.nanoTime()-start;}
    double[] phaseProfile(){
        waitAll();return phaseProfileSnapshot();
    }
    /** Queries only fences already signalled; no CPU/GPU drain at diagnostic sampling boundaries. */
    double[] phaseProfileSnapshot(){
        for(int index=0;index<SLOT_COUNT;index++){
            Slot slot=slots[index];
            if(slot.pending&&vkGetFenceStatus(context.device,slot.fence)==VK_SUCCESS)timers.collect(index);
            if(nativeTimers!=null)for(int pass=0;pass<slot.overlays;pass++)if(slot.overlayPending[pass]&&vkGetFenceStatus(context.device,slot.overlayFences[pass])==VK_SUCCESS)nativeTimers.collect(index*MAX_OVERLAYS+pass);
        }
        double[] a=new double[22];int p=0;
        for(int metric:new int[]{0,4,3,5}){double[] m=timers.metric(metric);a[p++]=m[0];a[p++]=m[1];}
        for(int metric=0;metric<2;metric++){double[] m=nativeTimers==null?new double[2]:nativeTimers.metric(metric);a[p++]=m[0];a[p++]=m[1];}
        a[p++]=recordNanos/1e6;a[p++]=uploadFlushNanos/1e6;a[p++]=queueSubmitNanos/1e6;a[p++]=prepareNanos/1e6;
        for(double v:timers.metric(6))a[p++]=v;for(double v:nativeTimers==null?new double[2]:nativeTimers.metric(2))a[p++]=v;a[p++]=queueGapMs;a[p]=envelopeMs;return a;
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
        flushPosts();postChainDepth=0;nativeIntervalDepth=0;
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
        if(entityTimers!=null)for(int k=0;k<MAX_OVERLAYS;k++)entityTimers.collect(index*(MAX_OVERLAYS+1)+k);
        if(nativeTimers!=null)for(int k=0;k<MAX_OVERLAYS;k++)nativeTimers.collect(index*MAX_OVERLAYS+k);
        if (!slot.pending) return;
        slot.pending = false; timers.collect(index);
        if(nativeTimers!=null){long[] terrain=timers.range(index,6);if(terrain!=null){long last=terrain[1];for(int pass=0;pass<slot.overlays;pass++){long[] range=nativeTimers.range(index*MAX_OVERLAYS+pass,2);if(range!=null){queueGapMs+=timers.elapsedMs(last,range[0]);last=range[1];}}envelopeMs+=timers.elapsedMs(terrain[0],last);}}
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
            if (!glTail) {
                slot.overlayColor = new SharedPresentImage(context, width, height, importer);
                slot.overlayDepth = new SharedPresentImage(context, width, height, importer, true);
                slot.sceneDepth = new GpuImage(context, width, height, VK_FORMAT_D32_SFLOAT,
                        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
            }
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
            log.accept("ENTITY_PERFORMANCE {calls,packets,recordNs,batchNs,uploadBytes,batchHeapCopyBytes,pipelines,growths,draws}="+java.util.Arrays.toString(entities.performanceStats()));
            log.accept("ENTITY_RECORD_CPU_MS "+entities.recordPercentiles());
            log.accept("WORLD_OVERLAYS passes=" + overlayPasses + " skyDraws=" + skyDraws + " draws=" + entity[0] + " vertices=" + entity[1] + " textures=" + entity[2] + " uploads=" + entity[3] + " missingTextures=" + entity[4]);
            entities.close(); entities = null;
        }
        if (timers != null) { timers.report(log); timers.close(); timers = null; }
        if(entityTimers!=null){entityTimers.report(log);entityTimers.close();entityTimers=null;}
        if(nativeTimers!=null){nativeTimers.report(log);nativeTimers.close();nativeTimers=null;}
        if (culler != null) { culler.report(log); culler = null; }
        core.close(); destroyTargets();
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
