package github.com.gengyoubo.vulkanforge.lab;

import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import java.util.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.ARBPipelineStatisticsQuery.GL_VERTICES_SUBMITTED_ARB;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK13.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdWriteTimestamp2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static github.com.gengyoubo.vulkanforge.lab.UnifiedWorkload.SLOTS;

/** Zero-draw shared-image experiments. Initialization/readback are outside the timed interval. */
final class InteropMicroBenchmark implements AutoCloseable {
    static final List<String> GL_CASES=List.of("A_GL_IDLE","C_GL_NO_COPY","C_GL_COPY");
    static final List<String> VK_CASES=List.of("A_SHARED_IDLE","A_VK_IDLE","A_HANDOFF_GENERAL","A_HANDOFF_ATTACHMENTS",
        "C_DEPENDENCIES_ONLY","C_VK_COPY","C_GL_COPY","C_BOTH_COPY","D_ROUNDTRIPS_2","D_ROUNDTRIPS_4");
    private static final long DEPTH_STAGES=VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
    private static final int QUERIES=8,MAX_ROUNDS=4;
    record Sample(double timeline,double vkBody,double vkDepth,double glDepth,long vertices,
                  double glSignalCpu,double vkRecordCpu,double vkSubmitCpu,double glWaitCpu,double fenceCpu,double queryCpu) { }
    private static final class Slot {
        SharedPresentImage color,depth;GpuImage copied;VkCommandBuffer[] commands=new VkCommandBuffer[MAX_ROUNDS];
        long[] fences=new long[MAX_ROUNDS];long glFence;boolean pending;
        int mainFbo,copyTexture,copyFbo;final int[] glTimes=new int[4];int stats;
        long signalCpu,recordCpu,submitCpu,waitCpu;
        SharedPresentImage[] group(){return new SharedPresentImage[]{color,depth};}
    }
    final String name;final int width,height,rounds;final boolean shared,localVk,vkDependencies,vkCopy,glCopy,attachmentLayouts;
    final VulkanContext context;private final Slot[] slots=new Slot[SLOTS];
    private long pool,timestamps,statistics;private double period;private int bits;
    InteropMicroBenchmark(VulkanContext context,String name,int width,int height) {
        this.context=context;this.name=name;this.width=width;this.height=height;
        shared=context!=null;localVk=name.equals("A_VK_IDLE");
        rounds=name.equals("D_ROUNDTRIPS_4")?4:name.equals("D_ROUNDTRIPS_2")?2:shared&&!name.equals("A_SHARED_IDLE")&&!localVk?1:0;
        vkDependencies=shared&&name.startsWith("C_");vkCopy=shared&&(name.equals("C_VK_COPY")||name.equals("C_BOTH_COPY"));
        glCopy=name.equals("C_GL_COPY")||name.equals("C_BOTH_COPY");attachmentLayouts=!name.equals("A_HANDOFF_GENERAL");
        try(MemoryStack stack=MemoryStack.stackPush()) {
            if(shared) {
                var h=stack.mallocLong(1);
                check(vkCreateCommandPool(context.device,VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),null,h),"micro pool");pool=h.get(0);
                check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS*MAX_ROUNDS*QUERIES),null,h),"micro timestamps");timestamps=h.get(0);
                check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_PIPELINE_STATISTICS).queryCount(SLOTS*MAX_ROUNDS).pipelineStatistics(VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_VERTICES_BIT),null,h),"micro statistics");statistics=h.get(0);
                var props=VkPhysicalDeviceProperties.calloc(stack);vkGetPhysicalDeviceProperties(context.physical,props);period=props.limits().timestampPeriod();
                var n=stack.ints(0);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,n,null);var families=VkQueueFamilyProperties.calloc(n.get(0),stack);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,n,families);bits=families.get(context.queueFamily).timestampValidBits();if(bits==0)throw new IllegalStateException("Missing timestamps");
            }
            for(int i=0;i<SLOTS;i++) {
                Slot s=slots[i]=new Slot();glGenQueries(s.glTimes);s.stats=glGenQueries();
                if(shared) {
                    s.color=new SharedPresentImage(context,width,height,GlInterop::importTerrain);s.depth=new SharedPresentImage(context,width,height,GlInterop::importTerrain,true);
                    s.copied=new GpuImage(context,width,height,VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_DEPTH_BIT);
                    var p=stack.mallocPointer(MAX_ROUNDS);check(vkAllocateCommandBuffers(context.device,VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(MAX_ROUNDS),p),"micro command buffers");
                    for(int r=0;r<MAX_ROUNDS;r++){s.commands[r]=new VkCommandBuffer(p.get(r),context.device);var h=stack.mallocLong(1);check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT),null,h),"micro fence");s.fences[r]=h.get(0);}
                    initialLease(s,stack);
                    s.mainFbo=fbo(s.color.textureGL(),s.depth.textureGL());
                } else s.mainFbo=fbo(texture(false),texture(true));
                s.copyTexture=texture(true);s.copyFbo=fbo(0,s.copyTexture);
                initializePattern(s.mainFbo);glBindFramebuffer(GL_FRAMEBUFFER,s.copyFbo);glClearBufferfv(GL_DEPTH,0,new float[]{.9375f});
            }
        }
        glFinish();UnifiedGl.check("micro initialization");
    }
    void frame(int index) {
        Slot s=slots[index];s.signalCpu=s.recordCpu=s.submitCpu=s.waitCpu=0;
        glBeginQuery(GL_VERTICES_SUBMITTED_ARB,s.stats);glQueryCounter(s.glTimes[0],GL_TIMESTAMP);
        for(int r=0;r<Math.max(rounds,localVk?1:0);r++) {
            if(!localVk){long t=System.nanoTime();SharedPresentImage.endGroupGL(s.group());s.signalCpu+=System.nanoTime()-t;}
            long t=System.nanoTime();record(index,r,!localVk,true,true);s.recordCpu+=System.nanoTime()-t;
            t=System.nanoTime();submit(s,r,!localVk,!localVk);s.submitCpu+=System.nanoTime()-t;
            if(!localVk){t=System.nanoTime();SharedPresentImage.beginGroupGL(s.group());s.waitCpu+=System.nanoTime()-t;}
        }
        glQueryCounter(s.glTimes[2],GL_TIMESTAMP);
        if(glCopy){glBindFramebuffer(GL_READ_FRAMEBUFFER,s.mainFbo);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,s.copyFbo);glBlitFramebuffer(0,0,width,height,0,0,width,height,GL_DEPTH_BUFFER_BIT,GL_NEAREST);}
        else if(name.startsWith("C_")){glBindFramebuffer(GL_READ_FRAMEBUFFER,s.mainFbo);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,s.copyFbo);}
        glQueryCounter(s.glTimes[3],GL_TIMESTAMP);glQueryCounter(s.glTimes[1],GL_TIMESTAMP);glEndQuery(GL_VERTICES_SUBMITTED_ARB);
        s.glFence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);glFlush();s.pending=true;
    }
    Sample waitSlot(int index) {
        Slot s=slots[index];if(!s.pending)return null;long wait=System.nanoTime();
        int status;do{status=glClientWaitSync(s.glFence,GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000_000L);if(status==GL_WAIT_FAILED)throw new IllegalStateException("micro GL fence");}while(status==GL_TIMEOUT_EXPIRED);
        glDeleteSync(s.glFence);s.glFence=0;
        for(int r=0;r<Math.max(rounds,localVk?1:0);r++)check(vkWaitForFences(context.device,s.fences[r],true,5_000_000_000L),"micro VK complete");
        long waitNs=System.nanoTime()-wait,queryStart=System.nanoTime();s.pending=false;
        double total=(glGetQueryObjectui64(s.glTimes[1],GL_QUERY_RESULT)-glGetQueryObjectui64(s.glTimes[0],GL_QUERY_RESULT))/1e6;
        double depthGl=glCopy?(glGetQueryObjectui64(s.glTimes[3],GL_QUERY_RESULT)-glGetQueryObjectui64(s.glTimes[2],GL_QUERY_RESULT))/1e6:0,body=0,depthVk=0;
        long vertices=glGetQueryObjectui64(s.stats,GL_QUERY_RESULT);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            for(int r=0;r<Math.max(rounds,localVk?1:0);r++) {
                int at=(index*MAX_ROUNDS+r)*QUERIES;var times=stack.mallocLong(vkDependencies?4:2);
                check(vkGetQueryPoolResults(context.device,timestamps,at,times.remaining(),times,8,VK_QUERY_RESULT_64_BIT),"micro times");
                body+=GpuTimers.elapsedTicks(times.get(0),times.get(1),bits)*period/1e6;
                if(vkDependencies)depthVk+=GpuTimers.elapsedTicks(times.get(2),times.get(3),bits)*period/1e6;
                var count=stack.mallocLong(1);check(vkGetQueryPoolResults(context.device,statistics,index*MAX_ROUNDS+r,1,count,8,VK_QUERY_RESULT_64_BIT),"micro zero draw check");vertices+=count.get(0);
            }
        }
        if(vertices!=0)throw new AssertionError("Zero-draw experiment submitted vertices");
        return new Sample(localVk?body:total,body,depthVk,depthGl,vertices,s.signalCpu/1e6,s.recordCpu/1e6,s.submitCpu/1e6,s.waitCpu/1e6,waitNs/1e6,(System.nanoTime()-queryStart)/1e6);
    }
    private void record(int index,int round,boolean interop,boolean acquire,boolean release) {
        Slot s=slots[index];VkCommandBuffer cmd=s.commands[round];int q=(index*MAX_ROUNDS+round)*QUERIES;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            begin(cmd,stack);vkCmdResetQueryPool(cmd,timestamps,q,QUERIES);vkCmdResetQueryPool(cmd,statistics,index*MAX_ROUNDS+round,1);
            stamp(cmd,q);vkCmdBeginQuery(cmd,statistics,index*MAX_ROUNDS+round,0);
            if(interop&&acquire)ownership(cmd,s,stack,true);
            if(vkDependencies&&acquire&&release)depth(cmd,s,stack,q);
            if(interop&&release)ownership(cmd,s,stack,false);
            vkCmdEndQuery(cmd,statistics,index*MAX_ROUNDS+round);stamp(cmd,q+1);check(vkEndCommandBuffer(cmd),"micro command end");
        }
    }
    private void depth(VkCommandBuffer cmd,Slot s,MemoryStack stack,int q) {
        stamp(cmd,q+2);var b=VkImageMemoryBarrier2.calloc(2,stack);
        image(b.get(0),s.depth.image,true,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
        image(b.get(1),s.copied.image,true,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
        vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
        if(vkCopy)vkCmdCopyImage(cmd,s.depth.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,s.copied.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkImageCopy.calloc(1,stack).srcSubresource(v->v.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).dstSubresource(v->v.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).extent(e->e.set(width,height,1)));
        var next=VkImageMemoryBarrier2.calloc(3,stack);
        image(next.get(0),s.depth.image,true,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
        image(next.get(1),s.copied.image,true,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
        image(next.get(2),s.color.image,false,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
        vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(next));stamp(cmd,q+3);
    }
    private void ownership(VkCommandBuffer cmd,Slot s,MemoryStack stack,boolean acquire) {
        int color=attachmentLayouts?VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_GENERAL,depth=attachmentLayouts?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_GENERAL;
        var b=VkImageMemoryBarrier2.calloc(2,stack);
        image(b.get(0),s.color.image,false,acquire?VK_IMAGE_LAYOUT_GENERAL:color,acquire?color:VK_IMAGE_LAYOUT_GENERAL,acquire?VK_PIPELINE_STAGE_2_NONE:VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,acquire?0:VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT,acquire?VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT:VK_PIPELINE_STAGE_2_NONE,acquire?VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT:0,acquire?VK_QUEUE_FAMILY_EXTERNAL:context.queueFamily,acquire?context.queueFamily:VK_QUEUE_FAMILY_EXTERNAL);
        image(b.get(1),s.depth.image,true,acquire?VK_IMAGE_LAYOUT_GENERAL:depth,acquire?depth:VK_IMAGE_LAYOUT_GENERAL,acquire?VK_PIPELINE_STAGE_2_NONE:VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,acquire?0:VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT,acquire?DEPTH_STAGES:VK_PIPELINE_STAGE_2_NONE,acquire?VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT:0,acquire?VK_QUEUE_FAMILY_EXTERNAL:context.queueFamily,acquire?context.queueFamily:VK_QUEUE_FAMILY_EXTERNAL);
        vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
    }
    private void submit(Slot s,int r,boolean wait,boolean signal) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var info=VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(s.commands[r]));
            if(wait)info.pWaitSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(s.color.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));
            if(signal)info.pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(s.color.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));
            check(vkResetFences(context.device,s.fences[r]),"micro reset fence");check(vkQueueSubmit2(context.queue,info,s.fences[r]),"micro submit");
        }
    }
    /** One direction only; synchronized completion latency. Reverse/reset work is explicitly outside each sample. */
    double oneWay(boolean glToVk) {
        Slot s=slots[0];
        if(glToVk) {
            glFinish();long start=System.nanoTime();SharedPresentImage.endGroupGL(s.group());record(0,0,true,true,false);submit(s,0,true,false);check(vkWaitForFences(context.device,s.fences[0],true,5_000_000_000L),"one-way acquire complete");double ms=(System.nanoTime()-start)/1e6;
            record(0,0,true,false,true);submit(s,0,false,true);SharedPresentImage.beginGroupGL(s.group());glFinish();check(vkWaitForFences(context.device,s.fences[0],true,5_000_000_000L),"one-way reset complete");return ms;
        }
        SharedPresentImage.endGroupGL(s.group());record(0,0,true,true,false);submit(s,0,true,false);check(vkWaitForFences(context.device,s.fences[0],true,5_000_000_000L),"one-way prepare complete");
        long start=System.nanoTime();record(0,0,true,false,true);submit(s,0,false,true);SharedPresentImage.beginGroupGL(s.group());glFinish();double ms=(System.nanoTime()-start)/1e6;
        check(vkWaitForFences(context.device,s.fences[0],true,5_000_000_000L),"one-way release complete");return ms;
    }
    void verify() {
        for(int i=0;i<SLOTS;i++) {
            Slot s=slots[i];float[] expected=readDepth(s.mainFbo);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                float want=x<width/2&&y<height/2?.25f:x>=width/2&&y>=height/2?.75f:.625f;
                if(expected[y*width+x]!=want)throw new AssertionError("Shared ownership changed depth pattern");
            }
            if(glCopy&&!Arrays.equals(expected,readDepth(s.copyFbo)))throw new AssertionError("GL depth copy mismatch");
            if(vkCopy&&!Arrays.equals(expected,readVkDepth(i)))throw new AssertionError("Vulkan depth copy mismatch");
            glBindFramebuffer(GL_READ_FRAMEBUFFER,s.mainFbo);ByteBuffer rgba=MemoryUtil.memAlloc(width*height*4);
            try{glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,rgba);for(int p=0;p<rgba.capacity();p+=4)if((rgba.get(p)&255)!=8||(rgba.get(p+1)&255)!=16||(rgba.get(p+2)&255)!=24||(rgba.get(p+3)&255)!=255)throw new AssertionError("Shared ownership changed color");}finally{MemoryUtil.memFree(rgba);}
        }
        UnifiedGl.check("micro full-image conformance");
    }
    private float[] readVkDepth(int index) {
        Slot s=slots[index];
        try(GpuBuffer host=new GpuBuffer(context,width*height*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT);MemoryStack stack=MemoryStack.stackPush()) {
            VkCommandBuffer cmd=s.commands[0];begin(cmd,stack);var b=VkImageMemoryBarrier2.calloc(1,stack);
            image(b.get(0),s.copied.image,true,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT|VK_ACCESS_2_MEMORY_READ_BIT,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));vkCmdCopyImageToBuffer(cmd,s.copied.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,host.buffer,VkBufferImageCopy.calloc(1,stack).imageSubresource(v->v.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).imageExtent(e->e.set(width,height,1)));
            image(b.get(0),s.copied.image,true,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b).pMemoryBarriers(VkMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)));
            check(vkEndCommandBuffer(cmd),"micro readback end");submit(s,0,false,false);check(vkWaitForFences(context.device,s.fences[0],true,5_000_000_000L),"micro readback complete");
            float[] result=new float[width*height];host.mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN).clear().asFloatBuffer().get(result);return result;
        }
    }
    private void initialLease(Slot s,MemoryStack stack) {
        begin(s.commands[0],stack);var b=VkImageMemoryBarrier2.calloc(2,stack);
        image(b.get(0),s.color.image,false,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_NONE,0,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL);
        image(b.get(1),s.depth.image,true,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_NONE,0,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL);
        vkCmdPipelineBarrier2(s.commands[0],VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));check(vkEndCommandBuffer(s.commands[0]),"micro initial lease end");submit(s,0,false,true);SharedPresentImage.beginGroupGL(s.group());glFinish();check(vkWaitForFences(context.device,s.fences[0],true,5_000_000_000L),"micro initial lease complete");
    }
    private int texture(boolean depth){int t=glGenTextures();glBindTexture(GL_TEXTURE_2D,t);glTexStorage2D(GL_TEXTURE_2D,1,depth?GL_DEPTH_COMPONENT32F:GL_RGBA8,width,height);return t;}
    private static int fbo(int color,int depth){int f=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,f);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);if(color!=0)glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);else{glDrawBuffer(GL_NONE);glReadBuffer(GL_NONE);}if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("micro FBO incomplete");return f;}
    private void initializePattern(int fbo){glBindFramebuffer(GL_FRAMEBUFFER,fbo);glDisable(GL_SCISSOR_TEST);glDisable(GL_DITHER);glDisable(GL_FRAMEBUFFER_SRGB);glColorMask(true,true,true,true);glDepthMask(true);glClearBufferfv(GL_COLOR,0,new float[]{.03125f,.0625f,.09375f,1});glClearBufferfv(GL_DEPTH,0,new float[]{.625f});glEnable(GL_SCISSOR_TEST);glScissor(0,0,width/2,height/2);glClearBufferfv(GL_DEPTH,0,new float[]{.25f});glScissor(width/2,height/2,width-width/2,height-height/2);glClearBufferfv(GL_DEPTH,0,new float[]{.75f});glDisable(GL_SCISSOR_TEST);}
    private float[] readDepth(int fbo){glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);FloatBuffer b=MemoryUtil.memAllocFloat(width*height);try{glReadPixels(0,0,width,height,GL_DEPTH_COMPONENT,GL_FLOAT,b);float[] result=new float[width*height];b.get(result);return result;}finally{MemoryUtil.memFree(b);}}
    private static void begin(VkCommandBuffer cmd,MemoryStack stack){check(vkResetCommandBuffer(cmd,0),"micro reset command");check(vkBeginCommandBuffer(cmd,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"micro begin command");}
    private void stamp(VkCommandBuffer cmd,int q){vkCmdWriteTimestamp2(cmd,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,timestamps,q);}
    private static void image(VkImageMemoryBarrier2 b,long image,boolean depth,int oldLayout,int newLayout,long srcStage,long srcAccess,long dstStage,long dstAccess,int srcFamily,int dstFamily){b.sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(srcFamily).dstQueueFamilyIndex(dstFamily).image(image).subresourceRange(r->r.aspectMask(depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));}
    public void close(){for(int i=0;i<SLOTS;i++)waitSlot(i);glFinish();for(Slot s:slots){glDeleteQueries(s.glTimes);glDeleteQueries(s.stats);if(!shared){glBindFramebuffer(GL_FRAMEBUFFER,s.mainFbo);for(int attachment:new int[]{GL_COLOR_ATTACHMENT0,GL_DEPTH_ATTACHMENT})glDeleteTextures(glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,attachment,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME));}glDeleteFramebuffers(s.mainFbo);glDeleteFramebuffers(s.copyFbo);glDeleteTextures(s.copyTexture);if(shared){SharedPresentImage.endGroupGL(s.group());glFinish();s.copied.close();s.color.close();s.depth.close();for(long f:s.fences)vkDestroyFence(context.device,f,null);}}if(shared){vkDestroyQueryPool(context.device,timestamps,null);vkDestroyQueryPool(context.device,statistics,null);vkDestroyCommandPool(context.device,pool,null);}}
}
