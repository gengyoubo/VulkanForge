package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;

final class GpuBuffer implements AutoCloseable {
    final VulkanContext context;
    long buffer, memory;
    private GpuMemory.Allocation allocation;
    java.nio.ByteBuffer mapped;
    GpuBuffer(VulkanContext context, byte[] bytes, int usage) {
        this(context, bytes.length, usage);
        mapped.put(bytes).clear();
        mapped = null;
    }
    GpuBuffer(VulkanContext context, int capacity, int usage) {
        this(context, capacity, usage, true);
    }
    GpuBuffer(VulkanContext context, int capacity, int usage, boolean hostVisible) {
        this.context = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(capacity).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(context.device, info, null, handle), "vkCreateBuffer(world)"); buffer = handle.get(0);
            var dedicated = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
            var requirements = VkMemoryRequirements2.calloc(stack).sType$Default().pNext(dedicated.address());
            org.lwjgl.vulkan.VK11.vkGetBufferMemoryRequirements2(context.device, VkBufferMemoryRequirementsInfo2.calloc(stack).sType$Default().buffer(buffer), requirements);
            allocation = context.memory.allocate(requirements.memoryRequirements(), hostVisible
                    ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    false, 0, dedicated.requiresDedicatedAllocation() ? buffer : 0);
            memory = allocation.memory();
            check(vkBindBufferMemory(context.device, buffer, memory, allocation.offset), "bind pooled GPU buffer");
            if (hostVisible) mapped = allocation.mapped(capacity);
        } catch (Throwable error) { close(); throw error; }
    }

    static int memoryType(VulkanContext context, MemoryStack stack, int mask, int required) {
        var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.physical, properties);
        for (int i = 0; i < properties.memoryTypeCount(); i++) if ((mask & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & required) == required) return i;
        throw new IllegalStateException("No Vulkan memory type for flags=" + required);
    }

    java.nio.ByteBuffer hostView(int size) { return allocation.mapped(size); }

    @Override public void close() {
        mapped = null;
        if (buffer != 0) { vkDestroyBuffer(context.device, buffer, null); buffer = 0; }
        if (allocation != null) { allocation.close(); allocation = null; memory = 0; }
    }
}
