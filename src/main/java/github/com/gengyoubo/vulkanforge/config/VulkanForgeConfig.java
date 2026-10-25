package github.com.gengyoubo.vulkanforge.config;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config (config/vulkanforge-client.toml), edited from the Mods list "Config" button.
 * Values are stored only; the renderer does not read them yet. All of them are startup choices.
 */
public final class VulkanForgeConfig {
    public enum Backend { VULKAN, OPENGL }

    public enum VulkanVersion {
        V1_0("1.0"), V1_1("1.1"), V1_2("1.2"), V1_3("1.3"), V1_4("1.4");
        public final String label;
        VulkanVersion(String label) { this.label = label; }
    }

    public enum OpenGLVersion {
        DEFAULT("default"), GL_4_2("4.2"), GL_4_3("4.3"), GL_4_4("4.4"), GL_4_5("4.5"), GL_4_6("4.6");
        public final String label;
        OpenGLVersion(String label) { this.label = label; }
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
        builder.comment("Reserved settings: saved but not applied yet. Changes will require a restart once implemented.").push("renderer");
        BACKEND = builder.comment("Rendering backend: VULKAN or OPENGL").defineEnum("backend", Backend.VULKAN);
        builder.pop();

        builder.push("vulkan");
        VULKAN_VERSION = builder.comment("Requested Vulkan API version (V1_0 .. V1_4). Only used when backend = VULKAN.")
                .defineEnum("version", VulkanVersion.V1_4);
        builder.comment("NVIDIA-only device extensions. Ignored on other vendors and when backend = OPENGL.").push("nvidiaExtensions");
        for (NvidiaExtension extension : NvidiaExtension.values()) {
            NVIDIA_EXTENSIONS.put(extension, builder.comment("Enable " + extension.extensionName).define(extension.key(), false));
        }
        builder.pop(2);

        builder.push("opengl");
        OPENGL_VERSION = builder.comment("Requested OpenGL core profile version. DEFAULT keeps Minecraft's own context request. Only used when backend = OPENGL.")
                .defineEnum("version", OpenGLVersion.DEFAULT);
        builder.pop();
        SPEC = builder.build();
    }

    private VulkanForgeConfig() { }
}
