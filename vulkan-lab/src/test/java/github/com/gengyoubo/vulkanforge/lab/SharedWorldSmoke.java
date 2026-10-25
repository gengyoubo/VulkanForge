package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import java.nio.*;
import java.util.function.Consumer;
import org.lwjgl.system.MemoryUtil;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import static org.lwjgl.opengl.GL11.*;

/** Real GPU assertions on all 45 section outputs and both sides of the external semaphore cycle. */
final class SharedWorldSmoke {
    static void run(MainWindowBackend backend) {
        Consumer<Object[]> local = backend.worldCommands(GlInterop::importTerrain);
        Consumer<WorldProtocol.Command> send = command -> local.accept(WorldProtocol.pack(command));
        send.accept(new WorldProtocol.Reset(1));
        send.accept(new WorldProtocol.Atlas(1, 1, 1, new byte[]{(byte)255, 0, 0, (byte)255}));
        byte[] light = new byte[16 * 16 * 4]; java.util.Arrays.fill(light, (byte)255);
        send.accept(new WorldProtocol.TexturePatch(1, WorldProtocol.LIGHT_MAP, 0, 0, 16, 16, light));
        send.accept(camera(1, 0));
        for (int y = 0; y < 5; y++) for (int x = 0; x < 9; x++) send.accept(mesh(1, y * 9 + x, x, y));
        for (int frame = 0; frame < 12; frame++) {
            int red = 20 + frame * 17, green = 255 - red;
            send.accept(new WorldProtocol.TexturePatch(1, WorldProtocol.BLOCK_ATLAS, 0, 0, 1, 1, new byte[]{(byte)red, (byte)green, 0, (byte)255}));
            int width = frame < 6 ? 180 : 270, height = frame < 6 ? 100 : 150;
            ByteBuffer pixels = read(backend.renderWorld(width, height), width, height);
            try {
                for (int y = 0; y < 5; y++) for (int x = 0; x < 9; x++) {
                    int offset = ((int)((.9f - y * .2f) * height) * width + (int)((.1f + x * .1f) * width)) * 4;
                    // Center positions are x=-.8..+.8, y=-.8..+.8. Vulkan viewport flips y in world.vert.
                    require(Math.abs(Byte.toUnsignedInt(pixels.get(offset)) - red) <= 1 && Math.abs(Byte.toUnsignedInt(pixels.get(offset + 1)) - green) <= 1
                            && Byte.toUnsignedInt(pixels.get(offset + 3)) == 255, "Section output/stale texture: frame=" + frame + " section=" + (y * 9 + x));
                }
            } finally { MemoryUtil.memFree(pixels); }
            if (frame == 0 || frame == 11) {
                int depth = (int)backend.worldStats()[9];
                java.nio.FloatBuffer depths = MemoryUtil.memAllocFloat(width * height);
                int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
                try {
                    glBindTexture(GL_TEXTURE_2D, depth); glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, depths);
                    require(Math.abs(depths.get(height / 2 * width + width / 2) - .6f) < .00001f, "Shared depth was not stored/exported");
                } finally { glBindTexture(GL_TEXTURE_2D, bound); MemoryUtil.memFree(depths); }
            }
            backend.finishWorldFrame();
        }
        long[] stats = backend.worldStats();
        require(stats[2] == 45 && stats[3] == 45 && stats[7] == 45 * 12, "Not all 45 sections were drawn");
        send.accept(camera(1, 3));
        requireAlpha(backend.renderWorld(270, 150), 270, 150, 0);
        backend.finishWorldFrame();
        send.accept(camera(1, 0));
        send.accept(new WorldProtocol.Remove(1, 22));
        requireAlpha(backend.renderWorld(270, 150), 270, 150, 0);
        backend.finishWorldFrame();
        send.accept(new WorldProtocol.Reset(2));
        require(backend.worldStats()[10] > 0 && backend.worldStats()[12] >= 45, "GPU compute culling did not reject off-screen sections");
        send.accept(mesh(1, 99, 0, 0));
        require(backend.worldStats()[2] == 0 && backend.renderWorld(270, 150) == 0, "Stale mesh survived reset");
        translucent(backend, send);
        backend.closeWorld();
        requireAlpha(backend.renderWorld(180, 100), 180, 100, 255); // Reopen as menu preview, close with a GL read lease open.
        backend.closeWorld();
        require(glGetError() == GL_NO_ERROR, "GL error in shared world path");
        System.out.println("SHARED_WORLD_SMOKE=OK sections=45 draws=" + stats[7] + " (shared color/depth, compute culling, both slots, fresh pixels, resize, camera, removal, reset and reopen)");
    }
    /**
     * Opaque grey at z=.2 (depth .6), translucent blue at z=0 (depth .5) and red at z=-.2 (depth .4), all alpha 128.
     * The camera sits at z=-5 (a matching z translation keeps clip positions), so red is nearer by distance and must be drawn last.
     */
    private static void translucent(MainWindowBackend backend, Consumer<WorldProtocol.Command> send) {
        send.accept(new WorldProtocol.Reset(3));
        send.accept(new WorldProtocol.Atlas(3, 1, 1, new byte[]{(byte)255, (byte)255, (byte)255, (byte)255}));
        byte[] light = new byte[16 * 16 * 4]; java.util.Arrays.fill(light, (byte)255);
        send.accept(new WorldProtocol.TexturePatch(3, WorldProtocol.LIGHT_MAP, 0, 0, 16, 16, light));
        send.accept(new WorldProtocol.Camera(3, 0, 0, -5, new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,-5,1}, 0, 0, 0));
        ByteBuffer opaque = quad(ByteBuffer.allocate(6 * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN), .2f, 0xff808080);
        ByteBuffer glass = ByteBuffer.allocate(12 * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        quad(glass, -.2f, 0x800000ff); quad(glass, 0, 0x80ff0000); // red first in the mesh, so only sorting puts it on top
        send.accept(new WorldProtocol.Mesh(3, 7, 0, 0, 0, opaque.array(), glass.array()));
        int width = 64, height = 64;
        require(backend.renderWorld(width, height) > 0, "Translucent scene did not render");
        long[] stats = backend.worldStats();
        require(stats.length > 17 && stats[15] == 1, "Translucent section was not tracked");
        ByteBuffer colors = read((int)stats[13], width, height), base = read(backend.renderWorld(width, height), width, height);
        try {
            int center = (height / 2 * width + width / 2) * 4;
            int red = Byte.toUnsignedInt(colors.get(center)), blue = Byte.toUnsignedInt(colors.get(center + 2)), alpha = Byte.toUnsignedInt(colors.get(center + 3));
            // Back to front, premultiplied: blue .5 then red .5 over it -> r~128, b~64, a~192. Wrong order swaps r and b.
            require(Math.abs(red - 128) <= 3 && Math.abs(blue - 64) <= 3 && Math.abs(alpha - 192) <= 3,
                    "Translucent blend/sort order wrong: r=" + red + " b=" + blue + " a=" + alpha);
            require(Byte.toUnsignedInt(base.get(center)) == 128 && Byte.toUnsignedInt(base.get(center + 3)) == 255, "Translucent pass changed the opaque target");
            require(Byte.toUnsignedInt(colors.get(3)) == 0, "Translucent target is not cleared to zero outside the quads");
        } finally { MemoryUtil.memFree(colors); MemoryUtil.memFree(base); }
        stats = backend.worldStats();
        require(Math.abs(depth((int)stats[14], width, height) - .4f) < .00001f, "Translucent depth is not the nearest translucent surface");
        require(Math.abs(depth((int)stats[9], width, height) - .6f) < .00001f, "Opaque depth was modified by the translucent pass");
        backend.finishWorldFrame();
        long sorts = backend.worldStats()[17];
        // Move more than one block (with a compensating matrix) in both slots: each slot must re-sort its own index buffer.
        send.accept(new WorldProtocol.Camera(3, 0, 0, -7, new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,-7,1}, 0, 0, 0));
        for (int i = 0; i < 2; i++) { require(backend.renderWorld(width, height) > 0, "Translucent re-render failed"); backend.finishWorldFrame(); }
        require(backend.worldStats()[17] >= sorts + 2, "Camera movement did not re-sort translucent quads");
        System.out.println("TRANSLUCENT_SMOKE=OK (sorted premultiplied blend, separate depth, opaque untouched, per-slot re-sort)");
    }
    private static ByteBuffer quad(ByteBuffer vertices, float z, int abgr) {
        float[][] corners = {{-.5f,-.5f},{.5f,-.5f},{.5f,.5f},{.5f,.5f},{-.5f,.5f},{-.5f,-.5f}};
        for (float[] c : corners) vertices.putFloat(c[0]).putFloat(c[1]).putFloat(z).putInt(abgr)
                .putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240);
        return vertices;
    }
    private static float depth(int texture, int width, int height) {
        java.nio.FloatBuffer depths = MemoryUtil.memAllocFloat(width * height);
        int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, texture); glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, depths);
            return depths.get(height / 2 * width + width / 2);
        } finally { glBindTexture(GL_TEXTURE_2D, bound); MemoryUtil.memFree(depths); }
    }
    private static void requireAlpha(int texture, int width, int height, int alpha) {
        ByteBuffer pixels = read(texture, width, height);
        try { require(Byte.toUnsignedInt(pixels.get((height / 2 * width + width / 2) * 4 + 3)) == alpha, "Unexpected shared alpha"); }
        finally { MemoryUtil.memFree(pixels); }
    }
    private static ByteBuffer read(int texture, int width, int height) {
        require(texture > 0, "Missing shared world texture");
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        try { glBindTexture(GL_TEXTURE_2D, texture); glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels); }
        finally { glBindTexture(GL_TEXTURE_2D, previous); }
        return pixels;
    }
    private static WorldProtocol.Camera camera(long epoch, double x) {
        return new WorldProtocol.Camera(epoch, x, 0, 0, new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}, .1f, .1f, .1f);
    }
    private static WorldProtocol.Mesh mesh(long epoch, long key, int column, int row) {
        float cx = -.8f + column * .2f, cy = -.8f + row * .4f;
        float[][] corners = {{-.07f,-.12f},{.07f,-.12f},{.07f,.12f},{.07f,.12f},{-.07f,.12f},{-.07f,-.12f}};
        ByteBuffer vertices = ByteBuffer.allocate(corners.length * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] c : corners) vertices.putFloat(cx + c[0]).putFloat(cy + c[1]).putFloat(.2f).putInt(-1)
                .putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240);
        return new WorldProtocol.Mesh(epoch, key, 0, 0, 0, vertices.array());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
