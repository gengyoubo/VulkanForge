package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vulkanforge.bridge.WorldProtocol;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.OpenGLVersion;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Real production terrain/shaders, hidden context + FBO only. No game window, swap or presentation. */
public final class GlOffscreenSmoke {
    private static final int SIZE = 64;
    private static final List<String> results = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        String version = args[0];
        Path directory = Path.of("build/reports/offscreen-versions/gl-" + version).toAbsolutePath();
        Files.createDirectories(directory);
        System.setProperty("vulkanforge.offscreenReportDir", directory.toString());
        RenderSystem.initRenderThread();
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        long window = 0;
        GLDebugMessageCallback debug = null;
        try {
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, Integer.parseInt(version.substring(2)));
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE); glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
            window = glfwCreateWindow(SIZE, SIZE, "VulkanForge offscreen test", 0, 0);
            require(window != 0, "hidden context created");
            glfwMakeContextCurrent(window); GL.createCapabilities();
            List<String> errors = new ArrayList<>();
            debug = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
                if (type == GL_DEBUG_TYPE_ERROR) errors.add(GLDebugMessageCallback.getMessage(length, message));
            });
            glEnable(GL_DEBUG_OUTPUT); glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS); glDebugMessageCallback(debug, 0);
            Gl46Context.select(OpenGLVersion.valueOf("GL_" + version.replace('.', '_')));
            require(Gl46Context.terrainUnsupported() == null, "selected backend available");
            results.add("REQUESTED_GL=" + version + " ACTUAL_GL=" + glGetString(GL_VERSION) + " GPU=" + glGetString(GL_RENDERER));
            results.add("PATHS=" + GlCompat.paths());
            results.add("VISIBLE=false SWAP_CALLS=0 PRESENT_CALLS=0 TARGET=FBO");
            // Compile every entity/instancing stage as well as the terrain programs.
            for (String vertex : new String[]{"entity.vert", "entity_instanced.vert"}) {
                var program = Gl46Programs.load(vertex, "entity.frag"); glDeleteProgram(program.id());
            }
            require(true, "entity and instanced entity shaders compile/link");
            int color = texture(SIZE, GL_RGBA8), depth = texture(SIZE, GL_DEPTH_COMPONENT32F);
            int framebuffer = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "offscreen framebuffer complete");
            int atlas = texture(2, GL_RGBA8), light = texture(16, GL_RGBA8);
            atlas(atlas, 255); light(light, 255);
            RenderSystem.setShaderFogStart(100); RenderSystem.setShaderFogEnd(200);
            glViewport(0, 0, SIZE, SIZE);
            try (Gl46Terrain terrain = new Gl46Terrain()) {
                terrain.send(new WorldProtocol.Reset(1)); camera(terrain, 1, 0);
                terrain.quads(1, 1, 0, 0, 0, layers(quad(-0.5f, 0.25f), 2));
                terrain.quads(1, 2, 0, 0, 0, layers(quad(0.5f, 0.75f), 0));
                draw(terrain, atlas, light); pixel(255, 0, 0, "depth: near cutout covers far blue");
                atlas(atlas, 0); draw(terrain, atlas, light); pixel(0, 0, 255, "cutout alpha hole preserves far geometry");
                atlas(atlas, 24); draw(terrain, atlas, light); pixel(0, 0, 255, "alpha below 0.1 discarded");
                atlas(atlas, 26); draw(terrain, atlas, light); pixel(255, 0, 0, "alpha above 0.1 visible");
                atlas(atlas, 255); light(light, 32); draw(terrain, atlas, light); pixel(32, 0, 0, "lightmap update without mesh rebuild");
                light(light, 255);
                terrain.buildHiZ(depth, SIZE, SIZE); draw(terrain, atlas, light); pixel(255, 0, 0, "Hi-Z compute pipeline");
                resizePyramid(terrain);
                terrain.buildHiZ(depth, SIZE, SIZE);
                camera(terrain, 1, 10); draw(terrain, atlas, light); pixel(0, 0, 0, "camera change culls sections");
                camera(terrain, 1, 0); terrain.send(new WorldProtocol.Remove(1, 1));
                draw(terrain, atlas, light); pixel(0, 0, 255, "section removal reveals far geometry");
                terrain.send(new WorldProtocol.Reset(2)); camera(terrain, 2, 0);
                terrain.quads(1, 3, 0, 0, 0, layers(quad(-0.5f, 0.25f), 0));
                terrain.quads(2, 4, 0, 0, 0, layers(quad(0.5f, 0.75f), 0));
                draw(terrain, atlas, light); pixel(0, 0, 255, "reset ignores stale epoch");
                // 50%-alpha red over blue using the real sorted translucent path.
                atlas(atlas, 128); terrain.quads(2, 5, 0, 0, 0, layers(quad(-0.5f, 0.25f), 3));
                draw(terrain, atlas, light); pixel(128, 0, 127, "sorted translucent blend");
                for (int i = 0; i < 40; i++) draw(terrain, atlas, light);
                require(terrain.stats()[0] >= 40 && terrain.cullStats()[0] > 0, "frame slots and GPU culling counters reused");
            }
            glDeleteTextures(color); glDeleteTextures(depth); glDeleteTextures(atlas); glDeleteTextures(light); glDeleteFramebuffers(framebuffer);
            glFinish(); require(glGetError() == GL_NO_ERROR && errors.isEmpty(), "GL_ERRORS=0 " + errors);
            results.add("STATUS=OK");
        } catch (Throwable error) {
            results.add("STATUS=FAILED " + error); throw error;
        } finally {
            Files.write(directory.resolve("report.txt"), results);
            results.forEach(System.out::println);
            if (debug != null) { glDebugMessageCallback(null, 0); debug.free(); }
            if (window != 0) glfwDestroyWindow(window); glfwTerminate();
        }
    }
    private static void resizePyramid(Gl46Terrain terrain) throws Exception {
        var handle=Gl46Terrain.class.getDeclaredField("pyramid");handle.setAccessible(true);
        // 65 and 66 share the same ceil(size/2), but require different source-size parameters.
        for(int size:new int[]{65,66}) {
            int source=texture(size,GL_DEPTH_COMPONENT32F);
            try {
                float[] data=new float[size*size];Arrays.fill(data,.1f);data[data.length-1]=.9f;
                glBindTexture(GL_TEXTURE_2D,source);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,size,size,GL_DEPTH_COMPONENT,GL_FLOAT,data);
                terrain.buildHiZ(source,size,size);
                glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
                int base=(size+1)/2;float[] pixels=new float[base*base];
                glBindTexture(GL_TEXTURE_2D,handle.getInt(terrain));glGetTexImage(GL_TEXTURE_2D,0,GL_RED,GL_FLOAT,pixels);
                require(Math.abs(pixels[pixels.length-1]-.9f)<.0001f,"Hi-Z retains odd/even border at size "+size);
            } finally {glDeleteTextures(source);}
        }
    }
    private static void camera(Gl46Terrain terrain, long epoch, float x) {
        float[] matrix = new org.joml.Matrix4f().identity().get(new float[16]);
        terrain.send(new WorldProtocol.Camera(epoch, x, 0, 0, matrix, 0, 0, 0));
    }
    private static byte[][] layers(byte[] bytes, int layer) {
        byte[][] layers = {new byte[0], new byte[0], new byte[0], new byte[0]}; layers[layer] = bytes; return layers;
    }
    private static byte[] quad(float z, float u) {
        ByteBuffer bytes = ByteBuffer.allocate(4 * 32).order(ByteOrder.nativeOrder());
        for (float[] point : new float[][]{{-.8f,-.8f},{.8f,-.8f},{.8f,.8f},{-.8f,.8f}}) {
            bytes.putFloat(point[0]).putFloat(point[1]).putFloat(z);
            bytes.putInt(-1).putFloat(u).putFloat(.25f).putShort((short)240).putShort((short)240).putInt(0);
        }
        return bytes.array();
    }
    private static int texture(int size, int format) {
        int texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture); glTexStorage2D(GL_TEXTURE_2D, 1, format, size, size);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); return texture;
    }
    private static void atlas(int texture, int alpha) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE,
                new int[]{(alpha << 24) | 255, 0xffff0000, (alpha << 24) | 255, 0xffff0000});
    }
    private static void light(int texture, int value) {
        int[] pixels = new int[256]; Arrays.fill(pixels, 0xff000000 | value * 0x010101);
        glBindTexture(GL_TEXTURE_2D, texture); glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 16, 16, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }
    private static void draw(Gl46Terrain terrain, int atlas, int light) {
        glDepthMask(true); glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        require(terrain.drawOpaque(atlas, light), "opaque submitted"); terrain.drawTranslucent(atlas, light);
    }
    private static void pixel(int red, int green, int blue, String label) {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int r = pixel.get(0) & 255, g = pixel.get(1) & 255, b = pixel.get(2) & 255;
            require(Math.abs(r-red) <= 3 && Math.abs(g-green) <= 3 && Math.abs(b-blue) <= 3, label + " rgb=" + r + "," + g + "," + b);
        } finally { MemoryUtil.memFree(pixel); }
    }
    private static void require(boolean pass, String label) { if (!pass) throw new AssertionError(label); if (!label.equals("opaque submitted")) results.add("PASS " + label); }
}
