package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING;

/** Holds the GUI until present. Original buffers survive until commit so unsupported draws can replay the complete GUI on GL. */
public final class GuiDraws {
    private record Draw(BufferBuilder.RenderedBuffer original, Object[] packet, State state) { }
    private static final List<Draw> pending = new ArrayList<>();
    private static boolean active, fallback;
    private static long draws, frames, fallbacks;
    private static final Map<String, Long> reasons = new TreeMap<>();
    static Map<String, Long> fallbackReasons() { return Collections.unmodifiableMap(reasons); }
    private static final int MAX_DRAWS = 4096;
    private GuiDraws() { }
    static boolean active() { return active; }
    static long[] stats() { return new long[]{draws, frames, fallbacks}; }

    /** GameRenderer's first depth clear is the boundary between held items and all GUI, including menus, overlays and toasts. */
    public static void clear(int mask, boolean onMac) {
        if (!RenderSystem.isOnRenderThread() || !NativeMainWindow.active()) { RenderSystem.clear(mask, onMac); return; }
        if (!active && (mask & GL_DEPTH_BUFFER_BIT) != 0 && VulkanPreview.guiEnabled()) {
            VulkanPreview.beforeSwap();
            EntityTextures.poll();
            active = true; fallback = false;
        } else if (active && (mask & GL_DEPTH_BUFFER_BIT) != 0 && !fallback) {
            // Capture the second clear (HUD -> screen), preserving item/icon depth ordering.
            if (glIsEnabled(GL_SCISSOR_TEST) || !glGetBoolean(GL_DEPTH_WRITEMASK) || mask != GL_DEPTH_BUFFER_BIT) fail();
            else pending.add(new Draw(null, new Object[]{null, rect(false)}, null));
        }
        RenderSystem.clear(mask, onMac);
    }

    static void draw(BufferBuilder.RenderedBuffer original) {
        if (fallback) { BufferUploader.drawWithShader(original); return; }
        var target = Minecraft.getInstance().getMainRenderTarget();
        int[] viewport = new int[4]; glGetIntegerv(GL_VIEWPORT, viewport);
        Object[] packet = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == target.frameBufferId && viewport[0] == 0 && viewport[1] == 0
                && viewport[2] == target.viewWidth && viewport[3] == target.viewHeight && pending.size() < MAX_DRAWS ? EntityDraws.guiPacket(original) : null;
        if (packet == null) {
            String shader = RenderSystem.getShader() == null ? "null" : RenderSystem.getShader().getName();
            reasons.merge(shader + (glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != target.frameBufferId ? ":framebuffer" : ":shader/state/texture"), 1L, Long::sum);
            fail(); BufferUploader.drawWithShader(original); return; }
        packet = Arrays.copyOf(packet, 5); packet[4] = rect(glIsEnabled(GL_SCISSOR_TEST));
        pending.add(new Draw(original, packet, new State()));
    }

    private static int[] rect(boolean clipped) {
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (!clipped) return new int[]{0, 0, target.viewWidth, target.viewHeight};
        int[] box = new int[4]; glGetIntegerv(GL_SCISSOR_BOX, box);
        int x = Math.max(0, box[0]), y = Math.max(0, target.viewHeight - box[1] - box[3]);
        int right = Math.min(target.viewWidth, box[0] + box[2]), bottom = Math.min(target.viewHeight, target.viewHeight - box[1]);
        return new int[]{x, y, Math.max(0, right - x), Math.max(0, bottom - y)};
    }

    /** A whole-frame GL fallback keeps custom shaders/logic operations in the same order, with their original depth. */
    private static void fail() {
        fallback = true; fallbacks++;
        State restore = new State();
        BufferUploader.reset();
        Map<VertexFormat, VertexBuffer> buffers = new IdentityHashMap<>();
        try {
            for (Draw draw : pending) {
                if (draw.original == null) { GlStateManager._disableScissorTest(); RenderSystem.depthMask(true); RenderSystem.clear(GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX); continue; }
                draw.state.restore();
                VertexBuffer buffer = buffers.computeIfAbsent(draw.original.drawState().format(), key -> new VertexBuffer(VertexBuffer.Usage.DYNAMIC));
                buffer.bind(); buffer.upload(draw.original);
                float[] uniforms = (float[]) draw.packet[1];
                buffer.drawWithShader(new Matrix4f().set(uniforms, 16), new Matrix4f().set(uniforms, 0), draw.state.shader);
            }
        } finally {
            pending.clear();
            VertexBuffer.unbind(); buffers.values().forEach(VertexBuffer::close); BufferUploader.reset();
            restore.restore();
        }
    }

