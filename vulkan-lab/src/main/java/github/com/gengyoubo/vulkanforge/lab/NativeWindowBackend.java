package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JDK-only boundary for a future GL-free Minecraft window owner. The caller owns the HWND,
 * message pump and input. This backend owns a private Vulkan device, terrain and swapchain.
 * It deliberately does not use MainWindowBackend, GL import callbacks or external handles.
 */
public final class NativeWindowBackend implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final PrintWriter report;
    private final VulkanContext context;
    private NativeWorldRenderer world;
    private NativeSwapchain presentation;
    private boolean acquired, closed;

    public NativeWindowBackend(String reportPath, long hwnd, long hinstance, int width, int height,
                               int apiVersion, String nvidiaExtensions, boolean requireValidation) throws java.io.IOException {
        Path path = Path.of(reportPath).toAbsolutePath(); Files.createDirectories(path.getParent());
        report = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), true);
        context = new VulkanContext(apiVersion, nvidiaExtensions);
        report.println("BACKEND=VK_NATIVE_WINDOW\nGAME_INTEGRATION=NOT_CONNECTED\nOPENGL_CONTEXTS=0\nSHARED_IMAGES=0\nEXTERNAL_SEMAPHORES=0\nGL_FALLBACK_ALLOWED=false");
        try {
            context.initializeNativeWin32(hwnd, hinstance, requireValidation);
            report.println("VALIDATION=" + (context.validationEnabled() ? "ENABLED" : "UNAVAILABLE"));
            report.println("PRESENT_RESOURCE_RETIREMENT=VK_EXT_swapchain_maintenance1_FENCES");
            world = new NativeWorldRenderer(context, width, height, report::println);
            presentation = new NativeSwapchain(context, report::println);
        } catch (RuntimeException | Error error) {
            report.println("INITIALIZATION_FAILED=" + error);
            try { close(); } catch (RuntimeException | Error cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    /** CPU-origin mesh/atlas/lightmap/camera commands; neither GL object IDs nor GPU readbacks are accepted. */
    public void accept(Object[] command) { requireOpen(); world.apply(WorldProtocol.unpack(command)); }
    void accept(WorldProtocol.Command command) { requireOpen(); world.apply(command); }

    /** Acquire before sampling input. Zero drawable extent skips WSI and rendering. */
    public boolean beginFrame(int width, int height) {
        requireOpen();
        acquired = presentation.begin(world.nextSlot(), width, height);
        try {
            if (acquired) {
                // Presentation's fence covers the final sampling read of this slot, after its terrain fence.
                world.waitSlot(world.nextSlot());
                world.resize(presentation.width(), presentation.height());
            }
        } catch (RuntimeException | Error error) {
            if (acquired) cancelFrame();
            throw error;
        }
        return acquired;
    }

    public boolean frame(int width, int height) { return frame(width, height, NativeFramePlan.terrain()); }
    boolean frame(int width, int height, NativeFramePlan plan) {
        requireOpen();
        try {
            plan.requireSupported();
            if (!beginFrame(width, height)) return false;
            int slot = world.nextSlot();
            world.frame(plan);
            presentation.present(world.output(slot));
            acquired = false;
            return true;
        } finally {
            // Shader/terrain failure cannot strand an acquired image or leave its binary semaphore signaled.
            if (acquired) cancelFrame();
        }
    }

    public void cancelFrame() { requireOpen(); presentation.cancel(); acquired = false; }
    public void setVsync(boolean enabled) { requireOpen(); presentation.setVsync(enabled); }
    public void captureNext(String path) { requireOpen(); presentation.captureNext(path); }
    public long presentedFrames() { requireOpen(); return presentation.frames(); }
    public int swapchainGenerations() { requireOpen(); return presentation.generations(); }
    public long[] terrainStats() { requireOpen(); return world.stats(); }
    public long[] presentationStats() {
        requireOpen();
        return new long[]{presentation.frames(), presentation.generations(), presentation.cancellations(), presentation.skippedExtents()};
    }
    public int validationErrors() { requireOpen(); return context.validationErrors(); }
    int width() { return world.width; }
    int height() { return world.height; }
    private void requireOpen() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native window backend must run on its owning render thread");
        if (closed) throw new IllegalStateException("Native window backend is closed");
    }

    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native window shutdown must run on its owning render thread");
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try {
            // First finish presentation reads, then free private scene images and geometry.
            if (presentation != null) {
                presentation.close();
                report.println("FINAL_PRESENTED_FRAMES=" + presentation.frames());
                report.println("FINAL_SWAPCHAIN_GENERATIONS=" + presentation.generations());
                report.println("CANCELLED_ACQUIRES=" + presentation.cancellations());
            }
        } catch (RuntimeException | Error error) { failure = error; }
        try {
            if (world != null) {
                report.println("TERRAIN_SUBMITTED_FRAMES=" + world.stats()[0]);
                world.close();
            }
        } catch (RuntimeException | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        try { context.close(); }
        catch (RuntimeException | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        report.println("VALIDATION_ERRORS=" + context.validationErrors());
        report.println("CLOSED=" + (failure == null)); report.close();
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
