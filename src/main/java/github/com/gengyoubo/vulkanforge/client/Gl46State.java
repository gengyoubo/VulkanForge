package github.com.gengyoubo.vulkanforge.client;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Raw GL state the OpenGL 4.6 passes touch. Restoring the exact previous values keeps GlStateManager's, ShaderInstance's and
 * BufferUploader's caches truthful without going through them. Texture units 0-2 are vanilla's Sampler0-2.
 */
final class Gl46State implements AutoCloseable {
    private final int program = glGetInteger(GL_CURRENT_PROGRAM), vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int indirect = glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING), parameter = glGetInteger(GL_PARAMETER_BUFFER_BINDING);
    private final int active = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int[] textures = new int[3];
    private final boolean depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE), blend = glIsEnabled(GL_BLEND);
    private final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int depthFunc = glGetInteger(GL_DEPTH_FUNC);
    private final int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
    private final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);

    Gl46State() {
        for (int unit = 0; unit < textures.length; unit++) { glActiveTexture(GL_TEXTURE0 + unit); textures[unit] = glGetInteger(GL_TEXTURE_BINDING_2D); }
        glActiveTexture(active);
    }

    @Override public void close() {
        glUseProgram(program); glBindVertexArray(vertexArray);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect); glBindBuffer(GL_PARAMETER_BUFFER, parameter);
        for (int unit = 0; unit < textures.length; unit++) {
            glBindSampler(unit, 0);
            glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D, textures[unit]);
        }
        glActiveTexture(active);
        toggle(GL_DEPTH_TEST, depth); toggle(GL_CULL_FACE, cull); toggle(GL_BLEND, blend);
        glDepthMask(depthMask); glDepthFunc(depthFunc);
        glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    static void toggle(int capability, boolean enabled) { if (enabled) glEnable(capability); else glDisable(capability); }
}
