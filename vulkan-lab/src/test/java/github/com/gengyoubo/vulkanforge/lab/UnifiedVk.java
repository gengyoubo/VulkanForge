package github.com.gengyoubo.vulkanforge.lab;

import github.com.gengyoubo.vulkanforge.interop.GlInterop;
import java.nio.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdWriteTimestamp2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkQueueSubmit2;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static github.com.gengyoubo.vulkanforge.lab.UnifiedWorkload.*;

/** Controlled Vulkan counterpart: same records, input attributes, fixed-slot compute kernel and blend/depth semantics. */
final class UnifiedVk implements AutoCloseable {
    private static final long DEPTH_STAGES=VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
    private static final class Slot {
        VkCommandBuffer command;long fence,set;boolean pending;UnifiedSubmissions submissions;int copies,handOffs;
        GpuImage color,depth,copiedDepth;SharedPresentImage sharedColor,sharedDepth;GpuBuffer commands;
        long color(){return sharedColor!=null?sharedColor.image:color.image;}
        long depth(){return sharedDepth!=null?sharedDepth.image:depth.image;}
        long colorView(){return sharedColor!=null?sharedColor.view():color.view;}
        long depthView(){return sharedDepth!=null?sharedDepth.view():depth.view;}
    }
    final VulkanContext context;final UnifiedWorkload scene;final int level,width,height;
    final Slot[] slots=new Slot[SLOTS];final UnifiedGl gl;
    private final GpuBuffer vertices,indices,ids,records,parameters;
    private final long commandPool,descriptorLayout,descriptorPool,layout,opaquePipeline,transparentPipeline,computePipeline,timestamps,statistics;
    private final double timestampPeriod;private final int timestampBits;
    UnifiedVk(VulkanContext context,UnifiedWorkload scene,int level,int width,int height) throws Exception {
        this(context,scene,level,width,height,true);
    }
    UnifiedVk(VulkanContext context,UnifiedWorkload scene,int level,int width,int height,boolean drawCompatibility) throws Exception {
        this.context=context;this.scene=scene;this.level=level;this.width=width;this.height=height;
        if(!context.pipelineStatisticsEnabled)throw new IllegalStateException("Vulkan pipeline statistics unsupported");
        if(level!=BASELINE&&(!context.capabilities.gpuDriven()||Boolean.getBoolean("vulkanforge.disableGpuDriven")))throw new IllegalStateException("Matched multi-draw features unavailable");
        if(context.capabilities.maxIndirectDraws()<scene.core(level))throw new IllegalStateException("Draw count exceeds maxDrawIndirectCount");
        vertices=upload(scene.mesh,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);indices=upload(scene.indices,VK_BUFFER_USAGE_INDEX_BUFFER_BIT);ids=upload(scene.ids,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);records=upload(scene.records,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);parameters=upload(bytes(16).putInt(scene.core(level)).array(),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var handle=stack.mallocLong(1);var ptr=stack.mallocPointer(SLOTS);
            check(vkCreateCommandPool(context.device,VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),null,handle),"unified command pool");commandPool=handle.get(0);
            check(vkAllocateCommandBuffers(context.device,VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOTS),ptr),"unified commands");
            for(int s=0;s<SLOTS;s++) {
                Slot slot=slots[s]=new Slot();slot.submissions=new UnifiedSubmissions(scene);slot.command=new VkCommandBuffer(ptr.get(s),context.device);
                check(vkCreateFence(context.device,VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT),null,handle),"unified fence");slot.fence=handle.get(0);
                slot.commands=upload(scene.commands(level),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                if(level==FULL){slot.sharedColor=new SharedPresentImage(context,width,height,GlInterop::importTerrain);slot.sharedDepth=new SharedPresentImage(context,width,height,GlInterop::importTerrain,true);slot.copiedDepth=new GpuImage(context,width,height,VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,VK_IMAGE_ASPECT_DEPTH_BIT);}
                else {slot.color=new GpuImage(context,width,height,VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT);slot.depth=new GpuImage(context,width,height,VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_DEPTH_BIT);}
            }
            var bindings=VkDescriptorSetLayoutBinding.calloc(3,stack);for(int i=0;i<3;i++)bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT|(i==0?VK_SHADER_STAGE_VERTEX_BIT:0));
            check(vkCreateDescriptorSetLayout(context.device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,handle),"unified descriptor layout");descriptorLayout=handle.get(0);
            check(vkCreatePipelineLayout(context.device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)),null,handle),"unified layout");layout=handle.get(0);
            check(vkCreateDescriptorPool(context.device,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(SLOTS).pPoolSizes(VkDescriptorPoolSize.calloc(1,stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(SLOTS*3)),null,handle),"unified descriptor pool");descriptorPool=handle.get(0);
            for(Slot slot:slots) {
                check(vkAllocateDescriptorSets(context.device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)),handle),"unified descriptor");slot.set=handle.get(0);
                GpuBuffer[] buffers={records,slot.commands,parameters};var info=VkDescriptorBufferInfo.calloc(3,stack);var writes=VkWriteDescriptorSet.calloc(3,stack);
                for(int i=0;i<3;i++){info.get(i).buffer(buffers[i].buffer).offset(0).range(i==0?scene.records.length:i==1?(long)scene.core(level)*COMMAND_BYTES:16);writes.get(i).sType$Default().dstSet(slot.set).dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.create(info.address(i),1));}
                vkUpdateDescriptorSets(context.device,writes,null);
            }
            check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS*8),null,handle),"unified timestamps");timestamps=handle.get(0);
            check(vkCreateQueryPool(context.device,VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_PIPELINE_STATISTICS).queryCount(SLOTS).pipelineStatistics(VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_VERTICES_BIT|VK_QUERY_PIPELINE_STATISTIC_VERTEX_SHADER_INVOCATIONS_BIT),null,handle),"unified statistics");statistics=handle.get(0);
            var properties=VkPhysicalDeviceProperties.calloc(stack);vkGetPhysicalDeviceProperties(context.physical,properties);timestampPeriod=properties.limits().timestampPeriod();
            var count=stack.ints(0);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,count,null);var families=VkQueueFamilyProperties.calloc(count.get(0),stack);vkGetPhysicalDeviceQueueFamilyProperties(context.physical,count,families);timestampBits=families.get(context.queueFamily).timestampValidBits();if(timestampBits==0)throw new IllegalStateException("GPU timestamps unavailable");
        }
        opaquePipeline=graphics(false);transparentPipeline=level==FULL?graphics(true):0;computePipeline=level!=BASELINE?compute():0;
        context.uploads.drain();
        if(level==FULL) {
            int[] colors=new int[SLOTS],depths=new int[SLOTS];
            for(int s=0;s<SLOTS;s++){initialLease(slots[s]);colors[s]=slots[s].sharedColor.textureGL();depths[s]=slots[s].sharedDepth.textureGL();}
            gl=new UnifiedGl(scene,level,width,height,false,colors,depths,drawCompatibility);
        }else gl=null;
    }
    private GpuBuffer upload(byte[] data,int usage) {
        GpuBuffer buffer=new GpuBuffer(context,data.length,usage|VK_BUFFER_USAGE_TRANSFER_DST_BIT,false);
        context.uploads.submit(data,(command,staging,offset,stack)->{
            vkCmdCopyBuffer(command,staging.buffer,buffer.buffer,VkBufferCopy.calloc(1,stack).srcOffset(offset).dstOffset(0).size(data.length));
            var b=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(buffer.buffer).size(data.length);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(b));
        });return buffer;
    }
    private long graphics(boolean blend) throws Exception {
        long vertex=ShaderCompiler.moduleSource(context,shader("unified_draw.vert",true),"unified_draw.vert",shaderc_glsl_vertex_shader),fragment=0;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            fragment=ShaderCompiler.moduleSource(context,shader("unified_draw.frag",true),"unified_draw.frag",shaderc_glsl_fragment_shader);
            var stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));
            var bindings=VkVertexInputBindingDescription.calloc(2,stack);bindings.get(0).binding(0).stride(24).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);bindings.get(1).binding(1).stride(4).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE);
            var attributes=VkVertexInputAttributeDescription.calloc(3,stack);attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(12);attributes.get(2).location(2).binding(1).format(VK_FORMAT_R32_UINT).offset(0);
            var attachment=VkPipelineColorBlendAttachmentState.calloc(1,stack).colorWriteMask(15).blendEnable(blend).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
            var info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(VK_FORMAT_R8G8B8A8_UNORM)).depthAttachmentFormat(VK_FORMAT_D32_SFLOAT).address()).pStages(stages)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default().pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attributes))
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(true).depthWriteEnable(!blend).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(attachment))
                .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR))).layout(layout).basePipelineIndex(-1);
            var handle=stack.mallocLong(1);check(vkCreateGraphicsPipelines(context.device,0,info,null,handle),"unified graphics pipeline");return handle.get(0);
        }finally{if(fragment!=0)vkDestroyShaderModule(context.device,fragment,null);vkDestroyShaderModule(context.device,vertex,null);}
    }
    private long compute() throws Exception {
        long module=ShaderCompiler.moduleSource(context,shader("unified_cull.comp",true),"unified_cull.comp",shaderc_glsl_compute_shader);
        try(MemoryStack stack=MemoryStack.stackPush()){var info=VkComputePipelineCreateInfo.calloc(1,stack).sType$Default().layout(layout);info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));var handle=stack.mallocLong(1);check(vkCreateComputePipelines(context.device,0,info,null,handle),"unified culling pipeline");return handle.get(0);}finally{vkDestroyShaderModule(context.device,module,null);}
    }

    void frame(int index) {
        Slot slot=slots[index];
        slot.submissions.reset();
        slot.copies=slot.handOffs=0;
        if(level==FULL){gl.queries[index].reset();gl.queries[index].stamp(0);gl.clear(index);SharedPresentImage.endGroupGL(slot.sharedColor,slot.sharedDepth);slot.handOffs++;}
        try(MemoryStack stack=MemoryStack.stackPush()) {
            VkCommandBuffer command=slot.command;begin(command,stack);
            vkCmdResetQueryPool(command,timestamps,index*8,8);vkCmdResetQueryPool(command,statistics,index,1);stamp(command,index,6);
            var images=VkImageMemoryBarrier2.calloc(2,stack);
            image(images.get(0),slot.color(),false,level==FULL?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,level==FULL?VK_QUEUE_FAMILY_EXTERNAL:VK_QUEUE_FAMILY_IGNORED,level==FULL?context.queueFamily:VK_QUEUE_FAMILY_IGNORED);
            image(images.get(1),slot.depth(),true,level==FULL?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_NONE,0,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,level==FULL?VK_QUEUE_FAMILY_EXTERNAL:VK_QUEUE_FAMILY_IGNORED,level==FULL?context.queueFamily:VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(images));
            if(level!=BASELINE) {
                stamp(command,index,4);vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,computePipeline);vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,stack.longs(slot.set),null);vkCmdDispatch(command,(scene.core(level)+63)/64,1,1);
                var barrier=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT).srcAccessMask(VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT).dstAccessMask(VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(slot.commands.buffer).size((long)scene.core(level)*COMMAND_BYTES);
                vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));stamp(command,index,5);
            }
            vkCmdBeginQuery(command,statistics,index,0);
            render(command,stack,slot.colorView(),slot.depthView(),level!=FULL);stamp(command,index,0);bind(command,stack,slot,opaquePipeline);draw(command,slot,0,scene.opaque);stamp(command,index,1);vkCmdEndRendering(command);
            if(level==FULL) {
                image(images.get(0),slot.depth(),true,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
                image(images.get(1),slot.copiedDepth.image,true,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
                vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(images));
                vkCmdCopyImage(command,slot.depth(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,slot.copiedDepth.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VkImageCopy.calloc(1,stack).srcSubresource(r->r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).dstSubresource(r->r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).extent(e->e.set(width,height,1)));
                slot.copies++;
                var next=VkImageMemoryBarrier2.calloc(3,stack);
                image(next.get(0),slot.depth(),true,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
                image(next.get(1),slot.copiedDepth.image,true,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
                image(next.get(2),slot.color(),false,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
                vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(next));
                render(command,stack,slot.colorView(),slot.copiedDepth.view,false);stamp(command,index,2);bind(command,stack,slot,transparentPipeline);draw(command,slot,scene.opaque,scene.transparent);stamp(command,index,3);vkCmdEndRendering(command);
            }
            vkCmdEndQuery(command,statistics,index);
            if(level==FULL) {
                image(images.get(0),slot.color(),false,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_NONE,0,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL);
                image(images.get(1),slot.depth(),true,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT,VK_PIPELINE_STAGE_2_NONE,0,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL);
                vkCmdPipelineBarrier2(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(images));
            }
            stamp(command,index,7);check(vkEndCommandBuffer(command),"unified frame end");
            var submit=VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(command));
            if(level==FULL){submit.pWaitSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(slot.sharedColor.complete).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));submit.pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(slot.sharedColor.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));}
            check(vkResetFences(context.device,slot.fence),"unified reset fence");check(vkQueueSubmit2(context.queue,submit,slot.fence),"unified frame submit");slot.pending=true;
        }
        if(level==FULL){SharedPresentImage.beginGroupGL(slot.sharedColor,slot.sharedDepth);slot.handOffs++;gl.post(index);gl.queries[index].stamp(1);gl.submit(index);}
    }
    UnifiedGl.Metrics waitSlot(int index) {
        Slot slot=slots[index];if(!slot.pending)return null;
        UnifiedGl.Metrics tail=level==FULL?gl.waitSlot(index):null;check(vkWaitForFences(context.device,slot.fence,true,5_000_000_000L),"unified completed frame");slot.pending=false;slot.submissions.completed();
        if(depthCopies(index)!=(level==FULL?2:0)||postPasses(index)!=(level==FULL?1:0)||handOffs(index)!=(level==FULL?2:0))throw new AssertionError("Vulkan frame operation graph changed");
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var times=stack.callocLong(16);int result=vkGetQueryPoolResults(context.device,timestamps,index*8,8,times,16,VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);if(result!=VK_NOT_READY)check(result,"unified GPU times");
            for(int q:new int[]{0,1,6,7})if(times.get(q*2+1)==0)throw new IllegalStateException("Completed timestamp unavailable");
            if(level==FULL&&(times.get(5)==0||times.get(7)==0))throw new IllegalStateException("Translucent timestamps unavailable");
            if(level!=BASELINE&&(times.get(9)==0||times.get(11)==0))throw new IllegalStateException("Compute timestamps unavailable");
            var counters=stack.callocLong(3);check(vkGetQueryPoolResults(context.device,statistics,index,1,counters,24,VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WITH_AVAILABILITY_BIT),"unified GPU vertices");if(counters.get(2)==0)throw new IllegalStateException("Pipeline statistics unavailable");
            double opaque=delta(times,0),transparent=level==FULL?delta(times,2):0,compute=level!=BASELINE?delta(times,4):0;
            return new UnifiedGl.Metrics(level==FULL?tail.timeline():delta(times,6),opaque+transparent,compute,tail==null?0:tail.post(),tail==null?0:tail.fallback(),counters.get(0),counters.get(1),tail==null?0:tail.fallbackVertices());
        }
    }
    private double delta(LongBuffer times,int q){return GpuTimers.elapsedTicks(times.get(q*2),times.get((q+1)*2),timestampBits)*timestampPeriod/1e6;}
    private void draw(VkCommandBuffer command,Slot slot,int start,int count){if(level==BASELINE)for(int id=start;id<start+count;id++){slot.submissions.direct(id,false);vkCmdDrawIndexed(command,INDEX_COUNT,1,0,0,id);}else{slot.submissions.indirect(start,count);vkCmdDrawIndexedIndirect(command,slot.commands.buffer,(long)start*COMMAND_BYTES,count,COMMAND_BYTES);}}
    private void bind(VkCommandBuffer command,MemoryStack stack,Slot slot,long pipeline){vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,stack.longs(slot.set),null);vkCmdBindVertexBuffers(command,0,stack.longs(vertices.buffer,ids.buffer),stack.longs(0,0));vkCmdBindIndexBuffer(command,indices.buffer,0,VK_INDEX_TYPE_UINT32);}
    private void render(VkCommandBuffer command,MemoryStack stack,long color,long depth,boolean clear) {
        var colorAttachment=VkRenderingAttachmentInfo.calloc(1,stack).sType$Default().imageView(color).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(clear?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);colorAttachment.clearValue().color().float32(0,.03125f).float32(1,.0625f).float32(2,.09375f).float32(3,1);
        var depthAttachment=VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(depth).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(clear?VK_ATTACHMENT_LOAD_OP_CLEAR:VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);depthAttachment.clearValue().depthStencil().depth(1);
        vkCmdBeginRendering(command,VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(colorAttachment).pDepthAttachment(depthAttachment).renderArea(r->r.extent(e->e.set(width,height))));vkCmdSetViewport(command,0,VkViewport.calloc(1,stack).width(width).height(height).maxDepth(1));vkCmdSetScissor(command,0,VkRect2D.calloc(1,stack).extent(e->e.set(width,height)));
    }
    private void stamp(VkCommandBuffer command,int slot,int query){vkCmdWriteTimestamp2(command,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,timestamps,slot*8+query);}
    private static void begin(VkCommandBuffer command,MemoryStack stack){check(vkResetCommandBuffer(command,0),"unified command reset");check(vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"unified command begin");}
    private static void image(VkImageMemoryBarrier2 b,long image,boolean depth,int oldLayout,int newLayout,long srcStage,long srcAccess,long dstStage,long dstAccess,int srcFamily,int dstFamily){b.sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess).oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(srcFamily).dstQueueFamilyIndex(dstFamily).image(image).subresourceRange(r->r.aspectMask(depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));}
    private void initialLease(Slot slot) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            begin(slot.command,stack);var b=VkImageMemoryBarrier2.calloc(2,stack);
            image(b.get(0),slot.color(),false,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_NONE,0,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL);image(b.get(1),slot.depth(),true,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_2_NONE,0,VK_PIPELINE_STAGE_2_NONE,0,context.queueFamily,VK_QUEUE_FAMILY_EXTERNAL);
            vkCmdPipelineBarrier2(slot.command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));check(vkEndCommandBuffer(slot.command),"unified initial release");check(vkResetFences(context.device,slot.fence),"unified initial fence");
            var submit=VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(slot.command)).pSignalSemaphoreInfos(VkSemaphoreSubmitInfo.calloc(1,stack).sType$Default().semaphore(slot.sharedColor.ready).stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT));
            check(vkQueueSubmit2(context.queue,submit,slot.fence),"unified initial lease");check(vkWaitForFences(context.device,slot.fence,true,5_000_000_000L),"unified initial completion");
        }
        SharedPresentImage.beginGroupGL(slot.sharedColor,slot.sharedDepth);
    }
    ByteBuffer commands(int slot) {
        int size=scene.core(level)*COMMAND_BYTES;
        try(GpuBuffer host=new GpuBuffer(context,size,VK_BUFFER_USAGE_TRANSFER_DST_BIT);MemoryStack stack=MemoryStack.stackPush()) {
            Slot s=slots[slot];begin(s.command,stack);
            var barrier=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT|VK_ACCESS_2_MEMORY_READ_BIT).dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(s.commands.buffer).size(size);
            vkCmdPipelineBarrier2(s.command,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));vkCmdCopyBuffer(s.command,s.commands.buffer,host.buffer,VkBufferCopy.calloc(1,stack).size(size));finishAudit(s,stack);
            return s.submissions.actualCommands(host.mapped.duplicate().clear(),scene.core(level));
        }
    }
    int[] fallbackIDs(int slot){return gl==null?new int[0]:gl.submissions[slot].fallbacks();}
    int coreCalls(int slot){return slots[slot].submissions.calls();}
    int commandSlots(int slot){return slots[slot].submissions.slots();}
    int depthCopies(int slot){return slots[slot].copies+(gl==null?0:gl.depthCopies[slot]);}
    int postPasses(int slot){return gl==null?0:gl.postPasses[slot];}
    int handOffs(int slot){return slots[slot].handOffs;}
    record Pixels(byte[] color,float[] depth) { }
    Pixels pixels(int slot) {
        if(level==FULL)return new Pixels(gl.color(slot),gl.depth(slot));
        int size=width*height*4;
        try(GpuBuffer color=new GpuBuffer(context,size,VK_BUFFER_USAGE_TRANSFER_DST_BIT);GpuBuffer depth=new GpuBuffer(context,size,VK_BUFFER_USAGE_TRANSFER_DST_BIT);MemoryStack stack=MemoryStack.stackPush()) {
            Slot s=slots[slot];begin(s.command,stack);var b=VkImageMemoryBarrier2.calloc(2,stack);
            image(b.get(0),s.color(),false,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);image(b.get(1),s.depth(),true,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(s.command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
            for(int i=0;i<2;i++){final int aspect=i==0?VK_IMAGE_ASPECT_COLOR_BIT:VK_IMAGE_ASPECT_DEPTH_BIT;vkCmdCopyImageToBuffer(s.command,i==0?s.color():s.depth(),VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,i==0?color.buffer:depth.buffer,VkBufferImageCopy.calloc(1,stack).imageSubresource(r->r.aspectMask(aspect).layerCount(1)).imageExtent(e->e.set(width,height,1)));}
            image(b.get(0),s.color(),false,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);image(b.get(1),s.depth(),true,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COPY_BIT,VK_ACCESS_2_TRANSFER_READ_BIT,DEPTH_STAGES,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED);
            vkCmdPipelineBarrier2(s.command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));finishAudit(s,stack);
            byte[] rgba=new byte[size];color.mapped.duplicate().clear().get(rgba);float[] z=new float[width*height];depth.mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN).clear().asFloatBuffer().get(z);return new Pixels(rgba,z);
        }
    }
    private void finishAudit(Slot slot,MemoryStack stack){vkCmdPipelineBarrier2(slot.command,VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(VkMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT).dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)));check(vkEndCommandBuffer(slot.command),"unified audit end");check(vkResetFences(context.device,slot.fence),"unified audit fence");check(vkQueueSubmit2(context.queue,VkSubmitInfo2.calloc(1,stack).sType$Default().pCommandBufferInfos(VkCommandBufferSubmitInfo.calloc(1,stack).sType$Default().commandBuffer(slot.command)),slot.fence),"unified audit submit");check(vkWaitForFences(context.device,slot.fence,true,5_000_000_000L),"unified audit complete");}
    public void close() {
        for(int i=0;i<SLOTS;i++)waitSlot(i);
        if(gl!=null){gl.close();for(Slot slot:slots)SharedPresentImage.endGroupGL(slot.sharedColor,slot.sharedDepth);org.lwjgl.opengl.GL11.glFinish();}
        for(long p:new long[]{opaquePipeline,transparentPipeline,computePipeline})if(p!=0)vkDestroyPipeline(context.device,p,null);vkDestroyPipelineLayout(context.device,layout,null);vkDestroyDescriptorPool(context.device,descriptorPool,null);vkDestroyDescriptorSetLayout(context.device,descriptorLayout,null);vkDestroyQueryPool(context.device,timestamps,null);vkDestroyQueryPool(context.device,statistics,null);
        for(Slot slot:slots){slot.commands.close();if(slot.color!=null)slot.color.close();if(slot.depth!=null)slot.depth.close();if(slot.copiedDepth!=null)slot.copiedDepth.close();if(slot.sharedColor!=null)slot.sharedColor.close();if(slot.sharedDepth!=null)slot.sharedDepth.close();vkDestroyFence(context.device,slot.fence,null);}for(GpuBuffer b:new GpuBuffer[]{vertices,indices,ids,records,parameters})b.close();vkDestroyCommandPool(context.device,commandPool,null);
    }
}
