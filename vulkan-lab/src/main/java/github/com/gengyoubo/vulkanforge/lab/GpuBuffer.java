package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;

final class GpuBuffer implements AutoCloseable {
    final VulkanContext context;
    long buffer, memory;
    GpuBuffer(VulkanContext context, byte[] bytes, int usage) {
        this.context = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes.length).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(context.device, info, null, handle), "vkCreateBuffer(world)"); buffer = handle.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(context.device, buffer, requirements);
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size())
                    .memoryTypeIndex(memoryType(context, stack, requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(vkAllocateMemory(context.device, allocation, null, handle), "vkAllocateMemory(world buffer)"); memory = handle.get(0);
            check(vkBindBufferMemory(context.device, buffer, memory, 0), "vkBindBufferMemory(world)");
            var pointer = stack.mallocPointer(1);
            check(vkMapMemory(context.device, memory, 0, bytes.length, 0, pointer), "vkMapMemory(world)");
            try { org.lwjgl.system.MemoryUtil.memByteBuffer(pointer.get(0), bytes.length).put(bytes); }
            finally { vkUnmapMemory(context.device, memory); }
        } catch (Throwable error) { close(); throw error; }
    }

    static int memoryType(VulkanContext context, MemoryStack stack, int mask, int required) {
        var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.physical, properties);
        for (int i = 0; i < properties.memoryTypeCount(); i++) if ((mask & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & required) == required) return i;
        throw new IllegalStateException("No Vulkan memory type for flags=" + required);
    }

    @Override public void close() {
        if (buffer != 0) { vkDestroyBuffer(context.device, buffer, null); buffer = 0; }
        if (memory != 0) { vkFreeMemory(context.device, memory, null); memory = 0; }
    }
}
