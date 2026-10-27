package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.JNI;
import org.lwjgl.system.windows.User32;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window;

/** Real WSI pixels from the native terrain kernel, not the hybrid MainWindowBackend. */
public final class VkNativeWindowSmoke {
    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    public static void main(String[] ignored) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new UnsupportedOperationException("Native Win32 smoke requires Windows");
        Configuration.STACK_SIZE.set(4096);
        Path output = Path.of(System.getProperty("vulkanforge.smokeReportDir", "build/reports/native-window"));
        Files.createDirectories(output);
        try (GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err).set()) {
            require(glfwInit(), "GLFW initialization failed");
            long window = 0;
            try {
                glfwDefaultWindowHints(); glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                window = glfwCreateWindow(320, 240, "VulkanForge native terrain WSI smoke", 0, 0);
                require(window != 0, "Native window creation failed");
                require(glfwGetWindowAttrib(window, GLFW_CLIENT_API) == GLFW_NO_API && glfwGetCurrentContext() == 0, "OpenGL context exists");
                Path report = output.resolve("native-window-report.txt");
                long beforeClose;
                try (NativeWindowBackend backend = new NativeWindowBackend(report.toString(), glfwGetWin32Window(window), windowInstance(window),
                        320, 240, VulkanContext.requestedApiVersion(), "", Boolean.getBoolean("vulkanforge.requireValidation"))) {
                    load(backend, 1);
                    render(backend, window, 6);
                    capture(backend, window, output.resolve("terrain.png"), 0xff0000);
                    assertPixel(output.resolve("terrain.png"), .75, .75, 0x80007f);
                    // Both slots have now sampled their private source. Neither may be recycled based only on the terrain fence.
                    long[] stats = backend.terrainStats();
                    require(stats[4] == 0 && stats[5] == 0 && stats[6] == 0 && stats[7] == 0, "Interop/fallback used in native rendering");
                    long presented = backend.presentedFrames();
                    require(!backend.frame(0, 0) && backend.presentedFrames() == presented, "Zero extent submitted a frame");
                    require(backend.beginFrame(320, 240), "Failed to acquire a cancellation frame");
                    backend.cancelFrame();
                    require(backend.presentationStats()[2] == 1 && backend.presentedFrames() == presented, "Cancelled acquisition counted as a terrain presentation");
                    render(backend, window, 3);
                    long submitted = backend.terrainStats()[0];
                    try {
                        backend.frame(320, 240, new NativeFramePlan(List.of(), List.of(new NativeFramePlan.UnsupportedDraw(17, "GUI", "unimplemented"))));
                        throw new AssertionError("Native GUI omission was accepted");
                    } catch (UnsupportedOperationException expected) { require(expected.getMessage().contains("GUI"), "Missing unsupported draw detail"); }
                    require(backend.terrainStats()[0] == submitted, "Unsupported frame was submitted");
                    var badPost = new NativeFramePlan.PostDraw("invalid", "#version 450\ninvalid shader", "#version 450\ninvalid shader", new float[256], new int[]{1,0,1,0,32774}, new float[4]);
                    try {
                        backend.frame(320, 240, new NativeFramePlan(List.of(badPost), List.of()));
                        throw new AssertionError("Invalid shader was accepted");
                    } catch (IllegalArgumentException | IllegalStateException expected) { }
                    require(backend.terrainStats()[0] == submitted && backend.presentationStats()[2] == 2, "Failed shader stranded an acquired frame");
                    render(backend, window, 3);
                    backend.accept(WorldProtocol.pack(new WorldProtocol.TexturePatch(1, WorldProtocol.BLOCK_ATLAS, 0, 0, 1, 1, new byte[]{0,(byte)255,0,(byte)255})));
                    capture(backend, window, output.resolve("patched.png"), 0x00ff00);
                    int generation = backend.swapchainGenerations();
                    glfwSetWindowSize(window, 480, 300); glfwPollEvents();
                    render(backend, window, 4);
                    require(backend.swapchainGenerations() > generation && backend.width() == 480 && backend.height() == 300, "Native resize failed");
                    capture(backend, window, output.resolve("resized.png"), 0x00ff00);
                    generation = backend.swapchainGenerations(); backend.setVsync(false); render(backend, window, 3);
                    require(backend.swapchainGenerations() > generation, "VSync change did not recreate swapchain");
                    backend.accept(WorldProtocol.pack(new WorldProtocol.Remove(1, 1)));
                    render(backend, window, 3);
                    backend.captureNext(output.resolve("removed.png").toString()); render(backend, window, 1);
                    assertPixel(output.resolve("removed.png"), .25, .25, 0x14293d);
                    load(backend, 2); render(backend, window, 4);
                    capture(backend, window, output.resolve("reset.png"), 0xff0000);
                    // Odd and even post counts select different private output images.
                    var invert = new NativeFramePlan.PostDraw("invert",
                            "#version 450\nlayout(location=0)out vec2 uv;void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}",
                            "#version 450\nlayout(location=0)in vec2 uv;layout(location=0)out vec4 color;layout(set=0,binding=0)uniform sampler2D sourceColor;void main(){color=vec4(1.0-texture(sourceColor,uv).rgb,1);}",
                            new float[0], new int[]{1,0,1,0,32774}, new float[4]);
                    for (int count : new int[]{1,2,3}) {
                        Path postCapture = output.resolve("posts-" + count + ".png"); backend.captureNext(postCapture.toString());
                        int[] size = extent(window);
                        require(backend.frame(size[0], size[1], new NativeFramePlan(Collections.nCopies(count,invert), List.of())), "Post frame skipped");
                        assertPixel(postCapture,.25,.25,count % 2 == 0 ? 0xff0000 : 0x00ffff);
                    }
                    require(glfwGetCurrentContext() == 0, "Native rendering created an OpenGL context");
                    require(backend.validationErrors() == 0, "Vulkan validation errors");
                    // Closing with an acquired image exercises WSI semaphore retirement as well as normal shutdown.
                    int[] extent = extent(window); require(backend.beginFrame(extent[0], extent[1]), "Final acquire failed");
                    beforeClose = backend.presentedFrames();
                }
                String log = Files.readString(report);
                require(log.contains("CLOSED=true") && log.contains("FINAL_PRESENTED_FRAMES=" + beforeClose)
                        && log.contains("CANCELLED_ACQUIRES=3") && log.contains("VALIDATION_ERRORS=0"), "Incomplete native shutdown report");
                // Reopening must not retain a surface/device or private attachment from the previous renderer.
                try (NativeWindowBackend backend = new NativeWindowBackend(output.resolve("reopened-report.txt").toString(), glfwGetWin32Window(window), windowInstance(window),
                        480, 300, VulkanContext.requestedApiVersion(), "", Boolean.getBoolean("vulkanforge.requireValidation"))) {
                    load(backend, 3); render(backend, window, 3); capture(backend, window, output.resolve("reopened.png"), 0xff0000);
                }
                System.out.println("VK_NATIVE_WINDOW_SMOKE=OK (private opaque/cutout/translucent terrain, swapchain pixels/orientation/colors, odd/even posts, both slots, CPU texture patch, zero extent, cancel/failure recovery, resize, VSync, remove/reset, acquired shutdown, reopen)");
            } finally {
                if (window != 0) glfwDestroyWindow(window);
                glfwTerminate(); glfwSetErrorCallback(null);
            }
        }
    }
    private static void load(NativeWindowBackend backend, long epoch) {
        backend.accept(WorldProtocol.pack(new WorldProtocol.Reset(epoch)));
        backend.accept(WorldProtocol.pack(new WorldProtocol.Atlas(epoch, 4, 1, new byte[]{(byte)255,0,0,(byte)255, 0,0,(byte)255,(byte)255, (byte)255,0,0,(byte)128, (byte)255,(byte)255,0,0})));
        byte[] light = new byte[16*16*4]; Arrays.fill(light, (byte)255);
        backend.accept(WorldProtocol.pack(new WorldProtocol.TexturePatch(epoch, WorldProtocol.LIGHT_MAP, 0, 0, 16, 16, light)));
        backend.accept(WorldProtocol.pack(new WorldProtocol.Camera(epoch, 0, 0, 0, IDENTITY, .08f, .16f, .24f)));
        backend.accept(WorldProtocol.pack(new WorldProtocol.Mesh(epoch, 1, 0, 0, 0, quad(-.9f,.1f,-.1f,.9f,.125f))));
        backend.accept(WorldProtocol.pack(new WorldProtocol.Mesh(epoch, 2, 0, 0, 0, quad(-.9f,-.9f,-.1f,-.1f,.375f))));
        byte[] translucent = quad(.1f,-.9f,.9f,-.1f,.625f), doubleSided = new byte[translucent.length*2];
        System.arraycopy(translucent,0,doubleSided,0,translucent.length);
        for (int i = 0; i < 6; i++) System.arraycopy(translucent,i*WorldProtocol.STRIDE,doubleSided,(11-i)*WorldProtocol.STRIDE,WorldProtocol.STRIDE);
        backend.accept(WorldProtocol.pack(new WorldProtocol.Mesh(epoch, 3, 0, 0, 0, quad(.1f,-.9f,.9f,-.1f,.375f), doubleSided)));
        backend.accept(WorldProtocol.pack(new WorldProtocol.Mesh(epoch, 4, 0, 0, 0, quad(.1f,.1f,.9f,.9f,.875f))));
    }
    private static byte[] quad(float x0,float y0,float x1,float y1,float u) {
        var bytes = ByteBuffer.allocate(6*WorldProtocol.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] p : new float[][]{{x0,y0},{x1,y0},{x1,y1},{x1,y1},{x0,y1},{x0,y0}})
            bytes.putFloat(p[0]).putFloat(p[1]).putFloat(0).putInt(-1).putFloat(u).putFloat(.5f).putShort((short)240).putShort((short)240);
        return bytes.array();
    }
    private static int[] extent(long window) { int[] w = {0}, h = {0}; glfwGetFramebufferSize(window, w, h); return new int[]{w[0],h[0]}; }
    private static long windowInstance(long window) {
        return JNI.callPP(glfwGetWin32Window(window), -6, User32.getLibrary().getFunctionAddress("GetWindowLongPtrW"));
    }
    private static void render(NativeWindowBackend backend,long window,int count) {
        int complete = 0;
        for (int attempt = 0; complete < count && attempt < count + 30; attempt++) {
            glfwPollEvents(); int[] size = extent(window);
            if (backend.frame(size[0],size[1])) complete++;
        }
        require(complete == count, "Native renderer did not make progress");
    }
    private static void capture(NativeWindowBackend backend,long window,Path path,int topColor) throws Exception {
        backend.captureNext(path.toString()); render(backend,window,1);
        assertPixel(path,.25,.25,topColor); assertPixel(path,.25,.75,0x0000ff); assertPixel(path,.75,.5,0x14293d);
    }
    private static void assertPixel(Path path,double x,double y,int expected) throws Exception {
        var image = ImageIO.read(path.toFile()); require(image != null, "No swapchain capture: " + path);
        int pixel = image.getRGB((int)(image.getWidth()*x),(int)(image.getHeight()*y));
        for (int shift : new int[]{0,8,16}) require(Math.abs(((pixel>>>shift)&255)-((expected>>>shift)&255)) <= 2,
                "Swapchain pixel mismatch in " + path + " at " + x + "," + y + ": " + Integer.toHexString(pixel) + " expected " + Integer.toHexString(expected));
    }
    private static void require(boolean value,String reason) { if (!value) throw new AssertionError(reason); }
}
