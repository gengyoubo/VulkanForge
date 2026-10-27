package github.com.gengyoubo.vulkanforge.lab;

/** Vulkan attachment handle; ownership/export policy is supplied by the executor boundary. */
interface VulkanRenderImage extends VulkanImageBinding, AutoCloseable {
    long image();
    @Override void close();
}
