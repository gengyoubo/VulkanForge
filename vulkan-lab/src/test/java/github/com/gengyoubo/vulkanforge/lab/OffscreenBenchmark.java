package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.file.*;
import java.util.function.Consumer;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Same terrain path as the game (GPU culling, mesh arena, opaque + translucent passes, GL lease of the shared targets) with no
 * window surface, swapchain, acquire or present, over the fixed BenchmarkScene.
 * FPS counts only frames whose fence has completed: both slots are drained before the clock starts and before it stops.
 * -Dbench.frames=N, -Dbench.warmup=N, -Dbench.width/height (1920x1080), -Dbench.spin=deg/frame, plus BenchmarkScene's options.
 */
public final class OffscreenBenchmark {
    public static void main(String[] args) throws Exception {
        int frames = Integer.getInteger("bench.frames", 3000), warmup = Integer.getInteger("bench.warmup", 300);
        int width = Integer.getInteger("bench.width", 1920), height = Integer.getInteger("bench.height", 1080);
        float spin = Float.parseFloat(System.getProperty("bench.spin", "0"));
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        Path output = Path.of(System.getProperty("bench.output", "build/offscreen-benchmark")).toAbsolutePath(); Files.createDirectories(output);
        // Hidden GL context only for the shared-target lease (semaphore wait/signal); nothing is drawn or shown.
        glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long hidden = glfwCreateWindow(64, 64, "Hidden GL", 0, 0);
        glfwMakeContextCurrent(hidden); org.lwjgl.opengl.GL.createCapabilities();
        System.out.println("OFFSCREEN_GL_RENDERER=" + glGetString(GL_RENDERER));
        MainWindowBackend backend = new MainWindowBackend(output.resolve("report.txt").toString(), GlInterop.deviceUUID(), GlInterop::importImage, org.lwjgl.opengl.GL11::glFinish);
        Consumer<Object[]> local = backend.worldCommands(GlInterop::importTerrain);
        Consumer<WorldProtocol.Command> send = command -> local.accept(WorldProtocol.pack(command));
        String sceneFile = System.getProperty("bench.scene");
        if (sceneFile == null) System.out.println("OFFSCREEN_SCENE " + BenchmarkScene.load(send, width, height));
        else dev.vulkanforge.bench.Workload.load(Path.of(sceneFile), send);

        var probe = new BenchmarkScene.Probe(backend);
        float yaw = 0;
        for (int i = 0; i < warmup; i++) { if (spin != 0) send.accept(BenchmarkScene.camera(yaw += spin, width, height)); frame(backend, width, height); }
        probe.drain();
        if (Boolean.getBoolean("bench.audit")) BenchmarkDrawAudit.write(backend, output.resolve("workload.txt"));
        int runs = Integer.getInteger("bench.runs", 1);
        double minimumSeconds = Double.parseDouble(System.getProperty("bench.seconds", "0"));
        for (int run = 1; run <= runs; run++) {
        probe.drain();
        long submitted0 = backend.worldStats()[1], fence0 = probe.fenceWaitNanos(), glNanos0 = SharedPresentImage.groupNanos;
        probe.mark();
        long epochStart = System.currentTimeMillis();
        long cpuNanos = 0, start = System.nanoTime();
        int measuredFrames = 0;
        do {
            long begin = System.nanoTime();
            if (spin != 0) send.accept(BenchmarkScene.camera(yaw += spin, width, height));
            frame(backend, width, height);
            cpuNanos += System.nanoTime() - begin;
            measuredFrames++;
        } while (measuredFrames < frames || System.nanoTime() - start < minimumSeconds * 1e9);
        probe.drain();
        double seconds = (System.nanoTime() - start) / 1e9;
        long epochEnd = System.currentTimeMillis();
        long completed = backend.worldStats()[1] - submitted0;
        if (completed != measuredFrames) throw new AssertionError("Completed " + completed + " of " + measuredFrames + " frames (terrain not ready?)");
        double fenceMs = (probe.fenceWaitNanos() - fence0) / 1e6 / measuredFrames, glMs = (SharedPresentImage.groupNanos - glNanos0) / 1e6 / measuredFrames;
        double cpuMs = cpuNanos / 1e6 / measuredFrames - fenceMs;
        Object[] gpu = probe.gpu(); double gpuMs = (double) gpu[0];
        System.out.printf("OFFSCREEN_BENCH %dx%d frames=%d seconds=%.3f OFFSCREEN_FPS=%.1f frameMs=%.3f CPU_FRAME_MS=%.3f fenceWaitMs=%.3f glHandoffMs=%.3f GPU_RENDER_MS=%s gpuBusy=%s%s%n",
                width, height, measuredFrames, seconds, measuredFrames / seconds, seconds * 1000 / measuredFrames, cpuMs, fenceMs, glMs,
                Double.isNaN(gpuMs) ? "n/a" : String.format("%.3f", gpuMs), Double.isNaN(gpuMs) ? "n/a" : String.format("%.0f%%", gpuMs * measuredFrames / seconds / 10), gpu[1]);
        System.out.printf(java.util.Locale.ROOT, "BENCH_RESULT API=VK%s run=%d width=%d height=%d frames=%d seconds=%.6f FPS=%.3f GPU_MS=%.6f START_MS=%d END_MS=%d%n",
                System.getProperty("vulkanforge.vulkanVersion", "1.4"), run, width, height, measuredFrames, seconds, measuredFrames / seconds, gpuMs, epochStart, epochEnd);
        }
        backend.closeWorld();
        backend.close();
        glfwDestroyWindow(hidden); glfwTerminate();
    }

    private static void frame(MainWindowBackend backend, int width, int height) {
        if (backend.renderWorld(width, height) == 0) throw new AssertionError("World frame was not rendered");
        backend.finishWorldFrame(); // Ends the GL lease: glSignalSemaphoreEXT + glFlush, as the game does before presenting.
    }
}
