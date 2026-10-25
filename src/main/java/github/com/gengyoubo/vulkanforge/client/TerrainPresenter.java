package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL14.*;

/** Presents Vulkan solid/cutout color before vanilla entities, translucency, particles and hand. */
final class TerrainPresenter implements AutoCloseable {
    private VertexBuffer quad;
    private final BufferBuilder builder = new BufferBuilder(128);
    private static final Matrix4f IDENTITY = new Matrix4f();

    void draw(FrameTexture texture) {
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
        int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
        int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        float[] color = RenderSystem.getShaderColor().clone();
        try {
            RenderSystem.disableDepthTest(); RenderSystem.depthMask(false); RenderSystem.disableCull();
            RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1); RenderSystem.setShaderTexture(0, texture.getId());
            quad.bind(); quad.drawWithShader(IDENTITY, IDENTITY, GameRenderer.getPositionTexShader());
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderTexture(0, sampler); RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            com.mojang.blaze3d.platform.GlStateManager._blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
            RenderSystem.depthMask(writeDepth); RenderSystem.activeTexture(active);
        }
    }

    @Override public void close() { if (quad != null) { quad.close(); quad = null; } }
}
