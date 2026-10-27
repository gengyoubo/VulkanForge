package github.com.gengyoubo.vulkanforge.lab;

import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;
import static org.lwjgl.vulkan.VK13.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdWriteTimestamp2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;

/** Experimental final-color-only export. Probe/ready controls are diagnostics, never production performance modes. */
final class FinalColorBoundary implements NativeFrameBoundary {
    enum Probe { NONE, NORMAL, INPUT_READY, OUTPUT_READY, BOTH_READY }
    private final VulkanContext context;
    private final SharedPresentImage[] outputs=new SharedPresentImage[2];
    private final VkCommandBuffer[] commands=new VkCommandBuffer[2];
    private final boolean[] leased=new boolean[2],probed=new boolean[2];
    private final int[][] glQueries=new int[2][2];
    private final Probe probe;
    private final boolean early=Boolean.getBoolean("bench.releaseEarly");
    private final boolean fixedProbe=Boolean.getBoolean("bench.probeBeforeEarlyRelease");
    private final boolean[] earlyProbeQueued=new boolean[2];
    private long pool,queryPool,controlCpuNanos,probeSubmits;
    FinalColorBoundary(VulkanContext context,int width,int height,Probe probe) {
        this.context=context;this.probe=probe;
        try(var stack=MemoryStack.stackPush()) {
            var h=stack.mallocLong(1);
            check(vkCreateCommandPool(context.device,VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),null,h),"final boundary pool");pool=h.get(0);
            var p=stack.mallocPointer(2);check(vkAllocateCommandBuffers(context.device,VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(2),p),"final boundary commands");
            check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2),null,h),"boundary probe queries");queryPool=h.get(0);
            for(int s=0;s<2;s++) {
                commands[s]=new VkCommandBuffer(p.get(s),context.device);outputs[s]=new SharedPresentImage(context,width,height,GlInterop::importTerrain);glGenQueries(glQueries[s]);
                begin(commands[s],stack);transition(commands[s],stack,s,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT);
                vkCmdClearColorImage(commands[s],outputs[s].image,VK_IMAGE_LAYOUT_GENERAL,VkClearColorValue.calloc(stack).float32(0,.125f).float32(1,.25f).float32(2,.5f).float32(3,1),VkImageSubresourceRange.calloc(1,stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
                transition(commands[s],stack,s,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_GENERAL,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_NONE,0);
                check(vkEndCommandBuffer(commands[s]),"initialize final color end");
                check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default(),null,h),"final color init fence");long fence=h.get(0);
                try {
                    check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(commands[s])).pSignalSemaphoreInfos(signals(stack,s,1)),fence),"initialize final color");
                    SharedPresentImage.beginGroupGL(outputs[s]);leased[s]=true;glFinish();check(vkWaitForFences(context.device,fence,true,5_000_000_000L),"initialize final color wait");
                } finally {vkDestroyFence(context.device,fence,null);}
            }
        }
    }
    public VulkanRenderImage color(int slot,int index){return index==1?outputs[slot]:null;}
    public void validatePlan(NativeFramePlan plan){int n=plan.posts().size();if(n!=0&&n!=3)throw new UnsupportedOperationException("Final-color experiment supports diagnostic terrain or exactly three posts; shared intermediate ownership is not implicit");}
    int texture(int slot){if(!leased[slot])throw new IllegalStateException("GL does not own output");return outputs[slot].textureGL();}
    public int sharedImages(){return 2;}
    public void beforeRecord(int slot,int result){if(early&&result==1){if(fixedProbe&&probe!=Probe.NONE){enqueueProbe(slot);earlyProbeQueued[slot]=true;}releaseGL(slot);}}
    public void acquire(VkCommandBuffer command,MemoryStack stack,int slot,int result){if(result==1)transition(command,stack,slot,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_GENERAL,VK_QUEUE_FAMILY_EXTERNAL,context.queueFamily,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT);}
    public void release(VkCommandBuffer command,MemoryStack stack,int slot,int result){if(result==1)transition(command,stack,slot,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_PIPELINE_STAGE_2_NONE,0);}
    public void releaseIdle(VkCommandBuffer command,MemoryStack stack,int slot){transition(command,stack,slot,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_GENERAL,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_NONE,0);}
    private void releaseGL(int slot){if(!leased[slot])throw new IllegalStateException("Output GL release repeated");SharedPresentImage.endGroupGL(outputs[slot]);leased[slot]=false;}
    public void beforeSubmit(int slot,int result) {
        if(result!=1)return;
        if(probe==Probe.INPUT_READY||probe==Probe.BOTH_READY){if(leased[slot])releaseGL(slot);long start=System.nanoTime();glFinish();controlCpuNanos+=System.nanoTime()-start;enqueueProbe(slot);}
        else {if(!earlyProbeQueued[slot])enqueueProbe(slot);earlyProbeQueued[slot]=false;if(leased[slot])releaseGL(slot);}
    }
    private void enqueueProbe(int slot) {
        if(probe==Probe.NONE)return;
        try(var stack=MemoryStack.stackPush()) {
            begin(commands[slot],stack);vkCmdResetQueryPool(commands[slot],queryPool,slot,1);vkCmdWriteTimestamp2(commands[slot],VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,queryPool,slot);check(vkEndCommandBuffer(commands[slot]),"end boundary probe");
            check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(commands[slot])),0),"boundary probe submit");probeSubmits++;probed[slot]=true;
        }
    }
    public VkSemaphoreSubmitInfo.Buffer waits(MemoryStack stack,int slot,int result){return result==1?VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(outputs[slot].complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT):null;}
    public VkSemaphoreSubmitInfo.Buffer signals(MemoryStack stack,int slot,int result){return result==1?VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(outputs[slot].ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT):null;}
    public void submitted(int slot,int result,long fence) {
        if(result!=1)return;
        if(probe==Probe.OUTPUT_READY||probe==Probe.BOTH_READY){long start=System.nanoTime();check(vkWaitForFences(context.device,fence,true,5_000_000_000L),"diagnostic output-ready control");controlCpuNanos+=System.nanoTime()-start;}
        if(probe!=Probe.NONE)glQueryCounter(glQueries[slot][0],GL_TIMESTAMP);
        SharedPresentImage.beginGroupGL(outputs[slot]);leased[slot]=true;
        if(probe!=Probe.NONE)glQueryCounter(glQueries[slot][1],GL_TIMESTAMP);
    }
    /** Each difference uses one API's clock. Gate span includes availability and scheduling, not a pure semaphore latency. */
    double[] collect(int slot,NativeWorldRenderer renderer,long entry) {
        if(!probed[slot])return new double[2];probed[slot]=false;
        try(var stack=MemoryStack.stackPush()) {
            var ticks=stack.mallocLong(1);check(vkGetQueryPoolResults(context.device,queryPool,slot,1,ticks,8,VK_QUERY_RESULT_64_BIT),"boundary probe result");
            return new double[]{renderer.elapsedMs(ticks.get(0),entry),(glGetQueryObjectui64(glQueries[slot][1],GL_QUERY_RESULT)-glGetQueryObjectui64(glQueries[slot][0],GL_QUERY_RESULT))/1e6};
        }
    }
    long[] cpuStats(){return new long[]{controlCpuNanos,probeSubmits};}
    private void transition(VkCommandBuffer command,MemoryStack stack,int slot,int old,int next,int from,int to,long src,long srcAccess,long dst,long dstAccess) {
        var b=VkImageMemoryBarrier2.calloc(1,stack).sType$Default().image(outputs[slot].image).oldLayout(old).newLayout(next).srcQueueFamilyIndex(from).dstQueueFamilyIndex(to).srcStageMask(src).srcAccessMask(srcAccess).dstStageMask(dst).dstAccessMask(dstAccess).subresourceRange(r->r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
        vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
    }
    private static void begin(VkCommandBuffer command,MemoryStack stack){check(vkResetCommandBuffer(command,0),"reset boundary command");check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"begin boundary command");}
    public void close(){for(int s=0;s<2;s++)if(leased[s])releaseGL(s);glFinish();vkQueueWaitIdle(context.queue);for(int[] q:glQueries)glDeleteQueries(q);vkDestroyQueryPool(context.device,queryPool,null);vkDestroyCommandPool(context.device,pool,null);}
}
