package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.LongBuffer;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

final class GraphicsPipeline {
    private GraphicsPipeline() { }

    static long create(VulkanContext context, long layout, int format, String vertexShader, MemoryStack stack) {
        return create(context, layout, format, vertexShader, false, stack);
    }

    static long create(VulkanContext context, long layout, int format, String vertexShader, boolean world, MemoryStack stack) {
        return create(context, layout, format, vertexShader, world ? "/shaders/world.frag" : "/shaders/triangle.frag", world, false, stack);
    }

    /** translucent: premultiplied-alpha blending (ONE, ONE_MINUS_SRC_ALPHA) for back-to-front sorted terrain. */
    static long create(VulkanContext context, long layout, int format, String vertexShader, String fragmentShader, boolean world, boolean translucent, MemoryStack stack) {
        return create(context, layout, format, vertexShader, null, fragmentShader, world, translucent, stack);
    }

    /** Opaque world state with VK_NV_mesh_shader task + mesh stages in place of vertex input and the vertex shader. */
    static long createMesh(VulkanContext context, long layout, int format, String taskShader, String meshShader, MemoryStack stack) {
        return create(context, layout, format, taskShader, meshShader, "/shaders/world.frag", true, false, stack);
    }

    private static long create(VulkanContext context, long layout, int format, String firstShader, String meshShader, String fragmentShader, boolean world, boolean translucent, MemoryStack stack) {
        long vert = 0, mesh = 0, frag = 0;
        boolean meshPipeline = meshShader != null;
        try {
            vert = ShaderCompiler.module(context, firstShader, meshPipeline ? shaderc_glsl_task_shader : shaderc_glsl_vertex_shader);
            if (meshPipeline) mesh = ShaderCompiler.module(context, meshShader, shaderc_glsl_mesh_shader);
            frag = ShaderCompiler.module(context, fragmentShader, shaderc_glsl_fragment_shader);
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(meshPipeline ? 3 : 2, stack);
            stages.get(0).sType$Default().stage(meshPipeline ? NVMeshShader.VK_SHADER_STAGE_TASK_BIT_NV : VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
            if (meshPipeline) stages.get(1).sType$Default().stage(NVMeshShader.VK_SHADER_STAGE_MESH_BIT_NV).module(mesh).pName(stack.UTF8("main"));
            stages.get(meshPipeline ? 2 : 1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (world && !meshPipeline) {
                vertexInput.pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(dev.vulkanforge.bridge.WorldProtocol.STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX));
                VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(4, stack);
                attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attributes.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(16);
                attributes.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_UINT).offset(24);
                vertexInput.pVertexAttributeDescriptions(attributes);
            }
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0f);
            // world.vert flips clip-space y, so vanilla's on-screen CCW front faces stay CCW in Vulkan framebuffer space.
            if (translucent) raster.cullMode(VK_CULL_MODE_BACK_BIT).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineColorBlendAttachmentState.Buffer attachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            if (translucent) attachment.blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(attachment);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(format));
            if (world) rendering.depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);
            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default().pNext(rendering.address()).pStages(stages).pVertexInputState(meshPipeline ? null : vertexInput)
                    .pInputAssemblyState(meshPipeline ? null : assembly).pViewportState(viewport).pRasterizationState(raster).pMultisampleState(multisample)
                    .pColorBlendState(blend).pDynamicState(dynamic).layout(layout).renderPass(NULL).basePipelineIndex(-1);
            if (world) info.pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL));
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(context.device, NULL, info, null, handle), "vkCreateGraphicsPipelines");
            return handle.get(0);
        } finally {
            if (frag != 0) vkDestroyShaderModule(context.device, frag, null);
            if (mesh != 0) vkDestroyShaderModule(context.device, mesh, null);
            if (vert != 0) vkDestroyShaderModule(context.device, vert, null);
        }
    }
}
