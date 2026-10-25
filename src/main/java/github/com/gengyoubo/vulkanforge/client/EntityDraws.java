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
 * P7-P9 upload hooks (coremods/entities.js): RenderType.end() and Tesselator.end(). Supported draws inside a capture window are
 * copied for a Vulkan overlay pass instead of being drawn by GL; each window is replayed and composited (with depth) when it closes:
 * <ul><li>ENTITIES: Vulkan terrain composite (AFTER_CUTOUT_BLOCKS) to AFTER_BLOCK_ENTITIES - entities and block entities (P7/P8);</li>
 * <li>PARTICLES: AFTER_TRIPWIRE_BLOCKS to AFTER_PARTICLES - ParticleEngine.render (P9);</li>
 * <li>WEATHER: AFTER_PARTICLES to AFTER_WEATHER - clouds (VertexBuffer, not captured) and rain/snow (P9).</li></ul>
 * Anything else - other shaders, formats, blend/depth states or not-yet-mirrored textures - is drawn by GL exactly as before.
 * Packet layout matches the backend's EntityPass.
 */
public final class EntityDraws {
    // EntityPass header indices / constants (the lab classes live in an isolated class loader).
    private static final int H_FORMAT = 0, H_VARIANT = 1, H_FLAGS = 2, H_TEXTURE = 3, H_OVERLAY = 4, H_VERTICES = 5, H_MODE = 6, H_INDICES = 7, H_INDEX_SIZE = 8, HEADER = 9;
    private static final int FORMAT_ENTITY = 0, FORMAT_TEXT = 1, FORMAT_PARTICLE = 2, MODE_QUADS = 0, MODE_TRIANGLES = 1;
    private static final int BLEND = 1, DEPTH_TEST = 2, DEPTH_WRITE = 4, CULL = 8;
    /** Vanilla shader name -> {fragment variant (see entity.frag), vertex format}; the draw's VertexFormat must match. */
    private static final Map<String, int[]> SHADERS = Map.ofEntries(
            Map.entry("rendertype_entity_cutout", new int[]{0, FORMAT_ENTITY}), Map.entry("rendertype_entity_cutout_no_cull", new int[]{0, FORMAT_ENTITY}),
            Map.entry("rendertype_entity_cutout_no_cull_z_offset", new int[]{0, FORMAT_ENTITY}), Map.entry("rendertype_entity_translucent", new int[]{0, FORMAT_ENTITY}),
            Map.entry("rendertype_entity_smooth_cutout", new int[]{0, FORMAT_ENTITY}), Map.entry("rendertype_entity_solid", new int[]{1, FORMAT_ENTITY}),
            Map.entry("rendertype_entity_translucent_cull", new int[]{2, FORMAT_ENTITY}), Map.entry("rendertype_armor_cutout_no_cull", new int[]{2, FORMAT_ENTITY}),
            Map.entry("rendertype_entity_no_outline", new int[]{3, FORMAT_ENTITY}), Map.entry("rendertype_text", new int[]{2, FORMAT_TEXT}),
            Map.entry("particle", new int[]{2, FORMAT_PARTICLE}));
    enum Window { ENTITIES, PARTICLES, WEATHER }
    private static Window open;
    private static boolean frameActive, disabled;
    private static int lightmap;
    private static final List<Object[]> packets = new ArrayList<>();
    private static long captured, unsupported, stateFallbacks, textureFallbacks, composites;
    private static final long[] windowDraws = new long[Window.values().length], windowComposites = new long[Window.values().length];
    private static final Map<String, Long> fallbackShaders = new TreeMap<>(), fallbackStates = new TreeMap<>();
    private static final String[] STATE_REASONS = {"", "blend", "depth-func", "cull-mode", "offset/scissor/logic-op", "color-mask", "lightmap"};
    private static final float[] matrix3 = new float[9];
    private EntityDraws() { }

    /** RenderType.end() (entities, block entities, item pickups inside the particle window). */
    public static void draw(RenderType type, BufferBuilder.RenderedBuffer rendered) { drawImmediate(rendered); }
    /** Tesselator.end() (particles, rain/snow) - and every other Tesselator user outside a capture window. */
    public static void drawImmediate(BufferBuilder.RenderedBuffer rendered) {
        if (open == null || !RenderSystem.isOnRenderThread() || !capture(rendered)) BufferUploader.drawWithShader(rendered);
    }

