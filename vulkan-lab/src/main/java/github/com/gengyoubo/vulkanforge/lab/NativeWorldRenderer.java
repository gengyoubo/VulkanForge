package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.*;
import java.nio.*;
import java.util.*;
import java.util.function.Consumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdWriteTimestamp2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;

/** Shared native drawing kernel. Default execution is private VK; an explicit adapter may export only final color. */
final class NativeWorldRenderer implements AutoCloseable {
    static final int SLOTS=2,MAX_POSTS=64;
    private static final long DEPTH=VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
    private static final class Slot {
        VkCommandBuffer command;long fence,serial;boolean pending;
        final VulkanRenderImage[] colors=new VulkanRenderImage[2],depths=new VulkanRenderImage[2];int result;
    }
    record Timing(double gpuMs,long vertices,long entryTicks,double acquireMs,double cullMs,double opaqueMs,double translucentMs,double postMs,double releaseMs) { }
    record Pixels(byte[] color,float[] depth) { }
    private record Retired(long serial,Runnable action) { }
    final VulkanContext context;final VulkanSceneCore core;final WorldScene world;final GpuCuller culler;
    private final Slot[] slots=new Slot[SLOTS];private final NativePost post;
    private final NativeFrameBoundary boundary;
    private final ArrayDeque<Retired> retired=new ArrayDeque<>();
    private long pool,timestamps,statistics,submitted,completed,postPasses;
    private long prepareNanos,recordNanos,submitNanos,fenceNanos,queryNanos,boundaryNanos;
    private final double period;private final int bits;final int width,height;
    NativeWorldRenderer(VulkanContext context,int width,int height,Consumer<String> log) {
        this(context,width,height,log,null);
    }
    NativeWorldRenderer(VulkanContext context,int width,int height,Consumer<String> log,NativeFrameBoundary boundary) {
        if(context.interopEnabled()!=(boundary!=null))throw new IllegalArgumentException("Native device interop policy must match its explicit boundary");
        if(width<=0||height<=0)throw new IllegalArgumentException("Native target extent must be positive");
        this.context=context;this.width=width;this.height=height;this.boundary=boundary;core=new VulkanSceneCore(context,SLOTS,log);world=core.world;culler=core.culler;
        world.retireUsing(action->{if(completed>=submitted)action.run();else retired.addLast(new Retired(submitted,action));});
        post=new NativePost(context,SLOTS*MAX_POSTS);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var h=stack.mallocLong(1);check(vkCreateCommandPool(context.device,VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),null,h),"native command pool");pool=h.get(0);
            var commands=stack.mallocPointer(SLOTS);check(vkAllocateCommandBuffers(context.device,VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOTS),commands),"native commands");
            for(int s=0;s<SLOTS;s++) {
                Slot slot=slots[s]=new Slot();slot.command=new VkCommandBuffer(commands.get(s),context.device);check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT),null,h),"native fence");slot.fence=h.get(0);
                for(int i=0;i<2;i++){slot.colors[i]=boundary==null?null:boundary.color(s,i);if(slot.colors[i]==null)slot.colors[i]=new GpuImage(context,width,height,VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT);slot.depths[i]=new GpuImage(context,width,height,VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_DEPTH_BIT);}
            }
            check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS*7),null,h),"native timestamps");timestamps=h.get(0);
            if(context.pipelineStatisticsEnabled){check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_PIPELINE_STATISTICS).queryCount(SLOTS).pipelineStatistics(VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_VERTICES_BIT),null,h),"native vertex counts");statistics=h.get(0);}
            var props=VkPhysicalDeviceProperties.calloc(stack);vkGetPhysicalDeviceProperties(context.physical,props);period=props.limits().timestampPeriod();
            var count=stack.ints(0);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,count,null);var families=VkQueueFamilyProperties.calloc(count.get(0),stack);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,count,families);bits=families.get(context.queueFamily).timestampValidBits();if(bits==0)throw new IllegalStateException("Native GPU timestamps unavailable");
        }
        log.accept("EXECUTOR="+(boundary==null?"VK_NATIVE_OFFSCREEN PRIVATE_IMAGES=8 SHARED_IMAGES=0 EXTERNAL_SEMAPHORES=0 GL_CONTEXTS=0":"VK_FINAL_COLOR_EXPERIMENT SHARED_IMAGES="+boundary.sharedImages()));
    }
    void apply(WorldProtocol.Command command){if(command instanceof WorldProtocol.Reset||command instanceof WorldProtocol.Atlas)drain();world.apply(command);}
    int nextSlot(){return (int)(submitted%SLOTS);}
    RenderMode mode(){return boundary==null?RenderMode.VK:RenderMode.GL_VK;}
    void frame(NativeFramePlan plan) {
        long prepareStart=System.nanoTime();
        plan.requireSupported();if(plan.posts().size()>MAX_POSTS)throw new UnsupportedOperationException("Native post budget exceeded; no passes discarded");
        if(boundary!=null)boundary.validatePlan(plan);
        // Reject unsupported shaders before touching frame resources or resetting its command buffer.
        for(var draw:plan.posts())post.prepare(draw.packet(0,1,width,height));
        if(!world.ready())throw new IllegalStateException("Native terrain is not ready; no GL fallback is available");
        int index=nextSlot();Slot slot=slots[index];if(slot.pending)throw new IllegalStateException("Native slot must complete before reuse");
        prepareNanos+=System.nanoTime()-prepareStart;
        if(boundary!=null){long start=System.nanoTime();boundary.beforeRecord(index,plan.posts().size()%2);boundaryNanos+=System.nanoTime()-start;}
        long recordStart=System.nanoTime();
        try(MemoryStack stack=MemoryStack.stackPush()) {
            begin(slot.command,stack);vkCmdResetQueryPool(slot.command,timestamps,index*7,7);stamp(slot.command,index*7);
            if(boundary!=null)boundary.acquire(slot.command,stack,index,plan.posts().size()%2);
            stamp(slot.command,index*7+1);
            if(statistics!=0)vkCmdResetQueryPool(slot.command,statistics,index,1);
            core.prepare(slot.command,stack,index);
            stamp(slot.command,index*7+2);
            var b=VkImageMemoryBarrier2.calloc(2,stack);image(b.get(0),slot.colors[0],false,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);image(b.get(1),slot.depths[0],true,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_NONE,0,DEPTH,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);barrier(slot.command,b,stack);
            if(statistics!=0)vkCmdBeginQuery(slot.command,statistics,index,0);
            render(slot.command,slot.colors[0],slot.depths[0],true,new float[]{world.red(),world.green(),world.blue(),0},stack);core.opaque(slot.command,stack,index);vkCmdEndRendering(slot.command);
            stamp(slot.command,index*7+3);
            image(b.get(0),slot.colors[0],false,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);image(b.get(1),slot.depths[0],true,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,DEPTH,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,DEPTH,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);barrier(slot.command,b,stack);
            render(slot.command,slot.colors[0],slot.depths[0],false,new float[4],stack);core.translucent(slot.command,stack,index);vkCmdEndRendering(slot.command);
            stamp(slot.command,index*7+4);
            if(statistics!=0)vkCmdEndQuery(slot.command,statistics,index);
            int[] colorLayouts={VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,boundary!=null&&boundary.color(index,1)!=null?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED},depthLayouts={VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_UNDEFINED};
            for(int p=0;p<plan.posts().size();p++) {
                try(var passStack=MemoryStack.stackPush()) {
                    int input=p%2,output=(p+1)%2;var draw=plan.posts().get(p);var barriers=VkImageMemoryBarrier2.calloc(3,passStack);
                    image(barriers.get(0),slot.colors[input],false,colorLayouts[input],VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                    image(barriers.get(1),slot.colors[output],false,colorLayouts[output],VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                    image(barriers.get(2),slot.depths[output],true,depthLayouts[output],VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT,DEPTH,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);barrier(slot.command,barriers,passStack);
                    colorLayouts[input]=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;colorLayouts[output]=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;depthLayouts[output]=VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
                    render(slot.command,slot.colors[output],slot.depths[output],true,draw.clear(),passStack);post.record(slot.command,passStack,index*MAX_POSTS+p,draw.packet(input,output,width,height),new VulkanImageBinding[]{slot.colors[input]});vkCmdEndRendering(slot.command);
                }
            }
            slot.result=plan.posts().size()%2;stamp(slot.command,index*7+5);
            if(boundary!=null)boundary.release(slot.command,stack,index,slot.result);
            stamp(slot.command,index*7+6);check(vkEndCommandBuffer(slot.command),"end native frame");recordNanos+=System.nanoTime()-recordStart;
            submitFrame(index,slot.result,plan.posts().size(),stack);
        }
    }
    private void submitFrame(int index,int result,int postCount,MemoryStack stack) {
        Slot slot=slots[index];
            long uploadStart=System.nanoTime();context.uploads.flush();prepareNanos+=System.nanoTime()-uploadStart;check(vkResetFences(context.device,slot.fence),"reset native frame fence");
            long boundaryStart=System.nanoTime();if(boundary!=null)boundary.beforeSubmit(index,result);boundaryNanos+=System.nanoTime()-boundaryStart;
            var info=VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(slot.command));
            if(boundary!=null)info.pWaitSemaphoreInfos(boundary.waits(stack,index,result)).pSignalSemaphoreInfos(boundary.signals(stack,index,result));
            long submitStart=System.nanoTime();check(vkQueueSubmit2(context.queue,info,slot.fence),"native frame submit");submitNanos+=System.nanoTime()-submitStart;
            slot.pending=true;slot.serial=++submitted;postPasses+=postCount;
            boundaryStart=System.nanoTime();if(boundary!=null)boundary.submitted(index,result,slot.fence);boundaryNanos+=System.nanoTime()-boundaryStart;
    }
    /** Explicit no-draw diagnostic: preserves image contents, external semaphore dependencies and ownership. */
    void minimalFrame() {
        if(boundary==null)throw new IllegalStateException("Minimal handoff requires an explicit GL-VK boundary");
        int index=nextSlot();Slot slot=slots[index];if(slot.pending)throw new IllegalStateException("Minimal slot pending");
        long start=System.nanoTime();boundary.beforeRecord(index,1);boundaryNanos+=System.nanoTime()-start;
        try(var stack=MemoryStack.stackPush()) {
            long recordStart=System.nanoTime();begin(slot.command,stack);vkCmdResetQueryPool(slot.command,timestamps,index*7,7);stamp(slot.command,index*7);
            boundary.acquire(slot.command,stack,index,1);stamp(slot.command,index*7+1);
            if(statistics!=0){vkCmdResetQueryPool(slot.command,statistics,index,1);vkCmdBeginQuery(slot.command,statistics,index,0);vkCmdEndQuery(slot.command,statistics,index);}
            for(int i=2;i<=5;i++)stamp(slot.command,index*7+i);
            boundary.releaseIdle(slot.command,stack,index);stamp(slot.command,index*7+6);check(vkEndCommandBuffer(slot.command),"end minimal handoff");recordNanos+=System.nanoTime()-recordStart;
            slot.result=1;submitFrame(index,1,0,stack);
        }
    }
    Timing waitSlot(int index) {
        Slot slot=slots[index];if(!slot.pending)return null;long waitStart=System.nanoTime();check(vkWaitForFences(context.device,slot.fence,true,5_000_000_000L),"native completed frame");fenceNanos+=System.nanoTime()-waitStart;slot.pending=false;completed=Math.max(completed,slot.serial);core.completed(index);
        while(!retired.isEmpty()&&retired.peekFirst().serial()<=completed)retired.removeFirst().action().run();
        long queryStart=System.nanoTime();
        try(MemoryStack stack=MemoryStack.stackPush()){var times=stack.mallocLong(7);check(vkGetQueryPoolResults(context.device,timestamps,index*7,7,times,8,VK_QUERY_RESULT_64_BIT),"native GPU time");long vertices=-1;if(statistics!=0){var n=stack.mallocLong(1);check(vkGetQueryPoolResults(context.device,statistics,index,1,n,8,VK_QUERY_RESULT_64_BIT),"native GPU vertices");vertices=n.get(0);}double[] ms=new double[6];for(int i=0;i<6;i++)ms[i]=GpuTimers.elapsedTicks(times.get(i),times.get(i+1),bits)*period/1e6;queryNanos+=System.nanoTime()-queryStart;return new Timing(GpuTimers.elapsedTicks(times.get(0),times.get(6),bits)*period/1e6,vertices,times.get(0),ms[0],ms[1],ms[2],ms[3],ms[4],ms[5]);}
    }
    void drain(){for(int s=0;s<SLOTS;s++)waitSlot(s);}
    Pixels pixels(int index) {
        if(slots[index].pending)throw new IllegalStateException("Readback before native completion");Slot slot=slots[index];int bytes=width*height*4;VulkanRenderImage color=slot.colors[slot.result],depth=slot.depths[slot.result];
        if(boundary!=null&&color==boundary.color(index,slot.result))throw new IllegalStateException("Shared output must be read through its current GL owner");
        try(GpuBuffer rgba=new GpuBuffer(context,bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT);GpuBuffer z=new GpuBuffer(context,bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT);MemoryStack stack=MemoryStack.stackPush()) {
            begin(slot.command,stack);var b=VkImageMemoryBarrier2.calloc(2,stack);image(b.get(0),color,false,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT);image(b.get(1),depth,true,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT);barrier(slot.command,b,stack);
            for(int i=0;i<2;i++){final int aspect=i==0?VK_IMAGE_ASPECT_COLOR_BIT:VK_IMAGE_ASPECT_DEPTH_BIT;vkCmdCopyImageToBuffer(slot.command,i==0?color.image():depth.image(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,i==0?rgba.buffer:z.buffer,VkBufferImageCopy.calloc(1,stack).imageSubresource(v->v.aspectMask(aspect).layerCount(1)).imageExtent(e->e.set(width,height,1)));}
            image(b.get(0),color,false,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);image(b.get(1),depth,true,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,DEPTH,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);barrier(slot.command,b,stack);
            vkCmdPipelineBarrier2(slot.command,VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(VkMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)));check(vkEndCommandBuffer(slot.command),"native readback end");check(vkResetFences(context.device,slot.fence),"native readback fence");check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(slot.command)),slot.fence),"native readback submit");check(vkWaitForFences(context.device,slot.fence,true,5_000_000_000L),"native readback complete");
            // Normalize top-origin Vulkan rows to the existing GL capture contract, outside all measured work.
            byte[] pixels=new byte[bytes];float[] depths=new float[width*height];ByteBuffer c=rgba.mapped.duplicate().clear();FloatBuffer d=z.mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN).clear().asFloatBuffer();for(int y=0;y<height;y++){c.get(y*width*4,pixels,(height-y-1)*width*4,width*4);d.get(y*width,depths,(height-y-1)*width,width);}return new Pixels(pixels,depths);
        }
    }
    long[] stats(){int shared=boundary==null?0:boundary.sharedImages();return new long[]{submitted,completed,postPasses,8-shared,shared,shared*2,0,0};}
    long[] cpuStats(){return new long[]{prepareNanos,recordNanos,submitNanos,fenceNanos,queryNanos,boundaryNanos};}
    private void render(VkCommandBuffer cmd,VulkanRenderImage color,VulkanRenderImage depth,boolean clear,float[] background,MemoryStack stack){var c=VkRenderingAttachmentInfo.calloc(1,stack).sType$Default().imageView(color.view()).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(clear?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);for(int i=0;i<4;i++)c.clearValue().color().float32(i,background[i]);var d=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(depth.view()).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(clear?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);d.clearValue().depthStencil().depth(1);vkCmdBeginRendering(cmd,VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(c).pDepthAttachment(d).renderArea(r->r.extent(e->e.set(width,height))));vkCmdSetViewport(cmd,0,VkViewport.calloc(1,stack).width(width).height(height).maxDepth(1));vkCmdSetScissor(cmd,0,VkRect2D.calloc(1,stack).extent(e->e.set(width,height)));}
    double elapsedMs(long start,long end){return GpuTimers.elapsedTicks(start,end,bits)*period/1e6;}
    private void stamp(VkCommandBuffer cmd,int query){vkCmdWriteTimestamp2(cmd,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,timestamps,query);}
    private static void begin(VkCommandBuffer cmd,MemoryStack stack){check(vkResetCommandBuffer(cmd,0),"reset native command");check(vkBeginCommandBuffer(cmd,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin native command");}
    private static void barrier(VkCommandBuffer cmd,VkImageMemoryBarrier2.Buffer images,MemoryStack stack){vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(images));}
    private static void image(VkImageMemoryBarrier2 b,VulkanRenderImage image,boolean depth,int oldLayout,int next,long srcStage,long srcAccess,long dstStage,long dstAccess){b.sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(next).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.image()).subresourceRange(r->r.aspectMask(depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));}
    public void close(){drain();context.uploads.drain();while(!retired.isEmpty())retired.removeFirst().action().run();if(boundary!=null)boundary.close();post.close();core.close();for(Slot slot:slots){for(VulkanRenderImage image:slot.colors)image.close();for(VulkanRenderImage image:slot.depths)image.close();vkDestroyFence(context.device,slot.fence,null);}if(statistics!=0)vkDestroyQueryPool(context.device,statistics,null);vkDestroyQueryPool(context.device,timestamps,null);vkDestroyCommandPool(context.device,pool,null);}
}
