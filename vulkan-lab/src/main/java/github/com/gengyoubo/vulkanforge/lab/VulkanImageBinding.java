package github.com.gengyoubo.vulkanforge.lab;

/** A shader-visible Vulkan image. Export/import ownership belongs to the executor, not the pass. */
interface VulkanImageBinding {
    long view();
}