    /** After this frame's Vulkan terrain composite; {@code lightmapTexture} is the lightmap WorldCapture mirrors. Opens ENTITIES. */
    static void beginFrame(int lightmapTexture) {
        packets.clear(); lightmap = lightmapTexture; frameActive = !disabled;
        open(Window.ENTITIES);
    }
    /** Later windows only open in frames whose terrain went through Vulkan (the overlay pass needs this frame's terrain depth). */
    static void open(Window window) { packets.clear(); open = frameActive ? window : null; }
    /** Replays the open window in Vulkan, composites it over GL with depth, and releases the shared images for the next window. */
    static void flush(Window window, TerrainPresenter presenter) {
        if (open != window) return;
        open = null;
        if (packets.isEmpty()) return;
        int[] textures = NativeMainWindow.renderOverlay(packets.toArray());
        packets.clear();
        if (textures == null) return;
        try { presenter.drawTranslucent(textures[0], textures[1]); }
        finally { NativeMainWindow.finishOverlay(); }
        composites++; windowComposites[window.ordinal()]++;
    }
    static void cancel() { open = null; frameActive = false; packets.clear(); }
    static void disable() { cancel(); disabled = true; }
    /** Preview restart: allow capture again. */
    static void reset() { cancel(); disabled = false; }
    static long[] stats() { return new long[]{captured, unsupported, stateFallbacks, textureFallbacks, composites}; }
    /** {draws captured, overlays composited} for one window. */
    static long[] stats(Window window) { return new long[]{windowDraws[window.ordinal()], windowComposites[window.ordinal()]}; }
    static Map<String, Long> fallbackShaders() { return Collections.unmodifiableMap(fallbackShaders); }
    /** "shader:reason" -> count for draws whose GL state Vulkan cannot reproduce yet. */
    static Map<String, Long> fallbackStates() { return Collections.unmodifiableMap(fallbackStates); }

    private static boolean capture(BufferBuilder.RenderedBuffer rendered) {
        var shader = RenderSystem.getShader();
        int[] spec = shader == null ? null : SHADERS.get(shader.getName());
        BufferBuilder.DrawState state = rendered.drawState();
        VertexFormat format = state.format();
        VertexFormat expected = spec == null ? null : spec[1] == FORMAT_ENTITY ? DefaultVertexFormat.NEW_ENTITY
                : spec[1] == FORMAT_TEXT ? DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP : DefaultVertexFormat.PARTICLE;
        if (spec == null || state.indexOnly() || format != expected
                || (state.mode() != VertexFormat.Mode.QUADS && state.mode() != VertexFormat.Mode.TRIANGLES)) {
            unsupported++;
            if (shader != null) fallbackShaders.merge(shader.getName(), 1L, Long::sum);
            return false;
        }
        int flags = glState();
        if (flags >= 0 && RenderSystem.getShaderTexture(2) != lightmap) flags = -6;
        if (flags < 0) {
            stateFallbacks++;
            fallbackStates.merge(shader.getName() + ":" + STATE_REASONS[-flags], 1L, Long::sum);
            return false;
        }
        int variant = spec[0];
        boolean entity = spec[1] == FORMAT_ENTITY;
        int texture = RenderSystem.getShaderTexture(0), overlay = entity ? RenderSystem.getShaderTexture(1) : texture;
        // Both checks run so a missing overlay is requested in the same frame as a missing main texture.
        boolean mirrored = EntityTextures.ready(texture);
        mirrored &= EntityTextures.ready(overlay);
        if (!mirrored) { textureFallbacks++; return false; }
        int[] header = new int[HEADER];
        header[H_FORMAT] = spec[1]; header[H_VARIANT] = variant; header[H_FLAGS] = flags;
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
        captured++; windowDraws[open.ordinal()]++;
        return true;
    }

    /** Pipeline flags for the current GL state, or a negative STATE_REASONS index when Vulkan cannot reproduce it. */
    private static int glState() {
        int flags = 0;
        if (glIsEnabled(GL_BLEND)) {
            int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
            int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
            // TRANSLUCENT_TRANSPARENCY and defaultBlendFunc() (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO) both become premultiplied
            // "over": their color blend is identical, and the main framebuffer's alpha channel they differ in is never displayed.
            if (srcRGB != GL_SRC_ALPHA || dstRGB != GL_ONE_MINUS_SRC_ALPHA || (srcAlpha != GL_ONE && srcAlpha != GL_SRC_ALPHA)
                    || (dstAlpha != GL_ONE_MINUS_SRC_ALPHA && dstAlpha != GL_ZERO)) return -1;
            flags |= BLEND;
        }
        if (glIsEnabled(GL_DEPTH_TEST)) {
            int func = glGetInteger(GL_DEPTH_FUNC);
            if (func != GL_LEQUAL && func != GL_LESS) return -2;
            flags |= DEPTH_TEST;
        }
        if (glGetBoolean(GL_DEPTH_WRITEMASK)) flags |= DEPTH_WRITE;
        if (glIsEnabled(GL_CULL_FACE)) {
            if (glGetInteger(GL_CULL_FACE_MODE) != GL_BACK || glGetInteger(GL_FRONT_FACE) != GL_CCW) return -3;
            flags |= CULL;
        }
        if (glIsEnabled(GL_POLYGON_OFFSET_FILL) || glIsEnabled(GL_SCISSOR_TEST) || glIsEnabled(GL_COLOR_LOGIC_OP)) return -4;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) if (mask.get(i) == 0) return -5;
        }
        return flags;
    }

    private static byte[] copy(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }
}
