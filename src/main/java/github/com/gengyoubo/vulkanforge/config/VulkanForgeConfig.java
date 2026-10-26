package github.com.gengyoubo.vulkanforge.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Client config (config/vulkanforge-client.toml), edited from the Mods list "Config" button.
 * All values are startup choices: {@link #startup()} snapshots them once, before the game window exists.
 * Implemented today: Vulkan 1.4 (main-window presentation) and OpenGL 4.6 (GPU-driven terrain).
 */
public final class VulkanForgeConfig {
    public enum Backend { VULKAN, OPENGL }
    //推荐1.3
    public enum VulkanVersion {
        V1_2("1.2", false), V1_3("1.3", false), V1_4("1.4", true);
        public final String label;
        /** False: selectable and saved, but startup falls back to the vanilla OpenGL window. */
        public final boolean implemented;
        VulkanVersion(String label, boolean implemented) { this.label = label; this.implemented = implemented; }
    }
    //推荐4.5
    public enum OpenGLVersion {
        DEFAULT("default", 0, 0, true), GL_4_3("4.3", 4, 3, false), GL_4_4("4.4", 4, 4, false), GL_4_5("4.5", 4, 5, false), GL_4_6("4.6", 4, 6, true);
        public final String label;
        /** Requested core context version; 0.0 keeps Minecraft's own request. */
        public final int major, minor;
        /** False: the context is requested, but terrain stays on vanilla rendering. */
        public final boolean implemented;
        OpenGLVersion(String label, int major, int minor, boolean implemented) {
            this.label = label; this.major = major; this.minor = minor; this.implemented = implemented;
        }
    }

    /** NVIDIA-only Vulkan device extensions offered as opt-in switches. */
    public enum NvidiaExtension {
        LOW_LATENCY_2("VK_NV_low_latency2"),
        DEVICE_DIAGNOSTIC_CHECKPOINTS("VK_NV_device_diagnostic_checkpoints"),
        DEVICE_DIAGNOSTICS_CONFIG("VK_NV_device_diagnostics_config"),
        MESH_SHADER("VK_NV_mesh_shader"),
        DEVICE_GENERATED_COMMANDS("VK_NV_device_generated_commands"),
        REPRESENTATIVE_FRAGMENT_TEST("VK_NV_representative_fragment_test"),
        SHADING_RATE_IMAGE("VK_NV_shading_rate_image");
        public final String extensionName;
        NvidiaExtension(String extensionName) { this.extensionName = extensionName; }
        /** Config key and translation suffix, e.g. low_latency_2. */
        public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<Backend> BACKEND;
    public static final ForgeConfigSpec.EnumValue<VulkanVersion> VULKAN_VERSION;
    public static final ForgeConfigSpec.EnumValue<OpenGLVersion> OPENGL_VERSION;
    public static final Map<NvidiaExtension, ForgeConfigSpec.BooleanValue> NVIDIA_EXTENSIONS = new LinkedHashMap<>();

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.comment("Startup settings: changes take effect after restarting the game.").push("renderer");
        BACKEND = builder.comment("Rendering backend: VULKAN or OPENGL").defineEnum("backend", Backend.VULKAN);
        builder.pop();

        builder.push("vulkan");
        VULKAN_VERSION = builder.comment("Requested Vulkan API version (V1_2 .. V1_4). Only V1_4 is implemented; others keep the vanilla OpenGL window. Only used when backend = VULKAN.")
                .defineEnum("version", VulkanVersion.V1_4);
        builder.comment("NVIDIA-only device extensions. Ignored on other vendors and when backend = OPENGL.").push("nvidiaExtensions");
        for (NvidiaExtension extension : NvidiaExtension.values()) {
            NVIDIA_EXTENSIONS.put(extension, builder.comment("Enable " + extension.extensionName).define(extension.key(), false));
        }
        builder.pop(2);

        builder.push("opengl");
        OPENGL_VERSION = builder.comment("Requested OpenGL core profile version. DEFAULT keeps Minecraft's own context and vanilla terrain; GL_4_6 enables the GPU-driven terrain renderer (F8). Only used when backend = OPENGL.")
                .defineEnum("version", OpenGLVersion.DEFAULT);
        builder.pop();
        SPEC = builder.build();
    }

    /** What this launch actually uses; the Mods-list screen only changes the next launch. */
    public record Startup(Backend backend, VulkanVersion vulkanVersion, OpenGLVersion openGLVersion) {
        public static final Startup DEFAULTS = new Startup(Backend.VULKAN, VulkanVersion.V1_4, OpenGLVersion.DEFAULT);
        /** Vulkan Surface/Swapchain main window (Windows only, also gated by -Dvulkanforge.directPresent). */
        public boolean vulkanMainWindow() { return backend == Backend.VULKAN && vulkanVersion == VulkanVersion.V1_4; }
        /** In-context OpenGL 4.6 terrain renderer instead of the Vulkan bridge. */
        public boolean gl46Terrain() { return backend == Backend.OPENGL && openGLVersion == OpenGLVersion.GL_4_6; }
        @Override public String toString() {
            return backend == Backend.VULKAN ? "VULKAN " + vulkanVersion.label : "OPENGL " + openGLVersion.label;
        }
    }
    private static Startup startup;

    /**
     * Minecraft creates its Window before Forge loads client configs, so the first call reads the TOML file directly.
     * Missing files or invalid values fall back to the spec defaults.
     */
    public static synchronized Startup startup() {
        if (startup == null) startup = read(FMLPaths.CONFIGDIR.get().resolve("vulkanforge-client.toml"));
        return startup;
    }

    private static Startup read(Path file) {
        if (!Files.isRegularFile(file)) return Startup.DEFAULTS;
        try (CommentedFileConfig config = CommentedFileConfig.builder(file).sync().build()) {
            config.load();
            return new Startup(value(config.get("renderer.backend"), Startup.DEFAULTS.backend()),
                    value(config.get("vulkan.version"), Startup.DEFAULTS.vulkanVersion()),
                    value(config.get("opengl.version"), Startup.DEFAULTS.openGLVersion()));
        } catch (RuntimeException error) {
            return Startup.DEFAULTS;
        }
    }

    private static <E extends Enum<E>> E value(Object raw, E fallback) {
        if (raw == null) return fallback;
        try { return Enum.valueOf(fallback.getDeclaringClass(), raw.toString().trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { return fallback; }
    }

    private VulkanForgeConfig() { }
}
