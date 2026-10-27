package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.renderer.ShaderInstance;
import static org.lwjgl.opengl.GL46C.*;

/**
 * Reads vanilla's own GL state caches (GlStateManager and ShaderInstance.lastProgramId) instead of calling glGet*, which
 * round-trips to the driver thread under NVIDIA's threaded optimization. Fields are found by type and declaration order
 * (names are SRG in production). {@link #trusted()} is false when the layout is unexpected or the caches disagree with GL;
 * callers then query GL instead.
 */
final class GlStateCache {
    /** {blend, depthTest, depthMask, cull, polygonOffsetFill, scissor, colorLogic, colorMaskAll} plus the ints below. */
    record Snapshot(int program, boolean blend, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha, boolean depthTest, int depthFunc,
                    boolean depthMask, boolean cull, boolean polygonOffset, boolean scissor, boolean colorLogic, boolean colorMaskAll,
                    int texture0, int texture1, int texture2) { }

    private static final boolean VERIFY = Boolean.getBoolean("vulkanforge.gl46.verifyState");
    private static Field blendMode, blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, depthMode, depthMask, depthFunc, cullEnable,
            offsetFill, scissorMode, logicEnable, maskRed, maskGreen, maskBlue, maskAlpha, textureBinding, booleanEnabled, lastProgram;
    private static Object blend, depth, cull, offset, scissor, logic, colorMask;
    private static Object[] textures;
    private static boolean trusted;
    private static String problem = "not initialised";
    private static long verified, reads, queries, invalidations;

    private GlStateCache() { }

    /** Render thread, once per renderer creation: resolve the fields and compare them with GL. */
    static synchronized boolean initialise() {
        try {
            if (blend == null) resolve();
            String mismatch = compare(read(), query());
            trusted = mismatch == null;
            problem = mismatch == null ? null : "cache disagrees with GL: " + mismatch;
        } catch (ReflectiveOperationException | RuntimeException error) {
            trusted = false; problem = "unexpected GlStateManager layout: " + error;
        }
        Gl46Context.report("STATE_CACHE=" + (trusted ? "TRUSTED" : "QUERY (" + problem + ")") + " verify=" + VERIFY);
        return trusted;
    }

    static boolean trusted() { return trusted; }
    static boolean verifying() { return VERIFY; }
    static long verified() { return verified; }
    /** Lifetime counters: {cache reads, real GL snapshots, verified boundaries, invalidations}. */
    static long[] stats() { return new long[]{reads, queries, verified, invalidations}; }

    /** Current state: cache reads when trusted (and, with -Dvulkanforge.gl46.verifyState, checked against GL), else glGet. */
    static Snapshot snapshot() {
        if (VERIFY) return boundarySnapshot();
        if (!trusted) return query();
        Snapshot cached;
        try { cached = read(); } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        return cached;
    }

    /** Authoritative pass boundary for differential restoration: foreign raw GL calls cannot poison the saved state. */
    static Snapshot boundarySnapshot() {
        Snapshot actual = query();
        if (trusted) {
            try {
                Snapshot cached = read();
                if (!cached.equals(actual)) invalidate("raw GL/cache mismatch: cache=" + cached + " gl=" + actual);
                else verified++;
            } catch (IllegalAccessException | RuntimeException error) { invalidate("cache read failed: " + error.getClass().getSimpleName()); }
        }
        return actual;
    }
    static Snapshot actualSnapshot() { return query(); }
    private static void invalidate(String reason) {
        invalidations++;
        trusted = false; problem = reason;
        Gl46Context.report("STATE_CACHE=INVALIDATED " + reason + " (using real GL snapshots)");
    }

