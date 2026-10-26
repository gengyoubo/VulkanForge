package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** coremods/block_entities.js: BlockEntityRenderDispatcher.setupAndRender's renderer call. Cached meshes skip the renderer. */
public final class BlockEntityMeshes {
    private static long calls, nanos;
    private BlockEntityMeshes() { }

    public static void render(BlockEntityRenderer<BlockEntity> renderer, BlockEntity entity, float partial, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        long start = System.nanoTime();
        if (!VulkanPreview.gl46BlockEntity(renderer, entity, partial, pose, light, overlay)) renderer.render(entity, partial, pose, buffers, light, overlay);
        nanos += System.nanoTime() - start; calls++;
    }

    /** {calls, total ns}: renderer calls with or without the cache, including vanilla's own vertex generation when it renders. */
    static long[] stats() { return new long[]{calls, nanos}; }
}
