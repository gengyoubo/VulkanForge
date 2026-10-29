package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;

final class GpuImage implements VulkanRenderImage {
    private final VulkanContext context;
    final int width, height,format,levels;
    long image, memory, view,sampledView;
    private GpuMemory.Allocation allocation;
    GpuImage(VulkanContext context, int width, int height, int format, int usage, int aspect) {
        this(context,width,height,format,usage,aspect,1);
    }
    GpuImage(VulkanContext context, int width, int height, int format, int usage, int aspect,int levels) {
        this.context = context;
        this.width = width; this.height = height;this.format=format;this.levels=levels;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var imageProps = VkImageFormatProperties.calloc(stack);
            check(vkGetPhysicalDeviceImageFormatProperties(context.physical, format, VK_IMAGE_TYPE_2D,
                    VK_IMAGE_TILING_OPTIMAL, usage, 0, imageProps), "query GPU image limits");
            if (width < 1 || height < 1 || width > imageProps.maxExtent().width() || height > imageProps.maxExtent().height())
                throw new IllegalArgumentException("GPU does not support " + width + "x" + height + " image; format/usage limit is "
                        + imageProps.maxExtent().width() + "x" + imageProps.maxExtent().height());
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physical, format, props);
            if (aspect == VK_IMAGE_ASPECT_DEPTH_BIT && (props.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) == 0) throw new IllegalStateException("D32 depth attachment is unsupported");
            var handle = stack.mallocLong(1);
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                    .extent(e -> e.set(width, height, 1)).mipLevels(levels).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
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
                sampledView=view;
                if(levels>1){viewInfo.subresourceRange().levelCount(levels);check(vkCreateImageView(context.device,viewInfo,null,handle),"vkCreateImageView(mips)");sampledView=handle.get(0);}
            }
        } catch (Throwable error) { close(); throw error; }
    }

    @Override public long view(){return view;}
    @Override public long image(){return image;}
    @Override public void close() {
        if(sampledView!=0&&sampledView!=view)vkDestroyImageView(context.device,sampledView,null);sampledView=0;
        if (view != 0) { vkDestroyImageView(context.device, view, null); view = 0; }
        if (image != 0) { vkDestroyImage(context.device, image, null); image = 0; }
        if (allocation != null) { allocation.close(); allocation = null; memory = 0; }
    }
}