    private static void resolve() throws ReflectiveOperationException {
        Map<String, List<Field>> byType = new HashMap<>();
        for (Field field : GlStateManager.class.getDeclaredFields())
            if (Modifier.isStatic(field.getModifiers())) byType.computeIfAbsent(field.getType().getSimpleName(), type -> new ArrayList<>()).add(field);
        blend = require(byType, "BlendState"); depth = require(byType, "DepthState"); cull = require(byType, "CullState");
        offset = require(byType, "PolygonOffsetState"); scissor = require(byType, "ScissorState"); logic = require(byType, "ColorLogicState");
        colorMask = require(byType, "ColorMask"); textures = (Object[]) require(byType, "TextureState[]");
        Class<?> booleanState = Arrays.stream(GlStateManager.class.getDeclaredClasses()).filter(type -> type.getSimpleName().equals("BooleanState"))
                .findFirst().orElseThrow(() -> new IllegalStateException("GlStateManager has no BooleanState"));
        booleanEnabled = only(booleanState, boolean.class, 0, 1);
        blendMode = only(blend.getClass(), booleanState, 0, 1);
        blendSrcRgb = only(blend.getClass(), int.class, 0, 4); blendDstRgb = only(blend.getClass(), int.class, 1, 4);
        blendSrcAlpha = only(blend.getClass(), int.class, 2, 4); blendDstAlpha = only(blend.getClass(), int.class, 3, 4);
        depthMode = only(depth.getClass(), booleanState, 0, 1); depthMask = only(depth.getClass(), boolean.class, 0, 1); depthFunc = only(depth.getClass(), int.class, 0, 1);
        cullEnable = only(cull.getClass(), booleanState, 0, 1);
        offsetFill = only(offset.getClass(), booleanState, 0, 2); // fill, then line
        scissorMode = only(scissor.getClass(), booleanState, 0, 1);
        logicEnable = only(logic.getClass(), booleanState, 0, 1);
        maskRed = only(colorMask.getClass(), boolean.class, 0, 4); maskGreen = only(colorMask.getClass(), boolean.class, 1, 4);
        maskBlue = only(colorMask.getClass(), boolean.class, 2, 4); maskAlpha = only(colorMask.getClass(), boolean.class, 3, 4);
        textureBinding = only(textures[0].getClass(), int.class, 0, 1);
        List<Field> ints = new ArrayList<>();
        for (Field field : ShaderInstance.class.getDeclaredFields())
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && field.getType() == int.class) ints.add(field);
        if (ints.size() != 1) throw new IllegalStateException("ShaderInstance program cache: " + ints.size() + " candidates");
        lastProgram = ints.get(0); lastProgram.setAccessible(true);
    }
    private static Object require(Map<String, List<Field>> fields, String type) throws IllegalAccessException {
        List<Field> matches = fields.getOrDefault(type, List.of());
        if (matches.size() != 1) throw new IllegalStateException("GlStateManager has " + matches.size() + " " + type + " fields");
        Field field = matches.get(0); field.setAccessible(true);
        return field.get(null);
    }
    /** The index-th of exactly {@code count} instance fields of {@code type}, in declaration order. */
    private static Field only(Class<?> owner, Class<?> type, int index, int count) {
        List<Field> matches = new ArrayList<>();
        for (Field field : owner.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && field.getType() == type) matches.add(field);
        if (matches.size() != count) throw new IllegalStateException(owner.getSimpleName() + " has " + matches.size() + " " + type.getSimpleName() + " fields");
        Field field = matches.get(index); field.setAccessible(true);
        return field;
    }

    private static boolean enabled(Field state, Object owner) throws IllegalAccessException { return booleanEnabled.getBoolean(state.get(owner)); }

    private static Snapshot read() throws IllegalAccessException {
        reads++;
        int program = lastProgram.getInt(null);
        return new Snapshot(Math.max(program, 0), enabled(blendMode, blend), blendSrcRgb.getInt(blend), blendDstRgb.getInt(blend),
                blendSrcAlpha.getInt(blend), blendDstAlpha.getInt(blend), enabled(depthMode, depth), depthFunc.getInt(depth), depthMask.getBoolean(depth),
                enabled(cullEnable, cull), enabled(offsetFill, offset), enabled(scissorMode, scissor), enabled(logicEnable, logic),
                maskRed.getBoolean(colorMask) && maskGreen.getBoolean(colorMask) && maskBlue.getBoolean(colorMask) && maskAlpha.getBoolean(colorMask),
                textureBinding.getInt(textures[0]), textureBinding.getInt(textures[1]), textureBinding.getInt(textures[2]));
    }

    private static Snapshot query() {
        queries++;
        boolean maskAll;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            maskAll = mask.get(0) != 0 && mask.get(1) != 0 && mask.get(2) != 0 && mask.get(3) != 0;
        }
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] bound = new int[3];
        for (int unit = 0; unit < 3; unit++) { glActiveTexture(GL_TEXTURE0 + unit); bound[unit] = glGetInteger(GL_TEXTURE_BINDING_2D); }
        glActiveTexture(active);
        return new Snapshot(glGetInteger(GL_CURRENT_PROGRAM), glIsEnabled(GL_BLEND), glGetInteger(GL_BLEND_SRC_RGB), glGetInteger(GL_BLEND_DST_RGB),
                glGetInteger(GL_BLEND_SRC_ALPHA), glGetInteger(GL_BLEND_DST_ALPHA), glIsEnabled(GL_DEPTH_TEST), glGetInteger(GL_DEPTH_FUNC),
                glGetBoolean(GL_DEPTH_WRITEMASK), glIsEnabled(GL_CULL_FACE), glIsEnabled(GL_POLYGON_OFFSET_FILL), glIsEnabled(GL_SCISSOR_TEST),
                glIsEnabled(GL_COLOR_LOGIC_OP), maskAll, bound[0], bound[1], bound[2]);
    }

    /** Null when equal. Blend factors only matter while blending is enabled. */
    private static String compare(Snapshot cached, Snapshot gl) {
        if (cached.equals(gl)) return null;
        if (!cached.blend() && !gl.blend()) {
            Snapshot normalized = new Snapshot(gl.program(), false, cached.srcRGB(), cached.dstRGB(), cached.srcAlpha(), cached.dstAlpha(), gl.depthTest(),
                    gl.depthFunc(), gl.depthMask(), gl.cull(), gl.polygonOffset(), gl.scissor(), gl.colorLogic(), gl.colorMaskAll(), gl.texture0(), gl.texture1(), gl.texture2());
            if (cached.equals(normalized)) return null;
        }
        return "cache=" + cached + " gl=" + gl;
    }
}
