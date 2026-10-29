package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import java.util.function.Consumer;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;

/** Immutable device-local buffer versions, retired after the owner's existing GPU fences. */
final class NativeGeometryCache implements AutoCloseable {
    static final class Buffer {
        final GpuBuffer gpu;
        final long version;
        final int bytes;
        private boolean closed;
        Buffer(GpuBuffer gpu,long version,int bytes){this.gpu=gpu;this.version=version;this.bytes=bytes;}
    }
    private final VulkanContext context;
    private final Consumer<Runnable> retire;
    private final long budget;
    private final Map<Integer,Buffer> buffers=new HashMap<>();
    private long liveBytes,peakBytes,uploads,uploadedBytes,reuses,fallbacks,partialUploads,partialBytes;
    NativeGeometryCache(VulkanContext context,Consumer<Runnable> retire){this(context,retire,256L<<20);}
    NativeGeometryCache(VulkanContext context,Consumer<Runnable> retire,long budget){this.context=context;this.retire=retire;this.budget=budget;}
    Buffer get(int id,long version,byte[] data){return get(id,version,data,-1,null);}
    /**
     * {@code ranges} ({start0,end0,...}) are the bytes written since {@code base}. When the resident buffer is exactly that
     * version and the size is unchanged, the new version is a device copy of it plus only those bytes from staging: a chunk
     * rebuild inside a large region arena no longer re-sends the whole arena through the upload ring.
     */
    Buffer get(int id,long version,byte[] data,long base,int[] ranges){
        Buffer old=buffers.get(id);
        if(old!=null&&old.version==version){reuses++;return old;}
        if(old!=null){buffers.remove(id);retire.accept(()->release(old));}
        int size=Math.addExact(data.length,3)&~3;
        if(size==0||size>budget-liveBytes){fallbacks++;return null;}
        GpuBuffer gpu=new GpuBuffer(context,size,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT|VK_BUFFER_USAGE_INDEX_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT,false);
        Buffer next=new Buffer(gpu,version,size);
        int dirty=0;if(ranges!=null)for(int i=0;i<ranges.length;i+=2)dirty+=ranges[i+1]-ranges[i];
        if(old!=null&&!old.closed&&ranges!=null&&old.version==base&&old.bytes==size&&dirty*2<size){
            try{partial(old,gpu,data,ranges,dirty,size);}catch(RuntimeException|Error error){gpu.close();throw error;}
            buffers.put(id,next);liveBytes+=size;peakBytes=Math.max(peakBytes,liveBytes);partialUploads++;partialBytes+=dirty;return next;
        }
        try {
            byte[] source=size==data.length?data:Arrays.copyOf(data,size);
            context.uploads.submit(source,(command,staging,offset,stack)->{
                vkCmdCopyBuffer(command,staging.buffer,gpu.buffer,VkBufferCopy.calloc(1,stack).srcOffset(offset).size(size));
                var barrier=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().buffer(gpu.buffer).size(size)
                        .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT).dstAccessMask(VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT|VK_ACCESS_2_INDEX_READ_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
            });
        }catch(RuntimeException|Error error){gpu.close();throw error;}
        buffers.put(id,next);liveBytes+=size;peakBytes=Math.max(peakBytes,liveBytes);uploads++;uploadedBytes+=size;return next;
    }
    private void partial(Buffer old,GpuBuffer gpu,byte[] data,int[] ranges,int dirty,int size){
        byte[] payload=new byte[Math.max(4,dirty)];int at=0;for(int i=0;i<ranges.length;i+=2){System.arraycopy(data,ranges[i],payload,at,ranges[i+1]-ranges[i]);at+=ranges[i+1]-ranges[i];}
        // The old version is retired after this frame's fence, which follows this upload batch in submission order.
        context.uploads.submit(payload,(command,staging,offset,stack)->{
            var read=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().buffer(old.gpu.buffer).size(VK_WHOLE_SIZE)
                    .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(read));
            vkCmdCopyBuffer(command,old.gpu.buffer,gpu.buffer,VkBufferCopy.calloc(1,stack).size(size));
            var ordered=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().buffer(gpu.buffer).size(VK_WHOLE_SIZE)
                    .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(ordered));
            var regions=VkBufferCopy.calloc(ranges.length/2,stack);long source=offset;
            for(int i=0;i<ranges.length;i+=2){regions.get(i/2).srcOffset(source).dstOffset(ranges[i]).size(ranges[i+1]-ranges[i]);source+=ranges[i+1]-ranges[i];}
            if(ranges.length>0)vkCmdCopyBuffer(command,staging.buffer,gpu.buffer,regions);
            var barrier=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().buffer(gpu.buffer).size(VK_WHOLE_SIZE)
                    .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT|VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT|VK_ACCESS_2_INDEX_READ_BIT|VK_ACCESS_2_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
        });
        uploads++;uploadedBytes+=dirty;
    }
    void delete(int id){Buffer old=buffers.remove(id);if(old!=null)retire.accept(()->release(old));}
    private void release(Buffer buffer){if(!buffer.closed){buffer.closed=true;buffer.gpu.close();liveBytes-=buffer.bytes;}}
    Map<String,Long> stats(){return Map.of("residentGeometryBytes",liveBytes,"residentGeometryPeakBytes",peakBytes,"residentUploads",uploads,"residentUploadBytes",uploadedBytes,"residentReuses",reuses,"residentFallbacks",fallbacks,"residentPartialUploads",partialUploads,"residentPartialBytes",partialBytes);}
    @Override public void close(){for(Buffer buffer:buffers.values())release(buffer);buffers.clear();}
}
