package github.com.gengyoubo.vulkanforge.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.vulkanforge.bridge.RenderMode;
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
 * Three modes: GL, GL_VK and experimental VK. Vulkan 1.2..1.4 and OpenGL 4.3..4.6 are API selections.
 */
public final class VulkanForgeConfig {
    /** Legacy startup aliases. VULKAN means GL_VK, as it did before the mode split. */
    @Deprecated public enum Backend { VULKAN, OPENGL }
    //推荐1.3
    public enum VulkanVersion {
        V1_2(2, true), V1_3(3, true), V1_4(4, true);
        public final String label;
        /** Packed VkApplicationInfo.apiVersion, passed across the isolated backend boundary as an int. */
        public final int apiVersion;
        /** Whether this version has a rendering implementation. Device support is checked separately. */
        public final boolean implemented;
        VulkanVersion(int minor, boolean implemented) {
            this.label = "1." + minor; this.apiVersion = (1 << 22) | (minor << 12); this.implemented = implemented;
        }
    }
    //推荐4.5
    public enum OpenGLVersion {
        DEFAULT("default", 0, 0, true), GL_4_3("4.3", 4, 3, true), GL_4_4("4.4", 4, 4, true), GL_4_5("4.5", 4, 5, true), GL_4_6("4.6", 4, 6, true);
        public final String label;
        /** Requested core context version; 0.0 keeps Minecraft's own request. */
        public final int major, minor;
        /** Whether this version has a rendering implementation. Device support is checked separately. */
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
    public static final ForgeConfigSpec.EnumValue<RenderMode> MODE;
    public static final ForgeConfigSpec.EnumValue<VulkanVersion> VULKAN_VERSION;
    public static final ForgeConfigSpec.EnumValue<OpenGLVersion> OPENGL_VERSION;
    public static final Map<NvidiaExtension, ForgeConfigSpec.BooleanValue> NVIDIA_EXTENSIONS = new LinkedHashMap<>();

    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.comment("Startup settings: changes take effect after restarting the game.").push("renderer");
        MODE = builder.comment("Running mode: GL (OpenGL), GL_VK (hybrid with GL fallback), VK (native Vulkan; experimental coverage gate, no GL fallback).")
                .defineEnum("mode", RenderMode.GL_VK);
        builder.pop();

        builder.push("vulkan");
        VULKAN_VERSION = builder.comment("Requested Vulkan API version (V1_2 .. V1_4). Used by GL_VK and VK. V1_2 uses KHR dynamic rendering/synchronization2 aliases.")
                .defineEnum("version", VulkanVersion.V1_4);
        builder.comment("NVIDIA-only Vulkan extensions. Ignored on other vendors and in GL mode.").push("nvidiaExtensions");
        for (NvidiaExtension extension : NvidiaExtension.values()) {
            NVIDIA_EXTENSIONS.put(extension, builder.comment("Enable " + extension.extensionName).define(extension.key(), false));
        }
        builder.pop(2);

        builder.push("opengl");
        OPENGL_VERSION = builder.comment("GL mode context/terrain path. GL_4_3 .. GL_4_6 enable the custom renderer; DEFAULT keeps vanilla GL. Ignored by VK.")
                .defineEnum("version", OpenGLVersion.GL_4_6);
        builder.pop();
        SPEC = builder.build();
    }

