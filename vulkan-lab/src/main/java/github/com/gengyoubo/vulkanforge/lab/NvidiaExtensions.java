package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.*;
import java.util.*;
import java.util.function.Predicate;
import static org.lwjgl.vulkan.NVDeviceDiagnosticsConfig.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * Opt-in VK_NV_* device extensions from the config screen. One is enabled only when it was requested, the GPU is NVIDIA,
 * the driver lists it, and its feature bit (if it has one) is reported; anything else is logged and skipped.
 */
final class NvidiaExtensions {
    static final int VENDOR_ID = 0x10de;
    static final String LOW_LATENCY_2 = NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME,
            DIAGNOSTIC_CHECKPOINTS = NVDeviceDiagnosticCheckpoints.VK_NV_DEVICE_DIAGNOSTIC_CHECKPOINTS_EXTENSION_NAME,
            DIAGNOSTICS_CONFIG = VK_NV_DEVICE_DIAGNOSTICS_CONFIG_EXTENSION_NAME,
            MESH_SHADER = NVMeshShader.VK_NV_MESH_SHADER_EXTENSION_NAME,
            DEVICE_GENERATED_COMMANDS = NVDeviceGeneratedCommands.VK_NV_DEVICE_GENERATED_COMMANDS_EXTENSION_NAME,
            REPRESENTATIVE_FRAGMENT_TEST = NVRepresentativeFragmentTest.VK_NV_REPRESENTATIVE_FRAGMENT_TEST_EXTENSION_NAME,
            SHADING_RATE_IMAGE = NVShadingRateImage.VK_NV_SHADING_RATE_IMAGE_EXTENSION_NAME;
    private static final List<String> KNOWN = List.of(LOW_LATENCY_2, DIAGNOSTIC_CHECKPOINTS, DIAGNOSTICS_CONFIG, MESH_SHADER,
            DEVICE_GENERATED_COMMANDS, REPRESENTATIVE_FRAGMENT_TEST, SHADING_RATE_IMAGE);

    final Set<String> enabled = new LinkedHashSet<>();
    private final Map<String, String> status = new LinkedHashMap<>();

    /** Comma-separated extension names, e.g. from -Dvulkanforge.nvidiaExtensions. Blank means none. */
    static Set<String> parse(String names) {
        Set<String> result = new LinkedHashSet<>();
        if (names != null) for (String name : names.split(",")) if (!name.isBlank()) result.add(name.trim());
        return result;
    }

    static NvidiaExtensions select(VkPhysicalDevice device, MemoryStack stack, Set<String> requested, Predicate<String> available) {
        NvidiaExtensions result = new NvidiaExtensions();
        if (requested.isEmpty()) return result;
        var props = VkPhysicalDeviceProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceProperties(device, props);
        boolean nvidia = props.vendorID() == VENDOR_ID;
        for (String name : requested) {
            String state;
            if (!KNOWN.contains(name)) state = "UNKNOWN";
            else if (!nvidia) state = "SKIPPED_NOT_NVIDIA";
            else if (!available.test(name)) state = "NOT_REPORTED_BY_DRIVER";
            else if (!featureSupported(device, stack, name)) state = "FEATURE_NOT_SUPPORTED";
            else { result.enabled.add(name); state = "ENABLED"; }
            result.status.put(name, state);
        }
        return result;
    }

    private static boolean featureSupported(VkPhysicalDevice device, MemoryStack stack, String name) {
        Struct<?> feature = feature(stack, name, false);
        if (feature == null) return true; // Extensions without a feature struct only need to be listed.
        vkGetPhysicalDeviceFeatures2(device, VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(feature.address()));
        return switch (name) {
            case MESH_SHADER -> ((VkPhysicalDeviceMeshShaderFeaturesNV) feature).meshShader() && ((VkPhysicalDeviceMeshShaderFeaturesNV) feature).taskShader();
            case DEVICE_GENERATED_COMMANDS -> ((VkPhysicalDeviceDeviceGeneratedCommandsFeaturesNV) feature).deviceGeneratedCommands();
            case REPRESENTATIVE_FRAGMENT_TEST -> ((VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV) feature).representativeFragmentTest();
            case SHADING_RATE_IMAGE -> ((VkPhysicalDeviceShadingRateImageFeaturesNV) feature).shadingRateImage();
            case DIAGNOSTICS_CONFIG -> ((VkPhysicalDeviceDiagnosticsConfigFeaturesNV) feature).diagnosticsConfig();
            default -> true;
        };
    }

    /** Feature struct for one extension, zeroed for a query or with its feature bits set for device creation. */
    private static Struct<?> feature(MemoryStack stack, String name, boolean enable) {
        return switch (name) {
            case MESH_SHADER -> VkPhysicalDeviceMeshShaderFeaturesNV.calloc(stack).sType$Default().taskShader(enable).meshShader(enable);
            case DEVICE_GENERATED_COMMANDS -> VkPhysicalDeviceDeviceGeneratedCommandsFeaturesNV.calloc(stack).sType$Default().deviceGeneratedCommands(enable);
            case REPRESENTATIVE_FRAGMENT_TEST -> VkPhysicalDeviceRepresentativeFragmentTestFeaturesNV.calloc(stack).sType$Default().representativeFragmentTest(enable);
            case SHADING_RATE_IMAGE -> VkPhysicalDeviceShadingRateImageFeaturesNV.calloc(stack).sType$Default().shadingRateImage(enable);
            case DIAGNOSTICS_CONFIG -> VkPhysicalDeviceDiagnosticsConfigFeaturesNV.calloc(stack).sType$Default().diagnosticsConfig(enable);
            default -> null;
        };
    }

    /** Prepends enabled feature structs (and the Aftermath create info) to a VkDeviceCreateInfo pNext chain. */
    long chain(MemoryStack stack, long next) {
        for (String name : enabled) {
            Struct<?> feature = feature(stack, name, true);
            if (feature != null) { org.lwjgl.system.MemoryUtil.memPutAddress(feature.address() + VkBaseOutStructure.PNEXT, next); next = feature.address(); }
        }
        if (enabled.contains(DIAGNOSTICS_CONFIG)) next = VkDeviceDiagnosticsConfigCreateInfoNV.calloc(stack).sType$Default().pNext(next)
                .flags(VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_SHADER_DEBUG_INFO_BIT_NV | VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_RESOURCE_TRACKING_BIT_NV
                        | VK_DEVICE_DIAGNOSTICS_CONFIG_ENABLE_AUTOMATIC_CHECKPOINTS_BIT_NV).address();
        return next;
    }

    boolean has(String name) { return enabled.contains(name); }
    @Override public String toString() { return status.isEmpty() ? "NONE_REQUESTED" : status.toString(); }
}
