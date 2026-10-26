package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;

/**
 * P7-P10 draw capture (coremods/entities.js): RenderType.end(), Tesselator.end(), and LevelRenderer's direct BufferUploader and
 * sky/cloud VertexBuffer draws (via SkyBuffers). Supported draws inside a capture window are copied for Vulkan instead of being
 * drawn by GL:
 * <ul><li>SKY: ViewportEvent.ComputeFogColor to AFTER_SKY - renderSky (P10); drawn under terrain in the next terrain submit;</li>
 * <li>ENTITIES: Vulkan terrain composite (AFTER_CUTOUT_BLOCKS) to AFTER_BLOCK_ENTITIES - entities and block entities (P7/P8);</li>
 * <li>PARTICLES: AFTER_TRIPWIRE_BLOCKS to AFTER_PARTICLES - ParticleEngine.render (P9);</li>
 * <li>WEATHER: AFTER_PARTICLES to AFTER_WEATHER - clouds (P10) and rain/snow (P9);</li>
 * <li>HAND: counters for GuiDraws' final-swapchain held items, arms and screen effects (P11).</li>
 * Hand and GUI capture are handled by GuiDraws and replayed directly into the swapchain (P11).</ul>
 * World windows are replayed as overlay passes over this frame's terrain depth and composited with depth when they close.
 * Legacy ordered overlay helpers start from cleared depth and flush before GL fallback. GuiDraws instead replays the deferred
 * hand and GUI together when required, so GL and Vulkan draws keep vanilla's painter's order. Anything else - other
 * shaders, formats, blend/depth states or not-yet-mirrored textures - is drawn by GL exactly as before.
 * Packets match the backend's EntityPass.
 */
