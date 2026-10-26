package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.function.Consumer;

/**
 * Fixed procedural heightmap terrain with water, shared by OffscreenBenchmark and PresentBenchmark (-Dbench.world=true) so both
 * render identical content. -Dbench.radius=chunks (12), -Dbench.water=false.
 */
final class BenchmarkScene {
    private static final int SEA = 62;
    private static final boolean WATER = !"false".equals(System.getProperty("bench.water"));
    static final int RADIUS = Integer.getInteger("bench.radius", 12);

    private BenchmarkScene() { }

    /** Sends the whole scene for epoch 1 and returns a one-line summary. */
    static String load(Consumer<WorldProtocol.Command> send, int width, int height) {
        long loadStart = System.nanoTime();
        send.accept(new WorldProtocol.Reset(1));
        send.accept(new WorldProtocol.Atlas(1, 32, 32, atlas()));
        byte[] light = new byte[16 * 16 * 4]; java.util.Arrays.fill(light, (byte) 255);
        send.accept(new WorldProtocol.TexturePatch(1, WorldProtocol.LIGHT_MAP, 0, 0, 16, 16, light));
        send.accept(camera(0, width, height));
        long vertices = 0, translucentVertices = 0; int sections = 0;
        for (int cx = -RADIUS; cx <= RADIUS; cx++) for (int cz = -RADIUS; cz <= RADIUS; cz++) for (int sy = 2; sy <= 5; sy++) {
            byte[][] mesh = section(cx, sy, cz);
            if (mesh[0].length == 0 && mesh[1].length == 0) continue;
            send.accept(new WorldProtocol.Mesh(1, ((long) (cx + 1024) << 32) | ((long) (cz + 1024) << 8) | sy, cx * 16, sy * 16, cz * 16, mesh[0], mesh[1]));
            vertices += mesh[0].length / WorldProtocol.STRIDE; translucentVertices += mesh[1].length / WorldProtocol.STRIDE; sections++;
        }
        return String.format("sections=%d opaqueVertices=%d translucentVertices=%d radius=%d loadMs=%.0f",
                sections, vertices, translucentVertices, RADIUS, (System.nanoTime() - loadStart) / 1e6);
    }

