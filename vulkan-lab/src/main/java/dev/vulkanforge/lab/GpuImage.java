package dev.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static dev.vulkanforge.lab.VulkanContext.check;

final class GpuImage implements AutoCloseable {
    private final VulkanContext context;
    final int width, height;
    long image, memory, view;
    GpuImage(VulkanContext context, int width, int height, int format, int usage, int aspect) {
        this.context = context;
        this.width = width; this.height = height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physical, format, props);
            if (aspect == VK_IMAGE_ASPECT_DEPTH_BIT && (props.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) == 0) throw new IllegalStateException("D32 depth attachment is unsupported");
            var handle = stack.mallocLong(1);
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                    .extent(e -> e.set(width, height, 1)).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateImage(context.device, info, null, handle), "vkCreateImage(world)"); image = handle.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(context.device, image, requirements);
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size())
                    .memoryTypeIndex(GpuBuffer.memoryType(context, stack, requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(context.device, allocation, null, handle), "vkAllocateMemory(world image)"); memory = handle.get(0);
            check(vkBindImageMemory(context.device, image, memory, 0), "vkBindImageMemory(world)");
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                    .subresourceRange(r -> r.aspectMask(aspect).levelCount(1).layerCount(1));
            check(vkCreateImageView(context.device, viewInfo, null, handle), "vkCreateImageView(world)"); view = handle.get(0);
        } catch (Throwable error) { close(); throw error; }
    }

    @Override public void close() {
        if (view != 0) { vkDestroyImageView(context.device, view, null); view = 0; }
        if (image != 0) { vkDestroyImage(context.device, image, null); image = 0; }
        if (memory != 0) { vkFreeMemory(context.device, memory, null); memory = 0; }
    }
}