public final class EntityDraws {
    // EntityPass header indices / constants (the lab classes live in an isolated class loader).
    private static final int H_FORMAT = 0, H_VARIANT = 1, H_FLAGS = 2, H_TEXTURE = 3, H_OVERLAY = 4, H_VERTICES = 5, H_MODE = 6, H_INDICES = 7, H_INDEX_SIZE = 8, HEADER = 9;
    private static final int FORMAT_ENTITY = 0, FORMAT_TEXT = 1, FORMAT_PARTICLE = 2, FORMAT_POSITION = 3, FORMAT_POSITION_COLOR = 4,
            FORMAT_POSITION_TEX = 5, FORMAT_POSITION_TEX_COLOR = 6, FORMAT_CLOUDS = 7, FORMAT_POSITION_COLOR_TEX = 8,
            MODE_QUADS = 0, MODE_TRIANGLES = 1, WHITE_TEXTURE = 0;
    /** Overlay passes per frame; the backend allows 16 (SharedWorldRenderer.MAX_OVERLAYS), one is kept spare. */
    private static final int MAX_PASSES = 15;
    private static final int BLEND = 1, DEPTH_TEST = 2, DEPTH_WRITE = 4, CULL = 8, BLEND_ADD = 16, NO_COLOR = 32;
    /** fragment variant (see entity.frag), EntityPass format, required VertexFormat, and which vanilla inputs the shader uses. */
    private record Spec(int variant, int format, VertexFormat vertexFormat, boolean lightmap, boolean fog, boolean textured) { }
    private static final Spec ENTITY = new Spec(0, FORMAT_ENTITY, DefaultVertexFormat.NEW_ENTITY, true, true, true);
    private static final Map<String, Spec> SHADERS = Map.ofEntries(
            Map.entry("rendertype_entity_cutout", ENTITY), Map.entry("rendertype_entity_cutout_no_cull", ENTITY),
            Map.entry("rendertype_entity_cutout_no_cull_z_offset", ENTITY), Map.entry("rendertype_entity_translucent", ENTITY),
            Map.entry("rendertype_entity_smooth_cutout", ENTITY),
            Map.entry("rendertype_entity_solid", new Spec(1, FORMAT_ENTITY, DefaultVertexFormat.NEW_ENTITY, true, true, true)),
            Map.entry("rendertype_entity_translucent_cull", new Spec(2, FORMAT_ENTITY, DefaultVertexFormat.NEW_ENTITY, true, true, true)),
            Map.entry("rendertype_armor_cutout_no_cull", new Spec(2, FORMAT_ENTITY, DefaultVertexFormat.NEW_ENTITY, true, true, true)),
            Map.entry("rendertype_entity_no_outline", new Spec(3, FORMAT_ENTITY, DefaultVertexFormat.NEW_ENTITY, true, true, true)),
            Map.entry("rendertype_text", new Spec(2, FORMAT_TEXT, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, true, true, true)),
            Map.entry("particle", new Spec(2, FORMAT_PARTICLE, DefaultVertexFormat.PARTICLE, true, true, true)),
            // P10 sky and clouds. position: sky/star/dark discs (fog); position_color: sunrise fan; position_tex: sun and moon;
            // position_tex_color: end sky; position_tex_color_normal: clouds (fog).
            Map.entry("position", new Spec(3, FORMAT_POSITION, DefaultVertexFormat.POSITION, false, true, false)),
            Map.entry("position_color", new Spec(4, FORMAT_POSITION_COLOR, DefaultVertexFormat.POSITION_COLOR, false, false, false)),
            Map.entry("position_tex", new Spec(4, FORMAT_POSITION_TEX, DefaultVertexFormat.POSITION_TEX, false, false, true)),
            Map.entry("position_tex_color", new Spec(6, FORMAT_POSITION_TEX_COLOR, DefaultVertexFormat.POSITION_TEX_COLOR, false, false, true)),
            Map.entry("position_tex_color_normal", new Spec(2, FORMAT_CLOUDS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, false, true, true)),
            // P11 GUI: fills/gradients/overlays (rendertype_gui*), colored blits and screen effects (position_color_tex), name tags.
            Map.entry("rendertype_gui", new Spec(4, FORMAT_POSITION_COLOR, DefaultVertexFormat.POSITION_COLOR, false, false, false)),
            Map.entry("rendertype_gui_overlay", new Spec(4, FORMAT_POSITION_COLOR, DefaultVertexFormat.POSITION_COLOR, false, false, false)),
            Map.entry("rendertype_gui_ghost_recipe_overlay", new Spec(4, FORMAT_POSITION_COLOR, DefaultVertexFormat.POSITION_COLOR, false, false, false)),
            Map.entry("position_color_tex", new Spec(6, FORMAT_POSITION_COLOR_TEX, DefaultVertexFormat.POSITION_COLOR_TEX, false, false, true)),
            Map.entry("rendertype_text_intensity", new Spec(7, FORMAT_TEXT, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, true, true, true)),
            Map.entry("rendertype_text_intensity_see_through", new Spec(7, FORMAT_TEXT, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, false, false, true)),
            Map.entry("rendertype_text_see_through", new Spec(2, FORMAT_TEXT, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, false, false, true)));
    enum Window {
        SKY, ENTITIES, PARTICLES, WEATHER, HAND;
        /** Starts from cleared depth and must keep painter's order with GL draws (splits on fallback). */
        boolean ordered() { return ordinal() >= HAND.ordinal(); }
    }
    private static TerrainPresenter presenter;
    /** Test hook (smoke source set only): sees each overlay's {color, depth} GL textures right before they are composited. */
    static java.util.function.BiConsumer<Window, int[]> overlayProbe;
    /** Test hook: right after an overlay was composited. */
    static java.util.function.Consumer<Window> overlayProbeAfter;
    private static int passes;
    private static long splits;
    private static Window open;
    private static boolean frameActive, skyArmed, disabled;
    private static int lightmap;
    private static final List<Object[]> packets = new ArrayList<>(), sky = new ArrayList<>();
    private static long captured, unsupported, stateFallbacks, textureFallbacks, composites;
    private static final long[] windowDraws = new long[Window.values().length], windowComposites = new long[Window.values().length];
    private static final Map<String, Long> capturedShaders = new TreeMap<>(), fallbackShaders = new TreeMap<>(), fallbackStates = new TreeMap<>();
    private static final String[] STATE_REASONS = {"", "blend", "depth-func", "cull-mode", "offset/scissor/logic-op", "color-mask", "lightmap"};
    private static final float[] matrix3 = new float[9];
    private EntityDraws() { }