    /** Called before the GL framebuffer is copied. Successful GUI frames never draw into a GL target. */
    static Object[] take() {
        Object[] packets = pending.stream().map(Draw::packet).toArray();
        if (packets.length > 0) { draws += pending.stream().filter(draw -> draw.original != null).count(); frames++; }
        for (Draw draw : pending) if (draw.original != null) draw.original.release();
        pending.clear(); active = fallback = false;
        return packets;
    }

    static void reset() {
        for (Draw draw : pending) if (draw.original != null) draw.original.release();
        pending.clear(); active = fallback = false;
    }

    /** Only supported GUI states enter pending; preserve the current unsupported draw's extra states too when replaying. */
    private static final class State {
        final Object blendCache = ShaderBlendState.save();
        final ShaderInstance shader = RenderSystem.getShader();
        final float[] color = RenderSystem.getShaderColor().clone(), fogColor = RenderSystem.getShaderFogColor().clone();
        final float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        final com.mojang.blaze3d.shaders.FogShape fogShape = RenderSystem.getShaderFogShape();
        final boolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE),
                scissor = glIsEnabled(GL_SCISSOR_TEST), logic = glIsEnabled(GL_COLOR_LOGIC_OP), offset = glIsEnabled(GL_POLYGON_OFFSET_FILL), write = glGetBoolean(GL_DEPTH_WRITEMASK);
        final int src = glGetInteger(GL_BLEND_SRC_RGB), dst = glGetInteger(GL_BLEND_DST_RGB), srcA = glGetInteger(GL_BLEND_SRC_ALPHA), dstA = glGetInteger(GL_BLEND_DST_ALPHA),
                equation = glGetInteger(org.lwjgl.opengl.GL20.GL_BLEND_EQUATION_RGB), depthFunc = glGetInteger(GL_DEPTH_FUNC);
        final int[] textures = new int[12], box = new int[4];
        final byte[] mask = new byte[4];
        final Vector3f light0, light1;
        State() {
            for (int i = 0; i < textures.length; i++) textures[i] = RenderSystem.getShaderTexture(i);
            glGetIntegerv(GL_SCISSOR_BOX, box);
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) { var bytes = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, bytes); bytes.get(mask); }
            if (shader != null) RenderSystem.setupShaderLights(shader);
            light0 = shader == null || shader.LIGHT0_DIRECTION == null ? null : new Vector3f(shader.LIGHT0_DIRECTION.getFloatBuffer().get(0), shader.LIGHT0_DIRECTION.getFloatBuffer().get(1), shader.LIGHT0_DIRECTION.getFloatBuffer().get(2));
            light1 = shader == null || shader.LIGHT1_DIRECTION == null ? null : new Vector3f(shader.LIGHT1_DIRECTION.getFloatBuffer().get(0), shader.LIGHT1_DIRECTION.getFloatBuffer().get(1), shader.LIGHT1_DIRECTION.getFloatBuffer().get(2));
        }
        void restore() {
            ShaderBlendState.restore(blendCache);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShaderFogStart(fogStart); RenderSystem.setShaderFogEnd(fogEnd); RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]); RenderSystem.setShaderFogShape(fogShape);
            if (light0 != null && light1 != null) RenderSystem.setShaderLights(light0, light1);
            for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.blendEquation(equation); GlStateManager._blendFuncSeparate(src, dst, srcA, dstA);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(depthFunc); RenderSystem.depthMask(write);
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            GlStateManager._scissorBox(box[0], box[1], box[2], box[3]);
            if (scissor) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
            if (logic) GlStateManager._enableColorLogicOp(); else GlStateManager._disableColorLogicOp();
            if (offset) GlStateManager._enablePolygonOffset(); else GlStateManager._disablePolygonOffset();
            GlStateManager._colorMask(mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
        }
    }
}
