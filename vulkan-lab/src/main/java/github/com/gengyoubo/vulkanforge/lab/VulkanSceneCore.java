package github.com.gengyoubo.vulkanforge.lab;

import java.util.function.Consumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Shared terrain/GPU-driven kernel. No GL imports, exported images, semaphore handoffs or presentation. */
final class VulkanSceneCore implements AutoCloseable {
    final WorldScene world;
    final GpuCuller culler;
    VulkanSceneCore(VulkanContext context,int slots,Consumer<String> log) {
        world=new WorldScene(context,log);GpuCuller created=null;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            world.initialize();var n=stack.ints(0);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,n,null);var families=VkQueueFamilyProperties.calloc(n.get(0),stack);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,n,families);
            if((families.get(context.queueFamily).queueFlags()&VK_QUEUE_COMPUTE_BIT)!=0&&!Boolean.getBoolean("vulkanforge.disableGpuCulling"))created=new GpuCuller(context,slots);
            if(created!=null)world.enableGpuDriven(created);culler=created;
            log.accept("GPU_DRIVEN="+(world.meshShaderActive()?"NV_TASK_MESH":culler!=null&&culler.compact()?"COMPACT_INDIRECT_COUNT":"FALLBACK"));
            log.accept("GPU_CULLING="+(culler==null?"DISABLED":world.meshShaderActive()?"TASK_SHADER":"COMPUTE_INDIRECT"));
        }catch(Throwable error){if(created!=null)created.close();world.close();throw error;}
    }
    void prepare(VkCommandBuffer command,MemoryStack stack,int slot){if(culler!=null)world.prepareCulling(command,stack,culler,slot);}
    void opaque(VkCommandBuffer command,MemoryStack stack,int slot){world.draw(command,stack,culler,slot);}
    void translucent(VkCommandBuffer command,MemoryStack stack,int slot){world.drawTranslucent(command,stack,slot);}
    void completed(int slot){if(culler!=null)culler.collect(slot);}
    public void close(){if(culler!=null)culler.close();world.close();}
}
