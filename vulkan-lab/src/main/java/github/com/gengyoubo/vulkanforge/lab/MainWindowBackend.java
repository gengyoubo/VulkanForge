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
    private final VulkanContext context;
    private static final int FRAMES_IN_FLIGHT = 2;
    private static final class FrameSlot {
        long fence, acquired;
        int imageIndex = -1, acquireResult;
        VkCommandBuffer command;
        SharedPresentImage shared;
    }
    private final FrameSlot[] slots = {new FrameSlot(), new FrameSlot()};
    private long swapchain, pool;
    private long[] images = new long[0], views = new long[0], finished = new long[0];
    private GuiPass gui;
    private PresentPass scenePresent;
    private final java.util.Map<Integer, Object[]> mirroredTextures = new java.util.HashMap<>();
    private java.util.List<Object[]> guiPackets = java.util.List.of();
    private java.util.List<Object[]> handPackets = java.util.List.of();
    private int width, height, format, generations, requestedWidth, requestedHeight;
    private long graphPasses, graphBarriers;
    private long frames, skippedExtents, abandonedFrames;
    private long directSourceFrames, copiedSourceFrames, compatibilityImages;
    private long earlyAcquires, lateAcquires, cancelledAcquires, acquireNanos, maxAcquireNanos;
    private long fenceWaitNanos, maxFenceWaitNanos, presentNanos, maxPresentNanos;
    /** CPU spent recording/submitting the present command buffer, and in the GL side of the shared-source handoff (signal+flush, wait). */
    private long recordNanos, maxRecordNanos, handoffNanos, maxHandoffNanos;
    private boolean dirty = true, vsync = true;
    private String capture;
    private PrintWriter report;
    private GpuTimers timers;
    private SharedWorldRenderer world;
    private SharedRenderTargets renderTargets;
    private Function<long[], Consumer<int[]>> terrainImporter;
    private final Function<long[], Consumer<int[]>> importer;
    private final Runnable finishGL;

    public MainWindowBackend(String logPath, byte[] uuid, Function<long[], Consumer<int[]>> importer, Runnable finishGL) throws Exception {
        this(logPath, uuid, importer, finishGL, VulkanContext.requestedApiVersion());
    }

    public MainWindowBackend(String logPath, byte[] uuid, Function<long[], Consumer<int[]>> importer, Runnable finishGL, int apiVersion) throws Exception {
        this(logPath, uuid, importer, finishGL, apiVersion, System.getProperty("vulkanforge.nvidiaExtensions", ""));
    }

    /** nvidiaExtensions: comma-separated VK_NV_* names from the config screen. */
    public MainWindowBackend(String logPath, byte[] uuid, Function<long[], Consumer<int[]>> importer, Runnable finishGL, int apiVersion, String nvidiaExtensions) throws Exception {
        context = new VulkanContext(apiVersion, nvidiaExtensions);
        this.importer = importer; this.finishGL = finishGL;
        Configuration.STACK_SIZE.set(1024);
        report = new PrintWriter(Files.newBufferedWriter(Path.of(logPath)));
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                context.initializeInterop(uuid, Boolean.getBoolean("vulkanforge.requireValidation"));
                log("INSTANCE_API=" + VulkanContext.version(context.apiVersion));
                log("DEVICE_CAPABILITIES=" + context.capabilities);
                log("NVIDIA_EXTENSIONS=" + context.nvidia);
                log("MESH_SHADER_NV_ENABLED=" + context.meshShaderNV);
                log("PUSH_DESCRIPTOR_AVAILABLE=" + (context.pushDescriptors ? context.pushDescriptorCore ? "VULKAN14_CORE" : "KHR_ALIAS" : "NONE"));
                log("ENTITY_DESCRIPTOR_MODE=" + (Boolean.getBoolean("vulkanforge.preferPushDescriptors") && context.pushDescriptors ? "PUSH" : "CACHED_SET"));
                timers = new GpuTimers(context, FRAMES_IN_FLIGHT, this::log, "COPY", "PRESENT_PREPARE", "PRESENT_SCENE");
                log("VALIDATION=" + (context.validationEnabled() ? "ENABLED" : "UNAVAILABLE"));
                log("SYNC_VALIDATION_REQUESTED=" + "1".equals(System.getenv("VK_VALIDATION_VALIDATE_SYNC")));
                verifyInterop(stack);
                var handle = stack.mallocLong(1);
                check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily)
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "main-window command pool"); pool = handle.get(0);
                var pointer = stack.mallocPointer(FRAMES_IN_FLIGHT);
                check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(FRAMES_IN_FLIGHT), pointer), "main-window command buffers");
                for (int i = 0; i < slots.length; i++) {
                    slots[i].command = new VkCommandBuffer(pointer.get(i), context.device);
                    createSync(slots[i]);
                }
            }
            // Check actual handle import and FBO creation before the caller replaces the visible window.
            try (var probe = new SharedPresentImage(context, 16, 16, importer)) { log("SHARED_IMAGE_PROBE=OK"); }
            log("BACKEND=WIN32_VULKAN_SWAPCHAIN_GPU_INTEROP");
            log("GL_BINDINGS=HOST_CALLBACKS");
            log("FRAMES_IN_FLIGHT=" + FRAMES_IN_FLIGHT);
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
    public void prepare(int width, int height) { if (width > 0 && height > 0) recreate(width, height); }
    public long frames() { return frames; }
    /** {frames sampling existing targets, frames copying GL targets, allocated compatibility images}. */
    public long[] presentationStats() { return new long[]{directSourceFrames, copiedSourceFrames, compatibilityImages}; }
    public int generations() { return generations; }
    public long earlyAcquires() { return earlyAcquires; }
    /** Cumulative CPU nanoseconds: {frames, fenceWait, acquire, recordSubmit, glHandoff, queuePresent}. */
    public long[] cpuStats() { return new long[]{frames, fenceWaitNanos, acquireNanos, recordNanos, handoffNanos, presentNanos}; }
    public long lateAcquires() { return lateAcquires; }
    public boolean gpuDrivenEnabled() { return world != null && world.gpuDrivenEnabled(); }
    public long[] optimizationStats() { return world == null ? new long[4] : world.optimizationStats(); }
    public long[] memoryStats() { return context.memory.stats(); }
    public long[] uploadStats() { return context.uploads.stats(); }
    public int validationErrors() { return context.validationErrors(); }
    public boolean gpuTimingsEnabled() { return timers != null && timers.enabled(); }
    public long gpuTimingSamples(int metric) { return timers == null ? 0 : timers.samples(metric); }
    public void captureNext(String path) { capture = path; }
    public void setVsync(boolean enabled) { if (vsync != enabled) { vsync = enabled; dirty = true; } }

    public Consumer<Object[]> worldCommands(Function<long[], Consumer<int[]>> terrainImporter) {
        this.terrainImporter = terrainImporter;
        return packet -> applyWorld(dev.vulkanforge.bridge.WorldProtocol.unpack(packet));
    }
    private void applyWorld(dev.vulkanforge.bridge.WorldProtocol.Command command) {
        var renderer = world(); renderer.apply(command);
        if (gui != null && command.epoch() == renderer.epoch() && command instanceof dev.vulkanforge.bridge.WorldProtocol.TexturePatch patch
                && patch.target() == dev.vulkanforge.bridge.WorldProtocol.LIGHT_MAP) gui.lightmap(patch.rgba());
    }
    public boolean tryWorldCommand(Object[] packet) {
        var command = dev.vulkanforge.bridge.WorldProtocol.unpack(packet);
        int count = 0; long bytes = 0;
        if (command instanceof dev.vulkanforge.bridge.WorldProtocol.TexturePatch patch) {
            count = patch.target() == dev.vulkanforge.bridge.WorldProtocol.LIGHT_MAP ? 2 : 1; bytes = (long) patch.rgba().length * count;
        }
        if (command instanceof dev.vulkanforge.bridge.WorldProtocol.Mesh mesh && mesh.vertices().length > 0) { count = 2; bytes = mesh.vertices().length; }
        if (count > 0 && !context.uploads.available(count, bytes)) return false;
        applyWorld(command); return true;
    }
    private SharedWorldRenderer world() {
        if (world == null) {
            if (terrainImporter == null) throw new IllegalStateException("Missing host GL terrain importer");
            world = new SharedWorldRenderer(context, terrainImporter, finishGL, this::log, targets());
            for (Object[] t : mirroredTextures.values()) world.entityTexture((Integer) t[0], (Integer) t[1], (Integer) t[2], (Integer) t[3], (byte[]) t[4]);
        }
        return world;
    }
    public int renderWorld(int width, int height) { return world().render(width, height); }
    /** P10: like renderWorld, drawing the captured sky packets (EntityPass Object[]s) under the terrain. */
    public int renderWorldWithSky(int width, int height, Object[] sky) {
        return renderWorldWithSky(width,height,sky,false);
    }
    public int renderWorldWithSky(int width, int height, Object[] sky, boolean nativeMode) {
        var list = new java.util.ArrayList<Object[]>(sky.length);
        for (Object packet : sky) list.add((Object[]) packet);
        return world().render(width, height, list, nativeMode);
    }
    public boolean nativeComposite(int framebuffer,int width,int height,int kind,Object[] packets){
        if(world==null)return false;
        var list=new java.util.ArrayList<Object[]>();for(Object packet:packets)list.add((Object[])packet);
        return world.composeNative(framebuffer,width,height,kind,list);
    }
    private SharedRenderTargets targets() {
        if (renderTargets == null) renderTargets = new SharedRenderTargets(context, terrainImporter, finishGL, this::log);
        return renderTargets;
    }
    public int[] createRenderTarget(int framebuffer, int width, int height) { return targets().create(framebuffer, width, height); }
    public void destroyRenderTarget(int framebuffer) { if (renderTargets != null) renderTargets.destroy(framebuffer); }
    public long[] renderTargetStats() { return renderTargets == null ? new long[6] : renderTargets.stats(); }
    public boolean nativePost(Object[] packet) { return world != null && world.postNative(packet); }
    /** Captured GUI packets, consumed exactly once by the next present (including skipped/minimized frames). */
    public void gui(Object[] packets) {
        var list = new java.util.ArrayList<Object[]>(packets.length);
        for (Object packet : packets) list.add((Object[]) packet);
        guiPackets = list;
    }
    public long[] guiBindingStats() { return gui == null ? new long[5] : gui.bindingStats(); }
    public boolean pushDescriptorsEnabled() { return gui != null && gui.usesPushDescriptors(); }
    public long guiPasses() { return gui == null ? 0 : gui.passes(); }
    public long handPasses() { return gui == null ? 0 : gui.handPasses(); }
    public void hand(Object[] packets) {
        var list = new java.util.ArrayList<Object[]>(packets.length);
        for (Object packet : packets) list.add((Object[]) packet);
        handPackets = list;
    }
    public long[] worldStats() { return world == null ? new long[25] : world.stats(); }
    /** P7-P9: packets are EntityPass Object[]s; returns {color, depth} GL textures leased until finishOverlay, or null. */
    public int[] renderOverlay(Object[] packets) { return renderOverlay(packets, false); }
    /** P11: {@code clearDepth} starts the overlay from depth 1.0 (hand, GUI) instead of this frame's terrain depth. */
    public int[] renderOverlay(Object[] packets, boolean clearDepth) {
        if (world == null) return null;
        var list = new java.util.ArrayList<Object[]>(packets.length);
        for (Object packet : packets) list.add((Object[]) packet);
        return world.renderOverlay(list, clearDepth);
    }
    /** Called by GL right after compositing the overlay returned by renderOverlay. */
    public void finishOverlay() { if (world != null) world.finishOverlay(); }
    /** {Integer glId, Integer width, Integer height, Integer flags, byte[] rgba}; false when no world renderer exists. */
    public boolean entityTexture(Object[] texture) {
        if (gui == null || !context.uploads.available(world == null ? 1 : 2, ((byte[]) texture[4]).length * (world == null ? 1L : 2L))) return false;
        gui.texture(texture);
        mirroredTextures.put((Integer) texture[0], texture.clone());
        if (world != null) world.entityTexture((Integer) texture[0], (Integer) texture[1], (Integer) texture[2], (Integer) texture[3], (byte[]) texture[4]);
        return true;
    }
    public boolean entityTexturePatch(Object[] patch) {
        if (gui == null || !context.uploads.available(world == null ? 1 : 2, ((byte[]) patch[5]).length * (world == null ? 1L : 2L))) return false;
        gui.patch(patch);
        Object[] texture = mirroredTextures.get((Integer) patch[0]);
        byte[] pixels = ((byte[]) texture[4]).clone(), data = (byte[]) patch[5];
        int stride = (Integer) texture[1], x = (Integer) patch[1], y = (Integer) patch[2], w = (Integer) patch[3], h = (Integer) patch[4];
        for (int row = 0; row < h; row++) System.arraycopy(data, row * w * 4, pixels, ((y + row) * stride + x) * 4, w * 4);
        texture[4] = pixels;
        if (world != null && world.hasEntityTexture((Integer) patch[0])) world.entityTexturePatch((Integer) patch[0], (Integer) patch[1],
                (Integer) patch[2], (Integer) patch[3], (Integer) patch[4], (byte[]) patch[5]);
        return true;
    }
    public void finishWorldFrame() { if (world != null) world.endRead(); }
    public void closeWorld() { if (world != null) { world.close(); world = null; } }

    /** Wait for a slot/image before Minecraft polls input and advances its timer. */
    public boolean beginFrame(int framebufferWidth, int framebufferHeight) {
        context.uploads.flush(); // Never carry an open upload batch across frames.
        boolean acquired = acquire(framebufferWidth, framebufferHeight, true);
        if (acquired && world != null) world.prepareFrame();
        return acquired;
    }
    private boolean acquire(int framebufferWidth, int framebufferHeight, boolean early) {
        if (framebufferWidth <= 0 || framebufferHeight <= 0) return false;
        // Compare requested sizes: the surface may clamp them.
        if ((dirty || framebufferWidth != requestedWidth || framebufferHeight != requestedHeight) && !recreate(framebufferWidth, framebufferHeight)) return false;
        FrameSlot slot = slots[(int)(frames % slots.length)];
        if (slot.imageIndex >= 0) return true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long start = System.nanoTime();
            check(vkWaitForFences(context.device, slot.fence, true, 5_000_000_000L), "wait presentation slot before input");
            long elapsed = System.nanoTime() - start; fenceWaitNanos += elapsed; maxFenceWaitNanos = Math.max(maxFenceWaitNanos, elapsed);
            timers.collect((int)(frames % slots.length));
            if (gui != null) gui.prepare(frames);
            var index = stack.ints(0); start = System.nanoTime();
            int result = vkAcquireNextImageKHR(context.device, swapchain, 5_000_000_000L, slot.acquired, 0, index);
            elapsed = System.nanoTime() - start; acquireNanos += elapsed; maxAcquireNanos = Math.max(maxAcquireNanos, elapsed);
            if (result == VK_ERROR_OUT_OF_DATE_KHR) { dirty = true; return false; }
            if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) check(result, "acquire main-window swapchain");
            slot.imageIndex = index.get(0); slot.acquireResult = result;
            if (early) earlyAcquires++; else lateAcquires++;
            return true;
        }
    }

    public void present(int sourceFramebuffer, int sourceWidth, int sourceHeight, int framebufferWidth, int framebufferHeight) throws IOException {
        finishWorldFrame();
        var packets = guiPackets; guiPackets = java.util.List.of();
        var held = handPackets; handPackets = java.util.List.of();
        if (!acquire(framebufferWidth, framebufferHeight, false)) return;
        FrameSlot slot = slots[(int)(frames % slots.length)];
        int timerSlot = (int)(frames % slots.length);
        long fence = slot.fence, acquired = slot.acquired;
        VkCommandBuffer command = slot.command;
        final SharedRenderTargets.Target directSource = renderTargets == null || Boolean.getBoolean("vulkanforge.legacyPresent")
                ? null : renderTargets.get(sourceFramebuffer, sourceWidth, sourceHeight);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var index = stack.ints(slot.imageIndex);
            int acquiredResult = slot.acquireResult, image = slot.imageIndex;
            CaptureBuffer proof = null;
            boolean submitted = false, sourceReleased = false;
            try {
                SharedPresentImage shared;
                long handoff = System.nanoTime();
                if (directSource != null) {
                    directSource.releaseGL(); sourceReleased = true; shared = directSource.color;
                } else {
                    shared = compatibilitySource(slot, stack);
                    shared.copyFromGL(sourceFramebuffer, sourceWidth, sourceHeight);
                }
                long recordStart = System.nanoTime(); handoff = recordStart - handoff;
                check(vkResetCommandBuffer(command, 0), "reset presentation command");
                check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin presentation command");
                timers.begin(command, timerSlot);
                // Imported ownership and per-pass usages generate the synchronization2 barriers.
                var graph = new FrameGraph();
                long sourceStage = directSource != null ? VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT : VK_PIPELINE_STAGE_2_BLIT_BIT;
                long targetStage = directSource != null ? VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_2_BLIT_BIT;
                var source = new FrameGraph.Image("Scene source", shared.image, VK_IMAGE_ASPECT_COLOR_BIT,
                        new FrameGraph.State(VK_IMAGE_LAYOUT_GENERAL, sourceStage, 0, VK_QUEUE_FAMILY_EXTERNAL));
                var sourceDepth = directSource == null ? null : new FrameGraph.Image("Shared scene depth", directSource.depth.image, VK_IMAGE_ASPECT_DEPTH_BIT,
                        new FrameGraph.State(VK_IMAGE_LAYOUT_GENERAL, sourceStage, 0, VK_QUEUE_FAMILY_EXTERNAL));
                var target = new FrameGraph.Image("Swapchain", images[image], VK_IMAGE_ASPECT_COLOR_BIT,
                        FrameGraph.state(VK_IMAGE_LAYOUT_UNDEFINED, targetStage, 0));
                if (directSource != null) graph.pass("Scene composite", () -> {
                    timers.start(command, timerSlot, 2, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                    scenePresent.record(command, stack, timerSlot, shared.view(), views[image], width, height);
                    timers.end(command, timerSlot, 2, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                }, FrameGraph.use(source, new FrameGraph.State(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, sourceStage, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, context.queueFamily)),
                        FrameGraph.use(sourceDepth, new FrameGraph.State(VK_IMAGE_LAYOUT_GENERAL, sourceStage, 0, context.queueFamily)),
                        FrameGraph.use(target, FrameGraph.state(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, targetStage, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)));
                else graph.pass("GL blit", () -> {
                    var blit = VkImageBlit.calloc(1, stack).srcSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                            .dstSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1));
                    blit.srcOffsets(0).set(0, height, 0); blit.srcOffsets(1).set(width, 0, 1);
                    blit.dstOffsets(0).set(0, 0, 0); blit.dstOffsets(1).set(width, height, 1);
                    timers.start(command, timerSlot, 0, VK_PIPELINE_STAGE_2_BLIT_BIT);
                    vkCmdBlitImage(command, shared.image, VK_IMAGE_LAYOUT_GENERAL, images[image], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
                    timers.end(command, timerSlot, 0, VK_PIPELINE_STAGE_2_BLIT_BIT);
                }, FrameGraph.use(source, new FrameGraph.State(VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_READ_BIT, context.queueFamily)),
                        FrameGraph.use(target, FrameGraph.state(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_BLIT_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT)));
                if (!held.isEmpty()) graph.pass("Hand", () -> gui.recordHand(command, stack, timerSlot, views[image], width, height, held),
                        FrameGraph.use(target, FrameGraph.state(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)));
                if (!packets.isEmpty()) graph.pass("GUI", () -> gui.record(command, stack, timerSlot, views[image], width, height, packets),
                        FrameGraph.use(target, FrameGraph.state(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                                VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)));
                if (capture != null) {
                    proof = new CaptureBuffer(context, width * height * 4); final CaptureBuffer screenshot = proof;
                    graph.pass("Capture", () -> screenshot.recordCopy(command, images[image], width, height, stack),
                            FrameGraph.use(target, FrameGraph.state(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_2_COPY_BIT, VK_ACCESS_2_TRANSFER_READ_BIT)));
                }
                // Start before the terminal graph node so PRESENT_PREPARE includes the generated release barriers.
                graph.pass("Present timer", () -> timers.start(command, timerSlot, 1, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));
                var sourceRelease = FrameGraph.use(source, new FrameGraph.State(VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_2_NONE, 0, VK_QUEUE_FAMILY_EXTERNAL));
                var presentTarget = FrameGraph.use(target, FrameGraph.state(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_PIPELINE_STAGE_2_NONE, 0));
                FrameGraph.Use[] terminal = directSource == null ? new FrameGraph.Use[]{sourceRelease, presentTarget}
                        : new FrameGraph.Use[]{sourceRelease, presentTarget, FrameGraph.use(sourceDepth, new FrameGraph.State(VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_2_NONE, 0, VK_QUEUE_FAMILY_EXTERNAL))};
                graph.pass("Present", java.util.List.of("Present timer"), () -> timers.end(command, timerSlot, 1, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT), terminal);
                graphBarriers += graph.execute(command, stack); graphPasses += graph.schedule().size();
                check(vkEndCommandBuffer(command), "end presentation command");
                var waits = VkSemaphoreSubmitInfo.calloc(2, stack);
                waits.get(0).sType$Default().semaphore(acquired).stageMask(targetStage);
                waits.get(1).sType$Default().semaphore(shared.complete).stageMask(sourceStage);
                var signals = VkSemaphoreSubmitInfo.calloc(2, stack);
                signals.get(0).sType$Default().semaphore(finished[image]).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                signals.get(1).sType$Default().semaphore(shared.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                var submit = VkSubmitInfo2.calloc(1, stack).sType$Default().pWaitSemaphoreInfos(waits).pSignalSemaphoreInfos(signals)
                        .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command));
                check(vkResetFences(context.device, fence), "reset presentation fence");
                context.uploads.flush(); // Batched texture uploads sampled by hand/GUI passes go first.
                check(vkQueueSubmit2(context.queue, submit, fence), "submit main-window presentation");
                timers.submitted(timerSlot);
                long recordEnd = System.nanoTime(); recordNanos += recordEnd - recordStart; maxRecordNanos = Math.max(maxRecordNanos, recordEnd - recordStart);
                slot.imageIndex = -1;
                submitted = true;
                if (directSource != null) directSource.acquireGL();
                handoff += System.nanoTime() - recordEnd; handoffNanos += handoff; maxHandoffNanos = Math.max(maxHandoffNanos, handoff);
            } finally {
                if (!submitted) {
                    try {
                        if (proof != null) proof.close();
                        if (sourceReleased) restoreUnsubmittedSource(directSource, stack);
                    } finally { abandon(slot); }
                }
            }
            long start = System.nanoTime();
            int result = vkQueuePresentKHR(context.queue, VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(finished[image]))
                    .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(index));
            long elapsed = System.nanoTime() - start; presentNanos += elapsed; maxPresentNanos = Math.max(maxPresentNanos, elapsed);
            if (result != VK_SUCCESS && result != VK_ERROR_OUT_OF_DATE_KHR && result != VK_SUBOPTIMAL_KHR) check(result, "vkQueuePresentKHR(main window)");
            if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR || acquiredResult == VK_SUBOPTIMAL_KHR) dirty = true;
            frames++;
            if (directSource != null) directSourceFrames++; else copiedSourceFrames++;
            if (frames == 1) check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "wait first visible frame");
            if (proof != null) {
                try { check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "wait swapchain capture"); proof.save(capture, width, height, format); }
                finally { proof.close(); capture = null; }
            }
            if (frames == 1 || frames % 300 == 0) { log("PRESENTED_FRAMES=" + frames); timers.report(this::log); }
        }
    }

    /** Only a non-shared source needs a second exported color image. Allocate it on its first compatibility frame. */
    private SharedPresentImage compatibilitySource(FrameSlot slot, MemoryStack stack) {
        if (slot.shared != null) return slot.shared;
        SharedPresentImage shared = new SharedPresentImage(context, width, height, importer);
        try {
            var command = slot.command;
            check(vkResetCommandBuffer(command, 0), "reset compatibility initializer");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin compatibility initializer");
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_NONE).dstStageMask(VK_PIPELINE_STAGE_2_NONE)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(context.queueFamily).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL)
                    .image(shared.image).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
            check(vkEndCommandBuffer(command), "end compatibility initializer");
            check(vkQueueSubmit2(context.queue, VkSubmitInfo2.calloc(1, stack).sType$Default()
                    .pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default().commandBuffer(command))
                    .pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(shared.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)), 0), "initialize compatibility ownership");
            check(vkQueueWaitIdle(context.queue), "wait compatibility initializer");
            slot.shared = shared; compatibilityImages++;
            return shared;
        } catch (Throwable error) { vkQueueWaitIdle(context.queue); shared.close(); throw error; }
    }

    /** No command was submitted, so GL still owns GENERAL images. Consume its signal and restore its lease. */
    private void restoreUnsubmittedSource(SharedRenderTargets.Target source, MemoryStack stack) {
        check(vkQueueSubmit2(context.queue, VkSubmitInfo2.calloc(1, stack).sType$Default()
                .pWaitSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(source.color.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT))
                .pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(source.color.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)), 0), "restore unsubmitted presentation source");
        source.acquireGL();
    }

    private boolean recreate(int requestedWidth, int requestedHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physical, context.surface, caps), "main-window surface capabilities");
            int required = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            if ((caps.supportedUsageFlags() & required) != required) throw new IllegalStateException("Swapchain transfer usage unavailable");
            int extentWidth = caps.currentExtent().width() == -1 ? Math.max(caps.minImageExtent().width(), Math.min(requestedWidth, caps.maxImageExtent().width())) : caps.currentExtent().width();
            int extentHeight = caps.currentExtent().height() == -1 ? Math.max(caps.minImageExtent().height(), Math.min(requestedHeight, caps.maxImageExtent().height())) : caps.currentExtent().height();
            // Fullscreen/minimize transitions can briefly report 0x0 while GLFW still has a size; retry next frame.
            if (extentWidth <= 0 || extentHeight <= 0) { dirty = true; if (skippedExtents++ == 0) log("ZERO_SURFACE_EXTENT_SKIPPED"); return false; }
            finishGL.run(); drainAcquired(); check(vkDeviceWaitIdle(context.device), "wait resize"); timers.collectAll(); destroySwapchain();
            width = extentWidth; height = extentHeight;
            this.requestedWidth = requestedWidth; this.requestedHeight = requestedHeight;
            var count = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, null), "surface formats");
            var formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, formats), "surface formats");
            format = 0; int colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
            for (var candidate : formats) if ((candidate.format() == VK_FORMAT_B8G8R8A8_UNORM || candidate.format() == VK_FORMAT_R8G8B8A8_UNORM) && candidate.colorSpace() == colorSpace) { format = candidate.format(); break; }
            if (format == 0) throw new IllegalStateException("UNORM swapchain format required to preserve Minecraft colors");
            var features = VkFormatProperties.calloc(stack); vkGetPhysicalDeviceFormatProperties(context.physical, format, features);
            if ((features.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) throw new IllegalStateException("Swapchain format cannot be a blit destination");
            int imageCount = Math.max(caps.minImageCount(), Integer.getInteger("vulkanforge.swapchainImages", caps.minImageCount() + 1));
            if (caps.maxImageCount() > 0) imageCount = Math.min(imageCount, caps.maxImageCount());
            int mode = VK_PRESENT_MODE_FIFO_KHR;
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physical, context.surface, count, null), "present modes");
            var modes = stack.mallocInt(count.get(0));
            check(vkGetPhysicalDeviceSurfacePresentModesKHR(context.physical, context.surface, count, modes), "present modes");
            boolean mailbox = false, immediate = false;
            for (int i = 0; i < modes.remaining(); i++) { mailbox |= modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR; immediate |= modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR; }
            // Mailbox keeps VSync and replaces older pending frames; FIFO remains the portable fallback.
            if (vsync && mailbox) mode = VK_PRESENT_MODE_MAILBOX_KHR;
            else if (!vsync) mode = immediate ? VK_PRESENT_MODE_IMMEDIATE_KHR : mailbox ? VK_PRESENT_MODE_MAILBOX_KHR : VK_PRESENT_MODE_FIFO_KHR;
            String forced = System.getProperty("vulkanforge.presentMode", "");
            if (forced.equalsIgnoreCase("mailbox") && mailbox) mode = VK_PRESENT_MODE_MAILBOX_KHR;
            else if (forced.equalsIgnoreCase("immediate") && immediate) mode = VK_PRESENT_MODE_IMMEDIATE_KHR;
            else if (forced.equalsIgnoreCase("fifo")) mode = VK_PRESENT_MODE_FIFO_KHR;
            var info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(context.surface).minImageCount(imageCount).imageFormat(format).imageColorSpace(colorSpace)
                    .imageExtent(e -> e.set(width, height)).imageArrayLayers(1).imageUsage(required).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform()).compositeAlpha(Integer.lowestOneBit(caps.supportedCompositeAlpha())).presentMode(mode).clipped(true);
            var handle = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(context.device, info, null, handle), "create Minecraft swapchain"); swapchain = handle.get(0);
            check(vkGetSwapchainImagesKHR(context.device, swapchain, count, null), "swapchain images");
            var handles = stack.mallocLong(count.get(0)); check(vkGetSwapchainImagesKHR(context.device, swapchain, count, handles), "swapchain images");
            if (gui == null) gui = new GuiPass(context, format);
            scenePresent = new PresentPass(context, format);
            images = new long[count.get(0)]; views = new long[images.length]; finished = new long[images.length];
            for (int i = 0; i < images.length; i++) { images[i] = handles.get(i); check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "present image semaphore"); finished[i] = handle.get(0); }
            for (int i = 0; i < images.length; i++) {
                var view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(images[i]).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
                check(vkCreateImageView(context.device, view, null, handle), "GUI swapchain view"); views[i] = handle.get(0);
            }
            generations++; dirty = false;
            log("SWAPCHAIN[" + generations + "]=" + width + "x" + height + " format=" + format + " mode=" + mode
                    + " (" + (mode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" : mode == VK_PRESENT_MODE_IMMEDIATE_KHR ? "IMMEDIATE" : "FIFO") + ") images=" + images.length);
            return true;
        }
    }

    private void createSync(FrameSlot slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, handle), "main-window frame fence"); slot.fence = handle.get(0);
            check(vkCreateSemaphore(context.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle), "main-window acquire semaphore"); slot.acquired = handle.get(0);
        }
    }

    /** A frame that acquired an image but never reached the queue leaves its acquire semaphore pending and its GL handoff half done. */
    private void abandon(FrameSlot slot) {
        abandonedFrames++; dirty = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Consume the pending acquire signal on the queue so the semaphore and fence can be destroyed legally.
            check(vkResetFences(context.device, slot.fence), "reset abandoned frame fence");
            check(vkQueueSubmit(context.queue, VkSubmitInfo.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(slot.acquired))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)), slot.fence), "consume failed frame acquisition");
            slot.imageIndex = -1;
            finishGL.run(); check(vkDeviceWaitIdle(context.device), "wait abandoned frame");
            vkDestroyFence(context.device, slot.fence, null); vkDestroySemaphore(context.device, slot.acquired, null);
            slot.fence = slot.acquired = 0;
            createSync(slot);
            log("ABANDONED_FRAME=" + abandonedFrames);
        } catch (Throwable error) { log("ABANDON_FAILED=" + error); }
    }

    private void log(String message) { if (report != null) { report.println(message); report.flush(); } }
    private void drainAcquired() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (FrameSlot slot : slots) if (slot.imageIndex >= 0) {
                var wait = VkSemaphoreSubmitInfo.calloc(1, stack).sType$Default().semaphore(slot.acquired).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                check(vkQueueSubmit2(context.queue, VkSubmitInfo2.calloc(1, stack).sType$Default().pWaitSemaphoreInfos(wait), 0), "consume cancelled frame acquisition");
                slot.imageIndex = -1; cancelledAcquires++;
            }
        }
    }
    private void destroySwapchain() {
        if (scenePresent != null) { scenePresent.close(); scenePresent = null; }
        for (FrameSlot slot : slots) if (slot.shared != null) { slot.shared.close(); slot.shared = null; }
        for (long view : views) if (view != 0) vkDestroyImageView(context.device, view, null);
        views = new long[0];
        for (long semaphore : finished) if (semaphore != 0) vkDestroySemaphore(context.device, semaphore, null);
        finished = new long[0]; images = new long[0];
        if (swapchain != 0) { vkDestroySwapchainKHR(context.device, swapchain, null); swapchain = 0; }
    }
    @Override public void close() {
        if (context.device != null) {
            closeWorld();
            if (renderTargets != null) { renderTargets.close(); renderTargets = null; }
            finishGL.run(); drainAcquired(); check(vkDeviceWaitIdle(context.device), "wait presentation shutdown");
            if (timers != null) { timers.collectAll(); timers.report(this::log); timers.close(); }
            if (gui != null) { log("GUI_SWAPCHAIN_PASSES=" + gui.passes()); log("HAND_SWAPCHAIN_PASSES=" + gui.handPasses()); log("GUI_BINDINGS=" + java.util.Arrays.toString(gui.bindingStats())); gui.close(); gui = null; }
            destroySwapchain();
            if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
            for (FrameSlot slot : slots) {
                if (slot.fence != 0) { vkDestroyFence(context.device, slot.fence, null); slot.fence = 0; }
                if (slot.acquired != 0) { vkDestroySemaphore(context.device, slot.acquired, null); slot.acquired = 0; }
            }
        }
        if (frames > 0) {
            log("CPU_FENCE_WAIT_MS mean=" + fenceWaitNanos / (frames * 1_000_000.0) + " max=" + maxFenceWaitNanos / 1_000_000.0);
            log("CPU_PRESENT_MS mean=" + presentNanos / (frames * 1_000_000.0) + " max=" + maxPresentNanos / 1_000_000.0);
            log("CPU_ACQUIRE_MS mean=" + acquireNanos / (Math.max(1, earlyAcquires + lateAcquires) * 1_000_000.0) + " max=" + maxAcquireNanos / 1_000_000.0);
            log("CPU_RECORD_SUBMIT_MS mean=" + recordNanos / (frames * 1_000_000.0) + " max=" + maxRecordNanos / 1_000_000.0);
            log("CPU_GL_HANDOFF_MS mean=" + handoffNanos / (frames * 1_000_000.0) + " max=" + maxHandoffNanos / 1_000_000.0);
        }
        log("ACQUIRES_BEFORE_INPUT=" + earlyAcquires); log("ACQUIRES_AFTER_RENDER=" + lateAcquires); log("CANCELLED_ACQUIRES=" + cancelledAcquires);
        if (skippedExtents > 0 || abandonedFrames > 0) log("ZERO_EXTENT_FRAMES=" + skippedExtents + " ABANDONED_FRAMES=" + abandonedFrames);
        log("PRESENT_GRAPH passes=" + graphPasses + " imageBarriers=" + graphBarriers);
        log("PRESENT_SOURCE=[directFrames,copiedFrames,compatibilityImages] " + java.util.Arrays.toString(presentationStats()));
        if (context.uploads != null) log("ASYNC_UPLOADS=" + java.util.Arrays.toString(context.uploads.stats()));
        if (context.memory != null) log("GPU_MEMORY=" + java.util.Arrays.toString(context.memory.stats()));
        context.close(); log("FINAL_PRESENTED_FRAMES=" + frames); log("VALIDATION_ERRORS=" + context.validationErrors());
        if (report != null) { report.close(); report = null; }
    }
}
