package github.com.gengyoubo.vulkanforge.client;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import dev.vulkanforge.bridge.FrameProtocol;
import dev.vulkanforge.bridge.WorldProtocol;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Latest-frame mailbox. No pipe reads, process waits or Vulkan calls on the game render thread. */
final class OffscreenBridge implements AutoCloseable {
    record Frame(long sequence, long epoch, byte[] rgba) { }
    enum Status { STARTING, RUNNING, FAILED, STOPPED }
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VulkanForge-bridge-io");
        t.setDaemon(true);
        return t;
    });
    private static final Set<OffscreenBridge> ACTIVE = ConcurrentHashMap.newKeySet();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> ACTIVE.forEach(OffscreenBridge::close), "VulkanForge-bridge-shutdown"));
    }
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final ArrayBlockingQueue<byte[]> available = new ArrayBlockingQueue<>(3);
    private final ArrayBlockingQueue<WorldProtocol.Command> commands = new ArrayBlockingQueue<>(64);
    private final AtomicReference<WorldProtocol.Camera> camera = new AtomicReference<>();
    private final long started = System.nanoTime();
    private volatile long lastFrame = started;
    private volatile Process child;
    private volatile Status status = Status.STARTING;

    OffscreenBridge() { ACTIVE.add(this); IO.execute(this::readFrames); }

    Status status() {
        if (!closed.get() && status != Status.FAILED && System.nanoTime() - lastFrame > TimeUnit.SECONDS.toNanos(15)) {
            fail(new IOException("No Vulkan frame received for 15 seconds"));
        }
        return status;
    }

    Frame take() { return latest.getAndSet(null); }
    void recycle(Frame frame) { if (frame != null) available.offer(frame.rgba()); }
    boolean send(WorldProtocol.Command command) { return !closed.get() && commands.offer(command); }
    void camera(WorldProtocol.Camera value) { camera.set(value); }
    void reset(long epoch) { commands.clear(); camera.set(null); send(new WorldProtocol.Reset(epoch)); }

    private void writeCommands(Process process) {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream(), 65536))) {
            while (!closed.get() && process.isAlive()) {
                WorldProtocol.Command command = commands.poll(20, TimeUnit.MILLISECONDS);
                if (command != null) WorldProtocol.write(output, command);
                WorldProtocol.Camera view = camera.getAndSet(null);
                if (view != null) WorldProtocol.write(output, view);
                output.flush();
            }
        } catch (Exception e) { if (!closed.get()) fail(e); }
    }

    private void readFrames() {
        try {
            Path report = FMLPaths.GAMEDIR.get().resolve("logs/vulkanforge/bridge-report.txt");
            Files.createDirectories(report.getParent());
            ProcessBuilder builder = LabLauncher.process("--bridge").redirectError(report.toFile());
            if (closed.get()) return;
            child = builder.start();
            if (closed.get()) { requestStop(child); return; }
            Process process = child;
            IO.execute(() -> writeCommands(process));
            VulkanForge.LOGGER.info("Vulkan in-window bridge started, pid={}, report={}", child.pid(), report);
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(child.getInputStream(), 65536))) {
                if (input.readInt() != FrameProtocol.MAGIC || input.readInt() != FrameProtocol.WIDTH || input.readInt() != FrameProtocol.HEIGHT) {
                    throw new IOException("Unexpected Vulkan frame protocol or dimensions");
                }
                for (int i = 0; i < 3; i++) available.add(new byte[FrameProtocol.BYTES]);
                long previous = 0;
                while (true) {
                    long sequence = input.readLong();
                    long epoch = input.readLong();
                    int size = input.readInt();
                    if (sequence <= previous || size != FrameProtocol.BYTES) throw new IOException("Invalid Vulkan frame header");
                    byte[] rgba = available.poll();
                    if (rgba == null) throw new IOException("Vulkan frame mailbox exhausted");
                    input.readFully(rgba);
                    previous = sequence;
                    synchronized (this) {
                        if (closed.get()) { available.offer(rgba); continue; }
                        Frame old = latest.getAndSet(new Frame(sequence, epoch, rgba));
                        recycle(old);
                        lastFrame = System.nanoTime();
                        status = Status.RUNNING;
                    }
                }
            }
        } catch (Exception e) {
            if (!closed.get()) fail(e);
        } finally {
            Process process = child;
            if (process != null) {
                requestStop(process);
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                    VulkanForge.LOGGER.info("Vulkan in-window bridge stopped, pid={}", process.pid());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            }
            ACTIVE.remove(this);
        }
    }

    private synchronized void fail(Exception error) {
        status = Status.FAILED;
        VulkanForge.LOGGER.error("Vulkan in-window bridge failed; see logs/vulkanforge/bridge-report.txt", error);
        stop();
    }

    private static void requestStop(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private synchronized void stop() {
        if (!closed.compareAndSet(false, true)) return;
        recycle(latest.getAndSet(null));
        Process process = child;
        if (process != null) {
            IO.execute(() -> requestStop(process));
            IO.execute(() -> {
                try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            });
        }
    }

    @Override public void close() { stop(); status = Status.STOPPED; }
}
