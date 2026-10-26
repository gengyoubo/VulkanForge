package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.*;

/** Vulkan RGBA8 target -> host-visible staging buffer. All GPU work belongs to this thread. */
final class OffscreenRenderer implements AutoCloseable {
    private static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private final VulkanContext context;
    private final int width, height, bytes;
    private long image, imageMemory, view, staging, stagingMemory, pool, fence, layout, pipeline;
    private VkCommandBuffer command;
    private ByteBuffer mapped;
    private boolean coherent;
    private GpuImage depth;
    private GpuTimers timers;
    private long frames;

    OffscreenRenderer(VulkanContext context, int width, int height) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
    }

    void initialize() {
        timers = new GpuTimers(context, 1, System.out::println, "TERRAIN", "READBACK_COPY", "TRIANGLE");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physical, FORMAT, props);
            int required = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
            if ((props.optimalTilingFeatures() & required) != required) throw new IllegalStateException("RGBA8 offscreen color+transfer is unsupported");
            LongBuffer handle = stack.mallocLong(1);
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(FORMAT)
                    .extent(e -> e.set(width, height, 1)).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            check(vkCreateImage(context.device, imageInfo, null, handle), "vkCreateImage(offscreen)");
            image = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(context.device, image, requirements);
            imageMemory = allocate(stack, requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, false);
            check(vkBindImageMemory(context.device, image, imageMemory, 0), "vkBindImageMemory");
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(FORMAT)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            check(vkCreateImageView(context.device, viewInfo, null, handle), "vkCreateImageView(offscreen)");
            view = handle.get(0);

            VkBufferCreateInfo buffer = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(context.device, buffer, null, handle), "vkCreateBuffer(staging)");
            staging = handle.get(0);
            vkGetBufferMemoryRequirements(context.device, staging, requirements);
            stagingMemory = allocate(stack, requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, true);
            check(vkBindBufferMemory(context.device, staging, stagingMemory, 0), "vkBindBufferMemory");
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkMapMemory(context.device, stagingMemory, 0, VK_WHOLE_SIZE, 0, pointer), "vkMapMemory");
            mapped = memByteBuffer(pointer.get(0), bytes);

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(context.queueFamily).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            check(vkCreateCommandPool(context.device, poolInfo, null, handle), "vkCreateCommandPool(offscreen)");
            pool = handle.get(0);
            VkCommandBufferAllocateInfo commandInfo = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            check(vkAllocateCommandBuffers(context.device, commandInfo, pointer), "vkAllocateCommandBuffers(offscreen)");
            command = new VkCommandBuffer(pointer.get(0), context.device);
            check(vkCreateFence(context.device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateFence(offscreen)");
            fence = handle.get(0);
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(8);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(push);
            check(vkCreatePipelineLayout(context.device, layoutInfo, null, handle), "vkCreatePipelineLayout(offscreen)");
            layout = handle.get(0);
            pipeline = GraphicsPipeline.create(context, layout, FORMAT, "/shaders/offscreen.vert", stack);
            depth = new GpuImage(context, width, height, VK_FORMAT_D32_SFLOAT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
            System.out.printf("OFFSCREEN_TARGET=%dx%d RGBA8 coherent=%s%n", width, height, coherent);
        }
    }

    private long allocate(MemoryStack stack, VkMemoryRequirements requirements, int required, boolean host) {
        VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.physical, props);
        int selected = -1, best = -1;
        for (int i = 0; i < props.memoryTypeCount(); i++) {
            int flags = props.memoryTypes(i).propertyFlags();
            if ((requirements.memoryTypeBits() & (1 << i)) == 0 || (flags & required) != required) continue;
            int score = host ? ((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0 ? 2 : 0)
                    + ((flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0 ? 1 : 0) : 0;
            if (score > best) { best = score; selected = i; }
        }
        if (selected < 0) throw new IllegalStateException("No compatible Vulkan memory type, required=" + required);
        if (host) coherent = (props.memoryTypes(selected).propertyFlags() & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(selected);
        LongBuffer handle = stack.mallocLong(1);
        check(vkAllocateMemory(context.device, info, null, handle), "vkAllocateMemory");
        return handle.get(0);
    }

    void render(float angle, byte[] output) {
        render(angle, output, null);
    }

    void render(float angle, byte[] output, WorldScene world) {
        boolean terrain = world != null && world.ready();
        if (output.length != bytes) throw new IllegalArgumentException("Wrong RGBA buffer size");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(command, 0), "vkResetCommandBuffer(offscreen)");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer(offscreen)");
            timers.begin(command, 0);
            VkImageMemoryBarrier2.Buffer barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK_ACCESS_2_NONE)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(command, dependency);
            if (terrain) {
                var depthBarrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                        .dstStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                        .dstAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(depth.image)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1));
                vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(depthBarrier));
            }
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                    .imageView(view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.clearValue().color().float32(0, 0.025f).float32(1, 0.04f).float32(2, 0.075f).float32(3, 1);
            if (terrain) color.clearValue().color().float32(0, world.red()).float32(1, world.green()).float32(2, world.blue()).float32(3, 0);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color)
                    .renderArea(r -> r.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
            if (terrain) {
                var depthAttachment = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(depth.view)
                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
                depthAttachment.clearValue().depthStencil().depth(1.0f).stencil(0);
                rendering.pDepthAttachment(depthAttachment);
            }
            int drawMetric = terrain ? 0 : 2;
            timers.start(command, 0, drawMetric, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            vkCmdBeginRendering(command, rendering);
            vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1));
            vkCmdSetScissor(command, 0, VkRect2D.calloc(1, stack).offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
            if (terrain) world.draw(command, stack);
            else {
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                vkCmdPushConstants(command, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, stack.floats(angle, (float) width / height));
                vkCmdDraw(command, 3, 1, 0, 0);
            }
            vkCmdEndRendering(command);
            timers.end(command, 0, drawMetric, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT).srcAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            vkCmdPipelineBarrier2(command, dependency);
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack)
                    .imageSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .imageOffset(o -> o.set(0, 0, 0)).imageExtent(e -> e.set(width, height, 1));
            timers.start(command, 0, 1, VK_PIPELINE_STAGE_2_COPY_BIT);
            vkCmdCopyImageToBuffer(command, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging, copy);
            timers.end(command, 0, 1, VK_PIPELINE_STAGE_2_COPY_BIT);
            VkMemoryBarrier2.Buffer toHost = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT);
            vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(toHost));
            check(vkEndCommandBuffer(command), "vkEndCommandBuffer(offscreen)");
            check(vkResetFences(context.device, fence), "vkResetFences(offscreen)");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command.address()));
            context.uploads.flush();
            check(vkQueueSubmit(context.queue, submit, fence), "vkQueueSubmit(offscreen)");
            timers.submitted(0);
            check(vkWaitForFences(context.device, fence, true, 5_000_000_000L), "vkWaitForFences(offscreen)");
            timers.collect(0);
            if (++frames % 300 == 0) timers.report(System.out::println);
            if (!coherent) {
                VkMappedMemoryRange.Buffer range = VkMappedMemoryRange.calloc(1, stack).sType$Default().memory(stagingMemory).offset(0).size(VK_WHOLE_SIZE);
                check(vkInvalidateMappedMemoryRanges(context.device, range), "vkInvalidateMappedMemoryRanges");
            }
            mapped.get(0, output);
        }
    }

    @Override public void close() {
        if (context.device == null) return;
        vkDeviceWaitIdle(context.device);
        if (timers != null) { timers.collectAll(); timers.report(System.out::println); timers.close(); timers = null; }
        if (depth != null) { depth.close(); depth = null; }
        if (mapped != null) { vkUnmapMemory(context.device, stagingMemory); mapped = null; }
        if (pipeline != NULL) { vkDestroyPipeline(context.device, pipeline, null); pipeline = NULL; }
        if (layout != NULL) { vkDestroyPipelineLayout(context.device, layout, null); layout = NULL; }
        if (pool != NULL) { vkDestroyCommandPool(context.device, pool, null); pool = NULL; }
        if (fence != NULL) { vkDestroyFence(context.device, fence, null); fence = NULL; }
        if (staging != NULL) { vkDestroyBuffer(context.device, staging, null); staging = NULL; }
        if (stagingMemory != NULL) { vkFreeMemory(context.device, stagingMemory, null); stagingMemory = NULL; }
        if (view != NULL) { vkDestroyImageView(context.device, view, null); view = NULL; }
        if (image != NULL) { vkDestroyImage(context.device, image, null); image = NULL; }
        if (imageMemory != NULL) { vkFreeMemory(context.device, imageMemory, null); imageMemory = NULL; }
    }
}
