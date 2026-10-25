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
        if (backend.gpuDrivenEnabled()) {
            long[] optimized = backend.optimizationStats();
            require(optimized[0] == 12 && optimized[2] == 45, "GPU driven batches/arena uploads not exercised: " + java.util.Arrays.toString(optimized));
            System.out.println("GPU_DRIVEN_SMOKE=OK sections=45 indirectBatches=" + optimized[0] + " arenaUploads=" + optimized[2]);
            arenaGrowth(backend, send);
        }
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
        entities(backend);
        sky(backend);
        gui(backend);
        backend.closeWorld();
        requireAlpha(backend.renderWorld(180, 100), 180, 100, 255); // Reopen as menu preview, close with a GL read lease open.
        backend.closeWorld();
        require(glGetError() == GL_NO_ERROR, "GL error in shared world path");
        System.out.println("SHARED_WORLD_SMOKE=OK sections=45 draws=" + stats[7] + " (shared color/depth, compute culling, both slots, fresh pixels, resize, camera, removal, reset and reopen)");
    }

    private static void arenaGrowth(MainWindowBackend backend, Consumer<WorldProtocol.Command> send) {
        // Force two arena resizes while both rendering slots and prior uploads can still refer to the old buffers.
        var mesh = ByteBuffer.allocate(60_000 * WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 60_000; i++) mesh.putFloat(4 + (i % 3) * .1f).putFloat(0).putFloat(0).putInt(-1)
                .putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240);
        for (int i = 0; i < 3; i++) {
            send.accept(new WorldProtocol.Mesh(1, 100 + i, 0, 0, 0, mesh.array()));
            backend.renderWorld(270, 150); backend.finishWorldFrame();
        }
        send.accept(new WorldProtocol.Mesh(1, 100, 0, 0, 0, mesh.array()));
        for (int i = 0; i < 3; i++) send.accept(new WorldProtocol.Remove(1, 100 + i));
        var pixels = read(backend.renderWorld(270, 150), 270, 150);
        try {
            int at = (75 * 270 + 135) * 4;
            require(Byte.toUnsignedInt(pixels.get(at)) == 207 && Byte.toUnsignedInt(pixels.get(at + 1)) == 48, "Arena growth/reuse corrupted existing sections");
            require(backend.optimizationStats()[3] >= 3, "Arena growth was not exercised");
        } finally { MemoryUtil.memFree(pixels); backend.finishWorldFrame(); }
        System.out.println("MESH_ARENA_SMOKE=OK (async growth, replacement, removal, preserved existing geometry)");
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
    /**
     * P7/P8 replay over the translucent scene's opaque terrain quad ([-.5,.5]^2, depth .6): a green entity in front of it (depth .5),
     * one behind it (depth .7, must be hidden) and one beside it (depth .7, visible over cleared depth).
     */
    private static void entities(MainWindowBackend backend) {
        require(backend.entityTexture(new Object[]{1001, 2, 2, 0, new byte[]{0, (byte)255, 0, (byte)255, 0, (byte)255, 0, (byte)255, 0, (byte)255, 0, (byte)255, 0, (byte)255, 0, (byte)255}}),
                "Entity texture upload failed");
        byte[] white = new byte[16 * 16 * 4]; java.util.Arrays.fill(white, (byte)255);
        backend.entityTexture(new Object[]{1002, 16, 16, 0, white});
        ByteBuffer vertices = ByteBuffer.allocate(3 * 4 * 36).order(ByteOrder.LITTLE_ENDIAN);
        entityQuad(vertices, -.4f, -.1f, 0); entityQuad(vertices, .1f, .4f, .4f); entityQuad(vertices, .6f, .9f, .4f);
        float[] uniforms = new float[EntityPass.UNIFORM_FLOATS];
        for (int i = 0; i < 4; i++) { uniforms[i * 5] = 1; uniforms[16 + i * 5] = 1; uniforms[44 + i] = 1; }
        uniforms[32] = uniforms[37] = uniforms[42] = 1;           // inverse view rotation columns
        uniforms[50] = uniforms[54] = 1; uniforms[55] = 1;       // light directions +z, opaque output
        uniforms[60] = 1e9f; uniforms[61] = 2e9f; uniforms[63] = 1; // fog out of range, variant 1 (entity_solid)
        int[] header = new int[EntityPass.HEADER];
        header[EntityPass.H_FORMAT] = EntityPass.FORMAT_ENTITY; header[EntityPass.H_VARIANT] = 1;
        header[EntityPass.H_FLAGS] = EntityPass.DEPTH_TEST | EntityPass.DEPTH_WRITE | EntityPass.CULL;
        header[EntityPass.H_TEXTURE] = 1001; header[EntityPass.H_OVERLAY] = 1002; header[EntityPass.H_VERTICES] = 12; header[EntityPass.H_MODE] = EntityPass.MODE_QUADS;
        Object[] packets = {new Object[]{header, uniforms, vertices.array(), null}};
        // P9: a translucent red particle quad over the left entity (depth .4), drawn by a second overlay pass of the same frame.
        backend.entityTexture(new Object[]{1003, 1, 1, 0, new byte[]{(byte)255, 0, 0, (byte)255}});
        ByteBuffer particle = ByteBuffer.allocate(4 * 28).order(ByteOrder.LITTLE_ENDIAN);
        float[][] corners = {{-.4f, -.3f}, {-.1f, -.3f}, {-.1f, .3f}, {-.4f, .3f}};
        for (float[] c : corners) particle.putFloat(c[0]).putFloat(c[1]).putFloat(-.2f).putFloat(.5f).putFloat(.5f).putInt(0x80ffffff).putShort((short)240).putShort((short)240);
        float[] particleUniforms = uniforms.clone(); particleUniforms[55] = 0; particleUniforms[63] = 2; // blended, discard-after variant
        int[] particleHeader = header.clone();
        particleHeader[EntityPass.H_FORMAT] = EntityPass.FORMAT_PARTICLE; particleHeader[EntityPass.H_VARIANT] = 2; particleHeader[EntityPass.H_TEXTURE] = 1003;
        particleHeader[EntityPass.H_OVERLAY] = 1003; particleHeader[EntityPass.H_VERTICES] = 4;
        particleHeader[EntityPass.H_FLAGS] = EntityPass.BLEND | EntityPass.DEPTH_TEST | EntityPass.DEPTH_WRITE;
        Object[] particles = {new Object[]{particleHeader, particleUniforms, particle.array(), null}};
        int width = 64, height = 64;
        for (int frame = 0; frame < 3; frame++) {
            require(backend.renderWorld(width, height) > 0, "Terrain frame for entities failed");
            int[] textures = backend.renderOverlay(packets);
            require(textures != null && textures[0] > 0 && textures[1] > 0, "Entity pass did not produce shared textures");
            ByteBuffer pixels = read(textures[0], width, height);
            try {
                int row = height / 2 * width;
                int front = (row + (int)(.35f * width)) * 4, behind = (row + (int)(.625f * width)) * 4, beside = (row + (int)(.875f * width)) * 4;
                require(Byte.toUnsignedInt(pixels.get(front + 1)) == 255 && Byte.toUnsignedInt(pixels.get(front + 3)) == 255 && pixels.get(front) == 0,
                        "Entity in front of terrain missing: frame=" + frame);
                require(Byte.toUnsignedInt(pixels.get(behind + 3)) == 0, "Entity behind Vulkan terrain depth was drawn: frame=" + frame);
                require(Byte.toUnsignedInt(pixels.get(beside + 1)) == 255 && Byte.toUnsignedInt(pixels.get(beside + 3)) == 255, "Entity beside terrain missing: frame=" + frame);
            } finally { MemoryUtil.memFree(pixels); }
            java.nio.FloatBuffer depths = MemoryUtil.memAllocFloat(width * height);
            int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
            try {
                glBindTexture(GL_TEXTURE_2D, textures[1]); glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, depths);
                int row = height / 2 * width;
                require(Math.abs(depths.get(row + (int)(.35f * width)) - .5f) < 1e-5f && Math.abs(depths.get(row + (int)(.625f * width)) - .6f) < 1e-5f
                        && Math.abs(depths.get(row + (int)(.875f * width)) - .7f) < 1e-5f, "Entity depth is not terrain depth plus entity writes");
            } finally { glBindTexture(GL_TEXTURE_2D, bound); MemoryUtil.memFree(depths); }
            backend.finishOverlay();
            int[] second = backend.renderOverlay(particles);
            require(second != null && second[0] == textures[0], "Second overlay pass did not reuse the shared images");
            ByteBuffer overlay = read(second[0], width, height);
            try {
                int row = height / 2 * width, front = (row + (int)(.35f * width)) * 4, beside = (row + (int)(.875f * width)) * 4;
                // Premultiplied red at alpha ~.5 over a cleared target; the entity pixels of the first pass must be gone.
                require(Math.abs(Byte.toUnsignedInt(overlay.get(front)) - 128) <= 2 && overlay.get(front + 1) == 0 && Math.abs(Byte.toUnsignedInt(overlay.get(front + 3)) - 128) <= 2,
                        "Particle overlay wrong: r=" + Byte.toUnsignedInt(overlay.get(front)) + " g=" + Byte.toUnsignedInt(overlay.get(front + 1)) + " a=" + Byte.toUnsignedInt(overlay.get(front + 3)));
                require(Byte.toUnsignedInt(overlay.get(beside + 3)) == 0, "Second overlay was not cleared: frame=" + frame);
            } finally { MemoryUtil.memFree(overlay); }
            require(Math.abs(depth(second[1], width, height, .35f) - .4f) < 1e-5f && Math.abs(depth(second[1], width, height, .875f) - 1f) < 1e-5f,
                    "Second overlay must start from terrain depth, not the first overlay's depth");
            backend.finishOverlay();
            backend.finishWorldFrame();
        }
        long[] stats = backend.worldStats();
        require(stats[18] >= 6 && stats[19] >= 6 && stats[21] >= 3 && stats[23] == 0, "Overlay statistics wrong: " + java.util.Arrays.toString(stats));
        System.out.println("ENTITY_SMOKE=OK (entity texture mirror, terrain depth test, entity depth export, both slots)");
        System.out.println("PARTICLE_OVERLAY_SMOKE=OK (second pass per frame, PARTICLE layout, premultiplied blend, cleared reuse, terrain-only depth)");
    }
    /**
     * P10 sky inside the terrain submit, under the translucent scene's opaque grey quad ([-.5,.5]^2, depth .6): an opaque POSITION
     * disc (sky blue via ColorModulator), an additive POSITION_TEX red "sun" on the right and a color-less depth quad (cloud prepass)
     * at depth .05 that must hide the terrain behind it.
     */
    private static void sky(MainWindowBackend backend) {
        ByteBuffer disc = ByteBuffer.allocate(4 * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] c : new float[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) disc.putFloat(c[0]).putFloat(c[1]).putFloat(.98f);
        ByteBuffer sun = ByteBuffer.allocate(4 * 20).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] c : new float[][]{{.6f, -.3f}, {.9f, -.3f}, {.9f, .3f}, {.6f, .3f}}) sun.putFloat(c[0]).putFloat(c[1]).putFloat(.97f).putFloat(.5f).putFloat(.5f);
        ByteBuffer prepass = ByteBuffer.allocate(4 * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] c : new float[][]{{-.4f, -.3f}, {-.2f, -.3f}, {-.2f, .3f}, {-.4f, .3f}}) prepass.putFloat(c[0]).putFloat(c[1]).putFloat(-.9f);
        Object[] packets = {
                skyPacket(EntityPass.FORMAT_POSITION, 3, EntityPass.DEPTH_TEST, EntityPass.WHITE_TEXTURE, disc.array(), new float[]{.2f, .4f, .8f, 1}, 1),
                skyPacket(EntityPass.FORMAT_POSITION_TEX, 4, EntityPass.DEPTH_TEST | EntityPass.BLEND_ADD, 1003, sun.array(), new float[]{1, 1, 1, 1}, 2),
                skyPacket(EntityPass.FORMAT_POSITION, 3, EntityPass.DEPTH_TEST | EntityPass.DEPTH_WRITE | EntityPass.NO_COLOR, EntityPass.WHITE_TEXTURE, prepass.array(), new float[]{1, 0, 1, 1}, 1)};
        int width = 64, height = 64;
        for (int frame = 0; frame < 2; frame++) {
            int texture = backend.renderWorldWithSky(width, height, packets);
            ByteBuffer pixels = read(texture, width, height);
            try {
                int row = height / 2 * width;
                int[] skyOnly = rgba(pixels, row + (int)(.05f * width)), terrain = rgba(pixels, row + width / 2), lit = rgba(pixels, row + (int)(.875f * width)),
                        hidden = rgba(pixels, row + (int)(.35f * width));
                require(near(skyOnly, 51, 102, 204, 255), "Sky disc missing under terrain: " + java.util.Arrays.toString(skyOnly));
                require(near(terrain, 128, 128, 128, 255), "Terrain must draw over the sky: " + java.util.Arrays.toString(terrain));
                require(near(lit, 255, 102, 204, 255), "Additive sun wrong: " + java.util.Arrays.toString(lit));
                require(near(hidden, 51, 102, 204, 255), "Color-less depth prepass must hide terrain and keep sky color: " + java.util.Arrays.toString(hidden));
            } finally { MemoryUtil.memFree(pixels); }
            backend.finishWorldFrame();
        }
        require(backend.worldStats()[24] >= 6, "Sky packets were not drawn in the terrain submit");
        System.out.println("SKY_SMOKE=OK (sky under terrain in the terrain submit, POSITION/POSITION_TEX formats, additive blend, color-less depth prepass)");
    }
    /**
     * P11 hand/GUI overlays: start from cleared depth, so an entity quad behind the terrain quad (depth .7 > .6) is visible and its
     * own depth is exported; plus a POSITION_COLOR_TEX quad. Six such passes in one frame (order-preserving GUI splits).
     */
    private static void gui(MainWindowBackend backend) {
        ByteBuffer entity = ByteBuffer.allocate(4 * 36).order(ByteOrder.LITTLE_ENDIAN);
        entityQuad(entity, .1f, .4f, .4f);
        Object[] hand = skyPacket(EntityPass.FORMAT_ENTITY, 1, EntityPass.DEPTH_TEST | EntityPass.DEPTH_WRITE | EntityPass.CULL, 1001, entity.array(), new float[]{1, 1, 1, 1}, 1);
        float[] light = (float[]) hand[1]; light[50] = light[54] = 1; // +z light, as in entities()
        ((int[]) hand[0])[EntityPass.H_OVERLAY] = 1002;
        ByteBuffer blit = ByteBuffer.allocate(4 * 24).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] c : new float[][]{{.6f, -.3f}, {.9f, -.3f}, {.9f, .3f}, {.6f, .3f}}) blit.putFloat(c[0]).putFloat(c[1]).putFloat(0).putInt(-1).putFloat(.5f).putFloat(.5f);
        Object[] colorTex = skyPacket(EntityPass.FORMAT_POSITION_COLOR_TEX, 6, EntityPass.DEPTH_TEST, 1003, blit.array(), new float[]{1, 1, 1, 1}, 1);
        int width = 64, height = 64;
        require(backend.renderWorld(width, height) > 0, "Terrain frame for GUI overlays failed");
        for (int pass = 0; pass < 6; pass++) {
            int[] textures = backend.renderOverlay(new Object[]{hand, colorTex}, true);
            require(textures != null, "Cleared-depth overlay pass " + pass + " was refused");
            ByteBuffer pixels = read(textures[0], width, height);
            try {
                int row = height / 2 * width;
                int[] behind = rgba(pixels, row + (int)(.625f * width)), red = rgba(pixels, row + (int)(.875f * width));
                require(near(behind, 0, 255, 0, 255), "Cleared depth must not hide the entity behind terrain: " + java.util.Arrays.toString(behind));
                require(near(red, 255, 0, 0, 255), "POSITION_COLOR_TEX quad wrong: " + java.util.Arrays.toString(red));
            } finally { MemoryUtil.memFree(pixels); }
            require(Math.abs(depth(textures[1], width, height, .625f) - .7f) < 1e-5f && Math.abs(depth(textures[1], width, height, .05f) - 1f) < 1e-5f,
                    "Cleared-depth overlay must export only its own depth");
            backend.finishOverlay();
        }
        backend.finishWorldFrame();
        System.out.println("GUI_OVERLAY_SMOKE=OK (cleared-depth overlays, six passes in one frame, POSITION_COLOR_TEX)");
    }
    private static Object[] skyPacket(int format, int variant, int flags, int texture, byte[] vertices, float[] color, float output) {
        float[] uniforms = new float[EntityPass.UNIFORM_FLOATS];
        for (int i = 0; i < 4; i++) { uniforms[i * 5] = 1; uniforms[16 + i * 5] = 1; }
        uniforms[32] = uniforms[37] = uniforms[42] = 1;
        System.arraycopy(color, 0, uniforms, 44, 4);
        uniforms[55] = output; uniforms[60] = 1e9f; uniforms[61] = 2e9f; uniforms[63] = variant;
        int[] header = new int[EntityPass.HEADER];
        header[EntityPass.H_FORMAT] = format; header[EntityPass.H_VARIANT] = variant; header[EntityPass.H_FLAGS] = flags;
        header[EntityPass.H_TEXTURE] = texture; header[EntityPass.H_OVERLAY] = texture; header[EntityPass.H_VERTICES] = 4; header[EntityPass.H_MODE] = EntityPass.MODE_QUADS;
        return new Object[]{header, uniforms, vertices, null};
    }
    private static int[] rgba(ByteBuffer pixels, int pixel) { int[] c = new int[4]; for (int i = 0; i < 4; i++) c[i] = Byte.toUnsignedInt(pixels.get(pixel * 4 + i)); return c; }
    private static boolean near(int[] c, int r, int g, int b, int a) { return Math.abs(c[0] - r) <= 2 && Math.abs(c[1] - g) <= 2 && Math.abs(c[2] - b) <= 2 && Math.abs(c[3] - a) <= 2; }
    /** NEW_ENTITY layout: position, color, uv0, overlay uv1, light uv2, normal + padding (36 bytes). */
    private static void entityQuad(ByteBuffer vertices, float x0, float x1, float z) {
        float[][] corners = {{x0, -.3f}, {x1, -.3f}, {x1, .3f}, {x0, .3f}};
        for (float[] c : corners) vertices.putFloat(c[0]).putFloat(c[1]).putFloat(z).putInt(-1).putFloat(.25f).putFloat(.25f)
                .putShort((short)0).putShort((short)10).putShort((short)240).putShort((short)240).put((byte)0).put((byte)0).put((byte)127).put((byte)0);
    }
    private static ByteBuffer quad(ByteBuffer vertices, float z, int abgr) {
        float[][] corners = {{-.5f,-.5f},{.5f,-.5f},{.5f,.5f},{.5f,.5f},{-.5f,.5f},{-.5f,-.5f}};
        for (float[] c : corners) vertices.putFloat(c[0]).putFloat(c[1]).putFloat(z).putInt(abgr)
                .putFloat(.5f).putFloat(.5f).putShort((short)240).putShort((short)240);
        return vertices;
    }
    private static float depth(int texture, int width, int height, float x) {
        java.nio.FloatBuffer depths = MemoryUtil.memAllocFloat(width * height);
        int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, texture); glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, depths);
            return depths.get(height / 2 * width + (int)(x * width));
        } finally { glBindTexture(GL_TEXTURE_2D, bound); MemoryUtil.memFree(depths); }
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
