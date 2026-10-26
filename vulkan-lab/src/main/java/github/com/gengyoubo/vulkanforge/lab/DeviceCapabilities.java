package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK11.*;

/** Query optional features before enabling them; a core API version alone is not a feature-enable request. */
record DeviceCapabilities(boolean timeline, boolean indirectCount, boolean multiDraw, boolean firstInstance,
                          boolean shaderDrawParameters, boolean bufferAddress, boolean descriptorIndexing,
                          boolean scalarLayout, boolean localRead, boolean hostImageCopy, boolean pushDescriptor,
                          long maxIndirectDraws, long maxPushDescriptors) {
    static DeviceCapabilities query(VkPhysicalDevice device, MemoryStack stack, int apiVersion, boolean pushExtension) {
        boolean core14 = apiVersion >= org.lwjgl.vulkan.VK14.VK_API_VERSION_1_4;
        var f14 = core14 ? VkPhysicalDeviceVulkan14Features.calloc(stack).sType$Default() : null;
        var f13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
        if (f14 != null) f13.pNext(f14.address());
        var f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(f13.address());
        var f11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default().pNext(f12.address());
        var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(f11.address());
        vkGetPhysicalDeviceFeatures2(device, features);
        var props14 = core14 ? VkPhysicalDeviceVulkan14Properties.calloc(stack).sType$Default() : null;
        var pushProps = !core14 && pushExtension ? VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default() : null;
        var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
        if (props14 != null) props.pNext(props14.address());
        else if (pushProps != null) props.pNext(pushProps.address());
        vkGetPhysicalDeviceProperties2(device, props);
        long maxPush = props14 != null ? Integer.toUnsignedLong(props14.maxPushDescriptors())
                : pushProps != null ? Integer.toUnsignedLong(pushProps.maxPushDescriptors()) : 0;
        return new DeviceCapabilities(f12.timelineSemaphore(), f12.drawIndirectCount(), features.features().multiDrawIndirect(),
                features.features().drawIndirectFirstInstance(), f11.shaderDrawParameters(), f12.bufferDeviceAddress(), f12.descriptorIndexing(),
                f12.scalarBlockLayout(), f14 != null && f14.dynamicRenderingLocalRead(), f14 != null && f14.hostImageCopy(),
                f14 != null ? f14.pushDescriptor() : pushExtension, Integer.toUnsignedLong(props.properties().limits().maxDrawIndirectCount()), maxPush);
    }
    boolean gpuDriven() { return indirectCount && multiDraw && firstInstance && shaderDrawParameters && maxIndirectDraws >= GpuCuller.CAPACITY; }
}
