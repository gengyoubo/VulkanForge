package github.com.gengyoubo.vulkanforge.lab;

import java.util.function.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.*;

/** Exported Vulkan allocation; imported GL objects belong to the host's existing LWJGL. */
final class SharedPresentImage implements AutoCloseable {
    final VulkanContext context;
    long image, memory, ready, complete, view;
    private long memoryHandle, readyHandle, completeHandle;
    private Consumer<int[]> glImage;
    private final int format, aspect;
    SharedPresentImage(VulkanContext context, int width, int height, Function<long[], Consumer<int[]>> importer) {
        this(context, width, height, importer, false);
    }
    SharedPresentImage(VulkanContext context, int width, int height, Function<long[], Consumer<int[]>> importer, boolean depth) {
        this.context = context;
        format = depth ? VK_FORMAT_D32_SFLOAT : VK_FORMAT_R8G8B8A8_UNORM;
        aspect = depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var info = VkImageCreateInfo.calloc(stack).sType$Default().pNext(external.address()).imageType(VK_IMAGE_TYPE_2D)
                    .format(format).extent(e -> e.set(width, height, 1)).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    // Depth targets are also copy destinations: translucent depth starts as a copy of opaque depth.
                    .usage((depth ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            check(vkCreateImage(context.device, info, null, handle), "create shared presentation image"); image = handle.get(0);
            var requirements = VkMemoryRequirements.calloc(stack); vkGetImageMemoryRequirements(context.device, image, requirements);
            var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
            var export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default().pNext(dedicated.address()).handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(export.address()).allocationSize(requirements.size())
                    .memoryTypeIndex(GpuBuffer.memoryType(context, stack, requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(context.device, allocation, null, handle), "allocate shared presentation image"); memory = handle.get(0);
            check(vkBindImageMemory(context.device, image, memory, 0), "bind shared presentation image");
            var winHandle = stack.mallocPointer(1);
            check(vkGetMemoryWin32HandleKHR(context.device, VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                    .memory(memory).handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT), winHandle), "export presentation memory"); memoryHandle = winHandle.get(0);
            var exportSemaphore = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default().handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            var semaphore = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(exportSemaphore.address());
            check(vkCreateSemaphore(context.device, semaphore, null, handle), "create GL-ready semaphore"); ready = handle.get(0);
            check(vkCreateSemaphore(context.device, semaphore, null, handle), "create GL-complete semaphore"); complete = handle.get(0);
            readyHandle = exportSemaphore(stack, ready); completeHandle = exportSemaphore(stack, complete);
            // Retain NT handles through the imported image lifetime, including deferred driver work.
            glImage = importer.apply(new long[]{memoryHandle, requirements.size(), readyHandle, completeHandle, width, height, format});
        } catch (Throwable error) { close(); throw error; }
    }
    private long exportSemaphore(MemoryStack stack, long semaphore) {
        var handle = stack.mallocPointer(1);
        check(vkGetSemaphoreWin32HandleKHR(context.device, VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                .semaphore(semaphore).handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT), handle), "export GL semaphore"); return handle.get(0);
    }
    void copyFromGL(int source, int width, int height) { glImage.accept(new int[]{source, width, height}); }
    long view() {
        if (view == 0) try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreateImageView(context.device, VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                    .subresourceRange(r -> r.aspectMask(aspect).levelCount(1).layerCount(1)), null, handle), "shared terrain view");
            view = handle.get(0);
        }
        return view;
    }
    int beginReadGL() { int[] result = {0, 0}; glImage.accept(result); return result[1]; }
    void endReadGL() { glImage.accept(new int[]{1}); }
    private static void closeHandle(long handle) {
        long function = org.lwjgl.system.windows.Kernel32.getLibrary().getFunctionAddress("CloseHandle");
        if (org.lwjgl.system.JNI.callPI(handle, function) == 0) throw new IllegalStateException("CloseHandle failed");
    }
    @Override public void close() {
        if (glImage != null) {
            try { ((AutoCloseable) glImage).close(); } catch (Exception error) { throw new IllegalStateException("Close imported GL image", error); }
            glImage = null;
        }
        if (ready != 0) { vkDestroySemaphore(context.device, ready, null); ready = 0; }
        if (complete != 0) { vkDestroySemaphore(context.device, complete, null); complete = 0; }
        if (view != 0) { vkDestroyImageView(context.device, view, null); view = 0; }
        if (image != 0) { vkDestroyImage(context.device, image, null); image = 0; }
        if (memory != 0) { vkFreeMemory(context.device, memory, null); memory = 0; }
        if (memoryHandle != 0) { closeHandle(memoryHandle); memoryHandle = 0; }
        if (readyHandle != 0) { closeHandle(readyHandle); readyHandle = 0; }
        if (completeHandle != 0) { closeHandle(completeHandle); completeHandle = 0; }
    }
}
