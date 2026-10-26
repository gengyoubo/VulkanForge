package github.com.gengyoubo.vulkanforge.lab;

import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdBeginRendering;
import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdEndRendering;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Samples the existing shared scene directly into the swapchain; no intermediate image or transfer blit. */
final class PresentPass implements AutoCloseable {
    private final VulkanContext context;
    private long setLayout, layout, pool, sampler, pipeline;
    private final long[] sets = new long[2];

    PresentPass(VulkanContext context, int format) {
        this.context = context;
        try (var stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var binding = VkDescriptorSetLayoutBinding.calloc(1, stack).binding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(context.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding), null, handle), "presentation descriptor layout"); setLayout = handle.get(0);
            check(vkCreatePipelineLayout(context.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)), null, handle), "presentation pipeline layout"); layout = handle.get(0);
            check(vkCreateDescriptorPool(context.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(sets.length)
                    .pPoolSizes(VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(sets.length)), null, handle), "presentation descriptor pool"); pool = handle.get(0);
            var allocated = stack.mallocLong(sets.length);
            check(vkAllocateDescriptorSets(context.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                    .pSetLayouts(stack.longs(setLayout, setLayout)), allocated), "presentation descriptor sets"); allocated.get(sets);
            check(vkCreateSampler(context.device, VkSamplerCreateInfo.calloc(stack).sType$Default().minFilter(VK_FILTER_NEAREST).magFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, handle), "presentation sampler"); sampler = handle.get(0);
            pipeline = GraphicsPipeline.create(context, layout, format, "/shaders/composite.vert", "/shaders/present.frag", false, false, stack);
        } catch (Throwable error) { close(); throw error; }
    }

    void record(VkCommandBuffer command, MemoryStack stack, int slot, long sourceView, long outputView, int width, int height) {
        // beginFrame already waited for this presentation slot before updating its descriptors.
        var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(sets[slot]).dstBinding(0).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(sourceView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
        vkUpdateDescriptorSets(context.device, write, null);
        var color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(outputView)
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        vkCmdBeginRendering(command, VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color)
                .renderArea(r -> r.extent(e -> e.set(width, height))));
        vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1));
        vkCmdSetScissor(command, 0, VkRect2D.calloc(1, stack).extent(e -> e.set(width, height)));
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(sets[slot]), null);
        vkCmdDraw(command, 3, 1, 0, 0);
        vkCmdEndRendering(command);
    }

    @Override public void close() {
        if (pipeline != 0) { vkDestroyPipeline(context.device, pipeline, null); pipeline = 0; }
        if (pool != 0) { vkDestroyDescriptorPool(context.device, pool, null); pool = 0; }
        if (sampler != 0) { vkDestroySampler(context.device, sampler, null); sampler = 0; }
        if (layout != 0) { vkDestroyPipelineLayout(context.device, layout, null); layout = 0; }
        if (setLayout != 0) { vkDestroyDescriptorSetLayout(context.device, setLayout, null); setLayout = 0; }
    }
}
