package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK11.*;

/** Query optional features before enabling them; a core API version alone is not a feature-enable request. */
record DeviceCapabilities(boolean timeline, boolean indirectCount, boolean multiDraw, boolean firstInstance,
                          boolean shaderDrawParameters, boolean bufferAddress, boolean descriptorIndexing,
                          boolean scalarLayout, boolean localRead, boolean hostImageCopy, boolean pushDescriptor,
                          long maxIndirectDraws, long maxPushDescriptors) {
    static DeviceCapabilities query(VkPhysicalDevice device, MemoryStack stack) {
        var f14 = VkPhysicalDeviceVulkan14Features.calloc(stack).sType$Default();
        var f13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default().pNext(f14.address());
        var f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().pNext(f13.address());
        var f11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default().pNext(f12.address());
        var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(f11.address());
        vkGetPhysicalDeviceFeatures2(device, features);
        var props14 = VkPhysicalDeviceVulkan14Properties.calloc(stack).sType$Default();
        var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(props14.address()); vkGetPhysicalDeviceProperties2(device, props);
        return new DeviceCapabilities(f12.timelineSemaphore(), f12.drawIndirectCount(), features.features().multiDrawIndirect(),
                features.features().drawIndirectFirstInstance(), f11.shaderDrawParameters(), f12.bufferDeviceAddress(), f12.descriptorIndexing(),
                f12.scalarBlockLayout(), f14.dynamicRenderingLocalRead(), f14.hostImageCopy(), f14.pushDescriptor(), Integer.toUnsignedLong(props.properties().limits().maxDrawIndirectCount()), Integer.toUnsignedLong(props14.maxPushDescriptors()));
    }
    boolean gpuDriven() { return indirectCount && multiDraw && firstInstance && shaderDrawParameters && maxIndirectDraws >= GpuCuller.CAPACITY; }
}
