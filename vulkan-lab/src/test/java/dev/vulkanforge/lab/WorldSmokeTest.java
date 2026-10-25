package dev.vulkanforge.lab;

import dev.vulkanforge.bridge.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Runs the actual world command protocol and checks depth, atlas, camera, removals and epochs on a GPU. */
public final class WorldSmokeTest {
    public static void main(String[] args) throws Exception {
        Path report = Path.of("build/reports/world-smoke.txt"); Files.createDirectories(report.getParent());
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-jar", args[0], "--bridge")
                .redirectError(report.toFile()).start();
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        watchdog.schedule(process::destroyForcibly, 30, TimeUnit.SECONDS);
        try (DataInputStream frames = new DataInputStream(new BufferedInputStream(process.getInputStream()));
             DataOutputStream commands = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()))) {
            require(frames.readInt() == FrameProtocol.MAGIC && frames.readInt() == FrameProtocol.WIDTH && frames.readInt() == FrameProtocol.HEIGHT, "Bad frame header");
            WorldProtocol.write(commands, new WorldProtocol.Reset(1));
            WorldProtocol.write(commands, atlas(1));
            WorldProtocol.write(commands, camera(1, 0));
            WorldProtocol.write(commands, mesh(1, 1, 0.2f, 0.25f)); // near red, submitted first
            WorldProtocol.write(commands, mesh(1, 2, 0.8f, 0.75f)); // far blue must fail depth
            commands.flush();
            await(frames, 1, WorldSmokeTest::red, "depth + red atlas sample");
            WorldProtocol.write(commands, new WorldProtocol.Remove(1, 1)); commands.flush();
            await(frames, 1, WorldSmokeTest::blue, "mesh removal reveals far blue");
            WorldProtocol.write(commands, camera(1, 1)); commands.flush();
            await(frames, 1, WorldSmokeTest::background, "camera translation");
            WorldProtocol.write(commands, new WorldProtocol.Remove(1, 2)); commands.flush();
            await(frames, 1, WorldSmokeTest::background, "empty world stays in world mode");
            WorldProtocol.write(commands, new WorldProtocol.Reset(2));
            WorldProtocol.write(commands, atlas(2));
            WorldProtocol.write(commands, camera(2, 0));
            WorldProtocol.write(commands, mesh(2, 3, 0.2f, 0.25f));
            WorldProtocol.write(commands, mesh(1, 4, 0.0f, 0.75f)); // stale epoch must be ignored
            commands.flush();
            await(frames, 2, WorldSmokeTest::red, "reset rejects stale world mesh");
            commands.close();
            while (true) {
                try { frames.readLong(); } catch (EOFException done) { break; }
                frames.readLong(); int bytes = frames.readInt(); frames.skipNBytes(bytes);
            }
            require(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0, "World process did not exit cleanly");
            String log = Files.readString(report);
            require(log.contains("STATUS=OK") && log.contains("VALIDATION_ERRORS=0"), log);
            System.out.println("World GPU smoke passed: textured geometry, depth, camera, mesh removal, epoch reset and shutdown");
        } finally { watchdog.shutdownNow(); if (process.isAlive()) process.destroyForcibly(); }
    }

    private static WorldProtocol.Atlas atlas(long epoch) { return new WorldProtocol.Atlas(epoch, 2, 1, new byte[] {(byte)255,0,0,(byte)255,0,0,(byte)255,(byte)255}); }
    private static WorldProtocol.Camera camera(long epoch, double x) {
        return new WorldProtocol.Camera(epoch, x, 0, 0, new float[] {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}, .1f, .1f, .1f);
    }
    private static WorldProtocol.Mesh mesh(long epoch, long key, float depth, float u) {
        float[][] positions = {{-.5f,-.5f},{.5f,-.5f},{.5f,.5f},{.5f,.5f},{-.5f,.5f},{-.5f,-.5f}};
        ByteBuffer buffer = ByteBuffer.allocate(positions.length * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] position : positions) buffer.putFloat(position[0]).putFloat(position[1]).putFloat(depth).putInt(-1).putFloat(u).putFloat(.5f);
        return new WorldProtocol.Mesh(epoch, key, 0, 0, 0, buffer.array());
    }
    private static void await(DataInputStream input, long epoch, Predicate<byte[]> predicate, String label) throws IOException {
        for (int i = 0; i < 80; i++) {
            input.readLong(); long frameEpoch = input.readLong(); int size = input.readInt();
            require(size == FrameProtocol.BYTES, "Bad frame size");
            byte[] rgba = new byte[size]; input.readFully(rgba);
            if (frameEpoch == epoch && predicate.test(rgba)) { System.out.println("PASS " + label); return; }
        }
        throw new AssertionError("No matching frame: " + label);
    }
    private static int component(byte[] bytes, int c) { return Byte.toUnsignedInt(bytes[((FrameProtocol.HEIGHT / 2) * FrameProtocol.WIDTH + FrameProtocol.WIDTH / 2) * 4 + c]); }
    private static boolean red(byte[] bytes) { return component(bytes, 0) > 220 && component(bytes, 2) < 20; }
    private static boolean blue(byte[] bytes) { return component(bytes, 2) > 220 && component(bytes, 0) < 20; }
    private static boolean background(byte[] bytes) { return Math.abs(component(bytes, 0) - 26) < 3 && Math.abs(component(bytes, 2) - 26) < 3; }
    private static void require(boolean success, String message) { if (!success) throw new AssertionError(message); }
}
