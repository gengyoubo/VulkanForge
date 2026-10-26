package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.file.*;
import java.util.Arrays;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * PresentBenchmark mode C (-Dbench.mode=gl): presentation through the GL context's own SwapBuffers (swap interval 0) instead of
 * a Vulkan swapchain, on the same window size. Default: glClear + SwapBuffers. -Dbench.world=true: the shared world renderer draws
 * BenchmarkScene into its exported image and GL blits that texture into the default framebuffer before SwapBuffers, i.e. Vulkan
 * rendering with GL presentation. FPS counts completed frames only (glFinish and both world slots drained at both ends).
 * -Dbench.timerResolutionUs=N (see TimerResolution), -Dbench.frames, -Dbench.width/height, -Dbench.fullscreen, -Dbench.world.
 */
final class GlSwapBenchmark {
    private static final int WARMUP = 120;

    static void run() throws Exception {
        int frames = Integer.getInteger("bench.frames", 3000), width = Integer.getInteger("bench.width", 1600), height = Integer.getInteger("bench.height", 900);
        boolean fullscreen = Boolean.getBoolean("bench.fullscreen"), world = Boolean.getBoolean("bench.world");
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
        TimerResolution.configure();
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        Path output = Path.of("build/gl-swap-benchmark").toAbsolutePath(); Files.createDirectories(output);
        glfwDefaultWindowHints(); glfwWindowHint(GLFW_FLOATING, GLFW_TRUE); glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE); // Topmost, as in mode A/B.
        long monitor = glfwGetPrimaryMonitor(); var video = glfwGetVideoMode(monitor);
        long window = fullscreen ? glfwCreateWindow(video.width(), video.height(), "VulkanForge GL swap benchmark", monitor, 0)
                : glfwCreateWindow(width, height, "VulkanForge GL swap benchmark", 0, 0);
        glfwMakeContextCurrent(window); org.lwjgl.opengl.GL.createCapabilities();
        glfwSwapInterval(0);
        glfwShowWindow(window); glfwFocusWindow(window);
        int[] w = {0}, h = {0}; glfwGetFramebufferSize(window, w, h);
        System.out.println("GL_SWAP_RENDERER=" + glGetString(GL_RENDERER) + " monitorHz=" + video.refreshRate());

        MainWindowBackend backend = null; BenchmarkScene.Probe probe = null; int read = 0;
        if (world) {
            backend = new MainWindowBackend(output.resolve("report.txt").toString(), GlInterop.deviceUUID(), GlInterop::importImage, org.lwjgl.opengl.GL11::glFinish);
            var local = backend.worldCommands(GlInterop::importTerrain);
            System.out.println("GL_SWAP_SCENE " + BenchmarkScene.load(command -> local.accept(WorldProtocol.pack(command)), w[0], h[0]));
            probe = new BenchmarkScene.Probe(backend);
            read = glGenFramebuffers();
        }
        long[] swap = new long[frames], interval = new long[frames];
        long start = 0, last = 0, cpuNanos = 0, worldFence0 = 0;
        for (int frame = 0; frame < WARMUP + frames; frame++) {
            int at = frame - WARMUP;
            if (at == 0) {
                if (probe != null) { probe.drain(); worldFence0 = probe.fenceWaitNanos(); probe.mark(); }
                glFinish(); start = System.nanoTime();
            }
            if (glfwGetWindowAttrib(window, GLFW_ICONIFIED) != 0) { glfwRestoreWindow(window); glfwFocusWindow(window); }
            glfwPollEvents();
            long t0 = System.nanoTime();
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, w[0], h[0]);
            if (world) {
                int texture = backend.renderWorld(w[0], h[0]);
                if (texture == 0) throw new AssertionError("World frame was not rendered");
                glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
                glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
                glBlitFramebuffer(0, 0, w[0], h[0], 0, 0, w[0], h[0], GL_COLOR_BUFFER_BIT, GL_NEAREST);
                backend.finishWorldFrame(); // GL signals the shared image back to Vulkan after the blit.
            } else {
                glClearColor((frame & 255) / 255f, .3f, .5f, 1); glClear(GL_COLOR_BUFFER_BIT);
            }
            long t1 = System.nanoTime();
            glfwSwapBuffers(window);
            long t2 = System.nanoTime();
            if (at >= 0) { cpuNanos += t1 - t0; swap[at] = t2 - t1; if (at > 0) interval[at] = t2 - last; }
            last = t2;
        }
        glFinish();
        if (probe != null) probe.drain();
        double seconds = (System.nanoTime() - start) / 1e9;
        StringBuilder line = new StringBuilder(String.format("GL_SWAP_BENCH %s %s %dx%d frames=%d fps=%.1f frameMs=%.3f cpuFrameMs=%.3f %s %s",
                fullscreen ? "fullscreen" : "windowed", world ? "vulkanWorldBlit" : "clear", w[0], h[0], frames, frames / seconds, seconds * 1000 / frames,
                cpuNanos / 1e6 / frames, stats("swapBuffers", swap, frames), stats("swapInterval", Arrays.copyOfRange(interval, 1, frames), frames - 1)));
        if (probe != null) {
            Object[] gpu = probe.gpu(); double gpuMs = (double) gpu[0];
            line.append(String.format(" worldFenceWaitMs=%.3f", (probe.fenceWaitNanos() - worldFence0) / 1e6 / frames));
            if (!Double.isNaN(gpuMs)) line.append(String.format(" vkGpuMs=%.3f vkGpuBusy=%.0f%%", gpuMs, gpuMs * frames / seconds / 10)).append(gpu[1]);
        }
        System.out.println(line);
        if (backend != null) { glDeleteFramebuffers(read); backend.closeWorld(); backend.close(); }
        glfwDestroyWindow(window); glfwTerminate();
    }

    /** name=mean/p50/p99/max in milliseconds. */
    private static String stats(String name, long[] nanos, int count) {
        long[] sorted = Arrays.copyOf(nanos, count); Arrays.sort(sorted);
        return String.format("%s=%.3f/%.3f/%.3f/%.3f", name, Arrays.stream(sorted).average().orElse(0) / 1e6, sorted[count / 2] / 1e6,
                sorted[Math.min(count - 1, (int) (count * .99))] / 1e6, sorted[count - 1] / 1e6);
    }
}
