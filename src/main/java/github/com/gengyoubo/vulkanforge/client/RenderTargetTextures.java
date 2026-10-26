package github.com.gengyoubo.vulkanforge.client;

/** Implemented on RenderTarget by the coremod, so texture ownership can change without reflective field names. */
public interface RenderTargetTextures {
    void vulkanforge$setTextures(int color, int depth);
}
