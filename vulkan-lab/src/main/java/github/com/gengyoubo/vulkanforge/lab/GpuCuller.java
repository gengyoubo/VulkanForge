package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Per-frame AABBs -> compute-written VkDrawIndirectCommand. Results are inspected only after existing fences. */
final class GpuCuller implements AutoCloseable {
    static final int CAPACITY = 4096, INPUT_STRIDE = 48;
    private final VulkanContext context;
    private final GpuBuffer[] inputs, outputs;
    private final long[] sets;
    private final int[] counts;
    private long descriptorLayout, descriptorPool, layout, pipeline;
    private long tested, visible;
    GpuCuller(VulkanContext context, int slots) {
        this.context = context;
        inputs = new GpuBuffer[slots]; outputs = new GpuBuffer[slots]; sets = new long[slots]; counts = new int[slots];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i = 0; i < 2; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(context.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "cull descriptor layout");
            descriptorLayout = handle.get(0);
            check(vkCreateDescriptorPool(context.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(slots)
                    .pPoolSizes(VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(slots * 2)), null, handle), "cull descriptor pool");
            descriptorPool = handle.get(0);
            for (int i = 0; i < slots; i++) {
                inputs[i] = new GpuBuffer(context, CAPACITY * INPUT_STRIDE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                outputs[i] = new GpuBuffer(context, CAPACITY * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
                check(vkAllocateDescriptorSets(context.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle), "cull descriptor set"); sets[i] = handle.get(0);
                var writes = VkWriteDescriptorSet.calloc(2, stack);
                for (int b = 0; b < 2; b++) writes.get(b).sType$Default().dstSet(sets[i]).dstBinding(b).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(b == 0 ? inputs[i].buffer : outputs[i].buffer).offset(0).range(VK_WHOLE_SIZE));
                vkUpdateDescriptorSets(context.device, writes, null);
            }
            check(vkCreatePipelineLayout(context.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout))
                    .pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(80)), null, handle), "cull pipeline layout"); layout = handle.get(0);
            long shader = ShaderCompiler.module(context, "/shaders/cull.comp", org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_compute_shader);
            try {
                check(vkCreateComputePipelines(context.device, 0, VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(layout)
                        .stage(VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"))), null, handle), "cull compute pipeline");
                pipeline = handle.get(0);
            } finally { vkDestroyShaderModule(context.device, shader, null); }
        } catch (Throwable error) { close(); throw error; }
    }
    ByteBuffer input(int slot) { return inputs[slot].mapped; }
    void record(VkCommandBuffer command, int slot, float[] matrix, int count, MemoryStack stack) {
        if (count > CAPACITY) throw new IllegalArgumentException("Too many sections for GPU culling");
        counts[slot] = count;
        if (count == 0) return;
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(sets[slot]), null);
        var push = stack.calloc(80); push.asFloatBuffer().put(matrix); push.putInt(64, count);
        vkCmdPushConstants(command, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        vkCmdDispatch(command, (count + 63) / 64, 1, 1);
        var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT).srcAccessMask(VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_HOST_READ_BIT);
        vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
    }
    void draw(VkCommandBuffer command, int slot, int index) { vkCmdDrawIndirect(command, outputs[slot].buffer, index * 16L, 1, 16); }
    void collect(int slot) {
        for (int i = 0; i < counts[slot]; i++) if (outputs[slot].mapped.getInt(i * 16) != 0) visible++;
        tested += counts[slot]; counts[slot] = 0;
    }
    long[] stats() { return new long[]{tested, visible, tested - visible}; }
    void report(Consumer<String> log) { log.accept("GPU_CULLING tested=" + tested + " visible=" + visible + " culled=" + (tested - visible)); }
    @Override public void close() {
        if (pipeline != 0) { vkDestroyPipeline(context.device, pipeline, null); pipeline = 0; }
        if (layout != 0) { vkDestroyPipelineLayout(context.device, layout, null); layout = 0; }
        if (descriptorPool != 0) { vkDestroyDescriptorPool(context.device, descriptorPool, null); descriptorPool = 0; }
        if (descriptorLayout != 0) { vkDestroyDescriptorSetLayout(context.device, descriptorLayout, null); descriptorLayout = 0; }
        for (GpuBuffer buffer : inputs) if (buffer != null) buffer.close();
        for (GpuBuffer buffer : outputs) if (buffer != null) buffer.close();
    }
}
