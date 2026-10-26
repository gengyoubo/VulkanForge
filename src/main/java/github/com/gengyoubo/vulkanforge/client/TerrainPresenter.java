package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
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
    void drawTranslucent(int texture, int depthTexture) { draw(texture, depthTexture, true, true); }
    private void draw(int texture, int depthTexture, boolean translucent) { draw(texture, depthTexture, translucent, true); }
    private void draw(int texture, int depthTexture, boolean translucent, boolean depthTest) {
        if(depthTexture>0 && NativeMainWindow.nativeComposite(translucent?1:0,new Object[0]))return;
        if (quad == null) {
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.vertex(-1, -1, 0).uv(0, 1).endVertex();
            builder.vertex(1, -1, 0).uv(1, 1).endVertex();
            builder.vertex(1, 1, 0).uv(1, 0).endVertex();
            builder.vertex(-1, 1, 0).uv(0, 0).endVertex();
            quad = new VertexBuffer(VertexBuffer.Usage.STATIC);
            quad.bind(); quad.upload(builder.end()); VertexBuffer.unbind();
        }
        Object blendCache = ShaderBlendState.save();
        boolean depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE), blend = glIsEnabled(GL_BLEND);
        boolean writeDepth = glGetBoolean(GL_DEPTH_WRITEMASK);
        int sampler = RenderSystem.getShaderTexture(0), active = glGetInteger(GL_ACTIVE_TEXTURE);
        int depthSampler = RenderSystem.getShaderTexture(1), depthFunc = glGetInteger(GL_DEPTH_FUNC);
        var shader = translucent ? translucentShader : depthShader;
        boolean transferDepth = depthTexture > 0 && shader != null;
        int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
        int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        float[] color = RenderSystem.getShaderColor().clone();
        // A GUI split can composite while a scissored list, text highlight (logic op) or partial color mask is active.
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST), logicOp = glIsEnabled(GL_COLOR_LOGIC_OP), offset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
        byte[] mask = new byte[4];
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) { var bytes = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, bytes); bytes.get(mask); }
        try {
            GlStateManager._disableScissorTest(); GlStateManager._disableColorLogicOp(); GlStateManager._disablePolygonOffset();
            GlStateManager._colorMask(true, true, true, true);
            if (transferDepth && depthTest) { RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_LEQUAL); }
            else RenderSystem.disableDepthTest();
            RenderSystem.depthMask(transferDepth && depthTest); RenderSystem.disableCull();
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
            GlStateManager._blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
            RenderSystem.depthMask(writeDepth); RenderSystem.activeTexture(active);
            if (scissor) GlStateManager._enableScissorTest();
            if (logicOp) GlStateManager._enableColorLogicOp();
            if (offset) GlStateManager._enablePolygonOffset();
            GlStateManager._colorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
            ShaderBlendState.restore(blendCache);
        }
    }

    @Override public void close() { if (quad != null) { quad.close(); quad = null; } }
}
