package github.com.gengyoubo.vulkanforge.lab;

import dev.vulkanforge.bridge.WorldProtocol;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** GPU section cache. Commands are applied on the Vulkan thread after the previous frame fence. */
final class WorldScene implements AutoCloseable {
    private record Section(int x, int y, int z, int vertices, GpuBuffer buffer) implements AutoCloseable {
        @Override public void close() { buffer.close(); }
    }
    private final VulkanContext context;
    private final Map<Long, Section> sections = new LinkedHashMap<>();
    private long epoch, descriptorLayout, descriptorPool, descriptorSet, layout, pipeline, sampler, lightSampler, pool, fence;
    private GpuImage atlas, lightMap;
    private long atlasPatches, lightUpdates;
    private VkCommandBuffer upload;
    private WorldProtocol.Camera camera;
    private boolean receivedMesh;

    WorldScene(VulkanContext context) { this.context = context; }

    void initialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var binding = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            binding.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            check(vkCreateDescriptorSetLayout(context.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding), null, handle), "world descriptor layout");
            descriptorLayout = handle.get(0);
            var sizes = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            check(vkCreateDescriptorPool(context.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, handle), "world descriptor pool");
            descriptorPool = handle.get(0);
            check(vkAllocateDescriptorSets(context.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle), "world descriptor set");
            descriptorSet = handle.get(0);
            var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).size(80);
            var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push);
            check(vkCreatePipelineLayout(context.device, layoutInfo, null, handle), "world pipeline layout"); layout = handle.get(0);
            pipeline = GraphicsPipeline.create(context, layout, VK_FORMAT_R8G8B8A8_UNORM, "/shaders/world.vert", true, stack);
            var samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0);
            check(vkCreateSampler(context.device, samplerInfo, null, handle), "world atlas sampler"); sampler = handle.get(0);
            samplerInfo.magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR);
            check(vkCreateSampler(context.device, samplerInfo, null, handle), "world lightmap sampler"); lightSampler = handle.get(0);
            check(vkCreateCommandPool(context.device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(context.queueFamily)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "world upload pool"); pool = handle.get(0);
            var pointer = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(context.device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "world upload command");
            upload = new VkCommandBuffer(pointer.get(0), context.device);
            check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "world upload fence"); fence = handle.get(0);
        }
    }

    void apply(WorldProtocol.Command command) {
        if (command instanceof WorldProtocol.Reset reset) {
            clear(); epoch = reset.epoch();
            System.out.println("WORLD_RESET=" + epoch);
            return;
        }
        if (command.epoch() != epoch || epoch == 0) return;
        if (command instanceof WorldProtocol.Camera view) camera = view;
        else if (command instanceof WorldProtocol.Atlas image) uploadAtlas(image);
        else if (command instanceof WorldProtocol.TexturePatch patch) updateTexture(patch);
        else if (command instanceof WorldProtocol.Remove remove) { Section old = sections.remove(remove.key()); if (old != null) old.close(); }
        else if (command instanceof WorldProtocol.Mesh mesh) {
            receivedMesh = true;
            if (sections.size() >= 64 && !sections.containsKey(mesh.key())) throw new IllegalStateException("World section cache limit exceeded");
            Section replacement = mesh.vertices().length == 0 ? null : new Section(mesh.x(), mesh.y(), mesh.z(), mesh.vertices().length / WorldProtocol.STRIDE,
                    new GpuBuffer(context, mesh.vertices(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT));
            Section old = sections.remove(mesh.key());
            if (old != null) old.close();
            if (replacement != null) sections.put(mesh.key(), replacement);
            System.out.printf("WORLD_MESH=%d vertices=%d cached=%d%n", mesh.key(), mesh.vertices().length / WorldProtocol.STRIDE, sections.size());
        }
    }

    boolean ready() { return camera != null && atlas != null && lightMap != null && receivedMesh; }
    long renderedEpoch() { return ready() ? epoch : 0; }
    float red() { return camera.red(); }
    float green() { return camera.green(); }
    float blue() { return camera.blue(); }

    private void uploadAtlas(WorldProtocol.Atlas pixels) {
        if (atlas != null) { atlas.close(); atlas = null; }
        GpuImage next = new GpuImage(context, pixels.width(), pixels.height(), VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        try {
            uploadPixels(next, 0, 0, pixels.width(), pixels.height(), pixels.rgba(), true);
            updateDescriptor(0, next, sampler);
            atlas = next;
            System.out.printf("WORLD_ATLAS=%dx%d%n", pixels.width(), pixels.height());
        } catch (Throwable error) { next.close(); throw error; }
    }

    private void updateTexture(WorldProtocol.TexturePatch patch) {
        if (patch.target() == WorldProtocol.LIGHT_MAP) {
            boolean fresh = lightMap == null;
            GpuImage image = fresh ? new GpuImage(context, 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT) : lightMap;
            try {
                uploadPixels(image, 0, 0, 16, 16, patch.rgba(), fresh);
                if (fresh) { updateDescriptor(1, image, lightSampler); lightMap = image; }
            } catch (Throwable error) { if (fresh) image.close(); throw error; }
            if (++lightUpdates == 1 || lightUpdates % 100 == 0) System.out.println("WORLD_LIGHTMAP_UPDATES=" + lightUpdates);
        } else {
            if (atlas == null || (long) patch.x() + patch.width() > atlas.width || (long) patch.y() + patch.height() > atlas.height)
                throw new IllegalArgumentException("Atlas patch exceeds current image bounds");
            uploadPixels(atlas, patch.x(), patch.y(), patch.width(), patch.height(), patch.rgba(), false);
            if (++atlasPatches == 1 || atlasPatches % 100 == 0) System.out.println("WORLD_ATLAS_PATCHES=" + atlasPatches);
        }
    }

    private void uploadPixels(GpuImage image, int x, int y, int width, int height, byte[] rgba, boolean fresh) {
        try (GpuBuffer staging = new GpuBuffer(context, rgba, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
             MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(upload, 0), "reset atlas upload");
            check(vkBeginCommandBuffer(upload, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin atlas upload");
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(fresh ? VK_PIPELINE_STAGE_2_NONE : VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(fresh ? 0 : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .oldLayout(fresh ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            var dependency = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(upload, dependency);
            var region = VkBufferImageCopy.calloc(1, stack).imageSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                    .imageOffset(o -> o.set(x, y, 0)).imageExtent(e -> e.set(width, height, 1));
            vkCmdCopyBufferToImage(upload, staging.buffer, image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT).dstAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            vkCmdPipelineBarrier2(upload, dependency);
            check(vkEndCommandBuffer(upload), "end atlas upload");
            check(vkResetFences(context.device, fence), "reset atlas fence");
            check(vkQueueSubmit(context.queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(upload.address())), fence), "submit atlas upload");
            check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "wait atlas upload");
        }
    }

    private void updateDescriptor(int binding, GpuImage image, long imageSampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var imageInfo = VkDescriptorImageInfo.calloc(1, stack).sampler(imageSampler).imageView(image.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(descriptorSet).dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfo);
            vkUpdateDescriptorSets(context.device, write, null);
        }
    }

    void draw(VkCommandBuffer command, MemoryStack stack) {
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(descriptorSet), null);
        var push = stack.mallocFloat(20);
        push.put(camera.matrix());
        push.put(0).put(0).put(0).put(0).flip();
        var buffers = stack.mallocLong(1);
        var offsets = stack.longs(0);
        for (Section section : sections.values()) {
            // Subtract in double before conversion: preserves precision at large world coordinates.
            push.put(16, (float) (section.x() - camera.x())).put(17, (float) (section.y() - camera.y())).put(18, (float) (section.z() - camera.z()));
            vkCmdPushConstants(command, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, push);
            buffers.put(0, section.buffer().buffer);
            vkCmdBindVertexBuffers(command, 0, buffers, offsets);
            vkCmdDraw(command, section.vertices(), 1, 0, 0);
        }
    }

    private void clear() {
        sections.values().forEach(Section::close); sections.clear(); camera = null; receivedMesh = false;
        if (atlas != null) { atlas.close(); atlas = null; }
        if (lightMap != null) { lightMap.close(); lightMap = null; }
    }

    @Override public void close() {
        vkDeviceWaitIdle(context.device); clear();
        if (pipeline != 0) { vkDestroyPipeline(context.device, pipeline, null); pipeline = 0; }
        if (layout != 0) { vkDestroyPipelineLayout(context.device, layout, null); layout = 0; }
        if (descriptorPool != 0) { vkDestroyDescriptorPool(context.device, descriptorPool, null); descriptorPool = 0; }
        if (descriptorLayout != 0) { vkDestroyDescriptorSetLayout(context.device, descriptorLayout, null); descriptorLayout = 0; }
        if (sampler != 0) { vkDestroySampler(context.device, sampler, null); sampler = 0; }
        if (lightSampler != 0) { vkDestroySampler(context.device, lightSampler, null); lightSampler = 0; }
        if (pool != 0) { vkDestroyCommandPool(context.device, pool, null); pool = 0; }
        if (fence != 0) { vkDestroyFence(context.device, fence, null); fence = 0; }
    }
}
