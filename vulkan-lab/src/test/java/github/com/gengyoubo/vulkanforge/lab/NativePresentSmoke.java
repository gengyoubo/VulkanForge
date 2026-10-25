package github.com.gengyoubo.vulkanforge.lab;

import java.nio.file.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** Real GPU test: distinct native GL/Vulkan windows and exported images/semaphores. */
public final class NativePresentSmoke {
    public static void main(String[] args) throws Exception {
        if (GpuTimers.elapsedTicks(250, 5, 8) != 11 || GpuTimers.elapsedTicks(-4, 3, 64) != 7
                || GpuTimers.elapsedTicks(0xfffffff0L, 0x10, 32) != 32) throw new AssertionError("Timestamp wrap conversion failed");
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        long hidden = 0, visible = 0;
        MainWindowBackend backend = null;
        int texture = 0, source = 0;
        Path output = Path.of("build/present-smoke").toAbsolutePath(); Files.createDirectories(output);
        AtomicInteger failurePhase = new AtomicInteger();
        try {
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            hidden = glfwCreateWindow(320, 240, "Hidden legacy renderer", 0, 0);
            if (hidden == 0) throw new AssertionError("GL window creation failed");
            glfwMakeContextCurrent(hidden); GL.createCapabilities();
            backend = new MainWindowBackend(output.resolve("report.txt").toString(), GlInterop.deviceUUID(),
                    handles -> new FaultImage(GlInterop.importImage(handles), failurePhase), org.lwjgl.opengl.GL11::glFinish);
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE); glfwWindowHint(GLFW_AUTO_ICONIFY, GLFW_FALSE);
            visible = glfwCreateWindow(640, 480, "VulkanForge main-window presentation check", 0, 0);
            if (visible == 0 || glfwGetWindowAttrib(visible, GLFW_CLIENT_API) != GLFW_NO_API) throw new AssertionError("Visible window must be GLFW_NO_API");
            long hwnd = glfwGetWin32Window(visible); backend.attach(hwnd, GlInterop.windowInstance(hwnd));
            texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 320, 240, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
            source = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, source);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Source FBO incomplete");
            // OpenGL's top-left (red), top-right (green), bottom-left (blue), bottom-right (white).
            glEnable(GL_SCISSOR_TEST);
            quadrant(0, 120, 1, 0, 0); quadrant(160, 120, 0, 1, 0);
            quadrant(0, 0, 0, 0, 1); quadrant(160, 0, 1, 1, 1); glDisable(GL_SCISSOR_TEST);
            for (int i = 0; i < 90; i++) {
                glfwPollEvents();
                if (i == 40) { glfwSetWindowSize(visible, 800, 500); glfwPollEvents(); backend.setVsync(false); }
                int[] w = {0}, h = {0}; glfwGetFramebufferSize(visible, w, h);
                if (!backend.beginFrame(w[0], h[0])) throw new AssertionError("Could not acquire before generating motion");
                // Change the source every frame, including both shared-image slots and many image acquisitions.
                glEnable(GL_SCISSOR_TEST); glScissor(144, 104, 32, 32);
                glClearColor((i + 1) / 255f, (255 - i) / 255f, ((i * 17) & 255) / 255f, 1); glClear(GL_COLOR_BUFFER_BIT);
                glDisable(GL_SCISSOR_TEST);
                if (i % 7 == 0) backend.captureNext(output.resolve("motion-" + i + ".png").toString());
                if (i == 20) { backend.captureNext(output.resolve("initial.png").toString()); }
                if (i == 55) backend.captureNext(output.resolve("resized.png").toString());
                backend.present(source, 320, 240, w[0], h[0]);
                if (i == 39) backend.beginFrame(w[0], h[0]); // Resize next iteration cancels a pending acquisition.
            }
            long before = backend.frames(); backend.present(source, 320, 240, 0, 0);
            if (backend.frames() != before || before < 80 || backend.generations() < 2) throw new AssertionError("Frame/resize/zero extent check failed");
            verify(output.resolve("initial.png"), 640, 480); verify(output.resolve("resized.png"), 800, 500);
            // Monitor-sized fullscreen at the desktop mode (no display mode change), then back to a window.
            long monitor = glfwGetPrimaryMonitor(); var mode = glfwGetVideoMode(monitor);
            if (monitor == 0 || mode == null) throw new AssertionError("No primary monitor for fullscreen check");
            int generations = backend.generations();
            glfwSetWindowMonitor(visible, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
            int[] fullscreen = presentFrames(backend, visible, source, 30, output.resolve("fullscreen.png"));
            glfwSetWindowMonitor(visible, 0, 120, 120, 640, 480, GLFW_DONT_CARE);
            int[] windowed = presentFrames(backend, visible, source, 30, output.resolve("windowed.png"));
            if (backend.generations() < generations + 2) throw new AssertionError("Fullscreen round trip did not rebuild the swapchain twice");
            verify(output.resolve("fullscreen.png"), fullscreen[0], fullscreen[1]); verify(output.resolve("windowed.png"), windowed[0], windowed[1]);
            before = backend.frames();
            for (int i = 0; i < 90; i += 7) {
                var image = ImageIO.read(output.resolve("motion-" + i + ".png").toFile());
                int expected = (i + 1) << 16 | (255 - i) << 8 | ((i * 17) & 255);
                int actual = image.getRGB(image.getWidth() / 2, image.getHeight() / 2) & 0xffffff;
                if (actual != expected) throw new AssertionError("Stale/out-of-order presentation at frame " + i + ": expected=" + Integer.toHexString(expected) + ", actual=" + Integer.toHexString(actual));
            }
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("GL error during shared-image presentation");
            if (backend.earlyAcquires() < 150 || backend.lateAcquires() != 0) throw new AssertionError("Image acquisition still happens after motion/rendering");
            for (int phase : new int[]{1, 2}) {
                long frames = backend.frames(); backend.beginFrame(640, 480); failurePhase.set(phase);
                try { backend.present(source, 320, 240, 640, 480); throw new AssertionError("Injected GL handoff failure was swallowed"); }
                catch (IllegalStateException expected) { if (!expected.getMessage().equals("injected GL handoff failure")) throw expected; }
                if (backend.frames() != frames) throw new AssertionError("Unsubmitted frame was counted as presented");
                backend.beginFrame(640, 480); backend.present(source, 320, 240, 640, 480);
                if (backend.frames() != frames + 1) throw new AssertionError("Failed handoff did not recover");
            }
            boolean timing = backend.gpuTimingsEnabled();
            backend.beginFrame(640, 480); // Closing must consume this acquired-but-unsubmitted frame.
            backend.close();
            if (backend.validationErrors() != 0) throw new AssertionError("Vulkan validation errors: " + backend.validationErrors());
            if (timing && (backend.gpuTimingSamples(0) != backend.frames() || backend.gpuTimingSamples(1) != backend.frames()))
                throw new AssertionError("GPU samples lost or unsubmitted frames counted: " + backend.gpuTimingSamples(0) + "/" + backend.gpuTimingSamples(1));
            System.out.println("PRESENT_SMOKE=OK frames=" + backend.frames() + " generations=" + backend.generations() + " fullscreen=" + fullscreen[0] + "x" + fullscreen[1]
                    + " (RGBA orientation, fresh moving pixels, GPU interop, resize, fullscreen round trip, vsync, zero extent, GPU timestamps and failure recovery)");
            backend = null;
        } finally {
            if (backend != null) backend.close();
            if (source != 0) glDeleteFramebuffers(source); if (texture != 0) glDeleteTextures(texture);
            if (visible != 0) glfwDestroyWindow(visible); if (hidden != 0) glfwDestroyWindow(hidden);
            glfwTerminate();
        }
    }
    /** Exercise failures both before GL waits and after GL signals its exported semaphore. */
    private static final class FaultImage implements Consumer<int[]>, AutoCloseable {
        private final Consumer<int[]> image;
        private final AtomicInteger failure;
        FaultImage(Consumer<int[]> image, AtomicInteger failure) { this.image = image; this.failure = failure; }
        @Override public void accept(int[] source) {
            int phase = failure.getAndSet(0);
            if (phase == 1) throw new IllegalStateException("injected GL handoff failure");
            image.accept(source);
            if (phase == 2) throw new IllegalStateException("injected GL handoff failure");
        }
        @Override public void close() throws Exception { ((AutoCloseable) image).close(); }
    }
    /** Presents a few frames and captures the last; returns the framebuffer size of the captured frame. */
    private static int[] presentFrames(MainWindowBackend backend, long window, int source, int count, Path capture) throws Exception {
        int[] w = {0}, h = {0};
        for (int i = 0; i < count; i++) {
            glfwPollEvents(); glfwGetFramebufferSize(window, w, h);
            if (!backend.beginFrame(w[0], h[0])) continue;
            if (i == count - 1) backend.captureNext(capture.toString());
            backend.present(source, 320, 240, w[0], h[0]);
        }
        return new int[]{w[0], h[0]};
    }
    private static void quadrant(int x, int y, float r, float g, float b) {
        glScissor(x, y, 160, 120); glClearColor(r, g, b, 1); glClear(GL_COLOR_BUFFER_BIT);
    }
    private static void verify(Path path, int width, int height) throws Exception {
        var image = ImageIO.read(path.toFile());
        if (image.getWidth() != width || image.getHeight() != height) throw new AssertionError("Capture size " + path);
        int[] colors = {0xff0000, 0x00ff00, 0x0000ff, 0xffffff};
        for (int i = 0; i < 4; i++) {
            int actual = image.getRGB((i % 2 * 2 + 1) * width / 4, (i / 2 * 2 + 1) * height / 4) & 0xffffff;
            if (actual != colors[i]) throw new AssertionError(path + " quadrant=" + i + " expected=" + Integer.toHexString(colors[i]) + " actual=" + Integer.toHexString(actual));
        }
    }
}
