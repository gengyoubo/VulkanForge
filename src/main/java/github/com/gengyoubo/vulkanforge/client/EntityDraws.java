package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import java.nio.ByteBuffer;
import java.util.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;

/**
 * P7/P8: RenderType.end() upload hook (coremods/entities.js). Between the Vulkan terrain composite (AFTER_CUTOUT_BLOCKS) and
 * AFTER_BLOCK_ENTITIES, supported entity/block-entity draws are copied for the Vulkan entity pass instead of being drawn by GL.
 * Anything else - other shaders, formats, blend/depth states or not-yet-mirrored textures - is drawn by GL exactly as before.
 * Packet layout matches the backend's EntityPass.
 */
public final class EntityDraws {
    // EntityPass header indices / constants (the lab classes live in an isolated class loader).
    private static final int H_FORMAT = 0, H_VARIANT = 1, H_FLAGS = 2, H_TEXTURE = 3, H_OVERLAY = 4, H_VERTICES = 5, H_MODE = 6, H_INDICES = 7, H_INDEX_SIZE = 8, HEADER = 9;
    private static final int FORMAT_ENTITY = 0, FORMAT_TEXT = 1, MODE_QUADS = 0, MODE_TRIANGLES = 1;
    private static final int BLEND = 1, DEPTH_TEST = 2, DEPTH_WRITE = 4, CULL = 8;
    /** Vanilla shader name -> fragment variant (see entity.frag). */
    private static final Map<String, Integer> VARIANTS = Map.ofEntries(
            Map.entry("rendertype_entity_cutout", 0), Map.entry("rendertype_entity_cutout_no_cull", 0), Map.entry("rendertype_entity_cutout_no_cull_z_offset", 0),
            Map.entry("rendertype_entity_translucent", 0), Map.entry("rendertype_entity_smooth_cutout", 0), Map.entry("rendertype_entity_solid", 1),
            Map.entry("rendertype_entity_translucent_cull", 2), Map.entry("rendertype_armor_cutout_no_cull", 2), Map.entry("rendertype_entity_no_outline", 3),
            Map.entry("rendertype_text", 2));
    private static boolean open, disabled;
    private static int lightmap;
    private static final List<Object[]> packets = new ArrayList<>();
    private static long captured, unsupported, stateFallbacks, textureFallbacks, composites;
    private static final Map<String, Long> fallbackShaders = new TreeMap<>();
    private static final float[] matrix3 = new float[9];
    private EntityDraws() { }

    public static void draw(RenderType type, BufferBuilder.RenderedBuffer rendered) {
        if (!open || !RenderSystem.isOnRenderThread() || !capture(rendered)) BufferUploader.drawWithShader(rendered);
    }

    /** Opened after this frame's Vulkan terrain composite; {@code lightmapTexture} is the lightmap WorldCapture mirrors. */
    static void open(int lightmapTexture) { packets.clear(); lightmap = lightmapTexture; open = !disabled; }
    /** AFTER_BLOCK_ENTITIES: replay in Vulkan, then composite over GL with depth. */
    static void flush(TerrainPresenter presenter) {
        if (!open) return;
        open = false;
        if (packets.isEmpty()) return;
        int[] textures = NativeMainWindow.renderEntities(packets.toArray());
        packets.clear();
        if (textures == null) return;
        presenter.drawTranslucent(textures[0], textures[1]);
        composites++;
    }
    static void cancel() { open = false; packets.clear(); }
    static void disable() { cancel(); disabled = true; }
    /** Preview restart: allow capture again. */
    static void reset() { cancel(); disabled = false; }
    static long[] stats() { return new long[]{captured, unsupported, stateFallbacks, textureFallbacks, composites}; }
    static Map<String, Long> fallbackShaders() { return Collections.unmodifiableMap(fallbackShaders); }

