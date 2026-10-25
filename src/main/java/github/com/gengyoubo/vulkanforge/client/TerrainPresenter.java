package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL14.*;

/** Presents Vulkan solid/cutout color before vanilla entities, and Vulkan translucency after them, both with Vulkan depth. */
final class TerrainPresenter implements AutoCloseable {
    private VertexBuffer quad;
    private final BufferBuilder builder = new BufferBuilder(128);
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static net.minecraft.client.renderer.ShaderInstance depthShader, translucentShader;
    static void shader(net.minecraft.client.renderer.ShaderInstance shader) { depthShader = shader; }
    static void translucentShader(net.minecraft.client.renderer.ShaderInstance shader) { translucentShader = shader; }
    static boolean depthReady() { return depthShader != null; }
    static boolean translucentReady() { return translucentShader != null; }

    void draw(FrameTexture texture) {
        draw(texture.getId());
    }
    void draw(int texture) {
        draw(texture, 0);
    }
    void draw(int texture, int depthTexture) { draw(texture, depthTexture, false); }
    /** Premultiplied color; GL depth test rejects pixels whose nearest translucent surface is behind entities. */
    void drawTranslucent(int texture, int depthTexture) { draw(texture, depthTexture, true); }
    private void draw(int texture, int depthTexture, boolean translucent) {
        if (quad == null) {
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.vertex(-1, -1, 0).uv(0, 1).endVertex();
            builder.vertex(1, -1, 0).uv(1, 1).endVertex();
            builder.vertex(1, 1, 0).uv(1, 0).endVertex();
            builder.vertex(-1, 1, 0).uv(0, 0).endVertex();
            quad = new VertexBuffer(VertexBuffer.Usage.STATIC);
            quad.bind(); quad.upload(builder.end()); VertexBuffer.unbind();
        }
        boolean depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE), blend = glIsEnabled(GL_BLEND);
        boolean writeDepth = glGetBoolean(GL_DEPTH_WRITEMASK);
        int sampler = RenderSystem.getShaderTexture(0), active = glGetInteger(GL_ACTIVE_TEXTURE);
        int depthSampler = RenderSystem.getShaderTexture(1), depthFunc = glGetInteger(GL_DEPTH_FUNC);
        var shader = translucent ? translucentShader : depthShader;
        boolean transferDepth = depthTexture > 0 && shader != null;
        int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
        int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        float[] color = RenderSystem.getShaderColor().clone();
        try {
            if (transferDepth) { RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_LEQUAL); }
            else RenderSystem.disableDepthTest();
            RenderSystem.depthMask(transferDepth); RenderSystem.disableCull();
            RenderSystem.enableBlend();
            if (translucent) RenderSystem.blendFuncSeparate(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            else RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1); RenderSystem.setShaderTexture(0, texture);
            if (transferDepth) RenderSystem.setShaderTexture(1, depthTexture);
            if (translucent && !transferDepth) return;
            quad.bind(); quad.drawWithShader(IDENTITY, IDENTITY, transferDepth ? shader : GameRenderer.getPositionTexShader());
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderTexture(0, sampler); RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShaderTexture(1, depthSampler); RenderSystem.depthFunc(depthFunc);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            com.mojang.blaze3d.platform.GlStateManager._blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
            RenderSystem.depthMask(writeDepth); RenderSystem.activeTexture(active);
        }
    }

    @Override public void close() { if (quad != null) { quad.close(); quad = null; } }
}