    /** What this launch actually uses; the Mods-list screen only changes the next launch. */
    public record Startup(RenderMode mode, VulkanVersion vulkanVersion, OpenGLVersion openGLVersion, Set<NvidiaExtension> nvidiaExtensions) {
        public static final Startup DEFAULTS = new Startup(RenderMode.GL_VK, VulkanVersion.V1_4, OpenGLVersion.GL_4_6, Set.of());
        public Startup { nvidiaExtensions = nvidiaExtensions.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(nvidiaExtensions)); }
        public Startup(RenderMode mode, VulkanVersion vulkanVersion, OpenGLVersion openGLVersion) { this(mode, vulkanVersion, openGLVersion, Set.of()); }
        @Deprecated public Startup(Backend backend,VulkanVersion vulkanVersion,OpenGLVersion openGLVersion){this(backend==Backend.OPENGL?RenderMode.GL:RenderMode.GL_VK,vulkanVersion,openGLVersion);}
        @Deprecated public Backend backend(){return mode==RenderMode.GL?Backend.OPENGL:Backend.VULKAN;}
        /** Comma-separated VK_NV_* names handed to the isolated backend; the backend still checks vendor, driver list and feature bits. */
        public String nvidiaExtensionNames() {
            if (!mode.usesVulkan()) return "";
            StringJoiner names = new StringJoiner(",");
            for (NvidiaExtension extension : nvidiaExtensions) names.add(extension.extensionName);
            return names.toString();
        }
        /** Vulkan Surface/Swapchain main window (Windows only, also gated by -Dvulkanforge.directPresent). */
        public boolean vulkanMainWindow() { return mode == RenderMode.GL_VK && vulkanVersion.implemented; }
        /** In-context OpenGL 4.3+ terrain renderer instead of the Vulkan bridge. */
        public boolean gl46Terrain() { return mode == RenderMode.GL && openGLVersion != OpenGLVersion.DEFAULT; }
        @Override public String toString() {
            if (mode == RenderMode.GL) return "GL " + openGLVersion.label;
            return (mode==RenderMode.GL_VK?"GL-VK ":"VK ") + vulkanVersion.label + (nvidiaExtensions.isEmpty() ? "" : " +" + nvidiaExtensionNames());
        }
    }
    private static Startup startup;

    /**
     * Minecraft creates its Window before Forge loads client configs, so the first call reads the TOML file directly.
     * Legacy renderer.backend is migrated without changing its meaning. Overrides: GL:GL_4_6, GL_VK:V1_4, VK:V1_4.
     * OPENGL and VULKAN remain legacy aliases for GL and GL_VK.
     * overrides the file for this launch only, e.g. for smoke runs.
     */
    public static synchronized Startup startup() {
        if (startup == null) startup = override(read(FMLPaths.CONFIGDIR.get().resolve("vulkanforge-client.toml")), System.getProperty("vulkanforge.startup"));
        return startup;
    }

    static Startup override(Startup file, String value) {
        if (value == null || value.isBlank()) return file;
        String[] parts = value.split(":", 2);
        RenderMode mode = RenderMode.parse(parts[0],file.mode());
        String version = parts.length > 1 ? parts[1] : null;
        return mode.usesVulkan() ? new Startup(mode, value(version, file.vulkanVersion()), file.openGLVersion(), file.nvidiaExtensions())
                : new Startup(mode, file.vulkanVersion(), value(version, file.openGLVersion()), file.nvidiaExtensions());
    }

    static Startup read(Path file) {
        if (!Files.isRegularFile(file)) return Startup.DEFAULTS;
        try (CommentedFileConfig config = CommentedFileConfig.builder(file).sync().build()) {
            config.load();
            Set<NvidiaExtension> nvidia = EnumSet.noneOf(NvidiaExtension.class);
            for (NvidiaExtension extension : NvidiaExtension.values())
                if (Boolean.TRUE.equals(config.get("vulkan.nvidiaExtensions." + extension.key()))) nvidia.add(extension);
            RenderMode mode=RenderMode.parse(config.get("renderer.mode"),RenderMode.parse(config.get("renderer.backend"),Startup.DEFAULTS.mode()));
            Startup result=new Startup(mode,
                    value(config.get("vulkan.version"), Startup.DEFAULTS.vulkanVersion()),
                    value(config.get("opengl.version"), Startup.DEFAULTS.openGLVersion()), nvidia);
            if(!config.contains("renderer.mode")||config.contains("renderer.backend")||!mode.name().equals(config.get("renderer.mode"))) {
                config.set("renderer.mode",mode.name());config.remove("renderer.backend");
                config.setComment("renderer.mode","GL / GL_VK / VK; legacy VULKAN migrates to GL_VK. Restart required.");
                try{config.save();}catch(RuntimeException error){com.mojang.logging.LogUtils.getLogger().warn("Could not persist renderer.mode migration ({})",error.getClass().getSimpleName());}
            }
            return result;
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
