package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Vulkan equivalent of terrain color/depth compositing, into a loaded color/depth target. */
final class DepthComposite implements AutoCloseable {
    private final VulkanContext context;
    private long setLayout, layout, pool, sampler;
    private final long[] pipelines = new long[2], sets = new long[4];
    DepthComposite(VulkanContext context) {
        this.context = context;
        try (var stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i=0;i<2;i++) bindings.get(i).binding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(context.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "composite set layout"); setLayout=handle.get(0);
            check(vkCreatePipelineLayout(context.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)), null, handle), "composite layout"); layout=handle.get(0);
            check(vkCreateDescriptorPool(context.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(4)
                    .pPoolSizes(VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(8)), null, handle), "composite pool"); pool=handle.get(0);
            var layouts=stack.longs(setLayout,setLayout,setLayout,setLayout); var allocated=stack.mallocLong(4);
            check(vkAllocateDescriptorSets(context.device,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(layouts),allocated),"composite sets"); allocated.get(sets);
            check(vkCreateSampler(context.device,VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),null,handle),"composite sampler");sampler=handle.get(0);
            for(int i=0;i<2;i++) pipelines[i]=pipeline(i==1,stack);
        } catch(Throwable e){close();throw e;}
    }
    void sources(int slot, long color, long depth, long translucent, long translucentDepth) {
        try(var stack=MemoryStack.stackPush()) {
            long[] views={color,depth,translucent,translucentDepth}; var writes=VkWriteDescriptorSet.calloc(4,stack);
            for(int i=0;i<4;i++) writes.get(i).sType$Default().dstSet(sets[slot*2+i/2]).dstBinding(i%2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).sampler(sampler).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            vkUpdateDescriptorSets(context.device,writes,null);
        }
    }
    void record(VkCommandBuffer command, MemoryStack stack, int slot, boolean translucent) {
        int index=translucent?1:0;
        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelines[index]);
        vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,stack.longs(sets[slot*2+index]),null);
        vkCmdDraw(command,3,1,0,0);
    }
    private long pipeline(boolean translucent,MemoryStack stack) {
        long vert=0,frag=0;
        try {
            vert=ShaderCompiler.module(context,"/shaders/composite.vert",shaderc_glsl_vertex_shader);
            frag=ShaderCompiler.module(context,"/shaders/composite.frag",shaderc_glsl_fragment_shader);
            var stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
            var blend=VkPipelineColorBlendAttachmentState.calloc(1,stack).colorWriteMask(15).blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                    .srcColorBlendFactor(translucent?VK_BLEND_FACTOR_ONE:VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(translucent?VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA:VK_BLEND_FACTOR_ZERO);
            var info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default()
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(VK_FORMAT_R8G8B8A8_UNORM)).depthAttachmentFormat(VK_FORMAT_D32_SFLOAT))
                    .pStages(stages).pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).lineWidth(1))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR))).layout(layout);
            var result=stack.mallocLong(1);check(vkCreateGraphicsPipelines(context.device,0,info,null,result),"depth composite pipeline");return result.get(0);
        } finally { if(vert!=0)vkDestroyShaderModule(context.device,vert,null);if(frag!=0)vkDestroyShaderModule(context.device,frag,null); }
    }
    public void close(){for(long p:pipelines)if(p!=0)vkDestroyPipeline(context.device,p,null);if(pool!=0)vkDestroyDescriptorPool(context.device,pool,null);if(sampler!=0)vkDestroySampler(context.device,sampler,null);if(layout!=0)vkDestroyPipelineLayout(context.device,layout,null);if(setLayout!=0)vkDestroyDescriptorSetLayout(context.device,setLayout,null);}
}
