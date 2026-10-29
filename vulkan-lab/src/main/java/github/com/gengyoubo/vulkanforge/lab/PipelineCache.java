package github.com.gengyoubo.vulkanforge.lab;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;

/**
 * Disk-backed VkPipelineCache. Data from another device, driver or cache UUID is discarded before it reaches the driver,
 * so a GPU or driver change only costs one cold start. Saved when the device closes, and only after new pipelines were built.
 */
final class PipelineCache {
    private PipelineCache() { }
    static Path file(){return Path.of(System.getProperty("vulkanforge.pipelineCacheFile","cache/vulkanforge/pipeline-cache.bin"));}
    static long create(VulkanContext context){
        if(Boolean.getBoolean("vulkanforge.disablePipelineCache"))return 0;
        byte[] initial=null;
        try{Path file=file();if(Files.isRegularFile(file)&&Files.size(file)<=256L*1024*1024){byte[] raw=Files.readAllBytes(file);if(compatible(context,raw))initial=raw;}}catch(IOException ignored){}
        ByteBuffer data=initial==null?null:MemoryUtil.memAlloc(initial.length).put(initial).flip();
        try(var stack=MemoryStack.stackPush()){
            var info=VkPipelineCacheCreateInfo.calloc(stack).sType$Default();if(data!=null)info.pInitialData(data);var handle=stack.mallocLong(1);
            int result=vkCreatePipelineCache(context.device,info,null,handle);
            // A driver may still reject data it wrote itself; an empty cache is always valid.
            if(result!=VK_SUCCESS&&data!=null)result=vkCreatePipelineCache(context.device,VkPipelineCacheCreateInfo.calloc(stack).sType$Default(),null,handle);
            check(result,"create pipeline cache");
            System.out.println("PIPELINE_CACHE="+(initial==null?"COLD":"WARM bytes="+initial.length));return handle.get(0);
        }finally{if(data!=null)MemoryUtil.memFree(data);}
    }
    /** VkPipelineCacheHeaderVersionOne: length, version 1, vendorID, deviceID, pipelineCacheUUID. */
    private static boolean compatible(VulkanContext context,byte[] raw){
        if(raw.length<32)return false;ByteBuffer header=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        try(var stack=MemoryStack.stackPush()){
            var properties=VkPhysicalDeviceProperties.malloc(stack);vkGetPhysicalDeviceProperties(context.physical,properties);
            byte[] uuid=new byte[VK_UUID_SIZE];properties.pipelineCacheUUID().get(uuid);
            return header.getInt(0)>=32&&header.getInt(4)==VK_PIPELINE_CACHE_HEADER_VERSION_ONE&&header.getInt(8)==properties.vendorID()&&header.getInt(12)==properties.deviceID()&&Arrays.equals(Arrays.copyOfRange(raw,16,32),uuid);
        }
    }
    static void save(VulkanContext context,long cache,long created){
        if(cache==0||created==0)return;
        try(var stack=MemoryStack.stackPush()){
            var size=stack.mallocPointer(1);if(vkGetPipelineCacheData(context.device,cache,size,null)!=VK_SUCCESS||size.get(0)==0)return;
            ByteBuffer data=MemoryUtil.memAlloc((int)size.get(0));
            try{
                if(vkGetPipelineCacheData(context.device,cache,size,data)!=VK_SUCCESS)return;byte[] raw=new byte[(int)size.get(0)];data.get(0,raw);
                Path file=file().toAbsolutePath();Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"pipeline-",".tmp");
                try{Files.write(temporary,raw);Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
                System.out.println("PIPELINE_CACHE_SAVED bytes="+raw.length+" newPipelines="+created);
            }catch(IOException ignored){}finally{MemoryUtil.memFree(data);}
        }
    }
}
