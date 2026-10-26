package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Hand then GUI draw into the acquired swapchain image after GL blit, with independent depth and presentation-owned textures. */
final class GuiPass implements AutoCloseable {
    private final VulkanContext context;
    private final EntityPass draws;
    private final GpuImage[] depths = new GpuImage[4];
    private final ArrayDeque<Map.Entry<Long, Runnable>> retired = new ArrayDeque<>();
    private long frame, passes, handPasses;
    private static final int HAND_LIGHTMAP = Integer.MIN_VALUE;

    GuiPass(VulkanContext context, int format) {
        this.context = context;
        draws = new EntityPass(context, 4, action -> retired.add(Map.entry(frame + 2, action)), format, true);
    }

    void texture(Object[] texture) {
        draws.texture((Integer) texture[0], (Integer) texture[1], (Integer) texture[2], (Integer) texture[3], (byte[]) texture[4]);
    }
    void patch(Object[] patch) { draws.texturePatch((Integer) patch[0], (Integer) patch[1], (Integer) patch[2], (Integer) patch[3], (Integer) patch[4], (byte[]) patch[5]); }
    void lightmap(byte[] rgba) {
        draws.texture(HAND_LIGHTMAP, 16, 16, EntityPass.TEXTURE_LINEAR | EntityPass.TEXTURE_CLAMP, rgba);
    }
    void prepare(long frame) {
        this.frame = frame;
        while (!retired.isEmpty() && retired.peek().getKey() <= frame) retired.remove().getValue().run();
    }
    boolean usesPushDescriptors() { return draws.usesPushDescriptors(); }
    long[] bindingStats() { return draws.bindingStats(); }
    long passes() { return passes; }
    long handPasses() { return handPasses; }

    void record(VkCommandBuffer command, MemoryStack stack, int slot, long view, int width, int height, List<Object[]> packets) {
        record(command, stack, slot, view, width, height, packets, 0, 0, null);
        if (!packets.isEmpty()) passes++;
    }
    void recordHand(VkCommandBuffer command, MemoryStack stack, int slot, long view, int width, int height, List<Object[]> packets) {
        boolean lit = draws.hasTexture(HAND_LIGHTMAP);
        record(command, stack, slot + 2, view, width, height, packets, lit ? draws.textureView(HAND_LIGHTMAP) : 0,
                lit ? draws.textureSampler(HAND_LIGHTMAP) : 0, lit ? draws.textureIdentity(HAND_LIGHTMAP) : null);
        if (!packets.isEmpty()) handPasses++;
    }
    private void record(VkCommandBuffer command, MemoryStack stack, int slot, long view, int width, int height, List<Object[]> packets, long lightView, long lightSampler, Object lightIdentity) {
        if (packets.isEmpty()) return;
        GpuImage depth = depths[slot];
        if (depth == null || depth.width != width || depth.height != height) {
            if (depth != null) depth.close(); // The presentation slot's fence has completed.
            depths[slot] = depth = new GpuImage(context, width, height, VK_FORMAT_D32_SFLOAT,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
        }
        var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_NONE).dstStageMask(VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT)
                .dstAccessMask(VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(depth.image)
                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1));
        vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
        var color = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default().imageView(view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        var depthInfo = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(depth.view).imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
        depthInfo.clearValue().depthStencil().set(1, 0);
        var info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color).pDepthAttachment(depthInfo);
        info.renderArea().extent().set(width, height);
        vkCmdBeginRendering(command, info);
        vkCmdSetViewport(command, 0, VkViewport.calloc(1, stack).width(width).height(height).minDepth(0).maxDepth(1));
        var scissor = VkRect2D.calloc(1, stack); scissor.extent().set(width, height);
        vkCmdSetScissor(command, 0, scissor);
        draws.record(command, stack, slot, packets, lightView, lightSampler, lightIdentity);
        vkCmdEndRendering(command);
    }

    @Override public void close() {
        for (GpuImage depth : depths) if (depth != null) depth.close();
        draws.close();
        while (!retired.isEmpty()) retired.remove().getValue().run();
    }
}
