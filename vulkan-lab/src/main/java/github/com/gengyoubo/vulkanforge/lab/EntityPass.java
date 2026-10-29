package github.com.gengyoubo.vulkanforge.lab;

import static github.com.gengyoubo.vulkanforge.lab.VulkanCommands.vkCmdPipelineBarrier2;

import java.util.*;
import java.util.function.Consumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Replays vanilla RenderType draws of the entity/text shader families captured on the GL thread (P7 block entities, P8 entities).
 * Packets are JDK-only: {int[] header, float[] uniforms (64 floats, entity_common.glsl layout), byte[] vertices, byte[] indices or null}.
 * Textures are mirrored by GL texture id; each upload creates a new image and the old one is retired after the frames using it.
 */
final class EntityPass implements AutoCloseable {
    static final int H_FORMAT = 0, H_VARIANT = 1, H_FLAGS = 2, H_TEXTURE = 3, H_OVERLAY = 4, H_VERTICES = 5, H_MODE = 6, H_INDICES = 7, H_INDEX_SIZE = 8, HEADER = 9;
    static final int FORMAT_ENTITY = 0, FORMAT_TEXT = 1, FORMAT_PARTICLE = 2;
    /** P10: POSITION, POSITION_COLOR, POSITION_TEX, POSITION_TEX_COLOR, POSITION_TEX_COLOR_NORMAL (sky, sun/moon, stars, clouds). */
    static final int FORMAT_POSITION = 3, FORMAT_POSITION_COLOR = 4, FORMAT_POSITION_TEX = 5, FORMAT_POSITION_TEX_COLOR = 6, FORMAT_CLOUDS = 7;
    /** P11: POSITION_COLOR_TEX (GuiGraphics colored blits, screen-effect overlays). */
    static final int FORMAT_POSITION_COLOR_TEX = 8;
    /** Texture id of the built-in 16x16 white image (also the fullbright GUI lightmap) used by untextured shaders. */
    static final int WHITE_TEXTURE = 0;
    static final int MODE_QUADS = 0, MODE_TRIANGLES = 1;
    /** BLEND: premultiplied "over"; BLEND_ADD: GL (SRC_ALPHA, ONE) additive; NO_COLOR: color writes off (cloud depth prepass). */
    static final int BLEND = 1, DEPTH_TEST = 2, DEPTH_WRITE = 4, CULL = 8, BLEND_ADD = 16, NO_COLOR = 32,
            BLEND_INVERT = 64, BLEND_MULTIPLY = 128, DEPTH_LESS = 256;
    static final int TEXTURE_LINEAR = 1, TEXTURE_CLAMP = 2;
    static final int UNIFORM_FLOATS = 64;
    private static final int UNIFORM_STRIDE = 256; // == the largest allowed minUniformBufferOffsetAlignment
    private static final int[] STRIDES = {36, 28, 28, 12, 16, 20, 24, 28, 24};
    private static final String[] VERTEX_SHADERS = {"/shaders/entity.vert", "/shaders/text.vert", "/shaders/particle.vert", "/shaders/simple_pos.vert",
            "/shaders/simple_color.vert", "/shaders/simple_tex.vert", "/shaders/simple_tex_color.vert", "/shaders/simple_tex_color.vert", "/shaders/simple_tex_color.vert"};
    private record Texture(GpuImage image, int flags) implements AutoCloseable { @Override public void close() { image.close(); } }
    private record BindingKey(Texture texture, Texture overlay, long lightView, long lightSampler, Object lightIdentity) { }
    private static final class SlotResources {
        GpuBuffer vertices, indices, uniforms;
        long pool, uniformSet, uniformBuffer;
        int poolSets;
        boolean invalidated;
        final Map<BindingKey, Long> textureSets = new HashMap<>();
    }
    private final VulkanContext context;
    private final int colorFormat;
    private final boolean direct, pushDescriptors;
    private final Consumer<Runnable> retire;
    private final Map<Integer, Texture> textures = new HashMap<>();
    private final Map<Integer, Long> pipelines = new HashMap<>();
    private final SlotResources[] slots;
    private final long[] samplers = new long[4];
    private long textureLayout, uniformLayout, layout;
    private GpuBuffer quadIndices;
    private long draws, vertices, textureUploads, missingTextures, descriptorAllocations, descriptorPushes, uniformWrites, bindingHits, mergedDraws;
    private static final boolean PROFILE = Boolean.getBoolean("vulkanforge.entityProfile");
    private final boolean legacyBatch = Boolean.getBoolean("vulkanforge.entityLegacyBatch");
    private long recordCalls, inputPackets, recordNanos, batchNanos, uploadedBytes, batchCopiedBytes, bufferGrowths;
    private final long[] recordSamples=PROFILE?new long[1024]:null;
    /** {record calls,input packets,record ns,batch ns,uploaded bytes,batch heap-copy bytes,pipelines,buffer growths,draws}. */
    long[] performanceStats() { return new long[]{recordCalls,inputPackets,recordNanos,batchNanos,uploadedBytes,batchCopiedBytes,pipelines.size(),bufferGrowths,draws}; }
    String recordPercentiles() {
        if(!PROFILE||recordCalls==0)return "disabled";
        int count=(int)Math.min(recordCalls,recordSamples.length);long[] sorted=Arrays.copyOf(recordSamples,count);Arrays.sort(sorted);
        return String.format(Locale.ROOT,"samples=%d p50=%.6f p99=%.6f",count,sorted[(count-1)/2]/1e6,sorted[(count-1)*99/100]/1e6);
    }

