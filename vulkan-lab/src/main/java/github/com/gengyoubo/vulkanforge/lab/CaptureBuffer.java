package github.com.gengyoubo.vulkanforge.lab;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Opt-in swapchain screenshot for integration checks, absent from normal frames. */
final class CaptureBuffer implements AutoCloseable {
    private final GpuBuffer storage;
    private final int size;
    CaptureBuffer(VulkanContext context, int size) {
        this.size = size;
        storage = new GpuBuffer(context, new byte[size], VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }
    void record(VkCommandBuffer command, long image, int width, int height, MemoryStack stack) {
        record(command, image, width, height, stack, false);
    }
    void record(VkCommandBuffer command, long image, int width, int height, MemoryStack stack, boolean gui) {
        var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(gui ? VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_2_BLIT_BIT)
                .srcAccessMask(gui ? VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT : VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                .oldLayout(gui ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
        vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
        var copy = VkBufferImageCopy.calloc(1, stack).imageSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                .imageExtent(e -> e.set(width, height, 1));
        vkCmdCopyImageToBuffer(command, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, storage.buffer, copy);
        var host = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(storage.buffer).size(size);
        vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(host));
    }
    void save(String path, int width, int height, int format) throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocPointer(1);
            check(vkMapMemory(storage.context.device, storage.memory, 0, size, 0, pointer), "map swapchain screenshot");
            try {
                var data = MemoryUtil.memByteBuffer(pointer.get(0), size);
                var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                boolean bgra = format == VK_FORMAT_B8G8R8A8_UNORM;
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int i = (y * width + x) * 4;
                    int r = data.get(i + (bgra ? 2 : 0)) & 255, g = data.get(i + 1) & 255, b = data.get(i + (bgra ? 0 : 2)) & 255;
                    image.setRGB(x, y, 0xff000000 | r << 16 | g << 8 | b);
                }
                Path target = Path.of(path).toAbsolutePath(); Files.createDirectories(target.getParent());
                ImageIO.write(image, "png", target.toFile());
            } finally { vkUnmapMemory(storage.context.device, storage.memory); }
        }
    }
    @Override public void close() { storage.close(); }
}
