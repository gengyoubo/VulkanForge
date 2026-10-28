package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;

/** Actual GPU readback of immutable mesh versions, including deferred deletion and budget pressure. */
public final class NativeGeometrySmoke {
    public static void main(String[] ignored) {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        VulkanContext context=new VulkanContext();
        List<Runnable> retired=new ArrayList<>();
        try(context) {
            context.initialize(0,VK.getInstanceVersionSupported(),Boolean.getBoolean("vulkanforge.requireValidation"));
            try(NativeGeometryCache cache=new NativeGeometryCache(context,retired::add,32)) {
                try {
                byte[] first={1,2,3,4,5,6,7,8,9,10,11,12}, second={21,22,23,24,25,26,27,28,29,30,31,32,33,34};
                var a=cache.get(7,1,first);
                require(cache.get(7,1,first)==a,"Unchanged mesh uploaded twice");
                var b=cache.get(7,2,second);
                require(a!=b&&a.gpu.buffer!=b.gpu.buffer,"Mesh update overwrote an in-flight version");
                require(a.gpu.buffer!=0&&retired.size()==1,"Old mesh retired before the frame fence");
                require(cache.get(8,1,new byte[8])==null,"Budget ignored in-flight old versions");
                cache.delete(7);
                require(b.gpu.buffer!=0&&retired.size()==2,"Deleted mesh retired before the frame fence");
                context.uploads.drain();
                readback(context,a,first); readback(context,b,Arrays.copyOf(second,16));
                for(Runnable action:retired)action.run(); retired.clear();
                require(a.gpu.buffer==0&&b.gpu.buffer==0,"Completed versions were not freed");
                require(cache.stats().get("residentGeometryBytes")==0,"Retired memory still counted");
                var c=cache.get(7,3,first);
                require(c!=null,"Cache did not recover after budget pressure");
                context.uploads.drain(); readback(context,c,first);
                require(cache.stats().get("residentUploads")==3&&cache.stats().get("residentReuses")==1,"Incorrect upload/reuse accounting");
                require(cache.stats().get("residentGeometryPeakBytes")==28,"Resident peak accounting");
                } finally {
                    context.uploads.drain();
                    for(Runnable action:retired)action.run();
                }
            }
        }
        require(context.validationErrors()==0,"Geometry cache validation errors="+context.validationErrors());
        System.out.println("NATIVE_GEOMETRY_SMOKE=OK (GPU readback, immutable versions, deferred deletion, budget recovery, zero validation errors)");
    }
    private static void readback(VulkanContext context,NativeGeometryCache.Buffer source,byte[] expected) {
        long pool=0,fence=0;
        GpuBuffer destination=new GpuBuffer(context,expected.length,VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var handle=stack.mallocLong(1);
            check(vkCreateCommandPool(context.device,VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily),null,handle),"geometry test pool");pool=handle.get(0);
            var pointer=stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(context.device,VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),pointer),"geometry test command");
            var command=new VkCommandBuffer(pointer.get(0),context.device);
            check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default()),"geometry test begin");
            var before=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().buffer(source.gpu.buffer).size(source.bytes)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(before));
            vkCmdCopyBuffer(command,source.gpu.buffer,destination.buffer,VkBufferCopy.calloc(1,stack).size(expected.length));
            var after=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().buffer(destination.buffer).size(expected.length)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(after));
            check(vkEndCommandBuffer(command),"geometry test end");
            check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default(),null,handle),"geometry test fence");fence=handle.get(0);
            check(vkQueueSubmit(context.queue,VkSubmitInfo.calloc(1,stack).sType$Default().pCommandBuffers(stack.pointers(command.address())),fence),"geometry test submit");
            check(vkWaitForFences(context.device,fence,true,5_000_000_000L),"geometry test wait");
            for(int i=0;i<expected.length;i++)require(destination.mapped.get(i)==expected[i],"GPU mesh version changed at byte "+i);
        } finally {
            vkQueueWaitIdle(context.queue);
            if(fence!=0)vkDestroyFence(context.device,fence,null);
            if(pool!=0)vkDestroyCommandPool(context.device,pool,null);
            destination.close();
        }
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
