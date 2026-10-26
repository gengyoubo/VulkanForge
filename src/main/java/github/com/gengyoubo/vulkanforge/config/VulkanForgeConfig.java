package github.com.gengyoubo.vulkanforge.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Client config (config/vulkanforge-client.toml), edited from the Mods list "Config" button.
 * All values are startup choices: {@link #startup()} snapshots them once, before the game window exists.
 * Implemented today: Vulkan 1.3/1.4 (main-window presentation) and OpenGL 4.6 (GPU-driven terrain).
 */
public final class VulkanForgeConfig {
    public enum Backend { VULKAN, OPENGL }
    //推荐1.3
    public enum VulkanVersion {
        V1_2(2, false), V1_3(3, true), V1_4(4, true);
        public final String label;
        /** Packed VkApplicationInfo.apiVersion, passed across the isolated backend boundary as an int. */
        public final int apiVersion;
        /** False: selectable and saved, but startup falls back to the vanilla OpenGL window. */
        public final boolean implemented;
        VulkanVersion(int minor, boolean implemented) {
            this.label = "1." + minor; this.apiVersion = (1 << 22) | (minor << 12); this.implemented = implemented;
        }
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
        VULKAN_VERSION = builder.comment("Requested Vulkan API version (V1_2 .. V1_4). V1_3 and V1_4 are implemented; V1_2 keeps the vanilla OpenGL window. Only used when backend = VULKAN.")
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
    public record Startup(Backend backend, VulkanVersion vulkanVersion, OpenGLVersion openGLVersion, Set<NvidiaExtension> nvidiaExtensions) {
        public static final Startup DEFAULTS = new Startup(Backend.VULKAN, VulkanVersion.V1_4, OpenGLVersion.DEFAULT, Set.of());
        public Startup { nvidiaExtensions = nvidiaExtensions.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(nvidiaExtensions)); }
        public Startup(Backend backend, VulkanVersion vulkanVersion, OpenGLVersion openGLVersion) { this(backend, vulkanVersion, openGLVersion, Set.of()); }
        /** Comma-separated VK_NV_* names handed to the isolated backend; the backend still checks vendor, driver list and feature bits. */
        public String nvidiaExtensionNames() {
            if (backend != Backend.VULKAN) return "";
            StringJoiner names = new StringJoiner(",");
            for (NvidiaExtension extension : nvidiaExtensions) names.add(extension.extensionName);
            return names.toString();
        }
        /** Vulkan Surface/Swapchain main window (Windows only, also gated by -Dvulkanforge.directPresent). */
        public boolean vulkanMainWindow() { return backend == Backend.VULKAN && vulkanVersion.implemented; }
        /** In-context OpenGL 4.6 terrain renderer instead of the Vulkan bridge. */
        public boolean gl46Terrain() { return backend == Backend.OPENGL && openGLVersion == OpenGLVersion.GL_4_6; }
        @Override public String toString() {
            if (backend != Backend.VULKAN) return "OPENGL " + openGLVersion.label;
            return "VULKAN " + vulkanVersion.label + (nvidiaExtensions.isEmpty() ? "" : " +" + nvidiaExtensionNames());
        }
    }
    private static Startup startup;

    /**
     * Minecraft creates its Window before Forge loads client configs, so the first call reads the TOML file directly.
     * Missing files or invalid values fall back to the spec defaults. -Dvulkanforge.startup=OPENGL:GL_4_6 (or VULKAN:V1_4)
     * overrides the file for this launch only, e.g. for smoke runs.
     */
    public static synchronized Startup startup() {
        if (startup == null) startup = override(read(FMLPaths.CONFIGDIR.get().resolve("vulkanforge-client.toml")), System.getProperty("vulkanforge.startup"));
        return startup;
    }

    private static Startup override(Startup file, String value) {
        if (value == null || value.isBlank()) return file;
        String[] parts = value.split(":", 2);
        Backend backend = value(parts[0], file.backend());
        String version = parts.length > 1 ? parts[1] : null;
        return backend == Backend.VULKAN ? new Startup(backend, value(version, file.vulkanVersion()), file.openGLVersion(), file.nvidiaExtensions())
                : new Startup(backend, file.vulkanVersion(), value(version, file.openGLVersion()), file.nvidiaExtensions());
    }

    private static Startup read(Path file) {
        if (!Files.isRegularFile(file)) return Startup.DEFAULTS;
        try (CommentedFileConfig config = CommentedFileConfig.builder(file).sync().build()) {
            config.load();
            Set<NvidiaExtension> nvidia = EnumSet.noneOf(NvidiaExtension.class);
            for (NvidiaExtension extension : NvidiaExtension.values())
                if (Boolean.TRUE.equals(config.get("vulkan.nvidiaExtensions." + extension.key()))) nvidia.add(extension);
            return new Startup(value(config.get("renderer.backend"), Startup.DEFAULTS.backend()),
                    value(config.get("vulkan.version"), Startup.DEFAULTS.vulkanVersion()),
                    value(config.get("opengl.version"), Startup.DEFAULTS.openGLVersion()), nvidia);
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
