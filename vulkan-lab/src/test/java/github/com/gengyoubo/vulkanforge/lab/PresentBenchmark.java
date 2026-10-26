package github.com.gengyoubo.vulkanforge.lab;

import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Uncapped main-window frame loop over the shared GL target (the game's default path): GL clears the shared source,
 * Vulkan samples it into the swapchain. Reports wall FPS and the backend's CPU breakdown per frame.
 * -Dbench.frames=N, -Dbench.fullscreen=true, -Dbench.width/height, -Dbench.vsync=true.
 */
public final class PresentBenchmark {
    public static void main(String[] args) throws Exception {
        int frames = Integer.getInteger("bench.frames", 3000), width = Integer.getInteger("bench.width", 1600), height = Integer.getInteger("bench.height", 900);
        boolean fullscreen = Boolean.getBoolean("bench.fullscreen");
        double cpuMs = Double.parseDouble(System.getProperty("bench.cpuMs", "0"));
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        Path output = Path.of("build/present-benchmark").toAbsolutePath(); Files.createDirectories(output);
        glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long hidden = glfwCreateWindow(64, 64, "Hidden GL", 0, 0);
        glfwMakeContextCurrent(hidden); org.lwjgl.opengl.GL.createCapabilities();
        MainWindowBackend backend = new MainWindowBackend(output.resolve("report.txt").toString(), GlInterop.deviceUUID(), GlInterop::importImage, org.lwjgl.opengl.GL11::glFinish);
        glfwDefaultWindowHints(); glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); glfwWindowHint(GLFW_FLOATING, GLFW_TRUE); // Topmost: occluded windows are throttled by DWM.
        long monitor = glfwGetPrimaryMonitor(); var mode = glfwGetVideoMode(monitor);
        long window = fullscreen ? glfwCreateWindow(mode.width(), mode.height(), "VulkanForge present benchmark", monitor, 0)
                : glfwCreateWindow(width, height, "VulkanForge present benchmark", 0, 0);
        glfwShowWindow(window); glfwFocusWindow(window);
        long hwnd = glfwGetWin32Window(window); backend.attach(hwnd, GlInterop.windowInstance(hwnd));
        backend.worldCommands(GlInterop::importTerrain);
        backend.setVsync(Boolean.getBoolean("bench.vsync"));
        int[] w = {0}, h = {0}; glfwGetFramebufferSize(window, w, h);
        int framebuffer = glGenFramebuffers();
        int[] textures = backend.createRenderTarget(framebuffer, w[0], h[0]);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[0], 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, textures[1], 0);
        long[] before = null; long presentedNanos = 0, beginNanos = 0, skipped = 0;
        // Only presented frames are timed; a minimized/occluded window (0x0 surface) is restored and skipped.
        for (int frame = 0; backend.frames() < frames + 120; frame++) {
            if (before == null && backend.frames() >= 120) { before = backend.cpuStats(); presentedNanos = beginNanos = skipped = 0; } // Warm-up excluded.
            if (glfwGetWindowAttrib(window, GLFW_ICONIFIED) != 0) { glfwRestoreWindow(window); glfwFocusWindow(window); }
            glfwPollEvents();
            long begin = System.nanoTime();
            if (!backend.beginFrame(w[0], h[0])) { skipped++; continue; }
            long acquired = System.nanoTime(); beginNanos += acquired - begin;
            // Stand-in for Minecraft's own CPU frame (tick, chunk work, GL recording): -Dbench.cpuMs.
            for (long until = System.nanoTime() + (long) (cpuMs * 1e6); System.nanoTime() < until; ) Thread.onSpinWait();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer); glViewport(0, 0, w[0], h[0]);
            glClearColor((frame & 255) / 255f, .3f, .5f, 1); glClearDepth(1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            backend.present(framebuffer, w[0], h[0], w[0], h[0]);
            presentedNanos += System.nanoTime() - begin;
        }
        double seconds = presentedNanos / 1e9;
        long[] after = backend.cpuStats(); long counted = after[0] - before[0];
        if (skipped > 0) System.out.println("PRESENT_BENCH_SKIPPED=" + skipped + " (window minimized/occluded)");
        // fenceWait/acquire run on the acquire worker unless -Dvulkanforge.asyncAcquire=false; acquireWait is the render thread's share.
        String[] names = {"fenceWait", "acquire", "recordSubmit", "glHandoff", "queuePresent", "acquireWait"};
        StringBuilder line = new StringBuilder(String.format("PRESENT_BENCH %s %dx%d cpuMs=" + cpuMs + " frames=%d fps=%.1f frameMs=%.3f beginFrameMs=%.3f",
                fullscreen ? "fullscreen" : "windowed", w[0], h[0], counted, counted / seconds, seconds * 1000 / counted, beginNanos / 1e6 / counted));
        for (int i = 0; i < names.length; i++) line.append(String.format(" %sMs=%.3f", names[i], (after[i + 1] - before[i + 1]) / 1e6 / counted));
        System.out.println(line);
        backend.destroyRenderTarget(framebuffer);
        backend.close();
        glfwDestroyWindow(window); glfwDestroyWindow(hidden); glfwTerminate();
    }
}
