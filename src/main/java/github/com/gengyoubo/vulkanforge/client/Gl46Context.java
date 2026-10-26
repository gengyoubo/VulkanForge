package github.com.gengyoubo.vulkanforge.client;

import com.mojang.logging.LogUtils;
import github.com.gengyoubo.vulkanforge.config.VulkanForgeConfig.OpenGLVersion;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import java.nio.file.*;
import java.util.function.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/** OpenGL backend: requests the configured core context at window creation and records what the driver actually gave. */
final class Gl46Context {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final StringBuilder REPORT = new StringBuilder();
    private static OpenGLVersion requested = OpenGLVersion.DEFAULT;
    private Gl46Context() { }

    /**
     * Forge's early window already tries 4.6 down to 3.2, so its context is the best the driver offers.
     * Only when earlyWindowControl is off (vanilla's 3.2 request) is a too-old context replaced by an explicit request.
     */
    static long window(long legacy, OpenGLVersion version, IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        select(version);
        report("REQUESTED_GL=" + version.label);
        if (version == OpenGLVersion.DEFAULT) return legacy;
        GLFW.glfwMakeContextCurrent(legacy); GL.createCapabilities();
        String current = describe();
        report("WINDOW_GL=" + current + " earlyWindowControl=" + FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL));
        if (atLeast(version) || FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL)) return legacy;
        long created = 0;
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, version.major);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, version.minor);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            created = GLFW.glfwCreateWindow(width.getAsInt(), height.getAsInt(), title.get(), monitor.getAsLong(), 0);
            if (created == 0) throw new IllegalStateException("driver refused an OpenGL " + version.label + " core context");
            GLFW.glfwMakeContextCurrent(created); GL.createCapabilities();
            report("RECREATED_GL=" + describe());
            GLFW.glfwDestroyWindow(legacy);
            LOGGER.info("Replaced Minecraft's OpenGL {} window with a requested {} core context", current, version.label);
            return created;
        } catch (RuntimeException error) {
            if (created != 0) GLFW.glfwDestroyWindow(created);
            GLFW.glfwMakeContextCurrent(legacy); GL.createCapabilities();
            report("RECREATE_FAILED=" + error.getMessage());
            LOGGER.warn("Could not request OpenGL {}; keeping the {} context", version.label, current, error);
            return legacy;
        }
    }

    static void select(OpenGLVersion version) {
        requested = version;
        GlCompat.configure(version == OpenGLVersion.DEFAULT ? 6 : version.minor);
    }

    /** Render thread, current context. Null when the selected terrain renderer can run, else the reason. */
    static String terrainUnsupported() {
        if (requested == OpenGLVersion.DEFAULT) return "Default OpenGL uses vanilla terrain";
        var caps = GL.getCapabilities();
        if (!caps.OpenGL43 || !atLeast(requested)) return "context is OpenGL " + describe() + ", " + requested.label + " required";
        report("GL_BACKEND_PATHS " + GlCompat.paths());
        return null;
    }

    static String describe() {
        return glGetInteger(GL_MAJOR_VERSION) + "." + glGetInteger(GL_MINOR_VERSION) + " (" + glGetString(GL_RENDERER) + ", " + glGetString(GL_VERSION) + ")";
    }

    private static boolean atLeast(OpenGLVersion version) {
        int major = glGetInteger(GL_MAJOR_VERSION), minor = glGetInteger(GL_MINOR_VERSION);
        return major > version.major || major == version.major && minor >= version.minor;
    }

    /** logs/vulkanforge/gl46-report.txt; rewritten on every addition so a crash still leaves the latest state. */
    static synchronized void report(String line) {
        REPORT.append(line).append('\n');
        try {
            String offscreen = System.getProperty("vulkanforge.offscreenReportDir");
            Path logs = offscreen == null ? FMLPaths.GAMEDIR.get().resolve("logs/vulkanforge") : Path.of(offscreen);
            Files.createDirectories(logs);
            Files.writeString(logs.resolve("gl46-report.txt"), REPORT);
        } catch (Exception error) { LOGGER.warn("Could not write gl46-report.txt", error); }
    }
}
