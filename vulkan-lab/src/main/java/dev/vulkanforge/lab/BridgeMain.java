package dev.vulkanforge.lab;

import dev.vulkanforge.bridge.FrameProtocol;
import dev.vulkanforge.bridge.WorldProtocol;
import org.lwjgl.vulkan.VK;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.LockSupport;

/** Binary frames on stdout; diagnostics on stderr. Closing stdin requests a graceful shutdown. */
final class BridgeMain {
    private BridgeMain() { }

    static int execute(String[] args) {
        System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        VulkanContext context = new VulkanContext();
        AtomicBoolean stop = new AtomicBoolean();
        ArrayBlockingQueue<WorldProtocol.Command> commands = new ArrayBlockingQueue<>(64);
        Thread control = new Thread(() -> {
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(System.in, 65536))) {
                while (true) commands.put(WorldProtocol.read(input));
            } catch (EOFException ignored) { }
            catch (Exception error) { System.err.println("World input failed: " + error); }
            finally { stop.set(true); }
        }, "VulkanForge-bridge-control");
        control.setDaemon(true);
        try {
            int maxFrames = 0;
            boolean requireValidation = false;
            for (String arg : args) {
                if (arg.equals("--bridge")) continue;
                if (arg.equals("--require-validation")) requireValidation = true;
                else if (arg.startsWith("--frames=")) {
                    maxFrames = Integer.parseInt(arg.substring(9));
                    if (maxFrames < 1) throw new IllegalArgumentException("--frames must be positive");
                } else throw new IllegalArgumentException("Unknown bridge argument: " + arg);
            }
            int loader = VK.getInstanceVersionSupported();
            System.out.println("BRIDGE_MODE=OFFSCREEN_READBACK");
            System.out.println("LOADER_API=" + VulkanContext.version(loader));
            if (loader < org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4) throw new IllegalStateException("Vulkan 1.4 loader required");
            context.initialize(0, loader, requireValidation);
            control.start();
            long frames = 0;
            try (WorldScene world = new WorldScene(context);
                 OffscreenRenderer renderer = new OffscreenRenderer(context, FrameProtocol.WIDTH, FrameProtocol.HEIGHT);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 65536))) {
                renderer.initialize();
                world.initialize();
                output.writeInt(FrameProtocol.MAGIC);
                output.writeInt(FrameProtocol.WIDTH);
                output.writeInt(FrameProtocol.HEIGHT);
                output.flush();
                byte[] rgba = new byte[FrameProtocol.BYTES];
                long start = System.nanoTime(), next = start;
                while (!stop.get() && (maxFrames == 0 || frames < maxFrames)) {
                    for (int i = 0; i < 8; i++) {
                        WorldProtocol.Command update = commands.poll();
                        if (update == null) break;
                        world.apply(update);
                    }
                    renderer.render((System.nanoTime() - start) / 1_000_000_000.0f * 0.7f, rgba, world);
                    output.writeLong(++frames);
                    output.writeLong(world.renderedEpoch());
                    output.writeInt(rgba.length);
                    output.write(rgba);
                    output.flush();
                    next += 1_000_000_000L / FrameProtocol.FPS;
                    long delay = next - System.nanoTime();
                    if (delay > 0) LockSupport.parkNanos(delay);
                    else next = System.nanoTime();
                }
            }
            context.close();
            System.out.println("OFFSCREEN_FRAMES=" + frames);
            System.out.println("VALIDATION_ERRORS=" + context.validationErrors());
            if (context.validationErrors() != 0) throw new IllegalStateException("Vulkan validation errors detected");
            System.out.println("STATUS=OK");
            return 0;
        } catch (Throwable e) {
            System.err.println("STATUS=FAILED");
            e.printStackTrace(System.err);
            return 1;
        } finally { context.close(); }
    }
}
