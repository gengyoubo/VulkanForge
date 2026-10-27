package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;

/** Compiles live post GLSL and samples shared targets directly. Resources are isolated by world slot and pass. */
final class NativePost implements AutoCloseable {
    private final VulkanContext context;
    private final Map<String,Program> programs=new HashMap<>();
    private record Program(long pipeline,long layout,long setLayout,long pool,long[] sets,GpuBuffer[] uniforms,int samplers){}
    private final long[] samplers=new long[2];
    private final int slots;
    NativePost(VulkanContext context,int slots){this.context=context;this.slots=slots;try(var stack=MemoryStack.stackPush()){
        var out=stack.mallocLong(1);for(int i=0;i<2;i++){check(vkCreateSampler(context.device,VkSamplerCreateInfo.calloc(stack).sType$Default().minFilter(i==0?VK_FILTER_NEAREST:VK_FILTER_LINEAR).magFilter(i==0?VK_FILTER_NEAREST:VK_FILTER_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),null,out),"post sampler");samplers[i]=out.get(0);}
    } catch(Throwable error){close();throw error;}}
    void prepare(Object[] packet){program(packet);}
    private Program program(Object[] packet){
        int[] blend=packet.length>6?(int[])packet[6]:new int[]{1,0,1,0,32774};
        String vertex=(String)packet[1],fragment=(String)packet[2],key=vertex+'\0'+fragment+'\0'+Arrays.toString(blend);
        return programs.computeIfAbsent(key,unused->{long setLayout=0,layout=0,pool=0;try(var stack=MemoryStack.stackPush()){
            int count=((int[])packet[3]).length/5;if(count<1||count>16)throw new IllegalArgumentException("Post sampler count "+count);
            var handle=stack.mallocLong(1);var bindings=VkDescriptorSetLayoutBinding.calloc(count+1,stack);
            for(int i=0;i<count;i++)bindings.get(i).binding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(count).binding(count).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(context.device,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,handle),"post descriptor layout");setLayout=handle.get(0);
            check(vkCreatePipelineLayout(context.device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)),null,handle),"post pipeline layout");layout=handle.get(0);
            var sizes=VkDescriptorPoolSize.calloc(2,stack);sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(slots*count);sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(slots);
            check(vkCreateDescriptorPool(context.device,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(slots).pPoolSizes(sizes),null,handle),"post descriptors");pool=handle.get(0);
            long[] sets=new long[slots];var layouts=stack.mallocLong(slots);for(int i=0;i<slots;i++)layouts.put(setLayout);layouts.flip();var allocated=stack.mallocLong(slots);
            check(vkAllocateDescriptorSets(context.device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(layouts),allocated),"post sets");allocated.get(sets);
            return new Program(pipeline(vertex,fragment,layout,blend,stack),layout,setLayout,pool,sets,new GpuBuffer[slots],count);
        }catch(Throwable error){if(pool!=0)vkDestroyDescriptorPool(context.device,pool,null);if(layout!=0)vkDestroyPipelineLayout(context.device,layout,null);if(setLayout!=0)vkDestroyDescriptorSetLayout(context.device,setLayout,null);throw error;}});
    }
    void record(VkCommandBuffer command,MemoryStack stack,int slot,Object[] packet,SharedPresentImage[] inputs){
        Program program=program(packet);if(program.uniforms[slot]==null)program.uniforms[slot]=new GpuBuffer(context,1024,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        program.uniforms[slot].mapped.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put((float[])packet[4]);
        var writes=VkWriteDescriptorSet.calloc(inputs.length+1,stack);int[] metadata=(int[])packet[3];
        for(int i=0;i<inputs.length;i++)writes.get(i).sType$Default().dstSet(program.sets[slot]).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(samplers[metadata[i*5+4]]).imageView(inputs[i].view()).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        writes.get(inputs.length).sType$Default().dstSet(program.sets[slot]).dstBinding(inputs.length).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(program.uniforms[slot].buffer).range(1024));
        vkUpdateDescriptorSets(context.device,writes,null);vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,program.pipeline);
        vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,program.layout,0,stack.longs(program.sets[slot]),null);vkCmdDraw(command,3,1,0,0);
    }
    private long pipeline(String vertex,String fragment,long layout,int[] blend,MemoryStack stack){
        long vert=0,frag=0;try{
            vert=ShaderCompiler.moduleSource(context,vertex,"live-post.vert",shaderc_glsl_vertex_shader);frag=ShaderCompiler.moduleSource(context,fragment,"live-post.frag",shaderc_glsl_fragment_shader);
            var stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
            var attachment=VkPipelineColorBlendAttachmentState.calloc(1,stack).colorWriteMask(15)
                    .blendEnable(!Arrays.equals(blend,new int[]{1,0,1,0,32774})).srcColorBlendFactor(factor(blend[0])).dstColorBlendFactor(factor(blend[1]))
                    .srcAlphaBlendFactor(factor(blend[2])).dstAlphaBlendFactor(factor(blend[3])).colorBlendOp(operation(blend[4])).alphaBlendOp(operation(blend[4]));
            var info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(VK_FORMAT_R8G8B8A8_UNORM)).depthAttachmentFormat(VK_FORMAT_D32_SFLOAT))
                    .pStages(stages).pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()).pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(false).depthWriteEnable(false))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default())
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(attachment))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR))).layout(layout);
            var out=stack.mallocLong(1);check(vkCreateGraphicsPipelines(context.device,0,info,null,out),"native post pipeline");return out.get(0);
        }finally{if(vert!=0)vkDestroyShaderModule(context.device,vert,null);if(frag!=0)vkDestroyShaderModule(context.device,frag,null);}
    }
    private static int factor(int gl){return switch(gl){case 0->VK_BLEND_FACTOR_ZERO;case 1->VK_BLEND_FACTOR_ONE;case 768->VK_BLEND_FACTOR_SRC_COLOR;case 769->VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
        case 770->VK_BLEND_FACTOR_SRC_ALPHA;case 771->VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;case 772->VK_BLEND_FACTOR_DST_ALPHA;case 773->VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
        case 774->VK_BLEND_FACTOR_DST_COLOR;case 775->VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;default->throw new IllegalArgumentException("Post blend factor "+gl);};}
    private static int operation(int gl){return switch(gl){case 32774->VK_BLEND_OP_ADD;case 32778->VK_BLEND_OP_SUBTRACT;case 32779->VK_BLEND_OP_REVERSE_SUBTRACT;case 32775->VK_BLEND_OP_MIN;case 32776->VK_BLEND_OP_MAX;default->throw new IllegalArgumentException("Post blend operation "+gl);};}
    public void close(){for(Program program:programs.values()){for(GpuBuffer buffer:program.uniforms)if(buffer!=null)buffer.close();vkDestroyPipeline(context.device,program.pipeline,null);vkDestroyPipelineLayout(context.device,program.layout,null);vkDestroyDescriptorPool(context.device,program.pool,null);vkDestroyDescriptorSetLayout(context.device,program.setLayout,null);}programs.clear();for(long sampler:samplers)if(sampler!=0)vkDestroySampler(context.device,sampler,null);}
}