    /** RenderType.end() (entities, block entities, item pickups inside the particle window). */
    public static void draw(RenderType type, BufferBuilder.RenderedBuffer rendered) { drawImmediate(rendered); }
    /** Tesselator.end() and LevelRenderer's direct uploads - and every such draw outside a capture window. */
    public static void drawImmediate(BufferBuilder.RenderedBuffer rendered) {
        if (GuiDraws.active() && RenderSystem.isOnRenderThread()) { GuiDraws.draw(rendered); return; }
        if (open != null && RenderSystem.isOnRenderThread() && capture(rendered)) return;
        split();
        BufferUploader.drawWithShader(rendered);
    }
    /** SkyBuffers: a static VertexBuffer drawn with its own matrices. False when GL must draw it. */
    static boolean drawMesh(SkyBuffers.Mesh mesh, Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        if (open == null || !RenderSystem.isOnRenderThread()) return false;
        Object[] packet = packet(shader, mesh.format(), mesh.mode(), mesh.indices() == null, modelView, projection);
        if (packet == null) { split(); return false; }
        add(packet, mesh.mode(), mesh.vertexCount(), mesh.vertices(), mesh.indices(), mesh.indexCount(), mesh.indexSize(), shader);
        return true;
    }

    /** Armed by the previous frame's Vulkan terrain composite: this frame's sky can be drawn by the terrain submit. */
    static void armSky(boolean armed) { skyArmed = armed && !disabled; if (!skyArmed) sky.clear(); }
    /** ComputeFogColor, just before LevelRenderer clears and draws the sky. */
    static void openSky() { sky.clear(); if (skyArmed && open == null) open = Window.SKY; }
    /** AFTER_SKY. */
    static void closeSky() { if (open == Window.SKY) open = null; }
    /** Sky packets for this frame's terrain submit (empty when none were captured). */
    static Object[] takeSky() { Object[] result = sky.toArray(); sky.clear(); if (result.length > 0) windowComposites[Window.SKY.ordinal()]++; return result; }
    /** After this frame's Vulkan terrain composite; {@code lightmapTexture} is the lightmap WorldCapture mirrors. Opens ENTITIES. */
    static void beginFrame(int lightmapTexture, TerrainPresenter compositor) {
        packets.clear(); lightmap = lightmapTexture; frameActive = !disabled; presenter = compositor; passes = 0;
        open(Window.ENTITIES);
    }
    /** P11: opens an order-sensitive window, flushing whichever one is still open (e.g. HAND when the GUI starts). */
    static void openOrdered(Window window) {
        if (open != null && open.ordered()) flush(open, presenter);
        if (open == null) open(window);
    }
    /** Flushes {@code window} if it is the open one (RenderGuiEvent.Post, ScreenEvent.Render.Post, swap). */
    static void closeOrdered(Window window) { if (open == window) flush(window, presenter); }
    static Window openWindow() { return open; }
    static boolean frameActive() { return frameActive; }
    static void lateHand(int draws) { windowDraws[Window.HAND.ordinal()] += draws; windowComposites[Window.HAND.ordinal()]++; }
    /** GL is about to draw something it must draw itself: keep painter's order by compositing what was captured before it. */
    private static void split() {
        if (open == null || !open.ordered() || packets.isEmpty() || !RenderSystem.isOnRenderThread()) return;
        Window window = open;
        flush(window, presenter);
        splits++;
        // Out of passes: the rest of this frame's window stays on GL, still in order.
        if (passes < MAX_PASSES) open = window;
    }
    /** Overlay windows only open in frames whose terrain went through Vulkan (the overlay pass needs this frame's terrain depth). */
    static void open(Window window) { packets.clear(); open = frameActive ? window : null; }
    /** Replays the open window in Vulkan, composites it over GL with depth, and releases the shared images for the next window. */
    static void flush(Window window, TerrainPresenter presenter) {
        if (open != window) return;
        open = null;
        if (packets.isEmpty()) return;
        int[] textures = passes < MAX_PASSES ? NativeMainWindow.renderOverlay(packets.toArray(), window.ordered()) : null;
        packets.clear();
        if (textures == null) return;
        passes++;
        if (overlayProbe != null) overlayProbe.accept(window, textures);
        try {
            presenter.drawTranslucent(textures[0], textures[1]);
        }
        finally { NativeMainWindow.finishOverlay(); }
        if (overlayProbeAfter != null) overlayProbeAfter.accept(window);
        composites++; windowComposites[window.ordinal()]++;
    }
    static void cancel() { open = null; frameActive = false; packets.clear(); }
    static void disable() { cancel(); armSky(false); disabled = true; }
    /** Preview restart: allow capture again. */
    static void reset() { cancel(); armSky(false); disabled = false; }
    static long[] stats() { return new long[]{captured, unsupported, stateFallbacks, textureFallbacks, composites, splits}; }
    /** {draws captured, passes (overlay composites, or terrain submits carrying sky)} for one window. */
    static long[] stats(Window window) { return new long[]{windowDraws[window.ordinal()], windowComposites[window.ordinal()]}; }
    static Map<String, Long> capturedShaders() { return Collections.unmodifiableMap(capturedShaders); }
    static Map<String, Long> fallbackShaders() { return Collections.unmodifiableMap(fallbackShaders); }
    /** "shader:reason" -> count for draws whose GL state Vulkan cannot reproduce yet. */
    static Map<String, Long> fallbackStates() { return Collections.unmodifiableMap(fallbackStates); }

