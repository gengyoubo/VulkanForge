package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;

final class VulkanContext implements AutoCloseable {
    final int apiVersion;
    VkInstance instance;
    VkPhysicalDevice physical;
    VkDevice device;
    VkQueue queue;
    DeviceCapabilities capabilities;
    AsyncUploads uploads;
    GpuMemory memory;
    /** Opt-in VK_NV_* extensions that were requested; {@link #nvidia} holds what the device actually enabled. */
    final java.util.Set<String> requestedNvidia;
    NvidiaExtensions nvidia = new NvidiaExtensions();
    /** Terrain draws through NV task/mesh shaders instead of compute culling + indirect count. */
    boolean meshShaderNV;
    boolean pushDescriptors, pushDescriptorCore;
    private boolean requestPipelineStatistics;
    boolean pipelineStatisticsEnabled;
    int queueFamily = -1;
    long surface;
    private long messenger;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private final AtomicInteger validationErrors = new AtomicInteger();
    private boolean interop;
    private byte[] deviceUUID;

    VulkanContext() { this(requestedApiVersion()); }

    VulkanContext(int apiVersion) { this(apiVersion, System.getProperty("vulkanforge.nvidiaExtensions", "")); }

    /** nvidiaExtensions: comma-separated VK_NV_* names selected in the config; blank for none. */
    VulkanContext(int apiVersion, String nvidiaExtensions) {
        this.requestedNvidia = NvidiaExtensions.parse(nvidiaExtensions);
        if (apiVersion != VK_API_VERSION_1_2 && apiVersion != VK_API_VERSION_1_3 && apiVersion != VK_API_VERSION_1_4)
            throw new IllegalArgumentException("Unsupported Vulkan API version: " + version(apiVersion));
        this.apiVersion = apiVersion;
    }
    /** Test-only opt-in; normal renderer constructors do not enable additional device features. */
    VulkanContext(int apiVersion, String nvidiaExtensions, boolean pipelineStatistics) {
        this(apiVersion,nvidiaExtensions);requestPipelineStatistics=pipelineStatistics;
    }

    static int requestedApiVersion() {
        return switch (System.getProperty("vulkanforge.vulkanVersion", "1.4")) {
            case "1.2" -> VK_API_VERSION_1_2;
            case "1.3" -> VK_API_VERSION_1_3;
            case "1.4" -> VK_API_VERSION_1_4;
            default -> throw new IllegalArgumentException("vulkanforge.vulkanVersion must be 1.2, 1.3 or 1.4");
        };
    }

    void initializeInterop(byte[] uuid, boolean requireValidation) {
        if(instance!=null)throw new IllegalStateException("Vulkan context already initialized; its interop policy cannot change");
        interop = true; deviceUUID = uuid;
        initialize(0, VK.getInstanceVersionSupported(), requireValidation);
    }
    boolean interopEnabled(){return interop;}