    private static boolean capture(BufferBuilder.RenderedBuffer rendered) {
        var shader = RenderSystem.getShader();
        Integer variant = shader == null ? null : VARIANTS.get(shader.getName());
        BufferBuilder.DrawState state = rendered.drawState();
        VertexFormat format = state.format();
        boolean text = format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;
        if (variant == null || state.indexOnly() || (format != DefaultVertexFormat.NEW_ENTITY && !text) || text != "rendertype_text".equals(shader.getName())
                || (state.mode() != VertexFormat.Mode.QUADS && state.mode() != VertexFormat.Mode.TRIANGLES)) {
            unsupported++;
            if (shader != null) fallbackShaders.merge(shader.getName(), 1L, Long::sum);
            return false;
        }
        int flags = glState();
        if (flags < 0 || RenderSystem.getShaderTexture(2) != lightmap) { stateFallbacks++; return false; }
        int texture = RenderSystem.getShaderTexture(0), overlay = text ? texture : RenderSystem.getShaderTexture(1);
        // Both checks run so a missing overlay is requested in the same frame as a missing main texture.
        boolean mirrored = EntityTextures.ready(texture);
        mirrored &= EntityTextures.ready(overlay);
        if (!mirrored) { textureFallbacks++; return false; }
        int[] header = new int[HEADER];
        header[H_FORMAT] = text ? FORMAT_TEXT : FORMAT_ENTITY; header[H_VARIANT] = variant; header[H_FLAGS] = flags;
        header[H_TEXTURE] = texture; header[H_OVERLAY] = overlay; header[H_VERTICES] = state.vertexCount();
        header[H_MODE] = state.mode() == VertexFormat.Mode.QUADS ? MODE_QUADS : MODE_TRIANGLES;
        byte[] indices = null;
        if (!state.sequentialIndex()) {
            indices = copy(rendered.indexBuffer());
            header[H_INDICES] = state.indexCount(); header[H_INDEX_SIZE] = state.indexType().bytes;
        }
        float[] uniforms = new float[64];
        RenderSystem.getProjectionMatrix().get(uniforms, 0);
        RenderSystem.getModelViewMatrix().get(uniforms, 16);
        RenderSystem.getInverseViewRotationMatrix().get(matrix3, 0);
        for (int column = 0; column < 3; column++) System.arraycopy(matrix3, column * 3, uniforms, 32 + column * 4, 3);
        System.arraycopy(RenderSystem.getShaderColor(), 0, uniforms, 44, 4);
        RenderSystem.setupShaderLights(shader);
        if (shader.LIGHT0_DIRECTION != null) shader.LIGHT0_DIRECTION.getFloatBuffer().get(0, uniforms, 48, 3);
        if (shader.LIGHT1_DIRECTION != null) shader.LIGHT1_DIRECTION.getFloatBuffer().get(0, uniforms, 52, 3);
        uniforms[55] = (flags & BLEND) == 0 ? 1 : 0;
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, uniforms, 56, 4);
        uniforms[60] = RenderSystem.getShaderFogStart(); uniforms[61] = RenderSystem.getShaderFogEnd();
        uniforms[62] = RenderSystem.getShaderFogShape().getIndex(); uniforms[63] = variant;
        packets.add(new Object[]{header, uniforms, copy(rendered.vertexBuffer()), indices});
        rendered.release();
        captured++;
        return true;
    }

    /** Pipeline flags for the current GL state, or -1 when Vulkan cannot reproduce it. */
    private static int glState() {
        int flags = 0;
        if (glIsEnabled(GL_BLEND)) {
            int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
            int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
            // TRANSLUCENT_TRANSPARENCY and the default blend func both become premultiplied "over".
            if (srcRGB != GL_SRC_ALPHA || dstRGB != GL_ONE_MINUS_SRC_ALPHA || (srcAlpha != GL_ONE && srcAlpha != GL_SRC_ALPHA) || dstAlpha != GL_ONE_MINUS_SRC_ALPHA) return -1;
            flags |= BLEND;
        }
        if (glIsEnabled(GL_DEPTH_TEST)) {
            int func = glGetInteger(GL_DEPTH_FUNC);
            if (func != GL_LEQUAL && func != GL_LESS) return -1;
            flags |= DEPTH_TEST;
        }
        if (glGetBoolean(GL_DEPTH_WRITEMASK)) flags |= DEPTH_WRITE;
        if (glIsEnabled(GL_CULL_FACE)) {
            if (glGetInteger(GL_CULL_FACE_MODE) != GL_BACK || glGetInteger(GL_FRONT_FACE) != GL_CCW) return -1;
            flags |= CULL;
        }
        if (glIsEnabled(GL_POLYGON_OFFSET_FILL) || glIsEnabled(GL_SCISSOR_TEST) || glIsEnabled(GL_COLOR_LOGIC_OP)) return -1;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) if (mask.get(i) == 0) return -1;
        }
        return flags;
    }

    private static byte[] copy(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }
}
