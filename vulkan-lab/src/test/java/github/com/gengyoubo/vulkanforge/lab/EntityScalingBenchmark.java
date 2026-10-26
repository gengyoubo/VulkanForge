package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.zip.CRC32;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.EntityPass.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Surface-free, paired replay scaling. Synthetic draws isolate replay; no game renderer or capture cost is included. */
public final class EntityScalingBenchmark {
    private static final int SIZE=256;
    private record Scene(boolean unique,int count,boolean prewarm) { }
    public static void main(String[] args) throws Exception {
        Configuration.STACK_SIZE.set(2048);
        Path output=Path.of(System.getProperty("bench.output","build/reports/entity-scaling"));Files.createDirectories(output);
        int measuredWarmup=Integer.getInteger("bench.warmup",20),measuredSamples=Integer.getInteger("bench.samples",100);
        VulkanContext context=new VulkanContext();EntityPass[] passes=new EntityPass[2];
        GpuImage color=null,depth=null;GpuBuffer readback=null;long pool=0,fence=0;
        var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().getId();
        if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        List<String> report=new ArrayList<>();
        Random order=new Random(Long.getLong("bench.seed",20261027L));
        report.add("uniforms,count,mode,record_p50_ms,record_p95_ms,record_p99_ms,allocated_bytes_per_record,upload_bytes,batch_heap_copy_bytes,draws,pipeline_creations,buffer_growths,crc32");
        try(MemoryStack setup=MemoryStack.stackPush()) {
            context.initialize(0,VK.getInstanceVersionSupported(),false);
            color=new GpuImage(context,SIZE,SIZE,VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT);
            depth=new GpuImage(context,SIZE,SIZE,VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,VK_IMAGE_ASPECT_DEPTH_BIT);
            readback=new GpuBuffer(context,SIZE*SIZE*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            for(int mode=0;mode<2;mode++) {
                System.setProperty("vulkanforge.entityLegacyBatch",Boolean.toString(mode==0));
                passes[mode]=new EntityPass(context,1,Runnable::run,VK_FORMAT_R8G8B8A8_UNORM,true,false);
                byte[] white=new byte[16*16*4];Arrays.fill(white,(byte)-1);passes[mode].texture(1,16,16,0,white);
            }
            context.uploads.drain();
            var handle=setup.mallocLong(1);var pointer=setup.mallocPointer(1);
            check(vkCreateCommandPool(context.device,VkCommandPoolCreateInfo.calloc(setup).sType$Default().queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),null,handle),"entity bench pool");pool=handle.get(0);
            check(vkAllocateCommandBuffers(context.device,VkCommandBufferAllocateInfo.calloc(setup).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),pointer),"entity bench commands");
            VkCommandBuffer command=new VkCommandBuffer(pointer.get(0),context.device);
            check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(setup).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT),null,handle),"entity bench fence");fence=handle.get(0);
            List<Scene> scenes=new ArrayList<>(List.of(new Scene(false,500,true),new Scene(true,500,true))),measured=new ArrayList<>();
            for(boolean unique:new boolean[]{false,true})for(int count:new int[]{0,50,100,200,500})measured.add(new Scene(unique,count,false));
            Collections.shuffle(measured,order);scenes.addAll(measured);
            for(Scene scene:scenes) {
                boolean unique=scene.unique();int count=scene.count(),warmup=scene.prewarm()?Integer.getInteger("bench.prewarm",300):measuredWarmup,samples=scene.prewarm()?0:measuredSamples;
                List<Object[]> packets=packets(count,unique);long[][] times=new long[2][samples];long[] allocated=new long[2],hashes=new long[2];
                long[][] before=new long[2][];GpuTimers[] timers=new GpuTimers[2];
                for(int mode=0;mode<2;mode++){before[mode]=passes[mode].performanceStats();timers[mode]=new GpuTimers(context,1,ignored->{},"ENTITY");}
                int first=0;
                for(int frame=0;frame<(warmup+samples)*2;frame++) {
                    if(frame%2==0)first=order.nextBoolean()?0:1;
                    int mode=(frame+first)%2,sample=frame/2-warmup;
                    check(vkWaitForFences(context.device,fence,true,5_000_000_000L),"entity bench wait");
                    // The command buffer is shared; collect both timer slots after its existing fence only.
                    for(GpuTimers timer:timers)timer.collect(0);
                    try(MemoryStack stack=MemoryStack.stackPush()) {
                        check(vkResetCommandBuffer(command,0),"entity bench reset");check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default()),"entity bench begin");
                        var barriers=VkImageMemoryBarrier2.calloc(2,stack);
                        barriers.get(0).sType$Default().newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT).image(color.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).subresourceRange(r->r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
                        barriers.get(1).sType$Default().newLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL).dstStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT).dstAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT).image(depth.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).subresourceRange(r->r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1));
                        vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
                        timers[mode].begin(command,0);
                        var attachment=VkRenderingAttachmentInfo.calloc(1,stack).sType$Default().imageView(color.view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                        var depthInfo=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(depth.view).imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);depthInfo.clearValue().depthStencil().depth(1);
                        var rendering=VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(attachment).pDepthAttachment(depthInfo);rendering.renderArea().extent().set(SIZE,SIZE);vkCmdBeginRendering(command,rendering);
                        vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).width(SIZE).height(SIZE).maxDepth(1));vkCmdSetScissor(command,0,VkRect2D.calloc(1,stack).extent(e->e.set(SIZE,SIZE)));
                        if(sample>=0)timers[mode].start(command,0,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
                        long bytes=allocation.getThreadAllocatedBytes(thread),start=System.nanoTime();
                        passes[mode].record(command,stack,0,packets,0,0);
                        long elapsed=System.nanoTime()-start,delta=allocation.getThreadAllocatedBytes(thread)-bytes;
                        if(sample>=0){times[mode][sample]=elapsed;allocated[mode]+=delta;timers[mode].end(command,0,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);}
                        vkCmdEndRendering(command);
                        boolean last=sample==samples-1;
                        if(last) {
                            barriers.limit(1);barriers.get(0).oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT).srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT);
                            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
                            vkCmdCopyImageToBuffer(command,color.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback.buffer,VkBufferImageCopy.calloc(1,stack).imageSubresource(r->r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e->e.set(SIZE,SIZE,1)));
                        }
                        check(vkEndCommandBuffer(command),"entity bench end");check(vkResetFences(context.device,fence),"entity bench reset fence");
                        check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(command)),fence),"entity bench submit");
                        if(sample>=0)timers[mode].submitted(0);
                        if(last){check(vkWaitForFences(context.device,fence,true,5_000_000_000L),"entity pixel wait");CRC32 crc=new CRC32();crc.update(readback.mapped.duplicate().clear());hashes[mode]=crc.getValue();}
                    }
                }
                check(vkWaitForFences(context.device,fence,true,5_000_000_000L),"entity case finish");
                if(hashes[0]!=hashes[1])throw new AssertionError("Batch pixel mismatch count="+count+" unique="+unique);
                if(scene.prewarm()){for(GpuTimers timer:timers)timer.close();continue;}
                for(int mode=0;mode<2;mode++) {
                    Arrays.sort(times[mode]);long[] after=passes[mode].performanceStats(),b=before[mode];
                    String row=String.format(Locale.ROOT,"%s,%d,%s,%.6f,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%d",unique?"UNIQUE":"SHARED",count,mode==0?"LEGACY":"STREAM",percentile(times[mode],50),percentile(times[mode],95),percentile(times[mode],99),allocated[mode]/samples,after[4]-b[4],after[5]-b[5],after[8]-b[8],after[6]-b[6],after[7]-b[7],hashes[mode]);
                    report.add(row);System.out.println("ENTITY_SCALE "+row);timers[mode].collect(0);final int measuredMode=mode;timers[mode].report(line->System.out.println("ENTITY_SCALE_GPU uniforms="+unique+" count="+count+" mode="+modeName(measuredMode)+" "+line));timers[mode].close();
                }
                Files.write(output.resolve("results.csv"),report);
            }
            System.out.println("ENTITY_SCALING=OK SURFACE=false CAPTURE_INCLUDED=false");
        } finally {
            if(context.device!=null){vkDeviceWaitIdle(context.device);for(EntityPass pass:passes)if(pass!=null)pass.close();if(readback!=null)readback.close();if(color!=null)color.close();if(depth!=null)depth.close();if(fence!=0)vkDestroyFence(context.device,fence,null);if(pool!=0)vkDestroyCommandPool(context.device,pool,null);}
            context.close();
        }
    }
    private static String modeName(int mode){return mode==0?"LEGACY":"STREAM";}
    private static double percentile(long[] data,int percent){return data[(data.length-1)*percent/100]/1e6;}
    static List<Object[]> packets(int count,boolean unique) {
        int quads=Integer.getInteger("bench.quads",96);List<Object[]> packets=new ArrayList<>();
        for(int i=0;i<count;i++) {
            ByteBuffer data=ByteBuffer.allocate(quads*4*36).order(ByteOrder.LITTLE_ENDIAN);
            float x=(i%25)/25f*1.8f-.9f,y=(i/25)/20f*1.8f-.9f;
            for(int q=0;q<quads;q++)for(float[] p:new float[][]{{0,0},{.025f,0},{.025f,.025f},{0,.025f}})
                data.putFloat(x+p[0]).putFloat(y+p[1]).putFloat(q*.00001f).putInt(-1).putFloat(.5f).putFloat(.5f).putInt(0).putInt(0).putInt(127<<16);
            float[] u=new float[64];for(int k=0;k<4;k++){u[k*5]=u[16+k*5]=1;u[44+k]=1;}for(int k=0;k<3;k++)u[32+k*5]=1;
            u[50]=u[54]=1;u[55]=1;u[60]=100;u[61]=200;u[63]=1;if(unique)u[44]=.4f+i*.001f;
            packets.add(new Object[]{new int[]{FORMAT_ENTITY,1,DEPTH_TEST|DEPTH_WRITE,1,1,quads*4,MODE_QUADS,0,0},u,data.array(),null});
        }
        return packets;
    }
}
