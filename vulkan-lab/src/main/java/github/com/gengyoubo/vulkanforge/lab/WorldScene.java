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
    /** cullIndex: stable record in the GPU culling input; it survives rebuilds of the same section. */
    private record Section(int x, int y, int z, int vertices, GpuBuffer buffer, MeshArena.Allocation allocation, float[] bounds, Translucent translucent, int cullIndex) implements AutoCloseable {
        @Override public void close() { if (buffer != null) buffer.close(); if (allocation != null) allocation.close(); if (translucent != null) translucent.close(); }
    }
    /** Frame slots that may record translucent draws concurrently; matches SharedWorldRenderer. */
    static final int TRANSLUCENT_SLOTS = 2;
    /**
     * Translucent sorts per slot, per frame: at most this many sections and (after the first) this many quads, nearest stale
     * sections first. A never-sorted slot draws its identity quad order until its turn comes.
     */
    private static final int SORT_BUDGET = 16, SORT_QUAD_BUDGET = 1 << 16;
    /** Culling records are stored relative to an anchor within this many blocks of the camera, keeping float offsets small. */
    private static final double ANCHOR_RANGE = 512;
    /** Triangles stay in quad order (6 vertices per quad); each frame slot owns an index buffer it rewrites only after its fence. */
    private final class Translucent implements AutoCloseable {
        final GpuBuffer vertices;
        final GpuBuffer[] indices = new GpuBuffer[TRANSLUCENT_SLOTS];
        final int quads;
        final long upload;
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
            // Device-local: every frame re-reads all translucent vertices, which over PCIe from host memory dominated the pass.
            GpuBuffer created = new GpuBuffer(context, bytes.length, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
            try {
                for (int slot = 0; slot < TRANSLUCENT_SLOTS; slot++) {
                    indices[slot] = new GpuBuffer(context, quads * 6 * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
                    var out = indices[slot].mapped.asIntBuffer(); for (int i = 0; i < quads * 6; i++) out.put(i);
                }
                // Last, so a failure above never frees a buffer with a recorded copy.
                long destination = created.buffer;
                upload = context.uploads.submit(bytes, (command, staging, offset, stack) -> {
                    vkCmdCopyBuffer(command, staging.buffer, destination, VkBufferCopy.calloc(1, stack).srcOffset(offset).size(bytes.length));
                    var barrier = VkBufferMemoryBarrier2.calloc(1, stack).sType$Default()
                            .srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                            .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_ATTRIBUTE_INPUT_BIT).dstAccessMask(VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT)
                            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .buffer(destination).offset(0).size(bytes.length);
                    vkCmdPipelineBarrier2(command, VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
                });
            }
            catch (Throwable error) { created.close(); for (GpuBuffer index : indices) if (index != null) index.close(); throw error; }
            vertices = created;
        }
        boolean stale(int slot, double x, double y, double z) {
            double dx = x - sortedAt[slot * 3], dy = y - sortedAt[slot * 3 + 1], dz = z - sortedAt[slot * 3 + 2];
            return !sorted[slot] || dx * dx + dy * dy + dz * dz > 1;
        }
        /** x/y/z: camera relative to the section origin. Farthest quad first. */
        void sort(int slot, double x, double y, double z) {
            long[] keys = sortKeys = sortKeys.length >= quads ? sortKeys : new long[Math.max(quads, sortKeys.length * 2)];
            for (int quad = 0; quad < quads; quad++) {
                float dx = (float)(centers[quad * 3] - x), dy = (float)(centers[quad * 3 + 1] - y), dz = (float)(centers[quad * 3 + 2] - z);
                // Non-negative float bits order like the floats themselves.
                keys[quad] = (long) Float.floatToIntBits(dx * dx + dy * dy + dz * dz) << 32 | quad;
            }
            Arrays.sort(keys, 0, quads);
            var out = indices[slot].mapped.asIntBuffer();
            for (int i = quads - 1; i >= 0; i--) { int base = (int) keys[i] * 6; for (int k = 0; k < 6; k++) out.put(base + k); }
            sortedAt[slot * 3] = x; sortedAt[slot * 3 + 1] = y; sortedAt[slot * 3 + 2] = z; sorted[slot] = true;
            translucentSorts++;
        }
        /** A section removed before any frame drew it may still have its copy in flight: free the buffer after the upload batch. */
        @Override public void close() { context.uploads.retire(upload, vertices::close); for (GpuBuffer index : indices) if (index != null) index.close(); }
    }
    private final VulkanContext context;
    private final Map<Long, Section> sections = new LinkedHashMap<>();
    private final Set<Long> tracked = new HashSet<>();
    private final java.util.function.Consumer<String> log;
    private int peakSections;
    private long epoch, descriptorLayout, descriptorPool, descriptorSet, layout, pipeline, translucentPipeline, sampler, lightSampler;
    private long translucentSorts, translucentDraws, cullWrites, arenaReuses;
    /** Reused scratch: quad sort keys, per-frame translucent section order and its distance keys. */
    private long[] sortKeys = new long[0], orderKeys = new long[0];
    private final List<Section> translucentOrder = new ArrayList<>();
    private final List<Section> cullSections = new ArrayList<>();
    private final BitSet freeCull = new BitSet();
    /** Per culler slot: records changed since that slot's input buffer was last written. */
    private BitSet[] cullDirty;
    private double anchorX, anchorY, anchorZ;
    private boolean anchored;
    private GpuImage atlas, lightMap;
    private MeshArena arena;
    private long drivenLayout, drivenPipeline, indirectBatches;
    /** VK_NV_mesh_shader terrain: task shader culls sections, mesh shader reads the arena as a storage buffer (set 2, one per frame slot). */
    private long meshLayout, meshPipeline, arenaLayout, arenaPool, meshBatches;
    private long[] arenaSets = new long[0], arenaBound = new long[0];
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
            binding.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | (context.meshShaderNV ? NVMeshShader.VK_SHADER_STAGE_MESH_BIT_NV : 0));
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

    private long cullerLayout;
    void enableGpuDriven(GpuCuller culler) {
        if (!culler.compact()) return;
        cullerLayout = culler.descriptorLayout();
        arena = new MeshArena(context, action -> retirement.accept(action));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            check(vkCreatePipelineLayout(context.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout, culler.descriptorLayout()))
                    .pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).size(80)), null, handle), "GPU driven world layout");
            drivenLayout = handle.get(0);
            drivenPipeline = GraphicsPipeline.create(context, drivenLayout, VK_FORMAT_R8G8B8A8_UNORM, "/shaders/world_driven.vert", true, stack);
        }
        if (context.meshShaderNV) {
            try { enableMeshShader(culler.slots()); }
            catch (RuntimeException error) { destroyMeshShader(); log.accept("MESH_SHADER_NV_FALLBACK=" + error.getMessage()); }
        }
    }

    private void enableMeshShader(int slots) {
        int stages = NVMeshShader.VK_SHADER_STAGE_TASK_BIT_NV | NVMeshShader.VK_SHADER_STAGE_MESH_BIT_NV;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var binding = VkDescriptorSetLayoutBinding.calloc(1, stack).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                    .stageFlags(NVMeshShader.VK_SHADER_STAGE_MESH_BIT_NV);
            check(vkCreateDescriptorSetLayout(context.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding), null, handle), "mesh arena layout");
            arenaLayout = handle.get(0);
            check(vkCreateDescriptorPool(context.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(slots)
                    .pPoolSizes(VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(slots)), null, handle), "mesh arena pool");
            arenaPool = handle.get(0);
            arenaSets = new long[slots]; arenaBound = new long[slots];
            for (int i = 0; i < slots; i++) {
                check(vkAllocateDescriptorSets(context.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(arenaPool)
                        .pSetLayouts(stack.longs(arenaLayout)), handle), "mesh arena set");
                arenaSets[i] = handle.get(0);
            }
            check(vkCreatePipelineLayout(context.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout, cullerLayout, arenaLayout))
                    .pPushConstantRanges(VkPushConstantRange.calloc(1, stack).stageFlags(stages).size(96)), null, handle), "mesh world layout");
            meshLayout = handle.get(0);
            meshPipeline = GraphicsPipeline.createMesh(context, meshLayout, VK_FORMAT_R8G8B8A8_UNORM, "/shaders/world_mesh.task", "/shaders/world_mesh.mesh", stack);
        }
    }
    boolean meshShaderActive() { return meshPipeline != 0; }
    private void destroyMeshShader() {
        if (meshPipeline != 0) { vkDestroyPipeline(context.device, meshPipeline, null); meshPipeline = 0; }
        if (meshLayout != 0) { vkDestroyPipelineLayout(context.device, meshLayout, null); meshLayout = 0; }
        if (arenaPool != 0) { vkDestroyDescriptorPool(context.device, arenaPool, null); arenaPool = 0; }
        if (arenaLayout != 0) { vkDestroyDescriptorSetLayout(context.device, arenaLayout, null); arenaLayout = 0; }
    }
    /** GPU-driven terrain batches: indirect-count draws or NV mesh task launches. */
    long indirectBatches() { return indirectBatches + meshBatches; }
    long[] arenaStats() { return arena == null ? new long[5] : arena.stats(); }

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
        else if (command instanceof WorldProtocol.Remove remove) {
            tracked.remove(remove.key()); Section old = sections.remove(remove.key());
            if (old != null) { setCull(old.cullIndex(), null); retirement.accept(old::close); }
        }
        else if (command instanceof WorldProtocol.Mesh mesh) {
            receivedMesh = true;
            if (tracked.size() >= GpuCuller.CAPACITY && !tracked.contains(mesh.key())) throw new IllegalStateException("World section cache limit exceeded");
            Section old = sections.get(mesh.key()), replacement = null;
            if (mesh.vertices().length > 0 || mesh.translucent().length > 0) {
                int cullIndex = old != null ? old.cullIndex() : claimCullIndex();
                GpuBuffer opaque = null;
                MeshArena.Allocation allocation = null;
                boolean reused = false;
                try {
                    if (mesh.vertices().length > 0 && arena == null) opaque = new GpuBuffer(context, mesh.vertices(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                    if (mesh.vertices().length > 0 && arena != null) {
                        // A rebuilt section overwrites its own arena range when the new mesh fits.
                        if (old != null && old.allocation() != null) { allocation = old.allocation().replace(mesh.vertices()); reused = allocation != null; }
                        if (allocation == null) allocation = arena.upload(mesh.vertices());
                    }
                    replacement = new Section(mesh.x(), mesh.y(), mesh.z(), mesh.vertices().length / WorldProtocol.STRIDE, opaque, allocation,
                            bounds(mesh.vertices().length > 0 ? mesh.vertices() : mesh.translucent()), mesh.translucent().length == 0 ? null : new Translucent(mesh.translucent()), cullIndex);
                } catch (Throwable error) {
                    if (opaque != null) opaque.close(); if (allocation != null) allocation.close();
                    // The old section no longer owns its range once it was reused; drop it rather than draw overwritten vertices.
                    if (reused) { sections.remove(mesh.key()); retirement.accept(old::close); }
                    if (old == null || reused) setCull(cullIndex, null);
                    throw error;
                }
                if (reused) arenaReuses++;
            }
            if (old != null) { sections.remove(mesh.key()); retirement.accept(old::close); }
            if (replacement != null) { sections.put(mesh.key(), replacement); setCull(replacement.cullIndex(), replacement); }
            else if (old != null) setCull(old.cullIndex(), null);
            tracked.add(mesh.key()); peakSections = Math.max(peakSections, tracked.size());
            log.accept("WORLD_MESH=" + mesh.key() + " vertices=" + mesh.vertices().length / WorldProtocol.STRIDE + " translucent=" + mesh.translucent().length / WorldProtocol.STRIDE
                    + " cached=" + sections.size() + " tracked=" + tracked.size());
        }
    }

    private int claimCullIndex() {
        int index = freeCull.nextSetBit(0);
        if (index < 0) { index = cullSections.size(); cullSections.add(null); } else freeCull.clear(index);
        return index;
    }
    private void setCull(int index, Section section) {
        cullSections.set(index, section);
        if (section == null) freeCull.set(index);
        if (cullDirty != null) for (BitSet dirty : cullDirty) dirty.set(index);
    }

    boolean ready() { return camera != null && atlas != null && lightMap != null && receivedMesh; }
    long renderedEpoch() { return ready() ? epoch : 0; }
    long epoch() { return epoch; }
    int trackedSections() { return tracked.size(); }
    int peakSections() { return peakSections; }
    int drawableSections() { return sections.size(); }
    /** Lightmap shared with the entity pass; 0 until the first lightmap patch of this epoch. */
    long lightMapView() { return lightMap == null ? 0 : lightMap.view; }
    Object lightMapIdentity() { return lightMap; }
    long lightSampler() { return lightSampler; }
    int translucentSections() { int count = 0; for (Section section : sections.values()) if (section.translucent() != null) count++; return count; }
    long translucentSorts() { return translucentSorts; }
    long translucentDraws() { return translucentDraws; }
    float red() { return camera.red(); }
    float green() { return camera.green(); }
    float blue() { return camera.blue(); }

    private void uploadAtlas(WorldProtocol.Atlas pixels) {
        context.uploads.drain(); // Full atlas replacement is a reload boundary; ordinary rectangular patches stay asynchronous.
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
        context.uploads.submit(rgba, (upload, staging, offset, stack) -> {
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(fresh ? VK_PIPELINE_STAGE_2_NONE : VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(fresh ? 0 : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .oldLayout(fresh ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            var dependency = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(upload, dependency);
            var region = VkBufferImageCopy.calloc(1, stack).bufferOffset(offset).imageSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
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
    /** Rewrites only the records changed since this slot's last frame; the camera moves through a push-constant shift. */
    void prepareCulling(VkCommandBuffer command, MemoryStack stack, GpuCuller culler, int slot) {
        int count = cullSections.size();
        if (cullDirty == null) { cullDirty = new BitSet[culler.slots()]; for (int i = 0; i < cullDirty.length; i++) (cullDirty[i] = new BitSet()).set(0, count); }
        if (!anchored || Math.abs(camera.x() - anchorX) > ANCHOR_RANGE || Math.abs(camera.y() - anchorY) > ANCHOR_RANGE || Math.abs(camera.z() - anchorZ) > ANCHOR_RANGE) {
            anchorX = Math.floor(camera.x() / 256) * 256; anchorY = Math.floor(camera.y() / 256) * 256; anchorZ = Math.floor(camera.z() / 256) * 256;
            anchored = true;
            for (BitSet dirty : cullDirty) dirty.set(0, count);
        }
        var input = culler.input(slot); BitSet dirty = cullDirty[slot];
        for (int i = dirty.nextSetBit(0); i >= 0 && i < count; i = dirty.nextSetBit(i + 1)) { writeCull(input, i, cullSections.get(i)); cullWrites++; }
        dirty.clear();
        if (meshPipeline != 0) culler.recordMesh(command, slot, count, sections.size(), stack);
        else culler.record(command, slot, camera.matrix(), cameraShift(), count, sections.size(), stack);
    }
    /** Anchor-relative records + this shift = camera-relative positions. */
    private float[] cameraShift() { return new float[]{(float) (anchorX - camera.x()), (float) (anchorY - camera.y()), (float) (anchorZ - camera.z())}; }
    /** 64-byte record: AABB min, AABB max, {vertices, firstVertex, 0, 0}, origin; all zero (never visible) for a free index. */
    private void writeCull(java.nio.ByteBuffer input, int index, Section section) {
        input.position(index * GpuCuller.INPUT_STRIDE);
        if (section == null) { for (int i = 0; i < GpuCuller.INPUT_STRIDE / 4; i++) input.putInt(0); return; }
        // Subtract in double before conversion: preserves precision at large world coordinates.
        double[] offset = {section.x() - anchorX, section.y() - anchorY, section.z() - anchorZ};
        for (int bound = 0; bound < 2; bound++) {
            for (int axis = 0; axis < 3; axis++) input.putFloat((float)(offset[axis] + section.bounds()[bound * 3 + axis]) + (bound == 0 ? -.001f : .001f));
            input.putFloat(0);
        }
        input.putInt(section.vertices()).putInt(section.allocation() == null ? 0 : section.allocation().firstVertex).putInt(0).putInt(0);
        for (int axis = 0; axis < 3; axis++) input.putFloat((float) offset[axis]); input.putFloat(0);
    }
    void draw(VkCommandBuffer command, MemoryStack stack) { draw(command, stack, null, 0); }
    void draw(VkCommandBuffer command, MemoryStack stack, GpuCuller culler, int slot) {
        if (meshPipeline != 0 && arena != null && culler != null) {
            int count = culler.count(slot);
            if (arena.buffer() == 0 || count == 0) return;
            // This slot's previous submission has completed, so its arena set can follow a grown arena buffer.
            if (arenaBound[slot] != arena.buffer()) {
                vkUpdateDescriptorSets(context.device, VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(arenaSets[slot]).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(arena.buffer()).range(VK_WHOLE_SIZE)), null);
                arenaBound[slot] = arena.buffer();
            }
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, meshPipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, meshLayout, 0, stack.longs(descriptorSet, culler.descriptorSet(slot), arenaSets[slot]), null);
            float[] shift = cameraShift();
            var push = stack.calloc(96); push.asFloatBuffer().put(camera.matrix()); push.putInt(64, count);
            push.putFloat(80, shift[0]).putFloat(84, shift[1]).putFloat(88, shift[2]);
            vkCmdPushConstants(command, meshLayout, NVMeshShader.VK_SHADER_STAGE_TASK_BIT_NV | NVMeshShader.VK_SHADER_STAGE_MESH_BIT_NV, 0, push);
            NVMeshShader.vkCmdDrawMeshTasksNV(command, (count + 31) / 32, 0); meshBatches++;
            return;
        }
        if (arena != null && culler != null && culler.compact()) {
            if (arena.buffer() == 0) return;
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, drivenPipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, drivenLayout, 0, stack.longs(descriptorSet, culler.descriptorSet(slot)), null);
            float[] shift = cameraShift();
            var push = stack.callocFloat(20); push.put(camera.matrix()).put(shift).position(0);
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
        for (Section section : sections.values()) {
            // Subtract in double before conversion: preserves precision at large world coordinates.
            push.put(16, (float) (section.x() - camera.x())).put(17, (float) (section.y() - camera.y())).put(18, (float) (section.z() - camera.z()));
            vkCmdPushConstants(command, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, push);
            if (section.buffer() != null) {
                buffers.put(0, section.buffer().buffer);
                vkCmdBindVertexBuffers(command, 0, buffers, offsets);
                if (culler == null) vkCmdDraw(command, section.vertices(), 1, 0, 0);
                else culler.draw(command, slot, section.cullIndex());
            }
        }
    }

    /** Back-to-front sections, each with back-to-front quads; drawn after opaque terrain into the translucent target. */
    void drawTranslucent(VkCommandBuffer command, MemoryStack stack, int slot) {
        if (slot < 0 || slot >= TRANSLUCENT_SLOTS) throw new IllegalArgumentException("Translucent slot out of range: " + slot);
        List<Section> ordered = translucentOrder; ordered.clear();
        for (Section section : sections.values()) if (section.translucent() != null) ordered.add(section);
        int count = ordered.size();
        if (count == 0) return;
        // Nearest first by packed (distance bits, index) keys: no comparator, no boxing.
        long[] keys = orderKeys = orderKeys.length >= count ? orderKeys : new long[Math.max(count, orderKeys.length * 2)];
        for (int i = 0; i < count; i++) keys[i] = (long) Float.floatToIntBits((float) distanceSquared(ordered.get(i))) << 32 | i;
        Arrays.sort(keys, 0, count);
        int sorts = 0; long sortedQuads = 0;
        for (int i = 0; i < count && sorts < SORT_BUDGET; i++) {
            Section section = ordered.get((int) keys[i]); Translucent translucent = section.translucent();
            double x = camera.x() - section.x(), y = camera.y() - section.y(), z = camera.z() - section.z();
            if (!translucent.stale(slot, x, y, z) || (sorts > 0 && sortedQuads + translucent.quads > SORT_QUAD_BUDGET)) continue;
            translucent.sort(slot, x, y, z); sorts++; sortedQuads += translucent.quads;
        }
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, translucentPipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(descriptorSet), null);
        var push = stack.mallocFloat(20);
        push.put(camera.matrix());
        push.put(0).put(0).put(0).put(0).flip();
        var buffers = stack.mallocLong(1);
        var offsets = stack.longs(0);
        for (int i = count - 1; i >= 0; i--) { // Back to front.
            Section section = ordered.get((int) keys[i]); Translucent translucent = section.translucent();
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
        cullSections.clear(); freeCull.clear(); anchored = false; if (cullDirty != null) for (BitSet dirty : cullDirty) dirty.clear();
        if (atlas != null) { atlas.close(); atlas = null; }
        if (lightMap != null) { lightMap.close(); lightMap = null; }
    }

    @Override public void close() {
        vkDeviceWaitIdle(context.device); clear();
        log.accept("CULL_INPUT_WRITES=" + cullWrites + " ARENA_IN_PLACE_REBUILDS=" + arenaReuses + " TRANSLUCENT_SORTS=" + translucentSorts);
        if (arena != null) { log.accept("MESH_ARENA=" + java.util.Arrays.toString(arena.stats()) + " indirectBatches=" + indirectBatches + " meshTaskBatches=" + meshBatches); arena.close(); arena = null; }
        destroyMeshShader();
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
