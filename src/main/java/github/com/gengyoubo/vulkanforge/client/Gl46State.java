package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.vertex.BufferUploader;
import static org.lwjgl.opengl.GL46C.*;

/**
 * GL state the OpenGL 4.6 passes touch, captured from vanilla's own caches (GlStateCache, no glGet when trusted) and written
 * back with plain set calls, so GlStateManager's and ShaderInstance's caches stay truthful. The vertex array is not restored:
 * BufferUploader is invalidated, and vanilla binds a VAO before each of its draws. Texture units 0-2 are Sampler0-2; the
 * active unit is never changed (DSA binds). Indirect/parameter/SSBO/UBO bindings are left as they are: vanilla uses none.
 */
final class Gl46State implements AutoCloseable {
    private final GlStateCache.Snapshot saved = GlStateCache.snapshot();

    @Override public void close() {
        glUseProgram(saved.program());
        glBindVertexArray(0); BufferUploader.invalidate();
        for (int unit = 0; unit < 3; unit++) glBindSampler(unit, 0);
        glBindTextureUnit(0, saved.texture0()); glBindTextureUnit(1, saved.texture1()); glBindTextureUnit(2, saved.texture2());
        toggle(GL_DEPTH_TEST, saved.depthTest()); toggle(GL_CULL_FACE, saved.cull()); toggle(GL_BLEND, saved.blend());
        glDepthMask(saved.depthMask()); glDepthFunc(saved.depthFunc());
        glBlendFuncSeparate(saved.srcRGB(), saved.dstRGB(), saved.srcAlpha(), saved.dstAlpha());
    }

    static void toggle(int capability, boolean enabled) { if (enabled) glEnable(capability); else glDisable(capability); }
}