    void attachWin32(long hwnd, long hinstance) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkWin32SurfaceCreateInfoKHR.calloc(stack).sType$Default().hwnd(hwnd)
                    .hinstance(hinstance);
            var handle = stack.mallocLong(1);
            check(org.lwjgl.vulkan.KHRWin32Surface.vkCreateWin32SurfaceKHR(instance, info, null, handle), "vkCreateWin32SurfaceKHR(main window)");
            surface = handle.get(0);
            var supported = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceSupportKHR(physical, queueFamily, surface, supported), "main window present support");
            if (supported.get(0) == 0) throw new IllegalStateException("Selected GPU cannot present to the Minecraft window");
        }
    }

    void initialize(long window, int loaderVersion, boolean requireValidation) {
        if(instance!=null)throw new IllegalStateException("Vulkan context already initialized");
        if (loaderVersion < apiVersion)
            throw new IllegalStateException("Vulkan " + version(apiVersion) + " loader required; found " + version(loaderVersion));
        boolean offscreen = window == NULL;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean validation = !Boolean.getBoolean("vulkanforge.disableValidation") && hasLayer(stack, "VK_LAYER_KHRONOS_validation");
            if (requireValidation && !validation) throw new IllegalStateException("VK_LAYER_KHRONOS_validation is not installed (install Vulkan SDK)");
            boolean debug = validation && hasInstanceExtension(stack, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (requireValidation && !debug) throw new IllegalStateException("VK_EXT_debug_utils is unavailable");
            System.out.println("VALIDATION=" + (debug ? "ENABLED" : "UNAVAILABLE"));

            PointerBuffer glfwExtensions = interop ? stack.pointers(stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME), stack.UTF8(org.lwjgl.vulkan.KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME))
                    : offscreen ? null : glfwGetRequiredInstanceExtensions();
            if (!offscreen && glfwExtensions == null) throw new IllegalStateException("GLFW Vulkan surface extensions are unavailable");
            PointerBuffer extensions = stack.mallocPointer((glfwExtensions == null ? 0 : glfwExtensions.remaining()) + (debug ? 1 : 0));
            if (glfwExtensions != null) extensions.put(glfwExtensions);
            if (debug) extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            extensions.flip();

            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("VulkanForge laboratory"))
                    .applicationVersion(VK_MAKE_VERSION(0, 1, 0)).pEngineName(stack.UTF8("VulkanForge"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0)).apiVersion(apiVersion);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .pApplicationInfo(app).ppEnabledExtensionNames(extensions);
            VkDebugUtilsMessengerCreateInfoEXT debugInfo = null;
            if (debug) {
                debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
                    if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) validationErrors.incrementAndGet();
                    System.err.println("[Vulkan validation] " + VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                    return VK_FALSE;
                });
                debugInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(debugCallback);
                info.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"))).pNext(debugInfo.address());
            }
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkCreateInstance(info, null, pointer), "vkCreateInstance");
            instance = new VkInstance(pointer.get(0), info);
            LongBuffer handle = stack.mallocLong(1);
            if (debug) {
                check(vkCreateDebugUtilsMessengerEXT(instance, debugInfo, null, handle), "vkCreateDebugUtilsMessengerEXT");
                messenger = handle.get(0);
            }
            if (!offscreen) {
                check(glfwCreateWindowSurface(instance, window, null, handle), "glfwCreateWindowSurface");
                surface = handle.get(0);
            }
            selectDevice(stack);

            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0f));
            // Vulkan 1.2 uses extension feature structures, never the promoted Vulkan 1.3 structure.
            long renderingFeatures;
            if (apiVersion >= VK_API_VERSION_1_3) {
                var enabled = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default().dynamicRendering(true).synchronization2(true);
                if (apiVersion >= VK_API_VERSION_1_4)
                    enabled.pNext(VkPhysicalDeviceVulkan14Features.calloc(stack).sType$Default().pushDescriptor(
                            !Boolean.getBoolean("vulkanforge.disablePushDescriptors")).address());
                renderingFeatures = enabled.address();
            } else {
                var sync = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default().synchronization2(true);
                renderingFeatures = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default()
                        .dynamicRendering(true).pNext(sync.address()).address();
            }
            boolean pushExtension = hasDeviceExtension(physical, stack, org.lwjgl.vulkan.KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
            capabilities = DeviceCapabilities.query(physical, stack, apiVersion, pushExtension);
            pushDescriptors = capabilities.pushDescriptor() && capabilities.maxPushDescriptors() >= 3 && !Boolean.getBoolean("vulkanforge.disablePushDescriptors");
            // The KHR extension has no feature bit on 1.3. Never chain a Vulkan 1.4 structure on that path.
            if (apiVersion >= VK_API_VERSION_1_4)
                VkPhysicalDeviceVulkan14Features.create(VkPhysicalDeviceVulkan13Features.create(renderingFeatures).pNext()).pushDescriptor(pushDescriptors);
            boolean driven = capabilities.gpuDriven() && !Boolean.getBoolean("vulkanforge.disableGpuDriven");
            var enabled12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(renderingFeatures)
                    .timelineSemaphore(capabilities.timeline()).drawIndirectCount(driven);
            var enabled11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default().pNext(enabled12.address()).shaderDrawParameters(driven);
            var core = VkPhysicalDeviceFeatures.calloc(stack).multiDrawIndirect(driven).drawIndirectFirstInstance(driven);
            if(requestPipelineStatistics) {
                var supported=VkPhysicalDeviceFeatures.calloc(stack);vkGetPhysicalDeviceFeatures(physical,supported);
                pipelineStatisticsEnabled=supported.pipelineStatisticsQuery();core.pipelineStatisticsQuery(pipelineStatisticsEnabled);
            }
            nvidia = NvidiaExtensions.select(physical, stack, requestedNvidia, name -> hasDeviceExtension(physical, stack, name));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queueInfo)
                    .pNext(nvidia.chain(stack, enabled11.address())).pEnabledFeatures(core);
            java.util.List<String> deviceExtensions = new java.util.ArrayList<>();
            if (apiVersion < VK_API_VERSION_1_3) {
                deviceExtensions.add(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
                deviceExtensions.add(KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
            }
            if (!offscreen || interop) deviceExtensions.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            if (interop) {
                deviceExtensions.add(org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
                deviceExtensions.add(org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
            }
            if (pushDescriptors && pushExtension) deviceExtensions.add(org.lwjgl.vulkan.KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
            deviceExtensions.addAll(nvidia.enabled);
            if (!deviceExtensions.isEmpty()) {
                var names = stack.mallocPointer(deviceExtensions.size()); for (String name : deviceExtensions) names.put(stack.UTF8(name)); names.flip();
                deviceInfo.ppEnabledExtensionNames(names);
            }
            check(vkCreateDevice(physical, deviceInfo, null, pointer), "vkCreateDevice");
            device = new VkDevice(pointer.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, queueFamily, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
            pushDescriptorCore = apiVersion >= VK_API_VERSION_1_4 && device.getCapabilities().vkCmdPushDescriptorSet != 0;
            pushDescriptors &= pushDescriptorCore || device.getCapabilities().vkCmdPushDescriptorSetKHR != 0;
            System.out.println("INSTANCE_API=" + version(app.apiVersion()));
            System.out.println("RENDERING_COMMAND_PATH=" + (apiVersion < VK_API_VERSION_1_3 ? "KHR_1_2" : "CORE_1_3"));
            System.out.println("PUSH_DESCRIPTOR_PATH=" + (pushDescriptors ? pushDescriptorCore ? "VULKAN14_CORE" : "KHR_ALIAS" : "LEGACY"));
            System.out.println("DEVICE_CAPABILITIES=" + capabilities);
            System.out.println("GPU_DRIVEN_ENABLED=" + driven);
            System.out.println("PUSH_DESCRIPTORS_ENABLED=" + pushDescriptors);
            // The mesh path reuses the GPU-driven arena and section bounds, so it needs the same capabilities.
            meshShaderNV = nvidia.has(NvidiaExtensions.MESH_SHADER) && driven && !Boolean.getBoolean("vulkanforge.disableMeshShader");
            System.out.println("NVIDIA_EXTENSIONS=" + nvidia);
            System.out.println("MESH_SHADER_NV_ENABLED=" + meshShaderNV);
            memory = new GpuMemory(this);
            uploads = new AsyncUploads(this, capabilities.timeline() && !Boolean.getBoolean("vulkanforge.disableUploadTimeline"));
            System.out.println("DEVICE_CREATED=true");
        }
    }

    private void selectDevice(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
        if (count.get(0) == 0) throw new IllegalStateException("No Vulkan physical device found");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
        int best = -1;
        String selected = null;
        for (int i = 0; i < devices.remaining(); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(candidate, props);
            boolean api = props.apiVersion() >= apiVersion;
            boolean offscreen = surface == NULL;
            boolean swapchain = !offscreen && hasDeviceExtension(candidate, stack, VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            int family = findQueueFamily(candidate, stack);
            boolean dynamicRendering = false, synchronization2 = false;
            if (apiVersion >= VK_API_VERSION_1_3 && props.apiVersion() >= VK_API_VERSION_1_3) {
                VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
                VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(features13.address());
                vkGetPhysicalDeviceFeatures2(candidate, features);
                dynamicRendering = features13.dynamicRendering();
                synchronization2 = features13.synchronization2();
            } else if (hasDeviceExtension(candidate, stack, KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)
                    && hasDeviceExtension(candidate, stack, KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME)) {
                var sync = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default();
                var rendering = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default().pNext(sync.address());
                vkGetPhysicalDeviceFeatures2(candidate, VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(rendering.address()));
                dynamicRendering = rendering.dynamicRendering(); synchronization2 = sync.synchronization2();
            }
            boolean presentation = false;
            if (!offscreen && swapchain && family >= 0) {
                check(vkGetPhysicalDeviceSurfaceFormatsKHR(candidate, surface, count, null), "surface formats");
                boolean formats = count.get(0) > 0;
                check(vkGetPhysicalDeviceSurfacePresentModesKHR(candidate, surface, count, null), "present modes");
                presentation = formats && count.get(0) > 0;
            }
            boolean suitable = api && family >= 0 && dynamicRendering && synchronization2 && (offscreen || (swapchain && presentation));
            if (interop) {
                var id = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
                var properties2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id.address());
                vkGetPhysicalDeviceProperties2(candidate, properties2);
                byte[] uuid = new byte[16]; id.deviceUUID().get(uuid);
                suitable &= java.util.Arrays.equals(uuid, deviceUUID)
                        && hasDeviceExtension(candidate, stack, VK_KHR_SWAPCHAIN_EXTENSION_NAME)
                        && hasDeviceExtension(candidate, stack, org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)
                        && hasDeviceExtension(candidate, stack, org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
            }
            System.out.printf("GPU[%d]=%s | API=%s | driverRaw=%s | vendor=0x%04x | device=0x%04x%n",
                    i, props.deviceNameString(), version(props.apiVersion()), Integer.toUnsignedString(props.driverVersion()), props.vendorID(), props.deviceID());
            System.out.printf("  requiredApi=%s apiSupported=%s swapchain=%s graphicsQueue=%d dynamicRendering=%s synchronization2=%s presentation=%s eligible=%s%n",
                    version(apiVersion), api, swapchain, family, dynamicRendering, synchronization2, presentation, suitable);
            int score = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 100 : 10;
            if (suitable && score > best) {
                physical = candidate;
                queueFamily = family;
                best = score;
                selected = props.deviceNameString();
            }
        }
        if (physical == null) throw new IllegalStateException("No eligible Vulkan " + version(apiVersion) + " GPU. Requires dynamicRendering, synchronization2 and a graphics queue; window tests also require VK_KHR_swapchain and presentation. Update the GPU driver or use a supported GPU.");
        System.out.println("SELECTED_GPU=" + selected);
    }

    private int findQueueFamily(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.ints(0), supported = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
        VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, queues);
        for (int i = 0; i < queues.remaining(); i++) {
            if (surface != NULL) check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, supported), "surface support");
            if (queues.get(i).queueCount() > 0 && (queues.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0 && (surface == NULL || supported.get(0) == VK_TRUE)) return i;
        }
        return -1;
    }

    private static boolean hasLayer(MemoryStack stack, String name) {
        IntBuffer count = stack.ints(0);
        check(vkEnumerateInstanceLayerProperties(count, null), "instance layers(count)");
        VkLayerProperties.Buffer props = VkLayerProperties.calloc(count.get(0), stack);
        check(vkEnumerateInstanceLayerProperties(count, props), "instance layers");
        for (VkLayerProperties prop : props) if (prop.layerNameString().equals(name)) return true;
        return false;
    }

    private static boolean hasInstanceExtension(MemoryStack stack, String name) {
        IntBuffer count = stack.ints(0);
        check(vkEnumerateInstanceExtensionProperties((String) null, count, null), "instance extensions(count)");
        VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0), stack);
        check(vkEnumerateInstanceExtensionProperties((String) null, count, props), "instance extensions");
        for (VkExtensionProperties prop : props) if (prop.extensionNameString().equals(name)) return true;
        return false;
    }

    private static boolean hasDeviceExtension(VkPhysicalDevice candidate, MemoryStack stack, String name) {
        IntBuffer count = stack.ints(0);
        check(vkEnumerateDeviceExtensionProperties(candidate, (String) null, count, null), "device extensions(count)");
        VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(candidate, (String) null, count, props), "device extensions");
        for (VkExtensionProperties prop : props) if (prop.extensionNameString().equals(name)) return true;
        return false;
    }

    static String version(int version) { return VK_VERSION_MAJOR(version) + "." + VK_VERSION_MINOR(version) + "." + VK_VERSION_PATCH(version); }
    static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult=" + result);
    }
    int validationErrors() { return validationErrors.get(); }
    boolean validationEnabled() { return messenger != 0; }

    @Override public void close() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            if (uploads != null) { uploads.close(); uploads = null; }
            if (memory != null) { memory.close(); memory = null; }
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            if (surface != NULL) { vkDestroySurfaceKHR(instance, surface, null); surface = NULL; }
            if (messenger != NULL) { vkDestroyDebugUtilsMessengerEXT(instance, messenger, null); messenger = NULL; }
            vkDestroyInstance(instance, null);
            instance = null;
        }
        if (debugCallback != null) { debugCallback.free(); debugCallback = null; }
    }
}
