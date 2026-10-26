package dev.vulkanforge.lab;

import dev.vulkanforge.bridge.FrameProtocol;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real-GPU smoke: binary protocol, nonblank RGBA, animated frames, bounded and graceful shutdown. */
public final class BridgeSmokeTest {
    public static void main(String[] args) throws Exception {
        run(args[0], false);
        run(args[0], true);
        System.out.println("Bridge GPU smoke passed: pixels, animation, framing and graceful resource shutdown");
    }

    private static void run(String jar, boolean stopEarly) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String api = System.getProperty("vulkanforge.vulkanVersion", "1.4");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dvulkanforge.vulkanVersion=" + api, "-jar", jar, "--bridge"));
        if (!stopEarly) command.add("--frames=24");
        Path report = Path.of("build", "reports", stopEarly ? "bridge-stop-smoke.txt" : "bridge-pixel-smoke.txt");
        Files.createDirectories(report.getParent());
        Process child = new ProcessBuilder(command).redirectError(report.toFile()).start();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        watchdog.schedule(child::destroyForcibly, 25, TimeUnit.SECONDS);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(child.getInputStream()))) {
            require(input.readInt() == FrameProtocol.MAGIC, "Bad magic");
            require(input.readInt() == FrameProtocol.WIDTH && input.readInt() == FrameProtocol.HEIGHT, "Bad dimensions");
            long previous = 0;
            int frames = 0;
            byte[] first = null, last = null;
            while (true) {
                long sequence;
                try { sequence = input.readLong(); } catch (EOFException done) { break; }
                require(sequence == previous + 1, "Frame sequence gap");
                require(input.readLong() == 0, "Unexpected world epoch in triangle test");
                require(input.readInt() == FrameProtocol.BYTES, "Bad pixel byte count");
                byte[] rgba = new byte[FrameProtocol.BYTES];
                input.readFully(rgba);
                if (first == null) first = rgba;
                last = rgba;
                previous = sequence;
                frames++;
                if (stopEarly && frames == 3) child.getOutputStream().close();
            }
            require(child.waitFor(5, TimeUnit.SECONDS), "Child did not stop");
            String log = Files.readString(report);
            require(child.exitValue() == 0 && log.contains("STATUS=OK"), "Child failed: " + log);
            require(log.contains("VALIDATION_ERRORS=0"), "Vulkan validation errors: " + log);
            require(log.contains("INSTANCE_API=" + api + ".0"), "Bridge ignored the requested API version");
            require(stopEarly ? frames >= 3 && frames < 20 : frames == 24, "Wrong frame count: " + frames);
            require(first != null && last != null, "Missing frames");
            if (!stopEarly) require(!Arrays.equals(first, last), "Triangle did not animate");
            int colored = 0;
            for (int i = 0; i < first.length; i += 4) {
                require(Byte.toUnsignedInt(first[i + 3]) == 255, "Unexpected alpha");
                if (Byte.toUnsignedInt(first[i]) > 50 || Byte.toUnsignedInt(first[i + 1]) > 50 || Byte.toUnsignedInt(first[i + 2]) > 50) colored++;
            }
            require(colored > 1000 && colored < FrameProtocol.WIDTH * FrameProtocol.HEIGHT / 2, "Blank or invalid triangle: " + colored);
            System.out.printf("%s: frames=%d coloredPixels=%d%n", stopEarly ? "stop" : "pixels", frames, colored);
        } finally {
            watchdog.shutdownNow();
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
