package github.com.gengyoubo.vulkanforge.lab;

import java.nio.file.*;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL;
import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** Real GPU test: distinct native GL/Vulkan windows and exported images/semaphores. */
public final class NativePresentSmoke {
    public static void main(String[] args) throws Exception {
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
        if (!glfwInit()) throw new AssertionError("GLFW init failed");
        long hidden = 0, visible = 0;
        MainWindowBackend backend = null;
        int texture = 0, source = 0;
        Path output = Path.of("build/present-smoke").toAbsolutePath(); Files.createDirectories(output);
        try {
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            hidden = glfwCreateWindow(320, 240, "Hidden legacy renderer", 0, 0);
            if (hidden == 0) throw new AssertionError("GL window creation failed");
            glfwMakeContextCurrent(hidden); GL.createCapabilities();
            backend = new MainWindowBackend(output.resolve("report.txt").toString(), GlInterop.deviceUUID(), GlInterop::importImage, org.lwjgl.opengl.GL11::glFinish);
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
            visible = glfwCreateWindow(640, 480, "VulkanForge main-window presentation check", 0, 0);
            if (visible == 0 || glfwGetWindowAttrib(visible, GLFW_CLIENT_API) != GLFW_NO_API) throw new AssertionError("Visible window must be GLFW_NO_API");
            long hwnd = glfwGetWin32Window(visible); backend.attach(hwnd, GlInterop.windowInstance(hwnd));
            texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 320, 240, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
            source = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, source);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Source FBO incomplete");
            // OpenGL's top-left (red), top-right (green), bottom-left (blue), bottom-right (white).
            glEnable(GL_SCISSOR_TEST);
            quadrant(0, 120, 1, 0, 0); quadrant(160, 120, 0, 1, 0);
            quadrant(0, 0, 0, 0, 1); quadrant(160, 0, 1, 1, 1); glDisable(GL_SCISSOR_TEST);
            for (int i = 0; i < 90; i++) {
                glfwPollEvents();
                if (i == 20) { backend.captureNext(output.resolve("initial.png").toString()); }
                if (i == 40) { glfwSetWindowSize(visible, 800, 500); glfwPollEvents(); backend.setVsync(false); }
                if (i == 55) backend.captureNext(output.resolve("resized.png").toString());
                int[] w = {0}, h = {0}; glfwGetFramebufferSize(visible, w, h);
                backend.present(source, 320, 240, w[0], h[0]);
            }
            long before = backend.frames(); backend.present(source, 320, 240, 0, 0);
            if (backend.frames() != before || before < 80 || backend.generations() < 2) throw new AssertionError("Frame/resize/zero extent check failed");
            verify(output.resolve("initial.png"), 640, 480); verify(output.resolve("resized.png"), 800, 500);
            if (glGetError() != GL_NO_ERROR) throw new AssertionError("GL error during shared-image presentation");
            System.out.println("PRESENT_SMOKE=OK frames=" + before + " generations=" + backend.generations() + " (RGBA orientation, GPU interop, resize, vsync and zero extent)");
        } finally {
            if (backend != null) backend.close();
            if (source != 0) glDeleteFramebuffers(source); if (texture != 0) glDeleteTextures(texture);
            if (visible != 0) glfwDestroyWindow(visible); if (hidden != 0) glfwDestroyWindow(hidden);
            glfwTerminate();
        }
    }
    private static void quadrant(int x, int y, float r, float g, float b) {
        glScissor(x, y, 160, 120); glClearColor(r, g, b, 1); glClear(GL_COLOR_BUFFER_BIT);
    }
    private static void verify(Path path, int width, int height) throws Exception {
        var image = ImageIO.read(path.toFile());
        if (image.getWidth() != width || image.getHeight() != height) throw new AssertionError("Capture size " + path);
        int[] colors = {0xff0000, 0x00ff00, 0x0000ff, 0xffffff};
        for (int i = 0; i < 4; i++) {
            int actual = image.getRGB((i % 2 * 2 + 1) * width / 4, (i / 2 * 2 + 1) * height / 4) & 0xffffff;
            if (actual != colors[i]) throw new AssertionError(path + " quadrant=" + i + " expected=" + Integer.toHexString(colors[i]) + " actual=" + Integer.toHexString(actual));
        }
    }
}