    private static boolean capture(BufferBuilder.RenderedBuffer rendered) {
        var shader = RenderSystem.getShader();
        BufferBuilder.DrawState state = rendered.drawState();
        if (state.indexOnly()) { reject(shader); return false; }
        Object[] packet = packet(shader, state.format(), state.mode(), state.sequentialIndex(), RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());
        if (packet == null) return false;
        add(packet, state.mode(), state.vertexCount(), copy(rendered.vertexBuffer()), state.sequentialIndex() ? null : copy(rendered.indexBuffer()),
                state.indexCount(), state.indexType().bytes, shader);
        rendered.release();
        return true;
    }

    static Object[] guiPacket(BufferBuilder.RenderedBuffer rendered) {
        var state = rendered.drawState();
        if (state.indexOnly()) return null;
        var shader = RenderSystem.getShader();
        Object[] result = packet(shader, state.format(), state.mode(), state.sequentialIndex(), RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());
        if (result == null || state.mode() == VertexFormat.Mode.TRIANGLE_FAN) return null;
        int[] header = (int[]) result[0];
        header[H_VERTICES] = state.vertexCount(); header[H_MODE] = state.mode() == VertexFormat.Mode.QUADS ? MODE_QUADS : MODE_TRIANGLES;
        header[H_INDICES] = state.indexCount(); header[H_INDEX_SIZE] = state.indexType().bytes;
        result[2] = copy(rendered.vertexBuffer()); result[3] = state.sequentialIndex() ? null : copy(rendered.indexBuffer());
        captured++; capturedShaders.merge(shader.getName(), 1L, Long::sum);
        return result;
    }

    private static void reject(ShaderInstance shader) {
        unsupported++;
        if (shader != null) fallbackShaders.merge(shader.getName(), 1L, Long::sum);
    }

