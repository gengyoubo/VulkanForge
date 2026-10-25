package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;

final class GpuImage implements AutoCloseable {
    private final VulkanContext context;
    final int width, height;
    long image, memory, view;
    private GpuMemory.Allocation allocation;
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
            var dedicated = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
            var requirements = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicated.address());
            org.lwjgl.vulkan.VK11.vkGetImageMemoryRequirements2(context.device, VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(image), requirements);
            allocation = context.memory.allocate(requirements.memoryRequirements(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true,
                    dedicated.requiresDedicatedAllocation() ? image : 0, 0);
            memory = allocation.memory();
            check(vkBindImageMemory(context.device, image, memory, allocation.offset), "bind pooled GPU image");
            if ((usage & (VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                    | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT)) != 0) {
                var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                        .subresourceRange(r -> r.aspectMask(aspect).levelCount(1).layerCount(1));
                check(vkCreateImageView(context.device, viewInfo, null, handle), "vkCreateImageView(world)"); view = handle.get(0);
            }
        } catch (Throwable error) { close(); throw error; }
    }

    @Override public void close() {
        if (view != 0) { vkDestroyImageView(context.device, view, null); view = 0; }
        if (image != 0) { vkDestroyImage(context.device, image, null); image = 0; }
        if (allocation != null) { allocation.close(); allocation = null; memory = 0; }
    }
}