    /** Fixed eye above the terrain center, 25 degrees down; yaw in degrees. GL-style camera-relative view-projection (column major). */
    static WorldProtocol.Camera camera(float yawDegrees, int width, int height) {
        double yaw = Math.toRadians(yawDegrees), pitch = Math.toRadians(-25);
        float[] f = {(float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch))};
        float[] s = normalize(cross(f, new float[]{0, 1, 0})), u = cross(s, f);
        float[] view = {s[0], u[0], -f[0], 0, s[1], u[1], -f[1], 0, s[2], u[2], -f[2], 0, 0, 0, 0, 1};
        float near = .05f, far = (RADIUS + 1) * 16 * 1.5f, t = (float) (1 / Math.tan(Math.toRadians(70) / 2)), a = (float) width / height;
        float[] projection = {t / a, 0, 0, 0, 0, t, 0, 0, 0, 0, (far + near) / (near - far), -1, 0, 0, 2 * far * near / (near - far), 0};
        float[] matrix = new float[16];
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) for (int k = 0; k < 4; k++) matrix[c * 4 + r] += projection[k * 4 + r] * view[c * 4 + k];
        return new WorldProtocol.Camera(1, 8, 100, 8, matrix, .5f, .7f, 1f);
    }
    private static float[] cross(float[] a, float[] b) { return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static float[] normalize(float[] v) { float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return new float[]{v[0] / l, v[1] / l, v[2] / l}; }

    private static int surface(int x, int z) {
        return 64 + (int) Math.round(9 * Math.sin(x * .071) * Math.cos(z * .053) + 5 * Math.sin((x + z) * .023) + 2 * Math.cos(x * .31 - z * .17));
    }

    /**
     * Per-block faces like vanilla's section mesher (no greedy merging): grass tops, stone sides down to the lower neighbour,
     * and a translucent water surface over columns below sea level. Returns {opaque, translucent}.
     */
    private static byte[][] section(int cx, int sy, int cz) {
        ByteArrayOutputStream opaque = new ByteArrayOutputStream(), water = new ByteArrayOutputStream();
        int base = sy * 16;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int wx = cx * 16 + x, wz = cz * 16 + z, h = surface(wx, wz);
            if (h - 1 >= base && h - 1 < base + 16) quad(opaque, 0, x, h - base, z, 0xff60c060, 0);
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int side = 0; side < 4; side++) {
                int below = surface(wx + sides[side][0], wz + sides[side][1]);
                for (int y = Math.max(below, base); y < Math.min(h, base + 16); y++)
                    quad(opaque, side + 1, x, y - base, z, side < 2 ? 0xffcccccc : 0xff999999, y == h - 1 ? 1 : 2);
            }
            if (WATER && h < SEA && SEA - 1 >= base && SEA - 1 < base + 16) quad(water, 0, x, SEA - base, z, 0x99ffffff, 3);
        }
        return new byte[][]{opaque.toByteArray(), water.toByteArray()};
    }

    /** face 0 = top at y; 1..4 = +x, -x, +z, -z sides of the block at (x, y, z). Two triangles v0 v1 v2 / v2 v3 v0, CCW from outside. */
    private static void quad(ByteArrayOutputStream out, int face, int x, int y, int z, int abgr, int tile) {
        float[][] c = switch (face) {
            case 0 -> new float[][]{{x, y, z}, {x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y, z}};
            case 1 -> new float[][]{{x + 1, y, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}};
            case 2 -> new float[][]{{x, y, z + 1}, {x, y + 1, z + 1}, {x, y + 1, z}, {x, y, z}};
            case 3 -> new float[][]{{x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}, {x, y, z + 1}};
            default -> new float[][]{{x, y, z}, {x, y + 1, z}, {x + 1, y + 1, z}, {x + 1, y, z}};
        };
        float u0 = (tile % 2) * .5f, v0 = (tile / 2) * .5f;
        float[][] uv = {{u0, v0 + .5f}, {u0, v0}, {u0 + .5f, v0}, {u0 + .5f, v0 + .5f}};
        ByteBuffer bytes = ByteBuffer.allocate(6 * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : new int[]{0, 1, 2, 2, 3, 0})
            bytes.putFloat(c[i][0]).putFloat(c[i][1]).putFloat(c[i][2]).putInt(abgr).putFloat(uv[i][0]).putFloat(uv[i][1]).putShort((short) 240).putShort((short) 240);
        out.write(bytes.array(), 0, bytes.capacity());
    }

    /** 32x32 atlas, four noisy 16x16 tiles: grass, grass side, stone, water. */
    private static byte[] atlas() {
        int[][] tones = {{90, 160, 60}, {120, 100, 70}, {125, 125, 125}, {40, 80, 200}};
        byte[] rgba = new byte[32 * 32 * 4]; java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int[] tone = tones[(y / 16) * 2 + x / 16]; int noise = random.nextInt(31) - 15, at = (y * 32 + x) * 4;
            for (int k = 0; k < 3; k++) rgba[at + k] = (byte) Math.max(0, Math.min(255, tone[k] + noise));
            rgba[at + 3] = (byte) 255;
        }
        return rgba;
    }

    /**
     * Reads SharedWorldRenderer's slot fences and fence-wait counter, and the GPU timestamp accumulators of the world and
     * main-window present passes; nothing here alters the frame path. Without a world renderer only the present timers are read.
     */
    static final class Probe {
        private static final String[][] LABELS = {{"TERRAIN", "TERRAIN_RELEASE", "TRIANGLE", "CULLING", "TRANSLUCENT"}, {"COPY", "PRESENT_PREPARE", "PRESENT_SCENE"}};
        private final Object world;
        private final Object[] timers;
        private final java.lang.reflect.Method waitAll;
        private final java.lang.reflect.Field fenceWait, samples, total;
        private final AsyncUploads uploads;
        private final java.lang.reflect.Field pendingBytes;
        private final long[][] samples0 = new long[2][]; private final double[][] total0 = new double[2][];
        Probe(MainWindowBackend backend) throws ReflectiveOperationException {
            var contextField=MainWindowBackend.class.getDeclaredField("context");contextField.setAccessible(true);
            var context=(VulkanContext)contextField.get(backend);uploads=context.uploads;
            pendingBytes=AsyncUploads.class.getDeclaredField("pendingBytes");pendingBytes.setAccessible(true);
            var worldField = MainWindowBackend.class.getDeclaredField("world"); worldField.setAccessible(true);
            world = worldField.get(backend);
            waitAll = SharedWorldRenderer.class.getDeclaredMethod("waitAll"); waitAll.setAccessible(true);
            fenceWait = SharedWorldRenderer.class.getDeclaredField("fenceWaitNanos"); fenceWait.setAccessible(true);
            var worldTimers = SharedWorldRenderer.class.getDeclaredField("timers"); worldTimers.setAccessible(true);
            var presentTimers = MainWindowBackend.class.getDeclaredField("timers"); presentTimers.setAccessible(true);
            timers = new Object[]{world == null ? null : worldTimers.get(world), presentTimers.get(backend)};
            samples = GpuTimers.class.getDeclaredField("samples"); samples.setAccessible(true);
            total = GpuTimers.class.getDeclaredField("total"); total.setAccessible(true);
        }
        /** Waits both world frame slots' fences, so every submitted world frame has completed on the GPU. */
        void drain() throws ReflectiveOperationException { if (world != null) waitAll.invoke(world); }
        long fenceWaitNanos() throws ReflectiveOperationException { return world == null ? 0 : fenceWait.getLong(world); }
        long pendingUploadBytes() throws IllegalAccessException {return pendingBytes.getLong(uploads);}
        /** Outside timed intervals only: refresh completed upload accounting without waiting on the GPU. */
        void reportUploads(String phase) throws IllegalAccessException {
            uploads.poll();
            System.out.println("TAIL_"+phase+"_UPLOAD_CHECKPOINT pendingBytes="+pendingUploadBytes()+" stats="+java.util.Arrays.toString(uploads.stats()));
        }
        /** Starts the GPU timestamp window. */
        void mark() throws ReflectiveOperationException {
            for (int i = 0; i < timers.length; i++) if (timers[i] != null) { samples0[i] = ((long[]) samples.get(timers[i])).clone(); total0[i] = ((double[]) total.get(timers[i])).clone(); }
        }
        /** {GPU ms per frame summed over every timed pass, or NaN without timestamps; per-pass " gpuXMs=" text} since mark(). */
        Object[] gpu() throws ReflectiveOperationException {
            StringBuilder text = new StringBuilder(); double sum = 0; boolean timed = false;
            for (int i = 0; i < timers.length; i++) {
                if (timers[i] == null) continue;
                long[] samples1 = (long[]) samples.get(timers[i]); double[] total1 = (double[]) total.get(timers[i]);
                for (int metric = 0; metric < LABELS[i].length; metric++) {
                    long n = samples1[metric] - samples0[i][metric];
                    if (n == 0) continue;
                    double mean = (total1[metric] - total0[i][metric]) / n; sum += mean; timed = true;
                    text.append(String.format(" gpu%sMs=%.3f", LABELS[i][metric], mean));
                }
            }
            return new Object[]{timed ? sum : Double.NaN, text.toString()};
        }
    }
}