    /** Header + uniforms for a supported draw in the current GL/RenderSystem state, or null (counted) when GL must draw it. */
    private static Object[] packet(ShaderInstance shader, VertexFormat format, VertexFormat.Mode mode, boolean sequential, Matrix4f modelView, Matrix4f projection) {
        // Overlay windows can only be replayed while this frame still has passes left; the sky rides the terrain submit.
        if (!GuiDraws.active() && open != Window.SKY && passes >= MAX_PASSES) return null;
        Spec spec = shader == null ? null : SHADERS.get(shader.getName());
        boolean fan = mode == VertexFormat.Mode.TRIANGLE_FAN;
        if (spec == null || format != spec.vertexFormat() || (fan && !sequential)
                || (mode != VertexFormat.Mode.QUADS && mode != VertexFormat.Mode.TRIANGLES && !fan)) { reject(shader); return null; }
        ShaderBlendState.apply(shader); // Match the blend change vanilla performs inside ShaderInstance.apply.
        int flags = glState();
        if (flags >= 0 && (!GuiDraws.active() || GuiDraws.handActive()) && spec.lightmap() && RenderSystem.getShaderTexture(2) != lightmap) flags = -6;
        if (flags < 0) {
            stateFallbacks++;
            String reason = STATE_REASONS[-flags];
            if (flags == -1) reason += "(" + glGetInteger(GL_BLEND_SRC_RGB) + "," + glGetInteger(GL_BLEND_DST_RGB) + "," + glGetInteger(GL_BLEND_SRC_ALPHA) + "," + glGetInteger(GL_BLEND_DST_ALPHA) + ")";
            fallbackStates.merge(shader.getName() + ":" + reason, 1L, Long::sum);
            return null;
        }
        int texture = spec.textured() ? RenderSystem.getShaderTexture(0) : WHITE_TEXTURE;
        int overlay = spec.format() == FORMAT_ENTITY ? RenderSystem.getShaderTexture(1) : texture;
        // Both checks run so a missing overlay is requested in the same frame as a missing main texture.
        boolean mirrored = texture == WHITE_TEXTURE || EntityTextures.ready(texture);
        mirrored &= overlay == WHITE_TEXTURE || EntityTextures.ready(overlay);
        if (!mirrored) { textureFallbacks++; return null; }
        int[] header = new int[HEADER];
        header[H_FORMAT] = spec.format(); header[H_VARIANT] = spec.variant(); header[H_FLAGS] = flags;
        header[H_TEXTURE] = texture; header[H_OVERLAY] = overlay;
        float[] uniforms = new float[64];
        projection.get(uniforms, 0);
        modelView.get(uniforms, 16);
        RenderSystem.getInverseViewRotationMatrix().get(matrix3, 0);
        for (int column = 0; column < 3; column++) System.arraycopy(matrix3, column * 3, uniforms, 32 + column * 4, 3);
        System.arraycopy(RenderSystem.getShaderColor(), 0, uniforms, 44, 4);
        if (spec.format() == FORMAT_ENTITY) {
            RenderSystem.setupShaderLights(shader);
            if (shader.LIGHT0_DIRECTION != null) shader.LIGHT0_DIRECTION.getFloatBuffer().get(0, uniforms, 48, 3);
            if (shader.LIGHT1_DIRECTION != null) shader.LIGHT1_DIRECTION.getFloatBuffer().get(0, uniforms, 52, 3);
        }
        uniforms[55] = (flags & BLEND_ADD) != 0 ? 2 : (flags & BLEND) != 0 ? 0 : 1;
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, uniforms, 56, 4);
        // Vanilla shaders without linear_fog get a fog start beyond any distance.
        uniforms[60] = spec.fog() ? RenderSystem.getShaderFogStart() : 1e30f; uniforms[61] = spec.fog() ? RenderSystem.getShaderFogEnd() : 2e30f;
        uniforms[62] = RenderSystem.getShaderFogShape().getIndex(); uniforms[63] = spec.variant();
        return new Object[]{header, uniforms, null, null};
    }

    /** Completes a packet with geometry; TRIANGLE_FAN (sky discs, sunrise) becomes an indexed triangle list (0, i, i + 1). */
    private static void add(Object[] packet, VertexFormat.Mode mode, int vertexCount, byte[] vertices, byte[] indices, int indexCount, int indexSize, ShaderInstance shader) {
        int[] header = (int[]) packet[0];
        header[H_VERTICES] = vertexCount;
        header[H_MODE] = mode == VertexFormat.Mode.QUADS ? MODE_QUADS : MODE_TRIANGLES;
        if (mode == VertexFormat.Mode.TRIANGLE_FAN) {
            int triangles = Math.max(0, vertexCount - 2);
            ByteBuffer fan = ByteBuffer.allocate(triangles * 12).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 1; i <= triangles; i++) fan.putInt(0).putInt(i).putInt(i + 1);
            indices = fan.array(); indexCount = triangles * 3; indexSize = 4;
        }
        if (indices != null) { header[H_INDICES] = indexCount; header[H_INDEX_SIZE] = indexSize; }
        packet[2] = vertices; packet[3] = indices;
        (open == Window.SKY ? sky : packets).add(packet);
        captured++; windowDraws[open.ordinal()]++;
        capturedShaders.merge(shader.getName(), 1L, Long::sum);
    }

    /** Pipeline flags for the current GL state, or a negative STATE_REASONS index when Vulkan cannot reproduce it. */
    private static int glState() {
        int flags = 0;
        if (glIsEnabled(GL_BLEND)) {
            if (glGetInteger(org.lwjgl.opengl.GL20.GL_BLEND_EQUATION_RGB) != GL_FUNC_ADD || glGetInteger(org.lwjgl.opengl.GL20.GL_BLEND_EQUATION_ALPHA) != GL_FUNC_ADD) return -1;
            int srcRGB = glGetInteger(GL_BLEND_SRC_RGB), dstRGB = glGetInteger(GL_BLEND_DST_RGB);
            int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
            // TRANSLUCENT_TRANSPARENCY and defaultBlendFunc() (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO) both become premultiplied
            // "over": their color blend is identical, and the main framebuffer's alpha channel they differ in is never displayed.
            // (SRC_ALPHA, ONE) is the sun/moon/star additive blend; its alpha factors likewise only touch the hidden alpha channel.
            boolean alphaFactors = (srcAlpha == GL_ONE || srcAlpha == GL_SRC_ALPHA) && (dstAlpha == GL_ONE_MINUS_SRC_ALPHA || dstAlpha == GL_ZERO);
            if (srcRGB == GL_SRC_ALPHA && dstRGB == GL_ONE_MINUS_SRC_ALPHA && alphaFactors) flags |= BLEND;
            else if (srcRGB == GL_SRC_ALPHA && dstRGB == GL_ONE) flags |= BLEND_ADD;
            else if (GuiDraws.active() && srcRGB == GL_ONE_MINUS_DST_COLOR && dstRGB == GL_ONE_MINUS_SRC_COLOR) flags |= 64;
            else if (GuiDraws.active() && srcRGB == GL_ZERO && dstRGB == GL_ONE_MINUS_SRC_COLOR) flags |= 128;
            else return -1;
        }
        if (glIsEnabled(GL_DEPTH_TEST)) {
            int func = glGetInteger(GL_DEPTH_FUNC);
            if (func != GL_LEQUAL && func != GL_LESS) return -2;
            flags |= DEPTH_TEST;
            if (func == GL_LESS) flags |= 256;
        }
        if (glGetBoolean(GL_DEPTH_WRITEMASK)) flags |= DEPTH_WRITE;
        if (glIsEnabled(GL_CULL_FACE)) {
            if (glGetInteger(GL_CULL_FACE_MODE) != GL_BACK || glGetInteger(GL_FRONT_FACE) != GL_CCW) return -3;
            flags |= CULL;
        }
        if (glIsEnabled(GL_POLYGON_OFFSET_FILL) || (!GuiDraws.active() && glIsEnabled(GL_SCISSOR_TEST)) || glIsEnabled(GL_COLOR_LOGIC_OP)) return -4;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            int written = 0;
            for (int i = 0; i < 4; i++) if (mask.get(i) != 0) written++;
            // All channels, or none (fancy clouds' depth-only prepass).
            if (written == 0) flags |= NO_COLOR;
            else if (written != 4) return -5;
        }
        return flags;
    }

    private static byte[] copy(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }
}
