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
    /** buffer is null when the section only has translucent geometry; translucent is null when it has none. */
    private record Section(int x, int y, int z, int vertices, GpuBuffer buffer, MeshArena.Allocation allocation, float[] bounds, Translucent translucent) implements AutoCloseable {
        @Override public void close() { if (buffer != null) buffer.close(); if (allocation != null) allocation.close(); if (translucent != null) translucent.close(); }
    }
    /** Frame slots that may record translucent draws concurrently; matches SharedWorldRenderer. */
    static final int TRANSLUCENT_SLOTS = 2;
    /** Re-sorts beyond a section's first sort per slot, per frame; the nearest stale sections go first. */
    private static final int RESORT_BUDGET = 16;
    /** Triangles stay in quad order (6 vertices per quad); each frame slot owns an index buffer it rewrites only after its fence. */
    private final class Translucent implements AutoCloseable {
        final GpuBuffer vertices;
        final GpuBuffer[] indices = new GpuBuffer[TRANSLUCENT_SLOTS];
        final int quads;
        final float[] centers;
        final double[] sortedAt = new double[TRANSLUCENT_SLOTS * 3];
        final boolean[] sorted = new boolean[TRANSLUCENT_SLOTS];
        Translucent(byte[] bytes) {
            quads = bytes.length / (6 * WorldProtocol.STRIDE);
            centers = new float[quads * 3];
            var data = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int[] corners = {0, 1, 2, 4}; // the four distinct quad corners among 0,1,2 / 2,3,0
            for (int quad = 0; quad < quads; quad++) for (int corner : corners) for (int axis = 0; axis < 3; axis++)
                centers[quad * 3 + axis] += data.getFloat((quad * 6 + corner) * WorldProtocol.STRIDE + axis * 4) * .25f;
            GpuBuffer created = new GpuBuffer(context, bytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            try { for (int slot = 0; slot < TRANSLUCENT_SLOTS; slot++) indices[slot] = new GpuBuffer(context, quads * 6 * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT); }
            catch (Throwable error) { created.close(); for (GpuBuffer index : indices) if (index != null) index.close(); throw error; }
            vertices = created;
        }
        boolean stale(int slot, double x, double y, double z) {
            double dx = x - sortedAt[slot * 3], dy = y - sortedAt[slot * 3 + 1], dz = z - sortedAt[slot * 3 + 2];
            return !sorted[slot] || dx * dx + dy * dy + dz * dz > 1;
        }
        /** x/y/z: camera relative to the section origin. Farthest quad first. */
        void sort(int slot, double x, double y, double z) {
            long[] keys = new long[quads];
            for (int quad = 0; quad < quads; quad++) {
                float dx = (float)(centers[quad * 3] - x), dy = (float)(centers[quad * 3 + 1] - y), dz = (float)(centers[quad * 3 + 2] - z);
                // Non-negative float bits order like the floats themselves.
                keys[quad] = (long) Float.floatToIntBits(dx * dx + dy * dy + dz * dz) << 32 | quad;
            }
            Arrays.sort(keys);
            var out = indices[slot].mapped.asIntBuffer();
            for (int i = quads - 1; i >= 0; i--) { int base = (int) keys[i] * 6; for (int k = 0; k < 6; k++) out.put(base + k); }
            sortedAt[slot * 3] = x; sortedAt[slot * 3 + 1] = y; sortedAt[slot * 3 + 2] = z; sorted[slot] = true;
            translucentSorts++;
        }
        @Override public void close() { vertices.close(); for (GpuBuffer index : indices) if (index != null) index.close(); }
    }
    private final VulkanContext context;
    private final Map<Long, Section> sections = new LinkedHashMap<>();
    private final Set<Long> tracked = new HashSet<>();
    private final java.util.function.Consumer<String> log;
    private int peakSections;
    private long epoch, descriptorLayout, descriptorPool, descriptorSet, layout, pipeline, translucentPipeline, sampler, lightSampler;
    private long translucentSorts, translucentDraws;
    private GpuImage atlas, lightMap;
    private MeshArena arena;
    private long drivenLayout, drivenPipeline, indirectBatches;
    private long atlasPatches, lightUpdates;
    private WorldProtocol.Camera camera;
    private boolean receivedMesh;
    private java.util.function.Consumer<Runnable> retirement = Runnable::run;
    void retireUsing(java.util.function.Consumer<Runnable> retirement) { this.retirement = retirement; }

    WorldScene(VulkanContext context) { this(context, System.out::println); }
    WorldScene(VulkanContext context, java.util.function.Consumer<String> log) { this.context = context; this.log = log; }

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
            translucentPipeline = GraphicsPipeline.create(context, layout, VK_FORMAT_R8G8B8A8_UNORM, "/shaders/world.vert", "/shaders/world_translucent.frag", true, true, stack);
            var samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0);
            check(vkCreateSampler(context.device, samplerInfo, null, handle), "world atlas sampler"); sampler = handle.get(0);
            samplerInfo.magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR);
            check(vkCreateSampler(context.device, samplerInfo, null, handle), "world lightmap sampler"); lightSampler = handle.get(0);
        }
    }

    void enableGpuDriven(GpuCuller culler) {
        if (!culler.compact()) return;
        arena = new MeshArena(context, action -> retirement.accept(action));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreatePipelineLayout(context.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout, culler.descriptorLayout()))
                    .pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).size(80)), null, handle), "GPU driven world layout");
            drivenLayout = handle.get(0);
            drivenPipeline = GraphicsPipeline.create(context, drivenLayout, VK_FORMAT_R8G8B8A8_UNORM, "/shaders/world_driven.vert", true, stack);
        }
    }
    long indirectBatches() { return indirectBatches; }
    long[] arenaStats() { return arena == null ? new long[3] : arena.stats(); }

    void apply(WorldProtocol.Command command) {
        if (command instanceof WorldProtocol.Reset reset) {
            clear(); epoch = reset.epoch();
            log.accept("WORLD_RESET=" + epoch);
            return;
        }
        if (command.epoch() != epoch || epoch == 0) return;
        if (command instanceof WorldProtocol.Camera view) camera = view;
        else if (command instanceof WorldProtocol.Atlas image) uploadAtlas(image);
        else if (command instanceof WorldProtocol.TexturePatch patch) updateTexture(patch);
        else if (command instanceof WorldProtocol.Remove remove) { tracked.remove(remove.key()); Section old = sections.remove(remove.key()); if (old != null) retirement.accept(old::close); }
        else if (command instanceof WorldProtocol.Mesh mesh) {
            receivedMesh = true;
            if (tracked.size() >= GpuCuller.CAPACITY && !tracked.contains(mesh.key())) throw new IllegalStateException("World section cache limit exceeded");
            Section replacement = null;
            if (mesh.vertices().length > 0 || mesh.translucent().length > 0) {
                GpuBuffer opaque = mesh.vertices().length == 0 || arena != null ? null : new GpuBuffer(context, mesh.vertices(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                MeshArena.Allocation allocation = arena != null && mesh.vertices().length > 0 ? arena.upload(mesh.vertices()) : null;
                try {
                    replacement = new Section(mesh.x(), mesh.y(), mesh.z(), mesh.vertices().length / WorldProtocol.STRIDE, opaque, allocation,
                            bounds(mesh.vertices().length > 0 ? mesh.vertices() : mesh.translucent()), mesh.translucent().length == 0 ? null : new Translucent(mesh.translucent()));
                } catch (Throwable error) { if (opaque != null) opaque.close(); if (allocation != null) allocation.close(); throw error; }
            }
            Section old = sections.remove(mesh.key());
            if (old != null) retirement.accept(old::close);
            if (replacement != null) sections.put(mesh.key(), replacement);
            tracked.add(mesh.key()); peakSections = Math.max(peakSections, tracked.size());
            log.accept("WORLD_MESH=" + mesh.key() + " vertices=" + mesh.vertices().length / WorldProtocol.STRIDE + " translucent=" + mesh.translucent().length / WorldProtocol.STRIDE
                    + " cached=" + sections.size() + " tracked=" + tracked.size());
        }
    }

    boolean ready() { return camera != null && atlas != null && lightMap != null && receivedMesh; }
    long renderedEpoch() { return ready() ? epoch : 0; }
    long epoch() { return epoch; }
    int trackedSections() { return tracked.size(); }
    int peakSections() { return peakSections; }
    int drawableSections() { return sections.size(); }
    /** Lightmap shared with the entity pass; 0 until the first lightmap patch of this epoch. */
    long lightMapView() { return lightMap == null ? 0 : lightMap.view; }
    long lightSampler() { return lightSampler; }
    int translucentSections() { int count = 0; for (Section section : sections.values()) if (section.translucent() != null) count++; return count; }
    long translucentSorts() { return translucentSorts; }
    long translucentDraws() { return translucentDraws; }
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
            log.accept("WORLD_ATLAS=" + pixels.width() + "x" + pixels.height());
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
            if (++lightUpdates == 1 || lightUpdates % 100 == 0) log.accept("WORLD_LIGHTMAP_UPDATES=" + lightUpdates);
        } else {
            if (atlas == null || (long) patch.x() + patch.width() > atlas.width || (long) patch.y() + patch.height() > atlas.height)
                throw new IllegalArgumentException("Atlas patch exceeds current image bounds");
            uploadPixels(atlas, patch.x(), patch.y(), patch.width(), patch.height(), patch.rgba(), false);
            if (++atlasPatches == 1 || atlasPatches % 100 == 0) log.accept("WORLD_ATLAS_PATCHES=" + atlasPatches);
        }
    }

    private void uploadPixels(GpuImage image, int x, int y, int width, int height, byte[] rgba, boolean fresh) {
        context.uploads.submit(rgba, (upload, staging, stack) -> {
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
        });
    }

    private void updateDescriptor(int binding, GpuImage image, long imageSampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var imageInfo = VkDescriptorImageInfo.calloc(1, stack).sampler(imageSampler).imageView(image.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(descriptorSet).dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imageInfo);
            vkUpdateDescriptorSets(context.device, write, null);
        }
    }

    private static float[] bounds(byte[] vertices) {
        float[] result = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        var bytes = java.nio.ByteBuffer.wrap(vertices).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < vertices.length; offset += WorldProtocol.STRIDE) for (int axis = 0; axis < 3; axis++) {
            float value = bytes.getFloat(offset + axis * 4);
            result[axis] = Math.min(result[axis], value); result[axis + 3] = Math.max(result[axis + 3], value);
        }
        return result;
    }
    void prepareCulling(VkCommandBuffer command, MemoryStack stack, GpuCuller culler, int slot) {
        var input = culler.input(slot); input.clear();
        for (Section section : sections.values()) {
            double[] offset = {section.x() - camera.x(), section.y() - camera.y(), section.z() - camera.z()};
            for (int bound = 0; bound < 2; bound++) {
                for (int axis = 0; axis < 3; axis++) input.putFloat((float)(offset[axis] + section.bounds()[bound * 3 + axis]) + (bound == 0 ? -.001f : .001f));
                input.putFloat(0);
            }
            input.putInt(section.vertices()).putInt(section.allocation() == null ? 0 : section.allocation().firstVertex).putInt(0).putInt(0);
            for (int axis = 0; axis < 3; axis++) input.putFloat((float) offset[axis]); input.putFloat(0);
        }
        culler.record(command, slot, camera.matrix(), sections.size(), stack);
    }
    void draw(VkCommandBuffer command, MemoryStack stack) { draw(command, stack, null, 0); }
    void draw(VkCommandBuffer command, MemoryStack stack, GpuCuller culler, int slot) {
        if (arena != null && culler != null && culler.compact()) {
            if (arena.buffer() == 0) return;
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, drivenPipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, drivenLayout, 0, stack.longs(descriptorSet, culler.descriptorSet(slot)), null);
            var push = stack.callocFloat(20); push.put(camera.matrix()).position(0);
            vkCmdPushConstants(command, drivenLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, push);
            vkCmdBindVertexBuffers(command, 0, stack.longs(arena.buffer()), stack.longs(0));
            culler.drawAll(command, slot); indirectBatches++;
            return;
        }
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(descriptorSet), null);
        var push = stack.mallocFloat(20);
        push.put(camera.matrix());
        push.put(0).put(0).put(0).put(0).flip();
        var buffers = stack.mallocLong(1);
        var offsets = stack.longs(0);
        int index = 0;
        for (Section section : sections.values()) {
            // Subtract in double before conversion: preserves precision at large world coordinates.
            push.put(16, (float) (section.x() - camera.x())).put(17, (float) (section.y() - camera.y())).put(18, (float) (section.z() - camera.z()));
            vkCmdPushConstants(command, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, push);
            if (section.buffer() != null) {
                buffers.put(0, section.buffer().buffer);
                vkCmdBindVertexBuffers(command, 0, buffers, offsets);
                if (culler == null) vkCmdDraw(command, section.vertices(), 1, 0, 0);
                else culler.draw(command, slot, index);
            }
            index++;
        }
    }

    /** Back-to-front sections, each with back-to-front quads; drawn after opaque terrain into the translucent target. */
    void drawTranslucent(VkCommandBuffer command, MemoryStack stack, int slot) {
        if (slot < 0 || slot >= TRANSLUCENT_SLOTS) throw new IllegalArgumentException("Translucent slot out of range: " + slot);
        List<Section> ordered = new ArrayList<>();
        for (Section section : sections.values()) if (section.translucent() != null) ordered.add(section);
        if (ordered.isEmpty()) return;
        ordered.sort(Comparator.comparingDouble(section -> -distanceSquared(section)));
        int resorts = 0;
        // Nearest stale sections spend the re-sort budget first; never-sorted slots are always filled before use.
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Section section = ordered.get(i); Translucent translucent = section.translucent();
            double x = camera.x() - section.x(), y = camera.y() - section.y(), z = camera.z() - section.z();
            if (!translucent.sorted[slot]) translucent.sort(slot, x, y, z);
            else if (resorts < RESORT_BUDGET && translucent.stale(slot, x, y, z)) { translucent.sort(slot, x, y, z); resorts++; }
        }
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, translucentPipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(descriptorSet), null);
        var push = stack.mallocFloat(20);
        push.put(camera.matrix());
        push.put(0).put(0).put(0).put(0).flip();
        var buffers = stack.mallocLong(1);
        var offsets = stack.longs(0);
        for (Section section : ordered) {
            Translucent translucent = section.translucent();
            push.put(16, (float) (section.x() - camera.x())).put(17, (float) (section.y() - camera.y())).put(18, (float) (section.z() - camera.z()));
            vkCmdPushConstants(command, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, push);
            buffers.put(0, translucent.vertices.buffer);
            vkCmdBindVertexBuffers(command, 0, buffers, offsets);
            vkCmdBindIndexBuffer(command, translucent.indices[slot].buffer, 0, VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexed(command, translucent.quads * 6, 1, 0, 0, 0);
            translucentDraws++;
        }
    }

    private double distanceSquared(Section section) {
        double x = section.x() + 8 - camera.x(), y = section.y() + 8 - camera.y(), z = section.z() + 8 - camera.z();
        return x * x + y * y + z * z;
    }

    private void clear() {
        context.uploads.drain();
        sections.values().forEach(Section::close); sections.clear(); tracked.clear(); camera = null; receivedMesh = false;
        if (atlas != null) { atlas.close(); atlas = null; }
        if (lightMap != null) { lightMap.close(); lightMap = null; }
    }

    @Override public void close() {
        vkDeviceWaitIdle(context.device); clear();
        if (arena != null) { log.accept("MESH_ARENA=" + java.util.Arrays.toString(arena.stats()) + " indirectBatches=" + indirectBatches); arena.close(); arena = null; }
        if (drivenPipeline != 0) { vkDestroyPipeline(context.device, drivenPipeline, null); drivenPipeline = 0; }
        if (drivenLayout != 0) { vkDestroyPipelineLayout(context.device, drivenLayout, null); drivenLayout = 0; }
        if (pipeline != 0) { vkDestroyPipeline(context.device, pipeline, null); pipeline = 0; }
        if (translucentPipeline != 0) { vkDestroyPipeline(context.device, translucentPipeline, null); translucentPipeline = 0; }
        if (layout != 0) { vkDestroyPipelineLayout(context.device, layout, null); layout = 0; }
        if (descriptorPool != 0) { vkDestroyDescriptorPool(context.device, descriptorPool, null); descriptorPool = 0; }
        if (descriptorLayout != 0) { vkDestroyDescriptorSetLayout(context.device, descriptorLayout, null); descriptorLayout = 0; }
        if (sampler != 0) { vkDestroySampler(context.device, sampler, null); sampler = 0; }
        if (lightSampler != 0) { vkDestroySampler(context.device, lightSampler, null); lightSampler = 0; }
    }
}