    EntityPass(VulkanContext context, int slotCount, Consumer<Runnable> retire) {
        this(context, slotCount, retire, VK_FORMAT_R8G8B8A8_UNORM, false);
    }

    EntityPass(VulkanContext context, int slotCount, Consumer<Runnable> retire, int colorFormat, boolean direct) {
        this(context, slotCount, retire, colorFormat, direct, context.pushDescriptors && Boolean.getBoolean("vulkanforge.preferPushDescriptors"));
    }
    EntityPass(VulkanContext context, int slotCount, Consumer<Runnable> retire, int colorFormat, boolean direct, boolean pushDescriptors) {
        this.context = context; this.retire = retire; this.colorFormat = colorFormat; this.direct = direct; this.pushDescriptors = pushDescriptors;
        slots = new SlotResources[slotCount];
        for (int i = 0; i < slotCount; i++) slots[i] = new SlotResources();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            var bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(context.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().flags(pushDescriptors ? org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0).pBindings(bindings), null, handle), "entity texture layout");
            textureLayout = handle.get(0);
            var uniform = VkDescriptorSetLayoutBinding.calloc(1, stack).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(context.device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(uniform), null, handle), "entity uniform layout");
            uniformLayout = handle.get(0);
            check(vkCreatePipelineLayout(context.device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(textureLayout, uniformLayout)), null, handle), "entity pipeline layout");
            layout = handle.get(0);
            for (int i = 0; i < 4; i++) {
                int filter = (i & TEXTURE_LINEAR) != 0 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
                int address = (i & TEXTURE_CLAMP) != 0 ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
                check(vkCreateSampler(context.device, VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(filter).minFilter(filter)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(address).addressModeV(address).addressModeW(address).maxLod(0), null, handle), "entity sampler");
                samplers[i] = handle.get(0);
            }
        } catch (Throwable error) { close(); throw error; }
        try { byte[] white = new byte[16 * 16 * 4]; Arrays.fill(white, (byte) 255); texture(WHITE_TEXTURE, 16, 16, 0, white); textureUploads = 0; }
        catch (Throwable error) { close(); throw error; }
    }

    boolean hasTexture(int id) { return textures.containsKey(id); }
    long textureView(int id) { return textures.get(id).image.view; }
    long textureSampler(int id) { return samplers[textures.get(id).flags]; }
    Object textureIdentity(int id) { return textures.get(id); }
    boolean usesPushDescriptors() { return pushDescriptors; }
    void invalidateDescriptors() { for (SlotResources slot : slots) slot.invalidated = true; }
    long[] bindingStats() { return new long[]{descriptorAllocations, descriptorPushes, uniformWrites, bindingHits, mergedDraws}; }
    long[] stats() { return new long[]{draws, vertices, textures.size(), textureUploads, missingTextures}; }

    /** Synchronous upload into a fresh image; never touches an image an in-flight frame may sample. */
    void texture(int id, int width, int height, int flags, byte[] rgba) {
        if (width < 1 || height < 1 || (long) width * height * 4 != rgba.length) throw new IllegalArgumentException("Invalid entity texture " + id);
        uploadTexture(id, width, height, flags, rgba, null, 0, 0, width, height);
    }

    /** Copy-on-write patch: old pixels stay GPU-resident, only the changed rectangle crosses the upload buffer. */
    void texturePatch(int id, int x, int y, int width, int height, byte[] rgba) {
        Texture source = textures.get(id);
        if (source == null) throw new IllegalStateException("Missing texture for patch " + id);
        if (x < 0 || y < 0 || width < 1 || height < 1 || x + width > source.image.width || y + height > source.image.height
                || (long) width * height * 4 != rgba.length) throw new IllegalArgumentException("Invalid texture patch");
        uploadTexture(id, source.image.width, source.image.height, source.flags, rgba, source, x, y, width, height);
    }

    private void uploadTexture(int id, int width, int height, int flags, byte[] rgba, Texture source, int x, int y, int patchWidth, int patchHeight) {
        GpuImage image = new GpuImage(context, width, height, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        long ticket;
        try {
            ticket = context.uploads.submit(rgba, (upload, staging, offset, stack) -> {
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                    .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.image)
                    .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            var dependency = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2(upload, dependency);
            if (source != null) {
                var old = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT).srcAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(source.image.image)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
                vkCmdPipelineBarrier2(upload, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(old));
                var copy = VkImageCopy.calloc(1, stack).srcSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                        .dstSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).extent(e -> e.set(width, height, 1));
                vkCmdCopyImage(upload, source.image.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                old.srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_READ_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT).dstAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                vkCmdPipelineBarrier2(upload, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(old));
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                vkCmdPipelineBarrier2(upload, dependency); // Copy destination -> patch destination write hazard.
            }
            vkCmdCopyBufferToImage(upload, staging.buffer, image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VkBufferImageCopy.calloc(1, stack).bufferOffset(offset)
                    .imageSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageOffset(o -> o.set(x, y, 0)).imageExtent(e -> e.set(patchWidth, patchHeight, 1)));
            barrier.srcStageMask(VK_PIPELINE_STAGE_2_COPY_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT).dstAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            vkCmdPipelineBarrier2(upload, dependency);
            });
        } catch (Throwable error) { image.close(); throw error; }
        Texture old = textures.put(id, new Texture(image, flags & 3));
        textureUploads++;
        if (old != null) context.uploads.retire(ticket, () -> retire.accept(old::close));
    }

    /** Records all draws inside an active dynamic-rendering pass. The slot's previous use must have completed. */
    void record(VkCommandBuffer command, MemoryStack stack, int slot, List<Object[]> packets, long lightView, long lightSampler) {
        record(command, stack, slot, packets, lightView, lightSampler, null);
    }
    void record(VkCommandBuffer command, MemoryStack stack, int slot, List<Object[]> packets, long lightView, long lightSampler, Object lightIdentity) {
        record(command,stack,slot,packets,lightView,lightSampler,lightIdentity,false);
    }
    void record(VkCommandBuffer command, MemoryStack stack, int slot, List<Object[]> packets, long lightView, long lightSampler, Object lightIdentity, boolean glTarget) {
        long start=PROFILE?System.nanoTime():0;recordCalls++;inputPackets+=packets.size();
        try { recordPackets(command,stack,slot,packets,lightView,lightSampler,lightIdentity,glTarget); }
        finally {if(PROFILE){long elapsed=System.nanoTime()-start;recordNanos+=elapsed;recordSamples[(int)((recordCalls-1)%recordSamples.length)]=elapsed;}}
    }
    private void recordPackets(VkCommandBuffer command, MemoryStack stack, int slot, List<Object[]> packets, long lightView, long lightSampler, Object lightIdentity, boolean glTarget) {
        if (packets.isEmpty()) return;
        long batchStart=PROFILE?System.nanoTime():0;
        packets = batch(packets);
        if(PROFILE)batchNanos+=System.nanoTime()-batchStart;
        SlotResources resources = slots[slot];
        int vertexBytes = 0, indexBytes = 0, maxQuadIndices = 0;
        for (Object[] packet : packets) {
            if (packet[0] == null) continue; // GUI depth clear
            int[] header = (int[]) packet[0];
            vertexBytes += geometryBytes(packet[2]);
            if (packet[3] != null) indexBytes += (((byte[]) packet[3]).length + 3) & ~3;
            else if (header[H_MODE] == MODE_QUADS) maxQuadIndices = Math.max(maxQuadIndices, header[H_VERTICES] / 4 * 6);
        }
        resources.vertices = ensure(resources.vertices, vertexBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        resources.indices = ensure(resources.indices, indexBytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        GpuBuffer previousUniforms = resources.uniforms;
        resources.uniforms = ensure(resources.uniforms, packets.size() * UNIFORM_STRIDE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        if (previousUniforms != resources.uniforms) resources.uniformBuffer = 0;
        ensureQuadIndices(maxQuadIndices);
        ensurePool(resources, pushDescriptors ? 1 : packets.size() + 1);
        if (!pushDescriptors && (resources.invalidated || resources.textureSets.size() + packets.size() > resources.poolSets)) {
            check(vkResetDescriptorPool(context.device, resources.pool, 0), "reset full entity descriptors");
            resources.textureSets.clear(); resources.uniformSet = resources.uniformBuffer = 0;
            resources.invalidated = false;
        }
        var handle = stack.mallocLong(1);
        if (resources.uniformSet == 0) {
            check(vkAllocateDescriptorSets(context.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(resources.pool)
                    .pSetLayouts(stack.longs(uniformLayout)), handle), "entity uniform set");
            resources.uniformSet = handle.get(0); descriptorAllocations++;
        }
        long uniformSet = resources.uniformSet;
        if (resources.uniformBuffer != resources.uniforms.buffer) {
            vkUpdateDescriptorSets(context.device, VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(uniformSet).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(resources.uniforms.buffer).offset(0).range(UNIFORM_STRIDE)), null);
            resources.uniformBuffer = resources.uniforms.buffer; uniformWrites++;
        }
        Map<Long, VkWriteDescriptorSet.Buffer> textureWrites = pushDescriptors ? new HashMap<>() : null;
        var vertexData = resources.vertices.mapped.duplicate().clear();
        var indexData = resources.indices.mapped.duplicate().clear();
        var uniformData = resources.uniforms.mapped.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        var buffers = stack.mallocLong(1); var offsets = stack.mallocLong(1); var dynamic = stack.mallocInt(1);
        long bound = 0, boundTexture = Long.MIN_VALUE;
        long effectiveLight = lightView != 0 ? lightView : direct ? textures.get(WHITE_TEXTURE).image.view : 0;
        long effectiveSampler = lightView != 0 ? lightSampler : samplers[0];
        for (int i = 0; i < packets.size(); i++) {
            Object[] packet = packets.get(i);
            if (packet[0] == null) {
                var clear = VkClearAttachment.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                clear.clearValue().depthStencil().set(1, 0);
                int[] rect = (int[]) packet[1];
                var area = VkClearRect.calloc(1, stack).baseArrayLayer(0).layerCount(1);
                area.rect().offset().set(rect[0], rect[1]); area.rect().extent().set(rect[2], rect[3]);
                vkCmdClearAttachments(command, clear, area);
                continue;
            }
            if (direct && packet.length > 4) {
                int[] rect = (int[]) packet[4];
                var scissor = VkRect2D.calloc(1, stack);
                scissor.offset().set(rect[0], rect[1]); scissor.extent().set(rect[2], rect[3]);
                vkCmdSetScissor(command, 0, scissor);
            }
            int[] header = (int[]) packet[0];
            Texture texture = textures.get(header[H_TEXTURE]);
            if (texture == null || effectiveLight == 0) { missingTextures++; continue; }
            Texture overlay = textures.getOrDefault(header[H_OVERLAY], texture);
            long pipeline = pipeline(header[H_FORMAT], header[H_FLAGS] | (glTarget ? 1 << 20 : 0));
            if (pipeline != bound) { vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline); bound = pipeline; }
            vkCmdSetDepthBias(command,header.length>10?Float.intBitsToFloat(header[10]):0,0,header.length>9?Float.intBitsToFloat(header[9]):0);
            long key = (long) header[H_TEXTURE] << 32 | (header[H_OVERLAY] & 0xffffffffL);
            BindingKey bindingKey = new BindingKey(texture, overlay, effectiveLight, effectiveSampler,
                    lightIdentity != null ? lightIdentity : direct ? textures.get(WHITE_TEXTURE) : effectiveLight);
            Long set = null;
            if (pushDescriptors) set = 0L; else set = resources.textureSets.get(bindingKey);
            if (pushDescriptors ? boundTexture != key : set == null) {
                if (!pushDescriptors) {
                    check(vkAllocateDescriptorSets(context.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(resources.pool)
                            .pSetLayouts(stack.longs(textureLayout)), handle), "entity texture set");
                    set = handle.get(0); descriptorAllocations++;
                }
                VkWriteDescriptorSet.Buffer writes = pushDescriptors ? textureWrites.get(key) : null;
                if (writes == null) {
                var images = VkDescriptorImageInfo.calloc(3, stack);
                images.get(0).sampler(samplers[texture.flags()]).imageView(texture.image().view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                images.get(1).sampler(samplers[0]).imageView(overlay.image().view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                images.get(2).sampler(effectiveSampler).imageView(effectiveLight).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                writes = VkWriteDescriptorSet.calloc(3, stack);
                for (int b = 0; b < 3; b++) writes.get(b).sType$Default().dstSet(set).dstBinding(b).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(VkDescriptorImageInfo.create(images.address(b), 1));
                if (pushDescriptors) textureWrites.put(key, writes);
                } else bindingHits++;
                if (pushDescriptors) { if (context.pushDescriptorCore) org.lwjgl.vulkan.VK14.vkCmdPushDescriptorSet(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, writes);
                    else org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, writes); descriptorPushes++; boundTexture = key; }
                else { vkUpdateDescriptorSets(context.device, writes, null); resources.textureSets.put(bindingKey, set); }
            } else bindingHits++;
            uniformData.position(i * UNIFORM_STRIDE / 4); uniformData.put((float[]) packet[1], 0, UNIFORM_FLOATS);
            if(glTarget)for(int column=0;column<4;column++){int at=i*UNIFORM_STRIDE/4+column*4+1;uniformData.put(at,-uniformData.get(at));}
            if (direct) uniformData.put(i * UNIFORM_STRIDE / 4 + 55, 3);
            if((header[H_FLAGS]&(1024|2048))!=0)uniformData.put(i*UNIFORM_STRIDE/4+55,3);
            dynamic.put(0, i * UNIFORM_STRIDE);
            if (pushDescriptors) vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 1, stack.longs(uniformSet), dynamic);
            else vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(set, uniformSet), dynamic);
            offsets.put(0, vertexData.position());
            if(packet[2] instanceof byte[] data) vertexData.put(data);
            else for(byte[] data:(byte[][])packet[2])vertexData.put(data);
            uploadedBytes+=geometryBytes(packet[2]);
            buffers.put(0, resources.vertices.buffer);
            vkCmdBindVertexBuffers(command, 0, buffers, offsets);
            if (packet[3] != null) {
                byte[] indices = (byte[]) packet[3];
                int at = indexData.position();
                indexData.put(indices); indexData.position((indexData.position() + 3) & ~3);
                vkCmdBindIndexBuffer(command, resources.indices.buffer, at, header[H_INDEX_SIZE] == 2 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexed(command, header[H_INDICES], 1, 0, 0, 0);
            } else if (header[H_MODE] == MODE_QUADS) {
                vkCmdBindIndexBuffer(command, quadIndices.buffer, 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexed(command, header[H_VERTICES] / 4 * 6, 1, 0, 0, 0);
            } else vkCmdDraw(command, header[H_VERTICES], 1, 0, 0);
            draws++; vertices += header[H_VERTICES];
        }
    }

    /** Merge only adjacent draws with identical uniforms/state; painter order and depth-clear boundaries remain intact. */
    private List<Object[]> batch(List<Object[]> source) {
        if (source.size() < 2) return source;
        List<Object[]> result = new ArrayList<>(source.size());
        for (int i = 0; i < source.size();) {
            Object[] first = source.get(i); int end = i + 1;
            int bytes = first[0] == null ? 0 : ((byte[]) first[2]).length;
            while (end < source.size() && compatible(first, source.get(end)) && bytes + ((byte[]) source.get(end)[2]).length <= 1 << 20) {
                bytes += ((byte[]) source.get(end)[2]).length; end++;
            }
            if (end == i + 1) result.add(first);
            else {
                Object[] combined = first.clone(); int[] header = ((int[]) first[0]).clone();
                // Keep the original byte arrays until their single copy into the mapped Vulkan buffer.
                byte[] geometry = legacyBatch ? new byte[bytes] : null;
                byte[][] chunks = legacyBatch ? null : new byte[end-i][]; int at = 0;
                header[H_VERTICES] = 0;
                for (int k = i; k < end; k++) {
                    Object[] packet = source.get(k); byte[] data = (byte[]) packet[2];
                    if(legacyBatch){System.arraycopy(data,0,geometry,at,data.length);at+=data.length;}
                    else chunks[k-i]=data;
                    header[H_VERTICES] += ((int[]) packet[0])[H_VERTICES];
                }
                if(legacyBatch)batchCopiedBytes+=bytes;
                combined[0] = header; combined[2] = legacyBatch?geometry:chunks; result.add(combined); mergedDraws += end - i - 1;
            }
            i = end;
        }
        return result;
    }
    private static boolean compatible(Object[] a, Object[] b) {
        if (a[0] == null || b[0] == null || a[3] != null || b[3] != null || a.length != b.length) return false;
        int[] x = (int[]) a[0], y = (int[]) b[0];
        if(x.length!=y.length)return false;
        for (int i = 0; i <= H_MODE; i++) if (i != H_VERTICES && x[i] != y[i]) return false;
        for(int i=HEADER;i<x.length;i++)if(x[i]!=y[i])return false; // Polygon offset is also a batch boundary.
        int unit = x[H_MODE] == MODE_QUADS ? 4 : 3;
        return x[H_VERTICES] % unit == 0 && y[H_VERTICES] % unit == 0 && Arrays.equals((float[]) a[1], (float[]) b[1])
                && (a.length <= 4 || Arrays.equals((int[]) a[4], (int[]) b[4]));
    }

    private GpuBuffer ensure(GpuBuffer current, int needed, int usage) {
        if (current != null && current.mapped.capacity() >= needed) return current;
        if (current != null) retire.accept(current::close);
        int capacity = 1 << 16;
        while (capacity < needed) capacity <<= 1;
        bufferGrowths++;return new GpuBuffer(context, capacity, usage);
    }
    private static int geometryBytes(Object geometry) {
        if(geometry instanceof byte[] data)return data.length;
        int bytes=0;for(byte[] data:(byte[][])geometry)bytes+=data.length;return bytes;
    }
    private void ensureQuadIndices(int count) {
        if (count == 0 || (quadIndices != null && quadIndices.mapped.capacity() >= count * 4)) return;
        int quads = 1 << 12;
        while (quads * 6 < count) quads <<= 1;
        GpuBuffer next = new GpuBuffer(context, quads * 6 * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        var out = next.mapped.asIntBuffer();
        // Same order as vanilla's sequential QUADS index generator.
        for (int q = 0; q < quads; q++) { int v = q * 4; out.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v); }
        if (quadIndices != null) { GpuBuffer old = quadIndices; retire.accept(old::close); }
        quadIndices = next;
    }
    private void ensurePool(SlotResources resources, int sets) {
        if (resources.pool != 0 && resources.poolSets >= sets) return;
        if (resources.pool != 0) { long old = resources.pool; retire.accept(() -> vkDestroyDescriptorPool(context.device, old, null)); }
        int capacity = 256;
        while (capacity < sets) capacity <<= 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(capacity * 3);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1);
            var handle = stack.mallocLong(1);
            check(vkCreateDescriptorPool(context.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(capacity + 1).pPoolSizes(sizes), null, handle), "entity descriptor pool");
            resources.pool = handle.get(0); resources.poolSets = capacity; resources.uniformSet = resources.uniformBuffer = 0; resources.textureSets.clear(); resources.invalidated = false;
        }
    }

    private long pipeline(int format, int flags) {
        int key = format | flags << 4;
        Long existing = pipelines.get(key);
        if (existing != null) return existing;
        long vert = 0, frag = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vert = ShaderCompiler.module(context, VERTEX_SHADERS[format], shaderc_glsl_vertex_shader);
            frag = ShaderCompiler.module(context, "/shaders/entity.frag", shaderc_glsl_fragment_shader);
            var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
            boolean text = format == FORMAT_TEXT;
            var attributes = VkVertexInputAttributeDescription.calloc(format == FORMAT_ENTITY ? 6 : format == FORMAT_POSITION ? 1
                    : format == FORMAT_POSITION_COLOR || format == FORMAT_POSITION_TEX ? 2 : format >= FORMAT_POSITION_TEX_COLOR ? 3 : 4, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            if (format >= FORMAT_POSITION) {
                // Position always first; POSITION_TEX* put UV0 at 12 and Color after it, POSITION_COLOR has Color at 12.
                if (format == FORMAT_POSITION_COLOR) attributes.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                if (format == FORMAT_POSITION_TEX) attributes.get(1).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(12);
                if (format == FORMAT_POSITION_TEX_COLOR || format == FORMAT_CLOUDS) {
                    attributes.get(1).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(12);
                    attributes.get(2).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(20);
                }
                if (format == FORMAT_POSITION_COLOR_TEX) {
                    attributes.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                    attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(16);
                }
            } else {
                // PARTICLE orders UV0 before Color: Position, UV0, Color, UV2.
                attributes.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(format == FORMAT_PARTICLE ? 20 : 12);
                attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(format == FORMAT_PARTICLE ? 12 : 16);
                if (text || format == FORMAT_PARTICLE) attributes.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_SINT).offset(24);
                else {
                    attributes.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_SINT).offset(24);
                    attributes.get(4).location(4).binding(0).format(VK_FORMAT_R16G16_SINT).offset(28);
                    // Normal (3 bytes) + padding read as one SNORM vec4; the shader uses xyz.
                    attributes.get(5).location(5).binding(0).format(VK_FORMAT_R8G8B8A8_SNORM).offset(32);
                }
            }
            var input = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                    .pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(STRIDES[format]).inputRate(VK_VERTEX_INPUT_RATE_VERTEX))
                    .pVertexAttributeDescriptions(attributes);
            var attachment = VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask((flags & NO_COLOR) != 0 ? 0
                    : VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            if ((flags & BLEND_ADD) != 0) attachment.blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE);
            if ((flags & BLEND) != 0) attachment.blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
            if (direct) {
                if ((flags & (BLEND | BLEND_ADD)) != 0) attachment.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA);
                if ((flags & BLEND_INVERT) != 0) attachment.blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO);
                if ((flags & BLEND_MULTIPLY) != 0) attachment.blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_ZERO).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO);
            }
            if((flags&1024)!=0)attachment.blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_COLOR).dstColorBlendFactor(VK_BLEND_FACTOR_ONE).srcAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE);
            if((flags&2048)!=0)attachment.blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_DST_COLOR).dstColorBlendFactor(VK_BLEND_FACTOR_SRC_COLOR).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO);
            // Vanilla front faces are CCW on screen; the shaders' y flip keeps them CCW in Vulkan framebuffer space.
            var raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode((flags & CULL) != 0 ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE).frontFace((flags & (1<<20))!=0?VK_FRONT_FACE_CLOCKWISE:VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable((flags&4096)!=0).lineWidth(1);
            var info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default().pColorAttachmentFormats(stack.ints(colorFormat))
                            .depthAttachmentFormat(VK_FORMAT_D32_SFLOAT).address())
                    .pStages(stages).pVertexInputState(input)
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(raster)
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable((flags & DEPTH_TEST) != 0)
                            .depthWriteEnable((flags & DEPTH_WRITE) != 0).depthCompareOp((flags&512)!=0?VK_COMPARE_OP_EQUAL:(flags & DEPTH_LESS) != 0 ? VK_COMPARE_OP_LESS : VK_COMPARE_OP_LESS_OR_EQUAL))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(attachment))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,VK_DYNAMIC_STATE_DEPTH_BIAS)))
                    .layout(layout).basePipelineIndex(-1);
            var handle = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(context.device, context.pipelineCache, info, null, handle), "entity pipeline");
            pipelines.put(key, handle.get(0));
            return handle.get(0);
        } finally {
            if (frag != 0) vkDestroyShaderModule(context.device, frag, null);
            if (vert != 0) vkDestroyShaderModule(context.device, vert, null);
        }
    }

    @Override public void close() {
        context.uploads.drain();
        textures.values().forEach(Texture::close); textures.clear();
        for (long pipeline : pipelines.values()) vkDestroyPipeline(context.device, pipeline, null);
        pipelines.clear();
        for (SlotResources resources : slots) {
            if (resources.vertices != null) resources.vertices.close();
            if (resources.indices != null) resources.indices.close();
            if (resources.uniforms != null) resources.uniforms.close();
            if (resources.pool != 0) vkDestroyDescriptorPool(context.device, resources.pool, null);
            resources.vertices = resources.indices = resources.uniforms = null; resources.pool = 0;
        }
        if (quadIndices != null) { quadIndices.close(); quadIndices = null; }
        for (int i = 0; i < samplers.length; i++) if (samplers[i] != 0) { vkDestroySampler(context.device, samplers[i], null); samplers[i] = 0; }
        if (layout != 0) { vkDestroyPipelineLayout(context.device, layout, null); layout = 0; }
        if (textureLayout != 0) { vkDestroyDescriptorSetLayout(context.device, textureLayout, null); textureLayout = 0; }
        if (uniformLayout != 0) { vkDestroyDescriptorSetLayout(context.device, uniformLayout, null); uniformLayout = 0; }
    }
}
