package github.com.gengyoubo.vulkanforge.client;

import static github.com.gengyoubo.vulkanforge.client.GlCompat.glBindTextureUnit;

import com.mojang.blaze3d.vertex.BufferUploader;
import static org.lwjgl.opengl.GL46C.*;

/**
 * GL state the OpenGL passes touch. Full mode uses the existing snapshot; opt-in differential mode queries the real boundary
 * and tracks only this owned pass's writes. No shadow state survives between passes or assumes third-party raw GL is cached.
 * Restoration uses plain GL calls, so GlStateManager/ShaderInstance caches are not rewritten. The vertex array is not restored:
 * BufferUploader is invalidated, and vanilla binds a VAO before each of its draws. Texture units 0-2 are Sampler0-2; the
 * active unit is never changed (DSA binds). Indirect/parameter/SSBO/UBO bindings are left as they are: vanilla uses none.
 */
final class Gl46State implements AutoCloseable {
    private static final boolean DIFF = Boolean.getBoolean("vulkanforge.gl46.diffState");
    private static Gl46State active;
    private static long fullScopes, diffScopes, restoreCalls, avoidedCalls;
    private final Gl46State previous = active;
    private final GlStateCache.Snapshot saved;
    private final int[] textures = new int[3], samplers = new int[3], savedSamplers = new int[3];
    private int program, vao, depthFunc, srcRGB, dstRGB, srcAlpha, dstAlpha;
    private boolean depth, cull, blend, depthMask, unknown, closed;

    Gl46State() {
        saved = DIFF ? previous == null ? GlStateCache.boundarySnapshot() : GlStateCache.actualSnapshot() : GlStateCache.snapshot();
        if (DIFF) {
            program = saved.program(); vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            textures[0] = saved.texture0(); textures[1] = saved.texture1(); textures[2] = saved.texture2();
            for (int unit=0;unit<3;unit++) samplers[unit] = savedSamplers[unit] = glGetIntegeri(GL_SAMPLER_BINDING, unit);
            depth=saved.depthTest();cull=saved.cull();blend=saved.blend();depthMask=saved.depthMask();depthFunc=saved.depthFunc();
            srcRGB=saved.srcRGB();dstRGB=saved.dstRGB();srcAlpha=saved.srcAlpha();dstAlpha=saved.dstAlpha();
            // All mutations inside these owned passes use the wrappers below. No shadow state survives the boundary.
            if (previous != null) previous.unknown = true;
            active = this; diffScopes++;
        } else fullScopes++;
    }
    static boolean differential() { return DIFF; }
    static long[] stats() { return new long[]{fullScopes,diffScopes,restoreCalls,avoidedCalls}; }
    /** Future callers that invoke foreign/raw callbacks inside a scope must request conservative full restoration. */
    static void invalidateCurrent() { if(active!=null) active.unknown=true; }
    private boolean needs(boolean different) { if(!DIFF||unknown||different) {restoreCalls++;return true;} avoidedCalls++;return false; }

    @Override public void close() {
        if(closed) return; closed=true;
        active=null;
        try {
            if(needs(program!=saved.program())) org.lwjgl.opengl.GL20C.glUseProgram(saved.program());
            if(needs(vao!=0)) org.lwjgl.opengl.GL30C.glBindVertexArray(0);
            BufferUploader.invalidate(); // Required even if the VAO was already zero.
            for(int unit=0;unit<3;unit++) if(needs(samplers[unit]!=savedSamplers[unit])) org.lwjgl.opengl.GL33C.glBindSampler(unit,savedSamplers[unit]);
            int[] bound={saved.texture0(),saved.texture1(),saved.texture2()};
            for(int unit=0;unit<3;unit++) if(needs(textures[unit]!=bound[unit])) glBindTextureUnit(unit,bound[unit]);
            if(needs(depth!=saved.depthTest())) rawToggle(GL_DEPTH_TEST,saved.depthTest());
            if(needs(cull!=saved.cull())) rawToggle(GL_CULL_FACE,saved.cull());
            if(needs(blend!=saved.blend())) rawToggle(GL_BLEND,saved.blend());
            if(needs(depthMask!=saved.depthMask())) org.lwjgl.opengl.GL11C.glDepthMask(saved.depthMask());
            if(needs(depthFunc!=saved.depthFunc())) org.lwjgl.opengl.GL11C.glDepthFunc(saved.depthFunc());
            if(needs(srcRGB!=saved.srcRGB()||dstRGB!=saved.dstRGB()||srcAlpha!=saved.srcAlpha()||dstAlpha!=saved.dstAlpha()))
                org.lwjgl.opengl.GL14C.glBlendFuncSeparate(saved.srcRGB(),saved.dstRGB(),saved.srcAlpha(),saved.dstAlpha());
        } finally { active=previous; }
    }

    static void toggle(int capability, boolean enabled) { if (enabled) glEnable(capability); else glDisable(capability); }
    private static void rawToggle(int capability, boolean enabled) {if(enabled)org.lwjgl.opengl.GL11C.glEnable(capability);else org.lwjgl.opengl.GL11C.glDisable(capability);}
    static void glUseProgram(int value) {org.lwjgl.opengl.GL20C.glUseProgram(value);if(active!=null)active.program=value;}
    static void glBindVertexArray(int value) {org.lwjgl.opengl.GL30C.glBindVertexArray(value);if(active!=null)active.vao=value;}
    static void glBindSampler(int unit,int value) {org.lwjgl.opengl.GL33C.glBindSampler(unit,value);if(active!=null&&unit<3)active.samplers[unit]=value;}
    static void textureBound(int unit,int value) {if(active!=null&&unit>=0&&unit<3)active.textures[unit]=value;}
    static void glEnable(int capability) {org.lwjgl.opengl.GL11C.glEnable(capability);enabled(capability,true);}
    static void glDisable(int capability) {org.lwjgl.opengl.GL11C.glDisable(capability);enabled(capability,false);}
    private static void enabled(int capability,boolean value) {
        if(active==null)return;
        switch(capability) {case GL_DEPTH_TEST->active.depth=value;case GL_CULL_FACE->active.cull=value;case GL_BLEND->active.blend=value;default->{}}
    }
    static void glDepthMask(boolean value) {org.lwjgl.opengl.GL11C.glDepthMask(value);if(active!=null)active.depthMask=value;}
    static void glDepthFunc(int value) {org.lwjgl.opengl.GL11C.glDepthFunc(value);if(active!=null)active.depthFunc=value;}
    static void glBlendFuncSeparate(int srcRGB,int dstRGB,int srcAlpha,int dstAlpha) {
        org.lwjgl.opengl.GL14C.glBlendFuncSeparate(srcRGB,dstRGB,srcAlpha,dstAlpha);
        if(active!=null) {active.srcRGB=srcRGB;active.dstRGB=dstRGB;active.srcAlpha=srcAlpha;active.dstAlpha=dstAlpha;}
    }
}
